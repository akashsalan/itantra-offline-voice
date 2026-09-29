# Two English speech-recognition choices

17 September 2026 — **0.6.3-english-options / code 17**.

Requested addition, not a replacement:

| App label | Recognizer | Model files | Installed payload with license | Pack |
|---|---|---:|---:|---:|
| English | Moonshine Small Streaming | 142,300,974 B | 142,315,154 B | 142,339,040 B |
| English — low-end devices | Moonshine Tiny Streaming | 45,233,659 B | 45,247,839 B | 45,256,959 B |

Sizes above are exact bytes; 45.23 MB uses decimal MB. Tiny is a size/speed-oriented
alternative, not a guarantee of responsiveness or accuracy on every low-end phone.
No microphone/accent/noise accuracy comparison or device-class benchmark is implied.
Both choices use the existing English eSpeak NG voice and wire language `en`.

## Selection and preservation

Talk's language menu and Models show both profiles. Select the desired installed
profile without importing again. Only one heavy recognizer is loaded; the old
recognizer closes before loading the new one. Activation occurs after successful
load. An explicit failed switch restores the prior selection/recognizer.

Preloaded bundles Small, Tiny and unchanged Hindi. Small is the fresh-install
default. Adding Tiny does not change an existing Small or Tiny selection. The
English profile pointer is persisted across process restart. Per-profile deletion
cannot remove the other English profile, and a deleted bundled profile is not
silently reinstalled next launch. Existing per-language opt-outs are respected.
Existing Parakeet remains supported for recovery and is never deleted by update.
The base APK contains no ASR packs and accepts either English pack via offline import.

No database migration, new permission, dependency, TTS, transport, encryption,
group/chat, emergency, PTT endpoint or idle microphone change. Selecting Tiny
changes the diagnostics model name/size, not language or recipient routing.

## Provenance and packaging

Tiny source: `https://download.moonshine.ai/model/tiny-streaming-en/quantized_26_08_21/`.
Publisher metadata pinned at Moonshine commit
`234f60faa0eb388b01cdf7e60aca232af37aefda` supplies size and CRC32C; acquisition
checks both, then computes SHA-256 values pinned in `models/models.lock.json`.
These are locally calculated SHA values, not a claim of publisher signatures.
The publisher's full license is packaged; English streaming weights and first-party
runtime use MIT. Tiny reuses the existing isolated Moonshine 0.1.5 arm64 AAR,
with architecture ID 2 (Small uses 4). No additional native runtime is bundled.
See [runtime isolation and third-party notices](MOONSHINE_SMALL_STREAMING.md).

Pack: `models/packs/en-low-end.itpack`.
ID: `asr.moonshine.en.tiny.streaming.2026-08-21.quantized`.
SHA-256: `f08bb49604dc6c4948ea648f17d07e92810f9eb31ccbc9367ce0e64314036979`.

Acquire: `python scripts/prepare-moonshine-small.py --variant tiny --model-only`.
Package: `./scripts/build-model-packs.ps1 -Languages en -EnglishVariant moonshine-tiny`.
The builder refuses overwriting an existing pack. Small and Hindi packs are unchanged.
The local catalogue records Tiny as an offline alternative, not an invented hosted
download. Models explains preloaded availability and manual `en-low-end.itpack` import.

Compared with code 16, preloaded stores one additional approximately 45.26 MB pack;
installation also extracts approximately 45.25 MB, plus small manifest/filesystem
overhead. Existing old models are retained, so their storage is not reclaimed.
Peak RAM and low-end responsiveness must not be inferred from model file size.

## Verification and recovery

See [focused checks](results/english-options-checks.md) for actual build/device results.
The unit checks cover distinct English identities, default/remembered selection,
streaming lifecycle and unchanged PTT endpoint rules. Device tests use disposable
model roots and preserve production pointers. No full regression/benchmark suite.

Baseline source/docs and code-16 APKs were copied to
`.tools/rollback-backups/moonshine-tiny-20260917-2037` before this addition.
Do not restore that entire snapshot over later user changes. Updating with
`adb install -r` preserves models and message history; do not uninstall or clear data.
