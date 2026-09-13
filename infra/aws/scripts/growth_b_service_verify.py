#!/usr/bin/env python3
"""Observe AWS B service reads, then verify the closed source before snapshot.

The controller owns every app/ASG/Connect/CDC mutation. This producer uses
actual HTTP responses and a live READ-fenced full database fingerprint. It
cannot accept caller-supplied success booleans or create an RDS snapshot.
"""
from __future__ import annotations
import argparse
import contextlib
import copy
import datetime as dt
import ipaddress
import json
import os
from pathlib import Path
import re
import select
import socket
import socketserver
import threading
import time
from types import SimpleNamespace
import urllib.request

import growth_b_aws_restore as restore
import growth_b_cdc as cdc_host
import growth_b_cdc_core as cdc
import growth_b_cdc_controller as controller
import growth_b_contract as contract
import growth_b_media as media
import growth_b_runtime as runtime_gate
import growth_b_snapshot as snapshot

core, need, Failed = cdc.core, cdc.need, cdc.Failed
KIND = 'global-growth-b-aws-service-verification-config'
LIVE_KIND = 'global-growth-b-aws-service-live-observation'
RESET_KIND = 'global-growth-b-aws-service-reset-verification'
SNAPSHOT_SHA = 'b2aa1e1cac9a30b98418a7244584bc2bca9451aed305213ac99ca742ac6dc9cd'
SOURCE_NAMES = ('growth_b_service_verify.py', 'growth_b_media.py', 'GlobalBImageDecode.java', 'growth_b_snapshot.py')
COMMAND_FIELDS = 'token name instanceId containerId commandSha256 startedEpoch commandId status completedEpoch'


def now():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def source_identity():
    root = Path(__file__).parent
    need(contract.sha(root / 'growth_b_snapshot.py') == SNAPSHOT_SHA, 'FROZEN_SNAPSHOT_SOURCE_CHANGED')
    need(contract.sha(root / 'GlobalBImageDecode.java') == media.DECODER_SHA, 'IMAGE_DECODER_SOURCE_CHANGED')
    return cdc.source_identity() | {'infra/aws/scripts/' + name: contract.sha(root / name) for name in SOURCE_NAMES}


def supervisor_sha():
    return contract.sha(Path(__file__).with_name('growth_b_cdc_supervisor.py'))


def ref(path):
    path = Path(path)
    return {'path': str(path), 'sha256': contract.sha(path)}


def public_ref(value):
    path = Path(value['path'])
    return {key: item for key, item in value.items() if key != 'path'} | {'bytes': path.stat().st_size}


def read_ref(value):
    return cdc.local_ref(value)


def shared_binding(configuration):
    value = copy.deepcopy(configuration)
    for key in ('lease', 'businessStartedEpoch', 'businessDeadlineEpoch'):
        value.pop(key)
    return core.digest(core.encoded(value))


def validate_config(value, *, check_files=True):
    expected = 'schemaVersion kind operation cdcConfiguration restoreConfig restoreReceipt controllerObservation deadlineEpoch'
    if value.get('operation') == 'finalize':
        expected += ' liveReceipt liveAttestation cdcReceipt controllerReceipt'
    cdc.fields(value, expected)
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['operation'] in ('live', 'finalize')
         and core.integer(value['deadlineEpoch'], 1), 'SERVICE_VERIFICATION_CONFIGURATION_REQUIRED')
    for name in expected.split():
        if name not in ('schemaVersion', 'kind', 'operation', 'deadlineEpoch'):
            if check_files:
                read_ref(value[name])
            else:
                reference = value[name]
                need(isinstance(reference, dict) and set(reference) in ({'path', 'sha256'}, {'path', 'sha256', 'key', 'versionId', 'bytes'})
                     and isinstance(reference['path'], str) and Path(reference['path']).is_absolute()
                     and contract.digest(reference['sha256']), 'EXACT_LOCAL_REFERENCE_REQUIRED')
    return value


def read_inputs(value):
    validate_config(value)
    configuration = read_ref(value['cdcConfiguration'])
    inputs = cdc.read_inputs(configuration)
    manifest = inputs['manifest']
    original = restore.configuration(Path(value['restoreConfig']['path']))
    need(value['restoreConfig']['sha256'] == manifest['preparation']['restoreConfigSha256']
         and value['restoreReceipt']['sha256'] == manifest['preparation']['restoreReceiptSha256'],
         'ORIGINAL_RESTORE_REFERENCES_REQUIRED')
    envelope = restore.validate_inputs(original)
    proof = read_ref(value['restoreReceipt'])
    initial = read_ref(configuration['preconditions']['preparedFingerprint'])
    need(restore.target_identity(original) == {key: configuration['rds'][key] for key in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}
         and original['release'] == configuration['releaseDirectory']
         and original['privateAccounts'] == configuration['privateAccounts']
         and envelope['datasetId'] == configuration['datasetId']
         and envelope['consumerManifestSha256'] == configuration['consumerManifestSha256']
         and envelope['checksumsSha256'] == configuration['checksumsSha256']
         and envelope['finalScaleSelected'] is True and envelope['awsExecutionAllowed'] is True,
         'RESTORE_SERVICE_TARGET_OR_SEAL_CHANGED')
    need(proof.get('schemaVersion') == 1 and proof.get('kind') == 'global-growth-b-aws-restore-receipt'
         and proof.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED'
         and proof.get('targetIdentity') == restore.target_identity(original)
         and proof.get('toolIdentity') == restore.tool_identity()
         and proof.get('envelopeSha256') == original['envelopeSha256']
         and proof.get('datasetId') == configuration['datasetId']
         and proof.get('account') == cdc.ACCOUNT and proof.get('region') == cdc.REGION
         and proof.get('mysqlVersion') == '8.4.11' and proof.get('flywayVersion') == 28
         and proof.get('allRowsAndDdlEqual') is True and proof.get('finalScaleSelected') is True
         and proof.get('executionScope') == 'final-b-rds' and proof.get('awsWritesExecuted') is True
         and proof.get('previousBusinessSchemaAbsent') is True and proof.get('maximumSimultaneousBusinessDatabases') == 1
         and proof.get('appJarSha256') == envelope['appJarSha256']
         and proof.get('migrationFilesSha256') == envelope['objects']['migration-files.json']['sha256']
         and proof.get('smallRdsPrerequisite', {}).get('sameToolApplicationAndV28Verified') is True
         and proof.get('preparedFingerprintSha256') == configuration['preconditions']['preparedFingerprint']['sha256']
         and proof.get('sealedFingerprint') == contract.read(Path(original['release']) / 'before-fingerprint.json'),
         'ACTUAL_FINAL_RESTORE_PROOF_REQUIRED')
    prepared = proof.get('preparation', {})
    need(prepared.get('passed') is True and prepared.get('readinessVerified') is True
         and prepared.get('accountLogins', {}).get('passed') is True and prepared.get('applicationLeftRunning') is False
         and prepared.get('currentInventory', {}).get('everyHorizonContiguous') is True
         and contract.digest(prepared.get('ownerSha256BeforeAndAfter')), 'ACTUAL_PREPARATION_PROOF_REQUIRED')
    contract.validate_fingerprint(initial, require_sealed=False)
    restore.validate_prepared_changes(proof['sealedFingerprint'], initial)
    need(value['deadlineEpoch'] <= min(configuration['expiresAt'], configuration['approvedExecutionDeadlineEpoch']),
         'APPROVED_RESOURCE_DEADLINE_EXCEEDED')
    return {'value': value, 'cdc': inputs, 'configuration': configuration, 'restoreConfig': original,
            'restoreReceipt': proof, 'initialFingerprint': initial, 'envelope': envelope,
            'toolIdentity': source_identity()}


def validate_app_observation(value, configuration, readiness, *, fresh_at=None):
    cdc.fields(value, 'schemaVersion kind configurationSha256 operationId lease supervisorSha256 command observation')
    need(value['schemaVersion'] == 1 and value['kind'] == 'global-b-aws-service-app-observation'
         and value['configurationSha256'] == cdc.journal_binding(configuration)
         and value['operationId'] == configuration['operationId'] and value['lease'] == configuration['lease']
         and value['supervisorSha256'] == supervisor_sha(), 'CONTROLLER_APP_ATTESTATION_CHANGED')
    command = value['command']; cdc.fields(command, COMMAND_FIELDS)
    pin = configuration['hosts']['app']
    import growth_b_cdc_supervisor as supervisor
    expected = supervisor.app_program(configuration, readiness)
    need(command['instanceId'] == pin['instanceId'] and command['containerId'] == pin['containerId']
         and command['commandSha256'] == core.digest(expected.encode()) and command['status'] == 'Success'
         and cdc.match(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', command['commandId'])
         and core.integer(command['startedEpoch'], 1) and command['startedEpoch'] <= command['completedEpoch'],
         'EXACT_APP_SSM_OBSERVATION_REQUIRED')
    observation = value['observation']
    cdc.fields(observation, 'instanceId privateIp containerId imageId image startedAt finishedAt running normalProfile readiness appJarSha256 runtimeRevision')
    need(all(observation[key] == item for key, item in pin.items()) and observation['running'] is True
         and observation['normalProfile'] == 'aws' and observation['readiness'] is True
         and observation['appJarSha256'] == readiness['appRuntime']['imageJarSha256']
         and observation['runtimeRevision'] == configuration['asg']['runtimeRevision'], 'EXACT_NORMAL_APP_RUNTIME_REQUIRED')
    if fresh_at is not None:
        need(0 <= fresh_at - command['completedEpoch'] <= 120, 'FRESH_APP_ATTESTATION_REQUIRED')
    return value


class Guard:
    """Instance-profile read-only control checks; no remote writer operations."""
    def __init__(self, inputs, *, aws=None, clock=time.time, monotonic=time.monotonic):
        self.inputs, self.config = inputs, inputs['configuration']
        self.aws = aws or cdc_host.HostAws()
        self.clock, self.monotonic = clock, monotonic
        self.lease = restore.Lease(self.aws, self.config['lease'])
        self.start, self.last = monotonic(), -float('inf')
        self.maximum = 1800 if inputs['value']['operation'] == 'live' else 18000
        self.deadline = min(inputs['value']['deadlineEpoch'], self.config['expiresAt'], self.config['approvedExecutionDeadlineEpoch'])
        self.close_observation = None
        self.verified_host = False

    def local(self):
        need(self.clock() < self.deadline and self.monotonic() - self.start < self.maximum, 'SERVICE_VERIFICATION_DEADLINE_REACHED')
        need(not (Path('/opt/airbob/global-b') / self.config['runId'] / 'STOP').exists(), 'SOURCE_STOP_REQUESTED')

    def __call__(self, force=False):
        self.local()
        if not force and self.monotonic() - self.last < 5:
            return
        self.lease(force=True)
        if not self.verified_host:
            need(not any(os.environ.get(key) for key in ('AWS_PROFILE', 'AWS_DEFAULT_PROFILE', 'AWS_ACCESS_KEY_ID', 'AWS_SECRET_ACCESS_KEY', 'AWS_SESSION_TOKEN')),
                 'CONNECT_INSTANCE_PROFILE_ONLY')
            need(cdc_host.imds_identity() == {key: self.config['hosts']['connect'][key] for key in ('instanceId', 'privateIp')},
                 'EXACT_RETAINED_CONNECT_HOST_REQUIRED')
            need(self.aws.call('sts', 'get-caller-identity').get('Account') == cdc.ACCOUNT, 'AWS_ACCOUNT_CHANGED')
            self.verified_host = True
        pin = self.config['rds']
        rows = self.aws.call('rds', 'describe-db-instances', '--db-instance-identifier', pin['identifier'])['DBInstances']
        need(len(rows) == 1, 'EXACT_RDS_REQUIRED'); row = rows[0]
        need(row['DBInstanceIdentifier'] == pin['identifier'] and row['DbiResourceId'] == pin['resourceId']
             and row['Endpoint']['Address'] == pin['endpoint'] and row['Endpoint']['Port'] == 3306
             and row['Engine'] == 'mysql' and row['EngineVersion'] == '8.4.11' and row['DBInstanceStatus'] == 'available'
             and row['PubliclyAccessible'] is False and row['MasterUserSecret']['SecretArn'] == pin['masterSecretArn'], 'RDS_IDENTITY_CHANGED')
        cdc_host.require_tags(cdc_host.tags(row['TagList']), self.config)
        self.master_username = row['MasterUsername']
        groups = self.aws.call('autoscaling', 'describe-auto-scaling-groups')['AutoScalingGroups']
        groups = [item for item in groups if item['AutoScalingGroupName'].startswith(pin['identifier'] + '-')]
        need(len(groups) == 1, 'RUN_ASG_INVENTORY_CHANGED'); group = groups[0]
        need(group['AutoScalingGroupName'] == self.config['asg']['name'] and group['AutoScalingGroupARN'] == self.config['asg']['arn']
             and group['LaunchTemplate'] == self.config['asg']['launchTemplate'], 'ASG_LAUNCH_IDENTITY_CHANGED')
        cdc_host.require_tags(cdc_host.tags(group['Tags']), self.config)
        frozen = self.inputs['value']['operation'] == 'finalize'
        shape = tuple(group[key] for key in ('MinSize', 'DesiredCapacity', 'MaxSize'))
        if frozen:
            need(shape == (0, 0, 0) and group['Instances'] == [] and self.close_observation is not None, 'ALL_APP_WRITERS_MUST_BE_STOPPED')
        else:
            need(shape == (1, 1, 1) and len(group['Instances']) == 1 and group['Instances'][0]['InstanceId'] == self.config['hosts']['app']['instanceId']
                 and group['Instances'][0]['LifecycleState'] == 'InService' and group['Instances'][0]['HealthStatus'] == 'Healthy'
                 and group['Instances'][0]['LaunchTemplate'] == self.config['asg']['launchTemplate'], 'EXACT_ONE_APP_REQUIRED')
        connect = self.config['hosts']['connect']
        rows = core.parse(core.command(['docker', '--host', 'unix:///var/run/docker.sock', 'inspect', connect['containerId']], timeout=10))
        need(len(rows) == 1, 'EXACT_CONNECT_CONTAINER_REQUIRED'); item = rows[0]
        expected = self.close_observation if frozen else connect
        need(item['Id'] == connect['containerId'] and item['Image'] == connect['imageId'] and item['Config']['Image'] == connect['image']
             and not item['State']['Paused'] and not item['State']['Restarting'] and item['State']['Running'] is (not frozen)
             and item['State']['StartedAt'] == expected['startedAt']
             and (not frozen or item['State']['FinishedAt'] == expected['finishedAt']), 'CONNECT_LIFETIME_CHANGED')
        need(source_identity() == self.inputs['toolIdentity'] and contract.sha(pin['caBundle']) == pin['caSha256'], 'SERVICE_TOOLS_OR_CA_CHANGED')
        self.last = self.monotonic()


class StrictDatabase(restore.Database):
    def __init__(self, defaults, timeout, guard, configuration):
        super().__init__(defaults, timeout, guard)
        self.configuration = configuration

    def command(self, database=True):
        pin = self.configuration['rds']
        return ['mysql', '--defaults-file=' + str(self.defaults), '--host=' + pin['endpoint'], '--port=3306',
                '--ssl-mode=VERIFY_IDENTITY', '--ssl-ca=' + pin['caBundle'], '--connect-timeout=5', '--skip-reconnect',
                '--default-character-set=utf8mb4', '--batch', '--raw', '--unbuffered'] + (['airbobdb'] if database else [])


class LoopbackRelay:
    """Only this fixed private app socket; never expose credentials or bind publicly."""
    def __init__(self, host, deadline, guard):
        ipaddress.ip_address(host)
        self.host, self.deadline, self.guard = host, deadline, guard
        self.stop = threading.Event(); self.error = None
        self.sockets, self.mutex = set(), threading.Lock()
        self.slots = threading.BoundedSemaphore(4); self.accepted = 0

    def check(self):
        self.guard()
        need(not self.stop.is_set() and self.error is None and time.monotonic() < self.deadline, 'LOOPBACK_RELAY_CLOSED_OR_EXPIRED')

    def __enter__(self):
        relay = self
        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                remote = None
                if not relay.slots.acquire(blocking=False):
                    relay.error = 'RELAY_CONCURRENCY_LIMIT'; return
                try:
                    with relay.mutex:
                        relay.accepted += 1
                        need(relay.accepted <= 600, 'RELAY_REQUEST_LIMIT')
                    need(self.client_address[0] == '127.0.0.1', 'LOOPBACK_CLIENT_REQUIRED')
                    remote = socket.create_connection((relay.host, 8080), timeout=5)
                    with relay.mutex:
                        relay.sockets.update((self.request, remote))
                    while not relay.stop.is_set() and time.monotonic() < relay.deadline:
                        readable, _, _ = select.select((self.request, remote), (), (), .25)
                        for source in readable:
                            raw = source.recv(65536)
                            if not raw:
                                return
                            target = remote if source is self.request else self.request
                            target.settimeout(5); target.sendall(raw)
                except Exception:
                    relay.error = 'RELAY_CONNECTION_UNCONFIRMED'
                finally:
                    with relay.mutex:
                        relay.sockets.discard(self.request)
                        if remote:
                            relay.sockets.discard(remote)
                    if remote:
                        remote.close()
                    relay.slots.release()
        class Server(socketserver.ThreadingTCPServer):
            allow_reuse_address = False
            daemon_threads = True
        self.server = Server(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={'poll_interval': .1}, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_address[1])
        return self

    def __exit__(self, *_):
        self.stop.set()
        with self.mutex:
            for item in tuple(self.sockets):
                try: item.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                item.close()
        self.server.shutdown(); self.server.server_close(); self.thread.join(timeout=2)
        need(not self.thread.is_alive(), 'LOOPBACK_RELAY_CLEANUP_UNCONFIRMED')


def guarded_sessions(base_class, guard):
    class Session(base_class):
        def request(self, *args, **kwargs):
            guard()
            with core.warm.wall_clock_limit(min(self.timeout, 15)):
                result = super().request(*args, **kwargs)
            guard()
            return result
    return Session


def observation_binding(inputs):
    config, value = inputs['configuration'], inputs['value']
    return {'operationId': config['operationId'], 'runId': config['runId'], 'datasetId': config['datasetId'],
            'configurationSha256': cdc.journal_binding(config), 'sharedBindingSha256': shared_binding(config),
            'targetIdentity': restore.target_identity(inputs['restoreConfig']), 'lease': config['lease'],
            'resourceFencingToken': config['resourceFencingToken'], 'expiresAt': config['expiresAt'],
            'approvedExecutionDeadlineEpoch': config['approvedExecutionDeadlineEpoch'],
            'verificationDeadlineEpoch': value['deadlineEpoch'],
            'serviceManifest': public_ref(config['serviceManifest']), 'serviceReadiness': public_ref(config['serviceReadiness']),
            'restoreConfigSha256': value['restoreConfig']['sha256'], 'restoreReceiptSha256': value['restoreReceipt']['sha256'],
            'toolIdentity': inputs['toolIdentity']}


def same_runtime(before, after):
    def qualified(binding):
        cdc.fields(binding, 'path sha256 execution')
        need(binding['execution'] == 'current process host', 'ACTUAL_HOST_RUNTIME_REQUIRED')
        report = read_ref({key: binding[key] for key in ('path', 'sha256')})
        need(report.get('passed') is True and report.get('consumerRuntimePassed') is True, 'ACTUAL_QUALIFIED_RUNTIME_REQUIRED')
        return report
    left, right = qualified(before), qualified(after)
    need(all(left[key] == right[key] for key in ('hostJavaTools', 'qualificationSourceSha256', 'fixedCasesSha256', 'scope', 'python'))
         and left['java']['tzdbFile'] == right['java']['tzdbFile']
         and left['consumerReleaseBindings']['files'] == right['consumerReleaseBindings']['files'], 'SERVICE_RUNTIME_CHANGED')


def validate_read_observations(representatives, warm, images, inputs):
    dataset = inputs['configuration']['datasetId']
    need(representatives.get('kind') == 'global-b-dev-representative-http-reads'
         and representatives.get('state') == 'AWS_B_REPRESENTATIVE_HTTP_READS_VERIFIED'
         and representatives.get('datasetId') == dataset and representatives.get('passed') is True
         and representatives.get('targetMode') == 'aws', 'ACTUAL_REPRESENTATIVE_READS_REQUIRED')
    accounts = representatives.get('accounts', [])
    need(len(accounts) == 3 and {row.get('account') for row in accounts} == {'demo', 'host', 'admin'}
         and all(all(row.get(key) is True for key in ('passed', 'normalLoginAndIdentityVerified', 'adminAuthorizationVerified',
                                                     'logoutInvalidatesSession')) for row in accounts), 'THREE_NORMAL_LOGINS_AND_OWNED_LOGOUTS_REQUIRED')
    need(all(all(row.get(key) is True for key in ('sealedReservationSampleMatches', 'sealedAuthoredReviewMatches',
                 'sealedListingSampleMatches', 'receivedReservationOwnershipMatches')) for row in accounts if row['account'] != 'admin'),
         'ACTUAL_SEALED_ACCOUNT_OWNERSHIP_READS_REQUIRED')
    observations = representatives.get('observations', [])
    need(observations and all(row.get('passed') is True for row in observations)
         and {'search-korea', 'search-north-america'} <= {row.get('check') for row in observations},
         'GLOBAL_PUBLIC_READ_OBSERVATIONS_REQUIRED')
    need(warm.get('kind') == 'global-b-after-verification-service-warmup' and warm.get('passed') is True
         and warm.get('state') == 'AFTER_VERIFICATION_WARMUP_COMPLETED' and warm.get('datasetId') == dataset
         and warm.get('targetMode') == 'aws' and warm.get('successfulWorkloadGetCount') == 300
         and warm.get('unfinishedTargets') == [] and warm.get('cleanupFailures') == []
         and len(warm.get('targets', [])) == 15 and all(row.get('successfulSamples') == 20 for row in warm['targets']),
         'BOUNDED_WARMUP_AND_SESSION_CLEANUP_REQUIRED')
    need(images.get('kind') == 'global-b-public-media-and-availability' and images.get('passed') is True
         and images.get('state') == 'PUBLIC_MEDIA_AVAILABILITY_VERIFIED' and images.get('datasetId') == dataset
         and images.get('toolSha256') == contract.sha(Path(media.__file__)) and images.get('decoderSha256') == media.DECODER_SHA
         and images.get('consumerManifestSha256') == inputs['configuration']['consumerManifestSha256']
         and images.get('checksumsSha256') == inputs['configuration']['checksumsSha256'], 'ACTUAL_MEDIA_AND_AVAILABILITY_REQUIRED')
    samples = images.get('images', [])
    need(len(samples) == 2 and {row.get('region') for row in samples} == {'korea', 'north-america'}
         and all(row.get('fullyDecoded') is True and row.get('status') == 200 and core.integer(row.get('width'), 1)
                 and core.integer(row.get('height'), 1) and contract.digest(row.get('contentSha256')) for row in samples)
         and len(images.get('listings', [])) >= 2 and all(row.get('currentLocalDateVerified') is True
                 and core.integer(row.get('reservableNights'), 1) for row in images['listings']), 'ACTUAL_REGIONAL_IMAGE_DECODE_AND_CURRENT_DATES_REQUIRED')
    for row in images['listings']:
        start, end = (dt.date.fromisoformat(row[key]) for key in ('localDate', 'endExclusive'))
        zone = media.ZoneInfo(row['timeZone'])
        first, last = (snapshot.instant(images[key]).astimezone(zone).date() for key in ('startedAt', 'completedAt'))
        need(first <= start <= last and end == media.plus_months(start, 3)
             and row['windowDays'] == (end - start).days and core.integer(row.get('unavailableNights'))
             and row['reservableNights'] + row['unavailableNights'] == row['windowDays'],
             'LISTING_LOCAL_THREE_MONTH_OBSERVATION_REQUIRED')
    for result in (representatives, warm):
        need(all(result.get('binding', {}).get(key) == inputs['configuration'][key]
                 for key in ('consumerManifestSha256', 'checksumsSha256', 'accountEnvironment')),
             'HTTP_RECEIPT_SOURCE_BINDING_CHANGED')


def verify_http(inputs, output, guard, *, relay_factory=LoopbackRelay, representative_runner=None, warm_runner=None, media_runner=None):
    config = inputs['configuration']; release = Path(config['releaseDirectory'])
    private = output / '.private'
    bundle = contract.read(config['privateAccounts'])
    selected = contract.read(release / 'representative-accounts.json')['accounts']
    ids = {row['memberId'] for row in selected}
    subset = {key: bundle[key] for key in ('schemaVersion', 'datasetProfile', 'environment')}
    subset['credentials'] = [row for row in bundle['credentials'] if row['memberId'] in ids]
    need(len(subset['credentials']) == 3, 'THREE_PRIVATE_REPRESENTATIVES_REQUIRED')
    private_subset = private / 'representatives.private.json'
    core.write_new(private_subset, core.encoded(subset))
    deadline = time.monotonic() + min(1800, inputs['value']['deadlineEpoch'] - time.time())
    with relay_factory(config['hosts']['app']['privateIp'], deadline, guard) as relay:
        relay.check()
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), core.reads.NoRedirect())
        with core.warm.wall_clock_limit(10), opener.open(relay.base + '/actuator/health/readiness', timeout=10) as response:
            need(response.status == 200 and response.headers.get('Content-Encoding', 'identity') == 'identity', 'ACTUAL_READINESS_HTTP_REQUIRED')
            raw = response.read(262145)
            need(len(raw) <= 262144 and core.parse(raw).get('status') == 'UP', 'ACTUAL_READINESS_UP_REQUIRED')
        relay.check()
        args = SimpleNamespace(release=release, consumer_manifest_sha256=config['consumerManifestSha256'],
            checksums_sha256=config['checksumsSha256'], account_environment=config['accountEnvironment'], target_mode='aws',
            base_url=relay.base, private_representatives=private_subset, timeout=min(15, config['requestTimeoutSeconds']), output=output / 'representatives')
        reads, reads_path, _ = (representative_runner or core.reads.run)(args,
            session_factory=guarded_sessions(core.reads.HttpSession, relay.check))
        need(reads.get('passed') is True, 'REPRESENTATIVE_READS_FAILED')
        checks = contract.read(release / 'SHA256SUMS.json')
        warm_config = {'schemaVersion': 1, 'releaseDirectory': str(release), 'datasetId': config['datasetId'],
            'consumerManifestSha256': config['consumerManifestSha256'], 'checksumsSha256': config['checksumsSha256'],
            'workloadSha256': checks['base-scenario-targets.json'], 'privateAccounts': config['privateAccounts'],
            'baseUrl': relay.base, 'targetMode': 'aws', 'accountEnvironment': config['accountEnvironment'],
            'requestTimeoutSeconds': min(15, config['requestTimeoutSeconds']),
            'preconditions': {'fingerprintState': 'PREPARED_ALL_ROWS_AND_DDL_VERIFIED',
                'fingerprintReceiptSha256': inputs['value']['restoreReceipt']['sha256'],
                'appState': 'READINESS_VERIFIED_EXISTING_PROCESS', 'appImage': config['hosts']['app']['image'], 'detailCacheState': 'DISABLED'}}
        warmed, warm_path, _ = (warm_runner or core.warm.run)(warm_config, output / 'warmup',
            session_factory=guarded_sessions(core.warm.WarmupSession, relay.check))
        need(warmed.get('passed') is True, 'WARMUP_FAILED')
        images, images_path, _ = (media_runner or media.verify)(release, config['datasetId'], config['consumerManifestSha256'],
            config['checksumsSha256'], relay.base, output / 'media', guard=relay.check, deadline=deadline)
        validate_read_observations(reads, warmed, images, inputs)
        relay.check()
    return {'readiness': {'status': 'UP', 'httpStatus': 200}, 'representatives': ref(reads_path),
            'warmup': ref(warm_path), 'media': ref(images_path), 'loopbackRelayClosed': True}


@contextlib.contextmanager
def host_runtime(inputs, output, guard):
    config = inputs['configuration']
    root = Path('/opt/airbob/global-b') / config['runId']
    runtime = root / 'bootstrap-runtime'
    need(root.resolve() == root and runtime.resolve() == runtime and runtime.is_dir(), 'RETAINED_BOOTSTRAP_RUNTIME_REQUIRED')
    guard(force=True)
    qualification_path = output / 'host-runtime-qualification.json'
    qualification = runtime_gate.qualify_runtime(Path(config['releaseDirectory']), runtime, qualification_path,
        expected_checks={name: value['sha256'] for name, value in inputs['envelope']['objects'].items()}, java_home=root / 'toolchain/jdk')
    with runtime_gate.activated_runtime(qualification):
        db, environment = restore.connection(inputs['restoreConfig'], guard.aws, {'masterUsername': guard.master_username},
            output / '.private', guard)
        db = StrictDatabase(db.defaults, min(18000, max(1, int(guard.deadline - time.time()))), guard, config)
        need(str(db.scalar('SELECT @@version', False)) == '8.4.11'
             and db.scalar('SELECT @@server_uuid', False) == config['mysqlServerUuid'], 'ACTUAL_MYSQL_IDENTITY_REQUIRED')
        cipher = db.rows("SHOW SESSION STATUS LIKE 'Ssl_cipher'", False)
        need(len(cipher) == 1 and cipher[0].get('Value'), 'ACTUAL_MYSQL_TLS_REQUIRED')
        yield runtime, db, environment, runtime_gate.qualification_binding(qualification_path)


def verify_live(config, output, *, inputs=None, guard=None, http_runner=None, runtime_context=host_runtime, admission_epoch=None):
    admitted = time.time() if admission_epoch is None else admission_epoch
    inputs = inputs or read_inputs(config)
    need(config['operation'] == 'live', 'LIVE_OPERATION_REQUIRED')
    guard = guard or Guard(inputs)
    before = validate_app_observation(read_ref(config['controllerObservation']), inputs['configuration'], inputs['cdc']['readiness'], fresh_at=admitted)
    output = Path(output); output.mkdir(mode=0o700)
    (output / '.private').mkdir(mode=0o700)
    started = now()
    with runtime_context(inputs, output, guard) as (_, _, _, qualification):
        observations = (http_runner or verify_http)(inputs, output, guard)
        guard(force=True)
        need(read_ref(config['controllerObservation']) == before and source_identity() == inputs['toolIdentity'], 'LIVE_INPUTS_CHANGED')
    report = {'schemaVersion': 1, 'kind': LIVE_KIND, 'state': 'LIVE_READS_OBSERVED', 'startedAt': started, 'completedAt': now(),
              'binding': observation_binding(inputs), 'observations': observations, 'hostRuntimeQualification': qualification,
              'controllerObservationSha256': config['controllerObservation']['sha256'],
              'admissionEpoch': admitted,
              'detailCacheDisabledVerified': True,
              'postReadAppIdentityAttestationRequired': True, 'sourceSnapshotGateSatisfied': False,
              'businessDatabaseWritesPerformed': False, 'privateValuesIncluded': False}
    core.write_new(output / 'live-service-receipt.json', core.encoded(report))
    return report


def validate_live_attestation(value, live, configuration, ready):
    cdc.fields(value, 'schemaVersion kind operationId supervisorSha256 liveConfiguration cdcConfiguration '
                     'liveConfigurationSha256 cdcConfigurationSha256 sharedBindingSha256 liveReceiptSha256 before after')
    old, current = read_ref(value['liveConfiguration']), read_ref(value['cdcConfiguration'])
    need(value['schemaVersion'] == 1 and value['kind'] == 'global-b-aws-service-live-attestation'
         and value['supervisorSha256'] == supervisor_sha() and current == configuration
         and value['operationId'] == configuration['operationId']
         and value['liveConfigurationSha256'] == cdc.journal_binding(old) == live['binding']['configurationSha256']
         and value['cdcConfigurationSha256'] == cdc.journal_binding(current)
         and shared_binding(old) == shared_binding(current) == value['sharedBindingSha256'] == live['binding']['sharedBindingSha256'],
         'LIVE_TO_CDC_CONFIGURATION_MAPPING_CHANGED')
    cdc.validate_config(old)
    before = validate_app_observation(value['before'], old, ready)
    after = validate_app_observation(value['after'], old, ready)
    need(before['observation'] == after['observation'] and before['command']['commandId'] != after['command']['commandId']
         and before['command']['token'] < after['command']['token']
         and before['command']['completedEpoch'] <= int(snapshot.instant(live['startedAt']).timestamp())
         and after['command']['startedEpoch'] >= int(snapshot.instant(live['completedAt']).timestamp())
         and snapshot.instant(live['completedAt']).timestamp() <= current['businessStartedEpoch'],
         'LIVE_READS_NOT_BRACKETED_BY_EXACT_APP_OBSERVATIONS')
    return old


def validate_cdc_receipt(value, inputs):
    config = inputs['configuration']
    need(value.get('schemaVersion') == 1 and value.get('kind') == 'global-b-aws-owned-api-cdc-verification'
         and value.get('action') == 'post-reset' and value.get('phasePassed') is True
         and value.get('operationId') == config['operationId'] and value.get('runId') == config['runId']
         and value.get('mysqlServerUuid') == config['mysqlServerUuid']
         and value.get('inputBinding') == inputs['cdc']['binding'] and value.get('failureCode') is None
         and value.get('sessionCleanupFailureCode') is None and value.get('apiRoundTripVerified') is True,
         'ACTUAL_SUCCESSFUL_POST_RESET_CDC_RECEIPT_REQUIRED')
    business = value.get('businessWindow', {})
    need(business.get('normalPatchCount') == 2 and business.get('normalSessionInvalidated') is True
         and business.get('deadlineEpoch') == config['businessDeadlineEpoch']
         and config['businessStartedEpoch'] <= business.get('completedEpoch', 0) <= config['businessDeadlineEpoch'], 'ORIGINAL_TWO_PATCH_WINDOW_REQUIRED')
    cleanup = value.get('cleanup', {})
    need(cleanup == {'historyRowsRemoved': 2, 'outboxRowsRemoved': 4,
                     'accommodationColumnsRestored': ['updated_at', 'updated_by'], 'historyColumnsRestored': ['valid_to'],
                     'autoIncrementRestored': True, 'binlogKeptEnabled': True, 'writerFenceRestored': True}, 'EXACT_CDC_ROW_AND_COUNTER_RESET_REQUIRED')
    health = value.get('postResetHealth', {})
    need(health.get('heartbeatAdvanced') is True and health.get('esOriginalNameMatches') is True, 'ACTUAL_POST_RESET_HEALTH_REQUIRED')
    steps = value.get('steps', [])
    need(len(steps) == 2 and [row.get('step') for row in steps] == [1, 2], 'EXACT_TWO_CDC_STEPS_REQUIRED')
    events = set()
    for step in steps:
        proof = step.get('propagation') or {}
        need(step.get('httpConfirmed') is True and step.get('outboxRowsObserved') == 2
             and proof.get('step') == step['step'] and proof.get('esNameMatches') is True
             and proof.get('eventId') not in events and cdc.match(r'[0-9a-f-]{36}', proof.get('eventId')),
             'EXACT_API_EVENT_AND_ES_PROPAGATION_REQUIRED')
        events.add(proof['eventId'])
        records = proof.get('records', [])
        need(records, 'OBSERVED_KAFKA_RECORDS_REQUIRED')
        for row in records:
            partition, offset = row.get('partition'), row.get('offset')
            need(core.integer(partition) and partition < 3 and core.integer(offset), 'KAFKA_OFFSET_COORDINATES_REQUIRED')
            for commits in (proof.get('committedOffsets', {}), health.get('committedOffsets', {})):
                committed = commits.get(str(partition), commits.get(partition))
                need(core.integer(committed) and committed > offset, 'ACTUAL_CONSUMER_COMMIT_AFTER_EVENT_REQUIRED')
    return value


def validate_close_attestation(value, receipt, config, *, fresh_at):
    cdc.fields(value, 'schemaVersion kind configurationSha256 operationId lease supervisorSha256 controllerReceiptSha256 '
                     'command observation sourceClosed observedEpoch')
    need(value['schemaVersion'] == 1 and value['kind'] == 'global-b-aws-service-source-close-attestation'
         and value['configurationSha256'] == cdc.journal_binding(config) and value['operationId'] == config['operationId']
         and value['lease'] == config['lease'] and value['supervisorSha256'] == supervisor_sha()
         and 0 <= fresh_at - value['observedEpoch'] <= 120, 'FRESH_SOURCE_CLOSE_ATTESTATION_REQUIRED')
    need(receipt.get('kind') == 'global-b-aws-cdc-controller-receipt' and receipt.get('phasePassed') is True
         and receipt.get('action') == 'close' and receipt.get('configurationSha256') == cdc.journal_binding(config)
         and receipt.get('toolIdentity') == cdc.source_identity() and receipt.get('operationId') == config['operationId']
         and receipt.get('runId') == config['runId'] and receipt.get('outstandingCommands') == []
         and all(receipt.get(key) is True for key in ('twoPatchRuntimeVerified', 'ownedResetConfirmed', 'postResetHealthConfirmed')),
         'ACTUAL_CONTROLLER_CDC_RESET_AND_CLOSE_REQUIRED')
    closed = value['sourceClosed']
    need(closed == receipt.get('sourceClosed') and closed.get('lease') == config['lease']
         and closed.get('writersStopped') is True and closed.get('cdcStopped') is True, 'SOURCE_CLOSE_LINEAGE_CHANGED')
    zero = closed['zeroObservation']
    need(zero['terminatedInstanceIds'] == closed['replacementInstanceIds'] and len(zero['terminatedInstanceIds']) == 1
         and zero['terminatedInstanceIds'] != [config['hosts']['app']['instanceId']]
         and zero['activeScalingActivityIds'] == zero['liveAppInstanceIds'] == [] and zero['stableSeconds'] >= 10
         and zero['observations'] >= 3 and zero['observedEpoch'] <= value['observedEpoch'], 'STABLE_OWNED_REPLACEMENT_RETIREMENT_REQUIRED')
    observed = controller.connect_observation(value['observation'], config['hosts']['connect'], running=False)
    need(observed['finishedAt'] == closed['connectFinishedAt'], 'FINAL_CONNECT_STOP_CHANGED')
    pin = dict(config['hosts']['connect'], startedAt=observed['startedAt'])
    command = value['command']; cdc.fields(command, COMMAND_FIELDS)
    expected = controller.host_program('connect', 'stop', config, pin=pin)
    need(command['commandId'] == closed['commandId'] and command['instanceId'] == pin['instanceId']
         and command['containerId'] == pin['containerId'] and command['commandSha256'] == core.digest(expected.encode())
         and command['status'] == 'Success' and command['startedEpoch'] <= command['completedEpoch'] <= value['observedEpoch'],
         'EXACT_FINAL_CONNECT_STOP_COMMAND_REQUIRED')
    return observed


def finalize_reset(config, output, *, inputs=None, guard=None, runtime_context=host_runtime, admission_epoch=None):
    admitted = time.time() if admission_epoch is None else admission_epoch
    inputs = inputs or read_inputs(config)
    need(config['operation'] == 'finalize', 'FINALIZE_OPERATION_REQUIRED')
    live = read_ref(config['liveReceipt']); attestation = read_ref(config['liveAttestation'])
    need(live.get('schemaVersion') == 1 and live.get('kind') == LIVE_KIND and live.get('state') == 'LIVE_READS_OBSERVED'
         and live['binding']['restoreReceiptSha256'] == config['restoreReceipt']['sha256']
         and live['binding']['restoreConfigSha256'] == config['restoreConfig']['sha256']
         and live['binding']['toolIdentity'] == inputs['toolIdentity']
         and attestation['liveReceiptSha256'] == config['liveReceipt']['sha256'], 'EXACT_LIVE_SERVICE_EVIDENCE_REQUIRED')
    old = validate_live_attestation(attestation, live, inputs['configuration'], inputs['cdc']['readiness'])
    old_inputs = dict(inputs, configuration=old)
    observations = live['observations']
    need(observations.get('readiness') == {'status': 'UP', 'httpStatus': 200} and observations.get('loopbackRelayClosed') is True,
         'ACTUAL_READINESS_AND_CLOSED_RELAY_REQUIRED')
    validate_read_observations(*(read_ref(observations[name]) for name in ('representatives', 'warmup', 'media')), old_inputs)
    cdc_proof = validate_cdc_receipt(read_ref(config['cdcReceipt']), inputs)
    control = read_ref(config['controllerReceipt']); close = read_ref(config['controllerObservation'])
    need(close['controllerReceiptSha256'] == config['controllerReceipt']['sha256'], 'EXACT_CONTROLLER_CLOSE_REFERENCE_REQUIRED')
    connect = validate_close_attestation(close, control, inputs['configuration'], fresh_at=admitted)
    need(snapshot.instant(cdc_proof['completedAt']) >= snapshot.instant(live['completedAt'])
         and snapshot.instant(control['completedAt']) >= snapshot.instant(cdc_proof['completedAt']), 'SERVICE_CDC_CLOSE_PHASE_ORDER_CHANGED')
    guard = guard or Guard(inputs); guard.close_observation = connect
    output = Path(output); output.mkdir(mode=0o700)
    (output / '.private').mkdir(mode=0o700)
    started = now()
    value = dict(inputs['restoreConfig'], lease=inputs['configuration']['lease'], writerAsgNames=[inputs['configuration']['asg']['name']])
    with runtime_context(inputs, output, guard) as (runtime, db, environment, qualification):
        same_runtime(live['hostRuntimeQualification'], qualification)
        with restore.exclusive_database(db), snapshot.frozen_tables(db) as fence:
            snapshot.database_identity(db, value)
            calendar_before = snapshot.current_inventory(db, runtime)
            fingerprint_started, fingerprint_clock = now(), time.monotonic()
            measured = restore.fingerprint(runtime, Path(value['release']), environment, output / 'prepared-fingerprint.json', db.timeout, db.guard)
            fingerprint_finished = now()
            fingerprint_seconds = time.monotonic() - fingerprint_clock
            contract.validate_fingerprint(measured, require_sealed=False)
            restore.validate_prepared_changes(inputs['restoreReceipt']['sealedFingerprint'], measured)
            need(measured['tables']['member'] == inputs['initialFingerprint']['tables']['member'], 'SERVICE_CHANGED_PREPARED_CREDENTIAL_ROWS')
            owners = restore.owner_fingerprint(db)
            need(owners == inputs['restoreReceipt']['preparation']['ownerSha256BeforeAndAfter'], 'HISTORICAL_INVENTORY_OWNERSHIP_CHANGED')
            calendar_after = snapshot.current_inventory(db, runtime)
            need(calendar_before['localDateVector'] == calendar_after['localDateVector'], 'FULL_VERIFICATION_CROSSED_LISTING_LOCAL_DATE')
            snapshot.database_identity(db, value)
            db.guard(force=True)
        guard(force=True)
        validate_config(config)
        need(read_ref(config['controllerObservation']) == close and source_identity() == inputs['toolIdentity'], 'FINAL_SOURCE_INPUTS_CHANGED')
    report = {'schemaVersion': 1, 'kind': RESET_KIND, 'state': 'SERVICE_VERIFIED_AND_RESET',
        'datasetId': inputs['configuration']['datasetId'], 'mysql': snapshot.MYSQL,
        'targetIdentity': restore.target_identity(value), 'application': inputs['cdc']['manifest']['application'],
        'restoreReceiptSha256': config['restoreReceipt']['sha256'], 'preparedFingerprintSha256': contract.sha(output / 'prepared-fingerprint.json'),
        'writersStopped': True, 'cdcStopped': True, 'sourceOriginalsUnchanged': True,
        'service': {'readinessPassed': True, 'normalLoginsPassed': True, 'publicReadsPassed': True,
            'globalSearchPassed': True, 'imagesSampled': True, 'reservableDatesPassed': True,
            'domainApiMutationCdcEsPassed': True, 'representativeAccounts': 3},
        'reset': {'passed': True, 'testMutationRemoved': True, 'unchangedDomainAndDdl': True,
            'remainingOutboxRows': 0, 'ownerSha256BeforeAndAfter': owners},
        'startedAt': started, 'completedAt': now(), 'binding': observation_binding(inputs), 'readFence': fence,
        'admissionEpoch': admitted,
        'calendar': calendar_after, 'hostRuntimeQualification': qualification,
        'fullFingerprint': {'startedAt': fingerprint_started, 'completedAt': fingerprint_finished,
                            'elapsedSeconds': fingerprint_seconds, 'elapsedClock': 'monotonic', 'allTablesReadLocked': True},
        'evidence': {key: public_ref(config[key]) for key in ('liveReceipt', 'liveAttestation', 'cdcReceipt', 'controllerReceipt', 'controllerObservation')},
        'snapshotCreated': False, 'privateValuesIncluded': False, 'databaseBusinessWritesPerformed': False}
    report['service']['detailCacheDisabledVerified'] = True
    need(snapshot.instant(report['completedAt']) >= snapshot.instant(control['completedAt']), 'FINAL_FINGERPRINT_COMPLETION_PREDATES_SOURCE_CLOSE')
    candidate = output / '.private/service-reset-candidate.json'
    core.write_new(candidate, core.encoded(report))
    admission = {'restoreReceipt': {key: config['restoreReceipt'][key] for key in ('path', 'sha256')},
        'preparedFingerprint': ref(output / 'prepared-fingerprint.json'),
        'serviceResetReceipt': ref(candidate), 'privateHandoff': ref(value['privateAccounts']),
        'application': {key: report['application'][key] for key in ('mainCommit', 'image')}}
    snapshot.validate_source_evidence(admission, value, inputs['envelope'])
    core.write_new(output / 'service-reset-receipt.json', core.encoded(report))
    return report


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args(argv)
    try:
        admitted = time.time()
        value = contract.read(args.config); inputs = read_inputs(value)
        if args.validate_only:
            print(json.dumps({'state': 'SERVICE_VERIFICATION_INPUTS_VALIDATED', 'cloudSuccessClaimed': False})); return 0
        need(not args.output.exists() and not args.output.is_symlink() and args.output.parent.is_dir()
             and not args.output.resolve().is_relative_to(Path(inputs['configuration']['releaseDirectory']).resolve()), 'NEW_OUTPUT_OUTSIDE_SEALED_RELEASE_REQUIRED')
        report = (verify_live if value['operation'] == 'live' else finalize_reset)(value, args.output, inputs=inputs, admission_epoch=admitted)
        print(json.dumps({'state': report['state'], 'snapshotCreated': False, 'privateValuesIncluded': False})); return 0
    except BaseException as error:
        code = error.code if isinstance(error, (Failed, core.reads.CheckFailed, core.warm.CheckFailed)) else 'SERVICE_VERIFICATION_UNCONFIRMED'
        failure = {'state': 'SERVICE_VERIFICATION_FAILED', 'failureCode': code, 'snapshotCreated': False,
                   'resourcesRetained': True, 'privateValuesIncluded': False}
        if args.output.is_dir() and not args.output.is_symlink() and args.output.stat().st_mode & 0o077 == 0:
            try: core.write_new(args.output / 'failure.json', core.encoded(failure))
            except BaseException: failure['failureReceiptWritten'] = False
        print(json.dumps(failure)); return 1


if __name__ == '__main__':
    raise SystemExit(main())
