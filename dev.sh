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
# So we run the backend as a background job and `~fastLinkJS` in the same sbt,
# and start Vite FIRST so the plugin's one-shot `sbt --batch` runs on its own.
#
# Just run ./dev.sh — no env vars to fiddle with. Ctrl-C stops everything.
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$(pwd)"

kill_port() { local p="$1" pids; pids="$(lsof -ti "tcp:$p" 2>/dev/null || true)"; [ -n "$pids" ] && kill $pids 2>/dev/null || true; }

# --- Preflight: clear stragglers from a previous unclean run ---------------------
kill_port 3000
kill_port 8080
pkill -f 'frontend/fastLinkJS' 2>/dev/null || true
pkill -f 'backend/bgRun'       2>/dev/null || true

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
( cd frontend && npm install --silent )

# --- Teardown: kill the whole process group so nothing (incl. the bgRun fork) leaks ---
cleanup() {
  trap - EXIT INT TERM
  echo; echo "▶ stopping..."
  kill_port 8080          # the backend fork may outlive its sbt parent
  kill 0 2>/dev/null || true
}
trap cleanup INT TERM EXIT

# --- 1) Vite first: its one-shot plugin `sbt --batch` runs alone (no sbt collision) ---
echo "▶ starting Vite (https://localhost:3000)..."
( cd frontend && npm run dev ) &
for _ in $(seq 1 180); do
  lsof -ti tcp:3000 >/dev/null 2>&1 && break
  sleep 1
done

# --- 2) Single sbt session: backend (background job) + Scala.js watch (foreground) ---
echo "▶ starting backend (:8080) + Scala.js watch — single sbt session (Ctrl-C to stop all)"
sbt --batch -no-colors "backend/bgRun" "~frontend/fastLinkJS" &
wait $!
