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
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.request

from coupon_experiment import order, require, inventory_failures, issue_route, DEFAULT_VARIANTS, VARIANTS
from coupon_monitoring import HTTP, delta, snapshot
from run_experiments import HERE, ROOT, Runner, save, FIXTURES
from local_diagnostics import LocalDiagnostics
from local_pool_sizing import pool_properties, pool_observation_failures, validate_pool_comparison, compare_pools


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
    def __init__(self, c, output, capacity_search=False, diagnostic_context=None, scarcity_stock=None,
                 capacity_stock=None):
        super().__init__(c, output)
        self.capacity_search = capacity_search
        self.bounds = {}
        self.capacity_seed = None
        self.metadata = None
        self.diagnostic_context = diagnostic_context
        self.collector = None
        self.latest_diagnostics = None
        self.scarcity_stock = scarcity_stock
        self.capacity_stock = capacity_stock
        self.workload = None
        self.preparation_events = []
        self.preparation_thread = None
        self.preparation_stop = None

    def scope(self):
        if self.metadata and self.metadata.get('poolSizes'):
            return 'local-pool-size-comparison'
        if self.metadata and self.metadata.get('workloadPlan'):
            return 'local-multi-scenario-comparison'
        if self.capacity_search:
            return 'local-shared-host-capacity-search'
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
        if self.preparation_thread:
            self.preparation_thread.start()

    def k6(self, fixture, sessions, scenario, rate, seconds, round_number, phase, directory):
        self.c['localWorkload'] = self.workload if phase == 'measure' else None
        context = nullcontext()
        self.latest_diagnostics = None
        if self.diagnostic_context and phase == 'measure':
            context = LocalDiagnostics(self.diagnostic_context, directory)
        before, started = resource.getrusage(resource.RUSAGE_CHILDREN), time.monotonic()
        self.preparation_events = []
        preparation = (self.workload or {}).get('otherPreparation') if phase == 'measure' else None
        if preparation:
            route = issue_route(fixture['variant'])
            request_baseline = snapshot(self.c['appMetricsUrls'], route)[0]['requests']
            self.preparation_stop = threading.Event()
            def prepare_other():
                try:
                    # Start relative to the first issuance, excluding k6 VM initialization.
                    while not self.preparation_stop.wait(0.25):
                        count = snapshot(self.c['appMetricsUrls'], route)[0]['requests']
                        if count > request_baseline:
                            break
                    for index in range(preparation['count']):
                        if self.preparation_stop.wait(preparation['intervalSeconds']):
                            return
                        label = f"prep-{self.sequence:03d}-{fixture['variant']}-{index}".replace('_', '-')
                        event = {'label': label, 'stock': preparation['stock'], 'startedAtEpochMs': time.time() * 1000}
                        self.preparation_events.append(event)
                        save(directory / 'other-preparation.json', self.preparation_events)
                        created = self.api('POST', FIXTURES, {'run_id': self.c['runId'], 'label': label,
                            'variant': fixture['variant'], 'stock': preparation['stock'], 'lifetime_seconds': seconds + 300})
                        event.update(couponId=created['coupon_id'], completedAtEpochMs=time.time() * 1000)
                        save(directory / 'other-preparation.json', self.preparation_events)
                except Exception as error:
                    self.preparation_events.append({'error': type(error).__name__})
                    save(directory / 'other-preparation.json', self.preparation_events)
            self.preparation_thread = threading.Thread(target=prepare_other, daemon=True)
        try:
            with context as collector:
                self.collector = collector
                artifact, generator, code = super().k6(fixture, sessions, scenario, rate, seconds, round_number, phase, directory)
            if collector:
                self.latest_diagnostics = collector.result(artifact['performance'])
                save(directory / 'diagnostics.json', self.latest_diagnostics)
        finally:
            self.collector = None
            if self.preparation_stop:
                self.preparation_stop.set()
                if self.preparation_thread.ident is not None:
                    self.preparation_thread.join(timeout=35)
                require(not self.preparation_thread.is_alive(), 'Other-campaign preparation did not stop')
                self.preparation_thread = self.preparation_stop = None
        for event in self.preparation_events:
            if 'couponId' in event:
                other = {'couponId': event['couponId']}
                event['state'] = self.state(other)
                require(not inventory_failures(event['state'], fixture['variant'], event['stock'], 0),
                        'Other campaign lost inventory during preparation')
                self.api('DELETE', f"{FIXTURES}/{event['couponId']}?run_id={self.c['runId']}")
        if preparation:
            save(directory / 'other-preparation.json', self.preparation_events)
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
                                    datasetScope='local coupon accounts only; source manifest records this disposable V30 database')
        save(directory / 'client.json', artifact)
        return artifact, generator, code

    def measure(self, variant, scenario, rate, seconds, round_number, sessions, phase='measure'):
        sessions = sessions() if callable(sessions) else sessions
        self.sequence += 1
        workload = self.workload if phase == 'measure' else None
        label = f"{self.sequence:03d}-{workload['name'] if workload else scenario}-{rate}-{variant}-r{round_number}-{phase}".replace('_', '-')[:64]
        planned = workload['plannedRequests'] if workload else rate * seconds
        stock = rate * seconds + 1 if scenario == 'capacity' else max(1, rate * seconds // 10)
        if scenario == 'capacity' and phase == 'measure' and self.capacity_stock is not None:
            require(self.capacity_stock > planned, 'Fixed capacity stock must exceed planned requests')
            stock = self.capacity_stock
        if scenario == 'scarcity' and self.scarcity_stock is not None:
            stock = self.scarcity_stock
        if workload:
            stock = workload['stock']
        directory = self.output / label
        directory.mkdir()
        fixture = self.create(variant, label, stock, seconds)
        try:
            self.prepare(fixture)
            route = issue_route(variant)
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
            if self.latest_diagnostics:
                invalid.extend(pool_observation_failures(self.latest_diagnostics,
                                                        self.c.get('hikariMaximumPoolSize', 10)))
            if workload and workload.get('otherPreparation'):
                successful_preparations = [event for event in self.preparation_events
                    if p['measurementStartEpochMs'] <= event.get('startedAtEpochMs', 0)
                    <= event.get('completedAtEpochMs', float('inf')) <= p['measurementEndEpochMs']]
                if len(successful_preparations) != workload['otherPreparation']['count']:
                    invalid.append('other-preparation-not-completed-during-issuance')
            if p['droppedIterations'] or abs(p['requestCount'] - planned) > 1:
                invalid.append('incomplete-offered-load')
            if sql['requests'] != p['requestCount']:
                invalid.append('server-client-count-mismatch')
            if sum(outcomes.values()) != p['requestCount']:
                invalid.append('incomplete-client-outcomes')
            bad_setup = ['notIssuable', 'unprepared', 'authentication']
            if scenario != 'duplicate':
                bad_setup.append('duplicate')
            if any(outcomes.get(key, 0) for key in bad_setup):
                invalid.append('fixture-or-authentication-error')
            if outcomes['unexpected']:
                failed.append('unexpected-outcome')
            issued = state['member_coupon_count']
            invalid.extend(inventory_failures(state, variant, stock, outcomes['success']))
            if outcomes.get('contended', 0):
                failed.append('retryable-contention')
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
            if scenario == 'capacity' and not workload and p['successRate'] < rate * 0.99:
                failed.append('successful-throughput-below-target')
            if scenario == 'scarcity':
                failed.extend(scarcity_slo_failures(p, rate, self.c['p95LimitMs'], self.c['p99LimitMs']))
            segments = []
            if workload:
                from local_workloads import segment_evaluations
                segments = segment_evaluations(p, workload, self.c['p95LimitMs'], self.c['p99LimitMs'])
                failed.extend('segment-' + s['name'] + ':' + reason for s in segments for reason in s['reasons'])
                if scenario == 'duplicate' and (issued != workload['uniqueMembers'] or
                        outcomes['success'] + outcomes['duplicate'] != p['requestCount']):
                    failed.append('duplicate-workload-outcomes')
            if code != 0:
                failed.append('k6-threshold-failed')
            failures = invalid + failed
            row = {'label': label, 'phase': phase, 'scenario': scenario, 'variant': variant, 'round': round_number,
                   'hikariMaximumPoolSize': self.c.get('hikariMaximumPoolSize', 10),
                   'poolCohort': self.c.get('poolCohort'),
                   'rate': rate, 'durationSeconds': seconds, 'stock': stock, 'couponId': fixture['couponId'],
                   'client': p, 'server': sql, 'state': state, 'generator': generator,
                   'diagnostics': self.latest_diagnostics,
                   'workload': workload, 'segments': segments,
                   'otherPreparation': self.preparation_events,
                   'passed': not failures, 'failures': failures,
                   'evaluation': {'valid': not invalid, 'sloPassed': not failures,
                                  'invalidReasons': invalid, 'sloReasons': failed}}
            self.rows.append(row)
            save(directory / 'result.json', row)
            self.report()
            print(f"{label}: pool={row['hikariMaximumPoolSize']} requests={p['requestCount']} success={issued} soldOut={outcomes['soldOut']} "
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
            for variant in order(round_number, getattr(args, 'variants', DEFAULT_VARIANTS)):
                if variant not in (getattr(args, 'scarcity_variants', None) or getattr(args, 'variants', DEFAULT_VARIANTS)):
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
    # step is the stopping interval, not a grid. Rounding to a grid can repeatedly probe low + 1.
    candidate = (low + high) // 2
    return max(low + 1, min(high - 1, candidate))


def search_capacity(subject, args, sessions):
    seed = getattr(subject, 'capacity_seed', None)
    subject.bounds = (json.loads(json.dumps(seed)) if isinstance(seed, dict) else
                      {v: {'status': 'not-measured', 'highestConfirmedTargetRps': None,
                           'firstFailingTargetRps': None} for v in getattr(args, 'variants', DEFAULT_VARIANTS)})

    def stage(targets):
        measured = {v: [] for v in targets}
        for round_number in range(1, args.rounds + 1):
            for variant in order(round_number, getattr(args, 'variants', DEFAULT_VARIANTS)):
                if variant not in targets:
                    continue
                subject.measure(variant, 'capacity', min(100, min(args.capacity_rates)), 10,
                                round_number, sessions, 'warmup')
                time.sleep(args.cooldown)
                row = subject.measure(variant, 'capacity', targets[variant], args.duration, round_number, sessions)
                measured[variant].append(row)
                fatal = set(row['evaluation'].get('invalidReasons', [])) - {
                    'incomplete-offered-load', 'local-host-pressure-or-missing-diagnostics'}
                require(not fatal, 'Capacity experiment lost correctness: ' + ', '.join(sorted(fatal)))
                time.sleep(args.cooldown)
        for variant, rows in measured.items():
            update_bound(subject.bounds[variant], targets[variant], rows)
        subject.report()
        print('Capacity bounds: ' + json.dumps(subject.bounds), flush=True)

    for rate in ([] if isinstance(seed, dict) else args.capacity_rates):
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
             f"앱 1대, Hikari {metadata.get('poolSizes') or metadata.get('hikariMaximumPoolSize', 10)}, "
             f"heap 512 MiB. 부하 발생기와 앱은 같은 로컬 호스트다.", '',
             '회원과 쿠폰만 넣은 일회성 DB다. 대규모 B 데이터는 적재하지 않았다. '
             'manifest는 기존 테스트 계약 형식에 로컬 회원 풀을 넣은 입력이며 B 데이터 적재의 증거가 아니다.', '',
             (f"본 측정 시나리오별 시간 × {metadata['rounds']}라운드, " if metadata.get('workloadPlan') else
              f"본 측정 {metadata['durationSeconds']}초 × {metadata['rounds']}라운드, ") +
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
        lines += [f"추가 확인: 비교군를 {confirmation['rate']} RPS·{confirmation['seconds']}초씩 "
                  f"{confirmation['rounds']}회 먼저 측정한 뒤 위 단계를 실행했다.", '']
    if metadata.get('capacityStock'):
        lines += [f"본 capacity 측정은 모든 부하 단계에 초기 재고 {metadata['capacityStock']:,}개를 고정했다.", '']
    if metadata.get('workloadPlan'):
        lines += ['## 시나리오와 구간별 요청 조건', '',
                  '각 행의 전체 목표 RPS는 해당 시나리오의 최고 구간 값이다. 실제 구간별 요청률과 시간은 아래와 같다.',
                  '| 시나리오 | 초기 재고 | 부하 순서 | 예정 요청 |', '|---|---:|---|---:|']
        for case in metadata['workloadPlan']['cases']:
            schedule = ' → '.join(f"{s['name']}: {s['rate']} RPS × {s['seconds']}s" for s in case['segments'])
            lines.append(f"| {case['name']} | {case['stock']} | {schedule} | {case['plannedRequests']} |")
        lines += ['', '급증·회복은 한 쿠폰을 유지한다. 구간 결과는 요청이 시작된 구간 기준이며 이전 구간의 늦은 응답도 원래 구간에 남긴다.',
                  '마지막 1개 재고 시나리오의 성공 지연은 단일 성공 표본이며 처리량 비교 근거로 사용하지 않는다.', '']
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
    if metadata.get('poolSizes'):
        lines += ['', '풀별 maximumPoolSize와 minimumIdle은 같은 값이다. 매 풀 비교 차례마다 앱을 재시작하고 '
                  '각 본 측정 전에 별도 쿠폰으로 워밍업한다. 풀과 RPS 실행 순서는 라운드별로 회전한다.',
                  '앱 JAR·DB·Redis는 동일하며 세션 준비는 측정 밖이다. 이전 쿠폰과 발급 이력은 비활성 상태로 '
                  '남으므로 데이터 누적 영향까지 제거한 실험은 아니다. run.json의 poolCohorts에 순서와 시작 상태를 기록한다.', '']
    lines += ['| 실험 | 목표 RPS | 시간 s | 라운드 | 풀 | 방식 | 요청 | 성공 RPS | 누락 | SQL 수 | 성공 p95 ms | 성공 p99 ms | 검증 |',
              '|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---|']
    for row in rows:
        if row['phase'] != 'measure':
            continue
        p = row['client']
        lines.append(f"| {row['scenario']} | {row['rate']} | {row['durationSeconds']} | {row['round']} | "
                     f"{row.get('hikariMaximumPoolSize', metadata.get('hikariMaximumPoolSize', 10))} | {row['variant']} | {p['requestCount']} | "
                     f"{p['successRate']:.2f} | {p['droppedIterations']} | {row['server']['queries']} | "
                     f"{p['successDuration'].get('p(95)', 0):.2f} | {p['successDuration'].get('p(99)', 0):.2f} | "
                     f"{'통과' if row['passed'] else ', '.join(row['failures'])} |")
    scarcity = [r for r in rows if r['phase'] == 'measure' and r['scenario'] == 'scarcity']
    segmented = [r for r in rows if r.get('segments')]
    if segmented:
        lines += ['', '## 구간별 응답과 회복', '',
                  '| 시나리오 | 방식 | 회차 | 구간 | 목표 RPS | 요청 | 성공 | 중복 | p95 ms | p99 ms | 판정 |',
                  '|---|---|---:|---|---:|---:|---:|---:|---:|---:|---|']
        for row in segmented:
            for segment in row['segments']:
                p = segment['performance']
                lines.append(f"| {row['workload']['name']} | {row['variant']} | {row['round']} | {segment['name']} | "
                             f"{segment['rate']} | {p['requestCount']} | {p['outcomes']['success']} | {p['outcomes']['duplicate']} | "
                             f"{p['duration'].get('p(95)', 0):.2f} | {p['duration'].get('p(99)', 0):.2f} | "
                             f"{'통과' if segment['passed'] else ', '.join(segment['reasons'])} |")
        lines += ['', '구간 통과는 전체 실행의 유효성과 함께 해석한다. 호스트 압력·요청 누락으로 무효인 실행의 회복 구간만 뽑아 성과로 쓰지 않는다.',
                  '다른 캠페인 준비를 병행한 실행은 해당 준비 시간·재고 검증을 other-preparation.json에 보존한다. DB 전체 관측에는 준비 부하도 포함된다.', '']
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
                  '| 목표 RPS | 시간 s | 풀 | 방식 | 라운드 | 행 잠금 대기 횟수 | 평균 잠금 대기 ms | 커넥션 획득 평균 ms | 커넥션 대기 최대 | DB 평균 CPU 코어 | 관측 |',
                  '|---:|---:|---:|---|---:|---:|---:|---:|---:|---:|---|']
        for r in diagnosed:
            d = r['diagnostics']
            lines.append(f"| {r['rate']} | {r['durationSeconds']} | {r.get('hikariMaximumPoolSize', metadata.get('hikariMaximumPoolSize', 10))} | "
                         f"{r['variant']} | {r['round']} | {d['rowLocks']['waits']} | "
                         f"{(d['rowLocks']['meanWaitMs'] or 0):.2f} | {(d['hikari']['acquireMeanMs'] or 0):.2f} | "
                         f"{d['hikari'].get('pendingMax', '—')} | {d['containers']['mysql']['averageCpuCores']:.2f} | "
                         f"{d['status']} |")
        lines += ['', '각 실행의 `diagnostics.json`에 잠금 대상·SQL별 지연·DB 파일 I/O 지연·GC·k6 CPU/메모리 요약을, '
                  '`diagnostic-samples.ndjson`에 2초 간격 원본을 보존한다. '
                  'DB 파일 I/O는 MySQL 계측이며 물리 디스크 전체의 포화 여부를 직접 뜻하지 않는다.', '']
    lines += ['', 'SQL 수는 발급 경로에서 기록한 Hibernate SQL 문장 수다. '
              '워밍업·로그인·쿠폰 생성·사후 검증 쿼리는 포함하지 않는다.',
              '회원 쿠폰·남은 unit 수, 회원별 중복 여부, 요청 누락, 실제 서버 요청 수와 k6 요청 수의 일치를 검증했다.',
              'RDS CPU는 로컬에서 측정하지 않았다. 결과의 p95/p99는 이번 로컬 실행에서 관측한 값이다.', '']
    (output / 'REPORT.ko.md').write_text('\n'.join(lines))


def run(args):
    workload_plan = None
    if getattr(args, 'workload_plan', None):
        from local_workloads import validate_plan
        workload_plan = validate_plan(json.loads(Path(args.workload_plan).read_text()))
    pool_sizes = getattr(args, 'pool_sizes', None)
    pool_size = pool_sizes[0] if pool_sizes else getattr(args, 'pool_size', 10)
    pool_properties(pool_size)
    if pool_sizes is not None:
        validate_pool_comparison(pool_sizes, workload_plan, args.rounds, args.variants)
    scarcity_rates = getattr(args, 'scarcity_rates', None)
    observed = bool(getattr(args, 'observe', False) or args.diagnose_rate or scarcity_rates
                    or getattr(args, 'capacity_rates', None) or workload_plan or pool_size != 10)
    require(1 <= args.rate <= (2000 if observed else 100) and 5 <= args.duration <= (180 if observed else 60)
            and 1 <= args.rounds <= 6, 'Invalid local workload')
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
    if workload_plan:
        count = workload_plan['requiredSessions']
    capacity_stock = getattr(args, 'capacity_stock', None)
    if capacity_stock is not None:
        require(count <= capacity_stock <= 1_000_000, 'Fixed capacity stock must exceed all planned requests, at most 1000000')
    confirmation_rate = getattr(args, 'scarcity_confirm_rate', None)
    if confirmation_rate:
        require(scarcity_rates and 1 <= confirmation_rate <= 2000
                and 30 <= args.scarcity_confirm_duration <= 180
                and 2 <= args.scarcity_confirm_rounds <= 3
                and args.scarcity_stock < confirmation_rate * args.scarcity_confirm_duration,
                'Invalid fixed-stock confirmation workload')
        count = max(count, confirmation_rate * args.scarcity_confirm_duration + 1)
    if observed:
        # A short measured stage can need fewer sessions than the separate 100 RPS × 10s warmup.
        count = max(count, 1001)
    require(count <= 60001, 'Local workload requires too many real login sessions; reduce rate or duration')
    refinement_source = None
    if getattr(args, 'refine_from', None):
        require(args.capacity_rates and capacity_stock is not None, 'Refinement needs capacity rates and fixed stock')
        previous = Path(args.refine_from).resolve()
        prior_run = json.loads((previous.parent / 'run.json').read_text())
        prior = json.loads(previous.read_text())
        require(prior_run['variants'] == list(args.variants) and prior_run['capacityStock'] == capacity_stock
                and prior_run['durationSeconds'] == args.duration and prior_run['rounds'] == args.rounds
                and prior_run['hikariMaximumPoolSize'] == pool_size
                and prior_run.get('hikariMinimumIdle', prior_run['hikariMaximumPoolSize']) == pool_size
                and prior_run['clientVUs'] == args.client_vus
                and prior_run['p95LimitMs'] == args.p95_limit_ms and prior_run['p99LimitMs'] == args.p99_limit_ms
                and prior_run['appJarSha256'] == hashlib.sha256((ROOT / 'build/libs/airbob.jar').read_bytes()).hexdigest(),
                'Refinement source differs from fixed workload/application conditions')
        require(set(prior['capacity']) == set(args.variants), 'Refinement source lacks variant bounds')
        for b in prior['capacity'].values():
            require(b['status'] in ('bracketed', 'inconclusive', 'unstable', 'lower-bound-only', 'no-passing-rate'),
                    'Refinement source has unmeasured variants')
            for key in ('highestConfirmedTargetRps', 'firstFailingTargetRps', 'stoppedAtRps'):
                value = b.get(key)
                require(value is None or type(value) is int and 0 < value <= maximum_rate, 'Invalid prior capacity bound')
        refinement_source = {'path': str(previous), 'sha256': hashlib.sha256(previous.read_bytes()).hexdigest(),
                             'priorBounds': prior['capacity'], 'scope': 'prior observations; new process and disposable database'}
    java_home = args.java_home
    if java_home is None and Path('/usr/libexec/java_home').is_file():
        java_home = command(['/usr/libexec/java_home', '-v', '21'])
    java_home = java_home or os.environ.get('JAVA_HOME')
    java = str(Path(java_home) / 'bin/java') if java_home else 'java'
    run_id = 'local-' + time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(2)
    output = ROOT / 'build/k6/coupon-local' / run_id
    output.mkdir(parents=True, mode=0o700)
    jar = output / 'application.jar'
    shutil.copy2(ROOT / 'build/libs/airbob.jar', jar)
    source = output / 'source' / 'k6'
    # k6 reads scripts anew each trial; pin them before startup so edits cannot alter a running cohort.
    shutil.copytree(HERE, source / 'coupon', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copytree(HERE.parent / 'lib', source / 'lib', ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    harness_hashes = {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in source.rglob('*') if p.is_file()}
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
                **pool_properties(pool_size),
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
            env = environment() | {'SPRING_PROFILES_ACTIVE': 'test,coupon-benchmark',
                                   'BENCHMARK_READ_MODEL_TOKEN': token, 'AWS_EC2_METADATA_DISABLED': 'true'}
            def start_application(log_name, server_port=0):
                nonlocal app
                log_path = output / log_name
                with log_path.open('w') as log:
                    app = subprocess.Popen([java, '-Xms512m', '-Xmx512m', '-Duser.timezone=UTC', '-jar', str(jar),
                                            '--spring.config.additional-location=file:' + str(config_path),
                                            '--server.address=127.0.0.1', f'--server.port={server_port}'], cwd=ROOT, env=env,
                                           stdout=log, stderr=subprocess.STDOUT)
                for _ in range(240):
                    require(app.poll() is None, 'Local Airbob stopped; inspect ' + log_name)
                    matches = re.findall(r'Tomcat started on port (\d+)', log_path.read_text(errors='replace'))
                    if matches:
                        started_origin = 'http://127.0.0.1:' + matches[-1]
                        try:
                            request(started_origin, '/actuator/health')
                            return started_origin
                        except (OSError, ValueError):
                            pass
                    time.sleep(0.5)
                raise ValueError('Local Airbob startup timed out; inspect ' + log_name)
            origin = start_application('application.log')
            require(sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;') == '30',
                    'Local coupon binary must use Flyway V30 and UTC coupon instants')
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
                    "'sync_binlog','log_bin','innodb_buffer_pool_size','max_connections');"), 'fileInstruments': sql(
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
            source_path = output / 'local-source-dataset.json'
            save(source_path, {'scope': 'local-coupon-only', 'flywayVersion': 30,
                               'mysqlVersion': sql('SELECT VERSION();'), 'accountCount': count})
            manifest = {'schemaVersion': 1, 'datasetVersion': 'coupon-accounts-v1',
                        'sourceDataset': {'id': run_id, 'flywayVersion': 30,
                                          'manifestSha256': hashlib.sha256(source_path.read_bytes()).hexdigest()},
                        'accountPool': {'capacity': count, 'emails': emails}}
            manifest_path = output / 'local-contract-manifest.json'
            save(manifest_path, manifest)
            sessions = private / 'sessions.json'
            print(f'Preparing {count} real login sessions (outside measurement)', flush=True)
            session_started = time.time()
            with (output / 'session-preparation.log').open('w') as log:
                subprocess.run(['node', str(source / 'coupon/prepare-coupon-sessions.js'), '--base-url', origin,
                                '--manifest', str(manifest_path), '--password-file', str(private / 'password'),
                                '--session-output', str(sessions), '--required-capacity', str(count), '--concurrency', '20'],
                               stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1800,
                               env=environment() | {'AIRBOB_SESSION_PREPARATION_TEST_MODE': '1'})
            print(f'Real login sessions ready: {count}', flush=True)
            session_refreshes = []
            session_file = sessions
            def current_sessions():
                nonlocal session_started, session_file
                # Refresh both member and administration sessions outside measured work.
                if time.time() - session_started < 30 * 60:
                    return session_file
                session_started = time.time()
                print('Refreshing local sessions outside measurement', flush=True)
                next_session_file = private / f'sessions-{len(session_refreshes) + 1}.json'
                with (output / 'session-preparation.log').open('a') as log:
                    subprocess.run(['node', str(source / 'coupon/prepare-coupon-sessions.js'), '--base-url', origin,
                                    '--manifest', str(manifest_path), '--password-file', str(private / 'password'),
                                    '--session-output', str(next_session_file), '--required-capacity', str(count), '--concurrency', '20'],
                                   stdout=log, stderr=subprocess.STDOUT, check=True, timeout=900,
                                   env=environment() | {'AIRBOB_SESSION_PREPARATION_TEST_MODE': '1'})
                _, renewed = request(origin, '/api/v1/auth/login', {'email': 'admin@coupon.local', 'password': password})
                renewed_cookie = SimpleCookie()
                renewed_cookie.load(renewed['Set-Cookie'])
                (private / 'admin-session').write_text(renewed_cookie['SESSION_ID'].value)
                session_file = next_session_file
                session_refreshes.append({'startedAtEpochMs': session_started * 1000,
                                          'durationSeconds': time.time() - session_started, 'accountCount': count})
                save(output / 'session-refreshes.json', session_refreshes)
                return session_file
            c = json.loads((HERE / 'experiment.example.json').read_text())
            c.update(variants=list(args.variants), runId=run_id, baseUrl=origin, appInstanceCount=1, appVersion=command(['git', 'rev-parse', 'HEAD']),
                     benchmarkDatasetManifest=str(manifest_path), adminSessionFile=str(private / 'admin-session'),
                     benchmarkTokenFile=str(private / 'token'), clientVUs=args.client_vus,
                     appMetricsUrls=[origin + '/actuator/prometheus'],
                     scriptDirectory=str(source / 'coupon'),
                     hikariMaximumPoolSize=pool_size,
                     p95LimitMs=args.p95_limit_ms, p99LimitMs=args.p99_limit_ms)
            subject = LocalRunner(c, output, bool(args.capacity_rates), diagnostic_context,
                                  args.scarcity_stock if scarcity_rates else None, capacity_stock)
            subject.capacity_seed = refinement_source['priorBounds'] if refinement_source else None
            subject.manifest_hash = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
            metadata = {'scope': 'local-coupon-comparison', 'environment': 'local', 'variants': list(args.variants), 'appJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                        'appCommit': c['appVersion'], 'sourceTreeDirty': bool(command(['git', 'status', '--porcelain'])),
                        'mysqlVersion': sql('SELECT VERSION();'),
                        'flywayVersion': sql('SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;'),
                        'memberCount': int(sql('SELECT COUNT(*) FROM member;')), 'mysqlContainer': mysql_id,
                        'redisContainer': redis_id, 'origin': origin,
                        'rate': args.rate if not workload_plan else None,
                        'durationSeconds': args.duration if not workload_plan else None,
                        'rounds': args.rounds, 'javaExecutable': java, 'capacityRates': args.capacity_rates,
                        'hikariMaximumPoolSize': None if pool_sizes else pool_size,
                        'hikariMinimumIdle': None if pool_sizes else pool_size,
                        'poolSizes': pool_sizes, 'poolCohorts': [],
                        'workloadPlan': workload_plan,
                        'capacityRefinementSource': refinement_source,
                        'capacityStock': capacity_stock, 'poolSizingExperiment': bool(pool_sizes or pool_size != 10),
                        'harnessSha256': harness_hashes, 'harnessSnapshot': str(source),
                        'refineStep': args.refine_step, 'clientVUs': args.client_vus, 'cooldownSeconds': args.cooldown,
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
            sessions = current_sessions
            def restart_for_pool(cohort):
                nonlocal origin
                size = cohort['poolSize']
                app.terminate()
                try:
                    app.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    app.kill()
                    app.wait()
                properties.update(pool_properties(size))
                config_path.write_text('\n'.join(k + '=' + v for k, v in properties.items()) + '\n')
                origin = start_application(f"application-pool-{cohort['cohort']:02d}-{size}.log",
                                           int(origin.rsplit(':', 1)[1]))
                diagnostic_context.update(appPid=app.pid, metricsUrl=origin + '/actuator/prometheus')
                subject.c.update(baseUrl=origin, appMetricsUrls=[origin + '/actuator/prometheus'],
                                 hikariMaximumPoolSize=size, poolCohort=cohort['cohort'])
                metadata['poolCohorts'].append({k: v for k, v in cohort.items() if k != 'cases'} | {
                    'caseOrder': [case['name'] for case in cohort['cases']], 'appPid': app.pid,
                    'readyAtEpochMs': time.time() * 1000,
                    'priorMemberCouponRows': int(sql('SELECT COUNT(*) FROM member_coupon;')),
                    'priorSurrogateUnitRows': int(sql('SELECT COUNT(*) FROM coupon_issue_unit;'))})
                save(output / 'run.json', metadata)
                print(f"Pool cohort {cohort['cohort']}: round={cohort['round']} pool={size}; application restarted", flush=True)
            if pool_sizes:
                compare_pools(subject, args, sessions, workload_plan, restart_for_pool)
            elif workload_plan:
                for case in workload_plan['cases']:
                    for round_number in range(1, args.rounds + 1):
                        for variant in order(round_number, args.variants):
                            subject.workload = None
                            subject.measure(variant, 'capacity', 100, 10, round_number, sessions, 'warmup')
                            time.sleep(args.cooldown)
                            subject.workload = case
                            row = subject.measure(variant, case['scenario'], max(s['rate'] for s in case['segments']),
                                                  case['durationSeconds'], round_number, sessions)
                            fatal = set(row['evaluation']['invalidReasons']) - {
                                'incomplete-offered-load', 'local-host-pressure-or-missing-diagnostics'}
                            require(not fatal, 'Local scenario lost correctness: ' + ', '.join(sorted(fatal)))
                            time.sleep(args.cooldown)
                subject.workload = None
            elif scarcity_rates:
                if confirmation_rate:
                    confirmation = argparse.Namespace(**vars(args))
                    confirmation.scarcity_rates = [confirmation_rate]
                    confirmation.scarcity_variants = list(args.variants)
                    confirmation.duration = args.scarcity_confirm_duration
                    confirmation.rounds = args.scarcity_confirm_rounds
                    compare_scarcity(subject, confirmation, sessions)
                compare_scarcity(subject, args, sessions)
            elif args.capacity_rates:
                search_capacity(subject, args, sessions)
            elif args.diagnose_rate:
                for round_number in range(1, args.rounds + 1):
                    for variant in order(round_number, getattr(args, 'variants', DEFAULT_VARIANTS)):
                        subject.measure(variant, 'capacity', min(100, args.diagnose_rate), 10,
                                        round_number, sessions, 'warmup')
                        time.sleep(args.cooldown)
                        subject.measure(variant, 'capacity', args.diagnose_rate, args.duration,
                                        round_number, sessions)
                        time.sleep(args.cooldown)
            else:
                for scenario in ('capacity', 'scarcity'):
                    for round_number in range(1, args.rounds + 1):
                        for variant in order(round_number, getattr(args, 'variants', DEFAULT_VARIANTS)):
                            subject.measure(variant, 'capacity', min(100 if observed else 10, args.rate),
                                            10 if observed else 5, round_number, sessions, 'warmup')
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
    mode.add_argument('--workload-plan', help='Bounded local sustain/spike/scarcity/duplicate workload JSON')
    parser.add_argument('--scarcity-stock', type=int, default=1000, help='Stock shared by all scarcity RPS steps')
    parser.add_argument('--capacity-stock', type=int, help='Fixed initial stock for every measured capacity trial')
    parser.add_argument('--refine-from', help='Prior local-results.json with completed coarse bounds; measure only refinement')
    parser.add_argument('--variants', '--scarcity-variants', dest='variants', nargs='+', choices=VARIANTS, default=list(DEFAULT_VARIANTS),
                        help='Variants to probe; previous failures remain in their original reports')
    parser.add_argument('--scarcity-confirm-rate', type=int, help='First confirm this RPS with both variants')
    parser.add_argument('--scarcity-confirm-duration', type=int, default=60)
    parser.add_argument('--scarcity-confirm-rounds', type=int, default=2)
    parser.add_argument('--refine-step', type=int, default=25, help='Stop narrowing at this RPS interval')
    parser.add_argument('--client-vus', type=int, default=50, help='Preallocated and maximum k6 virtual users')
    parser.add_argument('--cooldown', type=int, default=3, help='Pause between trials in seconds')
    parser.add_argument('--p95-limit-ms', type=int, default=500)
    parser.add_argument('--p99-limit-ms', type=int, default=1000)
    parser.add_argument('--observe', action='store_true', help='Observe DB, pool, containers and shared host in both scenarios')
    parser.add_argument('--pool-size', type=int, default=10, help='Local Hikari maximum/minimumIdle (1..64, default 10)')
    parser.add_argument('--pool-sizes', type=int, nargs='+', help='Compare pools with app restarts; requires one variant and workload plan')
    parser.add_argument('--java-home', help='Current Java 21 with the time-zone data required by Airbob')
    args = parser.parse_args()
    os.umask(0o077)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    run(args)
