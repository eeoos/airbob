"""AWS bindings around the unchanged, SHA-pinned two-PATCH CDC verifier.

The four originals are byte-identical under the AWS vendor tree, preserving
their internal relative paths. No root scripts, source rewriting, monkeypatch
of OCI admission, or OCI privileged reset are used for AWS execution.
"""
from __future__ import annotations

import copy
import importlib.util
import ipaddress
from pathlib import Path
import re
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
VENDOR_PATH = 'infra/aws/vendor/global-b-cdc-20260912'
VENDOR_ROOT = ROOT / VENDOR_PATH
ORIGINALS = {
    'scripts/verify-growth-b-cdc.py': 'befd25e68e7cd0e97e125bc339ee1679a0df159aeac745f40ced25c97744a5ac',
    'scripts/verify-growth-b-dev.py': '5405c5b3fc8ef12c4972e738f1b63f4b180457ee46a6873e0ce6d3783ce95be4',
    'scripts/warm-growth-b-service.py': '9e818f0fe2944b943663df43b99337a7e307ea0aa3b77999fc88cb793e3d7ef7',
    'infra/aws/scripts/growth_b_contract.py': 'f0327d99e7a6d53db4eb9210bf6634ea64552d05d9b802db90b4eaae19811e8d',
}


def load_original(root=VENDOR_ROOT):
    import hashlib
    if root.resolve() != root: raise RuntimeError('PINNED_CDC_SOURCE_CHANGED')
    for name, expected in ORIGINALS.items():
        path = root / name
        if not path.is_file() or path.is_symlink() or path.resolve() != path or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError('PINNED_CDC_SOURCE_CHANGED')
    existing = sys.modules.get('growth_b_contract')
    if existing is not None and hashlib.sha256(Path(existing.__file__).read_bytes()).hexdigest() != ORIGINALS['infra/aws/scripts/growth_b_contract.py']:
        raise RuntimeError('PINNED_CONTRACT_IMPORT_CHANGED')
    spec = importlib.util.spec_from_file_location('_aws_cdc_original', root / 'scripts/verify-growth-b-cdc.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


core = load_original()
need, Failed = core.need, core.Failed
ACCOUNT, REGION = '942632789808', 'ap-northeast-2'
KIND = 'global-b-aws-owned-cdc-configuration'
HASH = r'[0-9a-f]{64}'
INSTANCE = r'i-[0-9a-f]{17}'
IMAGE = ACCOUNT + r'\.dkr\.ecr\.ap-northeast-2\.amazonaws\.com/'


def match(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def fields(value, expected, code='CONFIGURATION_FIELDS_DIFFER'):
    need(isinstance(value, dict) and set(value) == set(expected.split()), code)


def local_ref(value):
    need(isinstance(value, dict) and set(value) in ({'path', 'sha256'}, {'path', 'sha256', 'key', 'versionId', 'bytes'}), 'EXACT_LOCAL_REFERENCE_REQUIRED')
    need(match(HASH, value['sha256']), 'INVALID_REFERENCE_SHA')
    raw = core.reads.read_bytes(value['path'])
    need(core.digest(raw) == value['sha256'], 'LOCAL_REFERENCE_SHA_CHANGED')
    if 'bytes' in value: need(len(raw) == value['bytes'], 'LOCAL_REFERENCE_SIZE_CHANGED')
    return core.parse(raw)


def source_identity():
    names = ('growth_b_cdc.py', 'growth_b_cdc_core.py', 'growth_b_cdc_mysql.py', 'growth_b_cdc_controller.py')
    for name, expected in ORIGINALS.items():
        path = VENDOR_ROOT / name
        need(path.is_file() and not path.is_symlink() and path.resolve() == path and core.digest(path.read_bytes()) == expected, 'PINNED_CDC_SOURCE_CHANGED')
    return {VENDOR_PATH + '/' + name: sha for name, sha in ORIGINALS.items()} | {
        'infra/aws/scripts/' + name: core.digest(Path(__file__).with_name(name).read_bytes()) for name in names}


def original_source_layout():
    return {'packageRoot': VENDOR_PATH, 'files': [
        {'repositoryPath': VENDOR_PATH + '/' + name, 'pathWithinVendorRoot': name, 'sha256': sha}
        for name, sha in ORIGINALS.items()]}


def validate_config(value, *, check_files=True):
    fields(value, 'schemaVersion kind operationId runId datasetId account region executionCommit resourceFencingToken '
           'expiresAt approvedExecutionDeadlineEpoch businessStartedEpoch businessDeadlineEpoch lease '
           'releaseDirectory consumerManifestSha256 checksumsSha256 privateAccounts accountEnvironment '
           'ownerMemberId accommodationId mysqlServerUuid rds asg hosts networkCidr preconditions '
           'serviceManifest serviceReadiness requestTimeoutSeconds propagationTimeoutSeconds maximumSeconds')
    need(value['schemaVersion'] == 1 and value['kind'] == KIND and value['account'] == ACCOUNT and value['region'] == REGION,
         'AWS_CDC_SCOPE_REQUIRED')
    need(match(r'lab-[a-z0-9][a-z0-9-]{0,27}', value['runId']) and match(r'[a-z0-9][a-z0-9-]{2,47}', value['operationId'])
         and match(r'global-growth-b-[0-9a-f]{16}', value['datasetId']) and match(r'[0-9a-f]{40}', value['executionCommit']), 'AWS_CDC_COORDINATES_INVALID')
    need(all(core.integer(value[k], 1) for k in ('resourceFencingToken', 'expiresAt', 'approvedExecutionDeadlineEpoch',
         'businessStartedEpoch', 'businessDeadlineEpoch', 'ownerMemberId', 'accommodationId')), 'AWS_CDC_INTEGER_REQUIRED')
    need(core.integer(value['maximumSeconds'], 60) and 0 < value['businessDeadlineEpoch'] - value['businessStartedEpoch'] <= value['maximumSeconds'] <= 900
         and value['businessDeadlineEpoch'] <= value['expiresAt'] <= value['approvedExecutionDeadlineEpoch'], 'IMMUTABLE_BUSINESS_DEADLINE_REQUIRED')
    need(core.integer(value['requestTimeoutSeconds'], 1) and value['requestTimeoutSeconds'] <= 15
         and core.integer(value['propagationTimeoutSeconds'], 15) and value['propagationTimeoutSeconds'] <= 300,
         'INVALID_HTTP_OR_PROPAGATION_DEADLINE')
    for k in ('consumerManifestSha256', 'checksumsSha256'):
        need(match(HASH, value[k]), 'MISSING_SEAL_ANCHOR')
    for key, prefix in [('serviceManifest', 'datasets/' + value['datasetId'] + '-aws-service/'), ('serviceReadiness', 'data-bootstrap/' + value['runId'] + '/')]:
        ref = value[key]; fields(ref, 'path key versionId sha256 bytes')
        need(match(HASH, ref['sha256']) and match(r'[A-Za-z0-9._~+/=-]{1,1024}', ref['versionId']) and ref['versionId'] not in ('null', 'None')
             and match(r'[A-Za-z0-9_./-]+', ref['key']) and ref['key'].startswith(prefix)
             and all(p not in ('', '.', '..') for p in ref['key'].split('/'))
             and core.integer(ref['bytes'], 1) and ref['bytes'] <= core.MAX_BYTES, 'EXACT_SELECTED_SERVICE_REFERENCE_REQUIRED')
    fields(value['lease'], 'table lockName owner runId command fencingToken')
    need(value['lease']['runId'] == value['runId'] and value['lease']['command'] == 'up'
         and value['lease']['table'] == 'airbob-performance-lab-orchestration-lease'
         and value['lease']['lockName'] == 'airbob-performance-lab'
         and core.integer(value['lease']['fencingToken'], 1)
         and all(match(r'[A-Za-z0-9_:/.-]{1,200}', value['lease'][k]) for k in ('table', 'lockName', 'owner')), 'EXACT_LAB_LEASE_REQUIRED')
    rds = value['rds']
    fields(rds, 'identifier resourceId serverUuid endpoint masterSecretArn caBundle caSha256')
    need(rds['identifier'] == 'airbob-' + value['runId'] and match(r'db-[A-Z0-9]{24}', rds['resourceId'])
         and match(r'[a-z0-9-]+\.[a-z0-9]+\.ap-northeast-2\.rds\.amazonaws\.com', rds['endpoint'])
         and match(r'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-[A-Za-z0-9-]+', rds['masterSecretArn'])
         and match(HASH, rds['caSha256']), 'EXACT_PRIVATE_RDS_REQUIRED')
    need(str(uuid.UUID(rds['serverUuid'])) == rds['serverUuid'] == value['mysqlServerUuid']
         and value['accountEnvironment'] == 'aws:' + rds['resourceId'] + ':' + rds['serverUuid'], 'AWS_ACCOUNT_TARGET_BINDING_REQUIRED')
    if check_files:
        need(core.digest(core.reads.read_bytes(rds['caBundle'])) == rds['caSha256'], 'RDS_CA_SHA_CHANGED')
    root = Path('/opt/airbob/global-b') / value['runId']
    need(Path(value['privateAccounts']) == root / 'private/accounts.private.json'
         and Path(value['releaseDirectory']).is_absolute(), 'RETAINED_CONNECT_ACCOUNTS_REQUIRED')
    asg = value['asg']
    fields(asg, 'name arn launchTemplate runtimeRevision originalCapacity')
    need(asg['name'] == rds['identifier'] + '-app' and match(r'arn:aws:autoscaling:ap-northeast-2:942632789808:autoScalingGroup:[A-Za-z0-9-]+:autoScalingGroupName/' + re.escape(asg['name']), asg['arn'])
         and asg['originalCapacity'] == {'min': 1, 'desired': 1, 'max': 1} and match(HASH, asg['runtimeRevision']), 'EXACT_ONE_APP_ASG_REQUIRED')
    fields(asg['launchTemplate'], 'LaunchTemplateId Version')
    need(match(r'lt-[0-9a-f]{17}', asg['launchTemplate']['LaunchTemplateId']) and match(r'[1-9][0-9]*', asg['launchTemplate']['Version']), 'PINNED_LAUNCH_TEMPLATE_REQUIRED')
    network = ipaddress.ip_network(value['networkCidr'], strict=True)
    need(network.version == 4 and network.is_private and network.prefixlen >= 16, 'PRIVATE_VPC_REQUIRED')
    fields(value['hosts'], 'app connect kafka elasticsearch')
    for role, host in value['hosts'].items():
        expected = 'instanceId privateIp containerId imageId image startedAt'
        if role == 'elasticsearch': expected += ' clusterUuid indexName'
        fields(host, expected)
        need(match(INSTANCE, host['instanceId']) and ipaddress.ip_address(host['privateIp']) in network, 'EXACT_HOST_COORDINATES_REQUIRED')
        repo = 'airbob-repo' if role == 'app' else 'airbob-infra/' + ('debezium' if role == 'connect' else role)
        need(match(HASH, host['containerId']) and match('sha256:' + HASH, host['imageId'])
             and match(IMAGE + re.escape(repo) + '@sha256:' + HASH, host['image'])
             and match(r'[0-9TZ:.-]{20,40}', host['startedAt']), 'EXACT_CONTAINER_IDENTITY_REQUIRED')
        if role == 'elasticsearch':
            need(match(r'[A-Za-z0-9_-]{10,64}', host['clusterUuid']) and match(r'accommodations[-_][a-zA-Z0-9_-]+', host['indexName']), 'EXACT_ES_IDENTITY_REQUIRED')
    need(len({h['instanceId'] for h in value['hosts'].values()}) == 4 and len({h['privateIp'] for h in value['hosts'].values()}) == 4,
         'SEPARATE_SERVICE_HOSTS_REQUIRED')
    pre = value['preconditions']
    fields(pre, 'preparedFingerprint preparationReceipt serviceReceipt exclusiveWriterWindow detailCacheState')
    need(pre['exclusiveWriterWindow'] is True and pre['detailCacheState'] == 'DISABLED', 'EXCLUSIVE_R4_WINDOW_REQUIRED')
    if check_files:
        for key in ('preparedFingerprint', 'preparationReceipt', 'serviceReceipt'):
            local_ref(pre[key])
    return value


def read_inputs(value):
    validate_config(value)
    import growth_b_service as service
    manifest = local_ref(value['serviceManifest'])
    ready = local_ref(value['serviceReadiness'])
    service.validate_manifest(manifest, value['datasetId'], value['runId'], manifest.get('serviceRelease'),
        {name: service.sha(Path(service.__file__).with_name(name)) for name in service.TOOLS})
    service.validate_readiness(ready, manifest, value['serviceManifest']['sha256'])
    need(value['serviceManifest']['key'] == f"datasets/{value['datasetId']}-aws-service/{manifest['serviceRelease']}/aws-service.json"
         and value['serviceReadiness']['key'] == f"data-bootstrap/{value['runId']}/{value['datasetId']}-service-{manifest['serviceRelease']}.json"
         and value['preconditions']['preparedFingerprint']['sha256'] == manifest['preparation']['preparedFingerprintSha256']
         and value['preconditions']['preparationReceipt']['sha256'] == manifest['preparation']['receipt']['sha256']
         and value['preconditions']['serviceReceipt']['sha256'] == value['serviceReadiness']['sha256'], 'EXACT_PREPARATION_SERVICE_REFS_REQUIRED')
    need(manifest['rds'] == {k: value['rds'][k] for k in ('identifier', 'resourceId', 'serverUuid')}
         and manifest['application']['image'] == value['hosts']['app']['image']
         and manifest['debezium']['image'] == value['hosts']['connect']['image']
         and manifest['search']['image'] == value['hosts']['elasticsearch']['image'], 'SELECTED_SERVICE_TARGET_CHANGED')
    release = Path(value['releaseDirectory'])
    checks_raw, consumer_raw = [core.reads.read_bytes(release / n) for n in ('SHA256SUMS.json', 'consumer-manifest.json')]
    need(core.digest(checks_raw) == value['checksumsSha256'] and core.digest(consumer_raw) == value['consumerManifestSha256'], 'SEAL_ANCHOR_MISMATCH')
    checks, consumer = core.parse(checks_raw), core.parse(consumer_raw)
    need(checks.get('consumer-manifest.json') == value['consumerManifestSha256'] and match(HASH, checks.get('airbob-growth.sql.gz'))
         and consumer.get('datasetId') == value['datasetId'] == 'global-growth-b-' + checks['airbob-growth.sql.gz'][:16]
         and consumer.get('schemaVersion') == 4 and consumer.get('mysql') == {'version': '8.4.11', 'flywayVersion': 28}
         and consumer.get('finalScaleSelected') is True and consumer.get('datasetScale') == 'selected-global-b-ten-million', 'FINAL_B_IDENTITY_REQUIRED')
    documents, hashes = {}, {}
    for name, key in [('accounts.json', 'accounts'), ('representative-accounts.json', 'representativeAccounts')]:
        raw = core.reads.read_bytes(release / name); hashes[name] = core.digest(raw); documents[name] = core.parse(raw)
        need(checks.get(name) == hashes[name] and consumer['artifacts'].get(key) == {'file': name, 'sha256': hashes[name]}, 'ACCOUNT_SEAL_MISMATCH')
    bundle, selected = documents['accounts.json'], documents['representative-accounts.json']
    need(bundle.get('representativeAccounts') == selected.get('accounts') and selected.get('finalScaleSelected') is True, 'REPRESENTATIVE_SEAL_MISMATCH')
    hosts = [row for row in selected['accounts'] if row.get('key') == 'host']
    need(len(hosts) == 1, 'SEALED_HOST_REQUIRED'); host = hosts[0]
    need(host.get('memberId') == value['ownerMemberId'] and host.get('email') == 'host@airbob.test'
         and host.get('role') == 'MEMBER' and host.get('status') == 'ACTIVE'
         and host.get('ownership', {}).get('publishedListings', {}).get('sampleIds', [None])[0] == value['accommodationId'], 'EXACT_REPRESENTATIVE_OWNER_REQUIRED')
    public = core.public_account_union(bundle); need(len(public) == 124, 'FULL_FINAL_ACCOUNT_UNION_REQUIRED')
    path = core.private_path(value['privateAccounts']); core.private_path(path.parent, directory=True)
    credentials = core.warm.private_accounts(path.read_bytes(), public, bundle, value['accountEnvironment'])
    return {'config': value, 'manifest': manifest, 'readiness': ready, 'credential': credentials[value['ownerMemberId']], 'host': host,
        'binding': {'datasetId': value['datasetId'], 'accountEnvironment': value['accountEnvironment'], 'consumerManifestSha256': value['consumerManifestSha256'],
            'checksumsSha256': value['checksumsSha256'], 'accountMetadataSha256': hashes, 'serviceManifestSha256': value['serviceManifest']['sha256'],
            'serviceReadinessSha256': value['serviceReadiness']['sha256'], 'toolIdentity': source_identity(),
            'serviceReferences': {k: {n: v for n, v in value[k].items() if n != 'path'} for k in ('serviceManifest', 'serviceReadiness')},
            'preconditions': {k: value['preconditions'][k]['sha256'] for k in ('preparedFingerprint', 'preparationReceipt', 'serviceReceipt')}}}


def journal_binding(config):
    # A newly acquired lease may resume cleanup. The business deadline, runtime,
    # resource fence, account environment and every source input stay immutable.
    value = copy.deepcopy(config); value.pop('lease')
    return core.digest(core.encoded(value))


def validate_freeze(value, config, *, now, expected_commands):
    fields(value, 'schemaVersion kind operationId configurationSha256 lease observedEpoch appTerminatedIds '
           'connectFinishedAt heartbeat commands outstandingCommandIds controllerToolSha256')
    need(value['schemaVersion'] == 1 and value['kind'] == 'global-b-aws-cdc-frozen-observation'
         and value['operationId'] == config['operationId'] and value['configurationSha256'] == journal_binding(config)
         and value['lease'] == config['lease'] and value['outstandingCommandIds'] == []
         and value['controllerToolSha256'] == source_identity()['infra/aws/scripts/growth_b_cdc_controller.py']
         and core.integer(value['observedEpoch'], 1) and 0 <= now - value['observedEpoch'] <= 120,
         'FRESH_OWNED_FREEZE_OBSERVATION_REQUIRED')
    need(value['appTerminatedIds'] == [config['hosts']['app']['instanceId']]
         and match(r'[0-9TZ:.-]{20,40}', value['connectFinishedAt']), 'EXACT_OLD_APP_TERMINATION_REQUIRED')
    fields(value['heartbeat'], 'topic offsets instanceId containerId')
    suffix = core.digest((config['runId'] + ':' + config['mysqlServerUuid']).encode())[:20]
    heartbeat = value['heartbeat']
    need(heartbeat['topic'] == '__debezium-heartbeat.airbob_b_' + suffix
         and heartbeat['instanceId'] == config['hosts']['kafka']['instanceId']
         and heartbeat['containerId'] == config['hosts']['kafka']['containerId']
         and set(heartbeat['offsets']) == {'0'} and core.integer(heartbeat['offsets']['0']), 'EXACT_FROZEN_HEARTBEAT_REQUIRED')
    need(isinstance(value['commands'], list) and len(value['commands']) == len(expected_commands), 'EXACT_FREEZE_COMMAND_SET_REQUIRED')
    seen = set()
    for command in value['commands']:
        fields(command, 'name instanceId containerId commandId commandSha256 status startedEpoch completedEpoch')
        name = command['name']; need(name in expected_commands and name not in seen, 'EXACT_FREEZE_COMMAND_SET_REQUIRED'); seen.add(name)
        expected = expected_commands[name]
        need({k: command[k] for k in ('instanceId', 'containerId', 'commandSha256')} == expected
             and match(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', command['commandId'])
             and command['status'] == 'Success' and core.integer(command['startedEpoch'], 1)
             and command['startedEpoch'] <= command['completedEpoch'] <= value['observedEpoch'], 'FREEZE_COMMAND_IDENTITY_OR_STATE_CHANGED')
    return value


class Session(core.Session):
    def request(self, method, path, body=None):
        cleanup = path == '/api/v1/auth/logout' or (path == '/api/v1/auth/me' and self.journal.latest_sequence('LOGOUT_INTENT') > self.journal.latest_sequence('LOGOUT_VERIFIED'))
        remaining = self.adapter.business_remaining() if not cleanup else self.adapter.cleanup_remaining()
        need(remaining > 0, 'CDC_WORK_DEADLINE_REACHED')
        requests = [e for e in self.journal.entries if e['kind'] == 'AWS_HTTP_REQUEST_INTENT']
        need(len(requests) < (64 if cleanup else 48), 'HTTP_REQUEST_BUDGET_EXCEEDED')
        self.journal.add('AWS_HTTP_REQUEST_INTENT', {'method': method, 'path': path, 'cleanup': cleanup})
        old = self.inputs['config']['requestTimeoutSeconds']
        self.inputs['config']['requestTimeoutSeconds'] = min(old, max(0.05, remaining))
        try:
            return super().request(method, path, body)
        finally:
            self.inputs['config']['requestTimeoutSeconds'] = old


class Verifier(core.Verifier):
    def __init__(self, inputs, adapter, journal, *, clock=time.monotonic, wall=time.time, sleep=time.sleep, session_factory=Session):
        super().__init__(inputs, adapter, journal, clock=clock, sleep=sleep, session_factory=session_factory)
        self.wall = wall
        self.business_deadline = self.started + max(0, inputs['config']['businessDeadlineEpoch'] - wall())
        self.deadline = self.business_deadline

    def verify(self):
        self.deadline = self.business_deadline
        if self.clock() >= self.business_deadline:
            # An expired business window never gets a new PATCH or login. It
            # may still invalidate the already-owned session under a fresh
            # valid outer lease; failure remains unqualified and journaled.
            baseline = self.journal.last('BASELINE')
            if baseline and self.journal.latest_sequence('LOGIN_INTENT') >= 0 and not self.journal.session_clean():
                names = ['airbob-cdc-' + self.journal.entries[0]['data']['nonce'], baseline['originalName']]
                session = self.factory(self.adapter, self.journal, self.inputs, names)
                try:
                    self.adapter.guard('running'); session.logout()
                    self.journal.add('AWS_EXPIRED_WINDOW_SESSION_INVALIDATED', {'newLogin': False, 'newPatch': False})
                except BaseException as error:
                    self.session_cleanup_failure = error.code if isinstance(error, Failed) else 'OWNED_SESSION_CLEANUP_UNCONFIRMED'
                    self.journal.add('SESSION_CLEANUP_UNCONFIRMED', {'code': self.session_cleanup_failure})
            raise Failed('CDC_WORK_DEADLINE_REACHED')
        self.limit()
        super().verify()
        self.limit()
        self.journal.add('AWS_BUSINESS_COMPLETE', {'deadlineEpoch': self.inputs['config']['businessDeadlineEpoch'],
            'completedEpoch': self.wall(), 'normalPatchCount': 2, 'normalSessionInvalidated': self.journal.session_clean()})

    def maintenance_deadline(self):
        config = self.inputs['config']
        self.deadline = self.clock() + max(0, min(900, config['expiresAt'] - self.wall(), config['approvedExecutionDeadlineEpoch'] - self.wall()))
        self.limit()

    def reset(self):
        self.maintenance_deadline()
        # The original reset orchestrator calls the AWS adapter's persistent
        # session and never calls reset_rows_sql or the OCI cleanup_window.
        return super().reset()

    def post_reset(self):
        self.maintenance_deadline()
        return super().post_reset()

    def public(self, action, passed):
        result = super().public(action, passed)
        result.update(kind='global-b-aws-owned-api-cdc-verification', operationId=self.inputs['config']['operationId'],
            runId=self.inputs['config']['runId'], businessWindow=self.journal.last('AWS_BUSINESS_COMPLETE'),
            controllerVerifyRuntimeIdentityRequired=True, sourceSnapshotGateSatisfied=False,
            r6LoadTest=False, r7ScaleOutTest=False)
        return result
