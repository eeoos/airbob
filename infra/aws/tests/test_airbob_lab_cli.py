"""Front-door routing and real snapshot/service contracts; remote IO is mocked."""
import ast
import contextlib
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

import test_growth_b_mac_snapshot as fixtures
from test_growth_b_mac_snapshot import m as snapshot

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
spec = importlib.util.spec_from_file_location('airbob_lab_cli', SCRIPTS / 'airbob-lab.py')
cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)


class FakeIO:
    def __init__(self):
        self.profile='admin-eeoos';self.objects={};self.events=[];self.markers=[];self.fail_put=None;self.versions={}
    def get(self,bucket,ref):
        raw=self.objects[(bucket,ref['key'])]
        cli.need(cli.sha(raw)==ref['sha256'] and len(raw)==ref['bytes'],'MOCK_EXACT_BODY_CHANGED')
        cli.need(self.versions.get((bucket,ref['key']),ref['versionId'])==ref['versionId'],'MOCK_EXACT_VERSION_CHANGED')
        self.events.append(('get',ref['key']));return raw
    def aws(self,*args):
        operation=args[:2];self.events.append(operation)
        if operation==('s3api','list-object-versions'):
            bucket=args[args.index('--bucket')+1];key=args[args.index('--prefix')+1]
            return {'DeleteMarkers':copy.deepcopy(self.markers),'Versions':([{'Key':key,'VersionId':self.versions[(bucket,key)],'IsLatest':True}]
                if (bucket,key) in self.objects else [])}
        if operation==('s3api','put-object'):
            bucket=args[args.index('--bucket')+1];key=args[args.index('--key')+1]
            self.assert_put_args=args
            if self.fail_put=='before':raise cli.Rejected('UNKNOWN_PUT')
            self.objects[(bucket,key)]=Path(args[args.index('--body')+1]).read_bytes()
            version='actual-mock-version-'+str(len(self.objects));self.versions[(bucket,key)]=version
            if self.fail_put=='after':raise cli.Rejected('UNKNOWN_PUT')
            return {'VersionId':version}
        raise AssertionError('Unexpected remote operation')


class FrontDoor(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=Path(self.temp.name).resolve()
        self.fx=fixtures.future_fixture();self.source=self.fx['source'];self.restored=snapshot.validate_restore(**self.fx)
        self.saved={'schemaVersion':1,'kind':'airbob-lab-config','profile':'admin-eeoos','githubRepository':'example/airbob',
            'sourceProvenance':self.fx['op']['sourceProvenance'],'bundleManifestVersionId':'actual-bundle-version',
            'ttlHours':24,'approvedDeadlineEpoch':None,'evidenceDirectory':str(self.root/'lab')}
        self.io=FakeIO();self.lab=object.__new__(cli.Lab)
        self.lab.saved,self.lab.profile,self.lab.base,self.lab.io=self.saved,'admin-eeoos',cli.Journal(self.root/'lab'),self.io
        self.lab.snapshot,self.lab.service=snapshot,snapshot.service

    def test_import_has_no_network_or_subprocess_and_syntax_is_python39(self):
        ast.parse((SCRIPTS/'airbob-lab.py').read_text(),feature_version=(3,9))
        with patch.object(cli.subprocess,'Popen',side_effect=AssertionError('on-import subprocess')):
            other=importlib.util.module_from_spec(spec);spec.loader.exec_module(other)
        self.assertEqual(SCRIPTS.parent/'lab-baseline.json',cli.DEFAULT_CONFIG)

    def test_saved_public_config_is_closed_and_actual_source_ref_is_required(self):
        path=self.root/'config.json';path.write_bytes(cli.encoded(self.saved))
        self.assertEqual(self.saved,cli.config(path))
        for key,value in [('password','private'),('ttlHours',169),('sourceProvenance',None)]:
            changed=copy.deepcopy(self.saved);changed[key]=value;path.write_bytes(cli.encoded(changed))
            with self.subTest(key=key),self.assertRaises((ValueError,cli.Rejected,TypeError)):cli.config(path)

    def test_new_24_or_168_hour_window_and_current_campaign_cap(self):
        now=1800000000
        self.assertEqual((24,now+86400+300),cli.new_window(self.saved,None,now))
        self.assertEqual((168,now+604800+300),cli.new_window(self.saved,168,now))
        self.assertEqual((6,now+21600+300),cli.new_window(self.saved,6,now))
        self.assertEqual((6,now+21600),cli.new_window(self.saved,6,now,now+21600))
        self.assertEqual((168,now+22000),cli.new_window(self.saved,168,now,now+22000))
        self.saved['approvedDeadlineEpoch']=now+23000
        self.assertEqual((168,now+22000),cli.new_window(self.saved,168,now,now+22000))
        self.assertEqual((168,now+23000),cli.new_window(self.saved,168,now,now+25000))
        for ttl in (0,1,5,169):
            with self.subTest(ttl=ttl),self.assertRaises(cli.Rejected):cli.new_window(self.saved,ttl,now)
        with self.assertRaises(cli.Rejected):cli.new_window(self.saved,24,now,now+100)

    def test_start_ready_or_paused_uses_power_without_new_restore(self):
        for phase,expected in [(None,['status','access']),('running',['status','access']),('stopped',['status','resume','access']),
                               ('writers-stopped',['status','resume','access'])]:
            calls=[]
            def power(action,cidr=None):
                calls.append(action)
                return {'state':'POWER_OBSERVED','phase':phase} if action=='status' else {'state':action.upper(),
                    'readinessCommand':'curl --fail --connect-to api.airbob.cloud:443:test.ap-northeast-2.elb.amazonaws.com:443 https://api.airbob.cloud/actuator/health/readiness'}
            with patch.object(self.lab,'power_action',power),patch.object(self.io,'command',return_value=(0,b'{"status":"UP"}\n200',b'',b''),create=True):result=self.lab.start(cidr='1.1.1.1/32')
            self.assertEqual(expected,calls);self.assertEqual('ACCESS',result['state'])
        with patch.object(self.lab,'power_action',return_value={'state':'POWER_OBSERVED','phase':'stopped'}),self.assertRaises(cli.Rejected):self.lab.start(ttl=168)

    def test_partial_lab_does_not_become_absent_or_start_new_run(self):
        with patch.object(self.lab,'power_action',return_value={'state':'PARTIAL'}),self.assertRaises(cli.Rejected):self.lab.start()
        self.assertIsNone(self.lab.base.read('active-start.json'))

    def test_new_start_connects_actual_operation_to_three_services_then_user_readiness(self):
        # Orchestration unit: validators below are real; expensive DB/publication
        # seams are exercised separately by their real-contract test above.
        fx=json.loads(json.dumps(self.fx).replace('lab-test-snapshot-2','lab-123-1'))
        fx['event']=fixtures.child(json.loads(fx['event']['rawUtf8']))
        source=fx['source'];key='datasets/'+snapshot.DATASET+'-mac-snapshots/'+source['snapshot']['identifier']+'/source-'+snapshot.digest(source)+'.json'
        self.saved['sourceProvenance']={**fx['op']['sourceProvenance'],'key':key}
        fx['op']['sourceProvenance']=self.saved['sourceProvenance'];restored=snapshot.validate_restore(**fx)
        original={'runId':'lab-123-1','fencingToken':fx['op']['resourceFence'],'globalBSnapshotRestoreOnly':True,
            'globalBSnapshotSourceMode':snapshot.MODE,'globalBMacSnapshotOperation':fx['op'],'expiresAt':str(fx['op']['window']['expiresAt'])}
        self.io.objects[(cli.DATASET_BUCKET,key)]=snapshot.encoded(source)
        restore_ref=fixtures.reference(snapshot.encoded(restored),'data-bootstrap/lab-123-1/mac-snapshot-201/restore.json')
        calls=[]
        def dispatch(journal,label,inputs,deadline):
            calls.append(label)
            if label=='restore':
                self.assertTrue(inputs['keep_on_failure']);self.assertEqual('snapshot',inputs['database_bootstrap'])
                self.assertEqual(snapshot.MODE,json.loads(inputs['b_operation'])['sourceMode'])
                return {'id':123}
            self.assertEqual(label,json.loads(inputs['b_operation'])['stage']);return {'id':124}
        manifest={'runId':'lab-123-1','datasetId':snapshot.DATASET,'serviceRelease':'test-service'}
        def prepare(*args):calls.append('counts-and-publication');return manifest,{'versionId':'service-version','sha256':'c'*64}
        with patch.object(self.lab,'power_action',return_value={'state':'LAB_ABSENT','runId':None,'phase':None,'expiresAt':None}), \
             patch.object(self.lab,'start_prerequisites'),patch.object(self.lab,'current_main',return_value='a'*40), \
             patch.object(cli.time,'time',return_value=1800002000),patch.object(self.lab,'dispatch',dispatch), \
             patch.object(self.lab,'read_run',return_value=(original,{})),patch.object(self.io,'latest',return_value=(snapshot.encoded(restored),restore_ref),create=True), \
             patch.object(self.lab,'prepare_service',prepare),patch.object(self.lab,'read_readiness',return_value=({}, {'versionId':'ready-version','sha256':'d'*64})), \
             patch.object(self.lab,'access',side_effect=lambda *_:calls.append('access') or {'readinessObservedAtEpoch':1800003000}):
            result=self.lab.start()
        self.assertEqual(['restore','counts-and-publication','dependencies','bootstrap','application','access'],calls)
        self.assertEqual('LAB_READY',result['state']);self.assertFalse(result['sqlReplayed'])
        self.assertEqual(2400,result['availableToUsableAppSeconds']);self.assertIsNone(self.lab.base.read('active-start.json'))

    def test_user_access_keeps_tls_hostname_pins_alb_and_rejects_redirect(self):
        response={'state':'USER_ACCESS_UPDATED','readinessCommand':'curl --fail --connect-to api.airbob.cloud:443:owned.ap-northeast-2.elb.amazonaws.com:443 https://api.airbob.cloud/actuator/health/readiness'}
        with patch.object(self.lab,'power_action',return_value=response),patch.object(self.io,'command',return_value=(0,b'{"status":"UP"}\n200',b'',b''),create=True) as command:
            self.assertTrue(self.lab.access()['userHttpsReadinessVerified'])
            argv=command.call_args.args[0]
            self.assertIn('api.airbob.cloud:443:owned.ap-northeast-2.elb.amazonaws.com:443',argv)
            self.assertEqual('https://api.airbob.cloud/actuator/health/readiness',argv[-1])
            self.assertNotIn('-k',argv);self.assertNotIn('--insecure',argv);self.assertNotIn('-L',argv)
        with patch.object(self.lab,'power_action',return_value=response),patch.object(self.io,'command',return_value=(0,b'{"status":"UP"}\n302',b'',b''),create=True),self.assertRaises(cli.Rejected):self.lab.access()

    def test_durable_journal_fsync_precedes_remote_put_and_marker_bytes_readback(self):
        journal=cli.Journal(self.root/'cas');events=[]
        publisher=cli.Publisher(self.io,journal,lambda:events.append('guard'),{cli.DATASET_BUCKET:'datasets/new/'})
        original=self.io.aws
        def aws(*args):
            if args[:2]==('s3api','put-object'):
                self.assertIsNotNone(journal.read('payload.intent.json'));events.append('put')
            return original(*args)
        with patch.object(self.io,'aws',aws),patch.object(cli.os,'fsync',side_effect=lambda *_:events.append('fsync')):
            ref=publisher.put('payload',cli.DATASET_BUCKET,'datasets/new/payload.json',b'public')
        self.assertLess(events.index('fsync'),events.index('put'))
        self.assertEqual(cli.sha(b'public'),ref['sha256']);self.assertEqual(ref,journal.read('payload.result.json'))
        args=self.io.assert_put_args
        self.assertEqual('*',args[args.index('--if-none-match')+1]);self.assertEqual('AES256',args[args.index('--server-side-encryption')+1])
        before=sum(x==('s3api','put-object') for x in self.io.events)
        self.assertEqual(ref,publisher.put('payload',cli.DATASET_BUCKET,'datasets/new/payload.json',b'public'))
        self.assertEqual(before,sum(x==('s3api','put-object') for x in self.io.events))

    def test_fsync_failure_prevents_any_put(self):
        p=cli.Publisher(self.io,cli.Journal(self.root/'syncfail'),lambda:None,{cli.DATASET_BUCKET:'datasets/new/'})
        with patch.object(cli.os,'fsync',side_effect=OSError('synthetic')),self.assertRaises(OSError):p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')
        self.assertNotIn(('s3api','put-object'),self.io.events)

    def test_evidence_put_requires_summary_retention_tag(self):
        p=cli.Publisher(self.io,cli.Journal(self.root/'evidence'),lambda:None,{cli.EVIDENCE_BUCKET:'data-bootstrap/new/'})
        p.put('result',cli.EVIDENCE_BUCKET,'data-bootstrap/new/result.json',b'public')
        args=self.io.assert_put_args;self.assertEqual('Retention=summary',args[args.index('--tagging')+1])

    def test_auth_failure_survives_actual_count_helper_failure_receipt(self):
        db=fixtures.BatchDB(snapshot.validate_source(self.source)['expectedTables'])
        def action(guard):return snapshot.validate_counts(db,self.restored,self.source,self.root/'failed-count',guard)
        def guard():raise cli.AuthStop('AWS_AUTHENTICATION_UNAVAILABLE_STOP')
        with patch.object(snapshot.time,'time',return_value=self.fx['observed_at']+10),self.assertRaises(cli.AuthStop):cli.counts_with_auth(action,guard)
        result=json.loads((self.root/'failed-count/result.json').read_bytes())
        self.assertEqual('TARGET_COUNTS_DDL_UNCONFIRMED',result['state']);self.assertEqual([],db.calls)

    def active_start(self):
        active={'operationId':'start-owned','configurationSha256':cli.sha(cli.encoded(self.saved)),
            'main':'a'*40,'ttlHours':24,'deadlineEpoch':2000000000,'startedAtEpoch':1800000000,'profile':'admin-eeoos'}
        self.lab.base.put('active-start.json',active)
        return cli.Journal(self.lab.base.path/'start-owned')

    def test_destroy_waits_owned_queued_start_once_before_absent_success(self):
        started=self.active_start();started.put('restore.intent.json',{'repository':self.saved['githubRepository'],'workflow':cli.WORKFLOW,
            'inputs':{'expected_execution_commit':'a'*40}});started.put('restore.dispatch.json',{'workflow_run_id':123})
        row={'id':123,'head_sha':'a'*40,'head_branch':'main','event':'workflow_dispatch','run_attempt':1,'path':'.github/workflows/'+cli.WORKFLOW}
        events=[];runs=iter([row|{'status':'queued','conclusion':None},row|{'status':'completed','conclusion':'cancelled'}])
        self.lab.power=types.SimpleNamespace(resources=lambda v:v['resources'])
        def gh(*args,**kwargs):events.append('read-start');return next(runs)
        def command(*args,**kwargs):events.append('cancel');return (0,b'',b'',b'')
        def backend():events.append('backend');return None,{'resources':[]}
        with patch.object(self.io,'gh',gh,create=True),patch.object(self.io,'command',command,create=True),patch.object(cli.time,'sleep'), \
             patch.object(self.lab,'backend',backend),patch.object(self.lab,'retained_snapshot',return_value=None):
            result=self.lab.destroy()
        self.assertEqual(['read-start','cancel','read-start','backend','backend'],events)
        self.assertEqual('LAB_ABSENT',result['state']);self.assertIsNone(self.lab.base.read('active-start.json'))

    def test_unknown_start_dispatch_blocks_destroy_absent_claim(self):
        self.active_start().put('restore.intent.json',{'inputs':{}})
        with patch.object(self.lab,'backend',side_effect=AssertionError('must settle first')),self.assertRaisesRegex(cli.Rejected,'UNCONFIRMED'):
            self.lab.destroy()
        self.assertIsNotNone(self.lab.base.read('active-start.json'))

    def test_destroy_retry_waits_original_exact_down_without_a_second_post(self):
        active={'operationId':'destroy-owned','profile':self.lab.profile,'configurationSha256':cli.sha(cli.encoded(self.saved)),'deadlineEpoch':2000000000}
        self.lab.base.put('active-destroy.json',active);journal=cli.Journal(self.lab.base.path/active['operationId'])
        journal.put('down.intent.json',{'inputs':{'run_id':'lab-old','expected_execution_commit':'a'*40}})
        journal.put('down.dispatch.json',{'workflow_run_id':321})
        row={'id':321,'head_sha':'a'*40,'head_branch':'main','event':'workflow_dispatch','run_attempt':1,
            'path':'.github/workflows/'+cli.WORKFLOW,'status':'completed','conclusion':'success','created_at':'fixture','updated_at':'fixture'}
        self.lab.power=types.SimpleNamespace(resources=lambda v:v['resources'])
        with patch.object(self.io,'gh',return_value=row,create=True) as gh,patch.object(self.lab,'current_main',side_effect=AssertionError('read known run, no new post')), \
             patch.object(self.lab,'backend',return_value=(None,{'resources':[]})),patch.object(self.lab,'retained_snapshot',return_value=None):
            result=self.lab.destroy()
        self.assertEqual(321,result['workflowRunId']);self.assertEqual(1,gh.call_count)
        self.assertEqual('actions/runs/321',gh.call_args.args[1]);self.assertIsNone(self.lab.base.read('active-destroy.json'))

    def failed_destroy(self):
        active={'operationId':'destroy-failed','profile':self.lab.profile,
            'configurationSha256':cli.sha(cli.encoded(self.saved)),'deadlineEpoch':1}
        self.lab.base.put('active-destroy.json',active);journal=cli.Journal(self.lab.base.path/active['operationId'])
        journal.put('down.intent.json',{'inputs':{'run_id':'lab-old','expected_execution_commit':'a'*40}})
        journal.put('down.dispatch.json',{'workflow_run_id':321})
        row={'id':321,'head_sha':'a'*40,'head_branch':'main','event':'workflow_dispatch','run_attempt':1,
            'path':'.github/workflows/'+cli.WORKFLOW,'status':'completed','conclusion':'failure','created_at':'fixture','updated_at':'fixture'}
        journal.put('down.completed.json',row)
        self.lab.power=types.SimpleNamespace(resources=lambda value:value['resources'])
        return journal,row

    def test_expired_failed_destroy_reconciles_manual_empty_state_without_replay(self):
        journal,row=self.failed_destroy()
        before={path.name:path.read_bytes() for path in journal.path.iterdir()}
        empty={'resources':[],'lineage':'same-backend-lineage','serial':596}
        with patch.object(self.io,'gh',return_value=row,create=True) as gh, \
             patch.object(self.lab,'current_main',side_effect=AssertionError('no new dispatch')), \
             patch.object(self.lab,'backend',return_value=(None,empty)), \
             patch.object(self.lab,'retained_snapshot',return_value=None) as snapshot:
            result=self.lab.destroy()
        self.assertEqual('LAB_ABSENT',result['state']);self.assertEqual('failure',result['workflowConclusion'])
        self.assertTrue(result['reconciledFailedWorkflow']);self.assertFalse(result['cleanupPerformedByThisCommand'])
        self.assertEqual(321,result['workflowRunId']);snapshot.assert_called_once_with()
        self.assertEqual(1,gh.call_count);self.assertEqual('actions/runs/321',gh.call_args.args[1])
        self.assertIsNone(self.lab.base.read('active-destroy.json'))
        self.assertEqual(before,{name:(journal.path/name).read_bytes() for name in before})
        proof=journal.read('reconciliation.json')
        self.assertEqual({'lineage':empty['lineage'],'serial':596,'canonicalSha256':cli.sha(cli.encoded(empty))},proof['emptyState'])
        self.assertFalse(proof['cleanupPerformedByThisCommand'])
        self.assertNotIn('teardownFinalized',proof)

    def test_failed_destroy_with_remaining_resources_keeps_original_failure_and_pointer(self):
        journal,row=self.failed_destroy()
        with patch.object(self.io,'gh',return_value=row,create=True), \
             patch.object(self.lab,'backend',return_value=(None,{'resources':['retained-rds']})), \
             patch.object(self.lab,'retained_snapshot',side_effect=AssertionError('not empty')), \
             self.assertRaisesRegex(cli.Rejected,'WORKFLOW_FAILED_RESOURCES_RETAINED'):
            self.lab.destroy()
        self.assertIsNotNone(self.lab.base.read('active-destroy.json'))
        self.assertEqual('failure',journal.read('down.completed.json')['conclusion'])
        self.assertIsNone(journal.read('reconciliation.json'));self.assertIsNone(journal.read('result.json'))

    def test_expired_destroy_reads_known_terminal_start_before_reconciling(self):
        journal,down=self.failed_destroy();started=self.active_start()
        started.put('restore.intent.json',{'repository':self.saved['githubRepository'],'workflow':cli.WORKFLOW,
            'inputs':{'expected_execution_commit':'a'*40}})
        started.put('restore.dispatch.json',{'workflow_run_id':123})
        completed=down|{'id':123,'conclusion':'cancelled'}
        with patch.object(self.io,'gh',side_effect=[completed,down],create=True) as gh, \
             patch.object(self.io,'command',side_effect=AssertionError('terminal run must not be cancelled'),create=True), \
             patch.object(self.lab,'backend',return_value=(None,{'resources':[],'outputs':{},'lineage':'fixture','serial':596})), \
             patch.object(self.lab,'retained_snapshot',return_value=None):
            result=self.lab.destroy()
        self.assertEqual('LAB_ABSENT',result['state'])
        self.assertEqual(['actions/runs/123','actions/runs/321'],[call.args[1] for call in gh.call_args_list])
        self.assertEqual('cancelled',journal.read('settled-start-restore.json')['conclusion'])
        self.assertIsNone(self.lab.base.read('active-start.json'))

    def test_expired_destroy_cannot_cancel_or_skip_nonterminal_start(self):
        journal,_=self.failed_destroy();started=self.active_start()
        started.put('restore.intent.json',{'repository':self.saved['githubRepository'],'workflow':cli.WORKFLOW,
            'inputs':{'expected_execution_commit':'a'*40}})
        started.put('restore.dispatch.json',{'workflow_run_id':123})
        pending={'id':123,'head_sha':'a'*40,'head_branch':'main','event':'workflow_dispatch','run_attempt':1,
            'path':'.github/workflows/'+cli.WORKFLOW,'status':'in_progress','conclusion':None}
        with patch.object(self.io,'gh',return_value=pending,create=True) as gh, \
             patch.object(self.io,'command',side_effect=AssertionError('expired cancellation'),create=True), \
             patch.object(self.lab,'backend',side_effect=AssertionError('start not terminal')), \
             self.assertRaisesRegex(cli.Rejected,'OWN_START_TERMINAL_NOT_CONFIRMED'):
            self.lab.destroy()
        self.assertEqual(1,gh.call_count);self.assertIsNotNone(self.lab.base.read('active-start.json'))
        self.assertIsNotNone(self.lab.base.read('active-destroy.json'));self.assertIsNone(journal.read('reconciliation.json'))

    def test_failed_destroy_reconciliation_accepts_matching_retained_identity_output(self):
        _,row=self.failed_destroy()
        empty={'resources':[],'lineage':'fixture','serial':596,
            'outputs':{'run_identity':{'value':{'run_id':'lab-old','resource_fencing_token':76}}}}
        with patch.object(self.io,'gh',return_value=row,create=True), \
             patch.object(self.lab,'backend',return_value=(None,empty)), \
             patch.object(self.lab,'retained_snapshot',return_value=None):
            self.assertEqual('LAB_ABSENT',self.lab.destroy()['state'])

    def test_failed_destroy_reconciliation_rejects_different_or_invalid_present_identity(self):
        journal,row=self.failed_destroy()
        for identity in ({'run_id':'lab-other','resource_fencing_token':77},None):
            empty={'resources':[],'outputs':{'run_identity':{'value':identity}}}
            with self.subTest(identity=identity),patch.object(self.io,'gh',return_value=row,create=True), \
                 patch.object(self.lab,'backend',return_value=(None,empty)), \
                 patch.object(self.lab,'retained_snapshot',side_effect=AssertionError('identity changed')), \
                 self.assertRaisesRegex(cli.Rejected,'EMPTY_STATE_RUN_IDENTITY_CHANGED'):
                self.lab.destroy()
            self.assertIsNotNone(self.lab.base.read('active-destroy.json'));self.assertIsNone(journal.read('reconciliation.json'))

    def test_failed_destroy_does_not_retire_pointer_when_snapshot_verification_fails(self):
        journal,row=self.failed_destroy()
        with patch.object(self.io,'gh',return_value=row,create=True), \
             patch.object(self.lab,'backend',return_value=(None,{'resources':[]})), \
             patch.object(self.lab,'retained_snapshot',side_effect=cli.Rejected('SNAPSHOT_CHANGED')), \
             self.assertRaisesRegex(cli.Rejected,'SNAPSHOT_CHANGED'):
            self.lab.destroy()
        self.assertIsNotNone(self.lab.base.read('active-destroy.json'));self.assertIsNone(journal.read('reconciliation.json'))

    def test_expired_down_still_running_cannot_claim_empty_or_dispatch_again(self):
        journal,row=self.failed_destroy();row=row|{'status':'in_progress','conclusion':None}
        with patch.object(self.io,'gh',return_value=row,create=True) as gh, \
             patch.object(self.lab,'backend',side_effect=AssertionError('run not terminal')), \
             self.assertRaisesRegex(cli.Rejected,'WORKFLOW_WAIT_DEADLINE'):
            self.lab.destroy()
        self.assertEqual(1,gh.call_count);self.assertIsNotNone(self.lab.base.read('active-destroy.json'))
        self.assertIsNone(journal.read('reconciliation.json'))

    def test_unknown_down_dispatch_cannot_reconcile_even_if_state_would_be_empty(self):
        journal,_=self.failed_destroy();(journal.path/'down.dispatch.json').unlink()
        with patch.object(self.lab,'current_main',return_value='a'*40), \
             patch.object(self.io,'gh',side_effect=AssertionError('must not redispatch'),create=True), \
             patch.object(self.lab,'backend',side_effect=AssertionError('unknown workflow')), \
             self.assertRaisesRegex(cli.Rejected,'WORKFLOW_DISPATCH_UNCONFIRMED_NO_RETRY'):
            self.lab.destroy()
        self.assertIsNotNone(self.lab.base.read('active-destroy.json'));self.assertIsNone(journal.read('reconciliation.json'))

    def test_ambiguous_put_reads_once_and_never_replays_missing_object(self):
        for phase in ('before','after'):
            with self.subTest(phase=phase):
                io=FakeIO();io.fail_put=phase;p=cli.Publisher(io,cli.Journal(self.root/phase),lambda:None,{cli.DATASET_BUCKET:'datasets/new/'})
                if phase=='after':self.assertEqual(6,p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')['bytes'])
                else:
                    with self.assertRaises(cli.Rejected):p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')
                    with self.assertRaisesRegex(cli.Rejected,'NO_RETRY'):p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')
                self.assertEqual(1,sum(x==('s3api','put-object') for x in io.events))

    def test_delete_marker_or_foreign_bytes_are_not_reused(self):
        journal=cli.Journal(self.root/'foreign');p=cli.Publisher(self.io,journal,lambda:None,{cli.DATASET_BUCKET:'datasets/new/'})
        self.io.markers=[{'Key':'datasets/new/x'}]
        with self.assertRaises(cli.Rejected):p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')
        self.io.markers=[];self.io.objects[(cli.DATASET_BUCKET,'datasets/new/x')]=b'foreign';self.io.versions[(cli.DATASET_BUCKET,'datasets/new/x')]='foreign'
        with self.assertRaises(cli.Rejected):p.put('x',cli.DATASET_BUCKET,'datasets/new/x',b'public')
        self.assertNotIn(('s3api','put-object'),self.io.events)

    def test_actual_anonymous_pipe_body_stays_in_memory_and_reaches_eof(self):
        io=cli.IO('test-profile');read_fd,write_fd=os.pipe()
        code='import os,sys;os.write(int(sys.argv[1]),b"public-body");print("{}")'
        try:
            result=io.command([sys.executable,'-B','-c',code,str(write_fd)],extra_fd=read_fd,pass_fds=(write_fd,),timeout=3)
            self.assertEqual(b'public-body',result[3]);self.assertEqual(b'{}\n',result[1])
        finally:os.close(read_fd)

    def test_gh_dispatch_requires_actual_returned_run_and_never_reposts_unknown(self):
        journal=cli.Journal(self.root/'github');calls=[]
        inputs={'expected_execution_commit':'a'*40,'action':'snapshot-restore'}
        def gh(repo,path,body=None):calls.append(body);return {}
        with patch.object(self.lab,'current_main',return_value='a'*40),patch.object(self.io,'gh',gh,create=True):
            with self.assertRaises(cli.Rejected):self.lab.dispatch(journal,'restore',inputs,2000000000)
            with self.assertRaisesRegex(cli.Rejected,'NO_RETRY'):self.lab.dispatch(journal,'restore',inputs,2000000000)
        self.assertEqual(1,len(calls));self.assertIsNotNone(journal.read('restore.intent.json'))

    def test_gh_exact_success_and_wrong_head_rejected(self):
        for head in ('a'*40,'b'*40):
            journal=cli.Journal(self.root/head[:1]);calls=[]
            def gh(repo,path,body=None):
                calls.append(path)
                if body is not None:return {'workflow_run_id':123}
                return {'id':123,'head_sha':head,'head_branch':'main','event':'workflow_dispatch','run_attempt':1,
                    'path':'.github/workflows/'+cli.WORKFLOW,'status':'completed','conclusion':'success','created_at':'future-test','updated_at':'future-test'}
            with patch.object(self.lab,'current_main',return_value='a'*40),patch.object(self.io,'gh',gh,create=True):
                if head=='a'*40:self.assertEqual(123,self.lab.dispatch(journal,'restore',{'expected_execution_commit':'a'*40},2000000000)['id'])
                else:
                    with self.assertRaisesRegex(cli.Rejected,'IDENTITY'):self.lab.dispatch(journal,'restore',{'expected_execution_commit':'a'*40},2000000000)

    def test_count_transport_only_reviewed_reads_with_headers_and_heartbeat(self):
        calls=[]
        class Cursor:
            description=[('rowCount',)]
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def execute(self,sql):calls.append(sql)
            def fetchall(self):return [(160882380,)]
        db=types.SimpleNamespace(cursor=lambda:Cursor());guards=[]
        adapter=cli.CountDB(db,lambda:guards.append(True),{'member':{}})
        self.assertEqual('rowCount\n160882380\n',adapter.execute('SELECT COUNT(*) AS rowCount FROM `airbobdb`.`member`;'))
        with self.assertRaises(cli.Rejected):adapter.execute('DELETE FROM member')
        self.assertEqual(1,len(calls))

    @unittest.skipUnless(os.environ.get('AIRBOB_RUNTIME_BINDING_FIXTURE'),'Exact public app-runtime-binding fixture is external to repository')
    def test_real_snapshot_and_runtime_validators_complete_cas_manifest_last(self):
        runtime=Path(os.environ['AIRBOB_RUNTIME_BINDING_FIXTURE']).read_bytes()
        self.assertEqual('a9e625ec256ecdbbf99e812c43ebccd544f2fa42f6529bd6c3e0a1ef3076f880',cli.sha(runtime))
        journal=cli.Journal(self.root/'complete');(journal.path/'counts').mkdir()
        counts=fixtures.synthetic_counts(self.source,self.restored)
        selected='counts-attempt-'+'1'*20;(journal.path/selected/'counts').mkdir(parents=True)
        (journal.path/selected/'counts/result.json').write_bytes(cli.encoded(counts))
        journal.put('counts-selected.json',{'directory':selected,'sha256':cli.sha(cli.encoded(counts))})
        old=snapshot.validate_source(self.source)['documents']['serviceManifest']
        self.io.objects[(cli.DATASET_BUCKET,old['appRuntimeBinding']['key'])]=runtime
        class Runner:
            @contextlib.contextmanager
            def acquired(self,state):yield
        state={'outputs':{'run_identity':{'value':{'run_id':self.restored['operation']['runId'],'resource_fencing_token':self.restored['operation']['resourceFence']}}}}
        self.lab.power=types.SimpleNamespace(outputs=lambda v:{k:r['value'] for k,r in v['outputs'].items()})
        with patch.object(self.lab,'current_main',return_value='a'*40),patch.object(self.lab,'backend',return_value=(Runner(),state)), \
             patch.object(self.lab,'target_guard',return_value=None):
            manifest,ref=self.lab.prepare_service(journal,{'main':'a'*40,'operationId':'start-synthetic-only'},self.source,self.restored,
                fixtures.reference(cli.encoded(self.restored),'data-bootstrap/'+self.restored['operation']['runId']+'/restore.json'))
        self.assertEqual(manifest,snapshot.service.validate_manifest(manifest,snapshot.DATASET,manifest['runId'],manifest['serviceRelease']))
        puts=[key for bucket,key in self.io.objects if key.startswith('datasets/'+snapshot.DATASET+'-aws-service/mac-snapshot-synthetic-only/')]
        self.assertTrue(puts[-1].endswith('/aws-service.json'))
        self.assertFalse(manifest['preparation'].get('preparedFingerprintSha256'))
        self.assertEqual(12,len(manifest['toolSources']));self.assertEqual(ref,journal.read('service-manifest-reference.json'))

    @unittest.skipUnless(os.environ.get('AIRBOB_RUNTIME_BINDING_FIXTURE'),'Exact public app-runtime-binding fixture is external to repository')
    def test_later_explicit_start_preserves_failed_counts_and_uses_new_attempt(self):
        journal=cli.Journal(self.root/'retry');failed=cli.Journal(journal.path/('counts-attempt-'+'0'*20))
        db=fixtures.BatchDB(snapshot.validate_source(self.source)['expectedTables'])
        with patch.object(snapshot.time,'time',return_value=self.fx['observed_at']+10),self.assertRaises(ValueError):
            snapshot.validate_counts(db,self.restored,self.source,failed.path/'counts')
        old=(failed.path/'counts/result.json').read_bytes();self.assertIn(b'TARGET_COUNTS_DDL_UNCONFIRMED',old)
        counts=fixtures.synthetic_counts(self.source,self.restored);attempts=[]
        def transport(attempt,*args):
            attempts.append(attempt.path);(attempt.path/'counts').mkdir()
            (attempt.path/'counts/result.json').write_bytes(cli.encoded(counts));return counts
        old_manifest=snapshot.validate_source(self.source)['documents']['serviceManifest']
        self.io.objects[(cli.DATASET_BUCKET,old_manifest['appRuntimeBinding']['key'])]=Path(os.environ['AIRBOB_RUNTIME_BINDING_FIXTURE']).read_bytes()
        class Runner:
            @contextlib.contextmanager
            def acquired(self,state):yield
        state={'outputs':{'run_identity':{'value':{'run_id':self.restored['operation']['runId'],'resource_fencing_token':self.restored['operation']['resourceFence']}}}}
        self.lab.power=types.SimpleNamespace(outputs=lambda v:{k:r['value'] for k,r in v['outputs'].items()})
        with patch.object(self.lab,'current_main',return_value='a'*40),patch.object(self.lab,'backend',return_value=(Runner(),state)), \
             patch.object(self.lab,'target_guard',return_value=None),patch.object(self.lab,'count_target',transport):
            self.lab.prepare_service(journal,{'main':'a'*40,'operationId':'start-retry-only'},self.source,self.restored,
                fixtures.reference(cli.encoded(self.restored),'data-bootstrap/'+self.restored['operation']['runId']+'/restore.json'))
        self.assertEqual(1,len(attempts));self.assertNotEqual(failed.path,attempts[0])
        self.assertEqual(old,(failed.path/'counts/result.json').read_bytes())
        self.assertEqual(attempts[0].name,journal.read('counts-selected.json')['directory'])


if __name__=='__main__':unittest.main()
