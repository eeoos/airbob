"""Hermetic consumer-host runtime, binding, and before-connection gates."""
from contextlib import nullcontext
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_runtime as runtime
import growth_b_aws_restore as aws_restore
import growth_b_search as search
spec = importlib.util.spec_from_file_location('consumer_host_local_restore', ROOT / 'scripts/restore-growth-b-local.py')
local = importlib.util.module_from_spec(spec); spec.loader.exec_module(local)


class HostRuntimeFixture(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.release = self.root / 'release'; self.release.mkdir()
        self.extracted = self.root / 'runtime'; (self.extracted / 'tools').mkdir(parents=True)
        self.tool = self.extracted / 'tools/growth_timezones.py'; self.tool.write_text('# authenticated unit temporal tool\n')
        self.home = self.root / 'consumer-linux-jdk'; (self.home / 'bin').mkdir(parents=True)
        for name in ('java', 'javac'):
            path = self.home / 'bin' / name; path.write_text('unit-' + name); path.chmod(0o700)
        tzdb = self.home / 'lib/tzdb.dat'; tzdb.parent.mkdir(); tzdb.write_bytes(b'unit consumer TZDB')
        self.zones = ['America/Phoenix', 'Asia/Seoul']
        self.files = {'tool-sources.json': {'growth_timezones.py': runtime.sha(self.tool)},
                      'source-provenance.json': {'coordinateTimeZones': {'resolvedZoneIds': self.zones}},
                      'timezone-qualification.json': {'java': {'javaHome': '/producer/mac-jdk',
                           'javaVersion': '21.0.12.1', 'javaVendor': 'different producer vendor',
                           'executableSha256': 'a' * 64}}}
        for name, value in self.files.items(): (self.release / name).write_text(json.dumps(value))
        self.expected_checks = {name: runtime.sha(self.release / name) for name in self.files}
        (self.release / 'SHA256SUMS.json').write_text(json.dumps(self.expected_checks))
        self.observation = {'schemaVersion': 1, 'kind': 'airbob-growth-timezone-qualification',
            'state': 'TIMEZONE_RUNTIME_QUALIFIED', 'passed': True, 'allFixedCasesPassed': True,
            'runtimeIdentityPassed': True, 'selectedZonesPassed': True, 'scope': {'selectedZones': self.zones},
            'qualificationSourceSha256': runtime.sha(self.tool),
            'java': {'javaHome': str(self.home.resolve()), 'requestedJavaHome': str(self.home.resolve()),
                     'javaExecutable': str((self.home / 'bin/java').resolve()), 'javaFeature': 21,
                     'javaVersion': '21.0.12', 'javaRuntimeVersion': '21.0.12+8', 'javaVendor': 'consumer vendor',
                     'executableSha256': runtime.sha(self.home / 'bin/java'),
                     'tzdbFile': {'path': str(tzdb), 'sha256': runtime.sha(tzdb), 'bytes': tzdb.stat().st_size}}}

    def qualify(self, output=None):
        module = Mock(); module.runtime_qualification.return_value = copy.deepcopy(self.observation)
        with patch.object(runtime, '_load_tool', return_value=module) as load, \
             patch.object(runtime.subprocess, 'run', return_value=Mock(returncode=0, stdout='javac 21.0.12\n')) as compiler:
            report = runtime.qualify_runtime(self.release, self.extracted, output or self.root / 'host.json',
                                             java_home=self.home, expected_checks=self.expected_checks)
        return report, load, module, compiler


class ConsumerHostRuntimeTest(HostRuntimeFixture):
    def test_different_consumer_os_vendor_and_patch_are_observed_instead_of_requiring_producer_binary_equality(self):
        report, load, module, compiler = self.qualify()
        self.assertTrue(report['consumerRuntimePassed'])
        self.assertFalse(report['consumerHost']['producerBinaryEqualityRequired'])
        self.assertFalse(report['consumerHost']['remoteExecutionClaimed'])
        self.assertEqual(report['java']['javaVersion'], '21.0.12')
        load.assert_called_once_with(self.tool, runtime.sha(self.tool))
        module.runtime_qualification.assert_called_once_with(java_home=self.home, selected_zones=self.zones)
        self.assertEqual(compiler.call_args.args[0][0], str((self.home / 'bin/javac').resolve()))

    def test_changed_tool_is_rejected_before_import_or_java_execution(self):
        self.tool.write_text('raise RuntimeError("unreviewed")')
        with patch.object(runtime, '_load_tool') as load, patch.object(runtime.subprocess, 'run') as execute:
            with self.assertRaises(RuntimeError): runtime.qualify_runtime(self.release, self.extracted, self.root / 'rejected.json', expected_checks=self.expected_checks)
            load.assert_not_called(); execute.assert_not_called()
        self.assertFalse(json.loads((self.root / 'rejected.json').read_text())['passed'])

    def test_changed_source_coordinate_scope_is_rejected_before_loading_tools(self):
        (self.release / 'source-provenance.json').write_text('{"coordinateTimeZones":{"resolvedZoneIds":["UTC"]}}')
        with patch.object(runtime, '_load_tool') as load:
            with self.assertRaises(RuntimeError): runtime.qualify_runtime(self.release, self.extracted, self.root / 'rejected.json', expected_checks=self.expected_checks)
            load.assert_not_called()

    def test_rewritten_checksum_file_cannot_replace_the_previously_validated_source_scope(self):
        path = self.release / 'source-provenance.json'; path.write_text('{"coordinateTimeZones":{"resolvedZoneIds":["UTC"]}}')
        changed = dict(self.expected_checks, **{'source-provenance.json': runtime.sha(path)})
        (self.release / 'SHA256SUMS.json').write_text(json.dumps(changed))
        with patch.object(runtime, '_load_tool') as load:
            with self.assertRaises(RuntimeError):
                runtime.qualify_runtime(self.release, self.extracted, self.root / 'rejected.json', expected_checks=self.expected_checks)
            load.assert_not_called()

    def test_tool_loader_executes_the_authenticated_buffer_without_rereading_changed_code(self):
        self.tool.write_text('VALUE = "authenticated"\n')
        approved = self.tool.read_bytes(); expected = runtime.sha(self.tool)
        def read_then_replace(path):
            path.write_text('raise AssertionError("unreviewed replacement must not execute")\n')
            return approved
        with patch.object(Path, 'read_bytes', autospec=True, side_effect=read_then_replace):
            loaded = runtime._load_tool(self.tool, expected)
        self.assertEqual(loaded.VALUE, 'authenticated')

    def test_stale_semantics_unsupported_patch_or_forged_tool_source_cannot_pass(self):
        for failure in ('semantics', 'patch', 'source', 'scope'):
            with self.subTest(failure=failure):
                original = copy.deepcopy(self.observation)
                if failure == 'semantics': self.observation['allFixedCasesPassed'] = False
                if failure == 'patch': self.observation['java']['javaVersion'] = '21.0.6'
                if failure == 'source': self.observation['qualificationSourceSha256'] = 'f' * 64
                if failure == 'scope': self.observation['scope']['selectedZones'] = ['Asia/Seoul']
                with self.assertRaises(RuntimeError): self.qualify(self.root / (failure + '.json'))
                self.assertFalse(json.loads((self.root / (failure + '.json')).read_text())['consumerRuntimePassed'])
                self.observation = original

    def test_same_home_compiler_version_must_match_the_observed_jvm(self):
        module = Mock(); module.runtime_qualification.return_value = self.observation
        with patch.object(runtime, '_load_tool', return_value=module), \
             patch.object(runtime.subprocess, 'run', return_value=Mock(returncode=0, stdout='javac 21.0.6\n')):
            with self.assertRaises(RuntimeError): runtime.qualify_runtime(self.release, self.extracted, self.root / 'rejected.json', expected_checks=self.expected_checks)

    def test_environment_binds_both_bare_java_and_javac_and_removes_injection(self):
        report, *_ = self.qualify()
        env = runtime.qualified_environment(report, {'PATH': '/usr/bin', 'JAVA_HOME': '/wrong',
            'JAVA_TOOL_OPTIONS': 'private-unit', 'JAVA_OPTS': 'private-unit', 'TLS_SETTING': 'preserved'})
        self.assertEqual(env['JAVA_HOME'], str(self.home))
        self.assertEqual(env['TLS_SETTING'], 'preserved')
        self.assertFalse(runtime.JVM_INJECTION & env.keys())
        for name in ('java', 'javac'):
            self.assertEqual(shutil.which(name, path=env['PATH']), str(self.home / 'bin' / name))

    def test_byte_drift_after_qualification_blocks_subsequent_use(self):
        for name in ('java', 'javac', 'tzdb', 'tool'):
            with self.subTest(name=name):
                report, *_ = self.qualify(self.root / (name + '.json'))
                target = {'java': self.home / 'bin/java', 'javac': self.home / 'bin/javac',
                          'tzdb': self.home / 'lib/tzdb.dat', 'tool': self.tool}[name]
                original = target.read_bytes(); target.write_bytes(original + b'changed')
                with self.assertRaises(ValueError): runtime.qualified_environment(report)
                target.write_bytes(original)

    def test_activation_binds_local_app_environment_and_restores_parent_environment_on_failure(self):
        report, *_ = self.qualify()
        with patch.dict(os.environ, {'JAVA_HOME': '/old', 'JAVA_OPTS': 'unit-original', 'PATH': '/usr/bin'}):
            with self.assertRaisesRegex(RuntimeError, 'unit stop'):
                with runtime.activated_runtime(report):
                    self.assertEqual(local.runtime_environment()['JAVA_HOME'], str(self.home))
                    self.assertEqual(shutil.which('java'), str(self.home / 'bin/java'))
                    self.assertNotIn('JAVA_OPTS', os.environ)
                    raise RuntimeError('unit stop')
            self.assertEqual(os.environ['JAVA_HOME'], '/old')
            self.assertEqual(os.environ['JAVA_OPTS'], 'unit-original')
            self.assertEqual(os.environ['PATH'], '/usr/bin')


class EntryBeforeConnectionTest(HostRuntimeFixture):
    def test_local_success_path_carries_the_qualified_jdk_into_preflight_and_records_it(self):
        report, *_ = self.qualify()
        output = self.root / 'local-positive'
        config = {'release': str(self.release), 'operation': 'fresh-empty-install'}
        def qualify(release, extracted, path, **kwargs):
            self.assertEqual(kwargs['expected_checks'], self.expected_checks)
            runtime._write(path, report); return report
        def preflight(*args):
            self.assertEqual(local.runtime_environment()['JAVA_HOME'], str(self.home))
            self.assertEqual(shutil.which('java'), str(self.home / 'bin/java'))
            return {'state': 'PREFLIGHT_READY'}
        with patch.dict(os.environ, {'JAVA_HOME': '/old', 'PATH': '/usr/bin'}), \
             patch.object(sys, 'argv', ['restore', '--config', 'unit', '--output', str(output)]), \
             patch.object(local, 'configuration', return_value=config), \
             patch.object(local, 'validate_inputs', return_value=({}, self.expected_checks, {})), \
             patch.object(local, 'extract_runtime', return_value=self.extracted), \
             patch.object(local, 'qualify_runtime', side_effect=qualify), patch.object(local, 'require_local_engine'), \
             patch.object(local, 'acquire_fresh_run_lock', return_value=Mock()), \
             patch.object(local, 'build_fresh_preflight', side_effect=preflight), patch('sys.stdout', new_callable=io.StringIO):
            local.main()
            self.assertEqual(os.environ['JAVA_HOME'], '/old')
        plan = json.loads((output / 'preflight.json').read_text())
        self.assertEqual(plan['hostRuntimeQualification']['sha256'], runtime.sha(output / 'host-runtime-qualification.json'))

    def test_local_fresh_and_oci_replacement_fail_before_docker_or_database_observation(self):
        for mode, operation in (('local', 'fresh-empty-install'), ('oci', 'replace-existing')):
            for apply in (False, True):
                with self.subTest(mode=mode, apply=apply):
                    output = self.root / (mode + str(apply)); trace = []
                    config = {'release': str(self.release), 'operation': operation}
                    arguments = ['restore', '--config', 'unit.json', '--output', str(output)] + (['--apply'] if apply else [])
                    with patch.object(sys, 'argv', arguments), patch.object(local, 'configuration', return_value=config), \
                         patch.object(local, 'validate_inputs', side_effect=lambda value: trace.append('validated') or ({}, self.expected_checks, {})), \
                         patch.object(local, 'extract_runtime', side_effect=lambda *args: trace.append('extracted') or self.extracted), \
                         patch.object(local, 'qualify_runtime', side_effect=RuntimeError('rejected runtime')) as qualify, \
                         patch.object(local, 'require_local_engine') as engine, patch.object(local, 'Database') as db, \
                         patch.object(local, 'build_preflight') as preflight, patch.object(local, 'apply_plan') as execute:
                        with self.assertRaises(RuntimeError): local.main(mode)
                        self.assertEqual(trace, ['validated', 'extracted'])
                        qualify.assert_called_once(); engine.assert_not_called(); db.assert_not_called()
                        preflight.assert_not_called(); execute.assert_not_called()

    def aws_inputs(self):
        config_path = self.root / 'aws-config.json'; config_path.write_text('{}')
        preflight_path = self.root / 'aws-preflight.json'
        preflight_path.write_text(json.dumps({'configSha256': runtime.sha(config_path)}))
        config = {'release': str(self.release), 'lease': {}}
        receipt = {'state': 'OFFLINE_PLAN', 'awsExecutionAllowed': True}
        return config_path, preflight_path, config, receipt

    def test_aws_offline_plan_never_starts_runtime_or_aws_connections(self):
        config_path, _, config, receipt = self.aws_inputs()
        with patch.object(sys, 'argv', ['restore', 'plan', '--config', str(config_path), '--output', str(self.root / 'plan')]), \
             patch.object(aws_restore, 'configuration', return_value=config), patch.object(aws_restore, 'validate_inputs', return_value={}), \
             patch.object(aws_restore, 'offline_plan', return_value=receipt), patch.object(aws_restore, 'extract_runtime') as extract, \
             patch.object(aws_restore, 'qualify_runtime') as qualify, patch.object(aws_restore, 'Aws') as aws, \
             patch('sys.stdout', new_callable=io.StringIO):
            aws_restore.main()
            extract.assert_not_called(); qualify.assert_not_called(); aws.assert_not_called()

    def test_aws_preflight_and_execute_qualify_before_authentication_or_database_connection(self):
        config_path, preflight_path, config, receipt = self.aws_inputs()
        for mode in ('preflight', 'execute'):
            with self.subTest(mode=mode):
                args = ['restore', mode, '--config', str(config_path), '--output', str(self.root / mode)]
                if mode == 'execute': args += ['--preflight', str(preflight_path), '--preflight-sha256', runtime.sha(preflight_path)]
                with patch.object(sys, 'argv', args), patch.object(aws_restore, 'configuration', return_value=config), \
                     patch.object(aws_restore, 'validate_inputs', return_value={'objects': {}}), \
                     patch.object(aws_restore, 'offline_plan', side_effect=lambda *args: copy.deepcopy(receipt)), \
                     patch.object(aws_restore, 'execution_scope', return_value='small-b-rds'), \
                     patch.object(aws_restore, 'extract_runtime', return_value=self.extracted), \
                     patch.object(aws_restore, 'qualify_runtime', side_effect=RuntimeError('rejected runtime')) as qualify, \
                     patch.object(aws_restore, 'Aws') as aws, patch.object(aws_restore, 'connection') as connection:
                    with self.assertRaises(SystemExit): aws_restore.main()
                    qualify.assert_called_once(); aws.assert_not_called(); connection.assert_not_called()

    def test_aws_success_paths_carry_the_qualified_environment_into_service_preparation(self):
        report, *_ = self.qualify()
        config_path, preflight_path, config, receipt = self.aws_inputs()
        envelope = {'objects': {name: {'sha256': value} for name, value in self.expected_checks.items()},
                    'finalScaleSelected': False}
        def qualify(release, extracted, path, **kwargs):
            self.assertEqual(kwargs['expected_checks'], self.expected_checks)
            runtime._write(path, report); return report
        def connection(*args):
            self.assertEqual(os.environ['JAVA_HOME'], str(self.home))
            self.assertEqual(shutil.which('java'), str(self.home / 'bin/java'))
            return Mock(), dict(os.environ, JAVA_OPTS='-Xmx1536m -Duser.timezone=UTC')
        def check_environment(environment):
            self.assertEqual(environment['JAVA_HOME'], str(self.home))
            self.assertEqual(shutil.which('java', path=environment['PATH']), str(self.home / 'bin/java'))
            self.assertEqual(environment['JAVA_OPTS'], '-Xmx1536m -Duser.timezone=UTC')
        def preflight(*args):
            check_environment(args[6]); return {'state': 'READ_ONLY_PREFLIGHT'}
        def execute(*args, **kwargs):
            check_environment(args[7]); args[9]('UNIT_EXECUTED')
        for mode in ('preflight', 'execute'):
            with self.subTest(mode=mode):
                output = self.root / ('positive-' + mode)
                args = ['restore', mode, '--config', str(config_path), '--output', str(output)]
                if mode == 'execute': args += ['--preflight', str(preflight_path), '--preflight-sha256', runtime.sha(preflight_path)]
                with patch.dict(os.environ, {'JAVA_HOME': '/old', 'PATH': '/usr/bin'}), patch.object(sys, 'argv', args), \
                     patch.object(aws_restore, 'configuration', return_value=config), \
                     patch.object(aws_restore, 'validate_inputs', return_value=envelope), \
                     patch.object(aws_restore, 'offline_plan', side_effect=lambda *args: copy.deepcopy(receipt)), \
                     patch.object(aws_restore, 'execution_scope', return_value='small-b-rds'), \
                     patch.object(aws_restore, 'extract_runtime', return_value=self.extracted), \
                     patch.object(aws_restore, 'qualify_runtime', side_effect=qualify), \
                     patch.object(aws_restore, 'Aws'), patch.object(aws_restore, 'live_rds', return_value={}), \
                     patch.object(aws_restore, 'Lease'), patch.object(aws_restore, 'connection', side_effect=connection), \
                     patch.object(aws_restore, 'preflight', side_effect=preflight), \
                     patch.object(aws_restore, 'execute', side_effect=execute), patch('sys.stdout', new_callable=io.StringIO):
                    aws_restore.main()
                    self.assertEqual(os.environ['JAVA_HOME'], '/old')

    def test_source_adapter_authenticates_and_qualifies_before_secrets_compilation_or_jdbc(self):
        with patch.object(search, 'release_identity', return_value=({}, {}, {})) as validate, \
             patch.object(search, 'extract_runtime', return_value=self.extracted) as extract, \
             patch.object(search, 'qualify_runtime', side_effect=RuntimeError('rejected runtime')) as qualify, \
             patch.object(search, 'properties') as secrets, patch.object(search, 'run') as execute:
            with self.assertRaises(RuntimeError):
                search.SourceAdapter({'releaseDirectory': str(self.release)}, self.root / 'source')
            validate.assert_called_once(); extract.assert_called_once(); qualify.assert_called_once()
            secrets.assert_not_called(); execute.assert_not_called()

    def test_all_native_entrypoints_reject_runtime_before_es_or_repository_access(self):
        config = {'datasetId': 'global-growth-b-' + 'a' * 16,
                  'snapshotRelease': 'global-growth-b-' + 'a' * 16 + '-search-unit'}
        operations = [lambda out: search.capture_baseline(config, out),
                      lambda out: search.produce(config, self.root / 'baseline', out),
                      lambda out: search.restore(config, self.root / 'companion', {}, self.root / 'baseline', out)]
        for index, operation in enumerate(operations):
            with self.subTest(index=index), patch.object(search, 'SourceAdapter', side_effect=RuntimeError('rejected runtime')) as source, \
                 patch.object(search, 'validate_companion', return_value=({}, {})), \
                 patch.object(search, 'Elasticsearch') as es, patch.object(search, 'repository_for') as repository:
                with self.assertRaises(RuntimeError): operation(self.root / ('native-' + str(index)))
                source.assert_called_once(); es.assert_not_called(); repository.assert_not_called()

    def test_native_compiler_and_jdbc_command_use_the_qualified_absolute_paths(self):
        report, *_ = self.qualify()
        (self.extracted / 'tools/growth_settings.py').write_text('BASE_SETTINGS = {}\ndef utc_jdbc_url(value): return value\n')
        config = {'releaseDirectory': str(self.release), 'mysql': {'jdbcUrl': 'jdbc:mysql://127.0.0.1:3306/airbobdb',
                  'passwordEnvironment': 'AIRBOB_UNIT_PASSWORD', 'username': 'unit',
                  'expectedServerUuid': '11111111-1111-1111-1111-111111111111'}}
        with patch.dict(os.environ, {'AIRBOB_UNIT_PASSWORD': 'private-unit-value', 'JAVA_HOME': '/old', 'PATH': '/usr/bin'}), \
             patch.object(search, 'release_identity', return_value=({}, {}, {})), \
             patch.object(search, 'extract_runtime', return_value=self.extracted), \
             patch.object(search, 'qualify_runtime', return_value=report), patch.object(search, 'run') as execute:
            adapter = search.SourceAdapter(config, self.root / 'source')
            self.assertEqual(execute.call_args.args[0][0], str(self.home / 'bin/javac'))
            self.assertEqual(adapter.command('identity', self.root / 'identity.json')[0], str(self.home / 'bin/java'))
            self.assertEqual(adapter.java_env['JAVA_HOME'], str(self.home))
            self.assertNotIn('private-unit-value', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
