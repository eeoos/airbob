import contextlib
import io
import json
from pathlib import Path
import re
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock

import claude_review
import pr_report
from test_contracts import TempRepo

RULE = '---\npaths:\n  - "scripts/**/*"\n---\n\n# CI rule\n'
OTHER_RULE = '---\npaths:\n  - "core/**/*"\n---\n\n# Core rule\n'


def message(text='{"summary": "지적 없음"}', stop_reason='end_turn', **usage):
    counts = dict(input_tokens=1000, output_tokens=200, cache_creation_input_tokens=None, cache_read_input_tokens=None)
    counts.update(usage)
    return SimpleNamespace(stop_reason=stop_reason, stop_details=None, model='claude-opus-5-5',
                           content=[SimpleNamespace(type='thinking'), SimpleNamespace(type='text', text=text)],
                           usage=SimpleNamespace(**counts))


class RequestTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.repo = TempRepo(directory.name)
        self.repo.write(claude_review.GUIDE, '# 리뷰 기준\n')
        self.repo.write('CLAUDE.md', '# Project\n')
        self.repo.write('.claude/rules/ci.md', RULE)
        self.repo.write('.claude/rules/core.md', OTHER_RULE)
        self.repo.write('scripts/old.py', 'old\n')
        self.repo.write('scripts/kept.py', 'a = 1\n')
        self.base = self.repo.commit('base')
        self.reader = pr_report.Repo(directory.name)

    def test_the_request_holds_the_criteria_matching_rules_and_changed_files(self):
        self.repo.write('scripts/kept.py', 'a = 2\nfence = "```"\n')
        (self.repo.root / 'scripts/old.py').unlink()
        head = self.repo.commit('change scripts')

        text, omitted = claude_review.build_request(self.reader, self.base, head, 'PR 제목', 'PR 설명', criteria=self.base)

        self.assertIn('# 리뷰 기준 (docs/development/code-review.md)\n\n# 리뷰 기준', text)
        self.assertIn('# 프로젝트 규칙 (CLAUDE.md)', text)
        self.assertIn('(.claude/rules/ci.md)', text)
        self.assertNotIn('core.md', text)
        self.assertIn('제목: PR 제목\n\nPR 설명', text)
        self.assertIn('change scripts', text)
        self.assertIn('-a = 1\n+a = 2', text)
        # The changed file is sent in full with a fence longer than any it contains; the deleted one is not.
        self.assertIn('## scripts/kept.py\n\n````\na = 2\nfence = "```"\n````', text)
        self.assertNotIn('## scripts/old.py', text)
        self.assertEqual(omitted, [])

    def test_large_or_binary_files_are_left_to_the_diff(self):
        self.repo.write('scripts/big.py', 'x' * (claude_review.FILE_LIMIT + 1))
        (self.repo.root / 'scripts/data.bin').write_bytes(b'\xff\xfe\x00binary')
        # ASCII as UTF-16 decodes as UTF-8 but carries NUL bytes, so only the NUL check catches it.
        (self.repo.root / 'scripts/utf16.txt').write_bytes('AB'.encode('utf-16-le'))
        head = self.repo.commit('add files')

        text, omitted = claude_review.build_request(self.reader, self.base, head, criteria=self.base)

        self.assertEqual(sorted(omitted), ['scripts/big.py', 'scripts/data.bin', 'scripts/utf16.txt'])
        self.assertIn('전체 내용을 싣지 않은 파일(크기 한도 또는 바이너리, diff만 있음): ', text)
        self.assertIn('제목: (없음)\n\n(설명 없음)', text)

    def test_criteria_and_rules_come_from_the_criteria_commit(self):
        # Neither the PR head nor a base branch the PR stacks on can weaken what the PR is reviewed against.
        self.repo.write(claude_review.GUIDE, '# 약해진 기준\n')
        self.repo.write('.claude/rules/ci.md', '---\npaths:\n  - "nothing/**"\n---\n')
        stacked_base = self.repo.commit('a base branch that weakens the rules')
        self.repo.write('scripts/kept.py', 'a = 2\n')
        self.repo.write('CLAUDE.md', '# Weakened\n')
        head = self.repo.commit('weaken more and change a script')

        text, _ = claude_review.build_request(self.reader, stacked_base, head, criteria=self.base)

        self.assertIn('# 리뷰 기준 (docs/development/code-review.md)\n\n# 리뷰 기준\n', text)
        self.assertIn('# 프로젝트 규칙 (CLAUDE.md)\n\n# Project\n', text)
        self.assertIn('(.claude/rules/ci.md)\n\n' + RULE, text)

    def test_criteria_the_pr_adds_are_not_used(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        repo = TempRepo(directory.name)
        repo.write('scripts/a.py', 'a\n')
        main = repo.commit('main without criteria')
        repo.write(claude_review.GUIDE, '# 새 기준\n')
        head = repo.commit('add the guide')

        text, _ = claude_review.build_request(pr_report.Repo(directory.name), main, head, criteria=main)

        self.assertIn('# 리뷰 기준 (docs/development/code-review.md)\n\n(없음)', text)
        self.assertIn('# 프로젝트 규칙 (CLAUDE.md)\n\n(없음)', text)

    def test_a_change_beyond_the_diff_limit_is_not_reviewed(self):
        self.repo.write('scripts/big.py', 'x\n' * claude_review.DIFF_LIMIT)
        head = self.repo.commit('huge')
        with self.assertRaises(claude_review.ReviewSkipped):
            claude_review.build_request(self.reader, self.base, head, criteria=self.base)

    def test_changed_files_beyond_the_request_budget_are_left_to_the_diff(self):
        self.repo.write('scripts/kept.py', 'b = 2\n' * 1000)
        head = self.repo.commit('long file')
        text, omitted = claude_review.build_request(self.reader, self.base, head, criteria=self.base)
        self.assertEqual(omitted, [])

        # A smaller budget leaves the file to the diff but still reviews the change within the limit.
        limit = len(claude_review.SYSTEM) + len(text) - 3000
        with mock.patch.object(claude_review, 'REQUEST_LIMIT', limit):
            text, omitted = claude_review.build_request(self.reader, self.base, head, criteria=self.base)
        self.assertEqual(omitted, ['scripts/kept.py'])
        self.assertLessEqual(len(claude_review.SYSTEM) + len(text), limit)
        self.assertIn('+b = 2', text)

    def test_deleted_files_take_no_room_in_the_budget(self):
        # Deleted paths are neither sent nor listed, so many of them must not push a fitting request out.
        for index in range(300):
            self.repo.write(f'scripts/deleted/{"long-name-" * 5}{index}.py', '')
        base = self.repo.commit('many empty files')
        for index in range(300):
            (self.repo.root / f'scripts/deleted/{"long-name-" * 5}{index}.py').unlink()
        head = self.repo.commit('delete them')
        text, _ = claude_review.build_request(self.reader, base, head, criteria=base)

        # The reserve is exact: a request that fits the limit to the character is reviewed, one more is not.
        size = len(claude_review.SYSTEM) + len(text)
        with mock.patch.object(claude_review, 'REQUEST_LIMIT', size):
            self.assertEqual(claude_review.build_request(self.reader, base, head, criteria=base)[0], text)
        with mock.patch.object(claude_review, 'REQUEST_LIMIT', size - 1), self.assertRaises(claude_review.ReviewSkipped):
            claude_review.build_request(self.reader, base, head, criteria=base)

    def test_a_request_beyond_the_total_limit_is_not_sent(self):
        self.repo.write('scripts/kept.py', 'a = 2\n')
        head = self.repo.commit('small change')
        with mock.patch.object(claude_review, 'REQUEST_LIMIT', 100), self.assertRaises(claude_review.ReviewSkipped):
            claude_review.build_request(self.reader, self.base, head, criteria=self.base)


class ResponseTests(unittest.TestCase):
    def test_the_summary_comes_from_the_structured_output(self):
        self.assertEqual(claude_review.summary_of(message('{"summary": "  | 파일 | 심각도 |  "}')), '| 파일 | 심각도 |')

    def test_an_answer_continued_by_a_fallback_model_is_joined(self):
        continued = message('{"summary": "앞부분')
        continued.content += [SimpleNamespace(type='fallback'), SimpleNamespace(type='text', text=' 뒷부분"}')]
        self.assertEqual(claude_review.summary_of(continued), '앞부분 뒷부분')

    def test_a_response_without_a_summary_fails_the_run(self):
        for bad in (message(stop_reason='refusal'), message(stop_reason='max_tokens'),
                    message('{"summary": ""}'), SimpleNamespace(stop_reason='end_turn', content=[])):
            with self.assertRaises(RuntimeError):
                claude_review.summary_of(bad)

    def test_one_request_with_structured_output_effort_and_fallback(self):
        calls = []

        class Stream:
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def get_final_message(self):
                return message()

        def stream(**kwargs):
            calls.append(kwargs)
            return Stream()

        client = SimpleNamespace(beta=SimpleNamespace(messages=SimpleNamespace(stream=stream)))
        response = claude_review.review('변경 내용', client)

        self.assertEqual(claude_review.summary_of(response), '지적 없음')
        self.assertEqual(len(calls), 1)
        request = calls[0]
        self.assertEqual((request['model'], request['fallbacks']), ('claude-opus-5-5', 'default'))
        self.assertEqual(request['output_config']['effort'], 'xhigh')
        self.assertEqual(request['max_tokens'], 64000)
        self.assertEqual(request['output_config']['format']['schema']['required'], ['summary'])
        self.assertEqual(request['messages'], [{'role': 'user', 'content': '변경 내용'}])
        self.assertNotIn('tools', request)

    def test_cost_uses_the_list_prices(self):
        usage = message(input_tokens=100_000, output_tokens=10_000, cache_read_input_tokens=50_000).usage
        # 100,000 × $4 + 10,000 × $20 + 50,000 × $0.2 per million tokens
        self.assertEqual(claude_review.usage_details(usage),
                         '토큰: claude-opus-5-5 입력 100,000 / 출력 10,000 · 추정 비용 $0.61')

    def test_every_attempt_of_a_fallback_is_shown_and_not_priced(self):
        attempt = dict(cache_creation_input_tokens=0, cache_read_input_tokens=0)
        usage = message(input_tokens=2000, output_tokens=300).usage
        usage.iterations = [
            SimpleNamespace(type='message', model='claude-opus-5-5', input_tokens=1000, output_tokens=100, **attempt),
            SimpleNamespace(type='fallback_message', model='claude-opus-4-8', input_tokens=2000, output_tokens=300,
                            **attempt)]
        self.assertEqual(claude_review.usage_details(usage),
                         '토큰: claude-opus-5-5 입력 1,000 / 출력 100, claude-opus-4-8 입력 2,000 / 출력 300'
                         ' · 대체 모델이 실행돼 비용은 Console에서 확인')


class CommentTests(unittest.TestCase):
    def test_the_comment_starts_with_the_marker_and_names_the_commit_and_run(self):
        body = claude_review.comment_body('지적 없음', 'a' * 40, '123/1', '모델: m')
        self.assertTrue(body.startswith(claude_review.MARKER + '\n지적 없음\n'))
        self.assertIn(f"<sub>리뷰한 커밋: {'a' * 40} · 실행: 123/1 · 모델: m · 기준: {claude_review.GUIDE}</sub>", body)

    def test_a_long_review_is_cut_but_keeps_the_footer(self):
        body = claude_review.comment_body('x' * (pr_report.COMMENT_LIMIT + 10), 'a' * 40, '1/1')
        self.assertLessEqual(len(body), pr_report.COMMENT_LIMIT)
        self.assertTrue(body.rstrip().endswith('</sub>'))
        self.assertIn('댓글 길이 한도를 넘어 잘랐습니다', body)


class IdentityTests(unittest.TestCase):
    def test_the_github_oidc_token_is_handed_to_the_sdk(self):
        environ = {'ACTIONS_ID_TOKEN_REQUEST_URL': 'https://token.example/x?api-version=2.0',
                   'ACTIONS_ID_TOKEN_REQUEST_TOKEN': 'request-token'}
        response = mock.MagicMock()
        response.__enter__.return_value = response
        response.read.return_value = json.dumps({'value': 'jwt'}).encode()
        with mock.patch('urllib.request.urlopen', return_value=response) as urlopen:
            claude_review.use_github_identity(environ)
        request = urlopen.call_args.args[0]
        self.assertEqual(request.full_url, 'https://token.example/x?api-version=2.0&audience=https%3A%2F%2Fapi.anthropic.com')
        self.assertEqual(request.get_header('Authorization'), 'Bearer request-token')
        self.assertEqual(environ['ANTHROPIC_IDENTITY_TOKEN'], 'jwt')

    def test_outside_github_actions_nothing_changes(self):
        environ = {}
        claude_review.use_github_identity(environ)
        self.assertEqual(environ, {})

    def test_the_pull_request_text_comes_from_the_api(self):
        request = mock.Mock(return_value={'title': '제목', 'body': None})
        self.assertEqual(claude_review.pull_request_text(request, 'eeoos/airbob', '223'), ('제목', ''))
        request.assert_called_once_with('GET', 'https://api.github.com/repos/eeoos/airbob/pulls/223')
        # A local dry run has no token and no API call.
        self.assertEqual(claude_review.pull_request_text(None, 'eeoos/airbob', '223'), ('', ''))


class MainTests(unittest.TestCase):
    def run_main(self, response, post, log):
        argv = ['claude_review.py', '--base', 'a' * 40, '--head', 'b' * 40, '--criteria', 'c' * 40,
                '--pr', '223', '--repository', 'eeoos/airbob']
        with mock.patch('sys.argv', argv), mock.patch.dict('os.environ', {'GITHUB_TOKEN': 'token'}, clear=True), \
                mock.patch.object(pr_report, 'Repo'), \
                mock.patch.object(claude_review, 'build_request', return_value=('요청', [])) as build, \
                mock.patch.object(claude_review, 'pull_request_text', return_value=('제목', '본문')), \
                mock.patch.object(claude_review, 'review', return_value=response), \
                mock.patch.object(claude_review.pr_comment, 'github', return_value=post), \
                contextlib.redirect_stdout(log):
            claude_review.main()
        self.assertEqual(build.call_args.kwargs, {'criteria': 'c' * 40})

    def test_a_failed_post_leaves_the_billed_review_in_the_log_with_workflow_commands_stopped(self):
        log = io.StringIO()
        review = message('{"summary": "리뷰 본문\\n::add-mask::x"}')
        with self.assertRaises(OSError):
            self.run_main(review, mock.Mock(side_effect=OSError('HTTP 502')), log)
        self.assertRegex(log.getvalue(), re.compile(
            rf'::stop-commands::([0-9a-f]{{32}})\n{re.escape(claude_review.MARKER)}\n리뷰 본문\n::add-mask::x\n::\1::\n'))

    def test_an_unusable_response_still_logs_its_tokens_and_text(self):
        log = io.StringIO()
        post = mock.Mock()
        with self.assertRaises(RuntimeError):
            self.run_main(message('{"summary": "잘린', stop_reason='max_tokens'), post, log)
        self.assertIn('입력 1,000 / 출력 200', log.getvalue())
        self.assertIn('Unusable response (stop_reason max_tokens):\n{"summary": "잘린\n', log.getvalue())
        post.assert_not_called()

    def test_a_refused_partial_answer_is_not_logged(self):
        log = io.StringIO()
        with self.assertRaisesRegex(RuntimeError, 'declined'):
            self.run_main(message('{"summary": "거절 전 부분', stop_reason='refusal'), mock.Mock(), log)
        self.assertIn('입력 1,000 / 출력 200', log.getvalue())
        self.assertNotIn('거절 전 부분', log.getvalue())

    def test_an_unreadable_usage_does_not_stop_the_comment(self):
        post = mock.Mock()
        self.run_main(message('{"summary": "리뷰 본문"}', input_tokens=None), post, io.StringIO())
        method, url, payload = post.call_args.args
        self.assertEqual((method, url), ('POST', 'https://api.github.com/repos/eeoos/airbob/issues/223/comments'))
        self.assertIn('리뷰 본문', payload['body'])
        self.assertIn('토큰 정보를 읽지 못함', payload['body'])


if __name__ == '__main__':
    unittest.main()
