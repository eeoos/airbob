"""Execute the Mac provisioning exit path with cloud commands replaced locally."""
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class MacProvisioningExit(unittest.TestCase):
    def run_exit(self, target):
        source = (ROOT / 'infra/aws/scripts/aws-lab.sh').read_text()
        start = source.index('    verify_retained_rds_class\n', source.index('current_stage=services-and-data-bootstrap'))
        end = source.index('    kafka_instance_id=', start)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'target.json').write_text(json.dumps(target))
            body = '\n'.join([
                'set -euo pipefail', 'temp_dir=' + shlex.quote(directory),
                'global_b_import_from_mac=true', 'global_b_snapshot_restore_only=false',
                'global_b_prepare_only=true', 'run_id=lab-mac-test', 'fencing_token=1',
                'expires_at=2000000000', 'lab_root=/unused', 'phase2=\'{"services":{}}\'',
                'up_in_progress=true', 'verify_retained_rds_class() { :; }',
                'run_terraform_command() { cat "$temp_dir/target.json"; }',
                'write_terraform_output_evidence() { touch "$temp_dir/output-saved"; }',
                'fail() { exit 23; }', source[start:end], 'exit 99',
            ])
            result = subprocess.run(['bash', '-c', body], capture_output=True, text=True,
                                    timeout=10, env={'PATH': os.environ['PATH']})
            return result, (root / 'output-saved').exists()

    def test_mac_target_returns_before_selecting_a_missing_preparation_host(self):
        result, saved = self.run_exit({'selected': True})
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(saved)
        self.assertIn('mac_import_target_ready=true', result.stdout)
        self.assertIn('sql_import_complete=false', result.stdout)
        self.assertNotIn('preparation_complete=true', result.stdout)

    def test_missing_mac_output_cannot_claim_target_ready(self):
        for target in (None, {}, {'selected': False}):
            with self.subTest(target=target):
                result, saved = self.run_exit(target)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(saved)
                self.assertNotIn('mac_import_target_ready=true', result.stdout)


if __name__ == '__main__':
    unittest.main()
