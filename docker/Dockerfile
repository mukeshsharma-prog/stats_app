# Stats App — one Dockerfile, three images.
#
# Each application module is a separately deployable image (Q2, answered), but
# they are built from one multi-module Gradle project, so a single parameterised
# Dockerfile keeps the three builds honestly identical instead of letting them
# drift apart in three near-copies.
#
#   docker build -f docker/Dockerfile --build-arg MODULE=stats-writer .
#
# stats-common is NOT buildable this way — it is a shared library, not deployable.

# --- build -----------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Wrapper and build scripts first, on their own layer. Dependency resolution is
# the slow part of the build and it only depends on these files, so editing Java
# source does not re-download the world.
COPY gradlew ./
COPY gradle ./gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY stats-common/build.gradle.kts   ./stats-common/
COPY stats-input/build.gradle.kts    ./stats-input/
COPY stats-writer/build.gradle.kts   ./stats-writer/
COPY stats-api/build.gradle.kts      ./stats-api/
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies --quiet || true

COPY stats-common ./stats-common
COPY stats-input  ./stats-input
COPY stats-writer ./stats-writer
COPY stats-api    ./stats-api

# Declared after the COPYs so a change of MODULE does not invalidate them.
ARG MODULE
RUN test -n "$MODULE" || (echo "ERROR: --build-arg MODULE=<stats-input|stats-writer|stats-api> is required" >&2; exit 1)
RUN ./gradlew --no-daemon :${MODULE}:bootJar -x test \
    && cp ${MODULE}/build/libs/*.jar /workspace/app.jar

# --- runtime ---------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime

# curl is here for the compose healthcheck only. The JRE image ships without it,
# and a healthcheck that cannot run reports the container healthy forever.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Non-root. Nothing in these modules needs to write to the filesystem.
RUN groupadd --system stats && useradd --system --gid stats --no-create-home stats
USER stats

WORKDIR /app
COPY --from=build --chown=stats:stats /workspace/app.jar /app/app.jar

# Structured JSON to stdout, uid as correlation ID (D1). Docker collects stdout;
# nothing writes a log file.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
