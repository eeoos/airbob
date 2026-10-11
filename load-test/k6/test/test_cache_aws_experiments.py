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
import hashlib
import http.server
import shutil
import threading

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
    def test_delivered_rate_and_volume_do_not_hide_real_drops_or_underload(self):
        c = config() | {'scenarios': ['capacity'], 'capacityCriterion': 'completed-load'}
        c['capacity']['rampSeconds'] = 60
        # Observed on AWS: every started request succeeded and no iterations
        # dropped, but the millisecond cohort contained 329998 of nominal 330000.
        sample = {'kind': 'rate', 'configuredRate': 1100, 'configuredSeconds': 300,
                  'completed': 329998, 'requestedSamples': 330000, 'successful': 329998,
                  'dropped': 0, 'errorRate': 0, 'responseMismatches': 0,
                  'latencyMs': {'p50': 5, 'p95': 966, 'p99': 1932}, 'successRps': 1099.978,
                  'generator': {'maxCpuPercent': 41, 'minAvailableMemoryPercent': 72},
                  'ramp': {'seconds': 60, 'startRate': 1, 'completed': 32994, 'errors': 0},
                  'totalCompleted': 362992, 'totalErrorRate': 0}
        for changes, passed in [({}, True), ({'dropped': 1}, False),
                ({'completed': 329980, 'successful': 329980, 'totalCompleted': 362974}, True),
                ({'completed': 326699, 'successful': 326699, 'totalCompleted': 359693}, False),
                ({'successRps': 1088.99}, False),
                ({'errorRate': 1 / 329998, 'totalErrorRate': 1 / 362992}, False),
                ({'ramp': sample['ramp'] | {'errors': 1}}, False),
                ({'generator': {'maxCpuPercent': 91, 'minAvailableMemoryPercent': 72}}, False)]:
            with self.subTest(changes=changes):
                candidate = copy.deepcopy(sample) | changes
                total = candidate['totalCompleted']
                observed = {'requests': total, 'selects': total * 3, 'loads': total, 'loadErrors': 0,
                            'redisGetErrors': 0, 'outcomes': {'loaded': total, 'hit': 0}}
                with patch.object(contract.local, 'server_observation', return_value={}), \
                     patch.object(contract.local, 'aggregate_observations', return_value=observed):
                    result = contract.evaluate(candidate, [''], [''], 'cache-off', 'capacity', c)
                self.assertEqual(result['capacityPassed'], passed)
                self.assertEqual(result['minimumCompletedForOfferedLoad'], 326700)
                self.assertAlmostEqual(result['offeredLoadRatio'], candidate['completed'] / 330000)
                self.assertEqual(result['nominalRequestShortfall'], 330000 - candidate['completed'])
                self.assertFalse(result['sloPassed'])

    def test_timed_executor_nominal_shortfall_is_reported_without_changing_actual_counts(self):
        # AWS 5000 RPS: no drops/errors, all server requests matched, but the
        # time-bounded executor completed 401 fewer than nominal 1.5 million.
        sample = {'completed': 1499599, 'requestedSamples': 1500000,
                  'configuredRate': 5000, 'successRps': 4998.230153386707,
                  'dropped': 0, 'errorRate': 0, 'evidenceReasons': [], 'latencyMs': {'p95': 127}}
        before = copy.deepcopy(sample)
        self.assertTrue(contract.local.qualifies(sample, float('inf'), 0))
        self.assertEqual(sample, before)
        self.assertEqual(contract.local.offered_load_observation(sample)['nominalRequestShortfall'], 401)
        self.assertFalse(contract.local.qualifies(sample | {'dropped': 1}, float('inf'), 0))
        self.assertFalse(contract.local.qualifies(sample | {'completed': 1484999}, float('inf'), 0))
        self.assertFalse(contract.local.qualifies(sample | {'successRps': 4949.99}, float('inf'), 0))

    def test_ramp_counts_match_all_server_requests_without_inflating_measured_capacity(self):
        c = config() | {'scenarios': ['capacity'], 'capacityCriterion': 'completed-load'}
        c['capacity']['rampSeconds'] = 30
        sample = {'kind': 'rate', 'completed': 100, 'requestedSamples': 100, 'successful': 100,
                  'dropped': 0, 'errorRate': 0, 'responseMismatches': 0,
                  'latencyMs': {'p50': 10, 'p95': 10, 'p99': 10}, 'successRps': 100, 'configuredRate': 100,
                  'generator': {'maxCpuPercent': 20, 'minAvailableMemoryPercent': 80},
                  'ramp': {'seconds': 30, 'startRate': 1, 'completed': 50, 'errors': 0},
                  'totalCompleted': 150, 'totalErrorRate': 0}
        observed = {'requests': 150, 'selects': 450, 'loads': 150, 'loadErrors': 0,
                    'redisGetErrors': 0, 'outcomes': {'loaded': 150, 'hit': 0}}
        with patch.object(contract.local, 'server_observation', return_value={}), \
             patch.object(contract.local, 'aggregate_observations', return_value=observed):
            result = contract.evaluate(copy.deepcopy(sample), [''], [''], 'cache-off', 'capacity', c)
            self.assertTrue(result['capacityPassed'])
            with patch.object(contract.local, 'aggregate_observations', return_value=observed | {
                    'requests': 145, 'selects': 435, 'loads': 145, 'outcomes': {'loaded': 145, 'hit': 0}}):
                result = contract.evaluate(copy.deepcopy(sample) | {
                    'completed': 95, 'successful': 95, 'totalCompleted': 145, 'successRps': 95},
                    [''], [''], 'cache-off', 'capacity', c)
                self.assertEqual(result['evidenceReasons'], [])
                self.assertFalse(result['capacityPassed'])
            for change in [{'completed': 99, 'totalCompleted': 149}, {'dropped': 1},
                           {'totalCompleted': 151}, {'ramp': {}}, {'ramp': sample['ramp'] | {'errors': 1}}]:
                with self.subTest(change=change):
                    result = contract.evaluate(copy.deepcopy(sample) | change, [''], [''], 'cache-off', 'capacity', c)
                    self.assertFalse(result['capacityPassed'])

    def test_capacity_ramp_duration_is_bounded(self):
        for value in [0, 30, 60]:
            c = config(); c['capacity']['rampSeconds'] = value; contract.validate(c)
        for value in [True, -1, 61, 1.5, '30']:
            c = config(); c['capacity']['rampSeconds'] = value
            with self.subTest(value=value), self.assertRaisesRegex(RuntimeError, 'rampSeconds'):
                contract.validate(c)

    def test_remote_measurement_timeout_includes_the_ramp(self):
        c = config(); run = runner.Runner(c, '/unused/prepared', '/unused/output', Mock())
        run.transport = Mock()
        args = {'action': 'measure', 'seconds': 300, 'rampSeconds': 60}
        run.generator(args)
        run.transport.action.assert_called_once_with(c['loadGeneratorInstanceId'], 'generator', args, timeout=450)

    def test_fixed_probe_and_pool_controls_are_bounded(self):
        c = config() | {'scenarios': ['capacity'], 'capacityProbeRates': [800, 1600],
                        'hikariMaximumPoolSize': 20}
        contract.validate(c)
        for change in ({'capacityProbeRates': []}, {'capacityProbeRates': [800, 800]},
                       {'capacityProbeRates': [20001]}, {'capacityProbeRates': [True]},
                       {'scenarios': ['latency']}, {'hikariMaximumPoolSize': 9},
                       {'hikariMaximumPoolSize': 41}, {'hikariMaximumPoolSize': '20'}):
            with self.subTest(change=change), self.assertRaises(RuntimeError):
                contract.validate(c | change)

    def test_fixed_probes_repeat_both_outcomes_without_claiming_maximum(self):
        c = config() | {'scenarios': ['capacity'], 'capacityProbeRates': [800, 1600],
                        'capacityVariants': ['cache-off'], 'rounds': 2}
        c['capacity']['warmupRps'] = 800
        aws = Mock()
        run = runner.Runner(c, '/unused/prepared', '/unused/output', aws)
        run.variant = Mock(); run.warm = Mock(); run.save = Mock()
        run.measure = Mock(side_effect=lambda label, scenario, variant, rate, seconds:
            row(rate, rate == 800, dropped=100 if rate == 1600 else 0))
        with patch.object(runner, 'capacity_search', side_effect=AssertionError('Fixed probes must not search')):
            run.measurements()
        self.assertEqual([800, 1600, 1600, 800], [c.kwargs['rate'] for c in run.measure.call_args_list])
        self.assertTrue(all(c.args[1] == 800 for c in run.warm.call_args_list))
        self.assertEqual(run.capacities[0]['status'], 'fixed-rate-observations')
        self.assertEqual(run.capacities[0]['observations'], 4)
        self.assertIsNone(run.capacities[0]['highestConfirmedRps'])
        aws.call.assert_not_called()

    def test_completed_load_search_keeps_going_after_latency_slo_failure(self):
        c = config() | {'capacityCriterion': 'completed-load'}
        c['capacity']['ceilingRps'] = 2000
        contract.validate(c)
        bound = contract.capacity_search(c, lambda rate, *args: row(
            rate, False, capacityPassed=rate <= 1000))
        self.assertEqual(bound['status'], 'confirmed-boundary')
        self.assertEqual(bound['highestConfirmedRps'], 1000)
        self.assertEqual(bound['criterion'], 'completed-load')

    def test_completed_load_retains_latency_result_and_every_other_requirement(self):
        sample = {'completed': 100, 'requestedSamples': 100, 'dropped': 0, 'errorRate': 0,
                  'latencyMs': {'p95': 300}, 'successRps': 100, 'configuredRate': 100,
                  'generator': {'maxCpuPercent': 20, 'minAvailableMemoryPercent': 80}}
        with patch.object(contract.local, 'server_observation', return_value={}), \
             patch.object(contract.local, 'aggregate_observations', return_value={}), \
             patch.object(contract.local, 'evidence_reasons', return_value=[]):
            strict = contract.evaluate(copy.deepcopy(sample), [''], [''], 'cache-off', 'capacity', config())
            self.assertFalse(strict['capacityPassed'])
            c = config() | {'capacityCriterion': 'completed-load'}
            actual = contract.evaluate(copy.deepcopy(sample), [''], [''], 'cache-off', 'capacity', c)
            self.assertTrue(actual['capacityPassed'])
            self.assertFalse(actual['sloPassed'])
            for change in [{'errorRate': .01}, {'dropped': 1}, {'successRps': 98}, {'completed': 98}]:
                with self.subTest(change=change):
                    actual = contract.evaluate(copy.deepcopy(sample) | change, [''], [''], 'cache-off', 'capacity', c)
                    self.assertFalse(actual['capacityPassed'])
        with self.assertRaises(RuntimeError):
            contract.validate(config() | {'capacityCriterion': 'ignore-errors'})

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
    def test_pool_diagnostic_changes_only_the_temporary_environment(self):
        original = self.original() | {'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE': '10'}
        saved = copy.deepcopy(original)
        default = host.benchmark_env(original, 'test-secret', 'cache-off')
        changed = host.benchmark_env(original, 'test-secret', 'cache-off', hikari_maximum_pool_size=20)
        self.assertEqual(original, saved)
        self.assertEqual(default['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'], '10')
        self.assertEqual({k for k in changed if changed[k] != default[k]},
                         {'SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'})
        self.assertEqual(changed['SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE'], '20')
        with self.assertRaises(RuntimeError):
            host.benchmark_env(original, 'test-secret', 'cache-off', hikari_maximum_pool_size=1000)

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

    def test_original_image_selection_keeps_the_isolated_v28_read_profile(self):
        c = config()
        c['originalApplication'] = {'appImage': c['appImage'], 'appCommit': c['appCommit']}
        contract.validate(c)
        value = host.benchmark_env(self.original(), 'test', 'cache-off')
        self.assertEqual(value['SPRING_FLYWAY_TARGET'], '28')
        self.assertEqual(value['SPRING_PROFILES_ACTIVE'], 'aws,cache-benchmark')
        self.assertEqual(value['SPRING_DATASOURCE_HIKARI_READ_ONLY'], 'true')
        self.assertEqual(value['RESERVATION_INVENTORY_STARTUP_ENABLED'], 'false')
        for original in [c['originalApplication'] | {'appImage': 'airbob:latest'},
                         c['originalApplication'] | {'appCommit': 'main'}]:
            with self.subTest(original=original), self.assertRaises(RuntimeError):
                contract.validate(c | {'originalApplication': original})

    def test_image_pull_failure_can_recover_the_recorded_original_image(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config(); owned.instance = owned.c['apps'][0]
            owned.root = Path(tmp); owned.state = {}
            owned.save = Mock(); owned.watchdog = Mock(); owned.cancel_watchdog = Mock()
            owned.app_compose = Mock()
            owned.benchmark_image = Mock(side_effect=RuntimeError('pull failed'))
            owned.container = Mock(return_value={'Image': 'original-id', 'Config': {
                'Image': owned.c['appImage'], 'Env': [],
                'Labels': {'com.docker.compose.project': 'existing-project'}}})
            def env_file(path):
                return {'APP_IMAGE': owned.c['appImage']} if path.name == 'images.env' else self.original()
            with patch.object(host, 'env_file', side_effect=env_file), \
                 patch.object(Path, 'read_bytes', return_value=b'original fixture bytes'), \
                 patch.object(host, 'command', return_value=json.dumps([{'Config': {'Labels': {
                     'org.opencontainers.image.revision': owned.c['appCommit']}}}])):
                with self.assertRaisesRegex(RuntimeError, 'pull failed'):
                    owned.variant('cache-off')
                owned.watchdog.assert_called_once_with(owned.c['deadlineSeconds'])
                self.assertEqual(owned.state['originalImageId'], 'original-id')
                owned.recover()
            owned.app_compose.assert_called_once_with(Path('/etc/airbob/app.env'), restoring=True)
            self.assertTrue(owned.state['restored'])

    def test_initializing_original_is_explicit_and_requires_exact_original_identity(self):
        c = config() | {'allowInitializingOriginal': True}
        with self.assertRaisesRegex(RuntimeError, 'explicit original'):
            contract.validate(c)
        c['originalApplication'] = {'appImage': c['appImage'], 'appCommit': c['appCommit']}
        contract.validate(c)
        for invalid in ('true', 1, None):
            with self.subTest(invalid=invalid), self.assertRaises(RuntimeError):
                contract.validate(c | {'allowInitializingOriginal': invalid})

    def test_initializing_handoff_rejects_a_changed_container_or_runtime_before_mutating(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config() | {'allowInitializingOriginal': True}
            owned.c['originalApplication'] = {'appImage': owned.c['appImage'], 'appCommit': owned.c['appCommit']}
            owned.root = Path(tmp); owned.instance = owned.c['apps'][0]
            owned.save = Mock(); owned.watchdog = Mock()
            owned.benchmark_image = Mock(side_effect=RuntimeError('pull failed'))
            owned.container = Mock(return_value={'Id': 'original-container', 'Image': 'original-id',
                'State': {'Running': True, 'Health': {'Status': 'starting'}}, 'Config': {
                'Image': owned.c['appImage'], 'Env': ['SPRING_PROFILES_ACTIVE=aws'],
                'Labels': {'com.docker.compose.project': 'existing'}}})
            expected = hashlib.sha256(b'original').hexdigest()
            marker = {'kind': 'cache-benchmark-handoff', 'runId': owned.c['runId'],
                      'containerId': 'original-container', 'imageId': 'original-id',
                      'originalEnvSha256': expected, 'originalImagesSha256': expected}
            def environment(path):
                return {'APP_IMAGE': owned.c['appImage']} if path.name == 'images.env' else self.original()
            for change in ({'containerId': 'replacement'}, {'runId': 'lab-other'}, {'originalEnvSha256': '0'*64}, {}):
                owned.state = {}; owned.benchmark_image.reset_mock(); owned.watchdog.reset_mock()
                with self.subTest(change=change), patch.object(host, 'env_file', side_effect=environment), \
                     patch.object(Path, 'read_bytes', return_value=b'original'), \
                     patch.object(Path, 'read_text', return_value=json.dumps(marker | change)), \
                     patch.object(host, 'command', return_value=json.dumps([{'Config': {'Labels': {
                         'org.opencontainers.image.revision': owned.c['appCommit']}}}])):
                    with self.assertRaisesRegex(RuntimeError, 'identity differs' if change else 'pull failed'):
                        owned.variant('cache-off')
                if change:
                    owned.benchmark_image.assert_not_called(); owned.watchdog.assert_not_called()
                else:
                    self.assertEqual(owned.state['originalReadiness'], 'initializing')
                    owned.watchdog.assert_called_once()

    def test_only_restoring_a_verified_initializing_original_skips_readiness_wait(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.root = Path(tmp)
            for readiness, restoring, should_wait in [('initializing', True, False),
                    ('initializing', False, True), ('ready', True, True)]:
                owned.state = {'composeProject': 'existing', 'originalReadiness': readiness}
                with self.subTest(readiness=readiness, restoring=restoring), \
                     patch.object(host, 'env_file', return_value={'APP_IMAGE': 'digest-image'}), \
                     patch.object(host, 'command') as command:
                    owned.app_compose(Path(tmp) / 'app.env', restoring=restoring)
                    argv = command.call_args.args[0]
                    self.assertEqual('--wait' in argv, should_wait)
                    self.assertIn('--force-recreate', argv)
                    self.assertEqual(argv[-1], 'app')
                    self.assertEqual(str(owned.root / 'benchmark-compose.json') in argv, not restoring)

    @unittest.skipUnless(shutil.which('wget'), 'wget is required for the real health probe')
    def test_benchmark_health_probe_uses_get_against_read_only_endpoint(self):
        methods = []
        class ReadOnlyHealth(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                methods.append('GET'); self.send_response(200); self.end_headers()
                self.wfile.write(b'{"status":"UP"}')
            def do_HEAD(self):
                methods.append('HEAD'); self.send_response(404); self.end_headers()
            def log_message(self, *args): pass

        with tempfile.TemporaryDirectory() as tmp, http.server.ThreadingHTTPServer(('127.0.0.1', 0), ReadOnlyHealth) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                owned = host.Host.__new__(host.Host); owned.root = Path(tmp)
                owned.state = {'composeProject': 'existing', 'originalReadiness': 'ready'}
                with patch.object(host, 'env_file', return_value={'APP_IMAGE': 'digest-image'}), patch.object(host, 'command'):
                    owned.app_compose(owned.root / 'app.env')
                overlay = json.loads((owned.root / 'benchmark-compose.json').read_text())
                probe = overlay['services']['app']['healthcheck']['test']
                result = subprocess.run(probe[1:-1] + [f'http://127.0.0.1:{server.server_port}/actuator/health'],
                                        capture_output=True, timeout=5)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(['GET'], methods)
            finally:
                server.shutdown(); thread.join(timeout=5)

    def test_initializing_recovery_refreshes_handoff_but_does_not_claim_readiness(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config() | {'allowInitializingOriginal': True}
            owned.root = Path(tmp); owned.instance = owned.c['apps'][0]
            owned.save = Mock(); owned.cancel_watchdog = Mock(); owned.app_compose = Mock()
            expected = hashlib.sha256(b'original').hexdigest()
            owned.state = {'kind': 'app', 'restored': False, 'originalImageId': 'original-id',
                           'originalEnvSha256': expected, 'originalImagesSha256': expected,
                           'originalReadiness': 'initializing'}
            owned.container = Mock(return_value={'Id': 'restored-container', 'Image': 'original-id',
                                                'State': {'Running': True}})
            with patch.object(Path, 'read_bytes', return_value=b'original'), patch.object(host, 'write') as write:
                result = owned.recover()
            self.assertTrue(result['recovered'])
            self.assertFalse(result['readinessVerified'])
            self.assertEqual(result['originalReadiness'], 'initializing')
            self.assertEqual(write.call_args.args[1]['containerId'], 'restored-container')
            self.assertEqual(write.call_args.args[1]['originalEnvSha256'], expected)
            owned.state['restored'] = False
            owned.container.return_value['State']['Running'] = False
            with patch.object(Path, 'read_bytes', return_value=b'original'), self.assertRaisesRegex(RuntimeError, 'did not start'):
                owned.recover()

    def test_recovery_uses_original_image_and_refuses_a_changed_image_environment(self):
        with tempfile.TemporaryDirectory() as tmp:
            owned = host.Host.__new__(host.Host)
            owned.c = config(); owned.instance = owned.c['apps'][0]; owned.root = Path(tmp)
            owned.save = Mock(); owned.cancel_watchdog = Mock(); owned.app_compose = Mock()
            owned.container = Mock(return_value={'Image': 'original-id'})
            owned.state = {'kind': 'app', 'restored': False, 'imageId': 'benchmark-id',
                           'originalImageId': 'original-id',
                           'originalEnvSha256': hashlib.sha256(b'original').hexdigest(),
                           'originalImagesSha256': hashlib.sha256(b'original').hexdigest()}
            with patch.object(Path, 'read_bytes', side_effect=[b'original', b'changed']):
                with self.assertRaisesRegex(RuntimeError, 'image environment changed'):
                    owned.recover()
            owned.app_compose.assert_not_called()
            with patch.object(Path, 'read_bytes', return_value=b'original'):
                self.assertTrue(owned.recover()['recovered'])
            owned.app_compose.assert_called_once_with(Path('/etc/airbob/app.env'), restoring=True)

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
