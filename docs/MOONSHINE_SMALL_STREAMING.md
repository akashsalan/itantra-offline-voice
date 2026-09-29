# English: Moonshine Small Streaming

This records the code-16 integration. Code 17 additionally bundles Tiny Streaming
as **English — low-end devices**, with a remembered profile chooser. The runtime
is unchanged; current packaging and selection are in [English options](ENGLISH_LOW_END.md).

17 September 2026, **0.6.2-moonshine / code 16**. User-selected replacement for
English Parakeet, not the legacy Moonshine Base trial. Other nine STT languages,
eSpeak NG, Silero VAD, PTT endpoints, transports, SOS, history and settings stay
unchanged. No hosted inference or idle microphone use.

## Exact artifacts and licensing

- Model: Small Streaming English, quantized_26_08_21, 123M parameters.
- Eight required files: **142,300,974 bytes**; with publisher LICENSE.txt:
  **142,315,154 bytes**. `.itpack`: **142,339,040 bytes**.
- Pack: `models/packs/en.itpack`, immutable ID
  `asr.moonshine.en.small.streaming.2026-08-21.quantized`.
- Pack SHA-256: `ba1fbd4182baa05fdfeb90c8d0707114afaf533364684e236539368b53d7d8ac`.
- Source: `https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21/`.
- Publisher metadata checked for size and CRC32C at commit
  `234f60faa0eb388b01cdf7e60aca232af37aefda`; SHA-256 values computed and pinned
  in `models/models.lock.json`. Do not call those publisher-signed SHA values.
- English streaming weights and first-party runtime code: **MIT**. Full license
  in pack and `licenses/moonshine-0.1.5-LICENSE.txt`. Section 2's legacy
  non-English noncommercial terms do not apply to this model.
- Optional spelling, attention decoder, TTS, speaker and LLM model files are
  not downloaded. Android inference uses CPU. No ten-language accuracy claim.

## Runtime isolation

Moonshine Android Maven AAR **0.1.5** comes from
`https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5/`.
Original SHA-256: `ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571`;
its SHA-1 also matched Maven's published checksum during acquisition.

It bundles ORT **1.23.2**, whereas the supplied sherpa AAR's native library
exports version **1.28.2**. They cannot use a Gradle `pickFirst` rule. The host
preparation script emits a reproducible arm64-only AAR, retaining classes.jar,
renaming Moonshine's ORT to `libmoonort.so`, and changing only the corresponding
complete ELF `.dynstr` filename in its JNI, core and ORT libraries. DT_NEEDED,
SONAME and version-dependency offsets remain valid; symbol-version namespaces
remain distinct. Original binaries are retained. The unused permission activity
is omitted; the app owns its existing microphone permission and lifecycle.

Derived AAR: `third_party/moonshine/moonshine-voice-0.1.5-isolated-arm64.aar`,
8,302,359 bytes, SHA-256
`cf948def454984963a9168e7e737d25ef3ab4b9f86e6d0c60b67d3e0fe41f893`.
Gradle verifies this hash. No SDK/dependency upgrades or new permissions.
No Maven transitive UI, WorkManager or HTTP dependencies are added.

Native code for some unused upstream TTS/diarization facilities is embedded in
the AAR. It is not invoked, but contributes binary size. Included third-party
license copies cover ORT, cpp-annote, Eigen's MPL2-only subset, kaldi-native-fbank,
KISS FFT, nlohmann/json, utf8 and utf8proc. Their acquisition paths/revisions are
in `scripts/prepare-moonshine-small.py`. Existing GPL eSpeak and other complete
corresponding-source/release-inventory obligations still apply. This model switch
does not by itself certify SIH acceptance or distribution compliance.

## PTT integration and measurements

`MoonshineStreamingEngine` uses only the low-level JNI API. It never starts a
microphone, downloader, service or background recognizer. A stream is created
only when PCM is supplied for an active PTT. A bounded channel feeds capture
blocks to the single speech worker without blocking AudioRecord/Silero on ASR.
Overflow/failure fails closed; PCM is never silently dropped or sent twice.

Moonshine's internal VAD threshold is zero and its segment cap exceeds the
app's 15-second recording limit. Existing Silero detection remains authoritative:
initial silence/noise is discarded, two seconds of trailing silence finalizes,
resumed speech resets that timer, manual release finalizes immediately, and
finger lift is required before starting again. No neural-VAD claim is based on
an amplitude threshold. Silent/failed/nonlinguistic results are not sent.

Incremental decode cadence starts at 0.5 seconds of audio and adapts to the
previous decode duration. Only the final transcript reaches the existing
draft/send policy. SOS retains its separate confirmation flow. Other recognizers
retain their existing post-capture feeding behavior.

ASR inference time is accumulated recognizer processing wall time across live
feeding/decoding and final flush; it excludes microphone waiting and model load.
Capture-end-to-transcript still uses one phone's monotonic clock. PTT diagnostics
identify the exact new model; no transcript is added to session exports.

## Installation and recovery

Base contains no ASR weights. On an existing base installation Parakeet stays
active until the new English pack is imported. Preloaded includes only Small
Streaming English plus unchanged Hindi and upgrades English on launch unless
the user explicitly opted out of bundled installation. Each file is hash checked,
then the new recognizer is load-tested **before atomic activation**. Only one
heavy recognizer is loaded at a time. A failure preserves the previous pointer;
post-activation load failure can restore the registered Parakeet installation.
There is no automatic mid-utterance/model or transport fallback.

Previous English PC pack: `models/archives/en-parakeet-before-small-20260917.itpack`.
Its SHA is `e9532d0a392fbd63b086e47bbd6bdc1cc69c5f213b4ba0ffb4d8526201435933`.
Existing phone model directories are not deleted. Reimporting that preserved
Parakeet pack remains supported if the user requests rollback. There is no new
one-click model chooser. Baseline source/docs and original APKs are backed up in
`.tools/rollback-backups/moonshine-small-20260917`; do not restore the entire
snapshot over subsequent unrelated user edits. No uninstall/data-clear needed.

## Reproduce basic checks

```powershell
python scripts/prepare-moonshine-small.py
# Preserve an existing en.itpack before rebuilding; builder refuses overwrite.
./scripts/build-model-packs.ps1 -Languages en
./scripts/verify-packs.ps1 -Languages en,hi
$env:JAVA_HOME = 'D:\JAVA 17'
./gradlew.bat :app:testBaseDebugUnitTest --tests org.itantra.app.core.StreamingUtteranceTest --tests org.itantra.app.core.PackCatalogTest --tests org.itantra.app.core.PttEndpointTest :app:assembleBaseDebug :app:assembleDemoPreloadedDebug
```

22 focused JVM tests passed (5 streaming lifecycle, 13 catalogue, 4 endpoint).
On connected RMX1801 / Android 10 / arm64, a fixed 6.625-second human fixture
passed the known-phrase check with both native load orders in separate test
processes. Hindi recognition and Silero still loaded/inferred in the same process.
A tiny rejected-load/import test preserved the prior pointer and successfully
restored Parakeet after a valid Small import. These three Android checks used
disposable cache roots; production pointers stayed unchanged during tests.

First-load-order smoke observation: 9,492.81 ms accumulated ASR processing for
6.625 seconds PCM (RTF about **1.43**); PSS sampled after decode 512,259 KB, **not
peak memory**, and not representative of every app state. This is one fixture,
not a benchmark average, accuracy study, live microphone latency, or low-end
device guarantee. Accent/noise acceptance and user listening/speech trials remain.

Final APK/device/UI evidence is in [the update check record](results/moonshine-small-checks.md).
