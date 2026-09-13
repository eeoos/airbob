import importlib.util
from pathlib import Path
import subprocess
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location('image_scope', ROOT / 'infra/aws/scripts/classify-image-publication.py')
scope = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scope)


class ScopeTests(unittest.TestCase):
    def test_only_real_legacy_image_inputs_trigger_push_publication(self):
        for path in ['docker/aws-mirror/Dockerfile', 'docker/kafka/Dockerfile',
                     'docker/debezium/connect-distributed.properties', 'docker/elasticsearch/Dockerfile',
                     'infra/aws/images/release.json']:
            with self.subTest(path=path):
                self.assertTrue(scope.needs_images([path]))
        self.assertFalse(scope.needs_images([
            'infra/aws/scripts/package-service-bundles.sh', 'infra/aws/scripts/deterministic_service_bundle.py',
            'infra/aws/scripts/classify-image-publication.py', '.github/workflows/infra-images.yml',
            '.github/workflows/aws-growth-b-images.yml', 'infra/aws/images/global-b-runtime.json',
            'docker/aws-global-b-debezium/Dockerfile', 'infra/aws/bundles/debezium/compose.yml',
            'monitoring/prometheus/prometheus.aws.yml', 'infra/aws/scripts/growth_b_images.py',
            'infra/aws/scripts/publish-ecr-image.sh', 'docs/performance/aws-performance-lab.md',
            'docker/kafka/init-topics.sh', 'docker/debezium/register-connector.sh',
            'docker/debezium/monitor-connector.sh']))

    def test_inventory_covers_every_dockerfile_local_copy_or_add_source(self):
        for directory in scope.IMAGE_DIRECTORIES:
            dockerfile = (ROOT / directory / 'Dockerfile').read_text()
            for line in dockerfile.splitlines():
                match = re.match(r'^\s*(?:COPY|ADD)\s+(.*)$', line, re.IGNORECASE)
                if not match or match[1].startswith('--from='):
                    continue
                # Closed current Dockerfile syntax: one plain local file and a
                # destination. New syntax requires an explicit scope update.
                parts = match[1].split()
                self.assertEqual(len(parts), 2)
                self.assertRegex(parts[0], r'^[A-Za-z0-9_.-]+$')
                self.assertIn(directory + parts[0], scope.IMAGE_FILES)

    def test_gate_only_and_full_config_diff_with_real_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', root, *args], stderr=subprocess.DEVNULL)
            git('init', '-q')
            git('config', 'user.name', 'Scope test')
            git('config', 'user.email', 'scope@example.invalid')
            (root / 'base').write_text('base')
            git('add', '.')
            git('commit', '-qm', 'base')
            before = git('rev-parse', 'HEAD').decode().strip()
            for name in ['.github/workflows/infra-images.yml', 'infra/aws/scripts/package-service-bundles.sh',
                         'docker/aws-global-b-debezium/Dockerfile', 'infra/aws/images/global-b-runtime.json']:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('config-only')
            git('add', '.')
            git('commit', '-qm', 'gate and B inputs')
            after = git('rev-parse', 'HEAD').decode().strip()
            self.assertFalse(scope.classify('push', before, after, after, git))
            self.assertTrue(scope.classify('workflow_dispatch', '', after, after, git))
            path = root / 'docker/debezium/Dockerfile'
            path.parent.mkdir(parents=True)
            path.write_text('real legacy source change')
            git('add', '.')
            git('commit', '-qm', 'legacy image')
            image_commit = git('rev-parse', 'HEAD').decode().strip()
            self.assertTrue(scope.classify('push', after, image_commit, image_commit, git))

    def test_bad_or_unavailable_git_input_fails_closed(self):
        commit = 'a' * 40
        def git(*args):
            return (commit + '\n').encode()
        for event, before, after, actual in [('push', '0' * 40, commit, commit),
                ('pull_request', commit, commit, commit), ('push', commit, '$(bad)', commit),
                ('push', commit, commit, 'b' * 40)]:
            with self.subTest(event=event, before=before):
                with self.assertRaises(ValueError):
                    scope.classify(event, before, after, actual, git)
        def failed(*args):
            raise subprocess.CalledProcessError(1, 'git')
        with self.assertRaises(subprocess.CalledProcessError):
            scope.classify('push', commit, commit, commit, failed)

    def test_all_legacy_write_jobs_consume_gate(self):
        workflow = (ROOT / '.github/workflows/infra-images.yml').read_text()
        for name in ('publish-ecr-images', 'publish-oci-compat-images', 'publish-service-bundles'):
            job = re.split(r'\n  \S', workflow.split('  ' + name + ':\n', 1)[1], maxsplit=1)[0]
            self.assertIn("needs.prepare.outputs.images_required == 'true'", job)
        self.assertIn('images_required: ${{ steps.scope.outputs.images_required }}', workflow)


if __name__ == '__main__':
    unittest.main()
