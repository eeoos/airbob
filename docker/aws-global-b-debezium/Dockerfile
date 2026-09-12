# B-only image. The legacy and OCI image definitions have separate release contracts.
ARG ALPINE_IMAGE=docker.io/library/alpine@sha256:6baf43584bcb78f2e5847d1de515f23499913ac9f12bdf834811a3145eb11ca1
ARG KAFKA_BASE_IMAGE=docker.io/apache/kafka@sha256:41c20d65b0a2180b9deca056d47540771752e69fe9225d5748dd65a25a9675d7
FROM ${ALPINE_IMAGE} AS downloader
RUN apk add --no-cache ca-certificates curl tar
RUN mkdir -p /downloads /plugins/debezium-mysql /opt/jmx \
    && curl -fsSL --retry 3 -o /downloads/debezium.tar.gz \
      https://repo.maven.apache.org/maven2/io/debezium/debezium-connector-mysql/3.0.8.Final/debezium-connector-mysql-3.0.8.Final-plugin.tar.gz \
    && echo '14b7c50782b162f1a1a17706ebc05c424995e681ffa1ae7d3a1e1a91345d871a  /downloads/debezium.tar.gz' | sha256sum -c - \
    && tar -xzf /downloads/debezium.tar.gz -C /plugins/debezium-mysql --strip-components=1 \
    && echo '6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59  /plugins/debezium-mysql/debezium-connector-mysql-3.0.8.Final.jar' | sha256sum -c - \
    && curl -fsSL --retry 3 -o /opt/jmx/jmx_prometheus_javaagent.jar \
      https://github.com/prometheus/jmx_exporter/releases/download/1.6.0/jmx_prometheus_javaagent-1.6.0.jar \
    && echo 'a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e  /opt/jmx/jmx_prometheus_javaagent.jar' | sha256sum -c -

FROM ${KAFKA_BASE_IMAGE}
USER root
COPY --from=downloader /plugins /opt/kafka/connect-plugins
COPY --from=downloader /opt/jmx/jmx_prometheus_javaagent.jar /opt/jmx/jmx_prometheus_javaagent.jar
RUN test -s /opt/kafka/libs/connect-runtime-3.7.0.jar \
    && test -s /opt/kafka/libs/kafka_2.13-3.7.0.jar \
    && chmod 0444 /opt/jmx/jmx_prometheus_javaagent.jar
USER appuser
EXPOSE 8083
# The verified AWS service bundle mounts this file before a worker can start.
CMD ["/opt/kafka/bin/connect-distributed.sh", "/opt/kafka/config/connect-distributed.aws.properties"]
