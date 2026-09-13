"""Explicit B/V28 AWS normal-service admission and fresh CDC bootstrap.

Runs on the retained preparation/Connect host under the existing controller lease.
It does not restore SQL/search, enable ASG capacity, reset Redis, or publish images.
Public receipts prove dependencies before app start; they never claim a live app.
"""
from __future__ import annotations
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import time
import urllib.request
import growth_b_app_runtime as app_runtime

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
EVIDENCE = 'airbob-performance-lab-evidence-' + ACCOUNT
KIND = 'global-growth-b-aws-service'
READY = 'GLOBAL_B_DEPENDENCIES_VERIFIED'
DEBEZIUM_PLUGIN_VERSION = '3.0.8.Final'
DEBEZIUM_PLUGIN_JAR_SHA256 = '6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59'
TOOLS = ('growth_b_service.py', 'growth_b_prepare.py', 'growth_b_aws_restore.py', 'growth_b_aws_contract.py',
         'growth_b_contract.py', 'growth_b_runtime.py', 'growth_b_inventory.py', 'growth_b_search.py', 'growth_b_app_runtime.py')
TOPICS = tuple(stream + '.events' + suffix for stream in ('PAYMENT_OPERATION', 'ACCOMMODATION_INDEX',
    'ACCOMMODATION_CACHE', 'OPERATOR_ALERT') for suffix in ('', '.RETRY', '.DLT'))


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def write(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'w') as output:
        json.dump(value, output, sort_keys=True, indent=2); output.write('\n')


def ref(item, prefix):
    require(isinstance(item, dict) and set(item) == {'key', 'versionId', 'sha256', 'bytes'}, 'Exact immutable object reference required')
    require(isinstance(item['key'], str) and item['key'].startswith(prefix)
            and all(part not in ('', '.', '..') for part in item['key'].split('/'))
            and re.fullmatch(r'[A-Za-z0-9_./-]+', item['key']), 'Object is outside its selected sibling/evidence prefix')
    require(isinstance(item['versionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', item['versionId'])
            and item['versionId'] not in ('null', 'None') and re.fullmatch(r'[0-9a-f]{64}', item['sha256'])
            and type(item['bytes']) is int and 0 < item['bytes'] <= 20 * 1024**2,
            'Object version, SHA, or metadata size is invalid')
    return item


def cdc_identity(run_id, server_uuid):
    suffix = hashlib.sha256((run_id + ':' + server_uuid).encode()).hexdigest()[:20]
    prefix = 'airbob_b_' + suffix
    return {'connectorName': 'airbob-b-' + suffix, 'topicPrefix': prefix,
        'schemaHistoryTopic': 'schemahistory.' + prefix, 'databaseServerId': 100000 + int(suffix[:7], 16),
        'username': 'b_cdc_' + suffix}


def validate_manifest(value, dataset_id, run_id, release, sources=None):
    require(re.fullmatch(r'global-growth-b-[0-9a-f]{16}', dataset_id)
            and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', run_id)
            and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}', release), 'Invalid B service coordinates')
    require(set(value) == {'schemaVersion', 'kind', 'datasetId', 'runId', 'serviceRelease', 'account', 'region',
            'mysql', 'rds', 'application', 'appRuntimeBinding', 'preparation', 'search', 'debezium', 'consumerTools', 'toolSources', 'cdc'}, 'B service manifest fields differ')
    require(value['schemaVersion'] == 1 and value['kind'] == KIND and value['datasetId'] == dataset_id
            and value['runId'] == run_id and value['serviceRelease'] == release and value['account'] == ACCOUNT
            and value['region'] == REGION and value['mysql'] == {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
            'B service schema or target differs')
    rds = value['rds']
    require(set(rds) == {'identifier', 'resourceId', 'serverUuid'} and rds['identifier'] == 'airbob-' + run_id
            and re.fullmatch(r'db-[A-Z0-9]{24}', rds['resourceId'])
            and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', rds['serverUuid']), 'Exact prepared RDS identity required')
    app = value['application']
    require(set(app) == {'mainCommit', 'image', 'appJarSha256', 'migrationFilesSha256'}
            and re.fullmatch(r'[0-9a-f]{40}', app['mainCommit'])
            and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/airbob-repo@sha256:[0-9a-f]{64}', app['image'])
            and all(re.fullmatch(r'[0-9a-f]{64}', app[key]) for key in ('appJarSha256', 'migrationFilesSha256')),
            'Current-main immutable application and V28 migration binding required')
    runtime_ref = value['appRuntimeBinding']
    ref(runtime_ref, f'datasets/{dataset_id}-aws-service/{release}/files/')
    require(runtime_ref['key'] == f'datasets/{dataset_id}-aws-service/{release}/files/app-runtime-binding.json',
            'Exact sibling application runtime binding required')
    debezium = value['debezium']
    require(set(debezium) == {'image', 'buildCommit', 'pluginVersion', 'pluginJarSha256', 'connectVersion'}
            and debezium['pluginVersion'] == DEBEZIUM_PLUGIN_VERSION
            and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/airbob-infra/debezium@sha256:[0-9a-f]{64}', debezium['image'])
            and re.fullmatch(r'[0-9a-f]{40}', debezium['buildCommit'])
            and debezium['pluginJarSha256'] == DEBEZIUM_PLUGIN_JAR_SHA256
            and debezium['connectVersion'] == '3.7.0',
            'B requires its separate immutable Debezium 3.0.8 plugin and Kafka Connect identity')
    prep = value['preparation']
    require(set(prep) == {'receipt', 'restoreConfigSha256', 'restoreReceiptSha256', 'preparedFingerprintSha256', 'rdsCaBundle'}, 'Preparation binding fields differ')
    ref(prep['receipt'], f'data-bootstrap/{run_id}/')
    ref(prep['rdsCaBundle'], f'datasets/{dataset_id}-aws-preparation/files/')
    require(prep['rdsCaBundle']['key'].endswith('/' + prep['rdsCaBundle']['sha256'] + '-rds-ca.pem') and all(re.fullmatch(r'[0-9a-f]{64}', prep[key])
            for key in ('restoreConfigSha256', 'restoreReceiptSha256', 'preparedFingerprintSha256')), 'Preparation hashes differ')
    search = value['search']
    require(set(search) == {'snapshotRelease', 'transport', 'restoreReceipt', 'documentFingerprint', 'image'}, 'Search fields differ')
    require(re.fullmatch(re.escape(dataset_id) + r'-search-[a-z0-9][a-z0-9._-]{0,60}', search['snapshotRelease']), 'Search release differs')
    prefix = f"datasets/{dataset_id}-search/{search['snapshotRelease']}/"
    ref(search['transport'], prefix)
    require(search['transport']['key'] == prefix + 'transport-manifest.json', 'Search completion marker required')
    if search['restoreReceipt'] is not None:
        ref(search['restoreReceipt'], f'data-bootstrap/{run_id}/')
    fingerprint = search['documentFingerprint']
    require(set(fingerprint) == {'algorithm', 'documents', 'contentSha256', 'identityPairsSha256', 'mappingSha256', 'indexSemanticsSha256'}
            and fingerprint['algorithm'] == 'airbob-es-accommodation-id-asc-length-prefixed-json-v1'
            and type(fingerprint['documents']) is int and fingerprint['documents'] > 0
            and all(re.fullmatch(r'[0-9a-f]{64}', fingerprint[key]) for key in fingerprint if key.endswith('Sha256')),
            'Complete sealed search fingerprint required')
    require(re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/airbob-infra/elasticsearch@sha256:[0-9a-f]{64}', search['image']), 'Pinned AWS ES image required')
    ref(value['consumerTools'], f'datasets/{dataset_id}-aws-service/{release}/files/')
    require(value['consumerTools']['key'].endswith('/consumer-tools.tar.gz') and set(value['toolSources']) == set(TOOLS)
            and all(re.fullmatch(r'[0-9a-f]{64}', item) for item in value['toolSources'].values()), 'Exact B service helper inventory required')
    if sources is not None:
        require(value['toolSources'] == sources, 'B service helper bytes differ from review')
    require(value['cdc'] == cdc_identity(run_id, rds['serverUuid']), 'CDC identity must be fresh for this exact run and RDS UUID')
    return value


def validate_app_runtime_projection(value, application):
    require(isinstance(value, dict) and set(value) == {'imageJarSha256', 'runtimeDigest', 'runtimeContract',
            'runtimeRevision', 'sourceJarSha256', 'image', 'mainCommit'}
            and value['runtimeContract'] == app_runtime.CONTRACT
            and value['runtimeRevision'] == value['mainCommit'] == application['mainCommit']
            and value['sourceJarSha256'] == application['appJarSha256'] and value['image'] == application['image']
            and all(isinstance(value[key], str) and re.fullmatch(r'[0-9a-f]{64}', value[key])
                    for key in ('imageJarSha256', 'runtimeDigest', 'sourceJarSha256')),
            'Application runtime projection differs from the sealed source and actual image')
    app_runtime.verify_running_jar(value, value['imageJarSha256'])
    return value


def validate_readiness(receipt, manifest, manifest_sha):
    require(receipt.get('schemaVersion') == 1 and receipt.get('kind') == KIND + '-readiness'
            and receipt.get('state') == READY and receipt.get('manifestSha256') == manifest_sha,
            'B readiness schema/state/trust anchor differs')
    for key in ('runId', 'datasetId', 'serviceRelease', 'mysql', 'rds', 'application', 'appRuntimeBinding', 'debezium', 'cdc', 'toolSources'):
        require(receipt.get(key) == manifest[key], 'B readiness selected identity differs: ' + key)
    validate_app_runtime_projection(receipt.get('appRuntime'), manifest['application'])
    require(receipt.get('preparationReceipt') == manifest['preparation']['receipt']
            and receipt.get('preparedFingerprintSha256') == manifest['preparation']['preparedFingerprintSha256']
            and receipt.get('searchRestoreReceipt') == manifest['search']['restoreReceipt']
            and receipt.get('searchRestoreReceipt') is not None
            and receipt.get('searchTransport') == manifest['search']['transport']
            and receipt.get('searchFingerprint') == manifest['search']['documentFingerprint']
            and receipt.get('topics') == list(TOPICS)
            and all(receipt.get(key) is True for key in ('redisSeparate', 'debeziumVerified', 'cdcRunning', 'heartbeatObserved', 'writersStopped'))
            and all(receipt.get(key) is False for key in ('redisReset', 'applicationStarted', 'deploymentReady')),
            'B readiness dependency or pre-application state differs')
    return {'state': READY, 'applicationAdmitted': True, 'deploymentReady': False}


def fetch(aws, reference, destination, bucket):
    require(not Path(destination).exists(), 'Pinned fetch output already exists')
    result = aws.call('s3api', 'get-object', '--bucket', bucket, '--key', reference['key'],
        '--version-id', reference['versionId'], str(destination))
    Path(destination).chmod(0o600)
    require(result.get('VersionId') == reference['versionId'] and Path(destination).stat().st_size == reference['bytes']
            and sha(destination) == reference['sha256'], 'Pinned service bytes or version differ')
    return read(destination)


def request(method, path, value=None):
    payload = None if value is None else json.dumps(value).encode()
    req = urllib.request.Request('http://127.0.0.1:8083' + path, data=payload, method=method,
        headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.loads(response.read() or '{}')


def connector_config(manifest, endpoint, password):
    cdc = manifest['cdc']
    return {'connector.class': 'io.debezium.connector.mysql.MySqlConnector', 'tasks.max': '1',
        'database.hostname': endpoint, 'database.port': '3306', 'database.user': cdc['username'],
        'database.password': password, 'database.server.id': str(cdc['databaseServerId']),
        'database.connectionTimeZone': 'UTC', 'database.ssl.mode': 'required',
        'database.include.list': 'airbobdb', 'table.include.list': 'airbobdb.outbox', 'topic.prefix': cdc['topicPrefix'],
        'snapshot.mode': 'no_data', 'heartbeat.interval.ms': '10000', 'include.schema.changes': 'false',
        'tombstones.on.delete': 'false', 'schema.history.internal.kafka.bootstrap.servers': 'kafka.lab.airbob.internal:9092',
        'schema.history.internal.kafka.topic': cdc['schemaHistoryTopic'],
        'key.converter': 'org.apache.kafka.connect.storage.StringConverter',
        'value.converter': 'org.apache.kafka.connect.storage.StringConverter',
        'header.converter': 'org.apache.kafka.connect.storage.StringConverter',
        'predicates': 'IsOutboxTable', 'predicates.IsOutboxTable.type': 'org.apache.kafka.connect.transforms.predicates.TopicNameMatches',
        'predicates.IsOutboxTable.pattern': cdc['topicPrefix'] + r'\.airbobdb\.outbox',
        'transforms': 'outbox', 'transforms.outbox.type': 'io.debezium.transforms.outbox.EventRouter',
        'transforms.outbox.predicate': 'IsOutboxTable', 'transforms.outbox.table.op.invalid.behavior': 'fatal',
        'transforms.outbox.route.by.field': 'destination', 'transforms.outbox.route.topic.replacement': '${routedByValue}',
        'transforms.outbox.table.field.event.id': 'event_id', 'transforms.outbox.table.field.event.key': 'partition_key',
        'transforms.outbox.table.field.event.payload': 'payload', 'transforms.outbox.table.field.event.timestamp': 'occurred_at',
        'transforms.outbox.table.fields.additional.placement': 'event_type:header:eventType,event_version:header:eventVersion,aggregate_type:header:aggregateType,aggregate_id:header:aggregateId'}


def verify_debezium(identity, command, guard):
    def run(*args):
        return command(list(args), guard=guard).decode().strip()
    ids = run('docker', 'ps', '--quiet', '--filter', 'label=com.docker.compose.service=debezium').splitlines()
    require(len(ids) == 1 and re.fullmatch(r'[0-9a-f]{12,64}', ids[0]), 'Exactly one running B Connect container required')
    container = ids[0]
    require(run('docker', 'inspect', '--format', '{{.Config.Image}}', container) == identity['image'],
            'Running Connect image differs from the reviewed B digest')
    require(run('docker', 'image', 'inspect', '--format', '{{index .Config.Labels "org.opencontainers.image.revision"}}', identity['image']) == identity['buildCommit'],
            'B Connect image build commit differs')
    jar = '/opt/kafka/connect-plugins/debezium-mysql/debezium-connector-mysql-' + identity['pluginVersion'] + '.jar'
    require(run('docker', 'exec', container, 'sha256sum', jar).split()[0] == identity['pluginJarSha256'],
            'Installed MySQL connector JAR differs from the reviewed B artifact')
    plugins = request('GET', '/connector-plugins')
    matching = [plugin for plugin in plugins if plugin.get('class') == 'io.debezium.connector.mysql.MySqlConnector']
    require(len(matching) == 1 and matching[0].get('version') == identity['pluginVersion']
            and matching[0].get('type') == 'source', 'Live MySQL connector is not the selected Debezium plugin')
    require(request('GET', '/').get('version') == identity['connectVersion'], 'Live Kafka Connect runtime differs')
    guard(force=True)
    return identity


def validate_preparation(proof, manifest, source_mode, provenance_sha):
    require(proof.get('kind') == 'global-growth-b-aws-data-only-preparation'
            and proof.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED'
            and proof.get('datasetId') == manifest['datasetId'] and proof.get('runId') == manifest['runId']
            and proof['rdsResourceId'] == manifest['rds']['resourceId'] and proof['serverUuid'] == manifest['rds']['serverUuid']
            and proof['restoreReceiptSha256'] == manifest['preparation']['restoreReceiptSha256']
            and proof['preparation']['preparedFingerprintSha256'] == manifest['preparation']['preparedFingerprintSha256']
            and proof['deploymentReady'] is False and proof['applicationLeftRunning'] is False,
            'Successful exact B preparation required')
    require(source_mode in ('dump', 'snapshot'), 'Explicit B database source mode required')
    if source_mode == 'snapshot':
        evidence = proof.get('snapshotRestoreEvidence', {})
        require(isinstance(provenance_sha, str) and re.fullmatch(r'[0-9a-f]{64}', provenance_sha)
                and proof.get('sourceMode') == 'verified-global-b-snapshot'
                and proof.get('snapshotProvenanceSha256') == provenance_sha
                and evidence.get('evidenceSource') == 'controller-pinned-cloudtrail-event'
                and isinstance(evidence.get('eventSha256'), str) and re.fullmatch(r'[0-9a-f]{64}', evidence['eventSha256']),
                'The actual snapshot preparation must bind this exact provenance and restore event')
    else:
        require(provenance_sha is None and 'snapshotProvenanceSha256' not in proof
                and proof.get('sourceMode') in (None, 'dump'), 'Dump preparation cannot adopt snapshot lineage')
    return proof


def bootstrap(manifest, context, root, output):
    import growth_b_aws_restore as restore
    import growth_b_runtime as runtime_gate
    import growth_b_search as search
    root, output = Path(root), Path(output)
    require(not (root / 'STOP').exists() and not output.exists(), 'Stopped or previously attempted B service bootstrap')
    output.mkdir(mode=0o700)
    aws = restore.Aws(); guard = restore.Lease(aws, context['lease']); guard(force=True)
    require(context['runId'] == manifest['runId'] and context['rds']['resourceId'] == manifest['rds']['resourceId']
            and context['rds']['identifier'] == manifest['rds']['identifier'], 'Service target differs')
    require(sha(root / 'restore-config.json') == manifest['preparation']['restoreConfigSha256'], 'Retained preparation config changed')
    config = restore.configuration(root / 'restore-config.json')
    for key, value in manifest['rds'].items():
        require(config['rds'][key] == value, 'Retained RDS identity differs')
    config['lease'] = context['lease']; config['writerAsgNames'] = ['airbob-' + manifest['runId'] + '-app']
    config.pop('operation', None); config.pop('resumeReceipt', None)
    envelope = restore.validate_inputs(config)
    require(envelope['appJarSha256'] == manifest['application']['appJarSha256']
            and envelope['objects']['migration-files.json']['sha256'] == manifest['application']['migrationFilesSha256'], 'Application preparation lineage differs')
    runtime_binding_file = output / 'app-runtime-binding.json'
    fetch(aws, manifest['appRuntimeBinding'], runtime_binding_file, BUCKET)
    verified_app_runtime = app_runtime.validate_runtime_binding(runtime_binding_file, manifest['application']['image'],
            manifest['application']['mainCommit'], envelope['appJarSha256'])
    validate_app_runtime_projection(verified_app_runtime, manifest['application'])
    prep = fetch(aws, manifest['preparation']['receipt'], output / 'preparation.json', EVIDENCE)
    validate_preparation(prep, manifest, context['databaseBootstrap'], context['snapshotProvenanceSha256'])
    verify_debezium(manifest['debezium'], restore.command, guard)
    require(manifest['search']['restoreReceipt'] is not None, 'An actual S3 native restore receipt is required before CDC')
    restored = fetch(aws, manifest['search']['restoreReceipt'], output / 'search-restore.json', EVIDENCE)
    transport = fetch(aws, manifest['search']['transport'], output / 'transport.json', BUCKET)
    require(transport.get('schemaVersion') == 1 and transport.get('kind') == 'global-growth-b-search-transport'
            and transport.get('bucket') == BUCKET and transport.get('region') == REGION
            and transport.get('datasetId') == manifest['datasetId'] and transport['source']['appJarSha256'] == manifest['application']['appJarSha256']
            and restored.get('state') == 'SEARCH_RESTORED_AND_ACTIVATED' and restored['datasetId'] == manifest['datasetId']
            and restored['snapshotRelease'] == manifest['search']['snapshotRelease'] and restored['snapshotUuid'] == transport['snapshotUuid']
            and restored['mysql']['serverUuid'] == manifest['rds']['serverUuid']
            and restored['fullDocumentFingerprint'] == manifest['search']['documentFingerprint']
            and restored['nativeTransport']['manifestSha256'] == manifest['search']['transport']['sha256']
            and restored['nativeTransport']['exactVersionBytesVerified'] is True
            and restored['nativeTransport']['sourceSealsChanged'] is False
            and restored['repositoryReadOnly'] is True and restored['nativeInventoryUnchanged'] is True
            and restored['allDocumentSourceFieldsEqual'] is True and restored['elasticsearch']['image'] == manifest['search']['image'],
            'Actual unchanged native S3 restore and full document equality proof required')
    runtime = root / 'bootstrap-runtime'
    qualification = runtime_gate.qualify_runtime(Path(config['release']), runtime, output / 'host-runtime.json',
        expected_checks={name: item['sha256'] for name, item in envelope['objects'].items()}, java_home=root / 'toolchain/jdk')
    with runtime_gate.activated_runtime(qualification):
        live = restore.live_rds(aws, config)
        secret_dir = restore.private_directory(output / '.connection')
        try:
            db, environment = restore.connection(config, aws, live, secret_dir, guard)
            with restore.exclusive_database(db):
                require(db.scalar('SELECT @@server_uuid', False) == manifest['rds']['serverUuid'] and
                    db.scalar("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == '28'
                    and db.scalar('SELECT COUNT(*) FROM outbox') == '0', 'RDS UUID/V28 or empty pre-CDC outbox differs')
                full = restore.fingerprint(runtime, Path(config['release']), environment, output / 'prepared-fingerprint.json', db.timeout, db.guard)
                require(sha(output / 'prepared-fingerprint.json') == manifest['preparation']['preparedFingerprintSha256'], 'Prepared full rows/DDL changed before service admission')
                es = search.Elasticsearch({'url': 'http://elasticsearch.lab.airbob.internal:9200', 'version': '8.18.8', 'image': manifest['search']['image']})
                require(es.api('GET', '/')['cluster_uuid'] == restored['elasticsearch']['clusterUuid']
                        and es.alias() == restored['restoredIndex'], 'Live restored search target changed')
                observed = es.fingerprint(restored['restoredIndex'])
                require(observed == manifest['search']['documentFingerprint'], 'Live complete search content/mapping differs')
                # No FLUSHDB: general sessions/coupon state and detail-cache Redis are distinct live dependencies.
                redis = []
                for host, port in [('redis-general.lab.airbob.internal', 6379), ('redis-cache.lab.airbob.internal', 6380)]:
                    raw = restore.command(['docker', 'run', '--rm', '--network', 'host', config['redisImage'],
                        'redis-cli', '-h', host, '-p', str(port), '--raw', 'INFO', 'server'], guard=db.guard).decode()
                    match = re.search(r'^run_id:([0-9a-f]{40})\r?$', raw, re.M)
                    require(match is not None, 'Redis runtime identity is unavailable'); redis.append(match.group(1))
                require(redis[0] != redis[1], 'General and dedicated cache Redis must be separate processes')
                require(request('GET', '/connectors') == [], 'Fresh B bootstrap requires no previously configured connector')
                compose = ['docker', 'compose', '--env-file', '/etc/airbob/images.env', '-f', '/opt/airbob/release/infra/aws/bundles/debezium/compose.yml']
                def kafka(tool, *args):
                    return restore.command(compose + ['exec', '--no-TTY', '-e', 'KAFKA_OPTS=', '-e', 'KAFKA_HEAP_OPTS=-Xms64m -Xmx128m',
                        'debezium', '/opt/kafka/bin/' + tool + '.sh', '--bootstrap-server', 'kafka.lab.airbob.internal:9092', *args],
                        guard=db.guard, timeout=120).decode()
                cdc = manifest['cdc']
                broker_config = kafka('kafka-configs', '--entity-type', 'brokers', '--entity-name', '1', '--describe', '--all')
                require('auto.create.topics.enable=false' in broker_config, 'Kafka automatic topic creation must remain disabled')
                current_topics = set(kafka('kafka-topics', '--list').splitlines())
                unique_topics = [cdc['schemaHistoryTopic'], '__debezium-heartbeat.' + cdc['topicPrefix']]
                require(not set(unique_topics) & current_topics, 'B CDC history/heartbeat identity was used before')
                for topic in TOPICS:
                    kafka('kafka-topics', '--create', '--if-not-exists', '--topic', topic, '--partitions', '3', '--replication-factor', '1', '--config', 'retention.ms=86400000')
                    description = kafka('kafka-topics', '--describe', '--topic', topic)
                    require('PartitionCount: 3' in description and 'ReplicationFactor: 1' in description, 'Canonical topic partition/replica contract differs')
                    config_text = kafka('kafka-configs', '--entity-type', 'topics', '--entity-name', topic, '--describe')
                    require(re.search(r'\bretention.ms=86400000\b', config_text), 'Canonical topic retention differs')
                    offsets = kafka('kafka-get-offsets', '--topic', topic, '--time', 'latest').splitlines()
                    require(set(offsets) == {topic + ':' + str(n) + ':0' for n in range(3)}, 'Business stream already contains records')
                kafka('kafka-topics', '--create', '--topic', unique_topics[0], '--partitions', '1', '--replication-factor', '1', '--config', 'retention.ms=-1', '--config', 'retention.bytes=-1')
                kafka('kafka-topics', '--create', '--topic', unique_topics[1], '--partitions', '1', '--replication-factor', '1', '--config', 'retention.ms=86400000')
                require(db.scalar('SELECT COUNT(*) FROM mysql.user WHERE user=' + db.literal(cdc['username']), False) == '0', 'CDC database identity already exists')
                password = secrets.token_urlsafe(36)
                db.execute('CREATE USER ' + db.literal(cdc['username']) + "@'%' IDENTIFIED BY " + db.literal(password) + ' REQUIRE SSL;', False)
                db.execute('GRANT SELECT, RELOAD, SHOW DATABASES, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO ' + db.literal(cdc['username']) + "@'%';", False)
                secret_file = secret_dir / 'cdc.json'; write(secret_file, {'username': cdc['username'], 'password': password})
                aws.call('secretsmanager', 'put-secret-value', '--secret-id', context['debeziumSecretArn'], '--secret-string', 'file://' + str(secret_file))
                desired = connector_config(manifest, config['rds']['endpoint'], password)
                db.guard(force=True); restore.live_rds(aws, config)
                request('POST', '/connectors', {'name': cdc['connectorName'], 'config': desired})
                for _ in range(120):
                    db.guard(force=True)
                    status = request('GET', '/connectors/' + cdc['connectorName'] + '/status')
                    if status.get('connector', {}).get('state') == 'RUNNING' and len(status.get('tasks', [])) == 1 and status['tasks'][0]['state'] == 'RUNNING':
                        break
                    time.sleep(5)
                else:
                    raise ValueError('Fresh B connector did not become RUNNING')
                for _ in range(60):
                    db.guard(force=True)
                    heartbeat = kafka('kafka-get-offsets', '--topic', unique_topics[1], '--time', 'latest').splitlines()
                    if len(heartbeat) == 1 and heartbeat[0].startswith(unique_topics[1] + ':0:') and int(heartbeat[0].rsplit(':', 1)[1]) > 0:
                        break
                    time.sleep(5)
                else:
                    raise ValueError('Fresh CDC heartbeat did not reach Kafka')
                actual = request('GET', '/connectors/' + cdc['connectorName'] + '/config')
                require(all(actual.get(key) == value for key, value in desired.items()), 'Live CDC configuration differs')
                require(db.scalar('SELECT COUNT(*) FROM outbox') == '0' and es.alias() == restored['restoredIndex'], 'Service admission source changed')
                del password, desired, actual
                return {'schemaVersion': 1, 'kind': KIND + '-readiness', 'state': READY, 'runId': manifest['runId'],
                    'datasetId': manifest['datasetId'], 'serviceRelease': manifest['serviceRelease'], 'manifestSha256': context['manifestSha256'],
                    'rds': manifest['rds'], 'mysql': manifest['mysql'], 'application': manifest['application'],
                    'appRuntimeBinding': manifest['appRuntimeBinding'], 'appRuntime': verified_app_runtime,
                    'debezium': manifest['debezium'], 'debeziumVerified': True,
                    'preparationReceipt': manifest['preparation']['receipt'], 'preparedFingerprintSha256': sha(output / 'prepared-fingerprint.json'),
                    'searchRestoreReceipt': manifest['search']['restoreReceipt'], 'searchTransport': manifest['search']['transport'],
                    'searchFingerprint': observed, 'restoredIndex': restored['restoredIndex'], 'redisSeparate': True,
                    'redisReset': False, 'cdc': cdc, 'cdcRunning': True, 'heartbeatObserved': True, 'topics': list(TOPICS), 'writersStopped': True,
                    'applicationStarted': False, 'deploymentReady': False, 'toolSources': manifest['toolSources'],
                    'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
        finally:
            shutil.rmtree(secret_dir)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['validate', 'validate-readiness', 'bootstrap'])
    parser.add_argument('--manifest', type=Path, required=True); parser.add_argument('--sha256', required=True)
    parser.add_argument('--dataset-id', required=True); parser.add_argument('--run-id', required=True); parser.add_argument('--release', required=True)
    parser.add_argument('--receipt', type=Path); parser.add_argument('--context', type=Path); parser.add_argument('--root', type=Path); parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        require(sha(args.manifest) == args.sha256, 'Service manifest trust anchor differs')
        manifest = validate_manifest(read(args.manifest), args.dataset_id, args.run_id, args.release,
            {name: sha(Path(__file__).parent / name) for name in TOOLS})
        if args.mode == 'validate':
            print(json.dumps({'state': 'OFFLINE_B_SERVICE_MANIFEST_VALIDATED', 'deploymentReady': False})); return
        if args.mode == 'validate-readiness':
            print(json.dumps(validate_readiness(read(args.receipt), manifest, args.sha256))); return
        receipt = bootstrap(manifest, read(args.context), args.root, args.output)
        write(args.output / 'service-readiness.json', receipt)
        print(json.dumps({'state': receipt['state'], 'deploymentReady': False}))
    except BaseException as error:
        if args.output and args.output.is_dir() and not (args.output / 'failure.json').exists():
            write(args.output / 'failure.json', {'state': 'FAILED', 'errorType': type(error).__name__, 'deploymentReady': False})
        raise SystemExit('B service admission failed; no credentials or raw remote errors were recorded.') from None


if __name__ == '__main__':
    main()
