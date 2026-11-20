FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends sqlite3 curl && rm -rf /var/lib/apt/lists/*
# The two JVMs do not run as root (see docker/entrypoint.sh). A fixed id, so that the owner of the files on the data volume is the same in
# every image.
RUN groupadd --system --gid 10001 ssbudget && useradd --system --uid 10001 --gid ssbudget --no-create-home --shell /usr/sbin/nologin ssbudget
RUN mkdir -p /data

WORKDIR /opt/docker

# Metabase is bundled by default and the entrypoint starts it whenever this jar is present, so an
# image built here serves the Analytics page with no further configuration. Build with
# --build-arg WITH_ANALYTICS=false (./build.sh --no-analytics) for an app-only image ~630MB smaller.
# Metabase is a plain jar on top of the JRE we already have, so this stays architecture-neutral —
# worth knowing, because there is no official arm64 Metabase image.
ARG WITH_ANALYTICS=true
ARG METABASE_VERSION=v0.63.16.1
# The SHA-256 of that jar. The build stops when the download is not this file: the jar runs next to the budget data, so a file that was
# changed on the way, or replaced at the source, must not get into the image. Change the two values together.
ARG METABASE_SHA256=a56b22bc42a292e1f7321dea8c0f74546e180b4377ec0f9cbe2a8ef9f1cd01b7
RUN if [ "$WITH_ANALYTICS" = "true" ]; then \
      mkdir -p /opt/metabase && \
      curl -fL -o /opt/metabase/metabase.jar \
        "https://downloads.metabase.com/${METABASE_VERSION}/metabase.jar" && \
      echo "${METABASE_SHA256}  /opt/metabase/metabase.jar" | sha256sum -c - && \
      chown -R ssbudget:ssbudget /opt/metabase; \
    fi

# Copy pre-built backend and frontend (run ./build.sh first)
COPY backend/target/universal/stage/ ./
# sbt writes the start script with mode 744: only its owner can run it. The app runs as the service user, and the files stay root's, so
# that the app cannot change its own program.
RUN chmod 755 /opt/docker/bin/ssbudget
COPY frontend/dist/ ./static/
COPY docker/entrypoint.sh /opt/docker/entrypoint.sh
# The commit this image was built from (written by ./build.sh); /api/health reports it.
COPY docker/VERSION /opt/docker/VERSION

ENV SSBUDGET_PORT=8080
ENV SSBUDGET_DB_PATH=/data/ssbudget.db
ENV SSBUDGET_STATIC_DIR=/opt/docker/static

EXPOSE 8080

# curl, not `wget --spider`: --spider sends a HEAD, which the GET-only health route rejects, so the
# container reported itself unhealthy while serving fine.
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD curl -fsS http://localhost:8080/api/health || exit 1

ENTRYPOINT ["/opt/docker/entrypoint.sh"]
