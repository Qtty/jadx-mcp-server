#!/usr/bin/env bash
#
# Start the jadx analysis daemon in the foreground.
#
# You normally do not need this: mcp_server/jadx_mcp.py spawns and supervises
# the JVM itself. Run it by hand when you want the daemon's log on your terminal,
# or want to drive the HTTP API with curl without an MCP client in the way.
#
# The daemon speaks plain HTTP, not MCP. It binds 127.0.0.1 only — these
# endpoints read arbitrary classes and resources out of a loaded APK and must
# never be reachable from the network.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

JAR="target/jadx-mcp-server-1.0.0.jar"
PORT="${JADX_PORT:-8765}"
HEAP="${JADX_HEAP:-24g}"
BASE="http://127.0.0.1:$PORT/api/jadx"

# One log per daemon, keyed by the port; the default port keeps the historical
# path. The JVM works this out for itself (see DaemonLogFile) -- this only has
# to agree with it well enough to print the truth.
export JADX_PORT="$PORT"
if [ -n "${JADX_LOG_FILE:-}" ]; then
    LOG="$JADX_LOG_FILE"
elif [ "$PORT" = "8765" ]; then
    LOG="/tmp/jadx-mcp-server.log"
else
    LOG="/tmp/jadx-mcp-server-$PORT.log"
fi

if [ ! -f "$JAR" ]; then
    echo "JAR file not found: $JAR"
    echo "Please run ./build.sh first"
    exit 1
fi

cat <<EOF
Starting the jadx analysis daemon...
  endpoints : $BASE
  heap      : -Xmx$HEAP   (override with JADX_HEAP)
  log       : $LOG   (follows JADX_PORT; override with JADX_LOG_FILE)

Load an APK (POST, JSON body):
  curl -XPOST $BASE/load-apk -H 'Content-Type: application/json' \\
       -d '{"apkPath":"$REPO_ROOT/misc/DivaApplication.apk"}'

Then query it. Every other endpoint is a GET taking QUERY PARAMS — never path
segments, because class names contain dots and resource paths contain slashes:
  curl "$BASE/health"
  curl "$BASE/deeplink-components"
  curl "$BASE/class-source?className=jakhar.aseem.diva.MainActivity"
  curl "$BASE/search-method?methodName=onCreate"

Press Ctrl+C to stop the daemon.

EOF

exec java "-Xmx$HEAP" -jar "$JAR"
