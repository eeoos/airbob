"""Bounded, read-only app observations collected beside AWS capacity traffic."""
import concurrent.futures
import json
import math
import threading
import time

from aws_cache_contract import local


GAUGES = {
    'hikaricp_connections_active', 'hikaricp_connections_idle',
    'hikaricp_connections_pending', 'hikaricp_connections_max',
    'process_cpu_usage', 'system_cpu_usage', 'jvm_threads_live_threads',
    'tomcat_threads_busy_threads', 'tomcat_threads_current_threads',
    'jvm_gc_pause_seconds_max', 'jvm_gc_overhead',
    'hikaricp_connections_acquire_seconds_max', 'hikaricp_connections_usage_seconds_max',
}
TIMERS = {
    'hikaricp_connections_acquire_seconds', 'hikaricp_connections_usage_seconds',
    'jvm_gc_pause_seconds', 'accommodation_detail_cache_load_duration_seconds',
    'accommodation_detail_cache_lock_wait_duration_seconds',
}
COUNTERS = {'hikaricp_connections_timeout_total', 'process_cpu_time_ns_total'}
NAMES = GAUGES | COUNTERS | {'process_start_time_seconds'} | {
    prefix + suffix for prefix in TIMERS for suffix in ('_sum', '_count')}


def metric_values(raw):
    values = {}
    for name, _, value in local.base.parse_metrics(raw):
        if name in NAMES and math.isfinite(value):
            # Timer maxima across causes/pools describe the longest observation,
            # not an accumulated duration. Micrometer retains them for a time window.
            values[name] = (max(values.get(name, 0), value) if name.endswith('_seconds_max')
                            else values.get(name, 0) + value)
    return values


def summarize(before, after, samples, errors, result):
    output = {'status': 'complete', 'errors': errors, 'apps': [],
              'samplingIntervalSeconds': 10,
              'counterScope': 'before/after snapshots bracket k6 initialization and final drain'}
    if result.get('ramp'):
        output['counterScope'] += '; includes the ramp, while gauge samples use the measurement window only'
    if len(before) != len(after) or any(len(row['apps']) != len(before) for row in samples):
        return output | {'status': 'incomplete', 'errors': errors + ['app-count-changed']}
    required = {'process_start_time_seconds', 'hikaricp_connections_pending',
                'hikaricp_connections_active', 'hikaricp_connections_max',
                'hikaricp_connections_timeout_total',
                'hikaricp_connections_acquire_seconds_sum',
                'hikaricp_connections_acquire_seconds_count'}
    for index, (first, last) in enumerate(zip(before, after)):
        a, b = first.get('diagnosticMetrics', {}), last.get('diagnosticMetrics', {})
        problems = []
        if not required <= a.keys() or not required <= b.keys():
            problems.append('required-metrics-missing')
        if (first['containerId'] != last['containerId']
                or a.get('process_start_time_seconds') != b.get('process_start_time_seconds')):
            problems.append('application-restarted')
        window = [sample['apps'][index] for sample in samples
                  if isinstance(result.get('startedEpochMs'), (int, float))
                  and isinstance(result.get('endedEpochMs'), (int, float))
                  and result['startedEpochMs'] <= sample['apps'][index].get('sampledAtEpochMs', -1)
                  <= result['endedEpochMs']]
        if len(window) < 2:
            problems.append('insufficient-samples-in-request-window')
        if any(not required <= row.get('diagnosticMetrics', {}).keys() for row in window):
            problems.append('sampled-metrics-missing')
        if any(row['containerId'] != first['containerId']
               or row.get('diagnosticMetrics', {}).get('process_start_time_seconds')
               != a.get('process_start_time_seconds') for row in window):
            problems.append('sampled-application-changed')
        gauges = {}
        for name in sorted(GAUGES):
            observed = [row['diagnosticMetrics'][name] for row in window
                        if name in row.get('diagnosticMetrics', {})]
            gauges[name] = {'min': min(observed), 'max': max(observed)} if observed else None
        deltas = {}
        for name in COUNTERS | {p + s for p in TIMERS for s in ('_sum', '_count')}:
            if name not in a or name not in b:
                deltas[name] = None
            elif b[name] < a[name]:
                problems.append('counter-reset:' + name)
                deltas[name] = None
            else:
                deltas[name] = b[name] - a[name]
        timers = {}
        for name in sorted(TIMERS):
            count, seconds = deltas[name + '_count'], deltas[name + '_sum']
            timers[name] = {'count': count, 'accumulatedSeconds': seconds,
                            'meanMs': seconds * 1000 / count if count and seconds is not None else None}
        output['apps'].append({'index': index, 'samplesInRequestWindow': len(window),
                               'gauges': gauges, 'timers': timers,
                               'counters': {k: deltas[k] for k in sorted(COUNTERS)},
                               'problems': problems})
    if errors or not output['apps'] or any(app['problems'] for app in output['apps']):
        output['status'] = 'incomplete'
    return output


class AppSampler:
    def __init__(self, transport, instances, path):
        self.transport, self.instances, self.path = transport, instances, path
        self.samples, self.errors = [], []
        self.stop = threading.Event()
        self.thread = threading.Thread(target=self.sample, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def sample(self):
        with self.path.open('w') as stream, concurrent.futures.ThreadPoolExecutor(max_workers=len(self.instances)) as pool:
            while not self.stop.is_set():
                started = time.monotonic()
                try:
                    futures = [pool.submit(self.transport.action, instance, 'snapshot', timeout=20)
                               for instance in self.instances]
                    # Wait for every read even if one fails; no overlapping sample batches.
                    rows, failures = [], []
                    for future in futures:
                        try:
                            rows.append(future.result())
                        except Exception as error:
                            failures.append(type(error).__name__)
                    if failures:
                        self.errors.extend(failures)
                    else:
                        value = {'apps': rows}
                        self.samples.append(value)
                        stream.write(json.dumps(value) + '\n')
                        stream.flush()
                except Exception as error:
                    self.errors.append(type(error).__name__)
                self.stop.wait(max(0, 10 - (time.monotonic() - started)))

    def __exit__(self, *args):
        self.stop.set()
        self.thread.join(timeout=120)
        local.require(not self.thread.is_alive(), 'App observation did not stop; retained evidence requires inspection')
