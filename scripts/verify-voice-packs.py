"""Verify voice .itpack files against models.lock.json the way the app importer does.

Mirrors VoicePacks.importStream: manifest must be the first entry, kind must be
"tts", pack id and engine and sample rate must match the pinned profile, the file
set must match the catalogue exactly, and every declared size and SHA-256 must
agree with both the lock and the actual bytes.
"""
from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKS = ROOT / "models" / "packs"
LOCK = ROOT / "models" / "models.lock.json"
FUSED_FILES = {"acoustic.onnx", "vocoder.onnx", "tokens.json"}
SPLIT_FILES = {"encoder.onnx", "decoder.onnx", "vocoder.onnx", "tokens.json"}
SPLIT_LANGUAGES = {"or"}


def expected_files(lang: str) -> set[str]:
    return SPLIT_FILES if lang in SPLIT_LANGUAGES else FUSED_FILES


def main() -> int:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    pinned: dict[str, dict[str, dict]] = {}
    for artifact in lock["artifacts"]:
        path = artifact["path"]
        if not path.startswith("models/source/tts/indictts/"):
            continue
        parts = path.split("/")
        pinned.setdefault(parts[-2], {})[parts[-1]] = artifact

    packs = sorted(PACKS.glob("*-voice.itpack"))
    if not packs:
        print("no voice packs built yet")
        return 0

    failures: list[str] = []
    for pack in packs:
        lang = pack.name.split("-voice")[0]
        catalogue = pinned.get(lang)
        problems: list[str] = []
        if not catalogue:
            failures.append(f"{pack.name}: language not pinned in models.lock.json")
            continue
        with zipfile.ZipFile(pack) as zip_file:
            names = zip_file.namelist()
            if names[0] != "manifest.json":
                problems.append("manifest.json is not the first entry")
            manifest = json.loads(zip_file.read("manifest.json"))
            if manifest.get("kind") != "tts":
                problems.append(f"kind={manifest.get('kind')!r}")
            if manifest.get("schemaVersion") != 1:
                problems.append("schemaVersion must be 1")
            if manifest.get("engine") != "fastpitch-hifigan":
                problems.append(f"engine={manifest.get('engine')!r}")
            if manifest.get("sampleRate") != 22050:
                problems.append(f"sampleRate={manifest.get('sampleRate')!r}")
            if manifest.get("language") != lang:
                problems.append(f"language={manifest.get('language')!r}")
            expected_id = f"tts.indictts.{lang}.fastpitch.hifigan.v1"
            if manifest.get("packId") != expected_id:
                problems.append(f"packId={manifest.get('packId')!r}")

            declared = {entry["path"]: entry for entry in manifest["files"]}
            if set(declared) != expected_files(lang):
                problems.append(f"declared files {sorted(declared)}")
            if set(names[1:]) != expected_files(lang):
                problems.append(f"archive entries {sorted(names[1:])}")

            for name, entry in sorted(declared.items()):
                artifact = catalogue.get(name)
                if artifact is None:
                    problems.append(f"{name}: not in the catalogue")
                    continue
                if entry["bytes"] != artifact["bytes"] or entry["sha256"] != artifact["sha256"]:
                    problems.append(f"{name}: manifest differs from models.lock.json")
                raw = zip_file.read(name)
                if len(raw) != entry["bytes"]:
                    problems.append(f"{name}: stored size {len(raw)} != declared {entry['bytes']}")
                digest = hashlib.sha256(raw).hexdigest()
                if digest != entry["sha256"]:
                    problems.append(f"{name}: SHA-256 mismatch in the archive")

        payload = sum(entry["bytes"] for entry in declared.values())
        if problems:
            failures.append(f"{pack.name}: " + "; ".join(problems))
            print(f"  FAIL {pack.name}")
            for problem in problems:
                print(f"       {problem}")
        else:
            print(f"  ok   {pack.name}  {pack.stat().st_size:,} bytes "
                  f"(payload {payload / (1 << 20):.2f} MiB)")

    print(f"\n{len(packs) - len(failures)}/{len(packs)} voice packs valid")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())

