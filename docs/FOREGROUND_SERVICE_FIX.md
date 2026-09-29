# Foreground microphone lifecycle fix — code 21

Version: `0.7.3-foreground-fix`, 21. User reported the generic microphone
foreground-service error after code 19 and authorized fixing, rebuilding and
installing both variants/the connected app. No model or speech-quality change.

## Diagnosis and limits

On RMX1801/API 29, microphone permission and its app-op were allowed. The generic
error was visible in Talk. The original platform exception was not retained by
the prior Boolean service helper, so the exact first triggering event could not
be established from its logs. Read-only inspection also showed the old blocked
flag had already cleared after the Activity became visible again.

There was nevertheless a concrete lifecycle defect: disconnected PTT changed
`recording=false` and requested service shutdown while `captureInProgress` was
still true during recognition/native cleanup. `SpeechService.onDestroy()` then
treated that app-requested stop as an unexpected failure. A delayed shutdown from
one operation could also poison a subsequent press. Explicit PTT did not clear
that failure, and a generic `check(service())` replaced the original error.

## Implementation

- Foreground demand covers the full capture/recognition/cleanup operation.
  Microphone service type is retained until cleanup finishes, and for the entire
  explicitly active hands-free session, including mute and reply playback.
- A pure `ForegroundServiceGate` tracks operation leases, concrete service
  instances and expected retirements. Intentional or stale destruction cannot
  fail a newer operation; unexpected destruction of the current owner still
  cancels outgoing audio and ends hands-free consent.
- Service callbacks read current demand rather than stale notification intent
  flags. A lease ID prevents old callbacks from promoting/failing new work.
- Recording/playback awaits acknowledgement **after** `startForeground()` succeeds.
  Startup request alone is not readiness. A four-second bounded wait reports and
  stops a service that never acknowledges; it does not retry microphone capture.
- Changing capabilities (for example, adding microphone use to an existing local
  connection service) requires a fresh foreground acknowledgement. Existing
  startup waiters follow a newer capability request instead of being discarded.
- Expected old-instance teardown may reissue an already-pending startup request.
  It cannot create new consent or restart an ended/failed hands-free session.
- The first concrete failure type/message/cause is retained and logged under
  `ItantraForeground`; cleanup cannot replace it with the old generic message.
  Diagnostic metadata records the failure type, not recognized speech.
- A fresh foreground PTT/SOS press, hands-free Start, or explicit replay can retry
  after failure without forcing an Activity restart. This does not auto-start any
  microphone. Permission checks remain enforced and Android restrictions remain.
- Activity visibility is tracked per lifecycle owner, so a retiring Activity
  cannot clear the visibility of its replacement. Manual PTT/SOS relies on its
  explicit UI gesture and Android's eligibility checks, not a second potentially
  transitioning visibility flag. This corrects an intermediate code-20 guard that
  incorrectly displayed "Open iTantra before starting the microphone" on Talk.

The implementation follows Android's two-stage [foreground-service launch](https://developer.android.com/develop/background-work/services/fgs/launch)
and [service lifecycle API](https://developer.android.com/reference/android/app/Service).
These are lifecycle corrections, not a workaround for Android permission or
background restrictions.

## Verification

`ForegroundServiceGateTest` has 24 cases for capture-to-transcription lifetime,
muted hands-free, link-only/playback-only demand, asynchronous readiness, normal
stop, rapid stop/start, delayed old callbacks, stale intents, unexpected shutdown,
first-error preservation, retry, capability upgrades/forwarded waiters, and
overlapping/idempotent Activity visibility updates.

The first test run exposed an overly strict test assertion: debug coroutine stack
recovery can copy a thrown exception. The test now checks the exception's type,
message **and preservation of the original in its cause chain**, rather than
requiring wrapper object identity. No runtime failure was hidden to pass it.

Final build/install evidence is in [the result record](results/foreground-fix-code21.md).
JVM tests and compilation are not evidence of actual microphone/TTS or two-phone
success. The user was separately asked about brief physical microphone tests;
no unanswered/preselected option is treated as consent.

The user-deferred native-speaker/model-quality milestone 4 remains deferred.
Full-duplex calling and full corresponding-source release gates are unchanged.
Selected code-19 source files and both APKs are backed up under
`.tools/rollback-backups/foreground-fix-20260919-120902/`.
