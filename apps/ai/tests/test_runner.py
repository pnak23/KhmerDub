import sys
import time

import pytest

from khmerdub_ai.engine.faults import Fault, match
from khmerdub_ai.engine.runner import InvalidInput, Tools, execute_attempt
from khmerdub_ai.engine.spring_client import Fenced

ATTEMPT = "11111111-1111-1111-1111-111111111111"


class FakeSpring:
    """Records calls; behaviour is configurable per test."""

    def __init__(self, claim_fenced=False, heartbeat_action="CONTINUE", heartbeat_fenced=False):
        self.calls: list[tuple[str, object]] = []
        self.claim_fenced = claim_fenced
        self.heartbeat_action = heartbeat_action
        self.heartbeat_fenced = heartbeat_fenced

    def claim(self, attempt_id, worker):
        self.calls.append(("claim", worker))
        if self.claim_fenced:
            raise Fenced("no longer current")
        return {"attemptId": attempt_id, "attemptNo": 1, "stage": "TEST", "chunkIndex": 0,
                "workDir": f"projects/p/temp/attempts/{attempt_id}", "heartbeatIntervalSeconds": 10, "input": {}}

    def heartbeat(self, attempt_id, progress):
        self.calls.append(("heartbeat", progress))
        if self.heartbeat_fenced:
            raise Fenced("reassigned")
        return {"action": self.heartbeat_action}

    def complete(self, attempt_id, result):
        self.calls.append(("complete", result))
        return {"accepted": True}

    def fail(self, attempt_id, failure):
        self.calls.append(("fail", failure))
        return {"recorded": True}

    def names(self):
        return [c[0] for c in self.calls]


def run(spring, handler, storage_root, **kw):
    return execute_attempt(ATTEMPT, client=spring, storage_root=storage_root, tools=Tools(None, None),
                           worker="w1", handlers={"TEST": handler}, heartbeat_interval=0.05, **kw)


def test_fenced_claim_does_no_work(storage_root):
    called = []
    spring = FakeSpring(claim_fenced=True)
    assert run(spring, lambda ctx: called.append(1) or {}, storage_root) == "fenced"
    assert called == [] and spring.names() == ["claim"]


def test_success_reports_result_with_timing(storage_root):
    spring = FakeSpring()

    def handler(ctx):
        assert ctx.work_dir.is_dir()  # the attempt's own folder exists
        return {"outputs": [], "metadata": {"x": 1}}

    assert run(spring, handler, storage_root) == "completed"
    _, result = spring.calls[-1]
    assert result["metadata"] == {"x": 1} and "wallMs" in result["metrics"]


def test_invalid_input_is_not_retryable(storage_root):
    spring = FakeSpring()

    def handler(ctx):
        raise InvalidInput("no such audio stream")

    assert run(spring, handler, storage_root) == "invalid"
    assert spring.calls[-1] == ("fail", {"retryable": False, "cancelled": False, "error": "no such audio stream"})


def test_unexpected_error_is_retryable(storage_root):
    spring = FakeSpring()

    def handler(ctx):
        raise OSError("disk full")

    assert run(spring, handler, storage_root) == "failed"
    failure = spring.calls[-1][1]
    assert failure["retryable"] is True and "disk full" in failure["error"]


def test_cancel_request_kills_the_running_process(storage_root):
    spring = FakeSpring(heartbeat_action="CANCEL")

    def handler(ctx):
        ctx.run_process([sys.executable, "-c", "import time; time.sleep(30)"])
        return {}

    started = time.monotonic()
    assert run(spring, handler, storage_root) == "cancelled"
    assert time.monotonic() - started < 10, "the 30 s process must have been killed"
    assert spring.calls[-1] == ("fail", {"retryable": False, "cancelled": True, "error": "Cancelled on request"})


def test_fenced_heartbeat_stops_work_and_reports_nothing(storage_root):
    spring = FakeSpring(heartbeat_fenced=True)

    def handler(ctx):
        ctx.run_process([sys.executable, "-c", "import time; time.sleep(30)"])
        return {}

    assert run(spring, handler, storage_root) == "fenced"
    assert "complete" not in spring.names() and "fail" not in spring.names()


def test_injected_fault_suppresses_heartbeats(storage_root):
    spring = FakeSpring()

    def handler(ctx):
        time.sleep(0.4)
        return {}

    assert run(spring, handler, storage_root, fault=lambda claim: Fault(suppress_heartbeat=True)) == "completed"
    assert "heartbeat" not in spring.names()


def test_fault_rules_match_stage_chunk_and_attempt():
    rules = [{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1, "sleepBeforeS": 30}]
    assert match(rules, {"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1}).sleep_before_s == 30
    assert match(rules, {"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 2}) is None
    assert match(rules, {"stage": "MERGE_AUDIO", "chunkIndex": 1, "attemptNo": 1}) is None


@pytest.mark.parametrize("attempt_dir", ["../escape", "/abs"])
def test_unsafe_work_dir_is_refused(storage_root, attempt_dir):
    class BadSpring(FakeSpring):
        def claim(self, attempt_id, worker):
            c = super().claim(attempt_id, worker)
            c["workDir"] = attempt_dir
            return c

    spring = BadSpring()
    assert run(spring, lambda ctx: {}, storage_root) == "failed"
