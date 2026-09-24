"""Opt-in local observation. Only public target addresses and measurement numbers are exported."""
import http.server
import json
import math
import os
from pathlib import Path
import re
import threading
import time
import urllib.parse
import urllib.request


class ExperimentMonitoring:
    def __init__(self, root, run_id, command):
        if not re.fullmatch(r'[0-9]{8}T[0-9]{6}-[0-9a-f]{6}', run_id):
            raise ValueError('Invalid local experiment ID')
        self.run_id, self.command = run_id, command
        self.directory = Path(root) / 'monitoring/prometheus/targets'
        self.directory.mkdir(parents=True, exist_ok=True)
        self.paths = {role: self.directory / ('cache-experiment-' + run_id + '-' + role + '.json')
                      for role in ('apps', 'redis', 'runner')}
        if any(path.exists() for path in self.paths.values()):
            raise RuntimeError('Monitoring target files already exist for this run')
        self.exporter_id = None
        self.server = None
        self.thread = None
        self.lock = threading.Lock()
        self.phase = {'phase': 'starting', 'stage': 'preparing'}
        self.results = []

    def publish(self, role, groups):
        path = self.paths[role]
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(groups) + '\n')
        # Prometheus runs as a different UID inside its container. No secrets are stored here.
        temporary.chmod(0o644)
        os.replace(temporary, path)

    def start(self):
        monitor = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                if self.path != '/metrics':
                    self.send_error(404)
                    return
                body = monitor.render().encode()
                self.send_response(200)
                self.send_header('Content-Type', 'text/plain; version=0.0.4; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.publish('runner', [{'targets': ['host.docker.internal:' + str(self.server.server_port)],
            'labels': {'environment': 'cache-experiment', 'run_id': self.run_id,
                       'instance': 'runner-' + self.run_id}}])
        self.wait_for_targets('cache-experiment-runner', 1)
        print('Grafana: http://127.0.0.1:3001/d/airbob-cache-experiments?var-run_id=' + self.run_id
              + '&from=now-30m&to=now&refresh=5s', flush=True)

    def attach_redis(self, redis_id, exporter_port):
        # Reuse the exact locally installed exporter image; never pull or change existing exporters.
        image = json.loads(self.command(['docker', 'inspect', 'redis-exporter-cache']))[0]['Image']
        self.exporter_id = self.command(['docker', 'run', '--detach',
            '--name', 'airbob-cache-exporter-' + self.run_id.lower(),
            '--label', 'airbob.cache-experiment.run=' + self.run_id,
            '--network', 'container:' + redis_id,
            '--env', 'REDIS_ADDR=redis://127.0.0.1:6379',
            '--env', 'REDIS_EXPORTER_CONNECTION_TIMEOUT=1s', image])
        self.publish('redis', [{'targets': ['host.docker.internal:' + str(exporter_port)],
            'labels': {'environment': 'cache-experiment', 'namespace': 'cache-experiment',
                       'run_id': self.run_id, 'instance': 'redis-' + self.run_id}}])
        self.wait_for_targets('cache-experiment-redis', 1)

    def attach_apps(self, origins, group):
        variant, round_number = group.rsplit('-r', 1)
        groups = []
        for index, origin in enumerate(origins):
            parsed = urllib.parse.urlsplit(origin)
            if parsed.scheme != 'http' or parsed.hostname != '127.0.0.1' or not parsed.port:
                raise ValueError('Only owned loopback app targets are supported')
            groups.append({'targets': ['host.docker.internal:' + str(parsed.port)], 'labels': {
                'environment': 'cache-experiment', 'run_id': self.run_id, 'variant': variant,
                'round': round_number, 'instance': group + '-app-' + str(index) + '-' + self.run_id}})
        self.publish('apps', groups)
        self.wait_for_targets('cache-experiment-app', len(origins))

    def detach_apps(self):
        self.publish('apps', [])

    def wait_for_targets(self, job, count):
        end = time.monotonic() + 25
        while time.monotonic() < end:
            try:
                with urllib.request.urlopen('http://127.0.0.1:9091/api/v1/targets?state=active', timeout=2) as response:
                    targets = json.load(response)['data']['activeTargets']
                healthy = [target for target in targets if target.get('scrapePool') == job
                           and target.get('labels', {}).get('run_id') == self.run_id
                           and target.get('health') == 'up']
                if len(healthy) == count:
                    return
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.5)
        raise RuntimeError('Experiment metrics are not being scraped. Apply the local Prometheus configuration first; see cache README.')

    def set_phase(self, phase, stage):
        with self.lock:
            self.phase = {'phase': phase, 'stage': stage}

    def record(self, result):
        # Deliberately whitelist numeric evidence; fixtures, credentials and response bodies never enter metrics.
        labels = {key: str(result[key]) for key in ('label', 'scenario', 'variant', 'round', 'kind')}
        labels['phase'] = labels.pop('label')
        values = {'p95_ms': result['latencyMs']['p95'], 'requests': result['completed'],
                  'db_loads': result['server']['loads'], 'selects': result['server']['selects'],
                  'coalescing_ratio': result['server']['coalescingRatio'], 'error_ratio': result['errorRate'],
                  'dropped': result['dropped'], 'evidence_valid': int(not result['evidenceReasons'])}
        with self.lock:
            self.results.append((labels, values))
            self.phase = {'phase': result['label'], 'stage': 'recorded'}

    def render(self):
        with self.lock:
            phase, results = dict(self.phase), list(self.results)

        def labels(extra):
            return '{' + ','.join(key + '=' + json.dumps(str(value), ensure_ascii=False)
                                  for key, value in ({'run_id': self.run_id} | extra).items()) + '}'

        lines = ['# TYPE airbob_cache_experiment_phase gauge',
                 'airbob_cache_experiment_phase' + labels(phase) + ' 1']
        for tags, values in results:
            for metric, value in values.items():
                if isinstance(value, (int, float)) and math.isfinite(value):
                    lines.append('airbob_cache_experiment_' + metric + labels(tags) + ' ' + str(value))
        return '\n'.join(lines) + '\n'

    def settle(self):
        # The sub-second burst counters and exact k6 result must survive at least two 1s scrapes.
        # This pause is OUTSIDE the measured request window.
        time.sleep(3)

    def close(self):
        try:
            for path in self.paths.values():
                path.unlink(missing_ok=True)
            if self.server:
                self.server.shutdown()
                self.server.server_close()
                self.thread.join(timeout=2)
        finally:
            if self.exporter_id:
                info = json.loads(self.command(['docker', 'inspect', self.exporter_id]))[0]
                if info['Config'].get('Labels', {}).get('airbob.cache-experiment.run') != self.run_id:
                    raise RuntimeError('Exporter ownership changed; refusing to remove it')
                self.command(['docker', 'rm', '-f', '-v', self.exporter_id])
