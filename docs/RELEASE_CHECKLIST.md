# Release gate — not a completed compliance certificate

The original app is GPL-3.0-or-later with embedded eSpeak NG. An email or link to an
upstream repository does not replace the applicable corresponding-source obligations.
The bundled About screen exposes the full GPL and available third-party texts offline.

## Required before distributing the finished release

- [ ] Freeze the APKs and exact source snapshot, with SHA-256 manifests.
- [ ] Preserve all original application/JNI/protocol code and build/packaging scripts.
- [ ] Include the exact supplied eSpeak source, its licence files and generated-data
      recipe. Its original acquisition revision is unknown; preserve the actual
      vendored tree instead of falsely identifying it with current upstream master.
- [ ] Acquire/audit matching sherpa-onnx 1.13.8 source, native dependency revisions,
      build flags and notices for the supplied Android AAR. A prebuilt AAR alone
      is not its corresponding source.
- [ ] Acquire/audit matching Moonshine 0.1.5 and bundled native dependency sources
      using the pinned source revision and applicable licences. Preserve the ORT
      filename/SONAME isolation script and both original/transformed artifact hashes.
- [ ] Finish the resolved Gradle/native/model licence inventory, including required
      notices and source for modified/relevant components. Keep system-library
      distinctions and model-weight licensing explicit; do not assume one upstream
      maintainer grants rights for other components.
- [ ] Provide complete corresponding source to recipients through a GPL-compliant
      distribution method. No additional no-redistribution/internal-only restrictions.
- [ ] Rebuild in a separate clean environment from the supplied source/instructions
      and pinned prerequisites. Record remaining environment/signing differences.
- [ ] Ship offline pack installation instructions and exact pack manifests. Base
      intentionally contains no ASR weights; preloaded contains EN Small/Tiny + Hindi.
- [ ] Attach actual low/mid-phone offline functional/performance evidence.
- [ ] Complete the user-deferred milestone 4 before claiming ten-language accuracy.

## Source preview tool

`powershell -File scripts/package-source-preview.ps1` generates a local, non-overwriting
ZIP with allowlisted application/protocol/native eSpeak sources, scripts, licences,
model metadata and source/APK hashes. It excludes local.properties, signing keys,
device outputs, speech weights, runtime AARs and generated build directories.

The manifest explicitly marks `completeCorrespondingSource: false`. This preview is
useful for review/recovery, but is **not** the complete release bundle: sherpa/Moonshine
native dependency source and the independent rebuild are still required. It does not
upload anything or grant a new licence exception.
