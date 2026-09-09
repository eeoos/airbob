#!/usr/bin/env python3
"""Validate the small v3 release consumed by the explicit growth restore path."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import re

FILES={'dump':'airbob-growth.sql.gz','migrations':'migration-files.json','fingerprint':'before-fingerprint.json',
       'reads':'read-scenarios.json','runtime':'runtime-plan.json','runtimeEvidence':'runtime-scenarios.json','scenarioQualification':'scenario-qualification.json'}


def require(condition,message):
    if not condition:raise ValueError(message)


def exact(value,keys):
    require(isinstance(value,dict) and set(value)==set(keys),'Unexpected object keys')


def read(path):
    require(path.is_file() and not path.is_symlink(),'Missing or unsafe artifact: '+path.name)
    def unique(pairs):
        value={}
        for key,item in pairs:
            require(key not in value,'Duplicate JSON key');value[key]=item
        return value
    return json.loads(path.read_text(),object_pairs_hook=unique)


def sha(path):
    require(path.is_file() and not path.is_symlink(),'Missing or unsafe artifact: '+path.name)
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for chunk in iter(lambda:source.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()


def validate(release,expected_id,expected_mysql='8.4.11',migration_dir=None):
    release=Path(release)
    require(release.is_dir() and not release.is_symlink(),'Unsafe release directory')
    manifest=read(release/'consumer-manifest.json')
    exact(manifest,['schemaVersion','datasetVersion','datasetId','qualification','mysql','time','artifacts','capabilities'])
    require(type(manifest['schemaVersion']) is int and manifest['schemaVersion']==3,'Unsupported schema version')
    require(manifest['datasetVersion']=='benchmark-dataset-v3','Unsupported dataset version')
    require(re.fullmatch(r'korea-growth-v3-[0-9a-f]{16}',str(expected_id)) and manifest['datasetId']==expected_id,'Dataset identity mismatch')
    require(manifest['qualification']=='SMALL_LOCAL_SCENARIOS','Unsupported qualification scope')
    exact(manifest['mysql'],['version','flywayVersion'])
    require(re.fullmatch(r'8\.4\.\d+',expected_mysql) and manifest['mysql']['version']==expected_mysql,'MySQL engine mismatch')
    require(type(manifest['mysql']['flywayVersion']) is int and manifest['mysql']['flywayVersion']==27,'Unsupported migration version')
    exact(manifest['time'],['snapshotAsOf','activityCutoffExclusive','timezone'])
    require(manifest['time']['timezone']=='Asia/Seoul','Unsupported business timezone')
    cutoff=dt.datetime.fromisoformat(manifest['time']['activityCutoffExclusive'])
    snapshot=dt.datetime.fromisoformat(manifest['time']['snapshotAsOf'])
    require(cutoff.utcoffset()==dt.timedelta(hours=9) and snapshot.utcoffset()==dt.timedelta(hours=9) and cutoff<snapshot,'Invalid historical time contract')
    require(manifest['capabilities']=={'immutableReads':True,'runtimePreparation':True,'searchSnapshot':False,'awsExecutionValidated':False},'Invalid capability claims')
    exact(manifest['artifacts'],FILES)
    for key,name in FILES.items():
        artifact=manifest['artifacts'][key];exact(artifact,['file','sha256'])
        require(artifact['file']==name and re.fullmatch(r'[0-9a-f]{64}',str(artifact['sha256'])),'Unsafe artifact reference')
        require(sha(release/name)==artifact['sha256'],'Artifact checksum mismatch: '+name)
    require(expected_id=='korea-growth-v3-'+manifest['artifacts']['dump']['sha256'][:16],'Dataset ID is not bound to its dump')
    migrations=read(release/FILES['migrations'])
    require(isinstance(migrations,dict) and len(migrations)==27,'Incomplete migrations')
    versions=[]
    for name,digest in migrations.items():
        match=re.fullmatch(r'V(\d+)__[A-Za-z0-9_]+\.sql',name)
        require(match and re.fullmatch(r'[0-9a-f]{64}',str(digest)),'Invalid migration inventory')
        versions.append(int(match.group(1)))
    require(sorted(versions)==list(range(1,28)),'Migration sequence mismatch')
    if migration_dir is not None:
        actual={p.name:sha(p) for p in Path(migration_dir).glob('V*__*.sql')}
        require(actual==migrations,'Application migration files do not match the dataset')
    fingerprint=read(release/FILES['fingerprint'])
    require(fingerprint['mysqlVersion']==expected_mysql,'Fingerprint engine mismatch')
    require(len(fingerprint['tables'])==32 and fingerprint['tables']['flyway_schema_history']['rows']==27,'Fingerprint schema mismatch')
    for table,info in fingerprint['tables'].items():
        require(re.fullmatch(r'[a-z][a-z0-9_]*',table) and type(info['rows']) is int and info['rows']>=0,'Invalid table inventory')
        require(all(re.fullmatch(r'[0-9a-f]{64}',str(info[k])) for k in ['rowsSha256','domainRowsSha256','ddlSha256']),'Invalid table fingerprint')
    require(fingerprint['tables']['accommodation_inventory_day']['rows']==0 and fingerprint['tables']['outbox']['rows']==0,'Base must be closed-history read-only data')
    require(read(release/FILES['scenarioQualification'])['state']=='SMALL_SCENARIOS_QUALIFIED','Scenarios not qualified')
    require(read(release/FILES['runtimeEvidence'])['passed'] is True,'Runtime fixtures not qualified')
    runtime=read(release/FILES['runtime'])
    exact(runtime,['schemaVersion','timePolicy','databasePolicy','baseIdentities','scenarios'])
    require(runtime['schemaVersion']==1 and runtime['baseIdentities']['version']=='query-boundaries-v1','Unsupported runtime plan')
    require(set(runtime['scenarios'])=={'recentlyViewed','coupon','reviewAtomicity','wishlistDelete','expiration'},'Runtime scenario set mismatch')
    targets=read(release/FILES['reads']);exact(targets,['schemaVersion','targets'])
    require(targets['schemaVersion']==1 and isinstance(targets['targets'],list) and len(targets['targets'])>0,'No read targets')
    seen=set()
    for target in targets['targets']:
        exact(target,['id','method','path','memberId','account','expectedStatus','expectedResponseSha256','arrayField','idField','expectedIds','immutable','preparation'])
        require(re.fullmatch(r'[a-z0-9_-]+',target['id']) and target['id'] not in seen,'Invalid/duplicate target ID');seen.add(target['id'])
        require(target['method']=='GET' and target['expectedStatus']==200 and target['immutable'] is True and target['preparation']=='none','Unsupported read target')
        require(re.fullmatch(r'/api/v1/[A-Za-z0-9/_?=&%+.-]+',target['path']) and '..' not in target['path'] and '%2e' not in target['path'].lower(),'Unsafe target path')
        require(re.fullmatch(r'[0-9a-f]{64}',str(target['expectedResponseSha256'])),'Missing response fingerprint')
        account=target['account']
        if target['memberId'] is None:require(account is None,'Anonymous target has an account')
        else:
            exact(account,['memberId','email','role'])
            require(type(target['memberId']) is int and target['memberId']>0 and account['memberId']==target['memberId'],'Account identity mismatch')
            require(account['role'] in ['MEMBER','ADMIN'] and re.fullmatch(r'growth-(?:\d+|boundary-(?:host|admin|guest-\d+))@example\.test',account['email']),'Non-synthetic account')
        require(isinstance(target['expectedIds'],list) and len(target['expectedIds'])<=50,'Invalid expected result size')
        if target['arrayField'] is None:require(target['idField'] is None and target['expectedIds']==[],'Invalid singleton target')
        else:require(target['arrayField'] in ['reservations','reviews','accommodations','wishlists','wishlist_accommodations'] and target['idField'] in ['id','reservation_id','reservation_uid','wishlist_accommodation_id'],'Invalid result mapping')
    return manifest,fingerprint,targets


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('release',type=Path);parser.add_argument('expected_id')
    parser.add_argument('--mysql-version',default='8.4.11');parser.add_argument('--migration-dir',type=Path)
    args=parser.parse_args()
    manifest,_,targets=validate(args.release,args.expected_id,args.mysql_version,args.migration_dir)
    print(json.dumps({'valid':True,'datasetId':manifest['datasetId'],'targets':len(targets['targets']),'scope':manifest['qualification']}))
