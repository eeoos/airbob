#!/usr/bin/env python3
"""Runs only on the selected AWS hosts through SSM. Never provisions infrastructure."""
import argparse
import contextlib
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import ssl
import subprocess
import tarfile
import threading
import time
import urllib.error
import urllib.request

from aws_cache_contract import ACCOUNT, REGION, HERE, ROOT, local, need, validate


def command(argv, *, env=None, timeout=60, log=None):
    result = subprocess.run(argv, env=env, stdout=subprocess.PIPE if log is None else log,
                            stderr=subprocess.PIPE if log is None else log, timeout=timeout, text=True)
    need(result.returncode == 0, 'Host command failed: ' + Path(argv[0]).name)
    return result.stdout or ''


def write(path, value):
    path = Path(path)
    need(not path.is_symlink(), 'Unsafe host file')
    temporary = path.with_name(path.name + '.' + str(os.getpid()) + '.tmp')
    try:
        with os.fdopen(os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as stream:
            stream.write(value if isinstance(value, str) else json.dumps(value, indent=2) + '\n')
            stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def env_file(path):
    need(path.is_file() and not path.is_symlink(), 'Runtime environment missing')
    values = dict(line.split('=', 1) for line in path.read_text().splitlines()
                  if '=' in line and not line.startswith('#'))
    return values


def measurement_metrics(raw):
    names = {'accommodation_detail_cache_request_total', 'accommodation_detail_cache_load_duration_seconds_count',
             'accommodation_detail_cache_redis_operation_total', 'app_query_per_request_queries_count',
             'app_query_per_request_count', 'app_query_per_request_queries_sum', 'app_query_per_request_sum'}
    lines = []
    for line in raw.splitlines():
        name = re.split(r'[\s{]', line, maxsplit=1)[0]
        if name not in names: continue
        if name.startswith('app_query_') and 'path="/api/v1/accommodations/{accommodationId}"' not in line: continue
        if name == 'accommodation_detail_cache_redis_operation_total' and ('operation="get"' not in line or 'result="error"' not in line): continue
        lines.append(line)
    return '\n'.join(lines) + '\n'


def token(config):
    response = json.loads(command(['aws', '--region', REGION, 'ssm', 'get-parameter',
                                  '--name', config['tokenParameter'], '--with-decryption', '--output', 'json']))
    parameter = response['Parameter']
    need(parameter['Type'] == 'SecureString' and re.fullmatch(r'[0-9a-f]{64}', parameter['Value']),
         'Benchmark token must be a 64-hex SecureString')
    return parameter['Value']


def instance_identity():
    req = urllib.request.Request('http://169.254.169.254/latest/api/token', method='PUT',
                                 headers={'X-aws-ec2-metadata-token-ttl-seconds': '60'})
    with urllib.request.urlopen(req, timeout=3) as response:
        value = response.read().decode()
    req = urllib.request.Request('http://169.254.169.254/latest/dynamic/instance-identity/document',
                                 headers={'X-aws-ec2-metadata-token': value})
    with urllib.request.urlopen(req, timeout=3) as response:
        identity = json.load(response)
    need(identity['accountId'] == ACCOUNT and identity['region'] == REGION, 'Wrong AWS host identity')
    return identity['instanceId']


def benchmark_env(original, secret, variant):
    # Preserve JDBC TLS credentials and dependency endpoints, but remove profile/command overrides.
    result = {k: v for k, v in original.items() if k not in {
        'SPRING_PROFILES_INCLUDE', 'SPRING_APPLICATION_JSON', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS'}}
    result.update(SPRING_PROFILES_ACTIVE='aws,cache-benchmark', SPRING_FLYWAY_TARGET='28',
        BENCHMARK_READ_MODEL_TOKEN=secret,
        ACCOMMODATION_DETAIL_CACHE_ENABLED=str(variant != 'cache-off').lower(),
        CACHE_BENCHMARK_LOCAL_COALESCING_ENABLED=str(variant != 'coalescing-off').lower(),
        SPRING_DATASOURCE_HIKARI_READ_ONLY='true', SPRING_KAFKA_LISTENER_AUTO_STARTUP='false',
        OPERATOR_ALERT_KAFKA_AUTO_STARTUP='false', ACCOMMODATION_INDEXING_AUTO_STARTUP='false',
        ACCOMMODATION_DETAIL_CACHE_INVALIDATION_AUTO_STARTUP='false',
        RESERVATION_INVENTORY_STARTUP_ENABLED='false', RESERVATION_INVENTORY_SEED_ENABLED='false',
        RESERVATION_INVENTORY_RETENTION_ENABLED='false', TOSS_PAYMENTS_ENABLED='false',
        GOOGLE_API_ENABLED='false', AWS_S3_WRITE_ENABLED='false', OPERATOR_ALERT_SLACK_ENABLED='false',
        AWS_EC2_METADATA_DISABLED='true', SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY='dummy',
        SPRING_CLOUD_AWS_CREDENTIALS_SECRET_KEY='dummy', AWS_REGION=REGION,
        SPRING_CLOUD_AWS_REGION_STATIC=REGION,
        ACCOMMODATION_DETAIL_CACHE_TTL='10m', ACCOMMODATION_DETAIL_CACHE_TTL_JITTER='2m')
    need(result.get('REDIS_HOST') == 'redis-general.lab.airbob.internal' and result.get('REDIS_PORT') == '6379'
         and result.get('ACCOMMODATION_DETAIL_CACHE_REDIS_HOST') == 'redis-cache.lab.airbob.internal'
         and result.get('ACCOMMODATION_DETAIL_CACHE_REDIS_PORT') == '6380', 'Dedicated Redis separation required')
    return result


class Host:
    def __init__(self, config, config_path):
        self.c, self.config_path = config, Path(config_path).resolve()
        self.root = self.config_path.parent
        self.instance = instance_identity()
        need(self.instance in config['apps'] + [config['redisInstanceId'], config['loadGeneratorInstanceId']],
             'Host is outside the experiment')
        self.state_path = self.root / 'host-state.json'
        self.state = {}
        self.unit = 'airbob-' + config['experimentId']

    def save(self):
        write(self.state_path, self.state)

    def install_k6(self):
        need(self.instance == self.c['loadGeneratorInstanceId'] and platform.machine() == 'x86_64', 'k6 requires the selected AMD64 generator')
        pins = env_file(ROOT / 'infra/aws/toolchain.env')
        version, expected = pins['AIRBOB_K6_VERSION'], pins['AIRBOB_K6_LINUX_AMD64_SHA256']
        need(version == '1.5.0' and re.fullmatch('[0-9a-f]{64}', expected), 'Unexpected k6 pin')
        directory = self.root / 'tools'
        directory.mkdir(mode=0o700, exist_ok=True)
        executable = directory / 'k6'
        receipt = directory / 'pin.json'
        if executable.exists():
            need(receipt.is_file() and json.loads(receipt.read_text()) == {
                'archiveSha256': expected, 'binarySha256': hashlib.sha256(executable.read_bytes()).hexdigest()}, 'Installed k6 changed')
            return {'version': version, 'archiveSha256': expected}
        archive = directory / 'k6.tar.gz'
        source = 'https://github.com/grafana/k6/releases/download/v' + version + '/k6-v' + version + '-linux-amd64.tar.gz'
        total, sha = 0, hashlib.sha256()
        with urllib.request.urlopen(source, timeout=30) as response, archive.open('wb') as destination:
            while chunk := response.read(1024 * 1024):
                total += len(chunk)
                need(total <= 128 * 1024 * 1024, 'k6 archive too large')
                sha.update(chunk); destination.write(chunk)
        need(sha.hexdigest() == expected, 'k6 download checksum mismatch')
        with tarfile.open(archive, 'r:gz') as source:
            name = 'k6-v' + version + '-linux-amd64/k6'
            members = source.getmembers()
            need({m.name.rstrip('/') for m in members} == {name, name.rsplit('/', 1)[0]}
                 and all(m.isfile() or m.isdir() for m in members), 'Unexpected k6 archive entries')
            binary = source.extractfile(name).read()
        executable.write_bytes(binary); executable.chmod(0o700)
        write(receipt, {'archiveSha256': expected, 'binarySha256': hashlib.sha256(binary).hexdigest()})
        archive.unlink()
        return {'version': version, 'archiveSha256': expected}

    def watchdog(self, seconds, arguments=None):
        # Survives controller interruption. Do not include the token in the unit or command arguments.
        deadline = json.loads((self.root / 'control.json').read_text())['deadlineEpoch']
        seconds = max(1, min(seconds, int(deadline - time.time())))
        command(['systemd-run', '--quiet', '--collect', '--unit', self.unit, '--on-active=' + str(seconds) + 's',
                 '/usr/bin/python3', str(Path(__file__).resolve()), str(self.config_path), 'recover', json.dumps(arguments or {})])

    def cancel_watchdog(self):
        subprocess.run(['systemctl', 'stop', self.unit + '.timer'], capture_output=True, timeout=15)
        subprocess.run(['systemctl', 'reset-failed', self.unit + '.service'], capture_output=True, timeout=15)

    def container(self, service):
        ids = command(['docker', 'ps', '-a', '--filter', 'label=com.docker.compose.service=' + service,
                       '--format', '{{.ID}}']).splitlines()
        need(len(ids) == 1, 'Exactly one ' + service + ' container required')
        return json.loads(command(['docker', 'inspect', ids[0]]))[0]

    def app_compose(self, environment):
        args = ['docker', 'compose', '--env-file', '/etc/airbob/images.env',
                '-f', '/opt/airbob/release/infra/aws/bundles/app/compose.yml']
        if Path('/etc/airbob/b-app-compose.yml').is_file():
            args += ['-f', '/etc/airbob/b-app-compose.yml']
        # Existing project is retained, including RDS trust mounts and CPU/memory limits.
        args += ['--project-name', self.state['composeProject']]
        with (self.root / 'app-control.log').open('a') as stream:
            command(args + ['up', '--detach', '--no-deps', '--force-recreate', '--wait',
                            '--wait-timeout', '300', 'app'],
                    env=os.environ | {'APP_ENV_FILE': str(environment)}, timeout=340, log=stream)

    def variant(self, variant):
        need(self.instance in self.c['apps'] and variant in {'cache-off', 'cache-on', 'coalescing-off', 'coalescing-on'},
             'Invalid application operation')
        original_path = Path('/etc/airbob/app.env')
        original = env_file(original_path)
        need(env_file(Path('/etc/airbob/images.env'))['APP_IMAGE'] == self.c['appImage'], 'Application image changed')
        current = self.container('app')
        need(current['Config']['Image'] == self.c['appImage'], 'Running image differs from the experiment image')
        revision = json.loads(command(['docker', 'image', 'inspect', self.c['appImage']]))[0]['Config']['Labels']
        need(revision.get('org.opencontainers.image.revision') == self.c['appCommit'], 'Application commit differs')
        original_sha = hashlib.sha256(original_path.read_bytes()).hexdigest()
        if not self.state:
            need('cache-benchmark' not in original.get('SPRING_PROFILES_ACTIVE', ''), 'Original app must not be an unfinished experiment')
            need(not any(e.startswith('SPRING_PROFILES_ACTIVE=') and 'cache-benchmark' in e
                         for e in current['Config']['Env']), 'Another cache experiment is still running')
            self.state = {'kind': 'app', 'originalEnvSha256': original_sha,
                          'composeProject': current['Config']['Labels']['com.docker.compose.project'],
                          'imageId': current['Image'], 'restored': False}
            self.save()
            self.watchdog(self.c['deadlineSeconds'])
        need(not self.state.get('restored') and self.state['originalEnvSha256'] == original_sha,
             'Original runtime changed; refusing to overwrite it')
        values = benchmark_env(original, token(self.c), variant)
        path = self.root / 'benchmark.env'
        write(path, ''.join(k + '=' + v + '\n' for k, v in values.items()))
        self.app_compose(path)
        applied = self.container('app')
        need(applied['Image'] == self.state['imageId'], 'Image changed during the comparison')
        active = dict(entry.split('=', 1) for entry in applied['Config']['Env'] if '=' in entry)
        for key in ('SPRING_PROFILES_ACTIVE', 'ACCOMMODATION_DETAIL_CACHE_ENABLED', 'CACHE_BENCHMARK_LOCAL_COALESCING_ENABLED'):
            need(active.get(key) == values[key], 'Variant configuration was not applied')
        self.state['variant'] = variant
        self.save()
        for version in (1, 2):
            try:
                local.base.get_json('http://127.0.0.1:8080', '/api/v' + str(version) + '/accommodations/' + str(self.c['accommodationIds'][0]))
                raise RuntimeError('Benchmark token isolation missing')
            except urllib.error.HTTPError as error:
                need(error.code == 403, 'Unexpected unauthenticated response')
        local.base.preflight('http://127.0.0.1:8080', values['BENCHMARK_READ_MODEL_TOKEN'], self.c['accommodationIds'], None)
        runtime = local.base.get_json('http://127.0.0.1:8080', '/api/v2/benchmark/cache-runtime',
                                      values['BENCHMARK_READ_MODEL_TOKEN'])
        need(runtime['flyway_version'] == '28' and runtime['cache_enabled'] == (variant != 'cache-off')
             and runtime['coalescing_enabled'] == (variant != 'coalescing-off')
             and runtime['ttl_seconds'] == 600 and runtime['ttl_jitter_seconds'] == 120,
             'Runtime schema or cache policy differs from the experiment')
        return {'imageId': applied['Image'], 'instanceId': self.instance, 'variant': variant,
                'runtime': runtime, 'cpuQuota': applied['HostConfig']['NanoCpus'], 'memoryBytes': applied['HostConfig']['Memory']}

    def snapshot(self):
        need(self.instance in self.c['apps'] and self.state.get('kind') == 'app', 'Snapshot requires an owned app')
        app = self.container('app')
        need(app['Image'] == self.state['imageId'] and app['State']['Running'], 'Application changed')
        with urllib.request.urlopen('http://127.0.0.1:8080/actuator/prometheus', timeout=10) as response:
            raw = response.read().decode()
        filtered = measurement_metrics(raw)
        need(len(filtered) < 18000, 'Snapshot exceeds the bounded SSM response')
        return {'prometheus': filtered, 'containerId': app['Id']}

    def redis(self, action):
        need(self.instance == self.c['redisInstanceId'], 'Not the selected Redis host')
        item = self.container('redis-cache')
        need(item['Config']['Image'] == env_file(Path('/etc/airbob/images.env'))['REDIS_IMAGE']
             and any(p['HostPort'] == '6380' for p in item['HostConfig']['PortBindings'].get('6379/tcp', [])),
             'Dedicated cache Redis identity mismatch')
        if self.state.get('paused'):
            need(self.state.get('containerId') == item['Id'], 'Redis container was replaced')
        if action == 'flush':
            need(not item['State']['Paused'], 'Redis is paused')
            need(command(['docker', 'exec', item['Id'], 'redis-cli', 'FLUSHDB']).strip() == 'OK', 'Cache reset failed')
        elif action == 'pause':
            need(not item['State']['Paused'], 'Redis was already paused outside this experiment')
            self.state = {'kind': 'redis', 'containerId': item['Id'], 'paused': True, 'pauseId': os.urandom(12).hex()}
            self.save()  # Record intent before mutation, so recover handles an uncertain command result.
            self.watchdog(self.c['durationSeconds'] + 120, {'pauseId': self.state['pauseId']})
            command(['docker', 'pause', item['Id']])
        elif action == 'unpause':
            if self.state.get('paused'):
                if item['State']['Paused']:
                    command(['docker', 'unpause', item['Id']])
                self.state['paused'] = False
                self.save()
                self.cancel_watchdog()
        return {'action': action, 'containerId': item['Id']}

    def recover(self):
        if self.state.get('kind') == 'redis':
            self.redis('unpause')
        if self.state.get('kind') == 'app' and not self.state.get('restored'):
            path = Path('/etc/airbob/app.env')
            need(hashlib.sha256(path.read_bytes()).hexdigest() == self.state['originalEnvSha256'],
                 'Original environment changed; manual recovery required')
            self.app_compose(path)
            need(self.container('app')['Image'] == self.state['imageId'], 'Recovery image mismatch')
            self.state['restored'] = True
            self.save()
            (self.root / 'benchmark.env').unlink(missing_ok=True)
            self.cancel_watchdog()
        return {'recovered': True, 'instanceId': self.instance}

    def stop_generator(self, expected=None):
        need(self.instance == self.c['loadGeneratorInstanceId'], 'Wrong generator recovery host')
        path = self.root / 'active-k6.json'
        try:
            active = json.loads(path.read_text())
        except FileNotFoundError:
            return {'recovered': True, 'generator': 'idle'}
        if expected and active != expected:
            return {'recovered': True, 'generator': 'superseded-watchdog'}
        pid = active['pid']
        need(type(pid) is int and pid > 1, 'Invalid generator process identity')
        proc = Path('/proc') / str(pid)
        # Normal completion can race any /proc read or signal during recovery.
        with contextlib.suppress(FileNotFoundError, ProcessLookupError):
            need(proc.joinpath('stat').read_text().split()[21] == active['startTicks']
                 and proc.joinpath('cmdline').read_bytes().split(b'\0')[0].decode() == str(self.root / 'tools/k6')
                 and os.getpgid(pid) == pid, 'Generator PID was reused; refusing to signal it')
            os.killpg(pid, signal.SIGTERM)
            end = time.monotonic() + 5
            while proc.exists() and time.monotonic() < end: time.sleep(.1)
            if proc.exists():
                # Check identity again; never kill a reused PID.
                need(proc.joinpath('stat').read_text().split()[21] == active['startTicks'], 'Generator PID changed')
                with contextlib.suppress(ProcessLookupError): os.killpg(pid, signal.SIGKILL)
        with contextlib.suppress(FileNotFoundError):
            if json.loads(path.read_text()) == active: path.unlink()
        return {'recovered': True, 'generator': 'stopped'}

    def generator(self, args):
        need(self.instance == self.c['loadGeneratorInstanceId'], 'Not the selected load generator')
        c = self.c
        secret = token(c)
        addresses = sorted({a[4][0] for a in socket.getaddrinfo(c['alb']['dnsName'], 443, socket.AF_INET)})
        need(addresses, 'ALB DNS did not resolve')
        address = addresses[0]

        class AlbConnection(http.client.HTTPSConnection):
            def connect(self):
                sock = socket.create_connection((address, 443), self.timeout)
                self.sock = ssl.create_default_context().wrap_socket(sock, server_hostname='api.airbob.cloud')

        def get(version, identifier):
            connection = AlbConnection('api.airbob.cloud', timeout=15)
            try:
                connection.request('GET', '/api/v' + str(version) + '/accommodations/' + str(identifier),
                                   headers={'X-Benchmark-Token': secret})
                response = connection.getresponse()
                need(response.status == 200, 'ALB preflight response failed')
                body = json.loads(response.read())
                need(body.get('success') is True and body['data']['id'] == identifier, 'Invalid fixture response')
                return local.base.canonical_detail(body['data'])
            finally:
                connection.close()

        fixture_path = self.root / 'fixture.json'
        if args['action'] == 'preflight':
            entries = []
            previous = json.loads(fixture_path.read_text()) if fixture_path.exists() else None
            for identifier in c['accommodationIds']:
                before, after = get(2, identifier), get(1, identifier)
                need(before == after, 'Before/after response mismatch')
                entries.append({'id': identifier, 'data': before})
            fixture = {'schemaVersion': 1, 'datasetId': c['datasetId'], 'accommodations': entries}
            need(previous is None or previous == fixture, 'Dataset changed between variants')
            write(fixture_path, fixture)
            return {'fixtureSha256': hashlib.sha256(fixture_path.read_bytes()).hexdigest(), 'albAddress': address}

        label = args['label']
        need(re.fullmatch('[a-z0-9-]{1,110}', label) and args['kind'] in ('rate', 'burst'), 'Invalid generator phase')
        result_path = self.root / (label + '.json')
        need(fixture_path.is_file() and not result_path.exists(), 'Fixture missing or repeated phase label')
        executable = str(self.root / 'tools/k6')
        self.install_k6()
        need(command([executable, 'version']).startswith('k6 v1.5.0 '), 'Pinned k6 v1.5.0 required')
        environment = local.base.process_environment() | {
            'EXPERIMENT_ENVIRONMENT': 'aws', 'EXPERIMENT_ORIGINS': json.dumps([c['alb']['origin']]),
            'EXPERIMENT_HOSTS': json.dumps({'api.airbob.cloud': address}), 'APP_INSTANCE_COUNT': str(len(c['apps'])),
            'EXPERIMENT_KIND': args['kind'], 'CACHE_BENCHMARK_FIXTURE': str(fixture_path),
            'RESULT_PATH': str(result_path), 'BENCHMARK_READ_MODEL_TOKEN': secret,
            'RATE': str(args['rate']), 'SECONDS': str(args['seconds']), 'BURST': str(c['burst']),
            'VUS': str(c['clientVUs']), 'DISTRIBUTION': args['distribution'], 'K6_NO_USAGE_REPORT': 'true'}
        samples, stop = [], threading.Event()

        def resources():
            def cpu():
                return list(map(int, Path('/proc/stat').read_text().splitlines()[0].split()[1:9]))
            previous = cpu()
            while not stop.wait(1):
                current = cpu()
                total = sum(current) - sum(previous)
                idle = current[3] + current[4] - previous[3] - previous[4]
                memory = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()}
                samples.append({'epochMs': time.time() * 1000, 'cpuPercent': (1 - idle / total) * 100 if total else 0,
                                'availableMemoryPercent': memory['MemAvailable'] / memory['MemTotal'] * 100})
                previous = current

        monitor = threading.Thread(target=resources, daemon=True)
        monitor.start()
        write(self.root / 'k6-options.json', {})
        try:
            with (self.root / (label + '.log')).open('w') as stream:
                process = subprocess.Popen([executable, 'run', '--config', str(self.root / 'k6-options.json'),
                    '--quiet', str(HERE / 'accommodation-detail-experiment.js')],
                    env=environment, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    active = {'pid': process.pid, 'startTicks': Path('/proc/' + str(process.pid) + '/stat').read_text().split()[21]}
                    write(self.root / 'active-k6.json', active)
                    self.watchdog(args['seconds'] + 45, {'generator': active})
                    code = process.wait(timeout=args['seconds'] + 45)
                    need(code in (0, 99), 'k6 execution failed')
                finally:
                    if process.poll() is None:
                        os.killpg(process.pid, signal.SIGTERM)
                        try:
                            process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
                    (self.root / 'active-k6.json').unlink(missing_ok=True)
                    self.cancel_watchdog()
        finally:
            stop.set()
            monitor.join(timeout=3)
        need(samples, 'Generator resource samples missing')
        result = json.loads(result_path.read_text())
        window = [s for s in samples if isinstance(result.get('startedEpochMs'), (int, float))
                  and result['startedEpochMs'] + 1000 <= s['epochMs'] <= result['endedEpochMs']]
        selected = window or samples
        result.update(albAddress=address, clientVUs=c['clientVUs'], distribution=args['distribution'],
                      generator={'maxCpuPercent': max(s['cpuPercent'] for s in selected),
                                 'minAvailableMemoryPercent': min(s['availableMemoryPercent'] for s in selected),
                                 'sampleCount': len(selected), 'window': 'requests' if window else 'whole-process'})
        write(self.root / (label + '-generator.json'), samples)
        write(result_path, result)
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config'); parser.add_argument('action'); parser.add_argument('arguments')
    args = parser.parse_args()
    config = validate(json.loads(Path(args.config).read_text()), executing=True)
    host = Host(config, args.config)
    values = json.loads(args.arguments)
    # Interrupting k6 must not wait for the long-running generator action's lock.
    if args.action == 'recover' and host.instance == config['loadGeneratorInstanceId']:
        print('AIRBOB_CACHE_RESULT=' + json.dumps(host.stop_generator(values.get('generator'))))
        return
    # Each SSM action is serialized on its host, including watchdog recovery.
    import fcntl
    with (host.root / 'operation.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        # Watchdog and SSM operations must observe state AFTER obtaining the lock.
        host.state = json.loads(host.state_path.read_text()) if host.state_path.exists() else {}
        if args.action == 'recover' and values.get('pauseId') and values['pauseId'] != host.state.get('pauseId'):
            print('AIRBOB_CACHE_RESULT={"recovered":true,"supersededWatchdog":true}')
            return
        if args.action == 'variant': result = host.variant(values['variant'])
        elif args.action == 'snapshot': result = host.snapshot()
        elif args.action in {'flush', 'pause', 'unpause'}: result = host.redis(args.action)
        elif args.action == 'recover': result = host.recover()
        elif args.action == 'generator': result = host.generator(values)
        elif args.action == 'install-k6': result = host.install_k6()
        else: raise RuntimeError('Unknown host action')
    print('AIRBOB_CACHE_RESULT=' + json.dumps(result, separators=(',', ':')))


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(SystemExit(128 + signum)))
    try:
        main()
    except Exception as error:
        # Never print AWS responses, runtime env, tokens, or exception messages from external libraries.
        print('AIRBOB_CACHE_HOST_FAILED=' + type(error).__name__)
        raise SystemExit(1) from None
