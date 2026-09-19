#!/usr/bin/env python3
"""Mac snapshot admission and observation; Terraform alone requests the restore.

The outer operator owns dispatch, leases, counts and service assembly. This
controller never creates/deletes an RDS, runs SQL, or changes an EC2 instance.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import time

import growth_b_mac_snapshot as mac
from growth_b_aws_restore import Aws, Lease

REGION, ACCOUNT, EVIDENCE = mac.service.REGION, mac.service.ACCOUNT, mac.service.EVIDENCE
DATASET_BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
KIND = 'global-b-mac-snapshot-controller'
need = mac.need


def read(path):
    path = Path(path)
    need(path.is_file() and not path.is_symlink() and path.stat().st_size <= mac.MAX_BYTES, 'BOUNDED_PUBLIC_JSON_REQUIRED')
    return mac.parse(path.read_bytes())


def write(path, value):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY | getattr(os, 'O_NOFOLLOW', 0), 0o600), 'wb') as stream:
        stream.write(mac.encoded(value))
    return path


def output_directory(path):
    root = Path(path)
    need(not root.exists(), 'NEW_CONTROLLER_OUTPUT_REQUIRED')
    root.mkdir(mode=0o700, parents=True)
    return root


def bucket(ref):
    key = ref['key']
    need(key.startswith(('datasets/', 'data-bootstrap/', 'measurements/state-clean/')), 'PUBLIC_REFERENCE_NAMESPACE_CHANGED')
    return DATASET_BUCKET if key.startswith('datasets/') else EVIDENCE


def fetch(aws, ref, root, name):
    mac.public_ref(ref)
    path = Path(root) / name
    need(not path.exists(), 'PUBLIC_INPUT_ALREADY_EXISTS')
    meta = aws.call('s3api', 'get-object', '--bucket', bucket(ref), '--key', ref['key'],
                    '--version-id', ref['versionId'], str(path))
    raw = path.read_bytes()
    need(meta.get('VersionId') == ref['versionId'] and len(raw) == ref['bytes'] and mac.sha(raw) == ref['sha256'],
         'EXACT_PUBLIC_INPUT_CHANGED')
    path.chmod(0o600)
    return {'reference': {'bucket': bucket(ref), **ref}, 'rawUtf8': raw.decode('utf-8')}


def publish(aws, key, value, root, name, guard):
    guard()
    path = write(Path(root) / name, value)
    # An uncertain or existing publication is left for observation by the outer
    # operator. No blind overwrite/retry and no second RDS restore occur here.
    reply = aws.call('s3api', 'put-object', '--bucket', EVIDENCE, '--key', key, '--body', str(path),
                     '--server-side-encryption', 'AES256', '--tagging', 'Retention=summary', '--if-none-match', '*')
    ref = {'key': key, 'versionId': reply.get('VersionId'), 'sha256': mac.sha(path.read_bytes()), 'bytes': path.stat().st_size}
    child = fetch(aws, ref, root, 'readback-' + name)
    need(mac.child(child) == value, 'IMMUTABLE_PUBLICATION_READBACK_CHANGED')
    guard()
    return ref, child


def snapshot_observation(aws, source):
    projection = mac.validate_source(source)
    selected = projection['snapshot']
    rows = aws.call('rds', 'describe-db-snapshots', '--db-snapshot-identifier', selected['identifier'])['DBSnapshots']
    need(len(rows) == 1, 'EXACT_MANUAL_SNAPSHOT_REQUIRED')
    tags = aws.call('rds', 'list-tags-for-resource', '--resource-name', selected['arn'])['TagList']
    attributes = aws.call('rds', 'describe-db-snapshot-attributes', '--db-snapshot-identifier', selected['identifier'])
    observation = {'snapshot': rows[0], 'tags': tags,
        'attributes': attributes['DBSnapshotAttributesResult']['DBSnapshotAttributes']}
    need(mac.snapshot_projection(observation) == selected, 'LIVE_SOURCE_SNAPSHOT_CHANGED')
    return observation


def verify_source(aws, source):
    need(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'AWS_ACCOUNT_CHANGED')
    snapshot_observation(aws, source)
    return {'state': 'MAC_SNAPSHOT_SOURCE_VERIFIED', 'sourceSha256': mac.digest(source),
            'snapshotIdentifier': source['snapshot']['identifier'], 'fullDatasetValidated': False}


def validate_retirement(operation, retirement):
    clean = mac.child(retirement)
    ref = retirement['reference']
    need(ref.get('bucket') == EVIDENCE and {k: v for k, v in ref.items() if k != 'bucket'} == operation['retirementReference'],
         'EXACT_RETIREMENT_REFERENCE_REQUIRED')
    empty = operation['emptyState']
    version_sha = mac.sha(empty['versionId'].encode())
    need(clean.get('schemaVersion') == 1 and clean.get('status') == 'clean' and clean.get('dnsMode') == 'direct-only'
         and clean['runId'] != operation['runId'] and type(clean['resourceFencingToken']) is int
         and clean['resourceFencingToken'] < operation['resourceFence']
         and clean['ociAuthority']['status'] == 'verified'
         and clean['orphanScan'] == {'status': 'clean', 'scope': 'global', 'runId': clean['runId']}
         and clean['terraformState'] == {'key': empty['key'], 'versionId': empty['versionId'], 'versionIdSha256': version_sha,
             'objectSha256': empty['sha256'], 'resourceCount': 0}
         and operation['retirementReference']['key'] == 'measurements/state-clean/' + version_sha + '.json'
         and mac.epoch(clean['completedAt']) <= operation['window']['startedAtEpoch'], 'LATEST_RETIRED_EMPTY_STATE_REQUIRED')


def admit(aws, source, operation, retirement, root, guard):
    guard()
    mac.validate_operation(operation, now=time.time())
    projection = mac.validate_source(source)
    need(operation['sourceProvenance']['sha256'] == mac.digest(source)
         and operation['sourceProvenance']['bytes'] == len(mac.encoded(source))
         and operation['sourceProvenance']['key'] == ('datasets/' + mac.DATASET + '-mac-snapshots/'
             + source['snapshot']['identifier'] + '/source-' + mac.digest(source) + '.json')
         and operation['resourceFence'] > projection['source']['resourceFence'], 'EXACT_NEW_SOURCE_BINDING_REQUIRED')
    validate_retirement(operation, retirement)
    snapshot_observation(aws, source)
    need(aws.call('rds', 'describe-db-instances')['DBInstances'] == [], 'SOURCE_AND_TARGET_MUST_BE_ABSENT')
    result = {'schemaVersion': 1, 'kind': KIND + '-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT',
        'operation': operation, 'sourceSha256': mac.digest(source), 'snapshotArn': source['snapshot']['arn'],
        'retirementReference': operation['retirementReference'], 'observedAtEpoch': time.time()}
    ref, _ = publish(aws, prefix(operation) + 'admission.json', result, root, 'admission.json', guard)
    return {'state': result['state'], 'admissionReference': ref}


def prefix(operation):
    return f'data-bootstrap/{operation["runId"]}/mac-snapshot-{operation["resourceFence"]}/'


def public_event(raw, source, operation, target, observed_at):
    value = mac.parse(raw.encode())
    request = value.get('requestParameters') or {}
    if (value.get('eventName') != 'RestoreDBInstanceFromDBSnapshot' or value.get('eventSource') != 'rds.amazonaws.com'
            or value.get('awsRegion') != REGION or value.get('recipientAccountId') != ACCOUNT
            or value.get('errorCode') or value.get('errorMessage')
            or request.get('dBInstanceIdentifier') != operation['targetIdentifier']
            or request.get('dBSnapshotIdentifier') not in (source['snapshot']['identifier'], source['snapshot']['arn'])):
        return None
    # Publish only actual safe CloudTrail coordinates, never caller credentials,
    # tokens or unrelated request parameters from the original response.
    result = {key: value[key] for key in ('eventName', 'eventSource', 'awsRegion', 'recipientAccountId', 'eventID', 'eventTime')}
    result['requestParameters'] = {key: request[key] for key in ('dBInstanceIdentifier', 'dBSnapshotIdentifier')}
    requested, created = mac.epoch(result['eventTime']), mac.epoch(target['createdAt'])
    need(re.fullmatch(mac.UUID, result['eventID']) and operation['window']['startedAtEpoch'] <= requested <= created+300
         and created-requested <= 7200 and requested <= observed_at and created <= observed_at, 'ACTUAL_RESTORE_CHRONOLOGY_REQUIRED')
    # The full consumer validates this again after publication, with the actual
    # S3 reference and the exact published bytes; no provisional ref is emitted.
    return result


def capture_restore(aws, source, operation, retirement, root, guard, *, clock=time.time, monotonic=time.monotonic, sleep=time.sleep):
    guard()
    # This is deliberately the first RDS read after Terraform apply. Snapshot
    # metadata and CloudTrail polling happen only after its time is captured.
    api = aws.call('rds', 'describe-db-instances')
    observed_at = clock()
    mac.validate_operation(operation, now=observed_at)
    target = mac.check_target_rds(api, operation, source)
    available = {'schemaVersion': 1, 'kind': KIND + '-available', 'operationSha256': mac.digest(operation),
        'observedAtEpoch': observed_at, 'target': target}
    available_ref, _ = publish(aws, prefix(operation) + 'available.json', available, root, 'available.json', guard)
    observation = snapshot_observation(aws, source)
    validate_retirement(operation, retirement)
    deadline = monotonic() + 600
    event = None
    while event is None:
        guard()
        start = dt.datetime.fromtimestamp(operation['window']['startedAtEpoch'], dt.timezone.utc).isoformat()
        events = aws.call('cloudtrail', 'lookup-events', '--lookup-attributes',
            json.dumps([{'AttributeKey': 'EventName', 'AttributeValue': 'RestoreDBInstanceFromDBSnapshot'}]), '--start-time', start)['Events']
        selected = {}
        for row in events:
            candidate = public_event(row['CloudTrailEvent'], source, operation, target, observed_at)
            if candidate is not None:
                selected[candidate['eventID']] = candidate
        need(len(selected) <= 1, 'AMBIGUOUS_RESTORE_EVENT')
        if selected:
            event = next(iter(selected.values()))
            break
        need(monotonic() < deadline, 'RESTORE_EVENT_NOT_VISIBLE_AVAILABLE_TIME_RETAINED')
        sleep(15)
    event_ref, event_child = publish(aws, prefix(operation) + 'restore-event.json', event, root, 'restore-event.json', guard)
    restored = mac.validate_restore(operation, source, retirement, observation, api, event_child, observed_at=observed_at)
    restore_ref, _ = publish(aws, prefix(operation) + 'restore.json', restored, root, 'restore.json', guard)
    return {'state': restored['state'], 'availableReference': available_ref, 'eventReference': event_ref,
            'restoreReference': restore_ref, 'requestToAvailableSeconds': restored['requestToAvailableSeconds']}


def validate_plan(plan, source):
    projection = mac.validate_source(source)
    for row in plan.get('resource_changes', []):
        after, before = row['change'].get('after'), row['change'].get('before')
        if row.get('type') == 'aws_instance' and after is not None:
            need(after.get('tags', {}).get('Service') in ('nat', 'egress-probe'), 'MAC_RESTORE_HAS_NO_SERVICE_OR_IMPORT_HOST')
        if row.get('type') != 'aws_db_instance':
            continue
        need('delete' not in row['change']['actions'] and isinstance(after, dict)
             and after.get('instance_class') == 'db.t3.small' and after.get('engine_version') == '8.4.11'
             and after.get('multi_az') is False and after.get('storage_encrypted') is True
             and after.get('publicly_accessible') is False and 'BDatabaseClass' not in after.get('tags', {}), 'MAC_RESTORE_RDS_PLAN_CHANGED')
        if before is None:
            need(row['change']['actions'] == ['create'] and after.get('snapshot_identifier') == projection['snapshot']['identifier']
                 and after.get('allocated_storage') in (None, 100) and after.get('storage_type') in (None, 'gp3'), 'MAC_RESTORE_EXACT_SNAPSHOT_REQUIRED')
        else:
            need(before.get('instance_class') == 'db.t3.small' and after.get('allocated_storage') == 100
                 and after.get('storage_type') == 'gp3', 'MAC_RESTORE_EXISTING_RDS_CHANGED')
    return {'state': 'MAC_SNAPSHOT_PLAN_VERIFIED'}


def validate_service_files(manifest, original, root):
    mac.validate_preparation_fields(manifest['preparation'], manifest['datasetId'], manifest['runId'])
    documents = {}
    for field, filename in [('sourceProvenance', 'source.json'), ('restoreReceipt', 'restore.json'),
                            ('countsDdlReceipt', 'counts.json'), ('receipt', 'target.json')]:
        raw = (Path(root) / filename).read_bytes()
        ref = manifest['preparation'][field]
        need(mac.sha(raw) == ref['sha256'] and len(raw) == ref['bytes'], 'MAC_TARGET_SOURCE_BYTES_CHANGED')
        documents[field] = mac.parse(raw)
    source, restored, counts, target = [documents[name] for name in ('sourceProvenance', 'restoreReceipt', 'countsDdlReceipt', 'receipt')]
    mac.validate_target_receipt(target, source, restored, counts)
    ref = manifest['preparation']['sourceProvenance']
    need(restored['operation']['sourceProvenance'] == ref, 'RESTORE_AND_SERVICE_SOURCE_REFERENCE_CHANGED')
    retained_ref = {'key': ref['key'], 'version_id': ref['versionId'], 'sha256': ref['sha256'], 'bytes': ref['bytes']}
    need(original.get('globalBSnapshotRestoreOnly') is True and original.get('globalBSnapshotSourceMode') == mac.MODE
         and original['globalBSnapshotProvenance'] == retained_ref and original['runId'] == manifest['runId'] == target['runId']
         and original['fencingToken'] == target['resourceFence'] and int(original['expiresAt']) == target['window']['expiresAt']
         and original['approvedExecutionDeadlineEpoch'] == target['window']['approvedDeadlineEpoch']
         and original['rdsInstanceClass'] == 'db.t3.small' and original['globalBMacSnapshotOperation'] == restored['operation']
         and manifest['rds'] == {key: target['target'][key] for key in ('identifier', 'resourceId', 'serverUuid')},
         'MAC_SNAPSHOT_SERVICE_ORIGINAL_TARGET_CHANGED')
    return {'state': 'MAC_SNAPSHOT_SERVICE_SOURCE_VERIFIED', 'runId': target['runId'], 'resourceFence': target['resourceFence']}


def validate_service(aws, manifest, original, root):
    mac.validate_preparation_fields(manifest['preparation'], manifest['datasetId'], manifest['runId'])
    for field, filename in [('sourceProvenance', 'source.json'), ('restoreReceipt', 'restore.json'),
                            ('countsDdlReceipt', 'counts.json'), ('receipt', 'target.json')]:
        fetch(aws, manifest['preparation'][field], root, filename)
    return validate_service_files(manifest, original, root)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['verify-source', 'admit', 'capture-restore', 'plan', 'validate-service'])
    for field in ('source', 'operation', 'retirement', 'lease', 'output', 'manifest', 'original', 'plan'):
        parser.add_argument('--' + field)
    args = parser.parse_args()
    if args.mode == 'plan':
        need(args.source and args.plan, 'PLAN_AND_SOURCE_REQUIRED')
        result = validate_plan(read(args.plan), read(args.source))
    elif args.mode == 'verify-source':
        need(args.source, 'SOURCE_REQUIRED')
        result = verify_source(Aws(), read(args.source))
    elif args.mode == 'validate-service':
        need(args.manifest and args.original and args.output, 'SERVICE_INPUTS_REQUIRED')
        root = output_directory(args.output)
        result = validate_service(Aws(), read(args.manifest), read(args.original), root)
        write(root / 'result.json', result)
    else:
        need(all((args.source, args.operation, args.retirement, args.lease, args.output)), 'RESTORE_CONTROLLER_INPUTS_REQUIRED')
        source, operation, retirement, lease = [read(path) for path in (args.source, args.operation, args.retirement, args.lease)]
        aws, root = Aws(), output_directory(args.output)
        need(lease['runId'] == operation['runId'] and lease['fencingToken'] == operation['resourceFence'] and lease['command'] == 'up',
             'RESTORE_LEASE_IDENTITY_CHANGED')
        lease_guard = Lease(aws, lease)
        def guard():
            mac.validate_operation(operation, now=time.time())
            lease_guard(force=True)
        result = (admit if args.mode == 'admit' else capture_restore)(aws, source, operation, retirement, root, guard)
        write(root / 'result.json', result)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
