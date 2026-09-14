ARG APP_BASE_IMAGE=docker.io/library/eclipse-temurin@sha256:d35199d74a3b2dff1bfb435d9adde4ef974e74a7d2a4fcb3079063c55926edb5
FROM ${APP_BASE_IMAGE}

WORKDIR /app
COPY build/libs/airbob.jar app.jar
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
