#!/usr/bin/env python3
"""Bounded B ASG 1→2→1 observation, preserving the exact baseline instance.

CLI: --config CONFIG --output NEW_PRIVATE_DIR [--preflight-only]
Resume: same original contract + resume:{path,sha256}, a new lease/deadline;
resume performs cleanup only and never starts another scale-out.

AWS semantics: https://docs.aws.amazon.com/autoscaling/ec2/userguide/ec2-auto-scaling-instance-protection.html
Scale-in protection does not prevent health-check replacement or manual termination.
"""
from __future__ import annotations
import argparse
import base64
import contextlib
import datetime as dt
import fnmatch
import hashlib
import http.client
import json
import math
import os
from pathlib import Path
import re
import signal
import socket
import ssl
import statistics
import threading
import time

import growth_b_aws_restore as restore
import growth_b_service as service

ACCOUNT, REGION = service.ACCOUNT, service.REGION
KIND='global-growth-b-asg-probe'
POLICY=f'arn:aws:iam::{ACCOUNT}:policy/airbob-lab-operator-app-compute'
CLEANUP_ACTIONS={'autoscaling:SetInstanceProtection','autoscaling:TerminateInstanceInAutoScalingGroup'}
COMPLETE='OBSERVATION_FINISHED_BASELINE_RESTORED'
FAILED_CLEAN='OBSERVATION_FAILED_BASELINE_RESTORED'
RETAINED='CLEANUP_REQUIRED_PROTECTION_RETAINED'
HEX=re.compile('[0-9a-f]{64}')
INSTANCE=re.compile('i-[0-9a-f]{17}')
TERMINAL_ACTIVITIES={'Successful','Failed','Cancelled'}
METRICS=('CPUUtilization','DatabaseConnections','FreeableMemory','ReadLatency','WriteLatency','ReadIOPS','WriteIOPS','DiskQueueDepth')


def need(ok, code):
    if not ok: raise RuntimeError(code)


def now(): return dt.datetime.now(dt.timezone.utc).isoformat()
def epoch(value): return dt.datetime.fromisoformat(value.replace('Z','+00:00')).timestamp()
def sha(path): return service.sha(path)
def read(path): return service.read(path)
def canonical(value): return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
def error_code(error): return str(error) if isinstance(error,RuntimeError) and re.fullmatch('[A-Z0-9_]+',str(error)) else type(error).__name__


def write(path, value):
    path=Path(path); need(not path.is_symlink(),'UNSAFE_OUTPUT')
    temp=path.with_name(path.name+'.'+os.urandom(6).hex()+'.tmp')
    with os.fdopen(os.open(temp,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as stream:
        json.dump(value,stream,indent=2,sort_keys=True); stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    os.replace(temp,path)


def local_ref(value):
    need(set(value)=={'path','sha256'} and Path(value['path']).is_absolute() and HEX.fullmatch(value['sha256']),'EXACT_OWN_RECEIPT_REFERENCE_REQUIRED')
    path=Path(value['path']); need(path.is_file() and not path.is_symlink() and path.stat().st_mode&0o077==0 and sha(path)==value['sha256'],'OWN_RECEIPT_CHANGED_OR_NOT_PRIVATE')
    return path


def tags(values):
    result={row['Key']:row['Value'] for row in values}; need(len(result)==len(values),'DUPLICATE_RESOURCE_TAG'); return result


def validate_config(value):
    fields={'schemaVersion','kind','operationId','datasetId','runId','serviceRelease','manifest','readiness','application','asg',
            'resourceFencingToken','lease','expiresAt','approvedExecutionDeadlineEpoch','deadlineEpoch','cleanupPolicy','http','timing'}
    need(set(value) in (fields,fields|{'resume'}) and value['schemaVersion']==1 and value['kind']==KIND,'EXACT_ASG_PROBE_CONFIG_REQUIRED')
    need(re.fullmatch('lab-[a-z0-9][a-z0-9-]{0,27}',value['runId']) and re.fullmatch('global-growth-b-[0-9a-f]{16}',value['datasetId'])
         and re.fullmatch('[a-z0-9][a-z0-9-]{2,47}',value['serviceRelease']) and re.fullmatch('[a-z0-9][a-z0-9-]{2,47}',value['operationId']),'INVALID_PROBE_COORDINATES')
    service.ref(value['manifest'],f"datasets/{value['datasetId']}-aws-service/{value['serviceRelease']}/")
    service.ref(value['readiness'],f"data-bootstrap/{value['runId']}/")
    need(value['manifest']['key'].endswith('/aws-service.json'),'EXACT_B_SERVICE_MANIFEST_REQUIRED')
    app=value['application']; need(set(app)=={'image','mainCommit','appJarSha256','migrationFilesSha256'} and re.fullmatch(f'{ACCOUNT}[.]dkr[.]ecr[.]{REGION}[.]amazonaws[.]com/airbob-repo@sha256:[0-9a-f]{{64}}',app['image'])
         and re.fullmatch('[0-9a-f]{40}',app['mainCommit']) and all(HEX.fullmatch(app[k]) for k in ('appJarSha256','migrationFilesSha256')),'PINNED_CURRENT_B_APPLICATION_REQUIRED')
    asg=value['asg']; need(set(asg)=={'name','arn','launchTemplate','baselineInstanceId','baselineLaunchTime','originalProtection','amiId','runtimeRevision','targetGroupArn','albArn','albDnsName'},'EXACT_ASG_BASELINE_REQUIRED')
    need(asg['name']=='airbob-'+value['runId']+'-app' and re.fullmatch(f'arn:aws:autoscaling:{REGION}:{ACCOUNT}:autoScalingGroup:[0-9a-f-]{{36}}:autoScalingGroupName/'+re.escape(asg['name']),asg['arn'])
         and INSTANCE.fullmatch(asg['baselineInstanceId']) and type(asg['originalProtection']) is bool and re.fullmatch('ami-[0-9a-f]{17}',asg['amiId']) and HEX.fullmatch(asg['runtimeRevision']),'EXACT_ASG_BASELINE_REQUIRED')
    need(epoch(asg['baselineLaunchTime'])<time.time(),'BASELINE_LAUNCH_TIME_REQUIRED')
    lt=asg['launchTemplate']; need(set(lt)=={'id','version','dataSha256'} and re.fullmatch('lt-[0-9a-f]{17}',lt['id']) and re.fullmatch('[1-9][0-9]*',lt['version']) and HEX.fullmatch(lt['dataSha256']),'NUMERIC_PINNED_LAUNCH_TEMPLATE_REQUIRED')
    need(re.fullmatch(f'arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:targetgroup/airbob-lab-[a-z0-9-]+/[0-9a-f]{{16}}',asg['targetGroupArn'])
         and re.fullmatch(f'arn:aws:elasticloadbalancing:{REGION}:{ACCOUNT}:loadbalancer/app/airbob-lab-[a-z0-9-]+/[0-9a-f]{{16}}',asg['albArn'])
         and re.fullmatch(r'[a-z0-9-]+[.]ap-northeast-2[.]elb[.]amazonaws[.]com',asg['albDnsName']),'EXACT_REGIONAL_ALB_REQUIRED')
    lease=value['lease']; need(set(lease)=={'table','lockName','owner','runId','command','fencingToken'} and lease['table']=='airbob-performance-lab-orchestration-lease'
         and lease['lockName']=='airbob-performance-lab' and lease['runId']==value['runId'] and lease['command'] in {'up','measurement'}
         and re.fullmatch('[A-Za-z0-9._:-]{1,200}',lease['owner']) and type(lease['fencingToken']) is int and lease['fencingToken']>0,'EXACT_LIVE_CONTROLLER_LEASE_REQUIRED')
    need(type(value['resourceFencingToken']) is int and value['resourceFencingToken']>0,'ORIGINAL_RESOURCE_FENCE_REQUIRED')
    timing=value['timing']; need(set(timing)=={'baselineSeconds','pollSeconds','healthySeconds','cleanupReserveSeconds','stableSeconds'} and all(type(n)is int for n in timing.values())
         and 60<=timing['baselineSeconds']<=300 and 15<=timing['pollSeconds']<=60 and 30<=timing['healthySeconds']<=180
         and 600<=timing['cleanupReserveSeconds']<=1200 and 60<=timing['stableSeconds']<=180,'BOUNDED_BASELINE_OBSERVATION_AND_CLEANUP_REQUIRED')
    current=int(time.time()); need(all(type(value[k])is int for k in ('expiresAt','approvedExecutionDeadlineEpoch','deadlineEpoch'))
         and current+timing['cleanupReserveSeconds']+60<value['deadlineEpoch']<=min(current+3600,value['expiresAt'],value['approvedExecutionDeadlineEpoch']),'ONE_HOUR_WITH_UNEXTENDED_RESOURCE_EXPIRY_REQUIRED')
    http=value['http']; need(set(http)=={'publicHost','detailPath','timeoutSeconds','maximumRequests'} and re.fullmatch('[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?',http['publicHost'])
         and '.' in http['publicHost'] and re.fullmatch('/api/v1/accommodations/[1-9][0-9]{0,18}',http['detailPath'])
         and type(http['timeoutSeconds'])is int and 1<=http['timeoutSeconds']<=5 and type(http['maximumRequests'])is int and 10<=http['maximumRequests']<=500,'BOUNDED_PUBLIC_V1_DETAIL_GET_REQUIRED')
    policy=value['cleanupPolicy']; need(set(policy)=={'policyArn','defaultVersionId','documentSha256'} and policy['policyArn']==POLICY
         and re.fullmatch('v[1-9][0-9]*',policy['defaultVersionId']) and HEX.fullmatch(policy['documentSha256']),'EXACT_LIVE_CLEANUP_POLICY_REQUIRED')
    if 'resume' in value: local_ref(value['resume'])
    return value


def helper_sources():
    return {Path(module.__file__).name:sha(module.__file__) for module in (restore,service,service.app_runtime)}


def contract_sha(config):
    return canonical({k:v for k,v in config.items() if k not in {'lease','deadlineEpoch','resume'}})


def policy_admission(aws,config):
    selected=config['cleanupPolicy']
    attached=aws.call('iam','list-attached-role-policies','--role-name','airbob-lab-operator')['AttachedPolicies']
    need(any(row['PolicyArn']==POLICY for row in attached),'CLEANUP_POLICY_NOT_ATTACHED_TO_CALLING_ROLE')
    policy=aws.call('iam','get-policy','--policy-arn',POLICY)['Policy']
    need(policy['Arn']==POLICY and policy['DefaultVersionId']==selected['defaultVersionId'],'CLEANUP_POLICY_DEFAULT_VERSION_CHANGED')
    doc=aws.call('iam','get-policy-version','--policy-arn',POLICY,'--version-id',selected['defaultVersionId'])['PolicyVersion']['Document']
    need(canonical(doc)==selected['documentSha256'],'CLEANUP_POLICY_DOCUMENT_CHANGED')
    allowed=set()
    for statement in doc['Statement']:
        actions=statement.get('Action',[]); actions=[actions] if isinstance(actions,str) else actions
        resources=statement.get('Resource',[]); resources=[resources] if isinstance(resources,str) else resources
        matches={action for action in CLEANUP_ACTIONS if any(fnmatch.fnmatchcase(action,p) for p in actions)}
        if matches and any(fnmatch.fnmatchcase(config['asg']['arn'],pattern) for pattern in resources):
            namespace=f'arn:aws:autoscaling:{REGION}:{ACCOUNT}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*'
            need(set(resources)<={namespace,config['asg']['arn']},'CLEANUP_POLICY_RESOURCE_BOUNDARY_CHANGED')
            need(statement['Effect']=='Allow' and not statement.get('NotAction') and not statement.get('NotResource'),'CLEANUP_PERMISSION_DENIED')
            condition=statement.get('Condition',{}); strings=condition.get('StringEquals',{}); null=condition.get('Null',{})
            need(all(strings.get('aws:ResourceTag/'+k)==v for k,v in {'Project':'airbob','Environment':'performance-lab','ManagedBy':'terraform','Persistence':'ephemeral','Stack':'lab'}.items())
                 and all(str(null.get('aws:ResourceTag/'+key)).lower()=='false' for key in ('RunId','FencingToken','ExpiresAt')),'CLEANUP_POLICY_TAG_BOUNDARY_MISSING')
            allowed|=matches
    need(allowed==CLEANUP_ACTIONS,'REQUIRED_EXACT_INSTANCE_CLEANUP_ACTIONS_NOT_GRANTED')
    return {'policyArn':POLICY,'defaultVersionId':selected['defaultVersionId'],'documentSha256':canonical(doc),'allowContractVerified':True,'effectivePermissionSimulationPerformed':False}


def resource_tags(config,rows,service_name=None):
    value=tags(rows); expected={'Project':'airbob','Environment':'performance-lab','Stack':'lab','ManagedBy':'terraform','Persistence':'ephemeral',
        'RunId':config['runId'],'FencingToken':str(config['resourceFencingToken']),'ExpiresAt':str(config['expiresAt'])}
    if service_name: expected['Service']=service_name
    if service_name=='app': expected['RuntimeRevision']=config['asg']['runtimeRevision']
    need(all(value.get(k)==v for k,v in expected.items()),'RESOURCE_RUN_FENCE_OR_EXPIRY_CHANGED')
    return value


def group(aws,config):
    data=aws.call('autoscaling','describe-auto-scaling-groups','--auto-scaling-group-names',config['asg']['name'])['AutoScalingGroups']
    need(len(data)==1 and data[0]['AutoScalingGroupARN']==config['asg']['arn'],'ASG_IDENTITY_CHANGED'); return data[0]


def static_group(row):
    ignored={'Instances','MinSize','MaxSize','DesiredCapacity','PredictedCapacity'}
    result={k:v for k,v in row.items() if k not in ignored}
    for key,field in (('Tags','Key'),('EnabledMetrics','Metric')):
        if key in result: result[key]=sorted(result[key],key=lambda item:item[field])
    return result


def member_ids(row):
    result={v['InstanceId'] for v in row['Instances']}; need(len(result)==len(row['Instances']),'ASG_DUPLICATE_MEMBER'); return result


def ec2_instance(aws,config,identifier):
    rows=aws.call('ec2','describe-instances','--instance-ids',identifier)['Reservations']
    instances=[item for reservation in rows for item in reservation['Instances']]
    need(len(instances)==1 and instances[0]['InstanceId']==identifier,'EXACT_INSTANCE_UNAVAILABLE')
    item=instances[0]; resource_tags(config,item['Tags'],'app')
    need(item['ImageId']==config['asg']['amiId'] and item['InstanceType']=='c6i.large' and item.get('InstanceLifecycle','on-demand')=='on-demand','INSTANCE_AMI_OR_TYPE_CHANGED')
    return item


def target_health(aws,config):
    result=aws.call('elbv2','describe-target-health','--target-group-arn',config['asg']['targetGroupArn'])['TargetHealthDescriptions']
    need(all(item['Target']['Port']==8080 for item in result),'ALB_TARGET_PORT_CHANGED')
    states={item['Target']['Id']:item['TargetHealth']['State'] for item in result}
    need(len(states)==len(result),'DUPLICATE_ALB_TARGET'); return states


def active_activities(aws,config):
    result=aws.call('autoscaling','describe-scaling-activities','--auto-scaling-group-name',config['asg']['name'],'--max-records','100')['Activities']
    return [item['ActivityId'] for item in result if item['StatusCode'] not in TERMINAL_ACTIVITIES]


def preflight(aws,config,root,lease):
    lease(force=True); caller=aws.call('sts','get-caller-identity')
    need(caller['Account']==ACCOUNT and re.fullmatch(f'arn:aws:sts::{ACCOUNT}:assumed-role/airbob-lab-operator/[A-Za-z0-9+=,.@_-]+',caller['Arn']),'EXACT_LAB_OPERATOR_SESSION_REQUIRED')
    permission=policy_admission(aws,config)
    manifest=service.fetch(aws,config['manifest'],root/'service-manifest.json',service.BUCKET)
    service.validate_manifest(manifest,config['datasetId'],config['runId'],config['serviceRelease'])
    need(manifest['application']==config['application'] and manifest['search']['documentFingerprint']['documents']==657358,'SELECTED_FINAL_B_APPLICATION_CHANGED')
    ready=service.fetch(aws,config['readiness'],root/'service-readiness.json',service.EVIDENCE)
    service.validate_readiness(ready,manifest,config['manifest']['sha256'])
    binding=root/'app-runtime-binding.json'
    service.fetch(aws,manifest['appRuntimeBinding'],binding,service.BUCKET)
    runtime=service.app_runtime.validate_runtime_binding(binding,config['application']['image'],config['application']['mainCommit'],config['application']['appJarSha256'])
    need(runtime==ready['appRuntime'],'RUNTIME_BINDING_AND_SERVICE_READINESS_DIFFER')
    row=group(aws,config); resource_tags(config,row['Tags'],'app'); selected=config['asg']
    need(row['LaunchTemplate']['LaunchTemplateId']==selected['launchTemplate']['id'] and row['LaunchTemplate']['Version']==selected['launchTemplate']['version']
         and row['TargetGroupARNs']==[selected['targetGroupArn']] and row['HealthCheckType']=='ELB' and row.get('CapacityRebalance',False) is False
         and len(row['AvailabilityZones'])==1 and len(row['VPCZoneIdentifier'].split(','))==1 and not row.get('MixedInstancesPolicy') and not row.get('LaunchConfigurationName') and not row.get('WarmPoolConfiguration') and row.get('WarmPoolSize',0)==0
         and row.get('NewInstancesProtectedFromScaleIn',False) is False and not row.get('SuspendedProcesses') and tags(row['Tags']).get('RuntimeRevision')==selected['runtimeRevision'],'NORMAL_SINGLE_AZ_B_ASG_REQUIRED')
    for command,key in [('describe-policies','ScalingPolicies'),('describe-scheduled-actions','ScheduledUpdateGroupActions'),('describe-lifecycle-hooks','LifecycleHooks')]:
        need(not aws.call('autoscaling',command,'--auto-scaling-group-name',selected['name'])[key],'COMPETING_ASG_AUTOMATION_PRESENT')
    refresh=aws.call('autoscaling','describe-instance-refreshes','--auto-scaling-group-name',selected['name'],'--max-records','100')['InstanceRefreshes']
    need(all(item['Status'] in {'Successful','Failed','Cancelled','RollbackSuccessful'} for item in refresh),'ACTIVE_INSTANCE_REFRESH')
    lt=aws.call('ec2','describe-launch-template-versions','--launch-template-id',selected['launchTemplate']['id'],'--versions',selected['launchTemplate']['version'])['LaunchTemplateVersions']
    need(len(lt)==1 and lt[0]['LaunchTemplateId']==selected['launchTemplate']['id'] and str(lt[0]['VersionNumber'])==selected['launchTemplate']['version']
         and canonical(lt[0]['LaunchTemplateData'])==selected['launchTemplate']['dataSha256'],'LAUNCH_TEMPLATE_BYTES_CHANGED')
    targets=aws.call('elbv2','describe-target-groups','--target-group-arns',selected['targetGroupArn'])['TargetGroups']
    need(len(targets)==1 and targets[0]['TargetType']=='instance' and targets[0]['Port']==8080 and targets[0]['Protocol']=='HTTP'
         and targets[0]['HealthCheckPath']=='/actuator/health' and targets[0]['LoadBalancerArns']==[selected['albArn']],'ALB_TARGET_GROUP_CHANGED')
    lbs=aws.call('elbv2','describe-load-balancers','--load-balancer-arns',selected['albArn'])['LoadBalancers']
    need(len(lbs)==1 and lbs[0]['DNSName']==selected['albDnsName'] and lbs[0]['State']['Code']=='active','EXACT_ALB_CHANGED')
    baseline=ec2_instance(aws,config,selected['baselineInstanceId'])
    need(epoch(baseline['LaunchTime'])==epoch(selected['baselineLaunchTime']) and baseline['State']['Name']=='running','BASELINE_INSTANCE_REPLACED')
    rds=aws.call('rds','describe-db-instances','--db-instance-identifier',manifest['rds']['identifier'])['DBInstances']
    need(len(rds)==1 and rds[0]['DbiResourceId']==manifest['rds']['resourceId'] and rds[0]['EngineVersion']=='8.4.11' and rds[0]['DBInstanceStatus']=='available','PREPARED_RDS_RESOURCE_CHANGED')
    resource_tags(config,rds[0]['TagList'])
    lease(force=True); return row,manifest,permission,runtime


class BoundedAws(restore.Aws):
    def __init__(self,deadline): self.deadline=deadline
    def call(self,*args):
        remaining=self.deadline-time.time(); need(remaining>0,'AWS_OPERATION_DEADLINE_REACHED')
        env=dict(os.environ,AWS_MAX_ATTEMPTS='1',AWS_RETRY_MODE='standard',AWS_CLI_AUTO_PROMPT='off')
        raw=restore.command(['aws','--region',REGION,'--no-cli-pager','--output','json','--cli-connect-timeout','5',
            '--cli-read-timeout',str(max(1,min(30,int(remaining)))),*args],env=env,timeout=min(45,remaining))
        return json.loads(raw or '{}')


HOST_SCRIPT=r'''import base64,datetime,hashlib,json,re,subprocess,time,urllib.request,urllib.error
expected=json.loads(base64.b64decode('__EXPECTED__'))
def call(args):
    result=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=10)
    if result.returncode: raise RuntimeError('HOST_READ_FAILED')
    return result.stdout.decode()
def need(ok,code):
    if not ok: raise RuntimeError(code)
def get(path,readiness=False):
    start=time.monotonic(); status=0; body=b''
    try:
        req=urllib.request.Request('http://127.0.0.1:8080'+path,method='GET',headers={'Connection':'close'})
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self,*args): return None
        with urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect()).open(req,timeout=expected['timeout']) as response:
            status=response.status; body=response.read(262145)
    except urllib.error.HTTPError as error: status=error.code
    except (OSError,TimeoutError): pass
    need(len(body)<=262144,'HTTP_RESPONSE_BOUND_EXCEEDED')
    up=False
    if readiness and status==200:
        try: up=json.loads(body).get('status')=='UP'
        except (ValueError,AttributeError): pass
    return {'status':status,'latencyMs':round((time.monotonic()-start)*1000,3),'bytes':len(body),'ready':up,'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat()}
result={'schemaVersion':1,'kind':'global-growth-b-asg-host-observation','instanceId':expected['instanceId'],'observedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'httpRequestCount':0,'available':False}
try:
    identifiers=call(['docker','ps','-q','--no-trunc','--filter','label=com.docker.compose.service=app']).splitlines()
    need(len(identifiers)<=1,'AMBIGUOUS_APPLICATION_CONTAINER')
    if identifiers:
        app=json.loads(call(['docker','inspect',identifiers[0]]))[0]
        env=dict(row.split('=',1) for row in app['Config']['Env'])
        need(env.get('SPRING_PROFILES_ACTIVE')=='aws' and env.get('RESERVATION_INVENTORY_STARTUP_ENABLED','true')=='true','NORMAL_AWS_INVENTORY_PROFILE_REQUIRED')
        need('SPRING_APPLICATION_JSON' not in env and not any(key.startswith('SPRING_CONFIG_') or key.startswith('SPRING_PROFILES_') and key!='SPRING_PROFILES_ACTIVE' for key in env),'UNREVIEWED_PROFILE_OVERRIDE')
        need(not any('spring.profiles' in env.get(k,'') or 'inventory.startup.enabled=false' in env.get(k,'') for k in ('JAVA_OPTS','JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS')),'UNREVIEWED_JVM_PROFILE_OVERRIDE')
        need(app['State']['Running'] and app['Config']['Image']==expected['image'],'PINNED_RUNNING_IMAGE_REQUIRED')
        image=json.loads(call(['docker','image','inspect',app['Image']]))[0]
        need(expected['image'] in image.get('RepoDigests',[]) and image['Config'].get('Labels',{}).get('org.opencontainers.image.revision')==expected['mainCommit'],'IMAGE_DIGEST_OR_COMMIT_CHANGED')
        jar=call(['docker','exec',identifiers[0],'sha256sum','/app/app.jar']).split()[0]
        need(jar==expected['imageJarSha256'],'RUNNING_APP_JAR_CHANGED')
        result.update(available=True,containerId=app['Id'],startedAt=app['State']['StartedAt'],imageId=app['Image'],image=expected['image'],appJarSha256=jar,normalProfile='aws')
        logs=call(['docker','logs','--since',app['State']['StartedAt'],'--timestamps','--tail','20001',identifiers[0]])
        need(len(logs.splitlines())<20001,'STARTUP_LOG_WINDOW_EXCEEDED')
        for line in logs.splitlines():
            found=re.search(r'예약 inventory startup bootstrap 완료: accommodations=(\d+) expectedDays=(\d+) missingDaysSubmitted=(\d+) insertStatements=(\d+) elapsedMs=(\d+)$',line)
            if found:
                need('AccommodationInventoryStartupBootstrap' in line[:found.start()],'STARTUP_LOGGER_IDENTITY_CHANGED')
                result['inventoryStartupComplete']={'logObservedAt':line.split(' ',1)[0],**dict(zip(('accommodations','expectedDays','missingDaysSubmitted','insertStatements','elapsedMs'),map(int,found.groups()))),'lineSha256':hashlib.sha256(line.encode()).hexdigest()}
        result['readiness']=get('/actuator/health/readiness',True); result['httpRequestCount']+=1
        if result['readiness']['ready']:
            result['detail']=get(expected['detailPath']); result['httpRequestCount']+=1
except Exception as error:
    result['failureCode']=str(error) if isinstance(error,RuntimeError) else type(error).__name__
result['completedAt']=datetime.datetime.now(datetime.timezone.utc).isoformat()
print(json.dumps(result,separators=(',',':')))
'''


class SsmReadProbe:
    """Fixed read-only host program; no starts, config writes, or secret lookups."""
    def __init__(self,aws,config,guard,sleep=time.sleep):
        self.aws,self.config,self.guard,self.sleep=aws,config,guard,sleep; self.runtime=None
    def __call__(self,identifier,deadline):
        self.guard(); need(self.runtime is not None,'VERIFIED_APP_RUNTIME_BINDING_REQUIRED')
        info=self.aws.call('ssm','describe-instance-information','--filters','Key=InstanceIds,Values='+identifier)['InstanceInformationList']
        if not info or info[0]['PingStatus']!='Online':
            return {'schemaVersion':1,'kind':'global-growth-b-asg-host-observation','instanceId':identifier,'observedAt':now(),'httpRequestCount':0,'available':False}
        need(len(info)==1 and info[0]['InstanceId']==identifier,'SSM_MANAGED_INSTANCE_IDENTITY_CHANGED')
        values=dict(self.config['application'],imageJarSha256=self.runtime['imageJarSha256'],instanceId=identifier,detailPath=self.config['http']['detailPath'],timeout=self.config['http']['timeoutSeconds'])
        encoded=base64.b64encode(json.dumps(values).encode()).decode()
        script="python3 - <<'AIRBOB_R7_READ_ONLY'\n"+HOST_SCRIPT.replace('__EXPECTED__',encoded)+"\nAIRBOB_R7_READ_ONLY"
        reply=self.aws.call('ssm','send-command','--document-name','AWS-RunShellScript','--instance-ids',identifier,
            '--timeout-seconds','60','--parameters',json.dumps({'commands':[script],'executionTimeout':['45']}))
        command=reply['Command']['CommandId']; need(re.fullmatch('[0-9a-f-]{36}',command),'SSM_COMMAND_ID_INVALID')
        until=min(deadline,time.time()+60)
        while time.time()<until:
            self.guard()
            rows=self.aws.call('ssm','list-command-invocations','--command-id',command,'--instance-id',identifier)['CommandInvocations']
            need(len(rows)<=1,'SSM_INVOCATION_AMBIGUOUS')
            if rows and rows[0]['Status'] in {'Success','Failed','Cancelled','TimedOut','Undeliverable','Terminated'}:
                need(rows[0]['Status']=='Success','READ_ONLY_SSM_PROBE_FAILED')
                result=self.aws.call('ssm','get-command-invocation','--command-id',command,'--instance-id',identifier)
                need(result['InstanceId']==identifier and result['CommandId']==command and result['ResponseCode']==0,'SSM_RESULT_IDENTITY_CHANGED')
                value=json.loads(result['StandardOutputContent']); need(value['instanceId']==identifier and value['kind']=='global-growth-b-asg-host-observation','HOST_OBSERVATION_IDENTITY_CHANGED')
                value['ssmCommandId']=command; return value
            self.sleep(min(2,max(0,until-time.time())))
        raise RuntimeError('READ_ONLY_SSM_PROBE_TIMEOUT')


class AlbGet:
    def __init__(self,config): self.config=config
    def __call__(self):
        config=self.config; start=time.monotonic(); status=0; count=0
        # Connect only to the described ALB while keeping the approved public
        # hostname for certificate verification and SNI; never use production DNS.
        class Connection(http.client.HTTPSConnection):
            def connect(self):
                sock=socket.create_connection((config['asg']['albDnsName'],443),self.timeout)
                self.sock=self._context.wrap_socket(sock,server_hostname=config['http']['publicHost'])
        connection=Connection(config['http']['publicHost'],timeout=config['http']['timeoutSeconds'],context=ssl.create_default_context())
        try:
            connection.request('GET',config['http']['detailPath'],headers={'Host':config['http']['publicHost'],'Connection':'close'})
            response=connection.getresponse(); status=response.status; count=len(response.read(262145)); need(count<=262144,'HTTP_RESPONSE_BOUND_EXCEEDED')
        except (OSError,TimeoutError,http.client.HTTPException): pass
        finally: connection.close()
        return {'status':status,'bytes':count,'latencyMs':round((time.monotonic()-start)*1000,3),'target':'exact-alb-public-detail','bodyRetained':False}


class CloudWatchDatabaseSampler:
    """RDS monitoring only. No SQL session, secret retrieval, or fabricated zeroes."""
    def __init__(self,aws,manifest): self.aws,self.identifier=aws,manifest['rds']['identifier']
    def __call__(self,phase):
        end=dt.datetime.now(dt.timezone.utc); values={}
        for metric in METRICS:
            reply=self.aws.call('cloudwatch','get-metric-statistics','--namespace','AWS/RDS','--metric-name',metric,
                '--dimensions','Name=DBInstanceIdentifier,Value='+self.identifier,'--start-time',(end-dt.timedelta(minutes=3)).isoformat(),
                '--end-time',end.isoformat(),'--period','60','--statistics','Average','Maximum')
            rows=sorted(reply.get('Datapoints',[]),key=lambda row:row['Timestamp'])
            values[metric]=[{'timestamp':row['Timestamp'],'average':row['Average'],'maximum':row['Maximum'],'unit':row['Unit']} for row in rows]
        return {'phase':phase,'observedAt':end.isoformat(),'available':any(values.values()),'source':'AWS/RDS CloudWatch',
                'windowSeconds':180,'periodSeconds':60,'overlappingWindows':True,'sqlLockWaitMeasurementsAvailable':False,'metrics':values}


def latency_summary(samples):
    result={}
    for phase in ('baseline','scale-out','healthy'):
        rows=[sample['baselineHost'].get('detail') for sample in samples if sample['phase']==phase]
        rows=[row for row in rows if row is not None]; numbers=sorted(row['latencyMs'] for row in rows)
        result[phase]={'requests':len(rows),'errors':sum(row['status']!=200 for row in rows),
            'p50Ms':statistics.median(numbers) if numbers else None,'p95Ms':numbers[max(0,(95*len(numbers)+99)//100-1)] if numbers else None,'maximumMs':max(numbers) if numbers else None}
    return result


class Probe:
    def __init__(self,config,output,aws=None,host_probe=None,db_sampler=None,public_get=None,lease=None,sleep=time.sleep):
        self.config,self.root=config,Path(output); self.aws=aws or BoundedAws(config['deadlineEpoch'])
        self.lease=lease or restore.Lease(self.aws,config['lease']); self.sleep=sleep
        self.host_probe=host_probe or SsmReadProbe(self.aws,config,self.guard_lease,sleep)
        self.db_sampler,self.public_get=db_sampler,public_get or AlbGet(config)
        self.samples=[]; self.cleanup_phase=False; self.source=None
        self.report={'schemaVersion':1,'kind':KIND,'state':'PREFLIGHT_STARTED','operationId':config['operationId'],'datasetId':config['datasetId'],'runId':config['runId'],
            'toolSha256':sha(__file__),'helperSources':helper_sources(),'contractSha256':contract_sha(config),'configurationSha256':canonical(config),'lease':config['lease'],
            'resourceFencingToken':config['resourceFencingToken'],'expiresAt':config['expiresAt'],'approvedExecutionDeadlineEpoch':config['approvedExecutionDeadlineEpoch'],
            'deadlineEpoch':config['deadlineEpoch'],'baselineInstanceId':config['asg']['baselineInstanceId'],'originalProtection':config['asg']['originalProtection'],
            'startedAt':now(),'events':[],'ownedInstanceIds':[],'terminationIntents':[],'scaleRequested':False,'protectionRequested':False,
            'serverAtomicCasAvailable':False,'comparisonBeforeAndAfterMutations':True,'baselineTerminatedByTool':False,'ttlExtended':False,
            'r7OverallComplete':False,'httpRequests':0,'databaseSqlWrites':False,'serviceConfigurationChanged':False,'cleanupComplete':False}

    def save(self): write(self.root/'asg-probe.json',self.report)
    def event(self,state,**fields):
        self.report.update(state=state,updatedAt=now(),**fields); self.report['events'].append({'state':state,'at':now()}); self.save()

    def guard_lease(self):
        self.lease(force=True); need(time.time()<self.config['deadlineEpoch']<=min(self.config['expiresAt'],self.config['approvedExecutionDeadlineEpoch']),'ORIGINAL_RESOURCE_OR_OPERATION_DEADLINE_ENDED')

    def snapshot(self,*,protected=None):
        self.guard_lease(); row=group(self.aws,self.config); resource_tags(self.config,row['Tags'],'app')
        need(canonical(static_group(row))==self.report['originalGroupStaticSha256'],'ASG_CONFIGURATION_DRIFT')
        need((row['MinSize'],row['DesiredCapacity'],row['MaxSize']) in {(1,1,1),(1,1,2),(1,2,2)},'ASG_CAPACITY_DRIFT')
        baseline=self.config['asg']['baselineInstanceId']; members=member_ids(row)
        need(baseline in members and len(members)<=2,'BASELINE_REPLACED_OR_MORE_THAN_ONE_ADDITIONAL_INSTANCE')
        original=next(v for v in row['Instances'] if v['InstanceId']==baseline)
        need(original['LifecycleState']=='InService' and original['HealthStatus']=='Healthy','BASELINE_NOT_HEALTHY_IN_SERVICE')
        if protected is not None: need(original['ProtectedFromScaleIn'] is protected,'BASELINE_SCALE_IN_PROTECTION_CHANGED')
        states=target_health(self.aws,self.config); need(states.get(baseline)=='healthy','BASELINE_ALB_NOT_HEALTHY')
        for member in row['Instances']:
            template=member.get('LaunchTemplate',{})
            need(template.get('LaunchTemplateId')==self.config['asg']['launchTemplate']['id'] and str(template.get('Version'))==self.config['asg']['launchTemplate']['version'],'INSTANCE_LAUNCH_TEMPLATE_CHANGED')
        other=members-{baseline}
        for identifier in other:
            need(self.report['scaleRequested'],'UNOWNED_ADDITIONAL_INSTANCE')
            item=ec2_instance(self.aws,self.config,identifier)
            need(epoch(item['LaunchTime'])>=self.report['scaleRequestedEpoch']-2,'ADDITIONAL_INSTANCE_PREDATES_OWN_SCALE_OUT')
            need(item['State']['Name'] in {'pending','running','shutting-down','terminated'},'UNEXPECTED_ADDED_INSTANCE_STATE')
            need(not self.report['ownedInstanceIds'] or identifier in self.report['ownedInstanceIds'],'REPLACEMENT_OR_UNOWNED_INSTANCE_DETECTED')
            if identifier not in self.report['ownedInstanceIds']:
                self.report['ownedInstanceIds'].append(identifier); self.report['newInstanceLaunchTime']=item['LaunchTime']; self.report['newInstanceFirstObservedAt']=now(); self.save()
        need(set(states)<=members|set(self.report['ownedInstanceIds']),'UNOWNED_ALB_TARGET_PRESENT')
        return row,states

    def mutate(self,state,callback,**intent):
        self.guard_lease(); self.event(state,**intent)
        callback()  # Intent is fsynced before every external mutation.
        self.guard_lease()

    def wait(self,seconds,deadline=None):
        end=min(self.config['deadlineEpoch'],deadline or self.config['deadlineEpoch'])
        need(time.time()<end,'PROBE_PHASE_DEADLINE_REACHED'); self.sleep(min(seconds,max(0,end-time.time())))

    def sample(self,phase,deadline):
        self.guard_lease(); row,states=self.snapshot(protected=True if self.report['protectionRequested'] else self.config['asg']['originalProtection'])
        baseline=self.config['asg']['baselineInstanceId']; budget=self.config['http']['maximumRequests']
        need(self.report['httpRequests']+5<=budget,'BOUNDED_HTTP_REQUEST_BUDGET_EXHAUSTED')
        original=self.host_probe(baseline,deadline); self.validate_host(original,baseline,required=True)
        self.report['httpRequests']+=original['httpRequestCount']
        sample={'observedAt':now(),'phase':phase,'baselineHost':original,'albTargetStates':states,'asgInstances':sorted(member_ids(row))}
        if self.report['ownedInstanceIds'] and self.report['ownedInstanceIds'][0] in member_ids(row):
            identifier=self.report['ownedInstanceIds'][0]; added=self.host_probe(identifier,deadline); self.validate_host(added,identifier,required=False)
            self.report['httpRequests']+=added['httpRequestCount']; sample['newHost']=added
            ready=added.get('readiness',{}).get('ready',False)
            if ready:
                need(added.get('inventoryStartupComplete',{}).get('accommodations')==657358,'FINAL_B_INVENTORY_STARTUP_LOG_REQUIRED')
                if 'inventoryReadyFirstObservedAt' not in self.report:
                    self.report['newAppStartedAt']=added['startedAt']; self.report['inventoryReadyFirstObservedAt']=added['readiness']['observedAt']
                    self.report['inventoryReadinessEvidence']={'instanceId':identifier,'containerId':added['containerId'],'ssmCommandId':added.get('ssmCommandId'),'startupLog':added['inventoryStartupComplete'],'responseObservedAt':added['readiness']['observedAt']}
                    self.report['containerToReadinessUpperBoundSeconds']=epoch(added['readiness']['observedAt'])-epoch(added['startedAt'])
            states=target_health(self.aws,self.config); sample['albTargetStatesAfterHostProbe']=states
            if states.get(identifier)=='healthy':
                if 'albHealthyFirstObservedAt' not in self.report:
                    self.report['albHealthyFirstObservedAt']=now(); self.report['albHealthEvidence']={'targetGroupArn':self.config['asg']['targetGroupArn'],'targetInstanceId':identifier,'port':8080,'state':'healthy','observedAt':self.report['albHealthyFirstObservedAt']}
                    self.report['timestampsAreFirstObservationsNotExactTransitionEvents']=True
                need(ready,'ALB_HEALTHY_BEFORE_VERIFIED_INVENTORY_READINESS')
                self.report['newHostAndAlbReady']=True
        sample['albDetail']=self.public_get(); self.report['httpRequests']+=1
        if phase=='baseline':
            need(original.get('detail',{}).get('status')==200 and sample['albDetail'].get('status')==200,'HEALTHY_BASELINE_PUBLIC_DETAIL_REQUIRED')
        try: sample['database']=self.db_sampler(phase) if self.db_sampler else {'available':False,'reason':'NO_READ_ONLY_DATABASE_ADAPTER'}
        except Exception as error: sample['database']={'available':False,'reason':error_code(error)}
        self.samples.append(sample); write(self.root/'samples.json',self.samples)
        self.report['baselineLatency']=latency_summary(self.samples); self.report['samples']=len(self.samples)
        self.report['databaseMetricsAvailable']=any(v['database'].get('available') is True for v in self.samples)
        self.report['dbMetricsAreSqlLockMeasurements']=False
        self.save(); return self.report.get('newHostAndAlbReady',False)

    def validate_host(self,result,identifier,*,required):
        allowed={'schemaVersion','kind','instanceId','observedAt','completedAt','httpRequestCount','available','containerId','startedAt','imageId','image','appJarSha256','normalProfile','readiness','detail','ssmCommandId','inventoryStartupComplete','failureCode'}
        need(set(result)<=allowed,'UNEXPECTED_HOST_PAYLOAD_FIELD')
        for key in ('readiness','detail'):
            if key in result:
                value=result[key]
                need(set(value)=={'status','latencyMs','bytes','ready','observedAt'} and type(value['status'])is int and 0<=value['status']<=599
                     and type(value['bytes'])is int and 0<=value['bytes']<=262144 and type(value['ready'])is bool
                     and isinstance(value['latencyMs'],(int,float)) and math.isfinite(value['latencyMs']) and value['latencyMs']>=0,'CLOSED_HTTP_OBSERVATION_REQUIRED')
                epoch(value['observedAt'])
        if 'inventoryStartupComplete' in result:
            startup=result['inventoryStartupComplete']
            need(set(startup)=={'logObservedAt','accommodations','expectedDays','missingDaysSubmitted','insertStatements','elapsedMs','lineSha256'}
                 and HEX.fullmatch(startup['lineSha256']) and all(type(startup[key])is int and startup[key]>=0 for key in ('accommodations','expectedDays','missingDaysSubmitted','insertStatements','elapsedMs')),'CLOSED_INVENTORY_STARTUP_LOG_REQUIRED')
        need(result.get('schemaVersion')==1 and result.get('kind')=='global-growth-b-asg-host-observation' and result.get('instanceId')==identifier
             and type(result.get('httpRequestCount'))is int and 0<=result['httpRequestCount']<=2,'HOST_PROBE_CLOSED_SCHEMA_REQUIRED')
        need(not result.get('failureCode'),'HOST_RUNTIME_PROOF_FAILED')
        if required: need(result.get('available') is True and result.get('readiness',{}).get('ready') is True,'BASELINE_INVENTORY_READINESS_LOST')
        if result.get('available'):
            need(result.get('image')==self.config['application']['image'] and result.get('appJarSha256')==self.report['appRuntime']['imageJarSha256']
                 and result.get('normalProfile')=='aws' and HEX.fullmatch(result.get('containerId','')) and re.fullmatch('sha256:[0-9a-f]{64}',result.get('imageId','')),'HOST_IMAGE_OR_PROFILE_CHANGED')
            identities=self.report.setdefault('hostIdentities',{}); identity={k:result[k] for k in ('containerId','startedAt','imageId','image','appJarSha256','normalProfile')}
            if identifier in identities: need(identities[identifier]==identity,'APPLICATION_CONTAINER_RESTARTED')
            else: identities[identifier]=identity

    def prepare(self):
        row,manifest,permission,runtime=preflight(self.aws,self.config,self.root,self.lease)
        self.report['cleanupPermission']=permission; self.report['appRuntime']=runtime
        if isinstance(self.host_probe,SsmReadProbe): self.host_probe.runtime=runtime
        if 'resume' in self.config:
            previous=read(local_ref(self.config['resume']))
            need(previous.get('schemaVersion')==1 and previous.get('kind')==KIND and previous.get('toolSha256')==sha(__file__) and previous.get('helperSources')==helper_sources()
                 and previous.get('contractSha256')==contract_sha(self.config) and previous.get('cleanupComplete') is False
                 and previous.get('state') not in {COMPLETE,FAILED_CLEAN,'PREFLIGHT_READY'} and previous.get('protectionRequested') is True
                 and self.config['lease']['fencingToken']>previous['lease']['fencingToken'],'EXACT_OWN_UNFINISHED_PROBE_AND_NEW_LEASE_REQUIRED')
            for key in ('originalGroupStaticSha256','ownedInstanceIds','terminationIntents','scaleRequested','protectionRequested','scaleRequestedEpoch','hostIdentities'):
                if key in previous: self.report[key]=previous[key]
            need(canonical(static_group(row))==self.report['originalGroupStaticSha256'],'RESUME_ASG_CONFIGURATION_DRIFT')
            self.report['resumedReceiptSha256']=self.config['resume']['sha256']; self.source=previous
        else:
            self.report['originalGroupStaticSha256']=canonical(static_group(row))
            need((row['MinSize'],row['DesiredCapacity'],row['MaxSize'])==(1,1,1) and member_ids(row)=={self.config['asg']['baselineInstanceId']},'EXACT_ONE_INSTANCE_BASELINE_REQUIRED')
            need(not active_activities(self.aws,self.config),'BASELINE_SCALING_ACTIVITY_PRESENT')
        self.snapshot(protected=self.config['asg']['originalProtection'] if self.source is None else None)
        if self.db_sampler is None: self.db_sampler=CloudWatchDatabaseSampler(self.aws,manifest)
        self.event('PREFLIGHT_READY',preflightScope='READ_ONLY_METADATA_AND_IMMUTABLE_SERVICE_CONTRACT',manifestSha256=self.config['manifest']['sha256'],readinessSha256=self.config['readiness']['sha256'])

    def observe(self):
        end=self.config['deadlineEpoch']-self.config['timing']['cleanupReserveSeconds']
        if isinstance(self.aws,BoundedAws): self.aws.deadline=end
        baseline_end=time.time()+self.config['timing']['baselineSeconds']
        while time.time()<baseline_end:
            self.sample('baseline',end); self.wait(self.config['timing']['pollSeconds'],end)
        self.snapshot(protected=self.config['asg']['originalProtection'])
        baseline=self.config['asg']['baselineInstanceId']
        self.mutate('BASELINE_PROTECTION_REQUESTED',lambda:self.aws.call('autoscaling','set-instance-protection','--auto-scaling-group-name',self.config['asg']['name'],
            '--instance-ids',baseline,'--protected-from-scale-in'),protectionRequested=True)
        self.snapshot(protected=True)
        self.mutate('SCALE_OUT_REQUESTED',lambda:self.aws.call('autoscaling','update-auto-scaling-group','--auto-scaling-group-name',self.config['asg']['name'],
            '--min-size','1','--desired-capacity','2','--max-size','2'),scaleRequested=True,scaleRequestedEpoch=time.time(),scaleRequestedAt=now())
        row,_=self.snapshot(protected=True); need((row['MinSize'],row['DesiredCapacity'],row['MaxSize'])==(1,2,2),'SCALE_OUT_COMPARE_AFTER_FAILED')
        healthy_since=None
        while time.time()<end:
            ready=self.sample('healthy' if healthy_since is not None else 'scale-out',end)
            if ready and healthy_since is None: healthy_since=time.time()
            need(time.time()<end,'NEW_APPLICATION_OBSERVATION_DEADLINE_REACHED')
            if healthy_since is not None and time.time()-healthy_since>=self.config['timing']['healthySeconds']:
                self.report['observationComplete']=True; self.save(); return
            self.wait(self.config['timing']['pollSeconds'],end)
        raise RuntimeError('NEW_APPLICATION_OBSERVATION_DEADLINE_REACHED')

    def cleanup(self):
        self.cleanup_phase=True
        if isinstance(self.aws,BoundedAws): self.aws.deadline=self.config['deadlineEpoch']
        self.event('CLEANUP_STARTED'); stable=None
        while time.time()<self.config['deadlineEpoch']:
            row,states=self.snapshot(protected=True if self.report['scaleRequested'] else None)
            baseline=self.config['asg']['baselineInstanceId']; original=next(v for v in row['Instances'] if v['InstanceId']==baseline)
            other=member_ids(row)-{baseline}; shape=(row['MinSize'],row['DesiredCapacity'],row['MaxSize'])
            if other and row['DesiredCapacity']==2:
                identifier=next(iter(other)); need(identifier in self.report['ownedInstanceIds'] and identifier!=baseline,'ONLY_EXACT_OWN_ADDED_INSTANCE_MAY_TERMINATE')
                previous=[v for v in self.report['terminationIntents'] if v['instanceId']==identifier]
                if previous:
                    # Never replay a possibly accepted decrement. A new lease may
                    # retry after 60 seconds of stable running/desired=2/no work.
                    if self.source is None or active_activities(self.aws,self.config): stable=None; self.wait(10); continue
                    item=ec2_instance(self.aws,self.config,identifier)
                    if item['State']['Name']!='running': stable=None; self.wait(10); continue
                    stable=stable or time.time()
                    if time.time()-stable<self.config['timing']['stableSeconds']: self.wait(10); continue
                    self.report['terminationIntents']=[v for v in self.report['terminationIntents'] if v['instanceId']!=identifier]
                self.snapshot(protected=True)
                self.report['terminationIntents'].append({'instanceId':identifier,'requestedAt':now(),'shouldDecrementDesiredCapacity':True})
                try:
                    self.mutate('EXACT_ADDED_INSTANCE_TERMINATION_REQUESTED',lambda:self.aws.call('autoscaling','terminate-instance-in-auto-scaling-group',
                        '--instance-id',identifier,'--should-decrement-desired-capacity'))
                except Exception as error:
                    self.report.setdefault('uncertainCleanupCommands',[]).append({'operation':'terminate-exact-added','failureCode':error_code(error),'at':now()}); self.save()
                stable=None; self.wait(10); continue
            if not other and shape in {(1,2,2),(1,1,2)}:
                need(original['ProtectedFromScaleIn'] is True,'BASELINE_PROTECTION_REQUIRED_FOR_PENDING_CANCEL')
                self.snapshot(protected=True)
                need(self.report.get('pendingCancelAttempts',0)<3,'PENDING_CANCEL_RETRY_BOUND_REACHED')
                self.report['pendingCancelAttempts']=self.report.get('pendingCancelAttempts',0)+1
                try:
                    self.mutate('PROTECTED_PENDING_SCALE_OUT_CANCEL_REQUESTED',lambda:self.aws.call('autoscaling','update-auto-scaling-group','--auto-scaling-group-name',self.config['asg']['name'],
                        '--min-size','1','--desired-capacity','1','--max-size','1'))
                except Exception as error:
                    self.report.setdefault('uncertainCleanupCommands',[]).append({'operation':'protected-pending-cancel','failureCode':error_code(error),'at':now()}); self.save()
                stable=None; self.wait(10); continue
            active=active_activities(self.aws,self.config)
            # Check the EC2 group-tag inventory as well as the ASG membership;
            # pending/replacement instances must finish before unprotecting.
            reservations=self.aws.call('ec2','describe-instances','--filters','Name=tag:aws:autoscaling:groupName,Values='+self.config['asg']['name'],
                'Name=instance-state-name,Values=pending,running,stopping,stopped,shutting-down')['Reservations']
            live={item['InstanceId'] for reservation in reservations for item in reservation['Instances']}
            terminated=all(ec2_instance(self.aws,self.config,identifier)['State']['Name']=='terminated' for identifier in self.report['ownedInstanceIds'])
            settled=shape==(1,1,1) and not other and not active and live=={baseline} and states=={baseline:'healthy'} and terminated
            if not settled: stable=None; self.wait(10); continue
            stable=stable or time.time()
            if time.time()-stable<self.config['timing']['stableSeconds']: self.wait(10); continue
            if original['ProtectedFromScaleIn']!=self.config['asg']['originalProtection']:
                self.snapshot(protected=original['ProtectedFromScaleIn'])
                flag='--protected-from-scale-in' if self.config['asg']['originalProtection'] else '--no-protected-from-scale-in'
                self.mutate('ORIGINAL_BASELINE_PROTECTION_RESTORE_REQUESTED',lambda:self.aws.call('autoscaling','set-instance-protection','--auto-scaling-group-name',self.config['asg']['name'],
                    '--instance-ids',baseline,flag))
            row,states=self.snapshot(protected=self.config['asg']['originalProtection'])
            need((row['MinSize'],row['DesiredCapacity'],row['MaxSize'])==(1,1,1) and member_ids(row)=={baseline} and states=={baseline:'healthy'},'CLEANUP_COMPARE_AFTER_FAILED')
            self.report.update(cleanupComplete=True,baselineProtectionRestored=True,finalCapacity={'min':1,'desired':1,'max':1},baselineRetainedAndHealthy=True,cleanupFinishedAt=now())
            self.save(); return
        raise RuntimeError('CLEANUP_DEADLINE_REACHED_PROTECTION_MUST_REMAIN')

    def run(self,preflight_only=False):
        self.root.mkdir(mode=0o700); self.save(); previous_handlers={}
        def stopped(signum,frame):
            self.report['signalReceived']=signum
            if not self.cleanup_phase: raise InterruptedError('PROBE_SIGNAL')
        if threading.current_thread() is threading.main_thread():
            for signum in (signal.SIGINT,signal.SIGTERM): previous_handlers[signum]=signal.signal(signum,stopped)
        try:
            self.prepare()
            if preflight_only:
                self.report['finishedAt']=now(); self.save(); return self.report
            if self.source is None: self.observe()
        except BaseException as error:
            self.report['failureCode']=error_code(error); self.report['observationComplete']=False; self.save()
        finally:
            if not preflight_only and self.report['protectionRequested']:
                try: self.cleanup()
                except BaseException as error:
                    self.report['cleanupFailureCode']=error_code(error); self.report['cleanupComplete']=False
                    self.event(RETAINED,baselineProtectionRestored=False,externalDriftOrDeadlineMayPreventCleanup=True)
            for signum,handler in previous_handlers.items(): signal.signal(signum,handler)
        if preflight_only:
            self.event('PREFLIGHT_FAILED'); return self.report
        if self.report['cleanupComplete']:
            self.event(COMPLETE if self.report.get('observationComplete') else FAILED_CLEAN)
        elif not self.report['protectionRequested']: self.event('FAILED_BEFORE_ASG_MUTATION')
        self.report['finishedAt']=now(); self.report['elapsedSeconds']=epoch(self.report['finishedAt'])-epoch(self.report['startedAt'])
        self.save(); return self.report


def run(config,output,*,aws=None,host_probe=None,db_sampler=None,public_get=None,lease=None,sleep=time.sleep,preflight_only=False):
    validate_config(config); os.umask(0o077)
    need(not Path(output).exists() and not Path(output).is_symlink(),'NEW_PRIVATE_OUTPUT_REQUIRED')
    return Probe(config,output,aws,host_probe,db_sampler,public_get,lease,sleep).run(preflight_only)


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--config',type=Path,required=True); parser.add_argument('--output',type=Path,required=True); parser.add_argument('--preflight-only',action='store_true')
    args=parser.parse_args()
    try:
        result=run(read(args.config),args.output,preflight_only=args.preflight_only)
        print(json.dumps({k:result[k] for k in ('state','cleanupComplete','baselineInstanceId','ownedInstanceIds','r7OverallComplete')}))
        raise SystemExit(0 if result['state'] in {COMPLETE,'PREFLIGHT_READY'} else 1)
    except Exception as error:
        print(json.dumps({'state':'FAILED_REVIEW_RETAINED_RECEIPT','failureCode':error_code(error),'r7OverallComplete':False})); raise SystemExit(1)
