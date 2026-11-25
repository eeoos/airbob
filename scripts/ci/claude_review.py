"""Second AI review of a pull request (claude-review.yml): one Claude Messages API request, no tools.

The first review, by Codex, runs locally before push (docs/development/ai-review-log.md). claude-review.yml
runs this script from the default branch (workflow_run), so a PR cannot change the review it gets. The
script reads the PR only as Git objects and data: the review criteria, CLAUDE.md, and the .claude/rules
files whose paths match the change come from the default-branch commit the workflow runs (`--criteria`);
the PR description comes from the API; the commits, the diff, and the changed files come from the PR head.
It asks Claude for the review as structured output and posts it as a new PR comment.

The Claude API is reached with workload identity federation: the run's GitHub OIDC token is handed to
the anthropic SDK, which exchanges it for a short-lived token. claude-code-action is not used because
the monthly API credit does not cover runs that the action starts.

Locally, `--dry-run <file>` writes the request instead of sending it.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import urllib.parse
import urllib.request

import pr_comment
import pr_report
import scope

MARKER = '<!-- airbob-claude-review -->'
MODEL = 'claude-opus-5-5'
EFFORT = 'xhigh'
MAX_TOKENS = 64000  # includes thinking, which grows with EFFORT
# List prices of MODEL in USD per million tokens, used only for the cost line of the comment.
PRICES = dict(input=4.0, output=20.0, cache_write=5.0, cache_read=0.2)
AUDIENCE = 'https://api.anthropic.com'
GUIDE = 'docs/development/code-review.md'
# Size limits in characters, not tokens. The first two runs (PR #223) sent about 1.9 to 2 characters per
# token for this mix of Korean and code, so REQUEST_LIMIT is about 400,000 tokens (about $1.60 of input at
# PRICES) on top of at most MAX_TOKENS of output (about $1.28). Those figures assume that mix: text with more
# tokens per character (emoji, rare symbols) costs more, and past MODEL's 1M-token context window the API
# rejects the request or ends the output early, so the job fails without a comment.
DIFF_LIMIT = 400_000     # a larger change is not reviewed
FILE_LIMIT = 100_000     # a larger file is left to the diff
REQUEST_LIMIT = 800_000  # the whole request; changed files fill what the rest leaves, then go to the diff
FILES_HEADING = '# 바뀐 파일의 전체 내용 (head)\n\n'
NO_FILES = '(없음)'
OMITTED_LABEL = '\n\n전체 내용을 싣지 않은 파일(크기 한도 또는 바이너리, diff만 있음): '
SCHEMA = {'type': 'object', 'properties': {'summary': {'type': 'string'}},
          'required': ['summary'], 'additionalProperties': False}
SYSTEM = ('너는 airbob 저장소의 pull request를 리뷰한다. 이 저장소의 변경은 대부분 AI 코딩 에이전트(Claude Code)가 작성했다. '
          '도구는 없다. 주어진 리뷰 기준, 프로젝트 규칙, 변경 내용만 근거로 삼고, 그것으로 확인할 수 없으면 "확실하지 않음"이라고 표시하라. '
          'summary에는 PR에 그대로 게시할 요약 댓글 본문을 한국어 마크다운으로 써라.')


class ReviewSkipped(Exception):
    pass


def fenced(text, info=''):
    fence = '```'
    while fence in text:
        fence += '`'
    return f'{fence}{info}\n{text.rstrip()}\n{fence}'


def git_text(repo, *args):
    """Git output that may contain non-UTF-8 file content."""
    return subprocess.check_output(['git', '-C', repo.cwd, *args], stderr=subprocess.PIPE, timeout=60) \
        .decode('utf-8', 'replace')


def read_text(repo, revision, path):
    """The file as text, or None when it is missing or binary (not UTF-8, or containing NUL as UTF-16 does)."""
    try:
        text = repo.show(revision, path)
    except UnicodeDecodeError:
        return None
    return None if text is None or '\0' in text else text


def criteria_text(repo, criteria, path):
    """Criteria and rules come from the default-branch commit the workflow runs, never from the PR's head or
    base, so a PR cannot weaken what it is reviewed against. A change to them applies once it is merged."""
    text = read_text(repo, criteria, path)
    return '(없음)' if text is None else text


def matched_rules(repo, revision, paths):
    compiled = {name: [pr_report.glob_regex(glob) for glob in globs]
                for name, globs in pr_report.rule_globs(repo, revision).items()}
    return sorted(name for name, patterns in compiled.items()
                  if any(pattern.match(path) for path in paths for pattern in patterns))


def build_request(repo, base, head, title='', body='', *, criteria):
    """(user message, changed files left to the diff); raises ReviewSkipped when the change is too large.

    `criteria` is the commit the criteria and rules are read from (see criteria_text).
    """
    diff = git_text(repo, 'diff', f'{base}...{head}')
    if len(diff) > DIFF_LIMIT:
        raise ReviewSkipped(f'diff가 {len(diff):,}자로 한도 {DIFF_LIMIT:,}자를 넘습니다')
    entries = repo.name_status(f'{base}...{head}')
    paths = [path for _, path in entries]
    sections = [f'# 리뷰 기준 ({GUIDE})\n\n{criteria_text(repo, criteria, GUIDE)}',
                f'# 프로젝트 규칙 (CLAUDE.md)\n\n{criteria_text(repo, criteria, "CLAUDE.md")}']
    for name in matched_rules(repo, criteria, paths):
        sections.append(f'# 이 변경에 해당하는 영역 규칙 (.claude/rules/{name})\n\n'
                        f'{criteria_text(repo, criteria, ".claude/rules/" + name)}')
    sections.append(f'# PR\n\n제목: {title or "(없음)"}\n\n{body or "(설명 없음)"}')
    commits = git_text(repo, 'log', '--format=%h %s', f'{base}..{head}')
    sections.append(f'# 커밋 ({base[:8]}..{head[:8]})\n\n{fenced(commits)}')
    sections.append(f'# 변경 (git diff {base[:8]}...{head[:8]})\n\n{fenced(diff, "diff")}')
    # Changed files fill what the rest of the request leaves. The reserve is the files section with every
    # file omitted: its heading, "(없음)", and the label listing each file that can be omitted (deleted
    # files are neither sent nor listed). Each file sent instead takes its entry and a separator.
    listable = [path for status, path in entries if status != 'D']
    reserve = len('\n\n') + len(FILES_HEADING) + len(NO_FILES)
    if listable:
        reserve += len(OMITTED_LABEL) + len(', '.join(listable))
    budget = REQUEST_LIMIT - len(SYSTEM) - len('\n\n'.join(sections)) - reserve
    if budget < 0:
        raise ReviewSkipped(f'파일 본문 없이도 요청이 한도 {REQUEST_LIMIT:,}자를 넘습니다')
    full, omitted, used = [], [], 0
    for status, path in entries:
        if status == 'D':
            continue
        text = read_text(repo, head, path)
        entry = None if text is None or len(text) > FILE_LIMIT else f'## {path}\n\n{fenced(text)}'
        if entry is None or used + len(entry) + 2 > budget:
            omitted.append(path)
            continue
        used += len(entry) + 2
        full.append(entry)
    files = FILES_HEADING + ('\n\n'.join(full) or NO_FILES)
    if omitted:
        files += OMITTED_LABEL + ', '.join(omitted)
    sections.append(files)
    return '\n\n'.join(sections), omitted


def response_text(message):
    # A fallback during streaming continues the answer in a new text block, so the blocks are joined.
    return ''.join(block.text for block in message.content if block.type == 'text')


def summary_of(message):
    if message.stop_reason == 'refusal':
        details = getattr(message, 'stop_details', None)
        raise RuntimeError(f"Claude declined the review: {getattr(details, 'category', None)} "
                           f"{getattr(details, 'explanation', '')}")
    if message.stop_reason == 'max_tokens':
        raise RuntimeError(f'the review stopped at max_tokens ({MAX_TOKENS})')
    text = response_text(message)
    if not text:
        raise RuntimeError(f'no text in the response (stop_reason {message.stop_reason})')
    summary = (json.loads(text).get('summary') or '').strip()
    if not summary:
        raise RuntimeError('the response has an empty summary')
    return summary


def review(user_message, client=None):
    """The final message of one review request.

    The SDK retries up to twice on connection errors and on HTTP 408, 409, 429, and 5xx. A request the
    server was still processing when the connection dropped may be billed.
    """
    if client is None:
        import anthropic  # installed only in the review job
        client = anthropic.Anthropic()
    with client.beta.messages.stream(
            model=MODEL, max_tokens=MAX_TOKENS, system=SYSTEM,
            # A safety decline is retried server-side on Anthropic's recommended model for its category.
            betas=['server-side-fallback-2026-07-01'], fallbacks='default',
            output_config={'effort': EFFORT, 'format': {'type': 'json_schema', 'schema': SCHEMA}},
            messages=[{'role': 'user', 'content': user_message}]) as stream:
        return stream.get_final_message()


def usage_details(usage):
    """Tokens of each billed attempt, and the estimated cost at PRICES when only MODEL ran.

    After a fallback the top-level usage holds only the last attempt; `usage.iterations` holds every one.
    """
    attempts = [item for item in (getattr(usage, 'iterations', None) or [])
                if item.type in ('message', 'fallback_message')] or [usage]
    parts, usd, priced = [], 0.0, True
    for attempt in attempts:
        model = getattr(attempt, 'model', None) or MODEL
        tokens = dict(input=attempt.input_tokens, output=attempt.output_tokens,
                      cache_write=attempt.cache_creation_input_tokens or 0,
                      cache_read=attempt.cache_read_input_tokens or 0)
        parts.append(f'{model} 입력 {tokens["input"]:,} / 출력 {tokens["output"]:,}')
        if model == MODEL:
            usd += sum(count * PRICES[kind] for kind, count in tokens.items()) / 1_000_000
        else:
            priced = False
    cost = f'추정 비용 ${usd:.2f}' if priced else '대체 모델이 실행돼 비용은 Console에서 확인'
    return f'토큰: {", ".join(parts)} · {cost}'


def comment_body(text, head, run, details=''):
    footer = f'리뷰한 커밋: {head} · 실행: {run}' + (f' · {details}' if details else '') + f' · 기준: {GUIDE}'
    body = f'{MARKER}\n{text}\n\n<sub>{footer}</sub>\n'
    if len(body) > pr_report.COMMENT_LIMIT:
        note = f'\n\n_댓글 길이 한도를 넘어 잘랐습니다._\n\n<sub>{footer}</sub>\n'
        body = body[:pr_report.COMMENT_LIMIT - len(note)] + note
    return body


def use_github_identity(environ=os.environ):
    """Hand this run's GitHub OIDC token to the SDK for workload identity federation.

    Needs `id-token: write`. Outside GitHub Actions nothing changes and the SDK uses other credentials.
    """
    url, token = environ.get('ACTIONS_ID_TOKEN_REQUEST_URL'), environ.get('ACTIONS_ID_TOKEN_REQUEST_TOKEN')
    if not url or not token:
        return
    request = urllib.request.Request(url + '&' + urllib.parse.urlencode({'audience': AUDIENCE}),
                                     headers={'Authorization': f'Bearer {token}'})
    with urllib.request.urlopen(request, timeout=30) as response:
        environ['ANTHROPIC_IDENTITY_TOKEN'] = json.load(response)['value']


def pull_request_text(request, repository, number):
    """The PR title and body from the API; the workflow_run event that starts the review does not carry them.
    Without a GitHub token (a local dry run) both are empty."""
    if request is None:
        return '', ''
    pull = request('GET', f'{pr_comment.API}/repos/{repository}/pulls/{number}') or {}
    return pull.get('title') or '', pull.get('body') or ''


def log_model_output(label, text):
    """Print model output to the job log with workflow commands stopped, since it can quote PR content.

    Flushed, so the log keeps it even if the job is killed while posting.
    """
    token = secrets.token_hex(16)
    print(f'::stop-commands::{token}\n{label}\n{text}\n::{token}::', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--base', required=True, help='PR base commit (pull_request.base.sha)')
    parser.add_argument('--head', required=True, help='PR head commit (pull_request.head.sha)')
    parser.add_argument('--criteria', required=True,
                        help='commit to read the review criteria and rules from: the default-branch commit '
                             'the workflow runs (github.sha), never one the PR controls')
    parser.add_argument('--pr', required=True)
    parser.add_argument('--repository', required=True)
    parser.add_argument('--dry-run', metavar='FILE', help='write the request to FILE instead of sending it')
    args = parser.parse_args()
    scope.require_sha(args.base)
    scope.require_sha(args.head)
    scope.require_sha(args.criteria)
    if not pr_comment.PR_NUMBER.fullmatch(args.pr) or not pr_comment.REPOSITORY.fullmatch(args.repository):
        raise ValueError('invalid pull request number or repository')
    run = f"{os.environ.get('GITHUB_RUN_ID', 'local')}/{os.environ.get('GITHUB_RUN_ATTEMPT', '1')}"
    token = os.environ.get('GITHUB_TOKEN')
    title, body = pull_request_text(pr_comment.github(token) if token else None, args.repository, args.pr)

    try:
        user_message, omitted = build_request(pr_report.Repo(), args.base, args.head, title, body,
                                              criteria=args.criteria)
    except ReviewSkipped as skipped:
        text, details = f'Claude 리뷰를 하지 않았습니다: {skipped}.', ''
    else:
        print(f'Request: {len(SYSTEM) + len(user_message):,} characters; left to the diff: {omitted or "none"}')
        if args.dry_run:
            Path(args.dry_run).write_text(f'# system\n\n{SYSTEM}\n\n# user\n\n{user_message}\n')
            return
        use_github_identity()
        message = review(user_message)
        # The response is billed by now: log its usage and text before anything that can fail, and let no
        # footer problem stop the comment.
        try:
            details = usage_details(message.usage)
        except Exception as error:
            print(f'Could not read the usage: {error!r}')
            details = '토큰 정보를 읽지 못함'
        print(details)
        try:
            text = summary_of(message)
        except Exception:
            # A refused partial answer is discarded, as Anthropic advises; the error names the refusal.
            if message.stop_reason != 'refusal':
                log_model_output(f'Unusable response (stop_reason {message.stop_reason}):', response_text(message))
            raise
        log_model_output(MARKER, text)
    if args.dry_run:
        print(text)
        return
    pr_comment.github(os.environ['GITHUB_TOKEN'])(
        'POST', f'{pr_comment.API}/repos/{args.repository}/issues/{args.pr}/comments',
        {'body': comment_body(text, args.head, run, details)})


if __name__ == '__main__':
    main()
