#!/usr/bin/env python3
"""Bind the reviewed main479 image JAR to the sealed B JAR's complete entries.

This has no AWS, Docker, or publication calls. A service must fetch the binding
with its pinned S3 VersionId/bytes/SHA before validation, then hash the actual
container's /app/app.jar and call verify_running_jar before starting the app.
The original preparation/search whole-JAR identity remains unchanged.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import sys
import zipfile


CONTRACT = 'global-b-main-479-entry-bytes-v1'
MAIN_COMMIT = '479ba02d54954af46f1f5633aceddeb69e321ff3'
INDEX_DIGEST = 'sha256:442574616c6e5572ee7c9493ccd9ec59eeed629188371a6384513816d589be70'
IMAGE = '942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo@' + INDEX_DIGEST
EMPTY_SHA = hashlib.sha256(b'').hexdigest()
MAX_JSON, MAX_JAR, MAX_EXPANDED, MAX_ENTRIES = 2 * 1024**2, 256 * 1024**2, 512 * 1024**2, 10000
SOURCE = {'sha256': 'e0f83dfa40a7359492c1f3bdb9b5fa88902b59504fab59e49781077db5e2285c', 'bytes': 154661318,
          'inventorySha256': '2f7a91e1fc671aac06f6c0982c079557194e83b87009ae4e6e5751d886982c85',
          'fileCount': 1277, 'directoryCount': 217}
ACTUAL = {'sha256': '95e88595b1cb245160109f3709bfa8ea17ff66fc532c99aaaf86b2b491334a8b', 'bytes': 154670045,
          'inventorySha256': 'aa6e1d3f82d7fc2bd3a15e80e352b9ac2ad70f5ed2874e7fa6413a6f2fbeecbd',
          'fileCount': 1278, 'directoryCount': 219}
RUNTIME_DIGEST = 'a60c1ee2d3a2a51b2d492e6ff1b69144b1d25e4eea341cc238d045123c2cc2c6'
APP_LAYER = {'digest': 'sha256:6c3c3c448811a4f7bdcbfd216a81c8c96452cdc10682b6626ff3187d67236af8',
             'bytes': 142099262}
PLATFORMS = [
    {'platform': 'linux/amd64', 'manifestDigest': 'sha256:b0475b8e8af079554a59204faea59b1ef72ed40d070f7a5d407dbf68d9ce240d',
     'manifestBytes': 1626, 'configDigest': 'sha256:8329f0479ea9c2380bd6f3180263e14917689991bf4512ffe50596193883278f'},
    {'platform': 'linux/arm64', 'manifestDigest': 'sha256:0c6d2f4fca81fa54942efe0bcc88ceae95c5710c8ee8d3953ff722f04c4d9d65',
     'manifestBytes': 1626, 'configDigest': 'sha256:af24fa5b4205e80a89aa0332053866f8ba1e667180c424a1eb6e78862cdc3932'},
]
DOC_FILE = {'path': 'BOOT-INF/classes/static/docs/index.html', 'kind': 'regular', 'bytes': 33914,
            'sha256': '612987b8c9668bff5102a3a64bdc7827b68065de355c2c38589fa3ce42883fa8'}
DOC_DIRS = [{'path': path, 'kind': 'directory', 'bytes': 0, 'sha256': EMPTY_SHA}
            for path in ('BOOT-INF/classes/static/', 'BOOT-INF/classes/static/docs/')]
POLICY = {'fileBytes': 'all regular entries including loader, META-INF, manifest, BOOT-INF and indexes',
          'duplicatePaths': 'reject', 'unsafePaths': 'reject', 'symlinks': 'reject', 'preamble': 'empty',
          'zipEncodingAndTimestamps': 'excluded from entry bytes; whole-JAR SHA remains exact',
          'onlyAddedEntries': 'exact pinned API documentation file and two empty parent directory entries'}


class RuntimeBindingError(ValueError):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def need(condition, code):
    if not condition:
        raise RuntimeBindingError(code)


def integer(value, minimum=0, maximum=2**63 - 1):
    return type(value) is int and minimum <= value <= maximum


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False) + '\n').encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def same(left, right):
    return canonical(left) == canonical(right)


def digest(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def regular(path, limit):
    path = Path(path).absolute()
    try:
        info = path.lstat()
    except OSError:
        raise RuntimeBindingError('INPUT_FILE_UNAVAILABLE') from None
    need(path.resolve() == path and stat.S_ISREG(info.st_mode) and 0 < info.st_size <= limit,
         'REGULAR_BOUNDED_INPUT_REQUIRED')
    return path


def read_json(path):
    def pairs(items):
        value = {}
        for key, item in items:
            need(key not in value, 'DUPLICATE_JSON_KEY')
            value[key] = item
        return value
    try:
        return json.loads(regular(path, MAX_JSON).read_bytes(), object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(RuntimeBindingError('INVALID_JSON_NUMBER')))
    except (ValueError, UnicodeError, RecursionError) as error:
        if isinstance(error, RuntimeBindingError):
            raise
        raise RuntimeBindingError('INVALID_JSON') from None


def path_ok(name, kind):
    need(isinstance(name, str) and 0 < len(name) <= 1024 and re.fullmatch(r'[\x21-\x7e]+', name)
         and not name.startswith('/') and '\\' not in name and ':' not in name, 'UNSAFE_JAR_ENTRY_PATH')
    need(kind in ('regular', 'directory') and name.endswith('/') is (kind == 'directory'), 'ENTRY_KIND_OR_PATH_INVALID')
    parts = name[:-1].split('/') if kind == 'directory' else name.split('/')
    need(all(part not in ('', '.', '..') for part in parts), 'UNSAFE_JAR_ENTRY_PATH')


def inventory_map(inventory):
    need(isinstance(inventory, dict) and set(inventory) == {'preamble', 'entries'}, 'INVALID_ENTRY_INVENTORY')
    need(same(inventory['preamble'], {'bytes': 0, 'sha256': EMPTY_SHA}), 'EXECUTABLE_PREAMBLE_NOT_ALLOWED')
    entries = inventory['entries']
    need(isinstance(entries, list) and 0 < len(entries) <= MAX_ENTRIES, 'ENTRY_COUNT_LIMIT')
    result, expanded = {}, 0
    for entry in entries:
        need(isinstance(entry, dict) and set(entry) == {'path', 'kind', 'bytes', 'sha256'}, 'INVALID_ENTRY_FIELDS')
        path_ok(entry['path'], entry['kind'])
        need(integer(entry['bytes'], maximum=MAX_EXPANDED) and digest(entry['sha256']), 'INVALID_ENTRY_BYTES_OR_SHA')
        need(entry['path'] not in result, 'DUPLICATE_JAR_ENTRY')
        if entry['kind'] == 'directory':
            need(entry['bytes'] == 0 and entry['sha256'] == EMPTY_SHA, 'DIRECTORY_HAS_CONTENT')
        result[entry['path']] = entry
        expanded += entry['bytes']
    need(list(result) == sorted(result) and expanded <= MAX_EXPANDED, 'INVENTORY_ORDER_OR_SIZE_INVALID')
    return result


def jar_inventory(path):
    """Read entry bytes without extracting or executing archive contents."""
    path = regular(path, MAX_JAR)
    try:
        with path.open('rb') as stream:
            whole_sha = hashlib.file_digest(stream, 'sha256').hexdigest()
            stream.seek(max(0, path.stat().st_size - 65557))
            tail = stream.read()
        position = tail.rfind(b'PK\x05\x06')
        need(position >= 0 and len(tail) - position >= 22, 'ZIP_END_RECORD_REQUIRED')
        _, disk, central_disk, count_disk, count, central_bytes, central_offset, comment = struct.unpack(
            '<4s4H2LH', tail[position:position + 22])
        need(disk == central_disk == 0 and count_disk == count and count <= MAX_ENTRIES
             and position + 22 + comment == len(tail), 'ZIP_CONTAINER_STRUCTURE_INVALID')
        absolute_end = path.stat().st_size - len(tail) + position
        need(central_offset + central_bytes == absolute_end, 'ZIP_CONTAINER_STRUCTURE_INVALID')
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            need(len(infos) == count and 0 < count <= MAX_ENTRIES and archive.start_dir == central_offset,
                 'ZIP_ENTRY_COUNT_MISMATCH')
            need(min(info.header_offset for info in infos) == 0, 'EXECUTABLE_PREAMBLE_NOT_ALLOWED')
            entries, seen, expanded = [], set(), 0
            for info in infos:
                need(info.orig_filename == info.filename and '\x00' not in info.orig_filename, 'UNSAFE_JAR_ENTRY_PATH')
                mode = (info.external_attr >> 16) & 0xffff
                need(not stat.S_ISLNK(mode) and stat.S_IFMT(mode) in (0, stat.S_IFREG, stat.S_IFDIR), 'JAR_SYMLINK_OR_SPECIAL_ENTRY')
                kind = 'directory' if info.is_dir() else 'regular'
                path_ok(info.filename, kind)
                need(info.filename not in seen, 'DUPLICATE_JAR_ENTRY')
                seen.add(info.filename)
                need(not info.flag_bits & 1 and info.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED),
                     'ZIP_ENCRYPTION_OR_COMPRESSION_NOT_ALLOWED')
                expanded += info.file_size
                need(info.file_size >= 0 and expanded <= MAX_EXPANDED, 'JAR_EXPANDED_SIZE_LIMIT')
                h, size = hashlib.sha256(), 0
                with archive.open(info) as stream:
                    while block := stream.read(1024 * 1024):
                        size += len(block)
                        need(size <= info.file_size, 'ZIP_ENTRY_SIZE_MISMATCH')
                        h.update(block)
                need(size == info.file_size, 'ZIP_ENTRY_SIZE_MISMATCH')
                entries.append({'path': info.filename, 'kind': kind, 'bytes': size, 'sha256': h.hexdigest()})
        result = {'preamble': {'bytes': 0, 'sha256': EMPTY_SHA}, 'entries': sorted(entries, key=lambda item: item['path'])}
        inventory_map(result)
        return {'sha256': whole_sha, 'bytes': path.stat().st_size, 'inventorySha256': sha(canonical(result)), 'inventory': result}
    except RuntimeBindingError:
        raise
    except (OSError, ValueError, RuntimeError, EOFError, zipfile.BadZipFile, struct.error):
        raise RuntimeBindingError('JAR_ARCHIVE_INVALID') from None


def compare_inventories(source, actual):
    left, right = inventory_map(source), inventory_map(actual)
    need(not set(left) - set(right), 'SOURCE_JAR_ENTRY_MISSING')
    allowed = {entry['path']: entry for entry in [DOC_FILE, *DOC_DIRS]}
    need(set(right) - set(left) == set(allowed), 'UNEXPECTED_ADDED_JAR_ENTRY')
    need(all(same(right[name], entry) for name, entry in allowed.items()), 'DOCUMENTATION_DELTA_NOT_EXACT')
    need(all(same(left[name], right[name]) for name in left), 'RUNTIME_ENTRY_BYTES_CHANGED')
    runtime = {'schemaVersion': 1, 'preamble': source['preamble'],
               'files': [entry for entry in source['entries'] if entry['kind'] == 'regular']}
    return {'runtimeDigest': sha(canonical(runtime)), 'commonFileCount': len(runtime['files']),
            'commonDirectoryCount': len(left) - len(runtime['files'])}


def platform_bindings():
    return [{**item, 'revision': MAIN_COMMIT, 'jarLayerDigest': APP_LAYER['digest'],
             'jarLayerBytes': APP_LAYER['bytes'], 'jarPath': '/app/app.jar', 'jarLayerIsFinal': True} for item in PLATFORMS]


def validate_image_proof(directory):
    """Validate exact ECR response bytes already downloaded by an operator."""
    directory = Path(directory).absolute()
    need(directory.is_dir() and directory.resolve() == directory, 'REGULAR_IMAGE_PROOF_DIRECTORY_REQUIRED')
    index_path = regular(directory / 'index-manifest.json', MAX_JSON)
    need('sha256:' + sha(index_path.read_bytes()) == INDEX_DIGEST, 'IMAGE_INDEX_DIGEST_MISMATCH')
    index = read_json(index_path)
    need(index.get('schemaVersion') == 2 and index.get('mediaType') == 'application/vnd.oci.image.index.v1+json',
         'IMAGE_INDEX_TYPE_MISMATCH')
    for expected in PLATFORMS:
        architecture = expected['platform'].split('/')[1]
        matches = [row for row in index['manifests'] if row.get('platform') == {'os': 'linux', 'architecture': architecture}]
        need(len(matches) == 1 and matches[0]['digest'] == expected['manifestDigest']
             and matches[0]['size'] == expected['manifestBytes'], 'IMAGE_PLATFORM_DESCRIPTOR_MISMATCH')
        manifest_path = regular(directory / (architecture + '-manifest.json'), MAX_JSON)
        raw = manifest_path.read_bytes()
        need('sha256:' + sha(raw) == expected['manifestDigest'] and len(raw) == expected['manifestBytes'], 'IMAGE_MANIFEST_DIGEST_MISMATCH')
        manifest = read_json(manifest_path)
        need(manifest['config']['digest'] == expected['configDigest'] and manifest['layers'][-1]['digest'] == APP_LAYER['digest']
             and manifest['layers'][-1]['size'] == APP_LAYER['bytes'], 'IMAGE_APP_LAYER_MISMATCH')
        config_path = regular(directory / (architecture + '-config.json'), MAX_JSON)
        raw = config_path.read_bytes()
        need('sha256:' + sha(raw) == expected['configDigest'] and len(raw) == manifest['config']['size'], 'IMAGE_CONFIG_DIGEST_MISMATCH')
        config = read_json(config_path)
        need(config['os'] == 'linux' and config['architecture'] == architecture
             and config['config']['Labels']['org.opencontainers.image.revision'] == MAIN_COMMIT
             and config['config']['Entrypoint'] == ['sh', '-c', 'java $JAVA_OPTS -jar /app/app.jar']
             and not config['config'].get('Volumes'), 'IMAGE_RUNTIME_CONFIG_MISMATCH')
        history = [item for item in config['history'] if not item.get('empty_layer', False)]
        need(len(history) == len(manifest['layers']) == len(config['rootfs']['diff_ids'])
             and history[-1]['created_by'] == 'COPY build/libs/airbob.jar app.jar # buildkit', 'IMAGE_FINAL_APP_COPY_REQUIRED')
    return platform_bindings()


def validate_jar_record(record, expected):
    need(isinstance(record, dict) and set(record) == {'sha256', 'bytes', 'inventorySha256', 'inventory'}, 'INVALID_JAR_RECORD')
    need(record['sha256'] == expected['sha256'] and type(record['bytes']) is int and record['bytes'] == expected['bytes'],
         'REVIEWED_WHOLE_JAR_IDENTITY_MISMATCH')
    entries = inventory_map(record['inventory'])
    need(record['inventorySha256'] == expected['inventorySha256'] == sha(canonical(record['inventory'])),
         'FULL_ENTRY_INVENTORY_SHA_MISMATCH')
    need(sum(entry['kind'] == 'regular' for entry in entries.values()) == expected['fileCount']
         and sum(entry['kind'] == 'directory' for entry in entries.values()) == expected['directoryCount'],
         'REVIEWED_ENTRY_COUNTS_MISMATCH')


def runtime_projection():
    return {'imageJarSha256': ACTUAL['sha256'], 'runtimeDigest': RUNTIME_DIGEST, 'runtimeContract': CONTRACT,
            'runtimeRevision': MAIN_COMMIT, 'sourceJarSha256': SOURCE['sha256'], 'image': IMAGE, 'mainCommit': MAIN_COMMIT}


def _validate(value, expected_image, expected_commit, expected_sealed_sha):
    need((expected_image, expected_commit, expected_sealed_sha) == (IMAGE, MAIN_COMMIT, SOURCE['sha256']),
         'EXPECTED_RUNTIME_IDENTITY_OUTSIDE_REVIEW')
    keys = {'schemaVersion', 'kind', 'runtimeContract', 'producedAt', 'producerToolSha256', 'image', 'mainCommit', 'platforms',
            'sourceJar', 'imageJar', 'allowedDocsDelta', 'runtimeDigest', 'runtime', 'comparison', 'zipComparisonPolicy'}
    need(isinstance(value, dict) and set(value) == keys and type(value['schemaVersion']) is int and value['schemaVersion'] == 1
         and value['kind'] == 'global-b-app-runtime-binding' and value['runtimeContract'] == CONTRACT, 'INVALID_RUNTIME_BINDING_SCHEMA')
    need(value['image'] == expected_image and value['mainCommit'] == expected_commit, 'RUNTIME_BINDING_IMAGE_OR_COMMIT_MISMATCH')
    need(isinstance(value['producedAt'], str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,6})?Z', value['producedAt'])
         and digest(value['producerToolSha256']), 'INVALID_PRODUCER_METADATA')
    need(value['producerToolSha256'] == sha(Path(__file__).read_bytes()), 'PRODUCER_TOOL_SHA_MISMATCH')
    try:
        datetime.fromisoformat(value['producedAt'].replace('Z', '+00:00'))
    except ValueError:
        raise RuntimeBindingError('INVALID_PRODUCER_METADATA') from None
    need(same(value['platforms'], platform_bindings()), 'PINNED_PLATFORM_BINDING_MISMATCH')
    need(same(value['zipComparisonPolicy'], POLICY) and same(value['allowedDocsDelta'],
         {'sourceAbsent': True, 'addedFile': DOC_FILE, 'addedDirectories': DOC_DIRS}), 'DOCUMENTATION_EXCEPTION_SCOPE_CHANGED')
    validate_jar_record(value['sourceJar'], SOURCE)
    validate_jar_record(value['imageJar'], ACTUAL)
    comparison = compare_inventories(value['sourceJar']['inventory'], value['imageJar']['inventory'])
    need(value['runtimeDigest'] == comparison.pop('runtimeDigest') == RUNTIME_DIGEST, 'RUNTIME_DIGEST_MISMATCH')
    need(same(value['comparison'], {**comparison, 'allCommonEntryBytesEqual': True, 'wholeJarEqual': False}),
         'COMPARISON_SUMMARY_MISMATCH')
    need(same(value['runtime'], runtime_projection()), 'RUNTIME_PROJECTION_MISMATCH')
    return runtime_projection()


def validate_runtime_binding(path, expected_image, expected_commit, expected_sealed_sha):
    """Validate a binding already fetched by exact VersionId/bytes/SHA."""
    try:
        return _validate(read_json(path), expected_image, expected_commit, expected_sealed_sha)
    except RuntimeBindingError:
        raise
    except (KeyError, TypeError, ValueError, OSError, RecursionError):
        raise RuntimeBindingError('RUNTIME_BINDING_INVALID') from None


def verify_running_jar(binding, actual_sha256):
    """Check the running-image JAR's observed whole-file digest before startup."""
    need(isinstance(binding, dict) and same(binding, runtime_projection()), 'VALIDATED_RUNTIME_PROJECTION_REQUIRED')
    need(actual_sha256 == ACTUAL['sha256'], 'RUNNING_JAR_SHA_MISMATCH')
    return True


def produce_runtime_binding(source_jar, image_jar, image_proof_directory):
    platforms = validate_image_proof(image_proof_directory)
    source, actual = jar_inventory(source_jar), jar_inventory(image_jar)
    validate_jar_record(source, SOURCE)
    validate_jar_record(actual, ACTUAL)
    comparison = compare_inventories(source['inventory'], actual['inventory'])
    runtime_digest = comparison.pop('runtimeDigest')
    value = {'schemaVersion': 1, 'kind': 'global-b-app-runtime-binding', 'runtimeContract': CONTRACT,
             'producedAt': datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z'),
             'producerToolSha256': sha(Path(__file__).read_bytes()), 'image': IMAGE, 'mainCommit': MAIN_COMMIT,
             'platforms': platforms, 'sourceJar': source, 'imageJar': actual,
             'allowedDocsDelta': {'sourceAbsent': True, 'addedFile': DOC_FILE, 'addedDirectories': DOC_DIRS},
             'runtimeDigest': runtime_digest, 'runtime': runtime_projection(),
             'comparison': {**comparison, 'allCommonEntryBytesEqual': True, 'wholeJarEqual': False},
             'zipComparisonPolicy': POLICY}
    _validate(value, IMAGE, MAIN_COMMIT, SOURCE['sha256'])
    return value


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest='action', required=True)
    produce = actions.add_parser('produce')
    produce.add_argument('--source-jar', type=Path, required=True)
    produce.add_argument('--image-jar', type=Path, required=True)
    produce.add_argument('--image-proof-directory', type=Path, required=True)
    produce.add_argument('--output', type=Path, required=True)
    validate = actions.add_parser('validate')
    validate.add_argument('--binding', type=Path, required=True)
    validate.add_argument('--expected-image', required=True)
    validate.add_argument('--expected-commit', required=True)
    validate.add_argument('--expected-sealed-sha', required=True)
    validate.add_argument('--actual-jar-sha')
    args = parser.parse_args(argv)
    try:
        if args.action == 'produce':
            value = produce_runtime_binding(args.source_jar, args.image_jar, args.image_proof_directory)
            raw = canonical(value)
            output = args.output.absolute()
            need(output.parent.is_dir() and output.parent.resolve() == output.parent, 'REGULAR_OUTPUT_DIRECTORY_REQUIRED')
            fd = os.open(output, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, 'wb') as stream:
                stream.write(raw)
                stream.flush()
                os.fsync(stream.fileno())
            print(json.dumps({'passed': True, 'bindingSha256': sha(raw), 'bytes': len(raw), 'runtimeDigest': value['runtimeDigest']}))
        else:
            projection = validate_runtime_binding(args.binding, args.expected_image, args.expected_commit, args.expected_sealed_sha)
            if args.actual_jar_sha is not None:
                verify_running_jar(projection, args.actual_jar_sha)
            print(json.dumps({'passed': True, **projection}))
        return 0
    except Exception as error:
        code = error.code if isinstance(error, RuntimeBindingError) else 'APP_RUNTIME_BINDING_FAILED'
        print(json.dumps({'passed': False, 'failureCode': code}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
