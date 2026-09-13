#!/usr/bin/env python3
"""Two host, exact-source adapter for the unchanged native B search restore.

prepare-source exports authenticated *historical* import proofs. It does not
recapture the pre-preparation baseline or rescan current rows. restore-on-es
runs the frozen full source/native comparison on the actual local ES host.
The controller owns AWS resources, the lease, ACK delivery and publication.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import stat
import subprocess
import sys
import threading
import time
import urllib.parse

import growth_b_aws_restore as restore
import growth_b_aws_contract as aws_contract
import growth_b_contract as contract
import growth_b_prepare as prepare
import growth_b_runtime as runtime
import growth_b_search as search
import growth_b_rds_class as rds_class
import growth_b_search_transport as transport
import growth_b_service as service

sys.dont_write_bytecode = True
ACCOUNT, REGION, BUCKET = prepare.ACCOUNT, prepare.REGION, prepare.BUCKET
DATASET = 'global-growth-b-b0fbda4d12511eeb'
DEADLINE = 1789366861
KIND = 'global-b-aws-native-search-host-context'
PACKAGE_KIND = 'global-b-aws-native-search-source-package'
ACK_KIND = 'global-b-aws-native-search-lease-ack'
MAX_PUBLIC_BYTES = 8 * 1024**2
TOOLS = tuple(sorted(set(prepare.TOOLS) | {'growth_b_search_host.py', 'growth_b_search.py',
    'growth_b_search_transport.py', 'growth_b_service.py', 'growth_b_app_runtime.py',
    'publish-growth-dataset-b.py', 'fetch-growth-dataset-b.py', 'growth_b_search_snapshot.py',
    'growth_b_search_snapshot_bridge.py', 'growth_b_snapshot.py', 'growth_b_snapshot_host.py', 'growth_b_rds_class.py'}))
CONTEXT_KEYS = set(('schemaVersion kind operationId runId datasetId account region resourceFence expiresAt '
    'approvedExecutionDeadlineEpoch controllerDeadlineEpoch lease hosts rds serviceManifest preparationManifest '
    'sourceRefs targetIndex repositoryName toolSources').split())
HEX = r'[0-9a-f]{64}'


class Rejected(ValueError):
    """Closed failure code; never contains input values, argv or exception text."""


def need(condition, code):
    if not condition:
        raise Rejected(code)


def keys(value, expected, code):
    need(isinstance(value, dict) and set(value) == set(expected.split()), code)


def digest(value):
    return isinstance(value, str) and re.fullmatch(HEX, value) is not None


def integer(value, low=0, high=2**63-1):
    return type(value) is int and low <= value <= high


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False) + '\n').encode()


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def decode(raw):
    need(len(raw) <= MAX_PUBLIC_BYTES, 'JSON_TOO_LARGE')
    try:
        return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(Rejected('NONFINITE_JSON')))
    except (UnicodeError, json.JSONDecodeError):
        raise Rejected('INVALID_JSON') from None


def regular(path, maximum=MAX_PUBLIC_BYTES, *, private=False):
    path = Path(path)
    need(path.is_absolute() and path.resolve() == path and not path.is_symlink(), 'NONCANONICAL_PATH')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        need(stat.S_ISREG(before.st_mode) and before.st_size <= maximum, 'UNSAFE_FILE')
        if private:
            need(before.st_uid == os.getuid() and stat.S_IMODE(before.st_mode) == 0o600, 'PRIVATE_FILE_MODE')
        raw = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
        need((before.st_ino, before.st_size, before.st_mtime_ns) ==
             (after.st_ino, after.st_size, after.st_mtime_ns) and len(raw) == before.st_size, 'FILE_CHANGED')
    return raw


def read(path, *, private=False):
    return decode(regular(path, private=private))


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def new_file(path, raw):
    path = Path(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    sync_directory(path.parent)


def write(path, value):
    new_file(path, canonical(value))


def file_ref(path):
    path = Path(path)
    return {'sha256': sha(path), 'bytes': path.stat().st_size}


def sources(directory=None):
    directory = Path(directory or Path(__file__).parent)
    return {name: sha(directory / name) for name in TOOLS}


def source_files():
    return {'infra/aws/scripts/' + name: value for name, value in sources().items()}


def op_root(context):
    return Path('/opt/airbob/native-search') / context['runId'] / context['operationId']


def retained_root(context):
    return Path('/opt/airbob/global-b') / context['runId']


def instant(value):
    need(isinstance(value, str), 'TIME_TYPE')
    try:
        result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise Rejected('TIME_FORMAT') from None
    need(result.tzinfo is not None, 'TIME_ZONE_REQUIRED')
    return result.timestamp()


def reference(value, prefix):
    keys(value, 'key versionId sha256 bytes', 'REFERENCE_FIELDS')
    need(isinstance(value['key'], str) and value['key'].startswith(prefix)
         and all(x not in ('', '.', '..') for x in value['key'].split('/'))
         and isinstance(value['versionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', value['versionId'])
         and value['versionId'] not in ('null', 'None') and digest(value['sha256'])
         and integer(value['bytes'], 1, MAX_PUBLIC_BYTES), 'REFERENCE_VALUE')


def public_pair(value, prefix):
    keys(value, 'reference value', 'PUBLIC_PAIR_FIELDS')
    reference(value['reference'], prefix)
    need(isinstance(value['value'], dict), 'PUBLIC_PAIR_VALUE')
    public_json(value['value'])


def public_json(value):
    if isinstance(value, dict):
        forbidden = {'password', 'secretstring', 'secretbinary', 'authorization', 'cookie',
                     'sessiontoken', 'accesskeyid', 'secretaccesskey'}
        need(not any(str(k).lower() in forbidden for k in value), 'PRIVATE_VALUE_IN_PUBLIC_PROOF')
        for child in value.values():
            public_json(child)
    elif isinstance(value, list):
        for child in value:
            public_json(child)


def validate_context(value, *, now=None, expected_sources=None):
    snapshot_target = isinstance(value, dict) and 'snapshotLineage' in value
    need(isinstance(value, dict) and set(value) == CONTEXT_KEYS | ({'snapshotLineage'} if snapshot_target else set()) | ({'rdsInstanceClass'} if 'rdsInstanceClass' in value else set()), 'CONTEXT_FIELDS')
    rds_class.selected(value.get('rdsInstanceClass', rds_class.DEFAULT))
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['datasetId'] == DATASET
         and value['account'] == ACCOUNT and value['region'] == REGION, 'CONTEXT_SCOPE')
    run, operation = value['runId'], value['operationId']
    need(isinstance(run, str) and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', run)
         and '--' not in run and not run.endswith('-')
         and isinstance(operation, str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}', operation)
         and '--' not in operation and not operation.endswith('-'), 'OPERATION_IDENTITY')
    now = int(time.time()) if now is None else now
    need(integer(value['resourceFence'], 1) and integer(value['expiresAt'], 1)
         and value['approvedExecutionDeadlineEpoch'] == DEADLINE
         and integer(value['controllerDeadlineEpoch'], now + 30, now + 18000)
         and value['controllerDeadlineEpoch'] <= value['expiresAt'] <= DEADLINE, 'DEADLINE_CONTRACT')
    lease = value['lease']
    keys(lease, 'table lockName owner runId command fencingToken', 'LEASE_FIELDS')
    need(lease['table'] == 'airbob-performance-lab-orchestration-lease'
         and lease['lockName'] == 'airbob-performance-lab' and lease['runId'] == run
         and lease['command'] == 'up' and integer(lease['fencingToken'], value['resourceFence'] + 1)
         and isinstance(lease['owner'], str) and re.fullmatch(r'[A-Za-z0-9._:@/-]{3,128}', lease['owner']), 'LEASE_IDENTITY')
    keys(value['hosts'], 'preparation elasticsearch', 'HOST_FIELDS')
    keys(value['hosts']['preparation'], 'instanceId', 'PREPARATION_HOST_FIELDS')
    es = value['hosts']['elasticsearch']
    keys(es, 'instanceId container containerId image imageId clusterUuid startedAt', 'ES_HOST_FIELDS')
    for host in value['hosts'].values():
        need(isinstance(host['instanceId'], str) and re.fullmatch(r'i-[0-9a-f]{17}', host['instanceId']), 'INSTANCE_ID')
    need(es['instanceId'] != value['hosts']['preparation']['instanceId'] and digest(es['containerId'])
         and isinstance(es['container'], str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}', es['container'])
         and re.fullmatch(r'sha256:' + HEX, es['imageId'])
         and re.fullmatch(r'[A-Za-z0-9_-]{16,32}', es['clusterUuid']), 'ES_IDENTITY')
    instant(es['startedAt'])
    rds = value['rds']
    keys(rds, 'identifier resourceId serverUuid endpoint masterSecretArn createdAt', 'RDS_FIELDS')
    need(rds['identifier'] == 'airbob-' + run and re.fullmatch(r'db-[A-Z0-9]+', rds['resourceId'])
         and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', rds['serverUuid'])
         and re.fullmatch(re.escape(rds['identifier']) + r'\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com', rds['endpoint'])
         and re.fullmatch(r'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:[A-Za-z0-9/_+=.@!-]+', rds['masterSecretArn']), 'RDS_IDENTITY')
    instant(rds['createdAt'])
    public_pair(value['serviceManifest'], f'datasets/{DATASET}-aws-service/')
    manifest = value['serviceManifest']['value']
    match = re.fullmatch(r'datasets/' + DATASET + r'-aws-service/([a-z0-9][a-z0-9-]{2,47})/aws-service.json', value['serviceManifest']['reference']['key'])
    need(match is not None, 'SERVICE_KEY')
    service.validate_manifest(manifest, DATASET, run, match[1])
    need(manifest['rds'] == {k: rds[k] for k in ('identifier', 'resourceId', 'serverUuid')}
         and manifest['search']['image'] == es['image'] and manifest['search']['restoreReceipt'] is None, 'SERVICE_TARGET')
    public_pair(value['preparationManifest'], f'datasets/{DATASET}-aws-preparation/')
    preparation = value['preparationManifest']
    need(preparation['reference']['key'] == f'datasets/{DATASET}-aws-preparation/aws-preparation-{preparation["reference"]["sha256"]}.json', 'PREPARATION_KEY')
    prepare.validate_manifest(preparation['value'], DATASET, {n: sources()[n] for n in prepare.TOOLS})
    need(preparation['value']['scope'] == 'final-b-rds', 'FINAL_DUMP_PREPARATION_REQUIRED')
    keys(value['sourceRefs'], 'preparationReceipt' if snapshot_target else 'preparationReceipt standaloneReceipt', 'SOURCE_REFERENCE_FIELDS')
    for pair in value['sourceRefs'].values():
        public_pair(pair, f'data-bootstrap/{run}/')
    prep_ref = value['sourceRefs']['preparationReceipt']
    need(prep_ref['reference'] == manifest['preparation']['receipt'], 'SOURCE_REFERENCE_BINDING')
    if snapshot_target:
        import growth_b_search_snapshot_bridge as snapshot_bridge
        snapshot_bridge.validate_context(value)
    else:
        raw_ref = value['sourceRefs']['standaloneReceipt']
        need(raw_ref['reference'] == prep_ref['value'].get('standaloneReceiptObject')
             and raw_ref['reference']['sha256'] == manifest['preparation']['restoreReceiptSha256'], 'SOURCE_REFERENCE_BINDING')
    need(value['targetIndex'] == 'accommodations-v' + operation
         and re.fullmatch(search.INDEX_RE, value['targetIndex'])
         and value['repositoryName'] == 'b_' + operation.replace('-', '_'), 'NEW_NAMESPACE_REQUIRED')
    need(value['toolSources'] == (expected_sources or sources()), 'HOST_SOURCE_IDENTITY')
    return value


def expected_mysql(context, envelope):
    if 'snapshotLineage' in context:
        return {'version': '8.4.11', 'serverUuid': context['rds']['serverUuid'],
            'schema': envelope['mysql']['schema'], 'publishedDocuments': envelope['storage']['publishedListings']}
    raw = context['sourceRefs']['standaloneReceipt']['value']
    return {'version': raw['beforeDatabase']['version'], 'serverUuid': raw['beforeDatabase']['serverUuid'],
            'schema': envelope['mysql']['schema'], 'publishedDocuments': envelope['storage']['publishedListings']}


def chronology(raw):
    needed = ('SQL_IMPORT_STARTED', 'SQL_IMPORT_COMPLETED', 'FULL_VALIDATION_STARTED',
              'SEALED_DATABASE_VERIFIED', 'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED')
    events = raw.get('events')
    need(isinstance(events, list) and 6 <= len(events) <= 100, 'IMPORT_EVENTS_REQUIRED')
    times = [instant(item['at']) for item in events]
    need(times == sorted(times) and not any(item.get('state') == 'FAILED' for item in events), 'IMPORT_EVENTS_ORDER')
    found = []
    for state in needed:
        rows = [(i, item) for i, item in enumerate(events) if item.get('state') == state]
        need(len(rows) == 1, 'IMPORT_EVENT_UNIQUE')
        found.append(rows[0])
    need([i for i, _ in found] == sorted(i for i, _ in found), 'IMPORT_PHASE_ORDER')
    fields = ('sqlImportStartedAt', 'sqlImportCompletedAt', 'fullValidationStartedAt',
              'fullValidationCompletedAt', 'preparationStartedAt', 'preparationCompletedAt')
    points = [instant(raw.get(name)) for name in fields]
    need(points == sorted(points) and all(point <= instant(item['at']) for point, (_, item) in zip(points, found)), 'IMPORT_FIELD_CHRONOLOGY')
    return {name: raw[name] for name in fields}


def bound_raw(path, expected_sha, expected_bytes=None):
    raw = regular(path, private=True)
    need(hashlib.sha256(raw).hexdigest() == expected_sha
         and (expected_bytes is None or len(raw) == expected_bytes), 'SOURCE_FILE_BINDING')
    return raw


def validate_historical(context, blobs, envelope, base):
    """Validate actual old bytes; never assert that current rows were rescanned."""
    required = {'preparationReceipt', 'standaloneReceipt', 'restoredFingerprint', 'preparedFingerprint', 'hostRuntimeQualification'}
    need(set(blobs) == required, 'SOURCE_BLOB_INVENTORY')
    docs = {name: decode(data) for name, data in blobs.items()}
    for value in docs.values():
        public_json(value)
    for name in ('preparationReceipt', 'standaloneReceipt'):
        pair = context['sourceRefs'][name]
        need(docs[name] == pair['value'] and hashlib.sha256(blobs[name]).hexdigest() == pair['reference']['sha256']
             and len(blobs[name]) == pair['reference']['bytes'], 'PUBLIC_SOURCE_BYTES')
    raw, prepared, prep = docs['standaloneReceipt'], docs['preparedFingerprint'], docs['preparationReceipt']
    manifest = context['serviceManifest']['value']
    need(raw.get('schemaVersion') == 1 and raw.get('kind') == 'global-growth-b-aws-restore-receipt'
         and raw.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED' and raw.get('executionScope') == 'final-b-rds'
         and raw.get('operation') == 'replace-database' and raw.get('finalScaleSelected') is True
         and raw.get('toolIdentity') == restore.tool_identity() and raw.get('datasetId') == DATASET
         and raw.get('account') == ACCOUNT and raw.get('region') == REGION
         and raw.get('mysqlVersion') == '8.4.11' and raw.get('flywayVersion') == 28
         and raw.get('awsWritesExecuted') is True and raw.get('allRowsAndDdlEqual') is True
         and raw.get('previousBusinessSchemaAbsent') is True and raw.get('maximumSimultaneousBusinessDatabases') == 1
         and raw.get('deploymentReady') is False and raw.get('sqlImportSkipped') is not True, 'ACTUAL_FINAL_IMPORT_REQUIRED')
    need(raw.get('targetIdentity') == {k: context['rds'][k] for k in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}
         and raw.get('rdsResourceId') == context['rds']['resourceId']
         and raw.get('beforeDatabase', {}).get('serverUuid') == context['rds']['serverUuid']
         and raw['beforeDatabase'].get('version') == '8.4.11' and bool(raw['beforeDatabase'].get('tlsCipher'))
         and raw.get('configSha256') == manifest['preparation']['restoreConfigSha256'], 'IMPORT_TARGET_BINDING')
    need(raw.get('envelopeSha256') == context['preparationManifest']['value']['files']['envelope']['sha256']
         and raw.get('appJarSha256') == envelope['appJarSha256'] == manifest['application']['appJarSha256']
         and raw.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256'], 'IMPORT_SOURCE_BINDING')
    contract.validate_fingerprint(base)
    contract.validate_fingerprint(prepared, require_sealed=False)
    need(docs['restoredFingerprint'] == base == raw.get('sealedFingerprint')
         and hashlib.sha256(blobs['restoredFingerprint']).hexdigest() == raw.get('restoredFingerprintSha256'), 'RAW_RESTORED_BASELINE_BYTES')
    restore.validate_prepared_changes(base, prepared)
    prepared_sha = hashlib.sha256(blobs['preparedFingerprint']).hexdigest()
    need(prepared_sha == raw.get('preparedFingerprintSha256') == raw.get('preparation', {}).get('preparedFingerprintSha256')
         == prep.get('preparation', {}).get('preparedFingerprintSha256') == manifest['preparation']['preparedFingerprintSha256'], 'PREPARED_FINGERPRINT_BYTES')
    need(raw['preparation'].get('preparationResumed') is False
         and raw['preparation'].get('passed') is True and raw['preparation'].get('fullBaselineVerifiedBeforeCredentials') is True
         and raw['preparation'].get('applicationLeftRunning') is False
         and raw['preparation'].get('temporarySessionsRemoved') is True
         and raw['preparation'].get('serviceCurrentlyAvailable') is False
         and raw['preparation'].get('privateCredentials', {}).get('usable') is False
         and raw['preparation'].get('accountLogins', {}).get('crossCredentialRejected') is True
         and raw['preparation'].get('currentInventory', {}).get('everyHorizonContiguous') is True, 'PREPARATION_PROOF_REQUIRED')
    service.validate_preparation(prep, manifest, 'dump', None)
    need(prep.get('manifestSha256') == context['preparationManifest']['reference']['sha256']
         and prep.get('preparation') == raw['preparation'] and prep.get('serverUuid') == context['rds']['serverUuid'], 'PREPARATION_RECEIPT_BINDING')
    host = docs['hostRuntimeQualification']
    need(hashlib.sha256(blobs['hostRuntimeQualification']).hexdigest() == raw.get('hostRuntimeQualification', {}).get('sha256')
         and raw['hostRuntimeQualification'].get('execution') == 'current process host'
         and raw['hostRuntimeQualification'].get('path') == str(retained_root(context) / 'execute/host-runtime-qualification.json')
         and host.get('state') == 'TIMEZONE_RUNTIME_QUALIFIED' and host.get('consumerRuntimePassed') is True
         and host.get('passed') is True
         and host.get('consumerReleaseBindings', {}).get('consumerHelperSha256') == context['toolSources']['growth_b_runtime.py'], 'ORIGINAL_HOST_RUNTIME')
    times = chronology(raw)
    need(instant(context['rds']['createdAt']) <= instant(times['sqlImportStartedAt']), 'RDS_CREATION_CHRONOLOGY')
    mysql = expected_mysql(context, envelope)
    need(mysql['version'] == '8.4.11' and mysql['schema'] == 'airbobdb'
         and mysql['publishedDocuments'] == 657358 == manifest['search']['documentFingerprint']['documents'], 'SEALED_PUBLISHED_POPULATION')
    return docs, {'kind': 'inherited-frozen-aws-import-verification',
        'consumerManifestSha256': envelope['consumerManifestSha256'], 'checksSha256': envelope['checksumsSha256'],
        'standaloneReceipt': context['sourceRefs']['standaloneReceipt']['reference'],
        'restoredFingerprintSha256': raw['restoredFingerprintSha256'],
        'preparedFingerprintSha256': prepared_sha, 'chronology': times,
        'originalVerificationToolIdentity': raw['toolIdentity'],
        'originalHostRuntimeQualification': raw['hostRuntimeQualification'],
        'mysqlIdentityFieldOrigins': {'version': 'original beforeDatabase.version', 'serverUuid': 'original beforeDatabase.serverUuid and targetIdentity',
            'schema': 'sealed envelope.mysql.schema', 'publishedDocuments': 'sealed envelope.storage.publishedListings; complete original restored fingerprint equality'},
        'newBaselineCaptureExecuted': False, 'currentFullRowsRevalidated': False, 'currentOwnedRowsRevalidated': False}


def encode_blobs(blobs):
    return {name: {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw), 'base64': base64.b64encode(raw).decode('ascii')} for name, raw in blobs.items()}


def decode_blobs(values):
    need(isinstance(values, dict) and len(values) == 5, 'PACKAGE_BLOB_COUNT')
    result = {}
    for name, item in values.items():
        keys(item, 'sha256 bytes base64', 'PACKAGE_BLOB_FIELDS')
        need(digest(item['sha256']) and integer(item['bytes'], 1, MAX_PUBLIC_BYTES)
             and isinstance(item['base64'], str) and len(item['base64']) <= MAX_PUBLIC_BYTES, 'PACKAGE_BLOB_SIZE')
        try:
            raw = base64.b64decode(item['base64'], validate=True)
        except (ValueError, TypeError):
            raise Rejected('PACKAGE_BLOB_ENCODING') from None
        need(len(raw) == item['bytes'] and hashlib.sha256(raw).hexdigest() == item['sha256'], 'PACKAGE_BLOB_BYTES')
        result[name] = raw
    return result


def validate_source_package(package, context, context_sha, envelope, base):
    if 'snapshotLineage' in context:
        import growth_b_search_snapshot_bridge as snapshot_bridge
        return snapshot_bridge.validate_source_package(package, context, context_sha, envelope, base)
    keys(package, 'schemaVersion kind state contextSha256 sourceToolSha256 runId datasetId operationId resourceFence lease exportedAt origin mysql currentIdentityOnly files', 'PACKAGE_FIELDS')
    need(package['schemaVersion'] == 1 and package['kind'] == PACKAGE_KIND
         and package['state'] == 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED'
         and package['contextSha256'] == context_sha
         and package['sourceToolSha256'] == context['toolSources']['growth_b_search_host.py']
         and all(package[key] == context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease')), 'PACKAGE_BINDING')
    blobs = decode_blobs(package['files'])
    docs, origin = validate_historical(context, blobs, envelope, base)
    need(package['origin'] == origin and package['mysql'] == expected_mysql(context, envelope), 'PACKAGE_BASELINE_ORIGIN')
    observed = package['currentIdentityOnly']
    keys(observed, 'serverUuid version tlsVerified outboxRows otherClients currentFullRowsRevalidated currentOwnedRowsRevalidated observedAt', 'CURRENT_IDENTITY_FIELDS')
    need(observed['serverUuid'] == context['rds']['serverUuid'] and observed['version'] == '8.4.11'
         and observed['tlsVerified'] is True and observed['outboxRows'] == observed['otherClients'] == 0
         and observed['currentFullRowsRevalidated'] is False and observed['currentOwnedRowsRevalidated'] is False
         and instant(origin['chronology']['preparationCompletedAt']) <= instant(observed['observedAt'])
         <= instant(package['exportedAt']) <= context['controllerDeadlineEpoch'], 'CURRENT_IDENTITY_PROOF')
    return blobs, docs, origin


def baseline_receipt(package, blobs):
    """Deterministic inherited proof, shared with the offline completion reader."""
    raw = blobs['restoredFingerprint']
    source = decode(blobs['standaloneReceipt'])
    receipt = {'schemaVersion': 1, 'state': 'BASELINE_VERIFIED_BEFORE_PREPARATION',
        'datasetId': source['datasetId'], 'consumerManifestSha256': package['origin']['consumerManifestSha256'],
        'checksSha256': package['origin']['checksSha256'],
        'appJarSha256': source['appJarSha256'], 'mysql': package['mysql'],
        'fingerprint': {'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)},
        'verifiedAllColumnsAndDdl': source['allRowsAndDdlEqual'],
        'capturedAt': source['fullValidationCompletedAt'],
        'sourceReadFence': 'original frozen AWS restore execution fence; inherited proof, no new capture',
        'hostRuntimeQualification': source['hostRuntimeQualification'], 'origin': package['origin']}
    # The caller sets the sealed anchors only through the validated package.
    need(digest(receipt['consumerManifestSha256']) and digest(receipt['checksSha256']), 'BASELINE_ANCHORS')
    return receipt


def derive_baseline(package, blobs, directory):
    """Preserve exact original fingerprint bytes and explicitly inherited origin."""
    directory = Path(directory); directory.mkdir(mode=0o700)
    new_file(directory / 'mysql-baseline-fingerprint.json', blobs['restoredFingerprint'])
    receipt = baseline_receipt(package, blobs)
    write(directory / 'baseline-receipt.json', receipt)
    return receipt


class AckGuard:
    """Controller-observed lease/writer authority; no fabricated ES IAM reads."""
    def __init__(self, context, context_sha, directory=None, clock=time.time):
        self.context, self.context_sha, self.clock = context, context_sha, clock
        self.directory = Path(directory or op_root(context) / 'acks')
        self.observed = {}
        self.latest = None

    def __call__(self, force=False):
        context, now = self.context, int(self.clock())
        need(self.directory.resolve() == self.directory and not self.directory.is_symlink(), 'ACK_DIRECTORY')
        meta = self.directory.stat()
        need(stat.S_IMODE(meta.st_mode) == 0o700 and meta.st_uid == os.getuid(), 'ACK_DIRECTORY_MODE')
        paths = sorted(self.directory.iterdir())
        need(1 <= len(paths) <= 500 and [p.name for p in paths] == [f'{n:06d}.json' for n in range(len(paths))], 'ACK_SEQUENCE')
        previous, previous_time = '0' * 64, 0
        for sequence, path in enumerate(paths):
            raw = regular(path, maximum=16384, private=True)
            checksum = hashlib.sha256(raw).hexdigest()
            need(sequence not in self.observed or self.observed[sequence] == checksum, 'ACK_BYTES_CHANGED')
            value = decode(raw)
            keys(value, 'schemaVersion kind contextSha256 operationId runId resourceFence lease esInstanceId sequence issuedAt expiresAt previousSha256', 'ACK_FIELDS')
            need(value['schemaVersion'] == 1 and value['kind'] == ACK_KIND
                 and value['contextSha256'] == self.context_sha and value['operationId'] == context['operationId']
                 and value['runId'] == context['runId'] and value['resourceFence'] == context['resourceFence']
                 and value['lease'] == {k: context['lease'][k] for k in ('owner', 'fencingToken')}
                 and value['esInstanceId'] == context['hosts']['elasticsearch']['instanceId']
                 and value['sequence'] == sequence and value['previousSha256'] == previous, 'ACK_BINDING')
            need(integer(value['issuedAt'], previous_time, now + 2)
                 and integer(value['expiresAt'], value['issuedAt'] + 1, value['issuedAt'] + 90)
                 and value['expiresAt'] <= min(context['controllerDeadlineEpoch'], context['expiresAt'], DEADLINE), 'ACK_TIME')
            self.observed[sequence] = checksum
            previous, previous_time = checksum, value['issuedAt']
            self.latest = {'sequence': sequence, 'sha256': checksum, 'issuedAt': value['issuedAt'], 'expiresAt': value['expiresAt']}
        # Stop five seconds before expiry, leaving bounded local signal delivery
        # time. Uncertain in-flight API outcomes never become successful receipts.
        need(self.latest['expiresAt'] > now + 5, 'ACK_EXPIRED_OR_CLEANUP_MARGIN')
        return dict(self.latest)


class HostAws:
    def __init__(self, context, phase):
        self.context, self.phase = context, phase
        self.client = restore.Aws()

    def call(self, service_name, operation, *args):
        allowed = {('sts', 'get-caller-identity'), ('rds', 'describe-db-instances'), ('secretsmanager', 'get-secret-value')}
        if self.phase == 'prepare-source':
            allowed |= {('dynamodb', 'get-item'), ('autoscaling', 'describe-auto-scaling-groups')}
        need((service_name, operation) in allowed, 'HOST_AWS_OPERATION_FORBIDDEN')
        try:
            return self.client.call(service_name, operation, *args)
        except Exception:
            raise Rejected('HOST_AWS_READ_FAILED') from None


def live_rds(context, aws):
    expected = context['rds']
    items = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', expected['identifier']).get('DBInstances')
    need(isinstance(items, list) and len(items) == 1, 'RDS_INVENTORY')
    actual = items[0]
    rds_class.actual_class(actual, context.get('rdsInstanceClass', rds_class.DEFAULT))
    need(actual.get('DBInstanceIdentifier') == expected['identifier'] and actual.get('DbiResourceId') == expected['resourceId']
         and actual.get('Endpoint', {}).get('Address') == expected['endpoint'] and actual['Endpoint'].get('Port') == 3306
         and actual.get('Engine') == 'mysql' and actual.get('EngineVersion') == '8.4.11'
         and actual.get('DBInstanceStatus') == 'available' and actual.get('PubliclyAccessible') is False
         and actual.get('AllocatedStorage') == 100 and actual.get('MultiAZ') is False
         and actual.get('MasterUserSecret', {}).get('SecretArn') == expected['masterSecretArn']
         and instant(actual.get('InstanceCreateTime')) == instant(expected['createdAt']), 'LIVE_RDS_CHANGED')
    return actual


def command(argv, **kwargs):
    try:
        return restore.command(argv, **kwargs)
    except Exception:
        raise Rejected('LOCAL_READ_COMMAND_FAILED') from None


def verify_es_container(context):
    expected = context['hosts']['elasticsearch']
    values = decode(command(['docker', 'inspect', expected['container']]))
    need(isinstance(values, list) and len(values) == 1, 'LOCAL_ES_CONTAINER_COUNT')
    actual = values[0]
    need(actual.get('Id') == expected['containerId'] and actual.get('Image') == expected['imageId']
         and actual.get('Config', {}).get('Image') == expected['image']
         and actual.get('State', {}).get('Running') is True and actual['State'].get('Paused') is False
         and actual['State'].get('Restarting') is False and actual['State'].get('StartedAt') == expected['startedAt'], 'LOCAL_ES_CONTAINER_CHANGED')
    return {'containerId': expected['containerId'], 'imageId': expected['imageId'], 'startedAt': expected['startedAt']}


class LiveGuard:
    def __init__(self, context, context_sha, phase, aws=None, *, clock=time.time, dmi=None):
        self.context, self.context_sha, self.phase, self.clock = context, context_sha, phase, clock
        self.aws = aws or HostAws(context, phase)
        self.dmi = dmi or (lambda: Path('/sys/devices/virtual/dmi/id/board_asset_tag').read_text().strip())
        self.ack = AckGuard(context, context_sha, clock=clock) if phase == 'restore-on-es' else None
        self.last_cloud = 0.0
        self.connect_identity = None
        self.live = None

    def __call__(self, force=False):
        c, now = self.context, self.clock()
        need(now + 5 < min(c['controllerDeadlineEpoch'], c['expiresAt'], DEADLINE), 'HOST_DEADLINE')
        need(not (op_root(c) / 'STOP').exists() and not (retained_root(c) / 'STOP').exists(), 'HOST_STOPPED')
        need(c['toolSources'] == sources(), 'ACTIVE_HOST_SOURCE_CHANGED')
        host_key = 'preparation' if self.phase == 'prepare-source' else 'elasticsearch'
        need(self.dmi() == c['hosts'][host_key]['instanceId'], 'DMI_INSTANCE_CHANGED')
        if self.ack:
            self.ack(force=force)
            verify_es_container(c)
        if force or now - self.last_cloud >= 15:
            identity = self.aws.call('sts', 'get-caller-identity')
            role = 'debezium' if self.phase == 'prepare-source' else 'elasticsearch'
            need(identity.get('Account') == ACCOUNT and re.fullmatch(
                re.escape(f'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-lab-host-{c["runId"]}-{role}/') + r'[^/]+', identity.get('Arn', '')), 'HOST_ROLE_CHANGED')
            self.live = live_rds(c, self.aws)
            if self.phase == 'prepare-source':
                restore.Lease(self.aws, c['lease'])(force=True)
                groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups').get('AutoScalingGroups', [])
                selected = [g for g in groups if g.get('AutoScalingGroupName', '').startswith('airbob-' + c['runId'] + '-')]
                need(len(selected) == 1 and selected[0].get('AutoScalingGroupName') == 'airbob-' + c['runId'] + '-app'
                     and selected[0].get('DesiredCapacity') == selected[0].get('MinSize') == selected[0].get('MaxSize') == 0
                     and selected[0].get('Instances') == [], 'WRITER_ASG_NOT_ZERO')
                need(service.request('GET', '/connectors') == [], 'CONNECT_BUSINESS_CONNECTOR_PRESENT')
                ids = command(['docker', 'ps', '--quiet', '--no-trunc', '--filter', 'label=com.docker.compose.service=debezium']).decode().splitlines()
                need(len(ids) == 1 and digest(ids[0]), 'CONNECT_CONTAINER_COUNT')
                inspected = decode(command(['docker', 'inspect', ids[0]]))[0]
                observed = {'id': inspected.get('Id'), 'image': inspected.get('Config', {}).get('Image'),
                            'startedAt': inspected.get('State', {}).get('StartedAt')}
                need(observed['image'] == c['serviceManifest']['value']['debezium']['image']
                     and inspected.get('State', {}).get('Running') is True, 'CONNECT_CONTAINER_CHANGED')
                need(self.connect_identity is None or self.connect_identity == observed, 'CONNECT_RESTARTED')
                self.connect_identity = observed
            self.last_cloud = now
        return self.ack.latest if self.ack else {'directLeaseObserved': True}


def input_paths(context, phase):
    root = retained_root(context) if phase == 'prepare-source' else op_root(context) / 'inputs'
    preparation_path = root / 'aws-preparation.json'
    if phase == 'prepare-source' and 'snapshotLineage' in context:
        document = context['snapshotLineage']['documents']['targetManifest']
        target = decode(base64.b64decode(document['base64'], validate=True))
        preparation_path = root / ('snapshot-' + target['operationId']) / 'aws-preparation.json'
    return {'root': root, 'release': root / 'release', 'migrations': root / 'migrations', 'app': root / 'app.jar',
            'envelope': root / 'envelope.json', 'publication': root / 'publication-receipt.json',
            'ca': root / 'rds-ca.pem', 'toolchainManifest': root / 'toolchain.json',
            'toolchain': (retained_root(context) if phase == 'prepare-source' else op_root(context)) / 'toolchain',
            'preparation': preparation_path, 'small': root / 'small-rds-receipt.json',
            'companion': op_root(context) / 'inputs/search/companion',
            'descriptor': op_root(context) / 'inputs/search/companion-descriptor.json',
            'transport': op_root(context) / 'inputs/search/transport-manifest.json'}


def load_inputs(context, phase, output):
    paths = input_paths(context, phase)
    original = context['preparationManifest']
    need(read(paths['preparation']) == original['value'] and sha(paths['preparation']) == original['reference']['sha256'], 'PREPARATION_MANIFEST_BYTES')
    manifest = original['value']
    for name, key in [('envelope', 'envelope'), ('publicationReceipt', 'publication'), ('appJar', 'app'),
                      ('rdsCaBundle', 'ca'), ('toolchainManifest', 'toolchainManifest'), ('smallRdsReceipt', 'small')]:
        expected = manifest['files'][name]
        need(paths[key].resolve() == paths[key] and not paths[key].is_symlink()
             and paths[key].stat().st_size == expected['bytes'] and sha(paths[key]) == expected['sha256'], 'STAGED_PUBLIC_INPUT_BYTES')
    environment = prepare.qualify_toolchain(paths['toolchain'], read(paths['toolchainManifest']), manifest['toolchain'])
    need(environment.get('PYTHONDONTWRITEBYTECODE') == '1', 'BYTECODE_PRESERVATION_REQUIRED')
    envelope = aws_contract.validate_envelope(paths['envelope'], manifest['files']['envelope']['sha256'], paths['release'],
        paths['migrations'], paths['publication'], paths['app'])
    prepare.validate_offline_inputs(manifest, paths['envelope'], paths['small'])
    qualified_root = Path(output) / '.private/qualification-runtime'
    contract.extract_runtime(paths['release'], qualified_root)
    qualification = runtime.qualify_runtime(paths['release'], qualified_root, Path(output) / 'runtime-qualification.json',
        expected_checks={name: ref['sha256'] for name, ref in envelope['objects'].items()}, java_home=paths['toolchain'] / 'jdk')
    return paths, envelope, read(paths['release'] / 'before-fingerprint.json'), qualification


def historical_blobs(context):
    root = retained_root(context)
    service_manifest = context['serviceManifest']['value']
    config_path = root / 'restore-config.json'
    bound_raw(config_path, service_manifest['preparation']['restoreConfigSha256'])
    config = restore.configuration(config_path)
    need(config.get('operation', 'replace-database') == 'replace-database' and 'resumeReceipt' not in config
         and config['replacementPolicy'] == 'empty-only' and 'search' not in config, 'ORIGINAL_DUMP_CONFIG')
    need(all(config['rds'][key] == context['rds'][key] for key in ('identifier', 'resourceId', 'serverUuid', 'endpoint', 'masterSecretArn'))
         and config['envelopeSha256'] == context['preparationManifest']['value']['files']['envelope']['sha256']
         and config['lease']['fencingToken'] == context['resourceFence'] and config['lease']['runId'] == context['runId'], 'ORIGINAL_CONFIG_BINDING')
    canonical_paths = {'release': root / 'release', 'migrationDirectory': root / 'migrations', 'publicationReceipt': root / 'publication-receipt.json',
                       'envelope': root / 'envelope.json', 'appJar': root / 'app.jar', 'privateAccounts': root / 'private/accounts.private.json'}
    need(all(config[key] == str(path) for key, path in canonical_paths.items()), 'ORIGINAL_CONFIG_PATHS')
    original = context['sourceRefs']['standaloneReceipt']['value']
    expected = {'preparationReceipt': (root / 'public-receipt.json', context['sourceRefs']['preparationReceipt']['reference']['sha256']),
                'standaloneReceipt': (root / 'execute/restore-receipt.json', context['sourceRefs']['standaloneReceipt']['reference']['sha256']),
                'restoredFingerprint': (root / 'execute/restored-fingerprint.json', original.get('restoredFingerprintSha256')),
                'preparedFingerprint': (root / 'execute/prepared-fingerprint.json', original.get('preparedFingerprintSha256')),
                'hostRuntimeQualification': (root / 'execute/host-runtime-qualification.json', original.get('hostRuntimeQualification', {}).get('sha256'))}
    need(all(digest(item[1]) for item in expected.values()), 'HISTORICAL_SHA_REQUIRED')
    return config, {name: bound_raw(path, checksum) for name, (path, checksum) in expected.items()}


def db_config(context, paths):
    return {'rds': {key: context['rds'][key] for key in ('identifier', 'resourceId', 'serverUuid', 'endpoint', 'masterSecretArn')}
            | {'caBundle': str(paths['ca']), 'caBundleSha256': context['preparationManifest']['value']['files']['rdsCaBundle']['sha256']},
            'operationTimeoutSeconds': 14400}


def identity_only(context, db, config):
    observed = restore.database_state(db, config)
    need(observed['tableObjects'] > 0 and observed['businessSchemas'] == ['airbobdb']
         and observed['serverUuid'] == context['rds']['serverUuid'] and observed['version'] == '8.4.11'
         and observed['otherClients'] == 0 and bool(observed['tlsCipher'])
         and db.scalar('SELECT COUNT(*) FROM outbox') == 0, 'CURRENT_SOURCE_IDENTITY')
    return {'serverUuid': observed['serverUuid'], 'version': observed['version'], 'tlsVerified': True,
            'outboxRows': 0, 'otherClients': 0, 'currentFullRowsRevalidated': False,
            'currentOwnedRowsRevalidated': False, 'observedAt': dt.datetime.now(dt.timezone.utc).isoformat()}


def prepare_source(context, context_sha, output, *, guard=None):
    if 'snapshotLineage' in context:
        import growth_b_search_snapshot_bridge as snapshot_bridge
        return snapshot_bridge.prepare_source(context, context_sha, output, guard=guard)
    output = Path(output)
    guard = guard or LiveGuard(context, context_sha, 'prepare-source')
    guard(force=True)
    paths, envelope, base, qualification = load_inputs(context, 'prepare-source', output)
    _, blobs = historical_blobs(context)
    docs, origin = validate_historical(context, blobs, envelope, base)
    prepare.validate_preparation_receipt(context['preparationManifest']['value'],
        {'runId': context['runId'], 'manifestSha256': context['preparationManifest']['reference']['sha256'],
         'rdsResourceId': context['rds']['resourceId']}, docs['preparationReceipt'],
        retained_root(context) / 'execute/restore-receipt.json', paths['envelope'])
    service.verify_debezium(context['serviceManifest']['value']['debezium'], command, guard)
    config = db_config(context, paths)
    with runtime.activated_runtime(qualification):
        guard(force=True)
        directory = output / '.private/connection'; directory.mkdir(mode=0o700)
        db, _ = restore.connection(config, guard.aws, {'masterUsername': guard.live['MasterUsername']}, directory, guard)
        observed = identity_only(context, db, config)
    guard(force=True)
    result = {'schemaVersion': 1, 'kind': PACKAGE_KIND, 'state': 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED',
        'contextSha256': context_sha, 'sourceToolSha256': sources()['growth_b_search_host.py'],
        **{key: context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease')},
        'exportedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'origin': origin,
        'mysql': expected_mysql(context, envelope), 'currentIdentityOnly': observed, 'files': encode_blobs(blobs)}
    validate_source_package(result, context, context_sha, envelope, base)
    need(len(canonical(result)) <= MAX_PUBLIC_BYTES, 'SOURCE_PACKAGE_TOO_LARGE')
    write(output / 'public/source-package.json', result)
    return result


def search_config(context, paths, envelope, private_directory, environment):
    trust = private_directory / 'rds-trust.p12'
    url = environment['AIRBOB_ETL_DB_URL'].split('?', 1)[0] + '?' + urllib.parse.urlencode({
        'sslMode': 'VERIFY_IDENTITY', 'connectionTimeZone': 'UTC', 'forceConnectionTimeZoneToSession': 'true', 'connectTimeout': '10000'})
    return {'datasetId': DATASET, 'releaseDirectory': str(paths['release']), 'migrationDirectory': str(paths['migrations']),
        'appJar': str(paths['app']), 'consumerManifestSha256': envelope['consumerManifestSha256'],
        'checksSha256': envelope['checksumsSha256'], 'allowSmallQualification': False,
        'mysql': {'jdbcUrl': url, 'username': environment['AIRBOB_ETL_DB_USER'], 'expectedServerUuid': context['rds']['serverUuid'],
            'passwordEnvironment': 'AIRBOB_NATIVE_SEARCH_DB_PASSWORD', 'tlsTrustStore': {'path': str(trust), 'sha256': sha(trust),
                'passwordEnvironment': 'AIRBOB_NATIVE_SEARCH_TRUST_PASSWORD'}},
        'elasticsearch': {'url': 'http://127.0.0.1:9200', 'version': '8.18.8',
            'container': context['hosts']['elasticsearch']['container'], 'image': context['hosts']['elasticsearch']['image']},
        'repository': {'name': context['repositoryName'], 'type': 's3', 'aws': {'region': REGION},
            'settings': {'bucket': BUCKET, 'region': REGION, 'base_path': 'datasets/' + DATASET + '-search/' +
                context['serviceManifest']['value']['search']['snapshotRelease'] + '/native'},
            'transport': {'manifest': str(paths['transport']), 'sha256': context['serviceManifest']['value']['search']['transport']['sha256']}},
        'sourceTimeoutSeconds': 14400, 'snapshotTimeoutSeconds': 7200, 'targetIndex': context['targetIndex']}


def restore_on_es(context, context_sha, package_path, package_sha, output, *, guard=None):
    if 'snapshotLineage' in context:
        import growth_b_search_snapshot_bridge as snapshot_bridge
        return snapshot_bridge.restore_on_es(context, context_sha, package_path, package_sha, output, guard=guard)
    output = Path(output)
    guard = guard or LiveGuard(context, context_sha, 'restore-on-es')
    guard(force=True)
    paths, envelope, base, qualification = load_inputs(context, 'restore-on-es', output)
    package = decode(bound_raw(package_path, package_sha))
    blobs, _, origin = validate_source_package(package, context, context_sha, envelope, base)
    descriptor = read(paths['descriptor'])
    marker = transport.validate_published_transport(paths['transport'], context['serviceManifest']['value']['search']['transport']['sha256'], paths['companion'], descriptor)
    need(marker['sql']['publicationReceiptSha256'] == envelope['publicationReceiptSha256']
         and marker['source']['consumerManifestSha256'] == envelope['consumerManifestSha256']
         and marker['source']['checksSha256'] == envelope['checksumsSha256'], 'TRANSPORT_SQL_SOURCE')
    baseline = output / 'baseline'
    derive_baseline(package, blobs, baseline)
    config = db_config(context, paths)
    with runtime.activated_runtime(qualification):
        guard(force=True)
        private = output / '.private/connection'; private.mkdir(mode=0o700)
        db, environment = restore.connection(config, guard.aws, {'masterUsername': guard.live['MasterUsername']}, private, guard)
        identity_only(context, db, config)
        settings = search_config(context, paths, envelope, private, environment)
        es = search.Elasticsearch(settings['elasticsearch'])
        identity = es.identity()
        expected = context['hosts']['elasticsearch']
        need(identity['clusterUuid'] == expected['clusterUuid'] and identity['imageId'] == expected['imageId']
             and es.alias(optional=True) is None and es.optional('/' + context['targetIndex']) is None
             and es.optional('/_snapshot/' + context['repositoryName']) is None, 'ES_FRESH_NAMESPACE_IDENTITY')
        secrets = {'AIRBOB_NATIVE_SEARCH_DB_PASSWORD': environment['AIRBOB_ETL_DB_PASSWORD'], 'AIRBOB_NATIVE_SEARCH_TRUST_PASSWORD': 'changeit'}
        previous = {key: os.environ.get(key) for key in secrets}
        os.environ.update(secrets)
        try:
            guard(force=True)
            receipt = search.restore(settings, paths['companion'], descriptor, baseline, output / 'native', activate_alias=True)
        finally:
            for key, old in previous.items():
                if old is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = old
        guard(force=True)
        need(receipt['state'] == 'SEARCH_RESTORED_AND_ACTIVATED' and receipt['activeAlias'] == context['targetIndex']
             and receipt['previousIndexRetained'] is None and receipt['elasticsearch'] == identity
             and receipt['mysql'] == package['mysql']
             and receipt['fullDocumentFingerprint'] == context['serviceManifest']['value']['search']['documentFingerprint']
             and receipt['nativeTransport']['manifestSha256'] == context['serviceManifest']['value']['search']['transport']['sha256']
             and receipt['allDocumentSourceFieldsEqual'] is True
             and receipt['baselineReceiptSha256'] == sha(baseline / 'baseline-receipt.json'), 'NATIVE_RESULT_BINDING')
        identity_only(context, db, config)
    guard(force=True)
    raw = regular(output / 'native/search-restore-receipt.json')
    need(decode(raw) == receipt, 'NATIVE_RESULT_BYTES')
    new_file(output / 'public/search-restore-receipt.json', raw)
    result = {'schemaVersion': 1, 'kind': 'global-b-aws-native-search-host-receipt', 'state': 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED',
        'contextSha256': context_sha, 'sourcePackageSha256': package_sha, 'toolSources': sources(),
        **{key: context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease', 'rds', 'hosts')},
        'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'baselineOrigin': origin,
        'restoreReceipt': file_ref(output / 'public/search-restore-receipt.json'), 'finalAck': guard.ack.latest,
        'freshFullSourceAndOwnedComparisons': True, 'allDocumentSourceFieldsEqual': True,
        'sqlImportExecuted': False, 'businessSqlWritesExecuted': False, 'accountPreparationExecuted': False,
        'redisModified': False, 'cloudInfrastructureModified': False, 'sourceSealsChanged': False}
    write(output / 'public/host-receipt.json', result)
    return result


def process_identity(pid):
    path = Path('/proc') / str(pid) / 'stat'
    try:
        raw = path.read_text()
    except FileNotFoundError:
        return None
    fields = raw[raw.rindex(')') + 2:].split()
    return {'pid': pid, 'parentPid': int(fields[1]), 'processGroup': int(fields[2]), 'startTicks': int(fields[19]), 'state': fields[0]}


def process_snapshot():
    """Only process coordinates, never command lines, environment or credentials."""
    need(Path('/proc/self/stat').is_file(), 'LINUX_PROCESS_INVENTORY_REQUIRED')
    result = {}
    for path in Path('/proc').iterdir():
        if path.name.isdigit():
            try:
                value = process_identity(int(path.name))
            except PermissionError:
                continue
            if value is not None:
                result[value['pid']] = value
    return result


class OwnedProcessTree:
    """Record only descendants observed under the exact newly launched worker."""
    def __init__(self, identity, directory=None):
        self.root = dict(identity)
        self.owned = {(identity['pid'], identity['startTicks']): dict(identity)}
        self.directory = Path(directory) if directory else None
        self.sequence = 0
        self.root_observed = False
        if self.directory:
            self.directory.mkdir(mode=0o700)
        self.observe()

    def observe(self):
        current = process_snapshot()
        root = current.get(self.root['pid'])
        if root is not None and root['startTicks'] == self.root['startTicks']:
            self.root_observed = True
        changed = []
        while True:
            added = []
            for value in current.values():
                key = value['pid'], value['startTicks']
                parent = current.get(value['parentPid'])
                if key not in self.owned and parent and (parent['pid'], parent['startTicks']) in self.owned:
                    need(value['startTicks'] >= parent['startTicks'], 'DESCENDANT_BIRTH_ORDER')
                    self.owned[key] = dict(value); added.append(dict(value))
            changed.extend(added)
            if not added:
                break
        if changed and self.directory:
            write(self.directory / f'{self.sequence:06d}.json', {'worker': self.root, 'observedDescendants': changed})
            self.sequence += 1
        return current

    def remaining(self):
        current = self.observe()
        return [value for key in self.owned if (value := current.get(key[0])) is not None
                and value['startTicks'] == key[1] and value['state'] != 'Z']

    def signal_descendants(self, selected):
        for value in self.remaining():
            if value['pid'] == self.root['pid']:
                continue
            observed = process_identity(value['pid'])
            if observed is None or observed['startTicks'] != value['startTicks']:
                continue
            need(observed['processGroup'] == value['processGroup'], 'DESCENDANT_GROUP_CHANGED')
            try:
                # A PID signal cannot target an unrelated member of a process
                # group; its birth and lineage were recorded before this call.
                os.kill(value['pid'], selected)
            except ProcessLookupError:
                pass


class WorkerWatchdog:
    """Independent local guard survives loss of the outer host supervisor.

    No cloud or DB call runs on this thread. On ES, the controller's immutable
    ACK is the permission boundary during the unchanged core's long reads.
    """
    def __init__(self, context, context_sha, phase, output, parent, *, clock=time.time, interval=.5):
        self.context, self.context_sha, self.phase = context, context_sha, phase
        self.output, self.parent, self.clock, self.interval = Path(output), dict(parent), clock, interval
        self.ack = AckGuard(context, context_sha, clock=clock) if phase == 'restore-on-es' else None
        self.stopped = threading.Event(); self.failure = None; self.thread = None
        self.worker_pid = os.getpid()

    def check(self):
        c = self.context
        need(os.getppid() == self.parent['pid'], 'WORKER_SUPERVISOR_REPARENTED')
        observed = process_identity(self.parent['pid'])
        need(observed is not None and observed['startTicks'] == self.parent['startTicks']
             and observed['state'] != 'Z', 'WORKER_SUPERVISOR_LOST')
        need(self.clock() + 5 < min(c['controllerDeadlineEpoch'], c['expiresAt'], DEADLINE), 'WORKER_DEADLINE')
        need(not (op_root(c) / 'STOP').exists() and not (retained_root(c) / 'STOP').exists(), 'WORKER_STOPPED')
        need(sources() == c['toolSources'], 'WORKER_SOURCE_CHANGED')
        if self.ack:
            self.ack()

    def watch(self):
        while not self.stopped.wait(self.interval):
            try:
                self.check()
            except BaseException as error:
                self.failure = str(error) if isinstance(error, Rejected) else 'WORKER_LOCAL_GUARD_FAILED'
                try:
                    write(self.output / 'public/worker-watchdog-failure.json', {'schemaVersion': 1,
                        'state': 'WORKER_INTERRUPTED_RESOURCES_RETAINED', 'failureCode': self.failure,
                        'contextSha256': self.context_sha, 'phase': self.phase,
                        'parentProcess': self.parent, 'observedAtEpoch': int(self.clock()),
                        'automaticResubmissionAllowed': False})
                except BaseException:
                    pass
                # Interrupting the main Python thread enters the frozen reader's
                # own finally blocks. Also signal exact observed read children,
                # including those created in separate sessions.
                try:
                    identity = process_identity(self.worker_pid)
                    if identity is not None and identity['processGroup'] == self.worker_pid:
                        OwnedProcessTree(identity).signal_descendants(signal.SIGINT)
                except BaseException:
                    pass
                if os.getpid() == self.worker_pid and os.getsid(0) == self.worker_pid:
                    os.killpg(self.worker_pid, signal.SIGINT)
                return

    def __enter__(self):
        need(os.getpid() == os.getsid(0) == os.getpgid(0), 'WORKER_SESSION_REQUIRED')
        self.check()
        self.thread = threading.Thread(target=self.watch, name='native-search-local-guard', daemon=True)
        self.thread.start()
        return self

    def __exit__(self, *_):
        self.stopped.set()
        if self.thread:
            self.thread.join(timeout=5)
            need(not self.thread.is_alive(), 'WORKER_WATCHDOG_NOT_SETTLED')
        if self.failure:
            raise Rejected(self.failure)
        self.check()


@contextlib.contextmanager
def original_control_lock(context):
    """Keep the existing inode; never delete started/STOP or signal the old job."""
    root = retained_root(context)
    path = root / 'control.lock'
    need(root.resolve() == root and not root.is_symlink(), 'RETAINED_ROOT_CHANGED')
    fd = os.open(path, os.O_RDWR | os.O_NOFOLLOW)
    try:
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid()
             and stat.S_IMODE(before.st_mode) == 0o600, 'ORIGINAL_LOCK_MODE')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Rejected('ORIGINAL_CONTROL_LOCK_HELD') from None
        need(path.stat().st_ino == before.st_ino and not (root / 'STOP').exists(), 'ORIGINAL_LOCK_CHANGED')
        owner_file = root / 'process-group'
        if owner_file.exists():
            fields = regular(owner_file, maximum=128, private=True).decode().split()
            need(len(fields) == 2 and all(re.fullmatch(r'[1-9][0-9]*', value) for value in fields), 'ORIGINAL_PROCESS_RECORD')
            pid, ticks = map(int, fields)
            observed = process_identity(pid)
            need(observed is None or observed['startTicks'] != ticks, 'ORIGINAL_PREPARATION_STILL_RUNNING')
        yield
        need(path.stat().st_ino == before.st_ino, 'ORIGINAL_LOCK_CHANGED')
    finally:
        os.close(fd)


def validate_completion(context, context_sha, package, package_sha, native, receipt):
    if 'snapshotLineage' in context:
        import growth_b_search_snapshot_bridge as snapshot_bridge
        return snapshot_bridge.validate_completion(context, context_sha, package, package_sha, native, receipt)
    """Pure public completion gate for the owning controller before publication."""
    keys(receipt, 'schemaVersion kind state contextSha256 sourcePackageSha256 toolSources runId datasetId operationId resourceFence lease rds hosts completedAt baselineOrigin restoreReceipt finalAck freshFullSourceAndOwnedComparisons allDocumentSourceFieldsEqual sqlImportExecuted businessSqlWritesExecuted accountPreparationExecuted redisModified cloudInfrastructureModified sourceSealsChanged', 'HOST_COMPLETION_FIELDS')
    need(receipt.get('schemaVersion') == 1 and receipt.get('kind') == 'global-b-aws-native-search-host-receipt'
         and receipt.get('state') == 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED'
         and receipt.get('contextSha256') == context_sha and receipt.get('sourcePackageSha256') == package_sha
         and receipt.get('toolSources') == context['toolSources']
         and all(receipt.get(key) == context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease', 'rds', 'hosts')),
         'HOST_COMPLETION_BINDING')
    need(hashlib.sha256(canonical(package)).hexdigest() == package_sha
         and package.get('kind') == PACKAGE_KIND and package.get('contextSha256') == context_sha
         and package.get('sourceToolSha256') == context['toolSources']['growth_b_search_host.py']
         and receipt.get('baselineOrigin') == package.get('origin'), 'COMPLETION_SOURCE_ORIGIN')
    blobs = decode_blobs(package['files'])
    for key in ('preparationReceipt', 'standaloneReceipt'):
        ref = context['sourceRefs'][key]
        need(decode(blobs[key]) == ref['value'] and hashlib.sha256(blobs[key]).hexdigest() == ref['reference']['sha256']
             and len(blobs[key]) == ref['reference']['bytes'], 'COMPLETION_ORIGINAL_SOURCE_BYTES')
    need(all(receipt.get(key) is False for key in ('sqlImportExecuted', 'businessSqlWritesExecuted', 'accountPreparationExecuted',
        'redisModified', 'cloudInfrastructureModified', 'sourceSealsChanged'))
         and receipt.get('freshFullSourceAndOwnedComparisons') is True and receipt.get('allDocumentSourceFieldsEqual') is True,
         'COMPLETION_SCOPE')
    native_value = decode(native)
    selected = context['serviceManifest']['value']['search']
    need(receipt['restoreReceipt'] == {'sha256': hashlib.sha256(native).hexdigest(), 'bytes': len(native)}
         and native_value.get('state') == 'SEARCH_RESTORED_AND_ACTIVATED' and native_value.get('datasetId') == DATASET
         and native_value.get('snapshotRelease') == selected['snapshotRelease']
         and native_value.get('fullDocumentFingerprint') == selected['documentFingerprint']
         and native_value.get('mysql') == package['mysql']
         and native_value.get('baselineReceiptSha256') == hashlib.sha256(canonical(baseline_receipt(package, blobs))).hexdigest()
         and native_value.get('restoredIndex') == native_value.get('activeAlias') == context['targetIndex']
         and native_value.get('previousIndexRetained') is None
         and native_value.get('elasticsearch', {}).get('clusterUuid') == context['hosts']['elasticsearch']['clusterUuid']
         and native_value['elasticsearch'].get('image') == context['hosts']['elasticsearch']['image']
         and native_value['elasticsearch'].get('imageId') == context['hosts']['elasticsearch']['imageId']
         and native_value.get('nativeTransport', {}).get('manifestSha256') == selected['transport']['sha256']
         and native_value['nativeTransport'].get('exactVersionBytesVerified') is True
         and native_value['nativeTransport'].get('sourceSealsChanged') is False
         and all(native_value.get(key) is True for key in ('allDocumentSourceFieldsEqual', 'repositoryReadOnly',
               'nativeInventoryUnchanged', 'repositoryRegistrationRemoved')), 'NATIVE_COMPLETION_BINDING')
    ack = receipt.get('finalAck', {})
    keys(ack, 'sequence sha256 issuedAt expiresAt', 'FINAL_ACK_FIELDS')
    need(integer(ack['sequence'], 0, 499) and digest(ack['sha256'])
         and integer(ack['issuedAt'], 1) and integer(ack['expiresAt'], ack['issuedAt'] + 1, ack['issuedAt'] + 90)
         and instant(package['exportedAt']) <= instant(receipt['completedAt']) < ack['expiresAt'] - 5
         and ack['issuedAt'] <= instant(receipt['completedAt'])
         and ack['expiresAt'] <= context['controllerDeadlineEpoch'], 'COMPLETION_ACK_TIME')
    return {'state': receipt['state'], 'restoreReceipt': receipt['restoreReceipt'], 'sourcePackageSha256': package_sha}


def stop_own_worker(process, identity, *, tree=None, sleep=time.sleep):
    """Drain exact observed descendants, including separate read sessions."""
    tree = tree or OwnedProcessTree(identity)
    tree.observe()
    current = process_identity(process.pid)
    if current is not None:
        need(current['startTicks'] == identity['startTicks'] and current['processGroup'] == process.pid, 'WORKER_PROCESS_IDENTITY_CHANGED')
    tree.signal_descendants(signal.SIGINT)
    if current is not None:
        try:
            os.killpg(process.pid, signal.SIGINT)
        except ProcessLookupError:
            pass
    forced = False
    try:
        process.wait(timeout=20)
    except subprocess.TimeoutExpired:
        tree.observe()
        forced = True
        current = process_identity(process.pid)
        if current is not None:
            need(current['startTicks'] == identity['startTicks'] and current['processGroup'] == process.pid, 'WORKER_PROCESS_IDENTITY_CHANGED')
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        process.wait(timeout=10)
    # Frozen restore.command deliberately starts separate sessions. Signal only
    # PID/birth pairs proven to descend from this invocation, and inspect them
    # independently of the worker process group. An unclean worker death could
    # have orphaned an unobserved descendant and therefore stays uncertain.
    tree.signal_descendants(signal.SIGINT)
    for _ in range(20):
        if not tree.remaining():
            break
        sleep(.1)
    tree.signal_descendants(signal.SIGKILL)
    for _ in range(20):
        if not tree.remaining():
            return not forced and tree.root_observed and process.returncode == 0
        sleep(.1)
    return False


@contextlib.contextmanager
def interrupted_by_signals():
    """Route supervisor termination through its owned-worker cleanup."""
    def interrupted(*_):
        raise KeyboardInterrupt()
    previous = {selected: signal.getsignal(selected) for selected in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
    try:
        for selected in previous:
            signal.signal(selected, interrupted)
        yield
    finally:
        for selected, handler in previous.items():
            signal.signal(selected, handler)


def worker(context, context_sha, phase, output, package_path=None, package_sha=None, *, parent_identity):
    os.umask(0o077)
    with interrupted_by_signals():
        with WorkerWatchdog(context, context_sha, phase, output, parent_identity):
            if phase == 'prepare-source':
                with original_control_lock(context):
                    return prepare_source(context, context_sha, output)
            return restore_on_es(context, context_sha, package_path, package_sha, output)


def run(context_file, phase, output, package_path=None, package_sha=None):
    context_file, output = Path(context_file), Path(output)
    raw = regular(context_file, private=True); context_sha = hashlib.sha256(raw).hexdigest()
    context = validate_context(decode(raw))
    need(phase in ('prepare-source', 'restore-on-es') and output == op_root(context) / phase
         and output.resolve() == output and not output.exists(), 'NEW_OPERATION_OUTPUT_REQUIRED')
    if phase == 'restore-on-es':
        need(package_path is not None and digest(package_sha), 'SOURCE_PACKAGE_REFERENCE_REQUIRED')
        bound_raw(package_path, package_sha)
    else:
        need(package_path is None and package_sha is None, 'SOURCE_PHASE_FORBIDS_PACKAGE_OVERRIDE')
    need(op_root(context).is_dir() and not op_root(context).is_symlink(), 'OPERATION_ROOT_REQUIRED')
    output.mkdir(mode=0o700)
    (output / '.private').mkdir(mode=0o700)
    (output / 'public').mkdir(mode=0o700)
    sync_directory(output.parent)
    # This immutable intent is never consumed as permission to resubmit.
    parent_identity = process_identity(os.getpid())
    need(parent_identity is not None, 'PARENT_PROCESS_IDENTITY_REQUIRED')
    write(output / 'intent.json', {'schemaVersion': 1, 'phase': phase, 'contextSha256': context_sha,
        'sourcePackageSha256': package_sha, 'targetIndex': context['targetIndex'], 'repositoryName': context['repositoryName'],
        'parentProcess': parent_identity})
    guard = LiveGuard(context, context_sha, phase)
    process = None; identity = None; owned = None; terminal = False; original_error = None
    try:
        guard(force=True)
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE='1', TMPDIR=str(output / '.private'))
        argv = [sys.executable, '-B', str(Path(__file__).resolve()), phase, '--context', str(context_file), '--output', str(output), '--worker']
        if package_path:
            argv += ['--source-package', str(package_path), '--source-package-sha256', package_sha]
        process = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                   start_new_session=True, env=environment)
        identity = process_identity(process.pid)
        need(identity is not None and identity['processGroup'] == process.pid, 'WORKER_START_NOT_OBSERVED')
        owned = OwnedProcessTree(identity, output / 'process-observations')
        write(output / 'worker.json', identity | {'contextSha256': context_sha, 'sourceSha256': sources()['growth_b_search_host.py']})
        while process.poll() is None:
            owned.observe()
            guard()
            time.sleep(.5)
        terminal = stop_own_worker(process, identity, tree=owned)
        need(terminal, 'OWNED_DESCENDANTS_NOT_TERMINAL')
        need(process.returncode == 0, 'NATIVE_HOST_WORKER_FAILED')
        guard(force=True)
        filename = 'source-package.json' if phase == 'prepare-source' else 'host-receipt.json'
        result = read(output / 'public' / filename)
        if phase == 'restore-on-es':
            validate_completion(context, context_sha, read(package_path), package_sha,
                regular(output / 'public/search-restore-receipt.json'), result)
        return {'state': result['state'], 'output': str(output), 'sha256': sha(output / 'public' / filename),
                'ownedWorkerTerminal': True, 'privateMaterialRemoved': True}
    except BaseException as error:
        original_error = error if isinstance(error, Rejected) else Rejected('HOST_OPERATION_FAILED')
        raise original_error from None
    finally:
        cleanup_code = None; private_removed = False
        if process is not None and not terminal:
            try:
                terminal = stop_own_worker(process, identity, tree=owned) if identity is not None and owned is not None else False
            except BaseException:
                cleanup_code = 'OWNED_WORKER_CLEANUP_UNCERTAIN'
        if process is None:
            terminal = True
        if terminal:
            try:
                shutil.rmtree(output / '.private')
                sync_directory(output)
                private_removed = True
            except BaseException:
                cleanup_code = 'OWNED_PRIVATE_CLEANUP_UNCERTAIN'
        else:
            cleanup_code = cleanup_code or 'OWNED_WORKER_CLEANUP_UNCERTAIN'
        cleanup_error = original_error is None and cleanup_code is not None
        if cleanup_error:
            original_error = Rejected(cleanup_code)
        if original_error is not None:
            try:
                write(output / 'public/failure.json', {'schemaVersion': 1, 'state': 'FAILED_RESOURCES_AND_EVIDENCE_RETAINED',
                    'phase': phase, 'contextSha256': context_sha, 'sourcePackageSha256': package_sha,
                    'failureCode': str(original_error), 'cleanupCode': cleanup_code,
                    'ownedWorkerTerminal': terminal, 'privateMaterialRemoved': private_removed,
                    'automaticResubmissionAllowed': False, 'newNamespaceOutcome': 'REQUIRES_EXACT_REVIEW',
                    'completedAt': dt.datetime.now(dt.timezone.utc).isoformat()})
            except BaseException:
                # An evidence fsync failure cannot replace the original cause.
                # The process still exits nonzero and no success is published.
                pass
        if cleanup_error:
            raise original_error from None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['prepare-source', 'restore-on-es'])
    parser.add_argument('--context', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--source-package', type=Path)
    parser.add_argument('--source-package-sha256')
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        if args.worker:
            raw = regular(args.context, private=True)
            context = validate_context(decode(raw))
            context_sha = hashlib.sha256(raw).hexdigest()
            need(args.output == op_root(context) / args.phase and (args.output / 'intent.json').is_file(), 'WORKER_INTENT_REQUIRED')
            intent = read(args.output / 'intent.json', private=True)
            parent = process_identity(os.getppid())
            need(parent is not None and intent.get('parentProcess', {}).get('pid') == parent['pid']
                 and intent['parentProcess'].get('startTicks') == parent['startTicks']
                 and intent.get('phase') == args.phase and intent.get('contextSha256') == context_sha
                 and intent.get('sourcePackageSha256') == args.source_package_sha256
                 and os.getsid(0) == os.getpid(), 'WORKER_PARENT_BINDING')
            write(args.output / 'worker-entry.json', {'contextSha256': context_sha, 'pid': os.getpid(), 'parent': parent})
            worker(context, context_sha, args.phase, args.output, args.source_package, args.source_package_sha256,
                   parent_identity=intent['parentProcess'])
            return 0
        with interrupted_by_signals():
            result = run(args.context, args.phase, args.output, args.source_package, args.source_package_sha256)
        print(json.dumps(result))
        return 0
    except BaseException as error:
        code = str(error) if isinstance(error, Rejected) else 'HOST_OPERATION_FAILED'
        print(json.dumps({'state': 'FAILED_RESOURCES_AND_EVIDENCE_RETAINED', 'failureCode': code}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
