"""RAM-only downsize contracts; real Mac receipt producer with its existing I/O doubles."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_b_mac_downsize as d
import growth_b_rds_class as gate
import test_growth_b_mac_import as importer_tests

SECRET = 'RAW_STATE_MUST_STAY_IN_RAM'


class MacDownsize(unittest.TestCase):
    def setUp(self):
        producer = importer_tests.MacImportTests()
        producer.setUp(); self.addCleanup(producer.doCleanups)
        self.root, self.producer = producer.root, producer
        image = 'sha256:' + 'b' * 64
        self.op = {'schemaVersion': 2, 'runId': 'lab-mac-test', 'expiresAt': '1999999999', 'fencingToken': 76,
            'mode': 'performance', 'policy': 'isolated-read', 'dnsMode': 'direct-only', 'albIngressCidr': '8.8.8.8/32',
            'imageDigest': image, 'datasetRelease': 'global-growth-b-' + 'a' * 16,
            'datasetManifestVersionId': 'manifest-version', 'bundleCommit': 'c' * 40, 'bundleSha256': 'd' * 64,
            'datasetManifestSha256': 'e' * 64, 'amiId': 'ami-0123456789abcdef0', 'rdsEngineVersion': '8.4.11',
            'databaseBootstrap': 'dump', 'cacheEnabled': False, 'requestTarget': '', 'loadGeneratorEnabled': False,
            'appImageReference': f'{d.ACCOUNT}.dkr.ecr.{d.REGION}.amazonaws.com/airbob-repo@{image}',
            'infraImageReferences': {k: f'{d.ACCOUNT}.dkr.ecr.{d.REGION}.amazonaws.com/airbob-infra/{k.lower().replace("_", "-")}@{image}'
                                     for k in d.IMAGE_KEYS},
            'globalBPrepareOnly': True, 'globalBImportFromMac': True, 'rdsInstanceClass': d.LARGE,
            'approvedExecutionDeadlineEpoch': 1999999999, 'rdsSnapshotIdentifier': '',
            'rdsSnapshotSourceRunId': '', 'rdsSnapshotSourceResourceId': ''}
        producer.rds['TagList'] = [{'Key': 'ExpiresAt', 'Value': self.op['expiresAt']}]
        # This executes the production importer receipt flow, not invented success booleans.
        self.postcheck = importer_tests.m.run(producer.args)
        self.sql_raw = (producer.args.output / 'sql-import-completed.json').read_bytes()
        self.sql = json.loads(self.sql_raw)
        self.op_raw = d.encoded(self.op)
        self.target = {'identifier': producer.args.identifier, 'resourceId': producer.args.resource_id,
            'endpoint': producer.args.endpoint, 'arn': producer.rds['DBInstanceArn']}
        self.before = dict(id=self.target['resourceId'], resource_id=self.target['resourceId'],
            identifier=self.target['identifier'], address=self.target['endpoint'], arn=self.target['arn'],
            instance_class=d.LARGE, engine='mysql', engine_version='8.4.11', allocated_storage=100,
            storage_type='gp3', storage_encrypted=True, publicly_accessible=False, multi_az=False,
            manage_master_user_password=True, port=3306, tags=d.expected_tags(self.op, d.LARGE),
            tags_all=d.expected_tags(self.op, d.LARGE), apply_immediately=True, iops=3000,
            backup_retention_period=1, db_name='airbobdb', password=SECRET)
        out = {'run_identity': {'run_id': self.op['runId'], 'resource_fencing_token': 76},
            'phase2_contract': {'run_id': self.op['runId'], 'fencing_token': 76, 'deployment_phase': 'services',
                'nat_instance_id': 'i-11111111111111111', 'expected_network_receipt_key': 'network-receipts/lab-mac-test/i-22222222222222222.json',
                'services': {}, 'probe_enabled': False},
            'phase3_contract': {'dataset_release': self.op['datasetRelease'], 'database_bootstrap': 'dump',
                'rds_engine_version': '8.4.11', 'rds_instance_class': d.LARGE, 'rds_configured_storage_gib': 100,
                'rds_allocated_storage_gib': 100, 'data_ready': False, 'rds_instance_id': self.target['identifier'],
                'rds_resource_id': self.target['resourceId'], 'rds_endpoint': self.target['endpoint']},
            'phase4_contract': {'app_enabled': False, 'load_generator_enabled': False, 'capacity': {'min': 0, 'desired': 0, 'max': 0}}}
        self.state = {'version': 4, 'lineage': '12345678-1234-4123-8123-123456789abc', 'serial': 10,
            'outputs': {k: {'value': v} for k, v in out.items()},
            'resources': [{'mode': 'managed', 'module': 'module.rds[0]', 'type': 'aws_db_instance', 'name': 'this',
                           'instances': [{'attributes': self.before}]}]}
        self.arguments = {'operator': self.op, 'state': self.state, 'sql_complete': self.sql, 'postcheck': self.postcheck,
            'operator_sha256': hashlib.sha256(self.op_raw).hexdigest(), 'sql_import_sha256': hashlib.sha256(self.sql_raw).hexdigest(),
            'postcheck_sha256': hashlib.sha256(d.encoded(self.postcheck)).hexdigest(),
            'expected_dump_sha256': producer.args.dump_sha256, 'lease_owner': 'current/mac-downsize', 'now': 1800000000}
        result = d.prepare_inputs(**self.arguments)
        self.request, self.tfvars = result['request'], result['tfvars']
        self.after = copy.deepcopy(self.before)
        self.after.update(instance_class=d.SMALL, tags=d.expected_tags(self.op, d.SMALL), tags_all=d.expected_tags(self.op, d.SMALL))
        self.plan = {'format_version': '1.2', 'terraform_version': '1.15.5', 'applyable': True, 'complete': True,
            'variables': {k: {'value': v} for k, v in self.tfvars.items()},
            'resource_changes': [{'address': d.ADDRESS, 'mode': 'managed', 'type': 'aws_db_instance',
                'change': {'actions': ['update'], 'before': self.before, 'after': self.after, 'after_unknown': {}, 'replace_paths': []}}]}
        self.state_after = copy.deepcopy(self.state)
        self.state_after['serial'] = 11
        self.state_after['resources'][0]['instances'][0]['attributes'] = self.after
        self.state_after['outputs']['phase3_contract']['value']['rds_instance_class'] = d.SMALL
        self.rds_after = {'DBInstances': [copy.deepcopy(producer.rds)]}
        self.rds_after['DBInstances'][0].update(DBInstanceClass=d.SMALL,
            TagList=[{'Key': k, 'Value': v} for k, v in d.expected_tags(self.op, d.SMALL).items()],
            InstanceCreateTime='2026-09-14T00:00:00Z')
        self.source_rds = copy.deepcopy(self.rds_after)
        self.source_rds['DBInstances'][0].update(DBInstanceClass=d.LARGE,
            TagList=[{'Key': k, 'Value': v} for k, v in d.expected_tags(self.op, d.LARGE).items()])
        self.modify_request = {'DBInstanceIdentifier': self.target['identifier'],
                               'DBInstanceClass': d.SMALL, 'ApplyImmediately': True}
        self.remove_tags_request = {'ResourceName': self.target['arn'], 'TagKeys': [d.TAG]}

    def approve(self, plan=None, **kwargs):
        return d.validate_plan(self.request, plan or self.plan, now=1800000010, **kwargs)

    def approve_api(self, **kwargs):
        arguments = {'request': self.request, 'source_rds': self.source_rds,
            'modify_request': self.modify_request, 'remove_tags_request': self.remove_tags_request,
            'observed_at_epoch': 1800000005, 'now': 1800000010}
        return d.validate_api_requests(**(arguments | kwargs))

    def finish(self, **kwargs):
        return d.complete_transition(self.request, kwargs.pop('approval', self.approve()),
            kwargs.pop('state_after', self.state_after), kwargs.pop('rds_after', self.rds_after),
            kwargs.pop('database_after', self.postcheck['database']), now=1800000100, **kwargs)

    def test_reconstructs_actual_settings_without_original_runner_tfvars(self):
        self.assertEqual(76, self.tfvars['fencing_token'])
        self.assertEqual('1999999999', self.tfvars['expires_at'])
        self.assertEqual('i-22222222222222222', self.tfvars['verified_probe_instance_id'])
        self.assertEqual(d.SMALL, self.tfvars['rds_instance_class'])
        self.assertTrue(self.tfvars['global_b_prepare_only']); self.assertTrue(self.tfvars['global_b_import_from_mac'])
        self.assertFalse(self.tfvars['global_b_services']); self.assertFalse(self.tfvars['app_enabled'])
        self.assertEqual(self.op['infraImageReferences'], self.tfvars['infra_image_references'])
        self.assertEqual(self.op['appImageReference'], self.tfvars['app_image_reference'])
        self.assertEqual('current/mac-downsize', self.tfvars['global_b_lease_owner'])
        self.assertEqual(0, self.tfvars['global_b_lease_fencing_token'])

    def test_sql_import_and_postcheck_must_be_real_matching_success_stages(self):
        for field, value in [('state', 'SQL_IMPORT_RUNNING'), ('automaticReplayAllowed', True), ('fullDatasetValidated', True)]:
            bad = copy.deepcopy(self.arguments); bad['sql_complete'][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): d.prepare_inputs(**bad)
        for field, value in [('state', 'SQL_IMPORTED_POSTCHECK_FAILED'), ('sqlImportReceiptSha256', 'f' * 64),
                             ('failedMigrations', 1), ('flywayVersion', 27), ('representativePrimaryKeysObserved', False)]:
            bad = copy.deepcopy(self.arguments); bad['postcheck'][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): d.prepare_inputs(**bad)

    def test_wrong_dump_partial_stream_and_bool_exit_are_rejected(self):
        with self.assertRaises(d.Rejected): d.prepare_inputs(**(self.arguments | {'expected_dump_sha256': 'f' * 64}))
        for field, value in [('mysqlExitCode', False), ('gzipExitCode', 1), ('compressedBytesRead', 1)]:
            bad = copy.deepcopy(self.arguments); bad['sql_complete']['stream'][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): d.prepare_inputs(**bad)

    def test_foreign_run_fence_expiry_and_active_service_are_rejected(self):
        for key, value in [('globalBImportFromMac', False), ('rdsInstanceClass', d.SMALL), ('fencingToken', 77),
                           ('expiresAt', '1999999998'), ('datasetRelease', 'global-growth-b-'+'f'*16)]:
            bad = copy.deepcopy(self.arguments); bad['operator'][key] = value
            with self.subTest(key=key), self.assertRaises(d.Rejected): d.prepare_inputs(**bad)
        bad = copy.deepcopy(self.arguments); bad['state']['outputs']['phase2_contract']['value']['services'] = {'debezium': 'i-foreign'}
        with self.assertRaises(d.Rejected): d.prepare_inputs(**bad)
        with self.assertRaises(d.Rejected): d.prepare_inputs(**(self.arguments | {'now': 2000000000}))

    def test_only_one_rds_update_and_original_resource_address_are_allowed(self):
        for actions in (['create'], ['delete'], ['delete', 'create'], ['no-op']):
            plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['change']['actions'] = actions
            with self.subTest(actions=actions), self.assertRaises(d.Rejected): self.approve(plan)
        plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['address'] = 'module.rds[1].aws_db_instance.this'
        with self.assertRaises(d.Rejected): self.approve(plan)
        plan = copy.deepcopy(self.plan); plan['resource_changes'].append({'address': 'module.nat.aws_instance.this', 'mode': 'managed', 'type': 'aws_instance', 'change': {'actions': ['update']}})
        with self.assertRaises(d.Rejected): self.approve(plan)

    def test_every_nonclass_setting_and_every_other_tag_are_preserved(self):
        for field, value in [('allocated_storage', 200), ('iops', 6000), ('publicly_accessible', True),
                             ('engine_version', '8.4.12'), ('backup_retention_period', 0), ('instance_class', 'db.m6i.xlarge')]:
            plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['change']['after'][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.approve(plan)
        for tag in ('RunId', 'FencingToken', 'ExpiresAt', 'Service'):
            plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['change']['after']['tags'][tag] = 'changed'
            with self.subTest(tag=tag), self.assertRaises(d.Rejected): self.approve(plan)
        plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['change']['after']['tags'][d.TAG] = d.SMALL
        with self.assertRaises(d.Rejected): self.approve(plan)

    def test_plan_variables_cannot_change_fence_dataset_image_or_owner(self):
        for field, value in [('fencing_token', 77), ('expires_at', '1999999998'), ('dataset_manifest_sha256', 'f'*64),
                             ('app_image_reference', 'foreign'), ('global_b_lease_owner', 'foreign/owner'), ('global_b_import_from_mac', False)]:
            plan = copy.deepcopy(self.plan); plan['variables'][field]['value'] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.approve(plan)

    def test_unknown_configuration_deferred_work_or_move_is_rejected(self):
        for key in ('instance_class', 'iops', 'tags'):
            plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['change']['after_unknown'][key] = True
            with self.subTest(key=key), self.assertRaises(d.Rejected): self.approve(plan)
        for key, value in [('complete', False), ('deferred_changes', [{'resource_change': 'later'}])]:
            plan = copy.deepcopy(self.plan); plan[key] = value
            with self.subTest(key=key), self.assertRaises(d.Rejected): self.approve(plan)
        plan = copy.deepcopy(self.plan); plan['resource_changes'][0]['previous_address'] = 'foreign'
        with self.assertRaises(d.Rejected): self.approve(plan)

    def test_computed_unknown_is_not_treated_as_a_config_change(self):
        plan = copy.deepcopy(self.plan); change = plan['resource_changes'][0]['change']
        change['before']['latest_restorable_time'] = '2026-09-14T01:00:00Z'
        change['after']['latest_restorable_time'] = None
        change['after_unknown']['latest_restorable_time'] = True
        self.assertEqual('SINGLE_RDS_DOWNSIZE_PLAN_VERIFIED', self.approve(plan)['state'])

    def test_unrelated_configuration_drift_before_or_after_apply_is_rejected(self):
        plan = copy.deepcopy(self.plan)
        plan['resource_changes'][0]['change']['before']['backup_retention_period'] = 0
        plan['resource_changes'][0]['change']['after']['backup_retention_period'] = 0
        with self.assertRaises(d.Rejected): self.approve(plan)
        state = copy.deepcopy(self.state_after)
        state['resources'][0]['instances'][0]['attributes']['backup_retention_period'] = 0
        with self.assertRaises(d.Rejected): self.finish(state_after=state)

    def test_saved_binary_plan_binding_is_optional_but_cannot_be_half_present(self):
        self.assertIsNone(self.approve()['savedPlanSha256'])
        self.assertEqual('a'*64, self.approve(saved_plan_sha256='a'*64, saved_plan_bytes=1024)['savedPlanSha256'])
        for extra in ({'saved_plan_bytes': 1}, {'saved_plan_sha256': 'a'*64}, {'saved_plan_sha256': 'bad', 'saved_plan_bytes': 1}):
            with self.subTest(extra=extra), self.assertRaises(d.Rejected): self.approve(**extra)

    def test_exact_api_requests_complete_using_actual_state_and_database_observations(self):
        streams = self.producer.state.streams
        approval = self.approve_api()
        result = self.finish(approval=approval)
        self.assertEqual('EXACT_RDS_API_REQUESTS_VERIFIED', approval['state'])
        self.assertEqual(d.API_EVIDENCE, result['planEvidenceKind'])
        self.assertIsNone(result['planJsonSha256']); self.assertIsNone(result['savedPlanSha256'])
        self.assertEqual(d.digest(approval), result['approvalSha256'])
        evidence = result['apiRequestEvidence']
        self.assertEqual(d.digest(self.source_rds), evidence['sourceObservationSha256'])
        self.assertEqual(self.modify_request, evidence['modifyDBInstance'])
        self.assertEqual(self.remove_tags_request, evidence['removeTagsFromResource'])
        self.assertEqual(self.request['rds'], result['rds'])
        self.assertEqual(self.request['stateBefore']['lineage'], result['stateAfter']['lineage'])
        self.assertEqual(d.SMALL, gate.effective_class(self.op, result, self.arguments['operator_sha256']))
        self.assertEqual(streams, self.producer.state.streams)
        self.assertEqual(self.op_raw, d.encoded(self.op))
        self.assertEqual(result, self.finish(approval=approval))

    def test_modify_scope_rejects_other_identifier_class_settings_and_nonboolean_immediate(self):
        for field, value in [('DBInstanceIdentifier', 'airbob-lab-foreign'), ('DBInstanceClass', 'db.m6i.xlarge'),
                             ('ApplyImmediately', False), ('ApplyImmediately', 1), ('ApplyImmediately', 'true'),
                             ('AllocatedStorage', 200), ('MasterUserPassword', SECRET),
                             ('NewDBInstanceIdentifier', 'airbob-lab-foreign'), ('EngineVersion', '8.4.12')]:
            with self.subTest(field=field, value=value), self.assertRaises(d.Rejected):
                self.approve_api(modify_request=self.modify_request | {field: value})
        for field in self.modify_request:
            with self.subTest(missing=field), self.assertRaises(d.Rejected):
                self.approve_api(modify_request={k: v for k, v in self.modify_request.items() if k != field})

    def test_tag_removal_cannot_remove_expiry_fence_or_touch_other_resource(self):
        for field, value in [('ResourceName', self.target['arn'] + '-foreign'), ('TagKeys', ['ExpiresAt']),
                             ('TagKeys', [d.TAG, 'FencingToken']), ('TagKeys', [d.TAG, d.TAG]),
                             ('TagKeys', []), ('TagKeys', d.TAG), ('Tags', [{'Key': d.TAG, 'Value': d.SMALL}])]:
            with self.subTest(field=field, value=value), self.assertRaises(d.Rejected):
                self.approve_api(remove_tags_request=self.remove_tags_request | {field: value})
        with self.assertRaises(d.Rejected):
            self.approve_api(remove_tags_request=[self.remove_tags_request])

    def test_api_admission_requires_one_fresh_available_large_source_with_no_pending(self):
        for field, value in [('DBInstanceClass', d.SMALL), ('DBInstanceStatus', 'modifying'),
                             ('PendingModifiedValues', {'DBInstanceClass': d.SMALL}), ('PendingModifiedValues', None),
                             ('DbiResourceId', 'db-FOREIGN'), ('DBInstanceArn', self.target['arn'] + '-foreign'),
                             ('AllocatedStorage', 200), ('MultiAZ', True), ('PubliclyAccessible', True),
                             ('EngineVersion', '8.4.12')]:
            bad = copy.deepcopy(self.source_rds); bad['DBInstances'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(d.Rejected): self.approve_api(source_rds=bad)
        missing = copy.deepcopy(self.source_rds); del missing['DBInstances'][0]['PendingModifiedValues']
        with self.assertRaises(d.Rejected): self.approve_api(source_rds=missing)
        for rows in ([], self.source_rds['DBInstances'] * 2):
            with self.subTest(count=len(rows)), self.assertRaises(d.Rejected): self.approve_api(source_rds={'DBInstances': rows})
        for observed, now in [(1800000005, 1800000066), (1800000011, 1800000010),
                              (1799999999, 1800000010), (True, 1800000010), (1999999998, 1999999999)]:
            with self.subTest(observed=observed, now=now), self.assertRaises(d.Rejected):
                self.approve_api(observed_at_epoch=observed, now=now)

    def test_api_source_tags_preserve_original_dataset_run_fence_expiry_scope(self):
        for tag in ('RunId', 'FencingToken', 'ExpiresAt', d.TAG):
            bad = copy.deepcopy(self.source_rds)
            next(item for item in bad['DBInstances'][0]['TagList'] if item['Key'] == tag)['Value'] = 'changed'
            with self.subTest(tag=tag), self.assertRaises(d.Rejected): self.approve_api(source_rds=bad)
        bad = copy.deepcopy(self.source_rds); bad['DBInstances'][0]['TagList'].append(bad['DBInstances'][0]['TagList'][0])
        with self.assertRaises(d.Rejected): self.approve_api(source_rds=bad)
        request = copy.deepcopy(self.request); request['tfvars']['dataset_release'] = 'foreign'
        with self.assertRaises(d.Rejected): self.approve_api(request=request)

    def test_api_approval_and_receipt_cannot_claim_plan_validation_or_change_request(self):
        approval = self.approve_api()
        for field, value in [('planEvidenceKind', d.PLAN_EVIDENCE), ('planEvidenceKind', 'terraform-saved-plan'),
                             ('planJsonSha256', 'a' * 64), ('savedPlanSha256', 'a' * 64), ('savedPlanBytes', 1024),
                             ('requestSha256', 'b' * 64), ('state', 'SINGLE_RDS_DOWNSIZE_PLAN_VERIFIED')]:
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.finish(approval=approval | {field: value})
        bad = copy.deepcopy(approval); bad['apiRequestEvidence']['modifyDBInstance']['AllocatedStorage'] = 200
        with self.assertRaises(d.Rejected): self.finish(approval=bad)
        result = self.finish(approval=approval)
        for field, value in [('planEvidenceKind', d.PLAN_EVIDENCE), ('planJsonSha256', 'a' * 64), ('savedPlanSha256', 'b' * 64)]:
            with self.subTest(receipt_field=field), self.assertRaises(d.Rejected):
                d.validate_receipt(self.op, result | {field: value}, self.arguments['operator_sha256'])
        plan_approval = self.approve()
        self.assertEqual(d.PLAN_EVIDENCE, plan_approval['planEvidenceKind'])
        self.assertIsNone(plan_approval['apiRequestEvidence'])
        with self.assertRaises(d.Rejected): self.finish(approval=plan_approval | {'apiRequestEvidence': approval['apiRequestEvidence']})

    def test_api_completion_still_rejects_foreign_config_nat_uuid_state_or_pending_target(self):
        approval = self.approve_api()
        for state in (self.state, self.state_after | {'lineage': 'foreign'}):
            with self.subTest(state_lineage=state['lineage']), self.assertRaises(d.Rejected):
                self.finish(approval=approval, state_after=state)
        for field, value in [('allocated_storage', 200), ('backup_retention_period', 0)]:
            bad = copy.deepcopy(self.state_after); bad['resources'][0]['instances'][0]['attributes'][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.finish(approval=approval, state_after=bad)
        bad = copy.deepcopy(self.state_after); bad['outputs']['phase2_contract']['value']['nat_instance_id'] = 'i-99999999999999999'
        with self.assertRaises(d.Rejected): self.finish(approval=approval, state_after=bad)
        for field, value in [('DBInstanceStatus', 'modifying'), ('DbiResourceId', 'db-FOREIGN'),
                             ('PendingModifiedValues', {'DBInstanceClass': d.SMALL})]:
            bad = copy.deepcopy(self.rds_after); bad['DBInstances'][0][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.finish(approval=approval, rds_after=bad)
        bad = copy.deepcopy(self.rds_after)
        bad['DBInstances'][0]['TagList'].append({'Key': d.TAG, 'Value': d.LARGE})
        with self.assertRaises(d.Rejected): self.finish(approval=approval, rds_after=bad)
        with self.assertRaises(d.Rejected):
            self.finish(approval=approval, database_after=self.postcheck['database'] | {'serverUuid': '99999999-1234-4123-8123-123456789abc'})

    def test_api_public_evidence_contains_exact_requests_but_no_raw_source_values(self):
        source = copy.deepcopy(self.source_rds); source['DBInstances'][0]['PrivateFixtureOnly'] = SECRET
        approval = self.approve_api(source_rds=source)
        for value in (approval, self.finish(approval=approval)):
            raw = d.encoded(value)
            self.assertNotIn(SECRET.encode(), raw); self.assertNotIn(b'MasterUserSecret', raw)
            self.assertNotIn(b'DBInstances', raw)

    def test_completion_requires_state_and_actual_available_rds_not_apply_boolean(self):
        result = self.finish()
        self.assertEqual('SAME_RDS_DOWNSIZED_AND_TERRAFORM_ALIGNED', result['state'])
        self.assertEqual(76, result['operator']['fencingToken'])
        self.assertFalse(result['sqlReplayed']); self.assertFalse(result['fullDatasetValidated']); self.assertFalse(result['servicesStarted'])
        for field, value in [('DBInstanceClass', d.LARGE), ('DBInstanceStatus', 'modifying'),
                             ('DbiResourceId', 'db-FOREIGN'), ('PendingModifiedValues', {'DBInstanceClass': d.SMALL})]:
            bad = copy.deepcopy(self.rds_after); bad['DBInstances'][0][field] = value
            with self.subTest(field=field), self.assertRaises(d.Rejected): self.finish(rds_after=bad)

    def test_old_state_foreign_lineage_nat_and_uuid_cannot_complete(self):
        with self.assertRaises(d.Rejected): self.finish(state_after=self.state)
        state = copy.deepcopy(self.state_after); state['lineage'] = 'foreign'
        with self.assertRaises(d.Rejected): self.finish(state_after=state)
        state = copy.deepcopy(self.state_after); state['outputs']['phase2_contract']['value']['nat_instance_id'] = 'i-99999999999999999'
        with self.assertRaises(d.Rejected): self.finish(state_after=state)
        with self.assertRaises(d.Rejected): self.finish(database_after=self.postcheck['database'] | {'serverUuid': '99999999-1234-4123-8123-123456789abc'})

    def test_lost_completion_receipt_can_be_rebuilt_without_replaying_sql(self):
        count = self.producer.state.streams
        self.assertEqual(self.finish(), self.finish())
        self.assertEqual(count, self.producer.state.streams)
        self.assertEqual(self.op_raw, d.encoded(self.op))
        self.assertEqual(self.sql_raw, (self.producer.args.output / 'sql-import-completed.json').read_bytes())

    def test_raw_state_plan_and_private_binding_are_not_in_any_output(self):
        for value in (self.request, self.tfvars, self.approve(), self.finish()):
            raw = d.encoded(value)
            self.assertNotIn(SECRET.encode(), raw)
            self.assertNotIn(str(self.producer.args.dump).encode(), raw)
            self.assertNotIn(b'password', raw)

    def test_receipt_is_mac_only_and_keeps_general_class_immutability(self):
        proof = self.finish(); operator_sha = self.arguments['operator_sha256']
        self.assertEqual(d.SMALL, gate.effective_class(self.op, proof, operator_sha))
        self.assertEqual(d.LARGE, gate.original_class(self.op))
        self.assertEqual(d.LARGE, gate.effective_class(self.op))
        with self.assertRaises(gate.Rejected): gate.validate_plan(self.plan, d.SMALL)
        for operator in (self.op | {'globalBImportFromMac': False}, self.op | {'fencingToken': 77}, self.op | {'expiresAt': '1999999998'}):
            with self.assertRaises(gate.Rejected): gate.effective_class(operator, proof, operator_sha)
        with self.assertRaises(gate.Rejected): gate.effective_class(self.op, proof, 'f'*64)
        phase3 = d.outputs(self.state_after)['phase3_contract']
        self.assertEqual(d.SMALL, gate.validate_live(self.op, phase3, self.rds_after, proof, operator_sha)['instanceClass'])
        with self.assertRaises(gate.Rejected): gate.validate_live(self.op, phase3 | {'rds_resource_id': 'db-FOREIGN'}, self.rds_after, proof, operator_sha)

    def test_receipt_reference_is_exact_run_dataset_version_and_sha(self):
        value = {'key': f'data-bootstrap/{self.op["runId"]}/{self.op["datasetRelease"]}-mac-rds-downsize.json',
                 'versionId': 'selected-version', 'sha256': 'a'*64, 'bytes': 1024}
        self.assertEqual(value, d.reference(value, self.op))
        for key, changed in [('key', 'data-bootstrap/foreign/receipt.json'), ('versionId', 'None'), ('sha256', 'bad'), ('bytes', 128*1024+1)]:
            with self.subTest(key=key), self.assertRaises(d.Rejected): d.reference(value | {key: changed}, self.op)

    def test_actual_operator_rehydrates_only_hash_pinned_mac_completion(self):
        proof = self.finish()
        original = self.root / 'operator.json'; original.write_bytes(self.op_raw)
        marker = self.root / 'published-transition.json'; d.write_new(marker, proof)
        reference = {'key': f'data-bootstrap/{self.op["runId"]}/{self.op["datasetRelease"]}-mac-rds-downsize.json',
            'versionId': 'selected-version', 'sha256': hashlib.sha256(marker.read_bytes()).hexdigest(), 'bytes': marker.stat().st_size}
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        functions = source[source.index('load_retained_rds_class() {'):source.index('validate_snapshot_bootstrap_inputs() {')]
        stub = self.root / 'aws.py'
        stub.write_text('''import json,os,pathlib,sys
a=sys.argv[1:]; ref=json.loads(os.environ['B_MAC_DOWNSIZE_RECEIPT_JSON'])
with open(os.environ['CALLS'],'a') as f:f.write(a[1]+'\\n')
assert a[:2] in (['s3api','head-object'],['s3api','get-object'])
assert a[a.index('--version-id')+1]==ref['versionId']
assert a[a.index('--key')+1]==ref['key']
if a[1]=='get-object':pathlib.Path(a[a.index('--version-id')+2]).write_bytes(pathlib.Path(os.environ['MARKER']).read_bytes())
print(json.dumps({'VersionId':ref['versionId'],'ContentLength':ref['bytes']}))
''')
        script = '''set -euo pipefail
fail() { printf '%s\\n' "$1" >&2; exit 1; }
aws() { python3 "$STUB" "$@"; }
sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
'''+functions+'''load_retained_rds_class "$temp_dir/operator.json"
printf '%s' "$rds_instance_class"
'''
        env = {'PATH': os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1', 'AWS_REGION': d.REGION, 'script_dir': str(SCRIPTS),
            'temp_dir': str(self.root), 'evidence_bucket': 'fixture-evidence', 'B_MAC_DOWNSIZE_RECEIPT_JSON': json.dumps(reference),
            'STUB': str(stub), 'MARKER': str(marker), 'CALLS': str(self.root/'calls')}
        result = subprocess.run(['bash'], input=script, text=True, capture_output=True, env=env, timeout=10)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(d.SMALL, result.stdout)
        self.assertEqual(['head-object', 'get-object'], (self.root/'calls').read_text().splitlines())
        self.assertEqual(self.op_raw, original.read_bytes())
        marker.write_bytes(d.encoded(proof | {'toClass': d.LARGE}))
        failed = subprocess.run(['bash'], input=script, text=True, capture_output=True, env=env, timeout=10)
        self.assertNotEqual(0, failed.returncode); self.assertNotEqual(d.SMALL, failed.stdout)

    def test_unknown_cli_clock_and_raw_json_errors_leave_no_output(self):
        output = self.root / 'must-not-exist.json'
        command = [sys.executable, str(SCRIPTS / 'growth_b_mac_downsize.py'), 'prepare', '--output', str(output)]
        for payload in (json.dumps(self.arguments), '{"state":null,"state":null}', '{"state":NaN}'):
            result = subprocess.run(command, input=payload, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(output.exists()); self.assertNotIn(SECRET, result.stdout+result.stderr)

    def test_stdin_cli_keeps_raw_state_in_memory_and_output_create_only(self):
        output = self.root / 'prepare-result.json'
        command = [sys.executable, str(SCRIPTS / 'growth_b_mac_downsize.py'), 'prepare', '--output', str(output)]
        env = {'PATH': os.environ['PATH'], 'PYTHONDONTWRITEBYTECODE': '1'}
        arguments = {k: v for k, v in self.arguments.items() if k != 'now'}
        result = subprocess.run(command, input=json.dumps(arguments), text=True, capture_output=True, env=env, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(0o600, output.stat().st_mode & 0o777)
        self.assertNotIn(SECRET, output.read_text() + result.stdout + result.stderr)
        second = subprocess.run(command, input=json.dumps(arguments), text=True, capture_output=True, env=env, timeout=10)
        self.assertNotEqual(0, second.returncode)


if __name__ == '__main__':
    unittest.main()
