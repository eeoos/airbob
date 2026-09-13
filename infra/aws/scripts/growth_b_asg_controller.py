#!/usr/bin/env python3
"""Assemble an exact B ASG probe context using read-only source observations.

The shared shell operator owns the lease and process supervision. This adapter
does not apply Terraform or change ASG capacity; growth_b_asg_probe owns those
bounded changes and its recovery journal.
"""
from __future__ import annotations
import argparse
import copy
import json
from pathlib import Path
import re
import time

import growth_b_asg_probe as probe
import growth_b_service as service
import growth_b_aws_restore as restore


def validate_operation(value, run_id, dataset_id):
    fields = {'operationId', 'serviceRelease', 'serviceManifestSha256', 'readinessVersionId', 'readinessSha256', 'detailPath'}
    service.require(isinstance(value, dict) and set(value) in (fields, fields | {'resume'}), 'ASG operation fields differ')
    service.require(all(isinstance(value[key], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}', value[key])
                        and '--' not in value[key] and not value[key].endswith('-') for key in ('operationId', 'serviceRelease')),
                    'ASG operation/release coordinates differ')
    service.require(all(isinstance(value[key], str) and re.fullmatch(r'[0-9a-f]{64}', value[key])
                        for key in ('serviceManifestSha256', 'readinessSha256')), 'ASG evidence SHA is invalid')
    service.require(isinstance(value['readinessVersionId'], str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', value['readinessVersionId'])
                    and value['readinessVersionId'] not in ('null', 'None'), 'ASG readiness version is invalid')
    service.require(isinstance(value['detailPath'], str) and re.fullmatch(r'/api/v1/accommodations/[1-9][0-9]{0,18}', value['detailPath']),
                    'One approved public accommodation detail GET is required')
    if 'resume' in value:
        service.require(isinstance(value['resume'], dict) and set(value['resume']) == {'configuration', 'receipt'}, 'ASG resume refs differ')
        for name, suffix in [('configuration', 'configuration.json'), ('receipt', 'asg-probe.json')]:
            ref = value['resume'][name]
            service.ref(ref, f'data-bootstrap/{run_id}/asg-probe/{value["operationId"]}-')
            service.require(re.fullmatch(re.escape(f'data-bootstrap/{run_id}/asg-probe/{value["operationId"]}-')
                            + r'[1-9][0-9]*/' + re.escape(suffix), ref['key']), 'Resume must use the same operation and exact prior fence')
        service.require(value['resume']['configuration']['key'].rsplit('/', 1)[0] == value['resume']['receipt']['key'].rsplit('/', 1)[0],
                        'Resume configuration and receipt must belong to the same prior execution')
    return value


def selected_ref(aws, bucket, key, version, digest):
    ref = {'key': key, 'versionId': version, 'sha256': digest, 'bytes': 1}
    service.ref(ref, 'datasets/' if bucket == service.BUCKET else 'data-bootstrap/')
    head = aws.call('s3api', 'head-object', '--bucket', bucket, '--key', key, '--version-id', version)
    service.require(head.get('VersionId') == version, 'Selected evidence version changed')
    ref['bytes'] = head['ContentLength']
    service.ref(ref, 'datasets/' if bucket == service.BUCKET else 'data-bootstrap/')
    return ref


def configure(operator, phase4, service_state, operation, lease, manifest_version, output, *, aws=None, clock=time.time):
    aws = aws or restore.Aws(); output = Path(output)
    service.require(output.is_dir() and not output.is_symlink(), 'New private controller output required')
    run, dataset = operator['runId'], operator['datasetRelease']
    validate_operation(operation, run, dataset)
    service.require(operator.get('globalBPrepareOnly') is True or operator.get('globalBSnapshotRestoreOnly') is True, 'Only a retained B run can be probed')
    service.require(operator['mode'] == 'performance' and operator['dnsMode'] == 'direct-only'
                    and operator['loadGeneratorEnabled'] is False, 'ASG probe requires the original B isolated topology')
    service.require(phase4['app_enabled'] is True and phase4['mode'] == 'performance'
                    and phase4['capacity'] == {'min': 1, 'desired': 1, 'max': 1} and phase4['load_generator_enabled'] is False,
                    'The selected Terraform application baseline is not one instance')
    release = operation['serviceRelease']
    key = f'datasets/{dataset}-aws-service/{release}/aws-service.json'
    ready_key = f'data-bootstrap/{run}/{dataset}-service-{release}.json'
    service.require(service_state['selected'] is True and service_state['manifest_key'] == key
                    and service_state['manifest_version_id'] == manifest_version
                    and service_state['manifest_sha256'] == operation['serviceManifestSha256'], 'Probe service manifest differs from current Terraform application')
    ready_state = service_state['readiness_receipt']
    service.require(ready_state is not None and ready_state['key'] == ready_key
                    and ready_state['version_id'] == operation['readinessVersionId'] and ready_state['sha256'] == operation['readinessSha256'],
                    'Probe readiness differs from current Terraform application')
    manifest_ref = selected_ref(aws, service.BUCKET, key, manifest_version, operation['serviceManifestSha256'])
    manifest = service.fetch(aws, manifest_ref, output / 'selected-service.json', service.BUCKET)
    service.validate_manifest(manifest, dataset, run, release, {name: service.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS})
    service.require(manifest['application']['mainCommit'] == operator['bundleCommit']
                    and manifest['application']['image'] == operator['appImageReference'], 'Original application tuple changed')
    readiness_ref = selected_ref(aws, service.EVIDENCE, ready_key, operation['readinessVersionId'], operation['readinessSha256'])
    service.require(readiness_ref['bytes'] == ready_state['bytes'], 'Selected readiness byte count changed')
    ready = service.fetch(aws, readiness_ref, output / 'selected-readiness.json', service.EVIDENCE)
    service.validate_readiness(ready, manifest, manifest_ref['sha256'])
    now = int(clock()); expiry = int(operator['expiresAt']); approved = operator['approvedExecutionDeadlineEpoch']
    common = {'schemaVersion': 1, 'kind': probe.KIND, 'operationId': operation['operationId'], 'datasetId': dataset,
        'runId': run, 'serviceRelease': release, 'manifest': manifest_ref, 'readiness': readiness_ref,
        'application': manifest['application'], 'resourceFencingToken': operator['fencingToken'], 'lease': lease,
        'expiresAt': expiry, 'approvedExecutionDeadlineEpoch': approved, 'deadlineEpoch': min(now + 3600, expiry, approved),
        'http': {'publicHost': 'api.airbob.cloud', 'detailPath': operation['detailPath'], 'timeoutSeconds': 5, 'maximumRequests': 500},
        'timing': {'baselineSeconds': 60, 'pollSeconds': 15, 'healthySeconds': 60, 'cleanupReserveSeconds': 900, 'stableSeconds': 60}}
    if 'resume' in operation:
        old = service.fetch(aws, operation['resume']['configuration'], output / 'previous-configuration.json', service.EVIDENCE)
        service.fetch(aws, operation['resume']['receipt'], output / 'previous-receipt.json', service.EVIDENCE)
        for key in common:
            if key not in ('lease', 'deadlineEpoch'):
                service.require(old.get(key) == common[key], 'Cleanup-only resume changed its original contract: ' + key)
        selected = copy.deepcopy(old); selected.pop('resume', None)
        selected.update(lease=lease, deadlineEpoch=common['deadlineEpoch'],
                        resume={'path': str((output / 'previous-receipt.json').resolve()), 'sha256': operation['resume']['receipt']['sha256']})
    else:
        asg_name = phase4['auto_scaling_group_name']
        groups = aws.call('autoscaling', 'describe-auto-scaling-groups', '--auto-scaling-group-names', asg_name)['AutoScalingGroups']
        service.require(len(groups) == 1 and len(groups[0]['Instances']) == 1, 'ASG probe requires exactly one current baseline')
        group = groups[0]; baseline = group['Instances'][0]; lt = group['LaunchTemplate']
        versions = aws.call('ec2', 'describe-launch-template-versions', '--launch-template-id', lt['LaunchTemplateId'], '--versions', lt['Version'])['LaunchTemplateVersions']
        service.require(len(versions) == 1, 'One exact launch template version required')
        rows = aws.call('ec2', 'describe-instances', '--instance-ids', baseline['InstanceId'])['Reservations']
        instances = [instance for row in rows for instance in row['Instances']]
        service.require(len(instances) == 1 and instances[0]['InstanceId'] == baseline['InstanceId'], 'Exact baseline instance unavailable')
        policy = aws.call('iam', 'get-policy', '--policy-arn', probe.POLICY)['Policy']
        document = aws.call('iam', 'get-policy-version', '--policy-arn', probe.POLICY, '--version-id', policy['DefaultVersionId'])['PolicyVersion']['Document']
        selected = common | {'cleanupPolicy': {'policyArn': probe.POLICY, 'defaultVersionId': policy['DefaultVersionId'], 'documentSha256': probe.canonical(document)},
            'asg': {'name': asg_name, 'arn': group['AutoScalingGroupARN'], 'baselineInstanceId': baseline['InstanceId'],
                'baselineLaunchTime': instances[0]['LaunchTime'], 'originalProtection': baseline['ProtectedFromScaleIn'], 'amiId': instances[0]['ImageId'],
                'runtimeRevision': phase4['runtime_revision'], 'targetGroupArn': phase4['target_group_arn'], 'albArn': phase4['alb_arn'],
                'albDnsName': phase4['alb_dns_name'], 'launchTemplate': {'id': lt['LaunchTemplateId'], 'version': lt['Version'],
                    'dataSha256': probe.canonical(versions[0]['LaunchTemplateData'])}}}
    probe.validate_config(selected)
    probe.policy_admission(aws, selected)
    service.write(output / 'configuration.json', selected)
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('operator', 'phase4', 'service-state', 'operation', 'lease', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--manifest-version', required=True)
    args = parser.parse_args()
    try:
        result = configure(*(service.read(path) for path in (args.operator, args.phase4, args.service_state, args.operation, args.lease)),
                           args.manifest_version, args.output)
        print(json.dumps({'state': 'EXACT_ASG_PROBE_CONTEXT_READY', 'configurationSha256': probe.canonical(result), 'scaleOutExecuted': False}))
    except Exception as error:
        print(json.dumps({'state': 'ASG_PROBE_CONTEXT_REJECTED', 'failureCode': type(error).__name__}))
        raise SystemExit(1)


if __name__ == '__main__':
    main()
