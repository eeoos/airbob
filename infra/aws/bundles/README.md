# AWS service bundles

AWS service bundles are configuration artifacts only. They describe how a
service is configured on a host; they do not include container images or runtime
secrets.

Every image is supplied externally as an immutable digest reference. Runtime
secrets belong in root-owned runtime environment files outside the published
archive. Verify a bundle before publishing it:

```bash
infra/aws/scripts/verify-service-bundle.sh <compose-file> <image-env-file>
```

Package and publish a current-HEAD bundle with:

```bash
infra/aws/scripts/package-service-bundles.sh <40-char-commit> <private-output-dir>
AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/publish-service-bundles.sh <40-char-commit> \
  airbob-performance-lab-bundles-942632789808
```

The S3 publisher uses immutable keys below `service-bundles/<commit>/`, checks
existing bytes for idempotency, and writes the release manifest last as the
completion marker. Provisioning consumes that marker and remains a separate
Terraform operation. No live publication has been executed from this branch.
