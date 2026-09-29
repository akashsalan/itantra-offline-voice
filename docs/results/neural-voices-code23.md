# Neural voices — executed checks, 24 September 2026

Build: **0.9.0-neural-voices / code 23**. Debug, arm64-v8a, targetSdk 36,
compileSdk 37. Java 17 and the Gradle wrapper, all tasks `--offline`.

## Passed host checks

- **186 JVM tests, 0 failures, 0 errors** (was 163). New: 9 `TtsTokenizerTest`
  and 14 `TtsRouterPolicyTest`.
- **Lint: 0 errors, 14 warnings** (was 13; the new one is `UsableSpace` from the
  voice importer's free-space guard). Categories unchanged otherwise:
  `UsableSpace`, `UseKtx`, `ConfigurationScreenWidthHeight`, `UnusedAttribute`,
  `OldTargetApi`, `DataExtractionRules`, `ChromeOsAbiSupport`,
  `CustomX509TrustManager`.
- Native `itantra_tts` builds clean for arm64-v8a with **no warnings**;
  `libitantra_tts.so` is 845 KB unstripped, 0.4 MiB packaged.
- Base APK, preloaded APK and the instrumentation APK all assemble.

## Why the vocoder is float32

`hifigan-hi.int8.onnx` op histogram: `ConvInteger` x74, `DynamicQuantizeLinear`
x66, `Cast` x74, `ConvTranspose` x4. Dynamic int8 rewrote every convolution.

Host, 4.2 s of audio, best of three:

| Vocoder | Time | RTF | Size |
| --- | ---: | ---: | ---: |
| int8, 1 thread | 31 923 ms | 7.60 | 21.11 MiB |
| int8, 2 threads | 22 126 ms | 5.26 | 21.11 MiB |
| int8, 8 threads | 21 186 ms | 5.04 | 21.11 MiB |
| int8, auto threads | 12 135 ms | 2.91 | 21.11 MiB |
| **float32, auto threads** | **937 ms** | **0.223** | 53.16 MiB |

About 13x faster for 32 MiB more. Recorded in
[`echobharat-vocoder-bench.json`](echobharat-vocoder-bench.json).

The float32 graph used for that comparison was a rebuild of HiFi-GAN V1 from the
published architecture. The AI4Bharat `hi` config then confirmed the architecture
exactly — `upsample_factors [8,8,2,2]`, `upsample_kernel_sizes [16,16,4,4]`,
512 initial channels, `resblock_kernel_sizes [3,7,11]`, hop 256, 22 050 Hz, 80
mel bins — so the comparison was architecturally fair, and the real export then
measured RTF 0.209 on 5.07 s of audio.

## Real exports

Generator: 13,936,130 parameters, 53.2 MiB float32, `weight_norm` folded.
ONNX output matches the PyTorch reference to **3.19e-06**, and accepts arbitrary
mel lengths (verified at 200 and 437 frames).

End-to-end for all nine languages, int8 FastPitch + float32 HiFi-GAN, one
sentence each, from [`indictts-voice-pairs.json`](indictts-voice-pairs.json):

| Language | Audio | Acoustic | Vocoder | RTF | Dropped chars |
| --- | ---: | ---: | ---: | ---: | ---: |
| hi | 4.21 s | 822 ms | 1061 ms | **0.447** | 0 |
| gu | 3.41 s | 699 ms | 1005 ms | 0.499 | 0 |
| en | 3.66 s | 907 ms | 1093 ms | 0.547 | 0 |
| ta | 3.10 s | 732 ms | 996 ms | 0.557 | 0 |
| mr | 2.68 s | 614 ms | 897 ms | 0.563 | 0 |
| kn | 2.78 s | 730 ms | 870 ms | 0.576 | 0 |
| bn | 2.82 s | 660 ms | 972 ms | 0.578 | 0 |
| ml | 2.73 s | 713 ms | 902 ms | 0.592 | 0 |
| te | 2.18 s | 805 ms | 932 ms | **0.796** | 0 |

Every language is comfortably faster than real time on host, and no language
dropped a character after the danda fix. Before this change the same Hindi
sentence measured RTF 4.42. Telugu is the slowest because its test sentence is
the shortest, so fixed per-call cost weighs more.

Each language now carries **its own** float32 vocoder exported from its own
checkpoint, which resolves the Telugu/Malayalam shared-vocoder defect below.

All nine packs validate under the importer's rules: **9/9 valid**, each about
118.93 MB with a 113.4 MiB payload.

## Earlier host smoke over all nine int8 pairs

[`echobharat-tts-host.json`](echobharat-tts-host.json) recorded the original int8
run across all nine languages and three sentence lengths. It established:

- The dynamo-exported FastPitch really is length-agnostic — 18, 55 and 81
  character Hindi sentences all decoded without the `Reshape` failure the
  publisher warns about for TorchScript exports.
- `।` (U+0964) is **absent from every Indic token table** and was being dropped,
  losing sentence-final prosody. Now folded to `.` before tokenisation.
- Interleaving `<BLNK>` roughly doubles audio duration, so plain longest-match
  tokenisation is correct.
- `hifigan-te.int8.onnx` and `hifigan-ml.int8.onnx` are byte-identical and
  produce identical output tensors: Telugu was being vocoded by Malayalam's
  model. Exporting each language's own float32 vocoder resolves this.

## Packaging

| Artifact | Size | Notes |
| --- | ---: | --- |
| `app-base-debug.apk` | 63.2 MiB | **no** voice or recogniser packs |
| `app-demoPreloaded-debug.apk` | 657.5 MiB | 3 STT packs + 2 voice packs |
| `app-base-debug-androidTest.apk` | 0.5 MiB | compiled only, not run |

Base APK grew by only 0.4 MiB, and inspection confirms zero `starter-voices` and
zero `starter-packs` entries. Preloaded contains exactly
`starter-packs/{en,en-low-end,hi}.itpack` and
`starter-voices/{en,hi}-voice.itpack`.

**Native libraries are unchanged apart from the new bridge.** There is still one
`libonnxruntime.so` at 21.22 MiB; no second runtime was added. `llvm-nm` confirmed
it exports `OrtGetApiBase@@VERS_1.28.2` as a defined global symbol, which is what
made the `dlopen` approach viable.

Voice packs validate against `models.lock.json` under the importer's own rules
(`scripts/verify-voice-packs.py`): **9/9 valid**. `models.lock.json` now pins 74
artifacts, adding 27 neural-voice files with sizes and SHA-256.

## Not run / not claimed

- **No device was attached.** No installation, launch, playback, RTF, RAM or
  cold-load measurement on the RMX1801. The A53 cores should be expected to be
  several times slower than these host figures.
- Instrumentation was **compiled only**. `NeuralVoiceSmokeTest` has not run.
- **Voice quality is not evaluated.** No native-speaker listening test, no MOS,
  no comparison against eSpeak. Deferred milestone 4 is unchanged.
- Odia has **no neural voice** and always uses eSpeak.
- Peak RAM with a resident recogniser plus a resident voice pair is unmeasured;
  this is the most likely real failure on a 4 GB-era device.
- Chunked playback streaming is not implemented, so synthesis completes before
  audio begins.
- The AI4Bharat MIT term is asserted by EchoBharat's `LICENSE`; the upstream
  GitHub repository has no licence file. Verify before distributing.
