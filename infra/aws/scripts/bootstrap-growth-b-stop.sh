#!/usr/bin/env bash
# Delivered by the reviewed controller only to this run/fence's bootstrap host.
# A durable stop marker also fences a delayed SSM bootstrap association.
set -euo pipefail
umask 077
[[ $# == 2 && "$1" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && "$2" =~ ^[1-9][0-9]*$ ]] || exit 1
run_id=$1
fence=$2
root="/opt/airbob/global-b/$run_id"
[[ ! -L "$root" ]] || exit 1
install -d -m 700 "$root"
exec 9> "$root/control.lock"
flock -x 9
if [[ -e "$root/context.json" ]]; then
  jq -e --arg run "$run_id" --argjson fence "$fence" \
    '.runId == $run and .lease.runId == $run and .lease.fencingToken == $fence' "$root/context.json" >/dev/null
fi
printf '%s\n' stopped > "$root/STOP"
[[ -e "$root/process-group" ]] || exit 0
read -r child start_ticks extra < "$root/process-group"
flock -u 9
[[ "$child" =~ ^[1-9][0-9]*$ && "$start_ticks" =~ ^[0-9]+$ && -z "$extra" ]] || exit 1
owned_process_exists() {
  [[ -r "/proc/$child/stat" ]] || return 1
  [[ "$(awk '{print $22}' "/proc/$child/stat")" == "$start_ticks" ]] || return 1
}
owned_process_exists || exit 0
[[ "$(awk '{print $5}' "/proc/$child/stat")" == "$child" ]] || exit 1
tr '\0' '\n' < "/proc/$child/cmdline" | grep -Fx -- "$root/consumer-tools/growth_b_prepare.py" >/dev/null
# The Python supervisor propagates cancellation to each exact child process
# group; the frozen consumer cleans its own temporary application and Redis.
kill -INT -- "-$child"
for attempt in $(seq 1 120); do
  owned_process_exists || exit 0
  sleep 1
done
# Do not destroy RDS if graceful cancellation cannot be attested.
exit 1
