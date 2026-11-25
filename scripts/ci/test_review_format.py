import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import review_format


def finding(severity, title, certain=True, **fields):
    values = dict(location='scripts/ci/a.py:1', severity=severity, title=title, problem='문제',
                  evidence='근거', suggestion='제안', certain=certain)
    values.update(fields)
    return values


def review(**fields):
    values = dict(overview='', findings=[], checked=[], unverified=[])
    values.update(fields)
    return values


class RenderTests(unittest.TestCase):
    def test_findings_are_counted_and_ordered_by_severity(self):
        text = review_format.render(review(overview=' 요약 ', findings=[
            finding('참고', '셋째'), finding('필수', '첫째'), finding('권장', '둘째')]), 'Claude 리뷰')
        self.assertTrue(text.startswith('## Claude 리뷰\n\n**필수 1 · 권장 1 · 참고 1**\n\n요약\n'))
        self.assertLess(text.index('### 1. 필수 · 첫째'), text.index('### 2. 권장 · 둘째'))
        self.assertLess(text.index('### 2. 권장 · 둘째'), text.index('### 3. 참고 · 셋째'))

    def test_each_finding_has_the_same_fields_in_the_same_order(self):
        text = review_format.render(review(findings=[finding('권장', '제목', certain=False, problem='첫 줄\n- 둘째 줄')]),
                                    'Codex 리뷰')
        self.assertIn('### 1. 권장 · 제목 (확실하지 않음)\n\n'
                      '- **위치:** scripts/ci/a.py:1\n'
                      '- **문제:** 첫 줄\n  - 둘째 줄\n'
                      '- **근거:** 근거\n'
                      '- **제안:** 제안\n', text)

    def test_an_empty_review_says_so_and_lists_only_what_it_has(self):
        text = review_format.render(review(checked=['요청 예산']), 'Claude 리뷰')
        self.assertIn('**필수 0 · 권장 0 · 참고 0**\n\n지적 없음.\n\n### 확인한 범위\n\n- 요청 예산\n', text)
        self.assertNotIn('확인하지 못한 것', text)

    def test_the_command_renders_a_saved_review(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'review.json'
            path.write_text(json.dumps(review(findings=[finding('필수', '제목')]), ensure_ascii=False), encoding='utf-8')
            out = io.StringIO()
            with mock.patch('sys.argv', ['review_format.py', str(path)]), contextlib.redirect_stdout(out):
                review_format.main()
        self.assertTrue(out.getvalue().startswith('## Codex 리뷰\n\n**필수 1 · 권장 0 · 참고 0**'))


class SchemaTests(unittest.TestCase):
    def test_every_object_is_strict_for_both_reviewers(self):
        # Anthropic structured outputs and OpenAI strict mode (codex --output-schema) both need closed objects;
        # OpenAI also needs every property listed as required.
        def objects(node):
            if isinstance(node, dict):
                if node.get('type') == 'object':
                    yield node
                for value in node.values():
                    yield from objects(value)

        found = list(objects(review_format.schema()))
        self.assertEqual(len(found), 2)
        for node in found:
            self.assertIs(node['additionalProperties'], False)
            self.assertEqual(sorted(node['required']), sorted(node['properties']))

    def test_the_severities_match_the_criteria(self):
        severity = review_format.schema()['properties']['findings']['items']['properties']['severity']
        self.assertEqual(tuple(severity['enum']), review_format.SEVERITIES)


if __name__ == '__main__':
    unittest.main()
