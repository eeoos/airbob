#!/usr/bin/env python3
"""Compare the two detail APIs on local MySQL with a disposable cache Redis."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
METRIC_LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([^\s]+)(?:\s+\d+)?$')
LABEL = re.compile(r'(\w+)=("(?:[^"\\]|\\.)*")')


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def command(args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def process_environment():
    # Do not inherit Spring overrides, Java agents, k6 debug/output sinks, or cloud credentials.
    allowed = {'PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'USER', 'LOGNAME', 'TZ'}
    return {key: value for key, value in os.environ.items() if key in allowed}


def canonical_detail(value):
    value = dict(value)
    if isinstance(value.get('amenities'), list):
        value['amenities'] = sorted(value['amenities'], key=lambda item: json.dumps(item, sort_keys=True))
    return value


def parse_metrics(text):
    result = []
    for line in text.splitlines():
        match = METRIC_LINE.fullmatch(line)
        if match:
            labels = {key: json.loads(value) for key, value in LABEL.findall(match[2] or '')}
            result.append((match[1], labels, float(match[3])))
    return result


def metric_sum(metrics, names, labels=None):
    rows = [value for name, tags, value in metrics
            if name in names and all(tags.get(key) == expected for key, expected in (labels or {}).items())]
    require(bool(rows), 'Required server metric is absent: ' + '/'.join(names))
    return sum(rows)


def server_delta(before, after, variant, completed):
    path = '/api/v' + ('2' if variant == 'before' else '1') + '/accommodations/{accommodationId}'
    tags = {'path': path, 'http_method': 'GET', 'query_type': 'SELECT'}

    def delta(names, labels=None):
        difference = metric_sum(after, names, labels) - metric_sum(before, names, labels)
        require(difference >= 0, 'A server counter reset during measurement')
        return difference

    selects = delta(['app_query_per_request_queries_sum', 'app_query_per_request_sum'], tags)
    requests = delta(['app_query_per_request_queries_count', 'app_query_per_request_count'], tags)
    hits = sum(delta(['accommodation_detail_cache_request_total'], {'result': kind})
               for kind in ['hit', 'hit_after_wait'])
    outcomes = delta(['accommodation_detail_cache_request_total'])
    loads = delta(['accommodation_detail_cache_load_duration_seconds_count'])
    errors = delta(['accommodation_detail_cache_redis_operation_total'], {'result': 'error'})
    expected_selects = completed * (3 if variant == 'before' else 0)
    reasons = []
    if completed <= 0 or requests != completed:
        reasons.append('server-request-count-mismatch')
    if selects != expected_selects:
        reasons.append('unexpected-select-count')
    if errors:
        reasons.append('redis-errors')
    if variant == 'after' and (hits != completed or outcomes != completed or loads != 0):
        reasons.append('warm-cache-not-proven')
    if variant == 'before' and (outcomes != 0 or loads != 0):
        reasons.append('before-used-cache-path')
    return {'requests': requests, 'selects': selects,
            'selectsPerRequest': selects / requests if requests else None,
            'cacheHits': hits, 'cacheOutcomes': outcomes, 'cacheLoads': loads,
            'redisErrors': errors, 'validityReasons': reasons}


def mysql(container, query):
    # The password stays inside the existing local container and is never a CLI argument.
    return command(['docker', 'exec', '-i', container, 'sh', '-c',
                    'MYSQL_PWD="$(cat /run/airbob-mysql/root-password)" '
                    'exec mysql --protocol=socket --user=root --batch --skip-column-names airbobdb'],
                   input='SET SESSION TRANSACTION READ ONLY; START TRANSACTION;\n' + query + '\nROLLBACK;')


def get_json(origin, path, token=None):
    headers = {'X-Benchmark-Token': token} if token else {}
    request = urllib.request.Request(origin + path, headers=headers)
    with urllib.request.urlopen(request, timeout=15) as response:
        require(response.status == 200, 'API preflight failed')
        return json.load(response)


def scrape(origin):
    with urllib.request.urlopen(origin + '/actuator/prometheus', timeout=15) as response:
        return response.read().decode()


def preflight(origin, token, ids, expected=None):
    accommodations = []
    for accommodation_id in ids:
        before = get_json(origin, f'/api/v2/accommodations/{accommodation_id}', token)
        after = get_json(origin, f'/api/v1/accommodations/{accommodation_id}', token)
        require(before.get('success') is True and after.get('success') is True,
                'Preflight API returned an unsuccessful envelope')
        require(before['data'].get('id') == accommodation_id,
                'Preflight returned another accommodation')
        require(canonical_detail(before['data']) == canonical_detail(after['data']),
                f'Before/after response differs for accommodation {accommodation_id}')
        if expected is not None:
            require(canonical_detail(before['data']) == canonical_detail(expected[accommodation_id]),
                    f'Dataset changed for accommodation {accommodation_id}')
        accommodations.append({'id': accommodation_id, 'data': before['data']})
    return accommodations


def wait_for_app(process, log, timeout=120):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        require(process.poll() is None, f'Benchmark app stopped; inspect {log}')
        matches = re.findall(r'Tomcat started on port (\d+)', log.read_text(errors='replace'))
        if matches:
            origin = 'http://127.0.0.1:' + matches[-1]
            try:
                if get_json(origin, '/actuator/health').get('status') == 'UP':
                    return origin
            except (OSError, ValueError):
                pass
        time.sleep(0.5)
    raise RuntimeError(f'Benchmark app startup timed out; inspect {log}')


def run(args):
    require(5 <= args.keys <= 200, '--keys must be between 5 and 200')
    require(1 <= args.rate <= 200, '--rate must be between 1 and 200 for this local runner')
    require(1 <= args.duration <= 120 and 1 <= args.warmup <= 60, 'Invalid local phase duration')
    require(1 <= args.rounds <= 5, '--rounds must be between 1 and 5')
    java_home = args.java_home
    if java_home is None and Path('/usr/libexec/java_home').is_file():
        java_home = command(['/usr/libexec/java_home', '-v', '21'])
    java_home = java_home or os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else shutil.which('java')
    require(java is not None and Path(java).is_file(), 'A current JDK 21 is required; use --java-home')
    run_id = time.strftime('%Y%m%dT%H%M%S', time.gmtime()) + '-' + secrets.token_hex(3)
    output = ROOT / 'build/k6/cache' / run_id
    output.mkdir(parents=True, mode=0o700)
    mysql_info = json.loads(command(['docker', 'inspect', args.mysql_container]))[0]
    require(mysql_info['State']['Running'], 'The existing local MySQL must be running')
    ports = mysql_info['NetworkSettings']['Ports'].get('3306/tcp') or []
    require(len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1', 'MySQL must be loopback-only')
    password_file = Path('.env')
    require(password_file.is_file(), 'Run from the repository with its existing local .env')
    # Spring loads the existing .env itself. Validate its DB endpoint without printing credentials.
    settings = dict(line.split('=', 1) for line in password_file.read_text().splitlines()
                    if '=' in line and not line.lstrip().startswith('#'))
    db_url = settings.get('SPRING_DATASOURCE_URL', '').strip()
    require(re.fullmatch(r'jdbc:mysql://(?:localhost|127\.0\.0\.1):' + ports[0]['HostPort']
                         + r'/airbobdb(?:\?[^\r\n]*)?', db_url) is not None,
            'The existing .env must address this local MySQL airbobdb')
    schema = mysql(args.mysql_container,
                   'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;')
    require(schema == '28', 'This source/local benchmark requires an already migrated V28 database')
    ids = [int(value) for value in mysql(args.mysql_container,
           "SELECT id FROM accommodation WHERE status='PUBLISHED' ORDER BY id LIMIT " + str(args.keys) + ';').splitlines()]
    require(len(ids) == args.keys, 'Not enough published accommodations in the existing database')
    source_commit = command(['git', 'rev-parse', 'HEAD'], cwd=ROOT)
    if not args.skip_build:
        with (output / 'build.log').open('w') as log:
            subprocess.run(['./gradlew', 'bootJar', '-x', 'test', '-x', 'generateAdoc', '-x', 'asciidoctor',
                            '-x', 'copyDocument', '--console=plain'], cwd=ROOT, stdout=log,
                           stderr=subprocess.STDOUT, check=True, env=process_environment())
    jar = ROOT / 'build/libs/airbob.jar'
    require(jar.is_file(), 'Application JAR is missing')
    redis_source = json.loads(command(['docker', 'inspect', args.redis_container]))[0]
    redis_image = redis_source['Image']
    redis_name = 'airbob-cache-benchmark-' + run_id.lower()
    redis_id, app = None, None
    results = []
    metadata = {'runId': run_id, 'scope': 'local-validation', 'appCommit': source_commit,
                'sourceTreeDirty': bool(command(['git', 'status', '--porcelain'], cwd=ROOT)),
                'appJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                'datasetId': settings.get('AIRBOB_DATASET_ID', 'local-airbobdb').strip(),
                'flywayVersion': schema, 'mysqlContainerId': mysql_info['Id'],
                'redisImageId': redis_image, 'selection': 'first-published-ids-local-smoke',
                'javaExecutable': java,
                'keys': len(ids), 'rate': args.rate, 'durationSeconds': args.duration,
                'rounds': args.rounds, 'authentication': 'anonymous', 'cacheEnabled': True}
    write_json(output / 'run.json', metadata)
    try:
        redis_id = command(['docker', 'run', '--detach', '--name', redis_name,
                            '--label', 'airbob.cache-benchmark.run=' + run_id,
                            '--publish', '127.0.0.1::6379', redis_image,
                            'redis-server', '--save', '', '--appendonly', 'no'])
        redis_info = json.loads(command(['docker', 'inspect', redis_id]))[0]
        redis_port = redis_info['NetworkSettings']['Ports']['6379/tcp'][0]['HostPort']
        token = secrets.token_urlsafe(32)
        environment = process_environment()
        environment.update(SPRING_PROFILES_ACTIVE='dev,cache-benchmark', BENCHMARK_READ_MODEL_TOKEN=token,
                           SPRING_DATASOURCE_URL=db_url,
                           SPRING_DATASOURCE_USERNAME=settings['SPRING_DATASOURCE_USERNAME'].strip(),
                           SPRING_DATASOURCE_PASSWORD=settings['SPRING_DATASOURCE_PASSWORD'].strip(),
                           ACCOMMODATION_DETAIL_CACHE_ENABLED='true',
                           ACCOMMODATION_DETAIL_CACHE_REDIS_HOST='127.0.0.1',
                           ACCOMMODATION_DETAIL_CACHE_REDIS_PORT=redis_port,
                           AWS_ACCESS_KEY_ID='dummy', AWS_SECRET_ACCESS_KEY='dummy',
                           AWS_EC2_METADATA_DISABLED='true', AWS_REGION='ap-northeast-2',
                           SPRING_CLOUD_AWS_REGION_STATIC='ap-northeast-2')
        log_path = output / 'application.log'
        with log_path.open('w') as log:
            app = subprocess.Popen([java, '-Xms512m', '-Xmx512m', '-Duser.timezone=UTC', '-jar', str(jar),
                                    '--server.address=127.0.0.1', '--server.port=0'],
                                   cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        origin = wait_for_app(app, log_path)
        for version in [1, 2]:
            try:
                get_json(origin, f'/api/v{version}/accommodations/{ids[0]}')
                raise RuntimeError('Benchmark detail unexpectedly accepted a missing token')
            except urllib.error.HTTPError as error:
                require(error.code == 403, 'Unexpected missing-token response')
        accommodations = preflight(origin, token, ids)
        fixture = {'schemaVersion': 1, 'datasetId': metadata['datasetId'], 'accommodations': accommodations}
        fixture_path = output / 'fixture.json'
        write_json(fixture_path, fixture)
        expected = {row['id']: row['data'] for row in accommodations}
        print(f'Preflight passed: {len(ids)} matching V2/V1 responses, V28, isolated cache Redis.', flush=True)
        client_env = process_environment()
        client_env.update(BASE_URL=origin, BENCHMARK_READ_MODEL_TOKEN=token,
                          CACHE_BENCHMARK_FIXTURE=str(fixture_path), APP_COMMIT=source_commit,
                          RATE=str(args.rate), K6_NO_USAGE_REPORT='true')
        for distribution in args.distributions:
            for round_number in range(1, args.rounds + 1):
                order = ['before', 'after'] if round_number % 2 else ['after', 'before']
                for variant in order:
                    # Only this run's newly-created Redis can be reset.
                    own = json.loads(command(['docker', 'inspect', redis_id]))[0]
                    require(own['Config']['Labels'].get('airbob.cache-benchmark.run') == run_id,
                            'Disposable cache ownership changed')
                    require(command(['docker', 'exec', redis_id, 'redis-cli', 'FLUSHDB']) == 'OK', 'Cache reset failed')
                    preflight(origin, token, ids, expected)
                    label = f'{distribution}-r{round_number}-{variant}'
                    env = client_env | {'VARIANT': variant, 'DISTRIBUTION': distribution, 'RUN_LABEL': label}
                    for mode, seconds in [('warmup', args.warmup), ('measure', args.duration)]:
                        result_path = output / (label + '.json')
                        env.update(MODE=mode, DURATION=f'{seconds}s', RESULT_PATH=str(result_path))
                        if mode == 'measure':
                            start_text = scrape(origin)
                            (output / (label + '-start.prom')).write_text(start_text)
                        with (output / (label + '-' + mode + '.log')).open('w') as log:
                            process = subprocess.run(['k6', 'run', '--quiet',
                                'load-test/k6/cache/accommodation-detail-comparison.js'], cwd=ROOT,
                                env=env, stdout=log, stderr=subprocess.STDOUT, timeout=seconds + 30)
                        require(process.returncode == 0, f'k6 {mode} failed; inspect {label}-{mode}.log')
                    end_text = scrape(origin)
                    (output / (label + '-end.prom')).write_text(end_text)
                    result = json.loads(result_path.read_text())
                    completed = result['load']['iterations']['completed']
                    observed = server_delta(parse_metrics(start_text), parse_metrics(end_text), variant, completed)
                    result['server'] = observed
                    result['metadata'].update(round=round_number, runOrder=order.index(variant) + 1,
                                              scope='local-validation', appJarSha256=metadata['appJarSha256'])
                    if observed['validityReasons']:
                        result['validity']['status'] = 'invalid'
                        result['validity']['reasons'].extend(observed['validityReasons'])
                    write_json(result_path, result)
                    require(result['validity']['status'] == 'valid', f'Measurement invalid: {label}')
                    results.append(result)
                    print(f"{label}: {completed} requests, p95={result['performance']['latencyMs']['p95']:.2f} ms, "
                          f"SELECT/request={observed['selectsPerRequest']:.0f}, cache hits={observed['cacheHits']:.0f}", flush=True)
        preflight(origin, token, ids, expected)
        write_json(output / 'comparison.json', {'metadata': metadata, 'results': results, 'validity': 'valid'})
        print('Results: ' + str(output / 'comparison.json'), flush=True)
    finally:
        try:
            if app is not None and app.poll() is None:
                os.killpg(app.pid, signal.SIGTERM)
                try:
                    app.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    os.killpg(app.pid, signal.SIGKILL)
                    app.wait()
        finally:
            if redis_id is not None:
                subprocess.run(['docker', 'rm', '-f', '-v', redis_id], check=True, capture_output=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-container', default='mysql')
    parser.add_argument('--redis-container', default='redis-cache', help='Existing Redis supplies image bytes only')
    parser.add_argument('--keys', type=int, default=20)
    parser.add_argument('--rate', type=int, default=20)
    parser.add_argument('--duration', type=int, default=15, help='Measurement seconds per variant')
    parser.add_argument('--warmup', type=int, default=5, help='Warm-up seconds per variant')
    parser.add_argument('--rounds', type=int, default=2, help='Alternate AB/BA order each round')
    parser.add_argument('--distributions', nargs='+', choices=['same-key', 'uniform', 'hotset-80-20'],
                        default=['same-key', 'uniform', 'hotset-80-20'])
    parser.add_argument('--skip-build', action='store_true', help='Use the existing JAR; its SHA is recorded')
    parser.add_argument('--java-home', help='JDK 21 with current timezone data; macOS defaults to java_home -v 21')
    args = parser.parse_args()
    os.chdir(ROOT)
    run(args)


if __name__ == '__main__':
    def terminate(signum, frame):
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, terminate)
    main()
