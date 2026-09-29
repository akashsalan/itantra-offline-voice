# iTantra model artifacts

Code 18 (`0.7.0-hands-free-preview`) keeps all speech weights and runtime AARs
unchanged. Silero now also serves explicit hands-free sessions; ordinary PTT idle
still does not capture audio. Native-speaker quality/model acceptance is deferred
by the user's 19 September instruction. Earlier PTT-only statements below are
historical. See ../docs/REQUIREMENTS_2026_09.md.

The files in `source/` are the local development artifacts described by `ITANTRA_PRD.md`.

App 0.5.2/code 13 bundles `vad/silero_vad.int8.onnx` in both APK variants for
PTT-only neural endpointing. The unchanged model is 212,860 bytes, SHA-256
`c36d490aff5ab924ca6c7aeec4d8f6bd3d22db6fa17611b9c5b17eae58ac3a20`.
`prepareAssets` verifies these values before packaging; the MIT notice is in
`licenses/silero-vad-MIT.txt`. No continuous idle microphone capture is enabled.

**Current (0.6.3/code 17): English defaults to Moonshine Small Streaming (MIT)**,
with a second **English — low-end devices** choice using Tiny Streaming (MIT).
Both are bundled with unchanged Hindi only in preloaded. `packs/en.itpack` is
142,339,040 bytes; installed payload including
license is 142,315,154 bytes. Immutable ID:
`asr.moonshine.en.small.streaming.2026-08-21.quantized`.
Run `python scripts/prepare-moonshine-small.py` for pinned acquisition and isolated
arm64 runtime preparation. The old Parakeet distribution is recoverable at
`archives/en-parakeet-before-small-20260917.itpack`; existing phone imports stay
loadable. All other language packs remain unchanged. See
[the current English integration](../docs/MOONSHINE_SMALL_STREAMING.md).

Tiny: `packs/en-low-end.itpack` is 45,256,959 bytes; its eight model files total
45,233,659 bytes (45.23 decimal MB), or 45,247,839 bytes including the license.
ID: `asr.moonshine.en.tiny.streaming.2026-08-21.quantized`.
SHA-256: `f08bb49604dc6c4948ea648f17d07e92810f9eb31ccbc9367ce0e64314036979`.
Acquire with `python scripts/prepare-moonshine-small.py --variant tiny --model-only`;
build only Tiny with `./scripts/build-model-packs.ps1 -Languages en -EnglishVariant moonshine-tiny`.
Tiny requires app 0.6.3 or newer, does not replace Small and uses the same English
eSpeak voice. See [English choices and limitations](../docs/ENGLISH_LOW_END.md).

Historical comparison acquisition (superseded):

New in app 0.5.0/code 11: two optional English choices alongside the current
Zipformer. `source/asr/en-parakeet-ctc/` contains the user-supplied Parakeet 110M
CTC INT8 export (CC-BY-4.0). `source/asr/en-moonshine-base/` contains the pinned
2026-02-27 quantized Base English ORT export (English MIT exception). Their
separate packs are `packs/en-parakeet.itpack` and `packs/en-moonshine-base.itpack`.
Import each once, then switch in Models or Talk without reimporting. Candidates
are not bundled in either APK and do not change TTS. Zipformer stays the default.
See [English comparison](../docs/ENGLISH_COMPARISON.md) for provenance, commands,
device locations and actual test evidence. No comparative microphone WER exists.

- asr/en/ retains the original English 20M transducer for rollback; local tests reproduced missing opening words.
- asr/en-2023-06-21/ contains the documented replacement for an English phone trial (190.18 MB payload). Use its INT8 encoder/joiner and FP32 decoder, pinned in the lock. Human-fixture host results improved; Android and accent/noise acceptance remain pending. See DECISIONS.md.
- `asr/{bn,gu,hi,kn,ml,mr,ta,te}/model.int8.onnx` are the Android-ready IndicConformer CTC packs.
- `asr/indic-tokens.txt` is shared by the eight ready Indic packs.
- `asr/or/source-model.nemo` is the original official Odia source checkpoint, preserved unchanged. It cannot run on Android directly.
- `asr/or-ctc-experimental/model.int8.onnx` is its local CTC-only, language-masked INT8 conversion. Import `packs/or-experimental.itpack` using app 0.3.3 or newer. Odia remains experimental pending native-speaker accuracy acceptance; see `docs/ODIA_EXPORT.md` and the actual result files.
- `vad/silero_vad.int8.onnx` is the endpointing/VAD model.
- Original compressed English source is retained under `archives/asr/en/`.

See models.lock.json for sizes, hashes, sources and readiness status. Conversion and synthetic-fixture tests do not establish ten-language accuracy acceptance.

Build only the updated English pack:
powershell -File scripts/build-model-packs.ps1 -Languages en
Output: models/packs/en.itpack (Moonshine Small Streaming); requires app 0.6.2 or newer.
The old English 20M/Zipformer/Moonshine imports are retired. Hindi is unchanged.
