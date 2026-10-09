"""Capacity-search decisions must not turn incomplete load into a measured server limit."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'coupon'))
import run_local_e2e as local


def bound():
    return {'status': 'lower-bound-only', 'highestConfirmedTargetRps': 100, 'firstFailingTargetRps': None}


def rows(*passes, valid=True):
    return [{'evaluation': {'valid': valid, 'sloPassed': passed}} for passed in passes]


class CapacitySearch(unittest.TestCase):
    def test_repeated_failure_brackets_and_midpoint_refines(self):
        result = bound()
        local.update_bound(result, 200, rows(False, False))
        self.assertEqual(result['status'], 'bracketed')
        self.assertEqual(local.refinement_rate(result, 25), 150)
        local.update_bound(result, 150, rows(True, True))
        self.assertEqual(local.refinement_rate(result, 25), 175)
        local.update_bound(result, 175, rows(False, False))
        self.assertIsNone(local.refinement_rate(result, 25))
        self.assertEqual(result['highestConfirmedTargetRps'], 150)
        self.assertEqual(result['firstFailingTargetRps'], 175)

    def test_dropped_load_is_not_a_failing_bound_but_can_guide_lower_probe(self):
        result = bound()
        local.update_bound(result, 200, rows(False, False, valid=False))
        self.assertEqual(result['status'], 'inconclusive')
        self.assertIsNone(result['firstFailingTargetRps'])
        self.assertEqual(local.refinement_rate(result, 25), 150)
        local.update_bound(result, 150, rows(True, True))
        self.assertEqual(result['status'], 'inconclusive')
        local.update_bound(result, 175, rows(False, False))
        self.assertEqual(result['status'], 'bracketed')
        self.assertNotIn('stoppedAtRps', result)

    def test_mixed_repetitions_remain_unstable(self):
        result = bound()
        local.update_bound(result, 200, rows(True, False))
        self.assertEqual(result['status'], 'unstable')
        self.assertIsNone(result['firstFailingTargetRps'])
        local.update_bound(result, 150, rows(True, True))
        self.assertEqual(result['status'], 'unstable')
        self.assertEqual(result['highestConfirmedTargetRps'], 150)

    def test_unbracketed_ceiling_is_only_a_lower_bound(self):
        result = bound()
        local.update_bound(result, 200, rows(True, True))
        self.assertEqual(result['status'], 'lower-bound-only')
        self.assertIsNone(local.refinement_rate(result, 25))

    def test_irregular_steps_stay_inside_the_interval(self):
        result = bound()
        result.update(highestConfirmedTargetRps=109, firstFailingTargetRps=136, status='bracketed')
        self.assertTrue(109 < local.refinement_rate(result, 25) < 136)

    def test_workload_alternates_variants_repeats_and_refines_independently(self):
        subject, calls = Mock(), []
        def measure(variant, scenario, rate, seconds, round_number, sessions, phase='measure'):
            if phase == 'warmup':
                return
            calls.append((variant, rate, round_number))
            return rows(rate <= (150 if variant == 'db' else 250))[0]
        subject.measure.side_effect = measure
        args = SimpleNamespace(capacity_rates=[100, 200, 400], rounds=2, duration=20, cooldown=0, refine_step=25)
        with patch.object(local.time, 'sleep'):
            local.search_capacity(subject, args, '/unused-sessions')
        self.assertEqual(calls[:4], [('db', 100, 1), ('lua', 100, 1), ('lua', 100, 2), ('db', 100, 2)])
        self.assertNotIn(('db', 400, 1), calls)
        self.assertEqual(subject.bounds['db']['highestConfirmedTargetRps'], 150)
        self.assertEqual(subject.bounds['db']['firstFailingTargetRps'], 175)
        self.assertEqual(subject.bounds['lua']['highestConfirmedTargetRps'], 250)
        self.assertEqual(subject.bounds['lua']['firstFailingTargetRps'], 275)


if __name__ == '__main__':
    unittest.main()
