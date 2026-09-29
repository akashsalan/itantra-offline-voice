"""Record neural voice sources in models.lock.json and build importable .itpack files.

Sources per language, under models/source/tts/indictts/<lang>/:
  acoustic.onnx  int8 FastPitch  (EchoBharat conversion of AI4Bharat Indic-TTS)
  vocoder.onnx   float32 HiFi-GAN (exported here from the AI4Bharat checkpoint)
  tokens.json    symbol table

Existing packs are preserved unless --force is given.

  .tools/odia-export/Scripts/python.exe scripts/build-voice-packs.py [langs...]
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "models" / "source" / "tts" / "indictts"
PACKS = ROOT / "models" / "packs"
LOCK = ROOT / "models" / "models.lock.json"

# Nine languages ship a fused acoustic graph. Odia ships the split topology:
# encoder + decoder, with the duration expansion done in the app between them,
# because no fused graph is published for it and a self-exported fused graph has
# a data-dependent output length the ONNX exporters cannot trace.
FUSED_FILES = ("acoustic.onnx", "vocoder.onnx", "tokens.json")
SPLIT_FILES = ("encoder.onnx", "decoder.onnx", "vocoder.onnx", "tokens.json")
SPLIT_LANGUAGES = {"or"}


def files_for(lang: str) -> tuple[str, ...]:
    return SPLIT_FILES if lang in SPLIT_LANGUAGES else FUSED_FILES
SAMPLE_RATE = 22050
ENGINE = "fastpitch-hifigan"

ACOUSTIC_SOURCE = (
    "https://huggingface.co/RaunakSaha/echobharat-models"
    "/resolve/2ad314c3ecafeb252bac82785ac541325596ac9c"
)
VOCODER_SOURCE = (
    "https://github.com/AI4Bharat/Indic-TTS/releases/download/v1-checkpoints-release"
)
LICENSE = "MIT (AI4Bharat Indic-TTS); int8 conversion MIT (EchoBharat)"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pack_id(lang: str) -> str:
    return f"tts.indictts.{lang}.fastpitch.hifigan.v1"


def describe(lang: str) -> list[dict]:
    directory = SOURCE / lang
    entries = []
    for name in files_for(lang):
        path = directory / name
        if not path.is_file():
            raise SystemExit(f"{lang}: missing {name}")
        entries.append({
            "name": name,
            "bytes": path.stat().st_size,
            "sha256": sha256(path),
            "path": f"models/source/tts/indictts/{lang}/{name}",
        })
    return entries


def update_lock(all_entries: dict[str, list[dict]]) -> None:
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    artifacts = lock["artifacts"]
    by_path = {item["path"]: item for item in artifacts}
    for lang, entries in sorted(all_entries.items()):
        for entry in entries:
            stem = entry["name"].split(".")[0]
            split = lang in SPLIT_LANGUAGES
            if stem == "vocoder":
                source = f"{VOCODER_SOURCE}/{lang}.zip#{lang}/hifigan/best_model.pth"
            elif split:
                # Both halves and the symbol table are derived here from the
                # FastPitch checkpoint, because nothing is published for Odia.
                source = f"{VOCODER_SOURCE}/{lang}.zip#{lang}/fastpitch/best_model.pth"
            elif stem == "tokens":
                source = f"{ACOUSTIC_SOURCE}/{lang}/fastpitch-{lang}.tokens.json"
            else:
                source = f"{ACOUSTIC_SOURCE}/{lang}/fastpitch-{lang}.int8.onnx"
            if stem == "vocoder":
                status = "float32_export_verified_against_torch_quality_not_evaluated"
            elif split:
                status = "split_export_verified_against_torch_quality_not_evaluated"
            else:
                status = "pinned_quality_not_evaluated"
            record = {
                "id": f"tts.indictts.{lang}.{stem}",
                "path": entry["path"],
                "bytes": entry["bytes"],
                "sha256": entry["sha256"],
                "source": source,
                "license": LICENSE,
                "status": status,
            }
            if entry["path"] in by_path:
                by_path[entry["path"]].update(record)
            else:
                artifacts.append(record)
                by_path[entry["path"]] = record
    lock["artifacts"] = sorted(artifacts, key=lambda item: item["id"])
    lock["generatedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    LOCK.write_text(json.dumps(lock, indent=2) + "\n", encoding="utf-8")
    print(f"  models.lock.json now holds {len(artifacts)} artifacts")


def build_pack(lang: str, entries: list[dict], force: bool) -> Path | None:
    PACKS.mkdir(parents=True, exist_ok=True)
    target = PACKS / f"{lang}-voice.itpack"
    if target.is_file() and not force:
        print(f"  {lang}: keeping existing {target.name}")
        return target
    manifest = {
        "schemaVersion": 1,
        "kind": "tts",
        "language": lang,
        "packId": pack_id(lang),
        "engine": ENGINE,
        "sampleRate": SAMPLE_RATE,
        "files": [
            {"path": entry["name"], "bytes": entry["bytes"], "sha256": entry["sha256"]}
            for entry in entries
        ],
    }
    temporary = target.with_suffix(".itpack.tmp")
    # manifest.json must be the first entry so the importer can validate before
    # writing any model bytes.
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED) as zip_file:
        zip_file.writestr("manifest.json", json.dumps(manifest, indent=2))
        for entry in entries:
            zip_file.write(SOURCE / lang / entry["name"], entry["name"])
    temporary.replace(target)
    total = sum(entry["bytes"] for entry in entries)
    print(f"  {lang}: {target.name} = {target.stat().st_size:,} bytes "
          f"(payload {total / (1 << 20):.2f} MiB)")
    return target


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("languages", nargs="*")
    parser.add_argument("--force", action="store_true", help="rebuild existing packs")
    args = parser.parse_args()

    available = sorted(
        directory.name for directory in SOURCE.iterdir()
        if directory.is_dir()
        and all((directory / name).is_file() for name in files_for(directory.name))
    ) if SOURCE.is_dir() else []
    selected = args.languages or available
    missing = [lang for lang in selected if lang not in available]
    if missing:
        raise SystemExit(f"not staged yet: {missing}. Run build-all-tts-vocoders.ps1 first.")
    if not selected:
        raise SystemExit("no staged voice sources found")

    print(f"staged languages: {selected}")
    entries = {lang: describe(lang) for lang in selected}
    update_lock(entries)
    for lang in selected:
        build_pack(lang, entries[lang], args.force)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
