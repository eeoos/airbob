"""Synthetic AWS CDC rows and Kafka records; no application or remote imports."""
import copy
import json
import uuid
from growth_b_cdc_core import core as cdc

OWNER, TARGET = 6675, 16102
UID = '12345678-1234-4123-8123-123456789abc'
ORIGINAL = 'ORIGINAL_PRIVATE_NAME'


def cells(table, **values):
    return {name: None if values.get(name) is None else cdc.hex_text(str(values[name])) for name in cdc.COLUMNS[table]}


def baseline():
    accommodation = cells('accommodation', id=TARGET, member_id=OWNER, name=ORIGINAL, status='PUBLISHED',
        created_at='2026-01-01 00:00:00.000000', updated_at='2026-01-01 00:00:00.000000',
        created_by=OWNER, time_zone_id='UTC', base_price=1000, currency='KRW', check_in_time='15:00:00', check_out_time='11:00:00')
    accommodation['accommodation_uid'] = uuid.UUID(UID).hex.upper()
    history = cells('accommodation_history', id=100, accommodation_id=TARGET, accommodation_uid=UID,
        member_id=OWNER, name=ORIGINAL, status='PUBLISHED', created_at='2026-01-01 00:00:00.000000', created_by=OWNER,
        time_zone_id='UTC', base_price=1000, currency='KRW', check_in_time='15:00:00', check_out_time='11:00:00',
        history_created_at='2026-01-01 00:00:00.000000', history_created_by=OWNER, change_type='CREATE',
        valid_from='2026-01-01 00:00:00.000000', valid_to=cdc.FOREVER)
    return {'accommodation': [accommodation], 'accommodation_history': [history], 'outbox': [],
            'schemaSha256': 'f' * 64, 'meta': {'autoIncrement': {'accommodation': 800000, 'accommodation_history': 200, 'outbox': 10},
            'historyCount': 1, 'historyMaxId': 150, 'outboxCount': 0}}


def changed(before, name, number):
    after = copy.deepcopy(before)
    when = f'2026-09-13 00:00:0{number}.123456'
    accom = after['accommodation'][0]
    accom.update(name=cdc.hex_text(name), updated_at=cdc.hex_text(when), updated_by=cdc.hex_text(str(OWNER)))
    current = next(row for row in after['accommodation_history'] if cdc.text_cell(row['valid_to']) == cdc.FOREVER)
    new = copy.deepcopy(current)
    current['valid_to'] = cdc.hex_text(when)
    new.update(id=cdc.hex_text(str(before['meta']['autoIncrement']['accommodation_history'])), name=accom['name'],
               valid_from=cdc.hex_text(when), valid_to=cdc.hex_text(cdc.FOREVER), history_created_at=cdc.hex_text(when),
               history_created_by=cdc.hex_text(str(OWNER)), change_type=cdc.hex_text('UPDATE'),
               change_reason=cdc.hex_text('PRIVATE_HISTORY_REASON'), client_ip=cdc.hex_text('10.0.0.8'), source_system=cdc.hex_text('WEB'))
    after['accommodation_history'].append(new)
    for index, (topic, event_type, aggregate, payload) in enumerate([
            (cdc.TOPIC, cdc.SEARCH_TYPE, UID, {'accommodationUid': UID}),
            (cdc.CACHE_TOPIC, cdc.CACHE_TYPE, str(TARGET), {'accommodationId': TARGET, 'reason': 'ACCOMMODATION'})]):
        event_id = str(uuid.uuid5(uuid.NAMESPACE_OID, f'cdc-test-{number}-{index}'))
        value = {'eventId': event_id, 'eventType': event_type, 'eventVersion': '1',
                 'occurredAt': f'2026-09-13T00:00:0{number}.123456Z', 'payload': payload}
        after['outbox'].append(cells('outbox', id=before['meta']['autoIncrement']['outbox'] + index,
            event_id=event_id, destination=topic, partition_key=aggregate, aggregate_type='ACCOMMODATION',
            aggregate_id=aggregate, event_type=event_type, event_version='1', payload=json.dumps(value),
            occurred_at=when, created_at=when, updated_at=when, created_by=OWNER, updated_by=OWNER))
    after['meta']['autoIncrement']['accommodation_history'] += 1
    after['meta']['autoIncrement']['outbox'] += 2
    after['meta']['historyCount'] += 1
    after['meta']['historyMaxId'] = cdc.row_id(new)
    after['meta']['outboxCount'] += 2
    return after


def kafka_record(event, offset=7, partition=1):
    headers = {'id': event['event_id'], 'eventType': cdc.SEARCH_TYPE, 'eventVersion': '1',
               'aggregateType': 'ACCOMMODATION', 'aggregateId': UID}
    payload = {'eventId': event['event_id'], 'eventType': cdc.SEARCH_TYPE, 'eventVersion': '1',
               'occurredAt': '2026-09-13T00:00:01Z', 'payload': {'accommodationUid': UID}}
    return b'\x1f'.join([f'Partition:{partition}'.encode(), f'Offset:{offset}'.encode(),
                         b'\x1d'.join((key + ':' + value).encode() for key, value in headers.items()),
                         UID.encode(), json.dumps(payload).encode()]) + b'\x1e'



from pathlib import Path
warm = cdc.warm


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True)); path.chmod(0o600)


def seal(config):
    release = Path(config['releaseDirectory'])
    consumer = json.loads((release / 'consumer-manifest.json').read_text())
    checks = {'airbob-growth.sql.gz': 'a' * 64}
    for name, key in (('manifest.json', None), ('accounts.json', 'accounts'), ('base-scenario-targets.json', 'baseWorkloads')):
        checks[name] = warm.http.hash_bytes((release / name).read_bytes())
        if key: consumer['artifacts'][key] = {'file': name, 'sha256': checks[name]}
    write(release / 'consumer-manifest.json', consumer)
    checks['consumer-manifest.json'] = warm.http.hash_bytes((release / 'consumer-manifest.json').read_bytes())
    write(release / 'SHA256SUMS.json', checks)
    config.update(consumerManifestSha256=checks['consumer-manifest.json'],
                  checksumsSha256=warm.http.hash_bytes((release / 'SHA256SUMS.json').read_bytes()),
                  workloadSha256=checks['base-scenario-targets.json'])


def warm_metadata_fixture(root, *, small=False, aws=False):
    release = root / 'release'; release.mkdir()
    private = root / 'private'; private.mkdir(mode=0o700)
    dataset = 'global-growth-b-' + 'a' * 16
    environment = 'aws:db-RESOURCE123:11111111-1111-1111-1111-111111111111' if aws else 'oci:mysql:' + dataset
    targets = []
    for kind in warm.KINDS:
        for band in warm.BANDS:
            target = {'kind': kind, 'band': band, 'ownerMemberId': len(targets) + 4, 'fanout': 0, 'method': 'GET'}
            if kind in warm.KINDS[:2]: target['resourceId'] = 1000 + len(targets)
            target['path'] = warm.ROUTES[kind][0].format(resourceId=target.get('resourceId'))
            targets.append(target)
    accounts, representatives, credentials = [], [], []
    for number in range(1, (113 if small else 124) + 1):
        key, email, role = (('demo', 'demo@airbob.test', 'MEMBER'), ('host', 'host@airbob.test', 'MEMBER'),
                            ('admin', 'admin@airbob.test', 'ADMIN'))[number - 1] if number <= 3 else (None, f'unit{number}@example.test', 'MEMBER')
        account = {'memberId': number, 'email': email, 'nickname': 'PRIVATE-NICKNAME-' + str(number), 'role': role, 'status': 'ACTIVE'}
        accounts.append(account)
        if key: representatives.append(account | {'key': key})
        credentials.append({'memberId': number, 'email': email, 'role': role, 'password': 'PRIVATE-UNIQUE-PASSWORD-' + str(number),
                            'environment': environment, 'group': ('administrator' if number == 3 else 'representative') if key else 'qualification',
                            'purpose': 'offline qualification account', 'usable': False, 'loginState': 'NOT_VERIFIED'})
    bundle = {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'accounts': accounts, 'accountCount': len(accounts),
              'representativeAccounts': representatives, 'representativeAccountCount': 3, 'baseScenarioTargets': targets,
              'loadPoolPolicy': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'qualificationAccountsAreLoadPool': False,
                                 'oneMemberPerConcurrentWriter': True}}
    scale = 'small-qualification' if small else 'selected-global-b-ten-million'
    write(release / 'manifest.json', {'datasetId': dataset, 'datasetProfile': 'global-growth-b', 'datasetScale': scale,
                                    'finalScaleSelected': not small, 'state': 'SMALL_DB_AND_HTTP_QUALIFIED' if small else 'DATASET_DB_AND_HTTP_QUALIFIED'})
    write(release / 'accounts.json', bundle)
    write(release / 'base-scenario-targets.json', {'schemaVersion': 1, 'targets': targets, 'reset': {}})
    write(release / 'consumer-manifest.json', {'schemaVersion': 4, 'datasetVersion': 'benchmark-dataset-v4', 'datasetId': dataset,
        'datasetScale': scale, 'finalScaleSelected': not small, 'qualification': 'SMALL_LOCAL_SCENARIOS',
        'mysql': {'version': '8.4.11', 'flywayVersion': 28}, 'appJarSha256': 'b' * 64, 'artifacts': {}})
    private_file = private / 'accounts.private.json'
    write(private_file, {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'environment': environment, 'credentials': credentials,
                         'loadPool': {'state': 'NOT_CONFIGURED', 'targetConcurrentMembers': None, 'memberCount': 0}})
    config = {'schemaVersion': 1, 'releaseDirectory': str(release), 'datasetId': dataset, 'privateAccounts': str(private_file),
              'baseUrl': 'http://localhost:18080', 'targetMode': 'aws' if aws else 'oci', 'accountEnvironment': environment,
              'allowSmallQualification': small, 'requestTimeoutSeconds': 5,
              'preconditions': {'fingerprintState': 'PREPARED_ALL_ROWS_AND_DDL_VERIFIED', 'fingerprintReceiptSha256': 'c' * 64,
                  'appState': 'READINESS_VERIFIED_EXISTING_PROCESS', 'appImage': 'sha256:' + 'd' * 64,
                  'detailCacheState': 'ENABLED_EXISTING_CONTENTS'}}
    seal(config)
    return config, accounts, credentials, targets
