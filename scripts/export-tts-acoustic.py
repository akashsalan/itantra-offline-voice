"""Export an AI4Bharat Indic-TTS FastPitch acoustic model to ONNX, plus its token table.

Needed only for languages EchoBharat never published. Odia is the case: it has no
published int8 FastPitch, so both the acoustic graph and the symbol table are
produced here.

Two differences from the nine published languages are handled:
  * the checkpoint is multi-speaker (num_speakers=2, speaker embedding), so one
    speaker is baked in and the exported graph keeps the single `text` input that
    the Android adapter expects;
  * export uses the dynamo exporter. The publisher warns that the legacy
    TorchScript tracer bakes the traced sequence length into a Reshape, producing
    a graph that loads fine and then fails on any other sentence length.

  .tools/tts-export/Scripts/python.exe scripts/export-tts-acoustic.py or
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / ".tools" / "indictts-vocoders" / "raw"
DEST = ROOT / "models" / "source" / "tts" / "indictts"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


class SingleSpeakerFastPitch(nn.Module):
    """Wraps ForwardTTS.inference so the graph is text -> mel[1, 80, frames]."""

    def __init__(self, model: nn.Module, speaker_id: int | None) -> None:
        super().__init__()
        self.model = model
        self.multi_speaker = speaker_id is not None
        if self.multi_speaker:
            self.register_buffer("speaker", torch.tensor([speaker_id], dtype=torch.long))

    def forward(self, text: torch.Tensor) -> torch.Tensor:
        aux = {"d_vectors": None, "speaker_ids": self.speaker if self.multi_speaker else None}
        outputs = self.model.inference(text, aux_input=aux)
        # coqui returns [B, frames, mels]; the adapter and the published nine
        # both expect [1, mels, frames].
        return outputs["model_outputs"].transpose(1, 2)


def patch_positional_encoding() -> None:
    """Remove a data-dependent guard that blocks torch.export.

    coqui's PositionalEncoding.forward compares the buffer length against the
    decoder length in Python. The decoder length comes from predicted durations,
    so it is data-dependent and torch.export cannot resolve the branch. The
    comparison only raises when a sequence exceeds max_len (5000 frames, about
    58 s of audio at hop 256), which the 15 s capture cap cannot reach. The
    arithmetic is unchanged.
    """
    import math as _math

    from TTS.tts.layers.generic.pos_encoding import PositionalEncoding

    def forward(self, x, mask=None, first_idx=None, last_idx=None):  # noqa: ANN001
        x = x * _math.sqrt(self.channels)
        if first_idx is None:
            # Slicing the fixed `pe` buffer by the unbacked decoder length trips
            # torch.export ("possible memo disaster"). Recomputing the encoding
            # from the dynamic length avoids the buffer entirely and reproduces
            # the buffer's arithmetic exactly, including its 10000^(2i/C)
            # multiplication and sin/cos interleaving.
            channels = self.channels
            length = x.size(2)
            position = torch.arange(length, device=x.device, dtype=torch.float32).unsqueeze(1)
            div_term = torch.pow(
                torch.tensor(10000.0, device=x.device),
                torch.arange(0, channels, 2, device=x.device, dtype=torch.float32) / channels,
            )
            angles = position * div_term
            pos_enc = torch.stack((torch.sin(angles), torch.cos(angles)), dim=-1)
            pos_enc = pos_enc.reshape(length, channels).unsqueeze(0).transpose(1, 2)
            pos_enc = pos_enc.to(x.dtype)
            if mask is not None:
                pos_enc = pos_enc * mask
        else:
            pos_enc = self.pe[:, :, first_idx:last_idx]
        x = x + (self.scale * pos_enc if self.use_scale else pos_enc)
        if hasattr(self, "dropout"):
            x = self.dropout(x)
        return x

    PositionalEncoding.forward = forward


def patch_fft_attention() -> None:
    """Make the FFTransformer self-attention export without a baked sequence length.

    `nn.MultiheadAttention` reshapes with Python ints taken from the traced input,
    so the TorchScript tracer hardcodes the token count into a Reshape. The graph
    then loads cleanly and fails at runtime on any other sentence length -- the
    exact defect the publisher documents. This computes the same attention with
    the same weights, using -1 for the sequence axis so the axis stays dynamic.

    Mathematically identical to nn.MultiheadAttention in eval mode (dropout off),
    including the averaged-over-heads weights it returns.
    """
    import math as _math

    from TTS.tts.layers.generic.transformer import FFTransformer

    def forward(self, src, src_mask=None, src_key_padding_mask=None):  # noqa: ANN001
        src = src.permute(2, 0, 1)  # [T, B, D]
        attention = self.self_attn
        embed = attention.embed_dim
        heads = attention.num_heads
        head_dim = embed // heads
        batch = src.size(1)

        projected = torch.nn.functional.linear(
            src, attention.in_proj_weight, attention.in_proj_bias
        )
        query, key, value = projected.chunk(3, dim=-1)
        # -1 keeps the token axis dynamic; batch is 1 for this export.
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
        context = torch.bmm(weights, value)
        context = context.transpose(0, 1).reshape(-1, batch, embed)
        src2 = torch.nn.functional.linear(
            context, attention.out_proj.weight, attention.out_proj.bias
        )
        enc_align = weights.reshape(batch, heads, weights.size(-2), weights.size(-1)).mean(dim=1)

        src = src + self.dropout1(src2)
        src = self.norm1(src + src2)
        src = src.permute(1, 2, 0)
        src2 = self.conv2(torch.nn.functional.relu(self.conv1(src)))
        src2 = self.dropout2(src2)
        src = src + src2
        src = src.transpose(1, 2)
        src = self.norm2(src)
        src = src.transpose(1, 2)
        return src, enc_align

    FFTransformer.forward = forward


def patch_sequence_mask() -> None:
    """Tell torch.export that a derived mask length is size-like.

    The decoder length comes from predicted durations, so it is unbacked. When
    `sequence_mask` derives `max_len` itself the solver cannot prove the sequence
    has at least one frame, and the convolution shape check fails with
    "Size-like symbols: none". Declaring the derived length size-like and at
    least 1 supplies a fact the model already guarantees: `format_durations`
    clamps every token to a minimum duration of 1. The arithmetic is unchanged.

    Patched in one place because several call sites derive the length this way.
    """
    from TTS.tts.utils import helpers

    def sequence_mask(sequence_length, max_len=None):  # noqa: ANN001
        # Durations are rounded integers held in a float tensor, so the derived
        # length arrives as an unbacked *float*. torch.arange(float) then becomes
        # ceil(x / 1.0), which no size hint can apply to -- hence the original
        # "Size-like symbols: none". Casting to int64 first is exact for rounded
        # durations and gives the solver an integer it can reason about.
        lengths = sequence_length.to(torch.long)
        if max_len is None:
            max_len = lengths.max().item()
            torch._check_is_size(max_len)
            torch._check(max_len >= 1)
            # Convolution also checks for int32 overflow on channels * length.
            # 65536 mel frames is about 12 minutes at hop 256, far beyond the
            # app's 15 second capture cap and its 2048-byte text limit.
            torch._check(max_len <= 65536)
        seq_range = torch.arange(max_len, dtype=lengths.dtype, device=lengths.device)
        return seq_range.unsqueeze(0) < lengths.unsqueeze(1)

    helpers.sequence_mask = sequence_mask
    # Re-point the modules that imported the symbol directly.
    for module_name in (
        "TTS.tts.models.forward_tts",
        "TTS.tts.layers.generic.aligner",
    ):
        try:
            module = __import__(module_name, fromlist=["sequence_mask"])
        except ImportError:
            continue
        if hasattr(module, "sequence_mask"):
            module.sequence_mask = sequence_mask


def build_tokens(config: dict) -> list[str]:
    """Reproduce coqui VitsCharacters vocab order: pad + punctuations + chars + blank."""
    block = config["characters"]
    vocab = [block["pad"]] + list(block["punctuations"]) + list(block["characters"]) + [block["blank"]]
    expected = config.get("model_args", {}).get("num_chars")
    if expected is not None and len(vocab) != expected:
        raise SystemExit(f"vocab size {len(vocab)} does not match num_chars {expected}")
    return vocab


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("language")
    parser.add_argument("--speaker", type=int, default=0, help="speaker index to bake in")
    args = parser.parse_args()
    lang = args.language

    from TTS.config import load_config
    from TTS.tts.models.forward_tts import ForwardTTS

    patch_positional_encoding()
    patch_sequence_mask()
    patch_fft_attention()

    base = RAW / lang / lang / "fastpitch"
    raw_config = json.loads((base / "config.json").read_text(encoding="utf-8"))
    config = load_config(str(base / "config.json"))
    # The config stores the training-time speakers path. Repoint it at the copy
    # shipped beside the checkpoint so the speaker manager can load.
    local_speakers = base / "speakers.pth"
    if getattr(config, "speakers_file", None) and local_speakers.is_file():
        config.speakers_file = str(local_speakers)
        if hasattr(config, "model_args"):
            config.model_args.speakers_file = str(local_speakers)

    tokens = build_tokens(raw_config)
    print(f"  token table: {len(tokens)} symbols, first5={tokens[:5]!r}")

    model = ForwardTTS.init_from_config(config)
    checkpoint = torch.load(base / "best_model.pth", map_location="cpu", weights_only=False)
    # The saved tensors carry inference-mode provenance, which makes torch.export's
    # functional-tensor tracking fail with a weakref KeyError. A numpy round-trip
    # produces plain tensors with identical values and no provenance.
    state = {
        name: torch.from_numpy(tensor.detach().cpu().numpy().copy())
        if torch.is_tensor(tensor) else tensor
        for name, tensor in checkpoint["model"].items()
    }
    missing, unexpected = model.load_state_dict(state, strict=False)
    # The aligner and duration/pitch training heads are not used at inference.
    blocking = [name for name in missing if not name.startswith("aligner.")]
    if blocking:
        print(f"  missing: {blocking[:8]}")
        raise SystemExit("FastPitch state dict does not match the configured model")
    if unexpected:
        print(f"  ignored extra tensors: {list(unexpected)[:8]}")
    model.eval()

    speaker = args.speaker if raw_config.get("use_speaker_embedding") else None
    wrapper = SingleSpeakerFastPitch(model, speaker).eval()
    if speaker is not None:
        print(f"  multi-speaker checkpoint: baking in speaker id {speaker}")

    out_dir = DEST / lang
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / "acoustic.onnx"
    tokens_path = out_dir / "tokens.json"

    # Non-strict export tracks functional tensors by storage. Tensors that carry
    # inference-mode provenance make that lookup fail with a weakref KeyError, so
    # take a clean copy of every parameter and buffer before tracing.
    with torch.no_grad():
        for module in wrapper.modules():
            for name, parameter in list(module.named_parameters(recurse=False)):
                parameter.data = parameter.data.clone()
            for name, buffer in list(module.named_buffers(recurse=False)):
                if buffer is not None:
                    module.register_buffer(name, buffer.clone())

    dummy = torch.randint(1, len(tokens) - 1, (1, 32), dtype=torch.long)

    # Prefer the dynamo exporter. FastPitch's decoder length comes from predicted
    # durations, so torch.export sometimes cannot discharge a convolution shape
    # guard on that unbacked length. When that happens fall back to the legacy
    # tracer -- and then *prove* the graph is length-agnostic below by decoding
    # two different token counts, which is exactly the failure the publisher
    # warns about. A baked-in length fails that check loudly.
    strategy = "dynamo"
    try:
        # The decoder length is unbacked, so convolution shape checks such as
        # "length + 2 < 3" cannot be discharged. Size-oblivious reasoning treats
        # such derived lengths as sizes (non-negative, and not degenerate), which
        # is the documented escape hatch for this class of guard.
        try:
            import torch.fx.experimental._config as fx_config

            fx_config.backed_size_oblivious = True
        except Exception:  # noqa: BLE001
            pass
        dynamic = torch.export.Dim("tokens", min=2, max=4096)
        from torch.export._trace import _export as export_with_runtime_asserts

        # Deliberately not under torch.no_grad(): inference mode interacts badly
        # with non-strict export's functional tensor tracking. The model is in
        # eval() and nothing here needs gradients.
        # The remaining guards are convolution heuristics on the unbacked decoder
        # length (for example "512 * length > 20480"). Deferring them to runtime
        # asserts is the supported escape hatch and keeps the length genuinely
        # dynamic instead of specialising it to the traced sentence.
        # strict=True: non-strict export tracks functional tensors by storage and
        # fails here with a weakref KeyError. Dynamo tracing avoids that path.
        program = export_with_runtime_asserts(
            wrapper, (dummy,), dynamic_shapes={"text": {1: dynamic}},
            strict=True, prefer_deferred_runtime_asserts_over_guards=True,
        )
        exported = torch.onnx.export(program, dynamo=True, opset_version=18)
        exported.optimize()
        exported.save(str(target))
    except Exception as error:  # noqa: BLE001
        strategy = "legacy-tracer"
        print(f"  dynamo export unavailable ({type(error).__name__}); using the legacy tracer")
        import traceback

        summary = str(error)
        marker = summary.find("Exception summary")
        print("  dynamo detail: " + (summary[marker:marker + 400] if marker >= 0 else summary[:400]))
        print("  dynamo trace:\n" + "".join(traceback.format_exc()).strip()[-1500:])
        with torch.no_grad():
            torch.onnx.export(
                wrapper, (dummy,), str(target), input_names=["text"], output_names=["mel"],
                dynamic_axes={"text": {1: "tokens"}, "mel": {2: "frames"}},
                opset_version=17, dynamo=False,
            )
    print(f"  export strategy: {strategy}")

    with torch.no_grad():
        reference = wrapper(dummy).numpy()
    print(f"  torch mel: {reference.shape}")

    session = ort.InferenceSession(str(target), providers=["CPUExecutionProvider"])
    produced = session.run(None, {"text": dummy.numpy()})[0]
    drift = float(np.max(np.abs(produced - reference)))

    # A different token count must work: this is exactly what the legacy tracer breaks.
    longer = torch.randint(1, len(tokens) - 1, (1, 61), dtype=torch.long)
    with torch.no_grad():
        long_reference = wrapper(longer).numpy()
    long_produced = session.run(None, {"text": longer.numpy()})[0]
    long_drift = float(np.max(np.abs(long_produced - long_reference)))

    tokens_path.write_text(json.dumps(tokens, ensure_ascii=False), encoding="utf-8")

    print(f"  exported {target.relative_to(ROOT)}")
    print(f"  bytes    {target.stat().st_size:,}")
    print(f"  sha256   {sha256(target)}")
    print(f"  drift    {drift:.3e} at 32 tokens, {long_drift:.3e} at 61 tokens")
    print(f"  dynamic  32 tokens -> {produced.shape}, 61 tokens -> {long_produced.shape}")
    if produced.shape[2] == long_produced.shape[2]:
        raise SystemExit(
            "mel length did not change with token count: the graph has a baked-in "
            "sequence length and would fail on most sentences"
        )
    if drift > 1e-3 or long_drift > 1e-3:
        raise SystemExit("ONNX output drifted from the PyTorch reference")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
