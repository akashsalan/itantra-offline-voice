"""Reproduce Android's English adapter on local WAV inputs; not a phone benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import wave

import numpy as np
import sherpa_onnx


def load_audio(path):
    with wave.open(str(path), "rb") as wav:
        assert wav.getnchannels() == 1 and wav.getsampwidth() == 2
        rate = wav.getframerate()
        samples = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").astype(np.float32) / 32768
    if rate != 16000:
        samples = np.interp(np.arange(int(len(samples) * 16000 / rate)) * rate / 16000,
                            np.arange(len(samples)), samples).astype(np.float32)
    # Match the app's PCM16 boundary after any test-only resampling.
    return np.clip(np.round(samples * 32768), -32768, 32767).astype(np.int16).astype(np.float32) / 32768


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model-dir", type=Path, default=Path("models/source/asr/en"))
    parser.add_argument("--reference-dir", type=Path, default=Path(".tools/english-reference/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/test_wavs"))
    parser.add_argument("--output", type=Path, default=Path("docs/results/english-stt-host-audit.json"))
    parser.add_argument("--precision-audit", action="store_true")
    parser.add_argument("--candidate", action="store_true", help="Compare only the standard decoder with 0.3/0.66 seconds tail")
    parser.add_argument("--check-regression", action="store_true", help="Require original human-sample openings and a blank silence control; not corpus WER")
    parser.add_argument("--fp32-dir", type=Path, default=Path(".tools/english-reference/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"))
    args = parser.parse_args()
    if args.check_regression and not args.candidate:
        parser.error("--check-regression requires --candidate")
    inputs = []
    for name in ("0.wav", "1.wav"):
        path = args.reference_dir / name
        audio = load_audio(path)
        inputs.append((name, audio, "bundled_human_reference", hashlib.sha256(path.read_bytes()).hexdigest()))
        voiced = np.flatnonzero(np.abs(audio) > 0.01)
        if len(voiced):
            inputs.append((name + ":hard_endpoint", audio[:voiced[-1] + 1], "derived_endpoint_trim_threshold_0.01", None))
    fixture_dir = Path(".tools/english-synthetic")
    fixture_dir.mkdir(exist_ok=True)
    environment = os.environ.copy()
    environment["PATH"] = "C:/msys64/ucrt64/bin;" + environment.get("PATH", "")
    environment["ESPEAK_DATA_PATH"] = str(Path(".tools/espeak-host").resolve())
    phrases = ["The main road is closed. Please move north.",
               "Send help to the railway station.",
               "My name is Akash. I am testing the radio."]
    for index, text in enumerate(phrases):
        path = fixture_dir / f"synthetic-{index}.wav"
        subprocess.run([str(Path(".tools/espeak-host/src/espeak-ng.exe").resolve()),
                        "-v", "en-us", "-s", "165", "-w", str(path), text], env=environment, check=True)
        inputs.append(("synthetic:" + text, load_audio(path), "espeak_synthetic_not_human_accuracy_evidence", hashlib.sha256(path.read_bytes()).hexdigest()))
    if args.candidate:
        inputs.append(("silence:3s", np.zeros(48000, dtype=np.float32), "digital_silence_control", None))

    rows = []
    variants = [
        ("greedy_search", 0.3, "zipformer", 0.0),
        ("greedy_search", 0.66, "zipformer", 0.0),
        ("greedy_search", 0.66, "", 0.0),
        ("modified_beam_search", 0.66, "", 0.0),
        ("greedy_search", 0.66, "", 0.66),
        ("greedy_search", 0.66, "", 1.5),
    ]
    variants = [(*variant, True, True, 1600) for variant in variants]
    if args.candidate:
        variants = [("greedy_search", tail, "zipformer", 0.0, True, True, 1600) for tail in (0.3, 0.66)]
    if args.precision_audit:
        variants = [("greedy_search", 0.66, "zipformer", 0.0, encoder_int8, joiner_int8, chunk)
                    for encoder_int8, joiner_int8 in ((True, True), (False, True), (True, False), (False, False))
                    for chunk in (1600, 0)]
    for decoding, padding, model_type, leading, encoder_int8, joiner_int8, chunk in variants:
        encoder_path = args.model_dir / "encoder-epoch-99-avg-1.int8.onnx" if encoder_int8 else args.fp32_dir / "encoder-epoch-99-avg-1.onnx"
        joiner_path = args.model_dir / "joiner-epoch-99-avg-1.int8.onnx" if joiner_int8 else args.fp32_dir / "joiner-epoch-99-avg-1.onnx"
        recognizer = sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(args.model_dir / "tokens.txt"),
            encoder=str(encoder_path),
            decoder=str(args.model_dir / "decoder-epoch-99-avg-1.onnx"),
            joiner=str(joiner_path),
            num_threads=2, sample_rate=16000, feature_dim=80,
            decoding_method=decoding, max_active_paths=4, enable_endpoint_detection=False,
            model_type=model_type, provider="cpu")
        for name, samples, kind, digest in inputs:
            stream = recognizer.create_stream()
            started = time.perf_counter()
            if leading:
                stream.accept_waveform(16000, np.zeros(int(leading * 16000), dtype=np.float32))
                while recognizer.is_ready(stream):
                    recognizer.decode_stream(stream)
            for offset in range(0, len(samples), chunk or len(samples)):
                stream.accept_waveform(16000, samples[offset:offset + (chunk or len(samples))])
                if chunk:
                    while recognizer.is_ready(stream):
                        recognizer.decode_stream(stream)
            stream.accept_waveform(16000, np.zeros(int(padding * 16000), dtype=np.float32))
            stream.input_finished()
            while recognizer.is_ready(stream):
                recognizer.decode_stream(stream)
            text = recognizer.get_result(stream).strip()
            elapsed = time.perf_counter() - started
            row = dict(input=name, input_kind=kind, wav_sha256=digest, decoding=decoding, padding_seconds=padding,
                       model_type=model_type or "auto", leading_seconds=leading,
                       encoder_int8=encoder_int8, joiner_int8=joiner_int8, chunk_samples=chunk,
                       text=text, audio_seconds=len(samples) / 16000, host_inference_ms=elapsed * 1000)
            rows.append(row)
            print(json.dumps(row, ensure_ascii=True), flush=True)
        del recognizer
    checks = {}
    if args.check_regression:
        final_rows = {row["input"]: row for row in rows if row["padding_seconds"] == 0.66}
        checks = {
            "sample_0_opening_retained": final_rows["0.wav"]["text"].startswith("AFTER EARLY NIGHTFALL "),
            "sample_0_final_word_retained": final_rows["0.wav"]["text"].endswith(" BROTHELS"),
            "sample_1_opening_retained": final_rows["1.wav"]["text"].startswith("GOD AS A DIRECT CONSEQUENCE "),
            "digital_silence_is_blank": final_rows["silence:3s"]["text"] == "",
        }
    model_files = [args.model_dir / name for name in ("encoder-epoch-99-avg-1.int8.onnx",
                   "decoder-epoch-99-avg-1.onnx", "joiner-epoch-99-avg-1.int8.onnx", "tokens.txt")]
    report = dict(kind="windows_host_english_configuration_comparison", sherpa_version=sherpa_onnx.__version__,
                  model_directory=str(args.model_dir), num_threads=2, provider="cpu",
                  model_files=[dict(name=path.name, bytes=path.stat().st_size,
                                    sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for path in model_files],
                  regression_checks=checks,
                  android_tested=False, user_recording_tested=False,
                  limitations="Two bundled human samples and synthetic endpoint/TTS fixtures; no claim about the user's voice or corpus WER.", rows=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if checks and not all(checks.values()):
        raise AssertionError(f"English regression failed: {checks}")


if __name__ == "__main__":
    main()
