#!/usr/bin/env python3
"""SQL-only Mac import into an existing private Global B RDS.

The operator must establish the tunnel and a reviewed temporary DNS override:
the ORIGINAL RDS endpoint must resolve only to loopback on this Mac. This tool
never changes DNS. MySQL negotiates TLS itself with VERIFY_IDENTITY against
that endpoint, through the selected local tunnel port; TLS is never disabled.

SQL streaming has no elapsed-time cutoff. Connection/query timeouts, the named
DB lock, tunnel checks and explicit interruption still apply. A durable intent
prevents automatic replay, including after an ambiguous or interrupted import.
Once SQL succeeds, only the explicit postcheck action can be resumed.
"""
import argparse
from contextlib import contextmanager
import datetime as dt
import fcntl
import hashlib
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import time
import uuid

import growth_b_aws_restore as restore

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'
ENGINE = '8.4.11'
CLASS = 'db.m6i.large'
SCHEMA = 'airbobdb'
SYSTEM_SCHEMAS = {'mysql', 'sys', 'information_schema', 'performance_schema'}
MAX_RESPONSE = 1024 * 1024


class MacImportError(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def need(value, code):
    if not value:
        raise MacImportError(code)


def utc():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def encoded(value):
    return (json.dumps(value, sort_keys=True, indent=2, allow_nan=False) + '\n').encode()


def private_directory(path):
    path = Path(path).absolute()
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = path.lstat()
    need(path.resolve() == path and stat.S_ISDIR(info.st_mode) and info.st_uid == os.geteuid()
         and stat.S_IMODE(info.st_mode) == 0o700, 'PRIVATE_DIRECTORY_REQUIRED')
    return path


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_new(path, raw):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as file:
        file.write(raw)
        file.flush()
        os.fsync(file.fileno())
    sync_directory(Path(path).parent)


def record(path, value):
    write_new(path, encoded(value))


def read_record(path):
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as file:
        info = os.fstat(file.fileno())
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
             and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1
             and 0 < info.st_size <= MAX_RESPONSE, 'PRIVATE_RECEIPT_REQUIRED')
        raw = file.read(MAX_RESPONSE + 1)
    need(len(raw) == info.st_size, 'RECEIPT_CHANGED')
    return json.loads(raw)


def file_identity(path):
    info = path.stat()
    need(path.is_file() and not path.is_symlink(), 'REGULAR_INPUT_REQUIRED')
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)


def check_hash(path, expected):
    before = file_identity(path)
    digest = hashlib.sha256()
    with path.open('rb') as file:
        while chunk := file.read(4 * 1024 * 1024):
            digest.update(chunk)
    need(file_identity(path) == before and digest.hexdigest() == expected, 'INPUT_SHA_OR_IDENTITY_CHANGED')
    return before


def validate_arguments(args):
    need(re.fullmatch(r'airbob-[a-z0-9](?:[a-z0-9-]{0,60}[a-z0-9])?', args.identifier)
         and len(args.identifier) <= 63 and '--' not in args.identifier, 'EXPLICIT_RDS_IDENTIFIER_REQUIRED')
    need(re.fullmatch(r'[a-z0-9-]+\.[a-z0-9.-]+\.ap-northeast-2\.rds\.amazonaws\.com', args.endpoint),
         'ORIGINAL_RDS_ENDPOINT_REQUIRED')
    need(re.fullmatch(r'db-[A-Z0-9]+', args.resource_id), 'EXPLICIT_RESOURCE_ID_REQUIRED')
    need(type(args.tunnel_port) is int and 1024 <= args.tunnel_port <= 65535, 'LOCAL_TUNNEL_PORT_REQUIRED')
    need(re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', args.aws_profile), 'EXPLICIT_AWS_PROFILE_REQUIRED')
    for value in (args.dump_sha256, args.ca_sha256):
        need(re.fullmatch(r'[0-9a-f]{64}', value or ''), 'EXPLICIT_SHA_REQUIRED')
    if args.expected_server_uuid is not None:
        need(str(uuid.UUID(args.expected_server_uuid)) == args.expected_server_uuid, 'CANONICAL_SERVER_UUID_REQUIRED')
    for name in ('dump', 'ca_bundle', 'output', 'mysql_client'):
        value = Path(getattr(args, name)).absolute()
        need(value.resolve() == value, 'CANONICAL_LOCAL_PATH_REQUIRED')
        setattr(args, name, value)
    need(args.dump.name.endswith('.sql.gz') and args.dump.stat().st_size > 0, 'SEALED_GZIP_DUMP_REQUIRED')
    need(args.mysql_client.is_file() and os.access(args.mysql_client, os.X_OK), 'MYSQL_CLIENT_REQUIRED')
    return args


def binding(args):
    return {'accountId': ACCOUNT, 'region': REGION, 'identifier': args.identifier,
            'resourceId': args.resource_id, 'endpoint': args.endpoint, 'tunnelPort': args.tunnel_port,
            'awsProfile': args.aws_profile, 'dumpPath': str(args.dump), 'dumpSha256': args.dump_sha256,
            'caBundlePath': str(args.ca_bundle), 'caBundleSha256': args.ca_sha256,
            'mysqlClient': str(args.mysql_client), 'expectedServerUuid': args.expected_server_uuid,
            'engineVersion': ENGINE, 'importClass': CLASS, 'allocatedStorageGiB': 100,
            'sqlElapsedTimeoutSeconds': None, 'schema': SCHEMA}


def loopback_endpoint(endpoint, port):
    try:
        rows = socket.getaddrinfo(endpoint, port, type=socket.SOCK_STREAM)
        addresses = sorted({str(ipaddress.ip_address(row[4][0])) for row in rows})
    except (OSError, ValueError):
        raise MacImportError('ENDPOINT_LOOPBACK_DNS_UNAVAILABLE') from None
    need(addresses and all(ipaddress.ip_address(value).is_loopback for value in addresses),
         'REVIEWED_ENDPOINT_LOOPBACK_DNS_OVERRIDE_REQUIRED')
    return addresses


class Aws:
    def __init__(self, profile):
        self.profile = profile

    def call(self, *args):
        need(args[:2] in {('sts', 'get-caller-identity'), ('rds', 'describe-db-instances'),
                              ('secretsmanager', 'get-secret-value')}, 'NON_READ_AWS_OPERATION_REJECTED')
        env = {k: v for k, v in os.environ.items() if not k.startswith('AWS_')}
        env.update(AWS_MAX_ATTEMPTS='1', AWS_EC2_METADATA_DISABLED='true', AWS_PAGER='', AWS_CLI_AUTO_PROMPT='off')
        try:
            result = subprocess.run(['aws', '--profile', self.profile, '--region', REGION, '--no-cli-pager',
                                     '--output', 'json', '--cli-connect-timeout', '5', '--cli-read-timeout', '30',
                                     *args], capture_output=True, env=env, timeout=45)
        except (OSError, subprocess.TimeoutExpired):
            raise MacImportError('AWS_READ_UNAVAILABLE') from None
        if result.returncode:
            auth = any(word in result.stderr.lower() for word in
                       (b'expired', b'credential', b'token', b'invalid_grant', b'login', b'authenticate'))
            raise MacImportError('AWS_AUTHENTICATION_REQUIRED' if auth else 'AWS_READ_REJECTED')
        need(len(result.stdout) <= MAX_RESPONSE, 'AWS_RESPONSE_TOO_LARGE')
        return json.loads(result.stdout)


def live_rds(aws, args):
    identity = aws.call('sts', 'get-caller-identity')
    need(identity.get('Account') == ACCOUNT, 'AWS_ACCOUNT_MISMATCH')
    rows = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', args.identifier).get('DBInstances')
    need(isinstance(rows, list) and len(rows) == 1, 'EXACT_RDS_REQUIRED')
    row = rows[0]
    need(row.get('DBInstanceIdentifier') == args.identifier and row.get('DbiResourceId') == args.resource_id
         and row.get('DBInstanceArn') == f'arn:aws:rds:{REGION}:{ACCOUNT}:db:{args.identifier}'
         and row.get('Endpoint') is not None and row['Endpoint'].get('Address') == args.endpoint
         and row['Endpoint'].get('Port') == 3306 and row.get('PubliclyAccessible') is False
         and row.get('Engine') == 'mysql' and row.get('EngineVersion') == ENGINE
         and row.get('DBInstanceStatus') == 'available' and row.get('DBInstanceClass') == CLASS
         and row.get('AllocatedStorage') == 100 and row.get('StorageType') == 'gp3'
         and row.get('MultiAZ') is False and row.get('StorageEncrypted') is True
         and not row.get('PendingModifiedValues'), 'RDS_IDENTITY_OR_SHAPE_MISMATCH')
    secret = row.get('MasterUserSecret', {})
    need(secret.get('SecretStatus') == 'active' and isinstance(secret.get('SecretArn'), str)
         and secret['SecretArn'].startswith(f'arn:aws:secretsmanager:{REGION}:{ACCOUNT}:secret:')
         and isinstance(row.get('MasterUsername'), str) and row['MasterUsername'], 'ACTIVE_MANAGED_SECRET_REQUIRED')
    expires = [tag.get('Value') for tag in row.get('TagList', []) if tag.get('Key') == 'ExpiresAt']
    need(len(expires) <= 1 and (not expires or isinstance(expires[0], str)
         and re.fullmatch(r'[1-9][0-9]{0,12}', expires[0])), 'INVALID_RESOURCE_EXPIRY_TAG')
    return {'identifier': args.identifier, 'resourceId': args.resource_id, 'endpoint': args.endpoint,
            'engineVersion': ENGINE, 'instanceClass': CLASS, 'allocatedStorageGiB': 100,
            'masterSecretArn': secret['SecretArn'], 'masterUsername': row['MasterUsername'],
            'resourceExpiresAtEpoch': int(expires[0]) if expires else None}


@contextmanager
def target_lock(resource_id):
    root = private_directory(Path.home() / '.airbob/mac-import-locks')
    path = root / (resource_id + '.lock')
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        info = os.fstat(fd)
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid()
             and stat.S_IMODE(info.st_mode) == 0o600 and info.st_nlink == 1, 'TARGET_LOCK_SHAPE')
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise MacImportError('TARGET_IMPORT_ALREADY_RUNNING') from None
        yield
    finally:
        os.close(fd)


@contextmanager
def credentials(aws, live, args):
    value = aws.call('secretsmanager', 'get-secret-value', '--secret-id', live['masterSecretArn'])
    need(value.get('ARN') == live['masterSecretArn'] and isinstance(value.get('SecretString'), str), 'SECRET_REFERENCE_MISMATCH')
    secret = json.loads(value['SecretString'])
    need(secret.get('username') == live['masterUsername'] and isinstance(secret.get('password'), str)
         and secret['password'], 'MANAGED_CREDENTIAL_IDENTITY_MISMATCH')
    path = args.output / ('.mysql-' + uuid.uuid4().hex + '.cnf')
    # Password never appears in argv, environment, public receipts or exception text.
    raw = '[client]\nuser=' + restore.cnf_quote(secret['username']) + '\npassword=' + restore.cnf_quote(secret['password']) + '\n'
    try:
        write_new(path, raw.encode())
        yield path
    finally:
        if path.exists():
            path.unlink()
            sync_directory(path.parent)


class Database(restore.Database):
    def __init__(self, defaults, args, guard):
        super().__init__(defaults, 60, guard)
        self.args = args

    def command(self, database=True):
        # env -i also prevents MYSQL_PWD, MYSQL_HOME and login-file overrides.
        return ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'TZ=UTC', 'LC_ALL=C.UTF-8',
                str(self.args.mysql_client), '--defaults-file=' + str(self.defaults), '--no-login-paths',
                '--skip-reconnect', '--protocol=TCP', '--host=' + self.args.endpoint,
                '--port=' + str(self.args.tunnel_port), '--ssl-mode=VERIFY_IDENTITY',
                '--ssl-ca=' + str(self.args.ca_bundle), '--connect-timeout=10',
                '--default-character-set=utf8mb4', '--batch', '--raw', '--unbuffered'] + ([SCHEMA] if database else [])


def database_identity(db, expected_uuid=None):
    rows = db.rows('SELECT @@version version,@@server_uuid serverUuid,@@read_only readOnly,@@super_read_only superReadOnly', False)
    need(len(rows) == 1, 'DATABASE_IDENTITY_UNAVAILABLE')
    row = rows[0]
    need(row.get('version') == ENGINE and row.get('readOnly') == row.get('superReadOnly') == 0
         and isinstance(row.get('serverUuid'), str) and str(uuid.UUID(row['serverUuid'])) == row['serverUuid'], 'DATABASE_IDENTITY_MISMATCH')
    need(expected_uuid is None or row['serverUuid'] == expected_uuid, 'DATABASE_UUID_CHANGED')
    cipher = db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'", False)
    need(len(cipher) == 1 and isinstance(cipher[0].get('Value'), str) and cipher[0]['Value'], 'VERIFIED_TLS_SESSION_REQUIRED')
    return {'serverUuid': row['serverUuid'], 'engineVersion': ENGINE, 'tlsCipher': cipher[0]['Value']}


def empty_database(db):
    schemas = {row['SCHEMA_NAME'] for row in db.rows('SELECT SCHEMA_NAME FROM information_schema.schemata', False)}
    need(schemas <= SYSTEM_SCHEMAS | {SCHEMA}, 'FOREIGN_BUSINESS_SCHEMA_PRESENT')
    need(db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='airbobdb'", False) == 0,
         'DATABASE_NOT_EMPTY')
    need(db.scalar("SELECT (SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='airbobdb')+"
                   "(SELECT COUNT(*) FROM information_schema.events WHERE event_schema='airbobdb')", False) == 0,
         'DATABASE_ROUTINE_OR_EVENT_PRESENT')
    # The named restore-fence connection has no default schema. Any app session
    # selecting airbobdb must already have been stopped by the operator.
    need(db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE DB='airbobdb' AND ID<>CONNECTION_ID()", False) == 0,
         'OTHER_DATABASE_CLIENTS_PRESENT')
    return SCHEMA in schemas


def public_progress(value):
    allowed = ('state', 'compressedBytesRead', 'compressedBytesTotal', 'elapsedSeconds',
               'secondsSinceInputProgress', 'mysqlExitCode', 'gzipExitCode')
    result = {key: value[key] for key in allowed if key in value}
    for key in ('compressedBytesRead', 'compressedBytesTotal'):
        need(type(result.get(key)) is int and result[key] >= 0, 'INVALID_STREAM_PROGRESS')
    need(result['compressedBytesRead'] <= result['compressedBytesTotal'] and
         isinstance(result.get('elapsedSeconds'), (int, float)) and math.isfinite(result['elapsedSeconds'])
         and result['elapsedSeconds'] >= 0, 'INVALID_STREAM_PROGRESS')
    need(result.get('state') in (None, 'SQL_IMPORT_RUNNING', 'SQL_IMPORT_COMPLETED', 'SQL_IMPORT_FAILED')
         and all(result.get(key) is None or type(result[key]) is int for key in ('mysqlExitCode', 'gzipExitCode')),
         'INVALID_STREAM_PROGRESS')
    if 'secondsSinceInputProgress' in result:
        need(isinstance(result['secondsSinceInputProgress'], (int, float))
             and math.isfinite(result['secondsSinceInputProgress']) and result['secondsSinceInputProgress'] >= 0,
             'INVALID_STREAM_PROGRESS')
    errors = value.get('mysqlErrors', [])
    need(isinstance(errors, list) and len(errors) <= 8, 'INVALID_STREAM_ERROR_CODES')
    for error in errors:
        need(isinstance(error, dict) and set(error) == {'code', 'sqlState', 'lineNumber'}
             and type(error['code']) is int and 0 <= error['code'] <= 99999
             and (error['sqlState'] is None or isinstance(error['sqlState'], str)
                  and re.fullmatch(r'[A-Z0-9]{5}', error['sqlState']))
             and (error['lineNumber'] is None or type(error['lineNumber']) is int
                  and 0 <= error['lineNumber'] < 10**12), 'INVALID_STREAM_ERROR_CODES')
    result['mysqlErrors'] = errors
    return result


def run(args):
    args = validate_arguments(args)
    need(sys.platform == 'darwin', 'MAC_EXECUTION_REQUIRED')
    args.output = private_directory(args.output)
    expected = binding(args)
    completed_path = args.output / 'sql-import-completed.json'
    intent_path = args.output / 'import-intent.json'
    with target_lock(args.resource_id):
        if args.action == 'import':
            need(not os.path.lexists(completed_path), 'SQL_ALREADY_IMPORTED_USE_POSTCHECK')
            need(not os.path.lexists(intent_path), 'IMPORT_INTENT_EXISTS_NO_REPLAY')
            dump_identity = check_hash(args.dump, args.dump_sha256)
            with args.dump.open('rb') as file:
                need(file.read(2) == b'\x1f\x8b', 'SEALED_GZIP_DUMP_REQUIRED')
        else:
            complete = read_record(completed_path)
            need(complete.get('state') == 'SQL_IMPORT_COMPLETED' and complete.get('binding') == expected,
                 'COMPLETED_IMPORT_BINDING_REQUIRED')
            need(read_record(intent_path).get('binding') == expected, 'ORIGINAL_IMPORT_INTENT_REQUIRED')
            dump_identity = None
        ca_identity = check_hash(args.ca_bundle, args.ca_sha256)

        def local_guard(force=False):
            loopback_endpoint(args.endpoint, args.tunnel_port)
            need(file_identity(args.ca_bundle) == ca_identity, 'CA_CHANGED_DURING_OPERATION')
            if dump_identity is not None:
                need(file_identity(args.dump) == dump_identity, 'DUMP_CHANGED_DURING_OPERATION')

        local_guard()
        aws = Aws(args.aws_profile)
        live = live_rds(aws, args)
        if args.action == 'postcheck':
            need(complete.get('rds') == live, 'COMPLETED_RDS_IDENTITY_CHANGED')
        with credentials(aws, live, args) as defaults:
            db = Database(defaults, args, local_guard)
            with restore.exclusive_database(db):
                identity = database_identity(db, args.expected_server_uuid if args.action == 'import' else complete['database']['serverUuid'])
                if args.action == 'import':
                    exists = empty_database(db)
                    record(intent_path, {'state': 'SQL_IMPORT_INTENT', 'binding': expected, 'rds': live,
                                         'database': identity, 'recordedAt': utc(), 'automaticReplayAllowed': False})
                    try:
                        if not exists:
                            db.execute('CREATE DATABASE airbobdb CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;', False)

                        def progress(value):
                            safe = public_progress(value)
                            expiry = live['resourceExpiresAtEpoch']
                            if expiry is not None:
                                safe.update(resourceExpiresAtEpoch=expiry,
                                            resourceExpiryReached=time.time() >= expiry,
                                            resourceExpiryAutomaticallyExtended=False)
                            path = args.output / ('.progress-' + uuid.uuid4().hex + '.json')
                            write_new(path, encoded(safe))
                            os.replace(path, args.output / 'progress.json')
                            sync_directory(args.output)
                            print(json.dumps(safe, sort_keys=True), flush=True)

                        summary = restore.stream_restore(db, args.dump, progress=progress)
                        summary = public_progress(summary)
                        need(summary.get('mysqlExitCode') == summary.get('gzipExitCode') == 0
                             and summary['compressedBytesRead'] == summary['compressedBytesTotal'] == dump_identity[2],
                             'IMPORT_PROCESS_RESULT_UNCONFIRMED')
                        # This precedes every postcheck. Failure later cannot cause SQL replay.
                        complete = {'schemaVersion': 1, 'kind': 'global-b-mac-sql-import', 'state': 'SQL_IMPORT_COMPLETED',
                                    'binding': expected, 'rds': live, 'database': identity, 'stream': summary,
                                    'completedAt': utc(), 'fullDatasetValidated': False, 'automaticReplayAllowed': False}
                        record(completed_path, complete)
                    except BaseException as error:
                        record(args.output / 'import-unconfirmed.json', {'state': 'SQL_IMPORT_UNCONFIRMED_NO_REPLAY',
                               'binding': expected, 'recordedAt': utc(), 'closedCode': error.code if isinstance(error, MacImportError) else 'SQL_IMPORT_OR_CHECKPOINT_UNCONFIRMED',
                               'partialDatabaseRetained': True, 'automaticReplayAllowed': False})
                        raise
                try:
                    local_guard(force=True)
                    current = database_identity(db, complete['database']['serverUuid'])
                    count = db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='airbobdb'", False)
                    need(type(count) is int and count > 0, 'IMPORTED_TABLES_NOT_OBSERVED')
                    flyway = db.scalar('SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM airbobdb.flyway_schema_history WHERE success=1', False)
                    failed = db.scalar('SELECT COUNT(*) FROM airbobdb.flyway_schema_history WHERE success=0', False)
                    owner = db.scalar('SELECT COUNT(*) FROM airbobdb.member WHERE id=6675', False)
                    listing = db.scalar('SELECT COUNT(*) FROM airbobdb.accommodation WHERE id=16102 AND member_id=6675', False)
                    need(flyway == 28 and failed == 0 and owner == listing == 1, 'IMPORTED_REPRESENTATIVE_OR_FLYWAY_MISMATCH')
                    result = {'state': 'SQL_IMPORT_POSTCHECK_PASSED', 'binding': expected, 'rds': live,
                              'database': current, 'tableObjects': count, 'checkedAt': utc(),
                              'flywayVersion': 28, 'failedMigrations': 0, 'representativePrimaryKeysObserved': True,
                              'sqlImportReceiptSha256': hashlib.sha256(completed_path.read_bytes()).hexdigest(),
                              'sqlReplayed': False, 'fullDatasetValidated': False}
                    record(args.output / ('postcheck-' + uuid.uuid4().hex + '.json'), result)
                    return result
                except BaseException as error:
                    record(args.output / ('postcheck-failed-' + uuid.uuid4().hex + '.json'),
                           {'state': 'SQL_IMPORTED_POSTCHECK_FAILED', 'recordedAt': utc(), 'binding': expected,
                            'closedCode': error.code if isinstance(error, MacImportError) else 'POSTCHECK_UNCONFIRMED',
                            'sqlImportReceiptPreserved': True, 'automaticReplayAllowed': False})
                    raise


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument('action', choices=('import', 'postcheck'))
    for flag in ('identifier', 'endpoint', 'resource-id', 'dump', 'dump-sha256', 'ca-bundle', 'ca-sha256', 'aws-profile', 'output'):
        result.add_argument('--' + flag, required=True)
    result.add_argument('--tunnel-port', required=True, type=int)
    result.add_argument('--expected-server-uuid', help='Optional initially; postcheck always requires the recorded live UUID.')
    result.add_argument('--mysql-client', default=str(Path(shutil.which('mysql') or '/usr/local/bin/mysql').resolve()))
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGINT, signal.SIGTERM)}
    def interrupted(*_):
        raise MacImportError('INTERRUPTED_IMPORT_UNCONFIRMED')
    try:
        for sig in previous:
            signal.signal(sig, interrupted)
        result = run(args)
        print(json.dumps(result, sort_keys=True))
        return 0
    except BaseException as error:
        code = error.code if isinstance(error, MacImportError) else 'LOCAL_IMPORT_UNCONFIRMED'
        print(json.dumps({'state': 'MAC_IMPORT_CLOSED', 'closedCode': code,
                          'authenticationRefreshRequired': code == 'AWS_AUTHENTICATION_REQUIRED',
                          'automaticReplayAllowed': False}))
        return 1
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    raise SystemExit(main())
