"""Regression test for the outage this rewrite exists to fix.

On 2026-08-07 a large production APK analysis hung permanently: four tool calls completed in the
same millisecond, raced on the MCP Java SDK's unicast outbound sink, and killed
the transport. The JVM stayed alive with no reader thread, so all 14 in-flight
agents waited forever.

This fires the same shape of load -- many simultaneous whole-APK searches and
class decompilations -- and requires every one to come back.

It brings up its own daemon to do that. Until 2026-08-08 it was skipped unless
someone had happened to start one by hand on the shared default port, which
meant the entire justification for this server being Python rather than Java
(upstream java-sdk #686) was never once exercised: three skips, reported as a
green suite, in every run this repo has recorded. A suite that reports success
while never running the thing the architecture exists for is worse than a red
one, because nobody goes looking.

The daemon is per-test-session and on its own port, never the default 8765: a
analysis's warm daemon is somebody's expensive state and this suite must not touch
it. Set JADX_TEST_URL to point at one you have already started -- that one is
used as found and is never stopped, because we did not start it.
"""

from __future__ import annotations

import concurrent.futures as cf
import os
import threading
from pathlib import Path

import httpx
import pytest

import daemon
from jadx_mcp import JadxClient

PARALLELISM = 16

# Not 8765. That is the default an analysis uses, and reusing it would mean this
# suite could stop somebody's warm 141,461-class daemon between two phases.
DEFAULT_TEST_URL = "http://127.0.0.1:9276"

# Small on purpose: this measures concurrent request handling, not parse speed.
TEST_APK = str(Path(daemon.REPO_ROOT) / "misc" / "DivaApplication.apk")


@pytest.fixture(scope="session")
def jadx_url() -> str:
    """A daemon with an APK loaded, started here unless one was pointed at.

    Fails rather than skips when it cannot get one. The skip is what hid this
    suite for its whole existence; a missing prerequisite should say what to run,
    not quietly subtract three tests from the count.
    """
    url = os.environ.get("JADX_TEST_URL", DEFAULT_TEST_URL)
    ours = False

    if daemon._health(url) is None:
        jar = daemon.DEFAULT_JAR
        if not Path(jar).exists():
            pytest.fail(
                f"the concurrency suite needs a daemon and there is no jar to "
                f"start one from: {jar}\n"
                f"  build it: cd {daemon.REPO_ROOT} && mvn -q package -DskipTests\n"
                f"  or point at a running daemon: JADX_TEST_URL=http://127.0.0.1:PORT"
            )
        if not Path(TEST_APK).exists():
            pytest.fail(f"fixture APK missing: {TEST_APK}")
        daemon.ensure_daemon(url, jar=jar, timeout=180.0)
        ours = True

    health = daemon._health(url)
    assert health is not None, f"daemon at {url} came up but is not healthy"
    if not health.get("loadedApk"):
        JadxClient(base=url).load_apk(TEST_APK)

    yield url

    # Only ever stop a daemon this fixture started. One we were pointed at
    # belongs to whoever started it.
    if ours:
        pid = (daemon._health(url) or {}).get("build", {}).get("pid")
        if pid:
            daemon._stop(int(pid))


@pytest.fixture
def client(jadx_url: str) -> JadxClient:
    return JadxClient(base=jadx_url)


def test_parallel_searches_all_succeed(client: JadxClient):
    # The exact method names the stalled agents were querying.
    names = ["doFilter", "onReceive", "handleNewIntent", "checkDrouterUrl"] * 8

    with cf.ThreadPoolExecutor(max_workers=PARALLELISM) as pool:
        results = list(pool.map(client.search_method_by_name, names))

    assert len(results) == len(names)
    assert all(isinstance(r, dict) for r in results)


def test_mixed_parallel_load_all_succeeds(client: JadxClient):
    """Searches and decompilations interleaved -- the calls have very different
    durations, which is what made simultaneous completion likely."""
    classes = [c for c in client.get_all_classes() if not c.startswith("@")][:24]
    assert classes, "the loaded APK lists no classes"

    def work(i):
        if i % 3 == 0:
            return client.search_method_by_name("onCreate")
        return client.get_class_source(classes[i % len(classes)])

    with cf.ThreadPoolExecutor(max_workers=PARALLELISM) as pool:
        results = list(pool.map(work, range(48)))

    assert len(results) == 48
    assert all(r is not None for r in results)


def test_simultaneous_completion_is_the_case_that_killed_the_transport(
    client: JadxClient,
):
    """The original failure needed responses finishing in the same instant.

    The two tests above fire load; this one aims at the specific condition.
    Identical requests released together take near-identical time to serve, so
    they finish together -- which on the Java SDK's unicast sink was the whole
    bug. Here there is no shared outbound sink, so the property to assert is
    that every one of them still comes back and that they agree.
    """
    barrier = threading.Barrier(PARALLELISM)

    def work(_):
        barrier.wait(timeout=60)
        return client.search_method_by_name("onCreate")

    with cf.ThreadPoolExecutor(max_workers=PARALLELISM) as pool:
        results = list(pool.map(work, range(PARALLELISM)))

    assert len(results) == PARALLELISM
    assert all(r == results[0] for r in results), (
        "the same query answered differently under concurrent load"
    )


def test_daemon_still_healthy_after_the_storm(jadx_url: str):
    """The old failure was silent: the server stopped answering but stayed up.
    A health check after the load is what would have caught it."""
    assert JadxClient(base=jadx_url).health()["status"] == "ok"


def test_the_suite_is_actually_pointed_at_a_daemon(jadx_url: str):
    """The property the skip used to hide. If this suite is green, a daemon
    answered -- rather than three tests having been silently subtracted."""
    health = JadxClient(base=jadx_url).health()

    assert health["status"] == "ok"
    assert health["loadedApk"], "a concurrency test against an empty daemon proves nothing"
    with httpx.Client(base_url=jadx_url, timeout=10) as raw:
        assert raw.get("/api/jadx/health").status_code == 200
