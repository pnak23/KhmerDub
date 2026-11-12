"""Safe access to the shared storage root.

The database stores paths RELATIVE to the storage root, with forward slashes. That lets the same record
work for a Windows process (D:/KhmerDub/data/...) and a Linux worker (/data/...).
"""

from __future__ import annotations

import os
import re
import uuid
from pathlib import Path

_DRIVE = re.compile(r"^[A-Za-z]:")


class UnsafePathError(ValueError):
    pass


def resolve_in_storage(root: Path, relative: str) -> Path:
    """Resolve a relative storage path inside ``root``. Reject absolute paths, backslashes and traversal."""
    if not relative or relative.startswith("/") or "\\" in relative or _DRIVE.match(relative):
        raise UnsafePathError(f"Not a safe relative storage path: {relative!r}")
    parts = relative.split("/")
    if any(p in ("", ".", "..") for p in parts):
        raise UnsafePathError(f"Not a safe relative storage path: {relative!r}")
    root_resolved = root.resolve()
    candidate = root_resolved.joinpath(*parts).resolve()
    if not candidate.is_relative_to(root_resolved):
        raise UnsafePathError(f"Path escapes the storage root: {relative!r}")
    return candidate


def atomic_write_bytes(target: Path, data: bytes) -> None:
    """Write to a temp file in the same directory, fsync, then rename into place.

    Readers never see a half-written file. A crash leaves at most a stray ``.*.tmp`` file behind.
    """
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_name(f".{target.name}.{uuid.uuid4().hex}.tmp")
    try:
        with open(tmp, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp, target)
    finally:
        if tmp.exists():
            tmp.unlink()
