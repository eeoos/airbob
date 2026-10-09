#!/usr/bin/env python3
"""Exercise the real Airbob JAR and k6 against disposable local MySQL/Redis."""
import argparse
from contextlib import nullcontext
import hashlib
from http.cookies import SimpleCookie
import json
import math
import os
from pathlib import Path
import re
import resource
import secrets
import signal
import subprocess
import tempfile
import time
import urllib.request

from coupon_experiment import order, require
from coupon_monitoring import HTTP, delta, snapshot
from run_experiments import HERE, ROOT, Runner, save


def environment():
    allowed = {'PATH', 'JAVA_HOME', 'HOME', 'TMPDIR', 'LANG', 'LC_ALL', 'USER', 'LOGNAME', 'TZ'}
    return {k: v for k, v in os.environ.items() if k in allowed}


def command(arguments, **kwargs):
    result = subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=120, **kwargs)
    return result.stdout.strip()


def request(origin, path, body=None):
    req = urllib.request.Request(origin + path, data=json.dumps(body).encode() if body is not None else None,
                                 headers={'Content-Type': 'application/json'})
    with HTTP.open(req, timeout=30) as response:
        value = json.load(response)
        require(value.get('success', value.get('status') == 'UP') is True, 'Local API request failed')
        return value, response.headers


class LocalRunner(Runner):
    def __init__(self, c, output, capacity_search=False, diagnostic_context=None, scarcity_stock=None):
        super().__init__(c, output)
        self.capacity_search = capacity_search
        self.bounds = {}
        self.metadata = None
        self.diagnostic_context = diagnostic_context
        self.collector = None
        self.latest_diagnostics = None
        self.scarcity_stock = scarcity_stock

    def scope(self):
        if self.scarcity_stock is not None:
            return 'local-fixed-stock-scarcity-comparison'
        if self.diagnostic_context:
            return 'local-fixed-rate-diagnosis'
        return 'local-shared-host-capacity-search' if self.capacity_search else 'local-real-http-validation'

    def report(self):
        save(self.output / 'local-results.json', {'scope': self.scope(), 'status': self.status,
                                                'capacity': self.bounds, 'runs': self.rows,
                                                'fixtures': self.fixtures, 'error': self.error})
        if self.metadata:
            write_report(self.output, self.metadata, self.rows, self.bounds, self.status)

    def on_k6_started(self, process):
        if self.collector:
            self.collector.k6_pid = process.pid

    def k6(self, fixture, sessions, scenario, rate, seconds, round_number, phase, directory):
        context = nullcontext()
        self.latest_diagnostics = None
        if self.diagnostic_context and phase == 'measure':
            from local_diagnostics import LocalDiagnostics
            context = LocalDiagnostics(self.diagnostic_context, directory)
        before, started = resource.getrusage(resource.RUSAGE_CHILDREN), time.monotonic()
        try:
            with context as collector:
                self.collector = collector
                artifact, generator, code = super().k6(fixture, sessions, scenario, rate, seconds, round_number, phase, directory)
            if collector:
                self.latest_diagnostics = collector.result(artifact['performance'])
                save(directory / 'diagnostics.json', self.latest_diagnostics)
        finally:
            self.collector = None
        after, elapsed = resource.getrusage(resource.RUSAGE_CHILDREN), time.monotonic() - started
        generator.update(scope='load generator, application and Docker share one local host',
                         isolatedGeneratorVerified=False,
                         k6AverageCpuCores=(after.ru_utime + after.ru_stime - before.ru_utime - before.ru_stime) / elapsed,
                         k6WallSeconds=elapsed)
        if self.latest_diagnostics:
            host = self.latest_diagnostics.get('host', {})
            generator.update(status='observed-shared-macos-host', k6AverageCpuCores=None,
                             processStatistics='diagnostics.json',
                             healthy=(self.latest_diagnostics['status'] == 'complete'
                                      and host.get('maxCpuPercent', 100) < 90
                                      and host.get('minMemoryFreePercent', 0) >= 15))
        save(directory / 'generator.json', generator)
        artifact['metadata'].update(scope=self.scope(),
                                    datasetScope='local coupon accounts only; manifest is a contract template, not Global B')
        save(directory / 'client.json', artifact)
        return artifact, generator, code

    def measure(self, variant, scenario, rate, seconds, round_number, sessions, phase='measure'):
        self.sequence += 1
        label = f'{self.sequence:03d}-{scenario}-{rate}-{variant}-r{round_number}-{phase}'
        stock = rate * seconds + 1 if scenario == 'capacity' else max(1, rate * seconds // 10)
        if scenario == 'scarcity' and self.scarcity_stock is not None:
            stock = self.scarcity_stock
        directory = self.output / label
        directory.mkdir()
        fixture = self.create(variant, label, stock, seconds)
        try:
            self.prepare(fixture)
            version = '2' if variant == 'db' else '1'
            route = '/api/v' + version + '/coupons/{couponId}/issue'
            before = snapshot(self.c['appMetricsUrls'], route)
            save(directory / 'sql-before.json', before)
            artifact, generator, code = self.k6(fixture, sessions, scenario, rate, seconds, round_number, phase, directory)
            time.sleep(0.5)
            after = snapshot(self.c['appMetricsUrls'], route)
            save(directory / 'sql-after.json', after)
            sql = delta(before, after)
            state = self.state(fixture)
            save(directory / 'state.json', state)
            p = artifact['performance']
            outcomes = p['outcomes']
            invalid, failed = [], []
            if self.latest_diagnostics and not generator['healthy']:
                invalid.append('local-host-pressure-or-missing-diagnostics')
            if p['droppedIterations'] or abs(p['requestCount'] - rate * seconds) > 1:
                invalid.append('incomplete-offered-load')
            if sql['requests'] != p['requestCount']:
                invalid.append('server-client-count-mismatch')
            if sum(outcomes.values()) != p['requestCount']:
                invalid.append('incomplete-client-outcomes')
            if any(outcomes.get(key, 0) for key in ('duplicate', 'notIssuable', 'unprepared', 'authentication')):
                invalid.append('fixture-or-authentication-error')
            if outcomes['unexpected']:
                failed.append('unexpected-outcome')
            issued = state['issued_quantity']
            if not (issued == state['member_coupon_count'] == state['distinct_member_count'] == outcomes['success']
                    and 0 <= issued <= stock and state['stock'] == stock):
                invalid.append('database-invariant')
            if variant == 'lua' and not (state['redis_prepared'] and state['redis_remaining_stock'] == stock - issued
                                        and state['redis_issued_count'] == issued):
                invalid.append('redis-invariant')
            if variant == 'db' and state['redis_prepared']:
                invalid.append('db-used-redis-stock')
            if scenario == 'capacity' and outcomes['success'] != p['requestCount']:
                failed.append('capacity-not-all-success')
            if scenario == 'capacity' and outcomes['soldOut']:
                invalid.append('capacity-stock-exhausted')
            if scenario == 'scarcity' and (issued != stock or not outcomes['soldOut']):
                invalid.append('scarcity-not-exercised')
            for percentile, key in (('p(95)', 'p95LimitMs'), ('p(99)', 'p99LimitMs')):
                value = p['successDuration'].get(percentile)
                if value is None or not math.isfinite(value) or value > self.c[key]:
                    failed.append('success-' + percentile)
            if scenario == 'capacity' and p['successRate'] < rate * 0.99:
                failed.append('successful-throughput-below-target')
            if scenario == 'scarcity':
                failed.extend(scarcity_slo_failures(p, rate, self.c['p95LimitMs'], self.c['p99LimitMs']))
            if code != 0:
                failed.append('k6-threshold-failed')
            failures = invalid + failed
            row = {'label': label, 'phase': phase, 'scenario': scenario, 'variant': variant, 'round': round_number,
                   'rate': rate, 'durationSeconds': seconds, 'stock': stock, 'couponId': fixture['couponId'],
                   'client': p, 'server': sql, 'state': state, 'generator': generator,
                   'diagnostics': self.latest_diagnostics,
                   'passed': not failures, 'failures': failures,
                   'evaluation': {'valid': not invalid, 'sloPassed': not failures,
                                  'invalidReasons': invalid, 'sloReasons': failed}}
            self.rows.append(row)
            save(directory / 'result.json', row)
            self.report()
            print(f"{label}: requests={p['requestCount']} success={issued} soldOut={outcomes['soldOut']} "
                  f"request_rps={p['requestRate']:.2f} success_p95={p['successDuration'].get('p(95)', 0):.2f} "
                  f"sold_out_p95={p['soldOutDuration'].get('p(95)', 0):.2f} "
                  f"dropped={p['droppedIterations']} passed={not failures} reasons={','.join(failures)}", flush=True)
            if phase == 'warmup':
                require(not warmup_failures(row), 'Real local warmup failed: ' + ', '.join(warmup_failures(row)))
            elif not self.capacity_search and not self.diagnostic_context:
                require(not failures, 'Real local HTTP validation failed: ' + ', '.join(failures))
            return row
        finally:
            self.close(fixture)


def warmup_failures(row):
    """Warmup gates correctness/delivery; latency SLOs are evaluated on measured trials."""
    failures = list(row['evaluation']['invalidReasons'])
    p = row['client']
    if p['outcomes']['success'] != p['requestCount']:
        failures.append('warmup-not-all-success')
    return failures


def scarcity_slo_failures(performance, rate, p95_limit_ms, p99_limit_ms):
    failures = []
    for percentile, limit in (('p(95)', p95_limit_ms), ('p(99)', p99_limit_ms)):
        value = performance['soldOutDuration'].get(percentile)
        if value is None or not math.isfinite(value) or value > limit:
            failures.append('sold-out-' + percentile)
    if performance['requestRate'] < rate * 0.99:
        failures.append('request-throughput-below-target')
    return failures


def compare_scarcity(subject, args, sessions):
    """Keep stock fixed across rates and retain every paired repetition, including failures."""
    for rate in args.scarcity_rates:
        for round_number in range(1, args.rounds + 1):
            for variant in order(round_number):
                if variant not in getattr(args, 'scarcity_variants', ('db', 'lua')):
                    continue
                subject.measure(variant, 'capacity', min(100, args.scarcity_rates[0]), 10,
                                round_number, sessions, 'warmup')
                time.sleep(args.cooldown)
                row = subject.measure(variant, 'scarcity', rate, args.duration, round_number, sessions)
                time.sleep(args.cooldown)
                # Latency/dropped-load failures are evidence; bad data/auth makes later trials meaningless.
                fatal = set(row['evaluation']['invalidReasons']) - {
                    'incomplete-offered-load', 'local-host-pressure-or-missing-diagnostics'}
                require(not fatal,
                        'Scarcity experiment lost correctness: ' + ', '.join(sorted(fatal)))


def update_bound(bound, rate, rows):
    """A missed workload is inconclusive; only repeated SLO failures form an upper bound."""
    if any(not row['evaluation']['valid'] for row in rows):
        bound.update(status='inconclusive', stoppedAtRps=rate)
    else:
        passes = [row['evaluation']['sloPassed'] for row in rows]
        if all(passes):
            bound['highestConfirmedTargetRps'] = rate
            if not bound.get('stoppedAtRps'):
                bound['status'] = 'bracketed' if bound['firstFailingTargetRps'] else 'lower-bound-only'
        elif any(passes):
            bound.update(status='unstable', stoppedAtRps=rate)
        else:
            bound['firstFailingTargetRps'] = rate
            bound['status'] = 'bracketed' if bound['highestConfirmedTargetRps'] else 'no-passing-rate'
            bound.pop('stoppedAtRps', None)


def refinement_rate(bound, step):
    low = bound['highestConfirmedTargetRps']
    high = bound.get('stoppedAtRps') or bound['firstFailingTargetRps']
    if low is None or high is None or high - low <= step:
        return None
    candidate = ((low + high) // (2 * step)) * step
    return max(low + 1, min(high - 1, candidate))


def search_capacity(subject, args, sessions):
    subject.bounds = {v: {'status': 'not-measured', 'highestConfirmedTargetRps': None,
                          'firstFailingTargetRps': None} for v in ('db', 'lua')}

    def stage(targets):
        measured = {v: [] for v in targets}
        for round_number in range(1, args.rounds + 1):
            for variant in order(round_number):
                if variant not in targets:
                    continue
                subject.measure(variant, 'capacity', min(20, min(args.capacity_rates)), 5,
                                round_number, sessions, 'warmup')
                time.sleep(args.cooldown)
                measured[variant].append(subject.measure(variant, 'capacity', targets[variant], args.duration,
                                                        round_number, sessions))
                time.sleep(args.cooldown)
        for variant, rows in measured.items():
            update_bound(subject.bounds[variant], targets[variant], rows)
        subject.report()
        print('Capacity bounds: ' + json.dumps(subject.bounds), flush=True)

    for rate in args.capacity_rates:
        active = {v: rate for v, b in subject.bounds.items() if b['status'] in ('not-measured', 'lower-bound-only')}
        if not active:
            break
        stage(active)
    while True:
        targets = {v: refinement_rate(b, args.refine_step) for v, b in subject.bounds.items()}
        targets = {v: rate for v, rate in targets.items() if rate is not None}
        if not targets:
            break
        stage(targets)


def write_report(output, metadata, rows, bounds=None, status='complete'):
    lines = ['# 쿠폰 실제 Airbob 로컬 k6 실행', '',
             '실제 애플리케이션 JAR → HTTP 인증·컨트롤러·서비스 → 전용 MySQL/Redis를 실행했다.',
             '같은 로컬 호스트에서 측정한 결과이며 AWS 성능이나 독립된 서버의 최대 지속 처리량은 아니다.',
             f'실행 상태: {status}', '',
             f"앱 JAR SHA-256: `{metadata['appJarSha256']}`", '',
             f"MySQL {metadata['mysqlVersion']}, Flyway V{metadata['flywayVersion']}, "
             f"앱 1대, Hikari 10, heap 512 MiB. 부하 발생기와 앱은 같은 로컬 호스트다.", '',
             '회원과 쿠폰만 넣은 일회성 DB다. 대규모 B 데이터는 적재하지 않았다. '
             'manifest는 기존 테스트 계약 형식에 로컬 회원 풀을 넣은 입력이며 B 데이터 적재의 증거가 아니다.', '',
             f"본 측정 {metadata['durationSeconds']}초 × {metadata['rounds']}라운드, "
             f"p95 ≤ {metadata['p95LimitMs']}ms, p99 ≤ {metadata['p99LimitMs']}ms, "
             'capacity는 전 요청 성공·성공 처리량 ≥ 목표의 99%, scarcity는 정확한 재고 소진·'
             '성공/매진 각각의 응답 기준·전체 처리량 ≥ 목표의 99%를 확인한다. '
             '두 실험 모두 요청 누락·예상 밖 응답 0건을 기준으로 평가했다.', '']
    if metadata.get('scarcityRates'):
        lines += [f"재고 {metadata['scarcityStock']:,}장을 고정하고 "
                  f"{metadata['scarcityRates']} RPS에서 비교했다. 각 실행은 새 쿠폰을 사용한다.",
                  '전체 처리량은 성공과 매진 응답을 포함한 발급 시도 처리량이다. '
                  '20초 등의 짧은 실행을 최대 지속 처리량으로 해석하지 않는다.', '']
    if metadata.get('scarcityConfirmation'):
        confirmation = metadata['scarcityConfirmation']
        lines += [f"추가 확인: DB/Lua를 {confirmation['rate']} RPS·{confirmation['seconds']}초씩 "
                  f"{confirmation['rounds']}회 먼저 측정한 뒤 위 단계를 실행했다.", '']
    if bounds:
        lines += ['| 방식 | 판정 | 반복 통과 RPS | 반복 실패 RPS | 미확정 RPS |',
                  '|---|---|---:|---:|---:|']
        for variant, bound in bounds.items():
            lines.append(f"| {variant} | {bound['status']} | {bound['highestConfirmedTargetRps']} | "
                         f"{bound['firstFailingTargetRps']} | {bound.get('stoppedAtRps', '—')} |")
        lines += ['', '`lower-bound-only`는 상한 미확인, `unstable`은 라운드별 결과 혼재, '
                  '`inconclusive`는 요청 누락·준비/관측 오류로 상한 판정 불가를 뜻한다.',
                  '20~60초 탐색 결과는 짧은 구간의 관측치다. 이력서용 최대 지속 처리량은 독립된 부하 발생기와 '
                  '더 긴 측정으로 다시 검증한다. 기존 개발 컨테이너는 그대로 둔다.', '']
    lines += ['| 실험 | 목표 RPS | 시간 s | 라운드 | 방식 | 요청 | 성공 RPS | 누락 | SQL 수 | 성공 p95 ms | 성공 p99 ms | 검증 |',
              '|---|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---|']
    for row in rows:
        if row['phase'] != 'measure':
            continue
        p = row['client']
        lines.append(f"| {row['scenario']} | {row['rate']} | {row['durationSeconds']} | {row['round']} | {row['variant']} | {p['requestCount']} | "
                     f"{p['successRate']:.2f} | {p['droppedIterations']} | {row['server']['queries']} | "
                     f"{p['successDuration'].get('p(95)', 0):.2f} | {p['successDuration'].get('p(99)', 0):.2f} | "
                     f"{'통과' if row['passed'] else ', '.join(row['failures'])} |")
    scarcity = [r for r in rows if r['phase'] == 'measure' and r['scenario'] == 'scarcity']
    if scarcity:
        lines += ['', '## 한정 수량 발급과 매진 응답', '',
                  '| 목표 RPS | 시간 s | 라운드 | 방식 | 성공 | 매진 | 전체 응답 RPS | 성공 p95 ms | 매진 p95 ms | 매진 p99 ms |',
                  '|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|']
        for r in scarcity:
            p = r['client']
            lines.append(f"| {r['rate']} | {r['durationSeconds']} | {r['round']} | {r['variant']} | {p['outcomes']['success']} | "
                         f"{p['outcomes']['soldOut']} | {p['requestRate']:.2f} | "
                         f"{p['successDuration'].get('p(95)', 0):.2f} | "
                         f"{p['soldOutDuration'].get('p(95)', 0):.2f} | "
                         f"{p['soldOutDuration'].get('p(99)', 0):.2f} |")
    diagnosed = [r for r in rows if r.get('diagnostics')]
    if diagnosed:
        lines += ['', '## 병목 관측', '',
                  '행 잠금 시간은 여러 트랜잭션의 대기 시간 합계다. 벽시계 시간이나 CPU 사용량이 아니다. '
                  '카운터 차이는 k6 시작 전부터 종료 후까지이며 초기화·마지막 응답 처리도 포함한다. '
                  '대기자 수·CPU·메모리는 실제 HTTP 측정 구간의 표본이다.', '',
                  '| 목표 RPS | 시간 s | 방식 | 라운드 | 행 잠금 대기 횟수 | 평균 잠금 대기 ms | 커넥션 획득 평균 ms | 커넥션 대기 최대 | DB 평균 CPU 코어 | 관측 |',
                  '|---:|---:|---|---:|---:|---:|---:|---:|---:|---|']
        for r in diagnosed:
            d = r['diagnostics']
            lines.append(f"| {r['rate']} | {r['durationSeconds']} | {r['variant']} | {r['round']} | {d['rowLocks']['waits']} | "
                         f"{(d['rowLocks']['meanWaitMs'] or 0):.2f} | {(d['hikari']['acquireMeanMs'] or 0):.2f} | "
                         f"{d['hikari'].get('pendingMax', '—')} | {d['containers']['mysql']['averageCpuCores']:.2f} | "
                         f"{d['status']} |")
        lines += ['', '각 실행의 `diagnostics.json`에 잠금 대상·SQL별 지연·DB 파일 I/O 지연·GC·k6 CPU/메모리 요약을, '
                  '`diagnostic-samples.ndjson`에 2초 간격 원본을 보존한다. '
                  'DB 파일 I/O는 MySQL 계측이며 물리 디스크 전체의 포화 여부를 직접 뜻하지 않는다.', '']
    lines += ['', 'SQL 수는 발급 경로에서 기록한 Hibernate SQL 문장 수다. '
              '워밍업·로그인·쿠폰 생성·사후 검증 쿼리는 포함하지 않는다.',
              'DB/Redis 발급 수, 회원별 중복 여부, 요청 누락, 실제 서버 요청 수와 k6 요청 수의 일치를 검증했다.',
              'RDS CPU는 로컬에서 측정하지 않았다. 결과의 p95/p99는 이번 로컬 실행에서 관측한 값이다.', '']
    (output / 'REPORT.ko.md').write_text('\n'.join(lines))


def run(args):
    scarcity_rates = getattr(args, 'scarcity_rates', None)
    observed = bool(args.diagnose_rate or scarcity_rates)
    require(1 <= args.rate <= 100 and 5 <= args.duration <= (180 if observed else 60)
            and 1 <= args.rounds <= 3, 'Invalid local workload')
    if observed:
        require(args.duration >= 10, 'Local diagnosis requires at least 10 seconds (including warmup session capacity)')
        import pymysql  # Optional dependency; fail before creating infrastructure if unavailable.
        require(os.uname().sysname == 'Darwin', 'Local diagnosis currently uses macOS host observations')
    if args.diagnose_rate:
        require(1 <= args.diagnose_rate <= 2000, 'Invalid diagnosis rate')
    if scarcity_rates:
        require(scarcity_rates == sorted(set(scarcity_rates)) and 1 <= len(scarcity_rates) <= 8
                and 1 <= scarcity_rates[0] <= scarcity_rates[-1] <= 5000 and args.rounds >= 2
                and 0 < args.scarcity_stock < scarcity_rates[0] * args.duration,
                'Scarcity comparison needs ascending rates ≤ 5000, at least two rounds and stock below request count')
    require(10 <= args.client_vus <= (5000 if scarcity_rates else 1000) and 0 <= args.cooldown <= 30
            and 0 < args.p95_limit_ms <= args.p99_limit_ms <= 5000, 'Invalid local limits')
    if args.capacity_rates:
        require(args.capacity_rates == sorted(set(args.capacity_rates))
                and 1 <= len(args.capacity_rates) <= 12 and 1 <= args.capacity_rates[0]
                and args.capacity_rates[-1] <= 2000 and args.rounds >= 2
                and args.duration >= 15 and 1 <= args.refine_step <= 100,
                'Capacity search needs ascending rates ≤ 2000, duration ≥ 15s and at least two rounds')
    maximum_rate = (scarcity_rates[-1] if scarcity_rates else args.diagnose_rate
                    or (args.capacity_rates[-1] if args.capacity_rates else args.rate))
    count = maximum_rate * args.duration + 1
    confirmation_rate = getattr(args, 'scarcity_confirm_rate', None)
    if confirmation_rate:
        require(scarcity_rates and 1 <= confirmation_rate <= 2000
                and 30 <= args.scarcity_confirm_duration <= 180
                and 2 <= args.scarcity_confirm_rounds <= 3
                and args.scarcity_stock < confirmation_rate * args.scarcity_confirm_duration,
                'Invalid fixed-stock confirmation workload')
        count = max(count, confirmation_rate * args.scarcity_confirm_duration + 1)
    require(count <= 60001, 'Local workload requires too many real login sessions; reduce rate or duration')
    java_home = args.java_home
    if java_home is None and Path('/usr/libexec/java_home').is_file():
        java_home = command(['/usr/libexec/java_home', '-v', '21'])
    java_home = java_home or os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    run_id = 'local-' + time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(2)
    output = ROOT / 'build/k6/coupon-local' / run_id
    output.mkdir(parents=True, mode=0o700)
    containers, app, subject = [], None, None
    print('Local real-HTTP results: ' + str(output), flush=True)
    try:
        with tempfile.TemporaryDirectory(prefix='airbob-coupon-local-') as private:
            private = Path(private)
            password = secrets.token_urlsafe(12)
            token = secrets.token_urlsafe(32)
            db_password = secrets.token_urlsafe(24)
            mysql_env = private / 'mysql.env'
            mysql_env.write_text(f'MYSQL_ROOT_PASSWORD={db_password}\nMYSQL_DATABASE=coupon_local\n'
                                 f'MYSQL_USER=coupon_app\nMYSQL_PASSWORD={db_password}\n')
            mysql_env.chmod(0o600)
            for name, image, port, extra in [
                ('mysql', 'mysql:8.4.11', 3306, ['--env-file', str(mysql_env)]),
                ('redis', 'redis:7.2-alpine', 6379, []),
            ]:
                image_id = command(['docker', 'image', 'inspect', image, '--format', '{{.Id}}'])
                container = command(['docker', 'run', '--detach', '--name', 'airbob-coupon-' + run_id + '-' + name,
                                     '--label', 'airbob.coupon-local.run=' + run_id,
                                     '--publish', f'127.0.0.1::{port}', *extra, image_id])
                containers.append(container)
            mysql_id, redis_id = containers
            def port(container, number):
                info = json.loads(command(['docker', 'inspect', container]))[0]
                return info['NetworkSettings']['Ports'][f'{number}/tcp'][0]['HostPort']
            def sql(query):
                return command(['docker', 'exec', '-i', '-e', 'MYSQL_PWD', mysql_id, 'mysql', '--protocol=socket',
                                '-uroot', '--batch', '--skip-column-names', 'coupon_local'],
                               input=query, env=environment() | {'MYSQL_PWD': db_password})
            for _ in range(120):
                try:
                    sql('SELECT 1;')
                    break
                except subprocess.CalledProcessError:
                    time.sleep(1)
            else:
                raise ValueError('Disposable MySQL did not become ready')
            properties = {
                'spring.datasource.url': f'jdbc:mysql://127.0.0.1:{port(mysql_id, 3306)}/coupon_local?allowPublicKeyRetrieval=true&useSSL=false',
                'spring.datasource.username': 'coupon_app', 'spring.datasource.password': db_password,
                'spring.data.redis.host': '127.0.0.1', 'spring.data.redis.port': port(redis_id, 6379),
                'spring.jpa.open-in-view': 'false', 'spring.jpa.show-sql': 'false',
                'spring.flyway.baseline-on-migrate': 'false',
                'spring.kafka.bootstrap-servers': '127.0.0.1:9', 'spring.kafka.listener.auto-startup': 'false',
                'spring.kafka.admin.auto-create': 'false', 'operator-alert.kafka.auto-startup': 'false',
                'accommodation.indexing.kafka.auto-startup': 'false', 'accommodation.indexing.bootstrap.enabled': 'false',
                'accommodation.detail-cache.invalidation.kafka.auto-startup': 'false',
                'accommodation.detail-cache.enabled': 'false',
                'accommodation.detail-cache.redis.host': '127.0.0.1',
                'accommodation.detail-cache.redis.port': port(redis_id, 6379),
                'spring.elasticsearch.uris': 'http://127.0.0.1:9',
                'reservation.inventory.startup.enabled': 'false', 'reservation.inventory.seed.enabled': 'false',
                'reservation.inventory.retention.enabled': 'false', 'operator-alert.slack.enabled': 'false',
                'payment.toss.enabled': 'false', 'payment.toss.secret-key': 'local-disabled',
                'payment.toss.base-url': 'http://127.0.0.1:9', 'google.api.enabled': 'false', 'google.api.key': 'local-disabled',
                'cloud.aws.s3.write-enabled': 'false', 'cloud.aws.s3.bucket': 'local-disabled',
                'cloud.cloudfront.domain': 'http://127.0.0.1:9',
                'spring.cloud.aws.credentials.access-key': 'local-disabled',
                'spring.cloud.aws.credentials.secret-key': 'local-disabled',
                'spring.cloud.aws.region.static': 'ap-northeast-2', 'spring.cloud.aws.s3.endpoint': 'http://127.0.0.1:9',
                'management.endpoints.web.exposure.include': 'health,prometheus',
                'management.health.elasticsearch.enabled': 'false',
                'logging.level.kr.kro.airbob.domain.auth.filter.SessionAuthFilter': 'WARN',
                'logging.level.org.hibernate.SQL': 'OFF',
            }
            config_path = private / 'local.properties'
            config_path.write_text('\n'.join(k + '=' + v for k, v in properties.items()) + '\n')
            jar = ROOT / 'build/libs/airbob.jar'
            log_path = output / 'application.log'
            env = environment() | {'SPRING_PROFILES_ACTIVE': 'test,coupon-benchmark',
                                   'BENCHMARK_READ_MODEL_TOKEN': token, 'AWS_EC2_METADATA_DISABLED': 'true'}
            with log_path.open('w') as log:
                app = subprocess.Popen([java, '-Xms512m', '-Xmx512m', '-Duser.timezone=UTC', '-jar', str(jar),
                                        '--spring.config.additional-location=file:' + str(config_path),
                                        '--server.address=127.0.0.1', '--server.port=0'], cwd=ROOT, env=env,
                                       stdout=log, stderr=subprocess.STDOUT)
            origin = None
            for _ in range(240):
                require(app.poll() is None, 'Local Airbob stopped; inspect application.log')
                matches = re.findall(r'Tomcat started on port (\d+)', log_path.read_text(errors='replace'))
                if matches:
                    origin = 'http://127.0.0.1:' + matches[-1]
                    try:
                        request(origin, '/actuator/health')
                        break
                    except (OSError, ValueError):
                        pass
                time.sleep(0.5)
            else:
                raise ValueError('Local Airbob startup timed out; inspect application.log')
            print('Real Airbob ready: ' + origin, flush=True)
            diagnostic_context = None
            if observed:
                observer_password = secrets.token_hex(24)
                sql("CREATE USER 'coupon_observer'@'%' IDENTIFIED BY '" + observer_password + "';\n"
                    "GRANT PROCESS ON *.* TO 'coupon_observer'@'%';\n"
                    "GRANT SELECT ON performance_schema.* TO 'coupon_observer'@'%';\n"
                    "UPDATE performance_schema.setup_instruments SET ENABLED='YES', TIMED='YES' "
                    "WHERE NAME LIKE 'wait/io/file/innodb/%' OR NAME LIKE 'wait/io/file/sql/binlog%';")
                docker_endpoint = command(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'])
                require(docker_endpoint.startswith('unix://'), 'Local diagnosis requires a local Docker Unix socket')
                diagnostic_context = {'mysqlPort': int(port(mysql_id, 3306)), 'password': observer_password,
                                      'metricsUrl': origin + '/actuator/prometheus', 'appPid': app.pid,
                                      'dockerSocket': docker_endpoint.removeprefix('unix://'),
                                      'containers': {'mysql': mysql_id, 'redis': redis_id}}
                save(output / 'mysql-observation-settings.json', {'variables': sql(
                    "SHOW GLOBAL VARIABLES WHERE Variable_name IN ('performance_schema','innodb_flush_log_at_trx_commit',"
                    "'sync_binlog','log_bin','innodb_buffer_pool_size');"), 'fileInstruments': sql(
                    "SELECT NAME,ENABLED,TIMED FROM performance_schema.setup_instruments "
                    "WHERE NAME LIKE 'wait/io/file/innodb/%' OR NAME LIKE 'wait/io/file/sql/binlog%';")})
            request(origin, '/api/v1/members', {'email': 'admin@coupon.local', 'password': password, 'nickname': 'coupon-admin'})
            sql("UPDATE member SET role='ADMIN' WHERE email='admin@coupon.local';")
            emails = [f'coupon-local-{i:05d}@airbob.cloud' for i in range(count)]
            # Seed only this disposable schema. Password hashing itself uses the real signup API above.
            statements = ["INSERT INTO member (email,nickname,password,role,status,created_at,updated_at) "
                          f"SELECT '{email}','coupon-member',password,'MEMBER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) "
                          "FROM member WHERE email='admin@coupon.local';" for email in emails]
            sql('START TRANSACTION;\n' + '\n'.join(statements) + '\nCOMMIT;')
            _, headers = request(origin, '/api/v1/auth/login', {'email': 'admin@coupon.local', 'password': password})
            cookie = SimpleCookie()
            cookie.load(headers['Set-Cookie'])
            for name, value in [('admin-session', cookie['SESSION_ID'].value), ('token', token), ('password', password)]:
                (private / name).write_text(value)
                (private / name).chmod(0o600)
            manifest = json.loads((ROOT / 'infra/aws/tests/fixtures/benchmark-dataset-v2.json').read_text())
            capsule = next(c for c in manifest['capsules'] if c['capsuleId'] == 'coupon-accounts-v1')
            capsule['accountPool'] = {'capacity': count, 'emails': emails}
            manifest_path = output / 'local-contract-manifest.json'
            save(manifest_path, manifest)
            sessions = private / 'sessions.json'
            print(f'Preparing {count} real login sessions (outside measurement)', flush=True)
            with (output / 'session-preparation.log').open('w') as log:
                subprocess.run(['node', str(HERE / 'prepare-coupon-sessions.js'), '--base-url', origin,
                                '--manifest', str(manifest_path), '--password-file', str(private / 'password'),
                                '--session-output', str(sessions), '--required-capacity', str(count), '--concurrency', '20'],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800,
                               env=environment() | {'AIRBOB_SESSION_PREPARATION_TEST_MODE': '1'})
            print(f'Real login sessions ready: {count}', flush=True)
            c = json.loads((HERE / 'experiment.example.json').read_text())
            c.update(runId=run_id, baseUrl=origin, appInstanceCount=1, appVersion=command(['git', 'rev-parse', 'HEAD']),
                     benchmarkDatasetManifest=str(manifest_path), adminSessionFile=str(private / 'admin-session'),
                     benchmarkTokenFile=str(private / 'token'), clientVUs=args.client_vus,
                     appMetricsUrls=[origin + '/actuator/prometheus'],
                     p95LimitMs=args.p95_limit_ms, p99LimitMs=args.p99_limit_ms)
            subject = LocalRunner(c, output, bool(args.capacity_rates), diagnostic_context,
                                  args.scarcity_stock if scarcity_rates else None)
            subject.manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            metadata = {'scope': 'local-real-http-validation', 'appJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                        'appCommit': c['appVersion'], 'sourceTreeDirty': bool(command(['git', 'status', '--porcelain'])),
                        'mysqlVersion': sql('SELECT VERSION();'),
                        'flywayVersion': sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;'),
                        'memberCount': int(sql('SELECT COUNT(*) FROM member;')), 'mysqlContainer': mysql_id,
                        'redisContainer': redis_id, 'origin': origin, 'rate': args.rate, 'durationSeconds': args.duration,
                        'rounds': args.rounds, 'javaExecutable': java, 'capacityRates': args.capacity_rates,
                        'refineStep': args.refine_step, 'clientVUs': args.client_vus,
                        'diagnoseRate': args.diagnose_rate,
                        'scarcityRates': scarcity_rates,
                        'scarcityStock': args.scarcity_stock if scarcity_rates else None,
                        'scarcityVariants': getattr(args, 'scarcity_variants', None),
                        'scarcityConfirmation': {'rate': confirmation_rate,
                                                'seconds': args.scarcity_confirm_duration,
                                                'rounds': args.scarcity_confirm_rounds}
                                               if confirmation_rate else None,
                        'p95LimitMs': c['p95LimitMs'], 'p99LimitMs': c['p99LimitMs'],
                        'dockerResources': json.loads(command(['docker', 'info', '--format',
                                                               '{"cpus":{{.NCPU}},"memoryBytes":{{.MemTotal}}}'])),
                        'existingContainers': command(['docker', 'ps', '--format', '{{.Names}}']).splitlines(),
                        'datasetScope': 'new database with local coupon accounts only; not Global B'}
            save(output / 'run.json', metadata)
            subject.metadata = metadata
            if scarcity_rates:
                if confirmation_rate:
                    confirmation = argparse.Namespace(**vars(args))
                    confirmation.scarcity_rates = [confirmation_rate]
                    confirmation.scarcity_variants = ['db', 'lua']
                    confirmation.duration = args.scarcity_confirm_duration
                    confirmation.rounds = args.scarcity_confirm_rounds
                    compare_scarcity(subject, confirmation, sessions)
                compare_scarcity(subject, args, sessions)
            elif args.capacity_rates:
                search_capacity(subject, args, sessions)
            elif args.diagnose_rate:
                for round_number in range(1, args.rounds + 1):
                    for variant in order(round_number):
                        subject.measure(variant, 'capacity', min(100, args.diagnose_rate), 10,
                                        round_number, sessions, 'warmup')
                        time.sleep(args.cooldown)
                        subject.measure(variant, 'capacity', args.diagnose_rate, args.duration,
                                        round_number, sessions)
                        time.sleep(args.cooldown)
            else:
                for scenario in ('capacity', 'scarcity'):
                    for round_number in range(1, args.rounds + 1):
                        for variant in order(round_number):
                            subject.measure(variant, 'capacity', min(10, args.rate), 5, round_number, sessions, 'warmup')
                            time.sleep(args.cooldown)
                            subject.measure(variant, scenario, args.rate, args.duration, round_number, sessions)
            subject.status = 'complete'
            subject.report()
            print('Local experiment complete: ' + str(output / 'REPORT.ko.md'), flush=True)
    except BaseException as error:
        if subject is not None:
            subject.status = 'incomplete'
            subject.error = str(error) if isinstance(error, ValueError) else type(error).__name__
            subject.report()
        raise
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=20)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait()
        removed, remaining = [], []
        for container in reversed(containers):
            try:
                command(['docker', 'rm', '--force', '--volumes', container])
                removed.append(container)
            except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
                remaining.append(container)
        save(output / 'cleanup.json', {'applicationStopped': app is None or app.poll() is not None,
                                     'removedDisposableContainers': removed, 'cleanupRequiredContainers': remaining})
        require(not remaining, 'Some disposable containers need cleanup; inspect cleanup.json')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rate', type=int, default=50)
    parser.add_argument('--duration', type=int, default=20)
    parser.add_argument('--rounds', type=int, default=2)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--capacity-rates', type=int, nargs='+', help='Coarse RPS steps; enables capacity-only search')
    mode.add_argument('--diagnose-rate', type=int, help='Fixed capacity RPS with local DB, pool and host observations')
    mode.add_argument('--scarcity-rates', type=int, nargs='+', help='Fixed-stock comparison with local observations')
    parser.add_argument('--scarcity-stock', type=int, default=1000, help='Stock shared by all scarcity RPS steps')
    parser.add_argument('--scarcity-variants', nargs='+', choices=['db', 'lua'], default=['db', 'lua'],
                        help='Variants to probe; previous failures remain in their original reports')
    parser.add_argument('--scarcity-confirm-rate', type=int, help='First confirm this RPS with both variants')
    parser.add_argument('--scarcity-confirm-duration', type=int, default=60)
    parser.add_argument('--scarcity-confirm-rounds', type=int, default=2)
    parser.add_argument('--refine-step', type=int, default=25, help='Stop narrowing at this RPS interval')
    parser.add_argument('--client-vus', type=int, default=50, help='Preallocated and maximum k6 virtual users')
    parser.add_argument('--cooldown', type=int, default=3, help='Pause between trials in seconds')
    parser.add_argument('--p95-limit-ms', type=int, default=500)
    parser.add_argument('--p99-limit-ms', type=int, default=1000)
    parser.add_argument('--java-home', help='Current Java 21 with the time-zone data required by Airbob')
    args = parser.parse_args()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    run(args)
