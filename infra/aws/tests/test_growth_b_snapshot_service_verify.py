"""Offline target-family admission and end-to-end public evidence contracts.

Fixtures below are synthetic unit evidence, never actual cloud completion.
Only network/host execution is replaced; production family/provenance/report
validators and the real Runner report/recovery generation remain in use.
"""
import base64
import contextlib
import copy
import datetime as dt
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import types
import unittest
import uuid
from unittest.mock import MagicMock, patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_snapshot_service_verify as target
import test_growth_b_cdc_aws as cdc_fixtures
import test_growth_b_cdc_supervisor as supervisor_fixtures
import test_growth_b_service_contract as service_fixtures
import test_growth_b_service_verify as read_fixtures
import test_growth_b_snapshot as snapshot_fixtures


def instant(value):
    return dt.datetime.fromtimestamp(value, dt.timezone.utc).isoformat()


def blob(value, key, version='unit-version-1'):
    raw = target.encoded(value)
    return {'reference': {'key': key, 'versionId': version, 'sha256': target.sha(raw), 'bytes': len(raw)},
            'base64': base64.b64encode(raw).decode()}


def proof_fixture(root, now=None):
    """Return exact synthetic snapshot target inputs, with no AWS calls."""
    epoch = int(time.time()) if now is None else int(now)
    root = Path(root).resolve(); root.mkdir(mode=0o700, parents=True, exist_ok=True); root.chmod(0o700)
    config, manifest, _ = cdc_fixtures.configuration(root)
    config.update(operationId='r5-read-01', expiresAt=epoch + 30000, approvedExecutionDeadlineEpoch=epoch + 40000)
    for pin in config['hosts'].values(): pin['startedAt'] = instant(epoch - 1000).replace('+00:00', 'Z')
    run, dataset, preparation_id = config['runId'], config['datasetId'], 'snapshot-prepare-01'
    snapshot_id = 'airbob-dataset-b-unit-final-snapshot'
    target_identity = {k: config['rds'][k] for k in ('identifier', 'resourceId', 'endpoint', 'serverUuid')}
    source = snapshot_fixtures.SnapshotFixture(); source.setUp()
    try: provenance = copy.deepcopy(source.provenance)
    finally: source.doCleanups()
    contract = provenance['contract']
    contract.update(datasetId=dataset, application=manifest['application'], source={
        'identifier': 'airbob-lab-b-source', 'resourceId': 'db-' + 'Z' * 24,
        'endpoint': 'source.abcdefghijk.ap-northeast-2.rds.amazonaws.com',
        'serverUuid': '87654321-4321-4321-4321-cba987654321'})
    contract['publication'].update(consumerManifestSha256=config['consumerManifestSha256'], checksumsSha256=config['checksumsSha256'])
    provenance['contractSha256'] = target.snapshot.canonical_sha(contract)
    provenance['snapshot'].update(sourceRdsResourceId=contract['source']['resourceId'])
    documents = {}
    def add(name, value, key):
        documents[name] = blob(value, key)
        return documents[name]['reference']
    provenance_ref = add('provenance', provenance, f'datasets/{dataset}-aws-snapshots/{snapshot_id}/placeholder')
    provenance_ref['key'] = f'datasets/{dataset}-aws-snapshots/{snapshot_id}/provenance-{provenance_ref["sha256"]}.json'
    admission = {'schemaVersion': 1, 'kind': target.snapshot.KIND + '-restore-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT',
        'provenanceSha256': provenance_ref['sha256'], 'source': contract['source'], 'targetIdentifier': target_identity['identifier'],
        'maximumSimultaneousBusinessDatabases': 1, 'restoreExecuted': False}
    admission_ref = add('admission', admission, f'data-bootstrap/{run}/{dataset}-snapshot/admit-01/restore-admission.json')
    event = {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com', 'awsRegion': target.cdc.REGION,
        'recipientAccountId': target.cdc.ACCOUNT, 'eventID': str(uuid.uuid4()), 'eventTime': instant(epoch - 800),
        'requestParameters': {'dBInstanceIdentifier': target_identity['identifier'], 'dBSnapshotIdentifier': snapshot_id}}
    event_ref = add('restoreEvent', event, f'data-bootstrap/{run}/{dataset}-snapshot/restore-01/restore-event.json')
    snapshot_prefix = target.snapshot_host.prefix(dataset, run, preparation_id)
    evidence_prefix = target.snapshot_host.evidence_prefix(dataset, run, preparation_id)
    selected = {'schemaVersion': 1, 'kind': target.snapshot_host.KIND, 'operation': 'prepare', 'operationId': preparation_id,
        'datasetId': dataset, 'runId': run, 'account': target.cdc.ACCOUNT, 'region': target.cdc.REGION,
        'mysql': target.snapshot.MYSQL, 'snapshotIdentifier': snapshot_id,
        'application': {k: manifest['application'][k] for k in ('mainCommit', 'image')},
        'awsPreparation': {'key': f'datasets/{dataset}-aws-preparation/aws-preparation-' + 'a' * 64 + '.json',
            'versionId': 'unit-preparation-v1', 'sha256': 'a' * 64, 'bytes': 100},
        'consumerTools': {'key': snapshot_prefix + 'files/' + 'b' * 64 + '-consumer-tools.tar.gz',
            'versionId': 'unit-tools-v1', 'sha256': 'b' * 64, 'bytes': 100},
        'toolSources': target.snapshot_host.sources(), 'operationTimeoutSeconds': 600,
        'evidence': {'provenance': provenance_ref, 'admission': admission_ref, 'restoreEvent': event_ref}}
    selected_ref = add('snapshotManifest', selected, snapshot_prefix + 'placeholder')
    selected_ref['key'] = snapshot_prefix + 'manifest-' + selected_ref['sha256'] + '.json'
    fingerprint = copy.deepcopy(contract['preparedFingerprint'])
    fingerprint_ref = add('preparedFingerprint', fingerprint, evidence_prefix + 'prepared-fingerprint.json')
    preparation = {'passed': True, 'readinessVerified': True, 'accountLogins': {'passed': True},
        'currentInventory': {'everyHorizonContiguous': True}, 'preparedFingerprintSha256': fingerprint_ref['sha256']}
    actual = {'eventId': event['eventID'], 'eventSha256': target.snapshot.canonical_sha(event), 'requestedAt': event['eventTime'],
        'instanceCreateTime': instant(epoch - 790), 'evidenceSource': 'controller-pinned-cloudtrail-event'}
    bindings = {'configSha256': 'd' * 64, 'targetIdentity': target_identity, 'provenanceSha256': provenance_ref['sha256'],
        'admissionSha256': admission_ref['sha256'], 'restoreEventSha256': event_ref['sha256'], 'credentialBindingSha256': 'e' * 64,
        'toolIdentity': target.snapshot.tool_identity(), 'datasetId': dataset, 'envelopeSha256': contract['publication']['envelopeSha256']}
    preflight = dict(bindings, schemaVersion=1, kind=target.snapshot.KIND + '-preflight', operation='prepare',
        state='RESTORED_TARGET_READ_ONLY_PREFLIGHT', actualRestore=actual)
    preflight_ref = add('targetPreflight', preflight, evidence_prefix + 'preflight.json')
    operation_proof = dict(bindings, schemaVersion=1, kind=target.snapshot.KIND + '-operation', operation='prepare',
        state=target.snapshot.PREPARED, snapshotWholeBaselineVerified=True, sqlImportSkipped=True,
        databaseRecreatedInThisRun=False, actualRestoreVerified=True, applicationLeftRunning=False, deploymentReady=False,
        snapshotBaselineCanonicalSha256=contract['preparedFingerprintCanonicalSha256'],
        preparedFingerprintSha256=fingerprint_ref['sha256'], preparedFingerprintCanonicalSha256=target.snapshot.canonical_sha(fingerprint),
        preparation=preparation, actualRestore=actual, currentInventory=preparation['currentInventory'],
        fullValidationStartedAt=instant(epoch - 700), fullValidationCompletedAt=instant(epoch - 600), fullValidationSeconds=100,
        preparationStartedAt=instant(epoch - 599), preparationCompletedAt=instant(epoch - 500), preparationSeconds=99)
    operation_ref = add('snapshotOperation', operation_proof, evidence_prefix + 'snapshot-operation.json')
    prepared = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
        'sourceMode': 'verified-global-b-snapshot', 'datasetId': dataset, 'runId': run, 'rdsResourceId': target_identity['resourceId'],
        'serverUuid': target_identity['serverUuid'], 'rdsEngineVersion': '8.4.11', 'flywayVersion': 28, 'preparation': preparation,
        'restoreReceiptSha256': operation_ref['sha256'], 'snapshotProvenanceSha256': provenance_ref['sha256'],
        'snapshotRestoreEvidence': actual, 'applicationLeftRunning': False, 'deploymentReady': False, 'completedAt': instant(epoch - 499)}
    prepared_ref = add('preparation', prepared, evidence_prefix + 'data-only-preparation.json')
    host = {'schemaVersion': 1, 'kind': target.snapshot_host.KIND + '-receipt', 'state': 'HOST_OPERATION_COMPLETE',
        'operation': 'prepare', 'operationId': preparation_id, 'runId': run, 'datasetId': dataset,
        'toolSources': selected['toolSources'], 'hostInstanceId': config['hosts']['connect']['instanceId'],
        'manifestSha256': selected_ref['sha256'], 'targetIdentity': target_identity,
        'restoreConfigSha256': manifest['preparation']['restoreConfigSha256'], 'derivedRestoreConfigSha256': 'f' * 64,
        'preflightSha256': preflight_ref['sha256'], 'objects': {'preflight.json': preflight_ref,
            'snapshot-operation.json': operation_ref, 'data-only-preparation.json': prepared_ref, 'prepared-fingerprint.json': fingerprint_ref},
        'persistentAwsResourcesMutatedByHost': False, 'sqlImportExecuted': False, 'applicationLeftRunning': False,
        'deploymentReady': False, 'completedAt': instant(epoch - 498)}
    host_ref = add('hostReceipt', host, evidence_prefix + 'host-receipt.json')
    search = {'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'datasetId': dataset, 'mysql': {'serverUuid': target_identity['serverUuid']},
        'snapshotRelease': manifest['search']['snapshotRelease'], 'fullDocumentFingerprint': manifest['search']['documentFingerprint'],
        'nativeTransport': {'manifestSha256': manifest['search']['transport']['sha256'], 'exactVersionBytesVerified': True, 'sourceSealsChanged': False},
        'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'allDocumentSourceFieldsEqual': True,
        'elasticsearch': {'image': manifest['search']['image'], 'clusterUuid': config['hosts']['elasticsearch']['clusterUuid']},
        'restoredIndex': config['hosts']['elasticsearch']['indexName']}
    search_ref = add('searchRestore', search, f'data-bootstrap/{run}/search-restore.json')
    manifest['search']['restoreReceipt'] = search_ref
    manifest['preparation'].update(receipt=prepared_ref, restoreReceiptSha256=operation_ref['sha256'], preparedFingerprintSha256=fingerprint_ref['sha256'])
    mref = add('serviceManifest', manifest, f'datasets/{dataset}-aws-service/{manifest["serviceRelease"]}/aws-service.json')
    ready = service_fixtures.receipt(manifest); ready.update(manifestSha256=mref['sha256'], restoredIndex=search['restoredIndex'])
    rref = add('readiness', ready, f'data-bootstrap/{run}/{dataset}-service-{manifest["serviceRelease"]}.json')
    config.update(serviceManifest=mref, serviceReadiness=rref)
    _, context = supervisor_fixtures.context(config, manifest, ready)
    context.update(kind=target.CONTEXT_KIND, controllerDeadlineEpoch=epoch + 17940)
    context['operator'].update(globalBSnapshotRestoreOnly=True, globalBPrepareOnly=False, databaseBootstrap='snapshot',
        mode='performance', dnsMode='direct-only', bundleCommit=manifest['application']['mainCommit'],
        appImageReference=manifest['application']['image'], approvedExecutionDeadlineEpoch=context['approvedExecutionDeadlineEpoch'], globalBSnapshotProvenance={
            'key': provenance_ref['key'], 'version_id': provenance_ref['versionId'], 'sha256': provenance_ref['sha256'], 'bytes': provenance_ref['bytes']})
    operation = {'schemaVersion': 1, 'kind': target.KIND, 'stage': 'snapshot-verify', 'operationId': config['operationId'],
        'runId': run, 'datasetId': dataset, 'serviceRelease': manifest['serviceRelease'], 'executionCommit': config['executionCommit'],
        'sourceArchiveSha256': target.source_archive()[1]['sha256'], 'manifest': mref, 'readiness': rref,
        'targetPreparation': {'manifest': selected_ref, 'hostReceipt': host_ref}}
    identity = {k: copy.deepcopy(config[k]) for k in ('rds', 'asg', 'hosts', 'networkCidr')}
    identity['asg'].pop('originalCapacity'); identity['rds']['caBundle'] = f'/opt/airbob/global-b/{run}/rds-ca.pem'
    identity['rds']['caSha256'] = manifest['preparation']['rdsCaBundle']['sha256']
    target.validate_operation(operation); target.validate_context(context, operation, clock=lambda: epoch)
    proofs = target.validate_proofs(operation, context, documents); target.validate_identity(identity, operation, context, proofs)
    return {'operation': operation, 'context': context, 'documents': documents, 'identity': identity, 'config': config, 'now': epoch}


class NoAws:
    def call(self, *args):
        raise AssertionError('Offline fixture forbids AWS: ' + ':'.join(args[:2]))


def completion_fixture(root, now=None):
    """Run the actual producer/report validator with only host/AWS I/O mocked."""
    values = proof_fixture(root, now); op, context, identity = (values[k] for k in ('operation', 'context', 'identity'))
    epoch = values['now']; clock = [epoch]; host_bytes = {}; token = [0]
    output = Path(root).resolve() / 'verifier'
    runner = target.Runner(op, context, output, aws=NoAws(), clock=lambda: clock[0])
    runner.documents = values['documents']; runner.identity = identity
    proofs = target.validate_proofs(op, context, values['documents'])
    runner.proofs = lambda: proofs; runner.discover = lambda _: identity; runner.install = lambda: None
    runner.guard = lambda: None; runner.transport.settle = lambda _: None
    def command(program, pin, start, end, name):
        token[0] += 1
        return {'token': token[0], 'name': name, 'instanceId': pin['instanceId'], 'containerId': pin['containerId'],
            'commandSha256': target.sha(program.encode()), 'startedEpoch': start, 'completedEpoch': end,
            'commandId': str(uuid.uuid4()), 'status': 'Success'}
    def app_observation(name, selected):
        after = name.endswith('after'); start = epoch + (160 if after else 0); end = start + 1
        pin = identity['hosts']['app']
        obs = dict(pin, finishedAt='0001-01-01T00:00:00.000000000Z', running=True, normalProfile='aws', readiness=True,
            appJarSha256=selected['readiness']['appRuntime']['imageJarSha256'], runtimeRevision=identity['asg']['runtimeRevision'])
        program = target.transport.observed_app_program(pin, identity['asg']['runtimeRevision'], selected['readiness']['appRuntime'])
        result = target.app_attestation(op, identity['hosts'], identity['asg']['runtimeRevision'], selected['readiness'],
            {'passed': True, 'observation': obs}, command(program, pin, start, end, name))
        runner.journal.add('TARGET_APP_OBSERVED', {'name': name, 'attestation': result}); clock[0] = end
        return result
    runner.app_observation = app_observation
    area = Path('/opt/airbob/global-b') / op['runId'] / 'r5-service' / op['operationId']
    def stage():
        raw = target.encoded(runner.job)
        return {'path': str(area / 'inputs' / (target.sha(raw) + '-job.json')), 'sha256': target.sha(raw), 'bytes': len(raw)}
    runner.stage_job = stage
    def host_output(name, value):
        raw = target.encoded(value); path = str(area / 'result' / name); host_bytes[path] = raw
        return {'path': path, 'sha256': target.sha(raw), 'bytes': len(raw)}
    def once(key, name, pin, program, **_):
        if key != 'target-read-worker': raise AssertionError('Unexpected fixture I/O')
        with patch.object(read_fixtures, 'NOW', epoch):
            representative, warm, media = read_fixtures.ServiceFixture.read_receipts(types.SimpleNamespace(old=values['config'], cdc=values['config']))
        warm.update(preconditions={'fingerprintReceiptSha256': values['documents']['snapshotOperation']['reference']['sha256'],
            'appImage': identity['hosts']['app']['image'], 'detailCacheState': 'DISABLED'}, scope={'businessWriteRequests': 0})
        observations = {'readiness': {'status': 'UP', 'httpStatus': 200}, 'loopbackRelayClosed': True}
        for key_, path, value in (('representatives', 'representatives/representative-http-reads.json', representative),
            ('warmup', 'warmup/warmup-receipt.json', warm), ('media', 'media/media-availability.json', media)):
            observations[key_] = {k: v for k, v in host_output(path, value).items() if k != 'bytes'}
        runtime = {'kind': 'airbob-growth-timezone-qualification', 'state': 'TIMEZONE_RUNTIME_QUALIFIED',
            'passed': True, 'consumerRuntimePassed': True, 'allFixedCasesPassed': True, 'runtimeIdentityPassed': True,
            'selectedZonesPassed': True, 'consumerHost': {'execution': 'current process host'},
            'consumerReleaseBindings': {'consumerHelperSha256': target.contract.sha(target.runtime_gate.__file__)}}
        runtime_ref = {k: v for k, v in host_output('host-runtime.json', runtime).items() if k != 'bytes'}
        observation = {'schemaVersion': 1, 'kind': target.HOST_KIND + '-observation', 'state': 'TARGET_READ_OBSERVATIONS_COMPLETE',
            'operationSha256': target.operation_binding(op), 'jobSha256': target.sha(target.encoded(runner.job)), 'identity': identity,
            'lease': context['lease'], 'sourceFiles': target.source_files(), 'startedAt': instant(epoch + 10), 'completedAt': instant(epoch + 150),
            'admissionEpoch': epoch + 3, 'deadlineEpoch': runner.job['deadlineEpoch'], 'observations': observations,
            'hostRuntimeQualification': runtime_ref | {'execution': 'current process host'},
            'preparedFingerprintRef': values['documents']['preparedFingerprint']['reference'],
            'actualTargetUuidAndTlsBeforeAndAfterVerified': True, 'businessDatabaseWritesPerformed': False, 'cdcMutationPerformed': False,
            'privateValuesIncluded': False, 'postReadAppAttestationRequired': True}
        result = {'passed': True, 'reference': host_output('host-observation.json', observation)}
        cmd = command(program, pin, epoch + 2, epoch + 151, name)
        runner.journal.add('COMMAND_COMPLETE', {'key': key, 'value': result, 'command': cmd}); clock[0] = epoch + 151
        return result, cmd
    runner.once = once
    def download(ref):
        raw = host_bytes[ref['path']]
        if target.sha(raw) != ref['sha256']: raise AssertionError('Synthetic host output differs')
        return raw
    runner.download = download
    try:
        with patch.object(target.controller, 'verify_environment'), patch.object(target, 'now', return_value=instant(epoch + 200)):
            report = runner.run()
        if report['state'] != target.COMPLETE:
            raise AssertionError('Synthetic actual-producer path failed: ' + str(report))
    finally: runner.close()
    target.validate_completion(op, context, output)
    values.update(output=output, report=report)
    return values


class TargetServiceVerificationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()

    def test_actual_producer_public_reports_validate_offline(self):
        value = completion_fixture(self.root)
        self.assertEqual(target.COMPLETE, target.validate_completion(value['operation'], value['context'], value['output'])['state'])
        self.assertEqual(target.PUBLIC_FILES | {'recovery.json'}, {p.name for p in (value['output'] / 'public').iterdir()})

    def test_completion_cli_has_no_time_or_cloud_dependency(self):
        value = completion_fixture(self.root, now=1800000000)
        operation, context = self.root / 'operation.json', self.root / 'context.json'
        for path, content in ((operation, value['operation']), (context, value['context'])):
            target.core.write_new(path, target.encoded(content))
        result = subprocess.run([sys.executable, str(Path(target.__file__)), 'validate-completion', '--operation', str(operation),
            '--context', str(context), '--output', str(value['output'])], capture_output=True, text=True, timeout=10,
            env={'PATH': os.environ['PATH'], 'AWS_EC2_METADATA_DISABLED': 'true'})
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual({'state': target.COMPLETE, 'awsCallsExecuted': False}, json.loads(result.stdout))

    def test_closed_target_operation_rejects_source_family_and_manual_flags(self):
        data = proof_fixture(self.root)
        for key, value in (('kind', target.transport.KIND), ('stage', 'all'), ('sourceArchiveSha256', '0' * 64), ('passed', True)):
            altered = copy.deepcopy(data['operation']); altered[key] = value
            with self.subTest(key=key), self.assertRaises((target.Failed, ValueError)): target.validate_operation(altered)

    def test_snapshot_restore_operator_family_cache_and_expiry_are_mandatory(self):
        data = proof_fixture(self.root)
        for key, value in (('globalBSnapshotRestoreOnly', False), ('globalBPrepareOnly', True), ('databaseBootstrap', 'dump'),
                           ('cacheEnabled', True), ('mode', 'legacy')):
            altered = copy.deepcopy(data['context']); altered['operator'][key] = value
            with self.subTest(key=key), self.assertRaises(target.Failed):
                target.validate_context(altered, data['operation'], clock=lambda: data['now'])
        altered = copy.deepcopy(data['context']); altered['controllerDeadlineEpoch'] = data['now'] + 18001
        with self.assertRaises(target.Failed): target.validate_context(altered, data['operation'], clock=lambda: data['now'])

    def test_actual_transitive_snapshot_baseline_proof_is_required(self):
        data = proof_fixture(self.root)
        for name in ('snapshotOperation', 'hostReceipt', 'targetPreflight', 'preparedFingerprint', 'restoreEvent', 'provenance'):
            documents = copy.deepcopy(data['documents']); documents.pop(name)
            with self.subTest(name=name), self.assertRaises(target.Failed): target.validate_proofs(data['operation'], data['context'], documents)

    def test_original_approved_application_and_deadline_cannot_be_rebound(self):
        data = proof_fixture(self.root)
        for key, value in (('bundleCommit', 'f' * 40), ('appImageReference', data['config']['hosts']['app']['image'].split('@')[0] + '@sha256:' + 'f' * 64)):
            context = copy.deepcopy(data['context']); context['operator'][key] = value
            with self.subTest(key=key), self.assertRaisesRegex(target.Failed, 'ORIGINAL_TARGET_APPLICATION_CHANGED'):
                target.validate_proofs(data['operation'], context, data['documents'])
        context = copy.deepcopy(data['context']); context['operator']['approvedExecutionDeadlineEpoch'] += 1
        with self.assertRaisesRegex(target.Failed, 'ORIGINAL_TARGET_DEADLINE_REQUIRED'):
            target.validate_context(context, data['operation'], clock=lambda: data['now'])

    def test_modified_snapshot_receipt_bytes_or_version_are_rejected(self):
        data = proof_fixture(self.root)
        for field, value in (('base64', base64.b64encode(b'{"passed":true}').decode()), ('version', 'different-version')):
            documents = copy.deepcopy(data['documents'])
            if field == 'version': documents['snapshotOperation']['reference']['versionId'] = value
            else: documents['snapshotOperation'][field] = value
            with self.subTest(field=field), self.assertRaises(target.Failed): target.validate_proofs(data['operation'], data['context'], documents)

    def test_target_identity_never_adopts_a_different_rds_asg_or_alias(self):
        data = proof_fixture(self.root); proofs = target.validate_proofs(data['operation'], data['context'], data['documents'])
        for group, field, value in (('rds', 'serverUuid', '87654321-4321-4321-4321-cba987654321'), ('rds', 'resourceId', 'db-' + 'Z' * 24),
                                  ('asg', 'runtimeRevision', '0' * 64), ('elasticsearch', 'indexName', 'accommodations_foreign')):
            changed = copy.deepcopy(data['identity'])
            (changed['hosts'][group] if group == 'elasticsearch' else changed[group])[field] = value
            with self.subTest(group=group, field=field), self.assertRaises(target.Failed):
                target.validate_identity(changed, data['operation'], data['context'], proofs)

    def test_private_output_or_changed_public_bytes_cannot_pass_completion(self):
        data = completion_fixture(self.root); path = data['output'] / 'public/representative-http-reads.json'
        path.write_bytes(b'{"cookie":"SYNTHETIC_COOKIE_NEVER_PUBLIC"}\n')
        with self.assertRaises((ValueError, target.Failed)): target.validate_completion(data['operation'], data['context'], data['output'])

    def test_missing_public_report_cannot_pass_completion(self):
        data = completion_fixture(self.root); (data['output'] / 'public/media-availability.json').unlink()
        with self.assertRaises((ValueError, target.Failed, FileNotFoundError)): target.validate_completion(data['operation'], data['context'], data['output'])

    def test_final_public_report_must_match_the_exact_checkpointed_bytes(self):
        data = completion_fixture(self.root); path = data['output'] / 'public/snapshot-target-service-verification.json'
        original = path.read_bytes()
        for key, value in (('unreviewedClaim', True), ('cookie', 'DO_NOT_PUBLISH')):
            report = json.loads(original); report[key] = value; path.write_bytes(target.encoded(report))
            with self.subTest(key=key), self.assertRaisesRegex(target.Failed, 'RECORDED_TARGET_COMPLETION_BYTES_CHANGED'):
                target.validate_completion(data['operation'], data['context'], data['output'])

    def test_resealed_read_reports_still_require_ownership_logout_warmup_media_and_runtime(self):
        data = completion_fixture(self.root)
        original = json.loads((data['output'] / 'public/recovery.json').read_bytes())
        cases = [('representative-http-reads.json', lambda p: p['accounts'][0].update(logoutInvalidatesSession=False)),
            ('representative-http-reads.json', lambda p: p['accounts'][1].update(sealedListingSampleMatches=False)),
            ('warmup-receipt.json', lambda p: p.update(successfulWorkloadGetCount=299)),
            ('warmup-receipt.json', lambda p: p['preconditions'].update(fingerprintReceiptSha256='0' * 64)),
            ('media-availability.json', lambda p: p['images'][0].update(fullyDecoded=False)),
            ('host-runtime-qualification.json', lambda p: p.update(selectedZonesPassed=False))]
        for name, mutate in cases:
            recovery, report = copy.deepcopy(original), copy.deepcopy(data['report'])
            old = base64.b64decode(original['publicArtifacts'][name]['base64']); content = json.loads(old); mutate(content)
            raw = target.encoded(content); path = data['output'] / 'public' / name; path.write_bytes(raw)
            report['artifacts'][name] = {'sha256': target.sha(raw), 'bytes': len(raw)}
            host = recovery['hostObservation']
            keys = {'representative-http-reads.json': 'representatives', 'warmup-receipt.json': 'warmup', 'media-availability.json': 'media'}
            if name in keys: host['observations'][keys[name]]['sha256'] = target.sha(raw)
            else: host['hostRuntimeQualification']['sha256'] = target.sha(raw)
            report['hostObservationSha256'] = target.sha(target.encoded(host))
            for event in recovery['journal']:
                if event['kind'] == 'COMMAND_COMPLETE': event['data']['value']['reference']['sha256'] = report['hostObservationSha256']
            with self.subTest(name=name), self.assertRaises(target.Failed):
                target.validate_result(data['operation'], data['context'], recovery['documents'], recovery['job'], host,
                    recovery['journal'], report, data['output'] / 'public')
            path.write_bytes(old)

    def test_manual_scope_and_runtime_timing_flags_cannot_replace_evidence(self):
        data = completion_fixture(self.root); recovery = json.loads((data['output'] / 'public/recovery.json').read_bytes())
        for mutate in (lambda p: p['service'].update(representativeAccounts=124), lambda p: p.update(sourceR4Claimed=True),
            lambda p: p['after']['observation'].update(containerId='f' * 64),
            lambda p: p['after']['command'].update(status='Failed'), lambda p: p['timings'].update(readCompletedAt=instant(data['now'] + 1)),
            lambda p: p.update(preparedFingerprintScope='unverified after-read fingerprint')):
            report = copy.deepcopy(data['report']); mutate(report)
            with self.assertRaises(target.Failed): target.validate_result(data['operation'], data['context'], recovery['documents'],
                recovery['job'], recovery['hostObservation'], recovery['journal'], report, data['output'] / 'public')

    def test_public_recovery_hash_chain_and_completion_command_are_mandatory(self):
        data = completion_fixture(self.root); path = data['output'] / 'public/recovery.json'
        recovery = json.loads(path.read_bytes()); recovery['journal'][-1]['data']['tampered'] = True
        path.write_bytes(target.encoded(recovery))
        with self.assertRaises(target.Failed): target.validate_completion(data['operation'], data['context'], data['output'])

    def test_unknown_ssm_submission_is_not_replayed_under_a_new_lease(self):
        data = proof_fixture(self.root)
        runner = target.Runner(data['operation'], data['context'], self.root / 'initial', aws=NoAws(), clock=lambda: data['now'])
        runner.guard = lambda: None; pin = data['identity']['hosts']['connect']
        runner.journal.add('COMMAND_OPERATION_INTENT', {'key': 'target-read-worker', 'name': 'target-read-worker',
            'instanceId': pin['instanceId'], 'containerId': pin['containerId'], 'commandSha256': 'a' * 64})
        recovery = json.loads((runner.public / 'recovery.json').read_bytes()); runner.close()
        ref = blob(recovery, 'placeholder')['reference']; ref['key'] = target.prefix(data['operation']) + 'recovery-' + ref['sha256'] + '.json'
        op = data['operation'] | {'resume': {'recovery': ref}}
        context = copy.deepcopy(data['context']); context['lease']['fencingToken'] += 1
        resumed = target.Runner(op, context, self.root / 'resumed', aws=NoAws(), recovery=recovery, clock=lambda: data['now'] + 1)
        try:
            resumed.guard = lambda: None; resumed.transport.run = MagicMock(side_effect=AssertionError('No replay'))
            with self.assertRaisesRegex(target.Failed, 'UNCERTAIN_SSM_SUBMISSION_NO_REPLAY'):
                resumed.once('target-read-worker', 'target-read-worker', pin, 'never executed')
            resumed.transport.run.assert_not_called()
        finally: resumed.close()

    def test_controller_adapter_has_no_resource_or_cdc_mutation_actions(self):
        aws = target.ReadAws(int(time.time()) + 100)
        for action in (('autoscaling', 'update-auto-scaling-group'), ('rds', 'create-db-snapshot'), ('ssm', 'cancel-command'),
                       ('iam', 'put-role-policy'), ('ec2', 'terminate-instances')):
            with self.subTest(action=action), self.assertRaises(target.Failed): aws.call(*action)

    def test_fixed_but_never_submitted_job_cannot_start_under_a_different_lease(self):
        data = completion_fixture(self.root)
        recovery = json.loads((data['output'] / 'public/recovery.json').read_bytes())
        context = copy.deepcopy(data['context']); context['lease']['fencingToken'] += 1
        runner = target.Runner(data['operation'], context, self.root / 'new-lease', aws=NoAws(), clock=lambda: data['now'] + 300)
        try:
            runner.documents, runner.identity, runner.job = recovery['documents'], recovery['identity'], recovery['job']
            fixed = next(e['data'] for e in recovery['journal'] if e['kind'] == 'TARGET_WORKER_FIXED')
            runner.journal.add('TARGET_WORKER_FIXED', fixed)
            runner.guard = lambda: None; runner.transport.settle = lambda _: None
            runner.proofs = lambda: target.validate_proofs(data['operation'], context, runner.documents)
            runner.discover = lambda _: runner.identity; runner.install = lambda: None
            runner.once = MagicMock(side_effect=AssertionError('Never submitted under the old lease'))
            with patch.object(target.controller, 'verify_environment'): result = runner.run()
            self.assertEqual('UNSTARTED_TARGET_JOB_REQUIRES_CURRENT_LEASE', result['failureCode'])
            runner.once.assert_not_called()
        finally: runner.close()

    def test_host_guard_accepts_real_rds_endpoint_shape_and_rejects_runtime_drift(self):
        data = proof_fixture(self.root); op, context, identity = (data[k] for k in ('operation', 'context', 'identity'))
        now = data['now']; connect = identity['hosts']['connect']
        base_tags = target.cdc_host.run_tags({'runId': op['runId'], 'resourceFencingToken': context['operator']['fencingToken'],
                                            'expiresAt': int(context['operator']['expiresAt'])})
        def tags(role): return [{'Key': k, 'Value': v} for k, v in (base_tags | {'Service': role}).items()]
        rds = {'DbiResourceId': identity['rds']['resourceId'], 'Endpoint': {'Address': identity['rds']['endpoint'], 'Port': 3306,
                'HostedZoneId': 'Z_SYNTHETIC_PUBLIC_METADATA'}, 'Engine': 'mysql', 'EngineVersion': '8.4.11', 'DBInstanceStatus': 'available',
            'PubliclyAccessible': False, 'MasterUserSecret': {'SecretArn': identity['rds']['masterSecretArn']},
            'MasterUsername': 'synthetic_master', 'TagList': tags('rds')}
        group = {'AutoScalingGroupName': identity['asg']['name'], 'AutoScalingGroupARN': identity['asg']['arn'],
            'LaunchTemplate': identity['asg']['launchTemplate'], 'MinSize': 1, 'DesiredCapacity': 1, 'MaxSize': 1, 'Tags': tags('app'),
            'Instances': [{'InstanceId': identity['hosts']['app']['instanceId'], 'LaunchTemplate': identity['asg']['launchTemplate'],
                           'HealthStatus': 'Healthy', 'LifecycleState': 'InService'}]}
        docker = {'Id': connect['containerId'], 'Image': connect['imageId'], 'Config': {'Image': connect['image']},
                  'State': {'Running': True, 'Paused': False, 'Restarting': False, 'StartedAt': connect['startedAt']}}
        def call(*args):
            if args[:2] == ('sts', 'get-caller-identity'):
                return {'Account': target.cdc.ACCOUNT, 'Arn': f'arn:aws:sts::{target.cdc.ACCOUNT}:assumed-role/airbob-lab-host-{op["runId"]}-debezium/{connect["instanceId"]}'}
            if args[:2] == ('rds', 'describe-db-instances'): return {'DBInstances': [rds]}
            if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'): return {'AutoScalingGroups': [group]}
            raise AssertionError('Unexpected host API: ' + str(args[:2]))
        job = {'operation': op, 'context': context, 'identity': identity, 'deadlineEpoch': now + 1800, 'sourceFiles': target.source_files()}
        guard = target.HostGuard(job, aws=types.SimpleNamespace(call=call), clock=lambda: now, monotonic=lambda: 10)
        guard.lease = MagicMock()
        old_sha = target.contract.sha
        def public_sha(path): return identity['rds']['caSha256'] if str(path) == identity['rds']['caBundle'] else old_sha(path)
        clean_env = {k: v for k, v in os.environ.items() if not k.startswith('AWS_')}
        with patch.dict(os.environ, clean_env, clear=True), patch.object(target.cdc_host, 'imds_identity', return_value={k: connect[k] for k in ('instanceId', 'privateIp')}), \
             patch.object(target.core, 'command', side_effect=lambda *a, **kw: target.encoded([docker])), patch.object(target.contract, 'sha', side_effect=public_sha):
            guard(force=True); self.assertEqual('synthetic_master', guard.master_username)
            group['Instances'][0]['LaunchTemplate'] = {'LaunchTemplateId': 'lt-' + 'a' * 17, 'Version': '4'}
            with self.assertRaisesRegex(target.Failed, 'TARGET_NORMAL_APP_CAPACITY_CHANGED'): guard(force=True)
            group['Instances'][0]['LaunchTemplate'] = identity['asg']['launchTemplate']
            docker['State']['StartedAt'] = instant(now)
            with self.assertRaisesRegex(target.Failed, 'TARGET_CONNECT_LIFETIME_CHANGED'): guard(force=True)

    def test_source_archive_is_exact_aws_only_and_each_stage_request_is_bounded(self):
        data = proof_fixture(self.root); archive, meta = target.source_archive()
        self.assertLessEqual(len(archive), 512 * 1024); self.assertEqual(target.source_files(), {k: v['sha256'] for k, v in meta['files'].items()})
        self.assertTrue(all(k.startswith('infra/aws/') for k in meta['files']))
        self.assertNotIn('scripts/verify-growth-b-cdc.py', meta['files'])
        pin = data['identity']['hosts']['connect']; payload = {'operationId': data['operation']['operationId'], 'runId': data['operation']['runId'],
            'lease': data['context']['lease'], 'pin': pin, 'expiresAt': int(data['context']['operator']['expiresAt']),
            'deadlineEpoch': data['context']['controllerDeadlineEpoch']}
        for index, offset in enumerate(range(0, len(archive), target.transport.CHUNK_BYTES)):
            text = target.transport.stage_program(payload, meta, index=index, data=archive[offset:offset + target.transport.CHUNK_BYTES])
            request = {'DocumentName': 'AWS-RunShellScript', 'DocumentVersion': '1', 'InstanceIds': [pin['instanceId']], 'TimeoutSeconds': 60,
                'Parameters': {'commands': [text], 'executionTimeout': ['90']}}
            self.assertLessEqual(len(target.encoded(request)), target.transport.MAX_COMMAND_BYTES)


class TargetWorkerBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup); self.root = Path(self.temp.name).resolve()
        self.area = self.root / 'r5-service/op-01'; self.area.mkdir(mode=0o700, parents=True); self.area.parent.chmod(0o700)
        (self.root / 'cdc-tools').mkdir(mode=0o700); (self.area / 'inputs').mkdir(mode=0o700)
        self.job = self.area / 'inputs/job.json'; self.job.write_bytes(target.encoded({'kind': target.HOST_KIND, 'deadlineEpoch': 1800001000})); self.job.chmod(0o600)
        self.payload = {'operationId': 'op-01', 'sources': {}, 'job': {'path': str(self.job), 'sha256': target.sha(self.job.read_bytes())},
            'deadlineEpoch': 1800001000}

    def namespace(self, payload):
        namespace = {}
        exec(target.transport.HOST_COMMON.replace('__PAYLOAD__', base64.b64encode(target.encoded(payload)).decode()), namespace)
        namespace['guard'] = lambda: self.root
        return namespace

    def test_private_credentials_or_cookie_paths_cannot_be_public_exports(self):
        for relative in ('result/.private/cookie.json', 'inputs/job.json'):
            path = self.area / relative; path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            path.write_bytes(b'{"cookie":"DO_NOT_EXPORT"}'); path.chmod(0o600)
            namespace = self.namespace(self.payload | {'reference': {'path': str(path), 'sha256': target.sha(path.read_bytes())}, 'index': None})
            with contextlib.redirect_stdout(io.StringIO()) as out, self.assertRaises(SystemExit): exec(target.DOWNLOAD_BODY, namespace)
            self.assertFalse(json.loads(out.getvalue())['passed']); self.assertNotIn('DO_NOT_EXPORT', out.getvalue())

    def test_exact_public_bytes_are_exported_without_rows_or_reencoding(self):
        path = self.area / 'result/representatives/representative-http-reads.json'; path.parent.mkdir(parents=True, mode=0o700)
        raw = b'{\n "publicUnitFixture": true\n}\n'; path.write_bytes(raw); path.chmod(0o600)
        namespace = self.namespace(self.payload | {'reference': {'path': str(path), 'sha256': target.sha(raw)}, 'index': None})
        with contextlib.redirect_stdout(io.StringIO()) as out: exec(target.DOWNLOAD_BODY, namespace)
        reference = json.loads(out.getvalue())['reference']; namespace['p'].update(reference=reference, index=0)
        with contextlib.redirect_stdout(io.StringIO()) as out: exec(target.DOWNLOAD_BODY, namespace)
        self.assertEqual(raw, base64.b64decode(json.loads(out.getvalue())['data']))

    def test_intent_without_pid_never_launches_a_second_reader(self):
        intent = self.area / 'worker-intent.json'; intent.write_bytes(b'{}\n'); intent.chmod(0o600)
        namespace = self.namespace(self.payload)
        with patch('subprocess.Popen') as launched, contextlib.redirect_stdout(io.StringIO()) as out, self.assertRaises(SystemExit):
            exec(target.WORKER_BODY, namespace)
        if 'lock' in namespace: os.close(namespace['lock'])
        launched.assert_not_called(); self.assertFalse(json.loads(out.getvalue())['passed'])
        self.assertFalse((self.area / 'worker.json').exists())

    def test_new_job_chunks_are_create_only_and_cannot_replace_existing_bytes(self):
        raw = b'{"kind":"unit-first"}\n'; meta = {'sha256': target.sha(raw), 'bytes': len(raw)}
        payload = self.payload | {'file': meta, 'action': 'chunk', 'index': 0, 'chunkSha256': target.sha(raw), 'data': base64.b64encode(raw).decode()}
        namespace = self.namespace(payload)
        with contextlib.redirect_stdout(io.StringIO()) as out: exec(target.INPUT_BODY, namespace)
        self.assertTrue(json.loads(out.getvalue())['passed'])
        changed = b'x' * len(raw); namespace['p'].update(chunkSha256=target.sha(changed), data=base64.b64encode(changed).decode())
        with contextlib.redirect_stdout(io.StringIO()) as out, self.assertRaises(SystemExit): exec(target.INPUT_BODY, namespace)
        self.assertFalse(json.loads(out.getvalue())['passed'])
        self.assertEqual(raw, (self.area / 'chunks' / meta['sha256'] / '0000.chunk').read_bytes())


if __name__ == '__main__': unittest.main()
