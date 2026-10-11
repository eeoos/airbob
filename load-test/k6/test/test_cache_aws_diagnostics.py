"""Synthetic metrics and fake SSM only; never creates or reads AWS resources."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parents[1] / 'cache'
sys.path.insert(0, str(HERE))
import aws_cache_diagnostics as diagnosis
import aws_cache_contract as contract

spec = importlib.util.spec_from_file_location('diagnostic_runner', HERE / 'run-aws-experiments.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def snapshot(timestamp=1000, count=10, seconds=.1, pending=0):
    return {'containerId': 'owned-app', 'sampledAtEpochMs': timestamp, 'prometheus': '',
            'diagnosticMetrics': {'process_start_time_seconds': 42,
                                 'hikaricp_connections_active': 10,
                                 'hikaricp_connections_pending': pending,
                                 'hikaricp_connections_max': 10,
                                 'hikaricp_connections_timeout_total': 0,
                                 'hikaricp_connections_acquire_seconds_count': count,
                                 'hikaricp_connections_acquire_seconds_sum': seconds}}


class DiagnosticTest(unittest.TestCase):
    def summarize(self, before=None, after=None, samples=None, errors=None):
        return diagnosis.summarize([before or snapshot()], [after or snapshot(5000, 30, .3)],
                                   samples if samples is not None else [
                                       {'apps': [snapshot(2000, pending=3)]},
                                       {'apps': [snapshot(3000, pending=7)]}], errors or [],
                                   {'startedEpochMs': 1500, 'endedEpochMs': 4500})

    def test_only_finite_allowed_metrics_survive_without_labels(self):
        raw = '\n'.join([
            'hikaricp_connections_pending{pool="one"} 3',
            'hikaricp_connections_pending{pool="two"} 2',
            'process_start_time_seconds 42',
            'process_cpu_usage NaN',
            'unrelated_value{email="never-export"} 1',
            'jvm_gc_pause_seconds_bucket{le="0.1"} 40'])
        result = diagnosis.metric_values(raw)
        self.assertEqual(result, {'hikaricp_connections_pending': 5, 'process_start_time_seconds': 42})
        self.assertNotIn('never-export', json.dumps(result))

    def test_uses_request_window_for_gauges_and_counter_delta_for_timers(self):
        samples = [{'apps': [snapshot(1400, pending=99)]},
                   {'apps': [snapshot(2000, pending=3)]}, {'apps': [snapshot(3000, pending=7)]}]
        result = self.summarize(samples=samples)
        self.assertEqual(result['status'], 'complete')
        app = result['apps'][0]
        self.assertEqual(app['gauges']['hikaricp_connections_pending'], {'min': 3, 'max': 7})
        self.assertEqual(app['samplesInRequestWindow'], 2)
        self.assertAlmostEqual(app['timers']['hikaricp_connections_acquire_seconds']['meanMs'], 10)
        self.assertIsNone(app['gauges']['tomcat_threads_busy_threads'])
        self.assertEqual(app['counters']['hikaricp_connections_timeout_total'], 0)

    def test_zero_timer_count_does_not_invent_zero_latency(self):
        result = self.summarize(after=snapshot(5000))
        self.assertIsNone(result['apps'][0]['timers']['hikaricp_connections_acquire_seconds']['meanMs'])

    def test_pause_and_connection_maxima_are_not_summed_across_causes_or_pools(self):
        raw = '\n'.join([
            'jvm_gc_pause_seconds_max{cause="one"} 0.2',
            'jvm_gc_pause_seconds_max{cause="two"} 0.7',
            'hikaricp_connections_acquire_seconds_max{pool="one"} 1.2',
            'hikaricp_connections_acquire_seconds_max{pool="two"} 0.4',
            'jvm_gc_pause_seconds_count{cause="one"} 2',
            'jvm_gc_pause_seconds_count{cause="two"} 3'])
        metrics = diagnosis.metric_values(raw)
        self.assertEqual(metrics['jvm_gc_pause_seconds_max'], .7)
        self.assertEqual(metrics['hikaricp_connections_acquire_seconds_max'], 1.2)
        self.assertEqual(metrics['jvm_gc_pause_seconds_count'], 5)

    def test_counter_reset_missing_metric_and_restart_invalidate_observation(self):
        for change in [lambda r: r['diagnosticMetrics'].update(hikaricp_connections_acquire_seconds_count=1),
                       lambda r: r['diagnosticMetrics'].pop('hikaricp_connections_pending'),
                       lambda r: r['diagnosticMetrics'].update(process_start_time_seconds=43),
                       lambda r: r.update(containerId='replacement')]:
            with self.subTest(change=change):
                after = snapshot(5000, 30, .3); change(after)
                self.assertEqual(self.summarize(after=after)['status'], 'incomplete')

    def test_incomplete_or_failed_sampling_never_becomes_complete_evidence(self):
        self.assertEqual(self.summarize(samples=[])['status'], 'incomplete')
        self.assertEqual(self.summarize(errors=['RuntimeError'])['status'], 'incomplete')
        self.assertEqual(self.summarize(samples=[{'apps': []}])['status'], 'incomplete')

    def test_sampler_reads_each_host_stops_and_preserves_read_failures(self):
        finished = threading.Event()
        transport = Mock()
        def read(instance, action, **kwargs):
            self.assertEqual(action, 'snapshot')
            if instance == 'bad':
                raise RuntimeError('sensitive remote details must not be recorded')
            finished.set()
            return snapshot()
        transport.action.side_effect = read
        with tempfile.TemporaryDirectory() as tmp:
            sampler = diagnosis.AppSampler(transport, ['bad', 'good'], Path(tmp) / 'samples.ndjson')
            with sampler:
                self.assertTrue(finished.wait(2))
            self.assertFalse(sampler.thread.is_alive())
            self.assertEqual(sampler.errors, ['RuntimeError'])
            self.assertEqual(sampler.samples, [])


class WarmupTest(unittest.TestCase):
    def config(self):
        return json.loads((HERE / 'aws-experiment.example.json').read_text())

    def test_optional_warmup_rate_accepts_old_configs_and_rejects_invalid_values(self):
        c = self.config(); c['capacity'].pop('warmupRps')
        contract.validate(c)
        for rate in [0, -1, True, 1.5, 20001]:
            c['capacity']['warmupRps'] = rate
            with self.subTest(rate=rate), self.assertRaisesRegex(RuntimeError, 'warmupRps'):
                contract.validate(c)

    def test_conditioning_is_bounded_and_only_applies_to_capacity(self):
        c = self.config(); c['scenarios'] = ['capacity']
        c['capacity']['conditioningStages'] = [{'rps': 100, 'seconds': 30}, {'rps': 400, 'seconds': 60}]
        contract.validate(c)
        for stages in [[], [{'rps': True, 'seconds': 30}], [{'rps': 100, 'seconds': 121}],
                       [{'rps': 20001, 'seconds': 30}], [{'rps': 100, 'seconds': 30, 'extra': 1}],
                       [{'rps': 100, 'seconds': 30}, {'rps': 100, 'seconds': 30}],
                       [{'rps': r, 'seconds': 120} for r in [100, 200, 400]]]:
            c['capacity']['conditioningStages'] = stages
            with self.subTest(stages=stages), self.assertRaises(RuntimeError):
                contract.validate(c)
        c['capacity']['conditioningStages'] = [{'rps': 100, 'seconds': 30}]
        c['scenarios'] = ['latency']
        with self.assertRaises(RuntimeError): contract.validate(c)

    def test_conditioning_runs_once_after_each_variant_before_candidate_warmups(self):
        c = self.config(); c['scenarios'] = ['capacity']
        c['capacity']['conditioningStages'] = [{'rps': 100, 'seconds': 30}, {'rps': 400, 'seconds': 60}]
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(c, tmp, tmp, Mock()); events = []
            run.variant = lambda variant: events.append(('variant', variant))
            run.warm = lambda *args: events.append(('warm', *args))
            run.measure = Mock(); run.save = Mock()
            def search(config, observe):
                observe(800, 60, 'probe', 1); observe(1000, 60, 'probe', 1)
                return {'status': 'fixture'}
            with patch.object(runner, 'capacity_search', side_effect=search): run.measurements()
            for start, variant in [(0, 'cache-off'), (5, 'cache-on')]:
                self.assertEqual(events[start:start + 3], [
                    ('variant', variant), ('warm', variant + '-conditioning-1', 100, 30),
                    ('warm', variant + '-conditioning-2', 400, 60)])
            self.assertEqual(len(events), 10)

    def test_failed_warmup_evidence_is_saved_before_aborting(self):
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(self.config(), tmp, tmp, Mock()); run.transport = Mock()
            result = {'errorRate': 0, 'dropped': 1}
            run.generator = Mock(side_effect=[{}, result])
            with self.assertRaisesRegex(RuntimeError, 'Warm-up failed'): run.warm('failed', 400, 60)
            self.assertEqual(json.loads((Path(tmp) / 'failed-warmup.json').read_text()), result)
            self.assertEqual(run.generator.call_args.args[0]['seconds'], 60)

    def test_capacity_warmup_uses_configured_rate_bounded_by_candidate(self):
        c = self.config(); c['scenarios'] = ['capacity']; c['capacity']['warmupRps'] = 200
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(c, tmp, tmp, Mock())
            run.variant = Mock(); run.warm = Mock(); run.measure = Mock(); run.save = Mock()
            def search(config, observe):
                observe(100, 60, 'probe', 1); observe(400, 60, 'probe', 1)
                return {'status': 'fixture'}
            with patch.object(runner, 'capacity_search', side_effect=search):
                run.measurements()
            self.assertEqual([call.args[1] for call in run.warm.call_args_list], [100, 200, 100, 200])

    def test_outage_keeps_its_existing_warmup_path(self):
        c = self.config(); c['scenarios'] = ['outage']; c['rounds'] = 1
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(c, tmp, tmp, Mock())
            run.variant = Mock(); run.warm = Mock(); run.measure = Mock(); run.generator = Mock()
            run.transport = Mock()
            run.measurements()
            self.assertEqual(len(run.warm.call_args_list), 2)
            self.assertTrue(all(len(call.args) == 1 for call in run.warm.call_args_list))

    def test_one_variant_can_finish_within_its_own_lease_without_changing_other_scenarios(self):
        c = self.config() | {'scenarios': ['capacity'], 'capacityVariants': ['cache-on'], 'responseCheck': 'body'}
        contract.validate(c)
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(c, tmp, tmp, Mock())
            run.variant = Mock(); run.save = Mock()
            with patch.object(runner, 'capacity_search', return_value={'status': 'fixture'}):
                run.measurements()
            run.variant.assert_called_once_with('cache-on')
        for change in [{'capacityVariants': []}, {'capacityVariants': ['cache-off', 'cache-off']},
                       {'capacityVariants': ['coalescing-on']}, {'responseCheck': 'status-only'}]:
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                contract.validate(c | change)


if __name__ == '__main__':
    unittest.main()
