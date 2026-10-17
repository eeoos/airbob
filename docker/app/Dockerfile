# Temurin 21.0.12.1+1 JRE on Ubuntu 24.04 (noble), multi-platform index.
ARG APP_BASE_IMAGE=docker.io/library/eclipse-temurin@sha256:b8b0f4780402db75dc6a734c3cdfa733b119d38ac7dd7bc03bf1d4bc743f6931
FROM ${APP_BASE_IMAGE}

WORKDIR /app
COPY build/libs/airbob.jar app.jar
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
