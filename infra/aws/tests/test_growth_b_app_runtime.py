"""Offline closed runtime-binding tests; no Docker, AWS, or preserved-file writes."""
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('growth_b_app_runtime', ROOT / 'infra/aws/scripts/growth_b_app_runtime.py')
app = importlib.util.module_from_spec(spec)
spec.loader.exec_module(app)


def archive(path, entries, *, timestamp=(2026, 1, 1, 0, 0, 0), compression=zipfile.ZIP_STORED, modes=None):
    with warnings.catch_warnings():
        warnings.simplefilter('ignore', UserWarning)
        with zipfile.ZipFile(path, 'w') as out:
            for name, data in entries:
                info = zipfile.ZipInfo(name, timestamp)
                info.create_system = 3
                info.external_attr = ((modes or {}).get(name, stat.S_IFDIR | 0o755 if name.endswith('/') else stat.S_IFREG | 0o644)) << 16
                out.writestr(info, data, compress_type=compression)
    return path


def reviewed(record):
    entries = record['inventory']['entries']
    return {key: record[key] for key in ('sha256', 'bytes', 'inventorySha256')} | {
        'fileCount': sum(entry['kind'] == 'regular' for entry in entries),
        'directoryCount': sum(entry['kind'] == 'directory' for entry in entries)}


class RuntimeBindingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.entries = [('META-INF/', b''), ('META-INF/MANIFEST.MF', b'Main-Class: JarLauncher\nStart-Class: Airbob\n'),
                        ('org/boot/Loader.class', b'loader-bytecode'), ('BOOT-INF/classes/App.class', b'application-bytecode'),
                        ('BOOT-INF/lib/library.jar', b'whole-nested-library-bytes'), ('BOOT-INF/classpath.idx', b'library.jar\n'),
                        ('BOOT-INF/layers.idx', b'application\n')]
        self.docs = [(row['path'], b'') for row in app.DOC_DIRS] + [(app.DOC_FILE['path'], b'api-reference-only')]
        self.source = archive(self.root / 'source.jar', self.entries)
        self.actual = archive(self.root / 'actual.jar', self.entries + self.docs)
        self.source_record = app.jar_inventory(self.source)
        self.actual_record = app.jar_inventory(self.actual)
        doc = {**app.DOC_FILE, 'bytes': len(self.docs[-1][1]), 'sha256': app.sha(self.docs[-1][1])}
        runtime = {'schemaVersion': 1, 'preamble': self.source_record['inventory']['preamble'],
                   'files': [row for row in self.source_record['inventory']['entries'] if row['kind'] == 'regular']}
        # Test-only fixture pins exercise the same closed contract with tiny ZIPs.
        self.pins = patch.multiple(app, SOURCE=reviewed(self.source_record), ACTUAL=reviewed(self.actual_record),
                                   DOC_FILE=doc, RUNTIME_DIGEST=app.sha(app.canonical(runtime)))
        self.pins.start()
        self.addCleanup(self.pins.stop)
        with patch.object(app, 'validate_image_proof', return_value=app.platform_bindings()):
            self.binding = app.produce_runtime_binding(self.source, self.actual, self.root)
        self.binding_file = self.root / 'binding.json'
        self.write(self.binding)

    def write(self, value):
        self.binding_file.write_bytes(app.canonical(value))

    def validate(self, value=None, **expected):
        if value is not None:
            self.write(value)
        return app.validate_runtime_binding(self.binding_file, expected.get('image', app.IMAGE),
                                           expected.get('commit', app.MAIN_COMMIT), expected.get('sealed', app.SOURCE['sha256']))

    def test_producer_and_validator_bind_complete_inventories_and_running_jar(self):
        projection = self.validate()
        self.assertEqual(self.binding['runtime'], projection)
        self.assertEqual(projection['imageJarSha256'], app.ACTUAL['sha256'])
        self.assertEqual(self.binding['comparison']['commonFileCount'], len(self.entries) - 1)
        self.assertFalse(self.binding['comparison']['wholeJarEqual'])
        self.assertTrue(app.verify_running_jar(projection, app.ACTUAL['sha256']))
        with self.assertRaisesRegex(app.RuntimeBindingError, 'RUNNING_JAR_SHA_MISMATCH'):
            app.verify_running_jar(projection, app.SOURCE['sha256'])
        with self.assertRaisesRegex(app.RuntimeBindingError, 'VALIDATED_RUNTIME_PROJECTION_REQUIRED'):
            app.verify_running_jar({**projection, 'runtimeDigest': 'a' * 64}, app.ACTUAL['sha256'])

    def test_source_image_commit_and_sealed_identity_cannot_be_relabelled(self):
        for expected in ({'image': app.IMAGE.replace('airbob-repo', 'another')}, {'commit': '0' * 40}, {'sealed': '0' * 64}):
            with self.assertRaisesRegex(app.RuntimeBindingError, 'EXPECTED_RUNTIME_IDENTITY_OUTSIDE_REVIEW'):
                self.validate(**expected)
        for key, value in [('image', app.IMAGE + '-other'), ('mainCommit', 'f' * 40), ('runtimeContract', 'weakened-v2')]:
            candidate = copy.deepcopy(self.binding)
            candidate[key] = value
            with self.assertRaises(app.RuntimeBindingError):
                self.validate(candidate)

    def test_otherwise_exact_binding_with_forged_producer_tool_sha_is_rejected(self):
        self.assertEqual(self.binding['producerToolSha256'], app.sha(Path(app.__file__).read_bytes()))
        candidate = copy.deepcopy(self.binding)
        candidate['producerToolSha256'] = 'a' * 64
        with self.assertRaisesRegex(app.RuntimeBindingError, 'PRODUCER_TOOL_SHA_MISMATCH'):
            self.validate(candidate)

    def test_all_entry_bytes_cover_loader_manifest_class_library_and_indexes(self):
        for target in ('org/boot/Loader.class', 'META-INF/MANIFEST.MF', 'BOOT-INF/classes/App.class',
                       'BOOT-INF/lib/library.jar', 'BOOT-INF/classpath.idx', 'BOOT-INF/layers.idx'):
            changed = copy.deepcopy(self.actual_record['inventory'])
            next(row for row in changed['entries'] if row['path'] == target)['sha256'] = app.sha(b'changed')
            with self.assertRaisesRegex(app.RuntimeBindingError, 'RUNTIME_ENTRY_BYTES_CHANGED'):
                app.compare_inventories(self.source_record['inventory'], changed)

    def test_any_added_missing_or_modified_doc_entry_is_rejected(self):
        original = self.actual_record['inventory']
        cases = []
        extra = copy.deepcopy(original)
        extra['entries'].append({'path': 'new.class', 'kind': 'regular', 'bytes': 1, 'sha256': app.sha(b'x')})
        extra['entries'].sort(key=lambda row: row['path'])
        cases.append(extra)
        missing = copy.deepcopy(original)
        missing['entries'] = [row for row in missing['entries'] if row['path'] != 'org/boot/Loader.class']
        cases.append(missing)
        doc = copy.deepcopy(original)
        next(row for row in doc['entries'] if row['path'] == app.DOC_FILE['path'])['sha256'] = app.sha(b'other docs')
        cases.append(doc)
        directory = copy.deepcopy(original)
        next(row for row in directory['entries'] if row['path'] == app.DOC_DIRS[0]['path'])['bytes'] = 1
        cases.append(directory)
        for value in cases:
            with self.assertRaises(app.RuntimeBindingError):
                app.compare_inventories(self.source_record['inventory'], value)

    def test_inventory_and_runtime_digest_cannot_be_forged_together(self):
        candidate = copy.deepcopy(self.binding)
        for key in ('sourceJar', 'imageJar'):
            row = next(row for row in candidate[key]['inventory']['entries'] if row['path'] == 'org/boot/Loader.class')
            row['sha256'] = app.sha(b'same-forged-loader')
            candidate[key]['inventorySha256'] = app.sha(app.canonical(candidate[key]['inventory']))
        with self.assertRaisesRegex(app.RuntimeBindingError, 'FULL_ENTRY_INVENTORY_SHA_MISMATCH'):
            self.validate(candidate)
        candidate = copy.deepcopy(self.binding)
        candidate['runtimeDigest'] = 'f' * 64
        with self.assertRaisesRegex(app.RuntimeBindingError, 'RUNTIME_DIGEST_MISMATCH'):
            self.validate(candidate)
        candidate = copy.deepcopy(self.binding)
        candidate['runtime']['imageJarSha256'] = app.SOURCE['sha256']
        with self.assertRaisesRegex(app.RuntimeBindingError, 'RUNTIME_PROJECTION_MISMATCH'):
            self.validate(candidate)

    def test_duplicate_unsafe_symlink_inventory_and_unknown_private_fields_fail_closed(self):
        mutations = [lambda v: v.update(password='PRIVATE-MUST-NOT-BE-ACCEPTED'),
                     lambda v: v['sourceJar']['inventory']['entries'].append(copy.deepcopy(v['sourceJar']['inventory']['entries'][0])),
                     lambda v: v['sourceJar']['inventory']['entries'][0].update(path='../outside'),
                     lambda v: v['sourceJar']['inventory']['entries'][0].update(kind='symlink'),
                     lambda v: v['sourceJar']['inventory'].update(preamble={'bytes': 1, 'sha256': app.sha(b'x')}),
                     lambda v: v['allowedDocsDelta'].update(sourceAbsent=False),
                     lambda v: v['sourceJar'].update(bytes=True)]
        for mutation in mutations:
            value = copy.deepcopy(self.binding)
            mutation(value)
            with self.assertRaises(app.RuntimeBindingError) as raised:
                self.validate(value)
            self.assertNotIn('PRIVATE-', str(raised.exception))
        self.binding_file.write_bytes(b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaisesRegex(app.RuntimeBindingError, 'DUPLICATE_JSON_KEY'):
            self.validate()

    def test_platform_swap_or_config_and_layer_digest_change_is_rejected(self):
        for field in ('manifestDigest', 'configDigest', 'jarLayerDigest', 'revision'):
            candidate = copy.deepcopy(self.binding)
            candidate['platforms'][0][field] = 'changed'
            with self.assertRaisesRegex(app.RuntimeBindingError, 'PINNED_PLATFORM_BINDING_MISMATCH'):
                self.validate(candidate)
        candidate = copy.deepcopy(self.binding)
        candidate['platforms'].reverse()
        with self.assertRaisesRegex(app.RuntimeBindingError, 'PINNED_PLATFORM_BINDING_MISMATCH'):
            self.validate(candidate)

    def test_zip_timestamp_and_compression_do_not_change_entries_but_change_whole_pin(self):
        different_encoding = archive(self.root / 'different-encoding.jar', self.entries + self.docs,
                                     timestamp=(2025, 2, 2, 4, 6, 8), compression=zipfile.ZIP_DEFLATED)
        observed = app.jar_inventory(different_encoding)
        self.assertEqual(observed['inventorySha256'], self.actual_record['inventorySha256'])
        self.assertNotEqual(observed['sha256'], self.actual_record['sha256'])
        self.assertEqual(app.compare_inventories(self.source_record['inventory'], observed['inventory'])['runtimeDigest'], app.RUNTIME_DIGEST)
        with patch.object(app, 'validate_image_proof', return_value=app.platform_bindings()):
            with self.assertRaisesRegex(app.RuntimeBindingError, 'REVIEWED_WHOLE_JAR_IDENTITY_MISMATCH'):
                app.produce_runtime_binding(self.source, different_encoding, self.root)

    def test_archive_duplicate_traversal_absolute_nul_symlink_and_corruption_are_rejected(self):
        cases = [('duplicate.jar', self.entries + [self.entries[0]], None),
                 ('traversal.jar', [('../outside.class', b'bad')], None),
                 ('absolute.jar', [('/etc/file', b'bad')], None),
                 ('drive.jar', [('C:/outside', b'bad')], None),
                 ('symlink.jar', [('inside.class', b'outside')], {'inside.class': stat.S_IFLNK | 0o777})]
        for name, rows, modes in cases:
            with self.assertRaises(app.RuntimeBindingError):
                app.jar_inventory(archive(self.root / name, rows, modes=modes))
        nul = archive(self.root / 'nul.jar', [('abc.class', b'bad')])
        nul.write_bytes(nul.read_bytes().replace(b'abc.class', b'ab\0.class'))
        with self.assertRaises(app.RuntimeBindingError):
            app.jar_inventory(nul)
        for name, raw in [('preamble.jar', b'#!/bin/sh\n' + self.source.read_bytes()),
                          ('trailing.jar', self.source.read_bytes() + b'not-a-zip-comment'),
                          ('corrupt-crc.jar', self.source.read_bytes().replace(b'loader-bytecode', b'loader-evilcode')),
                          ('truncated.jar', self.source.read_bytes()[:-20]), ('invalid.jar', b'not a ZIP archive')]:
            path = self.root / name
            path.write_bytes(raw)
            with self.assertRaises(app.RuntimeBindingError):
                app.jar_inventory(path)

    def test_source_file_symlink_and_expanded_size_limit_are_rejected(self):
        link = self.root / 'linked.jar'
        link.symlink_to(self.source)
        with self.assertRaisesRegex(app.RuntimeBindingError, 'REGULAR_BOUNDED_INPUT_REQUIRED'):
            app.jar_inventory(link)
        with patch.object(app, 'MAX_EXPANDED', 4):
            with self.assertRaisesRegex(app.RuntimeBindingError, 'JAR_EXPANDED_SIZE_LIMIT'):
                app.jar_inventory(self.source)

    def test_cli_output_is_create_only_and_never_emits_archive_or_exception_content(self):
        output = self.root / 'new-binding.json'
        args = ['produce', '--source-jar', str(self.source), '--image-jar', str(self.actual),
                '--image-proof-directory', str(self.root), '--output', str(output)]
        with patch.object(app, 'validate_image_proof', return_value=app.platform_bindings()), contextlib.redirect_stdout(io.StringIO()) as stdout:
            self.assertEqual(app.main(args), 0)
            original = output.read_bytes()
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(app.main(args), 1)
        self.assertEqual(output.read_bytes(), original)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        self.assertNotIn('application-bytecode', stdout.getvalue() + stderr.getvalue())
        self.assertEqual(json.loads(stderr.getvalue())['failureCode'], 'APP_RUNTIME_BINDING_FAILED')


class ImageProofTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        platforms, descriptors = [], []
        for architecture in ('amd64', 'arm64'):
            config = {'architecture': architecture, 'os': 'linux', 'config': {
                'Labels': {'org.opencontainers.image.revision': app.MAIN_COMMIT},
                'Entrypoint': ['sh', '-c', 'java $JAVA_OPTS -jar /app/app.jar']},
                'history': [{'created_by': 'COPY build/libs/airbob.jar app.jar # buildkit'}],
                'rootfs': {'diff_ids': ['sha256:' + '1' * 64]}}
            config_raw = app.canonical(config)
            (self.root / (architecture + '-config.json')).write_bytes(config_raw)
            config_digest = 'sha256:' + app.sha(config_raw)
            manifest = {'schemaVersion': 2, 'config': {'digest': config_digest, 'size': len(config_raw)},
                        'layers': [{'digest': app.APP_LAYER['digest'], 'size': app.APP_LAYER['bytes']}]}
            raw = app.canonical(manifest)
            (self.root / (architecture + '-manifest.json')).write_bytes(raw)
            manifest_digest = 'sha256:' + app.sha(raw)
            platforms.append({'platform': 'linux/' + architecture, 'manifestDigest': manifest_digest,
                              'manifestBytes': len(raw), 'configDigest': config_digest})
            descriptors.append({'platform': {'architecture': architecture, 'os': 'linux'}, 'digest': manifest_digest, 'size': len(raw)})
        index = {'schemaVersion': 2, 'mediaType': 'application/vnd.oci.image.index.v1+json', 'manifests': descriptors}
        index_raw = app.canonical(index)
        (self.root / 'index-manifest.json').write_bytes(index_raw)
        self.pins = patch.multiple(app, PLATFORMS=platforms, INDEX_DIGEST='sha256:' + app.sha(index_raw))
        self.pins.start()
        self.addCleanup(self.pins.stop)

    def test_both_platform_manifest_and_config_bytes_are_proven(self):
        self.assertEqual(app.validate_image_proof(self.root), app.platform_bindings())

    def test_any_raw_index_manifest_or_config_byte_change_is_rejected(self):
        for name in ('index-manifest.json', 'amd64-manifest.json', 'arm64-manifest.json', 'amd64-config.json', 'arm64-config.json'):
            path = self.root / name
            raw = path.read_bytes()
            path.write_bytes(raw + b' ')
            with self.assertRaises(app.RuntimeBindingError):
                app.validate_image_proof(self.root)
            path.write_bytes(raw)


if __name__ == '__main__':
    unittest.main()
