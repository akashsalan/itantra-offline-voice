"""Pick the Odia precision mix and measure it honestly.

Findings that drive this:
  * encoder int8  -> smaller and faster   (552 -> 270 ms)
  * decoder int8  -> smaller but 3.6x SLOWER (1970 -> 7182 ms) and waveform
                     correlation against float32 falls to 0.51
So the encoder is quantized and the decoder stays float32, matching the reason the
vocoder stays float32: dynamic int8 on convolution stacks produces ConvInteger,
which ONNX Runtime executes far slower than float32 Conv.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
DIR = ROOT / "models" / "source" / "tts" / "indictts" / "or"
OUT = ROOT / ".tools" / "tts-pair-check"
SAMPLE_RATE = 22050
SENTENCE = "ମୁଖ୍ୟ ରାସ୍ତା ବନ୍ଦ ଅଛି। ଉତ୍ତର ଦିଗକୁ ଯାଆନ୍ତୁ।"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def expand(features: np.ndarray, durations: np.ndarray) -> np.ndarray:
    counts = np.maximum(durations.reshape(-1).astype(np.int64), 0)
    return features[:, :, np.repeat(np.arange(counts.shape[0], dtype=np.int64), counts)]


def session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def encode(text: str, table: list[str]) -> list[int]:
    ids: dict[str, int] = {}
    for index, symbol in enumerate(table):
        ids.setdefault(symbol, index)
    folded = text.replace("\u0964", ".").replace("\u0965", ".")
    longest = max(len(s) for s in ids)
    tokens: list[int] = []
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
    return tokens


def main() -> int:
    table = json.loads((DIR / "tokens.json").read_text(encoding="utf-8"))
    tokens = np.asarray([encode(SENTENCE, table)], dtype=np.int64)
    backup = DIR / "float32"

    # int8 encoder (already produced), float32 decoder restored from the backup.
    shutil.copyfile(backup / "decoder.onnx", DIR / "decoder.onnx")

    enc = session(DIR / "encoder.onnx")
    dec = session(DIR / "decoder.onnx")
    voc = session(DIR / "vocoder.onnx")

    # Warm every graph first: ORT's first call includes arena/kernel setup.
    features, durations = enc.run(None, {"text": tokens})
    mel = dec.run(None, {"features": expand(features, durations)})[0]
    voc.run(["audio"], {"mel": mel.astype(np.float32)})

    def best(fn, repeats: int = 3) -> tuple[object, float]:
        result = None
        fastest = float("inf")
        for _ in range(repeats):
            start = time.perf_counter()
            result = fn()
            fastest = min(fastest, (time.perf_counter() - start) * 1000)
        return result, fastest

    (features, durations), enc_ms = best(lambda: enc.run(None, {"text": tokens}))
    expanded = expand(features, durations)
    mel, dec_ms = best(lambda: dec.run(None, {"features": expanded})[0])
    mel32 = mel.astype(np.float32)
    audio, voc_ms = best(lambda: voc.run(["audio"], {"mel": mel32})[0].reshape(-1))

    seconds = audio.shape[0] / SAMPLE_RATE
    total = enc_ms + dec_ms + voc_ms
    write = OUT / "or-final.wav"
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    write.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(write), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())

    files = {
        name: {"bytes": (DIR / name).stat().st_size, "sha256": sha256(DIR / name)}
        for name in ("encoder.onnx", "decoder.onnx", "vocoder.onnx", "tokens.json")
    }
    payload = sum(item["bytes"] for item in files.values())
    record = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "language": "or",
        "precision": "encoder int8, decoder float32, vocoder float32",
        "tokens": int(tokens.shape[1]),
        "frames": int(mel.shape[2]),
        "audioSeconds": round(seconds, 3),
        "encoderMs": round(enc_ms, 1),
        "decoderMs": round(dec_ms, 1),
        "vocoderMs": round(voc_ms, 1),
        "rtf": round(total / 1000 / seconds, 3),
        "peak": round(float(np.max(np.abs(audio))), 4),
        "payloadMiB": round(payload / (1 << 20), 2),
        "files": files,
    }
    report = ROOT / "docs" / "results" / "indictts-or-acoustic.json"
    report.write_text(json.dumps(record, indent=2), encoding="utf-8")

    print(f"  tokens={record['tokens']} frames={record['frames']} "
          f"audio={seconds:.2f}s peak={record['peak']}")
    print(f"  encoder={enc_ms:.0f}ms decoder={dec_ms:.0f}ms vocoder={voc_ms:.0f}ms "
          f"rtf={record['rtf']}")
    print(f"  payload={record['payloadMiB']} MiB")
    print(f"  wav={write.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
