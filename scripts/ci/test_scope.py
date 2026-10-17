from pathlib import Path
import subprocess
import tempfile
import unittest

import check_result
import scope


class ScopeTests(unittest.TestCase):
    def test_docs_and_archived_measurements_do_not_run_or_deploy_application(self):
        self.assertEqual(scope.classify(['docs/performance/cache.md', 'README.md',
            'load-test/k6/cache/comparison.js', 'scripts/archive/bootstrap.py']),
            dict(app=False, infrastructure=False, data=False, oci=False))

    def test_application_change_does_not_wait_for_unrelated_aws_tools(self):
        self.assertEqual(scope.classify(['src/main/java/example/Service.java']),
            dict(app=True, infrastructure=False, data=False, oci=True))
        self.assertEqual(scope.classify(['src/test/java/example/ServiceTest.java']),
            dict(app=True, infrastructure=False, data=False, oci=False))

    def test_shared_schema_configuration_and_workflow_inputs_run_all_checks(self):
        for path in ['src/main/resources/db/migration/V32__example.sql',
                     'src/main/resources/application.yaml', 'build.gradle',
                     'docker-compose.yml', '.github/workflows/cd.yml', 'scripts/ci/scope.py']:
            with self.subTest(path=path):
                selected = scope.classify([path])
                self.assertTrue(all(selected[group] for group in ('app', 'infrastructure', 'data')))

    def test_aws_and_dataset_tools_do_not_deploy_oci(self):
        self.assertEqual(scope.classify(['infra/aws/lab/main.tf']),
            dict(app=False, infrastructure=True, data=True, oci=False))
        self.assertEqual(scope.classify(['scripts/restore-growth-b-local.py']),
            dict(app=False, infrastructure=False, data=True, oci=False))
        self.assertTrue(scope.classify(['infra/aws/images/release.json'])['app'])

    def test_deployment_scripts_and_unknown_code_are_not_silently_skipped(self):
        for path in scope.DEPLOYMENT_SCRIPTS:
            with self.subTest(path=path):
                self.assertTrue(all(scope.classify([path]).values()))
        self.assertEqual(scope.classify(['new-runtime/tool.py']),
            dict(app=True, infrastructure=True, data=True, oci=False))

    def test_real_git_diff_detects_deleted_runtime_files_and_more_than_300_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output(['git', '-C', str(root), *args], stderr=subprocess.PIPE).decode().strip()
            git('init', '-q')
            git('config', 'user.name', 'CI test')
            git('config', 'user.email', 'ci@example.invalid')
            source = root / 'src/main/java/Removed.java'
            source.parent.mkdir(parents=True)
            source.write_text('class Removed {}')
            git('add', '.'); git('commit', '-qm', 'base')
            before = git('rev-parse', 'HEAD')
            source.unlink()
            (root / 'docs').mkdir()
            for i in range(305):
                (root / f'docs/{i}.md').write_text('documentation')
            git('add', '.'); git('commit', '-qm', 'delete source and update docs')
            after = git('rev-parse', 'HEAD')
            for name, event in [('push', dict(ref='refs/heads/main', before=before, after=after)),
                ('pull_request', {'pull_request': {'base': {'sha': before}, 'head': {'sha': after}}})]:
                paths = scope.changed_paths(name, event, after, git)
                self.assertEqual(len(paths), 306)
                self.assertTrue(scope.classify(paths)['oci'])

    def test_missing_commits_and_checkout_mismatch_fail(self):
        sha = 'a' * 40
        event = dict(ref='refs/heads/main', before='0' * 40, after=sha)
        with self.assertRaises(ValueError):
            scope.changed_paths('push', event, sha, lambda *args: sha)
        event['before'] = 'b' * 40
        with self.assertRaises(ValueError):
            scope.changed_paths('push', event, sha, lambda *args: 'c' * 40)
        def failed(*args):
            if args[0] == 'rev-parse': return sha
            raise subprocess.CalledProcessError(1, 'git')
        with self.assertRaises(subprocess.CalledProcessError):
            scope.changed_paths('push', event, sha, failed)


class ResultTests(unittest.TestCase):
    def needs(self):
        return {'scope': {'result': 'success', 'outputs': {'app': 'true', 'infrastructure': 'false', 'data': 'false'}},
                'app': {'result': 'success'}, 'infrastructure': {'result': 'skipped'}, 'data': {'result': 'skipped'}}

    def test_only_intentionally_unselected_checks_may_be_skipped(self):
        check_result.verify(self.needs())
        for result in ('failure', 'cancelled', 'skipped'):
            needs = self.needs(); needs['app']['result'] = result
            with self.assertRaises(ValueError): check_result.verify(needs)

    def test_scope_failure_or_missing_output_cannot_report_success(self):
        needs = self.needs(); needs['scope']['result'] = 'failure'
        with self.assertRaises(ValueError): check_result.verify(needs)
        needs = self.needs(); needs['scope']['outputs']['app'] = ''
        with self.assertRaises(ValueError): check_result.verify(needs)


if __name__ == '__main__':
    unittest.main()
