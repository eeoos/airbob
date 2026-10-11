#!/usr/bin/env python3
"""Compare local cache OFF/ON capacity while retaining bottleneck evidence."""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import time
import urllib.request

from cache_capacity_diagnostics import Diagnostics, other_load_generators
from cache_capacity_probes import LatencyProbes

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('cache_experiments', HERE / 'run-local-experiments.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)
base = runner.base


class CapacityLab(runner.LocalLab):
    def __init__(self, args):
        super().__init__(args)
        self.metadata.update(scope='local-capacity-investigation', diagnostics=True, clientVUs=args.client_vus,
                             variants=args.variants, cpuLimitEnforced=False, jfrEnabled=args.jfr,
                             responseCheck=args.response_check, warmupRateCap=args.warmup_rate_cap,
                             latencyProbes=args.trace_latency)
        for name in ['run-local-capacity.py', 'cache_capacity_diagnostics.py', 'cache_capacity_probes.py']:
            path = HERE / name
            self.metadata['scriptSha256'][str(path.relative_to(runner.ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
        self.metadata['limitations'].append('resource sampling and other running local programs share the host')

    def build(self):
        super().build()
        for name, digest in self.metadata['scriptSha256'].items():
            source = (runner.ROOT / name).read_bytes()
            base.require(hashlib.sha256(source).hexdigest() == digest, 'Runner changed during preparation')
            target = self.output / 'source-snapshots' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source)

    def recording(self, directory, start):
        for index, (process, _, _) in enumerate(self.apps):
            arguments = [str(Path(self.java).with_name('jcmd')), str(process.pid),
                         'JFR.start' if start else 'JFR.stop', 'name=CacheCapacity',
                         'filename=' + str(directory / ('app-' + str(index) + '.jfr'))]
            if start:
                arguments += ['settings=profile', 'dumponexit=true']
            with (directory / ('jfr-' + ('start' if start else 'stop') + '-' + str(index) + '.log')).open('w') as log:
                subprocess.run(arguments, check=True, stdout=log, stderr=subprocess.STDOUT, timeout=30)

    def warm(self, label, rate=20):
        # A full warm-cache measurement needs fresh TTLs even after a long series of probes.
        self.flush()
        if self.args.response_check == 'body':
            entries = []
            for accommodation_id in self.ids:
                bodies = []
                for origin in self.origins:
                    request = urllib.request.Request(origin + '/api/v1/accommodations/' + str(accommodation_id),
                                                     headers={'X-Benchmark-Token': self.token})
                    with urllib.request.urlopen(request, timeout=15) as response:
                        body = response.read().decode()
                    value = json.loads(body)
                    base.require(value.get('success') is True and base.canonical_detail(value['data'])
                                 == base.canonical_detail(self.expected[accommodation_id]),
                                 'Exact-body fixture differs from verified database response')
                    bodies.append(body)
                base.require(len(set(bodies)) == 1, 'App instances serialize different response bodies')
                entries.append({'id': accommodation_id, 'data': self.expected[accommodation_id],
                                'responseBody': bodies[0]})
            base.write_json(self.fixture, {'schemaVersion': 1, 'datasetId': self.metadata['datasetId'],
                                           'accommodations': entries})
        super().warm(label, rate)

    def client(self, label, kind, rate, seconds, distribution='same-key', outage=False):
        path = self.output / (label + '.json')
        env = base.process_environment() | {
            'EXPERIMENT_ORIGINS': json.dumps(self.origins), 'EXPERIMENT_KIND': kind,
            'CACHE_BENCHMARK_FIXTURE': str(self.fixture), 'RESULT_PATH': str(path),
            'BENCHMARK_READ_MODEL_TOKEN': self.token, 'K6_NO_USAGE_REPORT': 'true',
            'RATE': str(rate), 'SECONDS': str(seconds), 'BURST': str(self.args.burst),
            'VUS': str(self.args.client_vus), 'DISTRIBUTION': distribution,
            'LOCAL_CAPACITY_DIAGNOSTICS': 'true', 'EXPERIMENT_RESPONSE_CHECK': self.args.response_check}
        diagnosis = None if label.endswith('-warmup') else Diagnostics(self, base, self.output / (label + '-diagnostics'))
        if diagnosis and self.args.trace_latency:
            env['GODEBUG'] = 'gctrace=1'
        client_started = None

        def execute():
            nonlocal client_started
            with (self.output / (label + '.log')).open('w') as stream:
                client_started = time.time() * 1000
                process = subprocess.Popen(['k6', 'run', '--address', '', '--quiet',
                                            str(HERE / 'accommodation-detail-experiment.js')],
                                           cwd=runner.ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
                if diagnosis:
                    diagnosis.k6_pid = process.pid
                try:
                    code = process.wait(timeout=seconds + 45)
                except BaseException:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                    raise
                base.require(code in (0, 99) and path.is_file(), 'k6 failed; inspect ' + label + '.log')

        if diagnosis:
            with diagnosis:
                probes = LatencyProbes(self.origins, self.redis_port, diagnosis.directory) if self.args.trace_latency else None
                if probes:
                    probes.start()
                recording_started = False
                try:
                    if self.args.jfr:
                        self.recording(diagnosis.directory, True)
                        recording_started = True
                    execute()
                finally:
                    if probes:
                        probes.close()
                    if recording_started:
                        self.recording(diagnosis.directory, False)
        else:
            execute()
        result = json.loads(path.read_text())
        result.update(distribution=distribution, clientVUs=self.args.client_vus,
                      clientProcessStartedEpochMs=client_started, latencyProbes=bool(diagnosis and self.args.trace_latency))
        if diagnosis:
            result['diagnostics'] = diagnosis.result(result)
        base.write_json(path, result)
        return result

    def measure(self, *args, **kwargs):
        result = super().measure(*args, **kwargs)
        diagnostics = result['diagnostics']
        result['capacityObservationReasons'] = diagnostics['generatorReasons']
        if result['dropped']:
            result['capacityObservationReasons'].append('client-dropped-iterations')
        result['requestCriteriaPassed'] = result['sloPassed']
        result['sloPassed'] = result['sloPassed'] and not result['capacityObservationReasons']
        base.write_json(self.output / (result['label'] + '.json'), result)
        self.save('running')
        print('  diagnosis: ' + json.dumps({
            'hostCpuMaxPercent': diagnostics['host']['cpuMaxPercent'],
            'hikariPendingMax': [app['hikari']['pendingMax'] for app in diagnostics['apps']],
            'hikariAcquireMeanMs': [app['hikari']['acquire']['meanMs'] for app in diagnostics['apps']],
            'mysqlCpuCores': diagnostics['containers']['mysql']['averageCpuCores'],
            'redisCpuCores': diagnostics['containers']['redis']['averageCpuCores'],
            'capacityObservationReasons': result['capacityObservationReasons']}), flush=True)
        return result

    def save(self, state):
        super().save(state)
        with (self.output / 'report.md').open('a') as stream:
            stream.write('\n## 자원 관측\n\n')
            stream.write('| 조건 | RPS | 풀 대기 최대 | 커넥션 획득 평균 ms | MySQL CPU 코어 | Redis CPU 코어 | 호스트 CPU 최대 % | 요청 기준 충족 | 판정 제한 |\n')
            stream.write('|---|---:|---|---|---:|---:|---:|---|---|\n')
            for row in self.results:
                d = row.get('diagnostics')
                if not d:
                    continue
                stream.write('| ' + row['variant'] + ' r' + str(row['round']) + ' | ' + str(row['configuredRate'])
                             + ' | ' + str([a['hikari']['pendingMax'] for a in d['apps']])
                             + ' | ' + str([a['hikari']['acquire']['meanMs'] for a in d['apps']])
                             + ' | ' + str(round(d['containers']['mysql']['averageCpuCores'], 3))
                             + ' | ' + str(round(d['containers']['redis']['averageCpuCores'], 3))
                             + ' | ' + str(d['host']['cpuMaxPercent'])
                             + ' | ' + str(row.get('requestCriteriaPassed', runner.qualifies(row, self.args.p95_ms, self.args.error_rate)))
                             + ' | ' + ', '.join(row.get('capacityObservationReasons', [])) + ' |\n')
            stream.write('\nCPU 1코어는 논리 CPU 하나를 완전히 사용한 시간이다. JVM ActiveProcessorCount는 CPU 사용량 제한이 아니다.\n')
            stream.write('호스트 자원 부족 또는 k6 요청 누락이 있으면 서버 자체의 상한으로 확정하지 않는다.\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mysql-container', default='mysql')
    parser.add_argument('--redis-container', default='redis-cache')
    parser.add_argument('--java-home')
    parser.add_argument('--skip-build', action='store_true')
    parser.add_argument('--grafana', action='store_true')
    parser.add_argument('--jfr', action='store_true', help='Profile owned JVMs; use this for a separate diagnostic run')
    parser.add_argument('--trace-latency', action='store_true',
                        help='Diagnostic only: independent Redis/liveness probes and k6 GC logs')
    parser.add_argument('--apps', type=int, choices=[1, 2], default=1)
    parser.add_argument('--pool-size', type=int, default=10)
    parser.add_argument('--keys', type=int, default=20)
    parser.add_argument('--rates', type=int, nargs='+', default=[100, 250, 500, 1000, 2000, 4000, 8000, 16000])
    parser.add_argument('--duration', type=int, default=30)
    parser.add_argument('--warmup', type=int, default=10)
    parser.add_argument('--warmup-rate-cap', type=int, default=1000,
                        help='Warm each capacity stage at min(target RPS, this cap) before measuring')
    parser.add_argument('--rounds', type=int, default=2)
    parser.add_argument('--p95-ms', type=float, default=100)
    parser.add_argument('--client-vus', type=int, default=512)
    parser.add_argument('--response-check', choices=['body', 'canonical'], default='body')
    parser.add_argument('--variants', nargs='+', choices=['cache-off', 'cache-on'], default=['cache-off', 'cache-on'])
    args = parser.parse_args()
    base.require(4 <= args.pool_size <= 40 and 5 <= args.keys <= 200, 'Invalid pool/key bound')
    base.require(10 <= args.duration <= 600 and 2 <= args.warmup <= 30 and 1 <= args.rounds <= 3,
                 'Invalid duration/round bound')
    base.require(args.rates == sorted(set(args.rates)) and 1 <= min(args.rates) <= max(args.rates) <= 20000,
                 'Use increasing distinct rates up to 20000 RPS')
    base.require(64 <= args.client_vus <= 4000 and math.isfinite(args.p95_ms) and 1 <= args.p95_ms <= 1000,
                 'Invalid client/SLO bound')
    base.require(1 <= args.warmup_rate_cap <= 20000, 'Invalid warm-up rate cap')
    base.require(len(args.variants) == len(set(args.variants)), 'Duplicate variants')
    args.rate, args.outage_rate, args.burst, args.error_rate, args.scenarios = 40, 40, 60, 0, ['capacity']
    base.require(not other_load_generators(), 'Another k6 process is running; finish it before capacity measurement')
    os.chdir(runner.ROOT)
    lab = CapacityLab(args)
    print('Capacity evidence: ' + str(lab.output), flush=True)
    lab.execute()


if __name__ == '__main__':
    def terminate(signum, frame):
        raise SystemExit(128 + signum)
    signal.signal(signal.SIGTERM, terminate)
    main()
