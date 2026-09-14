"""Evaluate real HCL, including both future and preserved snapshot approvals.

These are local policy-expression tests, not a claim of AWS IAM authorization.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_global_b_infrastructure import evaluate_sources, sid, attribute, block, LAB


class ClassInfrastructure(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.initial = evaluate_sources('')
        cls.legacy = evaluate_sources()
        cls.target = evaluate_sources('airbob-dataset-b-' + 'a' * 48)

    def test_actual_rendered_managed_policy_sizes_in_all_approval_states(self):
        for result in (self.initial, self.legacy, self.target):
            for name in ('boundaryBytes', 'provisionBytes', 'dataBytes'):
                self.assertLessEqual(result[name], 6144, name)
        self.assertEqual(6006, self.target['boundaryBytes'])

    def test_new_class_create_requires_exact_class_tag_and_original_bounds(self):
        for result in (self.initial, self.legacy, self.target):
            condition = sid(result['provision'], 'CreateExplicitGlobalBLargeDb')['Condition']
            self.assertEqual('db.m6i.large', condition['StringEquals']['rds:DatabaseClass'])
            self.assertEqual('db.m6i.large', condition['StringEquals']['aws:RequestTag/BDatabaseClass'])
            self.assertEqual('mysql', condition['StringEquals']['rds:DatabaseEngine'])
            self.assertEqual({'rds:Piops': 3000}, condition['NumericEquals'])
            self.assertEqual(100, condition['NumericLessThanEqualsIfExists']['rds:StorageSize'])
            self.assertEqual('true', condition['Bool']['rds:ManageMasterUserPassword'])
            self.assertEqual('false', condition['Bool']['rds:PubliclyAccessible'])
            self.assertEqual('false', condition['BoolIfExists']['rds:MultiAz'])
            self.assertIn('BDatabaseClass', condition['ForAllValues:StringEquals']['aws:TagKeys'])
            self.assertEqual('db.t3.small', sid(result['provision'], 'CreateBoundedDumpLabDbInstance')['Condition']['StringEquals']['rds:DatabaseClass'])

    def test_old_snapshot_approval_does_not_authorize_large_restore(self):
        for result in (self.initial, self.legacy):
            self.assertNotIn('RestoreExplicitGlobalBLargeDb', [row['Sid'] for row in result['provision']['Statement']])
        self.assertEqual('arn:aws:rds:ap-northeast-2:942632789808:snapshot:airbob-dataset-rehearsal-v20',
                         sid(self.legacy['provision'], 'UseApprovedSnapshotForRestoreLabDb')['Resource'])
        row = sid(self.target['provision'], 'RestoreExplicitGlobalBLargeDb')
        self.assertEqual('db.m6i.large', row['Condition']['StringEquals']['rds:DatabaseClass'])
        self.assertEqual('db.m6i.large', row['Condition']['StringEquals']['aws:RequestTag/BDatabaseClass'])

    def test_modify_conditions_allow_unrelated_updates_but_do_not_allow_class_membership(self):
        old = sid(self.target['data'], 'DenyUnboundedLabRdsClassChange')['Condition']
        bound = sid(self.target['data'], 'DenySelectedGlobalBRdsClassChange')['Condition']
        self.assertEqual({'rds:DatabaseClass': 'false', 'rds:db-tag/BDatabaseClass': 'true'}, old['Null'])
        self.assertEqual({'rds:DatabaseClass': 'db.t3.small'}, old['StringNotEquals'])
        self.assertEqual({'rds:DatabaseClass': 'false', 'rds:db-tag/BDatabaseClass': 'false'}, bound['Null'])
        self.assertEqual({'rds:DatabaseClass': '${rds:db-tag/BDatabaseClass}'}, bound['StringNotEquals'])
        # Evaluate only these real Null/StringNotEquals predicates. The IAM
        # key's live request-vs-current interpretation is deliberately not assumed.
        def denied(condition, values):
            for key, null in condition['Null'].items():
                if (key not in values) != (null == 'true'):
                    return False
            for key, expected in condition['StringNotEquals'].items():
                if expected.startswith('${'):
                    expected = values.get(expected[2:-1])
                if values.get(key) == expected:
                    return False
            return True
        for values in ({}, {'rds:db-tag/BDatabaseClass': 'db.m6i.large'},
                       {'rds:db-tag/BDatabaseClass': 'db.m6i.large', 'rds:DatabaseClass': 'db.m6i.large'},
                       {'rds:DatabaseClass': 'db.t3.small'}):
            self.assertFalse(denied(old, values) or denied(bound, values))
        self.assertTrue(denied(bound, {'rds:db-tag/BDatabaseClass': 'db.m6i.large', 'rds:DatabaseClass': 'db.t3.small'}))
        self.assertTrue(denied(old, {'rds:DatabaseClass': 'db.m6i.large'}))

    def test_class_tag_can_only_be_reapplied_with_its_original_value_and_no_removal_permission(self):
        row = sid(self.target['data'], 'TagNewLabRdsOnCreate')
        self.assertEqual({'aws:RequestTag/BDatabaseClass': '${aws:ResourceTag/BDatabaseClass}'}, row['Condition']['StringEqualsIfExists'])
        self.assertEqual('${aws:ResourceTag/RunId}', row['Condition']['StringEquals']['aws:RequestTag/RunId'])
        self.assertEqual('${aws:ResourceTag/FencingToken}', row['Condition']['StringEquals']['aws:RequestTag/FencingToken'])
        for policy in ('provision', 'data'):
            for statement in self.target[policy]['Statement']:
                actions = statement['Action'] if isinstance(statement['Action'], list) else [statement['Action']]
                self.assertNotIn('rds:RemoveTagsFromResource', actions)
                self.assertNotIn('rds:*', actions)

    def test_snapshot_creation_still_requires_exact_source_and_approved_snapshot(self):
        base = sid(self.target['snapshot'], 'SnapshotOnlyBoundSourceRun')
        large = sid(self.target['snapshot'], 'SnapshotBoundGlobalBLargeSource')
        self.assertEqual(base['Resource'], large['Resource'])
        self.assertIn('${aws:RequestTag/SourceRunId}', large['Resource'])
        self.assertEqual('db.m6i.large', large['Condition']['StringEquals']['rds:db-tag/BDatabaseClass'])
        self.assertEqual('${aws:RequestTag/SourceRunId}', large['Condition']['StringEquals']['rds:db-tag/RunId'])
        self.assertEqual('arn:aws:rds:ap-northeast-2:942632789808:snapshot:airbob-dataset-b-class-test',
                         sid(self.target['snapshot'], 'CreateExactApprovedBSnapshot')['Resource'])

    def test_real_lab_and_module_class_conditions_close_legacy_engine_storage_and_tags(self):
        lab_guard = attribute(block((LAB / 'variables.tf').read_text(), 'variable', 'rds_instance_class'), 'condition', 4)
        module_guard = attribute(block((LAB / 'modules/rds/variables.tf').read_text(), 'variable', 'instance_class'), 'condition', 4)
        cases = [
            ['db.t3.small', '8.0.40', False, 20, {}],
            ['db.m6i.large', '8.4.11', True, 100, {'BDatabaseClass': 'db.m6i.large'}],
            ['db.m6i.large', '8.4.11', False, 100, {'BDatabaseClass': 'db.m6i.large'}],
            ['db.m6i.large', '8.0.40', True, 100, {'BDatabaseClass': 'db.m6i.large'}],
            ['db.m6i.large', '8.4.11', True, 20, {'BDatabaseClass': 'db.m6i.large'}],
            ['db.m6i.large', '8.4.11', True, 100, {}],
            ['db.t3.medium', '8.4.11', True, 100, {'BDatabaseClass': 'db.t3.medium'}],
        ]
        replacements = {'var.rds_instance_class': 'c[0]', 'var.instance_class': 'c[0]', 'var.rds_engine_version': 'c[1]',
            'var.engine_version': 'c[1]', 'var.global_b_prepare_only': 'c[2]', 'var.global_b_services': 'false',
            'var.global_b_snapshot_restore_only': 'false', 'var.dump_storage_gib': 'c[3]', 'var.tags': 'c[4]'}
        for key, value in replacements.items():
            lab_guard = lab_guard.replace(key, value); module_guard = module_guard.replace(key, value)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'main.tf').write_text('locals { cases = jsondecode(file("cases.json")) }\n')
            (root / 'cases.json').write_text(json.dumps(cases))
            (root / 'terraform.rc').write_text('disable_checkpoint = true\n')
            env = {'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'TF_CLI_CONFIG_FILE': str(root / 'terraform.rc')}
            result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
                input='jsonencode([for c in local.cases : (' + ' '.join(lab_guard.splitlines()) + ') && (' + ' '.join(module_guard.splitlines()) + ')])\n',
                capture_output=True, text=True, env=env, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual([True, True, False, False, False, False, False], json.loads(json.loads(result.stdout)))


if __name__ == '__main__':
    unittest.main()
