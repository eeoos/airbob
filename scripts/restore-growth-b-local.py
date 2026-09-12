#!/usr/bin/env python3
"""Preflight, then explicitly replace one identified local Airbob experiment MySQL.

The default invocation only reads Docker/MySQL and writes its private report directory.
--apply requires a previously reviewed preflight SHA. No SSH, cloud calls or broad cleanup.
"""
import argparse
import datetime as dt
import fcntl
import gzip
import importlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import stat
import subprocess
import sys
import threading
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'infra/aws/scripts'))
import growth_b_contract as contract
from growth_b_contract import (digest, extract_runtime, inspect_dump, integer, read, regular,
                               require, sha, validate, validate_fingerprint)
from growth_b_runtime import (active_environment, activated_runtime, qualification_binding,
                              qualify_runtime)

SYSTEM_SCHEMAS = {'information_schema', 'mysql', 'performance_schema', 'sys'}
NAME = r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}'
DATASET = r'(?:global-growth-b|korea-growth-v4)-[0-9a-f]{16}'
FRESH_OPERATION = 'fresh-empty-install'
OCI_DISCARD_OPERATION = 'oci-empty-install-after-discard'
OBSERVER_COMMAND = ['-c', 'while :; do sleep 3600; done']
SOURCE_REPRESENTATIVES = {'demo': ('demo@airbob.test', 'MEMBER'),
                          'host': ('host@airbob.test', 'MEMBER'),
                          'admin': ('admin@airbob.test', 'ADMIN')}


def write(path, value):
    """Atomic private output, including the first byte; never follow a symlink."""
    path = Path(path)
    require(not path.is_symlink(), 'Unsafe report destination')
    temporary = path.with_name(path.name + '.' + secrets.token_hex(6) + '.tmp')
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, 'w') as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def private_file(path):
    path = regular(path)
    require(not stat.S_IMODE(path.stat().st_mode) & 0o077, 'Private input must have mode 0600')
    return path


def command(arguments, *, input=None, timeout=60, output=None, env=None):
    result = subprocess.run(arguments, input=input, stdout=output or subprocess.PIPE,
                            stderr=subprocess.DEVNULL, timeout=timeout, env=env)
    # Exceptions and receipts never contain command arguments, output, credentials or HTTP bodies.
    require(result.returncode == 0, 'Subprocess failed: ' + Path(arguments[0]).name)
    return result.stdout or b''


def docker(*arguments, **kwargs):
    return command(['docker', *arguments], **kwargs)


def inspect(kind, name):
    result = json.loads(docker(kind, 'inspect', name))
    require(len(result) == 1, 'Docker identity is ambiguous')
    return result[0]


def existing(kind):
    if kind == 'container':
        return set(docker('ps', '-aq', '--no-trunc').decode().splitlines())
    return set(docker('volume', 'ls', '--format', '{{.Name}}').decode().splitlines())


def require_local_engine():
    context = docker('context', 'show').decode().strip()
    value = json.loads(docker('context', 'inspect', context))[0]
    endpoint = os.environ.get('DOCKER_HOST') or value['Endpoints']['docker']['Host']
    require(endpoint.startswith('unix://'), 'Only the local Docker engine is allowed; SSH/TCP contexts are rejected')


def runtime_environment():
    # Do not allow unrelated shell Spring settings or JVM injection to redirect the verified local probe.
    blocked = {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'CLASSPATH'}
    return active_environment({key: value for key, value in os.environ.items()
            if key not in blocked and not key.startswith(('SPRING_', 'AIRBOB_', 'AWS_'))})


class Database:
    SCHEMA = 'airbobdb'

    def __init__(self, container):
        self.container = container

    def command(self, database=True):
        # The inherited container environment supplies the password without host argv/log exposure.
        return ['docker', 'exec', '-i', self.container, 'sh', '-c',
                'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --protocol=TCP -h127.0.0.1 '
                '--default-character-set=utf8mb4 --batch --raw "$@"', 'sh'] + ([self.SCHEMA] if database else [])

    def execute(self, sql):
        return command(self.command(), input=sql.encode(), timeout=7200).decode()

    def rows(self, sql):
        lines = self.execute(sql).strip().splitlines()
        if not lines:
            return []
        keys = lines[0].split('\t')
        def value(item):
            return None if item == 'NULL' else int(item) if item.isdigit() else item
        return [dict(zip(keys, map(value, line.split('\t')))) for line in lines[1:]]

    def scalar(self, sql):
        return next(iter(self.rows(sql)[0].values()))

    @staticmethod
    def literal(value):
        return 'CONVERT(0x' + str(value).encode().hex() + ' USING utf8mb4)'

    def identity(self):
        return self.rows('SELECT @@version version,@@server_uuid uuid,@@read_only readOnly,@@super_read_only superReadOnly')[0]


def connection_environment(info):
    values = dict(item.split('=', 1) for item in info['Config']['Env'] if '=' in item)
    require(bool(values.get('MYSQL_ROOT_PASSWORD')), 'Source has no supported private root credential')
    ports = info['NetworkSettings'].get('Ports', {}).get('3306/tcp') or []
    if ports:
        require(len({entry['HostPort'] for entry in ports}) == 1, 'Ambiguous MySQL host port')
        host, port = '127.0.0.1', ports[0]['HostPort']
    else:
        addresses = {network['IPAddress'] for network in info['NetworkSettings']['Networks'].values() if network['IPAddress']}
        require(len(addresses) == 1, 'A single local Docker address or published MySQL port is required')
        host, port = next(iter(addresses)), '3306'
    return dict(runtime_environment(), AIRBOB_ETL_DB_URL=f'jdbc:mysql://{host}:{port}/airbobdb?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true',
                AIRBOB_ETL_DB_USER='root', AIRBOB_ETL_DB_PASSWORD=values['MYSQL_ROOT_PASSWORD'],
                JAVA_OPTS='-Xmx1536m -Duser.timezone=UTC')


def fingerprint(runtime, release, info, output, timeout):
    environment = connection_environment(info)
    environment['AIRBOB_ETL_BACKEND_ROOT'] = str(runtime / 'backend')
    with (output.parent / (output.stem + '.log')).open('wb') as log:
        command([str(runtime / 'runtime/bin/etl'), '--growth-profile=' + str(release / 'profile.json'),
                 '--growth-command=fingerprint', '--growth-output=' + str(output), '--service-schema=airbobdb'],
                env=environment, output=log, timeout=timeout)
    result = read(output)
    validate_fingerprint(result, require_sealed=False)
    return result


def configuration(path, mode):
    value = read(path)
    require(value['schemaVersion'] == 1 and value['mode'] == mode, 'Restore configuration mode differs')
    require(type(value.get('allowSmall', False)) is bool, 'Small qualification opt-in must be boolean')
    require(set(value) <= {'schemaVersion', 'mode', 'datasetId', 'release', 'consumerManifestSha256', 'checksumsSha256',
            'backendRoot', 'appJar', 'privateAccounts', 'allowSmall', 'source', 'target', 'writerContainers',
            'writerPorts', 'preservationPolicy', 'capacity', 'operationTimeoutSeconds', 'appStartupTimeoutSeconds', 'diskObserver', 'operation',
            'discardReceipt', 'discardReceiptSha256'},
            'Unknown configuration key; inline credentials are forbidden')
    for key in ('release', 'backendRoot', 'appJar', 'privateAccounts'):
        require(Path(value[key]).is_absolute(), 'Input paths must be absolute')
    for key in ('consumerManifestSha256', 'checksumsSha256'):
        require(digest(value[key]), 'Missing release trust anchor')
    operation = value.get('operation', 'replace-existing')
    require(operation in {'replace-existing', FRESH_OPERATION, OCI_DISCARD_OPERATION}, 'Unknown restore operation')
    fresh, discarded = operation == FRESH_OPERATION, operation == OCI_DISCARD_OPERATION
    source_less = fresh or discarded
    target = value['target']
    if source_less:
        require(mode == ('oci' if discarded else 'local'),
                'Post-discard installation is OCI-only' if discarded else 'Fresh installation is local-only')
        require(not {'source', 'preservationPolicy', 'writerContainers'} & set(value),
                'Source-less installation cannot claim an old source, retirement, or preservation policy')
        observer = value.get('diskObserver', {})
        require(set(observer) == {'name', 'id'} and re.fullmatch(NAME, observer['name']) and digest(observer['id']),
                'Source-less installation requires an explicitly identified pre-existing disk observer')
        source = None
    else:
        source = value['source']
        source_keys = {'container', 'containerId', 'volume', 'mysqlServerUuid', 'datasetId',
                'restoreReceipt', 'restoreReceiptSha256', 'fingerprint', 'fingerprintSha256', 'dump', 'dumpSha256'}
        require(set(source) in (source_keys, source_keys | {'retirementReceipt', 'retirementReceiptSha256'}),
                'Source identity and preservation coordinates must be explicit')
        require(digest(source['containerId']) and re.fullmatch(r'[0-9a-f-]{36}', source['mysqlServerUuid']) and
                re.fullmatch(DATASET, source['datasetId']), 'Exact existing experiment identity required')
        require(value['preservationPolicy'] in {'require-current-match', 'capture-current', 'discard-changes'}, 'Unknown preservation policy')
    if discarded:
        require(isinstance(value.get('discardReceipt'), str) and Path(value['discardReceipt']).is_absolute()
                and digest(value.get('discardReceiptSha256')), 'Reviewed OCI discard receipt and SHA are required')
    else:
        require(not {'discardReceipt', 'discardReceiptSha256'} & set(value),
                'Discard receipt belongs only to the explicit OCI post-discard operation')
    require(set(target) <= {'container', 'volume', 'image', 'redisImage', 'mysqlPort', 'network', 'memoryMiB', 'bufferPoolMiB'}
            and {'container', 'volume', 'image', 'redisImage', 'mysqlPort'} <= set(target), 'Unsupported target configuration')
    for item in ((target,) if source_less else (source, target)):
        require(all(re.fullmatch(NAME, item[key]) for key in ('container', 'volume')), 'Unsafe Docker name')
    require(integer(target['mysqlPort'], 1024) and target['mysqlPort'] <= 65535, 'Use an unprivileged loopback MySQL port')
    for key in ('image', 'redisImage'):
        require(re.fullmatch(r'(?:[A-Za-z0-9./:_-]+@)?sha256:[0-9a-f]{64}', target[key]), 'Images must be pinned by digest/ID')
    require(integer(target.get('memoryMiB', 2048), 512) and integer(target.get('bufferPoolMiB', 1024), 128)
            and target.get('bufferPoolMiB', 1024) < target.get('memoryMiB', 2048), 'Invalid reviewed MySQL memory limits')
    require(all(integer(value.get(key, default), 1) for key, default in
                [('operationTimeoutSeconds', 7200), ('appStartupTimeoutSeconds', 3600)]), 'Deadlines must be positive seconds')
    if target.get('network'):
        require(re.fullmatch(NAME, target['network']), 'Unsafe target network')
    for writer in value.get('writerContainers', []):
        require(set(writer) == {'name', 'id'} and re.fullmatch(NAME, writer['name']) and digest(writer['id'])
                and writer['id'] != source['containerId'], 'Exact separate writer container identity required')
    require(all(integer(port, 1024) and port <= 65535 for port in value.get('writerPorts', [])), 'Invalid host writer port')
    capacity = value['capacity']
    require(set(capacity) == {'reserveHostBytes', 'reserveDockerBytes', 'requiredDatabaseBytes', 'maxBackupBytes'}
            and all(integer(amount) for amount in capacity.values()) and capacity['requiredDatabaseBytes'] > 0,
            'Explicit measured disk budget required')
    require(value.get('preservationPolicy') != 'capture-current' or capacity['maxBackupBytes'] > 0, 'Backup capture needs a positive bounded budget')
    require(not source_less or capacity['maxBackupBytes'] == 0, 'Source-less installation has no previous database backup budget')
    private_file(value['privateAccounts'])
    require(not Path(value['privateAccounts']).resolve().is_relative_to(Path(value['release']).resolve()),
            'Private credentials must be stored outside the release')
    return value


def retired_observation(config):
    source = config['source']
    require(digest(source['retirementReceiptSha256']) and sha(source['retirementReceipt']) == source['retirementReceiptSha256'],
            'Reviewed retirement evidence changed')
    receipt = read(source['retirementReceipt'])
    require(receipt['schemaVersion'] == 1 and receipt['kind'] == 'airbob-experiment-database-retirement' and
            receipt['state'] == 'REMOVED' and receipt['datasetId'] == source['datasetId'] and
            receipt['containerId'] == source['containerId'] and receipt['containerName'] == source['container'] and
            receipt['volumeName'] == source['volume'] and receipt['mysqlServerUuid'] == source['mysqlServerUuid'] and
            receipt['preservedDumpSha256'] == source['dumpSha256'] and receipt['preservedFingerprintSha256'] == source['fingerprintSha256']
            and receipt['deletion']['containerAbsentObserved'] is True and receipt['deletion']['volumeAbsentObserved'] is True,
            'Retirement evidence does not identify the preserved experiment')
    names = set(docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
    require(source['containerId'] not in existing('container') and source['container'] not in names
            and source['volume'] not in existing('volume'), 'Retired source container/name/volume exists again')
    observer = config.get('diskObserver', {})
    require(set(observer) == {'name', 'id'} and re.fullmatch(NAME, observer['name']) and digest(observer['id']),
            'Retired targets need an explicitly identified live local disk observer')
    observed = inspect('container', observer['name'])
    require(observed['Id'] == observer['id'] and observed['State']['Running'] is True, 'Disk observer identity changed')
    free = int(docker('exec', observer['id'], 'df', '-P', '-k', '/').splitlines()[-1].split()[3]) * 1024
    return {'alreadyRetired': True, 'containerId': source['containerId'], 'container': source['container'],
            'mysql': {'uuid': source['mysqlServerUuid']}, 'volume': {'Name': source['volume']}, 'writers': [],
            'mysqlAllocatedBytes': 0, 'dockerFreeBytes': free,
            'retirementReceiptSha256': source['retirementReceiptSha256'],
            'absenceReobserved': {'container': True, 'volume': True}}


def source_observation(config):
    source = config['source']
    if 'retirementReceipt' in source:
        return None, retired_observation(config)
    info = inspect('container', source['container'])
    require(info['Id'] == source['containerId'] and info['State']['Running'] is True, 'Existing container identity/state differs')
    mounts = [mount for mount in info['Mounts'] if mount['Destination'] == '/var/lib/mysql']
    require(len(mounts) == 1 and mounts[0]['Type'] == 'volume' and mounts[0]['Name'] == source['volume'],
            'Source must own the exact named MySQL data volume')
    volume = inspect('volume', source['volume'])
    require(volume['Driver'] == 'local' and not volume.get('Options'),
            'Only a local Docker-managed data volume without remote/bind driver options is allowed')
    labels = info['Config'].get('Labels') or {}
    compose = labels.get('com.docker.compose.project') == 'airbob' and labels.get('com.docker.compose.service') == 'mysql'
    require(compose or labels.get('airbob.dataset.id') == source['datasetId'] or
            (volume.get('Labels') or {}).get('airbob.dataset.id') == source['datasetId'],
            'Source is not an identified Airbob experiment')
    users = docker('ps', '-aq', '--no-trunc', '--filter', 'volume=' + source['volume']).decode().splitlines()
    require(users == [source['containerId']], 'Source volume is mounted by another container')
    db = Database(source['containerId'])
    identity = db.identity()
    require(identity['version'] == '8.4.11' and identity['uuid'] == source['mysqlServerUuid'], 'Existing MySQL identity differs')
    schemas = {row['Database'] for row in db.rows('SHOW DATABASES')} - SYSTEM_SCHEMAS
    require(schemas == {'airbobdb'}, 'Refusing a source with additional business/unknown databases')
    # The sealed fingerprint covers base-table rows/DDL, not other schema objects.
    objects = {
        'nonBaseTables': db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='airbobdb' AND table_type<>'BASE TABLE'"),
        'routines': db.scalar("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema='airbobdb'"),
        'events': db.scalar("SELECT COUNT(*) FROM information_schema.events WHERE event_schema='airbobdb'"),
        'triggers': db.scalar("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema='airbobdb'"),
    }
    require(all(integer(count) and count == 0 for count in objects.values()),
            'Source contains unsupported views, routines, events, or triggers; preservation covers base-table rows/DDL only')
    addresses = source_account_scope(source, db)
    writers = []
    for writer in config.get('writerContainers', []):
        observed = inspect('container', writer['name'])
        require(observed['Id'] == writer['id'], 'Writer container identity differs')
        writers.append({'name': writer['name'], 'id': writer['id'], 'running': observed['State']['Running']})
    require(not any(port_open(port) for port in config.get('writerPorts', [])),
            'Stop the explicitly listed host JVM/listener before preflight; host processes are never killed implicitly')
    used = int(docker('exec', source['containerId'], 'du', '-s', '-B1', '/var/lib/mysql').split()[0])
    free = int(docker('exec', source['containerId'], 'df', '-B1', '--output=avail', '/var/lib/mysql').splitlines()[-1])
    return info, {'containerId': info['Id'], 'container': source['container'], 'mysql': identity,
                  'volume': {key: volume[key] for key in ('Name', 'Driver', 'Mountpoint', 'CreatedAt')},
                  'unsupportedSchemaObjects': objects,
                  'accountAddressScope': addresses,
                  'writers': writers, 'mysqlAllocatedBytes': used, 'dockerFreeBytes': free}


def source_account_scope(source, db):
    """Permit only a source receipt's own verified aliases; never use incoming release members."""
    predicate = "email IS NULL OR email NOT LIKE '%@example.test'"
    count = db.scalar('SELECT COUNT(*) FROM member WHERE ' + predicate)
    require(integer(count) and count <= 3, 'Source contains non-experiment account addresses')
    if count == 0:
        return {'exampleTestAccountsOnly': True, 'verifiedRepresentativeAccounts': []}
    require(count == 3 and str(source.get('datasetId', '')).startswith('global-growth-b-'),
            'Source canonical aliases require an authenticated global B source restore receipt')
    path = source.get('restoreReceipt')
    expected_sha = source.get('restoreReceiptSha256')
    require(isinstance(path, str) and Path(path).is_absolute() and digest(expected_sha)
            and sha(regular(path)) == expected_sha, 'Source representative restore receipt bytes differ')
    receipt = read(path)
    require(receipt.get('datasetId') == source['datasetId']
            and receipt.get('dumpSha256') == source.get('dumpSha256') and digest(source.get('dumpSha256'))
            and receipt.get('mysqlServerUuid') == source.get('mysqlServerUuid')
            and (receipt.get('allRowsAndDdlEqual') is True or receipt.get('fullRowAndDdlParity') is True),
            'Source representative receipt is not bound to this dataset, dump, and MySQL UUID')
    login = receipt.get('preparation', {}).get('accountLogins', {})
    proof = login.get('representatives', {})
    require(login.get('passed') is True and login.get('identityAndLogoutVerified') is True
            and login.get('crossCredentialRejected') is True and proof.get('passed') is True
            and proof.get('accountCount') == 3 and proof.get('identityRoleAndOwnershipVerified') is True
            and proof.get('emailMutationAfterRestore') is False,
            'Source canonical aliases lack successful normal-login representative proof')
    rows = proof.get('accounts')
    require(isinstance(rows, list) and len(rows) == 3, 'Source representative mapping is incomplete')
    seen_ids, seen_keys = set(), set()
    expected, normalized = [], []
    for row in rows:
        require(isinstance(row, dict), 'Source representative mapping is malformed')
        key, member = row.get('key'), row.get('memberId')
        require(key in SOURCE_REPRESENTATIVES and key not in seen_keys and integer(member, 1) and member not in seen_ids
                and (row.get('email'), row.get('role')) == SOURCE_REPRESENTATIVES[key] and row.get('status') == 'ACTIVE',
                'Source representative mapping has an unexpected alias, role, or member ID')
        seen_keys.add(key); seen_ids.add(member)
        expected.append({field: row[field] for field in ('memberId', 'email', 'role', 'status')})
        normalized.append({field: row[field] for field in ('key', 'memberId', 'email', 'role', 'status')})
    observed = db.rows('SELECT id memberId,email,role,status FROM member WHERE ' + predicate + ' ORDER BY id')
    require(observed == sorted(expected, key=lambda row: row['memberId']),
            'Actual source canonical members differ from its own authenticated restore receipt')
    return {'exampleTestAccountsOnly': False, 'sourceRestoreReceiptSha256': expected_sha,
            'verifiedRepresentativeAccounts': sorted(normalized, key=lambda row: row['memberId'])}


def account_environment(config):
    """Stable preflight binding derived from the actual target coordinates, not producer qualification."""
    return config['mode'] + ':' + config['target']['container'] + ':' + config['datasetId']


def public_account_union(bundle):
    return contract.public_account_union(bundle)


def port_open(port):
    with socket.socket() as client:
        client.settimeout(0.5)
        return client.connect_ex(('127.0.0.1', port)) == 0


def check_capacity(capacity, *, host_free, docker_free, reclaimable=0, backup=0):
    """Docker Desktop logical free space does not imply physical host free space."""
    require(host_free >= capacity['reserveHostBytes'] + backup +
            max(0, capacity['requiredDatabaseBytes'] - reclaimable),
            'Insufficient physical host space for the reviewed database and preservation budget')
    require(docker_free + reclaimable >= capacity['requiredDatabaseBytes'] + capacity['reserveDockerBytes'],
            'Insufficient Docker space for the reviewed database and retained reserve')


def preservation(config):
    source = config['source']
    for name, key in [('restoreReceipt', 'restoreReceiptSha256'), ('fingerprint', 'fingerprintSha256'), ('dump', 'dumpSha256')]:
        require(Path(source[name]).is_absolute() and digest(source[key]) and sha(source[name]) == source[key], 'Preserved evidence bytes differ')
    receipt, baseline = read(source['restoreReceipt']), read(source['fingerprint'])
    require(receipt['datasetId'] == source['datasetId'] and receipt['dumpSha256'] == source['dumpSha256'] and
            receipt['mysqlServerUuid'] == source['mysqlServerUuid'] and
            (receipt.get('allRowsAndDdlEqual') is True or receipt.get('fullRowAndDdlParity') is True),
            'Existing archive lacks a verified restore/identity receipt')
    validate_fingerprint(baseline)
    if 'fingerprint' in receipt:
        require(receipt['fingerprint'] == baseline, 'Preserved fingerprint differs from restore proof')
    if 'tableRows' in receipt:
        require(receipt['tableRows'] == {key: value['rows'] for key, value in baseline['tables'].items()},
                'Preserved row counts differ from restore proof')
    return baseline, inspect_dump(source['dump'])


def changed_tables(before, after):
    require(set(before['tables']) == set(after['tables']) and
            all(before['tables'][name]['ddlSha256'] == after['tables'][name]['ddlSha256'] for name in before['tables']),
            'Existing experiment DDL differs from its preserved V28 baseline')
    return sorted(name for name in before['tables'] if before['tables'][name] != after['tables'][name])


def build_preflight(config, output, runtime):
    source, target = config['source'], config['target']
    source_info, observed = source_observation(config)
    for key in ('image', 'redisImage'):
        inspect('image', target[key])
    if target.get('network'):
        inspect('network', target['network'])
    all_names = set(docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
    require(target['container'] == source['container'] or target['container'] not in all_names, 'Target container already exists')
    require(target['volume'] == source['volume'] or target['volume'] not in existing('volume'), 'Target volume already exists')
    baseline, old_dump = preservation(config)
    current = None if observed.get('alreadyRetired') else fingerprint(runtime, Path(config['release']), source_info,
                    output / 'source-fingerprint.json', config.get('operationTimeoutSeconds', 7200))
    differences = [] if current is None else changed_tables(baseline, current)
    require(not differences or config['preservationPolicy'] != 'require-current-match',
            'Current rows differ from the preserved archive; review an explicit capture-current or discard-changes policy')
    capacity = config['capacity']
    host_free = shutil.disk_usage(output).free
    current_measurement = read(Path(config['release']) / 'runtime-database-measurement.json')
    measured = current_measurement['allocatedTablespaceBytes']
    require(integer(measured, 1) and capacity['requiredDatabaseBytes'] >= measured,
            'Database budget is below measured current-inventory tablespaces')
    require(current is not None or config['preservationPolicy'] == 'require-current-match',
            'Previously retired input uses its preserved evidence, without a new discard/capture policy')
    backup = capacity['maxBackupBytes'] if config['preservationPolicy'] == 'capture-current' else 0
    check_capacity(capacity, host_free=host_free, docker_free=observed['dockerFreeBytes'],
                   reclaimable=observed['mysqlAllocatedBytes'], backup=backup)
    return {'schemaVersion': 1, 'kind': 'global-b-restore-preflight', 'mode': config['mode'],
            'state': 'PREFLIGHT_READY', 'configuration': config, 'source': observed, 'sourceFingerprint': current,
            'preservedDump': old_dump, 'changedTablesFromPreservedDump': differences,
            'preservationPolicy': config['preservationPolicy'], 'additionalBackupBudgetBytes': backup,
            'hostFreeBytes': host_free, 'capacity': capacity,
            'plannedOrder': ['stop the exact writer containers', 'freeze and recheck the existing MySQL identity and complete fingerprint',
                            'prove preserved dump or capture current compressed backup', 'remove exact source container and volume; observe absence',
                            'create one empty target MySQL', 'stream gzip; compare every row and DDL hash',
                            'apply private credentials', 'seed missing FREE days; verify owners/readiness/real login',
                            'stop qualification app and freeze prepared DB for service cutover'],
            'createdAt': dt.datetime.now(dt.timezone.utc).isoformat()}


def local_engine_identity(*, include_configured_endpoint=False):
    context = docker('context', 'show').decode().strip()
    value = json.loads(docker('context', 'inspect', context))[0]
    endpoint = os.environ.get('DOCKER_HOST') or value['Endpoints']['docker']['Host']
    require(endpoint.startswith('unix://'), 'Fresh installation requires the local Docker engine')
    info = json.loads(docker('info', '--format', '{{json .}}'))
    require(isinstance(info.get('ID'), str) and info['ID'] and isinstance(info.get('DockerRootDir'), str)
            and info['DockerRootDir'].startswith('/'), 'Docker engine identity is incomplete')
    result = {'context': context, 'endpoint': endpoint, 'engineId': info['ID'], 'dockerRootDir': info['DockerRootDir']}
    if include_configured_endpoint:
        result['configuredEndpoint'] = value['Endpoints']['docker']['Host']
    return result


def require_fresh_containers(observer_id, target_id=None):
    allowed = {observer_id} | ({target_id} if target_id else set())
    require(existing('container') == allowed,
            'Fresh installation requires no other running or stopped containers besides its observer and new target')


def fresh_observation(config):
    """Only reads an already-created, pinned, network-isolated disk observer."""
    engine = local_engine_identity()
    target, observer = config['target'], config['diskObserver']
    images = {key: inspect('image', target[key])['Id'] for key in ('image', 'redisImage')}
    observed = inspect('container', observer['name'])
    labels = observed['Config'].get('Labels') or {}
    claim = labels.get('airbob.restore.claim', '')
    require(observed['Id'] == observer['id'] and observed['Name'] == '/' + observer['name']
            and observed['State']['Running'] is True and observed['Image'] == images['redisImage'],
            'Fresh disk observer identity/image changed')
    require(labels.get('airbob.restore.temporary') == 'true' and
            labels.get('airbob.restore.observer') == FRESH_OPERATION and re.fullmatch(r'[0-9a-f]{32}', claim),
            'Fresh disk observer must have an explicit task ownership claim')
    require(observed['HostConfig']['ReadonlyRootfs'] is True and observed['HostConfig']['NetworkMode'] == 'none'
            and all(item['Type'] == 'tmpfs' for item in observed['Mounts'])
            and observed['Config']['Entrypoint'] == ['/bin/sh'] and observed['Config']['Cmd'] == OBSERVER_COMMAND,
            'Fresh disk observer must be an idle, read-only, network-none shell without data volumes')
    require_fresh_containers(observer['id'])
    names = set(docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
    require(target['container'] not in names and target['volume'] not in existing('volume'),
            'Fresh target container and data volume must both be absent')
    require(not port_open(target['mysqlPort']) and not any(port_open(port) for port in config.get('writerPorts', [])),
            'Fresh target or declared host writer port is already in use')
    if target.get('network'):
        inspect('network', target['network'])
    free = int(docker('exec', observer['id'], 'df', '-P', '-k', '/').splitlines()[-1].split()[3]) * 1024
    require(free >= 0, 'Docker free-space observation is invalid')
    return {'engine': engine, 'observer': dict(observer, imageId=observed['Image'], claim=claim),
            'targetImages': images, 'targetAbsence': {'container': target['container'], 'volume': target['volume'],
                                                    'containerAbsent': True, 'volumeAbsent': True},
            'dockerFreeBytes': free}


def discard_receipt(config):
    """Authenticate an actual user-discard record; this is not preserved-source evidence."""
    require(config.get('operation') == OCI_DISCARD_OPERATION and config['mode'] == 'oci'
            and not {'source', 'preservationPolicy', 'writerContainers'} & set(config),
            'OCI discard evidence requires its explicit operation')
    path = regular(config['discardReceipt'])
    require(sha(path) == config['discardReceiptSha256'], 'Reviewed OCI discard receipt bytes changed')
    value = read(path)
    require(value.get('schemaVersion') == 1 and value.get('kind') == 'oci-database-discarded-by-user'
            and value.get('state') == 'DISCARDED_BY_EXPLICIT_USER_REQUEST'
            and value.get('authorization', {}).get('mode') == 'explicit-user-discard-without-new-backup'
            and value.get('scope', {}).get('newBackupCreated') is False
            and value.get('authorization', {}).get('oldArchiveRequiredForDeletion') is False
            and value.get('onlyRequestedContainerAndVolumeRemoved') is True,
            'Receipt does not prove the explicitly requested OCI database discard')
    before, after = value['before'], value['after']
    def engine(raw):
        require(all(isinstance(raw.get(key), str) and raw[key] for key in
                    ('context', 'configuredEndpoint', 'effectiveEndpoint', 'id', 'dockerRootDir'))
                and raw['configuredEndpoint'].startswith('unix://') and raw['effectiveEndpoint'].startswith('unix://')
                and raw['dockerRootDir'].startswith('/'), 'Discard receipt requires an exact local-unix engine')
        return {'context': raw['context'], 'configuredEndpoint': raw['configuredEndpoint'],
                'endpoint': raw['effectiveEndpoint'], 'engineId': raw['id'], 'dockerRootDir': raw['dockerRootDir']}
    expected_engine = engine(before['engine'])
    require(engine(after['engine']) == expected_engine, 'Discard engine changed during removal')
    source, volume = before['source'], before['sourceVolume']
    require(digest(source.get('id')) and re.fullmatch(NAME, source.get('name', ''))
            and source.get('composeService') == 'mysql' and source.get('composeProject') == 'airbob'
            and re.fullmatch(NAME, volume.get('Name', '')) and volume.get('Driver') == 'local'
            and not volume.get('Options') and volume.get('mountingContainerIds') == [source['id']],
            'Discard receipt must identify the sole Airbob MySQL data volume')
    mounts = [row for row in source['mounts'] if row.get('Destination') == '/var/lib/mysql']
    require(len(mounts) == 1 and mounts[0].get('Type') == 'volume' and mounts[0].get('Name') == volume['Name'],
            'Discard receipt source data mount differs')
    old_uuid = before['database']['mysql']['uuid']
    require(isinstance(old_uuid, str) and str(uuid.UUID(old_uuid)) == old_uuid
            and all(after.get('absence', {}).get(key) is True for key in
                    ('oldContainerIdAbsent', 'oldContainerNameAbsent', 'oldVolumeNameAbsent')),
            'Discard receipt lacks observed old resource absence or MySQL UUID')
    def inventory(raw):
        rows, ids, names = [], set(), set()
        for row in raw['containers']:
            require(digest(row.get('id')) and row['id'] not in ids
                    and re.fullmatch(NAME, row.get('name', '')) and row['name'] not in names
                    and re.fullmatch(r'sha256:[0-9a-f]{64}', row.get('imageId', ''))
                    and isinstance(row.get('image'), str) and row['image'] and type(row.get('running')) is bool,
                    'Discard resource inventory is incomplete or ambiguous')
            ids.add(row['id']); names.add(row['name'])
            rows.append({key: row[key] for key in ('id', 'name', 'imageId', 'image', 'running')})
        volumes = raw['volumeNames']
        require(isinstance(volumes, list) and len(volumes) == len(set(volumes))
                and all(isinstance(name, str) and re.fullmatch(NAME, name) for name in volumes),
                'Discard volume inventory is incomplete or ambiguous')
        return sorted(rows, key=lambda row: row['id']), sorted(volumes)
    previous, previous_volumes = inventory(before['inventory'])
    remaining, remaining_volumes = inventory(after['inventory'])
    require({row['id'] for row in remaining} == {row['id'] for row in previous} - {source['id']}
            and source['id'] in {row['id'] for row in previous}
            and source['name'] not in {row['name'] for row in remaining}
            and volume['Name'] in previous_volumes
            and set(remaining_volumes) == set(previous_volumes) - {volume['Name']},
            'Discard inventory does not show exactly the old MySQL container and volume removed')
    writers = before['writers']
    require(isinstance(writers, list) and len(writers) == 2
            and {row.get('composeService') for row in writers} == {'app', 'debezium'}
            and all(row.get('composeProject') == 'airbob' for row in writers),
            'Discard evidence must identify the stopped application and Debezium writers')
    writer_ids = value.get('stoppedWriterIds')
    require(isinstance(writer_ids, list) and len(writer_ids) == 2 and len(set(writer_ids)) == 2
            and set(writer_ids) == {row['id'] for row in writers}, 'Stopped writer identities are incomplete')
    current_by_id = {row['id']: row for row in remaining}
    for writer in writers:
        current = current_by_id.get(writer['id'], {})
        require(all(current.get(key) == writer.get(key) for key in ('id', 'name', 'imageId', 'image'))
                and current.get('running') is False, 'Discard receipt does not retain each exact stopped writer')
    return {'receiptSha256': config['discardReceiptSha256'], 'engine': expected_engine,
            'discardedDatabase': {'containerId': source['id'], 'container': source['name'],
                                  'volume': volume['Name'], 'mysqlServerUuid': old_uuid},
            'stoppedWriterIds': sorted(writer_ids), 'remainingContainers': remaining,
            'remainingVolumeNames': remaining_volumes}


def mysql_container(info):
    """Reject an additional recognizable MySQL-family service, including stopped instances."""
    config = info['Config']
    command_words = [*(config.get('Entrypoint') or []), *(config.get('Cmd') or [])]
    executables = {Path(str(word)).name.lower() for word in command_words}
    return ((config.get('Labels') or {}).get('com.docker.compose.service') == 'mysql'
            or any(row.get('Destination') == '/var/lib/mysql' for row in info.get('Mounts', []))
            or bool({'3306/tcp', '33060/tcp'} & set(config.get('ExposedPorts') or {}))
            or bool({'mysqld', 'mariadbd', 'mysqld_safe'} & executables)
            or re.search(r'(^|/)(mysql|mariadb|percona(?:-server)?)(:|@|$)', config.get('Image', '').lower()) is not None)


def discard_observation(config, *, target_id=None, target_claim=None):
    """Read the OCI engine and preserved services; never stop or delete a resource."""
    evidence = discard_receipt(config)
    engine = local_engine_identity(include_configured_endpoint=True)
    require(engine == evidence['engine'], 'OCI discard engine identity changed')
    expected = {row['id']: row for row in evidence['remainingContainers']}
    ids = existing('container')
    require(ids == set(expected) | ({target_id} if target_id else set()),
            'OCI container inventory changed or an additional business MySQL exists')
    infos = {identifier: inspect('container', identifier) for identifier in sorted(ids)}
    for identifier, row in expected.items():
        info = infos[identifier]
        observed = {'id': info['Id'], 'name': info['Name'].removeprefix('/'), 'imageId': info['Image'],
                    'image': info['Config']['Image'], 'running': info['State']['Running']}
        require(observed == row, 'Preserved OCI service or stopped writer identity/state changed')
        require(not mysql_container(info), 'An additional business MySQL container is present')
    target, old = config['target'], evidence['discardedDatabase']
    volumes = existing('volume')
    require(volumes == set(evidence['remainingVolumeNames']) | ({target['volume']} if target_id else set()),
            'OCI volume inventory changed or the target data volume already exists')
    require(old['containerId'] not in ids, 'Discarded MySQL container exists again')
    names = {info['Name'].removeprefix('/') for info in infos.values()}
    images = {key: inspect('image', target[key])['Id'] for key in ('image', 'redisImage')}
    if target_id:
        require(target_id not in expected, 'OCI target must be a newly created container')
        info = infos[target_id]
        volume = inspect('volume', target['volume'])
        mounts = [row for row in info['Mounts'] if row.get('Destination') == '/var/lib/mysql']
        require(info['Id'] == target_id and info['Name'] == '/' + target['container'] and info['Image'] == images['image']
                and (info['Config'].get('Labels') or {}).get('airbob.dataset.id') == config['datasetId']
                and len(mounts) == 1 and mounts[0].get('Type') == 'volume' and mounts[0].get('Name') == target['volume']
                and isinstance(target_claim, str) and re.fullmatch(r'[0-9a-f]{32}', target_claim)
                and volume['Name'] == target['volume'] and volume['Driver'] == 'local' and not volume.get('Options')
                and (volume.get('Labels') or {}).get('airbob.dataset.id') == config['datasetId']
                and (volume.get('Labels') or {}).get('airbob.restore.claim') == target_claim,
                'The new OCI target identity or private volume claim changed')
        users = docker('ps', '-aq', '--no-trunc', '--filter', 'volume=' + target['volume']).decode().splitlines()
        require(users == [target_id], 'The new OCI target data volume is shared')
    else:
        require(old['container'] not in names and old['volume'] not in volumes
                and target['container'] not in names and target['volume'] not in volumes,
                'Discarded resource or target namespace already exists')
        require(not port_open(target['mysqlPort']), 'OCI target port is already in use')
    require(not any(port_open(port) for port in config.get('writerPorts', [])), 'Declared OCI host writer port is in use')
    observer = config['diskObserver']
    require(observer['id'] in expected and observer['name'] == expected[observer['id']]['name']
            and expected[observer['id']]['running'] is True, 'OCI disk observer must be an exact retained running service')
    network = None
    if target.get('network'):
        current_network = inspect('network', target['network'])
        network = {'name': current_network['Name'], 'id': current_network['Id']}
    free = int(docker('exec', observer['id'], 'df', '-P', '-k', '/').splitlines()[-1].split()[3]) * 1024
    require(free >= 0, 'OCI Docker free-space observation is invalid')
    return dict(evidence, observer=observer, targetImages=images, network=network, dockerFreeBytes=free,
                targetAbsence={'container': target['container'], 'volume': target['volume'],
                               'containerAbsent': target_id is None, 'volumeAbsent': target_id is None})


def fresh_input_bindings(config):
    # Only hashes are recorded; private account contents never enter the plan.
    return {'privateAccountsSha256': sha(private_file(config['privateAccounts'])),
            'appJarSha256': sha(config['appJar']), 'restoreScriptSha256': sha(Path(__file__)),
            'runtimeHelperSha256': sha(Path(__file__).resolve().parents[1] / 'infra/aws/scripts/growth_b_runtime.py'),
            'contractScriptSha256': sha(Path(validate.__code__.co_filename))}


def discard_input_bindings(config):
    return fresh_input_bindings(config) | {'discardReceiptSha256': sha(regular(config['discardReceipt']))}


def verify_discard_runtime(plan, current):
    binding = plan.get('hostRuntimeQualification', {})
    require(isinstance(binding.get('path'), str) and Path(binding['path']).is_absolute()
            and digest(binding.get('sha256')) and sha(regular(binding['path'])) == binding['sha256'],
            'Reviewed OCI preflight runtime evidence changed')
    previous = read(binding['path'])
    require(previous.get('consumerRuntimePassed') is True and current.get('consumerRuntimePassed') is True
            and previous.get('passed') is True and current.get('passed') is True
            and all(previous[key] == current[key] for key in
                    ('hostJavaTools', 'qualificationSourceSha256', 'fixedCasesSha256', 'scope', 'python'))
            and previous['java']['tzdbFile'] == current['java']['tzdbFile']
            and previous['consumerReleaseBindings']['files'] == current['consumerReleaseBindings']['files'],
            'Qualified OCI runtime changed after preflight')


def fresh_capacity(config, output, observed):
    capacity = config['capacity']
    measured = read(Path(config['release']) / 'runtime-database-measurement.json')['allocatedTablespaceBytes']
    require(integer(measured, 1) and capacity['requiredDatabaseBytes'] >= measured,
            'Database budget is below measured current-inventory tablespaces')
    host_free = shutil.disk_usage(output).free
    check_capacity(capacity, host_free=host_free, docker_free=observed['dockerFreeBytes'], reclaimable=0, backup=0)
    return host_free


def build_fresh_preflight(config, output):
    observed = fresh_observation(config)
    host_free = fresh_capacity(config, output, observed)
    return {'schemaVersion': 1, 'kind': 'global-b-fresh-install-preflight', 'mode': 'local',
            'operation': FRESH_OPERATION, 'state': 'PREFLIGHT_READY', 'configuration': config,
            'environment': observed, 'inputBindings': fresh_input_bindings(config),
            'hostFreeBytes': host_free, 'capacity': config['capacity'], 'reclaimableBytes': 0,
            'plannedOrder': ['recheck sealed inputs, local engine, observer, empty target namespace and disk budgets',
                            'claim a new data volume and create the sole empty MySQL target',
                            'stream the verified gzip; compare every row and DDL hash',
                            'run an optional trusted internal baseline callback before preparation',
                            'apply private credentials; seed missing FREE days and verify owners/readiness/real login',
                            'stop the qualification app and retain the frozen prepared DB'],
            'createdAt': dt.datetime.now(dt.timezone.utc).isoformat()}


def build_discard_preflight(config, output):
    observed = discard_observation(config)
    host_free = fresh_capacity(config, output, observed)
    return {'schemaVersion': 1, 'kind': 'global-b-oci-discard-install-preflight', 'mode': 'oci',
            'operation': OCI_DISCARD_OPERATION, 'state': 'PREFLIGHT_READY', 'configuration': config,
            'environment': observed, 'inputBindings': discard_input_bindings(config),
            'hostFreeBytes': host_free, 'capacity': config['capacity'], 'reclaimableBytes': 0,
            'plannedOrder': ['recheck actual user-discard receipt, unchanged OCI engine and retained stopped writers',
                            'reobserve the absent old DB and empty target namespace; recheck runtime, inputs and capacity',
                            'claim one new MySQL volume without deleting or stopping an existing service',
                            'stream gzip and compare every row and DDL hash',
                            'capture optional trusted baseline while frozen, before private credentials and FREE preparation',
                            'verify normal logins, stop the qualification app and retain the frozen new DB'],
            'createdAt': dt.datetime.now(dt.timezone.utc).isoformat()}


def acquire_fresh_run_lock(runtime):
    # Reuse the verified runner's exact lock path and retained-generation guard.
    source = Path(runtime) / 'tools/growth_streaming.py'
    spec = importlib.util.spec_from_file_location('_growth_b_fresh_streaming', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.acquire_run_lock()


def streaming(command_line, *, source=None, destination=None, timeout, byte_limit=None, reserve_bytes=0):
    """At most one bounded plaintext buffer; both failure paths retain their database."""
    require((source is None) != (destination is None), 'Select an import or compressed capture')
    process = subprocess.Popen(command_line, stdin=subprocess.PIPE if source else subprocess.DEVNULL,
                               stdout=subprocess.PIPE if destination else subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    timer = threading.Timer(timeout, process.kill)
    timer.start()
    try:
        if source:
            process.stdin.write(b'SET SESSION sql_log_bin=0;\n')
            with gzip.open(source, 'rb') as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                    process.stdin.write(chunk)
            process.stdin.close()
        else:
            with Path(destination).open('xb') as raw, gzip.GzipFile(filename='', fileobj=raw, mode='wb', mtime=0) as compressed:
                for chunk in iter(lambda: process.stdout.read(1024 * 1024), b''):
                    compressed.write(chunk)
                    require(raw.tell() <= byte_limit and shutil.disk_usage(Path(destination).parent).free >= reserve_bytes,
                            'Backup exceeded its reviewed disk budget; source retained')
        require(process.wait(timeout=30) == 0, 'Streaming operation failed or timed out')
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
            process.wait(timeout=30)
        if process.stdout:
            process.stdout.close()
        if process.stdin and not process.stdin.closed:
            process.stdin.close()


def restore_new_target(config, output, runtime, report, event, *, previous_server_uuid=None,
                       before_preparation=None, observer_id=None, expected_image_id=None, target_guard=None):
    """Common target restore; optional callbacks are trusted Python, never config commands."""
    target, private = config['target'], output / '.private'
    release = Path(config['release'])
    timeout = config.get('operationTimeoutSeconds', 7200)
    target_claim = create_target(config, output)
    new_info = inspect('container', target['container'])
    report['targetContainerId'] = new_info['Id']
    if expected_image_id is not None:
        require(new_info['Image'] == expected_image_id, 'Created MySQL image differs from the reviewed image')
    if observer_id:
        require_fresh_containers(observer_id, new_info['Id'])
    if target_guard is not None:
        target_guard(new_info['Id'], target_claim)
        report['targetVolumeClaim'] = target_claim
    db = Database(new_info['Id'])
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        try:
            if db.scalar('SELECT 1') == 1:
                break
        except (ValueError, subprocess.SubprocessError):
            pass
        time.sleep(1)
    else:
        raise ValueError('Target MySQL startup timed out')
    identity = db.identity()
    require(identity['version'] == '8.4.11' and str(uuid.UUID(identity['uuid'])) == identity['uuid']
            and identity['uuid'] != previous_server_uuid, 'New MySQL identity differs')
    require(db.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='airbobdb'") == 0, 'Target must be empty')
    if observer_id or target_guard is not None:
        require({row['Database'] for row in db.rows('SHOW DATABASES')} - SYSTEM_SCHEMAS == {'airbobdb'},
                'Fresh MySQL has an unexpected business schema')
    install_infrastructure_users(db, private)
    event('NEW_EMPTY_MYSQL_READY', mysqlServerUuid=identity['uuid'])
    streaming(db.command(), source=release / 'airbob-growth.sql.gz', timeout=timeout)
    db.execute('SET GLOBAL super_read_only=ON')
    observed = fingerprint(runtime, release, new_info, output / 'restored-fingerprint.json', timeout)
    require(observed == read(release / 'before-fingerprint.json'), 'Complete restored rows/DDL differ; credentials were not applied')
    event('ALL_ROWS_AND_DDL_VERIFIED', allRowsAndDdlEqual=True, fingerprint=observed)
    if before_preparation is not None:
        artifacts = before_preparation(config, output, runtime, new_info, db)
        require(isinstance(artifacts, dict) and artifacts, 'Before-preparation callback must return public evidence paths')
        bindings = {}
        for name, path in artifacts.items():
            require(isinstance(name, str) and re.fullmatch(r'[A-Za-z][A-Za-z0-9_-]{0,63}', name), 'Invalid callback evidence name')
            path = regular(path).resolve()
            require(path.is_relative_to(output.resolve()) and not path.is_relative_to(private.resolve()),
                    'Callback evidence must be public files inside this output, outside .private')
            bindings[name] = {'file': path.relative_to(output.resolve()).as_posix(), 'sha256': sha(path)}
        report['beforePreparationArtifacts'] = bindings
        write(output / 'restore.json', report)
    if observer_id:
        require_fresh_containers(observer_id, new_info['Id'])
    if target_guard is not None:
        target_guard(new_info['Id'], target_claim)
    db.execute('SET GLOBAL super_read_only=OFF; SET GLOBAL read_only=OFF')
    preparation = prepare_service(config, output, runtime, new_info, db)
    db.execute('SET GLOBAL super_read_only=ON')
    if observer_id or target_guard is not None:
        if observer_id:
            require_fresh_containers(observer_id, new_info['Id'])
        if target_guard is not None:
            target_guard(new_info['Id'], target_claim)
        require({row['Database'] for row in db.rows('SHOW DATABASES')} - SYSTEM_SCHEMAS == {'airbobdb'},
                'Fresh MySQL has an unexpected business schema after preparation')
    frozen = db.identity()['superReadOnly'] == 1
    require(frozen, 'Prepared database did not remain frozen')
    event('PREPARED_APP_STOPPED_DATABASE_FROZEN', preparation=preparation,
          serviceEnvironmentFile=str(private / 'service.env'), databaseFrozen=frozen)
    return report


def apply_plan(config, plan, output, runtime, *, before_preparation=None):
    source = config['source']
    report = {'schemaVersion': 1, 'kind': 'global-b-restore', 'state': 'APPLY_STARTED', 'datasetId': config['datasetId'],
              'dumpSha256': read(Path(config['release']) / 'consumer-manifest.json')['artifacts']['dump']['sha256'],
              'preservationPolicy': config['preservationPolicy'], 'source': plan['source'], 'plaintextTemporaryBytes': 0,
              'privateDirectory': str(output / '.private'), 'events': []}
    private = output / '.private'
    private.mkdir(mode=0o700)
    def event(state, **values):
        report.update(state=state, **values)
        report['events'].append({'state': state, 'at': dt.datetime.now(dt.timezone.utc).isoformat()})
        write(output / 'restore.json', report)
        print(json.dumps({'state': state}), flush=True)
    try:
        if 'retirementReceipt' in source:
            observed = retired_observation(config)
            preservation(config)
            require(observed['retirementReceiptSha256'] == plan['source']['retirementReceiptSha256'], 'Retirement changed after preflight')
            check_capacity(config['capacity'], host_free=shutil.disk_usage(output).free, docker_free=observed['dockerFreeBytes'])
            event('PREVIOUS_RETIREMENT_REOBSERVED', retirement=observed)
        else:
            remove_source(config, plan, output, runtime, report, event)
        return restore_new_target(config, output, runtime, report, event,
                                  previous_server_uuid=source['mysqlServerUuid'], before_preparation=before_preparation)
    except BaseException as error:
        if report.get('targetContainerId'):
            try:
                target_db = Database(report['targetContainerId'])
                target_db.execute('SET GLOBAL super_read_only=ON')
                report['databaseFrozenAfterFailure'] = target_db.identity()['superReadOnly'] == 1
            except BaseException as freeze_error:
                report['failureFreezeErrorType'] = type(freeze_error).__name__
        event('FAILED_RESOURCES_RETAINED', errorType=type(error).__name__)
        raise


def apply_fresh_plan(config, plan, output, runtime, *, before_preparation=None):
    require(config.get('operation') == FRESH_OPERATION and config['mode'] == 'local' and 'source' not in config,
            'Fresh application requires its explicit local operation without an old source')
    require(plan['kind'] == 'global-b-fresh-install-preflight' and plan['state'] == 'PREFLIGHT_READY'
            and plan['configuration'] == config, 'Reviewed fresh preflight configuration differs')
    report = {'schemaVersion': 1, 'kind': 'global-b-restore', 'operation': FRESH_OPERATION,
              'state': 'APPLY_STARTED', 'datasetId': config['datasetId'], 'plaintextTemporaryBytes': 0,
              'privateDirectory': str(output / '.private'), 'events': []}
    (output / '.private').mkdir(mode=0o700)
    def event(state, **values):
        report.update(state=state, **values)
        report['events'].append({'state': state, 'at': dt.datetime.now(dt.timezone.utc).isoformat()})
        write(output / 'restore.json', report)
        print(json.dumps({'state': state}), flush=True)
    try:
        # Re-read every incoming hash and the gzip CRC before creating resources.
        _, checks, _ = validate_inputs(config)
        report['dumpSha256'] = checks['airbob-growth.sql.gz']
        require(fresh_input_bindings(config) == plan['inputBindings'], 'Reviewed fresh input bytes or installer sources changed')
        observed = fresh_observation(config)
        require(all(observed[key] == plan['environment'][key]
                    for key in ('engine', 'observer', 'targetImages', 'targetAbsence')),
                'Reviewed fresh engine, observer, image, or target identity changed')
        host_free = fresh_capacity(config, output, observed)
        event('EMPTY_TARGET_REOBSERVED', environment=observed, hostFreeBytes=host_free)
        return restore_new_target(config, output, runtime, report, event,
                                  before_preparation=before_preparation, observer_id=config['diskObserver']['id'],
                                  expected_image_id=observed['targetImages']['image'])
    except BaseException as error:
        if report.get('targetContainerId'):
            try:
                target_db = Database(report['targetContainerId'])
                target_db.execute('SET GLOBAL super_read_only=ON')
                report['databaseFrozenAfterFailure'] = target_db.identity()['superReadOnly'] == 1
            except BaseException as freeze_error:
                report['failureFreezeErrorType'] = type(freeze_error).__name__
        event('FAILED_RESOURCES_RETAINED', errorType=type(error).__name__)
        raise


def apply_discard_plan(config, plan, output, runtime, *, before_preparation=None):
    require(config.get('operation') == OCI_DISCARD_OPERATION and config['mode'] == 'oci'
            and not {'source', 'preservationPolicy', 'writerContainers'} & set(config),
            'OCI post-discard application requires its explicit source-less operation')
    require(plan['kind'] == 'global-b-oci-discard-install-preflight' and plan['state'] == 'PREFLIGHT_READY'
            and plan['configuration'] == config, 'Reviewed OCI post-discard configuration differs')
    report = {'schemaVersion': 1, 'kind': 'global-b-restore', 'operation': OCI_DISCARD_OPERATION,
              'state': 'APPLY_STARTED', 'datasetId': config['datasetId'], 'plaintextTemporaryBytes': 0,
              'privateDirectory': str(output / '.private'), 'events': []}
    (output / '.private').mkdir(mode=0o700)
    def event(state, **values):
        report.update(state=state, **values)
        report['events'].append({'state': state, 'at': dt.datetime.now(dt.timezone.utc).isoformat()})
        write(output / 'restore.json', report)
        print(json.dumps({'state': state}), flush=True)
    stable = ('receiptSha256', 'engine', 'discardedDatabase', 'stoppedWriterIds', 'remainingContainers',
              'remainingVolumeNames', 'observer', 'targetImages', 'network')
    try:
        _, checks, _ = validate_inputs(config)
        report['dumpSha256'] = checks['airbob-growth.sql.gz']
        require(discard_input_bindings(config) == plan['inputBindings'], 'Reviewed OCI input bytes or installer sources changed')
        observed = discard_observation(config)
        require(all(observed[key] == plan['environment'][key] for key in (*stable, 'targetAbsence')),
                'Reviewed OCI engine, service, writer, image or target identity changed')
        host_free = fresh_capacity(config, output, observed)
        event('USER_DISCARD_AND_EMPTY_TARGET_REOBSERVED', environment=observed, hostFreeBytes=host_free,
              oldBackupRequired=False, oldResourcesRemovedByThisInstall=False)
        def target_guard(identifier, claim):
            current = discard_observation(config, target_id=identifier, target_claim=claim)
            require(all(current[key] == observed[key] for key in stable),
                    'OCI engine, preserved service, writer or image changed during target preparation')
        return restore_new_target(config, output, runtime, report, event,
            previous_server_uuid=observed['discardedDatabase']['mysqlServerUuid'],
            before_preparation=before_preparation, expected_image_id=observed['targetImages']['image'],
            target_guard=target_guard)
    except BaseException as error:
        if report.get('targetContainerId'):
            try:
                target_db = Database(report['targetContainerId'])
                target_db.execute('SET GLOBAL super_read_only=ON')
                report['databaseFrozenAfterFailure'] = target_db.identity()['superReadOnly'] == 1
            except BaseException as freeze_error:
                report['failureFreezeErrorType'] = type(freeze_error).__name__
        event('FAILED_RESOURCES_RETAINED', errorType=type(error).__name__)
        raise


def remove_source(config, plan, output, runtime, report, event):
    source, target = config['source'], config['target']
    private, release = output / '.private', Path(config['release'])
    timeout = config.get('operationTimeoutSeconds', 7200)
    info, observed = source_observation(config)
    require(observed['volume'] == plan['source']['volume'], 'Source volume changed after review')
    for writer in observed['writers']:
        if writer['running']:
            docker('stop', '--time', '30', writer['id'], timeout=60)
            require(inspect('container', writer['id'])['State']['Running'] is False, 'Writer did not stop')
    db = Database(source['containerId'])
    db.execute('SET GLOBAL super_read_only=ON')
    require(db.identity()['superReadOnly'] == 1, 'Source freeze did not take effect')
    current = fingerprint(runtime, release, info, output / 'frozen-source-fingerprint.json', timeout)
    require(current == plan['sourceFingerprint'], 'Source changed after preflight; retained frozen for a new review')
    require(db.scalar("SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() AND USER NOT IN ('event_scheduler','system user')") == 0,
            'An undeclared database client remains connected')
    baseline, old_dump = preservation(config)
    differences = changed_tables(baseline, current)
    require(differences == plan['changedTablesFromPreservedDump'], 'Reviewed source differences changed')
    event('SOURCE_FROZEN_AND_RECHECKED', sourceFingerprintSha256=sha(output / 'frozen-source-fingerprint.json'))
    if config['preservationPolicy'] == 'capture-current':
        backup = private / 'previous-airbob.sql.gz'
        capture = ['docker', 'exec', source['containerId'], 'sh', '-c',
                   'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --quick --skip-lock-tables '
                   '--hex-blob --set-gtid-purged=OFF --no-tablespaces --skip-comments --column-statistics=0 airbobdb']
        streaming(capture, destination=backup, timeout=timeout, byte_limit=config['capacity']['maxBackupBytes'],
                  reserve_bytes=config['capacity']['reserveHostBytes'])
        old_dump = inspect_dump(backup)
        after = fingerprint(runtime, release, info, output / 'after-preservation-fingerprint.json', timeout)
        require(after == current and db.identity()['superReadOnly'] == 1, 'Source changed during preservation')
        write(private / 'previous-fingerprint.json', current)
        report['preservedCurrentDump'] = dict(old_dump, file=str(backup), fingerprintFile=str(private / 'previous-fingerprint.json'))
    else:
        require(not differences or config['preservationPolicy'] == 'discard-changes', 'Unapproved source changes')
        report['reusedPreservedDump'] = dict(old_dump, file=source['dump'], fingerprintFile=source['fingerprint'])
        report['explicitlyDiscardedChangedTables'] = differences
    event('PRESERVATION_PROVED')
    # Re-read both gzip CRC and every release hash immediately before the irreversible boundary.
    validate_inputs(config)
    for key in ('image', 'redisImage'):
        inspect('image', target[key])
    final_info, final_observation = source_observation(config)
    require(final_observation['volume'] == plan['source']['volume'] and db.identity()['superReadOnly'] == 1,
            'Source identity/freeze changed before removal')
    check_capacity(config['capacity'], host_free=shutil.disk_usage(output).free,
                   docker_free=final_observation['dockerFreeBytes'], reclaimable=final_observation['mysqlAllocatedBytes'])
    docker('stop', '--time', '30', source['containerId'], timeout=60)
    docker('rm', source['containerId'])
    require(source['containerId'] not in existing('container'), 'Source container still exists')
    require(inspect('volume', source['volume'])['CreatedAt'] == plan['source']['volume']['CreatedAt'], 'Volume identity changed')
    docker('volume', 'rm', source['volume'])
    require(source['volume'] not in existing('volume'), 'Source volume still exists')
    event('SOURCE_CONTAINER_AND_VOLUME_REMOVED', deletion={'containerId': source['containerId'], 'volume': source['volume'],
          'containerAbsentObserved': True, 'volumeAbsentObserved': True})


def create_target(config, output):
    target, private = config['target'], output / '.private'
    require(target['volume'] not in existing('volume'), 'Target volume appeared during cutover')
    names = set(docker('ps', '-a', '--format', '{{.Names}}').decode().splitlines())
    require(target['container'] not in names, 'Target container appeared during cutover')
    values = {'MYSQL_ROOT_PASSWORD': secrets.token_hex(32), 'MYSQL_ROOT_HOST': '%', 'MYSQL_DATABASE': 'airbobdb',
              'MYSQL_USER': 'airbob', 'MYSQL_PASSWORD': secrets.token_hex(32), 'TZ': 'UTC',
              'DEBEZIUM_DATABASE_USER': 'debezium', 'DEBEZIUM_DATABASE_PASSWORD': secrets.token_hex(32),
              'LOGSTASH_JDBC_USER': 'logstash', 'LOGSTASH_JDBC_PASSWORD': secrets.token_hex(32)}
    env_file = private / 'mysql.env'
    env_file.write_text(''.join(key + '=' + value + '\n' for key, value in values.items()))
    env_file.chmod(0o600)
    service = dict(DB_ROOT_PASSWORD=values['MYSQL_ROOT_PASSWORD'], SPRING_DATASOURCE_USERNAME='airbob',
                   SPRING_DATASOURCE_PASSWORD=values['MYSQL_PASSWORD'],
                   **{key: value for key, value in values.items() if key.startswith(('DEBEZIUM_', 'LOGSTASH_'))})
    (private / 'service.env').write_text(''.join(key + '=' + value + '\n' for key, value in service.items()))
    (private / 'service.env').chmod(0o600)
    claim = secrets.token_hex(16)
    docker('volume', 'create', '--label', 'airbob.dataset.id=' + config['datasetId'],
           '--label', 'airbob.restore.claim=' + claim, target['volume'])
    volume = inspect('volume', target['volume'])
    require(volume['Driver'] == 'local' and not volume.get('Options') and
            (volume.get('Labels') or {}).get('airbob.restore.claim') == claim,
            'Target volume was concurrently claimed; it will not be mounted or removed')
    args = ['run', '-d', '--name', target['container'], '--label', 'airbob.dataset.id=' + config['datasetId'],
            '--memory', str(target.get('memoryMiB', 2048)) + 'm', '--env-file', str(env_file),
            '-p', '127.0.0.1:' + str(target['mysqlPort']) + ':3306',
            '--mount', 'type=volume,source=' + target['volume'] + ',target=/var/lib/mysql']
    if target.get('network'):
        args += ['--network', target['network'], '--network-alias', 'mysql']
    args += [target['image'], '--character-set-server=utf8mb4', '--collation-server=utf8mb4_0900_ai_ci',
             '--default-time-zone=+00:00', '--innodb-buffer-pool-size=' + str(target.get('bufferPoolMiB', 1024)) + 'M',
             '--innodb-redo-log-capacity=128M', '--server-id=1', '--log-bin=mysql-bin', '--binlog-format=ROW',
             '--binlog-row-image=FULL', '--gtid-mode=ON', '--enforce-gtid-consistency=ON', '--binlog-expire-logs-seconds=86400']
    docker(*args)
    return claim


def install_infrastructure_users(db, private):
    values = dict(line.split('=', 1) for line in (private / 'service.env').read_text().splitlines())
    # These generated hexadecimal passwords never appear in argv, receipts or query logs.
    for role, key in (('debezium', 'DEBEZIUM_DATABASE_PASSWORD'), ('logstash', 'LOGSTASH_JDBC_PASSWORD')):
        require(re.fullmatch(r'[0-9a-f]{64}', values[key]), 'Generated infrastructure password differs')
        sql = f"SET SESSION sql_log_bin=0; CREATE USER '{role}'@'%' IDENTIFIED BY '{values[key]}'; GRANT SELECT ON airbobdb.* TO '{role}'@'%';"
        if role == 'debezium':
            sql += "GRANT RELOAD,SHOW DATABASES,REPLICATION SLAVE,REPLICATION CLIENT ON *.* TO 'debezium'@'%';"
        db.execute(sql)


def prepare_service(config, output, runtime, info, db):
    """Reuse the qualified frozen Python sources through a local connection adapter."""
    sys.path.insert(0, str(runtime / 'tools'))
    accounts = importlib.import_module('growth_accounts')
    inventory = importlib.import_module('growth_inventory')
    runtime_module = importlib.import_module('growth_runtime')
    settings = importlib.import_module('growth_settings')
    release, private = Path(config['release']), output / '.private'
    environment = connection_environment(info)
    environment['AIRBOB_ETL_DB_URL'] += '&sessionVariables=sql_log_bin%3D0'
    environment['AIRBOB_ETL_BACKEND_ROOT'] = str(runtime / 'backend')
    credential_environment = account_environment(config)
    private_accounts = contract.validate_private_accounts(config['privateAccounts'], release, expected_environment=credential_environment)
    credential_ids = [row['memberId'] for row in private_accounts['credentials']]
    accounts.update_login_state(config['privateAccounts'], credential_ids, credential_environment,
                               usable=False, reason='Target preparation and normal-login qualification are in progress')
    environment['AIRBOB_GROWTH_CREDENTIALS_FILE'] = str(Path(config['privateAccounts']).resolve())
    environment['AIRBOB_GROWTH_STARTUP_TIMEOUT_SECONDS'] = str(config.get('appStartupTimeoutSeconds', 3600))
    inventory.verify_closed_ownership(db)
    owners = inventory.owned_fingerprint(db)
    representatives = accounts.verify_representative_accounts(db, read(release / 'representative-accounts.json'))
    accounts.apply_saved_credentials(runtime / 'runtime/bin/etl', release / 'profile.json', release / 'before-fingerprint.json',
            config['privateAccounts'], output / 'credentials-applied.json', environment, schema='airbobdb')
    redis = 'airbob-b-restore-redis-' + uuid.uuid4().hex[:12]
    created = False
    try:
        docker('run', '-d', '--name', redis, '--label', 'airbob.restore.temporary=true', '--memory=128m',
               '-p', '127.0.0.1::6379', config['target']['redisImage'], 'redis-server', '--save', '', '--appendonly', 'no')
        created = True
        redis_port = int(docker('port', redis, '6379/tcp').decode().strip().rsplit(':', 1)[1])
        app = runtime_module.App(config['appJar'], output, private, environment, redis_port, label='restored-b',
                settings={'reservation.inventory.startup.enabled': True, 'management.endpoint.health.probes.enabled': True})
        # The frozen App normally targets its benchmark schema; this adapter prepares the sole service schema.
        app.env['SPRING_DATASOURCE_URL'] = settings.utc_jdbc_url(environment['AIRBOB_ETL_DB_URL'])
        with app:
            readiness = app.request(app.client(), '/actuator/health/readiness', capture=False)
            require(readiness['response']['status'] == 'UP', 'Application readiness is not UP')
            horizon = inventory.verify_current_inventory(db)
            require(inventory.owned_fingerprint(db) == owners, 'Bootstrap changed HOLD/OCCUPIED ownership')
            login = accounts.qualify_account_logins(app, db, read(release / 'accounts.json'), output,
                    private_input=config['privateAccounts'], environment=credential_environment)
            startup_seconds = app.startup_seconds
        after = fingerprint(runtime, release, info, output / 'prepared-fingerprint.json', config.get('operationTimeoutSeconds', 7200))
        before = read(release / 'before-fingerprint.json')
        for name, values in before['tables'].items():
            require(after['tables'][name]['ddlSha256'] == values['ddlSha256'], 'Preparation changed schema')
            if name not in {'member', 'accommodation_inventory_day'}:
                require(after['tables'][name] == values, 'Preparation changed a non-live table: ' + name)
        require(after['tables']['member']['domainRowsSha256'] == before['tables']['member']['domainRowsSha256'],
                'Credential preparation changed member domain values')
        require(inventory.owned_fingerprint(db) == owners, 'Prepared inventory ownership changed')
        return {'passed': True, 'fullBaselineVerifiedBeforeCredentials': True, 'currentInventory': horizon,
                'ownerSha256BeforeAndAfter': owners, 'accountLogins': login, 'startupSeconds': startup_seconds,
                'representativeMapping': representatives, 'accountEnvironment': credential_environment,
                'accountPrivateDirectory': str(Path(config['privateAccounts']).resolve().parent),
                'privateAccountHandoffs': {'operator': 'representative-accounts.private.json',
                    'interviewer': 'demo-accounts.private.json', 'administrator': 'administrator.private.json'},
                'readinessVerified': True, 'applicationLeftRunning': False, 'temporarySessionsRemoved': True}
    except BaseException:
        accounts.update_login_state(config['privateAccounts'], credential_ids, credential_environment,
                                   usable=False, reason='Target preparation failed; service readiness is not qualified')
        raise
    finally:
        if created:
            # Fresh installation owns this disposable session Redis and its
            # anonymous volume; pre-existing Redis volumes are never targets.
            try:
                docker('rm', '-f', *(['-v'] if config.get('operation') in {FRESH_OPERATION, OCI_DISCARD_OPERATION} else []), redis)
            except BaseException:
                accounts.update_login_state(config['privateAccounts'], credential_ids, credential_environment,
                                           usable=False, reason='Target preparation cleanup did not complete')
                raise


def validate_inputs(config):
    release = Path(config['release'])
    require(sha(release / 'consumer-manifest.json') == config['consumerManifestSha256'] and
            sha(release / 'SHA256SUMS.json') == config['checksumsSha256'], 'Reviewed release trust anchors differ')
    manifest, checks, baseline = validate(release, config['datasetId'], Path(config['backendRoot']) / 'src/main/resources/db/migration',
            allow_small=config.get('allowSmall', False), expected_app_sha=sha(config['appJar']))
    contract.validate_private_accounts(config['privateAccounts'], release, expected_environment=account_environment(config))
    return manifest, checks, baseline


def main(mode='local', *, before_preparation=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--preflight', type=Path)
    parser.add_argument('--preflight-sha256')
    args = parser.parse_args()
    config = configuration(args.config, mode)
    validated = validate_inputs(config)
    output = args.output.absolute()
    require(not output.exists() and not output.is_symlink(), 'Output must be a new private directory')
    require(not output.is_relative_to(Path(config['release']).resolve()), 'Reports/private credentials must be outside the release')
    output.mkdir(mode=0o700)
    runtime = extract_runtime(Path(config['release']), output / 'runtime')
    host_runtime_path = output / 'host-runtime-qualification.json'
    host_runtime = qualify_runtime(Path(config['release']), runtime, host_runtime_path, expected_checks=validated[1])
    fresh = config.get('operation') == FRESH_OPERATION
    discarded = config.get('operation') == OCI_DISCARD_OPERATION
    fd, run_lock = None, None
    with activated_runtime(host_runtime):
        try:
            require_local_engine()
            if fresh or discarded:
                run_lock = acquire_fresh_run_lock(runtime)
            else:
                lock_path = Path('/tmp') / ('airbob-b-restore-' + config['source']['containerId'] + '.lock')
                fd = os.open(lock_path, os.O_WRONLY | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if args.apply:
                require(args.preflight is not None and digest(args.preflight_sha256) and sha(args.preflight) == args.preflight_sha256,
                        'Apply requires the exact reviewed preflight SHA')
                plan = read(args.preflight)
                expected_kind = ('global-b-oci-discard-install-preflight' if discarded else
                                 'global-b-fresh-install-preflight' if fresh else 'global-b-restore-preflight')
                require(plan['kind'] == expected_kind and plan['state'] == 'PREFLIGHT_READY'
                        and plan['configuration'] == config, 'Reviewed preflight configuration differs')
                if discarded:
                    verify_discard_runtime(plan, host_runtime)
                apply = apply_discard_plan if discarded else apply_fresh_plan if fresh else apply_plan
                result = apply(config, plan, output, runtime, before_preparation=before_preparation)
                if isinstance(result, dict):
                    result['hostRuntimeQualification'] = qualification_binding(host_runtime_path)
                    write(output / 'restore.json', result)
            else:
                require(args.preflight is None and args.preflight_sha256 is None, 'Preflight inputs apply only to --apply')
                plan = (build_discard_preflight(config, output) if discarded else
                        build_fresh_preflight(config, output) if fresh else build_preflight(config, output, runtime))
                plan['hostRuntimeQualification'] = qualification_binding(host_runtime_path)
                write(output / 'preflight.json', plan)
                print(json.dumps({'state': plan['state'], 'preflight': str(output / 'preflight.json'),
                                  'preflightSha256': sha(output / 'preflight.json')}))
        finally:
            if run_lock is not None:
                run_lock.close()
            if fd is not None:
                os.close(fd)


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        print(json.dumps({'state': 'FAILED', 'errorType': type(error).__name__}), file=sys.stderr)
        sys.exit(1)
