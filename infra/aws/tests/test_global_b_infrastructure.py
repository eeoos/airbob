"""Provider-free evaluation of production Terraform expressions for B prerequisites.

Only `terraform console` runs in a new directory with no backend, provider, data,
resource, or module blocks. No Terraform init/plan/apply or AWS APIs are used.
This is a static policy contract, not proof of deployed IAM authorization.

AWS service authorization references (reviewed 2026-09-11):
https://docs.aws.amazon.com/service-authorization/latest/reference/list_rds.html
https://docs.aws.amazon.com/service-authorization/latest/reference/list_dynamodb.html
https://docs.aws.amazon.com/service-authorization/latest/reference/list_autoscaling.html
https://docs.aws.amazon.com/service-authorization/latest/reference/list_cloudwatch.html
"""
import fnmatch
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
LAB = ROOT / 'infra/aws/lab'
FOUNDATION = ROOT / 'infra/aws/foundation'


def attribute(source, name, indent=2):
    """Extract one existing, indentation-delimited HCL attribute without rewriting its logic."""
    space = ' ' * indent
    pattern = (r'(?ms)^' + space + re.escape(name) + r'\s*=\s*(.*?)'
               + r'(?=\n' + space + r'[A-Za-z_][A-Za-z_0-9]*\s*=|\n' + ' ' * (indent - 2) + r'\})')
    matches = re.findall(pattern, source)
    if len(matches) != 1:
        raise AssertionError('Review changed HCL attribute: ' + name)
    return matches[0].strip()


def block(source, kind, *labels):
    header = kind + ''.join(' "' + label + '"' for label in labels)
    matches = re.findall(r'(?ms)^' + re.escape(header) + r' \{\n(.*?)^\}', source)
    if len(matches) != 1:
        raise AssertionError('Review changed HCL block: ' + header)
    return matches[0] + '}\n'


def evaluate_sources():
    compute = (FOUNDATION / 'lab-compute.tf').read_text()
    iam = (LAB / 'iam.tf').read_text()
    main = (LAB / 'modules/rds/main.tf').read_text()
    variables = (LAB / 'modules/rds/variables.tf').read_text()
    # These are identifiers of fake values only, never alternate policy logic.
    replacements = {
        'aws_s3_bucket.managed': 'local.buckets',
        'aws_dynamodb_table.orchestration_lease.arn': 'local.lease_arn',
        'module.rds[0].master_secret_arn': 'local.master_secret_arn',
        'module.rds[0].arn': 'local.rds_arn',
        'aws_secretsmanager_secret.debezium[0].arn': 'local.debezium_secret_arn',
    }
    expressions = {
        name: attribute(compute, name) for name in ('lab_host_boundary_policy', 'lab_rds_provision_policy',
            'lab_ephemeral_request_tag_condition', 'lab_ephemeral_create_tag_condition', 'lab_rds_request_tag_condition')}
    expressions['bootstrap_policy'] = attribute(block(iam, 'resource', 'aws_iam_role_policy', 'data_bootstrap'), 'policy')
    expressions['lease_lock_id'] = attribute((FOUNDATION / 'locals.tf').read_text(), 'lease_lock_id')
    expressions['authoritative_evidence_resources'] = attribute((FOUNDATION / 'storage.tf').read_text(), 'authoritative_evidence_resources')
    guard = attribute(block(variables, 'variable', 'engine_version'), 'condition', 4).replace('var.engine_version', 'engine')
    family = attribute(main, 'parameter_group_family').replace('var.engine_version', 'engine')
    expressions['engine_cases'] = ('[for engine in ["8.0.40", "8.0.46", "8.4.11", "8.4.1", "8.4", "8.0", '
        '"8.40.1", "9.0.1", "8.4.11-extra", "", null] : '
        '{version=engine, admitted=(' + guard + '), family=(' + guard + ') ? (' + family + ') : null}]')
    storage_guard = attribute(block(variables, 'variable', 'dump_storage_gib'), 'condition', 4).replace('var.dump_storage_gib', 'size')
    expressions['storage_cases'] = '[for size in [19,20,21,99,100,101,200] : {size=size, admitted=(' + storage_guard + ')}]'
    config = ('''variable "aws_region" { default = "ap-northeast-2" }
variable "account_id" { default = "942632789808" }
variable "run_id" { default = "lab-static-b-test" }
variable "approved_rds_snapshot_identifier" { default = "airbob-dataset-rehearsal-v20" }
locals {
  lab_contract = jsondecode(file("contract.json"))
  dataset_prefix = "datasets/global-growth-b-aaaaaaaaaaaaaaaa"
  lease_arn = "arn:aws:dynamodb:${var.aws_region}:${var.account_id}:table/${local.lab_contract.lease_table_name}"
  rds_arn = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-${var.run_id}"
  master_secret_arn = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:rds!db-static-b-test"
  debezium_secret_arn = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/${var.run_id}/debezium-static"
  buckets = {
    dataset = {arn="arn:aws:s3:::${local.lab_contract.dataset_bucket_name}"}
    evidence = {arn="arn:aws:s3:::${local.lab_contract.evidence_bucket_name}"}
    bundle = {arn="arn:aws:s3:::${local.lab_contract.bundle_bucket_name}"}
  }
  all_ecr_repository_arns = [for repository in local.lab_contract.ecr_repositories : repository.arn]
''')
    for name, expression in expressions.items():
        for original, replacement in replacements.items():
            expression = expression.replace(original, replacement)
        if re.search(r'\b(?:aws_[A-Za-z_0-9]+|module|data)\.', expression):
            raise AssertionError('Unexpected provider reference in isolated static expression: ' + name)
        config += '  ' + name + ' = ' + expression + '\n'
    config += '}\n'
    terraform = shutil.which('terraform')
    if not terraform:
        raise AssertionError('Terraform is required for provider-free HCL evaluation')
    with tempfile.TemporaryDirectory(prefix='airbob-b-infrastructure-unit-') as directory:
        root = Path(directory)
        (root / 'main.tf').write_text(config)
        (root / 'contract.json').write_bytes((LAB / 'tests/fixtures/lab-contract.json').read_bytes())
        (root / 'terraform.rc').write_text('disable_checkpoint = true\n')
        environment = {'PATH': os.environ.get('PATH', ''), 'CHECKPOINT_DISABLE': '1', 'TF_IN_AUTOMATION': '1',
            'TF_INPUT': '0', 'TF_CLI_CONFIG_FILE': str(root / 'terraform.rc'),
            'TF_DATA_DIR': str(root / '.terraform'), 'AWS_EC2_METADATA_DISABLED': 'true'}
        command = [terraform, '-chdir=' + str(root), 'console', '-no-color']
        result = subprocess.run(command, input='jsonencode({boundary=jsondecode(local.lab_host_boundary_policy), '
            'bootstrap=jsondecode(local.bootstrap_policy), provision=jsondecode(local.lab_rds_provision_policy), '
            'engines=local.engine_cases, storage=local.storage_cases, leaseLockId=local.lease_lock_id, '
            'boundaryBytes=length(local.lab_host_boundary_policy), provisionBytes=length(local.lab_rds_provision_policy)})\n',
            text=True, capture_output=True, timeout=30, env=environment)
        if result.returncode:
            raise AssertionError('Provider-free HCL evaluation failed: ' + result.stderr)
        return json.loads(json.loads(result.stdout))


def items(value):
    return value if isinstance(value, list) else [value]


def sid(policy, name):
    found = [statement for statement in policy['Statement'] if statement['Sid'] == name]
    if len(found) != 1:
        raise AssertionError('Expected exact statement: ' + name)
    return found[0]


class GlobalBInfrastructureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.result = evaluate_sources()
        cls.boundary, cls.bootstrap = cls.result['boundary'], cls.result['bootstrap']

    def test_explicit_80_and_84_patches_select_the_corresponding_family(self):
        observed = {row['version']: row for row in self.result['engines']}
        for version in ('8.0.40', '8.0.46', '8.4.11', '8.4.1'):
            self.assertTrue(observed[version]['admitted'])
            self.assertEqual('mysql8.4' if version in ('8.4.11', '8.4.1') else 'mysql8.0', observed[version]['family'])
        for version in ('8.4', '8.0', '8.40.1', '9.0.1', '8.4.11-extra', '', None):
            self.assertFalse(observed[version]['admitted']); self.assertIsNone(observed[version]['family'])
        group = block((LAB / 'modules/rds/main.tf').read_text(), 'resource', 'aws_db_parameter_group', 'this')
        self.assertEqual('local.parameter_group_family', attribute(group, 'family'))

    def test_existing_capacity_class_backup_and_private_instance_contract_are_unchanged(self):
        self.assertEqual([20, 100], [row['size'] for row in self.result['storage'] if row['admitted']])
        instance = block((LAB / 'modules/rds/main.tf').read_text(), 'resource', 'aws_db_instance', 'this')
        for name, expected in {'instance_class': '"db.t3.small"', 'multi_az': 'false',
                'publicly_accessible': 'false', 'storage_encrypted': 'true', 'backup_retention_period': '1',
                'auto_minor_version_upgrade': 'false', 'deletion_protection': 'false'}.items():
            self.assertEqual(expected, attribute(instance, name))
        for name in ('CreateBoundedDumpLabDbInstance', 'RestoreBoundedSnapshotLabDbInstance'):
            condition = sid(self.result['provision'], name)['Condition']
            self.assertEqual(100, condition['NumericLessThanEqualsIfExists']['rds:StorageSize'])
            self.assertEqual('db.t3.small', condition['StringEquals']['rds:DatabaseClass'])

    def test_only_exact_mysql_80_and_84_default_option_groups_are_allowed(self):
        expected = {'arn:aws:rds:ap-northeast-2:942632789808:og:default:mysql-8-0',
                    'arn:aws:rds:ap-northeast-2:942632789808:og:default:mysql-8-4'}
        for name, action in (('UseDefaultOptionGroupForDumpLabDb', 'rds:CreateDBInstance'),
                             ('UseDefaultOptionGroupForRestoreLabDb', 'rds:RestoreDBInstanceFromDBSnapshot')):
            statement = sid(self.result['provision'], name)
            self.assertEqual(expected, set(statement['Resource'])); self.assertEqual(action, statement['Action'])
            self.assertNotIn('Condition', statement)
            self.assertFalse(any('*' in arn for arn in statement['Resource']))

    def test_lease_read_is_the_same_exact_table_item_in_identity_and_boundary(self):
        expected = 'arn:aws:dynamodb:ap-northeast-2:942632789808:table/airbob-performance-lab-orchestration-lease'
        self.assertEqual('airbob-performance-lab', self.result['leaseLockId'])
        for policy in (self.boundary, self.bootstrap):
            statement = sid(policy, 'ReadBootstrapOrchestrationLease')
            self.assertEqual('dynamodb:GetItem', statement['Action']); self.assertEqual(expected, statement['Resource'])
            self.assertEqual({'ForAllValues:StringEquals': {'dynamodb:LeadingKeys': ['airbob-performance-lab']},
                              'Null': {'dynamodb:LeadingKeys': 'false'}}, statement['Condition'])
            self.assertNotIn('lab/main', statement['Condition']['ForAllValues:StringEquals']['dynamodb:LeadingKeys'])

    def test_host_cannot_write_or_scan_lease_or_create_rds_or_asg_resources(self):
        for policy in (self.boundary, self.bootstrap):
            actions = {action for statement in policy['Statement'] if statement['Effect'] == 'Allow'
                       for action in items(statement['Action'])}
            self.assertEqual({'dynamodb:GetItem'}, {action for action in actions if action.startswith('dynamodb:')})
            self.assertEqual({'rds:DescribeDBInstances'}, {action for action in actions if action.startswith('rds:')})
            self.assertEqual({'autoscaling:DescribeAutoScalingGroups'}, {action for action in actions if action.startswith('autoscaling:')})

    def test_rds_query_is_exact_and_boundary_is_bound_to_the_existing_host_run_tag(self):
        identity = sid(self.bootstrap, 'DescribeBootstrapRds')
        boundary = sid(self.boundary, 'DescribeBootstrapRds')
        self.assertEqual('rds:DescribeDBInstances', identity['Action'])
        self.assertEqual('rds:DescribeDBInstances', boundary['Action'])
        self.assertEqual('arn:aws:rds:ap-northeast-2:942632789808:db:airbob-lab-static-b-test', identity['Resource'])
        expected = boundary['Resource'].replace('${aws:PrincipalTag/RunId}', 'lab-static-b-test')
        self.assertEqual(identity['Resource'], expected)
        other = boundary['Resource'].replace('${aws:PrincipalTag/RunId}', 'lab-other')
        self.assertFalse(fnmatch.fnmatchcase(identity['Resource'], other))
        role = block((LAB / 'iam.tf').read_text(), 'resource', 'aws_iam_role', 'host')
        self.assertIn('local.ephemeral_tags', attribute(role, 'tags'))
        self.assertRegex((LAB / 'locals.tf').read_text(), r'(?m)^\s*RunId\s*= var.run_id$')

    def test_global_read_apis_use_documented_wildcard_with_regional_identity_scope(self):
        statement = sid(self.bootstrap, 'ReadBootstrapCapacityAndWriters')
        self.assertEqual({'autoscaling:DescribeAutoScalingGroups', 'cloudwatch:GetMetricStatistics'}, set(statement['Action']))
        self.assertEqual('*', statement['Resource'])
        self.assertEqual({'StringEquals': {'aws:RequestedRegion': 'ap-northeast-2'}}, statement['Condition'])
        asg = sid(self.boundary, 'DescribeBootstrapAutoScaling')
        self.assertEqual('autoscaling:DescribeAutoScalingGroups', asg['Action'])
        self.assertEqual('*', asg['Resource']); self.assertEqual(statement['Condition'], asg['Condition'])
        cloudwatch = sid(self.boundary, 'MonitoringReadOnly')
        self.assertIn('cloudwatch:GetMetricStatistics', cloudwatch['Action'])
        self.assertEqual('*', cloudwatch['Resource'])

    def test_existing_managed_secret_and_selected_dataset_reads_are_preserved(self):
        secret = sid(self.bootstrap, 'ReadRdsMasterSecret')
        boundary = sid(self.boundary, 'ReadLabRdsManagedMasterSecret')
        self.assertEqual({'secretsmanager:DescribeSecret', 'secretsmanager:GetSecretValue'}, set(secret['Action']))
        self.assertTrue(fnmatch.fnmatchcase(secret['Resource'], boundary['Resource']))
        self.assertEqual('rds', boundary['Condition']['StringLike']['aws:ResourceTag/aws:secretsmanager:owningService'])
        self.assertEqual('arn:aws:rds:ap-northeast-2:942632789808:db:airbob-lab-*',
                         boundary['Condition']['StringLike']['aws:ResourceTag/aws:rds:primaryDBInstanceArn'])
        dataset = sid(self.bootstrap, 'ReadSelectedDatasetRelease')
        self.assertEqual({'s3:GetObject', 's3:GetObjectVersion'}, set(dataset['Action']))
        self.assertEqual('arn:aws:s3:::airbob-performance-lab-dataset-942632789808/datasets/global-growth-b-aaaaaaaaaaaaaaaa/*',
                         dataset['Resource'])
        self.assertTrue(any(fnmatch.fnmatchcase(dataset['Resource'], arn)
                            for arn in sid(self.boundary, 'ReadImmutableRuntimeInputs')['Resource']))
        resource = block((LAB / 'iam.tf').read_text(), 'resource', 'aws_iam_role_policy', 'data_bootstrap')
        self.assertEqual('aws_iam_role.host["debezium"].id', attribute(resource, 'role'))
        self.assertEqual('local.services_enabled ? 1 : 0', attribute(resource, 'count'))

    def test_changed_policies_remain_below_the_existing_managed_policy_size_limit(self):
        self.assertLessEqual(self.result['boundaryBytes'], 6144)
        self.assertLessEqual(self.result['provisionBytes'], 6144)


if __name__ == '__main__':
    unittest.main()
