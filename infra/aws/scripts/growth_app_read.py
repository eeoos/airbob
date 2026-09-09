"""Short, loopback-only application compatibility on the private qualification host.

Runs after complete source parity. Uses the published app unchanged, restores
the two sealed read accounts, and leaves full-data parity to the caller.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import tarfile
import time
import urllib.request

REGISTRY = '942632789808.dkr.ecr.ap-northeast-2.amazonaws.com'
K6_VERSION = '1.5.0'
K6_SHA = '5ec7c7800ffedac41b9346c55fa7a4a73b4711b0d05d7226f1b8494748878263'


def validate_read_runtime(release):
    qualification = json.loads((Path(release) / 'scenario-qualification.json').read_text())
    expected = {'jvmTimeZone': 'UTC', 'jdbcConnectionTimeZone': 'UTC',
                'forceConnectionTimeZoneToSession': True}
    if qualification.get('applicationRuntime') != expected:
        raise ValueError('Sealed reads must be qualified with the published UTC application runtime')


def validate_identity(env):
    if env.get('AIRBOB_QUALIFICATION_ONLY') != 'true' or env.get('AIRBOB_DATABASE_BOOTSTRAP') != 'dump':
        raise ValueError('App read qualification requires the disposable dump preparation')
    if not re.fullmatch(re.escape(REGISTRY) + r'/airbob-repo@sha256:[0-9a-f]{64}', env.get('AIRBOB_GROWTH_APP_IMAGE', '')):
        raise ValueError('Expected the exact approved app repository and digest')
    for name, length in [('AIRBOB_GROWTH_APP_COMMIT', 40), ('AIRBOB_GROWTH_APP_JAR_SHA256', 64)]:
        if not re.fullmatch(r'[0-9a-f]{' + str(length) + '}', env.get(name, '')):
            raise ValueError('Missing immutable application identity')


def prepare_k6(work):
    archive = work / 'k6.tar.gz'
    with urllib.request.urlopen(f'https://github.com/grafana/k6/releases/download/v{K6_VERSION}/k6-v{K6_VERSION}-linux-amd64.tar.gz', timeout=45) as source, archive.open('wb') as target:
        shutil.copyfileobj(source, target)
    if hashlib.sha256(archive.read_bytes()).hexdigest() != K6_SHA:
        raise ValueError('k6 archive differs from the reviewed toolchain')
    with tarfile.open(archive) as bundle:
        entries = bundle.getmembers()
        expected = {f'k6-v{K6_VERSION}-linux-amd64', f'k6-v{K6_VERSION}-linux-amd64/k6'}
        if {m.name.rstrip('/') for m in entries} != expected or any(not (m.isdir() or m.isfile()) for m in entries):
            raise ValueError('Unexpected k6 archive entries')
        binary = work / 'k6'
        with bundle.extractfile(f'k6-v{K6_VERSION}-linux-amd64/k6') as source, binary.open('wb') as target:
            shutil.copyfileobj(source, target)
    binary.chmod(0o700)
    return binary


def app_settings(connection, cache_enabled):
    trust = str(connection['truststore'])
    settings = {
        'server.address': '127.0.0.1', 'server.port': 18080,
        'spring.profiles.active': 'aws,performance-lab,test',
        'spring.datasource.url': 'jdbc:mysql://' + connection['host'] + ':3306/airbobdb?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=VERIFY_IDENTITY&trustCertificateKeyStoreType=PKCS12&trustCertificateKeyStorePassword=public-ca-store&trustCertificateKeyStoreUrl=file:' + trust,
        'spring.datasource.username': connection['username'], 'spring.datasource.password': connection['password'],
        'spring.datasource.hikari.maximum-pool-size': 16,
        'spring.jpa.hibernate.ddl-auto': 'none', 'spring.jpa.show-sql': False,
        'spring.flyway.enabled': True, 'spring.flyway.baseline-on-migrate': False,
        'spring.data.redis.host': 'redis-general.lab.airbob.internal', 'spring.data.redis.port': 6379,
        'accommodation.detail-cache.enabled': cache_enabled,
        'accommodation.detail-cache.redis.host': 'redis-cache.lab.airbob.internal',
        'accommodation.detail-cache.redis.port': 6380,
        'reservation.inventory.startup.enabled': False, 'reservation.inventory.seed.enabled': False,
        'reservation.inventory.retention.enabled': False,
        'spring.kafka.listener.auto-startup': False, 'spring.kafka.admin.auto-create': False,
        'spring.kafka.bootstrap-servers': 'kafka.lab.airbob.internal:9092',
        'accommodation.indexing.bootstrap.enabled': False, 'accommodation.indexing.kafka.auto-startup': False,
        'accommodation.detail-cache.invalidation.kafka.auto-startup': False,
        'operator-alert.kafka.auto-startup': False, 'operator-alert.slack.enabled': False,
        'payment.toss.enabled': False, 'payment.toss.secret-key': 'disabled',
        'google.api.enabled': False, 'google.api.key': 'disabled',
        'cloud.aws.s3.write-enabled': False, 'cloud.aws.s3.bucket': 'dummy-bucket',
        'cloud.aws.credentials.access-key': 'dummy', 'cloud.aws.credentials.secret-key': 'dummy',
        'cloud.aws.region.static': 'ap-northeast-2', 'cloud.cloudfront.domain': 'example.test',
        'toss.client-key': 'dummy', 'toss.secret-key': 'dummy',
        'spring.elasticsearch.uris': 'http://elasticsearch.lab.airbob.internal:9200',
        'spring.elasticsearch.username': '', 'spring.elasticsearch.password': '',
        'management.health.elasticsearch.enabled': False,
        'logging.level.org.hibernate.SQL': 'OFF',
    }
    # The aws profile declares separate Flyway placeholders; bind those to the
    # same verified connection rather than leaving environment placeholders.
    settings.update({'spring.flyway.url': settings['spring.datasource.url'],
        'spring.flyway.user': connection['username'], 'spring.flyway.password': connection['password']})
    return settings


def cache_size():
    # Read-only check of the dedicated cache endpoint; never reset session Redis.
    with socket.create_connection(('redis-cache.lab.airbob.internal', 6380), timeout=5) as client:
        client.sendall(b'*1\r\n$6\r\nDBSIZE\r\n')
        with client.makefile('rb') as response:
            value = response.readline(100)
    if not re.fullmatch(rb':[0-9]+\r\n', value):
        raise RuntimeError('Dedicated cache Redis did not return its key count')
    return int(value[1:-2])


def app_environment(settings):
    # The isolated container has no host ~/.aws/config or instance credentials.
    # Framework AWS clients still need a region even with external writes off.
    return ('SPRING_APPLICATION_JSON=' + json.dumps(settings, separators=(',', ':'))
        + '\nJAVA_OPTS=-Xmx512m\nAWS_EC2_METADATA_DISABLED=true\n'
        + 'AWS_REGION=ap-northeast-2\nAWS_DEFAULT_REGION=ap-northeast-2\n'
        + 'AWS_ACCESS_KEY_ID=disabled\nAWS_SECRET_ACCESS_KEY=disabled\n')


def startup_diagnostic(log):
    return {
        'exceptionTypes': sorted(set(re.findall(r'\b(?:org|java|com|software|io)\.[A-Za-z0-9_.$]*(?:Exception|Error)', log))),
        'beans': sorted(set(re.findall(r"Error creating bean with name '([A-Za-z0-9_.$-]+)'", log))),
        'missingRegion': 'Unable to load region' in log,
        'connectionFailure': 'Failed to obtain JDBC Connection' in log,
        'heapExhausted': 'OutOfMemoryError' in log,
    }


def k6_diagnostic(log, password, state):
    # This runner's script logs only closed failure messages and target IDs.
    # Still remove the run credential and cookie/header values before retention.
    safe = log.replace(password, '[redacted]')
    safe = re.sub(r'(?i)SESSION_ID=[^\s;"\']+', 'SESSION_ID=[redacted]', safe)
    safe = re.sub(r'(?im)(Authorization|Cookie):[^\n]*', r'\1: [redacted]', safe)
    return {'logTail': safe[-12000:], 'appState': {key: state.get(key)
        for key in ['Running', 'ExitCode', 'OOMKilled', 'StartedAt', 'FinishedAt']}}


def qualify_application(release, manifest, runtime, private, work, connection, execute, aws):
    validate_identity(os.environ)
    validate_read_runtime(release)
    image = os.environ['AIRBOB_GROWTH_APP_IMAGE']
    credential = aws('ecr', 'get-authorization-token')['authorizationData'][0]
    import base64
    login = base64.b64decode(credential['authorizationToken']).split(b':', 1)
    if login[0] != b'AWS' or credential['proxyEndpoint'] != 'https://' + REGISTRY:
        raise ValueError('Unexpected ECR login authority')
    docker_env = dict(os.environ, DOCKER_CONFIG=str(private / 'docker-login'))
    execute(['docker', 'login', '--username', 'AWS', '--password-stdin', REGISTRY], input=login[1], env=docker_env)
    execute(['docker', 'pull', image], env=docker_env, timeout=300)
    actual_jar_sha = execute(['docker', 'run', '--rm', '--entrypoint', 'sha256sum', image, '/app/app.jar'], env=docker_env).decode().split()[0]
    if actual_jar_sha != os.environ['AIRBOB_GROWTH_APP_JAR_SHA256']:
        raise ValueError('Published image JAR differs from the locally qualified JAR')
    k6 = prepare_k6(work)
    spec = importlib.util.spec_from_file_location('growth_probe', Path(__file__).with_name('probe-growth-reads.py'))
    probe = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(probe)
    targets = json.loads((release / 'read-scenarios.json').read_text())['targets']
    accounts = {row['memberId']: row['account']['email'] for row in targets if row['account']}
    if not 1 <= len(accounts) <= 25 or any(not isinstance(k, int) or k < 1 or not re.fullmatch(r'growth-[a-z0-9-]+@example\.test', v) for k, v in accounts.items()):
        raise ValueError('Invalid bounded synthetic read accounts')
    def sql(text):
        return execute(['mysql', '--defaults-extra-file=' + str(private / 'client.cnf'), '--batch', '--raw', '--skip-column-names', 'airbobdb'], input=text.encode()).decode().strip()
    ids = ','.join(str(x) for x in sorted(accounts))
    saved = [line.split('\t') for line in sql(f'SELECT id,email,password FROM member WHERE id IN ({ids}) ORDER BY id').splitlines()]
    if {int(row[0]): row[1] for row in saved} != accounts or any(not re.fullmatch(r'\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}', row[2]) for row in saved):
        raise ValueError('Restored account identities differ from the sealed reads')
    password = secrets.token_hex(32)
    env = dict(docker_env, AIRBOB_ETL_BENCHMARK_PASSWORD=password)
    hash_source = private / 'PasswordHash.java'
    hash_source.write_text('class PasswordHash { public static void main(String[] args) { System.out.print(org.mindrot.jbcrypt.BCrypt.hashpw(System.getenv("AIRBOB_ETL_BENCHMARK_PASSWORD"),org.mindrot.jbcrypt.BCrypt.gensalt())); }}')
    hashed = execute(['java', '-cp', str(runtime / 'runtime/lib/jbcrypt-0.4.jar'), str(hash_source)], env=env).decode()
    if not re.fullmatch(r'\$2a\$[0-9]{2}\$[./A-Za-z0-9]{53}', hashed):
        raise ValueError('Invalid run password hash')
    name = 'airbob-growth-read-' + os.environ['AIRBOB_RUN_ID']
    # An already-running target must never be overwritten by this helper.
    if execute(['docker', 'ps', '-aq', '--filter', 'name=^/' + name + '$'], env=docker_env).strip():
        raise ValueError('Read qualification container already exists')
    results = []
    detail_ids = [int(value) for value in sql("SELECT id FROM accommodation WHERE status='PUBLISHED' ORDER BY id LIMIT 5").splitlines()]
    if len(detail_ids) != 5:
        raise ValueError('Five published detail targets are required')
    detail_baseline = None
    try:
        sql(f"UPDATE member SET password='{hashed}' WHERE id IN ({ids})")
        for cache in (False, True):
            cache_before = cache_size()
            if cache_before != 0:
                raise ValueError('Fresh qualification cache is not empty')
            config = private / 'app.env'
            config.write_text(app_environment(app_settings(connection, cache)))
            config.chmod(0o600)
            execute(['docker', 'run', '-d', '--name', name, '--network', 'host', '--memory', '768m', '--env-file', str(config),
                     '--mount', f'type=bind,source={private},target={private},readonly', image], env=docker_env)
            try:
                for attempt in range(120):
                    if execute(['docker', 'inspect', '--format', '{{.State.Running}}', name], env=docker_env).strip() != b'true':
                        raise RuntimeError('Qualification app stopped during startup')
                    try:
                        with urllib.request.urlopen('http://127.0.0.1:18080/actuator/health', timeout=3) as response:
                            if json.load(response)['status'] == 'UP':
                                break
                    except (OSError, ValueError):
                        pass
                    time.sleep(1)
                else:
                    raise RuntimeError('Qualification app startup timeout')
                observation = probe.probe(release, manifest['source']['datasetId'], 'http://127.0.0.1:18080', password)
                if not observation['passed']:
                    # Preserve only IDs, status and hashes so a mismatch can be
                    # diagnosed without exporting response bodies or sessions.
                    mismatch = work / ('read-mismatch-' + str(cache).lower() + '.json')
                    mismatch.write_text(json.dumps(dict(observation, runId=os.environ['AIRBOB_RUN_ID']), indent=2) + '\n')
                    try:
                        aws('s3api', 'put-object', '--bucket', os.environ['AIRBOB_EVIDENCE_BUCKET'],
                            '--key', 'data-bootstrap/' + os.environ['AIRBOB_RUN_ID'] + '/' + mismatch.name,
                            '--body', str(mismatch), '--if-none-match', '*', '--tagging', 'Retention=summary',
                            '--content-type', 'application/json', '--server-side-encryption', 'AES256')
                    except Exception:
                        pass
                    raise RuntimeError('AWS app differs from sealed read responses')
                details = []
                for listing_id in detail_ids:
                    hashes = []
                    for repeat in range(2):
                        with urllib.request.urlopen(f'http://127.0.0.1:18080/api/v1/accommodations/{listing_id}', timeout=30) as response:
                            body = json.load(response)
                            if response.status != 200 or not body.get('data'):
                                raise RuntimeError('Published accommodation detail failed')
                            hashes.append(probe.digest(body))
                    if len(set(hashes)) != 1:
                        raise RuntimeError('Repeated accommodation detail changed')
                    details.append({'id': listing_id, 'responseSha256': hashes[0]})
                if detail_baseline is None:
                    detail_baseline = details
                elif detail_baseline != details:
                    raise RuntimeError('Cached details differ from uncached details')
                cache_after = cache_size()
                if (cache and cache_after < len(detail_ids)) or (not cache and cache_after != 0):
                    raise RuntimeError('Dedicated cache contents do not match the selected mode')
                summary = work / ('k6-cache-' + str(cache).lower() + '.json')
                kenv = dict(env, GROWTH_RELEASE_DIR=str(release), BASE_URL='http://127.0.0.1:18080', EXPECTED_DATASET_ID=manifest['source']['datasetId'], EXPECTED_MYSQL_VERSION='8.4.11')
                print(json.dumps({'stage': 'sealed-reads-and-details-passed', 'cacheEnabled': cache,
                    'targets': observation['targetCount'], 'dedicatedCacheKeys': cache_after}), flush=True)
                k6_log = work / ('k6-cache-' + str(cache).lower() + '.log')
                try:
                    with k6_log.open('wb') as log:
                        execute([str(k6), 'run', '--quiet', '--summary-export', str(summary), str(Path(__file__).parent / 'load-test/k6/traffic/growth-dataset-read.js')], env=kenv, timeout=180, output=log)
                except Exception:
                    try:
                        state = json.loads(execute(['docker', 'inspect', '--format', '{{json .State}}', name], env=docker_env))
                        diagnostic = k6_diagnostic(k6_log.read_text(errors='replace'), password, state)
                        diagnostic.update(runId=os.environ['AIRBOB_RUN_ID'], cacheEnabled=cache,
                            inheritedK6EnvironmentKeys=sorted(k for k in kenv if k.startswith('K6_')))
                        path = work / ('k6-diagnostic-' + str(cache).lower() + '.json')
                        path.write_text(json.dumps(diagnostic, indent=2) + '\n')
                        aws('s3api', 'put-object', '--bucket', os.environ['AIRBOB_EVIDENCE_BUCKET'],
                            '--key', 'data-bootstrap/' + os.environ['AIRBOB_RUN_ID'] + '/' + path.name,
                            '--body', str(path), '--if-none-match', '*', '--tagging', 'Retention=summary',
                            '--content-type', 'application/json', '--server-side-encryption', 'AES256')
                    except Exception:
                        pass
                    raise
                results.append({'cacheEnabled': cache, 'reads': observation, 'repeatedDetailTargets': details,
                    'dedicatedCacheKeysBefore': cache_before, 'dedicatedCacheKeysAfterDetails': cache_after,
                    'k6Metrics': json.loads(summary.read_text())['metrics']})
            except BaseException:
                # Publish only closed diagnostic fields; application log text and
                # configuration values remain private on the disposable host.
                try:
                    diagnostic_path = work / ('app-startup-diagnostic-' + str(cache).lower() + '.json')
                    log = execute(['docker', 'logs', name], env=docker_env).decode(errors='replace')
                    diagnostic = startup_diagnostic(log)
                    diagnostic.update(runId=os.environ['AIRBOB_RUN_ID'], cacheEnabled=cache)
                    diagnostic_path.write_text(json.dumps(diagnostic, indent=2) + '\n')
                    aws('s3api', 'put-object', '--bucket', os.environ['AIRBOB_EVIDENCE_BUCKET'],
                        '--key', 'data-bootstrap/' + os.environ['AIRBOB_RUN_ID'] + '/' + diagnostic_path.name,
                        '--body', str(diagnostic_path), '--if-none-match', '*', '--tagging', 'Retention=summary',
                        '--content-type', 'application/json', '--server-side-encryption', 'AES256')
                except Exception:
                    pass  # Diagnostic delivery must not replace the original app failure.
                raise
            finally:
                try:
                    with (work / ('app-cache-' + str(cache).lower() + '.log')).open('wb') as log:
                        execute(['docker', 'logs', name], env=docker_env, output=log)
                finally:
                    execute(['docker', 'rm', '-f', name], env=docker_env)
    finally:
        # The source proof is checked again by the caller, including passwords.
        sql('START TRANSACTION;' + ''.join(f"UPDATE member SET password='{row[2]}' WHERE id={int(row[0])};" for row in saved) + 'COMMIT;')
    return {'schemaVersion': 1, 'kind': 'growth-app-read-qualification', 'passed': True,
        'runId': os.environ['AIRBOB_RUN_ID'], 'datasetId': manifest['source']['datasetId'],
        'rdsResourceId': os.environ['AIRBOB_RDS_RESOURCE_ID'],
        'datasetManifestSha256': os.environ['AIRBOB_DATASET_MANIFEST_SHA256'],
        'appCommit': os.environ['AIRBOB_GROWTH_APP_COMMIT'], 'appImage': image, 'appJarSha256': actual_jar_sha,
        'helperSha256': {name: hashlib.sha256((Path(__file__).parent / name).read_bytes()).hexdigest()
            for name in ['growth_app_read.py', 'probe-growth-reads.py', 'load-test/k6/traffic/growth-dataset-read.js',
                         'load-test/k6/lib/benchmark-dataset-v3.js']},
        'results': results, 'scope': 'private host loopback HTTP using real RDS and distinct Redis endpoints',
        'asgExecuted': False, 'albExecuted': False, 'cdcExecuted': False, 'runtimeWriteScenariosExecuted': False}
