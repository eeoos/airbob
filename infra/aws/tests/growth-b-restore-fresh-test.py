#!/usr/bin/env python3
"""Qualify a fresh local install from a sealed small dump, retaining DB until checked.

The pre-existing engine must have no containers. Only this test's claimed
observer, new MySQL container, and exact data volume may be removed. Existing
anonymous Redis volumes are neither mounted nor removed. No cloud calls.
"""
import argparse
import contextlib
import importlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import sys
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_search as search

spec = importlib.util.spec_from_file_location('fresh_integration_restore', ROOT / 'scripts/restore-growth-b-local.py')
restore = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restore)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def invoke(arguments, *, before_preparation=None):
    previous = sys.argv
    try:
        sys.argv = [str(ROOT / 'scripts/restore-growth-b-local.py'), *arguments]
        return restore.main(before_preparation=before_preparation)
    finally:
        sys.argv = previous


def capture_search_baseline(config, output, runtime, info, db):
    """Trusted internal hook; the normal CLI accepts no external callback command."""
    restore.require(db.identity()['superReadOnly'] == 1, 'Baseline hook must start while the DB is frozen')
    environment = restore.connection_environment(info)
    variable = 'AIRBOB_FRESH_TEST_BASELINE_DB_PASSWORD'
    source = {'releaseDirectory': config['release'], 'datasetId': config['datasetId'],
              'consumerManifestSha256': config['consumerManifestSha256'], 'checksSha256': config['checksumsSha256'],
              'migrationDirectory': str(Path(config['backendRoot']) / 'src/main/resources/db/migration'),
              'appJar': config['appJar'], 'allowSmallQualification': config.get('allowSmall', False),
              'sourceTimeoutSeconds': config.get('operationTimeoutSeconds', 7200),
              'mysql': {'jdbcUrl': environment['AIRBOB_ETL_DB_URL'], 'username': 'root',
                        'passwordEnvironment': variable, 'expectedServerUuid': db.identity()['uuid']}}
    previous = os.environ.get(variable)
    try:
        os.environ[variable] = environment['AIRBOB_ETL_DB_PASSWORD']
        search.capture_baseline(source, output / 'search-baseline')
    finally:
        if previous is None:
            os.environ.pop(variable, None)
        else:
            os.environ[variable] = previous
    return {'searchBaselineReceipt': output / 'search-baseline/baseline-receipt.json',
            'searchBaselineFingerprint': output / 'search-baseline/mysql-baseline-fingerprint.json'}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('release', 'app-jar', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('consumer-manifest-sha256', 'checks-sha256', 'mysql-image', 'redis-image'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    restore.require(all(re.fullmatch(r'sha256:[0-9a-f]{64}', image) for image in (args.mysql_image, args.redis_image)),
                    'The fresh integration requires exact local MySQL and Redis image IDs')
    release, output, app_jar = args.release.resolve(), args.output.resolve(), args.app_jar.resolve()
    restore.require(not output.exists() and not output.is_relative_to(release), 'Fresh private test output required')
    restore.require(restore.sha(release / 'consumer-manifest.json') == args.consumer_manifest_sha256 and
                    restore.sha(release / 'SHA256SUMS.json') == args.checks_sha256, 'Reviewed small release anchors differ')
    manifest = restore.read(release / 'consumer-manifest.json')
    restore.require(manifest['datasetScale'] == 'small-qualification', 'Only the bounded small release is allowed')
    _, _, baseline = restore.validate(release, manifest['datasetId'], ROOT / 'src/main/resources/db/migration',
                                      allow_small=True, expected_app_sha=restore.sha(app_jar))
    restore.require_local_engine()
    restore.require(not restore.existing('container'), 'Stop other test containers before fresh integration')
    output.mkdir(mode=0o700)
    runtime = restore.extract_runtime(release, output / 'runtime')
    before_volumes = restore.existing('volume')
    suffix, claim = uuid.uuid4().hex[:12], secrets.token_hex(16)
    observer = 'airbob-b-fresh-observer-' + suffix
    target, volume = 'airbob-b-fresh-mysql-' + suffix, 'airbob-b-fresh-data-' + suffix
    observer_id = None; target_id = None; target_claim = None; success = False
    account_tools = None; private_accounts = None
    try:
        # Observer creation is deliberately separate from read-only preflight.
        with restore.acquire_fresh_run_lock(runtime):
            restore.require(not restore.existing('container'), 'Another run started before observer creation')
            observer_id = restore.docker('run', '-d', '--name', observer, '--read-only', '--network', 'none',
                '--memory=32m', '--tmpfs', '/data:rw,noexec,nosuid,size=1m', '--entrypoint', '/bin/sh',
                '--label', 'airbob.restore.temporary=true', '--label', 'airbob.restore.observer=' + restore.FRESH_OPERATION,
                '--label', 'airbob.restore.claim=' + claim, args.redis_image, *restore.OBSERVER_COMMAND).decode().strip()
        accounts = restore.read(release / 'accounts.json')
        private_accounts = output / 'account-credentials/accounts.private.json'
        config = {'schemaVersion': 1, 'mode': 'local', 'operation': restore.FRESH_OPERATION,
                  'datasetId': manifest['datasetId'], 'release': str(release),
                  'consumerManifestSha256': args.consumer_manifest_sha256, 'checksumsSha256': args.checks_sha256,
                  'backendRoot': str(ROOT), 'appJar': str(app_jar), 'privateAccounts': str(private_accounts), 'allowSmall': True,
                  'diskObserver': {'name': observer, 'id': observer_id},
                  'target': {'container': target, 'volume': volume, 'image': args.mysql_image, 'redisImage': args.redis_image,
                             'mysqlPort': free_port(), 'memoryMiB': 2048, 'bufferPoolMiB': 512},
                  'capacity': {'reserveHostBytes': 1024**3, 'reserveDockerBytes': 1024**3,
                               'requiredDatabaseBytes': 1024**3, 'maxBackupBytes': 0},
                  'operationTimeoutSeconds': 600, 'appStartupTimeoutSeconds': 180}
        sys.path.insert(0, str(runtime / 'tools'))
        account_tools = importlib.import_module('growth_accounts')
        account_tools.create_private_credentials(accounts, private_accounts.parent, restore.account_environment(config))
        config_path = output / 'config.json'; restore.write(config_path, config)
        containers_before = restore.existing('container'); volumes_before = restore.existing('volume')
        with (output / 'preflight-console.jsonl').open('w') as console, contextlib.redirect_stdout(console):
            invoke(['--config', str(config_path), '--output', str(output / 'preflight')])
        restore.require(restore.existing('container') == containers_before and restore.existing('volume') == volumes_before,
                        'Read-only preflight changed Docker resources')
        plan = output / 'preflight/preflight.json'
        rejected = False
        try:
            invoke(['--config', str(config_path), '--output', str(output / 'wrong-plan'), '--apply',
                    '--preflight', str(plan), '--preflight-sha256', '0' * 64])
        except ValueError:
            rejected = True
        restore.require(rejected and restore.existing('container') == containers_before and
                        restore.existing('volume') == volumes_before, 'Wrong plan hash reached resource creation')
        with (output / 'apply-console.jsonl').open('w') as console, contextlib.redirect_stdout(console):
            invoke(['--config', str(config_path), '--output', str(output / 'apply'), '--apply',
                    '--preflight', str(plan), '--preflight-sha256', restore.sha(plan)],
                   before_preparation=capture_search_baseline)
        report = restore.read(output / 'apply/restore.json')
        restore.require(report['preparation']['accountLogins']['accounts'] == len(restore.public_account_union(accounts))
                        and report['preparation']['accountLogins']['representatives']['identityRoleAndOwnershipVerified'] is True,
                        'Representative and qualification login coverage is incomplete')
        target_id = report['targetContainerId']
        target_claim = restore.inspect('volume', volume)['Labels']['airbob.restore.claim']
        restore.require(report['state'] == 'PREPARED_APP_STOPPED_DATABASE_FROZEN' and
                        report['allRowsAndDdlEqual'] and report['fingerprint'] == baseline and
                        report['preparation']['passed'] and report['preparation']['readinessVerified'] and
                        report['preparation']['accountLogins']['passed'], 'Fresh target failed its restored/prepared contract')
        restore.require(restore.existing('container') == {observer_id, target_id} and
                        restore.inspect('container', target_id)['State']['Running'] is True and
                        restore.Database(target_id).identity()['superReadOnly'] == 1, 'Prepared target did not survive CLI exit frozen')
        for binding in report['beforePreparationArtifacts'].values():
            restore.require(restore.sha(output / 'apply' / binding['file']) == binding['sha256'], 'Baseline callback evidence changed')
        captured = restore.read(output / 'apply/search-baseline/baseline-receipt.json')
        restore.require(captured['verifiedAllColumnsAndDdl'] and captured['mysql']['serverUuid'] == report['mysqlServerUuid'],
                        'Search baseline was not captured on the real fresh target before preparation')
        result = {'state': 'SMALL_FRESH_INSTALL_AND_BASELINE_VERIFIED', 'datasetId': manifest['datasetId'],
                  'finalScaleSelected': False, 'cloudExecution': False,
                  'restoreScriptSha256': restore.sha(ROOT / 'scripts/restore-growth-b-local.py'),
                  'testScriptSha256': restore.sha(Path(__file__)), 'preflightSha256': restore.sha(plan),
                  'restoreReceiptSha256': restore.sha(output / 'apply/restore.json'),
                  'preflightDidNotCreateOrRemoveResources': True, 'wrongPlanRejectedBeforeCreation': True,
                  'noOldSourceOrRetirementClaim': 'source' not in report,
                  'allRowsAndDdlEqual': True, 'tables': len(baseline['tables']),
                  'accountLogins': report['preparation']['accountLogins']['accounts'],
                  'currentInventory': report['preparation']['currentInventory'],
                  'ownerSha256BeforeAndAfter': report['preparation']['ownerSha256BeforeAndAfter'],
                  'realReadFencedBaselineCapturedBeforePreparation': True,
                  'beforePreparationArtifacts': report['beforePreparationArtifacts'],
                  'targetSurvivedCliExitFrozen': True, 'maximumConcurrentMysqlContainers': 1,
                  'plaintextTemporaryDumpBytes': 0}
        restore.write(output / 'result.json', result)
        success = True
    finally:
        credential_error = None
        if account_tools is not None and private_accounts is not None and private_accounts.exists():
            try:
                credentials = account_tools.read_private(private_accounts)
                account_tools.update_login_state(private_accounts,
                    [row['memberId'] for row in credentials['credentials']], credentials['environment'],
                    usable=False, reason='The isolated fresh-install test is retiring its temporary target database')
            except BaseException as error:
                credential_error = error
        # A failed CLI retains its new DB; the harness only cleans its own recorded ID/claim.
        receipt_path = output / 'apply/restore.json'
        if target_id is None and receipt_path.exists():
            target_id = restore.read(receipt_path).get('targetContainerId')
            if target_id is not None:
                target_claim = restore.inspect('volume', volume)['Labels']['airbob.restore.claim']
        if target_id is not None:
            info, data = restore.inspect('container', target_id), restore.inspect('volume', volume)
            mounts = [item for item in info['Mounts'] if item['Destination'] == '/var/lib/mysql']
            restore.require(info['Id'] == target_id and info['Name'] == '/' + target and
                            info['Config']['Labels']['airbob.dataset.id'] == manifest['datasetId'] and
                            len(mounts) == 1 and mounts[0].get('Name') == volume and
                            data['Labels']['airbob.dataset.id'] == manifest['datasetId'] and
                            data['Labels']['airbob.restore.claim'] == target_claim,
                            'Test target ownership changed; cleanup refused')
            restore.docker('rm', '-f', target_id); restore.docker('volume', 'rm', volume)
        if observer_id is not None:
            info = restore.inspect('container', observer_id)
            restore.require(info['Id'] == observer_id and info['Name'] == '/' + observer and
                            info['Config']['Labels']['airbob.restore.claim'] == claim, 'Observer ownership changed')
            restore.docker('rm', '-fv', observer_id)
        restore.require(before_volumes <= restore.existing('volume'), 'A pre-existing volume disappeared')
        if credential_error is not None:
            raise credential_error
        if success:
            restore.require(not restore.existing('container') and restore.existing('volume') == before_volumes,
                            'Test resource cleanup incomplete or the reviewed volume inventory changed')
            result.update(ownedResourcesRemovedAfterVerification=True, preExistingVolumesPreserved=True,
                          temporaryAccountHandoffsUnavailable=True)
            restore.write(output / 'result.json', result)
            print(json.dumps({'state': result['state'], 'tables': result['tables'], 'accountLogins': result['accountLogins']}))


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        print('Fresh integration failed (' + type(error).__name__ + '); inspect its private output.', file=sys.stderr)
        raise SystemExit(1)
