#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
lease_script="$repo_root/infra/aws/scripts/orchestration-lease.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-lease-test.XXXXXX")

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

[[ -x "$lease_script" && ! -L "$lease_script" ]] || fail "orchestration lease script is missing or unsafe"
grep -Fq 'AcquiredAt.N,HeartbeatAt.N,ExpiresAt.N,CommandDeadline.N' "$lease_script" \
  || fail "lease status does not expose acquisition/heartbeat timestamps for runtime inspection"

mkdir -p "$temp_dir/bin"
cat > "$temp_dir/bin/date" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == '+%s' ]] || { printf 'unexpected fake date call: %s\n' "$*" >&2; exit 1; }
printf '%s\n' 1900000000
EOF
cat > "$temp_dir/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"

case " $* " in
  *' dynamodb update-item '*)
    [[ "${FAKE_CONDITIONAL_FAILURE:-0}" != 1 ]] || exit 254
    if [[ "$*" == *'if_not_exists(#token, :zero) + :one'* ]]; then
      printf '%s\n' 7
    fi
    ;;
  *' dynamodb get-item '*)
    printf '%s\n' "${FAKE_ITEM:-owner-a 7 run-a up 1900000000 1900000060 2000000000 2000001000}"
    ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
chmod 700 "$temp_dir/bin/aws" "$temp_dir/bin/date"

run_lease() {
  env PATH="$temp_dir/bin:/usr/bin:/bin" AWS_REGION=ap-northeast-2 \
    FAKE_CALL_LOG="$temp_dir/calls.log" "$@"
}

: > "$temp_dir/calls.log"
token=$(run_lease "$lease_script" acquire lease-table lock-a owner-a run-a up 180 5400)
[[ "$token" == 'fencing_token=7' ]] || fail "acquire did not return the atomic fencing token"
measurement_token=$(run_lease "$lease_script" acquire lease-table lock-a owner-a run-a measurement 180 5400)
[[ "$measurement_token" == 'fencing_token=7' ]] || fail "measurement did not use the shared fencing-token lease"
snapshot_token=$(run_lease "$lease_script" acquire lease-table airbob-dataset-snapshot/rehearsal-v17 owner-a snapshot-1234abcd dataset-snapshot 180 8100)
[[ "$snapshot_token" == 'fencing_token=7' ]] || fail "dataset snapshot did not use the shared fencing-token lease"
if run_lease "$lease_script" acquire lease-table lock-a owner-a run-a up 180 8100 >/dev/null 2>&1; then
  fail "non-snapshot command accepted the extended credential-fencing deadline"
fi
grep -Fq 'attribute_not_exists(#owner) OR #owner = :released OR (#expires < :now AND #deadline < :now)' "$temp_dir/calls.log" \
  || fail "acquire does not require both heartbeat expiry and command deadline for reclaim"
grep -Fq 'if_not_exists(#token, :zero) + :one' "$temp_dir/calls.log" \
  || fail "acquire does not monotonically increment the fencing token"

run_lease "$lease_script" assert lease-table lock-a owner-a 7 run-a up >/dev/null
run_lease "$lease_script" heartbeat lease-table lock-a owner-a 7 run-a up 180 >/dev/null
run_lease "$lease_script" release lease-table lock-a owner-a 7 run-a up >/dev/null
grep -Fq '#owner = :owner AND #token = :token AND #run = :run AND #command = :command' "$temp_dir/calls.log" \
  || fail "lease mutation is not fenced by exact owner/token/run/command"
heartbeat_call=$(grep -F 'SET #heartbeat = :now, #expires = :expires' "$temp_dir/calls.log")
release_call=$(grep -F 'SET #owner = :released, #heartbeat = :now, #expires = :zero, #deadline = :zero' "$temp_dir/calls.log")
if [[ "$heartbeat_call" == *'#acquired'* || "$release_call" == *'#acquired'* ]]; then
  fail "heartbeat/release sent an unused DynamoDB expression-name alias"
fi
if grep -Fq 'dynamodb delete-item' "$temp_dir/calls.log"; then
  fail "release must retain fencing-token history instead of deleting the row"
fi

if run_lease FAKE_ITEM='owner-b 7 run-a up 1900000000 1900000060 2000000000 2000001000' \
  "$lease_script" assert lease-table lock-a owner-a 7 run-a up >/dev/null 2>&1; then
  fail "assert accepted a different lease owner"
fi
if run_lease FAKE_CONDITIONAL_FAILURE=1 \
  "$lease_script" acquire lease-table lock-a owner-a run-a up 180 5400 >/dev/null 2>&1; then
  fail "acquire hid a conditional-write rejection"
fi

printf '%s\n' 'orchestration lease contract tests passed'
