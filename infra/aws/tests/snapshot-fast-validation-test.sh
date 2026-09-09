#!/usr/bin/env bash
set -euo pipefail
repo=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
work=$(mktemp -d "${TMPDIR:-/tmp}/airbob-fast-restore-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
bootstrap="$repo/infra/aws/scripts/bootstrap-data.sh"
# Execute the production branch. Any reintroduced full attestation query fails.
awk '/^# Full database qualification/{copy=1} /^# Operational mutations/{exit} copy{print}' "$bootstrap" > "$work/db-branch.sh"
[[ -s "$work/db-branch.sh" ]] || exit 1
manifest="$repo/infra/aws/lab/tests/fixtures/dataset-manifest.json"
benchmark_dataset_manifest="$work/targets.json"
qualification_file="$work/qualification.json"
printf '%s\n' '{"capsules":[{"targets":[{"query":{"accommodationId":101}},{"query":{"accommodationId":102}}]}]}' > "$benchmark_dataset_manifest"
printf '%s\n' '{"verification":{"semanticAttestationSha256":"inherited"}}' > "$qualification_file"
mysql_attestation_exec() { printf '%s\n' 'unexpected full-table attestation' >&2; return 78; }
mysql_result_hash() { return 78; }
mysql_readiness_exec() {
  [[ "$*" =~ id=([0-9]+)$ ]] || return 79
  printf '%s\n' "${BASH_REMATCH[1]}" >> "$work/queries.log"
  [[ "${MISSING_SAMPLE:-false}" == false ]] || return 0
  printf '%s\n' "${BASH_REMATCH[1]}"
}
AIRBOB_DATABASE_BOOTSTRAP=snapshot
source "$work/db-branch.sh"
[[ "$semantic_attestation_sha256" == inherited && $(wc -l < "$work/queries.log" | tr -d ' ') == 2 ]] || exit 1
if (MISSING_SAMPLE=true; source "$work/db-branch.sh") > /dev/null 2>&1; then
  printf '%s\n' 'missing sample was accepted' >&2; exit 1
fi
printf '%s\n' '{"capsules":[]}' > "$benchmark_dataset_manifest"
if (source "$work/db-branch.sh") > /dev/null 2>&1; then
  printf '%s\n' 'empty quick-check target set was accepted' >&2; exit 1
fi
# ES full scrolling must have the same initial-restore-only guard.
awk '/^  if \[\[ "\$AIRBOB_DATABASE_BOOTSTRAP" == dump \]\]; then/{copy=1} copy{print} copy && /^  fi$/{exit}' "$bootstrap" > "$work/es-full-branch.sh"
curl_http() { printf '%s\n' 'unexpected ES scroll during snapshot reuse' >&2; return 78; }
source "$work/es-full-branch.sh"
# Check the full initial qualification remains before experiment writes.
qualification_line=$(grep -n '^if \[\[ "\$qualification_only" == true \]\]; then' "$bootstrap" | cut -d: -f1)
redis_line=$(grep -n '^redis_cli 6379 FLUSHDB' "$bootstrap" | cut -d: -f1)
[[ "$qualification_line" -lt "$redis_line" ]] || exit 1
printf '%s\n' 'snapshot quick checks passed without full DB or ES scans; missing samples fail'
