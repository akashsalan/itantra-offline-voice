# Product Requirements Document: iTantra

**SIH problem statement:** 26173 — Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for Low-Bitrate Links  
**Document status:** Implementation baseline 1.0  
**Prepared:** 12 September 2026  
**Target:** SIH screening demo first; complete ten-language submission thereafter  
**Product type:** One fully offline Android application installed on both communicating phones

---

## 1. Executive decision

iTantra will be a text-over-radio voice communicator. A sender speaks into Phone A; Phone A converts speech to Unicode text locally; the app sends only the compact text and metadata over Wi-Fi Direct or Bluetooth; Phone B selects the matching local voice automatically and speaks the message. No speech audio and no cloud API are used in the communication loop.

The project is feasible, but the honest status is:

- The complete two-phone offline product is buildable with current open-source components.
- Hindi, Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu and Bengali have community-converted, Android-ready IndicConformer INT8 ONNX packs. English has an official small 20M-parameter streaming Zipformer model.
- Odia has an official open-source AI4Bharat checkpoint, but no selected Android-ready INT8 pack is currently published in the chosen community pack. It must be exported and validated before the team may claim all ten languages.
- eSpeak NG supports all ten required TTS languages in a very small footprint, but it is formant TTS: intelligible and robotic, not neural or natural-sounding. It is the reliable MVP TTS baseline, not the final answer to the “human flow” scoring criterion.
- Android cannot guarantee literally “non-interruptible” playback or override every user/OS safety policy. Emergency playback will therefore be implemented as the strongest standards-compliant, user-authorized best effort and described honestly in the demo.

This PRD deliberately does not use Sarvam or any hosted service. Network access is permitted only for installing model packs before offline deployment. After installation, inference and phone-to-phone communication work without internet.

## 2. Problem in plain language

Voice recordings are large. Text is tiny. In a disaster, remote field operation or weak-link environment, sending an audio recording may be slow or impossible. iTantra changes speech into text before transmission, sends that text over a local radio link, and recreates speech on the receiving phone.

Example:

1. The sender selects Hindi.
2. The sender holds Push to Talk and says, “मुख्य सड़क बंद है।”
3. Offline Hindi ASR produces the same Devanagari text.
4. The app transmits a small UTF-8 message tagged `hi`.
5. The receiver reads `hi`, selects the Hindi eSpeak voice automatically, and speaks the message.

Manual language selection is intentional. Language identification is not required and would consume resources and add a new failure mode.

## 3. Product goals

### 3.1 Primary goals

1. Complete a repeatable, offline, two-phone voice-to-text-to-radio-to-text-to-voice loop.
2. Support the exact required languages: Bengali, English, Gujarati, Hindi, Kannada, Malayalam, Marathi, Odia, Tamil and Telugu.
3. Minimize transmitted data by sending UTF-8 text rather than audio.
4. Run acceptably on 4 GB low-range and 6–8 GB mid-range Android phones.
5. Support both deliberate Push-to-Talk and voice-activated, turn-based hands-free operation.
6. Provide measured evidence for model size, app size, RAM, CPU, word error rate, real-time factor and end-to-end latency.
7. Remain open source and fully offline during inference and communication.

### 3.2 Competition strategy

The screening demo must prioritize a stable Hindi/English end-to-end loop and visible measurement instrumentation. A broken “ten-language” switchboard is worse than a proven two-language loop plus clearly installed additional packs. The final submission, however, is not complete until all ten language paths pass acceptance tests.

The defensible innovation is not merely combining STT and TTS. It is the complete low-bitrate system:

- modular on-device language packs;
- language metadata carried with every message;
- dual radio transport with automatic fallback;
- reliable delivery, deduplication and reconnect behavior;
- emergency priority handling;
- locally measured quality and latency;
- optional USB radio/embedded gateway without changing the speech pipeline.

### 3.3 Non-goals for the first demo

- automatic language detection;
- speech-to-speech translation;
- natural voice cloning;
- sending compressed voice recordings;
- internet chat, accounts, cloud storage or analytics;
- true simultaneous full-duplex calling;
- modifying a phone antenna or claiming illegal/unverified range amplification;
- production integration with an ISRO system that has not been provided to the team.

## 4. Users and core scenarios

### 4.1 Field sender

Selects a known language, connects to a nearby device, holds PTT, speaks a short operational sentence and sees the recognized text before or as it is sent.

### 4.2 Field receiver

Receives a Unicode message, sees its priority and language, hears it spoken automatically in that language and acknowledges it.

### 4.3 Hands-free user

Disables PTT. VAD detects speech, a pause closes the sentence, ASR runs, and the final text is sent. Incoming speech temporarily suspends local listening to avoid the app transcribing its own speaker output.

### 4.4 Team evaluator

Disables internet, connects two phones, sends normal and emergency messages, changes language, inspects transfer size and sees the latency/RTF/RAM/CPU results inside the diagnostic screen.

## 5. Required user experience

### 5.1 First launch and model setup

The app shall:

1. explain that speech stays on-device;
2. request microphone and nearby-device permissions only when required;
3. show installed and available ASR language packs with actual sizes and SHA-256 verification state;
4. allow a pack to be imported from local storage using Android's Storage Access Framework;
5. optionally download a pack when internet is available, but never require internet after installation;
6. include all eSpeak voices in the base app so receiving/TTS works for every supported language immediately;
7. refuse to label an ASR language “ready” until its model, vocabulary and checksum have passed validation.

For a fully disconnected deployment, language packs may be copied by USB and imported as `.itpack` files.

### 5.2 Home/communication screen

The primary screen shall contain:

- connection status and connected peer name;
- transport indicator: Wi-Fi Direct, Bluetooth or USB gateway;
- manually selected sender language;
- PTT/hands-free toggle;
- large hold-to-talk control;
- live/working/final transcript state;
- normal versus emergency priority control;
- last message delivery and acknowledgement status;
- quick access to devices, messages, model manager and diagnostics.

### 5.3 Nearby device connection

The user opens Nearby Devices, starts discovery and selects a peer running iTantra. Both devices display the same short confirmation code/fingerprint before the user confirms pairing. After connection, the apps exchange protocol version, device name, supported transports and installed language capabilities.

Wi-Fi Direct is primary because Android documents that it can connect nearby devices without a router and offers greater range than Bluetooth. Bluetooth Classic RFCOMM is the fallback. The app shall never silently pretend to connect when the radio or required permission is unavailable.

### 5.4 Push-to-Talk flow

1. Holding PTT starts 16 kHz, mono, PCM16 capture.
2. Releasing PTT closes the utterance immediately.
3. The ASR result is normalized to Unicode NFC and displayed.
4. If confidence is unavailable from the selected CTC runtime, the UI must not invent a confidence score.
5. A non-empty final result is transmitted with its explicit language and priority.
6. The receiver displays the text, sends delivery acknowledgement, selects TTS using the packet language and starts playback.
7. An empty or obvious silence result is not sent.

### 5.5 Hands-free flow

1. Silero VAD continuously processes small audio frames while the foreground communication service is active.
2. Speech starts after at least 250 ms of detected voice and includes 200 ms pre-roll.
3. A default 700 ms trailing silence closes a sentence. This threshold is configurable from 500–1,200 ms.
4. A segment is capped at 15 seconds to control RAM and latency.
5. The selected IndicConformer models are utterance/offline CTC models; they decode at the endpoint rather than providing genuine streaming tokens.
6. Incoming TTS suspends capture/VAD until playback ends plus a 250 ms guard interval. This makes the MVP turn-based, not true full duplex.

The phrase “when PTT is off it should work like a phone” is interpreted as automatic, hands-free turn taking. True simultaneous calling needs acoustic echo cancellation and is a later experiment, not an MVP promise.

### 5.6 Incoming emergency alert

An emergency packet shall:

- be visually distinct and placed before normal queued messages;
- use `AudioAttributes.USAGE_ALARM` with speech content type;
- request appropriate audio focus;
- run through an active foreground service;
- play on the alarm stream at a user-authorized emergency volume, restoring the previous setting afterwards;
- repeat until acknowledged or until the configured safety limit is reached;
- create a high-importance notification;
- optionally use Notification Policy/DND access only after explicit user authorization.

The UI and documentation shall say “Emergency Override: best effort under Android policy,” not “impossible to interrupt.” The OS, device administrator, user safety controls, power state and hardware volume behavior can still prevent playback.

## 6. Functional requirements

### FR-01 — Exact language registry

The code shall have one canonical registry; display names must never be used as protocol identifiers.

| Protocol code | Language | ASR baseline | eSpeak voice |
|---|---|---|---|
| `bn` | Bengali | IndicConformer INT8 CTC | `bn` |
| `en` | English | Zipformer streaming transducer 20M | `en` or `en-us` |
| `gu` | Gujarati | IndicConformer INT8 CTC | `gu` |
| `hi` | Hindi | IndicConformer INT8 CTC | `hi` |
| `kn` | Kannada | IndicConformer INT8 CTC | `kn` |
| `ml` | Malayalam | IndicConformer INT8 CTC | `ml` |
| `mr` | Marathi | IndicConformer INT8 CTC | `mr` |
| `or` | Odia | IndicConformer INT8 CTC, team export required | `or` |
| `ta` | Tamil | IndicConformer INT8 CTC | `ta` |
| `te` | Telugu | IndicConformer INT8 CTC | `te` |

### FR-02 — ASR abstraction

Implement:

```kotlin
interface SpeechRecognizerEngine : AutoCloseable {
    suspend fun load(pack: AsrPack): LoadResult
    fun acceptPcm16(samples: ShortArray, sampleRate: Int = 16_000)
    fun partialText(): String?
    suspend fun finish(): RecognitionResult
    fun reset()
}
```

Two adapters are required:

- `SherpaOnlineTransducerEngine` for the English 20M Zipformer;
- `SherpaOfflineNemoCtcEngine` for the eight published Indic packs and the eventual Odia export.

Only one heavy ASR model may be resident at a time. Changing language closes the previous recognizer, performs an off-main-thread load and exposes `Loading`, `Ready` or `Failed` state. Model loading or inference on the UI thread is prohibited.

### FR-03 — Audio capture

- Android `AudioRecord`, 16,000 Hz, mono, PCM16.
- Prefer `VOICE_RECOGNITION` audio source; fall back to `MIC` after a device capability test.
- Ring buffer for pre-roll and frame delivery.
- No permanent storage of raw audio by default.
- A consented diagnostics setting may save test utterances to app-private storage.

### FR-04 — VAD and endpointing

Use sherpa-onnx with the selected `silero_vad.int8.onnx`, which accepts 16 kHz audio. PTT release is always a hard endpoint. In hands-free mode, VAD and the thresholds in section 5.5 determine the endpoint.

### FR-05 — Text normalization

Before transport:

- trim repeated whitespace;
- normalize to Unicode NFC;
- retain native script;
- reject invalid UTF-8 at serialization boundaries;
- preserve punctuation when supplied by ASR or manual correction;
- cap one text message at 2,048 UTF-8 bytes and chunk longer content.

Do not transliterate the native text for transport. TTS preprocessing may expand digits, units and abbreviations using a language-specific local normalizer without changing the displayed/archived message.

### FR-06 — TTS abstraction

Implement:

```kotlin
interface SpeechSynthesizerEngine : AutoCloseable {
    fun supports(language: LanguageCode): Boolean
    suspend fun synthesize(request: TtsRequest): PcmAudio
    fun stop()
}
```

`EspeakNgEngine` shall be linked into the same APK through Android NDK/JNI. It shall not require the user to install or choose a separate system TTS app. Initialize eSpeak in retrieval mode, select the voice by canonical code, pass `espeakCHARS_UTF8`, receive PCM through its synthesis callback and stream PCM to Android `AudioTrack`.

Speech rate and pitch are local settings. The emergency voice defaults to a slightly slower rate for intelligibility. The team must create a small per-language emergency pronunciation test list containing numbers, directions, place names and operational abbreviations.

### FR-07 — Transport abstraction

```kotlin
interface LinkTransport {
    val state: StateFlow<LinkState>
    suspend fun discover(): Flow<Peer>
    suspend fun connect(peer: Peer)
    suspend fun send(frame: ByteArray): SendResult
    fun incoming(): Flow<ByteArray>
    suspend fun disconnect()
}
```

Implementations:

- `WifiDirectTransport` — primary;
- `BluetoothRfcommTransport` — fallback;
- `UsbSerialTransport` — interface and stub in MVP, implementation for optional embedded/radio gateway.

The speech and protocol layers must not depend on a concrete transport.

### FR-08 — Wi-Fi Direct

- Use `WifiP2pManager` discovery/service discovery and standard TCP sockets.
- Advertise an app service such as `_itantra._tcp`.
- Use a fixed application port declared in one configuration constant.
- Handle group-owner/client roles without requiring the user to understand them.
- Reconnect after a temporary drop without losing queued final messages.
- Check Wi-Fi Direct support at runtime; do not assume every Android model implements it correctly.

Required permissions include `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `INTERNET`, `NEARBY_WIFI_DEVICES` on Android 13+, and legacy fine-location declarations/rules for older Android versions. If the app later targets Android 17/API 37, it must also implement the new local-network permission behavior.

### FR-09 — Bluetooth Classic

- Use secure RFCOMM with one documented, app-specific 128-bit UUID.
- One side opens a server socket and advertises; the other discovers/selects and connects.
- Android 12+ runtime permissions: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
- Legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` declarations must be capped at API 30 as documented by Android.
- BLE is not the text data channel for MVP; it can be used later for discovery only.

### FR-10 — Session and capabilities

Immediately after connection, each peer sends `HELLO` and `CAPABILITIES`. Capabilities include app/protocol version, installed ASR languages, available TTS languages, supported transport features and maximum packet size. A mismatched protocol major version must fail clearly. Missing receiver ASR is irrelevant to playback; missing receiver TTS must show text and a clear error rather than drop the message.

### FR-11 — Reliable message delivery

- Every final message has a unique 64-bit message ID and monotonically increasing sequence number.
- Receiver persists the message before sending `ACK_DELIVERED`.
- Sender retries after 1, 2 and 4 seconds, maximum three retries.
- Receiver deduplicates repeated message IDs.
- Unsent messages remain in a Room-backed queue through process death.
- `ACK_PLAYED` and explicit human `ACKNOWLEDGED` are separate states.
- Ordering is preserved within a session.

### FR-12 — Message history

Store final text, language, priority, direction, timestamps, transport and delivery state locally. Do not store raw audio by default. Provide Clear History and optional auto-expiry. Diagnostic export must redact peer identifiers unless the user explicitly includes them.

### FR-13 — Diagnostics

Record monotonic timestamps at:

- microphone start;
- end of speech/PTT release;
- ASR completion;
- packet enqueue/send;
- packet receipt/persist/ACK;
- TTS request;
- first PCM sample produced;
- playback start/end.

The diagnostics screen shall report model load time, audio duration, ASR inference time, ASR RTF, network time, TTS synthesis time, TTS RTF, end-to-end time, current/peak process memory and approximate CPU time. Results can be exported as CSV/JSON for the SIH report.

## 7. Wire protocol

Use Protocol Buffers Lite for compact, versioned serialization. A protobuf `string` validates and carries UTF-8 text. Define an `itantra.proto` schema equivalent to:

```proto
syntax = "proto3";
package org.itantra.protocol.v1;

enum MessageType {
  TYPE_UNSPECIFIED = 0;
  HELLO = 1;
  CAPABILITIES = 2;
  TEXT_PARTIAL = 3;
  TEXT_FINAL = 4;
  ALERT = 5;
  ACK_DELIVERED = 6;
  ACK_PLAYED = 7;
  ACKNOWLEDGED = 8;
  PING = 9;
  ERROR = 10;
}

enum LanguageCode {
  LANGUAGE_UNSPECIFIED = 0;
  BN = 1; EN = 2; GU = 3; HI = 4; KN = 5;
  ML = 6; MR = 7; OR = 8; TA = 9; TE = 10;
}

message Envelope {
  uint32 protocol_major = 1;
  uint32 protocol_minor = 2;
  fixed64 session_id = 3;
  fixed64 message_id = 4;
  uint32 sequence = 5;
  MessageType type = 6;
  LanguageCode language = 7;
  string text = 8;
  uint64 sent_elapsed_ms = 9;
  bytes body = 10;
  uint32 crc32 = 11;
}
```

Each stream frame is `4-byte big-endian payload length + serialized Envelope`. Reject frames above 64 KiB before allocation. Compute CRC32 with the `crc32` field set to zero, then set that field before serialization; verify it the same way on receipt. `TEXT_PARTIAL` may be displayed but is never spoken or persisted. Only `TEXT_FINAL` and `ALERT` trigger TTS.

Link-layer WPA2/Bluetooth pairing is acceptable for the first controlled demo. Before handling sensitive operational data, add application-level authenticated encryption over both streams (TLS 1.3 or an audited AEAD session), peer fingerprint verification and replay protection. Do not invent “military-grade” security claims.

## 8. Technical architecture

```text
Microphone -> AudioRecord -> VAD/Endpoint -> Selected ASR -> Unicode text
                                                        |
                                                        v
UI/History <- Delivery state <- Protocol/Queue <- Transport adapter
                                                        |
                                         Wi-Fi Direct / Bluetooth / USB
                                                        |
                                                        v
Transport adapter -> Protocol/Dedup -> Language tag -> eSpeak NG -> AudioTrack
```

### 8.1 Android baseline

- Kotlin-first Android application.
- Jetpack Compose Material 3 UI.
- Clean module boundaries, MVVM/state-reducer presentation, coroutines and `StateFlow`.
- `minSdk = 26` for Android 8.0-era low/mid devices.
- `compileSdk = 37`; prototype `targetSdk = 36`, followed by an API 37 migration test because Android 17 adds local-network permission and background-audio restrictions.
- Java 17.
- Arm64-v8a is the required demo ABI. Produce a separate armeabi-v7a build only if sherpa-onnx/model testing passes; do not inflate the primary APK with unused ABIs.
- Room for messages/outbox; DataStore for settings; WorkManager for optional resumable pack downloads.
- One foreground service while actively listening/connected, with microphone/connected-device service types and visible notification.

### 8.2 Suggested Gradle modules

```text
app/
core-common/
core-audio/
core-models/
engine-asr-sherpa/
engine-vad-sherpa/
engine-tts-espeak/
protocol/
transport-api/
transport-wifidirect/
transport-bluetooth/
transport-usb/
data/
feature-onboarding/
feature-devices/
feature-talk/
feature-model-manager/
feature-diagnostics/
benchmark/
```

Avoid premature module splitting if the build becomes fragile; the interfaces and package boundaries are mandatory, not the exact number of Gradle modules.

### 8.3 State machine

The session reducer shall allow only valid transitions:

```text
Disconnected -> Discovering -> Connecting -> Connected/Idle
Connected/Idle -> Recording -> FinalizingASR -> Sending -> Connected/Idle
Connected/Idle -> Receiving -> Synthesizing -> Playing -> Connected/Idle
Any active state -> Recovering -> Connected/Idle or Disconnected
```

Recording and playback cannot coexist in MVP. Emergency receipt preempts a normal local capture only after safely closing/discarding that incomplete utterance.

## 9. Selected open-source components and exact downloads

### 9.1 sherpa-onnx Android runtime

Use [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), whose code is Apache-2.0. Pin release `v1.13.8` for this implementation rather than following `master` during the sprint.

- Android AAR: [sherpa-onnx-1.13.8.aar](https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar)
- Published SHA-256: `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`
- Android guide: [sherpa-onnx Android documentation](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- Kotlin offline recognizer API: [OfflineRecognizer.kt](https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/kotlin-api/OfflineRecognizer.kt)

Place the AAR at `third_party/sherpa-onnx/sherpa-onnx-1.13.8.aar` and declare it as a local Gradle dependency. Keep its license and notices under `licenses/`.

### 9.2 English ASR

Selected model: official sherpa-onnx streaming Zipformer English, 20M parameters.

- Documentation: [English 20M Zipformer model](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-en-20m-2023-02-17-english)
- Archive: [sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2)

Extract these files into `models/source/asr/en/`:

```text
encoder-epoch-99-avg-1.int8.onnx
decoder-epoch-99-avg-1.onnx
joiner-epoch-99-avg-1.int8.onnx
tokens.txt
```

Important: “20M” means approximately twenty million parameters, not a 20 MB download. Measure the extracted pack; do not label it 20 MB.

### 9.3 Android-ready Indic ASR packs

Selected source: [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx). It is a community conversion, not an official AI4Bharat Android release. Its model card states the packs are INT8, use the CTC head, work with sherpa-onnx's `OfflineNemoEncDecCtcModelConfig`, and are roughly 150–200 MB each. The underlying AI4Bharat IndicConformer is 120M parameters; these are not 20M models.

Download the shared vocabulary once:

- [Indic `tokens.txt`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/tokens.txt)

Download the required published packs:

| Language | Direct model file |
|---|---|
| Bengali | [`bn/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/bn/model.int8.onnx) |
| Gujarati | [`gu/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/gu/model.int8.onnx) |
| Hindi | [`hi/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi/model.int8.onnx) |
| Kannada | [`kn/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/kn/model.int8.onnx) |
| Malayalam | [`ml/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/ml/model.int8.onnx) |
| Marathi | [`mr/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/mr/model.int8.onnx) |
| Tamil | [`ta/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/ta/model.int8.onnx) |
| Telugu | [`te/model.int8.onnx`](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/te/model.int8.onnx) |

Place each model at `models/source/asr/<code>/model.int8.onnx` and copy the shared vocabulary to `models/source/asr/indic-tokens.txt`. Each produced `.itpack` should contain its own copy or a verified reference to the shared vocabulary so uninstalling one language cannot break another.

Do not use the empty `or/` placeholder in that repository as evidence of Odia support. The repository's published available-language list does not include Odia at the time of this PRD.

### 9.4 Odia ASR: mandatory export task

Official source:

- [AI4Bharat Odia IndicConformer model page](https://huggingface.co/ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large) — MIT, gated; the user must log in and accept access terms.
- [Direct official `.nemo` checkpoint](https://objectstore.e2enetworks.net/indicconformer/models/indicconformer_stt_or_hybrid_rnnt_large.nemo)
- [AI4Bharat IndicConformer repository](https://github.com/AI4Bharat/IndicConformerASR)
- [Community Android export pipeline notebook](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/blob/main/ai4bharat_export_pipeline.ipynb)

Run the export notebook with `LANG_CODE = "or"`. The required output is a single CTC-masked, MatMul-only dynamic-INT8 `model.int8.onnx` compatible with sherpa-onnx plus the matching Indic tokens. Place it at `models/source/asr/or/model.int8.onnx`.

Odia is accepted only after all of these pass:

1. ONNX model loads through `OfflineNemoEncDecCtcModelConfig` on a real arm64 Android phone.
2. It decodes 16 kHz mono audio without crash or invalid characters.
3. At least 100 native-speaker test sentences are scored.
4. FP32/source, INT8 desktop and INT8 Android outputs are compared on the same samples.
5. Size, load time, peak RAM, RTF and WER are recorded.
6. Model source, export notebook revision and SHA-256 are stored in `models.lock.json`.

If this task is not complete, the app must show Odia ASR as “experimental/unavailable,” and the team must not claim ten-language ASR completion.

### 9.5 Voice activity detection

Use sherpa-onnx's 16 kHz INT8 Silero VAD export:

- Documentation: [Silero VAD in sherpa-onnx](https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html)
- Model: [`silero_vad.int8.onnx`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.int8.onnx)

The documented size is approximately 208 KB. Place it at `models/source/vad/silero_vad.int8.onnx`. Silero VAD is MIT-licensed; retain the notice.

### 9.6 TTS for all ten languages

Selected baseline: [eSpeak NG](https://github.com/espeak-ng/espeak-ng), built directly into the app. It is compact formant synthesis and openly describes its speech as clear but less natural/smooth than large recorded-speech systems.

- Source: [eSpeak NG GitHub repository](https://github.com/espeak-ng/espeak-ng)
- Android/build guide: [Building eSpeak NG](https://github.com/espeak-ng/espeak-ng/blob/master/docs/building.md)
- Exact voice identifiers: [eSpeak NG supported languages](https://github.com/espeak-ng/espeak-ng/blob/master/docs/languages.md)
- C API: [`speak_lib.h`](https://github.com/espeak-ng/espeak-ng/blob/master/src/include/espeak-ng/speak_lib.h)

There are no separate 1 GB TTS model downloads. Clone/pin the source under `third_party/espeak-ng/`, build the native library and package its compiled language/voice data. Required identifiers are `bn`, `en`, `gu`, `hi`, `kn`, `ml`, `mr`, `or`, `ta`, `te`.

Licensing is important: eSpeak NG contains GPL-3.0-or-later code plus separately identified files under compatible/other licenses. Linking and distributing it in the APK means the app distribution and source-release plan must be GPLv3-compatible and include required notices/source offer. Perform a license inventory before submission; do not treat a GitHub link as legal compliance.

### 9.7 Download and integrity rules

1. Large model binaries shall not be committed to ordinary Git history. Use ignored `models/source/`, release assets, an artifact store or Git LFS.
2. A setup script may download the public packs, but every URL must come from a checked-in manifest.
3. Compute SHA-256 after every download. Record file size, hash, source URL, license, revision and validation status in `models.lock.json`.
4. The app verifies the pack manifest and SHA-256 before installation and after copying.
5. Never execute code from a model pack. Only allow declared ONNX/token/license files and defend against zip-slip paths.
6. Pin source revisions before the screening build; `resolve/main` links above are convenient acquisition links, not immutable release guarantees.

## 10. Local project and model layout

Create this structure before handing the folder to a coding model:

```text
D:/SIH/
  ITANTRA_PRD.md
  README.md
  settings.gradle.kts
  build.gradle.kts
  app/
  ...modules...
  models/
    README.md
    manifest.json
    models.lock.json
    source/                       # ignored by normal Git
      asr/
        en/
          encoder-epoch-99-avg-1.int8.onnx
          decoder-epoch-99-avg-1.onnx
          joiner-epoch-99-avg-1.int8.onnx
          tokens.txt
        bn/model.int8.onnx
        gu/model.int8.onnx
        hi/model.int8.onnx
        kn/model.int8.onnx
        ml/model.int8.onnx
        mr/model.int8.onnx
        or/model.int8.onnx       # generated and accepted separately
        ta/model.int8.onnx
        te/model.int8.onnx
        indic-tokens.txt
      vad/silero_vad.int8.onnx
    packs/                        # generated .itpack deliverables
  third_party/
    sherpa-onnx/sherpa-onnx-1.13.8.aar
    espeak-ng/
  licenses/
  protocol/src/main/proto/itantra.proto
  benchmark/
  docs/
    test-plan.md
    results/
```

The coding agent can use these local artifacts during build, but the production APK should not bundle all ASR packs. Bundle English and Hindi only in a special `demoPreloaded` flavor if required; the normal base APK uses installable language packs.

## 11. Model-pack format

An `.itpack` is a ZIP with a strict manifest:

```json
{
  "schemaVersion": 1,
  "packId": "asr.indicconformer.hi.int8",
  "language": "hi",
  "engine": "sherpa-offline-nemo-ctc",
  "sampleRate": 16000,
  "files": [
    {"path": "model.int8.onnx", "sha256": "REQUIRED", "bytes": 0},
    {"path": "tokens.txt", "sha256": "REQUIRED", "bytes": 0}
  ],
  "sourceUrl": "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx",
  "sourceRevision": "PIN_REQUIRED",
  "license": "Apache-2.0 / underlying AI4Bharat MIT; verify notices",
  "validated": false
}
```

Installation extracts to `context.noBackupFilesDir/models/<packId>/<version>/`, marks files read-only where supported and atomically switches an active-version pointer after validation. Partial downloads/extractions never replace a working pack.

## 12. Performance requirements and acceptance targets

SIH states evaluation categories but does not publish numeric pass thresholds in the supplied statement. The figures below are internal engineering targets and must be reported as measured, not guaranteed.

| Metric | Screening target | Final target |
|---|---:|---:|
| Base app excluding ASR packs | ≤ 80 MB arm64 APK | ≤ 70 MB optimized |
| English ASR pack | measured; expected well below Indic pack | ≤ 100 MB preferred |
| Each Indic INT8 ASR pack | ≤ 220 MB | ≤ 200 MB preferred |
| eSpeak native/data impact | measured | ≤ 15 MB preferred |
| Idle connected/listening CPU | ≤ 8% on reference low device | ≤ 5% median |
| Peak process RAM, one model loaded | ≤ 700 MB | ≤ 512 MB preferred |
| ASR RTF | ≤ 1.0 low device | ≤ 0.7 mid device |
| TTS RTF | ≤ 0.20 | ≤ 0.10 |
| PTT release to final text | ≤ 2.5 s p95 | ≤ 1.5 s p50 |
| Receiver packet to audio start | ≤ 500 ms p95 | ≤ 250 ms p50 |
| PTT release to remote audio start | ≤ 3.5 s p95 | ≤ 2.0 s p50 |
| Wi-Fi final packet ACK | ≤ 500 ms p95 | ≤ 300 ms p95 |
| Bluetooth final packet ACK | ≤ 1,000 ms p95 | ≤ 700 ms p95 |
| Peer discovery | ≤ 15 s p95 | ≤ 10 s p95 |
| Clean-speech WER | report honestly | ≤ 20% target per language |
| Noisy-speech WER | report honestly | ≤ 30% target per language |

If a target is missed, preserve the measurement and explain the trade-off. Never delete difficult samples to improve the score.

## 13. Test and evidence plan

### 13.1 Reference devices

At minimum:

- low-range: 4 GB RAM, 8-core budget ARM, Android 10–12;
- mid-range: 6–8 GB RAM, Android 13–17;
- two different manufacturers to expose Wi-Fi Direct/Bluetooth differences.

Record exact model number, SoC, RAM, Android build and battery mode.

### 13.2 ASR corpus

For every claimed language, collect at least 100 consented sentences with:

- multiple speakers and genders/ages where possible;
- quiet indoor, fan/traffic background and moderate-distance microphone conditions;
- commands, numbers, place names and emergency vocabulary;
- exact human reference transcripts in native Unicode.

Keep a fixed hidden evaluation split. Compute WER with a documented, language-aware normalization policy. Do not score only the best microphone clips.

### 13.3 TTS evaluation

For each language, use at least 30 operational sentences and five native listeners. Record:

- intelligibility: words correctly written after listening;
- mean opinion score for clarity/flow;
- first-audio latency and RTF;
- failures on numbers, abbreviations and names.

eSpeak's robotic character is acceptable only if intelligibility is proven. A failed language pronunciation is a release blocker even if the voice identifier exists.

### 13.4 Connectivity matrix

Test each direction for:

- Wi-Fi Direct normal and emergency messages;
- Bluetooth fallback;
- disconnect during send, retry and deduplication;
- both devices speaking at nearly the same time;
- app background/foreground and screen lock where OS permits;
- receiver app process restart with queued message recovery;
- 1, 10, 100 and 2,048-byte native-script payloads;
- malformed length, checksum, language and protocol version.

### 13.5 Offline proof

1. Preinstall required packs.
2. Remove SIM or mobile data.
3. Enable airplane mode, then manually re-enable only Wi-Fi/Bluetooth as required.
4. Clear DNS/network access or monitor traffic.
5. Restart both apps and complete the demo.
6. Show that no inference request leaves the device.

### 13.6 Automated tests

- unit tests for Unicode normalization, protocol serialization, limits, CRC, retry and dedup;
- golden tests for language mapping and TTS preprocessing;
- recognizer smoke test per pack using one checked-in/licensed short sample;
- JNI lifecycle and cancellation tests for eSpeak;
- instrumentation tests for permissions and foreground service;
- two-device manual/e2e checklist, because radio behavior cannot be proven only in an emulator;
- macrobenchmarks for cold model load, ASR, TTS and memory.

## 14. Delivery phases

### Phase 0 — Feasibility lock (2–4 days)

- create repository/modules and dependency lock;
- acquire English, Hindi, VAD and eSpeak artifacts;
- verify hashes/licenses;
- run English and Hindi ASR plus all ten eSpeak voice identifiers on one real phone;
- stop if either Hindi model cannot meet real-time operation or eSpeak intelligibility is unusable.

Exit: measured one-phone ASR/TTS evidence, not merely successful compilation.

### Phase 1 — Screening MVP (7–10 focused days)

- one APK on two phones;
- model manager and preloaded/imported Hindi/English;
- manual language selection;
- PTT capture and endpoint;
- Unicode final messages over Wi-Fi Direct;
- automatic receiver TTS;
- normal/emergency priority;
- acknowledgements, retry and message history;
- visible latency/RTF/size/RAM diagnostics;
- repeatable no-internet demo.

Exit: ten consecutive Hindi and ten consecutive English round trips with no app crash or wrong-language TTS.

### Phase 2 — Required breadth (5–8 days)

- install and smoke-test Bengali, Gujarati, Kannada, Malayalam, Marathi, Tamil and Telugu packs;
- Bluetooth RFCOMM fallback;
- hands-free VAD mode;
- per-language text normalization/pronunciation tests;
- low/mid-device benchmark matrix.

Exit: all nine available languages pass functional round trip and evidence is exported.

### Phase 3 — Odia and final compliance (3–7 days, uncertain)

- accept official AI4Bharat access terms;
- export/quantize Odia using the cited notebook;
- validate desktop-versus-Android output and native-speaker WER;
- complete all ten-language acceptance matrix;
- finish license/source distribution package.

Exit: Odia is no longer experimental and all exact language codes pass.

### Phase 4 — Differentiators after the core is stable

- USB serial gateway carrying the same framed protobuf text to an external radio/embedded board;
- store-and-forward multi-hop relay;
- optional application-level encryption and QR fingerprint pairing;
- optional neural TTS pack interface for languages where a genuinely mobile, license-compatible voice is proven;
- acoustic echo cancellation experiment for fuller duplex behavior.

No Phase 4 feature may destabilize the screening path.

## 15. Demo script

1. Show both phones with mobile data off and the Offline Ready indicator.
2. Open Model Manager and show model files, sizes, sources and checksums.
3. Discover/connect Phone A to Phone B with Wi-Fi Direct and confirm peer fingerprint.
4. Select Hindi on Phone A, hold PTT, speak a prepared but not memorized sentence, release, show transcript, transferred byte count and automatic Hindi playback on Phone B.
5. Reply in English from Phone B using the English 20M streaming model.
6. Send an emergency Hindi alert and show priority playback and acknowledgement.
7. Disable Wi-Fi Direct or disconnect it, connect over Bluetooth and repeat a message.
8. Open Diagnostics and show actual ASR RTF, remote-audio latency, RAM, CPU and message bytes.
9. Show the pack list for other languages. Demonstrate at least one Dravidian language if a native speaker is available.
10. State Odia's actual validation status. If incomplete, say so and show the reproducible export task rather than faking completion.

## 16. Risks and mitigations

| Risk | Severity | Honest mitigation |
|---|---|---|
| Indic models are 120M/~150–200 MB INT8, not tiny 20M models | High | on-demand packs, load one at a time, benchmark on 4 GB hardware |
| Community Android conversions have limited independent validation | High | compare against official source, pin hashes, native-speaker test, retain replacement interface |
| Odia Android pack missing | Critical | make export a named gate; never mark ready before real-device tests |
| eSpeak is robotic and non-neural | High | measure intelligibility, tune pronunciation/rate, present as compact fallback; investigate optional neural packs only after MVP |
| Wi-Fi Direct differs by OEM | High | runtime capability checks, Bluetooth fallback, test two manufacturers |
| Hands-free mode hears its own TTS | High | strict turn-taking, suspend mic while playing, AEC only as later experiment |
| Android blocks literal non-interruptible alerts | High | foreground service, alarm usage, user-authorized DND/volume; document best-effort limit |
| GPL obligations from eSpeak | High | GPL-compatible source release and notices; legal inventory before distribution |
| Huge all-language install | Medium | small base APK and individually installable/importable packs |
| Poor accuracy in noise/accent | High | real corpus, language-specific results, optional noise suppression and domain vocabulary after baseline |
| “Radio” judged as requiring hardware | Medium | two-phone Wi-Fi/Bluetooth loop exactly matches stated verification; show transport API and optional USB gateway roadmap |

## 17. Definition of done

The product is complete only when:

1. the same APK installs on two low/mid Android phones;
2. all inference works with internet unavailable;
3. every one of the ten exact languages has a validated ASR path and embedded TTS path;
4. sender language is manual and receiver language selection is automatic from protocol metadata;
5. Wi-Fi Direct and Bluetooth both transmit Unicode final messages reliably;
6. PTT and turn-based hands-free modes work;
7. priority alerts behave at the strongest permitted/user-authorized Android level;
8. retries, deduplication, reconnect and persistent outbox are verified;
9. no raw audio is transmitted;
10. accuracy, latency, RTF, RAM, CPU and size results are measured on reference hardware;
11. model/source revisions, SHA-256 values and licenses are included;
12. the source release satisfies all third-party obligations;
13. the demo can be repeated ten times without unexplained manual recovery.

## 18. Instructions to the implementation coding agent

1. Treat this document as the source of truth; create an issue/decision record before changing a selected model, protocol or language code.
2. Begin with Phase 0 and Phase 1. Do not build optional radio hardware before the two-phone loop is stable.
3. Inspect local model files and verify hashes before writing adapters. Never fabricate missing binaries, benchmark numbers or confidence values.
4. Keep the ASR, TTS and transport interfaces replaceable.
5. Use real Android hardware early. Emulator success is insufficient for microphones, Wi-Fi Direct, Bluetooth, native memory and performance.
6. Commit code, manifests, schemas, scripts, tests and small licensed samples; do not commit multi-gigabyte models to normal Git.
7. Maintain `DECISIONS.md`, `THIRD_PARTY_NOTICES.md`, `models.lock.json` and benchmark output from the first working build.
8. Do not call the project complete while Odia is absent or while eSpeak support is asserted only from a language list without listening tests.

---

## 19. Primary reference links

- [SIH problem statements portal](https://www.sih.gov.in/sih2026PS)
- [sherpa-onnx repository](https://github.com/k2-fsa/sherpa-onnx)
- [sherpa-onnx Android documentation](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [Official English 20M model documentation](https://k2-fsa.github.io/sherpa/onnx/pretrained_models/online-transducer/zipformer-transducer-models.html#csukuangfj-sherpa-onnx-streaming-zipformer-en-20m-2023-02-17-english)
- [AI4Bharat IndicConformer repository](https://github.com/AI4Bharat/IndicConformerASR)
- [Android-ready IndicConformer community conversions](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx)
- [Official AI4Bharat Odia checkpoint](https://huggingface.co/ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large)
- [eSpeak NG repository](https://github.com/espeak-ng/espeak-ng)
- [Android Wi-Fi Direct guide](https://developer.android.com/develop/connectivity/wifi/wifi-direct)
- [Android Bluetooth permission guide](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [Android 17 local-network/background behavior changes](https://developer.android.com/about/versions/17/behavior-changes-17)
