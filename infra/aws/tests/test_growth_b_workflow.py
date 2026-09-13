"""Execute the real workflow's admission body without credentials or cloud calls."""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest

WORKFLOW = Path(__file__).resolve().parents[3] / '.github/workflows/aws-performance-lab.yml'
COMMIT = 'a' * 40


class WorkflowAdmission(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = WORKFLOW.read_text()
        section = cls.source.split('      - name: Verify reviewed main commit and closed operation inputs\n', 1)[1].split('      - name:', 1)[0]
        cls.body = textwrap.dedent(section.split("          python3 - <<'PYCODE'\n", 1)[1].split('          PYCODE', 1)[0])
        compile(cls.body, str(WORKFLOW), 'exec')

    def run_gate(self, action='prepare', operation=None, *, expected=COMMIT, branch='refs/heads/main',
                 head=COMMIT, bootstrap='dump', policy='isolated-read', raw=None, deadline=None, event='workflow_dispatch'):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            git = root / 'git'
            git.write_text('#!/bin/sh\nprintf "%s\\n" "$TEST_REVIEWED_HEAD"\n'); git.chmod(0o700)
            env = {'PATH': directory + ':' + os.environ['PATH'], 'TEST_REVIEWED_HEAD': head,
                'GITHUB_SHA': COMMIT, 'GITHUB_REF': branch, 'GITHUB_ENV': str(root / 'github-env'),
                'AIRBOB_EVENT_NAME': event, 'AIRBOB_ACTION': action,
                'AIRBOB_EXPECTED_EXECUTION_COMMIT': expected, 'AIRBOB_B_OPERATION': raw if raw is not None else json.dumps(operation or {}),
                'AIRBOB_DNS_MODE': 'direct-only', 'AIRBOB_MODE': 'performance', 'AIRBOB_POLICY': policy,
                'AIRBOB_DATABASE_BOOTSTRAP': bootstrap, 'AIRBOB_LOAD_GENERATOR_ENABLED': 'false',
                'AIRBOB_APPROVED_EXECUTION_DEADLINE_EPOCH': str(int(time.time()) + 86400) if deadline is None else deadline}
            result = subprocess.run([sys.executable, '-c', self.body], env=env, text=True, capture_output=True,
                timeout=10, cwd=WORKFLOW.parents[2])
            return result.returncode, (root / 'github-env').read_text() if (root / 'github-env').exists() else ''

    def test_exact_reviewed_main_is_required_before_any_oidc(self):
        self.assertEqual(0, self.run_gate()[0])
        for overrides in ({'expected': ''}, {'expected': 'b'*40}, {'head': 'b'*40}, {'branch': 'refs/heads/feature'}):
            with self.subTest(overrides=overrides): self.assertNotEqual(0, self.run_gate(**overrides)[0])
        self.assertLess(self.source.index('Verify reviewed main commit'), self.source.index('Configure short-lived AWS credentials'))
        checkout = self.source.split('      - name: Checkout\n', 1)[1].split('      - name:', 1)[0]
        self.assertNotIn('ref:', checkout)

    def test_initial_class_selection_uses_the_existing_closed_operation_input(self):
        code, env = self.run_gate('prepare', {'rdsInstanceClass': 'db.m6i.large'})
        self.assertEqual(0, code); self.assertIn('B_RDS_INSTANCE_CLASS=db.m6i.large', env)
        for operation in ({'rdsInstanceClass': 'db.m6i.xlarge'}, {'rdsInstanceClass': 'db.m6i.large', 'autoUpsize': True},
                          {'rdsInstanceClass': 'db.m6i.large', 'classRehearsal': {}}):
            with self.subTest(operation=operation): self.assertNotEqual(0, self.run_gate('prepare', operation)[0])
        ref = {'key': 'data-bootstrap/lab-small/global-growth-b-' + 'a'*16 + '-rds-class.json',
            'versionId': 'qualified-version', 'sha256': 'b'*64, 'bytes': 10000}
        self.assertEqual(0, self.run_gate('prepare', {'rdsInstanceClass': 'db.m6i.large', 'classRehearsal': ref})[0])
        self.assertEqual(0, self.run_gate('snapshot-restore', {'provenanceSha256': 'c'*64,
            'rdsInstanceClass': 'db.m6i.large', 'classRehearsal': ref}, bootstrap='snapshot')[0])
        self.assertNotEqual(0, self.run_gate('up', {'rdsInstanceClass': 'db.m6i.large'})[0])
        services = {'serviceRelease': 'service-01', 'serviceManifestSha256': 'c'*64, 'stage': 'dependencies'}
        self.assertNotEqual(0, self.run_gate('services', services | {'rdsInstanceClass': 'db.m6i.large'})[0])
        inputs = self.source.split('  workflow_dispatch:\n    inputs:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertEqual(25, len(re.findall(r'^      [a-z][a-z_]*:', inputs, re.MULTILINE)))

    def test_services_map_closed_stage_inputs_to_controller_environment(self):
        selected = {'serviceRelease': 'service-01', 'serviceManifestSha256': 'c'*64, 'stage': 'dependencies'}
        status, env = self.run_gate('services', selected)
        self.assertEqual(0, status); self.assertIn('B_SERVICE_SHA256=' + 'c'*64, env)
        self.assertNotEqual(0, self.run_gate('services', selected | {'unused': True})[0])
        self.assertNotEqual(0, self.run_gate('services', selected | {'stage': 'application'})[0])
        status, env = self.run_gate('services', selected | {'stage': 'application', 'readinessVersionId': 'version-1', 'readinessSha256': 'd'*64})
        self.assertEqual(0, status); self.assertIn('B_READINESS_VERSION_ID=version-1', env)

    def test_native_search_requires_reviewed_source_and_original_dump_stage_before_oidc(self):
        sys.path.insert(0, str(WORKFLOW.parents[2] / 'infra/aws/scripts'))
        import growth_b_search_controller as native
        selected = {'schemaVersion': 1, 'kind': native.KIND, 'stage': 'native-restore',
            'operationId': 'native-final-01', 'runId': 'lab-source-01', 'datasetId': native.DATASET,
            'serviceRelease': 'dependencies-01', 'executionCommit': COMMIT,
            'sourceArchiveSha256': native.source_archive()[1]['sha256'],
            'manifest': {'key': f'datasets/{native.DATASET}-aws-service/dependencies-01/aws-service.json',
                'versionId': 'actual-service-v1', 'sha256': 'c' * 64, 'bytes': 3000}}
        status, environment = self.run_gate('services', selected)
        self.assertEqual(0, status)
        lines = dict(line.split('=', 1) for line in environment.splitlines())
        self.assertEqual('native-restore', lines['B_SERVICE_STAGE'])
        self.assertEqual(selected, json.loads(lines['B_NATIVE_OPERATION_JSON']))
        for patch in ({'sourceArchiveSha256': 'f' * 64}, {'executionCommit': 'b' * 40}, {'extra': True},
                      {'operationId': 'native\nAWS_PROFILE=other'}):
            with self.subTest(patch=patch):
                status, environment = self.run_gate('services', selected | patch)
                self.assertNotEqual(0, status); self.assertEqual('', environment)
        self.assertNotEqual(0, self.run_gate('services', selected, bootstrap='snapshot')[0])
        self.assertNotEqual(0, self.run_gate('services', selected, policy='integrated-smoke')[0])
        self.assertNotEqual(0, self.run_gate('services', selected, deadline='')[0])

    def test_native_snapshot_stage_requires_explicit_target_proofs_and_snapshot_mode(self):
        from test_growth_b_search_controller import snapshot_operation
        selected = snapshot_operation()
        status, env = self.run_gate('services', selected, bootstrap='snapshot')
        self.assertEqual(0, status)
        values = dict(line.split('=', 1) for line in env.splitlines())
        self.assertEqual('native-snapshot-restore', values['B_SERVICE_STAGE'])
        self.assertEqual(selected, json.loads(values['B_NATIVE_OPERATION_JSON']))
        self.assertNotEqual(0, self.run_gate('services', selected, bootstrap='dump')[0])
        for changes in ({'targetPreparation': {}}, {'sourceEvidence': {}}, {'stage': 'native-restore'}):
            with self.subTest(changes=changes):
                status, env = self.run_gate('services', selected | changes, bootstrap='snapshot')
                self.assertNotEqual(0, status); self.assertEqual('', env)

    def test_snapshot_is_explicit_and_cannot_use_legacy_dump_selection(self):
        selected = {'provenanceSha256': 'd'*64}
        status, env = self.run_gate('snapshot-restore', selected, bootstrap='snapshot')
        self.assertEqual(0, status); self.assertIn('B_SNAPSHOT_PROVENANCE_SHA256=' + 'd'*64, env)
        self.assertNotEqual(0, self.run_gate('snapshot-restore', selected)[0])
        self.assertNotEqual(0, self.run_gate('up', selected)[0])
        key = 'data-bootstrap/lab-source/global-growth-b-' + 'a'*16 + '-snapshot/create-001/snapshot-provenance.json'
        status, env = self.run_gate('snapshot-restore', selected | {'provenanceKey': key}, bootstrap='snapshot')
        self.assertEqual(0, status); self.assertIn('B_SNAPSHOT_PROVENANCE_KEY=' + key, env)
        self.assertNotEqual(0, self.run_gate('snapshot-restore', selected | {'provenanceKey': key.replace('create-001', '../escape')}, bootstrap='snapshot')[0])

    def test_all_snapshot_host_actions_are_closed_and_use_retained_run(self):
        selected = {'operationId': 'operation-001', 'manifestSha256': 'a'*64}
        for action in ('snapshot-create', 'snapshot-prepare', 'snapshot-retire'):
            with self.subTest(action=action):
                status, env = self.run_gate(action, selected)
                self.assertEqual(0, status); self.assertIn('B_SNAPSHOT_OPERATION_ID=operation-001', env)
                self.assertNotEqual(0, self.run_gate(action, selected | {'unused': True})[0])
                self.assertNotEqual(0, self.run_gate(action, selected | {'operationId': 'other\nAWS_PROFILE=x'})[0])
        self.assertNotEqual(0, self.run_gate('snapshot-unreviewed', selected)[0])
        run_selector = next(line for line in self.source.splitlines() if 'RUN_ID: ${{' in line)
        for action in ('snapshot-create', 'snapshot-prepare', 'snapshot-retire'):
            self.assertNotIn(action, run_selector)

    def test_asg_probe_has_one_closed_detail_get_and_retains_run_deadline(self):
        operation = {'operationId': 'asg-001', 'serviceRelease': 'service-01', 'serviceManifestSha256': 'a'*64,
            'readinessVersionId': 'ready-v1', 'readinessSha256': 'b'*64, 'detailPath': '/api/v1/accommodations/17'}
        status, env = self.run_gate('asg-probe', operation)
        self.assertEqual(0, status); self.assertIn('B_ASG_PROBE_OPERATION_JSON=', env)
        for patch in ({'detailPath': 'https://elsewhere.invalid/'}, {'extra': True}, {'operationId': '../unsafe'}):
            self.assertNotEqual(0, self.run_gate('asg-probe', operation | patch)[0])
        self.assertNotEqual(0, self.run_gate('asg-probe', operation, deadline='')[0])
        self.assertNotIn('asg-probe', next(line for line in self.source.splitlines() if 'RUN_ID: ${{' in line))
        self.assertIn('if: always() && github.event_name', self.source)
        self.assertIn('Preserve B ASG probe recovery evidence', self.source)

    def test_duplicate_json_keys_and_environment_injection_are_rejected(self):
        self.assertNotEqual(0, self.run_gate('services', raw='{"stage":"dependencies","stage":"application"}')[0])
        selected = {'serviceRelease': 'service-01\nAWS_PROFILE=other', 'serviceManifestSha256': 'c'*64, 'stage': 'dependencies'}
        status, env = self.run_gate('services', selected)
        self.assertNotEqual(0, status); self.assertEqual('', env)

    def cdc_operation(self):
        sys.path.insert(0, str(WORKFLOW.parents[2] / 'infra/aws/scripts'))
        from growth_b_cdc_supervisor import source_archive
        dataset, run, release = 'global-growth-b-' + 'b'*16, 'lab-source', 'service-01'
        return {'schemaVersion': 1, 'kind': 'global-b-aws-source-r4-operation', 'stage': 'all',
            'operationId': 'source-r4-01', 'runId': run, 'datasetId': dataset, 'serviceRelease': release,
            'executionCommit': COMMIT, 'sourceArchiveSha256': source_archive()[1]['sha256'],
            'manifest': {'key': f'datasets/{dataset}-aws-service/{release}/aws-service.json',
                'versionId': 'manifest-v1', 'sha256': 'c'*64, 'bytes': 1000},
            'readiness': {'key': f'data-bootstrap/{run}/{dataset}-service-{release}.json',
                'versionId': 'readiness-v1', 'sha256': 'd'*64, 'bytes': 1000}}

    def test_source_r4_binds_the_actual_source_archive_and_reviewed_main(self):
        operation = self.cdc_operation()
        status, environment = self.run_gate('cdc', operation)
        self.assertEqual(0, status)
        exported = environment.removeprefix('B_CDC_OPERATION_JSON=').strip()
        self.assertEqual(operation, json.loads(exported))
        for patch in ({'executionCommit': 'e'*40}, {'sourceArchiveSha256': 'f'*64},
                      {'stage': 'reset'}, {'unreviewedOverride': True}, {'operationId': 'r4\nAWS_PROFILE=x'}):
            with self.subTest(patch=patch):
                status, environment = self.run_gate('cdc', operation | patch)
                self.assertNotEqual(0, status); self.assertEqual('', environment)
        self.assertNotEqual(0, self.run_gate('cdc', operation, deadline='')[0])
        self.assertNotEqual(0, self.run_gate('cdc', operation, policy='integrated-smoke')[0])

    def test_source_r4_cannot_select_another_run_or_unversioned_service(self):
        operation = self.cdc_operation()
        for patch in ({'readiness': operation['readiness'] | {'key': 'data-bootstrap/lab-other/readiness.json'}},
                      {'manifest': operation['manifest'] | {'versionId': 'null'}},
                      {'manifest': operation['manifest'] | {'key': operation['manifest']['key'] + '/extra'}}):
            with self.subTest(patch=patch): self.assertNotEqual(0, self.run_gate('cdc', operation | patch)[0])
        self.assertNotIn("'cdc'", next(line for line in self.source.splitlines() if 'RUN_ID: ${{' in line))
        self.assertIn('CDC_EVIDENCE_DIR: ${{ runner.temp }}/airbob-b-cdc', self.source)
        self.assertIn('${{ runner.temp }}/airbob-b-cdc/**/public/**', self.source)

    def test_initial_b_workflow_selects_the_cache_disabled_service_baseline(self):
        self.assertIn("(inputs.action == 'prepare' || inputs.action == 'snapshot-restore') && 'false'", self.source)

    def test_input_count_and_six_hour_budget_remain_bounded(self):
        inputs = self.source.split('  workflow_dispatch:', 1)[1].split('\nconcurrency:', 1)[0]
        self.assertEqual(len(re.findall(r'^      [a-z0-9_]+:$', inputs, re.M)), 25)
        actions = "(inputs.action == 'up' || inputs.action == 'prepare' || inputs.action == 'services' || inputs.action == 'cdc' || inputs.action == 'asg-probe' || startsWith(inputs.action, 'snapshot-'))"
        self.assertIn('role-duration-seconds: ${{ ' + actions + ' && 21600 || 7200 }}', self.source)
        self.assertIn('timeout-minutes: ${{ ' + actions + ' && 359 || 120 }}', self.source)

    def test_b_requires_one_canonical_future_deadline_before_oidc(self):
        for invalid in ('', '0', '01700000000', '1700000000', '2000000000\nAWS_PROFILE=x', '2000000000.0'):
            with self.subTest(deadline=invalid): self.assertNotEqual(0, self.run_gate(deadline=invalid)[0])
        short = str(int(time.time()) + 21500)
        self.assertNotEqual(0, self.run_gate(deadline=short)[0])
        self.assertNotEqual(0, self.run_gate('snapshot-restore', {'provenanceSha256': 'd'*64}, bootstrap='snapshot', deadline=short)[0])
        selected = {'operationId': 'operation-001', 'manifestSha256': 'a'*64}
        for action in ('snapshot-create', 'snapshot-prepare', 'snapshot-retire'):
            self.assertNotEqual(0, self.run_gate(action, selected, deadline='')[0])
        service = {'serviceRelease': 'service-01', 'serviceManifestSha256': 'c'*64, 'stage': 'dependencies'}
        self.assertNotEqual(0, self.run_gate('services', service, deadline='')[0])
        self.assertIn('APPROVED_EXECUTION_DEADLINE_EPOCH: ${{ inputs.approved_execution_deadline_epoch }}', self.source)

    def test_legacy_and_scheduled_cleanup_do_not_require_a_future_b_deadline(self):
        for action in ('up', 'status', 'switch', 'down'):
            with self.subTest(action=action): self.assertEqual(0, self.run_gate(action, deadline='')[0])
        self.assertEqual(0, self.run_gate('down', deadline='1700000000', event='schedule')[0])


class OperatorAsgCompletion(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (WORKFLOW.parents[2] / 'infra/aws/scripts/aws-lab.sh').read_text()
        cls.function = 'complete_global_b_asg_probe() {' + source.split('complete_global_b_asg_probe() {', 1)[1].split('continue_global_b_asg_probe()', 1)[0]

    def complete(self, operation, receipt, status):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'operation.json').write_text(json.dumps(operation))
            (root / 'receipt.json').write_text(json.dumps(receipt))
            setup = 'set -euo pipefail\nfail() { printf "%s\\n" "$1" >&2; exit 1; }\nrun_id=lab-reviewed\n'
            return subprocess.run(['bash', '-c', setup + self.function + '\ncomplete_global_b_asg_probe "$1" "$2" "$3"',
                'bash', str(root/'operation.json'), str(root/'receipt.json'), str(status)],
                capture_output=True, text=True, timeout=10)

    def receipt(self, resumed=False):
        return {'state': 'OBSERVATION_FAILED_BASELINE_RESTORED' if resumed else 'OBSERVATION_FINISHED_BASELINE_RESTORED',
            'cleanupComplete': True, 'baselineTerminatedByTool': False, 'baselineProtectionRestored': True,
            'baselineRetainedAndHealthy': True, 'finalCapacity': {'min': 1, 'desired': 1, 'max': 1},
            'r7OverallComplete': False, 'observationComplete': not resumed, **({'resumedReceiptSha256': 'a'*64} if resumed else {})}

    def test_observation_success_requires_actual_observation_and_restored_baseline(self):
        result = self.complete({}, self.receipt(), 0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('b_asg_probe_complete=true', result.stdout)
        self.assertIn('r7_overall_complete=false', result.stdout)
        for patch, status in (({'observationComplete': False}, 0), ({'baselineProtectionRestored': False}, 0),
                              ({'cleanupComplete': False}, 0), ({'finalCapacity': {'min': 1, 'desired': 2, 'max': 2}}, 0), ({}, 1)):
            with self.subTest(patch=patch, status=status):
                self.assertNotEqual(0, self.complete({}, self.receipt() | patch, status).returncode)

    def test_exact_cleanup_only_success_does_not_convert_failed_observation_to_success(self):
        operation = {'resume': {'receipt': {'sha256': 'a'*64}}}
        result = self.complete(operation, self.receipt(True), 1)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('b_asg_cleanup_complete=true', result.stdout)
        self.assertIn('b_asg_probe_complete=false', result.stdout)
        self.assertNotEqual(0, self.complete({}, self.receipt(True), 1).returncode)
        for patch, status in (({'resumedReceiptSha256': 'b'*64}, 1), ({'observationComplete': True}, 1),
                              ({'baselineRetainedAndHealthy': False}, 1), ({'baselineTerminatedByTool': True}, 1),
                              ({'r7OverallComplete': True}, 1), ({}, 124), ({}, 0)):
            with self.subTest(patch=patch, status=status):
                self.assertNotEqual(0, self.complete(operation, self.receipt(True) | patch, status).returncode)


class OperatorExecutionDeadline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = (WORKFLOW.parents[2] / 'infra/aws/scripts/aws-lab.sh').read_text()
        cls.functions = cls.source.split('require_global_b_execution_deadline() {', 1)[1].split('validate_snapshot_bootstrap_inputs()', 1)[0]
        cls.functions = 'require_global_b_execution_deadline() {' + cls.functions

    def run_shell(self, body, *, deadline='2000086400', now=2000000000, manifest=None):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'operator.json'
            path.write_text(json.dumps(manifest or {'approvedExecutionDeadlineEpoch': int(deadline), 'expiresAt': deadline}))
            setup = '''set -euo pipefail
fail() { printf '%s\\n' "$1" >&2; exit 1; }
date() { printf '%s\\n' "$TEST_NOW"; }
approved_execution_deadline_epoch=$TEST_DEADLINE
UP_CREDENTIAL_SESSION_SECONDS=21600
LEASE_DEADLINE_SECONDS=20700
'''
            return subprocess.run(['bash', '-c', setup + self.functions + body],
                env={'PATH': os.environ['PATH'], 'TEST_NOW': str(now), 'TEST_DEADLINE': deadline, 'TEST_MANIFEST': str(path), 'script_dir': str(WORKFLOW.parents[2] / 'infra/aws/scripts')},
                capture_output=True, text=True, timeout=10)

    def test_new_run_caps_expiry_at_common_deadline_and_keeps_shorter_ttl(self):
        result = self.run_shell('set_global_b_execution_expiry "$TEST_NOW" 24; printf "%s\\n" "$expires_at"', deadline='2000043200')
        self.assertEqual(0, result.returncode, result.stderr); self.assertEqual('2000043200\n', result.stdout)
        result = self.run_shell('set_global_b_execution_expiry "$TEST_NOW" 6; printf "%s\\n" "$expires_at"')
        self.assertEqual(0, result.returncode, result.stderr); self.assertEqual('2000021600\n', result.stdout)

    def test_new_run_rejects_expired_short_or_malformed_deadlines_without_mutation(self):
        for deadline in ('', '2000000000', '1999999999', '2000021599', '02000043200', '2e9', '2000043200\n'):
            with self.subTest(deadline=deadline):
                result = self.run_shell('set_global_b_execution_expiry "$TEST_NOW" 24; printf mutation', deadline=deadline, manifest={'unused': True})
                self.assertNotEqual(0, result.returncode); self.assertNotIn('mutation', result.stdout)
        self.assertEqual(0, self.run_shell('set_global_b_execution_expiry "$TEST_NOW" 24', deadline='2000021600').returncode)

    def test_retained_run_preserves_both_original_expiry_and_approved_deadline(self):
        body = 'expires_at=2000043200; validate_retained_global_b_execution_deadline "$TEST_MANIFEST"; printf "%s\\n" "$expires_at"'
        result = self.run_shell(body)
        self.assertEqual(0, result.returncode, result.stderr); self.assertEqual('2000043200\n', result.stdout)
        for manifest in ({'approvedExecutionDeadlineEpoch': 2000086401}, {'approvedExecutionDeadlineEpoch': 2000086399},
                         {'approvedExecutionDeadlineEpoch': '2000086400'}, {'unused': True}):
            with self.subTest(manifest=manifest): self.assertNotEqual(0, self.run_shell(body, manifest=manifest).returncode)
        self.assertNotEqual(0, self.run_shell(body, now=2000022500).returncode)
        self.assertNotEqual(0, self.run_shell(body, deadline='2000021600').returncode)

    def test_every_b_continuation_rechecks_deadline_before_lease_and_new_run_persists_it(self):
        for name, next_name in (('continue_global_b_snapshot_operation', 'write_global_b_snapshot_admission'),
                                ('continue_global_b_native_search', 'continue_global_b_services'),
                                ('continue_global_b_services', None)):
            section = self.source.split(name + '() {', 1)[1]
            section = section.split(next_name + '() {', 1)[0] if next_name else section.split('\ncase "$action" in', 1)[0]
            before_guard = section.split('  start_mutation_guard', 1)[0]
            self.assertEqual(2, before_guard.count('validate_retained_global_b_execution_deadline "$original"'))
        new_run = self.source.split('    now_epoch=$(date +%s)\n', 1)[1].split('    start_mutation_guard', 1)[0]
        self.assertEqual(2, new_run.count('set_global_b_execution_expiry'))
        self.assertIn('approvedExecutionDeadlineEpoch:$deadline', self.source)
        self.assertIn('approvedExecutionDeadlineEpoch:$approvedDeadline', self.source)


if __name__ == '__main__':
    unittest.main()
