"""Quantize the split FastPitch graphs to int8 on MatMul only.

FastPitch is transformer-heavy, so almost all of its weight bulk sits in MatMul.
Dynamic int8 on MatMul is well optimised in ONNX Runtime and shrinks the graph a
lot. Convolutions are deliberately left in float32: quantizing them produces
DynamicQuantizeLinear + ConvInteger, which measured about 13x slower than the
float32 kernels when we benchmarked the HiFi-GAN vocoder.

  .tools/tts-export/Scripts/python.exe scripts/quantize-tts-acoustic.py or
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
from onnxruntime.quantization import QuantType, quantize_dynamic

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / "models" / "source" / "tts" / "indictts"
OUT = ROOT / ".tools" / "tts-pair-check"
SAMPLE_RATE = 22050


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expand(features: np.ndarray, durations: np.ndarray) -> np.ndarray:
    counts = np.maximum(durations.reshape(-1).astype(np.int64), 0)
    index = np.repeat(np.arange(counts.shape[0], dtype=np.int64), counts)
    return features[:, :, index]


def write_wav(path: Path, audio: np.ndarray) -> float:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())
    return len(pcm) / SAMPLE_RATE


def session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def run(encoder: Path, decoder: Path, vocoder: Path, tokens: list[int]) -> tuple[np.ndarray, dict]:
    enc, dec, voc = session(encoder), session(decoder), session(vocoder)
    text = np.asarray([tokens], dtype=np.int64)
    # Warm up: ORT's first run includes arena and kernel initialisation, which
    # otherwise dominates and makes the vocoder look several times slower.
    warm_features, warm_durations = enc.run(None, {"text": text})
    warm_mel = dec.run(None, {"features": expand(warm_features, warm_durations)})[0]
    voc.run(["audio"], {"mel": warm_mel.astype(np.float32)})
    start = time.perf_counter()
    features, durations = enc.run(None, {"text": text})
    enc_ms = (time.perf_counter() - start) * 1000
    expanded = expand(features, durations)
    start = time.perf_counter()
    mel = dec.run(None, {"features": expanded})[0]
    dec_ms = (time.perf_counter() - start) * 1000
    start = time.perf_counter()
    audio = voc.run(["audio"], {"mel": mel.astype(np.float32)})[0].reshape(-1)
    voc_ms = (time.perf_counter() - start) * 1000
    seconds = audio.shape[0] / SAMPLE_RATE
    total = enc_ms + dec_ms + voc_ms
    return audio, {
        "frames": int(mel.shape[2]), "audioSeconds": round(seconds, 3),
        "encoderMs": round(enc_ms, 1), "decoderMs": round(dec_ms, 1),
        "vocoderMs": round(voc_ms, 1), "rtf": round(total / 1000 / seconds, 3),
        "peak": round(float(np.max(np.abs(audio))), 4),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("language")
    args = parser.parse_args()
    lang = args.language
    directory = DEST / lang

    table = json.loads((directory / "tokens.json").read_text(encoding="utf-8"))
    ids = {symbol: index for index, symbol in enumerate(table)}
    # A real Odia sentence, so this measures a realistic token count.
    sentence = "ମୁଖ୍ୟ ରାସ୍ତା ବନ୍ଦ ଅଛି। ଉତ୍ତର ଦିଗକୁ ଯାଆନ୍ତୁ।"
    folded = sentence.replace("\u0964", ".")
    tokens: list[int] = []
    longest = max(len(s) for s in ids)
    position = 0
    while position < len(folded):
        for span in range(min(longest, len(folded) - position), 0, -1):
            piece = folded[position:position + span]
            if piece in ids:
                tokens.append(ids[piece])
                position += span
                break
        else:
            position += 1
    print(f"  sentence -> {len(tokens)} tokens")

    vocoder = directory / "vocoder.onnx"
    float_encoder = directory / "encoder.onnx"
    float_decoder = directory / "decoder.onnx"
    backup = directory / "float32"
    backup.mkdir(exist_ok=True)
    for path in (float_encoder, float_decoder):
        target = backup / path.name
        if not target.exists():
            shutil.copyfile(path, target)

    print("\n== float32 baseline ==")
    audio32, before = run(backup / "encoder.onnx", backup / "decoder.onnx", vocoder, tokens)
    print(f"  {before}")
    write_wav(OUT / f"{lang}-fp32.wav", audio32)

    print("\n== quantizing MatMul to int8 ==")
    for name in ("encoder.onnx", "decoder.onnx"):
        source = backup / name
        # Full dynamic int8, including the Conv1d feed-forward blocks that hold
        # most of FastPitch's weight. Safe here: the published int8 FastPitch for
        # the other nine languages measures about RTF 0.2. The ConvInteger
        # slowdown we measured was specific to HiFi-GAN's transposed-conv stack,
        # which is why the vocoder stays float32.
        quantize_dynamic(
            model_input=str(source), model_output=str(directory / name),
            weight_type=QuantType.QInt8,
        )
        saved = source.stat().st_size - (directory / name).stat().st_size
        print(f"  {name}: {source.stat().st_size:,} -> "
              f"{(directory / name).stat().st_size:,} bytes (-{saved / (1 << 20):.1f} MiB)")

    print("\n== int8 MatMul ==")
    audio8, after = run(float_encoder, float_decoder, vocoder, tokens)
    print(f"  {after}")
    write_wav(OUT / f"{lang}-int8-matmul.wav", audio8)

    shortest = min(audio32.shape[0], audio8.shape[0])
    if shortest > 0:
        a = audio32[:shortest].astype(np.float64)
        b = audio8[:shortest].astype(np.float64)
        denominator = float(np.linalg.norm(a) * np.linalg.norm(b))
        correlation = float(np.dot(a, b) / denominator) if denominator else 0.0
    else:
        correlation = 0.0
    print(f"\n  waveform correlation fp32 vs int8: {correlation:.4f}")
    print(f"  frames {before['frames']} -> {after['frames']}")

    payload = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "language": lang, "tokens": len(tokens),
        "float32": before, "int8Matmul": after,
        "waveformCorrelation": round(correlation, 4),
        "note": "int8 applied to MatMul only; convolutions stay float32 because "
                "ConvInteger measured about 13x slower than float32 Conv.",
        "files": {
            name: {
                "bytes": (directory / name).stat().st_size,
                "sha256": sha256(directory / name),
            } for name in ("encoder.onnx", "decoder.onnx", "vocoder.onnx", "tokens.json")
        },
    }
    report = ROOT / "docs" / "results" / f"indictts-{lang}-acoustic.json"
    report.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"  report: {report.relative_to(ROOT)}")
    total = sum(payload["files"][n]["bytes"] for n in payload["files"])
    print(f"  pack payload: {total / (1 << 20):.2f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
