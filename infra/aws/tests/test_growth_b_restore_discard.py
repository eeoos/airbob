"""Offline OCI installation after an explicitly recorded database discard.

Synthetic Docker metadata and MySQL adapters only; no Docker/DB/cloud calls.
"""
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import MagicMock, patch

from test_growth_b_contract import account_fixture, private_account_fixture
from test_growth_b_restore_fresh import FreshFixture, restore


def synthetic_container(identifier, name, image, *, running=True, service=None, mounts=()):
    return {'Id': identifier * 64, 'Name': '/' + name, 'Image': 'sha256:' + identifier * 64,
        'State': {'Running': running}, 'Mounts': list(mounts),
        'Config': {'Image': image, 'Entrypoint': [], 'Cmd': [], 'ExposedPorts': {},
                   'Labels': {'com.docker.compose.project': 'airbob', 'com.docker.compose.service': service or name}}}


def inventory_row(info):
    return {'id': info['Id'], 'name': info['Name'][1:], 'imageId': info['Image'],
            'image': info['Config']['Image'], 'running': info['State']['Running']}


class DiscardFixture(FreshFixture):
    def setUp(self):
        super().setUp()
        self.config.update(mode='oci', operation=restore.OCI_DISCARD_OPERATION)
        self.config['target'].update(container='new-oci-mysql', volume='new-oci-data')
        self.original = [synthetic_container('1', 'airbob-app', 'airbob:qualified', service='app'),
            synthetic_container('2', 'debezium', 'debezium:qualified'),
            synthetic_container('3', 'redis', 'redis:qualified'),
            synthetic_container('4', 'elasticsearch', 'elasticsearch:qualified'),
            synthetic_container('5', 'kafka', 'kafka:qualified'),
            synthetic_container('6', 'old-idle-example', 'hello-world', running=False)]
        data_mount = {'Type': 'volume', 'Name': 'old-oci-data', 'Destination': '/var/lib/mysql'}
        self.old = synthetic_container('7', 'old-oci-mysql', 'mysql:8.4.11', service='mysql',
            mounts=[data_mount, {'Type': 'bind', 'Destination': '/docker-entrypoint-initdb.d'}])
        source = inventory_row(self.old) | {'composeProject': 'airbob', 'composeService': 'mysql', 'mounts': self.old['Mounts']}
        writers = [inventory_row(info) | {'composeProject': 'airbob', 'composeService': info['Config']['Labels']['com.docker.compose.service']}
                   for info in self.original[:2]]
        engine = {'context': 'default', 'configuredEndpoint': 'unix:///var/run/docker.sock',
                  'effectiveEndpoint': 'unix:///var/run/docker.sock', 'id': 'unit-oci-engine', 'dockerRootDir': '/var/lib/docker'}
        before_inventory = {'containers': [inventory_row(info) for info in self.original + [self.old]],
                            'volumeNames': ['old-oci-data', 'existing-es', 'existing-redis', 'existing-kafka']}
        for writer in self.original[:2]: writer['State']['Running'] = False
        self.infos = {info['Id']: info for info in self.original}
        self.volumes = {'existing-es', 'existing-redis', 'existing-kafka'}
        self.receipt = {'schemaVersion': 1, 'kind': 'oci-database-discarded-by-user',
            'state': 'DISCARDED_BY_EXPLICIT_USER_REQUEST',
            'authorization': {'mode': 'explicit-user-discard-without-new-backup', 'oldArchiveRequiredForDeletion': False},
            'scope': {'newBackupCreated': False, 'fullFingerprintComputed': False},
            'onlyRequestedContainerAndVolumeRemoved': True,
            'before': {'engine': engine, 'source': source,
                'sourceVolume': {'Name': 'old-oci-data', 'Driver': 'local', 'Options': None, 'mountingContainerIds': [self.old['Id']]},
                'database': {'mysql': {'uuid': '12345678-1234-1234-1234-123456789abc'}}, 'writers': writers,
                'inventory': before_inventory},
            'after': {'engine': copy.deepcopy(engine), 'inventory': {'containers': [inventory_row(info) for info in self.original],
                'volumeNames': sorted(self.volumes)}, 'absence': {'oldContainerIdAbsent': True,
                'oldContainerNameAbsent': True, 'oldVolumeNameAbsent': True}},
            'stoppedWriterIds': [info['Id'] for info in self.original[:2]]}
        self.receipt_path = self.root / 'discard.json'
        self.config['discardReceipt'] = str(self.receipt_path)
        self.seal()
        self.config['diskObserver'] = {'name': 'redis', 'id': self.original[2]['Id']}
        self.accounts.write_text(json.dumps(private_account_fixture(account_fixture()[0], restore.account_environment(self.config))))
        self.engine = {'context': 'default', 'configuredEndpoint': 'unix:///var/run/docker.sock',
            'endpoint': 'unix:///var/run/docker.sock', 'engineId': 'unit-oci-engine', 'dockerRootDir': '/var/lib/docker'}
        self.calls = []; self.busy_port = False; self.shared_volume_users = None
        self.new_id, self.claim = '8' * 64, '9' * 32
        self.new_volume = {'Name': self.config['target']['volume'], 'Driver': 'local', 'Options': None,
            'Labels': {'airbob.dataset.id': self.config['datasetId'], 'airbob.restore.claim': self.claim}}
        self.patch(restore, 'local_engine_identity', side_effect=lambda **kwargs: copy.deepcopy(self.engine))
        self.patch(restore, 'existing', side_effect=lambda kind: set(self.infos) if kind == 'container' else set(self.volumes))
        self.patch(restore, 'inspect', side_effect=self.inspect)
        self.patch(restore, 'docker', side_effect=self.docker)
        self.patch(restore, 'port_open', side_effect=lambda port: self.busy_port)

    def patch(self, obj, name, **kwargs):
        item = patch.object(obj, name, **kwargs); self.addCleanup(item.stop)
        return item.start()

    def seal(self):
        self.receipt_path.write_text(json.dumps(self.receipt))
        self.config['discardReceiptSha256'] = restore.sha(self.receipt_path)

    def inspect(self, kind, identifier):
        if kind == 'image': return {'Id': identifier}
        if kind == 'volume': return copy.deepcopy(self.new_volume)
        if kind == 'container':
            if identifier in self.infos: return copy.deepcopy(self.infos[identifier])
            return copy.deepcopy(next(info for info in self.infos.values() if info['Name'] == '/' + identifier))
        if kind == 'network': return {'Name': identifier, 'Id': 'unit-network-id'}
        raise AssertionError('Unexpected metadata query')

    def docker(self, *args, **kwargs):
        self.calls.append(args)
        if args[:3] == ('exec', self.config['diskObserver']['id'], 'df'):
            return b'Filesystem 1024-blocks Used Available Capacity Mounted\noverlay 1000000 1 999999 1% /\n'
        if args[:2] == ('ps', '-aq'):
            return ('\n'.join(self.shared_volume_users or [self.new_id]) + '\n').encode()
        raise AssertionError('Offline test attempted a Docker mutation or unexpected query')

    def configured(self, value=None, mode='oci'):
        return super().configured(value, mode)

    def plan(self):
        return restore.build_discard_preflight(self.config, self.root)

    def install_synthetic_target(self, *args):
        info = synthetic_container('8', self.config['target']['container'], self.config['target']['image'],
            mounts=[{'Type': 'volume', 'Name': self.config['target']['volume'], 'Destination': '/var/lib/mysql'}])
        info['Image'] = self.config['target']['image']
        info['Config']['Labels'] = {'airbob.dataset.id': self.config['datasetId']}
        self.infos[self.new_id] = info; self.volumes.add(self.config['target']['volume'])
        return self.claim


class DiscardConfigurationTest(DiscardFixture):
    def test_explicit_oci_operation_needs_no_old_dump_fingerprint_or_source(self):
        self.assertEqual(self.config, self.configured())
        self.assertFalse({'source', 'preservationPolicy', 'writerContainers'} & self.config.keys())

    def test_operation_cannot_be_used_in_local_mode(self):
        with self.assertRaisesRegex(ValueError, 'OCI-only'):
            self.configured(self.config | {'mode': 'local'}, mode='local')

    def test_preservation_source_and_callback_command_fields_are_rejected(self):
        for key, value in [('source', {}), ('preservationPolicy', 'require-current-match'), ('writerContainers', []),
                           ('retirementReceipt', '/old/retirement.json'), ('beforePreparationCommand', 'command')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.configured(self.config | {key: value})
        changed = copy.deepcopy(self.config); changed['capacity']['maxBackupBytes'] = 1
        with self.assertRaisesRegex(ValueError, 'no previous database backup'):
            self.configured(changed)

    def test_discard_path_and_anchor_are_explicit_and_cannot_enter_local_fresh(self):
        for key in ('discardReceipt', 'discardReceiptSha256'):
            changed = dict(self.config); changed.pop(key)
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'receipt and SHA'):
                self.configured(changed)
        with self.assertRaisesRegex(ValueError, 'belongs only'):
            self.configured(self.config | {'operation': restore.FRESH_OPERATION, 'mode': 'local'}, mode='local')


class DiscardReceiptAndObservationTest(DiscardFixture):
    def test_recorded_redis_es_kafka_and_stopped_writers_are_retained_without_mutation(self):
        result = self.plan()
        self.assertEqual('global-b-oci-discard-install-preflight', result['kind'])
        self.assertEqual(0, result['reclaimableBytes'])
        self.assertEqual(6, len(result['environment']['remainingContainers']))
        self.assertEqual(self.config['discardReceiptSha256'], result['inputBindings']['discardReceiptSha256'])
        self.assertNotIn('source', result)
        self.assertTrue(all(args[0] == 'exec' and args[2] == 'df' for args in self.calls))

    def test_receipt_byte_changes_are_rejected_before_engine_queries(self):
        self.receipt_path.write_text(self.receipt_path.read_text() + ' ')
        with self.assertRaisesRegex(ValueError, 'receipt bytes changed'):
            self.plan()
        self.assertFalse(self.calls)

    def test_resealed_non_discard_or_incomplete_actual_removal_proof_is_rejected(self):
        original = copy.deepcopy(self.receipt)
        mutations = [lambda value: value.update(kind='airbob-experiment-database-retirement'),
            lambda value: value.update(state='FAILED'),
            lambda value: value['authorization'].update(mode='planned'),
            lambda value: value['authorization'].update(oldArchiveRequiredForDeletion=True),
            lambda value: value['scope'].update(newBackupCreated=True),
            lambda value: value.update(onlyRequestedContainerAndVolumeRemoved=False),
            lambda value: value['after']['absence'].update(oldVolumeNameAbsent=False),
            lambda value: value['before']['sourceVolume']['mountingContainerIds'].append('a' * 64),
            lambda value: value['after']['engine'].update(id='other-engine'),
            lambda value: value['stoppedWriterIds'].pop()]
        for index, mutation in enumerate(mutations):
            self.receipt = copy.deepcopy(original); mutation(self.receipt); self.seal()
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.plan()

    def test_engine_context_socket_id_and_root_drift_block_installation(self):
        original = dict(self.engine)
        for key in original:
            self.engine = original | {key: 'changed'}
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'engine identity changed'):
                self.plan()

    def test_restarted_or_replaced_writer_and_service_image_drift_are_rejected(self):
        original = copy.deepcopy(self.infos)
        changes = [lambda: self.infos['1' * 64]['State'].update(Running=True),
                   lambda: self.infos['2' * 64].update(Id='a' * 64),
                   lambda: self.infos['4' * 64].update(Image='sha256:' + 'b' * 64)]
        for index, change in enumerate(changes):
            self.infos = copy.deepcopy(original); change()
            with self.subTest(index=index), self.assertRaisesRegex(ValueError, 'identity/state changed'):
                self.plan()

    def test_old_resources_target_collisions_and_new_containers_are_rejected(self):
        baseline_infos, baseline_volumes, config = copy.deepcopy(self.infos), set(self.volumes), copy.deepcopy(self.config)
        for kind in ('old-id', 'old-volume', 'target-volume', 'target-name', 'another-mysql'):
            self.infos, self.volumes, self.config = copy.deepcopy(baseline_infos), set(baseline_volumes), copy.deepcopy(config)
            if kind == 'old-id': self.infos[self.old['Id']] = self.old
            if kind == 'old-volume': self.volumes.add('old-oci-data')
            if kind == 'target-volume': self.volumes.add(self.config['target']['volume'])
            if kind == 'target-name': self.config['target']['container'] = 'redis'
            if kind == 'another-mysql': self.infos['a' * 64] = synthetic_container('a', 'another-mysql', 'mysql:8.4.11')
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                self.plan()

    def test_even_an_extra_mysql_in_the_recorded_inventory_cannot_be_used(self):
        extra = synthetic_container('a', 'additional-business-db', 'custom-db', mounts=[{'Destination': '/var/lib/mysql'}])
        self.infos[extra['Id']] = extra
        for phase in ('before', 'after'):
            self.receipt[phase]['inventory']['containers'].append(inventory_row(extra))
        self.seal()
        with self.assertRaisesRegex(ValueError, 'additional business MySQL'):
            self.plan()

    def test_unknown_volume_disk_observer_and_busy_listener_are_rejected(self):
        self.volumes.add('unreviewed-volume')
        with self.assertRaisesRegex(ValueError, 'volume inventory changed'): self.plan()
        self.volumes.remove('unreviewed-volume')
        self.config['diskObserver'] = {'name': 'airbob-app', 'id': '1' * 64}
        with self.assertRaisesRegex(ValueError, 'retained running service'): self.plan()
        self.config['diskObserver'] = {'name': 'redis', 'id': '3' * 64}
        self.busy_port = True
        with self.assertRaisesRegex(ValueError, 'port is already in use'): self.plan()

    def test_only_new_claimed_target_is_allowed_after_creation_and_shared_volume_is_rejected(self):
        self.install_synthetic_target()
        observed = restore.discard_observation(self.config, target_id=self.new_id, target_claim=self.claim)
        self.assertFalse(observed['targetAbsence']['containerAbsent'])
        self.shared_volume_users = [self.new_id, '1' * 64]
        with self.assertRaisesRegex(ValueError, 'data volume is shared'):
            restore.discard_observation(self.config, target_id=self.new_id, target_claim=self.claim)
        self.shared_volume_users = None
        with self.assertRaisesRegex(ValueError, 'volume claim changed'):
            restore.discard_observation(self.config, target_id=self.new_id, target_claim='0' * 32)


class DiscardApplyTest(DiscardFixture):
    def setUp(self):
        super().setUp()
        self.trace, self.frozen = [], False
        self.reviewed_plan = copy.deepcopy(self.plan())
        self.db = MagicMock()
        self.db.scalar.side_effect = lambda sql: 1 if sql == 'SELECT 1' else 0
        self.db.rows.return_value = [{'Database': 'airbobdb'}]
        self.db.identity.side_effect = lambda: {'version': '8.4.11', 'uuid': 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
                                               'superReadOnly': int(self.frozen)}
        def execute(sql):
            self.trace.append(sql)
            if 'super_read_only=ON' in sql: self.frozen = True
            if 'super_read_only=OFF' in sql: self.frozen = False
        self.db.execute.side_effect = execute
        self.patch(restore, 'Database', return_value=self.db)
        self.patch(restore, 'validate_inputs', return_value=({}, {'airbob-growth.sql.gz': 'a' * 64}, self.baseline))
        self.create = self.patch(restore, 'create_target', side_effect=self.install_synthetic_target)
        self.patch(restore, 'install_infrastructure_users')
        self.import_dump = self.patch(restore, 'streaming', side_effect=lambda *args, **kwargs: self.trace.append('import'))
        self.fingerprint = self.patch(restore, 'fingerprint', side_effect=lambda *args: self.trace.append('fingerprint') or self.baseline)
        self.prepare = self.patch(restore, 'prepare_service', side_effect=lambda *args: self.trace.append('prepare') or {'passed': True})
        self.remove = self.patch(restore, 'remove_source', side_effect=AssertionError('No source can be deleted'))
        self.preserve = self.patch(restore, 'preservation', side_effect=AssertionError('No old archive is needed'))

    def apply(self, *, callback=None):
        self.output = self.root / 'apply'; self.output.mkdir()
        with contextlib.redirect_stdout(io.StringIO()):
            return restore.apply_discard_plan(self.config, self.reviewed_plan, self.output, self.root, before_preparation=callback)

    def test_full_fingerprint_then_frozen_baseline_then_credentials_and_final_freeze(self):
        def callback(config, output, runtime, info, db):
            self.assertTrue(self.frozen); self.trace.append('baseline')
            path = output / 'actual-baseline.json'; path.write_text('{"unit":true}')
            return {'baseline': path}
        report = self.apply(callback=callback)
        self.assertLess(self.trace.index('fingerprint'), self.trace.index('baseline'))
        self.assertLess(self.trace.index('baseline'), self.trace.index('prepare'))
        self.assertEqual('PREPARED_APP_STOPPED_DATABASE_FROZEN', report['state'])
        self.assertEqual(self.claim, report['targetVolumeClaim']); self.assertTrue(self.frozen)
        self.assertNotIn('source', report); self.assertNotIn('preservationPolicy', report)
        self.assertFalse(report['oldBackupRequired']); self.assertFalse(report['oldResourcesRemovedByThisInstall'])
        self.remove.assert_not_called(); self.preserve.assert_not_called()
        self.assertEqual(7, len(self.infos))
        self.assertTrue(all(args[0] in {'ps', 'exec'} for args in self.calls))

    def test_writer_change_after_review_prevents_resource_creation(self):
        self.infos['2' * 64]['State']['Running'] = True
        with self.assertRaisesRegex(ValueError, 'identity/state changed'):
            self.apply()
        self.create.assert_not_called(); self.import_dump.assert_not_called()

    def test_changed_credentials_stop_before_resource_creation(self):
        self.accounts.write_text('changed-private-fixture')
        with self.assertRaisesRegex(ValueError, 'input bytes'):
            self.apply()
        self.create.assert_not_called()

    def test_failed_gzip_and_capacity_stop_before_resource_creation(self):
        for gate, error in [('validate_inputs', EOFError('invalid gzip')), ('fresh_capacity', ValueError('insufficient space'))]:
            with self.subTest(gate=gate), patch.object(restore, gate, side_effect=error), self.assertRaises(type(error)):
                self.apply()
            self.create.assert_not_called()
            # Both attempts retained only their own report; use another fresh output for the next one.
            self.output.rename(self.root / gate)

    def test_image_change_during_creation_is_frozen_before_import(self):
        def changed(*args):
            claim = self.install_synthetic_target()
            self.infos[self.new_id]['Image'] = 'sha256:' + '0' * 64
            return claim
        self.create.side_effect = changed
        with self.assertRaisesRegex(ValueError, 'Created MySQL image'):
            self.apply()
        self.import_dump.assert_not_called(); self.prepare.assert_not_called(); self.assertTrue(self.frozen)

    def test_failed_final_freeze_never_receives_a_success_state(self):
        self.db.identity.side_effect = lambda: {'version': '8.4.11', 'uuid': 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'superReadOnly': 0}
        with self.assertRaisesRegex(ValueError, 'did not remain frozen'):
            self.apply()
        self.assertEqual('FAILED_RESOURCES_RETAINED', restore.read(self.output / 'restore.json')['state'])

    def test_callback_failure_retains_and_freezes_new_target(self):
        with self.assertRaisesRegex(RuntimeError, 'baseline failed'):
            self.apply(callback=MagicMock(side_effect=RuntimeError('baseline failed')))
        report = restore.read(self.output / 'restore.json')
        self.assertEqual('FAILED_RESOURCES_RETAINED', report['state'])
        self.assertTrue(report['databaseFrozenAfterFailure']); self.assertIn(self.new_id, self.infos)
        self.prepare.assert_not_called(); self.remove.assert_not_called(); self.preserve.assert_not_called()

    def test_wrong_full_fingerprint_never_prepares_or_calls_baseline(self):
        self.fingerprint.side_effect = None; self.fingerprint.return_value = {'different': True}
        callback = MagicMock()
        with self.assertRaisesRegex(ValueError, 'Complete restored rows'):
            self.apply(callback=callback)
        callback.assert_not_called(); self.prepare.assert_not_called(); self.assertTrue(self.frozen)

    def test_writer_restart_during_preparation_fails_and_retains_frozen_target(self):
        def restart(*args):
            self.infos['1' * 64]['State']['Running'] = True
            return {'passed': True}
        self.prepare.side_effect = restart
        with self.assertRaisesRegex(ValueError, 'identity/state changed'):
            self.apply()
        report = restore.read(self.output / 'restore.json')
        self.assertEqual('FAILED_RESOURCES_RETAINED', report['state'])
        self.assertTrue(report['databaseFrozenAfterFailure']); self.remove.assert_not_called()

    def test_additional_schema_in_new_mysql_blocks_import(self):
        self.db.rows.return_value = [{'Database': 'airbobdb'}, {'Database': 'unrelated_business'}]
        with self.assertRaisesRegex(ValueError, 'unexpected business schema'):
            self.apply()
        self.import_dump.assert_not_called(); self.assertTrue(self.frozen)


class DiscardRuntimeBindingTest(DiscardFixture):
    def setUp(self):
        super().setUp()
        self.runtime = {'passed': True, 'consumerRuntimePassed': True, 'hostJavaTools': {'java': {'sha256': 'a' * 64}},
            'qualificationSourceSha256': 'b' * 64, 'fixedCasesSha256': 'c' * 64, 'scope': {'selectedZones': ['Asia/Seoul']},
            'python': {'version': '3.12', 'zoneData': {}}, 'java': {'tzdbFile': {'sha256': 'd' * 64}},
            'consumerReleaseBindings': {'files': {'tool-sources.json': 'e' * 64}}}
        self.runtime_path = self.root / 'runtime.json'; self.runtime_path.write_text(json.dumps(self.runtime))
        self.runtime_plan = {'hostRuntimeQualification': {'path': str(self.runtime_path), 'sha256': restore.sha(self.runtime_path)}}

    def test_exact_reviewed_runtime_is_accepted(self):
        restore.verify_discard_runtime(self.runtime_plan, copy.deepcopy(self.runtime))

    def test_runtime_receipt_jdk_tzdb_python_and_zone_scope_drift_are_rejected(self):
        for key in ('hostJavaTools', 'scope', 'python'):
            changed = copy.deepcopy(self.runtime); changed[key] = {'changed': True}
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'runtime changed'):
                restore.verify_discard_runtime(self.runtime_plan, changed)
        changed = copy.deepcopy(self.runtime); changed['java']['tzdbFile']['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'runtime changed'):
            restore.verify_discard_runtime(self.runtime_plan, changed)
        self.runtime_path.write_text(self.runtime_path.read_text() + ' ')
        with self.assertRaisesRegex(ValueError, 'runtime evidence changed'):
            restore.verify_discard_runtime(self.runtime_plan, self.runtime)


class MainDiscardPlanTest(DiscardFixture):
    def test_exact_plan_dispatches_oci_path_and_trusted_callback_under_shared_lock(self):
        config_path = self.root / 'config.json'; config_path.write_text(json.dumps(self.config))
        plan_path = self.root / 'plan.json'; plan_path.write_text(json.dumps(self.plan()))
        callback, lock = MagicMock(), MagicMock()
        for name, value in [('qualify_runtime', {'current': True}), ('activated_runtime', contextlib.nullcontext()),
                            ('qualification_binding', {'sha256': 'a' * 64}), ('validate_inputs', ({}, {}, {})),
                            ('extract_runtime', self.root / 'runtime'), ('acquire_fresh_run_lock', lock),
                            ('require_local_engine', None)]:
            self.patch(restore, name, return_value=value)
        gate = self.patch(restore, 'verify_discard_runtime')
        apply = self.patch(restore, 'apply_discard_plan', return_value=None)
        local_fresh = self.patch(restore, 'apply_fresh_plan')
        preserved = self.patch(restore, 'apply_plan')
        with patch.object(sys, 'argv', ['restore-growth-b-oci.py', '--config', str(config_path), '--output', str(self.root / 'out'),
            '--apply', '--preflight', str(plan_path), '--preflight-sha256', restore.sha(plan_path)]):
            restore.main(mode='oci', before_preparation=callback)
        gate.assert_called_once(); apply.assert_called_once()
        self.assertIs(apply.call_args.kwargs['before_preparation'], callback)
        lock.close.assert_called_once(); local_fresh.assert_not_called(); preserved.assert_not_called()

    def test_wrong_plan_sha_never_reaches_oci_apply_and_closes_lock(self):
        config_path = self.root / 'config.json'; config_path.write_text(json.dumps(self.config))
        plan_path = self.root / 'plan.json'; plan_path.write_text(json.dumps(self.plan()))
        lock = MagicMock()
        for name, value in [('qualify_runtime', {}), ('activated_runtime', contextlib.nullcontext()),
                            ('validate_inputs', ({}, {}, {})), ('extract_runtime', self.root / 'runtime'),
                            ('acquire_fresh_run_lock', lock), ('require_local_engine', None)]:
            self.patch(restore, name, return_value=value)
        apply = self.patch(restore, 'apply_discard_plan')
        with patch.object(sys, 'argv', ['restore-growth-b-oci.py', '--config', str(config_path), '--output', str(self.root / 'out'),
            '--apply', '--preflight', str(plan_path), '--preflight-sha256', '0' * 64]), self.assertRaisesRegex(ValueError, 'exact reviewed preflight SHA'):
            restore.main(mode='oci')
        apply.assert_not_called(); lock.close.assert_called_once()


if __name__ == '__main__':
    unittest.main()
