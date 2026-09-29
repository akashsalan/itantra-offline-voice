# Optional model setup downloads

The base APK contains no ASR weights. As of code 16, the preloaded APK embeds
Moonshine Small Streaming English and unchanged IndicConformer Hindi packs only.
English upgrades after validation, preserving the previous installation for
recovery. Non-English imports and the Silero VAD model are unchanged.

`app/src/main/assets/model-catalogue.json` pins the actual current pack archive
bytes, unpacked file bytes and SHA-256 values. It is bundled, not remotely mutable.
Each URL is intentionally null until the owner approves an HTTPS release host.
No models have been uploaded. Manual `.itpack` selection and USB transfer remain
fully offline and usable now. The app must not present unpublished URLs as working.

Once a URL is approved, it must be a final HTTPS URL without credentials or a
fragment. HTTP redirects are refused rather than silently trusting another host.
The same release must preserve the exact archive bytes/hash. Download and import
do not change the licensing of any model; current source/model notices still apply.

## Workflow and security

- One selected language download at a time; 64 KiB streaming buffer, no new library.
- Wi-Fi-only default, checked before transfer and periodically, with the HTTP
  request bound to the selected Android network. Turning the setting off permits
  the selected non-Wi-Fi network; it does not affect inference or local links.
- Downloads deliberately pause when the activity leaves the screen. Resume is
  explicit; this version does not promise unattended/background completion.
- App-private partial files survive process death. Byte-range resume is accepted
  only for the exact expected range and total. A full 200 response replaces rather
  than appends to the partial. Pause, cancel/remove partial and retry are exposed.
- Check space for remaining archive, full extraction and 32 MiB reserve. Old
  installed models are retained until the existing atomic activation completes.
- Verify the full pinned archive SHA-256 before passing it to the existing importer.
  That importer independently verifies a manifest-first, exact flat filename
  allowlist, sizes and every pinned file SHA-256. No ZIP paths or executables are
  accepted from remote metadata.
- A verified archive retained after a busy/failed install can be retried without
  downloading again. A hash mismatch requires cancellation/removal and a fresh try.
- Delete requires confirmation and a different active recogniser. Only the
  selected registered version is removed. History, other languages and older
  versions are untouched. Explicit opt-out prevents preloaded startup from
  silently reinstalling a deleted bundled language.

## Footprint and verification limits

English archive: 142,339,040 bytes; installed payload: 142,315,154 bytes.
Hindi archive: 197,694,194 bytes; installed payload: 197,663,198 bytes.
VAD remains 212,860 bytes. Other exact pack figures are in the catalogue.
The catalogue itself adds only small UI code; no downloader
dependency, hosted inference API or foreground download-service permission.

Focused tests cover range acceptance, full-response restart, changed-range
rejection, HTTPS and free-space arithmetic. Actual pause/resume/server behaviour
and interrupted installs still require an approved host and a device integration
check. No end-to-end online download has been claimed or measured.
