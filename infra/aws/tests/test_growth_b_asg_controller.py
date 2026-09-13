"""Exercise actual ASG context assembly using versioned bytes and read-only AWS fakes."""
import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
import tempfile
import time
import unittest

from test_growth_b_service_contract import fixture, receipt
import growth_b_asg_controller as controller


class Aws:
    def __init__(self):
        self.objects = {}; self.calls = []; self.actions = set(controller.probe.CLEANUP_ACTIONS)
        self.policy_version = 'v3'

    def add(self, key, value, version):
        raw = json.dumps(value, sort_keys=True).encode()
        self.objects[key] = (raw, version)
        return {'key': key, 'versionId': version, 'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}

    def call(self, *args):
        self.calls.append(args); service, operation = args[:2]
        def selected(flag): return args[args.index(flag) + 1]
        if service == 's3api':
            key = selected('--key'); raw, version = self.objects[key]
            if selected('--version-id') != version: raise ValueError('Wrong version')
            if operation == 'get-object': Path(args[-1]).write_bytes(raw)
            elif operation != 'head-object': raise AssertionError(args)
            return {'VersionId': version, 'ContentLength': len(raw)}
        if operation == 'describe-auto-scaling-groups': return {'AutoScalingGroups': [self.group]}
        if operation == 'describe-launch-template-versions': return {'LaunchTemplateVersions': [{'LaunchTemplateData': {'ImageId': self.instance['ImageId']}}]}
        if operation == 'describe-instances': return {'Reservations': [{'Instances': [self.instance]}]}
        if operation == 'get-policy': return {'Policy': {'Arn': controller.probe.POLICY, 'DefaultVersionId': self.policy_version}}
        if operation == 'list-attached-role-policies': return {'AttachedPolicies': [{'PolicyArn': controller.probe.POLICY}]}
        if operation == 'get-policy-version':
            return {'PolicyVersion': {'Document': {'Version': '2012-10-17', 'Statement': [{
                'Effect': 'Allow', 'Action': sorted(self.actions), 'Resource': self.group['AutoScalingGroupARN'],
                'Condition': {'StringEquals': {'aws:ResourceTag/' + k: v for k, v in {'Project': 'airbob', 'Environment': 'performance-lab',
                    'ManagedBy': 'terraform', 'Persistence': 'ephemeral', 'Stack': 'lab'}.items()},
                    'Null': {'aws:ResourceTag/' + k: 'false' for k in ('RunId', 'FencingToken', 'ExpiresAt')}}}]}}}
        raise AssertionError('Unexpected/non-read-only AWS operation: ' + str(args))


class AsgContext(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.counter = 0; self.aws = Aws(); self.now = int(time.time())
        self.manifest = fixture(); run = self.manifest['runId']; dataset = self.manifest['datasetId']; release = self.manifest['serviceRelease']
        self.manifest_ref = self.aws.add(f'datasets/{dataset}-aws-service/{release}/aws-service.json', self.manifest, 'manifest-v1')
        ready = receipt(self.manifest); ready['manifestSha256'] = self.manifest_ref['sha256']
        self.ready_ref = self.aws.add(f'data-bootstrap/{run}/{dataset}-service-{release}.json', ready, 'ready-v1')
        self.operator = {'runId': run, 'datasetRelease': dataset, 'mode': 'performance', 'dnsMode': 'direct-only',
            'loadGeneratorEnabled': False, 'globalBPrepareOnly': True, 'bundleCommit': self.manifest['application']['mainCommit'],
            'appImageReference': self.manifest['application']['image'], 'fencingToken': 7,
            'expiresAt': str(self.now + 80000), 'approvedExecutionDeadlineEpoch': self.now + 86400}
        self.phase4 = {'app_enabled': True, 'mode': 'performance', 'capacity': {'min': 1, 'desired': 1, 'max': 1},
            'load_generator_enabled': False, 'auto_scaling_group_name': 'airbob-' + run + '-app', 'runtime_revision': 'f'*64,
            'target_group_arn': 'arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:targetgroup/airbob-lab-test/'+'a'*16,
            'alb_arn': 'arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-lab-test/'+'b'*16,
            'alb_dns_name': 'airbob-lab-test.ap-northeast-2.elb.amazonaws.com'}
        self.state = {'selected': True, 'manifest_key': self.manifest_ref['key'], 'manifest_version_id': 'manifest-v1',
            'manifest_sha256': self.manifest_ref['sha256'], 'readiness_receipt': {'key': self.ready_ref['key'], 'version_id': 'ready-v1',
                'sha256': self.ready_ref['sha256'], 'bytes': self.ready_ref['bytes']}}
        self.operation = {'operationId': 'asg-001', 'serviceRelease': release, 'serviceManifestSha256': self.manifest_ref['sha256'],
            'readinessVersionId': 'ready-v1', 'readinessSha256': self.ready_ref['sha256'], 'detailPath': '/api/v1/accommodations/17'}
        self.lease = {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
            'owner': 'reviewed-controller', 'runId': run, 'command': 'up', 'fencingToken': 9}
        self.aws.group = {'AutoScalingGroupARN': 'arn:aws:autoscaling:ap-northeast-2:942632789808:autoScalingGroup:12345678-1234-1234-1234-123456789abc:autoScalingGroupName/'+self.phase4['auto_scaling_group_name'],
            'Instances': [{'InstanceId': 'i-'+'a'*17, 'ProtectedFromScaleIn': False}],
            'LaunchTemplate': {'LaunchTemplateId': 'lt-'+'b'*17, 'Version': '3'}}
        self.aws.instance = {'InstanceId': 'i-'+'a'*17, 'ImageId': 'ami-'+'c'*17,
            'LaunchTime': dt.datetime.fromtimestamp(self.now-1000, dt.timezone.utc).isoformat()}

    def configure(self):
        self.counter += 1; output = self.root / str(self.counter); output.mkdir(mode=0o700)
        return controller.configure(self.operator, self.phase4, self.state, self.operation, self.lease,
                                    'manifest-v1', output, aws=self.aws, clock=lambda: self.now)

    def test_versioned_application_evidence_and_original_expiry_bind_exact_one_hour_probe(self):
        value = self.configure()
        self.assertEqual(self.manifest['application'], value['application'])
        self.assertEqual(self.now+3600, value['deadlineEpoch'])
        self.assertEqual(self.operator['approvedExecutionDeadlineEpoch'], value['approvedExecutionDeadlineEpoch'])
        self.assertEqual(7, value['resourceFencingToken']); self.assertEqual(9, value['lease']['fencingToken'])
        self.assertEqual(self.manifest_ref, value['manifest']); self.assertEqual(self.ready_ref, value['readiness'])
        self.assertFalse(any('update' in call[1] or 'terminate' in call[1] for call in self.aws.calls))

    def test_current_terraform_service_or_readiness_mismatch_stops_before_aws(self):
        for key, value in [('manifest_version_id', 'another'), ('manifest_sha256', '0'*64), ('manifest_key', 'datasets/other')]:
            original = self.state[key]; self.state[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.configure()
            self.state[key] = original
        self.assertEqual([], self.aws.calls)
        self.state['readiness_receipt']['version_id'] = 'another'
        with self.assertRaises(ValueError): self.configure()
        self.assertEqual([], self.aws.calls)

    def test_missing_live_exact_instance_cleanup_permissions_prevents_context_admission(self):
        self.aws.actions.remove('autoscaling:TerminateInstanceInAutoScalingGroup')
        with self.assertRaisesRegex(RuntimeError, 'NOT_GRANTED'): self.configure()
        self.assertFalse((self.root/'1/configuration.json').exists())

    def test_s3_version_and_byte_hash_cannot_be_replaced(self):
        raw, version = self.aws.objects[self.manifest_ref['key']]
        self.aws.objects[self.manifest_ref['key']] = (raw+b' ', version)
        with self.assertRaises(ValueError): self.configure()

    def test_resume_preserves_baseline_contract_and_only_replaces_lease_and_operation_deadline(self):
        previous = self.configure(); prefix = f'data-bootstrap/{self.operator["runId"]}/asg-probe/asg-001-9/'
        old = self.aws.add(prefix+'configuration.json', previous, 'config-v1')
        journal = self.aws.add(prefix+'asg-probe.json', {'state': controller.probe.RETAINED,
            'contractSha256': controller.probe.contract_sha(previous), 'cleanupComplete': False}, 'journal-v1')
        self.operation['resume'] = {'configuration': old, 'receipt': journal}
        self.lease = dict(self.lease, fencingToken=10)
        selected = self.configure()
        self.assertEqual(previous['asg'], selected['asg'])
        self.assertEqual(previous['cleanupPolicy'], selected['cleanupPolicy'])
        self.assertEqual(controller.probe.contract_sha(previous), controller.probe.contract_sha(selected))
        self.assertEqual(10, selected['lease']['fencingToken'])
        self.assertEqual(journal['sha256'], selected['resume']['sha256'])
        self.assertEqual(1, sum(call[1]=='describe-auto-scaling-groups' for call in self.aws.calls))
        self.operator['approvedExecutionDeadlineEpoch'] += 1
        with self.assertRaises(ValueError): self.configure()

    def test_closed_paths_actions_and_resume_namespace_reject_before_cloud(self):
        for patch in ({'detailPath': 'https://external.invalid/'}, {'serviceRelease': '../other'}, {'deleteEverything': True}):
            changed = self.operation | patch
            with self.subTest(patch=patch), self.assertRaises(ValueError):
                controller.validate_operation(changed, self.operator['runId'], self.operator['datasetRelease'])
        ref = {'key': 'data-bootstrap/lab-other/asg-probe/asg-001-9/configuration.json', 'versionId': 'v1', 'sha256': 'a'*64, 'bytes': 10}
        changed = self.operation | {'resume': {'configuration': ref, 'receipt': dict(ref, key=ref['key'].replace('configuration.json','asg-probe.json'))}}
        with self.assertRaises(ValueError): controller.validate_operation(changed, self.operator['runId'], self.operator['datasetRelease'])


if __name__ == '__main__': unittest.main()
