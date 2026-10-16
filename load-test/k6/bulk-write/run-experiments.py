#!/usr/bin/env python3
"""Plan/package offline, or measure an existing isolated local/AWS bulk-delete server."""
import argparse
import hashlib
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import tarfile
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from bulk_delete_experiment import comparison, fingerprint, plan, require, validate, validate_runtime

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUNTIME_PATH = '/api/v2/admin/benchmarks/bulk-write/runtime'
SOURCES = [
    'load-test/k6/bulk-write/' + name for name in (
        'bulk_delete_experiment.py', 'run-experiments.py', 'run-bulk-write-observations.sh',
        'run-wishlist-delete.sh', 'run-wishlist-delete-observations.sh',
        'run-accommodation-amenity-delete.sh', 'run-accommodation-amenity-delete-observations.sh',
        'wishlist-delete-comparison.js', 'accommodation-amenity-delete-comparison.js',
        'aggregate-bulk-write-observations.mjs', 'BENCHMARK.md',
    )
] + ['load-test/k6/lib/' + name for name in (
    'benchmark-fixture.js', 'bulk-write-benchmark.js', 'bulk-delete-runtime.js',
)] + ['infra/aws/toolchain.env']


def write_json(path, value):
    with Path(path).open('x', encoding='utf-8') as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def private_text(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, encoding='utf-8') as stream:
        info = os.fstat(stream.fileno())
        require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and not info.st_mode & 0o077
                and 0 < info.st_size <= 4096, 'Credential files must be owned regular files with mode 0600')
        text = stream.read().rstrip('\r\n')
    require(text and '\n' not in text and '\r' not in text and '\x00' not in text, 'Invalid credential file')
    return text


def credentials(c):
    values = {name: private_text(path) for name, path in c['credentials'].items()}
    require(len(values['tokenFile']) >= 32 and values['tokenFile'] == values['tokenFile'].strip(),
            'A non-padded benchmark token of at least 32 characters is required')
    require('@' in values['emailFile'] and values['emailFile'] == values['emailFile'].strip(),
            'Invalid benchmark email')
    return values


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def probe(c, secrets):
    """Use a short-lived ADMIN session; no session, credentials or response body is printed/saved."""
    opener = build_opener(NoRedirect())
    session = None

    def request(path, method='GET', body=None):
        headers = {'Content-Type': 'application/json', 'X-Bulk-Write-Benchmark-Token': secrets['tokenFile']}
        if session:
            headers['Cookie'] = 'SESSION_ID=' + session
        req = Request(c['baseUrl'] + path, data=None if body is None else json.dumps(body).encode(),
                      headers=headers, method=method)
        try:
            with opener.open(req, timeout=15) as response:
                require(response.status == 200, 'Benchmark HTTP preflight failed')
                raw = response.read(100001)
                require(len(raw) <= 100000, 'Benchmark preflight response is too large')
                return response.headers, json.loads(raw)
        except (HTTPError, OSError, ValueError):
            raise ValueError('Benchmark HTTP preflight failed; check origin, ADMIN login and token') from None

    try:
        headers, _ = request('/api/v1/auth/login', 'POST',
                             dict(email=secrets['emailFile'], password=secrets['passwordFile']))
        cookie = SimpleCookie()
        for header in headers.get_all('Set-Cookie', []):
            cookie.load(header)
        require('SESSION_ID' in cookie, 'Benchmark login did not issue a session')
        session = cookie['SESSION_ID'].value
        _, payload = request(RUNTIME_PATH)
        require(payload.get('success') is True, 'Benchmark runtime request failed')
        return validate_runtime(c, payload.get('data'))
    finally:
        if session:
            request('/api/v1/auth/logout', 'POST')


def invoke(command, env, cwd, timeout):
    # Capture instead of forwarding subprocess errors that might contain credential-bearing environment data.
    with subprocess.Popen(command, cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          start_new_session=True) as process:
        try:
            output, _ = process.communicate(timeout=timeout)
        except (subprocess.TimeoutExpired, KeyboardInterrupt):
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.communicate()
            raise ValueError('Benchmark block interrupted or timed out; inspect disposable fixture state') from None
        require(process.returncode == 0, 'Benchmark block failed; no comparison report was produced')


def child_environment(c, runtime, secrets, block):
    # Do not inherit K6_OUT/HTTP_DEBUG, arbitrary executable overrides or other experiment settings.
    env = {key: os.environ[key] for key in ('PATH', 'HOME', 'TMPDIR', 'LANG', 'SSL_CERT_FILE', 'SSL_CERT_DIR')
           if key in os.environ}
    env.update(
        BASE_URL=c['baseUrl'], BENCHMARK_EMAIL=secrets['emailFile'], TEST_PASSWORD=secrets['passwordFile'],
        BENCHMARK_BULK_WRITE_TOKEN=secrets['tokenFile'], APP_COMMIT=c['appCommit'], APP_INSTANCE_COUNT='1',
        SCHEMA_LABEL=c['schemaLabel'], JVM_VERSION=runtime['jvm_version'], MYSQL_VERSION=runtime['mysql_version'],
        REWRITE_BATCHED_STATEMENTS=str(c['rewriteBatchedStatements']).lower(), REQUEST_TIMEOUT='30s',
        DATASET_SIZE=str(block['datasetSize']), VARIANT=block['variant'], ROUND=str(block['round']),
        RUN_ORDER=str(block['runOrder']), RAW_OBSERVATION_SAMPLES=str(c['samples']),
        BULK_DELETE_RUNTIME_ID=runtime['runtime_id'], BULK_DELETE_DATABASE_ID=runtime['database_id'],
        BULK_DELETE_ENVIRONMENT=c['environment'], BULK_DELETE_IMAGE_DIGEST=c['imageDigest'],
        BULK_DELETE_POOL_SIZE=str(c['poolSize']), K6_NO_USAGE_REPORT='true', K6_ADDRESS='',
    )
    if block['measurement']:
        env['MEASUREMENT'] = block['measurement']
    return env


def validate_observations(c, runtime, block, artifact):
    require(artifact.get('schema_version') == 'bulk-write-observations-v1', 'Invalid observations format')
    metadata = artifact['metadata']
    expected = dict(candidate=block['candidate'], variant=block['variant'], phase='measure',
                    dataset_size=block['datasetSize'], samples=c['samples'], run_label=block['label'],
                    round=block['round'], run_order=block['runOrder'], app_commit=c['appCommit'],
                    schema_label=c['schemaLabel'], app_instance_count=1, jvm_version=runtime['jvm_version'],
                    mysql_version=runtime['mysql_version'], rewrite_batched_statements=c['rewriteBatchedStatements'])
    if block['measurement']:
        expected['measurement'] = block['measurement']
    require(all(metadata.get(k) == v for k, v in expected.items()), 'Observation metadata does not match this block')
    require(len(artifact['observations']) == c['samples'], 'Incomplete observations')
    require(all(o['verification']['succeeded'] is True for o in artifact['observations']), 'Unverified observations')


def execute(c, *, root=ROOT, probe_server=probe, run_command=invoke):
    c = validate(c)
    require(not c['example'], 'Example configuration cannot send load; fill real values and set example=false')
    secrets = credentials(c)
    # Pin the actual process/database before the first write request.
    runtime = probe_server(c, secrets)
    output = root / 'build/k6/bulk-delete' / c['runId']
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    schedule = plan(c)
    write_json(output / 'plan.json', schedule)
    write_json(output / 'runtime.json', runtime)
    completed = []
    active_block = None
    try:
        for block in schedule['blocks']:
            active_block = block['label']
            require(probe_server(c, secrets) == runtime, 'Server restarted or changed during the experiment')
            env = child_environment(c, runtime, secrets, block)
            warmup = f'build/k6/bulk-write/{block["label"]}-warmup.json'
            measure = f'build/k6/bulk-write/{block["label"]}-observations.json'
            require(not (root / warmup).exists() and not (root / measure).exists(), 'Block artifacts already exist')
            env.update(PHASE='warmup', SAMPLES=str(c['warmupSamples']),
                       RUN_LABEL=block['label'] + '-warmup', K6_RESULT_PATH=warmup)
            script = root / 'load-test/k6/bulk-write' / block['runner']
            run_command(['bash', str(script) + '.sh'], env, root, 90 + c['warmupSamples'] * 45)
            require((root / warmup).is_file(), 'Warmup did not emit its artifact')
            env.update(PHASE='measure', RUN_LABEL=block['label'], RAW_OBSERVATION_RESULT_PATH=measure)
            env.pop('K6_RESULT_PATH', None)
            run_command(['bash', str(script) + '-observations.sh'], env, root, 90 + c['samples'] * 90)
            artifact = json.loads((root / measure).read_text())
            validate_observations(c, runtime, block, artifact)
            completed.append(dict(block=block, artifact=artifact, source=measure))
            print(f'Completed {len(completed)}/{len(schedule["blocks"])}: {block["key"]} '
                  f'{block["variant"]} round {block["round"]}', flush=True)
        require(probe_server(c, secrets) == runtime, 'Server changed at the end of the experiment')
        # Pairing must also preserve the active code set size/workload class.
        for case in schedule['blocks']:
            group = [b['artifact']['metadata'] for b in completed if b['block']['key'] == case['key']]
            require(all((m.get('active_amenity_code_count'), m.get('workload_class')) ==
                        (group[0].get('active_amenity_code_count'), group[0].get('workload_class')) for m in group),
                    'Amenity workload changed between Before and After')
        report = dict(schemaVersion=1, state='measured', environment=c['environment'],
                      configSha256=fingerprint(c), runtime=runtime, scope=schedule['scope'],
                      percentileAlgorithm='nearest-rank', observations=[b['source'] for b in completed],
                      comparisons=comparison(c, completed))
        write_json(output / 'comparison.json', report)
        return output
    except BaseException:
        write_json(output / 'failure.json', dict(state='incomplete', completedBlocks=len(completed),
                   failedBlock=active_block, comparisonProduced=False,
                   note='Check the disposable schema for leftovers after a timeout or process failure.'))
        raise


def prepare(c, output):
    """Copy only tracked tool/config examples. No credentials, AWS calls, provisioning or deployment."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    with tarfile.open(output / 'bulk-delete-tools.tar.gz', 'w:gz') as archive:
        for name in SOURCES:
            archive.add(ROOT / name, arcname=name, recursive=False)
    write_json(output / 'config.json', c)
    schedule = plan(c)
    schedule.update(state='offline-prepared', readyToExecute=False, networkCallsPerformed=0,
                    configSha256=fingerprint(c),
                    sourceHashes={p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in SOURCES})
    write_json(output / 'plan.json', schedule)
    (output / 'aws-application.env.example').write_bytes((HERE / 'aws-application.env.example').read_bytes())
    (output / 'BENCHMARK.md').write_bytes((HERE / 'BENCHMARK.md').read_bytes())
    (output / 'SHA256SUMS').write_text(''.join(
        hashlib.sha256((output / name).read_bytes()).hexdigest() + '  ' + name + '\n'
        for name in ('bulk-delete-tools.tar.gz', 'config.json', 'plan.json',
                     'aws-application.env.example', 'BENCHMARK.md')))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('plan', 'prepare', 'run'))
    parser.add_argument('--config', required=True)
    parser.add_argument('--output', help='New output directory for offline prepare')
    parser.add_argument('--check-inputs', action='store_true', help='Only validate local files; do not connect')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        c = validate(json.loads(Path(args.config).read_text()))
        if args.mode == 'plan':
            print(json.dumps(plan(c), indent=2))
        elif args.mode == 'prepare':
            require(args.output is not None, '--output is required')
            print('Offline preparation: ' + str(prepare(c, args.output)))
        elif args.check_inputs:
            require(not c['example'], 'Replace the example settings first')
            credentials(c)
            print('Local configuration and credential files are valid; no network calls performed')
        else:
            print('Comparison report: ' + str(execute(c) / 'comparison.json'))
    except (ValueError, OSError, KeyError, TypeError) as exc:
        # Local validation errors are deliberately fixed strings; external/subprocess details are never forwarded.
        print(str(exc) if isinstance(exc, ValueError) else 'Experiment input or artifact could not be read/written',
              file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
