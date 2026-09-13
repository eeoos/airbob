#!/usr/bin/env python3
"""Pinned host bridge for B snapshot requests and preparation-only restoration.

The controller owns persistent AWS mutations and the six-hour session/lease.
This adapter publishes immutable public evidence, maintains live READ-lock ACKs,
and calls the unchanged snapshot implementation. It never imports SQL.
"""
from __future__ import annotations

import argparse
import datetime as dt
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import time
import zipfile

import growth_b_prepare as preparation
import growth_b_snapshot as snapshot

restore, contract = snapshot.restore, snapshot.contract
read, write, sha, require = preparation.read, restore.write, preparation.sha, preparation.require
ACCOUNT, REGION, BUCKET = preparation.ACCOUNT, preparation.REGION, preparation.BUCKET
EVIDENCE = 'airbob-performance-lab-evidence-' + ACCOUNT
KIND = 'global-growth-b-snapshot-host'
TOOLS = preparation.TOOLS + ('growth_b_snapshot.py', 'growth_b_snapshot_host.py')
MAX_SECONDS = 18000  # Retains the controller's cleanup margin in its six-hour session.
PUBLIC_FILES = {'snapshot-create-request.json', 'snapshot-provenance.json', 'snapshot-operation.json',
                'data-only-preparation.json', 'prepared-fingerprint.json', 'preflight.json', 'host-receipt.json',
                'source-deletion-admission.json', 'failure/snapshot-operation.json'}
SOURCE_OPERATIONS = {'create', 'retire'}


def sources(directory=None):
    return {name: sha((Path(directory) if directory else Path(__file__).parent) / name) for name in TOOLS}


def coordinates(dataset, run, operation):
    require(isinstance(dataset, str) and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', dataset), 'Exact B dataset required')
    for value, pattern in ((run, r'lab-[a-z0-9][a-z0-9-]{0,27}'), (operation, r'[a-z0-9][a-z0-9-]{2,47}')):
        require(isinstance(value, str) and re.fullmatch(pattern, value) and '--' not in value and not value.endswith('-'),
                'Safe exact host operation coordinates required')


def prefix(dataset, run, operation):
    coordinates(dataset, run, operation)
    return f'datasets/{dataset}-aws-snapshots/operations/{run}/{operation}/'


def evidence_prefix(dataset, run, operation):
    coordinates(dataset, run, operation)
    return f'data-bootstrap/{run}/{dataset}-snapshot/{operation}/'


def reference(value, expected_prefix, *, maximum=20 * preparation.GIB):
    require(isinstance(value, dict) and isinstance(value.get('key'), str)
            and value['key'].startswith(expected_prefix) and '..' not in value['key'].split('/')
            and re.fullmatch(r'[A-Za-z0-9_./-]+', value['key']), 'Artifact must use its exact approved prefix')
    preparation.object_reference(value, value['key'])
    require(value['bytes'] <= maximum, 'Artifact exceeds its public size bound')
    return value


def identity(value, run):
    require(set(value) == {'identifier', 'resourceId', 'endpoint', 'serverUuid'}
            and value['identifier'] == 'airbob-' + run
            and re.fullmatch(r'db-[A-Z0-9]+', value['resourceId'])
            and re.fullmatch(r'[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', value['endpoint'])
            and re.fullmatch(r'[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}', value['serverUuid']),
            'Exact RDS resource, endpoint and MySQL UUID required')


def validate_manifest(value, dataset, run, operation, expected_sources=None):
    coordinates(dataset, run, operation)
    common = {'schemaVersion', 'kind', 'operation', 'operationId', 'datasetId', 'runId', 'account', 'region', 'mysql',
              'snapshotIdentifier', 'application', 'awsPreparation', 'consumerTools', 'toolSources',
              'operationTimeoutSeconds', 'evidence'}
    mode = value.get('operation')
    require(mode in SOURCE_OPERATIONS | {'prepare'} and common | ({'source'} if mode in SOURCE_OPERATIONS else set()) <= set(value)
            and set(value) <= common | ({'source'} if mode in SOURCE_OPERATIONS else set()) | {'resume'}, 'Snapshot host manifest fields differ')
    require(value['schemaVersion'] == 1 and value['kind'] == KIND and value['datasetId'] == dataset
            and value['runId'] == run and value['operationId'] == operation and value['account'] == ACCOUNT
            and value['region'] == REGION and value['mysql'] == snapshot.MYSQL, 'Explicit B/V28 host manifest required')
    require(type(value['operationTimeoutSeconds']) is int and 60 <= value['operationTimeoutSeconds'] <= MAX_SECONDS,
            'Host operation must leave cleanup time within the six-hour controller session')
    require(re.fullmatch(r'airbob-dataset-b-[a-z0-9][a-z0-9-]{2,45}', value['snapshotIdentifier'])
            and '--' not in value['snapshotIdentifier'] and not value['snapshotIdentifier'].endswith('-'), 'New B snapshot name required')
    app = value['application']
    require(set(app) == {'mainCommit', 'image'} and re.fullmatch(r'[0-9a-f]{40}', app['mainCommit'])
            and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION + r'\.amazonaws\.com/airbob-repo@sha256:[0-9a-f]{64}', app['image']),
            'Exact application commit/image required')
    require(value['toolSources'] == (sources() if expected_sources is None else expected_sources)
            and set(value['toolSources']) == set(TOOLS), 'Snapshot host tools differ from the reviewed tree')
    ref = reference(value['awsPreparation'], f'datasets/{dataset}-aws-preparation/', maximum=4 * 1024**2)
    require(ref['key'] == f'datasets/{dataset}-aws-preparation/aws-preparation-{ref["sha256"]}.json', 'Canonical preparation manifest key required')
    ref = reference(value['consumerTools'], prefix(dataset, run, operation) + 'files/', maximum=4 * 1024**2)
    require(ref['key'] == prefix(dataset, run, operation) + f'files/{ref["sha256"]}-consumer-tools.tar.gz', 'Canonical snapshot helper key required')
    required_evidence = {'restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt'} if mode in SOURCE_OPERATIONS else {'provenance', 'admission', 'restoreEvent'}
    if mode == 'retire':
        required_evidence = required_evidence | {'provenance'}
    require(set(value['evidence']) == required_evidence, 'Exact operation evidence set required')
    for name, ref in value['evidence'].items():
        expected = (f'datasets/{dataset}-aws-snapshots/{value["snapshotIdentifier"]}/'
                    if name == 'provenance' and ref.get('key', '').startswith('datasets/') else 'data-bootstrap/')
        reference(ref, expected, maximum=4 * 1024**2)
        if name == 'provenance' and expected.startswith('datasets/'):
            require(ref['key'] == expected + f'provenance-{ref["sha256"]}.json', 'Canonical B provenance key required')
        elif name == 'provenance':
            match = re.fullmatch(r'data-bootstrap/(lab-[a-z0-9][a-z0-9-]{0,27})/' + dataset + r'-snapshot/([a-z0-9][a-z0-9-]{2,47})/snapshot-provenance\.json', ref['key'])
            require(match is not None, 'Canonical immutable source-host provenance key required')
            coordinates(dataset, match[1], match[2])
    if mode in SOURCE_OPERATIONS:
        source = value['source']
        require(set(source) == {'rds', 'restoreConfigSha256', 'privateAccountsSha256'}
                and all(preparation.digest(source[k]) for k in ('restoreConfigSha256', 'privateAccountsSha256')), 'Retained source hashes required')
        identity(source['rds'], run)
    if 'resume' in value:
        require(mode != 'retire', 'Retirement always rechecks a fresh source under the new lease')
        resumed = value['resume']
        require(set(resumed) == {'operationReceipt', 'restoreConfigSha256'} and preparation.digest(resumed['restoreConfigSha256']), 'Exact own retry inputs required')
        reference(resumed['operationReceipt'], f'data-bootstrap/{run}/{dataset}-snapshot/', maximum=4 * 1024**2)
        require(resumed['operationReceipt']['key'].endswith('/snapshot-operation.json')
                and not resumed['operationReceipt']['key'].startswith(evidence_prefix(dataset, run, operation)), 'Retry must name a previous operation receipt')
    return value


def validate_context(value, manifest, manifest_sha):
    require(set(value) == {'schemaVersion', 'kind', 'operation', 'operationId', 'datasetId', 'runId', 'manifest', 'toolSources',
                         'lease', 'rds', 'redisImage', 'hostInstanceId', 'deadlineEpoch', 'evidencePrefix', 'awsCli'}, 'Public context fields differ')
    require(value['schemaVersion'] == 1 and value['kind'] == KIND + '-context'
            and all(value[key] == manifest[key] for key in ('operation', 'operationId', 'datasetId', 'runId', 'toolSources')),
            'Controller and host manifest coordinates differ')
    expected = prefix(value['datasetId'], value['runId'], value['operationId']) + f'manifest-{manifest_sha}.json'
    preparation.object_reference(value['manifest'], expected)
    require(value['manifest']['sha256'] == manifest_sha and value['evidencePrefix'] == evidence_prefix(value['datasetId'], value['runId'], value['operationId']), 'Public manifest/output binding differs')
    snapshot.validate_lease(value['lease'], 'airbob-' + value['runId'])
    require(value['lease']['lockName'] == 'airbob-performance-lab' and value['lease']['command'] in {'up', 'measurement'}, 'Existing global controller fence required')
    require(type(value['deadlineEpoch']) is int and 0 < value['deadlineEpoch'] - time.time() <= MAX_SECONDS,
            'Expired or excessive controller command deadline')
    rds = value['rds']
    require(set(rds) == {'identifier', 'resourceId', 'endpoint', 'masterSecretArn'}
            and rds['identifier'] == 'airbob-' + value['runId'] and re.fullmatch(r'db-[A-Z0-9]+', rds['resourceId'])
            and re.fullmatch(r'[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', rds['endpoint'])
            and re.fullmatch(f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:[A-Za-z0-9/_+=.!@-]+', rds['masterSecretArn']), 'Controller RDS identity differs')
    require(re.fullmatch(r'i-[0-9a-f]{17}', value['hostInstanceId'])
            and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION + r'\.amazonaws\.com/[a-z0-9/_-]+@sha256:[0-9a-f]{64}', value['redisImage']), 'Pinned host and temporary Redis required')
    require(set(value['awsCli']) == {'version', 'archiveSha256'} and value['awsCli']['version'] == '2.34.64'
            and preparation.digest(value['awsCli']['archiveSha256']), 'Pinned AWS CLI archive required')
    return value


class DeadlineGuard:
    def __init__(self, raw_aws, context, root):
        self.lease = restore.Lease(raw_aws, context['lease'])
        self.deadline, self.root = context['deadlineEpoch'], Path(root)
        self.allow_stopped_source = context['operation'] == 'retire'

    def __call__(self, force=False):
        require(time.time() < self.deadline and (self.allow_stopped_source or not (self.root / 'STOP').exists()),
                'Controller deadline or host stop fence reached')
        self.lease(force=force)


class HostAws:
    """Closed host API: reads plus create-only evidence objects; no RDS writes."""
    READS = {('sts', 'get-caller-identity'), ('rds', 'describe-db-instances'), ('rds', 'describe-db-snapshots'),
             ('rds', 'describe-db-snapshot-attributes'), ('rds', 'list-tags-for-resource'),
             ('autoscaling', 'describe-auto-scaling-groups'), ('dynamodb', 'get-item'),
             ('secretsmanager', 'get-secret-value'), ('s3api', 'head-object'), ('s3api', 'get-object'),
             ('s3api', 'list-object-versions'), ('cloudwatch', 'get-metric-statistics')}

    def __init__(self, raw, guard, context):
        self.raw, self.guard, self.context = raw, guard, context
        self.read_lock_ack = None

    def call(self, *args):
        self.guard()
        operation = args[:2]
        if operation == ('s3api', 'put-object'):
            def option(key):
                return args[args.index(key) + 1] if key in args else None
            require(option('--bucket') == EVIDENCE and isinstance(option('--key'), str)
                    and option('--key').startswith(self.context['evidencePrefix'])
                    and option('--if-none-match') == '*' and option('--server-side-encryption') == 'AES256'
                    and option('--tagging') == 'Retention=summary', 'Host writes are limited to immutable operation evidence')
        else:
            require(operation in self.READS, 'Persistent AWS mutation and CloudTrail lookup are controller-only')
        if operation == ('rds', 'describe-db-snapshots') and self.read_lock_ack is not None:
            self.read_lock_ack()
        if operation == ('s3api', 'get-object'):
            # Full sealed dumps can exceed the ordinary metadata call timeout.
            import json
            result = json.loads(restore.command(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
                '--cli-connect-timeout', '5', '--cli-read-timeout', '30', *args], timeout=1800, guard=self.guard))
        else:
            result = self.raw.call(*args)
        self.guard()
        return result


def fetch(aws, ref, destination, bucket):
    destination = Path(destination)
    if destination.exists():
        require(destination.stat().st_size == ref['bytes'] and sha(destination) == ref['sha256'], 'Retained staged object changed')
        head = aws.call('s3api', 'head-object', '--bucket', bucket, '--key', ref['key'], '--version-id', ref['versionId'])
        require(head.get('VersionId') == ref['versionId'] and head.get('ContentLength') == ref['bytes'],
                'Retained bytes no longer have their exact remote object version')
        return read(destination) if destination.suffix == '.json' else None
    temporary = destination.with_name(destination.name + '.partial')
    require(not temporary.exists(), 'Interrupted staging object requires explicit operator review')
    response = aws.call('s3api', 'get-object', '--bucket', bucket, '--key', ref['key'], '--version-id', ref['versionId'], str(temporary))
    require(response.get('VersionId') == ref['versionId'] and temporary.stat().st_size == ref['bytes']
            and sha(temporary) == ref['sha256'], 'Pinned object VersionId, bytes or SHA differs')
    temporary.chmod(0o600); temporary.rename(destination)
    return read(destination) if destination.suffix == '.json' else None


def public_json(value):
    forbidden = {'password', 'passwordHash', 'SecretString', 'SecretBinary', 'accessKeyId', 'secretAccessKey',
                 'sessionToken', 'paymentKey', 'payload', 'authorization', 'cookie', 'credentials'}
    if isinstance(value, dict):
        require(not (set(value) & forbidden), 'Credential or payload fields cannot enter public snapshot evidence')
        for item in value.values():
            public_json(item)
    elif isinstance(value, list):
        for item in value:
            public_json(item)


class Publisher:
    def __init__(self, aws, context, directory):
        self.aws, self.context, self.directory = aws, context, Path(directory)
        self.objects = {}

    def publish(self, name, path):
        require(name in PUBLIC_FILES or re.fullmatch(r'read-lock-acks/[0-9]+-[0-9]{6}\.json', name), 'Unapproved public artifact name')
        path = Path(path)
        require(path.stat().st_size <= 4 * 1024**2, 'Public evidence exceeds its bound')
        public_json(read(path))
        checksum = sha(path)
        if name in self.objects:
            require(self.objects[name]['sha256'] == checksum, 'Published evidence cannot be overwritten')
            return self.objects[name]
        key = self.context['evidencePrefix'] + name
        result = self.aws.call('s3api', 'put-object', '--bucket', EVIDENCE, '--key', key, '--body', str(path),
            '--server-side-encryption', 'AES256', '--tagging', 'Retention=summary', '--if-none-match', '*')
        ref = {'key': key, 'versionId': result.get('VersionId'), 'sha256': checksum, 'bytes': path.stat().st_size}
        reference(ref, self.context['evidencePrefix'], maximum=4 * 1024**2)
        readback = self.directory / ('readback-' + name.replace('/', '-') + '.json')
        fetch(self.aws, ref, readback, EVIDENCE)
        self.objects[name] = ref
        return ref


class ReadLockAcks:
    def __init__(self, publisher, db, manifest_sha, request_ref, request):
        self.publisher, self.db = publisher, db
        self.manifest_sha, self.request_ref, self.request = manifest_sha, request_ref, request
        self.sequence, self.last = 0, 0.0

    def __call__(self):
        if self.sequence and time.monotonic() - self.last < 10:
            return
        self.db.guard(force=True)
        connection = getattr(self.db, 'snapshot_read_lock_connection_id', None)
        require(type(connection) is int and connection > 0, 'Live frozen READ-lock connection is required for ACK')
        now = dt.datetime.now(dt.timezone.utc)
        deadline = min(self.publisher.context['deadlineEpoch'], now.timestamp() + 45)
        require(deadline > now.timestamp(), 'Cannot acknowledge an expired controller deadline')
        self.sequence += 1
        name = f'read-lock-acks/{int(now.timestamp())}-{self.sequence:06d}.json'
        path = self.publisher.directory / name.replace('/', '-')
        write(path, {'schemaVersion': 1, 'kind': KIND + '-read-lock-ack', 'state': 'SOURCE_READ_LOCK_HELD',
            'manifestSha256': self.manifest_sha, 'requestSha256': self.request_ref['sha256'],
            'requestVersionId': self.request_ref['versionId'], 'requestKey': self.request_ref['key'],
            'lease': self.request['requiredLease'], 'source': self.request['source'],
            'snapshotIdentifier': self.request['snapshotIdentifier'], 'connectionId': connection,
            'hostInstanceId': self.publisher.context['hostInstanceId'], 'observedAt': now.isoformat(),
            'expiresAt': dt.datetime.fromtimestamp(deadline, dt.timezone.utc).isoformat(),
            'sequence': self.sequence, 'hostCreatesOrDeletesAwsResources': False})
        self.publisher.publish(name, path)
        self.last = time.monotonic()


def local_ref(path):
    return {'path': str(Path(path).resolve()), 'sha256': sha(path)}


def cloudtrail_projection(value):
    require(set(value) <= {'eventName', 'eventSource', 'awsRegion', 'recipientAccountId', 'eventID', 'eventTime',
                          'requestParameters', 'errorCode'} and set(value) >= {'eventName', 'eventSource', 'awsRegion',
                          'recipientAccountId', 'eventID', 'eventTime', 'requestParameters'}, 'Use the exact public CloudTrail projection')
    require(set(value['requestParameters']) == {'dBInstanceIdentifier', 'dBSnapshotIdentifier'}
            and re.fullmatch(r'[0-9a-f-]{36}', value['eventID']) and not value.get('errorCode'), 'CloudTrail payload/credentials or failed restore are forbidden')
    snapshot.instant(value['eventTime'])
    return value


def provenance_source_binding(ref, core):
    if ref['key'].startswith('data-bootstrap/'):
        source_run = ref['key'].split('/')[1]
        require(core['source']['identifier'] == 'airbob-' + source_run, 'Host provenance prefix names a different source run')


def artifact_bucket(ref):
    return BUCKET if ref['key'].startswith('datasets/') else EVIDENCE


def stage_target(manifest, context, wrapper, root, output, aws, guard):
    """Stage exact public artifacts; never invoke prepare_host/SQL import."""
    for key, ref in wrapper['files'].items():
        if key not in {'toolchain', 'toolchainManifest', 'consumerTools'}:
            fetch(aws, ref, root / preparation.FILES.get(key, 'small-rds-receipt.json'), BUCKET)
    envelope = read(root / 'envelope.json')
    preparation.validate_envelope_metadata(wrapper, envelope)
    release = root / 'release'
    if not release.exists():
        require(shutil.disk_usage(root).free >= sum(r['bytes'] for r in envelope['objects'].values())
                + envelope['storage']['requiredAdditionalDataHostFreeBytes'] + 2 * envelope['storage']['runtimeExtractionBytes'], 'Insufficient snapshot staging capacity')
        release.mkdir(mode=0o700)
    for name, ref in envelope['objects'].items():
        fetch(aws, ref, release / name, BUCKET)
    migrations = root / 'migrations'; migrations.mkdir(mode=0o700, exist_ok=True)
    with zipfile.ZipFile(root / 'app.jar') as archive:
        names = set()
        for item in archive.infolist():
            name = item.filename.removeprefix('BOOT-INF/classes/db/migration/')
            if item.filename.startswith('BOOT-INF/classes/db/migration/') and re.fullmatch(r'V[0-9]+__[A-Za-z0-9_.-]+\.sql', name):
                require(name not in names, 'Duplicate JAR migration'); names.add(name)
                target = migrations / name; content = archive.read(item)
                if target.exists():
                    require(not target.is_symlink() and target.read_bytes() == content, 'Retained migration changed')
                else:
                    target.write_bytes(content); target.chmod(0o600)
        require({p.name for p in migrations.iterdir()} == names, 'Unexpected retained migration file')
    import growth_b_aws_contract as aws_contract
    aws_contract.validate_envelope(root / 'envelope.json', wrapper['files']['envelope']['sha256'], release, migrations,
                                  root / 'publication-receipt.json', root / 'app.jar')
    retained = root / 'restore-config.json'
    if retained.exists():
        require('resume' in manifest and sha(retained) == manifest['resume']['restoreConfigSha256'], 'An existing target requires its exact preparation retry receipt/config')
        value = restore.configuration(retained)
        require(all(value['rds'][key] == item for key, item in context['rds'].items())
                and value['envelopeSha256'] == wrapper['files']['envelope']['sha256'], 'Retained retry target/release differs')
        return value
    require('resume' not in manifest, 'Preparation retry requires retained credentials and config')
    return {'schemaVersion': 1, 'mode': 'aws-global-b', 'release': str(release), 'migrationDirectory': str(migrations),
        'publicationReceipt': str(root / 'publication-receipt.json'), 'envelope': str(root / 'envelope.json'),
        'envelopeSha256': wrapper['files']['envelope']['sha256'], 'appJar': str(root / 'app.jar'),
        'privateAccounts': str(root / 'private/accounts.private.json'), 'writerAsgNames': [],
        'operationTimeoutSeconds': manifest['operationTimeoutSeconds'], 'appStartupTimeoutSeconds': manifest['operationTimeoutSeconds'],
        'redisImage': context['redisImage'], 'replacementPolicy': 'empty-only', 'lease': context['lease'],
        'rds': context['rds'] | {'serverUuid': '00000000-0000-0000-0000-000000000000',
            'caBundle': str(root / 'rds-ca.pem'), 'caBundleSha256': wrapper['files']['rdsCaBundle']['sha256']},
        'smallRdsReceipt': local_ref(root / 'small-rds-receipt.json'),
        'binlogBudget': {'additionalReserveBytes': wrapper['binlogAdditionalReserveBytes'],
            'basisFile': str(root / 'binlog-budget.json'), 'basisSha256': wrapper['files']['binlogBasis']['sha256']}}


def validate_host_paths(root, output, context):
    require(root == Path('/opt/airbob/global-b') / context['runId'] and root.resolve() == root
            and output == root / ('snapshot-' + context['operationId']) and output.resolve() == output, 'Canonical private host paths required')


def host(manifest, context, root, output, manifest_sha):
    started, started_at = time.monotonic(), snapshot.now()
    root, output = Path(root), Path(output)
    validate_host_paths(root, output, context)
    require(output.is_dir() and (output / 'aws-snapshot-host.json').is_file()
            and sha(output / 'aws-snapshot-host.json') == manifest_sha, 'Trusted bootstrap stage is missing or changed')
    validate_context(context, manifest, manifest_sha)
    raw = restore.Aws()
    caller = raw.call('sts', 'get-caller-identity')
    require(caller.get('Account') == ACCOUNT and caller.get('Arn') ==
        f'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-lab-host-{context["runId"]}-debezium/{context["hostInstanceId"]}', 'The exact controller-selected EC2 role/session is required')
    guard = DeadlineGuard(raw, context, root); guard(force=True)
    aws = HostAws(raw, guard, context)
    publisher = Publisher(aws, context, output)
    receipt = {'schemaVersion': 1, 'kind': snapshot.KIND + '-operation', 'operation': manifest['operation'],
               'toolIdentity': snapshot.tool_identity(), 'events': [], 'sourceDeletionAllowed': False}
    def event(state, **values):
        at = snapshot.now(); receipt.update(state=state, updatedAt=at, **values); receipt.setdefault('startedAt', at)
        receipt['events'].append({'state': state, 'at': at}); write(output / 'execute/snapshot-operation.json', receipt)
        if state == 'CREATE_REQUESTED':
            request_path = output / 'execute/snapshot-create-request.json'
            db.guard(force=True)
            request = read(request_path)
            require(request['requiredLease'] == context['lease'] and request['source'] == manifest['source']['rds']
                    and request['snapshotIdentifier'] == manifest['snapshotIdentifier'], 'Frozen create request binding differs')
            ref = publisher.publish('snapshot-create-request.json', request_path)
            aws.read_lock_ack = ReadLockAcks(publisher, db, manifest_sha, ref, request)
            aws.read_lock_ack()
        if state in {'SNAPSHOT_SOURCE_REVERIFIED', snapshot.AVAILABLE}:
            aws.read_lock_ack = None

    private = restore.private_directory(output / '.private')
    execute = restore.private_directory(output / 'execute')
    restore.private_directory(execute / '.private')
    primary = None
    def cleanup():
        failures = []
        for path in (private, execute / '.private'):
            if path.exists():
                try:
                    shutil.rmtree(path)
                except BaseException as error:
                    failures.append(type(error).__name__)
        if failures:
            write(output / 'cleanup-failure.json', {'state': 'PRIVATE_CONNECTION_CLEANUP_INCOMPLETE', 'errorTypes': failures})
            require(False, 'Private connection cleanup did not complete')
    try:
        wrapper = fetch(aws, manifest['awsPreparation'], output / 'aws-preparation.json', BUCKET)
        preparation.validate_manifest(wrapper, manifest['datasetId'], {k: manifest['toolSources'][k] for k in preparation.TOOLS})
        require(wrapper['scope'] == 'final-b-rds', 'Snapshots require the final B preparation manifest')
        preparation.qualify_toolchain(root / 'toolchain', read(root / 'toolchain.json'), wrapper['toolchain'])
        require(preparation.run(['aws', '--version']).decode().split()[0] == 'aws-cli/2.34.64', 'Pinned AWS CLI differs')
        for name, ref in manifest['evidence'].items():
            fetch(aws, ref, output / (name + '.json'), artifact_bucket(ref))
        if manifest['operation'] in SOURCE_OPERATIONS:
            retained = root / 'restore-config.json'
            require(sha(retained) == manifest['source']['restoreConfigSha256'], 'Retained source configuration changed')
            value = restore.configuration(retained)
            require(restore.target_identity(value) == manifest['source']['rds']
                    and all(value['rds'][k] == v for k, v in context['rds'].items())
                    and sha(value['privateAccounts']) == manifest['source']['privateAccountsSha256'], 'Retained source identity/private handoff changed')
            if 'resume' in manifest:
                require(sha(retained) == manifest['resume']['restoreConfigSha256'], 'Source retry configuration changed')
        else:
            cloudtrail_projection(read(output / 'restoreEvent.json'))
            value = stage_target(manifest, context, wrapper, root, output, aws, guard)
        require(value['envelopeSha256'] == wrapper['files']['envelope']['sha256'], 'Preparation manifest and retained release differ')
        value['lease'] = context['lease']
        value['operationTimeoutSeconds'] = value['appStartupTimeoutSeconds'] = manifest['operationTimeoutSeconds']
        value['writerAsgNames'] = ['airbob-' + context['runId'] + '-app'] if manifest['operation'] in SOURCE_OPERATIONS else []
        runtime = restore.extract_runtime(Path(value['release']), output / 'runtime')
        qualification = snapshot.qualify_runtime(Path(value['release']), runtime, output / 'host-runtime.json',
            expected_checks={name: item['sha256'] for name, item in read(root / 'envelope.json')['objects'].items()})
        staged_at, staging_seconds = snapshot.now(), round(time.monotonic() - started, 6)
        with snapshot.activated_runtime(qualification):
            guard(force=True)
            live = restore.live_rds(aws, value)
            db, environment = restore.connection(value, aws, live, private, guard)
            actual_uuid = db.scalar('SELECT @@server_uuid', False)
            if manifest['operation'] == 'prepare' and not (root / 'restore-config.json').exists():
                core = snapshot.validate_provenance(read(output / 'provenance.json'))
                provenance_source_binding(manifest['evidence']['provenance'], core)
                require(actual_uuid != core['source']['serverUuid'], 'Restored target must have its own new server UUID')
                value['rds']['serverUuid'] = actual_uuid
                identity(restore.target_identity(value), context['runId'])
                accounts = preparation.load_sealed_accounts(Path(value['release']), runtime)
                accounts.create_private_credentials(read(Path(value['release']) / 'accounts.json'), root / 'private', restore.credential_environment(value))
                preparation.write(root / 'restore-config.json', value)
            require(actual_uuid == value['rds']['serverUuid'], 'MySQL UUID differs from the exact retained target')
            envelope = restore.validate_inputs(value)
            configuration = {'schemaVersion': 1, 'kind': snapshot.CONFIG_KIND,
                'operation': 'create' if manifest['operation'] in SOURCE_OPERATIONS else 'prepare',
                'operationTimeoutSeconds': manifest['operationTimeoutSeconds'], 'restoreConfig': local_ref(root / 'restore-config.json')}
            derived = output / 'restore-config.json'; preparation.write(derived, value)
            configuration['restoreConfig'] = local_ref(derived)
            if manifest['operation'] in SOURCE_OPERATIONS:
                configuration.update({k: local_ref(output / (k + '.json')) for k in ('restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt')})
                configuration.update(snapshotIdentifier=manifest['snapshotIdentifier'], application=manifest['application'],
                                     privateHandoff=local_ref(value['privateAccounts']))
            else:
                configuration.update({k: local_ref(output / (k + '.json')) for k in ('provenance', 'admission', 'restoreEvent')})
                core = snapshot.validate_provenance(read(output / 'provenance.json'))
                provenance_source_binding(manifest['evidence']['provenance'], core)
                require(core['snapshotIdentifier'] == manifest['snapshotIdentifier']
                        and {k: core['application'][k] for k in ('mainCommit', 'image')} == manifest['application'], 'Snapshot application/name differs')
            if 'resume' in manifest:
                fetch(aws, manifest['resume']['operationReceipt'], output / 'resume-operation.json', EVIDENCE)
                configuration['resumeReceipt'] = local_ref(output / 'resume-operation.json')
            config_path = output / 'snapshot-config.json'; preparation.write(config_path, configuration)
            snapshot.config(config_path)
            receipt['configSha256'] = sha(config_path)
            preflight_output = restore.private_directory(output / 'preflight')
            if manifest['operation'] == 'retire':
                provenance = read(output / 'provenance.json')
                core = snapshot.verify(aws, provenance)
                provenance_source_binding(manifest['evidence']['provenance'], core)
                require(core == snapshot.core_contract(configuration, value, envelope), 'Retirement source evidence/contract differs')
                publisher.publish('snapshot-provenance.json', output / 'provenance.json')
                reviewed = {'schemaVersion': 1, 'kind': snapshot.KIND + '-preflight', 'state': 'SOURCE_RETIREMENT_PREFLIGHT',
                            'contractSha256': snapshot.canonical_sha(core), 'toolIdentity': snapshot.tool_identity(),
                            'provenanceSha256': manifest['evidence']['provenance']['sha256'], 'recordedAt': snapshot.now()}
            else:
                function = snapshot.source_preflight if manifest['operation'] == 'create' else snapshot.prepare_preflight
                reviewed = function(configuration, value, envelope, preflight_output, aws, runtime, db, environment)
            reviewed['configSha256'] = sha(config_path)
            preparation.write(preflight_output / 'preflight.json', reviewed)
            reviewed_sha = sha(preflight_output / 'preflight.json')
            publisher.publish('preflight.json', preflight_output / 'preflight.json')
            guard(force=True)
            require(sha(config_path) == reviewed['configSha256'] and sha(preflight_output / 'preflight.json') == reviewed_sha,
                    'Snapshot config/preflight changed before execution')
            receipt['preflightSha256'] = reviewed_sha
            execution_started_at = snapshot.now()
            if manifest['operation'] != 'retire':
                function = snapshot.create_snapshot if manifest['operation'] == 'create' else snapshot.prepare_restored
                completed = function(configuration, value, envelope, reviewed, execute, aws, runtime, db, environment, event)
                if manifest['operation'] == 'create':
                    provenance = completed
                    publisher.publish('snapshot-provenance.json', execute / 'snapshot-provenance.json')
            if manifest['operation'] in SOURCE_OPERATIONS:
                admission = snapshot.deletion_admission(aws, provenance, configuration, value, envelope, runtime, db, environment, execute)
                write(execute / 'source-deletion-admission.json', admission)
                publisher.publish('source-deletion-admission.json', execute / 'source-deletion-admission.json')
                if manifest['operation'] == 'retire':
                    event('SOURCE_DELETION_ADMISSION_ISSUED', contractSha256=admission['contractSha256'],
                          sourceRetired=False, sourceDeletionExecuted=False)
        cleanup()
        for name in ('snapshot-operation.json', 'snapshot-provenance.json', 'data-only-preparation.json', 'prepared-fingerprint.json'):
            if (execute / name).exists():
                publisher.publish(name, execute / name)
        result = {'schemaVersion': 1, 'kind': KIND + '-receipt', 'state': 'HOST_OPERATION_COMPLETE',
            'operation': manifest['operation'], 'operationId': manifest['operationId'], 'datasetId': manifest['datasetId'],
            'runId': context['runId'], 'manifestSha256': manifest_sha, 'toolSources': manifest['toolSources'],
            'hostInstanceId': context['hostInstanceId'], 'targetIdentity': restore.target_identity(value),
            'restoreConfigSha256': sha(root / 'restore-config.json'), 'derivedRestoreConfigSha256': sha(derived),
            'preflightSha256': reviewed_sha,
            'objects': {k: v for k, v in publisher.objects.items() if not k.startswith('read-lock-acks/')},
            'completedAt': snapshot.now(), 'sourceRetired': False,
            'timings': {'startedAt': started_at, 'stagingCompletedAt': staged_at, 'stagingSeconds': staging_seconds,
                'executionStartedAt': execution_started_at, 'elapsedSeconds': round(time.monotonic() - started, 6),
                'controllerDeadlineEpoch': context['deadlineEpoch']},
            'persistentAwsResourcesMutatedByHost': False, 'sqlImportExecuted': False, 'sourceDeletionAllowed': False,
            'applicationLeftRunning': False, 'deploymentReady': False}
        write(output / 'host-receipt.json', result)
        publisher.publish('host-receipt.json', output / 'host-receipt.json')
        return result
    except BaseException as error:
        primary = error
        aws.read_lock_ack = None
        try:
            retry_config = {name: sha(path) for name, path in (
                ('restoreConfigSha256', root / 'restore-config.json'),
                ('derivedRestoreConfigSha256', output / 'restore-config.json')) if path.is_file() and not path.is_symlink()}
            event('FAILED_RESOURCES_RETAINED', errorType=type(error).__name__, resourceDeletionExecuted=False, deploymentReady=False,
                  sourceRetired=False, **retry_config,
                  publicObjects={k: v for k, v in publisher.objects.items() if not k.startswith('read-lock-acks/')})
            try:
                publisher.publish('snapshot-operation.json', execute / 'snapshot-operation.json')
            except BaseException:
                # A completed core receipt may already be immutable when a later
                # publication fails. Preserve it and issue a separate own retry proof.
                publisher.publish('failure/snapshot-operation.json', execute / 'snapshot-operation.json')
        except BaseException as publication_error:
            try:
                write(output / 'publication-failure.json', {'state': 'FAILURE_RECEIPT_NOT_PUBLISHED', 'errorType': type(publication_error).__name__})
            except BaseException:
                if hasattr(error, 'add_note'):
                    error.add_note('Failure evidence could not be persisted; source remains retained.')
        raise
    finally:
        aws.read_lock_ack = None
        try:
            cleanup()
        except BaseException:
            if primary is None:
                raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['validate', 'host'])
    parser.add_argument('--manifest', type=Path, required=True); parser.add_argument('--sha256', required=True)
    parser.add_argument('--dataset-id', required=True); parser.add_argument('--run-id', required=True); parser.add_argument('--operation-id', required=True)
    parser.add_argument('--context', type=Path); parser.add_argument('--root', type=Path); parser.add_argument('--output', type=Path)
    args = parser.parse_args(); os.umask(0o077)
    try:
        require(preparation.digest(args.sha256) and sha(args.manifest) == args.sha256, 'Reviewed host manifest SHA differs')
        manifest = validate_manifest(read(args.manifest), args.dataset_id, args.run_id, args.operation_id)
        if args.mode == 'validate':
            print('{"state":"SNAPSHOT_HOST_MANIFEST_VALIDATED","cloudMutationsExecuted":false}'); return
        require(args.context and args.root and args.output, 'Host context and paths are required')
        def stopped(signum, frame):
            raise InterruptedError('Snapshot host stopped')
        for signal_number in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
            signal.signal(signal_number, stopped)
        result = host(manifest, read(args.context), args.root, args.output, args.sha256)
        print('{"state":"' + result['state'] + '","deploymentReady":false}')
    except BaseException:
        raise SystemExit('B snapshot host operation failed; private evidence retained and source deletion remains forbidden.') from None


if __name__ == '__main__':
    main()
