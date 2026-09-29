"""Host smoke test for EchoBharat FastPitch + HiFi-GAN int8 ONNX.

Proves the tokenizer scheme, dynamic sequence axes and two-stage pipeline before
any Android work. Writes reference WAVs plus a JSON record.

Read-only with respect to the models. Run with:
  .tools/odia-export/Scripts/python.exe scripts/tts-host-smoke.py
"""
from __future__ import annotations

import json
import time
import wave
from pathlib import Path

import numpy as np
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[1]
TTS = ROOT / "models" / "source" / "tts" / "echobharat"
OUT = ROOT / ".tools" / "tts-host-smoke"
REPORT = ROOT / "docs" / "results" / "echobharat-tts-host.json"
SAMPLE_RATE = 22050

# Three lengths per language: the dynamo-exported graph must accept all of them.
TEXTS = {
    "hi": [
        "मुख्य सड़क बंद है।",
        "राहत शिविर स्कूल के पास है, वहाँ पानी और दवा उपलब्ध है।",
        "सभी टीमों को सूचित किया जाए कि पुल क्षतिग्रस्त है और वैकल्पिक मार्ग से आगे बढ़ें।",
    ],
    "en": [
        "The main road is blocked.",
        "The relief camp is near the school and has water and medicine.",
        "All teams should be informed that the bridge is damaged, so please proceed using the alternate route.",
    ],
    "te": ["ప్రధాన రహదారి మూసివేయబడింది."],
    "ta": ["முக்கிய சாலை மூடப்பட்டுள்ளது."],
    "bn": ["প্রধান সড়ক বন্ধ আছে।"],
    "gu": ["મુખ્ય રસ્તો બંધ છે."],
    "kn": ["ಮುಖ್ಯ ರಸ್ತೆ ಮುಚ್ಚಲಾಗಿದೆ."],
    "ml": ["പ്രധാന റോഡ് അടച്ചിരിക്കുന്നു."],
    "mr": ["मुख्य रस्ता बंद आहे."],
}


def load_tokens(lang: str) -> tuple[dict[str, int], int]:
    """Symbol -> id. First occurrence wins, matching the documented contract."""
    table = json.loads((TTS / lang / f"fastpitch-{lang}.tokens.json").read_text(encoding="utf-8"))
    mapping: dict[str, int] = {}
    for index, symbol in enumerate(table):
        mapping.setdefault(symbol, index)
    return mapping, len(table)


def tokenize(text: str, mapping: dict[str, int], blank: bool) -> tuple[list[int], list[str]]:
    """Longest-match over the symbol table. Returns ids and any unmapped pieces."""
    longest = max(len(s) for s in mapping)
    ids: list[int] = []
    missing: list[str] = []
    position = 0
    while position < len(text):
        for span in range(min(longest, len(text) - position), 0, -1):
            piece = text[position:position + span]
            if piece in mapping:
                if blank and ids:
                    ids.append(mapping["<BLNK>"])
                ids.append(mapping[piece])
                position += span
                break
        else:
            missing.append(text[position])
            position += 1
    return ids, missing


def write_wav(path: Path, audio: np.ndarray) -> float:
    pcm = np.clip(audio, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as handle:
        handle.setnchannels(1)
        handle.setsampwidth(2)
        handle.setframerate(SAMPLE_RATE)
        handle.writeframes(pcm.tobytes())
    return len(pcm) / SAMPLE_RATE


def session(path: Path) -> ort.InferenceSession:
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    return ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])


def synthesize(acoustic, vocoder, ids: list[int]) -> tuple[np.ndarray, dict]:
    tokens = np.asarray([ids], dtype=np.int64)
    start = time.perf_counter()
    mel = acoustic.run(["mel"], {"text": tokens})[0]
    mel_ms = (time.perf_counter() - start) * 1000.0
    start = time.perf_counter()
    audio = vocoder.run(["audio"], {"mel": mel})[0]
    voc_ms = (time.perf_counter() - start) * 1000.0
    return audio.reshape(-1), {
        "melShape": list(mel.shape),
        "acousticMs": round(mel_ms, 1),
        "vocoderMs": round(voc_ms, 1),
    }


def main() -> int:
    results: list[dict] = []
    for lang, sentences in TEXTS.items():
        mapping, table_size = load_tokens(lang)
        acoustic = session(TTS / lang / f"fastpitch-{lang}.int8.onnx")
        vocoder = session(TTS / lang / f"hifigan-{lang}.int8.onnx")
        load_note = {"lang": lang, "tokenTable": table_size}
        print(f"\n== {lang}  ({table_size} symbols)")

        for index, text in enumerate(sentences):
            for blank in (False, True):
                ids, missing = tokenize(text, mapping, blank)
                if not ids:
                    print(f"  !! no tokens for {text!r}")
                    continue
                audio, timing = synthesize(acoustic, vocoder, ids)
                tag = "blank" if blank else "plain"
                name = f"{lang}-{index}-{tag}.wav"
                seconds = write_wav(OUT / name, audio)
                total = timing["acousticMs"] + timing["vocoderMs"]
                rtf = (total / 1000.0) / seconds if seconds else 0.0
                peak = float(np.max(np.abs(audio))) if audio.size else 0.0
                record = {
                    **load_note,
                    "sentence": index,
                    "variant": tag,
                    "chars": len(text),
                    "tokens": len(ids),
                    "unmapped": missing,
                    "audioSeconds": round(seconds, 3),
                    "peakAmplitude": round(peak, 4),
                    "rtf": round(rtf, 3),
                    "wav": name,
                    **timing,
                }
                results.append(record)
                flag = "" if not missing else "  UNMAPPED=" + ascii(missing)
                print(f"  [{tag:5}] chars={len(text):3} tokens={len(ids):4} "
                      f"audio={seconds:5.2f}s rtf={rtf:5.2f} peak={peak:.3f}{flag}")

    # Does Telugu really share Malayalam's vocoder? Their files are byte-identical.
    print("\n== cross-vocoder check: te mel through ml vocoder ==")
    mapping, _ = load_tokens("te")
    ids, _ = tokenize(TEXTS["te"][0], mapping, False)
    te_acoustic = session(TTS / "te" / "fastpitch-te.int8.onnx")
    own, _ = synthesize(te_acoustic, session(TTS / "te" / "hifigan-te.int8.onnx"), ids)
    other, _ = synthesize(te_acoustic, session(TTS / "ml" / "hifigan-ml.int8.onnx"), ids)
    identical = bool(own.shape == other.shape and np.array_equal(own, other))
    print(f"  identical output: {identical}")
    write_wav(OUT / "te-via-ml-vocoder.wav", other)

    payload = {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "onnxruntime": ort.__version__,
        "modelSource": "RaunakSaha/echobharat-models",
        "modelRevision": "2ad314c3ecafeb252bac82785ac541325596ac9c",
        "sampleRate": SAMPLE_RATE,
        "wavDirectory": str(OUT.relative_to(ROOT)).replace("\\", "/"),
        "teleguSharesMalayalamVocoder": identical,
        "runs": results,
    }
    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nreport: {REPORT.relative_to(ROOT)}")
    print(f"wavs:   {OUT.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
