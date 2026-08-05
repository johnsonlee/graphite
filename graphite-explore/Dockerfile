# syntax=docker/dockerfile:1.7
#
# Runtime-only image — no build stage needed.
# The unified Graphite CLI shadow jar is pre-built by CI and downloaded from
# the GitHub Release before building this runtime image.
#
# Usage:
#   docker build -f graphite-explore/Dockerfile \
#     -t graphite-explore .
#
#   docker run --rm -p 8080:8080 -v /path/to/graph:/data graphite-explore

FROM eclipse-temurin:17-jre-jammy

ARG VERSION=0.0.0-dev

LABEL org.opencontainers.image.title="graphite-explore" \
      org.opencontainers.image.description="Interactive web visualization for Graphite static analysis graphs" \
      org.opencontainers.image.source="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.url="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

RUN groupadd --system --gid 1000 graphite \
 && useradd  --system --uid 1000 --gid graphite --home /home/graphite --create-home graphite

WORKDIR /app
COPY graphite.jar /app/graphite.jar

USER graphite:graphite

ENV JAVA_TOOL_OPTIONS="-Xmx4g"

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["java", "-jar", "/app/graphite.jar", "serve"]
CMD ["--id", "app", "/data"]
