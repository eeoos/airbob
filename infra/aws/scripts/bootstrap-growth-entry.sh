#!/usr/bin/env bash
set -euo pipefail
umask 077
export LC_ALL=C

[[ "$AIRBOB_QUALIFICATION_ONLY:$AIRBOB_DATABASE_BOOTSTRAP" == true:dump ]]
[[ "$AIRBOB_REGION" == ap-northeast-2 ]]
[[ "$AIRBOB_DATASET_BUCKET" == airbob-performance-lab-dataset-942632789808 ]]
[[ "$AIRBOB_DATASET_RELEASE" =~ ^korea-growth-v3-[0-9a-f]{16}-aws(-r[1-9][0-9]{0,2})?$ ]]
[[ "$AIRBOB_DATASET_MANIFEST_SHA256" =~ ^[0-9a-f]{64}$ ]]
if ! command -v mysql >/dev/null; then
  dnf install -y mariadb105
fi
manifest=$(mktemp /var/tmp/airbob-growth-manifest.XXXXXX)
trap 'rm -f -- "$manifest"' EXIT
aws --region "$AIRBOB_REGION" s3api get-object --bucket "$AIRBOB_DATASET_BUCKET" \
  --key "datasets/$AIRBOB_DATASET_RELEASE/manifest.json" "$manifest" --no-cli-pager >/dev/null
printf '%s  %s\n' "$AIRBOB_DATASET_MANIFEST_SHA256" "$manifest" | sha256sum --check --status
helper_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
python3 "$helper_dir/bootstrap-growth-aws.py" --manifest "$manifest"
