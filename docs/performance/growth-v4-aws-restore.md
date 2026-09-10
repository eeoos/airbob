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

At this stage `prepare` qualifies the private RDS restore and optional HTTP/cache
reads. ALB, ASG, CDC, payment writes, rolling current inventory initialization,
and the native S3 Elasticsearch snapshot are separate subsequent checks; this
receipt makes no claim that those workloads ran. The initial AWS envelope has
`search.enabled=false`. Bind the existing search companion in a new immutable
revision before qualifying the combined final RDS/search environment.

## Preparation artifacts

Use `publish-dataset-release.sh` with `growth-v4-integration` to publish an
already validated source. `GROWTH_PUBLICATION_RECEIPT` identifies a new local
receipt. The shared publisher-role and bucket-policy gates remain mandatory.

Use `assemble-growth-v4-aws-release.py` with that source, its verified publication
receipt, and the current migration directory. The output directory contains one
new AWS manifest; source artifacts remain in their original S3 prefix. Publish
it through `publish-dataset-release.sh` with `growth-v4-aws-qualification`, and
pin the returned manifest VersionId when invoking `aws-lab.sh prepare`.

The execution checkout must be clean and committed. Continue development in a
different worktree while a run uses its frozen execution commit. Use the same
revision's operator for teardown. Do not change GitHub deployment branch
restrictions or merge an execution branch into main as an implicit prerequisite.

## Verification before real RDS execution

The v4 Python boundary suite passes on Python 3.9 and checks source/version
admission, archive traversal/link rejection, populated-schema refusal,
full-content parity, TLS requirements, process-group termination, and cleanup
error preservation. Terraform mock tests cover v4 and existing v3 preparation,
the 45 KB SSM document budget, app isolation, and MySQL 8.4 parameter-family
selection. Real RDS execution evidence is recorded separately per run.
