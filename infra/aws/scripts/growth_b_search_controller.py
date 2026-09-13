#!/usr/bin/env python3
"""Leased, two-host native search continuation of an actual prepared B run.

This stage does not apply Terraform, import SQL, start writers or change IAM.
Every remote submission has a durable intent. An unknown submission is never
retried. ES receives short controller attestations because its existing role has
no permission to read the orchestration lease or ASG state.
"""
from __future__ import annotations

import argparse
import base64
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import tarfile
import time

import growth_b_aws_restore as restore
import growth_b_prepare as prepare
import growth_b_search_host as host
import growth_b_service as service

KIND = 'global-b-aws-native-search-operation'
CONTEXT_KIND = 'global-b-aws-native-search-controller-context'
ACCOUNT, REGION, DATASET = host.ACCOUNT, host.REGION, host.DATASET
ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = service.EVIDENCE
MAX_COMMAND = 32768
CHUNK = 6144
MAX_ARCHIVE = 512 * 1024
MAX_EXPANDED = 8 * 1024**2
MAX_OUTPUT = 16000
ACK_INTERVAL = 40
ACK_TTL = 90
HOST_COMPLETION_MARGIN = 600
TERMINAL = {'Success', 'Failed', 'TimedOut', 'Cancelled', 'Cancelling', 'Delivery Timed Out', 'Execution Timed Out', 'Undeliverable', 'Terminated', 'InvalidPlatform', 'AccessDenied'} - {'Cancelling'}

need = host.need
encoded = host.canonical
decode = host.decode
read = host.read


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def source_files():
    return host.source_files() | {'infra/aws/scripts/growth_b_search_controller.py': host.sha(__file__)}


def source_archive(selected=None, root=ROOT):
    selected = source_files() if selected is None else selected
    need(1 <= len(selected) <= 32, 'SOURCE_COUNT')
    stream = io.BytesIO(); inventory = {}; total = 0
    with tarfile.open(fileobj=stream, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, expected in sorted(selected.items()):
            need(re.fullmatch(r'infra/aws/scripts/[A-Za-z0-9_-]+\.py', name) is not None, 'SOURCE_PATH')
            path = root / name
            raw = host.regular(path, 512 * 1024)
            total += len(raw)
            need(checksum(raw) == expected and total <= MAX_EXPANDED, 'SOURCE_BYTES')
            info = tarfile.TarInfo(name); info.size = len(raw); info.mode = 0o600; info.mtime = 0
            archive.addfile(info, io.BytesIO(raw))
            inventory[name] = {'sha256': expected, 'bytes': len(raw)}
    raw = gzip.compress(stream.getvalue(), mtime=0)
    # gzip's platform byte is canonical across Python versions.
    raw = raw[:9] + b'\xff' + raw[10:]
    need(len(raw) <= MAX_ARCHIVE and len(stream.getvalue()) <= MAX_EXPANDED, 'SOURCE_ARCHIVE_SIZE')
    return raw, {'sha256': checksum(raw), 'bytes': len(raw), 'files': inventory, 'expandedBytes': total}


def validate_operation(value, *, check_sources=True):
    host.keys(value, 'schemaVersion kind stage operationId runId datasetId serviceRelease executionCommit sourceArchiveSha256 manifest', 'OPERATION_FIELDS')
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['stage'] == 'native-restore'
         and value['datasetId'] == DATASET, 'OPERATION_SCOPE')
    for name in ('operationId', 'serviceRelease'):
        need(isinstance(value[name], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}', value[name])
             and '--' not in value[name] and not value[name].endswith('-'), 'OPERATION_COORDINATE')
    need(isinstance(value['runId'], str) and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', value['runId'])
         and '--' not in value['runId'] and not value['runId'].endswith('-'), 'OPERATION_RUN')
    need(isinstance(value['executionCommit'], str) and re.fullmatch(r'[0-9a-f]{40}', value['executionCommit'])
         and host.digest(value['sourceArchiveSha256']), 'OPERATION_SOURCE')
    key = f'datasets/{DATASET}-aws-service/{value["serviceRelease"]}/aws-service.json'
    host.reference(value['manifest'], key)
    need(value['manifest']['key'] == key, 'OPERATION_MANIFEST_KEY')
    if check_sources:
        need(source_archive()[1]['sha256'] == value['sourceArchiveSha256'], 'REVIEWED_SOURCE_ARCHIVE_CHANGED')
    return value


def validate_context(value, operation, *, now=None):
    now = int(time.time()) if now is None else now
    host.keys(value, 'schemaVersion kind executionCommit approvedExecutionDeadlineEpoch controllerDeadlineEpoch operator lease phase2 phase3 phase4 serviceState', 'CONTROLLER_CONTEXT_FIELDS')
    need(value['schemaVersion'] == 1 and value['kind'] == CONTEXT_KIND
         and value['executionCommit'] == operation['executionCommit'], 'CONTROLLER_CONTEXT_IDENTITY')
    original, lease = value['operator'], value['lease']
    need(original.get('schemaVersion') == 2 and original.get('runId') == operation['runId']
         and original.get('datasetRelease') == DATASET and original.get('globalBPrepareOnly') is True
         and original.get('databaseBootstrap') == 'dump' and original.get('mode') == 'performance'
         and original.get('dnsMode') == 'direct-only' and original.get('rdsEngineVersion') == '8.4.11'
         and original.get('cacheEnabled') is False and original.get('loadGeneratorEnabled') is False
         and host.integer(original.get('fencingToken'), 1), 'ORIGINAL_FINAL_PREPARATION_REQUIRED')
    need(value['approvedExecutionDeadlineEpoch'] == original.get('approvedExecutionDeadlineEpoch') == host.DEADLINE
         and host.integer(value['controllerDeadlineEpoch'], now + 120, now + 18000)
         and value['controllerDeadlineEpoch'] <= int(original['expiresAt']) <= host.DEADLINE, 'ORIGINAL_DEADLINE_REQUIRED')
    need(int(original['expiresAt']) > now + 20700, 'ORIGINAL_RESOURCE_TTL_CANNOT_COVER_SERVICE_OPERATION')
    host.keys(lease, 'table lockName owner runId command fencingToken', 'CONTROLLER_LEASE_FIELDS')
    need(lease['table'] == 'airbob-performance-lab-orchestration-lease' and lease['lockName'] == 'airbob-performance-lab'
         and lease['runId'] == operation['runId'] and lease['command'] == 'up'
         and host.integer(lease['fencingToken'], original['fencingToken'] + 1)
         and isinstance(lease['owner'], str) and re.fullmatch(r'[A-Za-z0-9._:@/-]{3,128}', lease['owner']), 'NEW_CONTROLLER_LEASE_REQUIRED')
    p2, p3, p4, state = (value[name] for name in ('phase2', 'phase3', 'phase4', 'serviceState'))
    need(p2['run_id'] == operation['runId'] and p2['fencing_token'] == original['fencingToken']
         and {'debezium', 'kafka', 'elasticsearch'} <= set(p2['services'])
         and all(re.fullmatch(r'i-[0-9a-f]{17}', p2['services'][k]) for k in ('debezium', 'kafka', 'elasticsearch')),
         'RETAINED_DEPENDENCY_HOSTS_REQUIRED')
    need(p3['dataset_release'] == DATASET and p3['database_bootstrap'] == 'dump'
         and p3['rds_instance_id'] == 'airbob-' + operation['runId'] and p3['rds_engine_version'] == '8.4.11'
         and p3['rds_configured_storage_gib'] == 100, 'RETAINED_RDS_REQUIRED')
    need(p4['app_enabled'] is False and p4['capacity'] == {'min': 0, 'desired': 0, 'max': 0}
         and p4['accommodation_detail_cache_enabled'] is False and p4['load_generator_enabled'] is False, 'ZERO_WRITER_TOPOLOGY_REQUIRED')
    reference = operation['manifest']
    need(state['selected'] is True and state['manifest_key'] == reference['key']
         and state['manifest_version_id'] == reference['versionId'] and state['manifest_sha256'] == reference['sha256']
         and state['readiness_receipt'] is None, 'DEPENDENCIES_MANIFEST_REQUIRED')
    return value


class Journal:
    def __init__(self, directory):
        self.directory = Path(directory); self.directory.mkdir(mode=0o700)
        self.entries = []; self.last = '0' * 64

    def add(self, kind, value):
        need(len(self.entries) < 8192, 'JOURNAL_LIMIT')
        host.public_json(value)
        item = {'sequence': len(self.entries), 'previousSha256': self.last, 'kind': kind,
                'observedAtEpoch': int(time.time()), 'data': value}
        raw = encoded(item)
        need(len(raw) <= host.MAX_PUBLIC_BYTES, 'JOURNAL_ITEM_LIMIT')
        host.new_file(self.directory / f'{len(self.entries):06d}.json', raw)
        self.entries.append(item); self.last = checksum(raw)
        return item


class Aws:
    ALLOWED = {('sts', 'get-caller-identity'), ('dynamodb', 'get-item'), ('rds', 'describe-db-instances'),
        ('ec2', 'describe-instances'), ('autoscaling', 'describe-auto-scaling-groups'),
        ('autoscaling', 'describe-scaling-activities'), ('ssm', 'send-command'),
        ('ssm', 'list-command-invocations'), ('ssm', 'get-command-invocation'),
        ('s3api', 'get-object'), ('s3api', 'put-object')}

    def __init__(self, deadline, *, clock=time.time):
        self.deadline, self.clock = deadline, clock

    def call(self, *args):
        need(tuple(args[:2]) in self.ALLOWED, 'AWS_ACTION_NOT_ALLOWED')
        remaining = self.deadline - self.clock()
        need(remaining > 0, 'CONTROLLER_DEADLINE_REACHED')
        env = dict(os.environ, AWS_MAX_ATTEMPTS='1', AWS_RETRY_MODE='standard', AWS_CLI_AUTO_PROMPT='off')
        raw = restore.command(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '5', '--cli-read-timeout', str(max(1, min(20, int(remaining)))), *args],
            timeout=min(30, remaining), env=env)
        return decode(raw or b'{}')


class Ssm:
    """One asynchronous worker and independently settled, immutable ACK writes."""
    def __init__(self, aws, journal, guard, deadline, *, clock=time.time, sleep=time.sleep):
        self.aws, self.journal, self.guard, self.deadline = aws, journal, guard, deadline
        self.clock, self.sleep = clock, sleep
        self.pending = {}; self.unknown = False

    def submit(self, name, instance, program, *, seconds=90, acknowledgement=False):
        need(not self.unknown, 'UNKNOWN_SUBMISSION_REQUIRES_REVIEW')
        need(not self.pending or acknowledgement, 'REMOTE_COMMAND_MUST_SETTLE')
        need(not acknowledgement or name in {'lease-ack', 'discover-preparation'}, 'SSM_CONCURRENT_ACTION_NOT_ALLOWED')
        self.guard()
        need(re.fullmatch(r'i-[0-9a-f]{17}', instance) and type(seconds) is int and 1 <= seconds <= 18000
             and self.clock() + seconds <= self.deadline, 'SSM_TARGET_OR_DEADLINE')
        parameters = {'commands': [program], 'executionTimeout': [str(seconds)]}
        request = {'DocumentName': 'AWS-RunShellScript', 'DocumentVersion': '1', 'InstanceIds': [instance],
                   'TimeoutSeconds': 60, 'Parameters': parameters}
        need(len(encoded(request)) <= MAX_COMMAND, 'SSM_COMMAND_LIMIT')
        item = {'name': name, 'instanceId': instance, 'commandSha256': checksum(program.encode()),
                'submittedAtEpoch': int(self.clock()), 'executionTimeoutSeconds': seconds}
        self.journal.add('SSM_INTENT', item)
        self.unknown = True
        result = self.aws.call('ssm', 'send-command', '--document-name', 'AWS-RunShellScript', '--document-version', '1',
            '--instance-ids', instance, '--timeout-seconds', '60', '--parameters', json.dumps(parameters))
        identifier = result.get('Command', {}).get('CommandId')
        need(isinstance(identifier, str) and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', identifier), 'SSM_SUBMISSION_UNKNOWN')
        item |= {'commandId': identifier}
        self.journal.add('SSM_SUBMITTED', item)
        self.pending[identifier] = item; self.unknown = False
        return item

    def poll(self, item):
        self.guard()
        rows = self.aws.call('ssm', 'list-command-invocations', '--command-id', item['commandId'], '--instance-id', item['instanceId'])['CommandInvocations']
        need(len(rows) <= 1, 'SSM_INVOCATION_AMBIGUOUS')
        if not rows or rows[0]['Status'] not in TERMINAL:
            return None
        result = self.aws.call('ssm', 'get-command-invocation', '--command-id', item['commandId'], '--instance-id', item['instanceId'])
        need(result['CommandId'] == item['commandId'] and result['InstanceId'] == item['instanceId']
             and result['Status'] == rows[0]['Status'], 'SSM_RESULT_IDENTITY')
        terminal = item | {'status': result['Status'], 'responseCode': result.get('ResponseCode'), 'completedAtEpoch': int(self.clock())}
        self.journal.add('SSM_TERMINAL', terminal)
        self.pending.pop(item['commandId'])
        # Never record stderr or arbitrary failed-program output.
        need(result['Status'] == 'Success' and result.get('ResponseCode') == 0, 'SSM_COMMAND_FAILED')
        raw = result.get('StandardOutputContent', '').encode()
        need(0 < len(raw) <= MAX_OUTPUT, 'SSM_PUBLIC_OUTPUT_LIMIT')
        value = decode(raw); host.public_json(value)
        return value

    def wait(self, item, *, tick=None):
        while self.clock() < self.deadline:
            if tick is not None:
                tick()
            value = self.poll(item)
            if value is not None:
                return value
            self.sleep(min(2, self.deadline - self.clock()))
        raise host.Rejected('SSM_TERMINAL_UNCONFIRMED')

    def run(self, name, instance, program, *, seconds=90, acknowledgement=False):
        return self.wait(self.submit(name, instance, program, seconds=seconds, acknowledgement=acknowledgement))


REMOTE_COMMON = r'''import base64,fcntl,gzip,hashlib,io,json,os,pathlib,re,signal,stat,subprocess,tarfile,tempfile,time,urllib.request
p=json.loads(base64.b64decode('__PAYLOAD__',validate=True))
def need(ok):
 if not ok: raise RuntimeError('NATIVE_SEARCH_REMOTE_REJECTED')
def digest(raw): return hashlib.sha256(raw).hexdigest()
def emit(value): print(json.dumps(value,sort_keys=True,separators=(',',':')))
def regular(path,maximum=8388608):
 path=pathlib.Path(path); need(path.is_absolute() and path.resolve()==path)
 with os.fdopen(os.open(path,os.O_RDONLY|os.O_NOFOLLOW),'rb') as f:
  st=os.fstat(f.fileno()); need(stat.S_ISREG(st.st_mode) and st.st_uid==0 and stat.S_IMODE(st.st_mode)==0o600 and st.st_size<=maximum)
  raw=f.read(maximum+1); after=os.fstat(f.fileno())
  need(len(raw)==st.st_size and (st.st_ino,st.st_size,st.st_mtime_ns)==(after.st_ino,after.st_size,after.st_mtime_ns)); return raw
def create(path,raw):
 with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') as f: f.write(raw); f.flush(); os.fsync(f.fileno())
 fd=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
 try: os.fsync(fd)
 finally: os.close(fd)
def safe_parent(path,make=False):
 for part in reversed([path]+list(path.parents)):
  if make and not part.exists(): part.mkdir(mode=0o700)
  st=part.lstat(); need(stat.S_ISDIR(st.st_mode) and st.st_uid==0 and not st.st_mode&0o022)
def call(args,timeout=15):
 env={k:v for k,v in os.environ.items() if not k.startswith('AWS_') and k not in ('PYTHONPATH','PYTHONHOME','DOCKER_HOST','DOCKER_CONTEXT')}
 env.update(AWS_MAX_ATTEMPTS='1',AWS_RETRY_MODE='standard',AWS_CLI_AUTO_PROMPT='off',AWS_CONFIG_FILE='/dev/null',AWS_SHARED_CREDENTIALS_FILE='/dev/null')
 x=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=timeout,env=env)
 need(x.returncode==0 and len(x.stdout)<=1048576); return x.stdout
def docker(args): return call(['docker','--host','unix:///var/run/docker.sock']+args)
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args): return None
def get(url):
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
 with opener.open(url,timeout=10) as response:
  need(response.status==200); raw=response.read(262145); need(len(raw)<=262144); return json.loads(raw)
need(os.getuid()==os.geteuid()==0 and pathlib.Path('/sys/devices/virtual/dmi/id/board_asset_tag').read_text().strip()==p['instanceId'])
need(time.time()<p['deadlineEpoch'] and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}',p['runId']) and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}',p['operationId']))
area=pathlib.Path('/opt/airbob/native-search')/p['runId']/p['operationId']
'''

DISCOVER = r'''
ids=docker(['ps','-q','--no-trunc','--filter','label=com.docker.compose.service='+p['service']]).decode().splitlines(); need(len(ids)==1)
x=json.loads(docker(['inspect',ids[0]]))[0]
need(x['Id']==ids[0] and x['Config']['Image']==p['image'] and x['State']['Running'] and not x['State']['Paused'] and not x['State']['Restarting'])
image=json.loads(docker(['image','inspect',x['Image']]))[0]; need(p['image'] in image.get('RepoDigests',[]))
pin={'instanceId':p['instanceId'],'container':x['Name'].lstrip('/'),'containerId':x['Id'],'imageId':x['Image'],'image':p['image'],'startedAt':x['State']['StartedAt']}
if p['service']=='elasticsearch':
 identity=get('http://127.0.0.1:9200/'); need(identity['version']['number']=='8.18.8'); pin['clusterUuid']=identity['cluster_uuid']
else:
 need(p['service']=='debezium' and get('http://127.0.0.1:8083/connectors')==[])
emit({'passed':True,'pin':pin})
'''

INIT = r'''
safe_parent(area.parent,make=True); area.mkdir(mode=0o700)
for name in ('chunks','acks','docker-client'): (area/name).mkdir(mode=0o700)
create(area/'staging-intent.json',json.dumps(p,sort_keys=True,separators=(',',':')).encode()+b'\n')
emit({'passed':True})
'''

CHUNK_WRITE = r'''
safe_parent(area); need(not (area/'worker-intent.json').exists())
need(p['kind'] in ('context','archive','package') and type(p['sequence']) is int and 0<=p['sequence']<1400)
raw=base64.b64decode(p['data'],validate=True); need(0<len(raw)<=6144 and digest(raw)==p['sha256'])
create(area/'chunks'/('%s-%06d'%(p['kind'],p['sequence'])),raw)
emit({'passed':True,'sha256':digest(raw)})
'''

ASSEMBLE = r'''
safe_parent(area); need(not (area/'worker-intent.json').exists())
names={'context':'context.json','archive':'source.tar.gz','package':'source-package.json'}
need(p['kind'] in names and 0<p['bytes']<=8388608 and 1<=p['count']<=1400)
files=sorted((area/'chunks').glob(p['kind']+'-*')); need([x.name for x in files]==['%s-%06d'%(p['kind'],n) for n in range(p['count'])])
raw=b''.join(regular(x,6144) for x in files); need(len(raw)==p['bytes'] and digest(raw)==p['sha256'])
create(area/names[p['kind']],raw)
if p['kind']=='archive':
 need(len(raw)<=524288); plain=gzip.decompress(raw); need(len(plain)<=8388608)
 with tarfile.open(fileobj=io.BytesIO(plain)) as archive:
  members=archive.getmembers(); need(len(members)==len(p['files']) and {m.name for m in members}==set(p['files']))
  tools=area/'tools'; tools.mkdir(mode=0o700)
  for m in members:
   need(m.isfile() and re.fullmatch(r'infra/aws/scripts/[A-Za-z0-9_-]+\.py',m.name) and 0<m.size<=524288)
   data=archive.extractfile(m).read(); need(len(data)==p['files'][m.name]['bytes'] and digest(data)==p['files'][m.name]['sha256'])
   target=tools/m.name; safe_parent(target.parent,make=True); create(target,data)
emit({'passed':True,'sha256':digest(raw),'bytes':len(raw)})
'''

ACK_WRITE = r'''
safe_parent(area); raw=base64.b64decode(p['data'],validate=True); need(len(raw)<=4096 and digest(raw)==p['sha256'])
context=regular(area/'context.json'); selected=json.loads(context); ack=json.loads(raw)
need(set(ack)=={'schemaVersion','kind','contextSha256','operationId','runId','resourceFence','lease','esInstanceId','sequence','issuedAt','expiresAt','previousSha256'})
need(ack['schemaVersion']==1 and ack['kind']=='global-b-aws-native-search-lease-ack' and ack['contextSha256']==digest(context)
 and ack['runId']==selected['runId']==p['runId'] and ack['operationId']==selected['operationId']==p['operationId']
 and ack['esInstanceId']==selected['hosts']['elasticsearch']['instanceId']==p['instanceId'] and ack['resourceFence']==selected['resourceFence']
 and ack['lease']=={k:selected['lease'][k] for k in ('owner','fencingToken')})
need(type(ack['sequence']) is int and 0<=ack['sequence']<500 and ack['issuedAt']<=time.time()<ack['expiresAt']<=p['deadlineEpoch'] and ack['expiresAt']-ack['issuedAt']<=90)
previous='0'*64 if ack['sequence']==0 else digest(regular(area/'acks'/('%06d.json'%(ack['sequence']-1)),4096))
need(previous==ack['previousSha256']); create(area/'acks'/('%06d.json'%ack['sequence']),raw)
emit({'passed':True,'sha256':digest(raw),'sequence':ack['sequence']})
'''

PUBLIC_READ = r'''
safe_parent(area)
allowed={'source':'prepare-source/public/source-package.json','native':'restore-on-es/public/search-restore-receipt.json','host':'restore-on-es/public/host-receipt.json'}
need(p['name'] in allowed and type(p['offset']) is int and 0<=p['offset']<=8388608)
raw=regular(area/allowed[p['name']]); data=raw[p['offset']:p['offset']+6144]
emit({'bytes':len(raw),'sha256':digest(raw),'offset':p['offset'],'data':base64.b64encode(data).decode()})
'''


def remote_program(body, payload):
    host.public_json(payload)
    code = REMOTE_COMMON.replace('__PAYLOAD__', base64.b64encode(encoded(payload)).decode())
    code += '\ntry:\n' + '\n'.join(' ' + line for line in body.strip().splitlines())
    code += "\nexcept BaseException:\n emit({'passed':False,'failureCode':'NATIVE_SEARCH_REMOTE_REJECTED'}); raise SystemExit(1)\n"
    compile(code, '<native-search-remote>', 'exec')
    return "exec /usr/bin/python3 -I -S -B - <<'AIRBOB_NATIVE_SEARCH'\n" + code + 'AIRBOB_NATIVE_SEARCH\n'


def ack_value(context, context_sha, sequence, previous_sha, issued):
    need(type(sequence) is int and 0 <= sequence < 500 and host.digest(previous_sha), 'ACK_SEQUENCE_LIMIT')
    until = min(issued + ACK_TTL, context['controllerDeadlineEpoch'], context['expiresAt'], context['approvedExecutionDeadlineEpoch'])
    need(until > issued + 15, 'ACK_DEADLINE_REACHED')
    return {'schemaVersion': 1, 'kind': host.ACK_KIND, 'contextSha256': context_sha,
        'operationId': context['operationId'], 'runId': context['runId'], 'resourceFence': context['resourceFence'],
        'lease': {k: context['lease'][k] for k in ('owner', 'fencingToken')},
        'esInstanceId': context['hosts']['elasticsearch']['instanceId'], 'sequence': sequence,
        'issuedAt': issued, 'expiresAt': until, 'previousSha256': previous_sha}


def validate_host_phase_result(context, phase, result):
    expected = {'prepare-source': 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED',
                'restore-on-es': 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED'}
    need(phase in expected, 'HOST_PHASE')
    host.keys(result, 'state output sha256 ownedWorkerTerminal privateMaterialRemoved', 'HOST_PHASE_RESULT_FIELDS')
    need(result['state'] == expected[phase] and result['output'] == str(host.op_root(context) / phase)
         and host.digest(result['sha256']) and result['ownedWorkerTerminal'] is True
         and result['privateMaterialRemoved'] is True, 'HOST_PHASE_CLEANUP_UNCONFIRMED')
    return result


BOOTSTRAP_ES = r'''
safe_parent(area); need(digest(regular(area/'context.json'))==p['contextSha256'])
need(not (area/'bootstrap-intent.json').exists()); create(area/'bootstrap-intent.json',b'{"started":true}\n')
context=json.loads(regular(area/'context.json')); preparation=context['preparationManifest']['value']
inputs=area/'inputs'; inputs.mkdir(mode=0o700)
awszip=area/'awscli.zip'
call(['curl','--fail','--silent','--show-error','--location','--max-time','300','--output',str(awszip),
 'https://awscli.amazonaws.com/awscli-exe-linux-x86_64-2.34.64.zip'],310)
def file_sha(path):
 h=hashlib.sha256()
 with open(path,'rb') as f:
  for b in iter(lambda:f.read(1048576),b''): h.update(b)
 return h.hexdigest()
need(file_sha(awszip)=='ae97157f36526c36673fbc71756a921d9ff238542cba9c88f6568cd80e88d1d4')
call(['unzip','-q',str(awszip),'-d',str(area/'aws-installer')],120)
call([str(area/'aws-installer/aws/install'),'--install-dir',str(area/'aws-cli'),'--bin-dir',str(area/'aws-bin')],120)
aws=str(area/'aws-bin/aws'); need(call([aws,'--version']).decode().startswith('aws-cli/2.34.64 '))
caller=json.loads(call([aws,'--region','ap-northeast-2','sts','get-caller-identity','--output','json']))
need(caller.get('Account')=='942632789808' and re.fullmatch('arn:aws:sts::942632789808:assumed-role/airbob-lab-host-'+p['runId']+'-elasticsearch/[^/]+',caller.get('Arn','')))
def download(ref,path):
 need(not path.exists() and time.time()<p['deadlineEpoch'])
 result=json.loads(call([aws,'--region','ap-northeast-2','--no-cli-pager','--cli-connect-timeout','5','--cli-read-timeout','60',
 's3api','get-object','--bucket','airbob-performance-lab-dataset-942632789808','--key',ref['key'],'--version-id',ref['versionId'],str(path)],900))
 path.chmod(0o600); need(result.get('VersionId')==ref['versionId'] and result.get('ContentLength')==ref['bytes']
 and path.stat().st_size==ref['bytes'] and file_sha(path)==ref['sha256'])
download(preparation['files']['toolchain'],area/'toolchain.tar.gz')
download(preparation['files']['toolchainManifest'],inputs/'toolchain.json')
inventory=json.loads(regular(inputs/'toolchain.json'))
need(set(inventory)=={'schemaVersion','files'} and inventory['schemaVersion']==1 and 1<=len(inventory['files'])<=50000)
tools=area/'toolchain'; tools.mkdir(mode=0o700)
with tarfile.open(area/'toolchain.tar.gz') as archive:
 members=archive.getmembers(); need(len(members)==len({m.name for m in members}) and len(members)<=50000
 and sum(m.size for m in members)<=preparation['toolchain']['unpackedBytes'])
 for m in members:
  need(re.fullmatch(r'[A-Za-z0-9_./+-]+',m.name) and not m.name.startswith('/') and '..' not in pathlib.PurePosixPath(m.name).parts and (m.isfile() or m.isdir()))
 for m in members:
  target=tools/m.name
  if m.isdir(): target.mkdir(mode=0o700,parents=True,exist_ok=True)
  else:
   target.parent.mkdir(mode=0o700,parents=True,exist_ok=True)
   with archive.extractfile(m) as source,os.fdopen(os.open(target,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o700 if m.mode&0o111 else 0o600),'wb') as destination:
    for b in iter(lambda:source.read(1048576),b''): destination.write(b)
need({str(f.relative_to(tools)) for f in tools.rglob('*') if f.is_file()}==set(inventory['files']))
for name,ref in inventory['files'].items():
 need(set(ref)=={'sha256','bytes'} and (tools/name).stat().st_size==ref['bytes'] and file_sha(tools/name)==ref['sha256'])
need(os.access(tools/'python/bin/python3',os.X_OK))
emit({'passed':True,'state':'PINNED_HOST_TOOLCHAIN_STAGED'})
'''

LAUNCH = r'''
safe_parent(area); raw=regular(area/'context.json'); need(digest(raw)==p['contextSha256'])
context=json.loads(raw); need(context['runId']==p['runId'] and context['operationId']==p['operationId'])
need(p['phase'] in ('stage-inputs','prepare-source','restore-on-es'))
lock=os.open(area/'launch.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
intent=area/(p['phase']+'-launch-intent.json'); create(intent,json.dumps(p,sort_keys=True,separators=(',',':')).encode()+b'\n')
if p['phase']!='stage-inputs': create(area/'worker-intent.json',json.dumps({'phase':p['phase'],'contextSha256':p['contextSha256']}).encode()+b'\n')
root=pathlib.Path('/opt/airbob/global-b')/p['runId'] if p['phase']=='prepare-source' else area
python=root/'toolchain/python/bin/python3'; need(python.is_file() and not python.is_symlink())
tools=area/'tools/infra/aws/scripts'
for name,ref in p['sources'].items(): need(digest(regular(area/'tools'/name,524288))==ref['sha256'])
env={k:v for k,v in os.environ.items() if not k.startswith('AWS_') and k not in ('PYTHONPATH','PYTHONHOME','PYTHONSTARTUP','PYTHONINSPECT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','JDK_JAVAC_OPTIONS','CLASSPATH','AIRBOB_ETL_BENCHMARK_PASSWORD','DOCKER_HOST','DOCKER_CONTEXT','DOCKER_CONFIG')}
env.update(PATH=':'.join(str(root/v) for v in ('aws-bin','toolchain/python/bin','toolchain/jdk/bin','toolchain/mysql/bin'))+':/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
 JAVA_HOME=str(root/'toolchain/jdk'),AWS_REGION='ap-northeast-2',AWS_MAX_ATTEMPTS='1',AWS_CLI_AUTO_PROMPT='off',PYTHONDONTWRITEBYTECODE='1',
 AWS_CONFIG_FILE='/dev/null',AWS_SHARED_CREDENTIALS_FILE='/dev/null',DOCKER_HOST='unix:///var/run/docker.sock',DOCKER_CONFIG=str(area/'docker-client'))
if p['phase']=='stage-inputs':
 argv=[str(python),'-B',str(tools/'growth_b_search_controller.py'),'stage-inputs','--context',str(area/'context.json')]
else:
 argv=[str(python),'-B',str(tools/'growth_b_search_host.py'),p['phase'],'--context',str(area/'context.json'),'--output',str(area/p['phase'])]
 if p['phase']=='restore-on-es':
  need(digest(regular(area/'source-package.json'))==p['sourcePackageSha256'])
  argv+=['--source-package',str(area/'source-package.json'),'--source-package-sha256',p['sourcePackageSha256']]
need(time.time()+30<p['workerDeadlineEpoch']<p['deadlineEpoch']-150)
def birth(pid):
 try:
  text=pathlib.Path('/proc/%d/stat'%pid).read_text().rsplit(')',1)[1].split()
  return {'pid':pid,'startTicks':text[19],'processGroup':int(text[2]),'state':text[0]}
 except FileNotFoundError: return None
def interrupt(*args): raise KeyboardInterrupt()
for selected_signal in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP): signal.signal(selected_signal,interrupt)
with tempfile.TemporaryFile() as output:
 child=subprocess.Popen(argv,stdout=output,stderr=subprocess.DEVNULL,env=env,start_new_session=True)
 identity=birth(child.pid)
 try:
  need(identity is not None and identity['processGroup']==child.pid)
  create(area/(p['phase']+'-launch-worker.json'),json.dumps(identity,sort_keys=True,separators=(',',':')).encode()+b'\n')
  while child.poll() is None:
   need(time.time()<p['workerDeadlineEpoch'] and os.fstat(output.fileno()).st_size<=12000)
   time.sleep(.5)
  need(child.returncode==0)
  output.seek(0); raw=output.read(12001); need(0<len(raw)<=12000); result=json.loads(raw)
 finally:
  if child.poll() is None:
   current=birth(child.pid)
   need(identity is not None and current is not None and current['startTicks']==identity['startTicks'] and current['processGroup']==child.pid)
   os.killpg(child.pid,signal.SIGTERM)
   try: child.wait(timeout=120)
   except subprocess.TimeoutExpired:
    current=birth(child.pid); need(current is not None and current['startTicks']==identity['startTicks'] and current['processGroup']==child.pid)
    os.killpg(child.pid,signal.SIGKILL); child.wait(timeout=10)
    raise RuntimeError('OWN_SUPERVISOR_FORCED_STOP_REQUIRES_REVIEW')
emit({'passed':True,'result':result})
'''


def stage_inputs(context_path):
    """Public exact-version input staging on ES; no SQL or ES requests."""
    import shutil
    import zipfile
    context = host.validate_context(read(context_path))
    root = host.op_root(context); inputs = root / 'inputs'; manifest = context['preparationManifest']['value']
    need(Path(context_path) == root / 'context.json' and inputs.is_dir(), 'HOST_STAGING_CONTEXT')
    need(Path('/sys/devices/virtual/dmi/id/board_asset_tag').read_text().strip() == context['hosts']['elasticsearch']['instanceId'], 'HOST_STAGING_INSTANCE')
    aws = str(root / 'aws-bin/aws')
    environment = prepare.qualify_toolchain(root / 'toolchain', read(inputs / 'toolchain.json'), manifest['toolchain'])
    need(prepare.run([aws, '--version']).decode().startswith('aws-cli/2.34.64 '), 'HOST_STAGING_AWS_VERSION')
    need(shutil.disk_usage(root).free >= manifest['storage']['minimumStagingFreeBytes'], 'HOST_STAGING_FREE_SPACE')

    def download(ref, destination):
        need(time.time() + 30 < context['controllerDeadlineEpoch'], 'HOST_STAGING_DEADLINE')
        prepare.fetch(ref, destination, aws)

    download(context['preparationManifest']['reference'], inputs / 'aws-preparation.json')
    for name, ref in manifest['files'].items():
        if name not in {'toolchain', 'toolchainManifest', 'consumerTools'}:
            download(ref, inputs / prepare.FILES.get(name, 'small-rds-receipt.json'))
    envelope = read(inputs / 'envelope.json')
    prepare.validate_envelope_metadata(manifest, envelope)
    release = inputs / 'release'; release.mkdir(mode=0o700)
    for name, ref in envelope['objects'].items():
        need('/' not in name and name not in ('', '.', '..'), 'RELEASE_FILENAME')
        download(ref, release / name)
    migrations = inputs / 'migrations'; migrations.mkdir(mode=0o700)
    with zipfile.ZipFile(inputs / 'app.jar') as archive:
        for item in archive.infolist():
            prefix = 'BOOT-INF/classes/db/migration/'
            if item.filename.startswith(prefix) and re.fullmatch(r'V[0-9]+__[A-Za-z0-9_.-]+\.sql', item.filename[len(prefix):]):
                need(item.file_size <= 4 * 1024**2, 'MIGRATION_SIZE')
                host.new_file(migrations / item.filename[len(prefix):], archive.read(item))
    search_dir = inputs / 'search'; search_dir.mkdir(mode=0o700)
    ref = context['serviceManifest']['value']['search']['transport']
    download(ref, search_dir / 'transport-manifest.json')
    transport = read(search_dir / 'transport-manifest.json')
    prefix = ref['key'].removesuffix('transport-manifest.json')
    need(transport.get('datasetId') == DATASET and isinstance(transport.get('objects'), dict), 'SEARCH_TRANSPORT_SCOPE')
    selected = {name: item for name, item in transport['objects'].items()
                if name == 'companion-descriptor.json' or name.startswith('companion/')}
    need(1 < len(selected) <= 64 and 'companion-descriptor.json' in selected, 'SEARCH_COMPANION_INVENTORY')
    for name, item in selected.items():
        host.reference(item, prefix + name)
        need(item['key'] == prefix + name and re.fullmatch(r'(?:companion/)?[A-Za-z0-9_.-]+', name), 'SEARCH_COMPANION_KEY')
        destination = search_dir / name; destination.parent.mkdir(mode=0o700, exist_ok=True)
        download(item, destination)
    # The unchanged host helper performs source/content qualification itself.
    return {'state': 'EXACT_NATIVE_SEARCH_INPUTS_STAGED', 'releaseFiles': len(envelope['objects']),
            'companionFiles': len(selected), 'sqlImportExecuted': False, 'elasticsearchRestoreExecuted': False}


class Controller:
    def __init__(self, operation, context, directory, *, aws=None, clock=time.time, sleep=time.sleep):
        validate_operation(operation); validate_context(context, operation, now=int(clock()))
        self.operation, self.context = operation, context
        self.directory = Path(directory).absolute()
        need(not self.directory.exists() and self.directory.parent.resolve() == self.directory.parent, 'NEW_CONTROLLER_DIRECTORY_REQUIRED')
        self.directory.mkdir(mode=0o700)
        (self.directory / 'public').mkdir(mode=0o700)
        self.journal = Journal(self.directory / 'journal')
        self.deadline, self.clock = context['controllerDeadlineEpoch'], clock
        self.aws = aws or Aws(self.deadline, clock=clock)
        self.lease = restore.Lease(self.aws, context['lease'])
        self.ssm = Ssm(self.aws, self.journal, self.guard, self.deadline, clock=clock, sleep=sleep)
        self.archive, self.archive_meta = source_archive()
        self.acks = []; self.last_ack_at = 0
        self.host_context = None; self.connect_pin = None
        self.journal.add('CONTROLLER_STARTED', {'operation': operation, 'resourceFence': context['operator']['fencingToken'],
            'controllerLease': context['lease'], 'deadlineEpoch': self.deadline, 'sourceArchive': self.archive_meta})

    def guard(self):
        need(self.clock() + 5 < self.deadline, 'CONTROLLER_DEADLINE_REACHED')
        self.lease()

    def fetch(self, reference, name, bucket):
        self.guard(); path = self.directory / name
        need(not path.exists() and path.parent == self.directory, 'NEW_INPUT_DESTINATION_REQUIRED')
        response = self.aws.call('s3api', 'get-object', '--bucket', bucket, '--key', reference['key'],
            '--version-id', reference['versionId'], str(path))
        path.chmod(0o600)
        raw = host.regular(path)
        need(response.get('VersionId') == reference['versionId'] and response.get('ContentLength') == len(raw)
             and checksum(raw) == reference['sha256'] and reference.get('bytes', len(raw)) == len(raw), 'PINNED_INPUT_BYTES_CHANGED')
        value = decode(raw); host.public_json(value)
        return {'reference': reference | {'bytes': len(raw)}, 'value': value}

    def selected_inputs(self):
        op, original = self.operation, self.context['operator']
        caller = self.aws.call('sts', 'get-caller-identity')
        need(caller.get('Account') == ACCOUNT and re.fullmatch(
            f'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-lab-operator/[^/]+', caller.get('Arn', '')), 'REVIEWED_OIDC_OPERATOR_REQUIRED')
        self.manifest = self.fetch(op['manifest'], 'selected-service.json', service.BUCKET)
        value = self.manifest['value']
        service.validate_manifest(value, DATASET, op['runId'], op['serviceRelease'],
            {name: host.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS})
        need(value['search']['restoreReceipt'] is None and value['application']['image'] == original['appImageReference']
             and value['application']['mainCommit'] == original['bundleCommit'], 'ORIGINAL_APP_AND_UNRESTORED_SEARCH_REQUIRED')
        prep_sha = original['datasetManifestSha256']
        need(host.digest(prep_sha), 'ORIGINAL_PREPARATION_SHA')
        self.preparation = self.fetch({'key': f'datasets/{DATASET}-aws-preparation/aws-preparation-{prep_sha}.json',
            'versionId': original['datasetManifestVersionId'], 'sha256': prep_sha}, 'selected-preparation.json', service.BUCKET)
        prepare.validate_manifest(self.preparation['value'], DATASET, {n: host.sources()[n] for n in prepare.TOOLS})
        self.wrapper = self.fetch(value['preparation']['receipt'], 'selected-preparation-receipt.json', EVIDENCE)
        self.standalone = self.fetch(self.wrapper['value']['standaloneReceiptObject'], 'selected-standalone-receipt.json', EVIDENCE)
        self.envelope = self.fetch(self.preparation['value']['files']['envelope'], 'selected-envelope.json', service.BUCKET)
        prepare.validate_preparation_receipt(self.preparation['value'], {'runId': op['runId'], 'manifestSha256': prep_sha,
            'rdsResourceId': value['rds']['resourceId']}, self.wrapper['value'], self.directory / 'selected-standalone-receipt.json',
            self.directory / 'selected-envelope.json')
        need(self.wrapper['value']['serverUuid'] == value['rds']['serverUuid']
             and self.standalone['reference']['sha256'] == value['preparation']['restoreReceiptSha256'], 'PREPARATION_UUID_BINDING')

    def tags(self, rows, role=None):
        tags = {r['Key']: r['Value'] for r in rows}
        need(len(tags) == len(rows), 'DUPLICATE_RESOURCE_TAG')
        original = self.context['operator']
        expected = {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'lab', 'ManagedBy': 'terraform',
            'Persistence': 'ephemeral', 'RunId': self.operation['runId'], 'FencingToken': str(original['fencingToken']),
            'ExpiresAt': str(original['expiresAt'])}
        need(all(tags.get(k) == v for k, v in expected.items()) and (role is None or tags.get('Service') == role), 'RESOURCE_TAGS_CHANGED')
        return tags

    def topology(self):
        self.guard(); self.lease(force=True)
        context, op = self.context, self.operation
        rows = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', 'airbob-' + op['runId'])['DBInstances']
        need(len(rows) == 1, 'EXACT_RDS_REQUIRED'); rds = rows[0]
        self.tags(rds['TagList'])
        need(rds['DBInstanceIdentifier'] == self.manifest['value']['rds']['identifier']
             and rds['DbiResourceId'] == self.manifest['value']['rds']['resourceId'] == context['phase3']['rds_resource_id']
             and rds['DBInstanceStatus'] == 'available' and rds['Engine'] == 'mysql' and rds['EngineVersion'] == '8.4.11'
             and rds['DBInstanceClass'] == 'db.t3.small' and rds['AllocatedStorage'] == 100 and rds['StorageType'] == 'gp3'
             and rds['PubliclyAccessible'] is False and rds['MultiAZ'] is False and rds['StorageEncrypted'] is True
             and rds['Endpoint']['Address'] == context['phase3']['rds_endpoint'] and rds['Endpoint']['Port'] == 3306,
             'ORIGINAL_SINGLE_RDS_CHANGED')
        groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
        groups = [g for g in groups if g['AutoScalingGroupName'].startswith('airbob-lab-')]
        need(len(groups) == 1 and groups[0]['AutoScalingGroupName'] == 'airbob-' + op['runId'] + '-app'
             == context['phase4']['auto_scaling_group_name'], 'EXACT_ZERO_APP_ASG_REQUIRED')
        group = groups[0]; self.tags(group['Tags'], 'app')
        need(group['MinSize'] == group['MaxSize'] == group['DesiredCapacity'] == 0 and group['Instances'] == []
             and not group.get('MixedInstancesPolicy'), 'WRITER_ASG_NOT_ZERO')
        activities = self.aws.call('autoscaling', 'describe-scaling-activities', '--auto-scaling-group-name', group['AutoScalingGroupName'])['Activities']
        need(all(a['StatusCode'] in {'Successful', 'Failed', 'Cancelled'} for a in activities), 'SCALING_ACTIVITY_IN_PROGRESS')
        reservations = self.aws.call('ec2', 'describe-instances', '--filters', 'Name=tag:Project,Values=airbob',
            'Name=tag:Environment,Values=performance-lab', 'Name=instance-state-name,Values=pending,running,stopping,stopped')['Reservations']
        instances = [i for r in reservations for i in r['Instances']]
        need(1 <= len(instances) <= 8, 'PAID_HOST_COUNT_CHANGED')
        selected = {}
        for instance in instances:
            tags = self.tags(instance['Tags'])
            need(tags.get('Service') not in {'app', 'load-generator', 'loadgen'}
                 and instance['State']['Name'] == 'running' and instance['ImageId'] == context['operator']['amiId']
                 and instance['VpcId'] == context['phase2']['vpc_id'], 'LIVE_HOST_OR_WRITER_CHANGED')
            selected[instance['InstanceId']] = instance
        for role in ('debezium', 'elasticsearch', 'kafka'):
            identifier = context['phase2']['services'][role]
            need(identifier in selected, 'SERVICE_HOST_MISSING'); self.tags(selected[identifier]['Tags'], role)
        projection = {'rds': {k: rds[k] for k in ('DBInstanceIdentifier', 'DbiResourceId', 'InstanceCreateTime')},
            'rdsEndpoint': rds['Endpoint']['Address'], 'masterSecretArn': rds['MasterUserSecret']['SecretArn'],
            'hostIds': sorted(selected), 'zeroAppAsg': group['AutoScalingGroupName']}
        if hasattr(self, 'topology_pin'):
            need(projection == self.topology_pin, 'ACTIVE_TOPOLOGY_IDENTITY_CHANGED')
        else:
            self.topology_pin = projection
        return rds, selected

    def payload(self, instance):
        return {'instanceId': instance, 'runId': self.operation['runId'], 'operationId': self.operation['operationId'], 'deadlineEpoch': self.deadline}

    def discovery(self, role, *, concurrent=False):
        key = 'debezium' if role == 'preparation' else 'elasticsearch'
        identifier = self.context['phase2']['services'][key]
        image = self.manifest['value']['debezium' if role == 'preparation' else 'search']['image']
        result = self.ssm.run('discover-' + role, identifier, remote_program(DISCOVER, self.payload(identifier) | {'service': key, 'image': image}),
            seconds=40, acknowledgement=concurrent)
        need(result.get('passed') is True, 'HOST_DISCOVERY_FAILED')
        pin = result['pin']
        expected_fields = 'instanceId container containerId imageId image startedAt' + (' clusterUuid' if role != 'preparation' else '')
        host.keys(pin, expected_fields, 'DISCOVERY_FIELDS')
        need(pin['instanceId'] == identifier and pin['image'] == image and host.digest(pin['containerId'])
             and re.fullmatch(r'sha256:[0-9a-f]{64}', pin['imageId']), 'DISCOVERY_IDENTITY')
        self.journal.add('HOST_OBSERVED', {'role': role, 'pin': pin})
        return pin

    def build_host_context(self):
        self.selected_inputs(); rds, _ = self.topology()
        self.connect_pin = self.discovery('preparation')
        es = self.discovery('elasticsearch'); self.topology()
        original = self.context['operator']
        value = {'schemaVersion': 1, 'kind': host.KIND, 'operationId': self.operation['operationId'], 'runId': self.operation['runId'],
            'datasetId': DATASET, 'account': ACCOUNT, 'region': REGION, 'resourceFence': original['fencingToken'],
            'expiresAt': int(original['expiresAt']), 'approvedExecutionDeadlineEpoch': self.context['approvedExecutionDeadlineEpoch'],
            'controllerDeadlineEpoch': self.deadline - HOST_COMPLETION_MARGIN, 'lease': self.context['lease'],
            'hosts': {'preparation': {'instanceId': self.connect_pin['instanceId']}, 'elasticsearch': es},
            'rds': self.manifest['value']['rds'] | {'endpoint': rds['Endpoint']['Address'], 'masterSecretArn': rds['MasterUserSecret']['SecretArn'],
                'createdAt': rds['InstanceCreateTime']}, 'serviceManifest': self.manifest, 'preparationManifest': self.preparation,
            'sourceRefs': {'preparationReceipt': self.wrapper, 'standaloneReceipt': self.standalone},
            'targetIndex': 'accommodations-v' + self.operation['operationId'], 'repositoryName': 'b_' + self.operation['operationId'].replace('-', '_'),
            'toolSources': host.sources()}
        host.validate_context(value)
        self.host_context = value; self.host_raw = encoded(value); self.host_sha = checksum(self.host_raw)
        host.new_file(self.directory / 'host-context.json', self.host_raw)
        return value

    def stage(self, instance, kind, raw):
        need(0 < len(raw) <= host.MAX_PUBLIC_BYTES, 'PUBLIC_STAGE_SIZE')
        for seq, offset in enumerate(range(0, len(raw), CHUNK)):
            chunk = raw[offset:offset + CHUNK]
            payload = self.payload(instance) | {'kind': kind, 'sequence': seq, 'data': base64.b64encode(chunk).decode(), 'sha256': checksum(chunk)}
            result = self.ssm.run('stage-' + kind, instance, remote_program(CHUNK_WRITE, payload))
            need(result == {'passed': True, 'sha256': checksum(chunk)}, 'STAGE_CHUNK_UNCONFIRMED')
        payload = self.payload(instance) | {'kind': kind, 'bytes': len(raw), 'sha256': checksum(raw), 'count': (len(raw) + CHUNK - 1) // CHUNK}
        if kind == 'archive': payload['files'] = self.archive_meta['files']
        result = self.ssm.run('assemble-' + kind, instance, remote_program(ASSEMBLE, payload))
        need(result == {'passed': True, 'sha256': checksum(raw), 'bytes': len(raw)}, 'STAGE_ASSEMBLY_UNCONFIRMED')

    def read_public(self, instance, name):
        result = bytearray(); reference = None
        while reference is None or len(result) < reference['bytes']:
            item = self.ssm.run('read-' + name, instance, remote_program(PUBLIC_READ, self.payload(instance) | {'name': name, 'offset': len(result)}))
            host.keys(item, 'bytes sha256 offset data', 'PUBLIC_CHUNK_FIELDS')
            current = {k: item[k] for k in ('bytes', 'sha256')}
            need(host.integer(current['bytes'], 1, host.MAX_PUBLIC_BYTES) and host.digest(current['sha256'])
                 and (reference is None or reference == current) and item['offset'] == len(result), 'PUBLIC_CHUNK_BINDING')
            reference = current
            chunk = base64.b64decode(item['data'], validate=True)
            need(len(chunk) == min(CHUNK, reference['bytes'] - len(result)), 'PUBLIC_CHUNK_LENGTH')
            result.extend(chunk)
        need(checksum(result) == reference['sha256'], 'PUBLIC_FULL_BYTES_CHANGED')
        raw = bytes(result); host.public_json(decode(raw))
        return raw

    def acknowledge(self, *, force=False):
        if not force and self.clock() - self.last_ack_at < ACK_INTERVAL:
            return
        self.topology()
        need(self.discovery('preparation', concurrent=bool(self.ssm.pending)) == self.connect_pin, 'CONNECT_WRITER_IDENTITY_CHANGED')
        issued = int(self.clock())
        previous = checksum(self.acks[-1]) if self.acks else '0' * 64
        value = ack_value(self.host_context, self.host_sha, len(self.acks), previous, issued)
        raw = encoded(value); instance = value['esInstanceId']
        self.journal.add('ACK_INTENT', {'ack': value, 'sha256': checksum(raw)})
        payload = self.payload(instance) | {'data': base64.b64encode(raw).decode(), 'sha256': checksum(raw)}
        result = self.ssm.run('lease-ack', instance, remote_program(ACK_WRITE, payload), seconds=30, acknowledgement=bool(self.ssm.pending))
        need(result == {'passed': True, 'sha256': checksum(raw), 'sequence': value['sequence']}, 'ACK_DELIVERY_UNCONFIRMED')
        need(self.clock() + 5 < value['expiresAt'], 'ACK_EXPIRED_DURING_DELIVERY')
        self.acks.append(raw); self.last_ack_at = issued
        self.journal.add('ACK_DELIVERED', {'sequence': value['sequence'], 'sha256': checksum(raw), 'expiresAt': value['expiresAt']})

    def launch(self, phase, instance, package_sha=None):
        self.topology()
        payload = self.payload(instance) | {'contextSha256': self.host_sha, 'phase': phase, 'sources': self.archive_meta['files']}
        if package_sha is not None:
            payload['sourcePackageSha256'] = package_sha
        # Leave room for receipt transport and the exact conditional publication.
        seconds = min(18000 if phase == 'restore-on-es' else 3600, int(self.deadline - self.clock()) - 30)
        payload['workerDeadlineEpoch'] = min(self.host_context['controllerDeadlineEpoch'], int(self.clock()) + seconds - 180)
        need(payload['workerDeadlineEpoch'] > self.clock() + 120, 'INSUFFICIENT_WORKER_WINDOW')
        if phase == 'restore-on-es':
            self.acknowledge(force=True)
        item = self.ssm.submit(phase, instance, remote_program(LAUNCH, payload), seconds=seconds)
        result = self.ssm.wait(item, tick=self.acknowledge if phase == 'restore-on-es' else None)
        need(result.get('passed') is True, 'NATIVE_HOST_PHASE_UNCONFIRMED')
        if phase in ('prepare-source', 'restore-on-es'):
            validate_host_phase_result(self.host_context, phase, result['result'])
            self.journal.add('HOST_PHASE_SETTLED', {'phase': phase, 'result': result['result']})
        return result['result']

    def publish(self, name, raw):
        self.topology(); need(not self.ssm.pending and not self.ssm.unknown, 'REMOTE_COMMAND_NOT_SETTLED')
        path = self.directory / 'public' / name
        if path.exists():
            need(host.regular(path) == raw, 'PUBLIC_LOCAL_BYTES_CHANGED')
        else:
            host.new_file(path, raw)
        key = f'data-bootstrap/{self.operation["runId"]}/{DATASET}-native-search/{self.operation["operationId"]}/{name}'
        self.journal.add('PUBLICATION_INTENT', {'key': key, 'sha256': checksum(raw), 'bytes': len(raw)})
        response = self.aws.call('s3api', 'put-object', '--bucket', EVIDENCE, '--key', key, '--body', str(path),
            '--server-side-encryption', 'AES256', '--tagging', 'Retention=summary', '--if-none-match', '*')
        version = response.get('VersionId')
        need(isinstance(version, str) and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', version) and version not in {'null', 'None'}, 'PUBLICATION_VERSION_UNCONFIRMED')
        ref = {'key': key, 'versionId': version, 'sha256': checksum(raw), 'bytes': len(raw)}
        self.journal.add('PUBLICATION_SUBMITTED', {'reference': ref})
        readback = self.fetch(ref, 'readback-' + name, EVIDENCE)
        need(readback['value'] == decode(raw), 'PUBLICATION_READBACK_CHANGED')
        self.journal.add('PUBLICATION_VERIFIED', {'reference': ref})
        return ref

    def run(self):
        failure = None
        try:
            context = self.build_host_context()
            for role in ('preparation', 'elasticsearch'):
                instance = context['hosts'][role]['instanceId']
                self.topology()
                result = self.ssm.run('initialize-' + role, instance, remote_program(INIT, self.payload(instance)))
                need(result == {'passed': True}, 'OPERATION_NAMESPACE_NOT_NEW')
                self.stage(instance, 'archive', self.archive)
                self.stage(instance, 'context', self.host_raw)
            source_instance = context['hosts']['preparation']['instanceId']
            source_completion = self.launch('prepare-source', source_instance)
            package = self.read_public(source_instance, 'source'); package_sha = checksum(package)
            need(source_completion['sha256'] == package_sha, 'SOURCE_PACKAGE_CHANGED_AFTER_CLEANUP')
            need(decode(package).get('state') == 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED', 'ACTUAL_IMPORT_SOURCE_PACKAGE_REQUIRED')
            host.validate_source_package(decode(package), context, self.host_sha, self.envelope['value'],
                self.standalone['value']['sealedFingerprint'])
            host.new_file(self.directory / 'public/source-package.json', package)
            es = context['hosts']['elasticsearch']['instanceId']
            self.stage(es, 'package', package)
            self.topology()
            bootstrap = self.ssm.run('bootstrap-es-toolchain', es, remote_program(BOOTSTRAP_ES,
                self.payload(es) | {'contextSha256': self.host_sha}), seconds=min(1800, int(self.deadline - self.clock()) - 120))
            need(bootstrap == {'passed': True, 'state': 'PINNED_HOST_TOOLCHAIN_STAGED'}, 'ES_TOOLCHAIN_BOOTSTRAP_UNCONFIRMED')
            self.launch('stage-inputs', es)
            es_completion = self.launch('restore-on-es', es, package_sha)
            native = self.read_public(es, 'native')
            receipt_raw = self.read_public(es, 'host'); receipt = decode(receipt_raw)
            need(es_completion['sha256'] == checksum(receipt_raw), 'HOST_RECEIPT_CHANGED_AFTER_CLEANUP')
            host.validate_completion(context, self.host_sha, decode(package), package_sha, native, receipt)
            final = receipt['finalAck']
            need(final['sequence'] < len(self.acks) and checksum(self.acks[final['sequence']]) == final['sha256'], 'COMPLETION_ACK_NOT_DELIVERED')
            self.topology()
            refs = {'sourcePackage': self.publish('source-package.json', package),
                    'hostReceipt': self.publish('host-receipt.json', receipt_raw),
                    'restoreReceipt': self.publish('search-restore-receipt.json', native)}
            result = {'schemaVersion': 1, 'kind': 'global-b-aws-native-search-completion',
                'state': 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED', 'operation': self.operation,
                'contextSha256': self.host_sha, 'resourceFence': context['resourceFence'], 'lease': context['lease'],
                'rds': context['rds'], 'hosts': context['hosts'], 'targetIndex': context['targetIndex'],
                'repositoryName': context['repositoryName'], 'references': refs, 'finalAck': final,
                'settledHostPhases': {'source': source_completion, 'elasticsearch': es_completion},
                'completedAtEpoch': int(self.clock()), 'sourceArchive': self.archive_meta,
                'sqlImportExecuted': False, 'cloudInfrastructureModified': False, 'applicationStarted': False,
                'freshServiceManifestRequired': True, 'journalSha256': self.journal.last}
            host.write(self.directory / 'public/completion.json', result)
            return result
        except BaseException as error:
            failure = str(error) if isinstance(error, host.Rejected) else 'NATIVE_SEARCH_CONTROLLER_FAILED'
            raise
        finally:
            if failure is not None:
                # Do not infer that an unconfirmed SSM worker has stopped. Its
                # short ACK expires independently; inspect its terminal receipt.
                host.write(self.directory / 'public/recovery.json', {'schemaVersion': 1,
                    'kind': 'global-b-aws-native-search-recovery', 'state': 'FAILED_RESOURCES_AND_EVIDENCE_RETAINED',
                    'failureCode': failure, 'operation': self.operation, 'hostContextSha256': getattr(self, 'host_sha', None),
                    'pendingCommands': list(self.ssm.pending.values()), 'unknownSubmission': self.ssm.unknown,
                    'latestAck': decode(self.acks[-1]) if self.acks else None, 'journalSha256': self.journal.last,
                    'automaticResubmissionAllowed': False, 'remoteTerminalStateEstablished': False,
                    'originalExpiresAt': self.context['operator']['expiresAt'], 'observedAtEpoch': int(self.clock())})


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('package-sources', 'validate-operation', 'run', 'stage-inputs'))
    parser.add_argument('--operation', type=Path)
    parser.add_argument('--context', type=Path)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'package-sources':
            need(args.directory is not None and not args.directory.exists(), 'NEW_PACKAGE_DIRECTORY_REQUIRED')
            args.directory.mkdir(mode=0o700)
            raw, metadata = source_archive()
            host.new_file(args.directory / 'source.tar.gz', raw)
            host.write(args.directory / 'source-manifest.json', metadata)
            result = {'state': 'PUBLIC_SOURCE_ARCHIVE_CREATED', 'archive': metadata}
        elif args.command == 'stage-inputs':
            result = stage_inputs(args.context)
        else:
            operation = validate_operation(read(args.operation))
            context = validate_context(read(args.context), operation)
            if args.command == 'validate-operation':
                result = {'state': 'NATIVE_SEARCH_OPERATION_VALIDATED', 'executionCommit': operation['executionCommit'],
                          'sourceArchiveSha256': operation['sourceArchiveSha256'], 'networkCalls': 0}
                if args.output is not None:
                    host.write(args.output, result)
            else:
                result = Controller(operation, context, args.directory).run()
        print(json.dumps(result, sort_keys=True, separators=(',', ':')))
        return 0
    except BaseException as error:
        code = str(error) if isinstance(error, host.Rejected) else 'NATIVE_SEARCH_COMMAND_FAILED'
        print(json.dumps({'state': 'FAILED', 'failureCode': code, 'automaticResubmissionAllowed': False}))
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
