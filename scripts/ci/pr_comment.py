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
    return int(number), pr_report.fit(body)


def add_test_delta(body, directory, baseline=None, baseline_sha='', status='found', searched=0, downloaded='success'):
    """Insert the main comparison; a failure is reported in the comment instead of blocking it.

    `status` and `searched` come from pr_baseline.py; an empty status means that step did not finish.
    `downloaded` is the outcome of the download step: a failed download may leave a partial file behind.
    """
    try:
        facts = json.loads((Path(directory) / 'facts.json').read_text())
        summary = None
        if status == 'found':
            scope.require_sha(baseline_sha)
            if downloaded == 'success' and baseline and Path(baseline).is_file():
                summary = json.loads(Path(baseline).read_text())
            else:
                status = 'download_failed'
        lines = pr_report.render_test_delta(facts, baseline_sha, summary, status or 'lookup_failed', searched)
    except Exception as error:
        lines = ['### main 대비 테스트 증감', f'{pr_report.WARN} 비교 실패: {type(error).__name__}: {pr_report.first_line(str(error))}']
    return pr_report.fit(pr_report.insert_section(body, lines))


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
    parser.add_argument('--baseline', help='main test-summary.json found by pr_baseline.py (optional)')
    parser.add_argument('--baseline-sha', default='', help='main commit of --baseline')
    parser.add_argument('--baseline-status', default='', help='status written by pr_baseline.py')
    parser.add_argument('--baseline-searched', default='0', help='main commits pr_baseline.py looked up')
    parser.add_argument('--baseline-download', default='', help='outcome of the baseline download step')
    args = parser.parse_args()
    number, body = load_report(args.dir)
    searched = int(args.baseline_searched) if args.baseline_searched.isdigit() else 0
    body = add_test_delta(body, args.dir, args.baseline, args.baseline_sha, args.baseline_status, searched,
                          args.baseline_download)
    print(publish(github(os.environ['GITHUB_TOKEN']), args.repository, number, args.head_sha, body))


if __name__ == '__main__':
    main()
