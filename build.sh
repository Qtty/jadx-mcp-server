#!/usr/bin/env bash
#
# Build the JVM half of the JADX MCP server and print a working client config.
#
#   Claude ──stdio──►  mcp_server/jadx_mcp.py  ──HTTP──►  JVM daemon (JADX)
#                      the MCP protocol          :8765     decompilation only
#
# This script builds the JVM daemon jar. The MCP client never launches that jar
# directly: it launches the Python script, which spawns and supervises the JVM
# (see mcp_server/daemon.py). So there are two install steps, and the config at
# the end points at Python, not at java.
#
# Usage:
#   ./build.sh                # build + run both test suites
#   ./build.sh --skip-tests   # build only

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

# daemon.py resolves exactly this path; the name is not free to change.
JAR="target/jadx-mcp-server-1.0.0.jar"
VENV_PY="$REPO_ROOT/.venv/bin/python"

SKIP_TESTS=0
case "${1:-}" in
    --skip-tests) SKIP_TESTS=1 ;;
    "") ;;
    *) echo "usage: $0 [--skip-tests]" >&2; exit 2 ;;
esac

echo "==> Building the jadx analysis daemon"
if [ "$SKIP_TESTS" -eq 1 ]; then
    mvn -B clean package -DskipTests
else
    mvn -B clean package
fi

if [ ! -f "$JAR" ]; then
    echo "Build reported success but $JAR is missing." >&2
    exit 1
fi
echo "==> Daemon jar: $REPO_ROOT/$JAR"

echo
echo "==> Python MCP layer"
if [ -x "$VENV_PY" ]; then
    echo "    Found $VENV_PY"
    echo "    Refresh its dependencies with:"
    echo "      $VENV_PY -m pip install -r mcp_server/requirements.txt"
else
    echo "    No virtualenv at $REPO_ROOT/.venv — create one:"
    echo "      python3 -m venv .venv"
    echo "      .venv/bin/pip install -r mcp_server/requirements.txt"
    echo
    echo "    Any interpreter with those packages works; the config below just"
    echo "    has to point at it."
fi

if [ "$SKIP_TESTS" -eq 0 ]; then
    echo
    echo "==> Python tests"
    if [ -x "$VENV_PY" ]; then
        "$VENV_PY" -m pip install -q -r mcp_server/requirements-dev.txt
        "$VENV_PY" -m pytest mcp_server/tests/ -q
    else
        echo "    Skipped: no $VENV_PY. See ./test.sh once the venv exists."
    fi
fi

PY_FOR_CONFIG="$VENV_PY"
[ -x "$PY_FOR_CONFIG" ] || PY_FOR_CONFIG="/path/to/python"

cat <<EOF

==> Add this to your MCP client configuration:

{
  "mcpServers": {
    "jadx-analyzer": {
      "command": "$PY_FOR_CONFIG",
      "args": ["$REPO_ROOT/mcp_server/jadx_mcp.py"]
    }
  }
}

Nothing else needs starting. On its first tool call jadx_mcp.py brings up the
JVM daemon on 127.0.0.1:8765 and reuses it across sessions, so a large APK is
parsed once rather than on every run. To drive the daemon by hand instead, see
./run-api.sh.
EOF
