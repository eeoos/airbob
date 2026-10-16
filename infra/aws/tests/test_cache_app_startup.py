"""Verify retained application startup and readiness without contacting AWS."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_global_b_infrastructure import attribute

ROOT = Path(__file__).resolve().parents[3]


class CacheAppStartupTest(unittest.TestCase):
    def test_fixed_capacity_always_selects_normal_application_startup(self):
        expression = attribute((ROOT / 'infra/aws/lab/app.tf').read_text(), 'start_app_document')
        expected = ROOT / 'infra/aws/lab/templates/start-growth-b-app.sh.tftpl'
        for enabled in (False, True):
            with self.subTest(enabled=enabled), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                lab = root / 'lab'
                lab.mkdir()
                (lab / 'templates').symlink_to(ROOT / 'infra/aws/lab/templates', target_is_directory=True)
                (root / 'scripts').symlink_to(ROOT / 'infra/aws/scripts', target_is_directory=True)
                (lab / 'main.tf').write_text(
                    'variable "global_b_services" { default = true }\n'
                    + 'variable "cache_benchmark_enabled" { default = ' + str(enabled).lower() + ' }\n'
                    + 'locals {\n application_infrastructure_enabled = true\n selected = ' + expression + '\n}\n')
                result = subprocess.run(
                    ['terraform', '-chdir=' + str(lab), 'console', '-no-color'],
                    input='jsonencode(local.selected)\n', capture_output=True, text=True, timeout=30,
                    env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'})
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(json.loads(result.stdout)), expected.read_text())

    def test_application_waits_for_readiness_and_rejects_unhealthy_response(self):
        source = (ROOT / 'infra/aws/scripts/aws-lab.sh').read_text()
        start = source.index('  if [[ "$stage" == application ]]; then\n    phase4=')
        body = source[start:source.index('  write_terraform_output_evidence required', start)]
        harness = """set -eu
stage=application
lab_root=unused
run_terraform_command(){ printf '%s\\n' '{"auto_scaling_group_name":"asg","target_group_arn":"tg","alb_dns_name":"alb"}'; }
wait_for_application(){ printf 'WAIT:%s\\n' "$1"; }
curl(){ printf '{"status":"%s"}\\n' "$TEST_HEALTH"; }
fail(){ exit 1; }
"""
        for health in ('UP', 'DOWN'):
            with self.subTest(health=health):
                result = subprocess.run(['bash'], input=harness + body, capture_output=True, text=True, timeout=10,
                    env={'PATH': os.environ['PATH'], 'TEST_HEALTH': health})
                self.assertEqual(result.returncode == 0, health == 'UP', result.stderr)
                self.assertIn('WAIT:18000', result.stdout)


if __name__ == '__main__':
    unittest.main()
