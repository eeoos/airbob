#!/usr/bin/env python3
"""Closed R4 SSM transport and reversible one-app writer fence.

This module is called only by the existing reviewed-main Lab supervisor. It
does not acquire a shorter session, create resources, change IAM/SGs, dispatch
GitHub, or use StartSession/CancelCommand. R4 1->0->1 is not an R7 scale-out.
"""
from __future__ import annotations

import argparse
import base64
import copy
from datetime import datetime, timezone
import json
import ipaddress
import os
from pathlib import Path
import time

from growth_b_cdc_core import (core, need, Failed, ACCOUNT, REGION, match, HASH, INSTANCE,
                              validate_config, journal_binding, source_identity, original_source_layout, validate_freeze)
from growth_b_cdc import tags, require_tags
import growth_b_aws_restore as restore
import growth_b_service as service


def package_sources():
    return source_identity() | {'infra/aws/scripts/' + name: service.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS}


def verify_environment(config, aws, *, environ=os.environ, now=time.time):
    need(environ.get('GITHUB_ACTIONS') == 'true' and environ.get('GITHUB_REF') == 'refs/heads/main'
         and environ.get('GITHUB_WORKFLOW_REF') == 'eeoos/airbob/.github/workflows/aws-performance-lab.yml@refs/heads/main'
         and environ.get('GITHUB_SHA') == config['executionCommit'], 'REVIEWED_MAIN_LAB_WORKFLOW_REQUIRED')
    try:
        expiration = datetime.fromisoformat(environ['AIRBOB_AWS_CREDENTIAL_EXPIRATION'].replace('Z', '+00:00')).timestamp()
    except (KeyError, ValueError): raise Failed('EXACT_STATIC_STS_EXPIRATION_REQUIRED') from None
    # Same original up lease budget (18000) and expiry margin (300). One-hour
    # chained credentials cannot satisfy this admission and are never requested.
    need(18300 <= expiration - now() <= 21660 and all(environ.get(k) for k in
         ('AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')), 'SIX_HOUR_STATIC_STS_BUDGET_REQUIRED')
    identity = aws.call('sts', 'get-caller-identity')
    need(identity.get('Account') == ACCOUNT and match(r'arn:aws:sts::942632789808:assumed-role/airbob-lab-operator/[A-Za-z0-9+=,.@_-]+', identity.get('Arn')), 'EXACT_LAB_OPERATOR_ROLE_REQUIRED')


OBSERVE = r'''import base64,hashlib,json,subprocess,urllib.request
p=json.loads(base64.b64decode('__PAYLOAD__'))
def call(args):
 r=subprocess.run(['docker','--host','unix:///var/run/docker.sock']+args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=40)
 if r.returncode: raise RuntimeError('DOCKER_COMMAND_UNCONFIRMED')
 return r.stdout
def require(v):
 if not v: raise RuntimeError('EXACT_RUNTIME_REQUIRED')
try:
 ids=call(['ps','-q','--no-trunc','--filter','label=com.docker.compose.service=app']).decode().splitlines() if p['role']=='app' else [p['pin']['containerId']]
 require(len(ids)==1)
 x=json.loads(call(['inspect',ids[0]]))[0]
 require(x['Id']==ids[0] and x['Config']['Image']==p['pin']['image'] and not x['State']['Paused'] and not x['State']['Restarting'])
 if p['role']!='app': require(x['Id']==p['pin']['containerId'] and x['Image']==p['pin']['imageId'])
 if p['action']=='stop':
  require(p['role']=='connect')
  require(x['State']['StartedAt']==p['pin']['startedAt'])
  if x['State']['Running']: call(['stop','--time','30',x['Id']])
  x=json.loads(call(['inspect',x['Id']]))[0]
  require(not x['State']['Running'])
 elif p['action']=='start':
  require(p['role']=='connect')
  expected=p['restartFence']['expectedStartedAt']
  if expected is None:
   require(not x['State']['Running'] and x['State']['FinishedAt']==p['restartFence']['finishedAt'])
   call(['start',x['Id']])
  else: require(x['State']['Running'] and x['State']['StartedAt']==expected)
  x=json.loads(call(['inspect',x['Id']]))[0]
  require(x['State']['Running'])
 elif p['action']=='heartbeat':
  require(p['role']=='kafka' and x['State']['Running'] and x['State']['StartedAt']==p['pin']['startedAt'])
  raw=call(['exec',x['Id'],'timeout','-s','TERM','-k','3','20','/opt/kafka/bin/kafka-get-offsets.sh','--bootstrap-server','localhost:9092','--topic',p['topic'],'--time','-1'])
  lines=raw.decode().strip().splitlines(); require(len(lines)==1)
  topic,partition,offset=lines[0].rsplit(':',2)
  require(topic==p['topic'] and partition=='0' and offset.isdigit())
  p['offsets']={'0':int(offset)}
 else: require(p['action']=='observe' and x['State']['Running'])
 result={'instanceId':p['pin']['instanceId'],'privateIp':p['pin']['privateIp'],'containerId':x['Id'],'imageId':x['Image'],'image':x['Config']['Image'],
         'startedAt':x['State']['StartedAt'],'finishedAt':x['State']['FinishedAt'],'running':x['State']['Running']}
 if p['role']=='app':
  env=dict(e.split('=',1) for e in x['Config']['Env'])
  require(env.get('SPRING_PROFILES_ACTIVE')=='aws' and env.get('SPRING_KAFKA_LISTENER_AUTO_STARTUP')=='true' and env.get('RESERVATION_INVENTORY_STARTUP_ENABLED')=='true')
  require('SPRING_APPLICATION_JSON' not in env and not any(k.startswith('SPRING_CONFIG_') or k.startswith('SPRING_PROFILES_') and k!='SPRING_PROFILES_ACTIVE' for k in env))
  require(not any('spring.profiles' in env.get(k,'') or 'inventory.startup.enabled=false' in env.get(k,'') for k in ('JAVA_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')))
  image=json.loads(call(['image','inspect',x['Image']]))[0]
  require(p['pin']['image'] in image.get('RepoDigests',[]) and image['Config'].get('Labels',{}).get('org.opencontainers.image.revision')==p['runtime']['mainCommit'])
  require(call(['exec',x['Id'],'sha256sum','/app/app.jar']).decode().split()[0]==p['runtime']['imageJarSha256'])
  require(open('/var/lib/airbob/app-runtime-revision').read().strip()==p['runtimeRevision'])
  class NoRedirect(urllib.request.HTTPRedirectHandler):
   def redirect_request(self,*args): return None
  with urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()).open('http://127.0.0.1:8080/actuator/health/readiness',timeout=10) as response:
   require(response.status==200 and json.loads(response.read(262144)).get('status')=='UP')
  result.update(normalProfile='aws',readiness=True,appJarSha256=p['runtime']['imageJarSha256'],runtimeRevision=p['runtimeRevision'])
 if p['action']=='heartbeat': result['heartbeat']={'topic':p['topic'],'offsets':p['offsets'],'instanceId':p['pin']['instanceId'],'containerId':x['Id']}
 print(json.dumps({'passed':True,'observation':result},separators=(',',':')))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'CLOSED_HOST_OPERATION_UNCONFIRMED'}))
 raise SystemExit(1)
'''


def host_program(role, action, config, *, pin=None, runtime=None, restart_fence=None):
    need((role, action) in {('app', 'observe'), ('connect', 'observe'), ('connect', 'stop'), ('connect', 'start'), ('kafka', 'heartbeat')}, 'CLOSED_HOST_OPERATION_REQUIRED')
    data = {'role': role, 'action': action, 'pin': pin or config['hosts'][role]}
    if role == 'app':
        need(runtime is not None, 'VERIFIED_APP_RUNTIME_REQUIRED')
        data.update(runtime=runtime, runtimeRevision=config['asg']['runtimeRevision'])
    if action == 'start':
        need(isinstance(restart_fence, dict) and set(restart_fence) == {'finishedAt', 'expectedStartedAt'}
             and (restart_fence['expectedStartedAt'] is None or match(r'[0-9TZ:.-]{20,40}', restart_fence['expectedStartedAt']))
             and match(r'[0-9TZ:.-]{20,40}', restart_fence['finishedAt']), 'OWNED_CONNECT_RESTART_FENCE_REQUIRED')
        data['restartFence'] = restart_fence
    if action == 'heartbeat':
        suffix = core.digest((config['runId'] + ':' + config['mysqlServerUuid']).encode())[:20]
        data['topic'] = '__debezium-heartbeat.airbob_b_' + suffix
    payload = base64.b64encode(core.encoded(data)).decode()
    return "python3 - <<'AIRBOB_CDC_CLOSED'\n" + OBSERVE.replace('__PAYLOAD__', payload) + '\nAIRBOB_CDC_CLOSED'


def expected_freeze_commands(config):
    return {name: {'instanceId': config['hosts'][role]['instanceId'], 'containerId': config['hosts'][role]['containerId'],
        'commandSha256': core.digest(host_program(role, action, config).encode())}
        for name, role, action in [('connect-stop', 'connect', 'stop'), ('heartbeat', 'kafka', 'heartbeat')]}


HOST_ACTION = r'''import base64,hashlib,json,os,pathlib,subprocess
p=json.loads(base64.b64decode('__PAYLOAD__'))
def require(value):
 if not value: raise RuntimeError('OWNED_HOST_INPUT_REQUIRED')
try:
 root=pathlib.Path('/opt/airbob/global-b')/p['config']['runId']; tools=root/'cdc-tools'
 require(root.resolve()==root and tools.resolve()==tools and tools.is_dir() and tools.stat().st_uid==os.geteuid() and tools.stat().st_mode&0o777==0o700)
 for name,sha in p['sources'].items():
  f=tools/name
  require(f.is_file() and not f.is_symlink() and f.resolve()==f and f.stat().st_uid==os.geteuid() and f.stat().st_mode&0o777==0o600 and hashlib.sha256(f.read_bytes()).hexdigest()==sha)
 area=root/'cdc'/p['config']['operationId']; area.mkdir(mode=0o700,parents=True,exist_ok=True)
 require(area.resolve()==area and area.stat().st_uid==os.geteuid() and area.stat().st_mode&0o777==0o700)
 stage=area/p['stage']; stage.mkdir(mode=0o700,exist_ok=True)
 require(stage.resolve()==stage and stage.stat().st_uid==os.geteuid() and stage.stat().st_mode&0o777==0o700)
 def write(name,value):
  raw=(json.dumps(value,sort_keys=True,separators=(',',':'))+'\n').encode(); path=stage/name
  if path.exists(): require(not path.is_symlink() and path.read_bytes()==raw)
  else:
   fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
   with os.fdopen(fd,'wb') as f: f.write(raw); f.flush(); os.fsync(f.fileno())
  return path
 config=write('config.json',p['config']); output=stage/'receipt.json'
 argv=[str(root/'toolchain/python/bin/python3'),str(tools/'infra/aws/scripts/growth_b_cdc.py'),p['action'],'--config',str(config),'--journal',str(area/'private-journal'),'--output',str(output)]
 for key in ('freeze','restart'):
  if p[key] is not None: argv+=['--'+key+'-observation',str(write(key+'.json',p[key]))]
 env=dict(os.environ,PATH=':'.join(str(root/x) for x in ('aws-bin','toolchain/python/bin','toolchain/jdk/bin','toolchain/mysql/bin'))+':'+os.environ['PATH'],JAVA_HOME=str(root/'toolchain/jdk'),AWS_REGION='ap-northeast-2')
 for k in ('PYTHONPATH','PYTHONSTARTUP','PYTHONINSPECT'): env.pop(k,None)
 code=subprocess.run(argv,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env,timeout=940).returncode
 require(output.is_file() and not output.is_symlink() and output.stat().st_size<=2*1024**2)
 report=json.loads(output.read_bytes()); require(report['action']==p['action'] and report['operationId']==p['config']['operationId'])
 print(json.dumps({'passed':code==0 and report['phasePassed'],'report':report},separators=(',',':')))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'CLOSED_HOST_ACTION_UNCONFIRMED'}))
 raise SystemExit(1)
'''


def action_program(config, action, stage, *, freeze=None, restart=None):
    need(action in ('preflight', 'verify', 'reset', 'post-reset') and match(r'execution-[1-9][0-9]*-[0-9]{4}', stage), 'CLOSED_CDC_ACTION_REQUIRED')
    payload = {'config': config, 'action': action, 'stage': stage, 'sources': package_sources(), 'freeze': freeze, 'restart': restart}
    return "python3 - <<'AIRBOB_CDC_ACTION'\n" + HOST_ACTION.replace('__PAYLOAD__', base64.b64encode(core.encoded(payload)).decode()) + '\nAIRBOB_CDC_ACTION'


def connect_observation(value, pin=None, *, running=True):
    """Persist only a closed runtime identity, never the raw SSM output."""
    need(isinstance(value, dict) and set(value) == {'instanceId', 'privateIp', 'containerId', 'imageId', 'image', 'startedAt', 'finishedAt', 'running'},
         'CLOSED_CONNECT_OBSERVATION_REQUIRED')
    need(match(INSTANCE, value['instanceId']) and match(HASH, value['containerId'])
         and match(r'sha256:[0-9a-f]{64}', value['imageId'])
         and match(r'942632789808\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/airbob-infra/debezium@sha256:[0-9a-f]{64}', value['image'])
         and all(match(r'[0-9TZ:.-]{20,40}', value[k]) for k in ('startedAt', 'finishedAt'))
         and value['running'] is running, 'EXACT_CONNECT_OBSERVATION_REQUIRED')
    try: address = ipaddress.ip_address(value['privateIp'])
    except ValueError: raise Failed('EXACT_CONNECT_PRIVATE_ADDRESS_REQUIRED') from None
    need(address.version == 4 and address.is_private, 'EXACT_CONNECT_PRIVATE_ADDRESS_REQUIRED')
    if pin:
        need(all(value[k] == pin[k] for k in ('instanceId', 'privateIp', 'containerId', 'imageId', 'image')), 'CONNECT_OBSERVATION_TARGET_CHANGED')
    return value


class Ssm:
    TERMINAL = {'Success', 'Failed', 'Cancelled', 'TimedOut', 'Undeliverable', 'Terminated'}
    def __init__(self, aws, journal, guard, *, clock=time.time, sleep=time.sleep):
        self.aws, self.journal, self.guard, self.clock, self.sleep = aws, journal, guard, clock, sleep

    def pending(self):
        intents = {e['data']['token']: e['data'] for e in self.journal.entries if e['kind'] == 'SSM_INTENT'}
        submitted = {e['data']['token']: e['data'] for e in self.journal.entries if e['kind'] == 'SSM_SUBMITTED'}
        done = {e['data']['token'] for e in self.journal.entries if e['kind'] == 'SSM_TERMINAL'}
        need(set(intents) <= set(submitted), 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY')
        return [v for k, v in submitted.items() if k not in done]

    def settle(self, deadline):
        for item in self.pending(): self.wait(item, deadline)

    def wait(self, item, deadline):
        while self.clock() < deadline:
            self.guard()
            rows = self.aws.call('ssm', 'list-command-invocations', '--command-id', item['commandId'], '--instance-id', item['instanceId'])['CommandInvocations']
            need(len(rows) <= 1, 'SSM_INVOCATION_AMBIGUOUS')
            if rows and rows[0]['Status'] in self.TERMINAL:
                result = self.aws.call('ssm', 'get-command-invocation', '--command-id', item['commandId'], '--instance-id', item['instanceId'])
                need(result['InstanceId'] == item['instanceId'] and result['CommandId'] == item['commandId'] and result['Status'] == rows[0]['Status'], 'SSM_RESULT_IDENTITY_CHANGED')
                complete = item | {'status': result['Status'], 'completedEpoch': int(self.clock())}
                value = None
                if result['Status'] == 'Success' and result.get('ResponseCode') == 0:
                    raw = result.get('StandardOutputContent', '').encode(); need(len(raw) <= core.MAX_BYTES, 'SSM_SAFE_OUTPUT_BUDGET')
                    value = core.parse(raw)
                    if item['name'] == 'connect-start' and value.get('passed') is True:
                        observed = connect_observation(value['observation'])
                        need(observed['instanceId'] == item['instanceId'] and observed['containerId'] == item['containerId'], 'SSM_CONNECT_TARGET_CHANGED')
                        complete['safeObservation'] = observed
                self.journal.add('SSM_TERMINAL', complete)
                need(result['Status'] == 'Success' and result.get('ResponseCode') == 0, 'SSM_COMMAND_UNCONFIRMED')
                return value, complete
            self.sleep(min(2, max(0, deadline - self.clock())))
        raise Failed('SSM_COMMAND_STILL_UNCONFIRMED')

    def run(self, name, instance, cid, program, deadline, *, seconds=90):
        self.settle(deadline); self.guard()
        need(match(INSTANCE, instance) and match(HASH, cid) and 1 <= seconds <= 1000, 'EXACT_SSM_TARGET_REQUIRED')
        token = len(self.journal.entries)
        item = {'token': token, 'name': name, 'instanceId': instance, 'containerId': cid,
                'commandSha256': core.digest(program.encode()), 'startedEpoch': int(self.clock())}
        self.journal.add('SSM_INTENT', item)
        reply = self.aws.call('ssm', 'send-command', '--document-name', 'AWS-RunShellScript', '--document-version', '1',
            '--instance-ids', instance, '--timeout-seconds', '60', '--parameters', json.dumps({'commands': [program], 'executionTimeout': [str(seconds)]}))
        command = reply['Command']['CommandId']; need(match(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', command), 'SSM_COMMAND_ID_INVALID')
        item = item | {'commandId': command}; self.journal.add('SSM_SUBMITTED', item)
        return self.wait(item, deadline)


def stable_group(group):
    return {k: v for k, v in group.items() if k not in ('Instances', 'MinSize', 'MaxSize', 'DesiredCapacity', 'PredictedCapacity', 'Status')}


def latest_entry(journal, kind):
    return next((e for e in reversed(journal.entries) if e['kind'] == kind), None)


def timestamp_epoch(value):
    try: return datetime.fromisoformat(value.replace('Z', '+00:00')).timestamp()
    except (AttributeError, ValueError): raise Failed('AWS_TIMESTAMP_REQUIRED') from None


def validate_selected_service(config, manifest, readiness, phase4, state):
    service.validate_manifest(manifest, config['datasetId'], config['runId'], manifest['serviceRelease'])
    service.validate_readiness(readiness, manifest, config['serviceManifest']['sha256'])
    need(phase4['app_enabled'] is True and phase4['capacity'] == {'min': 1, 'desired': 1, 'max': 1}
         and phase4['runtime_revision'] == config['asg']['runtimeRevision'] and phase4['auto_scaling_group_name'] == config['asg']['name']
         and phase4['load_generator_enabled'] is False, 'CURRENT_ONE_APP_TERRAFORM_CONTEXT_REQUIRED')
    need(manifest['rds'] == {k: config['rds'][k] for k in ('identifier', 'resourceId', 'serverUuid')}
         and manifest['application']['image'] == config['hosts']['app']['image']
         and manifest['debezium']['image'] == config['hosts']['connect']['image']
         and manifest['search']['image'] == config['hosts']['elasticsearch']['image'], 'CURRENT_SERVICE_IDENTITY_CHANGED')
    selected, ready = config['serviceManifest'], config['serviceReadiness']
    need(isinstance(state, dict) and state.get('selected') is True and state.get('manifest_key') == selected['key']
         and state.get('manifest_version_id') == selected['versionId'] and state.get('manifest_sha256') == selected['sha256']
         and state.get('readiness_receipt') == {'key': ready['key'], 'version_id': ready['versionId'], 'sha256': ready['sha256'], 'bytes': ready['bytes']},
         'CURRENT_TERRAFORM_SERVICE_REFERENCE_CHANGED')


class Controller:
    def __init__(self, config, manifest, readiness, phase4, journal, *, service_state, aws=None, clock=time.time, sleep=time.sleep, transport=None):
        validate_config(config, check_files=False)
        validate_selected_service(config, manifest, readiness, phase4, service_state)
        self.config, self.manifest, self.ready, self.journal = config, manifest, readiness, journal
        self.aws, self.clock, self.sleep = aws or restore.Aws(), clock, sleep
        self.lease = restore.Lease(self.aws, config['lease'])
        self.deadline = min(clock() + 1200, config['expiresAt'], config['approvedExecutionDeadlineEpoch'])
        self.last_target_check = -float('inf')
        self.transport = transport or Ssm(self.aws, journal, self.guard, clock=clock, sleep=sleep)

    def guard(self, force=False):
        need(self.clock() < self.deadline, 'CONTROLLER_ACTION_DEADLINE_REACHED'); self.lease(force=True)
        if force or self.clock() - self.last_target_check >= 5:
            pin = self.config['rds']
            rows = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', pin['identifier'])['DBInstances']
            need(len(rows) == 1 and rows[0]['DBInstanceIdentifier'] == pin['identifier'] and rows[0]['DbiResourceId'] == pin['resourceId']
                 and rows[0]['Endpoint']['Address'] == pin['endpoint'] and rows[0]['Endpoint']['Port'] == 3306
                 and rows[0]['EngineVersion'] == '8.4.11' and rows[0]['PubliclyAccessible'] is False
                 and rows[0]['DBInstanceStatus'] == 'available', 'CONTROLLER_RDS_TARGET_CHANGED')
            require_tags(tags(rows[0]['TagList']), self.config)
            self.last_target_check = self.clock()

    def instance(self, pin, *, terminated=False):
        self.guard()
        rows = self.aws.call('ec2', 'describe-instances', '--instance-ids', pin['instanceId'])['Reservations']
        rows = [v for r in rows for v in r['Instances']]
        need(len(rows) == 1 and rows[0]['InstanceId'] == pin['instanceId'], 'EXACT_EC2_REQUIRED')
        row = rows[0]; require_tags(tags(row['Tags']), self.config)
        if not terminated:
            need(row['State']['Name'] == 'running' and row['PrivateIpAddress'] == pin['privateIp'], 'RUNNING_EC2_COORDINATES_CHANGED')
        else: need(row['State']['Name'] == 'terminated', 'OLD_APP_INSTANCE_NOT_TERMINATED')
        return row

    def group(self):
        self.guard()
        groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
        groups = [g for g in groups if g['AutoScalingGroupName'].startswith('airbob-' + self.config['runId'] + '-')]
        need(len(groups) == 1, 'RUN_WRITER_ASG_INVENTORY_CHANGED'); group = groups[0]; selected = self.config['asg']
        need(group['AutoScalingGroupName'] == selected['name'] and group['AutoScalingGroupARN'] == selected['arn']
             and group['LaunchTemplate'] == selected['launchTemplate'] and not group.get('MixedInstancesPolicy')
             and group.get('NewInstancesProtectedFromScaleIn', False) is False and not group.get('SuspendedProcesses'), 'EXACT_NORMAL_ASG_REQUIRED')
        require_tags(tags(group['Tags']), self.config)
        saved = self.journal.last('ASG_ORIGINAL')
        if saved: need(stable_group(group) == saved['settings'], 'ORIGINAL_ASG_SETTINGS_CHANGED')
        return group

    def members(self, group, allowed, *, exact=False):
        ids = {x['InstanceId'] for x in group['Instances']}
        need(len(ids) == len(group['Instances']) and (ids == set(allowed) if exact else ids <= set(allowed)), 'ASG_MEMBER_CAS_MISMATCH')
        need(all(x.get('LaunchTemplate') == self.config['asg']['launchTemplate'] and x.get('ProtectedFromScaleIn') is False
                 for x in group['Instances']), 'ASG_MEMBER_TEMPLATE_OR_PROTECTION_CHANGED')
        return ids

    def live_apps(self):
        """Find pending or detached app instances as well as current ASG members."""
        self.guard()
        template = self.config['asg']['launchTemplate']
        rows = self.aws.call('ec2', 'describe-launch-template-versions', '--launch-template-id', template['LaunchTemplateId'],
            '--versions', template['Version'])['LaunchTemplateVersions']
        need(len(rows) == 1 and rows[0]['LaunchTemplateId'] == template['LaunchTemplateId']
             and str(rows[0]['VersionNumber']) == template['Version'], 'EXACT_APP_LAUNCH_TEMPLATE_REQUIRED')
        data = rows[0]['LaunchTemplateData']
        need(match(r'ami-[0-9a-f]{8,17}', data.get('ImageId')) and isinstance(data.get('InstanceType'), str), 'EXACT_APP_AMI_REQUIRED')
        rows = self.aws.call('ec2', 'describe-instances', '--filters', 'Name=tag:RunId,Values=' + self.config['runId'],
            'Name=tag:Service,Values=app', 'Name=instance-state-name,Values=pending,running,shutting-down,stopping,stopped')['Reservations']
        rows = [x for reservation in rows for x in reservation['Instances']]
        need(len(rows) <= 2 and len({x['InstanceId'] for x in rows}) == len(rows), 'RUN_APP_EC2_INVENTORY_UNBOUNDED')
        result = {}
        for row in rows:
            actual = tags(row['Tags']); require_tags(actual, self.config)
            need(actual.get('Service') == 'app' and actual.get('aws:autoscaling:groupName') == self.config['asg']['name']
                 and actual.get('RuntimeRevision') == self.config['asg']['runtimeRevision']
                 and row['ImageId'] == data['ImageId'] and row['InstanceType'] == data['InstanceType']
                 and row['State']['Name'] in ('pending', 'running', 'shutting-down', 'stopping', 'stopped'), 'RUN_APP_EC2_IDENTITY_CHANGED')
            result[row['InstanceId']] = row
        return result

    def active_scaling(self):
        self.guard()
        reply = self.aws.call('autoscaling', 'describe-scaling-activities', '--auto-scaling-group-name', self.config['asg']['name'], '--max-records', '100', '--no-paginate')
        need(not reply.get('NextToken') and len(reply['Activities']) <= 100, 'SCALING_ACTIVITY_INVENTORY_UNBOUNDED')
        active = []
        for row in reply['Activities']:
            need(row['AutoScalingGroupName'] == self.config['asg']['name'] and match(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', row['ActivityId']), 'EXACT_SCALING_ACTIVITY_REQUIRED')
            if row['StatusCode'] not in ('Successful', 'Failed', 'Cancelled'): active.append(row['ActivityId'])
        return sorted(active)

    def replacement_ids(self, group, live, cycle):
        observed = {x['InstanceId'] for x in group['Instances']} | set(live)
        entry = latest_entry(self.journal, 'REPLACEMENT_INSTANCE_OBSERVED')
        known = {entry['data']['instanceId']} if entry and entry['data']['cycleSequence'] == cycle['sequence'] else set()
        need(len(observed) <= 1 and self.config['hosts']['app']['instanceId'] not in observed, 'UNOWNED_REPLACEMENT_INSTANCE')
        if known:
            need(observed <= known, 'OWNED_REPLACEMENT_CHANGED')
        elif observed:
            intent = latest_entry(self.journal, 'ASG_ONE_INTENT')
            need(intent and intent['data']['cycleSequence'] == cycle['sequence'], 'OWNED_ASG_START_INTENT_REQUIRED')
            identifier = next(iter(observed)); row = live.get(identifier)
            if row is None:
                items = self.aws.call('ec2', 'describe-instances', '--instance-ids', identifier)['Reservations']
                items = [x for reservation in items for x in reservation['Instances']]
                need(len(items) == 1 and items[0]['InstanceId'] == identifier, 'EXACT_REPLACEMENT_REQUIRED'); row = items[0]
                require_tags(tags(row['Tags']), self.config)
            launched = timestamp_epoch(row['LaunchTime'])
            need(intent['data']['requestedEpoch'] - 2 <= launched <= self.clock() + 5, 'REPLACEMENT_PREDATES_OWN_RESTART')
            self.journal.add('REPLACEMENT_INSTANCE_OBSERVED', {'cycleSequence': cycle['sequence'], 'instanceId': identifier, 'launchEpoch': launched})
            known = {identifier}
        self.members(group, known)
        return known

    def await_zero(self, *, original=False, cycle=None):
        stable_since = None; observations = 0
        known = {self.config['hosts']['app']['instanceId']} if original else set()
        while self.clock() < self.deadline:
            group = self.group(); live = self.live_apps()
            need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (0, 0, 0), 'ZERO_ASG_CAPACITY_CHANGED')
            if original:
                self.members(group, known); need(set(live) <= known, 'ORIGINAL_APP_EC2_CAS_MISMATCH')
            else: known = self.replacement_ids(group, live, cycle)
            activities = self.active_scaling()
            terminated = True
            if not group['Instances'] and not live and not activities:
                for identifier in known:
                    try: self.instance({'instanceId': identifier}, terminated=True)
                    except Failed as error:
                        if error.code != 'OLD_APP_INSTANCE_NOT_TERMINATED': raise
                        terminated = False
                if terminated:
                    if stable_since is None: stable_since = self.clock()
                    observations += 1
                    if self.clock() - stable_since >= 10 and observations >= 3:
                        return {'terminatedInstanceIds': sorted(known), 'stableSinceEpoch': int(stable_since), 'observedEpoch': int(self.clock()),
                            'stableSeconds': self.clock() - stable_since, 'observations': observations,
                            'activeScalingActivityIds': [], 'liveAppInstanceIds': [], 'serverAtomicCapacityCasAvailable': False}
                else: stable_since = None; observations = 0
            else: stable_since = None; observations = 0
            self.sleep(min(5, max(0, self.deadline - self.clock())))
        raise Failed('ASG_ZERO_STABLE_FENCE_UNCONFIRMED')

    def observe_app(self, pin=None):
        pin = pin or self.config['hosts']['app']; self.instance(pin)
        group = self.group()
        need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
             and len(group['Instances']) == 1 and group['Instances'][0]['InstanceId'] == pin['instanceId']
             and group['Instances'][0].get('LaunchTemplate') == self.config['asg']['launchTemplate'], 'OBSERVED_APP_ASG_MEMBERSHIP_CHANGED')
        program = host_program('app', 'observe', self.config, pin=pin, runtime=self.ready['appRuntime'])
        value, _ = self.transport.run('app-observe', pin['instanceId'], pin['containerId'], program, self.deadline)
        need(value.get('passed') is True, 'APP_RUNTIME_OBSERVATION_FAILED')
        result = value['observation']
        need(result.get('readiness') is True and result.get('normalProfile') == 'aws'
             and result.get('runtimeRevision') == self.config['asg']['runtimeRevision']
             and result.get('appJarSha256') == self.ready['appRuntime']['imageJarSha256'], 'APP_RUNTIME_OBSERVATION_CHANGED')
        return result

    def host_action(self, action, *, freeze=None, restart=None):
        pin = self.config['hosts']['connect']; self.instance(pin)
        stage = f'execution-{self.config["lease"]["fencingToken"]}-{len(self.journal.entries):04d}'
        value, command = self.transport.run('host-' + action, pin['instanceId'], pin['containerId'],
            action_program(self.config, action, stage, freeze=freeze, restart=restart), self.deadline, seconds=1000)
        need(isinstance(value.get('report'), dict), 'HOST_RECEIPT_UNAVAILABLE')
        report = value['report']
        need(report['action'] == action and report['operationId'] == self.config['operationId']
             and report['runId'] == self.config['runId'] and report['mysqlServerUuid'] == self.config['mysqlServerUuid']
             and report['inputBinding']['toolIdentity'] == source_identity(), 'HOST_RECEIPT_BINDING_CHANGED')
        self.journal.add('HOST_' + action.upper().replace('-', '_'), {'report': report, 'command': command})
        need(value.get('passed') is True and report['phasePassed'] is True, 'HOST_PHASE_UNCONFIRMED')
        return report

    def verify(self):
        need(self.journal.last('ASG_ZERO_INTENT') is None, 'WRITER_FENCE_ALREADY_STARTED')
        self.transport.settle(self.deadline)
        group = self.group(); pin = self.config['hosts']['app']
        need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
             and len(group['Instances']) == 1 and group['Instances'][0]['InstanceId'] == pin['instanceId']
             and group['Instances'][0].get('ProtectedFromScaleIn') is False, 'ONE_UNPROTECTED_ORIGINAL_APP_REQUIRED')
        if self.journal.last('ASG_ORIGINAL') is None: self.journal.add('ASG_ORIGINAL', {'settings': stable_group(group), 'capacity': self.config['asg']['originalCapacity']})
        before = self.observe_app()
        need(all(before[k] == pin[k] for k in pin), 'VERIFY_BASELINE_RUNTIME_CHANGED')
        self.journal.add('VERIFY_APP_BEFORE', before)
        report = self.host_action('verify')
        after = self.observe_app()
        need(after == before, 'APP_RESTARTED_DURING_VERIFY')
        self.journal.add('VERIFY_APP_AFTER', after)
        need(self.clock() <= self.config['businessDeadlineEpoch'], 'CONTROLLER_BUSINESS_DEADLINE_REACHED')
        need(report['apiRoundTripVerified'] and report['businessWindow'] is not None, 'BOUNDED_TWO_PATCH_PROOF_REQUIRED')
        self.journal.add('CONTROLLER_VERIFY_COMPLETE', {'sameAppRuntimeDuringTwoPatches': True, 'hostJournalSha256': report['journalHeadSha256']})
        return report

    def freeze(self):
        self.transport.settle(self.deadline)
        need(self.journal.last('ASG_ORIGINAL') is not None, 'ORIGINAL_ASG_JOURNAL_REQUIRED')
        need(self.journal.last('ASG_RESTART_INTENT') is None, 'ORIGINAL_RESET_WINDOW_ALREADY_CLOSED')
        group = self.group(); shape = tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize'))
        need(shape in ((1, 1, 1), (0, 0, 0)), 'ASG_CAPACITY_CAS_MISMATCH')
        original = {self.config['hosts']['app']['instanceId']}
        self.members(group, original, exact=shape == (1, 1, 1))
        need(set(self.live_apps()) <= original, 'ORIGINAL_APP_EC2_CAS_MISMATCH')
        if self.journal.last('ASG_ZERO_INTENT') is None:
            need(shape == (1, 1, 1), 'OWNED_RUNNING_ASG_REQUIRED')
            self.journal.add('ASG_ZERO_INTENT', {'originalInstanceId': self.config['hosts']['app']['instanceId'], 'capacity': {'min': 0, 'desired': 0, 'max': 0}})
        if shape != (0, 0, 0):
            self.guard(force=True)
            # Recompare on every retry immediately before changing capacity.
            # AWS offers no server-side capacity CAS; later membership and EC2
            # observations must also remain within this exact original ID.
            fresh = self.group(); self.members(fresh, original, exact=True)
            need(tuple(fresh[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
                 and set(self.live_apps()) == original, 'ORIGINAL_APP_CAPACITY_CAS_MISMATCH')
            self.aws.call('autoscaling', 'update-auto-scaling-group', '--auto-scaling-group-name', self.config['asg']['name'], '--min-size', '0', '--desired-capacity', '0', '--max-size', '0')
        zero = self.await_zero(original=True)
        self.journal.add('ASG_ZERO_CONFIRMED', {'terminatedInstanceId': self.config['hosts']['app']['instanceId'], 'observation': zero})
        commands = []
        observations = {}
        for name, role, action in [('connect-stop', 'connect', 'stop'), ('heartbeat', 'kafka', 'heartbeat')]:
            pin = self.config['hosts'][role]; self.instance(pin)
            self.journal.add(name.upper().replace('-', '_') + '_INTENT', {'containerId': pin['containerId']})
            result, command = self.transport.run(name, pin['instanceId'], pin['containerId'], host_program(role, action, self.config), self.deadline)
            need(result.get('passed') is True, 'FREEZE_HOST_OBSERVATION_FAILED')
            observations[name] = result['observation']
            commands.append({k: command[k] for k in ('name', 'instanceId', 'containerId', 'commandId', 'commandSha256', 'status', 'startedEpoch', 'completedEpoch')})
        final_group = self.group(); self.members(final_group, set(), exact=True)
        need(tuple(final_group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (0, 0, 0)
             and not self.live_apps() and not self.active_scaling(), 'FREEZE_ZERO_OBSERVATION_CHANGED')
        self.transport.settle(self.deadline)
        value = {'schemaVersion': 1, 'kind': 'global-b-aws-cdc-frozen-observation', 'operationId': self.config['operationId'],
            'configurationSha256': journal_binding(self.config), 'lease': self.config['lease'], 'observedEpoch': int(self.clock()),
            'appTerminatedIds': [self.config['hosts']['app']['instanceId']], 'connectFinishedAt': observations['connect-stop']['finishedAt'],
            'heartbeat': observations['heartbeat']['heartbeat'], 'commands': commands, 'outstandingCommandIds': [],
            'controllerToolSha256': source_identity()['infra/aws/scripts/growth_b_cdc_controller.py']}
        validate_freeze(value, self.config, now=self.clock(), expected_commands=expected_freeze_commands(self.config))
        self.journal.add('FROZEN_OBSERVATION', value)
        return value

    def reset(self):
        # Reobserve a fresh frozen point on each retry; never run a fresh PATCH.
        freeze = self.freeze()
        return self.host_action('reset', freeze=freeze)

    def restart(self):
        try: return self._restart()
        except BaseException:
            self.recover_to_frozen(); raise

    def known_connect(self, cycle):
        confirmed = self.journal.last('CONNECT_RESTART_CONFIRMED')
        if confirmed and confirmed['cycleSequence'] == cycle['sequence']: return confirmed
        intent = latest_entry(self.journal, 'CONNECT_START_INTENT')
        if not intent or intent['data']['cycleSequence'] != cycle['sequence']: return None
        rows = [e['data'] for e in self.journal.entries if e['sequence'] > intent['sequence'] and e['kind'] == 'SSM_TERMINAL'
                and e['data']['name'] == 'connect-start' and e['data']['commandSha256'] == intent['data']['commandSha256']
                and e['data'].get('safeObservation') is not None]
        need(len(rows) <= 1, 'CONNECT_START_RESULT_AMBIGUOUS')
        if not rows: return None
        command = rows[0]; pin = self.config['hosts']['connect']
        observed = connect_observation(command['safeObservation'], pin)
        need(command['status'] == 'Success' and command['instanceId'] == pin['instanceId'] and command['containerId'] == pin['containerId']
             and observed['startedAt'] != pin['startedAt'], 'OWNED_CONNECT_START_RESULT_REQUIRED')
        return {'cycleSequence': cycle['sequence'], 'observation': observed,
                'sourceCommand': {k: command[k] for k in ('commandId', 'commandSha256', 'startedEpoch', 'completedEpoch')}}

    def start_connect(self, cycle, closed):
        pin = self.config['hosts']['connect']; self.instance(pin)
        intent = latest_entry(self.journal, 'CONNECT_START_INTENT')
        prior = intent and intent['data']['cycleSequence'] == cycle['sequence']
        known = self.known_connect(cycle) if prior else None
        if prior:
            # An intent, even with a failed or unknown response, is never
            # authority to adopt an arbitrary currently running lifetime.
            need(known is not None, 'OWNED_CONNECT_START_OUTCOME_UNCONFIRMED')
        fence = {'finishedAt': closed['connectFinishedAt'], 'expectedStartedAt': known['observation']['startedAt'] if known else None}
        program = host_program('connect', 'start', self.config, restart_fence=fence)
        if not prior:
            self.journal.add('CONNECT_START_INTENT', {'cycleSequence': cycle['sequence'], 'containerId': pin['containerId'],
                'finishedAt': fence['finishedAt'], 'commandSha256': core.digest(program.encode())})
        result, command = self.transport.run('connect-start' if not prior else 'connect-restart-observe', pin['instanceId'], pin['containerId'], program, self.deadline)
        need(result.get('passed') is True, 'EXACT_CONNECT_RESTART_REQUIRED')
        observed = connect_observation(result['observation'], pin)
        need(observed['startedAt'] != pin['startedAt'] and (known is None or observed == known['observation']), 'EXACT_CONNECT_RESTART_REQUIRED')
        if known is None:
            known = {'cycleSequence': cycle['sequence'], 'observation': observed,
                     'sourceCommand': {k: command[k] for k in ('commandId', 'commandSha256', 'startedEpoch', 'completedEpoch')}}
        if self.journal.last('CONNECT_RESTART_CONFIRMED') != known: self.journal.add('CONNECT_RESTART_CONFIRMED', known)
        return observed

    def _restart(self):
        self.transport.settle(self.deadline)
        reset = self.journal.last('HOST_RESET')
        need(reset and reset['report']['phasePassed'] and reset['report']['cleanup'], 'OWNED_RESET_REQUIRED_BEFORE_RESTART')
        group = self.group(); shape = tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize'))
        need(shape in ((0, 0, 0), (1, 1, 1)), 'RESTART_CAPACITY_CAS_MISMATCH')
        self.instance(self.config['hosts']['app'], terminated=True)
        stopped = max((latest_entry(self.journal, k) for k in ('SOURCE_CLOSED', 'FROZEN_OBSERVATION') if latest_entry(self.journal, k)),
                      key=lambda e: e['sequence'], default=None)
        need(stopped is not None, 'OBSERVED_CONNECT_STOP_REQUIRED')
        cycle = latest_entry(self.journal, 'ASG_RESTART_INTENT')
        if cycle is None or cycle['sequence'] < stopped['sequence']:
            need(shape == (0, 0, 0) and not group['Instances'], 'EXACT_FROZEN_ASG_REQUIRED')
            need(not self.live_apps() and not self.active_scaling(), 'RESTART_ZERO_FENCE_CHANGED')
            cycle = self.journal.add('ASG_RESTART_INTENT', {'originalCapacity': self.config['asg']['originalCapacity'], 'closedSequence': stopped['sequence']})
        else:
            need(self.journal.latest_sequence('SOURCE_CLOSE_INTENT') < cycle['sequence'], 'OWNED_REFREEZE_MUST_COMPLETE_BEFORE_RESTART')
            self.replacement_ids(group, self.live_apps(), cycle)
        self.start_connect(cycle, stopped['data'])
        if shape == (0, 0, 0):
            self.guard(force=True)
            fresh = self.group(); self.members(fresh, set(), exact=True)
            need(tuple(fresh[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (0, 0, 0)
                 and not self.live_apps() and not self.active_scaling(), 'RESTART_ZERO_FENCE_CHANGED')
            old = latest_entry(self.journal, 'ASG_ONE_INTENT')
            need(old is None or old['data']['cycleSequence'] != cycle['sequence'], 'OWNED_ASG_START_OUTCOME_UNCONFIRMED')
            self.journal.add('ASG_ONE_INTENT', {'cycleSequence': cycle['sequence'], 'requestedEpoch': int(self.clock())})
            self.aws.call('autoscaling', 'update-auto-scaling-group', '--auto-scaling-group-name', self.config['asg']['name'], '--min-size', '1', '--desired-capacity', '1', '--max-size', '1')
        while self.clock() < self.deadline:
            group = self.group()
            need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1) and len(group['Instances']) <= 1, 'RESTART_CAPACITY_CHANGED')
            self.replacement_ids(group, self.live_apps(), cycle)
            if group['Instances'] and group['Instances'][0]['LifecycleState'] == 'InService' and group['Instances'][0]['HealthStatus'] == 'Healthy':
                instance_id = group['Instances'][0]['InstanceId']; need(instance_id != self.config['hosts']['app']['instanceId'], 'OLD_INSTANCE_CANNOT_BE_REBOUND')
                rows = self.aws.call('ec2', 'describe-instances', '--instance-ids', instance_id)['Reservations']
                rows = [v for r in rows for v in r['Instances']]; need(len(rows) == 1, 'EXACT_NEW_APP_REQUIRED')
                pin = dict(self.config['hosts']['app'], instanceId=instance_id, privateIp=rows[0]['PrivateIpAddress'])
                observed = self.observe_app(pin)
                rebound = {k: observed[k] for k in self.config['hosts']['app']}
                prior = latest_entry(self.journal, 'NEW_APP_RUNTIME_BOUND')
                if prior and prior['sequence'] > cycle['sequence']: need(prior['data'] == rebound, 'OWNED_APP_RUNTIME_CHANGED')
                else: self.journal.add('NEW_APP_RUNTIME_BOUND', rebound)
                return rebound
            self.sleep(5)
        raise Failed('NEW_APP_READINESS_UNCONFIRMED')

    def post_reset(self):
        try: return self._post_reset()
        except BaseException:
            self.recover_to_frozen(); raise

    def _post_reset(self):
        pin = self.journal.last('NEW_APP_RUNTIME_BOUND'); need(pin is not None, 'EXPLICIT_NEW_APP_BINDING_REQUIRED')
        bound_connect = self.journal.last('CONNECT_RESTART_CONFIRMED'); need(bound_connect is not None, 'EXACT_CONNECT_RESTART_REQUIRED')
        need(bound_connect['cycleSequence'] == self.journal.latest_sequence('ASG_RESTART_INTENT')
             and self.journal.latest_sequence('NEW_APP_RUNTIME_BOUND') > bound_connect['cycleSequence']
             and self.journal.latest_sequence('SOURCE_CLOSE_INTENT') < bound_connect['cycleSequence'], 'CURRENT_RESTART_RUNTIME_REQUIRED')
        connect = bound_connect['observation']
        def observe_connect():
            target = self.config['hosts']['connect']; self.instance(target)
            value, _ = self.transport.run('connect-observe', target['instanceId'], target['containerId'], host_program('connect', 'observe', self.config), self.deadline)
            need(value.get('passed') is True and value['observation'] == connect, 'POST_RESET_CONNECT_IDENTITY_CHANGED')
        observe_connect()
        before = self.observe_app(pin)
        need(all(before[k] == pin[k] for k in pin), 'POST_RESET_APP_IDENTITY_CHANGED')
        result = self.host_action('post-reset', restart=pin)
        need(self.observe_app(pin) == before, 'POST_RESET_APP_RESTARTED')
        observe_connect()
        self.journal.add('CONTROLLER_POST_RESET_COMPLETE', {'newAppRuntimeObserved': True, 'sourceSnapshotGateSatisfied': False,
            'restartSequence': bound_connect['cycleSequence'], 'appBindingSequence': self.journal.latest_sequence('NEW_APP_RUNTIME_BOUND'),
            'connectBindingSequence': self.journal.latest_sequence('CONNECT_RESTART_CONFIRMED'),
            'appRuntimeSha256': core.digest(core.encoded(pin)), 'connectRuntimeSha256': core.digest(core.encoded(connect))})
        return result

    def close(self):
        """Close the owned replacement after post-health, or recover a failed restart.

        Source fingerprint/inventory validation and snapshot creation remain
        separate gates. No SQL reset, purge, or full equality is inferred here.
        """
        self.transport.settle(self.deadline)
        need(self.journal.last('ASG_RESTART_INTENT') is not None and self.journal.last('HOST_RESET')
             and self.journal.last('HOST_RESET')['report']['phasePassed'], 'OWNED_RESTART_AND_RESET_REQUIRED')
        group = self.group(); shape = tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize'))
        need(shape in ((0, 0, 0), (1, 1, 1)) and len(group['Instances']) <= 1, 'SOURCE_CLOSE_CAPACITY_CHANGED')
        cycle = latest_entry(self.journal, 'ASG_RESTART_INTENT')
        ids = self.replacement_ids(group, self.live_apps(), cycle)
        prior = latest_entry(self.journal, 'SOURCE_CLOSE_INTENT')
        if prior is None or prior['data']['cycleSequence'] != cycle['sequence']:
            self.journal.add('SOURCE_CLOSE_INTENT', {'cycleSequence': cycle['sequence'], 'replacementInstanceIds': sorted(ids)})
        if shape != (0, 0, 0):
            self.guard(force=True)
            fresh = self.group()
            need(tuple(fresh[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1), 'SOURCE_CLOSE_CAPACITY_CHANGED')
            self.replacement_ids(fresh, self.live_apps(), cycle)
            self.aws.call('autoscaling', 'update-auto-scaling-group', '--auto-scaling-group-name', self.config['asg']['name'], '--min-size', '0', '--desired-capacity', '0', '--max-size', '0')
        zero = self.await_zero(cycle=cycle)
        ids = zero['terminatedInstanceIds']
        pin = dict(self.config['hosts']['connect'])
        restarted = self.known_connect(cycle)
        if restarted: pin['startedAt'] = restarted['observation']['startedAt']
        elif self.journal.latest_sequence('CONNECT_START_INTENT') > cycle['sequence']:
            raise Failed('OWNED_CONNECT_START_OUTCOME_UNCONFIRMED')
        self.instance(pin)
        result, command = self.transport.run('source-connect-stop', pin['instanceId'], pin['containerId'],
            host_program('connect', 'stop', self.config, pin=pin), self.deadline)
        need(result.get('passed') is True, 'SOURCE_CONNECT_STOP_UNCONFIRMED')
        stopped = connect_observation(result['observation'], pin, running=False)
        need(stopped['startedAt'] == pin['startedAt'], 'SOURCE_CONNECT_LIFETIME_CHANGED')
        final_group = self.group(); self.members(final_group, set(), exact=True)
        need(tuple(final_group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (0, 0, 0)
             and not self.live_apps() and not self.active_scaling(), 'SOURCE_ZERO_OBSERVATION_CHANGED')
        value = {'writersStopped': True, 'cdcStopped': True, 'replacementInstanceIds': ids,
            'cycleSequence': cycle['sequence'], 'zeroObservation': zero,
            'connectFinishedAt': stopped['finishedAt'], 'commandId': command['commandId'],
            'lease': self.config['lease'], 'sourceSnapshotGateSatisfied': False}
        self.journal.add('SOURCE_CLOSED', value)
        return value

    def recover_to_frozen(self):
        if self.journal.last('ASG_RESTART_INTENT') is None: return
        try: self.close()
        except BaseException:
            self.journal.add('REFREEZE_UNCONFIRMED', {'privateJournalRetained': True, 'automaticCancellation': False,
                'sourceSnapshotGateSatisfied': False})

    def public(self, action, passed, failure=None):
        health = self.journal.last('CONTROLLER_POST_RESET_COMPLETE')
        current_health = bool(health and health['restartSequence'] == self.journal.latest_sequence('ASG_RESTART_INTENT')
            and health['appBindingSequence'] == self.journal.latest_sequence('NEW_APP_RUNTIME_BOUND')
            and health['connectBindingSequence'] == self.journal.latest_sequence('CONNECT_RESTART_CONFIRMED')
            and self.journal.latest_sequence('CONTROLLER_POST_RESET_COMPLETE') > self.journal.latest_sequence('CONNECT_START_INTENT'))
        return {'schemaVersion': 1, 'kind': 'global-b-aws-cdc-controller-receipt', 'operationId': self.config['operationId'],
            'runId': self.config['runId'], 'action': action, 'phasePassed': passed, 'failureCode': failure,
            'configurationSha256': journal_binding(self.config), 'toolIdentity': source_identity(), 'journalHeadSha256': self.journal.last_sha,
            'twoPatchRuntimeVerified': self.journal.last('CONTROLLER_VERIFY_COMPLETE') is not None,
            'ownedResetConfirmed': bool(self.journal.last('HOST_RESET') and self.journal.last('HOST_RESET')['report']['phasePassed']),
            'postResetHealthConfirmed': current_health,
            'sourceClosed': self.journal.last('SOURCE_CLOSED') if self.journal.latest_sequence('SOURCE_CLOSED') > self.journal.latest_sequence('CONNECT_START_INTENT') else None,
            'outstandingCommands': self.transport.pending(), 'r7ScaleOutTest': False, 'sourceSnapshotGateSatisfied': False,
            'externalPreparedFingerprintInventoryAndRemainingR4EvidenceRequired': True, 'privateValuesIncluded': False,
            'automaticRestartOnFailure': False, 'ownedRestartRefreezeOnFailure': True, 'completedAt': core.utc()}


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('plan', 'verify', 'freeze', 'reset', 'restart', 'post-reset', 'close'))
    for name in ('config', 'manifest', 'readiness', 'phase4', 'service-state', 'journal', 'output'): parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args(argv); journal = None; controller = None
    try:
        config = core.parse(core.reads.read_bytes(args.config)); validate_config(config, check_files=False)
        blobs = [core.reads.read_bytes(p) for p in (args.manifest, args.readiness)]
        need(all(core.digest(raw) == config[key]['sha256'] for raw, key in zip(blobs, ('serviceManifest', 'serviceReadiness'))), 'CONTROLLER_SERVICE_BYTES_CHANGED')
        phase4 = core.parse(core.reads.read_bytes(args.phase4)); state = core.parse(core.reads.read_bytes(args.service_state))
        validate_selected_service(config, *(core.parse(raw) for raw in blobs), phase4, state)
        if args.action == 'plan':
            core.write_new(args.output, core.encoded({'state': 'AWS_CDC_CODE_PLAN_ONLY', 'helperSources': package_sources(),
                'originalSourceLayout': original_source_layout(),
                'hostToolRoot': '/opt/airbob/global-b/' + config['runId'] + '/cdc-tools', 'remoteWritesExecuted': False,
                'requiredSgRule': 'B-only app TCP8080 from exact retained Connect SG', 'sourceSnapshotGateSatisfied': False})); return 0
        aws = restore.Aws(); verify_environment(config, aws)
        journal = core.Journal(args.journal, journal_binding(config), core.digest(core.encoded(source_identity())))
        controller = Controller(config, *(core.parse(raw) for raw in blobs), phase4, journal, service_state=state, aws=aws)
        try:
            {'verify': controller.verify, 'freeze': controller.freeze, 'reset': controller.reset, 'restart': controller.restart, 'post-reset': controller.post_reset, 'close': controller.close}[args.action]()
            report = controller.public(args.action, True)
        except BaseException as error:
            code = error.code if isinstance(error, Failed) else 'CONTROLLER_ACTION_UNCONFIRMED'
            journal.add('CONTROLLER_UNCONFIRMED', {'action': args.action, 'code': code, 'automaticRestart': False})
            # Keep even an ambiguous submission recoverable without attempting
            # another SendCommand or hiding a missing command identifier.
            try: report = controller.public(args.action, False, code)
            except Failed: report = {'phasePassed': False, 'failureCode': code, 'privateJournalRetained': True, 'sourceSnapshotGateSatisfied': False}
        core.write_new(args.output, core.encoded(report)); print(json.dumps({'phasePassed': report['phasePassed'], 'receiptSha256': core.digest(core.encoded(report))}))
        return 0 if report['phasePassed'] else 1
    except BaseException as error:
        code = error.code if isinstance(error, Failed) else 'CONTROLLER_ADMISSION_UNCONFIRMED'
        print(json.dumps({'phasePassed': False, 'failureCode': code, 'privateJournalRetained': journal is not None})); return 1
    finally:
        if journal: journal.close()


if __name__ == '__main__': raise SystemExit(main())
