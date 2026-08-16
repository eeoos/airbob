ARG APP_BASE_IMAGE=docker.io/library/eclipse-temurin@sha256:371da296b8cb74c7e53fbe7083d5374befc0011b493231d97d45fa789915e434
FROM ${APP_BASE_IMAGE}

WORKDIR /app
COPY build/libs/airbob.jar app.jar
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
