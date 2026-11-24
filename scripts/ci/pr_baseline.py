"""Find the main test summary to compare a PR's tests with.

The app job uploads `test-summary-<sha>` on main pushes too. When the PR base commit's run skipped
the app job (for example a documentation-only push), the nearest earlier first-parent main commit
with a summary is used. Runs in pr-report.yml, which may read artifacts of other runs, and writes
status/searched (plus name/run-id/sha when found) to $GITHUB_OUTPUT for actions/download-artifact.

The PR base comes from the workflow_run event, not from the PR artifact: a pull request run produced
the artifact, so its `base` only has to agree with the event. Artifacts must come from this repository.
"""
import argparse
import json
import os
from pathlib import Path
import urllib.parse

import pr_comment
import pr_report
import scope


def candidate_commits(base, read_git=scope.git, limit=pr_report.BASELINE_SEARCH_LIMIT):
    scope.require_sha(base)
    return [sha for sha in read_git('rev-list', '--first-parent', f'--max-count={limit}', base).split() if sha]


def find_baseline(request, repository, repository_id, commits):
    """(the newest main-run artifact of this repository among `commits` or None, commits looked up)."""
    for searched, sha in enumerate(commits, start=1):
        name = f'test-summary-{sha}'
        found = request('GET', f'{pr_comment.API}/repos/{repository}/actions/artifacts?'
                        + urllib.parse.urlencode({'name': name, 'per_page': 100}))
        for artifact in found.get('artifacts', []):
            run = artifact.get('workflow_run') or {}
            if artifact.get('name') == name and not artifact.get('expired') \
                    and run.get('repository_id') == repository_id and run.get('head_repository_id') == repository_id \
                    and run.get('head_branch') == 'main' and run.get('head_sha') == sha:
                return dict(name=name, run_id=run['id'], sha=sha), searched
    return None, len(commits)


def locate(facts_path, base, repository, repository_id, request, read_git=scope.git):
    """The search result for the comment. Never raises: without a baseline the report is still posted."""
    try:
        facts = json.loads(Path(facts_path).read_text())
        if not isinstance(facts, dict):
            raise ValueError('facts.json is not a JSON object')
        scope.require_sha(base)
        if facts.get('base') != base:
            print(f"The report's PR base {facts.get('base')!r} differs from the event's PR base {base}")
            return dict(status='base_mismatch', searched=0)
        found, searched = find_baseline(request, repository, int(repository_id), candidate_commits(base, read_git))
        return dict(status='found', searched=searched, **found) if found else dict(status='not_found', searched=searched)
    except Exception as error:
        print(f'Baseline lookup failed: {type(error).__name__}: {pr_report.first_line(str(error))}')
        return dict(status='lookup_failed', searched=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--facts', required=True, help='facts.json of the PR report artifact')
    parser.add_argument('--base', required=True, help='PR base commit from the workflow_run event')
    parser.add_argument('--repository', required=True)
    parser.add_argument('--repository-id', required=True, help='numeric ID of --repository')
    args = parser.parse_args()
    result = locate(args.facts, args.base, args.repository, args.repository_id,
                    pr_comment.github(os.environ.get('GITHUB_TOKEN', '')))
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write(f"status={result['status']}\nsearched={result['searched']}\n")
        if result['status'] == 'found':
            output.write(f"name={result['name']}\nrun-id={result['run_id']}\nsha={result['sha']}\n")
    print(json.dumps(result))


if __name__ == '__main__':
    main()
