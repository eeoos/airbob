import copy
import importlib.util
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parents[1] / 'cache'
SPEC = importlib.util.spec_from_file_location('cache_capacity_diagnostics', HERE / 'cache_capacity_diagnostics.py')
diagnostics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostics)
SPEC = importlib.util.spec_from_file_location('cache_local', HERE / 'run-local-experiments.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


def evidence():
    app = {'process_start_time_seconds': 1, 'hikaricp_connections_pending': 0,
           'hikaricp_connections_active': 2, 'hikaricp_connections_max': 10,
           'hikaricp_connections_timeout_total': 0, 'jvm_gc_pause_seconds_sum': 0}
    for name in ['acquire', 'usage']:
        app['hikaricp_connections_' + name + '_seconds_count'] = 0
        app['hikaricp_connections_' + name + '_seconds_sum'] = 0
    status = {key: 0 for key in ['Com_select', 'Innodb_buffer_pool_reads', 'Innodb_buffer_pool_read_requests',
                                 'Innodb_row_lock_waits', 'Innodb_row_lock_time', 'Threads_running']}
    before = {'timestampMs': 0, 'apps': [app], 'mysql': {'status': status, 'statements': []},
              'containers': {name: {'cpuNs': 0, 'memoryBytes': 100, 'networkRxBytes': 0, 'networkTxBytes': 0}
                             for name in ['mysql', 'redis']}}
    after = copy.deepcopy(before)
    after['timestampMs'] = 10000
    after['apps'][0].update(hikaricp_connections_acquire_seconds_count=100,
                            hikaricp_connections_acquire_seconds_sum=2,
                            hikaricp_connections_usage_seconds_count=100,
                            hikaricp_connections_usage_seconds_sum=4,
                            jvm_gc_pause_seconds_sum=.1)
    after['containers']['mysql']['cpuNs'] = 5_000_000_000
    after['mysql']['statements'] = [{'digest': 'abc', 'template': 'SELECT ?', 'count': 100,
                                     'timePs': 200_000_000_000, 'rows': 500}]
    samples = []
    for timestamp in [2000, 4000]:
        sample = copy.deepcopy(before)
        sample['timestampMs'] = timestamp
        sample['apps'][0]['hikaricp_connections_pending'] = 3
        sample['host'] = {'cpuPercent': 40, 'memoryFreePercent': 70,
                          'processes': {'k6': {'cpuPercent': 30, 'rssBytes': 200}}}
        samples.append(sample)
    return before, after, samples, [], {'startedEpochMs': 1000, 'endedEpochMs': 5000}


class CapacityDiagnosticsTest(unittest.TestCase):
    def test_macos_swap_counters_keep_page_units(self):
        parsed = diagnostics.virtual_memory('Mach Virtual Memory Statistics: (page size of 16384 bytes)\n'
                                            'Swapins: 12.\nSwapouts: 9.\nPageouts: 7.\n')
        self.assertEqual(parsed, {'pageSizeBytes': 16384, 'Swapins': 12, 'Swapouts': 9, 'Pageouts': 7})
        with self.assertRaises(ValueError):
            diagnostics.virtual_memory('no samples')

    def test_counter_units_and_http_window_are_distinct(self):
        report = diagnostics.summarize(*evidence())
        self.assertEqual(report['status'], 'complete')
        self.assertTrue(report['generatorHealthy'])
        self.assertEqual(report['apps'][0]['hikari']['acquire']['meanMs'], 20)
        self.assertEqual(report['apps'][0]['hikari']['pendingMax'], 3)
        self.assertEqual(report['containers']['mysql']['averageCpuCores'], .5)
        self.assertEqual(report['sql'][0]['meanMs'], 2)
        self.assertEqual(report['sql'][0]['rowsExamined'], 500)

    def test_outside_window_peaks_do_not_change_diagnosis(self):
        args = evidence()
        outside = copy.deepcopy(args[2][0])
        outside['timestampMs'] = 9000
        outside['host']['cpuPercent'] = 100
        outside['apps'][0]['hikaricp_connections_pending'] = 99
        args[2].append(outside)
        report = diagnostics.summarize(*args)
        self.assertEqual(report['apps'][0]['hikari']['pendingMax'], 3)
        self.assertTrue(report['generatorHealthy'])

    def test_missing_samples_and_collection_errors_are_incomplete(self):
        args = list(evidence())
        args[2] = []
        self.assertFalse(diagnostics.summarize(*args)['generatorHealthy'])
        args = list(evidence())
        args[3] = [{'type': 'TimeoutError'}]
        self.assertEqual(diagnostics.summarize(*args)['status'], 'incomplete')

    def test_shared_host_pressure_is_not_a_server_ceiling(self):
        args = evidence()
        args[2][0]['host']['cpuPercent'] = 95
        result = diagnostics.summarize(*args)
        self.assertIn('shared-host-cpu-pressure', result['generatorReasons'])
        bounds = runner.capacity_bound([{'configuredRate': 1000, 'sloPassed': False,
                                         'evidenceReasons': [], 'dropped': 0,
                                         'capacityObservationReasons': result['generatorReasons']}])
        self.assertEqual(bounds['status'], 'inconclusive')

    def test_competing_load_is_invalid_even_when_host_cpu_is_low(self):
        args = evidence()
        args[2][0]['host']['otherLoadGeneratorPids'] = [12345]
        report = diagnostics.summarize(*args)
        self.assertIn('competing-load-generator', report['generatorReasons'])
        self.assertFalse(report['generatorHealthy'])

    def test_restarts_and_counter_resets_are_rejected(self):
        for field, value in [('process_start_time_seconds', 2), ('hikaricp_connections_timeout_total', -1)]:
            args = evidence()
            args[1]['apps'][0][field] = value
            with self.assertRaises(ValueError):
                diagnostics.summarize(*args)

    def test_unused_pool_has_no_fabricated_acquisition_latency(self):
        args = evidence()
        args[1]['apps'][0]['hikaricp_connections_acquire_seconds_count'] = 0
        args[1]['apps'][0]['hikaricp_connections_acquire_seconds_sum'] = 0
        self.assertIsNone(diagnostics.summarize(*args)['apps'][0]['hikari']['acquire']['meanMs'])


if __name__ == '__main__':
    unittest.main()
