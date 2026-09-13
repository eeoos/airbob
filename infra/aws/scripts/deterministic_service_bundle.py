#!/usr/bin/env python3
"""Write config-only tar/gzip bytes independent of checkout time and host IDs."""
import argparse
import gzip
from pathlib import Path
import re
import stat
import tarfile


def write_archive(root, names, destination, timestamp):
    root = Path(root).resolve()
    names = list(names)
    if not names or len(names) != len(set(names)) or type(timestamp) is not int or timestamp < 0:
        raise ValueError('Invalid deterministic archive inventory or timestamp')
    paths = []
    for name in names:
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_./+-]+', name) or any(
                part in ('', '.', '..') for part in name.split('/')):
            raise ValueError('Unsafe deterministic archive member')
        path = root / name
        if path.resolve() != path.absolute() or not stat.S_ISREG(path.lstat().st_mode):
            raise ValueError('A regular file without symlinked parents is required')
        paths.append(path)
    with Path(destination).open('xb') as output:
        with gzip.GzipFile(filename='', fileobj=output, mode='wb', compresslevel=9, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                for name, path in zip(names, paths):
                    member = tarfile.TarInfo(name)
                    member.size = path.stat().st_size
                    member.mode = 0o644
                    member.uid = member.gid = 0
                    member.uname = member.gname = ''
                    member.mtime = timestamp
                    with path.open('rb') as stream:
                        archive.addfile(member, stream)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('inventory', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('timestamp', type=int)
    args = parser.parse_args()
    write_archive(args.root, args.inventory.read_text().splitlines(), args.destination, args.timestamp)
