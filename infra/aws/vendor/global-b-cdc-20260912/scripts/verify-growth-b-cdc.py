#!/usr/bin/env python3
"""Two owned name PATCHes through API/outbox/Kafka/consumer/ES, with CAS cleanup.

verify is resumable and never replays an ambiguous PATCH. reset requires writers
already stopped by the operator. post-reset only observes externally restarted
services. Full prepared-dataset verification remains the operator's next gate.
"""
import argparse
import contextlib
import copy
from datetime import datetime, timezone
import fcntl
import hashlib
import http.cookiejar
import importlib.util
import ipaddress
import json
import os
from pathlib import Path
import re
import signal
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
from growth_b_contract import public_account_union


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


reads = load_module('_cdc_read_contract', ROOT / 'scripts/verify-growth-b-dev.py')
warm = load_module('_cdc_account_contract', ROOT / 'scripts/warm-growth-b-service.py')
TOPIC = 'ACCOMMODATION_INDEX.events'
CACHE_TOPIC = 'ACCOMMODATION_CACHE.events'
HEARTBEAT = '__debezium-heartbeat.airbob_outbox'
GROUP = 'accommodation-indexing-group'
CONNECTOR = 'airbob-outbox-connector'
SEARCH_TYPE = 'ACCOMMODATION_SEARCH_REFRESH_REQUESTED'
CACHE_TYPE = 'ACCOMMODATION_DETAIL_CACHE_INVALIDATION_REQUESTED'
FOREVER = '9999-12-31 23:59:59.000000'
MAX_BYTES, MAX_HISTORY, MAX_EVENTS = 2 * 1024 * 1024, 64, 192
CORE_JAR_SHA = 'a80472b7ef67a2a1450c2ad81ab3516a0e86f031f3758da0ae3ce6a5da6c1a9d'
PLUGIN_JAR_SHA = '6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59'
# The pinned 3.0.8 EventRouterDelegate DELETE branch returns null. FATAL is an
# UPDATE policy. Cleanup uses DELETE only for outbox, with binlog kept enabled.
DELETE_SOURCE = 'https://github.com/debezium/debezium/blob/v3.0.8.Final/debezium-core/src/main/java/io/debezium/transforms/outbox/EventRouterDelegate.java#L99'
COLUMNS = {
    'accommodation': 'id base_price address_id created_at member_id occupancy_policy_id description name thumbnail_url type check_in_time check_out_time accommodation_uid updated_at status currency created_by updated_by time_zone_id'.split(),
    'accommodation_history': 'id accommodation_id accommodation_uid name description base_price currency thumbnail_url type status check_in_time check_out_time member_id address_country address_state address_city address_district address_street address_detail address_postal_code address_latitude address_longitude max_occupancy infant_occupancy pet_occupancy created_at created_by history_created_at history_created_by change_type change_reason source_system client_ip valid_from valid_to time_zone_id'.split(),
    'outbox': 'id event_id destination partition_key aggregate_type aggregate_id event_type event_version payload occurred_at deduplication_key created_at updated_at created_by updated_by'.split(),
}


class Failed(Exception):
    def __init__(self, code):
        self.code = code
        super().__init__(code)


def need(condition, code):
    if not condition:
        raise Failed(code)


def integer(value, minimum=0):
    return type(value) is int and minimum <= value <= 2**63 - 1


def utc():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n').encode()


def tool_identity():
    return {name: digest((ROOT / name).read_bytes()) for name in (
        'scripts/verify-growth-b-cdc.py', 'scripts/verify-growth-b-dev.py',
        'scripts/warm-growth-b-service.py', 'infra/aws/scripts/growth_b_contract.py')}


def parse(raw):
    def pairs(rows):
        result = {}
        for key, value in rows:
            need(key not in result, 'DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    try:
        need(len(raw) <= MAX_BYTES, 'JSON_SIZE_LIMIT')
        return json.loads(raw, object_pairs_hook=pairs)
    except (ValueError, TypeError):
        raise Failed('INVALID_JSON') from None


def hex_text(value):
    return value.encode().hex().upper()


def text_cell(value):
    need(isinstance(value, str) and re.fullmatch(r'(?:[0-9A-F]{2})*', value), 'INVALID_HEX_CELL')
    try:
        return bytes.fromhex(value).decode()
    except (ValueError, UnicodeError):
        raise Failed('INVALID_TEXT_CELL') from None


def forever(value):
    return text_cell(value) in ('9999-12-31 23:59:59', FOREVER)


def row_id(row):
    value = text_cell(row['id'])
    need(re.fullmatch(r'[1-9][0-9]{0,18}', value), 'INVALID_ROW_ID')
    return int(value)


def validate_rows(table, rows):
    need(table in COLUMNS and isinstance(rows, list), 'INVALID_ROW_SNAPSHOT')
    seen = set()
    for row in rows:
        need(isinstance(row, dict) and set(row) == set(COLUMNS[table]), 'FULL_ROW_COLUMNS_REQUIRED')
        need(all(value is None or (isinstance(value, str) and re.fullmatch(r'(?:[0-9A-F]{2})*', value))
                 for value in row.values()), 'INVALID_HEX_CELL')
        key = row_id(row)
        need(key not in seen, 'DUPLICATE_ROW_ID')
        seen.add(key)
    need([row_id(row) for row in rows] == sorted(seen), 'NONCANONICAL_ROW_ORDER')


def private_path(path, *, directory=False):
    path = Path(path).absolute()
    info = path.lstat()
    need(path.resolve() == path and info.st_uid == os.geteuid()
         and stat.S_IMODE(info.st_mode) == (0o700 if directory else 0o600)
         and (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode))
         and (directory or info.st_nlink == 1), 'PRIVATE_PATH_OWNER_OR_MODE')
    return path


def write_new(path, raw):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb') as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
    finally:
        directory = os.open(Path(path).parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)


class Journal:
    """Private, O_EXCL, hash-chained records plus a process-lifetime flock."""
    def __init__(self, path, config_sha, tool_sha):
        path = Path(path).absolute()
        if not path.exists():
            path.mkdir(mode=0o700)
        self.path = private_path(path, directory=True)
        lock = self.path / '.lock'
        self.lock = os.open(lock, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        private_path(lock)
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(self.lock)
            raise Failed('JOURNAL_ALREADY_IN_USE') from None
        self.entries = []
        files = sorted(self.path.glob('[0-9][0-9][0-9][0-9].json'))
        previous = None
        for index, file in enumerate(files):
            raw = private_path(file).read_bytes()
            item = parse(raw)
            need(file.name == f'{index:04d}.json' and item.get('sequence') == index
                 and item.get('previousSha256') == previous, 'JOURNAL_CHAIN_MISMATCH')
            self.entries.append(item)
            previous = digest(raw)
        self.last_sha = previous
        if not self.entries:
            self.add('CREATED', {'configSha256': config_sha, 'toolSha256': tool_sha, 'nonce': uuid.uuid4().hex})
        created = self.entries[0]
        need(created['kind'] == 'CREATED' and created['data']['configSha256'] == config_sha
             and created['data']['toolSha256'] == tool_sha, 'JOURNAL_INPUT_OR_TOOL_CHANGED')

    def add(self, kind, data):
        need(len(self.entries) < MAX_EVENTS, 'JOURNAL_EVENT_BUDGET')
        item = {'sequence': len(self.entries), 'previousSha256': self.last_sha, 'kind': kind,
                'utc': utc(), 'monotonicNs': time.monotonic_ns(), 'data': data}
        raw = encoded(item)
        need(len(raw) <= MAX_BYTES, 'JOURNAL_RECORD_SIZE')
        write_new(self.path / f'{len(self.entries):04d}.json', raw)
        self.entries.append(item)
        self.last_sha = digest(raw)
        return item

    def last(self, kind):
        return next((item['data'] for item in reversed(self.entries) if item['kind'] == kind), None)

    def latest_sequence(self, kind):
        return max((item['sequence'] for item in self.entries if item['kind'] == kind), default=-1)

    def session_clean(self):
        return self.last('LOGIN_SESSION_UNCONFIRMED') is None and self.latest_sequence('LOGOUT_VERIFIED') > max(
            self.latest_sequence('SESSION_CLEANUP_UNCONFIRMED'), self.latest_sequence('LOGIN_INTENT'))

    def close(self):
        fcntl.flock(self.lock, fcntl.LOCK_UN)
        os.close(self.lock)


def read_inputs(config):
    required = {'schemaVersion', 'releaseDirectory', 'datasetId', 'consumerManifestSha256', 'checksumsSha256',
                'privateAccounts', 'accountEnvironment', 'ownerMemberId', 'accommodationId', 'mysqlServerUuid',
                'dockerNetwork', 'containers', 'preconditions', 'requestTimeoutSeconds', 'propagationTimeoutSeconds', 'maximumSeconds'}
    need(isinstance(config, dict) and set(config) == required and config['schemaVersion'] == 1, 'INVALID_CONFIG')
    for key in ('consumerManifestSha256', 'checksumsSha256'):
        need(isinstance(config[key], str) and re.fullmatch(r'[0-9a-f]{64}', config[key]), 'MISSING_SEAL_ANCHOR')
    release = Path(config['releaseDirectory']).absolute()
    checks_raw = reads.read_bytes(release / 'SHA256SUMS.json')
    consumer_raw = reads.read_bytes(release / 'consumer-manifest.json')
    need(digest(checks_raw) == config['checksumsSha256'] and digest(consumer_raw) == config['consumerManifestSha256'],
         'SEAL_ANCHOR_MISMATCH')
    checks, consumer = parse(checks_raw), parse(consumer_raw)
    need(checks.get('consumer-manifest.json') == config['consumerManifestSha256']
         and re.fullmatch(r'[0-9a-f]{64}', checks.get('airbob-growth.sql.gz', ''))
         and consumer.get('datasetId') == config['datasetId'] == 'global-growth-b-' + checks['airbob-growth.sql.gz'][:16]
         and consumer.get('schemaVersion') == 4 and consumer.get('mysql') == {'version': '8.4.11', 'flywayVersion': 28}
         and consumer.get('finalScaleSelected') is True and consumer.get('datasetScale') == 'selected-global-b-ten-million',
         'FINAL_B_IDENTITY_REQUIRED')
    data, hashes = {}, {}
    for name, key in [('accounts.json', 'accounts'), ('representative-accounts.json', 'representativeAccounts')]:
        raw = reads.read_bytes(release / name)
        hashes[name] = digest(raw)
        need(checks.get(name) == hashes[name]
             and consumer.get('artifacts', {}).get(key) == {'file': name, 'sha256': hashes[name]}, 'ACCOUNT_SEAL_MISMATCH')
        data[name] = parse(raw)
    bundle, selected = data['accounts.json'], data['representative-accounts.json']
    need(bundle.get('representativeAccounts') == selected.get('accounts') and selected.get('finalScaleSelected') is True,
         'REPRESENTATIVE_SEAL_MISMATCH')
    hosts = [row for row in selected['accounts'] if row.get('key') == 'host']
    need(len(hosts) == 1, 'SEALED_HOST_REQUIRED')
    host = hosts[0]
    need(host.get('memberId') == config['ownerMemberId'] and host.get('email') == 'host@airbob.test'
         and host.get('role') == 'MEMBER' and host.get('status') == 'ACTIVE'
         and integer(config['ownerMemberId'], 1) and integer(config['accommodationId'], 1)
         and host.get('ownership', {}).get('publishedListings', {}).get('sampleIds', [None])[0] == config['accommodationId'],
         'EXACT_REPRESENTATIVE_OWNER_REQUIRED')
    public = public_account_union(bundle)
    need(len(public) == 124 and config['accountEnvironment'] == 'oci:mysql:' + config['datasetId'], 'FULL_OCI_ACCOUNT_BINDING_REQUIRED')
    private = private_path(config['privateAccounts'])
    private_path(private.parent, directory=True)
    credentials = warm.private_accounts(private.read_bytes(), public, bundle, config['accountEnvironment'])
    need(str(uuid.UUID(config['mysqlServerUuid'])) == config['mysqlServerUuid'], 'CANONICAL_MYSQL_UUID_REQUIRED')
    need(isinstance(config['dockerNetwork'], str) and re.fullmatch(r'[A-Za-z0-9_-]{1,128}', config['dockerNetwork']), 'DOCKER_NETWORK_REQUIRED')
    pins = config['containers']
    need(isinstance(pins, dict) and set(pins) == {'mysql', 'app', 'debezium', 'kafka', 'elasticsearch'}, 'EXACT_CONTAINER_PINS_REQUIRED')
    for pin in pins.values():
        need(isinstance(pin, dict) and set(pin) == {'name', 'id', 'image'}
             and re.fullmatch(r'[A-Za-z0-9_.-]{1,128}', pin['name'])
             and re.fullmatch(r'[0-9a-f]{64}', pin['id']) and re.fullmatch(r'sha256:[0-9a-f]{64}', pin['image']),
             'EXACT_CONTAINER_PINS_REQUIRED')
    need(len({pin['id'] for pin in pins.values()}) == 5, 'DUPLICATED_CONTAINER_PINS')
    pre = config['preconditions']
    need(isinstance(pre, dict) and set(pre) == {'preparedFingerprint', 'preparationReceipt', 'serviceReceipt',
                                              'exclusiveWriterWindow', 'detailCacheState'}
         and pre['exclusiveWriterWindow'] is True and pre['detailCacheState'] == 'DISABLED', 'ISOLATED_PRECONDITIONS_REQUIRED')
    for key in ('preparedFingerprint', 'preparationReceipt', 'serviceReceipt'):
        value = pre[key]
        need(isinstance(value, dict) and set(value) == {'path', 'sha256'}
             and re.fullmatch(r'[0-9a-f]{64}', value['sha256'])
             and digest(reads.read_bytes(value['path'])) == value['sha256'], 'PRECONDITION_RECEIPT_HASH_MISMATCH')
    need(integer(config['requestTimeoutSeconds'], 1) and config['requestTimeoutSeconds'] <= 15
         and integer(config['propagationTimeoutSeconds'], 15) and config['propagationTimeoutSeconds'] <= 300
         and integer(config['maximumSeconds'], 60) and config['maximumSeconds'] <= 900, 'INVALID_DEADLINES')
    return {'config': config, 'credential': credentials[config['ownerMemberId']], 'host': host,
            'binding': {'datasetId': config['datasetId'], 'consumerManifestSha256': config['consumerManifestSha256'],
                        'checksumsSha256': config['checksumsSha256'], 'accountMetadataSha256': hashes,
                        'toolIdentity': tool_identity(),
                        'preconditions': {key: pre[key]['sha256'] for key in ('preparedFingerprint', 'preparationReceipt', 'serviceReceipt')}}}


def assert_baseline(snapshot, owner, target):
    for table in COLUMNS:
        validate_rows(table, snapshot[table])
    need(len(snapshot['accommodation']) == 1 and row_id(snapshot['accommodation'][0]) == target
         and snapshot['accommodation'][0]['member_id'] == hex_text(str(owner))
         and snapshot['accommodation'][0]['status'] == hex_text('PUBLISHED')
         and not snapshot['outbox'] and snapshot['meta']['outboxCount'] == 0, 'BASELINE_TARGET_OR_OUTBOX_INVALID')
    name = text_cell(snapshot['accommodation'][0]['name'])
    need(0 < len(name) <= 50 and '\x00' not in name, 'BASELINE_NAME_INVALID')
    current = [row for row in snapshot['accommodation_history'] if forever(row['valid_to'])]
    need(len(current) == 1 and len(snapshot['accommodation_history']) <= MAX_HISTORY,
         'EXACT_CURRENT_HISTORY_REQUIRED')
    for table in COLUMNS:
        need(integer(snapshot['meta']['autoIncrement'][table], 1), 'AUTO_INCREMENT_OBSERVATION_REQUIRED')
    return name


def validate_change(before, after, expected_name, owner):
    """Only the documented name/audit, SCD2 and two outbox-row delta is owned."""
    for table in COLUMNS:
        validate_rows(table, after[table])
    need(after['schemaSha256'] == before['schemaSha256'], 'SCHEMA_CHANGED_DURING_API')
    old, new = before['accommodation'][0], after['accommodation'][0]
    need(len(after['accommodation']) == 1 and new['name'] == hex_text(expected_name)
         and new['updated_by'] == hex_text(str(owner))
         and {key: value for key, value in old.items() if key not in {'name', 'updated_at', 'updated_by'}}
             == {key: value for key, value in new.items() if key not in {'name', 'updated_at', 'updated_by'}}
         and new['updated_at'] is not None and new['updated_at'] != old['updated_at'], 'UNEXPECTED_ACCOMMODATION_DELTA')
    old_h = {row_id(row): row for row in before['accommodation_history']}
    new_h = {row_id(row): row for row in after['accommodation_history']}
    added = set(new_h) - set(old_h)
    need(len(added) == 1 and set(old_h) <= set(new_h), 'UNEXPECTED_HISTORY_ROW_SET')
    created_id = added.pop()
    created = new_h[created_id]
    current = [key for key, row in old_h.items() if forever(row['valid_to'])]
    need(len(current) == 1, 'CURRENT_HISTORY_CHANGED')
    current_id = current[0]
    for key, row in old_h.items():
        expected = dict(row)
        if key == current_id:
            expected['valid_to'] = created['valid_from']
        need(new_h[key] == expected, 'UNEXPECTED_EXISTING_HISTORY_DELTA')
    need(created['name'] == new['name'] and created['accommodation_id'] == old['id']
         and created['member_id'] == old['member_id'] and created['history_created_by'] == hex_text(str(owner))
         and created['change_type'] == hex_text('UPDATE')
         and forever(created['valid_to']), 'UNEXPECTED_NEW_HISTORY_CONTENT')
    for field in ('description', 'base_price', 'currency', 'thumbnail_url', 'type', 'status', 'check_in_time',
                  'check_out_time', 'time_zone_id', 'created_at', 'created_by'):
        need(created[field] == new[field], 'HISTORY_SNAPSHOT_MISMATCH')
    old_o = {row_id(row): row for row in before['outbox']}
    new_o = {row_id(row): row for row in after['outbox']}
    need(set(old_o) <= set(new_o) and all(new_o[key] == row for key, row in old_o.items()), 'EXISTING_OUTBOX_CHANGED')
    added_ids = sorted(set(new_o) - set(old_o))
    need(len(added_ids) == 2 and after['meta']['outboxCount'] == len(after['outbox']), 'EXACT_TWO_OUTBOX_ROWS_REQUIRED')
    uid = str(uuid.UUID(bytes=bytes.fromhex(new['accommodation_uid'])))
    need(created['accommodation_uid'] == hex_text(uid), 'HISTORY_AGGREGATE_UID_MISMATCH')
    events = []
    for key in added_ids:
        row = new_o[key]
        event = {field: text_cell(row[field]) for field in
                 ('event_id', 'destination', 'partition_key', 'aggregate_type', 'aggregate_id', 'event_type', 'event_version')}
        need(str(uuid.UUID(event['event_id'])) == event['event_id'] and event['aggregate_type'] == 'ACCOMMODATION'
             and event['event_version'] == '1' and row['created_by'] == row['updated_by'] == hex_text(str(owner))
             and row['deduplication_key'] is None, 'OUTBOX_IDENTITY_MISMATCH')
        payload = parse(text_cell(row['payload']).encode())
        need(set(payload) == {'eventId', 'eventType', 'eventVersion', 'occurredAt', 'payload'}
             and payload['eventId'] == event['event_id'] and payload['eventType'] == event['event_type']
             and payload['eventVersion'] == '1', 'OUTBOX_ENVELOPE_MISMATCH')
        if event['destination'] == TOPIC:
            need(event['event_type'] == SEARCH_TYPE and event['partition_key'] == event['aggregate_id'] == uid
                 and payload['payload'] == {'accommodationUid': uid}, 'SEARCH_EVENT_MISMATCH')
        else:
            need(event['destination'] == CACHE_TOPIC and event['event_type'] == CACHE_TYPE
                 and event['partition_key'] == event['aggregate_id'] == str(row_id(new))
                 and payload['payload'] == {'accommodationId': row_id(new), 'reason': 'ACCOMMODATION'}, 'CACHE_EVENT_MISMATCH')
        events.append({'rowId': key, **event})
    need({event['destination'] for event in events} == {TOPIC, CACHE_TOPIC}, 'SEARCH_AND_CACHE_EVENTS_REQUIRED')
    ai = before['meta']['autoIncrement']
    need(created_id == ai['accommodation_history'] and added_ids == [ai['outbox'], ai['outbox'] + 1]
         and after['meta']['autoIncrement'] == {**ai, 'accommodation_history': ai['accommodation_history'] + 1,
                                              'outbox': ai['outbox'] + 2}
         and after['meta']['historyMaxId'] == created_id, 'UNEXPECTED_AUTO_INCREMENT_OR_UNRELATED_HISTORY')
    return {'historyId': created_id, 'outboxEvents': events, 'aggregateUid': uid,
            'searchEvent': next(event for event in events if event['destination'] == TOPIC)}


def row_predicate(row):
    return ' AND '.join(f"HEX(CAST(`{key}` AS BINARY)) <=> " + ('NULL' if value is None else "'" + value + "'")
                        for key, value in sorted(row.items()))


def sql_assert(condition):
    # A scalar subquery returning two rows raises an error in SELECT even when
    # strict SQL mode is off. Batch mysql without --force then disconnects and
    # rolls back the still-open transaction; no stored routine or DDL is needed.
    return 'SELECT (SELECT n FROM (SELECT 1 n UNION ALL SELECT 2) cdc_assert WHERE n=1 OR NOT (' + condition + '));'


def literal(value):
    return 'NULL' if value is None else "CONVERT(UNHEX('" + value + "') USING utf8mb4)"


def reset_rows_sql(baseline, expected, target, server_uuid):
    need(integer(target, 1) and isinstance(server_uuid, str)
         and re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', server_uuid), 'RESET_TARGET_IDENTITY_REQUIRED')
    for snapshot in (baseline, expected):
        for table in COLUMNS:
            validate_rows(table, snapshot[table])
    need(len(baseline['accommodation']) == len(expected['accommodation']) == 1
         and row_id(baseline['accommodation'][0]) == row_id(expected['accommodation'][0]) == target,
         'RESET_TARGET_IDENTITY_REQUIRED')
    need(expected['accommodation'][0]['name'] == baseline['accommodation'][0]['name'], 'API_NAME_RESTORE_REQUIRED')
    original_h = {row_id(row): row for row in baseline['accommodation_history']}
    expected_h = {row_id(row): row for row in expected['accommodation_history']}
    removed = sorted(set(expected_h) - set(original_h))
    need(len(removed) == 2 and len(expected['outbox']) == 4 and not baseline['outbox'], 'EXACT_TWO_PATCH_CLEANUP_REQUIRED')
    sql = ['SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ;', 'START TRANSACTION;',
           sql_assert("@@server_uuid='" + server_uuid + "' AND @@global.read_only=1 AND @@global.log_bin=1 AND @@session.sql_log_bin=1"),
           f'SELECT id FROM accommodation WHERE id={target} FOR UPDATE;',
           f'SELECT id FROM accommodation_history WHERE accommodation_id={target} ORDER BY id FOR UPDATE;',
           'SELECT id FROM outbox ORDER BY id FOR UPDATE;']
    for table in COLUMNS:
        where = f'id={target}' if table == 'accommodation' else (f'accommodation_id={target}' if table == 'accommodation_history' else '1=1')
        sql.append(sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {where})={len(expected[table])}'))
        for row in expected[table]:
            sql.append(sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {row_predicate(row)})=1'))
    for row in expected['outbox']:
        sql.extend(['DELETE FROM outbox WHERE ' + row_predicate(row) + ';', sql_assert('ROW_COUNT()=1')])
    for key in reversed(removed):
        sql.extend(['DELETE FROM accommodation_history WHERE ' + row_predicate(expected_h[key]) + ';', sql_assert('ROW_COUNT()=1')])
    for key, original in original_h.items():
        if expected_h[key] != original:
            need({name for name in original if original[name] != expected_h[key][name]} == {'valid_to'}, 'CLEANUP_HISTORY_SCOPE_EXCEEDED')
            sql.extend(['UPDATE accommodation_history SET valid_to=' + literal(original['valid_to']) +
                        ' WHERE ' + row_predicate(expected_h[key]) + ';', sql_assert('ROW_COUNT()=1')])
    before, after = baseline['accommodation'][0], expected['accommodation'][0]
    need({name for name in before if before[name] != after[name]} <= {'updated_at', 'updated_by'}, 'CLEANUP_ACCOMMODATION_SCOPE_EXCEEDED')
    sql.extend(['UPDATE accommodation SET updated_at=' + literal(before['updated_at']) + ',updated_by=' + literal(before['updated_by']) +
                ' WHERE ' + row_predicate(after) + ';', sql_assert('ROW_COUNT()=1')])
    for table in COLUMNS:
        for row in baseline[table]:
            sql.append(sql_assert(f'(SELECT COUNT(*) FROM `{table}` WHERE {row_predicate(row)})=1'))
    sql.extend([sql_assert('(SELECT COUNT(*) FROM outbox)=0'), 'COMMIT;'])
    return '\n'.join(sql)


def parse_offsets(raw, topic=TOPIC, partitions=3):
    result = {}
    for line in raw.decode().splitlines():
        match = re.fullmatch(re.escape(topic) + r':([0-9]+):([0-9]+)', line)
        need(match and int(match[1]) not in result, 'KAFKA_OFFSET_OUTPUT_INVALID')
        result[int(match[1])] = int(match[2])
    need(set(result) == set(range(partitions)), 'KAFKA_PARTITION_SET_CHANGED')
    return result


def parse_records(raw, partition, start, end, event):
    rows = raw.split(b'\x1e')
    need(rows[-1] == b'' and len(rows) - 1 == end - start, 'KAFKA_RECORD_RANGE_INCOMPLETE')
    matches = []
    for index, row in enumerate(rows[:-1]):
        parts = row.split(b'\x1f')
        need(len(parts) == 5 and parts[0] == f'Partition:{partition}'.encode()
             and parts[1] == f'Offset:{start + index}'.encode(), 'KAFKA_RECORD_POSITION_MISMATCH')
        headers = {}
        for value in parts[2].split(b'\x1d'):
            key, separator, data = value.partition(b':')
            need(separator and key not in headers, 'KAFKA_HEADER_INVALID_OR_DUPLICATED')
            headers[key] = data
        if headers.get(b'id') != event['event_id'].encode():
            continue
        expected = {'id': event['event_id'], 'eventType': SEARCH_TYPE, 'eventVersion': '1',
                    'aggregateType': 'ACCOMMODATION', 'aggregateId': event['aggregate_id']}
        need(headers == {key.encode(): value.encode() for key, value in expected.items()}
             and parts[3] == event['aggregate_id'].encode(), 'KAFKA_EVENT_HEADERS_OR_KEY_MISMATCH')
        payload = parse(parts[4])
        need(payload.get('eventId') == event['event_id'] and payload.get('eventType') == SEARCH_TYPE
             and payload.get('eventVersion') == '1' and payload.get('payload') == {'accommodationUid': event['aggregate_id']},
             'KAFKA_EVENT_PAYLOAD_MISMATCH')
        matches.append({'topic': TOPIC, 'partition': partition, 'offset': start + index, 'headers': expected})
    return matches


def parse_committed(raw):
    result = {}
    for line in raw.decode().splitlines():
        fields = line.split()
        if len(fields) >= 6 and fields[0] == GROUP and fields[1] == TOPIC:
            need(fields[2].isdigit() and (fields[3].isdigit() or fields[3] == '-')
                 and int(fields[2]) not in result, 'CONSUMER_COMMIT_OUTPUT_INVALID')
            result[int(fields[2])] = None if fields[3] == '-' else int(fields[3])
    need(set(result) == {0, 1, 2}, 'EXACT_INDEXING_GROUP_PARTITIONS_REQUIRED')
    return result


def command(args, *, data=None, timeout=30):
    try:
        result = subprocess.run(args, input=data, capture_output=True, timeout=timeout)
    except (OSError, subprocess.TimeoutExpired):
        raise Failed('LOCAL_COMMAND_FAILED_OR_TIMED_OUT') from None
    need(result.returncode == 0, 'LOCAL_COMMAND_REJECTED')
    need(len(result.stdout) <= MAX_BYTES, 'LOCAL_COMMAND_OUTPUT_LIMIT')
    return result.stdout


class DockerAdapter:
    """OCI host adapter. An AWS adapter must implement its own identity/fence.

    Core operations: guard, snapshot, end_offsets, records, committed, document,
    connector_health, reset_rows, reset_counter. Only reset operations write SQL.
    """
    def __init__(self, config, journal):
        self.config, self.journal = config, journal
        self.pins = config['containers']
        self.docker = ['docker', '--host', 'unix:///var/run/docker.sock']
        self.runtime = None
        self.schema_sha = None
        self.target = config['accommodationId']
        self.record_cache = {}

    def local_daemon(self):
        need(sys.platform.startswith('linux') and os.environ.get('DOCKER_HOST', '') in ('', 'unix:///var/run/docker.sock')
             and not os.environ.get('DOCKER_CONTEXT'), 'LOCAL_LINUX_DOCKER_REQUIRED')
        info = Path('/var/run/docker.sock').stat()
        need(stat.S_ISSOCK(info.st_mode) and info.st_uid == 0, 'LOCAL_DOCKER_SOCKET_REQUIRED')
        need(command(self.docker + ['info', '--format', '{{.OSType}}'], timeout=10).strip() == b'linux', 'LINUX_DAEMON_REQUIRED')

    def inspect(self, role):
        pin = self.pins[role]
        raw = command(self.docker + ['inspect', pin['name']], timeout=10)
        info = parse(raw)
        need(isinstance(info, list) and len(info) == 1, 'CONTAINER_INSPECTION_INVALID')
        info = info[0]
        need(info.get('Id') == pin['id'] and info.get('Image') == pin['image'], 'CONTAINER_ID_OR_IMAGE_CHANGED')
        return info

    def guard(self, mode='running'):
        need(mode in ('running', 'frozen', 'cleanup', 'restarted'), 'INVALID_FENCE_MODE')
        self.local_daemon()
        networks = parse(command(self.docker + ['network', 'inspect', self.config['dockerNetwork']], timeout=10))
        need(len(networks) == 1 and networks[0].get('Driver') == 'bridge'
             and networks[0].get('Scope') == 'local', 'LOCAL_BRIDGE_REQUIRED')
        network = networks[0]
        subnets = [ipaddress.ip_network(item['Subnet']) for item in network.get('IPAM', {}).get('Config', []) if 'Subnet' in item]
        result = {'networkId': network['Id'], 'containers': {}}
        for role in self.pins:
            info = self.inspect(role)
            expected_running = not (mode in ('frozen', 'cleanup') and role in ('app', 'debezium'))
            need(info['State']['Running'] is expected_running and not info['State'].get('Paused', False)
                 and not info['State'].get('Restarting', False), 'WRITER_STATE_OR_DEPENDENCY_MISMATCH')
            row = {'id': info['Id'], 'image': info['Image'], 'running': expected_running,
                   'startedAt': info['State']['StartedAt'], 'restartCount': info['RestartCount']}
            if expected_running:
                endpoint = info['NetworkSettings']['Networks'].get(self.config['dockerNetwork'], {})
                address = ipaddress.ip_address(endpoint.get('IPAddress', ''))
                need(endpoint.get('NetworkID') == network['Id'] and address.version == 4 and address.is_private
                     and any(address in subnet for subnet in subnets), 'PINNED_BRIDGE_ENDPOINT_REQUIRED')
                row['address'] = str(address)
            result['containers'][role] = row
        baseline = self.journal.last('BASELINE')
        if baseline:
            previous = baseline['runtime']
            need(result['networkId'] == previous['networkId'], 'DOCKER_NETWORK_ID_CHANGED')
            for role, item in result['containers'].items():
                if mode == 'running':
                    need(item == previous['containers'][role], 'SERVICE_RESTARTED_DURING_VERIFICATION')
                elif role not in ('app', 'debezium'):
                    need(item == previous['containers'][role], 'DEPENDENCY_RESTARTED_DURING_CLEANUP')
        self.runtime = result
        metadata = parse(self.sql("SELECT JSON_OBJECT('uuid',@@server_uuid,'version',@@version,'readOnly',@@global.read_only,"
                                  "'superReadOnly',@@global.super_read_only,'binlog',@@global.log_bin,'sessionBinlog',@@session.sql_log_bin,"
                                  "'format',@@global.binlog_format,'increment',@@auto_increment_increment,'offset',@@auto_increment_offset,"
                                  "'otherConnections',(SELECT COUNT(*) FROM information_schema.processlist WHERE ID<>CONNECTION_ID() "
                                  "AND USER NOT IN ('system user','event_scheduler')));"))
        need(metadata['uuid'] == self.config['mysqlServerUuid'] and metadata['version'].startswith('8.4.11')
             and metadata['binlog'] == metadata['sessionBinlog'] == 1 and metadata['format'] == 'ROW'
             and metadata['increment'] == metadata['offset'] == 1, 'MYSQL_IDENTITY_OR_BINLOG_MISMATCH')
        if mode in ('frozen', 'cleanup'):
            need(metadata['readOnly'] == 1 and metadata['superReadOnly'] == (1 if mode == 'frozen' else 0)
                 and metadata['otherConnections'] == 0, 'EXCLUSIVE_FROZEN_MYSQL_REQUIRED')
        else:
            need(metadata['readOnly'] == metadata['superReadOnly'] == 0, 'WRITABLE_SERVICE_REQUIRED')
        return result

    def sql(self, sql, *, seconds=20):
        self.inspect('mysql')
        prefix = ("SET SESSION time_zone='+00:00'; SET SESSION information_schema_stats_expiry=0; "
                  "SET SESSION max_execution_time=5000; SET SESSION innodb_lock_wait_timeout=5; SET SESSION lock_wait_timeout=5; ")
        # The timeout lives inside the container, so losing the host docker CLI
        # cannot leave an unbounded MySQL client/transaction running remotely.
        args = self.docker + ['exec', '-i', self.pins['mysql']['id'], 'timeout', '-s', 'TERM', '-k', '3', str(seconds),
                             'sh', '-c', 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --no-defaults -uroot --protocol=TCP '
                             '--host=127.0.0.1 --default-character-set=utf8mb4 --batch --raw --skip-column-names airbobdb']
        return command(args, data=(prefix + sql).encode(), timeout=seconds + 8)

    def schema(self):
        rows = self.sql("SELECT TABLE_NAME,COLUMN_NAME,EXTRA FROM information_schema.columns "
                        "WHERE table_schema='airbobdb' AND TABLE_NAME IN ('accommodation','accommodation_history','outbox') "
                        "ORDER BY TABLE_NAME,ORDINAL_POSITION;").decode().splitlines()
        actual = {table: [] for table in COLUMNS}
        for row in rows:
            table, column, extra = row.split('\t')
            need(table in actual and column in COLUMNS[table] and extra in ('', 'auto_increment'), 'UNEXPECTED_TABLE_COLUMNS')
            actual[table].append(column)
        need(all(set(actual[table]) == set(columns) and len(actual[table]) == len(columns)
                 for table, columns in COLUMNS.items()), 'FULL_TABLE_SCHEMA_REQUIRED')
        ddl = []
        for table in COLUMNS:
            raw = self.sql('SHOW CREATE TABLE `' + table + '`;').decode()
            ddl.append(re.sub(r' AUTO_INCREMENT=[0-9]+', '', raw))
        triggers = self.sql("SELECT COUNT(*) FROM information_schema.triggers WHERE EVENT_OBJECT_SCHEMA='airbobdb' "
                            "AND EVENT_OBJECT_TABLE IN ('accommodation','accommodation_history','outbox');").strip()
        need(triggers == b'0', 'UNEXPECTED_TABLE_TRIGGERS')
        self.schema_sha = digest('\n'.join(ddl).encode())
        return self.schema_sha

    def snapshot(self):
        schema_sha = self.schema()
        selections = []
        for table, columns in COLUMNS.items():
            obj = 'JSON_OBJECT(' + ','.join("'" + name + "',HEX(CAST(`" + name + '` AS BINARY))' for name in columns) + ')'
            where = f'id={self.target}' if table == 'accommodation' else (f'accommodation_id={self.target}' if table == 'accommodation_history' else '1=1')
            limit = 2 if table == 'accommodation' else (MAX_HISTORY + 1 if table == 'accommodation_history' else 9)
            selections.append(f"SELECT '{table}',{obj} FROM `{table}` WHERE {where} ORDER BY id LIMIT {limit};")
        meta = ("SELECT 'meta',JSON_OBJECT('autoIncrement',JSON_OBJECT(" + ','.join(
            "'" + table + "',(SELECT AUTO_INCREMENT FROM information_schema.tables WHERE table_schema='airbobdb' AND table_name='" + table + "')"
            for table in COLUMNS) + "),'historyMaxId',(SELECT COALESCE(MAX(id),0) FROM accommodation_history),"
            "'historyCount',(SELECT COUNT(*) FROM accommodation_history WHERE accommodation_id=" + str(self.target) + "),"
            "'outboxCount',(SELECT COUNT(*) FROM outbox));")
        raw = self.sql('START TRANSACTION WITH CONSISTENT SNAPSHOT;\n' + '\n'.join(selections) + '\n' + meta + '\nCOMMIT;')
        result = {table: [] for table in COLUMNS}
        for line in raw.splitlines():
            key, value = line.split(b'\t', 1)
            key = key.decode()
            need(key in COLUMNS or key == 'meta', 'MYSQL_SNAPSHOT_OUTPUT_INVALID')
            if key == 'meta':
                need('meta' not in result, 'DUPLICATE_SNAPSHOT_METADATA')
                result['meta'] = parse(value)
            else:
                result[key].append(parse(value))
        need('meta' in result and result['meta']['historyCount'] == len(result['accommodation_history']) <= MAX_HISTORY
             and result['meta']['outboxCount'] == len(result['outbox']) <= 4, 'BOUNDED_SNAPSHOT_ROW_SET_REQUIRED')
        result['schemaSha256'] = schema_sha
        return result

    def kafka(self, tool, args):
        need(tool in ('kafka-get-offsets.sh', 'kafka-console-consumer.sh', 'kafka-consumer-groups.sh'), 'KAFKA_TOOL_NOT_ALLOWED')
        self.inspect('kafka')
        return command(self.docker + ['exec', self.pins['kafka']['id'], 'timeout', '-s', 'TERM', '-k', '3', '20',
                       '/opt/kafka/bin/' + tool, '--bootstrap-server', 'localhost:9092'] + args, timeout=28)

    def end_offsets(self, heartbeat=False):
        topic = HEARTBEAT if heartbeat else TOPIC
        return parse_offsets(self.kafka('kafka-get-offsets.sh', ['--topic', topic, '--time', '-1']),
                             topic, 1 if heartbeat else 3)

    def records(self, partition, start, end, event):
        need(integer(partition) and partition < 3 and integer(start) and integer(end) and 0 < end - start <= 64,
             'BOUNDED_KAFKA_RANGE_REQUIRED')
        args = ['--topic', TOPIC, '--partition', str(partition), '--offset', str(start), '--max-messages', str(end - start),
                '--timeout-ms', '5000', '--consumer-property', 'enable.auto.commit=false',
                '--consumer-property', 'auto.offset.reset=none', '--isolation-level', 'read_committed']
        for value in ('print.partition=true', 'print.offset=true', 'print.headers=true', 'print.key=true', 'print.value=true',
                      'print.timestamp=false', 'key.separator=\x1f', 'line.separator=\x1e', 'headers.separator=\x1d'):
            args.extend(['--property', value])
        key = (partition, start, end)
        raw = self.record_cache.get(key)
        if raw is None:
            raw = self.kafka('kafka-console-consumer.sh', args)
            self.journal.add('PRIVATE_KAFKA_RECORDS', {'topic': TOPIC, 'partition': partition, 'start': start, 'end': end,
                                                     'rawHex': raw.hex().upper()})
            self.record_cache[key] = raw
        return parse_records(raw, partition, start, end, event)

    def committed(self):
        return parse_committed(self.kafka('kafka-consumer-groups.sh', ['--describe', '--group', GROUP]))

    def consumer_health(self):
        raw = self.kafka('kafka-consumer-groups.sh', ['--describe', '--group', GROUP, '--state']).decode()
        rows = [line for line in raw.splitlines() if line.startswith(GROUP + ' ')]
        need(len(rows) == 1 and re.search(r'\sStable\s+[1-9][0-9]*\s*$', rows[0]), 'INDEXING_CONSUMER_GROUP_NOT_STABLE')
        return {'group': GROUP, 'state': 'Stable'}

    def endpoint(self, role, port):
        info = self.inspect(role)
        need(info['State']['Running'], 'DEPENDENCY_STOPPED')
        endpoint = info['NetworkSettings']['Networks'][self.config['dockerNetwork']]
        address = endpoint['IPAddress']
        need(self.runtime and endpoint['NetworkID'] == self.runtime['networkId']
             and address == self.runtime['containers'][role].get('address'), 'BRIDGE_ENDPOINT_CHANGED')
        need((role, port) in {('app', 8080), ('debezium', 8083), ('elasticsearch', 9200)}, 'SERVICE_PORT_NOT_ALLOWED')
        return f'http://{address}:{port}'

    def get(self, role, path):
        allowed = (role == 'elasticsearch' and (path in ('/', '/_alias/accommodations')
                   or re.fullmatch(r'/accommodations/_doc/[0-9a-f-]{36}\?_source_includes=name,accommodationId', path)))
        allowed = allowed or (role == 'debezium' and path in ('/', '/connector-plugins', '/connectors/' + CONNECTOR + '/status',
                                                           '/connectors/' + CONNECTOR + '/config'))
        need(allowed, 'READ_ENDPOINT_NOT_ALLOWED')
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), reads.NoRedirect())
        url = self.endpoint(role, 8083 if role == 'debezium' else 9200) + path
        try:
            with warm.wall_clock_limit(10), opener.open(urllib.request.Request(url, headers={'Accept': 'application/json',
                                         'Accept-Encoding': 'identity'}), timeout=10) as response:
                need(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity', 'READ_HTTP_STATUS')
                return parse(response.read(MAX_BYTES + 1))
        except (OSError, urllib.error.URLError, reads.CheckFailed, warm.CheckFailed):
            raise Failed('READ_HTTP_FAILED') from None

    def document(self, uid):
        need(str(uuid.UUID(uid)) == uid, 'ES_DOCUMENT_UID_INVALID')
        doc = self.get('elasticsearch', '/accommodations/_doc/' + uid + '?_source_includes=name,accommodationId')
        need(doc.get('found') is True and doc.get('_id') == uid and isinstance(doc.get('_source'), dict)
             and doc['_source'].get('accommodationId') == self.target, 'ES_DOCUMENT_IDENTITY_MISMATCH')
        return doc['_source']['name']

    def connector_health(self):
        version = self.get('debezium', '/')
        plugins = self.get('debezium', '/connector-plugins')
        status = self.get('debezium', '/connectors/' + CONNECTOR + '/status')
        configuration = self.get('debezium', '/connectors/' + CONNECTOR + '/config')
        expected_config = {'table.include.list': 'airbobdb.outbox', 'topic.prefix': 'airbob_outbox',
                           'database.include.list': 'airbobdb', 'database.hostname': self.pins['mysql']['name'], 'database.port': '3306',
                           'connector.class': 'io.debezium.connector.mysql.MySqlConnector', 'tasks.max': '1',
                           'heartbeat.interval.ms': '10000', 'include.schema.changes': 'false',
                           'key.converter': 'org.apache.kafka.connect.storage.StringConverter',
                           'value.converter': 'org.apache.kafka.connect.storage.StringConverter',
                           'header.converter': 'org.apache.kafka.connect.storage.StringConverter',
                           'tombstones.on.delete': 'false', 'transforms': 'outbox',
                           'predicates': 'IsOutboxTable',
                           'predicates.IsOutboxTable.type': 'org.apache.kafka.connect.transforms.predicates.TopicNameMatches',
                           'predicates.IsOutboxTable.pattern': r'airbob_outbox\.airbobdb\.outbox',
                           'transforms.outbox.predicate': 'IsOutboxTable',
                           'transforms.outbox.table.op.invalid.behavior': 'fatal',
                           'transforms.outbox.type': 'io.debezium.transforms.outbox.EventRouter',
                           'transforms.outbox.table.field.event.id': 'event_id',
                           'transforms.outbox.table.field.event.key': 'partition_key',
                           'transforms.outbox.table.field.event.payload': 'payload',
                           'transforms.outbox.table.field.event.timestamp': 'occurred_at',
                           'transforms.outbox.route.by.field': 'destination',
                           'transforms.outbox.route.topic.replacement': '${routedByValue}',
                           'transforms.outbox.table.fields.additional.placement':
                           'event_type:header:eventType,event_version:header:eventVersion,aggregate_type:header:aggregateType,aggregate_id:header:aggregateId'}
        need(all(configuration.get(key) == value for key, value in expected_config.items()), 'CONNECTOR_OUTBOX_ROUTE_CHANGED')
        need(version.get('version') == '3.7.0' and isinstance(plugins, list)
             and len([row for row in plugins if row.get('class') == 'io.debezium.connector.mysql.MySqlConnector'
                      and row.get('version') == '3.0.8.Final']) == 1
             and status.get('name') == CONNECTOR and status.get('connector', {}).get('state') == 'RUNNING'
             and len(status.get('tasks', [])) == 1 and status['tasks'][0].get('state') == 'RUNNING', 'CONNECTOR_RUNTIME_NOT_HEALTHY')
        hashes = command(self.docker + ['exec', self.pins['debezium']['id'], 'sha256sum',
                         '/opt/kafka/connect-plugins/debezium-mysql/debezium-connector-mysql-3.0.8.Final.jar',
                         '/opt/kafka/connect-plugins/debezium-mysql/debezium-core-3.0.8.Final.jar']).decode().splitlines()
        need(len(hashes) == 2 and hashes[0].split()[0] == PLUGIN_JAR_SHA and hashes[1].split()[0] == CORE_JAR_SHA,
             'PINNED_DEBEZIUM_JARS_REQUIRED')
        es = self.get('elasticsearch', '/')
        alias = self.get('elasticsearch', '/_alias/accommodations')
        need(es.get('version', {}).get('number') == '8.18.8' and len(alias) == 1
             and next(iter(alias.values())).get('aliases', {}).get('accommodations', {}).get('is_write_index') is True,
             'NATIVE_ES_ALIAS_NOT_READY')
        return {'connectVersion': '3.7.0', 'pluginVersion': '3.0.8.Final', 'coreJarSha256': CORE_JAR_SHA,
                'pluginJarSha256': PLUGIN_JAR_SHA, 'connector': 'RUNNING', 'tasks': ['RUNNING'],
                'elasticsearchVersion': '8.18.8', 'singleWriteAlias': True}

    @contextlib.contextmanager
    def cleanup_window(self):
        try:
            self.guard('frozen')
        except Failed as error:
            intents = [row['sequence'] for row in self.journal.entries if row['kind'] == 'CLEANUP_WRITE_WINDOW_INTENT']
            closed = [row['sequence'] for row in self.journal.entries if row['kind'] == 'CLEANUP_FENCE_RESTORED']
            need(error.code == 'EXCLUSIVE_FROZEN_MYSQL_REQUIRED' and intents and max(intents) > max(closed, default=-1),
                 'CLEANUP_FENCE_NOT_OWNED')
            self.guard('cleanup')
            self.sql('SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;')
            self.guard('frozen')
            self.journal.add('CLEANUP_FENCE_RESTORED', {'recoveredInterruptedWindow': True})
        self.journal.add('CLEANUP_WRITE_WINDOW_INTENT', {'readOnlyRemainsEnabled': True, 'binlogRemainsEnabled': True})
        try:
            self.sql('SET GLOBAL super_read_only=OFF;')
            self.guard('cleanup')
            yield
        finally:
            # Never leave the privileged cleanup window open after a rejected
            # CAS, timeout, partial counter reset, or successful operation.
            self.sql('SET GLOBAL read_only=ON; SET GLOBAL super_read_only=ON;')
            self.guard('frozen')
            self.journal.add('CLEANUP_FENCE_RESTORED', {'readOnly': True, 'superReadOnly': True, 'binlogEnabled': True})

    def reset_rows(self, baseline, expected):
        need(self.snapshot() == expected, 'ROW_CAS_INPUT_CHANGED')
        self.sql(reset_rows_sql(baseline, expected, self.target, self.config['mysqlServerUuid']))

    def reset_counter(self, table, value):
        need(table in ('outbox', 'accommodation_history') and integer(value, 1), 'COUNTER_RESET_SCOPE_INVALID')
        self.sql(f'ALTER TABLE `{table}` AUTO_INCREMENT={value};')


class PrivateCookieJar(http.cookiejar.LWPCookieJar):
    def __init__(self, path):
        self.path, self.loading = Path(path), True
        super().__init__()
        if self.path.exists():
            private_path(self.path)
            self.load(str(self.path), ignore_discard=True, ignore_expires=True)
        self.loading = False

    def persist(self):
        fd, name = tempfile.mkstemp(prefix='.cookies-', dir=self.path.parent)
        os.close(fd)
        try:
            self.save(name, ignore_discard=True, ignore_expires=True)
            os.chmod(name, 0o600)
            with open(name, 'rb') as stream:
                os.fsync(stream.fileno())
            os.replace(name, self.path)
            directory = os.open(self.path.parent, os.O_RDONLY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    def set_cookie(self, cookie, *args, **kwargs):
        super().set_cookie(cookie, *args, **kwargs)
        if not self.loading:
            self.persist()


class Session:
    def __init__(self, adapter, journal, inputs, allowed_names):
        self.adapter, self.journal, self.inputs = adapter, journal, inputs
        self.allowed_names = set(allowed_names)
        self.login_started = False
        self.login_response_received = False
        self.cookies = PrivateCookieJar(journal.path / '.cookies')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), reads.NoRedirect(),
                                                 urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, method, path, body=None):
        target = self.inputs['config']['accommodationId']
        allowed = method == 'GET' and path == '/api/v1/auth/me' and body is None
        allowed |= method == 'POST' and path == '/api/v1/auth/logout' and body is None
        allowed |= method == 'POST' and path == '/api/v1/auth/login' and isinstance(body, dict) \
            and body == {key: self.inputs['credential'][key] for key in ('email', 'password')}
        allowed |= method == 'PATCH' and path == '/api/v1/accommodations/' + str(target) and isinstance(body, dict) \
            and set(body) == {'name'} and body['name'] in self.allowed_names
        need(allowed, 'HTTP_REQUEST_OUTSIDE_CDC_CONTRACT')
        timeout = self.inputs['config']['requestTimeoutSeconds']
        url = self.adapter.endpoint('app', 8080) + path
        raw = encoded(body) if body is not None else None
        request = urllib.request.Request(url, method=method, data=raw, headers={'Accept': 'application/json',
                                         'Accept-Encoding': 'identity', 'Content-Type': 'application/json'})
        try:
            with warm.wall_clock_limit(timeout):
                try:
                    response = self.opener.open(request, timeout=timeout)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    need(response.headers.get('Content-Encoding', 'identity') == 'identity'
                         and 'application/json' in response.headers.get('Content-Type', ''), 'HTTP_FORMAT_REJECTED')
                    result = parse(response.read(MAX_BYTES + 1))
                    status = response.code
                self.cookies.persist()
                return status, result
        except (OSError, urllib.error.URLError, reads.CheckFailed, warm.CheckFailed):
            raise Failed('HTTP_RESULT_UNCONFIRMED') from None

    def login(self):
        # A resumed process invalidates any captured previous session first.
        if any(cookie.name == 'SESSION_ID' for cookie in self.cookies) \
                or max(self.journal.latest_sequence('LOGOUT_INTENT'), self.journal.latest_sequence('LOGIN_INTENT')) \
                    > self.journal.latest_sequence('LOGOUT_VERIFIED'):
            self.logout()
        self.journal.add('LOGIN_INTENT', {})
        self.login_started = True
        credential = self.inputs['credential']
        status, body = self.request('POST', '/api/v1/auth/login', {key: credential[key] for key in ('email', 'password')})
        self.journal.add('LOGIN_HTTP_RETURNED', {'status': status})
        need(status == 200 and body.get('success') is True and any(cookie.name == 'SESSION_ID' and cookie.value
             and not cookie.is_expired() for cookie in self.cookies), 'NORMAL_LOGIN_REQUIRED')
        self.login_response_received = True
        status, body = self.request('GET', '/api/v1/auth/me')
        user, host = body.get('data'), self.inputs['host']
        need(status == 200 and body.get('success') is True and isinstance(user, dict)
             and user.get('id') == host['memberId'] and all(user.get(key) == host[key] for key in ('email', 'nickname')),
             'AUTHENTICATED_HOST_MISMATCH')
        self.journal.add('LOGIN_OWNER_VERIFIED', {'loginStatus': 200, 'meStatus': 200})

    def logout(self):
        login_unconfirmed = (self.login_started and not self.login_response_received) or (
            self.journal.latest_sequence('LOGIN_INTENT') > self.journal.latest_sequence('LOGOUT_VERIFIED'))
        previous = self.journal.last('LOGOUT_INTENT')
        if previous and self.journal.latest_sequence('LOGOUT_INTENT') > self.journal.latest_sequence('LOGOUT_VERIFIED'):
            archive = private_path(self.journal.path / previous['cookieArchive'])
            self.cookies.clear()
            self.cookies.load(str(archive), ignore_discard=True, ignore_expires=True)
        had_cookie = any(cookie.name == 'SESSION_ID' and cookie.value for cookie in self.cookies)
        self.cookies.persist()
        cookie_archive = f'.owned-session-{len(self.journal.entries):04d}'
        write_new(self.journal.path / cookie_archive, private_path(self.cookies.path).read_bytes())
        self.journal.add('LOGOUT_INTENT', {'cookieArchive': cookie_archive})
        status, body = self.request('POST', '/api/v1/auth/logout')
        need(status in (200, 401), 'OWNED_SESSION_LOGOUT_FAILED')
        # A Set-Cookie deletion is only a browser change. Prove the original
        # owned credential no longer authenticates, even if logout cleared it.
        self.cookies.clear()
        self.cookies.load(str(self.journal.path / cookie_archive), ignore_discard=True, ignore_expires=True)
        status, _ = self.request('GET', '/api/v1/auth/me')
        need(status == 401, 'LOGOUT_DID_NOT_INVALIDATE_SESSION')
        self.cookies.clear()
        self.cookies.persist()
        if login_unconfirmed and not had_cookie:
            self.journal.add('LOGIN_SESSION_UNCONFIRMED', {})
            raise Failed('LOGIN_SESSION_ID_UNCONFIRMED')
        self.journal.add('LOGOUT_VERIFIED', {'meStatus': 401})


def snapshot_rows_equal(left, right):
    return left['schemaSha256'] == right['schemaSha256'] and all(left[table] == right[table] for table in COLUMNS) \
        and left['meta']['historyCount'] == right['meta']['historyCount'] \
        and left['meta']['historyMaxId'] == right['meta']['historyMaxId'] \
        and left['meta']['outboxCount'] == right['meta']['outboxCount']


class Verifier:
    def __init__(self, inputs, adapter, journal, *, session_factory=Session, clock=time.monotonic, sleep=time.sleep):
        self.inputs, self.adapter, self.journal = inputs, adapter, journal
        self.factory, self.clock, self.sleep = session_factory, clock, sleep
        self.started, self.started_utc = clock(), utc()
        self.deadline = self.started + inputs['config']['maximumSeconds']
        self.failure = None
        self.session_cleanup_failure = None

    def limit(self):
        need(self.clock() < self.deadline, 'CDC_WORK_DEADLINE_REACHED')

    def baseline(self):
        existing = self.journal.last('BASELINE')
        if existing:
            return existing
        runtime = self.adapter.guard('running')
        health = self.adapter.connector_health()
        consumer = self.adapter.consumer_health()
        end_offsets = self.adapter.end_offsets()
        committed = self.adapter.committed()
        snapshot = self.adapter.snapshot()
        config = self.inputs['config']
        name = assert_baseline(snapshot, config['ownerMemberId'], config['accommodationId'])
        uid = str(uuid.UUID(bytes=bytes.fromhex(snapshot['accommodation'][0]['accommodation_uid'])))
        need(self.adapter.document(uid) == name, 'BASELINE_ES_NAME_MISMATCH')
        value = {'snapshot': snapshot, 'originalName': name, 'aggregateUid': uid, 'runtime': runtime,
                 'connectorHealth': health, 'consumerHealth': consumer,
                 'topicEndOffsets': end_offsets, 'committedOffsets': committed, 'inputBinding': self.inputs['binding']}
        self.journal.add('BASELINE', value)
        return value

    def propagate(self, step, intent, observation):
        done = self.journal.last(f'STEP_{step}_PROPAGATED')
        if done:
            return done
        started, deadline = self.clock(), min(self.deadline, self.clock() + self.inputs['config']['propagationTimeoutSeconds'])
        starts = {int(key): value for key, value in intent['startOffsets'].items()}
        event = observation['change']['searchEvent']
        while self.clock() < deadline:
            ends = self.adapter.end_offsets()
            need(set(ends) == set(starts) == {0, 1, 2}
                 and all(integer(ends[key]) and 0 <= ends[key] - starts[key] <= 64 for key in starts)
                 and sum(ends[key] - starts[key] for key in starts) <= 64, 'KAFKA_RANGE_CHANGED_OR_UNBOUNDED')
            matched = []
            for partition in starts:
                self.limit()
                if ends[partition] > starts[partition]:
                    matched.extend(self.adapter.records(partition, starts[partition], ends[partition], event))
            commits = self.adapter.committed()
            if matched and all(integer(commits[row['partition']]) and commits[row['partition']] > row['offset'] for row in matched):
                name = self.adapter.document(observation['change']['aggregateUid'])
                if name == intent['expectedName']:
                    proof = {'step': step, 'eventId': event['event_id'], 'aggregateUid': event['aggregate_id'],
                             'records': matched, 'committedOffsets': commits, 'esNameMatches': True,
                             'elapsedSeconds': round(self.clock() - started, 6), 'completedAt': utc()}
                    self.journal.add(f'STEP_{step}_PROPAGATED', proof)
                    return proof
            self.sleep(min(2, max(0, deadline - self.clock())))
        raise Failed('CDC_PROPAGATION_UNCONFIRMED')

    def step(self, number, before, name, session):
        prefix = f'STEP_{number}_'
        intent = self.journal.last(prefix + 'INTENT')
        observed = self.journal.last(prefix + 'OBSERVED')
        if self.journal.last(prefix + 'PROPAGATED'):
            need(observed is not None, 'PROPAGATION_WITHOUT_ROW_PROOF')
            return observed['after']
        self.limit()
        self.adapter.guard('running')
        if intent is None:
            need(self.adapter.snapshot() == before, 'BEFORE_PATCH_SNAPSHOT_CHANGED')
            intent = {'expectedName': name, 'beforeSha256': digest(encoded(before)),
                      'startOffsets': self.adapter.end_offsets(), 'apiPath': '/api/v1/accommodations/' + str(self.inputs['config']['accommodationId'])}
            # Persist the unique intent before the request can leave this process.
            self.journal.add(prefix + 'INTENT', intent)
            self.limit()
            try:
                status, response = session.request('PATCH', intent['apiPath'], {'name': name})
                self.journal.add(prefix + 'HTTP_RETURNED', {'status': status})
                need(status == 200 and response.get('success') is True, 'PATCH_RESULT_UNCONFIRMED')
                self.journal.add(prefix + 'HTTP_OK', {'status': 200})
            except BaseException:
                self.journal.add(prefix + 'HTTP_UNCONFIRMED', {})
                try:
                    self.journal.add(prefix + 'AFTER_CAPTURED', {'after': self.adapter.snapshot()})
                except Exception:
                    self.journal.add(prefix + 'AFTER_CAPTURE_UNCONFIRMED', {})
                raise
        need(intent['expectedName'] == name and intent['beforeSha256'] == digest(encoded(before)), 'INTENT_BINDING_CHANGED')
        if observed is None:
            after = self.adapter.snapshot()
            self.journal.add(prefix + 'AFTER_CAPTURED', {'after': after})
            # An ambiguous request with unchanged rows is never replayed: it may
            # still commit, or the process may have died before sending it.
            need(after != before, 'API_COMMIT_OUTCOME_UNCONFIRMED_NO_REPLAY')
            change = validate_change(before, after, name, self.inputs['config']['ownerMemberId'])
            observed = {'after': after, 'change': change, 'httpConfirmed': self.journal.last(prefix + 'HTTP_OK') is not None}
            self.journal.add(prefix + 'OBSERVED', observed)
        need(self.adapter.snapshot() == observed['after'], 'OBSERVED_OWNED_ROWS_CHANGED')
        self.propagate(number, intent, observed)
        return observed['after']

    def verify(self):
        need(self.journal.last('RESET_ROWS_INTENT') is None, 'RESET_ALREADY_STARTED')
        baseline = self.baseline()
        need(self.journal.last('LOGIN_SESSION_UNCONFIRMED') is None, 'PRIOR_LOGIN_SESSION_NEEDS_RECONCILIATION')
        changed = 'airbob-cdc-' + self.journal.entries[0]['data']['nonce']
        need(0 < len(changed) <= 50 and changed != baseline['originalName'], 'BOUNDED_UNIQUE_NAME_REQUIRED')
        session = self.factory(self.adapter, self.journal, self.inputs, [changed, baseline['originalName']])
        if self.journal.last('API_ROUND_TRIP_OBSERVED'):
            if not self.journal.session_clean():
                self.adapter.guard('running')
                session.logout()
            need(self.journal.last('API_ROUND_TRIP_OBSERVED')['httpConfirmed'], 'HTTP_OUTCOME_REMAINS_UNCONFIRMED')
            return
        try:
            self.limit()
            self.adapter.guard('running')
            self.limit()
            session.login()
            after = self.step(1, baseline['snapshot'], changed, session)
            self.step(2, after, baseline['originalName'], session)
            self.journal.add('API_ROUND_TRIP_OBSERVED', {
                'httpConfirmed': all(self.journal.last(f'STEP_{step}_OBSERVED')['httpConfirmed'] for step in (1, 2)),
                'normalPatchCount': 2, 'nameRestoredThroughApi': True})
        finally:
            try:
                session.logout()
            except Exception as error:
                self.session_cleanup_failure = error.code if isinstance(error, Failed) else 'OWNED_SESSION_CLEANUP_UNCONFIRMED'
                self.journal.add('SESSION_CLEANUP_UNCONFIRMED', {'code': self.session_cleanup_failure})
        need(self.session_cleanup_failure is None, 'OWNED_SESSION_CLEANUP_UNCONFIRMED')
        need(self.journal.last('API_ROUND_TRIP_OBSERVED')['httpConfirmed'], 'HTTP_OUTCOME_REMAINS_UNCONFIRMED')

    def reset(self):
        need(self.journal.last('API_ROUND_TRIP_OBSERVED') is not None
             and all(self.journal.last(f'STEP_{step}_PROPAGATED') for step in (1, 2))
             and self.journal.session_clean(), 'VERIFIED_API_REVERT_AND_SESSION_CLEANUP_REQUIRED')
        baseline = self.journal.last('BASELINE')['snapshot']
        expected = self.journal.last('STEP_2_OBSERVED')['after']
        if self.journal.last('RESET_COMPLETE'):
            self.adapter.guard('frozen')
            need(self.adapter.snapshot() == baseline, 'RESET_BASELINE_CHANGED')
            return
        with self.adapter.cleanup_window():
            current = self.adapter.snapshot()
            if self.journal.last('RESET_ROWS_INTENT') is None:
                need(current == expected, 'RESET_EXPECTED_ROWS_CHANGED')
                self.journal.add('RESET_ROWS_INTENT', {'expectedSha256': digest(encoded(expected)),
                                                     'heartbeatOffsets': self.adapter.end_offsets(heartbeat=True)})
            if current == expected:
                self.adapter.reset_rows(baseline, expected)
                current = self.adapter.snapshot()
            else:
                # A crash after COMMIT but before journaling is recoverable only
                # when every owned row and all original rows already match.
                need(snapshot_rows_equal(current, baseline), 'PARTIAL_OR_FOREIGN_RESET_STATE')
            need(snapshot_rows_equal(current, baseline), 'ROW_RESET_VERIFICATION_FAILED')
            need(current['meta']['autoIncrement']['accommodation'] == baseline['meta']['autoIncrement']['accommodation']
                 and all(current['meta']['autoIncrement'][table] in (baseline['meta']['autoIncrement'][table],
                         expected['meta']['autoIncrement'][table]) for table in ('outbox', 'accommodation_history')),
                 'AUTO_INCREMENT_CAS_MISMATCH')
            self.journal.add('RESET_ROWS_CONFIRMED', {'baselineRowsMatch': True})
            for table in ('outbox', 'accommodation_history'):
                self.limit()
                current = self.adapter.snapshot()
                need(snapshot_rows_equal(current, baseline), 'ROWS_CHANGED_BEFORE_COUNTER_RESET')
                now = current['meta']['autoIncrement'][table]
                original = baseline['meta']['autoIncrement'][table]
                allocated = expected['meta']['autoIncrement'][table]
                need(now in (original, allocated), 'AUTO_INCREMENT_CAS_MISMATCH')
                self.journal.add('RESET_COUNTER_INTENT', {'table': table, 'observed': now, 'original': original})
                if now != original:
                    self.adapter.reset_counter(table, original)
                need(self.adapter.snapshot()['meta']['autoIncrement'][table] == original, 'COUNTER_RESTORE_UNCONFIRMED')
                self.journal.add('RESET_COUNTER_CONFIRMED', {'table': table, 'value': original})
            need(self.adapter.snapshot() == baseline, 'FINAL_OWNED_BASELINE_MISMATCH')
        self.journal.add('RESET_COMPLETE', {'historyRowsRemoved': 2, 'outboxRowsRemoved': 4,
                                          'accommodationColumnsRestored': ['updated_at', 'updated_by'],
                                          'historyColumnsRestored': ['valid_to'], 'autoIncrementRestored': True,
                                          'binlogKeptEnabled': True, 'writerFenceRestored': True})

    def post_reset(self):
        need(self.journal.last('RESET_COMPLETE') is not None, 'FENCED_RESET_REQUIRED')
        baseline = self.journal.last('BASELINE')
        original = self.journal.last('RESET_ROWS_INTENT')['heartbeatOffsets']
        original = {int(key): value for key, value in original.items()}
        deadline = min(self.deadline, self.clock() + self.inputs['config']['propagationTimeoutSeconds'])
        runtime = self.adapter.guard('restarted')
        need(runtime['containers']['debezium']['startedAt'] != baseline['runtime']['containers']['debezium']['startedAt'],
             'EXTERNAL_CONNECTOR_RESTART_REQUIRED')
        while self.clock() < deadline:
            health = self.adapter.connector_health()
            consumer_health = self.adapter.consumer_health()
            current = self.adapter.end_offsets(heartbeat=True)
            committed = self.adapter.committed()
            need(self.adapter.document(baseline['aggregateUid']) == baseline['originalName'], 'POST_RESET_ES_NAME_CHANGED')
            if set(current) == set(original) and all(current[key] > original[key] for key in original):
                for step in (1, 2):
                    proof = self.journal.last(f'STEP_{step}_PROPAGATED')
                    need(all(integer(committed[row['partition']]) and committed[row['partition']] > row['offset']
                             for row in proof['records']), 'POST_RESET_CONSUMER_OFFSET_REGRESSED')
                self.journal.add('POST_RESET_HEALTHY', {'connector': health, 'heartbeatAdvanced': True,
                                                       'consumer': consumer_health, 'committedOffsets': committed, 'esOriginalNameMatches': True})
                return
            self.sleep(min(2, max(0, deadline - self.clock())))
        raise Failed('POST_RESET_HEARTBEAT_UNCONFIRMED')

    def public(self, action, passed):
        steps = []
        for number in (1, 2):
            observed = self.journal.last(f'STEP_{number}_OBSERVED')
            proof = self.journal.last(f'STEP_{number}_PROPAGATED')
            steps.append({'step': number, 'httpConfirmed': bool(observed and observed['httpConfirmed']),
                          'outboxRowsObserved': len(observed['change']['outboxEvents']) if observed else 0,
                          'propagation': proof})
        roundtrip = self.journal.last('API_ROUND_TRIP_OBSERVED')
        return {'schemaVersion': 1, 'kind': 'global-b-owned-api-cdc-verification', 'action': action, 'phasePassed': passed,
                'startedAt': self.started_utc, 'completedAt': utc(), 'elapsedSeconds': round(self.clock() - self.started, 6),
                'inputBinding': self.inputs['binding'], 'journalHeadSha256': self.journal.last_sha,
                'mysqlServerUuid': self.inputs['config']['mysqlServerUuid'], 'steps': steps,
                'apiRoundTripVerified': bool(roundtrip and roundtrip['httpConfirmed']),
                'cleanup': self.journal.last('RESET_COMPLETE'), 'postResetHealth': self.journal.last('POST_RESET_HEALTHY'),
                'failureCode': self.failure, 'sessionCleanupFailureCode': self.session_cleanup_failure,
                'fullPreparedDatasetVerified': False, 'externalFullRowsDdlAndInventoryVerificationRequired': True,
                'rawRowsBodiesCookiesCredentialsRecordedPublicly': False,
                'deleteSmtEvidence': {'version': '3.0.8.Final', 'coreJarSha256': CORE_JAR_SHA, 'source': DELETE_SOURCE}}


def example_config():
    pin = lambda name, cid: {'name': name, 'id': cid, 'image': '<exact docker image ID sha256>'}
    return {'schemaVersion': 1, 'releaseDirectory': '/home/ubuntu/.airbob-b-staging/cloud-load-20260912-b4a1/final/release',
            'datasetId': '<sealed final datasetId>', 'consumerManifestSha256': '<sealed SHA256>', 'checksumsSha256': '<sealed SHA256>',
            'privateAccounts': '/home/ubuntu/.airbob-b-staging/cloud-load-20260912-b4a1/.private/final/accounts.private.json',
            'accountEnvironment': 'oci:mysql:<sealed final datasetId>', 'ownerMemberId': 6675, 'accommodationId': 16102,
            'mysqlServerUuid': '6bd481f4-ae9b-11f1-96ce-46fc8717c749', 'dockerNetwork': 'airbob_airbob-network',
            'containers': {'mysql': pin('mysql', '8cacb45a93923c442a786f1953e59527088f8696865db39c75f24df15a7a8109'),
                           'app': pin('airbob-app', '1acb8a86731c48d1c97e4c122796dabec4c3afab69a3af6bd2f2c0fcead4a963'),
                           'debezium': pin('debezium', '1b85abb011a65b8a433c562f47f75c17b0370ed724eaa6f4ea511c1388e77ea8'),
                           'kafka': pin('kafka', '<observed exact CID>'), 'elasticsearch': pin('elasticsearch', '<observed exact CID>')},
            'preconditions': {'preparedFingerprint': {'path': '<current prepared fingerprint path>', 'sha256': '<SHA256>'},
                              'preparationReceipt': {'path': '<current preparation receipt path>', 'sha256': '<SHA256>'},
                              'serviceReceipt': {'path': '<current service gate receipt path>', 'sha256': '<SHA256>'},
                              'exclusiveWriterWindow': True, 'detailCacheState': 'DISABLED'},
            'requestTimeoutSeconds': 10, 'propagationTimeoutSeconds': 180, 'maximumSeconds': 900}


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('example-config', 'preflight', 'verify', 'reset', 'post-reset'))
    parser.add_argument('--config', type=Path)
    parser.add_argument('--journal', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(argv)
    if args.action == 'example-config':
        print(json.dumps(example_config(), indent=2))
        return 0
    journal, verifier = None, None
    try:
        need(args.config and args.journal and args.output, 'CONFIG_JOURNAL_OUTPUT_REQUIRED')
        config_raw = reads.read_bytes(args.config)
        inputs = read_inputs(parse(config_raw))
        output = args.output.absolute()
        output.mkdir(mode=0o700, parents=False, exist_ok=True)
        private_path(output, directory=True)
        journal = Journal(args.journal, digest(config_raw), digest(encoded(inputs['binding']['toolIdentity'])))
        adapter = DockerAdapter(inputs['config'], journal)
        verifier = Verifier(inputs, adapter, journal)
        for sig in (signal.SIGTERM, signal.SIGINT):
            signal.signal(sig, lambda *_: (_ for _ in ()).throw(Failed('INTERRUPTED_WITH_JOURNAL_RETAINED')))
        action = {'preflight': verifier.baseline, 'verify': verifier.verify, 'reset': verifier.reset,
                  'post-reset': verifier.post_reset}[args.action]
        action()
        report = verifier.public(args.action, True)
        write_new(output / f'{args.action}-{len(journal.entries):04d}.json', encoded(report))
        print(json.dumps({'phasePassed': True, 'action': args.action, 'journalHeadSha256': journal.last_sha,
                          'fullPreparedDatasetVerified': False}))
        return 0
    except BaseException as error:
        code = error.code if isinstance(error, (Failed, reads.CheckFailed, warm.CheckFailed)) else 'CDC_EXECUTION_UNCONFIRMED'
        if verifier:
            verifier.failure = code
            try:
                journal.add('EXECUTION_UNCONFIRMED', {'action': args.action, 'code': code})
                write_new(output / f'{args.action}-failed-{len(journal.entries):04d}.json', encoded(verifier.public(args.action, False)))
            except Exception:
                pass
        print(json.dumps({'phasePassed': False, 'failureCode': code, 'privateJournalRetained': journal is not None}), file=sys.stderr)
        return 1
    finally:
        if journal:
            journal.close()


if __name__ == '__main__':
    sys.exit(main())
