"""Commit subjects of a pull request follow `<type>: <summary>` with Korean in the summary and a blank line after.

The rule is the "커밋 메시지" section of docs/development/coding-convention.md. Merge commits are not checked.
CI passes the pull request's base and head commits; run it locally with the same environment variables.
"""
import os
import re
import subprocess
import sys

TYPES = ('feat', 'fix', 'refactor', 'perf', 'test', 'docs', 'style', 'build', 'ci', 'chore', 'revert')
SUBJECT = re.compile(r'([a-z]+): (\S.*)')
HANGUL = re.compile('[가-힣]')


def problem(message):
    """Why the commit message breaks the rule, or None when it follows it.

    The subject is the first line, and the line after it must be blank. Git's %s joins the lines up to the first
    blank line, so without it `git log --oneline` would show a different subject from the one checked here.
    """
    subject, _, rest = message.partition('\n')
    match = SUBJECT.fullmatch(subject)
    if match is None:
        return 'expected "<type>: <summary>"'
    if match.group(1) not in TYPES:
        return f'unknown type "{match.group(1)}"'
    if not HANGUL.search(match.group(2)):
        return 'the summary has no Korean'
    if rest.partition('\n')[0].strip():
        return 'the line after the subject is not blank'
    return None


def commits(base, head, cwd='.'):
    """(sha, message) of the non-merge commits in base..head, oldest first."""
    log = subprocess.check_output(
        ['git', '-C', str(cwd), 'log', '-z', '--no-merges', '--reverse', '--format=%H%n%B', f'{base}..{head}'],
        encoding='utf-8', timeout=60)
    return [entry.partition('\n')[::2] for entry in filter(None, log.split('\0'))]


def violations(base, head, cwd='.'):
    """(sha, subject, reason) of the commits that break the rule."""
    return [(sha, message.partition('\n')[0], reason)
            for sha, message in commits(base, head, cwd) if (reason := problem(message))]


def main(env=os.environ, cwd='.'):
    found = violations(env['BASE_SHA'], env.get('HEAD_SHA', 'HEAD'), cwd)
    if not found:
        print('Every commit subject follows "<type>: <summary>" with Korean in the summary.')
        return 0
    for sha, subject, reason in found:
        print(f'{sha[:8]} {subject}  <- {reason}')
    print(f'Write "<type>: <summary>" on the first line with a type from {", ".join(TYPES)}, no scope or "!", '
          'and Korean in the summary. Leave the line after it blank.')
    print('Reword these commits and force-push. Rule: docs/development/coding-convention.md (커밋 메시지).')
    return 1


if __name__ == '__main__':
    sys.exit(main())
