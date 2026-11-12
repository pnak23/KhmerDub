import json

import pytest

from khmerdub_ai.tasks.system import ping, sleep_task


def test_ping_writes_probe_file_to_shared_storage(storage_root):
    result = ping.apply(kwargs={"probe": True}).get()
    probe = storage_root / result["probeFile"]
    assert probe.is_file()
    assert json.loads(probe.read_text(encoding="utf-8"))["taskId"] == result["taskId"]


def test_sleep_task_logs_start_and_finish(storage_root):
    sleep_task.apply(args=(0, "unit-sleep")).get()
    lines = (storage_root / "temp/diagnostics/unit-sleep.log").read_text(encoding="utf-8").splitlines()
    assert [json.loads(line)["event"] for line in lines] == ["start", "finish"]


@pytest.mark.parametrize("marker", ["../escape", "a/b", "", "x" * 65])
def test_sleep_task_rejects_unsafe_marker(marker):
    with pytest.raises(ValueError):
        sleep_task.apply(args=(0, marker)).get()
