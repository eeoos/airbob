import http.server
import importlib.util
import json
from pathlib import Path
import socketserver
import tempfile
import threading
import time
import unittest

SOURCE = Path(__file__).resolve().parents[1] / 'cache/cache_capacity_probes.py'
SPEC = importlib.util.spec_from_file_location('cache_capacity_probes', SOURCE)
probes = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probes)


class Liveness(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def do_GET(self):
        self.server.requests += 1
        status = 503 if self.server.requests == 1 else 200
        body = b'{"status":"UP"}'
        self.send_response(status)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class RedisPing(socketserver.StreamRequestHandler):
    def handle(self):
        while True:
            request = self.rfile.read(14)
            if not request:
                break
            if request != b'*1\r\n$4\r\nPING\r\n':
                return
            self.wfile.write(b'+PO')
            self.wfile.flush()
            time.sleep(.002)
            self.wfile.write(b'NG\r\n')
            self.wfile.flush()


class CapacityProbeTest(unittest.TestCase):
    def test_remote_application_is_rejected_before_any_probe_starts(self):
        with self.assertRaises(ValueError):
            probes.LatencyProbes(['http://example.com:80'], 1234, Path('.'))

    def test_fragmented_redis_replies_http_failure_recovery_and_cleanup(self):
        with http.server.ThreadingHTTPServer(('127.0.0.1', 0), Liveness) as app, \
                socketserver.ThreadingTCPServer(('127.0.0.1', 0), RedisPing) as redis, \
                tempfile.TemporaryDirectory() as directory:
            app.requests = 0
            redis.daemon_threads = True
            workers = [threading.Thread(target=s.serve_forever, daemon=True) for s in [app, redis]]
            for thread in workers:
                thread.start()
            observer = probes.LatencyProbes(['http://127.0.0.1:' + str(app.server_port)],
                                            redis.server_address[1], Path(directory))
            try:
                observer.start()
                time.sleep(.5)
            finally:
                observer.close()
                app.shutdown()
                redis.shutdown()
                for thread in workers:
                    thread.join(timeout=2)
            self.assertFalse(any(t.is_alive() for t in observer.threads))
            for name in ['redis', 'app-0']:
                rows = [json.loads(line) for line in (Path(directory) / (name + '-latency-probe.ndjson')).read_text().splitlines()]
                self.assertGreaterEqual(sum(row['success'] for row in rows), 2)
                self.assertTrue(all(row['durationMs'] >= 0 and row['scheduleLagMs'] >= 0 for row in rows))
                if name == 'app-0':
                    self.assertFalse(rows[0]['success'])


if __name__ == '__main__':
    unittest.main()
