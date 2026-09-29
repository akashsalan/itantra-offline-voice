"""Acquire pinned Moonshine Small Streaming EN and isolate its Android ORT.

Host-only, standard-library tool. Does not replace sherpa's AAR or download
optional TTS/LLM/speaker models. Run from any directory with Python 3.
"""
import base64
import argparse
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = "234f60faa0eb388b01cdf7e60aca232af37aefda"
RAW = f"https://raw.githubusercontent.com/moonshine-ai/moonshine/{REVISION}"
CDN = "https://download.moonshine.ai/model/small-streaming-en/quantized_26_08_21"
VARIANT = "small"
MAVEN = "https://repo.maven.apache.org/maven2/ai/moonshine/moonshine-voice/0.1.5"
AAR_SHA = "ee2d95c21150683c743db8f3aef66281fd5408bcefc94be3ca1d2545ada1f571"
# Published metadata from core/moonshine-model-file-metadata.generated.cpp at REVISION.
FILES = {
    "adapter.ort": (2870368, "XlWjTg=="),
    "cross_kv.ort": (5356536, "5ySK8g=="),
    "decoder_kv.ort": (81878600, "Rn/VUA=="),
    "encoder.ort": (44148576, "41D2jQ=="),
    "frontend.model.ort": (26944, "WW2/2g=="),
    "frontend.weights.ort": (7769464, "9pROuQ=="),
    "streaming_config.json": (512, "dPbFiw=="),
    "tokenizer.bin": (249974, "B7s10Q=="),
}
TINY_FILES = {
    "adapter.ort": (1319664, "kwQ+Bw=="),
    "cross_kv.ort": (1287544, "76wzFQ=="),
    "decoder_kv.ort": (32583720, "KJjeNw=="),
    "encoder.ort": (7675440, "UjAIpQ=="),
    "frontend.model.ort": (23344, "aM/+wQ=="),
    "frontend.weights.ort": (2093464, "WYZ7EQ=="),
    "streaming_config.json": (509, "HGL0Ug=="),
    "tokenizer.bin": (249974, "B7s10Q=="),
}
NOTICES = {
    "cpp-annote-MIT.txt": "core/cpp-annote/LICENSE",
    "Eigen-MPL2.txt": "core/third-party/Eigen/COPYING.MPL2",
    "kaldi-native-fbank-Apache2.txt": "core/third-party/kaldi-native-fbank/LICENSE",
    "kissfft-COPYING.txt": "core/third-party/kissfft/COPYING",
    "nlohmann-MIT.txt": "core/third-party/nlohmann/LICENSE.MIT",
    "onnxruntime-MIT.txt": "core/third-party/onnxruntime/LICENSE.txt",
    "utf8-BSD.txt": "core/third-party/utf-8/LICENSE.txt",
    "utf8proc-LICENSE.txt": "core/third-party/utf8proc/LICENSE.md",
}


def acquire(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        partial = target.with_name(target.name + ".partial")
        # The public CDN rejects Python's urllib TLS client; use Windows curl.
        subprocess.run(["curl.exe", "--fail", "--location", "--silent", "--show-error",
                        "--retry", "2", "--output", str(partial), url], check=True)
        partial.rename(target)
    return target.read_bytes()


def crc32c(data):
    table = []
    for index in range(256):
        value = index
        for _ in range(8):
            value = (value >> 1) ^ (0x82F63B78 if value & 1 else 0)
        table.append(value)
    crc = 0xFFFFFFFF
    for value in data:
        crc = table[(crc ^ value) & 0xFF] ^ (crc >> 8)
    return base64.b64encode(struct.pack(">I", crc ^ 0xFFFFFFFF)).decode()


def isolate_ort(data, required):
    """Change only a complete .dynstr filename, not symbol names or ELF layout.

    DT_NEEDED / DT_SONAME / VERNEED entries retain their offsets. The shorter
    name is NUL-padded. The two ORTs retain different symbol-version namespaces.
    Refuse unknown architectures/layouts; upstream archive is SHA-pinned.
    """
    assert data[:6] == b"\x7fELF\x02\x01" and struct.unpack_from("<H", data, 18)[0] == 183
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    section_size, count, names_index = struct.unpack_from("<HHH", data, 58)
    sections = [struct.unpack_from("<IIQQQQIIQQ", data, section_offset + i * section_size) for i in range(count)]
    names_section = sections[names_index]
    names = data[names_section[4]:names_section[4] + names_section[5]]
    strings_section = next(s for s in sections if names[s[0]:].split(b"\0", 1)[0] == b".dynstr")
    start, size = strings_section[4:6]
    strings = data[start:start + size]
    old, new = b"libonnxruntime.so\0", b"libmoonort.so\0"
    matches = [i for i in range(len(strings)) if strings.startswith(old, i) and (i == 0 or strings[i - 1] == 0)]
    assert len(matches) == (1 if required else 0), f"Unexpected ORT references: {len(matches)}"
    result = bytearray(data)
    for offset in matches:
        result[start + offset:start + offset + len(old)] = new.ljust(len(old), b"\0")
    assert len(result) == len(data)
    return bytes(result)


def prepare_runtime():
    original = acquire(f"{MAVEN}/moonshine-voice-0.1.5.aar", ROOT / ".tools/moonshine-small/moonshine-voice-0.1.5.aar")
    assert hashlib.sha256(original).hexdigest() == AAR_SHA
    output = ROOT / "third_party/moonshine/moonshine-voice-0.1.5-isolated-arm64.aar"
    output.parent.mkdir(parents=True, exist_ok=True)
    entries = {}
    with zipfile.ZipFile(io.BytesIO(original)) as archive:
        entries["classes.jar"] = archive.read("classes.jar")
        # The app owns all permissions and microphone lifecycle. Omit the
        # unused upstream permission activity; no upstream downloader UI.
        entries["AndroidManifest.xml"] = b'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="ai.moonshine.voice"><uses-sdk android:minSdkVersion="26" /></manifest>'
        for name in ("libmoonshine-jni.so", "libmoonshine.so", "libonnxruntime.so"):
            data = isolate_ort(archive.read("jni/arm64-v8a/" + name), required=True)
            entries["jni/arm64-v8a/" + ("libmoonort.so" if name == "libonnxruntime.so" else name)] = data
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 8, 24, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print(json.dumps({"runtime": str(output.relative_to(ROOT)), "bytes": output.stat().st_size,
        "sha256": hashlib.sha256(output.read_bytes()).hexdigest(), "originalSha256": AAR_SHA}), flush=True)


def prepare_file(item):
    name, (size, checksum) = item
    target = ROOT / f"models/source/asr/en-moonshine-{VARIANT}-streaming" / name
    data = acquire(f"{CDN}/{name}", target)
    assert len(data) == size and crc32c(data) == checksum, f"Publisher size/CRC32C mismatch: {name}"
    return {"id": f"asr.en.moonshine.{VARIANT}.streaming." + name, "path": target.relative_to(ROOT).as_posix(),
        "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(), "source": f"{CDN}/{name}",
        "publisherCrc32c": checksum, "status": "pinned_accuracy_not_validated", "license": "MIT (all streaming models)"}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("small", "tiny"), default="small")
    parser.add_argument("--model-only", action="store_true")
    args = parser.parse_args()
    VARIANT = args.variant
    CDN = f"https://download.moonshine.ai/model/{VARIANT}-streaming-en/quantized_26_08_21"
    if VARIANT == "tiny":
        FILES = TINY_FILES
    for filename, source in NOTICES.items():
        acquire(f"{RAW}/{source}", ROOT / "licenses/moonshine-runtime" / filename)
    acquire("https://raw.githubusercontent.com/mborgerding/kissfft/e5e3fac46e0d94a8f8170c06706b7a4218828333/LICENSES/BSD-3-Clause",
            ROOT / "licenses/moonshine-runtime/kissfft-BSD-3-Clause.txt")
    if not args.model_only:
        prepare_runtime()
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        records = list(pool.map(prepare_file, FILES.items()))
    license_path = ROOT / f"models/source/asr/en-moonshine-{VARIANT}-streaming/LICENSE.txt"
    license_data = acquire(RAW + "/LICENSE", license_path)
    notice = ROOT / "licenses/moonshine-0.1.5-LICENSE.txt"
    if notice.exists():
        assert notice.read_bytes() == license_data
    else:
        notice.write_bytes(license_data)  # Unchanged downloaded license, not generated prose.
    records.append({"id": f"asr.en.moonshine.{VARIANT}.streaming.license", "path": license_path.relative_to(ROOT).as_posix(),
        "bytes": len(license_data), "sha256": hashlib.sha256(license_data).hexdigest(), "source": RAW + "/LICENSE",
        "status": "pinned_license", "license": "MIT, Section 1 for all streaming models"})
    print(json.dumps(records, indent=2), flush=True)
