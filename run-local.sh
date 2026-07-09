#!/usr/bin/env bash
set -euo pipefail

echo "=== Building frontend (Scala.js fast) ==="
sbt frontend/fastLinkJS

echo "=== Building frontend (Vite) ==="
cd frontend && npm install && npm run build
cd ..

echo "=== Starting backend with bundled frontend ==="
SSBUDGET_STATIC_DIR=frontend/dist sbt backend/run
