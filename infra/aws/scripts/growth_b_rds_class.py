#!/usr/bin/env python3
"""Outer RDS class binding; never changes the six sealed preparation helpers.

An observation is an actual RDS API read, not a performance measurement. The
small qualification binds two such reads around a successful frozen import.
Only the controller's exact-version evidence reference can admit a final run.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

DEFAULT = 'db.t3.small'
LARGE = 'db.m6i.large'
TAG = 'BDatabaseClass'
KIND = 'global-growth-b-rds-class-qualification'
OBSERVATION_KIND = 'global-growth-b-rds-class-observation'
MAX_BYTES = 16 * 1024**2


class Rejected(ValueError):
    pass


def need(ok, code):
    if not ok:
        raise Rejected(code)


def sha(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def source_sha():
    return sha(__file__)


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def pairs(items):
    result = {}
    for key, value in items:
        need(key not in result, 'DUPLICATE_FIELD')
        result[key] = value
    return result


def read(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and path.stat().st_size <= MAX_BYTES, 'UNSAFE_INPUT')
    return json.loads(path.read_bytes(), object_pairs_hook=pairs,
                      parse_constant=lambda _: (_ for _ in ()).throw(Rejected('NONFINITE_JSON')))


def write(path, value):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(canonical(value)); stream.flush(); os.fsync(stream.fileno())
    fd = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def selected(value=DEFAULT):
    need(value in (DEFAULT, LARGE), 'UNREVIEWED_RDS_CLASS')
    return value


def original_class(operator):
    result = selected(operator.get('rdsInstanceClass', DEFAULT))
    if result != DEFAULT:
        need((operator.get('globalBPrepareOnly') is True or operator.get('globalBSnapshotRestoreOnly') is True)
             and operator.get('rdsEngineVersion') == '8.4.11', 'LARGE_CLASS_REQUIRES_GLOBAL_B')
    return result


def effective_class(operator, transition=None, operator_sha256=None):
    original = original_class(operator)
    if transition is None:
        return original
    import growth_b_mac_downsize as mac_downsize
    try:
        mac_downsize.validate_receipt(operator, transition, operator_sha256)
    except (ValueError, KeyError, TypeError):
        raise Rejected('EXACT_MAC_DOWNSIZE_TRANSITION_REQUIRED') from None
    need(original == LARGE, 'MAC_DOWNSIZE_REQUIRES_ORIGINAL_LARGE')
    return DEFAULT


def reference(value):
    need(isinstance(value, dict) and set(value) == {'key', 'versionId', 'sha256', 'bytes'}, 'CLASS_REFERENCE_FIELDS')
    need(isinstance(value['key'], str) and re.fullmatch(
        r'data-bootstrap/lab-[a-z0-9][a-z0-9-]{0,27}/global-growth-b-[0-9a-f]{16}-rds-class[.]json', value['key']), 'CLASS_REFERENCE_KEY')
    need(isinstance(value['versionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', value['versionId'])
         and value['versionId'] not in ('null', 'None'), 'CLASS_REFERENCE_VERSION')
    need(isinstance(value['sha256'], str) and re.fullmatch(r'[0-9a-f]{64}', value['sha256'])
         and type(value['bytes']) is int and 0 < value['bytes'] <= 100 * 1024, 'CLASS_REFERENCE_DIGEST')
    return value


def standalone_reference(value, run, dataset):
    need(isinstance(value, dict) and set(value) == {'key', 'versionId', 'sha256', 'bytes'}, 'SMALL_REFERENCE_FIELDS')
    need(value['key'] == f'data-bootstrap/{run}/{dataset}-standalone-rds.json'
         and isinstance(value['versionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', value['versionId'])
         and value['versionId'] not in ('null', 'None') and re.fullmatch(r'[0-9a-f]{64}', value['sha256'])
         and type(value['bytes']) is int and 0 < value['bytes'] <= 10 * 1024**2, 'SMALL_REFERENCE_BINDING')
    return value


def initial_operation(operation):
    need(isinstance(operation, dict) and set(operation) <= {'rdsInstanceClass', 'classRehearsal'}, 'CLASS_OPERATION_FIELDS')
    result = selected(operation.get('rdsInstanceClass', DEFAULT))
    if 'classRehearsal' in operation:
        need(result == LARGE, 'CLASS_REHEARSAL_REQUIRES_EXPLICIT_LARGE')
        reference(operation['classRehearsal'])
    return result


def phase3_class(operator, phase3, transition=None, operator_sha256=None):
    result = effective_class(operator, transition, operator_sha256)
    need(phase3.get('rds_instance_class', DEFAULT) == result, 'ORIGINAL_TERRAFORM_CLASS_CHANGED')
    if transition is not None:
        need(all(phase3.get(field) == transition['rds'][key] for field, key in (
            ('rds_instance_id', 'identifier'), ('rds_resource_id', 'resourceId'), ('rds_endpoint', 'endpoint')))
            and phase3.get('rds_configured_storage_gib') == 100, 'DOWNSIZED_TERRAFORM_TARGET_CHANGED')
    if result == LARGE:
        need(phase3.get('rds_instance_class') == result and (phase3.get('rds_configured_storage_gib') == 100 or
             (operator.get('databaseBootstrap') == 'snapshot' and phase3.get('rds_allocated_storage_gib') == 100)),
             'EXPLICIT_LARGE_TERRAFORM_BINDING_REQUIRED')
    return result


def actual_class(row, expected):
    expected = selected(expected)
    need(row.get('DBInstanceClass') == expected
         and row.get('PendingModifiedValues', {}).get('DBInstanceClass', expected) == expected,
         'ORIGINAL_RDS_CLASS_OR_PENDING_CHANGED')
    rows = row.get('TagList', [])
    tags = {item['Key']: item['Value'] for item in rows}
    need(len(tags) == len(rows) and tags.get(TAG, DEFAULT) == expected
         and (expected == DEFAULT or tags.get(TAG) == expected), 'IMMUTABLE_RDS_CLASS_TAG_REQUIRED')
    return expected


def validate_plan(plan, expected, provenance=None):
    expected = selected(expected)
    for row in plan.get('resource_changes', []):
        if row.get('type') != 'aws_db_instance':
            continue
        change = row['change']; before, after = change.get('before'), change.get('after')
        inherited_snapshot = (before is None and isinstance(after, dict)
            and isinstance(after.get('snapshot_identifier'), str)
            and re.fullmatch(r'airbob-dataset-b-[a-z0-9][a-z0-9-]{2,45}', after['snapshot_identifier']))
        if inherited_snapshot and (after.get('allocated_storage') is None or after.get('storage_type') is None):
            import growth_b_snapshot as snapshot
            need(provenance is not None, 'ACTUAL_B_SNAPSHOT_PROVENANCE_REQUIRED')
            core = snapshot.validate_provenance(provenance)
            observed = provenance['snapshot']
            need(core['snapshotIdentifier'] == after['snapshot_identifier'] == observed['identifier']
                 and observed['state'] == 'available' and observed['engineVersion'] == '8.4.11'
                 and observed['allocatedStorageGiB'] == 100 and observed['storageType'] == 'gp3'
                 and observed['encrypted'] is True, 'EXACT_SNAPSHOT_STORAGE_REQUIRED')
        need('delete' not in change['actions'] and after is not None
             and after.get('instance_class') == expected and after.get('engine_version') == '8.4.11'
             and (after.get('allocated_storage') == 100 or (inherited_snapshot and after.get('allocated_storage') is None))
             and (after.get('storage_type') == 'gp3' or (inherited_snapshot and after.get('storage_type') is None))
             and after.get('multi_az') is False and after.get('storage_encrypted') is True
             and after.get('publicly_accessible') is False, 'B_RDS_PLAN_SHAPE_CHANGED')
        need(before is None or before.get('instance_class') == expected, 'IN_PLACE_CLASS_CHANGE_FORBIDDEN')
        if expected == LARGE:
            need(after.get('tags', {}).get(TAG) == expected
                 and (before is None or before.get('tags', {}).get(TAG) == expected), 'INITIAL_CLASS_TAG_CANNOT_BE_REBOUND')
    return {'state': 'RDS_CLASS_PLAN_VERIFIED'}


def shape(row, expected, *, identifier, resource_id, run, fence, expiry=None):
    expected = actual_class(row, expected)
    rows = row.get('TagList', [])
    tags = {item['Key']: item['Value'] for item in rows}
    need(len(tags) == len(rows), 'DUPLICATE_RDS_TAG')
    need(row.get('DBInstanceIdentifier') == identifier == 'airbob-' + run
         and row.get('DbiResourceId') == resource_id and row.get('DBInstanceClass') == expected
         and row.get('PendingModifiedValues', {}).get('DBInstanceClass', expected) == expected,
         'ORIGINAL_RDS_CLASS_OR_ID_CHANGED')
    need(tags.get('RunId') == run and tags.get('FencingToken') == str(fence)
         and tags.get('Project') == 'airbob' and tags.get('Stack') == 'lab', 'ORIGINAL_RDS_TAGS_CHANGED')
    need(tags.get(TAG, DEFAULT) == expected and (expected == DEFAULT or tags.get(TAG) == expected),
         'IMMUTABLE_RDS_CLASS_TAG_REQUIRED')
    if expiry is not None:
        need(tags.get('ExpiresAt') == str(expiry), 'ORIGINAL_RDS_EXPIRY_CHANGED')
    need(row.get('Engine') == 'mysql' and row.get('EngineVersion') == '8.4.11'
         and row.get('AllocatedStorage') == 100 and row.get('StorageType') == 'gp3'
         and row.get('MultiAZ') is False and row.get('StorageEncrypted') is True
         and row.get('PubliclyAccessible') is False and row.get('DBInstanceStatus') == 'available', 'BOUNDED_B_RDS_SHAPE_REQUIRED')
    need(isinstance(row.get('InstanceCreateTime'), str), 'RDS_CREATION_TIME_REQUIRED')
    return {'identifier': identifier, 'resourceId': resource_id, 'instanceClass': expected,
            'createdAt': row['InstanceCreateTime'], 'engineVersion': '8.4.11', 'allocatedStorageGiB': 100,
            'storageType': 'gp3', 'multiAz': False, 'encrypted': True, 'publiclyAccessible': False}


def validate_live(operator, phase3, response, transition=None, operator_sha256=None):
    cls = phase3_class(operator, phase3, transition, operator_sha256)
    need(isinstance(response, dict) and len(response.get('DBInstances', [])) == 1, 'EXACT_RDS_REQUIRED')
    return shape(response['DBInstances'][0], cls, identifier=phase3['rds_instance_id'], resource_id=phase3['rds_resource_id'],
                 run=operator['runId'], fence=operator['fencingToken'], expiry=operator['expiresAt'])


def observe(context, context_sha, stage, response, *, now=None):
    now = int(time.time()) if now is None else now
    need(stage in ('before', 'after') and context.get('rdsClassGuardSha256') == source_sha(), 'EXACT_OUTER_CLASS_GUARD_REQUIRED')
    need(int(context['expiresAt']) > now, 'ORIGINAL_RDS_TTL_EXPIRED')
    need(len(response.get('DBInstances', [])) == 1, 'EXACT_RDS_REQUIRED')
    resource_fence = context.get('resourceFence', context['lease']['fencingToken'])
    identity = shape(response['DBInstances'][0], context['rdsInstanceClass'],
        identifier=context['rds']['identifier'], resource_id=context['rds']['resourceId'],
        run=context['runId'], fence=resource_fence, expiry=context['expiresAt'])
    return {'schemaVersion': 1, 'kind': OBSERVATION_KIND, 'stage': stage, 'sourceSha256': source_sha(),
            'contextSha256': context_sha, 'contextProjectionSha256': hashlib.sha256(canonical({k: context[k] for k in CONTEXT_PROJECTION})).hexdigest(),
            'runId': context['runId'], 'datasetId': context['datasetId'],
            'resourceFence': resource_fence, 'observedEpoch': now, 'rds': identity}


def validate_history(context, context_sha, before, after, wrapper, standalone, standalone_sha):
    expected_keys = {'schemaVersion', 'kind', 'stage', 'sourceSha256', 'contextSha256', 'runId', 'datasetId',
                     'resourceFence', 'observedEpoch', 'rds', 'contextProjectionSha256'}
    need(isinstance(context_sha, str) and re.fullmatch(r'[0-9a-f]{64}', context_sha)
         and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', context['runId'])
         and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', context['datasetId'])
         and re.fullmatch(r'[0-9a-f]{64}', context['manifestSha256'])
         and str(context['expiresAt']).isdigit() and len(str(context['expiresAt'])) == 10, 'ORIGINAL_CLASS_CONTEXT')
    lease = context['lease']
    need(set(lease) == {'table', 'lockName', 'owner', 'runId', 'command', 'fencingToken'}
         and lease['table'] == 'airbob-performance-lab-orchestration-lease' and lease['lockName'] == 'airbob-performance-lab'
         and lease['runId'] == context['runId'] and lease['command'] == 'up'
         and type(lease['fencingToken']) is int and lease['fencingToken'] > 0, 'ORIGINAL_CLASS_LEASE')
    projection_sha = hashlib.sha256(canonical({k: context[k] for k in CONTEXT_PROJECTION})).hexdigest()
    for observation, stage in ((before, 'before'), (after, 'after')):
        need(set(observation) == expected_keys and observation['schemaVersion'] == 1
             and observation['kind'] == OBSERVATION_KIND and observation['stage'] == stage
             and observation['sourceSha256'] == source_sha() and observation['contextSha256'] == context_sha
             and observation['contextProjectionSha256'] == projection_sha
             and observation['runId'] == context['runId'] and observation['datasetId'] == context['datasetId']
             and observation['resourceFence'] == context['lease']['fencingToken'], 'CLASS_OBSERVATION_BINDING')
    expected_rds = {'identifier', 'resourceId', 'instanceClass', 'createdAt', 'engineVersion', 'allocatedStorageGiB',
                    'storageType', 'multiAz', 'encrypted', 'publiclyAccessible'}
    for observation in (before, after):
        rds = observation['rds']
        need(set(rds) == expected_rds and rds['identifier'] == 'airbob-' + context['runId']
             and re.fullmatch(r'db-[A-Z0-9]+', rds['resourceId']) and rds['engineVersion'] == '8.4.11'
             and rds['allocatedStorageGiB'] == 100 and rds['storageType'] == 'gp3'
             and rds['multiAz'] is False and rds['encrypted'] is True and rds['publiclyAccessible'] is False,
             'OBSERVED_RDS_SHAPE')
        created = dt.datetime.fromisoformat(rds['createdAt'].replace('Z', '+00:00'))
        need(created.tzinfo is not None and created.timestamp() <= observation['observedEpoch'], 'RDS_CREATION_CHRONOLOGY')
    standalone_reference(wrapper.get('standaloneReceiptObject'), context['runId'], context['datasetId'])
    need(wrapper['standaloneReceiptObject']['sha256'] == standalone_sha, 'SMALL_REFERENCE_BYTES')
    need(before['rds'] == after['rds'] and before['rds']['instanceClass'] == context['rdsInstanceClass']
         and type(before['observedEpoch']) is int and type(after['observedEpoch']) is int
         and before['observedEpoch'] <= after['observedEpoch'] < int(context['expiresAt']), 'CLASS_OBSERVATION_DRIFT')
    need(wrapper.get('kind') == 'global-growth-b-aws-data-only-preparation'
         and wrapper.get('state') == 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED'
         and wrapper.get('runId') == context['runId'] and wrapper.get('datasetId') == context['datasetId']
         and wrapper.get('manifestSha256') == context['manifestSha256']
         and wrapper.get('rdsResourceId') == before['rds']['resourceId']
         and wrapper.get('restoreReceiptSha256') == standalone_sha, 'ACTUAL_SMALL_WRAPPER_REQUIRED')
    need(standalone.get('kind') == 'global-growth-b-aws-restore-receipt'
         and standalone.get('state') == 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED'
         and standalone.get('executionScope') == 'small-rds-rehearsal'
         and standalone.get('datasetId') == context['datasetId'] and standalone.get('rdsResourceId') == before['rds']['resourceId']
         and standalone.get('allRowsAndDdlEqual') is True and standalone.get('preparation', {}).get('passed') is True
         and standalone.get('beforeDatabase', {}).get('serverUuid') == wrapper.get('serverUuid')
         and standalone.get('preparation') == wrapper.get('preparation'), 'ACTUAL_SMALL_RAW_RECEIPT_REQUIRED')
    return True


CONTEXT_PROJECTION = {'runId', 'datasetId', 'manifestSha256', 'rdsInstanceClass', 'expiresAt', 'lease', 'toolSources'}
WRAPPER_PROJECTION = {'kind', 'state', 'runId', 'datasetId', 'manifestSha256', 'rdsResourceId',
                      'restoreReceiptSha256', 'serverUuid', 'preparation', 'standaloneReceiptObject'}


def qualify(context, context_sha, before, after, wrapper, standalone, standalone_sha):
    validate_history(context, context_sha, before, after, wrapper, standalone, standalone_sha)
    return {'schemaVersion': 1, 'kind': KIND, 'state': 'SMALL_CLASS_REHEARSAL_VERIFIED',
            'sourceSha256': source_sha(), 'runId': context['runId'], 'datasetId': context['datasetId'],
            'resourceFence': context['lease']['fencingToken'], 'contextSha256': context_sha,
            'preparationManifestSha256': context['manifestSha256'], 'restoreReceiptSha256': standalone_sha,
            'serverUuid': wrapper['serverUuid'], 'rdsInstanceClass': context['rdsInstanceClass'],
            'toolSources': context['toolSources'], 'toolIdentity': standalone['toolIdentity'],
            'before': before, 'after': after, 'standaloneReceipt': wrapper['standaloneReceiptObject'], 'performanceMeasured': False,
            'originalContext': {k: context[k] for k in CONTEXT_PROJECTION},
            'originalWrapper': {k: wrapper[k] for k in WRAPPER_PROJECTION}}


def validate_qualification(value, manifest, standalone, standalone_sha, expected):
    fields = {'schemaVersion', 'kind', 'state', 'sourceSha256', 'runId', 'datasetId', 'resourceFence', 'contextSha256',
              'preparationManifestSha256', 'restoreReceiptSha256', 'serverUuid', 'rdsInstanceClass', 'toolSources',
              'toolIdentity', 'before', 'after', 'standaloneReceipt', 'performanceMeasured', 'originalContext', 'originalWrapper'}
    need(set(value) == fields and value['schemaVersion'] == 1 and value['kind'] == KIND
         and value['state'] == 'SMALL_CLASS_REHEARSAL_VERIFIED' and value['sourceSha256'] == source_sha(), 'CLASS_QUALIFICATION_PRODUCER')
    if manifest is not None:
        need(value['toolSources'] == manifest['toolSources']
             and value['restoreReceiptSha256'] == manifest['files']['smallRdsReceipt']['sha256'], 'REHEARSAL_DIFFERS_FROM_SEALED_WRAPPER')
    need(value['rdsInstanceClass'] == selected(expected)
         and value['restoreReceiptSha256'] == standalone_sha == value['standaloneReceipt']['sha256']
         and value['datasetId'] == standalone.get('datasetId') and value['toolIdentity'] == standalone.get('toolIdentity')
         and value['serverUuid'] == standalone.get('beforeDatabase', {}).get('serverUuid')
         and value['performanceMeasured'] is False, 'SAME_CLASS_SMALL_REHEARSAL_REQUIRED')
    # Historical validation uses the actually retained projection and expiry.
    # It neither creates a new live window nor synthesizes a success wrapper.
    context, wrapper = value['originalContext'], value['originalWrapper']
    need(set(context) == CONTEXT_PROJECTION and set(wrapper) == WRAPPER_PROJECTION, 'HISTORICAL_PROJECTION_FIELDS')
    need(context['runId'] == value['runId'] and context['datasetId'] == value['datasetId']
         and context['lease']['fencingToken'] == value['resourceFence']
         and context['manifestSha256'] == value['preparationManifestSha256']
         and context['rdsInstanceClass'] == value['rdsInstanceClass'] and context['toolSources'] == value['toolSources']
         and wrapper['standaloneReceiptObject'] == value['standaloneReceipt']
         and wrapper['serverUuid'] == value['serverUuid'], 'HISTORICAL_PROJECTION_BINDING')
    validate_history(context, value['contextSha256'], value['before'], value['after'], wrapper, standalone, standalone_sha)
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('selected', 'live', 'observe', 'qualify', 'validate-rehearsal', 'validate-snapshot-rehearsal', 'verify-small', 'plan'))
    for name in ('operator', 'phase3', 'rds', 'context', 'before', 'after', 'wrapper', 'standalone', 'manifest', 'qualification', 'provenance', 'transition', 'output'):
        parser.add_argument('--' + name, type=Path)
    parser.add_argument('--instance-class', default=DEFAULT)
    parser.add_argument('--stage', choices=('before', 'after'))
    parser.add_argument('--aws')
    parser.add_argument('--provenance-sha256')
    parser.add_argument('--transition-sha256')
    args = parser.parse_args()
    try:
        transition = None
        if args.transition is not None:
            need(args.mode in ('selected', 'live') and args.transition_sha256 == sha(args.transition),
                 'MAC_TRANSITION_BYTES_OR_MODE_CHANGED')
            transition = read(args.transition)
        else:
            need(args.transition_sha256 is None, 'MAC_TRANSITION_FILE_REQUIRED')
        if args.mode == 'selected':
            print(effective_class(read(args.operator), transition, sha(args.operator))); return
        if args.mode == 'plan':
            provenance = None
            if args.provenance is not None:
                need(args.provenance_sha256 == sha(args.provenance), 'SNAPSHOT_PROVENANCE_BYTES_CHANGED')
                provenance = read(args.provenance)
            result = validate_plan(read(args.rds), args.instance_class, provenance)
        elif args.mode == 'live':
            result = validate_live(read(args.operator), read(args.phase3), read(args.rds), transition, sha(args.operator))
        elif args.mode == 'observe':
            context = read(args.context)
            result = subprocess.run([args.aws, '--region', 'ap-northeast-2', '--cli-connect-timeout', '5', '--cli-read-timeout', '15',
                'rds', 'describe-db-instances', '--db-instance-identifier', context['rds']['identifier'], '--output', 'json'],
                capture_output=True, timeout=30, check=False)
            need(result.returncode == 0 and len(result.stdout) <= MAX_BYTES, 'RDS_CLASS_READ_FAILED')
            result = observe(context, sha(args.context), args.stage, json.loads(result.stdout, object_pairs_hook=pairs))
        elif args.mode == 'qualify':
            result = qualify(read(args.context), sha(args.context), read(args.before), read(args.after),
                             read(args.wrapper), read(args.standalone), sha(args.standalone))
        elif args.mode == 'verify-small':
            import growth_b_prepare as preparation
            manifest = read(args.manifest)
            result = validate_qualification(read(args.qualification), None, read(args.standalone), sha(args.standalone), args.instance_class)
            need(manifest['scope'] == 'small-rds-rehearsal' and result['datasetId'] == manifest['datasetId']
                 and result['preparationManifestSha256'] == sha(args.manifest)
                 and result['toolSources'] == manifest['toolSources'] == preparation.source_hashes(Path(__file__).parent), 'SMALL_PRODUCER_BINDING')
        elif args.mode == 'validate-snapshot-rehearsal':
            import growth_b_snapshot as snapshot
            import growth_b_prepare as preparation
            core = snapshot.validate_provenance(read(args.provenance))
            # validate_rehearsal consumes precisely these two sealed properties;
            # they come from the actual provenance, not a fabricated envelope.
            snapshot.restore.validate_rehearsal({'smallRdsReceipt': {'path': str(args.standalone), 'sha256': sha(args.standalone)}},
                {'appJarSha256': core['application']['appJarSha256'], 'objects': core['publication']['objects']})
            result = validate_qualification(read(args.qualification), None, read(args.standalone), sha(args.standalone), args.instance_class)
            need(result['toolSources'] == preparation.source_hashes(Path(__file__).parent), 'CURRENT_SEALED_TOOLS_REQUIRED')
        else:
            result = validate_qualification(read(args.qualification), read(args.manifest), read(args.standalone),
                                            sha(args.standalone), args.instance_class)
        if args.output:
            write(args.output, result)
        else:
            print(json.dumps({'state': 'RDS_CLASS_VERIFIED'}))
    except (ValueError, OSError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print(json.dumps({'state': 'REJECTED', 'code': str(error) if isinstance(error, Rejected) else 'CLASS_INPUT_INVALID'}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
