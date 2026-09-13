"""Offline regular-file assembly tests; no upstream downloads or containers."""
import importlib.util
import io
from pathlib import Path
import tarfile
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('growth_b_toolchain_builder', Path(__file__).resolve().parents[1] / 'scripts/build-growth-b-toolchain.py')
builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)


class ToolchainBuilderTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name); self.source = self.root / 'source'; self.source.mkdir()
    def test_file_and_directory_links_become_regular_copies_without_changing_sources(self):
        (self.source / 'bin').mkdir(); (self.source / 'bin/python3.12').write_bytes(b'elf-fixture')
        (self.source / 'bin/python3.12').chmod(0o4755)
        (self.source / 'bin/python3').symlink_to('python3.12')
        (self.source / 'lib').mkdir(); (self.source / 'lib/module.py').write_bytes(b'value=1')
        (self.source / 'lib64').symlink_to('lib', target_is_directory=True)
        output = self.root / 'output'; links = []
        builder.materialize(self.source, output, self.source, links=links)
        self.assertTrue((self.source / 'bin/python3').is_symlink())
        self.assertEqual((output / 'bin/python3').read_bytes(), b'elf-fixture')
        self.assertEqual((output / 'lib64/module.py').read_bytes(), b'value=1')
        self.assertFalse(any(path.is_symlink() for path in output.rglob('*')))
        self.assertEqual((output / 'bin/python3').stat().st_mode & 0o7777, 0o755)
        self.assertEqual(len(links), 2)
    def test_escaping_broken_and_cyclic_links_cannot_be_materialized(self):
        external = self.root / 'external'; external.write_bytes(b'foreign')
        (self.source / 'escape').symlink_to('../external')
        with self.assertRaisesRegex(ValueError, 'escapes'): builder.materialize(self.source, self.root / 'escape-output', self.source)
        (self.source / 'escape').unlink(); (self.source / 'broken').symlink_to('absent')
        with self.assertRaisesRegex(ValueError, 'broken'): builder.materialize(self.source, self.root / 'broken-output', self.source)
        (self.source / 'broken').unlink(); (self.source / 'cycle').symlink_to('.', target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'cycle'): builder.materialize(self.source, self.root / 'cycle-output', self.source)
    def test_output_manifest_exact_bytes_and_deterministic_link_free_archive(self):
        (self.source / 'bin').mkdir(); (self.source / 'bin/tool').write_bytes(b'fixed bytes'); (self.source / 'bin/tool').chmod(0o755)
        manifest, total = builder.inventory(self.source)
        self.assertEqual(total, 11); self.assertEqual(manifest['files']['bin/tool'], builder.binding(self.source / 'bin/tool'))
        first, second = self.root / 'first.tar.gz', self.root / 'second.tar.gz'
        builder.pack(self.source, first); builder.pack(self.source, second)
        self.assertEqual(builder.sha(first), builder.sha(second))
        with tarfile.open(first) as archive:
            self.assertTrue(all(item.isfile() or item.isdir() for item in archive.getmembers()))
            archive.extractall(self.root / 'restored', filter='data')
        self.assertEqual(builder.inventory(self.root / 'restored'), (manifest, total))
    def test_final_names_symlinks_and_expanded_bound_are_checked(self):
        for name in ('../escape', '/absolute', 'dir/./file', 'dir//file', 'with space', 'dir/..'):
            with self.subTest(name=name): self.assertFalse(builder.safe_name(name))
        (self.source / 'file').write_bytes(b'three')
        with self.assertRaisesRegex(ValueError, 'size bound'): builder.inventory(self.source, maximum_bytes=4)
        (self.source / 'linked').symlink_to('file')
        with self.assertRaisesRegex(ValueError, 'link'): builder.inventory(self.source)
    def archive(self, name, entries):
        path = self.root / name
        with tarfile.open(path, 'w') as archive:
            for item, data in entries: archive.addfile(item, io.BytesIO(data) if data is not None else None)
        return path
    def test_source_duplicate_traversal_and_escaping_links_are_rejected(self):
        file = tarfile.TarInfo('file'); file.size = 1
        traversal = tarfile.TarInfo('../escape'); traversal.size = 1
        link = tarfile.TarInfo('escape'); link.type = tarfile.SYMTYPE; link.linkname = '../outside'
        for number, entries in enumerate(([(file, b'x'), (file, b'x')], [(traversal, b'x')], [(link, None)])):
            archive = self.archive(str(number) + '.tar', entries)
            with self.subTest(number=number), self.assertRaises((ValueError, tarfile.FilterError)):
                builder.extract_source(archive, self.root / ('extract-' + str(number)))
        self.assertFalse((self.root / 'escape').exists())
    def test_repeated_empty_vendor_directory_header_is_flattened_without_repeating_files(self):
        directory = tarfile.TarInfo('package'); directory.type = tarfile.DIRTYPE
        file = tarfile.TarInfo('package/client'); file.size = 1
        archive = self.archive('vendor.tar', [(directory, None), (file, b'x'), (directory, None)])
        target = self.root / 'vendor'
        self.assertEqual(builder.extract_source(archive, target), ['package'])
        self.assertEqual((target / 'package/client').read_bytes(), b'x')


if __name__ == '__main__': unittest.main()
