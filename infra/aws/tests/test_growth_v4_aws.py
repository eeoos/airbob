"""V28 restore admission, source fidelity, deadlines, and cleanup boundaries."""
import copy
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
