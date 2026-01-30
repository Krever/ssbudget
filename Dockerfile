FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends sqlite3 && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /data

WORKDIR /opt/docker

# Copy pre-built backend and frontend (run ./build.sh first)
COPY backend/target/universal/stage/ ./
COPY frontend/dist/ ./static/

ENV SSBUDGET_PORT=8080
ENV SSBUDGET_DB_PATH=/data/ssbudget.db
ENV SSBUDGET_STATIC_DIR=/opt/docker/static

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q --spider http://localhost:8080/api/health || exit 1

ENTRYPOINT ["bin/ssbudget"]
