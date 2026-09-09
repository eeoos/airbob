#!/usr/bin/env bash
set -euo pipefail
repo=$(CDPATH= cd -P -- "$(dirname -- "$0")/../../.." && pwd -P)
script_dir="$repo/infra/aws/scripts"
source "$script_dir/dataset-qualification.sh"
work=$(mktemp -d "${TMPDIR:-/tmp}/airbob-snapshot-reuse-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
fail() { printf '%s\n' "$1" >&2; exit 1; }
eval "$(sed -n '/^verify_snapshot_receipt_parity() {/,/^}/p' "$script_dir/aws-lab.sh")"
temp_dir="$work"
AWS_REGION=ap-northeast-2 evidence_bucket=airbob-performance-lab-evidence-942632789808
database_bootstrap=snapshot rds_snapshot_identifier=airbob-dataset-fixture
rds_snapshot_source_run_id=lab-source rds_snapshot_source_resource_id=db-ABCDEFGHIJKLMNOPQRSTUVWX rds_engine_version=8.4.8
cp "$repo/infra/aws/lab/tests/fixtures/dataset-manifest.json" "$work/dataset-manifest.json"
write_dataset_qualification "$work/dataset-manifest.json" "$work/source.json" lab-source "$rds_snapshot_source_resource_id" 8.4.8 "$(printf '%064d' 1)"
fake_version=source-v1
refresh() {
  jq -n --arg sha "$(qualification_sha_file "$work/source.json")" --arg vsha "$(printf source-v1 | qualification_sha_text)" '
    {DBSnapshots:[{DBSnapshotIdentifier:"airbob-dataset-fixture",DbiResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX",Engine:"mysql",EngineVersion:"8.4.8",Status:"available",Encrypted:true,
    TagList:({SourceLabRunId:"lab-source",SourceRdsResourceId:"db-ABCDEFGHIJKLMNOPQRSTUVWX",PromotionReceiptSchemaVersion:"3",
    DataBootstrapKey:"data-bootstrap/lab-source/dataset-qualification.json",DataBootstrapVersionIdSha256:$vsha,DataBootstrapSha256:$sha}|to_entries|map({Key:.key,Value:.value}))}]}' > "$work/snapshot.json"
}
refresh
jq -n --slurpfile q "$work/source.json" --arg sha "$(qualification_sha_file "$work/source.json")" --arg vsha "$(printf source-v1 | qualification_sha_text)" \
  '{schemaVersion:3,databaseBootstrap:"snapshot",datasetManifestSha256:$q[0].dataset.manifestSha256,semanticAttestationSha256:$q[0].verification.semanticAttestationSha256,
    verification:{mode:"approved-snapshot",source:{key:"data-bootstrap/lab-source/dataset-qualification.json",sha256:$sha,versionIdSha256:$vsha}}}' > "$work/runtime.json"
aws() {
  case " $* " in
    *' rds describe-db-snapshots '*) cat "$work/snapshot.json" ;;
    *' s3api head-object '*) printf '%s\n' "$fake_version" ;;
    *' s3api get-object '*) cp "$work/source.json" "${!#}" ;;
    *) return 1 ;;
  esac
}
verify_snapshot_receipt_parity # Preflight has no runtime or app dependency.
for config in '{"appImage":"old","cache":false,"asg":1}' '{"appImage":"new","cache":true,"asg":4}'; do
  printf '%s\n' "$config" > "$work/readiness.json"
  verify_snapshot_receipt_parity "$work/runtime.json" "$work/readiness.json"
done
fake_version=changed-v2
if (verify_snapshot_receipt_parity); then fail 'reused changed source version'; fi
fake_version=source-v1
printf ' ' >> "$work/source.json"
if (verify_snapshot_receipt_parity); then fail 'reused changed source content'; fi
refresh
jq '.verification.mode="approved-snapshot"' "$work/source.json" > "$work/changed.json"
mv "$work/changed.json" "$work/source.json"; refresh
if (verify_snapshot_receipt_parity); then fail 'inherited proof was accepted as initial qualification'; fi
database_bootstrap=dump
verify_snapshot_receipt_parity "$work/missing.json"
printf '%s\n' 'snapshot reuse tests passed; experiment configuration is independent of dataset approval'
