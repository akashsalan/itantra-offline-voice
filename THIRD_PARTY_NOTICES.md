# Third-party inventory

This is an implementation inventory, not a completed distribution compliance claim.
Code 18 adds explicit hands-free turn-taking and uses the same pinned Silero VAD
for PTT and hands-free sessions. No speech weights or runtime dependency changed.
The linked eSpeak NG code requires a GPLv3-compatible application source release.
Before distributing a release, provide complete corresponding source, build
instructions, dependency notices and the applicable license texts together with
the APK. A repository URL alone does not discharge source obligations.

<!-- Code 16 ships Moonshine Small Streaming English. Parakeet remains loadable
only for existing installs/recovery, not bundled. Legacy Base/Zipformer entries
preserve historical provenance. Other speech components are unchanged. -->

Code 17 additionally bundles **Moonshine Tiny Streaming English** in preloaded,
labelled "English — low-end devices". Quantized_26_08_21 files, same publisher
commit and MIT Section 1 as Small. It uses the existing Moonshine 0.1.5 runtime;
no new native dependency, TTS model or hosted inference is added. All eight
files and the full license are separately pinned in `models/models.lock.json`.
| Component | Selected source | License/status |
| --- | --- | --- |
| Moonshine Small Streaming English | quantized_26_08_21; runtime v0.1.5, commit 234f60faa0eb388b01cdf7e60aca232af37aefda | MIT code and streaming weights; licenses/moonshine-0.1.5-LICENSE.txt Section 1; Useful Sensors Inc. (dba Moonshine AI) |
| Moonshine Android runtime dependencies | ORT 1.23.2 (MIT), cpp-annote (MIT), Eigen MPL2-only subset, kaldi-native-fbank (Apache-2.0), KISS FFT (BSD-3-Clause), nlohmann/json (MIT), utf8 (BSL-1.0), utf8proc (MIT/Unicode data terms) | License copies in licenses/moonshine-runtime/; modified arm64 packaging and library filename isolation documented in docs/MOONSHINE_SMALL_STREAMING.md; complete corresponding-source distribution still required |
| eSpeak NG | supplied third_party/espeak-ng, declares 1.53.0 | GPL-3.0-or-later, with separately licensed files; retain COPYING, COPYING.APACHE, COPYING.BSD2 and COPYING.UCD; acquisition revision unknown |
| sherpa-onnx | supplied 1.13.8 Android AAR | Apache-2.0; native dependencies require their notices too |
| English Zipformer 20M (legacy) | original locked sherpa model archive | supplied model README declares Apache-2.0; retained for rollback |
| English Parakeet 110M CTC INT8 | NVIDIA and Suno parakeet-tdt_ctc-110m, sherpa-onnx CTC INT8 export | CC-BY-4.0 weights; Apache-2.0 sherpa runtime; licenses/parakeet-CC-BY-4.0.txt |
| English Moonshine Base quantized 2026-02-27 | csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27, revision 8f4d6c58c03d40bcea40043bb7120a878f2bbef6 | MIT for English, Copyright (c) 2025 Useful Sensors Inc. (dba Moonshine AI); licenses/moonshine-LICENSE.txt Section 1 |
| English Zipformer 2023-06-21 | csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21, revision 9a65b6ea94c311ca770c2bf895b30f456a22d703 | model card declares Apache-2.0; separate English device-trial pack |
| IndicConformer conversion | parismitaglobalsolutions/indicconformer-sherpa-onnx | model card Apache-2.0; underlying AI4Bharat MIT; retain and verify both notices |
| Neural voices: AI4Bharat Indic-TTS FastPitch + HiFi-GAN V1 | acoustic int8 from RaunakSaha/echobharat-models revision 2ad314c3ecafeb252bac82785ac541325596ac9c; vocoder exported here as float32 from AI4Bharat/Indic-TTS `v1-checkpoints-release` | MIT per licenses/indictts-echobharat-LICENSE.txt. The upstream AI4Bharat/Indic-TTS GitHub repository ships no LICENSE file, so the MIT term is asserted by the downstream conversion: **verify at the release before distributing**. Both the int8 conversion and this float32 re-export are modifications and must be declared as such |
| ONNX Runtime (neural voice execution) | the `libonnxruntime.so` 1.28.2 already inside the sherpa-onnx 1.13.8 AAR, resolved at runtime with `dlopen`/`OrtGetApiBase` | MIT; no additional runtime is packaged. `libitantra_tts.so` is app-authored bridge code under this project's licence |
| Odia IndicConformer | original AI4Bharat .nemo, locally exported CTC-only with the PRD-selected notebook | official model card declares MIT; experimental separate pack, not release-validated |
| AI4Bharat NeMo export tools | fork revision 8dce88cf8e94963e2033c3137f7b9993b51db88a | Apache-2.0; host-only tools, not bundled in Android |
| Silero VAD | locked INT8 artifact, unchanged; bundled for PTT and explicitly started hands-free sessions | MIT, Copyright (c) 2020-present Silero Team; licenses/silero-vad-MIT.txt; https://github.com/snakers4/silero-vad |
| AndroidX / Compose | pinned Gradle dependencies | Apache-2.0, notices from resolved artifacts |
| Kotlin / coroutines | pinned Gradle dependencies | Apache-2.0 |
| Protocol Buffers | protobuf-javalite/protoc 4.36.1, Gradle plugin 0.10.0 | BSD-3-Clause runtime; licenses/protobuf-LICENSE.txt |
| Local-network groups | Android platform NSD, sockets, TLS/Conscrypt, AndroidKeyStore and JCE PBKDF2 | OS-provided APIs; no additional SDK or bundled cryptography dependency |

Provenance and SHA-256 for acquired binary artifacts are in
models/models.lock.json. Source revision and license gaps remain explicit release
gates; successful compilation must not change their validation status.

Current English Small Streaming provenance:
https://github.com/moonshine-ai/moonshine/tree/234f60faa0eb388b01cdf7e60aca232af37aefda
https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/
All eight model files are unchanged. The full publisher license is included
in both the pack and APK; Section 1 explicitly covers all streaming models.
The noncommercial legacy non-English license in Section 2 does not apply here.
The Android AAR is repackaged for arm64 and its ORT renamed to libmoonort.so;
DT_NEEDED/SONAME/version-dependency filenames are updated without changing model
code or weights. Original AAR and transformed hashes are in models.lock.json.
Unused upstream microphone/downloader activity is omitted. Embedded optional
native TTS/diarization code is present, but no optional model files are acquired
or invoked. iTantra continues to use eSpeak NG for all TTS. No endorsement implied.

Historical Parakeet replacement provenance:
Parakeet: https://huggingface.co/nvidia/parakeet-tdt_ctc-110m
Export: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2
NVIDIA and Suno weights have been exported to CTC INT8 by upstream sherpa tooling;
iTantra repackages the supplied export unchanged. CC-BY-4.0 attribution and license
are carried in its manifest and LICENSE.txt. No endorsement is implied. Published
TDT scores do not measure this CTC export.

Moonshine: https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/blob/8f4d6c58c03d40bcea40043bb7120a878f2bbef6/LICENSE
The complete upstream LICENSE is retained in both the pack and APK notices. It
explicitly exempts English models from the non-English Community License: MIT
Section 1 applies here. This is Base in sherpa's Models v2 catalog, not Streaming
Small/Medium. The original quantized ORT files are not changed by iTantra.

Previous Zipformer provenance:
https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21/blob/9a65b6ea94c311ca770c2bf895b30f456a22d703/README.md
Training checkpoint:
https://huggingface.co/marcoyang/icefall-libri-giga-pruned-transducer-stateless7-streaming-2023-04-04
Training code:
https://github.com/k2-fsa/icefall/pull/984
The Apache-2.0 text is included under licenses/.

Odia conversion provenance and test limitations: docs/ODIA_EXPORT.md.
Official Odia model license declaration:
https://huggingface.co/ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large
The source checkpoint is preserved and never renamed or packaged as ONNX.
Python/PyTorch/NeMo/ONNX conversion dependencies are host-only; the app continues
to use the supplied sherpa-onnx AAR and embedded eSpeak NG. Existing complete
corresponding-source and release license-inventory obligations remain open.
