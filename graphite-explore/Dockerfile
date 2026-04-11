# syntax=docker/dockerfile:1.7
#
# Build context must be the repository root, e.g.
#   docker build -f graphite-explore/Dockerfile -t graphite-explore .
#
# The image runs the graphite-explore web visualizer. Mount a saved graph
# directory at /data and expose the HTTP port (defaults to 8080):
#   docker run --rm -p 8080:8080 -v /path/to/graph:/data graphite-explore /data

# ---- Build stage ---------------------------------------------------------
# Pin to BUILDPLATFORM so the gradle build runs once natively even when
# buildx targets multiple architectures. The resulting shadow jar is
# architecture independent.
FROM --platform=$BUILDPLATFORM gradle:8.10.2-jdk17 AS builder

WORKDIR /workspace

# Copy gradle wrapper and root build scripts first for better layer caching.
COPY gradlew gradlew.bat ./
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties ./

# settings.gradle.kts references every module, so all of them must exist in
# the build context even though :graphite-explore only depends on a subset.
COPY graphite-core graphite-core
COPY graphite-cypher graphite-cypher
COPY graphite-sootup graphite-sootup
COPY graphite-webgraph graphite-webgraph
COPY graphite-query graphite-query
COPY graphite-explore graphite-explore

ARG VERSION=0.0.0-dev
RUN ./gradlew :graphite-explore:shadowJar --no-daemon -Pversion=${VERSION}

# ---- Runtime stage -------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy AS runtime

ARG VERSION=0.0.0-dev

LABEL org.opencontainers.image.title="graphite-explore" \
      org.opencontainers.image.description="Interactive web visualization for Graphite static analysis graphs" \
      org.opencontainers.image.source="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.url="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

# Run as a non-root user.
RUN groupadd --system --gid 1000 graphite \
 && useradd  --system --uid 1000 --gid graphite --home /home/graphite --create-home graphite

WORKDIR /app
COPY --from=builder /workspace/graphite-explore/build/libs/graphite-explore.jar /app/graphite-explore.jar

USER graphite:graphite

# Override at runtime, e.g. -e JAVA_TOOL_OPTIONS="-Xmx8g"
ENV JAVA_TOOL_OPTIONS="-Xmx4g"

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["java", "-jar", "/app/graphite-explore.jar"]
# Default to loading the graph from the /data volume. Override by passing
# different args, e.g. `docker run graphite-explore --help`.
CMD ["/data"]
