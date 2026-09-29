"""Inspect an AI4Bharat/coqui FastPitch checkpoint and its character set.

Needed for languages EchoBharat never published (Odia), where both the acoustic
model and the token table have to be produced here.
"""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".tools" / "indictts-vocoders" / "raw"


def main(lang: str = "or") -> int:
    base = RAW / lang / lang / "fastpitch"
    config = json.loads((base / "config.json").read_text(encoding="utf-8"))

    print("== model identity ==")
    for key in ("model", "run_name", "use_phonemes", "phonemizer", "text_cleaner",
                "add_blank", "use_espeak_phonemes", "num_speakers", "use_d_vector_file",
                "use_speaker_embedding", "out_channels", "hidden_channels"):
        if key in config:
            print(f"  {key} = {json.dumps(config[key])}")

    print("\n== audio ==")
    for key in ("sample_rate", "hop_length", "win_length", "fft_size", "num_mels",
                "mel_fmin", "mel_fmax", "pitch_fmin", "pitch_fmax"):
        if key in config.get("audio", {}):
            print(f"  {key} = {config['audio'][key]}")

    characters = config.get("characters")
    print("\n== characters block ==")
    if characters:
        for key, value in characters.items():
            shown = value if not isinstance(value, str) or len(value) < 160 else value[:160] + "..."
            print(f"  {key} = {json.dumps(shown, ensure_ascii=False)}")

    print("\n== model_args (subset) ==")
    for key, value in sorted(config.get("model_args", {}).items()):
        if not isinstance(value, (dict, list)):
            print(f"  {key} = {json.dumps(value)}")

    checkpoint = torch.load(base / "best_model.pth", map_location="cpu", weights_only=False)
    print(f"\n== checkpoint keys ==\n  {sorted(checkpoint.keys())}")
    state = checkpoint["model"]
    prefixes = Counter(name.split(".")[0] for name in state)
    total = sum(t.numel() for t in state.values())
    print(f"\n== state_dict: {len(state)} tensors, {total:,} params "
          f"({total * 4 / (1 << 20):.1f} MiB fp32) ==")
    for prefix, count in prefixes.most_common(16):
        print(f"  {prefix:28} {count}")
    for name in ("emb.weight", "encoder.encoder.alpha", "decoder.decoder.alpha"):
        if name in state:
            print(f"  {name} -> {tuple(state[name].shape)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:]))
