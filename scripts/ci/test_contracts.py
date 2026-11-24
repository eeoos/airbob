import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import contracts

MIGRATION = contracts.MIGRATION_DIR


def sha256(text):
    return hashlib.sha256(text.encode()).hexdigest()


class FakeFiles:
    def __init__(self, contents, modes):
        self.contents, self._modes = contents, modes

    def modes(self):
        return self._modes

    def read(self, path):
        value = self.contents.get(path)
        return None if value is None else value.encode()


def manifest(*entries):
    return json.dumps({'files': [dict(path=path, restored_sha256=sha256(text), mode='100644')
                                 for path, text in entries]})


class TempRepo:
    def __init__(self, directory):
        self.root = Path(directory)
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI test')
        self.git('config', 'user.email', 'ci@example.invalid')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE).decode().strip()

    def write(self, path, text):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def commit(self, message):
        self.git('add', '-A')
        self.git('commit', '-qm', message)
        return self.git('rev-parse', 'HEAD')


class ArchiveTests(unittest.TestCase):
    def test_matching_archive_has_no_problems(self):
        files = FakeFiles({contracts.ARCHIVE_MANIFEST: manifest(('load-test/a.js', 'a')), 'load-test/a.js': 'a',
                           'load-test/README.md': 'guide'},
                          {'load-test/a.js': '100644', contracts.ARCHIVE_MANIFEST: '100644', 'load-test/README.md': '100644'})
        self.assertEqual(contracts.archive_mismatches(files), [])

    def test_edited_missing_mode_changed_and_unrecorded_files_are_reported(self):
        files = FakeFiles({contracts.ARCHIVE_MANIFEST: manifest(('load-test/edited.js', 'old'), ('load-test/gone.js', 'x'),
                                                                ('load-test/script.sh', 'run')),
                           'load-test/edited.js': 'new', 'load-test/script.sh': 'run', 'load-test/extra.js': 'extra'},
                          {'load-test/edited.js': '100644', 'load-test/script.sh': '100755', 'load-test/extra.js': '100644',
                           contracts.ARCHIVE_MANIFEST: '100644'})
        problems = contracts.archive_mismatches(files)
        self.assertEqual(problems, [
            'load-test/edited.js: content differs from the recorded restored_sha256',
            'load-test/gone.js: missing',
            'load-test/script.sh: mode 100755 differs from the recorded 100644',
            f'load-test/extra.js: tracked but not recorded in {contracts.ARCHIVE_MANIFEST}',
        ])

    def test_missing_manifest_is_a_problem(self):
        self.assertEqual(contracts.archive_mismatches(FakeFiles({}, {})), [f'{contracts.ARCHIVE_MANIFEST}: missing'])

    def test_worktree_and_revision_readers_see_the_same_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = TempRepo(directory)
            repo.write(contracts.ARCHIVE_MANIFEST, manifest(('load-test/k6/a.js', 'original')))
            repo.write('load-test/k6/a.js', 'original')
            recorded = repo.commit('archive')
            self.assertEqual(contracts.archive_mismatches(contracts.WorktreeFiles(repo.root)), [])
            self.assertEqual(contracts.archive_mismatches(contracts.RevisionFiles(recorded, repo.root)), [])
            repo.write('load-test/k6/a.js', 'edited for the new layout')
            edited = repo.commit('edit an archived script')
            expected = ['load-test/k6/a.js: content differs from the recorded restored_sha256']
            self.assertEqual(contracts.archive_mismatches(contracts.WorktreeFiles(repo.root)), expected)
            self.assertEqual(contracts.archive_mismatches(contracts.RevisionFiles(edited, repo.root)), expected)
            self.assertEqual(contracts.archive_mismatches(contracts.RevisionFiles(recorded, repo.root)), [])


class MigrationTests(unittest.TestCase):
    def changes(self, mutate):
        with tempfile.TemporaryDirectory() as directory:
            repo = TempRepo(directory)
            repo.write(MIGRATION + 'V1__init.sql', 'create table a (id bigint);')
            base = repo.commit('base')
            mutate(repo)
            head = repo.commit('change')
            return contracts.name_status(f'{base}...{head}', repo.git)

    def test_new_migrations_and_other_files_are_allowed(self):
        def add(repo):
            repo.write(MIGRATION + 'V2__add_b.sql', 'create table b (id bigint);')
            repo.write('docs/notes.md', 'notes')
        entries = self.changes(add)
        self.assertEqual(sorted(entries), [('A', MIGRATION + 'V2__add_b.sql'), ('A', 'docs/notes.md')])
        self.assertEqual(contracts.applied_migration_violations(entries), [])

    def test_editing_deleting_or_renaming_an_applied_migration_is_rejected(self):
        cases = {
            'edit': (lambda repo: repo.write(MIGRATION + 'V1__init.sql', 'create table a (id bigint, name text);'),
                     [MIGRATION + 'V1__init.sql: applied migrations are roll-forward only (M)']),
            'delete': (lambda repo: (repo.root / MIGRATION / 'V1__init.sql').unlink(),
                       [MIGRATION + 'V1__init.sql: applied migrations are roll-forward only (D)']),
            'rename': (lambda repo: (repo.root / MIGRATION / 'V1__init.sql').rename(repo.root / MIGRATION / 'V1__first.sql'),
                       [MIGRATION + 'V1__init.sql: applied migrations are roll-forward only (D)']),
        }
        for name, (mutate, expected) in cases.items():
            with self.subTest(name=name):
                self.assertEqual(contracts.applied_migration_violations(self.changes(mutate)), expected)

    def test_unexpected_diff_output_fails_loudly(self):
        with self.assertRaises(ValueError):
            contracts.name_status('a...b', lambda *args: 'M\0only-a-status-without-a-path\0A\0')


if __name__ == '__main__':
    unittest.main()
