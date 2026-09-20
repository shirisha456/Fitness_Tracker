# syntax=docker/dockerfile:1

# ---- Stage 1: build ---------------------------------------------------------
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

COPY src/ src/

# A BuildKit cache mount for the Maven repository, rather than a separate
# `dependency:go-offline` layer. go-offline resolves far more than the build needs
# (every plugin's full dependency graph) and fails on artifacts that do not exist,
# which is exactly how the first version of this file broke. The cache mount keeps
# rebuilds fast without pretending dependency resolution is a separate concern.
#
# Tests are skipped here deliberately: they use Testcontainers, which needs a Docker
# daemon that is not available inside a build container. CI runs `./mvnw verify` on
# the host before this image is ever built.
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B clean package -DskipTests \
    && cp target/*.jar /build/app.jar

# ---- Stage 2: runtime -------------------------------------------------------
FROM eclipse-temurin:21-jre-jammy AS runtime

# curl serves the container healthcheck; the JRE image ships no shell tooling.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Never run as root. A fixed uid/gid keeps ownership predictable if a volume is
# ever mounted.
RUN groupadd --system --gid 1001 app \
    && useradd --system --uid 1001 --gid app --create-home app

WORKDIR /app
COPY --from=build --chown=app:app /build/app.jar app.jar

USER app

EXPOSE 8000

# MaxRAMPercentage rather than a fixed -Xmx: the JVM sizes its heap from the
# container's cgroup limit, so one image behaves correctly on a small dev box and a
# larger production instance. ExitOnOutOfMemoryError turns an OOM into a restart
# rather than a wedged JVM the orchestrator still believes is alive.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Liveness uses /api/v1/health — the application's own compatibility contract, shared
# with the Python backend — not Actuator. start-period covers JVM boot plus Flyway.
HEALTHCHECK --interval=10s --timeout=5s --retries=5 --start-period=90s \
    CMD curl -fsS http://localhost:8000/api/v1/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
