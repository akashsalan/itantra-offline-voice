# Evidence index - 13 September 2026

Latest speech/model update, code 17: [English options checks](english-options-checks.md),
[Small/Tiny Models screen](english-options-models.png),
[Talk chooser](english-options-menu.png). This supersedes English selection and
packaging claims in older records, not their historical test results.

17 September visual refresh: [latest UI verification and APK hashes](visual-refresh-checks.md).
Code 15 was rebuilt without a version bump. See that report for the current
screenshots/artifacts; the code-12 measurements below remain historical evidence.

Current app: `0.5.1-english`, version code 12. This index supersedes the
initial no-phone/Phase-0-only status. See [project handoff](../PROJECT_HANDOFF.md)
for current implementation and [test plan](../test-plan.md) for remaining checks.
Preserve the distinction between host tests, device tests and user reports.

English is now Parakeet only, labelled English, following the user's successful
manual trial. [ENGLISH_MODEL.md](../ENGLISH_MODEL.md) is the current guide.
[android-code12-tests.txt](android-code12-tests.txt) records **14 passed / 1
failed**: English STT/import/reset/silence, pack safety/migration, protocol/TLS,
history and ten-voice native synthesis passed. The extra Odia parity test failed
on the second synthetic recording (phone and desktop produced different words).
[odia-android-parity-failure.json](odia-android-parity-failure.json) preserves the
partial run; no corpus accuracy or Odia regression cause is established.
Do not silently relabel this as an all-pass suite.

[english-selected-android.json](english-selected-android.json) contains the new
English measurements. [english-final-device.json](english-final-device.json)
verifies the installed APK and phone pack against PC hashes.
[english-retirement-cleanup.json](english-retirement-cleanup.json) records the
retired English directories removed after verifying Parakeet and PC backups.
[english-model-ui.png](english-model-ui.png) is the real-phone English-only UI.
Earlier three-engine comparison results remain historical, not active choices.

The code-10 navigation/layout inspection is recorded in
[connections-ui-check.md](connections-ui-check.md). The new Connections/Groups
landing page and name/password dialog were checked on the actual handset.

## Latest build and artifact checks

- [build-summary.json](build-summary.json) and [build.log](build.log) are generated
  by scripts/verify-build.ps1; consult its timestamp for the exact run.
  55 JVM tests, no failures/errors/skips; 0 lint errors, 19 warnings. Both debug
  variants and the instrumentation APK compiled. The JSON records APK sizes,
  hashes and native/model contents. Compilation does not run instrumentation.
- [pack-integrity.json](pack-integrity.json) records host verification of 10 pack
  files: ten languages, with English now Parakeet only. Hashes do not prove
  recognition quality or Android compatibility for every language.
- [Initial artifact audit](../../benchmark/artifact-audit.json) covers the initial
  16 source artifacts, not all later additions. It is not a current pack benchmark.
- Initial APK checks passed v2 signing, `zipalign -c -P 16 4`, and recorded 0x4000
  LOAD alignment in supplied arm64 sherpa/ONNX Runtime ELF files. These are earlier
  file checks, not proof of every Android device or a fresh signing/alignment
  check on every subsequent APK.

## Captured Android tests

[android-local-groups-tests.txt](android-local-groups-tests.txt) is the new
eight-test RMX1801/API 29 suite: three TLS/Room sessions on loopback, intentional
ACK loss/retry/dedup, group broadcast/DM isolation, password and direct consent,
saved group resume, TLS host pinning (including forced TLS 1.2), real Wi-Fi
interface discovery/message/ACK, plus legacy protocol/history and native eSpeak.
All endpoints run on one phone. The real-interface test passed with Wi-Fi
connected; it is designed to skip explicitly if no local interface is present.
It does not prove multi-phone radio delivery or simultaneous-speaker audibility.
See [LOCAL_GROUPS.md](../LOCAL_GROUPS.md) for exact scope and remaining tests.

[android-conversation-tests.txt](android-conversation-tests.txt) records an earlier
real RMX1801/API 29 run: four tests passed in 6.522 seconds. Coverage includes Room
v1-to-v2 history migration, persistent outbox/dedup, protocol retry and three ACK
states with silent chat, and native eSpeak PCM synthesis for ten voices with
engine reopening. The protocol link is in memory, not two physical radios.
Synthesis is not a native-listener intelligibility score.

[android-phase1-core-tests.txt](android-phase1-core-tests.txt) and
[android-tts-smoke.txt](android-tts-smoke.txt) are earlier device runs. Do not
present earlier tests as a fresh run against all changes in version 0.3.4.
The earlier ASR classes had no captured complete result. Code 12 now has a
passing English result and a failing Odia desktop-parity result as documented
above. No Odia full-success claim should be inferred from host measurements.

## Speech/model host evidence

- `english-stt-baseline-audit.json`, `english-stt-host-audit.json`,
  `english-stt-precision-audit.json` and `english-stt-candidate-audit.json` document
  fixed-fixture comparisons behind the English replacement. They are not a
  representative WER corpus or Android benchmark.
- [odia-export-environment.json](odia-export-environment.json) records the host
  export environment. [odia-export-host.json](odia-export-host.json) records the
  source/FP32 comparison and INT8 differences on two synthetic fixtures, silence
  behavior, hashes, load/decode/RTF and Windows process memory. Source/FP32 matched;
  not all quantized results matched. Host smoke passed, release validation did
  not; zero native-speaker sentences and no WER were reported.
- [host-voices.json](host-voices.json) is the earlier Windows CLI `123`-to-PCM
  smoke for all ten selected voice IDs. It is not Android or listening evidence.

## Manual reports and handoff inspection

The user reports Hindi STT/TTS works; replacement English works with some word
errors; and Odia works with apparently correct transcription. No consented audio
corpus, reference transcripts, scored WER or listening results were retained from
those informal trials.

Read-only ADB inspection for this documentation save confirmed RMX1801 serial
<test-phone> authorized, app code 8 installed and active English/Hindi/Odia model
pointers at the previous checkpoint. The new code-9 build was installed without
clearing data. The user has since reported successful legacy two-phone EN/HI and
Classic trials; new LAN multi-phone group acceptance remains pending. Only one
physical phone is attached here. Formal physical-radio measurements,
hands-free operation, full ten-language listening/accuracy and reference-device
performance acceptance remain unfinished. Do not fabricate missing results.
