# Code 18 host verification — 19 September 2026

Version: `0.7.0-hands-free-preview`, version code 18. Implementation and remaining
device checklist: [requirements work](../REQUIREMENTS_2026_09.md).

## Executed checks

- Final offline Gradle run exited 0: base JVM tests, base lint, base APK,
  demoPreloaded APK and base instrumentation APK compilation.
- **107 JVM tests / 18 suites; 0 failures, errors or skips.** This is 19 new tests
  beyond the audited 88: hands-free segmentation/consent/emergency policy (10),
  CPU window arithmetic (3), permission boundary recovery (4), alert queue/restart (2).
- **Lint: 0 errors, 15 warnings.** The five audited MissingPermission errors are
  fixed. One intermediate Compose state-observation error introduced during the
  update was corrected before the successful final run; it was not suppressed.
- The new isolated Room consent-boundary instrumentation test compiles. It has NOT
  been executed on a device. Neither the existing Odia parity failure nor any
  language-quality status was changed or reclassified.
- Both APK manifests report code 18 / the version above. Both remain arm64-only.
  Base contains no .itpack assets. Preloaded contains exactly en.itpack,
  en-low-end.itpack and hi.itpack. The full GPLv3 text is present in both APKs.
- Git diff whitespace checks and the source-checkpoint comparison reported no
  whitespace errors. Unrelated pre-existing work was preserved.
- The source-preview packaging script was executed successfully after correcting
  an obsolete manifest input and explicitly loading PowerShell's ZIP assembly.
  ZIP validation rehashed all 2,849 manifest-listed source files: 0 mismatches and
  0 forbidden build/model/key/local.properties entries.

Command (from D:\SIH):

```powershell
& 'D:\JAVA 17\bin\java.exe' '-Dorg.gradle.java.home=D:\JAVA 17' `
  -classpath 'D:\SIH\gradle\wrapper\gradle-wrapper.jar' `
  org.gradle.wrapper.GradleWrapperMain ':app:testBaseDebugUnitTest' `
  ':app:lintBaseDebug' ':app:assembleBaseDebug' `
  ':app:assembleDemoPreloadedDebug' ':app:assembleBaseDebugAndroidTest' `
  '--offline' '--console=plain' '--quiet'
```

Generated reports (subsequent builds may overwrite these):

- app/build/reports/tests/testBaseDebugUnitTest/index.html
- app/build/test-results/testBaseDebugUnitTest/TEST-*.xml
- app/build/reports/lint-results-baseDebug.html and .xml

## Artifacts

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| app/build/outputs/apk/base/debug/app-base-debug.apk | 66,386,766 | 594bb77b55c2684c2e78b1ad94f7d6cb06a14d7b8977c939cfb4e7a62cd66ce1 |
| app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk | 451,679,000 | d11bf47ee6059d12a8468a781d9503eca861b4e97397bea6e554fc94143767f7 |
| .tools/source-previews/itantra-code18-source-preview-20260919-114414.zip | 22,570,509 | 15fc4e268233809f58a3a1a7e5bbb350da2b7c99862b30b6d1fa9bf82982d672 |

The preview ZIP contains application/protocol/eSpeak sources, scripts, licence texts,
metadata and a source/APK hash manifest. It deliberately declares
`completeCorrespondingSource: false`; matching native dependency sources and the
independent rebuild remain [release gates](../RELEASE_CHECKLIST.md). It is not a
completed GPL distribution package. Nothing was uploaded or externally published.

## Phone state and external gates

No phone was attached initially. RMX1801 / <test-phone> connected later. Read-only ADB
inspection found code 17 (`0.6.3-english-options`) still installed and approximately
1,539,776 KiB available on /data. The user was asked to confirm that drafts are saved
and authorize an in-place update/non-microphone smoke tests. No update, app launch,
instrumentation execution, microphone capture or playback was performed in this
host-verification pass. Preserve models/history; do not uninstall or clear data.

Physical hands-free/permissions/audio-focus/SOS checks, two-phone offline radio tests,
low/mid-device performance and acoustic latency remain unverified. Milestone 4
(native-speaker quality evaluation) is expressly deferred by the user. Compilation
and deterministic tests do not establish those acceptance results.
