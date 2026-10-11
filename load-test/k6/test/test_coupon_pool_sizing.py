"""Pool experiments must change only their declared setting and retain failed trials."""
import copy
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'coupon'))
from local_pool_sizing import (compare_pools, pool_observation_failures, pool_properties,
                               pool_schedule, validate_pool_comparison)
from local_workloads import validate_plan


def plan():
    return validate_plan({'schemaVersion': 1, 'cases': [
        {'name': f'rate-{rate}', 'scenario': 'capacity', 'stock': 31,
         'segments': [{'name': 'steady', 'rate': rate, 'seconds': 10}]} for rate in (1, 2, 3)]})


class PoolSizing(unittest.TestCase):
    def test_fixed_pool_preserves_both_limits_and_rejects_unsafe_sizes(self):
        for size in (10, 20, 30):
            self.assertEqual(set(pool_properties(size).values()), {str(size)})
        for size in (0, -1, 65, True, 10.5, '20'):
            with self.subTest(size=size), self.assertRaises(ValueError):
                pool_properties(size)

    def test_observed_limit_must_match_selected_pool_instead_of_hard_coded_ten(self):
        self.assertEqual(pool_observation_failures({'hikari': {'maxConnections': 20, 'minConnections': 20}}, 20), [])
        for actual in ({'maxConnections': 10}, {'maxConnections': 20, 'minConnections': 10}):
            self.assertEqual(pool_observation_failures({'hikari': actual}, 20), ['unexpected-hikari-pool-size'])

    def test_every_pool_occurs_once_at_each_position_and_every_rate_rotates(self):
        schedule = pool_schedule([10, 20, 30], plan()['cases'], 3)
        self.assertEqual([c['poolSize'] for c in schedule], [10, 20, 30, 20, 30, 10, 30, 10, 20])
        for size in (10, 20, 30):
            self.assertEqual({i % 3 for i, c in enumerate(schedule) if c['poolSize'] == size}, {0, 1, 2})
            self.assertEqual({c['cases'][0]['name'] for c in schedule if c['poolSize'] == size},
                             {'rate-1', 'rate-2', 'rate-3'})

    def test_mixed_application_variants_stock_and_unbalanced_rounds_are_rejected(self):
        good = plan()
        validate_pool_comparison([10, 20, 30], good, 3, ['skip_surrogate_rr'])
        for sizes, rounds, variants in [([10, 10], 2, ['skip_surrogate_rr']),
                                         ([10, 20, 30], 2, ['skip_surrogate_rr']),
                                         ([10, 20], 2, ['db', 'skip_surrogate_rr'])]:
            with self.assertRaises(ValueError):
                validate_pool_comparison(sizes, good, rounds, variants)
        for field, value in [('stock', 32), ('scenario', 'scarcity')]:
            bad = copy.deepcopy(good)
            bad['cases'][0][field] = value
            with self.assertRaises(ValueError):
                validate_pool_comparison([10, 20, 30], bad, 3, ['skip_surrogate_rr'])

    def test_restarts_precede_each_cohort_and_load_failures_do_not_erase_later_trials(self):
        subject, events = Mock(), []
        args = SimpleNamespace(pool_sizes=[10, 20, 30], rounds=3, variants=['skip_surrogate_rr'], cooldown=0)
        def restart(cohort):
            events.append(('restart', cohort['poolSize']))
        def measure(*args):
            events.append(('warmup' if len(args) == 7 else 'measure', args[2]))
            return {'evaluation': {'invalidReasons': ['incomplete-offered-load']}}
        subject.measure.side_effect = measure
        with patch('local_pool_sizing.time.sleep'):
            compare_pools(subject, args, '/unused', plan(), restart)
        self.assertEqual(events[:3], [('restart', 10), ('warmup', 100), ('measure', 1)])
        self.assertEqual(sum(e[0] == 'restart' for e in events), 9)
        self.assertEqual(sum(e[0] == 'measure' for e in events), 27)

    def test_configuration_mismatch_stops_experiment_without_advancing_pool(self):
        subject, restart = Mock(), Mock()
        subject.measure.return_value = {'evaluation': {'invalidReasons': ['unexpected-hikari-pool-size']}}
        args = SimpleNamespace(pool_sizes=[10, 20, 30], rounds=3, variants=['skip_surrogate_rr'], cooldown=0)
        with patch('local_pool_sizing.time.sleep'), self.assertRaisesRegex(ValueError, 'unexpected-hikari-pool-size'):
            compare_pools(subject, args, '/unused', plan(), restart)
        self.assertEqual(restart.call_count, 1)


if __name__ == '__main__':
    unittest.main()
