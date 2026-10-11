"""Controlled local pool comparisons; the application and DB configuration stay fixed."""
import time

from coupon_experiment import require


def pool_properties(size):
    require(type(size) is int and 1 <= size <= 64, 'Local Hikari pool size must be 1..64')
    return {'spring.datasource.hikari.maximum-pool-size': str(size),
            'spring.datasource.hikari.minimum-idle': str(size)}


def pool_observation_failures(diagnostics, expected):
    hikari = diagnostics['hikari']
    return ([] if hikari['maxConnections'] == expected
            and hikari.get('minConnections') == expected else ['unexpected-hikari-pool-size'])


def validate_pool_comparison(sizes, plan, rounds, variants):
    require(isinstance(sizes, list) and 2 <= len(sizes) <= 5
            and all(type(size) is int for size in sizes) and len(set(sizes)) == len(sizes),
            'Pool comparison needs 2..5 distinct integer sizes')
    for size in sizes:
        pool_properties(size)
    require(len(sizes) <= rounds <= 6 and rounds % len(sizes) == 0,
            'Pool rounds must be a multiple of the number of sizes, at most six')
    require(plan and len(variants) == 1, 'Pool comparison needs a workload plan and one fixed variant')
    cases = plan['cases']
    require(all(case['scenario'] == 'capacity' and len(case['segments']) == 1
                and not case.get('otherPreparation') for case in cases),
            'Pool comparison only supports steady, sufficient-stock issuance')
    require(len({case['stock'] for case in cases}) == 1
            and len({case['durationSeconds'] for case in cases}) == 1,
            'Pool comparison must keep initial stock and duration fixed across rates')


def pool_schedule(sizes, cases, rounds):
    schedule = []
    for round_number in range(1, rounds + 1):
        pool_offset, case_offset = (round_number - 1) % len(sizes), (round_number - 1) % len(cases)
        for size in sizes[pool_offset:] + sizes[:pool_offset]:
            schedule.append({'cohort': len(schedule) + 1, 'round': round_number, 'poolSize': size,
                             'cases': cases[case_offset:] + cases[:case_offset]})
    return schedule


def compare_pools(subject, args, sessions, plan, restart):
    variant = args.variants[0]
    for cohort in pool_schedule(args.pool_sizes, plan['cases'], args.rounds):
        # Restart even the first pool, so login preparation never warms only one candidate.
        restart(cohort)
        for case in cohort['cases']:
            subject.workload = None
            subject.measure(variant, 'capacity', 100, 10, cohort['round'], sessions, 'warmup')
            time.sleep(args.cooldown)
            subject.workload = case
            row = subject.measure(variant, 'capacity', case['segments'][0]['rate'],
                                  case['durationSeconds'], cohort['round'], sessions)
            fatal = set(row['evaluation']['invalidReasons']) - {
                'incomplete-offered-load', 'local-host-pressure-or-missing-diagnostics'}
            require(not fatal, 'Pool experiment lost correctness/configuration: ' + ', '.join(sorted(fatal)))
            time.sleep(args.cooldown)
    subject.workload = None
