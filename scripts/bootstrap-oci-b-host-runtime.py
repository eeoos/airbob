"""One-use, isolated OCI CPython bootstrap and DB-free timezone qualification.

Run with the pinned script SHA argument and a JSON payload on stdin. The caller
transmits this exact source over SSH with strict host-key checking. Only new
ubuntu-owned directories under .airbob-b-host-runtime may be created. Existing
runtime paths are never replaced. No package manager, container, or DB is used.
"""

import base64
import datetime as dt
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import platform
import posixpath
import shutil
import stat
import subprocess
import sys
import tarfile
import time
import urllib.parse
import urllib.request


BASE = Path('/home/ubuntu')
TOOLS = BASE / '.airbob-b-host-runtime'
RUNTIME = TOOLS / 'cpython-3.12.14-20260901-aarch64-unknown-linux-gnu'
WORK = TOOLS / 'bootstrap-cpython-3.12.14-20260901-20260911'
JAVA_HOME = Path('/usr/lib/jvm/java-21-openjdk-arm64')
ASSET_URL = ('https://github.com/astral-sh/python-build-standalone/releases/download/'
             '20260901/cpython-3.12.14%2B20260901-aarch64-unknown-linux-gnu-'
             'install_only_stripped.tar.gz')
ASSET_SHA256 = '577b4bec0793ad1ff0cbff9adbd0df078eddde38a4c41bf5d83ad381a85ee39d'
ASSET_BYTES = 29199399
QUALIFICATION_SHA256 = '84af37f1f2e2fdbb6787bda3f7de3f88fd9942bb0e7f6dff2e8ccdc7e9616721'
PROVENANCE_SHA256 = '7bcdd02114ea030f7ecb61f613c3db97cd728b7331c18bccfc43fa6bf5b82eef'
MAX_MEMBERS = 50000
MAX_UNPACKED_BYTES = 1024 * 1024 * 1024
MAX_FILE_BYTES = 256 * 1024 * 1024


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def create_file(path, data, mode=0o600):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(data)


def write_json(path, value):
    create_file(path, (json.dumps(value, indent=2, sort_keys=True) + '\n').encode())


def safe_existing_directory(path, owned=False):
    path = Path(path)
    for candidate in (path, *path.parents):
        metadata = candidate.lstat()
        require(stat.S_ISDIR(metadata.st_mode), 'directory ancestor is missing, a link, or not a directory')
    if owned:
        metadata = path.stat()
        require(metadata.st_uid == os.getuid(), 'dedicated directory is not owned by current user')
        require(not metadata.st_mode & 0o022, 'dedicated directory is writable by another user')
    require(path.resolve() == path, 'directory path is not canonical')


def member_path(name):
    require(bool(name) and not any(ord(c) < 32 or ord(c) == 127 for c in name), 'invalid archive path')
    require('\\' not in name and not name.startswith('/'), 'absolute or platform-dependent archive path')
    canonical = name.rstrip('/')
    parts = canonical.split('/')
    require(parts[0] == 'python' and all(part not in ('', '.', '..') for part in parts),
            'archive path escapes or has unexpected top level')
    require(str(PurePosixPath(canonical)) == canonical, 'noncanonical archive path')
    return canonical


def link_target(name, linkname):
    require(bool(linkname) and not linkname.startswith('/') and '\\' not in linkname,
            'absolute or platform-dependent symlink target')
    require(not any(ord(c) < 32 or ord(c) == 127 for c in linkname), 'invalid symlink target')
    target = posixpath.normpath(posixpath.join(posixpath.dirname(name), linkname))
    require(target == 'python' or target.startswith('python/'), 'symlink escapes archive root')
    return target


def archive_audit(archive):
    require(Path(archive).stat().st_size == ASSET_BYTES, 'archive size differs from official release metadata')
    require(digest(archive) == ASSET_SHA256, 'archive SHA256 differs from official release metadata')
    entries = {}
    directories = {'python'}
    total_bytes = 0
    with tarfile.open(archive, 'r:gz') as source:
        for entry in source:
            require(len(entries) < MAX_MEMBERS, 'archive member count limit exceeded')
            name = member_path(entry.name)
            require(name not in entries, 'duplicate canonical archive path')
            require(entry.isfile() or entry.isdir() or entry.issym(), 'unsupported archive entry type')
            require(not entry.sparse, 'sparse archive entry is unsupported')
            require(0 <= entry.size <= MAX_FILE_BYTES, 'archive file size limit exceeded')
            if entry.isfile():
                total_bytes += entry.size
                require(total_bytes <= MAX_UNPACKED_BYTES, 'archive unpacked size limit exceeded')
            entries[name] = entry
            directories.update(str(p) for p in PurePosixPath(name).parents if str(p) != '.')
            if entry.isdir():
                directories.add(name)
    require('python' not in entries or entries['python'].isdir(), 'archive root is not a directory')
    for directory in directories:
        require(directory not in entries or entries[directory].isdir(), 'member traverses a file or symlink')
    links = {name: link_target(name, entry.linkname) for name, entry in entries.items() if entry.issym()}

    def resolve_target(target, seen):
        require(target not in seen, 'archive symlink cycle')
        if target in links:
            return resolve_target(links[target], seen | {target})
        require(target in entries or target in directories, 'archive symlink has a missing target')
        for ancestor in PurePosixPath(target).parents:
            require(str(ancestor) not in links, 'symlink target traverses another symlink')
        require(target in directories or entries[target].isfile(), 'archive symlink ultimate target is unsupported')
        return target

    for name, target in links.items():
        resolve_target(target, {name})
    summary = {
        'passed': True, 'archiveSha256': ASSET_SHA256, 'archiveBytes': ASSET_BYTES,
        'memberCount': len(entries), 'regularFileCount': sum(e.isfile() for e in entries.values()),
        'symlinkCount': len(links), 'hardLinkCount': 0, 'unpackedRegularBytes': total_bytes,
        'directoriesIncludingImplicit': len(directories), 'topLevel': 'python',
        'absolutePaths': 0, 'escapingLinks': 0, 'duplicatePaths': 0,
        'symlinkAncestorEntries': 0, 'specialOrSparseEntries': 0,
        'archiveOwnershipPreserved': False, 'setuidOrSetgidPreserved': False,
        'extractionMethod': 'exclusive manual writes; regular files before in-root symbolic links',
    }
    return entries, directories, summary


def extract(archive, runtime, entries, directories):
    runtime.mkdir(mode=0o700)
    for name in sorted(directories - {'python'}, key=lambda value: (value.count('/'), value)):
        (runtime / name.removeprefix('python/')).mkdir(mode=0o755)
    with tarfile.open(archive, 'r:gz') as source:
        for entry in source:
            if not entry.isfile():
                continue
            target = runtime / member_path(entry.name).removeprefix('python/')
            mode = 0o755 if entry.mode & 0o111 else 0o644
            descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, mode)
            with os.fdopen(descriptor, 'wb') as output, source.extractfile(entry) as input_stream:
                shutil.copyfileobj(input_stream, output, 1024 * 1024)
            require(target.stat().st_size == entry.size, 'extracted file size mismatch')
    for name, entry in entries.items():
        if entry.issym():
            (runtime / name.removeprefix('python/')).symlink_to(entry.linkname)
    for name, entry in entries.items():
        if entry.issym():
            resolved = (runtime / name.removeprefix('python/')).resolve(strict=True)
            require(resolved.is_relative_to(runtime), 'extracted symlink escapes runtime root')


class OfficialDownloadRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlparse(newurl)
        require(target.scheme == 'https' and target.hostname in {
            'github.com', 'release-assets.githubusercontent.com', 'objects.githubusercontent.com'
        }, 'download redirected outside official HTTPS release hosts')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(destination):
    opener = urllib.request.build_opener(OfficialDownloadRedirects())
    request = urllib.request.Request(ASSET_URL, headers={'User-Agent': 'Airbob-runtime-bootstrap/1'})
    value, total = hashlib.sha256(), 0
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'wb') as output, opener.open(request, timeout=45) as response:
        require(response.status == 200, 'official release download returned unexpected status')
        for chunk in iter(lambda: response.read(1024 * 1024), b''):
            total += len(chunk)
            require(total <= ASSET_BYTES, 'download exceeded pinned archive size')
            value.update(chunk)
            output.write(chunk)
    require(total == ASSET_BYTES and value.hexdigest() == ASSET_SHA256, 'download identity mismatch')
    return {'bytes': total, 'sha256': value.hexdigest(), 'verified': True}


def install(payload, bootstrap_sha):
    started = now()
    timer = time.monotonic()
    require(platform.system() == 'Linux' and platform.machine() == 'aarch64', 'target must be Linux aarch64')
    require(os.getuid() != 0 and Path.home() == Path('/home/ubuntu'), 'target must be the unprivileged ubuntu user')
    require(not RUNTIME.exists() and not RUNTIME.is_symlink(), 'runtime path already exists; refusing replacement')
    require(not WORK.exists() and not WORK.is_symlink(), 'bootstrap work path already exists; refusing replacement')
    manifest = payload['manifest']
    require(manifest['assetUrl'] == ASSET_URL and manifest['assetSha256'] == ASSET_SHA256
            and manifest['assetBytes'] == ASSET_BYTES, 'payload release identity mismatch')
    require(manifest['qualificationScriptSha256'] == QUALIFICATION_SHA256, 'payload qualification identity mismatch')
    require(manifest['sourceProvenanceSha256'] == PROVENANCE_SHA256, 'payload provenance identity mismatch')
    zones = manifest['selectedZones']
    require(len(zones) == 41 and zones == sorted(set(zones)), 'expected 41 unique sorted source zones')
    require(all(isinstance(zone, str) and 1 < len(zone) < 100 and '\n' not in zone for zone in zones), 'invalid source zone')
    qualification_source = base64.b64decode(payload['qualificationSourceBase64'], validate=True)
    bootstrap_source = base64.b64decode(payload['bootstrapSourceBase64'], validate=True)
    require(hashlib.sha256(qualification_source).hexdigest() == QUALIFICATION_SHA256, 'qualification source bytes mismatch')
    require(hashlib.sha256(bootstrap_source).hexdigest() == bootstrap_sha, 'bootstrap source bytes mismatch')
    safe_existing_directory(BASE, owned=True)
    if not TOOLS.exists():
        TOOLS.mkdir(mode=0o700)
    safe_existing_directory(TOOLS, owned=True)
    require((JAVA_HOME / 'bin/java').is_file(), 'host JDK is missing')
    require(shutil.disk_usage(TOOLS).free >= 512 * 1024 * 1024, 'less than 512 MiB free for isolated bootstrap')
    os.umask(0o077)
    WORK.mkdir(mode=0o700)
    create_file(WORK / 'bootstrap-oci-b-host-runtime.py', bootstrap_source)
    create_file(WORK / 'growth_timezones.py', qualification_source)
    write_json(WORK / 'input-manifest.json', manifest)
    selected_zones_bytes = (json.dumps(zones, indent=2) + '\n').encode()
    create_file(WORK / 'selected-zones.json', selected_zones_bytes)
    temp = WORK / 'tmp'
    temp.mkdir(mode=0o700)
    before = shutil.disk_usage(TOOLS)
    archive = WORK / 'cpython-3.12.14-20260901-aarch64-install_only_stripped.tar.gz'
    download_identity = download(archive)
    entries, directories, safety = archive_audit(archive)
    extract(archive, RUNTIME, entries, directories)
    executable = RUNTIME / 'bin/python3.12'
    with executable.open('rb') as stream:
        elf = stream.read(20)
    require(elf[:6] == b'\x7fELF\x02\x01' and int.from_bytes(elf[18:20], 'little') == 183,
            'installed Python is not a 64-bit little-endian AArch64 executable')
    env = {'HOME': '/home/ubuntu', 'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8',
           'TMPDIR': str(temp), 'JAVA_HOME': str(JAVA_HOME)}
    identity_code = ('import hashlib,json,platform,sys; print(json.dumps({'
                     '"version":sys.version,"versionInfo":list(sys.version_info[:3]),'
                     '"executable":sys.executable,"machine":platform.machine(),'
                     '"implementation":platform.python_implementation()}))')
    identity_run = subprocess.run([str(executable), '-I', '-B', '-c', identity_code],
                                  env=env, cwd=WORK, capture_output=True, text=True, timeout=15, check=True)
    identity = json.loads(identity_run.stdout)
    require(identity['versionInfo'] == [3, 12, 14] and identity['machine'] == 'aarch64', 'installed runtime identity differs')
    identity.update({'sha256': digest(executable), 'bytes': executable.stat().st_size,
                     'runtimeRoot': str(RUNTIME), 'globalPythonChanged': False})
    command = [str(executable), '-I', '-B', str(WORK / 'growth_timezones.py'), '--output',
               str(WORK / 'runtime-qualification.json'), '--java-home', str(JAVA_HOME)]
    for zone in zones:
        command += ['--zone', zone]
    gate_started = now()
    gate = subprocess.run(command, env=env, cwd=WORK, capture_output=True, text=True, timeout=180)
    create_file(WORK / 'qualification.stdout.txt', gate.stdout.encode())
    create_file(WORK / 'qualification.stderr.txt', gate.stderr.encode())
    gate_report = json.loads((WORK / 'runtime-qualification.json').read_text())
    after = shutil.disk_usage(TOOLS)
    report = {
        'state': 'HOST_RUNTIME_QUALIFIED' if gate.returncode == 0 and gate_report.get('passed') else 'HOST_RUNTIME_REJECTED',
        'startedAt': started, 'completedAt': now(), 'elapsedSeconds': round(time.monotonic() - timer, 3),
        'host': {'architecture': platform.machine(), 'system': platform.system(), 'uid': os.getuid()},
        'runtime': identity, 'workDirectory': str(WORK), 'javaHome': str(JAVA_HOME),
        'release': {'tag': '20260901', 'assetUrl': ASSET_URL, 'download': download_identity},
        'archiveSafety': safety, 'bootstrapScriptSha256': bootstrap_sha,
        'qualificationScriptSha256': digest(WORK / 'growth_timezones.py'),
        'sourceProvenancePath': manifest['sourceProvenancePath'], 'sourceProvenanceSha256': PROVENANCE_SHA256,
        'selectedZones': zones, 'selectedZoneCount': len(zones),
        'selectedZonesFileSha256': hashlib.sha256(selected_zones_bytes).hexdigest(),
        'qualificationCommand': command, 'qualificationStartedAt': gate_started,
        'qualificationExitCode': gate.returncode, 'qualificationReportSha256': digest(WORK / 'runtime-qualification.json'),
        'qualification': gate_report,
        'disk': {'beforeAvailableBytes': before.free, 'afterAvailableBytes': after.free},
        'scope': {'actualOciHostExecution': True, 'currentAppQualified': False,
                  'timeShapeFullBoundaryInclusionTested': False, 'databaseConnected': False,
                  'mysqlTlsConnected': False, 'hostMysqlInstalled': False,
                  'globalPackagesChanged': False, 'alternativesChanged': False,
                  'containerCommandsExecuted': False, 'servicesStartedOrRestarted': False,
                  'globalTimezoneDatabaseChanged': False},
    }
    write_json(WORK / 'bootstrap-result.json', report)
    print(json.dumps(report, sort_keys=True))
    return 0 if report['state'] == 'HOST_RUNTIME_QUALIFIED' else 1


if __name__ == '__main__':
    require(len(sys.argv) == 2, 'exact bootstrap SHA256 argument required')
    sys.exit(install(json.load(sys.stdin), sys.argv[1]))
