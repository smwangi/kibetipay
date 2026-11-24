# syntax=docker/dockerfile:1
# KibetiPay API image. Build from the repository root:  docker build -t kibetipay-api .
# Runs with the prod profile; every secret and endpoint comes from the environment
# (see application-prod.yml and observability/collector/README.md).

# ---- build: compile and package the Spring Boot jar ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q dependency:go-offline
COPY src ./src
# Tests run in CI before an image is built; the image build only packages.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -q package -DskipTests \
 && java -Djarmode=tools -jar target/kibetipay-*.jar extract --layers --launcher --destination /extracted

# ---- runtime: JRE only, non-root, dependency layers first so they cache across releases ----
FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 kibetipay \
 && useradd --system --uid 10001 --gid kibetipay --no-create-home --shell /usr/sbin/nologin kibetipay
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080 8081
# 8080: API (route publicly). 8081: actuator, internal only: probes at /actuator/health/liveness and
# /actuator/health/readiness, metrics at /actuator/prometheus.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
