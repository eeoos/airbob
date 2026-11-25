"""Render an AI review that follows review-schema.json as Markdown.

Both reviewers answer in that schema (Claude through structured outputs, Codex through
`codex exec --output-schema`), so the layout, the order, and the counts come from this code, not from
the model.

Locally: python3 scripts/ci/review_format.py <review.json> [heading]
"""
import json
from pathlib import Path
import sys

SCHEMA_PATH = Path(__file__).with_name('review-schema.json')
SEVERITIES = ('필수', '권장', '참고')


def schema():
    return json.loads(SCHEMA_PATH.read_text(encoding='utf-8'))


def indented(text):
    # Continuation lines are indented so that a multi-line value stays inside its list item.
    return text.strip().replace('\n', '\n  ')


def field(label, value):
    return f'- **{label}:** {indented(value)}'


def render(review, heading):
    findings = sorted(review['findings'], key=lambda finding: SEVERITIES.index(finding['severity']))
    counts = ' · '.join(f'{severity} {sum(f["severity"] == severity for f in findings)}' for severity in SEVERITIES)
    lines = [f'## {heading}', '', f'**{counts}**']
    if review['overview'].strip():
        lines += ['', review['overview'].strip()]
    for number, finding in enumerate(findings, 1):
        certainty = '' if finding['certain'] else ' (확실하지 않음)'
        lines += ['', f'### {number}. {finding["severity"]} · {finding["title"].strip()}{certainty}', '',
                  field('위치', finding['location']), field('문제', finding['problem']),
                  field('근거', finding['evidence']), field('제안', finding['suggestion'])]
    if not findings:
        lines += ['', '지적 없음.']
    for title, items in (('확인한 범위', review['checked']), ('확인하지 못한 것', review['unverified'])):
        if items:
            lines += ['', f'### {title}', ''] + [f'- {indented(item)}' for item in items]
    return '\n'.join(lines) + '\n'


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit('usage: review_format.py <review.json> [heading]')
    review = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    print(render(review, sys.argv[2] if len(sys.argv) == 3 else 'Codex 리뷰'), end='')


if __name__ == '__main__':
    main()
