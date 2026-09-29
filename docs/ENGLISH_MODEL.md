# English STT — Moonshine Small Streaming selected

Current: **0.6.2-moonshine / code 16**, 17 September 2026. The user's new selection
is quantized Moonshine Small Streaming (MIT), integrated through Moonshine 0.1.5,
not sherpa's older Moonshine Base. See [the current record](MOONSHINE_SMALL_STREAMING.md)
for exact files, runtime isolation, PTT integration, recovery and focused checks.
eSpeak NG, non-English STT and communication protocols are unchanged.

## Historical Parakeet selection (superseded)

Current version: **0.5.1-english / code 12**, 13 September 2026.
The user tested Parakeet 110M on the phone, reported a strong improvement, and
selected it as the only English STT option. This supersedes ENGLISH_COMPARISON.md.
The report is not a measured corpus WER or a universal accuracy guarantee.

## User-facing behavior

- The language is simply **English**. There is no English-model chooser.
- Parakeet 110M CTC INT8 performs STT. Embedded eSpeak still performs English TTS.
- Hold to speak, transcript review, auto-send, incoming auto-play, language
  metadata, groups, chats and other languages are unchanged.
- The phone's already-imported Parakeet ID/directory is preserved. No reimport
  is needed after updating. Diagnostics retain the technical model ID for audit.
- Only the new English pack is accepted. Retired English imports give an
  explanatory error instead of silently changing recognition engines.

## Files

Current English pack on PC: `D:\SIH\models\packs\en.itpack` (131702085 bytes).
Phone: **Internal storage / Download / itantra-en.itpack**.
Pack SHA-256: `e9532d0a392fbd63b086e47bbd6bdc1cc69c5f213b4ba0ffb4d8526201435933`.
The internal pack ID remains `asr.parakeet.en.110m.ctc.int8`; only its display
label/distribution filename changed, not the model weights or tokens.

Base APK: `D:\SIH\app\build\outputs\apk\base\debug\app-base-debug.apk`.
Preloaded APK: `D:\SIH\app\build\outputs\apk\demoPreloaded\debug\app-demoPreloaded-debug.apk`.
Base has no ASR models. Preloaded contains only **English (Parakeet) and Hindi**.
All ten current language packs are in `D:\SIH\models\packs`.

The previous `en.itpack` was the old 20M model; it is NOT the current file of the
same name. The immutable manifest ID and pinned hashes distinguish them.
Superseded PC distributions (old 20M, Zipformer v2, Moonshine and the earlier
Parakeet comparison filename) were moved, hash-verified, into
`D:\SIH\models\archives\english-retired-2026-09-13`. They are recoverable and
excluded from Git/current distribution. Retired/duplicate phone downloads were
checked against those backups before removal and replaced by the one new file.
Original acquisition artifacts and license/provenance records are retained.

For older phones using a base APK, install this update and import the new English
pack before speaking English. Retired activation pointers remain parseable but
are not treated as a supported English installation. A preloaded install adds
the new English pack if no supported English model is installed.

## Build and test

```powershell
$env:JAVA_HOME = 'D:\JAVA 17'
.\scripts\build-model-packs.ps1 -Languages en
.\scripts\verify-packs.ps1
.\scripts\verify-build.ps1 -IncludePreloaded
```

The builder refuses to overwrite an existing pack. The lock pins the supplied
Parakeet model, tokens and CC-BY-4.0 text. Attribution to NVIDIA/Suno is in the
manifest and THIRD_PARTY_NOTICES.md. The local sherpa-onnx AAR remains 1.13.8.
No cloud API, external TTS application or new runtime dependency was introduced.

Build evidence: `results/build-summary.json`; archive integrity:
`results/pack-integrity.json`. Current Android results are in
`results/android-code12-tests.txt`, with a fixed human English recording,
isolated imports/progress, legacy-pointer handling, retired/corrupt pack
rejection, protocols/Room/TLS and embedded TTS regression checks. Test imports
use a temporary store and do not replace the user's active model.

Actual code-12 result: **14 of 15 Android tests passed**. English import/decode,
reset, silence, five pack-store tests, three legacy protocol/history tests, four
LAN/TLS tests and native ten-voice PCM synthesis passed. The additional Odia
desktop-parity test failed on its second synthetic fixture: the phone produced
a different Odia word sequence. The first fixture matched. It is recorded in
`results/odia-android-parity-failure.json`; this is not a native-speaker accuracy
score, and neither a cause nor a regression from the previous app is established.
Odia's pinned model/token files were not changed. Do not report the suite as all
passing or weaken its expected output to make it pass.

The final APK was installed and its SHA-256 matched the PC build; the renamed
phone pack also matches. See `results/english-final-device.json`. The Models
screen was visually checked on the actual phone: one English entry, 131.7 MB,
Selected and SHA-256 verified, with no English-engine chooser.
See `results/english-model-ui.png`. Cleanup removed the two installed Zipformer
directories; Parakeet/English, Hindi and Odia active pointers stayed unchanged.
`results/english-retirement-cleanup.json` records exact targets and recovery path.

Historical code-11 comparison: all three engines decoded the same 6.625-second
human recording twice and produced blank output for digital silence. Parakeet
took 1183.07/1116.01 ms (RTF 0.179/0.168), Moonshine 1595.28/1532.15 ms and
Zipformer 2218.17/2216.43 ms on RMX1801/API 29. This is a single fixture, not
corpus WER or an accent/noise benchmark. The reported process PSS was identical
across that run and must not be used to rank isolated model RAM.

The prior Odia smoke class had the same JUnit non-void return-type issue found
in the initial English harness. Its return type was corrected for regression
testing; no Odia model, label or acceptance status changed. Consult the current
runner log for the actual result, not merely compilation.
