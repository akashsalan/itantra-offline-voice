# PTT endpointing and measurement sessions

Version 0.5.2 (code 13) uses the existing, pinned Silero INT8 model through the
sherpa-onnx 1.13.8 Kotlin `Vad.compute` API. Model reference:
https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/Vad.kt
and licence: https://github.com/snakers4/silero-vad/blob/master/LICENSE .

The microphone and neural inference run only for an active PTT press. Silero
classifies 512-sample windows at 16 kHz (32 ms). At least 160 ms of consecutive
neural speech classifications establishes speech; initial non-speech never
triggers pause completion. Thereafter each speech window resets the silence
counter. With the default setting enabled, the first full window reaching
2,000 ms of non-speech finalizes capture (32 ms resolution). Manual release
and the existing 15-second limit remain. One synchronized endpoint owns the
reason and capture-end timestamp. A later release cannot finalize again or
change that timestamp, and another press is rejected until release.

Discards, VAD failures, no detected speech, ASR failures and empty/non-word
results are not sent. Neural classification is fallible; this is not a claim
of perfect noise rejection or measured recognition accuracy. Auto-send and
connection availability retain the existing send-versus-draft policy.

Measurements live in memory for the current session: latest 10 sent PTT events,
10 received voice messages, and up to 10 unsent/discarded captures. Stored message
keys link lifecycle updates internally; peer keys never enter exported records.
All outgoing wire parts from a capture share its event. Each received message
combines its synthesis chunks and emergency repeats into one playback attempt;
replay replaces that attempt's timing fields. A new session drops old bindings,
so late callbacks cannot contaminate new records. Message persistence is unchanged.

Transmit bytes count successful local application-frame writes, including
length prefixes, retries and local group fan-out. They exclude TLS/network
overhead and remote host forwarding. Receive bytes use actual inbound frame
lengths. Group ACK counts cover unique original recipients who acknowledged
all parts. Human acknowledgement does not imply playback acknowledgement.
Capture-end-to-played-ACK uses only the sender's local clock and includes
playback and return travel; it is not remote audio onset.

More offers session reset, JSON export, latest records, history, footprint and
collapsed technical details. JSON schema version 1 explicitly serializes only
measurement fields and app/device context, never transcripts, peer information,
addresses, encryption data, firmware fingerprints or exception details. Null
means unmeasured. Metric definitions accompany every export. AudioTrack write
submission is not exact audible onset. Sampled PSS peak is not exact peak
allocation. Process CPU time is not CPU percentage.

Focused development verification (no broad instrumentation or benchmarks):

```powershell
.\gradlew.bat :app:testBaseDebugUnitTest --tests org.itantra.app.core.PttEndpointTest :app:assembleBaseDebug --console=plain
```

The four deterministic tests cover a short pause and speech reset, two-second
completion exactly once, manual release (also with automatic endpointing off),
and release/repress after automatic completion. Device results are recorded
separately in `results/ptt-update-checks.md`.
