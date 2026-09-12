#!/usr/bin/env python3
"""Exercise B restore against two sequential, exclusively test-owned MySQL instances.

Requires an existing qualified small release and its exact application JAR. Never
inspects, stops or deletes a pre-existing MySQL container. No downloads or cloud calls.
"""
import argparse
import importlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('tested_restore', ROOT/'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, required=True)
    parser.add_argument('--app-jar', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--mode', choices=['local', 'retired-oci'], default='local')
    args = parser.parse_args()
    release, output = args.release.resolve(), args.output.resolve()
    restore.require(not output.exists(), 'Fresh output required')
    output.mkdir(mode=0o700)
    manifest = restore.read(release/'consumer-manifest.json')
    restore.require(manifest['datasetScale'] == 'small-qualification', 'Only a bounded existing small release is allowed')
    _, checks, _ = restore.validate(release, manifest['datasetId'], ROOT/'src/main/resources/db/migration', allow_small=True,
                                    expected_app_sha=restore.sha(args.app_jar))
    mysql = restore.inspect('image', 'mysql:8.4.11')['Id']
    redis = restore.inspect('image', 'redis:7-alpine')['Id']
    suffix = uuid.uuid4().hex[:12]
    old_name, new_name = 'airbob-b-test-old-'+suffix, 'airbob-b-test-new-'+suffix
    old_volume, new_volume = old_name+'-data', new_name+'-data'
    source_output = output/'source'; source_output.mkdir(); (source_output/'.private').mkdir()
    runtime = restore.extract_runtime(release, source_output/'runtime')
    host_runtime = restore.qualify_runtime(release, runtime, source_output/'host-runtime-qualification.json', expected_checks=checks)
    sys.path.insert(0, str(runtime/'tools'))
    account_tools = importlib.import_module('growth_accounts')
    accounts = restore.read(release/'accounts.json')
    target = {'container': old_name, 'volume': old_volume, 'image': mysql, 'redisImage': redis,
              'mysqlPort': free_port(), 'memoryMiB': 2048, 'bufferPoolMiB': 512}
    created_ids = {}
    observer = None
    success = False
    source_private = source_output/'account-credentials/accounts.private.json'
    private_accounts = output/'account-credentials/accounts.private.json'
    try:
        restore.create_target({'datasetId': manifest['datasetId'], 'target': target}, source_output)
        info = restore.inspect('container', old_name)
        created_ids[old_name] = info['Id']
        db = restore.Database(info['Id'])
        deadline = time.monotonic()+180
        while time.monotonic() < deadline:
            try:
                if db.scalar('SELECT 1') == 1: break
            except Exception: pass
            time.sleep(1)
        else: raise AssertionError('Test MySQL did not start')
        restore.streaming(db.command(), source=release/'airbob-growth.sql.gz', timeout=300)
        with restore.activated_runtime(host_runtime):
            baseline = restore.fingerprint(runtime, release, info, source_output/'fingerprint.json', 300)
        assert baseline == restore.read(release/'before-fingerprint.json')
        original_uuid = db.identity()['uuid']
        source_config = {'mode': 'local', 'datasetId': manifest['datasetId'], 'release': str(release),
            'appJar': str(args.app_jar.resolve()), 'privateAccounts': str(source_private), 'target': target,
            'operationTimeoutSeconds': 600, 'appStartupTimeoutSeconds': 180}
        account_tools.create_private_credentials(accounts, source_private.parent, restore.account_environment(source_config))
        with restore.activated_runtime(host_runtime):
            source_preparation = restore.prepare_service(source_config, source_output, runtime, info, db)
        db.execute('SET GLOBAL super_read_only=ON')
        source_receipt = source_output/'restore.json'
        restore.write(source_receipt, {'datasetId': manifest['datasetId'], 'dumpSha256': restore.sha(release/'airbob-growth.sql.gz'),
                'mysqlServerUuid': original_uuid, 'allRowsAndDdlEqual': True, 'fingerprint': baseline,
                'state': 'PREPARED_APP_STOPPED_DATABASE_FROZEN', 'preparation': source_preparation})
        config = {'schemaVersion': 1, 'mode': 'oci' if args.mode == 'retired-oci' else 'local', 'datasetId': manifest['datasetId'], 'release': str(release),
            'consumerManifestSha256': restore.sha(release/'consumer-manifest.json'), 'checksumsSha256': restore.sha(release/'SHA256SUMS.json'),
            'backendRoot': str(ROOT), 'appJar': str(args.app_jar.resolve()), 'privateAccounts': str(private_accounts), 'allowSmall': True,
            'source': {'container': old_name, 'containerId': info['Id'], 'volume': old_volume, 'mysqlServerUuid': original_uuid,
                'datasetId': manifest['datasetId'], 'restoreReceipt': str(source_receipt), 'restoreReceiptSha256': restore.sha(source_receipt),
                'fingerprint': str(source_output/'fingerprint.json'), 'fingerprintSha256': restore.sha(source_output/'fingerprint.json'),
                'dump': str(release/'airbob-growth.sql.gz'), 'dumpSha256': restore.sha(release/'airbob-growth.sql.gz')},
            'target': dict(target, container=new_name, volume=new_volume, mysqlPort=free_port()),
            'preservationPolicy': 'capture-current', 'writerContainers': [], 'writerPorts': [],
            'capacity': {'reserveHostBytes': 1024**3, 'reserveDockerBytes': 1024**3, 'requiredDatabaseBytes': 1024**3, 'maxBackupBytes': 1024**3},
            'operationTimeoutSeconds': 600, 'appStartupTimeoutSeconds': 180}
        account_tools.create_private_credentials(accounts, private_accounts.parent, restore.account_environment(config))
        config_path = output/'config.json'; restore.write(config_path, config)
        if args.mode == 'retired-oci':
            # Preserve and restore-check the actually prepared source before the explicit retirement.
            captured = source_output/'.private/prepared-source.sql.gz'
            capture = ['docker', 'exec', info['Id'], 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --skip-lock-tables '
                '--hex-blob --set-gtid-purged=OFF --no-tablespaces --skip-comments --column-statistics=0 airbobdb']
            restore.streaming(capture, destination=captured, timeout=600, byte_limit=1024**3, reserve_bytes=1024**3)
            restore.inspect_dump(captured)
            with restore.activated_runtime(host_runtime):
                current = restore.fingerprint(runtime, release, info, source_output/'prepared-source-fingerprint.json', 300)
                db.execute('SET GLOBAL super_read_only=OFF; DROP DATABASE airbobdb; CREATE DATABASE airbobdb')
                restore.streaming(db.command(), source=captured, timeout=300)
                restored_current = restore.fingerprint(runtime, release, info, source_output/'prepared-source-restored-fingerprint.json', 300)
            assert current == restored_current
            db.execute('SET GLOBAL super_read_only=ON')
            restore.write(source_receipt, {'datasetId': manifest['datasetId'], 'dumpSha256': restore.sha(captured),
                'mysqlServerUuid': original_uuid, 'allRowsAndDdlEqual': True, 'fingerprint': current,
                'state': 'PREPARED_APP_STOPPED_DATABASE_FROZEN', 'preparation': source_preparation})
            config['source'].update(restoreReceiptSha256=restore.sha(source_receipt), dump=str(captured), dumpSha256=restore.sha(captured),
                fingerprint=str(source_output/'prepared-source-fingerprint.json'), fingerprintSha256=restore.sha(source_output/'prepared-source-fingerprint.json'))
            restore.docker('stop', '--time', '30', info['Id'])
            restore.docker('rm', info['Id'])
            restore.docker('volume', 'rm', old_volume)
            assert info['Id'] not in restore.existing('container') and old_volume not in restore.existing('volume')
            retirement = output/'retirement.json'
            restore.write(retirement, {'schemaVersion': 1, 'kind': 'airbob-experiment-database-retirement', 'state': 'REMOVED',
                'datasetId': manifest['datasetId'], 'containerId': info['Id'], 'containerName': old_name, 'volumeName': old_volume,
                'mysqlServerUuid': original_uuid, 'preservedDumpSha256': config['source']['dumpSha256'],
                'preservedFingerprintSha256': config['source']['fingerprintSha256'],
                'deletion': {'containerAbsentObserved': True, 'volumeAbsentObserved': True}})
            observer = 'airbob-b-test-observer-'+suffix
            restore.docker('run', '-d', '--name', observer, '--label', 'airbob.restore.temporary=true', '--memory=128m',
                           redis, 'redis-server', '--save', '', '--appendonly', 'no')
            config['diskObserver'] = {'name': observer, 'id': restore.inspect('container', observer)['Id']}
            config['source'].update(retirementReceipt=str(retirement), retirementReceiptSha256=restore.sha(retirement))
            config['mode'] = 'oci'
            restore.write(config_path, config)
        cli_file = 'restore-growth-b-oci.py' if args.mode == 'retired-oci' else 'restore-growth-b-local.py'
        cli = [sys.executable, str(ROOT/'scripts'/cli_file), '--config', str(config_path)]
        before = db.identity() if args.mode == 'local' else None
        restore.command(cli+['--output', str(output/'preflight')], timeout=600, env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
        if args.mode == 'local':
            assert db.identity() == before, 'Preflight changed MySQL state'
            assert restore.inspect('container', old_name)['State']['Running'] is True
        else:
            assert new_volume not in restore.existing('volume'), 'Preflight created a target volume'
        plan = output/'preflight/preflight.json'
        bad = subprocess.run(cli+['--output', str(output/'rejected-apply'), '--apply', '--preflight', str(plan),
                '--preflight-sha256', '0'*64], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'), timeout=600)
        assert bad.returncode != 0
        if args.mode == 'local':
            assert restore.inspect('container', old_name)['State']['Running'] is True
            assert old_volume in restore.existing('volume')
        else:
            assert new_volume not in restore.existing('volume'), 'Wrong preflight SHA created a target'
        with (output/'apply-console.jsonl').open('wb') as log:
            restore.command(cli+['--output', str(output/'apply'), '--apply', '--preflight', str(plan),
                    '--preflight-sha256', restore.sha(plan)], output=log, timeout=1200,
                    env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
        report = restore.read(output/'apply/restore.json')
        created_ids[new_name] = report['targetContainerId']
        assert report['state'] == 'PREPARED_APP_STOPPED_DATABASE_FROZEN'
        assert report['allRowsAndDdlEqual'] and report['preparation']['passed'] and report['preparation']['readinessVerified']
        assert report['preparation']['accountLogins']['passed'] and report['preparation']['accountLogins']['accounts'] == len(restore.public_account_union(accounts))
        assert report['preparation']['accountLogins']['representatives']['identityRoleAndOwnershipVerified']
        assert info['Id'] not in restore.existing('container') and old_volume not in restore.existing('volume')
        assert restore.Database(created_ids[new_name]).identity()['superReadOnly'] == 1
        states = [item['state'] for item in report['events']]
        removal_state = 'SOURCE_CONTAINER_AND_VOLUME_REMOVED' if args.mode == 'local' else 'PREVIOUS_RETIREMENT_REOBSERVED'
        assert states.index(removal_state) < states.index('NEW_EMPTY_MYSQL_READY')
        bundled_migrations = output/'apply/runtime/backend/src/main/resources/db/migration'
        assert not [path for path in (output/'apply').rglob('*.sql') if not path.is_relative_to(bundled_migrations)]
        assert ('preservedCurrentDump' in report) == (args.mode == 'local')
        result = {'passed': True, 'mode': args.mode, 'mysqlVersion': '8.4.11', 'datasetId': manifest['datasetId'],
                  'tables': len(baseline['tables']), 'reservations': baseline['tables']['reservation']['rows'],
                  'accountLogins': report['preparation']['accountLogins']['accounts'],
                  'currentInventory': report['preparation']['currentInventory'],
                  'ownerSha256BeforeAndAfter': report['preparation']['ownerSha256BeforeAndAfter'],
                  'preflightDidNotChangeDatabase': True, 'wrongPreflightHashRejectedBeforeMutation': True,
                  'oldVolumeRemovedBeforeTargetCreation': True, 'plaintextTemporaryBytes': 0,
                  'existingContainersModified': False,
                  'executionScope': 'local test only; retired-oci exercises the OCI wrapper without remote execution',
                  'sourceRepresentativeNormalLoginVerified': True, 'sourcePreparedStatePreservedBeforeRemoval': True}
        restore.write(output/'result.json', result)
        success = True
        print(json.dumps({key: value for key, value in result.items() if key not in ('currentInventory', 'ownerSha256BeforeAndAfter')}))
    finally:
        credential_error = None
        for path in (source_private, private_accounts):
            if path.exists():
                try:
                    credentials = account_tools.read_private(path)
                    account_tools.update_login_state(path,
                        [row['memberId'] for row in credentials['credentials']], credentials['environment'],
                        usable=False, reason='The isolated replacement test is retiring its temporary source and target databases')
                except BaseException as error:
                    credential_error = error
        # Only names exclusively created by this test, verified against their labels, may be cleaned up.
        for name, volume in [(old_name, old_volume), (new_name, new_volume)]:
            names = set(restore.docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
            if name in names:
                info = restore.inspect('container', name)
                assert info['Config']['Labels']['airbob.dataset.id'] == manifest['datasetId']
                assert all(mount.get('Name') == volume for mount in info['Mounts'] if mount['Destination'] == '/var/lib/mysql')
                restore.docker('rm', '-f', info['Id'])
            if volume in restore.existing('volume'):
                assert restore.inspect('volume', volume)['Labels']['airbob.dataset.id'] == manifest['datasetId']
                restore.docker('volume', 'rm', volume)
        if observer:
            info = restore.inspect('container', observer)
            assert info['Config']['Labels']['airbob.restore.temporary'] == 'true'
            restore.docker('rm', '-f', info['Id'])
        if credential_error is not None:
            raise credential_error
        if success:
            result.update(temporaryAccountHandoffsUnavailable=True, ownedResourcesRemovedAfterVerification=True)
            restore.write(output/'result.json', result)
        if not success:
            print('Isolated restore integration failed; private evidence retained at ' + str(output), file=sys.stderr)


if __name__ == '__main__':
    main()
