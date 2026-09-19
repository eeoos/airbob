"""Actual shell output admission with fake Terraform/S3 boundaries only."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

OPERATOR = Path(__file__).resolve().parents[1] / 'scripts/aws-lab.sh'


class DestroyOutputs(unittest.TestCase):
    def run_output(self, *, power=None, extra=None, sensitive=False, read_failed=False, requirement='required'):
        source = OPERATOR.read_text()
        start = source.index('write_terraform_output_evidence() {\n')
        function = source[start:source.index('\n}\n', start) + 3]
        outputs = {name: {'sensitive': False, 'value': {}} for name in (
            'persistent_resource_contract', 'phase2_contract', 'phase3_contract',
            'phase4_contract', 'run_identity', 'state_boundaries')}
        outputs['lab_power'] = {'sensitive': sensitive, 'value': power}
        if extra: outputs[extra] = {'sensitive': False, 'value': {}}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); (root / 'source.json').write_text(json.dumps(outputs))
            script = '''set -euo pipefail
fail() { printf '%s\\n' "$1" >&2; exit 1; }
assert_lease() { printf '%s\\n' lease >> "$temp_dir/events"; }
prepare_lab_backend() { printf '%s\\n' backend >> "$temp_dir/events"; }
run_terraform_command() {
  [[ "$*" == "Terraform output evidence read -chdir=/synthetic/lab output -json" ]]
  [[ "$read_failed" == false ]] || return 1
  cat "$temp_dir/source.json"
}
aws() {
  [[ "$1:$2" == s3api:put-object ]]
  printf '%s\\n' evidence-published >> "$temp_dir/events"
  local source=''
  while [[ "$#" -gt 0 ]]; do
    if [[ "$1" == --body ]]; then source=$2; shift; fi
    shift
  done
  cp "$source" "$temp_dir/published.json"
}
run_id=lab-output-test
fencing_token=106
lab_root=/synthetic/lab
evidence_bucket=synthetic-evidence
AWS_REGION=ap-northeast-2
'''
            script += function + '\nwrite_terraform_output_evidence "$requirement"\nprintf DESTROY_ADMISSION_PASSED\n'
            process = subprocess.run(['bash'], input=script, text=True, capture_output=True,
                env=dict(os.environ, temp_dir=directory, read_failed=str(read_failed).lower(), requirement=requirement), timeout=10)
            published = json.loads((root / 'published.json').read_text())
            self.assertEqual(['lease', 'backend', 'evidence-published'], (root / 'events').read_text().splitlines())
            return process, published

    def test_existing_running_or_paused_power_output_is_retained(self):
        for phase in ('running', 'stopped'):
            power = {'phase': phase, 'request': {'phase': phase}, 'instance_ids': {'app': 'i-original'}}
            with self.subTest(phase=phase):
                result, evidence = self.run_output(power=power)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual('DESTROY_ADMISSION_PASSED', result.stdout)
                self.assertEqual('available', evidence['status'])
                self.assertEqual(power, evidence['outputs']['lab_power'])

    def test_unselected_null_power_output_is_valid(self):
        result, evidence = self.run_output()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIsNone(evidence['outputs']['lab_power'])

    def test_unknown_or_sensitive_output_still_blocks_required_destroy(self):
        for options in ({'extra': 'unreviewed_output'}, {'sensitive': True}):
            with self.subTest(options=options):
                result, evidence = self.run_output(**options)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('Required Terraform output evidence is unavailable', result.stderr)
                self.assertEqual('', result.stdout)
                self.assertEqual('unavailable', evidence['status']); self.assertIsNone(evidence['outputs'])

    def test_terraform_read_failure_has_explicit_error_and_unavailable_evidence(self):
        result, evidence = self.run_output(read_failed=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('no destroy was started', result.stderr)
        self.assertEqual('unavailable', evidence['status'])

    def test_explicit_best_effort_can_record_unavailable_without_claiming_output(self):
        result, evidence = self.run_output(read_failed=True, requirement='best-effort')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('unavailable', evidence['status']); self.assertIsNone(evidence['outputs'])


if __name__ == '__main__': unittest.main()
