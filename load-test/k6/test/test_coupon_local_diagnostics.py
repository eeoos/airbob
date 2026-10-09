"""Keep diagnostic units, windows and missing observations explicit."""
import copy
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'coupon'))
from local_diagnostics import app_metrics, summary


def evidence():
    app = {'process_start_time_seconds': 1, 'hikaricp_connections_acquire_seconds_count': 0,
           'hikaricp_connections_acquire_seconds_sum': 0, 'hikaricp_connections_timeout_total': 0,
           'hikaricp_connections_max': 10, 'hikaricp_connections_pending': 0, 'jvm_gc_pause_seconds_sum': 0}
    before = {'timestampMs': 0, 'app': app, 'mysql': {'Innodb_row_lock_waits': 0, 'Innodb_row_lock_time': 0},
              'containers': {'mysql': {'cpuUsageNs': 0, 'blockReadBytes': 0, 'blockWriteBytes': 0,
                                       'memoryBytes': 100}}, 'fileIo': [], 'statements': []}
    after = copy.deepcopy(before)
    after.update(timestampMs=120000)
    after['app'].update(hikaricp_connections_acquire_seconds_count=1000,
                        hikaricp_connections_acquire_seconds_sum=20)
    after['mysql'].update(Innodb_row_lock_waits=900, Innodb_row_lock_time=180000)
    after['containers']['mysql']['cpuUsageNs'] = 60_000_000_000
    after['fileIo'] = [{'EVENT_NAME': 'wait/io/file/innodb/innodb_log_file', 'COUNT_STAR': 100,
                       'SUM_TIMER_WAIT': 1_000_000_000_000, 'COUNT_MISC': 50, 'SUM_TIMER_MISC': 900_000_000_000}]
    after['statements'] = [{'DIGEST': 'a', 'DIGEST_TEXT': 'UPDATE `coupon` SET ...',
                            'COUNT_STAR': 1000, 'SUM_TIMER_WAIT': 200_000_000_000_000}]
    samples = []
    for t in (500, 2000, 100000, 119500):
        s = copy.deepcopy(before)
        s.update(timestampMs=t, lockWaits=[{'OBJECT_NAME': 'coupon', 'INDEX_NAME': 'PRIMARY',
                                          'LOCK_MODE': 'X,REC_NOT_GAP', 'waiting_threads': 9}],
                 host={'cpuPercent': 25, 'memoryFreePercent': 40,
                       'processes': {'k6': {'cpuPercent': 30, 'rssBytes': 200}}})
        s['app']['hikaricp_connections_pending'] = 8 if t == 2000 else 0
        samples.append(s)
    return [before, after, samples, [], {'measurementStartEpochMs': 1000, 'measurementEndEpochMs': 119000}]


class Diagnostics(unittest.TestCase):
    def test_short_diagnosis_is_rejected_before_optional_dependencies_or_setup(self):
        import run_local_e2e
        args = SimpleNamespace(rate=50, duration=5, rounds=1, diagnose_rate=10)
        with self.assertRaisesRegex(ValueError, 'at least 10 seconds'):
            run_local_e2e.run(args)

    def test_prometheus_extracts_only_allowed_numeric_observations(self):
        values = app_metrics('''# HELP ignored
hikaricp_connections_pending{pool="one"} 2
hikaricp_connections_pending{pool="two"} 3
process_start_time_seconds 12
unrelated_metric{secret="never retained"} 999
process_cpu_usage NaN
''')
        self.assertEqual(values, {'hikaricp_connections_pending': 5, 'process_start_time_seconds': 12})

    def test_units_and_cumulative_wait_time_are_preserved(self):
        result = summary(*evidence())
        self.assertEqual(result['rowLocks']['accumulatedWaitSeconds'], 180)
        self.assertEqual(result['rowLocks']['meanWaitMs'], 200)
        self.assertEqual(result['hikari']['acquireMeanMs'], 20)
        self.assertEqual(result['containers']['mysql']['averageCpuCores'], 0.5)
        self.assertEqual(result['fileIo'][0]['meanMs'], 10)
        self.assertEqual(result['statements'][0]['meanMs'], 200)

    def test_only_http_window_samples_drive_peaks_and_targets(self):
        data = evidence()
        data[2][0]['app']['hikaricp_connections_pending'] = 1000
        result = summary(*data)
        self.assertEqual(result['samplesWithinHttpWindow'], 2)
        self.assertEqual(result['hikari']['pendingMax'], 8)
        self.assertEqual(result['hikari']['pendingSampleFraction'], 0.5)
        self.assertEqual(result['rowLocks']['observedTargets']['coupon/PRIMARY/X,REC_NOT_GAP']['samples'], 2)

    def test_missing_observations_and_sample_failures_are_incomplete(self):
        for change in ('samples', 'host', 'errors'):
            data = evidence()
            if change == 'samples':
                data[2] = []
            elif change == 'host':
                for sample in data[2]:
                    sample['host'] = {'processes': {}}
            else:
                data[3] = [{'type': 'TimeoutError'}]
            self.assertEqual(summary(*data)['status'], 'incomplete')

    def test_restart_and_counter_reset_are_not_valid_differences(self):
        data = evidence()
        data[1]['app']['process_start_time_seconds'] = 2
        with self.assertRaisesRegex(ValueError, 'restarted'):
            summary(*data)
        data = evidence()
        data[0]['mysql']['Innodb_row_lock_time'] = 999999
        with self.assertRaisesRegex(ValueError, 'reset counter'):
            summary(*data)


if __name__ == '__main__':
    unittest.main()
