"""Export AI4Bharat FastPitch as two graphs, split at the duration boundary.

Why split. FastPitch predicts how long each token lasts, so the mel length is
data-dependent. A single graph therefore has an output length that torch.export
cannot reason about (a chain of unbacked-symbol guards) and that the legacy
TorchScript tracer simply freezes. Splitting removes the problem instead of
fighting it:

  encoder.onnx : text[1, T]        -> features[1, C, T], durations[1, T]
  (host/app)   : repeat each feature column `durations[i]` times  -> [1, C, F]
  decoder.onnx : features[1, C, F] -> mel[1, 80, F]

Every graph's output length is now a function of its own input length, which is an
ordinary dynamic axis. Nothing is data-dependent, so the legacy tracer is safe and
is validated here at two different lengths.

  .tools/tts-export/Scripts/python.exe scripts/export-tts-acoustic-split.py or
"""
from __future__ import annotations

import argparse
import hashlib
import json
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".tools" / "indictts-vocoders" / "raw"
DEST = ROOT / "models" / "source" / "tts" / "indictts"
OUT = ROOT / ".tools" / "tts-pair-check"
SAMPLE_RATE = 22050
MEL_BINS = 80


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def patch_positional_encoding() -> None:
    """Compute the encoding from the input length instead of slicing a buffer."""
    import math as _math

    from TTS.tts.layers.generic.pos_encoding import PositionalEncoding

    def forward(self, x, mask=None, first_idx=None, last_idx=None):  # noqa: ANN001
        x = x * _math.sqrt(self.channels)
        channels = self.channels
        length = x.size(2)
        position = torch.arange(length, device=x.device, dtype=torch.float32).unsqueeze(1)
        div_term = torch.pow(
            torch.tensor(10000.0, device=x.device),
            torch.arange(0, channels, 2, device=x.device, dtype=torch.float32) / channels,
        )
        angles = position * div_term
        pos_enc = torch.stack((torch.sin(angles), torch.cos(angles)), dim=-1)
        pos_enc = pos_enc.reshape(length, channels).unsqueeze(0).transpose(1, 2).to(x.dtype)
        if mask is not None:
            pos_enc = pos_enc * mask
        x = x + (self.scale * pos_enc if self.use_scale else pos_enc)
        if hasattr(self, "dropout"):
            x = self.dropout(x)
        return x

    PositionalEncoding.forward = forward


def patch_fft_attention() -> None:
    """Shape-agnostic self-attention.

    nn.MultiheadAttention reshapes with Python ints from the traced input, so the
    tracer hardcodes the sequence length into a Reshape; the graph then fails on
    any other length. Same weights, same maths, dynamic sequence axis.
    """
    import math as _math

    from TTS.tts.layers.generic.transformer import FFTransformer

    def forward(self, src, src_mask=None, src_key_padding_mask=None):  # noqa: ANN001
        src = src.permute(2, 0, 1)
        attention = self.self_attn
        embed = attention.embed_dim
        heads = attention.num_heads
        head_dim = embed // heads
        batch = src.size(1)

        projected = nn.functional.linear(src, attention.in_proj_weight, attention.in_proj_bias)
        query, key, value = projected.chunk(3, dim=-1)
        query = query.reshape(-1, batch * heads, head_dim).transpose(0, 1)
        key = key.reshape(-1, batch * heads, head_dim).transpose(0, 1)
        value = value.reshape(-1, batch * heads, head_dim).transpose(0, 1)

        scores = torch.bmm(query, key.transpose(-2, -1)) / _math.sqrt(head_dim)
        if src_key_padding_mask is not None:
            pad = src_key_padding_mask.to(torch.bool).reshape(batch, 1, 1, -1)
            pad = pad.expand(batch, heads, 1, -1).reshape(batch * heads, 1, -1)
            scores = scores.masked_fill(pad, float("-inf"))
        if src_mask is not None:
            scores = scores + src_mask
        weights = torch.softmax(scores, dim=-1)
        context = torch.bmm(weights, value).transpose(0, 1).reshape(-1, batch, embed)
        src2 = nn.functional.linear(context, attention.out_proj.weight, attention.out_proj.bias)
        enc_align = weights.reshape(batch, heads, weights.size(-2), weights.size(-1)).mean(dim=1)

        src = src + self.dropout1(src2)
        src = self.norm1(src + src2)
        src = src.permute(1, 2, 0)
        src2 = self.conv2(nn.functional.relu(self.conv1(src)))
        src2 = self.dropout2(src2)
        src = src + src2
        src = src.transpose(1, 2)
        src = self.norm2(src)
        src = src.transpose(1, 2)
        return src, enc_align

    FFTransformer.forward = forward


class Encoder(nn.Module):
    """text -> conditioned encoder features and integer durations."""

    def __init__(self, model: nn.Module, speaker_id: int | None) -> None:
        super().__init__()
        self.model = model
        self.multi_speaker = speaker_id is not None
        if self.multi_speaker:
            self.register_buffer("speaker", torch.tensor([speaker_id], dtype=torch.long))

    def forward(self, text: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        model = self.model
        aux = {"d_vectors": None, "speaker_ids": self.speaker if self.multi_speaker else None}
        g = model._set_speaker_input(aux)
        # Full-length input: the mask is all ones, derived from the input shape.
        x_mask = torch.ones(1, 1, text.size(1), dtype=torch.float32, device=text.device)
        o_en, x_mask, g, _ = model._forward_encoder(text, x_mask, g)
        o_dr_log = model.duration_predictor(o_en.squeeze(), x_mask)
        o_dr = model.format_durations(o_dr_log, x_mask).squeeze(1)
        if model.args.use_pitch:
            o_pitch_emb, _ = model._forward_pitch_predictor(o_en, x_mask)
            o_en = o_en + o_pitch_emb
        if model.args.use_energy:
            o_energy_emb, _ = model._forward_energy_predictor(o_en, x_mask)
            o_en = o_en + o_energy_emb
        return o_en, o_dr.to(torch.int64)


class Decoder(nn.Module):
    """expanded encoder features -> mel spectrogram."""

    def __init__(self, model: nn.Module, speaker_id: int | None) -> None:
        super().__init__()
        self.model = model
        self.multi_speaker = speaker_id is not None
        if self.multi_speaker:
            self.register_buffer("speaker", torch.tensor([speaker_id], dtype=torch.long))

    def forward(self, features: torch.Tensor) -> torch.Tensor:
        model = self.model
        y_mask = torch.ones(1, 1, features.size(2), dtype=features.dtype, device=features.device)
        o_en_ex = features
        if hasattr(model, "pos_encoder"):
            o_en_ex = model.pos_encoder(o_en_ex, y_mask)
        # decoder already yields [B, mels, frames]; coqui's _forward_decoder
        # transposes to [B, frames, mels] for its loss, which we do not want.
        return model.decoder(o_en_ex, y_mask, g=None)


def expand(features: np.ndarray, durations: np.ndarray) -> np.ndarray:
    """Repeat each encoder column by its duration. Mirrors the app-side expansion."""
    counts = durations.reshape(-1).astype(np.int64)
    counts = np.maximum(counts, 0)
    index = np.repeat(np.arange(counts.shape[0], dtype=np.int64), counts)
    return features[:, :, index]


def trace(module: nn.Module, example, path: Path, inputs: list[str], outputs: list[str],
          axes: dict) -> None:
    with torch.no_grad():
        torch.onnx.export(
            module, example, str(path), input_names=inputs, output_names=outputs,
            dynamic_axes=axes, opset_version=17, dynamo=False,
        )


def write_wav(path: Path, audio: np.ndarray) -> float:
    pcm = (np.clip(audio, -1.0, 1.0) * 32767.0).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())
    return len(pcm) / SAMPLE_RATE


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("language")
    parser.add_argument("--speaker", type=int, default=0)
    args = parser.parse_args()
    lang = args.language

    from TTS.config import load_config
    from TTS.tts.models.forward_tts import ForwardTTS

    patch_positional_encoding()
    patch_fft_attention()

    base = RAW / lang / lang / "fastpitch"
    raw_config = json.loads((base / "config.json").read_text(encoding="utf-8"))
    config = load_config(str(base / "config.json"))
    local_speakers = base / "speakers.pth"
    if getattr(config, "speakers_file", None) and local_speakers.is_file():
        config.speakers_file = str(local_speakers)
        if hasattr(config, "model_args"):
            config.model_args.speakers_file = str(local_speakers)

    block = raw_config["characters"]
    tokens = [block["pad"]] + list(block["punctuations"]) + list(block["characters"]) + [block["blank"]]
    expected = raw_config.get("model_args", {}).get("num_chars")
    if expected is not None and len(tokens) != expected:
        raise SystemExit(f"vocab {len(tokens)} != num_chars {expected}")
    print(f"  tokens: {len(tokens)} symbols")

    model = ForwardTTS.init_from_config(config)
    checkpoint = torch.load(base / "best_model.pth", map_location="cpu", weights_only=False)
    missing, unexpected = model.load_state_dict(checkpoint["model"], strict=False)
    blocking = [name for name in missing if not name.startswith("aligner.")]
    if blocking:
        raise SystemExit(f"state dict mismatch: {blocking[:6]}")
    model.eval()

    speaker = args.speaker if raw_config.get("use_speaker_embedding") else None
    encoder = Encoder(model, speaker).eval()
    decoder = Decoder(model, speaker).eval()

    out_dir = DEST / lang
    out_dir.mkdir(parents=True, exist_ok=True)
    encoder_path = out_dir / "encoder.onnx"
    decoder_path = out_dir / "decoder.onnx"

    short = torch.randint(1, len(tokens) - 1, (1, 32), dtype=torch.long)
    long = torch.randint(1, len(tokens) - 1, (1, 61), dtype=torch.long)

    trace(encoder, (short,), encoder_path, ["text"], ["features", "durations"],
          {"text": {1: "tokens"}, "features": {2: "tokens"}, "durations": {1: "tokens"}})
    with torch.no_grad():
        features, durations = encoder(short)
        expanded = torch.from_numpy(expand(features.numpy(), durations.numpy()))
    trace(decoder, (expanded,), decoder_path, ["features"], ["mel"],
          {"features": {2: "frames"}, "mel": {2: "frames"}})

    enc_session = ort.InferenceSession(str(encoder_path), providers=["CPUExecutionProvider"])
    dec_session = ort.InferenceSession(str(decoder_path), providers=["CPUExecutionProvider"])

    report = {}
    for label, text in (("short", short), ("long", long)):
        with torch.no_grad():
            ref_features, ref_durations = encoder(text)
            ref_expanded = torch.from_numpy(expand(ref_features.numpy(), ref_durations.numpy()))
            ref_mel = decoder(ref_expanded).numpy()
        onnx_features, onnx_durations = enc_session.run(None, {"text": text.numpy()})
        onnx_expanded = expand(onnx_features, onnx_durations)
        onnx_mel = dec_session.run(None, {"features": onnx_expanded})[0]
        enc_drift = float(np.max(np.abs(onnx_features - ref_features.numpy())))
        dur_match = bool(np.array_equal(onnx_durations, ref_durations.numpy()))
        mel_drift = float(np.max(np.abs(onnx_mel - ref_mel)))
        report[label] = {
            "tokens": int(text.shape[1]), "frames": int(onnx_mel.shape[2]),
            "encoderDrift": enc_drift, "durationsMatch": dur_match, "melDrift": mel_drift,
        }
        print(f"  {label}: {text.shape[1]} tokens -> {onnx_mel.shape[2]} frames  "
              f"encDrift={enc_drift:.2e} durMatch={dur_match} melDrift={mel_drift:.2e}")
        print(f"        durations sum={int(onnx_durations.sum())} "
              f"min={int(onnx_durations.min())} max={int(onnx_durations.max())} "
              f"expandedShape={onnx_expanded.shape} featShape={onnx_features.shape}")

    if report["short"]["frames"] == report["long"]["frames"]:
        raise SystemExit("frame count did not change with token count: a length is baked in")
    if max(report[k]["melDrift"] for k in report) > 1e-3:
        raise SystemExit("ONNX drifted from the PyTorch reference")
    if not all(report[k]["durationsMatch"] for k in report):
        raise SystemExit("durations differ between ONNX and PyTorch")

    (out_dir / "tokens.json").write_text(json.dumps(tokens, ensure_ascii=False), encoding="utf-8")

    # Full pipeline through the real vocoder, so this is audio and not just tensors.
    vocoder_path = out_dir / "vocoder.onnx"
    if vocoder_path.is_file():
        voc = ort.InferenceSession(str(vocoder_path), providers=["CPUExecutionProvider"])
        started = time.perf_counter()
        features, durations = enc_session.run(None, {"text": long.numpy()})
        mel = dec_session.run(None, {"features": expand(features, durations)})[0]
        audio = voc.run(["audio"], {"mel": mel.astype(np.float32)})[0].reshape(-1)
        elapsed = time.perf_counter() - started
        seconds = write_wav(OUT / f"{lang}-split-pipeline.wav", audio)
        print(f"  pipeline: {seconds:.2f}s audio in {elapsed * 1000:.0f} ms "
              f"(rtf={elapsed / seconds:.3f}) peak={float(np.max(np.abs(audio))):.3f}")

    for path in (encoder_path, decoder_path):
        print(f"  {path.name}: {path.stat().st_size:,} bytes  sha256 {sha256(path)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
