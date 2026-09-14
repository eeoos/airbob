"""Explicit native-search consumer for an actually restored B snapshot target.

This is a new target evidence contract, not a compatibility baseline for
growth_b_search.restore().  The old search module and its raw-before-preparation
validator remain unchanged.  No raw baseline is claimed to have been observed
on the new UUID.  Inputs are bounded public exact-version bytes; the enclosing
host/controller still owns lease, process, AWS identity and private connections.
"""
from __future__ import annotations

import base64
import binascii
import copy
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile

import growth_b_contract as contract
import growth_b_search as search
import growth_b_search_transport as transport
import growth_b_snapshot as snapshot
import growth_b_snapshot_host as snapshot_host

ACCOUNT, REGION = snapshot.ACCOUNT, snapshot.REGION
DATASET = 'global-growth-b-b0fbda4d12511eeb'
BINDING_KIND = 'global-b-aws-snapshot-native-search-binding'
LINEAGE_KIND = 'global-growth-b-snapshot-target-search-lineage'
RECEIPT_KIND = 'global-growth-b-snapshot-target-native-restore'
LINEAGE_STATE = 'VERIFIED_SNAPSHOT_TARGET_LINEAGE'
DOCUMENTS = frozenset(('sourceRestoreReceipt sourcePreparedFingerprint sourceServiceResetReceipt '
    'targetManifest targetHostReceipt targetPreflight targetOperation targetPreparation '
    'targetPreparedFingerprint provenance admission restoreEvent').split())
MAX_DOCUMENT = 4 * 1024**2
MAX_TOTAL = 24 * 1024**2
HASH = r'[0-9a-f]{64}'


class Rejected(ValueError):
    """Closed codes only; never interpolate document values or connection data."""


def need(condition, code):
    if not condition:
        raise Rejected(code)


def fields(value, names, code):
    need(isinstance(value, dict) and set(value) == set(names.split()), code)


def digest(value):
    return isinstance(value, str) and re.fullmatch(HASH, value) is not None


def encoded(value):
    return (json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=False, allow_nan=False) + '\n').encode()


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def sources():
    """Additional exact engine dependencies; host adds its existing search set."""
    return {name: contract.sha(Path(__file__).with_name(name)) for name in
            ('growth_b_search_snapshot.py', 'growth_b_snapshot.py', 'growth_b_snapshot_host.py')}


def source_files():
    return {'infra/aws/scripts/' + name: value for name, value in sources().items()}


def tool_identity():
    return sources() | {'growth_b_search.py': contract.sha(search.__file__),
        'growth_b_search_transport.py': contract.sha(transport.__file__)} | snapshot.restore.tool_identity()


def exact_ref(value, prefix=''):
    snapshot_host.reference(value, prefix, maximum=MAX_DOCUMENT)
    need(not any(bit in {'', '.', '..'} for bit in value['key'].split('/')), 'REFERENCE_PATH')
    need(value['key'].startswith(('datasets/' + DATASET + '/', 'datasets/' + DATASET + '-', 'data-bootstrap/')), 'REFERENCE_NAMESPACE')
    return value


def _pairs(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value, 'DUPLICATE_JSON_KEY')
        value[key] = item
    return value


def unpack_document(value):
    fields(value, 'reference base64', 'DOCUMENT_FIELDS')
    ref = exact_ref(value['reference'])
    need(isinstance(value['base64'], str) and len(value['base64']) <= (MAX_DOCUMENT + 2) // 3 * 4, 'DOCUMENT_SIZE')
    raw = base64.b64decode(value['base64'], validate=True)
    need(len(raw) == ref['bytes'] and sha(raw) == ref['sha256'], 'DOCUMENT_BYTES')
    parsed = json.loads(raw, object_pairs_hook=_pairs,
        parse_constant=lambda _: (_ for _ in ()).throw(Rejected('NONFINITE_JSON')))
    need(isinstance(parsed, dict), 'DOCUMENT_OBJECT')
    snapshot_host.public_json(parsed)
    return parsed, raw


def instant(value):
    need(isinstance(value, str), 'TIMESTAMP_TYPE')
    result = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    need(result.tzinfo is not None and result.utcoffset() == dt.timedelta(0), 'UTC_TIMESTAMP')
    return result


def _chronology(value, states, names):
    events = value.get('events')
    need(isinstance(events, list) and len(states) <= len(events) <= 100, 'PHASE_EVENTS')
    times = [instant(item['at']) for item in events]
    need(times == sorted(times) and not any('FAILED' in item['state'] for item in events), 'FAILED_OR_UNORDERED_PHASE')
    selected = []
    for state in states:
        rows = [(i, row) for i, row in enumerate(events) if row['state'] == state]
        need(len(rows) == 1, 'PHASE_NOT_UNIQUE')
        selected.append(rows[0])
    need([i for i, _ in selected] == sorted(i for i, _ in selected), 'PHASE_ORDER')
    points = [instant(value[name]) for name in names]
    need(points == sorted(points), 'PHASE_TIME_ORDER')
    return points, selected


def validate_binding(binding):
    fields(binding, 'schemaVersion kind datasetId runId targetRds preparationHostInstanceId application '
        'envelopeSha256 consumerManifestSha256 checksumsSha256 targetPreparation sourceEvidence', 'BINDING_FIELDS')
    need(binding['schemaVersion'] == 1 and binding['kind'] == BINDING_KIND and binding['datasetId'] == DATASET, 'TARGET_BINDING_FAMILY')
    snapshot_host.coordinates(DATASET, binding['runId'], 'native-target')
    fields(binding['targetRds'], 'identifier resourceId endpoint serverUuid createdAt', 'TARGET_RDS_FIELDS')
    snapshot_host.identity({k: v for k, v in binding['targetRds'].items() if k != 'createdAt'}, binding['runId'])
    instant(binding['targetRds']['createdAt'])
    need(re.fullmatch(r'i-[0-9a-f]{17}', binding['preparationHostInstanceId']) is not None, 'TARGET_PREPARATION_HOST')
    app = binding['application']
    fields(app, 'mainCommit image appJarSha256 migrationFilesSha256', 'APPLICATION_FIELDS')
    need(re.fullmatch(r'[0-9a-f]{40}', app['mainCommit']) is not None
         and re.fullmatch(ACCOUNT + r'\.dkr\.ecr\.' + REGION + r'\.amazonaws\.com/airbob-repo@sha256:' + HASH, app['image']) is not None
         and all(digest(app[k]) for k in ('appJarSha256', 'migrationFilesSha256'))
         and all(digest(binding[k]) for k in ('envelopeSha256', 'consumerManifestSha256', 'checksumsSha256')), 'APPLICATION_RELEASE_BINDING')
    fields(binding['targetPreparation'], 'manifest hostReceipt', 'TARGET_PREPARATION_REFS')
    fields(binding['sourceEvidence'], 'restoreReceipt preparedFingerprint serviceResetReceipt', 'SOURCE_EVIDENCE_REFS')
    for ref in binding['targetPreparation'].values():
        exact_ref(ref)
    for ref in binding['sourceEvidence'].values():
        exact_ref(ref, 'data-bootstrap/')
    return binding


def _source_proof(binding, values, refs, envelope, raw_base, core):
    raw, prepared, reset = (values[k] for k in ('sourceRestoreReceipt', 'sourcePreparedFingerprint', 'sourceServiceResetReceipt'))
    original = core['source']; source_run = original['identifier'].removeprefix('airbob-')
    snapshot_host.identity(original, source_run)
    for name, field in (('sourceRestoreReceipt', 'restoreReceipt'), ('sourcePreparedFingerprint', 'preparedFingerprint'),
                        ('sourceServiceResetReceipt', 'serviceResetReceipt')):
        need(refs[name] == binding['sourceEvidence'][field]
             and refs[name]['sha256'] == core['evidence'][field + 'Sha256'], 'SOURCE_EXACT_EVIDENCE')
        exact_ref(refs[name], 'data-bootstrap/' + source_run + '/')
    need(raw.get('schemaVersion') == 1 and raw.get('kind') == 'global-growth-b-aws-restore-receipt'
         and raw.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED' and raw.get('executionScope') == 'final-b-rds'
         and raw.get('operation') == 'replace-database' and raw.get('sqlImportSkipped') is not True
         and raw.get('toolIdentity') == snapshot.restore.tool_identity() and raw.get('targetIdentity') == original
         and raw.get('datasetId') == DATASET and raw.get('envelopeSha256') == binding['envelopeSha256']
         and raw.get('account') == ACCOUNT and raw.get('region') == REGION
         and raw.get('mysqlVersion') == '8.4.11' and raw.get('flywayVersion') == 28
         and raw.get('finalScaleSelected') is True and raw.get('allRowsAndDdlEqual') is True
         and raw.get('awsWritesExecuted') is True and raw.get('previousBusinessSchemaAbsent') is True
         and raw.get('maximumSimultaneousBusinessDatabases') == 1
         and raw.get('beforeDatabase', {}).get('serverUuid') == original['serverUuid']
         and raw['beforeDatabase'].get('version') == '8.4.11' and bool(raw['beforeDatabase'].get('tlsCipher'))
         and raw.get('sealedFingerprint') == raw_base == core['sealedFingerprint']
         and digest(raw.get('restoredFingerprintSha256'))
         and raw.get('appJarSha256') == binding['application']['appJarSha256'] == envelope['appJarSha256']
         and raw.get('migrationFilesSha256') == binding['application']['migrationFilesSha256']
         and raw.get('smallRdsPrerequisite', {}).get('sameToolApplicationAndV28Verified') is True, 'SOURCE_ACTUAL_RAW_IMPORT')
    names = ('sqlImportStartedAt', 'sqlImportCompletedAt', 'fullValidationStartedAt', 'fullValidationCompletedAt',
             'preparationStartedAt', 'preparationCompletedAt')
    points, events = _chronology(raw, ('SQL_IMPORT_STARTED', 'SQL_IMPORT_COMPLETED', 'FULL_VALIDATION_STARTED',
        'SEALED_DATABASE_VERIFIED', 'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED'), names)
    need(all(point <= instant(event['at']) for point, (_, event) in zip(points, events)), 'SOURCE_IMPORT_CHRONOLOGY')
    prep = raw.get('preparation', {})
    need(prep.get('passed') is True and prep.get('readinessVerified') is True
         and prep.get('accountLogins', {}).get('passed') is True
         and prep.get('currentInventory', {}).get('everyHorizonContiguous') is True
         and prep.get('applicationLeftRunning') is False and prep.get('ownerSha256BeforeAndAfter') == core['ownerSha256'], 'SOURCE_PREPARATION')
    contract.validate_fingerprint(prepared, require_sealed=False)
    snapshot.restore.validate_prepared_changes(raw_base, prepared)
    need(prepared == core['preparedFingerprint'] and snapshot.canonical_sha(prepared) == core['preparedFingerprintCanonicalSha256'], 'SOURCE_SNAPSHOT_FINGERPRINT')
    need(reset.get('schemaVersion') == 1 and reset.get('kind') == 'global-growth-b-aws-service-reset-verification'
         and reset.get('state') == 'SERVICE_VERIFIED_AND_RESET' and reset.get('mysql') == snapshot.MYSQL
         and reset.get('datasetId') == DATASET and reset.get('targetIdentity') == original
         and reset.get('application') == binding['application']
         and reset.get('restoreReceiptSha256') == refs['sourceRestoreReceipt']['sha256']
         and reset.get('preparedFingerprintSha256') == refs['sourcePreparedFingerprint']['sha256']
         and all(reset.get(k) is True for k in ('writersStopped', 'cdcStopped', 'sourceOriginalsUnchanged')), 'SOURCE_R4_RESET_LINEAGE')
    service, reset_proof = reset.get('service', {}), reset.get('reset', {})
    need(all(service.get(k) is True for k in ('readinessPassed', 'normalLoginsPassed', 'publicReadsPassed', 'globalSearchPassed',
         'imagesSampled', 'reservableDatesPassed', 'domainApiMutationCdcEsPassed')) and service.get('representativeAccounts') == 3
         and all(reset_proof.get(k) is True for k in ('passed', 'testMutationRemoved', 'unchangedDomainAndDdl'))
         and reset_proof.get('remainingOutboxRows') == 0
         and reset_proof.get('ownerSha256BeforeAndAfter') == core['ownerSha256'], 'SOURCE_R4_RESET_COMPLETE')
    need(points[-1] <= instant(reset['completedAt']) <= instant(values['provenance']['timings']['createRequestedAt']), 'SOURCE_R4_BEFORE_SNAPSHOT')


def _target_proof(binding, values, refs, core):
    run, target = binding['runId'], {k: v for k, v in binding['targetRds'].items() if k != 'createdAt'}
    selected, host, proof, prep, preflight = (values[k] for k in
        ('targetManifest', 'targetHostReceipt', 'targetOperation', 'targetPreparation', 'targetPreflight'))
    snapshot_host.validate_manifest(selected, DATASET, run, selected['operationId'])
    need(selected['operation'] == 'prepare' and selected['snapshotIdentifier'] == core['snapshotIdentifier']
         and selected['application'] == {k: binding['application'][k] for k in ('mainCommit', 'image')}, 'TARGET_PREPARE_MANIFEST')
    prefix = snapshot_host.prefix(DATASET, run, selected['operationId'])
    output_prefix = snapshot_host.evidence_prefix(DATASET, run, selected['operationId'])
    need(refs['targetManifest'] == binding['targetPreparation']['manifest']
         and refs['targetManifest']['key'] == prefix + 'manifest-' + refs['targetManifest']['sha256'] + '.json'
         and refs['targetHostReceipt'] == binding['targetPreparation']['hostReceipt']
         and refs['targetHostReceipt']['key'] == output_prefix + 'host-receipt.json', 'TARGET_EXACT_ROOT_REFS')
    need(host.get('schemaVersion') == 1 and host.get('kind') == snapshot_host.KIND + '-receipt'
         and host.get('state') == 'HOST_OPERATION_COMPLETE' and host.get('operation') == 'prepare'
         and host.get('runId') == run and host.get('datasetId') == DATASET and host.get('operationId') == selected['operationId']
         and host.get('toolSources') == selected['toolSources'] and host.get('manifestSha256') == refs['targetManifest']['sha256']
         and host.get('targetIdentity') == target and host.get('hostInstanceId') == binding['preparationHostInstanceId']
         and all(host.get(k) is False for k in ('persistentAwsResourcesMutatedByHost', 'sqlImportExecuted', 'applicationLeftRunning', 'deploymentReady')),
         'ACTUAL_TARGET_HOST_COMPLETION')
    for name, key in {'targetPreflight': 'preflight.json', 'targetOperation': 'snapshot-operation.json',
                      'targetPreparation': 'data-only-preparation.json', 'targetPreparedFingerprint': 'prepared-fingerprint.json'}.items():
        need(refs[name] == host['objects'][key] and refs[name]['key'] == output_prefix + key, 'TARGET_TRANSITIVE_OBJECT_REF')
    for name in ('provenance', 'admission', 'restoreEvent'):
        need(refs[name] == selected['evidence'][name], 'TARGET_RESTORE_EVIDENCE_REFS')
    need(target['resourceId'] != core['source']['resourceId'] and target['serverUuid'] != core['source']['serverUuid']
         and target['identifier'] != core['source']['identifier'], 'NEW_TARGET_IDENTITY_REQUIRED')
    expected = {'targetIdentity': target, 'provenanceSha256': refs['provenance']['sha256'],
        'admissionSha256': refs['admission']['sha256'], 'restoreEventSha256': refs['restoreEvent']['sha256'],
        'toolIdentity': snapshot.tool_identity(), 'datasetId': DATASET, 'envelopeSha256': binding['envelopeSha256']}
    need(proof.get('schemaVersion') == 1 and proof.get('kind') == snapshot.KIND + '-operation'
         and proof.get('state') == snapshot.PREPARED and proof.get('operation') == 'prepare'
         and all(proof.get(k) == v for k, v in expected.items())
         and all(proof.get(k) is True for k in ('snapshotWholeBaselineVerified', 'sqlImportSkipped', 'actualRestoreVerified'))
         and all(proof.get(k) is False for k in ('databaseRecreatedInThisRun', 'applicationLeftRunning', 'deploymentReady'))
         and proof.get('snapshotBaselineCanonicalSha256') == core['preparedFingerprintCanonicalSha256']
         and proof.get('baselineOwnerSha256') == core['ownerSha256'], 'ACTUAL_SNAPSHOT_BASELINE_PREPARATION')
    need(preflight.get('state') == 'RESTORED_TARGET_READ_ONLY_PREFLIGHT' and preflight.get('operation') == 'prepare'
         and all(preflight.get(k) == v for k, v in expected.items())
         and preflight.get('configSha256') == proof.get('configSha256')
         and digest(proof.get('configSha256')) and digest(proof.get('credentialBindingSha256'))
         and preflight.get('credentialBindingSha256') == proof['credentialBindingSha256']
         and host.get('preflightSha256') == refs['targetPreflight']['sha256']
         and all(digest(host.get(k)) for k in ('restoreConfigSha256', 'derivedRestoreConfigSha256')), 'TARGET_PREFLIGHT_EXECUTION')
    # A resume may have added allowed FREE days since the original whole snapshot
    # check.  Its terminal frozen proof must still retain that original baseline.
    if 'resume' not in selected:
        need(preflight.get('resumeReceiptSha256') is None
             and preflight.get('currentFingerprintCanonicalSha256') == core['preparedFingerprintCanonicalSha256'], 'TARGET_INITIAL_SNAPSHOT_EQUALITY')
    else:
        need(preflight.get('resumeReceiptSha256') == selected['resume']['operationReceipt']['sha256']
             and digest(preflight.get('currentFingerprintCanonicalSha256')), 'TARGET_RESUME_BASELINE')
    final = values['targetPreparedFingerprint']
    contract.validate_fingerprint(final, require_sealed=False)
    snapshot.restore.validate_prepared_changes(core['sealedFingerprint'], final)
    need(proof.get('preparedFingerprintSha256') == refs['targetPreparedFingerprint']['sha256']
         and proof.get('preparedFingerprintCanonicalSha256') == snapshot.canonical_sha(final)
         and proof.get('preparation') == prep.get('preparation')
         and prep.get('schemaVersion') == 1 and prep.get('kind') == 'global-growth-b-aws-data-only-preparation'
         and prep.get('state') == 'DATABASE_INVENTORY_LOGIN_VERIFIED' and prep.get('sourceMode') == 'verified-global-b-snapshot'
         and prep.get('runId') == run and prep.get('datasetId') == DATASET
         and prep.get('serverUuid') == target['serverUuid'] and prep.get('rdsResourceId') == target['resourceId']
         and prep.get('rdsEngineVersion') == '8.4.11' and prep.get('flywayVersion') == 28
         and prep.get('restoreReceiptSha256') == refs['targetOperation']['sha256']
         and prep.get('snapshotProvenanceSha256') == refs['provenance']['sha256']
         and prep.get('applicationLeftRunning') is False and prep.get('deploymentReady') is False, 'TARGET_PREPARED_FINGERPRINT')
    preparation = prep['preparation']
    need(preparation.get('passed') is True and preparation.get('readinessVerified') is True
         and preparation.get('accountLogins', {}).get('passed') is True
         and preparation.get('currentInventory', {}).get('everyHorizonContiguous') is True
         and preparation.get('ownerSha256BeforeAndAfter') == core['ownerSha256']
         and preparation.get('preparedFingerprintSha256') == refs['targetPreparedFingerprint']['sha256']
         and preparation.get('applicationLeftRunning') is False, 'TARGET_NORMAL_PREPARATION')
    event, admission, provenance = (values[k] for k in ('restoreEvent', 'admission', 'provenance'))
    snapshot_host.cloudtrail_projection(event)
    need(admission.get('schemaVersion') == 1 and admission.get('kind') == snapshot.KIND + '-restore-admission'
         and admission.get('state') == 'SOURCE_ABSENT_TARGET_ABSENT' and admission.get('source') == core['source']
         and admission.get('targetIdentifier') == target['identifier'] and admission.get('snapshotArn') == provenance['snapshot']['arn']
         and admission.get('provenanceSha256') == refs['provenance']['sha256']
         and admission.get('maximumSimultaneousBusinessDatabases') == 1 and admission.get('restoreExecuted') is False,
         'SEQUENTIAL_SOURCE_ABSENT_ADMISSION')
    snapshot.validate_lease(admission['lease'], target['identifier'])
    need(event['eventName'] == 'RestoreDBInstanceFromDBSnapshot' and event['eventSource'] == 'rds.amazonaws.com'
         and event['awsRegion'] == REGION and event['recipientAccountId'] == ACCOUNT
         and event['requestParameters']['dBInstanceIdentifier'] == target['identifier']
         and event['requestParameters']['dBSnapshotIdentifier'] in (core['snapshotIdentifier'], provenance['snapshot']['arn']), 'EXACT_RESTORE_EVENT')
    actual = {'eventId': event['eventID'], 'eventSha256': snapshot.canonical_sha(event), 'requestedAt': event['eventTime'],
        'instanceCreateTime': binding['targetRds']['createdAt'], 'evidenceSource': 'controller-pinned-cloudtrail-event'}
    need(proof.get('actualRestore') == preflight.get('actualRestore') == prep.get('snapshotRestoreEvidence') == actual, 'ACTUAL_RESTORE_BINDING')
    requested, created = instant(event['eventTime']), instant(binding['targetRds']['createdAt'])
    available = instant(provenance['timings']['snapshotAvailableVerifiedAt'])
    need(available <= instant(admission['recordedAt']) <= requested <= created + dt.timedelta(minutes=5)
         and created - requested <= dt.timedelta(hours=2), 'TARGET_RESTORE_CHRONOLOGY')
    points, events = _chronology(proof, ('SNAPSHOT_WHOLE_BASELINE_VERIFIED', 'PREPARATION_STARTED', snapshot.PREPARED),
        ('fullValidationStartedAt', 'fullValidationCompletedAt', 'preparationStartedAt', 'preparationCompletedAt'))
    need(created <= points[0] and points[1] <= instant(events[0][1]['at'])
         and points[2] <= instant(events[1][1]['at']) and points[3] <= instant(events[2][1]['at'])
         <= instant(prep['completedAt']) <= instant(host['completedAt']), 'TARGET_VALIDATION_CHRONOLOGY')


def validate_lineage(binding, documents, envelope, raw_base):
    """Pure bounded validation; never reads credentials, invokes AWS, or scans SQL.

    envelope is another exact-byte {reference,base64} document. raw_base is the
    parsed sealed fingerprint; restore() additionally verifies its release-file
    SHA and the unchanged SourceAdapter's sealed-release validation.
    """
    try:
        validate_binding(binding)
        need(isinstance(documents, dict) and set(documents) == DOCUMENTS, 'EXACT_PROOF_INVENTORY')
        need(sum(len(v.get('base64', '')) for v in documents.values()) <= MAX_TOTAL * 4 // 3, 'PROOF_TOTAL_SIZE')
        values = {name: unpack_document(item)[0] for name, item in documents.items()}
        refs = {name: copy.deepcopy(item['reference']) for name, item in documents.items()}
        env, _ = unpack_document(envelope)
        need(envelope['reference']['sha256'] == binding['envelopeSha256']
             and env['datasetId'] == DATASET and env['mysql'] == {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'}
             and env['finalScaleSelected'] is True and env['awsExecutionAllowed'] is True
             and env['consumerManifestSha256'] == binding['consumerManifestSha256']
             and env['checksumsSha256'] == binding['checksumsSha256']
             and env['objects']['migration-files.json']['sha256'] == binding['application']['migrationFilesSha256'], 'EXACT_FINAL_ENVELOPE')
        contract.validate_fingerprint(raw_base)
        core = snapshot.validate_provenance(values['provenance'])
        need(core['datasetId'] == DATASET and core['application'] == binding['application']
             and core['publication'] == {'envelopeSha256': binding['envelopeSha256'],
                 'publicationReceiptSha256': env['publicationReceiptSha256'], 'consumerManifestSha256': binding['consumerManifestSha256'],
                 'checksumsSha256': binding['checksumsSha256'], 'objects': env['objects']}
             and core['sealedFingerprint'] == raw_base, 'SNAPSHOT_SEALED_SOURCE_BINDING')
        provenance = values['provenance']; meta, storage = provenance['snapshot'], provenance['storage']
        expected_arn = f'arn:aws:rds:{REGION}:{ACCOUNT}:snapshot:{core["snapshotIdentifier"]}'
        need(meta['identifier'] == core['snapshotIdentifier'] and meta['arn'] == expected_arn
             and meta['state'] == 'available' and meta['engineVersion'] == '8.4.11' and meta['encrypted'] is True
             and meta['sourceRdsResourceId'] == core['source']['resourceId'] == storage['resourceId']
             and storage['identifier'] == core['source']['identifier']
             and meta['allocatedStorageGiB'] == storage['allocatedStorageGiB'] == 100
             and meta['storageType'] == storage['storageType'] == 'gp3'
             and meta['kmsKeyArn'] == storage['kmsKeyArn']
             and re.fullmatch(f'arn:aws:kms:{REGION}:{ACCOUNT}:key/[0-9a-f-]{{36}}', meta['kmsKeyArn']) is not None
             and meta['tags'] == snapshot.snapshot_tags(core), 'SNAPSHOT_ACTUAL_METADATA')
        need(instant(storage['instanceCreateTime']) <= instant(provenance['timings']['createRequestedAt'])
             and instant(provenance['timings']['createRequestedAt']) - dt.timedelta(minutes=5) <= instant(meta['snapshotCreateTime'])
             <= instant(provenance['timings']['snapshotAvailableVerifiedAt']), 'SNAPSHOT_AVAILABLE_CHRONOLOGY')
        snapshot_host.provenance_source_binding(refs['provenance'], core)
        _source_proof(binding, values, refs, env, raw_base, core)
        _target_proof(binding, values, refs, core)
        return {'schemaVersion': 1, 'kind': LINEAGE_KIND, 'state': LINEAGE_STATE, 'datasetId': DATASET,
            'runId': binding['runId'], 'bindingSha256': sha(encoded(binding)), 'toolIdentity': tool_identity(),
            'consumerManifestSha256': binding['consumerManifestSha256'], 'checksumsSha256': binding['checksumsSha256'],
            'ancestorSource': core['source'], 'target': binding['targetRds'], 'application': binding['application'],
            'snapshotIdentifier': core['snapshotIdentifier'], 'snapshotArn': meta['arn'],
            'provenanceContractSha256': provenance['contractSha256'], 'evidence': refs,
            'envelope': copy.deepcopy(envelope['reference']), 'sealedFingerprintCanonicalSha256': snapshot.canonical_sha(raw_base),
            'ancestorPreparedFingerprintCanonicalSha256': core['preparedFingerprintCanonicalSha256'],
            'targetPreparedFingerprintCanonicalSha256': snapshot.canonical_sha(values['targetPreparedFingerprint']),
            'originalRestoredFingerprintSha256': values['sourceRestoreReceipt']['restoredFingerprintSha256'],
            'publishedDocuments': env['storage']['publishedListings'],
            'rawBaselineObservedOnTarget': False, 'currentFullRowsRevalidated': False, 'currentOwnedRowsRevalidated': False}
    except Rejected:
        raise
    except (ValueError, KeyError, TypeError, AttributeError, OverflowError, binascii.Error):
        raise Rejected('SNAPSHOT_LINEAGE_INVALID') from None


def _write_new(path, value):
    raw = encoded(value)
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600), 'wb') as stream:
        stream.write(raw); stream.flush(); os.fsync(stream.fileno())
    fd = os.open(Path(path).parent, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
    return sha(raw)


def validate_receipt(receipt, binding, documents, envelope, raw_base):
    """Offline completion admission in addition to the controller's live pins."""
    lineage = validate_lineage(binding, documents, envelope, raw_base)
    validate_native_receipt(receipt, lineage)
    return lineage


def validate_native_receipt(receipt, lineage):
    """Validate against a projection returned by validate_lineage, not an input flag.

    The caller must freshly validate the projection's exact source documents;
    this function intentionally does not attest an arbitrary supplied lineage.
    """
    fields(receipt, 'schemaVersion kind state hostRuntimeQualification datasetId consumerManifestSha256 checksSha256 '
        'appJarSha256 snapshotRelease snapshotUuid companionManifestSha256 mysql snapshotTargetBaselineSha256 sourceBaseline '
        'rawBaselineObservedOnTarget currentFullRowsRevalidated currentOwnedRowsRevalidated preparedAllowedChanges '
        'fullDocumentFingerprint allDocumentSourceFieldsEqual sourceDrift elasticsearch runtimeCompatibility restoredIndex '
        'previousIndexRetained activeAlias repositoryReadOnly nativeInventoryUnchanged repositoryRegistrationRemoved '
        'diskGate restoreMeasurements nativeTransport', 'NATIVE_RECEIPT_FIELDS')
    snapshot_host.public_json(receipt)
    need(lineage.get('kind') == LINEAGE_KIND and lineage.get('state') == LINEAGE_STATE
         and lineage.get('toolIdentity') == tool_identity() and lineage.get('rawBaselineObservedOnTarget') is False,
         'VALIDATED_TARGET_LINEAGE_REQUIRED')
    need(receipt.get('schemaVersion') == 1 and receipt.get('kind') == RECEIPT_KIND
         and receipt.get('state') in {'SEARCH_RESTORED_AND_ACTIVATED', 'SEARCH_VERIFIED_NOT_ACTIVATED'}
         and receipt.get('sourceBaseline') == lineage and receipt.get('rawBaselineObservedOnTarget') is False
         and 'baselineVerifiedBeforePreparation' not in receipt and 'baselineReceiptSha256' not in receipt
         and receipt.get('datasetId') == DATASET and receipt.get('appJarSha256') == lineage['application']['appJarSha256']
         and receipt.get('consumerManifestSha256') == lineage['consumerManifestSha256']
         and receipt.get('checksSha256') == lineage['checksumsSha256']
         and receipt.get('mysql') == {'version': '8.4.11', 'schema': 'airbobdb', 'serverUuid': lineage['target']['serverUuid'],
             'publishedDocuments': lineage['publishedDocuments']}
         and digest(receipt.get('snapshotTargetBaselineSha256'))
         and all(receipt.get(k) is True for k in ('allDocumentSourceFieldsEqual', 'repositoryReadOnly', 'nativeInventoryUnchanged',
             'repositoryRegistrationRemoved', 'currentFullRowsRevalidated', 'currentOwnedRowsRevalidated'))
         and receipt.get('sourceDrift') == {'mysqlUuidUnchanged': True, 'completeRowsAndDdlUnchanged': True, 'allCurrentDocumentFieldsUnchanged': True}
         and receipt.get('nativeTransport', {}).get('exactVersionBytesVerified') is True
         and receipt['nativeTransport'].get('sourceSealsChanged') is False, 'SNAPSHOT_NATIVE_COMPLETION')
    fingerprint = receipt['fullDocumentFingerprint']
    fields(fingerprint, 'algorithm documents contentSha256 identityPairsSha256 mappingSha256 indexSemanticsSha256', 'NATIVE_FULL_FINGERPRINT_FIELDS')
    need(fingerprint['algorithm'] == search.ALGORITHM and fingerprint['documents'] == lineage['publishedDocuments']
         and all(digest(fingerprint[k]) for k in ('contentSha256', 'identityPairsSha256', 'mappingSha256', 'indexSemanticsSha256'))
         and digest(receipt['companionManifestSha256'])
         and receipt['nativeTransport'].get('state') == 'IMMUTABLE_NATIVE_S3_BYTES_VERIFIED'
         and digest(receipt['nativeTransport'].get('manifestSha256')), 'NATIVE_FINGERPRINT_AND_VERSION_PROOF')
    prefix = transport.prefix_for(DATASET, receipt['snapshotRelease'])
    need(receipt['nativeTransport']['repository'] == {'type': 's3', 'bucket': transport.BUCKET, 'basePath': prefix + 'native'},
         'NATIVE_REPOSITORY_BINDING')
    exact_ref(receipt['nativeTransport']['completionMarker'], prefix)
    need(receipt['nativeTransport']['completionMarker']['key'] == prefix + transport.MARKER, 'NATIVE_COMPLETION_MARKER')
    prepared = receipt['preparedAllowedChanges']
    fields(prepared, 'unchangedCompleteTables onlyAllowedChanges historicalInventoryAllRowsEqual historicalInventoryRows historicalInventorySha256', 'PREPARED_COMPARISON_FIELDS')
    need(prepared['onlyAllowedChanges'] == ['member.password', 'additional valid FREE inventory rows']
         and prepared['historicalInventoryAllRowsEqual'] is True
         and prepared['unchangedCompleteTables'] == sorted(contract.TABLES - {'member', 'accommodation_inventory_day'})
         and type(prepared['historicalInventoryRows']) is int and prepared['historicalInventoryRows'] >= 0
         and digest(prepared['historicalInventorySha256']), 'PREPARED_COMPLETE_COMPARISON')
    if receipt['state'] == 'SEARCH_RESTORED_AND_ACTIVATED':
        need(receipt.get('activeAlias') == receipt.get('restoredIndex') and re.fullmatch(search.INDEX_RE, receipt['restoredIndex']), 'TARGET_ALIAS_COMPLETION')
    else:
        need(receipt['activeAlias'] == receipt['previousIndexRetained'], 'UNACTIVATED_ALIAS_CHANGED')
    return lineage


def restore(config, companion, descriptor, binding, documents, envelope, output, *, activate_alias=False, guard=None):
    """New target-only execution; all native operations use unchanged utilities.

    guard(force=True) is mandatory. The enclosing worker independently watches
    ACK/deadline during long frozen subprocesses, exactly as for the source path.
    No source baseline receipt is fabricated and search.restore is never called.
    """
    need(callable(guard), 'LIVE_CONTROLLER_GUARD_REQUIRED')
    guard(force=True)
    # Reject untrusted evidence before creating any SQL/ES/AWS adapter.
    base_path = Path(config['releaseDirectory']) / 'before-fingerprint.json'
    raw_base = search.read(base_path)
    lineage = validate_lineage(binding, documents, envelope, raw_base)
    env = unpack_document(envelope)[0]
    need(contract.sha(base_path) == env['objects']['before-fingerprint.json']['sha256'], 'SEALED_BASE_FILE_BYTES')
    need(config['datasetId'] == DATASET and config['consumerManifestSha256'] == binding['consumerManifestSha256']
         and config['checksSha256'] == binding['checksumsSha256']
         and config['mysql']['expectedServerUuid'] == binding['targetRds']['serverUuid']
         and config['repository']['type'] == 's3' and 'transport' in config['repository'], 'EXPLICIT_TARGET_S3_CONFIG')
    target = config.get('targetIndex')
    need(isinstance(target, str) and re.fullmatch(search.INDEX_RE, target), 'EXPLICIT_NEW_TARGET_INDEX')
    manifest, reference = search.validate_companion(companion, descriptor)
    output = Path(output); output.mkdir(mode=0o700)
    registered = False; repository = None; es = None; stage = 'VALIDATED_INPUTS'; alias_attempted = False
    cleanup_failed = False
    try:
        with tempfile.TemporaryDirectory(prefix='growth-b-snapshot-search-', dir=output) as work:
            guard(force=True)
            source = search.SourceAdapter(config, Path(work) / 'source', runtime_output=output / 'host-runtime-qualification.json')
            need(source.base == raw_base, 'LIVE_READER_SEALED_BASE')
            es = search.Elasticsearch(config['elasticsearch']); identity = es.identity(); previous = es.alias(optional=True)
            compatibility = search.runtime_compatibility(reference['elasticsearch'], identity)
            repository = search.repository_for(config)
            need(es.optional('/' + target) is None and es.optional('/_snapshot/' + repository.name) is None, 'FRESH_NATIVE_NAMESPACE')
            reviewed = config['repository']['transport']
            marker = transport.validate_published_transport(reviewed['manifest'], reviewed['sha256'], companion, descriptor)
            need(repository.settings.get('region') == config['repository']['aws']['region'] == marker['region'] == REGION
                 and marker['bucket'] == snapshot_host.BUCKET
                 and marker['sql']['publicationReceiptSha256'] == env['publicationReceiptSha256']
                 and marker['source']['consumerManifestSha256'] == binding['consumerManifestSha256']
                 and marker['source']['checksSha256'] == binding['checksumsSha256'], 'NATIVE_TRANSPORT_SOURCE')
            search.same_repository(repository, reference, marker)
            native = repository.inventory(); transport.validate_native_versions(marker, native)
            transport_proof = transport.verify_s3_repository(marker, repository.aws)
            need(repository.inventory() == native, 'NATIVE_VERSION_DRIFT')
            repository.transport_proof = transport_proof
            producer = search.read(Path(companion) / 'snapshot-producer-receipt.json')
            space = search.disk_gate(es, producer['storage']['measuredSourcePlusTemporaryRestoreStoreBytes'])
            need(all(manifest[k] == value for k, value in search.anchors(source).items())
                 and search.read(Path(companion) / 'mysql-baseline-fingerprint.json') == raw_base
                 and search.read(Path(companion) / 'source-proof.json')['baseDumpSha256'] == source.checks['airbob-growth.sql.gz'], 'COMPANION_SEALED_RELEASE')
            guard(force=True); stage = 'CURRENT_TARGET_VALIDATION'
            with source.fence() as mysql:
                need(mysql == {'version': '8.4.11', 'schema': 'airbobdb', 'serverUuid': binding['targetRds']['serverUuid'],
                     'publishedDocuments': env['storage']['publishedListings']}, 'LIVE_TARGET_IDENTITY')
                current = source.probe('fingerprint'); owned = source.probe('owned')
                prepared = search.verify_prepared(raw_base, current, owned)
                guard(force=True)
                current_sha = _write_new(output / 'current-target-fingerprint.json', current)
                baseline_sha = _write_new(output / 'snapshot-target-baseline.json', {'schemaVersion': 1,
                    'kind': LINEAGE_KIND + '-current-source', 'state': 'SNAPSHOT_TARGET_CURRENT_SOURCE_VERIFIED',
                    'lineage': lineage, 'mysql': mysql, 'currentFingerprintSha256': current_sha, 'ownedInventory': owned,
                    'preparedAllowedChanges': prepared, 'rawBaselineObservedOnTarget': False,
                    'sourceReadFence': 'all existing base tables READ', 'currentFullRowsRevalidated': True,
                    'currentOwnedRowsRevalidated': True})
                path = Path(work) / 'current-documents.jsonl'; projection = source.documents(path)
                need(projection == search.document_fingerprint(reference['fingerprint']), 'TARGET_DOCUMENT_PROJECTION')
                need(reference['fingerprint']['mappingSha256'] == search.digest(source.mapping['mappings'])
                     and reference['fingerprint']['indexSemanticsSha256'] == search.digest(search.index_semantics(source.mapping['settings'])), 'TARGET_MAPPING_SEMANTICS')
                guard(force=True); stage = 'NATIVE_RESTORE'
                # An ambiguous register may have created the exact namespace.
                # Mark it first so cleanup can report/attempt only that name.
                registered = True; repository.register(es, True)
                search.validate_snapshot(search.snapshot_info(es, repository, reference['snapshot']), reference['snapshot'],
                    reference['snapshotIndex'], reference['snapshotUuid'])
                measurements = search.restore_index(es, repository, reference, target, search.snapshot_timeout(config))
                guard(force=True); measurements.mark('fullComparisonStarted'); stage = 'FULL_NATIVE_COMPARISON'
                actual = es.fingerprint(target, path)
                need(actual == reference['fingerprint'], 'NATIVE_FULL_SOURCE_MISMATCH')
                measurements.mark('fullComparisonCompleted')
                drift = search.source_drift_check(source, mysql, current, projection, work)
                need(es.identity() == identity and es.alias(optional=True) == previous and repository.inventory() == native, 'RESTORE_SOURCE_NATIVE_DRIFT')
                guard(force=True); repository.unregister(es); registered = False
                if activate_alias:
                    stage = 'ALIAS_ACTIVATION'; guard(force=True)
                    need(es.api('PUT', '/' + target + '/_settings', {'index.blocks.write': None}).get('acknowledged'), 'INDEX_WRITE_ENABLE')
                    guard(force=True); alias_attempted = True
                    actions = ([] if previous is None else [{'remove': {'index': previous, 'alias': search.ALIAS, 'must_exist': True}}])
                    actions.append({'add': {'index': target, 'alias': search.ALIAS, 'is_write_index': True}})
                    need(es.api('POST', '/_aliases', {'actions': actions}).get('acknowledged') and es.alias() == target, 'ATOMIC_ALIAS_ACTIVATION')
                guard(force=True); stage = 'COMPLETION'
                receipt = {'schemaVersion': 1, 'kind': RECEIPT_KIND,
                    'state': 'SEARCH_RESTORED_AND_ACTIVATED' if activate_alias else 'SEARCH_VERIFIED_NOT_ACTIVATED',
                    'hostRuntimeQualification': search.qualification_binding(source.host_runtime_path), **search.anchors(source),
                    'snapshotRelease': reference['snapshotRelease'], 'snapshotUuid': reference['snapshotUuid'],
                    'companionManifestSha256': search.sha(Path(companion) / 'manifest.json'), 'mysql': mysql,
                    'snapshotTargetBaselineSha256': baseline_sha, 'sourceBaseline': lineage,
                    'rawBaselineObservedOnTarget': False, 'currentFullRowsRevalidated': True, 'currentOwnedRowsRevalidated': True,
                    'preparedAllowedChanges': prepared, 'fullDocumentFingerprint': actual, 'allDocumentSourceFieldsEqual': True,
                    'sourceDrift': drift, 'elasticsearch': identity, 'runtimeCompatibility': compatibility, 'restoredIndex': target,
                    'previousIndexRetained': previous, 'activeAlias': target if activate_alias else previous,
                    'repositoryReadOnly': True, 'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True,
                    'diskGate': space, 'restoreMeasurements': measurements.result(),
                    'nativeTransport': {'manifestSha256': reviewed['sha256'], **transport_proof}}
                stage = 'FENCE_RELEASE'
            # Completion is written only after the frozen context has checked
            # and released its actual connection, and private scratch is gone.
            validate_receipt(receipt, binding, documents, envelope, raw_base)
        guard(force=True); stage = 'COMPLETION'
        _write_new(output / 'search-restore-receipt.json', receipt)
        return receipt
    except BaseException as error:
        if registered and repository is not None and es is not None:
            try:
                guard(force=True); repository.unregister(es); registered = False
            except BaseException:
                cleanup_failed = True
        failure = {'schemaVersion': 1, 'kind': RECEIPT_KIND, 'state': 'FAILED_RESOURCES_RETAINED',
            'stage': stage, 'errorCode': str(error) if isinstance(error, Rejected) else 'FROZEN_OR_IO_OPERATION_FAILED',
            'sourceBaseline': lineage, 'targetIndex': target, 'completionIssued': False, 'automaticRetry': False,
            'aliasActivationAttempted': alias_attempted, 'repositoryRegistrationMayRemain': registered,
            'repositoryCleanupFailed': cleanup_failed, 'restoredIndexDeleted': False}
        try:
            _write_new(output / 'failure.json', failure)
        except BaseException:
            pass  # Preserve the original failure; the enclosing host also journals it.
        if isinstance(error, (KeyboardInterrupt, SystemExit)):
            raise
        raise Rejected(str(error) if isinstance(error, Rejected) else 'SNAPSHOT_NATIVE_RESTORE_FAILED') from None
