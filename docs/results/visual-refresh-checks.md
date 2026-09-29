# Visual refresh — 17 September 2026

This is a visual-only update of the existing `0.6.1-sos-preview` (code 15).
The version was not bumped. Identify these rebuilt APKs by their hashes below,
not by the older code-15 field-preview artifact hashes.

## Changes

- Shared light/dark teal palette, readable typography and rounded shape tokens
  in `AppTheme.kt`; original code-native radio and navigation icons.
- Compact safe-inset header: iTantra, raised red **Emergency SOS** button, and
  **Not connected / Connected** status. SOS opens the existing dedicated page;
  opening it does not transmit an alert. Existing busy/finger-release guards remain.
- Talk has a compact bottom control row with a **124 × 76dp rounded rectangular
  PTT**, state text beside it, finite press/halo transitions and no continuous
  animation. Original hold/release, TalkBack actions and haptics remain intact.
  Red is reserved for emergency UI; processing and general warnings use amber.
- Compact language/Auto-send controls and message bubbles; 48dp replay/ack controls.
- Existing numbered connection flow, clearer method icons, one primary action.
- Cleaner language rows, status chips and retained model/accuracy warnings.
- More uses a single icon-led list; diagnostics retain their structured records
  and honest definitions rather than decorative gauges.
- At larger font scales the navigation labels become Connect/Chat while their
  accessibility names remain Connections/Messages. Header text wraps rather than
  being truncated. Reduced Motion continues to disable transition motion.

Changed UI source files under `app/src/main/java/org/itantra/app/`:
`AppTheme.kt`, `AppIcons.kt`, `MainActivity.kt`, `TalkPage.kt`, `ConnectionsPage.kt`,
`ChatPage.kt`, `LanMessagesPage.kt`, `ModelsPage.kt`, `SettingsPage.kt`,
`MorePage.kt`, `LanDevicesPage.kt`, `ImportProgressDialog.kt`, `SosPage.kt`.
Documentation links and these screenshots/report were also added.

The initial 60-file source hash snapshot was compared with the refresh:
only these 13 UI files changed. Runtime, PTT endpointing, ASR/TTS/audio,
diagnostic measurement logic, model management, data/migrations, transports,
protocols, encryption and experimental BLE relay sources are unchanged.
No dependency, permission or model changes were made by this refresh. The wider
dirty working tree predates this task and was preserved.

## Build and installation

Both tasks passed:

```powershell
./gradlew.bat :app:assembleBaseDebug :app:assembleDemoPreloadedDebug --console=plain
```

| APK | Bytes | Decimal MB | SHA-256 |
| --- | ---: | ---: | --- |
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 48,650,696 | 48.65 | `cb4382d26849d20289d11f1b1f8b9c9389d2f53dab6f170b4278417f28f62d0b` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 378,048,235 | 378.05 | `aee2efa01d3d8d9e4f3f8ed5665cff2cf462bf07e436e6821791b0a13e009207` |

Compared with the earlier field-preview APKs, each grew by approximately
0.68 MB. Model payloads are unchanged; preloaded English remains Parakeet 110M
CTC INT8, not the retired English pack.

ADB confirmed an authorized **RMX1801**, serial `<test-phone>`, **Android 10**.
The preloaded debug APK was installed with `adb install -r` and launched.
The installed APK's on-device SHA-256 matches the preloaded artifact above.
No uninstall, data clearing, history reset or model reimport was performed.
The existing voice/chat history and installed Parakeet English were visible.

## Basic verification

- Both debug flavors compiled; no new unit-test logic was needed for this
  presentation-only change. No full instrumentation or benchmark suite was run.
- Inspected Talk, Connections, Messages, Models and More in light and dark themes.
- Checked Talk/Connections/More at Android font scale **1.35**. Restored the
  original **0.9** font scale through the phone's normal Settings UI.
- Restored the app's original **System** appearance preference after theme checks.
- PTT press/release smoke while disconnected: Listening/halo appeared, release
  ended recording, and the silent capture was rejected as **No usable speech
  captured**. No message was sent. This does not measure speech accuracy or
  validate trailing-silence behavior with real speech.
- Language menu, voice-options sheet and connection-method sheet opened and
  dismissed without changing their selections. SOS entry/back navigation worked
  without sending an alert. Diagnostics showed the discarded local capture.
- Silent-chat composer and Send control remained visible above the keyboard;
  no text was entered or sent.
- No iTantra entry was found in the device crash buffer during these checks.

## Screenshots

These are actual connected-device captures, not mockups. Conversation captures
contain existing message text and peer labels: review/redact before public use.

| Screen | Light | Dark |
| --- | --- | --- |
| Talk | [Light](visual-talk-light.png) | [Dark](visual-talk-dark.png) |
| Connections | [Light](visual-connections-light.png) | [Dark](visual-connections-dark.png) |
| Messages | [Light](visual-messages-light.png) | [Dark](visual-messages-dark.png) |
| Models | [Light](visual-models-light.png) | [Dark](visual-models-dark.png) |
| More | [Light](visual-more-light.png) | [Dark](visual-more-dark.png) |

Additional captures: [large-text Talk](visual-talk-large-text.png),
[large-text Connections](visual-connections-large-text.png),
[large-text More](visual-more-large-text.png), [recording](visual-ptt-recording.png),
[SOS](visual-sos-light.png), [diagnostics](visual-diagnostics-light.png),
[keyboard](visual-messages-keyboard.png).

## Remaining limitations

- On this ColorOS/Android 10 phone the keyboard pans the header out of view.
  The composer remains usable and unobscured; header and tabs return on dismissal.
- Very small screens, landscape, font scales above 1.35 and a full interactive
  TalkBack session were not physically tested. Native-script text was inspected.
- This is not a multi-phone transport, SOS delivery, BLE relay, accuracy or
  performance acceptance test. Existing experimental labels and safety limitations
  remain applicable.
- Haptic configuration and handlers were preserved in source; physical vibration
  intensity cannot be judged remotely.
