"""Settings, read from the repo-root .env (or real environment variables, which take precedence)."""

from __future__ import annotations

import os
import shutil
from functools import lru_cache
from pathlib import Path

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


def _default_env_file() -> str:
    """Repo-root .env when running from the repo (apps/ai/khmerdub_ai/config.py). Elsewhere, such as in the
    worker image, there is no repo; settings then come from real environment variables."""
    parents = Path(__file__).resolve().parents
    return str(parents[3] / ".env") if len(parents) > 3 else ".env"


ENV_FILE = os.environ.get("KHMERDUB_ENV_FILE") or _default_env_file()


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=ENV_FILE, env_file_encoding="utf-8", extra="ignore")

    storage_root: Path = Field(Path("D:/KhmerDub/data"), validation_alias="KHMERDUB_STORAGE_ROOT")
    # Directory holding ffmpeg/ffprobe. Empty means "use ffmpeg from PATH" (e.g. inside a Linux worker image).
    ffmpeg_dir: Path | None = Field(None, validation_alias="KHMERDUB_FFMPEG_DIR")
    internal_token: SecretStr = Field(validation_alias="KHMERDUB_INTERNAL_TOKEN")
    cloud_enabled: bool = Field(False, validation_alias="KHMERDUB_CLOUD_ENABLED")

    redis_host: str = Field("127.0.0.1", validation_alias="REDIS_HOST")
    redis_port: int = Field(6380, validation_alias="REDIS_PORT")
    api_host: str = Field("127.0.0.1", validation_alias="API_HOST")
    api_port: int = Field(8180, validation_alias="API_PORT")
    # Host header for calls to Spring. The isolated worker reaches Spring through the fixed relay ("api-relay")
    # but must present the host name Spring allows on /internal/** (host.docker.internal).
    api_host_header: str | None = Field(None, validation_alias="API_HOST_HEADER")
    ai_port: int = Field(8765, validation_alias="AI_PORT")
    # Verified models (read-only in the worker). Only repos listed in manifest.verified.json are used.
    models_dir: Path = Field(Path("D:/KhmerDub/data/models"), validation_alias="KHMERDUB_MODELS_DIR")

    # Must exceed the longest single task. Otherwise Redis re-delivers a task that is still running.
    celery_visibility_timeout_s: int = Field(7200, validation_alias="CELERY_VISIBILITY_TIMEOUT_S")
    worker_heartbeat_interval_s: int = Field(10, validation_alias="WORKER_HEARTBEAT_INTERVAL_S")
    min_free_disk_gb: int = Field(30, validation_alias="KHMERDUB_MIN_FREE_DISK_GB")
    # Test-only fault injection (temp/diagnostics/faults.json). Off unless explicitly enabled.
    diagnostics_enabled: bool = Field(False, validation_alias="KHMERDUB_DIAGNOSTICS")

    @field_validator("ffmpeg_dir", "api_host_header", mode="before")
    @classmethod
    def _empty_is_none(cls, v: object) -> object:
        return None if v in ("", None) else v

    @field_validator("internal_token")
    @classmethod
    def _token_length(cls, v: SecretStr) -> SecretStr:
        if len(v.get_secret_value()) < 32:
            raise ValueError("KHMERDUB_INTERNAL_TOKEN must be at least 32 characters")
        return v

    @property
    def redis_url(self) -> str:
        return f"redis://{self.redis_host}:{self.redis_port}/0"

    @property
    def api_base_url(self) -> str:
        return f"http://{self.api_host}:{self.api_port}"

    def ffmpeg_executable(self, name: str = "ffmpeg") -> str | None:
        if self.ffmpeg_dir is not None:
            exe = self.ffmpeg_dir / (f"{name}.exe" if os.name == "nt" else name)
            return str(exe) if exe.is_file() else None
        return shutil.which(name)


@lru_cache
def get_settings() -> Settings:
    return Settings()  # type: ignore[call-arg]
