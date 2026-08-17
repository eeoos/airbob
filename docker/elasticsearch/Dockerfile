ARG ELASTICSEARCH_BASE_IMAGE=docker.io/library/elasticsearch@sha256:e5b3511e96ee1c58e9027d9d06185be4e627074717b40f6145e860b39c851d6d
FROM ${ELASTICSEARCH_BASE_IMAGE}

RUN test -f /usr/share/elasticsearch/modules/repository-s3/plugin-descriptor.properties \
    && /usr/share/elasticsearch/bin/elasticsearch-plugin install --batch analysis-nori \
    && /usr/share/elasticsearch/bin/elasticsearch-plugin list | grep -Fx analysis-nori
