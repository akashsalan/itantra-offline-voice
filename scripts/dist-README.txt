iTantra {{VERSION}} (version code {{CODE}})
Offline voice and text communicator for Android

Built {{DATE}}. Needs Android 8.0 (API 26) or newer on a 64-bit ARM phone
(arm64-v8a). Source code: https://github.com/akashsalan/itantra-offline-voice


WHICH APK
=========

apk/itantra-base-...apk          about 67 MB
    Built-in eSpeak NG voice for all ten languages, nothing else. Import a
    speech-to-text pack from packs/ before push-to-talk works. Receiving and
    playback work straight away.

apk/itantra-preloaded-...apk     about 690 MB
    English and Hindi speech-to-text and natural voices already inside.
    Best for a quick demo: nothing to import to start talking.

Install one, not both. They share an app ID, so Android refuses the second
until the first is uninstalled. Allow "Install unknown apps" for your file
manager or browser when Android asks.


PACKS
=====

packs/speech-to-text/   one file per language: what the microphone needs
packs/voices/           optional natural voices, one file per language

Copy the .itpack files you want to the phone, then in the app open
  Models -> Speech to text -> Import a language, or
  Models -> Text to speech -> Import a voice,
and pick the file.

Every pack is checked against a pinned SHA-256 before it is turned on. A
damaged copy is refused instead of half-installed, and whatever was already
installed stays as it was.

Voices really are optional: eSpeak NG is inside every APK and speaks all ten
languages. A voice pack only changes how one language sounds. Emergency SOS
never waits for a neural voice to load.

or-experimental.itpack is Odia speech-to-text and is experimental. Check
every transcript.


CHECK YOUR DOWNLOAD
===================

SHA256SUMS.txt lists a SHA-256 for every file in this folder. To check one on
Windows, open PowerShell in this folder and run:

    Get-FileHash apk\itantra-base-{{VERSION}}-code{{CODE}}.apk

and compare the result with the matching line. On Linux or macOS, run
"sha256sum -c SHA256SUMS.txt" in this folder to check everything at once.

Large folders downloaded from Google Drive arrive as several zip parts.
Extract all of them into the same folder before checking.


DISK SPACE ON THE PHONE
=======================

A speech pack needs about its own size again, briefly, while it installs. Keep
roughly double the file size free, or the import refuses to start rather than
fill the phone.


LICENCE
=======

iTantra is GPL-3.0-or-later. Every model and library keeps its own licence;
see the source repository above for THIRD_PARTY_NOTICES.md and docs/legal.
No internet speech service is used. Delivery is not guaranteed, and iTantra
is not a replacement for emergency services.
