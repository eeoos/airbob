"""V28 restore admission, source fidelity, deadlines, and cleanup boundaries."""
import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_v4_aws_contract as contract
import growth_v4_app_read as app

spec = importlib.util.spec_from_file_location('v4_bootstrap', SCRIPTS / 'bootstrap-growth-v4-aws.py')
bootstrap = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bootstrap)


class ContractTest(unittest.TestCase):
    def setUp(self):
        self.manifest = contract.read(Path(__file__).parents[1] / 'lab/tests/fixtures/growth-v4-aws-manifest.json')

    def test_v28_historical_inventory_and_original_version_coordinates(self):
        contract.validate_manifest(self.manifest, self.manifest['datasetRelease'])
        self.assertEqual(self.manifest['mysql']['expectedTableRows']['accommodation_inventory_day'], 26046)
        self.assertEqual(self.manifest['mysql']['expectedTableRows']['reservation'], 10268)
        self.assertTrue(all('/' + self.manifest['source']['datasetId'] + '/' in a['key']
                            for a in self.manifest['artifacts'].values()))

    def test_rejects_unreviewed_population_versions_and_claims(self):
        for label, mutate in [
            ('old migration', lambda m: m['mysql'].update(flywayVersion='27')),
            ('old engine', lambda m: m['mysql'].update(engineVersion='8.0.46')),
            ('missing inventory', lambda m: m['mysql']['expectedTableRows'].update(accommodation_inventory_day=0)),
            ('pending outbox', lambda m: m['mysql']['expectedTableRows'].update(outbox=1)),
            ('widened small', lambda m: m['mysql']['expectedTableRows'].update(reservation=2000268)),
            ('relabelled final', lambda m: m.update(datasetScale='selected-two-million')),
            ('large small dump', lambda m: m['artifacts']['airbob-growth.sql.gz'].update(bytes=10000001)),
            ('large small runtime', lambda m: m['artifacts']['preparation-tools.tar.gz'].update(bytes=20000001)),
            ('latest object', lambda m: m['artifacts']['profile.json'].update(versionId='null')),
            ('other prefix', lambda m: m['artifacts']['profile.json'].update(key='datasets/other/profile.json')),
            ('forged source', lambda m: m['source'].update(consumerManifestSha256='0'*64)),
            ('unbound search', lambda m: m.update(search={'enabled': True})),
        ]:
            with self.subTest(label=label), self.assertRaises(ValueError):
                changed = copy.deepcopy(self.manifest)
                mutate(changed)
                contract.validate_manifest(changed, self.manifest['datasetRelease'])

    def test_unsafe_archive_rejected_before_destination_creation(self):
        for name, kind in [('runtime/../../outside', tarfile.REGTYPE),
                           ('/runtime/outside', tarfile.REGTYPE),
                           ('runtime/link', tarfile.SYMTYPE), ('runtime/hard', tarfile.LNKTYPE)]:
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                with tarfile.open(root / 'preparation-tools.tar.gz', 'w:gz') as archive:
                    item = tarfile.TarInfo(name)
                    item.type = kind
                    item.linkname = '/tmp/other'
                    item.size = 0
                    archive.addfile(item, io.BytesIO(b''))
                with self.assertRaisesRegex(ValueError, 'Unsafe runtime entry'):
                    contract.extract_runtime(root, root / 'unpack')
                self.assertFalse((root / 'unpack').exists())

    def test_existing_database_is_never_dropped_or_imported(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(contract, 'validate_directory'), \
                patch.object(bootstrap, 'execute', side_effect=[b'8.4.11', b'32']) as execute:
            root = Path(directory)
            with self.assertRaisesRegex(ValueError, 'Target must be empty'):
                bootstrap.restore_and_verify(root, self.manifest, root, root, root / 'proof',
                    {'host': '127.0.0.1', 'username': 'test', 'password': 'synthetic'})
            self.assertEqual(execute.call_count, 2)
            self.assertFalse(any('DROP' in repr(call) for call in execute.call_args_list))

    def test_remote_mysql_requires_verified_tls(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'verified TLS'):
                bootstrap.write_client(Path(directory), {'host': 'remote.example', 'username': 'test', 'password': 'synthetic'})
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_wrong_mode_cannot_read_aws_secrets(self):
        with patch.dict(os.environ, {'AIRBOB_QUALIFICATION_ONLY': 'false', 'AIRBOB_DATABASE_BOOTSTRAP': 'dump'}), \
                patch.object(bootstrap, 'aws') as aws:
            with self.assertRaises(ValueError):
                bootstrap.main(Path('/absent'))
            aws.assert_not_called()

    def test_fingerprint_rejects_equal_counts_with_changed_rows(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(bootstrap, 'execute'):
            root = Path(directory)
            expected = {'tables': {'review': {'rows': 4, 'rowsSha256': 'a'*64}}}
            (root / 'before-fingerprint.json').write_text(json.dumps(expected))
            output = root / 'actual.json'
            changed = copy.deepcopy(expected)
            changed['tables']['review']['rowsSha256'] = 'b'*64
            output.write_text(json.dumps(changed))
            with self.assertRaisesRegex(ValueError, 'All-row or DDL parity'):
                bootstrap.fingerprint(root, root, output,
                    {'host': '127.0.0.1', 'username': 'test', 'password': 'synthetic'}, 1)


class ProcessAndCleanupTest(unittest.TestCase):
    def test_failed_read_keeps_partial_progress_and_cleans_sessions(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = {'id': 'first', 'method': 'GET', 'immutable': True, 'path': '/api/v1/test',
                'memberId': 7, 'account': {'memberId': 7, 'email': 'growth-test@example.test'},
                'expectedStatus': 200, 'expectedResponseSha256': hashlib.sha256(app.canonical({'data': 1})).hexdigest()}
            scenarios = root / 'read-scenarios.json'
            scenarios.write_text(json.dumps({'targets': [target, dict(target, id='second')]}))
            (root / 'consumer-manifest.json').write_text(json.dumps({'artifacts': {'reads': {
                'sha256': hashlib.sha256(scenarios.read_bytes()).hexdigest()}}}))
            response = io.BytesIO(b'{"data":1}')
            response.status = 200
            progress = root / 'progress.json'
            with patch.object(app, 'redis', return_value='OK') as redis, \
                    patch.object(app.urllib.request, 'urlopen', side_effect=[response, ConnectionResetError('private-message')]):
                with self.assertRaises(ConnectionResetError):
                    app.read_cases(root, progress_path=progress)
            observed = json.loads(progress.read_text())
            self.assertFalse(observed['passed'])
            self.assertEqual(observed['readCount'], 1)
            self.assertEqual(observed['failure'], {'readId': 'second', 'errorType': 'ConnectionResetError'})
            self.assertTrue(any(call.args[1] == 'UNLINK' for call in redis.call_args_list))
            self.assertTrue(any(call.args[1] == 'EVAL' for call in redis.call_args_list))
            self.assertNotIn('private-message', progress.read_text())
            self.assertNotIn('SESSION', progress.read_text())

    def test_oom_diagnostic_retains_only_closed_state(self):
        state = {'Running': False, 'ExitCode': 137, 'OOMKilled': True,
            'Error': 'secret-error', 'Env': ['DB_PASSWORD=secret'], 'Health': {'Log': 'secret-payload'}}
        with tempfile.TemporaryDirectory() as directory, \
                patch.dict(os.environ, {'AIRBOB_RUN_ID': 'lab-test'}):
            diagnostic = app.failure_diagnostic('test', False, lambda *args, **kwargs: json.dumps(state), {},
                'java.lang.OutOfMemoryError: secret-message', ConnectionResetError('secret-reset'), Path(directory) / 'absent')
        self.assertTrue(diagnostic['appState']['OOMKilled'])
        self.assertEqual(diagnostic['appState']['ExitCode'], 137)
        self.assertNotIn('secret', json.dumps(diagnostic))
        self.assertIn('JAVA_OPTS=-Xmx' + str(diagnostic['maxHeapMiB']) + 'm\n', app.common.app_environment({}))
        self.assertGreaterEqual(diagnostic['containerMemoryMiB'] - diagnostic['maxHeapMiB'], 512)

    def test_stage_checkpoint_cannot_masquerade_as_complete_qualification(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {
                'AIRBOB_RUN_ID': 'lab-test', 'AIRBOB_RDS_RESOURCE_ID': 'db-test',
                'AIRBOB_DATASET_MANIFEST_SHA256': 'a'*64, 'AIRBOB_EVIDENCE_BUCKET': 'test-bucket'}), \
                patch.object(bootstrap, 'publish_evidence') as publish:
            root = Path(directory)
            proof = {'tableCount': 32, 'totalRows': 24988237, 'importSeconds': 1, 'verificationSeconds': 2}
            bootstrap.publish_checkpoint(root, 'db-verified', proof)
            path = root / 'growth-v4-db-verified-checkpoint.json'
            checkpoint = json.loads(path.read_text())
            self.assertFalse(checkpoint['qualificationComplete'])
            self.assertEqual(checkpoint['kind'], 'growth-v4-preparation-checkpoint')
            self.assertEqual(checkpoint['proof'], proof)
            self.assertEqual(checkpoint['rdsResourceId'], 'db-test')
            self.assertFalse((root / 'dataset-qualification.json').exists())
            publish.assert_called_once_with(path, 'test-bucket', 'data-bootstrap/lab-test/' + path.name)

    def test_timeout_terminates_pipeline_descendant(self):
        with tempfile.TemporaryDirectory() as directory:
            marker = Path(directory) / 'still-running'
            child = "import time,pathlib; time.sleep(1); pathlib.Path(" + repr(str(marker)) + ").write_text('bad')"
            parent = "import subprocess,sys,time; subprocess.Popen([sys.executable,'-c'," + repr(child) + "]); time.sleep(30)"
            with self.assertRaises(subprocess.TimeoutExpired):
                bootstrap.execute([sys.executable, '-c', parent], timeout=0.3)
            time.sleep(1.1)
            self.assertFalse(marker.exists())

    def test_cleanup_attempts_all_and_preserves_primary_error(self):
        seen = []
        def failing():
            seen.append('first')
            raise OSError('synthetic cleanup failure')
        with self.assertRaisesRegex(ValueError, 'primary'):
            try:
                raise ValueError('primary')
            finally:
                app.cleanup_all([failing, lambda: seen.append('second')])
        self.assertEqual(seen, ['first', 'second'])
        with self.assertRaisesRegex(RuntimeError, 'cleanup failed'):
            app.cleanup_all([failing])


if __name__ == '__main__':
    unittest.main()
