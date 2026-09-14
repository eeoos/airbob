"""Dependency sequencing and failure gates with no cloud/network or containers."""
import contextlib
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from test_growth_b_service_contract import fixture, service, runtime_projection
import growth_b_aws_restore as restore
import growth_b_runtime as runtime
import growth_b_search as search


class Bootstrap(unittest.TestCase):
    def execute(self, *, changed_fingerprint=False, reused_cdc=False, invalid_runtime=False,
                retention_rows=None, retention_error=False):
        self.events = []
        if retention_rows is None: retention_rows = [{'name': 'binlog retention hours', 'value': 24, 'description': 'Binlog retention'}]
        manifest = fixture()
        prepared_bytes = b'{}\n'
        import hashlib
        manifest['preparation']['preparedFingerprintSha256'] = hashlib.sha256(prepared_bytes).hexdigest()
        prep = {'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
            'datasetId': manifest['datasetId'], 'runId': manifest['runId'], 'rdsResourceId': manifest['rds']['resourceId'], 'serverUuid': manifest['rds']['serverUuid'],
            'restoreReceiptSha256': manifest['preparation']['restoreReceiptSha256'],
            'preparation': {'preparedFingerprintSha256': manifest['preparation']['preparedFingerprintSha256']},
            'deploymentReady': False, 'applicationLeftRunning': False}
        restored = {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'datasetId': manifest['datasetId'],
            'snapshotRelease': manifest['search']['snapshotRelease'], 'snapshotUuid': 'uuid-snapshot',
            'mysql': {'serverUuid': manifest['rds']['serverUuid']}, 'fullDocumentFingerprint': manifest['search']['documentFingerprint'],
            'nativeTransport': {'manifestSha256': manifest['search']['transport']['sha256'], 'exactVersionBytesVerified': True, 'sourceSealsChanged': False},
            'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'allDocumentSourceFieldsEqual': True,
            'elasticsearch': {'image': manifest['search']['image'], 'clusterUuid': 'cluster-b'}, 'restoredIndex': 'accommodations-vb'}
        transport = {'schemaVersion': 1, 'kind': 'global-growth-b-search-transport', 'bucket': service.BUCKET, 'region': service.REGION, 'datasetId': manifest['datasetId'], 'source': {'appJarSha256': manifest['application']['appJarSha256']}, 'snapshotUuid': 'uuid-snapshot'}
        selected = {manifest['preparation']['receipt']['key']: prep, manifest['search']['restoreReceipt']['key']: restored,
                    manifest['search']['transport']['key']: transport, manifest['appRuntimeBinding']['key']: {'runtime': runtime_projection(manifest)}}
        class Aws:
            def call(inner, *args): self.events.append(('aws', args)); return {}
        class DB:
            timeout = 60
            def guard(inner, **kwargs): self.events.append(('lease',))
            def scalar(inner, sql, *args):
                self.events.append(('sql-read', sql))
                if '@@server_uuid' in sql: return manifest['rds']['serverUuid']
                if 'MAX(CAST(version' in sql: return '28'
                return '0'
            def literal(inner, value): return "'" + value + "'"
            def rows(inner, sql, database=True):
                self.assertEqual('CALL mysql.rds_show_configuration()', sql); self.assertFalse(database)
                self.events.append(('sql-read', sql)); return retention_rows
            def execute(inner, sql, database=True):
                self.events.append(('sql-write', sql.split(' ')[0]))
                if sql.startswith('CALL'):
                    self.assertEqual("CALL mysql.rds_set_configuration('binlog retention hours', 24);", sql)
                    self.assertFalse(database)
                    if retention_error: raise ValueError('RDS_RETENTION_SET_FAILED')
        class ES:
            def __init__(inner, *_): pass
            def api(inner, *_): return {'cluster_uuid': 'cluster-b'}
            def alias(inner): return 'accommodations-vb'
            def fingerprint(inner, *_): self.events.append(('search-full',)); return manifest['search']['documentFingerprint']
        desired = {}
        def http(method, path, value=None):
            self.events.append(('connect', method, path))
            if path == '/connector-plugins': return [{'class': 'io.debezium.connector.mysql.MySqlConnector', 'type': 'source', 'version': '3.0.8.Final'}]
            if path == '/': return {'version': manifest['debezium']['connectVersion']}
            if path == '/connectors' and method == 'GET': return ['other'] if reused_cdc else []
            if method == 'POST': desired.update(value['config']); return {}
            if path.endswith('/status'): return {'connector': {'state': 'RUNNING'}, 'tasks': [{'state': 'RUNNING'}]}
            return desired
        def command(args, **kwargs):
            self.events.append(('command', args[0]))
            if args[:2] == ['docker', 'ps']: return b'0123456789ab\n'
            if args[:2] == ['docker', 'inspect']: return manifest['debezium']['image'].encode()
            if args[:3] == ['docker', 'image', 'inspect']: return manifest['debezium']['buildCommit'].encode()
            if args[:2] == ['docker', 'exec']: return (manifest['debezium']['pluginJarSha256'] + '  connector.jar\n').encode()
            if 'redis-cli' in args:
                value = '1' if '6379' in args else '2'
                return ('run_id:' + value * 40 + '\r\n').encode()
            tool = next(arg for arg in args if arg.startswith('/opt/kafka/bin/'))
            if tool.endswith('kafka-topics.sh'):
                if '--list' in args: return b''
                if '--describe' in args: return b'PartitionCount: 3 ReplicationFactor: 1'
                return b''
            if tool.endswith('kafka-configs.sh'):
                return b'auto.create.topics.enable=false' if 'brokers' in args else b'retention.ms=86400000'
            topic = args[args.index('--topic') + 1]
            if topic.startswith('__debezium-heartbeat.'):
                return (topic + ':0:1\n').encode()
            return ''.join(topic + ':' + str(n) + ':0\n' for n in range(3)).encode()
        def fingerprint(_runtime, _release, _environment, output, *_):
            self.events.append(('mysql-full',)); output.write_bytes(b'changed' if changed_fingerprint else prepared_bytes); return {}
        def verify_runtime(path, image, commit, sealed):
            self.events.append(('runtime-proof',))
            self.assertEqual(manifest['application']['image'], image)
            self.assertEqual(manifest['application']['mainCommit'], commit)
            self.assertEqual(manifest['application']['appJarSha256'], sealed)
            if invalid_runtime: raise ValueError('Runtime binding byte inventory differs')
            return runtime_projection(manifest)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'restore-config.json').write_text('{}')
            manifest['preparation']['restoreConfigSha256'] = service.sha(root / 'restore-config.json')
            config = {'rds': manifest['rds'] | {'endpoint': 'reviewed.rds.amazonaws.com'}, 'release': str(root/'release'), 'redisImage': 'redis@sha256:'+'d'*64}
            context = {'runId': manifest['runId'], 'rds': manifest['rds'], 'lease': {}, 'manifestSha256': '0'*64,
                       'debeziumSecretArn': 'arn:selected-cdc', 'databaseBootstrap': 'dump', 'snapshotProvenanceSha256': None}
            with contextlib.ExitStack() as stack:
                pairs = [(restore, 'Aws', Aws), (restore, 'Lease', lambda *_: DB().guard),
                    (restore, 'configuration', lambda *_: config), (restore, 'validate_inputs', lambda *_: {
                        'appJarSha256': manifest['application']['appJarSha256'],
                        'objects': {'migration-files.json': {'sha256': manifest['application']['migrationFilesSha256']}}}),
                    (restore, 'live_rds', lambda *_: {}), (restore, 'connection', lambda *_: (DB(), {})),
                    (restore, 'exclusive_database', lambda *_: contextlib.nullcontext()), (restore, 'fingerprint', fingerprint),
                    (restore, 'command', command), (runtime, 'qualify_runtime', lambda *_args, **_kw: {}),
                    (runtime, 'activated_runtime', lambda *_: contextlib.nullcontext()), (search, 'Elasticsearch', ES),
                    (service.app_runtime, 'validate_runtime_binding', verify_runtime),
                    (service, 'fetch', lambda _aws, ref, *_: selected[ref['key']]), (service, 'request', http)]
                for obj, name, value in pairs: stack.enter_context(patch.object(obj, name, value))
                result = service.bootstrap(manifest, context, root, root/'result')
                self.assertFalse((root/'result/.connection').exists())
                self.assertEqual('airbob-' + manifest['runId'] + '-app', config['writerAsgNames'][0])
                return result

    def test_complete_dependency_proof_precedes_application_and_has_no_credentials(self):
        result = self.execute()
        self.assertEqual(service.READY, result['state'])
        self.assertTrue(result['heartbeatObserved']); self.assertFalse(result['applicationStarted'])
        self.assertFalse(result['deploymentReady']); self.assertFalse(result['redisReset'])
        self.assertEqual(24, result['binlogRetentionHours'])
        encoded = json.dumps(result)
        self.assertNotIn('database.password', encoded)
        kinds = [item[0] for item in self.events]
        self.assertLess(kinds.index('mysql-full'), kinds.index('sql-write'))
        self.assertLess(kinds.index('search-full'), kinds.index('sql-write'))
        self.assertLess(kinds.index('runtime-proof'), kinds.index('sql-write'))
        self.assertEqual(runtime_projection(fixture()), result['appRuntime'])
        set_at = self.events.index(('sql-write', 'CALL'))
        read_at = self.events.index(('sql-read', 'CALL mysql.rds_show_configuration()'))
        create_at = self.events.index(('sql-write', 'CREATE'))
        self.assertLess(set_at, read_at); self.assertLess(read_at, create_at)
        self.assertLess(create_at, self.events.index(('connect', 'POST', '/connectors')))

    def test_retention_readback_must_be_one_exact_24_hour_value_before_cdc(self):
        row = {'name': 'binlog retention hours', 'value': 24}
        values = [[], [{'name': 'different setting', 'value': 24}], [row, row]] + [
            [{'name': 'binlog retention hours', 'value': value}] for value in (None, 0, 12, 48, '24', 24.0, True)]
        for rows in values:
            with self.subTest(rows=rows):
                with self.assertRaisesRegex(ValueError, 'RDS binlog retention must be exactly 24 hours'):
                    self.execute(retention_rows=rows)
                self.assert_no_new_cdc()

    def test_retention_set_failure_prevents_readback_or_cdc_creation(self):
        with self.assertRaisesRegex(ValueError, '^RDS_RETENTION_SET_FAILED$'):
            self.execute(retention_error=True)
        self.assertNotIn(('sql-read', 'CALL mysql.rds_show_configuration()'), self.events)
        self.assert_no_new_cdc()

    def assert_no_new_cdc(self):
        self.assertFalse(any(e[:2] in (('sql-write', 'CREATE'), ('sql-write', 'GRANT'), ('connect', 'POST'))
            or (e[0] == 'aws' and e[1][:2] == ('secretsmanager', 'put-secret-value')) for e in self.events))

    def test_invalid_runtime_proof_prevents_database_or_cdc_writes(self):
        with self.assertRaisesRegex(ValueError, 'Runtime binding byte inventory differs'): self.execute(invalid_runtime=True)
        self.assertFalse(any(item[0] == 'sql-write' or (item[0] == 'connect' and item[1] != 'GET') for item in self.events))

    def test_changed_full_prepared_database_prevents_any_cdc_or_credentials(self):
        with self.assertRaisesRegex(ValueError, 'Prepared full rows/DDL changed'): self.execute(changed_fingerprint=True)
        self.assertFalse(any(item[0] == 'sql-write' or (item[0] == 'connect' and item[1] != 'GET') for item in self.events))

    def test_existing_connector_prevents_cdc_reuse_and_credential_creation(self):
        with self.assertRaisesRegex(ValueError, 'Fresh B bootstrap'): self.execute(reused_cdc=True)
        self.assertFalse(any(item[0] == 'sql-write' or item[:2] == ('connect', 'POST') for item in self.events))


if __name__ == '__main__': unittest.main()
