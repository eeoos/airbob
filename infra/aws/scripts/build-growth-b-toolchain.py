#!/usr/bin/env python3
"""Assemble the reviewed B Linux toolchain without links or upstream-file edits.

Official source downloads and detached-signature receipts are retained separately.
AWS CLI keeps its original, separately pinned bootstrap ZIP installation; it is
not rewritten to evade this archive's deliberately restricted member names.
"""
import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import stat
import tarfile
import tempfile

GIB = 1024**3
MAX_FILES = 50000
SOURCES = {
    'python': {'name': 'cpython-3.12.14+20260901-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz',
        'sha256': '72748da13197c1fb161e3afeef20a6a385ff24f2165e6e2758e47008e7faba4c', 'bytes': 34143368, 'root': 'python'},
    'jdk': {'name': 'OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz',
        'sha256': 'ce79869e1307ed8ee1e2baa86a412b1eb5b75d10a01006d788a6f968bcfaee94', 'bytes': 207473347, 'root': 'jdk-21.0.12.1+1'},
    'mysql': {'name': 'mysql-8.4.11-linux-glibc2.28-x86_64-minimal.tar.xz',
        'sha256': '383f54e124d5f325d67f0c6912a8f96814eedc761a17ea30112e52fa4cc6b143', 'bytes': 79777440,
        'root': 'mysql-8.4.11-linux-glibc2.28-x86_64-minimal'},
}
RUNTIME = {'system': 'Linux', 'architecture': 'x86_64', 'pythonVersion': '3.12.14',
           'javaVersion': '21.0.12.1', 'mysqlVersion': '8.4.11', 'awsCliVersion': '2.34.64'}
REQUIRED = {'python/bin/python3', 'jdk/bin/java', 'jdk/bin/javac', 'jdk/bin/keytool', 'mysql/bin/mysql'}


def require(condition, message):
    if not condition: raise ValueError(message)


def sha(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'Regular source file required')
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def binding(path): return {'sha256': sha(path), 'bytes': Path(path).stat().st_size}


def write(path, value):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'w') as stream:
        json.dump(value, stream, sort_keys=True, indent=2); stream.write('\n')


def safe_name(name):
    return (isinstance(name, str) and re.fullmatch(r'[A-Za-z0-9_./+-]+', name)
            and not name.startswith('/') and all(part not in {'.', '..', ''} for part in name.rstrip('/').split('/')))


def extract_source(archive, destination):
    """Source links are allowed only for later in-tree materialization."""
    destination = Path(destination); destination.mkdir(mode=0o700)
    with tarfile.open(archive) as source:
        members = source.getmembers(); observed = {}; duplicate_directories = []
        require(0 < len(members) <= MAX_FILES
                and sum(item.size for item in members) <= 4 * GIB, 'Source archive exceeds its inventory/size bound')
        for item in members:
            require(safe_name(item.name) and (item.isfile() or item.isdir() or item.issym() or item.islnk()), 'Unsafe source archive member')
            name = item.name.rstrip('/')
            if name in observed:
                require(item.isdir() and observed[name].isdir() and item.size == observed[name].size == 0,
                        'Source archive repeats a non-directory member')
                duplicate_directories.append(name)
            observed[name] = item
        # Python's data filter prevents escaping links and extraction through an
        # already-created link. materialize() separately rejects cycles/escapes.
        source.extractall(destination, filter='data')
        return duplicate_directories


def materialize(source, target, source_root, *, ancestors=(), links=None):
    original_root = Path(source_root).absolute()
    relative = Path(source).absolute().relative_to(original_root)
    source_root = original_root.resolve()
    source, target = source_root / relative, Path(target)
    try: resolved = source.resolve(strict=True)
    except (OSError, RuntimeError): raise ValueError('Source link is broken or cyclic') from None
    require(resolved.is_relative_to(source_root), 'Source link escapes its package root')
    mode = resolved.stat().st_mode
    if source.is_symlink() and links is not None:
        links.append({'path': source.relative_to(source_root).as_posix(), 'target': resolved.relative_to(source_root).as_posix()})
    if stat.S_ISDIR(mode):
        require(resolved not in ancestors, 'Source directory links contain a cycle')
        target.mkdir(mode=0o755)
        for child in sorted(source.iterdir()):
            materialize(child, target / child.name, source_root, ancestors=(*ancestors, resolved), links=links)
    else:
        require(stat.S_ISREG(mode), 'Only source regular files/directories can enter the toolchain')
        target.parent.mkdir(parents=True, exist_ok=True, mode=0o755)
        with resolved.open('rb') as origin, target.open('xb') as destination: shutil.copyfileobj(origin, destination, 1024**2)
        target.chmod(0o755 if mode & 0o111 else 0o644)


def inventory(root, *, maximum_bytes=4 * GIB):
    root = Path(root); files = {}; total = 0
    for path in sorted(root.rglob('*')):
        require(not path.is_symlink() and (path.is_dir() or path.is_file()), 'Output contains a link or special file')
        name = path.relative_to(root).as_posix()
        require(safe_name(name), 'Output member violates the preparation contract')
        if path.is_file():
            metadata = binding(path); total += metadata['bytes']; files[name] = metadata
        require(len(files) <= MAX_FILES and total <= maximum_bytes, 'Toolchain exceeds the preparation file/size bound')
    return {'schemaVersion': 1, 'files': files}, total


def pack(root, destination):
    """Deterministic regular-file tar; no symlink/hardlink headers are emitted."""
    root = Path(root)
    with Path(destination).open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w', format=tarfile.GNU_FORMAT) as archive:
            for path in sorted(root.rglob('*')):
                require(not path.is_symlink() and (path.is_file() or path.is_dir()), 'Unreviewed link or special file at archive time')
                member = tarfile.TarInfo(path.relative_to(root).as_posix())
                require(safe_name(member.name), 'Invalid final archive member')
                member.uid = member.gid = member.mtime = 0
                member.mode = 0o755 if path.is_dir() or path.stat().st_mode & 0o111 else 0o644
                if path.is_dir(): member.type = tarfile.DIRTYPE; archive.addfile(member)
                else:
                    member.size = path.stat().st_size
                    with path.open('rb') as stream: archive.addfile(member, stream)


def build(sources, output):
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'Assemble on a case-sensitive Linux x86_64 filesystem')
    sources, output = Path(sources).resolve(), Path(output).absolute()
    require(not output.resolve().is_relative_to(sources), 'Output must remain outside the original source directory')
    for item in SOURCES.values(): require(binding(sources / item['name']) == {key: item[key] for key in ('sha256', 'bytes')}, 'Official source bytes differ')
    output.mkdir(mode=0o700)
    tree = output / 'toolchain'; tree.mkdir(mode=0o755)
    links = {}; selected = {}; duplicate_directories = {}
    with tempfile.TemporaryDirectory(prefix='toolchain-extraction-', dir=output) as scratch:
        for kind, item in SOURCES.items():
            extracted = Path(scratch) / kind
            duplicate_directories[kind] = extract_source(sources / item['name'], extracted)
            root = extracted / item['root']; require(root.is_dir() and not root.is_symlink(), 'Source package root differs')
            links[kind] = []
            if kind == 'mysql':
                (tree / kind).mkdir(mode=0o755)
                selected[kind] = ['bin/mysql', 'lib', 'share', 'LICENSE', 'README']
                for name in selected[kind]: materialize(root / name, tree / kind / name, root, links=links[kind])
            else:
                selected[kind] = ['complete distribution']
                materialize(root, tree / kind, root, links=links[kind])
    manifest, total = inventory(tree)
    require(REQUIRED <= set(manifest['files']) and all(os.access(tree / name, os.X_OK) for name in REQUIRED), 'Required tool executable is missing')
    write(output / 'toolchain.json', manifest)
    write(output / 'toolchain-runtime.json', RUNTIME | {'unpackedBytes': total})
    pack(tree, output / 'toolchain.tar.gz')
    require(inventory(tree) == (manifest, total), 'Toolchain changed during archive creation')
    result = {'schemaVersion': 1, 'state': 'ASSEMBLED_REQUIRES_LINUX_EXECUTION_QUALIFICATION',
              'sourceArchives': SOURCES, 'selectedSourcePaths': selected, 'materializedLinks': links,
              'sourceDuplicateDirectoryHeaders': duplicate_directories,
              'fileCount': len(manifest['files']), 'unpackedBytes': total,
              'archive': binding(output / 'toolchain.tar.gz'), 'manifest': binding(output / 'toolchain.json'),
              'runtime': RUNTIME, 'upstreamFileBytesModified': False, 'sourceArchivesModified': False,
              'awsCliInstallation': 'Original pinned 2.34.64 ZIP installed separately by bootstrap-growth-b-entry.sh',
              'applicationJarIncluded': False}
    write(output / 'assembly-receipt.json', result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sources', type=Path, required=True); parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = build(args.sources, args.output)
        print(json.dumps({key: result[key] for key in ('state', 'fileCount', 'unpackedBytes', 'archive', 'manifest')}))
    except Exception as error:
        detail = str(error) if type(error) is ValueError else type(error).__name__
        print('Toolchain assembly failed (' + detail + ').', file=__import__('sys').stderr)
        raise SystemExit(1)


if __name__ == '__main__': main()
