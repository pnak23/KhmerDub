import json

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from khmerdub_ai.config import Settings
from khmerdub_ai.main import app, get_redis
from tests.conftest import TOKEN, FakeRedis

AUTH = {"X-Internal-Token": TOKEN}


@pytest.fixture
def client():
    with TestClient(app, base_url="http://127.0.0.1") as c:
        yield c
    app.dependency_overrides.clear()


def use_redis(fake: FakeRedis) -> None:
    app.dependency_overrides[get_redis] = lambda: fake


def test_livez_is_open(client):
    assert client.get("/livez").json() == {"status": "ok"}


def test_health_requires_token(client):
    use_redis(FakeRedis())
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers={"X-Internal-Token": "wrong"}).status_code == 401


def test_roundtrip_requires_token(client):
    assert client.post("/internal/diagnostics/roundtrip").status_code == 401


def test_untrusted_host_is_rejected(client):
    assert client.get("/livez", headers={"Host": "evil.example"}).status_code == 400


def test_health_without_workers_or_ffmpeg_is_warn(client):
    use_redis(FakeRedis())
    body = client.get("/health", headers=AUTH).json()
    c = body["components"]
    assert c["redis"]["status"] == "UP"
    assert c["storage"]["status"] == "UP"
    assert c["storage"]["details"]["writable"] is True
    assert c["ffmpeg"]["status"] == "DOWN"
    assert c["workers"]["status"] == "WARN"
    assert body["status"] == "WARN"
    assert body["cloudEnabled"] is False


def test_health_with_redis_down_is_down(client):
    use_redis(FakeRedis(up=False))
    body = client.get("/health", headers=AUTH).json()
    assert body["components"]["redis"]["status"] == "DOWN"
    assert body["status"] == "DOWN"


def test_health_lists_worker_heartbeats(client):
    beat = {"hostname": "cpu@test", "os": "linux", "pool": "prefork", "queues": ["cpu"]}
    use_redis(FakeRedis(keys={"khmerdub:worker:cpu@test": json.dumps(beat).encode()}))
    workers = client.get("/health", headers=AUTH).json()["components"]["workers"]
    assert workers["status"] == "UP"
    assert workers["details"]["count"] == 1
    assert workers["details"]["workers"][0]["hostname"] == "cpu@test"


def test_short_internal_token_is_rejected(monkeypatch):
    monkeypatch.setenv("KHMERDUB_INTERNAL_TOKEN", "too-short")
    with pytest.raises(ValidationError):
        Settings()
