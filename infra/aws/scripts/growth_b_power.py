#!/usr/bin/env python3
"""Local, Terraform-based pause/resume/access for the current ready B lab.

The user supplies an AWS profile, not lease tokens, instance IDs or hashes.
Current state and immutable service inputs are read automatically. Raw state,
Terraform JSON streams and API responses remain in bounded memory. Only public
requests, tfvars and closed results are saved. No SQL or container redeployment
is performed.
"""
from __future__ import annotations

import argparse
import contextlib
import copy
import datetime
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import signal
import subprocess
import sys
import time
import urllib.request
import uuid

from growth_b_rds_power import ACCOUNT, REGION, Aws, Rejected, need, encoded, digest, write_new, read_own, sync_directory, aws_error, observe

SCRIPTS = Path(__file__).resolve().parent
LAB = SCRIPTS.parent / 'lab'
PROCESSES = {'Launch', 'Terminate', 'HealthCheck', 'ReplaceUnhealthy', 'AZRebalance',
             'AlarmNotification', 'ScheduledActions', 'AddToLoadBalancer', 'InstanceRefresh'}
HOSTS = {'app', 'debezium', 'elasticsearch', 'kafka', 'monitoring', 'nat', 'redis'}
ASG = 'module.app_asg[0].aws_autoscaling_group.app'
INGRESS = 'module.security.aws_vpc_security_group_ingress_rule.alb_https'
PAUSE = ('fenced', 'writers-stopped', 'stopped')
RESUME = ('dependencies-running', 'connect-running', 'app-running', 'running')


def resources(state):
    result = {}
    for resource in state.get('resources', []):
        if resource.get('mode') != 'managed':
            continue
        prefix = (resource.get('module', '') + '.').lstrip('.') + resource['type'] + '.' + resource['name']
        for row in resource.get('instances', []):
            address = prefix + ('[' + json.dumps(row['index_key']) + ']' if 'index_key' in row else '')
            need(address not in result and not row.get('deposed'), 'DEPOSED_OR_DUPLICATE_RESOURCE')
            result[address] = row['attributes']
    return result


def outputs(state):
    need(state.get('version') == 4 and isinstance(state.get('lineage'), str)
         and type(state.get('serial')) is int, 'CURRENT_TERRAFORM_STATE_REQUIRED')
    return {key: row['value'] for key, row in state['outputs'].items()}


def reconstruct(operator, manifest, state):
    """Rehydrate the actual ready service, including its current user ingress."""
    out, rows = outputs(state), resources(state)
    p2, p3, p4, service = [out[k] for k in ('phase2_contract', 'phase3_contract', 'phase4_contract', 'global_b_service')]
    run, fence = p2['run_id'], p2['fencing_token']
    need(out['run_identity'] == {'run_id': run, 'resource_fencing_token': fence}
         and operator['runId'] == run and operator['fencingToken'] == fence, 'ORIGINAL_RUN_CHANGED')
    need(p2['deployment_phase'] == 'data-ready' and p4['app_enabled'] is True
         and p4['mode'] == 'performance' and p4['capacity'] == {'min': 1, 'desired': 1, 'max': 1}
         and p4['load_generator_enabled'] is False and service['selected'] is True
         and service['readiness_receipt'] is not None and service['runtime_revision'] == p4['runtime_revision'],
         'READY_SINGLE_APP_SERVICE_REQUIRED')
    need(manifest['runId'] == run and manifest['datasetId'] == p3['dataset_release'] == operator['datasetRelease']
         and manifest['application']['mainCommit'] == operator['bundleCommit']
         and manifest['application']['image'] == operator['appImageReference'], 'SERVICE_SOURCE_CHANGED')
    need(p3['rds_instance_class'] == 'db.t3.small' and p3['rds_allocated_storage_gib'] == 100
         and p3['rds_engine_version'] == '8.4.11' and p3['rds_resource_id'] == manifest['rds']['resourceId'],
         'RETAINED_SMALL_RDS_REQUIRED')
    mapping = {'run_id': 'runId', 'expires_at': 'expiresAt', 'fencing_token': 'fencingToken', 'ami_id': 'amiId',
        'bundle_commit': 'bundleCommit', 'bundle_sha256': 'bundleSha256', 'app_image_reference': 'appImageReference',
        'infra_image_references': 'infraImageReferences', 'accommodation_detail_cache_enabled': 'cacheEnabled',
        'dataset_release': 'datasetRelease', 'rds_snapshot_identifier': 'rdsSnapshotIdentifier',
        'rds_snapshot_source_run_id': 'rdsSnapshotSourceRunId', 'rds_snapshot_source_resource_id': 'rdsSnapshotSourceResourceId'}
    result = {key: copy.deepcopy(operator.get(source, '')) for key, source in mapping.items()}
    result['infra_image_references']['DEBEZIUM_IMAGE'] = manifest['debezium']['image']
    probe = re.fullmatch(r'network-receipts/' + re.escape(run) + r'/(i-[0-9a-f]+)\.json', p2['expected_network_receipt_key'])
    need(probe is not None, 'CURRENT_NETWORK_RECEIPT_REQUIRED')
    result.update(deployment_phase='data-ready', app_enabled=True, mode='performance', measurement_policy='integrated-smoke',
        dns_mode='direct-only', alb_ingress_cidr=rows[INGRESS]['cidr_ipv4'], load_generator_enabled=False,
        request_count_per_target_per_minute=None, verified_probe_instance_id=probe[1],
        dataset_manifest_sha256=service['manifest_sha256'], global_b_manifest_version_id=service['manifest_version_id'],
        database_bootstrap=p3['database_bootstrap'], rds_engine_version='8.4.11', rds_instance_class='db.t3.small',
        global_b_services=True, global_b_service_release=manifest['serviceRelease'],
        global_b_readiness_receipt=service['readiness_receipt'], global_b_service_bootstrap_enabled=False,
        global_b_prepare_only=False, global_b_import_from_mac=False, global_b_snapshot_restore_only=False,
        global_b_snapshot_provenance=operator.get('globalBSnapshotProvenance'),
        global_b_snapshot_source_mode=operator.get('globalBSnapshotSourceMode', 'verified-global-b-snapshot'),
        global_b_lease_owner='', global_b_lease_fencing_token=0,
        lab_power=copy.deepcopy((out.get('lab_power') or {}).get('request')))
    return result


def phases(operation, current):
    if operation == 'pause':
        need(current in (None, 'running', *PAUSE), 'RESUME_INCOMPLETE_FINISH_RESUME_FIRST')
        return PAUSE[PAUSE.index(current) + 1:] if current in PAUSE else PAUSE
    need(operation == 'resume', 'POWER_OPERATION_INVALID')
    if current in (None, 'running'):
        return ()
    if current == 'fenced':
        return ('running',)
    need(current in (*PAUSE, *RESUME), 'POWER_PHASE_INVALID')
    return RESUME[RESUME.index(current) + 1:] if current in RESUME else RESUME


def states(phase):
    result = {name: 'running' for name in HOSTS}
    if phase == 'stopped':
        return {name: 'stopped' for name in HOSTS}
    if phase in ('writers-stopped', 'dependencies-running', 'connect-running'):
        result['app'] = 'stopped'
    if phase in ('writers-stopped', 'dependencies-running'):
        result['debezium'] = 'stopped'
    return result


def validate_plan(events, phase):
    """Validate the complete RAM plan UI stream; no -target or saved plan."""
    changes, summaries = [], []
    for event in events:
        need(event.get('@level') != 'error', 'TERRAFORM_PLAN_ERROR')
        if event.get('type') == 'planned_change':
            change = event['change']; resource = change['resource']
            address, action = resource['addr'], change['action']
            allowed = (phase == 'access' and address == INGRESS and action == 'update')
            if phase != 'access':
                allowed = (address == ASG and phase in ('fenced', 'running') and action == 'update') or (
                    address == 'terraform_data.rds_power[0]' and action in ('create', 'update', 'replace')) or (
                    re.fullmatch(r'aws_ec2_instance_state\.power\["([a-z]+)"\]', address) is not None
                    and address.split('"')[1] in HOSTS and action in ('create', 'update', 'replace'))
            need(allowed, 'POWER_PLAN_OUTSIDE_STATE_OR_SUSPENSION')
            changes.append({'address': address, 'action': action})
        if event.get('type') == 'change_summary':
            summaries.append(event['changes'])
    need(len(summaries) == 1 and summaries[0].get('operation') == 'plan', 'COMPLETE_TERRAFORM_PLAN_REQUIRED')
    need(len({x['address'] for x in changes}) == len(changes), 'DUPLICATE_PLAN_CHANGE')
    need(summaries[0].get('add') == sum(x['action'] in ('create', 'replace') for x in changes)
         and summaries[0].get('change') == sum(x['action'] == 'update' for x in changes)
         and summaries[0].get('remove') == sum(x['action'] == 'replace' for x in changes)
         and summaries[0].get('import', 0) == 0, 'PLAN_SUMMARY_DIFFERS_FROM_CHANGES')
    return changes


def source_sha(lab):
    files = [p for p in lab.rglob('*.tf') if '.terraform' not in p.parts]
    files += [Path(__file__), SCRIPTS / 'growth_b_rds_power.py', lab / '.terraform.lock.hcl']
    return digest({str(p.relative_to(lab.parent)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files})


def public_cidr(value):
    try:
        address = ipaddress.ip_address(value.split('/')[0])
    except (AttributeError, ValueError):
        raise Rejected('PUBLIC_IPV4_32_REQUIRED') from None
    need(address.version == 4 and address.is_global and value == str(address) + '/32', 'PUBLIC_IPV4_32_REQUIRED')
    return value


class Runner:
    def __init__(self, profile, directory, lab=LAB):
        self.lab, self.directory = Path(lab).resolve(), Path(directory).expanduser().resolve()
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        need(self.directory.stat().st_uid == os.getuid() and self.directory.stat().st_mode & 0o777 == 0o700, 'PRIVATE_POWER_DIRECTORY_REQUIRED')
        need(re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', profile), 'AWS_PROFILE_REQUIRED')
        self.env = {k: v for k, v in os.environ.items() if k not in
            {'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN', 'TF_LOG', 'TF_LOG_PATH'} and not k.startswith('TF_CLI_ARGS')}
        self.env.update(AWS_PROFILE=profile, AWS_REGION=REGION, AWS_DEFAULT_REGION=REGION, AWS_PAGER='', CHECKPOINT_DISABLE='1', TF_IN_AUTOMATION='1')
        self.env['TF_DATA_DIR'] = str(self.directory / '.terraform')
        self.aws = Aws(); self.aws.call = self.aws_call
        self.lease, self.deadline, self.last_heartbeat = None, int(time.time()) + 300, 0

    def command(self, command, *, check=True, control=True):
        # Continuously drain both pipes in RAM; never persist raw logs/state.
        process = subprocess.Popen(command, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
        selector = selectors.DefaultSelector(); buffers = {process.stdout: bytearray(), process.stderr: bytearray()}
        for stream in buffers: selector.register(stream, selectors.EVENT_READ)
        try:
            while selector.get_map():
                if control and self.lease:
                    self.heartbeat()
                need(time.time() < self.deadline, 'POWER_DEADLINE_EXPIRED')
                for key, _ in selector.select(0.25):
                    raw = os.read(key.fileobj.fileno(), 65536)
                    if not raw: selector.unregister(key.fileobj)
                    else:
                        buffers[key.fileobj].extend(raw)
                        need(sum(map(len, buffers.values())) <= 64 * 1024**2, 'COMMAND_OUTPUT_LIMIT')
            code = process.wait(timeout=5)
            if check: need(code == 0, 'LOCAL_COMMAND_FAILED')
            return code, bytes(buffers[process.stdout]), bytes(buffers[process.stderr])
        finally:
            selector.close()
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait(timeout=5)

    def aws_call(self, *args):
        # AWS reads / the lease helper only. RDS power mutation lives in Terraform.
        code, raw, error = self.command(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '10', '--cli-read-timeout', '30', *args], check=False, control=False)
        if code: raise Rejected(aws_error(error))
        return json.loads(raw or b'{}')

    def tf(self, *args):
        return self.command(['terraform', '-chdir=' + str(self.lab), *args])[1]

    def pull(self):
        code, raw, error = self.command(['terraform', '-chdir=' + str(self.lab), 'state', 'pull'], check=False)
        if code:
            need(b'No state file was found' in error and not raw.strip(), 'TERRAFORM_STATE_UNAVAILABLE')
            return {'version': 4, 'lineage': 'absent', 'serial': 0, 'outputs': {}, 'resources': []}
        return json.loads(raw)

    def lease_command(self, action, *tail):
        return self.command(['bash', str(SCRIPTS / 'orchestration-lease.sh'), action,
            self.contract['lease_table_name'], self.contract['lease_lock_id'], *map(str, tail)], control=False)[1].decode().strip()

    def heartbeat(self):
        if time.monotonic() - self.last_heartbeat >= 25:
            l = self.lease
            self.lease_command('heartbeat', l['owner'], l['fencingToken'], l['runId'], 'up', 180)
            self.last_heartbeat = time.monotonic()

    def pinned_json(self, bucket, key, directory, name, expected_sha=None, version=None):
        if version is None:
            version = self.aws.call('s3api', 'head-object', '--bucket', bucket, '--key', key)['VersionId']
        path = directory / name
        need(not path.exists(), 'INPUT_COPY_ALREADY_EXISTS')
        result = self.aws.call('s3api', 'get-object', '--bucket', bucket, '--key', key, '--version-id', version, str(path))
        os.chmod(path, 0o600); raw = path.read_bytes()
        need(result.get('VersionId') == version and len(raw) < 4 * 1024**2
             and (expected_sha is None or hashlib.sha256(raw).hexdigest() == expected_sha), 'PINNED_INPUT_CHANGED')
        return json.loads(raw)

    def initialize(self):
        need(self.aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'AWS_CALLER_CHANGED')
        self.contract = json.loads(self.aws.call('ssm', 'get-parameter', '--name', '/airbob/performance-lab/foundation/lab-contract')['Parameter']['Value'])
        need(self.contract['account_id'] == ACCOUNT and self.contract['region'] == REGION
             and self.contract['state_bucket_name'] == 'airbob-performance-lab-tfstate-' + ACCOUNT
             and self.contract['lab_state_key'] == 'airbob/lab/terraform.tfstate', 'LAB_BACKEND_CHANGED')
        self.tf('init', '-input=false', '-lockfile=readonly',
            '-backend-config=bucket=' + self.contract['state_bucket_name'],
            '-backend-config=key=' + self.contract['lab_state_key'],
            '-backend-config=region=' + REGION, '-backend-config=encrypt=true', '-backend-config=use_lockfile=true')
        return self.pull()

    @contextlib.contextmanager
    def acquired(self, state):
        out = outputs(state); run = out['run_identity']['run_id']
        rds = resources(state)['module.rds[0].aws_db_instance.this']
        self.deadline = min(int(time.time()) + 18000, int(rds['tags']['ExpiresAt']))
        need(self.deadline > time.time() + 300, 'RESOURCE_TTL_TOO_SHORT')
        owner = 'local-power/' + uuid.uuid4().hex
        token = self.lease_command('acquire', owner, run, 'up', 180, int(self.deadline - time.time()))
        need(re.fullmatch(r'fencing_token=[1-9][0-9]*', token), 'LEASE_ACQUIRE_UNCONFIRMED')
        self.lease = dict(table=self.contract['lease_table_name'], lockName=self.contract['lease_lock_id'],
            owner=owner, runId=run, command='up', fencingToken=int(token.split('=')[1]))
        try:
            self.heartbeat(); yield
        finally:
            l = self.lease
            self.lease = None
            original_failure = sys.exc_info()[0] is not None
            previous_deadline = self.deadline
            self.deadline = max(self.deadline, int(time.time()) + 45)
            try: self.lease_command('release', l['owner'], l['fencingToken'], run, 'up')
            except Exception:
                if not original_failure: raise Rejected('LEASE_RELEASE_UNCONFIRMED') from None
            finally: self.deadline = previous_deadline

    def inspect(self, state, expected=None, *, healthy=False):
        out, rows = outputs(state), resources(state)
        p2, p3, p4 = [out[x] for x in ('phase2_contract', 'phase3_contract', 'phase4_contract')]
        group = self.aws.call('autoscaling', 'describe-auto-scaling-groups', '--auto-scaling-group-names', p4['auto_scaling_group_name'])['AutoScalingGroups']
        need(len(group) == 1, 'EXACT_ASG_REQUIRED'); group = group[0]
        need([group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')] == [1, 1, 1]
             and len(group['Instances']) == 1 and group['Instances'][0]['LifecycleState'] == 'InService', 'ASG_IDENTITY_OR_CAPACITY_CHANGED')
        app = group['Instances'][0]['InstanceId']
        ids = dict(p2['services'], nat=p2['nat_instance_id'], app=app)
        need(set(ids) == HOSTS and len(set(ids.values())) == 7, 'EXACT_SEVEN_HOSTS_REQUIRED')
        activities = self.aws.call('autoscaling', 'describe-scaling-activities', '--auto-scaling-group-name', group['AutoScalingGroupName'], '--max-items', '20').get('Activities', [])
        need(all(x['StatusCode'] in {'Successful', 'Failed', 'Cancelled'} for x in activities), 'ASG_ACTIVITY_IN_PROGRESS')
        refreshes = self.aws.call('autoscaling', 'describe-instance-refreshes', '--auto-scaling-group-name', group['AutoScalingGroupName'], '--max-records', '20').get('InstanceRefreshes', [])
        need(all(x['Status'] in {'Successful', 'Failed', 'Cancelled', 'RollbackSuccessful', 'RollbackFailed'} for x in refreshes), 'ASG_REFRESH_IN_PROGRESS')
        instances = [x for reservation in self.aws.call('ec2', 'describe-instances', '--instance-ids', *sorted(ids.values()))['Reservations'] for x in reservation['Instances']]
        need({x['InstanceId'] for x in instances} == set(ids.values()) and len(instances) == 7, 'EC2_ID_SET_CHANGED')
        fixed = {}; current = {}
        for item in instances:
            name = next(k for k, v in ids.items() if v == item['InstanceId'])
            tags = {x['Key']: x['Value'] for x in item['Tags']}
            need(tags.get('RunId') == p2['run_id'] and tags.get('FencingToken') == str(p2['fencing_token'])
                 and tags.get('ExpiresAt') == rows['module.rds[0].aws_db_instance.this']['tags']['ExpiresAt'], 'EC2_FENCE_OR_EXPIRY_CHANGED')
            fixed[name] = {k: item.get(k) for k in ('InstanceId', 'ImageId', 'InstanceType', 'SubnetId', 'VpcId', 'PrivateIpAddress', 'IamInstanceProfile')}
            fixed[name]['volumes'] = sorted([b['DeviceName'], b['Ebs']['VolumeId'], b['Ebs']['DeleteOnTermination']] for b in item['BlockDeviceMappings'])
            fixed[name]['securityGroups'] = sorted(x['GroupId'] for x in item['SecurityGroups'])
            current[name] = item['State']['Name']
        rds_request = dict(schemaVersion=1, kind='airbob-rds-power-request', operationId='observe-only',
            runId=p2['run_id'], resourceFencingToken=p2['fencing_token'], expiresAt=int(rows['module.rds[0].aws_db_instance.this']['tags']['ExpiresAt']),
            deadlineEpoch=self.deadline, desiredState='available', identifier=p3['rds_instance_id'], resourceId=p3['rds_resource_id'],
            region=REGION, accountId=ACCOUNT, lease=self.lease, evidenceDirectory=str(self.directory))
        rds_state = observe(self.aws, rds_request)
        identity = {'instances': fixed, 'asgName': group['AutoScalingGroupName'], 'launchTemplate': group['LaunchTemplate'],
            'rdsResourceId': p3['rds_resource_id'], 'rdsIdentifier': p3['rds_instance_id'], 'runtimeRevision': p4['runtime_revision'],
            'resourceFence': p2['fencing_token'], 'expiresAt': rds_request['expiresAt']}
        if expected is not None: need(identity == expected, 'SAME_INSTANCE_OR_VOLUME_CHANGED')
        if healthy:
            need(set(current.values()) == {'running'} and rds_state == 'available'
                 and group['Instances'][0]['HealthStatus'] == 'Healthy', 'RUNNING_HEALTHY_SOURCE_REQUIRED')
        return identity, current, rds_state, sorted(x['ProcessName'] for x in group.get('SuspendedProcesses', []))

    def read_command(self, instance_id, command):
        response = self.aws.call('ssm', 'send-command', '--document-name', 'AWS-RunShellScript', '--instance-ids', instance_id,
            '--parameters', json.dumps({'commands': ['set -euo pipefail\n' + command], 'executionTimeout': ['30']}), '--timeout-seconds', '60')
        command_id = response['Command']['CommandId']
        for _ in range(20):
            self.heartbeat(); time.sleep(3)
            rows = self.aws.call('ssm', 'list-command-invocations', '--command-id', command_id, '--instance-id', instance_id).get('CommandInvocations', [])
            if not rows: continue
            need(len(rows) == 1, 'EXACT_READINESS_INVOCATION_REQUIRED')
            if rows[0]['Status'] == 'Success': return True
            if rows[0]['Status'] in {'Failed', 'Cancelled', 'TimedOut'}: return False
        raise Rejected('READINESS_SSM_OUTCOME_UNKNOWN')

    def wait_dependencies(self, state, identity, manifest, connect=False):
        connector = manifest['cdc']['connectorName']
        need(re.fullmatch(r'airbob-b-[0-9a-f]{20}', connector), 'EXACT_CONNECTOR_NAME_REQUIRED')
        commands = {'redis': 'for service in redis redis-cache; do ids=$(docker ps -q --filter label=com.docker.compose.service="$service"); '
            '[[ $(wc -w <<<"$ids") == 1 ]]; [[ $(docker inspect --format "{{.State.Health.Status}}" "$ids") == healthy ]]; done',
            'kafka': 'ids=$(docker ps -q --filter label=com.docker.compose.service=kafka); [[ $(wc -w <<<"$ids") == 1 ]]; '
            '[[ $(docker inspect --format "{{.State.Health.Status}}" "$ids") == healthy ]]',
            'elasticsearch': "curl --max-time 10 --fail --silent http://127.0.0.1:9200/_cluster/health | jq -e '(.status==\"green\" or .status==\"yellow\") and .timed_out==false' >/dev/null"}
        if connect:
            commands = {'debezium': 'curl --max-time 10 --fail --silent http://127.0.0.1:8083/connectors/' + connector +
                "/status | jq -e '.connector.state==\"RUNNING\" and (.tasks|length)>0 and all(.tasks[];.state==\"RUNNING\")' >/dev/null"}
        while time.time() < self.deadline:
            self.heartbeat(); self.inspect(state, identity)
            if all(self.read_command(identity['instances'][name]['InstanceId'], script) for name, script in commands.items()): return
            time.sleep(10)
        raise Rejected('DEPENDENCY_READINESS_DEADLINE')

    def wait_health(self, state, identity):
        p4 = outputs(state)['phase4_contract']
        # ALB health is /actuator/health; readiness is a separate exact-host GET.
        app = identity['instances']['app']['InstanceId']
        command = "curl --connect-timeout 2 --max-time 10 --fail --silent http://127.0.0.1:8080/actuator/health/readiness | jq -e '.status==\"UP\"' >/dev/null"
        while time.time() < self.deadline:
            self.heartbeat(); self.inspect(state, identity)
            target = self.aws.call('elbv2', 'describe-target-health', '--target-group-arn', p4['target_group_arn'])['TargetHealthDescriptions']
            if len(target) == 1 and target[0]['Target']['Id'] == app and target[0]['TargetHealth']['State'] == 'healthy':
                if self.read_command(app, command): return
            time.sleep(10)
        raise Rejected('APP_READINESS_DEADLINE')

    def apply_phase(self, variables, phase, directory, identity):
        started = time.monotonic()
        self.heartbeat(); before = self.pull(); self.inspect(before, identity)
        source = source_sha(self.lab); state_sha = digest(before)
        suffix = str(self.lease['fencingToken'])
        path = directory / (phase + '-' + suffix + '.tfvars.json'); write_new(path, variables)
        raw = self.tf('plan', '-json', '-input=false', '-lock-timeout=60s', '-var-file=' + str(path))
        changes = validate_plan([json.loads(line) for line in raw.splitlines() if line], phase)
        need(source_sha(self.lab) == source and digest(self.pull()) == state_sha
             and path.read_bytes() == encoded(variables), 'SOURCE_OR_STATE_CHANGED_AFTER_PLAN')
        self.heartbeat(); self.inspect(before, identity)
        self.tf('apply', '-json', '-input=false', '-lock-timeout=60s', '-auto-approve', '-var-file=' + str(path))
        after = self.pull(); _, ec2, rds, suspended = self.inspect(after, identity)
        if phase == 'access':
            need(resources(after)[INGRESS]['cidr_ipv4'] == variables['alb_ingress_cidr'], 'USER_INGRESS_NOT_APPLIED')
        else:
            need(ec2 == states(phase) and rds == ('stopped' if phase == 'stopped' else 'available'), 'POWER_POST_STATE_INCOMPLETE')
            wanted = sorted(variables['lab_power']['original_suspended_processes']) if phase == 'running' else sorted(PROCESSES)
            need(suspended == wanted, 'ASG_SUSPENSION_INCOMPLETE')
        write_new(directory / (phase + '-result-' + suffix + '.json'), {'phase': phase, 'state': 'TERRAFORM_PHASE_VERIFIED',
            'planEvidenceKind': 'terraform-json-ui-with-state-source-recheck',
            'changes': changes, 'sourceSha256': source, 'beforeStateSha256': state_sha,
            'afterStateSha256': digest(after), 'stateSerial': after['serial'], 'stateLineage': after['lineage'],
            'elapsedSeconds': round(time.monotonic() - started, 6),
            'observedAt': datetime.datetime.now(datetime.timezone.utc).isoformat()})
        return after

    def operate(self, operation, cidr=None):
        need(operation in {'pause', 'resume', 'access', 'status'}, 'POWER_OPERATION_INVALID')
        if operation == 'access': cidr = public_cidr(cidr)
        initial = self.initialize()
        if operation == 'status':
            out = outputs(initial)
            if not resources(initial):
                return {'state': 'LAB_ABSENT', 'runId': None, 'phase': None, 'expiresAt': None}
            need(out.get('global_b_service', {}).get('selected') is True
                 and out.get('phase4_contract', {}).get('app_enabled') is True, 'PARTIAL_LAB_REQUIRES_EXISTING_OPERATION_RECOVERY')
            identity, ec2, rds, suspended = self.inspect(initial)
            return {'state': 'POWER_OBSERVED', 'runId': outputs(initial)['run_identity']['run_id'],
                'phase': (outputs(initial).get('lab_power') or {}).get('phase'), 'ec2': ec2,
                'rdsState': rds, 'suspendedProcesses': suspended, 'expiresAt': identity['expiresAt']}
        with self.acquired(initial):
            state = self.pull(); out = outputs(state); current = (out.get('lab_power') or {}).get('phase')
            active_path = self.directory / ('active-' + out['run_identity']['run_id'] + '.json')
            active = read_own(active_path) if active_path.exists() else None
            if active:
                need(not active_path.is_symlink() and active['operation'] == operation, 'FINISH_ACTIVE_POWER_OPERATION_FIRST')
                current = active['lastCompletedPhase']
            operation_id = operation + '-' + uuid.uuid4().hex[:20]
            if active: operation_id = active['operationId']
            directory = self.directory / operation_id; directory.mkdir(mode=0o700, exist_ok=active is not None)
            attempt = directory / ('lease-' + str(self.lease['fencingToken'])); attempt.mkdir(mode=0o700)
            selected = out['global_b_service']
            operator = self.pinned_json(self.contract['evidence_bucket_name'], 'runs/' + out['run_identity']['run_id'] + '/operator.json', attempt, 'operator.json')
            manifest = self.pinned_json(self.contract['dataset_bucket_name'], selected['manifest_key'], attempt, 'manifest.json', selected['manifest_sha256'], selected['manifest_version_id'])
            variables = reconstruct(operator, manifest, state)
            identity, ec2, rds_state, suspended = self.inspect(state,
                expected=active['target'] if active else None, healthy=not active and current in (None, 'running'))
            old_power = variables.get('lab_power')
            if old_power:
                need(old_power['app_instance_id'] == identity['instances']['app']['InstanceId']
                     and old_power['rds_resource_id'] == identity['rdsResourceId']
                     and old_power['identity_sha256'] == digest(identity), 'PRIOR_POWER_TARGET_CHANGED')
            original = active['originalSuspendedProcesses'] if active else old_power['original_suspended_processes'] if old_power else suspended
            need(set(original) <= PROCESSES, 'UNKNOWN_ORIGINAL_ASG_PROCESS')
            if not active and current is not None:
                need(ec2 == states(current) and rds_state == ('stopped' if current == 'stopped' else 'available')
                     and set(suspended) == (set(original) if current == 'running' else PROCESSES), 'POWER_STATE_DRIFT')
            request = {'operation': operation, 'operationId': operation_id,
                'target': identity, 'originalSuspendedProcesses': original, 'deadlineEpoch': self.deadline,
                'lease': self.lease, 'baseTfvarsSha256': digest(variables), 'originalOperatorSha256': digest(operator)}
            write_new(attempt / 'request.json', request)
            if active is None:
                active = {**request, 'lastCompletedPhase': current}
                write_new(active_path, active)
            if operation == 'access':
                need(current in (None, 'running'), 'RESUME_BEFORE_USER_ACCESS')
                variables['alb_ingress_cidr'] = cidr
                state = self.apply_phase(variables, 'access', directory, identity)
            else:
                selected_phases = phases(operation, current)
                if operation == 'resume' and not selected_phases: self.wait_health(state, identity)
                for phase in selected_phases:
                    if phase == 'fenced': self.wait_health(state, identity)
                    if phase == 'connect-running': self.wait_dependencies(state, identity, manifest)
                    if phase == 'app-running': self.wait_dependencies(state, identity, manifest, connect=True)
                    if phase == 'running': self.wait_health(state, identity)
                    variables['lab_power'] = dict(operation_id=operation_id, phase=phase,
                        app_instance_id=identity['instances']['app']['InstanceId'], rds_resource_id=identity['rdsResourceId'],
                        identity_sha256=digest(identity),
                        original_suspended_processes=original, deadline_epoch=self.deadline,
                        evidence_directory=str(directory), lease=self.lease)
                    state = self.apply_phase(variables, phase, directory, identity)
                    active['lastCompletedPhase'] = phase
                    # A crash before rename may leave a previous attempt's file.
                    # A fresh lease gets a distinct temporary name, not a replay.
                    temporary = active_path.with_name(active_path.name + '.' + str(self.lease['fencingToken']) + '-' + phase + '.new')
                    write_new(temporary, active); os.replace(temporary, active_path); sync_directory(active_path.parent)
            result = {'state': {'pause': 'POWER_PAUSED', 'resume': 'POWER_RESUMED', 'access': 'USER_ACCESS_UPDATED'}[operation],
                'runId': out['run_identity']['run_id'], 'sameInstancesAndVolumes': True,
                'rdsResourceId': identity['rdsResourceId'], 'expiresAt': identity['expiresAt'],
                'evidenceDirectory': str(directory), 'normalBusinessCycleVerified': False}
            if operation == 'access':
                dns = out['phase4_contract']['alb_dns_name']
                need(re.fullmatch(r'[A-Za-z0-9.-]+\.elb\.amazonaws\.com', dns), 'ALB_DNS_INVALID')
                result['readinessCommand'] = 'curl --fail --connect-to api.airbob.cloud:443:' + dns + ':443 https://api.airbob.cloud/actuator/health/readiness'
            result_path = directory / 'result.json'
            if result_path.exists():
                # Final evidence may have reached disk before the active pointer
                # was removed. Actual state/readiness above must still pass.
                need(read_own(result_path) == result, 'POWER_COMPLETION_CHANGED')
            else:
                write_new(result_path, result)
            active_path.unlink()
            sync_directory(active_path.parent)
            return result


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['pause', 'resume', 'access', 'status'])
    parser.add_argument('--profile', default=os.environ.get('AWS_PROFILE', 'admin-eeoos'))
    parser.add_argument('--lab-directory', type=Path, default=LAB)
    parser.add_argument('--evidence-directory', type=Path, default=Path.home() / '.local/state/airbob-lab/power')
    parser.add_argument('--cidr', help='access only: current public IPv4 /32; otherwise detected by checkip.amazonaws.com')
    args = parser.parse_args(argv)
    try:
        runner = Runner(args.profile, args.evidence_directory, args.lab_directory)
        lock_path = runner.directory / '.power.lock'
        fd = os.open(lock_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'a') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            cidr = args.cidr
            if args.operation == 'access' and cidr is None:
                with urllib.request.urlopen('https://checkip.amazonaws.com', timeout=10) as response:
                    cidr = response.read(128).decode('ascii').strip() + '/32'
            result = runner.operate(args.operation, cidr)
            print(json.dumps(result, sort_keys=True))
        return 0
    except Exception as error:
        print(json.dumps({'state': 'POWER_INCOMPLETE_RESOURCES_RETAINED',
            'failureCode': str(error) if isinstance(error, Rejected) else type(error).__name__}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
