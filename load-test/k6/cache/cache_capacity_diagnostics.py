"""Read-only local capacity observations; counter times are not API latency."""
import http.client
import json
import math
import re
import socket
import statistics
import subprocess
import threading
import time


class DockerSocket(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__('localhost', timeout=5)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


def container_stats(path, container):
    if not re.fullmatch(r'[0-9a-f]{64}', container):
        raise ValueError('Expected an inspected local container ID')
    connection = DockerSocket(path)
    try:
        connection.request('GET', f'/containers/{container}/stats?stream=false&one-shot=true')
        response = connection.getresponse()
        if response.status != 200:
            raise RuntimeError('Local container metrics unavailable')
        value = json.load(response)
        return {'cpuNs': value['cpu_stats']['cpu_usage']['total_usage'],
                'memoryBytes': value['memory_stats'].get('usage'),
                'networkRxBytes': sum(v['rx_bytes'] for v in value.get('networks', {}).values()),
                'networkTxBytes': sum(v['tx_bytes'] for v in value.get('networks', {}).values())}
    finally:
        connection.close()


def virtual_memory(raw):
    size = re.search(r'page size of (\d+) bytes', raw)
    if not size:
        raise ValueError('Missing macOS page size')
    values = {name: int(value) for name, value in re.findall(r'^(Swapins|Swapouts|Pageouts):\s+(\d+)\.', raw, re.M)}
    if len(values) != 3:
        raise ValueError('Missing macOS swap counters')
    return {'pageSizeBytes': int(size[1]), **values}


def other_load_generators(own_pid=None):
    raw = subprocess.run(['ps', '-A', '-o', 'pid=,comm='], check=True,
                         capture_output=True, text=True, timeout=5).stdout
    found = []
    for line in raw.splitlines():
        pid, executable = line.strip().split(None, 1)
        if executable.rsplit('/', 1)[-1] == 'k6' and int(pid) != own_pid:
            found.append(int(pid))
    return found


def host_snapshot(pids, system=False):
    raw = subprocess.run(['ps', '-o', 'pid=,pcpu=,rss=', '-p', ','.join(map(str, pids.values()))],
                         check=True, capture_output=True, text=True, timeout=5).stdout
    processes = {}
    for line in raw.splitlines():
        pid, cpu, rss = line.split()
        for name, number in pids.items():
            if int(pid) == number:
                processes[name] = {'cpuPercent': float(cpu), 'rssBytes': int(rss) * 1024}
    memory = subprocess.run(['vm_stat'], check=True, capture_output=True, text=True, timeout=5).stdout
    result = {'processes': processes, 'virtualMemory': virtual_memory(memory),
              'otherLoadGeneratorPids': other_load_generators(pids.get('k6'))}
    if system:
        raw = subprocess.run(['top', '-l', '2', '-s', '1', '-n', '0'], check=True,
                             capture_output=True, text=True, timeout=8).stdout
        matches = re.findall(r'CPU usage: ([\d.]+)% user, ([\d.]+)% sys, ([\d.]+)% idle', raw)
        if not matches:
            raise RuntimeError('Missing macOS CPU observation')
        result['cpuPercent'] = 100 - float(matches[-1][2])
        raw = subprocess.run(['memory_pressure', '-Q'], check=True, capture_output=True,
                             text=True, timeout=5).stdout
        match = re.search(r'System-wide memory free percentage: (\d+)%', raw)
        if not match:
            raise RuntimeError('Missing macOS memory pressure observation')
        result['memoryFreePercent'] = int(match[1])
    return result


def difference(before, after, key):
    if key not in before or key not in after or after[key] < before[key]:
        raise ValueError('Missing or reset counter: ' + key)
    return after[key] - before[key]


def timer(before, after, prefix):
    count = difference(before, after, prefix + '_count')
    seconds = difference(before, after, prefix + '_sum')
    return {'count': count, 'meanMs': seconds * 1000 / count if count else None,
            'accumulatedSeconds': seconds}


def summarize(before, after, samples, errors, result):
    begin, end = result['startedEpochMs'], result['endedEpochMs']
    inside = [s for s in samples if begin is not None and end is not None and begin <= s['timestampMs'] <= end]
    elapsed = (after['timestampMs'] - before['timestampMs']) / 1000
    report = {'status': 'complete' if not errors and len(inside) >= 2 else 'incomplete',
              'errors': errors, 'samplesWithinHttpWindow': len(inside), 'counterWindowSeconds': elapsed,
              'counterWindowScope': 'brackets k6 initialization and final drain; MySQL counters include other clients',
              'sharedLocalHost': True, 'apps': [], 'containers': {}}
    for index, (a, b) in enumerate(zip(before['apps'], after['apps'])):
        if a['process_start_time_seconds'] != b['process_start_time_seconds']:
            raise ValueError('App restarted during measurement')
        pending = [s['apps'][index]['hikaricp_connections_pending'] for s in inside]
        active = [s['apps'][index]['hikaricp_connections_active'] for s in inside]
        app = {'hikari': {
            'maxConnections': b['hikaricp_connections_max'],
            'acquire': timer(a, b, 'hikaricp_connections_acquire_seconds'),
            'usage': timer(a, b, 'hikaricp_connections_usage_seconds'),
            'timeouts': difference(a, b, 'hikaricp_connections_timeout_total'),
            'pendingMax': max(pending, default=None),
            'pendingSampleMean': statistics.mean(pending) if pending else None,
            'pendingSampleFraction': sum(p > 0 for p in pending) / len(pending) if pending else None,
            'activeMax': max(active, default=None)},
            'gcPauseSeconds': difference(a, b, 'jvm_gc_pause_seconds_sum')}
        for label, prefix in [('dbLoad', 'accommodation_detail_cache_load_duration_seconds'),
                              ('lockWait', 'accommodation_detail_cache_lock_wait_duration_seconds')]:
            if prefix + '_count' in a and prefix + '_count' in b:
                app[label] = timer(a, b, prefix)
        for metric in ['tomcat_threads_busy_threads', 'tomcat_threads_current_threads', 'jvm_threads_live_threads']:
            values = [s['apps'][index][metric] for s in inside if metric in s['apps'][index]]
            app[metric + 'Max'] = max(values, default=None)
        if 'process_cpu_time_ns_total' in a and 'process_cpu_time_ns_total' in b:
            app['averageCpuCores'] = difference(a, b, 'process_cpu_time_ns_total') / 1e9 / elapsed
        if len(inside) >= 2 and 'process_cpu_time_ns_total' in inside[0]['apps'][index]:
            window = (inside[-1]['timestampMs'] - inside[0]['timestampMs']) / 1000
            app['cpuCoresWithinHttpSamples'] = difference(inside[0]['apps'][index], inside[-1]['apps'][index],
                                                        'process_cpu_time_ns_total') / 1e9 / window
        report['apps'].append(app)
    for name, b in after['containers'].items():
        a = before['containers'][name]
        report['containers'][name] = {
            'averageCpuCores': difference(a, b, 'cpuNs') / 1e9 / elapsed,
            'networkRxBytes': difference(a, b, 'networkRxBytes'),
            'networkTxBytes': difference(a, b, 'networkTxBytes'),
            'peakSampleMemoryBytes': max((s['containers'][name]['memoryBytes'] or 0 for s in inside), default=None)}
        if len(inside) >= 2:
            window = (inside[-1]['timestampMs'] - inside[0]['timestampMs']) / 1000
            report['containers'][name]['cpuCoresWithinHttpSamples'] = difference(
                inside[0]['containers'][name], inside[-1]['containers'][name], 'cpuNs') / 1e9 / window
    host = [s['host'] for s in inside if 'cpuPercent' in s['host']]
    processes = {}
    for sample in inside:
        for name, row in sample['host']['processes'].items():
            processes.setdefault(name, []).append(row)
    report['host'] = {
        'systemSamples': len(host),
        'cpuMeanPercent': statistics.mean(s['cpuPercent'] for s in host) if host else None,
        'cpuMaxPercent': max((s['cpuPercent'] for s in host), default=None),
        'minMemoryFreePercent': min((s['memoryFreePercent'] for s in host), default=None),
        'processes': {name: {'cpuMeanPercent': statistics.mean(s['cpuPercent'] for s in rows),
                             'cpuMaxPercent': max(s['cpuPercent'] for s in rows),
                             'rssMaxBytes': max(s['rssBytes'] for s in rows)} for name, rows in processes.items()},
        'processCpuUnits': '100 percent means one logical CPU; sampled ps values'}
    report['host']['otherLoadGeneratorPids'] = sorted({pid for s in inside
        for pid in s['host'].get('otherLoadGeneratorPids', [])})
    if len(inside) >= 2 and 'virtualMemory' in inside[0]['host']:
        a, b = inside[0]['host']['virtualMemory'], inside[-1]['host']['virtualMemory']
        window = (inside[-1]['timestampMs'] - inside[0]['timestampMs']) / 1000
        report['host']['virtualMemory'] = {
            key + 'MiBPerSecond': difference(a, b, key) * b['pageSizeBytes'] / 1048576 / window
            for key in ['Swapins', 'Swapouts', 'Pageouts']}
    reasons = []
    if report['host']['otherLoadGeneratorPids']:
        reasons.append('competing-load-generator')
    if not host or 'k6' not in processes:
        reasons.append('missing-generator-observation')
        report['status'] = 'incomplete'
    if host and max(s['cpuPercent'] for s in host) >= 90:
        reasons.append('shared-host-cpu-pressure')
    if host and min(s['memoryFreePercent'] for s in host) < 15:
        reasons.append('shared-host-memory-pressure')
    if report['status'] != 'complete':
        reasons.append('incomplete-diagnostics')
    report['generatorHealthy'] = not reasons
    report['generatorReasons'] = reasons
    old = {r['digest']: r for r in before['mysql']['statements']}
    report['sql'] = []
    for row in after['mysql']['statements']:
        first = old.get(row['digest'], {'count': 0, 'timePs': 0, 'rows': 0})
        count = difference(first, row, 'count')
        if count:
            report['sql'].append({'template': row['template'], 'count': count,
                                  'meanMs': difference(first, row, 'timePs') / 1e9 / count,
                                  'rowsExamined': difference(first, row, 'rows')})
    report['mysqlStatusDelta'] = {key: difference(before['mysql']['status'], after['mysql']['status'], key)
                                 for key in ['Innodb_buffer_pool_reads', 'Innodb_buffer_pool_read_requests',
                                             'Innodb_row_lock_waits', 'Innodb_row_lock_time']}
    report['mysqlThreadsRunningMax'] = max((s['mysql']['status']['Threads_running'] for s in inside), default=None)
    return report


class Diagnostics:
    def __init__(self, lab, base, directory):
        self.lab, self.base, self.directory = lab, base, directory
        self.samples, self.errors = [], []
        self.stop = threading.Event()
        self.k6_pid = None
        uri = base.command(['docker', 'context', 'inspect', '--format', '{{.Endpoints.docker.Host}}'])
        base.require(uri.startswith('unix://'), 'Local diagnosis requires a Docker Unix socket')
        self.socket_path = uri.removeprefix('unix://')
        self.containers = {'mysql': lab.metadata['mysqlContainerId'], 'redis': lab.redis_id}

    def capture(self, full=False, system=False):
        apps = []
        for origin in self.lab.origins:
            values = {}
            for name, labels, value in self.base.parse_metrics(self.base.scrape(origin)):
                if name.startswith(('hikaricp_', 'tomcat_threads_', 'jvm_gc_pause_seconds_',
                                    'jvm_threads_live_threads', 'process_start_time_seconds', 'process_cpu_time_ns_total',
                                    'accommodation_detail_cache_load_duration_seconds_',
                                    'accommodation_detail_cache_lock_wait_duration_seconds_')):
                    if math.isfinite(value):
                        values[name] = values.get(name, 0) + value
            apps.append(values)
        sql = """SELECT JSON_OBJECT('status', (SELECT JSON_OBJECTAGG(VARIABLE_NAME, CAST(VARIABLE_VALUE AS UNSIGNED))
            FROM performance_schema.global_status WHERE VARIABLE_NAME IN ('Threads_running',
            'Threads_connected','Innodb_buffer_pool_reads','Innodb_buffer_pool_read_requests',
            'Innodb_row_lock_waits','Innodb_row_lock_time')));"""
        mysql = json.loads(self.base.mysql(self.lab.args.mysql_container, sql))
        if full:
            sql = """SELECT COALESCE(JSON_ARRAYAGG(JSON_OBJECT('digest', DIGEST, 'template', DIGEST_TEXT,
                'count', COUNT_STAR, 'timePs', SUM_TIMER_WAIT, 'rows', SUM_ROWS_EXAMINED)), JSON_ARRAY())
                FROM performance_schema.events_statements_summary_by_digest
                WHERE SCHEMA_NAME='airbobdb' AND DIGEST_TEXT LIKE 'SELECT%'
                AND DIGEST_TEXT NOT LIKE '%performance_schema%';"""
            mysql['statements'] = json.loads(self.base.mysql(self.lab.args.mysql_container, sql))
        pids = {'app' + str(i): p.pid for i, (p, _, _) in enumerate(self.lab.apps)}
        if self.k6_pid:
            pids['k6'] = self.k6_pid
        return {'timestampMs': time.time() * 1000, 'apps': apps, 'mysql': mysql,
                'containers': {name: container_stats(self.socket_path, identifier)
                               for name, identifier in self.containers.items()},
                'host': host_snapshot(pids, system)}

    def __enter__(self):
        self.directory.mkdir()
        self.before = self.capture(full=True, system=True)
        for app in self.before['apps']:
            for metric in ['process_start_time_seconds', 'hikaricp_connections_pending', 'hikaricp_connections_active',
                           'hikaricp_connections_max', 'hikaricp_connections_timeout_total',
                           'hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum',
                           'hikaricp_connections_usage_seconds_count', 'hikaricp_connections_usage_seconds_sum',
                           'jvm_gc_pause_seconds_sum']:
                self.base.require(metric in app, 'Missing diagnostic: ' + metric)
        self.thread = threading.Thread(target=self.sample, daemon=True)
        self.thread.start()
        return self

    def sample(self):
        with (self.directory / 'samples.ndjson').open('w') as stream:
            while not self.stop.is_set():
                started = time.monotonic()
                try:
                    value = self.capture(system=len(self.samples) % 3 == 0)
                    self.samples.append(value)
                    stream.write(json.dumps(value) + '\n')
                    stream.flush()
                except Exception as error:
                    self.errors.append({'type': type(error).__name__, 'timestampMs': time.time() * 1000})
                self.stop.wait(max(0, 2 - (time.monotonic() - started)))

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=30)
        self.base.require(not self.thread.is_alive(), 'Diagnostic thread did not stop')
        self.after = self.capture(full=True, system=True)
        self.base.write_json(self.directory / 'before.json', self.before)
        self.base.write_json(self.directory / 'after.json', self.after)

    def result(self, result):
        value = summarize(self.before, self.after, self.samples, self.errors, result)
        self.base.write_json(self.directory / 'summary.json', value)
        return value
