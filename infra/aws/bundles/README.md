# AWS service bundles

> 2026-10-17: 앱 성능 측정은 종료했다. 현재 앱은 실험 전용 프로필과 비교 API를 제공하지 않는다.
> 아래 리소스 관리 계약은 기존 데이터셋과 호환되는 고정 이미지를 대상으로 보존한다.
> V27 실행기는 `SPRING_FLYWAY_TARGET=27`을 환경에 명시해 앱 프로필과 무관하게 스키마를 보호한다.
> 현재 앱을 과거 실험 이미지 대신 사용하지 않는다. 신규 측정 도구는 별도로 구성한다.

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
