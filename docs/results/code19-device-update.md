# Code 19 APK refresh and device update

19 September 2026, approximately 12:00 IST. User explicitly requested refreshing
both APK variants and updating the app on connected devices.

## APKs

Both `:app:assembleBaseDebug` and `:app:assembleDemoPreloadedDebug` completed
offline with exit code 0. Both APKs remain version `0.7.1-hands-free-ui`, code 19,
package `org.itantra.app`, arm64-v8a, minSdk 26. No application-source change was
needed; hashes match the previously verified code-19 artifacts.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 66,495,697 | `de27f51f71abb5b06ac722a07df812a841dc1781b941ba6df7679a2305809c1a` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 451,012,746 | `4862d23572ba4e9360608dd80a7344858f2cc1f77ff33c5442d6329033508a40` |

Both APK signatures verified with `apksigner`, using the same existing Android
debug certificate. Both include VAD and the eSpeak GPL text. Base has no ASR
packs; preloaded includes exactly English Small, English Tiny and Hindi packs.
The prior [121-test/lint verification](hands-free-ui-code19.md) still describes
this unchanged application source; this turn rebuilt and checked the APKs.

## Connected device

Only one device was available: RMX1801, serial `<test-phone>`, Android API 29 with
arm64-v8a support. It initially appeared offline, then unauthorized after an ADB
reconnect. Installation began only after it appeared authorized as `device`.

The installed code-17 app was the preloaded variant (451,613,418-byte APK), so
the same variant was retained. `/data` had approximately 1.5 GB free before
installation. The in-place command succeeded:

```text
adb -s <test-phone> install -r app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk
Performing Streamed Install
Success
```

Package Manager confirmed code 19, version `0.7.1-hands-free-ui`, with
`lastUpdateTime=2026-09-19 12:00:06`. No uninstall, clear-data, downgrade,
permission auto-grant or model deletion was performed.

## Data preservation and startup

Before and immediately after replacement (before launching), eight files had
identical SHA-256 hashes:

- Active English, Hindi and Odia selection pointers.
- Installed English Small and Tiny profile pointers.
- The settings DataStore file.
- The message database and its write-ahead log.

Model directories were retained; full model payloads were not rehashed on-device
as part of this update. The active language pointers and settings also remained
identical after launch.

`am start -W -n org.itantra.app/.MainActivity` returned `Status: ok` and a successful
cold launch. A subsequent check found process 9337 alive and MainActivity resumed.
The inspected 200-line app-process log slice had no matching fatal exception,
AndroidRuntime crash, fatal signal or ANR. No speech foreground service appeared
in the subsequent service query.

No UI controls were tapped, no settings changed, and no microphone, hands-free
session or speech playback was explicitly started. This is installation/startup
verification, not visual-layout, speech-quality, audio or two-phone acceptance.
No second connected device was present at the final enumeration. Both APKs remain
available for installing on additional phones later.
