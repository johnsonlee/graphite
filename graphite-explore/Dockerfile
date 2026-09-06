# syntax=docker/dockerfile:1.7
# Run scripts/native/stage_docker.py on the checksum-verified Linux release
# archives first. The runtime contains the native server and its notices only.
# No build/query functionality is removed from the separate unified JVM CLI.
FROM scratch

ARG TARGETARCH
ARG VERSION=0.0.0-dev

LABEL org.opencontainers.image.title="graphite-explore" \
      org.opencontainers.image.description="Interactive web visualization for Graphite static analysis graphs" \
      org.opencontainers.image.source="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.url="https://github.com/johnsonlee/graphite" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${VERSION}"

COPY native-runtime/rootfs/ /
COPY --chown=1000:1000 native-runtime/writable/ /
COPY --chmod=0755 native-runtime/linux-${TARGETARCH}/graphite-server /app/graphite-server
COPY native-runtime/linux-${TARGETARCH}/LICENSE native-runtime/linux-${TARGETARCH}/VERSION /app/
COPY native-runtime/linux-${TARGETARCH}/licenses/ /app/licenses/

WORKDIR /app
USER 1000:1000
ENV HOME="/home/graphite"
EXPOSE 8080
VOLUME ["/data"]
STOPSIGNAL SIGTERM
ENTRYPOINT ["/app/graphite-server", "serve"]
CMD ["--id", "app", "/data"]
