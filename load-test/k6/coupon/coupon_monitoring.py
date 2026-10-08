"""App SQL deltas, local generator pressure, and RDS CloudWatch CPU evidence."""
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import re
import subprocess
import threading
import time
import urllib.request

from coupon_experiment import require


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        raise ValueError('Unexpected HTTP redirect')


HTTP = urllib.request.build_opener(NoRedirect)


def fetch(url):
    with HTTP.open(url, timeout=10) as response:
        return response.read(8 * 1024 * 1024).decode()


def metrics(raw):
    result = []
    for line in raw.splitlines():
        if line.startswith('#') or not line.strip():
            continue
        match = re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(.*)\})?\s+([^\s]+)(?:\s+\S+)?', line)
        if not match:
            continue
        name, label_text, value = match.groups()
        if not (name.startswith('app_query_per_request_') or name == 'process_start_time_seconds'):
            continue
        labels = {k: json.loads('"' + v + '"') for k, v in
                  re.findall(r'(\w+)="((?:[^"\\]|\\.)*)"', label_text or '')}
        number = float(value)
        require(math.isfinite(number), 'Non-finite app metric')
        result.append((name, labels, number, line))
    return result


def snapshot(urls, path):
    output = []
    for url in urls:
        selected = [(name, labels, value, line) for name, labels, value, line in metrics(fetch(url))
                    if name == 'process_start_time_seconds'
                    or labels.get('path') == path and labels.get('http_method') == 'POST']
        start = [value for name, labels, value, line in selected if name == 'process_start_time_seconds']
        require(len(start) == 1, 'Missing process identity metric')
        def total(suffix):
            return sum(value for name, labels, value, line in selected
                       if name == 'app_query_per_request_queries_' + suffix and labels.get('query_type') == 'TOTAL')
        output.append({'endpoint': url, 'processStartTime': start[0], 'requests': total('count'),
                       'queries': total('sum'), 'prometheus': '\n'.join(row[3] for row in selected) + '\n'})
    return output


def delta(before, after):
    require(len(before) == len(after) > 0, 'Missing app snapshots')
    instances = []
    for a, b in zip(before, after):
        require(a['endpoint'] == b['endpoint'] and a['processStartTime'] == b['processStartTime'],
                'App restarted or target changed during measurement')
        requests, queries = b['requests'] - a['requests'], b['queries'] - a['queries']
        require(requests >= 0 and queries >= 0 and int(requests) == requests and int(queries) == queries,
                'Invalid or reset SQL counters')
        instances.append({'endpoint': a['endpoint'], 'requests': int(requests), 'queries': int(queries)})
    return {'requests': sum(r['requests'] for r in instances), 'queries': sum(r['queries'] for r in instances),
            'instances': instances, 'scope': 'Hibernate statements on the measured issue route; not all DB work'}


class GeneratorMonitor:
    def __init__(self):
        self.samples = []
        self.stop_event = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True)

    @staticmethod
    def read():
        numbers = [int(v) for v in Path('/proc/stat').read_text().splitlines()[0].split()[1:9]]
        memory = {line.split(':')[0]: int(line.split()[1]) for line in Path('/proc/meminfo').read_text().splitlines()}
        return sum(numbers), numbers[3] + numbers[4], memory['MemAvailable'] / memory['MemTotal'] * 100

    def sample(self):
        try:
            previous = self.read()
            while not self.stop_event.wait(1):
                current = self.read()
                elapsed = current[0] - previous[0]
                if elapsed > 0:
                    self.samples.append({'cpuPercent': 100 * (1 - (current[1] - previous[1]) / elapsed),
                                         'availableMemoryPercent': current[2]})
                previous = current
        except (OSError, KeyError, ValueError):
            return

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.stop_event.set()
        self.thread.join(timeout=3)

    def result(self):
        if not self.samples:
            return {'healthy': False, 'status': 'missing-linux-host-observations', 'samples': []}
        maximum = max(s['cpuPercent'] for s in self.samples)
        available = min(s['availableMemoryPercent'] for s in self.samples)
        return {'healthy': maximum < 90 and available >= 15, 'maxCpuPercent': maximum,
                'minAvailableMemoryPercent': available, 'samples': self.samples}


def cpu_window(start_ms, end_ms):
    # Use only complete minute buckets inside the actual HTTP measurement window.
    begin = math.ceil(start_ms / 60000) * 60
    end = math.floor(end_ms / 60000) * 60
    require(end > begin, 'Measurement has no full CloudWatch minute')
    return begin, end


def parse_cpu(data, begin, end):
    expected = set(range(begin, end, 60))
    values = {}
    for point in data.get('Datapoints', []):
        timestamp = int(datetime.fromisoformat(point['Timestamp'].replace('Z', '+00:00')).timestamp())
        if timestamp not in expected:
            continue
        value = point.get('Average')
        require(type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 100,
                'Invalid CloudWatch CPU sample')
        require(timestamp not in values, 'Duplicate CloudWatch CPU sample')
        values[timestamp] = value
    require(set(values) == expected, 'Missing CloudWatch CPU minute buckets')
    return {'averagePercent': sum(values.values()) / len(values), 'periodSeconds': 60,
            'startEpochSeconds': begin, 'endEpochSeconds': end,
            'samples': [{'timestamp': t, 'averagePercent': values[t]} for t in sorted(values)],
            'scope': 'RDS instance CPU; complete minute buckets within HTTP measurement only'}


def cloudwatch_cpu(c, start_ms, end_ms):
    begin, end = cpu_window(start_ms, end_ms)
    iso = lambda value: datetime.fromtimestamp(value, timezone.utc).isoformat()
    command = ['aws', '--region', c['region'], 'cloudwatch', 'get-metric-statistics',
               '--namespace', 'AWS/RDS', '--metric-name', 'CPUUtilization',
               '--dimensions', 'Name=DBInstanceIdentifier,Value=' + c['dbInstanceIdentifier'],
               '--statistics', 'Average', '--period', '60', '--start-time', iso(begin),
               '--end-time', iso(end), '--output', 'json']
    deadline = time.monotonic() + c['waitSeconds']
    while True:
        response = subprocess.run(command, capture_output=True, text=True, timeout=30)
        require(response.returncode == 0, 'CloudWatch CPU read failed')
        try:
            result = parse_cpu(json.loads(response.stdout), begin, end)
            result.update(dbInstanceIdentifier=c['dbInstanceIdentifier'], region=c['region'])
            return result
        except ValueError:
            if time.monotonic() >= deadline:
                raise ValueError('CloudWatch CPU evidence incomplete') from None
            time.sleep(min(10, max(0, deadline - time.monotonic())))
