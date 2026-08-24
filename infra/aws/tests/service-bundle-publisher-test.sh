#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
repo_root=$(CDPATH= cd -P -- "$script_dir/../../.." && pwd -P)
publisher="$repo_root/infra/aws/scripts/publish-service-bundles.sh"
temp_dir=$(mktemp -d "${TMPDIR:-/tmp}/airbob-bundle-publisher-test.XXXXXX")

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

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[[ -x "$publisher" && ! -L "$publisher" ]] || fail "bundle publisher is missing or unsafe"

fixture="$temp_dir/repo"
fake_bin="$temp_dir/bin"
fake_s3="$temp_dir/s3"
mkdir -p "$fixture/infra/aws/scripts" "$fake_bin" "$fake_s3"
cp "$publisher" "$fixture/infra/aws/scripts/publish-service-bundles.sh"
cp "$repo_root/infra/aws/toolchain.env" "$fixture/infra/aws/toolchain.env"

cat > "$fixture/infra/aws/scripts/package-service-bundles.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
commit=$1
output=$2
archive="airbob-service-bundles-$commit.tar.gz"
printf 'archive-%s\n' "$commit" > "$output/$archive"
printf '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  %s\n' "$archive" > "$output/$archive.sha256"
printf '{"schemaVersion":1,"commit":"%s"}\n' "$commit" > "$output/airbob-service-bundles-$commit.manifest.json"
EOF
chmod 700 "$fixture/infra/aws/scripts/package-service-bundles.sh"

cat > "$fake_bin/aws" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'aws %s\n' "$*" >> "${FAKE_CALL_LOG:?}"
case " $* " in
  *' sts get-caller-identity '*) printf '%s\n' '942632789808' ;;
  *' s3api head-object '*)
    key=''
    while [[ "$#" -gt 0 ]]; do
      case "$1" in --key) key=$2; shift 2 ;; *) shift ;; esac
    done
    [[ -f "${FAKE_S3_ROOT:?}/$key" ]]
    ;;
  *' s3api put-object '*)
    key=''
    body=''
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        --body) body=$2; shift 2 ;;
        --content-type|--server-side-encryption|--metadata|--if-none-match) shift 2 ;;
        --bucket) shift 2 ;;
        --no-cli-pager) shift ;;
        *) shift ;;
      esac
    done
    target="${FAKE_S3_ROOT:?}/$key"
    [[ ! -e "$target" ]]
    mkdir -p "${target%/*}"
    cp "$body" "$target"
    ;;
  *' s3api get-object '*)
    key=''
    destination=''
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        --bucket) shift 2 ;;
        --no-cli-pager) shift ;;
        --*) shift ;;
        *) destination=$1; shift ;;
      esac
    done
    [[ -n "$key" && -n "$destination" ]]
    cp "${FAKE_S3_ROOT:?}/$key" "$destination"
    ;;
  *) printf 'unexpected fake AWS call: %s\n' "$*" >&2; exit 1 ;;
esac
EOF
chmod 700 "$fake_bin/aws"

commit=0123456789abcdef0123456789abcdef01234567
bucket=airbob-performance-lab-bundles-942632789808
call_log="$temp_dir/aws-calls.log"
: > "$call_log"

run_publisher() {
  env \
    PATH="$fake_bin:/usr/bin:/bin" \
    AWS_REGION=ap-northeast-2 \
    FAKE_CALL_LOG="$call_log" \
    FAKE_S3_ROOT="$fake_s3" \
    "$fixture/infra/aws/scripts/publish-service-bundles.sh" "$commit" "$bucket"
}

first_output=$(run_publisher)
[[ "$first_output" == "bundle_manifest=s3://$bucket/service-bundles/$commit/airbob-service-bundles-$commit.manifest.json" ]] \
  || fail "publisher did not report the immutable completion marker"

prefix="$fake_s3/service-bundles/$commit"
[[ -f "$prefix/airbob-service-bundles-$commit.tar.gz" ]]
[[ -f "$prefix/airbob-service-bundles-$commit.tar.gz.sha256" ]]
[[ -f "$prefix/airbob-service-bundles-$commit.manifest.json" ]]
last_put=$(grep 's3api put-object' "$call_log" | tail -1)
[[ "$last_put" == *'.manifest.json'* ]] || fail "release manifest was not published last"

run_publisher >/dev/null || fail "publisher is not idempotent for identical remote bytes"

printf '%s\n' 'different bytes' > "$prefix/airbob-service-bundles-$commit.tar.gz"
if run_publisher >/dev/null 2>&1; then
  fail "publisher accepted an existing immutable key with different bytes"
fi

printf '%s\n' 'service bundle publisher tests passed'
