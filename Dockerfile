# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk-jammy@sha256:742daacb9316be4b6fc60258f6e4ac2a11882f6c597373c92290a57bfad0d2e0 AS build

WORKDIR /workspace
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY gradle/wrapper/ ./gradle/wrapper/
RUN chmod +x gradlew
COPY src/main/ ./src/main/

# CI runs the complete test suite separately; packaging needs no Docker socket or DB.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain && \
    cp build/libs/*.jar /workspace/app.jar

FROM eclipse-temurin:21-jre-jammy@sha256:affcab5356e1270ac35d2361ee83135af2f03e8d580eca78a6d5d6fdbf048db0 AS runtime

RUN groupadd --system --gid 10001 igilro && \
    useradd --system --uid 10001 --gid 10001 --home-dir /app --shell /usr/sbin/nologin igilro
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar ./app.jar

ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    TZ=UTC
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
