"""Exercise the actual handoff script with a local Docker stub, never AWS."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_global_b_infrastructure import attribute

ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / 'infra/aws/lab/templates/prepare-cache-benchmark-app.sh'


class CacheAppHandoffTest(unittest.TestCase):
    def run_handoff(self, change=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); bins = root / 'bin'; bins.mkdir()
            contract = {'kind': 'global-growth-b-app-runtime', 'region': 'ap-northeast-2',
                        'runId': 'lab-fixture', 'readiness': {'sha256': 'a'*64},
                        'application': {'image': 'original@sha256:'+'b'*64, 'mainCommit': 'c'*40}}
            contract['appRuntime'] = contract['application'] | {'imageJarSha256': 'd'*64}
            (root/'app-runtime-contract.env').write_text(json.dumps(contract))
            (root/'service-name').write_text('app\n')
            original = 'SPRING_PROFILES_ACTIVE=aws\nSPRING_DATASOURCE_PASSWORD=private-fixture\n'
            (root/'app.env').write_text(original)
            (root/'images.env').write_text('APP_IMAGE='+contract['application']['image']+'\n')
            app = {'Id': 'original-container', 'Image': 'image-id',
                   'State': {'Running': True, 'Health': {'Status': 'starting'}},
                   'Config': {'Image': contract['application']['image'], 'Env': original.splitlines()}}
            if change: change(app)
            (root/'app.json').write_text(json.dumps(app))
            (root/'image.json').write_text(json.dumps({'Id': 'image-id', 'Config': {'Labels': {
                'org.opencontainers.image.revision': 'c'*40}}}))
            stub = bins/'docker'
            stub.write_text('''#!/usr/bin/env python3
import json,os,pathlib,sys
p=pathlib.Path(os.environ['HANDOFF_FIXTURE']);a=sys.argv[1:]
with (p/'docker-calls.jsonl').open('a') as f:f.write(json.dumps(a)+'\\n')
if a[0]=='ps':print('original-container')
elif a[0]=='inspect':print('['+(p/'app.json').read_text()+']')
elif a[:2]==['image','inspect']:print('['+(p/'image.json').read_text()+']')
elif a==['exec','original-container','sha256sum','/app/app.jar']:print('d'*64+'  /app/app.jar')
else:raise SystemExit('Unexpected Docker mutation')
''')
            stub.chmod(0o700)
            source = SCRIPT.read_text().replace("pathlib.Path('/etc/airbob')", 'pathlib.Path('+repr(str(root))+')')
            completed = subprocess.run(['bash'], input=source, text=True, capture_output=True, timeout=30,
                env={'PATH': str(bins)+os.pathsep+os.environ['PATH'], 'HANDOFF_FIXTURE': str(root)})
            marker = root/'cache-benchmark-handoff.json'
            result = json.loads(marker.read_text()) if marker.exists() else None
            self.assertEqual((root/'app.env').read_text(), original)
            self.assertNotIn('private-fixture', completed.stdout+completed.stderr)
            return completed, result

    def test_initializing_app_is_bound_without_restart_or_readiness_claim(self):
        result, marker = self.run_handoff()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(marker['containerId'], 'original-container')
        self.assertEqual(marker['runId'], 'lab-fixture')
        self.assertEqual(marker['originalEnvSha256'], hashlib.sha256(
            b'SPRING_PROFILES_ACTIVE=aws\nSPRING_DATASOURCE_PASSWORD=private-fixture\n').hexdigest())
        self.assertIn('readiness has not been asserted', result.stdout)

    def test_changed_image_profile_environment_and_failed_app_are_rejected(self):
        changes = [lambda app: app['Config'].update(Image='other-image'),
                   lambda app: app['Config'].update(Env=['SPRING_PROFILES_ACTIVE=aws,cache-benchmark']),
                   lambda app: app['Config']['Env'].append('SPRING_DATASOURCE_PASSWORD=changed'),
                   lambda app: app['State']['Health'].update(Status='unhealthy'),
                   lambda app: app['State'].update(Running=False)]
        for change in changes:
            with self.subTest(change=change):
                result, marker = self.run_handoff(change)
                self.assertNotEqual(result.returncode, 0)
                self.assertIsNone(marker)

    def test_only_cache_opt_in_selects_the_handoff_document(self):
        expression = attribute((ROOT/'infra/aws/lab/app.tf').read_text(), 'start_app_document')
        for cache, handoff in ((False, False), (True, False), (True, True)):
            with self.subTest(cache=cache, handoff=handoff), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp); lab = root/'lab'; lab.mkdir()
                (lab/'templates').symlink_to(ROOT/'infra/aws/lab/templates', target_is_directory=True)
                (root/'scripts').symlink_to(ROOT/'infra/aws/scripts', target_is_directory=True)
                (lab/'main.tf').write_text('variable "global_b_services" { default = true }\n'
                    +'variable "cache_benchmark_enabled" { default = '+str(cache).lower()+' }\n'
                    +'variable "cache_benchmark_existing_app_handoff" { default = '+str(handoff).lower()+' }\n'
                    +'locals {\n application_infrastructure_enabled = true\n selected = '+expression+'\n}\n')
                result = subprocess.run(['terraform','-chdir='+str(lab),'console','-no-color'],
                    input='jsonencode(local.selected)\n',capture_output=True,text=True,timeout=30,
                    env={'PATH':os.environ['PATH'],'CHECKPOINT_DISABLE':'1','AWS_EC2_METADATA_DISABLED':'true'})
                self.assertEqual(result.returncode,0,result.stderr)
                expected = SCRIPT if handoff else ROOT/'infra/aws/lab/templates/start-growth-b-app.sh.tftpl'
                self.assertEqual(json.loads(json.loads(result.stdout)),expected.read_text())

    def test_normal_application_still_waits_for_readiness(self):
        source=(ROOT/'infra/aws/scripts/aws-lab.sh').read_text()
        start=source.index('  if [[ "$stage" == application && "$cache_benchmark_existing_app_handoff" != true ]]; then')
        body=source[start:source.index('  write_terraform_output_evidence required',start)]
        harness='''set -eu
stage=application
lab_root=unused
run_terraform_command(){ printf '%s\\n' '{"auto_scaling_group_name":"asg","target_group_arn":"tg","alb_dns_name":"alb"}'; }
wait_for_application(){ printf 'WAIT:%s\\n' "$1"; }
curl(){ printf '%s\\n' '{"status":"UP"}'; }
fail(){ exit 1; }
'''
        for cache in ('true','false'):
            with self.subTest(cache=cache):
                result=subprocess.run(['bash'],input=harness+body,capture_output=True,text=True,timeout=10,
                    env={'PATH':os.environ['PATH'],'cache_benchmark_existing_app_handoff':cache})
                self.assertEqual(result.returncode,0,result.stderr)
                self.assertEqual('WAIT:18000' in result.stdout,cache=='false')


if __name__ == '__main__':
    unittest.main()
