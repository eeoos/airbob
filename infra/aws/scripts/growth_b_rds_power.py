#!/usr/bin/env python3
"""Single RDS power controller invoked only by terraform_data.rds_power.

AWS provider 6.55.0's native state resource waits for available before Start.
This helper uses the same caller credentials, never changes DB configuration,
and records an intent before its sole state-changing request. Unknown requests
are observed, not resubmitted. No credentials or raw API responses are logged.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time

ACCOUNT = '942632789808'
REGION = 'ap-northeast-2'


class Rejected(ValueError):
    pass


def need(value, code):
    if not value:
        raise Rejected(code)


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False) + '\n').encode()


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def write_new(path, value):
    path = Path(path)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(encoded(value)); stream.flush(); os.fsync(stream.fileno())
    sync_directory(path.parent)


def sync_directory(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try: os.fsync(fd)
    finally: os.close(fd)


def read_own(path):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        need(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
             and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 65536, 'OWNED_PUBLIC_RECORD_REQUIRED')
        return json.loads(stream.read(65537))


class Aws:
    def call(self, *args):
        env = dict(os.environ, AWS_MAX_ATTEMPTS='1', AWS_RETRY_MODE='standard', AWS_PAGER='')
        result = subprocess.run(['aws', '--region', REGION, '--no-cli-pager', '--output', 'json',
            '--cli-connect-timeout', '10', '--cli-read-timeout', '30', *args],
            env=env, capture_output=True, timeout=45)
        if result.returncode:
            raise Rejected(aws_error(result.stderr))
        need(len(result.stdout) <= 2 * 1024**2, 'AWS_RESPONSE_TOO_LARGE')
        return json.loads(result.stdout or b'{}')


def aws_error(stderr):
    # Closed categories only; never surface service messages or credential data.
    if any(x in stderr for x in (b'ExpiredToken', b'InvalidClientTokenId', b'UnrecognizedClient',
        b'Unable to locate credentials', b'Token has expired', b'Error loading SSO Token', b'SSO session')):
        return 'AWS_AUTHENTICATION_UNAVAILABLE_STOP'
    if any(x in stderr for x in (b'AccessDenied', b'UnauthorizedOperation', b'InvalidParameter', b'InvalidDBInstanceState')):
        return 'AWS_REQUEST_REJECTED'
    return 'AWS_CALL_FAILED'


def validate_request(r):
    need(isinstance(r, dict) and set(r) == {'schemaVersion', 'kind', 'operationId', 'runId',
        'resourceFencingToken', 'expiresAt', 'deadlineEpoch', 'desiredState', 'identifier',
        'resourceId', 'region', 'accountId', 'lease', 'evidenceDirectory'}, 'CLOSED_POWER_REQUEST_REQUIRED')
    need(r['schemaVersion'] == 1 and r['kind'] == 'airbob-rds-power-request'
         and r['accountId'] == ACCOUNT and r['region'] == REGION, 'POWER_ACCOUNT_OR_REGION_CHANGED')
    need(re.fullmatch(r'lab-[a-z0-9][a-z0-9-]{0,27}', r['runId'])
         and r['identifier'] == 'airbob-' + r['runId'] and re.fullmatch(r'db-[A-Z0-9]+', r['resourceId'])
         and re.fullmatch(r'[a-z0-9][a-z0-9-]{2,47}', r['operationId']), 'POWER_TARGET_INVALID')
    need(r['desiredState'] in {'available', 'stopped'} and type(r['resourceFencingToken']) is int
         and r['resourceFencingToken'] > 0 and type(r['deadlineEpoch']) is int
         and type(r['expiresAt']) is int and r['deadlineEpoch'] <= r['expiresAt'], 'POWER_DEADLINE_INVALID')
    lease = r['lease']
    need(set(lease) == {'table', 'lockName', 'owner', 'runId', 'command', 'fencingToken'}
         and lease['table'] == 'airbob-performance-lab-orchestration-lease'
         and lease['runId'] == r['runId'] and lease['command'] == 'up'
         and type(lease['fencingToken']) is int and lease['fencingToken'] > 0
         and re.fullmatch(r'[A-Za-z0-9_.:/-]{3,255}', lease['lockName'])
         and re.fullmatch(r'[A-Za-z0-9._:@/-]{3,128}', lease['owner']), 'POWER_LEASE_INVALID')
    need(Path(r['evidenceDirectory']).is_absolute(), 'POWER_EVIDENCE_PATH_INVALID')
    return r


def guard(aws, request, now=time.time):
    need(int(now()) < request['deadlineEpoch'] <= request['expiresAt'], 'POWER_DEADLINE_EXPIRED')
    lease = request['lease']
    item = aws.call('dynamodb', 'get-item', '--table-name', lease['table'], '--consistent-read',
        '--key', json.dumps({'LockName': {'S': lease['lockName']}})).get('Item', {})
    need(all(item.get(key, {}).get('S') == lease[name] for key, name in
        [('Owner', 'owner'), ('RunId', 'runId'), ('Command', 'command')])
        and item.get('FencingToken', {}).get('N') == str(lease['fencingToken'])
        and all(int(item.get(key, {}).get('N', '0')) > int(now()) for key in ('ExpiresAt', 'CommandDeadline')),
        'POWER_LEASE_LOST')


def observe(aws, request):
    rows = aws.call('rds', 'describe-db-instances', '--db-instance-identifier', request['identifier']).get('DBInstances', [])
    need(len(rows) == 1, 'EXACT_RDS_REQUIRED')
    row = rows[0]
    fixed = {'DBInstanceIdentifier': request['identifier'], 'DbiResourceId': request['resourceId'],
        'DBInstanceArn': f'arn:aws:rds:{REGION}:{ACCOUNT}:db:{request["identifier"]}',
        'DBInstanceClass': 'db.t3.small', 'Engine': 'mysql', 'EngineVersion': '8.4.11',
        'AllocatedStorage': 100, 'StorageType': 'gp3', 'StorageEncrypted': True,
        'PubliclyAccessible': False, 'MultiAZ': False, 'PendingModifiedValues': {}}
    need(all(row.get(k) == v and type(row.get(k)) is type(v) for k, v in fixed.items()), 'RDS_IDENTITY_OR_CONFIGURATION_CHANGED')
    tags = {x['Key']: x['Value'] for x in row.get('TagList', [])}
    required = {'RunId': request['runId'], 'FencingToken': str(request['resourceFencingToken']),
        'ExpiresAt': str(request['expiresAt']), 'Project': 'airbob', 'Environment': 'performance-lab',
        'Stack': 'lab', 'ManagedBy': 'terraform', 'Persistence': 'ephemeral', 'Service': 'rds'}
    need(tags == required and len(tags) == len(row.get('TagList', [])), 'RDS_TAGS_CHANGED')
    need(row.get('DBInstanceStatus') in {'available', 'stopped', 'starting', 'stopping'}, 'RDS_STATE_UNSUPPORTED')
    return row['DBInstanceStatus']


def execute(request, aws=None, *, now=time.time, sleep=time.sleep):
    request = validate_request(request); aws = aws or Aws()
    need(aws.call('sts', 'get-caller-identity').get('Account') == ACCOUNT, 'AWS_CALLER_CHANGED')
    directory = Path(request['evidenceDirectory'])
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    need(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700
         and directory.resolve() == directory, 'PRIVATE_OWNED_EVIDENCE_DIRECTORY_REQUIRED')
    stem = request['operationId'] + '-rds-' + request['desiredState']
    intent_path, result_path = directory / (stem + '-intent.json'), directory / (stem + '-result.json')
    stable = {k: v for k, v in request.items() if k not in {'lease', 'deadlineEpoch'}}
    binding = digest(stable)
    prior = None
    if intent_path.exists():
        need(not intent_path.is_symlink(), 'POWER_INTENT_SYMLINK')
        prior = read_own(intent_path)
        need(prior.get('bindingSha256') == binding, 'POWER_INTENT_CHANGED')
    guard(aws, request, now)
    initial = observe(aws, request); desired = request['desiredState']
    if prior is None:
        prior = {'schemaVersion': 1, 'kind': 'airbob-rds-power-intent', 'bindingSha256': binding,
            'initialState': initial, 'desiredState': desired, 'observedAtEpoch': int(now()),
            'requestMayBeSubmitted': initial == ('stopped' if desired == 'available' else 'available')}
        write_new(intent_path, prior)
        if prior['requestMayBeSubmitted']:
            guard(aws, request, now)
            need(observe(aws, request) == initial, 'RDS_STATE_CHANGED_BEFORE_SUBMISSION')
            try:
                aws.call('rds', 'start-db-instance' if desired == 'available' else 'stop-db-instance',
                    '--db-instance-identifier', request['identifier'])
            except Rejected as error:
                # The durable intent fences an uncertain request on every retry.
                # Only subsequent actual state observations may establish success.
                if str(error) != 'AWS_CALL_FAILED': raise
            except subprocess.TimeoutExpired:
                pass
    while True:
        guard(aws, request, now)
        state = observe(aws, request)
        if state == desired:
            proof = {'schemaVersion': 1, 'kind': 'airbob-rds-power-result', 'state': 'RDS_POWER_VERIFIED',
                'bindingSha256': binding, 'identifier': request['identifier'], 'resourceId': request['resourceId'],
                'desiredState': desired, 'observedAtEpoch': int(now()), 'apiResubmitted': False,
                'implementation': 'terraform-data-state-aware-rds-provider-6.55-workaround'}
            if not result_path.exists():
                write_new(result_path, proof)
            else:
                old = read_own(result_path)
                need(old.get('bindingSha256') == binding and old.get('state') == 'RDS_POWER_VERIFIED', 'POWER_RESULT_CHANGED')
            return proof
        need(state == ('starting' if desired == 'available' else 'stopping') or state == prior['initialState'],
             'RDS_STATE_CHANGED_DURING_WAIT')
        sleep(min(10, max(0, request['deadlineEpoch'] - now())))


def main():
    try:
        request = json.loads(os.environ['AIRBOB_RDS_POWER_REQUEST'])
        proof = execute(request)
        print(json.dumps({'state': proof['state'], 'desiredState': proof['desiredState']}))
        return 0
    except Exception as error:
        print(json.dumps({'state': 'POWER_INCOMPLETE', 'failureCode': str(error) if isinstance(error, Rejected) else type(error).__name__}), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
