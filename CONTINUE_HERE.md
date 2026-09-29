# Continue iTantra here

Latest build: **0.9.0-neural-voices / code 23**, 24 September 2026.
Read [Neural voices](docs/NEURAL_TTS.md) first. Models now has a **Voices** tab.
eSpeak NG stays bundled for all ten languages as the default, the fallback and
the emergency voice; neural voices are optional per-language packs of about
113 MiB. Base bundles none; preloaded bundles the English and Hindi voices beside
their existing recognisers. Every other language imports in one tap from storage.

Voices are AI4Bharat Indic-TTS FastPitch + HiFi-GAN V1, MIT. The acoustic model is
EchoBharat's int8 conversion; **the vocoder is exported here as float32** because
dynamic int8 measured RTF 2.91 against 0.223 for the identical float32 graph.
Full Hindi pipeline went from RTF 4.42 to 0.447 on host. No second ONNX Runtime is
packaged: the bridge `dlopen`s the `libonnxruntime.so` 1.28.2 already inside the
sherpa-onnx AAR. Never combine the three runtimes with `pickFirst`.

186 JVM tests pass, lint has 0 errors and 14 warnings, base APK is 63.2 MiB and
grew only 0.4 MiB. **No phone test**: host RTF is not Android RTF, peak RAM
alongside a resident recogniser is unmeasured, and voice quality is not evaluated.
Odia has no neural voice and always uses eSpeak. Deferred milestone 4 is unchanged.
Verify the AI4Bharat MIT claim at its release before distributing.
Speech recognition, transports, protocol, Room, groups, SOS and hands-free are
untouched. Previous code-22 delivery and all STT packs are preserved.

Previous build: **0.8.0-public-sos-preview / code 22**, 22 September 2026.
Read [Public BLE SOS](docs/PUBLIC_BLE_SOS.md) and
[executed checks](docs/results/public-sos-code22.md). Emergency SOS now contains
Connected SOS and BLE Relay SOS tabs. Public receiving opt-in automatically
forwards valid alerts up to three radio hops, with no team code or pairing.
Private relay remains separate and moved out of Connections into Connected SOS.
163 JVM tests passed; lint zero errors/13 warnings; both APKs and Android test
APK built. No phones attached, installation, visual/audio or physical relay test.
Packaged APKs: `iTantra_Public_BLE_SOS_2026-09-22/APKs/`. Old code-21 delivery
and all STT models are preserved. 315 speech/native asset entries match code 21.
Public mode is signed but NOT encrypted, opt-in for 30 minutes, with five-minute
expiry, duplicate/rate/retry limits, and no automatic consent restart. Do not
claim verified sender identities, guaranteed delivery or physical multi-hop proof.
The source snapshot before this task is under `.tools/rollback/public-sos-before-20260922`.

Latest runtime fix: **0.7.3-foreground-fix / code 21**, 19 September 2026.
Read [the microphone foreground-service fix](docs/FOREGROUND_SERVICE_FIX.md) and
[final build/device evidence](docs/results/foreground-fix-code21.md). Both APKs
are rebuilt; preloaded is installed and hash-verified on RMX1801 (<test-phone>).
Capture protection lasts through recognition/cleanup; expected old service
teardown cannot poison the next press. Promotion is acknowledged before audio,
real failures are retained, and explicit retry/Activity visibility are corrected.
145 JVM tests passed, lint has zero errors/15 warnings, startup succeeded, and
all eight checked settings/model-selection/database files survived unchanged.
No automated microphone test was authorized or run; final PTT/hands-free and
two-phone acceptance remain pending. Speech/model payloads are unchanged.
The user's unsent pre-update draft was saved privately in the ignored rollback
folder; see the result record. Milestone 4 and source-release gates stay open.

Latest UI: **0.7.1-hands-free-ui / code 19**, 19 September 2026.
Read [Hands-free UI](docs/HANDS_FREE_UI.md) and its verification record. Talk now
has a single mode switch and a distinct call-style hands-free view with explicit
Start, real microphone/turn states, transcript sheet and Mute/End. Other tabs show
an active-session return banner. The code-18 speech/transport pipeline is unchanged;
this is not full-duplex calling. Code 19 preloaded is now installed and launched
on RMX1801 (<test-phone>); saved selections/settings/history were retained. See the
[device update record](docs/results/code19-device-update.md). Visual interaction,
microphone/audio and two-phone code-19 acceptance remain pending.
Milestone 4 and source-release gates remain deferred/open as recorded below.

Speech/runtime checkpoint: **0.7.0-hands-free-preview / code 18**, 19 September 2026.
Read [requirements work](docs/REQUIREMENTS_2026_09.md) and its verification record
before using older checkpoints below. Explicit hands-free turn-taking, unified
emergency priority, Wi-Fi permission recovery and idle CPU observations are added.
The user deferred milestone 4 (native-speaker quality evaluation); no language/model
acceptance status was upgraded. No code-18 physical-phone result is implied.
English Small/Tiny and all other model files remain unchanged. Source release is
still gated by [the release checklist](docs/RELEASE_CHECKLIST.md).

Latest speech update: **0.6.3-english-options / code 17**, 17 September 2026.
English has **Small Streaming** plus **English — low-end devices** (Tiny Streaming).
Read [English options](docs/ENGLISH_LOW_END.md) for packaging, selection and checks.
Preloaded contains both English profiles and Hindi; base has no ASR weights.
The selected English profile persists and is not reset by bundled installation.
Read [the integration record](docs/MOONSHINE_SMALL_STREAMING.md) before changing
English or native libraries. sherpa's ORT and Moonshine's ORT are isolated;
never use `pickFirst` to combine their libraries. eSpeak and other languages stay
unchanged. Previous Parakeet files and baseline source/APKs are recoverable.
The code-16 integration record and code-15/earlier notes below describe earlier
checkpoints; current English choices and packaging are as stated above.

Latest visual-only refresh: [screenshots, APK hashes and basic device checks](docs/results/visual-refresh-checks.md).
That visual refresh used code 15. Talk has a compact rectangular PTT; the top bar
contains Emergency SOS and Not connected/Connected. Speech, model, transport,
database and protocol logic were not changed by the visual refresh.

17 September update: source is now `0.6.1-sos-preview` (code 15). Read
[the approved implementation record](docs/IMPLEMENTATION_2026_09.md),
[the dedicated voice-first SOS flow](docs/SOS_FLOW.md),
[BLE limitations](docs/BLE_EMERGENCY_RELAY.md) and
[model catalogue status](docs/MODEL_CATALOGUE.md) before relying on the older snapshot below.

Saved: 13 September 2026. Project: `D:\SIH`.
This is an existing Android application, not a new scaffolding task.

## Read before making changes

Read these files completely, in order:

1. [ITANTRA_PRD.md](ITANTRA_PRD.md) - source of truth.
2. [models/README.md](models/README.md).
3. [models/models.lock.json](models/models.lock.json).
4. [Project handoff](docs/PROJECT_HANDOFF.md) - current implementation, evidence,
   commands, limitations and next steps.
5. [DECISIONS.md](DECISIONS.md) - newest entries supersede historical status.

For Odia work, also read [ODIA_EXPORT.md](docs/ODIA_EXPORT.md). Do not replace
models or protocols without recording the decision. Keep ASR, TTS and transport
replaceable, inference fully offline, and eSpeak inside this app.

## Current checkpoint

**0.5.1-english / code 12** supersedes the comparison checkpoint below. The user
tested Parakeet and selected it as the sole English STT model, labelled English.
Read [ENGLISH_MODEL.md](docs/ENGLISH_MODEL.md) for exact current files and tests.
Current PC pack: `models/packs/en.itpack`; phone: `Download/itantra-en.itpack`.
Old English packs were archived under `models/archives/english-retired-2026-09-13`.
The model ID is unchanged, so the user's imported Parakeet does not need reimport.
Base has no models; preloaded now bundles only Parakeet English plus Hindi.
The final APK is installed and hash-verified. The English-only Models UI was
visually checked. Current tests: 55 JVM passes, 0 lint errors/19 warnings; Android
14 passes and 1 Odia desktop-parity failure. English phone checks passed.
Do not describe the full Android suite as passing. Odia model files are unchanged.

## Previous comparison checkpoint (superseded)

Latest update: **0.5.0-english-comparison / code 11**. Read
[ENGLISH_COMPARISON.md](docs/ENGLISH_COMPARISON.md) before touching English models.
Three English engines are selectable, with per-profile installed pointers and
only one resident recognizer. Parakeet/Moonshine remain separate manual imports.
The updated base app is installed on serial <test-phone>; the new packs are in its
Download folder. Current Zipformer selection and production model data were kept.
The phone is almost full: recommend freeing 1 GB before importing both candidates.
Build: 55 JVM tests, zero lint errors, 19 warnings; both APK flavors compiled.
New pack-registry tests passed on the handset. Read the comparison evidence for
real-engine test outcomes; successful builds alone do not establish accuracy.

The following code-10 notes describe the preceding checkpoint and unchanged UX:

- App: `org.itantra.app`, version `0.4.1-connections-ui`, version code `10`.
- Kotlin/Compose, Java 17, minSdk 26, compileSdk 37, targetSdk 36, arm64-v8a.
- This version is installed on the available RMX1801 phone, serial `<test-phone>`.
  Read-only ADB inspection during this handoff confirmed active English, Hindi
  and Odia model pointers. This inspection was not a new speech test.
- User reports English/Hindi STT and TTS work; English still has some word
  errors. User also reports Odia works and its transcription looks correct.
  These are manual reports, not measured corpus accuracy.
- Talk has peer status, auto-send/auto-play, sent/right and received/left
  bubbles, replay icons, editable draft and compact hold-to-speak. Recording
  never automatically speaks back on the sender. Messages is silent text chat.
- Existing Wi-Fi Direct and Bluetooth Classic remain. The user reports their
  EN/HI two-phone trials worked. BLE is still discovery only, not transport.
- Latest UX: bottom tab renamed Connections, with prominent One-to-one / Groups
  sections. Groups shows only Same Wi-Fi / Phone Hotspot, with Create/Find
  together and name/password fields in a dialog. All conversation behavior stays.
- Group support: separate Same Wi-Fi / Phone Hotspot setup; named/password groups,
  creator-code confirmation over TLS, member rosters, five-second discovery,
  per-recipient ACK/retry, group Talk and silent Group/Direct message threads.
  The creator relays text and can read DMs. See [LOCAL_GROUPS.md](docs/LOCAL_GROUPS.md).
- Imports still show percentage/verification stages and separate engine loading.
- Latest recorded build: both debug APKs and the instrumentation APK compiled;
  52 JVM tests passed, lint had 0 errors and 19 warnings. Eight Android tests
  passed: real local TLS/Room multi-session routing, Wi-Fi-interface discovery,
  password/pinning/consent, legacy persistence/protocol and native synthesis.
  This is ONE physical phone, not new multi-phone group acceptance.
- Odia conversion is DONE. The original `.nemo` is still not an Android model;
  its derived INT8 ONNX pack is available. Do not repeat conversion unnecessarily.

## APKs and language packs

- Base: `D:\SIH\app\build\outputs\apk\base\debug\app-base-debug.apk`.
- English/Hindi preloaded: `D:\SIH\app\build\outputs\apk\demoPreloaded\debug\app-demoPreloaded-debug.apk`.
- All packs: `D:\SIH\models\packs\`.
- Use `en.itpack` for current Parakeet English. Old English distributions are archived.
- Odia: `or-experimental.itpack`; the phone download copy was named
  `/sdcard/Download/itantra-or-experimental.itpack`.

Base contains no ASR packs; only the special preloaded flavor contains English
and Hindi. Do not put every language in either APK.

## Important unfinished items

Next check the new LAN group on two physical phones; use three for private DM
isolation and simultaneous-speaker audio queue checks. Test router and hotspot
owner/client hosting separately; AP isolation can block clients. Legacy two-phone
user reports do not automatically validate this new feature. Hands-free Silero
VAD is not integrated. The automated Odia Android
parity test now runs but fails on its second synthetic desktop comparison. Native-speaker
WER, listening scores and full ten-language acceptance remain unmeasured.

The user asked to remove the Odia experimental label in an answer-only exchange;
no label/lock change was made. It remains experimental under the PRD acceptance
rules. The user's preference to prioritize a working demo did not create test
results or formally amend the PRD. Discuss that distinction if revisiting it.

## Preserve the working folder

Last recorded Git commit: `909a6e8` (Phase 0). Substantial later implementation
is modified or untracked. The current working tree, not that commit alone,
contains the latest app. Do not reset, clean, discard or blindly stage it.

This session implemented/build-tested the LAN extension and updated the phone;
no Git commit, push or remote backup was created. APKs, packs, sources, AAR and tools are ignored by Git;
back them up separately when arranging a backup. Never commit large binaries.
Use `adb install -r` for updates; uninstall/clear-data can erase imported packs
and message history.

## Prompt for a future session

> Continue the existing iTantra project in D:\SIH. Read CONTINUE_HERE.md,
> ITANTRA_PRD.md, models/README.md, models/models.lock.json,
> docs/PROJECT_HANDOFF.md and DECISIONS.md completely before editing. Preserve
> the dirty working tree and separately installed language packs. Do not
> re-scaffold or redo the English replacement/Odia conversion without a reason.
> Latest saved version is 0.5.1-english. Also read docs/ENGLISH_MODEL.md and docs/LOCAL_GROUPS.md.
> Separate manual user reports,
> host checks and actual device acceptance; never invent successful tests.
> My next task is: [describe what you want next].
