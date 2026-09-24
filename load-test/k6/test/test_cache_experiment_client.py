"""Exercise the real common k6 client against loopback only; AWS mode is inspected without traffic."""
import http.server
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'load-test/k6/cache/accommodation-detail-experiment.js'


@unittest.skipUnless(shutil.which('k6'), 'k6 is required for request-generator integration tests')
class K6ClientTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.data = {'id': 1, 'name': 'Local fixture', 'is_in_wishlist': False,
                     'amenities': [{'type': 'wifi', 'count': 1}], 'images': []}
        fixture = {'schemaVersion': 1, 'datasetId': 'offline-client-test',
                   'accommodations': [{'id': 1, 'data': self.data}]}
        (self.directory / 'fixture.json').write_text(json.dumps(fixture))
        self.env = {k: os.environ[k] for k in ('PATH', 'HOME', 'TMPDIR') if k in os.environ}
        self.env.update(CACHE_BENCHMARK_FIXTURE=str(self.directory / 'fixture.json'),
                        RESULT_PATH=str(self.directory / 'result.json'), BENCHMARK_READ_MODEL_TOKEN='offline-test-token',
                        K6_NO_USAGE_REPORT='true', RATE='2', SECONDS='1', VUS='2', BURST='2',
                        DISTRIBUTION='same-key', EXPERIMENT_KIND='rate')

    def test_rate_and_burst_use_same_payload_checks_and_emit_expected_metrics(self):
        data = self.data
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != '/api/v1/accommodations/1' or self.headers.get('X-Benchmark-Token') != 'offline-test-token':
                    self.send_error(403); return
                body = json.dumps({'success': True, 'data': data}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(body)))
                self.end_headers(); self.wfile.write(body)
            def log_message(self, *args): pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            for kind in ('rate', 'burst'):
                env = self.env | {'EXPERIMENT_KIND': kind,
                                  'EXPERIMENT_ORIGINS': json.dumps(['http://127.0.0.1:' + str(server.server_port)])}
                result = subprocess.run(['k6', 'run', '--quiet', str(SCRIPT)], env=env, capture_output=True, text=True, timeout=15)
                self.assertEqual(result.returncode, 0, result.stderr)
                evidence = json.loads((self.directory / 'result.json').read_text())
                self.assertEqual(evidence['errorRate'], 0)
                self.assertEqual(evidence['dropped'], 0)
                self.assertGreaterEqual(evidence['completed'], 2)
                self.assertEqual(evidence['environment'], 'local')
                self.assertGreaterEqual(evidence['endedEpochMs'], evidence['startedEpochMs'])
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=3)

    def test_aws_options_keep_tls_hostname_and_support_five_minute_confirmation_without_http(self):
        env = self.env | {'EXPERIMENT_ENVIRONMENT': 'aws', 'EXPERIMENT_ORIGINS': '["https://api.airbob.cloud"]',
                          'EXPERIMENT_HOSTS': '{"api.airbob.cloud":"10.1.2.3"}',
                          'SECONDS': '300', 'RATE': '5000', 'VUS': '2000', 'APP_INSTANCE_COUNT': '2'}
        result = subprocess.run(['k6', 'inspect', '--include-system-env-vars', str(SCRIPT)], env=env, capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stderr)
        options = json.loads(result.stdout)
        self.assertEqual(options['hosts'], {'api.airbob.cloud': '10.1.2.3'})
        self.assertEqual(options['scenarios']['measure']['duration'], '5m0s')
        self.assertEqual(options['scenarios']['measure']['rate'], 5000)
        self.assertFalse(options.get('insecureSkipTLSVerify', False))


if __name__ == '__main__':
    unittest.main()
