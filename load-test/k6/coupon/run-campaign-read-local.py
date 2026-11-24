#!/usr/bin/env python3
"""Real HTTP COUNT/EXISTS pilot on disposable MySQL/Redis and an unchanged V1 baseline."""
import argparse
import hashlib
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.request

from run_local_e2e import environment, command, request

ROOT = Path(__file__).resolve().parents[3]
STRATEGIES = ['SKIP_SURROGATE_RR', 'SKIP_SURROGATE_RC', 'SKIP_COMPOSITE_RR', 'SKIP_COMPOSITE_RC']


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def run(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    jar = output / 'application.jar'
    shutil.copy2(ROOT/'build/libs/airbob.jar', jar)
    script = output/'comparison.js'
    shutil.copy2(Path(__file__).with_name('campaign-read-comparison.js'), script)
    shutil.copy2(Path(__file__), output/'runner.py')
    shutil.copy2(Path(__file__).with_name('run_local_e2e.py'), output/'runtime-helpers.py')
    containers, app, results = [], None, []
    java = str(args.java_home/'bin/java') if args.java_home else 'java'
    java_version = subprocess.run([java,'-version'],capture_output=True,text=True,check=True).stderr.strip()
    metadata = {'scope': 'local synthetic campaign data; real Spring HTTP and MySQL',
                'sameHost': True, 'stockPerCampaign': 10000, 'campaigns': STRATEGIES,
                'rounds': args.rounds, 'rate': args.rate, 'measureSeconds': args.seconds,
                'warmupSeconds': 5, 'javaHeapMiB': 512, 'hikariPool': 10,
                'scenarios': args.scenarios,
                'digestSnapshotQuietSamples': 2, 'digestSnapshotIntervalMs': 100,
                'runnerSha256': hashlib.sha256((output/'runner.py').read_bytes()).hexdigest(),
                'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                'sourceCommit': command(['git','rev-parse','HEAD']), 'workingTreeChanges': True, 'javaVersion':java_version}
    save(output/'metadata.json', metadata)
    try:
        with tempfile.TemporaryDirectory(prefix='airbob-campaign-read-') as private_name:
            private = Path(private_name)
            password, token, db_password = secrets.token_urlsafe(12), secrets.token_urlsafe(32), secrets.token_urlsafe(24)
            envfile = private/'mysql.env'
            envfile.write_text(f'MYSQL_ROOT_PASSWORD={db_password}\nMYSQL_DATABASE=coupon_read\nMYSQL_USER=coupon_app\nMYSQL_PASSWORD={db_password}\n')
            envfile.chmod(0o600)
            run_id = secrets.token_hex(5)
            ports = {}
            for name, image, port, before, after in [
                ('mysql','mysql:8.4.11',3306,['--env-file',str(envfile),'--memory','768m','--cpus','2'],
                 ['--innodb-buffer-pool-size=268435456','--performance-schema-max-digest-length=8192','--max-digest-length=8192']),
                ('redis','redis:7.2-alpine',6379,['--memory','128m'],[]),
            ]:
                image_id = command(['docker','image','inspect',image,'--format','{{.Id}}'])
                container = command(['docker','run','--detach','--name',f'airbob-campaign-read-{run_id}-{name}',
                                     '--label',f'airbob.campaign-read={run_id}','--publish',f'127.0.0.1::{port}',*before,image_id,*after])
                containers.append(container)
                info = json.loads(command(['docker','inspect',container]))[0]
                ports[name] = info['NetworkSettings']['Ports'][f'{port}/tcp'][0]['HostPort']
                metadata[name+'ImageId'] = image_id
            def sql(query):
                return command(['docker','exec','-i',containers[0],'sh','-c',
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 --batch --raw --skip-column-names coupon_read'],input=query)
            for _ in range(120):
                try:
                    sql('SELECT 1;')
                    break
                except subprocess.CalledProcessError:
                    time.sleep(1)
            else:
                raise RuntimeError('Disposable MySQL startup timeout')
            properties = {
                'spring.datasource.url': f"jdbc:mysql://127.0.0.1:{ports['mysql']}/coupon_read?allowPublicKeyRetrieval=true&useSSL=false",
                'spring.datasource.username':'coupon_app','spring.datasource.password':db_password,
                'spring.datasource.hikari.maximum-pool-size':'10','spring.datasource.hikari.minimum-idle':'10',
                'spring.datasource.hikari.transaction-isolation':'TRANSACTION_REPEATABLE_READ',
                'spring.data.redis.host':'127.0.0.1','spring.data.redis.port':ports['redis'],
                'spring.jpa.open-in-view':'false','spring.jpa.show-sql':'false','spring.flyway.baseline-on-migrate':'false',
                'spring.kafka.bootstrap-servers':'127.0.0.1:9','spring.kafka.listener.auto-startup':'false',
                'spring.kafka.admin.auto-create':'false','operator-alert.kafka.auto-startup':'false',
                'accommodation.indexing.kafka.auto-startup':'false','accommodation.indexing.bootstrap.enabled':'false',
                'accommodation.detail-cache.invalidation.kafka.auto-startup':'false','accommodation.detail-cache.enabled':'false',
                'accommodation.detail-cache.redis.host':'127.0.0.1','accommodation.detail-cache.redis.port':ports['redis'],
                'spring.elasticsearch.uris':'http://127.0.0.1:9','management.health.elasticsearch.enabled':'false',
                'reservation.inventory.startup.enabled':'false','reservation.inventory.seed.enabled':'false',
                'reservation.inventory.retention.enabled':'false','operator-alert.slack.enabled':'false',
                'payment.toss.enabled':'false','payment.toss.secret-key':'local-disabled','payment.toss.base-url':'http://127.0.0.1:9',
                'google.api.enabled':'false','google.api.key':'local-disabled','cloud.aws.s3.write-enabled':'false',
                'cloud.aws.s3.bucket':'local-disabled','cloud.cloudfront.domain':'http://127.0.0.1:9',
                'spring.cloud.aws.credentials.access-key':'local-disabled','spring.cloud.aws.credentials.secret-key':'local-disabled',
                'spring.cloud.aws.region.static':'ap-northeast-2','spring.cloud.aws.s3.endpoint':'http://127.0.0.1:9',
                'management.endpoints.web.exposure.include':'health,prometheus',
                'logging.level.kr.kro.airbob.domain.auth.filter.SessionAuthFilter':'WARN','logging.level.org.hibernate.SQL':'OFF',
            }
            config = private/'application.properties'
            config.write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n')
            config.chmod(0o600)
            env = environment() | {'SPRING_PROFILES_ACTIVE':'test,coupon-benchmark',
                                   'BENCHMARK_READ_MODEL_TOKEN':token,'AWS_EC2_METADATA_DISABLED':'true'}
            with (output/'application.log').open('w') as log:
                app = subprocess.Popen([java,'-Xms512m','-Xmx512m','-Duser.timezone=UTC','-jar',str(jar),
                    '--spring.config.additional-location=file:'+str(config),'--server.address=127.0.0.1','--server.port=0'],
                    cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
            for _ in range(240):
                if app.poll() is not None:
                    raise RuntimeError('Disposable application stopped; inspect application.log')
                matches = re.findall(r'Tomcat started on port (\d+)',(output/'application.log').read_text(errors='replace'))
                if matches:
                    origin='http://127.0.0.1:'+matches[-1]
                    try:
                        request(origin,'/actuator/health')
                        break
                    except (OSError, ValueError):
                        pass
                time.sleep(0.5)
            else:
                raise RuntimeError('Disposable application startup timeout')
            request(origin,'/api/v1/members',{'email':'reader@campaign.invalid','password':password,'nickname':'read-lab'})
            _, headers=request(origin,'/api/v1/auth/login',{'email':'reader@campaign.invalid','password':password})
            cookie=SimpleCookie(); cookie.load(headers['Set-Cookie']); session=cookie['SESSION_ID'].value
            metadata['database'] = json.loads(sql("SELECT JSON_OBJECT('version',VERSION(),'bufferPoolBytes',@@innodb_buffer_pool_size,"
                "'isolation',@@transaction_isolation,'flyway',(SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history));"))
            digits='SELECT 0 n UNION ALL '+ ' UNION ALL '.join(f'SELECT {n}' for n in range(1,10))
            sql('CREATE TABLE readlab_numbers (n INT PRIMARY KEY); INSERT INTO readlab_numbers '
                f'SELECT a.n+10*b.n+100*c.n+1000*d.n+1 FROM ({digits}) a CROSS JOIN ({digits}) b CROSS JOIN ({digits}) c CROSS JOIN ({digits}) d;'
                "INSERT INTO member(id,email,nickname,role,status,created_at,updated_at) "
                "SELECT 1000+n,CONCAT('fixture-',n,'@campaign.invalid'),'fixture','MEMBER','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM readlab_numbers;")
            for cid,strategy in enumerate(STRATEGIES,101):
                table='coupon_issue_unit_composite' if 'COMPOSITE' in strategy else 'coupon_issue_unit'
                sql("INSERT INTO coupon(id,name,description,discount_type,discount_value,min_payment_price,issue_start_at,issue_end_at,"
                    "usable_from,usable_until,is_active,total_quantity,issued_quantity,stock_prepared_at,issue_strategy,created_at,updated_at) "
                    f"VALUES({cid},'campaign-{cid}','read experiment','FIXED_AMOUNT',10000,50000,UTC_TIMESTAMP(6)-INTERVAL 1 DAY,"
                    "UTC_TIMESTAMP(6)+INTERVAL 2 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 30 DAY,true,10000,0,"
                    f"UTC_TIMESTAMP(6)-INTERVAL 2 DAY,'{strategy}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)); "
                    f"INSERT INTO {table}(coupon_id,unit_no) SELECT {cid},n FROM readlab_numbers;")
            def get(variant):
                path='/api/v1/coupons' if variant=='v1' else '/api/v2/coupons/campaigns/'+variant
                req=urllib.request.Request(origin+path,headers={'Cookie':'SESSION_ID='+session,'X-Benchmark-Token':token})
                with urllib.request.urlopen(req,timeout=15) as response:
                    return response.read()
            sql("SET GLOBAL log_output='TABLE'; SET GLOBAL general_log=ON;")
            try:
                get('count'); get('exists')
            finally:
                sql('SET GLOBAL general_log=OFF;')
            captured=[json.loads(line)['sql'] for line in sql("SELECT JSON_OBJECT('sql',CONVERT(argument USING utf8mb4)) FROM mysql.general_log "
                "WHERE command_type IN ('Query','Execute') AND argument LIKE '%coupon%' "
                "AND argument NOT LIKE '%mysql.general_log%'").splitlines()]
            captured=[re.sub(r'/\*.*?\*/','',q,flags=re.S).strip() for q in captured]
            captured=[q for q in captured if q.lower().startswith('select') and
                      re.search(r'\bfrom\s+`?coupon`?\s',q,re.I)]
            save(output/'jdbc-capture.json',captured)
            statements={}
            for query in captured:
                statements['exists' if 'case' in query.lower() else 'count']=query
            if set(statements)!={'count','exists'}:
                raise RuntimeError('Actual JDBC SQL capture incomplete')
            save(output/'jdbc-sql.json',statements)
            def digests():
                previous_signature=None
                for attempt in range(5):
                    rows=sql("SELECT JSON_OBJECT('digest',DIGEST,'sql',DIGEST_TEXT,'count',COUNT_STAR,'timer',SUM_TIMER_WAIT,"
                        "'rowsExamined',SUM_ROWS_EXAMINED,'rowsSent',SUM_ROWS_SENT) FROM performance_schema.events_statements_summary_by_digest "
                        "WHERE SCHEMA_NAME='coupon_read' AND COUNT_STAR>0 AND DIGEST IS NOT NULL;")
                    snapshot={r['digest']:r for r in map(json.loads,rows.splitlines())}
                    signature={key:row['count'] for key,row in snapshot.items() if 'from `coupon`' in row['sql'].lower()}
                    if signature==previous_signature:
                        return snapshot
                    previous_signature=signature
                    time.sleep(0.1)
                raise RuntimeError('Campaign statement counters did not become quiescent')
            previous=0
            for label, issued in [('unissued',0),('half',5000),('last-unit',9999),('sold-out',10000)]:
                for cid,strategy in enumerate(STRATEGIES,101):
                    table='coupon_issue_unit_composite' if 'COMPOSITE' in strategy else 'coupon_issue_unit'
                    sql('START TRANSACTION; '+f'DELETE FROM {table} WHERE coupon_id={cid} AND unit_no<={issued}; '
                        'INSERT INTO member_coupon(coupon_id,member_id,used,created_at,updated_at) '
                        f'SELECT {cid},1000+n,false,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6) FROM readlab_numbers WHERE n>{previous} AND n<={issued}; COMMIT;')
                previous=issued
                if label not in args.scenarios:
                    continue
                bodies={variant:get(variant) for variant in ['v1','count','exists']}
                if len(set(bodies.values()))!=1:
                    raise RuntimeError('HTTP response parity failed: '+label)
                expected='SOLD_OUT' if issued==10000 else 'OPEN'
                infos=json.loads(bodies['v1'])['data']['infos']
                if len(infos)!=4 or any(c['issuance_status']!=expected for c in infos):
                    raise RuntimeError('Campaign state mismatch: '+label)
                response_hash=hashlib.sha256(bodies['v1']).hexdigest()
                directory=output/label; directory.mkdir()
                fixture=sql("SELECT JSON_OBJECT('id',c.id,'strategy',c.issue_strategy,'total',c.total_quantity,"
                    "'issued',(SELECT COUNT(*) FROM member_coupon m WHERE m.coupon_id=c.id),"
                    "'surrogateRemaining',(SELECT COUNT(*) FROM coupon_issue_unit u WHERE u.coupon_id=c.id),"
                    "'compositeRemaining',(SELECT COUNT(*) FROM coupon_issue_unit_composite u WHERE u.coupon_id=c.id)) FROM coupon c ORDER BY c.id;")
                fixture=[json.loads(line) for line in fixture.splitlines()]
                if any(c['total']!=c['issued']+c['surrogateRemaining']+c['compositeRemaining'] for c in fixture):
                    raise RuntimeError('Inventory invariant failed')
                save(directory/'fixture.json',{'campaigns':fixture,'responseHash':response_hash,'v1Parity':True})
                plans={variant:{'json':json.loads(sql('EXPLAIN FORMAT=JSON '+query)),
                                'tree':sql('EXPLAIN ANALYZE FORMAT=TREE '+query)} for variant,query in statements.items()}
                save(directory/'plans.json',plans)
                for round_number in range(1,args.rounds+1):
                    for variant in (['count','exists'] if round_number%2 else ['exists','count']):
                        base={'origin':origin,'variant':variant,'session':session,'token':token,
                              'rate':args.rate,'responseHash':response_hash}
                        run_dir=directory/f'{round_number}-{variant}'; run_dir.mkdir()
                        for phase,seconds in [('warmup',5),('measure',args.seconds)]:
                            target=run_dir/(phase+'.json')
                            settings=base|{'seconds':seconds,'summaryFile':str(target)}
                            settings_file=private/'k6.json'; settings_file.write_text(json.dumps(settings)); settings_file.chmod(0o600)
                            if phase=='measure':
                                before=digests(); began=time.time()
                                save(run_dir/'statements-before.json',before)
                            with (run_dir/(phase+'.log')).open('w') as log:
                                process=subprocess.run(['k6','run','--quiet',str(script)],
                                    env=environment()|{'CAMPAIGN_READ_CONFIG':str(settings_file)},stdout=log,stderr=subprocess.STDOUT,
                                    timeout=seconds+45)
                            summary=json.loads(target.read_text())
                            if process.returncode:
                                raise RuntimeError('k6 correctness/load threshold failed: '+str(run_dir)+'/'+phase)
                            if phase=='measure':
                                ended=time.time(); after=digests(); changes=[]
                                save(run_dir/'statements-after.json',after)
                                for key,row in after.items():
                                    prior=before.get(key,{}); count=row['count']-prior.get('count',0)
                                    if count and 'from `coupon`' in row['sql'].lower():
                                        changes.append({'sql':row['sql'],'count':count,
                                            'meanSqlMs':(row['timer']-prior.get('timer',0))/1e9/count,
                                            'meanRowsExamined':(row['rowsExamined']-prior.get('rowsExamined',0))/count})
                                metrics=summary['metrics']; requests=metrics['http_reqs']['values']['count']
                                row={'scenario':label,'round':round_number,'variant':variant,'requests':requests,
                                     'httpDurationMs':metrics['http_req_duration']['values'],'statementChanges':changes,
                                     'startEpoch':began,'endEpoch':ended,
                                     'responseMismatches':metrics['response_mismatches']['values']['count'],
                                     'dropped':metrics.get('dropped_iterations',{}).get('values',{}).get('count',0)}
                                save(run_dir/'observation.json',row)
                                if len(changes)!=1 or changes[0]['count']!=requests:
                                    raise RuntimeError('Server/client query counts did not match: '+json.dumps({
                                        'requests':requests,'digestCounts':[c['count'] for c in changes]}))
                                results.append(row); save(output/'results.json',results)
                                print(json.dumps({'scenario':label,'round':round_number,'variant':variant,
                                    'requests':requests,'p95':row['httpDurationMs']['p(95)'],
                                    'sqlMs':changes[0]['meanSqlMs'],'rowsExamined':changes[0]['meanRowsExamined']}),flush=True)
                if get('count')!=get('exists'):
                    raise RuntimeError('Post-run parity failed')
            metadata['status']='complete'; save(output/'metadata.json',metadata)
    except Exception as error:
        metadata.update(status='failed',error=str(error))
        save(output/'metadata.json',metadata)
        raise
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try: app.wait(timeout=20)
            except subprocess.TimeoutExpired: app.kill(); app.wait(timeout=5)
        removed=[]
        for container in containers:
            command(['docker','rm','--force','--volumes',container]); removed.append(container)
        save(output/'cleanup.json',{'appStopped':app is None or app.poll() is not None,'removedContainers':removed})


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--rate',type=int,default=50)
    parser.add_argument('--seconds',type=int,default=15)
    parser.add_argument('--java-home',type=Path)
    parser.add_argument('--scenarios',nargs='+',choices=['unissued','half','last-unit','sold-out'],
                        default=['unissued','half','last-unit','sold-out'])
    run(parser.parse_args())
