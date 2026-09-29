# English options: focused verification

17 September 2026, code 17 (`0.6.3-english-options`). This is a bounded model
addition, not a full regression run or formal device benchmark.

## Build and integrity

- `testBaseDebugUnitTest`: **24 passed**, zero failures/errors: 15 PackCatalog,
  5 StreamingUtterance, 4 PttEndpoint tests.
- Base debug, demoPreloaded debug and base debug instrumentation APK compiled.
- Small, Tiny and Hindi ZIP manifests, exact file sizes and hashes match the
  lock: [host integrity output](english-options-pack-integrity.json).
- APK contents checked: base has no ASR packs; preloaded has exactly
  `en.itpack`, `en-low-end.itpack`, `hi.itpack`. Both retain Silero VAD.
- Existing isolated Moonshine AAR hash remains
  `cf948def454984963a9168e7e737d25ef3ab4b9f86e6d0c60b67d3e0fe41f893`.
- Small and Hindi pack hashes are unchanged. No dependency/SDK upgrade.
- Existing deprecation warnings in RelayPage, SettingsPage and SosPage remain.

| APK | Bytes | Decimal MB | SHA-256 |
|---|---:|---:|---|
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 66,304,368 | 66.30 | `0f86af6a72d1257cf1398c00ecf0a7e11c634e6a7f57882b7034c86e84eaf55f` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 451,613,418 | 451.61 | `3d2a255ad571c190e68c6525872ea4c514abf5119a25ef49773e5e3d98eb8cd6` |

Relative to code 16, base increases 23,444 B; preloaded increases 45,297,377 B.
Tiny extraction adds 45,247,839 B plus filesystem/manifest overhead. Previous
installed models are retained, not replaced or automatically cleaned up.

## Focused Android tests

Device: connected RMX1801, Android 10/API 29, arm64-v8a, ADB serial <test-phone>.
Three selected tests passed in 17.441 seconds on retry:

1. Tiny native load, fixed human fixture transcription, cached finalization,
   reset and coexistence with existing Silero/Hindi native models.
2. Small/Tiny profile installation, persistent independent selection and
   deleting only the selected inactive profile (disposable test root).
3. Rejected Small load/import preserves the previous installed model and pointer.

The initial attempt was **not a pass**: OppoGuardElf force-stopped the background
instrumentation process for high CPU at 20:43:18. No native crash was shown for
that attempt. The identical focused command passed on retry; no device battery
settings were changed. Its abandoned 45 MB temporary cache copy was removed,
not production models. This OEM behavior remains a device-specific limitation.

The [Tiny fixed-fixture result](moonshine-tiny-sherpa-first.json) records one
6.625-second sample: 6,513.36 ms accumulated inference (RTF about 0.98), 292.89 ms
load time and 277,010 KiB PSS sampled after decoding. PSS is **not peak memory**.
This is not microphone end-to-end latency, an accuracy score, an average,
a controlled Small-vs-Tiny benchmark or a low-end performance guarantee.

Production English/Hindi/Odia active pointers and both the message database
and WAL hashes were unchanged across the focused tests. Test fixtures were
separate from user messages; no message or emergency was transmitted.

## Scope reviewed

Changed app components: PackCatalog, ModelPacks, MoonshineStreamingEngine,
SherpaEngines dispatch, AppRuntime model installation/selection/diagnostic name,
ModelsPage, Talk model menu and SOS selected-model label. Radio capability
languages are deduplicated because two ASR profiles still mean one English
language; protocol definitions and transport behavior are unchanged.

Build configuration, preparation/pack/verification scripts, lock/catalogue,
MIT notice and current documentation updated. Snapshot comparison found no
changes to existing PTT capture, VAD, TTS, database, settings, navigation,
transport or emergency flow implementations. No full suite or multi-phone test.

## Final installation and visual checks

Final preloaded APK installed on the same connected phone, version code 17 /
`0.6.3-english-options`. On-device `base.apk` SHA-256 exactly matched
`3d2a255ad571c190e68c6525872ea4c514abf5119a25ef49773e5e3d98eb8cd6`.

One final update attempt failed with `INSTALL_FAILED_INSUFFICIENT_STORAGE`.
Installing the smaller base APK with `-r`, then preloaded with `-r`, succeeded.
This replaced only application binaries; no uninstall, app-data clear or user
model/message deletion was used. Phone space is still low. G: was not mounted,
so no copy to that drive was made.

Launched MainActivity successfully. Bundled Tiny installed with verification
while Small remained active. Existing Talk history was visible. Both installed
English profiles render separately in Models; the Talk menu has adjacent
English and English — low-end devices choices. Selected Tiny from Models,
confirmed its persisted active pointer and ready Talk state, then switched back
to Small from Talk. Existing Hindi/Odia active-pointer hashes stayed unchanged.
The original Small active-pointer hash was restored exactly. The main database
hash was unchanged; the WAL changed during normal app startup/UI operation.
No claim is made that all database bytes stayed identical after launch.

A brief 350-ms PTT hold/release with Tiny selected returned to idle with
"No usable speech captured" and did not send or create a usable draft. The
device was not connected to a peer. Human microphone recognition, noisy/accented
speech, connected transmission and two-phone acceptance were not tested here.

Visually inspected the existing dark theme at the phone's current font scale;
the longer Tiny label fits the Talk chip/menu and Models row. No global theme,
large-font or navigation redesign was performed in this bounded update.

- [Both English models installed, Small selected](english-options-models.png).
- [Talk language menu with Tiny selected](english-options-menu.png).

The phone is left on Models with the original Small English choice selected;
Tiny is ready for the user's manual comparison. All old imported packs remain.
