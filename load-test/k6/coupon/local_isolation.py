"""Compare issuance variants with a matching default isolation on every pool connection."""
import re
import time

from coupon_experiment import order, require, VARIANTS

POOL_ISOLATION = 'spring.datasource.hikari.transaction-isolation'
POOL_SESSIONS_SQL = """SELECT v.VARIABLE_VALUE, COUNT(*)
    FROM performance_schema.variables_by_thread v
    JOIN performance_schema.threads t ON t.THREAD_ID = v.THREAD_ID
    WHERE v.VARIABLE_NAME = 'transaction_isolation'
      AND t.PROCESSLIST_USER = 'coupon_app' AND t.PROCESSLIST_DB = 'coupon_local'
    GROUP BY v.VARIABLE_VALUE"""


def isolation_for(variant):
    require(variant in VARIANTS, 'Unknown coupon variant')
    return 'TRANSACTION_READ_COMMITTED' if variant.endswith('_rc') else 'TRANSACTION_REPEATABLE_READ'


def validate_matched_isolation(plan, pool_sizes):
    require(plan is not None and pool_sizes is None,
            'Matched pool isolation requires a workload plan and cannot vary pool sizes')


def pool_sessions(raw):
    result = {}
    for line in raw.splitlines():
        level, count = line.split('\t')
        require(level in ('READ-COMMITTED', 'REPEATABLE-READ') and count.isdecimal(),
                'Unrecognized pool session observation')
        result[level] = result.get(level, 0) + int(count)
    return result


def verify_pool_sessions(observed, variant, size):
    expected = isolation_for(variant).removeprefix('TRANSACTION_').replace('_', '-')
    require(observed == {expected: size},
            f'Unexpected pool session isolation: expected {size} {expected}, observed {observed}')


def driver_isolation_kind(template):
    sql = ' '.join(template.replace('`', '').upper().split())
    if re.fullmatch(r'SET SESSION TRANSACTION ISOLATION LEVEL (READ COMMITTED|REPEATABLE READ)', sql):
        return 'set'
    if re.fullmatch(r'SELECT @@SESSION\s*\.\s*TRANSACTION_ISOLATION', sql):
        return 'read'
    return None


def isolation_schedule(variants, cases, rounds):
    schedule = []
    for round_number in range(1, rounds + 1):
        for variant in order(round_number, variants):
            schedule.append({'cohort': len(schedule) + 1, 'round': round_number,
                             'variant': variant, 'transactionIsolation': isolation_for(variant),
                             'cases': cases})
    return schedule


def compare_matched_isolation(subject, args, sessions, plan, restart):
    for cohort in isolation_schedule(args.variants, plan['cases'], args.rounds):
        # Restart the first candidate too: account preparation must not warm only that JVM.
        restart(cohort)
        for case in cohort['cases']:
            subject.workload = None
            subject.measure(cohort['variant'], 'capacity', 100, 10, cohort['round'], sessions, 'warmup')
            time.sleep(args.cooldown)
            subject.workload = case
            row = subject.measure(cohort['variant'], case['scenario'], max(s['rate'] for s in case['segments']),
                                  case['durationSeconds'], cohort['round'], sessions)
            fatal = set(row['evaluation']['invalidReasons']) - {
                'incomplete-offered-load', 'local-host-pressure-or-missing-diagnostics'}
            require(not fatal, 'Isolation experiment lost correctness/configuration: ' + ', '.join(sorted(fatal)))
            time.sleep(args.cooldown)
    subject.workload = None
