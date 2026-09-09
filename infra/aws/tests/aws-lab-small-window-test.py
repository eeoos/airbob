#!/usr/bin/env python3
"""Exercise the real window selector without contacting AWS."""
from pathlib import Path
import subprocess
import unittest

source = (Path(__file__).parents[1] / 'scripts/aws-lab.sh').read_text()
start = source.index('configure_execution_window() {')
function = source[start:source.index('\nconfigure_execution_window\n', start)]


class WindowTest(unittest.TestCase):
    def test_app_probe_admission_and_destroy_with_creation_inputs(self):
        start = source.index('growth_app_read=${GROWTH_APP_READ_QUALIFICATION:-false}')
        block = source[start:source.index('\n# Short timing', start)]
        valid = dict(action='up', qualification_only='true', operator_window='small-qualification',
            repo_root='/synthetic', GROWTH_APP_READ_QUALIFICATION='true', GROWTH_APP_COMMIT='a'*40,
            GROWTH_APP_JAR_SHA256='b'*64, FAKE_GIT_STATUS='0')
        cases = [(valid, True), (dict(valid, action='down'), True),
            (dict(valid, qualification_only='false'), False), (dict(valid, operator_window='standard'), False),
            (dict(valid, GROWTH_APP_JAR_SHA256=''), False), (dict(valid, GROWTH_APP_COMMIT='main'), False),
            (dict(valid, FAKE_GIT_STATUS='1'), False), (dict(valid, GROWTH_APP_READ_QUALIFICATION='false'), False)]
        for values, accepted in cases:
            with self.subTest(values=values):
                setup = 'set -euo pipefail\nfail() { exit 1; }; git() { return "$FAKE_GIT_STATUS"; };\n'
                setup += ''.join(k + "='" + v + "'\n" for k, v in values.items())
                result = subprocess.run(['bash', '-c', setup + block + '\nprintf "%s:%s:%s" "$growth_app_read" "$growth_app_commit" "$growth_app_jar_sha256"'],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode == 0, accepted)
                if values['action'] == 'down':
                    self.assertEqual(result.stdout, 'false::')

    def test_selected_budget_and_forbidden_actions(self):
        cases = [
            ('up', 'true', 'small-qualification', 0, '1800 900 3600'),
            ('up', 'false', 'small-qualification', 1, None),
            ('switch', 'false', 'small-qualification', 1, None),
            ('down', 'false', 'small-qualification', 0, '2700 0 3600'),
            ('status', 'false', 'small-qualification', 0, '2700 0 3600'),
            ('up', 'true', 'standard', 0, '18000 2400 21600'),
            ('up', 'true', 'bad', 1, None),
        ]
        for action, qualification, window, code, expected in cases:
            with self.subTest(action=action, qualification=qualification, window=window):
                variables = dict(action=action, qualification_only=qualification,
                                 AWS_LAB_EXECUTION_WINDOW=window, COMMAND_DEADLINE_SECONDS='18000',
                                 UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS='2400' if action == 'up' else '0',
                                 CREDENTIAL_SESSION_SECONDS='21600')
                setup = 'set -euo pipefail\nfail() { exit 1; };\n'
                setup += ''.join(key + '=' + value + '\n' for key, value in variables.items())
                result = subprocess.run(['bash', '-c', setup + function + '\nconfigure_execution_window\n'
                    'printf "%s %s %s" "$COMMAND_DEADLINE_SECONDS" "$UP_FAILURE_CLEANUP_ALLOWANCE_SECONDS" "$CREDENTIAL_SESSION_SECONDS"'],
                    capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, code)
                if expected is not None:
                    self.assertEqual(result.stdout, expected)
                if action == 'up' and window == 'small-qualification' and code == 0:
                    command, cleanup, session = map(int, result.stdout.split())
                    margins = 300 + 300
                    self.assertLess(command + cleanup + margins, session)
                    self.assertGreaterEqual(cleanup, 900)


if __name__ == '__main__':
    unittest.main()
