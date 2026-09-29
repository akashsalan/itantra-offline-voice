# Hands-free UI verification — code 19

Recorded 19 September 2026, 11:57 IST. Implementation scope and manual acceptance
checklist: [Hands-free UI](../HANDS_FREE_UI.md).

Subsequent update, approximately 12:00 IST: both APKs were rebuilt and the preloaded
APK was installed in-place on RMX1801, with data-hash preservation and a successful
startup check. See [device update](code19-device-update.md). The initial offline
device notes below are historical; full UI/audio acceptance remains pending.

## Host verification

Final command completed with exit code 0 after the last application-source change:

```powershell
& 'D:\JAVA 17\bin\java.exe' '-Dorg.gradle.java.home=D:\JAVA 17' `
  -classpath 'D:\SIH\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain ':app:testBaseDebugUnitTest' `
  ':app:lintBaseDebug' ':app:assembleBaseDebug' `
  ':app:assembleDemoPreloadedDebug' ':app:assembleBaseDebugAndroidTest' `
  '--offline' '--console=plain' '--quiet'
```

- 121 JVM tests in 19 suites: zero failures, errors or skipped tests.
- Includes 14 new `HandsFreePresentationTest` cases; one enumerates 1,024 boolean
  combinations of Start prerequisites. No microphone or network is used.
- Base Android lint: zero errors; 15 warnings. No lint finding in the new
  hands-free view/presentation or changed Talk screen. Existing warnings remain.
- Base and preloaded APKs built; the existing base instrumentation APK also
  assembled successfully (up-to-date). Instrumentation was **not executed**.
- `aapt dump badging` confirms the base APK package `org.itantra.app`, code 19,
  name `0.7.1-hands-free-ui`, minSdk 26, targetSdk 36 and arm64-v8a ABI.
- Both app ZIPs contain the full eSpeak GPLv3 licence. Base contains no ASR pack;
  preloaded contains exactly `en.itpack`, `en-low-end.itpack` and `hi.itpack`.
- All 312 `assets/` and `lib/` entries in the base APK were SHA-256 compared
  against the backed-up code-18 base APK: identical content, no entries added
  or missing. Speech payloads, model catalogue, licences and native binaries
  were not changed by this UI build.
- `git diff --check` passed. It reports only existing CRLF-normalization notices
  for `app/gradle.lockfile` and `docs/results/build-summary.json`.

Local reports:

- `app/build/reports/tests/testBaseDebugUnitTest/index.html`
- `app/build/test-results/testBaseDebugUnitTest/TEST-org.itantra.app.core.HandsFreePresentationTest.xml`
- `app/build/reports/lint-results-baseDebug.html`

## Artifacts

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/base/debug/app-base-debug.apk` | 66,495,697 | `de27f51f71abb5b06ac722a07df812a841dc1781b941ba6df7679a2305809c1a` |
| `app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk` | 451,012,746 | `4862d23572ba4e9360608dd80a7344858f2cc1f77ff33c5442d6329033508a40` |
| `app/build/outputs/apk/androidTest/base/debug/app-base-debug-androidTest.apk` | 559,271 | `fb8f12073cbb84fed3d567acf11641a9bfd3060cdce42981c07d28c27230f0e8` |

These are debug artifacts, not a production-release or complete GPL
corresponding-source distribution. The code-18 source-preview archive is an old
checkpoint and does not include these code-19 changes; it was not republished.

## Not verified on-device

ADB listed existing phone `<test-phone>` as **offline** at the final read-only check.
No AVD was configured and no emulator system image was found. No installation,
restart, mic permission grant, capture, playback, settings mutation or database
change was attempted on the phone.

Six isolated Compose preview functions compile, but were **not rendered**. There
are no new screenshots or visual-regression results. Phone-size/font-scale layout,
TalkBack, actual switch/action taps, rotation, background use, two-phone speech
turns and emergency regression remain manual acceptance work. Compilation and
presentation-unit tests are not evidence that those physical interactions passed.

The user-deferred milestone 4, measured speech quality, full-duplex/shared-floor
protocol work and complete corresponding-source release gates remain unchanged.

## Recovery

Pre-edit source files and both code-18 APKs are preserved at
`.tools/rollback-backups/hands-free-ui-20260919-115210/`.
No user changes were reverted and nothing was deleted, committed, uploaded or
published. Application code changes are limited to the Talk/call UI, authored
icons, cross-tab banner/navigation, pure presentation mapping/tests and version.
