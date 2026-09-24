#!/usr/bin/env python3
"""Run bounded, read-only local cache experiments with owned apps and disposable Redis."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import time
import urllib.error

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('cache_comparison', HERE / 'run-local-comparison.py')
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)
ROOT = base.ROOT
require = base.require
command = base.command
write_json = base.write_json


def server_observation(before_text, after_text):
    before, after = base.parse_metrics(before_text), base.parse_metrics(after_text)

    def delta(names, labels=None):
        value = base.metric_sum(after, names, labels) - base.metric_sum(before, names, labels)
        require(math.isfinite(value) and value >= 0, 'Server counter reset or non-finite sample')
        return value

    tags = {'path': '/api/v1/accommodations/{accommodationId}', 'http_method': 'GET', 'query_type': 'SELECT'}
    outcomes = {result: delta(['accommodation_detail_cache_request_total'], {'result': result})
                for result in ['hit', 'hit_after_wait', 'coalesced', 'negative_hit',
                               'negative_coalesced', 'loaded', 'negative_loaded']}
    return {
        'requests': delta(['app_query_per_request_queries_count', 'app_query_per_request_count'], tags),
        'selects': delta(['app_query_per_request_queries_sum', 'app_query_per_request_sum'], tags),
        'loads': delta(['accommodation_detail_cache_load_duration_seconds_count']),
        'loadErrors': delta(['accommodation_detail_cache_load_duration_seconds_count'], {'result': 'error'}),
        'redisGetErrors': delta(['accommodation_detail_cache_redis_operation_total'],
                                {'operation': 'get', 'result': 'error'}),
        'outcomes': outcomes,
    }


def aggregate_observations(observations):
    totals = {key: sum(row[key] for row in observations)
              for key in ['requests', 'selects', 'loads', 'loadErrors', 'redisGetErrors']}
    totals['outcomes'] = {key: sum(row['outcomes'][key] for row in observations)
                          for key in observations[0]['outcomes']}
    totals['coalesced'] = totals['outcomes']['coalesced']
    totals['coalescingRatio'] = totals['coalesced'] / totals['requests'] if totals['requests'] else 0
    return totals


def evidence_reasons(result, observed, variant, scenario):
    reasons = []
    if scenario != 'capacity' and (result['completed'] < result['requestedSamples'] or result['dropped']):
        reasons.append('offered-load-not-delivered')
    if result['completed'] <= 0 or result['completed'] != observed['requests']:
        reasons.append('client-server-request-count-mismatch')
    if result['responseMismatches']:
        reasons.append('response-mismatch')
    if not all(isinstance(result['latencyMs'][key], (int, float))
               and math.isfinite(result['latencyMs'][key]) for key in ['p50', 'p95', 'p99']):
        reasons.append('missing-latency')
    # Validate precise SQL/load relations only when every request and DB load completed successfully.
    if result['errorRate'] == 0 and observed['loadErrors'] == 0:
        if observed['selects'] != 3 * observed['loads']:
            reasons.append('sql-loader-count-mismatch')
        if sum(observed['outcomes'].values()) != result['completed']:
            reasons.append('cache-outcome-count-mismatch')
        if variant == 'cache-off' and (observed['loads'] != result['completed']
                                       or observed['redisGetErrors'] != 0):
            reasons.append('cache-off-control-not-proven')
        if scenario in ('latency', 'capacity') and variant == 'cache-on':
            if observed['loads'] != 0 or observed['outcomes']['hit'] != result['completed']:
                reasons.append('warm-cache-not-proven')
        if scenario == 'outage':
            if observed['redisGetErrors'] != result['completed']:
                reasons.append('redis-outage-not-proven-for-every-request')
            if observed['outcomes']['hit'] or observed['outcomes']['hit_after_wait']:
                reasons.append('redis-hit-during-outage')
            if variant == 'coalescing-off' and (observed['coalesced'] or observed['loads'] != result['completed']):
                reasons.append('uncoalesced-control-not-proven')
    return reasons


def qualifies(result, p95_ms, error_rate):
    return (not result['evidenceReasons'] and result['completed'] >= result['requestedSamples']
            and result['dropped'] == 0 and result['errorRate'] <= error_rate
            and result['latencyMs']['p95'] <= p95_ms
            and result['successRps'] >= result['configuredRate'] * .99)


def capacity_bound(rows):
    passed = [row['configuredRate'] for row in rows if row['sloPassed']]
    failed = [row['configuredRate'] for row in rows if not row['sloPassed']]
    lower = max(passed) if passed else None
    # A failed generator schedule is not proof of the server's capacity ceiling.
    invalid = any(row['evidenceReasons'] or row['dropped'] for row in rows)
    return {'highestPassingRps': lower, 'firstNonPassingRps': min(failed) if failed else None,
            'status': 'inconclusive' if invalid else ('bracketed-on-grid' if failed else 'lower-bound-only')}


def client_vus(rate, p95_ms, outage):
    # Capacity probes need headroom for tail latency, not just the p95 target. Never grow VUs mid-run.
    floor = 80 if outage else 160
    seconds = 2 if outage else p95_ms / 1000 * 4
    return max(floor, min(320, math.ceil(rate * seconds)))


class LocalLab:
    def __init__(self, args):
        self.args, self.redis_id, self.paused, self.apps = args, None, False, []
        self.run_id = time.strftime('%Y%m%dT%H%M%S', time.gmtime()) + '-' + secrets.token_hex(3)
        self.output = ROOT / 'build/k6/cache-experiments' / self.run_id
        self.output.mkdir(parents=True, mode=0o700)
        self.token = secrets.token_urlsafe(32)
        self.results = []
        self.java_home = args.java_home or command(['/usr/libexec/java_home', '-v', '21'])
        self.java = str(Path(self.java_home) / 'bin/java')
        info = json.loads(command(['docker', 'inspect', args.mysql_container]))[0]
        ports = info['NetworkSettings']['Ports'].get('3306/tcp') or []
        require(info['State']['Running'] and len(ports) == 1 and ports[0]['HostIp'] == '127.0.0.1',
                'Existing MySQL must be running and loopback-only')
        self.settings = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
                             if '=' in line and not line.lstrip().startswith('#'))
        self.db_url = self.settings.get('SPRING_DATASOURCE_URL', '').strip()
        require(base.re.fullmatch(r'jdbc:mysql://(?:localhost|127\.0\.0\.1):' + ports[0]['HostPort']
                                  + r'/airbobdb(?:\?[^\r\n]*)?', self.db_url) is not None,
                'Existing .env must address the local MySQL container')
        version = base.mysql(args.mysql_container,
            'SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;')
        require(version == '28', 'Experiments require the existing V28 database')
        self.ids = [int(v) for v in base.mysql(args.mysql_container,
            "SELECT id FROM accommodation WHERE status='PUBLISHED' ORDER BY id LIMIT " + str(args.keys) + ';').splitlines()]
        require(len(self.ids) == args.keys, 'Not enough published accommodations')
        self.redis_image = json.loads(command(['docker', 'inspect', args.redis_container]))[0]['Image']
        self.source_jar = ROOT / 'build/libs/airbob.jar'
        self.jar = self.output / 'application-under-test.jar'
        self.metadata = {'runId': self.run_id, 'scope': 'local-validation', 'apps': args.apps,
            'appCommit': command(['git', 'rev-parse', 'HEAD'], cwd=ROOT),
            'sourceTreeDirty': bool(command(['git', 'status', '--porcelain'], cwd=ROOT)),
            'datasetId': self.settings.get('AIRBOB_DATASET_ID', 'local-airbobdb').strip(),
            'mysqlContainerId': info['Id'], 'redisImageId': self.redis_image, 'flywayVersion': version,
            'keys': args.keys, 'endpoint': '/api/v1/accommodations/{id}', 'rounds': args.rounds,
            'selection': 'first-published-ids-local-validation',
            'javaExecutable': self.java, 'heapMbPerApp': 384, 'activeProcessorCountPerJvm': 2,
            'tomcatThreadsPerApp': 64, 'hikariConnectionsPerApp': 10,
            'p95LimitMs': args.p95_ms, 'errorRateLimit': args.error_rate,
            'rates': args.rates, 'durationSeconds': args.duration, 'warmupSeconds': args.warmup,
            'burstRequests': args.burst, 'fault': 'docker-pause-owned-cache-redis',
            'sqlPerSuccessfulLoad': 3, 'artificialDatabaseDelay': False,
            'scriptSha256': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                             for path in [HERE / 'run-local-experiments.py',
                                          HERE / 'accommodation-detail-experiment.js',
                                          HERE.parent / 'lib/cache-experiment-config.js',
                                          HERE / 'run-local-comparison.py',
                                          HERE.parent / 'lib/accommodation-cache-benchmark.js']},
            'limitations': ['client, apps and database share local hardware',
                            'burst release is client-side; HTTP arrival spread is recorded',
                            'coalescing is per JVM with independent loads after wait timeout',
                            'cache-off baseline uses the same V1 API; legacy V2/V1 runner remains separate']}
        self.expected = None
        self.fixture = self.output / 'fixture.json'
        self.monitor = None
        if args.grafana:
            from cache_experiment_monitoring import ExperimentMonitoring
            self.monitor = ExperimentMonitoring(ROOT, self.run_id, command)
            monitor_source = HERE / 'cache_experiment_monitoring.py'
            self.metadata['scriptSha256'][str(monitor_source.relative_to(ROOT))] = hashlib.sha256(monitor_source.read_bytes()).hexdigest()
        self.metadata['liveMonitoring'] = bool(self.monitor)
        if self.monitor:
            self.metadata['limitations'].append('opt-in 1s metric scrapes and Grafana share the local machine')

    def build(self):
        if not self.args.skip_build:
            with (self.output / 'build.log').open('w') as log:
                subprocess.run(['./gradlew', 'bootJar', '-x', 'test', '-x', 'generateAdoc',
                                '-x', 'asciidoctor', '-x', 'copyDocument', '--console=plain'],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True,
                    env=base.process_environment() | {'JAVA_HOME': self.java_home})
        require(self.source_jar.is_file(), 'Application JAR missing')
        # Another local build must not change the image between experiment groups.
        shutil.copyfile(self.source_jar, self.jar)
        self.metadata['appJarSha256'] = hashlib.sha256(self.jar.read_bytes()).hexdigest()
        write_json(self.output / 'run.json', self.metadata)

    def start_redis(self):
        exporter_port = ['--publish', '127.0.0.1::9121'] if self.monitor else []
        self.redis_id = command(['docker', 'run', '--detach', '--name', 'airbob-cache-experiment-' + self.run_id.lower(),
            '--label', 'airbob.cache-experiment.run=' + self.run_id, '--publish', '127.0.0.1::6379',
            *exporter_port, self.redis_image, 'redis-server', '--save', '', '--appendonly', 'no'])
        info = self.owned_redis()
        self.redis_port = info['NetworkSettings']['Ports']['6379/tcp'][0]['HostPort']
        if self.monitor:
            self.monitor.attach_redis(self.redis_id, info['NetworkSettings']['Ports']['9121/tcp'][0]['HostPort'])

    def owned_redis(self):
        require(self.redis_id is not None, 'No owned experiment Redis')
        info = json.loads(command(['docker', 'inspect', self.redis_id]))[0]
        require(info['Config']['Labels'].get('airbob.cache-experiment.run') == self.run_id,
                'Redis ownership changed')
        return info

    def flush(self):
        self.owned_redis()
        require(not self.paused, 'Cannot flush paused Redis')
        require(command(['docker', 'exec', self.redis_id, 'redis-cli', 'FLUSHDB']) == 'OK', 'Reset failed')

    def pause(self):
        self.owned_redis()
        command(['docker', 'pause', self.redis_id])
        self.paused = True
        require(self.owned_redis()['State']['Paused'], 'Redis pause not observed')

    def unpause(self):
        if self.redis_id and self.owned_redis()['State']['Paused']:
            command(['docker', 'unpause', self.redis_id])
        self.paused = False

    def stop_apps(self):
        for process, _, stream in self.apps:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait(timeout=5)
            stream.close()
        self.apps = []

    @property
    def origins(self):
        return [origin for _, origin, _ in self.apps]

    @contextmanager
    def group(self, label, cache_enabled, coalescing=True):
        self.flush()
        try:
            if self.monitor:
                self.monitor.set_phase(label, 'starting-apps')
            for index in range(self.args.apps):
                env = base.process_environment() | {
                    'JAVA_HOME': self.java_home, 'SPRING_PROFILES_ACTIVE': 'dev,cache-benchmark',
                    'BENCHMARK_READ_MODEL_TOKEN': self.token, 'SPRING_DATASOURCE_URL': self.db_url,
                    'SPRING_DATASOURCE_USERNAME': self.settings['SPRING_DATASOURCE_USERNAME'].strip(),
                    'SPRING_DATASOURCE_PASSWORD': self.settings['SPRING_DATASOURCE_PASSWORD'].strip(),
                    'ACCOMMODATION_DETAIL_CACHE_ENABLED': str(cache_enabled).lower(),
                    'CACHE_BENCHMARK_LOCAL_COALESCING_ENABLED': str(coalescing).lower(),
                    'ACCOMMODATION_DETAIL_CACHE_REDIS_HOST': '127.0.0.1',
                    'ACCOMMODATION_DETAIL_CACHE_REDIS_PORT': self.redis_port,
                    'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '10',
                    'SERVER_TOMCAT_THREADS_MAX': '64', 'SERVER_TOMCAT_THREADS_MIN_SPARE': '8',
                    'AWS_ACCESS_KEY_ID': 'dummy', 'AWS_SECRET_ACCESS_KEY': 'dummy',
                    'AWS_EC2_METADATA_DISABLED': 'true', 'AWS_REGION': 'ap-northeast-2',
                    'SPRING_CLOUD_AWS_REGION_STATIC': 'ap-northeast-2',
                }
                path = self.output / (label + '-app-' + str(index) + '.log')
                stream = path.open('w')
                process = subprocess.Popen([self.java, '-Xms384m', '-Xmx384m', '-XX:ActiveProcessorCount=2',
                    '-Duser.timezone=UTC', '-jar', str(self.jar), '--server.address=127.0.0.1', '--server.port=0'],
                    cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
                self.apps.append((process, None, stream))
                origin = base.wait_for_app(process, path)
                self.apps[-1] = (process, origin, stream)
                for version in [1, 2]:
                    try:
                        base.get_json(origin, '/api/v' + str(version) + '/accommodations/' + str(self.ids[0]))
                        raise RuntimeError('Benchmark token was not enforced')
                    except urllib.error.HTTPError as error:
                        require(error.code == 403, 'Unexpected missing-token response')
                entries = base.preflight(origin, self.token, self.ids, self.expected)
                if self.expected is None:
                    self.expected = {row['id']: row['data'] for row in entries}
                    write_json(self.fixture, {'schemaVersion': 1, 'datasetId': self.metadata['datasetId'],
                                              'accommodations': entries})
            print(label + ': isolated app group ready (' + str(self.args.apps) + ' JVM)', flush=True)
            if self.monitor:
                self.monitor.attach_apps(self.origins, label)
            yield
        finally:
            try:
                self.unpause()
            finally:
                try:
                    if self.monitor:
                        self.monitor.detach_apps()
                finally:
                    self.stop_apps()

    def client(self, label, kind, rate, seconds, distribution='same-key', outage=False):
        path = self.output / (label + '.json')
        env = base.process_environment() | {
            'EXPERIMENT_ORIGINS': json.dumps(self.origins), 'EXPERIMENT_KIND': kind,
            'CACHE_BENCHMARK_FIXTURE': str(self.fixture), 'RESULT_PATH': str(path),
            'BENCHMARK_READ_MODEL_TOKEN': self.token, 'K6_NO_USAGE_REPORT': 'true',
            'RATE': str(rate), 'SECONDS': str(seconds), 'BURST': str(self.args.burst),
            # Fault requests wait for the one-second Redis timeout; provision their client concurrency separately.
            'VUS': str(client_vus(rate, self.args.p95_ms, outage)),
            'DISTRIBUTION': distribution,
        }
        with (self.output / (label + '.log')).open('w') as stream:
            process = subprocess.run(['k6', 'run', '--quiet', str(HERE / 'accommodation-detail-experiment.js')],
                cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=seconds + 45)
        require(process.returncode in (0, 99) and path.is_file(), 'k6 failed; inspect ' + label + '.log')
        result = json.loads(path.read_text())
        result.update(distribution=distribution, clientVUs=int(env['VUS']) if kind == 'rate' else self.args.burst)
        write_json(path, result)
        return result

    def warm(self, label, rate=20):
        if self.monitor:
            self.monitor.set_phase(label, 'warmup')
        for origin in self.origins:
            base.preflight(origin, self.token, self.ids, self.expected)
        result = self.client(label + '-warmup', 'rate', rate, self.args.warmup, 'uniform')
        require(result['errorRate'] == 0 and result['responseMismatches'] == 0,
                'Warm-up failed; this stage cannot establish a comparison')

    def measure(self, label, scenario, variant, round_number, kind='rate', rate=20, distribution='same-key'):
        if self.monitor:
            self.monitor.set_phase(label, 'measuring')
        starts = [base.scrape(origin) for origin in self.origins]
        result = self.client(label, kind, rate, self.args.duration, distribution, outage=scenario == 'outage')
        ends = [base.scrape(origin) for origin in self.origins]
        instances = [server_observation(before, after) for before, after in zip(starts, ends)]
        observed = aggregate_observations(instances)
        for index, (before, after) in enumerate(zip(starts, ends)):
            (self.output / (label + '-app-' + str(index) + '-start.prom')).write_text(before)
            (self.output / (label + '-app-' + str(index) + '-end.prom')).write_text(after)
        result.update(label=label, scenario=scenario, variant=variant, round=round_number,
                      server=observed, instances=instances)
        result['evidenceReasons'] = evidence_reasons(result, observed, variant, scenario)
        if kind == 'burst' and result['completed'] != self.args.burst:
            result['evidenceReasons'].append('incomplete-burst')
        if scenario == 'capacity':
            result['sloPassed'] = qualifies(result, self.args.p95_ms, self.args.error_rate)
        write_json(self.output / (label + '.json'), result)
        self.results.append(result)
        self.save('running')
        if self.monitor:
            self.monitor.record(result)
            self.monitor.settle()
        print(label + ': requests=' + str(result['completed']) + ', p95=' + str(result['latencyMs']['p95'])
              + 'ms, DB loads=' + str(observed['loads']) + ', coalesced=' + str(observed['coalesced'])
              + ', errors=' + str(result['errorRate']) + ', dropped=' + str(result['dropped']), flush=True)
        require(not result['evidenceReasons'], 'Invalid evidence: ' + label + ': ' + str(result['evidenceReasons']))
        return result

    def save(self, state):
        capacities = []
        for round_number in range(1, self.args.rounds + 1):
            for variant in ['cache-off', 'cache-on']:
                rows = [row for row in self.results if row['scenario'] == 'capacity'
                        and row['variant'] == variant and row['round'] == round_number]
                if rows:
                    capacities.append({'round': round_number, 'variant': variant, **capacity_bound(rows)})
        write_json(self.output / 'comparison.json',
                   {'metadata': self.metadata, 'state': state, 'capacity': capacities, 'results': self.results})
        lines = ['# 숙소 상세 캐시 로컬 실험', '',
                 '- 범위: 로컬 동작·부하 검증. 클라이언트·앱·DB가 같은 장비를 공유하며 AWS 결과가 아님.',
                 '- 같은 V1 API·JAR·앱 수·JVM 설정·DB를 유지하고 캐시 또는 로컬 요청 병합만 전환.',
                 '- 최대 처리량 판정: p95 ≤ ' + str(self.args.p95_ms) + 'ms, 오류율 ≤ '
                 + str(self.args.error_rate) + ', 요청 누락 0, 목표 RPS의 99% 이상 완료.',
                 '- 부하 상한까지 통과한 경우 최대 처리량을 확정하거나 증가율을 계산하지 않음.',
                 '- cold-miss 버스트는 클라이언트 동시 출발 조건이며 모든 요청의 서버 도착/미스를 보장하지 않음.',
                 '- 장애는 실험 소유 Redis를 pause하여 응답 불능으로 만듦. 원본 DB에 인위적 지연을 넣지 않음.',
                 '', '| 실험 | 조건 | 회차 | 설정 부하 | 요청 | p95(ms) | 오류율 | DB 로딩 | SELECT | 병합률 |',
                 '|---|---|---:|---|---:|---:|---:|---:|---:|---:|']
        for row in self.results:
            s = row['server']
            p95 = row['latencyMs']['p95']
            p95_text = format(p95, '.2f') if isinstance(p95, (int, float)) else '미수집'
            load_text = (str(row['configuredRate']) + ' RPS' if row['kind'] == 'rate'
                         else str(row['requestedSamples']) + ' VU 버스트')
            lines.append('| ' + row['scenario'] + ' ' + row['kind'] + ' | ' + row['variant'] + ' | '
                + str(row['round']) + ' | ' + load_text + ' | ' + str(row['completed']) + ' | '
                + p95_text + ' | ' + format(row['errorRate'], '.2%') + ' | '
                + str(int(s['loads'])) + ' | ' + str(int(s['selects'])) + ' | '
                + format(s['coalescingRatio'], '.2%') + ' |')
        lines += ['', '## 처리량 탐색 범위', '']
        for bound in capacities:
            lines.append('- ' + bound['variant'] + ' r' + str(bound['round']) + ': 최고 통과 '
                + str(bound['highestPassingRps']) + ' RPS, 최초 미통과 '
                + str(bound['firstNonPassingRps']) + ' RPS (' + bound['status'] + ')')
        lines += ['', '상태: ' + state, '', '상세 조건, 인스턴스별 지표와 클라이언트 출발 시각 편차는 comparison.json 참조.']
        if self.monitor:
            lines += ['', 'Grafana 실시간 관측 모드: 실험 앱 1초 수집. 로컬 측정에는 모니터링 부하도 포함됨.',
                      'Grafana: http://127.0.0.1:3001/d/airbob-cache-experiments?var-run_id=' + self.run_id]
        (self.output / 'report.md').write_text('\n'.join(lines) + '\n')

    def execute(self):
        try:
            self.build()
            if self.monitor:
                self.monitor.start()
            self.start_redis()
            ordinary = set(self.args.scenarios) & {'latency', 'capacity', 'miss'}
            if ordinary:
                for round_number in range(1, self.args.rounds + 1):
                    variants = ['cache-off', 'cache-on'] if round_number % 2 else ['cache-on', 'cache-off']
                    for variant in variants:
                        label = variant + '-r' + str(round_number)
                        with self.group(label, cache_enabled=variant == 'cache-on'):
                            self.warm(label)
                            if 'latency' in ordinary:
                                self.measure(label + '-latency', 'latency', variant, round_number, rate=self.args.rate)
                            if 'capacity' in ordinary:
                                for rate in self.args.rates:
                                    prefix = label + '-capacity-' + str(rate)
                                    self.warm(prefix, min(rate, 80))
                                    result = self.measure(prefix, 'capacity', variant, round_number,
                                                          rate=rate, distribution='uniform')
                                    if not result['sloPassed']:
                                        break
                            if 'miss' in ordinary:
                                self.warm(label + '-miss')
                                self.flush()
                                self.measure(label + '-miss', 'miss', variant, round_number, kind='burst')
            if 'outage' in self.args.scenarios:
                for round_number in range(1, self.args.rounds + 1):
                    variants = ['coalescing-off', 'coalescing-on'] if round_number % 2 else ['coalescing-on', 'coalescing-off']
                    for variant in variants:
                        label = variant + '-r' + str(round_number)
                        with self.group(label, cache_enabled=True, coalescing=variant == 'coalescing-on'):
                            self.warm(label)
                            self.pause()
                            # Establish the same outage before counting either treatment's requests.
                            for origin in self.origins:
                                base.get_json(origin, '/api/v1/accommodations/' + str(self.ids[0]), self.token)
                            self.measure(label + '-outage-rate', 'outage', variant, round_number, rate=self.args.outage_rate)
                            self.measure(label + '-outage-burst', 'outage', variant, round_number, kind='burst')
                            self.unpause()
                            for origin in self.origins:
                                base.preflight(origin, self.token, self.ids, self.expected)
                            print(label + ': Redis recovery and response equivalence verified', flush=True)
            self.save('complete')
            print('Report: ' + str(self.output / 'report.md'), flush=True)
        except BaseException:
            self.save('incomplete')
            raise
        finally:
            try:
                self.unpause()
            finally:
                try:
                    self.stop_apps()
                finally:
                    try:
                        try:
                            if self.monitor:
                                self.monitor.close()
                        finally:
                            if self.redis_id is not None:
                                self.owned_redis()
                                command(['docker', 'rm', '-f', '-v', self.redis_id])
                    finally:
                        self.jar.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-container', default='mysql')
    parser.add_argument('--redis-container', default='redis-cache')
    parser.add_argument('--java-home')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--grafana', action='store_true',
                        help='Observe isolated experiments through the existing local Prometheus/Grafana')
    parser.add_argument('--apps', type=int, choices=[1, 2], default=1)
    parser.add_argument('--keys', type=int, default=20)
    parser.add_argument('--rate', type=int, default=40)
    parser.add_argument('--outage-rate', type=int, default=40)
    parser.add_argument('--rates', type=int, nargs='+', default=[40, 100, 200, 400, 800])
    parser.add_argument('--duration', type=int, default=15)
    parser.add_argument('--warmup', type=int, default=5)
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--burst', type=int, default=60)
    parser.add_argument('--p95-ms', type=float, default=100)
    parser.add_argument('--error-rate', type=float, default=0)
    parser.add_argument('--scenarios', nargs='+', choices=['latency', 'capacity', 'miss', 'outage'],
                        default=['latency', 'capacity', 'miss', 'outage'])
    args = parser.parse_args()
    require(5 <= args.keys <= 200 and 1 <= args.rounds <= 3, 'Invalid key/round bound')
    require(5 <= args.duration <= 120 and 1 <= args.warmup <= 30, 'Invalid phase duration')
    require(2 <= args.burst <= 300 and 1 <= args.rate <= 1000 and 1 <= args.outage_rate <= 100,
            'Invalid local load bound')
    require(args.rates == sorted(set(args.rates)) and 1 <= min(args.rates) <= max(args.rates) <= 1000,
            'Rates must be increasing distinct local steps, at most 1000 RPS')
    require(math.isfinite(args.p95_ms) and 1 <= args.p95_ms <= 1000
            and math.isfinite(args.error_rate) and 0 <= args.error_rate <= .01, 'Invalid SLO')
    os.chdir(ROOT)
    LocalLab(args).execute()


if __name__ == '__main__':
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    main()
