# Requirements hardening — code 18 preview

Date: 19 September 2026. Version: `0.7.0-hands-free-preview`.
This records implementation, not full PS 26173 acceptance.

## Scope and status

The user approved the six-milestone plan and deferred milestone 4: the native-speaker
STT/TTS quality study, English model comparison and Odia accuracy acceptance. Model
weights, profiles and experimental labels are retained. There are no new runtime
dependencies, wire schemas, Room migrations, hosted inference or model uploads.

- Milestone 1: Wi-Fi Direct permission checks and callback exception boundaries;
  stale callbacks invalidated, missing access exposed as a recoverable error.
- Milestone 2: explicit hands-free turn-taking, foreground notification and controls.
- Milestone 3: unified emergency preemption, protected ordinary controls and bounded
  audio-focus retries. Hardware/OS/volume behaviour is not yet verified on phones.
- Milestone 4: deferred by user, NOT completed.
- Milestone 5: eSpeak startup warm-up and resource observations implemented; measured
  low/mid-device latency/CPU/RAM targets remain pending.
- Milestone 6: full bundled licence viewer, corrected About information and source
  preview packaging/checklist. Complete corresponding-source distribution, clean
  rebuild and physical offline two-phone verification remain open.

## Hands-free operation

Choose Talk → Hands-free → Start hands-free after connecting/confirming a conversation.
The selected ASR pack must be ready and any existing draft sent or explicitly discarded.
Choosing the mode alone never starts the microphone. Active consent is not persisted.
PTT auto-send/auto-play preferences are retained but hands-free explicitly sends
utterances and plays replies while its session is active.

Silero analyzes 512-sample frames at 16 kHz. At least 250 ms of consecutive detected
speech confirms a segment (256 ms at this frame size). Its first ASR input includes
200 ms pre-roll plus the full confirmation window. Initial silence/noise never feeds
the recognizer. Trailing silence is configurable from 500 to 1200 ms, default 700 ms
(quantized upward to a full frame). Utterances include at most 15 seconds of PCM.
Moonshine receives confirmed PCM through the existing bounded live queue. Indic CTC
still decodes at the endpoint. ASR processing and playback suspend further capture;
the UI describes turn-taking, not gap-free/full-duplex calling.

Incoming voice and explicit replay stop/discard an unfinished hands-free capture.
The audio mutex serializes native processing/playback; after speech playback there
is a 250 ms guard before capture rearms. Long-running native ASR is not forcibly
terminated, so preemption waits for a safe native boundary. Measure that delay.

Mute and End invalidate capture-generation tokens. Disconnect or a changed recipient
set ends the session. Consent is rechecked at the Room insertion boundary after any
dispatcher/lock wait; old transcripts must not be sent to a new audience. Earlier
queued message parts are not recalled. A microphone, ASR or foreground-service failure
ends automatic sending and requires an explicit restart. Notification controls allow
mute/end/disconnect while backgrounded. Grant permissions/start from the visible app.

## Emergency policy

All routes use the same incoming-voice policy. Alerts preempt ordinary recording or
playback, but do not overlap another incoming alert. Normal replay/stop/auto-play
controls cannot cancel an active incoming emergency. Acknowledge or explicit session
disconnect can stop it. Interrupted normal messages remain available for manual replay.

Audio uses the alarm stream with Android audio focus. Maximum alarm-volume boost
remains explicitly authorized by the user, restores the prior setting and falls back
to existing volume if the OS denies the boost. Voice options warn when authorization
is absent. No guaranteed DND bypass or absolutely non-interruptible playback is claimed.

An incomplete emergency interrupted/denied by audio focus is not marked played. It
enters WAITING_AUDIO, with retries after 1, 3 and 10 seconds (at most three retries).
After exhaustion it requires explicit replay. Acknowledge/disconnect cancels retries;
process recovery turns PLAYING/WAITING_AUDIO into INTERRUPTED, not a stranded timer.
An entire announcement completing once permits a played ACK even if a later repeat
is interrupted. Played and human acknowledgement remain distinct.

## Measurements

eSpeak copies/initializes its bundled voice data during startup without generating
audio. Receiver voice selection still uses each packet's language, independent of
the sender's selected ASR language or installed ASR packs.

Diagnostics records capture mode and installed model storage including retained
versions. CPU windows distinguish connected idle, disconnected idle, hands-free
waiting and muted states. Percentage = process CPU delta / wall-time delta × 100;
100% means one busy CPU core, not the entire multicore device. Mode changes reset
the baseline to exclude mixed activity windows. Observations include UI, radio and
measurement overhead. Latest 120 windows are exported in diagnostics schema 2.

Memory/CPU sampling is approximately 1 s during speech/hands-free, 5 s otherwise.
Sampled PSS is not an exact allocation peak. AudioTrack submission is not acoustic
onset; returned played-ACK delay is not remote first-audio delay. Per-capture process
CPU includes initial hands-free waiting; it must not be mistaken for recognizer-only
CPU. No controlled benchmark, WER or listening score is created by this update.

## Required device acceptance (not yet executed)

Use a low-range and a mid-range arm64 phone, preferably different manufacturers.
Update with install -r only after user confirmation; never clear models/history.

1. Regress PTT press/release, two-second pause, 15-second limit, drafts and SOS
   confirmation. Verify model choice and imported files survive the update.
2. Complete at least 20 alternating hands-free sentences in both directions.
   Include short pauses, >15 s speech, silence and noise. Check clipping/duplicates.
3. During incoming playback verify capture is closed and TTS never loops back into
   outgoing text. Check automatic rearm, explicit replay, mute/unmute and end.
4. End/mute during capture and final ASR; change/disconnect peers and group members.
   No not-yet-queued transcript should transmit or switch audience. Inspect history.
5. Background/screen-lock; use notification controls. Revoke mic/Nearby permissions,
   disable radios, deny foreground access and restart the process. No auto mic restart.
6. Receive an alert during capture, ASR, normal playback and another alert on both
   Wi-Fi and Bluetooth. Test volume off/authorized/restricted and volume restoration.
7. Interrupt/deny audio focus; verify bounded retries, acknowledgement cancellation,
   process recovery and no false played ACK. Test calls/other audio on each device.
8. Disable internet, retaining only the local Wi-Fi/Bluetooth connection. Test both
   directions, Unicode payloads, reconnection/dedup and already-installed packs.
9. Export CPU/PSS/RTF/latency with device/OS context. Measure actual cross-phone
   acoustic onset with a common recording or validated clock mapping. Compare with
   PRD internal targets; do not invent an official numeric SIH pass threshold.

The meanings of “like a phone” and “non-interruptible” still need organizer confirmation
before claiming literal full-PS compliance. User-deferred language evaluation remains
a separate final gate. Current build evidence belongs in results/requirements-code18.md.

## Recovery

Pre-change source, build configuration and both APKs were copied to
`.tools/rollback-backups/requirements-20260919`. Model binaries were not rewritten.
Do not overwrite later user work by blindly copying the checkpoint back. No Git
commit, external publication or device installation was part of these host changes.
