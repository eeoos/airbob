"""Evaluate actual HCL in a provider-free directory. Never init/plan/apply or contact AWS."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_global_b_infrastructure import ROOT, LAB, attribute, block


def evaluate(variables, data_ready=True):
    source = (LAB / 'cache-benchmark.tf').read_text()
    condition = attribute(block(source, 'check', 'cache_benchmark_topology'), 'condition', indent=4)
    locals_source = (LAB / 'locals.tf').read_text()
    capacity = attribute(locals_source, 'app_capacity')
    count = attribute(locals_source, 'performance_app_count')
    code = ''.join('variable "' + key + '" { default = ' + json.dumps(value) + ' }\n'
                   for key, value in variables.items())
    code += ('locals {\n power_phase = var.lab_power == null ? "inactive" : var.lab_power.phase\n data_ready = ' + str(data_ready).lower() + '\n performance_app_count = ' + count
             + '\n valid = ' + condition + '\n capacity = ' + capacity + '\n}\n')
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / 'main.tf').write_text(code)
        result = subprocess.run(['terraform', '-chdir=' + tmp, 'console', '-no-color'],
            input='jsonencode({valid=local.valid,capacity=local.capacity})\n', capture_output=True, text=True,
            env={'PATH': os.environ['PATH'], 'CHECKPOINT_DISABLE': '1', 'AWS_EC2_METADATA_DISABLED': 'true'}, timeout=30)
        if result.returncode: raise AssertionError(result.stderr)
        return json.loads(json.loads(result.stdout))


class CacheInfrastructureTest(unittest.TestCase):
    def values(self):
        return {'cache_benchmark_enabled': True, 'cache_benchmark_app_count': 2, 'global_b_services': True,
                'app_enabled': True, 'load_generator_enabled': True, 'global_b_service_bootstrap_enabled': False,
                'mode': 'performance', 'dns_mode': 'direct-only', 'lab_power': None}

    def test_explicit_fixed_two_app_topology(self):
        result = evaluate(self.values())
        self.assertTrue(result['valid'])
        self.assertEqual(result['capacity'], {'min': 2, 'desired': 2, 'max': 2})

    def test_default_preserves_one_app_and_no_new_opt_in(self):
        result = evaluate(self.values() | {'cache_benchmark_enabled': False, 'load_generator_enabled': False})
        self.assertTrue(result['valid'])
        self.assertEqual(result['capacity'], {'min': 1, 'desired': 1, 'max': 1})

    def test_bootstrap_scaling_public_cutover_and_missing_app_are_rejected(self):
        for change in ({'global_b_services': False}, {'app_enabled': False}, {'load_generator_enabled': False},
                       {'global_b_service_bootstrap_enabled': True}, {'mode': 'scaling'}, {'dns_mode': 'cutover'},
                       {'lab_power': {'phase': 'stopped'}}):
            with self.subTest(change=change):
                self.assertFalse(evaluate(self.values() | change)['valid'])
        self.assertFalse(evaluate(self.values(), data_ready=False)['valid'])

    def test_alb_ingress_is_limited_to_current_generator_public_ipv4(self):
        source = block((LAB / 'cache-benchmark.tf').read_text(), 'resource',
                       'aws_vpc_security_group_ingress_rule', 'cache_benchmark_loadgen')
        self.assertEqual(attribute(source, 'cidr_ipv4'), '"${module.load_generator[0].public_ip}/32"')
        self.assertEqual(attribute(source, 'from_port'), '443')
        self.assertEqual(attribute(source, 'to_port'), '443')
        self.assertEqual(attribute(source, 'count'), 'var.cache_benchmark_enabled && var.load_generator_enabled ? 1 : 0')


if __name__ == '__main__':
    unittest.main()
