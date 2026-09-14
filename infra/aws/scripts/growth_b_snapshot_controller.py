#!/usr/bin/env python3
"""Lease-bound B snapshot controller; hosts receive only temporary exact S3 reads."""
from __future__ import annotations
import argparse
import datetime as dt
import gzip
import io
import json
import os
from pathlib import Path
import re
import signal
import tarfile
import time
import urllib.parse

import growth_b_snapshot as snapshot
import growth_b_snapshot_host as host
import growth_b_rds_class as rds_class

restore, preparation = snapshot.restore, host.preparation
read, sha, require = snapshot.read, snapshot.sha, snapshot.require
ACCOUNT, REGION, DATASET, EVIDENCE = host.ACCOUNT, host.REGION, host.BUCKET, host.EVIDENCE
KIND = 'global-growth-b-snapshot-controller'
BOUNDARY = f'arn:aws:iam::{ACCOUNT}:policy/airbob-performance-lab-host-boundary'


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)


def write(path, value):
    Path(path).write_text(json.dumps(value, sort_keys=True, indent=2) + '\n')
    Path(path).chmod(0o600)


def package_tools(root, dataset, run, operation):
    host.coordinates(dataset, run, operation)
    root = Path(root)
    require(not root.exists(), 'Snapshot helper packaging requires a new private output directory')
    root.mkdir(mode=0o700, parents=True)
    selected = host.sources()
    archive = root / 'consumer-tools.tar.gz'
    with archive.open('wb') as stream, gzip.GzipFile(fileobj=stream, mode='wb', filename='', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as bundle:
            for name in sorted(selected):
                source = Path(__file__).with_name(name)
                require(source.is_file() and not source.is_symlink() and sha(source) == selected[name], 'Snapshot helper changed during packaging')
                data = source.read_bytes()
                member = tarfile.TarInfo(name); member.size = len(data); member.mode = 0o644; member.mtime = 0
                bundle.addfile(member, io.BytesIO(data))
    archive.chmod(0o600)
    require(archive.stat().st_size <= 4 * 1024**2 and host.sources() == selected, 'Snapshot helper archive bound or source inventory changed')
    result = {'schemaVersion': 1, 'kind': KIND + '-helper-package', 'datasetId': dataset, 'runId': run, 'operationId': operation,
        'toolSources': selected, 'archive': {'path': str(archive.resolve()), 'key': host.prefix(dataset, run, operation) + f'files/{sha(archive)}-consumer-tools.tar.gz',
        'sha256': sha(archive), 'bytes': archive.stat().st_size}, 'cloudMutationsExecuted': False}
    write(root / 'package.json', result)
    return result


def bucket(ref):
    require(ref['key'].startswith(('datasets/', 'data-bootstrap/')), 'Unexpected public object namespace')
    return DATASET if ref['key'].startswith('datasets/') else EVIDENCE


def fetch(aws, ref, root, name):
    host.reference(ref, '', maximum=4 * 1024**2)
    path = Path(root) / name
    response = aws.call('s3api', 'get-object', '--bucket', bucket(ref), '--key', ref['key'],
                       '--version-id', ref['versionId'], str(path))
    require(response.get('VersionId') == ref['versionId'] and path.stat().st_size == ref['bytes']
            and sha(path) == ref['sha256'], 'Exact public object VersionId/bytes/SHA changed')
    return read(path)


def current_object(aws, key, root, name):
    result = aws.call('s3api', 'head-object', '--bucket', EVIDENCE, '--key', key)
    version = result.get('VersionId')
    require(isinstance(version, str) and version not in ('', 'None', 'null'), 'Evidence is not versioned')
    path = Path(root) / name
    response = aws.call('s3api', 'get-object', '--bucket', EVIDENCE, '--key', key, '--version-id', version, str(path))
    require(response.get('VersionId') == version and path.stat().st_size == result['ContentLength'], 'Evidence metadata changed')
    ref = {'key': key, 'versionId': version, 'sha256': sha(path), 'bytes': path.stat().st_size}
    host.reference(ref, 'data-bootstrap/', maximum=4 * 1024**2)
    return read(path), ref


def publish(aws, key, value, root, name):
    host.public_json(value)
    path = Path(root) / name; write(path, value)
    response = aws.call('s3api', 'put-object', '--bucket', EVIDENCE, '--key', key, '--body', str(path),
                       '--server-side-encryption', 'AES256', '--tagging', 'Retention=summary', '--if-none-match', '*')
    ref = {'key': key, 'versionId': response.get('VersionId'), 'sha256': sha(path), 'bytes': path.stat().st_size}
    fetch(aws, ref, root, 'readback-' + name)
    return ref


def validate_manifest(value, checksum, dataset, run, operation_id):
    require(re.fullmatch(r'[0-9a-f]{64}', checksum), 'Reviewed operation manifest SHA required')
    host.validate_manifest(value, dataset, run, operation_id, host.sources())
    for name, ref in value['evidence'].items():
        if name != 'provenance':
            require(ref['key'].startswith(f'data-bootstrap/{run}/'), 'Evidence belongs to another target/source run')
    return value


def collect_read_references(aws, value, manifest_ref, root):
    wrapper = fetch(aws, value['awsPreparation'], root, 'preparation-wrapper.json')
    preparation.validate_manifest(wrapper, value['datasetId'], preparation.source_hashes(Path(__file__).parent))
    require(wrapper['scope'] == 'final-b-rds', 'Snapshots require the sealed final B preparation')
    envelope = fetch(aws, wrapper['files']['envelope'], root, 'envelope.json')
    preparation.validate_envelope_metadata(wrapper, envelope)
    refs = [manifest_ref, value['awsPreparation'], value['consumerTools'], *value['evidence'].values(),
            *wrapper['files'].values(), *envelope['objects'].values()]
    if 'resume' in value:
        refs.append(value['resume']['operationReceipt'])
    unique = {(bucket(ref), ref['key'], ref['versionId']): ref for ref in refs}
    return list(unique.values())


def read_policy(refs, deadline):
    # Selected immutable prefixes and a finite VersionId pool keep the document
    # below the role quota. The host verifies each exact key/version/hash pair.
    statements = []
    for selected in (DATASET, EVIDENCE):
        rows = [ref for ref in refs if bucket(ref) == selected]
        if not rows:
            continue
        resources = set()
        for ref in rows:
            key = ref['key']
            sealed = re.fullmatch(r'(datasets/global-growth-b-[0-9a-f]{16}(?:-aws-preparation)?)/.+', key)
            operation = re.fullmatch(r'(datasets/global-growth-b-[0-9a-f]{16}-aws-snapshots/operations/lab-[a-z0-9-]+/[a-z0-9-]+)/.+', key)
            scope = (sealed or operation).group(1) + '/*' if (sealed or operation) else key
            resources.add(f'arn:aws:s3:::{selected}/{scope}')
        statements.append({'Sid': 'ReadPinned' + ('Dataset' if selected == DATASET else 'Evidence'),
            'Effect': 'Allow', 'Action': 's3:GetObjectVersion',
            'Resource': sorted(resources),
            'Condition': {'StringEquals': {'s3:VersionId': sorted({ref['versionId'] for ref in rows})},
                          'DateLessThan': {'aws:CurrentTime': dt.datetime.fromtimestamp(deadline, dt.timezone.utc).isoformat()}}})
    return {'Version': '2012-10-17', 'Statement': statements}


def policy_document(value):
    return value if isinstance(value, dict) else json.loads(urllib.parse.unquote(value))


def validate_target(aws, context, manifest, resource_fence):
    caller = aws.call('sts', 'get-caller-identity')
    require(caller.get('Account') == ACCOUNT and re.fullmatch(f'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-lab-operator/[A-Za-z0-9+=,.@_-]+', caller.get('Arn', '')),
            'The six-hour direct Lab controller role is required')
    rows = aws.call('rds', 'describe-db-instances')['DBInstances']
    require(len(rows) == 1, 'Only one business RDS may exist during B snapshot operations')
    row = rows[0]; selected = context['rds']
    chosen_class = rds_class.selected(context.get('rdsInstanceClass', rds_class.DEFAULT))
    require(context.get('resourceFence', resource_fence) == resource_fence, 'Original class resource fence differs')
    rds_class.actual_class(row, chosen_class)
    resource_tags = snapshot.tags(row.get('TagList', []))
    require(row['DBInstanceIdentifier'] == selected['identifier'] and row['DbiResourceId'] == selected['resourceId']
            and row['Endpoint']['Address'] == selected['endpoint'] and row['Endpoint']['Port'] == 3306
            and row['MasterUserSecret']['SecretArn'] == selected['masterSecretArn'] and row['EngineVersion'] == '8.4.11'
            and row['DBInstanceStatus'] == 'available' and row['PubliclyAccessible'] is False
            and row.get('StorageEncrypted') is True and row.get('DBInstanceClass') == chosen_class
            and resource_tags.get('RunId') == context['runId'] and resource_tags.get('FencingToken') == str(resource_fence)
            and resource_tags.get('Project') == 'airbob' and resource_tags.get('Stack') == 'lab', 'Live B RDS target changed')
    role = 'airbob-lab-host-' + context['runId'] + '-debezium'
    response = aws.call('iam', 'get-role', '--role-name', role)['Role']
    tags = snapshot.tags(response.get('Tags', []))
    require(response['PermissionsBoundary']['PermissionsBoundaryArn'] == BOUNDARY
            and tags.get('RunId') == context['runId'] and tags.get('FencingToken') == str(resource_fence)
            and tags.get('Project') == 'airbob' and tags.get('Stack') == 'lab' and tags.get('Service') == 'debezium',
            'The exact retained host role/boundary/resource fence is required')
    reservations = aws.call('ec2', 'describe-instances', '--instance-ids', context['hostInstanceId'])['Reservations']
    instances = [item for reservation in reservations for item in reservation['Instances']]
    require(len(instances) == 1 and instances[0]['State']['Name'] == 'running', 'The exact preparation host is unavailable')
    instance = instances[0]; tags = snapshot.tags(instance.get('Tags', []))
    require(tags.get('RunId') == context['runId'] and tags.get('FencingToken') == str(resource_fence)
            and tags.get('Service') == 'debezium' and instance['IamInstanceProfile']['Arn'].endswith('/' + role),
            'The preparation host instance/profile/fence differs')
    groups = aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
    require(all(group['DesiredCapacity'] == group['MinSize'] == 0 and not group['Instances']
                for group in groups if group['AutoScalingGroupName'].startswith('airbob-' + context['runId'] + '-')),
            'Every source/target application ASG must be stopped')
    if manifest['operation'] in ('create', 'retire'):
        require(all(manifest['source']['rds'][key] == selected[key] for key in ('identifier', 'resourceId', 'endpoint')),
                'The source manifest does not identify this retained RDS')
    return role


def status(aws, command_id, instance):
    rows = aws.call('ssm', 'list-command-invocations', '--command-id', command_id, '--instance-id', instance)['CommandInvocations']
    require(len(rows) <= 1, 'SSM command invocation is ambiguous')
    return rows[0]['Status'] if rows else 'Pending'


TERMINAL_HOST_STATES = {'Success', 'Failed', 'Cancelled', 'TimedOut', 'Undeliverable', 'Terminated'}


def finish_operation_access(aws, context, role, policy_name, expected_policy_sha, command_id, dispatch_attempted,
                            attached, attachment_attempted, completed, primary_failure_type, root):
    result = {'schemaVersion': 1, 'kind': KIND + '-cleanup', 'state': 'CLEANUP_REQUIRES_REVIEW',
        'runId': context['runId'], 'datasetId': context['datasetId'], 'operationId': context['operationId'],
        'lease': context['lease'], 'hostInstanceId': context['hostInstanceId'], 'commandId': command_id,
        'roleName': role, 'policyName': policy_name, 'policyCanonicalSha256': expected_policy_sha,
        'deadlineEpoch': context['deadlineEpoch'], 'hostOperationCompleted': completed,
        'originalFailureCode': 'SNAPSHOT_OPERATION_FAILED' if primary_failure_type else None,
        'originalFailureType': primary_failure_type, 'cancellationAttempted': False, 'cancellationConfirmed': False,
        'cancellationPolicy': 'NO_CANCEL_COMMAND_GRANT; observe exact invocation or retain until original deadline',
        'hostState': 'UNKNOWN' if dispatch_attempted else 'NOT_DISPATCHED', 'hostTerminalObserved': False,
        'policyRemoved': False, 'policyRetained': attached, 'dataResourcesRetained': True,
        'sourceDeletionExecuted': False, 'cleanupFailureCode': None, 'observationFailureCode': None,
        'resumeAction': 'cleanup-access or down under a new valid lease after exact host terminal or original deadline'}
    if attachment_attempted and not attached:
        try:
            names = aws.call('iam', 'list-role-policies', '--role-name', role)['PolicyNames']
            attached = policy_name in names
            result['policyRetained'] = attached
        except BaseException as error:
            result.update(policyRetained=True, observationFailureCode='IAM_ATTACHMENT_STATE_UNKNOWN', observationFailureType=type(error).__name__)
    if command_id:
        try:
            observed = status(aws, command_id, context['hostInstanceId'])
            require(observed in TERMINAL_HOST_STATES | {'Pending', 'InProgress', 'Delayed', 'Cancelling'}, 'Unknown host state')
            result.update(hostState=observed, hostTerminalObserved=observed in TERMINAL_HOST_STATES)
        except BaseException as error:
            result.update(observationFailureCode='SSM_STATUS_READ_FAILED', observationFailureType=type(error).__name__)
    expired = time.time() >= context['deadlineEpoch']
    result['originalDeadlineExpired'] = expired
    if attached and (not dispatch_attempted or result['hostTerminalObserved'] or expired):
        try:
            actual = policy_document(aws.call('iam', 'get-role-policy', '--role-name', role, '--policy-name', policy_name)['PolicyDocument'])
            require(restore.canonical_sha(actual) == expected_policy_sha, 'Temporary policy changed')
            aws.call('iam', 'delete-role-policy', '--role-name', role, '--policy-name', policy_name)
            result.update(policyRemoved=True, policyRetained=False)
        except BaseException as error:
            result.update(cleanupFailureCode='EXACT_POLICY_CLEANUP_FAILED', cleanupFailureType=type(error).__name__)
    result['state'] = 'ACCESS_CLEANUP_COMPLETE' if not result['policyRetained'] else 'HOST_OR_ACCESS_RETAINED'
    try:
        publish(aws, context['evidencePrefix'] + 'controller-cleanup.json', result, root, 'controller-cleanup.json')
    except BaseException as error:
        result.update(evidenceFailureCode='CLEANUP_RECEIPT_PUBLICATION_FAILED', evidenceFailureType=type(error).__name__)
        try:
            write(root / 'controller-cleanup-local.json', result)
        except BaseException:
            result['localEvidenceFailureCode'] = 'CLEANUP_LOCAL_RECORD_FAILED'
        try:
            print(json.dumps({'kind': result['kind'], 'state': result['state'], 'originalFailureCode': result['originalFailureCode'],
                              'cleanupFailureCode': result['cleanupFailureCode'], 'observationFailureCode': result['observationFailureCode'],
                              'evidenceFailureCode': result['evidenceFailureCode'], 'dataResourcesRetained': True}))
        except BaseException:
            pass  # The original operation failure must remain the raised error.
    return result


def validate_ack(ack, request, request_ref, context):
    now = dt.datetime.now(dt.timezone.utc)
    require(ack.get('kind') == host.KIND + '-read-lock-ack' and ack.get('state') == 'SOURCE_READ_LOCK_HELD'
            and ack.get('manifestSha256') == context['manifest']['sha256'] and ack.get('requestSha256') == request_ref['sha256']
            and ack.get('requestVersionId') == request_ref['versionId'] and ack.get('requestKey') == request_ref['key']
            and ack.get('source') == request['source'] and ack.get('lease') == context['lease']
            and ack.get('hostInstanceId') == context['hostInstanceId'] and ack.get('snapshotIdentifier') == request['snapshotIdentifier']
            and type(ack.get('connectionId')) is int and ack['connectionId'] > 0
            and snapshot.instant(ack['observedAt']) <= now < snapshot.instant(ack['expiresAt'])
            and (now - snapshot.instant(ack['observedAt'])).total_seconds() <= 45
            and (snapshot.instant(ack['expiresAt']) - snapshot.instant(ack['observedAt'])).total_seconds() <= 45
            and ack.get('hostCreatesOrDeletesAwsResources') is False, 'Fresh authenticated READ-lock acknowledgement required')


def create_exact_snapshot(aws, manifest, context, root, guard, command_id, approved_identifier):
    request_key = context['evidencePrefix'] + 'snapshot-create-request.json'
    request, ref = current_object(aws, request_key, root, 'source-create-request.json')
    now = dt.datetime.now(dt.timezone.utc)
    require(request.get('kind') == snapshot.KIND + '-controller-create-request' and request.get('state') == 'SOURCE_READ_LOCK_HELD'
            and request.get('source') == manifest['source']['rds'] and request.get('snapshotIdentifier') == manifest['snapshotIdentifier'] == approved_identifier
            and request.get('requiredLease') == context['lease'] and request.get('requiresLiveReadLockAcknowledgment') is True
            and request.get('hostCreatesOrDeletesAwsResources') is False and re.fullmatch(r'[0-9a-f]{64}', request.get('contractSha256', ''))
            and snapshot.instant(request['requestedAt']) <= now < snapshot.instant(request['deadlineAt'])
            and (now - snapshot.instant(request['requestedAt'])).total_seconds() <= 60,
            'The exact source-held controller request differs from review')
    expected_tags = {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'dataset', 'Persistence': 'persistent',
        'ManagedBy': 'global-b-snapshot', 'BProvenanceSchemaVersion': '1', 'DatasetId': manifest['datasetId'],
        'SourceRdsResourceId': manifest['source']['rds']['resourceId'], 'SourceMysqlUuid': manifest['source']['rds']['serverUuid'],
        'SourceRunId': context['runId'], 'MysqlVersion': '8.4.11', 'FlywayVersion': '28',
        'SnapshotContractSha256': request['contractSha256'],
        'SourceRestoreReceiptSha256': manifest['evidence']['restoreReceipt']['sha256'],
        'SourceServiceResetReceiptSha256': manifest['evidence']['serviceResetReceipt']['sha256'],
        'PreparedFingerprintSha256': manifest['evidence']['preparedFingerprint']['sha256'],
        'PrivateHandoffSha256': manifest['source']['privateAccountsSha256']}
    require(all(request.get('tags', {}).get(key) == value for key, value in expected_tags.items()), 'Snapshot tags/source contract differ')
    rows = aws.call('s3api', 'list-objects-v2', '--bucket', EVIDENCE, '--prefix', context['evidencePrefix'] + 'read-lock-acks/').get('Contents', [])
    require(rows, 'The source host has not acknowledged its live READ lock')
    newest = max(rows, key=lambda item: item['Key'])
    ack, ack_ref = current_object(aws, newest['Key'], root, 'source-lock-ack.json')
    validate_ack(ack, request, ref, context)
    require(status(aws, command_id, context['hostInstanceId']) == 'InProgress', 'The READ-lock SSM invocation is no longer running')
    require(not snapshot.snapshots(aws, approved_identifier), 'Existing snapshots cannot be overwritten or adopted by the controller')
    guard(force=True); validate_ack(ack, request, ref, context)
    intent = {'schemaVersion': 1, 'kind': KIND + '-create-intent', 'request': ref, 'ack': ack_ref,
        'lease': context['lease'], 'source': request['source'], 'snapshotIdentifier': approved_identifier, 'ssmCommandId': command_id}
    publish(aws, context['evidencePrefix'] + 'controller-create-intent.json', intent, root, 'create-intent.json')
    guard(force=True); validate_ack(ack, request, ref, context)
    tags = [{'Key': key, 'Value': value} for key, value in sorted(request['tags'].items())]
    result = aws.call('rds', 'create-db-snapshot', '--db-instance-identifier', request['source']['identifier'],
                      '--db-snapshot-identifier', approved_identifier, '--tags', canonical(tags))['DBSnapshot']
    require(result['DBSnapshotIdentifier'] == approved_identifier and result['DBInstanceIdentifier'] == request['source']['identifier'],
            'RDS returned a different snapshot identity')
    return {'request': ref, 'ack': ack_ref, 'snapshotArn': result['DBSnapshotArn']}


def dispatch(aws, context, root, *, stop=False):
    import base64
    destination = '/opt/airbob/bootstrap-helpers/b-snapshot-' + str(context['lease']['fencingToken'])
    if stop:
        commands = ['set -euo pipefail', 'umask 077', 'test -d /opt/airbob/global-b/' + context['runId'],
                    'printf "%s\\n" ' + context['manifest']['sha256'] + ' > /opt/airbob/global-b/' + context['runId'] + '/STOP']
    else:
        wrapper = Path(__file__).with_name('bootstrap-growth-b-snapshot.sh')
        encoded = base64.b64encode(wrapper.read_bytes()).decode()
        encoded_context = base64.b64encode(canonical(context).encode()).decode()
        commands = ['set -euo pipefail', 'umask 077', 'install -d -m 700 ' + destination,
            "printf '%s' '" + encoded + "' | base64 --decode > " + destination + '/entry.sh',
            "printf '%s' '" + encoded_context + "' | base64 --decode > " + destination + '/context.json',
            "printf '%s  %s\\n' '" + sha(wrapper) + "' '" + destination + "/entry.sh' | sha256sum --check --status",
            'bash ' + destination + '/entry.sh ' + destination + '/context.json']
    parameters = {'commands': commands, 'executionTimeout': ['60' if stop else str(max(60, min(18000, int(context['deadlineEpoch'] - time.time()))))]}
    path = Path(root) / ('stop-parameters.json' if stop else 'host-parameters.json'); write(path, parameters)
    response = aws.call('ssm', 'send-command', '--document-name', 'AWS-RunShellScript', '--instance-ids', context['hostInstanceId'],
                       '--parameters', 'file://' + str(path), '--timeout-seconds', '600')
    return response['Command']['CommandId']


def validate_retirement_admission(admission, provenance, core, manifest):
    require(admission.get('kind') == snapshot.KIND + '-source-deletion-admission'
            and admission.get('source') == manifest['source']['rds'] == core['source']
            and admission.get('contractSha256') == provenance['contractSha256']
            and admission.get('sourceDeletionAllowed') is True and admission.get('snapshotAvailableVerified') is True
            and admission.get('privateHandoffPreserved') is True and admission.get('deletionExecuted') is False
            and snapshot.instant(admission['issuedAt']) <= dt.datetime.now(dt.timezone.utc) < snapshot.instant(admission['expiresAt'])
            and (snapshot.instant(admission['expiresAt']) - snapshot.instant(admission['issuedAt'])).total_seconds() <= 60,
            'Fresh exact source deletion admission required')


def retire_source(aws, manifest, context, receipt, root, guard):
    provenance = fetch(aws, receipt['objects']['snapshot-provenance.json'], root, 'retirement-provenance.json')
    admission = fetch(aws, receipt['objects']['source-deletion-admission.json'], root, 'source-deletion-admission.json')
    core = snapshot.verify(aws, provenance)
    validate_retirement_admission(admission, provenance, core, manifest)
    guard(force=True)
    command_id = dispatch(aws, context, root, stop=True)
    while status(aws, command_id, context['hostInstanceId']) not in ('Success', 'Failed', 'Cancelled', 'TimedOut'):
        guard(force=True); require(time.time() < context['deadlineEpoch'], 'STOP command exceeded the operation deadline'); time.sleep(2)
    require(status(aws, command_id, context['hostInstanceId']) == 'Success', 'The source STOP fence was not installed')
    validate_retirement_admission(admission, provenance, core, manifest)
    require(snapshot.verify(aws, provenance) == core, 'Snapshot provenance changed while installing the STOP fence')
    sources = aws.call('rds', 'describe-db-instances')['DBInstances']
    require(len(sources) == 1 and sources[0]['DBInstanceIdentifier'] == core['source']['identifier']
            and sources[0]['DbiResourceId'] == core['source']['resourceId'], 'The exact source changed while installing STOP')
    result = {'schemaVersion': 1, 'kind': KIND + '-source-retirement', 'state': 'SOURCE_FENCED_FOR_TEARDOWN',
        'datasetId': manifest['datasetId'], 'runId': manifest['runId'], 'source': core['source'], 'snapshotIdentifier': core['snapshotIdentifier'],
        'provenance': receipt['objects']['snapshot-provenance.json'], 'sourceDeletionAdmission': receipt['objects']['source-deletion-admission.json'],
        'hostInstanceId': context['hostInstanceId'], 'stopCommandId': command_id, 'manifestSha256': context['manifest']['sha256'],
        'sourceReactivationForbidden': True, 'sourceDeletionExecuted': False, 'lease': context['lease'], 'recordedAt': snapshot.now()}
    guard(force=True)
    validate_retirement_admission(admission, provenance, core, manifest)
    return publish(aws, f'data-bootstrap/{context["runId"]}/b-source-retirement.json', result, root, 'source-retirement.json')


def capture_restore_event(aws, provenance, admission, root, guard):
    snapshot.verify(aws, provenance)
    require(admission.get('kind') == snapshot.KIND + '-restore-admission'
            and admission.get('state') == 'SOURCE_ABSENT_TARGET_ABSENT' and admission.get('snapshotArn') == provenance['snapshot']['arn']
            and admission.get('source') == provenance['contract']['source'],
            'The target must have its exact source-absence admission')
    target = admission['targetIdentifier']
    rows = aws.call('rds', 'describe-db-instances')['DBInstances']
    require(len(rows) == 1 and rows[0]['DBInstanceIdentifier'] == target
            and rows[0]['DbiResourceId'] != provenance['contract']['source']['resourceId'], 'The restored target is not the sole new RDS')
    created = snapshot.instant(rows[0]['InstanceCreateTime'])
    deadline = time.monotonic() + 600
    while True:
        guard(force=True)
        events = aws.call('cloudtrail', 'lookup-events', '--lookup-attributes',
            canonical([{'AttributeKey': 'EventName', 'AttributeValue': 'RestoreDBInstanceFromDBSnapshot'}]),
            '--start-time', admission['recordedAt'])['Events']
        selected = {}
        for outer in events:
            event = json.loads(outer['CloudTrailEvent']); request = event.get('requestParameters') or {}
            if (event.get('eventName') == 'RestoreDBInstanceFromDBSnapshot' and event.get('eventSource') == 'rds.amazonaws.com'
                    and event.get('awsRegion') == REGION and event.get('recipientAccountId') == ACCOUNT and not event.get('errorCode')
                    and request.get('dBInstanceIdentifier') == target
                    and request.get('dBSnapshotIdentifier') in {provenance['snapshot']['identifier'], provenance['snapshot']['arn']}):
                requested = snapshot.instant(event['eventTime'])
                if snapshot.instant(admission['recordedAt']) <= requested <= created + dt.timedelta(minutes=5) and created - requested <= dt.timedelta(hours=2):
                    projection = {key: event[key] for key in ('eventName', 'eventSource', 'awsRegion', 'recipientAccountId', 'eventID', 'eventTime')}
                    projection['requestParameters'] = {key: request[key] for key in ('dBInstanceIdentifier', 'dBSnapshotIdentifier')}
                    projection['errorCode'] = None
                    host.cloudtrail_projection(projection)
                    selected[event['eventID']] = projection
        require(len(selected) <= 1, 'The actual target restore event is ambiguous')
        if selected:
            event = next(iter(selected.values()))
            key = f'data-bootstrap/{admission["lease"]["runId"]}/b-snapshot-restore-event-{admission["lease"]["fencingToken"]}.json'
            return publish(aws, key, event, root, 'restore-event.json')
        require(time.monotonic() < deadline, 'CloudTrail has not yet exposed the exact restore event; target is retained for retry')
        time.sleep(15)


def cleanup_access(aws, run_id, dataset_id, resource_fence, root, guard):
    role = 'airbob-lab-host-' + run_id + '-debezium'
    names = aws.call('iam', 'list-role-policies', '--role-name', role)['PolicyNames']
    removed = []
    for name in names:
        match = re.fullmatch(r'airbob-b-snapshot-([1-9][0-9]*)', name)
        if not match:
            continue
        intent, _ = current_object(aws, f'data-bootstrap/{run_id}/b-snapshot-policy-intent-{match.group(1)}.json', root, 'cleanup-intent-' + match.group(1) + '.json')
        policy = policy_document(aws.call('iam', 'get-role-policy', '--role-name', role, '--policy-name', name)['PolicyDocument'])
        require(intent.get('kind') == KIND + '-access-intent' and intent.get('runId') == run_id and intent.get('datasetId') == dataset_id
                and intent.get('roleName') == role and intent.get('policyName') == name
                and intent.get('resourceFencingToken') == resource_fence and intent.get('policyCanonicalSha256') == restore.canonical_sha(policy)
                and all(item.get('Action') == 's3:GetObjectVersion' and item.get('Effect') == 'Allow' for item in policy['Statement']),
                'Only this run own unchanged temporary read policy may be removed')
        deadline = intent.get('deadlineEpoch')
        require(type(deadline) is int and all(snapshot.instant(item['Condition']['DateLessThan']['aws:CurrentTime']).timestamp() == deadline
                for item in policy['Statement']), 'The original immutable policy deadline differs')
        if time.time() < deadline:
            dispatch_key = f'data-bootstrap/{run_id}/b-snapshot-policy-dispatch-{match.group(1)}.json'
            require(intent.get('dispatchEvidenceKey') == dispatch_key, 'Live policy lacks exact host dispatch binding; retain until its deadline')
            dispatched, _ = current_object(aws, dispatch_key, root, 'cleanup-dispatch-' + match.group(1) + '.json')
            require(dispatched.get('kind') == KIND + '-dispatch' and dispatched.get('runId') == run_id
                    and dispatched.get('policyName') == name and dispatched.get('policyCanonicalSha256') == intent['policyCanonicalSha256']
                    and dispatched.get('hostInstanceId') == intent.get('hostInstanceId') and dispatched.get('deadlineEpoch') == deadline,
                    'Host dispatch differs from the original policy intent')
            require(status(aws, dispatched['commandId'], dispatched['hostInstanceId']) in TERMINAL_HOST_STATES,
                    'Exact host execution is not terminal and the original policy deadline has not expired')
        guard(force=True); aws.call('iam', 'delete-role-policy', '--role-name', role, '--policy-name', name); removed.append(name)
    return removed


def verify_retirement(aws, run_id, dataset_id, root):
    proof, ref = current_object(aws, f'data-bootstrap/{run_id}/b-source-retirement.json', root, 'retirement.json')
    require(proof.get('kind') == KIND + '-source-retirement' and proof.get('state') == 'SOURCE_FENCED_FOR_TEARDOWN'
            and proof.get('runId') == run_id and proof.get('datasetId') == dataset_id and proof.get('sourceReactivationForbidden') is True
            and proof.get('sourceDeletionExecuted') is False, 'The immutable source retirement receipt differs')
    provenance = fetch(aws, proof['provenance'], root, 'retired-provenance.json')
    core = snapshot.verify(aws, provenance)
    require(core['source'] == proof['source'] and core['datasetId'] == dataset_id
            and core['source']['identifier'] == 'airbob-' + run_id, 'Retired source and snapshot identity differ')
    rows = aws.call('rds', 'describe-db-instances')['DBInstances']
    require(not rows or (len(rows) == 1 and rows[0]['DBInstanceIdentifier'] == 'airbob-' + run_id
            and rows[0]['DbiResourceId'] == core['source']['resourceId']), 'Only the exact retired source RDS may be deleted')
    groups = aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
    require(all(item['DesiredCapacity'] == item['MinSize'] == 0 and not item['Instances'] for item in groups
                if item['AutoScalingGroupName'].startswith('airbob-' + run_id + '-')), 'A retired source application was restarted')
    return ref


def validate_host_receipt(aws, receipt, manifest, context, root):
    require(receipt.get('state') == 'HOST_OPERATION_COMPLETE' and receipt.get('kind') == host.KIND + '-receipt'
            and all(receipt.get(key) == context[key] for key in ('runId', 'datasetId', 'operationId', 'operation', 'toolSources', 'hostInstanceId'))
            and receipt.get('manifestSha256') == context['manifest']['sha256'] and receipt.get('persistentAwsResourcesMutatedByHost') is False
            and receipt.get('sqlImportExecuted') is False and receipt.get('applicationLeftRunning') is False
            and receipt.get('deploymentReady') is False, 'Host completion evidence differs')
    identity = receipt.get('targetIdentity', {})
    require(all(identity.get(key) == context['rds'][key] for key in ('identifier', 'resourceId', 'endpoint')),
            'Host receipt target differs from the retained RDS')
    host.identity(identity, context['runId'])
    for ref in receipt['objects'].values():
        host.reference(ref, context['evidencePrefix'], maximum=4 * 1024**2)
    if manifest['operation'] in ('create', 'retire'):
        require(identity == manifest['source']['rds'], 'Host source MySQL UUID differs')
        require({'snapshot-provenance.json', 'source-deletion-admission.json'} <= set(receipt['objects']),
                'Snapshot source completion lacks provenance and fresh deletion admission')
    else:
        require({'data-only-preparation.json', 'snapshot-operation.json', 'prepared-fingerprint.json'} <= set(receipt['objects']),
                'Snapshot target completion lacks actual preparation evidence')
        refs = receipt['objects']
        proof = fetch(aws, refs['data-only-preparation.json'], root, 'target-preparation-proof.json')
        require(proof.get('kind') == 'global-growth-b-aws-data-only-preparation' and proof.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED'
                and proof.get('sourceMode') == 'verified-global-b-snapshot' and proof.get('datasetId') == context['datasetId']
                and proof.get('runId') == context['runId'] and proof.get('rdsResourceId') == identity['resourceId']
                and proof.get('serverUuid') == identity['serverUuid'] and proof.get('rdsEngineVersion') == '8.4.11' and proof.get('flywayVersion') == 28
                and proof.get('snapshotProvenanceSha256') == manifest['evidence']['provenance']['sha256']
                and proof.get('restoreReceiptSha256') == refs['snapshot-operation.json']['sha256']
                and proof.get('preparation', {}).get('preparedFingerprintSha256') == refs['prepared-fingerprint.json']['sha256']
                and proof.get('snapshotRestoreEvidence', {}).get('evidenceSource') == 'controller-pinned-cloudtrail-event'
                and proof.get('applicationLeftRunning') is False and proof.get('deploymentReady') is False,
                'Actual snapshot-only target preparation differs from its immutable evidence')
    return receipt


def run(manifest, context, root, resource_fence, approved_identifier):
    root = Path(root); root.mkdir(mode=0o700, parents=True)
    host.validate_context(context, manifest, context['manifest']['sha256'])
    aws = restore.Aws(); guard = restore.Lease(aws, context['lease']); guard(force=True)
    role = validate_target(aws, context, manifest, resource_fence)
    refs = collect_read_references(aws, manifest, context['manifest'], root)
    policy = read_policy(refs, context['deadlineEpoch'])
    policy_name = 'airbob-b-snapshot-' + str(context['lease']['fencingToken'])
    names = aws.call('iam', 'list-role-policies', '--role-name', role)['PolicyNames']
    require(policy_name not in names, 'An existing temporary operation policy cannot be replaced')
    total = len(canonical(policy))
    for name in names:
        total += len(canonical(policy_document(aws.call('iam', 'get-role-policy', '--role-name', role, '--policy-name', name)['PolicyDocument'])))
    require(total <= 10240, 'Exact temporary read policy exceeds the retained role inline-policy quota')
    intent = {'schemaVersion': 1, 'kind': KIND + '-access-intent', 'runId': context['runId'], 'datasetId': context['datasetId'],
        'roleName': role, 'policyName': policy_name, 'policyCanonicalSha256': restore.canonical_sha(policy),
        'resourceFencingToken': resource_fence, 'manifest': context['manifest'], 'deadlineEpoch': context['deadlineEpoch'], 'lease': context['lease'],
        'hostInstanceId': context['hostInstanceId'], 'dispatchEvidenceKey': f'data-bootstrap/{context["runId"]}/b-snapshot-policy-dispatch-{context["lease"]["fencingToken"]}.json'}
    publish(aws, f'data-bootstrap/{context["runId"]}/b-snapshot-policy-intent-{context["lease"]["fencingToken"]}.json', intent, root, 'access-intent.json')
    write(root / 'read-policy.json', policy)
    command_id = None; attached = False; attachment_attempted = False; completed = False; dispatch_attempted = False; primary_failure_type = None
    try:
        guard(force=True)
        attachment_attempted = True
        aws.call('iam', 'put-role-policy', '--role-name', role, '--policy-name', policy_name, '--policy-document', 'file://' + str(root / 'read-policy.json'))
        attached = True
        dispatch_attempted = True
        command_id = dispatch(aws, context, root)
        publish(aws, intent['dispatchEvidenceKey'], {'schemaVersion': 1, 'kind': KIND + '-dispatch', 'runId': context['runId'],
            'policyName': policy_name, 'policyCanonicalSha256': intent['policyCanonicalSha256'], 'commandId': command_id,
            'hostInstanceId': context['hostInstanceId'], 'deadlineEpoch': context['deadlineEpoch']}, root, 'dispatch-receipt.json')
        created = None
        while True:
            guard(force=True); require(time.time() < context['deadlineEpoch'], 'Snapshot controller command deadline exceeded')
            state = status(aws, command_id, context['hostInstanceId'])
            require(state not in ('Failed', 'Cancelled', 'TimedOut', 'Cancelling'), 'Snapshot host operation failed; all data resources are retained')
            if manifest['operation'] == 'create' and created is None:
                rows = aws.call('s3api', 'list-objects-v2', '--bucket', EVIDENCE, '--prefix', context['evidencePrefix'] + 'read-lock-acks/').get('Contents', [])
                if rows:
                    created = create_exact_snapshot(aws, manifest, context, root, guard, command_id, approved_identifier)
            if state == 'Success':
                break
            time.sleep(5)
        receipt, receipt_ref = current_object(aws, context['evidencePrefix'] + 'host-receipt.json', root, 'host-receipt.json')
        validate_host_receipt(aws, receipt, manifest, context, root)
        retirement = retire_source(aws, manifest, context, receipt, root, guard) if manifest['operation'] in ('create', 'retire') else None
        result = {'schemaVersion': 1, 'kind': KIND + '-receipt', 'state': 'SNAPSHOT_CONTROLLER_OPERATION_COMPLETE',
            'runId': context['runId'], 'datasetId': context['datasetId'], 'operation': manifest['operation'], 'operationId': manifest['operationId'],
            'hostReceipt': receipt_ref, 'createdSnapshot': created, 'sourceRetirement': retirement, 'objects': receipt['objects'],
            'manifest': context['manifest'], 'lease': context['lease'], 'sourceDeletionExecuted': False, 'deploymentReady': False}
        completed = True
    except BaseException as error:
        primary_failure_type = type(error).__name__
        raise
    finally:
        cleanup = finish_operation_access(aws, context, role, policy_name, intent['policyCanonicalSha256'], command_id,
                                          dispatch_attempted, attached, attachment_attempted, completed, primary_failure_type, root)
    require(not cleanup['policyRetained'] and 'evidenceFailureCode' not in cleanup,
            'Snapshot host finished but access cleanup requires review; data resources are retained')
    result['accessCleanup'] = cleanup
    publish(aws, context['evidencePrefix'] + 'controller-receipt.json', result, root, 'controller-receipt.json')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['package-tools', 'validate', 'run', 'capture-event', 'cleanup-access', 'verify-retirement'])
    parser.add_argument('--manifest', type=Path); parser.add_argument('--sha256')
    parser.add_argument('--dataset-id', required=True); parser.add_argument('--run-id', required=True); parser.add_argument('--operation-id')
    parser.add_argument('--context', type=Path); parser.add_argument('--output', type=Path)
    parser.add_argument('--admission', type=Path); parser.add_argument('--lease', type=Path)
    parser.add_argument('--resource-fence', type=int); parser.add_argument('--approved-snapshot', default='')
    args = parser.parse_args(); os.umask(0o077)
    host.coordinates(args.dataset_id, args.run_id, args.operation_id or 'controller')
    if args.mode == 'package-tools':
        require(args.output and args.operation_id, 'Explicit output and operation ID are required for packaging')
        print(json.dumps(package_tools(args.output, args.dataset_id, args.run_id, args.operation_id))); return
    if args.mode in ('cleanup-access', 'verify-retirement'):
        require(args.output and args.lease and type(args.resource_fence) is int and args.resource_fence > 0,
                'A private output, resource fence and exact current lease are required')
        args.output.mkdir(parents=True, mode=0o700)
        lease = read(args.lease)
        require(lease.get('runId') == args.run_id and lease.get('command') in {'up', 'down'}, 'Own active up/down lease required')
        snapshot.validate_lease(lease | {'command': 'up'}, 'airbob-' + args.run_id)
        require(lease['lockName'] == 'airbob-performance-lab', 'Only the global existing lease is accepted')
        aws = restore.Aws(); guard = restore.Lease(aws, lease); guard(force=True)
        result = cleanup_access(aws, args.run_id, args.dataset_id, args.resource_fence, args.output, guard) if args.mode == 'cleanup-access' else verify_retirement(aws, args.run_id, args.dataset_id, args.output)
        print(json.dumps({'state': args.mode.upper().replace('-', '_') + '_VERIFIED', 'result': result})); return
    if args.mode == 'capture-event':
        require(args.admission and args.manifest and args.output and sha(args.manifest) == args.sha256, 'Exact restore admission/provenance required')
        args.output.mkdir(parents=True, mode=0o700)
        admission = read(args.admission)
        require(admission.get('provenanceSha256') == args.sha256 and admission.get('targetIdentifier') == 'airbob-' + args.run_id,
                'Restore event capture must use this exact target and provenance admission')
        snapshot.validate_lease(admission['lease'], 'airbob-' + args.run_id)
        aws = restore.Aws(); guard = restore.Lease(aws, admission['lease']); guard(force=True)
        result = capture_restore_event(aws, read(args.manifest), admission, args.output, guard)
        write(args.output / 'restore-event-ref.json', result); print(json.dumps(result)); return
    require(args.manifest and args.sha256 and sha(args.manifest) == args.sha256, 'Reviewed manifest SHA differs')
    value = validate_manifest(read(args.manifest), args.sha256, args.dataset_id, args.run_id, args.operation_id)
    if args.mode == 'validate':
        print(json.dumps({'state': 'SNAPSHOT_OPERATION_MANIFEST_VERIFIED', 'operation': value['operation']})); return
    require(args.context and args.output and args.resource_fence, 'Controller context/output/resource fence are required')
    def stopped(signum, frame):
        raise RuntimeError('Snapshot controller interrupted')
    for name in (signal.SIGTERM, signal.SIGINT): signal.signal(name, stopped)
    result = run(value, read(args.context), args.output, args.resource_fence, args.approved_snapshot)
    print(json.dumps({'state': result['state'], 'operation': result['operation'], 'runId': result['runId']}))


if __name__ == '__main__':
    main()
