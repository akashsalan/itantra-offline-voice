iTantra - APK and STT model delivery folder
Prepared: 21 September 2026
App: 0.7.3-foreground-fix (version code 21)

CONTENTS

APKs/
  app-base-debug.apk
    Smaller app: 65.72 MB. Includes built-in TTS for all ten target
    languages and speech detection. Import the STT packs you need.

  app-demoPreloaded-debug.apk
    Quick demo setup: 451.01 MB. Includes the same app plus English
    Small, English Tiny (low-end option), and Hindi STT starter packs.

These are two variants of the SAME Android app, not separate apps to
install together. Use one variant on each phone. Requirements: Android
8.0 or newer, ARM64. Both APKs are existing debug builds, copied without
rebuilding or changing hands-free, PTT, SOS, models, or connectivity.

STT_Models/
  bn.itpack                Bengali
  en.itpack                English - Moonshine Small Streaming
  en-low-end.itpack        English - Moonshine Tiny Streaming
  gu.itpack                Gujarati
  hi.itpack                Hindi
  kn.itpack                Kannada
  ml.itpack                Malayalam
  mr.itpack                Marathi
  or-experimental.itpack   Odia - experimental
  ta.itpack                Tamil
  te.itpack                Telugu

There are 11 installable packs for 10 languages because English has
two choices. These are all current packs from models/packs/. Retired
model experiments and source-only training/export checkpoints are not
needed for app installation and are not included.

HOW TO USE

1. Copy your chosen APK to a compatible Android phone and install it.
   For a quick English/Hindi demo, choose the preloaded APK.
2. If iTantra is already installed, update it in place. Do not uninstall
   or clear app data if you want to retain models, settings and history.
   If Android reports a signing/version conflict, stop and check it.
3. Copy any additional .itpack files you need to the phone, for example
   to Downloads. Leave the files intact; do not unzip or rename them.
4. Open iTantra > Models and use the import-from-file control. Select
   the desired .itpack and wait for verification and model loading.
5. Select your spoken language/model and connect the phones through
   a supported local Wi-Fi or Bluetooth connection.

No separate TTS installation is needed. A receiving phone does not need
the sender's STT pack to play incoming speech in that language. It does
need the relevant STT pack if it will recognise speech in that language.

VERIFICATION AND LIMITS

Both APKs match the recorded code-21 build hashes. All 11 source packs
match the checked-in model catalogue. Every copied APK and pack was
SHA-256 checked against its source. See SHA256SUMS.txt for the hashes.
APK and model payload total: 2,483,576,952 bytes (about 2.48 GB).

Odia STT remains experimental. Hash verification confirms file integrity,
not language accuracy or device performance. This is a local demo kit,
not a complete corresponding-source release or a new acceptance test.
Original project files, APKs and model packs have been left untouched.
