#!/usr/bin/env python3
"""Explicit B data-only admission and host preparation. Never publishes a deployment.

The public preparation wrapper lives beside, not inside, the sealed release.
Its reviewed SHA and exact S3 VersionId are controller inputs. Executable helper
hashes must match the reviewed controller tree; dataset tools come only from the
authenticated envelope's preparation archive. Private passwords are generated
on the target host and never leave that host.
"""
import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import platform
import re
import shutil
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import time
import zipfile

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
BUCKET = 'airbob-performance-lab-dataset-' + ACCOUNT
KIND = 'global-growth-b-aws-data-only-preparation'
TOOLS = ('growth_b_prepare.py', 'growth_b_aws_restore.py', 'growth_b_aws_contract.py',
         'growth_b_contract.py', 'growth_b_runtime.py')
GIB = 1024**3
FILES = {'envelope': 'envelope.json', 'publicationReceipt': 'publication-receipt.json',
    'appJar': 'app.jar', 'consumerTools': 'consumer-tools.tar.gz',
    'toolchain': 'toolchain.tar.gz', 'toolchainManifest': 'toolchain.json',
    'rdsCaBundle': 'rds-ca.pem', 'binlogBasis': 'binlog-budget.json'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(path):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), 'A regular non-symlink input is required')
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def read(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, 'Duplicate JSON key')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def write(path, value):
    with os.fdopen(os.open(path, os.O_CREAT | os.O_WRONLY | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0), 0o600), 'w') as stream:
        json.dump(value, stream, indent=2); stream.write('\n')


def source_hashes(directory):
    return {name: sha(Path(directory) / name) for name in TOOLS}


def digest(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def object_reference(value, key):
    require(isinstance(value, dict) and set(value) == {'key', 'versionId', 'sha256', 'bytes'}
            and value['key'] == key and digest(value['sha256'])
            and isinstance(value['versionId'], str) and value['versionId'] not in ('', 'null', 'None')
            and len(value['versionId']) <= 1024 and re.fullmatch(r'[A-Za-z0-9._~+/=-]+', value['versionId'])
            and type(value['bytes']) is int and 0 < value['bytes'] <= 20 * GIB,
            'Invalid exact-version preparation object reference')


def validate_manifest(value, dataset_id, expected_tools):
    require(set(value) == {'schemaVersion', 'kind', 'datasetId', 'account', 'region', 'scope', 'mysql',
            'files', 'toolSources', 'toolchain', 'storage', 'binlogAdditionalReserveBytes'},
            'Unexpected preparation fields; inline secrets and legacy envelopes are forbidden')
    require(value['schemaVersion'] == 1 and value['kind'] == KIND and value['datasetId'] == dataset_id
            and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', dataset_id)
            and value['account'] == ACCOUNT and value['region'] == REGION
            and value['scope'] in {'small-rds-rehearsal', 'final-b-rds'}
            and value['mysql'] == {'version': '8.4.11', 'flywayVersion': 28}, 'Explicit B/V28 data-only input is required')
    names = dict(FILES)
    if value['scope'] == 'final-b-rds':
        names['smallRdsReceipt'] = 'small-rds-receipt.json'
    require(set(value['files']) == set(names), 'Full B needs its current small RDS receipt; small has no override receipt')
    for name, filename in names.items():
        object_reference(value['files'][name], f'datasets/{dataset_id}/aws-preparation/{filename}')
    require(set(expected_tools) == set(TOOLS) and all(digest(item) for item in expected_tools.values())
            and value['toolSources'] == expected_tools, 'Preparation helpers differ from the reviewed controller tree')
    runtime = value['toolchain']
    require(set(runtime) == {'system', 'architecture', 'pythonVersion', 'javaVersion', 'mysqlVersion', 'awsCliVersion', 'unpackedBytes'}
            and runtime['system'] == 'Linux' and runtime['architecture'] == 'x86_64'
            and re.fullmatch(r'3\.(1[2-9]|[2-9][0-9])\.[0-9]+', runtime['pythonVersion'])
            and re.fullmatch(r'21\.0\.(1[2-9]|[2-9][0-9])(?:\.[0-9]+)?', runtime['javaVersion'])
            and runtime['mysqlVersion'] == '8.4.11' and runtime['awsCliVersion'] == '2.34.64'
            and type(runtime['unpackedBytes']) is int and 0 < runtime['unpackedBytes'] <= 4 * GIB,
            'A reviewed Linux Python 3.12+/JDK 21.0.12+/MySQL 8.4/AWS CLI toolchain is required')
    storage = value['storage']
    require(set(storage) == {'rdsAllocatedGiB', 'dataHostRootGiB', 'minimumStagingFreeBytes'}
            and storage['rdsAllocatedGiB'] == 100 and storage['dataHostRootGiB'] == 20
            and type(storage['minimumStagingFreeBytes']) is int and 4 * GIB <= storage['minimumStagingFreeBytes'] < 20 * GIB,
            'Preparation must remain within the closed 100 GiB RDS/20 GiB host shape')
    require(type(value['binlogAdditionalReserveBytes']) is int and 0 < value['binlogAdditionalReserveBytes'] < 100 * GIB,
            'The existing backup-enabled RDS needs an explicit reviewed binlog reserve')
    return value


def validate_envelope_metadata(manifest, envelope):
    require(envelope.get('schemaVersion') == 1 and envelope.get('kind') == 'global-growth-b-aws-restore'
            and envelope.get('datasetId') == manifest['datasetId'] and envelope.get('account') == ACCOUNT
            and envelope.get('region') == REGION and envelope.get('bucket') == BUCKET
            and envelope.get('mysql') == {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
            'Preparation envelope must identify B/V28/MySQL 8.4.11')
    final = manifest['scope'] == 'final-b-rds'
    require(envelope.get('finalScaleSelected') is final and envelope.get('awsExecutionAllowed') is final
            and (final or envelope.get('smallRehearsalEligible') is True), 'Pilot, legacy, or unqualified scales are not AWS preparation inputs')
    require(envelope['appJarSha256'] == manifest['files']['appJar']['sha256']
            and envelope['publicationReceiptSha256'] == manifest['files']['publicationReceipt']['sha256'],
            'Staged JAR or publication receipt differs from the envelope')
    require(isinstance(envelope.get('objects'), dict) and 20 <= len(envelope['objects']) <= 96,
            'Missing exact sealed release object inventory')
    for name, reference in envelope['objects'].items():
        require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]+', name), 'Unsafe sealed release filename')
        object_reference(reference, f'datasets/{manifest["datasetId"]}/{name}')
    storage = envelope['storage']
    require(storage['requiredRdsFreeAfterRemovalBytes'] + manifest['binlogAdditionalReserveBytes'] <= 100 * GIB,
            'Measured database plus binlog reserve exceeds the existing 100 GiB envelope')
    staged = sum(item['bytes'] for item in envelope['objects'].values()) + sum(item['bytes'] for item in manifest['files'].values())
    # Bootstrap, standalone preflight and execute retain three verified runtime
    # extractions. The envelope's reserve already includes one extraction.
    required = staged + manifest['toolchain']['unpackedBytes'] + storage['requiredAdditionalDataHostFreeBytes'] + 2 * storage['runtimeExtractionBytes']
    require(required <= manifest['storage']['minimumStagingFreeBytes'], 'Staging budget omits release/toolchain/runtime bytes')
    return {'state': 'OFFLINE_B_PREPARATION_INPUTS_VALIDATED', 'scope': manifest['scope'],
            'requiredStagingFreeBytes': required, 'rdsAllocatedGiB': 100, 'deploymentReady': False}


def run(argv, *, env=None, input=None, timeout=120):
    """Capture privately; never propagate raw child diagnostics or secret SQL."""
    process = subprocess.Popen(argv, env=env, stdin=subprocess.PIPE if input is not None else subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    try:
        output, _ = process.communicate(input=input.encode() if isinstance(input, str) else input, timeout=timeout)
        require(process.returncode == 0, 'Bounded preparation subprocess failed')
        return output
    finally:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGINT)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL); process.wait()


def fetch(reference, destination, aws):
    destination = Path(destination)
    require(not destination.exists(), 'Staging destination already exists')
    response = json.loads(run([aws, '--region', REGION, '--no-cli-pager', 's3api', 'get-object',
        '--bucket', BUCKET, '--key', reference['key'], '--version-id', reference['versionId'], str(destination)], timeout=1800))
    destination.chmod(0o600)
    require(response.get('VersionId') == reference['versionId'] and destination.stat().st_size == reference['bytes']
            and sha(destination) == reference['sha256'], 'Pinned preparation object bytes/version differ')


def safe_extract(archive_path, destination, *, maximum_bytes, expected_names=None):
    destination = Path(destination); destination.mkdir(mode=0o700)
    with tarfile.open(archive_path) as archive:
        members = archive.getmembers()
        names = [member.name for member in members]
        require(len(names) == len(set(names)) and 0 < len(names) <= 50000
                and sum(member.size for member in members) <= maximum_bytes,
                'Archive inventory or expanded size exceeds its bound')
        if expected_names is not None:
            require(set(names) == set(expected_names), 'Consumer helper archive inventory differs')
        for member in members:
            path = PurePosixPath(member.name)
            require(not path.is_absolute() and '..' not in path.parts and (member.isfile() or member.isdir())
                    and re.fullmatch(r'[A-Za-z0-9_./+-]+', member.name), 'Unsafe preparation archive member')
        archive.extractall(destination, filter='data')


def load_sealed_accounts(release, runtime):
    path = runtime / 'tools/growth_accounts.py'
    source = path.read_bytes()
    require(hashlib.sha256(source).hexdigest() == read(release / 'tool-sources.json')['growth_accounts.py'],
            'Account generator differs from the sealed preparation runtime')
    spec = importlib.util.spec_from_file_location('_airbob_b_host_accounts', path)
    module = importlib.util.module_from_spec(spec)
    exec(compile(source, str(path), 'exec'), module.__dict__)
    return module


def qualify_toolchain(root, manifest, expected):
    root = Path(root).resolve()
    require(platform.system() == 'Linux' and platform.machine() == 'x86_64', 'The reviewed preparation host must be Linux x86_64')
    require(set(manifest) == {'schemaVersion', 'files'} and manifest['schemaVersion'] == 1
            and isinstance(manifest['files'], dict) and manifest['files'], 'Missing toolchain file inventory')
    for name, metadata in manifest['files'].items():
        require(re.fullmatch(r'[A-Za-z0-9_./+-]+', name) and '..' not in PurePosixPath(name).parts
                and not name.startswith('/') and set(metadata) == {'sha256', 'bytes'}
                and sha(root / name) == metadata['sha256'] and (root / name).stat().st_size == metadata['bytes'],
                'Toolchain file differs from the reviewed bytes')
    actual = {str(path.relative_to(root)) for path in root.rglob('*') if path.is_file()}
    require(actual == set(manifest['files']), 'Toolchain inventory contains unreviewed files')
    required = {'python/bin/python3', 'jdk/bin/java', 'jdk/bin/javac', 'jdk/bin/keytool', 'mysql/bin/mysql'}
    require(required <= actual and all(os.access(root / name, os.X_OK) for name in required), 'Missing executable JDK/Python/MySQL tools')
    require(Path(sys.executable).resolve() == (root / 'python/bin/python3').resolve()
            and platform.python_version() == expected['pythonVersion'], 'Bootstrap is not using its verified Python')
    environment = {key: value for key, value in os.environ.items() if key not in
        {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH',
         'PYTHONPATH', 'PYTHONHOME', 'AIRBOB_ETL_BENCHMARK_PASSWORD'}}
    environment.update(JAVA_HOME=str(root / 'jdk'), PATH=str(root / 'jdk/bin') + ':' + str(root / 'mysql/bin') + ':' + environment['PATH'])
    run([str(root / 'jdk/bin/java'), '-XshowSettings:properties', '-version'], env=environment)
    # The authenticated timezone gate records and validates Java's full identity;
    # javac pins the advertised patch here without parsing suppressed stderr.
    javac = run([str(root / 'jdk/bin/javac'), '--version'], env=environment).decode().strip()
    require(javac == 'javac ' + expected['javaVersion'], 'JDK patch differs from the staged toolchain')
    mysql = run([str(root / 'mysql/bin/mysql'), '--version'], env=environment).decode()
    require(re.search(r'\bVer 8\.4\.11\b', mysql) and 'MariaDB' not in mysql, 'MySQL 8.4.11 client is required')
    help_text = run([str(root / 'mysql/bin/mysql'), '--no-defaults', '--help'], env=environment).decode()
    require('ssl-mode' in help_text and 'VERIFY_IDENTITY' in help_text, 'MySQL client lacks required TLS identity verification')
    return environment


def prepare_host(context, wrapper, root, aws):
    import growth_b_aws_restore as restore
    import growth_b_aws_contract as contract
    import growth_b_runtime as runtime_gate
    root = Path(root).resolve()
    manifest = validate_manifest(wrapper, context['datasetId'], context['toolSources'])
    require(sha(root / 'aws-preparation.json') == context['manifestSha256'], 'Reviewed preparation wrapper changed')
    require(context['lease']['table'] == 'airbob-performance-lab-orchestration-lease'
            and context['lease']['lockName'] == 'airbob-performance-lab' and context['lease']['runId'] == context['runId']
            and context['lease']['command'] == 'up' and type(context['lease']['fencingToken']) is int
            and context['lease']['fencingToken'] > 0, 'Exact existing controller lease is required')
    for name, digest_value in context['toolSources'].items():
        require(sha(Path(__file__).parent / name) == digest_value, 'Controller-bound helper changed before use')
    toolchain = root / 'toolchain'
    environment = qualify_toolchain(toolchain, read(root / 'toolchain.json'), manifest['toolchain'])
    environment['PATH'] = str(Path(aws).parent) + ':' + environment['PATH']
    require(run([aws, '--version']).decode().split()[0] == 'aws-cli/2.34.64', 'Pinned AWS CLI version differs')
    for name, reference in manifest['files'].items():
        if name not in {'toolchain', 'toolchainManifest', 'consumerTools'}:
            fetch(reference, root / FILES.get(name, 'small-rds-receipt.json'), aws)
    envelope = read(root / 'envelope.json')
    admitted = validate_envelope_metadata(manifest, envelope)
    require(shutil.disk_usage(root).free >= sum(item['bytes'] for item in envelope['objects'].values())
            + envelope['storage']['requiredAdditionalDataHostFreeBytes'] + 2 * envelope['storage']['runtimeExtractionBytes'],
            'Actual host staging space is insufficient')
    release = root / 'release'; release.mkdir(mode=0o700)
    for name, reference in envelope['objects'].items():
        fetch(reference, release / name, aws)
    migrations = root / 'migrations'; migrations.mkdir(mode=0o700)
    with zipfile.ZipFile(root / 'app.jar') as archive:
        for item in archive.infolist():
            prefix = 'BOOT-INF/classes/db/migration/'
            if item.filename.startswith(prefix) and re.fullmatch(r'V[0-9]+__[A-Za-z0-9_.-]+\.sql', item.filename[len(prefix):]):
                target = migrations / item.filename[len(prefix):]
                require(not target.exists(), 'Duplicate application migration')
                target.write_bytes(archive.read(item)); target.chmod(0o600)
    validated = contract.validate_envelope(root / 'envelope.json', manifest['files']['envelope']['sha256'],
        release, migrations, root / 'publication-receipt.json', root / 'app.jar', allow_small=manifest['scope'] == 'small-rds-rehearsal')
    runtime = restore.extract_runtime(release, root / 'bootstrap-runtime')
    qualification = runtime_gate.qualify_runtime(release, runtime, root / 'bootstrap-host-runtime.json',
        expected_checks={name: item['sha256'] for name, item in validated['objects'].items()}, java_home=toolchain / 'jdk')
    config = {'schemaVersion': 1, 'mode': 'aws-global-b', 'release': str(release), 'migrationDirectory': str(migrations),
        'publicationReceipt': str(root / 'publication-receipt.json'), 'envelope': str(root / 'envelope.json'),
        'envelopeSha256': manifest['files']['envelope']['sha256'], 'appJar': str(root / 'app.jar'),
        'privateAccounts': str(root / 'private/accounts.private.json'), 'writerAsgNames': [],
        'operationTimeoutSeconds': 14400, 'appStartupTimeoutSeconds': 14400,
        'redisImage': context['redisImage'], 'replacementPolicy': 'empty-only', 'lease': context['lease'],
        'rds': context['rds'] | {'caBundle': str(root / 'rds-ca.pem'), 'caBundleSha256': manifest['files']['rdsCaBundle']['sha256']},
        'binlogBudget': {'additionalReserveBytes': manifest['binlogAdditionalReserveBytes'],
            'basisFile': str(root / 'binlog-budget.json'), 'basisSha256': manifest['files']['binlogBasis']['sha256']}}
    if manifest['scope'] == 'small-rds-rehearsal':
        config['allowSmallRehearsal'] = True
    else:
        config['smallRdsReceipt'] = {'path': str(root / 'small-rds-receipt.json'), 'sha256': manifest['files']['smallRdsReceipt']['sha256']}
        restore.validate_rehearsal(config, validated)
    with runtime_gate.activated_runtime(qualification):
        # This is the first DB connection, after full release/runtime validation.
        client = restore.Aws(); guard = restore.Lease(client, context['lease']); guard(force=True)
        live = restore.live_rds(client, config)
        connection_secrets = restore.private_directory(root / '.connection')
        try:
            db, _ = restore.connection(config, client, live, connection_secrets, guard)
            server_uuid = db.scalar('SELECT @@server_uuid', False)
            require(re.fullmatch(r'[0-9a-f-]{36}', server_uuid), 'RDS returned no canonical server UUID')
            config['rds']['serverUuid'] = server_uuid
        finally:
            shutil.rmtree(connection_secrets)
        accounts = load_sealed_accounts(release, runtime)
        accounts.create_private_credentials(read(release / 'accounts.json'), root / 'private', restore.credential_environment(config))
        restore.validate_inputs(config)
        write(root / 'restore-config.json', config)
        runner = Path(__file__).with_name('growth_b_aws_restore.py')
        base = [sys.executable, str(runner), '--config', str(root / 'restore-config.json')]
        try:
            for phase in ('plan', 'preflight', 'execute'):
                guard(force=True)
                arguments = base + [phase, '--output', str(root / phase)]
                if phase == 'execute':
                    preflight = root / 'preflight/preflight.json'
                    arguments += ['--preflight', str(preflight), '--preflight-sha256', sha(preflight)]
                    if manifest['scope'] == 'small-rds-rehearsal':
                        arguments.append('--allow-small-rehearsal')
                run(arguments, env=runtime_gate.qualified_environment(qualification, environment), timeout=18000)
            receipt = read(root / 'execute/restore-receipt.json')
            expected = 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED' if manifest['scope'] == 'small-rds-rehearsal' else 'DATABASE_INVENTORY_LOGIN_VERIFIED'
            require(receipt['state'] == expected and receipt['deploymentReady'] is False
                    and receipt['preparation']['applicationLeftRunning'] is False, 'Standalone preparation did not complete')
            result = admitted | {'state': expected, 'kind': KIND, 'datasetId': manifest['datasetId'], 'runId': context['runId'],
                'manifestSha256': context['manifestSha256'], 'rdsResourceId': config['rds']['resourceId'], 'serverUuid': server_uuid,
                'restoreReceiptSha256': sha(root / 'execute/restore-receipt.json'), 'privateAccountsHostPath': config['privateAccounts'],
                'privateAccountsUsable': False, 'applicationLeftRunning': False, 'albReady': False, 'kafkaCdcReady': False,
                'preparation': receipt['preparation'], 'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat()}
            write(root / 'preparation-receipt.json', result)
            return result
        finally:
            saved = accounts.read_private(config['privateAccounts'])
            accounts.update_login_state(config['privateAccounts'], [row['memberId'] for row in saved['credentials']],
                restore.credential_environment(config), usable=False, reason='AWS data-only preparation retains no running application')


def validate_offline_inputs(manifest, envelope_path, small_receipt=None):
    require(sha(envelope_path) == manifest['files']['envelope']['sha256'], 'Staged envelope SHA differs')
    envelope = read(envelope_path)
    result = validate_envelope_metadata(manifest, envelope)
    if manifest['scope'] == 'final-b-rds':
        require(small_receipt is not None, 'Full B requires its current small RDS receipt before provisioning')
        import growth_b_aws_restore as restore
        result['smallRdsRehearsal'] = restore.validate_rehearsal({'smallRdsReceipt': {
            'path': str(small_receipt), 'sha256': manifest['files']['smallRdsReceipt']['sha256']}}, envelope)
    else:
        require(small_receipt is None, 'Small preparation cannot accept a prerequisite override')
    return result


def validate_preparation_receipt(manifest, context, receipt, standalone_path, envelope_path):
    """Controller verifies the host's public receipt without connecting to RDS."""
    import growth_b_aws_restore as restore
    envelope = read(envelope_path)
    require(sha(envelope_path) == manifest['files']['envelope']['sha256'], 'Prepared envelope differs')
    expected = 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED' if manifest['scope'] == 'small-rds-rehearsal' else 'DATABASE_INVENTORY_LOGIN_VERIFIED'
    require(receipt.get('kind') == KIND and receipt.get('state') == expected
            and receipt.get('datasetId') == manifest['datasetId'] and receipt.get('runId') == context['runId']
            and receipt.get('manifestSha256') == context['manifestSha256']
            and receipt.get('rdsResourceId') == context['rdsResourceId']
            and receipt.get('restoreReceiptSha256') == sha(standalone_path)
            and all(receipt.get(field) is False for field in ('deploymentReady', 'privateAccountsUsable',
                'applicationLeftRunning', 'albReady', 'kafkaCdcReady')),
            'B host receipt identity or data-only terminal state differs')
    proof = read(standalone_path)
    require(proof.get('kind') == 'global-growth-b-aws-restore-receipt' and proof.get('state') == expected
            and proof.get('datasetId') == manifest['datasetId'] and proof.get('executionScope') == manifest['scope']
            and proof.get('toolIdentity') == restore.tool_identity() and proof.get('appJarSha256') == envelope['appJarSha256']
            and proof.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256']
            and proof.get('rdsResourceId') == context['rdsResourceId']
            and proof.get('beforeDatabase', {}).get('serverUuid') == receipt.get('serverUuid')
            and proof.get('preparation') == receipt.get('preparation')
            and proof.get('deploymentReady') is False and proof.get('allRowsAndDdlEqual') is True
            and proof.get('maximumSimultaneousBusinessDatabases') == 1,
            'Standalone RDS receipt is not bound to this preparation')
    prepared = proof['preparation']
    require(prepared.get('passed') is True and prepared.get('applicationLeftRunning') is False
            and prepared.get('serviceCurrentlyAvailable') is False and prepared.get('temporarySessionsRemoved') is True
            and prepared.get('privateCredentials', {}).get('usable') is False
            and prepared.get('accountLogins', {}).get('crossCredentialRejected') is True,
            'Preparation left no authenticated data-only terminal proof')
    if manifest['scope'] == 'small-rds-rehearsal':
        restore.validate_rehearsal({'smallRdsReceipt': {'path': str(standalone_path), 'sha256': sha(standalone_path)}}, envelope)
    return {'state': expected, 'deploymentReady': False, 'privateAccountsUsable': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['validate-manifest', 'validate-inputs', 'validate-receipt', 'host'])
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--sha256', required=True)
    parser.add_argument('--dataset-id', required=True)
    parser.add_argument('--source-directory', type=Path, default=Path(__file__).parent)
    parser.add_argument('--context', type=Path)
    parser.add_argument('--root', type=Path)
    parser.add_argument('--aws')
    parser.add_argument('--envelope', type=Path)
    parser.add_argument('--small-receipt', type=Path)
    parser.add_argument('--receipt', type=Path)
    parser.add_argument('--standalone-receipt', type=Path)
    args = parser.parse_args()
    require(digest(args.sha256) and sha(args.manifest) == args.sha256, 'Preparation manifest trust anchor differs')
    manifest = validate_manifest(read(args.manifest), args.dataset_id, source_hashes(args.source_directory))
    if args.mode == 'validate-manifest':
        print(json.dumps({'state': 'OFFLINE_B_PREPARATION_MANIFEST_VALIDATED', 'scope': manifest['scope'], 'deploymentReady': False}))
        return
    if args.mode == 'validate-inputs':
        print(json.dumps(validate_offline_inputs(manifest, args.envelope, args.small_receipt)))
        return
    if args.mode == 'validate-receipt':
        print(json.dumps(validate_preparation_receipt(manifest, read(args.context), read(args.receipt),
            args.standalone_receipt, args.envelope)))
        return
    signal.signal(signal.SIGINT, signal.default_int_handler)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    signal.signal(signal.SIGHUP, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        result = prepare_host(read(args.context), manifest, args.root, args.aws)
        print(json.dumps({key: result[key] for key in ('state', 'runId', 'datasetId', 'deploymentReady', 'privateAccountsHostPath')}))
    except BaseException as error:
        if args.root and args.root.is_dir() and not (args.root / 'preparation-failure.json').exists():
            write(args.root / 'preparation-failure.json', {'state': 'FAILED', 'errorType': type(error).__name__, 'deploymentReady': False})
        raise SystemExit('B data-only preparation failed; no credentials or raw child errors were recorded.') from None


if __name__ == '__main__':
    main()
