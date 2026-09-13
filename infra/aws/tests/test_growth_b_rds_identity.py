"""Exercise real HCL consumers with the distinct AWS provider 6 RDS identities."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from test_global_b_infrastructure import LAB, attribute, block


class RdsIdentity(unittest.TestCase):
    def test_runtime_api_and_metric_bindings_use_database_name(self):
        name = 'airbob-lab-rds-identity-test'
        resource_id = 'db-ABCDEFGHIJKLMNOPQRSTUVWX'
        # Provider v6 uses DbiResourceId for both id and resource_id. The
        # user-facing DBInstanceIdentifier is a separate identifier attribute.
        provider = {'id': resource_id, 'resource_id': resource_id, 'identifier': name,
                    'arn': 'arn:aws:rds:ap-northeast-2:942632789808:db:' + name,
                    'address': name + '.example.ap-northeast-2.rds.amazonaws.com',
                    'port': 3306, 'master_user_secret': [{'secret_arn': 'arn:test:secret'}]}
        outputs = (LAB / 'modules/rds/outputs.tf').read_text()
        rds = {key: attribute(block(outputs, 'output', key), 'value')
               .replace('aws_db_instance.this', 'local.provider_rds')
               for key in ('id', 'identifier', 'resource_id', 'arn', 'address', 'master_secret_arn')}
        growth = (LAB / 'growth-b.tf').read_text()
        app = (LAB / 'app.tf').read_text()
        expressions = {
            'prepare': attribute(attribute(growth, 'growth_b_context'), 'rds', 4),
            'service': attribute(attribute(growth, 'growth_b_service_context'), 'rds', 4),
            'app': attribute(attribute(app, 'app_runtime_contract'), 'rds', 4),
            'operator': attribute(attribute(block((LAB / 'outputs.tf').read_text(),
                                                  'output', 'phase3_contract'), 'value'),
                                  'rds_instance_id', 4),
        }
        rows = re.findall(r'(?m)^\s*(\["AWS/RDS",[^\n]+),\s*$', (LAB / 'monitoring.tf').read_text())
        self.assertEqual(6, len(rows))
        expressions['metrics'] = '[' + ',\n'.join(rows) + ']'
        config = 'locals {\nprovider_rds = ' + json.dumps(provider) + '\n'
        config += 'rds = [{' + '\n'.join(key + '=' + expression for key, expression in rds.items()) + '}]\n'
        config += '''services_enabled = true
application_infrastructure_enabled = true
dataset_manifest = {rds={serverUuid="11111111-2222-3333-4444-555555555555"}}
'''
        for key, expression in expressions.items():
            config += key + '=' + expression.replace('module.rds', 'local.rds') + '\n'
        config += '}\n'
        with tempfile.TemporaryDirectory(prefix='airbob-rds-identity-') as directory:
            Path(directory, 'main.tf').write_text(config)
            env = dict(PATH=os.environ['PATH'], CHECKPOINT_DISABLE='1', TF_IN_AUTOMATION='1',
                       AWS_EC2_METADATA_DISABLED='true')
            result = subprocess.run(['terraform', '-chdir=' + directory, 'console', '-no-color'],
                                    input='jsonencode({rds=local.rds[0],prepare=local.prepare,service=local.service,app=local.app,operator=local.operator,metrics=local.metrics})\n',
                                    env=env, text=True, capture_output=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stderr)
            observed = json.loads(json.loads(result.stdout))
        self.assertEqual(resource_id, observed['rds']['id'])
        self.assertEqual(resource_id, observed['rds']['resource_id'])
        self.assertEqual(name, observed['rds']['identifier'])
        for consumer in ('prepare', 'service', 'app'):
            with self.subTest(consumer=consumer):
                self.assertEqual(name, observed[consumer]['identifier'])
                self.assertEqual(resource_id, observed[consumer]['resourceId'])
        self.assertEqual(name, observed['operator'])
        self.assertTrue(all(row[2:4] == ['DBInstanceIdentifier', name] for row in observed['metrics']))


if __name__ == '__main__':
    unittest.main()
