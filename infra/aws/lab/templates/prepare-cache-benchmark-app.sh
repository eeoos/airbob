#!/usr/bin/env bash
# An existing B app may still be seeding reservation inventory. This handoff
# verifies its original runtime without starting/restarting it or admitting HTTP.
# The cache runner must install its read-only profile and verify readiness itself.
set -euo pipefail
umask 077
python3 - <<'PY'
import hashlib,json,os,pathlib,subprocess,time

root=pathlib.Path('/etc/airbob')
def need(value, message):
    if not value: raise RuntimeError(message)
def command(args):
    return subprocess.check_output(args,text=True,stderr=subprocess.DEVNULL,timeout=30)
def fields(path):
    return dict(line.split('=',1) for line in path.read_text().splitlines() if line and not line.startswith('#'))
contract=json.loads((root/'app-runtime-contract.env').read_text())
need((root/'service-name').read_text().strip()=='app'
     and contract['kind']=='global-growth-b-app-runtime'
     and contract['region']=='ap-northeast-2', 'Existing B application required')
need(contract.get('readiness') and contract['appRuntime']['image']==contract['application']['image']
     and contract['appRuntime']['mainCommit']==contract['application']['mainCommit'], 'B runtime identity differs')
original=root/'app.env';images=root/'images.env'
need(original.is_file() and images.is_file() and not original.is_symlink() and not images.is_symlink(),
     'Original runtime files required')
original_values=fields(original)
need(fields(images)['APP_IMAGE']==contract['application']['image']
     and original_values.get('SPRING_PROFILES_ACTIVE')=='aws', 'Unmodified original B runtime required')
ids=command(['docker','ps','-a','--filter','label=com.docker.compose.service=app','--format','{{.ID}}']).splitlines()
need(len(ids)==1, 'Exactly one existing app container required')
app=json.loads(command(['docker','inspect',ids[0]]))[0]
active=dict(value.split('=',1) for value in app['Config']['Env'] if '=' in value)
need(app['Config']['Image']==contract['application']['image'] and active.get('SPRING_PROFILES_ACTIVE')=='aws'
     and app['State']['Running'] and app['State'].get('Health',{}).get('Status') in ('starting','healthy'),
     'Original B app must be running, ready or initializing')
need(all(active.get(key)==value for key,value in original_values.items()), 'Running original environment differs')
image=json.loads(command(['docker','image','inspect',contract['application']['image']]))[0]
need(image['Id']==app['Image']
     and image['Config']['Labels'].get('org.opencontainers.image.revision')==contract['application']['mainCommit'],
     'Original image revision differs')
need(command(['docker','exec',ids[0],'sha256sum','/app/app.jar']).split()[0]==contract['appRuntime']['imageJarSha256'],
     'Original application bytes differ')
marker={'kind':'cache-benchmark-handoff','runId':contract['runId'],'containerId':app['Id'],
        'imageId':app['Image'],'originalEnvSha256':hashlib.sha256(original.read_bytes()).hexdigest(),
        'originalImagesSha256':hashlib.sha256(images.read_bytes()).hexdigest(),'observedAtEpoch':int(time.time())}
temporary=root/'cache-benchmark-handoff.tmp'
with os.fdopen(os.open(temporary,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600),'w') as stream:
    json.dump(marker,stream)
os.replace(temporary,root/'cache-benchmark-handoff.json')
print('CACHE_BENCHMARK_HANDOFF_VERIFIED; application readiness has not been asserted')
PY
