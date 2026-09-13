#!/usr/bin/env python3
"""Global B/MySQL 8.4.11/V28 snapshot provenance and sequential restore admission.

The controller owns snapshot creation, RDS deletion and restoration. This tool
holds the source READ locks while requesting and observing snapshot creation,
then verifies the actual restored target before canonical preparation only.
All mutation modes require an exact reviewed preflight and a live controller lease.
"""
from __future__ import annotations
import argparse
from contextlib import contextmanager
import datetime as dt
import importlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import subprocess
import sys
import threading
import time

import growth_b_aws_restore as restore
import growth_b_contract as contract
from growth_b_inventory import inventory_date_vector, inventory_horizon_at_vector, write
from growth_b_runtime import activated_runtime, qualify_runtime

ACCOUNT, REGION = restore.ACCOUNT, restore.REGION
KIND = 'global-growth-b-rds-snapshot-provenance'
CONFIG_KIND = 'global-growth-b-rds-snapshot-configuration'
AVAILABLE = 'SNAPSHOT_AVAILABLE_PROVENANCE_VERIFIED'
PREPARED = 'SNAPSHOT_RESTORED_BASELINE_AND_PREPARATION_VERIFIED'
MYSQL = {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'}
sha, read, require, digest = contract.sha, contract.read, contract.require, contract.digest
canonical_sha = restore.canonical_sha
RUN = r'airbob-lab-[a-z0-9][a-z0-9-]{0,27}'


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def instant(value):
    require(isinstance(value, str), 'An explicit UTC timestamp is required')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    require(result.tzinfo is not None, 'Naive evidence timestamps are forbidden')
    return result


def tool_identity():
    return restore.tool_identity() | {'growth_b_snapshot.py': sha(Path(__file__))}


def reference(value, *, private=False):
    require(isinstance(value, dict) and set(value) == {'path', 'sha256'} and isinstance(value['path'], str)
            and Path(value['path']).is_absolute() and digest(value['sha256']), 'Exact absolute evidence path/SHA required')
    path = contract.regular(value['path'])
    require(sha(path) == value['sha256'], 'Reviewed evidence bytes changed')
    if private:
        require(path.stat().st_mode & 0o077 == 0 and path.parent.stat().st_mode & 0o077 == 0,
                'Preserved private handoff requires a private directory and file')
    return path


def config(path):
    value = read(path)
    common = {'schemaVersion', 'kind', 'operation', 'operationTimeoutSeconds'}
    operations = {
        'create': {'restoreConfig', 'restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt', 'privateHandoff',
                   'snapshotIdentifier', 'application'},
        'restore-admission': {'provenance', 'targetIdentifier', 'lease'},
        'prepare': {'provenance', 'restoreConfig', 'admission', 'restoreEvent'},
    }
    operation = value.get('operation')
    require(operation in operations and common | operations[operation] <= set(value)
            and set(value) <= common | operations[operation] | {'resumeReceipt'}, 'Snapshot configuration fields differ')
    require(value['schemaVersion'] == 1 and value['kind'] == CONFIG_KIND
            and contract.integer(value['operationTimeoutSeconds'], 60) and value['operationTimeoutSeconds'] <= 86400,
            'B snapshot configuration/deadline differs')
    for key in ('restoreConfig', 'restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt', 'privateHandoff',
                'provenance', 'admission', 'restoreEvent', 'resumeReceipt'):
        if key in value:
            reference(value[key], private=key == 'privateHandoff')
    if operation == 'create':
        identifier = value['snapshotIdentifier']
        require(isinstance(identifier, str) and re.fullmatch(r'airbob-dataset-b-[a-z0-9][a-z0-9-]{2,45}', identifier)
                and '--' not in identifier and not identifier.endswith('-'), 'A unique B-only snapshot name is required')
        app = value['application']
        require(set(app) == {'mainCommit', 'image'} and re.fullmatch(r'[0-9a-f]{40}', app['mainCommit'])
                and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION + r'\.amazonaws\.com/airbob-repo@sha256:[0-9a-f]{64}', app['image']),
                'Exact current application commit and immutable image required')
    if operation == 'restore-admission':
        require('resumeReceipt' not in value, 'Restore admission cannot reuse a failed mutation receipt')
        require(re.fullmatch(RUN, value['targetIdentifier']) and not value['targetIdentifier'].endswith('-')
                and '--' not in value['targetIdentifier'], 'Exact next lab RDS identifier required')
        validate_lease(value['lease'], value['targetIdentifier'])
    return value


def validate_lease(value, identifier):
    require(set(value) == {'table', 'lockName', 'owner', 'runId', 'command', 'fencingToken'}
            and value['table'] == 'airbob-performance-lab-orchestration-lease'
            and value['runId'] == identifier.removeprefix('airbob-') and value['command'] in {'up', 'measurement'}
            and contract.integer(value['fencingToken'], 1)
            and all(isinstance(value[key], str) and re.fullmatch(r'[A-Za-z0-9_.:@/-]{3,255}', value[key])
                    for key in ('owner', 'lockName')), 'Exact active target controller lease required')


def restore_inputs(configuration):
    value = restore.configuration(reference(configuration['restoreConfig']))
    require('lease' in value, 'Snapshot operations require the existing controller lease')
    envelope = restore.validate_inputs(value)
    require(envelope['finalScaleSelected'] is True and envelope['awsExecutionAllowed'] is True,
            'Only the sealed final B dataset may produce or consume this snapshot contract')
    return value, envelope


def validate_source_evidence(configuration, value, envelope):
    proof = read(reference(configuration['restoreReceipt']))
    baseline = read(reference(configuration['preparedFingerprint']))
    require(proof.get('schemaVersion') == 1 and proof.get('kind') == 'global-growth-b-aws-restore-receipt'
            and proof.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED'
            and proof.get('toolIdentity') == restore.tool_identity() and proof.get('targetIdentity') == restore.target_identity(value)
            and proof.get('datasetId') == envelope['datasetId'] and proof.get('envelopeSha256') == value['envelopeSha256']
            and proof.get('account') == ACCOUNT and proof.get('region') == REGION
            and proof.get('mysqlVersion') == '8.4.11' and proof.get('flywayVersion') == 28
            and proof.get('finalScaleSelected') is True and proof.get('executionScope') == 'final-b-rds'
            and proof.get('allRowsAndDdlEqual') is True and proof.get('awsWritesExecuted') is True
            and proof.get('previousBusinessSchemaAbsent') is True and proof.get('maximumSimultaneousBusinessDatabases') == 1
            and proof.get('sealedFingerprint') == read(Path(value['release']) / 'before-fingerprint.json')
            and proof.get('appJarSha256') == envelope['appJarSha256']
            and proof.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256']
            and proof.get('smallRdsPrerequisite', {}).get('sameToolApplicationAndV28Verified') is True,
            'A completed same-tool final B RDS restore with its small prerequisite is required')
    prepared = proof.get('preparation', {})
    require(prepared.get('passed') is True and prepared.get('readinessVerified') is True
            and prepared.get('accountLogins', {}).get('passed') is True
            and prepared.get('currentInventory', {}).get('everyHorizonContiguous') is True
            and digest(prepared.get('ownerSha256BeforeAndAfter'))
            and prepared.get('applicationLeftRunning') is False, 'Successful canonical preparation proof is incomplete')
    contract.validate_fingerprint(baseline, require_sealed=False)
    restore.validate_prepared_changes(proof['sealedFingerprint'], baseline)
    observed = read(reference(configuration['serviceResetReceipt']))
    require(observed.get('schemaVersion') == 1 and observed.get('kind') == 'global-growth-b-aws-service-reset-verification'
            and observed.get('state') == 'SERVICE_VERIFIED_AND_RESET' and observed.get('mysql') == MYSQL
            and observed.get('datasetId') == envelope['datasetId'] and observed.get('targetIdentity') == restore.target_identity(value)
            and observed.get('application') == configuration['application'] | {'appJarSha256': envelope['appJarSha256'],
                 'migrationFilesSha256': envelope['objects']['migration-files.json']['sha256']}
            and observed.get('restoreReceiptSha256') == configuration['restoreReceipt']['sha256']
            and observed.get('preparedFingerprintSha256') == configuration['preparedFingerprint']['sha256']
            and all(observed.get(key) is True for key in ('writersStopped', 'cdcStopped', 'sourceOriginalsUnchanged')),
            'Actual service and reset evidence must identify the exact prepared B target')
    service = observed.get('service', {})
    require(all(service.get(key) is True for key in ('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed',
                'globalSearchPassed', 'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed'))
            and service.get('representativeAccounts') == 3, 'R4 normal service, real API/CDC/ES and representative checks are incomplete')
    reset = observed.get('reset', {})
    require(all(reset.get(key) is True for key in ('passed', 'testMutationRemoved', 'unchangedDomainAndDdl'))
            and reset.get('remainingOutboxRows') == 0
            and reset.get('ownerSha256BeforeAndAfter') == prepared['ownerSha256BeforeAndAfter'],
            'Test mutations must be reset without changing historical ownership')
    require(instant(observed['completedAt']) >= instant(proof['preparationCompletedAt']), 'Reset proof predates source preparation')
    reference(configuration['privateHandoff'], private=True)
    return proof, baseline, observed


def core_contract(configuration, value, envelope):
    proof, baseline, observed = validate_source_evidence(configuration, value, envelope)
    return {'schemaVersion': 1, 'kind': KIND, 'account': ACCOUNT, 'region': REGION, 'mysql': MYSQL,
        'datasetId': envelope['datasetId'], 'source': restore.target_identity(value),
        'application': observed['application'], 'toolIdentity': tool_identity(),
        'publication': {'envelopeSha256': value['envelopeSha256'], 'publicationReceiptSha256': sha(value['publicationReceipt']),
            'consumerManifestSha256': envelope['consumerManifestSha256'], 'checksumsSha256': envelope['checksumsSha256'],
            'objects': envelope['objects']},
        'evidence': {key + 'Sha256': configuration[key]['sha256'] for key in
                     ('restoreReceipt', 'preparedFingerprint', 'serviceResetReceipt', 'privateHandoff')},
        'sealedFingerprint': proof['sealedFingerprint'], 'preparedFingerprint': baseline,
        'preparedFingerprintCanonicalSha256': canonical_sha(baseline),
        'ownerSha256': proof['preparation']['ownerSha256BeforeAndAfter'], 'snapshotIdentifier': configuration['snapshotIdentifier']}


def snapshot_tags(core):
    return {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'dataset',
        'ManagedBy': 'global-b-snapshot', 'Persistence': 'persistent', 'BProvenanceSchemaVersion': '1',
        'DatasetId': core['datasetId'], 'SourceRdsResourceId': core['source']['resourceId'],
        'SourceMysqlUuid': core['source']['serverUuid'], 'SourceRunId': core['source']['identifier'].removeprefix('airbob-'),
        'MysqlVersion': '8.4.11', 'FlywayVersion': '28', 'AppJarSha256': core['application']['appJarSha256'],
        'DumpSha256': core['publication']['objects']['airbob-growth.sql.gz']['sha256'],
        'ConsumerManifestSha256': core['publication']['consumerManifestSha256'],
        'EnvelopeSha256': core['publication']['envelopeSha256'], 'SnapshotContractSha256': canonical_sha(core),
        'PreparedFingerprintSha256': core['evidence']['preparedFingerprintSha256'],
        'SourceRestoreReceiptSha256': core['evidence']['restoreReceiptSha256'],
        'SourceServiceResetReceiptSha256': core['evidence']['serviceResetReceiptSha256'],
        'PrivateHandoffSha256': core['evidence']['privateHandoffSha256']}


def tags(value):
    require(isinstance(value, list), 'AWS tags are missing')
    result = {item['Key']: item['Value'] for item in value}
    require(len(result) == len(value), 'AWS tags contain duplicate keys')
    return result


def account(aws):
    require(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'Unexpected AWS snapshot account')


def instances(aws):
    return aws.call('rds', 'describe-db-instances')['DBInstances']


def source_instance(aws, value):
    live = restore.live_rds(aws, value)
    all_instances = instances(aws)
    require(len(all_instances) == 1 and all_instances[0]['DbiResourceId'] == value['rds']['resourceId'],
            'Snapshot source must be the sole existing RDS instance in the approved region')
    item = all_instances[0]
    require(item.get('StorageEncrypted') is True and isinstance(item.get('KmsKeyId'), str)
            and item['KmsKeyId'].startswith(f'arn:aws:kms:{REGION}:{ACCOUNT}:key/')
            and item.get('StorageType') == 'gp3', 'B snapshot source requires encrypted gp3 with an exact KMS key')
    return live, {'identifier': item['DBInstanceIdentifier'], 'resourceId': item['DbiResourceId'],
                  'kmsKeyArn': item['KmsKeyId'], 'allocatedStorageGiB': item['AllocatedStorage'], 'storageType': 'gp3',
                  'instanceCreateTime': item['InstanceCreateTime']}


def snapshots(aws, identifier):
    # A successful full listing distinguishes absence from access/network failure.
    matches = [item for item in aws.call('rds', 'describe-db-snapshots', '--snapshot-type', 'manual')['DBSnapshots']
               if item['DBSnapshotIdentifier'] == identifier]
    require(len(matches) <= 1, 'Ambiguous manual snapshot identifier')
    return matches


def snapshot_metadata(aws, core, storage, *, available=True):
    items = snapshots(aws, core['snapshotIdentifier'])
    require(len(items) == 1, 'The exact new B snapshot is missing')
    item = items[0]
    expected_arn = f"arn:aws:rds:{REGION}:{ACCOUNT}:snapshot:{core['snapshotIdentifier']}"
    require(item.get('DBSnapshotArn') == expected_arn and item.get('DBInstanceIdentifier') == core['source']['identifier']
            and item.get('DbiResourceId') == core['source']['resourceId']
            and item.get('Engine') == 'mysql' and item.get('EngineVersion') == '8.4.11'
            and item.get('SnapshotType') == 'manual' and item.get('Encrypted') is True
            and item.get('KmsKeyId') == storage['kmsKeyArn'] and item.get('StorageType') == storage['storageType']
            and item.get('AllocatedStorage') == storage['allocatedStorageGiB']
            and item.get('InstanceCreateTime') == storage['instanceCreateTime']
            and not item.get('SourceDBSnapshotIdentifier') and not item.get('SourceRegion'),
            'Snapshot source resource, engine, storage or encryption identity differs')
    observed_tags = tags(aws.call('rds', 'list-tags-for-resource', '--resource-name', expected_arn)['TagList'])
    require(all(observed_tags.get(key) == value for key, value in snapshot_tags(core).items()), 'B snapshot provenance tags differ')
    require(item.get('Status') in ({'available'} if available else {'creating', 'available'}), 'B snapshot creation failed or is incomplete')
    attributes = aws.call('rds', 'describe-db-snapshot-attributes', '--db-snapshot-identifier', core['snapshotIdentifier'])
    require(attributes.get('DBSnapshotAttributesResult', {}).get('DBSnapshotIdentifier') == core['snapshotIdentifier'],
            'Snapshot permission identity differs')
    grants = attributes['DBSnapshotAttributesResult']['DBSnapshotAttributes']
    require(all(not row.get('AttributeValues') for row in grants if row['AttributeName'] == 'restore'),
            'B snapshot must not be public or shared with another account')
    return {'identifier': core['snapshotIdentifier'], 'arn': expected_arn, 'state': item['Status'], 'engineVersion': '8.4.11',
            'encrypted': True, 'kmsKeyArn': storage['kmsKeyArn'], 'allocatedStorageGiB': storage['allocatedStorageGiB'],
            'storageType': storage['storageType'], 'sourceRdsResourceId': storage['resourceId'],
            'snapshotCreateTime': item.get('SnapshotCreateTime'), 'tags': snapshot_tags(core)}


def validate_provenance(value):
    require(value.get('schemaVersion') == 1 and value.get('kind') == KIND and value.get('state') == AVAILABLE
            and value.get('sourceDeletionAllowed') is False and value.get('actualRestoreVerified') is False,
            'A new verified B snapshot provenance is required; legacy promotion and restoration claims are rejected')
    core = value['contract']
    require(core.get('kind') == KIND and core.get('mysql') == MYSQL and core.get('account') == ACCOUNT
            and core.get('region') == REGION and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', core.get('datasetId', ''))
            and core.get('toolIdentity') == tool_identity() and value.get('contractSha256') == canonical_sha(core)
            and core.get('preparedFingerprintCanonicalSha256') == canonical_sha(core['preparedFingerprint'])
            and value.get('sourceFreeze', {}).get('heldUntilSnapshotAvailable') is True
            and value.get('allRowsAndDdlBeforeAndAfterSnapshotEqual') is True,
            'B snapshot immutable contract, freeze, full fingerprint or tool identity differs')
    contract.validate_fingerprint(core['preparedFingerprint'], require_sealed=False)
    restore.validate_prepared_changes(core['sealedFingerprint'], core['preparedFingerprint'])
    return core


def verify(aws, provenance):
    account(aws)
    core = validate_provenance(provenance)
    observed = snapshot_metadata(aws, core, provenance['storage'])
    require(observed == provenance['snapshot'], 'Available snapshot metadata changed after provenance was issued')
    return core


@contextmanager
def frozen_tables(db):
    """Keep every business table READ-locked; no SUPER/global-variable bypass."""
    process = subprocess.Popen(db.command() + ['--skip-column-names', '--skip-reconnect', '--unbuffered'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, start_new_session=True)
    previous = db.guard
    mutex = threading.Lock()
    last = 0.0
    def query(sql, *, connection_id=False):
        require(process.poll() is None, 'Snapshot READ-lock session was lost')
        process.stdin.write(sql + '\n'); process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            require(selector.select(timeout=35), 'Snapshot READ-lock acquisition/check timed out')
            result = process.stdout.readline().strip()
            require(result.isdigit() and int(result) > 0 if connection_id else result == '1', 'Snapshot READ-lock session failed')
            return int(result)
    def guarded(force=False):
        nonlocal last
        with mutex:
            previous(force=force)
            require(process.poll() is None, 'Snapshot READ-lock session was lost')
            if force or time.monotonic() - last >= 15:
                query('SELECT 1;'); last = time.monotonic()
    evidence = {'method': 'all-business-table-read-locks', 'tables': sorted(contract.TABLES),
                'heldUntilSnapshotAvailable': False, 'released': False}
    previous_connection = getattr(db, 'snapshot_read_lock_connection_id', None)
    primary = None
    try:
        previous(force=True)
        connection_id = query('SET SESSION lock_wait_timeout=30; SET autocommit=0; LOCK TABLES ' +
              ','.join('`' + name + '` READ' for name in sorted(contract.TABLES)) + '; SELECT CONNECTION_ID();', connection_id=True)
        db.snapshot_read_lock_connection_id = connection_id
        evidence['connectionId'] = connection_id
        evidence['acquiredAt'] = now(); db.guard = guarded
        yield evidence
        guarded(force=True)
    except BaseException as error:
        primary = error
        raise
    finally:
        db.guard = previous
        db.snapshot_read_lock_connection_id = previous_connection
        try:
            restore.terminate(process)
            process.stdout.close(); process.stdin.close()
            evidence.update(released=True, releasedAt=now())
        except BaseException as error:
            evidence['cleanupErrorType'] = type(error).__name__
            if primary is None:
                raise
            if hasattr(primary, 'add_note'):
                primary.add_note('Snapshot READ-lock cleanup did not complete.')


def database_identity(db, value):
    require(db.scalar('SELECT @@version', False) == '8.4.11'
            and db.scalar('SELECT @@server_uuid', False) == value['rds']['serverUuid'], 'Live RDS MySQL version/UUID differs')
    rows = db.rows("SELECT version,success FROM flyway_schema_history ORDER BY installed_rank")
    require(len(rows) == 28 and {str(row['version']) for row in rows} == {str(n) for n in range(1, 29)}
            and all(str(row['success']) == '1' for row in rows), 'Exact successful V1 through V28 history required')
    schemas = {row['SCHEMA_NAME'] for row in db.rows('SELECT SCHEMA_NAME FROM information_schema.schemata', False)}
    require(schemas - restore.SYSTEM_SCHEMAS == {'airbobdb'}, 'Exactly one complete B business database is required')
    table_rows = db.rows("SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE table_schema='airbobdb'", False)
    require({row['TABLE_NAME'] for row in table_rows} == contract.TABLES
            and all(row['TABLE_TYPE'] == 'BASE TABLE' for row in table_rows), 'Snapshot table inventory differs')
    require(str(db.scalar('SELECT COUNT(*) FROM outbox')) == '0', 'Snapshot source/target still contains test outbox records')
    lock_id = getattr(db, 'snapshot_read_lock_connection_id', None)
    require(type(lock_id) is int and lock_id > 0, 'A live authenticated snapshot READ lock is required')
    clients = db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() "
        "AND ID<>COALESCE(IS_USED_LOCK('airbob_global_b_restore'),0) AND ID<>" + str(lock_id) +
        " AND USER NOT IN ('rdsadmin','system user','event_scheduler')", False)
    require(str(clients) == '0', 'Application, CDC or another undeclared MySQL client remains connected')


def current_baseline(db, value, envelope, runtime, environment, output, core, *, allow_prepared=False):
    database_identity(db, value)
    result = restore.fingerprint(runtime, Path(value['release']), environment, output, db.timeout, db.guard)
    contract.validate_fingerprint(result, require_sealed=False)
    if allow_prepared:
        restore.validate_prepared_changes(core['sealedFingerprint'], result)
    else:
        require(result == core['preparedFingerprint'], 'Whole prepared snapshot baseline rows or DDL differ')
    require(restore.owner_fingerprint(db) == core['ownerSha256'], 'Snapshot historical HOLD/OCCUPIED ownership differs')
    return result


def current_inventory(db, runtime):
    sys.path.insert(0, str(runtime / 'tools'))
    inventory = importlib.import_module('growth_inventory')
    inventory.verify_closed_ownership(db)
    before = inventory_date_vector(db)
    result = inventory_horizon_at_vector(db, inventory, before)
    after = inventory_date_vector(db)
    require(before['dates'] == after['dates'] and before['publishedListingsByZone'] == after['publishedListingsByZone'],
            'Source date changed during snapshot admission; canonical preparation must be requalified')
    return {'checkedAt': after['observedAt'], 'localDateVector': after['dates'], 'currentInventory': result}


def own_create_resume(configuration, core):
    if 'resumeReceipt' not in configuration:
        return None
    previous = read(reference(configuration['resumeReceipt']))
    require(previous.get('schemaVersion') == 1 and previous.get('kind') == KIND + '-operation'
            and previous.get('operation') == 'create' and previous.get('contractSha256') == canonical_sha(core)
            and previous.get('toolIdentity') == tool_identity() and previous.get('snapshotIdentifier') == core['snapshotIdentifier']
            and previous.get('state') in {'FAILED_RESOURCES_RETAINED', 'CREATE_REQUESTED', 'SNAPSHOT_CREATING', 'SNAPSHOT_SOURCE_REVERIFIED'}
            and isinstance(previous.get('createRequestedAt'), str), 'Only the exact own snapshot attempt may be retried')
    instant(previous['createRequestedAt'])
    return previous


def source_preflight(configuration, value, envelope, output, aws, runtime, db, environment):
    core = core_contract(configuration, value, envelope)
    _, storage = source_instance(aws, value)
    resume = own_create_resume(configuration, core)
    present = snapshots(aws, core['snapshotIdentifier'])
    require(not present or resume is not None, 'Snapshot identifier already exists; no adoption or retagging is allowed')
    if present:
        require(resume.get('snapshotSourceReverified') is True
                and resume.get('sourceFreeze', {}).get('heldUntilSnapshotAvailable') is True,
                'An existing snapshot from a lost/incomplete READ lock cannot be adopted; retain source and use a new name')
        snapshot_metadata(aws, core, storage, available=False)
    restore.verify_remote_versions(aws, envelope)
    with restore.exclusive_database(db), frozen_tables(db):
        current_baseline(db, value, envelope, runtime, environment, output / 'source-fingerprint.json', core)
        calendar = current_inventory(db, runtime)
    return {'schemaVersion': 1, 'kind': KIND + '-preflight', 'state': 'SOURCE_READ_ONLY_PREFLIGHT',
            'operation': 'create', 'contractSha256': canonical_sha(core), 'toolIdentity': tool_identity(),
            'storage': storage, 'snapshotPreviouslyAbsent': not present, 'resumeReceiptSha256':
                configuration.get('resumeReceipt', {}).get('sha256'), 'calendar': calendar, 'recordedAt': now()}


def create_snapshot(configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event):
    reference(configuration['restoreConfig'])
    core = core_contract(configuration, value, envelope)
    require(reviewed['state'] == 'SOURCE_READ_ONLY_PREFLIGHT' and reviewed['contractSha256'] == canonical_sha(core)
            and reviewed['toolIdentity'] == tool_identity(), 'Reviewed snapshot source or tools changed')
    resume = own_create_resume(configuration, core)
    _, storage = source_instance(aws, value)
    require(storage == reviewed['storage'], 'Source RDS identity/storage changed after review')
    provenance = None
    with restore.exclusive_database(db), frozen_tables(db) as freeze:
        started = time.monotonic(); full_started = now()
        current_baseline(db, value, envelope, runtime, environment, output / 'before-snapshot-fingerprint.json', core)
        calendar = current_inventory(db, runtime)
        event('SOURCE_FROZEN_AND_VERIFIED', contractSha256=canonical_sha(core), snapshotIdentifier=core['snapshotIdentifier'],
              fullValidationStartedAt=full_started, fullValidationCompletedAt=now(),
              fullValidationSeconds=round(time.monotonic() - started, 6), sourceFreeze=freeze)
        present = snapshots(aws, core['snapshotIdentifier'])
        require(not present or resume is not None, 'Unexpected existing snapshot cannot be adopted')
        db.guard(force=True); source_instance(aws, value)
        if present:
            require(resume.get('snapshotSourceReverified') is True
                    and resume.get('sourceFreeze', {}).get('heldUntilSnapshotAvailable') is True,
                    'An existing snapshot from an interrupted freeze cannot be adopted; use a new snapshot name')
            snapshot_metadata(aws, core, storage, available=False)
            requested = resume['createRequestedAt']
        else:
            requested = now()
            request = {'schemaVersion': 1, 'kind': KIND + '-controller-create-request',
                'state': 'SOURCE_READ_LOCK_HELD', 'contractSha256': canonical_sha(core),
                'source': core['source'], 'snapshotIdentifier': core['snapshotIdentifier'],
                'requiredLease': value['lease'], 'requestedAt': requested,
                'deadlineAt': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=configuration['operationTimeoutSeconds'])).isoformat(),
                'requiresLiveReadLockAcknowledgment': True,
                'hostCreatesOrDeletesAwsResources': False, 'tags': snapshot_tags(core)}
            write(output / 'snapshot-create-request.json', request)
            event('CREATE_REQUESTED', createRequestedAt=requested, controllerSnapshotCreationRequired=True,
                  controllerRequestSha256=sha(output / 'snapshot-create-request.json'), awsSnapshotCreateRequested=False)
        deadline = time.monotonic() + configuration['operationTimeoutSeconds']
        last_state, seen = None, bool(present)
        while True:
            db.guard(force=True)
            items = snapshots(aws, core['snapshotIdentifier'])
            require(items or not seen, 'Snapshot disappeared after the controller created it')
            seen = seen or bool(items)
            require(not items or items[0].get('Status') in {'creating', 'available'}, 'Snapshot creation failed')
            if items and items[0]['Status'] == 'available':
                break
            state = 'SNAPSHOT_CREATING' if items else 'AWAITING_CONTROLLER_SNAPSHOT'
            if state != last_state:
                event(state, createRequestedAt=requested); last_state = state
            require(time.monotonic() < deadline, 'Snapshot creation deadline expired; source must be retained')
            time.sleep(15)
        snapshot = snapshot_metadata(aws, core, storage)
        require(instant(snapshot['snapshotCreateTime']) >= instant(requested) - dt.timedelta(minutes=5),
                'Snapshot create time predates this exact own request')
        current_baseline(db, value, envelope, runtime, environment, output / 'after-snapshot-fingerprint.json', core)
        db.guard(force=True); source_instance(aws, value)
        freeze['heldUntilSnapshotAvailable'] = True
        event('SNAPSHOT_SOURCE_REVERIFIED', createRequestedAt=requested, snapshotSourceReverified=True,
              sourceFreeze=freeze, snapshotMetadata=snapshot)
        completed = now()
        provenance = {'schemaVersion': 1, 'kind': KIND, 'state': AVAILABLE, 'contract': core,
            'contractSha256': canonical_sha(core), 'storage': storage, 'snapshot': snapshot,
            'sourceFreeze': freeze, 'calendarAtSnapshot': calendar, 'allRowsAndDdlBeforeAndAfterSnapshotEqual': True,
            'sourceDeletionAllowed': False, 'actualRestoreVerified': False,
            'timings': {'createRequestedAt': requested, 'snapshotAvailableVerifiedAt': completed,
                'requestToVerifiedAvailableSeconds': (instant(completed) - instant(requested)).total_seconds()}}
    write(output / 'snapshot-provenance.json', provenance)
    event(AVAILABLE, provenanceSha256=sha(output / 'snapshot-provenance.json'), sourceDeletionAllowed=False,
          actualRestoreVerified=False, sourceRetained=True)
    return provenance


def deletion_admission(aws, provenance, configuration, value, envelope, runtime, db, environment, output):
    db.guard(force=True)
    core = verify(aws, provenance)
    reference(configuration['privateHandoff'], private=True)
    require(configuration['privateHandoff']['sha256'] == core['evidence']['privateHandoffSha256']
            and restore.target_identity(value) == core['source'], 'Preserved handoff or exact source identity differs')
    source_instance(aws, value)
    with restore.exclusive_database(db), frozen_tables(db):
        current_baseline(db, value, envelope, runtime, environment, output / 'deletion-source-fingerprint.json', core)
        db.guard(force=True)
    return {'schemaVersion': 1, 'kind': KIND + '-source-deletion-admission', 'source': core['source'],
            'contractSha256': canonical_sha(core), 'sourceDeletionAllowed': True, 'deletionExecuted': False,
            'snapshotAvailableVerified': True, 'privateHandoffPreserved': True, 'issuedAt': now(),
            'expiresAt': (dt.datetime.now(dt.timezone.utc) + dt.timedelta(seconds=60)).isoformat()}


def restore_admission(configuration, aws):
    provenance = read(reference(configuration['provenance']))
    core = verify(aws, provenance)
    guard = restore.Lease(aws, configuration['lease']); guard(force=True)
    require(instances(aws) == [], 'Source RDS must be deleted and the region empty before sequential snapshot restoration')
    groups = aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
    require(all(row.get('DesiredCapacity') == row.get('MinSize') == 0 and not row.get('Instances')
                for row in groups if row['AutoScalingGroupName'].startswith(configuration['targetIdentifier'] + '-')),
            'The target application ASGs must remain stopped before snapshot restore admission')
    return {'schemaVersion': 1, 'kind': KIND + '-restore-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT',
        'provenanceSha256': configuration['provenance']['sha256'], 'snapshotArn': provenance['snapshot']['arn'],
        'source': core['source'], 'targetIdentifier': configuration['targetIdentifier'], 'lease': configuration['lease'],
        'maximumSimultaneousBusinessDatabases': 1, 'restoreExecuted': False, 'recordedAt': now()}


def restored_target(aws, provenance, admission, value, event):
    core = verify(aws, provenance)
    require(admission.get('kind') == KIND + '-restore-admission' and admission.get('state') == 'SOURCE_ABSENT_TARGET_ABSENT'
            and admission.get('source') == core['source'] and admission.get('targetIdentifier') == value['rds']['identifier']
            and admission.get('snapshotArn') == provenance['snapshot']['arn'], 'Snapshot restore admission differs')
    live = restore.live_rds(aws, value)
    present = instances(aws)
    require(len(present) == 1 and present[0]['DbiResourceId'] == value['rds']['resourceId']
            and value['rds']['resourceId'] != core['source']['resourceId'], 'Old source or another DB is still present')
    item = present[0]
    require(item.get('StorageEncrypted') is True and item.get('KmsKeyId') == provenance['storage']['kmsKeyArn']
            and item.get('AllocatedStorage') == provenance['storage']['allocatedStorageGiB']
            and item.get('StorageType') == provenance['storage']['storageType'], 'Restored target storage differs from snapshot')
    request = event.get('requestParameters') or {}
    require(event.get('eventName') == 'RestoreDBInstanceFromDBSnapshot' and event.get('eventSource') == 'rds.amazonaws.com'
            and event.get('awsRegion') == REGION and event.get('recipientAccountId') == ACCOUNT
            and not event.get('errorCode') and request.get('dBInstanceIdentifier') == value['rds']['identifier']
            and request.get('dBSnapshotIdentifier') in {provenance['snapshot']['identifier'], provenance['snapshot']['arn']},
            'An exact successful AWS snapshot-restore event for this new target is required')
    created, requested = instant(item['InstanceCreateTime']), instant(event['eventTime'])
    require(instant(admission['recordedAt']) <= requested <= created + dt.timedelta(minutes=5)
            and created - requested <= dt.timedelta(hours=2), 'Snapshot restore event predates admission or another target creation')
    return live, {'eventId': event['eventID'], 'eventSha256': canonical_sha(event), 'requestedAt': event['eventTime'],
                 'instanceCreateTime': item['InstanceCreateTime'], 'evidenceSource': 'controller-pinned-cloudtrail-event'}


def preparation_bindings(configuration, value, envelope):
    return {'provenanceSha256': configuration['provenance']['sha256'],
            'admissionSha256': configuration['admission']['sha256'], 'targetIdentity': restore.target_identity(value),
            'restoreEventSha256': configuration['restoreEvent']['sha256'],
            'credentialBindingSha256': restore.credential_binding(value), 'toolIdentity': tool_identity(),
            'datasetId': envelope['datasetId'], 'envelopeSha256': value['envelopeSha256']}


def validate_prepare_resume(configuration, bindings):
    if 'resumeReceipt' not in configuration:
        return None
    proof = read(reference(configuration['resumeReceipt']))
    require(proof.get('kind') == KIND + '-operation' and proof.get('operation') == 'prepare'
            and proof.get('state') == 'FAILED_RESOURCES_RETAINED'
            and all(proof.get(key) == val for key, val in bindings.items())
            and proof.get('snapshotWholeBaselineVerified') is True
            and digest(proof.get('snapshotBaselineCanonicalSha256')),
            'Preparation retry requires this exact own verified snapshot baseline and target')
    return proof


def prepare_preflight(configuration, value, envelope, output, aws, runtime, db, environment):
    provenance = read(reference(configuration['provenance']))
    core = validate_provenance(provenance)
    admission = read(reference(configuration['admission']))
    require(admission.get('provenanceSha256') == configuration['provenance']['sha256'], 'Admission provenance hash differs')
    _, actual = restored_target(aws, provenance, admission, value, read(reference(configuration['restoreEvent'])))
    require(core['datasetId'] == envelope['datasetId'] and core['publication']['envelopeSha256'] == value['envelopeSha256']
            and core['application']['appJarSha256'] == envelope['appJarSha256'], 'Restored target application/release differs')
    bindings = preparation_bindings(configuration, value, envelope)
    resumed = validate_prepare_resume(configuration, bindings)
    with restore.exclusive_database(db), frozen_tables(db):
        current = current_baseline(db, value, envelope, runtime, environment, output / 'restored-source-fingerprint.json', core,
                                   allow_prepared=resumed is not None)
    if resumed:
        require(resumed['snapshotBaselineCanonicalSha256'] == core['preparedFingerprintCanonicalSha256'], 'Resume snapshot baseline changed')
    return {'schemaVersion': 1, 'kind': KIND + '-preflight', 'state': 'RESTORED_TARGET_READ_ONLY_PREFLIGHT',
            'operation': 'prepare', **bindings, 'currentFingerprintCanonicalSha256': canonical_sha(current),
            'actualRestore': actual, 'resumeReceiptSha256': configuration.get('resumeReceipt', {}).get('sha256'), 'recordedAt': now()}


def prepare_restored(configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event):
    provenance = read(reference(configuration['provenance']))
    core = validate_provenance(provenance)
    bindings = preparation_bindings(configuration, value, envelope)
    require(reviewed.get('state') == 'RESTORED_TARGET_READ_ONLY_PREFLIGHT'
            and all(reviewed.get(key) == item for key, item in bindings.items()), 'Reviewed restored-target identity/input differs')
    _, actual = restored_target(aws, provenance, read(reference(configuration['admission'])), value,
                               read(reference(configuration['restoreEvent'])))
    require(actual == reviewed['actualRestore'], 'The actual snapshot restore event changed after review')
    resumed = validate_prepare_resume(configuration, bindings)
    with restore.exclusive_database(db):
        with frozen_tables(db):
            started = time.monotonic(); began = now()
            current = current_baseline(db, value, envelope, runtime, environment, output / 'snapshot-baseline-fingerprint.json', core,
                                       allow_prepared=resumed is not None)
            require(canonical_sha(current) == reviewed['currentFingerprintCanonicalSha256'], 'Restored DB changed after preflight')
            event('SNAPSHOT_WHOLE_BASELINE_VERIFIED', **bindings, snapshotWholeBaselineVerified=True,
                snapshotBaselineCanonicalSha256=core['preparedFingerprintCanonicalSha256'],
                baselineOwnerSha256=core['ownerSha256'], sqlImportSkipped=True, databaseRecreatedInThisRun=False,
                actualRestore=actual, fullValidationStartedAt=began, fullValidationCompletedAt=now(),
                fullValidationSeconds=round(time.monotonic() - started, 6), databaseConnectionVerifiedAt=now())
        started = time.monotonic(); event('PREPARATION_STARTED', preparationStartedAt=now())
        prepared = restore.prepare_service(value, envelope, runtime, environment, db, output, output / '.private',
                                          expected_owner_sha=core['ownerSha256'])
        # The shared adapter already seeds, fingerprints and rechecks dates,
        # retrying proven mid-check rollovers. Reuse that final artifact and its
        # observation time instead of introducing a new unprotected calendar check.
        final = read(output / 'prepared-fingerprint.json')
        contract.validate_fingerprint(final, require_sealed=False)
        restore.validate_prepared_changes(core['sealedFingerprint'], final)
        require(restore.owner_fingerprint(db) == core['ownerSha256'], 'Prepared restored target owner fingerprint differs')
        calendar = prepared['currentInventory']
        db.guard(force=True)
        event(PREPARED, preparation=prepared, preparedFingerprintSha256=sha(output / 'prepared-fingerprint.json'),
              preparedFingerprintCanonicalSha256=canonical_sha(final), currentInventory=calendar,
              preparationCompletedAt=now(), preparationSeconds=round(time.monotonic() - started, 6),
              applicationLeftRunning=False, deploymentReady=False, actualRestoreVerified=True)
    # The existing B service gate can consume this exact common projection. The
    # source mode and immutable snapshot lineage remain explicit additional fields.
    projection = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-data-only-preparation',
        'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED', 'sourceMode': 'verified-global-b-snapshot',
        'datasetId': envelope['datasetId'], 'runId': value['rds']['identifier'].removeprefix('airbob-'),
        'rdsResourceId': value['rds']['resourceId'], 'serverUuid': value['rds']['serverUuid'],
        'rdsEngineVersion': '8.4.11', 'flywayVersion': 28, 'preparation': prepared,
        'restoreReceiptSha256': sha(output / 'snapshot-operation.json'),
        'snapshotProvenanceSha256': configuration['provenance']['sha256'], 'snapshotRestoreEvidence': actual,
        'deploymentReady': False, 'applicationLeftRunning': False, 'completedAt': now()}
    projection['preparation']['preparedFingerprintSha256'] = sha(output / 'prepared-fingerprint.json')
    write(output / 'data-only-preparation.json', projection)
    return projection


def offline_plan(configuration):
    if configuration['operation'] == 'create':
        value, envelope = restore_inputs(configuration)
        core = core_contract(configuration, value, envelope)
        return {'schemaVersion': 1, 'kind': KIND + '-plan', 'state': 'OFFLINE_PLAN', 'operation': 'create',
            'contract': core, 'contractSha256': canonical_sha(core), 'tags': snapshot_tags(core),
            'snapshotIdentifier': core['snapshotIdentifier'], 'awsWritesExecuted': False, 'sourceDeletionAllowed': False,
            'phases': ['verify source RDS restore, service/reset and private handoff',
                'hold existing lease, database fence and all-business-table READ locks',
                'verify whole prepared fingerprint and actual current IANA inventory',
                'issue an exact controller snapshot request while holding READ locks; verify resulting metadata/permissions/tags',
                'verify whole source again before releasing locks; retain source and write provenance']}
    provenance = read(reference(configuration['provenance']))
    core = validate_provenance(provenance)
    return {'schemaVersion': 1, 'kind': KIND + '-plan', 'state': 'OFFLINE_PLAN', 'operation': configuration['operation'],
            'datasetId': core['datasetId'], 'provenanceSha256': configuration['provenance']['sha256'],
            'source': core['source'], 'snapshotArn': provenance['snapshot']['arn'], 'awsWritesExecuted': False,
            'phases': ['verify immutable B snapshot provenance and current AWS metadata',
                'require original source absent before controller restores the sole target RDS',
                'authenticate actual AWS restore event, new resource/UUID and whole snapshot baseline',
                'apply canonical credentials/current FREE preparation only; leave temporary app stopped']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'preflight', 'create', 'verify', 'delete-admission', 'restore-admission', 'prepare'])
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--preflight-sha256')
    parser.add_argument('--provenance', type=Path)
    parser.add_argument('--provenance-sha256')
    args = parser.parse_args(); os.umask(0o077)
    configuration = config(args.config)
    require(not args.output.exists(), 'Every snapshot operation requires a new private output directory')
    if 'restoreConfig' in configuration:
        source_config = read(reference(configuration['restoreConfig']))
        require(not args.output.resolve().is_relative_to(Path(source_config['release']).resolve()),
                'Snapshot output must remain outside the sealed release')
    output = restore.private_directory(args.output.resolve())
    planned = offline_plan(configuration)
    if args.mode == 'plan':
        write(output / 'plan.json', planned); return
    if args.mode in {'create', 'prepare'}:
        require(configuration['operation'] == args.mode and args.preflight and digest(args.preflight_sha256)
                and sha(contract.regular(args.preflight)) == args.preflight_sha256, 'Mutation requires the exact reviewed preflight SHA')
        reviewed = read(args.preflight)
        require(reviewed.get('configSha256') == sha(args.config), 'Snapshot configuration changed after review')
    receipt = {'schemaVersion': 1, 'kind': KIND + '-operation', 'operation': configuration['operation'],
               'toolIdentity': tool_identity(), 'configSha256': sha(args.config), 'events': [], 'sourceDeletionAllowed': False}
    def event(state, **values):
        at = now(); receipt.update(state=state, updatedAt=at, **values); receipt.setdefault('startedAt', at)
        receipt['events'].append({'state': state, 'at': at}); write(output / 'snapshot-operation.json', receipt)
        print(json.dumps({'state': state, 'at': at}), flush=True)
    aws = restore.Aws()
    try:
        if args.mode in {'verify', 'restore-admission'}:
            require(configuration['operation'] == 'restore-admission', 'Use the explicit restore-admission configuration')
            if args.mode == 'verify':
                core = verify(aws, read(reference(configuration['provenance'])))
                write(output / 'snapshot-verification.json', {'state': AVAILABLE, 'contractSha256': canonical_sha(core), 'verifiedAt': now()})
            else:
                write(output / 'restore-admission.json', restore_admission(configuration, aws))
            return
        require((configuration['operation'] == 'create' and args.mode in {'preflight', 'create', 'delete-admission'})
                or (configuration['operation'] == 'prepare' and args.mode in {'preflight', 'prepare'}), 'Snapshot operation/mode mismatch')
        value, envelope = restore_inputs(configuration)
        runtime = restore.extract_runtime(Path(value['release']), output / 'runtime')
        qualification = qualify_runtime(Path(value['release']), runtime, output / 'host-runtime.json',
            expected_checks={name: item['sha256'] for name, item in envelope['objects'].items()})
        with activated_runtime(qualification):
            guard = restore.Lease(aws, value['lease']); guard(force=True)
            live = restore.live_rds(aws, value)
            private = restore.private_directory(output / '.private')
            db, environment = restore.connection(value, aws, live, private, guard)
            if args.mode == 'preflight':
                function = source_preflight if configuration['operation'] == 'create' else prepare_preflight
                result = function(configuration, value, envelope, output, aws, runtime, db, environment)
                result['configSha256'] = sha(args.config); write(output / 'preflight.json', result)
            elif args.mode == 'create':
                create_snapshot(configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event)
            elif args.mode == 'prepare':
                prepare_restored(configuration, value, envelope, reviewed, output, aws, runtime, db, environment, event)
            else:
                require(args.provenance and digest(args.provenance_sha256), 'Deletion admission requires the exact new provenance')
                provenance = read(reference({'path': str(args.provenance), 'sha256': args.provenance_sha256}))
                result = deletion_admission(aws, provenance, configuration, value, envelope, runtime, db, environment, output)
                write(output / 'source-deletion-admission.json', result)
    except BaseException as error:
        trace = error.__traceback__
        while trace is not None and trace.tb_next is not None:
            trace = trace.tb_next
        location = None if trace is None else {'file': Path(trace.tb_frame.f_code.co_filename).name, 'line': trace.tb_lineno}
        event('FAILED_RESOURCES_RETAINED', errorType=type(error).__name__, errorLocation=location, sourceDeletionAllowed=False,
              resourceDeletionExecuted=False, deploymentReady=False)
        raise SystemExit('B snapshot operation failed; inspect its private receipt. All RDS/snapshot resources were retained.') from None
    finally:
        if (output / '.private').exists():
            shutil.rmtree(output / '.private')


if __name__ == '__main__':
    main()
