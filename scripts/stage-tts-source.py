"""Assemble a voice-pack source directory and prove the real acoustic+vocoder pair.

Copies EchoBharat's int8 FastPitch and token table next to the float32 vocoder
exported from the matching AI4Bharat checkpoint, then synthesises one sentence
end to end so the pairing is verified before any pack is built.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
ECHO = ROOT / "models" / "source" / "tts" / "echobharat"
DEST = ROOT / "models" / "source" / "tts" / "indictts"
OUT = ROOT / ".tools" / "tts-pair-check"
SAMPLE_RATE = 22050
HOP = 256

SENTENCES = {
    "hi": "राहत शिविर स्कूल के पास है। वहाँ पानी और दवा उपलब्ध है।",
    "en": "The relief camp is near the school and has water and medicine.",
    "bn": "আশ্রয় শিবিরটি স্কুলের কাছে আছে।",
    "gu": "રાહત શિબિર શાળા પાસે છે.",
    "kn": "ಪರಿಹಾರ ಶಿಬಿರ ಶಾಲೆಯ ಹತ್ತಿರ ಇದೆ.",
    "ml": "ദുരിതാശ്വാസ ക്യാമ്പ് സ്കൂളിനു സമീപമാണ്.",
    "mr": "मदत छावणी शाळेजवळ आहे.",
    "ta": "நிவாரண முகாம் பள்ளிக்கு அருகில் உள்ளது.",
    "te": "సహాయ శిబిరం పాఠశాల దగ్గర ఉంది.",
}

EQUIVALENTS = {"\u0964": ".", "\u0965": ".", "\u00a0": " "}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def encode(text: str, table: list[str]) -> tuple[list[int], list[str]]:
    ids: dict[str, int] = {}
    for index, symbol in enumerate(table):
        ids.setdefault(symbol, index)
    folded = "".join(EQUIVALENTS.get(ch, ch) for ch in " ".join(text.split()))
    longest = max(len(s) for s in ids)
    tokens: list[int] = []
    dropped: list[str] = []
    position = 0
    while position < len(folded):
        for span in range(min(longest, len(folded) - position), 0, -1):
            piece = folded[position:position + span]
            if piece in ids:
                tokens.append(ids[piece])
                position += span
                break
        else:
            dropped.append(folded[position])
            position += 1
    return tokens, dropped


def write_wav(path: Path, audio: np.ndarray) -> float:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())
    return len(pcm) / SAMPLE_RATE


def stage(lang: str) -> dict:
    out_dir = DEST / lang
    out_dir.mkdir(parents=True, exist_ok=True)
    vocoder = out_dir / "vocoder.onnx"
    if not vocoder.is_file():
        raise SystemExit(f"run export-tts-vocoder.py {lang} first")

    shutil.copyfile(ECHO / lang / f"fastpitch-{lang}.int8.onnx", out_dir / "acoustic.onnx")
    shutil.copyfile(ECHO / lang / f"fastpitch-{lang}.tokens.json", out_dir / "tokens.json")

    table = json.loads((out_dir / "tokens.json").read_text(encoding="utf-8"))
    tokens, dropped = encode(SENTENCES[lang], table)
    if not tokens:
        raise SystemExit(f"no tokens produced for {lang}")

    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    acoustic = ort.InferenceSession(str(out_dir / "acoustic.onnx"), options,
                                    providers=["CPUExecutionProvider"])
    voc = ort.InferenceSession(str(vocoder), options, providers=["CPUExecutionProvider"])

    start = time.perf_counter()
    mel = acoustic.run(["mel"], {"text": np.asarray([tokens], dtype=np.int64)})[0]
    acoustic_ms = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    audio = voc.run(["audio"], {"mel": mel.astype(np.float32)})[0].reshape(-1)
    vocoder_ms = (time.perf_counter() - start) * 1000

    seconds = write_wav(OUT / f"{lang}-fp32-vocoder.wav", audio)
    total = acoustic_ms + vocoder_ms
    record = {
        "language": lang,
        "tokens": len(tokens),
        "dropped": dropped,
        "melFrames": int(mel.shape[-1]),
        "audioSeconds": round(seconds, 3),
        "acousticMs": round(acoustic_ms, 1),
        "vocoderMs": round(vocoder_ms, 1),
        "rtf": round(total / 1000 / seconds, 3),
        "peak": round(float(np.max(np.abs(audio))), 4),
        "files": {
            name: {"bytes": (out_dir / name).stat().st_size, "sha256": sha256(out_dir / name)}
            for name in ("acoustic.onnx", "vocoder.onnx", "tokens.json")
        },
    }
    print(f"  {lang}: tokens={len(tokens)} audio={seconds:.2f}s "
          f"acoustic={acoustic_ms:.0f}ms vocoder={vocoder_ms:.0f}ms rtf={record['rtf']:.3f} "
          f"peak={record['peak']:.3f} dropped={len(dropped)}")
    return record


def main(*langs: str) -> int:
    selected = list(langs) or ["hi"]
    records = [stage(lang) for lang in selected]
    report = ROOT / "docs" / "results" / "indictts-voice-pairs.json"
    existing = json.loads(report.read_text(encoding="utf-8")) if report.is_file() else {}
    existing.update({
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "sampleRate": SAMPLE_RATE,
        "acousticSource": "RaunakSaha/echobharat-models@2ad314c3ecafeb252bac82785ac541325596ac9c (int8)",
        "vocoderSource": "AI4Bharat/Indic-TTS v1-checkpoints-release (float32, exported here)",
    })
    pairs = {item["language"]: item for item in existing.get("pairs", [])}
    for record in records:
        pairs[record["language"]] = record
    existing["pairs"] = [pairs[key] for key in sorted(pairs)]
    report.write_text(json.dumps(existing, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nreport: {report.relative_to(ROOT)}")
    print(f"wavs:   {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:]))
