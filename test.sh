#!/usr/bin/env bash
#
# Run the test suites.
#
#   ./test.sh            both suites
#   ./test.sh java       Maven / JUnit 5 only
#   ./test.sh python     pytest only
#
# Both suites are self-contained. The Java tests drive JadxAnalyzerCore directly,
# so no daemon has to be running; the Python tests mock the HTTP transport, so
# they need neither a daemon nor the jar.
#
# Fixtures. misc/DivaApplication.apk is committed and is what the Java tests use.
# A handful of tests can additionally use a larger APK for perf and scale
# checks; one is not in the repo, and those tests skip themselves with a JUnit
# Assumption when it is absent (point at one with -DlargeApk=...).
# mcp_server/tests/test_concurrency.py likewise skips unless a daemon is already
# up with an APK loaded. Skips are expected here — a green run is not a run with
# zero skips.
#
# The tool descriptions. ToolDocstringTest holds mcp_server/jadx_mcp.py's
# @mcp.tool docstrings — the text a model reads — to the same registry, through
# src/test/resources/tool-docstring-claims.md. Both files are in this repo, so
# that one runs everywhere.
#
# Either way, read the census: it is the difference between "the file is
# verified" and "nothing was".
#
# Set PYTHON to choose the interpreter; otherwise the first venv found wins.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

MODE="${1:-all}"
case "$MODE" in
    all|java|python) ;;
    *) echo "usage: $0 [all|java|python] [mvn args...]" >&2; exit 2 ;;
esac
shift $(( $# > 0 ? 1 : 0 ))
MVN_ARGS=("$@")

find_python() {
    if [ -n "${PYTHON:-}" ]; then echo "$PYTHON"; return; fi
    for cand in "$REPO_ROOT/.venv/bin/python" "$REPO_ROOT/../.venv/bin/python"; do
        [ -x "$cand" ] && { echo "$cand"; return; }
    done
    command -v python3 || true
}

JAVA_RC=0
PYTHON_RC=0

if [ "$MODE" = "all" ] || [ "$MODE" = "java" ]; then
    echo "==> Java suite: mvn -B test ${MVN_ARGS[*]}"
    mvn -B test "${MVN_ARGS[@]}"
    JAVA_RC=$?
    echo
fi

if [ "$MODE" = "all" ] || [ "$MODE" = "python" ]; then
    PY="$(find_python)"
    if [ -z "$PY" ]; then
        echo "==> Python suite: no interpreter found." >&2
        echo "    python3 -m venv .venv && .venv/bin/pip install -r mcp_server/requirements-dev.txt" >&2
        PYTHON_RC=1
    else
        echo "==> Python suite: $PY -m pytest mcp_server/tests/ -q"
        "$PY" -m pytest mcp_server/tests/ -q
        PYTHON_RC=$?
    fi
    echo
fi

echo "========================================"
[ "$MODE" != "python" ] && echo "  java   : $([ $JAVA_RC -eq 0 ] && echo PASS || echo FAIL)"
[ "$MODE" != "java" ]   && echo "  python : $([ $PYTHON_RC -eq 0 ] && echo PASS || echo FAIL)"
echo "========================================"

[ $JAVA_RC -eq 0 ] && [ $PYTHON_RC -eq 0 ] || exit 1
exit 0
