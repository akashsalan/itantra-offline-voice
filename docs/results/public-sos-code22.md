# Public BLE SOS — executed checks, 22 September 2026

Build: **0.8.0-public-sos-preview / code 22**. Debug, ARM64, target SDK 36,
compile SDK 37. All commands used the installed Java 17/Gradle wrapper and
offline dependencies. No model downloads, phone updates or broadcasts occurred.

## Passed host checks

Final Gradle invocation exited 0:

```text
:app:testBaseDebugUnitTest
:app:lintBaseDebug
:app:assembleBaseDebug
:app:assembleDemoPreloadedDebug
:app:assembleBaseDebugAndroidTest
--offline --console=plain --quiet
```

- **163 JVM tests, 0 failures, 0 errors**. Includes 14 public relay codec/policy
  tests, 12 playback queue tests and 9 SOS confirmation tests. Other existing
  speech/permission/foreground/transport policy regression suites also passed.
- Simulated A -> B -> C -> D automatic forwarding, hop-3 receipt without further
  forwarding, duplicate/loop suppression, bounded retries, origin/global rate
  limits, signed expiry/tamper rejection, minimum-MTU fragmentation and separation
  from trusted-team frames.
- Public playback cannot use private relay consent. A new receiving session
  cannot automatically play a stale pending Room row from previous consent.
- SOS countdown invalidates on public epoch change; a private voice capture
  cannot be retargeted into a public broadcast.
- **Lint: 0 errors, 13 warnings.** Warning categories: OldTargetApi,
  UnusedAttribute, ConfigurationScreenWidthHeight, ChromeOsAbiSupport,
  CustomX509TrustManager, DataExtractionRules, UsableSpace and UseKtx. These are
  not represented as proof of real-radio or security acceptance.
- Both application APKs and the Android test APK assembled. Instrumentation was
  **compiled only**, not run. The test APK is 559,271 bytes.
- Both application APK signatures verify with APK Signature Scheme v2. Debug
  signer SHA-256 matches the old code-21 APK:
  `e5afd5495b6c381f88a6df4e7a0daafbc8efdce648bc8ac8be7b88a989429309`.
- SHA-256 compared **315 asset/native-library entries** between old and new
  preloaded APKs: **zero changed, zero added**. Speech packs, eSpeak data, VAD
  and native engines are unchanged. No extra speech SDK was added.
- Source whitespace check against this turn's complete `app/src` backup passed.
  The worktree already contained extensive unrelated changes; none were reset.

## Packaged output

`D:\SIH\iTantra_Public_BLE_SOS_2026-09-22\APKs`

| File | Bytes | SHA-256 |
| --- | ---: | --- |
| app-base-debug.apk | 66,595,516 | `981fb40455ef3ce12f70018e3e1aadb0d72867591e5cb5e0f3b4cd46ce27dd81` |
| app-demoPreloaded-debug.apk | 451,888,325 | `085bc2898f731cf446a91570fabd5be1882424d2df4b93254c06e12fbd0ed3c9` |

Packaged copies match the corresponding build output hashes. `aapt2` confirms
application ID `org.itantra.app`, version code 22, version name above, target 36
and ARM64 native code. The delivery folder includes README and SHA256SUMS.

Previous `iTantra_Delivery_2026-09-21` remains intact. Its base/preloaded APK
hashes are still `fb158703af4dd7992b4c7bcd7e1b6001170039f316ded4373583986a25ddd893`
and `f9aa39ec1031d20ceec6061ae2d539f5bc8f172dfdd6d92db8b77c73902b2b66`.
All 11 STT packs remain in that existing delivery kit; they were not recopied.

Pre-edit source snapshot:
`D:\SIH\.tools\rollback\public-sos-before-20260922`.
This captures the then-current dirty source, not an older Git commit.

## Not run / not claimed

Read-only `adb devices -l` found **no attached devices**. No installation, app
launch, microphone capture, physical GATT exchange, on-device visual verification,
screen-off/permission-revocation test or real multi-hop test was performed.

The [physical checklist](../PUBLIC_BLE_SOS.md) remains required. In particular,
three/four phones all within direct range do not prove a multi-hop chain. Public
alerts are readable and senders unverified; this is an experimental opt-in relay,
not a certified rescue network. Ten-language accuracy and source-release gates
remain unchanged and open.
