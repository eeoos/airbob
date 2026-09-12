"""Local-only runtime used by the growth scenario qualification runner."""
import hashlib
import http.cookiejar
import json
from pathlib import Path
import re
import subprocess
import time
import urllib.error
import urllib.request
from growth_settings import BASE_SETTINGS, utc_jdbc_url


def canonical(value):
    def normalize(item):
        if isinstance(item,float) and item.is_integer():return int(item)
        if isinstance(item,list):return [normalize(v) for v in item]
        if isinstance(item,dict):return {k:normalize(v) for k,v in item.items()}
        return item
    return json.dumps(normalize(value),ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()


class Expression(str):
    """A trusted SQL expression authored by the local fixture implementation."""


class Database:
    SCHEMA = 'airbob_growth_bulk_write_benchmark'
    def __init__(self, container):
        if not re.fullmatch(r'airbob-growth-restore-[a-f0-9]{10}',container):
            raise ValueError('Expected the roundtrip runner\'s disposable restore container')
        self.container=container
        self._columns={}
    def command(self, database=True):
        return ['docker','exec','-i',self.container,'mysql','--defaults-extra-file=/run/etl-secrets/client.cnf',
                '--default-character-set=utf8mb4','--batch','--raw']+([self.SCHEMA] if database else [])
    def execute(self,sql):
        return subprocess.check_output(self.command()+['-e',sql],text=True)
    def rows(self,sql):
        lines=self.execute(sql).strip().splitlines()
        if not lines:return []
        names=lines[0].split('\t')
        def value(x):
            return None if x=='NULL' else int(x) if x.isdigit() else x
        return [dict(zip(names,map(value,line.split('\t')))) for line in lines[1:]]
    def scalar(self,sql):
        return next(iter(self.rows(sql)[0].values()))
    @staticmethod
    def literal(value):
        if isinstance(value,Expression):return str(value)
        if value is None:return 'NULL'
        if isinstance(value,bool):return '1' if value else '0'
        if isinstance(value,int):return str(value)
        return "CONVERT(0x"+str(value).encode().hex()+" USING utf8mb4)"
    def copy(self,table,source_id,overrides):
        if table not in {'reservation','payment','payment_transaction','member_coupon','coupon'}:
            raise ValueError('Unsupported runtime fixture table')
        if table not in self._columns:
            self._columns[table]=[r['COLUMN_NAME'] for r in self.rows("SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME="+self.literal(table)+" AND COLUMN_NAME<>'id' ORDER BY ORDINAL_POSITION")]
        columns=self._columns[table]
        if not set(overrides).issubset(columns):raise ValueError('Unknown fixture columns')
        names=','.join('`'+c+'`' for c in columns)
        values=','.join(self.literal(overrides[c]) if c in overrides else '`'+c+'`' for c in columns)
        return int(self.scalar(f'INSERT INTO `{table}` ({names}) SELECT {values} FROM `{table}` WHERE id={int(source_id)}; SELECT LAST_INSERT_ID() id'))


class App:
    def __init__(self,jar,output,secret_path,environment,redis_port,*,label,profiles='test',settings=None):
        self.jar=Path(jar).resolve();self.output=output;self.secret_path=secret_path;self.env=environment.copy()
        self.label=label;self.profiles=profiles;self.settings=dict(BASE_SETTINGS)|dict(settings or {})
        self.settings.update({'logging.level.org.hibernate.SQL':'DEBUG','spring.jpa.properties.hibernate.format_sql':'false'})
        self.env.update(SPRING_DATASOURCE_URL=utc_jdbc_url(self.env['AIRBOB_ETL_DB_URL'].replace('/airbobdb','/'+Database.SCHEMA)),
                        SPRING_DATASOURCE_USERNAME='root',SPRING_DATASOURCE_PASSWORD=self.env['AIRBOB_ETL_DB_PASSWORD'],
                        SPRING_DATA_REDIS_HOST='127.0.0.1',SPRING_DATA_REDIS_PORT=str(redis_port),
                        ACCOMMODATION_DETAIL_CACHE_REDIS_HOST=str(self.settings.get('accommodation.detail-cache.redis.host','127.0.0.1')),
                        ACCOMMODATION_DETAIL_CACHE_REDIS_PORT=str(self.settings.get('accommodation.detail-cache.redis.port',redis_port)),
                        ACCOMMODATION_DETAIL_CACHE_ENABLED=str(self.settings['accommodation.detail-cache.enabled']).lower(),
                        AWS_ACCESS_KEY_ID='dummy',AWS_SECRET_ACCESS_KEY='dummy',AWS_EC2_METADATA_DISABLED='true')
        self.process=None;self.log=None;self.base=None
    def __enter__(self):
        try:
            started=time.monotonic()
            config=self.secret_path/(self.label+'.properties')
            config.write_text('\n'.join(k+'='+str(v).lower() if isinstance(v,bool) else k+'='+str(v) for k,v in self.settings.items())+'\n')
            config.chmod(0o600)
            self.log_path=self.output/(self.label+'-app.log');self.log=self.log_path.open('w')
            self.process=subprocess.Popen(['java','-Duser.timezone=UTC','-Xmx512m','-jar',str(self.jar),
                '--spring.profiles.active='+self.profiles,'--server.address=127.0.0.1','--server.port=0',
                '--spring.config.additional-location=file:'+str(config)],cwd=self.secret_path,env=self.env,
                stdout=self.log,stderr=subprocess.STDOUT)
            for _ in range(int(self.env.get('AIRBOB_GROWTH_STARTUP_TIMEOUT_SECONDS','120'))):
                if self.process.poll() is not None:raise RuntimeError('App startup failed: '+self.label)
                text=self.log_path.read_text();match=re.search(r'Tomcat started on port (\d+)',text)
                inventory_ready=not self.settings.get('reservation.inventory.startup.enabled') in (True,'true') or '예약 inventory startup bootstrap 완료' in text
                if match and 'Started AirbobApplication' in text and inventory_ready:
                    self.base='http://127.0.0.1:'+match.group(1);self.startup_seconds=round(time.monotonic()-started,4);return self
                time.sleep(1)
            raise RuntimeError('App startup timeout: '+self.label)
        except BaseException:
            self.__exit__(None,None,None);raise
    def __exit__(self,*_):
        if self.process is not None:
            self.process.terminate()
            try:self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:self.process.kill();self.process.wait()
        if self.log is not None:self.log.close()
    def client(self):
        return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    def request(self,client,path,*,method='GET',body=None,headers=None,expected=(200,),capture=True):
        offset=self.log_path.stat().st_size
        req=urllib.request.Request(self.base+path,data=None if body is None else json.dumps(body).encode(),method=method,
            headers={'Content-Type':'application/json'}|dict(headers or {}))
        try:
            response=client.open(req,timeout=40)
        except urllib.error.HTTPError as error:response=error
        with response:
            status=response.code;raw=response.read()
        try:data=json.loads(raw) if raw else None
        except json.JSONDecodeError:data={'nonJsonResponse':True}
        with (self.output/'http-observations.jsonl').open('a') as observations:
            observations.write(json.dumps({'app':self.label,'method':method,'path':path,'status':status,'responseBytes':len(raw)},ensure_ascii=False)+'\n')
        if status not in expected:
            detail = f'; response={data}' if capture else ''
            raise AssertionError(f'{method} {path}: expected {expected}, got {status}'+detail)
        sql=[]
        if capture:
            with self.log_path.open('rb') as log:log.seek(offset);text=log.read().decode()
            sql=re.findall(r'org\.hibernate\.SQL\s*:\s*(.*)',text)
        return {'path':path,'status':status,'response':data,'sql':sql,
                'responseSha256':hashlib.sha256(canonical(data)).hexdigest()}
    def login(self,client,email):
        from growth_accounts import credential_for_email
        source = self.env.get('AIRBOB_GROWTH_CREDENTIALS_FILE')
        if not source:raise ValueError('Normal login requires an explicit prepared private credential file')
        credential = credential_for_email(source,email)
        self.request(client,'/api/v1/auth/login',method='POST',
            body={'email':email,'password':credential['password']},capture=False)
