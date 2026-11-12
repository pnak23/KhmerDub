"""Test isolation: never read the developer's .env, never touch real Redis or the real storage root."""

import atexit
import os
import shutil
import tempfile
from pathlib import Path

_STORAGE = Path(tempfile.mkdtemp(prefix="khmerdub-test-storage-"))
atexit.register(shutil.rmtree, _STORAGE, ignore_errors=True)

os.environ["KHMERDUB_ENV_FILE"] = str(Path(__file__).parent / "does-not-exist.env")
os.environ["KHMERDUB_INTERNAL_TOKEN"] = "test-token-0123456789abcdef0123456789"
os.environ["KHMERDUB_STORAGE_ROOT"] = str(_STORAGE)
os.environ["KHMERDUB_FFMPEG_DIR"] = str(_STORAGE / "no-ffmpeg-here")
os.environ["KHMERDUB_MIN_FREE_DISK_GB"] = "1"
os.environ["REDIS_PORT"] = "1"  # nothing listens here

import pytest  # noqa: E402

TOKEN = os.environ["KHMERDUB_INTERNAL_TOKEN"]


class FakeRedis:
    def __init__(self, up: bool = True, keys: dict[str, bytes] | None = None):
        self.up = up
        self.keys = keys or {}

    def _check(self) -> None:
        if not self.up:
            raise ConnectionError("redis down")

    def ping(self) -> bool:
        self._check()
        return True

    def info(self, section: str) -> dict:
        self._check()
        return {"redis_version": "7.4.0"}

    def scan_iter(self, match: str, count: int = 100):
        self._check()
        prefix = match.rstrip("*")
        return [k for k in self.keys if k.startswith(prefix)]

    def get(self, key: str):
        self._check()
        return self.keys.get(key)


@pytest.fixture
def storage_root() -> Path:
    return _STORAGE
