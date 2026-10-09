"""AWS preparation is filesystem-only; all service calls are forbidden in these tests."""
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

COUPON = Path(__file__).resolve().parents[1] / 'coupon'
sys.path.insert(0, str(COUPON))
import prepare_aws
import run_experiments


class Preparation(unittest.TestCase):
    def test_bundle_does_not_use_subprocess_network_or_private_files(self):
        c = json.loads((COUPON / 'experiment.example.json').read_text())
        with tempfile.TemporaryDirectory() as root:
            output = Path(root) / 'prepared'
            with patch.object(subprocess, 'run', side_effect=AssertionError('no commands during preparation')), \
                    patch.object(socket, 'socket', side_effect=AssertionError('no network during preparation')):
                result = prepare_aws.prepare(c, output)
            self.assertEqual(result['awsCallsPerformed'], 0)
            self.assertEqual(result['networkCallsPerformed'], 0)
            self.assertFalse(result['readyToExecute'])
            self.assertEqual(result['requiredUniqueSessions'], 180001)
            self.assertEqual(result['maximumMeasuredRuns'], 24)
            self.assertEqual(result['fixedWorkloadSecondsUpperBound'], 5280)
            with tarfile.open(output / 'coupon-tools.tar.gz') as archive:
                self.assertEqual(set(archive.getnames()), set(prepare_aws.SOURCES))
                self.assertTrue(all('..' not in Path(name).parts and not name.startswith('/') for name in archive.getnames()))
            for line in (output / 'SHA256SUMS').read_text().splitlines():
                sha, name = line.split('  ')
                self.assertEqual(hashlib.sha256((output / name).read_bytes()).hexdigest(), sha)
            policy = json.loads((output / 'cloudwatch-read-policy.json').read_text())
            self.assertEqual(policy['Statement'][0]['Action'], ['cloudwatch:GetMetricStatistics'])
            with self.assertRaises(FileExistsError):
                prepare_aws.prepare(c, output)

    def test_account_manifest_binds_existing_emails_to_exact_v28_source_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, emails, output = root / 'source.json', root / 'emails.json', root / 'accounts.json'
            source.write_text('{"mysql":{"flywayVersion":28},"datasetId":"global-b-test"}\n')
            emails.write_text('["coupon-a@airbob.cloud", "coupon-b@airbob.cloud"]')
            with patch.object(subprocess, 'run', side_effect=AssertionError('no commands')), \
                    patch.object(socket, 'socket', side_effect=AssertionError('no network')):
                result = prepare_aws.accounts('global-b-test', source, emails, output)
            self.assertEqual(result['createdAccounts'], 0)
            manifest = json.loads(output.read_text())
            self.assertEqual(manifest['sourceDataset']['manifestSha256'], hashlib.sha256(source.read_bytes()).hexdigest())
            self.assertEqual(manifest['accountPool']['capacity'], 2)
            with self.assertRaises(FileExistsError):
                prepare_aws.accounts('global-b-test', source, emails, output)

    def test_rejects_duplicate_accounts_and_v27_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, emails = root / 'source.json', root / 'emails.json'
            source.write_text('{"mysql":{"flywayVersion":28}}')
            emails.write_text('["same@airbob.cloud", "same@airbob.cloud"]')
            with self.assertRaises(ValueError):
                prepare_aws.accounts('global-b-test', source, emails, root / 'out.json')
            emails.write_text('["same@airbob.cloud"]')
            source.write_text('{"mysql":{"flywayVersion":27}}')
            with self.assertRaisesRegex(ValueError, 'V28'):
                prepare_aws.accounts('global-b-test', source, emails, root / 'out.json')

    def test_local_input_check_does_not_login_or_call_aws(self):
        c = json.loads((COUPON / 'experiment.example.json').read_text())
        c.update(capacityRates=[1], comparisonRates=[1], durationSeconds=120)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, emails, manifest = root / 'source.json', root / 'emails.json', root / 'accounts.json'
            source.write_text('{"mysql":{"flywayVersion":28}}')
            emails.write_text(json.dumps([f'member-{i}@airbob.cloud' for i in range(121)]))
            prepare_aws.accounts('global-b-test', source, emails, manifest)
            c['benchmarkDatasetManifest'] = str(manifest)
            for field in ('adminSessionFile', 'benchmarkTokenFile', 'accountPasswordFile'):
                path = root / field
                path.write_text('private-placeholder-value')
                path.chmod(0o600)
                c[field] = str(path)
            original_run = subprocess.run
            calls = []
            def local_node_only(command, **kwargs):
                calls.append(command)
                self.assertEqual(command[0], 'node')
                self.assertEqual(command[1], '-e')
                return original_run(command, **kwargs)
            with patch.object(subprocess, 'run', side_effect=local_node_only), \
                    patch.object(run_experiments.HTTP, 'open', side_effect=AssertionError('no HTTP')), \
                    patch.object(socket, 'socket', side_effect=AssertionError('no network')):
                result = run_experiments.check_inputs(c)
            self.assertEqual(len(calls), 1)
            self.assertEqual(result['awsCallsPerformed'], 0)
            self.assertFalse(result['runtimeAuthenticationVerified'])
            self.assertNotIn('private-placeholder-value', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
