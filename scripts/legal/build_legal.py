"""Build LEGAL.pdf: a plain-language, source-linked licensing note for iTantra.

Run from anywhere: python scripts/legal/build_legal.py [--output PATH]
Reads the current debug APKs and models/models.lock.json; does not change the app.
"""

from pathlib import Path
from xml.sax.saxutils import escape
import argparse
import json
import zipfile

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas as rl_canvas
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
)
from pypdf import PdfReader


# scripts/legal/build_legal.py -> parents[2] is the repository root.
ROOT = Path(__file__).resolve().parents[2]
APP_VERSION = "0.9.1-emergency-alert"
APP_CODE = 24
UPDATED = "24 September 2026"

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=ROOT / "docs" / "legal" / "LEGAL.pdf")
OUTPUT = parser.parse_args().output.resolve()
OUTPUT.parent.mkdir(parents=True, exist_ok=True)

# ---- Measured facts (fail loudly if the inputs drift) ---------------------------------
BASE_APK = ROOT / "app/build/outputs/apk/base/debug/app-base-debug.apk"
PRELOADED_APK = ROOT / "app/build/outputs/apk/demoPreloaded/debug/app-demoPreloaded-debug.apk"
LOCK = ROOT / "models" / "models.lock.json"
for required_input in (BASE_APK, PRELOADED_APK, LOCK, ROOT / "LICENSE", ROOT / "THIRD_PARTY_NOTICES.md"):
    assert required_input.is_file(), f"Missing input: {required_input.relative_to(ROOT)}"

BASE_MB = BASE_APK.stat().st_size / 1_000_000
PRELOADED_MB = PRELOADED_APK.stat().st_size / 1_000_000
with zipfile.ZipFile(BASE_APK) as apk:
    names = {item.filename: item.file_size for item in apk.infolist()}
ESPEAK_BYTES = sum(size for name, size in names.items()
                   if name.startswith("assets/espeak-ng-data/")
                   or name == "lib/arm64-v8a/libitantra_espeak.so")
assert ESPEAK_BYTES == 3_239_455, "Recheck the documented eSpeak footprint"
assert "lib/arm64-v8a/libonnxruntime.so" in names, "sherpa ORT missing from base APK"
assert not any(n.startswith("assets/starter-voices/") for n in names), "Base must bundle no voices"
with zipfile.ZipFile(PRELOADED_APK) as apk:
    preloaded_assets = {Path(n).name for n in apk.namelist()
                        if n.startswith(("assets/starter-packs/", "assets/starter-voices/"))}
assert preloaded_assets == {"en.itpack", "en-low-end.itpack", "hi.itpack",
                            "en-voice.itpack", "hi-voice.itpack"}, preloaded_assets

artifacts = json.loads(LOCK.read_text(encoding="utf-8"))["artifacts"]


def lock_mb(prefix):
    total = sum(a["bytes"] for a in artifacts if a["id"].startswith(prefix))
    assert total, f"No lock entries for {prefix}"
    return total / 1_000_000


SMALL_MB = lock_mb("asr.en.moonshine.small.streaming.")
TINY_MB = lock_mb("asr.en.moonshine.tiny.streaming.")
INDIC_MB = lock_mb("asr.hi.indicconformer.int8") + lock_mb("asr.indicconformer.shared_tokens")
VOICE_MB = lock_mb("tts.indictts.hi.")
VOICE_OR_MB = lock_mb("tts.indictts.or.")
VAD_KB = lock_mb("vad.silero.int8") * 1000

# ---- Fonts and styles --------------------------------------------------------------------
pdfmetrics.registerFont(TTFont("Segoe", r"C:\Windows\Fonts\segoeui.ttf"))
pdfmetrics.registerFont(TTFont("Segoe-Bold", r"C:\Windows\Fonts\segoeuib.ttf"))
pdfmetrics.registerFontFamily("Segoe", normal="Segoe", bold="Segoe-Bold")

NAVY = colors.HexColor("#102A2E")
TEAL = colors.HexColor("#006B63")
MUTED = colors.HexColor("#526667")
LIGHT = colors.HexColor("#F3F7F6")
LINE = colors.HexColor("#D4E2DF")
WHITE = colors.white
WIDTH, HEIGHT = A4
MARGIN = 42
CONTENT = WIDTH - MARGIN * 2

STYLES = {
    "title": ParagraphStyle("title", fontName="Segoe-Bold", fontSize=26,
                            leading=30, textColor=NAVY, spaceAfter=4),
    "subtitle": ParagraphStyle("subtitle", fontName="Segoe", fontSize=12,
                               leading=16, textColor=MUTED, spaceAfter=7),
    "section": ParagraphStyle("section", fontName="Segoe-Bold", fontSize=12,
                              leading=16, textColor=TEAL, spaceBefore=9,
                              spaceAfter=5, keepWithNext=True),
    "body": ParagraphStyle("body", fontName="Segoe", fontSize=10.2,
                           leading=13.6, textColor=NAVY, spaceAfter=6),
    "small": ParagraphStyle("small", fontName="Segoe", fontSize=9,
                            leading=12.2, textColor=MUTED, spaceAfter=5),
    "cell": ParagraphStyle("cell", fontName="Segoe", fontSize=9,
                           leading=12, textColor=NAVY),
    "callout": ParagraphStyle("callout", fontName="Segoe", fontSize=10.3,
                              leading=14, textColor=NAVY, spaceAfter=5),
    "tablehead": ParagraphStyle("tablehead", fontName="Segoe-Bold", fontSize=8.8,
                                leading=11.5, textColor=WHITE),
    "reference": ParagraphStyle("reference", fontName="Segoe", fontSize=8.3,
                                leading=11.4, textColor=TEAL, spaceAfter=2),
}

REFERENCES = [
    ("Moonshine: pinned model and code licence",
     "https://github.com/moonshine-ai/moonshine/blob/234f60faa0eb388b01cdf7e60aca232af37aefda/LICENSE"),
    ("AI4Bharat: IndicConformer and MIT licence",
     "https://github.com/AI4Bharat/IndicConformerASR#license"),
    ("IndicConformer ONNX exports: model card",
     "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx"),
    ("AI4Bharat: original Odia model declaration",
     "https://huggingface.co/ai4bharat/indicconformer_stt_or_hybrid_ctc_rnnt_large"),
    ("AI4Bharat Indic-TTS: upstream repository",
     "https://github.com/AI4Bharat/Indic-TTS"),
    ("EchoBharat: int8 FastPitch conversions",
     "https://huggingface.co/RaunakSaha/echobharat-models"),
    ("eSpeak NG: upstream licence statement",
     "https://github.com/espeak-ng/espeak-ng#license-information"),
    ("Silero VAD: MIT licence",
     "https://github.com/snakers4/silero-vad/blob/master/LICENSE"),
    ("sherpa-onnx: Apache-2.0 licence",
     "https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE"),
    ("ONNX Runtime: MIT licence",
     "https://github.com/microsoft/onnxruntime/blob/main/LICENSE"),
    ("Protocol Buffers: BSD-3-Clause licence",
     "https://github.com/protocolbuffers/protobuf/blob/main/LICENSE"),
    ("GNU FAQ: linking through JNI",
     "https://www.gnu.org/licenses/gpl-faq.html.en#IfInterpreterIsGPL"),
    ("GPLv3: source, notices and distribution",
     "https://opensource.org/license/gpl-3-0"),
    ("MIT: full licence and OSI approval",
     "https://opensource.org/license/mit"),
    ("Apache-2.0: full licence and OSI approval",
     "https://opensource.org/license/apache-2-0"),
    ("BSD-3-Clause: full licence and OSI approval",
     "https://opensource.org/license/bsd-3-clause"),
]
HALF = len(REFERENCES) // 2


def p(text, style="body"):
    return Paragraph(text, STYLES[style])


def section(text):
    return p(text, "section")


def table(rows, widths, header=True):
    data = []
    for i, row in enumerate(rows):
        style = "tablehead" if header and i == 0 else "cell"
        data.append([p(value, style) for value in row])
    result = Table(data, colWidths=widths, hAlign="LEFT", repeatRows=1 if header else 0)
    commands = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LEFTPADDING", (0, 0), (-1, -1), 7),
        ("RIGHTPADDING", (0, 0), (-1, -1), 7),
        ("TOPPADDING", (0, 0), (-1, -1), 5),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 5),
        ("LINEBELOW", (0, 0), (-1, -1), 0.5, LINE),
    ]
    if header:
        commands.append(("BACKGROUND", (0, 0), (-1, 0), TEAL))
    start = 1 if header else 0
    for i in range(start, len(rows)):
        if (i - start) % 2 == 0:
            commands.append(("BACKGROUND", (0, i), (-1, i), LIGHT))
    result.setStyle(TableStyle(commands))
    return result


def note(text, style="small"):
    result = Table([[p(text, style)]], colWidths=[CONTENT], hAlign="LEFT")
    result.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, -1), LIGHT),
        ("LINEBEFORE", (0, 0), (0, -1), 2, TEAL),
        ("LEFTPADDING", (0, 0), (-1, -1), 11),
        ("RIGHTPADDING", (0, 0), (-1, -1), 10),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 3),
    ]))
    return result


class NumberedCanvas(rl_canvas.Canvas):
    """Draws the footer after layout so it can show 'page / total'."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self._pages = []

    def showPage(self):
        self._pages.append(dict(self.__dict__))
        self._startPage()

    def save(self):
        total = len(self._pages)
        for state in self._pages:
            self.__dict__.update(state)
            self._footer(total)
            super().showPage()
        super().save()

    def _footer(self, total):
        self.saveState()
        self.setStrokeColor(LINE)
        self.setLineWidth(0.6)
        self.line(MARGIN, 35, WIDTH - MARGIN, 35)
        self.setFont("Segoe", 8)
        self.setFillColor(MUTED)
        self.drawString(MARGIN, 23, f"iTantra / Team Sky  |  Updated {UPDATED}  |  "
                                    f"App {APP_VERSION} ({APP_CODE})")
        self.drawRightString(WIDTH - MARGIN, 23, f"{self._pageNumber} / {total}")
        self.restoreState()


story = [
    p("LEGAL AND OPEN-SOURCE LICENCES", "title"),
    p("iTantra: speech models, licences and offline deployment", "subtitle"),
    note("<b>iTantra is licensed GPL-3.0-or-later.</b><br/>"
         "The app links eSpeak NG, which is GPL-3.0-or-later, through JNI. The combined application is "
         "therefore distributed under GPL-3.0-or-later (see LICENSE). Every other component below keeps "
         "its own GPL-compatible licence and notices. [7, 12, 13]", "callout"),
    section("1. Offline by design"),
    p("STT turns speech into text, TTS reads text aloud and VAD detects speech and pauses. All of them run "
      "on the phone. There is <b>no hosted STT, TTS, translation or other cloud API</b>, and no audio or text "
      "is sent to a server. Messages travel only over local Wi-Fi, Wi-Fi Direct or Bluetooth links."),
    p("<b>Models are not bundled in the base APK.</b> Speech-recognition and neural-voice packs are imported "
      "from local storage (.itpack, SHA-256 checked against models/models.lock.json). The preloaded demo APK "
      "bundles English and Hindi STT plus the English and Hindi neural voices."),
    section("2. Speech components"),
    table([
        ["COMPONENT", "USE / LANGUAGES", "SIZE", "LICENCE"],
        ["<b>Moonshine Small Streaming</b>", "STT: English default.",
         f"{SMALL_MB:.2f} MB", "MIT (Section 1 of the pinned publisher LICENSE covers all streaming models). [1]"],
        ["<b>Moonshine Tiny Streaming</b>", "STT: smaller English option for low-end devices.",
         f"{TINY_MB:.2f} MB", "MIT, same publisher commit and licence as Small. [1]"],
        ["<b>AI4Bharat IndicConformer</b> (INT8)", "STT: Bengali, Gujarati, Hindi, Kannada, Malayalam, "
         "Marathi, Odia*, Tamil, Telugu.", f"{INDIC_MB:.2f} MB per language",
         "Original AI4Bharat models: MIT. The community ONNX export repository declares Apache-2.0. "
         "Both notices are retained. [2-4]"],
        ["<b>AI4Bharat Indic-TTS</b> FastPitch + HiFi-GAN V1 neural voices",
         "Optional TTS packs for all ten languages. Acoustic: EchoBharat int8 conversion. "
         "Vocoder: re-exported here as float32.",
         f"{VOICE_MB:.2f} MB per language (Odia {VOICE_OR_MB:.2f} MB)",
         "MIT <b>as declared by the EchoBharat publisher</b> (licenses/indictts-echobharat-LICENSE.txt). "
         "The upstream AI4Bharat/Indic-TTS repository ships no LICENSE file, so the MIT term is not "
         "confirmed upstream and must be verified before release. Both conversions are declared "
         "modifications. [5, 6]"],
        ["<b>eSpeak NG</b> 1.53.0", "Built-in TTS for all ten languages: default, fallback and "
         "emergency voice. Formant synthesis.", f"{ESPEAK_BYTES / 1_000_000:.2f} MB total",
         "GPL-3.0-or-later; separately licensed files keep COPYING.APACHE, COPYING.BSD2 and "
         "COPYING.UCD. [7]"],
        ["<b>Silero VAD</b> (INT8)", "Speech/pause detection for push-to-talk and hands-free.",
         f"{VAD_KB:.0f} KB", "MIT, Copyright (c) 2020-present Silero Team. [8]"],
    ], [112, 140, 70, CONTENT - 322]),
    Spacer(1, 5),
    p("*Odia STT is a local CTC export of the MIT model and remains experimental; the Odia voice is a "
      "locally exported split graph. Export-tool licences (AI4Bharat NeMo fork, Apache-2.0) are host-only "
      "and not bundled. Accuracy and voice quality are not yet validated.", "small"),
    p("Sizes use decimal MB/KB and are summed from models/models.lock.json (model files, tokens and "
      "configuration). The eSpeak total is the native engine plus uncompressed voice data. None of these "
      "is a RAM measurement.", "small"),
    section("3. Runtime and app libraries"),
    table([
        ["COMPONENT", "ROLE", "LICENCE"],
        ["<b>sherpa-onnx</b> 1.13.8 AAR", "Runs IndicConformer STT and Silero VAD.", "Apache-2.0. [9]"],
        ["<b>ONNX Runtime</b> 1.28.2", "The libonnxruntime.so inside the sherpa-onnx AAR. The neural-voice "
         "bridge (libitantra_tts.so, app code) loads this same library; <b>no second runtime is packaged "
         "for neural voices</b>.", "MIT. [10]"],
        ["<b>Moonshine Android runtime</b> 0.1.5", "Runs Moonshine STT. Repackaged for arm64 with its own "
         "isolated ORT 1.23.2 renamed libmoonort.so; bundled deps: cpp-annote, Eigen (MPL-2.0 subset), "
         "kaldi-native-fbank, KISS FFT, nlohmann/json, utf8, utf8proc.",
         "MIT runtime and ORT; dependency licences (MIT, MPL-2.0, Apache-2.0, BSD-3-Clause, BSL-1.0, "
         "Unicode) in licenses/moonshine-runtime/. [1, 10]"],
        ["<b>Protocol Buffers</b> javalite 4.36.1", "Wire format for messages.", "BSD-3-Clause. [11]"],
        ["<b>AndroidX, Jetpack Compose, Room, Kotlin, coroutines</b>", "App UI, storage and language runtime.",
         "Apache-2.0; notices from resolved artifacts."],
        ["<b>Android platform APIs</b>", "NSD, sockets, TLS/Conscrypt, AndroidKeyStore, BLE, Wi-Fi Direct.",
         "OS-provided; nothing extra bundled."],
    ], [140, 215, CONTENT - 355]),
    Spacer(1, 5),
    p("<b>Legacy recovery only:</b> an English Parakeet 110M pack imported by an earlier build stays loadable "
      "so existing phones are not broken (CC BY 4.0 weights; licenses/parakeet-CC-BY-4.0.txt). It is not "
      "bundled or offered as a choice. Zipformer and Moonshine Base English packs are retired and the app "
      "refuses to activate them.", "small"),
    section("4. What is inside each app"),
    table([
        ["CURRENT DEBUG APK", "FILE SIZE", "BUNDLED SPEECH COMPONENTS"],
        ["<b>Base</b>", f"{BASE_MB:.2f} MB", "eSpeak NG (all ten languages), Silero VAD and the runtimes. "
         "No STT models and no neural voices; import packs separately."],
        ["<b>Preloaded</b>", f"{PRELOADED_MB:.2f} MB", "Everything in base, plus English Small, English Tiny "
         "and Hindi STT, and the English and Hindi neural voices."],
    ], [112, 70, CONTENT - 182]),
    section("5. Licence foundations"),
    p("All four are open-source licences approved by the Open Source Initiative. [13-16]", "small"),
    p("<b>MIT:</b> permits reuse with the copyright and licence notices retained. [14]<br/>"
      "<b>BSD-3-Clause:</b> permits reuse with notices retained and without implying author endorsement. [16]<br/>"
      "<b>Apache-2.0:</b> permits reuse with the licence, required notices and records of changed files. [15]<br/>"
      "<b>GPLv3:</b> permits reuse while preserving source access and recipients' rights when distributing "
      "covered software. This is called <b>copyleft</b>. [13]"),
    section("6. Why the whole app is GPL-3.0-or-later"),
    p("iTantra links eSpeak NG as a native library called through JNI. The Free Software Foundation treats "
      "this as one combined program, so distribution conditions apply to the whole app even though the eSpeak "
      "library itself is unchanged. The project LICENSE is therefore GPL-3.0-or-later. MIT, BSD and Apache-2.0 "
      "components are compatible and keep their own notices. [12, 13]"),
    p("<b>Source access.</b> Each distributed APK must be accompanied by the complete corresponding source: "
      "app and native code, the exact eSpeak NG source and voice-data inputs, the neural-voice export scripts, "
      "required dependency source and build instructions, at no extra charge (or included in USB handoffs). "
      "A repository URL alone does not discharge this. [13]"),
    p("<b>Attribution.</b> THIRD_PARTY_NOTICES.md and the licenses/ folder ship in the APK assets and in each "
      "model pack, with the full publisher licence texts, credits and modification records. No endorsement "
      "by any upstream author is implied. [13-16]"),
    section("7. Release documentation status"),
    table([
        ["DOCUMENTATION", "CURRENT STATUS"],
        ["<b>Recorded in the project</b>", "Pinned model versions, sizes, SHA-256 hashes, declared licences and "
         "third-party credits (models/models.lock.json, THIRD_PARTY_NOTICES.md, licenses/)."],
        ["<b>Pending release verification</b>", "Complete corresponding-source package; exact eSpeak NG "
         "acquisition revision; upstream confirmation of the AI4Bharat Indic-TTS MIT term; full resolved "
         "AndroidX/Kotlin notice inventory."],
    ], [150, CONTENT - 150]),
    Spacer(1, 5),
    p("<b>Licence basis:</b> existing licence terms apply as published; no separate exception is assumed. "
      "This overview records the release plan, not a completed compliance audit.", "small"),
    section("Sources - click a reference to open"),
]

reference_cells = []
for offset in range(HALF):
    row = []
    for index in (offset, offset + HALF):
        label, url = REFERENCES[index]
        row.append(p(f'<link href="{escape(url)}" color="#006B63">'
                     f'[{index + 1}] {escape(label)}</link>', "reference"))
    reference_cells.append(row)
refs = Table(reference_cells, colWidths=[CONTENT / 2, CONTENT / 2], hAlign="LEFT")
refs.setStyle(TableStyle([
    ("VALIGN", (0, 0), (-1, -1), "TOP"),
    ("LEFTPADDING", (0, 0), (-1, -1), 0),
    ("RIGHTPADDING", (0, 0), (-1, -1), 9),
    ("TOPPADDING", (0, 0), (-1, -1), 0),
    ("BOTTOMPADDING", (0, 0), (-1, -1), 1),
]))
story.extend([
    refs,
    Spacer(1, 5),
    p("Evidence: current debug APKs, models/models.lock.json, THIRD_PARTY_NOTICES.md, LICENSE, licenses/, "
      "docs/NEURAL_TTS.md and DECISIONS.md. This is general information, not legal advice or SIH approval. "
      "Full licence terms apply. Model licences alone do not prove rights to all training data.", "small"),
])

doc = SimpleDocTemplate(
    str(OUTPUT), pagesize=A4, rightMargin=MARGIN, leftMargin=MARGIN,
    topMargin=34, bottomMargin=45, title="iTantra - Legal and Open-source Licences",
    author="Team Sky", subject="Licences of bundled and importable components, sizes and release obligations",
    creator="iTantra project documentation", pageCompression=1,
)
doc.build(story, canvasmaker=NumberedCanvas)

reader = PdfReader(str(OUTPUT))
all_text = "\n".join(page.extract_text() for page in reader.pages)
summary = {
    "file": str(OUTPUT.relative_to(ROOT)) if OUTPUT.is_relative_to(ROOT) else OUTPUT.name,
    "pages": len(reader.pages), "bytes": OUTPUT.stat().st_size,
    "words_per_page": [len(page.extract_text().split()) for page in reader.pages],
    "links": sum(len(page.get("/Annots", [])) for page in reader.pages),
}
print(json.dumps(summary, indent=2))
assert summary["links"] == len(REFERENCES), "Reference links missing"
for required in ["Moonshine Small", "Moonshine Tiny", "IndicConformer", "Indic-TTS", "Silero VAD",
                 "eSpeak NG", "GPL-3.0-or-later", "sherpa-onnx", "1.28.2", "Protocol Buffers",
                 "no second runtime", "ships no LICENSE file", "Models are not bundled",
                 APP_VERSION, f"{BASE_MB:.2f} MB", f"{PRELOADED_MB:.2f} MB"]:
    assert required in all_text, f"Missing expected text: {required}"
for forbidden in ["0.6.3", "<test-phone>", "C:\\Users", "@gmail", "\ufffd"]:
    assert forbidden not in all_text, f"Unexpected text: {forbidden!r}"
