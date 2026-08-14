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

AWS provisioning and bundle publication are handled by separate plans.
