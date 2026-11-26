import contextlib
import io
from pathlib import Path
import subprocess
import tempfile
import unittest

import commit_messages


class SubjectTests(unittest.TestCase):
    def test_every_type_with_a_korean_summary_passes(self):
        for type_ in commit_messages.TYPES:
            with self.subTest(type=type_):
                self.assertIsNone(commit_messages.problem(f'{type_}: 예약 취소 규칙을 고친다'))

    def test_english_identifiers_may_appear_in_a_korean_summary(self):
        self.assertIsNone(commit_messages.problem('docs: CLAUDE.md에 행동 가이드라인 추가'))

    def test_subjects_outside_the_format_fail(self):
        for subject in ('숙소 상세 캐시 무효화 순서 개선', 'Feat: 예약을 추가한다', 'fix(aws): 복원을 기다린다',
                        'feat!: 응답 형식을 바꾼다', 'fix:예약을 고친다', 'fix:  예약을 고친다', 'fix: ',
                        'Revert "feat: 예약을 추가한다"', 'fixup! feat: 예약을 추가한다'):
            with self.subTest(subject=subject):
                self.assertEqual(commit_messages.problem(subject), 'expected "<type>: <summary>"')

    def test_unknown_types_fail(self):
        self.assertEqual(commit_messages.problem('feature: 예약을 추가한다'), 'unknown type "feature"')

    def test_summaries_without_korean_fail(self):
        for subject in ('fix: wait through RDS rebooting during resume', 'docs: CLAUDE.md'):
            with self.subTest(subject=subject):
                self.assertEqual(commit_messages.problem(subject), 'the summary has no Korean')


class RangeTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.git('init', '-q')
        self.git('config', 'user.name', 'CI test')
        self.git('config', 'user.email', 'ci@example.invalid')

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.root), *args], stderr=subprocess.PIPE).decode().strip()

    def commit(self, message):
        self.git('commit', '-q', '--allow-empty', '-m', message)
        return self.git('rev-parse', 'HEAD')

    def test_only_the_pull_request_commits_are_checked_and_merges_are_skipped(self):
        self.commit('chore: 시작한다')
        main = self.git('rev-parse', '--abbrev-ref', 'HEAD')
        self.git('checkout', '-q', '-b', 'feature')
        good = self.commit('feat: 예약을 추가한다')
        bad = self.commit('fix: wait for the database')
        self.git('checkout', '-q', main)
        base = self.commit('Update the base without the rule')
        self.git('checkout', '-q', 'feature')
        self.git('merge', '-q', '--no-ff', '-m', 'Merge branch main into feature', main)
        head = self.commit('Fix the subject later')

        self.assertEqual(commit_messages.commits(base, head, self.root), [
            (good, 'feat: 예약을 추가한다'), (bad, 'fix: wait for the database'), (head, 'Fix the subject later')])
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            code = commit_messages.main({'BASE_SHA': base, 'HEAD_SHA': head}, self.root)
        self.assertEqual(code, 1)
        lines = output.getvalue().splitlines()
        self.assertEqual(lines[:2], [f'{bad[:8]} fix: wait for the database  <- the summary has no Korean',
                                     f'{head[:8]} Fix the subject later  <- expected "<type>: <summary>"'])

    def test_a_range_that_follows_the_rule_passes(self):
        base = self.commit('chore: 시작한다')
        head = self.commit('docs: 규칙을 적는다')
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(commit_messages.main({'BASE_SHA': base, 'HEAD_SHA': head}, self.root), 0)


if __name__ == '__main__':
    unittest.main()
