"""Snapshot-target branch of the existing guarded native-search host protocol.

The snapshot search engine owns the distinct lineage/native contract. This
bridge binds that contract to the actual retained preparation host and ES host.
It never manufactures a raw pre-preparation baseline for the new target UUID.
"""
from __future__ import annotations

import base64
import datetime as dt
import hashlib
import os
from pathlib import Path

import growth_b_search_host as host
import growth_b_search_snapshot as engine

PACKAGE_KIND = 'global-b-aws-snapshot-native-search-source-package'
PACKAGE_STATE = 'SNAPSHOT_TARGET_LINEAGE_PROOFS_EXPORTED'
HOST_KIND = 'global-b-aws-snapshot-native-search-host-receipt'
need, keys = host.need, host.keys


def document(value):
    keys(value, 'reference base64', 'SNAPSHOT_DOCUMENT_FIELDS')
    raw = base64.b64decode(value['base64'], validate=True)
    need(len(raw) <= host.MAX_PUBLIC_BYTES and len(raw) == value['reference']['bytes']
         and hashlib.sha256(raw).hexdigest() == value['reference']['sha256'], 'SNAPSHOT_DOCUMENT_BYTES')
    result = host.decode(raw)
    host.public_json(result)
    return result, raw


def lineage(context, envelope=None, base=None):
    selected = context['snapshotLineage']
    keys(selected, 'binding documents envelope', 'SNAPSHOT_LINEAGE_FIELDS')
    actual_envelope, _ = document(selected['envelope'])
    need(envelope is None or actual_envelope == envelope, 'SNAPSHOT_LOADED_ENVELOPE_CHANGED')
    if base is None:
        provenance, _ = document(selected['documents']['provenance'])
        base = engine.snapshot.validate_provenance(provenance)['sealedFingerprint']
    return engine.validate_lineage(selected['binding'], selected['documents'], selected['envelope'], base)


def validate_context(context):
    selected, manifest = context['snapshotLineage'], context['serviceManifest']['value']
    keys(selected, 'binding documents envelope', 'SNAPSHOT_LINEAGE_FIELDS')
    binding = selected['binding']
    need(binding['datasetId'] == context['datasetId'] and binding['runId'] == context['runId']
         and binding['targetRds'] == {k: context['rds'][k] for k in ('identifier', 'resourceId', 'endpoint', 'serverUuid', 'createdAt')}
         and binding['preparationHostInstanceId'] == context['hosts']['preparation']['instanceId']
         and binding['application'] == manifest['application'], 'SNAPSHOT_HOST_BINDING')
    need(selected['envelope']['reference'] == context['preparationManifest']['value']['files']['envelope'], 'SNAPSHOT_HOST_ENVELOPE')
    docs = selected['documents']
    prep, _ = document(docs['targetPreparation'])
    target_manifest, _ = document(docs['targetManifest'])
    target_host, _ = document(docs['targetHostReceipt'])
    need(target_manifest['awsPreparation'] == context['preparationManifest']['reference']
         and docs['targetPreparation']['reference'] == manifest['preparation']['receipt']
         and prep == context['sourceRefs']['preparationReceipt']['value']
         and target_host['restoreConfigSha256'] == manifest['preparation']['restoreConfigSha256']
         and docs['targetOperation']['reference']['sha256'] == manifest['preparation']['restoreReceiptSha256'],
         'SNAPSHOT_COMMON_PREPARATION_BINDING')
    host.service.validate_preparation(prep, manifest, 'snapshot', docs['provenance']['reference']['sha256'])
    lineage(context)


def validate_source_package(package, context, context_sha, envelope, base):
    keys(package, 'schemaVersion kind state contextSha256 sourceToolSha256 runId datasetId operationId resourceFence lease exportedAt origin mysql currentIdentityOnly lineage', 'SNAPSHOT_PACKAGE_FIELDS')
    need(package['schemaVersion'] == 1 and package['kind'] == PACKAGE_KIND and package['state'] == PACKAGE_STATE
         and package['contextSha256'] == context_sha
         and package['sourceToolSha256'] == context['toolSources']['growth_b_search_snapshot_bridge.py']
         and all(package[key] == context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease'))
         and package['lineage'] == context['snapshotLineage'], 'SNAPSHOT_PACKAGE_BINDING')
    origin = lineage(context, envelope, base)
    need(package['origin'] == origin and package['mysql'] == host.expected_mysql(context, envelope), 'SNAPSHOT_PACKAGE_ORIGIN')
    current = package['currentIdentityOnly']
    target_host, _ = document(context['snapshotLineage']['documents']['targetHostReceipt'])
    keys(current, 'serverUuid version tlsVerified outboxRows otherClients currentFullRowsRevalidated currentOwnedRowsRevalidated observedAt', 'SNAPSHOT_CURRENT_IDENTITY_FIELDS')
    need(current['serverUuid'] == context['rds']['serverUuid'] and current['version'] == '8.4.11'
         and current['tlsVerified'] is True and current['outboxRows'] == current['otherClients'] == 0
         and current['currentFullRowsRevalidated'] is False and current['currentOwnedRowsRevalidated'] is False
         and host.instant(target_host['completedAt']) <= host.instant(current['observedAt'])
         <= host.instant(package['exportedAt']) < context['controllerDeadlineEpoch'],
         'SNAPSHOT_CURRENT_IDENTITY_ONLY')
    return {}, {}, origin


def retained_target_bytes(context):
    selected = context['snapshotLineage']
    target_manifest, _ = document(selected['documents']['targetManifest'])
    root = host.retained_root(context)
    directory = root / ('snapshot-' + target_manifest['operationId'])
    config = root / 'restore-config.json'
    host.bound_raw(config, context['serviceManifest']['value']['preparation']['restoreConfigSha256'])
    value = host.restore.configuration(config)
    need(all(value['rds'][k] == context['rds'][k] for k in ('identifier', 'resourceId', 'serverUuid', 'endpoint', 'masterSecretArn'))
         and value['envelopeSha256'] == context['preparationManifest']['value']['files']['envelope']['sha256'],
         'RETAINED_SNAPSHOT_CONFIGURATION_CHANGED')
    paths = {'targetManifest': directory / 'aws-snapshot-host.json',
        'targetHostReceipt': directory / 'host-receipt.json',
        'targetPreflight': directory / 'preflight/preflight.json',
        'targetOperation': directory / 'execute/snapshot-operation.json',
        'targetPreparation': directory / 'execute/data-only-preparation.json',
        'targetPreparedFingerprint': directory / 'execute/prepared-fingerprint.json'}
    for name, path in paths.items():
        expected = selected['documents'][name]
        _, raw = document(expected)
        need(host.bound_raw(path, expected['reference']['sha256'], expected['reference']['bytes']) == raw,
             'RETAINED_SNAPSHOT_PUBLIC_BYTES_CHANGED')


def prepare_source(context, context_sha, output, *, guard=None):
    output = Path(output)
    guard = guard or host.LiveGuard(context, context_sha, 'prepare-source')
    guard(force=True)
    paths, envelope, base, qualification = host.load_inputs(context, 'prepare-source', output)
    origin = lineage(context, envelope, base)
    retained_target_bytes(context)
    host.service.verify_debezium(context['serviceManifest']['value']['debezium'], host.command, guard)
    config = host.db_config(context, paths)
    with host.runtime.activated_runtime(qualification):
        guard(force=True)
        directory = output / '.private/connection'; directory.mkdir(mode=0o700)
        db, _ = host.restore.connection(config, guard.aws, {'masterUsername': guard.live['MasterUsername']}, directory, guard)
        observed = host.identity_only(context, db, config)
    guard(force=True)
    result = {'schemaVersion': 1, 'kind': PACKAGE_KIND, 'state': PACKAGE_STATE,
        'contextSha256': context_sha, 'sourceToolSha256': context['toolSources']['growth_b_search_snapshot_bridge.py'],
        **{key: context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease')},
        'exportedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'origin': origin,
        'mysql': host.expected_mysql(context, envelope), 'currentIdentityOnly': observed,
        'lineage': context['snapshotLineage']}
    validate_source_package(result, context, context_sha, envelope, base)
    need(len(host.canonical(result)) <= host.MAX_PUBLIC_BYTES, 'SNAPSHOT_SOURCE_PACKAGE_TOO_LARGE')
    host.write(output / 'public/source-package.json', result)
    return result


def restore_on_es(context, context_sha, package_path, package_sha, output, *, guard=None):
    output = Path(output)
    guard = guard or host.LiveGuard(context, context_sha, 'restore-on-es')
    guard(force=True)
    paths, envelope, base, qualification = host.load_inputs(context, 'restore-on-es', output)
    package = host.decode(host.bound_raw(package_path, package_sha))
    _, _, origin = validate_source_package(package, context, context_sha, envelope, base)
    descriptor = host.read(paths['descriptor'])
    marker = host.transport.validate_published_transport(paths['transport'],
        context['serviceManifest']['value']['search']['transport']['sha256'], paths['companion'], descriptor)
    need(marker['sql']['publicationReceiptSha256'] == envelope['publicationReceiptSha256']
         and marker['source']['consumerManifestSha256'] == envelope['consumerManifestSha256']
         and marker['source']['checksSha256'] == envelope['checksumsSha256'], 'SNAPSHOT_TRANSPORT_SQL_SOURCE')
    config = host.db_config(context, paths)
    with host.runtime.activated_runtime(qualification):
        guard(force=True)
        private = output / '.private/connection'; private.mkdir(mode=0o700)
        db, environment = host.restore.connection(config, guard.aws, {'masterUsername': guard.live['MasterUsername']}, private, guard)
        host.identity_only(context, db, config)
        settings = host.search_config(context, paths, envelope, private, environment)
        es = host.search.Elasticsearch(settings['elasticsearch']); identity = es.identity()
        expected = context['hosts']['elasticsearch']
        need(identity['clusterUuid'] == expected['clusterUuid'] and identity['imageId'] == expected['imageId']
             and es.alias(optional=True) is None and es.optional('/' + context['targetIndex']) is None
             and es.optional('/_snapshot/' + context['repositoryName']) is None, 'SNAPSHOT_ES_FRESH_NAMESPACE_IDENTITY')
        secrets = {'AIRBOB_NATIVE_SEARCH_DB_PASSWORD': environment['AIRBOB_ETL_DB_PASSWORD'],
            'AIRBOB_NATIVE_SEARCH_TRUST_PASSWORD': 'changeit'}
        previous = {key: os.environ.get(key) for key in secrets}
        os.environ.update(secrets)
        try:
            selected = context['snapshotLineage']
            receipt = engine.restore(settings, paths['companion'], descriptor, selected['binding'], selected['documents'],
                selected['envelope'], output / 'native', activate_alias=True, guard=guard)
        finally:
            for key, old in previous.items():
                if old is None:
                    os.environ.pop(key, None)
                else:
                    os.environ[key] = old
        guard(force=True)
        engine.validate_native_receipt(receipt, origin)
        need(receipt['state'] == 'SEARCH_RESTORED_AND_ACTIVATED'
             and host.sha(output / 'native/snapshot-target-baseline.json') == receipt['snapshotTargetBaselineSha256']
             and receipt['activeAlias'] == context['targetIndex'] and receipt['previousIndexRetained'] is None
             and receipt['elasticsearch'] == identity and receipt['mysql'] == package['mysql']
             and receipt['fullDocumentFingerprint'] == context['serviceManifest']['value']['search']['documentFingerprint']
             and receipt['nativeTransport']['manifestSha256'] == context['serviceManifest']['value']['search']['transport']['sha256'],
             'SNAPSHOT_NATIVE_RESULT_BINDING')
        host.identity_only(context, db, config)
    guard(force=True)
    raw = host.regular(output / 'native/search-restore-receipt.json')
    need(host.decode(raw) == receipt, 'SNAPSHOT_NATIVE_RESULT_BYTES')
    host.new_file(output / 'public/search-restore-receipt.json', raw)
    result = {'schemaVersion': 1, 'kind': HOST_KIND, 'state': 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED',
        'contextSha256': context_sha, 'sourcePackageSha256': package_sha, 'toolSources': context['toolSources'],
        **{key: context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease', 'rds', 'hosts')},
        'completedAt': dt.datetime.now(dt.timezone.utc).isoformat(), 'baselineOrigin': origin,
        'restoreReceipt': host.file_ref(output / 'public/search-restore-receipt.json'), 'finalAck': guard.ack.latest,
        'freshFullSourceAndOwnedComparisons': True, 'allDocumentSourceFieldsEqual': True,
        'sqlImportExecuted': False, 'businessSqlWritesExecuted': False, 'accountPreparationExecuted': False,
        'redisModified': False, 'cloudInfrastructureModified': False, 'sourceSealsChanged': False}
    host.write(output / 'public/host-receipt.json', result)
    return result


def validate_completion(context, context_sha, package, package_sha, native, receipt):
    keys(receipt, 'schemaVersion kind state contextSha256 sourcePackageSha256 toolSources runId datasetId operationId resourceFence lease rds hosts completedAt baselineOrigin restoreReceipt finalAck freshFullSourceAndOwnedComparisons allDocumentSourceFieldsEqual sqlImportExecuted businessSqlWritesExecuted accountPreparationExecuted redisModified cloudInfrastructureModified sourceSealsChanged', 'SNAPSHOT_HOST_COMPLETION_FIELDS')
    need(receipt['schemaVersion'] == 1 and receipt['kind'] == HOST_KIND
         and receipt['state'] == 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED'
         and receipt['contextSha256'] == context_sha and receipt['sourcePackageSha256'] == package_sha
         and receipt['toolSources'] == context['toolSources']
         and all(receipt[key] == context[key] for key in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease', 'rds', 'hosts')),
         'SNAPSHOT_HOST_COMPLETION_BINDING')
    need(hashlib.sha256(host.canonical(package)).hexdigest() == package_sha, 'SNAPSHOT_PACKAGE_SHA')
    envelope, _ = document(context['snapshotLineage']['envelope'])
    provenance, _ = document(context['snapshotLineage']['documents']['provenance'])
    base = engine.snapshot.validate_provenance(provenance)['sealedFingerprint']
    _, _, origin = validate_source_package(package, context, context_sha, envelope, base)
    need(receipt['baselineOrigin'] == origin
         and all(receipt[key] is False for key in ('sqlImportExecuted', 'businessSqlWritesExecuted', 'accountPreparationExecuted',
             'redisModified', 'cloudInfrastructureModified', 'sourceSealsChanged'))
         and receipt['freshFullSourceAndOwnedComparisons'] is True and receipt['allDocumentSourceFieldsEqual'] is True,
         'SNAPSHOT_COMPLETION_SCOPE')
    native_value = host.decode(native)
    engine.validate_native_receipt(native_value, origin)
    selected = context['serviceManifest']['value']['search']
    need(receipt['restoreReceipt'] == {'sha256': hashlib.sha256(native).hexdigest(), 'bytes': len(native)}
         and native_value['state'] == 'SEARCH_RESTORED_AND_ACTIVATED'
         and native_value['mysql'] == package['mysql'] and native_value['datasetId'] == host.DATASET
         and native_value['snapshotRelease'] == selected['snapshotRelease']
         and native_value['fullDocumentFingerprint'] == selected['documentFingerprint']
         and native_value['restoredIndex'] == native_value['activeAlias'] == context['targetIndex']
         and native_value['previousIndexRetained'] is None
         and native_value['elasticsearch']['clusterUuid'] == context['hosts']['elasticsearch']['clusterUuid']
         and native_value['elasticsearch']['image'] == context['hosts']['elasticsearch']['image']
         and native_value['elasticsearch']['imageId'] == context['hosts']['elasticsearch']['imageId']
         and native_value['nativeTransport']['manifestSha256'] == selected['transport']['sha256'], 'SNAPSHOT_NATIVE_COMPLETION_BINDING')
    ack = receipt['finalAck']
    keys(ack, 'sequence sha256 issuedAt expiresAt', 'SNAPSHOT_FINAL_ACK_FIELDS')
    need(host.integer(ack['sequence'], 0, 499) and host.digest(ack['sha256'])
         and host.integer(ack['issuedAt'], 1) and host.integer(ack['expiresAt'], ack['issuedAt'] + 1, ack['issuedAt'] + 90)
         and host.instant(package['exportedAt']) <= host.instant(receipt['completedAt']) < ack['expiresAt'] - 5
         and ack['issuedAt'] <= host.instant(receipt['completedAt'])
         and ack['expiresAt'] <= context['controllerDeadlineEpoch'], 'SNAPSHOT_COMPLETION_ACK_TIME')
    return {'state': receipt['state'], 'restoreReceipt': receipt['restoreReceipt'], 'sourcePackageSha256': package_sha}
