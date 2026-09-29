# Phase 0 acceptance and subsequent gates

Record phone model, SoC, RAM, ABI, Android build and battery mode first. An
emulator cannot prove the microphone, native ARM behavior, memory or radios.

1. Verify the APK is arm64-only and has no ASR binaries or cloud permissions.
2. Install on an arm64 phone. Run the instrumentation voice/engine-reopen smoke
   test using `gradlew :app:connectedDebugAndroidTest`. Non-zero PCM is only a
   synthesis smoke result, not listening evidence.
3. Import English/Hindi packs via SAF. Reject altered hashes, unknown files,
   duplicate entries, oversized/truncated ZIP entries and Odia `.nemo` files.
   Interrupt import and confirm a previous active pack still loads after restart.
4. Verify permission denial and regrant. Press/release quickly, record silence,
   record 15 seconds, cancel, background, rotate and interrupt with another audio
   app. Confirm no leaked microphone or speaker activity.
5. Produce real English and Hindi transcriptions from multiple speakers. Switch
   language repeatedly while watching memory; only one heavy model is resident.
   Test app restart after airplane mode. Keep Wi-Fi/data off for this phase.
6. Save exported phone diagnostics with the exact device and corpus metadata.
   Record model-load time, audio duration, inference time/RTF, release-to-text,
   TTS first PCM/synthesis/RTF, AudioTrack submission, PSS/RSS and CPU deltas.
7. Have native listeners evaluate all ten eSpeak voices. Include 12/108/1000,
   directions, local place names and operational abbreviations. The initial
   instrumentation sentences are authored smoke fixtures requiring linguistic
   review, not an accepted evaluation corpus.
8. Stop progression if Hindi cannot meet real-time requirements on reference
   hardware or eSpeak intelligibility is unusable. Document measurements before
   deciding on any model or engine change.

Phase 1 follows measured feasibility: implement the PRD protobuf-lite schema,
64 KiB framed streams, CRC, Wi-Fi Direct service discovery and owner/client TCP,
HELLO/capabilities, peer confirmation, persistent Room outbox/history, 1/2/4 s
retry, ID deduplication, delivery/playback/human ACK separation, Unicode language
metadata, normal/emergency ordering and automatic receiver TTS. Verify 10 Hindi
and 10 English round trips on two physical phones with internet unavailable.

No Android test has been run unless its actual output is recorded in docs/results.
