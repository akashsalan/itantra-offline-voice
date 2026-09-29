"""Inspect EchoBharat FastPitch/HiFi-GAN ONNX graph signatures and token tables.

Read-only. Does not modify models. Run with .tools/odia-export/Scripts/python.exe
"""
import json
import sys
from pathlib import Path

import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
TTS = ROOT / "models" / "source" / "tts" / "echobharat"


def describe(path: Path) -> None:
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    print(f"  {path.name}")
    for i in sess.get_inputs():
        print(f"    in  {i.name:24} {i.type:22} {i.shape}")
    for o in sess.get_outputs():
        print(f"    out {o.name:24} {o.type:22} {o.shape}")


def main() -> int:
    langs = sorted(p.name for p in TTS.iterdir() if p.is_dir())
    print(f"languages: {langs}\n")

    print("== graph signatures (hi) ==")
    describe(TTS / "hi" / "fastpitch-hi.int8.onnx")
    describe(TTS / "hi" / "hifigan-hi.int8.onnx")

    print("\n== token tables ==")
    for lang in langs:
        tokens = json.loads((TTS / lang / f"fastpitch-{lang}.tokens.json").read_text(encoding="utf-8"))
        dupes = len(tokens) - len(set(tokens))
        multi = [t for t in tokens if len(t) > 1]
        print(f"  {lang}: {len(tokens):4} symbols, {dupes} duplicate, "
              f"{len(multi)} multi-char, first5={tokens[:5]!r}")
        if multi[:6]:
            print(f"        multi-char sample: {multi[:6]!r}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
