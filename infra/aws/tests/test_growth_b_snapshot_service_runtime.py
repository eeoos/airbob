"""Snapshot service runtime handoff, without a database, network or container."""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_b_aws_restore as restore
import growth_b_runtime as runtime
import growth_b_service as service


class SnapshotServiceRuntime(unittest.TestCase):
    def setUp(self):
        previous = os.umask(0o077); self.addCleanup(os.umask, previous)
        temporary = tempfile.TemporaryDirectory(); self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.release = self.root / 'release'; self.release.mkdir(mode=0o700)
        self.output = self.root / 'service-result'; self.output.mkdir(mode=0o700)
        self.files = {'tools/growth_timezones.py': b'# sealed temporal tool fixture\n',
                      'runtime/lib/verifier.jar': b'bounded verifier fixture'}
        self.archive()
        self.write('etl-binaries.json', {'verifier.jar': self.digest(self.files['runtime/lib/verifier.jar'])})
        self.write('tool-sources.json', {'growth_timezones.py': self.digest(self.files['tools/growth_timezones.py'])})
        self.write('source-provenance.json', {'coordinateTimeZones': {'resolvedZoneIds': ['Asia/Seoul']}})
        self.write('timezone-qualification.json', {})
        self.envelope = {'objects': {p.name: {'sha256': service.sha(p)} for p in self.release.iterdir()}}
        self.write('SHA256SUMS.json', {key: row['sha256'] for key, row in self.envelope['objects'].items()})

    def digest(self, raw):
        return hashlib.sha256(raw).hexdigest()

    def write(self, name, value):
        (self.release / name).write_text(json.dumps(value))

    def archive(self, *, linked=False):
        path = self.release / 'preparation-tools.tar.gz'
        with tarfile.open(path, 'w:gz') as archive:
            for name, raw in self.files.items():
                member = tarfile.TarInfo(name); member.size = len(raw); member.mode = 0o600
                if linked:
                    member.type = tarfile.SYMTYPE; member.linkname = '/tmp/unowned'; member.size = 0
                archive.addfile(member, None if linked else io.BytesIO(raw))
        self.write('consumer-manifest.json', {'artifacts': {'tools': {'sha256': service.sha(path)}}})

    def qualify(self, mode='snapshot', callback=None):
        def observed(release, directory, path, *, expected_checks, java_home):
            self.assertEqual(self.release, release)
            self.assertEqual(self.root / 'bootstrap-runtime', directory)
            self.assertEqual(self.output / 'host-runtime.json', path)
            self.assertEqual(self.root / 'toolchain/jdk', java_home)
            self.assertEqual({k: v['sha256'] for k, v in self.envelope['objects'].items()}, expected_checks)
            self.assertEqual(self.files, {str(p.relative_to(directory)): p.read_bytes()
                                         for p in directory.rglob('*') if p.is_file()})
            return {'consumerRuntimePassed': True}
        with patch.object(runtime, 'qualify_runtime', side_effect=callback or observed) as current, \
             patch.object(restore, 'command') as command, patch.object(restore, 'connection') as connection, \
             patch.object(restore, 'prepare_service') as prepare, patch.object(restore, 'Aws') as aws:
            result = service.qualify_service_runtime(self.root, self.release, self.output, self.envelope, mode)
            current.assert_called_once(); command.assert_not_called(); connection.assert_not_called()
            prepare.assert_not_called(); aws.assert_not_called()
        return result

    def test_new_snapshot_target_extracts_sealed_runtime_and_qualifies_before_use(self):
        directory, proof = self.qualify()
        self.assertEqual(0o700, directory.stat().st_mode & 0o777)
        self.assertTrue(proof['consumerRuntimePassed'])
        self.assertEqual({'release', 'service-result', 'bootstrap-runtime'}, {p.name for p in self.root.iterdir()})
        self.assertTrue(all(p.stat().st_mode & 0o077 == 0 for p in directory.rglob('*')))

    def test_existing_snapshot_runtime_is_preserved_and_currently_requalified(self):
        directory, _ = self.qualify(); before = {p: p.read_bytes() for p in directory.rglob('*') if p.is_file()}
        with patch.object(restore, 'extract_runtime') as extract:
            self.qualify()
            extract.assert_not_called()
        self.assertEqual(before, {p: p.read_bytes() for p in before})

    def test_existing_dump_runtime_keeps_original_qualification_path(self):
        restore.extract_runtime(self.release, self.root / 'bootstrap-runtime')
        with patch.object(restore, 'extract_runtime') as extract:
            self.qualify('dump')
            extract.assert_not_called()

    def test_missing_dump_runtime_is_not_rebuilt(self):
        with patch.object(restore, 'extract_runtime') as extract:
            with self.assertRaises((ValueError, RuntimeError, FileNotFoundError)):
                service.qualify_service_runtime(self.root, self.release, self.output, self.envelope, 'dump')
            extract.assert_not_called()
        self.assertFalse((self.root / 'bootstrap-runtime').exists())

    def test_changed_envelope_or_unsafe_archive_never_creates_runtime(self):
        self.envelope['objects']['preparation-tools.tar.gz']['sha256'] = '0' * 64
        with patch.object(runtime, 'qualify_runtime') as qualify:
            with self.assertRaisesRegex(ValueError, 'sealed envelope'): self.qualify()
            qualify.assert_not_called()
        self.archive(linked=True)
        self.envelope['objects']['preparation-tools.tar.gz']['sha256'] = service.sha(self.release / 'preparation-tools.tar.gz')
        with self.assertRaisesRegex(ValueError, 'Unsafe runtime archive'): self.qualify()
        self.assertFalse((self.root / 'bootstrap-runtime').exists())

    def test_existing_or_dangling_symlink_is_rejected_without_following(self):
        directory = self.root / 'bootstrap-runtime'
        for existing in (False, True):
            with self.subTest(existing=existing):
                target = self.root / ('unowned-' + str(existing))
                if existing: target.mkdir()
                directory.symlink_to(target, target_is_directory=True)
                with self.assertRaisesRegex(ValueError, 'canonical retained path'): self.qualify()
                directory.unlink()
                self.assertEqual([], list(target.iterdir()) if existing else [])

    def test_failed_current_qualification_retains_existing_bytes_without_reextraction(self):
        self.qualify(); directory = self.root / 'bootstrap-runtime'
        tool = directory / 'tools/growth_timezones.py'; tool.write_bytes(b'CHANGED_EXISTING_TOOL')
        with patch.object(restore, 'extract_runtime') as extract:
            with self.assertRaises((RuntimeError, ValueError)):
                service.qualify_service_runtime(self.root, self.release, self.output, self.envelope, 'snapshot')
            extract.assert_not_called()
        self.assertEqual(b'CHANGED_EXISTING_TOOL', tool.read_bytes())

    def test_competing_creation_is_not_overwritten(self):
        original = restore.extract_runtime
        def competing(release, destination):
            destination.mkdir(mode=0o700); (destination / 'unowned').write_bytes(b'keep')
            return original(release, destination)
        with patch.object(restore, 'extract_runtime', side_effect=competing):
            with self.assertRaisesRegex(ValueError, 'already exists'): self.qualify()
        self.assertEqual(b'keep', (self.root / 'bootstrap-runtime/unowned').read_bytes())


if __name__ == '__main__':
    unittest.main()
