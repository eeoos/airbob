"""Offline R4 receipt production, actual frozen snapshot admission and fences."""
import contextlib
import copy
import datetime as dt
import json
import os
from pathlib import Path
import sys
import time
from types import SimpleNamespace
import unittest
import uuid
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
import growth_b_service_verify as verify
from test_growth_b_snapshot import SnapshotFixture
from test_growth_b_cdc_aws import configuration as cdc_configuration, NOW


def instant(epoch):
    return dt.datetime.fromtimestamp(epoch, dt.timezone.utc).isoformat()


class ServiceFixture(SnapshotFixture):
    def setUp(self):
        super().setUp()
        self.root = self.root.resolve()
        self.saved_umask = os.umask(0o077); self.addCleanup(os.umask, self.saved_umask)
        croot = self.root / 'cdc'; croot.mkdir()
        self.cdc, self.manifest, self.ready = cdc_configuration(croot)
        self.old = copy.deepcopy(self.cdc)
        self.cdc.update(businessStartedEpoch=NOW + 200, businessDeadlineEpoch=NOW + 1100)
        self.patch(verify, 'supervisor_sha', return_value='a' * 64)
        self.supervisor = SimpleNamespace(app_program=lambda config, ready: 'immutable cache=false policy\n' +
            verify.controller.host_program('app', 'observe', config, runtime=ready['appRuntime']))
        item = patch.dict(sys.modules, {'growth_b_cdc_supervisor': self.supervisor}); item.start(); self.addCleanup(item.stop)
        self.patch(verify.time, 'time', return_value=NOW + 500)
        self.patch(verify, 'now', return_value=instant(NOW + 600))
        self.value.update(rds=copy.deepcopy(self.cdc['rds']), privateAccounts=self.configuration['privateHandoff']['path'])
        self.target = verify.restore.target_identity(self.value)
        self.envelope.update(datasetId=self.cdc['datasetId'], appJarSha256=self.manifest['application']['appJarSha256'])
        self.envelope['objects']['migration-files.json']['sha256'] = self.manifest['application']['migrationFilesSha256']
        self.proof.update(targetIdentity=self.target, datasetId=self.cdc['datasetId'], appJarSha256=self.envelope['appJarSha256'],
                          migrationFilesSha256=self.envelope['objects']['migration-files.json']['sha256'])
        self.raw_ref = self.write('original-restore.json', self.proof)
        self.config_ref = self.write('original-config.json', self.value)
        self.inputs = {'configuration': self.cdc, 'restoreConfig': self.value, 'restoreReceipt': self.proof,
            'envelope': self.envelope, 'initialFingerprint': copy.deepcopy(self.baseline), 'toolIdentity': verify.source_identity(),
            'cdc': {'config': self.cdc, 'manifest': self.manifest, 'readiness': self.ready,
                    'binding': {'toolIdentity': verify.cdc.source_identity(), 'datasetId': self.cdc['datasetId']}}}
        runtime = {'passed': True, 'consumerRuntimePassed': True, 'hostJavaTools': {'java': {'sha256': 'f' * 64}},
            'qualificationSourceSha256': 'a' * 64, 'fixedCasesSha256': 'b' * 64, 'scope': {'zones': ['Asia/Seoul']},
            'python': {'version': '3.12.14'}, 'java': {'tzdbFile': {'sha256': 'c' * 64}},
            'consumerReleaseBindings': {'files': {'tool-sources.json': 'd' * 64}}}
        rref = self.write('runtime.json', runtime)
        self.runtime = rref | {'execution': 'current process host'}
        self.representatives, self.warmed, self.images = self.read_receipts()
        observations = {'readiness': {'status': 'UP', 'httpStatus': 200}, 'loopbackRelayClosed': True,
            'representatives': self.write('representatives.json', self.representatives),
            'warmup': self.write('warmup.json', self.warmed), 'media': self.write('media.json', self.images)}
        before = self.app_observation(NOW + 5, 1)
        after = self.app_observation(NOW + 160, 4)
        self.live = {'schemaVersion': 1, 'kind': verify.LIVE_KIND, 'state': 'LIVE_READS_OBSERVED',
            'startedAt': instant(NOW + 10), 'completedAt': instant(NOW + 150), 'hostRuntimeQualification': self.runtime,
            'binding': {'configurationSha256': verify.cdc.journal_binding(self.old), 'sharedBindingSha256': verify.shared_binding(self.old),
                'restoreConfigSha256': self.config_ref['sha256'], 'restoreReceiptSha256': self.raw_ref['sha256'],
                'toolIdentity': self.inputs['toolIdentity']}, 'observations': observations}
        self.live_ref = self.write('live.json', self.live)
        self.attestation = {'schemaVersion': 1, 'kind': 'global-b-aws-service-live-attestation',
            'operationId': self.cdc['operationId'], 'supervisorSha256': 'a' * 64,
            'liveConfiguration': self.write('live-config.json', self.old), 'cdcConfiguration': self.write('cdc-config.json', self.cdc),
            'liveConfigurationSha256': verify.cdc.journal_binding(self.old), 'cdcConfigurationSha256': verify.cdc.journal_binding(self.cdc),
            'sharedBindingSha256': verify.shared_binding(self.cdc), 'liveReceiptSha256': self.live_ref['sha256'], 'before': before, 'after': after}
        self.cdc_proof = self.cdc_receipt()
        self.closed = self.close_attestation()
        self.producer = {'schemaVersion': 1, 'kind': verify.KIND, 'operation': 'finalize',
            'cdcConfiguration': self.write('selected-cdc.json', self.cdc), 'restoreConfig': self.config_ref, 'restoreReceipt': self.raw_ref,
            'controllerObservation': self.write('close.json', self.closed), 'deadlineEpoch': NOW + 10000,
            'liveReceipt': self.live_ref, 'liveAttestation': self.write('attestation.json', self.attestation),
            'cdcReceipt': self.write('post-reset.json', self.cdc_proof), 'controllerReceipt': self.controller_ref}
        self.inputs['value'] = self.producer
        self.guard = MagicMock()
        self.measured = copy.deepcopy(self.baseline)
        self.owners = self.proof['preparation']['ownerSha256BeforeAndAfter']
        self.calendar = {'localDateVector': {'Asia/Seoul': '2027-01-15'}, 'currentInventory': {'everyHorizonContiguous': True}}
        self.db = MagicMock(timeout=600, guard=MagicMock())
        self.patch(verify.restore, 'exclusive_database', side_effect=lambda db: contextlib.nullcontext())
        self.patch(verify.snapshot, 'frozen_tables', side_effect=lambda db: contextlib.nullcontext({'method': 'all-business-table-read-locks', 'released': True}))
        self.identity = self.patch(verify.snapshot, 'database_identity')
        self.inventory = self.patch(verify.snapshot, 'current_inventory', side_effect=lambda db, runtime: copy.deepcopy(self.calendar))
        self.owner_call = self.patch(verify.restore, 'owner_fingerprint', side_effect=lambda db: self.owners)
        self.fingerprint_call = self.patch(verify.restore, 'fingerprint', side_effect=self.fingerprint)

    def patch(self, target, name, *args, **kwargs):
        item = patch.object(target, name, *args, **kwargs); result = item.start(); self.addCleanup(item.stop); return result

    def read_receipts(self):
        binding = {key: self.old[key] for key in ('consumerManifestSha256', 'checksumsSha256', 'accountEnvironment')}
        representatives = {'kind': 'global-b-dev-representative-http-reads', 'state': 'AWS_B_REPRESENTATIVE_HTTP_READS_VERIFIED',
            'datasetId': self.cdc['datasetId'], 'targetMode': 'aws', 'passed': True, 'binding': binding,
            'accounts': [{'account': key, 'passed': True, 'normalLoginAndIdentityVerified': True,
                          'adminAuthorizationVerified': True, 'logoutInvalidatesSession': True} for key in ('demo', 'host', 'admin')],
            'observations': [{'check': key, 'passed': True} for key in ('search-korea', 'search-north-america', 'owned-listings')]}
        for row in representatives['accounts']:
            if row['account'] != 'admin':
                row.update(sealedReservationSampleMatches=True, sealedAuthoredReviewMatches=True,
                           sealedListingSampleMatches=True, receivedReservationOwnershipMatches=True)
        warmed = {'kind': 'global-b-after-verification-service-warmup', 'state': 'AFTER_VERIFICATION_WARMUP_COMPLETED',
            'datasetId': self.cdc['datasetId'], 'targetMode': 'aws', 'passed': True, 'binding': binding,
            'successfulWorkloadGetCount': 300, 'unfinishedTargets': [], 'cleanupFailures': [],
            'targets': [{'successfulSamples': 20} for _ in range(15)]}
        date = dt.datetime.fromtimestamp(NOW + 20, dt.timezone.utc).astimezone(verify.media.ZoneInfo('Asia/Seoul')).date()
        end = verify.media.plus_months(date, 3)
        listing = {'currentLocalDateVerified': True, 'reservableNights': (end - date).days, 'unavailableNights': 0,
                   'windowDays': (end - date).days, 'localDate': date.isoformat(), 'endExclusive': end.isoformat(), 'timeZone': 'Asia/Seoul'}
        images = {'kind': 'global-b-public-media-and-availability', 'state': 'PUBLIC_MEDIA_AVAILABILITY_VERIFIED',
            'datasetId': self.cdc['datasetId'], 'passed': True, 'toolSha256': verify.contract.sha(verify.media.__file__),
            'decoderSha256': verify.media.DECODER_SHA, 'consumerManifestSha256': self.old['consumerManifestSha256'],
            'startedAt': instant(NOW + 20), 'completedAt': instant(NOW + 140),
            'checksumsSha256': self.old['checksumsSha256'], 'images': [
                {'region': region, 'fullyDecoded': True, 'status': 200, 'width': 1, 'height': 1, 'contentSha256': 'a' * 64}
                for region in ('korea', 'north-america')], 'listings': [copy.deepcopy(listing), copy.deepcopy(listing)]}
        return representatives, warmed, images

    def command(self, program, pin, when, token=1, name='app-observe'):
        return {'token': token, 'name': name, 'instanceId': pin['instanceId'], 'containerId': pin['containerId'],
            'commandSha256': verify.core.digest(program.encode()), 'startedEpoch': when - 1, 'completedEpoch': when,
            'commandId': str(uuid.uuid4()), 'status': 'Success'}

    def app_observation(self, when, token):
        app = self.old['hosts']['app']
        observation = dict(app, finishedAt='0001-01-01T00:00:00.000000000Z', running=True, normalProfile='aws', readiness=True,
            appJarSha256=self.ready['appRuntime']['imageJarSha256'], runtimeRevision=self.old['asg']['runtimeRevision'])
        return {'schemaVersion': 1, 'kind': 'global-b-aws-service-app-observation', 'operationId': self.old['operationId'],
            'configurationSha256': verify.cdc.journal_binding(self.old), 'lease': self.old['lease'], 'supervisorSha256': 'a' * 64,
            'command': self.command(self.supervisor.app_program(self.old, self.ready), app, when, token), 'observation': observation}

    def cdc_receipt(self):
        return {'schemaVersion': 1, 'kind': 'global-b-aws-owned-api-cdc-verification', 'action': 'post-reset', 'phasePassed': True,
            'operationId': self.cdc['operationId'], 'runId': self.cdc['runId'], 'mysqlServerUuid': self.cdc['mysqlServerUuid'],
            'inputBinding': self.inputs['cdc']['binding'], 'failureCode': None, 'sessionCleanupFailureCode': None,
            'apiRoundTripVerified': True, 'completedAt': instant(NOW + 300),
            'businessWindow': {'normalPatchCount': 2, 'normalSessionInvalidated': True,
                'deadlineEpoch': self.cdc['businessDeadlineEpoch'], 'completedEpoch': NOW + 250},
            'cleanup': {'historyRowsRemoved': 2, 'outboxRowsRemoved': 4, 'accommodationColumnsRestored': ['updated_at', 'updated_by'],
                'historyColumnsRestored': ['valid_to'], 'autoIncrementRestored': True, 'binlogKeptEnabled': True, 'writerFenceRestored': True},
            'postResetHealth': {'heartbeatAdvanced': True, 'esOriginalNameMatches': True, 'committedOffsets': {'0': 4, '1': 4}},
            'steps': [{'step': n, 'httpConfirmed': True, 'outboxRowsObserved': 2, 'propagation': {
                'step': n, 'eventId': str(uuid.uuid4()), 'esNameMatches': True,
                'records': [{'partition': n - 1, 'offset': 3}], 'committedOffsets': {str(n - 1): 4}}} for n in (1, 2)]}

    def close_attestation(self):
        pin = self.cdc['hosts']['connect'] | {'startedAt': '2027-01-02T00:00:00.000000000Z'}
        observed = dict(pin, finishedAt='2027-01-03T00:00:00.000000000Z', running=False)
        command = self.command(verify.controller.host_program('connect', 'stop', self.cdc, pin=pin), pin, NOW + 495,
                               token=20, name='source-connect-stop')
        terminated = ['i-' + '8' * 17]
        closed = {'writersStopped': True, 'cdcStopped': True, 'replacementInstanceIds': terminated, 'cycleSequence': 10,
            'zeroObservation': {'terminatedInstanceIds': terminated, 'stableSinceEpoch': NOW + 475, 'observedEpoch': NOW + 490,
                'stableSeconds': 15, 'observations': 4, 'activeScalingActivityIds': [], 'liveAppInstanceIds': [], 'serverAtomicCapacityCasAvailable': False},
            'connectFinishedAt': observed['finishedAt'], 'commandId': command['commandId'], 'lease': self.cdc['lease'], 'sourceSnapshotGateSatisfied': False}
        self.controller_proof = {'kind': 'global-b-aws-cdc-controller-receipt', 'phasePassed': True, 'action': 'close',
            'configurationSha256': verify.cdc.journal_binding(self.cdc), 'toolIdentity': verify.cdc.source_identity(),
            'operationId': self.cdc['operationId'], 'runId': self.cdc['runId'], 'outstandingCommands': [],
            'twoPatchRuntimeVerified': True, 'ownedResetConfirmed': True, 'postResetHealthConfirmed': True,
            'sourceClosed': closed, 'completedAt': instant(NOW + 498)}
        self.controller_ref = self.write('controller.json', self.controller_proof)
        return {'schemaVersion': 1, 'kind': 'global-b-aws-service-source-close-attestation',
            'configurationSha256': verify.cdc.journal_binding(self.cdc), 'operationId': self.cdc['operationId'],
            'lease': self.cdc['lease'], 'supervisorSha256': 'a' * 64, 'controllerReceiptSha256': self.controller_ref['sha256'],
            'command': command, 'observation': observed, 'sourceClosed': closed, 'observedEpoch': NOW + 499}

    @contextlib.contextmanager
    def runtime_context(self, inputs, output, guard):
        yield self.root, self.db, {}, self.runtime

    def fingerprint(self, runtime, release, env, output, timeout, guard):
        self.write(str(output.relative_to(self.root)), self.measured)
        return copy.deepcopy(self.measured)

    def finalize(self, directory='result'):
        return verify.finalize_reset(self.producer, self.root / directory, inputs=self.inputs,
                                     guard=self.guard, runtime_context=self.runtime_context)

    def rebind(self, key, value):
        path = Path(self.producer[key]['path'])
        self.producer[key] = self.write(str(path.relative_to(self.root)), value)


class FinalServiceVerificationTest(ServiceFixture):
    def test_input_admission_binds_actual_original_restore_and_rejects_partial_or_small_claims(self):
        configuration = copy.deepcopy(self.cdc)
        configuration.update(releaseDirectory=self.value['release'], privateAccounts=self.value['privateAccounts'])
        configuration['preconditions']['preparedFingerprint'] = self.write('admission-prepared.json', self.baseline)
        self.envelope.update(consumerManifestSha256=configuration['consumerManifestSha256'], checksumsSha256=configuration['checksumsSha256'])
        proof = self.proof | {'preparedFingerprintSha256': configuration['preconditions']['preparedFingerprint']['sha256']}
        manifest = copy.deepcopy(self.manifest)
        manifest['preparation']['restoreConfigSha256'] = self.config_ref['sha256']
        reference = self.write('admission-restore.json', proof)
        manifest['preparation']['restoreReceiptSha256'] = reference['sha256']
        selected = {key: value for key, value in self.producer.items()
                    if key not in ('liveReceipt', 'liveAttestation', 'cdcReceipt', 'controllerReceipt')}
        selected.update(operation='live', cdcConfiguration=self.write('admission-cdc.json', configuration), restoreReceipt=reference)
        with patch.object(verify.cdc, 'read_inputs', return_value={'config': configuration, 'manifest': manifest, 'readiness': self.ready}), \
             patch.object(verify.restore, 'configuration', return_value=self.value), \
             patch.object(verify.restore, 'validate_inputs', return_value=self.envelope):
            observed = verify.read_inputs(selected)
            self.assertEqual(observed['restoreReceipt'], proof)
            for key, changed in (('state', 'SQL_IMPORT_COMPLETED'), ('executionScope', 'small-rds-rehearsal'),
                                 ('finalScaleSelected', False), ('awsWritesExecuted', False)):
                value = proof | {key: changed}
                selected['restoreReceipt'] = self.write('admission-restore.json', value)
                manifest['preparation']['restoreReceiptSha256'] = selected['restoreReceipt']['sha256']
                with self.subTest(key=key), self.assertRaisesRegex(verify.Failed, 'ACTUAL_FINAL_RESTORE_PROOF_REQUIRED'):
                    verify.read_inputs(selected)

    def test_actual_full_fingerprint_produces_receipt_accepted_by_unchanged_snapshot_core(self):
        report = self.finalize()
        self.assertEqual(report['state'], 'SERVICE_VERIFIED_AND_RESET')
        self.assertEqual(report['restoreReceiptSha256'], self.raw_ref['sha256'])
        self.assertEqual(report['preparedFingerprintSha256'], verify.contract.sha(self.root / 'result/prepared-fingerprint.json'))
        self.assertFalse(report['snapshotCreated']); self.assertFalse(report['databaseBusinessWritesPerformed'])
        self.assertTrue(report['service']['detailCacheDisabledVerified'])
        self.identity.assert_called(); self.fingerprint_call.assert_called_once(); self.owner_call.assert_called_once()
        config = {'restoreReceipt': self.raw_ref, 'preparedFingerprint': verify.ref(self.root / 'result/prepared-fingerprint.json'),
            'serviceResetReceipt': verify.ref(self.root / 'result/service-reset-receipt.json'),
            'privateHandoff': self.configuration['privateHandoff'],
            'application': {key: self.manifest['application'][key] for key in ('mainCommit', 'image')}}
        self.assertEqual(verify.snapshot.validate_source_evidence(config, self.value, self.envelope)[1], self.measured)
        raw = (self.root / 'result/service-reset-receipt.json').read_text()
        self.assertNotIn('PRIVATE_HANDOFF_CONTENT', raw)
        self.assertNotIn('password', raw.lower())

    def test_staged_versioned_restore_ref_preserves_sha_and_normalizes_only_frozen_core_interface(self):
        self.producer['restoreReceipt'] = self.raw_ref | {'key': 'data-bootstrap/run/raw-restore-receipt.json',
            'versionId': 'exact-version-01', 'bytes': Path(self.raw_ref['path']).stat().st_size}
        verify.validate_config(self.producer, check_files=False)
        report = self.finalize()
        self.assertEqual(report['restoreReceiptSha256'], self.raw_ref['sha256'])

    def test_full_domain_ddl_member_credential_owner_and_outbox_drift_each_block_success(self):
        original = copy.deepcopy(self.measured)
        mutations = [lambda: self.measured['tables']['accommodation'].update(rowsSha256='0' * 64),
            lambda: self.measured['tables']['reservation'].update(ddlSha256='0' * 64),
            lambda: self.measured['tables']['member'].update(rowsSha256='0' * 64),
            lambda: setattr(self, 'owners', '0' * 64), lambda: self.measured['tables']['outbox'].update(rows=1)]
        for index, mutate in enumerate(mutations):
            self.measured = copy.deepcopy(original); self.owners = self.proof['preparation']['ownerSha256BeforeAndAfter']; mutate()
            with self.subTest(index=index), self.assertRaises((ValueError, verify.Failed)):
                self.finalize('failure-' + str(index))
            self.assertFalse((self.root / ('failure-' + str(index)) / 'service-reset-receipt.json').exists())

    def test_new_free_rows_with_exact_owned_history_publish_new_current_fingerprint(self):
        self.measured['tables']['accommodation_inventory_day'].update(rows=3, rowsSha256='e' * 64, domainRowsSha256='f' * 64)
        report = self.finalize()
        self.assertNotEqual(report['preparedFingerprintSha256'], self.configuration['preparedFingerprint']['sha256'])
        self.assertEqual(report['reset']['ownerSha256BeforeAndAfter'], self.owners)

    def test_local_date_rollover_during_full_scan_blocks_snapshot_evidence(self):
        self.inventory.side_effect = [self.calendar, self.calendar | {'localDateVector': {'Asia/Seoul': '2027-01-16'}}]
        with self.assertRaisesRegex(verify.Failed, 'CROSSED_LISTING_LOCAL_DATE'):
            self.finalize()
        self.assertFalse((self.root / 'result/service-reset-receipt.json').exists())

    def test_failed_cdc_missing_offsets_or_incomplete_reset_are_never_synthesized(self):
        for index, mutate in enumerate((lambda v: v.update(phasePassed=False),
            lambda v: v['steps'][0]['propagation'].update(records=[]),
            lambda v: v['postResetHealth'].update(committedOffsets={'0': 3, '1': 4}),
            lambda v: v['cleanup'].update(outboxRowsRemoved=0), lambda v: v.update(apiRoundTripVerified=False))):
            value = copy.deepcopy(self.cdc_proof); mutate(value); self.rebind('cdcReceipt', value)
            with self.subTest(index=index), self.assertRaises(verify.Failed):
                self.finalize('bad-cdc-' + str(index))
        self.fingerprint_call.assert_not_called()

    def test_fresh_close_requires_actual_stopped_connect_success_command_and_zero_capacity_evidence(self):
        for index, mutate in enumerate((lambda v: v.update(observedEpoch=NOW),
            lambda v: v['command'].update(status='TimedOut'), lambda v: v['command'].update(commandSha256='0' * 64),
            lambda v: v['observation'].update(running=True))):
            value = copy.deepcopy(self.closed); mutate(value); self.rebind('controllerObservation', value)
            with self.subTest(index=index), self.assertRaises(verify.Failed):
                self.finalize('bad-close-' + str(index))
        self.fingerprint_call.assert_not_called()

    def test_live_to_cdc_mapping_allows_only_lease_and_business_epochs(self):
        verify.validate_live_attestation(self.attestation, self.live, self.cdc, self.ready)
        modified = copy.deepcopy(self.cdc); modified['hosts']['app']['startedAt'] = '2027-01-02T00:00:00.000000000Z'
        self.assertNotEqual(verify.shared_binding(modified), verify.shared_binding(self.cdc))
        changed = copy.deepcopy(self.attestation); changed['cdcConfiguration'] = self.write('changed-cdc.json', modified)
        with self.assertRaisesRegex(verify.Failed, 'CONFIGURATION_MAPPING_CHANGED'):
            verify.validate_live_attestation(changed, self.live, modified, self.ready)

    def test_before_after_ssm_exact_app_runtime_and_chronology_required(self):
        for mutate in (lambda v: v['after']['observation'].update(startedAt='2027-01-02T00:00:00.000000000Z'),
                       lambda v: v['after']['command'].update(startedEpoch=NOW + 1),
                       lambda v: v['before']['command'].update(commandSha256='0' * 64)):
            candidate = copy.deepcopy(self.attestation); mutate(candidate)
            with self.assertRaises(verify.Failed):
                verify.validate_live_attestation(candidate, self.live, self.cdc, self.ready)

    def test_representative_login_media_global_search_and_warmup_each_need_actual_receipt(self):
        for index, mutate in enumerate((lambda r, w, i: r['accounts'][0].update(logoutInvalidatesSession=False),
            lambda r, w, i: r.update(observations=[{'check': 'search-korea', 'passed': True}]),
            lambda r, w, i: w.update(successfulWorkloadGetCount=299),
            lambda r, w, i: i['images'][0].update(fullyDecoded=False),
            lambda r, w, i: i['listings'][0].update(reservableNights=0))):
            values = copy.deepcopy((self.representatives, self.warmed, self.images)); mutate(*values)
            with self.subTest(index=index), self.assertRaises(verify.Failed):
                verify.validate_read_observations(*values, self.inputs)

    def test_runtime_binary_or_temporal_change_between_live_and_finalize_is_rejected(self):
        candidate = verify.read_ref({key: self.runtime[key] for key in ('path', 'sha256')})
        candidate['python']['version'] = '3.12.13'
        other = self.write('other-runtime.json', candidate) | {'execution': 'current process host'}
        with self.assertRaisesRegex(verify.Failed, 'SERVICE_RUNTIME_CHANGED'):
            verify.same_runtime(self.runtime, other)

    def test_configuration_rejects_manual_success_booleans_and_changed_ref_bytes(self):
        verify.validate_config(self.producer)
        with self.assertRaises(verify.Failed):
            verify.validate_config(self.producer | {'readinessPassed': True})
        Path(self.producer['liveReceipt']['path']).write_text('{}')
        with self.assertRaisesRegex(verify.Failed, 'LOCAL_REFERENCE_SHA_CHANGED'):
            verify.validate_config(self.producer)

    def test_actual_client_options_use_exclusive_defaults_and_verified_tls_without_reconnect(self):
        database = verify.StrictDatabase('/private/mysql.cnf', 100, lambda: None, self.cdc)
        argv = database.command()
        self.assertEqual(argv[1], '--defaults-file=/private/mysql.cnf')
        self.assertIn('--ssl-mode=VERIFY_IDENTITY', argv); self.assertIn('--skip-reconnect', argv)
        self.assertNotIn('--defaults-extra-file=/private/mysql.cnf', argv)
        self.assertNotIn('--password', ' '.join(argv))

    def test_live_receipt_requires_after_controller_attestation_before_full_snapshot_claim(self):
        selected = {key: value for key, value in self.producer.items()
                    if key not in ('liveReceipt', 'liveAttestation', 'cdcReceipt', 'controllerReceipt')}
        selected.update(operation='live', cdcConfiguration=self.attestation['liveConfiguration'],
                        controllerObservation=self.write('before-live.json', self.app_observation(NOW + 495, 30)))
        inputs = self.inputs | {'value': selected, 'configuration': self.old}
        runner = MagicMock(return_value=self.live['observations'])
        report = verify.verify_live(selected, self.root / 'live-output', inputs=inputs, guard=self.guard,
                                   http_runner=runner, runtime_context=self.runtime_context)
        runner.assert_called_once()
        self.assertEqual(report['state'], 'LIVE_READS_OBSERVED')
        self.assertTrue(report['postReadAppIdentityAttestationRequired'])
        self.assertTrue(report['detailCacheDisabledVerified'])
        self.assertFalse(report['sourceSnapshotGateSatisfied'])
        self.assertFalse((self.root / 'live-output/service-reset-receipt.json').exists())

    def test_live_pipeline_keeps_exact_three_credentials_private_and_uses_guarded_loopback(self):
        output = self.root / 'http-output'; output.mkdir(); (output / '.private').mkdir(mode=0o700)
        private = self.root / 'private-input'; private.mkdir(mode=0o700)
        bundle = {'schemaVersion': 2, 'datasetProfile': 'global-growth-b', 'environment': self.old['accountEnvironment'],
                  'credentials': [{'memberId': n, 'password': 'PRIVATE_LOCAL_PASSWORD_' + str(n)} for n in (1, 2, 3, 4)]}
        accounts = private / 'accounts.private.json'; accounts.write_text(json.dumps(bundle)); accounts.chmod(0o600)
        self.write('release/representative-accounts.json', {'accounts': [{'memberId': n} for n in (1, 2, 3)]})
        self.write('release/SHA256SUMS.json', {'base-scenario-targets.json': 'f' * 64})
        config = self.old | {'privateAccounts': str(accounts), 'releaseDirectory': str(self.release)}
        inputs = self.inputs | {'configuration': config}
        before = accounts.read_bytes()
        relay = MagicMock(); relay.__enter__.return_value = relay; relay.base = 'http://127.0.0.1:43219'
        response = MagicMock(); response.__enter__.return_value = response
        response.status = 200; response.headers = {}; response.read.return_value = b'{"status":"UP"}'
        opener = MagicMock(); opener.open.return_value = response
        calls = []
        def representative(args, **kwargs):
            selected = json.loads(Path(args.private_representatives).read_bytes())
            self.assertEqual([v['memberId'] for v in selected['credentials']], [1, 2, 3])
            self.assertEqual(Path(args.private_representatives).stat().st_mode & 0o777, 0o600)
            self.assertEqual(args.target_mode, 'aws'); self.assertEqual(args.base_url, relay.base)
            calls.append('representative')
            path = Path(self.live['observations']['representatives']['path'])
            return self.representatives, path, verify.contract.sha(path)
        def warmer(config, output, **kwargs):
            self.assertEqual(config['baseUrl'], relay.base); self.assertEqual(config['targetMode'], 'aws')
            calls.append('warm')
            path = Path(self.live['observations']['warmup']['path'])
            return self.warmed, path, verify.contract.sha(path)
        def images(*args, **kwargs):
            self.assertEqual(args[4], relay.base); self.assertEqual(kwargs['guard'], relay.check)
            calls.append('media')
            path = Path(self.live['observations']['media']['path'])
            return self.images, path, verify.contract.sha(path)
        with patch.object(verify.urllib.request, 'build_opener', return_value=opener):
            result = verify.verify_http(inputs, output, self.guard, relay_factory=lambda *args: relay,
                representative_runner=representative, warm_runner=warmer, media_runner=images)
        self.assertEqual(calls, ['representative', 'warm', 'media'])
        self.assertTrue(result['loopbackRelayClosed']); self.assertEqual(accounts.read_bytes(), before)
        self.assertNotIn('PRIVATE_LOCAL_PASSWORD', json.dumps(result))


class LiveControlGuardTest(ServiceFixture):
    def live_guard(self, *, frozen=False):
        config = self.cdc
        inputs = self.inputs | {'value': self.producer | {'operation': 'finalize' if frozen else 'live'}}
        tags = [{'Key': key, 'Value': value} for key, value in verify.cdc_host.run_tags(config).items()]
        self.rds = {'DBInstanceIdentifier': config['rds']['identifier'], 'DbiResourceId': config['rds']['resourceId'],
            'Endpoint': {'Address': config['rds']['endpoint'], 'Port': 3306}, 'Engine': 'mysql', 'EngineVersion': '8.4.11',
            'DBInstanceStatus': 'available', 'PubliclyAccessible': False, 'MasterUserSecret': {'SecretArn': config['rds']['masterSecretArn']},
            'TagList': tags, 'MasterUsername': 'test-master'}
        self.group = {'AutoScalingGroupName': config['asg']['name'], 'AutoScalingGroupARN': config['asg']['arn'],
            'LaunchTemplate': config['asg']['launchTemplate'], 'Tags': tags, 'MinSize': 0 if frozen else 1,
            'DesiredCapacity': 0 if frozen else 1, 'MaxSize': 0 if frozen else 1, 'Instances': [] if frozen else [
                {'InstanceId': config['hosts']['app']['instanceId'], 'LifecycleState': 'InService', 'HealthStatus': 'Healthy',
                 'LaunchTemplate': config['asg']['launchTemplate']}]}
        pin = config['hosts']['connect']
        self.connect = {'Id': pin['containerId'], 'Image': pin['imageId'], 'Config': {'Image': pin['image']},
            'State': {'Paused': False, 'Restarting': False, 'Running': not frozen,
                      'StartedAt': self.closed['observation']['startedAt'] if frozen else pin['startedAt'],
                      'FinishedAt': self.closed['observation']['finishedAt'] if frozen else '0001-01-01T00:00:00.000000000Z'}}
        def aws_call(*args):
            if args[:2] == ('sts', 'get-caller-identity'): return {'Account': verify.cdc.ACCOUNT}
            if args[:2] == ('rds', 'describe-db-instances'): return {'DBInstances': [self.rds]}
            if args[:2] == ('autoscaling', 'describe-auto-scaling-groups'): return {'AutoScalingGroups': [self.group]}
            raise AssertionError('Unexpected AWS action')
        self.aws = MagicMock(); self.aws.call.side_effect = aws_call
        self.patch(verify.restore, 'Lease', return_value=MagicMock())
        self.patch(verify.cdc_host, 'imds_identity', return_value={key: pin[key] for key in ('instanceId', 'privateIp')})
        self.patch(verify.core, 'command', side_effect=lambda *args, **kwargs: verify.core.encoded([self.connect]))
        guard = verify.Guard(inputs, aws=self.aws, clock=lambda: NOW + 500)
        if frozen: guard.close_observation = self.closed['observation']
        return guard

    def test_exact_live_target_reobserved_and_only_read_actions_used(self):
        guard = self.live_guard()
        with patch.dict(os.environ, {}, clear=True):
            guard(force=True)
        self.assertEqual({tuple(call.args[:2]) for call in self.aws.call.call_args_list},
                         {('sts', 'get-caller-identity'), ('rds', 'describe-db-instances'), ('autoscaling', 'describe-auto-scaling-groups')})

    def test_deadline_and_credentials_override_fail_before_service_operations(self):
        guard = self.live_guard(); guard.deadline = NOW + 499
        with self.assertRaisesRegex(verify.Failed, 'DEADLINE'):
            guard(force=True)
        self.aws.call.assert_not_called()
        guard.deadline = NOW + 1000
        with patch.dict(os.environ, {'AWS_PROFILE': 'unapproved'}), self.assertRaisesRegex(verify.Failed, 'INSTANCE_PROFILE_ONLY'):
            guard(force=True)

    def test_rds_resource_asg_instance_or_connect_lifetime_drift_fails(self):
        guard = self.live_guard()
        with patch.dict(os.environ, {}, clear=True):
            guard(force=True)
            self.rds['DbiResourceId'] = 'db-' + 'Z' * 24
            with self.assertRaisesRegex(verify.Failed, 'RDS_IDENTITY_CHANGED'): guard(force=True)
            self.rds['DbiResourceId'] = self.cdc['rds']['resourceId']
            self.group['Instances'][0]['InstanceId'] = 'i-' + '9' * 17
            with self.assertRaisesRegex(verify.Failed, 'EXACT_ONE_APP_REQUIRED'): guard(force=True)
            self.group['Instances'][0]['InstanceId'] = self.cdc['hosts']['app']['instanceId']
            self.connect['State']['StartedAt'] = 'changed'
            with self.assertRaisesRegex(verify.Failed, 'CONNECT_LIFETIME_CHANGED'): guard(force=True)


    def test_final_fence_requires_asg_zero_and_exact_stopped_connect(self):
        guard = self.live_guard(frozen=True)
        with patch.dict(os.environ, {}, clear=True):
            guard(force=True)
            self.group['DesiredCapacity'] = 1
            with self.assertRaisesRegex(verify.Failed, 'ALL_APP_WRITERS_MUST_BE_STOPPED'): guard(force=True)
            self.group['DesiredCapacity'] = 0; self.connect['State']['Running'] = True
            with self.assertRaisesRegex(verify.Failed, 'CONNECT_LIFETIME_CHANGED'): guard(force=True)


class RelayBoundaryTest(unittest.TestCase):
    def server(self):
        class Server:
            def __init__(self, address, handler):
                self.bound, self.handler = address, handler
                self.server_address = ('127.0.0.1', 43210)
            def serve_forever(self, **kwargs): pass
            def shutdown(self): pass
            def server_close(self): pass
        return Server

    def test_only_loopback_listener_and_exact_private_app_8080_are_used(self):
        guard = MagicMock()
        peer = MagicMock(); request = MagicMock(); request.recv.side_effect = [b'PRIVATE_HTTP_BYTES', b'']
        with patch.object(verify.socketserver, 'ThreadingTCPServer', self.server()), \
             patch.object(verify.socket, 'create_connection', return_value=peer) as connection, \
             patch.object(verify.select, 'select', return_value=([request], [], [])):
            with verify.LoopbackRelay('10.42.1.11', time.monotonic() + 5, guard) as relay:
                self.assertEqual(relay.server.bound, ('127.0.0.1', 0))
                handler = relay.server.handler.__new__(relay.server.handler)
                handler.request, handler.client_address = request, ('127.0.0.1', 55555)
                handler.handle()
                relay.check()
                connection.assert_called_once_with(('10.42.1.11', 8080), timeout=5)
                peer.sendall.assert_called_once_with(b'PRIVATE_HTTP_BYTES')
                self.assertEqual(relay.base, 'http://127.0.0.1:43210')
            self.assertTrue(relay.stop.is_set())

    def test_relay_error_is_closed_and_no_runtime_admission_is_monkeypatched(self):
        guard = MagicMock()
        with patch.object(verify.socketserver, 'ThreadingTCPServer', self.server()), \
             patch.object(verify.socket, 'create_connection', side_effect=OSError('PRIVATE_RAW_MESSAGE')):
            with verify.LoopbackRelay('10.42.1.11', time.monotonic() + 5, guard) as relay:
                handler = relay.server.handler.__new__(relay.server.handler)
                handler.request, handler.client_address = MagicMock(), ('127.0.0.1', 55555)
                handler.handle()
                with self.assertRaisesRegex(verify.Failed, 'LOOPBACK_RELAY_CLOSED_OR_EXPIRED'):
                    relay.check()
                self.assertEqual(relay.error, 'RELAY_CONNECTION_UNCONFIRMED')

if __name__ == '__main__':
    unittest.main()
