# English STT update - 2026-09-12

App 0.1.1-english-trial uses the unchanged local sherpa-onnx 1.13.8 AAR.
Hindi ASR and embedded eSpeak code are unchanged. No Wi-Fi Direct feature exists
yet. The user reported successful Hindi STT/TTS; that is manual feedback, not WER.

## Change and evidence

The supplied English 20M model drops opening words in both original bundled
human WAVs on Windows. The publisher's own example also omits the opening of
sample 0. Full precision, bulk input, model autodetection and tail padding did
not fix that. The new English Zipformer 2023-06-21 pack recovers those openings
with the same local runtime version and CPU/thread settings. See DECISIONS.md
and the raw reports in docs/results/english-stt-*-audit.json.

These are two fixed human recordings, not an accuracy corpus. Sample 1 is
16.715 seconds, longer than the app's microphone cap: it tests the adapter,
not capture. Synthetic eSpeak phrases still produce severe errors. Derived
hard-endpoint fixtures remove quiet samples and do not establish true word
boundaries. Neither a general accuracy claim nor Android inference performance
can be inferred from these checks.

## Outputs and phone trial

- APK: app/build/outputs/apk/debug/app-debug.apk
- Updated English: models/packs/en-v2.itpack (about 190 MB)
- Hindi: models/packs/hi.itpack (unchanged)
- Rollback: models/packs/en.itpack (original 20M)

Install the updated APK over the existing app without clearing its data.
Copy en-v2.itpack to the phone's Internal storage / Download, then use
Models / Import .itpack. The old app cannot import the new pack: update the APK
first. The loaded label should say English Zipformer 2023-06-21 (device trial).
Existing Hindi imports remain valid. Old English imports remain selectable by
re-importing the original pack, and are labelled legacy.

The phone was disconnected during this update; no new APK install, Android ASR
test or receiver connection is claimed. Reconnect USB with debugging authorized
to install, copy the pack and test live English. Compare spoken text with the
transcript and export measured JSON; include short utterances and immediate
release after the final word. Repeat Hindi as a regression check.

## Reproduce host checks

The diagnostic virtual environment uses sherpa-onnx==1.13.8 and numpy==2.2.6.
Reference WAVs are extracted from the supplied original English archive under
.tools/english-reference; generated synthetic fixtures stay under .tools.
Models, recordings and packs are not added to Git or the base APK.

Run scripts/audit-english-stt.py with the local Python environment:

    --candidate --model-dir models/source/asr/en --output docs/results/english-stt-baseline-audit.json
    --candidate --check-regression --model-dir models/source/asr/en-2023-06-21 --output docs/results/english-stt-candidate-audit.json

Use scripts/acquire-english-v2.ps1 to fetch/verify the four revision-pinned files,
scripts/build-model-packs.ps1 -Languages en to build the new pack once,
scripts/verify-packs.ps1 for all three pack integrity checks, and
scripts/verify-build.ps1 for APK, unit tests and lint. Existing pack outputs are
preserved; the builder refuses to overwrite them.
