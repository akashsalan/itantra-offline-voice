# iTantra implementation handoff

Current build: **0.8.0-public-sos-preview / code 22**, 22 September 2026.
See [public BLE SOS design](PUBLIC_BLE_SOS.md) and
[host verification / device limits](results/public-sos-code22.md). Public SOS
receiving and automatic forwarding (three radio hops, no team code) now live
inside Emergency SOS, isolated from connected/private routes. Both APKs are in
`iTantra_Public_BLE_SOS_2026-09-22/APKs`. 163 JVM tests passed and lint has no
errors/13 warnings. No phone was attached or updated; real multi-phone and UI
validation remain open. Speech assets and hands-free capture are unchanged.

The older checkpoints below are historical, not the current build version.

Earlier code: **0.7.0-hands-free-preview / 18**, 19 September 2026.
Read [requirements implementation and device checklist](REQUIREMENTS_2026_09.md)
and [code-18 verification](results/requirements-code18.md). Models are unchanged.
Hands-free is explicit turn-taking, not full duplex. Milestone 4 quality evaluation
was deferred by the user. No code-18 phone installation/test is implied by host
build success. [Source-release gates](RELEASE_CHECKLIST.md) remain open.

The code-17 and older snapshots below retain their historical evidence.

Latest speech checkpoint: **0.6.3-english-options / code 17**. English has MIT
Moonshine Small Streaming plus **English — low-end devices** (Tiny Streaming).
Preloaded bundles both and Hindi; base remains ASR-weight-free. Read
[English options](ENGLISH_LOW_END.md), [current checks](results/english-options-checks.md)
and [the native integration record](MOONSHINE_SMALL_STREAMING.md).
The prior Parakeet install remains recoverable; Hindi/Odia phone imports and
the communication, SOS, VAD, diagnostics and visual-refresh work are retained.

Superseded UI/connection snapshot: see [17 September field-preview implementation](IMPLEMENTATION_2026_09.md).
That preview retained Parakeet English, PTT-only neural VAD and structured diagnostics.
Direct groups and BLE emergency relay are implemented but require multi-phone validation.

Snapshot: 13 September 2026, `D:\SIH`. Entry point: [CONTINUE_HERE.md](../CONTINUE_HERE.md).
The [PRD](../ITANTRA_PRD.md) remains the baseline; this file records current
implementation and evidence, not a declaration that every requirement passed.

## Previous checkpoint - English was Parakeet

**0.5.1-english / code 12**: the user manually tested and selected Parakeet 110M
as the only English STT model. It is labelled English; comparison controls and
retired English imports are removed. See [ENGLISH_MODEL.md](ENGLISH_MODEL.md).
The current pack is `models/packs/en.itpack`, phone `Download/itantra-en.itpack`.
Old English distributions are recoverably archived, not in the ten-pack current
folder. The existing Parakeet installation and other languages are preserved.
Base remains model-free; preloaded now contains Parakeet English and Hindi only.
Model identity/CC-BY-4.0 attribution remain intact. No corpus WER is claimed.
The final base APK is installed/hash-verified, and the English-only UI was checked
on the handset. Current build: 55 JVM passes, 0 lint errors/19 warnings. The new
Android suite has 14 passes and 1 Odia synthetic desktop-parity failure. English
STT and all pack/protocol/TLS/TTS checks in that suite passed. Odia's model is
unchanged; the mismatch cause is not established. See the guide and raw reports.

## Previous checkpoint - selectable English engines (superseded)

Latest: **0.5.0-english-comparison / code 11**, installed as an in-place base APK
update on RMX1801/<test-phone>. See [ENGLISH_COMPARISON.md](ENGLISH_COMPARISON.md) for
the exact model decision, import/switch workflow, source revisions, phone paths,
tests, storage warning and reproducible commands. Current Zipformer stays the
default; Parakeet 110M CTC INT8 and Moonshine Base are separate comparison packs.
No other language, TTS implementation, connectivity protocol or group UX changed.
All 13 packs are in `D:\SIH\models\packs`; the two candidates are in the phone's
Download folder for manual import. Neither candidate is in either APK flavor.
Both APKs and instrumentation compile, 55 JVM tests pass, lint has 0 errors/19
warnings. Five new Android pack-store tests pass. Real ASR evidence is linked
from the comparison guide. No accuracy winner or corpus WER is claimed.

The per-language activation pointer remains backward compatible. Separate
`installed-<packId>` pointers preserve variants for switching; old active packs
are registered lazily before selecting another pack. Imports still hash-verify
twice and show progress. English engine dispatch follows pinned profile metadata,
not language. Runtime releases the old recognizer before loading another.
The demo starter installer no longer replaces a valid alternate English pack.

## Preceding checkpoint - local groups

Latest version is **0.4.1-connections-ui / code 10**. This follow-up renames
Devices to Connections and brings One-to-one / Groups to the top. Groups exposes
its two supported methods immediately, with Create/Find together and a separate
name/password dialog. Talk, Messages and transport behavior are unchanged.
The real-phone layout inspection is recorded in
[connections-ui-check.md](results/connections-ui-check.md).
The underlying group feature was introduced in code 9. The user approved and we
implemented named/password groups on Same Wi-Fi/Phone Hotspot, simple Devices
versus Groups setup, auto-refresh, local one-to-one invitations, group Talk,
and silent group/direct Messages with member selection and unread counts.
The user previously reported successful legacy EN/HI two-phone and Classic use.

Read [LOCAL_GROUPS.md](LOCAL_GROUPS.md) for exact operation, architecture,
security boundaries, recovery and remaining tests. `DECISIONS.md` records the
additive LAN protocol and the Android TLS signing-authorization fix discovered
and verified in real tests. Existing models, speech engines, model import,
legacy radio protocol and history were preserved. Room now migrates through v3.

Current source additions are `lan/`, `LanDevicesPage.kt`, `LanMessagesPage.kt`,
`core/PlaybackQueue.kt`, `protocol/src/main/proto/lan.proto`, and their tests.
AppRuntime selects LAN versus existing radios without replacing ASR/TTS APIs.
One audio owner serializes automatic incoming speech and explicit replay.

Both APK variants build. Base contains no ASR model; preloaded contains only
English v2 and Hindi. The paths below remain valid, but **old hashes/sizes/version
claims in the archived section are not current**. See current
[build-summary.json](results/build-summary.json) for exact artifact values.
Latest check: 52 JVM tests, 0 lint errors, 19 warnings. Eight tests passed on
RMX1801/API 29 in [android-local-groups-tests.txt](results/android-local-groups-tests.txt).
They include real TLS sockets/Room (three local identities), dropped ACK/dedup,
private routing, named group resume, password/host consent, TLS 1.2 and host pin
rejection, real Wi-Fi-interface discovery/message, legacy protocol/migration,
and ten-voice native PCM synthesis. They are not multiple physical radios or
acoustic/listening measurements. New group UI was inspected on the USB phone.

Updates use `adb install -r`; no app data is cleared.
All 11 existing `.itpack` files remain under `D:\SIH\models\packs`. No packs
were changed or copied again for this feature. No Git commit/push/backup occurred.
The existing dirty working tree contains all latest work; preserve it.

Next: install this same APK on two/three phones and follow LOCAL_GROUPS.md's
router/hotspot and group/direct/audio queue checklist. Hotspot clients may host
only when the local network permits peer traffic. No host migration or host-blind
DM encryption is claimed. Independent security review, hands-free VAD and formal
language/release obligations remain open. Do not re-convert Odia unnecessarily.

## Archived handoff - 12 September 2026

Everything below records the previous checkpoint. Its no-LAN/no-application-TLS,
version-8 and old test-count statements are historical, not the current status.

## User intent and current UX

The user wants a practical offline two-phone communicator and currently has
one physical Android phone. They asked for builds to be installed on that phone,
separate importable language packs, and a polished Talk screen. The latest
request was to save the work in Markdown so a future session can resume.

- Talk: connected peer at the top; persistent auto-send and receiver auto-play
  controls; manually selected language; normal/emergency priority. Scrollable
  conversation with outgoing bubbles right, incoming left, replay icons on both,
  and an editable unsent transcript. Compact hold-to-speak, maximum 15 seconds.
- Recording does NOT automatically trigger local TTS. Own-message replay is an
  explicit action. With auto-send off, review and tap Send. With auto-send on,
  only a confirmed connection permits sending; an offline capture stays a draft
  and is not silently sent on a later connection.
- Receiver voice messages can play automatically or manually on Talk. Messages
  is separate text-only chat, with no microphone and no automatic speech.
- Devices supports Wi-Fi Direct, secure Bluetooth Classic, matching-code
  confirmation and BLE discovery. BLE is not the message transport.
- Models imports local `.itpack` files. The newest change adds a modal percentage
  UI, copied bytes, file name and copy/verification stages. File installation can
  reach 100% before engine loading finishes; loading has a separate status.

## Latest app and deliverables

Package `org.itantra.app`; version `0.3.4-import-progress`; version code `8`.
The build evidence timestamp is `2026-09-12T11:29:18.8338345Z`.

| Artifact | Path relative to D:\SIH | Bytes |
|---|---|---:|
| Base debug APK | `app/build/outputs/apk/base/debug/app-base-debug.apk` | 47,158,315 |
| English/Hindi preloaded APK | `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 435,080,370 |
| Instrumentation APK | `app/build/outputs/apk/androidTest/base/debug/app-base-debug-androidTest.apk` | See current file |

Base SHA-256:
`3c038f5b9d452ca1a607486bd49b1ff89754f0fc25aec4c9f3a709fc3f1a05c0`.
Preloaded SHA-256:
`25c65a39e667fedb33a26c1fc61fae8988c46cd4f2baceeee92b363c404a2975`.
These values are from [build-summary.json](results/build-summary.json), not
guarantees about future overwritten build outputs.

Both variants are arm64-only and embed all ten selected eSpeak voices. Base
contains no ASR pack. The larger `demoPreloaded` flavor contains ONLY English v2
and Hindi, which are verified/imported on first use. Other languages stay
separately installable. No Android system-TTS app or cloud inference is required.

All 11 pack files are in `D:\SIH\models\packs\` (ten languages plus old English):

| Language/use | File | Bytes |
|---|---|---:|
| English, current | `en-v2.itpack` | 190,211,192 |
| English, legacy rollback | `en.itpack` | 45,210,248 |
| Hindi | `hi.itpack` | 197,694,194 |
| Bengali | `bn.itpack` | 197,694,179 |
| Gujarati | `gu.itpack` | 197,694,063 |
| Kannada | `kn.itpack` | 197,694,330 |
| Malayalam | `ml.itpack` | 197,694,154 |
| Marathi | `mr.itpack` | 197,694,195 |
| Odia, still labeled experimental | `or-experimental.itpack` | 197,692,569 |
| Tamil | `ta.itpack` | 197,694,115 |
| Telugu | `te.itpack` | 197,694,294 |

Pack integrity is not speech accuracy. See [pack-integrity.json](results/pack-integrity.json)
and [models.lock.json](../models/models.lock.json) for provenance and hashes.

## Machine and connected phone

- Java 17: `D:\JAVA 17`; Android SDK:
  `%LOCALAPPDATA%\Android\Sdk`.
- Kotlin/Jetpack Compose; minSdk 26, compileSdk 37, targetSdk 36; arm64-v8a.
- Gradle 9.3.1, AGP 9.1.1, Compose compiler 2.2.10.
- NDK 27.1.12297006; CMake 3.22.1.
- Local runtime AAR: `third_party/sherpa-onnx/sherpa-onnx-1.13.8.aar`.
- Native eSpeak source: `third_party/espeak-ng`; generated host data:
  `.tools/espeak-host/espeak-ng-data`. Preserve local ignored prerequisites.

Phone: RMX1801, serial `<test-phone>`, Android 10/API 29, arm64. Read-only ADB checks
during this handoff confirmed an authorized device, installed version code 8 /
`0.3.4-import-progress`, and `active-en`, `active-hi`, `active-or` under
`no_backup/models`. Existing model directories include English v2 and legacy,
Hindi, and `asr.indicconformer.or.ctc.experimental.v1`. This is an inventory,
not a fresh speech test or per-file integrity audit.

The Odia USB copy was placed at
`/sdcard/Download/itantra-or-experimental.itpack`. Do not assume every desktop pack
was copied to the phone; inspect storage before saying so. Free space was limited
during recent work; recheck before copying/importing more approximately 198 MB
packs. Update with `adb install -r`, not uninstall or clear-data, to preserve
models/settings/history.

## Source map and design boundaries

Application paths below are relative to `app/src/main/java/org/itantra/app/`.

| Area | Main files |
|---|---|
| Lifecycle/state coordination | `ItantraApplication.kt`, `AppRuntime.kt`, `TalkViewModel.kt` |
| Compose UX | `MainActivity.kt`, `TalkPage.kt`, `ChatPage.kt`, `ImportProgressDialog.kt` |
| Language/engine contracts | `core/Contracts.kt`, `core/PackCatalog.kt` |
| Model install/progress | `models/ModelPacks.kt`, `core/PackImportProgress.kt` |
| Speech adapters | `asr/SherpaEngines.kt`, `tts/EspeakNgEngine.kt`, `audio/` |
| Native TTS | `app/src/main/cpp/espeak_jni.cpp` (repository-relative) |
| History/outbox/settings | `data/` (Room v2 and DataStore) |
| Session and framing | `protocol/RadioSession.kt`, `protocol/Wire.kt` |
| Wi-Fi/Bluetooth/BLE | `transport/` |
| Protobuf schema | `protocol/src/main/proto/itantra.proto` (repository-relative) |

ASR/TTS/transport interfaces remain replaceable. Only one heavy ASR model is
resident; audio capture, ASR and TTS are coordinated off the UI thread. English
uses the June 2023 Zipformer online-transducer replacement with 0.66 seconds of
tail context, excluded from real audio duration. Indic/Odia use offline NeMo
CTC utterance decoding at 16 kHz. Local eSpeak JNI returns PCM to AudioTrack.

Imports validate the exact allowlisted manifest, file sizes and SHA-256, reject
unsafe paths, and switch an active-version pointer only after validation. A
partial import must never replace a working model. Progress counts copying and
two file-verification passes, caps at 99% until activation, then reports engine
loading separately. Both manual and starter-pack imports use this progress path.

Room v1 history migrates to v2 without deletion; existing messages are VOICE.
CHAT is silent and requires the peer's negotiated text-chat capability. Do not
remove the capability check or make chat trigger TTS on older peers.

Wi-Fi Direct advertises `_itantra._tcp`, TCP port `38773`. Secure Bluetooth Classic
RFCOMM UUID: `86b64920-28b8-4db5-8c15-2c93d279bc61`. BLE presence UUID:
`d691c0b2-18d4-42db-93e8-a287ac6aa6f4`. A BLE address is not assumed to be a
Classic address. One stream transport is selected at a time; fallback is manual
and creates a freshly confirmed session. Queued messages retain their peer.

Wire protocol remains framed protobuf: four-byte big-endian length, max 64 KiB
frame, CRC verification, NFC Unicode text, 2,048-byte text chunks, language and
priority metadata. Protocol major 1/minor 1 adds negotiated silent chat. Retries
are 1/2/4 seconds, at most three retries. Delivery, playback and human
acknowledgements are distinct. In-memory tests do not prove radio reliability.

## Evidence: what actually happened

| Evidence | What it establishes | What it does not establish |
|---|---|---|
| Latest `results/build-summary.json` | Both APK variants and test APK compiled; 36 JVM tests passed; 0 lint errors, 17 warnings; APK model/ABI inventory | Fresh on-device speech or two-phone operation |
| `results/android-conversation-tests.txt` | Earlier real-phone run: 4 tests passed, including Room migration/persistence, retry/dedup/ACK states and ten-voice native PCM synthesis with reopening | Physical-radio operation, speech intelligibility or a rerun against every later change |
| `results/pack-integrity.json` | Host pack manifest, size and hash checks for 11 packs | Recognition quality or Android acceptance of every pack |
| `results/english-stt-*-audit.json` | Recorded host comparisons behind the English replacement | A representative corpus, accent/noise acceptance or phone performance |
| `results/odia-export-host.json` | Source/FP32 comparison, INT8 differences, two synthetic host fixtures, silence and measured host runtime | Native-speaker WER or Android benchmark results |
| User's manual trials | Hindi speech worked; replacement English worked with some errors; user said Odia works and STT looks correct | Retained recordings, scored references, a formal listening study or all ten languages tested |
| Handoff ADB inventory | Current app version installed and English/Hindi/Odia active pointers present | A new recognition, playback or model-integrity test |

Earlier logs `android-phase1-core-tests.txt` and `android-tts-smoke.txt` remain
historical evidence. The newer progress unit tests cover copy/check accounting,
premature completion rejection and bounded updates. A completed visual device
inspection of the new percentage dialog was not captured.

`OdiaAsrSmokeTest.kt` and `EnglishAsrSmokeTest.kt` compile, but a completed run of
those Android ASR tests was not captured. Do not manufacture an Android result
file, quote host RTF as Android RTF, or treat app installation as a passed test.
The evidence index is [results/README.md](results/README.md).

## Odia: conversion complete, formal acceptance incomplete

See [ODIA_EXPORT.md](ODIA_EXPORT.md) for pinned sources and the recipe. We used
the supplied original checkpoint, not a substitute. Export and quantization ran
on Windows CPU with `.tools/odia-export/Scripts/python.exe`, using global Torch
2.10.0 CPU and the pinned AI4Bharat NeMo source under `.tools/odia-export-src`.
No GPU, Docker, WSL or cloud inference was needed for this completed export.

Generated runtime files are isolated in `models/source/asr/or-ctc-experimental/`.
The INT8 model is 197,593,819 bytes, SHA-256
`b55bab9f4e4ac53655acd5cf9cde8ae8ca5dab20cab426c8e0282c9335b05caf`.
The installable pack is `models/packs/or-experimental.itpack`, pack ID
`asr.indicconformer.or.ctc.experimental.v1`. Shared vocabulary is
`models/source/asr/indic-tokens.txt`. Original `.nemo` and FP32/intermediate files
are preserved; the `.nemo` itself remains source-only and cannot run in Android.

Source and FP32 CTC tokens matched on two synthetic fixtures. Quantization and
waveform decoding had recorded differences; do not claim full equivalence.
Host smoke passed, but `release_validated` is false, native-speaker sentence
count is zero and WER is unset. The lock's derived-model status still says
`experimental_host_synthetic_smoke_passed_android_pending`. This predates the
user's successful informal Odia trial and refers to the unfinished formal test.

The user requested removal of "experimental" in a message ending "just need
answer"; no source/pack/lock label was changed. They then preferred a working
demo over collecting 100 sentences. No requirement was formally rewritten and
no tests were implied. If revisiting release labeling, record an explicit
decision and keep the distinction between functional demo and PRD acceptance.

## Rebuild or reinstall later

These are future commands, not actions performed by the documentation save.
Build/test outputs overwrite generated reports; retain an evidence snapshot if
comparing builds. A normal rebuild does not need Odia reconversion.

```powershell
Set-Location D:\SIH
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-build.ps1 -IncludePreloaded
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-packs.ps1
```

The build script compiles both requested variants/test APK, runs base JVM tests
and lint, checks ABI/model contents and writes `docs/results/build-summary.json`.
Pack-building scripts preserve existing outputs; do not delete or regenerate all
packs merely to rebuild the app.

For an explicitly requested phone update, confirm the current serial first:

```powershell
$adbExe = '%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe'
& $adbExe devices -l
& $adbExe -s <test-phone> install -r app/build/outputs/apk/base/debug/app-base-debug.apk
& $adbExe -s <test-phone> shell am start -n org.itantra.app/.MainActivity
```

Do not uninstall or clear app data to resolve an ordinary update problem.
This handset already has the necessary English/Hindi/Odia imports; the base
APK is sufficient for its update. Use the preloaded APK for a fresh EN/HI demo
installation when appropriate, not to bundle every language.

### Completing the pending Odia Android smoke test

Read `app/src/androidTest/java/org/itantra/app/OdiaAsrSmokeTest.kt` first. Confirm
the selected Odia pack and the original host fixtures still exist. Instrumentation
restarts the app; do not run it during an active user conversation.

```powershell
& $adbExe -s <test-phone> install -r app/build/outputs/apk/androidTest/base/debug/app-base-debug-androidTest.apk
& $adbExe -s <test-phone> shell mkdir -p /sdcard/Android/data/org.itantra.app/files/odia-trial
& $adbExe -s <test-phone> push .tools/odia-export-work/fixture-0.wav .tools/odia-export-work/fixture-1.wav /sdcard/Android/data/org.itantra.app/files/odia-trial/
& $adbExe -s <test-phone> push docs/results/odia-export-host.json /sdcard/Android/data/org.itantra.app/files/odia-trial/host.json
& $adbExe -s <test-phone> shell am instrument -w -e class org.itantra.app.OdiaAsrSmokeTest org.itantra.app.test/androidx.test.runner.AndroidJUnitRunner |
    Tee-Object -FilePath docs/results/android-odia-tests.txt
& $adbExe -s <test-phone> pull /sdcard/Android/data/org.itantra.app/files/odia-trial/result.json docs/results/odia-android.json
```

Check actual runner assertions/status, not only ADB exit code. Check result
freshness: a JSON export can exist after a failed assertion. Record the failure
if output differs; do not modify expected text to make it pass. This test compares
identical synthetic PCM with host decoding and measures phone load/inference/RTF/
sampled process memory. It is still not native-speaker accuracy acceptance.

## Remaining work and next-session priorities

Start from the user's next request, not an automatic rewrite of the app.

1. With the current phone: finish the import-progress visual check and the Odia
   instrumented comparison if requested, preserving imported packs and history.
2. With a second arm64 phone: install the same version; select the same transport,
   discover/connect from one side, approve Android pairing as needed, and confirm
   the matching app code on both. Test both directions over Wi-Fi Direct and
   Classic, correct language playback, manual/automatic Talk controls, silent
   chat, emergency priority, distinct ACK states, retry/dedup and reconnect.
   Repeat without internet and retain results. BLE presence alone is not a link.
3. Hands-free Silero VAD is not implemented. The supplied VAD artifact exists,
   but PTT currently supplies the speech endpoint. Add turn-taking and TTS/mic
   exclusion under the PRD before claiming hands-free completion.
4. Physical-radio automatic fallback, range, low/mid-device performance and
   full ten-language acceptance are not established. Existing radio selection
   is manual. Use [test-plan.md](test-plan.md) and PRD acceptance sections.
5. Native-speaker corpus/listening tests and source/license distribution remain
   final-submission work. The 100-sentence requirement is in our PRD; it was not
   in the user's quoted framework restriction. Do not use this distinction to
   invent an evaluation or claim the PRD has already been satisfied.
6. No application-level authenticated encryption is implemented. PRD permits
   WPA2/Bluetooth pairing for a controlled initial demo, but requires stronger
   protection before sensitive operational data. Emergency audio is best effort
   under Android policy, never guaranteed uninterruptible.

The selected architecture uses open-source speech components and local inference;
no proprietary voice SDK, hosted speech API or other installed TTS app is used.
This is separate from final license/source-distribution obligations documented
in [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md). Do not describe the large
Indic models as microcontroller-sized TinyML models.

## Git, backups and safety

Last recorded commit: `909a6e8 Implement Phase 0 offline speech with separate
model packs`. Substantial later code, tests, scripts and documentation remain
modified/untracked. Preserve this dirty working tree; a checkout of the last
commit alone would lose access to the latest implementation.

The Markdown save did not commit, push, rebuild, reinstall or alter models. No
remote backup was created. A future backup must cover the working tree and its
ignored prerequisites/artifacts separately, including `models/source/`,
`models/packs/`, the local sherpa AAR, native source/data and useful export tools.
Do not put large `.itpack`, `.onnx`, `.nemo`, `.aar` or `.apk` binaries into normal
Git history. Do not reset/clean the repository or remove older phone models
without the user's explicit authorization.

The current shell may start in `C:\Windows\System32`; always set the working
directory to `D:\SIH`. Current sandbox configuration may require permission to
write there. Use `apply_patch` for source/document edits, and preserve unrelated
user changes. Read the PRD/model records completely before implementation work.
