"""Mac snapshot source/target contracts; no cloud or database IO on import.

Historical source expiry is provenance, not a future target's operating window.
Only explicit bootstrap() has service effects; it never imports SQL or computes
whole-row fingerprints. The operator owns Terraform, retirement and live reads.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time

import growth_b_service as service
import growth_b_mac_service as mac
import growth_b_mac_downsize as downsize
import growth_b_aws_restore as restore
import growth_b_search as search

MODE = 'mac-snapshot-counts-ddl'
SOURCE_KIND = 'global-b-mac-snapshot-source'
RESTORE_KIND = 'global-b-mac-snapshot-restore'
COUNTS_KIND = 'global-b-mac-snapshot-counts-ddl'
TARGET_KIND = 'global-b-mac-snapshot-target'
DATASET = 'global-growth-b-b0fbda4d12511eeb'
DUMP_SHA = 'b0fbda4d12511eeb8182ab4563ae9e64c8158e78fd50f22c8462f2eb95a2dc00'
FINGERPRINT_SHA = '26f07337f974285e0a8f24f433ebfcc378c15c311ab7daef3ea7227f2f9f3ee1'
MANIFEST_SHA = 'f43044ee0b13a9700c5ae704fd6570285a8ab34fa9c15b03a764e46941e43b79'
COUNT_SOURCE_SHA = '2ef0b90448aec369811cf14b7dcd072aa3464b600b6a95248d4e0b2b21cc01eb'
CHILDREN = ('serviceManifest', 'sqlImport', 'postcheck', 'downsize', 'preparedRequest',
            'snapshotIntent', 'snapshotAvailable', 'countsDdl', 'countsWitness', 'consumerManifest', 'sealedFingerprint')
PREPARATION_FIELDS = {'sourceMode', 'receipt', 'sourceProvenance', 'restoreReceipt', 'countsDdlReceipt', 'rdsCaBundle'}
MAX_BYTES = 2 * 1024 * 1024
HASH = r'[0-9a-f]{64}'
UUID = r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}'
ENDPOINT = r'[a-z0-9-]+\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com'
SECRET_ARN = r'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:[A-Za-z0-9/_+=.@!-]+'


def need(ok, code):
    if not ok: raise ValueError(code)


def encoded(value): return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)+'\n').encode()
def sha(raw): return hashlib.sha256(raw).hexdigest()
def digest(value): return sha(encoded(value))
def source_sha(): return sha(Path(__file__).read_bytes())
def fields(value, keys): need(type(value) is dict and set(value) == set(keys), 'MAC_SNAPSHOT_FIELDS_DIFFER')


def parse(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            need(key not in result, 'DUPLICATE_JSON_KEY'); result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('INVALID_JSON_NUMBER')))


def epoch(value):
    if isinstance(value, dt.datetime): parsed = value
    else:
        need(type(value) is str, 'ACTUAL_UTC_TIME_REQUIRED')
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    need(parsed.tzinfo is not None and parsed.utcoffset() == dt.timedelta(0), 'ACTUAL_UTC_TIME_REQUIRED')
    return parsed.timestamp()


def public_ref(value, prefix=None):
    fields(value, ('key', 'versionId', 'sha256', 'bytes'))
    service.ref(value, prefix or '')
    need(value['bytes'] <= MAX_BYTES, 'PUBLIC_REFERENCE_TOO_LARGE')
    return value


def child(value):
    """Check embedded exact bytes; never reopen an origin path on another host."""
    fields(value, ('reference', 'rawUtf8'))
    ref = value['reference']; need(type(ref) is dict, 'SOURCE_REFERENCE_REQUIRED')
    if set(ref) == {'path', 'sha256', 'bytes'}:
        need(type(ref['path']) is str and ref['path'].startswith('/') and '\x00' not in ref['path']
             and not any(x in ('.', '..') for x in ref['path'].split('/')),
             'SOURCE_LOCAL_REFERENCE_PATH_INVALID')
    else:
        fields(ref, ('bucket', 'key', 'versionId', 'sha256', 'bytes'))
        need(ref['bucket'] in (service.BUCKET, service.EVIDENCE), 'SOURCE_BUCKET_CHANGED')
        public_ref({k: v for k, v in ref.items() if k != 'bucket'})
    need(type(value['rawUtf8']) is str and type(ref['bytes']) is int and 0 < ref['bytes'] <= MAX_BYTES
         and type(ref['sha256']) is str and re.fullmatch(HASH, ref['sha256']), 'SOURCE_RAW_BYTES_REQUIRED')
    raw = value['rawUtf8'].encode('utf-8')
    need(len(raw) == ref['bytes'] and sha(raw) == ref['sha256'], 'SOURCE_CHILD_BYTES_CHANGED')
    return parse(raw)


def children(values):
    fields(values, CHILDREN)
    need(sum(len(x['rawUtf8'].encode()) for x in values.values()) <= MAX_BYTES, 'SOURCE_PACKAGE_TOO_LARGE')
    return {key: child(value) for key, value in values.items()}


def _source_documents(values):
    docs = children(values)
    manifest, transition = docs['serviceManifest'], docs['downsize']
    need(manifest.get('datasetId') == DATASET and manifest.get('preparation', {}).get('sourceMode') == mac.MODE,
         'ORIGINAL_MAC_SOURCE_REQUIRED')
    service.validate_manifest(manifest, DATASET, manifest['runId'], manifest['serviceRelease'])
    mac.validate_source(manifest, docs['sqlImport'], docs['postcheck'], transition)
    for key, field in [('sqlImport', 'sqlImportReceipt'), ('postcheck', 'postcheckReceipt'), ('downsize', 'receipt')]:
        ref = values[key]['reference']; wanted = manifest['preparation'][field]
        need(all(ref[k] == wanted[k] for k in ('sha256', 'bytes')), 'ORIGINAL_SOURCE_REFERENCE_CHANGED')
        if 'bucket' in ref:
            need(ref['bucket'] == service.EVIDENCE and {k:v for k,v in ref.items() if k!='bucket'} == wanted,
                 'ORIGINAL_SOURCE_VERSION_CHANGED')
    need(docs['sqlImport']['binding']['dumpSha256'] == DUMP_SHA, 'SEALED_B_DUMP_CHANGED')
    need(values['consumerManifest']['reference']['sha256'] == MANIFEST_SHA
         and values['sealedFingerprint']['reference']['sha256'] == FINGERPRINT_SHA, 'SEALED_EXPECTATIONS_CHANGED')
    consumer, fp = docs['consumerManifest'], docs['sealedFingerprint']
    need(consumer['datasetId'] == DATASET and consumer['artifacts']['fingerprint'] == {'file':'before-fingerprint.json','sha256':FINGERPRINT_SHA}
         and fp['algorithm'] == 'sha256-pk-order-length-prefixed-jdbc-bytes-v1' and fp['mysqlVersion'] == '8.4.11',
         'SEALED_COUNT_DDL_CONTRACT_CHANGED')
    expected = {name: {k: row[k] for k in ('rows','ddlSha256')} for name,row in sorted(fp['tables'].items())}
    need(len(expected)==32 and sum(x['rows'] for x in expected.values())==160882380, 'EXACT_32_TABLES_REQUIRED')
    counts, witness = docs['countsDdl'], docs['countsWitness']
    need(counts.get('kind') == 'mac-rds-counts-and-ddl-validation' and counts.get('state') == 'ALL_32_BASE_TABLE_COUNTS_AND_DDL_MATCHED'
         and counts.get('sourceSha256') == COUNT_SOURCE_SHA and counts.get('datasetId') == DATASET
         and counts.get('tables') == expected and counts.get('mismatches') == [] and counts.get('fullDatasetValidated') is False
         and counts.get('rowContentHashesVerified') is False and counts.get('domainContentHashesVerified') is False
         and counts.get('sqlImportExecuted') is False and counts.get('identityBefore') == counts.get('identityAfter'),
         'ORIGINAL_COUNTS_DDL_PROOF_CHANGED')
    identity = counts['identityBefore']
    need(identity.get('serverUuid') == manifest['rds']['serverUuid'] and identity.get('mysqlVersion') == '8.4.11'
         and identity.get('schemaName') == 'airbobdb' and identity.get('tlsCipher')
         and counts['expectedTarget']['rdsResourceId'] == manifest['rds']['resourceId']
         and counts['expectedTarget']['rdsIdentifier'] == manifest['rds']['identifier'], 'ORIGINAL_COUNTS_TARGET_CHANGED')
    need(witness.get('resultSha256') == values['countsDdl']['reference']['sha256']
         and witness.get('rdsApiIdentityVerified') is True and witness.get('tlsCertificateAndHostnameVerified') is True
         and witness.get('readOnlyTransaction') is True and witness.get('credentialsPersisted') is False,
         'ORIGINAL_COUNTS_WITNESS_CHANGED')
    intent, available = docs['snapshotIntent'], docs['snapshotAvailable']
    fields(docs['preparedRequest'], ('request','tfvars'))
    downsize.validate_request(docs['preparedRequest']['request'])
    need(docs['preparedRequest']['tfvars']==docs['preparedRequest']['request']['tfvars'], 'ORIGINAL_PREPARED_TFVARS_CHANGED')
    request_sha = digest(docs['preparedRequest']['request'])
    need(request_sha == transition['requestSha256'] == intent['requestSha256'] == available['requestSha256'],
         'SNAPSHOT_ORIGINAL_REQUEST_CHANGED')
    need(available.get('state') == 'MANUAL_SNAPSHOT_AVAILABLE_SQL_AND_LIGHT_POSTCHECK_ONLY'
         and available.get('fullDatasetValidated') is False and intent.get('fullDatasetValidated') is False
         and available['sqlImportReceiptSha256'] == values['sqlImport']['reference']['sha256']
         and available['postcheckSha256'] == values['postcheck']['reference']['sha256']
         and available['sourceResourceId'] == manifest['rds']['resourceId']
         and intent['request']['DBInstanceIdentifier'] == manifest['rds']['identifier']
         and intent['request']['DBSnapshotIdentifier'] == available['snapshotIdentifier'], 'ORIGINAL_SNAPSHOT_PROOF_CHANGED')
    expected_tags = {'DatasetRelease':DATASET,'Environment':'performance-lab','Persistence':'persistent','Project':'airbob',
                     'SourceResourceId':manifest['rds']['resourceId'],'SourceRunId':manifest['runId']}
    need(available['tags'] == expected_tags and intent['request']['Tags'] == [{'Key':k,'Value':v} for k,v in sorted(expected_tags.items())],
         'ACTUAL_SIX_PERSISTENT_TAGS_REQUIRED')
    need(epoch(docs['sqlImport']['completedAt']) <= epoch(docs['postcheck']['checkedAt']) <= intent['issuedAtEpoch']
         <= epoch(available['snapshotCreatedAt']) <= available['observedAtEpoch'] <= transition['completedAtEpoch']
         <= epoch(counts['startedAt']) <= epoch(counts['completedAt']), 'ORIGINAL_SOURCE_CHRONOLOGY_CHANGED')
    op = transition['operator']
    source = {'runId':manifest['runId'],'resourceFence':op['fencingToken'],'expiresAt':int(op['expiresAt']),
              'rds':transition['rds'],'application':manifest['application'],'mysql':manifest['mysql']}
    return docs, source, expected


def snapshot_projection(observation):
    fields(observation, ('snapshot','tags','attributes'))
    row = observation['snapshot']; tags = observation['tags']
    need(type(tags) is list and len(tags)==6 and len({x['Key'] for x in tags})==6, 'SNAPSHOT_TAGS_CHANGED')
    need(observation['attributes']==[{'AttributeName':'restore','AttributeValues':[]}], 'PRIVATE_MANUAL_SNAPSHOT_REQUIRED')
    need(row.get('Status')=='available' and row.get('SnapshotType')=='manual' and row.get('Engine')=='mysql'
         and row.get('EngineVersion')=='8.4.11' and row.get('AllocatedStorage')==100
         and row.get('StorageType')=='gp3' and row.get('Encrypted') is True
         and isinstance(row.get('KmsKeyId'),str) and row['KmsKeyId'].startswith('arn:aws:kms:'+service.REGION+':'+service.ACCOUNT+':key/'),
         'AVAILABLE_100_GIB_ENCRYPTED_MYSQL_SNAPSHOT_REQUIRED')
    stamp = row['SnapshotCreateTime'].isoformat() if isinstance(row['SnapshotCreateTime'],dt.datetime) else row['SnapshotCreateTime']
    epoch(stamp)
    return {'identifier':row['DBSnapshotIdentifier'],'arn':row['DBSnapshotArn'],'sourceIdentifier':row['DBInstanceIdentifier'],
            'sourceResourceId':row['DbiResourceId'],'createdAt':stamp,'engineVersion':'8.4.11','allocatedStorageGiB':100,
            'storageType':'gp3','encrypted':True,'kmsKeyArn':row['KmsKeyId'],'tags':{x['Key']:x['Value'] for x in tags},'restorePermissions':[]}


def make_source(values, snapshot_observation):
    docs, source, expected = _source_documents(values)
    value = {'schemaVersion':1,'kind':SOURCE_KIND,'state':'MAC_SQL_SNAPSHOT_SOURCE_VERIFIED','datasetId':DATASET,
             'account':service.ACCOUNT,'region':service.REGION,'source':source,'snapshot':snapshot_projection(snapshot_observation),
             'children':values,'fullDatasetValidated':False,'rowContentHashesVerified':False}
    validate_source(value)
    return value


def validate_source(value):
    fields(value, ('schemaVersion','kind','state','datasetId','account','region','source','snapshot','children','fullDatasetValidated','rowContentHashesVerified'))
    need(value['schemaVersion']==1 and value['kind']==SOURCE_KIND and value['state']=='MAC_SQL_SNAPSHOT_SOURCE_VERIFIED'
         and value['datasetId']==DATASET and value['account']==service.ACCOUNT and value['region']==service.REGION
         and value['fullDatasetValidated'] is False and value['rowContentHashesVerified'] is False, 'MAC_SNAPSHOT_SOURCE_SCOPE_CHANGED')
    docs, source, expected = _source_documents(value['children'])
    need(value['source']==source, 'HISTORICAL_SOURCE_IDENTITY_CHANGED')
    snap = value['snapshot']; old = docs['snapshotAvailable']
    fields(snap, ('identifier','arn','sourceIdentifier','sourceResourceId','createdAt','engineVersion','allocatedStorageGiB','storageType','encrypted','kmsKeyArn','tags','restorePermissions'))
    need(snap['identifier']==old['snapshotIdentifier'] and snap['arn']==old['snapshotArn']
         and snap['arn']=='arn:aws:rds:'+service.REGION+':'+service.ACCOUNT+':snapshot:'+snap['identifier']
         and re.fullmatch(r'airbob-dataset-b-[a-z0-9-]{3,45}',snap['identifier'])
         and snap['sourceIdentifier']==source['rds']['identifier'] and snap['sourceResourceId']==source['rds']['resourceId']
         and epoch(snap['createdAt'])==epoch(old['snapshotCreatedAt']) and snap['tags']==old['tags']
         and snap['engineVersion']=='8.4.11' and snap['allocatedStorageGiB']==100 and snap['storageType']=='gp3'
         and snap['encrypted'] is True and snap['restorePermissions']==[]
         and type(snap['kmsKeyArn']) is str and snap['kmsKeyArn'].startswith('arn:aws:kms:'+service.REGION+':'+service.ACCOUNT+':key/'),
         'SNAPSHOT_AND_ORIGINAL_SOURCE_DIFFER')
    return {'source':source,'snapshot':snap,'expectedTables':expected,'documents':docs,'sourceSha256':digest(value)}


def validate_operation(op, *, now=None):
    fields(op, ('schemaVersion','kind','operationId','runId','resourceFence','executionCommit','sourceProvenance',
                'retirementReference','emptyState','window','targetIdentifier'))
    need(op['schemaVersion']==1 and op['kind']==RESTORE_KIND+'-operation'
         and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}',op['operationId'])
         and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}',op['runId']) and op['targetIdentifier']=='airbob-'+op['runId']
         and type(op['resourceFence']) is int and op['resourceFence']>0 and re.fullmatch(r'[0-9a-f]{40}',op['executionCommit']),
         'EXPLICIT_NEW_TARGET_OPERATION_REQUIRED')
    public_ref(op['sourceProvenance']); public_ref(op['retirementReference'],'measurements/state-clean/')
    need(op['sourceProvenance']['key'].startswith('datasets/'+DATASET+'-mac-snapshots/'),'SOURCE_PROVENANCE_PREFIX_CHANGED')
    fields(op['emptyState'],('key','versionId','sha256'))
    need(op['emptyState']['key']=='airbob/lab/terraform.tfstate' and re.fullmatch(HASH,op['emptyState']['sha256'])
         and type(op['emptyState']['versionId']) is str and bool(op['emptyState']['versionId']), 'EXACT_EMPTY_STATE_REQUIRED')
    w=op['window'];fields(w,('startedAtEpoch','expiresAt','approvedDeadlineEpoch'))
    need(all(type(x) is int and x>0 for x in w.values()) and w['startedAtEpoch']<w['expiresAt']<=w['approvedDeadlineEpoch']
         and w['expiresAt']-w['startedAtEpoch']<=604800, 'EXPLICIT_TARGET_OPERATING_WINDOW_REQUIRED')
    if now is not None: need(w['startedAtEpoch']<=now<w['expiresAt'],'TARGET_OPERATING_WINDOW_CLOSED')
    return op


def check_target_rds(api, op, provenance):
    rows=api['DBInstances'];need(len(rows)==1,'SOLE_RESTORED_TARGET_REQUIRED');r=rows[0]
    origin=provenance['source']['rds'];snap=provenance['snapshot']
    need(r['DBInstanceIdentifier']==op['targetIdentifier'] and r['DBInstanceIdentifier']!=origin['identifier']
         and re.fullmatch(r'db-[A-Z0-9]+',r['DbiResourceId']) and r['DbiResourceId']!=origin['resourceId']
         and r['DBInstanceStatus']=='available' and r['Engine']=='mysql' and r['EngineVersion']=='8.4.11'
         and r['DBInstanceClass']=='db.t3.small' and r['AllocatedStorage']==100 and r['StorageType']=='gp3'
         and r['StorageEncrypted'] is True and r['KmsKeyId']==snap['kmsKeyArn'] and r['MultiAZ'] is False
         and r['PubliclyAccessible'] is False and not r.get('PendingModifiedValues')
         and r['Endpoint']['Port']==3306 and re.fullmatch(ENDPOINT,r['Endpoint']['Address'])
         and r['Endpoint']['Address']!=origin['endpoint'] and r['MasterUserSecret']['SecretStatus']=='active'
         and re.fullmatch(SECRET_ARN,r['MasterUserSecret']['SecretArn']),
         'RESTORED_TARGET_IDENTITY_OR_SHAPE_CHANGED')
    need(len({x['Key'] for x in r['TagList']})==len(r['TagList']), 'DUPLICATE_TARGET_TAGS')
    tags={x['Key']:x['Value'] for x in r['TagList']}
    need(all(tags.get(k)==v for k,v in {'Project':'airbob','Environment':'performance-lab','Service':'rds','RunId':op['runId'],
         'FencingToken':str(op['resourceFence']),'ExpiresAt':str(op['window']['expiresAt'])}.items()) and 'BDatabaseClass' not in tags,
         'NEW_TARGET_RESOURCE_TAGS_CHANGED')
    stamp=r['InstanceCreateTime'].isoformat() if isinstance(r['InstanceCreateTime'],dt.datetime) else r['InstanceCreateTime'];epoch(stamp)
    return {'identifier':r['DBInstanceIdentifier'],'resourceId':r['DbiResourceId'],'endpoint':r['Endpoint']['Address'],
            'createdAt':stamp,'masterSecretArn':r['MasterUserSecret']['SecretArn']}


def restore_event(event, target, snapshot, operation, observed_at):
    e=child(event);request=e.get('requestParameters') or {}
    need(e.get('eventName')=='RestoreDBInstanceFromDBSnapshot' and e.get('eventSource')=='rds.amazonaws.com'
         and e.get('recipientAccountId')==service.ACCOUNT and e.get('awsRegion')==service.REGION
         and not e.get('errorCode') and not e.get('errorMessage')
         and request.get('dBInstanceIdentifier')==target['identifier']
         and request.get('dBSnapshotIdentifier') in (snapshot['identifier'],snapshot['arn'])
         and re.fullmatch(UUID,e.get('eventID','')), 'ACTUAL_TARGET_RESTORE_EVENT_REQUIRED')
    requested=epoch(e['eventTime']);created=epoch(target['createdAt'])
    need(type(observed_at) in (int,float) and operation['window']['startedAtEpoch']<=requested<=created+300
         and created-requested<=7200 and requested<=observed_at and created<=observed_at,
         'ACTUAL_RESTORE_CHRONOLOGY_REQUIRED')
    return requested


def validate_restore(op, source, retirement, snapshot_observation, target_api, event, *, observed_at=None):
    now=time.time() if observed_at is None else observed_at
    validate_operation(op,now=now);p=validate_source(source)
    need(op['sourceProvenance']['sha256']==digest(source) and op['sourceProvenance']['bytes']==len(encoded(source)), 'SOURCE_CANONICAL_BYTES_REQUIRED')
    need(op['resourceFence']>p['source']['resourceFence'] and op['window']['startedAtEpoch']>=epoch(p['documents']['countsDdl']['completedAt']),
         'NEW_OPERATION_MUST_FOLLOW_HISTORICAL_SOURCE')
    need(snapshot_projection(snapshot_observation)==p['snapshot'],'LIVE_SOURCE_SNAPSHOT_CHANGED')
    clean=child(retirement);ref=retirement['reference']
    need(ref.get('bucket')==service.EVIDENCE and {k:v for k,v in ref.items() if k!='bucket'}==op['retirementReference'], 'EXACT_RETIREMENT_REFERENCE_REQUIRED')
    need(clean.get('schemaVersion')==1 and clean.get('status')=='clean' and clean.get('dnsMode')=='direct-only'
         and clean['runId']!=op['runId'] and type(clean['resourceFencingToken']) is int and clean['resourceFencingToken']<op['resourceFence']
         and clean['ociAuthority']['status']=='verified' and clean['orphanScan']=={'status':'clean','scope':'global','runId':clean['runId']}
         and clean['terraformState']=={'key':op['emptyState']['key'],'versionId':op['emptyState']['versionId'],
             'versionIdSha256':sha(op['emptyState']['versionId'].encode()),'objectSha256':op['emptyState']['sha256'],'resourceCount':0}
         and op['retirementReference']['key']=='measurements/state-clean/'+sha(op['emptyState']['versionId'].encode())+'.json'
         and epoch(clean['completedAt'])<=op['window']['startedAtEpoch'], 'LATEST_RETIRED_EMPTY_STATE_REQUIRED')
    target=check_target_rds(target_api,op,source)
    requested=restore_event(event,target,p['snapshot'],op,now)
    return {'schemaVersion':1,'kind':RESTORE_KIND,'state':'MAC_SNAPSHOT_RDS_AVAILABLE','operation':op,
        'sourceSha256':p['sourceSha256'],'retirementReference':op['retirementReference'],'event':event,'target':target,
        'snapshotArn':p['snapshot']['arn'],'availableObservedAtEpoch':now,'requestToAvailableSeconds':round(now-requested,6),
        'timerSemantics':'actual-restore-event-to-first-caller-observed-available','sqlReplayed':False,'fullDatasetValidated':False}


def validate_restore_receipt(value, source):
    fields(value,('schemaVersion','kind','state','operation','sourceSha256','retirementReference','event','target','snapshotArn',
                  'availableObservedAtEpoch','requestToAvailableSeconds','timerSemantics','sqlReplayed','fullDatasetValidated'))
    p=validate_source(source);op=validate_operation(value['operation'])
    need(op['resourceFence']>p['source']['resourceFence'] and op['window']['startedAtEpoch']>=epoch(p['documents']['countsDdl']['completedAt']),
         'NEW_OPERATION_MUST_FOLLOW_HISTORICAL_SOURCE')
    fields(value['target'],('identifier','resourceId','endpoint','createdAt','masterSecretArn'))
    t=value['target'];requested=restore_event(value['event'],t,p['snapshot'],op,value['availableObservedAtEpoch'])
    need(value['schemaVersion']==1 and value['kind']==RESTORE_KIND and value['state']=='MAC_SNAPSHOT_RDS_AVAILABLE'
         and value['sourceSha256']==digest(source)==op['sourceProvenance']['sha256']
         and op['sourceProvenance']['bytes']==len(encoded(source)) and value['snapshotArn']==p['snapshot']['arn']
         and value['retirementReference']==op['retirementReference'] and value['sqlReplayed'] is False and value['fullDatasetValidated'] is False
         and t['identifier']==op['targetIdentifier'] and t['identifier']!=p['source']['rds']['identifier']
         and t['resourceId']!=p['source']['rds']['resourceId'] and re.fullmatch(r'db-[A-Z0-9]+',t['resourceId'])
         and re.fullmatch(ENDPOINT,t['endpoint']) and t['endpoint']!=p['source']['rds']['endpoint']
         and re.fullmatch(SECRET_ARN,t['masterSecretArn'])
         and value['availableObservedAtEpoch']<op['window']['expiresAt']
         and value['timerSemantics']=='actual-restore-event-to-first-caller-observed-available'
         and value['requestToAvailableSeconds']==round(value['availableObservedAtEpoch']-requested,6), 'RESTORE_RECEIPT_CHANGED')
    return value


def batch_rows(raw):
    need(type(raw) is str and raw.endswith('\n') and '\r' not in raw and len(raw.encode())<=MAX_BYTES,'BATCH_RESULT_SHAPE')
    lines=raw[:-1].split('\n');names=lines.pop(0).split('\t');need(len(names)==len(set(names)),'BATCH_COLUMNS_AMBIGUOUS')
    result=[]
    for line in lines:
        row=line.split('\t');need(len(row)==len(names),'BATCH_ROW_SHAPE');result.append(dict(zip(names,row)))
    return result


def ddl_sha(raw, table):
    # Identical byte rule to GrowthFingerprint.java: SHOW CREATE column2 UTF-8,
    # no trimming, whitespace/escape changes or AUTO_INCREMENT removal.
    prefix='Table\tCreate Table\n'+table+'\t'
    need(type(raw) is str and raw.startswith(prefix) and raw.endswith('\n') and len(raw.encode())<=MAX_BYTES,'SHOW_CREATE_RESULT_SHAPE')
    ddl=raw[len(prefix):-1];need(ddl.startswith('CREATE TABLE `'+table+'`'),'SHOW_CREATE_TABLE_CHANGED')
    return sha(ddl.encode())


def _collect_counts(db, expected, observe, guard):
    def query(sql):
        if guard:guard()
        return db.execute(sql)
    def identity():
        rows=batch_rows(query('SELECT @@version AS mysqlVersion, @@server_uuid AS serverUuid, DATABASE() AS schemaName;'))
        need(len(rows)==1 and rows[0].get('mysqlVersion')=='8.4.11' and rows[0].get('schemaName')=='airbobdb'
             and re.fullmatch(UUID,rows[0].get('serverUuid','')),'ACTUAL_TARGET_SQL_IDENTITY_REQUIRED')
        tls=batch_rows(query("SHOW SESSION STATUS LIKE 'Ssl_cipher';"))
        need(len(tls)==1 and tls[0].get('Variable_name')=='Ssl_cipher' and re.fullmatch(r'[A-Za-z0-9_-]{1,128}',tls[0].get('Value','')),'TARGET_TLS_REQUIRED')
        return rows[0]|{'tlsCipher':tls[0]['Value']}
    before=identity();inventory_sql="SELECT table_name AS tableName FROM information_schema.tables WHERE table_schema='airbobdb' AND table_type='BASE TABLE' ORDER BY table_name;"
    wanted=[{'tableName':name} for name in expected];need(batch_rows(query(inventory_sql))==wanted,'EXACT_BASE_TABLE_INVENTORY_REQUIRED')
    found={};mismatches=[]
    for name,wanted_row in expected.items():
        need(re.fullmatch(r'[a-z0-9_]+',name),'SAFE_TABLE_REQUIRED')
        rows=batch_rows(query('SELECT COUNT(*) AS rowCount FROM `airbobdb`.`'+name+'`;'))
        need(len(rows)==1 and set(rows[0])=={'rowCount'} and re.fullmatch(r'0|[1-9][0-9]*',rows[0]['rowCount']),'EXACT_COUNT_RESULT_REQUIRED')
        result={'rows':int(rows[0]['rowCount']),'ddlSha256':ddl_sha(query('SHOW CREATE TABLE `airbobdb`.`'+name+'`;'),name)}
        found[name]=result
        mismatches.extend({'table':name,'field':key} for key in result if result[key]!=wanted_row[key])
        observe(name,result)
    need(batch_rows(query(inventory_sql))==wanted,'BASE_TABLE_INVENTORY_CHANGED')
    after=identity();need(before==after,'TARGET_SQL_IDENTITY_CHANGED')
    need(not mismatches,'TARGET_COUNT_OR_DDL_MISMATCH')
    if guard:guard()
    return found,before,after


def write_new(path, value):
    raw=encoded(value)
    with os.fdopen(os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600),'wb') as stream:
        stream.write(raw);stream.flush();os.fsync(stream.fileno())
    fd=os.open(path.parent,os.O_RDONLY)
    try:os.fsync(fd)
    finally:os.close(fd)


def validate_counts(db, target_binding, source, output, guard=None):
    """db.execute(sql) -> MySQL --batch --raw text WITH column headers.

    Caller provides an actual CA/hostname-verified read-only connection/transaction
    and current API/lease guard. The UUID is observed here, not supplied by a user.
    """
    restored=validate_restore_receipt(target_binding,source);p=validate_source(source)
    path=Path(output).absolute();need(path.resolve()==path and not path.exists(),'NEW_COUNT_OUTPUT_REQUIRED');path.mkdir(mode=0o700)
    result={'schemaVersion':1,'kind':COUNTS_KIND,'state':'TARGET_COUNTS_DDL_STARTED','sourceSha256':digest(source),
        'validatorSha256':source_sha(),'restoreCanonicalSha256':digest(restored),'target':restored['target'],
        'runId':restored['operation']['runId'],'resourceFence':restored['operation']['resourceFence'],'window':restored['operation']['window'],
        'startedAtEpoch':time.time(),'tables':{},'sourceServerUuid':p['source']['rds']['serverUuid'],
        'fullDatasetValidated':False,'rowContentHashesVerified':False,'sqlReplayed':False}
    try:
        validate_operation(restored['operation'],now=time.time())
        found,before,after=_collect_counts(db,p['expectedTables'],lambda name,row:write_new(path/(name+'.json'),row),guard)
        result.update(tables=found,identityBefore=before,identityAfter=after,observedTargetUuid=before['serverUuid'],
                      state='TARGET_32_COUNTS_AND_DDL_VERIFIED')
        validate_operation(restored['operation'],now=time.time())
    except BaseException:
        result.update(state='TARGET_COUNTS_DDL_UNCONFIRMED',completedAtEpoch=time.time());write_new(path/'result.json',result)
        raise ValueError('TARGET_COUNTS_DDL_UNCONFIRMED') from None
    result['completedAtEpoch']=time.time();write_new(path/'result.json',result);return result


def make_target_receipt(source, restored, counts):
    value={'schemaVersion':1,'kind':TARGET_KIND,'state':'MAC_SNAPSHOT_TARGET_COUNTS_DDL_VERIFIED','sourceSha256':digest(source),
        'restoreCanonicalSha256':digest(restored),'countsCanonicalSha256':digest(counts),'target':restored['target']|{'serverUuid':counts.get('observedTargetUuid')},
        'runId':restored['operation']['runId'],'resourceFence':restored['operation']['resourceFence'],'window':restored['operation']['window'],
        'sourceServerUuid':source['source']['rds']['serverUuid'],'fullDatasetValidated':False,'rowContentHashesVerified':False,'sqlReplayed':False}
    validate_target_receipt(value,source,restored,counts);return value


def validate_target_receipt(value, source, restored, counts):
    validate_restore_receipt(restored,source);p=validate_source(source)
    fields(value,('schemaVersion','kind','state','sourceSha256','restoreCanonicalSha256','countsCanonicalSha256','target','runId','resourceFence','window','sourceServerUuid','fullDatasetValidated','rowContentHashesVerified','sqlReplayed'))
    fields(counts,('schemaVersion','kind','state','sourceSha256','validatorSha256','restoreCanonicalSha256','target',
                  'runId','resourceFence','window','startedAtEpoch','completedAtEpoch','tables','sourceServerUuid',
                  'fullDatasetValidated','rowContentHashesVerified','sqlReplayed','identityBefore','identityAfter','observedTargetUuid'))
    fields(counts['identityBefore'],('mysqlVersion','serverUuid','schemaName','tlsCipher'))
    need(type(counts['tables']) is dict and all(type(row.get('rows')) is int and row['rows']>=0
         and type(row.get('ddlSha256')) is str and re.fullmatch(HASH,row['ddlSha256']) for row in counts['tables'].values()),
         'EXACT_COUNT_AND_DDL_TYPES_REQUIRED')
    need(value['schemaVersion']==1 and value['kind']==TARGET_KIND and value['state']=='MAC_SNAPSHOT_TARGET_COUNTS_DDL_VERIFIED'
         and value['sourceSha256']==counts['sourceSha256']==digest(source)
         and value['restoreCanonicalSha256']==counts['restoreCanonicalSha256']==digest(restored)
         and value['countsCanonicalSha256']==digest(counts) and counts.get('schemaVersion')==1 and counts.get('kind')==COUNTS_KIND
         and counts.get('state')=='TARGET_32_COUNTS_AND_DDL_VERIFIED' and counts.get('validatorSha256')==source_sha()
         and counts.get('tables')==p['expectedTables'] and counts.get('identityBefore')==counts.get('identityAfter')
         and value['target']==restored['target']|{'serverUuid':counts['observedTargetUuid']}
         and counts['target']==restored['target'] and counts['identityBefore']['serverUuid']==counts['observedTargetUuid']
         and re.fullmatch(UUID,counts['observedTargetUuid']) and counts['identityBefore']['mysqlVersion']=='8.4.11'
         and counts['identityBefore']['schemaName']=='airbobdb'
         and re.fullmatch(r'[A-Za-z0-9_-]{1,128}',counts['identityBefore']['tlsCipher'])
         and value['sourceServerUuid']==counts['sourceServerUuid']==p['source']['rds']['serverUuid']
         and all(value[k]==counts[k]==restored['operation'][k] for k in ('runId','resourceFence','window'))
         and restored['availableObservedAtEpoch']<=counts['startedAtEpoch']<=counts['completedAtEpoch']<value['window']['expiresAt']
         and all(value[k] is False and counts[k] is False for k in ('fullDatasetValidated','rowContentHashesVerified','sqlReplayed')),
         'NEW_TARGET_COUNTS_DDL_BINDING_CHANGED')
    # A coincident, actually observed source/target UUID is not rewritten/rejected.
    return value


def selected(manifest): return manifest.get('preparation',{}).get('sourceMode')==MODE


def validate_preparation_fields(prep, dataset, run):
    fields(prep,PREPARATION_FIELDS);need(prep['sourceMode']==MODE and dataset==DATASET,'MAC_SNAPSHOT_SERVICE_MODE_REQUIRED')
    for key in ('receipt','restoreReceipt','countsDdlReceipt'):public_ref(prep[key],'data-bootstrap/'+run+'/')
    public_ref(prep['sourceProvenance'],'datasets/'+dataset+'-mac-snapshots/')
    public_ref(prep['rdsCaBundle'],'datasets/'+dataset+'-aws-preparation/files/')
    need(prep['rdsCaBundle']['key'].endswith('/'+prep['rdsCaBundle']['sha256']+'-rds-ca.pem'),'EXACT_RDS_CA_REFERENCE_REQUIRED')


def bootstrap(manifest, context, root, output):
    """Use existing native ES/dependency utilities only after target admission."""
    validate_preparation_fields(manifest['preparation'],manifest['datasetId'],manifest['runId'])
    root,output=Path(root),Path(output);need(not (root/'STOP').exists() and not output.exists(),'NEW_MAC_SNAPSHOT_BOOTSTRAP_REQUIRED')
    output.mkdir(mode=0o700,parents=False)
    aws=restore.Aws();lease=restore.Lease(aws,context['lease'])
    def guard(force=False):
        need(not (root/'STOP').exists() and time.time()<int(context['expiresAt']),'TARGET_OPERATING_WINDOW_CLOSED');lease(force=force)
    guard(force=True);prep=manifest['preparation']
    documents={name:service.fetch(aws,prep[name],output/(name+'.json'),service.BUCKET if name=='sourceProvenance' else service.EVIDENCE)
               for name in ('sourceProvenance','receipt','restoreReceipt','countsDdlReceipt')}
    source=documents['sourceProvenance'];p=validate_source(source)
    target=validate_target_receipt(documents['receipt'],source,documents['restoreReceipt'],documents['countsDdlReceipt'])
    need(all(manifest['rds'][key]==target['target'][key] for key in manifest['rds'])
         and manifest['application']==p['source']['application'] and manifest['mysql']==p['source']['mysql']
         and manifest['search']==p['documents']['serviceManifest']['search']
         and manifest['debezium']==p['documents']['serviceManifest']['debezium']
         and prep['rdsCaBundle']==p['documents']['serviceManifest']['preparation']['rdsCaBundle']
         and context['runId']==target['runId'] and context['resourceFence']==target['resourceFence']
         and int(context['expiresAt'])==target['window']['expiresAt'] and context['databaseBootstrap']=='snapshot'
         and context['rdsInstanceClass']=='db.t3.small'
         and all(context['rds'][key]==target['target'][key] for key in ('identifier','resourceId','endpoint','masterSecretArn')),
         'MAC_SNAPSHOT_SERVICE_TARGET_CHANGED')
    def live_guard():
        guard(force=True)
        restore.live_rds(aws,{'rds':context['rds'],'writerAsgNames':['airbob-'+manifest['runId']+'-app']})
        current=check_target_rds(aws.call('rds','describe-db-instances'),documents['restoreReceipt']['operation'],source)
        need(current==documents['restoreReceipt']['target'],'LIVE_RESTORED_TARGET_CHANGED')
        return current
    live_guard()
    ca=output/'rds-ca.pem';ca_ref=prep['rdsCaBundle']
    response=aws.call('s3api','get-object','--bucket',service.BUCKET,'--key',ca_ref['key'],'--version-id',ca_ref['versionId'],str(ca))
    ca.chmod(0o600)
    need(response.get('VersionId')==ca_ref['versionId'] and service.sha(ca)==ca_ref['sha256']
         and ca.stat().st_size==ca_ref['bytes'],'EXACT_RDS_CA_BYTES_REQUIRED')
    runtime=output/'app-runtime-binding.json';service.fetch(aws,manifest['appRuntimeBinding'],runtime,service.BUCKET)
    app_runtime=service.app_runtime.validate_runtime_binding(runtime,manifest['application']['image'],manifest['application']['mainCommit'],manifest['application']['appJarSha256'])
    service.validate_app_runtime_projection(app_runtime,manifest['application'])
    service.verify_debezium(manifest['debezium'],restore.command,guard)
    transport=service.fetch(aws,manifest['search']['transport'],output/'transport.json',service.BUCKET)
    private=output/'.connection';private.mkdir(mode=0o700)
    try:
        with mac.connection(aws,context['rds'],ca,private,guard) as db,restore.exclusive_database(db):
            need(int(db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() AND COALESCE(USER,'') NOT IN ('rdsadmin','event_scheduler') AND ID<>COALESCE(IS_USED_LOCK('airbob_global_b_restore'),-1)",False))==0,'UNDECLARED_DATABASE_CLIENT')
            need(db.scalar('SELECT @@server_uuid',False)==target['target']['serverUuid']
                 and int(db.scalar('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1'))==28
                 and int(db.scalar('SELECT COUNT(*) FROM flyway_schema_history WHERE success=0'))==0
                 and int(db.scalar('SELECT COUNT(*) FROM outbox'))==0
                 and int(db.scalar("SELECT COUNT(*) FROM accommodation WHERE id=16102 AND member_id=6675 AND status='PUBLISHED'"))==1,'LIVE_TARGET_LIGHT_POSTCHECK_CHANGED')
            cipher=db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'",False);need(len(cipher)==1 and bool(cipher[0].get('Value')),'TARGET_TLS_REQUIRED')
            class GuardedSearch(search.Elasticsearch):
                def api(self,*args,**kwargs):db.guard();return super().api(*args,**kwargs)
            es=GuardedSearch({'url':'http://elasticsearch.lab.airbob.internal:9200','requestTimeoutSeconds':30})
            native=mac.native_restore(manifest,transport,DUMP_SHA,es,db.guard,output,min(18000,int(context['expiresAt'])-int(time.time())-60))
            dependencies=service.bootstrap_dependencies(manifest,context['rds']['endpoint'],context['redisImage'],aws,db,private,context['debeziumSecretArn'],live_guard)
            need(es.alias()==native['restoredIndex'],'RESTORED_ALIAS_CHANGED')
    finally:shutil.rmtree(private)
    return {'schemaVersion':1,'kind':service.KIND+'-readiness','state':service.READY,
        **{k:manifest[k] for k in ('datasetId','runId','serviceRelease','mysql','rds','application','appRuntimeBinding','debezium','toolSources')},
        'manifestSha256':context['manifestSha256'],'appRuntime':app_runtime,'preparationReceipt':prep['receipt'],
        'sourceMode':MODE,'sourceProvenance':prep['sourceProvenance'],'restoreReceipt':prep['restoreReceipt'],'countsDdlReceipt':prep['countsDdlReceipt'],
        'fullDatasetValidated':False,'sqlReplayed':False,'searchDatasetRestored':True,'nativeSearch':native,
        'searchTransport':manifest['search']['transport'],'restoredIndex':native['restoredIndex'],'debeziumVerified':True,
        **dependencies,'applicationStarted':False,'deploymentReady':False,'recordedAt':dt.datetime.now(dt.timezone.utc).isoformat()}
