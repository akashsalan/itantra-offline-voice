# Moonshine Small Streaming update checks — 17 September 2026

Version **0.6.2-moonshine / code 16**, connected **RMX1801**, Android **10/API 29**,
arm64-v8a. Installed with `adb install -r`, never uninstalled or data-cleared.

## Build and focused tests

- `testBaseDebugUnitTest` with only StreamingUtteranceTest, PackCatalogTest and
  PttEndpointTest: **22 passed, 0 failed**. Four existing endpoint cases cover
  short pause, two-second pause once, manual release and lift after auto-finish.
- Both debug variants compiled. A first build-script ZipFile name-resolution
  error was corrected before the successful builds. Existing unrelated icon
  deprecation warning remains; no dependency upgrade or full lint run.
- `assembleBaseDebugAndroidTest` compiled the targeted device checks.
- `MoonshineStreamingSmokeTest` ran twice in separate processes: Moonshine-first
  and sherpa-first native load order. Both passed. Fixed human fixture, no live
  network transmission. Repeated finalization cached; Hindi and Silero inferred
  successfully afterward. Raw non-transcript results:
  [Moonshine first](moonshine-small-native-first.json),
  [sherpa first](moonshine-small-sherpa-first.json).
- `PackRegistryTest#rejectedSmallStreamingLeavesPreviousPackAndPointerIntact`:
  **passed**. Tiny disposable fixtures; failed validation kept old model active;
  successful import and explicit Parakeet recovery also passed.
- Focused EN/HI archive verification passed against the pinned lock. Previous
  full-pack report retained separately from the new focused report.

## APKs

| Variant | Path | Exact bytes | SHA-256 |
| --- | --- | ---: | --- |
| Base, no ASR weights | `app/build/outputs/apk/base/debug/app-base-debug.apk` | 66,280,924 | `48ad49421e3940bb8bc114a63a05e686ac7bcfc0d2e219c817f342361b59d89f` |
| Preloaded, English Small Streaming + Hindi | `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 406,316,041 | `998041405724d99809cd37bf354676fa67f9ea866a70dcefec18222bcd0528d9` |

Installed APK's SHA-256 matches the preloaded APK above. Against the saved code-15
APKs, base grows 17,629,377 bytes and preloaded grows 28,267,281 bytes. Most growth
is the additional isolated native runtime; the English pack grows 10,636,955 bytes.
No other language weights changed. G: was not mounted, so no G: copy was made.

## Device observations

- Launched MainActivity successfully. No new AndroidRuntime/libc crash appeared
  in the inspected log window. Correct code/version confirmed with PackageManager.
- Preloaded import completed and `active-en` points to
  `asr.moonshine.en.small.streaming.2026-08-21.quantized`.
- The old Parakeet directory and registered pointer are retained. Existing Hindi
  and Odia active-pointer hashes are unchanged. Main message DB hash is unchanged;
  WAL changed during ordinary app/test activity, so this is not a full logical
  database diff. Existing messages remain visibly available in Talk.
- Models hierarchy showed English selected, Installed, File integrity verified,
  142.32 MB installed / 142.34 MB download and the new MIT/accuracy-pending label.
- Talk showed live, unsent PTT drafts with English selected and Not connected.
  No send, SOS or replay was initiated by the agent. Screen changes/live draft
  updates indicated the user was using the device, so further UI automation was
  stopped to avoid interference. The captured [screenshot](moonshine-small-talk.png)
  is Talk, not Models.
- No broad navigation redesign, model removal, settings reset, full instrumentation
  suite, multi-device acceptance or formal benchmark was performed.

## Remaining limitations

The one fixture used about 9.4–9.5 seconds accumulated inference for 6.625 seconds
of PCM (RTF approximately 1.4). Sampled post-decode PSS was about 500–504 MiB,
not peak and not a full-app memory bound. Do not claim low-end performance,
accent/noise accuracy, live endpoint latency or universal reliability from this.
Manual noisy-speech/Indian-English acceptance is still needed. Existing Odia,
BLE-relay and release-licence/source-distribution limitations remain unchanged.

Implementation/provenance/recovery: [Moonshine record](../MOONSHINE_SMALL_STREAMING.md).
