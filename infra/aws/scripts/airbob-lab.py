#!/usr/bin/env python3
"""Start, pause, resume or destroy the saved B lab; never replay its SQL dump.

The saved configuration contains public immutable baseline coordinates only.
Cloud calls occur only from main/run. Credentials and raw Terraform state are
never written by this front door. Per-operation evidence is private by default.
"""
from __future__ import annotations

import argparse
import base64
import contextlib
import datetime as dt
import fcntl
import gzip
import hashlib
import importlib
import importlib.metadata
import io
import ipaddress
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import socket
import ssl
import subprocess
import sys
import tarfile
import threading
import time
import uuid

SCRIPTS = Path(__file__).resolve().parent
REPO = SCRIPTS.parents[2]
DEFAULT_CONFIG = SCRIPTS.parent / 'lab-baseline.json'
WORKFLOW = 'aws-performance-lab.yml'
ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
DATASET_BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
EVIDENCE_BUCKET = 'airbob-performance-lab-evidence-' + ACCOUNT
MAX_BYTES = 16 * 1024 * 1024


class Rejected(Exception): pass
class AuthStop(Rejected): pass


def need(ok, code):
    if not ok: raise Rejected(code)


def encoded(value): return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)+'\n').encode()
def sha(raw): return hashlib.sha256(raw).hexdigest()


def parse(raw):
    def pairs(rows):
        result = {}
        for k, v in rows:
            need(k not in result, 'DUPLICATE_JSON_KEY'); result[k] = v
        return result
    return json.loads(raw, object_pairs_hook=pairs)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def private_directory(path):
    path = Path(path).expanduser().absolute()
    need(path.resolve() == path and not path.is_symlink(), 'CANONICAL_LOCAL_DIRECTORY_REQUIRED')
    missing=[];current=path
    while not current.exists():missing.append(current);current=current.parent
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    need(path.stat().st_uid == os.getuid() and path.stat().st_mode & 0o777 == 0o700, 'PRIVATE_LOCAL_DIRECTORY_REQUIRED')
    for created in reversed(missing):sync_directory(created);sync_directory(created.parent)
    return path


class Journal:
    def __init__(self, path): self.path = private_directory(path)
    def read(self, name):
        p = self.path / name
        if not p.exists(): return None
        need(p.is_file() and not p.is_symlink() and p.stat().st_uid == os.getuid()
             and p.stat().st_mode & 0o777 == 0o600 and p.stat().st_size <= MAX_BYTES, 'JOURNAL_FILE_CHANGED')
        return parse(p.read_bytes())
    def put(self, name, value):
        p = self.path / name; raw = encoded(value)
        old = self.read(name)
        if old is not None:
            need(encoded(old) == raw, 'JOURNAL_ALREADY_HAS_DIFFERENT_RESULT'); return old
        with os.fdopen(os.open(p, os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW, 0o600), 'wb') as stream:
            stream.write(raw); stream.flush(); os.fsync(stream.fileno())
        sync_directory(self.path); return value


def config(path, *, required=False):
    path = Path(path).expanduser()
    if not path.exists():
        need(not required, 'SAVED_BASELINE_CONFIG_REQUIRED'); return None
    need(path.is_file() and not path.is_symlink() and path.stat().st_size < 65536, 'PUBLIC_CONFIG_FILE_REQUIRED')
    v = parse(path.read_bytes())
    need(type(v) is dict and set(v) == {'schemaVersion','kind','profile','githubRepository','sourceProvenance',
        'bundleManifestVersionId','ttlHours','approvedDeadlineEpoch','evidenceDirectory'}, 'SAVED_CONFIG_FIELDS_DIFFER')
    need(v['schemaVersion'] == 1 and v['kind'] == 'airbob-lab-config'
         and re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', v['profile'])
         and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', v['githubRepository'])
         and type(v['ttlHours']) is int and 6 <= v['ttlHours'] <= 168
         and (v['approvedDeadlineEpoch'] is None or type(v['approvedDeadlineEpoch']) is int and v['approvedDeadlineEpoch'] > 0)
         and re.fullmatch(r'[A-Za-z0-9._~+/=-]{1,1024}', v['bundleManifestVersionId'])
         and v['bundleManifestVersionId'] not in ('null','None')
         and isinstance(v['evidenceDirectory'], str) and bool(v['evidenceDirectory']), 'SAVED_CONFIG_INVALID')
    from growth_b_mac_snapshot import DATASET, public_ref
    public_ref(v['sourceProvenance'], 'datasets/'+DATASET+'-mac-snapshots/')
    return v


class IO:
    """One bounded subprocess primitive; raw outputs are only held in RAM."""
    def __init__(self, profile):
        need(re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', profile), 'AWS_PROFILE_REQUIRED')
        self.profile = profile
        self.env = {k:v for k,v in os.environ.items() if k not in {'AWS_ACCESS_KEY_ID','AWS_SECRET_ACCESS_KEY','AWS_SESSION_TOKEN','TF_LOG','TF_LOG_PATH'}
                    and not k.startswith('TF_CLI_ARGS')}
        self.env.update(AWS_PROFILE=profile,AWS_REGION=REGION,AWS_DEFAULT_REGION=REGION,AWS_PAGER='',AWS_MAX_ATTEMPTS='1',PYTHONDONTWRITEBYTECODE='1')

    def command(self, argv, *, input_bytes=None, timeout=60, extra_fd=None, pass_fds=(), check=True):
        try:
            proc = subprocess.Popen(argv, env=self.env, cwd=REPO, stdin=subprocess.PIPE if input_bytes is not None else subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True, pass_fds=pass_fds)
        except BaseException:
            for fd in pass_fds:os.close(fd)
            raise
        for fd in pass_fds: os.close(fd)
        if input_bytes is not None:
            try:proc.stdin.write(input_bytes)
            except BrokenPipeError:pass
            finally:proc.stdin.close()
        buffers = {proc.stdout:bytearray(),proc.stderr:bytearray()}
        if extra_fd is not None: buffers[extra_fd] = bytearray()
        selected = selectors.DefaultSelector(); started = time.monotonic()
        for stream in buffers: selected.register(stream,selectors.EVENT_READ)
        try:
            while selected.get_map():
                need(time.monotonic()-started < timeout,'LOCAL_COMMAND_DEADLINE')
                for key,_ in selected.select(.25):
                    fd = key.fileobj if isinstance(key.fileobj,int) else key.fileobj.fileno()
                    raw = os.read(fd,65536)
                    if not raw: selected.unregister(key.fileobj)
                    else:
                        buffers[key.fileobj].extend(raw)
                        need(sum(map(len,buffers.values())) <= MAX_BYTES,'COMMAND_OUTPUT_LIMIT')
            code = proc.wait(timeout=5)
            out,err = bytes(buffers[proc.stdout]),bytes(buffers[proc.stderr])
            if code and any(x in err.lower() for x in (b'expiredtoken',b'expired token',b'token has expired',b'sso session',b'unable to locate credentials',b'invalidclienttokenid',b'aws_authentication')):
                raise AuthStop('AWS_AUTHENTICATION_UNAVAILABLE_STOP')
            need(not check or code==0,'LOCAL_COMMAND_FAILED')
            return code,out,err,bytes(buffers[extra_fd]) if extra_fd is not None else b''
        finally:
            selected.close()
            if proc.poll() is None:
                os.killpg(proc.pid,signal.SIGTERM)
                try: proc.wait(timeout=10)
                except subprocess.TimeoutExpired: os.killpg(proc.pid,signal.SIGKILL);proc.wait(timeout=5)
            proc.stdout.close();proc.stderr.close()

    def aws(self,*args):
        return parse(self.command(['aws','--profile',self.profile,'--region',REGION,'--no-cli-pager','--output','json',*args])[1])

    def gh(self, repository, endpoint, *, body=None):
        argv=['gh','api','repos/'+repository+'/'+endpoint,'-H','X-GitHub-Api-Version:2026-03-10']
        if body is not None: argv += ['--method','POST','--input','-']
        raw=self.command(argv,input_bytes=encoded(body) if body is not None else None)[1]
        need(bool(raw.strip()),'GITHUB_RETURNED_NO_EXACT_RUN_ID')
        return parse(raw)

    def get(self,bucket,reference):
        read_fd,write_fd=os.pipe()
        # Open /dev/fd through the same child's inherited read/write descriptor.
        # Parent closes its writer before draining so EOF is observable.
        try:
            argv=['aws','--profile',self.profile,'--region',REGION,'--no-cli-pager','--output','json','s3api','get-object',
                '--bucket',bucket,'--key',reference['key'],'--version-id',reference['versionId'],'/dev/fd/'+str(write_fd)]
            # command closes this duplicate in the parent immediately after spawn.
            _,meta,_,raw=self.command(argv,extra_fd=read_fd,pass_fds=(write_fd,),timeout=90)
            need(parse(meta).get('VersionId')==reference['versionId'] and len(raw)==reference['bytes'] and sha(raw)==reference['sha256'],
                 'EXACT_PUBLIC_OBJECT_CHANGED')
            return raw
        finally:
            os.close(read_fd)

    def latest(self,bucket,key):
        head=self.aws('s3api','head-object','--bucket',bucket,'--key',key)
        # HEAD's checksum is not assumed to be present; the full object is hashed.
        ref={'key':key,'versionId':head['VersionId'],'bytes':head['ContentLength']}
        return self.read_version(bucket,ref)

    def read_version(self,bucket,ref):
        read_fd,write_fd=os.pipe()
        try:
            argv=['aws','--profile',self.profile,'--region',REGION,'--no-cli-pager','--output','json','s3api','get-object',
                  '--bucket',bucket,'--key',ref['key'],'--version-id',ref['versionId'],'/dev/fd/'+str(write_fd)]
            _,meta,_,raw=self.command(argv,extra_fd=read_fd,pass_fds=(write_fd,),timeout=90)
            need(parse(meta).get('VersionId')==ref['versionId'] and 0<len(raw)<=MAX_BYTES and len(raw)==ref['bytes'],'EXACT_OBJECT_VERSION_CHANGED')
            return raw,{**ref,'sha256':sha(raw)}
        finally:
            os.close(read_fd)


class Publisher:
    def __init__(self, io, journal, guard, prefixes): self.io,self.journal,self.guard,self.prefixes=io,journal,guard,prefixes
    def put(self,name,bucket,key,raw):
        need(bucket in self.prefixes and key.startswith(self.prefixes[bucket]) and 0<len(raw)<=MAX_BYTES,'PUBLICATION_OUTSIDE_NEW_NAMESPACE')
        intent={'bucket':bucket,'key':key,'sha256':sha(raw),'bytes':len(raw),'ifNoneMatch':'*','encryption':'AES256'}
        def found():
            listing=self.io.aws('s3api','list-object-versions','--bucket',bucket,'--prefix',key)
            need(not listing.get('IsTruncated'),'CAS_HISTORY_INCOMPLETE')
            rows=[x for x in listing.get('Versions',[]) if x['Key']==key]
            need(not any(x['Key']==key for x in listing.get('DeleteMarkers',[])) and len(rows)<=1,'CAS_VERSION_HISTORY_CHANGED')
            if not rows:return None
            need(rows[0].get('IsLatest') is True,'CAS_LATEST_VERSION_CHANGED')
            ref={'key':key,'versionId':rows[0]['VersionId'],'sha256':sha(raw),'bytes':len(raw)}
            need(self.io.get(bucket,ref)==raw,'CAS_BYTES_CHANGED');return ref
        self.guard();existing=found()
        if existing:return self.journal.put(name+'.result.json',existing)
        need(self.journal.read(name+'.intent.json') is None,'PREVIOUS_PUT_UNCONFIRMED_NO_RETRY')
        self.journal.put(name+'.intent.json',intent)
        path=self.journal.path/(name+'.public-bytes')
        with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') as stream:
            stream.write(raw);stream.flush();os.fsync(stream.fileno())
        sync_directory(path.parent);self.guard()
        response=None
        try:
            args=['s3api','put-object','--bucket',bucket,'--key',key,'--body',str(path),
                '--if-none-match','*','--server-side-encryption','AES256','--checksum-sha256',base64.b64encode(hashlib.sha256(raw).digest()).decode()]
            if bucket==EVIDENCE_BUCKET:args+=['--tagging','Retention=summary']
            response=self.io.aws(*args)
        except AuthStop:raise
        except Exception:pass  # Read-only reconciliation; never issue a second PUT.
        actual=found();need(actual is not None,'PUBLICATION_UNCONFIRMED_NO_RETRY')
        need(response is None or response.get('VersionId')==actual['versionId'],'PUT_RESPONSE_VERSION_CHANGED')
        self.guard();return self.journal.put(name+'.result.json',actual)


def new_window(saved, ttl, now, approved_deadline=None):
    ttl=saved['ttlHours'] if ttl is None else ttl
    need(type(ttl) is int and 6<=ttl<=168,'NEW_TARGET_TTL_MUST_BE_6_TO_168_HOURS')
    need(approved_deadline is None or type(approved_deadline) is int and approved_deadline>0,'APPROVED_DEADLINE_INVALID')
    deadline=now+ttl*3600
    if saved['approvedDeadlineEpoch'] is None and approved_deadline is None:deadline+=300
    if saved['approvedDeadlineEpoch'] is not None:deadline=min(deadline,saved['approvedDeadlineEpoch'])
    if approved_deadline is not None:deadline=min(deadline,approved_deadline)
    need(deadline-now>=21600,'NEW_TARGET_REQUIRES_SIX_HOURS_WITHIN_CURRENT_APPROVAL')
    return ttl,deadline


def tools_archive(manifest):
    import growth_b_service as service
    stream=io.BytesIO();names=service.tools_for(manifest);hashes={}
    with gzip.GzipFile(fileobj=stream,mode='wb',filename='',mtime=0) as zipped:
        with tarfile.open(fileobj=zipped,mode='w',format=tarfile.USTAR_FORMAT) as tar:
            for name in sorted(names):
                raw=(SCRIPTS/name).read_bytes();hashes[name]=sha(raw)
                info=tarfile.TarInfo(name);info.size=len(raw);info.mode=0o600;info.mtime=info.uid=info.gid=0
                tar.addfile(info,io.BytesIO(raw))
    raw=stream.getvalue()
    with tarfile.open(fileobj=io.BytesIO(raw),mode='r:gz') as tar:
        need(set(tar.getnames())==set(names) and all(sha(tar.extractfile(x).read())==hashes[x.name] for x in tar.getmembers()),'CONSUMER_ARCHIVE_CHANGED')
    return raw,hashes


class Lab:
    def __init__(self,saved,profile,directory,*,io_instance=None):
        self.saved,self.profile=saved,profile;self.base=Journal(directory);self.io=io_instance or IO(profile)
        self.power=importlib.import_module('growth_b_power')
        self.snapshot=importlib.import_module('growth_b_mac_snapshot')
        self.service=importlib.import_module('growth_b_service')

    def power_action(self,operation,cidr=None):
        argv=[sys.executable,'-B',str(SCRIPTS/'growth_b_power.py'),operation,'--profile',self.profile,
              '--lab-directory',str(SCRIPTS.parent/'lab'),'--evidence-directory',str(self.base.path/'power')]
        if cidr is not None:argv+=['--cidr',cidr]
        _,raw,_,_=self.io.command(argv,timeout=19000)
        value=parse(raw);need(type(value) is dict and 'state' in value,'POWER_RESULT_UNCONFIRMED');return value

    def access(self,cidr=None):
        result=self.power_action('access',cidr)
        match=re.fullmatch(r'curl --fail --connect-to api\.airbob\.cloud:443:([A-Za-z0-9.-]+\.elb\.amazonaws\.com):443 https://api\.airbob\.cloud/actuator/health/readiness',result.get('readinessCommand',''))
        need(match is not None,'EXACT_AWS_ALB_ACCESS_MAPPING_REQUIRED')
        raw=self.io.command(['curl','--fail','--silent','--show-error','--connect-timeout','10','--max-time','30',
            '--connect-to','api.airbob.cloud:443:'+match[1]+':443','--write-out','\n%{http_code}',
            'https://api.airbob.cloud/actuator/health/readiness'],timeout=40)[1]
        body,code=raw.rsplit(b'\n',1)
        need(code==b'200' and parse(body).get('status')=='UP','USER_ALB_READINESS_UNCONFIRMED')
        return {**result,'awsAlbDnsName':match[1],'userHttpsReadinessVerified':True,'readinessObservedAtEpoch':time.time()}

    def backend(self):
        runner=self.power.Runner(self.profile,self.base.path/'backend',SCRIPTS.parent/'lab')
        return runner,runner.initialize()

    def current_main(self):
        need(self.saved is not None,'SAVED_BASELINE_CONFIG_REQUIRED')
        main=self.io.gh(self.saved['githubRepository'],'git/ref/heads/main')['object']['sha']
        need(re.fullmatch(r'[0-9a-f]{40}',main),'EXACT_MAIN_REQUIRED')
        local=self.io.command(['git','rev-parse','HEAD'])[1].decode().strip()
        need(local==main,'UPDATE_CHECKOUT_TO_CURRENT_MAIN')
        need(not self.io.command(['git','status','--porcelain','--untracked-files=no'])[1].strip(),'CLEAN_MAIN_CHECKOUT_REQUIRED')
        return main

    def start_prerequisites(self):
        need(all(shutil.which(name) for name in ('aws','gh','terraform','git','lsof','curl','session-manager-plugin')),
             'INSTALL_AWS_GH_TERRAFORM_SESSION_MANAGER_PLUGIN_AND_LSOF')
        try:version=importlib.metadata.version('PyMySQL')
        except importlib.metadata.PackageNotFoundError:raise Rejected('INSTALL_PINNED_PYMYSQL_1_1_2') from None
        need(version=='1.1.2','INSTALL_PINNED_PYMYSQL_1_1_2')

    def dispatch(self,journal,label,inputs,deadline):
        repo=self.saved['githubRepository'];selected=inputs['expected_execution_commit']
        response=journal.read(label+'.dispatch.json')
        if response is None:
            need(self.current_main()==selected,'MAIN_CHANGED_DURING_START')
            need(journal.read(label+'.intent.json') is None,'WORKFLOW_DISPATCH_UNCONFIRMED_NO_RETRY')
            journal.put(label+'.intent.json',{'repository':repo,'workflow':WORKFLOW,'ref':'main','inputs':inputs})
            response=self.io.gh(repo,'actions/workflows/'+WORKFLOW+'/dispatches',body={'ref':'main','inputs':inputs})
            need(type(response.get('workflow_run_id')) is int and response['workflow_run_id']>0,'WORKFLOW_DISPATCH_UNCONFIRMED_NO_RETRY')
            journal.put(label+'.dispatch.json',response)
        run_id=response['workflow_run_id']
        while True:
            need(time.time()<deadline,'WORKFLOW_WAIT_DEADLINE_RESOURCES_RETAINED')
            run=self.io.gh(repo,'actions/runs/'+str(run_id))
            need(run['id']==run_id and run['head_sha']==selected and run['head_branch']=='main'
                 and run['event']=='workflow_dispatch' and run['run_attempt']==1
                 and run['path']=='.github/workflows/'+WORKFLOW,'WORKFLOW_IDENTITY_CHANGED')
            if run['status']=='completed':
                journal.put(label+'.completed.json',{k:run[k] for k in ('id','head_sha','head_branch','event','run_attempt','path','status','conclusion','created_at','updated_at')})
                need(run['conclusion']=='success','WORKFLOW_FAILED_RESOURCES_RETAINED');return run
            time.sleep(15)

    def base_inputs(self,source,main,deadline):
        original=source['source'];return {'mode':'performance','policy':'integrated-smoke','dns_mode':'direct-only',
            'load_generator_enabled':False,'force':False,'keep_on_failure':True,'database_bootstrap':'snapshot',
            'dataset_release':source['datasetId'],'bundle_commit':original['application']['mainCommit'],
            'app_image_digest':original['application']['image'].split('@')[1],
            'bundle_manifest_version_id':self.saved['bundleManifestVersionId'],'expected_execution_commit':main,
            'approved_execution_deadline_epoch':str(deadline)}

    def read_run(self,run):
        raw,ref=self.io.latest(EVIDENCE_BUCKET,'runs/'+run+'/operator.json');op=parse(raw)
        need(op['runId']==run and op['fencingToken']>0,'ORIGINAL_RUN_CHANGED');return op,ref

    def target_guard(self,runner,restored,source):
        runner.heartbeat()
        op=restored['operation'];self.snapshot.validate_operation(op,now=time.time())
        api=self.io.aws('rds','describe-db-instances')
        need(self.snapshot.check_target_rds(api,op,source)==restored['target'],'CURRENT_RESTORED_TARGET_CHANGED')
        asgs=self.io.aws('autoscaling','describe-auto-scaling-groups')['AutoScalingGroups']
        need(not any(x['AutoScalingGroupName'].startswith(op['targetIdentifier']+'-') and
             (x['MinSize'] or x['DesiredCapacity'] or x['Instances']) for x in asgs),'STOP_WRITERS_BEFORE_TARGET_COUNTS')

    def start(self,ttl=None,cidr=None,approved_deadline=None):
        active=self.base.read('active-start.json')
        if active is None:
            status=self.power_action('status')
            if status['state']=='POWER_OBSERVED':
                need(ttl is None,'TTL_ONLY_APPLIES_TO_NEW_TARGETS')
                need(approved_deadline is None or status['expiresAt']<=approved_deadline,'RETAINED_EXPIRY_CANNOT_BE_REWRITTEN')
                if status.get('phase') not in (None,'running'):self.power_action('resume')
                return self.access(cidr)
            need(status=={'state':'LAB_ABSENT','runId':None,'phase':None,'expiresAt':None},'EXACT_EMPTY_LAB_REQUIRED')
            need(self.saved is not None,'SAVED_BASELINE_CONFIG_REQUIRED')
            self.start_prerequisites()
            main=self.current_main();hours,deadline=new_window(self.saved,ttl,int(time.time()),approved_deadline)
            active={'operationId':'start-'+uuid.uuid4().hex[:20],'configurationSha256':sha(encoded(self.saved)),
                'main':main,'ttlHours':hours,'deadlineEpoch':deadline,'startedAtEpoch':int(time.time()),'profile':self.profile}
            self.base.put('active-start.json',active)
        need(self.saved is not None and active['configurationSha256']==sha(encoded(self.saved))
             and active['profile']==self.profile and (ttl is None or ttl==active['ttlHours']),'ACTIVE_START_CONFIGURATION_CHANGED')
        need(approved_deadline is None or active['deadlineEpoch']<=approved_deadline,'ACTIVE_START_DEADLINE_CANNOT_BE_REWRITTEN')
        journal=Journal(self.base.path/active['operationId']);main=active['main'];deadline=active['deadlineEpoch']
        need(time.time()<deadline,'ORIGINAL_START_DEADLINE_EXPIRED')
        complete=journal.read('result.json')
        if complete is not None:
            access=self.access(cidr)
            (self.base.path/'active-start.json').unlink();sync_directory(self.base.path)
            return {**complete,'access':access}
        source=parse(self.io.get(DATASET_BUCKET,self.saved['sourceProvenance']));self.snapshot.validate_source(source)
        snap=source['snapshot'];expected_key='datasets/'+source['datasetId']+'-mac-snapshots/'+snap['identifier']+'/source-'+sha(encoded(source))+'.json'
        need(self.saved['sourceProvenance']['key']==expected_key,'SAVED_BASELINE_KEY_CHANGED')
        inputs=self.base_inputs(source,main,deadline)
        restore_inputs={**inputs,'action':'snapshot-restore','policy':'isolated-read','ttl_hours':str(active['ttlHours']),
            'dataset_manifest_version_id':self.saved['sourceProvenance']['versionId'],
            'rds_snapshot_identifier':snap['identifier'],'rds_snapshot_source_run_id':source['source']['runId'],
            'rds_snapshot_source_resource_id':source['source']['rds']['resourceId'],
            'b_operation':json.dumps({'sourceMode':self.snapshot.MODE,'provenanceSha256':self.saved['sourceProvenance']['sha256'],
                'provenanceKey':expected_key,'rdsInstanceClass':'db.t3.small'},separators=(',',':'))}
        workflow=self.dispatch(journal,'restore',restore_inputs,deadline)
        run='lab-'+str(workflow['id'])+'-1';original,original_ref=self.read_run(run);fence=original['fencingToken']
        need(original['globalBMacSnapshotOperation']['executionCommit']==main and original['globalBSnapshotRestoreOnly'] is True
             and original['globalBSnapshotSourceMode']==self.snapshot.MODE and int(original['expiresAt'])<=deadline,'RESTORED_OPERATOR_CHANGED')
        restore_key='data-bootstrap/'+run+'/mac-snapshot-'+str(fence)+'/restore.json'
        raw,restore_ref=self.io.latest(EVIDENCE_BUCKET,restore_key);restored=parse(raw)
        self.snapshot.validate_restore_receipt(restored,source)
        need(restored['operation']==original['globalBMacSnapshotOperation'],'ORIGINAL_RESTORE_OPERATION_CHANGED')
        need(restored['operation']['runId']==run and restored['operation']['resourceFence']==fence
             and restored['operation']['executionCommit']==main and restored['operation']['sourceProvenance']==self.saved['sourceProvenance']
             and restored['operation']['window']['expiresAt']==int(original['expiresAt']),'RESTORE_COMPLETION_CHANGED')
        journal.put('restore-reference.json',restore_ref)
        manifest,manifest_ref=self.prepare_service(journal,active,source,restored,restore_ref)
        for stage in ('dependencies','bootstrap','application'):
            operation={'serviceRelease':manifest['serviceRelease'],'serviceManifestSha256':manifest_ref['sha256'],'stage':stage}
            if stage=='application':
                readiness,readiness_ref=self.read_readiness(manifest,manifest_ref)
                operation.update(readinessVersionId=readiness_ref['versionId'],readinessSha256=readiness_ref['sha256'])
            self.dispatch(journal,stage,{**inputs,'action':'services','run_id':run,
                'dataset_manifest_version_id':manifest_ref['versionId'],'b_operation':json.dumps(operation,separators=(',',':'))},deadline)
        access=self.access(cidr);available=restored['availableObservedAtEpoch'];usable=access['readinessObservedAtEpoch']
        result={'state':'LAB_READY','runId':run,'resourceFence':fence,'expiresAt':int(original['expiresAt']),
            'restoreReceipt':restore_ref,'serviceManifest':manifest_ref,'requestToAvailableSeconds':restored['requestToAvailableSeconds'],
            'availableToUsableAppSeconds':round(usable-available,6),'usableAppObservedAtEpoch':usable,
            'appTimerSemantics':'first CLI HTTPS readiness observation after exact application workflow success',
            'fullDatasetValidated':False,'sqlReplayed':False}
        journal.put('result.json',result)
        (self.base.path/'active-start.json').unlink();sync_directory(self.base.path)
        return {**result,'access':access}

    def read_readiness(self,manifest,manifest_ref):
        key='data-bootstrap/'+manifest['runId']+'/'+manifest['datasetId']+'-service-'+manifest['serviceRelease']+'.json'
        raw,ref=self.io.latest(EVIDENCE_BUCKET,key);value=parse(raw)
        self.service.validate_readiness(value,manifest,manifest_ref['sha256']);return value,ref

    def prepare_service(self,journal,active,source,restored,restore_ref):
        need(self.current_main()==active['main'],'MAIN_CHANGED_BEFORE_TARGET_PUBLICATION')
        prior=journal.read('service-manifest-reference.json')
        if prior:
            manifest=parse(self.io.get(DATASET_BUCKET,prior));self.service.validate_manifest(manifest,source['datasetId'],restored['operation']['runId'],manifest['serviceRelease'])
            return manifest,prior
        runner,state=self.backend();out=self.power.outputs(state);run=restored['operation']['runId']
        need(out['run_identity']=={'run_id':run,'resource_fencing_token':restored['operation']['resourceFence']},'CURRENT_BACKEND_TARGET_CHANGED')
        with runner.acquired(state):
            guard=lambda:self.target_guard(runner,restored,source);guard()
            prefix='data-bootstrap/'+run+'/mac-snapshot-local/'
            release='mac-snapshot-'+active['operationId'].removeprefix('start-')
            service_prefix='datasets/'+source['datasetId']+'-aws-service/'+release+'/'
            publisher=Publisher(self.io,journal,guard,{EVIDENCE_BUCKET:prefix,DATASET_BUCKET:service_prefix})
            selected_counts=journal.read('counts-selected.json')
            if selected_counts is not None:
                need(set(selected_counts)=={'directory','sha256'} and re.fullmatch(r'counts-attempt-[0-9a-f]{20}',selected_counts['directory']),
                     'SELECTED_COUNTS_REFERENCE_CHANGED')
                counts_path=journal.path/selected_counts['directory']/'counts/result.json'
                need(not counts_path.is_symlink() and counts_path.is_file() and counts_path.stat().st_size<MAX_BYTES,'SELECTED_COUNTS_FILE_CHANGED')
                raw=counts_path.read_bytes();need(sha(raw)==selected_counts['sha256'],'SELECTED_COUNTS_BYTES_CHANGED')
                counts=parse(raw)
                need(counts['state']=='TARGET_32_COUNTS_AND_DDL_VERIFIED','SELECTED_COUNTS_NOT_COMPLETE')
            else:
                # A later explicit start may repeat these read-only SELECTs after
                # a lost tunnel. Prior attempts and their failure receipts stay.
                attempt=Journal(journal.path/('counts-attempt-'+uuid.uuid4().hex[:20]))
                counts=self.count_target(attempt,runner,state,source,restored,guard)
                raw=(attempt.path/'counts/result.json').read_bytes()
                need(raw==encoded(counts),'ACTUAL_COUNT_RESULT_BYTES_REQUIRED')
                journal.put('counts-selected.json',{'directory':attempt.path.name,'sha256':sha(raw)})
            target=self.snapshot.make_target_receipt(source,restored,counts)
            count_ref=publisher.put('counts',EVIDENCE_BUCKET,prefix+'counts.json',encoded(counts))
            target_ref=publisher.put('target',EVIDENCE_BUCKET,prefix+'target.json',encoded(target))
            manifest=json.loads(json.dumps(self.snapshot.validate_source(source)['documents']['serviceManifest']))
            runtime_raw=self.io.get(DATASET_BUCKET,manifest['appRuntimeBinding'])
            manifest.update(runId=run,serviceRelease=release,rds={k:target['target'][k] for k in ('identifier','resourceId','serverUuid')})
            manifest['cdc']=self.service.cdc_identity(run,target['target']['serverUuid'])
            manifest['preparation']={'sourceMode':self.snapshot.MODE,'receipt':target_ref,'sourceProvenance':self.saved['sourceProvenance'],
                'restoreReceipt':restore_ref,'countsDdlReceipt':count_ref,'rdsCaBundle':manifest['preparation']['rdsCaBundle']}
            archive,hashes=tools_archive(manifest)
            manifest['consumerTools']=publisher.put('consumer-tools',DATASET_BUCKET,service_prefix+'files/consumer-tools.tar.gz',archive)
            manifest['appRuntimeBinding']=publisher.put('app-runtime',DATASET_BUCKET,service_prefix+'files/app-runtime-binding.json',runtime_raw)
            manifest['toolSources']=hashes
            self.service.validate_manifest(manifest,source['datasetId'],run,release,hashes)
            runtime_file=journal.path/'runtime-binding.json'
            if not runtime_file.exists():journal.put('runtime-binding.json',parse(runtime_raw))
            projection=self.service.app_runtime.validate_runtime_binding(runtime_file,manifest['application']['image'],manifest['application']['mainCommit'],manifest['application']['appJarSha256'])
            self.service.validate_app_runtime_projection(projection,manifest['application'])
            self.snapshot.validate_target_receipt(target,source,restored,counts);guard()
            need(self.current_main()==active['main'],'MAIN_CHANGED_BEFORE_MANIFEST_PUBLICATION')
            ref=publisher.put('service-manifest',DATASET_BUCKET,service_prefix+'aws-service.json',encoded(manifest))
            journal.put('service-manifest-reference.json',ref);return manifest,ref

    def count_target(self,journal,runner,state,source,restored,guard):
        import pymysql
        need(importlib.metadata.version('PyMySQL')=='1.1.2','INSTALL_PINNED_PYMYSQL_1_1_2')
        target=restored['target'];api=self.io.aws('rds','describe-db-instances','--db-instance-identifier',target['identifier'])['DBInstances'][0]
        ca_ref=self.snapshot.validate_source(source)['documents']['serviceManifest']['preparation']['rdsCaBundle']
        ca_raw=self.io.get(DATASET_BUCKET,ca_ref);ca=journal.path/'rds-ca.pem'
        if ca.exists():need(ca.read_bytes()==ca_raw,'CA_FILE_CHANGED')
        else:
            with os.fdopen(os.open(ca,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') as stream:stream.write(ca_raw)
        credentials=parse(self.io.aws('secretsmanager','get-secret-value','--secret-id',target['masterSecretArn'])['SecretString'])
        need(credentials['username']==api['MasterUsername'] and isinstance(credentials['password'],str) and credentials['password'],'MANAGED_CREDENTIALS_INVALID')
        context=ssl.create_default_context(cafile=str(ca));need(context.check_hostname and context.verify_mode==ssl.CERT_REQUIRED,'VERIFIED_RDS_TLS_REQUIRED')
        with Tunnel(self.io,journal,runner,self.power.outputs(state)['phase2_contract']['nat_instance_id'],restored,guard) as tunnel:
            read_timeout=min(7200,int(runner.deadline-time.time())-60)
            need(read_timeout>0,'COUNTS_OPERATION_WINDOW_CLOSED')
            db=pymysql.Connection(host=target['endpoint'],port=tunnel.port,user=credentials['username'],password=credentials['password'],
                database='airbobdb',charset='utf8mb4',autocommit=False,local_infile=False,ssl=context,
                connect_timeout=10,read_timeout=read_timeout,write_timeout=30,defer_connect=True)
            credentials.clear();sock=socket.create_connection(('127.0.0.1',tunnel.port),timeout=10)
            try:
                db.connect(sock=sock);need(db._secure and db._sock.context.check_hostname and db._sock.context.verify_mode==ssl.CERT_REQUIRED,'RDS_TLS_NOT_VERIFIED')
                with db.cursor() as cursor:
                    cursor.execute('SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ')
                    cursor.execute('START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY')
                def count(checked_guard):
                    adapter=CountDB(db,checked_guard,self.snapshot.validate_source(source)['expectedTables'])
                    return self.snapshot.validate_counts(adapter,restored,source,journal.path/'counts',checked_guard)
                result=counts_with_auth(count,lambda:(tunnel.check(),guard()))
                db.rollback();return result
            finally:
                db.close();sock.close();credentials.clear()

    def settle_start_before_destroy(self,journal,deadline):
        active=self.base.read('active-start.json')
        if active is None:return
        need(active['profile']==self.profile and active['configurationSha256']==sha(encoded(self.saved)),
             'ACTIVE_START_CONFIGURATION_CHANGED')
        started=Journal(self.base.path/active['operationId']);repo=self.saved['githubRepository']
        for label in ('restore','dependencies','bootstrap','application'):
            intent=started.read(label+'.intent.json');response=started.read(label+'.dispatch.json')
            need(intent is None or response is not None,'OWN_START_DISPATCH_UNCONFIRMED_REVIEW_BEFORE_DESTROY')
            if response is None:continue
            run_id=response['workflow_run_id']
            need(intent['repository']==repo and intent['workflow']==WORKFLOW and intent['inputs']['expected_execution_commit']==active['main'],
                 'OWN_START_DISPATCH_IDENTITY_CHANGED')
            while True:
                need(time.time()<deadline,'OWN_START_TERMINAL_NOT_CONFIRMED')
                run=self.io.gh(repo,'actions/runs/'+str(run_id))
                need(run['id']==run_id and run['head_sha']==active['main'] and run['head_branch']=='main'
                     and run['event']=='workflow_dispatch' and run['run_attempt']==1 and run['path']=='.github/workflows/'+WORKFLOW,
                     'OWN_START_WORKFLOW_IDENTITY_CHANGED')
                if run['status']=='completed':
                    journal.put('settled-start-'+label+'.json',{k:run[k] for k in ('id','head_sha','status','conclusion')});break
                cancel_name='cancel-start-'+label
                if journal.read(cancel_name+'.intent.json') is None:
                    journal.put(cancel_name+'.intent.json',{'workflowRunId':run_id,'headSha':active['main']})
                    try:
                        self.io.command(['gh','api','repos/'+repo+'/actions/runs/'+str(run_id)+'/cancel',
                            '--method','POST','-H','X-GitHub-Api-Version:2026-03-10'])
                        journal.put(cancel_name+'.response.json',{'requestAccepted':True,'workflowRunId':run_id})
                    except AuthStop:raise
                    except Exception:pass  # Existing known run is observed; cancellation is not resubmitted.
                time.sleep(15)

    def retained_snapshot(self):
        source=parse(self.io.get(DATASET_BUCKET,self.saved['sourceProvenance']));self.snapshot.validate_source(source)
        snapshot=source['snapshot'];row=self.io.aws('rds','describe-db-snapshots','--db-snapshot-identifier',snapshot['identifier'])['DBSnapshots']
        need(len(row)==1,'RETAINED_BASELINE_SNAPSHOT_NOT_FOUND')
        observed={'snapshot':row[0],'tags':self.io.aws('rds','list-tags-for-resource','--resource-name',snapshot['arn'])['TagList'],
            'attributes':self.io.aws('rds','describe-db-snapshot-attributes','--db-snapshot-identifier',snapshot['identifier'])['DBSnapshotAttributesResult']['DBSnapshotAttributes']}
        need(self.snapshot.snapshot_projection(observed)==snapshot,'RETAINED_BASELINE_SNAPSHOT_CHANGED')

    def destroy(self):
        need(self.saved is not None,'SAVED_BASELINE_CONFIG_REQUIRED')
        active=self.base.read('active-destroy.json')
        if active is None:
            active={'operationId':'destroy-'+uuid.uuid4().hex[:20],'profile':self.profile,
                'configurationSha256':sha(encoded(self.saved)),'deadlineEpoch':int(time.time())+21600}
            self.base.put('active-destroy.json',active)
        need(active['profile']==self.profile and active['configurationSha256']==sha(encoded(self.saved)),
             'ACTIVE_DESTROY_CONFIGURATION_CHANGED')
        journal=Journal(self.base.path/active['operationId']);deadline=active['deadlineEpoch']
        self.settle_start_before_destroy(journal,deadline)
        intent=journal.read('down.intent.json');workflow=None;run=None
        if intent is not None:
            # Reuse the exact returned run, or fail closed on its unknown dispatch.
            inputs=intent['inputs'];run=inputs['run_id'];workflow=self.dispatch(journal,'down',inputs,deadline)
        else:
            _,state=self.backend()
            if self.power.resources(state):
                run=self.power.outputs(state)['run_identity']['run_id'];original,_=self.read_run(run)
                need(original['dnsMode']=='direct-only','DIRECT_ONLY_LAB_REQUIRED')
                inputs={'action':'down','run_id':run,'mode':'performance','policy':'isolated-read','dns_mode':'direct-only','force':False,
                    'expected_execution_commit':self.current_main(),'approved_execution_deadline_epoch':str(original['approvedExecutionDeadlineEpoch'])}
                workflow=self.dispatch(journal,'down',inputs,deadline)
        _,after=self.backend();need(not self.power.resources(after),'DESTROY_NOT_EMPTY')
        self.retained_snapshot()
        result={'state':'LAB_DESTROYED' if workflow else 'LAB_ABSENT','runId':run,
            'workflowRunId':workflow['id'] if workflow else None,'manualSnapshotPreserved':True,'foundationPreserved':True,'ociUnchangedByThisCommand':True}
        journal.put('result.json',result)
        for name in ('active-start.json','active-destroy.json'):
            if (self.base.path/name).exists():(self.base.path/name).unlink()
        sync_directory(self.base.path)
        return result


def counts_with_auth(action,guard):
    auth=[False]
    def checked():
        try:return guard()
        except BaseException as error:
            if isinstance(error,AuthStop) or str(error).startswith('AWS_AUTHENTICATION'):auth[0]=True
            raise
    try:return action(checked)
    except BaseException:
        if auth[0]:raise AuthStop('AWS_AUTHENTICATION_UNAVAILABLE_STOP') from None
        raise


class CountDB:
    def __init__(self,connection,guard,tables):
        self.db,self.guard=connection,guard
        self.allowed={"SELECT @@version AS mysqlVersion, @@server_uuid AS serverUuid, DATABASE() AS schemaName;",
            "SHOW SESSION STATUS LIKE 'Ssl_cipher';",
            "SELECT table_name AS tableName FROM information_schema.tables WHERE table_schema='airbobdb' AND table_type='BASE TABLE' ORDER BY table_name;"}
        for name in tables:self.allowed.update(('SELECT COUNT(*) AS rowCount FROM `airbobdb`.`'+name+'`;','SHOW CREATE TABLE `airbobdb`.`'+name+'`;'))
    def execute(self,sql):
        need(sql in self.allowed,'QUERY_OUTSIDE_COUNTS_DDL_SCOPE');box={}
        def query():
            try:
                with self.db.cursor() as cursor:
                    cursor.execute(sql);rows=cursor.fetchall();need(len(rows)<=32,'SQL_RESULT_ROW_BOUND')
                    header='\t'.join(x[0] for x in cursor.description)
                    result=header+'\n'+''.join('\t'.join('NULL' if x is None else str(x) for x in row)+'\n' for row in rows)
                    need(len(result.encode())<=2*1024*1024,'SQL_RESULT_BYTE_BOUND');box['result']=result
            except BaseException:box['failed']=True
        thread=threading.Thread(target=query,daemon=True);thread.start()
        try:
            while thread.is_alive():thread.join(10);self.guard()
        except BaseException:
            with contextlib.suppress(Exception):self.db._sock.shutdown(socket.SHUT_RDWR)
            thread.join(5);raise
        need(not box.get('failed'),'COUNT_QUERY_UNCONFIRMED');return box['result']


class Tunnel:
    def __init__(self,io,journal,runner,instance,restored,guard):
        self.io,self.journal,self.runner,self.instance,self.restored,self.guard=io,journal,runner,instance,restored,guard
        self.port=13306;self.proc=None;self.session=None;self.buffers={};self.started=time.monotonic()
    def drain(self):
        for stream,buffer in self.buffers.items():
            while True:
                try:raw=os.read(stream.fileno(),65536)
                except BlockingIOError:break
                if not raw:break
                buffer.extend(raw);need(sum(map(len,self.buffers.values()))<1024*1024,'SSM_OUTPUT_BOUND')
    def check(self):
        self.drain();need(self.proc.poll() is None,'OWN_TUNNEL_ENDED')
    def __enter__(self):
        self.guard();need(shutil.which('session-manager-plugin') is not None,'INSTALL_SESSION_MANAGER_PLUGIN')
        with socket.socket() as probe:need(probe.connect_ex(('127.0.0.1',self.port))!=0,'LOCAL_MYSQL_PORT_ALREADY_USED')
        op=self.restored['operation'];identity=self.io.aws('sts','get-caller-identity')
        rows=[x for r in self.io.aws('ec2','describe-instances','--instance-ids',self.instance)['Reservations'] for x in r['Instances']]
        need(len(rows)==1 and rows[0]['State']['Name']=='running','EXACT_RUNNING_NAT_REQUIRED')
        tags={x['Key']:x['Value'] for x in rows[0]['Tags']}
        need(tags.get('RunId')==op['runId'] and tags.get('FencingToken')==str(op['resourceFence']) and tags.get('ExpiresAt')==str(op['window']['expiresAt']),'NAT_TARGET_CHANGED')
        request={'target':self.instance,'documentName':'AWS-StartPortForwardingSessionToRemoteHost',
            'parameters':{'host':[self.restored['target']['endpoint']],'portNumber':['3306'],'localPortNumber':[str(self.port)]}}
        self.journal.put('counts-tunnel-intent.json',{'request':request,'runId':op['runId'],'resourceFence':op['resourceFence']})
        argv=['aws','--profile',self.io.profile,'--region',REGION,'--no-cli-pager','ssm','start-session','--target',self.instance,
            '--document-name',request['documentName'],'--parameters',json.dumps(request['parameters'])]
        self.proc=subprocess.Popen(argv,env=self.io.env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,start_new_session=True)
        self.buffers={self.proc.stdout:bytearray(),self.proc.stderr:bytearray()}
        for stream in self.buffers:os.set_blocking(stream.fileno(),False)
        try:
            while time.monotonic()-self.started<60:
                self.guard();self.check()
                match=re.search(rb'Starting session with SessionId: ([A-Za-z0-9_-]+)',self.buffers[self.proc.stdout])
                if match:
                    session=match[1].decode()
                    sessions=[x for x in self.io.aws('ssm','describe-sessions','--state','Active')['Sessions'] if x['SessionId']==session]
                    need(len(sessions)==1 and sessions[0]['Target']==self.instance and sessions[0]['Owner']==identity['Arn']
                         and sessions[0]['DocumentName']==request['documentName'] and sessions[0]['Status']=='Connected','SSM_SESSION_IDENTITY_CHANGED')
                    with socket.socket() as probe:ready=probe.connect_ex(('127.0.0.1',self.port))==0
                    if ready:
                        # Listener must be a descendant of the exact launched CLI process.
                        raw=self.io.command(['lsof','-nP','-iTCP:'+str(self.port),'-sTCP:LISTEN','-Fp'])[1]
                        pids={int(x[1:]) for x in raw.decode().splitlines() if x.startswith('p')}
                        rows=[tuple(map(int,x.split())) for x in self.io.command(['ps','-axo','pid=,ppid=,uid='])[1].decode().splitlines()]
                        own={self.proc.pid}
                        for _ in range(8):own.update(pid for pid,parent,uid in rows if parent in own and uid==os.getuid())
                        need(len(pids)==1 and pids<=own,'SSM_LISTENER_NOT_OWNED')
                        self.session=session;self.journal.put('counts-tunnel-ready.json',{'request':request,'sessionId':session,'owner':identity['Arn'],'listenerPid':next(iter(pids))})
                        return self
                time.sleep(1)
            raise Rejected('SSM_SESSION_START_UNCONFIRMED')
        except BaseException:self.__exit__(None,None,None);raise
    def __exit__(self,*_):
        if self.proc and self.proc.poll() is None:
            os.killpg(self.proc.pid,signal.SIGINT)
            try:self.proc.wait(timeout=10)
            except subprocess.TimeoutExpired:os.killpg(self.proc.pid,signal.SIGTERM);self.proc.wait(timeout=10)
        if self.proc:
            self.proc.stdout.close();self.proc.stderr.close()


def present(value):
    state=value['state']
    labels={'LAB_READY':'실험 환경이 준비됐습니다.','LAB_ABSENT':'실험 자원이 없습니다.',
            'POWER_PAUSED':'실험 환경을 일시 정지했습니다. DB 변경 내용은 보존됩니다.',
            'POWER_RESUMED':'동일한 실험 환경을 다시 켰습니다.',
            'LAB_DESTROYED':'임시 실험 자원을 삭제했습니다. 보관용 B 스냅샷은 유지했습니다.',
            'USER_ACCESS_UPDATED':'현재 위치에서 AWS 접속과 준비 상태를 확인했습니다.'}
    if state=='POWER_OBSERVED':
        phase=value.get('phase');print('상태: '+('실행 중' if phase in (None,'running') else '일시 정지' if phase=='stopped' else '전환 중 ('+phase+')'))
    else:print(labels.get(state,'상태: '+str(value.get('phase') or state)))
    if value.get('expiresAt'):
        print('만료: '+dt.datetime.fromtimestamp(int(value['expiresAt']),dt.timezone.utc).strftime('%Y-%m-%d %H:%M:%S UTC'))
    if 'requestToAvailableSeconds' in value:
        print('RDS 복원: %.2f분 · RDS 준비 후 앱 사용 가능까지: %.2f분' %
              (value['requestToAvailableSeconds']/60,value['availableToUsableAppSeconds']/60))
    access=value.get('access',value)
    if access.get('awsAlbDnsName'):print('AWS ALB: '+access['awsAlbDnsName'])
    if access.get('readinessCommand'):print('AWS API 확인: '+access['readinessCommand'])
    if value.get('runId'):print('실행: '+value['runId'])


def main(argv=None):
    parser=argparse.ArgumentParser(description='저장된 B 스냅샷으로 실험 환경을 켜고, 정지·재시작·삭제합니다.')
    parser.add_argument('action',choices=['start','pause','resume','destroy','status','access'])
    parser.add_argument('--config',type=Path,default=DEFAULT_CONFIG)
    parser.add_argument('--profile')
    parser.add_argument('--ttl-hours',type=int,help='New target only, 6..168 hours; default24 in saved config; an existing approval cap wins')
    parser.add_argument('--approved-deadline',type=int,help='Explicit UTC epoch cap for this start; never extends an existing saved cap or retained target')
    parser.add_argument('--cidr',help='access/start: current public IPv4 /32; otherwise detected by the power helper')
    parser.add_argument('--json',action='store_true',help='출력 결과와 증거 참조를 JSON으로 표시')
    args=parser.parse_args(argv)
    try:
        saved=config(args.config);profile=args.profile or (saved or {}).get('profile','admin-eeoos')
        need(args.ttl_hours is None or args.action=='start','TTL_ONLY_APPLIES_TO_NEW_START')
        need(args.approved_deadline is None or args.action=='start','DEADLINE_ONLY_APPLIES_TO_NEW_START')
        need(args.cidr is None or args.action in ('start','access'),'CIDR_ONLY_APPLIES_TO_ACCESS')
        directory=(saved or {}).get('evidenceDirectory',str(Path.home()/'.local/state/airbob-lab'))
        lab=Lab(saved,profile,directory)
        fd=os.open(lab.base.path/'.cli.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
        with os.fdopen(fd,'a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            if args.action=='start':result=lab.start(args.ttl_hours,args.cidr,args.approved_deadline)
            elif args.action=='destroy':result=lab.destroy()
            elif args.action=='access':result=lab.access(args.cidr)
            else:result=lab.power_action(args.action,args.cidr)
        if args.json:print(json.dumps(result,sort_keys=True))
        else:present(result)
        return 0
    except (Exception,KeyboardInterrupt) as error:
        code=str(error) if isinstance(error,Rejected) else type(error).__name__
        if type(error).__name__=='Rejected' and re.fullmatch(r'[A-Z0-9_]+',str(error)):code=str(error)
        auth=isinstance(error,AuthStop) or code.startswith('AWS_AUTHENTICATION')
        result={'state':'LAB_ACTION_INCOMPLETE_RESOURCES_RETAINED','authenticationRefreshRequired':auth,'failureCode':code}
        if args.json:print(json.dumps(result),file=sys.stderr)
        else:
            print('작업이 완료되지 않았습니다. 진행 기록과 자원을 보존했습니다. 원인: '+code,file=sys.stderr)
            if auth:print('AWS 인증 갱신이 필요합니다.',file=sys.stderr)
        return 1


if __name__=='__main__':raise SystemExit(main())
