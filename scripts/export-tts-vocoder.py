"""Export an AI4Bharat Indic-TTS HiFi-GAN generator to float32 ONNX.

Why float32 and not int8: EchoBharat's int8 vocoder rewrites every convolution
into DynamicQuantizeLinear + ConvInteger, which measured RTF 2.91 against 0.223
for the identical float32 graph - about 13x slower. Size grows 21 -> 53 MiB and
that is the correct trade for a real-time speech path.

  .tools/odia-export/Scripts/python.exe scripts/export-tts-vocoder.py hi
"""
from __future__ import annotations

import hashlib
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.nn.utils import remove_weight_norm, weight_norm

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".tools" / "indictts-vocoders" / "raw"
DEST = ROOT / "models" / "source" / "tts" / "indictts"
LRELU = 0.1
MEL_BINS = 80


def padding(kernel: int, dilation: int = 1) -> int:
    return int((kernel * dilation - dilation) / 2)


class ResBlock1(nn.Module):
    """Matches coqui-ai/TTS ResBlock1 module naming so the state dict loads as-is."""

    def __init__(self, channels: int, kernel_size: int, dilation: list[int]) -> None:
        super().__init__()
        self.convs1 = nn.ModuleList([
            weight_norm(nn.Conv1d(channels, channels, kernel_size, 1,
                                  dilation=d, padding=padding(kernel_size, d)))
            for d in dilation
        ])
        self.convs2 = nn.ModuleList([
            weight_norm(nn.Conv1d(channels, channels, kernel_size, 1,
                                  dilation=1, padding=padding(kernel_size, 1)))
            for _ in dilation
        ])

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        for c1, c2 in zip(self.convs1, self.convs2):
            xt = c1(F.leaky_relu(x, LRELU))
            xt = c2(F.leaky_relu(xt, LRELU))
            x = xt + x
        return x

    def strip(self) -> None:
        for layer in list(self.convs1) + list(self.convs2):
            remove_weight_norm(layer)


class HifiganGenerator(nn.Module):
    def __init__(self, params: dict) -> None:
        super().__init__()
        upsample_factors = params["upsample_factors"]
        upsample_kernels = params["upsample_kernel_sizes"]
        initial = params["upsample_initial_channel"]
        resblock_kernels = params["resblock_kernel_sizes"]
        resblock_dilations = params["resblock_dilation_sizes"]
        self.num_kernels = len(resblock_kernels)
        self.conv_pre = weight_norm(nn.Conv1d(MEL_BINS, initial, 7, 1, padding=3))
        self.ups = nn.ModuleList([
            weight_norm(nn.ConvTranspose1d(initial // (2 ** i), initial // (2 ** (i + 1)),
                                           k, u, padding=(k - u) // 2))
            for i, (u, k) in enumerate(zip(upsample_factors, upsample_kernels))
        ])
        self.resblocks = nn.ModuleList()
        channels = initial
        for i in range(len(self.ups)):
            channels = initial // (2 ** (i + 1))
            for k, d in zip(resblock_kernels, resblock_dilations):
                self.resblocks.append(ResBlock1(channels, k, d))
        self.conv_post = weight_norm(nn.Conv1d(channels, 1, 7, 1, padding=3))

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        x = self.conv_pre(mel)
        for i, up in enumerate(self.ups):
            x = up(F.leaky_relu(x, LRELU))
            total = None
            for j in range(self.num_kernels):
                out = self.resblocks[i * self.num_kernels + j](x)
                total = out if total is None else total + out
            x = total / self.num_kernels
        return torch.tanh(self.conv_post(F.leaky_relu(x)))

    def strip(self) -> None:
        remove_weight_norm(self.conv_pre)
        remove_weight_norm(self.conv_post)
        for layer in self.ups:
            remove_weight_norm(layer)
        for block in self.resblocks:
            block.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main(lang: str) -> int:
    base = RAW / lang / lang / "hifigan"
    config = json.loads((base / "config.json").read_text(encoding="utf-8"))
    params = config["generator_model_params"]
    audio = config["audio"]
    assert config["generator_model"] == "hifigan_generator", config["generator_model"]
    assert audio["sample_rate"] == 22050 and audio["hop_length"] == 256, audio
    assert audio["num_mels"] == MEL_BINS, audio

    checkpoint = torch.load(base / "best_model.pth", map_location="cpu", weights_only=False)
    state = {
        name[len("model_g."):]: tensor
        for name, tensor in checkpoint["model"].items()
        if name.startswith("model_g.")
    }
    model = HifiganGenerator(params)
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing or unexpected:
        print(f"  missing={list(missing)[:6]}\n  unexpected={list(unexpected)[:6]}")
        raise SystemExit("generator architecture does not match the checkpoint")
    model.eval()
    model.strip()  # fold weight_norm so the exported graph has plain convolutions

    out_dir = DEST / lang
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / "vocoder.onnx"
    frames = 200
    dummy = torch.randn(1, MEL_BINS, frames) * 0.5 - 2.0
    with torch.no_grad():
        reference = model(dummy).numpy()
        torch.onnx.export(
            model, (dummy,), str(target), input_names=["mel"], output_names=["audio"],
            dynamic_axes={"mel": {2: "frames"}, "audio": {2: "samples"}},
            opset_version=17, dynamo=False,
        )

    session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    produced = session.run(["audio"], {"mel": dummy.numpy()})[0]
    drift = float(np.max(np.abs(produced - reference)))

    # A different frame count must work too: the graph has to stay length-agnostic.
    longer = np.random.randn(1, MEL_BINS, 437).astype(np.float32) * 0.5 - 2.0
    start = time.perf_counter()
    long_audio = session.run(["audio"], {"mel": longer})[0]
    elapsed = time.perf_counter() - start
    seconds = long_audio.shape[-1] / audio["sample_rate"]

    print(f"  exported {target.relative_to(ROOT)}")
    print(f"  bytes    {target.stat().st_size:,}")
    print(f"  sha256   {sha256(target)}")
    print(f"  drift    {drift:.3e} (onnx vs torch)")
    print(f"  dynamic  437 frames -> {long_audio.shape[-1]} samples "
          f"({seconds:.2f}s, {elapsed * 1000:.0f} ms, rtf={elapsed / seconds:.3f})")
    if drift > 1e-3:
        raise SystemExit("ONNX output drifted from the PyTorch reference")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(*(sys.argv[1:] or ["hi"])))
