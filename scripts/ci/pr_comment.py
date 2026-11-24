"""Create or update the single PR report comment from a CI run's pr-report artifact.

Runs in the `workflow_run` workflow, which has a write token. The artifact comes from a pull request
run, so it is treated as data: the PR number, head commit, repository, and marker are checked, and the
report is posted as text. Nothing from the artifact is executed.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

import pr_report
import scope

API = 'https://api.github.com'
BOT = 'github-actions[bot]'
REPOSITORY = re.compile(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+')
PR_NUMBER = re.compile(r'[1-9][0-9]{0,8}')


def github(token):
    def request(method, url, body=None):
        data = None if body is None else json.dumps(body).encode()
        headers = {'Authorization': f'Bearer {token}', 'Accept': 'application/vnd.github+json',
                   'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'airbob-pr-report'}
        if data is not None:
            headers['Content-Type'] = 'application/json'
        with urllib.request.urlopen(urllib.request.Request(url, data=data, method=method, headers=headers),
                                    timeout=30) as response:
            return json.loads(response.read() or b'null')
    return request


def load_report(directory):
    directory = Path(directory)
    number = (directory / 'pr-number').read_text().strip()
    if not PR_NUMBER.fullmatch(number):
        raise ValueError('pr-number is not a pull request number')
    body = (directory / 'report.md').read_text()
    if not body.startswith(pr_report.MARKER):
        raise ValueError('report.md does not start with the report marker')
    if len(body) > pr_report.COMMENT_LIMIT:
        note = '\n\n_보고서가 너무 길어 잘랐습니다._\n'
        body = body[:pr_report.COMMENT_LIMIT - len(note)] + note
    return int(number), body


def publish(request, repository, number, head_sha, body):
    """Returns what happened; posts only for the open same-repository PR whose head is `head_sha`."""
    if not REPOSITORY.fullmatch(repository):
        raise ValueError('invalid repository')
    scope.require_sha(head_sha)
    pull = request('GET', f'{API}/repos/{repository}/pulls/{number}')
    if (pull['head'].get('repo') or {}).get('full_name') != repository:
        return 'skipped: the pull request comes from a fork'
    if pull['head']['sha'] != head_sha:
        return f"skipped: PR #{number} moved to {pull['head']['sha'][:8]}; this report is for {head_sha[:8]}"
    if pull['state'] != 'open':
        return f'skipped: PR #{number} is {pull["state"]}'
    existing, page = None, 1
    while True:
        comments = request('GET', f'{API}/repos/{repository}/issues/{number}/comments?per_page=100&page={page}')
        for comment in comments:
            if comment['user']['login'] == BOT and comment['body'].startswith(pr_report.MARKER):
                existing = comment
        if len(comments) < 100:
            break
        page += 1
    if existing is None:
        request('POST', f'{API}/repos/{repository}/issues/{number}/comments', {'body': body})
        return f'created the report on PR #{number}'
    if existing['body'] == body:
        return f'the report on PR #{number} is already current'
    request('PATCH', f"{API}/repos/{repository}/issues/comments/{existing['id']}", {'body': body})
    return f'updated the report on PR #{number}'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dir', required=True, help='downloaded pr-report artifact')
    parser.add_argument('--repository', required=True)
    parser.add_argument('--head-sha', required=True, help='head commit of the CI run (workflow_run.head_sha)')
    args = parser.parse_args()
    number, body = load_report(args.dir)
    print(publish(github(os.environ['GITHUB_TOKEN']), args.repository, number, args.head_sha, body))


if __name__ == '__main__':
    main()
