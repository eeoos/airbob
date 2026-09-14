"""Synthetic offline snapshot lineage and real native-validation orchestration.

No fixture is actual cloud success. Only MySQL/JVM/ES and AWS I/O are replaced;
source/target lineage, companion seals, S3 version/byte verification and full
prepared-row comparison use production validators.
"""
import base64
import contextlib
import copy
import datetime as dt
import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_search_snapshot as engine
import test_growth_b_search as search_tests
import test_growth_b_search_transport as transport_tests

search, snapshot, host, transport = engine.search, engine.snapshot, engine.snapshot_host, engine.transport


def stamp(offset):
    return (dt.datetime(2026, 9, 13, tzinfo=dt.timezone.utc) + dt.timedelta(seconds=offset)).isoformat()


def blob(value, key):
    raw = engine.encoded(value)
    return {'reference': {'key': key, 'versionId': 'unit-version-1', 'sha256': engine.sha(raw), 'bytes': len(raw)},
        'base64': base64.b64encode(raw).decode()}


def document(value):
    return json.loads(base64.b64decode(value['base64']))


def fixture():
    """Actual producer-shaped public-only documents, deliberately synthetic."""
    dataset, run, source_run, op = engine.DATASET, 'lab-snapshot-target', 'lab-snapshot-source', 'prepare-target-01'
    original = {'identifier': 'airbob-' + source_run, 'resourceId': 'db-' + 'A' * 24,
        'endpoint': 'source.abcdefghijk.ap-northeast-2.rds.amazonaws.com', 'serverUuid': str(uuid.UUID(int=1))}
    target = {'identifier': 'airbob-' + run, 'resourceId': 'db-' + 'B' * 24,
        'endpoint': 'target.abcdefghijk.ap-northeast-2.rds.amazonaws.com', 'serverUuid': str(uuid.UUID(int=2))}
    app = {'mainCommit': '8' * 40, 'image': engine.ACCOUNT + '.dkr.ecr.' + engine.REGION + '.amazonaws.com/airbob-repo@sha256:' + '9' * 64,
        'appJarSha256': '3' * 64, 'migrationFilesSha256': '6' * 64}
    base = search_tests.base_fingerprint()
    prepared = copy.deepcopy(base); prepared['tables']['member']['rowsSha256'] = '4' * 64
    prepared['tables']['accommodation_inventory_day'].update(rows=2, rowsSha256='5' * 64)
    target_fp = copy.deepcopy(prepared); target_fp['tables']['member']['rowsSha256'] = '6' * 64
    target_fp['tables']['accommodation_inventory_day'].update(rows=3, rowsSha256='7' * 64)
    def sql_ref(name, checksum, size=10):
        return {'key': 'datasets/' + dataset + '/' + name, 'versionId': 'unit-sql', 'sha256': checksum, 'bytes': size}
    dump_sha = dataset.removeprefix('global-growth-b-') + 'a' * 48
    env = {'datasetId': dataset, 'mysql': snapshot.MYSQL | {'schema': 'airbobdb'}, 'appJarSha256': app['appJarSha256'],
        'finalScaleSelected': True, 'awsExecutionAllowed': True, 'consumerManifestSha256': '1' * 64,
        'checksumsSha256': '2' * 64, 'publicationReceiptSha256': engine.sha(b'{}\n'), 'storage': {'publishedListings': 1},
        'objects': {'airbob-growth.sql.gz': sql_ref('airbob-growth.sql.gz', dump_sha),
            'migration-files.json': sql_ref('migration-files.json', app['migrationFilesSha256']),
            'before-fingerprint.json': sql_ref('before-fingerprint.json', engine.sha(engine.encoded(base)), len(engine.encoded(base)))}}
    envelope = blob(env, f'datasets/{dataset}-aws-preparation/files/envelope.json')
    prep = {'passed': True, 'readinessVerified': True, 'accountLogins': {'passed': True},
        'currentInventory': {'everyHorizonContiguous': True}, 'ownerSha256BeforeAndAfter': '7' * 64,
        'applicationLeftRunning': False}
    raw = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
        'operation': 'replace-database', 'executionScope': 'final-b-rds', 'toolIdentity': snapshot.restore.tool_identity(),
        'targetIdentity': original, 'datasetId': dataset, 'envelopeSha256': envelope['reference']['sha256'],
        'account': engine.ACCOUNT, 'region': engine.REGION, 'mysqlVersion': '8.4.11', 'flywayVersion': 28,
        'finalScaleSelected': True, 'allRowsAndDdlEqual': True, 'awsWritesExecuted': True,
        'previousBusinessSchemaAbsent': True, 'maximumSimultaneousBusinessDatabases': 1,
        'beforeDatabase': {'serverUuid': original['serverUuid'], 'version': '8.4.11', 'tlsCipher': 'TLS_TEST'},
        'sealedFingerprint': base, 'restoredFingerprintSha256': engine.sha(engine.encoded(base)),
        'appJarSha256': app['appJarSha256'], 'migrationFilesSha256': app['migrationFilesSha256'],
        'smallRdsPrerequisite': {'sameToolApplicationAndV28Verified': True}, 'preparation': copy.deepcopy(prep)}
    phases = ('SQL_IMPORT_STARTED', 'SQL_IMPORT_COMPLETED', 'FULL_VALIDATION_STARTED', 'SEALED_DATABASE_VERIFIED',
        'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED')
    names = ('sqlImportStartedAt', 'sqlImportCompletedAt', 'fullValidationStartedAt', 'fullValidationCompletedAt',
        'preparationStartedAt', 'preparationCompletedAt')
    raw.update({name: stamp(i + 2) for i, name in enumerate(names)})
    raw['events'] = [{'state': state, 'at': stamp(i + 2)} for i, state in enumerate(phases)]
    documents = {}
    def add(name, value, key):
        documents[name] = blob(value, key)
        return copy.deepcopy(documents[name]['reference'])
    source_prefix = f'data-bootstrap/{source_run}/r4/'
    raw_ref = add('sourceRestoreReceipt', raw, source_prefix + 'restore-receipt.json')
    prepared_ref = add('sourcePreparedFingerprint', prepared, source_prefix + 'prepared-fingerprint.json')
    reset = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-service-reset-verification', 'state': 'SERVICE_VERIFIED_AND_RESET',
        'mysql': snapshot.MYSQL, 'datasetId': dataset, 'targetIdentity': original, 'application': app,
        'restoreReceiptSha256': raw_ref['sha256'], 'preparedFingerprintSha256': prepared_ref['sha256'],
        'writersStopped': True, 'cdcStopped': True, 'sourceOriginalsUnchanged': True, 'completedAt': stamp(10),
        'service': dict.fromkeys(('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed', 'globalSearchPassed',
            'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed'), True) | {'representativeAccounts': 3},
        'reset': {'passed': True, 'testMutationRemoved': True, 'unchangedDomainAndDdl': True,
            'remainingOutboxRows': 0, 'ownerSha256BeforeAndAfter': '7' * 64}}
    reset_ref = add('sourceServiceResetReceipt', reset, source_prefix + 'service-verified-and-reset.json')
    sid = 'airbob-dataset-b-unit-snapshot'
    core = {'schemaVersion': 1, 'kind': snapshot.KIND, 'account': engine.ACCOUNT, 'region': engine.REGION,
        'mysql': snapshot.MYSQL, 'datasetId': dataset, 'source': original, 'application': app,
        'toolIdentity': snapshot.tool_identity(), 'snapshotIdentifier': sid,
        'publication': {'envelopeSha256': envelope['reference']['sha256'], 'publicationReceiptSha256': env['publicationReceiptSha256'],
            'consumerManifestSha256': env['consumerManifestSha256'], 'checksumsSha256': env['checksumsSha256'], 'objects': env['objects']},
        'evidence': {'restoreReceiptSha256': raw_ref['sha256'], 'preparedFingerprintSha256': prepared_ref['sha256'],
            'serviceResetReceiptSha256': reset_ref['sha256'], 'privateHandoffSha256': 'f' * 64},
        'sealedFingerprint': base, 'preparedFingerprint': prepared,
        'preparedFingerprintCanonicalSha256': snapshot.canonical_sha(prepared), 'ownerSha256': '7' * 64}
    storage = {'identifier': original['identifier'], 'resourceId': original['resourceId'],
        'kmsKeyArn': f'arn:aws:kms:{engine.REGION}:{engine.ACCOUNT}:key/{uuid.UUID(int=3)}',
        'allocatedStorageGiB': 100, 'storageType': 'gp3', 'instanceCreateTime': stamp(1)}
    metadata = {'identifier': sid, 'arn': f'arn:aws:rds:{engine.REGION}:{engine.ACCOUNT}:snapshot:{sid}',
        'state': 'available', 'engineVersion': '8.4.11', 'encrypted': True, 'kmsKeyArn': storage['kmsKeyArn'],
        'allocatedStorageGiB': 100, 'storageType': 'gp3', 'sourceRdsResourceId': original['resourceId'],
        'snapshotCreateTime': stamp(21), 'tags': snapshot.snapshot_tags(core)}
    provenance = {'schemaVersion': 1, 'kind': snapshot.KIND, 'state': snapshot.AVAILABLE, 'contract': core,
        'contractSha256': snapshot.canonical_sha(core), 'storage': storage, 'snapshot': metadata,
        'sourceFreeze': {'heldUntilSnapshotAvailable': True}, 'allRowsAndDdlBeforeAndAfterSnapshotEqual': True,
        'sourceDeletionAllowed': False, 'actualRestoreVerified': False,
        'timings': {'createRequestedAt': stamp(20), 'snapshotAvailableVerifiedAt': stamp(30), 'requestToVerifiedAvailableSeconds': 10}}
    p_ref = add('provenance', provenance, f'datasets/{dataset}-aws-snapshots/{sid}/placeholder')
    p_ref['key'] = f'datasets/{dataset}-aws-snapshots/{sid}/provenance-{p_ref["sha256"]}.json'; documents['provenance']['reference'] = p_ref
    lease = {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
        'owner': 'unit-target-owner', 'runId': run, 'command': 'up', 'fencingToken': 75}
    admission = {'schemaVersion': 1, 'kind': snapshot.KIND + '-restore-admission', 'state': 'SOURCE_ABSENT_TARGET_ABSENT',
        'source': original, 'targetIdentifier': target['identifier'], 'snapshotArn': metadata['arn'], 'lease': lease,
        'provenanceSha256': p_ref['sha256'], 'maximumSimultaneousBusinessDatabases': 1, 'restoreExecuted': False, 'recordedAt': stamp(40)}
    a_ref = add('admission', admission, f'data-bootstrap/{run}/admission/restore-admission.json')
    event = {'eventName': 'RestoreDBInstanceFromDBSnapshot', 'eventSource': 'rds.amazonaws.com', 'awsRegion': engine.REGION,
        'recipientAccountId': engine.ACCOUNT, 'eventID': str(uuid.UUID(int=4)), 'eventTime': stamp(50),
        'requestParameters': {'dBInstanceIdentifier': target['identifier'], 'dBSnapshotIdentifier': sid}}
    e_ref = add('restoreEvent', event, f'data-bootstrap/{run}/restore-event.json')
    prefix, out = host.prefix(dataset, run, op), host.evidence_prefix(dataset, run, op)
    selected = {'schemaVersion': 1, 'kind': host.KIND, 'operation': 'prepare', 'operationId': op, 'datasetId': dataset,
        'runId': run, 'account': engine.ACCOUNT, 'region': engine.REGION, 'mysql': snapshot.MYSQL,
        'snapshotIdentifier': sid, 'application': {k: app[k] for k in ('mainCommit', 'image')},
        'awsPreparation': {'key': f'datasets/{dataset}-aws-preparation/aws-preparation-' + 'a' * 64 + '.json',
            'versionId': 'unit-wrapper', 'sha256': 'a' * 64, 'bytes': 100},
        'consumerTools': {'key': prefix + 'files/' + 'b' * 64 + '-consumer-tools.tar.gz', 'versionId': 'unit-tools', 'sha256': 'b' * 64, 'bytes': 100},
        'toolSources': host.sources(), 'operationTimeoutSeconds': 600,
        'evidence': {'provenance': p_ref, 'admission': a_ref, 'restoreEvent': e_ref}}
    selected_ref = add('targetManifest', selected, prefix + 'placeholder')
    selected_ref['key'] = prefix + 'manifest-' + selected_ref['sha256'] + '.json'; documents['targetManifest']['reference'] = selected_ref
    fp_ref = add('targetPreparedFingerprint', target_fp, out + 'prepared-fingerprint.json')
    actual = {'eventId': event['eventID'], 'eventSha256': snapshot.canonical_sha(event), 'requestedAt': event['eventTime'],
        'instanceCreateTime': stamp(60), 'evidenceSource': 'controller-pinned-cloudtrail-event'}
    binds = {'targetIdentity': target, 'provenanceSha256': p_ref['sha256'], 'admissionSha256': a_ref['sha256'],
        'restoreEventSha256': e_ref['sha256'], 'toolIdentity': snapshot.tool_identity(), 'datasetId': dataset,
        'envelopeSha256': envelope['reference']['sha256'], 'credentialBindingSha256': 'c' * 64, 'configSha256': 'd' * 64}
    preflight = dict(binds, schemaVersion=1, kind=snapshot.KIND + '-preflight', state='RESTORED_TARGET_READ_ONLY_PREFLIGHT',
        operation='prepare', currentFingerprintCanonicalSha256=core['preparedFingerprintCanonicalSha256'],
        actualRestore=actual, resumeReceiptSha256=None, recordedAt=stamp(69))
    pf_ref = add('targetPreflight', preflight, out + 'preflight.json')
    target_prep = copy.deepcopy(prep); target_prep['preparedFingerprintSha256'] = fp_ref['sha256']
    proof = dict(binds, schemaVersion=1, kind=snapshot.KIND + '-operation', state=snapshot.PREPARED, operation='prepare',
        snapshotWholeBaselineVerified=True, sqlImportSkipped=True, actualRestoreVerified=True, databaseRecreatedInThisRun=False,
        applicationLeftRunning=False, deploymentReady=False, snapshotBaselineCanonicalSha256=core['preparedFingerprintCanonicalSha256'],
        baselineOwnerSha256=core['ownerSha256'], preparedFingerprintSha256=fp_ref['sha256'],
        preparedFingerprintCanonicalSha256=snapshot.canonical_sha(target_fp), preparation=target_prep, actualRestore=actual,
        fullValidationStartedAt=stamp(70), fullValidationCompletedAt=stamp(80), preparationStartedAt=stamp(81), preparationCompletedAt=stamp(90),
        events=[{'state': s, 'at': stamp(t)} for s, t in [('SNAPSHOT_WHOLE_BASELINE_VERIFIED', 80), ('PREPARATION_STARTED', 81), (snapshot.PREPARED, 90)]])
    op_ref = add('targetOperation', proof, out + 'snapshot-operation.json')
    common = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
        'sourceMode': 'verified-global-b-snapshot', 'runId': run, 'datasetId': dataset, 'serverUuid': target['serverUuid'],
        'rdsResourceId': target['resourceId'], 'rdsEngineVersion': '8.4.11', 'flywayVersion': 28, 'preparation': target_prep,
        'restoreReceiptSha256': op_ref['sha256'], 'snapshotProvenanceSha256': p_ref['sha256'], 'snapshotRestoreEvidence': actual,
        'applicationLeftRunning': False, 'deploymentReady': False, 'completedAt': stamp(91)}
    common_ref = add('targetPreparation', common, out + 'data-only-preparation.json')
    receipt = {'schemaVersion': 1, 'kind': host.KIND + '-receipt', 'state': 'HOST_OPERATION_COMPLETE', 'operation': 'prepare',
        'runId': run, 'datasetId': dataset, 'operationId': op, 'toolSources': selected['toolSources'], 'manifestSha256': selected_ref['sha256'],
        'targetIdentity': target, 'hostInstanceId': 'i-' + '4' * 17, 'restoreConfigSha256': 'e' * 64, 'derivedRestoreConfigSha256': 'f' * 64,
        'preflightSha256': pf_ref['sha256'], 'objects': {'preflight.json': pf_ref, 'snapshot-operation.json': op_ref,
            'data-only-preparation.json': common_ref, 'prepared-fingerprint.json': fp_ref},
        'completedAt': stamp(92), 'persistentAwsResourcesMutatedByHost': False, 'sqlImportExecuted': False,
        'applicationLeftRunning': False, 'deploymentReady': False}
    host_ref = add('targetHostReceipt', receipt, out + 'host-receipt.json')
    binding = {'schemaVersion': 1, 'kind': engine.BINDING_KIND, 'datasetId': dataset, 'runId': run,
        'targetRds': target | {'createdAt': stamp(60)}, 'preparationHostInstanceId': receipt['hostInstanceId'], 'application': app,
        'envelopeSha256': envelope['reference']['sha256'], 'consumerManifestSha256': env['consumerManifestSha256'],
        'checksumsSha256': env['checksumsSha256'], 'targetPreparation': {'manifest': selected_ref, 'hostReceipt': host_ref},
        'sourceEvidence': {'restoreReceipt': raw_ref, 'preparedFingerprint': prepared_ref, 'serviceResetReceipt': reset_ref}}
    return binding, documents, envelope, base


class LineageTest(unittest.TestCase):
    def setUp(self):
        self.binding, self.docs, self.envelope, self.base = fixture()

    def validate(self):
        return engine.validate_lineage(self.binding, self.docs, self.envelope, self.base)

    def changed(self, name, mutate):
        value = document(self.docs[name]); mutate(value)
        self.docs[name] = blob(value, self.docs[name]['reference']['key'])

    def test_valid_target_has_distinct_honest_baseline_family(self):
        proof = self.validate()
        self.assertEqual(engine.LINEAGE_STATE, proof['state'])
        self.assertNotEqual(proof['ancestorSource']['serverUuid'], proof['target']['serverUuid'])
        self.assertFalse(proof['rawBaselineObservedOnTarget'])
        self.assertFalse(proof['currentFullRowsRevalidated'])
        self.assertNotIn('BASELINE_VERIFIED_BEFORE_PREPARATION', json.dumps(proof))
        self.assertNotEqual(document(self.docs['targetPreparedFingerprint']), self.base)

    def test_closed_inventory_and_bounded_bytes(self):
        for action in ('missing', 'extra', 'size', 'sha', 'version'):
            with self.subTest(action=action):
                self.binding, self.docs, self.envelope, self.base = fixture()
                if action == 'missing': del self.docs['restoreEvent']
                if action == 'extra': self.docs['privateAccounts'] = self.docs['sourceRestoreReceipt']
                if action == 'size': self.docs['provenance']['reference']['bytes'] += 1
                if action == 'sha': self.docs['provenance']['reference']['sha256'] = '0' * 64
                if action == 'version': self.docs['targetManifest']['reference']['versionId'] = 'null'
                with self.assertRaises(engine.Rejected): self.validate()

    def test_binding_rejects_source_uuid_as_target(self):
        self.binding['targetRds']['serverUuid'] = document(self.docs['provenance'])['contract']['source']['serverUuid']
        with self.assertRaises(engine.Rejected): self.validate()

    def test_missing_actual_restore_and_false_success_are_rejected(self):
        for name, field, value in [('targetOperation', 'actualRestoreVerified', False), ('targetOperation', 'sqlImportSkipped', False),
                                  ('targetOperation', 'state', 'DATABASE_INVENTORY_LOGIN_VERIFIED'),
                                  ('sourceServiceResetReceipt', 'state', 'CONNECTOR_RUNNING')]:
            with self.subTest(name=name, field=field):
                self.binding, self.docs, self.envelope, self.base = fixture()
                self.changed(name, lambda v: v.update({field: value}))
                with self.assertRaises(engine.Rejected): self.validate()

    def test_source_fingerprint_and_target_fingerprint_mismatch_rejected(self):
        for name in ('sourcePreparedFingerprint', 'targetPreparedFingerprint'):
            with self.subTest(name=name):
                self.binding, self.docs, self.envelope, self.base = fixture()
                self.changed(name, lambda v: v['tables']['accommodation'].update(rowsSha256='f' * 64))
                with self.assertRaises(engine.Rejected): self.validate()

    def test_semantic_source_checks_even_when_content_refs_are_rebound(self):
        # Call the same pure subvalidator after rebinding all relevant source
        # anchors, so this tests semantic admission rather than only stale SHA.
        values = {k: document(v) for k, v in self.docs.items()}
        refs = {k: copy.deepcopy(v['reference']) for k, v in self.docs.items()}
        core = values['provenance']['contract']; env = document(self.envelope)
        def validate(): engine._source_proof(self.binding, values, refs, env, self.base, core)
        validate()
        for name, mutate in [('uuid', lambda: values['sourceRestoreReceipt']['beforeDatabase'].update(serverUuid=self.binding['targetRds']['serverUuid'])),
                             ('r4', lambda: values['sourceServiceResetReceipt']['service'].update(domainApiMutationCdcEsPassed=False)),
                             ('domain', lambda: values['sourcePreparedFingerprint']['tables']['reservation'].update(rows=99)),
                             ('tool', lambda: values['sourceRestoreReceipt']['toolIdentity'].update({'growth_b_aws_restore.py': 'f' * 64})),
                             ('raw', lambda: values['sourceRestoreReceipt'].update(sqlImportSkipped=True))]:
            with self.subTest(name=name):
                originals = copy.deepcopy(values); mutate()
                with self.assertRaises((engine.Rejected, ValueError)): validate()
                values.clear(); values.update(originals); core = values['provenance']['contract']

    def test_semantic_target_checks_independent_of_ref_mismatch(self):
        values = {k: document(v) for k, v in self.docs.items()}; refs = {k: v['reference'] for k, v in self.docs.items()}
        core = values['provenance']['contract']
        def validate(): engine._target_proof(self.binding, values, refs, core)
        validate()
        actions = [('actual', lambda: values['targetOperation'].update(actualRestoreVerified=False)),
            ('baseline', lambda: values['targetOperation'].update(snapshotBaselineCanonicalSha256='f' * 64)),
            ('cloudtrail', lambda: values['restoreEvent'].update(recipientAccountId='000000000000')),
            ('created', lambda: values['targetOperation']['actualRestore'].update(instanceCreateTime=stamp(90))),
            ('admission', lambda: values['admission'].update(maximumSimultaneousBusinessDatabases=2)),
            ('outbox', lambda: values['targetPreparedFingerprint']['tables']['outbox'].update(rows=1)),
            ('chronology', lambda: values['targetOperation'].update(fullValidationStartedAt=stamp(10))),
            ('undeclared-resume', lambda: values['targetPreflight'].update(resumeReceiptSha256='f' * 64)),
            ('host', lambda: values['targetHostReceipt'].update(hostInstanceId='i-' + 'f' * 17))]
        for name, mutate in actions:
            with self.subTest(name=name):
                originals = copy.deepcopy(values); mutate()
                with self.assertRaises((engine.Rejected, ValueError)): validate()
                values.clear(); values.update(originals); core = values['provenance']['contract']

    def test_source_refs_versions_and_provenance_cannot_be_interchanged(self):
        self.binding['sourceEvidence']['restoreReceipt']['versionId'] = 'foreign'
        with self.assertRaises(engine.Rejected): self.validate()

    def test_secret_duplicate_or_nonfinite_document_has_closed_error(self):
        for raw in (b'{"password":"SECRET_MUST_NOT_LEAK"}', b'{"x":1,"x":2}', b'{"x":NaN}'):
            ref = copy.deepcopy(self.docs['admission']['reference']); ref.update(sha256=engine.sha(raw), bytes=len(raw))
            self.docs['admission'] = {'reference': ref, 'base64': base64.b64encode(raw).decode()}
            with self.assertRaises(engine.Rejected) as caught: self.validate()
            self.assertNotIn('SECRET_MUST_NOT_LEAK', str(caught.exception))

    def test_frozen_target_baseline_still_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            search.write(root / 'mysql-baseline-fingerprint.json', document(self.docs['targetPreparedFingerprint']))
            anchors = {'datasetId': engine.DATASET, 'consumerManifestSha256': self.binding['consumerManifestSha256'],
                'checksSha256': self.binding['checksumsSha256'], 'appJarSha256': self.binding['application']['appJarSha256']}
            identity = {'version': '8.4.11', 'schema': 'airbobdb', 'serverUuid': self.binding['targetRds']['serverUuid'], 'publishedDocuments': 1}
            search.write(root / 'baseline-receipt.json', dict(anchors, schemaVersion=1, state=engine.LINEAGE_STATE,
                verifiedAllColumnsAndDdl=True, mysql=identity, fingerprint=search.file_binding(root / 'mysql-baseline-fingerprint.json')))
            source = MagicMock(); source.config = anchors; source.manifest = anchors; source.base = self.base
            with self.assertRaises(ValueError): search.verify_baseline(source, root, identity)


def native_fixture(root, binding, documents, envelope, base):
    """Build a sealed synthetic native companion and versioned in-memory S3."""
    root = Path(root); companion = root / 'companion'; companion.mkdir()
    release = root / 'release'; release.mkdir()
    (release / 'before-fingerprint.json').write_bytes(engine.encoded(base))
    env = document(envelope); dataset = binding['datasetId']; snapshot_release = dataset + '-search-offline'
    anchors = {'datasetId': dataset, 'consumerManifestSha256': binding['consumerManifestSha256'],
        'checksSha256': binding['checksumsSha256'], 'appJarSha256': binding['application']['appJarSha256']}
    docs_path = root / 'docs.jsonl'; docs_path.write_text(json.dumps(search_tests.doc(1)) + '\n')
    mappings = {'properties': {'accommodationId': {'type': 'long'}}}
    full = search.file_fingerprint(docs_path) | {'mappingSha256': search.digest(mappings),
        'indexSemanticsSha256': search.digest(search.index_semantics({}))}
    native_bytes = b'unit native bytes'; native = {'type': 'fs', 'entries': [{'key': 'index-0', 'bytes': len(native_bytes), 'sha256': engine.sha(native_bytes)}]}
    owners = {'rows': 1, 'rowsSha256': 'a' * 64, 'invalidFreeRows': 0}
    drift = {'mysqlUuidUnchanged': True, 'completeRowsAndDdlUnchanged': True, 'allCurrentDocumentFieldsUnchanged': True}
    source_proof = {'schemaVersion': 1, **anchors, 'state': 'PINNED_MYSQL_SOURCE_VERIFIED',
        'baselineVerifiedBeforePreparation': True, 'everyElasticsearchSourceFieldCompared': True,
        'baseDumpSha256': env['objects']['airbob-growth.sql.gz']['sha256'], 'sourceDrift': drift,
        'projection': search.document_fingerprint(full),
        'mysql': {'version': '8.4.11', 'publishedDocuments': 1, 'serverUuid': str(uuid.UUID(int=9))},
        'baselineFingerprintSha256': search.digest(base), 'preparedFingerprintSha256': search.digest(base),
        'preparedAllowedChanges': search.verify_prepared(base, base, owners), 'ownedInventory': owners}
    reference = {'schemaVersion': 1, **anchors, 'snapshotRelease': snapshot_release, 'snapshot': snapshot_release,
        'snapshotUuid': 'native-uuid', 'snapshotMetadataVersion': search.SNAPSHOT_METADATA_VERSION,
        'snapshotMetadataVersionId': search.SNAPSHOT_METADATA_VERSION_ID, 'snapshotIndex': 'accommodations-voriginal',
        'logicalAlias': search.ALIAS, 'fingerprint': full, 'elasticsearch': search_tests.runtime_identity(),
        'repository': {'type': 'fs', 'layout': 'native-elasticsearch-repository'}, 'nativeInventorySha256': search.digest(native)}
    producer = {'schemaVersion': 1, **anchors, 'snapshotRelease': snapshot_release, 'snapshotUuid': 'native-uuid',
        'state': 'NATIVE_SNAPSHOT_PRODUCED_AND_RESTORED', 'sourceFingerprint': full, 'restoredFingerprint': full,
        'storage': {'measuredSourcePlusTemporaryRestoreStoreBytes': 4096}, 'temporaryRestoreDeleted': True,
        'repositoryRegistrationRemoved': True, 'repositoryUnchangedAfterReadOnlyRestore': True}
    payloads = {'source-proof.json': source_proof, 'snapshot-reference.json': reference, 'snapshot-producer-receipt.json': producer,
        'native-inventory.json': native, 'mysql-baseline-fingerprint.json': base, 'mysql-prepared-fingerprint.json': base}
    for name, value in payloads.items(): search.write(companion / name, value)
    search.write(companion / 'snapshot-seal.json', {'schemaVersion': 1, **anchors, 'snapshotRelease': snapshot_release,
        'state': 'SEALED_AFTER_NATIVE_ROUND_TRIP', 'artifacts': {n: search.file_binding(companion / n) for n in payloads}})
    manifest = {'schemaVersion': 1, **anchors, 'snapshotRelease': snapshot_release, 'snapshotUuid': 'native-uuid',
        'kind': 'airbob-global-growth-b-native-search-companion', 'fullDocumentFingerprint': full,
        'artifacts': {n: search.file_binding(companion / n) for n in search.NAMES - {'manifest.json'}}}
    search.write(companion / 'manifest.json', manifest)
    descriptor = search.descriptor_for(companion, manifest); search.validate_companion(companion, descriptor)
    prefix = transport.prefix_for(dataset, snapshot_release); aws = transport_tests.FakeAws(); objects = {}
    def seed(name, raw):
        selected = aws.seed(prefix + name, raw)
        objects[name] = {'key': prefix + name, 'versionId': selected['versionId'], 'bytes': len(raw), 'sha256': engine.sha(raw)}
    for name in search.NAMES: seed('companion/' + name, (companion / name).read_bytes())
    seed(transport.DESCRIPTOR, engine.encoded(descriptor)); seed(transport.SQL_RECEIPT, b'{}\n'); seed('native/index-0', native_bytes)
    sql = {}
    for key, name, checksum in [('consumerManifest', 'consumer-manifest.json', binding['consumerManifestSha256']),
                               ('checksums', 'SHA256SUMS.json', binding['checksumsSha256']),
                               ('dump', 'airbob-growth.sql.gz', source_proof['baseDumpSha256'])]:
        ref = {'key': f'datasets/{dataset}/{name}', 'versionId': 'version-1', 'sha256': checksum, 'bytes': 10}
        aws.seed(ref['key'], b'unit-bytes'); sql[key] = ref
    marker = {'schemaVersion': 1, 'kind': transport.KIND, 'datasetId': dataset, 'snapshotRelease': snapshot_release,
        'snapshotUuid': reference['snapshotUuid'], 'bucket': transport.BUCKET, 'region': transport.REGION,
        'sql': {'datasetId': dataset, 'publicationReceiptSha256': env['publicationReceiptSha256'], **sql},
        'source': {'descriptorSha256': objects[transport.DESCRIPTOR]['sha256'], 'companionManifestSha256': descriptor['objects']['manifest.json']['sha256'],
            'consumerManifestSha256': binding['consumerManifestSha256'], 'checksSha256': binding['checksumsSha256'],
            'appJarSha256': binding['application']['appJarSha256'], 'nativeInventorySha256': reference['nativeInventorySha256'],
            'nativeInventoryFileSha256': descriptor['objects']['native-inventory.json']['sha256']},
        'repository': {'type': 's3', 'bucket': transport.BUCKET, 'basePath': prefix + 'native'},
        'objects': objects, 'totalBytes': sum(item['bytes'] for item in objects.values())}
    marker_path = root / 'transport.json'; marker_raw = transport.manifest_bytes(marker); marker_path.write_bytes(marker_raw)
    aws.seed(prefix + transport.MARKER, marker_raw)
    transport.validate_published_transport(marker_path, engine.sha(marker_raw), companion, descriptor)
    config = dict(anchors, releaseDirectory=str(release), targetIndex='accommodations-vnew', snapshotTimeoutSeconds=60,
        mysql={'expectedServerUuid': binding['targetRds']['serverUuid']}, elasticsearch={},
        repository={'type': 's3', 'name': 'target-only', 'settings': {'bucket': transport.BUCKET, 'base_path': prefix + 'native', 'region': transport.REGION},
            'aws': {'region': transport.REGION}, 'transport': {'manifest': str(marker_path), 'sha256': engine.sha(marker_raw)}})
    return config, companion, descriptor, aws, reference, manifest


class TargetSource:
    def __init__(self, config, base, prepared, manifest, dump):
        self.config = config; self.base = base; self.prepared = prepared; self.manifest = manifest
        self.checks = {'airbob-growth.sql.gz': dump}; self.calls = []
        self.mapping = {'mappings': {'properties': {'accommodationId': {'type': 'long'}}}, 'settings': {}}
        self.fingerprint_drift = False; self.document_drift = False; self.bad_owner = False; self.bad_initial = False
        self.fail_fence_release = False
    def identity(self):
        return {'version': '8.4.11', 'schema': 'airbobdb', 'serverUuid': self.config['mysql']['expectedServerUuid'], 'publishedDocuments': 1}
    @contextlib.contextmanager
    def fence(self):
        self.calls.append('fence-enter')
        try: yield self.identity()
        finally:
            self.calls.append('fence-exit')
            if self.fail_fence_release: raise ValueError('SECRET_FENCE_DETAIL')
    def probe(self, mode):
        self.calls.append(mode)
        if mode == 'owned': return {'rows': 1, 'rowsSha256': 'f' * 64 if self.bad_owner else 'a' * 64, 'invalidFreeRows': 0}
        if mode == 'fingerprint':
            value = copy.deepcopy(self.prepared)
            if self.bad_initial or (self.fingerprint_drift and self.calls.count(mode) > 1): value['tables']['reservation']['rows'] += 1
            return value
        raise AssertionError(mode)
    def documents(self, path):
        self.calls.append('documents'); value = search_tests.doc(1)
        if self.document_drift and self.calls.count('documents') > 1: value['currency'] = 'USD'
        Path(path).write_text(json.dumps(value) + '\n')
        return search.file_fingerprint(path)


class TargetEs(search.Elasticsearch):
    def __init__(self, reference):
        self.reference = reference; self.config = {}; self.calls = []; self.alias_value = 'accommodations-vprevious'
        self.registered = False; self.restored = False; self.reject_restore = False; self.reject_alias = False
        self.bad_document = False; self.fail_cleanup = False; self.write_block = True
    def identity(self): return search_tests.runtime_identity() | {'clusterUuid': 'unit-cluster'}
    def alias(self, optional=False): return self.alias_value
    def optional(self, path):
        if path == '/accommodations-vnew': return {} if self.restored else None
        if path == '/_snapshot/target-only': return {} if self.registered else None
        raise AssertionError(path)
    def stream(self, index):
        value = search_tests.doc(1)
        if self.bad_document: value['currency'] = 'USD'
        yield {'id': value['id'], 'source': value}
    def api(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, copy.deepcopy(body)))
        if path == '/_nodes/stats/fs': return {'nodes': {'one': {'fs': {'total': {'available_in_bytes': 10 * 1024**3}}}}}
        if path == '/_snapshot/target-only':
            if method == 'PUT':
                assert body['settings']['readonly'] is True
                self.registered = True; return {'acknowledged': True}
            if method == 'GET': return {'target-only': {'settings': {'readonly': True}}}
            if method == 'DELETE':
                if self.fail_cleanup: raise ValueError('SECRET_MUST_NOT_LEAK')
                self.registered = False; return {'acknowledged': True}
        if path.endswith('/_restore'):
            assert body['include_aliases'] is False and body['include_global_state'] is False and body['index_settings']['index.blocks.write'] is True
            self.restored = not self.reject_restore; return {'accepted': not self.reject_restore}
        if path.startswith('/_snapshot/target-only/'):
            return {'snapshots': [{'snapshot': self.reference['snapshot'], 'state': 'SUCCESS', 'indices': [self.reference['snapshotIndex']],
                'failures': [], 'shards': {'failed': 0, 'successful': 1, 'total': 1}, 'include_global_state': False, 'feature_states': [],
                'uuid': self.reference['snapshotUuid'], 'version': search.SNAPSHOT_METADATA_VERSION, 'version_id': search.SNAPSHOT_METADATA_VERSION_ID}]}
        if path.startswith('/_cluster/health/'): return search_tests.restore_health()
        if path.endswith('/_mapping'): return {'accommodations-vnew': {'mappings': {'properties': {'accommodationId': {'type': 'long'}}}}}
        if path.endswith('/_settings'):
            if method == 'PUT': self.write_block = False; return {'acknowledged': True}
            return {'accommodations-vnew': {'settings': {'index': {'blocks': {'write': self.write_block}}}}}
        if path.endswith('/_alias'): return {'accommodations-vnew': {'aliases': {}}}
        if path == '/_aliases':
            if self.reject_alias: return {'acknowledged': False}
            self.alias_value = body['actions'][-1]['add']['index']; return {'acknowledged': True}
        raise AssertionError((method, path))


class RestoreTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup); self.root = Path(self.temporary.name)
        self.binding, self.docs, self.envelope, self.base = fixture()
        self.config, self.companion, self.descriptor, self.aws, reference, manifest = native_fixture(
            self.root, self.binding, self.docs, self.envelope, self.base)
        self.source = TargetSource(self.config, self.base, document(self.docs['targetPreparedFingerprint']), manifest,
            document(self.envelope)['objects']['airbob-growth.sql.gz']['sha256'])
        self.es = TargetEs(reference); self.guard_calls = 0
    def guard(self, force=False):
        self.assertTrue(force); self.guard_calls += 1
    def execute(self, *, activate=True, guard=True):
        def source_factory(config, work, *, runtime_output):
            self.source.host_runtime_path = runtime_output; Path(runtime_output).write_text('{}')
            return self.source
        with patch.object(search, 'SourceAdapter', side_effect=source_factory), patch.object(search, 'Elasticsearch', return_value=self.es), \
             patch.object(search, 'aws_cli', return_value=self.aws.call), \
             patch.object(search, 'restore', side_effect=AssertionError('Frozen source restore must not be called')), \
             patch.object(search, 'verify_baseline', side_effect=AssertionError('No false target raw baseline')):
            return engine.restore(self.config, self.companion, self.descriptor, self.binding, self.docs, self.envelope,
                self.root / 'output', activate_alias=activate, guard=self.guard if guard else None)
    def test_full_native_restore_and_honest_receipt(self):
        receipt = self.execute()
        engine.validate_receipt(receipt, self.binding, self.docs, self.envelope, self.base)
        self.assertEqual('SEARCH_RESTORED_AND_ACTIVATED', receipt['state'])
        self.assertEqual('accommodations-vnew', self.es.alias_value)
        self.assertFalse(receipt['rawBaselineObservedOnTarget']); self.assertNotIn('baselineReceiptSha256', receipt)
        self.assertEqual(['fence-enter', 'fingerprint', 'owned', 'documents', 'documents', 'fingerprint', 'fence-exit'], self.source.calls)
        self.assertEqual(engine.sha((self.root / 'output/snapshot-target-baseline.json').read_bytes()), receipt['snapshotTargetBaselineSha256'])
        self.assertGreater(self.guard_calls, 8)
        self.assertTrue(any(op == 'get-object' for op, _ in self.aws.calls)); self.assertFalse(self.es.registered)
        self.assertFalse(any(op in {'put-object', 'create-multipart-upload'} for op, _ in self.aws.calls))
        self.assertNotIn('BASELINE_VERIFIED_BEFORE_PREPARATION', (self.root / 'output/snapshot-target-baseline.json').read_text())
    def test_verified_without_activation_keeps_previous_alias(self):
        receipt = self.execute(activate=False)
        self.assertEqual('SEARCH_VERIFIED_NOT_ACTIVATED', receipt['state']); self.assertEqual('accommodations-vprevious', self.es.alias_value)
        self.assertFalse(any(path == '/_aliases' for _, path, _ in self.es.calls))
    def test_no_guard_or_invalid_proof_has_no_aws_es_or_sql(self):
        with self.assertRaises(engine.Rejected): self.execute(guard=False)
        self.docs['provenance']['reference']['sha256'] = 'f' * 64
        with self.assertRaises(engine.Rejected): self.execute()
        self.assertEqual([], self.aws.calls); self.assertEqual([], self.es.calls); self.assertEqual([], self.source.calls)
    def assert_failed(self):
        with self.assertRaises(engine.Rejected): self.execute()
        self.assertFalse((self.root / 'output/search-restore-receipt.json').exists())
        result = json.loads((self.root / 'output/failure.json').read_text())
        self.assertEqual('FAILED_RESOURCES_RETAINED', result['state']); self.assertFalse(result['automaticRetry'])
        return result
    def test_native_bytes_corruption_prevents_restore(self):
        native = self.config['repository']['settings']['base_path'] + '/index-0'; self.aws.corrupt.add(native)
        self.assert_failed(); self.assertFalse(self.es.restored); self.assertEqual([], self.source.calls)
    def test_foreign_native_version_prevents_restore(self):
        native = self.config['repository']['settings']['base_path'] + '/index-0'; self.aws.seed(native, b'changed')
        self.assert_failed(); self.assertFalse(self.es.restored)
    def test_prepared_owner_mismatch_prevents_restore(self):
        self.source.bad_owner = True; self.assert_failed(); self.assertFalse(self.es.restored)
    def test_current_domain_mismatch_prevents_restore(self):
        self.source.bad_initial = True; self.assert_failed(); self.assertFalse(self.es.restored)
    def test_native_restore_failure_keeps_previous_alias(self):
        self.es.reject_restore = True; self.assert_failed(); self.assertEqual('accommodations-vprevious', self.es.alias_value)
        self.assertFalse(any(path == '/_aliases' for _, path, _ in self.es.calls))
    def test_full_document_mismatch_keeps_previous_alias(self):
        self.es.bad_document = True; self.assert_failed(); self.assertTrue(self.es.restored)
        self.assertEqual('accommodations-vprevious', self.es.alias_value); self.assertFalse(self.es.registered)
    def test_database_drift_keeps_previous_alias(self):
        self.source.fingerprint_drift = True; self.assert_failed(); self.assertEqual('accommodations-vprevious', self.es.alias_value)
    def test_current_document_drift_keeps_previous_alias(self):
        self.source.document_drift = True; self.assert_failed(); self.assertEqual('accommodations-vprevious', self.es.alias_value)
    def test_alias_failure_is_not_success_and_is_not_retried(self):
        self.es.reject_alias = True; failure = self.assert_failed()
        self.assertTrue(failure['aliasActivationAttempted']); self.assertEqual(1, sum(path == '/_aliases' for _, path, _ in self.es.calls))
    def test_primary_failure_survives_cleanup_failure_without_secret_message(self):
        self.es.bad_document = True; self.es.fail_cleanup = True; failure = self.assert_failed()
        self.assertTrue(failure['repositoryCleanupFailed']); self.assertTrue(failure['repositoryRegistrationMayRemain'])
        self.assertNotIn('SECRET_MUST_NOT_LEAK', json.dumps(failure))
    def test_guard_expiry_after_restore_blocks_alias_and_completion(self):
        def guard(force=False):
            if self.es.restored: raise engine.Rejected('ACK_EXPIRED')
        self.guard = guard; failure = self.assert_failed()
        self.assertFalse(failure['aliasActivationAttempted']); self.assertEqual('accommodations-vprevious', self.es.alias_value)
    def test_failed_fence_release_never_leaves_completion_receipt(self):
        self.source.fail_fence_release = True; failure = self.assert_failed()
        self.assertEqual('FENCE_RELEASE', failure['stage'])
        self.assertTrue(failure['aliasActivationAttempted'])
        self.assertNotIn('SECRET_FENCE_DETAIL', json.dumps(failure))
    def test_completion_rejects_raw_target_claim_or_foreign_ancestor(self):
        receipt = self.execute(); lineage = engine.validate_lineage(self.binding, self.docs, self.envelope, self.base)
        for key, value in [('rawBaselineObservedOnTarget', True), ('baselineVerifiedBeforePreparation', True), ('baselineReceiptSha256', '0' * 64)]:
            with self.subTest(key=key), self.assertRaises(engine.Rejected): engine.validate_native_receipt(receipt | {key: value}, lineage)
        modified = copy.deepcopy(receipt); modified['sourceBaseline']['ancestorSource']['serverUuid'] = self.binding['targetRds']['serverUuid']
        with self.assertRaises(engine.Rejected): engine.validate_native_receipt(modified, lineage)
    def test_completion_requires_whole_comparison_and_versioned_native_fields(self):
        receipt = self.execute(); lineage = engine.validate_lineage(self.binding, self.docs, self.envelope, self.base)
        for name, mutate in [('owners', lambda v: v['preparedAllowedChanges'].update(historicalInventoryAllRowsEqual=False)),
                            ('tables', lambda v: v['preparedAllowedChanges'].update(unchangedCompleteTables=[])),
                            ('count', lambda v: v['fullDocumentFingerprint'].update(documents=0)),
                            ('versions', lambda v: v['nativeTransport']['completionMarker'].update(versionId='null'))]:
            with self.subTest(name=name):
                changed = copy.deepcopy(receipt); mutate(changed)
                with self.assertRaises(ValueError): engine.validate_native_receipt(changed, lineage)
