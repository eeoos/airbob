FROM eclipse-temurin:21-jre

WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-Duser.timezone=UTC"
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
