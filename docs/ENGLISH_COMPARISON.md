# English speech-engine comparison — 13 September 2026

**Historical code-11 experiment, superseded by [ENGLISH_MODEL.md](ENGLISH_MODEL.md).**
The user selected Parakeet as the only English model in code 12. The chooser and
retired pack imports described below no longer exist in the current app. Old
pack files are preserved in `models/archives/english-retired-2026-09-13`.

iTantra 0.5.0-english-comparison (code 11) supports three English choices. This
does not replace the default or claim an accuracy winner. Inference is offline,
CPU/arm64, with the supplied sherpa-onnx 1.13.8 AAR. TTS stays embedded eSpeak NG.

## On the phone

The updated base APK preserves app data and the existing selected English pack.
The candidates are copied to **Internal storage / Download** for manual import:

- `itantra-en-parakeet.itpack`
- `itantra-en-moonshine-base.itpack`
- `itantra-en-v2.itpack` is the existing Zipformer download; it was not duplicated.

Open **Models → Import**, select a candidate and wait for percentage verification
and engine loading to finish. Import the second candidate the same way. Then
use **Models → English speech engines → Use**, or the **STT** dropdown on Talk.
An import selects its model; earlier imported English choices stay installed.
The legacy 20M model is also shown if registered/active from an earlier version.
Never re-import merely to switch. Only one recognizer is resident at a time.

Keep auto-send off while comparing so drafts are not sent accidentally. Use the
same quiet setting, phone distance and sentences for each engine. Review actual
words and the diagnostics under More. `asr_pack_id` is the selected model;
`last_asr_pack_id` identifies the model that produced the last timing measurement.
`model_load_ms`, `model_bytes`, `asr_inference_ms`, `asr_rtf`, process memory and
release-to-transcript timings are measured, not publisher benchmark numbers.
All engines follow the existing hold-to-speak / release-to-transcribe workflow.
Captures are limited to 15 seconds. Receiving language metadata and TTS do not
depend on the sender's English model selection.

Storage is very low on the current phone. The two archives need about 273 MB;
their private imported copies need another 273 MB. Free at least 1 GB before
extended testing. Do not delete app data to free space: that loses chats/packs.

## Exact artifacts

| Choice | Separate PC pack | Pack bytes | Engine |
| --- | --- | ---: | --- |
| Zipformer (current) | `models/packs/en-v2.itpack` | 190211192 | Online transducer |
| Parakeet 110M CTC | `models/packs/en-parakeet.itpack` | 131702060 | Offline NeMo CTC |
| Moonshine Base | `models/packs/en-moonshine-base.itpack` | 141336800 | Offline Moonshine encoder + merged decoder |

Both new packs include the publisher/license text. Every entry is allowlisted
and SHA-256/size pinned in `models/models.lock.json`; arbitrary model exports are
not accepted by renaming them. The base APK contains no ASR model. The optional
demoPreloaded APK still contains only Zipformer and Hindi, not these candidates.
Its startup does not overwrite an already-selected alternative English pack.

Parakeet is the user's local `sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8`
download. It uses the CTC export, not the TDT decoder. NVIDIA/Suno CC-BY-4.0
attribution is in its manifest and THIRD_PARTY_NOTICES.md. Published TDT scores
must not be presented as this CTC export's WER.

Moonshine is specifically `sherpa-onnx-moonshine-base-en-quantized-2026-02-27`,
publisher revision `8f4d6c58c03d40bcea40043bb7120a878f2bbef6`, from sherpa's
**Models v2** catalog. It is not the newer Streaming Small/Medium family.
The upstream LICENSE explicitly assigns English models MIT (Section 1); the
non-English Community terms are not applied to this English pack.

Sources and the model-selection decision are in DECISIONS.md. No change to
Hindi/Odia/other languages, transport protocols, group features or TTS is intended.

## Reproduce packaging and checks

From PowerShell in `D:\SIH`:

```powershell
$env:JAVA_HOME = 'D:\JAVA 17'
.\scripts\acquire-english-comparison.ps1
.\scripts\build-model-packs.ps1 -Languages en -EnglishVariant parakeet
.\scripts\build-model-packs.ps1 -Languages en -EnglishVariant moonshine-base
.\scripts\verify-packs.ps1
.\scripts\verify-build.ps1 -IncludePreloaded
```

The acquisition script verifies existing files; it does not overwrite a bad
download. The pack builder deliberately refuses to replace existing packs.
All 13 local packs (ten languages, legacy English and the two new choices) are
checked by `verify-packs.ps1`. Large binaries, including ORT files, are ignored
by Git. No Git commit/push is made as part of this task.

## Evidence boundaries

The fresh build has 55 passing JVM tests, zero lint errors and 19 warnings.
The device pack-registry suite has five passing tests for coexisting choices,
switch/reopen, old-pointer migration, invalid import rejection and corrupt-pack
activation rejection. These use tiny fixtures in a separate test directory.

Real-engine test logs: `results/android-english-comparison-tests.txt`. They run
the pinned human `0.wav` twice with reset, measure actual timings and PSS after
decode (not peak), and record a digital-silence control. Candidate archives are
read from Downloads into a temporary test-only store, never into production
model storage. Temporary test models are removed afterward. The existing
Zipformer test reads its installed pack without changing the active pointer.
Consult the log and per-engine JSON reports for completed outcomes, not merely
the fact that test classes compile. No microphone corpus WER, accent/noise
reliability, native-listener score or new two-phone result is claimed.

The first candidate test launch exposed a test-harness return-type error before
either candidate ran. It was fixed by making the JUnit methods return Unit;
`results/android-english-comparison-initial-harness-error.txt` preserves that run.
The subsequent test-helper update hit Android's low-storage reserve. Only the
newly transferred Moonshine download was temporarily removed, after verifying
its PC backup; it was restored immediately after installing the helper. No
existing user files or production app data were deleted.
