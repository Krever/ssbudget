#!/usr/bin/env bash
set -euo pipefail

echo "=== Building backend ==="
sbt backend/stage

echo "=== Building frontend (Scala.js) ==="
sbt frontend/fullLinkJS

echo "=== Building frontend (Vite) ==="
cd frontend && npm install --no-audit --no-fund && npm run build
cd ..

echo "=== Building Docker image ==="
# Metabase ships in the image by default (~630MB bigger, wants a 2GB machine). Pass --no-analytics
# for an app-only image; the container then behaves exactly as it did before analytics existed.
WITH_ANALYTICS=true
case "${1:-}" in
  --no-analytics) WITH_ANALYTICS=false ;;
  --analytics | "") ;; # --analytics is now the default; still accepted so old habits keep working
  *) echo "usage: ./build.sh [--no-analytics]" >&2; exit 1 ;;
esac
docker build --build-arg "WITH_ANALYTICS=$WITH_ANALYTICS" -t ssbudget:latest .

echo "=== Build complete (analytics: $WITH_ANALYTICS) ==="
echo "Run:    docker run -p 8080:8080 -v ./data:/data ssbudget:latest"
echo "Deploy: fly deploy --local-only"
