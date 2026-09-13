#!/usr/bin/env python3
"""Reviewed-main source R4 orchestration on an existing, leased B lab.

Only the existing AWS-RunShellScript transport and the owned CDC controller may
write. Credentials, cookies and raw SQL/Kafka records remain on Connect. An
unknown submission is never resubmitted, cancelled, or treated as a stopped host.
"""
from __future__ import annotations

import argparse
import base64
import copy
from datetime import datetime, timezone
import gzip
import hashlib
import io
import json
import math
import os
from pathlib import Path
import signal
import tarfile
import time

import growth_b_cdc_core as cdc
import growth_b_cdc_controller as controller
import growth_b_cdc as host
import growth_b_aws_restore as restore
import growth_b_service as service

core, need, Failed = cdc.core, cdc.need, cdc.Failed
ROOT = Path(__file__).resolve().parents[3]
KIND = 'global-b-aws-source-r4-operation'
CONTEXT_KIND = 'global-b-aws-source-r4-context'
CHUNK_BYTES, MAX_COMMAND_BYTES, MAX_OUTPUT_CHARS = 6144, 32768, 16000
MAX_ARCHIVE_BYTES, MAX_EXPANDED_BYTES, MAX_FILES = 512 * 1024, 8 * 1024**2, 32
COMMAND_FIELDS = 'token name instanceId containerId commandSha256 startedEpoch commandId status completedEpoch'
SSM_SOURCES = {
    'documentLimit': 'https://docs.aws.amazon.com/systems-manager/latest/APIReference/API_SendCommand.html',
    'outputLimit': 'https://docs.aws.amazon.com/systems-manager/latest/APIReference/API_GetCommandInvocation.html',
}
# AWS documents 64 KB per document and 24,000 stdout characters. The smaller
# bounds above are this tool's conservative policy, not an asserted Parameters
# API quota. We measure the entire JSON SendCommand body before submission.


def sha(raw): return hashlib.sha256(raw).hexdigest()
def encoded(value): return core.encoded(value)
def utc(): return datetime.now(timezone.utc).isoformat()


def source_files():
    import growth_b_service_verify as verify
    selected = controller.package_sources() | verify.source_identity()
    selected['infra/aws/scripts/growth_b_cdc_supervisor.py'] = sha(Path(__file__).read_bytes())
    need(1 <= len(selected) <= MAX_FILES and all(name.startswith('infra/aws/') for name in selected), 'CLOSED_AWS_SOURCE_INVENTORY_REQUIRED')
    return selected


def source_archive(selected=None, root=ROOT):
    selected = source_files() if selected is None else selected
    need(1 <= len(selected) <= MAX_FILES, 'SOURCE_FILE_COUNT_EXCEEDED')
    plain = io.BytesIO(); inventory = {}; total = 0
    with tarfile.open(fileobj=plain, mode='w', format=tarfile.USTAR_FORMAT) as archive:
        for name, expected in sorted(selected.items()):
            need(isinstance(name, str) and name.startswith('infra/aws/') and cdc.match(r'[A-Za-z0-9_./-]+', name)
                 and all(p not in ('', '.', '..') for p in name.split('/')), 'SOURCE_PATH_NOT_ALLOWED')
            path = root / name
            need(path.is_file() and not path.is_symlink() and path.resolve() == path and cdc.match(cdc.HASH, expected), 'SOURCE_FILE_MISSING_OR_LINKED')
            raw = path.read_bytes(); total += len(raw)
            need(0 < len(raw) <= 512 * 1024 and sha(raw) == expected and total <= MAX_EXPANDED_BYTES, 'SOURCE_BYTES_OR_SIZE_CHANGED')
            member = tarfile.TarInfo(name); member.size = len(raw); member.mode = 0o600; member.mtime = 0
            archive.addfile(member, io.BytesIO(raw)); inventory[name] = {'sha256': expected, 'bytes': len(raw)}
    need(len(plain.getvalue()) <= MAX_EXPANDED_BYTES, 'SOURCE_TAR_EXPANSION_EXCEEDED')
    result = io.BytesIO()
    with gzip.GzipFile(fileobj=result, mode='wb', filename='', mtime=0) as compressed: compressed.write(plain.getvalue())
    raw = result.getvalue(); need(len(raw) <= MAX_ARCHIVE_BYTES, 'SOURCE_ARCHIVE_BUDGET_EXCEEDED')
    return raw, {'sha256': sha(raw), 'bytes': len(raw), 'expandedBytes': total, 'files': inventory}


def package_sources(output):
    output = Path(output).absolute(); need(not output.exists() and output.parent.resolve() == output.parent, 'NEW_SOURCE_PACKAGE_DIRECTORY_REQUIRED')
    raw, manifest = source_archive(); output.mkdir(mode=0o700)
    core.write_new(output / 'source.tar.gz', raw)
    report = {'schemaVersion': 1, 'kind': KIND + '-sources', 'archive': manifest, 'originalSourceLayout': cdc.original_source_layout(),
              'ssmLimits': {'sources': SSM_SOURCES, 'toolCommandJsonBytes': MAX_COMMAND_BYTES, 'toolOutputCharacters': MAX_OUTPUT_CHARS, 'chunkBytes': CHUNK_BYTES},
              'awsCallsExecuted': False}
    core.write_new(output / 'source-package.json', encoded(report)); return report


def exact_ref(value, prefix):
    try: service.ref(value, prefix)
    except (ValueError, KeyError, TypeError): raise Failed('EXACT_PUBLIC_OBJECT_REFERENCE_REQUIRED') from None
    need(value['bytes'] <= 2 * 1024**2, 'R4_PUBLIC_INPUT_BUDGET_EXCEEDED')
    return value


def validate_operation(value, *, check_sources=True):
    """Offline admission for the single b_operation input, before OIDC."""
    expected = 'schemaVersion kind stage operationId runId datasetId serviceRelease executionCommit sourceArchiveSha256 manifest readiness'
    need(isinstance(value, dict), 'R4_OPERATION_OBJECT_REQUIRED')
    cdc.fields(value, expected + (' resume' if value.get('stage') == 'resume' else ''), 'R4_OPERATION_FIELDS_DIFFER')
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['stage'] in ('all', 'resume'), 'R4_OPERATION_SCOPE_REQUIRED')
    need(cdc.match(r'lab-[a-z0-9][a-z0-9-]{0,27}', value['runId'])
         and cdc.match(r'global-growth-b-[0-9a-f]{16}', value['datasetId'])
         and all(cdc.match(r'[a-z0-9][a-z0-9-]{2,47}', value[k]) for k in ('operationId', 'serviceRelease'))
         and cdc.match(r'[0-9a-f]{40}', value['executionCommit']) and cdc.match(cdc.HASH, value['sourceArchiveSha256']), 'R4_OPERATION_COORDINATES_REQUIRED')
    prefix = f"datasets/{value['datasetId']}-aws-service/{value['serviceRelease']}/"
    exact_ref(value['manifest'], prefix)
    need(value['manifest']['key'] == prefix + 'aws-service.json', 'EXACT_R4_SERVICE_MANIFEST_REQUIRED')
    exact_ref(value['readiness'], f"data-bootstrap/{value['runId']}/")
    need(value['readiness']['key'] == f"data-bootstrap/{value['runId']}/{value['datasetId']}-service-{value['serviceRelease']}.json", 'EXACT_R4_READINESS_REQUIRED')
    if value['stage'] == 'resume':
        resume = value['resume']; cdc.fields(resume, 'originalOperationSha256 supervisorJournalHeadSha256 controllerJournalHeadSha256 liveConfigurationSha256 cdcConfigurationSha256 recovery', 'R4_RESUME_FIELDS_DIFFER')
        need(all(cdc.match(cdc.HASH, resume[k]) for k in ('originalOperationSha256', 'supervisorJournalHeadSha256'))
             and all(resume[k] is None or cdc.match(cdc.HASH, resume[k]) for k in ('controllerJournalHeadSha256', 'liveConfigurationSha256', 'cdcConfigurationSha256')), 'R4_RESUME_ORIGINAL_REFS_REQUIRED')
        reference = exact_ref(resume['recovery'], f"data-bootstrap/{value['runId']}/{value['datasetId']}-r4/{value['operationId']}/")
        need(reference['key'].endswith('/recovery-' + reference['sha256'] + '.json'), 'EXACT_R4_RECOVERY_KEY_REQUIRED')
    if check_sources: need(source_archive()[1]['sha256'] == value['sourceArchiveSha256'], 'REVIEWED_R4_SOURCE_ARCHIVE_CHANGED')
    return value


def operation_binding(value):
    return sha(encoded({k: v for k, v in value.items() if k not in ('stage', 'resume')}))


def validate_context(value, operation, *, now=time.time):
    cdc.fields(value, 'schemaVersion kind executionCommit approvedExecutionDeadlineEpoch controllerDeadlineEpoch operator lease phase2 phase3 phase4 serviceState', 'R4_CONTEXT_FIELDS_DIFFER')
    need(value['schemaVersion'] == 1 and value['kind'] == CONTEXT_KIND and value['executionCommit'] == operation['executionCommit'], 'REVIEWED_R4_CONTEXT_REQUIRED')
    original = value['operator']; lease = value['lease']
    need(original.get('schemaVersion') == 2 and original.get('runId') == operation['runId'] and original.get('datasetRelease') == operation['datasetId']
         and original.get('rdsEngineVersion') == '8.4.11' and original.get('cacheEnabled') is False
         and original.get('loadGeneratorEnabled') is False and core.integer(original.get('fencingToken'), 1), 'ORIGINAL_FINAL_B_RUN_REQUIRED')
    need(cdc.match(r'[1-9][0-9]{9}', str(original.get('expiresAt'))), 'ORIGINAL_RESOURCE_EXPIRY_REQUIRED')
    expiry = int(original['expiresAt'])
    need(core.integer(value['approvedExecutionDeadlineEpoch'], 1) and core.integer(value['controllerDeadlineEpoch'], 1)
         and now() < value['controllerDeadlineEpoch'] <= min(expiry, value['approvedExecutionDeadlineEpoch'], now() + 18000), 'R4_COMMON_DEADLINE_REQUIRED')
    cdc.fields(lease, 'table lockName owner runId command fencingToken', 'R4_LEASE_FIELDS_DIFFER')
    need(lease['table'] == 'airbob-performance-lab-orchestration-lease' and lease['lockName'] == 'airbob-performance-lab'
         and lease['runId'] == operation['runId'] and lease['command'] == 'up' and core.integer(lease['fencingToken'], 1), 'R4_UP_LEASE_REQUIRED')
    phase2, phase3, phase4, state = (value[k] for k in ('phase2', 'phase3', 'phase4', 'serviceState'))
    need(phase2['run_id'] == operation['runId'] and phase2['fencing_token'] == original['fencingToken']
         and {'debezium', 'kafka', 'elasticsearch'} <= set(phase2['services']) and all(cdc.match(cdc.INSTANCE, phase2['services'][k]) for k in ('debezium', 'kafka', 'elasticsearch')),
         'CURRENT_SERVICE_HOST_TOPOLOGY_REQUIRED')
    need(phase3['dataset_release'] == operation['datasetId'] and phase3['rds_instance_id'] == 'airbob-' + operation['runId']
         and phase3['rds_engine_version'] == '8.4.11', 'CURRENT_FINAL_RDS_TOPOLOGY_REQUIRED')
    need(phase4['app_enabled'] is True and phase4['capacity'] == {'min': 1, 'desired': 1, 'max': 1}
         and phase4['accommodation_detail_cache_enabled'] is False and phase4['load_generator_enabled'] is False
         and phase4['auto_scaling_group_name'] == 'airbob-' + operation['runId'] + '-app'
         and cdc.match(cdc.HASH, phase4['runtime_revision']), 'CURRENT_SINGLE_APP_PROFILE_REQUIRED')
    need(state['selected'] is True and state['manifest_key'] == operation['manifest']['key']
         and state['manifest_version_id'] == operation['manifest']['versionId'] and state['manifest_sha256'] == operation['manifest']['sha256']
         and state['readiness_receipt'] == {'key': operation['readiness']['key'], 'version_id': operation['readiness']['versionId'], 'sha256': operation['readiness']['sha256'], 'bytes': operation['readiness']['bytes']},
         'CURRENT_R4_TERRAFORM_REFERENCES_CHANGED')
    need(cdc.match(cdc.IMAGE + r'airbob-infra/kafka@sha256:[0-9a-f]{64}', original['infraImageReferences']['KAFKA_IMAGE']), 'CURRENT_KAFKA_IMAGE_PIN_REQUIRED')
    return value


class RecoveryJournal(core.Journal):
    """Closed controller metadata; larger than the host's bounded API journal."""
    def add(self, kind, data):
        need(len(self.entries) < 1024, 'R4_RECOVERY_JOURNAL_BUDGET')
        item = {'sequence': len(self.entries), 'previousSha256': self.last_sha, 'kind': kind, 'utc': utc(), 'monotonicNs': time.monotonic_ns(), 'data': data}
        raw = encoded(item); need(len(raw) <= core.MAX_BYTES, 'R4_RECOVERY_RECORD_BUDGET')
        core.write_new(self.path / f'{len(self.entries):04d}.json', raw)
        self.entries.append(item); self.last_sha = sha(raw)
        checkpoint = getattr(self, 'checkpoint', None)
        if checkpoint: checkpoint()
        return item


class BoundedAws(restore.Aws):
    ALLOWED = {('sts', 'get-caller-identity'), ('dynamodb', 'get-item'), ('rds', 'describe-db-instances'),
        ('ec2', 'describe-instances'), ('ec2', 'describe-vpcs'), ('ec2', 'describe-launch-template-versions'),
        ('autoscaling', 'describe-auto-scaling-groups'), ('autoscaling', 'describe-scaling-activities'), ('autoscaling', 'update-auto-scaling-group'),
        ('s3api', 'get-object'), ('ssm', 'send-command'), ('ssm', 'list-command-invocations'), ('ssm', 'get-command-invocation')}
    def __init__(self, deadline, *, clock=time.time): self.deadline, self.clock = deadline, clock
    def call(self, *args):
        need(tuple(args[:2]) in self.ALLOWED, 'R4_AWS_ACTION_NOT_ALLOWED')
        remaining = self.deadline - self.clock(); need(remaining > 0, 'R4_COMMON_DEADLINE_REACHED')
        environment = dict(os.environ, AWS_MAX_ATTEMPTS='1', AWS_RETRY_MODE='standard', AWS_CLI_AUTO_PROMPT='off')
        result = restore.command(['aws', '--region', cdc.REGION, '--no-cli-pager', '--output', 'json', '--cli-connect-timeout', '5',
            '--cli-read-timeout', str(max(1, min(30, int(remaining)))), *args], env=environment, timeout=min(45, remaining))
        return json.loads(result or '{}')


class Ssm(controller.Ssm):
    def wait(self, item, deadline):
        while self.clock() < deadline:
            self.guard()
            rows = self.aws.call('ssm', 'list-command-invocations', '--command-id', item['commandId'], '--instance-id', item['instanceId'])['CommandInvocations']
            need(len(rows) <= 1, 'SSM_INVOCATION_AMBIGUOUS')
            if rows and rows[0]['Status'] in self.TERMINAL:
                result = self.aws.call('ssm', 'get-command-invocation', '--command-id', item['commandId'], '--instance-id', item['instanceId'])
                need(result['InstanceId'] == item['instanceId'] and result['CommandId'] == item['commandId'] and result['Status'] == rows[0]['Status'], 'SSM_RESULT_IDENTITY_CHANGED')
                complete = item | {'status': result['Status'], 'completedEpoch': int(self.clock())}; value = None
                if result['Status'] == 'Success' and result.get('ResponseCode') == 0:
                    raw = result.get('StandardOutputContent', '').encode()
                    need(len(raw) <= MAX_OUTPUT_CHARS, 'R4_SSM_SAFE_OUTPUT_BUDGET'); value = core.parse(raw)
                    if item['name'] == 'connect-start' and value.get('passed') is True:
                        observed = controller.connect_observation(value['observation'])
                        need(observed['instanceId'] == item['instanceId'] and observed['containerId'] == item['containerId'], 'SSM_CONNECT_TARGET_CHANGED')
                        complete['safeObservation'] = observed
                self.journal.add('SSM_TERMINAL', complete)
                need(result['Status'] == 'Success' and result.get('ResponseCode') == 0, 'SSM_COMMAND_UNCONFIRMED')
                return value, complete
            self.sleep(min(2, max(0, deadline - self.clock())))
        raise Failed('SSM_COMMAND_STILL_UNCONFIRMED')

    def run(self, name, instance, cid, program, deadline, *, seconds=90):
        self.settle(deadline); self.guard()
        if hasattr(self, 'common_deadline'):
            need(self.clock() + seconds + 60 <= self.common_deadline, 'SSM_EXECUTION_EXCEEDS_COMMON_DEADLINE')
        discovery = name in ('discover-app', 'discover-connect', 'discover-kafka', 'discover-elasticsearch')
        need(cdc.match(cdc.INSTANCE, instance) and ((discovery and cid is None) or cdc.match(cdc.HASH, cid))
             and core.integer(seconds, 1) and seconds <= 18000, 'R4_EXACT_SSM_TARGET_REQUIRED')
        parameters = {'commands': [program], 'executionTimeout': [str(seconds)]}
        request = {'DocumentName': 'AWS-RunShellScript', 'DocumentVersion': '1', 'InstanceIds': [instance], 'TimeoutSeconds': 60, 'Parameters': parameters}
        need(len(encoded(request)) <= MAX_COMMAND_BYTES, 'R4_SSM_COMMAND_JSON_BUDGET')
        item = {'token': len(self.journal.entries), 'name': name, 'instanceId': instance, 'containerId': cid,
                'commandSha256': sha(program.encode()), 'startedEpoch': int(self.clock())}
        self.journal.add('SSM_INTENT', item)
        response = self.aws.call('ssm', 'send-command', '--document-name', 'AWS-RunShellScript', '--document-version', '1',
            '--instance-ids', instance, '--timeout-seconds', '60', '--parameters', json.dumps(parameters))
        identifier = response['Command']['CommandId']
        need(cdc.match(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', identifier), 'R4_SSM_COMMAND_ID_REQUIRED')
        item = item | {'commandId': identifier}; self.journal.add('SSM_SUBMITTED', item)
        value, terminal = self.wait(item, deadline)
        need(len(encoded(value)) <= MAX_OUTPUT_CHARS, 'R4_SSM_SAFE_OUTPUT_BUDGET')
        return value, terminal


def tf_digest(value):
    raw = json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False)
    for a, b in (('&', r'\u0026'), ('<', r'\u003c'), ('>', r'\u003e'), ('\u2028', r'\u2028'), ('\u2029', r'\u2029')): raw = raw.replace(a, b)
    return sha(raw.encode())


def validate_runtime_revision(context, operation, manifest, readiness):
    original, phase4 = context['operator'], context['phase4']
    legacy = tf_digest({'run_id': operation['runId'], 'app_image_reference': manifest['application']['image'],
        'bundle_sha256': original['bundleSha256'], 'dataset_manifest_sha256': operation['manifest']['sha256'],
        'rds_resource_id': manifest['rds']['resourceId'], 'measurement_policy': phase4['measurement_policy'], 'accommodation_detail_cache_enabled': False})
    actual = tf_digest({'legacyRevision': legacy, 'globalBReadiness': context['serviceState']['readiness_receipt'],
        'appRuntimeBinding': manifest['appRuntimeBinding'], 'appRuntime': readiness['appRuntime']})
    need(actual == phase4['runtime_revision'] == context['serviceState']['runtime_revision'], 'CACHE_DISABLED_RUNTIME_REVISION_CHANGED')
    return actual


APP_POLICY = r'''import base64,json,subprocess
p=json.loads(base64.b64decode('__PAYLOAD__'))
def read(args):
 r=subprocess.run(['docker','--host','unix:///var/run/docker.sock']+args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10)
 if r.returncode: raise SystemExit(1)
 return json.loads(r.stdout)[0]
x=read(['inspect',p['containerId']]); image=read(['image','inspect',x['Image']])
env=dict(v.split('=',1) for v in x['Config']['Env'])
valid=(x['Id']==p['containerId'] and x['State']['Running'] and x['State']['StartedAt']==p['startedAt']
 and x['Image']==p['imageId'] and x['Config']['Image']==p['image'] and env.get('ACCOMMODATION_DETAIL_CACHE_ENABLED')=='false'
 and env.get('JAVA_OPTS')=='-Xms1536m -Xmx1536m -XX:+UseG1GC'
 and not any(env.get(k) for k in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JDK_JAVAC_OPTIONS','CLASSPATH'))
 and not any(k=='SPRING_APPLICATION_JSON' or k.startswith('SPRING_CONFIG_') or k.startswith('SPRING_PROFILES_') and k!='SPRING_PROFILES_ACTIVE' for k in env)
 and x['Config'].get('Entrypoint')==image['Config'].get('Entrypoint') and x['Config'].get('Cmd')==image['Config'].get('Cmd'))
if not valid: raise SystemExit(1)
'''


def app_program(configuration, readiness):
    """Additional policy checks precede the unchanged, pinned app observer."""
    cdc.validate_config(configuration, check_files=False)
    return observed_app_program(configuration['hosts']['app'], configuration['asg']['runtimeRevision'], readiness['appRuntime'])


def observed_app_program(pin, runtime_revision, runtime):
    """Fixed runtime probe; the caller separately admits its source family."""
    cdc.fields(pin, 'instanceId privateIp containerId imageId image startedAt', 'EXACT_APP_RUNTIME_PIN_REQUIRED')
    need(cdc.match(cdc.INSTANCE, pin['instanceId']) and cdc.match(cdc.HASH, pin['containerId'])
         and cdc.match(r'sha256:[0-9a-f]{64}', pin['imageId']) and cdc.match(cdc.HASH, runtime_revision), 'EXACT_APP_RUNTIME_PIN_REQUIRED')
    configuration = {'hosts': {'app': pin}, 'asg': {'runtimeRevision': runtime_revision}}
    policy = APP_POLICY.replace('__PAYLOAD__', base64.b64encode(encoded(pin)).decode())
    return "set -eu\npython3 - <<'AIRBOB_R4_APP_POLICY'\n" + policy + '\nAIRBOB_R4_APP_POLICY\n' + controller.host_program('app', 'observe', configuration, runtime=runtime)


HOST_COMMON = r'''import base64,fcntl,gzip,hashlib,io,json,os,pathlib,subprocess,sys,tarfile,time,urllib.request
p=json.loads(base64.b64decode('__PAYLOAD__'))
def need(v):
 if not v: raise RuntimeError('OWNED_R4_HOST_STATE_REQUIRED')
def digest(raw): return hashlib.sha256(raw).hexdigest()
def run(args):
 r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
 need(r.returncode==0); return r.stdout
def aws(*args):
 env={k:v for k,v in os.environ.items() if not k.startswith('AWS_')}
 env.update(AWS_CONFIG_FILE='/dev/null',AWS_SHARED_CREDENTIALS_FILE='/dev/null',AWS_EC2_METADATA_DISABLED='false',AWS_MAX_ATTEMPTS='1',AWS_CLI_AUTO_PROMPT='off')
 binary=str(pathlib.Path('/opt/airbob/global-b')/p['runId']/'aws-bin/aws')
 r=subprocess.run([binary,'--region','ap-northeast-2','--no-cli-pager','--output','json','--cli-connect-timeout','5','--cli-read-timeout','10']+list(args),env=env,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
 need(r.returncode==0); return json.loads(r.stdout)
def private(path,directory=False):
 need(path.resolve()==path and not path.is_symlink() and path.stat().st_uid==os.geteuid() and path.stat().st_mode&0o777==(0o700 if directory else 0o600))
 need(path.is_dir() if directory else path.is_file()); return path
def new_directory(path):
 if not path.exists(): path.mkdir(mode=0o700)
 return private(path,True)
def create(path,raw):
 if path.exists(): need(private(path).read_bytes()==raw); return
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,'wb') as f: f.write(raw); f.flush(); os.fsync(f.fileno())
 d=os.open(path.parent,os.O_RDONLY|os.O_DIRECTORY)
 try: os.fsync(d)
 finally: os.close(d)
def guard():
 need(sys.platform=='linux' and time.time()<p['deadlineEpoch']<=p['expiresAt'])
 opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
 with opener.open(urllib.request.Request('http://169.254.169.254/latest/api/token',method='PUT',headers={'X-aws-ec2-metadata-token-ttl-seconds':'60'}),timeout=3) as response: token=response.read(1024).decode()
 with opener.open(urllib.request.Request('http://169.254.169.254/latest/meta-data/instance-id',headers={'X-aws-ec2-metadata-token':token}),timeout=3) as response: need(response.read(1024).decode()==p['pin']['instanceId'])
 need(aws('sts','get-caller-identity')['Account']=='942632789808')
 lease=p['lease']; item=aws('dynamodb','get-item','--table-name',lease['table'],'--key',json.dumps({'LockName':{'S':lease['lockName']}}),'--consistent-read')['Item']
 need(all(item[k]['S']==lease[v] for k,v in (('Owner','owner'),('RunId','runId'),('Command','command')))
  and int(item['FencingToken']['N'])==lease['fencingToken'] and float(item['ExpiresAt']['N'])>time.time() and float(item['CommandDeadline']['N'])>time.time())
 x=json.loads(run(['docker','--host','unix:///var/run/docker.sock','inspect',p['pin']['containerId']]))[0]
 need(x['Id']==p['pin']['containerId'] and x['Image']==p['pin']['imageId'] and x['Config']['Image']==p['pin']['image']
  and x['State']['StartedAt']==p['pin']['startedAt'] and x['State']['Running']==p.get('connectRunning',True) and not x['State']['Paused'] and not x['State']['Restarting'])
 if not p.get('connectRunning',True): need(x['State']['FinishedAt']==p['connectFinishedAt'])
 root=private(pathlib.Path('/opt/airbob/global-b')/p['runId'],True); need(not (root/'STOP').exists())
 return root
'''

STAGE_BODY = r'''try:
 root=guard(); area=new_directory(new_directory(root/'r4-staging')/p['operationId'])
 lock=os.open(area/'.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
 fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 # A worker's open lease/PID lock blocks every source write, including resumes.
 active=root/'r4'/p['operationId']/'worker.lock'
 if active.exists():
  private(active); fd=os.open(active,os.O_RDWR|os.O_NOFOLLOW); fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
 manifest=p['archive']; create(area/'archive.json',(json.dumps(manifest,sort_keys=True,separators=(',',':'))+'\n').encode())
 if p['action']=='chunk':
  data=base64.b64decode(p['data'],validate=True)
  need(0<len(data)<=6144 and digest(data)==p['chunkSha256'] and 0<=p['index']<p['chunks']<=86)
  need(p['chunks']==(manifest['bytes']+6143)//6144 and len(data)==min(6144,manifest['bytes']-p['index']*6144))
  create(area/('%03d.chunk'%p['index']),data)
  print(json.dumps({'passed':True,'index':p['index'],'sha256':digest(data),'bytes':len(data)}))
 elif p['action']=='install':
  need(0<manifest['bytes']<=524288 and len(manifest['files'])<=32)
  chunks=(manifest['bytes']+6143)//6144
  raw=b''.join(private(area/('%03d.chunk'%i)).read_bytes() for i in range(chunks))
  need(len(raw)==manifest['bytes'] and digest(raw)==manifest['sha256'])
  with gzip.GzipFile(fileobj=io.BytesIO(raw)) as stream:
   expanded=stream.read(8388609); need(len(expanded)<=8388608 and not stream.read(1))
  tools=new_directory(root/'cdc-tools')
  with tarfile.open(fileobj=io.BytesIO(expanded),mode='r:') as archive:
   members=archive.getmembers(); need(len(members)==len(manifest['files']) and {m.name for m in members}==set(manifest['files']))
   total=0
   for member in members:
    need(member.isfile() and not member.pax_headers and member.name.startswith('infra/aws/') and all(v not in ('','.','..') for v in member.name.split('/')))
    spec=manifest['files'][member.name]; need(0<member.size==spec['bytes']<=524288)
    data=archive.extractfile(member).read(); need(len(data)==member.size and digest(data)==spec['sha256']); total+=len(data)
    current=tools
    for name in member.name.split('/')[:-1]: current=new_directory(current/name)
    create(tools/member.name,data)
   need(total==manifest['expandedBytes']<=8388608)
  guard(); create(tools/'source-package.json',(json.dumps(manifest,sort_keys=True,separators=(',',':'))+'\n').encode())
  print(json.dumps({'passed':True,'archiveSha256':manifest['sha256'],'files':len(manifest['files']),'expandedBytes':total}))
 else: raise RuntimeError('UNKNOWN_STAGE_ACTION')
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_SOURCE_STAGE_UNCONFIRMED'})); raise SystemExit(1)
'''


def program(body, payload, marker='AIRBOB_R4_FIXED'):
    need(cdc.match(r'AIRBOB_R4_[A-Z_]+', marker), 'CLOSED_PROGRAM_MARKER_REQUIRED')
    source = HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(encoded(payload)).decode()) + body
    return "python3 - <<'" + marker + "'\n" + source + '\n' + marker


def stage_program(configuration, archive, *, index=None, data=None):
    payload = {'runId': configuration['runId'], 'operationId': configuration['operationId'], 'lease': configuration['lease'],
        'deadlineEpoch': configuration['deadlineEpoch'], 'expiresAt': configuration['expiresAt'], 'pin': configuration['pin'], 'archive': archive}
    if index is None: payload['action'] = 'install'
    else:
        need(isinstance(data, bytes) and 0 < len(data) <= CHUNK_BYTES, 'BOUNDED_SOURCE_CHUNK_REQUIRED')
        payload.update(action='chunk', index=index, chunks=(archive['bytes'] + CHUNK_BYTES - 1) // CHUNK_BYTES,
                       data=base64.b64encode(data).decode(), chunkSha256=sha(data))
    return program(STAGE_BODY, payload)


DISCOVER = r'''import base64,hashlib,json,subprocess,urllib.request
p=json.loads(base64.b64decode('__PAYLOAD__'))
def need(v):
 if not v: raise RuntimeError('EXACT_R4_DISCOVERY_REQUIRED')
def call(args):
 r=subprocess.run(['docker','--host','unix:///var/run/docker.sock']+args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=15)
 need(r.returncode==0); return r.stdout
try:
 ids=call(['ps','-q','--no-trunc','--filter','label=com.docker.compose.service='+p['service']]).decode().splitlines(); need(len(ids)==1)
 x=json.loads(call(['inspect',ids[0]]))[0]
 need(x['Id']==ids[0] and x['Config']['Image']==p['image'] and x['State']['Running'] and not x['State']['Paused'] and not x['State']['Restarting'])
 image=json.loads(call(['image','inspect',x['Image']]))[0]; need(p['image'] in image.get('RepoDigests',[]))
 result={'instanceId':p['instanceId'],'privateIp':p['privateIp'],'containerId':x['Id'],'imageId':x['Image'],'image':x['Config']['Image'],'startedAt':x['State']['StartedAt']}
 if p['service']=='elasticsearch':
  class NoRedirect(urllib.request.HTTPRedirectHandler):
   def redirect_request(self,*args): return None
  opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
  def get(path):
   with opener.open('http://127.0.0.1:9200'+path,timeout=10) as response: need(response.status==200); return json.loads(response.read(262144))
  es=get('/'); alias=get('/_alias/accommodations'); need(es['version']['number']=='8.18.8' and set(alias)=={p['restoredIndex']} and alias[p['restoredIndex']]['aliases']['accommodations']['is_write_index'] is True)
  result.update(clusterUuid=es['cluster_uuid'],indexName=p['restoredIndex'])
 print(json.dumps({'passed':True,'observation':result},separators=(',',':')))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_DISCOVERY_UNCONFIRMED'})); raise SystemExit(1)
'''


def discovery_program(role, instance, image, readiness):
    names = {'app': 'app', 'connect': 'debezium', 'kafka': 'kafka', 'elasticsearch': 'elasticsearch'}
    need(role in names and cdc.match(cdc.INSTANCE, instance['InstanceId']), 'CLOSED_DISCOVERY_ROLE_REQUIRED')
    payload = {'service': names[role], 'instanceId': instance['InstanceId'], 'privateIp': instance['PrivateIpAddress'], 'image': image}
    if role == 'elasticsearch': payload['restoredIndex'] = readiness['restoredIndex']
    return "python3 - <<'AIRBOB_R4_DISCOVER'\n" + DISCOVER.replace('__PAYLOAD__', base64.b64encode(encoded(payload)).decode()) + '\nAIRBOB_R4_DISCOVER'


RETAINED_BODY = r'''try:
 root=guard(); release=root/'release'; stage=root/('service-'+p['serviceRelease'])
 def document(path,expected):
  raw=private(path).read_bytes(); need(len(raw)<=2097152 and digest(raw)==expected); return json.loads(raw)
 manifest=p['manifest']; prep=manifest['preparation']
 config=document(root/'restore-config.json',prep['restoreConfigSha256'])
 document(root/'execute/restore-receipt.json',prep['restoreReceiptSha256'])
 document(stage/'aws-service.json',p['manifestSha256'])
 document(stage/'bootstrap/service-readiness.json',p['readinessSha256'])
 document(stage/'bootstrap/preparation.json',prep['receipt']['sha256'])
 document(stage/'bootstrap/prepared-fingerprint.json',prep['preparedFingerprintSha256'])
 need(digest(private(root/'rds-ca.pem').read_bytes())==prep['rdsCaBundle']['sha256'])
 envelope=document(root/'envelope.json',config['envelopeSha256']); objects=envelope['objects']
 consumer=document(release/'consumer-manifest.json',objects['consumer-manifest.json']['sha256'])
 checks=document(release/'SHA256SUMS.json',objects['SHA256SUMS.json']['sha256'])
 need(consumer['datasetId']==p['datasetId'] and consumer['finalScaleSelected'] is True and consumer['datasetScale']=='selected-global-b-ten-million')
 accounts=document(release/'accounts.json',objects['accounts.json']['sha256'])
 representatives=document(release/'representative-accounts.json',objects['representative-accounts.json']['sha256'])
 need(accounts['representativeAccounts']==representatives['accounts'] and representatives['finalScaleSelected'] is True)
 hosts=[v for v in representatives['accounts'] if v['key']=='host']; need(len(hosts)==1)
 owner=hosts[0]; need(owner['email']=='host@airbob.test' and owner['role']=='MEMBER' and owner['status']=='ACTIVE')
 need(config['privateAccounts']==str(root/'private/accounts.private.json') and all(config['rds'][k]==manifest['rds'][k] for k in ('identifier','resourceId','serverUuid')))
 result={'consumerManifestSha256':objects['consumer-manifest.json']['sha256'],'checksumsSha256':objects['SHA256SUMS.json']['sha256'],
  'ownerMemberId':owner['memberId'],'accommodationId':owner['ownership']['publishedListings']['sampleIds'][0],
  'restoreConfigSha256':prep['restoreConfigSha256'],'restoreReceiptSha256':prep['restoreReceiptSha256'],'rds':config['rds']}
 print(json.dumps({'passed':True,'retained':result},separators=(',',':')))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_RETAINED_INPUTS_UNCONFIRMED'})); raise SystemExit(1)
'''


class Builder:
    def __init__(self, operation, context, directory, journal, *, aws, clock=time.time, sleep=time.sleep, transport=None):
        validate_operation(operation); validate_context(context, operation, now=clock)
        self.operation, self.context, self.directory, self.journal = operation, context, Path(directory), journal
        self.aws, self.clock, self.sleep = aws, clock, sleep
        self.deadline = context['controllerDeadlineEpoch']; self.lease = restore.Lease(aws, context['lease'])
        self.transport = transport or Ssm(aws, journal, self.guard, clock=clock, sleep=sleep)
        self.manifest = self.readiness = None

    def guard(self):
        need(self.clock() < self.deadline <= min(int(self.context['operator']['expiresAt']), self.context['approvedExecutionDeadlineEpoch']), 'R4_COMMON_DEADLINE_REACHED')
        self.lease(force=True)

    def resource_tags(self, rows, *, role=None):
        actual = host.tags(rows); original = self.context['operator']
        pin = {'runId': self.operation['runId'], 'resourceFencingToken': original['fencingToken'], 'expiresAt': int(original['expiresAt'])}
        host.require_tags(actual, pin)
        if role: need(actual.get('Service') == role, 'R4_RESOURCE_SERVICE_CHANGED')
        return actual

    def fetch(self, reference, name, bucket):
        self.guard(); path = self.directory / name
        if path.exists(): need(not path.is_symlink() and path.stat().st_size == reference['bytes'] and sha(path.read_bytes()) == reference['sha256'], 'SAVED_R4_INPUT_CHANGED')
        else:
            reply = self.aws.call('s3api', 'get-object', '--bucket', bucket, '--key', reference['key'], '--version-id', reference['versionId'], str(path))
            need(reply.get('VersionId') == reference['versionId'] and path.is_file() and path.stat().st_size == reference['bytes']
                 and sha(path.read_bytes()) == reference['sha256'], 'EXACT_R4_INPUT_VERSION_OR_BYTES_CHANGED'); path.chmod(0o600)
        return core.parse(path.read_bytes())

    def selected_inputs(self):
        op = self.operation
        self.manifest = self.fetch(op['manifest'], 'selected-service.json', service.BUCKET)
        self.readiness = self.fetch(op['readiness'], 'selected-readiness.json', service.EVIDENCE)
        service.validate_manifest(self.manifest, op['datasetId'], op['runId'], op['serviceRelease'],
            {name: service.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS})
        service.validate_readiness(self.readiness, self.manifest, op['manifest']['sha256'])
        validate_runtime_revision(self.context, op, self.manifest, self.readiness)

    def instance(self, identifier, *, role):
        self.guard(); reply = self.aws.call('ec2', 'describe-instances', '--instance-ids', identifier)['Reservations']
        rows = [row for reservation in reply for row in reservation['Instances']]
        need(len(rows) == 1 and rows[0]['InstanceId'] == identifier and rows[0]['State']['Name'] == 'running', 'R4_EXACT_RUNNING_HOST_REQUIRED')
        row = rows[0]; actual = self.resource_tags(row['Tags'], role=role)
        need(row['VpcId'] == self.context['phase2']['vpc_id'] and cdc.match(r'10\.[0-9.]+', row['PrivateIpAddress']), 'R4_HOST_VPC_CHANGED')
        if role == 'app': need(actual.get('RuntimeRevision') == self.context['phase4']['runtime_revision'], 'R4_APP_RUNTIME_TAG_CHANGED')
        return row

    def topology(self):
        self.guard(); op, context = self.operation, self.context
        rows = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', 'airbob-' + op['runId'])['DBInstances']
        need(len(rows) == 1, 'R4_EXACT_RDS_REQUIRED'); rds = rows[0]
        need(rds['DbiResourceId'] == self.manifest['rds']['resourceId'] == context['phase3']['rds_resource_id']
             and rds['DBInstanceIdentifier'] == self.manifest['rds']['identifier'] and rds['DBInstanceStatus'] == 'available'
             and rds['EngineVersion'] == '8.4.11' and rds['PubliclyAccessible'] is False
             and rds['Endpoint']['Address'] == context['phase3']['rds_endpoint'] and rds['Endpoint']['Port'] == 3306, 'R4_RDS_TARGET_CHANGED')
        self.resource_tags(rds['TagList'])
        groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
        groups = [g for g in groups if g['AutoScalingGroupName'].startswith('airbob-' + op['runId'] + '-')]
        need(len(groups) == 1 and groups[0]['AutoScalingGroupName'] == context['phase4']['auto_scaling_group_name'], 'R4_EXACT_WRITER_ASG_REQUIRED')
        group = groups[0]; self.resource_tags(group['Tags'], role='app')
        need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (1, 1, 1)
             and len(group['Instances']) == 1 and group['Instances'][0]['LifecycleState'] == 'InService'
             and group['Instances'][0]['HealthStatus'] == 'Healthy' and group['Instances'][0]['ProtectedFromScaleIn'] is False
             and group['Instances'][0]['LaunchTemplate'] == group['LaunchTemplate'] and not group.get('MixedInstancesPolicy')
             and not group.get('SuspendedProcesses') and not group.get('NewInstancesProtectedFromScaleIn', False), 'R4_ONE_NORMAL_APP_REQUIRED')
        instances = {'app': self.instance(group['Instances'][0]['InstanceId'], role='app')}
        for role, source in (('connect', 'debezium'), ('kafka', 'kafka'), ('elasticsearch', 'elasticsearch')):
            instances[role] = self.instance(context['phase2']['services'][source], role=source)
        vpcs = self.aws.call('ec2', 'describe-vpcs', '--vpc-ids', context['phase2']['vpc_id'])['Vpcs']
        need(len(vpcs) == 1 and vpcs[0]['VpcId'] == context['phase2']['vpc_id'], 'R4_EXACT_VPC_REQUIRED')
        return group, rds, instances, vpcs[0]['CidrBlock']

    def discover(self):
        self.selected_inputs(); group, rds, instances, network = self.topology()
        images = {'app': self.manifest['application']['image'], 'connect': self.manifest['debezium']['image'],
            'kafka': self.context['operator']['infraImageReferences']['KAFKA_IMAGE'], 'elasticsearch': self.manifest['search']['image']}
        pins = {}
        for role in ('app', 'connect', 'kafka', 'elasticsearch'):
            value, command = self.transport.run('discover-' + role, instances[role]['InstanceId'], None,
                discovery_program(role, instances[role], images[role], self.readiness), self.deadline)
            need(value.get('passed') is True, 'R4_HOST_DISCOVERY_UNCONFIRMED'); pin = value['observation']
            cdc.fields(pin, 'instanceId privateIp containerId imageId image startedAt' + (' clusterUuid indexName' if role == 'elasticsearch' else ''), 'R4_DISCOVERY_FIELDS_DIFFER')
            need(pin['instanceId'] == instances[role]['InstanceId'] and pin['privateIp'] == instances[role]['PrivateIpAddress'] and pin['image'] == images[role]
                 and cdc.match(cdc.HASH, pin['containerId']) and cdc.match(r'sha256:[0-9a-f]{64}', pin['imageId']), 'R4_DISCOVERED_IDENTITY_CHANGED')
            pins[role] = pin; self.journal.add('HOST_DISCOVERED', {'role': role, 'pin': pin, 'command': command})
        fresh_group, fresh_rds, fresh_instances, fresh_network = self.topology()
        need(fresh_group == group and fresh_rds['DbiResourceId'] == rds['DbiResourceId']
             and fresh_network == network and all(fresh_instances[k]['InstanceId'] == instances[k]['InstanceId']
             and fresh_instances[k]['PrivateIpAddress'] == instances[k]['PrivateIpAddress'] for k in instances), 'DISCOVERY_TOPOLOGY_CHANGED')
        return {'group': group, 'rds': rds, 'pins': pins, 'networkCidr': network}

    def retained(self, discovered):
        p = {'runId': self.operation['runId'], 'operationId': self.operation['operationId'], 'datasetId': self.operation['datasetId'],
            'serviceRelease': self.operation['serviceRelease'], 'lease': self.context['lease'], 'deadlineEpoch': self.deadline,
            'expiresAt': int(self.context['operator']['expiresAt']), 'pin': discovered['pins']['connect'], 'manifest': self.manifest,
            'manifestSha256': self.operation['manifest']['sha256'], 'readinessSha256': self.operation['readiness']['sha256']}
        value, command = self.transport.run('retained-metadata', p['pin']['instanceId'], p['pin']['containerId'], program(RETAINED_BODY, p), self.deadline)
        need(value.get('passed') is True, 'R4_RETAINED_INPUTS_UNCONFIRMED')
        result = value['retained']; cdc.fields(result, 'consumerManifestSha256 checksumsSha256 ownerMemberId accommodationId restoreConfigSha256 restoreReceiptSha256 rds')
        cdc.fields(result['rds'], 'identifier resourceId endpoint serverUuid masterSecretArn caBundle caBundleSha256')
        for key in ('consumerManifestSha256', 'checksumsSha256', 'restoreConfigSha256', 'restoreReceiptSha256'): need(cdc.match(cdc.HASH, result[key]), 'R4_RETAINED_HASH_REQUIRED')
        need(result['rds']['endpoint'] == discovered['rds']['Endpoint']['Address']
             and result['rds']['masterSecretArn'] == discovered['rds']['MasterUserSecret']['SecretArn']
             and result['restoreConfigSha256'] == self.manifest['preparation']['restoreConfigSha256']
             and result['restoreReceiptSha256'] == self.manifest['preparation']['restoreReceiptSha256'], 'R4_RETAINED_RDS_OR_PREPARATION_CHANGED')
        self.journal.add('RETAINED_INPUTS_OBSERVED', {'result': result, 'command': command}); return result

    def configuration(self, discovered, retained):
        op, context = self.operation, self.context; root = '/opt/airbob/global-b/' + op['runId']; stage = root + '/service-' + op['serviceRelease']
        def local(path, digest): return {'path': path, 'sha256': digest}
        started = int(self.clock()); deadline = min(started + 900, self.deadline)
        value = {'schemaVersion': 1, 'kind': cdc.KIND, 'operationId': op['operationId'], 'runId': op['runId'], 'datasetId': op['datasetId'],
            'account': cdc.ACCOUNT, 'region': cdc.REGION, 'executionCommit': op['executionCommit'], 'resourceFencingToken': context['operator']['fencingToken'],
            'expiresAt': int(context['operator']['expiresAt']), 'approvedExecutionDeadlineEpoch': context['approvedExecutionDeadlineEpoch'],
            'businessStartedEpoch': started, 'businessDeadlineEpoch': deadline, 'lease': copy.deepcopy(context['lease']),
            'releaseDirectory': root + '/release', 'consumerManifestSha256': retained['consumerManifestSha256'], 'checksumsSha256': retained['checksumsSha256'],
            'privateAccounts': root + '/private/accounts.private.json', 'accountEnvironment': 'aws:' + retained['rds']['resourceId'] + ':' + retained['rds']['serverUuid'],
            'ownerMemberId': retained['ownerMemberId'], 'accommodationId': retained['accommodationId'], 'mysqlServerUuid': retained['rds']['serverUuid'],
            'rds': {k: v for k, v in retained['rds'].items() if k != 'caBundleSha256'} | {'caSha256': retained['rds']['caBundleSha256']},
            'asg': {'name': discovered['group']['AutoScalingGroupName'], 'arn': discovered['group']['AutoScalingGroupARN'],
                    'launchTemplate': discovered['group']['LaunchTemplate'], 'runtimeRevision': context['phase4']['runtime_revision'], 'originalCapacity': {'min': 1, 'desired': 1, 'max': 1}},
            'hosts': discovered['pins'], 'networkCidr': discovered['networkCidr'], 'preconditions': {
                'preparedFingerprint': local(stage + '/bootstrap/prepared-fingerprint.json', self.manifest['preparation']['preparedFingerprintSha256']),
                'preparationReceipt': local(stage + '/bootstrap/preparation.json', self.manifest['preparation']['receipt']['sha256']),
                'serviceReceipt': local(stage + '/bootstrap/service-readiness.json', op['readiness']['sha256']), 'exclusiveWriterWindow': True, 'detailCacheState': 'DISABLED'},
            'serviceManifest': op['manifest'] | {'path': stage + '/aws-service.json'},
            'serviceReadiness': op['readiness'] | {'path': stage + '/bootstrap/service-readiness.json'},
            'requestTimeoutSeconds': 10, 'propagationTimeoutSeconds': 180, 'maximumSeconds': 900}
        cdc.validate_config(value, check_files=False)
        controller.validate_selected_service(value, self.manifest, self.readiness, context['phase4'], context['serviceState'])
        return value


PUBLIC_BANNED_KEYS = {'password', 'passwords', 'credentials', 'credential', 'cookies', 'cookie', 'rawHex', 'originalName', 'expectedName',
    'SecretString', 'SecretBinary', 'AccessKeyId', 'SecretAccessKey', 'SessionToken', 'StandardOutputContent', 'StandardErrorContent',
    'nickname', 'email', 'phoneNumber', 'rawRows', 'payload', 'beforeRows', 'afterRows', 'requestBody', 'responseBody'}


def require_public(value):
    if isinstance(value, dict):
        need(not (set(value) & PUBLIC_BANNED_KEYS), 'PRIVATE_FIELD_IN_R4_PUBLIC_EXPORT')
        for key, item in value.items():
            need(isinstance(key, str), 'PUBLIC_JSON_KEY_REQUIRED'); require_public(item)
    elif isinstance(value, list):
        for item in value: require_public(item)
    else:
        need(value is None or type(value) in (str, int, float, bool), 'PUBLIC_JSON_VALUE_REQUIRED')
        if isinstance(value, str): need(not value.startswith('PRIVATE_') and not value.startswith('PRIVATE-'), 'PRIVATE_VALUE_IN_R4_PUBLIC_EXPORT')


def write_current(path, value):
    require_public(value); raw = encoded(value); path = Path(path)
    need(path.parent.resolve() == path.parent and (not path.exists() or (path.is_file() and not path.is_symlink())), 'OWNED_PUBLIC_OUTPUT_PATH_REQUIRED')
    temporary = path.with_name('.' + path.name + '-' + os.urandom(8).hex())
    core.write_new(temporary, raw); os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(descriptor)
    finally: os.close(descriptor)
    return {'path': str(path), 'sha256': sha(raw), 'bytes': len(raw)}


def shared_binding(configuration):
    return sha(encoded({k: v for k, v in configuration.items() if k not in ('lease', 'businessStartedEpoch', 'businessDeadlineEpoch')}))


def fix_cdc_configuration(live, path, journal, *, live_completed, host_api_intent, deadline, lease, clock=time.time, sleep=time.sleep, guard=lambda: None):
    """First window is fsynced once; a missing prior window is never replaced."""
    guard(); path = Path(path)
    if path.exists():
        current = core.parse(core.private_path(path).read_bytes())
        need(shared_binding(current) == shared_binding(live), 'SAVED_CDC_TARGET_OR_SOURCE_CHANGED')
        recorded = journal.last('CDC_CONFIGURATION_FIXED')
        need(recorded is None or recorded['sha256'] == sha(path.read_bytes()), 'SAVED_CDC_CONFIGURATION_CHANGED')
    else:
        need(not host_api_intent and journal.last('CDC_CONFIGURATION_FIXED') is None
             and not any(e['kind'] == 'CDC_CONTROLLER_INTENT' for e in journal.entries), 'MISSING_PRIOR_CDC_WINDOW_NO_REPLACEMENT')
        start = max(math.ceil(clock()), math.ceil(controller.timestamp_epoch(live_completed)))
        need(start + 900 <= deadline, 'FULL_FIRST_CDC_WINDOW_NOT_AVAILABLE')
        while clock() < start:
            guard(); sleep(min(1, start - clock()))
        guard()
        current = copy.deepcopy(live); current.update(lease=copy.deepcopy(lease), businessStartedEpoch=start, businessDeadlineEpoch=start + 900)
        cdc.validate_config(current, check_files=False); core.write_new(path, encoded(current))
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try: os.fsync(descriptor)
        finally: os.close(descriptor)
    need(controller.timestamp_epoch(live_completed) <= current['businessStartedEpoch']
         and current['businessDeadlineEpoch'] - current['businessStartedEpoch'] == 900, 'FIRST_CDC_WINDOW_ORDER_CHANGED')
    if journal.last('CDC_CONFIGURATION_FIXED') is None:
        journal.add('CDC_CONFIGURATION_FIXED', {'sha256': sha(path.read_bytes()), 'configurationSha256': cdc.journal_binding(current),
            'sharedBindingSha256': shared_binding(current), 'businessStartedEpoch': current['businessStartedEpoch'], 'businessDeadlineEpoch': current['businessDeadlineEpoch']})
    return current


INPUT_BODY = r'''try:
 root=guard(); area=new_directory(new_directory(root/'r4')/p['operationId']); inputs=new_directory(area/'inputs')
 need(p['slot'] in ('live-configuration.json','cdc-configuration.json','live-before.json','live-attestation.json','controller-close.json','close-attestation.json','live-job.json','finalize-job.json','cdc-receipt.json'))
 meta=p['file']; need(0<meta['bytes']<=2097152 and len(meta['sha256'])==64)
 chunks=new_directory(new_directory(area/'input-chunks')/meta['sha256'])
 create(chunks/'file.json',(json.dumps({'slot':p['slot'],'file':meta},sort_keys=True,separators=(',',':'))+'\n').encode())
 if p['action']=='chunk':
  raw=base64.b64decode(p['data'],validate=True); need(0<len(raw)<=6144 and digest(raw)==p['chunkSha256'])
  need(0<=p['index']<(meta['bytes']+6143)//6144 and len(raw)==min(6144,meta['bytes']-p['index']*6144))
  create(chunks/('%04d.chunk'%p['index']),raw)
  print(json.dumps({'passed':True,'index':p['index'],'sha256':digest(raw),'bytes':len(raw)}))
 else:
  need(p['action']=='install')
  raw=b''.join(private(chunks/('%04d.chunk'%i)).read_bytes() for i in range((meta['bytes']+6143)//6144))
  need(len(raw)==meta['bytes'] and digest(raw)==meta['sha256']); json.loads(raw)
  path=inputs/(meta['sha256']+'-'+p['slot']); create(path,raw)
  print(json.dumps({'passed':True,'reference':{'path':str(path),'sha256':meta['sha256'],'bytes':meta['bytes']}}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_PUBLIC_INPUT_STAGE_UNCONFIRMED'})); raise SystemExit(1)
'''

ATTEMPT_BODY = r'''try:
 root=guard(); folder=root/'cdc'/p['operationId']/'private-journal'; count=0; head=None; present=False
 if folder.exists():
  private(folder,True); files=sorted(folder.glob('[0-9][0-9][0-9][0-9].json')); need(len(files)<=192)
  for index,path in enumerate(files):
   raw=private(path).read_bytes(); need(len(raw)<=2097152); item=json.loads(raw)
   need(item['sequence']==index and item['previousSha256']==head); head=digest(raw)
   if item['kind'] in ('LOGIN_INTENT','STEP_1_INTENT','STEP_2_INTENT','AWS_HTTP_REQUEST_INTENT'): present=True
   count+=1
 print(json.dumps({'passed':True,'apiIntentPresent':present,'records':count,'journalHeadSha256':head}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_PRIOR_ATTEMPT_UNCONFIRMED'})); raise SystemExit(1)
'''

WORKER_BODY = r'''try:
 root=guard(); area=new_directory(new_directory(root/'r4')/p['operationId'])
 tools=private(root/'cdc-tools',True)
 for name,expected in p['sources'].items(): need(digest(private(tools/name).read_bytes())==expected)
 config_path=private(pathlib.Path(p['config']['path'])); need(config_path.parent==area/'inputs' and digest(config_path.read_bytes())==p['config']['sha256'])
 cfg=json.loads(config_path.read_bytes()); need(cfg['operation']==p['phase'] and cfg['deadlineEpoch']==p['deadlineEpoch'])
 need(p['phase'] in ('live','finalize') and p['attempt']==('live' if p['phase']=='live' else 'finalize-'+str(p['lease']['fencingToken'])))
 lock=os.open(area/'worker.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600); fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
 for old_intent in area.glob('*-worker-intent.json'):
  private(old_intent); old_state=area/old_intent.name.replace('-worker-intent.json','-worker.json')
  old=json.loads(private(old_state).read_bytes()); need(type(old['pid']) is int and old['pid']>1 and str(old['startTicks']).isdigit())
  process=pathlib.Path('/proc/%d/stat'%old['pid'])
  try: stat=process.read_text().rsplit(')',1)[1].split()
  except FileNotFoundError: stat=None
  need(stat is None or stat[19]!=str(old['startTicks']) or stat[0]=='Z')
 output=area/p['attempt']; need(not output.exists())
 state=area/(p['attempt']+'-worker.json'); need(not state.exists())
 intent=area/(p['attempt']+'-worker-intent.json'); need(not intent.exists())
 create(intent,(json.dumps({'phase':p['phase'],'deadlineEpoch':p['deadlineEpoch'],'configurationSha256':p['config']['sha256']},sort_keys=True,separators=(',',':'))+'\n').encode())
 env={k:v for k,v in os.environ.items() if not k.startswith('AWS_') and k not in ('PYTHONPATH','PYTHONHOME','PYTHONSTARTUP','PYTHONINSPECT','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','JAVA_OPTS','JDK_JAVAC_OPTIONS','CLASSPATH')}
 env.update(PATH=':'.join(str(root/v) for v in ('aws-bin','toolchain/python/bin','toolchain/jdk/bin','toolchain/mysql/bin'))+':'+os.environ['PATH'],JAVA_HOME=str(root/'toolchain/jdk'),PYTHONDONTWRITEBYTECODE='1',AWS_REGION='ap-northeast-2')
 remaining=int(p['deadlineEpoch']-time.time()); need(remaining>10)
 argv=['timeout','-s','TERM','-k','5',str(remaining),str(root/'toolchain/python/bin/python3'),str(tools/'infra/aws/scripts/growth_b_service_verify.py'),'--config',str(config_path),'--output',str(output)]
 child=subprocess.Popen(argv,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True,env=env,pass_fds=(lock,))
 start_ticks=pathlib.Path('/proc/%d/stat'%child.pid).read_text().rsplit(')',1)[1].split()[19]
 create(state,(json.dumps({'phase':p['phase'],'pid':child.pid,'startTicks':start_ticks,'deadlineEpoch':p['deadlineEpoch'],'configurationSha256':p['config']['sha256']},sort_keys=True,separators=(',',':'))+'\n').encode())
 code=child.wait(timeout=remaining+8)
 filename='live-service-receipt.json' if p['phase']=='live' else 'service-reset-receipt.json'
 path=private(output/filename); raw=path.read_bytes(); need(len(raw)<=2097152); report=json.loads(raw)
 print(json.dumps({'passed':code==0,'phase':p['phase'],'state':report.get('state'),'reference':{'path':str(path),'sha256':digest(raw),'bytes':len(raw)}}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_SERVICE_WORKER_UNCONFIRMED'})); raise SystemExit(1)
'''

READ_PUBLIC_BODY = r'''try:
 root=guard(); area=root/'r4'/p['operationId']; ref=p['reference']; path=pathlib.Path(ref['path'])
 final=area/('finalize-'+str(p['lease']['fencingToken']))
 need(path in (area/'live/live-service-receipt.json',final/'service-reset-receipt.json',final/'prepared-fingerprint.json',root/'execute/restore-receipt.json'))
 raw=private(path).read_bytes(); need(0<len(raw)<=2097152 and digest(raw)==ref['sha256'])
 if p['index'] is None:
  print(json.dumps({'passed':True,'reference':{'path':str(path),'sha256':digest(raw),'bytes':len(raw)}}))
 else:
  need(len(raw)==ref['bytes']); offset=p['index']*6144; need(0<=offset<len(raw)); chunk=raw[offset:offset+6144]
  print(json.dumps({'passed':True,'index':p['index'],'sha256':digest(chunk),'data':base64.b64encode(chunk).decode(),'bytes':len(chunk)}))
except BaseException:
 print(json.dumps({'passed':False,'failureCode':'R4_PUBLIC_RECEIPT_READ_UNCONFIRMED'})); raise SystemExit(1)
'''


PUBLIC_FILES = {'live-service-receipt.json', 'live-attestation.json', 'cdc-post-reset.json', 'controller-close.json',
                'close-attestation.json', 'service-verified-and-reset.json', 'prepared-fingerprint.json', 'restore-receipt.json'}
RECOVERY_KIND = 'global-b-aws-source-r4-recovery'
SUPERVISOR_KIND = 'global-b-aws-source-r4-supervisor-receipt'


def local(reference):
    return {key: reference[key] for key in ('path', 'sha256')}


def exact_write(path, raw):
    path = Path(path)
    if path.exists(): need(core.private_path(path).read_bytes() == raw, 'OWNED_IMMUTABLE_FILE_CHANGED')
    else: core.write_new(path, raw)
    return {'path': str(path), 'sha256': sha(raw), 'bytes': len(raw)}


def validate_chain(entries, expected_head, *, configuration_sha, tool_sha):
    need(isinstance(entries, list) and 1 <= len(entries) <= 1024, 'RECOVERY_JOURNAL_REQUIRED')
    previous = None
    for index, item in enumerate(entries):
        cdc.fields(item, 'sequence previousSha256 kind utc monotonicNs data', 'RECOVERY_RECORD_FIELDS_DIFFER')
        need(item['sequence'] == index and item['previousSha256'] == previous and cdc.match(r'[A-Z][A-Z0-9_]{1,63}', item['kind'])
             and core.integer(item['monotonicNs']) and isinstance(item['data'], dict), 'RECOVERY_JOURNAL_CHAIN_CHANGED')
        require_public(item); previous = sha(encoded(item))
    need(previous == expected_head and entries[0]['kind'] == 'CREATED'
         and entries[0]['data']['configSha256'] == configuration_sha and entries[0]['data']['toolSha256'] == tool_sha,
         'RECOVERY_JOURNAL_BINDING_CHANGED')
    return entries


def validate_recovery(value, operation, *, sources=None):
    cdc.fields(value, 'schemaVersion kind originalOperation originalOperationSha256 originalContext sourceArchiveSha256 '
        'supervisorJournal supervisorJournalHeadSha256 controllerJournal controllerJournalHeadSha256 '
        'liveConfiguration liveConfigurationSha256 cdcConfiguration cdcConfigurationSha256 publicArtifacts', 'RECOVERY_FIELDS_DIFFER')
    need(value['schemaVersion'] == 1 and value['kind'] == RECOVERY_KIND and operation['stage'] == 'resume', 'BOUND_RECOVERY_REQUIRED')
    original = validate_operation(value['originalOperation']); resume = operation['resume']
    validate_context(value['originalContext'], original, now=lambda: value['originalContext']['controllerDeadlineEpoch'] - 1)
    need(original['stage'] == 'all' and operation_binding(original) == operation_binding(operation)
         and sha(encoded(original)) == value['originalOperationSha256'] == resume['originalOperationSha256']
         and value['sourceArchiveSha256'] == operation['sourceArchiveSha256'], 'RECOVERY_ORIGINAL_OPERATION_CHANGED')
    tool_sha = sha(encoded(source_files() if sources is None else sources))
    for key in ('supervisorJournalHeadSha256', 'controllerJournalHeadSha256', 'liveConfigurationSha256', 'cdcConfigurationSha256'):
        need(value[key] == resume[key], 'RECOVERY_SELECTED_HEAD_CHANGED')
    validate_chain(value['supervisorJournal'], value['supervisorJournalHeadSha256'], configuration_sha=operation_binding(original), tool_sha=tool_sha)
    for key in ('liveConfiguration', 'cdcConfiguration'):
        config = value[key]
        need((config is None) == (value[key + 'Sha256'] is None), 'RECOVERY_CONFIGURATION_MISSING')
        if config is not None:
            cdc.validate_config(config, check_files=False)
            need(sha(encoded(config)) == value[key + 'Sha256'] and config['operationId'] == original['operationId']
                 and config['executionCommit'] == original['executionCommit'] and config['datasetId'] == original['datasetId']
                 and {k: v for k, v in config['serviceManifest'].items() if k != 'path'} == original['manifest']
                 and {k: v for k, v in config['serviceReadiness'].items() if k != 'path'} == original['readiness'], 'RECOVERY_CONFIGURATION_CHANGED')
    if value['cdcConfiguration'] is not None:
        need(value['liveConfiguration'] is not None and shared_binding(value['liveConfiguration']) == shared_binding(value['cdcConfiguration']), 'RECOVERY_SHARED_TARGET_CHANGED')
    if value['controllerJournal'] is None: need(value['controllerJournalHeadSha256'] is None, 'RECOVERY_CONTROLLER_MISSING')
    else:
        need(value['cdcConfiguration'] is not None, 'RECOVERY_CONTROLLER_CONFIGURATION_MISSING')
        validate_chain(value['controllerJournal'], value['controllerJournalHeadSha256'],
            configuration_sha=cdc.journal_binding(value['cdcConfiguration']), tool_sha=sha(encoded(cdc.source_identity())))
    artifacts = value['publicArtifacts']; need(isinstance(artifacts, dict) and set(artifacts) <= PUBLIC_FILES, 'RECOVERY_PUBLIC_FILES_CHANGED')
    for name, reference in artifacts.items():
        cdc.fields(reference, 'sha256 bytes base64', 'RECOVERY_PUBLIC_REFERENCE_FIELDS_DIFFER')
        raw = base64.b64decode(reference['base64'], validate=True)
        need(len(raw) == reference['bytes'] and len(raw) <= 2 * 1024**2 and sha(raw) == reference['sha256'], 'RECOVERY_PUBLIC_BYTES_CHANGED')
        require_public(core.parse(raw))
    need(len(encoded(value)) <= 2 * 1024**2, 'RECOVERY_EXPORT_BUDGET_EXCEEDED')
    return value


def import_recovery(value, directory):
    """Call only after validate_recovery; reconstitute closed runner metadata."""
    directory = Path(directory)
    for name, entries in (('supervisor-journal', value['supervisorJournal']), ('controller-journal', value['controllerJournal'])):
        if entries is None: continue
        path = directory / name
        if not path.exists(): path.mkdir(mode=0o700)
        core.private_path(path, directory=True)
        need(not list(path.glob('[0-9]*.json')), 'RECOVERY_REQUIRES_NEW_LOCAL_JOURNAL')
        for index, item in enumerate(entries): core.write_new(path / f'{index:04d}.json', encoded(item))
    for key, name in (('liveConfiguration', 'live-configuration.json'), ('cdcConfiguration', 'cdc-configuration.json')):
        if value[key] is not None: exact_write(directory / name, encoded(value[key]))
    for name, reference in value['publicArtifacts'].items():
        exact_write(directory / 'public' / name, base64.b64decode(reference['base64'], validate=True))


def final_gate(report, fingerprint, restored, configuration, manifest, readiness):
    """A controller's stopped state alone is never a completed source gate."""
    import growth_b_snapshot as snapshot
    import growth_b_contract as contract
    import growth_b_service_verify as verify
    require_public(report); require_public(fingerprint); require_public(restored)
    expected_target = {key: configuration['rds'][key] for key in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}
    need(report.get('schemaVersion') == 1 and report.get('kind') == verify.RESET_KIND and report.get('state') == 'SERVICE_VERIFIED_AND_RESET'
         and report.get('datasetId') == configuration['datasetId'] and report.get('mysql') == snapshot.MYSQL
         and report.get('targetIdentity') == expected_target and report.get('application') == manifest['application']
         and all(report.get(key) is True for key in ('writersStopped', 'cdcStopped', 'sourceOriginalsUnchanged'))
         and all(report.get(key) is False for key in ('snapshotCreated', 'privateValuesIncluded', 'databaseBusinessWritesPerformed')), 'ACTUAL_FINAL_SERVICE_RESET_REQUIRED')
    checks = report.get('service', {})
    need(all(checks.get(key) is True for key in ('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed', 'globalSearchPassed',
        'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed', 'detailCacheDisabledVerified'))
        and checks.get('representativeAccounts') == 3, 'ALL_SOURCE_R4_SERVICE_CHECKS_REQUIRED')
    reset = report.get('reset', {})
    need(all(reset.get(key) is True for key in ('passed', 'testMutationRemoved', 'unchangedDomainAndDdl'))
         and reset.get('remainingOutboxRows') == 0 and cdc.match(cdc.HASH, reset.get('ownerSha256BeforeAndAfter')), 'FULL_SOURCE_RESET_REQUIRED')
    binding = report['binding']
    need(binding['configurationSha256'] == cdc.journal_binding(configuration)
         and binding['sharedBindingSha256'] == shared_binding(configuration)
         and binding['toolIdentity'] == verify.source_identity() and binding['targetIdentity'] == expected_target
         and binding['serviceManifest'] == {k: v for k, v in configuration['serviceManifest'].items() if k != 'path'}
         and binding['serviceReadiness'] == {k: v for k, v in configuration['serviceReadiness'].items() if k != 'path'}
         and binding['restoreConfigSha256'] == manifest['preparation']['restoreConfigSha256']
         and report['restoreReceiptSha256'] == binding['restoreReceiptSha256'] == manifest['preparation']['restoreReceiptSha256'], 'FINAL_SOURCE_RUNTIME_LINEAGE_CHANGED')
    need(restored.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED' and restored.get('datasetId') == configuration['datasetId']
         and restored.get('targetIdentity') == expected_target and restored.get('toolIdentity') == restore.tool_identity()
         and restored.get('allRowsAndDdlEqual') is True and restored.get('mysqlVersion') == '8.4.11', 'ORIGINAL_SUCCESSFUL_RESTORE_REQUIRED')
    need(reset['ownerSha256BeforeAndAfter'] == restored['preparation']['ownerSha256BeforeAndAfter'], 'ORIGINAL_OWNER_INVENTORY_CHANGED')
    contract.validate_fingerprint(fingerprint, require_sealed=False)
    restore.validate_prepared_changes(restored['sealedFingerprint'], fingerprint)
    need(fingerprint['tables']['outbox']['rows'] == 0, 'FINAL_OUTBOX_NOT_EMPTY')
    return report


class Supervisor:
    def __init__(self, operation, context, directory, *, aws=None, recovery=None, clock=time.time, sleep=time.sleep, transport_factory=Ssm, controller_factory=controller.Controller):
        self.operation = validate_operation(operation); self.context = validate_context(context, operation, now=clock)
        self.directory, self.clock, self.sleep = Path(directory).absolute(), clock, sleep
        need(self.directory.parent.resolve() == self.directory.parent, 'OWNED_R4_DIRECTORY_REQUIRED')
        if not self.directory.exists(): self.directory.mkdir(mode=0o700)
        core.private_path(self.directory, directory=True)
        self.public = self.directory / 'public'
        if not self.public.exists(): self.public.mkdir(mode=0o700)
        core.private_path(self.public, directory=True)
        self.archive, self.archive_meta = source_archive(); self.sources = source_files()
        self.original_operation, self.original_context = operation, copy.deepcopy(context)
        self.live = self.config = self.controller_journal = None
        self.journal = None; self.transport_factory, self.controller_factory = transport_factory, controller_factory
        self.original_configuration = None
        self.aws = aws or BoundedAws(context['controllerDeadlineEpoch'], clock=clock)
        if operation['stage'] == 'resume':
            need(recovery is not None, 'EXACT_PUBLIC_RECOVERY_REQUIRED')
            validate_recovery(recovery, operation, sources=self.sources)
            self.original_operation, self.original_context = recovery['originalOperation'], recovery['originalContext']
            previous = self.original_context
            need(context['executionCommit'] == previous['executionCommit'] and context['operator'] == previous['operator']
                 and context['approvedExecutionDeadlineEpoch'] == previous['approvedExecutionDeadlineEpoch']
                 and all(context[k] == previous[k] for k in ('phase2', 'phase3', 'phase4', 'serviceState')), 'RESUME_ORIGINAL_CONTEXT_OR_DEADLINE_CHANGED')
            import_recovery(recovery, self.directory)
        else:
            need(recovery is None and not (self.directory / 'supervisor-journal').exists(), 'NEW_OPERATION_DIRECTORY_REQUIRED')
        self.journal = RecoveryJournal(self.directory / 'supervisor-journal', operation_binding(operation), sha(encoded(self.sources)))
        self.builder = Builder(operation, context, self.directory, self.journal, aws=self.aws, clock=clock, sleep=sleep)
        self.transport = transport_factory(self.aws, self.journal, self.guard, clock=clock, sleep=sleep)
        self.transport.common_deadline = context['controllerDeadlineEpoch']; self.builder.transport = self.transport
        self.deadline = min(context['controllerDeadlineEpoch'], self.original_context['controllerDeadlineEpoch'])
        self.builder.deadline = self.deadline; self.transport.common_deadline = self.deadline
        if isinstance(self.aws, BoundedAws): self.aws.deadline = self.deadline
        self.controller_transport = None
        for name, attr in (('live-configuration.json', 'live'), ('cdc-configuration.json', 'original_configuration')):
            path = self.directory / name
            if path.exists(): setattr(self, attr, core.parse(core.private_path(path).read_bytes()))
        if (self.directory / 'controller-journal').exists():
            need(self.original_configuration is not None, 'RECOVERY_CONTROLLER_CONFIGURATION_MISSING')
            self.controller_journal = RecoveryJournal(self.directory / 'controller-journal',
                cdc.journal_binding(self.original_configuration), sha(encoded(cdc.source_identity())))
            self.controller_journal.checkpoint = self.checkpoint
        self.journal.checkpoint = self.checkpoint
        self.checkpoint()

    def guard(self):
        self.builder.guard()

    def checkpoint(self):
        if self.journal is None: return
        artifacts = {}
        for name in sorted(PUBLIC_FILES):
            path = self.public / name
            if path.exists():
                raw = core.private_path(path).read_bytes(); require_public(core.parse(raw))
                artifacts[name] = {'sha256': sha(raw), 'bytes': len(raw), 'base64': base64.b64encode(raw).decode()}
        live_path, cdc_path = self.directory / 'live-configuration.json', self.directory / 'cdc-configuration.json'
        def config(path): return core.parse(core.private_path(path).read_bytes()) if path.exists() else None
        live, current = config(live_path), config(cdc_path)
        journal = self.controller_journal
        value = {'schemaVersion': 1, 'kind': RECOVERY_KIND, 'originalOperation': self.original_operation,
            'originalOperationSha256': sha(encoded(self.original_operation)), 'originalContext': self.original_context,
            'sourceArchiveSha256': self.operation['sourceArchiveSha256'], 'supervisorJournal': self.journal.entries,
            'supervisorJournalHeadSha256': self.journal.last_sha, 'controllerJournal': journal.entries if journal else None,
            'controllerJournalHeadSha256': journal.last_sha if journal else None, 'liveConfiguration': live,
            'liveConfigurationSha256': sha(live_path.read_bytes()) if live is not None else None, 'cdcConfiguration': current,
            'cdcConfigurationSha256': sha(cdc_path.read_bytes()) if current is not None else None, 'publicArtifacts': artifacts}
        need(len(encoded(value)) <= 2 * 1024**2, 'RECOVERY_EXPORT_BUDGET_EXCEEDED')
        write_current(self.public / 'recovery.json', value)

    def exported(self, name, raw):
        need(name in PUBLIC_FILES, 'CLOSED_PUBLIC_BASENAME_REQUIRED'); require_public(core.parse(raw))
        path = self.public / name
        if path.exists() and core.private_path(path).read_bytes() != raw:
            previous = path.read_bytes(); history = self.public / 'history'
            if not history.exists(): history.mkdir(mode=0o700)
            core.private_path(history, directory=True)
            exact_write(history / (sha(previous) + '-' + name), previous)
            temporary = self.public / ('.replace-' + os.urandom(8).hex())
            core.write_new(temporary, raw); os.replace(temporary, path)
            fd = os.open(self.public, os.O_RDONLY | os.O_DIRECTORY)
            try: os.fsync(fd)
            finally: os.close(fd)
        reference = exact_write(path, raw); self.checkpoint(); return reference

    def once(self, key, name, pin, text, *, seconds=90):
        """Recover a known command by ID. An intent without an ID blocks replay."""
        self.guard()
        complete = next((e['data'] for e in reversed(self.journal.entries) if e['kind'] == 'COMMAND_COMPLETE' and e['data']['key'] == key), None)
        if complete: return complete['value'], complete['command']
        intent = next((e for e in reversed(self.journal.entries) if e['kind'] == 'COMMAND_OPERATION_INTENT' and e['data']['key'] == key), None)
        if intent:
            data = intent['data']
            need(data['name'] == name and data['instanceId'] == pin['instanceId'] and data['containerId'] == pin['containerId'], 'RECOVERY_COMMAND_TARGET_CHANGED')
            submitted = [e['data'] for e in self.journal.entries if e['sequence'] > intent['sequence'] and e['kind'] == 'SSM_SUBMITTED'
                and e['data']['name'] == name and e['data']['commandSha256'] == data['commandSha256']]
            need(len(submitted) == 1, 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY')
            item = submitted[0]
            terminal = next((e['data'] for e in reversed(self.journal.entries) if e['kind'] == 'SSM_TERMINAL' and e['data']['commandId'] == item['commandId']), None)
            if terminal is None: value, terminal = self.transport.wait(item, self.deadline)
            else:
                need(terminal['status'] == 'Success', 'PRIOR_SSM_COMMAND_UNCONFIRMED')
                response = self.aws.call('ssm', 'get-command-invocation', '--command-id', item['commandId'], '--instance-id', item['instanceId'])
                need(response.get('CommandId') == item['commandId'] and response.get('InstanceId') == item['instanceId']
                     and response.get('Status') == 'Success' and response.get('ResponseCode') == 0, 'RECOVERED_COMMAND_IDENTITY_CHANGED')
                raw = response.get('StandardOutputContent', '').encode(); need(len(raw) <= MAX_OUTPUT_CHARS, 'R4_SSM_SAFE_OUTPUT_BUDGET')
                value = core.parse(raw)
        else:
            self.journal.add('COMMAND_OPERATION_INTENT', {'key': key, 'name': name, 'instanceId': pin['instanceId'],
                'containerId': pin['containerId'], 'commandSha256': sha(text.encode())})
            value, terminal = self.transport.run(name, pin['instanceId'], pin['containerId'], text, self.deadline, seconds=seconds)
        require_public(value); need(value.get('passed') is True, 'R4_ONCE_COMMAND_UNCONFIRMED')
        # Every caller uses a fixed source program returning only public metadata.
        self.journal.add('COMMAND_COMPLETE', {'key': key, 'value': value, 'command': terminal})
        return value, terminal

    def host_payload(self, configuration=None, *, deadline=None, closed=None):
        value = configuration or self.config or self.live
        pin = dict(value['hosts']['connect'])
        payload = {'runId': value['runId'], 'operationId': value['operationId'], 'lease': self.context['lease'],
            'deadlineEpoch': self.deadline if deadline is None else deadline, 'expiresAt': value['expiresAt'], 'pin': pin}
        if closed:
            pin['startedAt'] = closed['observation']['startedAt']
            payload.update(connectRunning=False, connectFinishedAt=closed['observation']['finishedAt'])
        elif self.controller_journal is not None:
            journal = self.controller_journal
            restarted = journal.last('CONNECT_RESTART_CONFIRMED')
            if restarted: pin['startedAt'] = restarted['observation']['startedAt']
            stopped = max((e for e in journal.entries if e['kind'] in ('FROZEN_OBSERVATION', 'SOURCE_CLOSED')),
                          key=lambda e: e['sequence'], default=None)
            if stopped and stopped['sequence'] > journal.latest_sequence('CONNECT_START_INTENT'):
                payload.update(connectRunning=False, connectFinishedAt=stopped['data']['connectFinishedAt'])
            elif journal.latest_sequence('CONNECT_START_INTENT') > journal.latest_sequence('CONNECT_RESTART_CONFIRMED'):
                raise Failed('CONNECT_LIFETIME_UNCONFIRMED_NO_HOST_ADOPTION')
        return payload

    def install_sources(self):
        p = self.host_payload(self.live); pin = p['pin']
        for index, offset in enumerate(range(0, len(self.archive), CHUNK_BYTES)):
            result, _ = self.once('source-chunk-' + str(index), f'source-chunk-{index:03d}', pin,
                stage_program(p, self.archive_meta, index=index, data=self.archive[offset:offset + CHUNK_BYTES]))
            need(result == {'passed': True, 'index': index, 'sha256': sha(self.archive[offset:offset + CHUNK_BYTES]),
                 'bytes': len(self.archive[offset:offset + CHUNK_BYTES])}, 'SOURCE_CHUNK_ACK_CHANGED')
        result, _ = self.once('source-install', 'source-install', pin, stage_program(p, self.archive_meta))
        need(result == {'passed': True, 'archiveSha256': self.archive_meta['sha256'], 'files': len(self.archive_meta['files']),
             'expandedBytes': self.archive_meta['expandedBytes']}, 'SOURCE_INSTALL_ACK_CHANGED')

    def stage_input(self, slot, value, *, closed=None):
        require_public(value); raw = encoded(value); digest = sha(raw); p = self.host_payload(closed=closed)
        p.update(slot=slot, file={'sha256': digest, 'bytes': len(raw)})
        for index, offset in enumerate(range(0, len(raw), CHUNK_BYTES)):
            chunk = raw[offset:offset + CHUNK_BYTES]
            payload = p | {'action': 'chunk', 'index': index, 'data': base64.b64encode(chunk).decode(), 'chunkSha256': sha(chunk)}
            result, _ = self.once('input-' + slot + '-' + digest + '-' + str(index), 'public-input-chunk', p['pin'], program(INPUT_BODY, payload))
            need(result == {'passed': True, 'index': index, 'sha256': sha(chunk), 'bytes': len(chunk)}, 'PUBLIC_INPUT_CHUNK_ACK_CHANGED')
        result, _ = self.once('input-' + slot + '-' + digest + '-install', 'public-input-install', p['pin'], program(INPUT_BODY, p | {'action': 'install'}))
        expected = {'path': '/opt/airbob/global-b/' + p['runId'] + '/r4/' + p['operationId'] + '/inputs/' + digest + '-' + slot,
                    'sha256': digest, 'bytes': len(raw)}
        need(result == {'passed': True, 'reference': expected}, 'PUBLIC_INPUT_INSTALL_ACK_CHANGED')
        return local(expected)

    def download(self, reference, name, *, closed=None):
        p = self.host_payload(closed=closed); p['reference'] = reference
        if 'bytes' not in reference:
            result, _ = self.transport.run('public-file-metadata', p['pin']['instanceId'], p['pin']['containerId'], program(READ_PUBLIC_BODY, p | {'index': None}), self.deadline)
            need(result.get('passed') is True and all(result['reference'][k] == reference[k] for k in reference), 'PUBLIC_FILE_REFERENCE_CHANGED')
            reference = result['reference']; p['reference'] = reference
        need(core.integer(reference['bytes'], 1) and reference['bytes'] <= 2 * 1024**2, 'PUBLIC_FILE_SIZE_BUDGET')
        chunks = []
        for index, offset in enumerate(range(0, reference['bytes'], CHUNK_BYTES)):
            value, _ = self.transport.run('public-file-read', p['pin']['instanceId'], p['pin']['containerId'], program(READ_PUBLIC_BODY, p | {'index': index}), self.deadline)
            cdc.fields(value, 'passed index sha256 data bytes', 'PUBLIC_FILE_CHUNK_FIELDS_DIFFER')
            chunk = base64.b64decode(value['data'], validate=True)
            need(value['passed'] is True and value['index'] == index and sha(chunk) == value['sha256']
                 and len(chunk) == value['bytes'] == min(CHUNK_BYTES, reference['bytes'] - offset), 'PUBLIC_FILE_CHUNK_CHANGED')
            chunks.append(chunk)
        raw = b''.join(chunks); need(sha(raw) == reference['sha256'], 'PUBLIC_FILE_SHA_CHANGED')
        self.exported(name, raw)
        return core.parse(raw)

    def observe_app(self, name, *, configuration=None):
        import growth_b_service_verify as verify
        configuration = configuration or self.live
        self.guard(); group, _, instances, _ = self.builder.topology()
        pin = configuration['hosts']['app']
        need(group['AutoScalingGroupARN'] == configuration['asg']['arn'] and group['LaunchTemplate'] == configuration['asg']['launchTemplate']
             and instances['app']['InstanceId'] == pin['instanceId'] and instances['app']['PrivateIpAddress'] == pin['privateIp'], 'LIVE_APP_TARGET_CHANGED')
        result, command = self.transport.run(name, pin['instanceId'], pin['containerId'], app_program(configuration, self.builder.readiness), self.deadline)
        need(result.get('passed') is True, 'LIVE_APP_OBSERVATION_UNCONFIRMED')
        observation = {'schemaVersion': 1, 'kind': 'global-b-aws-service-app-observation',
            'configurationSha256': cdc.journal_binding(configuration), 'operationId': self.operation['operationId'],
            'lease': configuration['lease'], 'supervisorSha256': sha(Path(__file__).read_bytes()),
            'command': {key: command[key] for key in COMMAND_FIELDS.split()}, 'observation': result['observation']}
        verify.validate_app_observation(observation, configuration, self.builder.readiness, fresh_at=self.clock())
        return observation

    def producer_config(self, phase, config_ref, observation_ref, deadline, **refs):
        import growth_b_service_verify as verify
        root = '/opt/airbob/global-b/' + self.operation['runId']; prep = self.builder.manifest['preparation']
        value = {'schemaVersion': 1, 'kind': verify.KIND, 'operation': phase, 'cdcConfiguration': local(config_ref),
            'restoreConfig': {'path': root + '/restore-config.json', 'sha256': prep['restoreConfigSha256']},
            'restoreReceipt': {'path': root + '/execute/restore-receipt.json', 'sha256': prep['restoreReceiptSha256']},
            'controllerObservation': local(observation_ref), 'deadlineEpoch': deadline} | {k: local(v) for k, v in refs.items()}
        verify.validate_config(value, check_files=False); return value

    def worker(self, phase, job, *, closed=None):
        attempt = phase if phase == 'live' else 'finalize-' + str(self.context['lease']['fencingToken'])
        saved = next((e['data'] for e in reversed(self.journal.entries) if e['kind'] == 'SERVICE_JOB_FIXED' and e['data']['attempt'] == attempt), None)
        if saved:
            need(job is None or saved['job'] == job, 'ORIGINAL_SERVICE_JOB_CHANGED')
            job, reference, payload, seconds = (saved[k] for k in ('job', 'reference', 'hostPayload', 'seconds'))
        else:
            need(job is not None, 'ACTUAL_SERVICE_JOB_REQUIRED')
            reference = self.stage_input(phase + '-job.json', job, closed=closed)
            payload = self.host_payload(deadline=job['deadlineEpoch'], closed=closed) | {'phase': phase, 'attempt': attempt, 'config': reference, 'sources': self.sources}
            seconds = int(job['deadlineEpoch'] - self.clock()) + 15
            need(seconds > 25, 'SERVICE_WORKER_DEADLINE_REACHED')
            self.journal.add('SERVICE_JOB_FIXED', {'phase': phase, 'attempt': attempt, 'job': job, 'reference': reference, 'hostPayload': payload, 'seconds': seconds})
        result, command = self.once('service-worker-' + attempt, 'service-worker-' + phase, payload['pin'], program(WORKER_BODY, payload), seconds=seconds)
        need(result.get('phase') == phase and result.get('state') == ('LIVE_READS_OBSERVED' if phase == 'live' else 'SERVICE_VERIFIED_AND_RESET'), 'FULL_SERVICE_WORKER_PROOF_REQUIRED')
        reference = result['reference']
        expected = '/opt/airbob/global-b/' + self.operation['runId'] + '/r4/' + self.operation['operationId'] + '/' + attempt + '/' + (
            'live-service-receipt.json' if phase == 'live' else 'service-reset-receipt.json')
        need(reference['path'] == expected and cdc.match(cdc.HASH, reference['sha256']), 'EXACT_SERVICE_WORKER_RECEIPT_REQUIRED')
        return reference, command

    def live_phase(self):
        if self.live is None:
            discovered = self.builder.discover(); retained = self.builder.retained(discovered)
            self.live = self.builder.configuration(discovered, retained)
            exact_write(self.directory / 'live-configuration.json', encoded(self.live))
            self.journal.add('LIVE_CONFIGURATION_FIXED', {'sha256': sha(encoded(self.live)), 'sharedBindingSha256': shared_binding(self.live)})
        else: self.builder.selected_inputs()
        self.install_sources()
        live_ref = self.stage_input('live-configuration.json', self.live)
        result_path = self.public / 'live-service-receipt.json'
        if result_path.exists():
            result = core.parse(core.private_path(result_path).read_bytes())
            completed = self.journal.last('LIVE_SERVICE_OBSERVED')
            if completed is not None:
                need(completed['receiptSha256'] == sha(result_path.read_bytes()), 'SAVED_LIVE_RECEIPT_UNBOUND')
                return result, completed['reference'], live_ref
        job_saved = any(e['kind'] == 'SERVICE_JOB_FIXED' and e['data']['phase'] == 'live' for e in self.journal.entries)
        if not job_saved:
            # A renewed lease may be used only before this first live worker.
            need(self.live['lease'] == self.context['lease'], 'LIVE_WORKER_ORIGINAL_LEASE_CHANGED')
            before = self.observe_app('live-app-before'); self.journal.add('LIVE_APP_BEFORE', before)
            before_ref = self.stage_input('live-before.json', before)
            deadline = min(int(self.clock()) + 1800, self.deadline - 90)
            job = self.producer_config('live', live_ref, before_ref, deadline)
        else: job = None
        reference, command = self.worker('live', job)
        result = self.download(reference, 'live-service-receipt.json')
        need(result.get('binding', {}).get('configurationSha256') == cdc.journal_binding(self.live)
             and result['binding']['sharedBindingSha256'] == shared_binding(self.live)
             and result.get('sourceSnapshotGateSatisfied') is False and result.get('privateValuesIncluded') is False, 'LIVE_WORKER_BINDING_CHANGED')
        self.journal.add('LIVE_SERVICE_OBSERVED', {'receiptSha256': reference['sha256'], 'reference': reference, 'command': command})
        return result, reference, live_ref

    def open_controller(self):
        need(self.original_configuration is not None, 'ORIGINAL_CDC_CONFIGURATION_REQUIRED')
        self.config = copy.deepcopy(self.original_configuration); self.config['lease'] = copy.deepcopy(self.context['lease'])
        cdc.validate_config(self.config, check_files=False)
        need(shared_binding(self.config) == shared_binding(self.live), 'EFFECTIVE_CDC_TARGET_CHANGED')
        if self.controller_journal is None:
            self.controller_journal = RecoveryJournal(self.directory / 'controller-journal', cdc.journal_binding(self.config), sha(encoded(cdc.source_identity())))
            self.controller_journal.checkpoint = self.checkpoint
        self.controller_transport = self.transport_factory(self.aws, self.controller_journal, self.guard, clock=self.clock, sleep=self.sleep)
        self.controller_transport.common_deadline = self.deadline
        current = self.controller_factory(self.config, self.builder.manifest, self.builder.readiness, self.context['phase4'], self.controller_journal,
            service_state=self.context['serviceState'], aws=self.aws, clock=self.clock, sleep=self.sleep, transport=self.controller_transport)
        current.deadline = min(current.deadline, self.deadline)
        def control_guard():
            self.guard(); current.guard(force=True)
        self.controller_transport.guard = control_guard
        return current

    def action(self, name):
        current = self.open_controller(); self.guard()
        self.journal.add('CDC_CONTROLLER_INTENT', {'action': name, 'configurationSha256': cdc.journal_binding(self.config),
            'effectiveConfigurationSha256': sha(encoded(self.config)), 'lease': self.context['lease']})
        getattr(current, name.replace('-', '_'))()
        report = current.public(name, True)
        require_public(report); need(report['phasePassed'] is True and report['outstandingCommands'] == [], 'CONTROLLER_PHASE_NOT_COMPLETE')
        self.journal.add('CDC_CONTROLLER_COMPLETE', {'action': name, 'receipt': report})
        return current, report

    def cdc_phase(self, live_result, live_ref):
        if self.original_configuration is None:
            after, before = self.journal.last('LIVE_APP_AFTER'), self.journal.last('LIVE_APP_BEFORE')
            if after is None:
                need(self.live['lease'] == self.context['lease'], 'UNATTESTED_LIVE_WINDOW_LEASE_CHANGED')
                after = self.observe_app('live-app-after'); self.journal.add('LIVE_APP_AFTER', after)
            need(before is not None and after['observation'] == before['observation'], 'LIVE_APP_CHANGED_DURING_READS')
            admission_config = copy.deepcopy(self.live); admission_config['lease'] = copy.deepcopy(self.context['lease'])
            admitted = self.observe_app('cdc-app-admission', configuration=admission_config)
            need(admitted['observation'] == after['observation'], 'APP_CHANGED_BEFORE_FIRST_CDC_WINDOW')
            self.journal.add('CDC_APP_ADMISSION', admitted)
            payload = self.host_payload(self.live)
            prior, command = self.transport.run('prior-api-intent', payload['pin']['instanceId'], payload['pin']['containerId'], program(ATTEMPT_BODY, payload), self.deadline)
            cdc.fields(prior, 'passed apiIntentPresent records journalHeadSha256', 'PRIOR_API_OBSERVATION_FIELDS_DIFFER')
            need(prior['passed'] is True and type(prior['apiIntentPresent']) is bool, 'PRIOR_API_INTENT_UNCONFIRMED')
            self.journal.add('PRIOR_API_ATTEMPT_OBSERVED', {'observation': prior, 'command': command})
            self.original_configuration = fix_cdc_configuration(self.live, self.directory / 'cdc-configuration.json', self.journal,
                live_completed=live_result['completedAt'], host_api_intent=prior['apiIntentPresent'], deadline=self.deadline,
                lease=self.context['lease'], clock=self.clock, sleep=self.sleep, guard=self.guard)
        self.config = copy.deepcopy(self.original_configuration); self.config['lease'] = copy.deepcopy(self.context['lease'])
        cdc_ref = self.stage_input('cdc-configuration.json', self.config)
        before, after = self.journal.last('LIVE_APP_BEFORE'), self.journal.last('LIVE_APP_AFTER')
        need(before and after and before['observation'] == after['observation'], 'ORIGINAL_LIVE_APP_ATTESTATION_REQUIRED')
        attestation = {'schemaVersion': 1, 'kind': 'global-b-aws-service-live-attestation', 'operationId': self.operation['operationId'],
            'supervisorSha256': sha(Path(__file__).read_bytes()), 'liveConfiguration': live_ref, 'cdcConfiguration': cdc_ref,
            'liveConfigurationSha256': cdc.journal_binding(self.live), 'cdcConfigurationSha256': cdc.journal_binding(self.config),
            'sharedBindingSha256': shared_binding(self.config), 'liveReceiptSha256': sha((self.public / 'live-service-receipt.json').read_bytes()), 'before': before, 'after': after}
        # On resume only the current-lease ref changes; the old exact attestations and business window remain fixed.
        self.attestation_ref = self.stage_input('live-attestation.json', attestation)
        self.current_attestation = attestation
        self.exported('live-attestation.json', encoded(attestation))
        self.current_cdc_ref = cdc_ref
        current = self.open_controller(); journal = self.controller_journal
        if journal.last('CONTROLLER_VERIFY_COMPLETE') is None:
            if journal.last('ASG_ZERO_INTENT') is not None:
                # The private core may reset only a proven two-API revert with
                # completed propagation and logout. Missing app attestation
                # can never be recreated after the original instance exited.
                self.action('reset')
                self.journal.add('UNQUALIFIED_CYCLE_RESET', {'ownedResetConfirmed': True, 'sourceSnapshotGateSatisfied': False,
                    'originalRuntimeAttestationMissing': True, 'automaticRestart': False})
                raise Failed('OWNED_RESET_WITHOUT_COMPLETE_RUNTIME_ATTESTATION')
            need(self.clock() < self.config['businessDeadlineEpoch'], 'ORIGINAL_CDC_WINDOW_EXPIRED')
            current, _ = self.action('verify')
        if journal.last('HOST_RESET') is None or not journal.last('HOST_RESET')['report']['phasePassed']:
            current, _ = self.action('reset')
        healthy = current.public('post-reset', False)['postResetHealthConfirmed']
        if journal.last('SOURCE_CLOSED') is None or not healthy:
            if journal.last('NEW_APP_RUNTIME_BOUND') is None or journal.last('SOURCE_CLOSED') is not None: current, _ = self.action('restart')
            if not current.public('post-reset', False)['postResetHealthConfirmed']: current, _ = self.action('post-reset')
        # An actual fresh close observation is needed even after a previously successful close.
        current, report = self.action('close')
        need(all(report[k] is True for k in ('twoPatchRuntimeVerified', 'ownedResetConfirmed', 'postResetHealthConfirmed')), 'ALL_CDC_RUNTIME_PHASES_REQUIRED')
        return current, report

    def close_attestation(self, current, report):
        closed = report['sourceClosed']; need(closed is not None, 'ACTUAL_SOURCE_CLOSE_REQUIRED')
        command = next((e['data'] for e in reversed(self.controller_journal.entries) if e['kind'] == 'SSM_TERMINAL'
            and e['data']['commandId'] == closed['commandId']), None)
        need(command is not None and command['status'] == 'Success', 'EXACT_FINAL_STOP_RESULT_REQUIRED')
        reply = self.aws.call('ssm', 'get-command-invocation', '--command-id', command['commandId'], '--instance-id', command['instanceId'])
        need(reply.get('CommandId') == command['commandId'] and reply.get('InstanceId') == command['instanceId']
             and reply.get('Status') == 'Success' and reply.get('ResponseCode') == 0, 'FINAL_STOP_RESULT_CHANGED')
        raw = reply.get('StandardOutputContent', '').encode(); need(len(raw) <= MAX_OUTPUT_CHARS, 'R4_SSM_SAFE_OUTPUT_BUDGET')
        value = core.parse(raw); need(value.get('passed') is True, 'FINAL_STOP_NOT_CONFIRMED')
        observation = controller.connect_observation(value['observation'], self.config['hosts']['connect'], running=False)
        need(observation['finishedAt'] == closed['connectFinishedAt'], 'FINAL_STOP_LIFETIME_CHANGED')
        proof = {'schemaVersion': 1, 'kind': 'global-b-aws-service-source-close-attestation',
            'configurationSha256': cdc.journal_binding(self.config), 'operationId': self.operation['operationId'],
            'lease': self.context['lease'], 'supervisorSha256': sha(Path(__file__).read_bytes()),
            'controllerReceiptSha256': sha(encoded(report)), 'command': {key: command[key] for key in COMMAND_FIELDS.split()},
            'observation': observation, 'sourceClosed': closed, 'observedEpoch': int(self.clock())}
        import growth_b_service_verify as verify
        verify.validate_close_attestation(proof, report, self.config, fresh_at=self.clock())
        return proof

    def finalize_phase(self, current, report, live_receipt_ref):
        attempt = 'finalize-' + str(self.context['lease']['fencingToken'])
        saved = next((e['data'] for e in reversed(self.journal.entries) if e['kind'] == 'FINALIZE_SOURCE_FIXED' and e['data']['attempt'] == attempt), None)
        if saved:
            job, closed, report, cdc_proof = (saved[k] for k in ('job', 'closed', 'report', 'cdcProof'))
            need(job['cdcConfiguration']['sha256'] == sha(encoded(self.config)) and closed['lease'] == self.context['lease'], 'ORIGINAL_FINALIZER_CONFIGURATION_CHANGED')
        else:
            closed = self.close_attestation(current, report)
            cdc_proof = self.controller_journal.last('HOST_POST_RESET')['report']
            cdc_ref = self.stage_input('cdc-receipt.json', cdc_proof, closed=closed)
            controller_ref = self.stage_input('controller-close.json', report, closed=closed)
            close_ref = self.stage_input('close-attestation.json', closed, closed=closed)
            # A new lease may perform another READ-locked fingerprint after all
            # prior SSM commands and host worker PIDs are known to be finished.
            job = self.producer_config('finalize', self.current_cdc_ref, close_ref, self.deadline - 90,
                liveReceipt=live_receipt_ref, liveAttestation=self.attestation_ref, cdcReceipt=cdc_ref, controllerReceipt=controller_ref)
            self.journal.add('FINALIZE_SOURCE_FIXED', {'attempt': attempt, 'job': job, 'closed': closed, 'report': report, 'cdcProof': cdc_proof})
        for name, value in (('cdc-post-reset.json', cdc_proof), ('controller-close.json', report), ('close-attestation.json', closed)):
            self.exported(name, encoded(value))
        reference, _ = self.worker('finalize', job, closed=closed)
        final = self.download(reference, 'service-verified-and-reset.json', closed=closed)
        root = '/opt/airbob/global-b/' + self.operation['runId']
        fingerprint = self.download({'path': root + '/r4/' + self.operation['operationId'] + '/' + attempt + '/prepared-fingerprint.json',
            'sha256': final['preparedFingerprintSha256']}, 'prepared-fingerprint.json', closed=closed)
        restored = self.download({'path': root + '/execute/restore-receipt.json', 'sha256': self.builder.manifest['preparation']['restoreReceiptSha256']}, 'restore-receipt.json', closed=closed)
        need(sha((self.public / 'restore-receipt.json').read_bytes()) == final['restoreReceiptSha256'], 'FINAL_RAW_RESTORE_REFERENCE_CHANGED')
        final_gate(final, fingerprint, restored, self.config, self.builder.manifest, self.builder.readiness)
        current = self.open_controller(); current.guard(force=True)
        group = current.group(); current.members(group, set(), exact=True)
        need(tuple(group[k] for k in ('MinSize', 'DesiredCapacity', 'MaxSize')) == (0, 0, 0)
             and not current.live_apps() and not current.active_scaling(), 'FINAL_SOURCE_WRITER_FENCE_CHANGED')
        for identifier in closed['sourceClosed']['replacementInstanceIds']:
            current.instance({'instanceId': identifier}, terminated=True)
        self.journal.add('FINAL_SOURCE_REOBSERVED', {'lease': self.context['lease'], 'observedEpoch': int(self.clock()),
            'configurationSha256': cdc.journal_binding(self.config), 'writersStopped': True, 'rdsResourceId': self.config['rds']['resourceId']})
        self.journal.add('SOURCE_R4_COMPLETE', {'serviceReceiptSha256': reference['sha256'],
            'preparedFingerprintSha256': final['preparedFingerprintSha256'], 'restoreReceiptSha256': final['restoreReceiptSha256']})
        return final

    def public_report(self, passed, failure=None):
        exports = {}
        for name in sorted(PUBLIC_FILES):
            path = self.public / name
            if path.exists():
                raw = core.private_path(path).read_bytes(); exports[name] = {'sha256': sha(raw), 'bytes': len(raw)}
        final = self.journal.last('SOURCE_R4_COMPLETE')
        need(not passed or final is not None and {'service-verified-and-reset.json', 'prepared-fingerprint.json', 'restore-receipt.json'} <= set(exports), 'ACTUAL_FULL_SOURCE_EVIDENCE_REQUIRED')
        live = self.live; config = self.config or self.original_configuration
        value = {'schemaVersion': 1, 'kind': SUPERVISOR_KIND, 'operationId': self.operation['operationId'], 'runId': self.operation['runId'],
            'datasetId': self.operation['datasetId'], 'executionCommit': self.operation['executionCommit'],
            'operationSha256': operation_binding(self.operation), 'contextSha256': sha(encoded(self.context)),
            'sourceArchiveSha256': self.operation['sourceArchiveSha256'],
            'liveConfigurationSha256': sha(encoded(live)) if live else None,
            'cdcConfigurationSha256': sha(encoded(self.original_configuration)) if self.original_configuration else None,
            'liveJournalBindingSha256': cdc.journal_binding(live) if live else None,
            'cdcJournalBindingSha256': cdc.journal_binding(config) if config else None,
            'sharedBindingSha256': shared_binding(config) if config else None,
            'targetIdentity': {k: config['rds'][k] for k in ('identifier', 'resourceId', 'endpoint', 'serverUuid')} if config else None,
            'selected': {k: self.operation[k] for k in ('manifest', 'readiness')},
            'state': 'SOURCE_R4_VERIFIED_AND_RESET' if passed else 'R4_UNCONFIRMED', 'phasePassed': passed,
            'fullServiceVerificationAndReset': passed, 'snapshotCreated': False, 'privateValuesIncluded': False,
            'failureCode': failure, 'exports': exports}
        write_current(self.public / 'supervisor.json', value); return value

    def run(self):
        try:
            controller.verify_environment({'executionCommit': self.operation['executionCommit']}, self.aws, now=self.clock)
            self.guard()
            if self.controller_journal is not None:
                self.builder.selected_inputs()
                self.open_controller().transport.settle(self.deadline)
            try: self.transport.settle(self.deadline)
            except Failed as error:
                terminal = self.journal.last('SSM_TERMINAL')
                need(error.code == 'SSM_COMMAND_UNCONFIRMED' and terminal and terminal['name'] == 'service-worker-finalize'
                     and not self.transport.pending(), 'PRIOR_SSM_WORK_UNCONFIRMED')
                self.journal.add('PRIOR_FINALIZER_TERMINAL', {'commandId': terminal['commandId'], 'status': terminal['status'], 'newBusinessWindow': False})
            live, live_receipt_ref, live_config_ref = self.live_phase()
            attempt = 'finalize-' + str(self.context['lease']['fencingToken'])
            same_finalizer = any(e['kind'] == 'FINALIZE_SOURCE_FIXED' and e['data']['attempt'] == attempt for e in self.journal.entries)
            if same_finalizer:
                current = self.open_controller(); report = None
            else: current, report = self.cdc_phase(live, live_config_ref)
            self.finalize_phase(current, report, live_receipt_ref)
            self.checkpoint(); return self.public_report(True)
        except BaseException as error:
            code = error.code if isinstance(error, Failed) else 'R4_EXECUTION_UNCONFIRMED'
            # On a known pre-restart failure, preserve the original app/Connect
            # fence. Controller.settle refuses unknown submissions before any
            # capacity mutation. No automatic SQL reset or new PATCH follows.
            if self.controller_journal and self.controller_journal.last('ASG_ORIGINAL') and self.controller_journal.last('ASG_RESTART_INTENT') is None:
                try:
                    current = self.open_controller(); frozen = current.freeze()
                    self.journal.add('FAILURE_SOURCE_FROZEN', {'configurationSha256': cdc.journal_binding(self.config), 'observation': frozen})
                except BaseException:
                    self.journal.add('FAILURE_FENCE_UNCONFIRMED', {'automaticCancellation': False, 'automaticTeardown': False})
            self.journal.add('SUPERVISOR_UNCONFIRMED', {'code': code, 'automaticCancellation': False, 'automaticTeardown': False})
            self.checkpoint(); return self.public_report(False, code)

    def close(self):
        self.checkpoint()
        if self.controller_journal: self.controller_journal.close()
        if self.journal: self.journal.close()


def recovery_input(operation, path, aws, directory):
    if operation['stage'] != 'resume':
        need(path is None, 'RECOVERY_ONLY_ON_EXPLICIT_RESUME'); return None
    reference = operation['resume']['recovery']
    if path is None:
        path = directory / 'selected-recovery.json'
        need(not path.exists() and path.parent.resolve() == path.parent, 'NEW_RECOVERY_DOWNLOAD_PATH_REQUIRED')
        result = aws.call('s3api', 'get-object', '--bucket', service.EVIDENCE, '--key', reference['key'], '--version-id', reference['versionId'], str(path))
        need(result.get('VersionId') == reference['versionId'], 'EXACT_RECOVERY_VERSION_CHANGED'); path.chmod(0o600)
    raw = core.private_path(Path(path)).read_bytes()
    need(len(raw) == reference['bytes'] and sha(raw) == reference['sha256'], 'EXACT_RECOVERY_BYTES_CHANGED')
    return validate_recovery(core.parse(raw), operation)


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest='command', required=True)
    package = sub.add_parser('package-sources'); package.add_argument('--output', type=Path, required=True)
    validate = sub.add_parser('validate-operation'); validate.add_argument('--operation', type=Path, required=True)
    validate.add_argument('--context', type=Path); validate.add_argument('--output', type=Path, required=True)
    run = sub.add_parser('run'); run.add_argument('--operation', type=Path, required=True); run.add_argument('--context', type=Path, required=True)
    run.add_argument('--directory', type=Path, required=True); run.add_argument('--recovery', type=Path)
    args = parser.parse_args(argv); instance = None
    try:
        if args.command == 'package-sources':
            result = package_sources(args.output); print(json.dumps({'state': 'PUBLIC_SOURCE_PACKAGE_CREATED', 'archive': result['archive']['sha256']})); return 0
        operation = validate_operation(core.parse(core.reads.read_bytes(args.operation)))
        context = validate_context(core.parse(core.reads.read_bytes(args.context)), operation) if args.context else None
        if args.command == 'validate-operation':
            report = {'schemaVersion': 1, 'kind': KIND + '-validation', 'state': 'OFFLINE_OPERATION_VALIDATED',
                'operationSha256': operation_binding(operation), 'sourceArchiveSha256': operation['sourceArchiveSha256'],
                'contextSha256': sha(encoded(context)) if context else None, 'awsCallsExecuted': False}
            core.write_new(args.output, encoded(report)); print(json.dumps({'state': report['state']})); return 0
        directory = args.directory.absolute()
        if not directory.exists(): directory.mkdir(mode=0o700)
        core.private_path(directory, directory=True)
        aws = BoundedAws(context['controllerDeadlineEpoch'])
        controller.verify_environment({'executionCommit': operation['executionCommit']}, aws)
        recovery = recovery_input(operation, args.recovery, aws, directory)
        instance = Supervisor(operation, context, directory, aws=aws, recovery=recovery)
        report = instance.run(); print(json.dumps({'state': report['state'], 'phasePassed': report['phasePassed'], 'failureCode': report['failureCode']}))
        return 0 if report['phasePassed'] else 1
    except BaseException as error:
        code = error.code if isinstance(error, Failed) else 'R4_ADMISSION_UNCONFIRMED'
        print(json.dumps({'phasePassed': False, 'failureCode': code, 'snapshotCreated': False})); return 1
    finally:
        if instance: instance.close()


if __name__ == '__main__': raise SystemExit(main())
