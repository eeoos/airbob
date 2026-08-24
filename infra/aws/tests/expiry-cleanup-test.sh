#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
source_script="$repo_root/infra/aws/scripts/cleanup-expired-lab.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-expiry-cleanup-test.XXXXXX")

cleanup() {
  local status=$?
  trap - EXIT HUP INT TERM
  rm -rf "$temp_dir"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { printf '%s\n' "$1" >&2; exit 1; }
[[ -x "$source_script" && ! -L "$source_script" ]] || fail "expiry cleanup script is missing or unsafe"

fixture_scripts="$temp_dir/repo/infra/aws/scripts"
mkdir -p "$fixture_scripts" "$temp_dir/bin"
cp "$source_script" "$fixture_scripts/cleanup-expired-lab.sh"
cat > "$fixture_scripts/aws-lab.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'operator %s run=%s force=%s\n' "$*" "${RUN_ID:-}" "${FORCE:-}" >> "${FAKE_OPERATOR_LOG:?}"
EOF
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
case "${FAKE_INVENTORY_KIND:-empty}" in
  empty) printf '%s\n' '{"ResourceTagMappingList":[]}' ;;
  current) expires=1900000000 ;;
  expired) expires=1899990000 ;;
  *) exit 1 ;;
esac
if [[ "${FAKE_INVENTORY_KIND:-empty}" != empty ]]; then
  printf '{"ResourceTagMappingList":[{"ResourceARN":"arn:aws:ec2:x","Tags":[{"Key":"Persistence","Value":"ephemeral"},{"Key":"RunId","Value":"lab-expiry-test"},{"Key":"ExpiresAt","Value":"%s"}]}]}\n' "$expires"
fi
EOF
chmod 700 "$fixture_scripts/aws-lab.sh" "$fixture_scripts/cleanup-expired-lab.sh" "$temp_dir/bin/aws"
: > "$temp_dir/operator.log"

run_cleanup() {
  env PATH="$temp_dir/bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
    AIRBOB_NOW_EPOCH=1900000000 FAKE_OPERATOR_LOG="$temp_dir/operator.log" "$@" \
    "$fixture_scripts/cleanup-expired-lab.sh"
}

run_cleanup FAKE_INVENTORY_KIND=empty | grep -Fq 'reason=no-active-lab' \
  || fail "empty inventory did not exit cleanly"
run_cleanup FAKE_INVENTORY_KIND=current | grep -Fq 'cleanup_due=false' \
  || fail "unexpired inventory triggered cleanup"
[[ ! -s "$temp_dir/operator.log" ]] || fail "operator ran before expiry grace"
run_cleanup FAKE_INVENTORY_KIND=expired | grep -Fq 'cleanup_due=true' \
  || fail "expired inventory was not selected"
grep -Fqx 'operator down run=lab-expiry-test force=true' "$temp_dir/operator.log" \
  || fail "expired cleanup did not invoke the shared forced-down path"

printf '%s\n' 'expiry cleanup contract tests passed'
