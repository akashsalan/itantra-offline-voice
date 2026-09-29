"""Is the vocoder slow because of int8, or because HiFi-GAN V1 is just heavy?

Rebuilds standard HiFi-GAN V1 (random weights - only op shapes matter for timing),
exports fp32 and fp16 ONNX, and times them against EchoBharat's int8 file on an
identical mel tensor.

The int8 graph has 74 Conv + 4 ConvTranspose and hop 256, which matches V1 with
upsample_rates [8,8,2,2] exactly, so the comparison is architecturally fair.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
import torch.nn.functional as F

ROOT = Path(__file__).resolve().parents[1]
TTS = ROOT / "models" / "source" / "tts" / "echobharat"
WORK = ROOT / ".tools" / "tts-vocoder-bench"
SAMPLE_RATE = 22050
HOP = 256
LRELU = 0.1


def pad_for(kernel: int, dilation: int = 1) -> int:
    return int((kernel * dilation - dilation) / 2)


class ResBlock1(nn.Module):
    def __init__(self, channels: int, kernel: int, dilations: list[int]) -> None:
        super().__init__()
        self.convs1 = nn.ModuleList([
            nn.Conv1d(channels, channels, kernel, 1, dilation=d, padding=pad_for(kernel, d))
            for d in dilations
        ])
        self.convs2 = nn.ModuleList([
            nn.Conv1d(channels, channels, kernel, 1, dilation=1, padding=pad_for(kernel, 1))
            for _ in dilations
        ])

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        for c1, c2 in zip(self.convs1, self.convs2):
            xt = c2(F.leaky_relu(c1(F.leaky_relu(x, LRELU)), LRELU))
            x = xt + x
        return x


class GeneratorV1(nn.Module):
    """HiFi-GAN V1: 4 upsample stages x 3 MRF resblocks x 6 convs + pre + post = 74 convs."""

    def __init__(self) -> None:
        super().__init__()
        upsample_rates = [8, 8, 2, 2]
        upsample_kernels = [16, 16, 4, 4]
        initial = 512
        resblock_kernels = [3, 7, 11]
        resblock_dilations = [[1, 3, 5], [1, 3, 5], [1, 3, 5]]
        self.num_kernels = len(resblock_kernels)
        self.conv_pre = nn.Conv1d(80, initial, 7, 1, padding=3)
        self.ups = nn.ModuleList([
            nn.ConvTranspose1d(initial // (2 ** i), initial // (2 ** (i + 1)),
                               k, u, padding=(k - u) // 2)
            for i, (u, k) in enumerate(zip(upsample_rates, upsample_kernels))
        ])
        self.resblocks = nn.ModuleList()
        for i in range(len(self.ups)):
            channels = initial // (2 ** (i + 1))
            for k, d in zip(resblock_kernels, resblock_dilations):
                self.resblocks.append(ResBlock1(channels, k, d))
        self.conv_post = nn.Conv1d(channels, 1, 7, 1, padding=3)

    def forward(self, mel: torch.Tensor) -> torch.Tensor:
        x = self.conv_pre(mel)
        for i, up in enumerate(self.ups):
            x = up(F.leaky_relu(x, LRELU))
            acc = None
            for j in range(self.num_kernels):
                out = self.resblocks[i * self.num_kernels + j](x)
                acc = out if acc is None else acc + out
            x = acc / self.num_kernels
        return torch.tanh(self.conv_post(F.leaky_relu(x)))


def export(frames: int) -> tuple[Path, Path]:
    WORK.mkdir(parents=True, exist_ok=True)
    fp32 = WORK / "hifigan-v1-fp32.onnx"
    fp16 = WORK / "hifigan-v1-fp16.onnx"
    if not fp32.is_file():
        model = GeneratorV1().eval()
        dummy = torch.randn(1, 80, frames)
        with torch.no_grad():
            torch.onnx.export(
                model, (dummy,), str(fp32), input_names=["mel"], output_names=["audio"],
                dynamic_axes={"mel": {2: "frames"}, "audio": {2: "samples"}}, opset_version=17,
                dynamo=False,
            )
    if not fp16.is_file():
        try:
            from onnxconverter_common import float16  # type: ignore
            import onnx
            onnx.save(float16.convert_float_to_float16(onnx.load(str(fp32))), str(fp16))
        except Exception as error:  # noqa: BLE001
            print(f"  (fp16 conversion unavailable: {error})")
    return fp32, fp16


def timed(path: Path, mel: np.ndarray, threads: int = 0) -> float:
    options = ort.SessionOptions()
    options.intra_op_num_threads = threads
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
    feed = {session.get_inputs()[0].name: mel}
    session.run(None, feed)
    best = min(
        (lambda s=time.perf_counter(): (session.run(None, feed), time.perf_counter() - s)[1])()
        for _ in range(3)
    )
    return best * 1000.0


def main() -> int:
    frames = 362  # same mel length used in the earlier Hindi measurement
    seconds = frames * HOP / SAMPLE_RATE
    mel32 = np.random.randn(1, 80, frames).astype(np.float32) * 0.5 - 2.0
    print(f"mel frames={frames}  audio={seconds:.2f}s\n")

    fp32, fp16 = export(frames)
    rows: list[dict] = []

    int8_path = TTS / "hi" / "hifigan-hi.int8.onnx"
    for label, path, feed in (
        ("int8 (EchoBharat, ConvInteger)", int8_path, mel32),
        ("fp32 (rebuilt V1)", fp32, mel32),
        ("fp16 (rebuilt V1)", fp16, mel32.astype(np.float16) if fp16.is_file() else None),
    ):
        if feed is None or not path.is_file():
            continue
        ms = timed(path, feed)
        rtf = ms / 1000.0 / seconds
        rows.append({"variant": label, "ms": round(ms, 1), "rtf": round(rtf, 3),
                     "fileMiB": round(path.stat().st_size / (1 << 20), 2)})
        print(f"  {label:34} {ms:9.1f} ms   rtf={rtf:6.3f}   {path.stat().st_size / (1 << 20):6.2f} MiB")

    report = ROOT / "docs" / "results" / "echobharat-vocoder-bench.json"
    report.write_text(json.dumps({
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "onnxruntime": ort.__version__,
        "torch": torch.__version__,
        "melFrames": frames,
        "audioSeconds": round(seconds, 3),
        "note": "fp32/fp16 use rebuilt HiFi-GAN V1 with random weights; timing only, not audio quality.",
        "results": rows,
    }, indent=2), encoding="utf-8")
    print(f"\nreport: {report.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
