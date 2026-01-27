# ARM64 호환 Debezium Kafka Connect
# Multi-stage 빌드: apache/kafka에 패키지 매니저가 없어서 Alpine에서 다운로드

# Stage 1: Debezium 플러그인 다운로드
FROM alpine:3.19 AS downloader

RUN apk add --no-cache curl tar

ENV DEBEZIUM_VERSION=2.6.1.Final

RUN mkdir -p /plugins/debezium-mysql && \
    curl -fSL https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/${DEBEZIUM_VERSION}/debezium-connector-mysql-${DEBEZIUM_VERSION}-plugin.tar.gz | \
    tar -xzC /plugins/debezium-mysql --strip-components=1

# Stage 2: Kafka Connect 실행 이미지
FROM apache/kafka:3.7.0

USER root

# 다운로드한 플러그인 복사
COPY --from=downloader /plugins /opt/kafka/connect-plugins

# 설정 파일 복사
COPY connect-distributed.properties /opt/kafka/config/connect-distributed.properties

USER appuser

EXPOSE 8083

CMD ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config/connect-distributed.properties"]
