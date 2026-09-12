#!/usr/bin/env python3
"""Exercise the real B MySQL read fence and native search companion end to end.

Only a sealed small release is accepted. Producer and target MySQL/ES containers
are sequential, uniquely named, and removed by verified ID and volume claim.
The native filesystem repository and non-secret evidence remain for inspection.
No cloud requests, downloads, credential preparation, or frozen source edits.
"""
import argparse
import contextlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_search as search

spec = importlib.util.spec_from_file_location('source_test_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)

PASSWORD_ENV = 'AIRBOB_B_SEARCH_TEST_DB_PASSWORD'
RUN_LABEL = 'airbob.search-source-test.run'


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def verify_owned(resource, container, volume):
    """Verify every destructive target before removing either resource."""
    restore.require(container['Id'] == resource['id'] and
                    container['Name'] == '/' + resource['name'] and
                    container['Image'] == resource['image'], 'Test container identity changed')
    labels = container['Config'].get('Labels') or {}
    restore.require(all(labels.get(key) == value for key, value in resource['containerLabels'].items()),
                    'Test container labels changed')
    mounts = [item for item in container['Mounts'] if item['Destination'] == resource['dataPath']]
    restore.require(len(mounts) == 1 and mounts[0].get('Type') == 'volume' and
                    mounts[0].get('Name') == resource['volume'], 'Test data volume binding changed')
    restore.require(volume['Name'] == resource['volume'] and volume['Driver'] == 'local' and
                    not volume.get('Options') and
                    all((volume.get('Labels') or {}).get(key) == value
                        for key, value in resource['volumeLabels'].items()), 'Test volume claim changed')


def verify_table_fence(observed_locks, tables):
    # MySQL 8.4 reports LOCK TABLES READ metadata locks as TRANSACTION duration.
    # The lock mode, complete table set, owner, heartbeat, and blocked write prove
    # the live fence; the metadata duration label is not its connection lifetime.
    locks = [item for item in observed_locks if item['lockType'] == 'SHARED_READ_ONLY']
    restore.require(sorted(item['tableName'] for item in locks) == sorted(tables) and
                    len({item['ownerThreadId'] for item in locks}) == 1,
                    'A single live connection must hold every sealed base-table fence')
    return locks


class Resources:
    def __init__(self, dataset_id, suffix, output):
        self.dataset_id, self.suffix, self.output = dataset_id, suffix, output
        self.active = []
        self.events = []

    def event(self, stage, action, resource):
        self.events.append({'stage': stage, 'action': action, 'containerId': resource['id'],
                            'container': resource['name'], 'volume': resource['volume']})
        restore.write(self.output / 'resource-lifetime.json', {'maximumConcurrentMysqlContainers': 1,
                      'events': self.events, 'activeContainerIds': [item['id'] for item in self.active]})

    def require_exclusive(self):
        running = set(restore.docker('ps', '-q', '--no-trunc').decode().splitlines())
        restore.require(running <= {item['id'] for item in self.active},
                        'This isolated test requires all unrelated containers to be stopped')

    def mysql(self, stage, image, output):
        self.require_exclusive()
        restore.require(not any(item['kind'] == 'mysql' for item in self.active),
                        'Remove the previous MySQL and volume before creating the target')
        name = 'airbob-b-source-test-' + stage + '-mysql-' + self.suffix
        target = {'container': name, 'volume': name + '-data', 'image': image,
                  'mysqlPort': free_port(), 'memoryMiB': 2048, 'bufferPoolMiB': 512}
        (output / '.private').mkdir(mode=0o700)
        restore.create_target({'datasetId': self.dataset_id, 'target': target}, output)
        info = restore.inspect('container', name)
        volume = restore.inspect('volume', target['volume'])
        resource = {'kind': 'mysql', 'stage': stage, 'id': info['Id'], 'name': name,
                    'image': image, 'volume': target['volume'], 'dataPath': '/var/lib/mysql',
                    'containerLabels': {'airbob.dataset.id': self.dataset_id},
                    'volumeLabels': {'airbob.dataset.id': self.dataset_id,
                                     'airbob.restore.claim': volume['Labels']['airbob.restore.claim']},
                    'private': output / '.private'}
        verify_owned(resource, info, volume)
        self.active.append(resource); self.event(stage, 'CREATED', resource)
        db = restore.Database(info['Id'])
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try:
                if db.scalar('SELECT 1') == 1:
                    return info, db
            except Exception:
                pass
            time.sleep(1)
        raise ValueError('Test MySQL did not start')

    def elasticsearch(self, stage, image, native):
        self.require_exclusive()
        restore.require(not any(item['kind'] == 'elasticsearch' for item in self.active),
                        'Remove the previous ES container before creating the target')
        name = 'airbob-b-source-test-' + stage + '-es-' + self.suffix
        volume_name = name + '-data'
        labels = {'airbob.dataset.id': self.dataset_id, RUN_LABEL: self.suffix}
        restore.require(volume_name not in restore.existing('volume'), 'Fresh ES volume required')
        restore.docker('volume', 'create', '--label', RUN_LABEL + '=' + self.suffix,
                       '--label', 'airbob.dataset.id=' + self.dataset_id, volume_name)
        mount = 'type=bind,source=' + str(native) + ',target=/backup'
        if stage == 'target':
            mount += ',readonly'
        container_id = restore.docker('create', '--name', name, '--memory=1g',
            '--label', RUN_LABEL + '=' + self.suffix, '--label', 'airbob.dataset.id=' + self.dataset_id,
            '-e', 'ES_JAVA_OPTS=-Xms512m -Xmx512m', '-e', 'discovery.type=single-node',
            '-e', 'xpack.security.enabled=false', '-e', 'path.repo=/backup', '-p', '127.0.0.1::9200',
            '--mount', mount, '--mount', 'type=volume,source=' + volume_name + ',target=/usr/share/elasticsearch/data',
            image).decode().strip()
        resource = {'kind': 'elasticsearch', 'stage': stage, 'id': container_id, 'name': name,
                    'image': image, 'volume': volume_name, 'dataPath': '/usr/share/elasticsearch/data',
                    'containerLabels': labels, 'volumeLabels': labels}
        verify_owned(resource, restore.inspect('container', container_id), restore.inspect('volume', volume_name))
        self.active.append(resource); self.event(stage, 'CREATED', resource)
        restore.docker('start', container_id)
        port = restore.docker('port', container_id, '9200/tcp').decode().strip().rsplit(':', 1)[1]
        config = {'url': 'http://127.0.0.1:' + port, 'container': name, 'image': image,
                  'version': '8.18.8', 'diskSafetyBytes': 1024**3}
        es = search.Elasticsearch(config)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                es.identity()
                return config, es
            except (urllib.error.URLError, ConnectionError):
                time.sleep(1)
        raise ValueError('Test Elasticsearch did not start')

    def remove(self, resource):
        container = restore.inspect('container', resource['id'])
        volume = restore.inspect('volume', resource['volume'])
        verify_owned(resource, container, volume)
        restore.docker('rm', '-fv', resource['id'])
        restore.docker('volume', 'rm', resource['volume'])
        restore.require(resource['id'] not in restore.existing('container') and
                        resource['volume'] not in restore.existing('volume'), 'Test resource removal was incomplete')
        self.active.remove(resource); self.event(resource['stage'], 'REMOVED', resource)
        if resource.get('private'):
            shutil.rmtree(resource['private'])

    def cleanup(self):
        errors = []
        for resource in list(reversed(self.active)):
            try:
                self.remove(resource)
            except Exception as error:
                errors.append({'container': resource['name'], 'errorType': type(error).__name__})
        if errors:
            restore.write(self.output / 'cleanup-errors.json', errors)
        restore.require(not errors, 'Owned resource cleanup failed; inspect the retained private report')


def source_config(args, manifest, info, identity, suffix, native, es_config):
    environment = restore.connection_environment(info)
    config = {'releaseDirectory': str(args.release), 'datasetId': manifest['datasetId'],
              'consumerManifestSha256': args.consumer_manifest_sha256, 'checksSha256': args.checks_sha256,
              'migrationDirectory': str(ROOT / 'src/main/resources/db/migration'), 'appJar': str(args.app_jar),
              'allowSmallQualification': True, 'sourceTimeoutSeconds': 600, 'snapshotTimeoutSeconds': 600,
              'mysql': {'jdbcUrl': environment['AIRBOB_ETL_DB_URL'], 'username': 'root',
                        'passwordEnvironment': PASSWORD_ENV, 'expectedServerUuid': identity['uuid']},
              'elasticsearch': es_config,
              'snapshotRelease': manifest['datasetId'] + '-search-source-test-' + suffix,
              'repository': {'name': 'source-test-' + suffix, 'type': 'fs',
                             'settings': {'location': '/backup'}, 'inventoryRoot': str(native)}}
    return config, environment['AIRBOB_ETL_DB_PASSWORD']


@contextlib.contextmanager
def database_secret(password):
    previous = os.environ.get(PASSWORD_ENV)
    os.environ[PASSWORD_ENV] = password
    try:
        yield
    finally:
        if previous is None:
            os.environ.pop(PASSWORD_ENV, None)
        else:
            os.environ[PASSWORD_ENV] = previous


def live_fence(config, db, baseline, output, expected_documents):
    """Observe real table locks, a fresh JDBC heartbeat, and blocked no-op writes."""
    source = search.SourceAdapter(config, output / 'fence-adapter', runtime_output=output / 'fence-host-runtime.json')
    accommodation_id = db.scalar('SELECT MIN(id) FROM accommodation')
    # Explicit rollback also keeps an unexpected successful write from changing data.
    sql = ('SET SESSION sql_log_bin=0; SET SESSION lock_wait_timeout=1; START TRANSACTION; '
           'UPDATE accommodation SET name=name WHERE id=' + str(accommodation_id) + '; ROLLBACK;')
    with source.fence() as identity:
        observed_locks = db.rows("SELECT OBJECT_NAME tableName,OWNER_THREAD_ID ownerThreadId,LOCK_DURATION duration,LOCK_TYPE lockType FROM performance_schema.metadata_locks "
                        "WHERE OBJECT_TYPE='TABLE' AND OBJECT_SCHEMA='airbobdb' AND LOCK_STATUS='GRANTED' "
                        "ORDER BY OBJECT_NAME,LOCK_TYPE")
        observation = {'completedStage': 'metadata-locks', 'locks': observed_locks,
                       'expectedTables': sorted(baseline['tables'])}
        restore.write(output / 'source-fence-observation.json', observation)
        locks = verify_table_fence(observed_locks, baseline['tables'])
        prior = source.heartbeat_path.read_text()
        source.check_fence()
        restore.require(source.heartbeat_path.read_text() != prior, 'Fence heartbeat was stale')
        blocked = subprocess.run(db.command(), input=sql.encode(), stdout=subprocess.PIPE,
                                 stderr=subprocess.PIPE, timeout=15)
        observation.update(completedStage='competing-write', blockedReturnCode=blocked.returncode,
                           mysqlErrorNumbers=[int(item) for item in re.findall(rb'ERROR (\d+)', blocked.stderr)])
        restore.write(output / 'source-fence-observation.json', observation)
        restore.require(blocked.returncode != 0 and b'ERROR 1205 ' in blocked.stderr,
                        'A competing write was not blocked by the real source fence')
        projection = source.documents(output / 'current-documents.jsonl')
        restore.require(projection['documents'] == expected_documents and source.probe('fingerprint') == baseline,
                        'Fenced application projection or complete base rows differ')
    restore.command(db.command(), input=sql.encode(), timeout=15)
    proof = {'state': 'LIVE_MYSQL_SOURCE_FENCE_VERIFIED', 'mysql': identity,
             'hostRuntimeQualification': restore.qualification_binding(source.host_runtime_path),
             'lockedBaseTables': len(locks), 'oneOwningConnection': True, 'freshConnectionHeartbeat': True,
             'competingWriteBlockedWithMysql1205': True, 'competingWriteAllowedAfterRelease': True,
             'fullRowsAndDdlUnchanged': True, 'projection': projection}
    search.write(output / 'source-fence-proof.json', proof)
    # The adapter's connection/application properties contain the test password.
    shutil.rmtree(output / 'fence-adapter')
    return proof


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('release', 'app-jar', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('consumer-manifest-sha256', 'checks-sha256', 'mysql-image', 'image'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    args.release, args.app_jar, args.output = args.release.resolve(), args.app_jar.resolve(), args.output.resolve()
    restore.require(not args.output.exists() and not args.output.is_relative_to(args.release), 'Fresh private output outside the release required')
    for pin in (args.mysql_image, args.image):
        restore.require(re.fullmatch(r'sha256:[0-9a-f]{64}', pin), 'Exact local MySQL and ES image IDs required')
    restore.require(restore.sha(args.release / 'consumer-manifest.json') == args.consumer_manifest_sha256 and
                    restore.sha(args.release / 'SHA256SUMS.json') == args.checks_sha256, 'Reviewed release anchors differ')
    manifest = restore.read(args.release / 'consumer-manifest.json')
    restore.require(manifest['datasetScale'] == 'small-qualification' and not manifest['finalScaleSelected'],
                    'This harness accepts only the bounded small qualification')
    manifest, checks, baseline = restore.validate(args.release, manifest['datasetId'], ROOT / 'src/main/resources/db/migration',
                                                 allow_small=True, expected_app_sha=restore.sha(args.app_jar))
    search_qualification = restore.read(args.release / 'search-qualification.json')
    restore.require(args.image == search_qualification['imageId'], 'Use the ES image qualified by this sealed small release')
    expected_documents = search_qualification['publishedDocuments']
    args.output.mkdir(mode=0o700)
    runtime = restore.extract_runtime(args.release, args.output / 'runtime')
    lock_spec = importlib.util.spec_from_file_location('sealed_source_test_streaming', runtime / 'tools/growth_streaming.py')
    streaming = importlib.util.module_from_spec(lock_spec); lock_spec.loader.exec_module(streaming)
    resources = Resources(manifest['datasetId'], uuid.uuid4().hex[:12], args.output)
    current_stage = 'LOCAL_PREFLIGHT'; started = time.monotonic()
    try:
        current_stage = 'HOST_RUNTIME_QUALIFICATION'
        host_runtime_path = args.output / 'host-runtime-qualification.json'
        host_runtime = restore.qualify_runtime(args.release, runtime, host_runtime_path, expected_checks=checks)
        restore.require_local_engine()
        # This is the exact same local lock used by the large ETL runner.
        with contextlib.ExitStack() as run_lifetime:
            run_lifetime.enter_context(restore.activated_runtime(host_runtime))
            run_lifetime.enter_context(streaming.acquire_run_lock())
            # Remove owned resources before releasing the shared ETL run lock.
            run_lifetime.callback(resources.cleanup)
            resources.require_exclusive()
            original_containers, original_volumes = restore.existing('container'), restore.existing('volume')
            native = args.output / 'native-repository'; native.mkdir(mode=0o777); native.chmod(0o777)
            stages = {}
            for stage in ('producer', 'target'):
                current_stage = stage.upper() + '_RESTORE'
                output = args.output / stage; output.mkdir(mode=0o700)
                info, db = resources.mysql(stage, args.mysql_image, output)
                restore.streaming(db.command(), source=args.release / 'airbob-growth.sql.gz', timeout=300)
                observed = restore.fingerprint(runtime, args.release, info, output / 'full-fingerprint.json', 300)
                restore.require(observed == baseline, 'Freshly restored rows or DDL differ from the sealed base')
                identity = db.identity()
                restore.require(identity['version'] == '8.4.11' and
                                db.scalar("SELECT COUNT(*) FROM accommodation WHERE status='PUBLISHED'") == expected_documents,
                                'Live MySQL version or published-document count differs')
                es_config, es = resources.elasticsearch(stage, args.image, native)
                config, password = source_config(args, manifest, info, identity, resources.suffix, native, es_config)
                restore.write(output / 'search-config.json', config)
                with database_secret(password):
                    current_stage = stage.upper() + '_BASELINE'
                    captured = search.capture_baseline(config, output / 'baseline')
                    restore.require(captured['verifiedAllColumnsAndDdl'] and
                                    captured['mysql']['serverUuid'] == identity['uuid'], 'Live baseline identity differs')
                    if stage == 'producer':
                        current_stage = 'LIVE_SOURCE_FENCE'
                        fence = live_fence(config, db, baseline, output, expected_documents)
                        current_stage = 'NATIVE_PRODUCTION'
                        descriptor = search.produce(config, output / 'baseline', args.output / 'companion', build_index=True)
                        search.write(args.output / 'companion-descriptor.json', descriptor)
                        proof = search.read(args.output / 'companion/source-proof.json')
                        restore.require(proof['projection'] == fence['projection'], 'Producer differs from the independently exercised live reader')
                        reference = search.read(args.output / 'companion/snapshot-reference.json')
                        native_inventory = search.repository_for(config).inventory()
                    else:
                        current_stage = 'NATIVE_TARGET_RESTORE'
                        restore.require(identity['uuid'] != stages['producer']['mysql']['uuid'] and
                                        es.identity()['clusterUuid'] != stages['producer']['elasticsearch']['clusterUuid'],
                                        'Target must be a new MySQL server and ES cluster')
                        receipt = search.restore(config, args.output / 'companion', descriptor, output / 'baseline',
                                                 output / 'search-restore', activate_alias=False)
                        restore.require(receipt['state'] == 'SEARCH_VERIFIED_NOT_ACTIVATED' and
                                        receipt['fullDocumentFingerprint'] == reference['fingerprint'] and
                                        receipt['allDocumentSourceFieldsEqual'] and es.alias(optional=True) is None,
                                        'Target native restore changed full source fields, IDs, mapping, or alias')
                        restore.require(search.repository_for(config).inventory() == native_inventory,
                                        'Read-only target changed the native repository')
                after = restore.fingerprint(runtime, args.release, info, output / 'after-fingerprint.json', 300)
                restore.require(after == baseline, 'Search operations changed the immutable MySQL dataset')
                stages[stage] = {'mysql': identity, 'elasticsearch': es.identity(), 'fullRowsAndDdlUnchanged': True,
                    'baselineHostRuntimeQualification': captured['hostRuntimeQualification'],
                    'nativeHostRuntimeQualification': (proof if stage == 'producer' else receipt)['hostRuntimeQualification']}
                current_stage = stage.upper() + '_REMOVAL'
                resources.cleanup()
            current_stage = 'COMPLETE'
            restore.require(restore.existing('container') == original_containers and
                            restore.existing('volume') == original_volumes, 'Original container or volume inventory changed')
            result = {'state': 'SMALL_SOURCE_AND_NATIVE_SNAPSHOT_TEST_PASSED', 'finalScaleSelected': False,
                      'cloudExecution': False, 'datasetId': manifest['datasetId'], 'publishedDocuments': expected_documents,
                      'appJarSha256': manifest['appJarSha256'], 'dumpSha256': checks['airbob-growth.sql.gz'],
                      'consumerManifestSha256': args.consumer_manifest_sha256, 'checksSha256': args.checks_sha256,
                      'adapterSourceSha256': search.sha(Path(search.__file__)), 'testSourceSha256': search.sha(Path(__file__)),
                      'hostRuntimeQualification': restore.qualification_binding(host_runtime_path),
                      'sourceFence': fence, 'stages': stages, 'fullDocumentFingerprint': receipt['fullDocumentFingerprint'],
                      'differentMysqlServerUuids': True, 'differentElasticsearchClusterUuids': True,
                      'targetAliasAbsent': True, 'targetRepositoryReadOnly': True, 'nativeInventoryUnchanged': True,
                      'maximumConcurrentMysqlContainers': 1, 'allOwnedContainersAndVolumesRemoved': not resources.active,
                      'preExistingContainerIds': sorted(original_containers), 'preExistingVolumeNames': sorted(original_volumes),
                      'preExistingContainersPreserved': True, 'preExistingVolumesPreserved': True,
                      'plaintextTemporaryDumpBytes': 0, 'durationSeconds': round(time.monotonic() - started, 3)}
            search.write(args.output / 'source-test-result.json', result)
            print(json.dumps({'state': result['state'], 'publishedDocuments': expected_documents,
                              'durationSeconds': result['durationSeconds']}))
    except BaseException as error:
        restore.write(args.output / 'source-test-failure.json', {'state': 'FAILED', 'stage': current_stage,
                      'errorType': type(error).__name__, 'datasetId': manifest['datasetId']})
        raise
    finally:
        resources.cleanup()
        # Remove any source adapter left by a failed assertion, including properties.
        for private in args.output.glob('*/fence-adapter'):
            shutil.rmtree(private)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Small source integration failed (' + type(error).__name__ + '); no success receipt was issued.', file=sys.stderr)
        raise SystemExit(1)
