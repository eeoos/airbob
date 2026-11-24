import json
from pathlib import Path
import tempfile
import unittest

import pr_baseline
import pr_comment
import pr_report
from test_contracts import TempRepo

REPO = 'eeoos/airbob'


def summary(classes, module='core/core-api', task='test'):
    return dict(schema=1, modules_without_tests=[], failed=[], suites=[dict(
        module=module, task=task, tests=sum(classes.values()), failures=0, errors=0, skipped=0, time=1.0,
        classes={name: dict(tests=count, failures=0, skipped=0, time=0.1) for name, count in classes.items()})])


def artifact(sha, branch='main', expired=False, run=7):
    return {'name': f'test-summary-{sha}', 'expired': expired,
            'workflow_run': {'id': run, 'head_branch': branch, 'head_sha': sha}}


class BaselineSearchTests(unittest.TestCase):
    def test_walks_first_parent_history_from_the_base(self):
        with tempfile.TemporaryDirectory() as directory:
            repo = TempRepo(directory)
            repo.write('a.txt', '1')
            first = repo.commit('first')
            repo.write('a.txt', '2')
            second = repo.commit('second')
            self.assertEqual(pr_baseline.candidate_commits(second, repo.git), [second, first])
            self.assertEqual(pr_baseline.candidate_commits(second, repo.git, limit=1), [second])
        with self.assertRaises(ValueError):
            pr_baseline.candidate_commits('not-a-sha')

    def test_finds_the_newest_unexpired_main_artifact(self):
        commits = ['a' * 40, 'b' * 40, 'c' * 40, 'd' * 40]
        published = {commits[1]: [artifact(commits[1], branch='feature')],
                     commits[2]: [artifact(commits[2], expired=True)],
                     commits[3]: [artifact(commits[3], run=99)]}
        requested = []

        def request(method, url, body=None):
            requested.append(url)
            sha = url.split('test-summary-')[1][:40]
            return {'artifacts': published.get(sha, [])}

        self.assertEqual(pr_baseline.find_baseline(request, REPO, commits),
                         dict(name=f'test-summary-{commits[3]}', run_id=99, sha=commits[3]))
        self.assertEqual(len(requested), 4)
        self.assertTrue(requested[0].startswith(f'{pr_comment.API}/repos/{REPO}/actions/artifacts?name=test-summary-'))
        self.assertIsNone(pr_baseline.find_baseline(lambda *a, **k: {'artifacts': []}, REPO, commits))


class DeltaTests(unittest.TestCase):
    def facts(self, tests, base='b' * 40):
        return dict(base=base, tests=tests)

    def test_counts_module_and_class_changes(self):
        head = summary({'a.KeptTest': 3, 'a.ShrunkTest': 1, 'a.NewTest': 2})
        base = summary({'a.KeptTest': 3, 'a.ShrunkTest': 4, 'a.GoneTest': 5})
        delta = pr_report.test_delta(head, base)
        self.assertEqual((delta['before'], delta['after']), (12, 6))
        self.assertEqual(delta['removed_classes'], ['a.GoneTest'])
        self.assertEqual(delta['added_classes'], ['a.NewTest'])
        self.assertEqual(delta['shrunk_classes'], [dict(name='a.ShrunkTest', before=4, after=1)])

        lines = '\n'.join(pr_report.render_test_delta(self.facts(head), 'b' * 40, base, 30))
        self.assertIn('기준: main `bbbbbbbb` · 테스트 12 → 6 (-6) ⚠️', lines)
        self.assertIn('| core/core-api | test | 12 | 6 | -6 ⚠️ |', lines)
        self.assertIn('<summary>⚠️ 사라진 테스트 클래스 1개</summary>', lines)
        self.assertIn('- `a.ShrunkTest` 4 → 1', lines)
        self.assertIn('<summary>새 테스트 클래스 1개</summary>', lines)

    def test_unchanged_tests_and_an_older_baseline_are_described(self):
        tests = summary({'a.KeptTest': 3})
        lines = '\n'.join(pr_report.render_test_delta(self.facts(tests), 'c' * 40, tests, 30))
        self.assertIn('기준: main `cccccccc` (PR base `bbbbbbbb`에는 요약이 없어 가장 가까운 이전 main 커밋) · 테스트 3 → 3 (±0)', lines)
        self.assertNotIn('| 모듈 |', lines)
        self.assertNotIn('⚠️', lines)

    def test_missing_summaries_are_explained(self):
        self.assertIn('이 PR의 테스트 요약이 없어 비교하지 않았습니다.',
                      pr_report.render_test_delta(self.facts(None), 'c' * 40, summary({}), 30))
        self.assertIn('main 기준 테스트 요약을 찾지 못했습니다(PR base부터 main 커밋 30개 확인).',
                      pr_report.render_test_delta(self.facts(summary({'a.T': 1})), '', None, 30))

    def test_section_goes_before_the_contract_signals(self):
        body = pr_report.MARKER + '\n### 테스트\nx\n\n### 계약 신호\ny\n'
        self.assertEqual(pr_report.insert_section(body, ['### main 대비 테스트 증감', 'z']),
                         pr_report.MARKER + '\n### 테스트\nx\n\n### main 대비 테스트 증감\nz\n\n### 계약 신호\ny\n')
        self.assertTrue(pr_report.insert_section('no anchor\n', ['s']).endswith('no anchor\n\ns\n\n'))


class CommentDeltaTests(unittest.TestCase):
    BODY = pr_report.MARKER + '\n### 테스트\nx\n\n### 계약 신호\ny\n'

    def artifact_dir(self, facts):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        (Path(directory.name) / 'facts.json').write_text(json.dumps(facts))
        return directory.name

    def test_baseline_comparison_is_inserted(self):
        directory = self.artifact_dir(dict(base='b' * 40, tests=summary({'a.T': 2})))
        baseline = Path(directory) / 'baseline.json'
        baseline.write_text(json.dumps(summary({'a.T': 1})))
        body = pr_comment.add_test_delta(self.BODY, directory, str(baseline), 'b' * 40)
        self.assertIn('### main 대비 테스트 증감\n기준: main `bbbbbbbb` · 테스트 1 → 2 (+1)', body)
        self.assertLess(body.index('main 대비'), body.index('### 계약 신호'))

    def test_a_broken_comparison_is_reported_without_blocking_the_comment(self):
        directory = self.artifact_dir(dict(base='b' * 40, tests=summary({'a.T': 2})))
        baseline = Path(directory) / 'baseline.json'
        baseline.write_text('{}')
        body = pr_comment.add_test_delta(self.BODY, directory, str(baseline), 'not-a-sha')
        self.assertIn('⚠️ 비교 실패: ValueError', body)
        self.assertTrue(body.startswith(pr_report.MARKER))


if __name__ == '__main__':
    unittest.main()
