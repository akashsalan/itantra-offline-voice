# Implementation decisions

## 24 September 2026 — emergency alerting and SOS layout (code 24)

An incoming emergency now runs an attention stage before it is read aloud:
`EmergencyAlarm` vibrates on a waveform pattern with `USAGE_ALARM` and sounds
repeated `ToneGenerator` bursts on `STREAM_ALARM` for 3.5 seconds, then falls
silent so the tone never talks over the words. It is bounded and cancellable, and
every platform call is wrapped — a missing vibrator or a denied tone generator
must not stop the message itself from being spoken, because the speech is the part
that carries the information. `interruptAudio()` cancels it alongside the player.

The emergency notification channel now vibrates, bypasses Do Not Disturb, shows on
the lock screen, and carries `CATEGORY_ALARM` with a full-screen intent so Android
is asked to wake the screen. The channel's own sound stays null for the same
reason as above. `USE_FULL_SCREEN_INTENT` is declared, but nothing depends on it:
Android 14 may still require the user to allow it, in which case the alert degrades
to a heads-up notification. Channel settings only apply on first creation, so
installs that already have the channel keep their existing behaviour — acceptable
because the app's own alarm, not the channel, does the work.

SOS layout. The docked action block used to be pinned and took roughly half the
screen. The record button, "Type it", "Quick pick" and "Review and send" now sit at
the top of the scrolling content, with the explanation below them. The only pinned
element left is the cancel window, shown for the three seconds before transmission
and while queueing, so a user who has scrolled away can still stop it. Nothing is
pinned at rest.

The two routes promise different things, so they read and animate differently.
Connected SOS says "Alert your team" and confirms with a settled ring that pulses
in place; public BLE SOS says "Alert anyone nearby" and confirms with rings
travelling outward, one per possible hop. Both collapse to a still frame under
reduced motion.

Startup no longer begins on a language with no recogniser when the phone has one
installed. Booting to English unconditionally left push-to-talk disabled with no
visible cause on a phone that had only, say, Bengali imported. Receiving was never
affected; this only chooses the language the microphone starts on, and it still
falls back to English when nothing is installed.

## 24 September 2026 — optional neural voices (code 23)

User approved adding neural TTS while keeping embedded eSpeak NG as the fallback.
eSpeak stays bundled for all ten languages, remains the default, and remains the
emergency voice. Neural voices are per-language optional packs: the base flavour
bundles none, preloaded bundles English and Hindi beside their existing
recognisers, and every other language imports from local storage in one tap.

Selected AI4Bharat Indic-TTS (FastPitch + HiFi-GAN V1, 22 050 Hz, MIT). The
acoustic model is EchoBharat's published int8 conversion at revision
`2ad314c3ecafeb252bac82785ac541325596ac9c`; the **vocoder is exported here as
float32** rather than reusing the published int8 file. Measured, not assumed:
dynamic int8 turns every convolution into `DynamicQuantizeLinear` + `ConvInteger`
and measured RTF 2.91 against 0.223 for the identical float32 graph — about 13x
slower for 32 MiB less. Full Hindi pipeline moved from RTF 4.42 to 0.447. The
acoustic model stays int8 because it already measures RTF 0.25.

Rejected: `ai4bharat/vits_rasa_13` (no Hindi, English, Gujarati or Odia; gated
upstream licence; MNN format needs a new runtime), `facebook/mms-tts` (CC-BY-NC,
not redistributable here), Piper (no Indic voices in its catalogue), IIT-M
FastSpeech2_HS (CC BY 4.0 and covers Odia, retained as the licensed fallback, but
needs a Kotlin phoneme front-end port and a second acoustic adapter).

No second ONNX Runtime is packaged. `libonnxruntime.so` 1.28.2 already ships in
the sherpa-onnx AAR and exports the stable `OrtGetApiBase` C ABI, so the new
`itantra_tts` bridge resolves it with `dlopen`. Never combine sherpa's, Moonshine's
and this runtime with `pickFirst`; the bridge adds no library of its own beyond
about 845 KB. It requests a conservative API version and null-checks it so a
future sherpa upgrade degrades to eSpeak instead of aborting. The bridge only
opens sessions and runs one tensor in and out: tokenisation, chunking and all
policy stay in Kotlin because a native abort cannot be caught.

Rules that must not regress. With no pack installed the router is a strict
pass-through to eSpeak. Emergency alerts never trigger a cold model load; they use
a resident voice or eSpeak, because alert playback must stay prompt. At most one
neural voice is resident and switching language releases the previous pair. A
neural failure degrades that language to eSpeak, reports once, and is not retried
until selection changes — a message is never lost to a voice fault. Voice pointers
live in their own `voices` namespace so installing or deleting a voice cannot
disturb an imported speech-recognition model or change the selected speech
language.

`।` and `॥` are absent from every shipped token table and were being dropped
silently, losing sentence-final prosody; they are folded to `.` before
tokenisation only. Stored and transmitted text is never altered.

Odia ships no neural voice in this release and always uses eSpeak; `or.zip` exists
upstream so an export remains possible. Telugu and Malayalam shared one
byte-identical vocoder in the int8 export, which exporting each language's own
float32 vocoder resolves. Voice quality is not evaluated and deferred milestone 4
is unchanged; host RTF is not Android RTF and no phone test is implied. The
AI4Bharat MIT claim is asserted by EchoBharat's LICENSE while the upstream GitHub
repository has no licence file — verify at the release before distributing.
See [docs/NEURAL_TTS.md](docs/NEURAL_TTS.md).

## 22 September 2026 — public BLE SOS opt-in (code 22)

User authorized a BLE Relay SOS tab inside Emergency SOS, not Connections. No
team code or pairing is required for this public route. Turning receiving on also
opts into automatic forwarding: A -> B -> C -> D is three radio hops; D receives
but does not forward the original again. Forwarding does not wait for a human
acknowledgement and does not need STT, microphone permission, Wi-Fi or internet.

Public and trusted-team relays have separate service/mailbox UUIDs, wire/signing
domains, identities, queues and Room namespaces. The private format/keys are
retained. Never mirror a connected/private alert into public BLE. One BLE relay
mode runs at a time. The private setup remains available inside Connected SOS.

Public traffic is signed, not encrypted; signatures do not verify a person or
the truth of their alert. Signed five-minute expiry (correct phone clocks needed),
bounded duplicate/rate/retry/peer queues and three cooperative radio hops are
implemented. A malicious modified app can ignore forwarding limits; this is not
a secure public-safety network or Bluetooth SIG Mesh. Receipts identify devices,
not verified rescuers. No guarantee of reach, delivery, battery life or help.

Receiving is explicit, foreground-service-backed and limited to 30 minutes per
activation. Stop/process death cancels pending public forwarding; never restart
consent on boot. History remains, but old public alerts do not automatically
replay/rebroadcast on reactivation. New public alerts speak once and can be
stopped. Keep the existing hands-free capture and private/conversation behavior.
See [public SOS design and physical test checklist](docs/PUBLIC_BLE_SOS.md).

## 19 September 2026 — foreground-service lifecycle repair (code 21)

User explicitly requested fixing the reported microphone service failure, rebuilding
both APKs and updating the phone. Keep the service through final recognition/native
cleanup, distinguish expected instance teardown from actual loss, and await foreground
promotion before microphone/playback. Capability upgrades need their own readiness
acknowledgement. Preserve the first real Android error and permit a new explicit retry;
never silently restart microphone consent. Speech/model payloads stay unchanged.
Track visible Activity owners independently. Manual PTT/SOS must not be rejected
by a stale UI visibility flag; Android permissions and foreground eligibility
remain enforced. This supersedes an intermediate code-20 visibility guard.
See [the implementation/diagnosis](docs/FOREGROUND_SERVICE_FIX.md).

## 19 September 2026 — call-style hands-free UI (code 19)

User authorized improving the Talk tab so a single switch transforms PTT into
a call-like experience. Keep selecting the view separate from explicitly starting
capture/automatic sending. Use real local pipeline states and message history;
do not imply ringing, full duplex, a shared floor lock or a peer's microphone state.
Preserve existing drafts and route their review through PTT/SOS. Disable language
changes for the whole active hands-free session, including mute. End stays available
on the call view, transcript sheet and return banner on other tabs. It does not
disconnect or override emergency playback. No model/runtime/transport change or
milestone-4 quality evaluation is included. See [the UI handoff](docs/HANDS_FREE_UI.md).

## 19 September 2026 — requirements hardening (code 18 preview)

User authorized the six-milestone fix plan, explicitly deferring milestone 4
(native-speaker STT/TTS quality study, including model comparison/Odia acceptance).
Keep eSpeak, both selected English profiles and all language-pack bytes unchanged.
Implement the PRD's explicit hands-free **turn-taking**, not simultaneous calling.
Never restore an active microphone session after process restart. Recipient changes,
mute/end and foreground-access failure invalidate automatic-send consent; check it
again inside the persistent outbox insertion boundary, after dispatcher/lock waits.
Already queued messages keep their original recipients and are not recalled.

This approval supersedes the earlier group rule that alerts wait for normal speech:
incoming emergencies preempt ordinary audio/capture on every route, at a safe native
operation boundary. Ordinary stop/auto-play controls do not stop an incoming alert;
human acknowledgement and explicit disconnect remain available. Audio focus is
respected; failed alert playback gets at most three delayed retries, then requires
explicit replay. Maximum alarm volume remains opt-in and its incomplete setup is
visible. Android control, hardware and DND limits still apply.

Code 18 also fixes Wi-Fi Direct callback permission handling, prewarms embedded
TTS without producing audio, and adds clearly defined idle CPU/storage observations.
No dependency, model weights, wire schema or Room schema changes. Diagnostics JSON
is schema 2 (adds CPU windows, capture mode and installed model storage).
The complete corresponding-source release and physical-device acceptance are still
gates, not inferred from builds. See docs/REQUIREMENTS_2026_09.md and
docs/RELEASE_CHECKLIST.md. Baseline source/APKs are recoverable at
`.tools/rollback-backups/requirements-20260919`; no reset, cleanup or commit occurred.

## 17 September 2026 — add English low-end devices (code 17)

User approved adding quantized Moonshine Tiny Streaming beside Small Streaming,
not replacing it. Label the additional choice "English — low-end devices".
Both remain language `en` for text, transport and eSpeak. Keep one recognizer
resident, preserve the selected English profile across restart, and bundle Small,
Tiny and existing Hindi only in preloaded. Base supports offline imports but
bundles no ASR weights. No new runtime/dependency, no pipeline or protocol changes.
Retain accuracy/low-end performance caveats. Record focused verification separately.
Baseline saved under `.tools/rollback-backups/moonshine-tiny-20260917-2037`.
Implemented in `0.6.3-english-options`: 24 focused JVM checks and three selected
Android checks passed (one initial OEM-killed test retry is documented).
Both APKs built; final preloaded installed and hash-verified on RMX1801 with
`install -r`, using a temporary base update to work around low storage.
Both English choices loaded through the UI; original Small selection restored.
Existing Hindi/Odia pointers and message history retained. See
[implementation](docs/ENGLISH_LOW_END.md) and [checks](docs/results/english-options-checks.md).

## 17 September 2026 — Moonshine Small Streaming English

The user explicitly selected Moonshine Small Streaming after the licensing
review. Replace only the preferred English STT model with the English Small
Streaming quantized 2026-08-21 release (MIT), not the older Moonshine Base export.
Integrate a pinned Moonshine runtime behind the existing recognizer interface;
retain sherpa-onnx 1.13.8 for Indic STT and Silero VAD and retain embedded eSpeak NG.
PTT remains the sole microphone owner and Silero/manual release/15-second maximum
remain the only app utterance endpoints. No idle listening or hosted inference.

Preserve installed Parakeet files and activation provenance for recovery. A new
pack must be verified before activation, and a failed model load must leave a
recoverable previous installation. Base stays free of ASR weights; preloaded
contains only the new English pack and unchanged Hindi. No protocol, database,
group, SOS, accessibility or unrelated UI changes are authorized by this switch.

Baseline source, documents and both existing APKs were copied to
`.tools/rollback-backups/moonshine-small-20260917` before changes. Exact model and
runtime hashes, isolated native-library packaging, recovery and focused checks
are recorded in `docs/MOONSHINE_SMALL_STREAMING.md`. Both APKs compile; 22 focused
JVM tests and three small Android checks passed. Code 16 preloaded is installed,
with the new English active and existing Hindi/Odia/history retained. This does
not assert better accuracy, lower RAM or SIH organizer approval. The handset
fixture RTF was about 1.4, not faster than real time. Keep validation limitations.

## 17 September 2026 — approved field-preview changes

- Material 3 guided Connections, separate More destinations and restrained,
  reducible motion; native-script names and large PTT controls.
- Wi-Fi Direct group formation reuses existing LAN TLS/admission/messages;
  existing one-to-one transports and wire protocols are unchanged.
- SOS now opens a dedicated voice-first page with original radio artwork.
  An intentional emergency PTT → offline STT → 3-second cancel window sends
  priority text to the selected route. Presets/typing require explicit review.
  Normal Auto-send does not control SOS. Its separate draft preserves Talk.
  See [sender/receiver flow and honest reach limits](docs/SOS_FLOW.md).
- BLE emergency text is a separate opt-in trusted-team experiment, not automatic
  fallback or Bluetooth SIG Mesh. See [bounds and physical-test gate](docs/BLE_EMERGENCY_RELAY.md).
- Model catalogue uses locally pinned archive hashes and the existing safe
  importer. No release URL is enabled until an approved host exists. No heavy
  model or new dependency was added. [Download/deletion design](docs/MODEL_CATALOGUE.md).
- Database schema and imported-model pointers are retained; no data clearing or
  model replacement is part of the update.

The implementation baseline is ITANTRA_PRD.md. Model deviations are recorded
below; language codes, embedded TTS and wire protocol selections are unchanged.

## 2026-09-13 - Parakeet becomes English (0.5.1 / code 12)

After manually importing and trying Parakeet 110M, the user explicitly selected
it as the sole English STT model and requested removal of other English packs
and the comparison names. This supersedes the three-choice decision below.
The UI calls it English; diagnostics and its immutable pack ID retain Parakeet
provenance. The canonical distribution file is now models/packs/en.itpack.
The optional preloaded flavor contains this English pack and unchanged Hindi.
Base still contains no ASR. English TTS remains embedded eSpeak, unchanged.

Remove the English comparison UI and reject retired English imports. Retain
their pinned provenance and adapters for audit/history, not as app choices.
Archive superseded PC packs under models/archives/english-retired-2026-09-13
before removing duplicate/retired phone downloads and their installed English
model directories. Verify the selected, working Parakeet files before cleanup;
never clear app data or remove Hindi/Odia/other-language files. The previous
English pack IDs remain parseable for safe migration; an old base installation
must import the new English pack before speaking, and preloaded installs it.

The user's favorable report is not corpus WER or universal accuracy acceptance.
The preceding phone fixed-fixture run passed for all three engines. Preserve
that historical evidence without keeping alternative packs in the active list.

## 2026-09-13 - Three selectable English ASR packs (0.5.0 / code 11; superseded)

The user requested Parakeet 110M CTC INT8 and English Moonshine Base alongside
the current Zipformer for manual comparison. Keep Zipformer as the existing
default; importing a pack may activate it, and each installed English pack must
remain selectable without reimport. Keep one recognizer resident at a time.
Use the supplied sherpa-onnx 1.13.8 AAR, existing NeMo CTC adapter for Parakeet,
and a new offline Moonshine encoder/merged-decoder adapter. Resolve engines by
pinned pack profile, never by language alone. Preserve legacy activation pointers.

Moonshine selection is specifically sherpa-onnx-moonshine-base-en-quantized-
2026-02-27 (English Base, MIT), not the newer Tiny/Small/Medium streaming family.
Its publisher revision is 8f4d6c58c03d40bcea40043bb7120a878f2bbef6. Retain the
publisher licence: its introductory English MIT exception is applicable, not
the non-English Community terms. Parakeet weights are CC-BY-4.0 (NVIDIA/Suno),
converted to CTC INT8 by sherpa-onnx. Do not use TDT benchmarks as CTC results.
These are comparison candidates, not measured accuracy upgrades.

Keep both candidates in separate .itpack files, never in either APK flavor.
The demoPreloaded flavor remains Zipformer/Hindi only and must not overwrite a
user-selected alternative English pack at startup. Receiving/TTS, all other
languages, radio protocols and conversation behavior remain unchanged. Expose
the selected English engine on Talk and in diagnostics. Models owns installation
and switching controls. Do not automatically send/replay speech while switching.

Sources:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-ctc/nemo/english.html
https://huggingface.co/nvidia/parakeet-tdt_ctc-110m
https://k2-fsa.github.io/sherpa/onnx/moonshine/models-v2.html
https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-en-quantized-2026-02-27/tree/8f4d6c58c03d40bcea40043bb7120a878f2bbef6

Build, integrity and phone smoke results are recorded separately after execution.
No microphone WER, accent/noise reliability or Android performance is presumed.

## 2026-09-13 - Connections information hierarchy (0.4.1 / code 10)

The user approved the working UI but found Groups too deeply nested. Rename the
bottom Devices destination to Connections and expose One-to-one / Groups first.
Groups shows only Same Wi-Fi and Phone Hotspot, with explicit shared-network
requirements. The Groups section is visible without Wi-Fi, while create/join
actions still require a detected local network. Selecting Groups from an idle
legacy mode selects Same Wi-Fi; active conversations cannot be silently replaced.
Keep Create and Find visible together, putting name/password fields in a focused
creation dialog. Keep all four methods under One-to-one. Talk, Messages, models,
protocols and delivery behavior are unchanged; only navigation and help text
change. Update errors and setup guidance consistently to say Connections.

## 2026-09-13 - Named local-network groups and direct text messages

- Implemented as `0.4.0-local-groups`, code 9; operation and evidence are in
  [docs/LOCAL_GROUPS.md](docs/LOCAL_GROUPS.md). This extends the baseline PRD with
  the user's approved group/DM scope; it does not mark all PRD gates complete.
- User reports successful two-phone Hindi/English and Bluetooth Classic trials.
  This supersedes the one-phone-only development status, not corpus WER or a
  formal radio benchmark. Only RMX1801 is currently attached to this workstation.
- User approved separate Same Wi-Fi and Phone Hotspot setup options, named and
  password-protected groups, and group/direct text conversations. Any reachable
  member of the shared network may host; the hotspot owner need not be admin.
- Add a separate LAN session layer and additive protobuf schema. Preserve v1
  Wi-Fi Direct/Classic framing, engines, language codes and existing history.
  Groups initially support eight concurrent devices including the creator; this
  is a software cap, not a measured platform capacity. One active room per phone.
- LAN discovery exposes only bounded room/name/count metadata, using NSD and
  local-subnet UDP discovery as a hotspot fallback. Reconcile presence every
  five seconds while browsing. Never broadcast passwords or conversation text.
  Restrict LAN endpoints to on-link Wi-Fi/hotspot IPv4 addresses; no WAN service.
- Named groups use password admission over TLS, with a host-certificate code
  checked by the joining user before sending credentials. Client certificate
  identities bind members and DM destinations. Use Android's TLS/KeyStore and
  PBKDF2, not custom ciphers. TLS 1.3 is preferred where available; TLS 1.2 with
  ECDHE/AEAD is retained for the required Android 8/9 baseline. This is an explicit
  compatibility extension to the PRD's suggested TLS 1.3 option, not a new SDK.
- The creator relays text; DMs are delivered only to their intended member but
  are not encrypted against the relay host. The UI must explain this. Same-Wi-Fi
  one-to-one invitations require host approval instead of a group password.
- Persist original recipient sets and distinct delivered/played/human ACKs.
  New joiners do not receive earlier messages. Retrying never changes audience.
  Group sender attribution comes from the authenticated connection, not a
  client-supplied display name. No automatic host migration in this release.
- Talk remains group-targeted when a DM is selected in Messages. Group Talk has
  sender names, right/left bubbles, language-matched TTS and serial playback.
  Per the user's refinement, alerts advance ahead of waiting normal messages;
  they do not overlap or cut off currently playing group speech.
- Add a non-destructive Room migration for room/recipient/receipt metadata.
  Group name/password changes do not alter ASR packs or the base APK policy.
- This entry records the approved implementation scope, not successful tests.
  Record actual build, unit/instrumentation and multi-phone outcomes separately.

Android testing found and fixed a TLS KeyStore authorization defect: Conscrypt
passes an already hashed TLS transcript to its raw ECDSA signer. Generated keys
must authorize `DIGEST_NONE` in addition to SHA-256/384/512, as Android documents
for TLS keys. This does not enable plaintext or remove TLS transcript hashing.
Use identity alias `itantra-lan-identity-v2`; do not reuse the unreleased, unusable
v1 test identity or silently delete its data. Test identities are uniquely named
and removed by their own fixtures. The fixed TLS test checks mutual certificate
identity, rejection of a wrong host pin, and explicitly negotiated TLS 1.2.
See [Android KeyGenParameterSpec.Builder](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder#setDigests(java.lang.String...)).

Room is now v3 (non-destructive v1 -> v2 -> v3 migrations). Legacy session
recovery ignores LAN rows. Group rows keep the original audience, recipient
receipt sets and a deduplicated host relay record. Rejoining restores eligible
failed sends without adding newly joined people to their original audience.
Host-readable relay records are hidden from ordinary conversation UI, not
cryptographically hidden from the creator. LAN uses TCP 38774 and UDP 38775;
legacy Wi-Fi Direct stays on 38773. No new speech model or library was selected.

The existing serial audio owner remains the only TTS player. A tested queue
selects incoming voice or explicit replay, never silent chat or sender auto-play.
A group alert can cancel microphone capture and move ahead of waiting messages,
but does not cut off speech already playing. Legacy emergency behavior remains.

Known limits: IPv4 local networks only; router/hotspot isolation may prevent
client-to-client access. No host migration, IPv6-only discovery, membership
revocation UI, or host-blind DM encryption is claimed. The custom trust-manager
lint warning is retained; functional tests are not an independent security audit.

References: Android NSD/local-only hotspot, Android KeyStore and TLS guides.
https://developer.android.com/develop/connectivity/wifi/use-nsd
https://developer.android.com/develop/connectivity/wifi/localonlyhotspot
https://developer.android.com/privacy-and-security/security-ssl

## 2026-09-12 - Latest: Odia conversion and import progress (0.3.4)

This entry supersedes historical claims below that Odia is unavailable, Phase 1
is unimplemented or no phone is connected. See docs/PROJECT_HANDOFF.md for the
current checkpoint; retain earlier entries as the development history.

- Converted the supplied Odia checkpoint using the PRD-selected CTC/masked
  MatMul-only INT8 pipeline. No replacement model, cloud speech API or alternate
  runtime was introduced. The original `.nemo` remains source-only; the derived
  model is isolated under models/source/asr/or-ctc-experimental/ and packaged as
  models/packs/or-experimental.itpack, ID
  asr.indicconformer.or.ctc.experimental.v1. It is never bundled in either APK.
- Source and FP32 tokens matched on two synthetic host fixtures; quantized
  differences are retained in docs/results/odia-export-host.json. Native-speaker
  WER is unset. This is not full equivalence or Android performance acceptance.
  Export provenance and compatibility adaptations are in docs/ODIA_EXPORT.md.
- User reports Odia works and its transcript looks correct. The dedicated
  instrumented Odia comparison test compiled but has no captured completed run.
  Preserve this distinction; the user report is not a scored corpus.
- The request to remove the experimental label came in an answer-only exchange.
  No label, manifest validation flag or lock status was changed. PRD acceptance
  is still incomplete; any later release-label decision must be documented.
- Version 0.3.4-import-progress, code 8, adds real byte-based pack import progress
  for both manual and starter imports. Copying plus two verification reads feed
  progress; cap at 99% before atomic activation and show 100% after installation.
  Engine loading remains a separate stage. Do not report an invented ETA or
  imply that file-copy completion guarantees recognizer readiness.
- Preserve the allowlisted manifest, size/hash checks, safe extraction and atomic
  active-pointer behavior. Progress callbacks do not weaken validation. The
  modal UI stays visible during import/loading and presents failures explicitly.
- Latest saved host verification: 36 JVM tests pass, 0 lint errors/17 warnings,
  base/preloaded/test APKs compile. Earlier four-test phone run covers native
  synthesis, Room and an in-memory protocol link. Two-phone radio testing and a
  completed visual progress-dialog check remain unrecorded.
- During the documentation handoff, ADB confirmed code 8 installed on RMX1801
  serial <test-phone>, with active English/Hindi/Odia pointers. No new speech test,
  install, model mutation, Git commit or remote backup was performed by that
  documentation task. Preserve the substantial uncommitted implementation.

## 2026-09-12 - Two-phone application and Bluetooth implementation

### Talk versus text chat (user-requested UX refinement)

- Final Talk layout keeps controls fixed above a separately scrolling, auto-
  scrolling conversation. Sent bubbles align right; received bubbles align left.
  Both directions have explicit replay controls (local synthesis from text).
  This does not restore automatic sender-side TTS. Drafts are editable bubbles
  before manual sending; the 15-second PTT control is a compact rectangle.

- Recording never triggers local TTS. Talk defaults to review-then-send, with
  persistent auto-send and automatic receiver-playback switches on Talk itself.
- Messages is text-only chat, with its own draft and no microphone controls.
  Add TextOptionsBody.silent_chat in the existing Envelope.body, a negotiated
  supports_text_chat capability and protocol minor version 1. Envelope fields,
  message types, language IDs, CRC and framing remain unchanged. Do not send
  silent chat to older peers that cannot honor the capability.
- Room v1 to v2 adds a VOICE/CHAT channel using a non-destructive migration.
  Existing messages remain VOICE. CHAT is persisted/acknowledged, never spoken.
  Emergency ALERT messages remain voice-mode and cannot be marked silent chat.
- User currently has one phone: build/install and one-device checks are in scope;
  physical two-phone radio acceptance is explicitly deferred.

- This supersedes the historical Phase 0 status below. Wi-Fi Direct TCP,
  protobuf framing, Room outbox/deduplication, three acknowledgement states,
  receiver TTS, emergency priority and model-manager screens now exist.
  Three on-device core/native tests passed (android-phase1-core-tests.txt).
  Their in-memory link does not establish physical radio reliability.
- Secure Bluetooth Classic RFCOMM uses application UUID
  86b64920-28b8-4db5-8c15-2c93d279bc61. Both phones listen; initiate from ONE
  phone, accept Android pairing, then confirm the app code on both.
  No insecure RFCOMM or hidden API fallback is permitted.
- BLE is iTantra presence/discovery per FR-09, UUID
  d691c0b2-18d4-42db-93e8-a287ac6aa6f4. A BLE address is never assumed to
  be a Classic address. Select the phone in Classic discovery to connect.
  Advertisements contain only an abbreviated Bluetooth device name, not
  messages, transcripts, stable app identity or model data.
- Message transports share the unchanged framed protobuf and RadioSession.
  Switching establishes a fresh session with matching-code consent. Outgoing
  messages remain bound to their original application peer identity.
- The small base APK has no ASR packs. The separate demoPreloaded variant
  includes only English v2 and Hindi, verified/imported at first launch.
  Other languages remain importable; Odia remains unavailable.
- Real two-device radio acceptance is still required. No radio success,
  BLE range/throughput or speech accuracy is implied by compilation.

References:
https://developer.android.com/develop/connectivity/bluetooth/connect-bluetooth-devices
https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
https://developer.android.com/develop/connectivity/bluetooth/ble/find-ble-devices

## 2026-09-12 - English-only replacement for device trial

- User reports Hindi STT and TTS work on the phone, but English recognition is
  unsatisfactory. This is user-reported manual evidence, not a measured WER/MOS.
- The small English model drops opening words in both bundled human fixtures on
  sherpa-onnx 1.13.8 Windows. Full-precision weights, bulk audio feeding, automatic
  model detection and additional tail padding do not fix this. Leading silence
  and beam search are not adopted: their results are inconsistent.
- Introduce a separately importable English Zipformer 2023-06-21 INT8 encoder /
  joiner plus FP32 decoder, from the same sherpa online-transducer family.
  This replaces the PRD's 20M default for the next English device trial, at the
  user's request to change English STT. Hindi, microphone capture, eSpeak and
  the local 1.13.8 Android AAR are unchanged.
- Source: csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-21, Hugging Face
  revision 9a65b6ea94c311ca770c2bf895b30f456a22d703. Model card declares
  Apache-2.0; trained from LibriSpeech + GigaSpeech according to the publisher.
  All model LFS SHA-256 hashes were verified. Payload is 190,180,941 bytes,
  versus 45,202,074 bytes for the original English files.
- Same-input host comparison restored the opening words in both human fixtures.
  Synthetic eSpeak phrases still have severe errors. These are two fixed human
  recordings, not corpus WER, accent/noise acceptance or Android performance.
  The second recording exceeds the app's 15-second microphone limit and tests
  the adapter only. See docs/results/english-stt-*-audit.json.
- Increase English tail context from 0.3 to 0.66 seconds, following the pinned
  upstream recipe. Padding is excluded from recorded audio duration/RTF's
  denominator. No artificial leading silence, hotword bias or text correction.
- Preserve old model binaries and pack imports for rollback. New imports use
  a versioned, allowlisted pack identity. Existing UUID-only activation pointers
  continue to resolve the original English/Hindi packs.
- Device trial remains required: the phone is currently disconnected. A previous
  native eSpeak instrumentation smoke test passed on RMX1801/API 29; its log is
  docs/results/android-tts-smoke.txt. That does not validate the replacement ASR.
- Wi-Fi Direct and reliable two-phone messaging are not implemented. The current
  scope is the English repair; Phase 1 remains outstanding.

References:
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html
https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/python-api-examples/online-decode-files.py

## 2026-09-12 — Initial build

- Kotlin, Compose Material 3, Java 17, minSdk 26, compileSdk 37, targetSdk 36.
- First APK contains arm64-v8a native libraries only. ASR models are separate,
  checksum-verified `.itpack` imports, never bundled in the base APK.
- Start with app, speech, model-management and transport package boundaries;
  avoid unnecessary Gradle module splitting during the first feasibility build.
- Use the supplied sherpa-onnx 1.13.8 AAR. All 16 artifact sizes and SHA-256
  values matched models/models.lock.json during the initial local audit.
- English uses the exact INT8 encoder/joiner and FP32 decoder in the lock.
  Hindi uses the supplied IndicConformer CTC model and shared vocabulary.
  Neither is device-validated yet. Odia remains unavailable: its `.nemo`
  source requires conversion and acceptance testing.
- Embed the supplied eSpeak NG source through NDK/JNI; generate its voice data
  with a host build of the same source. Do not use Android system TTS.
- Phase 0 acceptance requires physical-device speech and timing evidence.
  Compilation and host tests do not satisfy that gate.

## Build choices and measured scope

- AGP 9.1.1 / Gradle 9.3.1 support the requested Java 17/API 37 baseline.
  Reference: https://developer.android.com/build/releases/agp-9-1-0-release-notes
  Compose compiler 2.2.10 follows AGP's built-in Kotlin baseline. Runtime
  dependencies are pinned in app/gradle.lockfile.
- Google's API 37 package is named android-37.0. Its downloaded revision 2
  archive matched the repository SHA-1 ed8ebf7f8822a4de5686d427f237d2fa30ff7410.
  The app still declares compileSdk 37 and targetSdk 36 exactly as requested.
- The supplied eSpeak source remains unchanged. Build synchronous retrieval
  (AUDIO_OUTPUT_SYNCHRONOUS), with callback PCM, and disable native device
  output, async support, MBROLA and optional Sonic. These build flags retain
  the selected eSpeak engine and allow direct AudioTrack playback without
  additional installed apps or native dependency downloads.
- Bundle the ten required dictionaries plus shared phoneme/voice data.
  Other acquired ASR models and all generated packs remain outside the APK.
- Phase 0 captures up to 15 seconds then performs recognition off the UI
  thread. English uses the selected online transducer adapter with explicit
  right-context padding; live partial decoding during capture is deferred.
- Report AudioTrack submission timing, not an unmeasured acoustic onset.
  Process CPU is an elapsed CPU delta, not an invented CPU percentage.
- Original iTantra application code is GPL-3.0-or-later. Upstream sources
  retain their own licenses. Release compliance and model-license inventory
  remain open gates, as do source acquisition revision gaps.
- The first Android debug build and five JVM unit tests passed. A phone was
  not attached; no Android speech, intelligibility or benchmark acceptance
  is claimed. Phase 1 radio implementation follows this physical gate.
