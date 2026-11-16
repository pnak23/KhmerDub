"""Network self-check for the isolated worker: internet must be unreachable, Redis and Spring reachable.

Run: python -m khmerdub_ai.net.selfcheck   (exit 0 = isolated and connected). Never prints the internal token.
"""

from __future__ import annotations

import json
import socket
import sys
from typing import Any

import httpx

# Destinations that must NOT be reachable from the worker.
EXTERNAL_NAMES = ("huggingface.co", "otel.pyannote.ai", "generativelanguage.googleapis.com", "pypi.org")
EXTERNAL_ADDRESSES = (("1.1.1.1", 443), ("8.8.8.8", 53))


def _resolves(host: str) -> bool:
    try:
        socket.getaddrinfo(host, 443)
        return True
    except OSError:
        return False


def _connects(host: str, port: int, timeout: float = 3.0) -> bool:
    try:
        socket.create_connection((host, port), timeout=timeout).close()
        return True
    except OSError:
        return False


def check() -> dict[str, Any]:
    from khmerdub_ai.config import get_settings

    s = get_settings()
    leaks = [h for h in EXTERNAL_NAMES if _resolves(h)] + [f"{h}:{p}" for h, p in EXTERNAL_ADDRESSES if _connects(h, p)]
    redis_ok = False
    try:
        with socket.create_connection((s.redis_host, s.redis_port), timeout=3) as c:
            c.sendall(b"PING\r\n")
            redis_ok = c.recv(16).startswith(b"+PONG")
    except OSError:
        pass
    spring = "unreachable"
    headers = {"X-Internal-Token": s.internal_token.get_secret_value()}
    if s.api_host_header:
        headers["Host"] = s.api_host_header
    try:
        r = httpx.get(f"{s.api_base_url}/internal/ping", headers=headers, timeout=5)
        spring = "ok" if r.status_code == 200 and r.json().get("ok") else f"HTTP {r.status_code}"
    except httpx.HTTPError as e:
        spring = f"unreachable ({type(e).__name__})"
    return {"internetBlocked": not leaks, "reachableExternal": leaks, "redis": "ok" if redis_ok else "unreachable",
            "spring": spring, "isolatedAndConnected": not leaks and redis_ok and spring == "ok"}


if __name__ == "__main__":
    result = check()
    print(json.dumps(result, indent=1))
    sys.exit(0 if result["isolatedAndConnected"] else 1)
