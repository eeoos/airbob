"""Mac SQL/postcheck admission and native-search/dependency startup, without SQL replay.

This consumer uses the normal Connect host and existing service tools. It does
not import SQL, run ETL, fingerprint all database rows, or change infrastructure.
"""
from __future__ import annotations

import contextlib
import datetime as dt
import json
from pathlib import Path
import re
import shutil
import time
from types import SimpleNamespace

import growth_b_service as service
import growth_b_mac_downsize as downsize
import growth_b_aws_restore as restore
import growth_b_search as search

MODE = 'mac-sql-postcheck'
require = service.require


def selected(manifest):
    return manifest.get('preparation', {}).get('sourceMode') == MODE


def validate_preparation_fields(prep, dataset, run):
    require(set(prep) == {'sourceMode', 'receipt', 'sqlImportReceipt', 'postcheckReceipt', 'rdsCaBundle'}
            and prep['sourceMode'] == MODE, 'Exact Mac source references required')
    for name in ('receipt', 'sqlImportReceipt', 'postcheckReceipt'):
        service.ref(prep[name], f'data-bootstrap/{run}/')
    require(prep['receipt']['key'] == f'data-bootstrap/{run}/{dataset}-mac-rds-downsize.json',
            'Exact same-instance Mac downsize reference required')
    service.ref(prep['rdsCaBundle'], f'datasets/{dataset}-aws-preparation/files/')
    require(prep['rdsCaBundle']['key'].endswith('/' + prep['rdsCaBundle']['sha256'] + '-rds-ca.pem'),
            'Exact RDS CA bundle reference required')


def validate_source(manifest, sql, postcheck, transition, *, original=None, original_sha=None):
    prep, target = manifest['preparation'], manifest['rds']
    validate_preparation_fields(prep, manifest['datasetId'], manifest['runId'])
    op = transition['operator'] if original is None else original
    downsize.validate_receipt(op, transition, transition['operatorSha256'] if original_sha is None else original_sha)
    require(transition['operator']['runId'] == manifest['runId']
            and transition['operator']['datasetRelease'] == manifest['datasetId']
            and transition['operator']['bundleCommit'] == manifest['application']['mainCommit']
            and transition['operator']['appImageReference'] == manifest['application']['image']
            and all(transition['rds'][k] == v for k, v in target.items())
            and transition['sqlImportSha256'] == prep['sqlImportReceipt']['sha256']
            and transition['postcheckSha256'] == prep['postcheckReceipt']['sha256'], 'Mac source lineage differs')
    require(sql.get('schemaVersion') == 1 and sql.get('kind') == 'global-b-mac-sql-import'
            and sql.get('state') == 'SQL_IMPORT_COMPLETED' and sql.get('fullDatasetValidated') is False
            and sql.get('automaticReplayAllowed') is False, 'Completed SQL-only Mac import required')
    binding, stream = sql['binding'], sql['stream']
    require(all(binding.get(k) == v for k, v in {'accountId': service.ACCOUNT, 'region': service.REGION,
        'identifier': target['identifier'], 'resourceId': target['resourceId'], 'endpoint': transition['rds']['endpoint'],
        'schema': 'airbobdb', 'engineVersion': '8.4.11', 'importClass': downsize.LARGE,
        'allocatedStorageGiB': 100, 'sqlElapsedTimeoutSeconds': None}.items())
        and re.fullmatch(r'[0-9a-f]{64}', binding.get('dumpSha256', ''))
        and binding['caBundleSha256'] == prep['rdsCaBundle']['sha256'], 'Mac SQL target or sealed input differs')
    require(stream.get('state') == 'SQL_IMPORT_COMPLETED'
            and all(type(stream.get(k)) is int and stream[k] == 0 for k in ('mysqlExitCode', 'gzipExitCode'))
            and type(stream.get('compressedBytesTotal')) is int and stream['compressedBytesTotal'] > 0
            and stream.get('compressedBytesRead') == stream['compressedBytesTotal'], 'Confirmed complete SQL stream required')
    require(postcheck.get('state') == 'SQL_IMPORT_POSTCHECK_PASSED' and postcheck.get('binding') == binding
            and postcheck.get('database') == sql.get('database') and postcheck.get('rds') == sql.get('rds')
            and postcheck['database']['serverUuid'] == target['serverUuid']
            and postcheck['database']['engineVersion'] == '8.4.11'
            and postcheck.get('sqlImportReceiptSha256') == prep['sqlImportReceipt']['sha256']
            and postcheck.get('flywayVersion') == 28 and postcheck.get('failedMigrations') == 0
            and postcheck.get('representativePrimaryKeysObserved') is True
            and postcheck.get('fullDatasetValidated') is False and postcheck.get('sqlReplayed') is False,
            'Same successful lightweight postcheck required')
    return transition


def native_restore(manifest, transport, dump_sha, es, guard, output, timeout):
    """Restore sealed native bytes; verify recovery/count/sample, not all fields."""
    selection = manifest['search']
    require(type(timeout) is int and 0 < timeout <= 18000, 'Bounded native restore time required')
    prefix = f"datasets/{manifest['datasetId']}-search/{selection['snapshotRelease']}/native"
    require(transport.get('schemaVersion') == 1 and transport.get('kind') == 'global-growth-b-search-transport'
            and transport.get('datasetId') == manifest['datasetId']
            and transport.get('snapshotRelease') == selection['snapshotRelease']
            and transport.get('bucket') == service.BUCKET and transport.get('region') == service.REGION
            and transport['repository'] == {'type': 's3', 'bucket': service.BUCKET, 'basePath': prefix}
            and transport['source']['appJarSha256'] == manifest['application']['appJarSha256']
            and transport['sql']['dump']['sha256'] == dump_sha, 'Sealed search transport differs from Mac SQL source')
    identity = es.api('GET', '/')
    require(identity['version']['number'] == '8.18.8' and isinstance(identity.get('cluster_uuid'), str)
            and identity['cluster_uuid'] not in ('', '_na_'), 'Live native search runtime differs')
    require(es.alias(optional=True) is None and es.api('GET', '/_cat/indices/accommodations-v*?format=json&h=index') == [],
            'Fresh application search namespace required; no automatic replay')
    suffix = downsize.digest({'run': manifest['runId'], 'release': manifest['serviceRelease']})[:20]
    repository = SimpleNamespace(name='airbob_mac_' + suffix)
    target = 'accommodations-vmac-' + suffix
    require(es.optional('/_snapshot/' + repository.name) is None, 'Mac snapshot intent already exists')
    service.write(output / 'search-intent.json', {'repository': repository.name, 'index': target,
        'transport': selection['transport'], 'snapshotUuid': transport['snapshotUuid'], 'automaticReplayAllowed': False})
    guard(force=True)
    require(es.api('PUT', '/_snapshot/' + repository.name, {'type': 's3', 'settings': {
        'bucket': service.BUCKET, 'base_path': prefix, 'region': service.REGION, 'readonly': True}}).get('acknowledged') is True,
        'Read-only native repository registration failed')
    registration = es.api('GET', '/_snapshot/' + repository.name)[repository.name]
    require(registration['type'] == 's3' and registration['settings']['bucket'] == service.BUCKET
            and registration['settings']['base_path'] == prefix
            and str(registration['settings'].get('readonly')).lower() == 'true', 'Read-only repository binding changed')
    snapshots = es.api('GET', '/_snapshot/' + repository.name + '/_all')['snapshots']
    require(len(snapshots) == 1 and snapshots[0]['uuid'] == transport['snapshotUuid']
            and len(snapshots[0]['indices']) == 1, 'Exact sealed snapshot UUID required')
    snapshot = snapshots[0]
    search.validate_snapshot(snapshot, snapshot['snapshot'], snapshot['indices'][0], transport['snapshotUuid'])
    started = time.monotonic()
    measurements = search.restore_index(es, repository, {'snapshot': snapshot['snapshot'], 'snapshotIndex': snapshot['indices'][0]}, target, timeout)
    count = es.api('GET', '/' + target + '/_count').get('count')
    require(type(count) is int and count == selection['documentFingerprint']['documents'], 'Restored published document count differs')
    query = {'size': 1, 'track_total_hits': True, '_source': ['accommodationId'], 'query': {'term': {'accommodationId': 16102}}}
    def sample(index):
        result = es.api('POST', '/' + index + '/_search', query)
        hits = result.get('hits', {})
        require(hits.get('total') == {'value': 1, 'relation': 'eq'} and len(hits.get('hits', [])) == 1
                and hits['hits'][0].get('_source') == {'accommodationId': 16102}, 'Representative native search query failed')
    sample(target)
    guard(force=True)
    require(es.alias(optional=True) is None, 'Application alias appeared during restore')
    require(es.api('PUT', '/' + target + '/_settings', {'index.blocks.write': False}).get('acknowledged') is True,
            'Verified native index could not open for CDC')
    require(es.api('POST', '/_aliases', {'actions': [{'add': {'index': target, 'alias': search.ALIAS, 'is_write_index': True}}]}).get('acknowledged') is True,
            'Single native write alias activation failed')
    require(es.alias() == target, 'Single native write alias differs')
    sample(search.ALIAS)
    require(es.api('DELETE', '/_snapshot/' + repository.name).get('acknowledged') is True, 'Read-only repository cleanup failed')
    result = {'kind': 'global-b-mac-native-search', 'state': 'NATIVE_SEARCH_COUNT_AND_SAMPLE_VERIFIED',
        'datasetId': manifest['datasetId'], 'runId': manifest['runId'], 'transport': selection['transport'],
        'snapshotUuid': snapshot['uuid'], 'clusterUuid': identity['cluster_uuid'], 'restoredIndex': target,
        'documents': count, 'singleWriteAlias': True, 'representativeSearchPassed': True,
        'nativeRestoreSucceeded': True, 'repositoryReadOnly': True, 'repositoryRemoved': True,
        'restoreRequestedAt': measurements.events['restoreRequested'][0],
        'recoveryCompletedAt': measurements.events['recoveryCompleted'][0],
        'elapsedSeconds': round(time.monotonic() - started, 6), 'fullDatasetValidated': False,
        'allDocumentSourceFieldsEqual': False, 'sqlReplayed': False,
        'validationLocation': 'connect-host-private-dns', 'publicHttpVerified': False}
    service.write(output / 'native-search.json', result)
    return result


@contextlib.contextmanager
def connection(aws, target, ca, directory, guard):
    secret = json.loads(aws.call('secretsmanager', 'get-secret-value', '--secret-id', target['masterSecretArn'])['SecretString'])
    require(isinstance(secret.get('username'), str) and secret['username'] and isinstance(secret.get('password'), str)
            and secret['password'], 'Managed database credentials unavailable')
    defaults = directory / 'mysql.cnf'
    # The existing normal Connect bundle installs MariaDB's CLI. These flags
    # require CA verification and hostname verification, without ETL or a JDK.
    restore.private_text(defaults, '[client]\n' + '\n'.join(k + '=' + restore.cnf_quote(v) for k, v in {
        'host': target['endpoint'], 'port': '3306', 'user': secret['username'], 'password': secret['password'],
        'ssl-ca': str(ca), 'ssl-verify-server-cert': '1', 'connect-timeout': '10'}.items()) + '\n')
    del secret
    try:
        yield restore.Database(defaults, 60, guard)
    finally:
        defaults.unlink(missing_ok=True)


def bootstrap(manifest, context, root, output):
    root, output = Path(root), Path(output)
    require(not (root / 'STOP').exists() and not output.exists(), 'Stopped or previously attempted Mac service bootstrap')
    output.mkdir(mode=0o700, parents=False)
    aws = restore.Aws(); lease = restore.Lease(aws, context['lease'])
    def guard(force=False):
        require(time.time() < int(context['expiresAt']), 'Original resource expiry reached')
        lease(force=force)
    guard(force=True)
    prep = manifest['preparation']
    documents = {name: service.fetch(aws, prep[name], output / (name + '.json'), service.EVIDENCE)
                 for name in ('receipt', 'sqlImportReceipt', 'postcheckReceipt')}
    transition = validate_source(manifest, documents['sqlImportReceipt'], documents['postcheckReceipt'], documents['receipt'])
    require(context['resourceFence'] == transition['operator']['fencingToken']
            and context['expiresAt'] == transition['operator']['expiresAt']
            and context['runId'] == manifest['runId'] and context['databaseBootstrap'] == 'dump'
            and context['rdsInstanceClass'] == downsize.SMALL
            and all(context['rds'][k] == transition['rds'][k] for k in ('identifier', 'resourceId', 'endpoint')),
            'Current Mac service context differs from original run')
    config = {'rds': context['rds'], 'writerAsgNames': ['airbob-' + manifest['runId'] + '-app']}
    def live_guard():
        guard(force=True)
        live = restore.live_rds(aws, config)
        observation = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', context['rds']['identifier'])
        downsize.check_live_rds(observation, transition['operator'], transition['rds'], downsize.SMALL)
        return live
    live_guard()
    ca = output / 'rds-ca.pem'
    response = aws.call('s3api', 'get-object', '--bucket', service.BUCKET, '--key', prep['rdsCaBundle']['key'],
        '--version-id', prep['rdsCaBundle']['versionId'], str(ca))
    ca.chmod(0o600)
    require(response.get('VersionId') == prep['rdsCaBundle']['versionId'] and service.sha(ca) == prep['rdsCaBundle']['sha256']
            and ca.stat().st_size == prep['rdsCaBundle']['bytes'], 'Exact RDS CA bytes required')
    runtime_file = output / 'app-runtime-binding.json'
    service.fetch(aws, manifest['appRuntimeBinding'], runtime_file, service.BUCKET)
    app_runtime = service.app_runtime.validate_runtime_binding(runtime_file, manifest['application']['image'],
        manifest['application']['mainCommit'], manifest['application']['appJarSha256'])
    service.validate_app_runtime_projection(app_runtime, manifest['application'])
    service.verify_debezium(manifest['debezium'], restore.command, guard)
    transport = service.fetch(aws, manifest['search']['transport'], output / 'transport.json', service.BUCKET)
    secret_dir = output / '.connection'; secret_dir.mkdir(mode=0o700)
    try:
        with connection(aws, context['rds'], ca, secret_dir, guard) as db, restore.exclusive_database(db):
            require(int(db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() "
                "AND COALESCE(USER,'') NOT IN ('rdsadmin','event_scheduler') "
                "AND ID<>COALESCE(IS_USED_LOCK('airbob_global_b_restore'),-1)", False)) == 0,
                'No undeclared database clients allowed before service startup')
            require(db.scalar('SELECT @@server_uuid', False) == manifest['rds']['serverUuid']
                    and int(db.scalar('SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1')) == 28
                    and int(db.scalar('SELECT COUNT(*) FROM flyway_schema_history WHERE success=0')) == 0
                    and int(db.scalar('SELECT COUNT(*) FROM outbox')) == 0
                    and int(db.scalar("SELECT COUNT(*) FROM accommodation WHERE id=16102 AND member_id=6675 AND status='PUBLISHED'")) == 1,
                    'Current database identity, V28, or representative differs')
            cipher = db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'", False)
            require(len(cipher) == 1 and bool(cipher[0].get('Value')), 'Database TLS is required')
            class GuardedSearch(search.Elasticsearch):
                def api(self, *args, **kwargs):
                    db.guard(); return super().api(*args, **kwargs)
            es = GuardedSearch({'url': 'http://elasticsearch.lab.airbob.internal:9200', 'requestTimeoutSeconds': 30})
            native = native_restore(manifest, transport, documents['sqlImportReceipt']['binding']['dumpSha256'], es,
                db.guard, output, min(18000, int(context['expiresAt']) - int(time.time()) - 60))
            dependencies = service.bootstrap_dependencies(manifest, context['rds']['endpoint'], context['redisImage'],
                aws, db, secret_dir, context['debeziumSecretArn'], live_guard)
            require(es.alias() == native['restoredIndex'], 'Restored alias changed during CDC startup')
    finally:
        shutil.rmtree(secret_dir)
    return {'schemaVersion': 1, 'kind': service.KIND + '-readiness', 'state': service.READY,
        **{k: manifest[k] for k in ('datasetId', 'runId', 'serviceRelease', 'mysql', 'rds', 'application', 'appRuntimeBinding', 'debezium', 'toolSources')},
        'manifestSha256': context['manifestSha256'], 'appRuntime': app_runtime, 'preparationReceipt': prep['receipt'],
        'sourceMode': MODE, 'sqlImportReceipt': prep['sqlImportReceipt'], 'postcheckReceipt': prep['postcheckReceipt'],
        'fullDatasetValidated': False, 'sqlReplayed': False, 'searchDatasetRestored': True, 'nativeSearch': native,
        'searchTransport': manifest['search']['transport'], 'restoredIndex': native['restoredIndex'],
        'debeziumVerified': True, **dependencies, 'applicationStarted': False, 'deploymentReady': False,
        'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
