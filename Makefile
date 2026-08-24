.PHONY: aws-up aws-status aws-switch aws-down aws-discovery

aws-up:
	AWS_REGION=ap-northeast-2 \
	MODE="$(MODE)" POLICY="$(POLICY)" IMAGE_DIGEST="$(IMAGE_DIGEST)" \
	DATASET_RELEASE="$(DATASET_RELEASE)" REQUEST_TARGET="$(REQUEST_TARGET)" \
	TTL_HOURS="$(or $(TTL_HOURS),6)" KEEP_ON_FAILURE="$(or $(KEEP_ON_FAILURE),false)" \
	AMI_ID="$(AMI_ID)" OCI_ORIGIN_IPV4="$(OCI_ORIGIN_IPV4)" \
	RDS_ENGINE_VERSION="$(RDS_ENGINE_VERSION)" CACHE_ENABLED="$(or $(CACHE_ENABLED),true)" \
	LOAD_GENERATOR_ENABLED="$(or $(LOAD_GENERATOR_ENABLED),true)" \
	BUNDLE_COMMIT="$(BUNDLE_COMMIT)" DATABASE_BOOTSTRAP="$(or $(DATABASE_BOOTSTRAP),dump)" \
	RDS_SNAPSHOT_IDENTIFIER="$(RDS_SNAPSHOT_IDENTIFIER)" RUN_ID="$(RUN_ID)" \
	infra/aws/scripts/aws-lab.sh up

aws-status:
	AWS_REGION=ap-northeast-2 infra/aws/scripts/aws-lab.sh status

aws-switch:
	AWS_REGION=ap-northeast-2 TARGET="$(TARGET)" RUN_ID="$(RUN_ID)" \
	infra/aws/scripts/aws-lab.sh switch

aws-down:
	AWS_REGION=ap-northeast-2 RUN_ID="$(RUN_ID)" FORCE="$(or $(FORCE),false)" \
	infra/aws/scripts/aws-lab.sh down

aws-discovery:
	AWS_REGION=ap-northeast-2 RUN_ID="$(RUN_ID)" TARGET="$(or $(TARGET),accommodation-detail)" \
	RATE="$(RATE)" DURATION="$(DURATION)" WARMUP_DURATION="$(or $(WARMUP_DURATION),10s)" \
	MIN_COMPLETED_SAMPLES="$(MIN_COMPLETED_SAMPLES)" ROUND="$(ROUND)" RUN_ORDER="$(RUN_ORDER)" \
	APP_COMMIT="$(APP_COMMIT)" EXPECTED_SQL_CALLS_PER_REQUEST="$(EXPECTED_SQL_CALLS_PER_REQUEST)" \
	RUN_LABEL="$(RUN_LABEL)" OCI_ORIGIN_IPV4="$(OCI_ORIGIN_IPV4)" \
	load-test/k6/traffic/run-aws-discovery.sh
