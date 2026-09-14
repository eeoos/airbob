"""Offline B service admission, source binding, and controller/legacy separation."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
SCRIPTS = ROOT / 'infra/aws/scripts'
sys.path.insert(0, str(SCRIPTS))
import growth_b_service as service
import growth_b_prepare as prepare


def fixture():
    dataset, run, release = 'global-growth-b-' + 'a' * 16, 'lab-b-services-test', 'service-01'
    def ref(key):
        return {'key': key, 'versionId': 'version-' + str(len(key)), 'sha256': 'b' * 64, 'bytes': 100}
    rds = {'identifier': 'airbob-' + run, 'resourceId': 'db-' + 'A' * 26, 'serverUuid': '12345678-1234-1234-1234-123456789abc'}
    image = service.ACCOUNT + '.dkr.ecr.' + service.REGION + '.amazonaws.com/'
    value = {'schemaVersion': 1, 'kind': service.KIND, 'datasetId': dataset, 'runId': run, 'serviceRelease': release,
        'account': service.ACCOUNT, 'region': service.REGION, 'mysql': {'version': '8.4.11', 'flywayVersion': 28, 'schema': 'airbobdb'},
        'rds': rds, 'application': {'mainCommit': service.app_runtime.MAIN_COMMIT, 'image': service.app_runtime.IMAGE,
            'appJarSha256': service.app_runtime.SOURCE['sha256'], 'migrationFilesSha256': 'f' * 64},
        'appRuntimeBinding': ref(f'datasets/{dataset}-aws-service/{release}/files/app-runtime-binding.json'),
        'debezium': {'image': image + 'airbob-infra/debezium@sha256:' + '9' * 64, 'buildCommit': 'c' * 40,
            'pluginVersion': service.DEBEZIUM_PLUGIN_VERSION, 'pluginJarSha256': service.DEBEZIUM_PLUGIN_JAR_SHA256, 'connectVersion': '3.7.0'},
        'preparation': {'receipt': ref(f'data-bootstrap/{run}/{dataset}.json'), 'restoreConfigSha256': '1' * 64,
            'restoreReceiptSha256': '2' * 64, 'preparedFingerprintSha256': '3' * 64,
            'rdsCaBundle': ref(f'datasets/{dataset}-aws-preparation/files/' + 'b' * 64 + '-rds-ca.pem')},
        'search': {'snapshotRelease': dataset + '-search-dev-20260911',
            'transport': ref(f'datasets/{dataset}-search/{dataset}-search-dev-20260911/transport-manifest.json'),
            'restoreReceipt': ref(f'data-bootstrap/{run}/search-restore.json'),
            'image': image + 'airbob-infra/elasticsearch@sha256:' + '4' * 64,
            'documentFingerprint': {'algorithm': 'airbob-es-accommodation-id-asc-length-prefixed-json-v1', 'documents': 657358,
                'contentSha256': '5' * 64, 'identityPairsSha256': '6' * 64, 'mappingSha256': '7' * 64, 'indexSemanticsSha256': '8' * 64}},
        'consumerTools': ref(f'datasets/{dataset}-aws-service/{release}/files/consumer-tools.tar.gz'),
        'toolSources': {name: service.sha(SCRIPTS / name) for name in service.TOOLS},
        'cdc': service.cdc_identity(run, rds['serverUuid'])}
    return value


def runtime_projection(manifest):
    return {'imageJarSha256': service.app_runtime.ACTUAL['sha256'], 'runtimeDigest': service.app_runtime.RUNTIME_DIGEST,
        'runtimeContract': service.app_runtime.CONTRACT, 'runtimeRevision': manifest['application']['mainCommit'],
        'sourceJarSha256': manifest['application']['appJarSha256'], 'image': manifest['application']['image'],
        'mainCommit': manifest['application']['mainCommit']}


def receipt(manifest):
    return {'schemaVersion': 1, 'kind': service.KIND + '-readiness', 'state': service.READY,
        'manifestSha256': '0' * 64, **{key: manifest[key] for key in ('runId', 'datasetId', 'serviceRelease', 'mysql',
            'rds', 'application', 'appRuntimeBinding', 'debezium', 'cdc', 'toolSources')},
        'appRuntime': runtime_projection(manifest),
        'preparationReceipt': manifest['preparation']['receipt'],
        'preparedFingerprintSha256': manifest['preparation']['preparedFingerprintSha256'],
        'searchRestoreReceipt': manifest['search']['restoreReceipt'], 'searchTransport': manifest['search']['transport'],
        'searchFingerprint': manifest['search']['documentFingerprint'], 'topics': list(service.TOPICS),
        'redisSeparate': True, 'debeziumVerified': True, 'cdcRunning': True, 'heartbeatObserved': True, 'writersStopped': True, 'redisReset': False,
        'applicationStarted': False, 'deploymentReady': False}


class ServiceContract(unittest.TestCase):
    def validate(self, value):
        return service.validate_manifest(value, value['datasetId'], value['runId'], value['serviceRelease'])

    def test_normal_service_requires_explicit_new_contract(self):
        self.assertEqual('8.4.11', self.validate(fixture())['mysql']['version'])
        for key, value in [('kind', 'global-growth-b-aws-data-only-preparation'), ('mysql', {'version': '8.0.46', 'flywayVersion': 27, 'schema': 'airbobdb'})]:
            data = fixture(); data[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(data)

    def test_legacy_debezium_is_rejected_for_b_without_changing_the_legacy_release(self):
        data = fixture(); data['debezium']['pluginVersion'] = '2.6.1.Final'
        with self.assertRaises(ValueError): self.validate(data)
        legacy = json.loads((ROOT / 'infra/aws/images/release.json').read_text())
        self.assertEqual('2.6.1.Final', legacy['artifacts']['debezium']['version'])

    def test_live_debezium_plugin_is_distinct_from_kafka_connect_and_exact_jar(self):
        selected = fixture()['debezium']
        def command(args, **kwargs):
            if args[:2] == ['docker', 'ps']: return b'0123456789ab\n'
            if args[:2] == ['docker', 'inspect']: return selected['image'].encode()
            if args[:3] == ['docker', 'image', 'inspect']: return selected['buildCommit'].encode()
            if args[:2] == ['docker', 'exec']: return (selected['pluginJarSha256']+'  plugin.jar').encode()
            raise AssertionError(args)
        def api(method, path):
            self.assertEqual('GET', method)
            if path == '/': return {'version': selected['connectVersion']}
            return [{'class': 'io.debezium.connector.mysql.MySqlConnector', 'type': 'source', 'version': selected['pluginVersion']}]
        with patch.object(service, 'request', side_effect=api):
            service.verify_debezium(selected, command, lambda **_: None)
            wrong = copy.deepcopy(selected); wrong['pluginJarSha256'] = 'f'*64
            with self.assertRaisesRegex(ValueError, 'JAR differs'):
                service.verify_debezium(wrong, command, lambda **_: None)
        with patch.object(service, 'request', return_value=[{'class': 'io.debezium.connector.mysql.MySqlConnector',
                'type': 'source', 'version': '2.6.1.Final'}]):
            with self.assertRaisesRegex(ValueError, 'selected Debezium plugin'):
                service.verify_debezium(selected, command, lambda **_: None)

    def test_controller_selects_only_the_separate_exact_b_debezium_tag(self):
        import shlex
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        marker = '  if [[ "$global_b_services" == true ]]; then\n    # B/MySQL 8.4'
        start = source.index(marker)
        branch = source[start:source.index('\n  fi\n}', start) + len('\n  fi')]
        value = fixture()
        with tempfile.TemporaryDirectory() as directory:
            manifest = Path(directory) / 'manifest.json'; manifest.write_text(json.dumps(value))
            repository = value['debezium']['image'].split('@')[0]
            prefix = ('set -euo pipefail\nfail() { exit 2; }\nAWS_REGION=ap-northeast-2\n'
                'dataset_manifest=' + shlex.quote(str(manifest)) + '\n'
                'lab_contract=' + shlex.quote(json.dumps({'ecr_repositories': {'DEBEZIUM_IMAGE': {'url': repository}}})) + '\n'
                'infra_image_references=\'{"DEBEZIUM_IMAGE":"legacy-original"}\'\n'
                'aws() { [[ "$*" == *"imageTag=global-b-' + value['debezium']['buildCommit'] + '"* ]] || exit 3; printf "%s" "$TEST_DIGEST"; }\n'
                'select_b_image() {\n' + branch + '\n}\n')
            def run(enabled, digest):
                return subprocess.run(['bash'], text=True, capture_output=True, input=prefix+
                    'global_b_services='+enabled+'\nTEST_DIGEST='+shlex.quote(digest)+'\nselect_b_image\nprintf "%s" "$infra_image_references"\n')
            result = run('true', value['debezium']['image'].split('@')[1])
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(value['debezium']['image'], json.loads(result.stdout)['DEBEZIUM_IMAGE'])
            self.assertNotEqual(0, run('true', 'sha256:'+'0'*64).returncode)
            result = run('false', '')
            self.assertEqual('legacy-original', json.loads(result.stdout)['DEBEZIUM_IMAGE'])

    def test_source_refs_cannot_pollute_sql_or_cross_run(self):
        data = fixture()
        for group, field, key in [('preparation', 'rdsCaBundle', f"datasets/{data['datasetId']}/rds-ca.pem"),
                ('preparation', 'receipt', 'data-bootstrap/other-run/preparation.json'),
                ('search', 'transport', 'elasticsearch/releases/legacy/transport-manifest.json')]:
            changed = copy.deepcopy(data); changed[group][field]['key'] = key
            with self.subTest(key=key), self.assertRaises(ValueError): self.validate(changed)

    def test_exact_versions_hashes_and_paths_required(self):
        for field, value in [('versionId', 'null'), ('versionId', ''), ('sha256', 'latest'), ('bytes', True),
                ('key', fixture()['consumerTools']['key'].replace('/files/', '/./files/'))]:
            changed = fixture(); changed['consumerTools'][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): self.validate(changed)

    def test_current_sources_are_bound_and_shared_inventory_staged(self):
        value = fixture()
        self.assertIn('growth_b_inventory.py', value['toolSources'])
        self.assertIn('growth_b_app_runtime.py', value['toolSources'])
        service.validate_manifest(value, value['datasetId'], value['runId'], value['serviceRelease'], value['toolSources'])
        changed = copy.deepcopy(value); changed['toolSources']['growth_b_inventory.py'] = '0' * 64
        with self.assertRaises(ValueError):
            service.validate_manifest(changed, value['datasetId'], value['runId'], value['serviceRelease'], value['toolSources'])

    def test_dependency_phase_can_precede_search_but_application_cannot(self):
        manifest = fixture(); manifest['search']['restoreReceipt'] = None
        self.validate(manifest)
        with self.assertRaises(ValueError): service.validate_readiness(receipt(manifest), manifest, '0' * 64)

    def test_pre_application_proof_is_bound_to_every_dependency(self):
        manifest = fixture(); proof = receipt(manifest)
        self.assertTrue(service.validate_readiness(proof, manifest, '0' * 64)['applicationAdmitted'])
        changes = [('state', 'COMPLETE'), ('manifestSha256', '9' * 64), ('deploymentReady', True), ('redisSeparate', False),
            ('redisReset', True), ('heartbeatObserved', False), ('cdcRunning', False), ('writersStopped', False), ('applicationStarted', True), ('topics', [])]
        for field, value in changes:
            changed = copy.deepcopy(proof); changed[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): service.validate_readiness(changed, manifest, '0' * 64)
        for group, field in [('rds', 'serverUuid'), ('rds', 'resourceId'), ('application', 'mainCommit'),
                ('searchTransport', 'versionId'), ('searchRestoreReceipt', 'sha256'), ('appRuntimeBinding', 'versionId'),
                ('appRuntime', 'runtimeDigest'), ('appRuntime', 'imageJarSha256')]:
            changed = copy.deepcopy(proof); changed[group][field] = 'other'
            with self.subTest(group=group, field=field), self.assertRaises(ValueError): service.validate_readiness(changed, manifest, '0' * 64)

    def test_cdc_is_fresh_per_rds_and_only_routes_outbox(self):
        manifest = fixture(); ident = manifest['cdc']
        self.assertNotEqual(ident, service.cdc_identity(manifest['runId'], '99999999-1234-1234-1234-123456789abc'))
        self.assertNotEqual(ident, service.cdc_identity('lab-other', manifest['rds']['serverUuid']))
        config = service.connector_config(manifest, 'reviewed.rds.amazonaws.com', 'private-not-recorded')
        self.assertEqual('no_data', config['snapshot.mode'])
        self.assertEqual('required', config['database.ssl.mode'])
        self.assertEqual('airbobdb.outbox', config['table.include.list'])
        import re
        regex = re.compile(config['predicates.IsOutboxTable.pattern'])
        self.assertTrue(regex.fullmatch(ident['topicPrefix'] + '.airbobdb.outbox'))
        self.assertFalse(regex.fullmatch('__debezium-heartbeat.' + ident['topicPrefix']))
        self.assertFalse(regex.fullmatch('airbob_server.airbobdb.outbox'))

    def test_legacy_runtime_stays_isolated_and_b_inventory_is_live(self):
        legacy = (ROOT / 'infra/aws/lab/templates/start-app.sh.tftpl').read_text()
        normal = (ROOT / 'infra/aws/lab/templates/start-growth-b-app.sh.tftpl').read_text()
        self.assertIn("spring_profiles='aws,performance-lab,test'", legacy)
        self.assertIn('RESERVATION_INVENTORY_STARTUP_ENABLED=false', legacy)
        self.assertNotIn('performance-lab,test', normal)
        self.assertIn('SPRING_PROFILES_ACTIVE=aws', normal); self.assertIn('SPRING_FLYWAY_TARGET=28', normal)
        for setting in ('SPRING_CLOUD_AWS_REGION_STATIC=ap-northeast-2', 'AWS_EC2_METADATA_DISABLED=true',
                        'SPRING_CLOUD_AWS_CREDENTIALS_ACCESS_KEY=dummy', 'AWS_S3_WRITE_ENABLED=false'):
            self.assertIn(setting, normal)
        for flag in ('STARTUP', 'SEED', 'RETENTION'):
            self.assertIn('RESERVATION_INVENTORY_' + flag + '_ENABLED=true', normal)
        self.assertIn('sslMode=VERIFY_IDENTITY', normal)
        self.assertIn('org.opencontainers.image.revision', normal)
        self.assertIn("'.appRuntime.imageJarSha256'", normal)
        self.assertIn('fetch appRuntimeBinding', normal)
        self.assertIn('$r.appRuntimeBinding==$c.appRuntimeBinding', normal)

    def test_runtime_binding_cannot_pollute_sealed_sql_or_admit_unreviewed_runtime(self):
        manifest = fixture()
        manifest['appRuntimeBinding']['key'] = f"datasets/{manifest['datasetId']}/app-runtime-binding.json"
        with self.assertRaises(ValueError): self.validate(manifest)
        manifest = fixture(); proof = receipt(manifest)
        proof['appRuntime']['imageJarSha256'] = manifest['application']['appJarSha256']
        with self.assertRaises(ValueError): service.validate_readiness(proof, manifest, '0'*64)
        self.assertEqual({'mainCommit', 'image', 'appJarSha256', 'migrationFilesSha256'}, set(manifest['application']))

    def test_controller_retains_original_resource_fence_and_ttl(self):
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        self.assertIn('continue_global_b_services', source)
        self.assertIn('global_b_lease_fencing_token:(if $services then $operationFence else 0 end)', source)
        self.assertIn('fencing_token:(if $services then $resourceFence else .fencing_token end)', source)
        self.assertIn('Original paid-resource TTL cannot cover', source)
        self.assertIn('assert_state_run_identity required', source)
        self.assertIn('B_READINESS_VERSION_ID', source)
        self.assertIn('B service transition must retain', source)

    def test_service_ingress_uses_current_runner_and_rejects_broad_or_private_addresses(self):
        import re, shlex
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        functions = '\n'.join(re.search(r'(?ms)^' + name + r'\(\) \{\n.*?^\}\n', source).group(0)
            for name in ('valid_ipv4', 'valid_public_ipv4', 'select_global_b_service_ingress'))
        with tempfile.TemporaryDirectory() as directory:
            original = Path(directory) / 'operator.json'
            original.write_text(json.dumps({'albIngressCidr': '8.8.8.8/32'}))
            def call(cidr):
                body = 'set -euo pipefail\nfail() { exit 2; }\n' + functions + '\n'
                body += 'ALB_INGRESS_CIDR=' + shlex.quote(cidr) + '\nselect_global_b_service_ingress ' + shlex.quote(str(original))
                body += '\nprintf "%s" "$alb_ingress_cidr"\n'
                return subprocess.run(['bash'], input=body, text=True, capture_output=True)
            self.assertEqual('1.1.1.1/32', call('1.1.1.1/32').stdout)
            self.assertEqual('8.8.8.8/32', call('').stdout)
            for value in ('0.0.0.0/0', '1.1.1.1/24', '10.0.0.1/32', '1.01.1.1/32', '1.1.1.1/32\nEVIL=1'):
                with self.subTest(value=value): self.assertNotEqual(0, call(value).returncode)
            self.assertEqual('8.8.8.8/32', json.loads(original.read_text())['albIngressCidr'])

    def test_snapshot_preparation_cannot_be_replaced_with_dump_receipt(self):
        manifest = fixture()
        proof = {'kind': 'global-growth-b-aws-data-only-preparation', 'state': 'DATABASE_INVENTORY_LOGIN_VERIFIED',
            'datasetId': manifest['datasetId'], 'runId': manifest['runId'], 'rdsResourceId': manifest['rds']['resourceId'],
            'serverUuid': manifest['rds']['serverUuid'], 'restoreReceiptSha256': manifest['preparation']['restoreReceiptSha256'],
            'preparation': {'preparedFingerprintSha256': manifest['preparation']['preparedFingerprintSha256']},
            'deploymentReady': False, 'applicationLeftRunning': False}
        service.validate_preparation(proof, manifest, 'dump', None)
        with self.assertRaises(ValueError): service.validate_preparation(proof, manifest, 'snapshot', 'd'*64)
        proof.update(sourceMode='verified-global-b-snapshot', snapshotProvenanceSha256='d'*64,
            snapshotRestoreEvidence={'eventSha256': 'e'*64, 'evidenceSource': 'controller-pinned-cloudtrail-event'})
        service.validate_preparation(proof, manifest, 'snapshot', 'd'*64)
        with self.assertRaises(ValueError): service.validate_preparation(proof, manifest, 'snapshot', 'f'*64)
        with self.assertRaises(ValueError): service.validate_preparation(proof, manifest, 'dump', None)

    def test_snapshot_target_admission_precedes_rds_creation_and_emits_existing_readable_evidence_prefix(self):
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        start = source.index('current_stage=services-and-data-bootstrap')
        self.assertLess(source.index('write_global_b_snapshot_admission', start), source.index('apply_lab # deployment_phase=services', start))
        self.assertIn('snapshot.restore_admission(configuration, snapshot.restore.Aws())', source)
        self.assertIn('data-bootstrap/$run_id/b-snapshot-admission-$fencing_token.json', source)
        self.assertIn('data-bootstrap/$run_id/b-service-transition-', source)

    def test_actual_controller_tfvars_keeps_resource_fence_and_separate_lease(self):
        import re, shlex
        source = (SCRIPTS / 'aws-lab.sh').read_text()
        function = re.search(r'(?ms)^write_tfvars\(\) \{\n.*?^\}\n', source).group(0)
        with tempfile.TemporaryDirectory() as directory:
            values = dict(temp_dir=directory, run_id='lab-b-services-test', expires_at='1999999999', fencing_token='99',
                resource_fencing_token='62', ami_id='ami-0123456789abcdef0', bundle_commit='c'*40, bundle_sha256='b'*64,
                infra_image_references='{}', app_image_reference='image@sha256:'+'d'*64, mode='performance', policy='integrated-smoke',
                cache_enabled='true', request_target='', load_generator_enabled='false', dataset_release='global-growth-b-'+'a'*16,
                dataset_manifest_sha256='e'*64, database_bootstrap='dump', global_b_prepare_only='false', global_b_import_from_mac='false',
                global_b_services='true', global_b_snapshot_restore_only='false', global_b_snapshot_provenance='null', global_b_service_release='service-01', global_b_service_bootstrap_enabled='false',
                global_b_readiness_receipt='null', dataset_manifest_version_id='version-service', lease_owner='owner/current',
                rds_snapshot_identifier='', rds_snapshot_source_run_id='', rds_snapshot_source_resource_id='',
                rds_engine_version='8.4.11', rds_instance_class='db.t3.small', dns_mode='direct-only', alb_ingress_cidr='203.0.113.1/32')
            prefix = 'set -euo pipefail\n' + '\n'.join(key+'='+shlex.quote(value) for key,value in values.items()) + '\n'
            result = subprocess.run(['bash'], input=prefix+function+'write_tfvars services false i-0123456789abcdef0\ncat "$current_tfvars"\n',
                text=True, capture_output=True, check=True)
            data = json.loads(result.stdout)
            self.assertEqual(62, data['fencing_token']); self.assertEqual(99, data['global_b_lease_fencing_token'])
            self.assertEqual('owner/current', data['global_b_lease_owner']); self.assertTrue(data['global_b_services'])
            self.assertFalse(data['app_enabled']); self.assertEqual('1999999999', data['expires_at'])
            self.assertEqual('db.t3.small', data['rds_instance_class'])
            large = prefix+'rds_instance_class=db.m6i.large\n'+function+'write_tfvars services false i-0123456789abcdef0\ncat "$current_tfvars"\n'
            selected = json.loads(subprocess.run(['bash'], input=large, text=True, capture_output=True, check=True).stdout)
            self.assertEqual('db.m6i.large', selected['rds_instance_class'])
            self.assertEqual(62, selected['fencing_token']); self.assertEqual('1999999999', selected['expires_at'])
            legacy = prefix+'global_b_services=false\n'+function+'write_tfvars services false i-0123456789abcdef0\ncat "$current_tfvars"\n'
            data = json.loads(subprocess.run(['bash'], input=legacy, text=True, capture_output=True, check=True).stdout)
            self.assertEqual(99, data['fencing_token']); self.assertEqual(0, data['global_b_lease_fencing_token'])
            self.assertEqual('', data['global_b_manifest_version_id']); self.assertEqual('', data['global_b_lease_owner'])

    def test_preparation_content_addressing_admits_consumer_revisions_without_overwrite(self):
        dataset = 'global-growth-b-' + 'a' * 16
        sources = prepare.source_hashes(SCRIPTS)
        value = {'schemaVersion': 1, 'kind': prepare.KIND, 'datasetId': dataset, 'account': prepare.ACCOUNT,
            'region': prepare.REGION, 'scope': 'small-rds-rehearsal', 'mysql': {'version': '8.4.11', 'flywayVersion': 28},
            'files': {name: {'key': f'datasets/{dataset}-aws-preparation/files/'+'b'*64+'-'+filename,
                'sha256': 'b'*64, 'bytes': 100, 'versionId': 'version-1'} for name,filename in prepare.FILES.items()},
            'toolSources': sources, 'toolchain': {'system': 'Linux', 'architecture': 'x86_64', 'pythonVersion': '3.12.14',
                'javaVersion': '21.0.12.1', 'mysqlVersion': '8.4.11', 'awsCliVersion': '2.34.64', 'unpackedBytes': 844524385},
            'storage': {'rdsAllocatedGiB': 100, 'dataHostRootGiB': 20, 'minimumStagingFreeBytes': 8*1024**3},
            'binlogAdditionalReserveBytes': 1024**3}
        prepare.validate_manifest(value, dataset, sources)
        revised = copy.deepcopy(value)
        revised['files']['consumerTools'].update(sha256='c'*64, versionId='version-2',
            key=f'datasets/{dataset}-aws-preparation/files/'+'c'*64+'-consumer-tools.tar.gz')
        prepare.validate_manifest(revised, dataset, sources)
        self.assertNotEqual(value['files']['consumerTools']['key'], revised['files']['consumerTools']['key'])
        for key in (f'datasets/{dataset}/consumer-tools.tar.gz', f'datasets/{dataset}-aws-preparation/files/consumer-tools.tar.gz',
                value['files']['consumerTools']['key']):
            bad = copy.deepcopy(revised); bad['files']['consumerTools']['key'] = key
            with self.subTest(key=key), self.assertRaises(ValueError): prepare.validate_manifest(bad, dataset, sources)
        self.assertIn('aws-preparation-${var.dataset_manifest_sha256}.json', (ROOT/'infra/aws/lab/locals.tf').read_text())
        self.assertIn('aws-preparation-$B_PREPARATION_SHA256.json', (SCRIPTS/'aws-lab.sh').read_text())
        self.assertIn('aws-preparation-$(jq', (SCRIPTS/'bootstrap-growth-b-entry.sh').read_text())

    def test_mysql_capability_uses_accepted_option_and_invalid_probe(self):
        source = (SCRIPTS / 'growth_b_prepare.py').read_text()
        self.assertIn("'--ssl-mode=VERIFY_IDENTITY', '--help'", source)
        self.assertIn("'--ssl-mode=AIRBOB_INVALID_MODE', '--help'", source)
        self.assertNotIn("'VERIFY_IDENTITY' in help_text", source)


if __name__ == '__main__': unittest.main()
