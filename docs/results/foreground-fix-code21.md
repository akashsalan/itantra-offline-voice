# Foreground-service repair: final code-21 verification

19 September 2026. User authorized fixing the reported microphone service error,
rebuilding both APK variants, and updating the connected phone. See
[diagnosis and implementation](../FOREGROUND_SERVICE_FIX.md).

## Final host checks

Java 17, offline Gradle command (exit 0):

```powershell
& 'D:\JAVA 17\bin\java.exe' '-Dorg.gradle.java.home=D:\JAVA 17' `
  -classpath 'D:\SIH\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain ':app:testBaseDebugUnitTest' `
  ':app:lintBaseDebug' ':app:assembleBaseDebug' `
  ':app:assembleDemoPreloadedDebug' ':app:assembleBaseDebugAndroidTest' `
  '--offline' '--console=plain' '--quiet'
```

- 145 JVM tests across 20 suites; zero failures, errors or skips.
- 24 foreground gate/demand/visibility tests, including normal capture cleanup,
  overlapping stop/start, stale callbacks, promotion readiness, capability changes,
  first-error preservation, explicit retry and overlapping Activity owners.
- Lint: zero errors, 15 warnings. Android test APK compiled; instrumentation was
  not executed in this turn.
- Both final APKs passed signature verification. `aapt` confirmed package
  `org.itantra.app`, version `0.7.3-foreground-fix`, code 21, minSdk 26,
  targetSdk 36, arm64-v8a.

| APK | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 65,722,118 | `fb158703af4dd7992b4c7bcd7e1b6001170039f316ded4373583986a25ddd893` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 451,012,742 | `f9aa39ec1031d20ceec6061ae2d539f5bc8f172dfdd6d92db8b77c73902b2b66` |

All 312 base-APK `assets/` and `lib/` entries have the same individual SHA-256
hashes and membership as the backed-up code-19 base APK. No native/model payload
was changed. The final preloaded APK contains exactly these starter packs:

- English low-end/Tiny: 45,256,959 bytes.
- English Small: 142,339,040 bytes.
- Hindi: 197,694,194 bytes.

Embedded eSpeak GPL text and Silero VAD remain present.

## Installation and preserved data

Only one authorized phone was attached: RMX1801, serial `<test-phone>`, Android API 29.
The preloaded variant was retained and updated in place:

```text
adb -s <test-phone> install -r app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk
Performing Streamed Install
Success
```

Package Manager confirmed code 21 and `0.7.3-foreground-fix`, with
`lastUpdateTime=2026-09-19 12:18:34`. The installed `base.apk` SHA-256 exactly
matches the final preloaded artifact above. No uninstall, data clearing, model
deletion, permission auto-grant or downgrade was used.

Immediately before and after the final update, before launching, all eight
checked files matched byte-for-byte by SHA-256:

- Active English, Hindi and Odia selection pointers.
- Installed English Small and Tiny profile pointers.
- Settings DataStore file.
- Message database and its write-ahead log.

Full on-device model-weight files were not rehashed; the update did not remove
their directories. An unsent, in-memory Talk draft seen before the final restart
was backed up locally to the ignored private file
`.tools/rollback-backups/foreground-fix-20260919-120902/unsent-talk-draft.json`.
It was not sent or restored into the new process, and its text is not included
in this report. Selected pre-edit source files and both code-19 APKs are also
retained in that rollback folder.

## Startup and physical-test limits

`am start -W -n org.itantra.app/.MainActivity` returned `Status: ok`, cold launch,
with 4,249 ms reported total time. MainActivity was resumed and process 14119
remained alive at the subsequent read-only checks. The inspected process log
query had no `AndroidRuntime` error or `ItantraForeground` failure. Service
inspection showed no active speech foreground service while idle. The inspected
UI hierarchy had no matching reported foreground-error text and showed
"Not connected". This is startup verification, not evidence of audio success.

An intermediate code-20 APK was installed during development. Read-only checks
observed a user-initiated microphone session and an unsent draft, but also found
that its newly added manual visibility guard could reject a press on visible
Talk. Code 21 removes that manual guard and fixes Activity-owner bookkeeping;
the final build and 145-test run include these corrections. The intermediate
observation is not claimed as a final code-21 speech acceptance test.

The user was asked separately for permission to run brief microphone tests.
No answer was received, and no automated microphone, hands-free or playback
test was initiated. PTT transcription, repeated presses, hands-free start/mute/
resume/end, screen transitions, and two-phone delivery remain physical regression
checks. Android background/permission restrictions still apply.

Milestone 4 (native-speaker/model-quality study), Odia acceptance, full-duplex
calling limitations and corresponding-source release gates are unchanged.
