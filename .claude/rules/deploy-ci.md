---
paths:
  - ".github/**/*"
  - "scripts/**/*"
  - "infra/**/*"
  - "load-test/**/*"
  - "docker-compose*.yml"
  - "Makefile"
  - "monitoring/**/*"
---

# Deployment, CI/CD, and the archived AWS performance lab

## CI/CD

- PR CI and main CD reuse `ci.yml`. Keep the required `CI result` job unconditional and reject
  failed, cancelled, or unexpectedly skipped checks. CD must use the tested JAR from that exact
  main run and deploy the resulting image digest.
- `scripts/ci/scope.py` selects checks from the exact Git diff; `scripts/ci/test_scope.py` covers
  the classification.
- `scripts/README.md` identifies deployment, dataset, and archived tools. OCI CD and asset sync
  include only the five named deployment/operations scripts; local dataset and archived tools
  must not trigger an OCI deployment or be copied to its deployment directory.
- API-documentation-only CI must validate contracts without requesting OCI deployment. Required
  `CI result` includes documentation job outcomes; app builds already validate contracts via `check`.
- `scripts/ci/contracts.py` runs in the `scope` job and fails CI when an archived `load-test/` file no
  longer matches `load-test/archive-source.json` or an applied Flyway migration is edited or deleted.
- PR report: `ci.yml`'s `report` job collects deterministic facts read-only (`scripts/ci/pr_report.py`)
  and is not part of `CI result`; `pr-report.yml` (`workflow_run`, default-branch code) posts the one
  sticky comment. Never add `pull-requests: write` or `actions: read` to `ci.yml`: CD reuses it with
  `contents: read`, and a called workflow cannot request more than its caller grants.
- Claude review: `claude-review-request.yml` (`pull_request`, no permissions, runs nothing) marks
  same-repository, non-draft PRs when opened, reopened, or marked ready for review, and when the
  `claude-review` label is added. When it succeeds, `claude-review.yml` (`workflow_run`, default-branch
  code) runs `scripts/ci/claude_review.py` from main. The PR is fetched and read only as Git objects and
  API data, and the criteria and rules come from the default-branch commit the workflow runs, so a PR
  cannot change how it is reviewed; never check out, install from, or run anything from the PR head in
  that job. The script sends one review request to the Messages API with no tools (structured output;
  the SDK retries up to twice on connection errors and HTTP 408/409/429/5xx), logs the usage and then the
  review (or the unusable raw response, except a refused one) with workflow commands stopped around model
  output, and posts the result as a new PR comment; a usage footer it cannot read does not stop the post.
  Keep it tool-free and bounded by its size limits and `max_tokens`. It is informational, not part of
  `CI result`, and stays outside `ci.yml` for the same permission reason. Do not use
  `pull_request_target`: GitHub blocks it by default in public repositories from 2026-12-02. Do not switch
  to `anthropics/claude-code-action`: the monthly API credit does not cover runs that the action starts.
  The review job authenticates with workload identity federation. The rule accepts the subject
  `repo:eeoos/airbob:ref:refs/heads/main` only with `event_name` `workflow_run` and `workflow_ref`
  `eeoos/airbob/.github/workflows/claude-review.yml@refs/heads/main`; its IDs are repository variables.
  Do not add an API key secret, and keep `id-token: write` on that job only. Its Python packages are
  pinned by version and hash in `scripts/ci/claude-review-requirements.txt` and installed with
  `--require-hashes`; regenerate it with the `uv pip compile` command in `scripts/README.md`.
  Documentation-only PRs are skipped, but agent
  policy (`CLAUDE.md`, `AGENTS.md`, `.claude/`) and the review criteria are reviewed. Criteria and
  verdict records live in `docs/development/code-review.md` and `docs/development/ai-review-log.md`.

## OCI deployment

- OCI deployment is independent from the AWS experiment. AWS app publication is an explicit manual
  workflow; do not add it back to the OCI deployment path.
- `scripts/deploy-oci.sh` stops nginx and the app, starts infrastructure, runs the
  reservation-inventory preflight, creates topics (`kafka-topic-init` → `init-topics.sh`), stops
  Debezium, applies Flyway, restarts Debezium and registers the connector
  (`debezium-connector-init` → `register-connector.sh`), then starts the connector monitor, app, and
  nginx. Keep these gates and their order. Runbook: `docs/operations/oci-deployment-runbook.md`.

## Archived AWS performance lab

- App performance measurement ended on 2026-10-17. The application has no experiment profiles or
  comparison APIs; do not restore them or add archived runners back to CI.
- Preserve historical load-test scripts, fixtures, and guides under `load-test/` as evidence of
  measurement methodology; their original versions and hashes are recorded in
  `load-test/archive-source.json`. Reproduction needs the app/image/dataset recorded in each result.
- Preserved AWS resource-management tools target their compatible digest-pinned historical images
  and immutable datasets. The V27 launcher pins Flyway explicitly; do not substitute the current
  application image or silently migrate a recorded dataset.
- AWS infrastructure and immutable image assets live under `infra/aws/`. Keep image references
  digest-pinned, secrets out of the repository and rendered bundles, and ECR publication
  authenticated through GitHub OIDC.
- See `docs/operations/aws-performance-lab.md` and `infra/aws/bundles/README.md` before changing
  experiment topology or image publication.
