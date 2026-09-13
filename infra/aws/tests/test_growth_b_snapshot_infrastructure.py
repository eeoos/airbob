"""Evaluate B snapshot Terraform gates against the snapshot tool's actual schema."""
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_global_b_infrastructure import attribute
import test_growth_b_snapshot as snapshot_tests

ROOT = Path(__file__).resolve().parents[3]
LAB = ROOT / 'infra/aws/lab'


def evaluate(change=None):
    fixture = snapshot_tests.SnapshotFixture()
    fixture.setUp()
    try:
        provenance = copy.deepcopy(fixture.provenance)
        provenance['storage']['allocatedStorageGiB'] = 100
        provenance['snapshot']['allocatedStorageGiB'] = 100
        core = provenance['contract']
        live = {'db_snapshot_arn': provenance['snapshot']['arn'], 'db_instance_identifier': core['source']['identifier'],
            'status': 'available', 'snapshot_type': 'manual', 'engine': 'mysql', 'engine_version': '8.4.11',
            'encrypted': True, 'kms_key_id': provenance['storage']['kmsKeyArn'], 'allocated_storage': 100,
            'storage_type': 'gp3', 'iops': 3000, 'tags': copy.deepcopy(provenance['snapshot']['tags'])}
        state = {'provenance': provenance, 'snapshot': live, 'instances': [], 'engine': '8.4.11',
            'sourceRunId': core['source']['identifier'].removeprefix('airbob-'), 'sourceResourceId': core['source']['resourceId'],
            'approvedSnapshot': core['snapshotIdentifier']}
        if change:
            change(state)
        raw = json.dumps(provenance)
        digest = hashlib.sha256(raw.encode()).hexdigest()
        variables = {'global_b_snapshot_restore_only': True, 'global_b_services': False, 'database_bootstrap': 'snapshot',
            'rds_engine_version': state['engine'], 'dataset_release': core['datasetId'], 'run_id': 'lab-b-snapshot-next',
            'rds_snapshot_identifier': core['snapshotIdentifier'], 'rds_snapshot_source_run_id': state['sourceRunId'],
            'rds_snapshot_source_resource_id': state['sourceResourceId'], 'account_id': '942632789808', 'aws_region': 'ap-northeast-2',
            'app_image_reference': core['application']['image'], 'bundle_commit': core['application']['mainCommit'],
            'global_b_snapshot_provenance': {'key': state.get('provenanceKey', f"datasets/{core['datasetId']}-aws-snapshots/{core['snapshotIdentifier']}/provenance-{digest}.json"),
                'version_id': 'exact-version', 'sha256': digest, 'bytes': len(raw)}}
        config = ''.join('variable "' + name + '" { default = ' + json.dumps(value) + ' }\n' for name, value in variables.items())
        config += '''locals {
          services_enabled = true
          s3 = [{body=file("provenance.json"),version_id="exact-version"}]
          live_snapshot = [jsondecode(file("snapshot.json"))]
          live_instances = [{instance_identifiers=jsondecode(file("instances.json"))}]
          lab_contract = {approved_rds_snapshot_identifier=''' + json.dumps(state['approvedSnapshot']) + '''}
        '''
        source = (LAB / 'growth-b-snapshot.tf').read_text()
        for name in ('growth_b_snapshot_selected', 'growth_b_snapshot_provenance', 'growth_b_snapshot_core',
                     'growth_b_snapshot_tool_sources', 'growth_b_snapshot_valid'):
            expression = attribute(source, name).replace('data.aws_s3_object.growth_b_snapshot_provenance', 'local.s3')
            expression = expression.replace('data.aws_db_snapshot.dataset', 'local.live_snapshot')
            expression = expression.replace('data.aws_db_instances.growth_b_snapshot_targets', 'local.live_instances')
            expression = expression.replace('${path.module}/../scripts/', str(ROOT / 'infra/aws/scripts') + '/')
            config += name + ' = ' + expression + '\n'
        config += '}\n'
        with tempfile.TemporaryDirectory(prefix='airbob-b-snapshot-hcl-') as directory:
            work = Path(directory)
            (work / 'main.tf').write_text(config)
            (work / 'provenance.json').write_text(raw)
            for name in ('snapshot', 'instances'):
                (work / (name + '.json')).write_text(json.dumps(state[name]))
            env = {'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'TF_IN_AUTOMATION': '1', 'AWS_EC2_METADATA_DISABLED': 'true'}
            result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
                input='local.growth_b_snapshot_valid\n', text=True, capture_output=True, env=env, timeout=30)
            if result.returncode:
                raise AssertionError(result.stderr)
            return result.stdout.strip() == 'true'
    finally:
        fixture.doCleanups()


class SnapshotInfrastructure(unittest.TestCase):
    def test_exact_new_b_provenance_schema_is_admitted(self):
        self.assertTrue(evaluate())

    def test_legacy_provenance_engine_and_tags_are_rejected(self):
        for change in (lambda s: s['provenance'].update(kind='legacy-promotion'),
                       lambda s: s.update(engine='8.0.46'),
                       lambda s: s['snapshot']['tags'].update(ManagedBy='dataset-publisher'),
                       lambda s: s['snapshot']['tags'].update(FlywayVersion='27')):
            with self.subTest(change=change): self.assertFalse(evaluate(change))

    def test_source_fingerprint_identity_and_live_source_absence_are_required(self):
        for change in (lambda s: s['provenance'].update(contractSha256='0' * 64),
                       lambda s: s['provenance']['sourceFreeze'].update(heldUntilSnapshotAvailable=False),
                       lambda s: s.update(sourceResourceId='db-' + 'B' * 24),
                       lambda s: s['instances'].append(s['provenance']['contract']['source']['identifier']),
                       lambda s: s['snapshot'].update(engine_version='8.4.12'),
                       lambda s: s['snapshot'].update(encrypted=False)):
            with self.subTest(change=change): self.assertFalse(evaluate(change))

    def test_foundation_exact_approval_is_still_required(self):
        self.assertFalse(evaluate(lambda s: s.update(approvedSnapshot='airbob-dataset-other-approved')))

    def test_source_host_evidence_provenance_is_bound_to_the_exact_source_and_dataset(self):
        def select(state):
            state['provenanceKey'] = f"data-bootstrap/{state['sourceRunId']}/{state['provenance']['contract']['datasetId']}-snapshot/create-001/snapshot-provenance.json"
        self.assertTrue(evaluate(select))
        def wrong_source(state):
            select(state); state['provenanceKey'] = state['provenanceKey'].replace(state['sourceRunId'], 'lab-other')
        self.assertFalse(evaluate(wrong_source))

    def test_snapshot_restore_host_does_not_receive_unversioned_input_prefix_access(self):
        from test_global_b_infrastructure import block
        policy = block((LAB / 'iam.tf').read_text(), 'resource', 'aws_iam_role_policy', 'growth_b_snapshot_restore_inputs')
        self.assertNotIn('/*', policy)
        self.assertIn('s3:VersionId', policy)
        self.assertIn('local.growth_b_snapshot_bucket', policy)

    def test_snapshot_read_policy_adds_no_host_mutations(self):
        from test_global_b_infrastructure import block
        policy = block((LAB / 'iam.tf').read_text(), 'resource', 'aws_iam_role_policy', 'growth_b_snapshot_read')
        self.assertIn('aws:RequestedRegion', policy)
        for action in ('CreateDBSnapshot', 'DeleteDBSnapshot', 'RestoreDBInstanceFromDBSnapshot', 'LookupEvents', 'PutObject'):
            self.assertNotIn(action, policy)
        self.assertIn('airbob-dataset-b-*', policy)


if __name__ == '__main__':
    unittest.main()
