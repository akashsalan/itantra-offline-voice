# Approved field-communication update — 17 September 2026

The user approved the read-only product proposal, then added Wi-Fi Direct groups
and a separately verified trusted-team BLE emergency relay prototype. Existing
uncommitted implementation is the baseline, not the last Git commit.

## Preservation boundaries

- Keep Parakeet English, all imported packs and embedded eSpeak NG.
- Keep PTT-only neural Silero VAD, manual release, two-second pause endpoint,
  maximum duration, exactly-once finalisation and finger-lift requirement.
- Preserve legacy Wi-Fi Direct/Classic, LAN TLS admission, Room migrations,
  message history, silent chat, recipient acknowledgements and diagnostics.
- No model publication, cloud inference, location collection, automatic
  translation or unverified mesh/performance claims.
- Only focused tests and practical device smoke checks; update with install -r.

## Reviewable phases

1. Guided connection selection, permission recovery and shared Material 3 UI.
2. Wi-Fi Direct group adapter reusing the existing authenticated group layer.
3. Accessible Talk/More/chat, restrained motion and explicit audience labels.
4. Accident-resistant SOS composition and receiver acknowledgement presentation.
5. Language catalogue, resumable verified downloads, safe unused-pack deletion.
6. Opt-in authenticated BLE emergency-only relay, with bounded forwarding.
7. Focused tests, both debug builds and non-destructive device inspection.

## External gates

- Download catalogue needs an approved HTTPS release location for the existing
  .itpack artifacts. The importer remains fully offline while this is pending.
- Three physical phones are needed to validate Wi-Fi Direct multi-member groups
  and genuine BLE relay with endpoints outside direct range. One phone cannot
  establish either result. BLE remains experimental until these checks pass.
- Native-speaker review of translated guidance/templates remains separate from
  integrity validation or compilation; no language-accuracy claim is inferred.

## Progress

- Baseline inspected: source version 0.5.2-ptt / code 13; existing dirty tree kept.
- Connected ADB device at start: RMX1801 (<test-phone>).
- Source phases implemented: guided Connections, Wi-Fi Direct group adapter,
  Material 3/accessibility, safe SOS, model catalogue/download infrastructure,
  confirmed model deletion, chat audience/draft polish and experimental BLE relay.
- Both debug APKs compile; 29 focused tests pass. No dependency/model replacement
  or database/protocol migration. Baseline hash comparison found 50 of 66 original
  source/protocol files unchanged; changes to the other 16 are within this scope.
- An intermediate preloaded preview was installed with `adb install -r` and
  opens on RMX1801. Imported EN/HI/OR pointers and existing voice history remain.
- Final reinstall is paused because an unsent voice draft appeared while the
  phone was being used. User direction is needed before resetting that in-memory
  draft. The final workspace build includes later refinements not yet on-device.
- G: is unavailable. No models were uploaded; catalogue URLs remain null.

## Handoff and files

- [APK paths/sizes/hashes, focused test and device evidence, screenshots and gates](results/field-preview-checks.md)
- [BLE architecture, threat model and physical verification requirements](BLE_EMERGENCY_RELAY.md)
- [Model catalogue/download/deletion design and hosting gate](MODEL_CATALOGUE.md)

Modified existing application files (relative to `app/src/main/java/org/itantra/app`):
`AppRuntime.kt`, `MainActivity.kt`, `TalkPage.kt`, `ChatPage.kt`, `MorePage.kt`,
`LanDevicesPage.kt`, `LanMessagesPage.kt`, `audio/SpeechService.kt`,
`core/PlaybackQueue.kt`, `data/AppSettings.kt`, `diagnostics/Diagnostics.kt`,
`lan/LanSession.kt`, `models/ModelPacks.kt`, `transport/LinkTransport.kt`.
Also `app/build.gradle.kts`, the manifest comment, and `PlaybackQueueTest.kt`.

Added UI: `AppTheme.kt`, `AppIcons.kt`, `ConnectionsPage.kt`, `ModelsPage.kt`,
`SettingsPage.kt`, `SosPage.kt`, `RelayPage.kt`. The 0.6.1/code-15 refinement
replaces the SOS bottom sheet with a dedicated voice-first page and original
radio branding; see [SOS_FLOW.md](SOS_FLOW.md).
Added logic: `core/{ConnectionChoice,DownloadRules,SosConfirmation,RelayPacket,RelayLedger}.kt`,
`transport/WifiDirectGroups.kt`, `lan/ConversationNetworks.kt`,
`models/ModelDownloads.kt`, `relay/{RelayIdentity,BleRelayLink,RelaySession}.kt`.
Added focused tests for those pure connection/download/SOS/relay rules.
Added assets: `model-catalogue.json` and ten authored `emergency/<language>.json`
template files. Non-English templates explicitly await native-speaker review.

Updated handoff pointers in `CONTINUE_HERE.md`, `README.md`,
`docs/PROJECT_HANDOFF.md` and `DECISIONS.md`. Existing unrelated dirty files,
model source/pack archives, dependency locks, VAD/audio engines and protocol
schemas were not reset or overwritten. No Git commit was created.
