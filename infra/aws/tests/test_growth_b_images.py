import copy
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'infra/aws/scripts'))
import growth_b_images as images
from deterministic_service_bundle import write_archive


def ecr_response(variable='DEBEZIUM_IMAGE', tag=None):
    tag = tag or images.RUNTIME
    body = json.dumps({'schemaVersion': 2, 'mediaType': images.MEDIA[0], 'manifests': [
        {'digest': 'sha256:' + digit * 64, 'platform': {'os': 'linux', 'architecture': arch}}
        for arch, digit in [('amd64', '1'), ('arm64', '2')]]})
    return {'images': [{'registryId': images.ACCOUNT, 'repositoryName': images.REPOSITORIES[variable],
                        'imageId': {'imageTag': tag, 'imageDigest': 'sha256:' + images.sha(body.encode())},
                        'imageManifest': body}], 'failures': []}


class FakeCloud:
    def __init__(self):
        self.objects = {}
        self.calls = []
        self.get_wrong_version = False
        self.head_denied = False
        self.fail_put = None
        self.no_list_bucket = False
        self.ecr = ecr_response()

    def call(self, *args, missing=False):
        self.calls.append(args)
        if args[:2] == ('ecr', 'batch-get-image'):
            return copy.deepcopy(self.ecr)
        operation = args[1]
        key = args[args.index('--key') + 1]
        if operation == 'head-object':
            if self.head_denied:
                raise images.GateError('access_denied')
            if key not in self.objects:
                if missing:
                    if self.no_list_bucket:
                        raise images.HeadUnavailable('s3_head_absence_or_access_unresolved')
                    return None
                raise images.GateError('missing_object')
            body, version = self.objects[key]
            return {'ContentLength': len(body), 'VersionId': version, 'ServerSideEncryption': 'AES256',
                    'Metadata': {'commit': images.RUNTIME}}
        if operation == 'put-object':
            if self.fail_put and key.endswith(self.fail_put):
                raise images.GateError('put_rejected')
            if key in self.objects or '--if-none-match' not in args or args[-1] != '*':
                raise images.GateError('overwrite_attempt')
            body = Path(args[args.index('--body') + 1]).read_bytes()
            version = 'version-' + str(len(self.objects))
            self.objects[key] = body, version
            return {'VersionId': version}
        if operation == 'get-object':
            body, version = self.objects[key]
            Path(args[-1]).write_bytes(body)
            return {'VersionId': 'wrong' if self.get_wrong_version else version, 'ContentLength': len(body)}
        raise AssertionError(args)


class ImageContractTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name).resolve()

    def test_index_digest_coordinates_and_both_architectures(self):
        cloud = FakeCloud()
        image = images.image_lookup(cloud, 'DEBEZIUM_IMAGE', images.RUNTIME)
        self.assertEqual(set(image['platforms']), {'amd64', 'arm64'})
        self.assertIn('/airbob-infra/debezium@sha256:', image['image'])
        for mutation in ('digest', 'repository', 'tag', 'account', 'platform'):
            with self.subTest(mutation=mutation):
                cloud.ecr = ecr_response()
                item = cloud.ecr['images'][0]
                if mutation == 'digest':
                    item['imageId']['imageDigest'] = 'sha256:' + 'f' * 64
                elif mutation == 'repository':
                    item['repositoryName'] = 'airbob-repo'
                elif mutation == 'tag':
                    item['imageId']['imageTag'] = 'latest'
                elif mutation == 'account':
                    item['registryId'] = '000000000000'
                else:
                    raw = json.loads(item['imageManifest'])
                    raw['manifests'].pop()
                    item['imageManifest'] = json.dumps(raw)
                    item['imageId']['imageDigest'] = 'sha256:' + images.sha(item['imageManifest'].encode())
                with self.assertRaises(images.GateError):
                    images.image_lookup(cloud, 'DEBEZIUM_IMAGE', images.RUNTIME)

    def test_only_exact_missing_tag_can_authorize_build(self):
        cloud = FakeCloud()
        cloud.ecr = {'images': [], 'failures': [{'failureCode': 'ImageNotFound',
                                               'imageId': {'imageTag': images.RUNTIME}}]}
        self.assertIsNone(images.image_lookup(cloud, 'DEBEZIUM_IMAGE', images.RUNTIME, allow_missing=True))
        for code in ('AccessDenied', 'InvalidImageTag', 'ServerException'):
            cloud.ecr['failures'][0]['failureCode'] = code
            with self.assertRaises(images.GateError):
                images.image_lookup(cloud, 'DEBEZIUM_IMAGE', images.RUNTIME, allow_missing=True)
        with self.assertRaises(images.GateError):
            images.image_lookup(cloud, 'DEBEZIUM_IMAGE', 'latest', allow_missing=True)

    def test_duplicate_or_unexpected_platform_rejected(self):
        manifest = json.loads(ecr_response()['images'][0]['imageManifest'])
        for extra in (manifest['manifests'][0], {'digest': 'sha256:' + '3' * 64,
                    'platform': {'os': 'windows', 'architecture': 'amd64'}},
                    {'digest': 'sha256:' + '3' * 64, 'platform': {'os': 'unknown', 'architecture': 'unknown'}}):
            with self.assertRaises(images.GateError):
                images.platform_children({**manifest, 'manifests': manifest['manifests'] + [extra]})
        attestation = {'platform': {'os': 'unknown', 'architecture': 'unknown'},
                       'annotations': {'vnd.docker.reference.type': 'attestation-manifest'}}
        self.assertEqual(len(images.platform_children({**manifest,
                         'manifests': manifest['manifests'] + [attestation]})), 2)

    def test_revision_is_verified_on_each_child(self):
        image = images.image_lookup(FakeCloud(), 'DEBEZIUM_IMAGE', images.RUNTIME)
        seen = []
        def runner(args):
            seen.append(args)
            arch = 'amd64' if args[-1].endswith('1' * 64) else 'arm64'
            return json.dumps({'architecture': arch, 'os': 'linux',
                               'config': {'Labels': {'org.opencontainers.image.revision': images.RUNTIME}}}).encode()
        result = images.image_metadata(image, images.RUNTIME, runner)
        self.assertEqual(len(seen), 2)
        self.assertEqual(set(result), {'amd64', 'arm64'})
        with self.assertRaises(images.GateError):
            images.image_metadata(image, 'a' * 40, runner)

    def test_b_probe_has_no_network_and_requires_exact_plugin_and_connect_jars(self):
        commands = []
        output = (images.PLUGIN_SHA + '  /opt/kafka/connect-plugins/debezium-mysql/'
                  'debezium-connector-mysql-3.0.8.Final.jar\n' + images.JMX_SHA +
                  '  /opt/jmx/jmx_prometheus_javaagent.jar\n').encode()
        def runner(args, **kwargs):
            commands.append(args)
            return b'' if args[1] == 'pull' else output
        proof = images.runtime_probe('DEBEZIUM_IMAGE', 'fixed@sha256:123', b=True, runner=runner)
        self.assertFalse(proof['restVersionMeasured'])
        self.assertFalse(proof['connectWorkerStarted'])
        self.assertEqual(proof['connectVersion'], '3.7.0')
        run = commands[1]
        self.assertEqual(run[run.index('--network') + 1], 'none')
        self.assertIn('--read-only', run)
        self.assertIn('connect-runtime-3.7.0.jar', run[-1])
        self.assertNotIn('connect-distributed.sh', run[-1])
        output = output.replace(images.PLUGIN_SHA.encode(), b'0' * 64)
        with self.assertRaises(images.GateError):
            images.runtime_probe('DEBEZIUM_IMAGE', 'fixed', b=True, runner=runner)

    def test_publisher_identity_cannot_be_admin_or_dataset_role(self):
        cloud = images.Cloud()
        for arn in (f'arn:aws:iam::{images.ACCOUNT}:user/admin-eeoos',
                    f'arn:aws:sts::{images.ACCOUNT}:assumed-role/airbob-dataset-publisher/session',
                    'arn:aws:sts::000000000000:assumed-role/airbob-image-publisher/session'):
            with patch.object(cloud, 'call', return_value={'Account': images.ACCOUNT, 'Arn': arn}):
                with self.assertRaises(images.GateError):
                    cloud.identity()
        with patch.object(cloud, 'call', return_value={'Account': images.ACCOUNT,
                          'Arn': f'arn:aws:sts::{images.ACCOUNT}:assumed-role/airbob-image-publisher/session'}):
            self.assertEqual(cloud.identity()['role'], 'airbob-image-publisher')

    def test_fixed_contract_matches_frozen_runtime_and_b_dockerfile(self):
        # Only the newly reviewed contract is supplied here; all legacy blobs
        # really come from the fixed commit in the repository.
        original = images.blob
        def blob(root, commit, name):
            if name == images.CONTRACT:
                return (ROOT / name).read_bytes()
            return original(root, commit, name)
        with patch.object(images, 'blob', side_effect=blob):
            spec, legacy, inventory = images.load_contract(ROOT, {'executionCommit': 'a' * 40})
        self.assertEqual(len(legacy['infra']), 9)
        self.assertEqual(len(inventory['files']), 19)
        docker = (ROOT / images.B_CONTEXT / 'Dockerfile').read_text()
        self.assertIn(spec['debezium']['pluginUrl'], docker)
        self.assertIn(images.ARCHIVE_SHA, docker)
        self.assertIn(images.PLUGIN_SHA, docker)
        self.assertNotIn('2.6.1', docker)

    def test_legacy_bundle_exports_exact_nineteen_git_blobs_and_is_repeatable(self):
        inventory = images.parse(images.blob(ROOT, images.RUNTIME, 'infra/aws/bundles/manifest.json'))
        first, second = self.directory / 'first', self.directory / 'second'
        first.mkdir()
        second.mkdir()
        paths, hashes = images.package_bundle(ROOT, inventory, first)
        again, _ = images.package_bundle(ROOT, inventory, second)
        self.assertEqual([path.read_bytes() for path in paths], [path.read_bytes() for path in again])
        with tarfile.open(paths[0]) as archive:
            self.assertEqual(archive.getnames(), inventory['files'])
            for member in archive:
                self.assertEqual(images.sha(archive.extractfile(member).read()), hashes[member.name])
                self.assertEqual(member.uid, 0)
        self.assertEqual(images.parse(paths[-1].read_bytes())['commit'], images.RUNTIME)

    def test_safe_archive_rejects_symlinks_and_noncanonical_paths(self):
        (self.directory / 'file').write_text('value')
        (self.directory / 'link').symlink_to('file')
        for name in ('link', '../file', './file', 'a//file', '/file'):
            with self.subTest(name=name):
                with self.assertRaises((ValueError, FileNotFoundError)):
                    write_archive(self.directory, [name], self.directory / 'out', 1)

    def test_build_reuses_existing_tag_without_pushing(self):
        spec = images.parse((ROOT / images.CONTRACT).read_bytes())
        legacy = images.parse(images.blob(ROOT, images.RUNTIME, 'infra/aws/images/release.json'))
        found = images.image_lookup(FakeCloud(), 'DEBEZIUM_IMAGE', images.RUNTIME)
        runner_calls = []
        with patch.object(images, 'image_lookup', return_value=found), \
             patch.object(images, 'image_metadata', return_value={}), \
             patch.object(images, 'runtime_probe', return_value={}):
            receipt = images.publish_image(ROOT, {'executionCommit': 'a' * 40}, spec, legacy,
                                            FakeCloud(), 'DEBEZIUM_IMAGE',
                                            runner=lambda args, **kwargs: runner_calls.append(args))
        self.assertTrue(receipt['reused'])
        self.assertEqual(runner_calls, [])
        self.assertEqual(receipt['sourceCommit'], images.RUNTIME)
        self.assertIn('docker/debezium/Dockerfile', receipt['sourceFilesSha256'])

    def test_b_build_uses_only_separate_tag_and_reviewed_blob_context(self):
        spec = images.parse((ROOT / images.CONTRACT).read_bytes())
        commit = 'b' * 40
        cloud = FakeCloud()
        cloud.ecr = ecr_response(tag='global-b-' + commit)
        found = images.image_lookup(cloud, 'DEBEZIUM_IMAGE', 'global-b-' + commit)
        calls = []
        with patch.object(images, 'image_lookup', side_effect=[None, found]), \
             patch.object(images, 'image_metadata', return_value={}), \
             patch.object(images, 'runtime_probe', return_value={}), \
             patch.object(images, 'materialize', return_value={'Dockerfile': 'f' * 64}) as materialize:
            receipt = images.publish_image(ROOT, {'executionCommit': commit}, spec, {}, cloud,
                                            'DEBEZIUM_IMAGE', b=True,
                                            runner=lambda args, **kwargs: calls.append(args))
        command = calls[0]
        self.assertIn(images.REGISTRY + '/airbob-infra/debezium:global-b-' + commit, command)
        self.assertIn('--push', command)
        self.assertNotIn('latest', ' '.join(command))
        self.assertNotIn('ghcr.io', ' '.join(command))
        self.assertEqual(materialize.call_args.args[1], commit)
        self.assertEqual(receipt['debezium']['buildCommit'], commit)


class SourceGateTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        for name in images.TOOLS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('reviewed source')
        self.git('init', '-q')
        self.git('config', 'user.name', 'Image test')
        self.git('config', 'user.email', 'images@example.invalid')
        self.git('add', '.')
        self.git('commit', '-qm', 'reviewed main')
        self.commit = self.git('rev-parse', 'HEAD').decode().strip()
        self.git('remote', 'add', 'origin', 'https://github.com/eeoos/airbob.git')
        self.git('update-ref', 'refs/remotes/origin/main', self.commit)
        patcher = patch.object(images, 'RUNTIME', self.commit)
        patcher.start()
        self.addCleanup(patcher.stop)
        self.env = {'GITHUB_ACTIONS': 'true', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
                    'GITHUB_REPOSITORY': 'eeoos/airbob', 'GITHUB_REF': 'refs/heads/main',
                    'GITHUB_SHA': self.commit, 'GITHUB_WORKFLOW_SHA': self.commit,
                    'AIRBOB_EXPECTED_EXECUTION_COMMIT': self.commit,
                    'GITHUB_WORKFLOW_REF': 'eeoos/airbob/' + images.WORKFLOW + '@refs/heads/main'}

    def git(self, *args):
        return subprocess.check_output(['git', '-C', self.root, *args], stderr=subprocess.DEVNULL)

    def test_exact_reviewed_main_workflow_and_hashes(self):
        result = images.source_gate(self.root, self.env, workflow=True)
        self.assertEqual(result['executionCommit'], self.commit)
        self.assertEqual(set(result['executionFilesSha256']), set(images.TOOLS))

    def test_feature_event_wrong_repository_or_workflow_cannot_publish(self):
        for key, value in [('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_EVENT_NAME', 'push'),
                           ('GITHUB_WORKFLOW_REF', 'eeoos/airbob/other.yml@refs/heads/main'),
                           ('GITHUB_SHA', 'a' * 40), ('GITHUB_WORKFLOW_SHA', 'b' * 40),
                           ('AIRBOB_EXPECTED_EXECUTION_COMMIT', 'c' * 40),
                           ('AIRBOB_EXPECTED_EXECUTION_COMMIT', ''),
                           ('GITHUB_REPOSITORY', 'other/airbob')]:
            with self.subTest(key=key), self.assertRaises(images.GateError):
                images.source_gate(self.root, {**self.env, key: value}, workflow=True)

    def test_unreviewed_commit_and_modified_tool_rejected(self):
        path = self.root / images.TOOLS[0]
        path.write_text('unreviewed')
        with self.assertRaises(images.GateError):
            images.source_gate(self.root, self.env, workflow=False)
        self.git('add', '.')
        self.git('commit', '-qm', 'feature commit outside origin main')
        with self.assertRaises(images.GateError):
            images.source_gate(self.root, self.env, workflow=False)

    def test_materialize_ignores_local_credentials_and_rejects_git_symlink(self):
        context = self.root / images.B_CONTEXT
        (context / 'credentials.env').write_text('private-marker-not-for-export')
        output = self.root / 'export'
        output.mkdir()
        hashes = images.materialize(self.root, self.commit, [images.B_CONTEXT], output)
        self.assertNotIn(images.B_CONTEXT + '/credentials.env', hashes)
        self.assertFalse((output / images.B_CONTEXT / 'credentials.env').exists())
        (context / 'link').symlink_to('Dockerfile')
        self.git('add', images.B_CONTEXT + '/link')
        self.git('commit', '-qm', 'invalid linked source')
        linked = self.git('rev-parse', 'HEAD').decode().strip()
        with self.assertRaises(images.GateError):
            images.materialize(self.root, linked, [images.B_CONTEXT], self.root / 'bad-export')


class BundlePublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.paths = [root / ('airbob-service-bundles-' + images.RUNTIME + suffix)
                      for suffix in ('.tar.gz', '.tar.gz.sha256', '.manifest.json')]
        for index, path in enumerate(self.paths):
            path.write_bytes(('public-' + str(index)).encode())
        self.cloud = FakeCloud()

    def test_create_only_marker_last_exact_version_readback_and_idempotent_resume(self):
        receipt = images.publish_bundle(self.cloud, self.paths, create=True)
        puts = [call for call in self.cloud.calls if call[1] == 'put-object']
        self.assertEqual(len(puts), 3)
        self.assertTrue(puts[-1][puts[-1].index('--key') + 1].endswith('.manifest.json'))
        for entry in receipt['objects']:
            self.assertTrue(entry['versionId'].startswith('version-'))
            self.assertIn('get-response-version', entry['verification'])
        self.cloud.calls.clear()
        repeated = images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertEqual(receipt, repeated)
        self.assertFalse(any(call[1] == 'put-object' for call in self.cloud.calls))
        self.assertFalse(any('--version-id' in call or call[1] == 'list-object-versions' for call in self.cloud.calls))

    def test_read_only_verification_cannot_create(self):
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=False)
        self.assertFalse(any(call[1] == 'put-object' for call in self.cloud.calls))

    def test_permission_error_does_not_count_as_absence(self):
        self.cloud.head_denied = True
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertEqual(self.cloud.objects, {})

    def test_missing_head_403_without_list_permission_uses_only_conditional_put(self):
        self.cloud.no_list_bucket = True
        result = images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertEqual(len(result['objects']), 3)
        puts = [call for call in self.cloud.calls if call[1] == 'put-object']
        self.assertEqual(len(puts), 3)
        self.assertTrue(all(call[-2:] == ('--if-none-match', '*') for call in puts))

    def test_create_only_race_is_read_back_without_overwriting(self):
        call = self.cloud.call
        def race(*args, **kwargs):
            if args[1] == 'put-object':
                key = args[args.index('--key') + 1]
                body = Path(args[args.index('--body') + 1]).read_bytes()
                self.cloud.objects[key] = body, 'concurrent-' + str(len(self.cloud.objects))
                raise images.ConditionalExists('conditional_object_already_exists')
            return call(*args, **kwargs)
        with patch.object(self.cloud, 'call', side_effect=race):
            result = images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertTrue(all(item['versionId'].startswith('concurrent-') for item in result['objects']))

    def test_wrong_get_version_blocks_marker(self):
        self.cloud.get_wrong_version = True
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertFalse(any(key.endswith('.manifest.json') for key in self.cloud.objects))

    def test_conflicting_bytes_in_any_existing_object_preflight_before_writes(self):
        key = 'service-bundles/' + images.RUNTIME + '/' + self.paths[1].name
        self.cloud.objects[key] = b'conflicting', 'existing'
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertFalse(any(call[1] == 'put-object' for call in self.cloud.calls))

    def test_partial_failure_and_recovery_write_only_missing_objects(self):
        self.cloud.fail_put = '.sha256'
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertEqual(len(self.cloud.objects), 1)
        self.cloud.fail_put = None
        self.cloud.calls.clear()
        images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertEqual(len([call for call in self.cloud.calls if call[1] == 'put-object']), 2)

    def test_orphan_marker_wrong_names_and_null_versions_rejected(self):
        key = 'service-bundles/' + images.RUNTIME + '/' + self.paths[-1].name
        self.cloud.objects[key] = self.paths[-1].read_bytes(), 'existing'
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, self.paths, create=True)
        self.assertFalse(any(call[1] == 'put-object' for call in self.cloud.calls))
        with self.assertRaises(images.GateError):
            images.publish_bundle(self.cloud, list(reversed(self.paths)), create=True)
        for value in ('', 'null', None):
            with self.assertRaises(images.GateError):
                images.version(value)


class WorkflowTests(unittest.TestCase):
    def test_only_fixed_main_oidc_route_has_mutating_commands(self):
        text = (ROOT / images.WORKFLOW).read_text()
        self.assertIn('workflow_dispatch:', text)
        self.assertNotIn('  push:', text)
        dispatch = text.split('\nenv:', 1)[0]
        self.assertEqual(re.findall(r'^      ([a-z_]+):$', dispatch, re.MULTILINE),
                         ['expected_execution_commit'])
        validation = text.split('  publish-legacy-infra:', 1)[0]
        self.assertIn('[[ "$GITHUB_SHA" == "$AIRBOB_EXPECTED_EXECUTION_COMMIT" ]]', validation)
        self.assertNotIn('configure-aws-credentials', validation)
        for absent in ('ghcr.io', 'packages: write', ':latest', 'create-repository', 'terraform', 'aws iam',
                       'secrets.GITHUB_TOKEN', 'ref: ${{ inputs'):
            self.assertNotIn(absent, text)
        for job in ('publish-legacy-infra', 'publish-b-debezium', 'publish-bundle-and-receipt'):
            body = re.split(r'\n  \S', text.split('  ' + job + ':\n', 1)[1], maxsplit=1)[0]
            self.assertIn('environment: aws-image-publisher', body)
            self.assertIn("github.ref == 'refs/heads/main'", body)
            self.assertIn('role/airbob-image-publisher', body)
            self.assertIn('role-duration-seconds: 7200', body)
        self.assertIn('needs: [publish-legacy-infra, publish-b-debezium]', text)
        for line in text.splitlines():
            if 'uses:' in line:
                self.assertRegex(line, r'@[0-9a-f]{40}$')


if __name__ == '__main__':
    unittest.main()
