# ARM64 호환 Debezium Kafka Connect
# Apache Kafka 이미지 기반으로 Debezium 플러그인 설치
FROM apache/kafka:3.7.0

USER root

# 필요한 도구 설치
RUN microdnf install -y curl tar gzip && \
    microdnf clean all

# Debezium connector 다운로드
ENV DEBEZIUM_VERSION=2.6.1.Final
ENV KAFKA_CONNECT_PLUGINS_DIR=/opt/kafka/connect-plugins

RUN mkdir -p ${KAFKA_CONNECT_PLUGINS_DIR}/debezium-mysql && \
    curl -fSL https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/${DEBEZIUM_VERSION}/debezium-connector-mysql-${DEBEZIUM_VERSION}-plugin.tar.gz | \
    tar -xzC ${KAFKA_CONNECT_PLUGINS_DIR}/debezium-mysql --strip-components=1

# Connect 설정 파일 복사
COPY connect-distributed.properties /opt/kafka/config/connect-distributed.properties

# 권한 설정
RUN chown -R appuser:appuser ${KAFKA_CONNECT_PLUGINS_DIR} && \
    chown appuser:appuser /opt/kafka/config/connect-distributed.properties

USER appuser

EXPOSE 8083

# Kafka Connect 분산 모드 실행
CMD ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config/connect-distributed.properties"]
