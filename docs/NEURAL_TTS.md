# Neural voices â€” code 23

Version `0.9.0-neural-voices`. Adds optional neural text-to-speech beside the
embedded eSpeak NG voice. eSpeak remains bundled for all ten languages and is
still the default, the fallback and the emergency voice.

## What was added

| Layer | File |
|---|---|
| Native ONNX bridge | `app/src/main/cpp/onnx_tts_jni.cpp`, `itantra_tts` CMake target |
| Tokenizer | `core/TtsTokenizer.kt` |
| Voice registry | `core/VoiceCatalog.kt` |
| Voice pack store | `models/VoicePacks.kt` |
| Two-stage engine | `tts/FastPitchHifiGanEngine.kt` |
| Selection and fallback | `tts/TtsRouter.kt` |
| Models UI | `ModelsPage.kt` â€” new **Voices** tab |

Speech recognition, the wire protocol, Room, transports, groups, SOS and the
hands-free pipeline are untouched. `AppRuntime` changes only where the
synthesizer is constructed plus new voice import/select/remove actions.

## Model choice and why float32

Voices are AI4Bharat Indic-TTS: **FastPitch** (text to 80-bin mel) plus
**HiFi-GAN V1** (mel to waveform) at 22 050 Hz, MIT licensed.

The acoustic model is the int8 conversion published by
[EchoBharat](https://huggingface.co/RaunakSaha/echobharat-models) at revision
`2ad314c3ecafeb252bac82785ac541325596ac9c`. The vocoder is **exported here as
float32** from the matching
[AI4Bharat `v1-checkpoints-release`](https://github.com/AI4Bharat/Indic-TTS/releases/tag/v1-checkpoints-release)
checkpoint.

That split is the central decision, and it is measured, not assumed. Dynamic
int8 rewrites every convolution into `DynamicQuantizeLinear` + `ConvInteger`,
which ORT's CPU backend runs far slower than its float32 kernels. On the host,
for 4.2 s of audio:

| Vocoder | Time | RTF | Size |
|---|---|---|---|
| int8 (EchoBharat) | 12 236 ms | 2.91 | 21.11 MiB |
| float32 (exported here) | 937 ms | **0.223** | 53.16 MiB |

About 13x faster for 32 MiB more. The acoustic model stays int8 because it
already measures RTF 0.25 and int8 halves its size.

Full Hindi pipeline after the change: **RTF 0.447** (822 ms acoustic + 1061 ms
vocoder for 4.21 s of audio), against 4.42 with both stages int8.

The exported vocoder matches its PyTorch reference to 3.2e-06 and accepts
arbitrary mel lengths, so the graph is genuinely length-agnostic.

## Runtime: no second ONNX Runtime

`libonnxruntime.so` already ships inside the sherpa-onnx AAR â€” version 1.28.2,
exporting the stable `OrtGetApiBase` C ABI (verified with `llvm-nm`). The bridge
resolves it through `dlopen`, so **no additional runtime is packaged** and
sherpa's and Moonshine's isolated libraries are untouched. `libitantra_tts.so` is
about 845 KB.

The bridge requests a conservative API version and null-checks it, so a future
sherpa upgrade degrades to eSpeak rather than crashing. It only opens sessions
and runs one tensor in and one out; tokenisation, chunking and every policy
decision stay in Kotlin, because a native abort cannot be caught.

## Behaviour rules

- With no voice pack installed the router is a **strict pass-through to eSpeak**,
  so a fresh install behaves exactly as before.
- **Emergency alerts never trigger a cold model load.** They use an already
  resident neural voice or eSpeak. Alert playback must stay prompt.
- **At most one neural voice is resident.** Switching language releases the
  previous pair, bounding native memory alongside the resident recogniser.
- A neural failure degrades that language to eSpeak, reports once, and is not
  retried until the selection changes. A message is never lost to a voice fault.
- The vocoder runs in overlapping mel chunks (250 frames, 8 overlap) to bound
  peak native memory. This does not reduce total work.
- `à¥¤` (U+0964) and `à¥¥` are absent from every shipped token table and were being
  dropped silently, losing sentence-final prosody. They are folded to `.` before
  tokenisation. The stored and transmitted message is never altered.

## Packaging

- Pack contents: `acoustic.onnx` (int8), `vocoder.onnx` (float32), `tokens.json`.
- About **113 MiB payload per language** (Odia 175 MiB); the `.itpack` is about 119 MB (Odia 184 MB).
- **Base flavour bundles no voice packs** â€” eSpeak only.
- **Preloaded bundles English and Hindi voices** beside their existing
  recognisers. Every other language imports from local storage in one tap.
- Voice pointers live under `noBackupFilesDir/voices`, a separate namespace from
  recogniser pointers, so installing or deleting a voice cannot disturb an
  imported speech model.
- Import validation matches the recogniser importer: manifest first, exact flat
  allowlist, declared sizes and SHA-256 checked against `models.lock.json`, two
  verification passes, activation only after a complete staged copy.

## Reproducing the sources

```powershell
# one language at a time; the 1.5 GB archive is deleted after export
powershell -File scripts/build-all-tts-vocoders.ps1
.tools/odia-export/Scripts/python.exe scripts/build-voice-packs.py
```

`fetch-indictts-checkpoint.ps1` keeps only the HiFi-GAN generator and deletes the
archive, so peak disk stays near 2.5 GB instead of 13 GB for all languages.
`export-tts-vocoder.py` folds `weight_norm`, exports float32 ONNX and fails if
output drifts from PyTorch. `stage-tts-source.py` synthesises one sentence per
language so a bad pairing is caught before a pack is built.

## Verified

- 186 JVM tests pass, including 9 tokenizer and 14 voice-policy tests.
- Native `itantra_tts` builds clean for arm64-v8a with no warnings.
- Host synthesis verified for all nine languages; see
  [`results/echobharat-tts-host.json`](results/echobharat-tts-host.json),
  [`results/echobharat-vocoder-bench.json`](results/echobharat-vocoder-bench.json)
  and [`results/indictts-voice-pairs.json`](results/indictts-voice-pairs.json).

## Not verified

- **No phone test.** Host RTF is not Android RTF; the RMX1801's A53 cores should
  be expected to be several times slower, and peak RAM alongside a resident
  recogniser is unmeasured.
- **Voice quality is not evaluated.** These are community int8 conversions with
  a locally exported vocoder and no native-speaker listening study. Deferred
  milestone 4 is unchanged.
## Odia: the split acoustic topology

Odia is the tenth language and the only one with **no published fused acoustic
graph**, so both halves are exported here from `or.zip`. That surfaced a real
obstacle worth recording.

FastPitch predicts how long each token lasts, so a fused graph's output length is
data-dependent. Neither exporter handles it:

- the legacy TorchScript tracer **freezes** the length. The traced graph loaded
  fine and returned 307 frames for every input, and separately hardcoded the token
  count into a `Reshape` inside `nn.MultiheadAttention`;
- `torch.export`/dynamo refuses, through a chain of unbacked-symbol guards.

The fix was to stop fighting the exporter and **split the model at the duration
boundary**, so every graph's output length is a function of its own input length:

```
encoder.onnx  text[1, T]        -> features[1, 512, T], durations[1, T]
app (Kotlin)  repeat each feature column durations[i] times -> [1, 512, F]
decoder.onnx  features[1, 512, F] -> mel[1, 80, F]
vocoder.onnx  mel                 -> audio[1, 1, S]
```

Verified dynamic: 32 tokens produced 286 frames and 61 tokens produced 559, with
encoder drift 5e-06, identical durations and mel drift 2e-04 against PyTorch.

Precision is mixed for measured reasons. Encoder int8 is smaller and faster
(552 -> 170 ms). Decoder int8 was **3.6x slower** and dropped waveform correlation
against float32 to 0.51, so it stays float32 â€” the same `ConvInteger` effect as the
vocoder. Measured: encoder 170 ms + decoder 116 ms + vocoder 1135 ms for 3.66 s of
audio, **RTF 0.389**, payload 175.08 MiB.

Odia's pack is therefore larger than the other nine (175 vs 113 MiB) because its
acoustic half is two graphs with a float32 decoder rather than one published int8
graph. It is still under the 200 MB per-language budget and slightly faster than
Hindi on host.

Two export patches are required and are documented in
`scripts/export-tts-acoustic-split.py`: the positional encoding is recomputed from
the input length instead of slicing a fixed buffer, and the FFTransformer
self-attention is reimplemented with the same weights using `-1` for the sequence
axis. Both are arithmetically identical to the originals.
- **Telugu and Malayalam shared one vocoder** in the EchoBharat int8 export
  (byte-identical files, confirmed identical output). This release exports each
  language's own float32 vocoder from its own checkpoint, so that is resolved.
- The AI4Bharat MIT claim comes from EchoBharat's `LICENSE` and the upstream
  GitHub repository has no licence file. Confirm it at the release before any
  distribution.
- Chunked *playback* streaming is not implemented; synthesis completes before
  audio starts. That remains the main latency improvement still available.

## Device checklist

1. Install preloaded; confirm English and Hindi voices appear installed under
   Models â†’ Voices and that other languages show the built-in voice.
2. Receive a Hindi and an English message; confirm natural playback, then toggle
   **Use built-in** and confirm eSpeak still works.
3. Receive Odia; confirm it speaks with eSpeak and reports so honestly.
4. Send an emergency alert; confirm playback starts promptly and does not wait
   for a model load.
5. Import a third language voice from storage; confirm progress, verification and
   that the recogniser selection and message history are unchanged.
6. Delete a voice; confirm that language reverts to eSpeak and other voices and
   all speech models survive.
7. Measure RTF, peak RAM and cold-load time. Run `NeuralVoiceSmokeTest` and pull
   `neural-voice-android.json`.
8. Confirm STT, groups, DMs, SOS, BLE relay and hands-free are unaffected.

