"""Optional diagnostic probes, kept separate from headline capacity measurements."""
import http.client
import json
import socket
import threading
import time
from urllib.parse import urlsplit


class LatencyProbes:
    def __init__(self, origins, redis_port, directory):
        self.targets = [('redis', '127.0.0.1', int(redis_port))]
        for index, origin in enumerate(origins):
            parsed = urlsplit(origin)
            if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port:
                raise ValueError('Latency probes require a loopback application')
            self.targets.append(('app-' + str(index), parsed.hostname, parsed.port))
        self.directory = directory
        self.stop = threading.Event()
        self.threads = []

    def start(self):
        for target in self.targets:
            thread = threading.Thread(target=self.sample, args=target, daemon=True)
            self.threads.append(thread)
            thread.start()

    def close(self):
        self.stop.set()
        for thread in self.threads:
            thread.join(timeout=5)
            if thread.is_alive():
                raise RuntimeError('Latency probe did not stop')

    def sample(self, name, host, port):
        connection = None
        next_start = time.monotonic()
        with (self.directory / (name + '-latency-probe.ndjson')).open('w') as stream:
            try:
                while not self.stop.is_set():
                    started = time.monotonic()
                    row = {'timestampMs': time.time() * 1000,
                           'scheduleLagMs': max(0, started - next_start) * 1000}
                    try:
                        if name == 'redis':
                            if connection is None:
                                connection = socket.create_connection((host, port), timeout=2)
                                connection.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                            connection.sendall(b'*1\r\n$4\r\nPING\r\n')
                            response = b''
                            while len(response) < 7:
                                chunk = connection.recv(7 - len(response))
                                if not chunk:
                                    raise ConnectionError('Closed probe connection')
                                response += chunk
                            if response != b'+PONG\r\n':
                                raise ValueError('Unexpected Redis probe reply')
                        else:
                            if connection is None:
                                connection = http.client.HTTPConnection(host, port, timeout=2)
                            connection.request('GET', '/actuator/health/liveness')
                            response = connection.getresponse()
                            body = response.read(65537)
                            if response.status != 200 or len(body) > 65536 or json.loads(body).get('status') != 'UP':
                                raise ValueError('Unexpected liveness probe reply')
                        row['success'] = True
                    except Exception as error:
                        row.update(success=False, errorType=type(error).__name__)
                        if connection is not None:
                            connection.close()
                            connection = None
                    row['durationMs'] = (time.monotonic() - started) * 1000
                    stream.write(json.dumps(row) + '\n')
                    stream.flush()
                    # Do not catch up with a burst after a delayed probe.
                    next_start = time.monotonic() + .1
                    self.stop.wait(.1)
            finally:
                if connection is not None:
                    connection.close()
