import json
from pathlib import Path
import tempfile
import unittest
import urllib.parse

import pr_baseline
import pr_comment
import pr_report
from test_contracts import TempRepo

REPO = 'eeoos/airbob'
REPO_ID = 42


def summary(classes, module='core/core-api', task='test', build_outcome='success'):
    return dict(schema=1, modules_without_tests=[], failed=[], build_outcome=build_outcome, suites=[dict(
        module=module, task=task, tests=sum(classes.values()), failures=0, errors=0, skipped=0, time=1.0,
        classes={name: dict(tests=count, failures=0, skipped=0, time=0.1) for name, count in classes.items()})])


def artifact(sha, branch='main', expired=False, run=7, repository_id=REPO_ID, head_repository_id=REPO_ID):
    return {'name': f'test-summary-{sha}', 'expired': expired,
            'workflow_run': {'id': run, 'head_branch': branch, 'head_sha': sha,
                             'repository_id': repository_id, 'head_repository_id': head_repository_id}}


def github_with(published):
    """A fake GitHub API answering artifact searches from {sha: [artifact, ...]} in pages; records the URLs."""
    requested = []

    def request(method, url, body=None):
        requested.append(url)
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
        sha = query['name'][0][len('test-summary-'):]
        size, page = int(query['per_page'][0]), int(query['page'][0])
        return {'artifacts': published.get(sha, [])[(page - 1) * size:page * size]}
    return request, requested


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

    def test_finds_the_newest_unexpired_main_artifact_of_this_repository(self):
        commits = ['a' * 40, 'b' * 40, 'c' * 40, 'd' * 40, 'e' * 40]
        request, requested = github_with({
            commits[1]: [artifact(commits[1], branch='feature')],
            commits[2]: [artifact(commits[2], expired=True)],
            commits[3]: [artifact(commits[3], repository_id=7), artifact(commits[3], head_repository_id=7)],
            commits[4]: [artifact(commits[4], run=99)]})

        self.assertEqual(pr_baseline.find_baseline(request, REPO, REPO_ID, commits),
                         (dict(name=f'test-summary-{commits[4]}', run_id=99, sha=commits[4]), 5))
        self.assertEqual(len(requested), 5)
        self.assertTrue(requested[0].startswith(f'{pr_comment.API}/repos/{REPO}/actions/artifacts?name=test-summary-'))
        self.assertIn('per_page=100', requested[0])

    def test_a_valid_artifact_on_a_later_page_is_found(self):
        # A PR run that changes the artifact name can upload many artifacts under a main commit's name.
        sha = 'a' * 40
        request, requested = github_with({sha: [artifact(sha, branch='feature')] * 100 + [artifact(sha, run=101)]})
        self.assertEqual(pr_baseline.find_baseline(request, REPO, REPO_ID, [sha]),
                         (dict(name=f'test-summary-{sha}', run_id=101, sha=sha), 1))
        self.assertEqual(len(requested), 2)
        self.assertIn('page=2', requested[1])

    def test_nothing_found_reports_how_many_commits_were_looked_up(self):
        request, _ = github_with({})
        self.assertEqual(pr_baseline.find_baseline(request, REPO, REPO_ID, ['a' * 40, 'b' * 40]), (None, 2))


class LocateTests(unittest.TestCase):
    BASE = 'b' * 40

    def facts_file(self, facts):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / 'facts.json'
        path.write_text(json.dumps(facts))
        return path

    def locate(self, facts, base=BASE, published=None, repository_id=str(REPO_ID)):
        request, _ = github_with(published or {})
        history = lambda *args: f"{self.BASE}\n{'a' * 40}\n"
        return pr_baseline.locate(self.facts_file(facts), base, REPO, repository_id, request, history)

    def test_found_and_not_found(self):
        older = 'a' * 40
        self.assertEqual(self.locate(dict(base=self.BASE), published={older: [artifact(older, run=5)]}),
                         dict(status='found', searched=2, name=f'test-summary-{older}', run_id=5, sha=older))
        self.assertEqual(self.locate(dict(base=self.BASE)), dict(status='not_found', searched=2))

    def test_the_artifact_cannot_choose_its_own_base(self):
        self.assertEqual(self.locate(dict(base='c' * 40)), dict(status='base_mismatch', searched=0))

    def test_broken_input_is_a_lookup_failure_not_a_crash(self):
        failed = dict(status='lookup_failed', searched=0)
        self.assertEqual(self.locate([]), failed)
        self.assertEqual(self.locate(dict(base=self.BASE), base=''), failed)
        self.assertEqual(self.locate(dict(base=self.BASE), repository_id=''), failed)

        def broken(method, url, body=None):
            raise OSError('HTTP 502')
        self.assertEqual(pr_baseline.locate(self.facts_file(dict(base=self.BASE)), self.BASE, REPO, str(REPO_ID),
                                            broken, lambda *args: self.BASE), failed)


class DeltaTests(unittest.TestCase):
    def facts(self, tests, base='b' * 40):
        return dict(base=base, tests=tests)

    def render(self, tests, baseline_sha, baseline, status='found', searched=0):
        return '\n'.join(pr_report.render_test_delta(self.facts(tests), baseline_sha, baseline, status, searched))

    def test_counts_module_and_class_changes(self):
        head = summary({'a.KeptTest': 3, 'a.ShrunkTest': 1, 'a.NewTest': 2})
        base = summary({'a.KeptTest': 3, 'a.ShrunkTest': 4, 'a.GoneTest': 5})
        delta = pr_report.test_delta(head, base)
        self.assertEqual((delta['before'], delta['after']), (12, 6))
        self.assertEqual(delta['removed_classes'], ['a.GoneTest'])
        self.assertEqual(delta['added_classes'], ['a.NewTest'])
        self.assertEqual(delta['shrunk_classes'], [dict(name='a.ShrunkTest', before=4, after=1)])

        lines = self.render(head, 'b' * 40, base)
        self.assertIn('기준: main `bbbbbbbb` · 테스트 12 → 6 (-6) ⚠️', lines)
        self.assertIn('| core/core-api | test | 12 | 6 | -6 ⚠️ |', lines)
        self.assertIn('<summary>⚠️ 사라진 테스트 클래스 1개</summary>', lines)
        self.assertIn('- `a.ShrunkTest` 4 → 1', lines)
        self.assertIn('<summary>새 테스트 클래스 1개</summary>', lines)

    def test_unchanged_tests_and_an_older_baseline_are_described(self):
        tests = summary({'a.KeptTest': 3})
        lines = self.render(tests, 'c' * 40, tests)
        self.assertIn('기준: main `cccccccc` (PR base `bbbbbbbb`에는 요약이 없어 가장 가까운 이전 main 커밋) · 테스트 3 → 3 (±0)', lines)
        self.assertNotIn('| 모듈 |', lines)
        self.assertNotIn('⚠️', lines)

    def test_a_build_not_known_to_succeed_is_not_compared(self):
        # A compile failure leaves an empty summary; it must not read as every test being deleted.
        # Summaries from before build_outcome was recorded were also kept after failed builds.
        legacy = summary({})
        del legacy['build_outcome']
        for partial in (summary({}, build_outcome='failure'), legacy):
            lines = self.render(partial, 'b' * 40, summary({'a.T': 100}))
            self.assertIn('⚠️ 이 PR의 빌드 성공이 확인되지 않아(실패 또는 이전 형식 요약) 일부 모듈의 테스트만 담겼을 수 있습니다. '
                          '비교하지 않았습니다.', lines)
            self.assertNotIn('→', lines)
            lines = self.render(summary({'a.T': 100}), 'b' * 40, dict(partial, suites=summary({'a.T': 1})['suites']))
            self.assertIn('⚠️ main `bbbbbbbb`의 빌드 성공이 확인되지 않아(실패 또는 이전 형식 요약) 기준 요약이 불완전할 수 있습니다. '
                          '비교하지 않았습니다.', lines)
            self.assertNotIn('→', lines)

    def test_each_search_status_is_explained(self):
        tests = summary({'a.T': 1})
        self.assertIn('이 PR의 테스트 요약이 없어 비교하지 않았습니다.',
                      pr_report.render_test_delta(self.facts(None), 'c' * 40, tests, 'found'))
        self.assertIn('main 기준 테스트 요약을 찾지 못했습니다(PR base부터 main 커밋 3개 확인).',
                      self.render(tests, '', None, 'not_found', 3))
        self.assertIn('⚠️ 보고서의 PR base(`bbbbbbbb`)가 이 PR의 실제 base와 달라 비교하지 않았습니다.',
                      self.render(tests, '', None, 'base_mismatch'))
        self.assertIn('⚠️ main `cccccccc`의 테스트 요약을 내려받지 못했습니다.',
                      self.render(tests, 'c' * 40, None, 'download_failed'))
        self.assertIn('⚠️ main 기준 테스트 요약을 조회하지 못했습니다. PR report 실행 로그를 보세요.',
                      self.render(tests, '', None, 'lookup_failed'))

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
        body = pr_comment.add_test_delta(self.BODY, directory, str(baseline), 'b' * 40, 'found', 1)
        self.assertIn('### main 대비 테스트 증감\n기준: main `bbbbbbbb` · 테스트 1 → 2 (+1)', body)
        self.assertLess(body.index('main 대비'), body.index('### 계약 신호'))

    def test_search_and_download_failures_are_told_apart(self):
        directory = self.artifact_dir(dict(base='b' * 40, tests=summary({'a.T': 2})))
        missing = str(Path(directory) / 'missing.json')
        self.assertIn('main `bbbbbbbb`의 테스트 요약을 내려받지 못했습니다.',
                      pr_comment.add_test_delta(self.BODY, directory, missing, 'b' * 40, 'found', 1))
        self.assertIn('main 기준 테스트 요약을 조회하지 못했습니다.',
                      pr_comment.add_test_delta(self.BODY, directory, missing, '', '', 0))
        self.assertIn('main 커밋 30개 확인',
                      pr_comment.add_test_delta(self.BODY, directory, missing, '', 'not_found', 30))

    def test_a_file_left_by_a_failed_download_is_not_read(self):
        directory = self.artifact_dir(dict(base='b' * 40, tests=summary({'a.T': 2})))
        baseline = Path(directory) / 'baseline.json'
        baseline.write_text(json.dumps(summary({'a.T': 1})))
        body = pr_comment.add_test_delta(self.BODY, directory, str(baseline), 'b' * 40, 'found', 1, 'failure')
        self.assertIn('main `bbbbbbbb`의 테스트 요약을 내려받지 못했습니다.', body)
        self.assertNotIn('→', body)

    def test_a_broken_comparison_is_reported_without_blocking_the_comment(self):
        directory = self.artifact_dir(dict(base='b' * 40, tests=summary({'a.T': 2})))
        baseline = Path(directory) / 'baseline.json'
        baseline.write_text('{}')
        body = pr_comment.add_test_delta(self.BODY, directory, str(baseline), 'not-a-sha', 'found', 1)
        self.assertIn('⚠️ 비교 실패: ValueError', body)
        self.assertTrue(body.startswith(pr_report.MARKER))


if __name__ == '__main__':
    unittest.main()
