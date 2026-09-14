"""Offline native-power plans and real state-aware RDS request/journal tests."""
import contextlib
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_b_power as power
import growth_b_rds_power as rds
from test_global_b_infrastructure import attribute, block
from test_growth_b_mac_service import mac_fixture


class Clock:
    value = 1800000000
    def now(self): return self.value
    def sleep(self, delay): self.value += delay


class FakeAws:
    def __init__(self, request, state='stopped'):
        self.request = request; self.state = state; self.calls = []; self.transition = None
        self.error = None; self.rid = request['resourceId']; self.tag_change = {}; self.lease_lost = False

    def call(self, *args):
        self.calls.append(args)
        if args[:2] == ('sts', 'get-caller-identity'): return {'Account': rds.ACCOUNT}
        if args[:2] == ('dynamodb', 'get-item'):
            lease = self.request['lease']
            return {'Item': {'Owner': {'S': 'other' if self.lease_lost else lease['owner']},
                'RunId': {'S': lease['runId']}, 'Command': {'S': lease['command']},
                'FencingToken': {'N': str(lease['fencingToken'])},
                'ExpiresAt': {'N': '1999999999'}, 'CommandDeadline': {'N': '1999999999'}}}
        if args[:2] == ('rds', 'describe-db-instances'):
            state = self.state
            if self.transition is not None: self.state, self.transition = self.transition, None
            tags = {'RunId': self.request['runId'], 'FencingToken': '76', 'ExpiresAt': '1999999999',
                'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'lab',
                'ManagedBy': 'terraform', 'Persistence': 'ephemeral', 'Service': 'rds'} | self.tag_change
            return {'DBInstances': [{'DBInstanceIdentifier': self.request['identifier'], 'DbiResourceId': self.rid,
                'DBInstanceArn': f'arn:aws:rds:{rds.REGION}:{rds.ACCOUNT}:db:{self.request["identifier"]}',
                'DBInstanceClass': 'db.t3.small', 'DBInstanceStatus': state, 'Engine': 'mysql', 'EngineVersion': '8.4.11',
                'AllocatedStorage': 100, 'StorageType': 'gp3', 'StorageEncrypted': True, 'PubliclyAccessible': False,
                'MultiAZ': False, 'PendingModifiedValues': {}, 'TagList': [{'Key': k, 'Value': v} for k, v in tags.items()]}]}
        if args[:2] in [('rds', 'start-db-instance'), ('rds', 'stop-db-instance')]:
            if self.error: raise self.error
            self.state = 'starting' if args[1] == 'start-db-instance' else 'stopping'
            self.transition = 'available' if self.state == 'starting' else 'stopped'
            return {}
        raise AssertionError('Unexpected external boundary: ' + repr(args[:2]))

    def mutations(self):
        return [x for x in self.calls if x[:2] in [('rds', 'start-db-instance'), ('rds', 'stop-db-instance')]]


class RdsPower(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve(); self.directory.chmod(0o700); self.clock = Clock()
        self.request = {'schemaVersion': 1, 'kind': 'airbob-rds-power-request', 'operationId': 'resume-owned',
            'runId': 'lab-power-test', 'resourceFencingToken': 76, 'expiresAt': 1999999999,
            'deadlineEpoch': self.clock.now() + 60, 'desiredState': 'available', 'identifier': 'airbob-lab-power-test',
            'resourceId': 'db-EXACTSOURCE123', 'region': rds.REGION, 'accountId': rds.ACCOUNT,
            'evidenceDirectory': str(self.directory), 'lease': {'table': 'airbob-performance-lab-orchestration-lease',
                'lockName': 'lab/global', 'owner': 'power/test', 'runId': 'lab-power-test', 'command': 'up', 'fencingToken': 81}}
        self.aws = FakeAws(self.request)

    def execute(self):
        return rds.execute(self.request, self.aws, now=self.clock.now, sleep=self.clock.sleep)

    def test_stopped_to_starting_to_available_submits_once(self):
        result = self.execute()
        self.assertEqual(result['state'], 'RDS_POWER_VERIFIED')
        self.assertEqual(self.aws.mutations(), [('rds', 'start-db-instance', '--db-instance-identifier', self.request['identifier'])])
        self.assertFalse(result['apiResubmitted'])

    def test_already_available_never_submits(self):
        self.aws.state = 'available'; self.execute(); self.assertEqual(self.aws.mutations(), [])

    def test_starting_only_waits(self):
        self.aws.state = 'starting'; self.aws.transition = 'available'; self.execute()
        self.assertEqual(self.aws.mutations(), [])

    def test_stop_and_stopping_wait_are_symmetric(self):
        self.request['desiredState'] = 'stopped'; self.aws.state = 'available'; self.execute()
        self.assertEqual(len(self.aws.mutations()), 1); self.assertEqual(self.aws.mutations()[0][1], 'stop-db-instance')
        self.aws.calls.clear(); self.execute(); self.assertEqual(self.aws.mutations(), [])

    def test_unknown_submission_keeps_durable_intent_and_retry_never_replays(self):
        self.aws.error = rds.Rejected('AWS_CALL_FAILED')
        with self.assertRaisesRegex(rds.Rejected, 'DEADLINE'): self.execute()
        self.assertEqual(len(self.aws.mutations()), 1)
        old = next(self.directory.glob('*-intent.json')).read_bytes()
        self.request['deadlineEpoch'] = self.clock.now() + 60
        self.aws.error = None
        with self.assertRaisesRegex(rds.Rejected, 'DEADLINE'): self.execute()
        self.assertEqual(len(self.aws.mutations()), 1)
        self.assertEqual(next(self.directory.glob('*-intent.json')).read_bytes(), old)
        self.aws.state = 'available'; self.request['deadlineEpoch'] = self.clock.now() + 60
        self.execute(); self.assertEqual(len(self.aws.mutations()), 1)

    def test_auth_and_known_rejection_do_not_wait_or_retry(self):
        for code in ('AWS_AUTHENTICATION_UNAVAILABLE_STOP', 'AWS_REQUEST_REJECTED'):
            with self.subTest(code=code):
                self.request['operationId'] = code.lower().replace('_', '-')
                self.aws.error = rds.Rejected(code); before = self.clock.now()
                with self.assertRaisesRegex(rds.Rejected, code): self.execute()
                self.assertEqual(before, self.clock.now())

    def test_foreign_rid_tags_expiry_and_lease_fail_before_write(self):
        for mutate, code in [(lambda: setattr(self.aws, 'rid', 'db-FOREIGN'), 'IDENTITY'),
            (lambda: self.aws.tag_change.update(ExpiresAt='1999999998'), 'TAGS'),
            (lambda: setattr(self.aws, 'lease_lost', True), 'LEASE'),
            (lambda: self.request.update(deadlineEpoch=self.clock.now()), 'DEADLINE')]:
            with self.subTest(code=code):
                self.aws.rid = self.request['resourceId']; self.aws.tag_change = {}; self.aws.lease_lost = False
                self.request['deadlineEpoch'] = self.clock.now() + 60; mutate()
                with self.assertRaisesRegex(rds.Rejected, code): self.execute()
                self.assertEqual(self.aws.mutations(), [])

    def test_changed_existing_intent_and_symlink_fail_closed(self):
        self.aws.state = 'available'; self.execute()
        self.request['resourceId'] = 'db-OTHER'; self.aws.rid = 'db-OTHER'
        with self.assertRaisesRegex(rds.Rejected, 'INTENT_CHANGED'): self.execute()

    def test_aws_retry_is_disabled_for_mutation_and_stderr_stays_closed(self):
        failed = subprocess.CompletedProcess([], 1, b'', b'ExpiredToken SECRET_DO_NOT_PRINT')
        with patch.object(rds.subprocess, 'run', return_value=failed) as run:
            with self.assertRaisesRegex(rds.Rejected, '^AWS_AUTHENTICATION_UNAVAILABLE_STOP$'):
                rds.Aws().call('rds', 'start-db-instance', '--db-instance-identifier', self.request['identifier'])
        self.assertEqual(run.call_args.kwargs['env']['AWS_MAX_ATTEMPTS'], '1')


def plan(*pairs):
    events = [{'type': 'planned_change', 'change': {'resource': {'addr': a}, 'action': b}} for a, b in pairs]
    return events + [{'type': 'change_summary', 'changes': {'operation': 'plan',
        'add': sum(action in ('create', 'replace') for _, action in pairs),
        'change': sum(action == 'update' for _, action in pairs),
        'remove': sum(action == 'replace' for _, action in pairs)}}]


def ready_fixture(test):
    producer, manifest, _, _, _, _ = mac_fixture(test)
    state = copy.deepcopy(producer.state_after); out = power.outputs(state)
    p2, p3 = out['phase2_contract'], out['phase3_contract']
    p2['deployment_phase'] = 'data-ready'
    p2['services'] = {k: 'i-' + format(n, '017x') for n, k in enumerate(sorted(power.HOSTS - {'nat', 'app'}), 10)}
    out['phase4_contract'].update(app_enabled=True, mode='performance', capacity={'min': 1, 'desired': 1, 'max': 1},
        runtime_revision='9' * 64, auto_scaling_group_name='airbob-' + producer.op['runId'] + '-app',
        target_group_arn='arn:exact-target-group', alb_dns_name='lab.ap-northeast-2.elb.amazonaws.com')
    out['global_b_service'] = {'selected': True, 'runtime_revision': '9' * 64,
        'manifest_key': 'datasets/service/aws-service.json', 'manifest_sha256': 'a' * 64,
        'manifest_version_id': 'exact-service-version', 'readiness_receipt': {'key': 'same', 'version_id': 'original-ready', 'sha256': 'b' * 64, 'bytes': 19}}
    state['outputs'] = {k: {'value': v} for k, v in out.items()}
    state['resources'].append({'mode': 'managed', 'module': 'module.security', 'type': 'aws_vpc_security_group_ingress_rule', 'name': 'alb_https',
        'instances': [{'attributes': {'cidr_ipv4': '1.1.1.1/32'}}]})
    return producer.op, manifest, state


class MemoryRunner(power.Runner):
    """Actual phase orchestration/plan validation with only cloud/process I/O replaced."""
    def __init__(self, test, directory):
        self.directory = directory; self.lab = power.LAB
        self.operator, self.manifest, self.state = ready_fixture(test)
        self.contract = {'evidence_bucket_name': 'evidence', 'dataset_bucket_name': 'dataset',
            'state_bucket_name': 'state', 'lab_state_key': 'airbob/lab/terraform.tfstate'}
        self.deadline = 1800001000; self.clock = Clock(); self.token = 80; self.lease = None
        self.suspended = ['ScheduledActions']; self.app = 'i-' + 'a' * 17
        self.host_states = {name: 'running' for name in power.HOSTS}; self.volume_override = None
        self.readiness_status = 'Success'; self.fail_phase = None; self.applied = []
        self.app_health = 'Healthy'
        self.reorder_check_results = False; self.pull_count = 0; self.version_suffix = ''
        self.planned_variables = []
        request = self.rds_request({'operation_id': 'initial-read', 'evidence_directory': str(directory)})
        self.db = FakeAws(request, 'available'); self.aws = self

    def rds_request(self, selected):
        p2, p3 = [power.outputs(self.state)[k] for k in ('phase2_contract', 'phase3_contract')]
        return {'schemaVersion': 1, 'kind': 'airbob-rds-power-request', 'operationId': selected['operation_id'],
            'runId': p2['run_id'], 'resourceFencingToken': 76, 'expiresAt': 1999999999, 'deadlineEpoch': self.deadline,
            'desiredState': 'available', 'identifier': p3['rds_instance_id'], 'resourceId': p3['rds_resource_id'],
            'region': rds.REGION, 'accountId': rds.ACCOUNT, 'lease': self.lease, 'evidenceDirectory': selected['evidence_directory']}

    def call(self, *args):
        out = power.outputs(self.state); p2, p4 = out['phase2_contract'], out['phase4_contract']
        if args[:2] == ('s3api', 'head-object'):
            raw = rds.encoded(self.state)
            return {'VersionId': 'state-' + str(self.state['serial']) + self.version_suffix,
                'ETag': '"' + hashlib.sha256(raw).hexdigest() + '"', 'ContentLength': len(raw)}
        if args[0] in {'rds', 'dynamodb', 'sts'}: return self.db.call(*args)
        if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'):
            return {'AutoScalingGroups': [{'AutoScalingGroupName': p4['auto_scaling_group_name'], 'MinSize': 1,
                'MaxSize': 1, 'DesiredCapacity': 1, 'Instances': [{'InstanceId': self.app, 'LifecycleState': 'InService', 'HealthStatus': self.app_health}],
                'LaunchTemplate': {'LaunchTemplateId': 'lt-owned', 'Version': '1'},
                'SuspendedProcesses': [{'ProcessName': name} for name in self.suspended]}]}
        if args[:2] == ('autoscaling', 'describe-scaling-activities'): return {'Activities': []}
        if args[:2] == ('autoscaling', 'describe-instance-refreshes'): return {'InstanceRefreshes': []}
        if args[:2] == ('ec2', 'describe-instances'):
            ids = dict(p2['services'], nat=p2['nat_instance_id'], app=self.app)
            return {'Reservations': [{'Instances': [{'InstanceId': instance, 'ImageId': 'ami-original',
                'InstanceType': 'c6i.large' if name == 'app' else 't3.small', 'SubnetId': 'subnet-original',
                'VpcId': 'vpc-original', 'PrivateIpAddress': '10.42.1.' + str(index + 1),
                'IamInstanceProfile': {'Arn': 'arn:original-' + name}, 'SecurityGroups': [{'GroupId': 'sg-' + name}],
                'BlockDeviceMappings': [{'DeviceName': '/dev/xvda', 'Ebs': {'VolumeId': self.volume_override if name == 'app' and self.volume_override else 'vol-' + name, 'DeleteOnTermination': True}}],
                'Tags': [{'Key': k, 'Value': v} for k, v in {'RunId': p2['run_id'], 'FencingToken': '76', 'ExpiresAt': '1999999999'}.items()],
                'State': {'Name': self.host_states[name]}} for index, (name, instance) in enumerate(sorted(ids.items()))]}]}
        if args[:2] == ('elbv2', 'describe-target-health'):
            return {'TargetHealthDescriptions': [{'Target': {'Id': self.app}, 'TargetHealth': {'State': 'healthy'}}]}
        if args[:2] == ('ssm', 'send-command'): return {'Command': {'CommandId': '00000000-0000-0000-0000-000000000001'}}
        if args[:2] == ('ssm', 'list-command-invocations'): return {'CommandInvocations': [{'Status': self.readiness_status}]}
        raise AssertionError('Unexpected cloud operation ' + repr(args[:2]))

    def initialize(self): return self.pull()
    def pull(self):
        self.pull_count += 1; value = copy.deepcopy(self.state)
        if self.reorder_check_results and self.pull_count % 2:
            value['check_results'] = list(reversed(value['check_results']))
        self.last_state_pull_sha256 = hashlib.sha256(rds.encoded(value)).hexdigest()
        return value
    def heartbeat(self): pass

    @contextlib.contextmanager
    def acquired(self, state):
        self.token += 1
        self.lease = dict(table='airbob-performance-lab-orchestration-lease', lockName='lab/global', owner='power/test',
            runId=self.operator['runId'], command='up', fencingToken=self.token)
        self.db.request['lease'] = self.lease
        yield

    def pinned_json(self, bucket, key, directory, name, expected_sha=None, version=None):
        value = self.operator if name == 'operator.json' else self.manifest
        rds.write_new(directory / name, value); return copy.deepcopy(value)

    def tf(self, *args):
        selected = json.loads(Path(next(arg.split('=', 1)[1] for arg in args if arg.startswith('-var-file='))).read_bytes())
        phase = selected['lab_power']['phase'] if selected.get('lab_power') else 'access'
        if selected['alb_ingress_cidr'] != power.resources(self.state)[power.INGRESS]['cidr_ipv4']: phase = 'access'
        if args[0] == 'plan':
            self.planned_variables.append(copy.deepcopy(selected))
            changes = []
            target = sorted(selected['lab_power']['original_suspended_processes']) if phase == 'running' else sorted(power.PROCESSES)
            if phase == 'access': changes.append((power.INGRESS, 'update'))
            else:
                if self.suspended != target: changes.append((power.ASG, 'update'))
                if power.outputs(self.state).get('lab_power') is None:
                    changes.extend(('aws_ec2_instance_state.power["' + name + '"]', 'create') for name in sorted(power.HOSTS))
                    changes.append(('terraform_data.rds_power[0]', 'create'))
                else:
                    changes.extend(('aws_ec2_instance_state.power["' + name + '"]', 'update') for name in sorted(power.HOSTS) if self.host_states[name] != power.states(phase)[name])
                    if self.db.state != ('stopped' if phase == 'stopped' else 'available'):
                        changes.append(('terraform_data.rds_power[0]', 'replace'))
            return b'\n'.join(rds.encoded(event).strip() for event in plan(*changes))
        if args[0] != 'apply': raise AssertionError(args)
        self.applied.append(phase)
        if phase == 'access':
            power.resources(self.state)[power.INGRESS]['cidr_ipv4'] = selected['alb_ingress_cidr']
            self.state['serial'] += 1
            return b'{}'
        if self.fail_phase == phase:
            self.host_states['app'] = 'stopped'; self.fail_phase = None
            raise power.Rejected('INJECTED_APPLY_FAILURE')
        self.host_states = power.states(phase)
        self.suspended = sorted(selected['lab_power']['original_suspended_processes']) if phase == 'running' else sorted(power.PROCESSES)
        request = self.rds_request(selected['lab_power']); request['desiredState'] = 'stopped' if phase == 'stopped' else 'available'
        self.db.request = request
        rds.execute(request, self.db, now=self.clock.now, sleep=self.clock.sleep)
        self.state['outputs']['lab_power'] = {'value': {'phase': phase, 'request': selected['lab_power']}}
        self.state['serial'] += 1
        return b'{}'


class PowerOrchestration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve(); self.directory.chmod(0o700)
        self.runner = MemoryRunner(self, self.directory)
        self.sleep = patch.object(power.time, 'sleep', return_value=None); self.sleep.start(); self.addCleanup(self.sleep.stop)

    def test_actual_pause_resume_uses_same_seven_instances_and_restores_original_suspension(self):
        before = self.runner.inspect(self.runner.state)[0]
        self.assertEqual(self.runner.operate('pause')['state'], 'POWER_PAUSED')
        self.assertEqual(self.runner.applied, list(power.PAUSE))
        self.assertEqual(set(self.runner.host_states.values()), {'stopped'})
        self.assertEqual(self.runner.db.state, 'stopped')
        self.assertEqual(self.runner.operate('resume')['state'], 'POWER_RESUMED')
        self.assertEqual(self.runner.applied, list(power.PAUSE + power.RESUME))
        self.assertEqual(self.runner.suspended, ['ScheduledActions'])
        self.assertEqual(self.runner.inspect(self.runner.state)[0], before)
        self.assertEqual([x[1] for x in self.runner.db.mutations()], ['stop-db-instance', 'start-db-instance'])
        for path in self.directory.rglob('*.json'):
            self.assertNotIn('RAW_STATE_MUST_STAY_IN_RAM', path.read_text())

    def test_partial_pause_retry_reuses_exact_operation_and_does_not_restart_app(self):
        self.runner.fail_phase = 'writers-stopped'
        with self.assertRaisesRegex(power.Rejected, 'INJECTED'): self.runner.operate('pause')
        pointer = next(self.directory.glob('active-*.json')); first = json.loads(pointer.read_bytes())
        self.assertEqual(first['lastCompletedPhase'], 'fenced')
        with self.assertRaisesRegex(power.Rejected, 'ACTIVE_POWER'): self.runner.operate('resume')
        self.runner.operate('pause')
        self.assertEqual(self.runner.applied, ['fenced', 'writers-stopped', 'writers-stopped', 'stopped'])
        requests = list((self.directory / first['operationId']).glob('lease-*/request.json'))
        self.assertEqual(len(requests), 2)
        self.assertFalse(pointer.exists())

    def test_changed_volume_on_resume_rejected_before_apply(self):
        self.runner.operate('pause'); previous = list(self.runner.applied)
        self.runner.volume_override = 'vol-foreign'
        with self.assertRaisesRegex(power.Rejected, 'PRIOR_POWER_TARGET'): self.runner.operate('resume')
        self.assertEqual(self.runner.applied, previous)

    def test_unknown_readiness_never_restores_asg_processes_or_claims_resume(self):
        self.runner.operate('pause'); self.runner.readiness_status = 'InProgress'
        with self.assertRaisesRegex(power.Rejected, 'SSM_OUTCOME_UNKNOWN'): self.runner.operate('resume')
        self.assertEqual(set(self.runner.suspended), power.PROCESSES)
        self.assertNotIn('running', self.runner.applied)
        self.assertFalse(any(json.loads(p.read_bytes()).get('state') == 'POWER_RESUMED' for p in self.directory.rglob('result.json')))

    def test_asg_unhealthy_never_unfences_even_when_alb_and_app_checks_pass(self):
        self.runner.operate('pause'); self.runner.app_health = 'Unhealthy'
        with self.assertRaisesRegex(power.Rejected, 'RUNNING_HEALTHY_SOURCE_REQUIRED'):
            self.runner.operate('resume')
        self.assertEqual(set(self.runner.suspended), power.PROCESSES)
        self.assertEqual(set(self.runner.host_states.values()), {'running'})
        self.assertNotIn('running', self.runner.applied)

    def test_asg_health_is_rechecked_after_plan_before_unfencing(self):
        self.runner.operate('pause')
        original = self.runner.tf
        def change_after_plan(*args):
            result = original(*args)
            if args[0] == 'plan' and any('/running-' in item for item in args):
                self.runner.app_health = 'Unhealthy'
            return result
        with patch.object(self.runner, 'tf', side_effect=change_after_plan):
            with self.assertRaisesRegex(power.Rejected, 'RUNNING_HEALTHY_SOURCE_REQUIRED'):
                self.runner.operate('resume')
        self.assertEqual(set(self.runner.suspended), power.PROCESSES)
        self.assertNotIn('running', self.runner.applied)

    def test_pull_serialization_order_change_is_evidence_not_backend_mutation(self):
        self.runner.state['check_results'] = [{'config_addr': 'var.run_id'}, {'config_addr': 'var.global_b_services'}]
        self.runner.reorder_check_results = True
        self.assertEqual(self.runner.operate('access', '8.8.8.8/32')['state'], 'USER_ACCESS_UPDATED')
        result = json.loads(next(self.directory.glob('access-*/access-result-*.json')).read_bytes())
        self.assertNotEqual(result['beforeStateSha256'], result['planRecheckStateSha256'])
        self.assertIn('VersionId', result['backendBefore'])

    def test_changed_backend_version_after_plan_rejects_before_apply(self):
        original = self.runner.tf
        def changed(*args):
            result = original(*args)
            if args[0] == 'plan': self.runner.version_suffix = '-external-change'
            return result
        with patch.object(self.runner, 'tf', side_effect=changed):
            with self.assertRaisesRegex(power.Rejected, 'SOURCE_OR_STATE_CHANGED_AFTER_PLAN'):
                self.runner.operate('access', '8.8.8.8/32')
        self.assertEqual(self.runner.applied, [])

    def test_actual_power_tfvars_satisfy_production_service_lease_validations(self):
        self.runner.operate('access', '8.8.8.8/32')
        value = self.runner.planned_variables[0]
        self.assertEqual(value['global_b_lease_owner'], self.runner.lease['owner'])
        self.assertEqual(value['global_b_lease_fencing_token'], self.runner.lease['fencingToken'])
        self.assertEqual(value['fencing_token'], 76)
        source = (power.LAB / 'variables.tf').read_text()
        names = {'global_b_services', 'global_b_lease_fencing_token'}
        hcl = ''.join('variable "' + name + '" {\n' + block(source, 'variable', name) for name in sorted(names))
        references = set(re.findall(r'var\.([A-Za-z_0-9]+)', hcl)) - names
        hcl += ''.join('variable "' + name + '" { default = ' + json.dumps(value[name]) + ' }\n' for name in sorted(references))
        # Only actual variable blocks/values: no backend, provider, data or resources.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'main.tf').write_text(hcl)
            for changes, success in (({}, True), ({'global_b_lease_owner': ''}, False), ({'global_b_lease_fencing_token': 0}, False)):
                varfile = root / 'input.tfvars.json'
                varfile.write_text(json.dumps({k: changes.get(k, value[k]) for k in names | references}))
                result = subprocess.run(['terraform', '-chdir=' + str(root), 'plan', '-input=false', '-lock=false', '-no-color', '-var-file=' + str(varfile)],
                    text=True, capture_output=True, timeout=20,
                    env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'})
                self.assertEqual(result.returncode == 0, success, result.stderr)

    def test_status_distinguishes_absent_from_partial_and_never_acquires_lease(self):
        initial_token = self.runner.token
        result = self.runner.operate('status')
        self.assertEqual(result['state'], 'POWER_OBSERVED'); self.assertEqual(result['expiresAt'], 1999999999)
        self.assertEqual(self.runner.token, initial_token)
        self.runner.state['outputs']['phase4_contract']['value']['app_enabled'] = False
        with self.assertRaisesRegex(power.Rejected, 'PARTIAL_LAB'): self.runner.operate('status')
        self.runner.state['resources'] = []; self.runner.state['outputs'] = {}
        self.assertEqual(self.runner.operate('status'), {'state': 'LAB_ABSENT', 'runId': None, 'phase': None, 'expiresAt': None})

    def test_access_changes_only_ingress_after_resume_and_preserves_tls_sni(self):
        self.runner.operate('pause'); self.runner.operate('resume')
        previous = copy.deepcopy(power.outputs(self.runner.state)['lab_power'])
        result = self.runner.operate('access', '8.8.4.4/32')
        self.assertEqual(result['state'], 'USER_ACCESS_UPDATED')
        self.assertIn('--connect-to api.airbob.cloud:443:lab.ap-northeast-2.elb.amazonaws.com:443', result['readinessCommand'])
        self.assertEqual(power.outputs(self.runner.state)['lab_power'], previous)
        self.assertEqual([x[1] for x in self.runner.db.mutations()], ['stop-db-instance', 'start-db-instance'])

    def test_private_or_broad_ingress_never_applies(self):
        for cidr in ('10.0.0.1/32', '8.8.8.8/24', '0.0.0.0/0', None, 'bad'):
            with self.subTest(cidr=cidr), self.assertRaises(power.Rejected): self.runner.operate('access', cidr)
        self.assertEqual(self.runner.applied, [])
        self.assertEqual(self.runner.token, 80)
        self.assertEqual(list(self.directory.glob('active-*.json')), [])

    def test_crash_after_phase_record_uses_fresh_lease_temporary_without_restarting_writers(self):
        original = os.replace
        def crash(source, target):
            if 'writers-stopped' in str(source): raise OSError('injected-before-rename')
            return original(source, target)
        with patch.object(power.os, 'replace', side_effect=crash), self.assertRaises(OSError):
            self.runner.operate('pause')
        self.assertEqual(self.runner.host_states['app'], 'stopped')
        self.assertEqual(self.runner.operate('pause')['state'], 'POWER_PAUSED')
        self.assertEqual(self.runner.applied, ['fenced', 'writers-stopped', 'writers-stopped', 'stopped'])
        self.assertEqual([x[1] for x in self.runner.db.mutations()], ['stop-db-instance'])

    def test_crash_after_completion_preserves_receipt_and_can_close_owned_pointer(self):
        original = Path.unlink
        def crash(path, *args, **kwargs):
            if path.name.startswith('active-'): raise OSError('injected-after-completion')
            return original(path, *args, **kwargs)
        with patch.object(Path, 'unlink', new=crash), self.assertRaises(OSError):
            self.runner.operate('pause')
        receipt = next(self.directory.glob('pause-*/result.json')); previous = receipt.read_bytes()
        applied = list(self.runner.applied)
        self.assertEqual(self.runner.operate('pause')['state'], 'POWER_PAUSED')
        self.assertEqual(self.runner.applied, applied)
        self.assertEqual(receipt.read_bytes(), previous)
        self.assertEqual(list(self.directory.glob('active-*.json')), [])

    def test_already_paused_drift_is_not_reported_as_success(self):
        self.runner.operate('pause'); self.runner.db.state = 'available'
        previous = list(self.runner.applied)
        with self.assertRaisesRegex(power.Rejected, 'POWER_STATE_DRIFT'): self.runner.operate('pause')
        self.assertEqual(self.runner.applied, previous)


class PowerPlan(unittest.TestCase):
    def test_only_state_entries_and_asg_suspension_are_admitted(self):
        events = plan((power.ASG, 'update'), ('terraform_data.rds_power[0]', 'create'),
                      ('aws_ec2_instance_state.power["app"]', 'create'))
        self.assertEqual(len(power.validate_plan(events, 'fenced')), 3)
        for address, action in [('module.rds[0].aws_db_instance.this', 'update'),
            ('module.service_hosts.aws_instance.this["debezium"]', 'replace'),
            ('aws_ssm_association.app[0]', 'update'), ('module.app_asg[0].aws_launch_template.app', 'update'),
            ('aws_ec2_instance_state.power["foreign"]', 'update')]:
            with self.subTest(address=address), self.assertRaises(power.Rejected):
                power.validate_plan(plan((address, action)), 'fenced')

    def test_access_only_allows_current_ingress_rule(self):
        power.validate_plan(plan((power.INGRESS, 'update')), 'access')
        for address, action in [(power.ASG, 'update'), (power.INGRESS, 'replace'), ('terraform_data.rds_power[0]', 'update')]:
            with self.subTest(address=address), self.assertRaises(power.Rejected):
                power.validate_plan(plan((address, action)), 'access')

    def test_incomplete_error_or_duplicate_plan_is_rejected(self):
        concealed = plan(); concealed[-1]['changes']['add'] = 1
        for events in [[], [{'@level': 'error'}], plan((power.ASG, 'update'), (power.ASG, 'update')), concealed]:
            with self.assertRaises(power.Rejected): power.validate_plan(events, 'fenced')

    def test_order_never_restarts_writers_when_retrying_partial_pause(self):
        self.assertEqual(power.phases('pause', 'writers-stopped'), ('stopped',))
        self.assertEqual(power.phases('pause', 'stopped'), ())
        self.assertEqual(power.phases('resume', 'stopped'), power.RESUME)
        self.assertEqual(power.phases('resume', 'fenced'), ('running',))
        with self.assertRaises(power.Rejected): power.phases('pause', 'dependencies-running')

    def test_real_terraform_expressions_have_inactive_default_and_ordered_states(self):
        source = (power.LAB / 'power.tf').read_text()
        names = ['power_processes', 'power_phase', 'power_suspended', 'power_ids', 'power_states', 'power_rds_state']
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            hcl = 'variable "lab_power" { default = null }\nlocals {\n'
            hcl += 'service_ids = {debezium="i-d",redis="i-r",kafka="i-k",elasticsearch="i-e",monitoring="i-m"}\n'
            for name in names:
                expression = attribute(source, name).replace('module.service_hosts.instance_ids', 'local.service_ids').replace('module.nat.instance_id', '"i-n"')
                hcl += name + ' = ' + expression + '\n'
            hcl += '}\n'; (root / 'main.tf').write_text(hcl)
            for phase in [None, *power.PAUSE, *power.RESUME]:
                value = None if phase is None else {'phase': phase, 'app_instance_id': 'i-a', 'original_suspended_processes': ['ScheduledActions']}
                varfile = root / 'input.tfvars.json'; varfile.write_text(json.dumps({'lab_power': value}))
                result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-var-file=' + str(varfile)],
                    input='jsonencode({states=local.power_states,suspended=local.power_suspended,rds=local.power_rds_state})\n',
                    text=True, capture_output=True, timeout=20,
                    env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'})
                self.assertEqual(result.returncode, 0, result.stderr)
                actual = json.loads(json.loads(result.stdout))
                self.assertEqual(actual['states'], {} if phase is None else power.states(phase))
                self.assertEqual(set(actual['suspended']), set() if phase is None else {'ScheduledActions'} if phase == 'running' else power.PROCESSES)
                self.assertEqual(actual['rds'], 'stopped' if phase == 'stopped' else 'available')

    def test_reconstruct_reuses_actual_mac_receipt_fixture_and_current_ingress(self):
        producer, manifest, _, _, _, _ = mac_fixture(self)
        producer.op['globalBSnapshotSourceMode'] = 'mac-snapshot-counts-ddl'
        state = copy.deepcopy(producer.state_after); out = power.outputs(state)
        out['phase2_contract']['deployment_phase'] = 'data-ready'
        out['phase2_contract']['services'] = {k: 'i-' + str(n) * 17 for n, k in enumerate(sorted(power.HOSTS - {'nat', 'app'}), 1)}
        out['phase4_contract'].update(app_enabled=True, mode='performance', capacity={'min': 1, 'desired': 1, 'max': 1},
            runtime_revision='9' * 64)
        out['global_b_service'] = {'selected': True, 'runtime_revision': '9' * 64, 'manifest_sha256': 'a' * 64,
            'manifest_version_id': 'exact-service-version', 'readiness_receipt': {'key': 'same', 'version_id': 'original-ready', 'sha256': 'b' * 64, 'bytes': 19}}
        state['outputs'] = {k: {'value': v} for k, v in out.items()}
        state['resources'].append({'mode': 'managed', 'module': 'module.security', 'type': 'aws_vpc_security_group_ingress_rule', 'name': 'alb_https',
            'instances': [{'attributes': {'cidr_ipv4': '1.1.1.1/32'}}]})
        result = power.reconstruct(producer.op, manifest, state)
        self.assertEqual(result['alb_ingress_cidr'], '1.1.1.1/32')
        self.assertEqual(result['global_b_readiness_receipt'], out['global_b_service']['readiness_receipt'])
        self.assertEqual(result['bundle_commit'], producer.op['bundleCommit'])
        self.assertEqual(result['expires_at'], producer.op['expiresAt'])
        self.assertEqual(result['fencing_token'], 76)
        self.assertEqual(result['rds_instance_class'], 'db.t3.small')
        self.assertEqual(result['global_b_snapshot_source_mode'], 'mac-snapshot-counts-ddl')
        self.assertFalse(result['global_b_import_from_mac']); self.assertFalse(result['global_b_service_bootstrap_enabled'])
        self.assertNotIn('RAW_STATE_MUST_STAY_IN_RAM', json.dumps(result))
        manifest['application']['image'] = 'changed'
        with self.assertRaises(power.Rejected): power.reconstruct(producer.op, manifest, state)


if __name__ == '__main__':
    unittest.main()
