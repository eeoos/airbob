"""Find the main test summary to compare a PR's tests with.

The app job uploads `test-summary-<sha>` on main pushes too. When the PR base commit's run skipped
the app job (for example a documentation-only push), the nearest earlier first-parent main commit
with a summary is used. Runs in pr-report.yml, which may read artifacts of other runs, and writes
found/name/run-id/sha to $GITHUB_OUTPUT for actions/download-artifact.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import urllib.parse

import pr_comment
import pr_report
import scope


def candidate_commits(base, read_git=scope.git, limit=pr_report.BASELINE_SEARCH_LIMIT):
    scope.require_sha(base)
    return [sha for sha in read_git('rev-list', '--first-parent', f'--max-count={limit}', base).split() if sha]


def find_baseline(request, repository, commits):
    """The newest main-run artifact among `commits`, or None."""
    for sha in commits:
        name = f'test-summary-{sha}'
        found = request('GET', f'{pr_comment.API}/repos/{repository}/actions/artifacts?'
                        + urllib.parse.urlencode({'name': name, 'per_page': 10}))
        for artifact in found.get('artifacts', []):
            run = artifact.get('workflow_run') or {}
            if artifact.get('name') == name and not artifact.get('expired') \
                    and run.get('head_branch') == 'main' and run.get('head_sha') == sha:
                return dict(name=name, run_id=run['id'], sha=sha)
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--facts', required=True, help='facts.json of the PR report artifact')
    parser.add_argument('--repository', required=True)
    args = parser.parse_args()
    result = None
    try:
        facts = json.loads(Path(args.facts).read_text())
        commits = candidate_commits(facts.get('base'))
        result = find_baseline(pr_comment.github(os.environ['GITHUB_TOKEN']), args.repository, commits)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f'No baseline: {type(error).__name__}: {error}')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f"found={'true' if result else 'false'}\n")
        if result:
            output.write(f"name={result['name']}\nrun-id={result['run_id']}\nsha={result['sha']}\n")
    print(json.dumps(result))


if __name__ == '__main__':
    main()
