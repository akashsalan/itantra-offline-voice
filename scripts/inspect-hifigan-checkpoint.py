"""Inspect an AI4Bharat/coqui HiFi-GAN training checkpoint before exporting."""
from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".tools" / "indictts-vocoders" / "raw"


def main(lang: str = "hi") -> int:
    base = RAW / lang / lang / "hifigan"
    config = json.loads((base / "config.json").read_text(encoding="utf-8"))
    print("== vocoder config keys ==")
    for key in sorted(config):
        value = config[key]
        if not isinstance(value, (dict, list)) or key in {"generator_model_params", "audio"}:
            print(f"  {key} = {json.dumps(value)[:200]}")

    print("\n== audio params ==")
    for key, value in sorted(config.get("audio", {}).items()):
        print(f"  {key} = {value}")

    checkpoint = torch.load(base / "best_model.pth", map_location="cpu", weights_only=False)
    print(f"\n== checkpoint top-level keys ==\n  {sorted(checkpoint.keys())}")

    for key in ("model", "model_g", "generator"):
        if key in checkpoint and isinstance(checkpoint[key], dict):
            state = checkpoint[key]
            prefixes = Counter(name.split(".")[0] for name in state)
            print(f"\n== '{key}' state_dict: {len(state)} tensors ==")
            for prefix, count in prefixes.most_common(12):
                print(f"  {prefix:24} {count}")
            generator = {n: t for n, t in state.items() if n.startswith("model_g.")}
            if generator:
                print(f"  model_g tensors: {len(generator)}")
                total = sum(t.numel() for t in generator.values())
                print(f"  model_g params : {total:,} ({total * 4 / (1 << 20):.1f} MiB fp32)")
                for name in list(generator)[:8]:
                    print(f"    {name}  {tuple(generator[name].shape)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*sys.argv[1:]))
