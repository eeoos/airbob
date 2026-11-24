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
- Claude review: `claude-review.yml` runs `anthropics/claude-code-action` on same-repository PRs when
  opened and when the `claude-review` label is added. It is informational, not part of `CI result`, and
  stays outside `ci.yml` for the same permission reason. A step prepares the diff, commit list, and PR
  description from the event's base and head SHAs in a new directory under `runner.temp` (outside the
  checkout). Keep Claude's tools to Read/Grep/Glob and the inline-comment tool with Bash disallowed: git
  options such as `--output` write files, and `gh pr comment` could edit or delete this account's other
  comments (the PR report). The summary comes back as structured output and a separate step posts it.
  It authenticates to the Claude API with workload identity federation (GitHub OIDC, rule subject
  `repo:eeoos/airbob:pull_request`, IDs in repository variables); do not add an API key secret, and keep
  `id-token: write` on that job only. Keep the per-run `--max-budget-usd` cap and `classify_inline_comments: false` (the
  classifier is a separate API call outside that cap). Criteria and verdict records live in
  `docs/development/code-review.md` and `docs/development/ai-review-log.md`.

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
