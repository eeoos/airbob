#!/usr/bin/env python3
"""Run coupon A/B experiments on an already running, isolated AWS lab."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import statistics
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from coupon_experiment import capacity_summary, evaluate, fingerprint, order, plan, require, validate
from coupon_monitoring import HTTP, GeneratorMonitor, cloudwatch_cpu, delta, snapshot

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
FIXTURES = '/api/v1/admin/coupons/benchmark/fixtures'


def read_private(path, maximum=512):
    path = Path(path)
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0
            and 0 < info.st_size <= maximum, 'Credential file must be an owned private regular file')
    return path.read_text()


def credential(path):
    value = read_private(path).strip()
    require(re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,512}', value) is not None, 'Invalid credential file')
    return value


def save(path, value):
    # Atomic replacement keeps the last completed report readable after interruption.
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')
    temporary.replace(path)


def manifest_contract(c, needed):
    path = Path(c['benchmarkDatasetManifest'])
    require(0 < path.stat().st_size <= 16 * 1024 * 1024, 'Invalid manifest size')
    # Use the existing canonical validator; do not invent a second dataset contract in Python.
    script = """
const fs = require('node:fs');
const { parseBenchmarkDatasetManifest } = require(process.argv[1]);
try {
  const manifest = parseBenchmarkDatasetManifest(fs.readFileSync(process.argv[2], 'utf8'));
  const capsule = manifest.capsules.find(c => c.capsuleId === 'coupon-accounts-v1');
  if (!capsule || capsule.accountPool.capacity < Number(process.argv[3])) process.exit(2);
} catch (_) { process.exit(2); }
"""
    result = subprocess.run(['node', '-e', script, str(HERE / 'benchmark-dataset-manifest-validator.js'),
                             str(path), str(needed)], capture_output=True, timeout=30)
    require(result.returncode == 0, 'Invalid manifest or insufficient coupon account capacity')
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_inputs(c):
    """Read local input files only. No logins, HTTP requests, or AWS commands."""
    needed = plan(c)['requiredUniqueSessions']
    manifest_hash = manifest_contract(c, needed)
    manifest = json.loads(Path(c['benchmarkDatasetManifest']).read_text())
    require(manifest.get('datasetVersion') == 'coupon-accounts-v1',
            'AWS coupon-performance requires a V28 coupon-accounts-v1 manifest bound to the restored dataset')
    credential(c['adminSessionFile'])
    credential(c['benchmarkTokenFile'])
    if c['accountPasswordFile'] is not None:
        password = read_private(c['accountPasswordFile'], 256)
        require(not any(ch in password for ch in '\r\n\0'), 'Invalid account password file')
    else:
        with tempfile.TemporaryDirectory(prefix='airbob-coupon-input-check-') as temporary:
            # No password file means ensure() can only inspect; it cannot invoke login preparation.
            Sessions(c, temporary, manifest_hash, needed).ensure()
    return {'state': 'local-inputs-checked', 'networkCallsPerformed': 0, 'awsCallsPerformed': 0,
            'manifestSha256': manifest_hash, 'requiredUniqueSessions': needed,
            'sourceDataset': manifest['sourceDataset'], 'runtimeAuthenticationVerified': False}


class Sessions:
    def __init__(self, c, temporary, manifest_hash, needed):
        self.c, self.temporary, self.manifest_hash, self.needed = c, temporary, manifest_hash, needed
        self.path = Path(c['sessionFixture'])
        self.refresh_number = 0

    def ensure(self):
        # Keep a 15-minute margin on the application's one-hour session TTL.
        budget = self.c['warmupSeconds'] + self.c['durationSeconds'] + 2 * self.c['cooldownSeconds'] + 120
        age = time.time() - self.path.stat().st_mtime if self.path.exists() else math.inf
        if age + budget >= 45 * 60:
            require(self.c['accountPasswordFile'] is not None,
                    'Session fixture is missing/old; supply accountPasswordFile or prepare fresh sessions')
            started = time.time()
            self.refresh_number += 1
            self.path = Path(self.temporary) / f'sessions-{self.refresh_number}.json'
            result = subprocess.run([
                'node', str(HERE / 'prepare-coupon-sessions.js'), '--base-url', self.c['baseUrl'],
                '--manifest', self.c['benchmarkDatasetManifest'], '--password-file', self.c['accountPasswordFile'],
                '--session-output', str(self.path), '--required-capacity', str(self.needed),
            ], capture_output=True, timeout=1800)
            require(result.returncode == 0, 'Coupon session preparation failed; no credentials were logged')
            # File creation time is later than the oldest login; preserve that conservative timestamp.
            os.utime(self.path, (started, started))
            require(time.time() - started + budget < 45 * 60, 'Session preparation was too slow for this workload')
        fixture = json.loads(read_private(self.path, 512 * 1024 * 1024))
        sessions = fixture.get('sessions', [])
        require(fixture.get('datasetVersion') == 'coupon-issuance-v2'
                and fixture.get('benchmarkDatasetManifestSha256') == self.manifest_hash,
                'Session fixture does not match the dataset manifest')
        require(isinstance(sessions, list) and len(sessions) >= self.needed
                and all(isinstance(s, str) and re.fullmatch(r'[A-Za-z0-9._~-]{16,512}', s) for s in sessions)
                and len(set(sessions)) == len(sessions), 'Insufficient or invalid unique sessions')
        return self.path


def aggregates(rows):
    output = []
    for scenario, rate, variant in sorted({(r['scenario'], r['rate'], r['variant']) for r in rows}):
        group = [r for r in rows if (r['scenario'], r['rate'], r['variant']) == (scenario, rate, variant)]
        valid = [r for r in group if r['evaluation']['valid']]
        result = {'scenario': scenario, 'targetRps': rate, 'variant': variant, 'rounds': len(group),
                  'validRounds': len(valid), 'passingRounds': sum(r['evaluation']['sloPassed'] for r in group)}
        if valid and len(valid) == len(group):
            for key, getter in {
                'queries': lambda r: r['server']['queries'],
                'dbCpuPercent': lambda r: r['cpu']['averagePercent'],
                'successP95Ms': lambda r: r['client']['successDuration'].get('p(95)'),
                'successfulRps': lambda r: r['client']['successRate'],
            }.items():
                values = [getter(r) for r in valid]
                values = [v for v in values if v is not None and math.isfinite(v)]
                if values:
                    result[key] = {'median': statistics.median(values), 'min': min(values), 'max': max(values)}
        output.append(result)
    return output


def render(report):
    lines = ['# 쿠폰 DB 조건부 UPDATE / Redis Lua 측정', '',
             f"실행 상태: {report['status']} · 앱: `{report['appVersion']}`", '',
             '최대치는 충분한 재고에서 모든 라운드가 기준을 만족한 **설정 RPS 단계**다. '
             '`lower-bound-only`는 상한을 찾지 못했다는 뜻이다.', '',
             '| 방식 | 판정 | 확인된 성공 발급 목표 RPS | 첫 실패 RPS |',
             '|---|---|---:|---:|']
    for variant, value in report['capacity'].items():
        lines.append(f"| {variant} | {value['status']} | {value['highestConfirmedTargetRps']} | {value['firstFailingTargetRps']} |")
    lines += ['', '반복 결과는 중앙값으로 표시한다. 최소·최대값과 각 실행의 검증 결과는 report.json에 보존한다.', '',
              '| 실험 | 목표 RPS | 방식 | 유효/전체 라운드 | SLO 통과 | 발급 SQL 수 | RDS CPU % | 성공 p95 ms | 성공 RPS |',
              '|---|---:|---|---:|---:|---:|---:|---:|---:|']
    for row in report['aggregates']:
        values = [f"{row[key]['median']:.2f}" if key in row else '—'
                  for key in ('queries', 'dbCpuPercent', 'successP95Ms', 'successfulRps')]
        lines.append(f"| {row['scenario']} | {row['targetRps']} | {row['variant']} | "
                     f"{row['validRounds']}/{row['rounds']} | {row['passingRounds']} | " + ' | '.join(values) + ' |')
    lines += ['', 'SQL 수는 측정한 발급 API의 Hibernate SQL 문장 수다. 커밋·세션 명령 등 DB 전체 명령 수가 아니다.',
              'CPU는 HTTP 측정 구간 안에 완전히 포함된 60초 버킷만 집계한다. 서로 다른 요청량의 결과를 직접 비교하지 않는다.',
              '실행 완료는 성능 기준 통과와 별개다. 무효/실패 라운드를 빼고 성과를 계산하지 않는다.', '']
    if report.get('error'):
        lines += [f"중단 사유: {report['error']}", '']
    return '\n'.join(lines)


class Runner:
    def __init__(self, c, output):
        self.c, self.output = c, output
        self.rows, self.fixtures = [], []
        self.status, self.error, self.sequence = 'running', None, 0

    def report(self):
        value = {'schemaVersion': 1, 'runId': self.c['runId'], 'configurationSha256': fingerprint(self.c),
                 'appVersion': self.c['appVersion'], 'status': self.status, 'error': self.error,
                 'capacity': {v: capacity_summary(self.rows, v, self.c) for v in ('db', 'lua')},
                 'aggregates': aggregates(self.rows), 'runs': self.rows, 'fixtures': self.fixtures}
        save(self.output / 'report.json', value)
        (self.output / 'REPORT.ko.md').write_text(render(value))

    def api(self, method, path, body=None):
        headers = {'Cookie': 'SESSION_ID=' + credential(self.c['adminSessionFile']),
                   'X-Benchmark-Token': credential(self.c['benchmarkTokenFile']), 'Content-Type': 'application/json'}
        request = urllib.request.Request(self.c['baseUrl'].rstrip('/') + path, method=method,
                                         headers=headers, data=json.dumps(body).encode() if body is not None else None)
        try:
            with HTTP.open(request, timeout=30) as response:
                result = json.loads(response.read(1024 * 1024))
                require(result.get('success') is True, 'Benchmark API did not succeed')
                return result.get('data')
        except urllib.error.HTTPError as error:
            raise ValueError(f'Benchmark administration HTTP {error.code}; check session, ADMIN role and benchmark profile/token') from None
        except (urllib.error.URLError, TimeoutError):
            raise ValueError('Benchmark administration request failed; inspect the fixture journal before retrying') from None

    def create(self, variant, label, stock, seconds):
        entry = {'label': label, 'variant': variant, 'stock': stock, 'status': 'create-requested'}
        self.fixtures.append(entry)
        self.report()
        # No automatic POST retries: a timed-out creation might already have committed.
        created = self.api('POST', FIXTURES, {'run_id': self.c['runId'], 'label': label,
                                           'variant': variant, 'stock': stock, 'lifetime_seconds': seconds + 300})
        entry.update(couponId=created['coupon_id'], issueEndAt=created['issue_end_at'], status='created')
        self.report()
        return entry

    def state(self, fixture):
        return self.api('GET', f"{FIXTURES}/{fixture['couponId']}?run_id={self.c['runId']}")

    def close(self, fixture):
        if fixture['variant'] == 'db':
            self.api('DELETE', f"{FIXTURES}/{fixture['couponId']}?run_id={self.c['runId']}")
            fixture['status'] = 'closed'
        else:
            fixture['status'] = 'retained-until-expiry'
        self.report()

    def prepare(self, fixture):
        if fixture['variant'] == 'lua':
            self.api('POST', f"/api/v1/admin/coupons/{fixture['couponId']}/stock/prepare")
        state = self.state(fixture)
        require(state['issued_quantity'] == state['member_coupon_count'] == state['distinct_member_count'] == 0
                and state['stock'] == fixture['stock'] and state['variant'] == fixture['variant']
                and state['run_id'] == self.c['runId'] and state['active'], 'Fixture was not empty or is not owned by this run')
        require(state['redis_prepared'] == (fixture['variant'] == 'lua'), 'Wrong fixture preparation mode')
        if fixture['variant'] == 'lua':
            require(state['redis_remaining_stock'] == fixture['stock'] and state['redis_issued_count'] == 0,
                    'Lua fixture was not empty')

    def on_k6_started(self, process):
        """Local diagnostics can observe this exact process without inspecting its credentials."""

    def k6(self, fixture, session_path, scenario, rate, seconds, round_number, phase, directory):
        env = {k: v for k, v in os.environ.items() if not k.startswith('K6_')}
        values = {'BASE_URL': self.c['baseUrl'], 'SESSION_FIXTURE': session_path,
                  'BENCHMARK_DATASET_MANIFEST': self.c['benchmarkDatasetManifest'],
                  'BENCHMARK_READ_MODEL_TOKEN': credential(self.c['benchmarkTokenFile']),
                  'APP_VERSION': self.c['appVersion'], 'APP_INSTANCE_COUNT': self.c['appInstanceCount'],
                  'VARIANT': fixture['variant'], 'COUPON_ID': fixture['couponId'], 'COUPON_STOCK': fixture['stock'],
                  'EXPERIMENT': scenario, 'PHASE': phase, 'ROUND': round_number, 'RUN_ORDER': self.sequence,
                  'RUN_LABEL': fixture['label'], 'RATE': rate, 'DURATION': f'{seconds}s',
                  'PRE_ALLOCATED_VUS': self.c['clientVUs'], 'MAX_VUS': self.c['clientVUs'],
                  'P95_LIMIT_MS': self.c['p95LimitMs'], 'P99_LIMIT_MS': self.c['p99LimitMs'],
                  'K6_RESULT_PATH': directory / 'client.json', 'K6_NO_USAGE_REPORT': 'true',
                  'K6_WEB_DASHBOARD': 'false', 'REQUEST_TIMEOUT': '10s', 'GRACEFUL_STOP': '30s'}
        env.update({k: str(v) for k, v in values.items()})
        save(directory / 'k6-options.json', {})
        with (directory / 'k6.log').open('w') as log, GeneratorMonitor() as monitor:
            process = subprocess.Popen(['k6', 'run', '--quiet', '--address', '', '--config', str(directory / 'k6-options.json'),
                                        str(HERE / 'coupon-issuance-comparison.js')],
                                       stdout=log, stderr=log, env=env)
            try:
                self.on_k6_started(process)
                code = process.wait(timeout=seconds + 180)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
        require(code in (0, 99), 'k6 did not finish; inspect the private run log')
        artifact = json.loads((directory / 'client.json').read_text())
        m = artifact['metadata']
        require(m['couponId'] == fixture['couponId'] and m['runLabel'] == fixture['label']
                and m['variant'] == fixture['variant'] and m['phase'] == phase
                and m['appVersion'] == self.c['appVersion'] and m['appInstanceCount'] == self.c['appInstanceCount']
                and m['duration'] == f'{seconds}s' and m['round'] == round_number
                and m['benchmarkDatasetManifestSha256'] == self.manifest_hash, 'k6 artifact contract mismatch')
        return artifact, monitor.result(), code

    def case(self, variant, scenario, rate, round_number, session_path, phase='measure'):
        self.sequence += 1
        seconds = self.c['warmupSeconds'] if phase == 'warmup' else self.c['durationSeconds']
        scenario = 'capacity' if phase == 'warmup' else scenario
        stock = rate * seconds + 1 if scenario == 'capacity' else math.floor(rate * seconds * self.c['stockRatio'])
        label = f'{self.sequence:03d}-{scenario}-{rate}-r{round_number}-{variant}-{phase}'
        directory = self.output / label
        directory.mkdir()
        fixture = self.create(variant, label, stock, seconds)
        try:
            self.prepare(fixture)
            route = '/api/v2/coupons/{couponId}/issue' if variant == 'db' else '/api/v1/coupons/{couponId}/issue'
            before = snapshot(self.c['appMetricsUrls'], route) if phase == 'measure' else None
            if before is not None:
                save(directory / 'sql-before.json', before)
            artifact, generator, code = self.k6(fixture, session_path, scenario, rate, seconds, round_number, phase, directory)
            if phase == 'warmup':
                p = artifact['performance']
                require(p['outcomes']['success'] == p['requestCount'] and p['requestCount'] >= rate * seconds - 1
                        and p['droppedIterations'] == 0, 'Warmup could not deliver successful load; reduce the starting rate')
                return
            # Allow request-completion counters to publish before administration queries.
            time.sleep(1)
            after = snapshot(self.c['appMetricsUrls'], route)
            save(directory / 'sql-after.json', after)
            server = delta(before, after)
            state = self.state(fixture)
            save(directory / 'state.json', state)
            save(directory / 'generator.json', generator)
            p = artifact['performance']
            cpu = cloudwatch_cpu(self.c['cpu'], p['measurementStartEpochMs'], p['measurementEndEpochMs'])
            save(directory / 'db-cpu.json', cpu)
            evaluation = evaluate(artifact, self.c, scenario, rate, stock, state, server, generator)
            if code == 99 and evaluation['sloPassed']:
                evaluation.update(sloPassed=False, sloReasons=['k6-threshold-failed'])
            row = {'scenario': scenario, 'variant': variant, 'rate': rate, 'round': round_number,
                   'order': self.sequence, 'stock': stock, 'couponId': fixture['couponId'], 'label': label,
                   'evaluation': evaluation, 'server': server, 'cpu': cpu, 'client': p}
            self.rows.append(row)
            save(directory / 'result.json', row)
            self.report()
        finally:
            try:
                self.close(fixture)
            except Exception:
                fixture['status'] = 'cleanup-required'
                self.report()
                raise ValueError('Fixture cleanup failed; use the report coupon ID and run_id to close it') from None

    def run(self):
        needed = plan(self.c)['requiredUniqueSessions']
        for command in ('k6', 'node', 'aws'):
            require(shutil.which(command) is not None, 'Missing executable: ' + command)
        checked = check_inputs(self.c)
        self.manifest_hash = checked['manifestSha256']
        require(Path('/proc/stat').exists(), 'Execute on the dedicated Linux load generator; planning works locally')
        credential(self.c['adminSessionFile'])
        credential(self.c['benchmarkTokenFile'])
        self.output.mkdir(parents=True, exist_ok=False, mode=0o700)
        save(self.output / 'configuration.json', self.c)
        self.report()
        try:
            with tempfile.TemporaryDirectory(prefix='airbob-coupon-sessions-') as temporary:
                sessions = Sessions(self.c, temporary, self.manifest_hash, needed)
                self.workloads(sessions)
                self.status = 'complete'
        except BaseException as error:
            self.status = 'incomplete'
            # Never serialize arbitrary subprocess/HTTP exception messages containing credentials.
            self.error = str(error) if isinstance(error, ValueError) else type(error).__name__
            raise
        finally:
            self.report()

    def workloads(self, sessions):
        for scenario, rates in [('capacity', self.c['capacityRates']), ('scarcity', self.c['comparisonRates'])]:
            active = {'db', 'lua'}
            for rate in rates:
                if not active:
                    break
                for round_number in range(1, self.c['rounds'] + 1):
                    for variant in order(round_number):
                        if variant not in active:
                            continue
                        session_path = sessions.ensure()
                        time.sleep(self.c['cooldownSeconds'])
                        warmup_rate = min(rate, self.c['capacityRates'][0], self.c['comparisonRates'][0])
                        self.case(variant, scenario, warmup_rate, round_number, session_path, 'warmup')
                        time.sleep(self.c['cooldownSeconds'])
                        self.case(variant, scenario, rate, round_number, session_path)
                if scenario == 'capacity':
                    for variant in list(active):
                        step = [r for r in self.rows if r['scenario'] == scenario and r['rate'] == rate and r['variant'] == variant]
                        if not all(r['evaluation']['sloPassed'] for r in step):
                            active.remove(variant)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', help='New result directory; defaults to build/k6/coupon-experiments/RUN_ID')
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--execute', action='store_true', help='Send traffic to the configured, existing lab')
    mode.add_argument('--check-inputs', action='store_true', help='Validate local input files without network calls')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        c = validate(json.loads(Path(args.config).read_text()), args.execute)
        if args.check_inputs:
            print(json.dumps(check_inputs(c), indent=2, ensure_ascii=False))
            return 0
        if not args.execute:
            print(json.dumps(plan(c), indent=2, ensure_ascii=False))
            return 0
        output = Path(args.output).resolve() if args.output else ROOT / 'build/k6/coupon-experiments' / c['runId']
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
        Runner(c, output).run()
        print('Coupon experiment results: ' + str(output / 'REPORT.ko.md'))
        return 0
    except (Exception, KeyboardInterrupt) as error:
        print('Coupon experiment stopped: ' + (str(error) if isinstance(error, ValueError) else type(error).__name__))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
