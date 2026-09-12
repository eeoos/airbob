"""Hermetic adoption boundaries. Docker/DB/runtime calls are always replaced.

These tests do not construct a second dataset or run an integration rehearsal.
They exercise actual admission/observation/stage selection with inert fixtures.
"""
from contextlib import ExitStack, nullcontext
import copy
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location('dev_adoption', ROOT / 'scripts/adopt-growth-b-dev.py')
adopt = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(adopt)


class Fixture(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='airbob-dev-adoption-unit-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name).resolve()
        self.compose = self.root / 'usual'; self.compose.mkdir()
        self.compose_file = self.compose / 'docker-compose.yml'; self.compose_file.write_text('services: {}\n')
        self.source = self.root / 'global-b-final-20260911-02'; self.source.mkdir()
        self.release = self.source / 'release'; self.release.mkdir()
        for name in ('consumer-manifest.json', 'SHA256SUMS.json'):
            (self.release / name).write_text('{}')
        self.jar = self.root / 'app.jar'; self.jar.write_bytes(b'fixture jar')
        self.original_private = self.root / 'preserved'; self.original_private.mkdir(mode=0o700)
        self.target_private = self.compose / '.local/airbob-dev/mysql'; self.target_private.mkdir(mode=0o700, parents=True)
        for directory in (self.original_private, self.target_private):
            (directory / 'root-password').write_text('a' * 64); (directory / 'root-password').chmod(0o600)
        self.engine = {'context': 'desktop-linux', 'configuredEndpoint': 'unix:///fixture/docker.sock',
            'endpoint': 'unix:///fixture/docker.sock', 'engineId': 'engine-fixture', 'dockerRootDir': '/var/lib/docker'}
        self.volume = {'Name': 'original-anonymous-volume', 'CreatedAt': '2026-09-11T10:33:00Z',
            'Driver': 'local', 'Options': None, 'Labels': {'com.docker.volume.anonymous': ''}, 'Scope': 'local'}
        self.preserved = {'state': 'ACTIVE_VERIFIER_VOLUME_REFERENCED_NOT_YET_QUALIFIED', 'sourceRun': str(self.source),
            'sourceContainerId': '1' * 64, 'sourceImageId': 'sha256:' + '2' * 64, 'serverUuid': '11111111-1111-1111-1111-111111111111',
            'engineId': self.engine['engineId'], 'dockerRootDir': self.engine['dockerRootDir'], 'volume': self.volume,
            'privateMysqlDirectory': str(self.original_private), 'holderContainerId': '3' * 64,
            'holderContainerName': 'fixture-holder', 'holderImageId': 'sha256:' + '4' * 64,
            'holderVolumeReadOnly': True, 'dataCopied': False, 'newDatabasesStarted': 0}
        self.preserved_path = self.save('preserved.json', self.preserved)
        self.qualification = {'state': 'DATASET_DB_AND_HTTP_QUALIFIED', 'finalScaleSelected': True,
            'datasetScale': adopt.contract.FINAL_SCALE, 'stages': ['migrate-passed', 'etl-and-integrity-passed', 'dump-passed',
                'restore-rows-ddl-integrity-passed', 'http-reads-and-immutable-database-passed', 'small-read-and-runtime-scenarios-passed'],
            'imageId': self.preserved['sourceImageId'], 'dumpSha256': '5' * 64, 'timezoneRuntimeQualified': True,
            'databaseLifetime': {'policy': 'sequential-single-database', 'maximumConcurrentDatasetSchemas': 1, 'plaintextTemporaryDumpBytes': 0}}
        self.qualified_path = self.save('roundtrip.json', self.qualification)
        (self.release / 'qualification.json').write_text(json.dumps(self.qualification))
        self.config = {'schemaVersion': 1, 'kind': adopt.KIND + '-config', 'datasetId': 'global-growth-b-' + 'a' * 16,
            'releaseDirectory': str(self.release), 'consumerManifestSha256': adopt.contract.sha(self.release / 'consumer-manifest.json'),
            'checksSha256': adopt.contract.sha(self.release / 'SHA256SUMS.json'), 'roundtripReceipt': adopt.binding(self.qualified_path),
            'appJar': adopt.binding(self.jar), 'migrationDirectory': str(self.compose / 'migrations'),
            'preservationReceipt': adopt.binding(self.preserved_path), 'engine': self.engine,
            'compose': {'directory': str(self.compose), 'project': 'airbob', 'files': [adopt.binding(self.compose_file)], 'network': 'airbob_local-infra'},
            'mysql': {'container': 'mysql', 'port': 3307, 'rootPasswordFile': str(self.target_private / 'root-password'),
                      'rootPasswordContainerPath': '/run/airbob-mysql/root-password'},
            'elasticsearch': {'image': 'sha256:' + '6' * 64,
                'producer': {'container': 'airbob-dev-b-producer-unit', 'port': 19220, 'claim': '7' * 32},
                'service': {'container': 'elasticsearch', 'port': 9200},
                'repository': {'directory': str(self.root / 'native'), 'containerPath': '/usr/share/elasticsearch/backup',
                    'name': 'native-unit', 'snapshotRelease': 'global-growth-b-' + 'a' * 16 + '-search-unit'}},
            'redisImage': adopt.REDIS_IMAGE, 'javaHome': str(self.root / 'jdk'), 'couponPreparation': False,
            'privateAccounts': str(self.compose / '.local/airbob-dev/representative-accounts/accounts.private.json'), 'writerPorts': [8080],
            'timeouts': {name: 7200 for name in ('mysqlOperationSeconds', 'appStartupSeconds', 'searchSourceSeconds', 'snapshotSeconds')},
            'capacity': {name: adopt.GIB for name in ('incrementalFreeInventoryBytes', 'producerEsBytes', 'serviceEsBytes',
                'nativeRepositoryBytes', 'scratchBytes', 'reserveHostBytes', 'reserveDockerBytes', 'esDiskSafetyBytes')}}
        self.config_path = self.save('config.json', self.config); self.config_binding = adopt.binding(self.config_path)
        self.network = {'Name': 'airbob_local-infra', 'Id': '8' * 64, 'Driver': 'bridge', 'Scope': 'local',
                        'Labels': {'com.docker.compose.project': 'airbob', 'com.docker.compose.network': 'local-infra'}}
        self.mysql = self.container('mysql', '9' * 64, self.preserved['sourceImageId'], 3306, 3307, 'mysql')
        self.mysql['Config']['Env'] = ['MYSQL_ROOT_PASSWORD_FILE=/run/airbob-mysql/root-password']
        self.mysql['Mounts'] = [self.mount('volume', '/var/lib/mysql/volume', '/var/lib/mysql', True, self.volume['Name']),
            self.mount('bind', str(self.compose / 'docker/mysql/init'), '/docker-entrypoint-initdb.d', False),
            self.mount('bind', str(self.target_private), '/run/airbob-mysql', False)]
        self.data = {('container', 'mysql'): self.mysql, ('container', self.mysql['Id']): self.mysql,
                     ('network', 'airbob_local-infra'): self.network, ('volume', self.volume['Name']): self.volume}
        self.ids = {self.mysql['Id']}

    def save(self, name, value):
        path = self.root / name; path.write_text(json.dumps(value)); return path

    def mount(self, kind, source, target, rw, name=None):
        return {'Type': kind, 'Source': source, 'Destination': target, 'RW': rw, **({'Name': name} if name else {})}

    def container(self, name, identifier, image, internal, port, service=None):
        labels = {'com.docker.compose.project': 'airbob', 'com.docker.compose.service': service,
            'com.docker.compose.project.working_dir': str(self.compose), 'com.docker.compose.project.config_files': str(self.compose_file)} if service else {}
        ports = {str(internal) + '/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]}
        return {'Name': '/' + name, 'Id': identifier, 'Image': image, 'State': {'Running': True},
                'Config': {'Env': [], 'Labels': labels}, 'Mounts': [],
                'HostConfig': {'Privileged': False, 'NetworkMode': 'airbob_local-infra', 'Memory': 2 * adopt.GIB, 'PortBindings': ports},
                'NetworkSettings': {'Ports': ports, 'Networks': {'airbob_local-infra': {'NetworkID': self.network['Id'], 'Aliases': [name]}}}}

    def observing(self):
        stack = ExitStack()
        stack.enter_context(patch.object(adopt.restore, 'local_engine_identity', return_value=self.engine))
        stack.enter_context(patch.object(adopt.restore, 'existing', side_effect=lambda kind: self.ids if kind == 'container' else {self.volume['Name']}))
        stack.enter_context(patch.object(adopt.restore, 'inspect', side_effect=lambda kind, name: self.data[(kind, name)]))
        stack.enter_context(patch.object(adopt.restore, 'port_open', return_value=False))
        return stack


class AdmissionTest(Fixture):
    def test_config_accepts_original_anonymous_labels_without_fabricating_fresh_claim(self):
        actual = adopt.configuration(self.config_path, self.config_binding['sha256'])
        self.assertEqual(self.config, actual)
        self.assertNotIn('airbob.restore.claim', self.volume['Labels'])

    def test_application_image_as_redis_and_coupon_generation_are_refused(self):
        for change in ({'redisImage': self.preserved['holderImageId']}, {'couponPreparation': True}):
            with self.subTest(change=change):
                self.config_path.write_text(json.dumps(self.config | change))
                with self.assertRaises(ValueError): adopt.configuration(self.config_path, adopt.contract.sha(self.config_path))

    def test_private_member_output_cannot_enter_release_or_root_secret_directory(self):
        for path in (self.release / 'accounts.private.json', self.target_private / 'accounts.private.json'):
            with self.subTest(path=path):
                self.config_path.write_text(json.dumps(self.config | {'privateAccounts': str(path)}))
                with self.assertRaisesRegex(ValueError, 'separate ordinary private'):
                    adopt.configuration(self.config_path, adopt.contract.sha(self.config_path))

    def test_preservation_is_not_a_substitute_for_completed_final_qualification(self):
        for change in ({'state': 'RUNNING'}, {'finalScaleSelected': False}, {'retainedDatabases': [{'reason': 'failed'}]}, {'stages': []}):
            with self.subTest(change=change), patch.object(adopt.contract, 'validate') as validate:
                proof = self.qualification | change
                self.qualified_path.write_text(json.dumps(proof)); (self.release / 'qualification.json').write_text(json.dumps(proof))
                self.config['roundtripReceipt'] = adopt.binding(self.qualified_path)
                with self.assertRaises(ValueError): adopt.validate_final(self.config)
                validate.assert_not_called()

    def test_complete_final_binds_exact_original_image_and_dump(self):
        validated = ({'finalScaleSelected': True}, {'airbob-growth.sql.gz': '5' * 64}, {})
        with patch.object(adopt.contract, 'validate', return_value=validated) as validate:
            self.assertEqual(validated, adopt.validate_final(self.config))
            self.assertFalse(validate.call_args.kwargs['allow_small'])
            self.assertEqual(self.config['appJar']['sha256'], validate.call_args.kwargs['expected_app_sha'])

    def test_changed_compose_bytes_fail_before_observing_engine(self):
        self.compose_file.write_text('changed')
        with patch.object(adopt.restore, 'local_engine_identity') as engine:
            with self.assertRaisesRegex(ValueError, 'Compose source'): adopt.observe(self.config, 'mysql')
            engine.assert_not_called()


class ResourceTest(Fixture):
    def service_es(self):
        es = self.container('elasticsearch', 'e' * 64, self.config['elasticsearch']['image'], 9200, 9200, 'elasticsearch')
        es['Mounts'] = [self.mount('volume', '/var/lib/docker/es', '/usr/share/elasticsearch/data', True, 'airbob_es-data'),
            self.mount('bind', self.config['elasticsearch']['repository']['directory'], '/usr/share/elasticsearch/backup', False)]
        volume = self.volume | {'Name': 'airbob_es-data', 'Labels': {'com.docker.compose.project': 'airbob', 'com.docker.compose.volume': 'es-data'}}
        self.data[('container', 'elasticsearch')] = es; self.data[('volume', 'airbob_es-data')] = volume; self.ids.add(es['Id'])
        return es, volume

    def test_exact_host_source_remains_valid_on_other_platforms(self):
        for platform in ('darwin', 'linux', 'win32'):
            with self.subTest(platform=platform), patch.object(adopt.sys, 'platform', platform):
                self.assertTrue(adopt.host_bind_source_matches('/Users/fixture/native', '/Users/fixture/native', self.config))

    def test_es_desktop_alias_passes_actual_observe_and_keeps_observed_source_bytes(self):
        es, _ = self.service_es(); expected = self.config['elasticsearch']['repository']['directory']
        es['Mounts'][1]['Source'] = '/host_mnt' + expected
        with patch.object(adopt.sys, 'platform', 'darwin'), self.observing(): value = adopt.observe(self.config, 'service')
        native = next(item for item in value['elasticsearch']['mounts'] if item['Type'] == 'bind')
        self.assertEqual('/host_mnt' + expected, native['Source']); self.assertFalse(native['RW'])
        self.assertEqual(self.engine, value['engine'])

    def test_es_alias_requires_darwin_desktop_context_and_same_local_unix_endpoint(self):
        es, _ = self.service_es(); es['Mounts'][1]['Source'] = '/host_mnt' + self.config['elasticsearch']['repository']['directory']
        for platform, changes in [('linux', {}), ('win32', {}), ('darwin', {'context': 'other'}),
                ('darwin', {'endpoint': 'unix://remote/fixture.sock', 'configuredEndpoint': 'unix://remote/fixture.sock'}),
                ('darwin', {'endpoint': 'ssh://host', 'configuredEndpoint': 'ssh://host'}),
                ('darwin', {'endpoint': 'tcp://127.0.0.1:2375', 'configuredEndpoint': 'tcp://127.0.0.1:2375'}),
                ('darwin', {'endpoint': 'unix:///another.sock'}),
                ('darwin', {'endpoint': 'unix:////fixture/docker.sock', 'configuredEndpoint': 'unix:////fixture/docker.sock'})]:
            original = copy.deepcopy(self.engine); self.engine.update(changes)
            try:
                with self.subTest(platform=platform, changes=changes), patch.object(adopt.sys, 'platform', platform), self.observing():
                    with self.assertRaisesRegex(ValueError, 'Native repository/data mounts'): adopt.observe(self.config, 'service')
            finally: self.engine.clear(); self.engine.update(original)

    def test_es_alias_rejects_wrong_host_prefix_suffix_relative_and_multiple_prefixes(self):
        es, _ = self.service_es(); expected = self.config['elasticsearch']['repository']['directory']
        actuals = ['/mnt' + expected, '/host_mnt/another-host' + expected, '/host_mnt' + expected + '-other',
                   '/host_mnt/host_mnt' + expected, 'host_mnt' + expected, '/host_mnt' + expected + '/',
                   '/host_mnt' + expected + '/.', '/host_mnt' + expected + '/../native', '/host_mnt/' + expected]
        for actual in actuals:
            es['Mounts'][1]['Source'] = actual
            with self.subTest(actual=actual), patch.object(adopt.sys, 'platform', 'darwin'), self.observing():
                with self.assertRaisesRegex(ValueError, 'Native repository/data mounts'): adopt.observe(self.config, 'service')
        self.assertFalse(adopt.host_bind_source_matches('relative', 'relative', self.config))
        with patch.object(adopt.sys, 'platform', 'darwin'):
            self.assertFalse(adopt.host_bind_source_matches('/host_mnt/host_mnt/Users/fixture/native', '/host_mnt/Users/fixture/native', self.config))

    def test_alias_does_not_relax_readonly_volume_port_or_engine_guards(self):
        es, volume = self.service_es(); es['Mounts'][1]['Source'] = '/host_mnt' + self.config['elasticsearch']['repository']['directory']
        mutations = [(es['Mounts'][1], 'RW', True), (volume['Labels'], 'com.docker.compose.project', 'someone-else'),
                     (es['HostConfig']['PortBindings']['9200/tcp'][0], 'HostIp', '0.0.0.0')]
        for owner, key, value in mutations:
            original = owner[key]; owner[key] = value
            try:
                with self.subTest(field=key), patch.object(adopt.sys, 'platform', 'darwin'), self.observing(), self.assertRaises(ValueError):
                    adopt.observe(self.config, 'service')
            finally: owner[key] = original
        with patch.object(adopt.sys, 'platform', 'darwin'), self.observing(), \
                patch.object(adopt.restore, 'local_engine_identity', return_value=self.engine | {'engineId': 'other'}), \
                patch.object(adopt.restore, 'inspect') as inspect:
            with self.assertRaisesRegex(ValueError, 'engine'): adopt.observe(self.config, 'service')
            inspect.assert_not_called()

    def test_original_container_must_be_absent_even_if_target_is_correct(self):
        self.ids.add(self.preserved['sourceContainerId'])
        with self.observing(), self.assertRaisesRegex(ValueError, 'original verifier'): adopt.observe(self.config, 'mysql')

    def test_exact_ordinary_compose_target_is_observed_without_env_or_password(self):
        with self.observing(): value = adopt.observe(self.config, 'mysql')
        self.assertEqual(self.volume, value['volume'])
        self.assertEqual('mysql', value['mysql']['container'])
        self.assertNotIn('MYSQL_ROOT_PASSWORD', json.dumps(value))
        self.assertNotIn('a' * 64, json.dumps(value))

    def test_another_engine_is_rejected_before_container_read(self):
        with self.observing(), patch.object(adopt.restore, 'local_engine_identity', return_value=self.engine | {'engineId': 'other'}), patch.object(adopt.restore, 'inspect') as inspect:
            with self.assertRaisesRegex(ValueError, 'engine'): adopt.observe(self.config, 'mysql')
            inspect.assert_not_called()

    def test_volume_metadata_changes_and_wrong_mount_are_rejected(self):
        for change in ({'CreatedAt': 'later'}, {'Driver': 'nfs'}, {'Labels': {}}, {'Options': {'device': 'elsewhere'}}):
            with self.subTest(change=change), self.observing():
                self.data[('volume', self.volume['Name'])] = self.volume | change
                with self.assertRaisesRegex(ValueError, 'volume'): adopt.observe(self.config, 'mysql')
        self.data[('volume', self.volume['Name'])] = self.volume
        self.mysql['Mounts'][0]['Name'] = 'different'
        with self.observing(), self.assertRaisesRegex(ValueError, 'sole original'): adopt.observe(self.config, 'mysql')

    def test_wrong_compose_project_or_service_is_not_an_adoption(self):
        for field in ('com.docker.compose.project', 'com.docker.compose.service', 'com.docker.compose.project.working_dir'):
            original = self.mysql['Config']['Labels'][field]
            self.mysql['Config']['Labels'][field] = 'other'
            with self.subTest(field=field), self.observing(), self.assertRaisesRegex(ValueError, 'Compose'): adopt.observe(self.config, 'mysql')
            self.mysql['Config']['Labels'][field] = original

    def test_writable_secret_mount_and_inline_password_are_rejected(self):
        self.mysql['Mounts'][2]['RW'] = True
        with self.observing(), self.assertRaisesRegex(ValueError, 'read-only'): adopt.observe(self.config, 'mysql')
        self.mysql['Mounts'][2]['RW'] = False
        self.mysql['Config']['Env'].append('MYSQL_ROOT_PASSWORD=fixture-only')
        with self.observing(), self.assertRaisesRegex(ValueError, 'inline'): adopt.observe(self.config, 'mysql')

    def test_unknown_container_is_not_silently_accepted_as_normal_infra(self):
        self.ids.add('f' * 64)
        with self.observing(), self.assertRaisesRegex(ValueError, 'Unexpected'): adopt.observe(self.config, 'mysql')

    def test_memory_adapter_preserves_actual_info_and_never_places_password_in_argv(self):
        original = copy.deepcopy(self.mysql)
        enriched = adopt.private_info(self.config, self.mysql)
        self.assertEqual(self.mysql, original)
        self.assertIn('MYSQL_ROOT_PASSWORD=' + 'a' * 64, enriched['Config']['Env'])
        db = adopt.FilePasswordDatabase(self.mysql['Id'], '/run/airbob-mysql/root-password')
        self.assertNotIn('a' * 64, json.dumps(db.command()))
        self.assertIn('/run/airbob-mysql/root-password', db.command())
        self.assertIn('"$secret"', db.command()[6])

    def test_changed_private_copy_or_permissions_are_rejected(self):
        path = self.target_private / 'root-password'; path.write_text('b' * 64)
        with self.assertRaisesRegex(ValueError, 'preserved original'): adopt.private_info(self.config, self.mysql)
        path.write_text('a' * 64); path.chmod(0o644)
        with self.assertRaisesRegex(ValueError, '0600/0700'): adopt.private_info(self.config, self.mysql)

    def test_mysql_uuid_mismatch_fails_before_schema_or_credentials_are_used(self):
        db = Mock(); db.identity.return_value = {'version': '8.4.11', 'uuid': 'different'}
        with self.observing(), patch.object(adopt, 'FilePasswordDatabase', return_value=db), patch.object(adopt, 'private_info') as private:
            with self.assertRaisesRegex(ValueError, 'UUID'): adopt.mysql_connection(self.config)
        db.rows.assert_not_called(); private.assert_not_called()

    def test_final_compose_es_requires_readonly_native_mount_and_its_own_volume(self):
        es, volume = self.service_es()
        with self.observing(): value = adopt.observe(self.config, 'service')
        self.assertEqual('airbob_es-data', value['elasticsearchVolume']['Name'])
        es['Mounts'][1]['RW'] = True
        with self.observing(), self.assertRaisesRegex(ValueError, 'RO'): adopt.observe(self.config, 'service')
        es['Mounts'][1]['RW'] = False; volume['Labels']['com.docker.compose.project'] = 'someone-else'
        with self.observing(), self.assertRaisesRegex(ValueError, 'es-data'): adopt.observe(self.config, 'service')


class RetainedSourceTest(Fixture):
    """A successful second-repeat closure binds a different final run and source volume."""
    def setUp(self):
        super().setUp()
        self.first_source = self.source
        for name in ('roundtrip.json', 'scenario-qualification.json'):
            (self.first_source / name).write_text(json.dumps({'state': 'FAILED'}))
        self.old_holder = copy.deepcopy(self.preserved)
        self.old_holder['volume']['Name'] = 'separate-old-source-volume'
        self.old_holder_path = self.save('old-holder.json', self.old_holder)
        self.source = self.root / 'global-b-final-20260912-05'; self.source.mkdir()
        self.release = self.source / 'release'; self.release.mkdir()
        for name in ('consumer-manifest.json', 'SHA256SUMS.json'):
            (self.release / name).write_text('{}')
        controller = self.root / 'continuation.py'; controller.write_text('# inert controller fixture\n')
        self.plan = {'kind': 'second-runtime-continuation-plan', 'sourceRun': str(self.first_source),
            'output': str(self.source), 'driver': adopt.binding(controller), 'firstRepeatInherited': True,
            'executedRepeats': [2], 'firstRepeatReexecuted': False, 'datasetRegenerated': False,
            'allowedHolderReceipt': None,
            'sourceFiles': {name: adopt.contract.sha(self.first_source / name)
                            for name in ('roundtrip.json', 'scenario-qualification.json')}}
        self.plan['sourceFiles']['release/airbob-growth.sql.gz'] = self.qualification['dumpSha256']
        self.plan_path = self.save('continuation-plan.json', self.plan)
        self.attach = {'state': 'SECOND_REPEAT_BASELINE_RESTORED_FROZEN_FINGERPRINT_PENDING',
            'sourceRun': str(self.first_source), 'schema': 'airbob_growth_bulk_write_benchmark',
            'dumpSha256': self.qualification['dumpSha256'], 'firstRepeatReexecuted': False, 'datasetRegenerated': False,
            'engine': self.engine, 'containerId': 'b' * 64, 'image': self.mysql['Image'],
            'serverUuid': '22222222-2222-2222-2222-222222222222', 'volume': self.volume,
            'secretDirectory': str(self.original_private)}
        self.attach_path = self.save('attach.json', self.attach)
        attach = adopt.binding(self.attach_path)
        self.qualification.update(retainedTarget={'attach': attach, 'serverUuid': self.attach['serverUuid'],
            'schema': 'airbobdb', 'frozen': True, 'automaticRemoval': False}, continuation={
                'kind': 'second-repeat-continuation', 'plan': adopt.binding(self.plan_path), 'attach': attach,
                'controller': self.plan['driver'], 'inheritedFirstRepeat': True, 'executedRepeats': [2],
                'firstRepeatReexecuted': False, 'datasetRegenerated': False, 'localOnly': True,
                'originalFailureReceipt': adopt.binding(self.first_source / 'roundtrip.json'),
                'originalScenarioReceipt': adopt.binding(self.first_source / 'scenario-qualification.json')})
        self.qualification['databaseLifetime'].update(attachedRestore=attach, finalSoleSchema='airbobdb', mysqlRetainedFrozen=True)
        self.qualified_path = self.source / 'roundtrip.json'
        self.preserved = {'schemaVersion': 1, 'state': adopt.QUALIFIED_SOURCE, 'sourceRun': str(self.source),
            'roundtripReceipt': {}, 'engine': self.engine, 'sourceContainerId': self.attach['containerId'],
            'sourceImageId': self.attach['image'], 'serverUuid': self.attach['serverUuid'], 'volume': self.volume,
            'privateMysqlDirectory': self.attach['secretDirectory'], 'dataCopied': False, 'newDatabasesStarted': 0,
            'allowedHolder': None}
        self.preserved_path = self.root / 'qualified-preservation.json'
        self.config['releaseDirectory'] = str(self.release)
        self.seal_outer()

    def seal_outer(self):
        """Rebind only reviewed JSON bytes; no tool, database or integration execution."""
        for path in (self.qualified_path, self.release / 'qualification.json'):
            path.write_text(json.dumps(self.qualification))
        self.config['roundtripReceipt'] = adopt.binding(self.qualified_path)
        self.preserved['roundtripReceipt'] = self.config['roundtripReceipt']
        self.preserved_path.write_text(json.dumps(self.preserved))
        self.config['preservationReceipt'] = adopt.binding(self.preserved_path)
        self.config_path.write_text(json.dumps(self.config)); self.config_binding = adopt.binding(self.config_path)

    def admit(self):
        return adopt.configuration(self.config_path, self.config_binding['sha256'])

    def install_holder(self, *, authorized=True):
        labels = {'airbob.dev-handoff.fixture': 'original-holder'}
        holder = self.container(self.old_holder['holderContainerName'], self.old_holder['holderContainerId'],
            self.old_holder['holderImageId'], 0, 0)
        holder['Config']['Labels'] = labels; holder['State']['Running'] = False
        holder['Mounts'] = [self.mount('volume', '/fixture/original-volume', '/preserved', False, self.old_holder['volume']['Name'])]
        self.data[('container', holder['Id'])] = holder
        self.data[('volume', self.old_holder['volume']['Name'])] = copy.deepcopy(self.old_holder['volume'])
        self.ids.add(holder['Id'])
        if authorized:
            reference = adopt.binding(self.old_holder_path)
            self.plan['allowedHolderReceipt'] = reference
            self.plan_path.write_text(json.dumps(self.plan))
            self.qualification['continuation']['plan'] = adopt.binding(self.plan_path)
            self.preserved['allowedHolder'] = {'receipt': reference, 'labels': labels}
            self.seal_outer()
        return holder

    def test_successful_retention_accepts_new_final_run_without_a_fake_holder(self):
        self.assertEqual(self.config, self.admit())
        validated = ({'finalScaleSelected': True}, {'airbob-growth.sql.gz': self.qualification['dumpSha256']}, {})
        with patch.object(adopt.contract, 'validate', return_value=validated):
            self.assertEqual(validated, adopt.validate_final(self.config))
        with self.observing(): observed = adopt.observe(self.config, 'mysql')
        self.assertFalse(observed['holderPresent']); self.assertTrue(observed['sourceContainerAbsent'])
        self.assertNotEqual(self.attach['sourceRun'], self.preserved['sourceRun'])
        self.assertNotIn('holderContainerId', self.preserved)
        self.assertNotIn('retainedDatabases', self.qualification)

    def test_pending_or_failed_closure_cannot_be_relabelled_as_qualified_retention(self):
        original = copy.deepcopy(self.qualification)
        changes = [lambda p: p.update(state='FAILED'), lambda p: p.update(retainedDatabases=[{'container': 'failed'}]),
            lambda p: p['retainedTarget'].update(frozen=False), lambda p: p['retainedTarget'].update(automaticRemoval=True),
            lambda p: p['retainedTarget'].update(schema='airbob_growth_bulk_write_benchmark')]
        for change in changes:
            self.qualification = copy.deepcopy(original); change(self.qualification); self.seal_outer()
            with self.subTest(proof=self.qualification['retainedTarget']), self.assertRaises(ValueError): self.admit()

    def test_all_attach_references_and_source_coordinates_must_match(self):
        original = copy.deepcopy(self.qualification)
        for key in ('retainedTarget', 'continuation', 'databaseLifetime'):
            self.qualification = copy.deepcopy(original)
            field = 'attachedRestore' if key == 'databaseLifetime' else 'attach'
            self.qualification[key][field] = self.qualification[key][field] | {'sha256': 'f' * 64}
            self.seal_outer()
            with self.subTest(reference=key), self.assertRaisesRegex(ValueError, 'exact frozen'): self.admit()
        self.qualification = original
        preserved = copy.deepcopy(self.preserved)
        changes = {'sourceContainerId': 'c' * 64, 'sourceImageId': 'sha256:' + 'd' * 64,
            'serverUuid': '33333333-3333-3333-3333-333333333333',
            'volume': self.volume | {'Name': 'unrelated-volume'}, 'privateMysqlDirectory': str(self.root / 'other-private')}
        for key, value in changes.items():
            self.preserved = preserved | {key: value}; self.seal_outer()
            with self.subTest(sourceField=key), self.assertRaisesRegex(ValueError, 'attached target'): self.admit()
        self.preserved = preserved | {'sourceRun': str(self.first_source)}; self.seal_outer()
        with self.assertRaisesRegex(ValueError, 'successful final run'): self.admit()

    def test_bound_original_or_attach_tampering_fails_before_docker(self):
        for path in (self.attach_path, self.first_source / 'roundtrip.json', self.plan_path):
            original = path.read_bytes(); path.write_bytes(original + b' ')
            with self.subTest(path=path.name), patch.object(adopt.restore, 'inspect') as inspect, self.assertRaises(ValueError):
                self.admit()
            inspect.assert_not_called(); path.write_bytes(original)

    def test_only_plan_bound_old_holder_can_keep_its_separate_original_volume(self):
        holder = self.install_holder()
        self.admit()
        with self.observing(): observed = adopt.observe(self.config, 'mysql')
        self.assertTrue(observed['holderPresent'])
        self.assertNotEqual(holder['Mounts'][0]['Name'], observed['volume']['Name'])
        self.old_holder['engineId'] = 'different-engine'
        self.old_holder_path.write_text(json.dumps(self.old_holder))
        self.plan['allowedHolderReceipt'] = adopt.binding(self.old_holder_path)
        self.plan_path.write_text(json.dumps(self.plan))
        self.qualification['continuation']['plan'] = adopt.binding(self.plan_path)
        self.preserved['allowedHolder']['receipt'] = self.plan['allowedHolderReceipt']; self.seal_outer()
        with self.assertRaisesRegex(ValueError, 'separate volume and engine'): self.admit()

    def test_holder_drift_or_unrecorded_holder_is_rejected(self):
        holder = self.install_holder(authorized=False)
        with self.observing(), self.assertRaisesRegex(ValueError, 'Unexpected'): adopt.observe(self.config, 'mysql')
        holder = self.install_holder(); original = copy.deepcopy(holder)
        changes = [lambda h: h['State'].update(Running=True), lambda h: h['Config'].update(Labels={'changed': 'true'}),
            lambda h: h.update(Id='e' * 64), lambda h: h.update(Image='sha256:' + 'e' * 64),
            lambda h: h['Mounts'][0].update(RW=True), lambda h: h['Mounts'][0].update(Name=self.volume['Name'])]
        for change in changes:
            observed = copy.deepcopy(original); change(observed)
            self.data[('container', holder['Id'])] = observed
            with self.subTest(holder=observed), self.observing(), self.assertRaises(ValueError): adopt.observe(self.config, 'mysql')
        self.data[('container', holder['Id'])] = original
        self.data[('volume', self.old_holder['volume']['Name'])]['CreatedAt'] = 'different-creation'
        with self.observing(), self.assertRaisesRegex(ValueError, 'volume metadata'): adopt.observe(self.config, 'mysql')

    def test_source_absence_same_uuid_both_readonly_and_sole_schema_gates_remain(self):
        self.ids.add(self.attach['containerId'])
        with self.observing(), self.assertRaisesRegex(ValueError, 'original verifier'): adopt.observe(self.config, 'mysql')
        self.ids.remove(self.attach['containerId'])
        identity = {'version': '8.4.11', 'uuid': self.attach['serverUuid'], 'readOnly': 1, 'superReadOnly': 1}
        for change in ({'uuid': 'different'}, {'readOnly': 0}, {'superReadOnly': 0}):
            db = Mock(); db.identity.return_value = identity | change
            with self.subTest(identity=change), self.observing(), patch.object(adopt, 'FilePasswordDatabase', return_value=db), self.assertRaises(ValueError):
                adopt.mysql_connection(self.config)
            db.rows.assert_not_called()
        db = Mock(); db.identity.return_value = identity
        db.rows.return_value = [{'Database': 'airbobdb'}, {'Database': 'another_business_db'}]
        with self.observing(), patch.object(adopt, 'FilePasswordDatabase', return_value=db), self.assertRaisesRegex(ValueError, 'Exactly one'):
            adopt.mysql_connection(self.config)


class CapacityTest(Fixture):
    def test_existing_large_database_is_recorded_not_charged_twice(self):
        result = adopt.capacity_gate(self.config, 'baseline', host_free=3 * adopt.GIB,
            docker_free=2 * adopt.GIB, allocated_database_bytes=80 * adopt.GIB)
        self.assertEqual(80 * adopt.GIB, result['alreadyAllocatedDatabaseBytes'])
        self.assertEqual(0, result['additionalMysqlBudgetBytes'])
        self.assertEqual(2 * adopt.GIB, result['requiredHostFreeBytes'])

    def test_free_prep_is_incremental_and_host_vm_free_are_not_combined(self):
        with self.assertRaises(ValueError):
            adopt.capacity_gate(self.config, 'prepare', host_free=2 * adopt.GIB,
                docker_free=100 * adopt.GIB, allocated_database_bytes=80 * adopt.GIB)
        with self.assertRaises(ValueError):
            adopt.capacity_gate(self.config, 'prepare', host_free=100 * adopt.GIB,
                docker_free=adopt.GIB, allocated_database_bytes=80 * adopt.GIB)


class StageTest(Fixture):
    def setUp(self):
        super().setUp()
        self.stage_sources = {'script': 'f' * 64}
        self.observed = {'kind': adopt.KIND + '-resource-claim', 'role': 'mysql'}
        self.claim_path = self.save('claim.json', self.observed | {'config': self.config_binding, 'productionSources': self.stage_sources})
        self.claim = adopt.binding(self.claim_path)
        self.db = Mock(); self.db.identity.return_value = {'superReadOnly': 1}
        self.stack = ExitStack(); self.addCleanup(self.stack.close)
        mocks = {'sources': self.stage_sources, 'observe': self.observed, 'mysql_connection': (self.mysql, self.db),
                 'observe_capacity': {}, 'search_configuration': ({}, 'test-password'), 'qualify_runtime': {},
                 'activated_runtime': nullcontext()}
        for name, result in mocks.items(): self.stack.enter_context(patch.object(adopt, name, return_value=result))
        self.stack.enter_context(patch.object(adopt, 'qualification_binding', return_value={}))
        self.stack.enter_context(patch.object(adopt.contract, 'extract_runtime', return_value=self.root / 'runtime'))
        self.stack.enter_context(patch.object(adopt.restore, 'acquire_fresh_run_lock', return_value=nullcontext()))
        # Any accidental use of a fresh/import path fails this test immediately.
        for name in ('streaming', 'create_target', 'apply_fresh_plan', 'restore_new_target', 'remove_source'):
            self.stack.enter_context(patch.object(adopt.restore, name, side_effect=AssertionError('Import/create/remove forbidden')))

    def output(self, name='stage'):
        result = self.root / name; result.mkdir(mode=0o700); return result

    def previous(self, stage, artifacts=None, **updates):
        value = {'kind': adopt.KIND + '-stage', 'stage': stage, 'state': adopt.STATES[stage], 'datasetId': self.config['datasetId'],
            'config': self.config_binding, 'productionSources': self.stage_sources, 'sqlImports': 0,
            'databaseCreated': False, 'databaseFrozen': True, 'artifacts': artifacts or {}, 'result': {}}
        value.update(updates); return adopt.binding(self.save('previous.json', value))

    def test_missing_or_wrong_order_precedes_database_access(self):
        with self.assertRaisesRegex(ValueError, 'preceding'):
            adopt.execute_stage(self.config, 'prepare', self.output(), self.config_binding, self.claim, None, ({}, {}, {}))
        adopt.mysql_connection.assert_not_called()
        previous = self.previous('prepare')
        with self.assertRaisesRegex(ValueError, 'Previous stage'):
            adopt.execute_stage(self.config, 'prepare', self.output('other'), self.config_binding, self.claim, previous, ({}, {}, {}))
        adopt.mysql_connection.assert_not_called()

    def test_changed_resource_claim_is_rejected_before_db_or_runtime(self):
        adopt.observe.return_value = self.observed | {'differentContainer': True}
        with self.assertRaisesRegex(ValueError, 'claim changed'):
            adopt.execute_stage(self.config, 'baseline', self.output(), self.config_binding, self.claim, None, ({}, {}, {}))
        adopt.mysql_connection.assert_not_called(); adopt.qualify_runtime.assert_not_called()

    def test_baseline_uses_real_capture_interface_without_import_or_preparation(self):
        def capture(config, output):
            output.mkdir(); (output / 'baseline-receipt.json').write_text('{}'); (output / 'mysql-baseline-fingerprint.json').write_text('{}')
        with patch.object(adopt.search, 'capture_baseline', side_effect=capture) as capture, patch.object(adopt.restore, 'prepare_service') as prepare:
            result = adopt.execute_stage(self.config, 'baseline', self.output(), self.config_binding, self.claim, None, ({}, {}, {}))
        capture.assert_called_once(); prepare.assert_not_called(); self.db.execute.assert_not_called()
        self.assertEqual(0, result['sqlImports']); self.assertFalse(result['databaseCreated'])

    def test_failed_baseline_recheck_never_unfreezes_or_prepares_credentials(self):
        previous = self.previous('baseline')
        with patch.object(adopt.search, 'capture_baseline', side_effect=ValueError('baseline changed')), patch.object(adopt.restore, 'prepare_service') as prepare, patch.object(adopt, 'account_module') as accounts:
            with self.assertRaisesRegex(ValueError, 'baseline changed'):
                adopt.execute_stage(self.config, 'prepare', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
        prepare.assert_not_called(); accounts.assert_not_called()
        self.assertEqual(['SET GLOBAL super_read_only=ON'], [call.args[0] for call in self.db.execute.call_args_list])

    def test_mutated_preceding_artifact_is_rejected_before_mysql(self):
        path = self.save('baseline.json', {})
        previous = self.previous('baseline', {'baselineReceipt': adopt.binding(path)})
        path.write_text('{"changed":true}')
        with self.assertRaisesRegex(ValueError, 'evidence changed'):
            adopt.execute_stage(self.config, 'prepare', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
        adopt.mysql_connection.assert_not_called()

    def test_prepare_uses_existing_adapter_and_retires_password_availability_after_app_exit(self):
        previous = self.previous('baseline')
        output = self.output()
        accounts = Mock()
        prepared = {'passed': True, 'readinessVerified': True, 'applicationLeftRunning': False, 'temporarySessionsRemoved': True,
            'currentInventory': {'everyHorizonContiguous': True},
            'accountLogins': {'passed': True, 'crossCredentialRejected': True, 'representativeAccounts': 3}}
        def preparation(config, out, runtime, info, db):
            self.assertEqual('local:mysql:' + self.config['datasetId'], adopt.restore.account_environment(config))
            self.assertNotIn('serviceEnvironmentFile', config)
            (out / 'prepared-fingerprint.json').write_text('{}')
            return prepared
        (self.release / 'accounts.json').write_text('{}')
        with patch.object(adopt.search, 'capture_baseline') as capture, patch.object(adopt, 'account_module', return_value=accounts), \
                patch.object(adopt.contract, 'validate_private_accounts', return_value={'credentials': [{'memberId': 1}]}), \
                patch.object(adopt.restore, 'prepare_service', side_effect=preparation) as prepare:
            result = adopt.execute_stage(self.config, 'prepare', output, self.config_binding, self.claim, previous, ({}, {}, {}))
        capture.assert_called_once(); prepare.assert_called_once()
        self.assertEqual(['SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF', 'SET GLOBAL super_read_only=ON'],
                         [call.args[0] for call in self.db.execute.call_args_list])
        self.assertFalse(accounts.update_login_state.call_args.kwargs['usable'])
        self.assertFalse(result['result']['privateAccountsUsable']); self.assertFalse(result['couponPreparation'])

    def test_prepare_failure_still_freezes_and_retires_private_availability(self):
        previous = self.previous('baseline'); accounts = Mock(); (self.release / 'accounts.json').write_text('{}')
        with patch.object(adopt.search, 'capture_baseline'), patch.object(adopt, 'account_module', return_value=accounts), \
                patch.object(adopt.contract, 'validate_private_accounts', return_value={'credentials': [{'memberId': 1}]}), \
                patch.object(adopt.restore, 'prepare_service', side_effect=ValueError('account API mismatch')):
            with self.assertRaisesRegex(ValueError, 'account API mismatch'):
                adopt.execute_stage(self.config, 'prepare', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
        self.assertFalse(accounts.update_login_state.call_args.kwargs['usable'])
        self.assertEqual('SET GLOBAL super_read_only=ON', self.db.execute.call_args.args[0])

    def test_produce_requires_full_native_build_and_keeps_mysql(self):
        prepared = {'tables': {'fixture': {}}}
        artifacts = {'preparedFingerprint': adopt.binding(self.save('prepared.json', prepared)),
            'baselineReceipt': adopt.binding(self.save('baseline.json', {}))}
        previous = self.previous('prepare', artifacts)
        self.observed.update(elasticsearch={'imageId': self.config['elasticsearch']['image'], 'containerId': 'b' * 64},
                             elasticsearchVolume={'Name': 'owned-producer-data'})
        self.claim_path.write_text(json.dumps(self.observed | {'config': self.config_binding, 'productionSources': self.stage_sources})); self.claim = adopt.binding(self.claim_path)
        def produce(config, baseline, output, *, build_index):
            self.assertTrue(build_index); output.mkdir()
            for name, value in [('snapshot-reference.json', {'elasticsearch': {'imageId': self.config['elasticsearch']['image'], 'clusterUuid': 'source-cluster'}}),
                                ('mysql-prepared-fingerprint.json', prepared), ('manifest.json', {}), ('native-inventory.json', {})]:
                (output / name).write_text(json.dumps(value))
            return {'fixture': 'native descriptor'}
        with patch.object(adopt.restore, 'fingerprint', return_value=prepared), patch.object(adopt.search, 'produce', side_effect=produce) as produce:
            result = adopt.execute_stage(self.config, 'produce', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
        produce.assert_called_once(); self.db.execute.assert_not_called()
        self.assertTrue(result['result']['producerSafeForOperatorRemoval']); self.assertEqual(0, result['sqlImports'])

    def test_final_native_restore_refuses_producer_still_present_or_same_cluster(self):
        artifacts = {'descriptor': adopt.binding(self.save('descriptor.json', {})),
            'companionManifest': adopt.binding(self.save('manifest.json', {}))}
        previous = self.previous('produce', artifacts, result={'producerContainerId': 'b' * 64,
            'producerVolume': {'Name': 'owned-producer'}, 'producerClusterUuid': 'same-cluster'})
        with patch.object(adopt.restore, 'existing', return_value={'b' * 64}), patch.object(adopt.search, 'restore') as restore_search:
            with self.assertRaisesRegex(ValueError, 'remove only'):
                adopt.execute_stage(self.config, 'restore', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
            restore_search.assert_not_called()
        adopt.search_configuration.return_value = ({'elasticsearch': {}}, 'fixture-password')
        es = Mock(); es.identity.return_value = {'clusterUuid': 'same-cluster'}; es.alias.return_value = None
        with patch.object(adopt.restore, 'existing', return_value=set()), patch.object(adopt.search, 'Elasticsearch', return_value=es), patch.object(adopt.search, 'restore') as restore_search:
            with self.assertRaisesRegex(ValueError, 'separate fresh cluster'):
                adopt.execute_stage(self.config, 'restore', self.output('other'), self.config_binding, self.claim, previous, ({}, {}, {}))
            restore_search.assert_not_called()

    def test_final_stage_activates_only_full_native_restore_and_rechecks_prepared_db(self):
        prepared = {'tables': {'fixture': {}}}
        artifacts = {'descriptor': adopt.binding(self.save('descriptor.json', {})),
            'companionManifest': adopt.binding(self.save('manifest.json', {})),
            'baselineReceipt': adopt.binding(self.save('baseline.json', {})),
            'preparedFingerprint': adopt.binding(self.save('prepared.json', prepared))}
        previous = self.previous('produce', artifacts, result={'producerContainerId': 'b' * 64,
            'producerVolume': {'Name': 'owned-producer'}, 'producerClusterUuid': 'producer-cluster'})
        adopt.search_configuration.return_value = ({'elasticsearch': {}}, 'fixture-password')
        es = Mock(); es.identity.return_value = {'clusterUuid': 'usual-compose-cluster'}
        es.alias.side_effect = [None, 'accommodations-vnative-restored']
        def native_restore(config, companion, descriptor, baseline, output, *, activate_alias):
            self.assertTrue(activate_alias)
            output.mkdir(); (output / 'search-restore-receipt.json').write_text('{}')
            return {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'allDocumentSourceFieldsEqual': True,
                'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'activeAlias': 'accommodations-vnative-restored'}
        def fingerprint(runtime, release, info, output, timeout):
            output.write_text(json.dumps(prepared)); return prepared
        with patch.object(adopt.restore, 'existing', return_value=set()), patch.object(adopt.search, 'Elasticsearch', return_value=es), \
                patch.object(adopt.search, 'restore', side_effect=native_restore) as restore_search, \
                patch.object(adopt.restore, 'fingerprint', side_effect=fingerprint) as fingerprint:
            result = adopt.execute_stage(self.config, 'restore', self.output(), self.config_binding, self.claim, previous, ({}, {}, {}))
        restore_search.assert_called_once(); fingerprint.assert_called_once(); self.db.execute.assert_not_called()
        self.assertEqual(adopt.STATES['restore'], result['state']); self.assertEqual(0, result['sqlImports'])
        self.assertFalse(result['result']['usualApplicationStarted'])


if __name__ == '__main__':
    unittest.main()
