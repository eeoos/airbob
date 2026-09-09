#!/usr/bin/env python3
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_app_read as app
spec = importlib.util.spec_from_file_location('probe_reads', SCRIPTS / 'probe-growth-reads.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class ReadProtocolTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.release = Path(self.directory.name)
        self.requests = []
        self.reply = {'data': {'items': [{'id': 2}, {'id': 1}], 'number': 1.0, 'text': '한글'}}
        self.status = 200
        self.redirect_login = False
        owner = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                owner.requests.append(('login', self.headers.get('Cookie')))
                self.send_response(302 if owner.redirect_login else 200)
                self.send_header('Set-Cookie', 'SESSION_ID=synthetic; Path=/')
                self.send_header('Location', '/api/redirected')
                self.end_headers()
                self.wfile.write(b'{}')

            def do_GET(self):
                owner.requests.append((self.path, self.headers.get('Cookie')))
                self.send_response(owner.status)
                self.end_headers()
                self.wfile.write(json.dumps(owner.reply).encode())

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)
        self.target = {'id': 'sealed', 'memberId': 2002,
            'account': {'memberId': 2002, 'email': 'growth-boundary-guest-0@example.test'},
            'path': '/api/read', 'expectedStatus': 200, 'expectedResponseSha256': probe.digest(self.reply),
            'arrayField': 'items', 'idField': 'id', 'expectedIds': [2, 1]}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.directory.cleanup()

    def run_probe(self, targets):
        (self.release / 'read-scenarios.json').write_text(json.dumps({'targets': targets}))
        # The existing full release validator has its own real-artifact tests;
        # exercise HTTP, cookie isolation, and failure handling independently.
        validator = SimpleNamespace(validate=lambda *args: None)
        loader = SimpleNamespace(exec_module=lambda module: None)
        with patch.object(probe.importlib.util, 'spec_from_file_location', return_value=SimpleNamespace(loader=loader)), \
                patch.object(probe.importlib.util, 'module_from_spec', return_value=validator):
            return probe.probe(self.release, 'korea-growth-v3-64deb4f838935b8e', self.base, 'synthetic-password-for-test')

    def test_sealed_unicode_numbers_ids_and_anonymous_cookie_isolation(self):
        anonymous = dict(self.target, id='anonymous', memberId=None, account=None)
        result = self.run_probe([self.target, anonymous, self.target])
        self.assertTrue(result['passed'])
        self.assertEqual(self.requests, [('login', None), ('/api/read', 'SESSION_ID=synthetic'),
            ('/api/read', None), ('/api/read', 'SESSION_ID=synthetic')])

    def test_response_hash_change_fails_even_with_same_ids(self):
        self.reply['data']['text'] = 'changed'
        self.assertFalse(self.run_probe([self.target])['passed'])

    def test_wrong_order_fails_even_when_response_hash_matches(self):
        self.target['expectedIds'] = [1, 2]
        self.assertFalse(self.run_probe([self.target])['passed'])

    def test_wrong_http_status_fails(self):
        self.status = 403
        self.assertFalse(self.run_probe([self.target])['passed'])

    def test_login_redirect_is_never_followed(self):
        self.redirect_login = True
        with self.assertRaises(RuntimeError):
            self.run_probe([self.target])
        self.assertEqual(self.requests, [('login', None)])

    def test_foreign_url_and_non_synthetic_account_rejected(self):
        for path in ['https://example.invalid/api/read', '//example.invalid/api/read', '/api/\\example']:
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.run_probe([dict(self.target, path=path, memberId=None, account=None)])
        forged = copy.deepcopy(self.target)
        forged['account']['email'] = 'person@example.com'
        with self.assertRaises(ValueError):
            self.run_probe([forged])
        self.assertEqual(self.requests, [])


class AppBoundaryTest(unittest.TestCase):
    def test_source_and_repository_identity_are_required(self):
        valid = {'AIRBOB_QUALIFICATION_ONLY': 'true', 'AIRBOB_DATABASE_BOOTSTRAP': 'dump',
            'AIRBOB_GROWTH_APP_IMAGE': app.REGISTRY + '/airbob-repo@sha256:' + 'a' * 64,
            'AIRBOB_GROWTH_APP_COMMIT': 'b' * 40, 'AIRBOB_GROWTH_APP_JAR_SHA256': 'c' * 64}
        app.validate_identity(valid)
        for key, bad in [('AIRBOB_QUALIFICATION_ONLY', 'false'), ('AIRBOB_DATABASE_BOOTSTRAP', 'snapshot'),
            ('AIRBOB_GROWTH_APP_IMAGE', 'docker.io/unknown:latest'), ('AIRBOB_GROWTH_APP_COMMIT', 'main'),
            ('AIRBOB_GROWTH_APP_JAR_SHA256', '')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                app.validate_identity(dict(valid, **{key: bad}))

    def test_app_connection_preserves_tls_and_keeps_writers_disabled(self):
        settings = app.app_settings({'host': 'fixture.rds.amazonaws.com', 'username': 'fixture',
            'password': 'synthetic', 'truststore': Path('/public/ca.p12')}, True)
        self.assertIn('sslMode=VERIFY_IDENTITY', settings['spring.datasource.url'])
        self.assertNotEqual(settings['spring.data.redis.host'], settings['accommodation.detail-cache.redis.host'])
        self.assertEqual(settings['server.address'], '127.0.0.1')
        for key in ['reservation.inventory.startup.enabled', 'reservation.inventory.seed.enabled',
            'reservation.inventory.retention.enabled', 'spring.kafka.listener.auto-startup',
            'accommodation.indexing.kafka.auto-startup', 'accommodation.detail-cache.invalidation.kafka.auto-startup',
            'operator-alert.kafka.auto-startup', 'operator-alert.slack.enabled', 'payment.toss.enabled',
            'google.api.enabled', 'cloud.aws.s3.write-enabled']:
            self.assertFalse(settings[key], key)


if __name__ == '__main__':
    unittest.main()
