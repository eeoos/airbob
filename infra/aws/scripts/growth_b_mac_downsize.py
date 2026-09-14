#!/usr/bin/env python3
"""Offline Mac RDS downsize request checks and completion receipt.

No AWS, Terraform, SQL or subprocess is invoked. Raw Terraform/RDS responses
stay in caller memory (or bounded stdin). Only closed non-secret projections
are returned/written. The caller owns the existing Mac lock, orchestration
lease, admin credentials and actual API/Terraform execution.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import time
import uuid

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
LARGE = 'db.m6i.large'
SMALL = 'db.t3.small'
TAG = 'BDatabaseClass'
ADDRESS = 'module.rds[0].aws_db_instance.this'
KIND = 'global-b-mac-rds-downsize'
PLAN_EVIDENCE = 'terraform-full-plan'
API_EVIDENCE = 'aws-requests-with-terraform-refresh-only'
SOURCE_MAX_AGE = 60
MAX_INPUT = 64 * 1024**2
OP_FIELDS = ('schemaVersion', 'runId', 'expiresAt', 'fencingToken', 'mode', 'policy',
    'dnsMode', 'albIngressCidr', 'imageDigest', 'datasetRelease', 'datasetManifestVersionId',
    'bundleCommit', 'bundleSha256', 'datasetManifestSha256', 'amiId', 'rdsEngineVersion',
    'databaseBootstrap', 'cacheEnabled', 'requestTarget', 'loadGeneratorEnabled',
    'appImageReference', 'infraImageReferences', 'globalBPrepareOnly', 'globalBImportFromMac',
    'rdsInstanceClass', 'approvedExecutionDeadlineEpoch')
SNAPSHOT_FIELDS = ('rdsSnapshotIdentifier', 'rdsSnapshotSourceRunId', 'rdsSnapshotSourceResourceId')
IMAGE_KEYS = {'REDIS_IMAGE', 'REDIS_EXPORTER_IMAGE', 'NODE_EXPORTER_IMAGE', 'KAFKA_IMAGE',
    'DEBEZIUM_IMAGE', 'ELASTICSEARCH_IMAGE', 'ELASTICSEARCH_EXPORTER_IMAGE', 'PROMETHEUS_IMAGE', 'GRAFANA_IMAGE'}
PHASE2_FIELDS = ('run_id', 'fencing_token', 'deployment_phase', 'nat_instance_id',
                 'expected_network_receipt_key', 'services', 'probe_enabled')
# Provider-computed observations may become unknown during an in-place update.
# Configurable settings, including optional/computed IOPS, are never exempted.
COMPUTED = {'id', 'arn', 'address', 'endpoint', 'resource_id', 'latest_restorable_time',
            'status', 'engine_version_actual', 'hosted_zone_id', 'master_user_secret'}


class Rejected(ValueError):
    pass


def need(value, code):
    if not value:
        raise Rejected(code)


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def sha256(value):
    need(isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value), 'SHA256_REQUIRED')
    return value


def source_sha():
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def project_operator(operator):
    need(isinstance(operator, dict) and all(k in operator for k in OP_FIELDS), 'ORIGINAL_OPERATOR_REQUIRED')
    op = {k: copy.deepcopy(operator[k]) for k in OP_FIELDS}
    need(op['schemaVersion'] == 2 and op['globalBPrepareOnly'] is True
         and op['globalBImportFromMac'] is True and op['rdsInstanceClass'] == LARGE
         and op['rdsEngineVersion'] == '8.4.11' and op['databaseBootstrap'] == 'dump'
         and op['mode'] == 'performance' and op['policy'] == 'isolated-read'
         and op['dnsMode'] == 'direct-only' and op['cacheEnabled'] is False
         and op['loadGeneratorEnabled'] is False and op['requestTarget'] in (None, ''), 'ORIGINAL_MAC_RUN_REQUIRED')
    need(all(operator.get(k, '') == '' for k in SNAPSHOT_FIELDS), 'SNAPSHOT_SOURCE_FORBIDDEN')
    need(isinstance(op['runId'], str) and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', op['runId'])
         and '--' not in op['runId'] and not op['runId'].endswith('-'), 'RUN_ID_REQUIRED')
    need(isinstance(op['datasetRelease'], str) and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', op['datasetRelease']), 'DATASET_REQUIRED')
    need(type(op['fencingToken']) is int and op['fencingToken'] > 0
         and isinstance(op['expiresAt'], str) and re.fullmatch(r'[1-9][0-9]{9}', op['expiresAt'])
         and type(op['approvedExecutionDeadlineEpoch']) is int
         and int(op['expiresAt']) <= op['approvedExecutionDeadlineEpoch'], 'ORIGINAL_FENCE_AND_EXPIRY_REQUIRED')
    for key in ('bundleSha256', 'datasetManifestSha256'):
        sha256(op[key])
    need(re.fullmatch(r'[0-9a-f]{40}', op['bundleCommit'] or '')
         and re.fullmatch(r'ami-[0-9a-f]{8,17}', op['amiId'] or ''), 'ORIGINAL_IMAGE_RELEASE_REQUIRED')
    need(isinstance(op['datasetManifestVersionId'], str)
         and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', op['datasetManifestVersionId'])
         and op['datasetManifestVersionId'] not in ('null', 'None'), 'MANIFEST_VERSION_REQUIRED')
    image_prefix = ACCOUNT + r'\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/'
    need(isinstance(op['appImageReference'], str)
         and re.fullmatch(image_prefix + r'airbob-repo@sha256:[0-9a-f]{64}', op['appImageReference'])
         and op['appImageReference'].split('@')[1] == op['imageDigest'], 'APP_IMAGE_BINDING_REQUIRED')
    need(isinstance(op['infraImageReferences'], dict) and set(op['infraImageReferences']) == IMAGE_KEYS
         and all(isinstance(v, str) and re.fullmatch(image_prefix + r'airbob-infra/[a-z0-9-]+@sha256:[0-9a-f]{64}', v)
                 for v in op['infraImageReferences'].values()), 'INFRA_IMAGE_BINDING_REQUIRED')
    need(isinstance(op['albIngressCidr'], str) and re.fullmatch(r'[0-9.]+/32', op['albIngressCidr']), 'ORIGINAL_INGRESS_REQUIRED')
    return op


def expected_tags(op, chosen):
    tags = {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'lab',
            'ManagedBy': 'terraform', 'Persistence': 'ephemeral', 'ExpiresAt': op['expiresAt'],
            'RunId': op['runId'], 'FencingToken': str(op['fencingToken']), 'Service': 'rds'}
    if chosen == LARGE:
        tags[TAG] = LARGE
    return tags


def outputs(state):
    need(state.get('version') == 4 and isinstance(state.get('lineage'), str)
         and type(state.get('serial')) is int and state['serial'] >= 0, 'TERRAFORM_STATE_REQUIRED')
    return {name: item['value'] for name, item in state['outputs'].items()}


def rds_resource(state):
    rows = [r for r in state['resources'] if r.get('mode') == 'managed' and r.get('type') == 'aws_db_instance']
    need(len(rows) == 1 and rows[0].get('module') == 'module.rds[0]' and rows[0].get('name') == 'this'
         and len(rows[0]['instances']) == 1, 'SINGLE_EXISTING_RDS_REQUIRED')
    return rows[0]['instances'][0]['attributes']


def configuration_sha(row):
    return digest({k: v for k, v in row.items() if k not in COMPUTED | {'instance_class', 'tags', 'tags_all'}})


def check_resource(row, op, target, chosen):
    fixed = {'id': target['resourceId'], 'resource_id': target['resourceId'],
        'identifier': 'airbob-' + op['runId'], 'arn': target['arn'], 'address': target['endpoint'],
        'instance_class': chosen, 'engine': 'mysql', 'engine_version': '8.4.11', 'allocated_storage': 100,
        'storage_type': 'gp3', 'storage_encrypted': True, 'publicly_accessible': False, 'multi_az': False,
        'manage_master_user_password': True, 'port': 3306}
    need(all(row.get(k) == v and type(row.get(k)) is type(v) for k, v in fixed.items()), 'RDS_IDENTITY_OR_CONFIGURATION_CHANGED')
    need(row.get('tags') == row.get('tags_all') == expected_tags(op, chosen), 'RDS_TAGS_FENCE_OR_EXPIRY_CHANGED')


def check_live_rds(observation, op, target, chosen):
    rows = observation.get('DBInstances', [])
    need(isinstance(rows, list) and len(rows) == 1, 'EXACT_RDS_OBSERVATION_REQUIRED')
    row = rows[0]
    fixed = {'DBInstanceIdentifier': target['identifier'], 'DbiResourceId': target['resourceId'], 'DBInstanceArn': target['arn'],
        'DBInstanceClass': chosen, 'DBInstanceStatus': 'available', 'Engine': 'mysql', 'EngineVersion': '8.4.11',
        'AllocatedStorage': 100, 'StorageType': 'gp3', 'StorageEncrypted': True, 'PubliclyAccessible': False, 'MultiAZ': False}
    need(all(row.get(k) == v and type(row.get(k)) is type(v) for k, v in fixed.items())
         and row.get('PendingModifiedValues') == {}
         and row.get('Endpoint', {}).get('Address') == target['endpoint']
         and type(row.get('Endpoint', {}).get('Port')) is int and row['Endpoint']['Port'] == 3306,
         'AVAILABLE_EXACT_RDS_WITHOUT_PENDING_CHANGES_REQUIRED')
    tags = {item['Key']: item['Value'] for item in row.get('TagList', [])}
    need(len(tags) == len(row.get('TagList', [])) and tags == expected_tags(op, chosen), 'OBSERVED_RDS_TAGS_CHANGED')


def check_topology(out, op, chosen):
    phase2, phase3, phase4 = (out[k] for k in ('phase2_contract', 'phase3_contract', 'phase4_contract'))
    need(out['run_identity'] == {'run_id': op['runId'], 'resource_fencing_token': op['fencingToken']}
         and phase2['run_id'] == op['runId'] and phase2['fencing_token'] == op['fencingToken']
         and phase2['deployment_phase'] == 'services' and phase2['services'] == {}
         and phase2['probe_enabled'] is False, 'ORIGINAL_MAC_TOPOLOGY_REQUIRED')
    need(phase4['app_enabled'] is False and phase4['load_generator_enabled'] is False
         and phase4['capacity'] == {'min': 0, 'desired': 0, 'max': 0}
         and all(phase4.get(k) is None for k in ('alb_arn', 'target_group_arn', 'auto_scaling_group_name', 'load_generator_instance_id')),
         'WRITER_TOPOLOGY_FORBIDDEN')
    need(phase3['dataset_release'] == op['datasetRelease'] and phase3['database_bootstrap'] == 'dump'
         and phase3['rds_engine_version'] == '8.4.11' and phase3['rds_instance_class'] == chosen
         and phase3['rds_configured_storage_gib'] == phase3['rds_allocated_storage_gib'] == 100
         and phase3['data_ready'] is False, 'ORIGINAL_RDS_OUTPUT_REQUIRED')
    target = {'identifier': phase3['rds_instance_id'], 'resourceId': phase3['rds_resource_id'],
              'endpoint': phase3['rds_endpoint'], 'arn': f'arn:aws:rds:{REGION}:{ACCOUNT}:db:airbob-{op["runId"]}'}
    need(target['identifier'] == 'airbob-' + op['runId']
         and re.fullmatch(r'db-[A-Z0-9]+', target['resourceId'])
         and re.fullmatch(r'[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', target['endpoint'])
         and re.fullmatch(r'i-[0-9a-f]{8,17}', phase2['nat_instance_id']), 'EXACT_TARGET_REQUIRED')
    return target, {k: phase2[k] for k in PHASE2_FIELDS}


def tfvars_for(op, topology, lease_owner):
    need(isinstance(lease_owner, str) and re.fullmatch(r'[A-Za-z0-9._:@/-]{3,128}', lease_owner), 'CURRENT_LEASE_OWNER_REQUIRED')
    match = re.fullmatch(r'network-receipts/' + re.escape(op['runId']) + r'/(i-[0-9a-f]{8,17})\.json', topology['expected_network_receipt_key'])
    need(match is not None, 'ORIGINAL_PROBE_RECEIPT_REQUIRED')
    mapping = {'run_id': 'runId', 'expires_at': 'expiresAt', 'fencing_token': 'fencingToken', 'ami_id': 'amiId',
        'bundle_commit': 'bundleCommit', 'bundle_sha256': 'bundleSha256', 'infra_image_references': 'infraImageReferences',
        'app_image_reference': 'appImageReference', 'mode': 'mode', 'measurement_policy': 'policy',
        'accommodation_detail_cache_enabled': 'cacheEnabled', 'dataset_release': 'datasetRelease',
        'dataset_manifest_sha256': 'datasetManifestSha256', 'database_bootstrap': 'databaseBootstrap',
        'rds_engine_version': 'rdsEngineVersion', 'dns_mode': 'dnsMode', 'alb_ingress_cidr': 'albIngressCidr',
        'global_b_manifest_version_id': 'datasetManifestVersionId'}
    result = {k: copy.deepcopy(op[v]) for k, v in mapping.items()}
    result.update(deployment_phase='services', verified_probe_instance_id=match[1], app_enabled=False,
        request_count_per_target_per_minute=None, load_generator_enabled=False, rds_instance_class=SMALL,
        rds_snapshot_identifier='', rds_snapshot_source_run_id='', rds_snapshot_source_resource_id='',
        global_b_prepare_only=True, global_b_import_from_mac=True, global_b_services=False,
        global_b_service_release='', global_b_service_bootstrap_enabled=False, global_b_readiness_receipt=None,
        global_b_snapshot_restore_only=False, global_b_snapshot_provenance=None,
        global_b_lease_owner=lease_owner, global_b_lease_fencing_token=0)
    return result


def prepare_inputs(operator, state, sql_complete, postcheck, *, operator_sha256,
                   sql_import_sha256, postcheck_sha256, expected_dump_sha256, lease_owner, now=None):
    op = project_operator(operator)
    now = int(time.time()) if now is None else now
    need(now < int(op['expiresAt']), 'ORIGINAL_RESOURCE_EXPIRY_REACHED')
    target, topology = check_topology(outputs(state), op, LARGE)
    check_resource(rds_resource(state), op, target, LARGE)
    for value in (operator_sha256, sql_import_sha256, postcheck_sha256):
        sha256(value)
    need(sql_complete.get('schemaVersion') == 1 and sql_complete.get('kind') == 'global-b-mac-sql-import'
         and sql_complete.get('state') == 'SQL_IMPORT_COMPLETED'
         and sql_complete.get('automaticReplayAllowed') is False and sql_complete.get('fullDatasetValidated') is False,
         'SUCCESSFUL_MAC_SQL_IMPORT_REQUIRED')
    binding = sql_complete['binding']; stream = sql_complete['stream']
    need(all(binding.get(k) == v for k, v in {'accountId': ACCOUNT, 'region': REGION, 'identifier': target['identifier'],
        'resourceId': target['resourceId'], 'endpoint': target['endpoint'], 'engineVersion': '8.4.11', 'importClass': LARGE,
        'allocatedStorageGiB': 100, 'schema': 'airbobdb', 'sqlElapsedTimeoutSeconds': None}.items()), 'SQL_TARGET_BINDING_CHANGED')
    need(sha256(binding['dumpSha256']) == sha256(expected_dump_sha256), 'SEALED_DUMP_SHA_CHANGED')
    need(stream.get('state') == 'SQL_IMPORT_COMPLETED' and stream.get('mysqlExitCode') == stream.get('gzipExitCode') == 0
         and type(stream.get('mysqlExitCode')) is int and type(stream.get('gzipExitCode')) is int
         and type(stream.get('compressedBytesTotal')) is int and stream['compressedBytesTotal'] > 0
         and stream.get('compressedBytesRead') == stream['compressedBytesTotal'], 'SQL_STREAM_COMPLETION_REQUIRED')
    need(postcheck.get('state') == 'SQL_IMPORT_POSTCHECK_PASSED' and postcheck.get('binding') == binding
         and postcheck.get('sqlImportReceiptSha256') == sql_import_sha256
         and postcheck.get('rds') == sql_complete.get('rds') and postcheck.get('database') == sql_complete.get('database')
         and postcheck.get('flywayVersion') == 28 and postcheck.get('failedMigrations') == 0
         and postcheck.get('representativePrimaryKeysObserved') is True and postcheck.get('sqlReplayed') is False
         and postcheck.get('fullDatasetValidated') is False and type(postcheck.get('tableObjects')) is int
         and postcheck['tableObjects'] > 0, 'SUCCESSFUL_SAME_IMPORT_POSTCHECK_REQUIRED')
    server_uuid = sql_complete['database']['serverUuid']
    need(str(uuid.UUID(server_uuid)) == server_uuid and sql_complete['database']['engineVersion'] == '8.4.11', 'IMPORTED_DATABASE_UUID_REQUIRED')
    need(all(sql_complete['rds'].get(k) == v for k, v in {'identifier': target['identifier'], 'resourceId': target['resourceId'],
        'endpoint': target['endpoint'], 'instanceClass': LARGE, 'engineVersion': '8.4.11', 'allocatedStorageGiB': 100,
        'resourceExpiresAtEpoch': int(op['expiresAt'])}.items()), 'SQL_RDS_METADATA_CHANGED')
    target['serverUuid'] = server_uuid
    tfvars = tfvars_for(op, topology, lease_owner)
    request = {'schemaVersion': 1, 'kind': KIND + '-request', 'operator': op, 'operatorSha256': operator_sha256,
        'rds': target, 'topology': topology, 'stateBefore': {'lineage': state['lineage'], 'serial': state['serial'], 'sha256': digest(state)},
        'sqlImportSha256': sql_import_sha256, 'postcheckSha256': postcheck_sha256, 'dumpSha256': binding['dumpSha256'],
        'tfvars': tfvars, 'tfvarsSha256': digest(tfvars),
        'rdsConfigurationSha256': configuration_sha(rds_resource(state)), 'preparedAtEpoch': now}
    return {'tfvars': tfvars, 'request': request}


def validate_request(request):
    need(set(request) == {'schemaVersion', 'kind', 'operator', 'operatorSha256', 'rds', 'topology', 'stateBefore',
        'sqlImportSha256', 'postcheckSha256', 'dumpSha256', 'tfvars', 'tfvarsSha256', 'rdsConfigurationSha256', 'preparedAtEpoch'}
        and request['schemaVersion'] == 1 and request['kind'] == KIND + '-request', 'DOWNSIZE_REQUEST_REQUIRED')
    op = project_operator(request['operator'])
    need(request['tfvars'] == tfvars_for(op, request['topology'], request['tfvars']['global_b_lease_owner'])
         and digest(request['tfvars']) == request['tfvarsSha256'], 'RECONSTRUCTED_TFVARS_CHANGED')
    for key in ('operatorSha256', 'sqlImportSha256', 'postcheckSha256', 'dumpSha256', 'rdsConfigurationSha256'):
        sha256(request[key])
    return op


def unknown(value):
    return any(unknown(v) for v in value.values()) if isinstance(value, dict) else (
        any(unknown(v) for v in value) if isinstance(value, list) else value is True)


def check_api_scope(target, modify_request, remove_tags_request):
    need(isinstance(modify_request, dict) and set(modify_request) == {
        'DBInstanceIdentifier', 'DBInstanceClass', 'ApplyImmediately'}
        and modify_request['DBInstanceIdentifier'] == target['identifier']
        and modify_request['DBInstanceClass'] == SMALL and modify_request['ApplyImmediately'] is True,
        'ONLY_EXACT_RDS_CLASS_MODIFY_ALLOWED')
    need(isinstance(remove_tags_request, dict) and set(remove_tags_request) == {'ResourceName', 'TagKeys'}
         and remove_tags_request['ResourceName'] == target['arn'] and remove_tags_request['TagKeys'] == [TAG],
         'ONLY_EXACT_RDS_CLASS_TAG_REMOVAL_ALLOWED')


def check_api_evidence(evidence, target):
    need(isinstance(evidence, dict) and set(evidence) == {'sourceObservationSha256', 'sourceObservedAtEpoch',
        'modifyDBInstance', 'removeTagsFromResource'}, 'EXACT_API_REQUEST_EVIDENCE_REQUIRED')
    sha256(evidence['sourceObservationSha256'])
    need(type(evidence['sourceObservedAtEpoch']) is int, 'SOURCE_OBSERVATION_TIME_REQUIRED')
    check_api_scope(target, evidence['modifyDBInstance'], evidence['removeTagsFromResource'])


def validate_api_requests(request, source_rds, modify_request, remove_tags_request, *, observed_at_epoch, now=None):
    """Check an exact admin API request pair; do not submit either request."""
    op = validate_request(request)
    now = int(time.time()) if now is None else now
    need(type(observed_at_epoch) is int and type(now) is int
         and request['preparedAtEpoch'] <= observed_at_epoch <= now < int(op['expiresAt'])
         and now - observed_at_epoch <= SOURCE_MAX_AGE, 'FRESH_LARGE_SOURCE_WITHIN_ORIGINAL_WINDOW_REQUIRED')
    check_live_rds(source_rds, op, request['rds'], LARGE)
    check_api_scope(request['rds'], modify_request, remove_tags_request)
    return {'schemaVersion': 1, 'kind': KIND + '-api-requests', 'state': 'EXACT_RDS_API_REQUESTS_VERIFIED',
        'planEvidenceKind': API_EVIDENCE, 'requestSha256': digest(request), 'planJsonSha256': None,
        'savedPlanSha256': None, 'savedPlanBytes': None, 'validatedAtEpoch': now,
        'apiRequestEvidence': {'sourceObservationSha256': digest(source_rds), 'sourceObservedAtEpoch': observed_at_epoch,
            'modifyDBInstance': copy.deepcopy(modify_request), 'removeTagsFromResource': copy.deepcopy(remove_tags_request)}}


def validate_plan(request, plan, *, saved_plan_sha256=None, saved_plan_bytes=None, now=None):
    op = validate_request(request)
    now = int(time.time()) if now is None else now
    need(request['preparedAtEpoch'] <= now < int(op['expiresAt']), 'DOWNSIZE_PLAN_OUTSIDE_ORIGINAL_WINDOW')
    if saved_plan_sha256 is None:
        need(saved_plan_bytes is None, 'PLAN_FILE_BINDING_INCOMPLETE')
    else:
        sha256(saved_plan_sha256)
        need(type(saved_plan_bytes) is int and saved_plan_bytes > 0, 'PLAN_FILE_BINDING_INCOMPLETE')
    need(plan.get('errored') is not True, 'ERROR_PLAN_FORBIDDEN')
    need(plan.get('terraform_version') == '1.15.5' and plan.get('applyable', True) is True
         and plan.get('complete', True) is True and not plan.get('deferred_changes'), 'PINNED_COMPLETE_TERRAFORM_PLAN_REQUIRED')
    variables = {k: item['value'] for k, item in plan['variables'].items()}
    need(all(variables.get(k) == v for k, v in request['tfvars'].items()), 'PLAN_INPUTS_CHANGED')
    changes = [r for r in plan.get('resource_changes', []) if r.get('mode') == 'managed' and r['change']['actions'] != ['no-op']]
    need(len(changes) == 1 and changes[0].get('address') == ADDRESS and changes[0].get('type') == 'aws_db_instance'
         and changes[0]['change']['actions'] == ['update'], 'ONLY_ONE_IN_PLACE_RDS_UPDATE_ALLOWED')
    change = changes[0]['change']; before, after = change['before'], change['after']
    check_resource(before, op, request['rds'], LARGE)
    need(configuration_sha(before) == request['rdsConfigurationSha256'], 'BASELINE_RDS_CONFIGURATION_DRIFTED')
    need(after.get('instance_class') == SMALL and after.get('tags') == after.get('tags_all') == expected_tags(op, SMALL),
         'ONLY_LARGE_TO_SMALL_AND_CLASS_TAG_REMOVAL_ALLOWED')
    uncertain = change.get('after_unknown', {})
    need(not any(unknown(v) for k, v in uncertain.items() if k not in COMPUTED), 'UNKNOWN_CONFIGURABLE_CHANGE')
    need(not change.get('replace_paths') and not changes[0].get('previous_address'), 'REPLACEMENT_OR_MOVE_FORBIDDEN')
    for key in set(before) | set(after):
        if key in ('instance_class', 'tags', 'tags_all'):
            continue
        if key in COMPUTED and unknown(uncertain.get(key)):
            continue
        need(before.get(key) == after.get(key), 'NON_CLASS_RDS_SETTING_CHANGED')
    return {'schemaVersion': 1, 'kind': KIND + '-plan', 'state': 'SINGLE_RDS_DOWNSIZE_PLAN_VERIFIED',
        'planEvidenceKind': PLAN_EVIDENCE, 'apiRequestEvidence': None,
        'requestSha256': digest(request), 'planJsonSha256': digest(plan), 'savedPlanSha256': saved_plan_sha256,
        'savedPlanBytes': saved_plan_bytes, 'validatedAtEpoch': now}


def validate_approval(request, approval, op, now):
    need(set(approval) == {'schemaVersion', 'kind', 'state', 'requestSha256', 'planJsonSha256', 'savedPlanSha256',
        'savedPlanBytes', 'validatedAtEpoch', 'planEvidenceKind', 'apiRequestEvidence'}
         and approval['schemaVersion'] == 1 and approval['requestSha256'] == digest(request)
         and type(approval['validatedAtEpoch']) is int
         and request['preparedAtEpoch'] <= approval['validatedAtEpoch'] <= now
         and approval['validatedAtEpoch'] < int(op['expiresAt']), 'EXACT_VERIFIED_DOWNSIZE_APPROVAL_REQUIRED')
    if approval['planEvidenceKind'] == PLAN_EVIDENCE:
        need(approval['kind'] == KIND + '-plan' and approval['state'] == 'SINGLE_RDS_DOWNSIZE_PLAN_VERIFIED'
             and approval['apiRequestEvidence'] is None, 'EXACT_VERIFIED_PLAN_REQUIRED')
        sha256(approval['planJsonSha256'])
        if approval['savedPlanSha256'] is None:
            need(approval['savedPlanBytes'] is None, 'PLAN_FILE_BINDING_INCOMPLETE')
        else:
            sha256(approval['savedPlanSha256'])
            need(type(approval['savedPlanBytes']) is int and approval['savedPlanBytes'] > 0, 'PLAN_FILE_BINDING_INCOMPLETE')
    else:
        need(approval['planEvidenceKind'] == API_EVIDENCE and approval['kind'] == KIND + '-api-requests'
             and approval['state'] == 'EXACT_RDS_API_REQUESTS_VERIFIED'
             and all(approval[k] is None for k in ('planJsonSha256', 'savedPlanSha256', 'savedPlanBytes')),
             'API_REQUEST_APPROVAL_CANNOT_CLAIM_TERRAFORM_PLAN')
        evidence = approval['apiRequestEvidence']
        check_api_evidence(evidence, request['rds'])
        need(request['preparedAtEpoch'] <= evidence['sourceObservedAtEpoch'] <= approval['validatedAtEpoch']
             and approval['validatedAtEpoch'] - evidence['sourceObservedAtEpoch'] <= SOURCE_MAX_AGE,
             'FRESH_LARGE_SOURCE_WITHIN_ORIGINAL_WINDOW_REQUIRED')


def complete_transition(request, approval, state_after, rds_after, database_after, *, now=None):
    op = validate_request(request)
    now = int(time.time()) if now is None else now
    validate_approval(request, approval, op, now)
    need(state_after['lineage'] == request['stateBefore']['lineage']
         and state_after['serial'] > request['stateBefore']['serial'], 'SAME_TERRAFORM_STATE_ADVANCE_REQUIRED')
    target, topology = check_topology(outputs(state_after), op, SMALL)
    need(topology == request['topology'] and all(request['rds'][k] == v for k, v in target.items()), 'TARGET_OR_NAT_CHANGED')
    check_resource(rds_resource(state_after), op, target, SMALL)
    need(configuration_sha(rds_resource(state_after)) == request['rdsConfigurationSha256'], 'FINAL_RDS_CONFIGURATION_DRIFTED')
    check_live_rds(rds_after, op, target, SMALL)
    need(set(database_after) == {'serverUuid', 'engineVersion', 'tlsCipher'}
         and database_after['serverUuid'] == request['rds']['serverUuid'] and database_after['engineVersion'] == '8.4.11'
         and isinstance(database_after['tlsCipher'], str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', database_after['tlsCipher']),
         'SAME_DATABASE_UUID_AND_TLS_REQUIRED')
    receipt = {'schemaVersion': 1, 'kind': KIND, 'state': 'SAME_RDS_DOWNSIZED_AND_TERRAFORM_ALIGNED',
        'sourceSha256': source_sha(), 'operatorSha256': request['operatorSha256'], 'operator': op,
        'rds': request['rds'], 'fromClass': LARGE, 'toClass': SMALL,
        'sqlImportSha256': request['sqlImportSha256'], 'postcheckSha256': request['postcheckSha256'],
        'planEvidenceKind': approval['planEvidenceKind'], 'apiRequestEvidence': copy.deepcopy(approval['apiRequestEvidence']),
        'approvalSha256': digest(approval),
        'requestSha256': digest(request), 'planJsonSha256': approval['planJsonSha256'], 'savedPlanSha256': approval['savedPlanSha256'],
        'tfvarsSha256': request['tfvarsSha256'], 'rdsConfigurationSha256': request['rdsConfigurationSha256'], 'stateBefore': request['stateBefore'],
        'stateAfter': {'lineage': state_after['lineage'], 'serial': state_after['serial'], 'sha256': digest(state_after)},
        'completedAtEpoch': now, 'sqlReplayed': False, 'fullDatasetValidated': False, 'servicesStarted': False}
    validate_receipt(operator=op, receipt=receipt, operator_sha256=request['operatorSha256'])
    return receipt


def validate_receipt(operator, receipt, operator_sha256):
    expected = {'schemaVersion', 'kind', 'state', 'sourceSha256', 'operatorSha256', 'operator', 'rds', 'fromClass', 'toClass',
        'sqlImportSha256', 'postcheckSha256', 'requestSha256', 'planJsonSha256', 'savedPlanSha256', 'tfvarsSha256',
        'planEvidenceKind', 'apiRequestEvidence', 'approvalSha256',
        'rdsConfigurationSha256', 'stateBefore', 'stateAfter', 'completedAtEpoch', 'sqlReplayed', 'fullDatasetValidated', 'servicesStarted'}
    need(set(receipt) == expected and receipt['schemaVersion'] == 1 and receipt['kind'] == KIND
         and receipt['state'] == 'SAME_RDS_DOWNSIZED_AND_TERRAFORM_ALIGNED'
         and receipt['operator'] == project_operator(operator) and receipt['operatorSha256'] == sha256(operator_sha256)
         and receipt['sourceSha256'] == source_sha() and receipt['fromClass'] == LARGE and receipt['toClass'] == SMALL
         and all(receipt[k] is False for k in ('sqlReplayed', 'fullDatasetValidated', 'servicesStarted')), 'EXACT_MAC_DOWNSIZE_RECEIPT_REQUIRED')
    for key in expected:
        if key.endswith('Sha256') and not (key in ('savedPlanSha256', 'planJsonSha256') and receipt[key] is None):
            sha256(receipt[key])
    target = receipt['rds']; op = receipt['operator']
    need(set(target) == {'identifier', 'resourceId', 'endpoint', 'arn', 'serverUuid'}
         and target['identifier'] == 'airbob-' + op['runId']
         and target['arn'] == f'arn:aws:rds:{REGION}:{ACCOUNT}:db:{target["identifier"]}'
         and re.fullmatch(r'db-[A-Z0-9]+', target['resourceId'])
         and re.fullmatch(r'[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', target['endpoint'])
         and str(uuid.UUID(target['serverUuid'])) == target['serverUuid'], 'DOWNSIZE_TARGET_REQUIRED')
    if receipt['planEvidenceKind'] == PLAN_EVIDENCE:
        need(receipt['apiRequestEvidence'] is None, 'PLAN_CANNOT_CLAIM_API_REQUEST_EVIDENCE')
        sha256(receipt['planJsonSha256'])
    else:
        need(receipt['planEvidenceKind'] == API_EVIDENCE and receipt['planJsonSha256'] is None
             and receipt['savedPlanSha256'] is None, 'API_REQUEST_RECEIPT_CANNOT_CLAIM_TERRAFORM_PLAN')
        check_api_evidence(receipt['apiRequestEvidence'], target)
        need(receipt['apiRequestEvidence']['sourceObservedAtEpoch'] <= receipt['completedAtEpoch'],
             'SOURCE_OBSERVATION_AFTER_COMPLETION')
    before, after = receipt['stateBefore'], receipt['stateAfter']
    need(set(before) == set(after) == {'lineage', 'serial', 'sha256'} and before['lineage'] == after['lineage']
         and type(before['serial']) is int and type(after['serial']) is int and after['serial'] > before['serial']
         and type(receipt['completedAtEpoch']) is int, 'DOWNSIZE_STATE_LINEAGE_REQUIRED')
    sha256(before['sha256']); sha256(after['sha256'])
    return receipt


def reference(value, operator):
    op = project_operator(operator)
    need(isinstance(value, dict) and set(value) == {'key', 'versionId', 'sha256', 'bytes'}
         and value['key'] == f'data-bootstrap/{op["runId"]}/{op["datasetRelease"]}-mac-rds-downsize.json'
         and isinstance(value['versionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', value['versionId'])
         and value['versionId'] not in ('null', 'None') and type(value['bytes']) is int and 0 < value['bytes'] <= 128 * 1024,
         'EXACT_MAC_DOWNSIZE_REFERENCE_REQUIRED')
    sha256(value['sha256'])
    return value


def _pairs(items):
    value = {}
    for key, item in items:
        need(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def read_json(stream):
    raw = stream.read(MAX_INPUT + 1)
    need(len(raw) <= MAX_INPUT, 'INPUT_TOO_LARGE')
    return json.loads(raw, object_pairs_hook=_pairs, parse_constant=lambda _: (_ for _ in ()).throw(Rejected('NONFINITE_JSON')))


def write_new(path, value):
    path = Path(path)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as output:
        need(stat.S_ISREG(os.fstat(output.fileno()).st_mode), 'REGULAR_OUTPUT_REQUIRED')
        output.write(encoded(value)); output.flush(); os.fsync(output.fileno())


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=('prepare', 'plan', 'api-requests', 'complete'))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        payload = read_json(sys.stdin.buffer)
        need('now' not in payload, 'CLI_CLOCK_OVERRIDE_FORBIDDEN')
        if args.mode == 'prepare':
            result = prepare_inputs(**payload)
        elif args.mode == 'plan':
            result = validate_plan(**payload)
        elif args.mode == 'api-requests':
            result = validate_api_requests(**payload)
        else:
            result = complete_transition(**payload)
        write_new(args.output, result)
        print(json.dumps({'state': 'OFFLINE_DOWNSIZE_' + args.mode.upper() + '_VERIFIED'}))
        return 0
    except (ValueError, TypeError, KeyError, OSError) as error:
        print(json.dumps({'state': 'REJECTED', 'code': str(error) if isinstance(error, Rejected) else 'INVALID_DOWNSIZE_INPUT'}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
