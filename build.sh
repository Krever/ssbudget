#!/usr/bin/env bash
set -euo pipefail

echo "=== Building backend ==="
sbt backend/stage

echo "=== Building frontend (Scala.js) ==="
sbt frontend/fullLinkJS

echo "=== Building frontend (Vite) ==="
cd frontend && npm install && npm run build
cd ..

echo "=== Building Docker image ==="
docker build -t ssbudget:latest .

echo "=== Build complete ==="
echo "Run: docker run -p 8080:8080 -v ./data:/data ssbudget:latest"
