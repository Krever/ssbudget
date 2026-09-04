#!/usr/bin/env bash
# Container entrypoint. Runs the app, plus Metabase alongside it whenever the image carries the jar.
#
# Analytics needs no configuration to work: if the jar is there we start it, point the app at it over
# loopback, and mint the two credentials it needs on first boot. Set SSBUDGET_ANALYTICS_ENABLED=false
# to run app-only from an analytics image; set it to true to turn a missing jar into a hard error
# rather than a silent skip.
#
# Two JVMs under one PID 1, so this has to be a real supervisor in miniature: forward signals to
# both, and exit as soon as either dies. A half-alive container that still answers health checks is
# worse than one that restarts.
set -euo pipefail

MB_JAR="/opt/metabase/metabase.jar"
MB_STATE_DIR="/data/metabase"
MB_SECRETS="$MB_STATE_DIR/secrets.env"
mb_pid=""

analytics_wanted() {
  case "${SSBUDGET_ANALYTICS_ENABLED:-auto}" in
    false) return 1 ;;
    true)
      [[ -f "$MB_JAR" ]] && return 0
      echo "✗ SSBUDGET_ANALYTICS_ENABLED=true but $MB_JAR is missing — the image was built with ./build.sh --no-analytics" >&2
      exit 1
      ;;
    *) [[ -f "$MB_JAR" ]] ;;
  esac
}

random_hex() { head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'; }

# The service-account password and the embed signing key have to survive a restart: the account is
# created on first boot and can't be re-created under a different password, and the embed key is the
# shared secret between the app (which signs) and Metabase (which verifies). Supply them as secrets
# if you want to own them; otherwise they are generated once and kept on the data volume, so a plain
# deploy needs no ceremony. Whatever we end up using is written there, so removing a supplied secret
# later doesn't lock the service account out.
load_secrets() {
  mkdir -p "$MB_STATE_DIR"
  local saved_password="" saved_secret="" generated=0
  if [[ -f "$MB_SECRETS" ]]; then
    saved_password="$(sed -n 's/^SSBUDGET_METABASE_PASSWORD=//p' "$MB_SECRETS")"
    saved_secret="$(sed -n 's/^SSBUDGET_METABASE_EMBED_SECRET=//p' "$MB_SECRETS")"
  fi
  # Precedence: what the environment supplies, then what an earlier boot generated, then something new.
  local password="${SSBUDGET_METABASE_PASSWORD:-$saved_password}"
  local secret="${SSBUDGET_METABASE_EMBED_SECRET:-$saved_secret}"
  [[ -n "$password" ]] || { password="$(random_hex 16)"; generated=1; }
  [[ -n "$secret" ]] || { secret="$(random_hex 32)"; generated=1; }
  export SSBUDGET_METABASE_PASSWORD="$password"
  export SSBUDGET_METABASE_EMBED_SECRET="$secret"

  if [[ "$password" != "$saved_password" || "$secret" != "$saved_secret" ]]; then
    ( umask 077
      printf 'SSBUDGET_METABASE_PASSWORD=%s\nSSBUDGET_METABASE_EMBED_SECRET=%s\n' "$password" "$secret" > "$MB_SECRETS" )
  fi
  if [[ "$generated" == 1 ]]; then
    echo "▶ generated analytics credentials, stored in $MB_SECRETS"
  fi
}

start_metabase() {
  export MB_DB_FILE="${MB_DB_FILE:-$MB_STATE_DIR/metabase.db}"
  # Loopback only: the app's session-gated proxy is the sole way in.
  export MB_JETTY_HOST="${MB_JETTY_HOST:-127.0.0.1}"
  export MB_JETTY_PORT="${MB_JETTY_PORT:-3001}"
  export MB_ENABLE_EMBEDDING_STATIC="${MB_ENABLE_EMBEDDING_STATIC:-true}"
  # No sample database or "Examples" collection: the point is to explore this app's data.
  export MB_LOAD_SAMPLE_CONTENT="${MB_LOAD_SAMPLE_CONTENT:-false}"
  export MB_ANON_TRACKING_ENABLED="${MB_ANON_TRACKING_ENABLED:-false}"

  # What the app needs to find Metabase and serve it under a prefix.
  export SSBUDGET_METABASE_URL="${SSBUDGET_METABASE_URL:-http://${MB_JETTY_HOST}:${MB_JETTY_PORT}}"
  export SSBUDGET_METABASE_PATH="${SSBUDGET_METABASE_PATH:-/metabase}"

  # Metabase derives its asset `<base href>` from the basename of its site URL, so that has to carry
  # the same prefix the app proxies it under. Only the path matters here; the origin merely makes the
  # URLs Metabase prints look right, so it falls back to the local one.
  export MB_SITE_URL="${MB_SITE_URL:-${SSBUDGET_PUBLIC_ORIGIN:-http://localhost:${SSBUDGET_PORT:-8080}}${SSBUDGET_METABASE_PATH}}"
  export MB_EMBEDDING_SECRET_KEY="${MB_EMBEDDING_SECRET_KEY:-$SSBUDGET_METABASE_EMBED_SECRET}"
  export JAVA_OPTS="${METABASE_JAVA_OPTS:--Xmx1g}"

  echo "▶ starting Metabase on ${MB_JETTY_HOST}:${MB_JETTY_PORT}, proxied at ${SSBUDGET_METABASE_PATH}"
  # Metabase unpacks driver plugins into its working directory, which must be writable.
  ( cd /opt/metabase && java -jar "$MB_JAR" ) &
  mb_pid=$!
}

shutdown() {
  trap - TERM INT
  [[ -n "$mb_pid" ]] && kill "$mb_pid" 2>/dev/null || true
  kill 0 2>/dev/null || true
}
trap shutdown TERM INT

if analytics_wanted; then
  load_secrets
  start_metabase
else
  # Leave no half-configuration behind: without these the app skips provisioning, drops the proxy
  # route and hides the Analytics tab, exactly as in a build without the jar.
  unset SSBUDGET_METABASE_URL SSBUDGET_METABASE_PASSWORD SSBUDGET_METABASE_EMBED_SECRET
  echo "▶ analytics disabled"
fi

# The app is what the container exists for, so it runs in the foreground.
bin/ssbudget &
app_pid=$!

# Whichever exits first takes the container down with it.
wait -n "$app_pid" ${mb_pid:+"$mb_pid"}
exit_code=$?
echo "▶ a process exited (code $exit_code) — shutting down"
shutdown
exit "$exit_code"
