#!/usr/bin/env bash
set -euo pipefail

echo "=== Building backend ==="
sbt backend/stage

echo "=== Building frontend (Scala.js) ==="
sbt frontend/fullLinkJS

echo "=== Building frontend (Vite) ==="
# SCALAJS_STAGE=full: bundle the fullLinkJS output from the step above (see frontend/vite.config.mjs).
cd frontend && npm ci --no-audit --no-fund && SCALAJS_STAGE=full npm run build
cd ..

echo "=== Stamping the version ==="
# The commit of this build, and "-dirty" when the working tree has changes that are not committed. The image copies the file, and
# /api/health reports it, so there is one place to see what is live.
VERSION="$(git rev-parse --short HEAD)"
[ -z "$(git status --porcelain)" ] || VERSION="$VERSION-dirty"
echo "$VERSION" > docker/VERSION
echo "version: $VERSION"

# Metabase ships in the image by default (~630MB bigger, wants a 2GB machine). Pass --no-analytics
# for an app-only image; the container then behaves exactly as it did before analytics existed.
# Pass --no-image to stop here: `fly deploy --remote-only` then builds the image from these artifacts.
WITH_ANALYTICS=true
BUILD_IMAGE=true
for arg in "$@"; do
  case "$arg" in
    --no-analytics) WITH_ANALYTICS=false ;;
    --analytics) ;; # the default; still accepted so old habits keep working
    --no-image) BUILD_IMAGE=false ;;
    *) echo "usage: ./build.sh [--no-analytics] [--no-image]" >&2; exit 1 ;;
  esac
done

if [ "$BUILD_IMAGE" = "false" ]; then
  echo "=== Build complete (no image) ==="
  echo "Deploy: fly deploy --remote-only"
  exit 0
fi

echo "=== Building Docker image ==="
docker build --build-arg "WITH_ANALYTICS=$WITH_ANALYTICS" -t ssbudget:latest -t "ssbudget:$VERSION" .

echo "=== Build complete (analytics: $WITH_ANALYTICS) ==="
echo "Run:    docker run -p 8080:8080 -v ./data:/data ssbudget:latest"
echo "Deploy: fly deploy --local-only"
