# Odia conversion: experimental development record

The supplied `models/source/asr/or/source-model.nemo` is not an Android model.
Conversion work uses that exact checkpoint; no substitute model was selected.
Do not mark Odia release-ready based on conversion or synthetic speech alone.

## Completed checkpoint - 12 September 2026

Conversion and host smoke testing completed; do not restart this as a missing
conversion task. `scripts/export-odia.py` produced the derived INT8 model at
`models/source/asr/or-ctc-experimental/model.int8.onnx` and the separately
importable `models/packs/or-experimental.itpack`. The pack ID is
`asr.indicconformer.or.ctc.experimental.v1`; the original checkpoint is unchanged.

- INT8 model: 197,593,819 bytes; SHA-256
  `b55bab9f4e4ac53655acd5cf9cde8ae8ca5dab20cab426c8e0282c9335b05caf`.
- Pack: 197,692,569 bytes. Uses the locked shared Indic vocabulary, 67,605 bytes,
  SHA-256 `ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2`.
- Original checkpoint: 523,192,320 bytes. FP32 export: 493,087,781 bytes.
- Host source/FP32 CTC tokens matched on two eSpeak-generated fixtures. One
  quantized feature-fixture result differed; waveform decoding also recorded a
  difference. The report does not claim full INT8 equivalence.
- [Host results](results/odia-export-host.json) record a single Windows CPU run:
  load 466.2118 ms; fixture inference 130.6146 / 86.9065 ms; RTF approximately
  0.0429 / 0.0391; process peak working set 491,483,136 bytes. These are NOT Android
  timings or model-only RAM. Three seconds of silence decoded to blank text.
- Host smoke passed; release validation is false. Native-speaker sentence count
  is zero and WER is unset. The two synthetic fixtures are not human ground truth.

The pack was copied to `/sdcard/Download/itantra-or-experimental.itpack`. The user
subsequently reported that Odia works and its STT looks correct. During the later
documentation handoff, read-only ADB confirmed the Odia active pointer on RMX1801
serial <test-phone> with app version `0.3.4-import-progress`, code 8. This is a useful
manual functional report plus installation evidence, not a corpus score.

`OdiaAsrSmokeTest.kt` is compiled but has no captured completed Android run.
The derived-model lock status remains
`experimental_host_synthetic_smoke_passed_android_pending`; the app/pack still
use the experimental label. The original checkpoint's source-only status refers
to the `.nemo` itself, not to the absence of this successful conversion.
See [PROJECT_HANDOFF.md](PROJECT_HANDOFF.md) for exact future test commands.

## Pinned sources

- Checkpoint: https://objectstore.e2enetworks.net/indicconformer/models/indicconformer_stt_or_hybrid_rnnt_large.nemo
- Checkpoint SHA-256: e30cfee192fbdc37b7c0b221c76788b439d445e64368cd0447172f7d77deb616
- AI4Bharat NeMo fork: https://github.com/AI4Bharat/NeMo/tree/8dce88cf8e94963e2033c3137f7b9993b51db88a
- PRD-selected notebook: https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/blob/9b07b2bfbfc6039c2e394545474e87c4cbafa19f/ai4bharat_export_pipeline.ipynb

## Local adaptation

`scripts/export-odia.py` replaces the notebook's interactive cloud download with
SHA-checked local checkpoint restoration. CPU tools live in the ignored
`.tools/odia-export` virtual environment; existing global Torch 2.10.0 CPU is
reused. No training or cloud speech service is involved. The source NeMo archive
is extracted beneath `.tools/odia-export-src`.

The numerical recipe remains the selected notebook's: CTC decoder, Odia-only
output mask plus CTC blank, 16 kHz mono input, legacy opset-16 export, dynamic
feature length, ONNX preprocessing and per-channel QUInt8 MatMul-only
quantization. Conv operators are not quantized. Token offsets and vocabulary
sizes must be asserted against the restored model, not guessed from filenames.

The notebook's unused Neptune logger compatibility shim fails if invoked.
Training dataset configuration is disabled during restoration. Dependency API
incompatibilities must be documented, not hidden by substituting model layers.

### Compatibility adaptations that were necessary

- Native Windows CPU export worked using Python 3.11, Torch/torchaudio 2.10.0 CPU,
  numpy 1.26.4, ONNX 1.20.1, ONNX Runtime 1.24.3 and sherpa-onnx 1.13.8. Other
  pinned packages include PyTorch Lightning 2.2.5, transformers 4.44.2,
  huggingface-hub 0.23.2 (the fork expects `ModelFilter`) and omegaconf 2.3.0.
  Actual environment details are in results/odia-export-environment.json.
- Generate Odia eSpeak fixtures through UTF-8 stdin (`-b 1 --stdin`). Passing
  Odia as Windows CLI arguments initially yielded unusable near-silent audio;
  those files were not accepted as the final fixtures.
- Disable training dataset setup during restore, but retain/restore the
  validation configuration required by the source model's transcription path.
- Source `transcribe()` cleanup changes submodule training state. Reapply
  `wrapper.eval()` after source-reference transcription and before export.
  Initial adaptation checks caught a mismatch before the final model was used.
- Disable inference dither, use the selected per-feature normalization, dynamic
  feature lengths and legacy opset-16 export (`dynamo=False`). Keep the selected
  decoder/masking math; do not replace layers to silence compatibility failures.
- Assert actual Odia language index 14, token offset 3584, language vocabulary
  size 256 and global blank 5632 against the restored model. Output retains only
  Odia tokens plus blank. The shared vocabulary has 5633 entries.
- Quantize MatMul only, per-channel QUInt8. Do not silently switch to Conv
  quantization, FP16, another checkpoint or another runtime.

Ignored `.tools/odia-export-work/` contains FP32/intermediate files and the two
final 16 kHz PCM16 fixtures. Preserve these with the host result if continuing
desktop-versus-Android comparison; don't recreate the expected output from a
failed Android run.

## Reproduction stages

Run from D:\SIH using `.tools/odia-export/Scripts/python.exe -X utf8`:

These stages already ran successfully. Read the script and preserve current
artifacts/evidence before repeating them: reproduction can replace generated
intermediates and reports. An ordinary APK rebuild does not require reconversion.

1. `scripts/export-odia.py probe`
2. `scripts/export-odia.py export` (optionally `--wav path/to/real-odia.wav`)
3. `scripts/export-odia.py quantize`
4. `scripts/export-odia.py audit`

The exporter refuses quantization if source and FP32 CTC tokens differ on the
fixtures. Reports record quantized differences rather than assuming equivalence.
Generated model output is isolated under `models/source/asr/or-ctc-experimental/`;
the original checkpoint is preserved. FP32/intermediate models remain in `.tools`.

## Acceptance scope

Host eSpeak-generated Odia samples are runtime/conversion fixtures only. Their
input strings are synthesis prompts, not human-transcribed ground truth. A
nonempty Odia transcript does not establish accuracy. Native-speaker WER remains
unset until a real labeled corpus has been tested. Quantized Android decoding
must be compared on the exact same PCM16 files as desktop decoding.

PRD section 9.4 still requires at least 100 native-speaker sentences, measured
source/FP32/INT8 desktop/INT8 arm64 comparison, model size/load time/peak RAM/RTF,
and recorded provenance. Until all gates pass, any installable pack and app UI
must say experimental; Odia must not be silently bundled into the base APK or
the English/Hindi starter APK.
