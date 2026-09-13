#!/usr/bin/env bash
# Exact public SSM entry. Credentials remain in host-local files/subprocess memory.
set -euo pipefail
umask 077
[[ $# == 1 ]]
context=$1
run_id=$(jq -er '.runId' "$context")
dataset=$(jq -er '.datasetId' "$context")
release=$(jq -er '.serviceRelease' "$context")
[[ "$run_id" =~ ^lab-[a-z0-9][a-z0-9-]{0,27}$ && "$dataset" =~ ^global-growth-b-[0-9a-f]{16}$ && "$release" =~ ^[a-z0-9][a-z0-9-]{2,47}$ ]]
root="/opt/airbob/global-b/$run_id"
[[ -x "$root/toolchain/python/bin/python3" && -f "$root/restore-config.json" && ! -e "$root/STOP" ]]
export PATH="$root/aws-bin:$root/toolchain/python/bin:$root/toolchain/jdk/bin:$root/toolchain/mysql/bin:$PATH"
export JAVA_HOME="$root/toolchain/jdk" AWS_REGION=ap-northeast-2 AWS_MAX_ATTEMPTS=1 AWS_CLI_AUTO_PROMPT=off
# Each SSM entry must preserve the toolchain independently of earlier sessions.
export PYTHONDONTWRITEBYTECODE=1
unset PYTHONPATH PYTHONHOME JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS JAVA_OPTS JDK_JAVAC_OPTIONS CLASSPATH
stage="$root/service-$release"
mkdir -m 700 "$stage"
manifest="$stage/aws-service.json"
aws --region "$AWS_REGION" s3api get-object --bucket airbob-performance-lab-dataset-942632789808 \
  --key "datasets/$dataset-aws-service/$release/aws-service.json" --version-id "$(jq -er '.manifestVersionId' "$context")" \
  "$manifest" > "$stage/download.json"
[[ "$(jq -er '.VersionId' "$stage/download.json")" == "$(jq -er '.manifestVersionId' "$context")" ]]
printf '%s  %s\n' "$(jq -er '.manifestSha256' "$context")" "$manifest" | sha256sum --check --status
[[ "$(jq -er '.consumerTools.key' "$manifest")" == "datasets/$dataset-aws-service/$release/files/consumer-tools.tar.gz" ]]
aws --region "$AWS_REGION" s3api get-object --bucket airbob-performance-lab-dataset-942632789808 \
  --key "$(jq -er '.consumerTools.key' "$manifest")" --version-id "$(jq -er '.consumerTools.versionId' "$manifest")" \
  "$stage/consumer-tools.tar.gz" > "$stage/download.json"
[[ "$(jq -er '.VersionId' "$stage/download.json")" == "$(jq -er '.consumerTools.versionId' "$manifest")" ]]
printf '%s  %s\n' "$(jq -er '.consumerTools.sha256' "$manifest")" "$stage/consumer-tools.tar.gz" | sha256sum --check --status
jq -e --slurpfile context "$context" '.toolSources == $context[0].toolSources' "$manifest" >/dev/null
python3 -B - "$stage" <<'PY'
import hashlib,json,pathlib,tarfile,sys
root=pathlib.Path(sys.argv[1]); meta=json.loads((root/'aws-service.json').read_text())
assert (root/'consumer-tools.tar.gz').stat().st_size == meta['consumerTools']['bytes']
with tarfile.open(root/'consumer-tools.tar.gz') as archive:
    members=archive.getmembers()
    assert len(members)==len(meta['toolSources']) and {m.name for m in members}==set(meta['toolSources'])
    assert all(m.isfile() and '/' not in m.name and m.size <= 512*1024 for m in members)
    tools=root/'tools'; tools.mkdir(mode=0o700)
    for member in members:
        raw=archive.extractfile(member).read()
        assert hashlib.sha256(raw).hexdigest()==meta['toolSources'][member.name]
        file=tools/member.name; file.write_bytes(raw); file.chmod(0o600)
PY
python3 -B "$stage/tools/growth_b_service.py" bootstrap --manifest "$manifest" \
  --sha256 "$(jq -er '.manifestSha256' "$context")" --dataset-id "$dataset" --run-id "$run_id" --release "$release" \
  --context "$context" --root "$root" --output "$stage/bootstrap" > "$stage/bootstrap.log" 2>&1
aws --region "$AWS_REGION" s3api put-object --bucket airbob-performance-lab-evidence-942632789808 \
  --key "data-bootstrap/$run_id/$dataset-service-$release.json" --body "$stage/bootstrap/service-readiness.json" \
  --tagging Retention=summary --server-side-encryption AES256 --if-none-match '*' > "$stage/publication.json"
version=$(jq -er '.VersionId | select(. != "null" and . != "")' "$stage/publication.json")
aws --region "$AWS_REGION" s3api get-object --bucket airbob-performance-lab-evidence-942632789808 \
  --key "data-bootstrap/$run_id/$dataset-service-$release.json" --version-id "$version" "$stage/readback.json" > "$stage/readback-object.json"
cmp -s "$stage/bootstrap/service-readiness.json" "$stage/readback.json"
printf '%s\n' 'B dependencies verified. Application capacity remains a separate gated transition.'
