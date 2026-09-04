#!/usr/bin/env bash
set -euo pipefail

script_dir=$(CDPATH= cd -P -- "$(dirname -- "$0")" && pwd -P)
operator="$script_dir/../scripts/aws-lab.sh"
comparison_projection_filter="$script_dir/../scripts/readiness-comparison-projection.jq"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/airbob-snapshot-parity-test.XXXXXX")
trap 'rm -rf "$test_root"' EXIT
fail() { printf '%s\n' "$1" >&2; exit 1; }
sha256_file() { shasum -a 256 "$1" | awk '{print $1}'; }
sha256_text() { shasum -a 256 | awk '{print $1}'; }
assert_lease() { :; }

# Run the production publication tail, so deleting or moving its parity gate
# after publication causes these rejection cases to expose a published receipt.
eval "$(sed -n '/^verify_snapshot_receipt_parity() {/,/^}/p' "$operator")"
publication_tail=$(awk '
  /^publish_direct_readiness\(\) \{/ { in_function=1 }
  in_function && /^  jq --arg comparisonProjectionSha256/ { capture=1 }
  capture && /^}/ { exit }
  capture { print }
' "$operator")
[[ -n "$publication_tail" ]] || fail 'direct readiness publication boundary is missing'

AWS_REGION=ap-northeast-2
evidence_bucket=airbob-performance-lab-evidence-942632789808
run_id=lab-replay
dataset_release=fixture-v27
rds_snapshot_identifier=airbob-dataset-fixture-v27
rds_snapshot_source_run_id=lab-source
rds_snapshot_source_resource_id=db-ABCDEFGHIJKLMNOPQRSTUVWX
source_data_key=data-bootstrap/lab-source/fixture-v27.json
source_readiness_key=measurements/lab-source/direct-readiness.json

publish_immutable_json() {
  cp "$2" "$temp_dir/published.json"
}
aws() {
  local key='' version='' destination="${!#}" previous='' value
  printf '%s\n' "$*" >> "$temp_dir/aws.log"
  for value in "$@"; do
    case "$previous" in --key) key=$value ;; --version-id) version=$value ;; esac
    case "$value" in "$temp_dir"/snapshot-source-*.json) destination=$value ;; esac
    previous=$value
  done
  case " $* " in
    *' rds describe-db-snapshots '*) cat "$temp_dir/snapshot.json" ;;
    *' s3api head-object '*)
      [[ "$key" == "$source_data_key" || "$key" == "$source_readiness_key" ]] || return 1
      if [[ "$key" == "$source_readiness_key" ]]; then
        printf '{"versionId":"%s"}\n' "$readiness_head_version"
      else
        printf '{"versionId":"%s"}\n' "$head_version"
      fi
      ;;
    *' s3api get-object '*)
      [[ "$version" == source-v1 ]] || return 1
      case "$key" in
        "$source_data_key") cp "$temp_dir/source-data.json" "$destination" ;;
        "$source_readiness_key") cp "$temp_dir/source-readiness.json" "$destination" ;;
        *) return 1 ;;
      esac
      ;;
    *) return 1 ;;
  esac
}

prepare_case() {
  temp_dir="$test_root/$1"
  mkdir "$temp_dir"
  database_bootstrap=snapshot
  head_version=source-v1
  readiness_head_version=source-v1
  jq -n '{schemaVersion:2,runId:"lab-source",datasetRelease:"fixture-v27",databaseBootstrap:"dump",rdsResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX",verifiedAt:"2026-09-01T00:00:00Z",semanticAttestationSha256:("a" * 64),outboxState:"empty"}' > "$temp_dir/source-data.json"
  jq '.runId="lab-replay" | .databaseBootstrap="snapshot" | .rdsResourceId="db-ZYXWVUTSRQPONMLKJIHGFEDC" | .verifiedAt="2026-09-02T00:00:00Z"' \
    "$temp_dir/source-data.json" > "$temp_dir/current-data.json"
  jq -n --arg dataSha "$(sha256_file "$temp_dir/source-data.json")" '{
    schemaVersion:1,status:"ready",runId:"lab-source",executionCode:{commit:("b" * 40),operatorTreeSha256:("c" * 64)},
    dataset:{release:"fixture-v27",manifestVersionId:"manifest-v1",manifestSha256:("d" * 64)},
    bundle:{commit:("e" * 40),archiveSha256:("f" * 64),manifestVersionId:"bundle-v1",manifestSha256:("1" * 64)},
    images:{app:"app@sha256:fixture"},actual:{ami:{id:"ami-fixture"},rds:{identifier:"airbob-lab-source",resourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX",class:"db.t3.small",parameterGroups:["source-pg"]},rdsParameterGroupFamily:"mysql8.0",alb:{shape:{availabilityZones:["a","b"],securityGroups:["sg-source"]},observedIngress:[{isEgress:false,ipProtocol:"tcp",fromPort:443,toPort:443,cidrIpv4:"8.8.8.8/32"}]},autoScalingGroup:{name:"source-asg",min:1,desired:1,max:1}},
    topology:{mode:"performance",policy:"integrated-smoke",dnsMode:"direct-only",cacheEnabled:false,loadGeneratorEnabled:false},
    networkClearance:{projectionSha256:("2" * 64)},smoke:{health:{passed:true}},
    bootstrap:{mode:"dump",rdsSnapshotIdentifier:null,rdsSnapshotSourceRunId:null,rdsSnapshotSourceResourceId:null,receipt:{key:"data-bootstrap/lab-source/fixture-v27.json",versionId:"source-v1",sha256:$dataSha}}
  }' > "$temp_dir/source-basis.json"
  bind_readiness "$temp_dir/source-data.json" "$temp_dir/source-basis.json" "$temp_dir/source-readiness.json"
  jq '.runId="lab-replay" | .bootstrap.mode="snapshot" | .bootstrap.rdsSnapshotIdentifier="airbob-dataset-fixture-v27" | .bootstrap.rdsSnapshotSourceRunId="lab-source" | .bootstrap.rdsSnapshotSourceResourceId="db-ABCDEFGHIJKLMNOPQRSTUVWX" | .actual.rds.resourceId="db-ZYXWVUTSRQPONMLKJIHGFEDC"' \
    "$temp_dir/source-basis.json" > "$temp_dir/current-basis.json"
  refresh_tags
}
bind_readiness() {
  local data=$1 basis=$2 output=$3 projection data_sha
  data_sha=$(jq -cS 'del(.runId,.databaseBootstrap,.rdsResourceId,.verifiedAt)' "$data" | tr -d '\n' | sha256_text)
  jq --arg sha "$data_sha" '.bootstrap.dataProjectionSha256=$sha' "$basis" > "$temp_dir/bound-basis.json"
  projection=$(jq -cSf "$comparison_projection_filter" "$temp_dir/bound-basis.json")
  jq --argjson projection "$projection" --arg sha "$(printf '%s\n' "$projection" | sha256_text)" \
    '. + {comparisonProjection:$projection,comparisonProjectionSha256:$sha}' "$temp_dir/bound-basis.json" > "$output"
}
refresh_tags() {
  jq -n --arg dataSha "$(sha256_file "$temp_dir/source-data.json")" \
    --arg readinessSha "$(sha256_file "$temp_dir/source-readiness.json")" \
    --arg versionSha "$(printf source-v1 | sha256_text)" '{DBSnapshots:[{DBSnapshotIdentifier:"airbob-dataset-fixture-v27",DbiResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX",TagList:([
      {Key:"SourceLabRunId",Value:"lab-source"},{Key:"SourceRdsResourceId",Value:"db-ABCDEFGHIJKLMNOPQRSTUVWX"},
      {Key:"PromotionReceiptSchemaVersion",Value:"2"},{Key:"DatasetRelease",Value:"fixture-v27"},
      {Key:"DataBootstrapKey",Value:"data-bootstrap/lab-source/fixture-v27.json"},{Key:"DataBootstrapVersionIdSha256",Value:$versionSha},{Key:"DataBootstrapSha256",Value:$dataSha},
      {Key:"DirectReadinessKey",Value:"measurements/lab-source/direct-readiness.json"},{Key:"DirectReadinessVersionIdSha256",Value:$versionSha},{Key:"DirectReadinessSha256",Value:$readinessSha}
    ])}]}' > "$temp_dir/snapshot.json"
}
run_publication() {
  local data_receipt="$temp_dir/current-data.json" receipt_basis="$temp_dir/receipt-basis.json"
  local receipt="$temp_dir/direct-readiness.json" comparison_projection="$temp_dir/current-projection.json" comparison_projection_sha256
  bind_readiness "$data_receipt" "$temp_dir/current-basis.json" "$receipt_basis"
  jq -Sf "$comparison_projection_filter" "$receipt_basis" > "$comparison_projection"
  comparison_projection_sha256=$(jq -cS . "$comparison_projection" | sha256_text)
  eval "$publication_tail"
}

for scenario in semantic-drift readiness-drift wrong-version wrong-content readiness-wrong-version readiness-wrong-content wrong-source-run wrong-source-resource source-data-identity source-readiness-binding source-projection-binding; do
  prepare_case "$scenario"
  case "$scenario" in
    semantic-drift) jq '.semanticAttestationSha256=("9" * 64)' "$temp_dir/current-data.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/current-data.json" ;;
    readiness-drift) jq '.actual.rds.class="db.t3.medium"' "$temp_dir/current-basis.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/current-basis.json" ;;
    wrong-version) head_version=changed-v2 ;;
    wrong-content) printf ' ' >> "$temp_dir/source-data.json" ;;
    readiness-wrong-version) readiness_head_version=changed-v2 ;;
    readiness-wrong-content) printf ' ' >> "$temp_dir/source-readiness.json" ;;
    wrong-source-run) jq '(.DBSnapshots[0].TagList[]|select(.Key=="SourceLabRunId").Value)="lab-other"' "$temp_dir/snapshot.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/snapshot.json" ;;
    wrong-source-resource) jq '(.DBSnapshots[0].TagList[]|select(.Key=="SourceRdsResourceId").Value)="db-ZYXWVUTSRQPONMLKJIHGFEDC"' "$temp_dir/snapshot.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/snapshot.json" ;;
    source-data-identity) jq '.databaseBootstrap="snapshot"' "$temp_dir/source-data.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/source-data.json"; refresh_tags ;;
    source-readiness-binding) jq '.bootstrap.dataProjectionSha256=("8" * 64)' "$temp_dir/source-readiness.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/source-readiness.json"; refresh_tags ;;
    source-projection-binding) jq '.comparisonProjectionSha256=("8" * 64)' "$temp_dir/source-readiness.json" > "$temp_dir/changed.json"; mv "$temp_dir/changed.json" "$temp_dir/source-readiness.json"; refresh_tags ;;
  esac
  if (run_publication) > "$temp_dir/stdout" 2> "$temp_dir/stderr"; then
    fail "snapshot receipt parity accepted $scenario"
  fi
  [[ ! -f "$temp_dir/published.json" ]] || fail "snapshot receipt parity published before rejecting $scenario"
done
prepare_case matching
run_publication
[[ -f "$temp_dir/published.json" ]] || fail 'matching snapshot receipts did not publish readiness'
prepare_case dump-bypass
database_bootstrap=dump
run_publication
[[ -f "$temp_dir/published.json" && ! -f "$temp_dir/aws.log" ]] || fail 'dump mode did not bypass snapshot receipt reads'
printf '%s\n' 'AWS snapshot receipt parity tests passed'
