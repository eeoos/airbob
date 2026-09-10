"""V28 loopback reads using temporary Redis sessions, with no DB content edits."""
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import sys
import time
import urllib.request
import uuid
import growth_app_read as common
from growth_v4_contract import require


def canonical(value):
    def normalize(item):
        if isinstance(item, float) and item.is_integer(): return int(item)
        if isinstance(item, list): return [normalize(v) for v in item]
        if isinstance(item, dict): return {k: normalize(v) for k, v in item.items()}
        return item
    return json.dumps(normalize(value), ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()


def redis(port, *args):
    host = 'redis-general.lab.airbob.internal' if port == 6379 else 'redis-cache.lab.airbob.internal'
    parts = [str(a).encode() for a in args]
    wire = b'*' + str(len(parts)).encode() + b'\r\n' + b''.join(b'$' + str(len(p)).encode() + b'\r\n' + p + b'\r\n' for p in parts)
    with socket.create_connection((host, port), timeout=5) as client:
        client.sendall(wire)
        with client.makefile('rb') as stream:
            line = stream.readline(4096)
            require(not line.startswith(b'-'), 'Redis fixture operation failed')
            if line.startswith(b'$'):
                size = int(line[1:-2])
                return None if size == -1 else stream.read(size + 2)[:-2].decode()
            return line[1:-2].decode()


def cleanup_all(actions):
    """Attempt every cleanup and preserve an already propagating failure."""
    primary = sys.exc_info()[0]
    failures = []
    for action in actions:
        try:
            action()
        except Exception as error:
            failures.append(type(error).__name__)
    if failures:
        print(json.dumps({'cleanupFailureTypes': failures}), flush=True)
        if primary is None:
            raise RuntimeError('Qualification cleanup failed')


def read_cases(release, base='http://127.0.0.1:18080'):
    manifest = json.loads((release / 'consumer-manifest.json').read_text())
    path = release / 'read-scenarios.json'
    require(hashlib.sha256(path.read_bytes()).hexdigest() == manifest['artifacts']['reads']['sha256'], 'Read contract differs')
    targets = json.loads(path.read_text())['targets']
    sessions, active, owned = {}, [], []
    results = []
    try:
        for target in targets:
            require(target['method'] == 'GET' and target['immutable'] and target['path'].startswith('/api/v1/'), 'Only sealed immutable reads allowed')
            member = target['memberId']
            if member is not None and member not in sessions:
                require(target['account']['memberId'] == member and target['account']['email'].endswith('@example.test'), 'Synthetic account required')
                session = str(uuid.uuid4())
                key = 'SESSION:' + session
                require(redis(6379, 'SET', key, member, 'EX', 600, 'NX') == 'OK', 'Session creation failed')
                owned.append(key)
                sessions[member] = session
                active_key = 'MEMBER_SESSION_ACTIVE:' + str(member)
                marker = json.dumps('v4-aws-verify-' + session)
                if redis(6379, 'SET', active_key, marker, 'EX', 600, 'NX') == 'OK': active.append((active_key, marker))
            headers = {} if member is None else {'Cookie': 'SESSION_ID=' + sessions[member]}
            request = urllib.request.Request(base + target['path'], headers=headers)
            with urllib.request.urlopen(request, timeout=45) as response:
                body = json.load(response)
                sha = hashlib.sha256(canonical(body)).hexdigest()
                passed = response.status == target['expectedStatus'] and sha == target['expectedResponseSha256']
                results.append({'id': target['id'], 'status': response.status, 'responseSha256': sha, 'passed': passed})
    finally:
        actions = [lambda: redis(6379, 'UNLINK', *owned)] if owned else []
        actions += [lambda key=key, marker=marker: redis(6379, 'EVAL',
            "if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('UNLINK',KEYS[1]) else return 0 end", 1, key, marker)
            for key, marker in active]
        cleanup_all(actions)
    return {'passed': bool(results) and all(r['passed'] for r in results), 'readCount': len(results), 'checks': results,
            'authentication': 'temporary synthetic-member Redis sessions; password login not tested'}


def qualify_application(release, manifest, runtime, private, work, connection, execute, aws):
    common.validate_identity(os.environ)
    common.validate_read_runtime(release)
    image = os.environ['AIRBOB_GROWTH_APP_IMAGE']
    credential = aws('ecr', 'get-authorization-token')['authorizationData'][0]
    login = base64.b64decode(credential['authorizationToken']).split(b':', 1)
    require(login[0] == b'AWS' and credential['proxyEndpoint'] == 'https://' + common.REGISTRY, 'Unexpected image registry')
    docker_env = dict(os.environ, DOCKER_CONFIG=str(private / 'docker-login'))
    execute(['docker', 'login', '--username', 'AWS', '--password-stdin', common.REGISTRY], input=login[1], env=docker_env)
    execute(['docker', 'pull', image], env=docker_env, timeout=300)
    actual_jar = execute(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', image, '/app/app.jar'], env=docker_env).decode().split()[0]
    require(actual_jar == os.environ['AIRBOB_GROWTH_APP_JAR_SHA256'], 'Published app JAR differs')
    name = 'airbob-growth-v4-read-' + os.environ['AIRBOB_RUN_ID']
    require(not execute(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'], env=docker_env).strip(), 'Probe container already exists')
    defaults = private / 'client.cnf'
    detail_ids = execute(['mysql', '--defaults-extra-file=' + str(defaults), '--batch', '--skip-column-names', 'airbobdb'],
        input=b"SELECT id FROM accommodation WHERE status='PUBLISHED' ORDER BY id LIMIT 5").decode().splitlines()
    require(len(detail_ids) == 5 and all(value.isdigit() for value in detail_ids), 'Five published detail fixtures required')
    results, detail_baseline = [], None
    for cache in [False, True]:
        require(redis(6380, 'DBSIZE') == '0', 'Dedicated cache must start empty')
        settings = common.app_settings(connection, cache)
        settings['spring.flyway.target'] = '28'
        config = private / 'app.env'
        config.write_text(common.app_environment(settings))
        config.chmod(0o600)
        started = False
        try:
            started = True
            execute(['docker', 'run', '-d', '--name', name, '--network', 'host', '--memory', '768m',
                '--env-file', str(config), '--mount', f'type=bind,source={private},target={private},readonly', image], env=docker_env)
            for _ in range(120):
                require(execute(['docker', 'inspect', '--format', '{{.State.Running}}', name], env=docker_env).strip() == b'true', 'Probe app exited')
                try:
                    with urllib.request.urlopen('http://127.0.0.1:18080/actuator/health', timeout=3) as response:
                        if json.load(response)['status'] == 'UP': break
                except (OSError, ValueError): pass
                time.sleep(1)
            else: raise RuntimeError('V28 app startup deadline exceeded')
            observation = read_cases(release)
            (work / ('read-observation-' + str(cache).lower() + '.json')).write_text(json.dumps(observation, indent=2) + '\n')
            require(observation['passed'], 'App response differs from sealed V28 data')
            details = []
            for listing_id in detail_ids:
                hashes = []
                for _ in range(2):
                    with urllib.request.urlopen('http://127.0.0.1:18080/api/v1/accommodations/' + listing_id, timeout=30) as response:
                        body = json.load(response)
                        require(response.status == 200 and body.get('data'), 'Published detail failed')
                        hashes.append(hashlib.sha256(canonical(body)).hexdigest())
                require(len(set(hashes)) == 1, 'Repeated detail changed')
                details.append({'id': int(listing_id), 'responseSha256': hashes[0]})
            require(detail_baseline is None or detail_baseline == details, 'Cached and uncached detail differ')
            detail_baseline = details
            cache_after = int(redis(6380, 'DBSIZE'))
            require(cache_after >= len(detail_ids) if cache else cache_after == 0, 'Cache contents differ from selected mode')
            results.append({'cacheEnabled': cache, 'reads': observation, 'repeatedDetailTargets': details,
                'dedicatedCacheKeysBefore': 0, 'dedicatedCacheKeysAfterDetails': cache_after})
        except BaseException:
            if started:
                try:
                    log_path = work / ('app-cache-' + str(cache).lower() + '.log')
                    with log_path.open('wb') as log:
                        execute(['docker', 'logs', name], env=docker_env, output=log)
                    (work / 'app-startup-diagnostic.json').write_text(json.dumps(common.startup_diagnostic(log_path.read_text(errors='replace')), indent=2) + '\n')
                except Exception:
                    pass
            raise
        finally:
            # The general Redis is never flushed. This endpoint is the separate,
            # fresh qualification cache required by the performance-lab profile.
            cleanup_all(([lambda: execute(['docker', 'rm', '-f', name], env=docker_env)] if started else []) +
                        [lambda: redis(6380, 'FLUSHDB')])
    return {'schemaVersion': 1, 'kind': 'growth-app-read-qualification', 'passed': True,
        'runId': os.environ['AIRBOB_RUN_ID'], 'rdsResourceId': os.environ['AIRBOB_RDS_RESOURCE_ID'],
        'datasetId': manifest['source']['datasetId'], 'datasetManifestSha256': os.environ['AIRBOB_DATASET_MANIFEST_SHA256'],
        'appCommit': os.environ['AIRBOB_GROWTH_APP_COMMIT'], 'appImage': image, 'appJarSha256': actual_jar,
        'flywayTarget': 28, 'results': results, 'asgExecuted': False, 'albExecuted': False,
        'databaseContentChangedByProbe': False}
