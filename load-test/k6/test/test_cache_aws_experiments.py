"""Offline tests: AWS clients/transports are fakes; no credentials or cloud resources are used."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zlib
import base64
import shlex
import subprocess

HERE = Path(__file__).resolve().parents[1] / 'cache'
sys.path.insert(0, str(HERE))
import aws_cache_contract as contract
import aws_cache_host as host

spec = importlib.util.spec_from_file_location('aws_runner', HERE / 'run-aws-experiments.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


def config():
    return json.loads((HERE / 'aws-experiment.example.json').read_text())


def row(rate, passed=True, **changes):
    return {'configuredRate': rate, 'sloPassed': passed, 'evidenceReasons': [],
            'dropped': 0, 'generatorHealthy': True, **changes}


class PlanTest(unittest.TestCase):
    def test_prepare_is_offline_and_bundle_contains_exact_shared_sources(self):
        with tempfile.TemporaryDirectory() as tmp, \
             patch('subprocess.run', side_effect=AssertionError('No external programs during prepare')), \
             patch('socket.getaddrinfo', side_effect=AssertionError('No DNS during prepare')), \
             patch.object(runner.Aws, 'call', side_effect=AssertionError('No AWS during prepare')):
            directory = runner.prepare(config(), Path(tmp) / 'prepared')
            plan = json.loads((directory / 'plan.json').read_text())
            self.assertEqual(plan['awsCallsPerformed'], 0)
            self.assertFalse(plan['provisionsInfrastructure'])
            files = json.loads(zlib.decompress((directory / 'host-bundle.zlib').read_bytes()))
            self.assertEqual(set(files), set(runner.SOURCES))
            for path, content in files.items():
                self.assertEqual(content, (contract.ROOT / path).read_text())
            self.assertNotIn('BENCHMARK_READ_MODEL_TOKEN', (directory / 'config.json').read_text())

    def test_prepare_does_not_overwrite_an_existing_plan(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(FileExistsError):
                runner.prepare(config(), tmp)

    def test_example_cannot_execute_or_access_aws(self):
        aws = Mock()
        with tempfile.TemporaryDirectory() as tmp:
            run = runner.Runner(config(), tmp, Path(tmp) / 'result', aws)
            with self.assertRaisesRegex(RuntimeError, 'example'):
                run.execute()
        aws.call.assert_not_called()

    def test_invalid_target_secret_and_image_contracts_are_rejected(self):
        for change in [ {'appImage': 'airbob:latest'}, {'tokenParameter': '/production/token'},
                        {'apps': ['i-33333333333333333']}, {'scenarios': ['lua']},
                        {'durationSeconds': 600}, {'password': 'must-not-be-rendered'},
                        {'clientVUs': 100000}, {'asgName': 'production'} ]:
            with self.subTest(change=change), self.assertRaises((RuntimeError, TypeError)):
                contract.validate(config() | change)

    def test_real_configuration_can_be_validated_without_cloud(self):
        self.assertFalse(contract.validate(config() | {'example': False}, executing=True)['example'])

    def test_actual_install_payload_reconstructs_the_bundle_in_a_temporary_host(self):
        with tempfile.TemporaryDirectory() as tmp:
            prepared = runner.prepare(config(), Path(tmp) / 'prepared')
            transport = runner.Transport(Mock(), config(), 4102444800)
            transport.root = str(Path(tmp) / 'host')
            def local_shell(instance, body, *args, **kwargs):
                parts = shlex.split(body)
                if body.startswith('umask'):
                    Path(transport.root).mkdir(); (Path(transport.root) / 'bundle.b64').write_text(''); return ''
                if parts[0] == 'printf':
                    with (Path(transport.root) / 'bundle.b64').open('a') as stream: stream.write(parts[2])
                    return ''
                self.assertEqual(parts[:2], ['python3', '-c'])
                result = subprocess.run([sys.executable, '-c', parts[2]], capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stderr)
                return result.stdout
            transport.shell = local_shell
            transport.install(config()['apps'][0], prepared)
            self.assertTrue((Path(transport.root) / 'bundle.ready').is_file())
            for path in runner.SOURCES:
                self.assertEqual((Path(transport.root) / path).read_bytes(), (contract.ROOT / path).read_bytes())
            self.assertEqual(json.loads((Path(transport.root) / 'control.json').read_text())['deadlineEpoch'], 4102444800)


class CapacityTest(unittest.TestCase):
    def test_search_brackets_and_repeats_both_sides_three_times(self):
        c = config()
        c['capacity']['ceilingRps'] = 2000
        calls = []
        def measure(rate, seconds, phase, repeat):
            calls.append((rate, seconds, phase, repeat))
            return row(rate, rate <= 1000)
        bound = contract.capacity_search(c, measure)
        self.assertEqual(bound['status'], 'confirmed-boundary')
        self.assertEqual(bound['highestConfirmedRps'], 1000)
        self.assertGreater(bound['firstFailingRps'], 1000)
        self.assertLessEqual(bound['firstFailingRps'] - 1000, 50)
        confirmations = [call for call in calls if call[2] == 'confirm-boundary']
        self.assertEqual(len(confirmations), 6)
        self.assertTrue(all(call[1] == 300 for call in confirmations))
        self.assertEqual([call[3] for call in confirmations], [1, 1, 2, 2, 3, 3])

    def test_ceiling_is_confirmed_lower_bound_not_maximum(self):
        bound = contract.capacity_search(config(), lambda rate, *args: row(rate))
        self.assertEqual(bound['status'], 'lower-bound-only')
        self.assertEqual(bound['highestConfirmedRps'], 20000)
        self.assertIsNone(bound['firstFailingRps'])

    def test_drops_or_generator_pressure_do_not_establish_server_limit(self):
        for change in ({'dropped': 1}, {'generatorHealthy': False}, {'evidenceReasons': ['bad']}):
            with self.subTest(change=change):
                bound = contract.capacity_search(config(), lambda rate, *args: row(rate, **change))
                self.assertEqual(bound['status'], 'inconclusive-generator-or-evidence')
                self.assertIsNone(bound['highestConfirmedRps'])

    def test_unstable_confirmation_is_not_published_as_a_maximum(self):
        def measure(rate, seconds, phase, repeat):
            return row(rate, rate <= 800 if phase != 'confirm-boundary' else True)
        bound = contract.capacity_search(config(), measure)
        self.assertEqual(bound['status'], 'unstable-or-inconclusive')
        self.assertIsNone(bound['highestConfirmedRps'])

    def test_no_passing_rate_does_not_fabricate_zero_capacity(self):
        bound = contract.capacity_search(config(), lambda rate, *args: row(rate, False))
        self.assertEqual(bound['status'], 'no-passing-rate')
        self.assertIsNone(bound['highestConfirmedRps'])


class HostPolicyTest(unittest.TestCase):
    def original(self):
        return {'SPRING_PROFILES_ACTIVE': 'aws', 'SPRING_PROFILES_INCLUDE': 'read-model-benchmark',
                'SPRING_DATASOURCE_PASSWORD': 'private-fixture-password',
                'SPRING_DATASOURCE_URL': 'jdbc:mysql://test:3306/airbobdb?sslMode=VERIFY_IDENTITY',
                'REDIS_HOST': 'redis-general.lab.airbob.internal', 'REDIS_PORT': '6379',
                'ACCOMMODATION_DETAIL_CACHE_REDIS_HOST': 'redis-cache.lab.airbob.internal',
                'ACCOMMODATION_DETAIL_CACHE_REDIS_PORT': '6380', 'SPRING_KAFKA_LISTENER_AUTO_STARTUP': 'true',
                'JAVA_TOOL_OPTIONS': 'unwanted-agent', 'SPRING_APPLICATION_JSON': '{"unexpected":true}'}

    def test_outage_control_changes_only_coalescing_and_preserves_redis_and_jdbc(self):
        before = host.benchmark_env(self.original(), 'test-secret', 'coalescing-off')
        after = host.benchmark_env(self.original(), 'test-secret', 'coalescing-on')
        changed = {k for k in before if before[k] != after[k]}
        self.assertEqual(changed, {'CACHE_BENCHMARK_LOCAL_COALESCING_ENABLED'})
        self.assertEqual(before['ACCOMMODATION_DETAIL_CACHE_ENABLED'], 'true')
        self.assertEqual(before['SPRING_DATASOURCE_URL'], self.original()['SPRING_DATASOURCE_URL'])
        self.assertEqual(before['SPRING_DATASOURCE_PASSWORD'], self.original()['SPRING_DATASOURCE_PASSWORD'])
        self.assertEqual(before['SPRING_KAFKA_LISTENER_AUTO_STARTUP'], 'false')
        self.assertEqual(before['SPRING_PROFILES_ACTIVE'], 'aws,cache-benchmark')
        self.assertNotIn('SPRING_PROFILES_INCLUDE', before)
        self.assertNotIn('JAVA_TOOL_OPTIONS', before)
        self.assertNotIn('SPRING_APPLICATION_JSON', before)

    def test_shared_redis_endpoint_is_rejected(self):
        original = self.original() | {'ACCOMMODATION_DETAIL_CACHE_REDIS_HOST': 'redis-general.lab.airbob.internal'}
        with self.assertRaisesRegex(RuntimeError, 'separation'):
            host.benchmark_env(original, 'test', 'cache-on')

    def test_secret_is_resolved_on_host_and_requires_securestring(self):
        response = {'Parameter': {'Type': 'SecureString', 'Value': 'a' * 64}}
        with patch.object(host, 'command', return_value=json.dumps(response)) as call:
            self.assertEqual(host.token(config()), 'a' * 64)
            self.assertNotIn('a' * 64, repr(call.call_args))
        response['Parameter']['Type'] = 'String'
        with patch.object(host, 'command', return_value=json.dumps(response)), self.assertRaises(RuntimeError):
            host.token(config())

    def test_snapshot_omits_histograms_and_other_routes_but_keeps_loader_and_outcome_counts(self):
        raw = '\n'.join([
            'accommodation_detail_cache_request_total{result="loaded"} 2',
            'accommodation_detail_cache_load_duration_seconds_count{result="success"} 2',
            'accommodation_detail_cache_load_duration_seconds_bucket{le="1.0"} 100',
            'app_query_per_request_queries_count{path="/api/v1/accommodations/{accommodationId}"} 2',
            'app_query_per_request_queries_count{path="/api/v2/accommodations/{accommodationId}"} 100',
            'accommodation_detail_cache_redis_operation_total{operation="get",result="error"} 0',
            'accommodation_detail_cache_redis_operation_total{operation="set",result="success"} 2'])
        filtered = host.measurement_metrics(raw)
        self.assertIn('result="loaded"', filtered)
        self.assertIn('duration_seconds_count', filtered)
        self.assertNotIn('bucket', filtered)
        self.assertNotIn('/api/v2/', filtered)
        self.assertNotIn('operation="set"', filtered)

    def test_redis_recovery_never_unpauses_a_replacement_container(self):
        owned = host.Host.__new__(host.Host)
        owned.c = config(); owned.instance = config()['redisInstanceId']
        owned.state = {'kind': 'redis', 'paused': True, 'containerId': 'original-cache'}
        owned.container = Mock(return_value={'Id': 'replacement-cache', 'Config': {'Image': 'redis@sha256:fake'},
            'State': {'Paused': True}, 'HostConfig': {'PortBindings': {'6379/tcp': [{'HostPort': '6380'}]}}})
        with patch.object(host, 'env_file', return_value={'REDIS_IMAGE': 'redis@sha256:fake'}), patch.object(host, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'replaced'):
                owned.redis('unpause')
            command.assert_not_called()

    def test_generator_exiting_during_recovery_is_already_recovered(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config(); owned.instance = config()['loadGeneratorInstanceId']
            owned.root = Path(tmp)
            active_path = owned.root / 'active-k6.json'
            active_path.write_text(json.dumps({'pid': 123456789, 'startTicks': '100'}))
            read_text = Path.read_text
            def disappearing_process(path, *args, **kwargs):
                if path == Path('/proc/123456789/stat'):
                    raise FileNotFoundError('process completed')
                return read_text(path, *args, **kwargs)
            with patch.object(Path, 'read_text', disappearing_process), patch.object(host.os, 'killpg') as signal_group:
                self.assertTrue(owned.stop_generator()['recovered'])
                signal_group.assert_not_called()
            self.assertFalse(active_path.exists())

    def test_generator_recovery_does_not_signal_a_reused_pid(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config(); owned.instance = config()['loadGeneratorInstanceId']
            owned.root = Path(tmp)
            (owned.root / 'active-k6.json').write_text(json.dumps({'pid': 123456789, 'startTicks': '100'}))
            read_text = Path.read_text
            def reused_process(path, *args, **kwargs):
                if path == Path('/proc/123456789/stat'):
                    return ' '.join(['0'] * 21 + ['200'])
                return read_text(path, *args, **kwargs)
            with patch.object(Path, 'read_text', reused_process), patch.object(host.os, 'killpg') as signal_group:
                with self.assertRaisesRegex(RuntimeError, 'reused'):
                    owned.stop_generator()
                signal_group.assert_not_called()


class FakeAws:
    def __init__(self, c):
        self.c, self.calls, self.mutate = c, [], lambda item: item

    def call(self, service, action, *args):
        self.calls.append((service, action, args))
        c = self.c
        if action == 'get-caller-identity':
            return {'Account': contract.ACCOUNT, 'Arn': 'arn:aws:sts::' + contract.ACCOUNT + ':assumed-role/airbob-lab-operator/test'}
        if action == 'describe-instances':
            items = []
            for identifier, role in [(i, 'app') for i in c['apps']] + [(c['redisInstanceId'], 'redis'), (c['loadGeneratorInstanceId'], 'loadgen')]:
                tags = {'Project': 'airbob', 'Environment': 'performance-lab', 'RunId': c['runId'],
                        'Service': role, 'Persistence': 'ephemeral', 'ExpiresAt': '4102444800', 'FencingToken': '17'}
                item = {'InstanceId': identifier, 'InstanceType': 'c6i.large', 'ImageId': 'ami-fixture',
                        'State': {'Name': 'running'}, 'VpcId': 'vpc-fixture',
                        'Tags': [{'Key': k, 'Value': v} for k, v in tags.items()]}
                items.append(self.mutate(item))
            return {'Reservations': [{'Instances': items}]}
        if action == 'describe-auto-scaling-groups':
            return {'AutoScalingGroups': [{'Instances': [{'InstanceId': i} for i in c['apps']],
                'MinSize': len(c['apps']), 'MaxSize': len(c['apps']), 'DesiredCapacity': len(c['apps']), 'SuspendedProcesses': []}]}
        if action == 'describe-policies': return {'ScalingPolicies': []}
        if action == 'describe-load-balancers': return {'LoadBalancers': [{'DNSName': c['alb']['dnsName'], 'VpcId': 'vpc-fixture'}]}
        if action == 'describe-target-groups': return {'TargetGroups': [{'LoadBalancerArns': [c['alb']['arn']], 'Port': 8080}]}
        if action in ('suspend-processes', 'resume-processes', 'put-object'): return {}
        raise AssertionError('Unexpected AWS operation: ' + service + ' ' + action)


class AwsPreflightTest(unittest.TestCase):
    def test_only_reads_are_used_for_identity_preflight(self):
        aws = FakeAws(config())
        evidence = runner.preflight(aws, config())
        self.assertEqual(len(evidence['instances']), 4)
        self.assertTrue(all(action.startswith(('describe-', 'get-')) for _, action, _ in aws.calls))

    def test_stopped_or_wrong_run_instances_are_rejected_without_starting_them(self):
        for state in ('stopped', 'terminated'):
            aws = FakeAws(config())
            aws.mutate = lambda item: item | {'State': {'Name': state}}
            with self.assertRaisesRegex(RuntimeError, 'ownership'):
                runner.preflight(aws, config())
            self.assertNotIn('start-instances', [action for _, action, _ in aws.calls])

    def test_account_and_role_mismatch_stop_before_instance_access(self):
        aws = Mock()
        aws.call.return_value = {'Account': '000000000000', 'Arn': 'wrong'}
        with self.assertRaisesRegex(RuntimeError, 'lab-operator'):
            runner.preflight(aws, config())
        self.assertEqual(aws.call.call_count, 1)


class RecoveryTest(unittest.TestCase):
    def run_failure(self, recover_fails=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        c = config() | {'example': False}
        prepared = runner.prepare(c, Path(temporary.name) / 'prepared')
        output = Path(temporary.name) / 'result'
        aws = FakeAws(c)
        run = runner.Runner(c, prepared, output, aws)
        run.lease = Mock(return_value='fencing_token=19')
        actions = []
        transport = Mock()
        def action(instance, name, *args, **kwargs):
            actions.append((instance, name))
            if recover_fails and instance == c['redisInstanceId']: raise RuntimeError('fake recovery failure')
            return {'recovered': True}
        transport.action.side_effect = action
        run.transport = transport
        run.measurements = Mock(side_effect=RuntimeError('fake measurement failure'))
        with self.assertRaisesRegex(RuntimeError, 'fake measurement failure'):
            run.execute()
        return c, run, actions, aws, json.loads((output / 'comparison.json').read_text())

    def test_failure_recovers_redis_then_every_app_and_releases_lease(self):
        c, run, actions, aws, result = self.run_failure()
        self.assertEqual(actions[:2], [(c['loadGeneratorInstanceId'], 'install-k6'), (c['redisInstanceId'], 'recover')])
        self.assertEqual(actions[2], (c['loadGeneratorInstanceId'], 'recover'))
        self.assertCountEqual(actions[3:], [(i, 'recover') for i in c['apps']])
        self.assertEqual(result['state'], 'incomplete')
        self.assertIn(('release',), [call.args for call in run.lease.call_args_list])
        self.assertIn('resume-processes', [action for _, action, _ in aws.calls])

    def test_failed_recovery_is_visible_and_does_not_resume_replacement(self):
        c, run, actions, aws, result = self.run_failure(True)
        self.assertEqual(result['state'], 'recovery-required')
        self.assertIn(c['redisInstanceId'], result['recoveryErrors'])
        self.assertNotIn('resume-processes', [action for _, action, _ in aws.calls])

    def test_ssm_failure_cancels_remote_command(self):
        aws = Mock()
        aws.call.side_effect = [ {'Command': {'CommandId': 'fixture-command'}},
                                {'CommandInvocations': [{'Status': 'Failed'}]}, {} ]
        transport = runner.Transport(aws, config(), 4102444800)
        with self.assertRaisesRegex(RuntimeError, 'SSM host action failed'):
            transport.shell(config()['apps'][0], 'true')
        self.assertEqual(aws.call.call_args.args[:2], ('ssm', 'cancel-command'))

    def test_recover_never_runs_measurement_or_creates_infrastructure(self):
        c = config() | {'example': False}
        with tempfile.TemporaryDirectory() as tmp:
            runner.write(Path(tmp) / 'recovery.json', {'config': c, 'installed': c['apps'] + [c['redisInstanceId']],
                                                       'changedProcesses': ['ReplaceUnhealthy']})
            aws = FakeAws(c)
            with patch.object(runner.Runner, 'lease', return_value='fencing_token=25'), \
                 patch.object(runner.Transport, 'action', return_value={'recovered': True}) as action:
                runner.recover(tmp, aws)
            self.assertTrue(all(call.args[1] == 'recover' for call in action.call_args_list))
            self.assertEqual(json.loads((Path(tmp) / 'recovery-result.json').read_text())['state'], 'recovered')


if __name__ == '__main__':
    unittest.main()
