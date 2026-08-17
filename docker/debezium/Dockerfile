# Multi-architecture Debezium Kafka Connect image.
# The connector config itself is mounted and registered by a one-shot init service.

FROM alpine:3.19 AS downloader

RUN apk add --no-cache curl tar

ENV DEBEZIUM_VERSION=2.6.1.Final

RUN mkdir -p /plugins/debezium-mysql && \
    curl -fSL https://repo1.maven.org/maven2/io/debezium/debezium-connector-mysql/${DEBEZIUM_VERSION}/debezium-connector-mysql-${DEBEZIUM_VERSION}-plugin.tar.gz | \
    tar -xzC /plugins/debezium-mysql --strip-components=1

FROM apache/kafka:3.7.0

USER root

COPY --from=downloader /plugins /opt/kafka/connect-plugins

COPY connect-distributed.properties /opt/kafka/config/connect-distributed.properties

USER appuser

EXPOSE 8083

CMD ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config/connect-distributed.properties"]
