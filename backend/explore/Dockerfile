# syntax=docker/dockerfile:1.7
#
# Runtime-only image for `graphite serve`: the graphite binary from the GitHub Release,
# one per architecture, on a distroless base. No JVM: this image serves and queries
# graphs; building them is the JVM frontend's job (`graphite build` on a host with a JDK,
# or the graphite.jar release asset).
#
# Usage:
#   # release-assets/linux/<amd64|arm64>/graphite are the extracted release binaries
#   docker build -f backend/explore/Dockerfile -t graphite-explore .
#   docker run --rm -p 8080:8080 -v /path/to/graph:/data graphite-explore
#
# The entrypoint is `graphite serve`, so every `graphite-explore` / `graphite.jar serve`
# argument works unchanged: `docker run ... graphite-explore --graph a:/data/a --port 8080`.

FROM gcr.io/distroless/cc-debian12:nonroot

ARG VERSION=0.0.0-dev
ARG TARGETARCH

LABEL org.opencontainers.image.title="graphite-explore" \
      org.opencontainers.image.description="Interactive web visualization for Graphite static analysis graphs" \
      org.opencontainers.image.source="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.url="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

WORKDIR /app
COPY --chmod=0755 release-assets/linux/${TARGETARCH}/graphite /app/graphite

EXPOSE 8080
VOLUME ["/data"]

ENTRYPOINT ["/app/graphite", "serve"]
CMD ["--id", "app", "/data"]
