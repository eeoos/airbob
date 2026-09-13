"""Actual frozen proof/CLI contracts with synthetic public fixtures; no cloud."""
import copy
import contextlib
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import types
import time
import unittest
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import growth_b_search_host as host
from test_growth_b_service_contract import fixture as service_fixture

NOW = 1789314000


def timestamp(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).isoformat()


def checksum(raw):
    return hashlib.sha256(raw).hexdigest()


def remote(key, raw=b'{}\n'):
    return {'key': key, 'versionId': 'version-exact', 'sha256': checksum(raw), 'bytes': len(raw)}


def fixture():
    manifest = service_fixture()
    old_dataset, old_run = manifest['datasetId'], manifest['runId']
    run = 'lab-native-search-test'
    manifest = json.loads(json.dumps(manifest).replace(old_dataset, host.DATASET).replace(old_run, run))
    manifest['cdc'] = host.service.cdc_identity(run, manifest['rds']['serverUuid'])
    manifest['search']['restoreReceipt'] = None
    base = {'algorithm': 'sha256-pk-order-length-prefixed-jdbc-bytes-v1', 'mysqlVersion': '8.4.11',
        'domainHashExcludedColumns': {'member': ['password']},
        'tables': {name: {'rows': 1, 'rowsSha256': 'a'*64, 'domainRowsSha256': 'b'*64, 'ddlSha256': 'c'*64} for name in host.contract.TABLES}}
    base['tables']['flyway_schema_history']['rows'] = 28
    base['tables']['outbox']['rows'] = 0
    base['tables']['accommodation']['rows'] = 657358
    prepared = copy.deepcopy(base)
    prepared['tables']['member']['rowsSha256'] = 'd'*64
    prepared['tables']['accommodation_inventory_day']['rows'] += 99
    prepared['tables']['accommodation_inventory_day']['rowsSha256'] = 'e'*64
    raw_base, raw_prepared = host.canonical(base), host.canonical(prepared)
    runtime = {'state': 'TIMEZONE_RUNTIME_QUALIFIED', 'consumerRuntimePassed': True, 'passed': True,
               'consumerReleaseBindings': {'consumerHelperSha256': host.sources()['growth_b_runtime.py']}}
    raw_runtime = host.canonical(runtime)
    envelope = {'mysql': {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
        'appJarSha256': manifest['application']['appJarSha256'], 'consumerManifestSha256': '3'*64, 'checksumsSha256': '4'*64,
        'objects': {'migration-files.json': {'sha256': manifest['application']['migrationFilesSha256']}},
        'storage': {'publishedListings': 657358}}
    envelope_sha = checksum(host.canonical(envelope))
    prep_wrapper = {'schemaVersion': 1, 'kind': host.prepare.KIND, 'datasetId': host.DATASET,
        'account': host.ACCOUNT, 'region': host.REGION, 'scope': 'final-b-rds', 'mysql': {'version': '8.4.11', 'flywayVersion': 28},
        'files': {}, 'toolSources': {n: host.sources()[n] for n in host.prepare.TOOLS},
        'toolchain': {'system': 'Linux', 'architecture': 'x86_64', 'pythonVersion': '3.12.14', 'javaVersion': '21.0.12.1',
                      'mysqlVersion': '8.4.11', 'awsCliVersion': '2.34.64', 'unpackedBytes': 844524385},
        'storage': {'rdsAllocatedGiB': 100, 'dataHostRootGiB': 20, 'minimumStagingFreeBytes': 12*1024**3},
        'binlogAdditionalReserveBytes': 27*1024**3}
    for name, filename in dict(host.prepare.FILES, smallRdsReceipt='small-rds-receipt.json').items():
        item = remote('unused', host.canonical(envelope) if name == 'envelope' else b'{}\n')
        item['key'] = f'datasets/{host.DATASET}-aws-preparation/files/{item["sha256"]}-{filename}'
        prep_wrapper['files'][name] = item
    prep_wrapper_sha = checksum(host.canonical(prep_wrapper))
    environment = 'aws:' + manifest['rds']['resourceId'] + ':' + manifest['rds']['serverUuid']
    prep = {'passed': True, 'fullBaselineVerifiedBeforeCredentials': True, 'preparedFingerprintSha256': checksum(raw_prepared),
        'preparationResumed': False, 'applicationLeftRunning': False, 'temporarySessionsRemoved': True,
        'serviceCurrentlyAvailable': False, 'privateCredentials': {'usable': False, 'environment': environment},
        'accountLogins': {'crossCredentialRejected': True}, 'currentInventory': {'everyHorizonContiguous': True}}
    rds = manifest['rds'] | {'endpoint': f'airbob-{run}.example.ap-northeast-2.rds.amazonaws.com',
        'masterSecretArn': 'arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-123456-abcdef',
        'createdAt': timestamp(NOW-2000)}
    states = ['SQL_IMPORT_STARTED', 'SQL_IMPORT_COMPLETED', 'FULL_VALIDATION_STARTED', 'SEALED_DATABASE_VERIFIED',
              'PREPARATION_STARTED', 'DATABASE_INVENTORY_LOGIN_VERIFIED']
    times = {name: timestamp(NOW-1000+i*100) for i, name in enumerate(('sqlImportStartedAt', 'sqlImportCompletedAt',
        'fullValidationStartedAt', 'fullValidationCompletedAt', 'preparationStartedAt', 'preparationCompletedAt'))}
    raw = {'schemaVersion': 1, 'kind': 'global-growth-b-aws-restore-receipt', 'state': states[-1],
        'executionScope': 'final-b-rds', 'operation': 'replace-database', 'finalScaleSelected': True,
        'toolIdentity': host.restore.tool_identity(), 'datasetId': host.DATASET, 'account': host.ACCOUNT, 'region': host.REGION,
        'mysqlVersion': '8.4.11', 'flywayVersion': 28, 'awsWritesExecuted': True, 'allRowsAndDdlEqual': True,
        'previousBusinessSchemaAbsent': True, 'maximumSimultaneousBusinessDatabases': 1, 'deploymentReady': False,
        'targetIdentity': {k: rds[k] for k in ('identifier', 'resourceId', 'endpoint', 'serverUuid')},
        'rdsResourceId': rds['resourceId'], 'beforeDatabase': {'serverUuid': rds['serverUuid'], 'version': '8.4.11', 'tlsCipher': 'TLS_AES_256_GCM_SHA384'},
        'configSha256': manifest['preparation']['restoreConfigSha256'], 'envelopeSha256': envelope_sha,
        'appJarSha256': envelope['appJarSha256'], 'migrationFilesSha256': manifest['application']['migrationFilesSha256'],
        'sealedFingerprint': base, 'restoredFingerprintSha256': checksum(raw_base), 'preparedFingerprintSha256': checksum(raw_prepared),
        'preparation': prep, 'hostRuntimeQualification': {'path': f'/opt/airbob/global-b/{run}/execute/host-runtime-qualification.json',
            'sha256': checksum(raw_runtime), 'execution': 'current process host'},
        'events': [{'state': state, 'at': timestamp(NOW-1000+i*100+1)} for i, state in enumerate(states)], **times}
    raw_bytes = host.canonical(raw)
    standalone = remote(f'data-bootstrap/{run}/{host.DATASET}-standalone-rds.json', raw_bytes)
    receipt = {'schemaVersion': 1, 'kind': host.prepare.KIND, 'state': states[-1], 'datasetId': host.DATASET, 'runId': run,
        'manifestSha256': prep_wrapper_sha, 'rdsResourceId': rds['resourceId'], 'serverUuid': rds['serverUuid'],
        'restoreReceiptSha256': standalone['sha256'], 'standaloneReceiptObject': standalone, 'preparation': prep,
        'deploymentReady': False, 'applicationLeftRunning': False, 'privateAccountsUsable': False, 'albReady': False, 'kafkaCdcReady': False}
    receipt_bytes = host.canonical(receipt)
    receipt_ref = remote(f'data-bootstrap/{run}/{host.DATASET}.json', receipt_bytes)
    manifest['preparation'].update(receipt=receipt_ref, restoreReceiptSha256=standalone['sha256'], preparedFingerprintSha256=checksum(raw_prepared))
    context = {'schemaVersion': 1, 'kind': host.KIND, 'operationId': 'native-test-01', 'runId': run, 'datasetId': host.DATASET,
        'account': host.ACCOUNT, 'region': host.REGION, 'resourceFence': 74, 'expiresAt': host.DEADLINE,
        'approvedExecutionDeadlineEpoch': host.DEADLINE, 'controllerDeadlineEpoch': NOW+18000,
        'lease': {'table': 'airbob-performance-lab-orchestration-lease', 'lockName': 'airbob-performance-lab',
            'owner': 'repo/123:1@own', 'runId': run, 'command': 'up', 'fencingToken': 75},
        'hosts': {'preparation': {'instanceId': 'i-'+'1'*17}, 'elasticsearch': {'instanceId': 'i-'+'2'*17,
            'container': 'elasticsearch-1', 'containerId': 'a'*64, 'image': manifest['search']['image'],
            'imageId': 'sha256:'+'b'*64, 'clusterUuid': 'c'*22, 'startedAt': timestamp(NOW-100)}},
        'rds': rds,
        'serviceManifest': {'reference': remote(f'datasets/{host.DATASET}-aws-service/service-01/aws-service.json', host.canonical(manifest)), 'value': manifest},
        'preparationManifest': {'reference': remote(f'datasets/{host.DATASET}-aws-preparation/aws-preparation-{prep_wrapper_sha}.json', host.canonical(prep_wrapper)), 'value': prep_wrapper},
        'sourceRefs': {'preparationReceipt': {'reference': receipt_ref, 'value': receipt},
            'standaloneReceipt': {'reference': standalone, 'value': raw}},
        'targetIndex': 'accommodations-vnative-test-01', 'repositoryName': 'b_native_test_01', 'toolSources': host.sources()}
    blobs = {'preparationReceipt': receipt_bytes, 'standaloneReceipt': raw_bytes, 'restoredFingerprint': raw_base,
             'preparedFingerprint': raw_prepared, 'hostRuntimeQualification': raw_runtime}
    return context, blobs, envelope, base


def package_fixture():
    context, blobs, envelope, base = fixture()
    _, origin = host.validate_historical(context, blobs, envelope, base)
    context_sha = checksum(host.canonical(context))
    package = {'schemaVersion': 1, 'kind': host.PACKAGE_KIND, 'state': 'FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED',
        'contextSha256': context_sha, 'sourceToolSha256': context['toolSources']['growth_b_search_host.py'],
        **{k: context[k] for k in ('runId', 'datasetId', 'operationId', 'resourceFence', 'lease')},
        'exportedAt': timestamp(NOW), 'origin': origin, 'mysql': host.expected_mysql(context, envelope),
        'currentIdentityOnly': {'serverUuid': context['rds']['serverUuid'], 'version': '8.4.11', 'tlsVerified': True,
            'outboxRows': 0, 'otherClients': 0, 'currentFullRowsRevalidated': False, 'currentOwnedRowsRevalidated': False,
            'observedAt': timestamp(NOW-1)}, 'files': host.encode_blobs(blobs)}
    return context, context_sha, blobs, envelope, base, package


class FixedDatetime(dt.datetime):
    @classmethod
    def now(cls, tz=None):
        return cls.fromtimestamp(NOW, tz)


def phase_output(root, phase):
    output = root / phase; output.mkdir(mode=0o700)
    (output / '.private').mkdir(mode=0o700); (output / 'public').mkdir(mode=0o700)
    return output


def native_fixture(context, package, blobs):
    es = context['hosts']['elasticsearch']
    return {'schemaVersion': 1, 'state': 'SEARCH_RESTORED_AND_ACTIVATED', 'datasetId': host.DATASET,
        'snapshotRelease': context['serviceManifest']['value']['search']['snapshotRelease'],
        'restoredIndex': context['targetIndex'], 'activeAlias': context['targetIndex'], 'previousIndexRetained': None,
        'elasticsearch': {'clusterUuid': es['clusterUuid'], 'imageId': es['imageId'], 'image': es['image']},
        'mysql': package['mysql'], 'fullDocumentFingerprint': context['serviceManifest']['value']['search']['documentFingerprint'],
        'baselineReceiptSha256': checksum(host.canonical(host.baseline_receipt(package, blobs))),
        'nativeTransport': {'manifestSha256': context['serviceManifest']['value']['search']['transport']['sha256'],
            'exactVersionBytesVerified': True, 'sourceSealsChanged': False},
        'allDocumentSourceFieldsEqual': True, 'repositoryReadOnly': True,
        'nativeInventoryUnchanged': True, 'repositoryRegistrationRemoved': True}


def completed_fixture(context, context_sha, package, package_sha, native):
    return {'schemaVersion': 1, 'kind': 'global-b-aws-native-search-host-receipt', 'state': 'NATIVE_SEARCH_RESTORED_AND_SOURCE_VERIFIED',
        'contextSha256': context_sha, 'sourcePackageSha256': package_sha, 'toolSources': context['toolSources'],
        **{k: context[k] for k in ('runId','datasetId','operationId','resourceFence','lease','rds','hosts')},
        'completedAt': timestamp(NOW), 'baselineOrigin': package['origin'],
        'restoreReceipt': {'sha256': checksum(native), 'bytes': len(native)},
        'finalAck': {'sequence': 3, 'sha256': '1'*64, 'issuedAt': NOW-1, 'expiresAt': NOW+80},
        'freshFullSourceAndOwnedComparisons': True, 'allDocumentSourceFieldsEqual': True,
        **{k: False for k in ('sqlImportExecuted','businessSqlWritesExecuted','accountPreparationExecuted',
            'redisModified','cloudInfrastructureModified','sourceSealsChanged')}}


class Contracts(unittest.TestCase):
    def test_real_frozen_validators_accept_closed_final_fixture(self):
        context, blobs, envelope, base = fixture()
        self.assertIs(host.validate_context(context, now=NOW), context)
        docs, origin = host.validate_historical(context, blobs, envelope, base)
        self.assertEqual(base, docs['restoredFingerprint'])
        self.assertFalse(origin['currentFullRowsRevalidated'])
        self.assertFalse(origin['newBaselineCaptureExecuted'])

    def test_context_foreign_target_scope_namespace_or_old_lease_is_rejected(self):
        context, *_ = fixture()
        changes = [('datasetId', 'global-growth-b-'+'a'*16), ('resourceFence', 75), ('approvedExecutionDeadlineEpoch', host.DEADLINE+1),
                   ('controllerDeadlineEpoch', NOW+18001), ('targetIndex', 'accommodations-vother'), ('repositoryName', 'shared')]
        for key, value in changes:
            changed = copy.deepcopy(context); changed[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): host.validate_context(changed, now=NOW)
        for section, field in [('rds', 'serverUuid'), ('hosts', 'x')]:
            changed = copy.deepcopy(context)
            if section == 'rds': changed['rds']['serverUuid'] = 'ffffffff-ffff-ffff-ffff-ffffffffffff'
            else: changed['hosts']['x'] = {}
            with self.subTest(section=section), self.assertRaises(ValueError): host.validate_context(changed, now=NOW)

    def test_no_snapshot_or_preparation_resume_relabel(self):
        for key, value in [('state', 'SMALL_RDS_INVENTORY_LOGIN_VERIFIED'), ('operation', 'resume-preparation'),
                           ('sqlImportSkipped', True), ('allRowsAndDdlEqual', False)]:
            context, blobs, envelope, base = fixture()
            raw = context['sourceRefs']['standaloneReceipt']['value']; raw[key] = value
            self.rebind(context, blobs, 'standaloneReceipt', raw)
            with self.subTest(key=key), self.assertRaises(ValueError): host.validate_historical(context, blobs, envelope, base)

    @staticmethod
    def rebind(context, blobs, name, value):
        blobs[name] = host.canonical(value)
        context['sourceRefs'][name]['value'] = value
        context['sourceRefs'][name]['reference'].update(sha256=checksum(blobs[name]), bytes=len(blobs[name]))

    def test_full_fp_ddl_row_or_private_runtime_tampering_fails(self):
        for name in ('restoredFingerprint', 'preparedFingerprint', 'hostRuntimeQualification'):
            context, blobs, envelope, base = fixture()
            value = host.decode(blobs[name])
            if 'tables' in value: value['tables']['reservation']['rowsSha256'] = 'f'*64
            else: value['consumerRuntimePassed'] = False
            blobs[name] = host.canonical(value)
            with self.subTest(name=name), self.assertRaises(ValueError): host.validate_historical(context, blobs, envelope, base)

    def test_wrong_tool_or_uuid_cannot_be_hidden_by_valid_rehash(self):
        for field in ('toolIdentity', 'targetIdentity'):
            context, blobs, envelope, base = fixture()
            raw = context['sourceRefs']['standaloneReceipt']['value']
            raw[field] = dict(raw[field]); raw[field][next(iter(raw[field]))] = 'f'*64
            self.rebind(context, blobs, 'standaloneReceipt', raw)
            with self.subTest(field=field), self.assertRaises(ValueError): host.validate_historical(context, blobs, envelope, base)

    def test_import_chronology_duplicate_failure_and_reordered_stages_rejected(self):
        context, *_ = fixture()
        original = context['sourceRefs']['standaloneReceipt']['value']
        changes = [lambda r: r['events'].append(r['events'][0]),
                   lambda r: r['events'].reverse(),
                   lambda r: r['events'].append({'state': 'FAILED', 'at': timestamp(NOW)}),
                   lambda r: r.update(fullValidationCompletedAt=timestamp(NOW+1))]
        for mutate in changes:
            raw = copy.deepcopy(original); mutate(raw)
            with self.assertRaises(ValueError): host.chronology(raw)

    def test_inherited_baseline_passes_original_reader_without_recapturing(self):
        c, cs, blobs, env, base, p = package_fixture()
        host.validate_source_package(p, c, cs, env, base)
        source = types.SimpleNamespace(config={'datasetId': c['datasetId'], 'consumerManifestSha256': env['consumerManifestSha256'],
            'checksSha256': env['checksumsSha256']}, manifest={'appJarSha256': env['appJarSha256']}, base=base)
        with tempfile.TemporaryDirectory() as directory, patch.object(host.search, 'capture_baseline', side_effect=AssertionError('no capture')):
            path = Path(directory).resolve()/'baseline'
            receipt = host.derive_baseline(p, blobs, path)
            self.assertEqual(receipt, host.search.verify_baseline(source, path, p['mysql']))
            self.assertEqual(blobs['restoredFingerprint'], (path/'mysql-baseline-fingerprint.json').read_bytes())
            self.assertEqual(p['origin']['chronology']['fullValidationCompletedAt'], receipt['capturedAt'])
            self.assertIn('inherited proof', receipt['sourceReadFence'])
            self.assertFalse(receipt['origin']['newBaselineCaptureExecuted'])

    def test_package_cannot_claim_new_full_scan_or_replace_baseline(self):
        for mutate in (lambda p: p['origin'].update(currentFullRowsRevalidated=True),
                       lambda p: p['currentIdentityOnly'].update(currentOwnedRowsRevalidated=True),
                       lambda p: p['mysql'].update(publishedDocuments=657357),
                       lambda p: p['files']['restoredFingerprint'].update(base64='AAAA'),
                       lambda p: p.update(contextSha256='f'*64)):
            c, cs, _, env, base, package = package_fixture(); mutate(package)
            with self.assertRaises(ValueError): host.validate_source_package(package, c, cs, env, base)

    def test_private_values_are_never_public_proofs(self):
        for key in ('password', 'SecretString', 'Authorization', 'cookie', 'SessionToken'):
            with self.subTest(key=key), self.assertRaisesRegex(host.Rejected, 'PRIVATE_VALUE'):
                host.public_json({'nested': [{key: 'DO_NOT_PRINT_SECRET'}]})
        host.public_json({'individualPasswords': True, 'domainHashExcludedColumns': {'member': ['password']}})

    def test_sources_include_real_dynamic_transport_import_dependencies(self):
        names = host.sources()
        self.assertEqual(17, len(names))
        self.assertTrue({'growth_b_search_snapshot.py', 'growth_b_search_snapshot_bridge.py',
            'growth_b_snapshot.py', 'growth_b_snapshot_host.py'} <= set(names))
        self.assertTrue({'growth_b_search_transport.py','publish-growth-dataset-b.py','fetch-growth-dataset-b.py'} <= names.keys())
        self.assertEqual({'infra/aws/scripts/'+n:s for n,s in names.items()}, host.source_files())


class Acks(unittest.TestCase):
    def setUp(self):
        self.context, *_ = fixture(); self.context_sha = checksum(host.canonical(self.context))
        self.temp = tempfile.TemporaryDirectory(); self.directory = Path(self.temp.name).resolve(); self.directory.chmod(0o700)
    def tearDown(self): self.temp.cleanup()
    def ack(self, sequence=0, previous='0'*64, issued=NOW-1, expires=NOW+80):
        return {'schemaVersion': 1, 'kind': host.ACK_KIND, 'contextSha256': self.context_sha,
            'operationId': self.context['operationId'], 'runId': self.context['runId'], 'resourceFence': 74,
            'lease': {'owner': self.context['lease']['owner'], 'fencingToken': 75},
            'esInstanceId': self.context['hosts']['elasticsearch']['instanceId'], 'sequence': sequence,
            'issuedAt': issued, 'expiresAt': expires, 'previousSha256': previous}
    def save(self, value):
        host.write(self.directory / f'{value["sequence"]:06d}.json', value)
    def guard(self): return host.AckGuard(self.context,self.context_sha,self.directory,clock=lambda:NOW)
    def test_valid_chain_and_new_sequence(self):
        first=self.ack(); self.save(first); guard=self.guard(); one=guard()
        self.save(self.ack(1,one['sha256'],issued=NOW,expires=NOW+90)); two=guard()
        self.assertEqual(1,two['sequence'])
    def test_missing_expired_foreign_or_future_ack_blocks(self):
        with self.assertRaises(host.Rejected): self.guard()()
        for update in ({'expiresAt':NOW+5},{'expiresAt':NOW+100},{'issuedAt':NOW+3}, {'resourceFence':75},
                       {'contextSha256':'f'*64},{'esInstanceId':'i-'+'3'*17},{'previousSha256':'f'*64}, {'lease':{'owner':'other','fencingToken':75}}):
            with self.subTest(update=update):
                value=self.ack();value.update(update);self.save(value)
                with self.assertRaises(host.Rejected): self.guard()()
                for path in self.directory.iterdir():path.unlink()
    def test_old_ack_bytes_cannot_change_and_gap_rejected(self):
        self.save(self.ack());guard=self.guard();guard()
        path=self.directory/'000000.json';path.write_bytes(host.canonical(self.ack(issued=NOW-2)))
        with self.assertRaisesRegex(host.Rejected,'ACK_BYTES_CHANGED'):guard()
        path.unlink();self.save(self.ack(2))
        with self.assertRaisesRegex(host.Rejected,'ACK_SEQUENCE'):self.guard()()
    def test_symlink_and_wrong_mode_rejected(self):
        self.save(self.ack());p=self.directory/'000000.json';p.chmod(0o644)
        with self.assertRaisesRegex(host.Rejected,'PRIVATE_FILE_MODE'):self.guard()()
        p.unlink();target=self.directory.parent/'ack-target-do-not-read';target.write_bytes(b'{}')
        try:
            p.symlink_to(target)
            with self.assertRaises(host.Rejected):self.guard()()
        finally:target.unlink()


class ExecutionBoundaries(unittest.TestCase):
    def test_es_role_never_uses_unavailable_dynamodb_or_asg_permission(self):
        c,*_=fixture()
        with patch.object(host.restore,'Aws'):
            aws=host.HostAws(c,'restore-on-es')
            for args in [('dynamodb','get-item'),('autoscaling','describe-auto-scaling-groups'),('rds','delete-db-instance')]:
                with self.assertRaisesRegex(host.Rejected,'HOST_AWS_OPERATION_FORBIDDEN'):aws.call(*args)
            aws.client.call.assert_not_called()
    def test_existing_lock_open_has_no_create_and_never_signals_old_process(self):
        c,*_=fixture()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d).resolve(); lock=root/'control.lock';lock.write_bytes(b'');lock.chmod(0o600)
            (root/'process-group').write_text('123 456\n');(root/'process-group').chmod(0o600)
            with patch.object(host,'retained_root',return_value=root),patch.object(host,'process_identity',return_value={'startTicks':456}),patch.object(host.os,'killpg') as kill:
                with self.assertRaisesRegex(host.Rejected,'ORIGINAL_PREPARATION_STILL_RUNNING'):
                    with host.original_control_lock(c):pass
                kill.assert_not_called()
            lock.unlink()
            with patch.object(host,'retained_root',return_value=root):
                with self.assertRaises(FileNotFoundError):
                    with host.original_control_lock(c):pass
                self.assertFalse(lock.exists())
    def test_wrong_process_birth_never_gets_a_signal(self):
        process=types.SimpleNamespace(pid=123)
        with patch.object(host,'process_identity',return_value={'startTicks':999,'processGroup':123}),patch.object(host.os,'killpg') as kill:
            with self.assertRaisesRegex(host.Rejected,'WORKER_PROCESS_IDENTITY_CHANGED'):host.stop_own_worker(process,{'startTicks':1},tree=MagicMock())
            kill.assert_not_called()
    def test_child_group_cleanup_is_bounded_and_only_exact_group(self):
        process=MagicMock(pid=123);process.wait.return_value=0;process.returncode=0
        tree=MagicMock(root_observed=True);tree.remaining.return_value=[]
        with patch.object(host,'process_identity',return_value={'startTicks':1,'processGroup':123}),patch.object(host.os,'killpg') as kill:
            self.assertTrue(host.stop_own_worker(process,{'startTicks':1},tree=tree))
            self.assertEqual([(123,signal.SIGINT)],[c.args for c in kill.call_args_list])
            tree.signal_descendants.assert_any_call(signal.SIGINT)
    def test_frozen_source_restore_call_preserves_all_core_gates(self):
        source=Path(host.__file__).read_text()
        self.assertIn('search.restore(settings, paths[\'companion\'], descriptor, baseline, output / \'native\', activate_alias=True)',source)
        self.assertNotIn('capture_baseline(',source)
        self.assertNotIn('search.Elasticsearch =',source)
        self.assertNotIn('search.SourceAdapter =',source)
    def test_duplicate_json_and_existing_evidence_are_rejected(self):
        with self.assertRaisesRegex(host.Rejected,'DUPLICATE_JSON_KEY'):host.decode(b'{"a":1,"a":2}')
        with tempfile.TemporaryDirectory() as d:
            path=Path(d).resolve()/'proof.json';host.write(path,{'a':1})
            with self.assertRaises(FileExistsError):host.write(path,{'a':2})
            self.assertEqual({'a':1},host.read(path))


class PhaseIntegration(unittest.TestCase):
    def test_source_phase_exports_original_bytes_with_no_current_full_scan(self):
        c, cs, blobs, envelope, base, package = package_fixture()
        guard = MagicMock(); guard.live = {'MasterUsername': 'admin'}
        observed = {'version': '8.4.11', 'serverUuid': c['rds']['serverUuid'], 'businessSchemas': ['airbobdb'],
            'tableObjects': 28, 'otherClients': 0, 'tlsCipher': 'TLS_AES_256_GCM_SHA384'}
        db = MagicMock(); db.scalar.return_value = 0
        frozen_validate = host.prepare.validate_preparation_receipt
        with tempfile.TemporaryDirectory() as d, contextlib.ExitStack() as stack:
            root = Path(d).resolve(); output = phase_output(root, 'prepare-source')
            env_path = root/'envelope.json'; host.write(env_path, envelope)
            raw_path = root/'original-restore.json'; host.new_file(raw_path, blobs['standaloneReceipt'])
            paths = {'ca': root/'ca.pem', 'envelope': env_path}
            stack.enter_context(patch.object(host, 'load_inputs', return_value=(paths,envelope,base,{})))
            stack.enter_context(patch.object(host, 'historical_blobs', return_value=({},blobs)))
            stack.enter_context(patch.object(host.prepare, 'validate_preparation_receipt',
                side_effect=lambda manifest,context,receipt,*_: frozen_validate(manifest,context,receipt,raw_path,env_path)))
            stack.enter_context(patch.object(host.service, 'verify_debezium'))
            stack.enter_context(patch.object(host.runtime, 'activated_runtime', return_value=contextlib.nullcontext()))
            stack.enter_context(patch.object(host.restore, 'connection', return_value=(db,{})))
            stack.enter_context(patch.object(host.restore, 'database_state', return_value=observed))
            fingerprint = stack.enter_context(patch.object(host.restore, 'fingerprint', side_effect=AssertionError('no duplicate scan')))
            owned = stack.enter_context(patch.object(host.restore, 'owner_fingerprint', side_effect=AssertionError('no duplicate scan')))
            capture = stack.enter_context(patch.object(host.search, 'capture_baseline', side_effect=AssertionError('no new capture')))
            stack.enter_context(patch.object(host,'dt',types.SimpleNamespace(datetime=FixedDatetime,timezone=dt.timezone)))
            result = host.prepare_source(c,cs,output,guard=guard)
            self.assertEqual('FROZEN_IMPORT_BASELINE_PROOFS_EXPORTED',result['state'])
            self.assertEqual(blobs,host.decode_blobs(result['files']))
            self.assertFalse(result['currentIdentityOnly']['currentFullRowsRevalidated'])
            self.assertFalse(result['currentIdentityOnly']['currentOwnedRowsRevalidated'])
            db.scalar.assert_called_once_with('SELECT COUNT(*) FROM outbox')
            for call in (fingerprint,owned,capture): call.assert_not_called()
            self.assertEqual(result,host.read(output/'public/source-package.json'))

    def exercise_restore(self, *, fail=False):
        c, cs, blobs, envelope, base, package = package_fixture()
        guard = MagicMock(); guard.live = {'MasterUsername': 'admin'}
        guard.ack.latest = {'sequence':3,'sha256':'1'*64,'issuedAt':NOW-1,'expiresAt':NOW+80}
        native = native_fixture(c,package,blobs)
        es = MagicMock(); es.identity.return_value = native['elasticsearch']; es.alias.return_value = None; es.optional.return_value = None
        with tempfile.TemporaryDirectory() as d, contextlib.ExitStack() as stack:
            root=Path(d).resolve();output=phase_output(root,'restore-on-es');p=root/'source-package.json';host.write(p,package)
            descriptor=root/'descriptor.json';host.write(descriptor,{'descriptor':True})
            paths={'descriptor':descriptor,'transport':root/'transport.json','companion':root/'companion','ca':root/'ca.pem'}
            envelope['publicationReceiptSha256']='1'*64
            marker={'sql':{'publicationReceiptSha256':'1'*64},'source':{'consumerManifestSha256':envelope['consumerManifestSha256'],
                    'checksSha256':envelope['checksumsSha256']}}
            stack.enter_context(patch.object(host,'load_inputs',return_value=(paths,envelope,base,{})))
            stack.enter_context(patch.object(host.transport,'validate_published_transport',return_value=marker))
            stack.enter_context(patch.object(host.runtime,'activated_runtime',return_value=contextlib.nullcontext()))
            stack.enter_context(patch.object(host.restore,'connection',return_value=(MagicMock(),{'AIRBOB_ETL_DB_PASSWORD':'PRIVATE_VALUE'})))
            stack.enter_context(patch.object(host,'identity_only',return_value={}))
            stack.enter_context(patch.object(host,'search_config',return_value={'elasticsearch':{'container':'selected'},'targetIndex':c['targetIndex']}))
            stack.enter_context(patch.object(host.search,'Elasticsearch',return_value=es))
            stack.enter_context(patch.object(host,'dt',types.SimpleNamespace(datetime=FixedDatetime,timezone=dt.timezone)))
            def actual_call(settings, companion, desc, baseline, native_output, *, activate_alias):
                self.assertTrue(activate_alias)
                self.assertEqual(c['targetIndex'],settings['targetIndex'])
                self.assertEqual(blobs['restoredFingerprint'],(baseline/'mysql-baseline-fingerprint.json').read_bytes())
                self.assertEqual(native['baselineReceiptSha256'],host.sha(baseline/'baseline-receipt.json'))
                self.assertEqual('PRIVATE_VALUE',os.environ['AIRBOB_NATIVE_SEARCH_DB_PASSWORD'])
                native_output.mkdir(mode=0o700)
                if fail: raise ValueError('UNTRUSTED PRIVATE_VALUE')
                host.new_file(native_output/'search-restore-receipt.json',host.canonical(native));return native
            core=stack.enter_context(patch.object(host.search,'restore',side_effect=actual_call))
            stack.enter_context(patch.dict(os.environ,{'AIRBOB_NATIVE_SEARCH_DB_PASSWORD':'previous'},clear=False))
            if fail:
                with self.assertRaises(ValueError):host.restore_on_es(c,cs,p,host.sha(p),output,guard=guard)
                self.assertFalse((output/'public/host-receipt.json').exists())
                self.assertFalse((output/'public/search-restore-receipt.json').exists())
            else:
                result=host.restore_on_es(c,cs,p,host.sha(p),output,guard=guard)
                actual=(output/'public/search-restore-receipt.json').read_bytes()
                self.assertEqual(host.canonical(native),actual)
                host.validate_completion(c,cs,package,host.sha(p),actual,result)
                self.assertNotIn('PRIVATE_VALUE',host.canonical(result).decode())
            core.assert_called_once()
            self.assertEqual('previous',os.environ['AIRBOB_NATIVE_SEARCH_DB_PASSWORD'])

    def test_frozen_restore_is_invoked_once_with_inherited_baseline_and_alias_gate(self):
        self.exercise_restore()

    def test_failed_native_operation_preserves_namespace_without_public_success(self):
        self.exercise_restore(fail=True)

    def test_completion_rejects_namespace_source_opaque_extra_and_expired_ack(self):
        c,cs,blobs,_,_,package=package_fixture();ps=checksum(host.canonical(package))
        native=host.canonical(native_fixture(c,package,blobs)); receipt=completed_fixture(c,cs,package,ps,native)
        host.validate_completion(c,cs,package,ps,native,receipt)
        for mutate in (lambda r:r.update(unreviewed=True),lambda r:r.update(sourcePackageSha256='f'*64),
            lambda r:r['finalAck'].update(expiresAt=NOW+5),lambda r:r.update(businessSqlWritesExecuted=True)):
            altered=copy.deepcopy(receipt);mutate(altered)
            with self.assertRaises(host.Rejected):host.validate_completion(c,cs,package,ps,native,altered)
        for key,value in [('baselineReceiptSha256','f'*64),('activeAlias','accommodations-vforeign'),
                         ('nativeInventoryUnchanged',False)]:
            changed=host.decode(native);changed[key]=value;raw=host.canonical(changed)
            altered=copy.deepcopy(receipt);altered['restoreReceipt']={'sha256':checksum(raw),'bytes':len(raw)}
            with self.assertRaises(host.Rejected):host.validate_completion(c,cs,package,ps,raw,altered)
        changed=copy.deepcopy(package);changed['exportedAt']=timestamp(NOW-1)
        with self.assertRaises(host.Rejected):host.validate_completion(c,cs,changed,ps,native,receipt)


class SupervisorFailures(unittest.TestCase):
    def attempt(self, root, *, cloud_error=None, poll_error=None, cleanup_error=False, evidence_error=False):
        c, *_ = fixture(); op=root/'operation';op.mkdir(mode=0o700)
        context_path=root/'context.json';host.write(context_path,c)
        process=MagicMock(pid=321);process.poll.return_value=None;process.returncode=0
        if poll_error: process.poll.side_effect=poll_error
        guard=MagicMock()
        if cloud_error:guard.side_effect=cloud_error
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(host,'op_root',return_value=op))
            stack.enter_context(patch.object(host.time,'time',return_value=NOW))
            stack.enter_context(patch.object(host,'process_identity',side_effect=lambda pid:{'pid':pid,'processGroup':pid,'startTicks':77,'state':'S'}))
            stack.enter_context(patch.object(host,'LiveGuard',return_value=guard))
            start=stack.enter_context(patch.object(host.subprocess,'Popen',return_value=process))
            stack.enter_context(patch.object(host,'OwnedProcessTree'))
            stop=stack.enter_context(patch.object(host,'stop_own_worker',return_value=True))
            if cleanup_error:stack.enter_context(patch.object(host.shutil,'rmtree',side_effect=OSError('SECRET_ERROR')))
            if evidence_error:
                original_write=host.write
                def failed_evidence(path,value):
                    if Path(path).name=='failure.json':raise OSError('PRIVATE_DISK_ERROR')
                    return original_write(path,value)
                stack.enter_context(patch.object(host,'write',side_effect=failed_evidence))
            with self.assertRaises(host.Rejected) as caught:
                host.run(context_path,'prepare-source',op/'prepare-source')
            return str(caught.exception),op/'prepare-source',start.call_count,stop.call_count

    def test_guard_failure_prevents_any_child_and_removes_owned_private_directory(self):
        with tempfile.TemporaryDirectory() as d:
            code,out,starts,stops=self.attempt(Path(d).resolve(),cloud_error=host.Rejected('ACK_EXPIRED'))
            self.assertEqual('ACK_EXPIRED',code);self.assertEqual((0,0),(starts,stops))
            failure=host.read(out/'public/failure.json')
            self.assertTrue(failure['ownedWorkerTerminal']);self.assertTrue(failure['privateMaterialRemoved'])
            self.assertFalse((out/'.private').exists())

    def test_inflight_guard_failure_cleans_own_child_and_preserves_original_error(self):
        with tempfile.TemporaryDirectory() as d:
            calls=[None,host.Rejected('ACK_EXPIRED')]
            code,out,starts,stops=self.attempt(Path(d).resolve(),cloud_error=calls,cleanup_error=True)
            self.assertEqual('ACK_EXPIRED',code);self.assertEqual((1,1),(starts,stops))
            failure=host.read(out/'public/failure.json')
            self.assertEqual('OWNED_PRIVATE_CLEANUP_UNCERTAIN',failure['cleanupCode'])
            self.assertFalse(failure['privateMaterialRemoved']);self.assertTrue((out/'.private').is_dir())
            self.assertNotIn('SECRET_ERROR',host.canonical(failure).decode())

    def test_evidence_fsync_failure_does_not_replace_guard_failure(self):
        with tempfile.TemporaryDirectory() as d:
            code,_,starts,_=self.attempt(Path(d).resolve(),cloud_error=host.Rejected('ACK_EXPIRED'),evidence_error=True)
            self.assertEqual('ACK_EXPIRED',code);self.assertEqual(0,starts)

    def test_signal_interrupt_enters_own_cleanup_and_restores_original_handlers(self):
        previous={s:signal.getsignal(s) for s in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP)}
        with host.interrupted_by_signals():
            for selected in previous:
                handler=signal.getsignal(selected)
                with self.assertRaises(KeyboardInterrupt):handler(selected,None)
        self.assertEqual(previous,{s:signal.getsignal(s) for s in previous})
        with tempfile.TemporaryDirectory() as d:
            code,_,starts,stops=self.attempt(Path(d).resolve(),poll_error=KeyboardInterrupt())
            self.assertEqual('HOST_OPERATION_FAILED',code);self.assertEqual((1,1),(starts,stops))

    def test_parent_intent_fsync_failure_never_starts_worker(self):
        c,*_=fixture()
        with tempfile.TemporaryDirectory() as d,contextlib.ExitStack() as stack:
            root=Path(d).resolve();context_path=root/'context.json';host.write(context_path,c)
            op=root/'operation';op.mkdir()
            stack.enter_context(patch.object(host,'op_root',return_value=op))
            stack.enter_context(patch.object(host.time,'time',return_value=NOW))
            stack.enter_context(patch.object(host,'process_identity',return_value={'pid':1,'processGroup':1,'startTicks':3,'state':'S'}))
            stack.enter_context(patch.object(host,'write',side_effect=OSError('fsync failed')))
            start=stack.enter_context(patch.object(host.subprocess,'Popen'))
            with self.assertRaises(OSError):host.run(context_path,'prepare-source',op/'prepare-source')
            start.assert_not_called()


class ProcessTreeTests(unittest.TestCase):
    @staticmethod
    def row(pid,parent,group,ticks,state='S'):
        return {'pid':pid,'parentPid':parent,'processGroup':group,'startTicks':ticks,'state':state}

    def test_only_exact_descendant_births_are_recorded_and_signalled(self):
        root=self.row(10,1,10,100);child=self.row(11,10,11,110);grand=self.row(12,11,12,120)
        foreign=self.row(13,1,13,130);rows={v['pid']:v for v in (root,child,grand,foreign)}
        with patch.object(host,'process_snapshot',side_effect=lambda:copy.deepcopy(rows)),patch.object(host,'process_identity',side_effect=lambda pid:rows.get(pid)),patch.object(host.os,'kill') as kill:
            tree=host.OwnedProcessTree(root)
            self.assertEqual({(10,100),(11,110),(12,120)},set(tree.owned))
            rows[11]=self.row(11,1,11,999)  # PID reused after the original child died.
            rows[12]['parentPid']=1       # Previously observed own orphan remains ours.
            tree.signal_descendants(signal.SIGINT)
            self.assertEqual([(12,signal.SIGINT)],[call.args for call in kill.call_args_list])
            self.assertEqual(3,len(tree.owned))

    def test_unclean_worker_exit_cannot_claim_complete_ancestry_or_private_cleanup(self):
        root=self.row(10,1,10,100);process=MagicMock(pid=10);process.returncode=-signal.SIGKILL
        tree=MagicMock(root_observed=True);tree.remaining.return_value=[]
        with patch.object(host,'process_identity',return_value=None),patch.object(host.os,'killpg'):
            self.assertFalse(host.stop_own_worker(process,root,tree=tree))

    def test_actual_local_separate_process_group_is_detected_and_drained(self):
        # The OS groups/signals/waits are real. The small metadata adapter below
        # supplies birth tokens on macOS; Linux /proc lineage parsing is tested
        # independently, without scanning or signalling any existing process.
        with tempfile.TemporaryDirectory() as d:
            root=Path(d).resolve();record=root/'child.json'
            code='''import json,os,signal,subprocess,sys,time
signal.signal(signal.SIGINT,lambda *_:None)
child=subprocess.Popen([sys.executable,'-B','-c','import signal,time; signal.signal(signal.SIGINT,lambda *_:exit(0)); time.sleep(60)'],start_new_session=True)
with open(sys.argv[1],'w') as f: json.dump({'worker':os.getpid(),'child':child.pid},f)
child.wait()
'''
            process=subprocess.Popen([sys.executable,'-B','-c',code,str(record)],start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            child_id=None
            try:
                deadline=time.monotonic()+5
                while not record.exists() and time.monotonic()<deadline:time.sleep(.02)
                value=json.loads(record.read_text());child_id=value['child']
                self.assertNotEqual(os.getpgid(process.pid),os.getpgid(child_id))
                def identity(pid):
                    if pid not in (process.pid,child_id):return None
                    try:group=os.getpgid(pid)
                    except ProcessLookupError:return None
                    return self.row(pid,os.getpid() if pid==process.pid else process.pid,group,100 if pid==process.pid else 101)
                def snapshot():
                    process.poll()
                    return {pid:row for pid in (process.pid,child_id) if (row:=identity(pid)) is not None}
                with patch.object(host,'process_snapshot',side_effect=snapshot),patch.object(host,'process_identity',side_effect=identity):
                    tree=host.OwnedProcessTree(identity(process.pid))
                    self.assertIn((child_id,101),tree.owned)
                    # stop_own_worker itself delivers to the observed separate
                    # child, allowing the real worker to reap it and exit.
                    self.assertTrue(host.stop_own_worker(process,tree.root,tree=tree))
                    self.assertIsNone(identity(child_id))
                    self.assertEqual(0,process.returncode)
            finally:
                for pid in (child_id,process.pid):
                    if pid:
                        try:os.killpg(pid,signal.SIGKILL)
                        except ProcessLookupError:pass
                process.wait(timeout=5)


class IndependentWorkerTests(unittest.TestCase):
    def test_es_ack_expiry_and_parent_birth_change_close_local_worker_guard(self):
        c,cs,*_=package_fixture()
        parent={'pid':123,'startTicks':456,'state':'S'}
        with tempfile.TemporaryDirectory() as d,patch.object(host.os,'getppid',return_value=123),patch.object(host,'process_identity',return_value=parent):
            guard=host.WorkerWatchdog(c,cs,'restore-on-es',Path(d).resolve(),parent,clock=lambda:NOW)
            guard.ack=MagicMock(side_effect=host.Rejected('ACK_EXPIRED_OR_CLEANUP_MARGIN'))
            with self.assertRaisesRegex(host.Rejected,'ACK_EXPIRED'):guard.check()
            guard.ack.assert_called_once_with()
            with patch.object(host,'process_identity',return_value=parent|{'startTicks':457}):
                with self.assertRaisesRegex(host.Rejected,'WORKER_SUPERVISOR_LOST'):guard.check()
            with patch.object(host.os,'getppid',return_value=1):
                with self.assertRaisesRegex(host.Rejected,'WORKER_SUPERVISOR_REPARENTED'):guard.check()

    def test_actual_outer_parent_death_interrupts_unchanged_frozen_read_subprocess(self):
        c,*_=fixture()
        with tempfile.TemporaryDirectory() as d:
            root=Path(d).resolve();context_path=root/'context.json';host.write(context_path,c)
            output=phase_output(root,'worker-output')
            runner=root/'worker.py'
            worker_code=r'''import contextlib,json,os,pathlib,sys,time
sys.path.insert(0,sys.argv[1])
import growth_b_search_host as h
root=pathlib.Path(sys.argv[2]); c=h.read(root/'context.json'); parent_pid=os.getppid()
original_identity=h.process_identity
def identity(pid):
 if pathlib.Path('/proc/self/stat').exists(): return original_identity(pid)
 if pid not in {parent_pid,os.getpid()}:
  path=root/'frozen-read.pid'
  if not path.exists() or pid!=int(path.read_text()): return None
 try: group=os.getpgid(pid)
 except ProcessLookupError: return None
 return {'pid':pid,'parentPid':os.getppid() if pid==os.getpid() else os.getpid(),
  'processGroup':group,'startTicks':1 if pid==parent_pid else 2 if pid==os.getpid() else 3,'state':'S'}
h.process_identity=identity
if not pathlib.Path('/proc/self/stat').exists():
 def snapshot():
  candidates=[parent_pid,os.getpid()]
  if (root/'frozen-read.pid').exists(): candidates.append(int((root/'frozen-read.pid').read_text()))
  return {p:r for p in candidates if (r:=identity(p)) is not None}
 h.process_snapshot=snapshot
parent=identity(parent_pid)
OriginalWatchdog=h.WorkerWatchdog
h.WorkerWatchdog=lambda *a,**kw: OriginalWatchdog(*a,**kw,clock=lambda:__NOW__,interval=.05)
h.original_control_lock=lambda _:contextlib.nullcontext()
def frozen_read(*_,**__):
 code="import os,pathlib,sys,time; pathlib.Path(sys.argv[1]).write_text(str(os.getpid())); time.sleep(60)"
 h.restore.command([sys.executable,'-B','-c',code,str(root/'frozen-read.pid')],timeout=60)
 raise AssertionError('read unexpectedly completed')
h.prepare_source=frozen_read
try:
 h.worker(c,h.sha(root/'context.json'),'prepare-source',root/'worker-output',parent_identity=parent)
except BaseException as e:
 code=str(e) if isinstance(e,h.Rejected) else type(e).__name__
 h.write(root/'worker-done.json',{'closedCode':code})
'''.replace('__NOW__',str(NOW))
            runner.write_text(worker_code);runner.chmod(0o600)
            controller_code="import pathlib,subprocess,sys,time; p=subprocess.Popen([sys.executable,'-B',sys.argv[1],sys.argv[2],sys.argv[3]],start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL); pathlib.Path(sys.argv[3]+'/worker.pid').write_text(str(p.pid)); time.sleep(60)"
            parent=subprocess.Popen([sys.executable,'-B','-c',controller_code,str(runner),str(Path(host.__file__).parent),str(root)],
                start_new_session=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            owned=[]
            try:
                end=time.monotonic()+8
                while not (root/'frozen-read.pid').exists() and time.monotonic()<end:time.sleep(.02)
                self.assertTrue((root/'frozen-read.pid').exists(),'local frozen command must have started')
                worker_pid=int((root/'worker.pid').read_text());read_pid=int((root/'frozen-read.pid').read_text());owned=[worker_pid,read_pid]
                self.assertNotEqual(os.getpgid(worker_pid),os.getpgid(read_pid))
                parent.kill();parent.wait(timeout=5)
                end=time.monotonic()+8
                while not (root/'worker-done.json').exists() and time.monotonic()<end:time.sleep(.02)
                result=host.read(root/'worker-done.json')
                self.assertIn(result['closedCode'],('WORKER_SUPERVISOR_REPARENTED','WORKER_SUPERVISOR_LOST'))
                event=host.read(output/'public/worker-watchdog-failure.json')
                self.assertEqual(result['closedCode'],event['failureCode'])
                self.assertFalse(event['automaticResubmissionAllowed'])
                # The original frozen restore.command finally reaps its own
                # separate-session reader even after its supervisor disappeared.
                with self.assertRaises(ProcessLookupError):os.getpgid(read_pid)
            finally:
                for pid in owned:
                    try:os.killpg(pid,signal.SIGKILL)
                    except ProcessLookupError:pass
                if parent.poll() is None:parent.kill()
                parent.wait(timeout=5)


if __name__=='__main__':unittest.main()
