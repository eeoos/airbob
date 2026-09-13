# Fixed Global B AWS image inputs

`global-b-runtime.json` keeps the application and the nine general infrastructure
tags at `479ba02d54954af46f1f5633aceddeb69e321ff3`. The application already has an
approved digest and is only read. Missing general tags are built from regular Git
blobs at that exact main ancestor. The general Debezium image therefore retains
its original 2.6.1 contract. B services use the separate connector described below.

The B connector uses the existing `airbob-infra/debezium` repository and the tag
`global-b-<executionCommit>`. Its source must be committed on reviewed main. It
contains Debezium MySQL 3.0.8.Final, with the plugin archive and connector JAR
SHA256 pinned separately, and the existing Kafka base containing Connect 3.7.0.
Both Linux architectures must carry the expected source revision. The amd64
verification container has no network and checks the exact plugin/JMX hashes and
the Connect/Kafka runtime JAR names. This is image evidence; the B host separately
checks the live Connect REST version, connector plugin version, and connector JAR
hash before CDC starts.

The closed entry point is `.github/workflows/aws-growth-b-images.yml`, dispatched
on main with only an `expected_execution_commit` equality guard. A main commit
that changed after review stops before OIDC and publication. It uses the protected `aws-image-publisher`
environment and its OIDC role in account `942632789808`. There is no alternate
checkout, role, registry, region, bucket, runtime commit, or publication tag input.
It cannot build the application, create ECR repositories, change IAM, publish to
GHCR, or deploy OCI. The two-hour image-publisher session is unrelated to the
unchanged six-hour Lab operator session contract.

After review and merge, the operator may dispatch this workflow on main. It runs
offline contract tests first, supplies the nine general images and B connector,
then checks all ten general references plus the B reference before publishing
the fixed 479 configuration bundle. Successful job outputs are public GitHub
artifacts named `global-b-legacy-<variable>-<executionCommit>`,
`global-b-debezium-<executionCommit>`, and
`global-b-aws-image-inputs-<executionCommit>`.

The final `receipt.json` distinguishes `runtimeCommit` from `executionCommit`,
records execution and build source file SHA256 values, all ten general immutable
references, the B-only `debezium` fragment consumed by the service manifest, and
the configuration completion marker's VersionId/SHA256/size. Re-running the same
main commit reuses matching immutable tags and bytes; mismatches stop the run.

The configuration archive contains the same nineteen files as the fixed source.
File order is fixed, file timestamps use the runtime Git commit time, and gzip
time, UID/GID, names, and modes are canonical. The ordinary current-HEAD packager
uses the same archive writer, so a new checkout reproduces identical bytes. Old
immutable bundle keys with different bytes are never replaced.

The existing image-publisher role has S3 GetObject/PutObject, without ListBucket
or GetObjectVersion. A 403 HEAD is explicitly unresolved; it permits only a
create-only `If-None-Match: *` PUT. A 412 race must resolve to matching readable
bytes. Every PUT/HEAD VersionId is matched against the GET response's VersionId
and full body SHA256, before the manifest is written last. The receipt labels
this method accurately. The operator's separate read-only audit should also read
the explicit VersionIds and complete prefix history before infrastructure use.

`infra-images.yml` still accepts its original explicit manual dispatch. On push,
its existing ECR/GHCR jobs now require a change to actual legacy Dockerfile input:
the four Dockerfiles, their Docker ignore files, the copied Connect properties,
or the legacy release JSON. Config-only, packager, workflow-gate, topic-admin,
and new B files do not publish legacy images. Tests fail if future local COPY/ADD
inputs are missing from this classification. `cd.yml` retains its existing scope;
the AWS-only change must be reviewed separately from OCI operational changes.

Local verification:

```bash
python3 -m unittest discover -s infra/aws/tests -p 'test_growth_b_images.py'
python3 -m unittest discover -s infra/aws/tests -p 'test_image_publication_scope.py'
bash infra/aws/tests/package-service-bundles-test.sh
bash infra/aws/tests/service-bundle-publisher-test.sh
bash infra/aws/tests/image-publication-test.sh
```

The focused image tests read real fixed-commit Git blobs, so the checkout must
include the fixed runtime ancestor. The protected workflow uses full history.
