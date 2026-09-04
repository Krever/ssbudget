FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends sqlite3 curl && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /data

WORKDIR /opt/docker

# Metabase is bundled by default and the entrypoint starts it whenever this jar is present, so an
# image built here serves the Analytics page with no further configuration. Build with
# --build-arg WITH_ANALYTICS=false (./build.sh --no-analytics) for an app-only image ~630MB smaller.
# Metabase is a plain jar on top of the JRE we already have, so this stays architecture-neutral —
# worth knowing, because there is no official arm64 Metabase image.
ARG WITH_ANALYTICS=true
ARG METABASE_VERSION=v0.63.16.1
RUN if [ "$WITH_ANALYTICS" = "true" ]; then \
      mkdir -p /opt/metabase && \
      curl -fL -o /opt/metabase/metabase.jar \
        "https://downloads.metabase.com/${METABASE_VERSION}/metabase.jar"; \
    fi

# Copy pre-built backend and frontend (run ./build.sh first)
COPY backend/target/universal/stage/ ./
COPY frontend/dist/ ./static/
COPY docker/entrypoint.sh /opt/docker/entrypoint.sh

ENV SSBUDGET_PORT=8080
ENV SSBUDGET_DB_PATH=/data/ssbudget.db
ENV SSBUDGET_STATIC_DIR=/opt/docker/static

EXPOSE 8080

# curl, not `wget --spider`: --spider sends a HEAD, which the GET-only health route rejects, so the
# container reported itself unhealthy while serving fine.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

ENTRYPOINT ["/opt/docker/entrypoint.sh"]
