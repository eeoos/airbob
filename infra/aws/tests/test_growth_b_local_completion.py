"""Offline local-completion boundaries; every Docker/DB/Java call is mocked."""
import contextlib
import copy
import datetime as dt
import importlib.util
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
_spec = importlib.util.spec_from_file_location('tested_local_completion', ROOT / 'scripts/complete-growth-b-local.py')
completion = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(completion)


def put(path, value):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + '\n')
    path.chmod(0o600)
    return completion.binding(path)


def input_fixture(root):
    """Small synthetic metadata only, never a deployable/final-qualified dataset."""
    root = Path(root)
    release = root / 'release'; release.mkdir(mode=0o700)
    jar = root / 'app.jar'; jar.write_bytes(b'offline-fixture-app')
    java = root / 'jdk'; java.mkdir()
    dump_sha = 'd' * 64
    receipt = {'state': 'DATASET_DB_AND_HTTP_QUALIFIED', 'finalScaleSelected': True,
        'datasetScale': completion.contract.FINAL_SCALE, 'stages': sorted(completion.FINAL_STAGES),
        'imageId': 'sha256:' + 'a' * 64, 'timezoneRuntimeQualified': True, 'dumpSha256': dump_sha,
        'databaseLifetime': {'policy': 'sequential-single-database', 'maximumConcurrentDatasetSchemas': 1,
                             'plaintextTemporaryDumpBytes': 0}}
    put(release / 'qualification.json', receipt)
    consumer = put(release / 'consumer-manifest.json', {'finalScaleSelected': True,
        'datasetScale': completion.contract.FINAL_SCALE, 'datasetId': 'global-growth-b-' + 'd' * 16})
    checks = put(release / 'SHA256SUMS.json', {'airbob-growth.sql.gz': dump_sha})
    tool_binding = put(release / 'tool-sources.json', {'verify-growth-roundtrip.py': '9' * 64})
    put(release / 'runtime-database-measurement.json', {'allocatedTablespaceBytes': 10 * completion.GIB})
    config = {'schemaVersion': 1, 'kind': completion.KIND + '-config', 'datasetId': 'global-growth-b-' + 'd' * 16,
        'release': str(release), 'consumerManifestSha256': consumer['sha256'], 'checksumsSha256': checks['sha256'],
        'appJar': str(jar), 'appJarSha256': completion.contract.sha(jar),
        'roundtripReceipt': put(root / 'roundtrip.json', receipt), 'toolSourcesSha256': tool_binding['sha256'],
        'namespace': 'airbob-b-local-unit-01',
        'engine': {'context': 'desktop-linux', 'configuredEndpoint': 'unix:///unit.sock', 'endpoint': 'unix:///unit.sock',
                   'engineId': 'unit-engine', 'dockerRootDir': '/var/lib/docker'},
        'images': {'mysql': receipt['imageId'], 'redis': 'sha256:' + 'b' * 64, 'elasticsearch': completion.ES_IMAGE},
        'ports': {'mysql': 13306, 'producerElasticsearch': 19200, 'serviceElasticsearch': 19201},
        'runtime': {'javaHome': str(java), 'mysqlMemoryMiB': 2048, 'mysqlBufferPoolMiB': 1024,
                    'elasticsearchMemoryMiB': 2048, 'elasticsearchHeapMiB': 1024},
        'capacity': {key: 10 * completion.GIB if key == 'requiredDatabaseBytes' else 2 * completion.GIB
                     for key in completion.CAPACITY},
        'timeouts': {'mysqlOperationSeconds': 7200, 'appStartupSeconds': 3600, 'searchSourceSeconds': 7200,
                     'snapshotSeconds': 7200, 'esStartupSeconds': 180}, 'writerPorts': [18080]}
    return config


def resource_fixture(config, network, role):
    mysql = role == 'mysql'
    names = completion.planned_names(config)
    claim = '3' * 32 if mysql else network['claim']
    label = {'airbob.dataset.id': config['datasetId'], completion.CLAIM: claim}
    if not mysql:
        label[completion.ROLE] = role
    name = names[role]
    return {'container': name, 'containerId': ('4' if mysql else '5' if role == 'producer' else '6') * 64,
        'image': config['images']['mysql' if mysql else 'elasticsearch'], 'volume': names[role + 'Volume'],
        'volumeClaim': claim, 'volumeCreatedAt': '2026-09-11T07:05:00Z',
        'containerLabels': {'airbob.dataset.id': config['datasetId']} if mysql else label,
        'volumeLabels': label, 'role': role,
        'dataPath': '/var/lib/mysql' if mysql else '/usr/share/elasticsearch/data',
        'internalPort': 3306 if mysql else 9200,
        'port': config['ports']['mysql' if mysql else 'producerElasticsearch' if role == 'producer' else 'serviceElasticsearch'],
        'memoryMiB': config['runtime']['mysqlMemoryMiB' if mysql else 'elasticsearchMemoryMiB'],
        'networkAlias': 'mysql' if mysql else 'producer-elasticsearch' if role == 'producer' else 'elasticsearch',
        **({'mysqlServerUuid': '11111111-2222-4333-8444-555555555555'} if mysql else
           {'clusterUuid': role + '-cluster', 'url': 'http://127.0.0.1:' + str(config['ports'][
               'producerElasticsearch' if role == 'producer' else 'serviceElasticsearch'])})}


def inspect_fixture(resource, network, native=None):
    mount = {'Type': 'volume', 'Name': resource['volume'], 'Destination': resource['dataPath'], 'RW': True}
    mounts = [mount]
    if native is not None:
        mounts.append({'Type': 'bind', 'Source': str(native), 'Destination': '/backup', 'RW': resource['role'] == 'producer'})
    ports = {str(resource['internalPort']) + '/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(resource['port'])}]}
    container = {'Id': resource['containerId'], 'Name': '/' + resource['container'], 'Image': resource['image'],
        'Config': {'Labels': resource['containerLabels'], 'Env': ['MYSQL_ROOT_PASSWORD=unit-only-secret']},
        'State': {'Running': True}, 'Mounts': mounts,
        'HostConfig': {'Privileged': False, 'NetworkMode': network['name'], 'Memory': resource['memoryMiB'] * 1024**2,
                       'PortBindings': copy.deepcopy(ports)},
        'NetworkSettings': {'Ports': copy.deepcopy(ports), 'Networks': {network['name']: {
            'NetworkID': network['id'], 'Aliases': [resource['networkAlias']]}}}}
    volume = {'Name': resource['volume'], 'Driver': 'local', 'Options': None,
              'Labels': resource['volumeLabels'], 'CreatedAt': resource['volumeCreatedAt']}
    return container, volume


def fingerprint_fixture():
    return {'algorithm': 'sha256-pk-order-length-prefixed-jdbc-bytes-v1', 'mysqlVersion': '8.4.11',
        'domainHashExcludedColumns': {'member': ['password']},
        'tables': {name: {'rows': 28 if name == 'flyway_schema_history' else 0 if name == 'outbox' else 1,
                         'rowsSha256': '1' * 64, 'domainRowsSha256': '2' * 64, 'ddlSha256': '3' * 64}
                   for name in completion.contract.TABLES}}


def completion_fixture(root, *, app_jar=None):
    """Emit the real completion_payload schema for the independent serve input test.

    This is explicitly synthetic offline metadata. Optional app_jar avoids replacing
    the service tool's fixed JAR anchor when a caller has its real local artifact.
    """
    root = Path(root)
    config = input_fixture(root)
    if app_jar is not None:
        config.update(appJar=str(Path(app_jar).resolve()), appJarSha256=completion.contract.sha(app_jar))
    config_binding = put(root / 'config.json', config)
    output = root / 'output'; output.mkdir(mode=0o700)
    resources = completion.Resources(config, output)
    resources.claim = '2' * 32
    resources.network = {'name': resources.names['network'], 'id': '1' * 64, 'claim': resources.claim}
    resources.observer = {'name': resources.names['observer'], 'id': '7' * 64}
    resources.mysql = resource_fixture(config, resources.network, 'mysql')
    resources.es = resource_fixture(config, resources.network, 'service')
    producer = resource_fixture(config, resources.network, 'producer')
    fresh = completion.fresh_config(config, resources, output)
    put(output / 'restore-config.json', fresh)
    private = output / 'restore/.private'; private.mkdir(mode=0o700, parents=True)
    service_env = private / 'service.env'; service_env.write_text('UNIT_FIXTURE=true\n'); service_env.chmod(0o600)
    put(Path(fresh['privateAccounts']), {'offlineFixtureOnly': True})
    restored = {'schemaVersion': 1, 'kind': 'global-b-restore', 'operation': completion.restore.FRESH_OPERATION,
        'state': 'PREPARED_APP_STOPPED_DATABASE_FROZEN', 'datasetId': config['datasetId'],
        'allRowsAndDdlEqual': True, 'databaseFrozen': True, 'preparation': {'passed': True},
        'targetContainerId': resources.mysql['containerId'], 'mysqlServerUuid': resources.mysql['mysqlServerUuid'],
        'serviceEnvironmentFile': str(service_env),
        'environment': {'engine': {key: value for key, value in config['engine'].items() if key != 'configuredEndpoint'}}}
    put(output / 'restore/restore.json', restored)
    prepared = fingerprint_fixture()
    put(output / 'restore/prepared-fingerprint.json', prepared)
    projection = {'documents': 1, 'mappingSha256': 'a' * 64, 'contentSha256': 'b' * 64,
                  'identityPairsSha256': 'c' * 64, 'indexSemanticsSha256': 'd' * 64}
    native = {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'datasetId': config['datasetId'],
        'appJarSha256': config['appJarSha256'], 'allDocumentSourceFieldsEqual': True,
        'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True,
        'previousIndexRetained': None, 'activeAlias': 'accommodations-voffline-fixture',
        'restoredIndex': 'accommodations-voffline-fixture', 'fullDocumentFingerprint': projection,
        'mysql': {'serverUuid': resources.mysql['mysqlServerUuid']},
        'elasticsearch': {'clusterUuid': resources.es['clusterUuid'], 'imageId': resources.es['image']}}
    put(output / 'search-restore/search-restore-receipt.json', native)
    preflight = put(output / 'restore-preflight.json', {'state': 'PREFLIGHT_READY'})
    seal = put(output / 'producer-removal-seal.json', {'offlineFixtureOnly': True})
    for name in ('native-companion-descriptor.json', 'host-runtime-qualification.json', 'measurements.json'):
        put(output / name, {'offlineFixtureOnly': True})
    now = dt.datetime(2026, 9, 11, 7, 5, tzinfo=dt.timezone.utc)
    result = completion.completion_payload(config, output, config_binding, resources, fresh, restored, preflight,
                                           producer, native, seal, completion.source_bindings(), now, now)
    receipt_binding = put(output / 'completion-receipt.json', result)
    return {'config': config, 'output': output, 'completion': result, 'completionBinding': receipt_binding,
            'fresh': fresh, 'restore': restored, 'resources': resources, 'prepared': prepared, 'native': native,
            'appJarSha256': config['appJarSha256']}


class Fixture(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.config = input_fixture(self.root)
        self.output = self.root / 'out'; self.output.mkdir(mode=0o700)
        self.resources = completion.Resources(self.config, self.output)
        self.resources.claim = '2' * 32
        self.resources.network = {'name': self.resources.names['network'], 'id': '1' * 64, 'claim': self.resources.claim}
        self.mysql = resource_fixture(self.config, self.resources.network, 'mysql')
        self.producer = resource_fixture(self.config, self.resources.network, 'producer')
        self.service = resource_fixture(self.config, self.resources.network, 'service')
        self.native = self.output / 'native-repository'
        self.docker = patch.object(completion.restore, 'docker', side_effect=AssertionError('Docker is forbidden in this offline test')).start()
        self.addCleanup(patch.stopall)

    def configured(self, mutate=None):
        value = copy.deepcopy(self.config)
        if mutate:
            mutate(value)
        bound = put(self.root / 'config.json', value)
        return completion.configuration(bound['path'], bound['sha256'])


class InputsTests(Fixture):
    def test_exact_config_accepts_only_full_local_scope(self):
        self.assertEqual(self.config, self.configured())

    def test_configuration_anchor_rejects_changed_bytes(self):
        bound = put(self.root / 'config.json', self.config)
        Path(bound['path']).write_text('{}')
        with self.assertRaisesRegex(ValueError, 'SHA'):
            completion.configuration(bound['path'], bound['sha256'])

    def test_foreign_unsafe_or_unbounded_configuration_rejected(self):
        mutations = [lambda c: c.update(password='secret'), lambda c: c.update(allowSmall=True),
            lambda c: c.update(namespace='mysql'), lambda c: c['images'].update(elasticsearch='sha256:' + '0' * 64),
            lambda c: c['images'].update(mysql='mysql:8.4.11'), lambda c: c['engine'].update(endpoint='tcp://remote:2375'),
            lambda c: c['ports'].update(mysql=c['ports']['serviceElasticsearch']),
            lambda c: c['runtime'].update(elasticsearchHeapMiB=2048),
            lambda c: c['capacity'].update(reserveDockerBytes=0),
            lambda c: c['timeouts'].update(snapshotSeconds=999999),
            lambda c: c.update(appJar=str(self.root / 'missing.jar'))]
        for mutate in mutations:
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                self.configured(mutate)

    def test_symlink_destination_rejected(self):
        link = self.root / 'linked'; link.symlink_to(self.output, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'Symlink'):
            completion.safe_path(str(link / 'new'), exists=False)

    def validate(self):
        manifest = completion.contract.read(Path(self.config['release']) / 'consumer-manifest.json')
        checks = {'airbob-growth.sql.gz': 'd' * 64}
        with patch.object(completion.contract, 'validate', return_value=(manifest, checks, {})) as validate:
            result = completion.validate_final(self.config)
            self.assertIs(validate.call_args.kwargs['allow_small'], False)
            self.assertEqual(validate.call_args.kwargs['expected_app_sha'], self.config['appJarSha256'])
            return result

    def test_final_population_gate_never_enables_small(self):
        self.assertTrue(self.validate()[0]['finalScaleSelected'])

    def test_running_pilot_small_and_retained_receipts_rejected(self):
        original = completion.contract.read(self.config['roundtripReceipt']['path'])
        for change in ({'state': 'RUNNING'}, {'state': 'SMALL_DB_AND_HTTP_QUALIFIED'},
                       {'state': 'PILOT_DB_AND_HTTP_QUALIFIED'}, {'finalScaleSelected': False},
                       {'retainedDatabases': [{'container': 'owned-but-retained'}]},
                       {'timezoneRuntimeQualified': False}, {'stages': []},
                       {'imageId': 'sha256:' + '0' * 64}):
            with self.subTest(change=change):
                value = original | change
                self.config['roundtripReceipt'] = put(self.root / 'roundtrip.json', value)
                put(Path(self.config['release']) / 'qualification.json', value)
                with self.assertRaises(ValueError):
                    self.validate()

    def test_sealed_and_external_roundtrip_must_be_identical(self):
        value = completion.contract.read(self.config['roundtripReceipt']['path']) | {'differentExecution': True}
        self.config['roundtripReceipt'] = put(self.root / 'roundtrip.json', value)
        with self.assertRaisesRegex(ValueError, 'same-tool'):
            self.validate()

    def test_database_budget_below_actual_full_allocation_rejected(self):
        self.config['capacity']['requiredDatabaseBytes'] = completion.GIB
        with self.assertRaisesRegex(ValueError, 'measured'):
            self.validate()

    def test_runtime_requires_actual_local_qualification_and_exact_patch_tzdb(self):
        java = {'javaVersion': '21.0.12.1', 'javaRuntimeVersion': '21.0.12.1+1-LTS', 'javaVendor': 'Eclipse Adoptium',
                'tzdbFile': {'sha256': 'a' * 64}}
        put(Path(self.config['release']) / 'timezone-qualification.json', {'java': java})
        completion.assert_runtime({'consumerRuntimePassed': True, 'java': java}, self.config['release'])
        for changed in (java | {'javaVersion': '21.0.6'}, java | {'tzdbFile': {'sha256': 'b' * 64}}):
            with self.assertRaises(ValueError):
                completion.assert_runtime({'consumerRuntimePassed': True, 'java': changed}, self.config['release'])

    def test_offline_output_cannot_replace_existing_artifact(self):
        path = self.output / 'proof.json'
        completion.write_new(path, {'first': True})
        with self.assertRaises(FileExistsError):
            completion.write_new(path, {'second': True})
        self.assertEqual(completion.contract.read(path), {'first': True})


class OwnershipTests(Fixture):
    def test_matching_owned_resource_and_readonly_target_pass(self):
        for resource in (self.mysql, self.producer, self.service):
            native = None if resource['role'] == 'mysql' else self.native
            info, volume = inspect_fixture(resource, self.resources.network, native)
            completion.verify_resource(resource, info, volume, self.resources.network, native)

    def test_foreign_id_image_claim_mount_or_network_rejected(self):
        mutations = [lambda c, v: c.update(Id='0' * 64), lambda c, v: c.update(Image='sha256:' + '0' * 64),
            lambda c, v: c['Config'].update(Labels={}), lambda c, v: v.update(Labels={}),
            lambda c, v: v.update(CreatedAt='different'), lambda c, v: v.update(Options={'device': '/other'}),
            lambda c, v: c['Mounts'][0].update(Name='another-volume'),
            lambda c, v: c['Mounts'].append({'Type': 'volume', 'Name': 'shared', 'Destination': '/shared', 'RW': True}),
            lambda c, v: c['Mounts'][1].update(RW=True),
            lambda c, v: c['HostConfig'].update(NetworkMode='host'),
            lambda c, v: c['NetworkSettings']['Networks'].update(other={}),
            lambda c, v: c['NetworkSettings']['Ports']['9200/tcp'][0].update(HostIp='0.0.0.0')]
        for mutate in mutations:
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                info, volume = copy.deepcopy(inspect_fixture(self.service, self.resources.network, self.native))
                mutate(info, volume)
                completion.verify_resource(self.service, info, volume, self.resources.network, self.native)

    def test_stopped_producer_still_needs_exact_host_bindings(self):
        info, volume = inspect_fixture(self.producer, self.resources.network, self.native)
        info['State']['Running'] = False; info['NetworkSettings']['Ports'] = {}
        info['NetworkSettings']['Networks'] = {}
        completion.verify_resource(self.producer, info, volume, self.resources.network, self.native)
        info['HostConfig']['PortBindings']['9200/tcp'][0]['HostIp'] = '0.0.0.0'
        with self.assertRaises(ValueError):
            completion.verify_resource(self.producer, info, volume, self.resources.network, self.native)

    def test_stopped_producer_accepts_torn_down_endpoint_but_keeps_persistent_claims(self):
        network = self.resources.network
        for attached in ({}, {network['name']: {'NetworkID': '', 'Aliases': None}},
                         {network['name']: {'NetworkID': network['id'], 'Aliases': None}}):
            info, volume = inspect_fixture(self.producer, network, self.native)
            info['State']['Running'] = False
            info['NetworkSettings'].update(Ports={}, Networks=attached)
            completion.verify_resource(self.producer, info, volume, network, self.native)
            for mutation in (lambda c, v: c['HostConfig'].update(NetworkMode='other-network'),
                             lambda c, v: c['Config'].update(Labels={}),
                             lambda c, v: v.update(Labels={}),
                             lambda c, v: c['Mounts'][0].update(Name='other-volume')):
                changed, changed_volume = copy.deepcopy((info, volume))
                mutation(changed, changed_volume)
                with self.subTest(attached=attached, mutation=mutation), self.assertRaises(ValueError):
                    completion.verify_resource(self.producer, changed, changed_volume, network, self.native)

    def test_running_producer_cannot_use_stopped_endpoint_exception(self):
        info, volume = inspect_fixture(self.producer, self.resources.network, self.native)
        info['NetworkSettings']['Networks'] = {}
        with self.assertRaisesRegex(ValueError, 'running resource'):
            completion.verify_resource(self.producer, info, volume, self.resources.network, self.native)

    def test_initial_existing_container_rejects_before_creation(self):
        with patch.object(self.resources, 'engine'), patch.object(completion.restore, 'existing', return_value={'existing-db'}):
            with self.assertRaisesRegex(ValueError, 'absent'):
                self.resources.empty()
        self.docker.assert_not_called()

    def test_only_sealed_exact_producer_can_be_removed(self):
        self.resources.es = self.service
        with self.assertRaisesRegex(ValueError, 'Service ES'):
            self.resources.remove_producer(self.native, {})
        self.resources.es = self.producer
        with patch.object(completion, 'verify_producer_seal', return_value={'producer': self.producer | {'containerId': '9' * 64}}):
            with self.assertRaisesRegex(ValueError, 'another producer'):
                self.resources.remove_producer(self.native, {})
        self.docker.assert_not_called()

    def test_producer_seal_failure_does_not_stop_or_remove_it(self):
        self.resources.es = self.producer
        with patch.object(completion, 'verify_producer_seal', side_effect=ValueError('native bytes changed')):
            with self.assertRaisesRegex(ValueError, 'native bytes'):
                self.resources.remove_producer(self.native, {})
        self.docker.assert_not_called()

    def test_validated_producer_removal_targets_only_its_exact_id_and_sole_volume(self):
        self.resources.mysql = self.mysql; self.resources.es = self.producer
        info, volume = inspect_fixture(self.producer, self.resources.network, self.native)
        mysql_info, _ = inspect_fixture(self.mysql, self.resources.network)
        removed = {'container': False, 'volume': False}
        commands = []
        def docker(*args, **kwargs):
            commands.append(args)
            if args[0] == 'stop':
                self.assertEqual(args[-1], self.producer['containerId'])
                info['State']['Running'] = False
                info['NetworkSettings']['Ports'] = {}
            elif args[0] == 'rm':
                self.assertEqual(args, ('rm', self.producer['containerId'])); removed['container'] = True
            elif args[:2] == ('volume', 'rm'):
                self.assertEqual(args, ('volume', 'rm', self.producer['volume'])); removed['volume'] = True
            else:
                self.fail('Unexpected command: ' + repr(args))
            return b''
        def existing(kind):
            return ({self.mysql['containerId']} | (set() if removed['container'] else {self.producer['containerId']})) if kind == 'container' else \
                   ({self.mysql['volume'], 'unrelated-existing-volume'} | (set() if removed['volume'] else {self.producer['volume']}))
        def inspect(kind, identifier):
            if kind == 'volume':
                self.assertEqual(identifier, self.producer['volume']); return volume
            return mysql_info if identifier == self.mysql['containerId'] else info
        with patch.object(completion, 'verify_producer_seal', return_value={'producer': self.producer}), \
             patch.object(self.resources, 'exclusive'), patch.object(self.resources, 'verify_mysql'), \
             patch.object(completion.restore, 'existing', side_effect=existing), \
             patch.object(completion.restore, 'inspect', side_effect=inspect), \
             patch.object(completion.restore, 'docker', side_effect=docker):
            self.resources.remove_producer(self.native, {'path': 'unit-seal', 'sha256': '9' * 64})
        self.assertEqual([row[0] for row in commands], ['stop', 'rm', 'volume'])
        self.assertIsNone(self.resources.es)
        self.assertEqual(self.resources.mysql, self.mysql)

    def test_shared_producer_volume_rejected_without_stopping_anything(self):
        self.resources.mysql = self.mysql; self.resources.es = self.producer
        info, volume = inspect_fixture(self.producer, self.resources.network, self.native)
        foreign = {'Mounts': [{'Type': 'volume', 'Name': self.producer['volume']}]}
        def inspect(kind, identifier):
            return volume if kind == 'volume' else info if identifier == self.producer['containerId'] else foreign
        with patch.object(completion, 'verify_producer_seal', return_value={'producer': self.producer}), \
             patch.object(self.resources, 'exclusive'), patch.object(self.resources, 'verify_mysql'), \
             patch.object(completion.restore, 'existing', return_value={self.producer['containerId'], 'other'}), \
             patch.object(completion.restore, 'inspect', side_effect=inspect):
            with self.assertRaisesRegex(ValueError, 'another user'):
                self.resources.remove_producer(self.native, {})
        self.docker.assert_not_called()

    def test_ownership_change_prevents_failure_freeze_of_foreign_database(self):
        self.resources.mysql = self.mysql
        info, volume = inspect_fixture(self.mysql, self.resources.network)
        info['Id'] = '9' * 64
        with patch.object(self.resources, 'engine'), patch.object(completion.restore, 'inspect', side_effect=[info, volume]), \
             patch.object(completion.restore, 'Database') as database:
            result = completion.freeze_on_failure(self.resources)
        self.assertFalse(result['frozen']); database.assert_not_called(); self.docker.assert_not_called()

    def test_owned_failure_database_frozen_without_cleanup(self):
        self.resources.mysql = self.mysql
        info, volume = inspect_fixture(self.mysql, self.resources.network)
        db = MagicMock()
        db.identity.side_effect = [{'uuid': self.mysql['mysqlServerUuid'], 'superReadOnly': 0},
                                  {'uuid': self.mysql['mysqlServerUuid'], 'superReadOnly': 1, 'readOnly': 1}]
        with patch.object(self.resources, 'engine'), patch.object(completion.restore, 'inspect', side_effect=[info, volume]), \
             patch.object(completion.restore, 'Database', return_value=db):
            result = completion.freeze_on_failure(self.resources)
        self.assertTrue(result['frozen']); db.execute.assert_called_once_with('SET GLOBAL super_read_only=ON')
        self.docker.assert_not_called()


class AdapterAndMeasurementTests(Fixture):
    def test_trusted_baseline_runs_frozen_before_credentials_without_es(self):
        self.resources.observer = {'name': 'owned-observer', 'id': '7' * 64}
        config = completion.fresh_config(self.config, self.resources, self.output)
        info, volume = inspect_fixture(self.mysql, self.resources.network)
        db = MagicMock()
        db.identity.return_value = {'version': '8.4.11', 'uuid': self.mysql['mysqlServerUuid'], 'readOnly': 1, 'superReadOnly': 1}
        seen = []
        def capture(source, output):
            seen.append(source)
            self.assertEqual(os.environ[completion.PASSWORD_ENV], 'unit-only-secret')
            self.assertNotIn('elasticsearch', source)
            self.assertIs(source['allowSmallQualification'], False)
            put(output / 'baseline-receipt.json', {'unit': True})
            put(output / 'mysql-baseline-fingerprint.json', {'unit': True})
        with patch.object(completion.restore, 'inspect', return_value=volume), \
             patch.object(completion.search, 'capture_baseline', side_effect=capture), \
             patch.dict(os.environ, {completion.PASSWORD_ENV: 'previous-value'}):
            artifacts = completion.capture_search_baseline(config, self.output, self.output / 'runtime', info, db)
            self.assertEqual(os.environ[completion.PASSWORD_ENV], 'previous-value')
        self.assertEqual(len(seen), 1)
        self.assertEqual(set(artifacts), {'searchBaselineReceipt', 'searchBaselineFingerprint', 'mysqlBeforePreparation'})
        self.assertNotIn('unit-only-secret', (self.output / 'search-baseline-config.json').read_text())
        db.execute.assert_not_called()

    def test_unfrozen_baseline_is_rejected_before_search_or_credentials(self):
        db = MagicMock(); db.identity.return_value = {'readOnly': 0, 'superReadOnly': 0}
        with patch.object(completion.search, 'capture_baseline') as capture:
            with self.assertRaisesRegex(ValueError, 'Freeze'):
                completion.capture_search_baseline({}, self.output, None, {}, db)
            capture.assert_not_called(); db.execute.assert_not_called()

    def test_service_config_uses_os_readonly_mount_without_repository_readonly_setting(self):
        self.resources.observer = {'name': 'disk', 'id': '7' * 64}
        fresh = completion.fresh_config(self.config, self.resources, self.output)
        info, _ = inspect_fixture(self.mysql, self.resources.network)
        value, password = completion.search_config(self.config, fresh, info, {'uuid': self.mysql['mysqlServerUuid']},
            {'url': self.service['url']}, self.native)
        self.assertEqual(value['repository']['settings'], {'location': '/backup'})
        self.assertEqual(value['repository']['type'], 'fs')
        self.assertEqual(value['mysql']['passwordEnvironment'], completion.PASSWORD_ENV)
        self.assertNotIn(password, json.dumps(value))
        self.assertIs(value['allowSmallQualification'], False)

    def test_different_cluster_same_mysql_full_mapping_and_fields_required(self):
        projection = {'documents': 2, 'contentSha256': '1' * 64, 'mappingSha256': '2' * 64,
                      'identityPairsSha256': '3' * 64, 'indexSemanticsSha256': '4' * 64}
        value = {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'allDocumentSourceFieldsEqual': True,
                 'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True,
                 'previousIndexRetained': None, 'elasticsearch': {'clusterUuid': self.service['clusterUuid'], 'imageId': self.service['image']},
                 'mysql': {'serverUuid': self.mysql['mysqlServerUuid']}, 'fullDocumentFingerprint': projection,
                 'activeAlias': 'accommodations-vunit', 'restoredIndex': 'accommodations-vunit'}
        reference = {'fingerprint': projection}
        completion.verify_native_result(value, reference, self.producer, self.service, self.mysql)
        mutations = [lambda v: v['elasticsearch'].update(clusterUuid=self.producer['clusterUuid']),
                     lambda v: v['mysql'].update(serverUuid='other-server'),
                     lambda v: v['fullDocumentFingerprint'].update(mappingSha256='0' * 64),
                     lambda v: v['fullDocumentFingerprint'].update(contentSha256='0' * 64),
                     lambda v: v.update(activeAlias='accommodations-vother'),
                     lambda v: v.update(repositoryReadOnly=False), lambda v: v.update(previousIndexRetained='old-index')]
        for mutate in mutations:
            current = copy.deepcopy(value); mutate(current)
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                completion.verify_native_result(current, reference, self.producer, self.service, self.mysql)

    def test_budget_accounts_for_host_native_and_scratch_without_reclaim_credit(self):
        capacity = self.config['capacity']
        docker = capacity['requiredDatabaseBytes'] + max(capacity['requiredProducerEsBytes'], capacity['requiredServiceEsBytes'])
        host = docker + capacity['requiredNativeRepositoryBytes'] + capacity['requiredScratchBytes']
        value = completion.capacity_gate(capacity, host + capacity['reserveHostBytes'], docker + capacity['reserveDockerBytes'], 'initial')
        self.assertEqual(value['budgetedAdditionalHostBytes'], host)
        self.assertEqual(value['reclaimableBytesCredited'], 0)
        with self.assertRaises(ValueError):
            completion.capacity_gate(capacity, host + capacity['reserveHostBytes'] - 1, 100 * completion.GIB, 'initial')

    def test_native_size_measurement_does_not_follow_symlinks(self):
        self.native.mkdir(); (self.native / 'file').write_bytes(b'native')
        self.assertEqual(completion.tree_size(self.native)['logicalBytes'], 6)
        (self.native / 'link').symlink_to(self.root / 'app.jar')
        with self.assertRaisesRegex(ValueError, 'non-regular'):
            completion.tree_size(self.native)

    def test_sampled_peak_and_reserve_are_separate_and_phase_clock_is_recorded(self):
        observer = completion.Measurements(self.output, self.config['capacity'])
        with patch.object(completion.shutil, 'disk_usage', return_value=SimpleNamespace(free=100 * completion.GIB)):
            with observer.stage('offline-sampled-phase'):
                pass
        result = observer.finish()
        self.assertEqual(result['sampleCount'], 2)
        self.assertTrue(result['samplingComplete'])
        self.assertIsNone(result['minimumObservedDockerFreeBytes'])
        self.assertIn('startedUtcDate', result['phases'][0])
        self.assertIn('elapsedSeconds', result['phases'][0])
        self.assertIn('not exact peaks', result['interpretation'])


class HandoffTests(unittest.TestCase):
    def test_actual_payload_exports_canonical_exact_retained_resources_and_private_paths_only(self):
        with tempfile.TemporaryDirectory() as directory:
            fixture = completion_fixture(Path(directory).resolve())
            value = fixture['completion']
            self.assertEqual(value['state'], completion.STATE)
            self.assertEqual(value['kind'], completion.KIND)
            self.assertEqual(value['network']['claim'], value['elasticsearch']['volumeClaim'])
            self.assertEqual(value['mysql']['networkAlias'], 'mysql')
            self.assertEqual(value['elasticsearch']['networkAlias'], 'elasticsearch')
            self.assertFalse(value['applicationStartedByCompletion'])
            self.assertTrue(value['databaseFrozen'])
            self.assertFalse(value['cloudExecution'])
            self.assertEqual(value['accountEnvironment'], 'local:' + value['mysql']['container'] + ':' + value['datasetId'])
            for key in ('inputConfig', 'restoreConfig', 'restoreReceipt', 'preparedFingerprint', 'nativeDescriptor', 'measurements'):
                self.assertEqual(completion.binding(value[key]['path']), value[key])
            self.assertEqual(completion.contract.read(value['restoreReceipt']['path'])['environment']['engine'],
                {key: item for key, item in fixture['config']['engine'].items() if key != 'configuredEndpoint'})
            self.assertIsInstance(value['privateAccounts'], str)
            self.assertIsInstance(value['serviceEnvironmentFile'], str)
            self.assertNotIn('unit-only-secret', json.dumps(value))


class OrchestrationTests(Fixture):
    """Run the real orchestration with isolated dependency doubles, no processes."""
    def run_pipeline(self, fail_produce=False):
        module = completion
        config = self.config
        output = self.output
        events = []
        resources = self.resources
        resources.network = {'name': resources.names['network'], 'id': '1' * 64, 'claim': resources.claim}
        resources.observer = {'name': resources.names['observer'], 'id': '7' * 64}
        mysql = self.mysql
        identity = {'version': '8.4.11', 'uuid': mysql['mysqlServerUuid'], 'readOnly': 1, 'superReadOnly': 1}
        db = MagicMock(); db.identity.return_value = identity
        mysql_info, _ = inspect_fixture(mysql, resources.network)
        prepared = fingerprint_fixture()
        projection = {'documents': 1, 'contentSha256': 'a' * 64, 'mappingSha256': 'b' * 64,
                      'identityPairsSha256': 'c' * 64, 'indexSemanticsSha256': 'd' * 64}
        reference = {'fingerprint': projection}
        fake_es = MagicMock()
        fake_es.alias.return_value = 'accommodations-vpipeline'
        fake_es.identity.return_value = {'clusterUuid': self.service['clusterUuid']}
        sampler = MagicMock()
        sampler.lock = contextlib.nullcontext()
        sampler.finish.return_value = {'samplingComplete': True, 'sampleCount': 1}
        sampler.stage.side_effect = lambda name: contextlib.nullcontext()
        def gate(phase):
            put(output / ('capacity-' + phase + '.json'), {'phase': phase})
        sampler.gate.side_effect = gate
        def extract(release, destination):
            destination.mkdir(mode=0o700); return destination
        def runtime(*args, **kwargs):
            put(output / 'host-runtime-qualification.json', {'consumerRuntimePassed': True})
            return {'consumerRuntimePassed': True}
        def apply(fresh, plan, restored, runtime, *, before_preparation):
            events.append('fresh-apply')
            self.assertIs(before_preparation, module.capture_search_baseline)
            self.assertIs(fresh['allowSmall'], False)
            self.assertNotIn('source', fresh)
            put(restored / 'prepared-fingerprint.json', prepared)
            return {'state': 'PREPARED_APP_STOPPED_DATABASE_FROZEN', 'databaseFrozen': True,
                    'allRowsAndDdlEqual': True, 'preparation': {'passed': True}, 'datasetId': config['datasetId'],
                    'targetContainerId': mysql['containerId'], 'mysqlServerUuid': mysql['mysqlServerUuid'],
                    'serviceEnvironmentFile': str(restored / '.private/service.env')}
        def adopt(receipt):
            resources.mysql = mysql; events.append('retained-mysql')
        def start_es(role, native):
            self.assertIsNotNone(resources.mysql)
            resources.es = copy.deepcopy(self.producer if role == 'producer' else self.service)
            events.append(role + '-start')
            return {'url': resources.es['url'], 'container': resources.es['container'], 'image': resources.es['image']}, \
                   fake_es, {'clusterUuid': resources.es['clusterUuid']}
        def produce(source, baseline, companion, *, build_index):
            self.assertTrue(build_index); self.assertIs(source['allowSmallQualification'], False)
            self.assertEqual(os.environ[module.PASSWORD_ENV], 'unit-only-secret')
            events.append('full-produce')
            if fail_produce:
                raise RuntimeError('secret value must never be serialized')
            put(companion / 'snapshot-reference.json', reference)
            return {'offlineDescriptor': True}
        def seal(*args):
            events.append('sealed')
            return put(output / 'producer-removal-seal.json', {'state': 'unit-sealed'})
        def remove(native, sealed):
            self.assertEqual(events[-1], 'sealed')
            events.append('producer-remove')
            resources.es = None
            return self.producer
        def restore_native(source, companion, descriptor, baseline, destination, *, activate_alias):
            self.assertTrue(activate_alias)
            self.assertEqual(source['mysql']['expectedServerUuid'], mysql['mysqlServerUuid'])
            self.assertEqual(source['repository']['settings'], {'location': '/backup'})
            self.assertEqual(resources.es['role'], 'service')
            self.assertEqual(events[-1], 'service-start')
            events.append('full-native-restore')
            value = {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'allDocumentSourceFieldsEqual': True,
                'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True,
                'previousIndexRetained': None, 'elasticsearch': {'clusterUuid': self.service['clusterUuid'], 'imageId': self.service['image']},
                'mysql': {'serverUuid': mysql['mysqlServerUuid']}, 'fullDocumentFingerprint': projection,
                'activeAlias': 'accommodations-vpipeline', 'restoredIndex': 'accommodations-vpipeline'}
            put(destination / 'search-restore-receipt.json', value)
            return value
        def final_fingerprint(runtime, release, info, path, timeout):
            events.append('same-prepared-fingerprint')
            put(path, prepared); return prepared
        put(output / 'resource-lifetime.json', {})
        put(output / 'phase-times.json', [])
        config_binding = put(self.root / 'config.json', config)
        lock = MagicMock(); lock.close.side_effect = lambda: events.append('unlock')
        def locked(runtime):
            events.append('lock'); return lock
        with contextlib.ExitStack() as stack:
            def p(target, name, **kwargs):
                return stack.enter_context(patch.object(target, name, **kwargs))
            p(module, 'Resources', return_value=resources); p(module, 'Measurements', return_value=sampler)
            p(module.contract, 'extract_runtime', side_effect=extract)
            p(module, 'qualify_runtime', side_effect=runtime); p(module, 'assert_runtime')
            p(module, 'activated_runtime', side_effect=lambda proof: contextlib.nullcontext())
            p(module, 'install_private_accounts', side_effect=lambda runtime, fresh: put(Path(fresh['privateAccounts']), {'offlineFixtureOnly': True}))
            p(module, 'verify_callback_receipt'); p(module, 'verify_resource')
            p(module, 'measure_mysql', return_value={'unit': True})
            p(module, 'measure_es', return_value={'unit': True})
            p(module, 'seal_producer', side_effect=seal)
            p(module, 'verify_producer_seal', return_value={'producer': self.producer})
            freeze = p(module, 'freeze_on_failure', return_value={'attempted': True, 'frozen': True})
            p(module.restore, 'acquire_fresh_run_lock', side_effect=locked)
            p(module.restore, 'validate_inputs', return_value=({}, {}, {}))
            p(module.restore, 'build_fresh_preflight', side_effect=lambda fresh, out: {'configuration': fresh, 'state': 'PREFLIGHT_READY'})
            p(module.restore, 'apply_fresh_plan', side_effect=apply)
            p(module.restore, 'fingerprint', side_effect=final_fingerprint)
            p(module.restore, 'inspect', return_value={})
            p(module.search, 'produce', side_effect=produce)
            p(module.search, 'restore', side_effect=restore_native)
            p(resources, 'engine'); p(resources, 'empty', return_value={'empty': True})
            p(resources, 'start_network_observer'); p(resources, 'adopt_mysql', side_effect=adopt)
            p(resources, 'verify_mysql', return_value=(mysql_info, db)); p(resources, 'exclusive')
            p(resources, 'remove_observer', side_effect=lambda: events.append('observer-remove'))
            p(resources, 'start_es', side_effect=start_es); p(resources, 'remove_producer', side_effect=remove)
            if fail_produce:
                with self.assertRaises(RuntimeError):
                    module.complete(config, output, config_binding, ({}, {}, {}))
                self.assertTrue(freeze.called)
                self.assertFalse((output / 'completion-receipt.json').exists())
                failure = module.contract.read(output / 'completion-failure.json')
                self.assertEqual(failure['state'], 'FAILED_UNVERIFIED_RESOURCES_RETAINED')
                self.assertFalse(failure['cleanupAttempted'])
                self.assertNotIn('secret value', json.dumps(failure))
            else:
                result = module.complete(config, output, config_binding, ({}, {}, {}))
                self.assertEqual(result['state'], module.STATE)
                self.assertEqual(result['mysql']['containerId'], mysql['containerId'])
                self.assertEqual(result['elasticsearch']['containerId'], self.service['containerId'])
                self.assertEqual(result['elasticsearch']['activeAlias'], 'accommodations-vpipeline')
                self.assertFalse(freeze.called)
                self.assertTrue((output / 'completion-seal.json').is_file())
        self.docker.assert_not_called()
        return events

    def test_full_adapters_order_and_same_db_lock_span_the_entire_pipeline(self):
        self.assertEqual(self.run_pipeline(), ['lock', 'fresh-apply', 'retained-mysql', 'observer-remove',
            'producer-start', 'full-produce', 'sealed', 'producer-remove', 'service-start',
            'full-native-restore', 'same-prepared-fingerprint', 'unlock'])

    def test_failed_production_never_removes_unverified_producer_or_starts_service(self):
        self.assertEqual(self.run_pipeline(fail_produce=True), ['lock', 'fresh-apply', 'retained-mysql',
            'observer-remove', 'producer-start', 'full-produce', 'unlock'])


if __name__ == '__main__':
    unittest.main()
