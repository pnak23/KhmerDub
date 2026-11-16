"""Client for Spring Boot's worker protocol (/internal/attempts/{id}/...).

Spring owns all job state. The worker only claims, heartbeats and reports. HTTP status codes:
200 = go on; 409/404 = fenced (stop, publish nothing); 422 = result rejected; 5xx/network = retry.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable
from typing import Any

import httpx

log = logging.getLogger(__name__)


class Fenced(Exception):
    """This attempt may no longer act (reassigned, cancelled or unknown). Stop and publish nothing."""


class RejectedOutput(Exception):
    """Spring refused the result (missing file, wrong size or checksum). Spring has recorded the failure."""


class SpringUnavailable(Exception):
    """Spring could not be reached within the retry window. Its lease monitor will reassign the work."""


class SpringClient:
    def __init__(self, base_url: str, token: str, *, host_header: str | None = None,
                 transport: httpx.BaseTransport | None = None,
                 sleep: Callable[[float], None] = time.sleep, clock: Callable[[], float] = time.monotonic):
        headers = {"X-Internal-Token": token}
        if host_header:
            headers["Host"] = host_header  # reaching Spring through the fixed relay (isolated worker network)
        self._http = httpx.Client(base_url=base_url, headers=headers,
                                  timeout=httpx.Timeout(30.0, connect=5.0), transport=transport)
        self._sleep = sleep
        self._clock = clock

    def claim(self, attempt_id: str, worker: str) -> dict[str, Any]:
        return self._call(f"/internal/attempts/{attempt_id}/claim", {"worker": worker}, retry_for=60)

    def heartbeat(self, attempt_id: str, progress: float) -> dict[str, Any]:
        return self._call(f"/internal/attempts/{attempt_id}/heartbeat", {"progress": round(progress, 2)}, retry_for=0)

    def complete(self, attempt_id: str, result: dict[str, Any]) -> dict[str, Any]:
        # Keep trying for a while: losing a finished result means redoing the work.
        return self._call(f"/internal/attempts/{attempt_id}/complete", result, retry_for=600)

    def fail(self, attempt_id: str, failure: dict[str, Any]) -> dict[str, Any]:
        return self._call(f"/internal/attempts/{attempt_id}/fail", failure, retry_for=600)

    def close(self) -> None:
        self._http.close()

    def _call(self, path: str, payload: dict[str, Any], *, retry_for: float) -> dict[str, Any]:
        deadline = self._clock() + retry_for
        delay = 1.0
        while True:
            try:
                r = self._http.post(path, json=payload)
            except httpx.TransportError as e:
                problem = f"{type(e).__name__}: {e}"
            else:
                if r.status_code in (404, 409):
                    raise Fenced(_detail(r))
                if r.status_code == 422:
                    raise RejectedOutput(_detail(r))
                if r.is_success:
                    return r.json()
                if r.status_code < 500:
                    raise RuntimeError(f"Spring rejected {path}: HTTP {r.status_code} {_detail(r)}")
                problem = f"HTTP {r.status_code}"
            remaining = deadline - self._clock()
            if remaining <= 0:
                raise SpringUnavailable(f"{path}: {problem}")
            log.warning("Spring call %s failed (%s); retrying", path, problem)
            self._sleep(min(delay, remaining))
            delay = min(delay * 2, 15.0)


def _detail(r: httpx.Response) -> str:
    try:
        return str(r.json().get("detail") or r.text)
    except ValueError:
        return r.text
