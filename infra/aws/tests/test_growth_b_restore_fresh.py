"""Offline fresh-install boundaries and trusted before-preparation callback order."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

from test_growth_b_contract import account_fixture, private_account_fixture

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('fresh_restore_tested', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


class FreshFixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.release = self.root / 'release'; self.release.mkdir()
        self.accounts = self.root / 'accounts.private.json'
        self.jar = self.root / 'app.jar'; self.jar.write_bytes(b'unit-jar')
        self.baseline = {'tables': {'member': {'rows': 1, 'rowsSha256': 'a' * 64, 'ddlSha256': 'b' * 64}}}
        (self.release / 'before-fingerprint.json').write_text(json.dumps(self.baseline))
        (self.release / 'runtime-database-measurement.json').write_text('{"allocatedTablespaceBytes": 1024}')
        self.config = {'schemaVersion': 1, 'mode': 'local', 'operation': restore.FRESH_OPERATION,
                       'datasetId': 'global-growth-b-' + '1' * 16, 'release': str(self.release),
                       'consumerManifestSha256': '2' * 64, 'checksumsSha256': '3' * 64,
                       'backendRoot': str(ROOT), 'appJar': str(self.jar), 'privateAccounts': str(self.accounts),
                       'target': {'container': 'fresh-mysql', 'volume': 'fresh-mysql-data',
                                  'image': 'sha256:' + '4' * 64, 'redisImage': 'sha256:' + '5' * 64,
                                  'mysqlPort': 13306, 'memoryMiB': 2048, 'bufferPoolMiB': 1024},
                       'diskObserver': {'name': 'fresh-observer', 'id': '6' * 64},
                       'capacity': {'reserveHostBytes': 1024, 'reserveDockerBytes': 1024,
                                    'requiredDatabaseBytes': 2048, 'maxBackupBytes': 0}}
        private = private_account_fixture(account_fixture()[0], restore.account_environment(self.config))
        self.accounts.write_text(json.dumps(private)); self.accounts.chmod(0o600)
        self.environment = {'engine': {'context': 'desktop-linux', 'endpoint': 'unix:///unit.sock',
                                      'engineId': 'observed-engine', 'dockerRootDir': '/var/lib/docker'},
                            'observer': dict(self.config['diskObserver'], imageId=self.config['target']['redisImage'], claim='7' * 32),
                            'targetImages': {'image': self.config['target']['image'], 'redisImage': self.config['target']['redisImage']},
                            'targetAbsence': {'container': 'fresh-mysql', 'volume': 'fresh-mysql-data',
                                              'containerAbsent': True, 'volumeAbsent': True},
                            'dockerFreeBytes': 1024**3}
        self.observer = {'Id': '6' * 64, 'Name': '/fresh-observer', 'Image': self.config['target']['redisImage'],
                         'State': {'Running': True}, 'Mounts': [],
                         'HostConfig': {'ReadonlyRootfs': True, 'NetworkMode': 'none'},
                         'Config': {'Labels': {'airbob.restore.temporary': 'true',
                                              'airbob.restore.observer': restore.FRESH_OPERATION,
                                              'airbob.restore.claim': '7' * 32},
                                    'Entrypoint': ['/bin/sh'], 'Cmd': list(restore.OBSERVER_COMMAND)}}

    def configured(self, value=None, mode='local'):
        path = self.root / 'config.json'
        path.write_text(json.dumps(self.config if value is None else value))
        return restore.configuration(path, mode)

    def plan(self):
        return {'kind': 'global-b-fresh-install-preflight', 'state': 'PREFLIGHT_READY',
                'configuration': copy.deepcopy(self.config), 'environment': copy.deepcopy(self.environment),
                'inputBindings': restore.fresh_input_bindings(self.config)}

    def apply_output(self):
        output = self.root / 'apply'; output.mkdir()
        return output


class FreshConfigurationTest(FreshFixture):
    def test_explicit_fresh_configuration_does_not_need_or_invent_a_source(self):
        self.assertEqual(self.configured(), self.config)
        self.assertNotIn('source', self.config)

    def test_fresh_cannot_be_enabled_on_oci(self):
        with self.assertRaisesRegex(ValueError, 'local-only'):
            self.configured(self.config | {'mode': 'oci'}, mode='oci')

    def test_old_source_policy_or_writer_claims_cannot_enter_fresh_install(self):
        for key, value in [('source', {}), ('preservationPolicy', 'discard-changes'), ('writerContainers', [])]:
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, 'cannot claim'):
                self.configured(self.config | {key: value})

    def test_callback_commands_and_unknown_operations_are_not_configuration(self):
        with self.assertRaisesRegex(ValueError, 'Unknown configuration'):
            self.configured(self.config | {'beforePreparationCommand': 'external-script'})
        with self.assertRaisesRegex(ValueError, 'Unknown restore operation'):
            self.configured(self.config | {'operation': 'reuse-whatever-exists'})

    def test_fresh_requires_observer_and_zero_previous_backup_budget(self):
        missing = dict(self.config); missing.pop('diskObserver')
        with self.assertRaisesRegex(ValueError, 'disk observer'):
            self.configured(missing)
        changed = copy.deepcopy(self.config); changed['capacity']['maxBackupBytes'] = 1
        with self.assertRaisesRegex(ValueError, 'no previous'):
            self.configured(changed)


class FreshObservationTest(FreshFixture):
    def setUp(self):
        super().setUp()
        self.calls = []
        self.containers = {'6' * 64}; self.volumes = {'unrelated-anonymous-redis-volume'}
        self.names = 'fresh-observer\n'; self.used_port = False
        def docker(*args, **kwargs):
            self.calls.append(args)
            if args == ('ps', '-a', '--format', '{{.Names}}'): return self.names.encode()
            if args == ('exec', '6' * 64, 'df', '-P', '-k', '/'):
                return b'Filesystem 1024-blocks Used Available Capacity Mounted\noverlay 100000 1 99999 1% /\n'
            raise AssertionError('Unexpected or mutating Docker command: ' + str(args[:2]))
        def inspect(kind, name):
            if kind == 'image': return {'Id': name}
            if kind == 'container' and name == 'fresh-observer': return copy.deepcopy(self.observer)
            raise AssertionError('Unexpected inspection')
        self.addCleanup(patch.stopall)
        patch.object(restore, 'docker', side_effect=docker).start()
        patch.object(restore, 'inspect', side_effect=inspect).start()
        patch.object(restore, 'existing', side_effect=lambda kind: self.containers if kind == 'container' else self.volumes).start()
        patch.object(restore, 'local_engine_identity', return_value=self.environment['engine']).start()
        patch.object(restore, 'port_open', side_effect=lambda port: self.used_port).start()

    def test_read_only_preflight_records_absence_without_deleting_unrelated_volumes(self):
        plan = restore.build_fresh_preflight(self.config, self.root)
        self.assertEqual(plan['environment']['engine'], self.environment['engine'])
        self.assertEqual(plan['environment']['targetAbsence'], self.environment['targetAbsence'])
        self.assertEqual(plan['reclaimableBytes'], 0)
        self.assertNotIn('unit-fixture-value-only-', json.dumps(plan))
        self.assertTrue(all(call[0] in {'ps', 'exec'} for call in self.calls))

    def test_any_other_running_or_stopped_container_blocks_fresh_plan(self):
        self.containers.add('8' * 64)
        with self.assertRaisesRegex(ValueError, 'no other running or stopped'):
            restore.fresh_observation(self.config)

    def test_target_container_volume_and_port_must_all_be_absent(self):
        for kind in ('container-name', 'volume', 'port'):
            with self.subTest(kind=kind):
                self.names = 'fresh-observer\nfresh-mysql\n' if kind == 'container-name' else 'fresh-observer\n'
                self.volumes = {'fresh-mysql-data'} if kind == 'volume' else set()
                self.used_port = kind == 'port'
                with self.assertRaises(ValueError): restore.fresh_observation(self.config)

    def test_observer_requires_exact_owner_image_isolation_and_idle_command(self):
        original = copy.deepcopy(self.observer)
        for mutate in (
            lambda value: value.update(Id='9' * 64),
            lambda value: value.update(Image=self.config['target']['image']),
            lambda value: value['HostConfig'].update(ReadonlyRootfs=False),
            lambda value: value['HostConfig'].update(NetworkMode='bridge'),
            lambda value: value.update(Mounts=[{'Type': 'volume'}]),
            lambda value: value['Config'].update(Cmd=['redis-server']),
            lambda value: value['Config']['Labels'].pop('airbob.restore.claim'),
        ):
            self.observer = copy.deepcopy(original); mutate(self.observer)
            with self.assertRaises(ValueError): restore.fresh_observation(self.config)


class FreshApplyTest(FreshFixture):
    def setUp(self):
        super().setUp()
        self.addCleanup(patch.stopall)
        self.validated = patch.object(restore, 'validate_inputs', return_value=({}, {'airbob-growth.sql.gz': 'a' * 64}, self.baseline)).start()
        self.observe = patch.object(restore, 'fresh_observation', return_value=copy.deepcopy(self.environment)).start()
        self.capacity = patch.object(restore, 'fresh_capacity', return_value=1024**3).start()
        self.target = patch.object(restore, 'restore_new_target', side_effect=lambda config, output, runtime, report, event, **kwargs: report).start()
        self.remove = patch.object(restore, 'remove_source', side_effect=AssertionError('Fresh cannot remove a source')).start()

    def test_fresh_apply_never_calls_source_removal_and_records_actual_absence(self):
        with contextlib.redirect_stdout(io.StringIO()):
            report = restore.apply_fresh_plan(self.config, self.plan(), self.apply_output(), self.root)
        self.remove.assert_not_called(); self.target.assert_called_once()
        self.assertEqual(report['state'], 'EMPTY_TARGET_REOBSERVED')
        self.assertEqual(report['dumpSha256'], 'a' * 64)
        self.assertNotIn('source', report)

    def test_changed_engine_observer_or_image_blocks_creation(self):
        for key in ('engine', 'observer', 'targetImages', 'targetAbsence'):
            output = self.root / key; output.mkdir()
            self.observe.return_value = copy.deepcopy(self.environment)
            self.observe.return_value[key] = {'changed': True}
            with self.subTest(key=key), contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'identity changed'):
                restore.apply_fresh_plan(self.config, self.plan(), output, self.root)
        self.target.assert_not_called(); self.remove.assert_not_called()

    def test_crc_input_change_and_capacity_failure_precede_target_creation(self):
        for failure in ('crc', 'private', 'capacity'):
            output = self.root / failure; output.mkdir(); plan = self.plan()
            self.validated.side_effect = EOFError('unit gzip failure') if failure == 'crc' else None
            self.capacity.side_effect = ValueError('unit space failure') if failure == 'capacity' else None
            if failure == 'private': self.accounts.write_text('changed-private-unit-input')
            with self.subTest(failure=failure), contextlib.redirect_stdout(io.StringIO()), self.assertRaises((EOFError, ValueError)):
                restore.apply_fresh_plan(self.config, plan, output, self.root)
        self.target.assert_not_called(); self.remove.assert_not_called()


class TargetCallbackTest(FreshFixture):
    def setUp(self):
        super().setUp()
        self.addCleanup(patch.stopall)
        self.trace = []; self.frozen = False
        self.db = MagicMock()
        self.db.scalar.side_effect = lambda sql: 1 if sql == 'SELECT 1' else 0
        self.db.rows.return_value = [{'Database': 'airbobdb'}]
        self.db.identity.side_effect = lambda: {'version': '8.4.11', 'uuid': '01234567-89ab-cdef-0123-456789abcdef', 'superReadOnly': int(self.frozen)}
        def execute(sql):
            self.trace.append(sql)
            if 'super_read_only=ON' in sql: self.frozen = True
            if 'super_read_only=OFF' in sql: self.frozen = False
        self.db.execute.side_effect = execute
        patch.object(restore, 'Database', return_value=self.db).start()
        patch.object(restore, 'validate_inputs', return_value=({}, {'airbob-growth.sql.gz': 'a' * 64}, self.baseline)).start()
        patch.object(restore, 'fresh_observation', return_value=self.environment).start()
        patch.object(restore, 'fresh_capacity', return_value=1024**3).start()
        patch.object(restore, 'require_fresh_containers').start()
        patch.object(restore, 'create_target', side_effect=lambda *args: self.trace.append('create')).start()
        self.inspect = patch.object(restore, 'inspect', return_value={'Id': '8' * 64, 'Image': self.config['target']['image']}).start()
        patch.object(restore, 'install_infrastructure_users').start()
        patch.object(restore, 'streaming', side_effect=lambda *args, **kwargs: self.trace.append('stream')).start()
        self.fingerprint = patch.object(restore, 'fingerprint', side_effect=lambda *args: self.trace.append('fingerprint') or self.baseline).start()
        def prepare(*args):
            self.assertFalse(self.frozen)
            self.trace.append('prepare'); return {'passed': True}
        self.prepare = patch.object(restore, 'prepare_service', side_effect=prepare).start()
        self.docker = patch.object(restore, 'docker', side_effect=AssertionError('No raw Docker mutations in callback orchestration')).start()

    def test_callback_follows_exact_fingerprint_while_frozen_and_precedes_preparation(self):
        output = self.apply_output()
        def callback(config, destination, runtime, info, db):
            self.trace.append('callback'); self.assertTrue(self.frozen)
            self.assertEqual(json.loads((destination / 'restore.json').read_text())['state'], 'ALL_ROWS_AND_DDL_VERIFIED')
            evidence = destination / 'unit-baseline.json'; evidence.write_text('{"unit":true}')
            return {'searchBaselineReceipt': evidence}
        with contextlib.redirect_stdout(io.StringIO()):
            report = restore.apply_fresh_plan(self.config, self.plan(), output, self.root, before_preparation=callback)
        self.assertLess(self.trace.index('fingerprint'), self.trace.index('callback'))
        self.assertLess(self.trace.index('callback'), self.trace.index('prepare'))
        self.assertTrue(self.frozen)
        self.assertEqual(report['beforePreparationArtifacts']['searchBaselineReceipt'],
                         {'file': 'unit-baseline.json', 'sha256': restore.sha(output / 'unit-baseline.json')})
        self.assertEqual(report['state'], 'PREPARED_APP_STOPPED_DATABASE_FROZEN')
        self.docker.assert_not_called()

    def test_callback_failure_freezes_and_retains_target_without_preparing(self):
        output = self.apply_output()
        callback = MagicMock(side_effect=RuntimeError('unit callback failure'))
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises(RuntimeError):
            restore.apply_fresh_plan(self.config, self.plan(), output, self.root, before_preparation=callback)
        report = json.loads((output / 'restore.json').read_text())
        self.assertEqual(report['state'], 'FAILED_RESOURCES_RETAINED')
        self.assertTrue(report['databaseFrozenAfterFailure']); self.assertTrue(self.frozen)
        self.prepare.assert_not_called(); self.docker.assert_not_called()

    def test_failed_full_fingerprint_never_invokes_callback_or_preparation(self):
        self.fingerprint.side_effect = None; self.fingerprint.return_value = {'different': True}
        callback = MagicMock()
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'Complete restored rows'):
            restore.apply_fresh_plan(self.config, self.plan(), self.apply_output(), self.root, before_preparation=callback)
        callback.assert_not_called(); self.prepare.assert_not_called(); self.assertTrue(self.frozen)

    def test_private_or_external_callback_evidence_cannot_enter_receipt(self):
        for place in ('private', 'external'):
            output = self.root / place; output.mkdir()
            def callback(config, destination, *args):
                path = destination / '.private/secret.json' if place == 'private' else self.root / 'external.json'
                path.write_text('{"unit":true}')
                return {'evidence': path}
            with self.subTest(place=place), contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'public files'):
                restore.apply_fresh_plan(self.config, self.plan(), output, self.root, before_preparation=callback)
        self.prepare.assert_not_called(); self.assertTrue(self.frozen)

    def test_default_callback_is_absent_and_existing_final_state_is_unchanged(self):
        with contextlib.redirect_stdout(io.StringIO()):
            report = restore.apply_fresh_plan(self.config, self.plan(), self.apply_output(), self.root)
        self.assertNotIn('beforePreparationArtifacts', report)
        self.assertEqual(report['state'], 'PREPARED_APP_STOPPED_DATABASE_FROZEN')

    def test_unverified_freeze_cannot_receive_a_success_state(self):
        self.db.identity.side_effect = lambda: {'version': '8.4.11', 'uuid': '01234567-89ab-cdef-0123-456789abcdef', 'superReadOnly': 0}
        output = self.apply_output()
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'did not remain frozen'):
            restore.apply_fresh_plan(self.config, self.plan(), output, self.root)
        self.assertEqual(restore.read(output / 'restore.json')['state'], 'FAILED_RESOURCES_RETAINED')

    def test_created_image_drift_retains_target_before_import_or_preparation(self):
        self.inspect.return_value['Image'] = 'sha256:' + '9' * 64
        with contextlib.redirect_stdout(io.StringIO()), self.assertRaisesRegex(ValueError, 'Created MySQL image'):
            restore.apply_fresh_plan(self.config, self.plan(), self.apply_output(), self.root)
        self.assertNotIn('stream', self.trace); self.prepare.assert_not_called()
        self.assertTrue(self.frozen)

    def test_legacy_replacement_still_removes_reviewed_source_then_uses_same_target_checks(self):
        legacy = dict(self.config); legacy.pop('operation'); legacy.pop('diskObserver')
        legacy.update(source={'mysqlServerUuid': 'fedcba98-7654-3210-fedc-ba9876543210'}, preservationPolicy='require-current-match')
        (self.release / 'consumer-manifest.json').write_text(json.dumps({'artifacts': {'dump': {'sha256': 'a' * 64}}}))
        def remove(*args): self.trace.append('remove-source')
        with patch.object(restore, 'remove_source', side_effect=remove), contextlib.redirect_stdout(io.StringIO()):
            report = restore.apply_plan(legacy, {'source': {'unit': True}}, self.apply_output(), self.root)
        self.assertLess(self.trace.index('remove-source'), self.trace.index('create'))
        self.assertEqual(report['state'], 'PREPARED_APP_STOPPED_DATABASE_FROZEN')
        self.assertNotIn('beforePreparationArtifacts', report)

    def test_retired_replacement_keeps_retirement_validation_and_never_removes_again(self):
        legacy = dict(self.config); legacy.pop('operation'); legacy.pop('diskObserver')
        legacy.update(source={'mysqlServerUuid': 'fedcba98-7654-3210-fedc-ba9876543210', 'retirementReceipt': '/unit/retired.json'},
                      preservationPolicy='require-current-match')
        (self.release / 'consumer-manifest.json').write_text(json.dumps({'artifacts': {'dump': {'sha256': 'a' * 64}}}))
        retired = {'retirementReceiptSha256': 'b' * 64, 'dockerFreeBytes': 1024**3}
        with patch.object(restore, 'retired_observation', return_value=retired) as observe, \
             patch.object(restore, 'preservation') as preserved, patch.object(restore, 'remove_source') as remove, \
             contextlib.redirect_stdout(io.StringIO()):
            report = restore.apply_plan(legacy, {'source': retired}, self.apply_output(), self.root)
        observe.assert_called_once(); preserved.assert_called_once(); remove.assert_not_called()
        self.assertEqual(report['state'], 'PREPARED_APP_STOPPED_DATABASE_FROZEN')


class MainFreshPlanTest(FreshFixture):
    def setUp(self):
        super().setUp()
        # Host runtime boundaries are exercised independently in test_growth_b_runtime.
        for name, value in (('qualify_runtime', {}), ('activated_runtime', contextlib.nullcontext()),
                            ('qualification_binding', {'sha256': 'a' * 64, 'execution': 'unit fixture'})):
            item = patch.object(restore, name, return_value=value)
            self.addCleanup(item.stop); item.start()

    def test_wrong_reviewed_plan_hash_cannot_reach_apply_and_releases_shared_lock(self):
        path = self.root / 'config.json'; path.write_text(json.dumps(self.config))
        plan = self.root / 'preflight.json'; plan.write_text(json.dumps(self.plan()))
        lock = MagicMock()
        with patch.object(sys, 'argv', ['restore-growth-b-local.py', '--config', str(path), '--output', str(self.root / 'out'),
                                       '--apply', '--preflight', str(plan), '--preflight-sha256', '0' * 64]), \
             patch.object(restore, 'require_local_engine'), patch.object(restore, 'validate_inputs'), \
             patch.object(restore, 'extract_runtime', return_value=self.root / 'runtime'), \
             patch.object(restore, 'acquire_fresh_run_lock', return_value=lock) as acquire, \
             patch.object(restore, 'apply_fresh_plan') as apply, patch.object(restore, 'apply_plan') as old_apply:
            with self.assertRaisesRegex(ValueError, 'exact reviewed preflight SHA'):
                restore.main()
            acquire.assert_called_once(); lock.close.assert_called_once()
            apply.assert_not_called(); old_apply.assert_not_called()

    def test_correct_plan_dispatches_trusted_internal_callback_only_to_fresh_apply(self):
        path = self.root / 'config.json'; path.write_text(json.dumps(self.config))
        plan = self.root / 'preflight.json'; plan.write_text(json.dumps(self.plan()))
        callback = MagicMock()
        with patch.object(sys, 'argv', ['restore-growth-b-local.py', '--config', str(path), '--output', str(self.root / 'out'),
                                       '--apply', '--preflight', str(plan), '--preflight-sha256', restore.sha(plan)]), \
             patch.object(restore, 'require_local_engine'), patch.object(restore, 'validate_inputs'), \
             patch.object(restore, 'extract_runtime', return_value=self.root / 'runtime'), \
             patch.object(restore, 'acquire_fresh_run_lock', return_value=MagicMock()), \
             patch.object(restore, 'apply_fresh_plan') as apply, patch.object(restore, 'apply_plan') as old_apply:
            restore.main(before_preparation=callback)
            apply.assert_called_once(); old_apply.assert_not_called()
            self.assertIs(apply.call_args.kwargs['before_preparation'], callback)

    def test_preflight_never_executes_the_optional_callback(self):
        path = self.root / 'config.json'; path.write_text(json.dumps(self.config))
        callback = MagicMock()
        with patch.object(sys, 'argv', ['restore-growth-b-local.py', '--config', str(path), '--output', str(self.root / 'out')]), \
             patch.object(restore, 'require_local_engine'), patch.object(restore, 'validate_inputs'), \
             patch.object(restore, 'extract_runtime', return_value=self.root / 'runtime'), \
             patch.object(restore, 'acquire_fresh_run_lock', return_value=MagicMock()), \
             patch.object(restore, 'build_fresh_preflight', return_value=self.plan()) as build, \
             patch.object(restore, 'apply_fresh_plan') as apply, contextlib.redirect_stdout(io.StringIO()):
            restore.main(before_preparation=callback)
            build.assert_called_once(); apply.assert_not_called(); callback.assert_not_called()


if __name__ == '__main__':
    unittest.main()
