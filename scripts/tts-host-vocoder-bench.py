"""Why is the int8 HiFi-GAN slow? Check thread scaling and op-level cost."""
from __future__ import annotations

import json
import time
from collections import Counter
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
TTS = ROOT / "models" / "source" / "tts" / "echobharat"
LANG = "hi"
SAMPLE_RATE = 22050


def token_ids(text: str) -> list[int]:
    table = json.loads((TTS / LANG / f"fastpitch-{LANG}.tokens.json").read_text(encoding="utf-8"))
    mapping: dict[str, int] = {}
    for index, symbol in enumerate(table):
        mapping.setdefault(symbol, index)
    longest = max(len(s) for s in mapping)
    ids, position = [], 0
    while position < len(text):
        for span in range(min(longest, len(text) - position), 0, -1):
            piece = text[position:position + span]
            if piece in mapping:
                ids.append(mapping[piece])
                position += span
                break
        else:
            position += 1
    return ids


def make(path: Path, threads: int) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.inter_op_num_threads = 1
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def main() -> int:
    print("== vocoder graph op histogram ==")
    model = onnx.load(str(TTS / LANG / f"hifigan-{LANG}.int8.onnx"))
    ops = Counter(node.op_type for node in model.graph.node)
    for op, count in ops.most_common(14):
        print(f"  {op:28} {count}")

    acoustic_model = onnx.load(str(TTS / LANG / f"fastpitch-{LANG}.int8.onnx"))
    acoustic_ops = Counter(node.op_type for node in acoustic_model.graph.node)
    print("\n== acoustic graph op histogram (top) ==")
    for op, count in acoustic_ops.most_common(8):
        print(f"  {op:28} {count}")

    ids = token_ids("राहत शिविर स्कूल के पास है, वहाँ पानी और दवा उपलब्ध है")
    mel = make(TTS / LANG / f"fastpitch-{LANG}.int8.onnx", 4).run(
        ["mel"], {"text": np.asarray([ids], dtype=np.int64)})[0]
    frames = mel.shape[-1]
    seconds = frames * 256 / SAMPLE_RATE
    print(f"\nmel frames={frames}  implied audio={seconds:.2f}s")

    print("\n== vocoder thread scaling ==")
    for threads in (1, 2, 4, 8, 0):
        session = make(TTS / LANG / f"hifigan-{LANG}.int8.onnx", threads)
        session.run(["audio"], {"mel": mel})  # warm up
        best = min(
            (time.perf_counter_ns(), session.run(["audio"], {"mel": mel}), time.perf_counter_ns())
            for _ in range(3)
        )
        elapsed_ms = (best[2] - best[0]) / 1e6
        label = "auto" if threads == 0 else str(threads)
        print(f"  threads={label:>4}  {elapsed_ms:9.1f} ms   rtf={elapsed_ms / 1000 / seconds:5.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
