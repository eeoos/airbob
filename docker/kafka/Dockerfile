ARG ALPINE_IMAGE=docker.io/library/alpine@sha256:6baf43584bcb78f2e5847d1de515f23499913ac9f12bdf834811a3145eb11ca1
ARG KAFKA_BASE_IMAGE=docker.io/apache/kafka@sha256:41c20d65b0a2180b9deca056d47540771752e69fe9225d5748dd65a25a9675d7
FROM ${ALPINE_IMAGE} AS downloader

RUN apk add --no-cache ca-certificates curl

ARG JMX_EXPORTER_VERSION=1.6.0
ARG JMX_EXPORTER_SHA256=a95983fd96e865d2bcdf911cc500e7c82808c27ab9fd226bf96732b6c3d8c46e
RUN mkdir -p /opt/jmx \
    && curl -fsSL --retry 3 \
      -o /opt/jmx/jmx_prometheus_javaagent.jar \
      "https://github.com/prometheus/jmx_exporter/releases/download/${JMX_EXPORTER_VERSION}/jmx_prometheus_javaagent-${JMX_EXPORTER_VERSION}.jar" \
    && printf '%s  %s\n' "${JMX_EXPORTER_SHA256}" /opt/jmx/jmx_prometheus_javaagent.jar | sha256sum -c -

FROM ${KAFKA_BASE_IMAGE}

USER root
COPY --from=downloader /opt/jmx/jmx_prometheus_javaagent.jar /opt/jmx/jmx_prometheus_javaagent.jar
RUN test -s /opt/jmx/jmx_prometheus_javaagent.jar \
    && chmod 0444 /opt/jmx/jmx_prometheus_javaagent.jar
USER appuser
