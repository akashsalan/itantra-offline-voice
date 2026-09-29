# Field-preview update: focused verification, 17 September 2026

## Final workspace build

Version `0.6.1-sos-preview`, code **15**, arm64, minSdk 26 / targetSdk 36.
The APK manifest was inspected with `aapt2`; version code/name match the source.

| APK | Bytes | Decimal MB | SHA-256 |
| --- | ---: | ---: | --- |
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 47,967,346 | 47.97 | `042ded0cd665cbfd47fea6e35d8a9969315566ab049a054b9ba391b98c10b433` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 377,363,910 | 377.36 | `0cf47b9a4a0b26e75933d8253561c6d620d77e351b9dc1c19874c122196d301e` |

Both Gradle assemble tasks succeeded. The final focused run passed **32 tests**:
ConnectionChoice 3, DownloadRules 4, PlaybackQueue 8, PttEndpoint 4,
RelayLedger 3, RelayPacket 3, SosConfirmation 7. The added SOS tests verify one
window per completed voice capture, no duplicate after finger release/cancel,
new-press recovery and no-route/changed-recipient rejection.
No failures/errors were ignored.
An earlier development run failed the new replay-window assertion; the final
production/test-source rerun passes it. No full instrumentation, multi-device
acceptance suite or formal benchmark was run. `git diff --check` passed; Git
reported existing CRLF-normalisation warnings for two unrelated dirty files.

```powershell
$env:JAVA_HOME = 'D:/JAVA 17'
$env:ANDROID_HOME = "$env:LOCALAPPDATA/Android/Sdk"
./gradlew.bat :app:testBaseDebugUnitTest `
  --tests org.itantra.app.core.RelayPacketTest `
  --tests org.itantra.app.core.RelayLedgerTest `
  --tests org.itantra.app.core.DownloadRulesTest `
  --tests org.itantra.app.core.SosConfirmationTest `
  --tests org.itantra.app.core.ConnectionChoiceTest `
  --tests org.itantra.app.core.PttEndpointTest `
  --tests org.itantra.app.core.PlaybackQueueTest `
  :app:assembleBaseDebug :app:assembleDemoPreloadedDebug --console=plain
```

## Model/archive checks

Stream hashing of the preloaded APK's embedded archives confirmed:

- English: 131,702,085 bytes; SHA-256 `e9532d0a392fbd63b086e47bbd6bdc1cc69c5f213b4ba0ffb4d8526201435933`.
  This is the current **Parakeet 110M CTC INT8** `.itpack`, not a retired Zipformer/Moonshine pack.
- Hindi: 197,694,194 bytes; SHA-256 `000fec352d780bc1765dad360ba985a0534cd6baf446b2c844ddb7d94947cb06`.
- Silero VAD: 212,860 bytes; SHA-256 `c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20`.
- Base APK: **zero** bundled heavy starter packs. No model weights or inference dependencies were added.

Relative to the pre-update artifacts inspected at the start (48,168,384 and
377,079,097 bytes), final APKs differ by -201,038 and +284,813 bytes respectively.
These are artifact-size differences, not a benchmark or an isolated per-feature cost.

## Device checks, initial pause and approved update

ADB confirmed **RMX1801, Android 10, serial <test-phone>**. Approximately 2.5 GB free
on `/data` was reported before installation. An **intermediate preloaded build
of code 14** was successfully installed with `adb install -r`, then launched
with `am start -W`. No uninstall or application-data clearing was used.

Observed on that installed preview:

- App opens and remains running. No crash-buffer entry for the observed app PID.
- Talk, Connections/method sheet, Models, More, Diagnostics and SOS render.
- Existing voice history remains visible, including English and Hindi messages.
- Models UI explicitly shows Parakeet 110M CTC INT8 and verified installed English.
- English, Hindi and experimental Odia active-model pointers retain their
  previous contents/timestamps. The existing Room database and WAL remain present.
- A completed English voice draft was observed while the phone was also being
  used. This is **not** a controlled real-device VAD threshold/timing test.
- SOS confirmation is disabled when no recipient/active BLE route is available.

**Initial pause (subsequently resolved):** After the first
installation, an unsent voice draft appeared and the phone received concurrent
touches. The final reinstall was paused to avoid erasing that in-memory draft.
The draft was re-observed during the final read-only check; package inspection
still reports installed **0.6.0-field-preview, code 14**. Explicit direction was
requested from the user. Final code 15 adds the dedicated SOS page, its own
emergency PTT/STT draft, radio vectors and per-alert queue/acknowledgement state,
as well as earlier BLE framing/accounting/replay, palette and tab-state fixes.
There is no final-device screenshot or controlled SOS/PTT result for code 15.

**Approved update completed:** The user subsequently requested installation and
will perform the manual UI checks. The exact preloaded APK above was installed
successfully using `adb -s <test-phone> install -r`. Package inspection reports
`0.6.1-sos-preview`, code **15**, last update `2026-09-17 18:00:39`.
`am start -W -n org.itantra.app/.MainActivity` returned `Status: ok`; the app
process was running and its crash-buffer query was empty. The existing message
database remains present (49,152 bytes). No uninstall or data-clearing command
was used. Detailed SOS/PTT, model and connection interaction checks are left to
the user as requested; a successful launch is not a full regression result.

## Screenshots

These are captures of the **intermediate installed preview**, not mockups and
not evidence that the later refinements have been inspected on-device:

- [Talk / retained voice history](field-preview-talk.png)
- [Connections method sheet](field-preview-connections.png)
- [More](field-preview-more.png)
- [Diagnostics](field-preview-diagnostics.png)
- [SOS, no available recipient](field-preview-sos.png)

The Talk screenshot contains existing local conversation content; review it
before sharing this results directory publicly. Diagnostic JSON exports do not
include such content. No new session JSON was exported during this smoke check.

## Still required / not claimed

- Final-screen/SOS inspection by the user; code 15 installation and launch are
  complete. The earlier unsent-draft installation gate is resolved.
- Controlled PTT/VAD smoke check; permission-revocation, large-font/TalkBack and
  dark-theme device checks. The deterministic endpoint tests all pass.
- Real Wi-Fi Direct multi-member and isolated A→B→C BLE relay/return-ACK tests.
  One connected phone does not establish these results. BLE remains experimental.
- Approved HTTPS model hosting, followed by real online pause/resume/retry and
  interrupted-install checks. URLs stay disabled; no model was uploaded.
- Native-speaker review of translated emergency templates/guidance.
- G: was not mounted/visible (`Test-Path G:/` false); no APK copy to G: occurred.

No WER, intelligibility score, exact acoustic onset, idle-listening CPU percentage,
range, battery-life, ten-language accuracy or benchmark averages are claimed.
