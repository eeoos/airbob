#!/usr/bin/env python3
"""Disposable native ES test using exact JSONL from the real, sealed-run reader.

This is not a producer/source lineage receipt and cannot become a companion.
The only deleted resources are this script's uniquely named Docker container
and its anonymous volumes. The host-native repository is retained for inspection.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import uuid
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search as search


def main():
    parser = argparse.ArgumentParser()
    for key in ('documents', 'mapping', 'output'): parser.add_argument('--' + key, type=Path, required=True)
    parser.add_argument('--image', required=True)
    args = parser.parse_args(); args.output.mkdir(mode=0o700)
    repository_path = args.output / 'native-repository'; repository_path.mkdir(mode=0o777); repository_path.chmod(0o777)
    name = 'airbob-b-native-test-' + uuid.uuid4().hex[:12]
    started = time.monotonic(); running = False
    try:
        search.run(['docker', 'run', '-d', '--name', name, '--memory=1g', '-e', 'ES_JAVA_OPTS=-Xms512m -Xmx512m',
                    '-e', 'discovery.type=single-node', '-e', 'xpack.security.enabled=false', '-e', 'path.repo=/backup',
                    '-p', '127.0.0.1::9200', '-v', str(repository_path.resolve()) + ':/backup', args.image])
        running = True
        port = search.run(['docker', 'port', name, '9200/tcp']).decode().strip().rsplit(':', 1)[1]
        es = search.Elasticsearch({'url': 'http://127.0.0.1:' + port, 'container': name, 'version': '8.18.8', 'image': args.image})
        for attempt in range(90):
            try: identity = es.identity(); break
            except (urllib.error.URLError, ConnectionError): time.sleep(1)
        else: raise AssertionError('Disposable ES did not become ready')
        index = 'accommodations-vnative-test'; restored = 'accommodations-vnative-restored'
        es.create_from_source(index, args.documents, search.read(args.mapping))
        es.api('PUT', '/' + index + '/_settings', {'index.blocks.write': True})
        original = es.fingerprint(index, args.documents)
        space = search.disk_gate(es, args.documents.stat().st_size * 3)
        repo = search.Repository({'name': 'native-test', 'type': 'fs', 'settings': {'location': '/backup'}, 'inventoryRoot': str(repository_path)})
        repo.register(es, False)
        info = search.create_snapshot(es, repo, 'native-test-snapshot', index, 300)
        repo.unregister(es); repo.register(es, True)
        inventory = repo.inventory()
        reference = {'snapshot': info['snapshot'], 'snapshotIndex': index, 'snapshotUuid': info['uuid']}
        search.validate_snapshot(search.snapshot_info(es, repo, info['snapshot']), info['snapshot'], index, info['uuid'])
        search.restore_index(es, repo, reference, restored, 300)
        try: actual = es.fingerprint(restored, args.documents)
        except urllib.error.HTTPError as error:
            diagnostic = json.loads(error.read())
            print(json.dumps({'nativeTestFailure': diagnostic, 'restoreHealth': es.api('GET', '/_cluster/health/' + restored)}), flush=True)
            raise
        search.require(original == actual and es.alias() == index, 'Native restore changed sources or alias')
        # A nested/source-only mismatch must fail even with the same IDs/count.
        changed = args.output / 'deliberately-changed-documents.jsonl'
        with args.documents.open('rb') as source, changed.open('wb') as target:
            row = json.loads(source.readline()); row['currency'] = 'NOT_THE_SOURCE_CURRENCY'
            target.write(search.canonical(row) + b'\n')
            for line in source: target.write(line)
        rejected_source = False
        try: es.fingerprint(restored, changed)
        except ValueError: rejected_source = True
        changed.unlink(); search.require(rejected_source, 'Full source mismatch was not rejected')
        readonly_rejected = False
        try: es.api('PUT', '/_snapshot/' + repo.name + '/forbidden-write', {'indices': index, 'include_global_state': False})
        except urllib.error.HTTPError: readonly_rejected = True
        search.require(readonly_rejected and repo.inventory() == inventory, 'Read-only repository accepted a mutation')
        repo.unregister(es)
        result = {'state': 'DISPOSABLE_NATIVE_TEST_PASSED', 'isProductionSourceProof': False,
            'source': {'documents': search.file_binding(args.documents), 'mapping': search.file_binding(args.mapping)},
            'elasticsearch': identity, 'snapshotUuid': info['uuid'], 'snapshotMetadataVersion': info['version'],
            'snapshotMetadataVersionId': info['version_id'], 'sourceFingerprint': original,
            'adapterSourceSha256': search.sha(Path(search.__file__)), 'testSourceSha256': search.sha(Path(__file__)),
            'restoredFingerprint': actual, 'fullSourceMutationRejected': rejected_source,
            'readOnlySnapshotWriteRejected': readonly_rejected, 'singleWriteAliasUnchanged': True,
            'nativeInventoryUnchanged': True, 'nativeInventorySha256': search.digest(inventory),
            'diskGate': space, 'durationSeconds': round(time.monotonic() - started, 3)}
        search.write(args.output / 'native-test-result.json', result)
        print(json.dumps({'state': result['state'], 'documents': original['documents'], 'durationSeconds': result['durationSeconds']}))
    finally:
        if running: search.run(['docker', 'rm', '-fv', name])


if __name__ == '__main__': main()
