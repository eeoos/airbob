# V28 growth dataset AWS restore

The `growth-v4-aws-qualification` envelope references the existing v4 source
objects by S3 key, VersionId, SHA-256, and byte length. It does not regenerate
reviews, names, images, reservations, or inventory, and does not rewrite the
source release's historical qualification claims.

## Restore contract

- Require MySQL 8.4.11 and the complete V1–V28 migration inventory.
- Refuse to import into a schema that already contains tables.
- Restore the sealed compressed dump as a bounded stream. On interruption or
  timeout, terminate the entire import process group, including its children.
- Use verified RDS TLS for both the SQL client and the sealed Java verifier.
- Compare every row and DDL in all 32 tables, including historical occupied
  inventory. Equal row counts alone do not qualify a restore.
- When requested, run the immutable published application privately on the data
  host with Flyway target 28. Verify sealed HTTP reads with temporary synthetic
  Redis sessions and repeat five accommodation details with cache off and on.
- Recheck all DB rows and DDL after the application probe. The probe does not
  alter member passwords. Only the dedicated accommodation cache is flushed.
- Publish versioned, hash-verified evidence only after successful verification.

## Selected inputs and execution windows

The small release `korea-growth-v4-c8c75883908247ef` contains 428 accommodations,
10,268 reservations, and 26,046 occupied inventory rows. Its existing 36 files
total 21,942,182 bytes. This fits the existing one-hour credential window for
`prepare`: 1,800 seconds for the operator command and 900 seconds reserved for
failure cleanup, with the existing credential and lease safety margins.

The selected final release `korea-growth-v4-778895bd2bd73be4` contains 2,000,268
reservations (2,000,000 base rows plus 268 boundary fixtures), 15,954
accommodations, and 4,392,547 occupied inventory rows. It requires the standard
six-hour execution window and 100 GiB of RDS storage. It must never be admitted
through the small window by increasing that window's population or file limits.

`prepare` qualifies the private RDS restore and optional HTTP/cache reads. The
small envelope has `search.enabled=false`; the final envelope must bind the
existing `korea-growth-v4-778895bd2bd73be4-search-r1` companion and native seal.
Before resource creation, the operator verifies current native S3 object
membership and VersionIds. After RDS parity, the private Elasticsearch host
restores the snapshot with its EC2 role and a read-only repository, compares all
14,343 document bodies, mappings, and UID/ID pairs, and publishes one write alias.
The repository is unregistered after verification; static S3 keys are never
installed. Existing target indices are never implicitly deleted.

ALB, ASG, CDC, payment writes, and rolling current inventory initialization are
separate subsequent checks. This preparation receipt makes no claim that those
workloads ran or that end-to-end service performance has been measured.

## Preparation artifacts

Use `publish-dataset-release.sh` with `growth-v4-integration` to publish an
already validated source. `GROWTH_PUBLICATION_RECEIPT` identifies a new local
receipt. The shared publisher-role and bucket-policy gates remain mandatory.

Use `assemble-growth-v4-aws-release.py` with that source, its verified publication
receipt, and the current migration directory. The output directory contains one
new AWS manifest; source artifacts remain in their original S3 prefix. Publish
it through `publish-dataset-release.sh` with `growth-v4-aws-qualification`, and
pin the returned manifest VersionId when invoking `aws-lab.sh prepare`.

For the final source, pass `--search-directory` and `--search-publication` to the
assembler. Both refer to the existing verified companion and publication receipt.
The following completion markers were published and downloaded by exact version:

| Envelope | S3 manifest VersionId | SHA-256 |
| --- | --- | --- |
| `korea-growth-v4-c8c75883908247ef-aws-r1` | `zSibwCP0Sz0vubjG9iPpIV.m7ay3vy3b` | `de904b7f442956100d7af8ab6a8c58a79231f634c02dc9f5623ad057276a0639` |
| `korea-growth-v4-778895bd2bd73be4-aws-r1` | `myHfv8kJ6DawMPCljsTm5SFFCenxJ9Vt` | `a74b19a622d3855f7369bfb7a6ea43d3849f22d4db039fa6fe88df703e0b365f` |

Each is `datasets/<envelope>/manifest.json` in
`airbob-performance-lab-dataset-942632789808`. Source files and native snapshot
objects were not rewritten by envelope publication.

The execution checkout must be clean and committed. Continue development in a
different worktree while a run uses its frozen execution commit. Use the same
revision's operator for teardown. Do not change GitHub deployment branch
restrictions or merge an execution branch into main as an implicit prerequisite.
The shared workflow accepts explicit `rds_engine_version`,
`growth_app_read_qualification`, `growth_app_commit`, and
`growth_app_jar_sha256` inputs. This avoids changing persistent environment
defaults when qualifying MySQL 8.4.11 with the current published app. Large runs
retain the standard six-hour OIDC session and existing cleanup deadlines.

## Verification before real RDS execution

The v4 Python boundary suite passes on Python 3.9 and checks source/version
admission, archive traversal/link rejection, populated-schema refusal,
full-content parity, TLS requirements, process-group termination, and cleanup
error preservation. Terraform mock tests cover v4 and existing v3 preparation,
the 45 KB SSM document budget, app isolation, and MySQL 8.4 parameter-family
selection. Real RDS execution evidence is recorded separately per run.

## Actual small RDS qualification, 2026-09-10

Run `lab-v4-small-0910a`, execution commit
`7df239e23c83c878a0bbcd59d20a111a348a0593`, completed successfully at
13:52:35 UTC. MySQL resource ID: `db-MIVFLHNQYCWELLJZUKTZP7G4SA`.

- 32 tables and all 167,432 rows matched their source data and DDL, including
  10,268 reservations and 26,046 occupied inventory rows.
- Streamed import took 11.055 seconds; the first complete fingerprint comparison
  took 4.950 seconds. These are small-restore timings, not service benchmarks or
  extrapolations for the final dataset.
- The unchanged published application at
  `c1d0a33eb7e9686a18fbc5c999bde74ed6d5ce35` passed all 66 sealed reads with cache
  disabled and again with cache enabled. Five repeated details agreed across
  both modes; dedicated cache key counts were 0 and 8 respectively.
- Every DB row and DDL matched again after the application probe.
- The qualification receipt is
  `data-bootstrap/lab-v4-small-0910a/dataset-qualification.json` in the evidence
  bucket, VersionId `F6jrNaqTjia1mwaq2OqJBreznsWLCHu4`. The full DB and app proofs
  were independently downloaded by exact version and verified locally.

The temporary small environment is torn down before starting the final run.
The final run's execution outcome is recorded separately; publication and mock
test success alone are not a final RDS qualification.
