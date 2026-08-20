import pytest

import daemon


@pytest.fixture
def fake_jar(tmp_path):
    """A jar that exists on disk; ensure_daemon refuses to spawn without one."""
    jar = tmp_path / "jadx-mcp-server-1.0.0.jar"
    jar.write_bytes(b"PK\x03\x04")
    return str(jar)


def test_returns_immediately_when_already_healthy(monkeypatch, fake_jar):
    spawned = []
    monkeypatch.setattr(daemon, "_healthy", lambda url: True)
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: spawned.append(a))

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar)

    assert spawned == [], "must reuse a running JVM, never start a second one"


def test_spawns_when_not_healthy(monkeypatch, fake_jar):
    # unhealthy on the first probe, healthy once spawned
    states = iter([False, False, True])
    monkeypatch.setattr(daemon, "_healthy", lambda url: next(states, True))
    spawned = []
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: spawned.append(a))

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar)

    assert spawned, "a dead daemon must be started"


def test_raises_with_log_tail_when_it_never_comes_up(monkeypatch, tmp_path, fake_jar):
    log = tmp_path / "jadx-daemon.log"
    log.write_text("Caused by: java.lang.OutOfMemoryError: Java heap space\n")

    monkeypatch.setattr(daemon, "_healthy", lambda url: False)
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: None)
    monkeypatch.setenv("JADX_DAEMON_LOG", str(log))
    monkeypatch.setenv("JADX_DAEMON_LOCK", str(tmp_path / "lock"))

    with pytest.raises(RuntimeError, match="OutOfMemoryError"):
        daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar, timeout=0.3)


def test_raises_with_the_spring_log_too(monkeypatch, tmp_path, fake_jar):
    """A port collision never reaches the stdout capture.

    The console pattern is deliberately blank -- a detached daemon's stdout has
    no reader -- so "Web server failed to start. Port 8791 was already in use"
    only ever appears in Spring's own log file. An error that quoted the stdout
    capture alone showed an empty tail for the single most common start failure.
    """
    spring = tmp_path / "jadx-mcp-server-8791.log"
    spring.write_text("Web server failed to start. Port 8791 was already in use.\n")

    monkeypatch.setattr(daemon, "_healthy", lambda url: False)
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: None)
    monkeypatch.setenv("JADX_DAEMON_LOG", str(tmp_path / "empty.log"))
    monkeypatch.setenv("JADX_DAEMON_LOCK", str(tmp_path / "lock"))
    monkeypatch.setenv("JADX_LOG_FILE", str(spring))

    with pytest.raises(RuntimeError, match="already in use"):
        daemon.ensure_daemon("http://127.0.0.1:8791", jar=fake_jar, timeout=0.3)


def test_missing_jar_is_reported_clearly(monkeypatch):
    monkeypatch.setattr(daemon, "_healthy", lambda url: False)

    with pytest.raises(RuntimeError, match="jar not found"):
        daemon.ensure_daemon("http://127.0.0.1:8765",
                             jar="/nonexistent/nope.jar", timeout=0.3)


def test_java_command_does_not_preallocate_the_heap():
    cmd = daemon.build_command("/tmp/fake.jar")
    assert "-jar" in cmd and cmd[-1] == "/tmp/fake.jar"
    # -Xms32g committed 32 GB up front for a ~14 GB working set.
    assert not any(a.startswith("-Xms") for a in cmd), cmd
    assert any(a.startswith("-Xmx") for a in cmd), cmd


# --- staleness: a live daemon running code that no longer matches the jar ------


def _health_for(jar_path, *, pid=4242, mtime=None, size=None):
    """A /health body as a daemon launched from jar_path would report it."""
    import os
    st = os.stat(jar_path)
    return {
        "status": "ok",
        "loadedApk": None,
        "build": {
            "pid": pid,
            "artifact": str(jar_path),
            "mtime": int(st.st_mtime * 1000) if mtime is None else mtime,
            "size": st.st_size if size is None else size,
        },
    }


def test_matching_build_is_not_stale(fake_jar):
    assert daemon.stale_reason(_health_for(fake_jar), fake_jar) is None


def test_rebuilt_jar_is_stale(fake_jar):
    # daemon started before the rebuild: older mtime, different size
    health = _health_for(fake_jar, mtime=1, size=999)
    reason = daemon.stale_reason(health, fake_jar)
    assert reason and "rebuilt" in reason


def test_daemon_running_a_different_jar_is_stale(tmp_path, fake_jar):
    other = tmp_path / "worktree" / "jadx-mcp-server-1.0.0.jar"
    other.parent.mkdir()
    other.write_bytes(b"PK\x03\x04")
    # This is the real case that bit us: a daemon started from a worktree's
    # target/ while the client points at the main checkout.
    reason = daemon.stale_reason(_health_for(other), fake_jar)
    assert reason and "points at" in reason


def test_exploded_classes_are_never_stale(fake_jar):
    """A daemon running from classes (tests, IDE) reports no artifact. Not
    knowing must not be grounds for killing it."""
    health = {"status": "ok", "build": {"pid": 1, "artifact": None,
                                        "mtime": 0, "size": 0}}
    assert daemon.stale_reason(health, fake_jar) is None


def test_daemon_without_a_build_block_is_never_stale(fake_jar):
    """An older daemon predating BuildIdentity. Leave it alone."""
    assert daemon.stale_reason({"status": "ok"}, fake_jar) is None


def test_missing_jar_is_not_grounds_for_restart():
    health = {"status": "ok", "build": {"pid": 1, "artifact": "/nope.jar",
                                        "mtime": 1, "size": 1}}
    assert daemon.stale_reason(health, "/nonexistent/nope.jar") is None


def test_ensure_daemon_restarts_a_stale_daemon(monkeypatch, fake_jar):
    stopped, spawned = [], []

    # Alive and stale to begin with; dead once stopped, then alive again after
    # the replacement spawns. A constant True here would be a lie -- the code
    # correctly declines to spawn over a daemon that is still answering.
    state = {"up": True}
    monkeypatch.setattr(daemon, "_healthy", lambda url: state["up"])
    monkeypatch.setattr(daemon, "_health",
                        lambda url: _health_for(fake_jar, pid=777, mtime=1, size=999)
                        if state["up"] else None)

    def stop(pid, **kwargs):
        stopped.append(pid)
        state["up"] = False

    def spawn(*args, **kwargs):
        spawned.append(args)
        state["up"] = True

    monkeypatch.setattr(daemon, "_stop", stop)
    monkeypatch.setattr(daemon, "_spawn", spawn)
    monkeypatch.delenv("JADX_ALLOW_STALE", raising=False)

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar, timeout=5)

    assert stopped == [777], "the stale daemon must be stopped by pid"
    assert spawned, "and a fresh one started"


def test_ensure_daemon_leaves_a_current_daemon_alone(monkeypatch, fake_jar):
    stopped, spawned = [], []
    monkeypatch.setattr(daemon, "_healthy", lambda url: True)
    monkeypatch.setattr(daemon, "_health", lambda url: _health_for(fake_jar))
    monkeypatch.setattr(daemon, "_stop", lambda pid, **k: stopped.append(pid))
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: spawned.append(a))
    monkeypatch.delenv("JADX_ALLOW_STALE", raising=False)

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar)

    assert stopped == [] and spawned == []


def test_allow_stale_opts_out_of_the_restart(monkeypatch, fake_jar):
    stopped = []
    monkeypatch.setattr(daemon, "_healthy", lambda url: True)
    monkeypatch.setattr(daemon, "_health",
                        lambda url: _health_for(fake_jar, mtime=1, size=999))
    monkeypatch.setattr(daemon, "_stop", lambda pid, **k: stopped.append(pid))
    monkeypatch.setenv("JADX_ALLOW_STALE", "1")

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar)

    assert stopped == []


def test_stale_daemon_without_a_pid_is_left_running(monkeypatch, fake_jar):
    """Killing by name could take down a daemon that is not ours."""
    stopped, spawned = [], []
    health = _health_for(fake_jar, mtime=1, size=999)
    health["build"].pop("pid")
    monkeypatch.setattr(daemon, "_healthy", lambda url: True)
    monkeypatch.setattr(daemon, "_health", lambda url: health)
    monkeypatch.setattr(daemon, "_stop", lambda pid, **k: stopped.append(pid))
    monkeypatch.setattr(daemon, "_spawn", lambda *a, **k: spawned.append(a))
    monkeypatch.delenv("JADX_ALLOW_STALE", raising=False)

    daemon.ensure_daemon("http://127.0.0.1:8765", jar=fake_jar)

    assert stopped == [] and spawned == []


# --- one daemon per analysis means one set of files per port -----------------------
#
# The project tells you to run one daemon per concurrent analysis (JADX_URL /
# JADX_PORT), but every daemon appended to the same /tmp/jadx-mcp-server.log,
# captured stdout into the same /tmp/jadx-daemon.log, and contended for the same
# /tmp/jadx-daemon.lock. A cache-thrash warning in that shared file could not be
# attributed to a daemon without matching pids by hand.


@pytest.fixture(autouse=True)
def _no_path_overrides(monkeypatch):
    """These vars leak in from a developer's shell and would mask the port rule."""
    for var in ("JADX_DAEMON_LOG", "JADX_DAEMON_LOCK", "JADX_LOG_FILE"):
        monkeypatch.delenv(var, raising=False)


def test_port_comes_from_the_url_being_supervised():
    assert daemon.daemon_port("http://127.0.0.1:8791") == "8791"
    assert daemon.daemon_port("http://127.0.0.1:8765") == "8765"
    # No port in the URL is the default the daemon binds, not HTTP's 80.
    assert daemon.daemon_port("http://127.0.0.1") == "8765"


def test_default_port_keeps_every_historical_path():
    assert daemon.spring_log_path("8765") == "/tmp/jadx-mcp-server.log"
    assert daemon.daemon_log_path("8765") == "/tmp/jadx-daemon.log"
    assert daemon.lock_path("8765") == "/tmp/jadx-daemon.lock"


def test_a_second_port_gets_its_own_files():
    assert daemon.spring_log_path("8791") == "/tmp/jadx-mcp-server-8791.log"
    assert daemon.daemon_log_path("8791") == "/tmp/jadx-daemon-8791.log"
    assert daemon.lock_path("8791") == "/tmp/jadx-daemon-8791.lock"


def test_two_hunts_share_no_file():
    for fn in (daemon.spring_log_path, daemon.daemon_log_path, daemon.lock_path):
        assert fn("8791") != fn("8792"), fn.__name__


def test_spring_log_rule_matches_the_jvm(tmp_path):
    """This mirrors DaemonLogFile.forPort in the JVM. If one moves, both move.

    Kept as literals rather than importing anything: the point of the assertion
    is that two independent implementations agree on the same strings, which is
    exactly what DaemonLogFileTest asserts on the Java side.
    """
    assert daemon.spring_log_path("8765") == "/tmp/jadx-mcp-server.log"
    assert daemon.spring_log_path("8791") == "/tmp/jadx-mcp-server-8791.log"


def test_explicit_overrides_still_win(monkeypatch):
    monkeypatch.setenv("JADX_DAEMON_LOG", "/var/log/mine.log")
    monkeypatch.setenv("JADX_DAEMON_LOCK", "/var/lock/mine.lock")
    monkeypatch.setenv("JADX_LOG_FILE", "/var/log/spring.log")
    assert daemon.daemon_log_path("8791") == "/var/log/mine.log"
    assert daemon.lock_path("8791") == "/var/lock/mine.lock"
    assert daemon.spring_log_path("8791") == "/var/log/spring.log"


def test_spawn_tells_the_jvm_which_port_to_bind(monkeypatch, tmp_path):
    """Without this the supervisor waited 180 s for a daemon it had started on
    the wrong port: JADX_URL selected 8791, the JVM read JADX_PORT (unset) and
    bound 8765, and no amount of polling 8791 was ever going to find it."""
    captured = {}

    def fake_popen(cmd, **kwargs):
        captured["env"] = kwargs.get("env")
        return object()

    monkeypatch.setattr(daemon.subprocess, "Popen", fake_popen)
    monkeypatch.setenv("JADX_DAEMON_LOG", str(tmp_path / "d.log"))

    daemon._spawn(str(tmp_path / "fake.jar"), "8791")

    assert captured["env"]["JADX_PORT"] == "8791"
    # ...and the JVM must not be left to guess where its own log goes.
    assert captured["env"]["JADX_LOG_FILE"] == "/tmp/jadx-mcp-server-8791.log"


def test_spawn_captures_stdout_into_that_ports_file(monkeypatch, tmp_path):
    monkeypatch.setattr(daemon.subprocess, "Popen", lambda cmd, **kw: object())
    monkeypatch.setenv("JADX_DAEMON_LOG", str(tmp_path / "d-8791.log"))

    daemon._spawn(str(tmp_path / "fake.jar"), "8791")

    assert (tmp_path / "d-8791.log").exists()


def test_ensure_daemon_spawns_on_the_url_port(monkeypatch, fake_jar, tmp_path):
    states = iter([False, False, True])
    monkeypatch.setattr(daemon, "_healthy", lambda url: next(states, True))
    spawned = []
    monkeypatch.setattr(daemon, "_spawn", lambda jar, port: spawned.append(port))
    monkeypatch.setenv("JADX_DAEMON_LOCK", str(tmp_path / "lock"))

    daemon.ensure_daemon("http://127.0.0.1:8791", jar=fake_jar)

    assert spawned == ["8791"]
