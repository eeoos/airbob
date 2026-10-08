"""Read-only observations of the disposable local coupon experiment (never AWS)."""
import http.client
import json
import math
from pathlib import Path
import re
import socket
import statistics
import subprocess
import threading
import time
import urllib.request

from coupon_experiment import require

STATUS = ('Innodb_row_lock_time', 'Innodb_row_lock_waits', 'Innodb_row_lock_current_waits',
          'Innodb_data_reads', 'Innodb_data_writes', 'Innodb_data_fsyncs', 'Innodb_data_read',
          'Innodb_data_written', 'Innodb_os_log_written', 'Threads_running')
APP_PREFIXES = ('hikaricp_', 'process_cpu_', 'system_cpu_', 'jvm_gc_pause_seconds_',
                'jvm_memory_used_bytes', 'tomcat_threads_', 'process_start_time_seconds')
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def app_metrics(raw):
    result = {}
    for line in raw.splitlines():
        match = re.fullmatch(r'([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{[^\n]*\})?\s+(\S+)', line)
        if not match or not match[1].startswith(APP_PREFIXES):
            continue
        value = float(match[2])
        if math.isfinite(value):
            result[match[1]] = result.get(match[1], 0) + value
    return result


class DockerSocket(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__('localhost', timeout=5)
        self.path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)


def container_stats(path, container):
    require(re.fullmatch(r'[0-9a-f]{64}', container), 'Invalid disposable container ID')
    connection = DockerSocket(path)
    try:
        connection.request('GET', f'/containers/{container}/stats?stream=false&one-shot=true')
        response = connection.getresponse()
        require(response.status == 200, 'Disposable container stats unavailable')
        value = json.load(response)
        memory = value['memory_stats']
        io = value.get('blkio_stats', {}).get('io_service_bytes_recursive') or []
        return {'cpuUsageNs': value['cpu_stats']['cpu_usage']['total_usage'],
                'memoryBytes': memory.get('usage'), 'memoryLimitBytes': memory.get('limit'),
                'blockReadBytes': sum(r['value'] for r in io if r['op'].lower() == 'read'),
                'blockWriteBytes': sum(r['value'] for r in io if r['op'].lower() == 'write')}
    finally:
        connection.close()


def host_snapshot(pids, system=False):
    # ps exposes numeric process resource usage; no arguments, environment or credentials.
    result = subprocess.run(['ps', '-o', 'pid=,pcpu=,rss=', '-p', ','.join(str(p) for p in pids.values())],
                            capture_output=True, text=True, timeout=5)
    processes = {}
    for line in result.stdout.splitlines():
        pid, cpu, rss = line.split()
        for role, number in pids.items():
            if int(pid) == number:
                processes[role] = {'cpuPercent': float(cpu), 'rssBytes': int(rss) * 1024}
    value = {'processes': processes}
    if system:
        # Last sample is an interval observation, unlike top's initial since-boot CPU sample.
        top = subprocess.run(['top', '-l', '2', '-s', '1', '-n', '0'], capture_output=True,
                             text=True, check=True, timeout=8).stdout
        cpus = re.findall(r'CPU usage: ([\d.]+)% user, ([\d.]+)% sys, ([\d.]+)% idle', top)
        require(cpus, 'Missing macOS host CPU observation')
        value['cpuPercent'] = 100 - float(cpus[-1][2])
        pressure = subprocess.run(['memory_pressure', '-Q'], capture_output=True, text=True,
                                  check=True, timeout=5).stdout
        free = re.search(r'System-wide memory free percentage: (\d+)%', pressure)
        require(free, 'Missing macOS memory-pressure observation')
        value['memoryFreePercent'] = int(free[1])
    return value


def difference(before, after, key):
    require(key in before and key in after and after[key] >= before[key], 'Missing/reset counter: ' + key)
    return after[key] - before[key]


def summary(before, after, samples, errors, performance):
    begin, end = performance['measurementStartEpochMs'], performance['measurementEndEpochMs']
    inside = [s for s in samples if begin <= s['timestampMs'] <= end]
    result = {'status': 'complete' if not errors and len(inside) >= 2 else 'incomplete',
              'errors': errors, 'samplesWithinHttpWindow': len(inside),
              'counterWindowSeconds': (after['timestampMs'] - before['timestampMs']) / 1000,
              'counterWindowScope': 'brackets k6, including initialization and final drain; no other business workload',
              'sharedLocalHost': True}
    require(before['app']['process_start_time_seconds'] == after['app']['process_start_time_seconds'],
            'App restarted during diagnosis')
    waits = difference(before['mysql'], after['mysql'], 'Innodb_row_lock_waits')
    lock_ms = difference(before['mysql'], after['mysql'], 'Innodb_row_lock_time')
    result['rowLocks'] = {'waits': waits, 'accumulatedWaitSeconds': lock_ms / 1000,
                          'meanWaitMs': lock_ms / waits if waits else None,
                          'scope': 'cumulative across transactions; not wall-clock or CPU time'}
    acquire_n = difference(before['app'], after['app'], 'hikaricp_connections_acquire_seconds_count')
    acquire_s = difference(before['app'], after['app'], 'hikaricp_connections_acquire_seconds_sum')
    result['hikari'] = {'acquisitions': acquire_n,
                        'acquireMeanMs': acquire_s * 1000 / acquire_n if acquire_n else None,
                        'timeouts': difference(before['app'], after['app'], 'hikaricp_connections_timeout_total'),
                        'maxConnections': after['app']['hikaricp_connections_max']}
    if inside:
        pending = [s['app']['hikaricp_connections_pending'] for s in inside]
        result['hikari'].update(pendingMax=max(pending), pendingSampleMean=statistics.mean(pending),
                               pendingSampleFraction=sum(p > 0 for p in pending) / len(pending))
        groups = {}
        for sample in inside:
            for row in sample['lockWaits']:
                key = '/'.join(str(row[k]) for k in ('OBJECT_NAME', 'INDEX_NAME', 'LOCK_MODE'))
                groups.setdefault(key, []).append(row['waiting_threads'])
        result['rowLocks']['observedTargets'] = {k: {'samples': len(v), 'maxWaitingThreads': max(v)}
                                                for k, v in groups.items()}
        system = [s['host'] for s in inside if 'cpuPercent' in s['host']]
        processes = [s['host']['processes'].get('k6') for s in inside]
        processes = [p for p in processes if p]
        result['host'] = {'systemSamples': len(system), 'k6Samples': len(processes),
                          'maxCpuPercent': max((s['cpuPercent'] for s in system), default=None),
                          'minMemoryFreePercent': min((s['memoryFreePercent'] for s in system), default=None),
                          'k6MaxCpuPercent': max((p['cpuPercent'] for p in processes), default=None),
                          'k6MaxRssBytes': max((p['rssBytes'] for p in processes), default=None)}
        if not system or not processes:
            result['status'] = 'incomplete'
    result['containers'] = {}
    for name in after['containers']:
        a, b = before['containers'][name], after['containers'][name]
        result['containers'][name] = {
            'averageCpuCores': difference(a, b, 'cpuUsageNs') / 1e9 / result['counterWindowSeconds'],
            'blockReadBytes': difference(a, b, 'blockReadBytes'),
            'blockWriteBytes': difference(a, b, 'blockWriteBytes'),
            'peakSampleMemoryBytes': max((s['containers'][name]['memoryBytes'] or 0 for s in samples), default=None)}
    result['fileIo'] = []
    old = {r['EVENT_NAME']: r for r in before['fileIo']}
    for row in after['fileIo']:
        first = old.get(row['EVENT_NAME'], {k: 0 for k in row if k != 'EVENT_NAME'})
        n = difference(first, row, 'COUNT_STAR')
        if n:
            result['fileIo'].append({'event': row['EVENT_NAME'], 'operations': n,
                                    'accumulatedSeconds': difference(first, row, 'SUM_TIMER_WAIT') / 1e12,
                                    'meanMs': difference(first, row, 'SUM_TIMER_WAIT') / 1e9 / n,
                                    'miscOperations': difference(first, row, 'COUNT_MISC'),
                                    'miscSeconds': difference(first, row, 'SUM_TIMER_MISC') / 1e12})
    result['statements'] = []
    old = {r['DIGEST']: r for r in before['statements']}
    for row in after['statements']:
        first = old.get(row['DIGEST'], {'COUNT_STAR': 0, 'SUM_TIMER_WAIT': 0})
        n = difference(first, row, 'COUNT_STAR')
        if n:
            result['statements'].append({'sqlTemplate': row['DIGEST_TEXT'], 'count': n,
                                        'meanMs': difference(first, row, 'SUM_TIMER_WAIT') / 1e9 / n})
    result['gcPauseSeconds'] = difference(before['app'], after['app'], 'jvm_gc_pause_seconds_sum')
    return result


class LocalDiagnostics:
    def __init__(self, context, directory):
        self.context, self.directory = context, directory
        self.samples, self.errors = [], []
        self.stop = threading.Event()
        self.k6_pid = None

    def query(self, sql, args=None):
        with self.connection.cursor() as cursor:
            cursor.execute(sql, args)
            return cursor.fetchall()

    def capture(self, full=False, system=False):
        start = time.monotonic()
        with HTTP.open(self.context['metricsUrl'], timeout=5) as response:
            app = app_metrics(response.read().decode())
        rows = self.query('SELECT VARIABLE_NAME, VARIABLE_VALUE FROM performance_schema.global_status '
                          'WHERE VARIABLE_NAME IN (' + ','.join(['%s'] * len(STATUS)) + ')', STATUS)
        mysql = {r['VARIABLE_NAME']: int(r['VARIABLE_VALUE']) for r in rows}
        locks = self.query('''SELECT l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_MODE,
                 COUNT(DISTINCT w.REQUESTING_THREAD_ID) waiting_threads
            FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l
              ON l.ENGINE = w.ENGINE AND l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
           WHERE l.OBJECT_SCHEMA = 'coupon_local'
           GROUP BY l.OBJECT_NAME, l.INDEX_NAME, l.LOCK_MODE''')
        pids = {'app': self.context['appPid']}
        if self.k6_pid:
            pids['k6'] = self.k6_pid
        value = {'timestampMs': time.time() * 1000, 'app': app, 'mysql': mysql, 'lockWaits': locks,
                 'containers': {k: container_stats(self.context['dockerSocket'], v)
                                for k, v in self.context['containers'].items()},
                 'host': host_snapshot(pids, system)}
        if full:
            value['fileIo'] = self.query('''SELECT EVENT_NAME, COUNT_STAR, SUM_TIMER_WAIT, COUNT_MISC, SUM_TIMER_MISC
                FROM performance_schema.file_summary_by_event_name
                WHERE EVENT_NAME LIKE 'wait/io/file/innodb/%%' OR EVENT_NAME LIKE 'wait/io/file/sql/binlog%%' ''')
            rows = self.query('''SELECT DIGEST, DIGEST_TEXT, COUNT_STAR, SUM_TIMER_WAIT
                  FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME = 'coupon_local' ''')
            value['statements'] = [r for r in rows if r['DIGEST_TEXT'] and
                                   (re.search(r'`(?:coupon|member_coupon)`', r['DIGEST_TEXT'])
                                    or r['DIGEST_TEXT'] == 'COMMIT')]
        value['collectionMs'] = (time.monotonic() - start) * 1000
        return value

    def __enter__(self):
        import pymysql
        self.connection = pymysql.connect(host='127.0.0.1', port=self.context['mysqlPort'],
                                          user='coupon_observer', password=self.context['password'],
                                          autocommit=True, cursorclass=pymysql.cursors.DictCursor,
                                          connect_timeout=5, read_timeout=5, write_timeout=5)
        try:
            self.before = self.capture(full=True, system=True)
            # Fail before the workload if essential observations cannot be collected.
            for key in ('hikaricp_connections_acquire_seconds_count', 'hikaricp_connections_acquire_seconds_sum',
                        'hikaricp_connections_pending', 'hikaricp_connections_max',
                        'hikaricp_connections_timeout_total', 'jvm_gc_pause_seconds_sum'):
                require(key in self.before['app'], 'Missing app diagnostic: ' + key)
            require(set(STATUS) <= self.before['mysql'].keys(), 'Missing MySQL diagnostic status')
            self.stream = (self.directory / 'diagnostic-samples.ndjson').open('w')
            self.thread = threading.Thread(target=self.sample, daemon=True)
            self.thread.start()
            return self
        except BaseException:
            self.connection.close()
            raise

    def sample(self):
        index = 0
        while not self.stop.is_set():
            start = time.monotonic()
            try:
                value = self.capture(system=index % 5 == 0)
                self.samples.append(value)
                self.stream.write(json.dumps(value) + '\n')
                self.stream.flush()
            except Exception as error:
                self.errors.append({'timestampMs': time.time() * 1000, 'type': type(error).__name__})
            index += 1
            self.stop.wait(max(0, 2 - (time.monotonic() - start)))

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=30)
        try:
            require(not self.thread.is_alive(), 'Diagnostic collection did not stop')
            self.after = self.capture(full=True, system=True)
            (self.directory / 'diagnostic-before.json').write_text(json.dumps(self.before, indent=2) + '\n')
            (self.directory / 'diagnostic-after.json').write_text(json.dumps(self.after, indent=2) + '\n')
        finally:
            self.stream.close()
            self.connection.close()

    def result(self, performance):
        return summary(self.before, self.after, self.samples, self.errors, performance)
