#!/usr/bin/env python3
"""Verify normal reads on the actual prepared B snapshot target.

This family admits snapshot preparation, not the original dump/source-R4 gate.
It never changes ASG capacity, starts or stops containers, runs CDC PATCH/reset,
imports SQL, or creates cloud resources. Login/logout are the only API writes.
"""
from __future__ import annotations
import argparse
import base64
import contextlib
import copy
import datetime as dt
import json
import os
from pathlib import Path
import tempfile
import time

import growth_b_aws_restore as restore
import growth_b_cdc as cdc_host
import growth_b_cdc_core as cdc
import growth_b_cdc_controller as controller
import growth_b_cdc_supervisor as transport
import growth_b_contract as contract
import growth_b_runtime as runtime_gate
import growth_b_service as service
import growth_b_service_verify as reads
import growth_b_snapshot as snapshot
import growth_b_snapshot_controller as snapshot_controller
import growth_b_snapshot_host as snapshot_host

core, need, Failed = cdc.core, cdc.need, cdc.Failed
sha, encoded = core.digest, core.encoded
KIND = 'global-b-aws-snapshot-target-service-operation'
CONTEXT_KIND = 'global-b-aws-snapshot-target-service-context'
HOST_KIND = 'global-b-aws-snapshot-target-service-host'
RECEIPT_KIND = 'global-growth-b-aws-snapshot-target-service-verification'
COMPLETE = 'SNAPSHOT_TARGET_SERVICE_READS_AND_WARMUP_VERIFIED'
RECOVERY_KIND = 'global-b-aws-snapshot-target-service-recovery'
DOCUMENTS = {'serviceManifest', 'readiness', 'searchRestore', 'snapshotManifest', 'hostReceipt', 'targetPreflight', 'preparation',
             'snapshotOperation', 'preparedFingerprint', 'provenance', 'admission', 'restoreEvent'}
PUBLIC_FILES = {'snapshot-target-service-verification.json', 'representative-http-reads.json', 'warmup-receipt.json',
                'media-availability.json', 'host-runtime-qualification.json'}
MAX_JOB = 512 * 1024


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def source_files():
    value = transport.source_files()
    for name in ('growth_b_snapshot_service_verify.py', 'growth_b_snapshot_host.py', 'growth_b_snapshot_controller.py', 'growth_b_rds_class.py'):
        value['infra/aws/scripts/' + name] = contract.sha(Path(__file__).with_name(name))
    return value


def source_archive():
    return transport.source_archive(source_files())


def prefix(operation):
    return f"data-bootstrap/{operation['runId']}/{operation['datasetId']}-snapshot-service/{operation['operationId']}/"


def exact_ref(value, expected_prefix):
    snapshot_host.reference(value, expected_prefix, maximum=2 * 1024**2)
    return value


def operation_binding(value):
    return sha(encoded({k: v for k, v in value.items() if k != 'resume'}))


def validate_operation(value, *, check_sources=True):
    expected = 'schemaVersion kind stage operationId runId datasetId serviceRelease executionCommit sourceArchiveSha256 manifest readiness targetPreparation'
    cdc.fields(value, expected + (' resume' if 'resume' in value else ''), 'SNAPSHOT_SERVICE_OPERATION_FIELDS_DIFFER')
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['stage'] == 'snapshot-verify', 'EXPLICIT_SNAPSHOT_TARGET_SERVICE_REQUIRED')
    snapshot_host.coordinates(value['datasetId'], value['runId'], value['operationId'])
    need(cdc.match(r'[a-z0-9][a-z0-9-]{2,47}', value['serviceRelease']) and cdc.match(r'[0-9a-f]{40}', value['executionCommit'])
         and cdc.match(cdc.HASH, value['sourceArchiveSha256']), 'SNAPSHOT_SERVICE_COORDINATES_INVALID')
    selected = f"datasets/{value['datasetId']}-aws-service/{value['serviceRelease']}/"
    exact_ref(value['manifest'], selected)
    need(value['manifest']['key'] == selected + 'aws-service.json', 'EXACT_TARGET_SERVICE_MANIFEST_REQUIRED')
    exact_ref(value['readiness'], f"data-bootstrap/{value['runId']}/")
    need(value['readiness']['key'] == f"data-bootstrap/{value['runId']}/{value['datasetId']}-service-{value['serviceRelease']}.json",
         'EXACT_TARGET_SERVICE_READINESS_REQUIRED')
    proof = value['targetPreparation']; cdc.fields(proof, 'manifest hostReceipt')
    exact_ref(proof['manifest'], f"datasets/{value['datasetId']}-aws-snapshots/operations/{value['runId']}/")
    bits = proof['manifest']['key'].split('/')
    need(len(bits) == 6 and bits[-1] == 'manifest-' + proof['manifest']['sha256'] + '.json', 'EXACT_TARGET_PREPARATION_MANIFEST_REQUIRED')
    snapshot_host.coordinates(value['datasetId'], value['runId'], bits[-2])
    target_prefix = snapshot_host.evidence_prefix(value['datasetId'], value['runId'], bits[-2])
    exact_ref(proof['hostReceipt'], target_prefix)
    need(proof['hostReceipt']['key'] == target_prefix + 'host-receipt.json', 'EXACT_TARGET_HOST_RECEIPT_REQUIRED')
    if 'resume' in value:
        cdc.fields(value['resume'], 'recovery')
        ref = exact_ref(value['resume']['recovery'], prefix(value))
        need(ref['key'] == prefix(value) + 'recovery-' + ref['sha256'] + '.json', 'EXACT_TARGET_RECOVERY_KEY_REQUIRED')
    if check_sources:
        need(source_archive()[1]['sha256'] == value['sourceArchiveSha256'], 'REVIEWED_TARGET_SERVICE_SOURCES_CHANGED')
    return value


def validate_context(value, operation, *, clock=time.time):
    cdc.fields(value, 'schemaVersion kind executionCommit approvedExecutionDeadlineEpoch controllerDeadlineEpoch operator lease phase2 phase3 phase4 serviceState')
    need(value['schemaVersion'] == 1 and value['kind'] == CONTEXT_KIND and value['executionCommit'] == operation['executionCommit'],
         'EXPLICIT_TARGET_SERVICE_CONTEXT_REQUIRED')
    original, phase2, phase3, phase4 = (value[k] for k in ('operator', 'phase2', 'phase3', 'phase4'))
    need(original.get('schemaVersion') == 2 and original.get('globalBSnapshotRestoreOnly') is True
         and original.get('globalBPrepareOnly', False) is False and original.get('databaseBootstrap') == 'snapshot'
         and original.get('runId') == operation['runId'] and original.get('datasetRelease') == operation['datasetId']
         and original.get('mode') == 'performance' and original.get('dnsMode') == 'direct-only'
         and original.get('loadGeneratorEnabled') is False and original.get('cacheEnabled') is False
         and original.get('rdsEngineVersion') == '8.4.11', 'ACTUAL_SNAPSHOT_TARGET_RUN_REQUIRED')
    expiry = int(original['expiresAt'])
    need(core.integer(original.get('fencingToken'), 1) and core.integer(value['approvedExecutionDeadlineEpoch'], 1)
         and original.get('approvedExecutionDeadlineEpoch') == value['approvedExecutionDeadlineEpoch']
         and expiry <= value['approvedExecutionDeadlineEpoch']
         and clock() < value['controllerDeadlineEpoch'] <= min(expiry, value['approvedExecutionDeadlineEpoch'], clock() + 18000),
         'ORIGINAL_TARGET_DEADLINE_REQUIRED')
    snapshot.validate_lease(value['lease'], 'airbob-' + operation['runId'])
    need(value['lease']['command'] == 'up', 'EXACT_TARGET_UP_LEASE_REQUIRED')
    need(phase2['run_id'] == operation['runId'] and phase2['fencing_token'] == original['fencingToken']
         and {'debezium', 'kafka', 'elasticsearch'} <= set(phase2['services']), 'TARGET_SERVICE_HOSTS_REQUIRED')
    need(phase3['dataset_release'] == operation['datasetId'] and phase3['rds_instance_id'] == 'airbob-' + operation['runId']
         and phase3['rds_engine_version'] == '8.4.11', 'TARGET_RDS_TERRAFORM_IDENTITY_REQUIRED')
    need(phase4['app_enabled'] is True and phase4['capacity'] == {'min': 1, 'desired': 1, 'max': 1}
         and phase4['accommodation_detail_cache_enabled'] is False and phase4['load_generator_enabled'] is False
         and phase4['auto_scaling_group_name'] == 'airbob-' + operation['runId'] + '-app', 'EXACT_NORMAL_TARGET_APP_REQUIRED')
    state = value['serviceState']
    need(state['selected'] is True and all(state[k] == operation['manifest'][v] for k, v in
         (('manifest_key', 'key'), ('manifest_version_id', 'versionId'), ('manifest_sha256', 'sha256')))
         and state['readiness_receipt'] == {'key': operation['readiness']['key'], 'version_id': operation['readiness']['versionId'],
             'sha256': operation['readiness']['sha256'], 'bytes': operation['readiness']['bytes']}, 'TARGET_SELECTED_SERVICE_REFS_CHANGED')
    return value


def unpack_document(value):
    cdc.fields(value, 'reference base64')
    raw = base64.b64decode(value['base64'], validate=True)
    ref = exact_ref(value['reference'], '')
    need(len(raw) == ref['bytes'] and sha(raw) == ref['sha256'], 'TARGET_PROOF_BYTES_CHANGED')
    result = core.parse(raw); snapshot_host.public_json(result)
    return result, raw


class VerifiedEvidence:
    """Offline adapter for the existing exact host-receipt validator only."""
    def __init__(self, documents):
        self.values = {(snapshot_controller.bucket(v['reference']), v['reference']['key'], v['reference']['versionId']): v
                       for v in documents.values()}

    def call(self, *args):
        need(args[:2] == ('s3api', 'get-object'), 'OFFLINE_PROOF_ADAPTER_CANNOT_CALL_AWS')
        key = tuple(args[args.index(flag) + 1] for flag in ('--bucket', '--key', '--version-id'))
        value = self.values[key]; _, raw = unpack_document(value)
        core.write_new(Path(args[-1]), raw)
        return {'VersionId': key[-1]}


def validate_proofs(operation, context, documents):
    need(set(documents) == DOCUMENTS, 'EXACT_TARGET_PROOF_INVENTORY_REQUIRED')
    values = {name: unpack_document(value)[0] for name, value in documents.items()}
    refs = {name: value['reference'] for name, value in documents.items()}
    need(refs['serviceManifest'] == operation['manifest'] and refs['readiness'] == operation['readiness']
         and refs['snapshotManifest'] == operation['targetPreparation']['manifest']
         and refs['hostReceipt'] == operation['targetPreparation']['hostReceipt'], 'TARGET_TOP_LEVEL_REFERENCES_CHANGED')
    manifest, ready, selected, host = (values[k] for k in ('serviceManifest', 'readiness', 'snapshotManifest', 'hostReceipt'))
    need(context['operator'].get('bundleCommit') == manifest['application']['mainCommit']
         and context['operator'].get('appImageReference') == manifest['application']['image'], 'ORIGINAL_TARGET_APPLICATION_CHANGED')
    service.validate_manifest(manifest, operation['datasetId'], operation['runId'], operation['serviceRelease'],
        {name: service.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS})
    service.validate_readiness(ready, manifest, operation['manifest']['sha256'])
    transport.validate_runtime_revision(context, operation, manifest, ready)
    restored = values['searchRestore']
    need(refs['searchRestore'] == manifest['search']['restoreReceipt'] == ready['searchRestoreReceipt']
         and restored.get('state') == 'SEARCH_RESTORED_AND_ACTIVATED' and restored['datasetId'] == operation['datasetId']
         and restored['mysql']['serverUuid'] == manifest['rds']['serverUuid']
         and restored['snapshotRelease'] == manifest['search']['snapshotRelease']
         and restored['fullDocumentFingerprint'] == manifest['search']['documentFingerprint'] == ready['searchFingerprint']
         and restored['nativeTransport']['manifestSha256'] == manifest['search']['transport']['sha256']
         and restored['nativeTransport']['exactVersionBytesVerified'] is True and restored['nativeTransport']['sourceSealsChanged'] is False
         and restored['repositoryReadOnly'] is True and restored['nativeInventoryUnchanged'] is True
         and restored['allDocumentSourceFieldsEqual'] is True and restored['elasticsearch']['image'] == manifest['search']['image']
         and restored['restoredIndex'] == ready['restoredIndex'], 'ACTUAL_TARGET_NATIVE_SEARCH_RESTORE_REQUIRED')
    snapshot_controller.validate_manifest(selected, refs['snapshotManifest']['sha256'], operation['datasetId'], operation['runId'], selected['operationId'])
    need(selected['operation'] == 'prepare' and host['operation'] == 'prepare'
         and selected['application'] == {k: manifest['application'][k] for k in ('mainCommit', 'image')}, 'SNAPSHOT_PREPARE_FAMILY_REQUIRED')
    need(host['hostInstanceId'] == context['phase2']['services']['debezium']
         and host['targetIdentity']['resourceId'] == context['phase3']['rds_resource_id'] == manifest['rds']['resourceId']
         and host['targetIdentity']['serverUuid'] == manifest['rds']['serverUuid']
         and host['targetIdentity']['endpoint'] == context['phase3']['rds_endpoint'], 'PREPARED_TARGET_RESOURCE_CHANGED')
    reference_names = {'preparation': 'data-only-preparation.json', 'snapshotOperation': 'snapshot-operation.json',
                       'preparedFingerprint': 'prepared-fingerprint.json', 'targetPreflight': 'preflight.json'}
    need(all(refs[name] == host['objects'][key] for name, key in reference_names.items())
         and all(refs[name] == selected['evidence'][name] for name in ('provenance', 'admission', 'restoreEvent')), 'TARGET_TRANSITIVE_REFERENCES_CHANGED')
    previous_context = {k: host[k] for k in ('runId', 'datasetId', 'operationId', 'operation', 'toolSources', 'hostInstanceId')}
    previous_context.update(manifest=refs['snapshotManifest'], rds=host['targetIdentity'],
        evidencePrefix=snapshot_host.evidence_prefix(operation['datasetId'], operation['runId'], selected['operationId']))
    with tempfile.TemporaryDirectory() as directory:
        snapshot_controller.validate_host_receipt(VerifiedEvidence(documents), host, selected, previous_context, directory)
    provenance = snapshot.validate_provenance(values['provenance'])
    need(provenance['datasetId'] == operation['datasetId'] and provenance['application'] == manifest['application']
         and provenance['snapshotIdentifier'] == selected['snapshotIdentifier'], 'TARGET_SNAPSHOT_APPLICATION_OR_DATASET_CHANGED')
    expected_provenance = context['operator']['globalBSnapshotProvenance']
    need(refs['provenance'] == {'key': expected_provenance['key'], 'versionId': expected_provenance['version_id'],
         'sha256': expected_provenance['sha256'], 'bytes': expected_provenance['bytes']}, 'ACTUALLY_RESTORED_PROVENANCE_CHANGED')
    proof, prepared, preflight = (values[k] for k in ('snapshotOperation', 'preparation', 'targetPreflight'))
    need(proof.get('schemaVersion') == 1 and proof.get('kind') == snapshot.KIND + '-operation'
         and proof.get('operation') == 'prepare' and proof.get('state') == snapshot.PREPARED
         and proof.get('toolIdentity') == snapshot.tool_identity() and proof.get('configSha256') == preflight['configSha256']
         and proof.get('targetIdentity') == host['targetIdentity'] and proof.get('datasetId') == operation['datasetId']
         and proof.get('provenanceSha256') == refs['provenance']['sha256']
         and proof.get('envelopeSha256') == provenance['publication']['envelopeSha256']
         and proof.get('admissionSha256') == refs['admission']['sha256'] and proof.get('restoreEventSha256') == refs['restoreEvent']['sha256']
         and proof.get('snapshotWholeBaselineVerified') is True and proof.get('sqlImportSkipped') is True
         and proof.get('databaseRecreatedInThisRun') is False and proof.get('actualRestoreVerified') is True
         and proof.get('applicationLeftRunning') is False and proof.get('deploymentReady') is False
         and proof.get('snapshotBaselineCanonicalSha256') == provenance['preparedFingerprintCanonicalSha256'],
         'ACTUAL_SNAPSHOT_BASELINE_AND_PREPARATION_REQUIRED')
    need(preflight.get('state') == 'RESTORED_TARGET_READ_ONLY_PREFLIGHT' and preflight.get('operation') == 'prepare'
         and refs['targetPreflight']['sha256'] == host['preflightSha256']
         and all(preflight.get(k) == proof[k] for k in ('targetIdentity', 'provenanceSha256', 'admissionSha256',
             'restoreEventSha256', 'credentialBindingSha256', 'toolIdentity', 'datasetId', 'envelopeSha256'))
         and preflight['actualRestore'] == proof['actualRestore'], 'TARGET_PREFLIGHT_EXECUTION_LINEAGE_CHANGED')
    contract.validate_fingerprint(values['preparedFingerprint'], require_sealed=False)
    restore.validate_prepared_changes(provenance['sealedFingerprint'], values['preparedFingerprint'])
    need(proof['preparedFingerprintSha256'] == refs['preparedFingerprint']['sha256']
         and proof['preparedFingerprintCanonicalSha256'] == snapshot.canonical_sha(values['preparedFingerprint'])
         and proof['preparation'] == prepared['preparation']
         and prepared['preparation'].get('passed') is True and prepared['preparation'].get('readinessVerified') is True
         and prepared['preparation'].get('accountLogins', {}).get('passed') is True
         and prepared['preparation'].get('currentInventory', {}).get('everyHorizonContiguous') is True,
         'ACTUAL_TARGET_PREPARATION_RESULT_REQUIRED')
    event, admission = values['restoreEvent'], values['admission']
    snapshot_host.cloudtrail_projection(event)
    need(event['eventName'] == 'RestoreDBInstanceFromDBSnapshot' and event['eventSource'] == 'rds.amazonaws.com'
         and event['awsRegion'] == cdc.REGION and event['recipientAccountId'] == cdc.ACCOUNT
         and event['requestParameters']['dBInstanceIdentifier'] == host['targetIdentity']['identifier']
         and not event.get('errorCode')
         and event['requestParameters']['dBSnapshotIdentifier'] in (values['provenance']['snapshot']['arn'], selected['snapshotIdentifier'])
         and prepared['snapshotRestoreEvidence'] == proof['actualRestore']
         and prepared['snapshotRestoreEvidence']['eventSha256'] == snapshot.canonical_sha(event)
         and prepared['snapshotRestoreEvidence']['eventId'] == event['eventID']
         and admission['kind'] == snapshot.KIND + '-restore-admission' and admission['state'] == 'SOURCE_ABSENT_TARGET_ABSENT'
         and admission['maximumSimultaneousBusinessDatabases'] == 1 and admission['restoreExecuted'] is False
         and admission['source'] == provenance['source']
         and admission['targetIdentifier'] == host['targetIdentity']['identifier']
         and admission['provenanceSha256'] == refs['provenance']['sha256']
         and host['targetIdentity']['resourceId'] != provenance['source']['resourceId']
         and host['targetIdentity']['serverUuid'] != provenance['source']['serverUuid'], 'EXACT_SEQUENTIAL_RESTORE_EVENT_REQUIRED')
    need(host['restoreConfigSha256'] == manifest['preparation']['restoreConfigSha256']
         and refs['preparation'] == manifest['preparation']['receipt'], 'TARGET_SERVICE_PREPARATION_LINEAGE_CHANGED')
    service.validate_preparation(prepared, manifest, 'snapshot', refs['provenance']['sha256'])
    return values


class ReadAws(transport.BoundedAws):
    ALLOWED = transport.BoundedAws.ALLOWED - {('autoscaling', 'update-auto-scaling-group')}


def require_tags(rows, operation, context, role=None):
    tags = cdc_host.tags(rows)
    cdc_host.require_tags(tags, {'runId': operation['runId'], 'resourceFencingToken': context['operator']['fencingToken'],
                              'expiresAt': int(context['operator']['expiresAt'])})
    if role: need(tags.get('Service') == role, 'TARGET_HOST_SERVICE_CHANGED')
    return tags


def validate_observation(value, pin, revision, app_runtime):
    cdc.fields(value, 'instanceId privateIp containerId imageId image startedAt finishedAt running normalProfile readiness appJarSha256 runtimeRevision')
    need(all(value[k] == v for k, v in pin.items()) and value['running'] is True and value['normalProfile'] == 'aws'
         and value['readiness'] is True and value['appJarSha256'] == app_runtime['imageJarSha256']
         and value['runtimeRevision'] == revision, 'ACTUAL_NORMAL_TARGET_RUNTIME_REQUIRED')
    return value


def app_attestation(operation, pins, runtime_revision, ready, result, command):
    need(result.get('passed') is True, 'TARGET_APP_OBSERVATION_UNCONFIRMED')
    cdc.fields(command, transport.COMMAND_FIELDS)
    need(core.integer(command['token'], 1) and core.integer(command['startedEpoch'], 1)
         and core.integer(command['completedEpoch'], command['startedEpoch'])
         and cdc.match(r'[0-9a-f-]{36}', command['commandId']), 'EXACT_TARGET_APP_COMMAND_METADATA_REQUIRED')
    validate_observation(result['observation'], pins['app'], runtime_revision, ready['appRuntime'])
    text = transport.observed_app_program(pins['app'], runtime_revision, ready['appRuntime'])
    need(command['status'] == 'Success' and command['instanceId'] == pins['app']['instanceId']
         and command['containerId'] == pins['app']['containerId'] and command['commandSha256'] == sha(text.encode()), 'EXACT_TARGET_APP_COMMAND_REQUIRED')
    return {'operationSha256': operation_binding(operation), 'command': {k: command[k] for k in transport.COMMAND_FIELDS.split()},
            'observation': result['observation']}


def validate_identity(value, operation, context, proofs):
    cdc.fields(value, 'rds asg hosts networkCidr')
    rds = value['rds']; cdc.fields(rds, 'identifier resourceId serverUuid endpoint masterSecretArn caBundle caSha256')
    expected = proofs['hostReceipt']['targetIdentity']
    need(all(rds[k] == expected[k] for k in expected)
         and cdc.match(r'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-[A-Za-z0-9-]+', rds['masterSecretArn'])
         and rds['caBundle'] == f"/opt/airbob/global-b/{operation['runId']}/rds-ca.pem"
         and rds['caSha256'] == proofs['serviceManifest']['preparation']['rdsCaBundle']['sha256'], 'SNAPSHOT_TARGET_RDS_IDENTITY_CHANGED')
    group = value['asg']; cdc.fields(group, 'name arn launchTemplate runtimeRevision')
    need(group['name'] == context['phase4']['auto_scaling_group_name'] and group['runtimeRevision'] == context['phase4']['runtime_revision']
         and cdc.match(r'arn:aws:autoscaling:ap-northeast-2:942632789808:autoScalingGroup:[A-Za-z0-9-]+:autoScalingGroupName/' + group['name'], group['arn']),
         'SNAPSHOT_TARGET_ASG_IDENTITY_CHANGED')
    cdc.fields(group['launchTemplate'], 'LaunchTemplateId Version')
    need(cdc.match(r'lt-[0-9a-f]{17}', group['launchTemplate']['LaunchTemplateId'])
         and cdc.match(r'[1-9][0-9]*', group['launchTemplate']['Version']), 'EXACT_TARGET_LAUNCH_TEMPLATE_REQUIRED')
    cdc.fields(value['hosts'], 'app connect kafka elasticsearch')
    images = {'app': proofs['serviceManifest']['application']['image'], 'connect': proofs['serviceManifest']['debezium']['image'],
              'kafka': context['operator']['infraImageReferences']['KAFKA_IMAGE'], 'elasticsearch': proofs['serviceManifest']['search']['image']}
    import ipaddress
    network = ipaddress.ip_network(value['networkCidr'], strict=True)
    need(network.is_private and network.version == 4 and network.prefixlen >= 16, 'PRIVATE_TARGET_NETWORK_REQUIRED')
    for role, pin in value['hosts'].items():
        cdc.fields(pin, 'instanceId privateIp containerId imageId image startedAt' + (' clusterUuid indexName' if role == 'elasticsearch' else ''))
        need(cdc.match(cdc.INSTANCE, pin['instanceId']) and ipaddress.ip_address(pin['privateIp']) in network
             and cdc.match(cdc.HASH, pin['containerId']) and cdc.match('sha256:' + cdc.HASH, pin['imageId'])
             and cdc.match(r'[0-9TZ:.-]{20,40}', pin['startedAt']) and pin['image'] == images[role], 'EXACT_TARGET_CONTAINER_REQUIRED')
        if role != 'app':
            key = 'debezium' if role == 'connect' else role
            need(pin['instanceId'] == context['phase2']['services'][key], 'EXACT_TARGET_SERVICE_INSTANCE_REQUIRED')
    need(len({row['instanceId'] for row in value['hosts'].values()}) == 4
         and len({row['privateIp'] for row in value['hosts'].values()}) == 4
         and value['hosts']['elasticsearch']['indexName'] == proofs['readiness']['restoredIndex']
         and value['hosts']['elasticsearch']['clusterUuid'] == proofs['searchRestore']['elasticsearch']['clusterUuid'],
         'SEPARATE_TARGET_SERVICES_AND_RESTORED_ALIAS_REQUIRED')
    return value


def validate_job(job, *, admitted=None):
    cdc.fields(job, 'schemaVersion kind operation context documents identity before deadlineEpoch sourceFiles')
    need(job['schemaVersion'] == 1 and job['kind'] == HOST_KIND and job['sourceFiles'] == source_files(), 'EXACT_TARGET_HOST_JOB_REQUIRED')
    # Completion/recovery validation is historical and offline. Actual host
    # admission always supplies its current wall clock and checks freshness.
    observed_epoch = admitted if admitted is not None else job['before']['command']['completedEpoch']
    validate_operation(job['operation']); validate_context(job['context'], job['operation'], clock=lambda: observed_epoch)
    proofs = validate_proofs(job['operation'], job['context'], job['documents'])
    validate_identity(job['identity'], job['operation'], job['context'], proofs)
    before = job['before']; cdc.fields(before, 'operationSha256 command observation')
    need(before['operationSha256'] == operation_binding(job['operation']), 'TARGET_BEFORE_OBSERVATION_CHANGED')
    app_attestation(job['operation'], job['identity']['hosts'], job['identity']['asg']['runtimeRevision'], proofs['readiness'],
                    {'passed': True, 'observation': before['observation']}, before['command'])
    if admitted is not None:
        need(0 <= admitted - before['command']['completedEpoch'] <= 120, 'FRESH_TARGET_APP_ATTESTATION_REQUIRED')
    need(job['deadlineEpoch'] <= job['context']['controllerDeadlineEpoch']
         and (admitted is None or admitted < job['deadlineEpoch'] <= admitted + 1800), 'TARGET_READ_DEADLINE_REQUIRED')
    return proofs


class HostGuard:
    def __init__(self, job, *, aws=None, clock=time.time, monotonic=time.monotonic):
        self.job, self.aws, self.clock, self.monotonic = job, aws or cdc_host.HostAws(), clock, monotonic
        self.operation, self.context, self.identity = job['operation'], job['context'], job['identity']
        self.root = Path('/opt/airbob/global-b') / self.operation['runId']
        self.lease = restore.Lease(self.aws, self.context['lease'])
        self.start, self.last, self.initialized = monotonic(), -float('inf'), False

    def __call__(self, force=False):
        need(self.clock() < self.job['deadlineEpoch'] and self.monotonic() - self.start < 1800
             and not (self.root / 'STOP').exists(), 'TARGET_HOST_DEADLINE_OR_STOP')
        if not force and self.monotonic() - self.last < 5: return
        self.lease(force=True)
        connect = self.identity['hosts']['connect']
        if not self.initialized:
            need(not any(os.environ.get(k) for k in ('AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')),
                 'TARGET_CONNECT_INSTANCE_PROFILE_ONLY')
            need(cdc_host.imds_identity() == {k: connect[k] for k in ('instanceId', 'privateIp')}, 'EXACT_TARGET_CONNECT_HOST_REQUIRED')
            caller = self.aws.call('sts', 'get-caller-identity')
            need(caller.get('Account') == cdc.ACCOUNT and caller.get('Arn') ==
                 f"arn:aws:sts::{cdc.ACCOUNT}:assumed-role/airbob-lab-host-{self.operation['runId']}-debezium/{connect['instanceId']}",
                 'EXACT_TARGET_CONNECT_ROLE_REQUIRED')
            self.initialized = True
        pin = self.identity['rds']
        rows = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', pin['identifier'])['DBInstances']
        need(len(rows) == 1, 'EXACT_TARGET_RDS_REQUIRED'); row = rows[0]
        need(row['DbiResourceId'] == pin['resourceId'] and row['Endpoint']['Address'] == pin['endpoint'] and row['Endpoint']['Port'] == 3306
             and row['Engine'] == 'mysql' and row['EngineVersion'] == '8.4.11' and row['DBInstanceStatus'] == 'available'
             and row['PubliclyAccessible'] is False and row['MasterUserSecret']['SecretArn'] == pin['masterSecretArn'], 'ACTUAL_TARGET_RDS_CHANGED')
        require_tags(row['TagList'], self.operation, self.context); self.master_username = row['MasterUsername']
        groups = [g for g in self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
                  if g['AutoScalingGroupName'].startswith(pin['identifier'] + '-')]
        need(len(groups) == 1, 'TARGET_ASG_INVENTORY_CHANGED'); group = groups[0]
        require_tags(group['Tags'], self.operation, self.context, 'app')
        need(group['AutoScalingGroupName'] == self.identity['asg']['name'] and group['AutoScalingGroupARN'] == self.identity['asg']['arn']
             and group['LaunchTemplate'] == self.identity['asg']['launchTemplate']
             and tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
             and len(group['Instances']) == 1 and group['Instances'][0]['InstanceId'] == self.identity['hosts']['app']['instanceId']
             and group['Instances'][0]['LaunchTemplate'] == self.identity['asg']['launchTemplate']
             and group['Instances'][0]['HealthStatus'] == 'Healthy' and group['Instances'][0]['LifecycleState'] == 'InService', 'TARGET_NORMAL_APP_CAPACITY_CHANGED')
        rows = core.parse(core.command(['docker', '--host', 'unix:///var/run/docker.sock', 'inspect', connect['containerId']], timeout=10))
        need(len(rows) == 1, 'EXACT_TARGET_CONNECT_REQUIRED'); item = rows[0]
        need(item['Id'] == connect['containerId'] and item['Image'] == connect['imageId'] and item['Config']['Image'] == connect['image']
             and item['State']['Running'] and not item['State']['Paused'] and not item['State']['Restarting']
             and item['State']['StartedAt'] == connect['startedAt'], 'TARGET_CONNECT_LIFETIME_CHANGED')
        need(source_files() == self.job['sourceFiles'] and contract.sha(pin['caBundle']) == pin['caSha256'], 'TARGET_RUNTIME_SOURCES_CHANGED')
        self.last = self.monotonic()


def http_inputs(job, value):
    operation, identity = job['operation'], job['identity']
    config = {'datasetId': operation['datasetId'], 'releaseDirectory': value['release'], 'privateAccounts': value['privateAccounts'],
        'consumerManifestSha256': contract.sha(Path(value['release']) / 'consumer-manifest.json'),
        'checksumsSha256': contract.sha(Path(value['release']) / 'SHA256SUMS.json'), 'accountEnvironment': restore.credential_environment(value),
        'hosts': identity['hosts'], 'requestTimeoutSeconds': 10}
    # This generic HTTP helper accepts a prerequisite hash, not a dump state.
    # The outer snapshot family has authenticated the actual preparation above.
    return {'configuration': config, 'value': {'deadlineEpoch': job['deadlineEpoch'],
        'restoreReceipt': {'sha256': job['documents']['snapshotOperation']['reference']['sha256']}}}


def host_verify(job, output, *, guard=None, http_runner=reads.verify_http, admitted=None):
    admitted = time.time() if admitted is None else admitted
    proofs = validate_job(job, admitted=admitted)
    guard = guard or HostGuard(job); guard(force=True)
    root = Path('/opt/airbob/global-b') / job['operation']['runId']; output = Path(output)
    need(output == root / 'r5-service' / job['operation']['operationId'] / 'result' and output.resolve() == output
         and not output.exists(), 'CANONICAL_NEW_TARGET_RESULT_REQUIRED')
    output.mkdir(mode=0o700); (output / '.private').mkdir(mode=0o700)
    original = root / 'restore-config.json'
    need(contract.sha(original) == proofs['hostReceipt']['restoreConfigSha256'], 'RETAINED_TARGET_CONFIG_CHANGED')
    value = restore.configuration(original); value['lease'] = job['context']['lease']
    need(restore.target_identity(value) == proofs['hostReceipt']['targetIdentity'] and value['privateAccounts'] == str(root / 'private/accounts.private.json')
         and value['release'] == str(root / 'release') and value['rds']['masterSecretArn'] == job['identity']['rds']['masterSecretArn'], 'RETAINED_TARGET_INPUTS_CHANGED')
    envelope = restore.validate_inputs(value)
    operation = proofs['snapshotOperation']
    need(envelope['datasetId'] == job['operation']['datasetId'] and value['envelopeSha256'] == operation['envelopeSha256']
         and restore.credential_binding(value) == operation['credentialBindingSha256'], 'TARGET_SEALED_PREPARATION_INPUT_CHANGED')
    require = contract.require
    require((root / 'bootstrap-runtime').is_dir(), 'Service runtime must already be qualified by dependency bootstrap')
    started = now()
    _, qualification = service.qualify_service_runtime(root, value['release'], output, envelope, 'snapshot')
    with runtime_gate.activated_runtime(qualification):
        db, _ = restore.connection(value, guard.aws, {'masterUsername': guard.master_username}, output / '.private', guard)
        db = reads.StrictDatabase(db.defaults, min(1800, max(1, int(job['deadlineEpoch'] - time.time()))), guard,
                                  {'rds': job['identity']['rds']})
        def database_identity():
            need(db.scalar('SELECT @@version', False) == '8.4.11' and db.scalar('SELECT @@server_uuid', False) == value['rds']['serverUuid'],
                 'CURRENT_TARGET_DATABASE_IDENTITY_CHANGED')
            cipher = db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'", False)
            need(len(cipher) == 1 and cipher[0].get('Value'), 'CURRENT_TARGET_DATABASE_TLS_REQUIRED')
        database_identity()
        inputs = http_inputs(job, value)
        observations = http_runner(inputs, output, guard)
        guard(force=True); database_identity()
    # Source rows and private credentials are not exported. Owned sessions must
    # have been invalidated by the real HTTP runners before any completion.
    need(contract.sha(original) == proofs['hostReceipt']['restoreConfigSha256'] and source_files() == job['sourceFiles'], 'TARGET_INPUTS_CHANGED_DURING_READS')
    result = {'schemaVersion': 1, 'kind': HOST_KIND + '-observation', 'state': 'TARGET_READ_OBSERVATIONS_COMPLETE',
        'operationSha256': operation_binding(job['operation']), 'jobSha256': sha(encoded(job)), 'identity': job['identity'],
        'lease': job['context']['lease'], 'sourceFiles': job['sourceFiles'], 'startedAt': started, 'completedAt': now(),
        'admissionEpoch': admitted, 'deadlineEpoch': job['deadlineEpoch'], 'observations': observations,
        'hostRuntimeQualification': runtime_gate.qualification_binding(output / 'host-runtime.json'),
        'preparedFingerprintRef': job['documents']['preparedFingerprint']['reference'],
        'actualTargetUuidAndTlsBeforeAndAfterVerified': True, 'businessDatabaseWritesPerformed': False,
        'cdcMutationPerformed': False, 'privateValuesIncluded': False, 'postReadAppAttestationRequired': True}
    core.write_new(output / 'host-observation.json', encoded(result))
    return result


INPUT_BODY = r'''try:
 root=guard(); area=new_directory(new_directory(root/'r5-service')/p['operationId']); folder=new_directory(area/'inputs')
 meta=p['file']; need(0<meta['bytes']<=524288 and len(meta['sha256'])==64)
 chunks=new_directory(new_directory(area/'chunks')/meta['sha256'])
 create(chunks/'input.json',(json.dumps(meta,sort_keys=True,separators=(',',':'))+'\n').encode())
 if p['action']=='chunk':
  raw=base64.b64decode(p['data'],validate=True)
  need(0<=p['index']<(meta['bytes']+6143)//6144 and len(raw)==min(6144,meta['bytes']-p['index']*6144) and digest(raw)==p['chunkSha256'])
  create(chunks/('%04d.chunk'%p['index']),raw)
  print(json.dumps({'passed':True,'sha256':digest(raw),'bytes':len(raw),'index':p['index']}))
 else:
  need(p['action']=='install')
  raw=b''.join(private(chunks/('%04d.chunk'%i)).read_bytes() for i in range((meta['bytes']+6143)//6144))
  need(len(raw)==meta['bytes'] and digest(raw)==meta['sha256']); job=json.loads(raw)
  need(job['kind']=='global-b-aws-snapshot-target-service-host' and job['operation']['operationId']==p['operationId'])
  path=folder/(meta['sha256']+'-job.json'); create(path,raw)
  print(json.dumps({'passed':True,'reference':{'path':str(path),'sha256':digest(raw),'bytes':len(raw)}}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'TARGET_INPUT_STAGE_UNCONFIRMED'})); raise SystemExit(1)
'''

WORKER_BODY = r'''try:
 root=guard(); area=private(root/'r5-service'/p['operationId'],True); tools=private(root/'cdc-tools',True)
 for name,expected in p['sources'].items(): need(digest(private(tools/name).read_bytes())==expected)
 path=private(pathlib.Path(p['job']['path'])); need(path.parent==area/'inputs' and digest(path.read_bytes())==p['job']['sha256'])
 job=json.loads(path.read_bytes()); need(job['kind']=='global-b-aws-snapshot-target-service-host' and job['deadlineEpoch']==p['deadlineEpoch'])
 lock=os.open(area/'worker.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 intent=area/'worker-intent.json'; need(not intent.exists() and not (area/'result').exists())
 create(intent,(json.dumps({'jobSha256':p['job']['sha256'],'deadlineEpoch':p['deadlineEpoch']},sort_keys=True,separators=(',',':'))+'\n').encode())
 env={k:v for k,v in os.environ.items() if not k.startswith('AWS_') and k not in ('PYTHONPATH','PYTHONHOME','PYTHONSTARTUP','PYTHONINSPECT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','JDK_JAVAC_OPTIONS','CLASSPATH')}
 env.update(PATH=':'.join(str(root/v) for v in ('aws-bin','toolchain/python/bin','toolchain/jdk/bin','toolchain/mysql/bin'))+':'+os.environ['PATH'],JAVA_HOME=str(root/'toolchain/jdk'),PYTHONDONTWRITEBYTECODE='1',AWS_REGION='ap-northeast-2',AWS_CONFIG_FILE='/dev/null',AWS_SHARED_CREDENTIALS_FILE='/dev/null')
 remaining=int(p['deadlineEpoch']-time.time()); need(remaining>10)
 child=subprocess.Popen(['timeout','-s','TERM','-k','5',str(remaining),str(root/'toolchain/python/bin/python3'),str(tools/'infra/aws/scripts/growth_b_snapshot_service_verify.py'),'host','--job',str(path),'--output',str(area/'result')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True,env=env,pass_fds=(lock,))
 ticks=pathlib.Path('/proc/%d/stat'%child.pid).read_text().rsplit(')',1)[1].split()[19]
 create(area/'worker.json',(json.dumps({'pid':child.pid,'startTicks':ticks,'jobSha256':p['job']['sha256'],'deadlineEpoch':p['deadlineEpoch']},sort_keys=True,separators=(',',':'))+'\n').encode())
 code=child.wait(timeout=remaining+8); raw=private(area/'result/host-observation.json').read_bytes(); report=json.loads(raw)
 need(code==0 and report['state']=='TARGET_READ_OBSERVATIONS_COMPLETE')
 print(json.dumps({'passed':True,'reference':{'path':str(area/'result/host-observation.json'),'sha256':digest(raw),'bytes':len(raw)}}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'TARGET_SERVICE_WORKER_UNCONFIRMED'})); raise SystemExit(1)
'''

DOWNLOAD_BODY = r'''try:
 root=guard(); result=root/'r5-service'/p['operationId']/'result'; ref=p['reference']; path=pathlib.Path(ref['path'])
 allowed={'host-observation.json','representatives/representative-http-reads.json','warmup/warmup-receipt.json','media/media-availability.json','host-runtime.json'}
 need(any(path==result/name for name in allowed)); raw=private(path).read_bytes()
 need(0<len(raw)<=2097152 and digest(raw)==ref['sha256'])
 if p['index'] is None: print(json.dumps({'passed':True,'reference':{'path':str(path),'sha256':digest(raw),'bytes':len(raw)}}))
 else:
  need(len(raw)==ref['bytes']); offset=p['index']*6144; need(0<=offset<len(raw)); chunk=raw[offset:offset+6144]
  print(json.dumps({'passed':True,'index':p['index'],'sha256':digest(chunk),'bytes':len(chunk),'data':base64.b64encode(chunk).decode()}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'TARGET_PUBLIC_READ_UNCONFIRMED'})); raise SystemExit(1)
'''


def validate_recovery(value, operation, *, selected_resume=True):
    cdc.fields(value, 'schemaVersion kind originalOperation originalContext sources documents identity job hostObservation journal journalHead publicArtifacts')
    need(value['schemaVersion'] == 1 and value['kind'] == RECOVERY_KIND and (not selected_resume or 'resume' in operation)
         and operation_binding(value['originalOperation']) == operation_binding(operation) and 'resume' not in value['originalOperation']
         and value['sources'] == source_files(), 'EXACT_TARGET_RECOVERY_REQUIRED')
    validate_operation(value['originalOperation'])
    validate_context(value['originalContext'], value['originalOperation'], clock=lambda: value['originalContext']['controllerDeadlineEpoch'] - 1)
    transport.validate_chain(value['journal'], value['journalHead'], configuration_sha=operation_binding(operation), tool_sha=sha(encoded(source_files())))
    need(set(value['documents']) <= DOCUMENTS, 'RECOVERY_TARGET_PROOF_INVENTORY_CHANGED')
    for blob in value['documents'].values(): unpack_document(blob)
    if set(value['documents']) == DOCUMENTS:
        proofs = validate_proofs(operation, value['originalContext'], value['documents'])
        if value['identity']: validate_identity(value['identity'], operation, value['originalContext'], proofs)
    else:
        need(value['identity'] is None and value['job'] is None and value['hostObservation'] is None, 'RECOVERY_TARGET_PROOF_CHAIN_INCOMPLETE')
    need(value['job'] is None or value['job']['operation'] == value['originalOperation'], 'RECOVERY_TARGET_JOB_CHANGED')
    if value['job'] is not None: validate_job(value['job'])
    need(set(value['publicArtifacts']) <= PUBLIC_FILES, 'RECOVERY_TARGET_PUBLIC_FILES_CHANGED')
    for blob in value['publicArtifacts'].values():
        cdc.fields(blob, 'sha256 bytes base64'); raw = base64.b64decode(blob['base64'], validate=True)
        need(len(raw) == blob['bytes'] and sha(raw) == blob['sha256'], 'RECOVERY_TARGET_PUBLIC_BYTES_CHANGED')
        snapshot_host.public_json(core.parse(raw))
    return value


class Runner:
    # Generic immutable command recovery only; no source-R4 Builder/admission.
    once = transport.Supervisor.once

    def __init__(self, operation, context, output, *, aws=None, recovery=None, clock=time.time, sleep=time.sleep, transport_factory=transport.Ssm):
        self.operation = validate_operation(operation); self.context = validate_context(context, operation, clock=clock)
        self.directory, self.clock, self.sleep = Path(output).absolute(), clock, sleep
        need(self.directory.parent.resolve() == self.directory.parent, 'CANONICAL_TARGET_OUTPUT_REQUIRED')
        if not self.directory.exists(): self.directory.mkdir(mode=0o700)
        core.private_path(self.directory, directory=True)
        self.public = self.directory / 'public'
        if not self.public.exists(): self.public.mkdir(mode=0o700)
        core.private_path(self.public, directory=True)
        self.original_operation = {k: v for k, v in operation.items() if k != 'resume'}
        self.original_context = copy.deepcopy(context)
        self.documents, self.identity, self.job, self.host_observation = {}, None, None, None
        if 'resume' in operation:
            need(recovery is not None, 'EXACT_TARGET_RECOVERY_INPUT_REQUIRED'); validate_recovery(recovery, operation)
            self.original_operation, self.original_context = recovery['originalOperation'], recovery['originalContext']
            need(all(context[k] == self.original_context[k] for k in ('executionCommit', 'approvedExecutionDeadlineEpoch', 'operator', 'phase2', 'phase3', 'phase4', 'serviceState')),
                 'RESUME_TARGET_CONTEXT_CHANGED')
            journal_dir = self.directory / 'journal'; journal_dir.mkdir(mode=0o700)
            for i, event in enumerate(recovery['journal']): core.write_new(journal_dir / f'{i:04d}.json', encoded(event))
            self.documents, self.identity, self.job, self.host_observation = (recovery[k] for k in ('documents', 'identity', 'job', 'hostObservation'))
            for name, blob in recovery['publicArtifacts'].items(): core.write_new(self.public / name, base64.b64decode(blob['base64'], validate=True))
        else:
            need(recovery is None and not (self.directory / 'journal').exists(), 'NEW_TARGET_OPERATION_DIRECTORY_REQUIRED')
        self.deadline = min(context['controllerDeadlineEpoch'], self.original_context['controllerDeadlineEpoch'])
        self.aws = aws or ReadAws(self.deadline, clock=clock)
        self.lease = restore.Lease(self.aws, context['lease'])
        self.journal = transport.RecoveryJournal(self.directory / 'journal', operation_binding(operation), sha(encoded(source_files())))
        self.transport = transport_factory(self.aws, self.journal, self.guard, clock=clock, sleep=sleep)
        self.transport.common_deadline = self.deadline
        self.journal.checkpoint = self.checkpoint
        self.checkpoint()

    def guard(self):
        need(self.clock() < self.deadline, 'TARGET_CONTROLLER_DEADLINE_REACHED'); self.lease(force=True)

    def checkpoint(self):
        artifacts = {}
        for name in PUBLIC_FILES:
            path = self.public / name
            if path.exists():
                raw = core.private_path(path).read_bytes(); snapshot_host.public_json(core.parse(raw))
                artifacts[name] = {'sha256': sha(raw), 'bytes': len(raw), 'base64': base64.b64encode(raw).decode()}
        value = {'schemaVersion': 1, 'kind': RECOVERY_KIND, 'originalOperation': self.original_operation,
            'originalContext': self.original_context, 'sources': source_files(), 'documents': self.documents,
            'identity': self.identity, 'job': self.job, 'hostObservation': self.host_observation,
            'journal': self.journal.entries, 'journalHead': self.journal.last_sha, 'publicArtifacts': artifacts}
        need(len(encoded(value)) <= 2 * 1024**2, 'TARGET_RECOVERY_BUDGET_EXCEEDED')
        transport.write_current(self.public / 'recovery.json', value)

    def fetch(self, name, reference):
        need(name in DOCUMENTS, 'CLOSED_TARGET_INPUT_NAME_REQUIRED'); exact_ref(reference, '')
        if name in self.documents:
            need(self.documents[name]['reference'] == reference, 'SAVED_TARGET_REFERENCE_CHANGED')
            return unpack_document(self.documents[name])[0]
        self.guard(); path = self.directory / ('selected-' + name + '.json')
        need(not path.exists(), 'NEW_TARGET_REFERENCE_PATH_REQUIRED')
        result = self.aws.call('s3api', 'get-object', '--bucket', snapshot_controller.bucket(reference), '--key', reference['key'],
                               '--version-id', reference['versionId'], str(path))
        need(result.get('VersionId') == reference['versionId'], 'TARGET_REFERENCE_VERSION_CHANGED'); path.chmod(0o600)
        raw = core.private_path(path).read_bytes()
        blob = {'reference': reference, 'base64': base64.b64encode(raw).decode()}; value, _ = unpack_document(blob)
        self.documents[name] = blob; self.checkpoint(); return value

    def proofs(self):
        op = self.operation
        manifest = self.fetch('serviceManifest', op['manifest']); self.fetch('readiness', op['readiness'])
        service.validate_manifest(manifest, op['datasetId'], op['runId'], op['serviceRelease'])
        self.fetch('searchRestore', exact_ref(manifest['search']['restoreReceipt'], f"data-bootstrap/{op['runId']}/"))
        selected = self.fetch('snapshotManifest', op['targetPreparation']['manifest'])
        snapshot_controller.validate_manifest(selected, op['targetPreparation']['manifest']['sha256'], op['datasetId'], op['runId'], selected['operationId'])
        need(selected['operation'] == 'prepare', 'ACTUAL_TARGET_PREPARATION_REQUIRED')
        host = self.fetch('hostReceipt', op['targetPreparation']['hostReceipt'])
        target_prefix = snapshot_host.evidence_prefix(op['datasetId'], op['runId'], selected['operationId'])
        for name, key in (('preparation', 'data-only-preparation.json'), ('snapshotOperation', 'snapshot-operation.json'),
                          ('preparedFingerprint', 'prepared-fingerprint.json'), ('targetPreflight', 'preflight.json')):
            ref = exact_ref(host['objects'][key], target_prefix)
            need(ref['key'] == target_prefix + key, 'EXACT_TARGET_PREPARATION_OBJECT_REQUIRED')
            self.fetch(name, ref)
        for name in ('provenance', 'admission', 'restoreEvent'): self.fetch(name, selected['evidence'][name])
        return validate_proofs(op, self.context, self.documents)

    def topology(self, proofs):
        self.guard(); op, context = self.operation, self.context
        rows = self.aws.call('rds', 'describe-db-instances')['DBInstances']
        expected = proofs['hostReceipt']['targetIdentity']
        need(len(rows) == 1, 'SOLE_SNAPSHOT_TARGET_RDS_REQUIRED'); rds = rows[0]
        need(rds['DBInstanceIdentifier'] == expected['identifier'] and rds['DbiResourceId'] == expected['resourceId']
             and rds['Endpoint']['Address'] == expected['endpoint'] and rds['Endpoint']['Port'] == 3306
             and rds['Engine'] == 'mysql' and rds['EngineVersion'] == '8.4.11' and rds['DBInstanceStatus'] == 'available'
             and rds['PubliclyAccessible'] is False, 'ACTUAL_SNAPSHOT_TARGET_CHANGED')
        require_tags(rds['TagList'], op, context)
        groups = [g for g in self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
                  if g['AutoScalingGroupName'].startswith(expected['identifier'] + '-')]
        need(len(groups) == 1, 'EXACT_TARGET_ASG_REQUIRED'); group = groups[0]
        require_tags(group['Tags'], op, context, 'app')
        need(group['AutoScalingGroupName'] == context['phase4']['auto_scaling_group_name']
             and tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
             and len(group['Instances']) == 1 and group['Instances'][0]['HealthStatus'] == 'Healthy'
             and group['Instances'][0]['LifecycleState'] == 'InService' and group['Instances'][0]['LaunchTemplate'] == group['LaunchTemplate']
             and not group.get('MixedInstancesPolicy') and not group.get('SuspendedProcesses'), 'CURRENT_SINGLE_TARGET_APP_REQUIRED')
        selected = {'app': group['Instances'][0]['InstanceId'], **{name: context['phase2']['services'][key]
                    for name, key in (('connect', 'debezium'), ('kafka', 'kafka'), ('elasticsearch', 'elasticsearch'))}}
        instances = {}
        for role, identifier in selected.items():
            rows = [r for res in self.aws.call('ec2', 'describe-instances', '--instance-ids', identifier)['Reservations'] for r in res['Instances']]
            need(len(rows) == 1, 'EXACT_TARGET_HOST_REQUIRED'); row = rows[0]
            tags = require_tags(row['Tags'], op, context, 'debezium' if role == 'connect' else role)
            need(row['InstanceId'] == identifier and row['State']['Name'] == 'running' and row['VpcId'] == context['phase2']['vpc_id'], 'TARGET_HOST_IDENTITY_CHANGED')
            if role == 'app': need(tags.get('RuntimeRevision') == context['phase4']['runtime_revision'], 'TARGET_APP_REVISION_CHANGED')
            instances[role] = row
        networks = self.aws.call('ec2', 'describe-vpcs', '--vpc-ids', context['phase2']['vpc_id'])['Vpcs']
        need(len(networks) == 1 and networks[0]['VpcId'] == context['phase2']['vpc_id'], 'EXACT_TARGET_VPC_REQUIRED')
        return group, rds, instances, networks[0]['CidrBlock']

    def discover(self, proofs):
        group, rds, instances, network = self.topology(proofs)
        manifest = proofs['serviceManifest']
        images = {'app': manifest['application']['image'], 'connect': manifest['debezium']['image'],
                  'kafka': self.context['operator']['infraImageReferences']['KAFKA_IMAGE'], 'elasticsearch': manifest['search']['image']}
        pins = {}
        for role, instance in instances.items():
            value, command = self.transport.run('discover-' + role, instance['InstanceId'], None,
                transport.discovery_program(role, instance, images[role], proofs['readiness']), self.deadline)
            need(value.get('passed') is True, 'TARGET_DISCOVERY_UNCONFIRMED')
            pins[role] = value['observation']
            need(pins[role]['instanceId'] == instance['InstanceId'] and pins[role]['privateIp'] == instance['PrivateIpAddress'], 'TARGET_DISCOVERY_COORDINATES_CHANGED')
            self.journal.add('TARGET_DISCOVERED', {'role': role, 'command': command, 'observation': pins[role]})
        current_group, _, current_instances, current_network = self.topology(proofs)
        need(current_group == group and current_network == network
             and all(current_instances[k]['InstanceId'] == v['InstanceId'] and current_instances[k]['PrivateIpAddress'] == v['PrivateIpAddress']
                     for k, v in instances.items()), 'TARGET_DISCOVERY_TOPOLOGY_CHANGED')
        value = {'rds': proofs['hostReceipt']['targetIdentity'] | {'masterSecretArn': rds['MasterUserSecret']['SecretArn'],
                    'caBundle': f"/opt/airbob/global-b/{self.operation['runId']}/rds-ca.pem", 'caSha256': manifest['preparation']['rdsCaBundle']['sha256']},
                 'asg': {'name': group['AutoScalingGroupName'], 'arn': group['AutoScalingGroupARN'], 'launchTemplate': group['LaunchTemplate'],
                         'runtimeRevision': self.context['phase4']['runtime_revision']}, 'hosts': pins, 'networkCidr': network}
        validate_identity(value, self.operation, self.context, proofs)
        if self.identity is not None: need(self.identity == value, 'RESUMED_TARGET_RUNTIME_CHANGED')
        self.identity = value; self.checkpoint(); return value

    def payload(self, deadline=None):
        return {'runId': self.operation['runId'], 'operationId': self.operation['operationId'], 'lease': self.context['lease'],
                'expiresAt': int(self.context['operator']['expiresAt']), 'deadlineEpoch': deadline or self.deadline, 'pin': self.identity['hosts']['connect']}

    def install(self):
        archive, meta = source_archive(); p = self.payload()
        for index, offset in enumerate(range(0, len(archive), transport.CHUNK_BYTES)):
            chunk = archive[offset:offset + transport.CHUNK_BYTES]
            observed, _ = self.once('source-' + str(index), f'source-chunk-{index:03d}', p['pin'], transport.stage_program(p, meta, index=index, data=chunk))
            need(observed == {'passed': True, 'index': index, 'sha256': sha(chunk), 'bytes': len(chunk)}, 'TARGET_SOURCE_CHUNK_CHANGED')
        observed, _ = self.once('source-install', 'source-install', p['pin'], transport.stage_program(p, meta))
        need(observed['archiveSha256'] == meta['sha256'] and observed['files'] == len(meta['files']), 'TARGET_SOURCE_INSTALL_CHANGED')

    def app_observation(self, name, proofs):
        self.topology(proofs)
        pin = self.identity['hosts']['app']
        result, command = self.transport.run(name, pin['instanceId'], pin['containerId'],
            transport.observed_app_program(pin, self.identity['asg']['runtimeRevision'], proofs['readiness']['appRuntime']), self.deadline)
        result = app_attestation(self.operation, self.identity['hosts'], self.identity['asg']['runtimeRevision'], proofs['readiness'], result, command)
        self.journal.add('TARGET_APP_OBSERVED', {'name': name, 'attestation': result}); return result

    def stage_job(self):
        raw = encoded(self.job); digest = sha(raw)
        need(len(raw) <= MAX_JOB, 'BOUNDED_TARGET_JOB_REQUIRED')
        p = self.payload() | {'file': {'sha256': digest, 'bytes': len(raw)}}
        for index, offset in enumerate(range(0, len(raw), transport.CHUNK_BYTES)):
            chunk = raw[offset:offset + transport.CHUNK_BYTES]
            payload = p | {'action': 'chunk', 'index': index, 'chunkSha256': sha(chunk), 'data': base64.b64encode(chunk).decode()}
            value, _ = self.once('job-' + digest + '-' + str(index), 'target-job-chunk', p['pin'], transport.program(INPUT_BODY, payload))
            need(value == {'passed': True, 'index': index, 'bytes': len(chunk), 'sha256': sha(chunk)}, 'TARGET_JOB_CHUNK_CHANGED')
        value, _ = self.once('job-install-' + digest, 'target-job-install', p['pin'], transport.program(INPUT_BODY, p | {'action': 'install'}))
        expected = {'path': f"/opt/airbob/global-b/{self.operation['runId']}/r5-service/{self.operation['operationId']}/inputs/{digest}-job.json",
                    'sha256': digest, 'bytes': len(raw)}
        need(value == {'passed': True, 'reference': expected}, 'EXACT_TARGET_JOB_INSTALL_REQUIRED')
        return expected

    def download(self, reference):
        p = self.payload() | {'reference': reference}
        if 'bytes' not in reference:
            result, _ = self.transport.run('target-public-metadata', p['pin']['instanceId'], p['pin']['containerId'],
                transport.program(DOWNLOAD_BODY, p | {'index': None}), self.deadline)
            need(result.get('passed') is True and all(result['reference'][k] == v for k, v in reference.items()), 'TARGET_PUBLIC_REFERENCE_CHANGED')
            reference = result['reference']; p['reference'] = reference
        need(core.integer(reference['bytes'], 1) and reference['bytes'] <= 2 * 1024**2, 'TARGET_PUBLIC_BYTES_BUDGET')
        chunks = []
        for index, offset in enumerate(range(0, reference['bytes'], transport.CHUNK_BYTES)):
            result, _ = self.transport.run('target-public-read', p['pin']['instanceId'], p['pin']['containerId'],
                transport.program(DOWNLOAD_BODY, p | {'index': index}), self.deadline)
            raw = base64.b64decode(result['data'], validate=True)
            need(result['passed'] is True and result['index'] == index and sha(raw) == result['sha256']
                 and len(raw) == result['bytes'] == min(transport.CHUNK_BYTES, reference['bytes'] - offset), 'TARGET_PUBLIC_CHUNK_CHANGED')
            chunks.append(raw)
        raw = b''.join(chunks); need(sha(raw) == reference['sha256'], 'TARGET_PUBLIC_DOCUMENT_CHANGED')
        snapshot_host.public_json(core.parse(raw)); return raw

    def run(self):
        try:
            controller.verify_environment({'executionCommit': self.operation['executionCommit']}, self.aws, now=self.clock)
            self.guard(); self.transport.settle(self.deadline)
            proofs = self.proofs(); self.discover(proofs); self.install()
            if self.job is None:
                need(self.clock() + 1830 <= self.deadline, 'FULL_TARGET_READ_WINDOW_REQUIRED')
                before = self.app_observation('target-app-before', proofs)
                self.job = {'schemaVersion': 1, 'kind': HOST_KIND, 'operation': self.original_operation,
                    'context': copy.deepcopy(self.context), 'documents': self.documents, 'identity': self.identity,
                    'before': before, 'deadlineEpoch': int(self.clock()) + 1800, 'sourceFiles': source_files()}
                validate_job(self.job, admitted=self.clock()); self.checkpoint()
            existing = self.journal.last('TARGET_WORKER_FIXED')
            if existing:
                need(existing['jobSha256'] == sha(encoded(self.job)), 'ORIGINAL_TARGET_WORKER_JOB_CHANGED')
                payload, seconds = existing['programInputs'], existing['seconds']
                need(self.job['context']['lease'] == self.context['lease'] or any(
                    row['kind'] in ('COMMAND_OPERATION_INTENT', 'COMMAND_COMPLETE') and row['data']['key'] == 'target-read-worker'
                    for row in self.journal.entries), 'UNSTARTED_TARGET_JOB_REQUIRES_CURRENT_LEASE')
            else:
                need(self.job['context']['lease'] == self.context['lease'], 'UNSTARTED_TARGET_JOB_REQUIRES_CURRENT_LEASE')
                reference = self.stage_job()
                payload = self.payload(self.job['deadlineEpoch']) | {'job': reference, 'sources': source_files()}
                seconds = int(self.job['deadlineEpoch'] - self.clock()) + 15
                need(25 < seconds <= 1815, 'TARGET_WORKER_BUDGET_EXPIRED')
                self.journal.add('TARGET_WORKER_FIXED', {'jobSha256': sha(encoded(self.job)), 'programInputs': payload, 'seconds': seconds})
            result, command = self.once('target-read-worker', 'target-read-worker', payload['pin'], transport.program(WORKER_BODY, payload), seconds=seconds)
            reference = result['reference']
            expected = f"/opt/airbob/global-b/{self.operation['runId']}/r5-service/{self.operation['operationId']}/result/host-observation.json"
            need(reference['path'] == expected, 'CANONICAL_TARGET_HOST_RESULT_REQUIRED')
            if self.host_observation is None:
                raw = self.download(reference); self.host_observation = core.parse(raw)
                need(sha(encoded(self.host_observation)) == reference['sha256'], 'CANONICAL_TARGET_HOST_RESULT_BYTES_REQUIRED')
                self.checkpoint()
            host = self.host_observation
            need(host['jobSha256'] == sha(encoded(self.job)), 'TARGET_HOST_JOB_RECEIPT_CHANGED')
            refs = {'representative-http-reads.json': host['observations']['representatives'],
                    'warmup-receipt.json': host['observations']['warmup'], 'media-availability.json': host['observations']['media'],
                    'host-runtime-qualification.json': {k: host['hostRuntimeQualification'][k] for k in ('path', 'sha256')}}
            for name, ref in refs.items():
                raw = self.download(ref); transport.exact_write(self.public / name, raw); self.checkpoint()
            self.discover(proofs)
            after = self.app_observation('target-app-after', proofs); self.guard()
            report = {'schemaVersion': 1, 'kind': RECEIPT_KIND, 'state': COMPLETE, 'sourceMode': 'verified-global-b-snapshot',
                'operationId': self.operation['operationId'], 'runId': self.operation['runId'], 'datasetId': self.operation['datasetId'],
                'operationSha256': operation_binding(self.operation), 'completionContextSha256': sha(encoded(self.context)),
                'sourceArchiveSha256': self.operation['sourceArchiveSha256'], 'toolSources': source_files(),
                'targetIdentity': proofs['hostReceipt']['targetIdentity'], 'identity': self.identity,
                'completionLease': self.context['lease'], 'verificationLease': self.job['context']['lease'],
                'inputReferences': {name: blob['reference'] for name, blob in self.documents.items()},
                'jobSha256': sha(encoded(self.job)), 'hostObservationSha256': sha(encoded(host)),
                'hostCommand': {k: command[k] for k in transport.COMMAND_FIELDS.split()}, 'before': self.job['before'], 'after': after,
                'artifacts': {name: {'sha256': contract.sha(self.public / name), 'bytes': (self.public / name).stat().st_size} for name in refs},
                'completedAt': now(), 'resourceExpiresAt': int(self.context['operator']['expiresAt']),
                'approvedExecutionDeadlineEpoch': self.context['approvedExecutionDeadlineEpoch'],
                'service': {'readinessVerified': True, 'representativeAccounts': 3, 'normalLoginAndOwnershipPassed': True,
                    'publicAndGlobalSearchPassed': True, 'imagesSampled': True, 'reservableDatesPassed': True,
                    'warmupSuccessfulReads': 300, 'detailCacheDisabledVerified': True, 'ownedSessionsInvalidated': True},
                'timings': {'targetPreparation': {k: proofs['snapshotOperation'][k] for k in (
                    'fullValidationStartedAt', 'fullValidationCompletedAt', 'fullValidationSeconds',
                    'preparationStartedAt', 'preparationCompletedAt', 'preparationSeconds')},
                    'readStartedAt': host['startedAt'], 'readCompletedAt': host['completedAt']},
                'preparedFingerprintScope': 'target preparation before normal service reads',
                'databaseBusinessWritesPerformed': False, 'cdcMutationPerformed': False, 'sourceR4Claimed': False,
                'snapshotCreated': False, 'asgCapacityChanged': False, 'containersStartedOrStopped': False, 'privateValuesIncluded': False}
            validate_result(self.operation, self.context, self.documents, self.job, host, self.journal.entries, report, self.public)
            transport.exact_write(self.public / 'snapshot-target-service-verification.json', encoded(report)); self.checkpoint()
            return report
        except BaseException as error:
            code = error.code if isinstance(error, Failed) else 'SNAPSHOT_TARGET_READS_UNCONFIRMED'
            self.journal.add('TARGET_UNCONFIRMED', {'code': code, 'resourcesRetained': True, 'automaticTeardown': False, 'cancellationRequested': False})
            self.checkpoint()
            return {'state': 'SNAPSHOT_TARGET_SERVICE_UNCONFIRMED', 'failureCode': code, 'resourcesRetained': True}

    def close(self):
        self.checkpoint(); self.journal.close()


def validate_result(operation, context, documents, job, host, journal, report, public):
    """Offline final admission; every claim is derived from exact real outputs."""
    transport.require_public(report); snapshot_host.public_json(report)
    proofs = validate_proofs(operation, context, documents); validate_job(job)
    need(report.get('schemaVersion') == 1 and report.get('kind') == RECEIPT_KIND and report.get('state') == COMPLETE
         and report.get('sourceMode') == 'verified-global-b-snapshot'
         and all(report[k] == operation[k] for k in ('operationId', 'runId', 'datasetId', 'sourceArchiveSha256'))
         and report['operationSha256'] == operation_binding(operation) and report['completionContextSha256'] == sha(encoded(context))
         and report['toolSources'] == source_files() and report['completionLease'] == context['lease']
         and report['verificationLease'] == job['context']['lease'] and report['targetIdentity'] == proofs['hostReceipt']['targetIdentity']
         and report['inputReferences'] == {k: v['reference'] for k, v in documents.items()}, 'ACTUAL_TARGET_COMPLETION_BINDING_REQUIRED')
    identity = validate_identity(report['identity'], operation, context, proofs)
    need(identity == job['identity'] == host['identity'] and host.get('schemaVersion') == 1 and host.get('kind') == HOST_KIND + '-observation'
         and host.get('state') == 'TARGET_READ_OBSERVATIONS_COMPLETE' and host['operationSha256'] == operation_binding(operation)
         and host['jobSha256'] == report['jobSha256'] == sha(encoded(job))
         and report['hostObservationSha256'] == sha(encoded(host)) and host['sourceFiles'] == source_files()
         and host['lease'] == job['context']['lease'] and host['preparedFingerprintRef'] == documents['preparedFingerprint']['reference']
         and host['actualTargetUuidAndTlsBeforeAndAfterVerified'] is True
         and host['businessDatabaseWritesPerformed'] is False and host['cdcMutationPerformed'] is False
         and host['privateValuesIncluded'] is False and host['postReadAppAttestationRequired'] is True,
         'ACTUAL_TARGET_HOST_OBSERVATION_REQUIRED')
    need(report['before'] == job['before'] and report['before']['observation'] == report['after']['observation'], 'TARGET_READ_RUNTIME_BRACKETING_REQUIRED')
    for observation in (report['before'], report['after']):
        need(observation['operationSha256'] == operation_binding(operation), 'TARGET_APP_OPERATION_CHANGED')
        app_attestation(operation, identity['hosts'], identity['asg']['runtimeRevision'], proofs['readiness'],
                        {'passed': True, 'observation': observation['observation']}, observation['command'])
    before, after = report['before']['command'], report['after']['command']
    started, completed = (snapshot.instant(host[k]).timestamp() for k in ('startedAt', 'completedAt'))
    need(before['commandId'] != after['commandId'] and before['token'] < after['token']
         and before['completedEpoch'] <= int(started) <= completed <= host['deadlineEpoch'] == job['deadlineEpoch']
         and after['startedEpoch'] >= int(completed) and after['completedEpoch'] <= snapshot.instant(report['completedAt']).timestamp()
         and host['admissionEpoch'] >= before['completedEpoch'] and host['admissionEpoch'] - before['completedEpoch'] <= 120
         and snapshot.instant(proofs['preparation']['completedAt']).timestamp() <= started
         and snapshot.instant(report['completedAt']).timestamp() <= context['controllerDeadlineEpoch']
         and report['resourceExpiresAt'] == int(context['operator']['expiresAt'])
         and report['approvedExecutionDeadlineEpoch'] == context['approvedExecutionDeadlineEpoch'], 'TARGET_VERIFICATION_CHRONOLOGY_CHANGED')
    need(host['deadlineEpoch'] <= host['admissionEpoch'] + 1800
         and report['timings'] == {'targetPreparation': {k: proofs['snapshotOperation'][k] for k in (
             'fullValidationStartedAt', 'fullValidationCompletedAt', 'fullValidationSeconds',
             'preparationStartedAt', 'preparationCompletedAt', 'preparationSeconds')},
             'readStartedAt': host['startedAt'], 'readCompletedAt': host['completedAt']}, 'TARGET_PHASE_TIMINGS_CHANGED')
    finished = [e['data'] for e in journal if e['kind'] == 'COMMAND_COMPLETE' and e['data']['key'] == 'target-read-worker']
    need(len(finished) == 1 and finished[0]['command'] == report['hostCommand'] and finished[0]['value']['passed'] is True
         and finished[0]['value']['reference']['sha256'] == report['hostObservationSha256'], 'ACTUAL_TARGET_SSM_WORKER_COMPLETION_REQUIRED')
    submitted = [e['data'] for e in journal if e['kind'] == 'TARGET_WORKER_FIXED']
    need(len(submitted) == 1 and submitted[0]['jobSha256'] == report['jobSha256']
         and report['hostCommand']['status'] == 'Success'
         and report['hostCommand']['commandSha256'] == sha(transport.program(WORKER_BODY, submitted[0]['programInputs']).encode()), 'FIXED_TARGET_SSM_PROGRAM_REQUIRED')
    payload = submitted[0]['programInputs']; connection = identity['hosts']['connect']; command = report['hostCommand']
    need(payload['pin'] == connection and payload['lease'] == job['context']['lease'] and payload['sources'] == source_files()
         and payload['job']['sha256'] == report['jobSha256'] and payload['deadlineEpoch'] == job['deadlineEpoch']
         and command['instanceId'] == connection['instanceId'] and command['containerId'] == connection['containerId']
         and command['startedEpoch'] <= started <= completed < command['completedEpoch'] + 1,
         'TARGET_WORKER_TARGET_OR_LIFETIME_CHANGED')
    for field in (before, after):
        need(any(e['kind'] == 'TARGET_APP_OBSERVED' and e['data']['attestation']['command'] == field for e in journal), 'ACTUAL_TARGET_APP_SSM_EVIDENCE_REQUIRED')
    public = Path(public); artifacts = {}
    expected_files = PUBLIC_FILES - {'snapshot-target-service-verification.json'}
    need(set(report['artifacts']) == expected_files, 'TARGET_READ_REPORT_INVENTORY_CHANGED')
    for name in expected_files:
        raw = core.private_path(public / name).read_bytes(); snapshot_host.public_json(core.parse(raw))
        need(report['artifacts'][name] == {'sha256': sha(raw), 'bytes': len(raw)}, 'TARGET_READ_REPORT_BYTES_CHANGED')
        artifacts[name] = core.parse(raw)
    publication = proofs['provenance']['contract']['publication']
    inputs = {'configuration': {'datasetId': operation['datasetId'], 'consumerManifestSha256': publication['consumerManifestSha256'],
        'checksumsSha256': publication['checksumsSha256'],
        'accountEnvironment': 'aws:' + identity['rds']['resourceId'] + ':' + identity['rds']['serverUuid']}}
    reads.validate_read_observations(artifacts['representative-http-reads.json'], artifacts['warmup-receipt.json'], artifacts['media-availability.json'], inputs)
    need(host['observations']['readiness'] == {'status': 'UP', 'httpStatus': 200} and host['observations']['loopbackRelayClosed'] is True
         and all(host['observations'][key]['sha256'] == report['artifacts'][name]['sha256'] for key, name in (
             ('representatives', 'representative-http-reads.json'), ('warmup', 'warmup-receipt.json'), ('media', 'media-availability.json'))),
         'ACTUAL_TARGET_HTTP_OUTPUTS_REQUIRED')
    warm = artifacts['warmup-receipt.json']
    need(warm['preconditions']['fingerprintReceiptSha256'] == documents['snapshotOperation']['reference']['sha256']
         and warm['preconditions']['appImage'] == identity['hosts']['app']['image'] and warm['preconditions']['detailCacheState'] == 'DISABLED'
         and warm['scope']['businessWriteRequests'] == 0, 'SNAPSHOT_TARGET_WARMUP_PREREQUISITE_CHANGED')
    runtime = artifacts['host-runtime-qualification.json']
    need(host['hostRuntimeQualification']['sha256'] == report['artifacts']['host-runtime-qualification.json']['sha256']
         and runtime.get('kind') == 'airbob-growth-timezone-qualification' and runtime.get('state') == 'TIMEZONE_RUNTIME_QUALIFIED'
         and all(runtime.get(key) is True for key in ('passed', 'consumerRuntimePassed', 'allFixedCasesPassed', 'runtimeIdentityPassed', 'selectedZonesPassed'))
         and runtime.get('consumerHost', {}).get('execution') == 'current process host'
         and runtime.get('consumerReleaseBindings', {}).get('consumerHelperSha256') == contract.sha(runtime_gate.__file__),
         'ACTUAL_TARGET_HOST_RUNTIME_REQUIRED')
    expected_service = {'readinessVerified': True, 'representativeAccounts': 3, 'normalLoginAndOwnershipPassed': True,
        'publicAndGlobalSearchPassed': True, 'imagesSampled': True, 'reservableDatesPassed': True,
        'warmupSuccessfulReads': 300, 'detailCacheDisabledVerified': True, 'ownedSessionsInvalidated': True}
    need(report['service'] == expected_service and all(report.get(key) is False for key in (
        'databaseBusinessWritesPerformed', 'cdcMutationPerformed', 'sourceR4Claimed', 'snapshotCreated', 'asgCapacityChanged',
        'containersStartedOrStopped', 'privateValuesIncluded'))
         and report.get('preparedFingerprintScope') == 'target preparation before normal service reads', 'TARGET_READ_SCOPE_CHANGED')
    return report


def validate_completion(operation, context, output):
    validate_operation(operation)
    validate_context(context, operation, clock=lambda: context['controllerDeadlineEpoch'] - 1)
    public = Path(output).absolute() / 'public'
    value = core.parse(core.private_path(public / 'recovery.json').read_bytes())
    validate_recovery(value, operation, selected_resume=False)
    raw = core.private_path(public / 'snapshot-target-service-verification.json').read_bytes()
    saved = value['publicArtifacts'].get('snapshot-target-service-verification.json', {})
    need(saved.get('sha256') == sha(raw) and saved.get('bytes') == len(raw)
         and saved.get('base64') == base64.b64encode(raw).decode(), 'RECORDED_TARGET_COMPLETION_BYTES_CHANGED')
    report = core.parse(raw)
    need(value['hostObservation'] is not None and value['job'] is not None, 'ACTUAL_TARGET_HOST_RECEIPT_MISSING')
    return validate_result(operation, context, value['documents'], value['job'], value['hostObservation'], value['journal'], report, public)


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    package = sub.add_parser('package-sources'); package.add_argument('--output', type=Path, required=True)
    validate = sub.add_parser('validate-operation'); validate.add_argument('--operation', type=Path, required=True)
    validate.add_argument('--context', type=Path)
    for name in ('run', 'validate-completion'):
        command = sub.add_parser(name); command.add_argument('--operation', type=Path, required=True)
        command.add_argument('--context', type=Path, required=True); command.add_argument('--output', type=Path, required=True)
    host = sub.add_parser('host'); host.add_argument('--job', type=Path, required=True); host.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv); runner = None
    try:
        if args.command == 'package-sources':
            raw, meta = source_archive(); need(not args.output.exists(), 'NEW_TARGET_PACKAGE_REQUIRED')
            args.output.mkdir(mode=0o700); core.write_new(args.output / 'source.tar.gz', raw)
            core.write_new(args.output / 'source-package.json', encoded(meta)); print(json.dumps({'archiveSha256': meta['sha256'], 'awsCallsExecuted': False})); return 0
        if args.command == 'host':
            admitted = time.time(); report = host_verify(core.parse(core.private_path(args.job).read_bytes()), args.output, admitted=admitted)
            print(json.dumps({'state': report['state']})); return 0
        operation = validate_operation(core.parse(core.reads.read_bytes(args.operation)))
        context = core.parse(core.reads.read_bytes(args.context)) if args.context else None
        if args.command == 'validate-operation':
            if context: validate_context(context, operation)
            print(json.dumps({'state': 'SNAPSHOT_TARGET_SERVICE_OPERATION_VALIDATED', 'awsCallsExecuted': False})); return 0
        if args.command == 'validate-completion':
            report = validate_completion(operation, context, args.output); print(json.dumps({'state': report['state'], 'awsCallsExecuted': False})); return 0
        validate_context(context, operation); aws = ReadAws(context['controllerDeadlineEpoch'])
        controller.verify_environment({'executionCommit': operation['executionCommit']}, aws)
        recovery = None
        if 'resume' in operation:
            ref = operation['resume']['recovery']; args.output.mkdir(mode=0o700, exist_ok=True)
            path = args.output / 'selected-recovery.json'; need(not path.exists(), 'NEW_TARGET_RECOVERY_PATH_REQUIRED')
            response = aws.call('s3api', 'get-object', '--bucket', service.EVIDENCE, '--key', ref['key'], '--version-id', ref['versionId'], str(path))
            need(response.get('VersionId') == ref['versionId'], 'TARGET_RECOVERY_VERSION_CHANGED'); path.chmod(0o600)
            raw = core.private_path(path).read_bytes(); need(len(raw) == ref['bytes'] and sha(raw) == ref['sha256'], 'TARGET_RECOVERY_BYTES_CHANGED')
            recovery = core.parse(raw)
        runner = Runner(operation, context, args.output, aws=aws, recovery=recovery)
        report = runner.run(); print(json.dumps({'state': report['state'], 'failureCode': report.get('failureCode')})); return 0 if report['state'] == COMPLETE else 1
    except BaseException as error:
        code = error.code if isinstance(error, Failed) else 'SNAPSHOT_TARGET_SERVICE_UNCONFIRMED'
        print(json.dumps({'state': 'SNAPSHOT_TARGET_SERVICE_UNCONFIRMED', 'failureCode': code, 'resourcesRetained': True})); return 1
    finally:
        if runner: runner.close()


if __name__ == '__main__':
    raise SystemExit(main())
