"""Sold-out responses must not hide failed issuance latency or missed offered load."""
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'coupon'))
import run_local_e2e as local


class ScarcityComparison(unittest.TestCase):
    def test_warmup_latency_does_not_weaken_measured_slo_or_mask_missing_requests(self):
        row = {'evaluation': {'invalidReasons': [], 'sloReasons': ['success-p(95)'], 'sloPassed': False},
               'client': {'requestCount': 1000, 'outcomes': {'success': 1000}}}
        self.assertEqual(local.warmup_failures(row), [])
        self.assertFalse(row['evaluation']['sloPassed'])
        row['evaluation']['invalidReasons'] = ['incomplete-offered-load']
        self.assertEqual(local.warmup_failures(row), ['incomplete-offered-load'])
        row['client']['outcomes']['success'] = 999
        self.assertIn('warmup-not-all-success', local.warmup_failures(row))

    def test_slow_sold_out_responses_fail_even_if_total_rate_is_met(self):
        p = {'soldOutDuration': {'p(95)': 501, 'p(99)': 1001}, 'requestRate': 3000}
        self.assertEqual(local.scarcity_slo_failures(p, 3000, 500, 1000),
                         ['sold-out-p(95)', 'sold-out-p(99)'])

    def test_missing_rejection_latency_is_not_zero_and_drain_reduces_throughput(self):
        p = {'soldOutDuration': {}, 'requestRate': 2969}
        self.assertEqual(local.scarcity_slo_failures(p, 3000, 500, 1000),
                         ['sold-out-p(95)', 'sold-out-p(99)', 'request-throughput-below-target'])

    def test_alternates_variants_and_keeps_later_pairs_after_latency_or_dropped_load(self):
        subject, calls = Mock(), []

        def measure(variant, scenario, rate, seconds, round_number, sessions, phase='measure'):
            calls.append((variant, scenario, rate, seconds, round_number, phase))
            return {'evaluation': {'invalidReasons': ['incomplete-offered-load']},
                    'client': {'outcomes': {'unexpected': 0}}}

        subject.measure.side_effect = measure
        args = SimpleNamespace(scarcity_rates=[500, 3000], rounds=2, duration=20, cooldown=0)
        with patch.object(local.time, 'sleep'):
            local.compare_scarcity(subject, args, '/unused-sessions')
        measured = [c for c in calls if c[-1] == 'measure']
        self.assertEqual([(v, r, n) for v, _, r, _, n, _ in measured],
                         [('db', 500, 1), ('lua', 500, 1), ('lua', 500, 2), ('db', 500, 2),
                          ('db', 3000, 1), ('lua', 3000, 1), ('lua', 3000, 2), ('db', 3000, 2)])
        self.assertTrue(all(c[1] == 'scarcity' and c[3] == 20 for c in measured))
        self.assertEqual(len([c for c in calls if c[1] == 'capacity' and c[-1] == 'warmup']), 8)

    def test_bad_fixtures_stop_before_more_business_requests(self):
        subject = Mock()
        subject.measure.return_value = {'evaluation': {'invalidReasons': ['database-invariant']},
                                        'client': {'outcomes': {'unexpected': 0}}}
        args = SimpleNamespace(scarcity_rates=[500, 3000], rounds=2, duration=20, cooldown=0)
        with patch.object(local.time, 'sleep'), self.assertRaisesRegex(ValueError, 'database-invariant'):
            local.compare_scarcity(subject, args, '/unused-sessions')
        self.assertEqual(subject.measure.call_count, 2)

    def test_selected_variant_retains_timeout_failures_without_repeating_other_variant(self):
        subject = Mock()
        subject.measure.return_value = {'evaluation': {'invalidReasons': ['incomplete-offered-load']},
                                        'client': {'outcomes': {'unexpected': 5}}}
        args = SimpleNamespace(scarcity_rates=[3000], scarcity_variants=['lua'],
                               rounds=3, duration=20, cooldown=0)
        with patch.object(local.time, 'sleep'):
            local.compare_scarcity(subject, args, '/unused-sessions')
        self.assertEqual(subject.measure.call_count, 6)
        self.assertTrue(all(c.args[0] == 'lua' for c in subject.measure.call_args_list))


if __name__ == '__main__':
    unittest.main()
