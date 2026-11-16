"""Windows keep-awake for long jobs (verified in M3a: selftest 10/10; 24 min screen-off without sleep, sleep within
seconds of release).

Design (approved):
  * Runs ONLY in the Windows FastAPI process (never in the Linux worker container).
  * One dedicated thread "khmerdub-keep-awake" is the only caller of SetThreadExecutionState:
    ES_CONTINUOUS | ES_SYSTEM_REQUIRED to hold, ES_CONTINUOUS to release. Never ES_DISPLAY_REQUIRED or
    ES_AWAYMODE_REQUIRED, so the display still turns off. Windows power settings are never changed.
  * Lease: Spring renews every 30 s with a TTL (default 120 s). Without renewal the request is dropped when the TTL
    expires, so a crashed or stuck Spring can never keep the PC awake. Process exit also drops it.
"""

from __future__ import annotations

import logging
import queue
import sys
import threading
import time
from dataclasses import dataclass
from typing import Callable

log = logging.getLogger(__name__)

ES_SYSTEM_REQUIRED = 0x00000001
ES_CONTINUOUS = 0x80000000
MIN_TTL_S, MAX_TTL_S = 30, 300


def _windows_setter() -> Callable[[int], int] | None:
    if sys.platform != "win32":
        return None
    import ctypes
    from ctypes import wintypes

    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel32.SetThreadExecutionState.argtypes = [wintypes.DWORD]
    kernel32.SetThreadExecutionState.restype = wintypes.DWORD
    return kernel32.SetThreadExecutionState


@dataclass
class PowerStatus:
    supported: bool
    held: bool
    holder: str | None
    reason: str | None
    held_since: float | None
    expires_in_s: float | None
    acquisitions: int
    releases: int


class KeepAwake:
    """Lease-based execution-state request owned by a single dedicated thread."""

    def __init__(self, setter: Callable[[int], int] | None = None, clock: Callable[[], float] = time.monotonic,
                 wall: Callable[[], float] = time.time):
        self._setter = setter if setter is not None else _windows_setter()
        self._clock = clock
        self._wall = wall
        self._commands: queue.Queue[tuple[str, dict]] = queue.Queue()
        self._state_lock = threading.Lock()
        self._held = False
        self._holder: str | None = None
        self._reason: str | None = None
        self._since: float | None = None
        self._deadline: float | None = None
        self._acquisitions = 0
        self._releases = 0
        self._thread = threading.Thread(target=self._run, name="khmerdub-keep-awake", daemon=True)
        self._thread.start()

    @property
    def supported(self) -> bool:
        return self._setter is not None

    def hold(self, holder: str, ttl_s: float, reason: str | None = None) -> None:
        ttl = max(MIN_TTL_S, min(MAX_TTL_S, float(ttl_s)))
        self._commands.put(("hold", {"holder": holder, "ttl": ttl, "reason": reason}))

    def release(self, holder: str | None = None, why: str = "released") -> None:
        self._commands.put(("release", {"holder": holder, "why": why}))

    def stop(self) -> None:
        self._commands.put(("stop", {}))
        self._thread.join(timeout=5)

    def status(self) -> PowerStatus:
        with self._state_lock:
            expires = None if self._deadline is None else max(0.0, self._deadline - self._clock())
            return PowerStatus(self.supported, self._held, self._holder, self._reason, self._since, expires,
                               self._acquisitions, self._releases)

    def wait_idle(self, timeout: float = 2.0) -> None:
        """Test helper: wait until queued commands are processed."""
        end = time.monotonic() + timeout
        while not self._commands.empty() and time.monotonic() < end:
            time.sleep(0.01)
        time.sleep(0.02)

    # ------------------------------------------------------------------ dedicated thread

    def _apply(self, flags: int) -> None:
        if self._setter is not None and self._setter(flags) == 0:
            raise OSError("SetThreadExecutionState failed")

    def _run(self) -> None:
        while True:
            timeout = None if self._deadline is None else max(0.0, self._deadline - self._clock())
            try:
                cmd, args = self._commands.get(timeout=timeout)
            except queue.Empty:
                cmd, args = "release", {"holder": None, "why": "TTL expired (no renewal)"}
            try:
                if cmd == "hold":
                    with self._state_lock:
                        if not self._held:
                            self._apply(ES_CONTINUOUS | ES_SYSTEM_REQUIRED)
                            self._held, self._since = True, self._wall()
                            self._acquisitions += 1
                            log.info("keep-awake acquired by %s (%s)", args["holder"], args["reason"])
                        self._holder, self._reason = args["holder"], args["reason"]
                        self._deadline = self._clock() + args["ttl"]
                elif cmd in ("release", "stop"):
                    with self._state_lock:
                        if self._held:
                            self._apply(ES_CONTINUOUS)
                            self._releases += 1
                            log.info("keep-awake %s", args.get("why", "stopped"))
                        self._held, self._holder, self._reason, self._since, self._deadline = (
                            False, None, None, None, None)
                    if cmd == "stop":
                        return
            except OSError:
                log.exception("keep-awake: execution-state call failed")
