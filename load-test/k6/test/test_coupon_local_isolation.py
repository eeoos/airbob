"""Pool defaults and transaction declarations must not be conflated in isolation comparisons."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'coupon'))
from coupon_experiment import VARIANTS
from local_isolation import (compare_matched_isolation, driver_isolation_kind, isolation_for,
                             isolation_schedule, pool_sessions, validate_matched_isolation,
                             verify_pool_sessions)
from local_workloads import validate_plan


def plan():
    return validate_plan({'schemaVersion': 1, 'cases': [
        {'name': 'enough', 'scenario': 'capacity', 'stock': 101,
         'segments': [{'name': 'steady', 'rate': 10, 'seconds': 10}]},
        {'name': 'empty', 'scenario': 'scarcity', 'stock': 1,
         'segments': [{'name': 'steady', 'rate': 10, 'seconds': 10}]}]})


class MatchedIsolation(unittest.TestCase):
    def test_all_variants_have_matching_explicit_defaults(self):
        for variant in VARIANTS:
            expected = 'TRANSACTION_READ_COMMITTED' if variant.endswith('_rc') else 'TRANSACTION_REPEATABLE_READ'
            self.assertEqual(isolation_for(variant), expected)
        with self.assertRaises(ValueError):
            isolation_for('unknown')

    def test_rejects_pool_size_experiment_or_missing_plan_before_infrastructure(self):
        import run_local_e2e
        for sizes in (None, [10, 20]):
            with self.assertRaisesRegex(ValueError, 'requires a workload plan'):
                run_local_e2e.run(SimpleNamespace(match_pool_isolation=True, pool_sizes=sizes))
        with self.assertRaises(ValueError):
            validate_matched_isolation(plan(), [10, 20])

    def test_requires_all_app_connections_to_match_not_just_a_global_default(self):
        observed = pool_sessions('READ-COMMITTED\t10')
        verify_pool_sessions(observed, 'skip_surrogate_rc', 10)
        for raw in ('', 'REPEATABLE-READ\t10', 'READ-COMMITTED\t9',
                    'READ-COMMITTED\t9\nREPEATABLE-READ\t1'):
            with self.subTest(raw=raw), self.assertRaisesRegex(ValueError, 'Unexpected pool session isolation'):
                verify_pool_sessions(pool_sessions(raw), 'skip_surrogate_rc', 10)

    def test_balances_variant_position_with_a_separate_restart_per_candidate(self):
        schedule = isolation_schedule(VARIANTS, plan()['cases'], 5)
        self.assertEqual(len(schedule), 25)
        for variant in VARIANTS:
            self.assertEqual({i % 5 for i, c in enumerate(schedule) if c['variant'] == variant}, set(range(5)))

    def test_restarts_even_first_variant_and_keeps_scarcity_and_failed_load_trials(self):
        events, subject = [], Mock()
        args = SimpleNamespace(variants=['skip_surrogate_rr', 'skip_surrogate_rc'], rounds=1, cooldown=0)
        def restart(cohort):
            events.append(('restart', cohort['variant']))
        def measure(*args):
            events.append(('warmup' if len(args) == 7 else 'measure', args[0], args[1]))
            return {'evaluation': {'invalidReasons': ['incomplete-offered-load']}}
        subject.measure.side_effect = measure
        with patch('local_isolation.time.sleep'):
            compare_matched_isolation(subject, args, '/unused', plan(), restart)
        self.assertEqual(events[:3], [('restart', 'skip_surrogate_rr'),
                                     ('warmup', 'skip_surrogate_rr', 'capacity'),
                                     ('measure', 'skip_surrogate_rr', 'capacity')])
        self.assertEqual(sum(e[0] == 'restart' for e in events), 2)
        self.assertEqual(sum(e[0] == 'measure' for e in events), 4)
        self.assertEqual(events[-1], ('measure', 'skip_surrogate_rc', 'scarcity'))

    def test_stops_on_configuration_or_correctness_failure(self):
        subject, restart = Mock(), Mock()
        subject.measure.return_value = {'evaluation': {'invalidReasons': ['unexpected-hikari-pool-size']}}
        args = SimpleNamespace(variants=list(VARIANTS), rounds=1, cooldown=0)
        with patch('local_isolation.time.sleep'), self.assertRaisesRegex(ValueError, 'unexpected-hikari-pool-size'):
            compare_matched_isolation(subject, args, '/unused', plan(), restart)
        self.assertEqual(restart.call_count, 1)

    def test_driver_query_matching_excludes_startup_and_observer_queries(self):
        self.assertEqual(driver_isolation_kind('SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED'), 'set')
        self.assertEqual(driver_isolation_kind('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ'), 'set')
        self.assertEqual(driver_isolation_kind('SELECT @@SESSION . `transaction_isolation`'), 'read')
        for sql in ('SELECT @@SESSION . `transaction_isolation`, @@`time_zone`',
                    'SELECT DIGEST_TEXT WHERE DIGEST_TEXT LIKE ?', 'COMMIT'):
            self.assertIsNone(driver_isolation_kind(sql))


if __name__ == '__main__':
    unittest.main()
