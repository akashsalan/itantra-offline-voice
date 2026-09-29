"""CPU export of the pinned Odia checkpoint; never evidence of corpus accuracy.

Adapted from the PRD-selected IndicConformer notebook at revision
9b07b2bfbfc6039c2e394545474e87c4cbafa19f. See docs/ODIA_EXPORT.md.
Run using the isolated .tools/odia-export Python environment.
"""
import argparse
import gc
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
NEMO_REVISION = "8dce88cf8e94963e2033c3137f7b9993b51db88a"
NOTEBOOK_REVISION = "9b07b2bfbfc6039c2e394545474e87c4cbafa19f"
CHECKPOINT_SHA = "e30cfee192fbdc37b7c0b221c76788b439d445e64368cd0447172f7d77deb616"
WORK = ROOT / ".tools/odia-export-work"
OUTPUT = ROOT / "models/source/asr/or-ctc-experimental"
REPORT = ROOT / "docs/results/odia-export-host.json"


def sha(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def save_report(report):
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


def load_nemo():
    os.environ["HF_HOME"] = str(WORK / "hf-cache")
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    sys.path.insert(0, str(ROOT / f".tools/odia-export-src/NeMo-{NEMO_REVISION}"))
    # Notebook's import-only shim for an unused, removed cloud logger.
    import pytorch_lightning.loggers as loggers
    if not hasattr(loggers, "NeptuneLogger"):
        class NeptuneLogger:
            def __init__(self, *args, **kwargs):
                raise RuntimeError("Neptune is not used or configured for offline export")
        loggers.NeptuneLogger = NeptuneLogger
    import nemo.collections.asr as nemo_asr
    return nemo_asr


def fixtures(extra):
    import librosa
    import numpy as np
    WORK.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["PATH"] = "C:/msys64/ucrt64/bin;" + environment.get("PATH", "")
    environment["ESPEAK_DATA_PATH"] = str(ROOT / ".tools/espeak-host")
    phrases = ["ନମସ୍କାର ଆପଣ କେମିତି ଅଛନ୍ତି", "ମୋତେ ସାହାଯ୍ୟ କରନ୍ତୁ"]
    paths = []
    for index, phrase in enumerate(phrases):
        raw = WORK / f"synthetic-{index}-native.wav"
        # MinGW argv can lose Odia characters on Windows; feed explicit UTF-8.
        subprocess.run([str(ROOT / ".tools/espeak-host/src/espeak-ng.exe"), "-v", "or", "-s", "140", "-b", "1", "-w", str(raw), "--stdin"],
                       input=phrase.encode("utf-8"), env=environment, check=True)
        paths.append((raw, "espeak_synthetic_not_human_accuracy", phrase))
    paths.extend((Path(path), "user_supplied_unscored", None) for path in extra)
    rows = []
    for index, (path, kind, prompt) in enumerate(paths):
        audio, _ = librosa.load(path, sr=16000, mono=True)
        assert 0.5 <= len(audio) / 16000 <= 15, "Fixture must be 0.5 to 15 seconds"
        pcm = np.clip(np.round(audio * 32768), -32768, 32767).astype("<i2")
        normalized = WORK / f"fixture-{index}.wav"
        with wave.open(str(normalized), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(16000)
            wav.writeframes(pcm.tobytes())
        rows.append(dict(path=str(normalized), kind=kind, synthesis_prompt_not_ground_truth=prompt,
                         sha256=sha(normalized), audio_seconds=len(pcm) / 16000))
    return rows


def collapse(logits, blank=5632):
    ids = logits.argmax(axis=-1).tolist()
    result, previous = [], None
    for token in ids:
        if token != previous and token != blank:
            assert 3584 <= token < 3840, f"Out-of-language token: {token}"
            result.append(token)
        previous = token
    return result


def restore():
    import torch
    from omegaconf import OmegaConf
    checkpoint = ROOT / "models/source/asr/or/source-model.nemo"
    assert sha(checkpoint) == CHECKPOINT_SHA, "Source checkpoint SHA mismatch"
    with tarfile.open(checkpoint) as archive:
        # Verify the provided container before NeMo extracts it.
        for member in archive.getmembers():
            path = Path(member.name)
            assert not path.is_absolute() and ".." not in path.parts
            assert member.isfile() or member.isdir(), "Links/devices are not allowed"
        config = OmegaConf.load(archive.extractfile("./model_config.yaml"))
    validation_config = config.get("validation_ds")
    for dataset in ("train_ds", "validation_ds", "test_ds"):
        config[dataset] = None  # No training data or network access is needed.
    nemo_asr = load_nemo()
    started = time.perf_counter()
    model = nemo_asr.models.EncDecHybridRNNTCTCBPEModel.restore_from(
        str(checkpoint), override_config_path=config, map_location=torch.device("cpu"))
    # transcribe() reads tokenizer options from this config even without a dataset.
    model.cfg.validation_ds = validation_config or OmegaConf.create({"use_start_end_token": False})
    model.eval()
    # NeMo transcribe disables dither; use the same deterministic preprocessor
    # for the direct source forward and the exported-feature comparisons.
    model.preprocessor.featurizer.dither = 0.0
    model.cur_decoder = "ctc"
    model.set_export_config({"decoder_type": "ctc"})
    assert model.output_module.__class__.__name__ == "ConvASRDecoder"
    assert model.tokenizer.token_id_offset["or"] == 3584
    assert len(model.tokenizer.tokenizers_dict["or"].vocab) == 256
    assert len(model.tokenizer.vocab) == 5632
    print("RESTORED", time.perf_counter() - started, "seconds; Odia offset=3584 size=256 blank=5632", flush=True)
    return model


def export(extra):
    import numpy as np
    import torch
    import librosa
    import onnxruntime as ort
    torch.set_num_threads(2)
    model = restore()
    OUTPUT.mkdir(parents=True, exist_ok=True)
    tokens = "".join(f"{token} {index}\n" for index, token in enumerate(model.tokenizer.vocab)) + "<blk> 5632\n"
    (OUTPUT / "tokens.txt").write_text(tokens, encoding="utf-8", newline="\n")
    rows = fixtures(extra)
    report = dict(kind="conversion_smoke_not_native_speaker_accuracy", source_sha256=CHECKPOINT_SHA,
                  nemo_revision=NEMO_REVISION, notebook_revision=NOTEBOOK_REVISION,
                  language_offset=3584, language_size=256, blank_id=5632,
                  tokens_sha256=sha(OUTPUT / "tokens.txt"),
                  tokens_match_shared=sha(OUTPUT / "tokens.txt") == sha(ROOT / "models/source/asr/indic-tokens.txt"),
                  versions={name: importlib.metadata.version(name) for name in ("torch", "numpy", "onnx", "onnxruntime", "sherpa-onnx", "pytorch-lightning", "transformers")},
                  native_speaker_sentences_scored=0, wer=None, release_validated=False, rows=rows)

    class MaskedCTC(torch.nn.Module):
        def __init__(self):
            super().__init__()
            self.model = model
            mask = torch.full((5633,), float("-inf"))
            mask[3584:3840] = 0
            mask[5632] = 0
            self.register_buffer("mask", mask)
        def forward(self, audio_signal, length):
            return self.model.forward_for_export(input=audio_signal, length=length) + self.mask

    wrapper = MaskedCTC().eval()
    feature_inputs = []
    for row in rows:
        result = model.transcribe([row["path"]], batch_size=1, language_id="or", channel_selector="average")
        while isinstance(result, (tuple, list)):
            result = result[0]
        row["nemo_transcript"] = result if isinstance(result, str) else result.text
        # NeMo's transcribe teardown unfreezes submodules (sets train mode).
        # The notebook constructs/evals its wrapper after transcription too.
        wrapper.eval()
        assert not any(module.training for module in wrapper.modules())
        audio, _ = librosa.load(row["path"], sr=16000, mono=True)
        with torch.no_grad():
            feature, length = model.preprocessor(input_signal=torch.from_numpy(audio).unsqueeze(0), length=torch.tensor([len(audio)]))
            logits = wrapper(feature, length).numpy()
        ids = collapse(logits[0])
        row["source_ctc_ids"] = ids
        row["source_ctc_transcript"] = model.tokenizer.tokenizers_dict["or"].ids_to_text([i - 3584 for i in ids])
        print("SOURCE COMPARISON", json.dumps(row, ensure_ascii=False), flush=True)
        save_report(report)
        assert row["source_ctc_transcript"].strip() == row["nemo_transcript"].strip(), "Source wrapper mismatch"
        feature_inputs.append((feature, length))
    save_report(report)
    fp32 = WORK / "model.fp32.onnx"
    print("EXPORT FP32", flush=True)
    with torch.no_grad():
        torch.onnx.export(wrapper, feature_inputs[0], str(fp32), dynamo=False,
                          input_names=["audio_signal", "length"], output_names=["logprobs"],
                          dynamic_axes={"audio_signal": {0: "batch", 2: "time"}, "length": {0: "batch"}, "logprobs": {0: "batch", 1: "time"}}, opset_version=16)
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(fp32), sess_options=options, providers=["CPUExecutionProvider"])
    for row, (feature, length) in zip(rows, feature_inputs):
        logits = session.run(None, {"audio_signal": feature.numpy(), "length": length.numpy()})[0]
        row["fp32_ctc_ids"] = collapse(logits[0])
        row["fp32_matches_source"] = row["fp32_ctc_ids"] == row["source_ctc_ids"]
        save_report(report)
        assert row["fp32_matches_source"], "FP32 export differs from source; stop before quantizing"
    report["fp32_bytes"] = fp32.stat().st_size
    for index, (feature, length) in enumerate(feature_inputs):
        np.savez(WORK / f"features-{index}.npz", audio_signal=feature.numpy(), length=length.numpy())
    save_report(report)
    print("FP32 CHECKS PASSED", flush=True)


def quantize():
    import onnx
    from onnxruntime.quantization import QuantType, quantize_dynamic
    from onnxruntime.quantization.shape_inference import quant_pre_process
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    assert all(row["fp32_matches_source"] for row in report["rows"])
    print("PREPROCESS", flush=True)
    preprocessed = WORK / "model.preproc.onnx"
    quant_pre_process(input_model=WORK / "model.fp32.onnx", output_model_path=preprocessed)
    print("QUANTIZE MatMul ONLY", flush=True)
    output = OUTPUT / "model.int8.onnx"
    quantize_dynamic(preprocessed, output, per_channel=True, weight_type=QuantType.QUInt8, op_types_to_quantize=["MatMul"])
    model = onnx.load(output)
    onnx.helper.set_model_props(model, dict(vocab_size="5633", normalize_type="per_feature", subsampling_factor="4",
        model_type="EncDecCTCModelBPE", version="1", model_author="ai4bharat", language="or",
        comment="iTantra experimental Odia; CTC-only; Odia mask; int8 MatMul-only; accuracy unvalidated"))
    onnx.checker.check_model(model)
    onnx.save(model, output)
    report["int8_bytes"] = output.stat().st_size
    report["int8_sha256"] = sha(output)
    save_report(report)
    print("INT8 WRITTEN", report["int8_bytes"], report["int8_sha256"], flush=True)


def audit():
    import numpy as np
    import librosa
    import onnxruntime as ort
    import sherpa_onnx
    import psutil
    report = json.loads(REPORT.read_text(encoding="utf-8"))
    tokens = [line.rsplit(" ", 1)[0] for line in (OUTPUT / "tokens.txt").read_text(encoding="utf-8").splitlines()]
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    session = ort.InferenceSession(str(OUTPUT / "model.int8.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
    for index, row in enumerate(report["rows"]):
        features = dict(np.load(WORK / f"features-{index}.npz"))
        started = time.perf_counter()
        logits = session.run(None, features)[0]
        row["int8_features_ms"] = (time.perf_counter() - started) * 1000
        ids = collapse(logits[0])
        row["int8_ctc_ids"] = ids
        row["int8_features_transcript"] = "".join(tokens[i] for i in ids).replace("\u2581", " ").strip()
        row["int8_matches_fp32"] = ids == row["fp32_ctc_ids"]
    del session
    gc.collect()
    started = time.perf_counter()
    recognizer = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=str(OUTPUT / "model.int8.onnx"),
        tokens=str(OUTPUT / "tokens.txt"), num_threads=2, decoding_method="greedy_search")
    report["host_sherpa_load_ms"] = (time.perf_counter() - started) * 1000
    for row in report["rows"]:
        audio, _ = librosa.load(row["path"], sr=16000, mono=True)
        started = time.perf_counter()
        stream = recognizer.create_stream()
        stream.accept_waveform(16000, audio)
        recognizer.decode_stream(stream)
        row["sherpa_ms"] = (time.perf_counter() - started) * 1000
        row["sherpa_rtf"] = row["sherpa_ms"] / (row["audio_seconds"] * 1000)
        row["sherpa_transcript"] = stream.result.text
        assert row["sherpa_transcript"].strip(), "Speech fixture produced empty transcript"
        assert "\ufffd" not in row["sherpa_transcript"], "Invalid Unicode"
        assert all(ch.isspace() or "\u0b00" <= ch <= "\u0b7f" or ch in ".,!?0123456789" for ch in row["sherpa_transcript"]), "Unexpected script"
    silence = recognizer.create_stream()
    silence.accept_waveform(16000, np.zeros(48000, dtype=np.float32))
    recognizer.decode_stream(silence)
    report["silence_transcript"] = silence.result.text
    report["host_process_peak_working_set_bytes"] = psutil.Process().memory_info().peak_wset
    report["host_smoke_passed"] = True
    save_report(report)
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=["probe", "fixtures", "restore", "export", "quantize", "audit"])
    parser.add_argument("--wav", action="append", default=[])
    arguments = parser.parse_args()
    if arguments.stage == "probe":
        print(load_nemo().models.EncDecHybridRNNTCTCBPEModel)
    elif arguments.stage == "fixtures":
        print(json.dumps(fixtures(arguments.wav), ensure_ascii=False, indent=2))
    elif arguments.stage == "restore":
        restore()
    elif arguments.stage == "export":
        export(arguments.wav)
    elif arguments.stage == "quantize":
        quantize()
    else:
        audit()
