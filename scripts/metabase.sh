#!/usr/bin/env bash
# Downloads (once) and runs the Metabase that backs the Analytics page.
#
# Metabase binds to loopback and is never exposed directly: the app proxies it under /metabase on
# its own origin, gated by the app's session. MB_SITE_URL must therefore carry that same path —
# Metabase uses its basename to emit `<base href="/analytics/">`, which is what makes its relative
# assets resolve through the proxy.
#
# Everything lives in .metabase/ (gitignored): the ~630MB jar, and Metabase's own H2 app database
# holding the dashboards you build. Delete that directory to start over from a blank instance.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"

MB_DIR="$ROOT/.metabase"
MB_JAR="$MB_DIR/metabase.jar"
MB_VERSION="${MB_VERSION:-v0.63.16.1}"

# Metabase requires Java 21; the system default may well be something else.
JAVA_BIN="${METABASE_JAVA:-}"
if [[ -z "$JAVA_BIN" ]]; then
  for candidate in /opt/homebrew/opt/openjdk@21/bin/java /usr/local/opt/openjdk@21/bin/java "$(command -v java || true)"; do
    if [[ -x "$candidate" ]] && "$candidate" -version 2>&1 | grep -q '"21'; then JAVA_BIN="$candidate"; break; fi
  done
fi
if [[ -z "$JAVA_BIN" ]]; then
  echo "✗ Metabase needs Java 21 (brew install openjdk@21), or set METABASE_JAVA=/path/to/java" >&2
  exit 1
fi

mkdir -p "$MB_DIR"
if [[ ! -f "$MB_JAR" ]]; then
  echo "▶ downloading Metabase $MB_VERSION (~630MB, once)..."
  curl -fL --progress-bar -o "$MB_JAR.tmp" \
    "https://downloads.metabase.com/${MB_VERSION}/metabase.jar"
  mv "$MB_JAR.tmp" "$MB_JAR"
fi

export MB_DB_FILE="${MB_DB_FILE:-$MB_DIR/metabase.db}"
export MB_JETTY_HOST="${MB_JETTY_HOST:-127.0.0.1}"
export MB_JETTY_PORT="${MB_JETTY_PORT:-3001}"
export MB_SITE_URL="${MB_SITE_URL:-https://localhost:3000/metabase}"
export MB_ENABLE_EMBEDDING_STATIC="${MB_ENABLE_EMBEDDING_STATIC:-true}"
export MB_EMBEDDING_SECRET_KEY="${MB_EMBEDDING_SECRET_KEY:?set MB_EMBEDDING_SECRET_KEY}"
# No sample database or "Examples" collection: the point is to explore this app's data.
export MB_LOAD_SAMPLE_CONTENT="${MB_LOAD_SAMPLE_CONTENT:-false}"
export MB_ANON_TRACKING_ENABLED="${MB_ANON_TRACKING_ENABLED:-false}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx1g}"

echo "▶ Metabase starting on http://$MB_JETTY_HOST:$MB_JETTY_PORT (site url $MB_SITE_URL)"
# Run from .metabase/: Metabase unpacks driver plugins and a sample database into its working
# directory, and those belong next to the jar rather than scattered across the repo root.
cd "$MB_DIR"
exec "$JAVA_BIN" -jar "$MB_JAR"
