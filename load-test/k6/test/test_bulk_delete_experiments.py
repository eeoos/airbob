import importlib.util
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SUITE = HERE.parent / 'bulk-write'
sys.path.insert(0, str(SUITE))
from bulk_delete_experiment import comparison, plan, stats, validate, validate_runtime

spec = importlib.util.spec_from_file_location('bulk_delete_runner', SUITE / 'run-experiments.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class BulkDeleteExperimentsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.c = json.loads((SUITE / 'local-experiment.example.json').read_text())
        self.c.update(example=False, rounds=2, samples=2, warmupSamples=1, wishlistSizes=[3], amenitySizes=[3])
        for name, value in [('emailFile', 'benchmark@example.test'), ('passwordFile', 'private-password'),
                            ('tokenFile', 'private-token-' + 'x' * 32)]:
            path = self.root / name
            path.write_text(value)
            path.chmod(0o600)
            self.c['credentials'][name] = str(path)
        self.runtime = dict(schema_version='bulk-delete-runtime-v1', runtime_id='a' * 8 + '-' + 'b' * 27,
                            environment='local', app_commit=self.c['appCommit'], image_digest='local',
                            schema_label=self.c['schemaLabel'], flyway_version='28',
                            database_id='c' * 8 + '-' + 'd' * 27, jvm_version='21.0.12',
                            mysql_version='8.0.33', rewrite_batched_statements=True, pool_size=10)
        self.calls = []

    def fake_command(self, command, env, cwd, timeout):
        self.calls.append((command, env.copy()))
        output = cwd / (env['K6_RESULT_PATH'] if env['PHASE'] == 'warmup' else env['RAW_OBSERVATION_RESULT_PATH'])
        output.parent.mkdir(parents=True, exist_ok=True)
        if env['PHASE'] == 'warmup':
            output.write_text('{}')
            return
        block = next(b for b in plan(self.c)['blocks'] if b['label'] == env['RUN_LABEL'])
        metadata = dict(candidate=block['candidate'], variant=block['variant'], phase='measure',
                        dataset_size=3, samples=2, run_label=block['label'], round=block['round'],
                        run_order=block['runOrder'], app_commit=self.c['appCommit'], app_instance_count=1,
                        schema_label=self.c['schemaLabel'], jvm_version='21.0.12', mysql_version='8.0.33',
                        rewrite_batched_statements=True)
        if block['measurement']:
            metadata.update(measurement=block['measurement'], workload_class='REALISTIC', active_amenity_code_count=30)
        observations = [dict(server_operation_ms=10 if block['variant'] == 'BEFORE' else 2,
                             verification=dict(succeeded=True), hibernate_statements_by_type={'TOTAL': 3})] * 2
        output.write_text(json.dumps(dict(schema_version='bulk-write-observations-v1',
                                         metadata=metadata, observations=observations)))

    def test_plan_balances_ab_ba_and_counts_warmup_separately(self):
        p = plan(validate(self.c))
        self.assertEqual(len(p['blocks']), 12)
        self.assertEqual(p['measuredRequests'], 24)
        self.assertEqual(p['warmupRequests'], 12)
        self.assertEqual([b['variant'] for b in p['blocks'][:4]], ['BEFORE', 'AFTER', 'AFTER', 'BEFORE'])
        self.assertEqual([b['runOrder'] for b in p['blocks'][:4]], [1, 2, 1, 2])
        self.assertEqual(stats([1, 2, 3, 4])['p50'], 2)

    def test_rejects_incomplete_or_unsafe_configuration(self):
        for field, value in [('rounds', 3), ('samples', 0), ('schemaLabel', 'airbobdb'),
                             ('appCommit', 'short'), ('wishlistSizes', [1, 1]),
                             ('amenitySizes', [101]), ('baseUrl', 'http://server:8080'),
                             ('runId', '../escape'), ('rewriteBatchedStatements', 'true')]:
            with self.subTest(field=field):
                invalid = dict(self.c, **{field: value})
                with self.assertRaises(ValueError):
                    validate(invalid)
        aws = json.loads((SUITE / 'aws-experiment.example.json').read_text())
        validate(aws)
        for value in ['http://lab.example.test', 'https://api.airbob.cloud', 'https://user:secret@lab.example.test',
                      'https://lab.example.test/path']:
            with self.assertRaises(ValueError):
                validate(dict(aws, baseUrl=value))

    def test_every_runtime_identity_and_setting_is_checked(self):
        validate_runtime(self.c, self.runtime)
        for key, value in [('app_commit', 'b' * 40), ('environment', 'aws'), ('flyway_version', '27'),
                           ('schema_label', 'airbobdb'), ('image_digest', 'latest'), ('pool_size', 11),
                           ('rewrite_batched_statements', False), ('jvm_version', 'http://private')]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_runtime(self.c, dict(self.runtime, **{key: value}))

    def test_credentials_must_be_private_regular_files(self):
        runner.credentials(self.c)
        path = Path(self.c['credentials']['passwordFile'])
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            runner.credentials(self.c)
        path.chmod(0o600)
        link = self.root / 'password-link'
        link.symlink_to(path)
        self.c['credentials']['passwordFile'] = str(link)
        with self.assertRaises(OSError):
            runner.credentials(self.c)

    def test_complete_execution_preserves_pairs_and_excludes_warmup(self):
        output = runner.execute(self.c, root=self.root, probe_server=lambda *_: self.runtime,
                                run_command=self.fake_command)
        report = json.loads((output / 'comparison.json').read_text())
        self.assertEqual(len(report['comparisons']), 3)
        self.assertEqual(len(self.calls), 24)
        for item in report['comparisons']:
            self.assertEqual(item['p50ReductionPercent'], 80)
            self.assertEqual(item['variants']['BEFORE']['serverOperationMs']['count'], 4)
        raw = (output / 'comparison.json').read_text()
        self.assertNotIn('private-password', raw)
        self.assertNotIn('benchmark@example.test', raw)
        self.assertTrue(all('BULK_DELETE_RUNTIME_ID' in env for _, env in self.calls))

    def test_child_environment_does_not_inherit_k6_debug_or_destinations(self):
        with patch.dict(os.environ, K6_HTTP_DEBUG='full', K6_OUT='json=/tmp/leak', NODE_OPTIONS='--inspect'):
            env = runner.child_environment(self.c, self.runtime, runner.credentials(self.c), plan(self.c)['blocks'][0])
        self.assertNotIn('K6_HTTP_DEBUG', env)
        self.assertNotIn('K6_OUT', env)
        self.assertNotIn('NODE_OPTIONS', env)

    def test_aws_uses_the_same_blocks_and_binds_the_declared_image(self):
        self.c.update(environment='aws', baseUrl='https://bulk-delete.lab.example.test',
                      imageDigest='sha256:' + 'b' * 64, runId='bulk-delete-aws-test')
        self.runtime.update(environment='aws', image_digest=self.c['imageDigest'])
        validate_runtime(self.c, self.runtime)
        output = runner.execute(self.c, root=self.root, probe_server=lambda *_: self.runtime,
                                run_command=self.fake_command)
        report = json.loads((output / 'comparison.json').read_text())
        self.assertEqual(report['environment'], 'aws')
        self.assertEqual(report['runtime']['image_digest'], self.c['imageDigest'])
        self.assertTrue(all(env['BULK_DELETE_ENVIRONMENT'] == 'aws' for _, env in self.calls))

    def test_restart_or_wrong_artifact_cannot_produce_success_report(self):
        count = 0

        def restarting_probe(*_):
            nonlocal count
            count += 1
            return self.runtime if count < 3 else dict(self.runtime, runtime_id='e' * 36)

        with self.assertRaisesRegex(ValueError, 'restarted'):
            runner.execute(self.c, root=self.root, probe_server=restarting_probe, run_command=self.fake_command)
        output = self.root / 'build/k6/bulk-delete' / self.c['runId']
        self.assertFalse((output / 'comparison.json').exists())
        self.assertEqual(json.loads((output / 'failure.json').read_text())['completedBlocks'], 1)
        with self.assertRaises(FileExistsError):
            runner.execute(self.c, root=self.root, probe_server=lambda *_: self.runtime, run_command=self.fake_command)

    def test_failed_warmup_stops_before_measurement(self):
        def failure(*_):
            raise ValueError('failed warmup')
        with self.assertRaises(ValueError):
            runner.execute(self.c, root=self.root, probe_server=lambda *_: self.runtime, run_command=failure)
        output = self.root / 'build/k6/bulk-delete' / self.c['runId']
        self.assertFalse((output / 'comparison.json').exists())
        self.assertEqual(json.loads((output / 'failure.json').read_text())['completedBlocks'], 0)

    def test_example_never_calls_server(self):
        with self.assertRaisesRegex(ValueError, 'Example'):
            runner.execute(dict(self.c, example=True), root=self.root,
                           probe_server=lambda *_: self.fail('Must not call server'))

    def test_offline_bundle_contains_tools_without_credentials_or_cloud_calls(self):
        with patch('subprocess.Popen', side_effect=AssertionError('No subprocess allowed')):
            output = runner.prepare(self.c, self.root / 'prepared')
        schedule = json.loads((output / 'plan.json').read_text())
        self.assertFalse(schedule['readyToExecute'])
        self.assertEqual(schedule['networkCallsPerformed'], 0)
        with tarfile.open(output / 'bulk-delete-tools.tar.gz') as archive:
            names = archive.getnames()
            self.assertIn('load-test/k6/lib/bulk-delete-runtime.js', names)
            self.assertTrue(all(not n.startswith('/') and '..' not in Path(n).parts for n in names))
            contents = b''.join(archive.extractfile(n).read() for n in names)
            self.assertNotIn(b'private-password', contents)
        with self.assertRaises(FileExistsError):
            runner.prepare(self.c, output)


if __name__ == '__main__':
    unittest.main()
