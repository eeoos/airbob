#!/usr/bin/env python3
"""Restore a reviewed Global B envelope on an existing, fenced private RDS.

Default ``plan`` is offline. ``preflight`` is read-only. Only ``execute`` changes
the named airbobdb. Final B needs a successful same-tool small RDS receipt;
qualified small rehearsal needs both reviewed config and explicit CLI opt-in.
No Terraform, S3 publication, DNS, ALB, ASG, or IAM mutations are performed here.
"""
import argparse
import base64
from contextlib import contextmanager
import datetime as dt
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
from urllib.parse import parse_qsl, urlencode

from growth_b_contract import (digest, extract_runtime, integer, public_account_union,
                               read, representative_rows, require, sha, validate_private_accounts, validate_fingerprint)
from growth_b_aws_contract import ACCOUNT, REGION, validate_envelope
from growth_b_runtime import activated_runtime, qualification_binding, qualify_runtime
from growth_b_inventory import prepare_inventory_epochs

SYSTEM_SCHEMAS = {'information_schema', 'performance_schema', 'mysql', 'sys'}


def private_directory(path):
    path = Path(path)
    path.mkdir(parents=True, mode=0o700, exist_ok=True)
    require(not path.is_symlink() and stat.S_IMODE(path.stat().st_mode) == 0o700, 'Private directory must have mode 0700')
    return path


def write(path, value):
    path = Path(path)
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(path, flags, 0o600), 'w') as output:
        os.fchmod(output.fileno(), 0o600)
        json.dump(value, output, indent=2); output.write('\n')


def private_text(path, value):
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, 'O_NOFOLLOW', 0)
    with os.fdopen(os.open(path, flags, 0o600), 'w') as output:
        output.write(value)


def canonical_sha(value):
    # Java Map.of serialization order may differ between JVMs. The reviewed
    # preflight binds semantic full-fingerprint content, not incidental key order.
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def credential_environment(config):
    # Both coordinates are reviewed offline and checked on the live RDS before
    # preparation. A local/pilot credential file cannot silently change targets.
    return 'aws:' + config['rds']['resourceId'] + ':' + config['rds']['serverUuid']


def private_accounts(path, release, *, expected_environment=None):
    return validate_private_accounts(path, release, expected_environment=expected_environment)


def terminate(process):
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            process.wait(); return
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL); process.wait()


def command(argv, *, timeout=120, env=None, input=None, output=None, guard=None):
    """No raw stderr/arguments in exceptions; passwords never enter argv or logs."""
    if guard:
        guard()
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stdin:
        if input is not None:
            stdin.write(input.encode() if isinstance(input, str) else input)
            stdin.seek(0)
        process = subprocess.Popen(argv, stdin=stdin, stdout=output or stdout, stderr=subprocess.DEVNULL,
                                   env=env, start_new_session=True)
        deadline = time.monotonic() + timeout
        try:
            while process.poll() is None:
                remaining = deadline - time.monotonic()
                require(remaining > 0, 'Bounded subprocess deadline expired')
                if output is None:
                    require(stdout.tell() <= 32 * 1024**2, 'Bounded command output exceeded 32 MiB')
                try:
                    process.wait(timeout=min(5, remaining))
                except subprocess.TimeoutExpired:
                    if guard:
                        guard()
            require(process.returncode == 0, 'Subprocess failed: ' + Path(argv[0]).name)
            if guard:
                guard()
            if output:
                return b''
            require(stdout.tell() <= 32 * 1024**2, 'Bounded command output exceeded 32 MiB')
            stdout.seek(0)
            return stdout.read()
        finally:
            terminate(process)


class Aws:
    def call(self, *args):
        environment = dict(os.environ, AWS_MAX_ATTEMPTS='1', AWS_RETRY_MODE='standard', AWS_CLI_AUTO_PROMPT='off')
        raw = command(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '5', '--cli-read-timeout', '30', *args], env=environment, timeout=45)
        return json.loads(raw or '{}')


class Lease:
    """Read existing fencing state; its owning controller must maintain heartbeats."""
    def __init__(self, aws, config):
        self.aws, self.config, self.last = aws, config, 0.0

    def __call__(self, force=False):
        if not force and time.monotonic() - self.last < 15:
            return
        item = self.aws.call('dynamodb', 'get-item', '--table-name', self.config['table'], '--consistent-read',
            '--key', json.dumps({'LockName': {'S': self.config['lockName']}})).get('Item', {})
        for key, field in [('Owner', 'owner'), ('RunId', 'runId'), ('Command', 'command')]:
            require(item.get(key, {}).get('S') == self.config[field], 'AWS orchestration lease identity changed')
        require(item.get('FencingToken', {}).get('N') == str(self.config['fencingToken']), 'AWS fencing token changed')
        now = int(time.time())
        require(all(int(item.get(key, {}).get('N', '0')) > now for key in ('ExpiresAt', 'CommandDeadline')),
                'AWS orchestration lease expired')
        self.last = time.monotonic()


def configuration(path):
    config = read(path)
    required = {'schemaVersion', 'mode', 'release', 'migrationDirectory', 'publicationReceipt', 'envelope',
        'envelopeSha256', 'appJar', 'privateAccounts', 'rds', 'writerAsgNames', 'redisImage', 'replacementPolicy'}
    require(required <= set(config) <= required | {'allowSmallOffline', 'operationTimeoutSeconds',
        'appStartupTimeoutSeconds', 'lease', 'search', 'binlogBudget', 'allowSmallRehearsal', 'smallRdsReceipt',
        'operation', 'resumeReceipt'},
            'Unknown/missing configuration field; inline secrets are forbidden')
    require(config['schemaVersion'] == 1 and config['mode'] == 'aws-global-b', 'B AWS configuration is required')
    for key in ('release', 'migrationDirectory', 'publicationReceipt', 'envelope', 'appJar', 'privateAccounts'):
        require(Path(config[key]).is_absolute(), 'Input path must be absolute')
    rds = config['rds']
    require(set(rds) == {'identifier', 'resourceId', 'endpoint', 'serverUuid', 'masterSecretArn', 'caBundle', 'caBundleSha256'},
            'Explicit RDS instance/resource/server/TLS identity is required')
    run_id = rds['identifier'].removeprefix('airbob-') if isinstance(rds['identifier'], str) else ''
    require(rds['identifier'] == 'airbob-' + run_id
            and re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', run_id)
            and not run_id.endswith('-') and '--' not in run_id
            and re.fullmatch(r'db-[A-Z0-9]+', rds['resourceId'])
            and re.fullmatch(r'[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', rds['endpoint'])
            and re.fullmatch(r'[0-9a-f-]{36}', rds['serverUuid'])
            and rds['masterSecretArn'].startswith(f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:')
            and digest(rds['caBundleSha256']), 'Invalid RDS identity')
    require(Path(rds['caBundle']).is_absolute() and sha(rds['caBundle']) == rds['caBundleSha256'], 'Pinned RDS CA bundle differs')
    require(config['replacementPolicy'] in {'empty-only', 'discard-reviewed-existing'}, 'Explicit replacement policy is required')
    require(config.get('operation', 'replace-database') in {'replace-database', 'resume-preparation'}, 'Unknown B RDS operation')
    if config.get('operation') == 'resume-preparation':
        reference = config.get('resumeReceipt', {})
        require(set(reference) == {'path', 'sha256'} and isinstance(reference['path'], str)
                and Path(reference['path']).is_absolute() and digest(reference['sha256']),
                'Preparation resume needs the exact own prior restore receipt path/SHA')
    else:
        require('resumeReceipt' not in config, 'A resume receipt belongs only to explicit preparation resume')
    require(all(type(config[key]) is bool for key in ('allowSmallOffline', 'allowSmallRehearsal') if key in config),
            'Small qualification options must be booleans')
    # Lab app.tf passes the full run ID; modules/app-asg appends exactly "-app".
    require(isinstance(config['writerAsgNames'], list) and len(config['writerAsgNames']) <= 1
            and all(isinstance(name, str) and name == rds['identifier'] + '-app' for name in config['writerAsgNames']),
            'Writer ASGs must match the exact lab app ASG name')
    require(re.fullmatch(r'[A-Za-z0-9./:_-]+@sha256:[0-9a-f]{64}', config['redisImage']), 'Temporary session Redis image must be digest-pinned')
    for key, default in [('operationTimeoutSeconds', 14400), ('appStartupTimeoutSeconds', 21600)]:
        config.setdefault(key, default)
        require(integer(config[key], 60) and config[key] <= 86400, 'Operation deadline must be bounded')
    if 'lease' in config:
        lease = config['lease']
        require(set(lease) == {'table', 'lockName', 'owner', 'runId', 'command', 'fencingToken'}
                and lease['table'] == 'airbob-performance-lab-orchestration-lease'
                and re.fullmatch(r'[A-Za-z0-9_.:/-]{3,255}', lease['lockName'])
                and re.fullmatch(r'[A-Za-z0-9._:@/-]{3,128}', lease['owner'])
                and lease['runId'] == run_id
                and lease['command'] in {'up', 'measurement'} and integer(lease['fencingToken'], 1), 'Invalid existing orchestration lease')
    if 'search' in config:
        require(sys.version_info >= (3, 12), 'Native search companion commands require Python 3.12+')
        require(set(config['search']) == {'companionDirectory', 'descriptor', 'descriptorSha256', 'config', 'configSha256'}, 'Invalid search companion coordinates')
        require(all(Path(config['search'][key]).is_absolute() for key in ('companionDirectory', 'descriptor', 'config')), 'Search input paths must be absolute')
        require(all(digest(config['search'][key + 'Sha256']) and sha(config['search'][key]) == config['search'][key + 'Sha256']
                    for key in ('config', 'descriptor')), 'Reviewed ES descriptor/config changed')
    if 'binlogBudget' in config:
        budget = config['binlogBudget']
        require(set(budget) == {'additionalReserveBytes', 'basisFile', 'basisSha256'}
                and integer(budget['additionalReserveBytes'], 1) and Path(budget['basisFile']).is_absolute()
                and digest(budget['basisSha256']) and sha(budget['basisFile']) == budget['basisSha256'],
                'Enabled binlogs require a separately reviewed capacity basis')
    if 'smallRdsReceipt' in config:
        receipt = config['smallRdsReceipt']
        require(set(receipt) == {'path', 'sha256'} and Path(receipt['path']).is_absolute() and digest(receipt['sha256']),
                'Final B requires an explicit reviewed small RDS receipt path/SHA')
    return config


def validate_inputs(config):
    envelope = validate_envelope(config['envelope'], config['envelopeSha256'], Path(config['release']),
        Path(config['migrationDirectory']), Path(config['publicationReceipt']), Path(config['appJar']),
        allow_small=config.get('allowSmallOffline') is True or config.get('allowSmallRehearsal') is True)
    private_accounts(config['privateAccounts'], config['release'], expected_environment=credential_environment(config))
    return envelope


def tool_identity():
    scripts = Path(__file__).resolve().parent
    return {name: sha(scripts / name) for name in
            ('growth_b_aws_restore.py', 'growth_b_aws_contract.py', 'growth_b_contract.py', 'growth_b_runtime.py', 'growth_b_inventory.py')}


def target_identity(config):
    return {key: config['rds'][key] for key in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}


def credential_binding(config):
    private = private_accounts(config['privateAccounts'], Path(config['release']),
                               expected_environment=credential_environment(config))
    fields = ('memberId', 'email', 'role', 'group', 'purpose', 'environment', 'password')
    return canonical_sha([{key: row[key] for key in fields} for row in private['credentials']])


def execution_bindings(config):
    return {'targetIdentity': target_identity(config), 'credentialBindingSha256': credential_binding(config),
            'searchCoordinatesSha256': canonical_sha(config.get('search'))}


def validate_preparation_resume(config, envelope, current, db, runtime):
    reference = config.get('resumeReceipt', {})
    require(config.get('operation') == 'resume-preparation' and digest(reference.get('sha256'))
            and sha(reference['path']) == reference['sha256'], 'Reviewed preparation resume receipt changed')
    proof = read(reference['path'])
    scope = 'final-b-rds' if envelope['finalScaleSelected'] else 'small-rds-rehearsal'
    require(proof.get('schemaVersion') == 1 and proof.get('kind') == 'global-growth-b-aws-restore-receipt'
            and proof.get('account') == ACCOUNT and proof.get('region') == REGION
            and proof.get('toolIdentity') == tool_identity() and proof.get('datasetId') == envelope['datasetId']
            and proof.get('envelopeSha256') == config['envelopeSha256']
            and proof.get('appJarSha256') == envelope['appJarSha256']
            and proof.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256']
            and proof.get('mysqlVersion') == '8.4.11' and proof.get('flywayVersion') == 28
            and proof.get('finalScaleSelected') is envelope['finalScaleSelected'] and proof.get('executionScope') == scope
            and proof.get('allRowsAndDdlEqual') is True and digest(proof.get('restoredFingerprintSha256'))
            and proof.get('previousBusinessSchemaAbsent') is True and proof.get('maximumSimultaneousBusinessDatabases') == 1
            and proof.get('awsWritesExecuted') is True
            and all(proof.get(key) == value for key, value in execution_bindings(config).items()),
            'Resume requires this exact target, sealed B, credentials, and successful same-tool baseline')
    before = read(Path(config['release']) / 'before-fingerprint.json')
    require(proof.get('sealedFingerprint') == before, 'Resume lacks the complete original verified baseline')
    require('search' not in config or proof.get('searchVerified') is True,
            'Required search qualification did not finish before preparation resume')
    validate_fingerprint(current, require_sealed=False)
    validate_prepared_changes(before, current)
    sys.path.insert(0, str(runtime / 'tools'))
    inventory = importlib.import_module('growth_inventory')
    inventory.verify_closed_ownership(db)
    owners = owner_fingerprint(db)
    expected = proof.get('baselineOwnerSha256')
    if not digest(expected):
        require(current == before, 'An interrupted owner checkpoint requires an unchanged complete baseline')
        expected = owners
    require(owners == expected, 'Preparation resume changed historical HOLD/OCCUPIED ownership')
    return {'receiptSha256': reference['sha256'], 'baselineOwnerSha256': expected,
            'restoredFingerprintSha256': proof['restoredFingerprintSha256'], 'searchVerified': proof.get('searchVerified', False)}


def execution_scope(config, envelope, *, allow_small_rehearsal=False):
    if envelope['finalScaleSelected'] is True:
        require(envelope['awsExecutionAllowed'] is True and not allow_small_rehearsal
                and config.get('allowSmallRehearsal') is not True, 'Final B cannot use the small rehearsal override')
        return 'final-b-rds'
    require(envelope['finalScaleSelected'] is False and envelope.get('smallRehearsalEligible') is True
            and config.get('allowSmallRehearsal') is True and allow_small_rehearsal is True,
            'Execution requires final B or explicit reviewed qualified-small RDS rehearsal opt-ins')
    return 'small-rds-rehearsal'


def validate_rehearsal(config, envelope):
    """Hash-authenticate the reviewed receipt before accepting its proof claims."""
    reference = config.get('smallRdsReceipt')
    require(reference is not None and sha(reference['path']) == reference['sha256'],
            'A reviewed successful same-tool small RDS receipt is required before final B')
    proof = read(reference['path'])
    require(proof.get('schemaVersion') == 1 and proof.get('kind') == 'global-growth-b-aws-restore-receipt'
            and proof.get('state') == 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED'
            and proof.get('executionScope') == 'small-rds-rehearsal' and proof.get('finalScaleSelected') is False,
            'The prerequisite must be a completed small RDS rehearsal, not an offline plan or final/performance claim')
    require(proof.get('toolIdentity') == tool_identity() and proof.get('appJarSha256') == envelope['appJarSha256']
            and proof.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256'],
            'Small RDS rehearsal used different tools, application, or V28 migrations')
    require(proof.get('account') == ACCOUNT and proof.get('region') == REGION
            and proof.get('mysqlVersion') == '8.4.11' and proof.get('flywayVersion') == 28
            and re.fullmatch(r'global-growth-b-[0-9a-f]{16}', proof.get('datasetId', ''))
            and proof.get('awsWritesExecuted') is True and proof.get('allRowsAndDdlEqual') is True
            and proof.get('previousBusinessSchemaAbsent') is True
            and proof.get('maximumSimultaneousBusinessDatabases') == 1
            and digest(proof.get('restoredFingerprintSha256'))
            and bool(proof.get('beforeDatabase', {}).get('tlsCipher'))
            and proof.get('beforeDatabase', {}).get('version') == '8.4.11'
            and proof.get('deploymentReady') is False, 'Small RDS baseline/TLS/removal proof is incomplete')
    prepared = proof.get('preparation', {})
    login = prepared.get('accountLogins', {})
    require(prepared.get('passed') is True and prepared.get('fullBaselineVerifiedBeforeCredentials') is True
            and prepared.get('readinessVerified') is True and prepared.get('applicationLeftRunning') is False
            and prepared.get('currentInventory', {}).get('everyHorizonContiguous') is True
            and digest(prepared.get('ownerSha256BeforeAndAfter')) and login.get('passed') is True
            and integer(login.get('accounts'), 1) and login.get('baseWorkloads') == 15
            and login.get('identityAndLogoutVerified') is True, 'Small RDS normal-login/current-inventory proof is incomplete')
    representatives = login.get('representatives', {})
    private = prepared.get('privateCredentials', {})
    credential = prepared.get('credentialPreparation', {})
    resource = proof.get('rdsResourceId', '')
    server = proof.get('beforeDatabase', {}).get('serverUuid', '')
    require(isinstance(resource, str) and re.fullmatch(r'db-[A-Z0-9]+', resource)
            and isinstance(server, str) and re.fullmatch(r'[0-9a-f-]{36}', server),
            'Small RDS credential environment identity is missing')
    environment = 'aws:' + resource + ':' + server
    require(representatives.get('passed') is True and representatives.get('accountCount') == 3
            and representatives.get('identityRoleAndOwnershipVerified') is True
            and representatives.get('emailMutationAfterRestore') is False
            and login.get('representativeAccounts') == 3 and login.get('crossCredentialRejected') is True
            and login.get('environment') == environment
            and private.get('schemaVersion') == 2 and private.get('environment') == environment
            and private.get('inputValidated') is True and private.get('individualPasswords') is True
            and private.get('fileMode') == '0600' and private.get('directoryMode') == '0700'
            and private.get('state') == 'NOT_AVAILABLE' and private.get('usable') is False
            and private.get('credentialValuesRecorded') is False
            and credential.get('preparedMembers') == login['accounts']
            and credential.get('onlyColumnChanged') == 'member.password'
            and credential.get('identityRoleAndActiveVerifiedBeforeMutation') is True
            and credential.get('singleTransactionCommittedAfterExactCount') is True
            and credential.get('individualPasswords') is True and credential.get('environment') == environment
            and prepared.get('serviceCurrentlyAvailable') is False and prepared.get('temporarySessionsRemoved') is True,
            'Small RDS representative/private credential proof is incomplete')
    # These are the small dataset's own members; final B selects its own IDs.
    representative_rows(representatives)
    return {'receiptSha256': reference['sha256'], 'datasetId': proof['datasetId'],
            'state': proof['state'], 'sameToolApplicationAndV28Verified': True}


def live_rds(aws, config):
    require(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'Unexpected AWS account')
    rds = config['rds']
    matches = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', rds['identifier'])['DBInstances']
    require(len(matches) == 1, 'RDS identity is ambiguous')
    item = matches[0]
    require(item['DbiResourceId'] == rds['resourceId'] and item['Endpoint']['Address'] == rds['endpoint']
            and item['Endpoint']['Port'] == 3306 and item['Engine'] == 'mysql' and item['EngineVersion'] == '8.4.11'
            and item['PubliclyAccessible'] is False and item['DBInstanceStatus'] == 'available'
            and item.get('MasterUserSecret', {}).get('SecretArn') == rds['masterSecretArn'],
            'RDS engine, private endpoint, resource, or managed-secret identity differs')
    all_asgs = aws.call('autoscaling', 'describe-auto-scaling-groups').get('AutoScalingGroups', [])
    run_asgs = [group for group in all_asgs if group['AutoScalingGroupName'].startswith(rds['identifier'] + '-')]
    require({group['AutoScalingGroupName'] for group in run_asgs} == set(config['writerAsgNames']), 'Run ASG inventory differs')
    require(all(group['DesiredCapacity'] == group['MinSize'] == 0 and not group['Instances'] for group in run_asgs),
            'All run ASGs must be stopped before database preparation')
    return {'identifier': item['DBInstanceIdentifier'], 'resourceId': item['DbiResourceId'], 'engine': item['EngineVersion'],
            'allocatedStorageGiB': item['AllocatedStorage'], 'publiclyAccessible': False,
            'backupRetentionDays': item['BackupRetentionPeriod'],
            'writerAsgsStopped': sorted(config['writerAsgNames']), 'masterUsername': item['MasterUsername']}


def free_storage(aws, identifier, *, not_before=None):
    now = dt.datetime.now(dt.timezone.utc)
    raw = aws.call('cloudwatch', 'get-metric-statistics', '--namespace', 'AWS/RDS', '--metric-name', 'FreeStorageSpace',
        '--dimensions', 'Name=DBInstanceIdentifier,Value=' + identifier, '--period', '60', '--statistics', 'Minimum',
        '--start-time', (now - dt.timedelta(minutes=15)).isoformat(), '--end-time', now.isoformat())
    points = sorted(raw.get('Datapoints', []), key=lambda item: item['Timestamp'])
    require(points, 'Fresh RDS FreeStorageSpace is unavailable')
    latest = points[-1]
    recorded = dt.datetime.fromisoformat(latest['Timestamp'].replace('Z', '+00:00'))
    require(latest.get('Unit') == 'Bytes' and 0 <= (now - recorded).total_seconds() <= 300,
            'RDS free-space measurement is stale')
    if not_before:
        require(recorded >= not_before, 'RDS free-space metric predates database removal')
    require(isinstance(latest.get('Minimum'), (int, float)) and latest['Minimum'] >= 0, 'Invalid RDS free-space metric')
    return {'bytes': int(latest['Minimum']), 'recordedAt': recorded.isoformat()}


def cnf_quote(value):
    require(isinstance(value, str) and not any(char in value for char in '\n\r\x00'), 'Unsupported MySQL option value')
    return '"' + value.replace('\\', '\\\\').replace('"', '\\"') + '"'


def connection(config, aws, live, secret_dir, guard=None):
    secret = json.loads(aws.call('secretsmanager', 'get-secret-value', '--secret-id', config['rds']['masterSecretArn'])['SecretString'])
    require(secret.get('username') == live['masterUsername'] and isinstance(secret.get('password'), str)
            and secret['password'], 'RDS managed secret identity differs')
    host = config['rds']['endpoint']
    ca = Path(config['rds']['caBundle'])
    trust = secret_dir / 'rds-trust.p12'
    blocks = re.findall(r'-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----', ca.read_text())
    require(0 < len(blocks) <= 64, 'RDS certificate bundle is invalid')
    for i, block in enumerate(blocks):
        certificate = secret_dir / f'ca-{i}.pem'
        certificate.write_text(block + '\n'); certificate.chmod(0o600)
        command(['keytool', '-importcert', '-noprompt', '-storetype', 'PKCS12', '-keystore', str(trust),
            '-storepass', 'changeit', '-alias', 'rds-' + str(i), '-file', str(certificate)], guard=guard)
    trust.chmod(0o600)
    defaults = secret_dir / 'mysql.cnf'
    private_text(defaults, '[client]\n' + '\n'.join(key + '=' + cnf_quote(value) for key, value in {
        'host': host, 'port': '3306', 'user': secret['username'], 'password': secret['password'],
        'ssl-mode': 'VERIFY_IDENTITY', 'ssl-ca': str(ca), 'connect-timeout': '10'}.items()) + '\n')
    url = 'jdbc:mysql://' + host + ':3306/airbobdb?' + urlencode({'sslMode': 'VERIFY_IDENTITY',
        'trustCertificateKeyStoreUrl': trust.as_uri(), 'trustCertificateKeyStoreType': 'PKCS12',
        'trustCertificateKeyStorePassword': 'changeit', 'fallbackToSystemTrustStore': 'false',
        'connectionTimeZone': 'UTC', 'forceConnectionTimeZoneToSession': 'true', 'connectTimeout': '10000'})
    environment = dict(os.environ, AIRBOB_ETL_DB_URL=url, AIRBOB_ETL_DB_USER=secret['username'],
        AIRBOB_ETL_DB_PASSWORD=secret['password'], JAVA_OPTS='-Xmx1536m -Duser.timezone=UTC')
    return Database(defaults, config['operationTimeoutSeconds'], guard), environment


class Database:
    SCHEMA = 'airbobdb'
    def __init__(self, defaults, timeout, guard=None):
        self.defaults, self.timeout, self.guard = defaults, timeout, guard

    def command(self, database=True):
        return ['mysql', '--defaults-extra-file=' + str(self.defaults), '--default-character-set=utf8mb4',
                '--batch', '--raw', '--unbuffered'] + ([self.SCHEMA] if database else [])

    def execute(self, sql, database=True):
        # stdin keeps credential UPDATEs out of the process command line.
        return command(self.command(database), input=sql, timeout=self.timeout, guard=self.guard).decode()

    def rows(self, sql, database=True):
        lines = self.execute(sql, database).strip().splitlines()
        if not lines:
            return []
        names = lines[0].split('\t')
        def value(item):
            return None if item == 'NULL' else int(item) if item.isdigit() else item
        return [dict(zip(names, map(value, line.split('\t')))) for line in lines[1:]]

    def scalar(self, sql, database=True):
        return next(iter(self.rows(sql, database)[0].values()))

    @staticmethod
    def literal(value):
        if value is None:
            return 'NULL'
        if type(value) is int:
            return str(value)
        return 'CONVERT(0x' + str(value).encode().hex() + ' USING utf8mb4)'


def database_state(db, config):
    identity = db.rows('SELECT @@version version,@@server_uuid serverUuid,@@read_only readOnly,@@super_read_only superReadOnly', False)[0]
    require(identity == {'version': '8.4.11', 'serverUuid': config['rds']['serverUuid'], 'readOnly': 0, 'superReadOnly': 0},
            'Live MySQL identity/writability differs')
    cipher = db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'", False)
    require(len(cipher) == 1 and bool(cipher[0].get('Value')), 'MySQL TLS session is required')
    schemas = {row['SCHEMA_NAME'] for row in db.rows('SELECT SCHEMA_NAME FROM information_schema.schemata', False)}
    require(schemas <= SYSTEM_SCHEMAS | {'airbobdb'}, 'Another non-system database exists; one business database is required')
    require(db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE DB='airbobdb' AND ID<>CONNECTION_ID()", False) == 0,
            'Existing database clients must be stopped')
    objects = db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='airbobdb'", False)
    other = db.scalar("SELECT (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='airbobdb')+"
        "(SELECT COUNT(*) FROM information_schema.events WHERE event_schema='airbobdb')", False)
    require(other == 0, 'Unexpected database routines/events')
    allocated = db.scalar("SELECT COALESCE(SUM(FILE_SIZE),0) FROM information_schema.innodb_tablespaces WHERE NAME LIKE 'airbobdb/%'", False)
    binlog = db.scalar('SELECT @@log_bin', False)
    retention = None
    if binlog:
        values = db.rows('CALL mysql.rds_show_configuration()', False)
        matched = [row for row in values if row.get('name') == 'binlog retention hours']
        require(len(matched) == 1, 'RDS binlog retention is unavailable')
        retention = matched[0]['value']
    return identity | {'tlsCipher': cipher[0]['Value'], 'businessSchemas': sorted(schemas - SYSTEM_SCHEMAS),
                       'tableObjects': objects, 'allocatedTablespaceBytes': allocated, 'otherClients': 0,
                       'binaryLogging': bool(binlog), 'binlogRetentionHours': retention}


def required_rds_bytes(config, envelope, before):
    additional = 0
    if before['binaryLogging']:
        require('binlogBudget' in config, 'Binary logging is enabled: a separate reviewed import/seed binlog budget is required')
        additional = config['binlogBudget']['additionalReserveBytes']
    return envelope['storage']['requiredRdsFreeAfterRemovalBytes'] + additional


def fingerprint(runtime, release, environment, output, timeout, guard=None):
    environment = dict(environment, AIRBOB_ETL_BACKEND_ROOT=str(runtime / 'backend'))
    command([str(runtime / 'runtime/bin/etl'), '--growth-profile=' + str(release / 'profile.json'),
        '--growth-command=fingerprint', '--growth-output=' + str(output), '--service-schema=airbobdb'],
        env=environment, timeout=timeout, guard=guard)
    return read(output)


def owner_fingerprint(db):
    sql = 'SELECT accommodation_id,stay_date,state,reservation_id,hold_expires_at FROM accommodation_inventory_day WHERE reservation_id IS NOT NULL ORDER BY accommodation_id,stay_date'
    digest_value = hashlib.sha256()
    process = subprocess.Popen(db.command() + ['--quick', '-e', sql], stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.monotonic() + db.timeout
    try:
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while selector.get_map():
                require(time.monotonic() < deadline, 'Owned inventory fingerprint deadline expired')
                if db.guard:
                    db.guard()
                for key, _ in selector.select(timeout=5):
                    chunk = os.read(key.fileobj.fileno(), 1024 * 1024)
                    if chunk:
                        digest_value.update(chunk)
                    else:
                        selector.unregister(key.fileobj)
        require(process.wait(timeout=10) == 0, 'Owned inventory fingerprint failed')
    finally:
        terminate(process)
        process.stdout.close()
    return digest_value.hexdigest()


@contextmanager
def exclusive_database(db):
    process = subprocess.Popen(db.command(False) + ['--skip-column-names', '--skip-reconnect'], stdin=subprocess.PIPE,
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, start_new_session=True)
    previous_guard = db.guard
    last_check = 0.0
    mutex = threading.Lock()
    def query(sql):
        require(process.poll() is None, 'Database fence connection was lost')
        process.stdin.write(sql + '\n'); process.stdin.flush()
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            require(selector.select(timeout=30), 'Database fence check timed out')
            require(process.stdout.readline().strip() == '1', 'Database restore fence was lost or held elsewhere')
    def guarded(force=False):
        nonlocal last_check
        with mutex:
            if previous_guard:
                previous_guard(force=force)
            if force or time.monotonic() - last_check >= 15:
                query("SELECT IF(IS_USED_LOCK('airbob_global_b_restore')=CONNECTION_ID(),1,0);")
                last_check = time.monotonic()
    try:
        query("SELECT GET_LOCK('airbob_global_b_restore',0);")
        last_check = time.monotonic()
        db.guard = guarded
        yield
    finally:
        db.guard = previous_guard
        if process.poll() is None:
            process.stdin.close()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                terminate(process)
        process.stdout.close()
        if not process.stdin.closed:
            process.stdin.close()


def stream_restore(db, dump):
    """Two checked processes; no shell interpolation and no plaintext SQL file."""
    if db.guard:
        db.guard(force=True)
    unpack = subprocess.Popen(['gzip', '-dc', '--', str(dump)], stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL, start_new_session=True)
    mysql = None
    try:
        mysql = subprocess.Popen(db.command(), stdin=unpack.stdout, stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL, start_new_session=True)
        unpack.stdout.close()
        deadline = time.monotonic() + db.timeout
        while mysql.poll() is None:
            require(time.monotonic() < deadline, 'Streaming restore deadline expired')
            try:
                mysql.wait(timeout=5)
            except subprocess.TimeoutExpired:
                if db.guard:
                    db.guard()
        unpack.wait(timeout=10)
        require(mysql.returncode == unpack.returncode == 0, 'Streaming gzip/MySQL restore failed')
        if db.guard:
            db.guard(force=True)
    finally:
        if mysql:
            terminate(mysql)
        terminate(unpack)
        unpack.stdout.close()


@contextmanager
def credential_transaction(db):
    """Check each result before COMMIT; disconnect rolls back incomplete writes."""
    if db.guard:
        db.guard(force=True)
    process = subprocess.Popen(db.command() + ['--skip-column-names', '--skip-reconnect'],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.monotonic() + db.timeout
    def query(sql):
        if db.guard:
            db.guard()
        require(process.poll() is None, 'Credential transaction connection was lost')
        marker = ('airbob_credentials_' + secrets.token_hex(16)).encode()
        process.stdin.write(sql.encode() + b"\nSELECT '" + marker + b"';\n")
        process.stdin.flush()
        pending, lines, received = b'', [], 0
        with selectors.DefaultSelector() as selector:
            selector.register(process.stdout, selectors.EVENT_READ)
            while True:
                remaining = deadline - time.monotonic()
                require(remaining > 0, 'Credential transaction deadline expired')
                if db.guard:
                    db.guard()
                for key, _ in selector.select(timeout=min(5, remaining)):
                    chunk = os.read(key.fileobj.fileno(), 65536)
                    require(chunk, 'Credential transaction ended before acknowledgement')
                    received += len(chunk)
                    require(received <= 32 * 1024**2, 'Credential transaction output exceeded its bound')
                    pending += chunk
                    while b'\n' in pending:
                        line, _, pending = pending.partition(b'\n')
                        if line == marker:
                            require(not pending, 'Unexpected credential transaction output')
                            return lines
                        lines.append(line.decode())
    try:
        query('START TRANSACTION;')
        yield query
        query('COMMIT;')
    finally:
        # The same TLS connection owns the row locks and all password writes.
        # A failed identity/count/lease check never sends COMMIT.
        try:
            process.stdin.close()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                terminate(process)
        finally:
            terminate(process)
            process.stdout.close()


CREDENTIAL_HASH_SOURCE = '''import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
class CredentialHashes {
  public static void main(String[] args) throws Exception {
    try (var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
      for (String line; (line = reader.readLine()) != null;) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 2 || !fields[0].matches("[1-9][0-9]*")) throw new IllegalArgumentException();
        byte[] bytes = Base64.getDecoder().decode(fields[1]);
        if (bytes.length < 16 || bytes.length > 72) throw new IllegalArgumentException();
        String password = new String(bytes, StandardCharsets.UTF_8);
        String hash = org.mindrot.jbcrypt.BCrypt.hashpw(password, org.mindrot.jbcrypt.BCrypt.gensalt());
        System.out.println(fields[0] + "\\t" + hash);
      }
    }
  }
}
'''


def apply_credentials(db, runtime, private, environment, secret_dir, bundle):
    selected = public_account_union(bundle)
    by_id = {item['memberId']: item for item in private['credentials']}
    require(len(by_id) == len(private['credentials']) == len(selected)
            and all(row['memberId'] in by_id and all(by_id[row['memberId']][key] == row[key]
                    for key in ('email', 'role')) for row in selected), 'No exact prepared account inventory')
    expected = [{key: row[key] for key in ('memberId', 'email', 'role', 'status')} for row in selected]
    identifiers = ','.join(str(row['memberId']) for row in selected)
    identity_sql = ('SELECT id memberId,email,role,status FROM member WHERE id IN (' + identifiers + ') ORDER BY id')
    require(db.rows(identity_sql) == expected, 'Restored account identity, role, or ACTIVE status differs')
    source = secret_dir / 'CredentialHashes.java'
    private_text(source, CREDENTIAL_HASH_SOURCE)
    # Framed stdin permits arbitrary UTF-8 passwords without shell/argv or a
    # common-password environment variable. Only BCrypt hashes return in memory.
    payload = ''.join(str(row['memberId']) + '\t' + base64.b64encode(
        by_id[row['memberId']]['password'].encode()).decode() + '\n' for row in selected)
    hash_environment = dict(environment)
    hash_environment.pop('AIRBOB_ETL_BENCHMARK_PASSWORD', None)
    hash_environment.pop('AIRBOB_ETL_DB_PASSWORD', None)
    raw = command(['java', '--class-path', str(runtime / 'runtime/lib/*'), str(source)],
        env=hash_environment, input=payload, timeout=db.timeout, guard=db.guard).decode()
    hashes = {}
    for line in raw.splitlines():
        fields = line.split('\t')
        require(len(fields) == 2 and fields[0].isdigit() and int(fields[0]) not in hashes
                and re.fullmatch(r'\$2a\$[0-9]{2}\$[./A-Za-z0-9]{53}', fields[1]), 'BCrypt helper output is invalid')
        hashes[int(fields[0])] = fields[1]
    require(set(hashes) == set(by_id) and len(set(hashes.values())) == len(selected), 'Individual BCrypt coverage differs')
    with credential_transaction(db) as query:
        locked = query("SELECT JSON_OBJECT('memberId',id,'email',email,'role',role,'status',status) "
                       'FROM member WHERE id IN (' + identifiers + ') ORDER BY id FOR UPDATE;')
        require([json.loads(line) for line in locked] == expected, 'Locked account identity, role, or ACTIVE status differs')
        choices = ' '.join('WHEN ' + str(row['memberId']) + ' THEN ' + db.literal(hashes[row['memberId']]) for row in selected)
        changed = query('UPDATE member SET password=CASE id ' + choices + ' END WHERE id IN (' + identifiers + ');\n'
                        'SELECT ROW_COUNT();')
        require(changed == [str(len(selected))], 'Selected credential update count differs; transaction was not committed')
    return {'preparedMembers': len(selected), 'qualificationMembers': len(bundle['accounts']),
            'representativeMembers': 3, 'onlyColumnChanged': 'member.password',
            'scope': 'sealed qualification and representative account union',
            'identityRoleAndActiveVerifiedBeforeMutation': True, 'individualPasswords': True,
            'singleTransactionCommittedAfterExactCount': True,
            'environment': private['environment'], 'privateCredentialFileValidated': True,
            'fullBaselineVerifiedBeforeCredentials': True}


def validate_prepared_changes(before, after):
    require(set(after['tables']) == set(before['tables']), 'Preparation changed the table inventory')
    for table, info in before['tables'].items():
        require(after['tables'][table]['ddlSha256'] == info['ddlSha256'], 'Preparation changed DDL')
        if table not in {'member', 'accommodation_inventory_day'}:
            require(after['tables'][table] == info, 'Preparation changed a non-live table: ' + table)
    require(after['tables']['member']['domainRowsSha256'] == before['tables']['member']['domainRowsSha256']
            and after['tables']['member']['rows'] == before['tables']['member']['rows'], 'Preparation changed member domain values')


def prepare_service(config, envelope, runtime, environment, db, output, secret_dir, *, expected_owner_sha=None, checkpoint=None):
    sys.path.insert(0, str(runtime / 'tools'))
    accounts = importlib.import_module('growth_accounts')
    inventory = importlib.import_module('growth_inventory')
    runtime_module = importlib.import_module('growth_runtime')
    release = Path(config['release'])
    account_environment = credential_environment(config)
    private_input = Path(config['privateAccounts']).resolve()
    private = private_accounts(config['privateAccounts'], release, expected_environment=account_environment)
    bundle = read(release / 'accounts.json')
    selected_ids = [row['memberId'] for row in public_account_union(bundle)]
    accounts.update_login_state(private_input, selected_ids, account_environment, usable=False,
                               reason='AWS account preparation and normal-login verification are in progress')
    name = 'airbob-b-aws-login-' + secrets.token_hex(6)
    created = False
    try:
        inventory.verify_closed_ownership(db)
        owners = owner_fingerprint(db)
        require(expected_owner_sha is None or owners == expected_owner_sha, 'Preparation changed the verified historical owners')
        if checkpoint is not None:
            checkpoint(owners)
        accounts.verify_representative_accounts(db, bundle['representativeAccounts'])
        prepared = apply_credentials(db, runtime, private, environment, secret_dir, bundle)
        write(output / 'credentials-applied.json', prepared)
        command(['docker', 'run', '-d', '--name', name, '--memory=128m', '--label', 'airbob.global-b.temporary=true',
            '-p', '127.0.0.1::6379', config['redisImage'], 'redis-server', '--save', '', '--appendonly', 'no'], guard=db.guard)
        created = True
        port = int(command(['docker', 'port', name, '6379/tcp'], guard=db.guard).decode().strip().rsplit(':', 1)[1])
        app_env = dict(environment, AIRBOB_GROWTH_CREDENTIALS_FILE=str(private_input),
            AIRBOB_GROWTH_STARTUP_TIMEOUT_SECONDS=str(config['appStartupTimeoutSeconds']))
        app_env.pop('AIRBOB_ETL_BENCHMARK_PASSWORD', None)
        @contextmanager
        def app_factory(attempt):
            app = runtime_module.App(config['appJar'], output, secret_dir, app_env, port,
                label='aws-restored-b-inventory-' + str(attempt),
                settings={'spring.flyway.enabled': False, 'reservation.inventory.startup.enabled': True,
                          'management.endpoint.health.probes.enabled': True,
                          'spring.cloud.aws.region.static': REGION,
                          'spring.cloud.aws.credentials.access-key': 'dummy',
                          'spring.cloud.aws.credentials.secret-key': 'dummy'})
            app.env['SPRING_DATASOURCE_URL'] = environment['AIRBOB_ETL_DB_URL']
            app.env['SPRING_DATASOURCE_USERNAME'] = environment['AIRBOB_ETL_DB_USER']
            app.env.update(AWS_REGION=REGION, AWS_DEFAULT_REGION=REGION,
                           AWS_ACCESS_KEY_ID='dummy', AWS_SECRET_ACCESS_KEY='dummy', AWS_EC2_METADATA_DISABLED='true')
            stopped, failures = threading.Event(), []
            def watchdog():
                while not stopped.wait(5):
                    try:
                        db.guard()
                    except BaseException as error:
                        failures.append(type(error).__name__)
                        if app.process is not None:
                            app.process.terminate()
                        return
            watcher = threading.Thread(target=watchdog, daemon=True); watcher.start()
            try:
                with app:
                    require(not failures, 'AWS lease watchdog stopped the application')
                    yield app
                    require(not failures, 'AWS lease was lost during application qualification')
            finally:
                stopped.set(); watcher.join(timeout=50)
        def qualify(app, attempt):
            if db.guard:
                db.guard(force=True)
            readiness = app.request(app.client(), '/actuator/health/readiness', capture=False)
            require(readiness['response']['status'] == 'UP', 'Application readiness is not UP')
            return accounts.qualify_account_logins(app, db, bundle, output,
                private_input=private_input, environment=account_environment)
        def finalize(attempt):
            after = fingerprint(runtime, release, environment, output / 'prepared-fingerprint.json', db.timeout, db.guard)
            validate_prepared_changes(read(release / 'before-fingerprint.json'), after)
            return after
        # Reuse the exact OCI calendar defense, retaining the AWS lease-aware owner reader.
        inventory_adapter = SimpleNamespace(horizon=inventory.horizon, owned_fingerprint=owner_fingerprint)
        horizon, login, startup, inventory_preparation = prepare_inventory_epochs(db, inventory_adapter, owners,
            app_factory, qualify, finalize, output / 'inventory-preparation.json')
        return {'passed': True, 'fullBaselineVerifiedBeforeCredentials': True, 'currentInventory': horizon,
            'ownerSha256BeforeAndAfter': owners, 'accountLogins': login, 'startupSeconds': startup,
            'credentialPreparation': prepared,
            'privateCredentials': {'schemaVersion': 2, 'environment': account_environment,
                'inputValidated': True, 'fileMode': '0600', 'directoryMode': '0700',
                'individualPasswords': True, 'state': 'NOT_AVAILABLE', 'usable': False,
                'reason': 'Temporary qualification application stopped', 'credentialValuesRecorded': False},
            'readinessVerified': True, 'applicationLeftRunning': False, 'temporarySessionsRemoved': True,
            'serviceCurrentlyAvailable': False, 'inventoryPreparation': inventory_preparation,
            'preparedFingerprintSha256': sha(output / 'prepared-fingerprint.json'),
            'preparationResumed': expected_owner_sha is not None}
    finally:
        try:
            accounts.update_login_state(private_input, selected_ids, account_environment, usable=False,
                                       reason='Temporary AWS qualification application stopped; no live service was retained')
        finally:
            if created:
                # Removal is necessary even after the lease expires; this is only
                # our uniquely named ephemeral Redis, with no persistent volume.
                command(['docker', 'rm', '-f', name])


def offline_plan(config, envelope):
    return {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt', 'state': 'OFFLINE_PLAN', 'configSha256': None,
        'datasetId': envelope['datasetId'], 'envelopeSha256': config['envelopeSha256'],
        'account': ACCOUNT, 'region': REGION, 'toolIdentity': tool_identity(),
        'appJarSha256': envelope['appJarSha256'], 'migrationFilesSha256': envelope['objects']['migration-files.json']['sha256'],
        'finalScaleSelected': envelope['finalScaleSelected'], 'smallRehearsalEligible': envelope['smallRehearsalEligible'],
        'finalRequiresSameToolSmallRdsReceipt': True,
        'rdsResourceId': config['rds']['resourceId'], 'mysqlVersion': '8.4.11', 'flywayVersion': 28,
        'awsExecutionAllowed': envelope['awsExecutionAllowed'], 'replacementPolicy': config['replacementPolicy'],
        'operation': config.get('operation', 'replace-database'),
        'storage': envelope['storage'], 'privateCredentialsIncluded': False, 'awsWritesExecuted': False,
        'phases': (['verify the exact prior same-tool baseline receipt, target, credentials and release',
            'assert the current live lease, stopped writers and unchanged full prepared fingerprint',
            'hold the same MySQL execution fence without removing or importing the business database',
            'prepare credentials and missing current FREE days with bounded real-calendar retries',
            'verify normal login, complete prepared fingerprint and unchanged owned nights']
            if config.get('operation') == 'resume-preparation' else
            ['verify exact object versions and local release', 'assert live lease and stopped writers',
            'review existing full fingerprint', 'remove only reviewed airbobdb', 'observe fresh free storage',
            'restore gzip stream into empty airbobdb', 'compare all rows and DDL before any preparation',
            'qualify optional ES companion', 'prepare private accounts and current FREE inventory',
            'normal login plus owner-scoped API reads', 'verify only password/FREE rows changed'])}


def verify_remote_versions(aws, envelope):
    for item in envelope['objects'].values():
        got = aws.call('s3api', 'head-object', '--bucket', envelope['bucket'], '--key', item['key'], '--version-id', item['versionId'])
        require(got.get('VersionId') == item['versionId'] and got.get('ContentLength') == item['bytes'], 'Pinned S3 object is unavailable or differs')


def preflight(config, envelope, output, aws, runtime, db, environment, live):
    verify_remote_versions(aws, envelope)
    require(shutil.disk_usage(output).free >= envelope['storage']['requiredAdditionalDataHostFreeBytes'], 'Data-host scratch reserve is insufficient')
    command(['docker', 'image', 'inspect', config['redisImage']])
    before = database_state(db, config)
    resume = config.get('operation') == 'resume-preparation'
    resume_evidence = None
    if before['tableObjects']:
        require(resume or config['replacementPolicy'] == 'discard-reviewed-existing', 'Target must be empty; replacement was not selected')
        old = output / 'existing-fingerprint.json'
        current = fingerprint(runtime, Path(config['release']), environment, old, db.timeout)
        before['fullFingerprintSha256'] = canonical_sha(current)
        if resume:
            resume_evidence = validate_preparation_resume(config, envelope, current, db, runtime)
    else:
        require(not resume, 'Preparation resume requires the original verified business database')
        before['fullFingerprintSha256'] = None
    free = free_storage(aws, config['rds']['identifier'])
    required_bytes = required_rds_bytes(config, envelope, before)
    require(free['bytes'] + before['allocatedTablespaceBytes'] >= required_bytes,
            'Even removing the old database cannot meet the RDS disk budget')
    return {'schemaVersion': 1, 'state': 'READ_ONLY_PREFLIGHT', 'datasetId': envelope['datasetId'],
        'configSha256': None, 'envelopeSha256': config['envelopeSha256'], 'rds': live, 'beforeDatabase': before,
        'freeStorageBeforeRemoval': free, 'dataHostFreeBytes': shutil.disk_usage(output).free,
        'requiredRdsFreeAfterRemovalBytes': required_bytes,
        'recordedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'sourceVersionsAvailable': True,
        'awsWritesExecuted': False, 'replacementPolicy': config['replacementPolicy'],
        'operation': config.get('operation', 'replace-database'), 'resumeEvidence': resume_evidence}


def finish_preparation(config, envelope, runtime, environment, db, output, event, scope, *, expected_owner_sha=None):
    started = time.monotonic()
    event('PREPARATION_STARTED', preparationStartedAt=dt.datetime.now(dt.timezone.utc).isoformat())
    prepared = prepare_service(config, envelope, runtime, environment, db, output, output / '.private',
        expected_owner_sha=expected_owner_sha,
        checkpoint=lambda owners: event('PREPARATION_OWNER_BASELINE_VERIFIED', baselineOwnerSha256=owners))
    db.guard(force=True)
    final_state = 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED' if scope == 'small-rds-rehearsal' else 'DATABASE_INVENTORY_LOGIN_VERIFIED'
    event(final_state, preparation=prepared, deploymentReady=False, applicationLeftRunning=False, albReady=False, kafkaCdcReady=False,
          preparedFingerprintSha256=prepared['preparedFingerprintSha256'],
          preparationCompletedAt=dt.datetime.now(dt.timezone.utc).isoformat(), preparationSeconds=round(time.monotonic() - started, 6))


def execute(config, envelope, reviewed, output, aws, runtime, db, environment, live, event, *, allow_small_rehearsal=False):
    scope = execution_scope(config, envelope, allow_small_rehearsal=allow_small_rehearsal)
    prerequisite = validate_rehearsal(config, envelope) if scope == 'final-b-rds' else None
    require('lease' in config, 'AWS execution requires a live orchestration lease')
    guard = db.guard
    guard(force=True)
    require(reviewed['state'] == 'READ_ONLY_PREFLIGHT' and reviewed['datasetId'] == envelope['datasetId']
            and reviewed['envelopeSha256'] == config['envelopeSha256'] and reviewed['rds'] == live
            and reviewed.get('operation', 'replace-database') == config.get('operation', 'replace-database'),
            'Reviewed preflight belongs to another target')
    verify_remote_versions(aws, envelope)
    require(shutil.disk_usage(output).free >= envelope['storage']['requiredAdditionalDataHostFreeBytes'], 'Data-host reserve is insufficient')
    command(['docker', 'image', 'inspect', config['redisImage']], guard=guard)
    with exclusive_database(db):
        guard = db.guard
        before = database_state(db, config)
        expected = dict(reviewed['beforeDatabase']); old_sha = expected.pop('fullFingerprintSha256')
        require(before == expected, 'Existing database changed since preflight')
        if before['tableObjects']:
            require(config.get('operation') == 'resume-preparation' or config['replacementPolicy'] == 'discard-reviewed-existing',
                    'Nonempty target cannot be replaced')
            source_name = 'resume-source-fingerprint.json' if config.get('operation') == 'resume-preparation' else 'removed-database-fingerprint.json'
            removed_fingerprint = fingerprint(runtime, Path(config['release']), environment, output / source_name, db.timeout, guard)
            require(canonical_sha(removed_fingerprint) == old_sha, 'Existing full database changed since review')
        if config.get('operation') == 'resume-preparation':
            require(before['tableObjects'], 'Preparation resume cannot recreate a missing database')
            evidence = validate_preparation_resume(config, envelope, removed_fingerprint, db, runtime)
            require(evidence == reviewed.get('resumeEvidence'), 'Reviewed preparation-resume evidence changed')
            free = free_storage(aws, config['rds']['identifier'])
            require(free['bytes'] + before['allocatedTablespaceBytes'] >= required_rds_bytes(config, envelope, before),
                    'RDS lacks the reviewed preparation growth and binlog reserve')
            event('VERIFIED_BASELINE_REUSED_FOR_PREPARATION', beforeDatabase=before, **execution_bindings(config),
                executionScope=scope, finalScaleSelected=envelope['finalScaleSelected'], smallRdsPrerequisite=prerequisite,
                allRowsAndDdlEqual=True, sealedFingerprint=read(Path(config['release']) / 'before-fingerprint.json'),
                restoredFingerprintSha256=evidence['restoredFingerprintSha256'], baselineOwnerSha256=evidence['baselineOwnerSha256'],
                previousBusinessSchemaAbsent=True, previousBusinessSchemaAbsenceInheritedFromReceipt=evidence['receiptSha256'],
                maximumSimultaneousBusinessDatabases=1, awsWritesExecuted=True, databaseRecreatedInThisRun=False,
                sqlImportSkipped=True, searchVerified=evidence['searchVerified'], preparationResume=evidence,
                freeStorageBeforePreparation=free, plaintextTemporaryDumpBytes=0)
            finish_preparation(config, envelope, runtime, environment, db, output, event, scope,
                               expected_owner_sha=evidence['baselineOwnerSha256'])
            return
        event('EXISTING_DATABASE_VERIFIED', beforeDatabase=before, removedFingerprintSha256=old_sha,
              **execution_bindings(config),
              executionScope=scope, finalScaleSelected=envelope['finalScaleSelected'], smallRdsPrerequisite=prerequisite,
              awsExecutionAllowed=True, explicitSmallRehearsalOptIn=scope == 'small-rds-rehearsal')
        require(sha(Path(config['release']) / 'airbob-growth.sql.gz') == envelope['objects']['airbob-growth.sql.gz']['sha256'],
                'Sealed dump changed during preflight; previous database was not removed')
        guard(force=True)
        # The exact reviewed run schema is the sole deletion target. There is no
        # fallback to another schema, snapshot promotion, or automatic rollback.
        db.execute('DROP DATABASE IF EXISTS airbobdb;', False)
        removed_at = dt.datetime.now(dt.timezone.utc)
        require(db.scalar("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='airbobdb'", False) == 0,
                'Previous database still exists')
        event('PREVIOUS_DATABASE_REMOVED', previousBusinessSchemaAbsent=True, maximumSimultaneousBusinessDatabases=1, awsWritesExecuted=True)
        deadline = time.monotonic() + 900
        while True:
            guard()
            try:
                free = free_storage(aws, config['rds']['identifier'], not_before=removed_at)
                break
            except ValueError:
                require(time.monotonic() < deadline, 'Fresh post-removal RDS disk metric did not arrive; database remains absent')
                time.sleep(15)
        require(free['bytes'] >= required_rds_bytes(config, envelope, before), 'Fresh post-removal RDS space is insufficient')
        event('POST_REMOVAL_CAPACITY_VERIFIED', freeStorageAfterRemoval=free)
        db.execute('CREATE DATABASE airbobdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;', False)
        require(db.scalar('SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()') == 0, 'Import target is not empty')
        started = time.monotonic()
        event('SQL_IMPORT_STARTED', sqlImportStartedAt=dt.datetime.now(dt.timezone.utc).isoformat())
        stream_restore(db, Path(config['release']) / 'airbob-growth.sql.gz')
        event('SQL_IMPORT_COMPLETED', sqlImportCompletedAt=dt.datetime.now(dt.timezone.utc).isoformat(),
              sqlImportSeconds=round(time.monotonic() - started, 6))
        started = time.monotonic()
        event('FULL_VALIDATION_STARTED', fullValidationStartedAt=dt.datetime.now(dt.timezone.utc).isoformat())
        observed = fingerprint(runtime, Path(config['release']), environment, output / 'restored-fingerprint.json', db.timeout, guard)
        require(observed == read(Path(config['release']) / 'before-fingerprint.json'), 'Full restored rows/DDL differ; preparation was not run')
        event('SEALED_DATABASE_VERIFIED', allRowsAndDdlEqual=True, plaintextTemporaryDumpBytes=0,
              restoredFingerprintSha256=sha(output / 'restored-fingerprint.json'), sealedFingerprint=observed,
              fullValidationCompletedAt=dt.datetime.now(dt.timezone.utc).isoformat(), fullValidationSeconds=round(time.monotonic() - started, 6))
        if 'search' in config:
            qualify_search(config, environment, runtime, output, guard)
            event('SEARCH_COMPANION_VERIFIED', searchVerified=True, aliasActivated=False)
        finish_preparation(config, envelope, runtime, environment, db, output, event, scope)


def qualify_search(config, environment, runtime, output, guard):
    """Bind the optional companion adapter to this already-verified RDS source."""
    coordinates = config['search']
    settings = read(coordinates['config'])
    envelope = read(config['envelope'])
    require(settings['datasetId'] == envelope['datasetId'], 'ES dataset differs')
    settings.update(releaseDirectory=config['release'], migrationDirectory=config['migrationDirectory'],
        appJar=config['appJar'], etlLibDirectory=str(runtime / 'runtime/lib'),
        consumerManifestSha256=envelope['consumerManifestSha256'], checksSha256=envelope['checksumsSha256'],
        allowSmallQualification=envelope['finalScaleSelected'] is False)
    base, query = environment['AIRBOB_ETL_DB_URL'].split('?', 1)
    properties = {key: value for key, value in parse_qsl(query) if not key.startswith('trustCertificateKeyStore')}
    trust = output / '.private/rds-trust.p12'
    settings['mysql'] = {'jdbcUrl': base + '?' + urlencode(properties), 'username': environment['AIRBOB_ETL_DB_USER'],
        'passwordEnvironment': 'AIRBOB_B_SEARCH_DB_PASSWORD', 'expectedServerUuid': config['rds']['serverUuid'],
        'tlsTrustStore': {'path': str(trust), 'sha256': sha(trust), 'passwordEnvironment': 'AIRBOB_B_SEARCH_TRUST_PASSWORD'}}
    guard(force=True)
    child_environment = dict(os.environ, AIRBOB_B_SEARCH_DB_PASSWORD=environment['AIRBOB_ETL_DB_PASSWORD'],
                             AIRBOB_B_SEARCH_TRUST_PASSWORD='changeit')
    settings_file = output / '.private/search-config.json'
    write(settings_file, settings)
    scripts = Path(__file__).resolve().parents[3] / 'scripts'
    baseline = output / 'search-baseline'
    command([sys.executable, str(scripts / 'produce-growth-b-search-snapshot.py'), 'capture-baseline',
        '--config', str(settings_file), '--output', str(baseline)], env=child_environment,
        timeout=config['operationTimeoutSeconds'], guard=guard)
    command([sys.executable, str(scripts / 'restore-growth-b-search.py'), '--config', str(settings_file),
        '--companion', coordinates['companionDirectory'], '--descriptor', coordinates['descriptor'],
        '--baseline', str(baseline), '--output', str(output / 'search')], env=child_environment,
        timeout=config['operationTimeoutSeconds'], guard=guard)
    guard(force=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['plan', 'preflight', 'execute'], nargs='?', default='plan')
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--preflight-sha256')
    parser.add_argument('--allow-small-rehearsal', action='store_true', help='Only with reviewed allowSmallRehearsal=true and an exactly qualified small B')
    args = parser.parse_args()
    config = configuration(args.config)
    envelope = validate_inputs(config)
    require(not args.output.exists(), 'Use a new output directory for each operation')
    output = private_directory(args.output.resolve())
    receipt = offline_plan(config, envelope)
    receipt['configSha256'] = sha(args.config)
    if args.mode == 'plan':
        write(output / 'plan.json', receipt)
        print(json.dumps({'state': receipt['state'], 'awsExecutionAllowed': receipt['awsExecutionAllowed']}))
        return
    if args.mode == 'execute':
        scope = execution_scope(config, envelope, allow_small_rehearsal=args.allow_small_rehearsal)
        require('lease' in config, 'Execution requires an existing live lease')
        if scope == 'final-b-rds':
            validate_rehearsal(config, envelope)
        require(args.preflight and digest(args.preflight_sha256) and sha(args.preflight) == args.preflight_sha256,
                'An exact reviewed read-only preflight is required')
        reviewed = read(args.preflight)
        require(reviewed['configSha256'] == sha(args.config), 'Reviewed restore configuration changed')
    receipt.update(state='STARTING', events=[])
    def event(state, **values):
        at = dt.datetime.now(dt.timezone.utc).isoformat()
        receipt.update(state=state, updatedAt=at, **values)
        receipt.setdefault('startedAt', at)
        receipt.setdefault('events', []).append({'state': state, 'at': at})
        write(output / 'restore-receipt.json', receipt)
    try:
        runtime = extract_runtime(Path(config['release']), output / 'runtime')
        host_runtime_path = output / 'host-runtime-qualification.json'
        host_runtime = qualify_runtime(Path(config['release']), runtime, host_runtime_path,
            expected_checks={name: value['sha256'] for name, value in envelope['objects'].items()})
        receipt['hostRuntimeQualification'] = qualification_binding(host_runtime_path)
        with activated_runtime(host_runtime):
            aws = Aws()
            live = live_rds(aws, config)
            guard = Lease(aws, config['lease']) if args.mode == 'execute' else None
            if guard:
                guard(force=True)
            secret_dir = private_directory(output / '.private')
            db, environment = connection(config, aws, live, secret_dir, guard)
            if args.mode == 'preflight':
                receipt = preflight(config, envelope, output, aws, runtime, db, environment, live)
                receipt['configSha256'] = sha(args.config)
                receipt['hostRuntimeQualification'] = qualification_binding(host_runtime_path)
                write(output / 'preflight.json', receipt)
            else:
                execute(config, envelope, reviewed, output, aws, runtime, db, environment, live, event,
                        allow_small_rehearsal=args.allow_small_rehearsal)
    except BaseException as error:
        event('FAILED', errorType=type(error).__name__, deploymentReady=False)
        raise SystemExit('B AWS operation failed; see the private receipt. Credentials and raw errors were not recorded.') from None
    finally:
        # Only connection secrets/config are ephemeral; the supplied accounts
        # file remains in the operator's separate private directory for reuse.
        if (output / '.private').exists():
            shutil.rmtree(output / '.private')
    receipt_path = output / ('preflight.json' if args.mode == 'preflight' else 'restore-receipt.json')
    print(json.dumps({'state': receipt['state'], 'output': str(output), 'receiptSha256': sha(receipt_path),
                      'finalScaleSelected': envelope['finalScaleSelected'], 'awsDeploymentReady': False}))


if __name__ == '__main__':
    main()
