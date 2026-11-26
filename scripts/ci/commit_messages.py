"""Commit subjects of a pull request follow `<type>: <summary>` with Korean in the summary.

The rule is the "커밋 메시지" section of docs/development/coding-convention.md. Merge commits are not checked.
CI passes the pull request's base and head commits; run it locally with the same environment variables.
"""
import os
import re
import subprocess
import sys

TYPES = ('feat', 'fix', 'refactor', 'docs', 'test', 'chore', 'perf', 'style', 'build', 'ci', 'revert')
SUBJECT = re.compile(r'([a-z]+): (\S.*)')
HANGUL = re.compile('[가-힣]')


def problem(subject):
    """Why the subject breaks the rule, or None when it follows it."""
    match = SUBJECT.fullmatch(subject)
    if match is None:
        return 'expected "<type>: <summary>"'
    if match.group(1) not in TYPES:
        return f'unknown type "{match.group(1)}"'
    if not HANGUL.search(match.group(2)):
        return 'the summary has no Korean'
    return None


def commits(base, head, cwd='.'):
    """(sha, subject) of the non-merge commits in base..head, oldest first."""
    log = subprocess.check_output(
        ['git', '-C', str(cwd), 'log', '--no-merges', '--reverse', '--format=%H%x00%s', f'{base}..{head}'],
        encoding='utf-8', timeout=60)
    return [tuple(line.split('\0', 1)) for line in log.splitlines()]


def violations(base, head, cwd='.'):
    return [(sha, subject, reason) for sha, subject in commits(base, head, cwd) if (reason := problem(subject))]


def main(env=os.environ, cwd='.'):
    found = violations(env['BASE_SHA'], env.get('HEAD_SHA', 'HEAD'), cwd)
    if not found:
        print('Every commit subject follows "<type>: <summary>" with Korean in the summary.')
        return 0
    for sha, subject, reason in found:
        print(f'{sha[:8]} {subject}  <- {reason}')
    print(f'Write "<type>: <summary>" with a type from {", ".join(TYPES)}, no scope or "!", and Korean in the summary.')
    print('Reword these commits and force-push. Rule: docs/development/coding-convention.md (커밋 메시지).')
    return 1


if __name__ == '__main__':
    sys.exit(main())
