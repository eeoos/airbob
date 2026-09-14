"""Run the production SSM readiness gate with an isolated host and installer.

Terraform evaluates the real command/document expressions without providers.
Only the host path, sleep clock, and installer are substituted for execution.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_global_b_infrastructure import LAB, attribute, block


def connect_command(selected=True, services=True):
    source = (LAB / 'growth-b.tf').read_text()
    command = attribute(source, 'growth_b_connect_bundle_command')
    document = attribute(block(source, 'resource', 'aws_ssm_document', 'growth_b_connect_bundle'), 'content')
    # This fake installer deliberately fails if the host marker was not seen.
    installer = 'test -f "$HOST_READY"\nprintf "%s\\n" installed >> "$HOST_EVENTS"'
    config = ('variable "global_b_services" { default = ' + json.dumps(selected) + ' }\n'
              'locals {\nservices_enabled = ' + json.dumps(services) + '\n'
              'growth_b_connect_bundle = ' + json.dumps(installer) + '\n'
              'growth_b_connect_bundle_command = ' + command + '\n'
              'document = ' + document + '\n}\n')
    with tempfile.TemporaryDirectory(prefix='airbob-connect-command-') as directory:
        (Path(directory) / 'main.tf').write_text(config)
        result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
            input='jsonencode(jsondecode(local.document).mainSteps[0].inputs)\n',
            env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'TF_IN_AUTOMATION': '1',
                 'AWS_EC2_METADATA_DISABLED': 'true'}, text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stderr)
        inputs = json.loads(json.loads(result.stdout))
        if len(inputs['runCommand']) != 1:
            raise AssertionError('Expected one ordered SSM command')
        return inputs['runCommand'][0]


class ConnectHostReadiness(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.command = connect_command()

    def run_host(self, ready_after):
        with tempfile.TemporaryDirectory(prefix='airbob-connect-host-') as directory:
            root = Path(directory)
            marker, events = root / 'ready', root / 'events'
            if ready_after == 0:
                marker.touch()
            # Advance a simulated cloud-init completion only on a wait. No
            # package installation, real sleeping, or privileged paths are used.
            setup = '''waits=0
sleep() {
  waits=$((waits + 1))
  printf '%s\\n' waiting >> "$HOST_EVENTS"
  if [[ "$waits" == "$HOST_READY_AFTER" ]]; then touch "$HOST_READY"; fi
}
'''
            command = setup + self.command.replace('/var/lib/airbob/b-host-ready', '"$HOST_READY"')
            result = subprocess.run(['bash', '-c', command], capture_output=True, text=True, timeout=10,
                env={**os.environ, 'HOST_READY': str(marker), 'HOST_EVENTS': str(events),
                     'HOST_READY_AFTER': str(ready_after)})
            return result, events.read_text().splitlines() if events.exists() else []

    def test_already_initialized_host_installs_without_wait(self):
        result, events = self.run_host(0)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['installed'], events)

    def test_new_host_installs_only_after_cloud_init_finishes(self):
        result, events = self.run_host(2)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['waiting', 'waiting', 'installed'], events)

    def test_failed_cloud_init_never_enters_bundle_installer(self):
        result, events = self.run_host(-1)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(['waiting'] * 120, events)

    def test_disabled_service_has_no_install_command(self):
        self.assertEqual('', connect_command(selected=False))
        self.assertEqual('', connect_command(services=False))

    def test_mac_database_access_survives_service_transition(self):
        rule = block((LAB / 'security.tf').read_text(), 'resource', 'aws_vpc_security_group_ingress_rule', 'mac_import_rds')
        self.assertNotIn('cidr_ipv4', rule)
        self.assertNotIn('cidr_ipv6', rule)
        for prepared, importing, services, mac_source, expected in (
            (True, True, False, False, 1), (False, False, True, True, 1),
            (False, False, True, False, 0), (False, False, False, False, 0),
        ):
            variables = {'global_b_prepare_only': prepared, 'global_b_import_from_mac': importing, 'global_b_services': services}
            config = ''.join('variable "' + k + '" { default = ' + json.dumps(v) + ' }\n' for k, v in variables.items())
            config += 'locals {\nservices_enabled = true\ngrowth_b_service_mac_source = ' + json.dumps(mac_source) + '\n'
            config += 'groups = {rds="sg-rds",nat="sg-nat"}\n'
            for key in ('count', 'security_group_id', 'referenced_security_group_id', 'ip_protocol', 'from_port', 'to_port'):
                config += key + ' = ' + attribute(rule, key).replace('module.security.security_group_ids', 'local.groups') + '\n'
            config += '}\n'
            with tempfile.TemporaryDirectory(prefix='airbob-mac-access-') as directory:
                (Path(directory) / 'main.tf').write_text(config)
                result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
                    input='jsonencode([local.count,local.security_group_id,local.referenced_security_group_id,local.ip_protocol,local.from_port,local.to_port])\n',
                    text=True, capture_output=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual([expected, 'sg-rds', 'sg-nat', 'tcp', 3306, 3306], json.loads(json.loads(result.stdout)))


if __name__ == '__main__':
    unittest.main()
