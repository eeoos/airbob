#!/usr/bin/env bash
# Shared approval contract. This file does not run validators or grant approval.
# Only the initial restore path calls write_dataset_qualification after DB + ES validation.
qualification_sha_file() { openssl dgst -sha256 "$1" | awk '{print $NF}'; }
qualification_sha_text() { openssl dgst -sha256 | awk '{print $NF}'; }

verify_dataset_qualification() {
  local receipt=$1 manifest=$2 run=$3 resource=$4 engine=$5
  jq -e --slurpfile m "$manifest" --arg manifestSha "$(qualification_sha_file "$manifest")" \
    --arg run "$run" --arg resource "$resource" --arg engine "$engine" '
    def sha: type == "string" and test("^[0-9a-f]{64}$");
    (keys | sort) == (["schemaVersion","kind","runId","rdsResourceId","rdsEngineVersion",
      "dataset","verification","verifiedAt"] | sort) and
    .schemaVersion == 1 and .kind == "dataset-qualification" and
    .runId == $run and (.runId | test("^[a-z0-9][a-z0-9-]{2,31}$")) and
    .rdsResourceId == $resource and (.rdsResourceId | test("^db-[A-Z0-9]{26}$")) and
    .rdsEngineVersion == $engine and (.rdsEngineVersion | test("^8\\.4\\.[0-9]+$")) and
    .dataset == {release:$m[0].datasetRelease,runId:$m[0].datasetRunId,
      manifestSha256:$manifestSha,mysql:$m[0].mysql,releaseTuple:$m[0].releaseTuple,search:$m[0].search} and
    (.dataset.release | type == "string" and test("^[a-z0-9][a-z0-9._-]{2,63}$")) and
    (.dataset.runId | type == "string" and test("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$")) and
    (.dataset.mysql.flywayVersion | type == "string" and test("^[1-9][0-9]*$")) and
    (.dataset.search.enabled | type == "boolean") and
    (.dataset.releaseTuple | to_entries | all(.[]; if (.key|endswith("Sha256")) then (.value|sha) else true end)) and
    (.dataset.manifestSha256 | sha) and (.dataset.mysql.dumpSha256 | sha) and
    (.dataset.mysql.migrationChecksumSha256 | sha) and (.dataset.mysql.schemaFingerprintSha256 | sha) and
    (.dataset.releaseTuple | type == "object" and length > 0) and
    (.verification | keys | sort) == ["mode","search","semanticAttestationSha256"] and
    .verification.mode == "full" and (.verification.semanticAttestationSha256 | sha) and
    .verification.search == (if $m[0].search.enabled then "full" else "disabled" end) and
    (.verifiedAt | type == "string" and test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$") and
      (fromdateiso8601 | type == "number"))
  ' "$receipt" >/dev/null
}

write_dataset_qualification() {
  local manifest=$1 output=$2 run=$3 resource=$4 engine=$5 attestation=$6
  jq -n --slurpfile m "$manifest" --arg manifestSha "$(qualification_sha_file "$manifest")" \
    --arg run "$run" --arg resource "$resource" --arg engine "$engine" \
    --arg attestation "$attestation" --arg now "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" '
    {schemaVersion:1,kind:"dataset-qualification",runId:$run,rdsResourceId:$resource,rdsEngineVersion:$engine,
      dataset:{release:$m[0].datasetRelease,runId:$m[0].datasetRunId,manifestSha256:$manifestSha,
        mysql:$m[0].mysql,releaseTuple:$m[0].releaseTuple,search:$m[0].search},
      verification:{mode:"full",semanticAttestationSha256:$attestation,
        search:(if $m[0].search.enabled then "full" else "disabled" end)},verifiedAt:$now}
  ' > "$output" && verify_dataset_qualification "$output" "$manifest" "$run" "$resource" "$engine"
}

fetch_dataset_qualification() {
  local bucket=$1 key=$2 version_sha=$3 content_sha=$4 output=$5 region=$6 version
  [[ "$bucket" =~ ^airbob-performance-lab-evidence-[0-9]{12}$ \
    && "$key" =~ ^data-bootstrap/[a-z0-9][a-z0-9-]{2,31}/dataset-qualification\.json$ \
    && "$version_sha" =~ ^[0-9a-f]{64}$ && "$content_sha" =~ ^[0-9a-f]{64}$ ]] || return 1
  version=$(aws --region "$region" s3api head-object --bucket "$bucket" --key "$key" \
    --query VersionId --output text --no-cli-pager) || return 1
  [[ "$version" =~ ^[A-Za-z0-9._~+/=-]+$ && "$version" != null && "$version" != None \
    && "$(printf '%s' "$version" | qualification_sha_text)" == "$version_sha" ]] || return 1
  aws --region "$region" s3api get-object --bucket "$bucket" --key "$key" --version-id "$version" \
    --no-cli-pager "$output" >/dev/null || return 1
  [[ "$(qualification_sha_file "$output")" == "$content_sha" ]]
}
