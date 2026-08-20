"""Spawn and supervise the jadx JVM.

The JVM is a long-lived daemon rather than a per-session subprocess, so a large
APK is parsed once instead of on every run. This module is what makes that
transparent: the MCP server just calls ensure_daemon() at startup and either
finds a warm JVM or starts one.

It also removes the failure mode that made the original outage invisible. The
old server, once its transport died, sat there holding the socket open with no
reader, so clients blocked forever. Here the daemon is checked over HTTP: if it
is not answering it gets restarted, and if it cannot be started the error says
why instead of hanging.

Everything on disk is keyed by the port, because the project's own advice is to
run one daemon per concurrent analysis (JADX_URL / JADX_PORT). See the path rules
below.
"""

from __future__ import annotations

import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlsplit

import httpx

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_JAR = str(REPO_ROOT / "target" / "jadx-mcp-server-1.0.0.jar")

# Must match application.properties (server.port=${JADX_PORT:8765}) and
# DaemonLogFile.DEFAULT_PORT in the JVM.
DEFAULT_PORT = "8765"

# Observed working set on a 282 MB APK is ~14 GB. The old launch line used
# -Xms32g -Xmx32g, which committed 32 GB up front for no benefit.
HEAP = os.environ.get("JADX_HEAP", "24g")

HEALTH_TIMEOUT = 2.0
POLL_INTERVAL = 0.5


# --- per-port paths ------------------------------------------------------------
#
# A daemon is meant to be one-per-analysis, but all of them used to write to one
# hardcoded log, capture stdout into one file and contend for one lock. The
# warnings that matter -- AnalyzerRegistry's cache-thrash warning, the oversize
# guards -- were only discoverable in the shared file, so two analyses in flight
# meant matching pids by hand to attribute a warning to a daemon.
#
# The rule everywhere: the default port keeps the path everything already greps
# for, any other port gets "-<port>" before the extension.


def _suffixed(path: str, port: str) -> str:
    """/tmp/x.log at the default port; /tmp/x-8791.log anywhere else."""
    if port == DEFAULT_PORT or not port.isdigit():
        return path
    stem, dot, ext = path.rpartition(".")
    return f"{stem}-{port}{dot}{ext}" if dot else f"{path}-{port}"


def daemon_port(url: str) -> str:
    """The port this supervisor is managing, taken from the URL it health-checks.

    The URL is the authority, not JADX_PORT: jadx_mcp.py picks the daemon by
    JADX_URL, and a JADX_PORT inherited from some other analysis's shell would send
    the spawned JVM somewhere the supervisor is not looking.
    """
    port = urlsplit(url).port
    return str(port) if port else DEFAULT_PORT


def spring_log_path(port: str) -> str:
    """The daemon's own application log -- where its warnings land.

    Mirrors DaemonLogFile.forPort() in the JVM, which is what actually decides
    it. Duplicated rather than queried because this is needed before the daemon
    exists, and because it has to hold for a daemon started by hand.
    """
    return os.environ.get("JADX_LOG_FILE") or _suffixed("/tmp/jadx-mcp-server.log", port)


def daemon_log_path(port: str) -> str:
    """Where the spawned JVM's stdout/stderr are captured.

    Distinct from spring_log_path: this one catches what happens before or
    outside logging -- a JVM that dies on -Xmx, a stack trace on the way down.
    """
    return os.environ.get("JADX_DAEMON_LOG") or _suffixed("/tmp/jadx-daemon.log", port)


def lock_path(port: str) -> str:
    """Per port too: two supervisors bringing up two different daemons are not
    racing each other, and must not serialise behind one lock."""
    return os.environ.get("JADX_DAEMON_LOCK") or _suffixed("/tmp/jadx-daemon.lock", port)


def build_command(jar: str) -> list[str]:
    java = os.environ.get("JAVA_BIN", "java")
    return [java, f"-Xmx{HEAP}", "-jar", jar]


def _health(url: str) -> dict | None:
    """The daemon's full health body, or None if it is not answering."""
    try:
        r = httpx.get(f"{url}/api/jadx/health", timeout=HEALTH_TIMEOUT)
        if r.status_code != 200:
            return None
        body = r.json()
        return body if body.get("status") == "ok" else None
    except (httpx.HTTPError, ValueError):
        return None


def _healthy(url: str) -> bool:
    return _health(url) is not None


def jar_build_id(jar: str) -> dict | None:
    """The identity a daemon running this jar would report."""
    try:
        st = os.stat(jar)
    except OSError:
        return None
    return {
        "artifact": str(Path(jar).resolve()),
        "mtime": int(st.st_mtime * 1000),
        "size": st.st_size,
    }


def stale_reason(health: dict, jar: str) -> str | None:
    """Why the running daemon does not match the jar on disk, or None if it does.

    A long-lived daemon can outlive the code it was built from. That is not
    hypothetical: a merge once landed 21 tools in the source while target/ still
    held a 14-tool jar, and because the old daemon answered /health the supervisor
    reused it. Tool calls then ran stale analysis code -- not failing, just
    quietly answering from the wrong build.

    Returns None whenever the comparison cannot be made (a daemon running from
    exploded classes, a missing jar, an older daemon with no build block). Not
    knowing is not a reason to kill someone else's warm daemon.
    """
    running = (health or {}).get("build") or {}
    if not running.get("artifact"):
        return None

    on_disk = jar_build_id(jar)
    if not on_disk:
        return None

    if running["artifact"] != on_disk["artifact"]:
        return (f"running {running['artifact']} but this client points at "
                f"{on_disk['artifact']}")

    if running.get("size") != on_disk["size"] or running.get("mtime") != on_disk["mtime"]:
        delta = (on_disk["mtime"] - int(running.get("mtime") or 0)) / 1000.0
        detail = ""
        if running.get("size") != on_disk["size"]:
            detail = f", {running.get('size')} -> {on_disk['size']} bytes"
        return f"jar was rebuilt {delta:.0f}s after the running daemon started{detail}"

    return None


def _stop(pid: int, timeout: float = 30.0) -> bool:
    """Stop a daemon we are replacing. SIGTERM, then SIGKILL."""
    try:
        os.kill(pid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except OSError:
        return False

    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        time.sleep(POLL_INTERVAL)

    try:
        os.kill(pid, signal.SIGKILL)
    except OSError:
        pass
    return True


def _spawn(jar: str, port: str) -> None:
    log = open(daemon_log_path(port), "ab", buffering=0)
    env = dict(os.environ)
    # The URL decides the port, so say so explicitly rather than letting the JVM
    # read whatever JADX_PORT happens to be in this shell. JADX_LOG_FILE keeps
    # the daemon's application log and this stdout capture describing the same
    # process even if the two rules ever drift.
    env["JADX_PORT"] = port
    env["JADX_LOG_FILE"] = spring_log_path(port)
    subprocess.Popen(
        build_command(jar),
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=log,
        env=env,
        # Outlive this MCP process: a new Claude session should find the JVM warm
        # rather than pay the parse again.
        start_new_session=True,
        cwd=str(REPO_ROOT),
    )


def _tail(path: str, limit: int = 40) -> str:
    try:
        with open(path, "r", errors="replace") as f:
            text = "".join(f.readlines()[-limit:])
    except OSError:
        return "(not written)"
    return text or "(empty)"


def _try_acquire_lock(port: str) -> int | None:
    """Best-effort: stops two MCP servers starting two JVMs on the same port."""
    path = lock_path(port)
    try:
        fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
        os.write(fd, str(os.getpid()).encode())
        return fd
    except FileExistsError:
        try:
            # A lock older than five minutes is stale; a JVM start never takes that long.
            if time.time() - os.path.getmtime(path) > 300:
                os.unlink(path)
                return _try_acquire_lock(port)
        except OSError:
            pass
        return None


def _release_lock(fd: int | None, port: str) -> None:
    if fd is None:
        return
    try:
        os.close(fd)
        os.unlink(lock_path(port))
    except OSError:
        pass


def ensure_daemon(url: str, jar: str | None = None, timeout: float = 180.0) -> None:
    """Return once the daemon answers /health, starting it if necessary.

    A daemon that is alive but running a stale jar is replaced. That check
    happens here and only here -- at MCP server startup, once per session --
    never per request, so it cannot interrupt an analysis in flight. Set
    JADX_ALLOW_STALE=1 to keep a stale daemon instead.
    """
    if _healthy(url):
        jar_path = jar or DEFAULT_JAR
        if os.environ.get("JADX_ALLOW_STALE") == "1":
            return
        health = _health(url)
        reason = stale_reason(health or {}, jar_path)
        if not reason:
            return
        pid = ((health or {}).get("build") or {}).get("pid")
        print(f"jadx daemon is stale: {reason}", file=sys.stderr)
        if not pid:
            # Nothing to stop precisely; leave it rather than killing by name and
            # taking down a daemon that is not ours.
            print("  no pid in /health; leaving it running", file=sys.stderr)
            return
        print(f"  restarting daemon pid {pid} to pick up the current build",
              file=sys.stderr)
        _stop(int(pid))

    jar = jar or DEFAULT_JAR
    if not Path(jar).exists():
        raise RuntimeError(
            f"jadx daemon jar not found: {jar}\n"
            f"Build it with: cd {REPO_ROOT} && mvn -q package -DskipTests"
        )

    port = daemon_port(url)
    lock = _try_acquire_lock(port)
    try:
        # Another process may have won the race and started it already.
        if lock is not None and not _healthy(url):
            _spawn(jar, port)

        deadline = time.time() + timeout
        while time.time() < deadline:
            if _healthy(url):
                return
            time.sleep(POLL_INTERVAL)
    finally:
        _release_lock(lock, port)

    # Both logs, because they catch different failures. A JVM that dies on -Xmx
    # or a bad jar only ever reaches the stdout capture; a port already in use is
    # logged by Spring and, with the console pattern deliberately blank, appears
    # nowhere else.
    stdout_log, app_log = daemon_log_path(port), spring_log_path(port)
    raise RuntimeError(
        f"jadx daemon did not become healthy at {url} within {timeout:.0f}s.\n"
        f"--- tail of {stdout_log} ---\n{_tail(stdout_log)}\n"
        f"--- tail of {app_log} ---\n{_tail(app_log)}"
    )
