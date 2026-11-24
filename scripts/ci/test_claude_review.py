import json
from pathlib import Path
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

        text, omitted = claude_review.build_request(self.reader, self.base, head, 'PR 제목', 'PR 설명')

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
        head = self.repo.commit('add files')

        text, omitted = claude_review.build_request(self.reader, self.base, head)

        self.assertEqual(sorted(omitted), ['scripts/big.py', 'scripts/data.bin'])
        self.assertIn('전체 내용을 싣지 않은 파일(크기 한도 또는 바이너리, diff만 있음): ', text)
        self.assertIn('제목: (없음)\n\n(설명 없음)', text)

    def test_a_change_beyond_the_diff_limit_is_not_reviewed(self):
        self.repo.write('scripts/big.py', 'x\n' * claude_review.DIFF_LIMIT)
        head = self.repo.commit('huge')
        with self.assertRaises(claude_review.ReviewSkipped):
            claude_review.build_request(self.reader, self.base, head)


class ResponseTests(unittest.TestCase):
    def test_the_summary_comes_from_the_structured_output(self):
        self.assertEqual(claude_review.summary_of(message('{"summary": "  | 파일 | 심각도 |  "}')), '| 파일 | 심각도 |')

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
        summary, _ = claude_review.review('변경 내용', client)

        self.assertEqual(summary, '지적 없음')
        self.assertEqual(len(calls), 1)
        request = calls[0]
        self.assertEqual((request['model'], request['fallbacks']), ('claude-opus-5-5', 'default'))
        self.assertEqual(request['output_config']['effort'], 'high')
        self.assertEqual(request['output_config']['format']['schema']['required'], ['summary'])
        self.assertEqual(request['messages'], [{'role': 'user', 'content': '변경 내용'}])
        self.assertNotIn('tools', request)

    def test_cost_uses_the_list_prices(self):
        tokens, usd = claude_review.cost(message(input_tokens=100_000, output_tokens=10_000,
                                                 cache_read_input_tokens=50_000).usage)
        self.assertEqual(tokens, dict(input=100_000, output=10_000, cache_write=0, cache_read=50_000))
        self.assertAlmostEqual(usd, 0.4 + 0.2 + 0.01)


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

    def test_the_pull_request_text_comes_from_the_event(self):
        with tempfile.TemporaryDirectory() as directory:
            event = Path(directory) / 'event.json'
            event.write_text(json.dumps({'pull_request': {'title': '제목', 'body': None}}))
            self.assertEqual(claude_review.pull_request_text(str(event)), ('제목', ''))
        self.assertEqual(claude_review.pull_request_text(None), ('', ''))


if __name__ == '__main__':
    unittest.main()
