"""Offline retained-service boundaries; no Docker, DB, cloud or application is started."""
import contextlib
import copy
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from test_growth_b_contract import account_fixture, private_account_fixture
from test_growth_b_local_completion import completion_fixture

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('retained_local_service', ROOT / 'scripts/serve-growth-b-local.py')
service = importlib.util.module_from_spec(spec); spec.loader.exec_module(service)
spec = importlib.util.spec_from_file_location('service_account_helpers', ROOT / 'infra/aws/tests/fixtures/etl-account-runtime/growth_accounts.py')
accounts = importlib.util.module_from_spec(spec); spec.loader.exec_module(accounts)
spec = importlib.util.spec_from_file_location('service_setting_helpers', ROOT / 'infra/aws/tests/fixtures/etl-account-runtime/growth_settings.py')
settings = importlib.util.module_from_spec(spec); spec.loader.exec_module(settings)


class Fixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name); self.root.chmod(0o700)
        self.blockers = []
        for obj, name in ((service.restore, 'docker'), (service.search, 'run'), (service.host_runtime, 'qualify_runtime')):
            blocker = patch.object(obj, name, side_effect=AssertionError('Offline test attempted an external process'))
            blocker.start(); self.addCleanup(blocker.stop)
        self.bundle = account_fixture()[0]
        self.private = self.root / 'accounts.private.json'
        self.environment = 'local:unit-mysql:global-growth-b-' + '1' * 16
        self.private.write_text(json.dumps(private_account_fixture(self.bundle, self.environment))); self.private.chmod(0o600)
        self.network = {'name': 'unit-network', 'id': 'a' * 64, 'claim': 'b' * 32}
        self.engine = {'context': 'desktop-linux', 'configuredEndpoint': 'unix:///unit.sock', 'endpoint': 'unix:///unit.sock',
                       'engineId': 'unit-engine', 'dockerRootDir': '/var/lib/docker'}
        self.completion = {'privateAccounts': str(self.private), 'network': self.network,
            'mysql': {'container': 'unit-mysql', 'containerId': '1' * 64, 'image': 'sha256:' + '2' * 64,
                'volume': 'unit-mysql-data', 'volumeClaim': '3' * 32, 'volumeCreatedAt': '2026-09-11T00:00:00Z',
                'networkAlias': 'mysql', 'port': 13306, 'mysqlServerUuid': 'unit-mysql-uuid',
                'containerLabels': {'airbob.dataset.id': 'unit'}, 'volumeLabels': {'airbob.dataset.id': 'unit'}},
            'elasticsearch': {'container': 'unit-es', 'containerId': '4' * 64, 'image': 'sha256:' + '5' * 64,
                'volume': 'unit-es-data', 'volumeClaim': '6' * 32, 'volumeCreatedAt': '2026-09-11T00:00:00Z',
                'networkAlias': 'elasticsearch', 'port': 19200, 'url': 'http://127.0.0.1:19200', 'activeAlias': 'accommodations_v1',
                'clusterUuid': 'unit-es-uuid', 'containerLabels': {}, 'volumeLabels': {}}}
        self.config = {'schemaVersion': 1, 'appContainer': 'unit-app', 'redisContainer': 'unit-redis',
            'redisImage': 'sha256:' + '9' * 64, 'httpPort': 18080, 'completionReceipt': {}, 'appImageQualification': {}}

    def save(self, name, value):
        path = self.root / name; service.write(path, value)
        return {'path': str(path), 'sha256': service.sha(path)}

    def data_observation(self, which='mysql'):
        expected = self.completion[which]; mysql = which == 'mysql'
        info = {'Id': expected['containerId'], 'Name': '/' + expected['container'], 'Image': expected['image'],
            'State': {'Running': True}, 'Config': {'Labels': expected['containerLabels']},
            'NetworkSettings': {'Networks': {self.network['name']: {'NetworkID': self.network['id'], 'Aliases': [expected['networkAlias']]}}},
            'Mounts': [{'Type': 'volume', 'Name': expected['volume'],
                        'Destination': '/var/lib/mysql' if mysql else '/usr/share/elasticsearch/data'}]}
        info['NetworkSettings']['Ports'] = {'3306/tcp' if mysql else '9200/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(expected['port'])}]}
        volume = {'Name': expected['volume'], 'Driver': 'local', 'Options': {}, 'CreatedAt': expected['volumeCreatedAt'],
                  'Labels': expected['volumeLabels'] | {'airbob.restore.claim': expected['volumeClaim']}}
        return info, volume

    def manager(self):
        path = self.root / 'service-config.json'; service.write(path, self.config)
        manager = service.Supervisor(self.config, self.root, config_path=path, config_sha=service.sha(path))
        manager.completion = self.completion
        manager.network = self.network; manager.engine = self.engine; manager.environment = self.environment
        manager.restore_config = {'release': str(self.root), 'privateAccounts': str(self.private)}
        manager.runtime = self.root / 'runtime'; manager.tools = {'accounts': accounts, 'settings': settings}
        return manager


class ConfigurationTest(Fixture):
    def test_configuration_requires_reviewed_sha_and_rejects_inline_credentials(self):
        bound = self.save('config.json', self.config)
        self.assertEqual(service.configuration(bound['path'], bound['sha256']), self.config)
        with self.assertRaises(ValueError): service.configuration(bound['path'], '0' * 64)
        bound = self.save('config.json', self.config | {'password': 'never-inline'})
        with self.assertRaisesRegex(ValueError, 'inline credentials'): service.configuration(bound['path'], bound['sha256'])

    def test_config_rejects_tags_privileged_ports_unbounded_memory_and_duplicate_names(self):
        for delta in ({'redisImage': 'redis:latest'}, {'httpPort': 80}, {'appMemoryMiB': 99999},
                      {'redisContainer': self.config['appContainer']}, {'monitorIntervalSeconds': 31}):
            binding = self.save('config.json', self.config | delta)
            with self.subTest(delta=delta), self.assertRaises(ValueError): service.configuration(binding['path'], binding['sha256'])

    def test_tampered_receipt_and_shared_private_directory_are_rejected(self):
        binding = self.save('receipt.json', {'state': 'original'})
        service.write(binding['path'], {'state': 'different'})
        with self.assertRaises(ValueError): service.bound(binding)
        folder = self.root / 'shared'; folder.mkdir(mode=0o755)
        folder.chmod(0o755)  # Preserve the shared-directory fixture under a restrictive caller umask.
        with self.assertRaises(ValueError): service.private_directory(folder)

    def test_old_resource_state_cannot_change_pinned_app_image(self):
        manager = self.manager()
        manager.state['resources'] = {'app': {'id': 'f' * 64, 'name': 'unit-app', 'image': 'sha256:' + '0' * 64}}
        manager.event('STOPPED')
        with self.assertRaisesRegex(ValueError, 'binding changed'): self.manager()

    def test_private_service_environment_rejects_duplicate_key_and_symlink(self):
        path = self.root / 'service.env'; service.write_text_private(path, 'SPRING_DATASOURCE_USERNAME=u\nSPRING_DATASOURCE_PASSWORD=p\n')
        self.assertEqual(service.service_secrets(path)['SPRING_DATASOURCE_USERNAME'], 'u')
        service.write_text_private(path, 'SPRING_DATASOURCE_USERNAME=u\nSPRING_DATASOURCE_USERNAME=v\n')
        with self.assertRaises(ValueError): service.service_secrets(path)
        link = self.root / 'link.env'; link.symlink_to(path)
        with self.assertRaises(ValueError): service.service_secrets(link)


class CompletionContractTest(Fixture):
    def setUp(self):
        super().setUp()
        fixture_root = self.root / 'completion'; fixture_root.mkdir(mode=0o700)
        self.fixture = completion_fixture(fixture_root)
        self.image = {'state': 'LOCAL_CANDIDATE_IMAGE_BYTES_VERIFIED', 'imageId': service.IMAGE,
                      'platform': {'os': 'linux', 'architecture': 'arm64'},
                      'actualImageFileSha256': {'/app/app.jar': self.fixture['appJarSha256'], '/opt/java/openjdk/lib/tzdb.dat': service.TZDB_SHA}}
        self.config['completionReceipt'] = self.fixture['completionBinding']
        self.config['appImageQualification'] = self.save('image.json', self.image)
        jar_pin = patch.object(service, 'JAR_SHA', self.fixture['appJarSha256']); jar_pin.start(); self.addCleanup(jar_pin.stop)

    def reseal(self):
        value = self.fixture['completion']
        for field, source in (('inputConfig', 'config'), ('restoreConfig', 'fresh'), ('restoreReceipt', 'restore'), ('preparedFingerprint', 'prepared')):
            service.write(value[field]['path'], self.fixture[source]); value[field]['sha256'] = service.sha(value[field]['path'])
        native_binding = value['elasticsearch']['restoreReceipt']
        service.write(native_binding['path'], self.fixture['native']); native_binding['sha256'] = service.sha(native_binding['path'])
        self.config['completionReceipt'] = self.save('final-completion.json', value)
        self.config['appImageQualification'] = self.save('image.json', self.image)

    def test_real_completion_payload_crosses_service_boundary_including_five_to_four_engine_fields(self):
        result = service.read_inputs(self.config)
        self.assertEqual(result[0]['state'], 'LOCAL_B_DATABASE_AND_SEARCH_FROZEN')
        self.assertEqual(result[0]['_engine'], self.fixture['config']['engine'])
        self.assertEqual(result[2]['environment']['engine'], {k: v for k, v in result[0]['_engine'].items() if k != 'configuredEndpoint'})
        self.assertEqual(result[0]['mysql'], self.fixture['resources'].mysql)

    def test_completion_receipt_byte_tampering_is_rejected_before_any_external_call(self):
        path = Path(self.config['completionReceipt']['path']); path.write_text('{}')
        with self.assertRaisesRegex(ValueError, 'binding changed'): service.read_inputs(self.config)

    def test_shared_runtime_helper_drift_cannot_reuse_an_old_completion_proof(self):
        self.fixture['completion']['productionSources'][service.SOURCE_FILES[0]] = '0' * 64
        self.reseal()
        with self.assertRaisesRegex(ValueError, 'Production helper source'): service.read_inputs(self.config)

    def test_small_cloud_or_unprepared_result_cannot_open_service(self):
        original = copy.deepcopy(self.fixture['completion'])
        for changes in ({'cloudExecution': True}, {'state': 'PREPARED_APP_STOPPED_DATABASE_FROZEN'},
                        {'finalScaleSelected': False}, {'databaseFrozen': False}, {'qualificationApplicationStopped': False}):
            self.fixture['completion'] = original | changes; self.reseal()
            with self.subTest(changes=changes), self.assertRaises(ValueError): service.read_inputs(self.config)

    def test_changed_db_uuid_volume_or_native_mysql_binding_is_rejected(self):
        cases = [(self.fixture['restore'], 'mysqlServerUuid', 'different'),
                 (self.fixture['fresh']['target'], 'volume', 'different'),
                 (self.fixture['native']['mysql'], 'serverUuid', 'different'),
                 (self.fixture['native'], 'activeAlias', 'different'),
                 (self.fixture['native'], 'repositoryReadOnly', False)]
        for target, key, value in cases:
            old = target[key]; target[key] = value; self.reseal()
            with self.subTest(key=key), self.assertRaises(ValueError): service.read_inputs(self.config)
            target[key] = old

    def test_engine_fields_must_be_local_and_match_original_fresh_restore(self):
        for key, value in (('configuredEndpoint', 'ssh://elsewhere'), ('endpoint', 'unix:///different.sock')):
            old = self.fixture['config']['engine'][key]; self.fixture['config']['engine'][key] = value; self.reseal()
            with self.subTest(key=key), self.assertRaises(ValueError): service.read_inputs(self.config)
            self.fixture['config']['engine'][key] = old

    def test_reviewed_image_requires_exact_app_and_timezone_bytes(self):
        for path in ('/app/app.jar', '/opt/java/openjdk/lib/tzdb.dat'):
            old = self.image['actualImageFileSha256'][path]; self.image['actualImageFileSha256'][path] = '0' * 64; self.reseal()
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, 'image evidence'): service.read_inputs(self.config)
            self.image['actualImageFileSha256'][path] = old
        self.reseal(); Path(self.fixture['config']['appJar']).write_bytes(b'changed application')
        with self.assertRaisesRegex(ValueError, 'application JAR'): service.read_inputs(self.config)

    def test_source_or_oci_fresh_claim_cannot_enter_retained_local_service(self):
        for changes in ({'source': {}}, {'mode': 'oci'}, {'operation': service.restore.OCI_DISCARD_OPERATION}, {'allowSmall': True}):
            original = self.fixture['fresh']; self.fixture['fresh'] = original | changes; self.reseal()
            with self.subTest(changes=changes), self.assertRaisesRegex(ValueError, 'final local fresh'): service.read_inputs(self.config)
            self.fixture['fresh'] = original

    def test_restart_qualifies_explicit_completion_jdk_despite_old_inherited_java_home(self):
        manager = self.manager()
        runtime = self.root / 'extracted'; (runtime / 'tools').mkdir(parents=True)
        for name in ('growth_accounts', 'growth_inventory', 'growth_settings', 'growth_runtime'):
            (runtime / 'tools' / (name + '.py')).write_text('# synthetic offline helper fixture\n')
        private = private_account_fixture(self.bundle, service.restore.account_environment(self.fixture['fresh']))
        with patch.dict(os.environ, {'JAVA_HOME': '/old/corretto-21.0.6'}), \
             patch.object(service.restore, 'local_engine_identity', return_value=self.fixture['config']['engine']), \
             patch.object(service.contract, 'validate', return_value=({}, {}, {})), \
             patch.object(service.contract, 'validate_private_accounts', return_value=private), \
             patch.object(service.restore, 'extract_runtime', return_value=runtime), \
             patch.object(service.host_runtime, 'qualify_runtime', return_value={'offline': True}) as qualify, \
             patch.object(service, 'load_tools', return_value={'accounts': accounts}):
            manager.prepare_inputs()
        self.assertEqual(qualify.call_args.kwargs['java_home'], self.fixture['config']['runtime']['javaHome'])
        self.assertNotEqual(qualify.call_args.kwargs['java_home'], '/old/corretto-21.0.6')


class DataBoundaryTest(Fixture):
    def test_retained_volume_requires_exact_identity_sole_mount_network_and_loopback(self):
        info, volume = self.data_observation()
        with patch.object(service.restore, 'inspect', side_effect=lambda kind, _: info if kind == 'container' else volume), \
             patch.object(service.restore, 'docker', return_value=(info['Id'] + '\n').encode()):
            self.assertEqual(service.verify_data_container(self.completion['mysql'], self.network, mysql=True), info)

    def test_retained_volume_and_endpoint_drift_fail_closed(self):
        changes = [lambda i, v: i.update(Id='f' * 64), lambda i, v: i['State'].update(Running=False),
                   lambda i, v: v['Labels'].update({'airbob.restore.claim': '0' * 32}),
                   lambda i, v: v.update(CreatedAt='recreated'),
                   lambda i, v: i['NetworkSettings']['Networks'][self.network['name']].update(Aliases=['wrong']),
                   lambda i, v: i['NetworkSettings']['Ports']['3306/tcp'][0].update(HostIp='0.0.0.0'),
                   lambda i, v: i['NetworkSettings']['Ports']['3306/tcp'][0].update(HostPort='3307')]
        for mutation in changes:
            info, volume = self.data_observation(); mutation(info, volume)
            with self.subTest(mutation=mutation), patch.object(service.restore, 'inspect', side_effect=lambda k, _: info if k == 'container' else volume), \
                 patch.object(service.restore, 'docker', return_value=(info['Id'] + '\n').encode()), self.assertRaises(ValueError):
                service.verify_data_container(self.completion['mysql'], self.network, mysql=True)

    def test_shared_data_volume_is_rejected(self):
        info, volume = self.data_observation()
        with patch.object(service.restore, 'inspect', side_effect=lambda k, _: info if k == 'container' else volume), \
             patch.object(service.restore, 'docker', return_value=(info['Id'] + '\n' + 'f' * 64 + '\n').encode()), self.assertRaises(ValueError):
            service.verify_data_container(self.completion['mysql'], self.network, mysql=True)

    def test_another_mysql_data_container_is_rejected_even_outside_owned_network(self):
        observations = {'1' * 64: {'Id': '1' * 64, 'Mounts': [{'Destination': '/var/lib/mysql'}]},
                        '2' * 64: {'Id': '2' * 64, 'Mounts': [{'Destination': '/var/lib/mysql'}]}}
        with patch.object(service.restore, 'docker', return_value=('\n'.join(observations) + '\n').encode()), \
             patch.object(service.restore, 'inspect', side_effect=lambda _, identifier: observations[identifier]):
            with self.assertRaisesRegex(ValueError, 'Another local MySQL'): service.verify_single_mysql('1' * 64)
            observations['2' * 64]['Mounts'] = []
            service.verify_single_mysql('1' * 64)

    def test_es_wrong_alias_or_document_fingerprint_is_rejected_without_index_mutation(self):
        native = {'elasticsearch': {'clusterUuid': 'unit-es-uuid'}, 'fullDocumentFingerprint': {'sha256': 'a' * 64}}
        es = MagicMock(); es.identity.return_value = native['elasticsearch']; es.alias.return_value = 'accommodations_v1'
        es.fingerprint.return_value = native['fullDocumentFingerprint']
        with patch.object(service, 'es_client', return_value=es):
            service.verify_es(self.completion, native, all_documents=True)
            es.alias.return_value = 'accommodations_other'
            with self.assertRaises(ValueError): service.verify_es(self.completion, native)
            es.alias.return_value = 'accommodations_v1'; es.fingerprint.return_value = {'sha256': 'b' * 64}
            with self.assertRaises(ValueError): service.verify_es(self.completion, native, all_documents=True)
        es.api.assert_not_called()

    def test_remote_es_endpoint_is_not_accepted(self):
        for url in ('http://remote:9200', 'https://127.0.0.1:9200', 'http://user:password@127.0.0.1:9200'):
            value = copy.deepcopy(self.completion); value['elasticsearch']['url'] = url
            with self.subTest(url=url), self.assertRaises(ValueError): service.es_client(value)

    def test_stopped_or_unfrozen_database_and_engine_drift_are_rejected(self):
        manager = self.manager(); db = MagicMock()
        db.identity.return_value = {'uuid': 'unit-mysql-uuid', 'version': '8.4.11', 'superReadOnly': 0}
        network = {'Id': self.network['id'], 'Labels': {'airbob.restore.claim': self.network['claim']},
                   'Containers': {self.completion[k]['containerId']: {} for k in ('mysql', 'elasticsearch')}}
        with patch.object(service.restore, 'local_engine_identity', return_value=self.engine), \
             patch.object(service, 'verify_data_container', return_value={'Id': '1' * 64}), \
             patch.object(service.restore, 'inspect', return_value=network), patch.object(service.restore, 'Database', return_value=db):
            with self.assertRaisesRegex(ValueError, 'frozen'): manager.verify_dependencies(frozen=True)
        with patch.object(service.restore, 'local_engine_identity', return_value=self.engine | {'configuredEndpoint': 'unix:///other.sock'}):
            with self.assertRaisesRegex(ValueError, 'engine'): manager.verify_dependencies(frozen=True)


class ServiceResourcesTest(Fixture):
    def test_settings_use_exact_internal_names_and_preserve_frozen_isolation(self):
        value = service.settings_for_service(settings.BASE_SETTINGS, self.completion, 'unit-redis')
        self.assertEqual(value['spring.elasticsearch.uris'], 'http://elasticsearch:9200')
        self.assertEqual(value['spring.data.redis.host'], 'unit-redis')
        self.assertEqual(value['reservation.inventory.startup.enabled'], 'true')
        for key in ('payment.toss.enabled', 'google.api.enabled', 'cloud.aws.s3.write-enabled', 'operator-alert.slack.enabled',
                    'spring.kafka.listener.auto-startup', 'accommodation.detail-cache.enabled', 'reservation.inventory.seed.enabled',
                    'spring.mvc.log-request-details', 'server.tomcat.accesslog.enabled'):
            self.assertEqual(value[key], 'false')
        with self.assertRaises(ValueError): service.settings_for_service(settings.BASE_SETTINGS | {'payment.toss.enabled': 'true'}, self.completion, 'unit-redis')

    def test_name_or_port_collision_never_creates_resource(self):
        for names, port in ((b'unit-app\n', False), (b'', True)):
            manager = self.manager()
            with patch.object(service.restore, 'docker', return_value=names) as docker, \
                 patch.object(service.restore, 'port_open', return_value=port), self.assertRaises(ValueError):
                manager.create_or_reuse()
            self.assertFalse(any(call.args[0] == 'create' for call in docker.call_args_list))

    def test_creation_keeps_passwords_out_of_argv_and_reports_and_publishes_only_api_loopback(self):
        manager = self.manager(); manager.dataset_id = 'unit'
        env = self.root / 'prepared.env'; service.write_text_private(env, 'SPRING_DATASOURCE_USERNAME=airbob\nSPRING_DATASOURCE_PASSWORD=unit-secret-value\n')
        manager.completion['serviceEnvironmentFile'] = str(env)
        created = []
        def docker(*args):
            if args[0] == 'ps': return b''
            created.append(args); return (('c' if args[2] == 'unit-redis' else 'd') * 64 + '\n').encode()
        with patch.object(service.restore, 'docker', side_effect=docker), patch.object(service.restore, 'port_open', return_value=False), \
             patch.object(service.restore, 'inspect', side_effect=lambda _, pin: {'Id': pin, 'Os': 'linux', 'Architecture': 'arm64'}), \
             patch.object(service, 'container_file_sha', side_effect=[service.JAR_SHA, service.TZDB_SHA]):
            manager.create_or_reuse()
        self.assertEqual(len(created), 2)
        self.assertNotIn('unit-secret-value', repr(created)); self.assertNotIn('unit-secret-value', manager.state_path.read_text())
        self.assertIn('127.0.0.1:18080:8080', created[1]); self.assertNotIn('-p', created[0])
        self.assertIn('max-size=10m', created[1]); self.assertNotIn('--log-driver=none', created[1])
        private_env = manager.private / 'app.env'
        self.assertEqual(private_env.stat().st_mode & 0o777, 0o600)
        self.assertIn('jdbc:mysql://mysql:3306/airbobdb?', private_env.read_text())
        self.assertIn('connectionTimeZone=UTC', private_env.read_text()); self.assertNotIn('host.docker.internal', private_env.read_text())
        self.assertFalse(any('mysql' == item or item in ('rm', 'volume') for args in created for item in args))

    def test_reused_app_image_files_are_rechecked_before_start(self):
        manager = self.manager()
        manager.state['resources'] = {'app': {'id': 'c' * 64}, 'redis': {'id': 'd' * 64}}
        with patch.object(service, 'own_container'), patch.object(service.restore, 'port_open', return_value=False), \
             patch.object(service, 'container_file_sha', return_value='0' * 64), self.assertRaisesRegex(ValueError, 'timezone bytes'):
            manager.create_or_reuse()

    def test_owned_container_check_never_adopts_a_database_or_foreign_claim(self):
        resource = {'id': 'c' * 64, 'name': 'unit-app', 'image': service.IMAGE}
        info = {'Id': resource['id'], 'Name': '/unit-app', 'Image': service.IMAGE,
                'Config': {'Labels': {service.LABEL: 'a' * 32}}, 'Mounts': [{'Destination': '/var/lib/mysql'}]}
        with patch.object(service.restore, 'inspect', return_value=info), self.assertRaisesRegex(ValueError, 'ownership'):
            service.own_container(resource, 'a' * 32, self.network)
        info['Mounts'] = []
        with patch.object(service.restore, 'inspect', return_value=info), self.assertRaisesRegex(ValueError, 'ownership'):
            service.own_container(resource, 'b' * 32, self.network)

    def test_normal_docker_stop_endpoint_teardown_is_accepted_but_foreign_persistent_network_is_not(self):
        resource = {'id': 'c' * 64, 'name': 'unit-app', 'image': service.IMAGE}
        info = {'Id': resource['id'], 'Name': '/unit-app', 'Image': service.IMAGE, 'State': {'Running': False},
                'Config': {'Labels': {service.LABEL: 'a' * 32}}, 'Mounts': [],
                'HostConfig': {'NetworkMode': self.network['name'], 'Privileged': False}, 'NetworkSettings': {'Networks': {}}}
        for attached in ({}, {self.network['name']: {'NetworkID': '', 'Aliases': None}},
                         {self.network['name']: {'NetworkID': self.network['id'], 'Aliases': ['unit-app']}}):
            info['NetworkSettings']['Networks'] = attached
            with patch.object(service.restore, 'inspect', return_value=info):
                service.own_container(resource, 'a' * 32, self.network, running=False)
        info['HostConfig']['NetworkMode'] = 'foreign-network'
        with patch.object(service.restore, 'inspect', return_value=info), self.assertRaises(ValueError):
            service.own_container(resource, 'a' * 32, self.network, running=False)


class AccountsTest(Fixture):
    def test_private_handoffs_contain_only_their_audience_and_update_availability_without_password_changes(self):
        before = accounts.read_private(self.private)
        service.mark_accounts({'accounts': accounts}, self.completion, self.environment, 'http://127.0.0.1:18080', usable=True, reason='unit')
        for name, count in zip(service.HANDOFFS, (3, 2, 1)):
            path = self.root / name; result = service.read(path)
            self.assertEqual(len(result['credentials']), count); self.assertTrue(result['usable'])
            self.assertEqual(result['serviceUrl'], 'http://127.0.0.1:18080')
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertNotIn('admin@airbob.test', (self.root / service.HANDOFFS[1]).read_text())
        service.mark_accounts({'accounts': accounts}, self.completion, self.environment, 'http://127.0.0.1:18080', usable=False, reason='stopped')
        after = accounts.read_private(self.private)
        self.assertEqual([c['password'] for c in before['credentials']], [c['password'] for c in after['credentials']])
        self.assertEqual(after['loadPool']['targetConcurrentMembers'], None)
        self.assertTrue(all(c['loginState'] == 'NOT_AVAILABLE' and not c['usable'] for c in after['credentials']))

    def test_wrong_actual_account_environment_cannot_publish_verified_handoff(self):
        with self.assertRaises(ValueError): service.mark_accounts({'accounts': accounts}, self.completion, 'other-environment', 'http://127.0.0.1:18080', usable=True, reason='unit')

    def login_fixture(self):
        selected = accounts.public_account_union(self.bundle)
        database_rows = {item['memberId']: {key: item[key] for key in ('memberId', 'email', 'role', 'status', 'nickname')} for item in selected}
        for item in database_rows.values(): item['nickname'] = 'Current changed nickname'
        db = MagicMock(); db.scalar.side_effect = lambda sql: json.dumps(database_rows[int(sql.rsplit('=', 1)[1])])
        app = MagicMock(); app.env = {'AIRBOB_GROWTH_CREDENTIALS_FILE': str(self.private)}; app.client.side_effect = lambda: {}
        def login(client, email): client['member'] = next(row for row in database_rows.values() if row['email'] == email)
        app.login.side_effect = login
        def request(client, path, **kwargs):
            self.assertIs(kwargs.get('capture'), False)
            if path == '/api/v1/auth/me' and kwargs.get('expected') != (401,):
                row = client['member']; return {'response': {'data': {'id': row['memberId'], 'email': row['email'], 'nickname': row['nickname']}}}
            return {'response': None}
        app.request.side_effect = request
        return app, db, database_rows

    def test_restart_checks_current_identity_and_real_login_logout_cross_passwords_without_generation_counts(self):
        app, db, _ = self.login_fixture()
        with patch.object(accounts, 'verify_representative_accounts', side_effect=AssertionError('Generation counts must not run')):
            result = service.qualify_restart_logins({'accounts': accounts}, app, db, self.bundle, self.root, self.environment)
        self.assertTrue(result['passed']); self.assertTrue(result['crossCredentialRejected'])
        self.assertEqual(app.login.call_count, len(accounts.public_account_union(self.bundle)))
        self.assertEqual(sum(call.args[1] == '/api/v1/auth/logout' for call in app.request.call_args_list), app.login.call_count)
        cross = [call for call in app.request.call_args_list if call.args[1] == '/api/v1/auth/login']
        self.assertEqual({call.kwargs['body']['email'] for call in cross[1:]}, {'host@airbob.test', 'admin@airbob.test', self.bundle['accounts'][0]['email']})
        report = (self.root / 'restart-account-login-qualification.json').read_text()
        self.assertTrue(all(row['password'] not in report for row in accounts.read_private(self.private)['credentials']))

    def test_restart_refuses_changed_role_or_successful_cross_password(self):
        app, db, rows = self.login_fixture(); next(iter(rows.values()))['role'] = 'ADMIN'
        with self.assertRaisesRegex(ValueError, 'identity/active role'):
            service.qualify_restart_logins({'accounts': accounts}, app, db, self.bundle, self.root, self.environment)
        app, db, _ = self.login_fixture(); original = app.request.side_effect
        def rejecting(client, path, **kwargs):
            if path == '/api/v1/auth/login': raise AssertionError('Normal API wrongly accepted a rejected password')
            return original(client, path, **kwargs)
        app.request.side_effect = rejecting
        with self.assertRaises(AssertionError): service.qualify_restart_logins({'accounts': accounts}, app, db, self.bundle, self.root, self.environment)


class LifecycleTest(Fixture):
    def lifecycle(self, *, restart=False, drift=False, monitor_failure=False, mark_failure=False):
        manager = self.manager(); old = {'tables': 'original'}; checkpoint = {'tables': 'user booking changes'}
        manager.prepared = old; manager.receipt = {'preparation': {'ownerSha256BeforeAndAfter': '0' * 64}}
        manager.native = {}; manager.qualification = {'unit': True}; manager.dataset_id = 'unit'
        manager.db = MagicMock(); manager.db.identity.return_value = {'uuid': 'unit-mysql-uuid', 'superReadOnly': 1}
        manager.tools['inventory'] = MagicMock(); manager.tools['inventory'].owned_fingerprint.return_value = ('1' if restart else '0') * 64
        manager.tools['accounts'] = MagicMock(); manager.tools['accounts'].qualify_account_logins.return_value = {'passed': True, 'crossCredentialRejected': True}
        manager.tools['inventory'].verify_current_inventory.return_value = {'everyHorizonContiguous': True}
        app = MagicMock(); app.request.return_value = {'response': {'status': 'UP'}}
        service.write(self.root / 'accounts.json', self.bundle)
        if restart:
            manager.state['stoppedFingerprint'] = self.save('prior-checkpoint.json', checkpoint)
            manager.state['stoppedOwnerSha256'] = '1' * 64
        events = []; calls = []
        def fingerprint(runtime, release, info, path, timeout):
            value = {'tables': 'tampered'} if drift else checkpoint if restart else old
            if path.name.startswith('stopped'): value = checkpoint
            service.write(path, value); calls.append(('fingerprint', path.name)); return value
        def create():
            calls.append(('create',))
            manager.state['resources'] = {'app': {'id': 'c' * 64}, 'redis': {'id': 'd' * 64}}
        def sleep(_):
            if monitor_failure: raise OSError('Unit readiness loss')
            manager.stop_requested = True
        def ownership(*args, **kwargs):
            return {'State': {'Running': kwargs.get('running') is not False, 'ExitCode': 0, 'OOMKilled': False}}
        def mark(*args, **kwargs):
            events.append(kwargs['usable'])
            if mark_failure and not kwargs['usable']: raise OSError('Unit private file error')
        stack = contextlib.ExitStack(); self.addCleanup(stack.close)
        mocks = [(manager, 'prepare_inputs', lambda: None), (manager, 'verify_dependencies', lambda **_: {'Id': '1' * 64}),
                 (manager, 'create_or_reuse', create), (manager, 'app_client', lambda: app),
                 (service.restore, 'fingerprint', fingerprint), (service.restore, 'docker', lambda *args: calls.append(args)),
                 (service.restore, 'local_engine_identity', lambda **_: self.engine), (service, 'verify_data_container', lambda *a, **k: {'Id': '1' * 64}),
                 (service, 'verify_single_mysql', lambda _: None),
                 (service, 'verify_es', lambda *a, **k: None), (service, 'own_container', ownership), (service, 'mark_accounts', mark),
                 (service.host_runtime, 'activated_runtime', lambda _: contextlib.nullcontext()),
                 (service.time, 'sleep', sleep)]
        for obj, name, value in mocks: stack.enter_context(patch.object(obj, name, side_effect=value))
        restart_login = stack.enter_context(patch.object(service, 'qualify_restart_logins', return_value={'passed': True, 'crossCredentialRejected': True}))
        return manager, calls, events, restart_login

    def test_first_start_checks_sealed_baseline_then_stops_exact_service_and_checkpoints_current_user_state(self):
        manager, calls, events, _ = self.lifecycle(); manager.run()
        manager.tools['inventory'].verify_closed_ownership.assert_called_once()
        self.assertEqual(manager.state['state'], 'STOPPED'); self.assertTrue(manager.state['retainedDatabaseFrozen'])
        self.assertEqual(service.bound(manager.state['stoppedFingerprint']), {'tables': 'user booking changes'})
        self.assertEqual(events, [False, True, False])
        self.assertEqual([call[-1] for call in calls if call[0] == 'stop'], ['c' * 64, 'd' * 64])
        self.assertFalse(any(call[0] in ('rm', 'volume') for call in calls))
        self.assertEqual([call.args[0] for call in manager.db.execute.call_args_list],
                         ['SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF', 'SET GLOBAL super_read_only=ON'])

    def test_normal_restart_uses_stopped_checkpoint_and_does_not_reimpose_original_owner_rules(self):
        manager, _, _, restart_login = self.lifecycle(restart=True); manager.run()
        manager.tools['inventory'].verify_closed_ownership.assert_not_called()
        manager.tools['accounts'].qualify_account_logins.assert_not_called(); restart_login.assert_called_once()
        self.assertEqual(manager.state['startupBaseline'], 'last successful stopped checkpoint')

    def test_checkpoint_drift_never_unfreezes_creates_or_promotes_the_changed_database(self):
        manager, calls, events, _ = self.lifecycle(restart=True, drift=True)
        original = copy.deepcopy(manager.state['stoppedFingerprint'])
        with self.assertRaises(ValueError): manager.run()
        self.assertFalse(any(call[0] in ('create', 'start') for call in calls)); self.assertNotIn(True, events)
        self.assertEqual(manager.state['stoppedFingerprint'], original)
        self.assertEqual(manager.state['state'], 'FAILED'); self.assertTrue(manager.state['retainedDatabaseFrozen'])
        self.assertTrue(all('OFF' not in call.args[0] for call in manager.db.execute.call_args_list))

    def test_monitor_failure_clears_availability_and_freezes_without_creating_a_successful_checkpoint(self):
        manager, calls, events, _ = self.lifecycle(monitor_failure=True)
        with self.assertRaises(OSError): manager.run()
        self.assertEqual(events[-1], False); self.assertEqual(manager.state['state'], 'FAILED')
        self.assertNotIn('stoppedFingerprint', manager.state)
        self.assertEqual([call[-1] for call in calls if call[0] == 'stop'], ['c' * 64, 'd' * 64])
        self.assertTrue(manager.state['retainedDatabaseFrozen']); self.assertFalse(manager.state['containerTermination']['app']['OOMKilled'])

    def test_failure_to_write_account_handoff_does_not_prevent_exact_stops_or_freeze(self):
        manager, calls, _, _ = self.lifecycle(mark_failure=True)
        manager.state['resources'] = {'app': {'id': 'c' * 64}, 'redis': {'id': 'd' * 64}}
        manager.shutdown()
        self.assertEqual([call[-1] for call in calls if call[0] == 'stop'], ['c' * 64, 'd' * 64])
        self.assertTrue(manager.state['retainedDatabaseFrozen']); self.assertEqual(manager.state['state'], 'FAILED')

    def test_early_input_failure_is_recorded_and_never_touches_a_database(self):
        manager = self.manager()
        with patch.object(manager, 'prepare_inputs', side_effect=ValueError('Unit changed completion receipt')):
            with self.assertRaises(ValueError): manager.run()
        self.assertEqual(manager.state['state'], 'FAILED'); self.assertFalse(manager.state['retainedDatabaseFrozen'])

    def test_engine_drift_prevents_stopping_any_container_or_freezing_the_wrong_database(self):
        manager, calls, _, _ = self.lifecycle()
        manager.state['resources'] = {'app': {'id': 'c' * 64}, 'redis': {'id': 'd' * 64}}
        with patch.object(service.restore, 'local_engine_identity', return_value=self.engine | {'engineId': 'other'}): manager.shutdown()
        self.assertFalse(any(call[0] == 'stop' for call in calls)); manager.db.execute.assert_not_called()
        self.assertFalse(manager.state['retainedDatabaseFrozen'])

    def test_service_lock_detects_live_supervisor_without_signalling_arbitrary_pid(self):
        manager = self.manager()
        self.assertFalse(service.supervisor_running(self.root))
        with service.file_lock(manager.private / 'supervisor.lock'):
            self.assertTrue(service.supervisor_running(self.root))
        self.assertFalse(service.supervisor_running(self.root))


class OperatorCommandTest(Fixture):
    def recorded(self, state='READY'):
        manager = self.manager()
        manager.state.update(state=state, runtime=str(manager.runtime), engine=self.engine, network=self.network,
                             environment=self.environment, url=manager.url,
                             resources={'app': {'id': 'c' * 64}, 'redis': {'id': 'd' * 64}})
        service.write(manager.state_path, manager.state)
        return manager

    def test_status_never_claims_ready_when_supervisor_is_dead(self):
        manager = self.recorded()
        with patch.object(service, 'recorded_manager', return_value=manager), patch.object(service, 'supervisor_running', return_value=False), \
             patch.object(service, 'stop', return_value={'state': 'FAILED', 'usable': False}) as stop:
            result = service.status(self.root)
        stop.assert_called_once(); self.assertFalse(result['usable'])

    def test_stop_dead_supervisor_stops_only_its_service_then_freezes_and_requires_checkpoint_review(self):
        manager = self.recorded(); db = MagicMock(); db.identity.return_value = {'uuid': 'unit-mysql-uuid', 'superReadOnly': 1}
        order = []
        def owned(*_, **kwargs): return {'State': {'Running': kwargs.get('running') is not False}}
        with patch.object(service, 'recorded_manager', return_value=manager), patch.object(service, 'supervisor_running', return_value=False), \
             patch.object(service, 'mark_accounts', side_effect=lambda *a, **k: order.append('unavailable')), \
             patch.object(service.restore, 'local_engine_identity', return_value=self.engine), patch.object(service, 'own_container', side_effect=owned), \
             patch.object(service.restore, 'docker', side_effect=lambda *args: order.append(args)), \
             patch.object(service, 'verify_data_container', return_value={'Id': '1' * 64}), patch.object(service.restore, 'Database', return_value=db):
            result = service.stop(self.root)
        self.assertEqual(order[0], 'unavailable')
        self.assertEqual([item[-1] for item in order[1:]], ['c' * 64, 'd' * 64])
        db.execute.assert_called_once_with('SET GLOBAL super_read_only=ON')
        self.assertEqual(result['state'], 'FAILED'); self.assertTrue(result['retainedDatabaseFrozen'])
        state = service.read(manager.state_path)
        self.assertTrue(state['recoveryRequired']); self.assertNotIn('stoppedFingerprint', state)

    def test_stop_rejects_foreign_container_but_still_stops_other_owned_service_and_preserves_database(self):
        manager = self.recorded(); db = MagicMock(); db.identity.return_value = {'uuid': 'unit-mysql-uuid', 'superReadOnly': 1}
        def owned(resource, *args, **kwargs):
            if resource['id'] == 'c' * 64: raise ValueError('Unit foreign ownership')
            return {'State': {'Running': kwargs.get('running') is not False}}
        with patch.object(service, 'recorded_manager', return_value=manager), patch.object(service, 'supervisor_running', return_value=False), \
             patch.object(service, 'mark_accounts'), patch.object(service.restore, 'local_engine_identity', return_value=self.engine), \
             patch.object(service, 'own_container', side_effect=owned), patch.object(service.restore, 'docker') as docker, \
             patch.object(service, 'verify_data_container', return_value={'Id': '1' * 64}), patch.object(service.restore, 'Database', return_value=db):
            result = service.stop(self.root)
        docker.assert_called_once_with('stop', '--time', '30', 'd' * 64)
        self.assertFalse(result['usable']); self.assertTrue(result['retainedDatabaseFrozen'])

    def test_stop_during_initial_validation_sets_cancellation_without_creating_or_deleting_anything(self):
        manager = self.manager(); manager.event('START_REQUESTED', usable=False)
        result = service.stop(self.root)
        self.assertEqual(result['state'], 'STOPPING'); self.assertTrue(manager.requested_stop())
        self.assertEqual(service.status(self.root)['state'], 'START_REQUESTED')

    def test_incomplete_start_is_not_automatically_retried(self):
        manager = self.manager(); manager.event('FAILED', usable=False)
        with patch.object(service.subprocess, 'Popen', side_effect=AssertionError('No duplicate supervisor')) as process:
            with self.assertRaises(ValueError): service.start(manager.state['configPath'], manager.state['configSha256'], self.root)
        process.assert_not_called()


if __name__ == '__main__': unittest.main()
