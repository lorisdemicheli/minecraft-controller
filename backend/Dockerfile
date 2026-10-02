# syntax=docker/dockerfile:1

# ---- build -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies first, so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped by default to keep the image build fast (run them with
# `./mvnw verify` or build with --build-arg SKIP_TESTS=false).
ARG SKIP_TESTS=true
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q package -DskipTests=${SKIP_TESTS} \
 && cp target/*.jar /app.jar

# ---- runtime -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

# Same UID/GID as the Minecraft server pods (itzg default) and as the chart's
# securityContext, so files written to the shared volume have matching owners.
RUN mkdir -p /app /data && chown 1000:1000 /app /data
WORKDIR /app
COPY --from=build --chown=1000:1000 /app.jar /app/app.jar

USER 1000:1000
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
