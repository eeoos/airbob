#!/usr/bin/env python3
"""prepare is entirely offline. run/recover operate only on an already running, fixed AWS lab."""
import argparse
import base64
import concurrent.futures
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import threading
import time
import uuid
import zlib

from aws_cache_contract import ACCOUNT, REGION, BUCKET, ROOT, HERE, digest, evaluate, capacity_search, validate, need

SOURCES = [
    'load-test/k6/cache/aws_cache_host.py', 'load-test/k6/cache/aws_cache_contract.py',
    'load-test/k6/cache/run-local-experiments.py', 'load-test/k6/cache/run-local-comparison.py',
    'load-test/k6/cache/accommodation-detail-experiment.js',
    'load-test/k6/lib/cache-experiment-config.js', 'load-test/k6/lib/accommodation-cache-benchmark.js',
    'infra/aws/toolchain.env',
]


def write(path, value):
    path = Path(path)
    need(not path.is_symlink(), 'Unsafe output path')
    temporary = path.with_suffix(path.suffix + '.tmp')
    with os.fdopen(os.open(temporary, os.O_CREAT | os.O_TRUNC | os.O_WRONLY, 0o600), 'w') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
    os.replace(temporary, path)


def prepare(config, output):
    """No subprocesses, DNS, credentials, Docker, Terraform, or AWS clients here."""
    validate(config)
    output = Path(output)
    output.mkdir(parents=True, mode=0o700, exist_ok=False)
    files = {path: (ROOT / path).read_text() for path in SOURCES}
    bundle = zlib.compress(json.dumps(files, sort_keys=True).encode(), level=9)
    (output / 'host-bundle.zlib').write_bytes(bundle)
    hashes = {path: hashlib.sha256(value.encode()).hexdigest() for path, value in files.items()}
    hashes[str(Path(__file__).relative_to(ROOT))] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    lease_path = 'infra/aws/scripts/orchestration-lease.sh'
    hashes[lease_path] = hashlib.sha256((ROOT / lease_path).read_bytes()).hexdigest()
    write(output / 'config.json', config)
    write(output / 'plan.json', {'schemaVersion': 1, 'state': 'offline-prepared',
        'configSha256': digest(config), 'bundleSha256': hashlib.sha256(bundle).hexdigest(), 'sources': hashes,
        'awsCallsPerformed': 0, 'provisionsInfrastructure': False,
        'topology': 'existing fixed apps, existing dedicated cache Redis, separate existing k6 host, HTTPS ALB',
        'operations': ['verify identity/tags/image/fixed ASG/healthy targets', 'acquire measurement lease',
                       'install checksum-verified helpers', 'switch same-image cache/merge variants',
                       'warm, measure, scrape each app', 'pause owned cache Redis with recovery watchdog',
                       'restore original application environments and Redis', 'save evidence'],
        'capacity': config['capacity'],
        'requirements': ['existing V28 dataset and published IDs from its sealed manifest',
            'app image containing cache-benchmark and local-coalescing control already deployed',
            'one external 64-hex SecureString token shared by app and load-generator roles',
            'separate load generator can download the checksum-pinned k6 release', 'all hosts support Python 3 and systemd',
            'fixed app ASG, healthy ALB targets, enough resource lifetime for the experiment',
            'lab-operator credentials for SSM, measurement lease and evidence storage']})
    parameter_arn = 'arn:aws:ssm:' + REGION + ':' + ACCOUNT + ':parameter' + config['tokenParameter']
    write(output / 'token-read-policy.json', {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': 'ssm:GetParameter', 'Resource': parameter_arn}]})
    write(output / 'token-admin-policy.json', {'Version': '2012-10-17', 'Statement': [
        {'Effect': 'Allow', 'Action': ['ssm:PutParameter', 'ssm:DeleteParameter'], 'Resource': parameter_arn}]})
    return output


class Aws:
    def call(self, service, action, *args):
        result = subprocess.run(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
                                 '--cli-connect-timeout', '10', '--cli-read-timeout', '30', service, action, *args],
                                capture_output=True, text=True, timeout=90)
        need(result.returncode == 0, 'AWS command failed: ' + service + ' ' + action)
        return json.loads(result.stdout) if result.stdout.strip() else {}


class Transport:
    def __init__(self, aws, config, deadline, heartbeat=lambda: None):
        self.aws, self.c, self.deadline, self.heartbeat = aws, config, deadline, heartbeat
        self.root = '/var/lib/airbob/cache-benchmark/' + config['experimentId']

    def shell(self, instance, body, timeout=120, cleanup=False):
        if not cleanup:
            need(time.time() + timeout < self.deadline, 'Experiment deadline reached; cleanup required')
            self.heartbeat()
        response = self.aws.call('ssm', 'send-command', '--instance-ids', instance,
            '--document-name', 'AWS-RunShellScript', '--document-version', '1', '--timeout-seconds', '60',
            '--comment', 'Airbob cache experiment ' + self.c['experimentId'],
            '--parameters', json.dumps({'commands': ['set -eu\n' + body], 'executionTimeout': [str(timeout)]}))
        command_id = response['Command']['CommandId']
        end = time.monotonic() + timeout + 60
        try:
            while time.monotonic() < end:
                if not cleanup:
                    self.heartbeat()
                response = self.aws.call('ssm', 'list-command-invocations', '--command-id', command_id,
                                         '--instance-id', instance, '--details')
                items = response.get('CommandInvocations', [])
                if items and items[0]['Status'] not in ('Pending', 'InProgress', 'Delayed', 'Cancelling'):
                    need(items[0]['Status'] == 'Success', 'SSM host action failed; inspect private host logs')
                    result = self.aws.call('ssm', 'get-command-invocation', '--command-id', command_id, '--instance-id', instance)
                    need(result['Status'] == 'Success', 'SSM result changed')
                    return result.get('StandardOutputContent', '')
                time.sleep(2)
            raise RuntimeError('SSM action timed out')
        except BaseException:
            with contextlib.suppress(Exception):
                self.aws.call('ssm', 'cancel-command', '--command-id', command_id, '--instance-ids', instance)
            raise

    def install(self, instance, prepared):
        data = (prepared / 'host-bundle.zlib').read_bytes()
        expected = json.loads((prepared / 'plan.json').read_text())['bundleSha256']
        need(hashlib.sha256(data).hexdigest() == expected, 'Prepared bundle changed')
        root = shlex.quote(self.root)
        # A new directory is an ownership boundary; a second run cannot replace an active host controller.
        self.shell(instance, 'umask 077\nmkdir -p /var/lib/airbob/cache-benchmark\nmkdir ' + root
                   + '\n: > ' + root + '/bundle.b64')
        encoded = base64.b64encode(data).decode()
        for index in range(0, len(encoded), 12000):
            self.shell(instance, "printf '%s' " + shlex.quote(encoded[index:index + 12000]) + ' >> ' + root + '/bundle.b64')
        config64 = base64.b64encode(json.dumps(self.c).encode()).decode()
        installer = """import base64,hashlib,json,pathlib,zlib,os
root=pathlib.Path(ROOT)
raw=base64.b64decode((root/'bundle.b64').read_bytes())
assert hashlib.sha256(raw).hexdigest()==EXPECTED
files=json.loads(zlib.decompress(raw))
for name,content in files.items():
    path=pathlib.PurePosixPath(name)
    assert not path.is_absolute() and '..' not in path.parts and (name.startswith('load-test/k6/') or name=='infra/aws/toolchain.env')
    dest=root/name; dest.parent.mkdir(parents=True,exist_ok=True); dest.write_text(content); os.chmod(dest,0o600)
(root/'config.json').write_bytes(base64.b64decode(CONFIG)); os.chmod(root/'config.json',0o600)
(root/'control.json').write_text(json.dumps({'deadlineEpoch':DEADLINE})); os.chmod(root/'control.json',0o600)
(root/'bundle.ready').write_text(EXPECTED)
print('AIRBOB_CACHE_INSTALLED')
""".replace('ROOT', repr(self.root)).replace('EXPECTED', repr(expected)).replace('CONFIG', repr(config64)).replace('DEADLINE', repr(int(self.deadline)))
        result = self.shell(instance, 'python3 -c ' + shlex.quote(installer))
        need('AIRBOB_CACHE_INSTALLED' in result, 'Host bundle installation was not confirmed')

    def action(self, instance, action, arguments=None, timeout=120, cleanup=False):
        argv = ['python3', self.root + '/load-test/k6/cache/aws_cache_host.py', self.root + '/config.json',
                action, json.dumps(arguments or {}, separators=(',', ':'))]
        body = shlex.join(argv)
        if action == 'recover':
            body = ('if [ -f ' + shlex.quote(self.root + '/bundle.ready') + ' ]; then\n' + body
                    + '\nelse\nprintf \'%s\\n\' \'AIRBOB_CACHE_RESULT={"recovered":true,"noApplicationMutation":true}\'\nfi')
        else:
            body = 'test -f ' + shlex.quote(self.root + '/bundle.ready') + '\n' + body
        output = self.shell(instance, body, timeout, cleanup)
        matches = [line.removeprefix('AIRBOB_CACHE_RESULT=') for line in output.splitlines()
                   if line.startswith('AIRBOB_CACHE_RESULT=')]
        need(len(matches) == 1, 'Missing or truncated host evidence')
        return json.loads(matches[0])


def preflight(aws, c, *, recovery=False):
    identity = aws.call('sts', 'get-caller-identity')
    need(identity['Account'] == ACCOUNT and ':assumed-role/airbob-lab-operator/' in identity['Arn'],
         'Use the existing lab-operator role')
    ids = c['apps'] + [c['redisInstanceId'], c['loadGeneratorInstanceId']]
    response = aws.call('ec2', 'describe-instances', '--instance-ids', *ids)
    instances = [i for r in response['Reservations'] for i in r['Instances']]
    need({i['InstanceId'] for i in instances} == set(ids), 'AWS instance set differs')
    fences, vpcs = set(), set()
    for item in instances:
        tags = {t['Key']: t['Value'] for t in item.get('Tags', [])}
        role = 'app' if item['InstanceId'] in c['apps'] else ('redis' if item['InstanceId'] == c['redisInstanceId'] else 'loadgen')
        need(item['State']['Name'] == 'running' and tags.get('Project') == 'airbob'
             and tags.get('Environment') == 'performance-lab' and tags.get('RunId') == c['runId']
             and tags.get('Service') == role and tags.get('Persistence') == 'ephemeral', 'Instance ownership/role mismatch')
        if not recovery:
            need(int(tags.get('ExpiresAt', 0)) > time.time() + c['deadlineSeconds'] + 600,
                 'Resource lifetime is too short; do not extend it through this runner')
        fences.add(tags.get('FencingToken')); vpcs.add(item['VpcId'])
    need(len(fences) == 1 and None not in fences and len(vpcs) == 1, 'Mixed lab resources')
    groups = aws.call('autoscaling', 'describe-auto-scaling-groups', '--auto-scaling-group-names', c['asgName'])['AutoScalingGroups']
    need(len(groups) == 1, 'ASG missing')
    group = groups[0]
    need({i['InstanceId'] for i in group['Instances']} == set(c['apps'])
         and group['MinSize'] == group['MaxSize'] == group['DesiredCapacity'] == len(c['apps']), 'Fixed app count required')
    need(not aws.call('autoscaling', 'describe-policies', '--auto-scaling-group-name', c['asgName']).get('ScalingPolicies'),
         'Scaling policies must be disabled before capacity comparisons')
    alb = aws.call('elbv2', 'describe-load-balancers', '--load-balancer-arns', c['alb']['arn'])['LoadBalancers'][0]
    need(alb['DNSName'] == c['alb']['dnsName'] and alb['VpcId'] in vpcs, 'ALB identity mismatch')
    tg = aws.call('elbv2', 'describe-target-groups', '--target-group-arns', c['alb']['targetGroupArn'])['TargetGroups'][0]
    need(tg['LoadBalancerArns'] == [c['alb']['arn']] and tg['Port'] == 8080, 'Target group identity mismatch')
    return {'instances': [{k: i[k] for k in ('InstanceId', 'InstanceType', 'ImageId', 'VpcId')} for i in instances],
            'resourceFence': next(iter(fences)),
            'originalSuspendedProcesses': [p['ProcessName'] for p in group.get('SuspendedProcesses', [])]}


class Runner:
    def __init__(self, config, prepared, output, aws=None):
        self.c, self.prepared, self.output = config, Path(prepared), Path(output)
        self.aws = aws or Aws()
        self.results, self.capacities, self.installed, self.recovery_errors = [], [], [], []
        self.owner = 'cache/' + uuid.uuid4().hex
        self.lease_token = None
        self.lease_error = None
        self.stop = threading.Event()
        # Leave ten minutes of the lease for parallel host restoration and evidence capture.
        self.deadline = time.time() + config['deadlineSeconds'] - 600
        self.transport = Transport(self.aws, config, self.deadline, self.assert_lease)
        self.changed_processes = []

    def lease(self, action, *tail):
        args = ['bash', str(ROOT / 'infra/aws/scripts/orchestration-lease.sh'), action,
                'airbob-performance-lab-orchestration-lease', 'airbob-performance-lab', self.owner]
        if action != 'acquire': args += [str(self.lease_token)]
        args += [self.c['runId'], 'measurement', *map(str, tail)]
        result = subprocess.run(args, env=os.environ | {'AWS_REGION': REGION}, capture_output=True, text=True, timeout=45)
        need(result.returncode == 0, 'Measurement lease ' + action + ' failed')
        return result.stdout.strip()

    def assert_lease(self):
        need(self.lease_token is not None and self.lease_error is None, 'Measurement lease lost')
        need(time.time() < self.deadline, 'Measurement deadline reached')
        self.lease('assert')

    def heartbeat(self):
        while not self.stop.wait(30):
            try: self.lease('heartbeat', 180)
            except Exception as error:
                self.lease_error = type(error).__name__
                return

    def save(self, state):
        write(self.output / 'comparison.json', {'schemaVersion': 1, 'state': state,
            'metadata': self.c, 'sourcePlan': json.loads((self.prepared / 'plan.json').read_text()),
            'capacity': self.capacities, 'results': self.results, 'recoveryErrors': self.recovery_errors})
        lines = ['# AWS 숙소 상세 캐시 실험', '', '상태: ' + state, '',
                 '| 실험 | 조건 | 요청 | p95(ms) | DB 로딩 | 병합률 | 오류율 | 누락 |',
                 '|---|---|---:|---:|---:|---:|---:|---:|']
        for r in self.results:
            p95 = r['latencyMs']['p95']
            lines.append('| ' + r['label'] + ' | ' + r['variant'] + ' | ' + str(r['completed']) + ' | '
                + (str(round(p95, 2)) if isinstance(p95, (int, float)) else 'missing') + ' | ' + str(int(r['server']['loads'])) + ' | '
                + format(r['server']['coalescingRatio'], '.2%') + ' | ' + format(r['errorRate'], '.2%')
                + ' | ' + str(r['dropped']) + ' |')
        lines += ['', '처리량은 confirmed-boundary인 경우에만 반복 검증한 경계다. lower-bound-only는 확인한 하한이다.',
                  '버스트는 클라이언트 동시 출발 조건이며 단일 DB 로딩을 보장하지 않는다.', '',
                  '```json', json.dumps(self.capacities, indent=2), '```']
        (self.output / 'report.md').write_text('\n'.join(lines) + '\n')

    def healthy(self):
        end = time.monotonic() + 180
        while time.monotonic() < end:
            self.assert_lease()
            rows = self.aws.call('elbv2', 'describe-target-health', '--target-group-arn', self.c['alb']['targetGroupArn'])['TargetHealthDescriptions']
            need({r['Target']['Id'] for r in rows} == set(self.c['apps']), 'App target set changed')
            if all(r['TargetHealth']['State'] == 'healthy' for r in rows): return
            time.sleep(5)
        raise RuntimeError('ALB targets did not become healthy')

    def variant(self, variant):
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(self.c['apps'])) as pool:
            futures = {i: pool.submit(self.transport.action, i, 'variant', {'variant': variant}, timeout=360)
                       for i in self.c['apps']}
            for instance, future in futures.items():
                write(self.output / (variant + '-' + instance + '-runtime.json'), future.result())
        self.healthy()

    def restore_hosts(self):
        # Unpause first, then independently restore app hosts within the cleanup reserve.
        redis = self.c['redisInstanceId']
        if redis in self.installed:
            try: self.transport.action(redis, 'recover', timeout=120, cleanup=True)
            except Exception: self.recovery_errors.append(redis)
        generator = self.c['loadGeneratorInstanceId']
        if generator in self.installed:
            try: self.transport.action(generator, 'recover', timeout=45, cleanup=True)
            except Exception: self.recovery_errors.append(generator)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(self.c['apps'])) as pool:
            futures = {i: pool.submit(self.transport.action, i, 'recover', timeout=360, cleanup=True)
                       for i in self.c['apps'] if i in self.installed}
            for instance, future in futures.items():
                try: future.result()
                except Exception: self.recovery_errors.append(instance)

    def generator(self, args):
        return self.transport.action(self.c['loadGeneratorInstanceId'], 'generator', args,
                                     timeout=args.get('seconds', 30) + 90)

    def warm(self, label):
        self.transport.action(self.c['redisInstanceId'], 'flush')
        self.generator({'action': 'preflight'})
        result = self.generator({'action': 'measure', 'label': label + '-warmup', 'kind': 'rate',
            'rate': min(40, self.c['rate']), 'seconds': self.c['warmupSeconds'], 'distribution': 'uniform'})
        need(result['errorRate'] == 0 and result['dropped'] == 0, 'Warm-up failed')

    def measure(self, label, scenario, variant, rate=None, seconds=None, kind='rate'):
        self.assert_lease()
        before = [self.transport.action(i, 'snapshot') for i in self.c['apps']]
        result = self.generator({'action': 'measure', 'label': label, 'kind': kind,
            'rate': rate or self.c['rate'], 'seconds': seconds or self.c['durationSeconds'],
            'distribution': 'uniform' if scenario == 'capacity' else 'same-key'})
        after = [self.transport.action(i, 'snapshot') for i in self.c['apps']]
        need([r['containerId'] for r in before] == [r['containerId'] for r in after], 'App restarted during measurement')
        for index, pair in enumerate(zip(before, after)):
            for phase, row in zip(('start', 'end'), pair):
                (self.output / (label + '-app-' + str(index) + '-' + phase + '.prom')).write_text(row['prometheus'])
        result = evaluate(result, [r['prometheus'] for r in before], [r['prometheus'] for r in after], variant, scenario, self.c)
        result['label'] = label
        self.results.append(result)
        write(self.output / (label + '.json'), result)
        self.save('running')
        print(label + ': p95=' + str(result['latencyMs']['p95']) + 'ms, loads=' + str(result['server']['loads'])
              + ', dropped=' + str(result['dropped']), flush=True)
        if scenario != 'capacity': need(not result['evidenceReasons'], 'Invalid measurement evidence: ' + label)
        return result

    def measurements(self):
        c = self.c
        for round_number in range(1, c['rounds'] + 1):
            variants = ['cache-off', 'cache-on'] if round_number % 2 else ['cache-on', 'cache-off']
            if set(c['scenarios']) & {'latency', 'miss'}:
                for variant in variants:
                    self.variant(variant)
                    for scenario in ('latency', 'miss'):
                        if scenario not in c['scenarios']: continue
                        label = variant + '-r' + str(round_number) + '-' + scenario
                        self.warm(label)
                        if scenario == 'miss': self.transport.action(c['redisInstanceId'], 'flush')
                        self.measure(label, scenario, variant, kind='burst' if scenario == 'miss' else 'rate')
            if 'outage' in c['scenarios']:
                for variant in (['coalescing-off', 'coalescing-on'] if round_number % 2 else ['coalescing-on', 'coalescing-off']):
                    self.variant(variant)
                    label = variant + '-r' + str(round_number) + '-outage'
                    self.warm(label)
                    try:
                        self.transport.action(c['redisInstanceId'], 'pause')
                        self.measure(label + '-rate', 'outage', variant, rate=c['outageRate'])
                        self.measure(label + '-burst', 'outage', variant, kind='burst')
                    finally:
                        self.transport.action(c['redisInstanceId'], 'unpause', cleanup=True)
                    self.generator({'action': 'preflight'})
        if 'capacity' in c['scenarios']:
            # Run one search per variant; each candidate boundary has its own repeated confirmation.
            for variant in ('cache-off', 'cache-on'):
                self.variant(variant)
                count = 0
                def observe(rate, seconds, phase, repeat):
                    nonlocal count
                    count += 1
                    label = variant + '-capacity-' + str(count) + '-' + phase + '-' + str(rate) + '-r' + str(repeat)
                    self.warm(label)
                    return self.measure(label, 'capacity', variant, rate=rate, seconds=seconds)
                self.capacities.append({'variant': variant, **capacity_search(c, observe)})
                self.save('running')

    def execute(self):
        validate(self.c, executing=True)
        self.output.mkdir(parents=True, mode=0o700, exist_ok=False)
        plan = json.loads((self.prepared / 'plan.json').read_text())
        need(plan['configSha256'] == digest(self.c), 'Prepared configuration changed')
        for path, expected in plan['sources'].items():
            need(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected, 'Source changed after prepare: ' + path)
        evidence = preflight(self.aws, self.c)
        write(self.output / 'preflight.json', evidence)
        token = self.lease('acquire', 180, self.c['deadlineSeconds'])
        need(token.startswith('fencing_token=') and token.split('=')[1].isdigit(), 'Lease token missing')
        self.lease_token = int(token.split('=')[1])
        worker = threading.Thread(target=self.heartbeat, daemon=True)
        worker.start()
        status = 'incomplete'
        try:
            # Avoid ELB health-check replacement while the same fixed app instances restart.
            self.changed_processes = [p for p in ('ReplaceUnhealthy',) if p not in evidence['originalSuspendedProcesses']]
            write(self.output / 'recovery.json', {'config': self.c, 'changedProcesses': self.changed_processes,
                                                'installed': self.installed})
            if self.changed_processes:
                self.aws.call('autoscaling', 'suspend-processes', '--auto-scaling-group-name', self.c['asgName'],
                              '--scaling-processes', *self.changed_processes)
            for instance in self.c['apps'] + [self.c['redisInstanceId'], self.c['loadGeneratorInstanceId']]:
                # Journal before dispatch, including an uncertain installation result.
                self.installed.append(instance)
                write(self.output / 'recovery.json', {'config': self.c, 'changedProcesses': self.changed_processes,
                                                    'installed': self.installed})
                self.transport.install(instance, self.prepared)
            self.transport.action(self.c['loadGeneratorInstanceId'], 'install-k6', timeout=300)
            self.measurements()
            status = 'complete'
        finally:
            self.restore_hosts()
            if self.changed_processes and not self.recovery_errors:
                try:
                    self.aws.call('autoscaling', 'resume-processes', '--auto-scaling-group-name', self.c['asgName'],
                                  '--scaling-processes', *self.changed_processes)
                except Exception: self.recovery_errors.append('asg-processes')
            self.stop.set(); worker.join(timeout=50)
            try: self.lease('release')
            except Exception: self.recovery_errors.append('measurement-lease')
            self.save('recovery-required' if self.recovery_errors else status)
        need(not self.recovery_errors, 'Recovery incomplete; use recover with this result directory')
        # Only public config/measurement evidence is exported; host runtime env and tokens never leave hosts.
        for path in self.output.iterdir():
            if path.suffix not in ('.json', '.md', '.prom'): continue
            self.aws.call('s3api', 'put-object', '--bucket', BUCKET,
                '--key', 'measurements/' + self.c['runId'] + '/' + self.c['experimentId'] + '/' + path.name,
                '--body', str(path), '--tagging', 'Retention=raw', '--server-side-encryption', 'AES256', '--if-none-match', '*')
        print('Report: ' + str(self.output / 'report.md'))


def recover(output, aws=None):
    output = Path(output)
    receipt = json.loads((output / 'recovery.json').read_text())
    config = validate(receipt['config'], executing=True)
    aws = aws or Aws()
    preflight(aws, config, recovery=True)
    runner = Runner(config, output, output, aws)
    lease = runner.lease('acquire', 180, 1200)
    need(lease.startswith('fencing_token=') and lease.split('=')[1].isdigit(), 'Recovery lease missing')
    runner.lease_token = int(lease.split('=')[1])
    thread = threading.Thread(target=runner.heartbeat, daemon=True)
    thread.start()
    runner.transport = Transport(aws, config, time.time() + 1200, runner.assert_lease)
    runner.installed = receipt['installed']
    try:
        runner.restore_hosts()
        if not runner.recovery_errors and receipt['changedProcesses']:
            aws.call('autoscaling', 'resume-processes', '--auto-scaling-group-name', config['asgName'],
                     '--scaling-processes', *receipt['changedProcesses'])
    finally:
        runner.stop.set(); thread.join(timeout=50)
        runner.lease('release')
    write(output / 'recovery-result.json', {'state': 'recovered' if not runner.recovery_errors else 'recovery-required', 'failures': runner.recovery_errors})
    need(not runner.recovery_errors, 'Some hosts still require recovery')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('prepare', help='offline only: validate and package, without any AWS calls')
    p.add_argument('--config', required=True); p.add_argument('--output', required=True)
    p = sub.add_parser('run', help='measure an already running AWS lab; never create/start EC2 or RDS')
    p.add_argument('--prepared', required=True); p.add_argument('--output', required=True)
    p = sub.add_parser('recover', help='restore the exact hosts recorded by an interrupted experiment')
    p.add_argument('--output', required=True)
    args = parser.parse_args()
    if args.command == 'prepare':
        path = prepare(json.loads(Path(args.config).read_text()), args.output)
        print('Offline plan: ' + str(path / 'plan.json'))
    elif args.command == 'run':
        prepared = Path(args.prepared).resolve()
        Runner(json.loads((prepared / 'config.json').read_text()), prepared, Path(args.output).resolve()).execute()
    else:
        recover(args.output)


if __name__ == '__main__':
    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(SystemExit(128 + signum)))
    main()
