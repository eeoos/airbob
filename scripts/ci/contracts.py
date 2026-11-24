"""Contracts CI enforces on every change.

- Archived load-test files keep the bytes and modes recorded in load-test/archive-source.json.
- Applied Flyway migrations (a V file that exists on the base side) are never edited or deleted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

import scope

ARCHIVE_DIR = 'load-test/'
ARCHIVE_MANIFEST = 'load-test/archive-source.json'
# Tracked archive files that are not themselves archived originals.
ARCHIVE_UNLISTED = frozenset({'load-test/README.md', ARCHIVE_MANIFEST, 'load-test/k6/traffic/README.md'})
MIGRATION_DIR = 'core/core-api/src/main/resources/db/migration/'


def run_git(args, cwd='.'):
    return subprocess.check_output(['git', '-C', str(cwd), *args], stderr=subprocess.PIPE, timeout=60)


class WorktreeFiles:
    """Archive files as checked out (CI checks the tested commit's working tree)."""

    def __init__(self, root='.', git=run_git):
        self.root, self.git = Path(root), git

    def modes(self):
        listing = self.git(['ls-files', '-s', '-z', '--', ARCHIVE_DIR], self.root).decode()
        modes = {}
        for entry in filter(None, listing.split('\0')):
            info, path = entry.split('\t', 1)
            modes[path] = info.split(' ')[0]
        return modes

    def read(self, path):
        file = self.root / path
        return file.read_bytes() if file.is_file() else None


class RevisionFiles:
    """Archive files of a commit without checking it out (local checks of past changes)."""

    def __init__(self, revision, cwd='.', git=run_git):
        self.revision, self.cwd, self.git = revision, cwd, git
        listing = self.git(['ls-tree', '-r', '-z', revision, '--', ARCHIVE_DIR], cwd).decode()
        self.entries = {}
        for entry in filter(None, listing.split('\0')):
            info, path = entry.split('\t', 1)
            mode, _, blob = info.split(' ')
            self.entries[path] = (mode, blob)

    def modes(self):
        return {path: mode for path, (mode, _) in self.entries.items()}

    def read(self, path):
        if path not in self.entries:
            return None
        return self.git(['cat-file', 'blob', self.entries[path][1]], self.cwd)


def archive_mismatches(files):
    """Problems with the archived load-test files; empty when they match the manifest."""
    raw = files.read(ARCHIVE_MANIFEST)
    if raw is None:
        return [f'{ARCHIVE_MANIFEST}: missing']
    manifest = json.loads(raw)
    modes = files.modes()
    problems, listed = [], set()
    for entry in manifest['files']:
        path = entry['path']
        listed.add(path)
        content = files.read(path)
        if content is None:
            problems.append(f'{path}: missing')
            continue
        if hashlib.sha256(content).hexdigest() != entry['restored_sha256']:
            problems.append(f'{path}: content differs from the recorded restored_sha256')
        if modes.get(path) != entry['mode']:
            problems.append(f'{path}: mode {modes.get(path)} differs from the recorded {entry["mode"]}')
    for path in sorted(set(modes) - listed - ARCHIVE_UNLISTED):
        problems.append(f'{path}: tracked but not recorded in {ARCHIVE_MANIFEST}')
    return problems


def name_status(comparison, read_git=scope.git):
    """(status, path) pairs of `git diff --name-status --no-renames` for a verified range."""
    tokens = [t for t in read_git('diff', '--name-status', '--no-renames', '-z', comparison, '--').split('\0') if t]
    if len(tokens) % 2:
        raise ValueError('unexpected git diff --name-status output')
    return list(zip(tokens[0::2], tokens[1::2]))


def applied_migration_violations(entries):
    """An edit or deletion of a migration that existed on the base side; renames show as D + A."""
    return [f'{path}: applied migrations are roll-forward only ({status})'
            for status, path in entries
            if path.startswith(MIGRATION_DIR) and status[0] in 'MDT']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', help='check a local range BASE...HEAD instead of the CI event')
    parser.add_argument('--head', help='commit to check with --base (default: the working tree and HEAD)')
    args = parser.parse_args()

    problems = []
    if args.base:
        head = args.head or 'HEAD'
        files = RevisionFiles(head) if args.head else WorktreeFiles()
        problems += archive_mismatches(files)
        problems += applied_migration_violations(name_status(f'{args.base}...{head}'))
    else:
        problems += archive_mismatches(WorktreeFiles())
        name, sha = os.environ['GITHUB_EVENT_NAME'], os.environ['GITHUB_SHA']
        if name in ('pull_request', 'push'):
            event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
            problems += applied_migration_violations(name_status(scope.comparison_range(name, event, sha)))
        else:
            print(f'No base commit for {name}; only the archive is checked.')
    if problems:
        print('Contract violations:')
        for problem in problems:
            print(f'- {problem}')
        raise SystemExit(1)
    print('Archived load-test files and applied migrations are unchanged.')


if __name__ == '__main__':
    main()
