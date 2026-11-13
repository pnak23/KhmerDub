import httpx
import pytest

from khmerdub_ai.engine.spring_client import Fenced, RejectedOutput, SpringClient, SpringUnavailable


class FakeClock:
    def __init__(self):
        self.now = 0.0
        self.sleeps: list[float] = []

    def clock(self) -> float:
        return self.now

    def sleep(self, s: float) -> None:
        self.sleeps.append(s)
        self.now += s


def client_with(handler, clock: FakeClock | None = None) -> SpringClient:
    clock = clock or FakeClock()
    return SpringClient("http://spring", "t" * 40, transport=httpx.MockTransport(handler),
                        sleep=clock.sleep, clock=clock.clock)


def test_sends_token_and_returns_json():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["token"] = request.headers["X-Internal-Token"]
        seen["path"] = request.url.path
        return httpx.Response(200, json={"attemptId": "a1"})

    assert client_with(handler).claim("a1", "w") == {"attemptId": "a1"}
    assert seen == {"token": "t" * 40, "path": "/internal/attempts/a1/claim"}


@pytest.mark.parametrize("status", [404, 409])
def test_conflict_and_unknown_mean_fenced(status):
    with pytest.raises(Fenced):
        client_with(lambda r: httpx.Response(status, json={"detail": "no longer current"})).complete("a1", {})


def test_rejected_output():
    with pytest.raises(RejectedOutput):
        client_with(lambda r: httpx.Response(422, json={"detail": "Checksum mismatch"})).complete("a1", {})


def test_server_errors_are_retried_until_success():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(503) if calls["n"] < 3 else httpx.Response(200, json={"accepted": True})

    clock = FakeClock()
    assert client_with(handler, clock).complete("a1", {}) == {"accepted": True}
    assert calls["n"] == 3
    assert clock.sleeps == [1.0, 2.0]


def test_network_errors_give_up_after_the_retry_window():
    def handler(request):
        raise httpx.ConnectError("refused")

    clock = FakeClock()
    with pytest.raises(SpringUnavailable):
        client_with(handler, clock).claim("a1", "w")
    assert 59 <= clock.now <= 61  # claim retries for 60 s


def test_heartbeat_is_not_retried():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        raise httpx.ConnectError("refused")

    with pytest.raises(SpringUnavailable):
        client_with(handler).heartbeat("a1", 10)
    assert calls["n"] == 1
