"""Monitoring discovery, exact result export and cleanup; no AWS or live Docker required."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
import urllib.error
import urllib.request

HERE = Path(__file__).resolve().parents[1] / 'cache'
spec = importlib.util.spec_from_file_location('experiment_monitoring', HERE / 'cache_experiment_monitoring.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MonitoringTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.monitor = module.ExperimentMonitoring(self.temporary.name, '20260924T010812-f0aee7', Mock())
        self.monitor.wait_for_targets = Mock()
        self.addCleanup(self.monitor.close)

    def test_random_app_ports_have_run_variant_and_instance_labels(self):
        self.monitor.attach_apps(['http://127.0.0.1:18001', 'http://127.0.0.1:18002'], 'cache-on-r2')
        groups = json.loads(self.monitor.paths['apps'].read_text())
        self.assertEqual([g['targets'] for g in groups], [['host.docker.internal:18001'], ['host.docker.internal:18002']])
        self.assertEqual(groups[0]['labels']['variant'], 'cache-on')
        self.assertEqual(groups[0]['labels']['round'], '2')
        self.assertEqual(groups[0]['labels']['environment'], 'cache-experiment')
        self.assertNotEqual(groups[0]['labels']['instance'], groups[1]['labels']['instance'])
        self.monitor.detach_apps()
        self.assertEqual(json.loads(self.monitor.paths['apps'].read_text()), [])

    def test_non_loopback_apps_are_rejected(self):
        for origin in ['http://example.com:8080', 'https://127.0.0.1:8080']:
            with self.assertRaises(ValueError):
                self.monitor.attach_apps([origin], 'cache-on-r1')

    def test_exported_results_match_evidence_and_do_not_include_secrets(self):
        self.monitor.record({'label': 'cache-on-r1-miss', 'scenario': 'miss', 'variant': 'cache-on',
            'round': 1, 'kind': 'burst', 'completed': 60, 'dropped': 0, 'errorRate': 0,
            'latencyMs': {'p95': 432.19}, 'server': {'loads': 1, 'selects': 3, 'coalescingRatio': 0},
            'evidenceReasons': [], 'token': 'private-value', 'fixture': 'private-response'})
        text = self.monitor.render()
        self.assertIn('airbob_cache_experiment_p95_ms', text)
        self.assertIn(' 432.19\n', text)
        self.assertIn('airbob_cache_experiment_db_loads', text)
        self.assertIn('airbob_cache_experiment_evidence_valid', text)
        self.assertNotIn('private-', text)
        self.assertIn('stage="recorded"', text)

    def test_http_exporter_only_serves_metrics_and_cleanup_preserves_other_runs(self):
        other = self.monitor.directory / 'cache-experiment-another-run-apps.json'
        other.write_text('[]')
        self.monitor.start()
        origin = 'http://127.0.0.1:' + str(self.monitor.server.server_port)
        with urllib.request.urlopen(origin + '/metrics') as response:
            self.assertIn('airbob_cache_experiment_phase', response.read().decode())
        with self.assertRaises(urllib.error.HTTPError) as failure:
            urllib.request.urlopen(origin + '/config')
        self.assertEqual(failure.exception.code, 404)
        failure.exception.close()
        self.monitor.close()
        self.assertTrue(other.exists())
        self.assertTrue(all(not p.exists() for p in self.monitor.paths.values()))

    def test_exporter_replacement_is_never_removed(self):
        self.monitor.exporter_id = 'replaced-exporter'
        self.monitor.command.return_value = json.dumps([{'Config': {'Labels': {'airbob.cache-experiment.run': 'other'}}}])
        with self.assertRaisesRegex(RuntimeError, 'ownership'):
            self.monitor.close()
        self.assertEqual(self.monitor.command.call_count, 1)
        self.monitor.exporter_id = None

    def test_instance_discovery_contains_no_benchmark_token(self):
        self.monitor.attach_apps(['http://127.0.0.1:18001'], 'coalescing-on-r1')
        self.assertNotIn('token', self.monitor.paths['apps'].read_text().lower())


if __name__ == '__main__':
    unittest.main()
