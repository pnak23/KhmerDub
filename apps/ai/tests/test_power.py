"""Keep-awake lease logic (with a fake SetThreadExecutionState; the real call is verified on Windows separately)."""

from __future__ import annotations

import threading

from khmerdub_ai.power import ES_CONTINUOUS, ES_SYSTEM_REQUIRED, KeepAwake


class FakeClock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def make():
    calls: list[tuple[int, str]] = []
    clock = FakeClock()

    def setter(flags):
        calls.append((flags, threading.current_thread().name))
        return 1

    return KeepAwake(setter=setter, clock=clock), calls, clock


def test_hold_release_on_dedicated_thread_never_display_flag():
    ka, calls, _ = make()
    ka.hold("job-engine", 120, "job 1 RUNNING")
    ka.hold("job-engine", 120, "job 1 RUNNING")  # renewal does not call Windows again
    ka.wait_idle()
    assert ka.status().held and ka.status().acquisitions == 1
    ka.release("job-engine")
    ka.wait_idle()
    assert not ka.status().held
    assert calls == [(ES_CONTINUOUS | ES_SYSTEM_REQUIRED, "khmerdub-keep-awake"), (ES_CONTINUOUS, "khmerdub-keep-awake")]
    assert all(flags & 0x2 == 0 and flags & 0x40 == 0 for flags, _ in calls)  # no DISPLAY / AWAYMODE
    ka.stop()


def test_ttl_expiry_releases_without_renewal():
    calls: list[int] = []
    ka = KeepAwake(setter=lambda f: calls.append(f) or 1)
    ka.hold("job-engine", 30)  # minimum TTL is 30 s; shorten it for the test
    ka.wait_idle()
    with ka._state_lock:  # noqa: SLF001
        ka._deadline = ka._clock() + 0.2  # noqa: SLF001
    ka._commands.put(("noop", {}))  # wake the thread so it re-reads the deadline  # noqa: SLF001
    for _ in range(100):
        if not ka.status().held:
            break
        threading.Event().wait(0.02)
    assert not ka.status().held and calls[-1] == ES_CONTINUOUS
    ka.stop()


def test_ttl_is_clamped_and_stop_releases():
    ka, calls, clock = make()
    ka.hold("job-engine", 99999)
    ka.wait_idle()
    assert ka.status().expires_in_s <= 300
    ka.stop()
    assert calls[-1][0] == ES_CONTINUOUS and not ka.status().held
