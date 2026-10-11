"""The optional cache topology must fit CLI-login credentials without weakening lease guards."""
import os
from pathlib import Path
import subprocess
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'scripts/aws-lab.sh'


class CacheTopologyBudgetTest(unittest.TestCase):
    def budget(self, enabled, keep):
        source = SCRIPT.read_text()
        constants = source.split('fail() {', 1)[0]
        section = source.split('if [[ "$action" == up ]]; then\n  COMMAND_DEADLINE_SECONDS=', 1)[1]
        section = 'if [[ "$action" == up ]]; then\n  COMMAND_DEADLINE_SECONDS=' + section.split(
            '# Short timing values', 1)[0]
        body = constants + '\nfail() { exit 23; }\naction=up\n' + section + """
printf '%s %s %s %s\n' "$COMMAND_DEADLINE_SECONDS" "$CREDENTIAL_SESSION_SECONDS" \
  "$UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS" "$LEASE_TRANSITION_MARGIN_SECONDS"
"""
        return subprocess.run(['bash', '-c', body], capture_output=True, text=True,
                              env=os.environ | {'cache_benchmark_enabled': enabled, 'KEEP_ON_FAILURE': keep})

    def test_cache_transition_retains_all_margins_inside_one_hour(self):
        result = self.budget('true', 'true')
        self.assertEqual(result.returncode, 0)
        command, credentials, cleanup, transition = map(int, result.stdout.split())
        self.assertEqual(credentials, 3600)
        self.assertEqual(command, 2400)
        self.assertGreaterEqual(cleanup, 300)
        self.assertEqual(transition, 300)
        self.assertLessEqual(command + cleanup + transition + 300, credentials)

    def test_cache_transition_cannot_promise_full_automatic_teardown(self):
        self.assertEqual(self.budget('true', 'false').returncode, 23)

    def test_normal_bootstrap_keeps_its_existing_six_hour_budget(self):
        result = self.budget('false', 'false')
        self.assertEqual(result.returncode, 0)
        self.assertEqual(tuple(map(int, result.stdout.split())), (18000, 21600, 2400, 300))


if __name__ == '__main__':
    unittest.main()
