"""Test-only fault injection for recovery scenarios.

Active only when KHMERDUB_DIAGNOSTICS=true. Rules are read from <storage>/temp/diagnostics/faults.json:
  [{"stage": "EXTRACT_AUDIO", "chunkIndex": 1, "attemptNo": 1,
    "sleepBeforeS": 30, "suppressHeartbeat": false, "sleepBeforeCompleteS": 0}]
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class Fault:
    sleep_before_s: float = 0
    suppress_heartbeat: bool = False
    sleep_before_complete_s: float = 0


def load_rules(storage_root: Path) -> list[dict[str, Any]]:
    path = storage_root / "temp" / "diagnostics" / "faults.json"
    if not path.is_file():
        return []
    try:
        rules = json.loads(path.read_text(encoding="utf-8"))
        return rules if isinstance(rules, list) else []
    except (OSError, ValueError) as e:
        log.warning("Ignoring unreadable faults.json: %s", e)
        return []


def match(rules: list[dict[str, Any]], claim: dict[str, Any]) -> Fault | None:
    for r in rules:
        if r.get("stage") not in (None, claim.get("stage")):
            continue
        if r.get("chunkIndex") not in (None, claim.get("chunkIndex")):
            continue
        if r.get("attemptNo") not in (None, claim.get("attemptNo")):
            continue
        return Fault(sleep_before_s=float(r.get("sleepBeforeS", 0)),
                     suppress_heartbeat=bool(r.get("suppressHeartbeat", False)),
                     sleep_before_complete_s=float(r.get("sleepBeforeCompleteS", 0)))
    return None
