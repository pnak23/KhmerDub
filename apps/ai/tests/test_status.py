"""Worker self-report (System page): only booleans and short safe words leave the worker."""

import json

from khmerdub_ai import status


ML = {
    "versions": {"torch": {"installed": "2.15.0+cpu", "ok": True}, "celery": {"installed": "5.7.0", "ok": True}},
    "sileroVad": "ok",
    "modelsRoot": "/data/models",
    "models": {"mobiuslabsgmbh/faster-whisper-large-v3-turbo": {"ok": True, "files": 6, "problems": ["/data/x"]},
               "pyannote/speaker-diarization-community-1": {"ok": False, "problems": ["size mismatch: /data/models/y"]}},
    "envFlags": {"HF_HUB_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1", "PYANNOTE_METRICS_ENABLED": "0",
                 "SOMETHING_ELSE": "secret-value"},
    "ok": False,
}
NET = {"internetBlocked": True, "reachableExternal": ["1.1.1.1:443"], "redis": "ok", "spring": "ok",
       "isolatedAndConnected": True}


def test_sanitize_keeps_only_allow_listed_fields():
    out = status.sanitize(ML, NET)
    text = json.dumps(out)
    assert "/data" not in text and "modelsRoot" not in text and "problems" not in text
    assert "secret-value" not in text and "SOMETHING_ELSE" not in text and "reachableExternal" not in text
    assert out["ml"] == {"ok": False, "versionsOk": True, "sileroVadOk": True,
                         "models": {"mobiuslabsgmbh/faster-whisper-large-v3-turbo": True,
                                    "pyannote/speaker-diarization-community-1": False}}
    assert out["offline"] == {"HF_HUB_OFFLINE": "1", "HF_HUB_DISABLE_TELEMETRY": "1", "PYANNOTE_METRICS_ENABLED": "0"}
    assert out["network"] == {"internetBlocked": True, "redis": "ok", "spring": "ok", "isolatedAndConnected": True}


def test_words_with_paths_or_odd_characters_are_dropped():
    assert status._word("unreachable (ConnectError)") == "unreachable (ConnectError)"
    assert status._word("C:\\Users\\x") is None
    assert status._word("/data/models") is None
    assert status._word("token=abc&x") is None
    assert status._word(123) is None


def test_failed_checks_report_unavailable():
    out = status.sanitize(None, None)
    assert out["ml"] == "unavailable" and out["network"] == "unavailable"


def test_write_is_atomic_and_leaves_no_temp_file(tmp_path):
    target = status.write(status.sanitize(ML, NET), tmp_path)
    assert target == tmp_path / "system" / "worker-status.json"
    assert json.loads(target.read_text(encoding="utf-8"))["network"]["isolatedAndConnected"] is True
    assert [p.name for p in (tmp_path / "system").iterdir()] == ["worker-status.json"]


def test_collect_never_raises_when_checks_fail(monkeypatch):
    import khmerdub_ai.ml.selfcheck as ml_selfcheck
    import khmerdub_ai.net.selfcheck as net_selfcheck

    def boom(*_a, **_k):
        raise RuntimeError("no speech stack here")

    monkeypatch.setattr(ml_selfcheck, "check", boom)
    monkeypatch.setattr(net_selfcheck, "check", boom)
    out = status.collect()
    assert out["ml"] == "unavailable" and out["network"] == "unavailable"
