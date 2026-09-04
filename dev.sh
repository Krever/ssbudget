#!/usr/bin/env bash
# One-command local dev.
#
# Auto-configures the Enable Banking integration from the *.pem in the repo root
# (its filename is the app_id), generates an HTTPS cert for Vite (Enable Banking
# requires an https redirect URL), then runs the full stack:
#   - Vite dev server on https://localhost:3000 (proxies /api -> :8080)
#   - backend on :8080 + Scala.js watch, in a SINGLE sbt session
#
# Why one sbt session: two concurrent `sbt` on the same build collide on sbt's
# boot server socket ("Address already in use" / ServerAlreadyBootingException).
# So we run a single watch that both restarts the backend (sbt-revolver `reStart`,
# a forked JVM) and relinks the frontend (`fastLinkJS`), and start Vite FIRST so
# the plugin's one-shot `sbt --batch` runs on its own.
#
# Backend now auto-restarts on Scala changes (was a one-shot `bgRun` before).
# Caveat of the single-sbt constraint: one `~` watches BOTH tasks' sources, so a
# frontend-only edit also bounces the backend (~a few seconds). Acceptable for a
# small app; the alternative (a 2nd sbt just for the backend) hits the boot-socket
# collision above.
#
# Metabase runs too (downloaded on first use into .metabase/, ~630MB), with the backend pointed at it,
# so the Analytics page works out of the box. Pass --no-analytics to skip it: the app then runs exactly
# as it did before the integration existed, and the Analytics tab hides itself.
#
# Just run ./dev.sh — no env vars to fiddle with. Ctrl-C stops everything.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

ANALYTICS=1
case "${1:-}" in
  --no-analytics) ANALYTICS=0 ;;
  --analytics | "") ;; # --analytics is now the default; still accepted so old habits keep working
  *) echo "usage: ./dev.sh [--no-analytics]" >&2; exit 1 ;;
esac

kill_port() { local p="$1" pids; pids="$(lsof -ti "tcp:$p" 2>/dev/null || true)"; [ -n "$pids" ] && kill $pids 2>/dev/null || true; }

# --- Preflight: clear stragglers from a previous unclean run ---------------------
kill_port 3000
kill_port 8080
kill_port 3001
pkill -f 'frontend/fastLinkJS' 2>/dev/null || true
pkill -f 'ssbudget.backend.Main' 2>/dev/null || true  # the reStart/bgRun backend fork

# --- Enable Banking config, auto-derived from the .pem in the repo root ----------
PEM="$(ls "$ROOT"/*.pem 2>/dev/null | grep -v '/localhost' | head -1 || true)"
if [[ -n "$PEM" ]]; then
  export EB_APP_ID="$(basename "$PEM" .pem)"
  export EB_PRIVATE_KEY_PATH="$PEM"                                   # absolute: backend runs with cwd=backend/
  export EB_BASE_URL="${EB_BASE_URL:-https://api.enablebanking.com}"
  export EB_REDIRECT_URL="${EB_REDIRECT_URL:-https://localhost:3000/banking/callback}"
  echo "▶ Enable Banking configured: app_id=$EB_APP_ID"
else
  echo "▶ Enable Banking: no .pem in repo root — integration disabled"
fi

# --- WebAuthn origins (we now serve https on :3000, so passkeys need the https origin) ---
export SSBUDGET_RP_ORIGINS="${SSBUDGET_RP_ORIGINS:-https://localhost:3000,http://localhost:8080}"

# --- HTTPS cert for Vite (Enable Banking requires an https redirect URL) ---------
CERT_DIR="$ROOT/frontend/.certs"
if [[ ! -f "$CERT_DIR/localhost.pem" || ! -f "$CERT_DIR/localhost-key.pem" ]]; then
  mkdir -p "$CERT_DIR"
  if command -v mkcert >/dev/null 2>&1; then
    mkcert -install >/dev/null 2>&1 || true
    ( cd "$CERT_DIR" && mkcert localhost >/dev/null 2>&1 )
    echo "▶ Generated trusted localhost cert via mkcert"
  else
    openssl req -x509 -newkey rsa:2048 -nodes \
      -keyout "$CERT_DIR/localhost-key.pem" -out "$CERT_DIR/localhost.pem" \
      -days 825 -subj "/CN=localhost" -addext "subjectAltName=DNS:localhost" >/dev/null 2>&1
    echo "▶ Generated self-signed localhost cert (browser warns once — accept it; 'brew install mkcert' avoids the warning)"
  fi
fi

# --- Dependencies ----------------------------------------------------------------
# `npm install` spends minutes on a registry audit even when nothing changed (~4 min here vs 0.2s
# with it off), and --silent hid that anything was happening at all. Skip it unless the lockfile
# actually moved, and say so either way.
if [[ ! -d frontend/node_modules || frontend/package-lock.json -nt frontend/node_modules ]]; then
  echo "▶ installing frontend deps (npm install)..."
  ( cd frontend && npm install --no-audit --no-fund )
  touch frontend/node_modules
else
  echo "▶ frontend deps: up to date"
fi

# --- Teardown: kill the whole process group so nothing (incl. the bgRun fork) leaks ---
cleanup() {
  trap - EXIT INT TERM
  echo; echo "▶ stopping..."
  kill_port 8080          # the backend fork may outlive its sbt parent
  kill_port 3001          # Metabase, unless --no-analytics
  kill 0 2>/dev/null || true
}
trap cleanup INT TERM EXIT

# --- 0) Metabase (unless --no-analytics), plus the backend config that points at it ---
# Dev-only credentials: Metabase is on loopback and reachable only through the app's proxy, so these
# never leave the machine. Production supplies real secrets via fly secrets.
if [[ "$ANALYTICS" == "1" ]]; then
  export SSBUDGET_METABASE_URL="http://127.0.0.1:3001"
  export SSBUDGET_METABASE_USER="service@ssbudget.local"
  export SSBUDGET_METABASE_PASSWORD="dev-metabase-password-1"
  export SSBUDGET_METABASE_EMBED_SECRET="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  export SSBUDGET_METABASE_PATH="/metabase"
  mkdir -p .metabase
  echo "▶ starting Metabase (:3001, first run downloads ~630MB and takes a minute)..."
  MB_EMBEDDING_SECRET_KEY="$SSBUDGET_METABASE_EMBED_SECRET" \
    MB_SITE_URL="https://localhost:3000${SSBUDGET_METABASE_PATH}" \
    ./scripts/metabase.sh > .metabase/metabase.log 2>&1 &
  echo "▶ Metabase log: .metabase/metabase.log"
else
  echo "▶ Analytics: disabled (--no-analytics)"
fi

# --- 1) Vite first: its one-shot plugin `sbt --batch` runs alone (no sbt collision) ---
echo "▶ starting Vite (https://localhost:3000)..."
( cd frontend && npm run dev ) &
for _ in $(seq 1 180); do
  lsof -ti tcp:3000 >/dev/null 2>&1 && break
  sleep 1
done

# --- 2) Single sbt session: one watch that restarts the backend AND relinks the frontend ---
echo "▶ starting backend (:8080, auto-restart) + Scala.js watch — single sbt session (Ctrl-C to stop all)"
sbt --batch -no-colors "~ ;backend/reStart ;frontend/fastLinkJS" &
wait $!
