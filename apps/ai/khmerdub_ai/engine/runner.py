"""Runs one attempt: claim -> heartbeat in the background -> do the work -> complete or fail.

Rules:
  * Outputs are written only inside the attempt's own folder (workDir). Spring verifies and publishes them.
  * A 409 from Spring (fenced) or a CANCEL heartbeat reply stops the work immediately; running FFmpeg
    processes are killed. A fenced attempt reports nothing.
"""

from __future__ import annotations

import logging
import os
import signal
import subprocess
import threading
import time
from collections import deque
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from khmerdub_ai.engine.faults import Fault
from khmerdub_ai.engine.spring_client import Fenced, RejectedOutput, SpringClient, SpringUnavailable
from khmerdub_ai.storage import resolve_in_storage

log = logging.getLogger(__name__)


class Cancelled(Exception):
    """The work was told to stop (job cancelled, or this attempt was fenced)."""


class InvalidInput(Exception):
    """The input cannot be processed; retrying will not help."""


@dataclass(frozen=True)
class Tools:
    ffmpeg: str | None
    ffprobe: str | None


@dataclass
class AttemptContext:
    claim: dict[str, Any]
    storage_root: Path
    tools: Tools
    _progress: float = 0.0
    _cancel: threading.Event = field(default_factory=threading.Event)
    _fenced: bool = False
    _procs: set[subprocess.Popen] = field(default_factory=set)
    _lock: threading.Lock = field(default_factory=threading.Lock)

    @property
    def attempt_id(self) -> str:
        return str(self.claim["attemptId"])

    @property
    def input(self) -> dict[str, Any]:
        return self.claim.get("input") or {}

    @property
    def work_dir(self) -> Path:
        return resolve_in_storage(self.storage_root, self.claim["workDir"])

    @property
    def progress(self) -> float:
        return self._progress

    @property
    def fenced(self) -> bool:
        return self._fenced

    @property
    def cancelled(self) -> bool:
        return self._cancel.is_set()

    def set_progress(self, value: float) -> None:
        self._progress = max(0.0, min(100.0, value))

    def resolve(self, relative: str) -> Path:
        return resolve_in_storage(self.storage_root, relative)

    def relative(self, path: Path) -> str:
        return path.resolve().relative_to(self.storage_root.resolve()).as_posix()

    def request_cancel(self, *, fenced: bool = False) -> None:
        self._fenced = self._fenced or fenced
        self._cancel.set()
        with self._lock:
            for p in list(self._procs):
                _kill(p)

    def check_cancelled(self) -> None:
        if self._cancel.is_set():
            raise Cancelled("fenced" if self._fenced else "cancelled")

    def sleep(self, seconds: float) -> None:
        """Interruptible sleep (used by diagnostics)."""
        if self._cancel.wait(seconds):
            self.check_cancelled()

    def run_process(self, cmd: list[str], on_line: Callable[[str], None] | None = None) -> str:
        """Run a process; feed its stdout lines to on_line; kill it on cancel. Returns the stderr tail."""
        self.check_cancelled()
        popen_kwargs: dict[str, Any] = {"stdout": subprocess.PIPE, "stderr": subprocess.PIPE, "text": True,
                                        "stdin": subprocess.DEVNULL}
        if os.name == "posix":
            popen_kwargs["start_new_session"] = True  # kill the whole process group on cancel
        proc = subprocess.Popen(cmd, **popen_kwargs)
        stderr_tail: deque[str] = deque(maxlen=20)
        reader = threading.Thread(target=lambda: [stderr_tail.append(line.rstrip()) for line in proc.stderr],
                                  daemon=True)
        reader.start()
        with self._lock:
            self._procs.add(proc)
        try:
            assert proc.stdout is not None
            for line in proc.stdout:
                if on_line:
                    on_line(line.strip())
            code = proc.wait()
        finally:
            with self._lock:
                self._procs.discard(proc)
            reader.join(timeout=5)
        self.check_cancelled()
        tail = "\n".join(stderr_tail)
        if code != 0:
            raise RuntimeError(f"{Path(cmd[0]).name} exited with code {code}: {tail[-2000:]}")
        return tail

    def run_ffmpeg(self, args: list[str], expected_duration_s: float) -> str:
        if not self.tools.ffmpeg:
            raise RuntimeError("ffmpeg is not available on this worker")

        def on_line(line: str) -> None:
            if line.startswith("out_time_us=") and expected_duration_s > 0:
                try:
                    us = int(line.split("=", 1)[1])
                except ValueError:
                    return
                self.set_progress(min(99.0, us / 1_000_000 / expected_duration_s * 100))

        return self.run_process([self.tools.ffmpeg, *args, "-progress", "pipe:1", "-nostats"], on_line)


def _kill(proc: subprocess.Popen) -> None:
    try:
        if os.name == "posix":
            os.killpg(proc.pid, signal.SIGKILL)
        else:
            proc.kill()
    except (ProcessLookupError, OSError):
        pass


class Heartbeat(threading.Thread):
    """Renews the lease every interval. CANCEL reply or 409 (fenced) stops the work."""

    def __init__(self, client: SpringClient, ctx: AttemptContext, interval: float):
        super().__init__(name=f"heartbeat-{ctx.attempt_id[:8]}", daemon=True)
        self._client = client
        self._ctx = ctx
        self._interval = interval
        self._stop = threading.Event()
        self.suppressed = False
        self.sent = 0

    def run(self) -> None:
        while not self._stop.wait(self._interval):
            if self.suppressed:
                continue
            try:
                reply = self._client.heartbeat(self._ctx.attempt_id, self._ctx.progress)
                self.sent += 1
                if reply.get("action") == "CANCEL":
                    log.info("Attempt %s: cancel requested", self._ctx.attempt_id)
                    self._ctx.request_cancel()
            except Fenced:
                log.warning("Attempt %s was fenced; stopping", self._ctx.attempt_id)
                self._ctx.request_cancel(fenced=True)
            except Exception as e:  # noqa: BLE001 - Spring briefly unreachable: keep working, keep trying
                log.warning("Heartbeat for %s failed: %s", self._ctx.attempt_id, e)

    def stop(self) -> None:
        self._stop.set()


Handler = Callable[[AttemptContext], dict[str, Any]]


def execute_attempt(attempt_id: str, *, client: SpringClient, storage_root: Path, tools: Tools, worker: str,
                    handlers: dict[str, Handler], fault: Callable[[dict[str, Any]], Fault | None] | None = None,
                    heartbeat_interval: float | None = None) -> str:
    """Run one attempt end to end. Returns an outcome label (for logs and tests)."""
    try:
        claim = client.claim(attempt_id, worker)
    except Fenced as e:
        log.info("Attempt %s not run: %s", attempt_id, e)
        return "fenced"
    except SpringUnavailable as e:
        log.error("Attempt %s could not be claimed: %s", attempt_id, e)
        return "spring-unavailable"

    ctx = AttemptContext(claim=claim, storage_root=storage_root, tools=tools)
    injected = fault(claim) if fault else None
    hb = Heartbeat(client, ctx, heartbeat_interval or float(claim.get("heartbeatIntervalSeconds", 10)))
    hb.start()
    started = time.monotonic()
    try:
        ctx.work_dir.mkdir(parents=True, exist_ok=True)
        if injected:
            log.warning("Diagnostics: injecting %s into attempt %s", injected, attempt_id)
            hb.suppressed = injected.suppress_heartbeat
            ctx.sleep(injected.sleep_before_s)
        handler = handlers.get(claim["stage"])
        if handler is None:
            raise InvalidInput(f"This worker cannot run stage {claim['stage']}")
        result = handler(ctx)
        ctx.check_cancelled()
        if injected and injected.sleep_before_complete_s:
            time.sleep(injected.sleep_before_complete_s)
        result.setdefault("metrics", {})["wallMs"] = round((time.monotonic() - started) * 1000)
        client.complete(attempt_id, result)
        return "completed"
    except Cancelled:
        if ctx.fenced:
            return "fenced"
        return _report(client, attempt_id, {"retryable": False, "cancelled": True, "error": "Cancelled on request"},
                       "cancelled")
    except Fenced as e:
        log.warning("Attempt %s fenced while reporting: %s", attempt_id, e)
        return "fenced"
    except RejectedOutput as e:
        log.error("Attempt %s result rejected: %s", attempt_id, e)
        return "rejected"
    except SpringUnavailable as e:
        log.error("Attempt %s: Spring unavailable (%s); the lease will expire and the work will be reassigned",
                  attempt_id, e)
        return "spring-unavailable"
    except InvalidInput as e:
        return _report(client, attempt_id, {"retryable": False, "cancelled": False, "error": str(e)}, "invalid")
    except Exception as e:  # noqa: BLE001
        log.exception("Attempt %s failed", attempt_id)
        return _report(client, attempt_id, {"retryable": True, "cancelled": False,
                                            "error": f"{type(e).__name__}: {e}"[:20000]}, "failed")
    finally:
        hb.stop()


def _report(client: SpringClient, attempt_id: str, failure: dict[str, Any], outcome: str) -> str:
    try:
        client.fail(attempt_id, failure)
    except (Fenced, SpringUnavailable, RejectedOutput) as e:
        log.warning("Could not report %s for %s: %s", outcome, attempt_id, e)
    return outcome
