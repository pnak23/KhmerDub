"""Speech stages with the REAL models, offline, inside the khmerdub-worker:ml image (stdlib unittest, no pytest).

Run (from the repo root):
  docker run --rm --network none -e HF_HUB_OFFLINE=1 -e KHMERDUB_INTERNAL_TOKEN=<32+ chars, any> \
    -e KHMERDUB_STORAGE_ROOT=/data -e KHMERDUB_MODELS_DIR=/models \
    -v <test storage>:/data -v D:/KhmerDub/data/models:/models:ro -v ./apps/ai/khmerdub_ai:/app/khmerdub_ai:ro \
    -v ./apps/ai/tests_ml:/app/tests_ml:ro khmerdub-worker:ml python -m unittest discover -s /app/tests_ml -v
Expects /data/projects/mltest/extracted/clip1-90s.wav (90 s of real Chinese dialogue).
"""

from __future__ import annotations

import json
import socket
import time
import unittest
import uuid
from pathlib import Path

from khmerdub_ai.engine.runner import AttemptContext, Cancelled, Tools
from khmerdub_ai.stages import diarize, speech

ROOT = Path("/data")
AUDIO = "projects/mltest/extracted/clip1-90s.wav"
DURATION_MS = 90_000


def ctx(stage: str, inp: dict) -> AttemptContext:
    attempt = str(uuid.uuid4())
    return AttemptContext(claim={"attemptId": attempt, "stage": stage, "workDir": f"projects/mltest/temp/attempts/{attempt}",
                                 "input": inp}, storage_root=ROOT, tools=Tools(None, None))


def run(stage: str, handler, inp: dict) -> tuple[AttemptContext, dict]:
    c = ctx(stage, inp)
    c.work_dir.mkdir(parents=True, exist_ok=True)
    return c, handler(c)


def path_of(result: dict, role: str) -> str:
    return next(o["path"] for o in result["outputs"] if o["role"] == role)


class SpeechStagesWithRealModels(unittest.TestCase):
    results: dict = {}

    @classmethod
    def setUpClass(cls):
        cls.t0 = time.monotonic()

    def test_1_network_is_off(self):
        with self.assertRaises(OSError):
            socket.create_connection(("1.1.1.1", 443), timeout=3)

    def test_2_pipeline_end_to_end(self):
        r = {}
        _, out = run("SPEECH_MAP", speech.speech_map, {"audioPath": AUDIO, "durationMs": DURATION_MS})
        smap = path_of(out, "speechMap")
        regions = json.loads((ROOT / smap).read_text())["regions"]
        self.assertGreater(len(regions), 5)
        r["speechRegions"] = len(regions)

        _, out = run("DETECT_LANGUAGE", speech.detect_language, {"audioPath": AUDIO, "durationMs": DURATION_MS,
                                                                  "speechMapPath": smap, "threads": 4, "windows": 2})
        self.assertEqual(out["metadata"]["language"], "zh")

        transcripts = []
        for i, (s, e, cs, ce) in enumerate([(0, 47_500, 0, 45_000), (42_500, 90_000, 45_000, 90_000)]):
            _, out = run("TRANSCRIBE", speech.transcribe, {"audioPath": AUDIO, "chunkIndex": i, "startMs": s, "endMs": e,
                                                           "coreStartMs": cs, "coreEndMs": ce, "language": "zh",
                                                           "beamSize": 5, "threads": 4})
            t = path_of(out, "transcript")
            doc = json.loads((ROOT / t).read_text())
            words = [w for seg in doc["segments"] for w in seg["words"]]
            self.assertGreater(len(words), 20)
            self.assertTrue(all(s / 1000 - 0.5 <= w["s"] <= e / 1000 + 0.5 for w in words), "absolute times in range")
            transcripts.append(t)
        r["transcribeMetrics"] = out["metrics"]

        chunks = []
        for i, (s, e) in enumerate([(0, 60_000), (30_000, 90_000)]):
            c, out = run("DIARIZE", diarize.diarize, {"audioPath": AUDIO, "chunkIndex": i, "startMs": s, "endMs": e,
                                                       "segmentationStep": 0.1, "threads": 8})
            emb = path_of(out, "embeddings")
            self.assertIn("/private/fingerprints/", "/" + emb)
            meta = json.loads((ROOT / path_of(out, "diarization")).read_text())
            self.assertGreaterEqual(len(meta["labels"]), 1)
            self.assertNotIn("embedding", json.dumps(meta).replace("embeddingLabels", ""), "no vectors in JSON")
            chunks.append({"index": i, "diarizationPath": path_of(out, "diarization"), "embeddingsPath": emb})
        r["diarizeMetrics"] = out["metrics"]

        _, out = run("RECONCILE_SPEAKERS", diarize.reconcile_speakers, {"audioPath": AUDIO, "chunks": chunks,
                                                                        "threshold": 0.5, "samplesPerSpeaker": 3})
        speakers_doc = json.loads((ROOT / path_of(out, "speakers")).read_text())
        self.assertGreaterEqual(len(speakers_doc["speakers"]), 1)
        samples = [o for o in out["outputs"] if o["role"] == "sample"]
        self.assertTrue(samples and all("/private/voice-samples/" in "/" + o["path"] for o in samples))
        r["speakers"] = len(speakers_doc["speakers"])
        r["mustLinks"] = speakers_doc["mustLinks"]

        _, out = run("BUILD_DIALOGUE", diarize.build_dialogue, {"transcriptPaths": transcripts,
                                                                "speakersPath": path_of(out, "speakers"),
                                                                "speechMapPath": smap, "language": "zh"})
        dialogue = json.loads((ROOT / path_of(out, "dialogue")).read_text())
        s = dialogue["summary"]
        self.assertGreater(s["lines"], 10)
        self.assertEqual(s["duplicateWords"], 0, "overlap words emitted once")
        codes = {sp["code"] for sp in dialogue["speakers"]}
        self.assertTrue(all(ln["speaker"] in codes or ln["speaker"] is None for ln in dialogue["lines"]))
        r["dialogueSummary"] = s
        r["firstLines"] = [f'{ln["startMs"]/1000:.1f}s {ln["speaker"]}: {ln["text"]}' for ln in dialogue["lines"][:5]]
        print(json.dumps(r, ensure_ascii=False, indent=1))

    def test_3_cancel_stops_diarization_promptly(self):
        c = ctx("DIARIZE", {"audioPath": AUDIO, "chunkIndex": 9, "startMs": 0, "endMs": 90_000,
                            "segmentationStep": 0.1, "threads": 8})
        c.work_dir.mkdir(parents=True, exist_ok=True)
        import threading
        threading.Timer(3.0, c.request_cancel).start()
        t0 = time.monotonic()
        with self.assertRaises(Cancelled):
            diarize.diarize(c)
        self.assertLess(time.monotonic() - t0, 30.0)
        print(json.dumps({"cancelLatencySeconds": round(time.monotonic() - t0 - 3.0, 1)}))

    def test_9_purge_leaves_no_private_voice_data(self):
        """These tests call the stages directly, so nothing else purges their attempt folders (in the app Spring
        does it on every attempt end). Remove them and prove no embedding or voice sample is left behind."""
        import shutil
        attempts = ROOT / "projects/mltest/temp/attempts"
        shutil.rmtree(attempts, ignore_errors=True)
        project = ROOT / "projects/mltest"
        left = [str(p) for p in project.rglob("*") if p.suffix == ".npy" or "private" in p.parts]
        self.assertEqual(left, [])


if __name__ == "__main__":
    unittest.main(verbosity=2)
