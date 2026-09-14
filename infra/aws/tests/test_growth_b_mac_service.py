"""Mac source service contracts: real receipt producers, mocked network/SQL only."""
import contextlib
import copy
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import test_growth_b_mac_downsize as downsize_tests
from test_growth_b_service_contract import fixture, runtime_projection, SCRIPTS
import growth_b_mac_service as mac

service, restore, search, downsize = mac.service, mac.restore, mac.search, mac.downsize


def mac_fixture(test):
    producer = downsize_tests.MacDownsize(); producer.setUp(); test.addCleanup(producer.doCleanups)
    manifest = fixture(); old_run = manifest['runId']
    producer.op.update(bundleCommit=manifest['application']['mainCommit'],
        appImageReference=manifest['application']['image'], imageDigest=manifest['application']['image'].split('@')[1])
    producer.arguments.update(operator=producer.op, operator_sha256=hashlib.sha256(downsize.encoded(producer.op)).hexdigest())
    producer.request = downsize.prepare_inputs(**producer.arguments)['request']
    transition = downsize.complete_transition(producer.request, producer.approve_api(), producer.state_after,
        producer.rds_after, producer.postcheck['database'], now=1800000100)
    manifest = json.loads(json.dumps(manifest).replace(old_run, producer.op['runId']))
    manifest['rds'] = {k: transition['rds'][k] for k in ('identifier', 'resourceId', 'serverUuid')}
    manifest['cdc'] = service.cdc_identity(manifest['runId'], manifest['rds']['serverUuid'])
    manifest['search']['restoreReceipt'] = None
    prep = manifest['preparation']
    prep = {'sourceMode': mac.MODE, 'rdsCaBundle': prep['rdsCaBundle']}
    objects = {}
    def bind(key, raw):
        objects[key] = raw
        return {'key': key, 'versionId': 'version-' + str(len(objects)),
            'sha256': hashlib.sha256(raw).hexdigest(), 'bytes': len(raw)}
    prefix = 'data-bootstrap/' + manifest['runId'] + '/'
    prep['receipt'] = bind(prefix + manifest['datasetId'] + '-mac-rds-downsize.json', downsize.encoded(transition))
    prep['sqlImportReceipt'] = bind(prefix + 'mac-sql-completed.json', producer.sql_raw)
    prep['postcheckReceipt'] = bind(prefix + 'mac-postcheck.json', downsize.encoded(producer.postcheck))
    ca_bytes = producer.producer.ca.read_bytes()
    prep['rdsCaBundle'] = bind('datasets/' + manifest['datasetId'] + '-aws-preparation/files/' +
        hashlib.sha256(ca_bytes).hexdigest() + '-rds-ca.pem', ca_bytes)
    manifest['preparation'] = prep
    manifest['toolSources'] = {name: service.sha(SCRIPTS / name) for name in service.tools_for(manifest)}
    manifest['appRuntimeBinding'] = bind(manifest['appRuntimeBinding']['key'],
        downsize.encoded({'runtime': runtime_projection(manifest)}))
    transport = {'schemaVersion': 1, 'kind': 'global-growth-b-search-transport',
        'datasetId': manifest['datasetId'], 'snapshotRelease': manifest['search']['snapshotRelease'],
        'bucket': service.BUCKET, 'region': service.REGION, 'snapshotUuid': 'native-sealed-uuid',
        'repository': {'type': 's3', 'bucket': service.BUCKET, 'basePath':
            'datasets/' + manifest['datasetId'] + '-search/' + manifest['search']['snapshotRelease'] + '/native'},
        'source': {'appJarSha256': manifest['application']['appJarSha256']},
        'sql': {'dump': {'sha256': producer.sql['binding']['dumpSha256']}}}
    manifest['search']['transport'] = bind(manifest['search']['transport']['key'], downsize.encoded(transport))
    context = {'runId': manifest['runId'], 'resourceFence': producer.op['fencingToken'], 'expiresAt': producer.op['expiresAt'],
        'databaseBootstrap': 'dump', 'rdsInstanceClass': downsize.SMALL, 'lease': {}, 'manifestSha256': '0' * 64,
        'rds': manifest['rds'] | {'endpoint': transition['rds']['endpoint'],
            'masterSecretArn': producer.producer.rds['MasterUserSecret']['SecretArn']},
        'redisImage': producer.op['infraImageReferences']['REDIS_IMAGE'], 'debeziumSecretArn': 'arn:exact-cdc-secret'}
    return producer, manifest, transition, transport, context, objects


class FakeES(search.Elasticsearch):
    """Actual restore_index/alias validators consume these bounded HTTP responses."""
    def __init__(self, manifest, transport):
        self.config = {}; self.manifest = manifest; self.transport = transport
        self.calls = []; self.repository = None; self.target = None; self.active_alias = None
        self.count = manifest['search']['documentFingerprint']['documents']; self.sample_id = 16102
        self.blocked = True; self.accepted = True; self.snapshot_uuid = transport['snapshotUuid']
        self.snapshot_state = 'SUCCESS'; self.repository_removed = True

    def optional(self, path):
        if path == '/_alias/accommodations':
            return None if self.active_alias is None else {self.active_alias: {'aliases': {'accommodations': {'is_write_index': True}}}}
        if path.startswith('/_snapshot/'):
            return None if self.repository is None else {self.repository: {}}
        return None if self.target is None else {self.target: {}}

    def api(self, method, path, value=None):
        self.calls.append((method, path, copy.deepcopy(value)))
        if path == '/': return {'version': {'number': '8.18.8'}, 'cluster_uuid': 'cluster-exact'}
        if path.startswith('/_cat/indices'): return [] if self.target is None else [{'index': self.target}]
        if path.endswith('/_all'):
            return {'snapshots': [{'snapshot': 'sealed-native', 'uuid': self.snapshot_uuid, 'indices': ['accommodations-vsealed'],
                'state': self.snapshot_state, 'failures': [], 'shards': {'failed': 0, 'successful': 1, 'total': 1},
                'include_global_state': False, 'feature_states': [],
                'version': search.SNAPSHOT_METADATA_VERSION, 'version_id': search.SNAPSHOT_METADATA_VERSION_ID}]}
        if path.endswith('/_restore'):
            self.target = value['rename_replacement']; return {'accepted': self.accepted}
        if path.startswith('/_snapshot/'):
            if method == 'PUT': self.repository = path.split('/')[-1]; return {'acknowledged': True}
            if method == 'DELETE': return {'acknowledged': self.repository_removed}
            return {self.repository: {'type': 's3', 'settings': {'bucket': service.BUCKET,
                'base_path': self.transport['repository']['basePath'], 'readonly': 'true'}}}
        if path.startswith('/_cluster/health/'):
            return {'cluster_name': 'test', 'timed_out': False, 'status': 'green', 'number_of_nodes': 1,
                'number_of_data_nodes': 1, 'active_primary_shards': 1, 'active_shards': 1,
                'relocating_shards': 0, 'initializing_shards': 0, 'unassigned_shards': 0}
        if path.endswith('/_settings'):
            if method == 'PUT': self.blocked = value['index.blocks.write']; return {'acknowledged': True}
            return {self.target: {'settings': {'index': {'blocks': {'write': self.blocked}}}}}
        if path.endswith('/_alias'): return {self.target: {'aliases': {}}}
        if path.endswith('/_count'): return {'count': self.count}
        if path.endswith('/_search'):
            return {'hits': {'total': {'value': 1, 'relation': 'eq'}, 'hits': [{'_source': {'accommodationId': self.sample_id}}]}}
        if path == '/_aliases': self.active_alias = value['actions'][0]['add']['index']; return {'acknowledged': True}
        if path == '/_alias/accommodations': return self.optional(path)
        raise AssertionError((method, path))


class MacService(unittest.TestCase):
    def setUp(self):
        self.producer, self.manifest, self.transition, self.transport, self.context, self.objects = mac_fixture(self)
        self.root = self.producer.root

    def validate(self, **kwargs):
        return mac.validate_source(kwargs.get('manifest', self.manifest), kwargs.get('sql', self.producer.sql),
            kwargs.get('postcheck', self.producer.postcheck), kwargs.get('transition', self.transition),
            original=kwargs.get('original', self.producer.op), original_sha=self.producer.arguments['operator_sha256'])

    def native(self, es=None, output=None):
        output = output or self.root / 'native'; output.mkdir(exist_ok=True)
        self.es = es or FakeES(self.manifest, self.transport)
        return mac.native_restore(self.manifest, self.transport, self.producer.sql['binding']['dumpSha256'],
            self.es, lambda **_: None, output, 30)

    def test_real_mac_import_postcheck_and_api_downsize_admit_without_full_proof(self):
        self.assertEqual(self.transition, self.validate())
        value = service.validate_manifest(self.manifest, self.manifest['datasetId'], self.manifest['runId'],
            self.manifest['serviceRelease'], self.manifest['toolSources'])
        self.assertEqual(11, len(service.tools_for(value)))
        self.assertNotIn('preparedFingerprintSha256', value['preparation'])
        self.assertFalse(self.transition['fullDatasetValidated'])

    def test_exact_eleven_file_host_package_imports_without_repository_or_external_io(self):
        with tempfile.TemporaryDirectory(prefix='airbob-mac-service-tools-') as directory:
            tools = Path(directory)
            sources = self.manifest['toolSources']
            for name, expected in sources.items():
                raw = (SCRIPTS / name).read_bytes()
                self.assertEqual(expected, hashlib.sha256(raw).hexdigest())
                (tools / name).write_bytes(raw)
            program = '''import hashlib,importlib,json,pathlib,sys
root=pathlib.Path(sys.argv[1]); sys.path.insert(0,str(root))
def offline(event,args):
    if event.startswith('socket.') or event in {'subprocess.Popen','os.system','os.fork','os.posix_spawn'}:
        raise AssertionError('unexpected import side effect: '+event)
sys.addaudithook(offline)
service=importlib.import_module('growth_b_service')
names=service.tools_for({'preparation':{'sourceMode':'mac-sql-postcheck'}})
assert len(names)==11 and {p.name for p in root.iterdir()}==set(names)
for name in names:
    compile((root/name).read_bytes(),str(root/name),'exec')
    importlib.import_module(name[:-3])
print(json.dumps({'pythonVersion':sys.version.split()[0],'count':len(names),'state':'FULL_PACKAGE_IMPORTED'}))
'''
            observed = subprocess.run([sys.executable, '-B', '-I', '-c', program, str(tools)],
                cwd=directory, capture_output=True, text=True, timeout=10,
                env={'PATH': os.environ['PATH'], 'AWS_EC2_METADATA_DISABLED': 'true'})
            self.assertEqual(0, observed.returncode, observed.stderr)
            self.assertEqual({'pythonVersion': sys.version.split()[0], 'count': 11, 'state': 'FULL_PACKAGE_IMPORTED'},
                json.loads(observed.stdout))
            self.assertEqual(set(sources), {p.name for p in tools.iterdir()})

    def test_original_operator_immutable_fields_cannot_be_relabelled(self):
        for key, value in [('fencingToken', 77), ('expiresAt', '1999999998'), ('bundleCommit', 'f' * 40),
                ('appImageReference', 'foreign'), ('globalBImportFromMac', False), ('cacheEnabled', True)]:
            op = copy.deepcopy(self.producer.op); op[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(original=op)

    def test_target_dump_or_current_receipt_identity_drift_is_rejected(self):
        for key, value in [('serverUuid', 'ffffffff-1234-4123-8123-123456789abc'), ('resourceId', 'db-FOREIGN'),
                ('identifier', 'airbob-lab-foreign')]:
            manifest = copy.deepcopy(self.manifest); manifest['rds'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(manifest=manifest)
        for name in ('sqlImportReceipt', 'postcheckReceipt'):
            manifest = copy.deepcopy(self.manifest); manifest['preparation'][name]['sha256'] = 'f' * 64
            with self.subTest(name=name), self.assertRaises(ValueError): self.validate(manifest=manifest)

    def test_partial_failed_or_full_claim_cannot_admit(self):
        for key, value in [('state', 'SQL_IMPORT_RUNNING'), ('fullDatasetValidated', True), ('automaticReplayAllowed', True)]:
            sql = copy.deepcopy(self.producer.sql); sql[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(sql=sql)
        for key, value in [('compressedBytesRead', 1), ('mysqlExitCode', False), ('gzipExitCode', 1)]:
            sql = copy.deepcopy(self.producer.sql); sql['stream'][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(sql=sql)
        for key, value in [('failedMigrations', 1), ('flywayVersion', 27), ('sqlReplayed', True), ('fullDatasetValidated', True),
                ('representativePrimaryKeysObserved', False), ('sqlImportReceiptSha256', 'f' * 64)]:
            post = copy.deepcopy(self.producer.postcheck); post[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(postcheck=post)

    def test_cross_run_refs_and_linux_preparation_impersonation_rejected(self):
        for key, value in [('receipt', self.manifest['preparation']['sqlImportReceipt']),
                ('restoreReceiptSha256', 'a' * 64), ('preparedFingerprintSha256', 'a' * 64)]:
            prep = copy.deepcopy(self.manifest['preparation']); prep[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                mac.validate_preparation_fields(prep, self.manifest['datasetId'], self.manifest['runId'])
        prep = copy.deepcopy(self.manifest['preparation']); prep['postcheckReceipt']['key'] = 'data-bootstrap/lab-foreign/post.json'
        with self.assertRaises(ValueError): mac.validate_preparation_fields(prep, self.manifest['datasetId'], self.manifest['runId'])
        value = copy.deepcopy(self.manifest); value['search']['restoreReceipt'] = value['preparation']['receipt']
        with self.assertRaises(ValueError): service.validate_manifest(value, value['datasetId'], value['runId'], value['serviceRelease'])

    def test_real_low_level_native_restore_quarantines_then_queries_then_activates(self):
        result = self.native()
        requests = [call for call in self.es.calls if call[1].endswith('/_restore')]
        self.assertEqual(1, len(requests))
        body = requests[0][2]
        self.assertEqual({'index.number_of_replicas': 0, 'index.blocks.write': True}, body['index_settings'])
        self.assertFalse(body['include_aliases']); self.assertFalse(body['include_global_state']); self.assertFalse(body['partial'])
        paths = [call[1] for call in self.es.calls]
        self.assertLess(paths.index('/' + result['restoredIndex'] + '/_search'), paths.index('/_aliases'))
        self.assertLess(paths.index('/_aliases'), paths.index('/accommodations/_search'))
        self.assertEqual(657358, result['documents']); self.assertTrue(result['repositoryRemoved'])
        self.assertFalse(result['allDocumentSourceFieldsEqual']); self.assertFalse(result['fullDatasetValidated'])
        self.assertFalse(result['publicHttpVerified'])
        self.assertEqual(result, service.read(self.root / 'native/native-search.json'))

    def test_unknown_restore_result_is_not_replayed_or_successful(self):
        es = FakeES(self.manifest, self.transport); es.accepted = False
        with self.assertRaisesRegex(ValueError, 'not accepted'): self.native(es)
        self.assertTrue((self.root / 'native/search-intent.json').exists())
        self.assertFalse((self.root / 'native/native-search.json').exists())
        with self.assertRaisesRegex(ValueError, 'Fresh application search'): self.native(es)
        self.assertEqual(1, len([c for c in es.calls if c[1].endswith('/_restore')]))

    def test_native_recovery_count_sample_uuid_and_cleanup_fail_closed(self):
        for key, value in [('count', 657357), ('sample_id', 999), ('snapshot_uuid', 'foreign'),
                ('snapshot_state', 'PARTIAL'), ('repository_removed', False)]:
            es = FakeES(self.manifest, self.transport); setattr(es, key, value)
            output = self.root / key
            with self.subTest(key=key), self.assertRaises(ValueError): self.native(es, output)
            self.assertFalse((output / 'native-search.json').exists())
            if key != 'repository_removed': self.assertIsNone(es.active_alias)

    def test_existing_alias_or_transport_from_other_dump_cannot_restore(self):
        es = FakeES(self.manifest, self.transport); es.active_alias = 'accommodations-vforeign'
        with self.assertRaises(ValueError): self.native(es)
        self.assertFalse(any(c[0] != 'GET' for c in es.calls))
        for group, key, value in [('sql', 'dump', {'sha256': 'f' * 64}),
                ('repository', 'basePath', 'datasets/foreign/native'), ('source', 'appJarSha256', 'f' * 64)]:
            prior = copy.deepcopy(self.transport); self.transport[group][key] = value
            try:
                with self.subTest(group=group), self.assertRaises(ValueError): self.native(output=self.root / group)
            finally: self.transport = prior

    def test_mariadb_tls_credentials_are_private_and_always_removed(self):
        directory = self.root / 'connection'; directory.mkdir(mode=0o700)
        class Aws:
            def call(inner, *args): return {'SecretString': json.dumps({'username': 'admin', 'password': 'TEST_SECRET'})}
        def database(defaults, timeout, guard):
            text = defaults.read_text()
            self.assertEqual(0o600, defaults.stat().st_mode & 0o777)
            self.assertIn('ssl-ca=', text); self.assertIn('ssl-verify-server-cert="1"', text)
            self.assertNotIn('ssl-mode', text)
            raise RuntimeError('simulated client startup failure')
        with patch.object(restore, 'Database', database), self.assertRaises(RuntimeError):
            with mac.connection(Aws(), self.context['rds'], self.producer.producer.ca, directory, lambda: None): pass
        self.assertEqual([], list(directory.iterdir()))

    def test_source_fetch_checks_exact_version_bytes_and_hash_before_admission(self):
        ref = self.manifest['preparation']['sqlImportReceipt']; raw = self.objects[ref['key']]
        class Aws:
            def call(inner, *args): Path(args[-1]).write_bytes(raw); return {'VersionId': 'wrong-version'}
        with self.assertRaises(ValueError): service.fetch(Aws(), ref, self.root / 'fetched.json', service.EVIDENCE)
        self.assertEqual(0o600, (self.root / 'fetched.json').stat().st_mode & 0o777)

    def execute(self, *, clients=0, wrong_class=False, wrong_uuid=False, bad_search=False, changed_version=False):
        self.events = []; manifest, context = self.manifest, self.context
        refs = {v['key']: v for v in manifest['preparation'].values() if isinstance(v, dict)}
        refs.update({manifest[k]['key']: manifest[k] for k in ('appRuntimeBinding',)})
        refs[manifest['search']['transport']['key']] = manifest['search']['transport']
        live = copy.deepcopy(self.producer.rds_after)
        live['DBInstances'][0]['BackupRetentionPeriod'] = 1
        if wrong_class: live['DBInstances'][0]['DBInstanceClass'] = downsize.LARGE
        es = FakeES(manifest, self.transport)
        if bad_search: es.count -= 1
        desired = {}
        class Aws:
            def call(inner, *args):
                self.events.append(('aws', args[:2]))
                if args[:2] == ('s3api', 'get-object'):
                    key = args[args.index('--key') + 1]; ref = refs[key]
                    self.assertEqual(ref['versionId'], args[args.index('--version-id') + 1])
                    Path(args[-1]).write_bytes(self.objects[key])
                    return {'VersionId': 'foreign' if changed_version else ref['versionId']}
                if args[:2] == ('sts', 'get-caller-identity'): return {'Account': service.ACCOUNT}
                if args[:2] == ('rds', 'describe-db-instances'): return live
                if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'):
                    return {'AutoScalingGroups': [{'AutoScalingGroupName': 'airbob-' + manifest['runId'] + '-app',
                        'MinSize': 0, 'DesiredCapacity': 0, 'Instances': []}]}
                if args[:2] == ('secretsmanager', 'get-secret-value'):
                    return {'SecretString': json.dumps({'username': 'admin', 'password': 'TEST_PRIVATE_VALUE'})}
                if args[:2] == ('secretsmanager', 'put-secret-value'): return {}
                raise AssertionError('Unexpected AWS operation: ' + str(args[:2]))
        class DB:
            def __init__(inner, defaults, timeout, guard): inner.timeout, inner.guard = timeout, guard
            def scalar(inner, sql, *_):
                self.events.append(('sql-read', sql))
                if 'processlist' in sql: return clients
                if '@@server_uuid' in sql: return 'foreign' if wrong_uuid else manifest['rds']['serverUuid']
                if 'MAX(CAST(version' in sql: return 28
                if 'id=16102' in sql: return 1
                return 0
            def rows(inner, sql, *_):
                self.assertEqual("SHOW SESSION STATUS LIKE 'Ssl_cipher'", sql)
                return [{'Variable_name': 'Ssl_cipher', 'Value': 'TLS_AES_256_GCM_SHA384'}]
            literal = staticmethod(restore.Database.literal)
            def execute(inner, sql, *_):
                # No SQL import, data DML, reset, or DELETE/DROP is permitted.
                self.assertIn(sql.split()[0], ('CREATE', 'GRANT'))
                account = "'" + manifest['cdc']['username'] + "'@'%'"
                if sql.startswith('CREATE'):
                    self.assertRegex(sql, '^CREATE USER ' + account + " IDENTIFIED BY '[A-Za-z0-9_-]{48}' REQUIRE SSL;$")
                else:
                    self.assertEqual('GRANT SELECT, RELOAD, LOCK TABLES, SHOW DATABASES, '
                        'REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO ' + account + ';', sql)
                self.assertNotIn('CONVERT(', sql)
                self.events.append(('sql-write', sql.split()[0]))
        def http(method, path, value=None):
            self.events.append(('connect', method, path))
            if path == '/connector-plugins': return [{'class': 'io.debezium.connector.mysql.MySqlConnector',
                'type': 'source', 'version': service.DEBEZIUM_PLUGIN_VERSION}]
            if path == '/': return {'version': '3.7.0'}
            if method == 'GET' and path == '/connectors': return []
            if method == 'POST': desired.update(value['config']); return {}
            if path.endswith('/status'): return {'connector': {'state': 'RUNNING'}, 'tasks': [{'state': 'RUNNING'}]}
            return desired
        def command(args, **kwargs):
            self.events.append(('command', args[0]))
            self.assertEqual('docker', args[0])
            if args[:2] == ['docker', 'ps']: return b'0123456789ab\n'
            if args[:2] == ['docker', 'inspect']: return manifest['debezium']['image'].encode()
            if args[:3] == ['docker', 'image', 'inspect']: return manifest['debezium']['buildCommit'].encode()
            if args[:2] == ['docker', 'exec']: return (manifest['debezium']['pluginJarSha256'] + '  connector.jar').encode()
            if 'redis-cli' in args:
                self.assertNotIn('FLUSHDB', args)
                return ('run_id:' + ('1' if '6379' in args else '2') * 40 + '\r\n').encode()
            tool = next(arg for arg in args if arg.startswith('/opt/kafka/bin/'))
            if tool.endswith('kafka-topics.sh'):
                return b'PartitionCount: 3 ReplicationFactor: 1' if '--describe' in args else b''
            if tool.endswith('kafka-configs.sh'):
                return b'auto.create.topics.enable=false' if 'brokers' in args else b'retention.ms=86400000'
            topic = args[args.index('--topic') + 1]
            if topic.startswith('__debezium-heartbeat.'): return (topic + ':0:1\n').encode()
            return ''.join(topic + ':' + str(n) + ':0\n' for n in range(3)).encode()
        def api(_self, *args, **kwargs):
            self.events.append(('es', args[0], args[1])); return es.api(*args, **kwargs)
        with contextlib.ExitStack() as stack:
            for obj, name, value in [(restore, 'Aws', Aws), (restore, 'Lease', lambda *_: lambda **__: None),
                    (restore, 'Database', DB), (restore, 'exclusive_database', lambda *_: contextlib.nullcontext()),
                    (restore, 'command', command), (service, 'request', http),
                    (service.app_runtime, 'validate_runtime_binding', lambda *_: runtime_projection(manifest)),
                    (search.Elasticsearch, 'api', api), (search.Elasticsearch, 'optional', lambda _self, path: es.optional(path))]:
                stack.enter_context(patch.object(obj, name, value))
            for name in ('stream_restore', 'fingerprint', 'prepare_service', 'validate_inputs'):
                stack.enter_context(patch.object(restore, name, side_effect=AssertionError('Mac must not call ' + name)))
            result = service.bootstrap(manifest, context, self.root, self.root / 'bootstrap')
        self.assertFalse((self.root / 'bootstrap/.connection').exists())
        self.assertNotIn('TEST_PRIVATE_VALUE', json.dumps(result))
        return result

    def test_actual_mac_bootstrap_and_common_dependency_flow_no_import_or_full_scan(self):
        result = self.execute()
        self.assertEqual(service.READY, result['state'])
        self.assertEqual(list(service.TOPICS), result['topics'])
        self.assertTrue(result['heartbeatObserved']); self.assertFalse(result['redisReset'])
        self.assertFalse(result['fullDatasetValidated']); self.assertFalse(result['sqlReplayed'])
        admitted = service.validate_readiness(result, self.manifest, self.context['manifestSha256'])
        self.assertTrue(admitted['applicationAdmitted']); self.assertFalse(admitted['deploymentReady'])
        kinds = [event[0] for event in self.events]
        alias = self.events.index(('es', 'POST', '/_aliases'))
        self.assertLess(alias, kinds.index('sql-write'))
        self.assertEqual([('sql-write', 'CREATE'), ('sql-write', 'GRANT')], [e for e in self.events if e[0] == 'sql-write'])
        with self.assertRaisesRegex(ValueError, 'previously attempted'):
            service.bootstrap(self.manifest, self.context, self.root, self.root / 'bootstrap')

    def test_failed_class_uuid_clients_or_source_cannot_mutate_es_or_cdc(self):
        for option in ('wrong_class', 'wrong_uuid', 'clients', 'changed_version'):
            with self.subTest(option=option):
                with self.assertRaises(ValueError): self.execute(**{option: 1})
                self.assertFalse(any(e[0] == 'sql-write' or e[:2] in [('es', 'POST'), ('es', 'PUT'), ('connect', 'POST')] for e in self.events))
                # Only test-owned evidence is removed to let each independent case use this fixture.
                import shutil
                shutil.rmtree(self.root / 'bootstrap')

    def test_native_mismatch_prevents_cdc_user_and_connector(self):
        with self.assertRaisesRegex(ValueError, 'document count'): self.execute(bad_search=True)
        self.assertFalse(any(e[0] == 'sql-write' or e[:2] == ('connect', 'POST') for e in self.events))
        self.assertFalse((self.root / 'bootstrap/.connection').exists())

    def test_readiness_cannot_promote_light_checks_to_full_or_ignore_search_failure(self):
        result = self.execute()
        for field, value in [('fullDatasetValidated', True), ('sqlReplayed', True), ('cdcRunning', False),
                ('preparedFingerprintSha256', 'a' * 64), ('searchFingerprint', {}), ('searchDatasetRestored', False)]:
            changed = copy.deepcopy(result); changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): service.validate_readiness(changed, self.manifest, '0' * 64)

    def test_actual_terraform_mac_admission_and_readiness(self):
        from test_growth_b_service_infrastructure import evaluate
        result = self.execute()
        def run(preparation=None, proof=None, mode='dump'):
            return evaluate(copy.deepcopy(self.manifest), copy.deepcopy(proof or result), database_bootstrap=mode,
                preparation_document=copy.deepcopy(preparation or self.transition), retained_operator=self.producer.op)
        evaluated = run()
        self.assertTrue(evaluated['valid']); self.assertTrue(evaluated['ready'])
        self.assertFalse(run(mode='snapshot')['valid'])
        for field, value in [('fullDatasetValidated', True), ('sqlReplayed', True), ('toClass', downsize.LARGE)]:
            changed = copy.deepcopy(self.transition); changed[field] = value
            with self.subTest(field=field): self.assertFalse(run(preparation=changed)['valid'])
        changed = copy.deepcopy(self.transition); changed['operator']['fencingToken'] += 1
        self.assertFalse(run(preparation=changed)['valid'])
        changed = copy.deepcopy(result); changed['nativeSearch']['singleWriteAlias'] = False
        self.assertFalse(run(proof=changed)['ready'])
        for field, value in [('documents', 1), ('singleWriteAlias', False), ('nativeRestoreSucceeded', False),
                ('allDocumentSourceFieldsEqual', True), ('fullDatasetValidated', True)]:
            changed = copy.deepcopy(result); changed['nativeSearch'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): service.validate_readiness(changed, self.manifest, '0' * 64)

    def operator_admission(self, *, changed_reference=False, corrupt_object=False):
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        start = source.index('  if jq -e \'.preparation.sourceMode == "mac-sql-postcheck"\'', source.index('continue_global_b_services()'))
        end = source.index('  validate_workflow_deadline_budget;', start)
        admission = source[start:end]
        self.assertLess(end, source.index('  start_mutation_guard', end))
        self.assertLess(end, source.index('  apply_lab', end))
        def function(name):
            start = source.index(name + '() {')
            return source[start:source.index('\n}\n', start) + 3]
        directory = self.root / 'operator'; directory.mkdir(exist_ok=True)
        original = directory / 'operator.json'; original.write_bytes(downsize.encoded(self.producer.op))
        original_raw = original.read_bytes()
        (directory / 'dataset-manifest.json').write_bytes(downsize.encoded(self.manifest))
        object_paths = {}
        for n, (key, raw) in enumerate(self.objects.items()):
            path = directory / ('object-' + str(n)); path.write_bytes(raw); object_paths[key] = str(path)
        refs = {v['key']: v for v in self.manifest['preparation'].values() if isinstance(v, dict)}
        (directory / 'objects.json').write_text(json.dumps({'files': object_paths, 'refs': refs}))
        stub = directory / 'aws.py'
        stub.write_text('''import json,sys,pathlib
args=sys.argv[1:]; data=json.loads(pathlib.Path(__file__).with_name('objects.json').read_text())
assert args[:2] in (['s3api','head-object'],['s3api','get-object'])
key=args[args.index('--key')+1]; ref=data['refs'][key]
assert args[args.index('--version-id')+1]==ref['versionId']
raw=pathlib.Path(data['files'][key]).read_bytes()
if args[1]=='get-object':
    output=args[args.index('--version-id')+2]; pathlib.Path(output).write_bytes(raw)
print(json.dumps({'VersionId':ref['versionId'],'ContentLength':len(raw)}))
''')
        if corrupt_object:
            key = self.manifest['preparation']['sqlImportReceipt']['key']
            path = Path(object_paths[key]); raw = path.read_bytes(); path.write_bytes(raw.replace(b'SQL_IMPORT_COMPLETED', b'SQL_IMPORT_FAIL_TEST'))
        bad_ref = copy.deepcopy(self.manifest['preparation']['receipt']); bad_ref['sha256'] = 'f' * 64
        header = 'set -euo pipefail\n' + '\n'.join(k + '=' + shlex.quote(v) for k, v in {
            'temp_dir': str(directory), 'original': str(original), 'script_dir': str(SCRIPTS),
            'evidence_bucket': service.EVIDENCE, 'AWS_REGION': service.REGION,
            'B_MAC_DOWNSIZE_RECEIPT_JSON': json.dumps(bad_ref) if changed_reference else ''}.items()) + '\n'
        header += 'fail() { printf "%s\\n" "$*" >&2; exit 2; }\nsha256_file() { shasum -a 256 "$1" | awk \'{print $1}\'; }\n'
        header += 'aws() { python3 ' + shlex.quote(str(stub)) + ' "$@"; }\n'
        script = header + function('fetch_b_class_evidence') + function('load_retained_rds_class')
        script += '\nadmit() {\n' + admission + '\nprintf "%s\\n" "$rds_instance_class"\n}\nadmit\n'
        result = subprocess.run(['bash'], input=script, text=True, capture_output=True, timeout=10,
            env={'PATH': os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'})
        self.assertEqual(original_raw, original.read_bytes())
        return result

    def test_actual_operator_infers_small_from_exact_manifest_and_preserves_original(self):
        result = self.operator_admission()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(downsize.SMALL, result.stdout.strip())

    def test_operator_ref_disagreement_or_changed_s3_bytes_never_admits(self):
        self.assertNotEqual(0, self.operator_admission(changed_reference=True).returncode)
        self.assertNotEqual(0, self.operator_admission(corrupt_object=True).returncode)

    def test_mac_app_can_pass_after_fifteen_minutes_without_removing_failed_status_gate(self):
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        start = source.index('wait_for_application() {'); end = source.index('\n}\n', start) + 3
        body = source[start:end]
        def run(seconds, failed=False):
            clock = self.root / 'app-clock'; clock.write_text('0')
            script = '''set -euo pipefail
INSTANCE_REFRESH_TIMEOUT_SECONDS=900
asg_name=exact-asg; target_group_arn=exact-target; AWS_REGION=ap-northeast-2
fail() { exit 2; }
assert_lease() { :; }
sleep() { :; }
run_supervised_mutation() { :; }
date() { n=$(cat "$CLOCK"); printf '%s' "$((n+600))" > "$CLOCK"; printf '%s' "$n"; }
aws() {
  case "$1 $2" in
    'autoscaling describe-instance-refreshes') printf '%s' "$STATUS" ;;
    'autoscaling describe-auto-scaling-groups') printf '1' ;;
    'elbv2 describe-target-health')
      n=$(cat "$CLOCK"); ready=0; (( n < 1800 )) || ready=1
      if [[ "$*" == *'State!=`healthy`'* ]]; then printf '%s' "$((1-ready))"; else printf '%s' "$ready"; fi ;;
    *) exit 99 ;;
  esac
}
'''
            script += body + '\nwait_for_application ' + str(seconds) + '\n'
            return subprocess.run(['bash'], input=script, text=True, capture_output=True, timeout=3,
                env={'PATH': os.environ['PATH'], 'CLOCK': str(clock), 'STATUS': 'Failed' if failed else 'None'}).returncode
        self.assertNotEqual(0, run(900)); self.assertEqual(0, run(1800)); self.assertNotEqual(0, run(1800, failed=True))
        selected = source[source.index('continue_global_b_services()'):]
        self.assertIn('wait_for_application 1800', selected)
        self.assertIn('preparation.sourceMode == "mac-sql-postcheck"', selected)


class MacServicePlan(unittest.TestCase):
    """Execute the real plan gate and Mac receipt validators; stub cloud I/O only."""
    HOST = 'module.service_hosts.aws_instance.this["debezium"]'

    def setUp(self):
        self.producer, self.manifest, self.transition, _, _, self.objects = mac_fixture(self)
        self.root = self.producer.root

    def plan(self, host_actions=None, before=None):
        op = self.producer.op
        host = {'ami': op['amiId'], 'instance_type': 't3.medium', 'associate_public_ip_address': False,
            'iam_instance_profile': 'airbob-lab-host-' + op['runId'] + '-debezium',
            'root_block_device': [{'encrypted': True, 'delete_on_termination': True, 'volume_type': 'gp3', 'volume_size': 20}],
            'tags': {'Project': 'airbob', 'Environment': 'performance-lab', 'Stack': 'lab', 'ManagedBy': 'terraform',
                'Persistence': 'ephemeral', 'Service': 'debezium', 'Name': 'airbob-' + op['runId'] + '-debezium',
                'RunId': op['runId'], 'FencingToken': str(op['fencingToken']), 'ExpiresAt': op['expiresAt']}}
        return {'resource_changes': [
            {'address': downsize.ADDRESS, 'type': 'aws_db_instance', 'mode': 'managed',
                'change': {'actions': ['no-op'], 'before': self.producer.after, 'after': self.producer.after}},
            {'address': self.HOST, 'type': 'aws_instance', 'mode': 'managed',
                'change': {'actions': host_actions or ['create'], 'before': before, 'after': host}}]}

    def run_plan(self, plan=None, *, stage='dependencies', linux=False, tamper=None, context=None):
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        start = source.index('  if [[ "$global_b_services" == true ]]; then', source.index('apply_lab() {'))
        # The preceding NAT/persistence/class gates are unchanged. Execute this
        # exact phase gate through its real final apply boundary, without the
        # Linux-only empty-array expansion earlier in apply_lab on macOS Bash3.
        body = 'apply_lab() {\n' + source[start:source.index('\n}\n', start) + 3]
        with tempfile.TemporaryDirectory(dir=self.root, prefix='plan-') as directory:
            root = Path(directory)
            manifest = fixture() if linux else self.manifest
            files = {'dataset-manifest.json': downsize.encoded(manifest),
                'operator.json': downsize.encoded(self.producer.op),
                'transition.json': downsize.encoded(self.transition), 'mac-sql-complete.json': self.producer.sql_raw,
                'mac-sql-postcheck.json': downsize.encoded(self.producer.postcheck)}
            manifest_sha = hashlib.sha256(files['dataset-manifest.json']).hexdigest()
            if tamper:
                name, mutate = tamper
                value = json.loads(files[name]); mutate(value); files[name] = downsize.encoded(value)
            for name, raw in files.items(): (root / name).write_bytes(raw)
            (root / 'selected-plan.json').write_text(json.dumps(plan or self.plan()))
            variables = {'temp_dir': directory, 'script_dir': str(SCRIPTS), 'lab_root': '/never-real-terraform',
                'current_tfvars': str(root / 'unused.tfvars'), 'global_b_services': 'true', 'global_b_prepare_only': 'false',
                'plan_json': str(root / 'selected-plan.json'), 'plan_file': str(root / 'lab.tfplan'),
                'global_b_snapshot_restore_only': 'false', 'rds_instance_class': downsize.SMALL,
                'rds_class_operator_file': str(root / 'operator.json'), 'rds_class_transition_file': str(root / 'transition.json'),
                'dataset_manifest_sha256': manifest_sha, 'B_SERVICE_STAGE': stage, 'run_id': self.producer.op['runId'],
                'ami_id': self.producer.op['amiId'], 'resource_fencing_token': str(self.producer.op['fencingToken']),
                'expires_at': self.producer.op['expiresAt']}
            variables.update(context or {})
            script = 'set -euo pipefail\n' + '\n'.join(k + '=' + shlex.quote(v) for k, v in variables.items()) + '\n'
            script += '''fail() { printf '%s\\n' "$*" >&2; exit 23; }
assert_lease() { :; }
prepare_lab_backend() { :; }
sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
aws() { fail FORBIDDEN_AWS_CALL; }
terraform() { fail FORBIDDEN_TERRAFORM_CALL; }
run_terraform_command() {
  [[ "$1" == 'Terraform lab-plan inspection' ]] || fail UNEXPECTED_INSPECTION
  cat "$temp_dir/selected-plan.json"
}
run_supervised_mutation() {
  case "$1" in
    'Terraform lab plan') printf 'fake-plan' > "$temp_dir/lab.tfplan" ;;
    'Terraform lab apply') printf 'applied' > "$temp_dir/applied" ;;
    *) fail FORBIDDEN_MUTATION ;;
  esac
}
'''
            result = subprocess.run(['bash'], input=script + body + '\napply_lab\n', capture_output=True,
                text=True, timeout=10, env={'PATH': os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'})
            return result, (root / 'applied').exists()

    def test_actual_mac_receipts_allow_one_absent_normal_connect_create(self):
        result, applied = self.run_plan()
        self.assertEqual(0, result.returncode, result.stderr); self.assertTrue(applied)

    def test_linux_source_and_other_service_stages_still_reject_host_creation(self):
        for arguments in ({'linux': True}, {'stage': 'bootstrap'}, {'stage': 'application'}):
            with self.subTest(arguments=arguments):
                result, applied = self.run_plan(**arguments)
                self.assertNotEqual(0, result.returncode); self.assertFalse(applied)
                self.assertIn('retain the prepared RDS and data host', result.stderr)

    def test_existing_host_noop_or_update_remains_allowed_for_both_sources(self):
        for linux in (False, True):
            for actions in (['no-op'], ['update']):
                plan = self.plan(actions); plan['resource_changes'][1]['change']['before'] = copy.deepcopy(plan['resource_changes'][1]['change']['after'])
                with self.subTest(linux=linux, actions=actions):
                    result, applied = self.run_plan(plan, linux=linux, stage='bootstrap')
                    self.assertEqual(0, result.returncode, result.stderr); self.assertTrue(applied)

    def test_host_delete_replacement_import_move_and_prior_presence_cannot_use_exception(self):
        plans = []
        for actions in (['delete'], ['delete', 'create'], ['create', 'delete']): plans.append(self.plan(actions, {'id': 'i-existing'}))
        plans.append(self.plan(before={'id': 'i-existing'}))
        for field in ('previous_address',):
            plan = self.plan(); plan['resource_changes'][1][field] = 'module.foreign.aws_instance.this'; plans.append(plan)
        plan = self.plan(); plan['resource_changes'][1]['change']['importing'] = {'id': 'i-existing'}; plans.append(plan)
        plan = self.plan(); plan['prior_state'] = {'values': {'root_module': {'child_modules': [{'resources': [{'address': self.HOST}]}]}}}; plans.append(plan)
        plan = self.plan(); plan['resource_changes'].append(copy.deepcopy(plan['resource_changes'][1])); plans.append(plan)
        for n, plan in enumerate(plans):
            with self.subTest(case=n):
                result, applied = self.run_plan(plan); self.assertNotEqual(0, result.returncode); self.assertFalse(applied)

    def test_rds_create_delete_or_replacement_never_reaches_apply(self):
        for address in (downsize.ADDRESS, 'module.rds[1].aws_db_instance.this'):
            for actions in (['create'], ['delete'], ['delete', 'create']):
                plan = self.plan(); row = plan['resource_changes'][0]; row['address'] = address; row['change']['actions'] = actions
                if actions == ['create']: row['change']['before'] = None
                if actions == ['delete']: row['change']['after'] = None
                with self.subTest(address=address, actions=actions):
                    result, applied = self.run_plan(plan); self.assertNotEqual(0, result.returncode); self.assertFalse(applied)

    def test_nonordinary_host_and_original_identity_drift_are_rejected(self):
        changes = [('instance_type', 'c6i.large'), ('ami', 'ami-foreign'), ('associate_public_ip_address', True),
            ('iam_instance_profile', 'foreign-profile'), ('root_block_device', [{'encrypted': True, 'delete_on_termination': True, 'volume_type': 'gp3', 'volume_size': 100}])]
        for field, value in changes:
            plan = self.plan(); plan['resource_changes'][1]['change']['after'][field] = value
            with self.subTest(field=field):
                result, applied = self.run_plan(plan); self.assertNotEqual(0, result.returncode); self.assertFalse(applied)
        for tag in ('RunId', 'FencingToken', 'ExpiresAt', 'Service'):
            plan = self.plan(); plan['resource_changes'][1]['change']['after']['tags'][tag] = 'foreign'
            with self.subTest(tag=tag):
                result, applied = self.run_plan(plan); self.assertNotEqual(0, result.returncode); self.assertFalse(applied)
        for variable in ('run_id', 'resource_fencing_token', 'expires_at', 'ami_id'):
            with self.subTest(variable=variable):
                result, applied = self.run_plan(context={variable: 'foreign'})
                self.assertNotEqual(0, result.returncode); self.assertFalse(applied)

    def test_reviewed_source_bytes_and_mac_operator_are_rechecked_at_plan_boundary(self):
        for name, field, value in [('dataset-manifest.json', 'runId', 'lab-foreign'),
                ('operator.json', 'globalBImportFromMac', False), ('transition.json', 'fullDatasetValidated', True),
                ('mac-sql-complete.json', 'state', 'SQL_IMPORT_FAILED'), ('mac-sql-postcheck.json', 'state', 'FAILED')]:
            with self.subTest(file=name):
                result, applied = self.run_plan(tamper=(name, lambda row: row.update({field: value})))
                self.assertNotEqual(0, result.returncode); self.assertFalse(applied)


if __name__ == '__main__': unittest.main()
