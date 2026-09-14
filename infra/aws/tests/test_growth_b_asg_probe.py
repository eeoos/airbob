"""Bounded ASG controller tests: no cloud calls, real state transitions and signal cleanup."""
import copy
import datetime as dt
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'scripts'))
import growth_b_asg_probe as probe
from test_growth_b_service_contract import fixture as service_fixture,receipt as service_receipt,runtime_projection


def stamp(epoch): return dt.datetime.fromtimestamp(epoch,dt.timezone.utc).isoformat()


class Clock:
    def __init__(self): self.value=time.time(); self.hook=None
    def now(self): return self.value
    def sleep(self,seconds):
        self.value+=seconds
        if self.hook: self.hook()


def policy():
    return {'Version':'2012-10-17','Statement':[{'Effect':'Allow','Action':sorted(probe.CLEANUP_ACTIONS),
        'Resource':f'arn:aws:autoscaling:{probe.REGION}:{probe.ACCOUNT}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*',
        'Condition':{'StringEquals':{'aws:ResourceTag/'+k:v for k,v in {'Project':'airbob','Environment':'performance-lab','Stack':'lab','ManagedBy':'terraform','Persistence':'ephemeral'}.items()},
                     'Null':{'aws:ResourceTag/'+k:'false' for k in ('RunId','FencingToken','ExpiresAt')}}}]}


def config(clock,manifest):
    asg_name='airbob-'+manifest['runId']+'-app'; ltdata={'ImageId':'ami-'+'a'*17,'InstanceType':'c6i.large','UserData':'PUBLIC_RUNTIME_BINDING'}
    def ref(key,checksum): return {'key':key,'versionId':'selected-version','sha256':checksum,'bytes':100}
    return {'schemaVersion':1,'kind':probe.KIND,'operationId':'probe-001','datasetId':manifest['datasetId'],'runId':manifest['runId'],'serviceRelease':manifest['serviceRelease'],
        'manifest':ref(f"datasets/{manifest['datasetId']}-aws-service/{manifest['serviceRelease']}/aws-service.json",'0'*64),
        'readiness':ref(f"data-bootstrap/{manifest['runId']}/b-service-readiness.json",'1'*64),'application':manifest['application'],
        'asg':{'name':asg_name,'arn':f'arn:aws:autoscaling:{probe.REGION}:{probe.ACCOUNT}:autoScalingGroup:12345678-1234-1234-1234-123456789abc:autoScalingGroupName/'+asg_name,
            'launchTemplate':{'id':'lt-'+'b'*17,'version':'1','dataSha256':probe.canonical(ltdata)},'baselineInstanceId':'i-'+'c'*17,
            'baselineLaunchTime':stamp(clock.now()-3600),'originalProtection':False,'amiId':'ami-'+'a'*17,'runtimeRevision':'d'*64,
            'targetGroupArn':f'arn:aws:elasticloadbalancing:{probe.REGION}:{probe.ACCOUNT}:targetgroup/airbob-lab-unit/'+'e'*16,
            'albArn':f'arn:aws:elasticloadbalancing:{probe.REGION}:{probe.ACCOUNT}:loadbalancer/app/airbob-lab-unit/'+'f'*16,
            'albDnsName':'airbob-lab-unit-123.ap-northeast-2.elb.amazonaws.com'},
        'resourceFencingToken':62,'lease':{'table':'airbob-performance-lab-orchestration-lease','lockName':'airbob-performance-lab','owner':'controller-unit',
            'runId':manifest['runId'],'command':'up','fencingToken':100},
        'expiresAt':int(clock.now())+20000,'approvedExecutionDeadlineEpoch':int(clock.now())+86400,'deadlineEpoch':int(clock.now())+1800,
        'cleanupPolicy':{'policyArn':probe.POLICY,'defaultVersionId':'v3','documentSha256':probe.canonical(policy())},
        'http':{'publicHost':'api.example.test','detailPath':'/api/v1/accommodations/123','timeoutSeconds':2,'maximumRequests':500},
        'timing':{'baselineSeconds':60,'pollSeconds':30,'healthySeconds':60,'cleanupReserveSeconds':600,'stableSeconds':60}}


class Aws:
    def __init__(self,c,clock):
        self.c,self.clock=c,clock; self.calls=[]; self.new='i-'+'9'*17; self.pending=False
        self.rds_resource_id=service_fixture()['rds']['resourceId']
        self.fail_scale_before=self.fail_scale_after=self.fail_terminate_after=False
        self.fail_terminate_before=self.fail_protect_after=self.fail_max_restore=False; self.drift=False; self.online=True
        self.policy=policy(); self.public_targets={c['asg']['baselineInstanceId']:'healthy'}
        base={'InstanceId':c['asg']['baselineInstanceId'],'LifecycleState':'InService','HealthStatus':'Healthy','ProtectedFromScaleIn':c['asg']['originalProtection'],'LaunchTemplate':{'LaunchTemplateId':c['asg']['launchTemplate']['id'],'Version':'1'}}
        self.row={'AutoScalingGroupName':c['asg']['name'],'AutoScalingGroupARN':c['asg']['arn'],'MinSize':1,'DesiredCapacity':1,'MaxSize':1,'Instances':[base],
            'LaunchTemplate':{'LaunchTemplateId':c['asg']['launchTemplate']['id'],'Version':'1'},'TargetGroupARNs':[c['asg']['targetGroupArn']],
            'HealthCheckType':'ELB','CapacityRebalance':False,'AvailabilityZones':['ap-northeast-2a'],'VPCZoneIdentifier':'subnet-unit',
            'NewInstancesProtectedFromScaleIn':False,'SuspendedProcesses':[],'Tags':self.resource_tags('app'),
            'EnabledMetrics':[{'Metric':'GroupDesiredCapacity','Granularity':'1Minute'}]}
        self.instances={base['InstanceId']:self.instance(base['InstanceId'],c['asg']['baselineLaunchTime'])}; self.activities=[]
    def resource_tags(self,service=None):
        values={'Project':'airbob','Environment':'performance-lab','Stack':'lab','ManagedBy':'terraform','Persistence':'ephemeral','RunId':self.c['runId'],
                'FencingToken':str(self.c['resourceFencingToken']),'ExpiresAt':str(self.c['expiresAt'])}
        if service: values['Service']=service
        if service=='app': values['RuntimeRevision']=self.c['asg']['runtimeRevision']
        return [{'Key':k,'Value':v} for k,v in values.items()]
    def instance(self,identifier,launch):
        return {'InstanceId':identifier,'ImageId':self.c['asg']['amiId'],'InstanceType':'c6i.large','LaunchTime':launch,'State':{'Name':'running'},
            'Tags':self.resource_tags('app')}
    def spawn(self):
        self.instances[self.new]=self.instance(self.new,stamp(self.clock.now()))
        self.row['Instances'].append({'InstanceId':self.new,'LifecycleState':'InService','HealthStatus':'Healthy','ProtectedFromScaleIn':False,'LaunchTemplate':{'LaunchTemplateId':self.c['asg']['launchTemplate']['id'],'Version':'1'}})
        self.public_targets[self.new]='initial'
    def call(self,*args):
        self.calls.append(args)
        def option(flag): return args[args.index(flag)+1]
        pair=args[:2]
        if pair==('sts','get-caller-identity'): return {'Account':probe.ACCOUNT,'Arn':f'arn:aws:sts::{probe.ACCOUNT}:assumed-role/airbob-lab-operator/unit'}
        if pair==('iam','list-attached-role-policies'): return {'AttachedPolicies':[{'PolicyArn':probe.POLICY}]}
        if pair==('iam','get-policy'): return {'Policy':{'Arn':probe.POLICY,'DefaultVersionId':self.c['cleanupPolicy']['defaultVersionId']}}
        if pair==('iam','get-policy-version'): return {'PolicyVersion':{'Document':copy.deepcopy(self.policy)}}
        if pair==('autoscaling','describe-auto-scaling-groups'): return {'AutoScalingGroups':[copy.deepcopy(self.row)]}
        empty={'describe-policies':'ScalingPolicies','describe-scheduled-actions':'ScheduledUpdateGroupActions','describe-lifecycle-hooks':'LifecycleHooks','describe-instance-refreshes':'InstanceRefreshes'}
        if pair[0]=='autoscaling' and pair[1] in empty: return {empty[pair[1]]:[]}
        if pair==('autoscaling','describe-scaling-activities'): return {'Activities':copy.deepcopy(self.activities)}
        if pair==('ec2','describe-launch-template-versions'):
            return {'LaunchTemplateVersions':[{'LaunchTemplateId':self.c['asg']['launchTemplate']['id'],'VersionNumber':1,
                'LaunchTemplateData':{'ImageId':'ami-'+'a'*17,'InstanceType':'c6i.large','UserData':'PUBLIC_RUNTIME_BINDING'}}]}
        if pair==('ec2','describe-instances'):
            items=[self.instances[option('--instance-ids')]] if '--instance-ids' in args else [v for v in self.instances.values() if v['State']['Name']!='terminated']
            return {'Reservations':[{'Instances':copy.deepcopy(items)}]}
        if pair==('elbv2','describe-target-health'):
            return {'TargetHealthDescriptions':[{'Target':{'Id':key,'Port':8080},'TargetHealth':{'State':state}} for key,state in self.public_targets.items()]}
        if pair==('elbv2','describe-target-groups'): return {'TargetGroups':[{'TargetType':'instance','Port':8080,'Protocol':'HTTP','HealthCheckPath':'/actuator/health','LoadBalancerArns':[self.c['asg']['albArn']]}]}
        if pair==('elbv2','describe-load-balancers'): return {'LoadBalancers':[{'DNSName':self.c['asg']['albDnsName'],'State':{'Code':'active'}}]}
        if pair==('rds','describe-db-instances'): return {'DBInstances':[{'DbiResourceId':self.rds_resource_id,'EngineVersion':'8.4.11','DBInstanceStatus':'available','TagList':self.resource_tags()}]}
        if pair==('autoscaling','set-instance-protection'):
            assert option('--instance-ids')==self.c['asg']['baselineInstanceId']
            self.row['Instances'][0]['ProtectedFromScaleIn']='--protected-from-scale-in' in args
            if self.fail_protect_after: self.fail_protect_after=False; raise RuntimeError('UNCERTAIN_PROTECTION_RESPONSE')
            return {}
        if pair==('autoscaling','update-auto-scaling-group'):
            if option('--desired-capacity')=='2':
                assert self.row['Instances'][0]['ProtectedFromScaleIn'] is True
                if self.fail_scale_before: raise RuntimeError('SCALE_REQUEST_REJECTED')
                self.row.update(MinSize=1,MaxSize=2,DesiredCapacity=2)
                if not self.pending: self.spawn()
                if self.drift: self.row['LaunchTemplate']['Version']='2'
                if self.fail_scale_after: raise RuntimeError('UNCERTAIN_SCALE_RESPONSE')
            else:
                assert self.row['Instances'][0]['ProtectedFromScaleIn'] is True
                if self.fail_max_restore: raise RuntimeError('MAX_RESTORE_REJECTED')
                self.row.update(MinSize=1,MaxSize=1,DesiredCapacity=1)
            return {}
        if pair==('autoscaling','terminate-instance-in-auto-scaling-group'):
            identifier=option('--instance-id'); assert identifier!=self.c['asg']['baselineInstanceId']; assert self.row['DesiredCapacity']==2
            assert '--should-decrement-desired-capacity' in args
            if self.fail_terminate_before: raise RuntimeError('TERMINATION_REJECTED')
            self.row['DesiredCapacity']-=1; self.row['Instances']=[v for v in self.row['Instances'] if v['InstanceId']!=identifier]
            self.instances[identifier]['State']['Name']='terminated'; self.public_targets.pop(identifier,None)
            if self.fail_terminate_after: raise RuntimeError('UNCERTAIN_TERMINATE_RESPONSE')
            return {'Activity':{'ActivityId':'own-termination'}}
        if pair==('cloudwatch','get-metric-statistics'): return {'Datapoints':[]}
        if pair==('ssm','describe-instance-information'):
            return {'InstanceInformationList':[] if not self.online else [{'InstanceId':option('--filters').split('Values=')[1],'PingStatus':'Online'}]}
        raise AssertionError('Unimplemented safe fixture AWS call '+str(pair))


class Fixture(unittest.TestCase):
    def setUp(self):
        # The CLI entry point sets a process-wide private mask; restore the
        # caller's mask when running it inside the shared unittest process.
        previous_umask = os.umask(0o077)
        self.addCleanup(os.umask, previous_umask)
        self.temp=tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root=Path(self.temp.name)
        self.clock=Clock(); self.manifest=service_fixture(); self.c=config(self.clock,self.manifest); self.aws=Aws(self.c,self.clock)
        self.ready=service_receipt(self.manifest); self.runtime=runtime_projection(self.manifest); self.signal_once=False; self.host_calls=[]
    def fetch(self,aws,ref,path,bucket):
        if ref==self.c['manifest']: return copy.deepcopy(self.manifest)
        if ref==self.c['readiness']: return copy.deepcopy(self.ready)
        if ref==self.manifest['appRuntimeBinding']: return {'public-runtime-binding':True}
        raise AssertionError(ref)
    def host(self,identifier,deadline):
        self.host_calls.append(identifier)
        if identifier==self.aws.new: self.aws.public_targets[identifier]='healthy'
        return {'schemaVersion':1,'kind':'global-growth-b-asg-host-observation','instanceId':identifier,'observedAt':stamp(self.clock.now()),'httpRequestCount':2,'available':True,
            'containerId':('a' if identifier==self.c['asg']['baselineInstanceId'] else 'b')*64,'startedAt':stamp(self.clock.value-1000 if identifier==self.c['asg']['baselineInstanceId'] else self.clock.value-100),
            'imageId':'sha256:'+'c'*64,'image':self.c['application']['image'],'appJarSha256':self.runtime['imageJarSha256'],'normalProfile':'aws',
            'readiness':{'status':200,'latencyMs':2,'bytes':15,'ready':True,'observedAt':stamp(self.clock.now())},'detail':{'status':200,'latencyMs':12,'bytes':150,'ready':False,'observedAt':stamp(self.clock.now())},
            'inventoryStartupComplete':{'logObservedAt':stamp(self.clock.now()),'accommodations':657358,'expectedDays':657358*98,'missingDaysSubmitted':0,'insertStatements':0,'elapsedMs':1000,'lineSha256':'8'*64}}
    def fixed_host(self,identifier,deadline):
        result=self.host(identifier,deadline); result['startedAt']=self.c['asg']['baselineLaunchTime']; return result
    def invoke(self,*,name='run',host=None,lease=lambda **kw:None,preflight=False,config=None):
        with patch.object(probe.time,'time',side_effect=self.clock.now),patch.object(probe.service,'fetch',side_effect=self.fetch),\
             patch.object(probe.service.app_runtime,'validate_runtime_binding',return_value=self.runtime):
            return probe.run(config or self.c,self.root/name,aws=self.aws,host_probe=host or self.fixed_host,
                db_sampler=lambda phase:{'available':False,'reason':'NO_ACTUAL_AWS_DATABASE_RESULTS'},public_get=lambda:{'status':200,'latencyMs':15,'bytes':150,'bodyRetained':False},
                lease=lease,sleep=self.clock.sleep,preflight_only=preflight)
    def assert_clean(self,result):
        self.assertTrue(result['cleanupComplete'],result); self.assertEqual(self.aws.row['MinSize'],1); self.assertEqual(self.aws.row['MaxSize'],1); self.assertEqual(self.aws.row['DesiredCapacity'],1)
        self.assertEqual([v['InstanceId'] for v in self.aws.row['Instances']],[self.c['asg']['baselineInstanceId']]); self.assertFalse(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])
        self.assertFalse(result['r7OverallComplete']); self.assertFalse(result['baselineTerminatedByTool'])


class LifecycleTests(Fixture):
    def test_normal_one_to_two_to_one_records_real_image_runtime_and_no_database_fabrication(self):
        result=self.invoke(); self.assertEqual(result['state'],probe.COMPLETE,result); self.assert_clean(result)
        self.assertEqual(result['ownedInstanceIds'],[self.aws.new]); self.assertIn('inventoryReadyFirstObservedAt',result); self.assertIn('albHealthyFirstObservedAt',result)
        self.assertNotEqual(result['appRuntime']['imageJarSha256'],self.c['application']['appJarSha256'])
        self.assertFalse(result['databaseMetricsAvailable']); self.assertGreater(result['baselineLatency']['baseline']['requests'],0)
        self.assertEqual(sum(call[:2]==('autoscaling','terminate-instance-in-auto-scaling-group') for call in self.aws.calls),1)

    def test_read_only_preflight_does_not_set_protection_or_change_capacity(self):
        result=self.invoke(preflight=True); self.assertEqual(result['state'],'PREFLIGHT_READY',result)
        self.assertFalse(any(call[0]=='autoscaling' and not call[1].startswith('describe') for call in self.aws.calls)); self.assertEqual(self.host_calls,[])

    def test_uncertain_scale_response_reconciles_live_instance_and_cleans_only_that_id(self):
        self.aws.fail_scale_after=True; result=self.invoke(); self.assertEqual(result['state'],probe.FAILED_CLEAN,result); self.assert_clean(result)
        self.assertEqual(result['failureCode'],'UNCERTAIN_SCALE_RESPONSE'); self.assertEqual(result['ownedInstanceIds'],[self.aws.new])

    def test_uncertain_termination_response_is_never_double_decremented(self):
        self.aws.fail_terminate_after=True; result=self.invoke(); self.assert_clean(result)
        self.assertEqual(len(result['uncertainCleanupCommands']),1)
        self.assertEqual(sum(c[:2]==('autoscaling','terminate-instance-in-auto-scaling-group') for c in self.aws.calls),1)

    def test_rejected_scale_before_creation_restores_protection_after_stability(self):
        self.aws.fail_scale_before=True; result=self.invoke(); self.assert_clean(result); self.assertEqual(result['ownedInstanceIds'],[])

    def test_uncertain_protection_response_does_not_trigger_scale_out(self):
        self.aws.fail_protect_after=True; result=self.invoke(); self.assert_clean(result)
        self.assertFalse(any(c[:2]==('autoscaling','update-auto-scaling-group') for c in self.aws.calls))

    def test_pending_only_scale_is_cancelled_with_baseline_protected(self):
        self.aws.pending=True
        def interrupt():
            if self.aws.row['DesiredCapacity']==2 and not self.signal_once: self.signal_once=True; raise KeyboardInterrupt()
        self.clock.hook=interrupt; result=self.invoke(); self.assert_clean(result); self.assertEqual(result['ownedInstanceIds'],[])
        self.assertFalse(any(c[:2]==('autoscaling','terminate-instance-in-auto-scaling-group') for c in self.aws.calls))

    def test_actual_sigterm_uses_the_same_bounded_exact_cleanup(self):
        def host(identifier,deadline):
            if identifier==self.aws.new and not self.signal_once:
                self.signal_once=True; os.kill(os.getpid(),signal.SIGTERM)
            return self.fixed_host(identifier,deadline)
        result=self.invoke(host=host); self.assert_clean(result); self.assertEqual(result['signalReceived'],signal.SIGTERM)

    def test_external_template_drift_retains_protection_without_rolling_back_others(self):
        self.aws.drift=True; result=self.invoke(); self.assertEqual(result['state'],probe.RETAINED,result)
        self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn']); self.assertEqual(self.aws.row['LaunchTemplate']['Version'],'2')
        self.assertFalse(any(c[:2]==('autoscaling','terminate-instance-in-auto-scaling-group') for c in self.aws.calls))

    def test_lost_lease_never_mutates_using_a_stale_fence(self):
        def lease(**kw):
            if self.aws.row['DesiredCapacity']==2: raise RuntimeError('LEASE_REPLACED')
        result=self.invoke(lease=lease); self.assertEqual(result['state'],probe.RETAINED,result)
        self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn']); self.assertEqual(result['cleanupFailureCode'],'LEASE_REPLACED')

    def test_rejected_termination_retains_protection_and_own_receipt_for_new_lease(self):
        self.aws.fail_terminate_before=True; result=self.invoke(); self.assertEqual(result['state'],probe.RETAINED,result)
        self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn']); self.assertEqual(result['ownedInstanceIds'],[self.aws.new])
        self.assertFalse(result['cleanupComplete'])

    def test_new_lease_cleanup_resume_retries_own_failed_decrement_after_stable_readback(self):
        self.aws.fail_terminate_before=True; first=self.invoke(); self.assertEqual(first['state'],probe.RETAINED)
        source=self.root/'run/asg-probe.json'; changed=copy.deepcopy(self.c); changed['lease']['fencingToken']+=1; changed['deadlineEpoch']=int(self.clock.now())+1200
        changed['resume']={'path':str(source.resolve()),'sha256':probe.sha(source)}; self.aws.fail_terminate_before=False
        result=self.invoke(name='resume',config=changed); self.assert_clean(result)
        self.assertEqual(self.host_calls.count(self.aws.new),3) # only original observation; resume issues no GETs
        self.assertFalse(result.get('observationComplete',False)); self.assertEqual(result['resumedReceiptSha256'],changed['resume']['sha256'])


class AdmissionTests(Fixture):
    def test_time_capacity_profile_and_reference_bounds_reject_unsafe_inputs(self):
        mutations=[lambda c:c.update(deadlineEpoch=int(self.clock.now())+3601),lambda c:c.update(deadlineEpoch=int(self.clock.now())+600),
            lambda c:c['asg']['launchTemplate'].update(version='$Latest'),lambda c:c['asg'].update(baselineInstanceId='i-other'),
            lambda c:c['http'].update(detailPath='/api/v1/admin/members'),lambda c:c['http'].update(maximumRequests=501),
            lambda c:c['timing'].update(cleanupReserveSeconds=599),lambda c:c['manifest'].update(key='datasets/old-v27/manifest.json')]
        for mutate in mutations:
            changed=copy.deepcopy(self.c); mutate(changed)
            with self.subTest(mutate=mutate), self.assertRaises((RuntimeError,ValueError)):
                probe.validate_config(changed)

    def test_original_scale_in_protection_true_remains_true(self):
        self.c['asg']['originalProtection']=True; self.aws.row['Instances'][0]['ProtectedFromScaleIn']=True
        result=self.invoke(); self.assertEqual(result['state'],probe.COMPLETE,result)
        self.assertTrue(result['cleanupComplete']); self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])
        self.assertFalse(any('--no-protected-from-scale-in' in call for call in self.aws.calls))

    def test_no_sid_is_needed_but_missing_cleanup_action_hash_or_tag_boundary_fails(self):
        self.assertNotIn('Sid',self.aws.policy['Statement'][0]); probe.policy_admission(self.aws,self.c)
        for changed in (self.aws.policy|{'Version':'2020-01-01'},):
            self.aws.policy=changed
            with self.assertRaisesRegex(RuntimeError,'DOCUMENT_CHANGED'): probe.policy_admission(self.aws,self.c)
        self.aws.policy=policy(); self.aws.policy['Statement'][0]['Action'].remove('autoscaling:TerminateInstanceInAutoScalingGroup')
        self.c['cleanupPolicy']['documentSha256']=probe.canonical(self.aws.policy)
        with self.assertRaisesRegex(RuntimeError,'NOT_GRANTED'): probe.policy_admission(self.aws,self.c)
        self.aws.policy=policy(); del self.aws.policy['Statement'][0]['Condition']['Null']['aws:ResourceTag/ExpiresAt']
        self.c['cleanupPolicy']['documentSha256']=probe.canonical(self.aws.policy)
        with self.assertRaisesRegex(RuntimeError,'TAG_BOUNDARY'): probe.policy_admission(self.aws,self.c)

    def test_current_cleanup_policy_missing_action_prevents_any_asg_mutation(self):
        self.aws.policy['Statement'][0]['Action'].remove('autoscaling:TerminateInstanceInAutoScalingGroup')
        self.c['cleanupPolicy']['documentSha256']=probe.canonical(self.aws.policy)
        result=self.invoke(); self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION',result)
        self.assertFalse(any(call[0]=='autoscaling' and not call[1].startswith('describe') for call in self.aws.calls))

    def test_rds_resource_or_resource_expiry_drift_prevents_scale_out(self):
        self.aws.row['Tags'][0]['Value']='different-project'
        result=self.invoke(); self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION',result)
        self.assertFalse(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])

    def test_unfinished_baseline_activity_is_not_cleared_by_probe(self):
        self.aws.activities=[{'ActivityId':'not-owned','StatusCode':'InProgress'}]
        result=self.invoke(); self.assertEqual(result['failureCode'],'BASELINE_SCALING_ACTIVITY_PRESENT')
        self.assertFalse(any(c[:2]==('autoscaling','update-auto-scaling-group') for c in self.aws.calls))

    def test_order_insensitive_tags_do_not_create_false_cas_drift(self):
        expected=probe.canonical(probe.static_group(self.aws.row)); self.aws.row['Tags'].reverse()
        self.assertEqual(probe.canonical(probe.static_group(self.aws.row)),expected)
        self.aws.row['TargetGroupARNs']=['different']; self.assertNotEqual(probe.canonical(probe.static_group(self.aws.row)),expected)

    def test_scale_intent_is_durable_before_remote_request(self):
        original=self.aws.call
        def call(*args):
            if args[:2]==('autoscaling','update-auto-scaling-group') and args[args.index('--desired-capacity')+1]=='2':
                saved=probe.read(self.root/'run/asg-probe.json')
                self.assertTrue(saved['scaleRequested']); self.assertTrue(saved['protectionRequested']); self.assertIn('scaleRequestedEpoch',saved)
            return original(*args)
        self.aws.call=call; result=self.invoke(); self.assert_clean(result)


class HostAndReadTests(Fixture):
    def test_existing_instance_latency_comes_only_from_its_loopback_get(self):
        def slow(identifier,deadline):
            result=self.fixed_host(identifier,deadline); result['detail']['latencyMs']=42 if identifier==self.c['asg']['baselineInstanceId'] else 999
            return result
        result=self.invoke(host=slow); self.assert_clean(result)
        for value in result['baselineLatency'].values():
            if value['requests']: self.assertEqual(value['maximumMs'],42)
        rows=probe.read(self.root/'run/samples.json'); self.assertTrue(all(row['albDetail']['latencyMs']==15 for row in rows))
        self.assertEqual(result['inventoryReadinessEvidence']['startupLog']['accommodations'],657358)
        self.assertEqual(result['albHealthEvidence']['targetInstanceId'],self.aws.new)

    def test_actual_container_restart_fails_before_scaleout(self):
        result=self.invoke(host=self.host); self.assertEqual(result['failureCode'],'APPLICATION_CONTAINER_RESTARTED')
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')

    def test_sealed_source_jar_is_not_substituted_for_verified_runtime_image_jar(self):
        def wrong(identifier,deadline):
            result=self.fixed_host(identifier,deadline); result['appJarSha256']=self.c['application']['appJarSha256']; return result
        result=self.invoke(host=wrong); self.assertEqual(result['failureCode'],'HOST_IMAGE_OR_PROFILE_CHANGED')
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')

    def test_healthy_alb_before_inventory_readiness_is_not_a_success(self):
        def early(identifier,deadline):
            result=self.fixed_host(identifier,deadline)
            if identifier==self.aws.new: result['readiness']['ready']=False
            return result
        result=self.invoke(host=early); self.assert_clean(result); self.assertEqual(result['failureCode'],'ALB_HEALTHY_BEFORE_VERIFIED_INVENTORY_READINESS')

    def test_missing_final_inventory_startup_proof_does_not_claim_complete(self):
        def missing(identifier,deadline):
            result=self.fixed_host(identifier,deadline)
            if identifier==self.aws.new: result.pop('inventoryStartupComplete')
            return result
        result=self.invoke(host=missing); self.assert_clean(result); self.assertEqual(result['failureCode'],'FINAL_B_INVENTORY_STARTUP_LOG_REQUIRED')

    def test_payload_or_credentials_are_not_accepted_as_host_receipt_fields(self):
        def unsafe(identifier,deadline): return self.fixed_host(identifier,deadline)|{'payload':'PRIVATE_SENTINEL'}
        result=self.invoke(host=unsafe); self.assertEqual(result['failureCode'],'UNEXPECTED_HOST_PAYLOAD_FIELD')
        self.assertNotIn('PRIVATE_SENTINEL',(self.root/'run/asg-probe.json').read_text())
        self.assertFalse((self.root/'run/samples.json').exists())

    def test_new_ssm_agent_not_yet_online_is_observed_as_unavailable_without_send(self):
        self.aws.online=False; reader=probe.SsmReadProbe(self.aws,self.c,lambda:None,self.clock.sleep); reader.runtime=self.runtime
        result=reader(self.aws.new,self.c['deadlineEpoch']); self.assertFalse(result['available']); self.assertEqual(result['httpRequestCount'],0)
        self.assertFalse(any(call[:2]==('ssm','send-command') for call in self.aws.calls))

    def test_no_actual_cloudwatch_data_is_never_zero_filled(self):
        result=probe.CloudWatchDatabaseSampler(self.aws,self.manifest)('baseline')
        self.assertFalse(result['available']); self.assertFalse(result['sqlLockWaitMeasurementsAvailable'])
        self.assertEqual(set(result['metrics']),set(probe.METRICS)); self.assertTrue(all(value==[] for value in result['metrics'].values()))

    def test_host_program_is_syntax_valid_and_read_only(self):
        compile(probe.HOST_SCRIPT.replace('__EXPECTED__','e30='),'host-read-only','exec')
        for forbidden in ('secretsmanager','docker start','docker stop','docker run','CREATE DATABASE','SPRING_PROFILES_ACTIVE=test','open(',"'w'"):
            if forbidden=='open(': continue # urllib opener is deliberately read-only HTTP.
            self.assertNotIn(forbidden,probe.HOST_SCRIPT)
        self.assertIn("method='GET'",probe.HOST_SCRIPT); self.assertIn("'http://127.0.0.1:8080'",probe.HOST_SCRIPT)


class ResumeTests(Fixture):
    def failed(self):
        self.aws.fail_terminate_before=True; result=self.invoke(); self.assertEqual(result['state'],probe.RETAINED)
        source=self.root/'run/asg-probe.json'; changed=copy.deepcopy(self.c); changed['lease']['fencingToken']+=1; changed['deadlineEpoch']=int(self.clock.now())+1200
        changed['resume']={'path':str(source.resolve()),'sha256':probe.sha(source)}; self.aws.fail_terminate_before=False
        return changed,source

    def test_resume_rejects_input_tampering_and_same_or_old_lease(self):
        changed,source=self.failed(); changed['lease']['fencingToken']=self.c['lease']['fencingToken']
        result=self.invoke(name='old-lease',config=changed)
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION'); self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])
        source.write_text(source.read_text()+' ')
        with self.assertRaisesRegex(RuntimeError,'RECEIPT_CHANGED'):
            self.invoke(name='tamper',config=changed)

    def test_failed_cleanup_does_not_relax_original_shared_expiry(self):
        changed,_=self.failed(); changed['expiresAt']+=3600
        result=self.invoke(name='extended',config=changed); self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')
        self.assertFalse(result['ttlExtended']); self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])

    def test_cleanup_waits_for_activities_and_retains_protection_if_they_never_finish(self):
        original=self.aws.call
        def call(*args):
            reply=original(*args)
            if args[:2]==('autoscaling','terminate-instance-in-auto-scaling-group'):
                self.aws.activities=[{'ActivityId':'own-pending','StatusCode':'InProgress'}]
            return reply
        self.aws.call=call; result=self.invoke(); self.assertEqual(result['state'],probe.RETAINED)
        self.assertTrue(self.aws.row['Instances'][0]['ProtectedFromScaleIn']); self.assertFalse(result['cleanupComplete'])


class ApiShapeTests(Fixture):
    def test_ec2_has_no_launch_template_and_asg_member_supplies_the_binding(self):
        self.assertNotIn('LaunchTemplate',self.aws.instances[self.c['asg']['baselineInstanceId']])
        result=self.invoke(); self.assert_clean(result)

    def test_member_launch_template_drift_is_rejected_before_scaleout(self):
        self.aws.row['Instances'][0]['LaunchTemplate']['Version']='2'
        result=self.invoke(); self.assertEqual(result['failureCode'],'INSTANCE_LAUNCH_TEMPLATE_CHANGED')
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')

    def test_unattached_policy_cannot_authorize_cleanup(self):
        original=self.aws.call
        def call(*args):
            if args[:2]==('iam','list-attached-role-policies'): return {'AttachedPolicies':[]}
            return original(*args)
        self.aws.call=call; result=self.invoke()
        self.assertEqual(result['failureCode'],'CLEANUP_POLICY_NOT_ATTACHED_TO_CALLING_ROLE')
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')

    def test_asg_warm_pool_is_not_a_fresh_instance_boot(self):
        self.aws.row['WarmPoolSize']=1
        result=self.invoke(); self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')
        self.assertFalse(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])

class BaselineAdmissionTests(Fixture):
    def test_unhealthy_baseline_detail_never_adds_paid_capacity(self):
        def unavailable(identifier,deadline):
            result=self.fixed_host(identifier,deadline); result['detail']['status']=500; return result
        result=self.invoke(host=unavailable)
        self.assertEqual(result['failureCode'],'HEALTHY_BASELINE_PUBLIC_DETAIL_REQUIRED')
        self.assertEqual(result['state'],'FAILED_BEFORE_ASG_MUTATION')
        self.assertEqual(self.aws.row['DesiredCapacity'],1)
        self.assertFalse(self.aws.row['Instances'][0]['ProtectedFromScaleIn'])

if __name__=='__main__': unittest.main()
