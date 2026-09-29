# Hands-free Talk experience — code 19

User requested a switch in the same Talk tab that changes the whole experience
to a call-like layout, and authorized improving the UI/UX. This is a presentation
update over the code-18 automatic-turn-taking pipeline, not a new call protocol.
Version: `0.7.1-hands-free-ui` / `19`.

## Interaction

- One labelled Hands-free switch replaces the previous two mode chips. Switching
  on changes the view; it does **not** activate capture. Explicit Start remains.
- Push-to-talk retains the connection card, editable draft, conversation/replay,
  language selection, voice options and held recording control.
- Hands-free shows the confirmed conversation, connection route, prominent state
  and microphone indicator, speech-language selector, latest real exchange and
  large Start or Mute/End controls. No simulated waveform, audio level, signal
  strength, call duration, participant presence or remote microphone status.
- Start is gated by connection confirmation, permission, model readiness, idle
  speech pipeline, no existing draft and no held PTT interaction. Where possible,
  the primary button takes the user to the missing setup step. Existing drafts
  return to PTT for review; this screen never silently discards or sends them.
- Listening appears only during actual capture. Processing and playback tell the
  user to wait. Muted state overrides an in-flight outgoing capture; incoming
  playback remains visible even when outgoing speech is muted. Emergency playback
  takes visual priority. The runtime still owns all consent and cancellation.
- Speech-language selection is disabled for an active hands-free session, including
  muted periods. End before changing it. Incoming TTS still uses message language;
  this is not automatic source-language recognition or translation.
- A transcript sheet retains real history, replay, delivery/retry and acknowledgement
  controls, plus Mute/End while active. Replaying audio pauses hands-free capture.
- A help sheet explains automatic sending, separate local opt-in on both phones,
  processing gaps, no shared turn lock, mute/end cancellation, queued messages and
  the distinction between ending hands-free and disconnecting.
- Switching off ends local hands-free consent. Changing tabs does not end it;
  other tabs show a return-to-Talk banner with an immediately accessible End action.
- PTT per-recording haptics are suppressed for automatic hands-free segments so
  the phone does not buzz at every utterance boundary.

## Layout and accessibility

Uses the existing Material 3 colours/typography, with new app-authored vector
icons only. No new dependency, runtime, model, artwork download or speech SDK.
The state indicator has one short transition when state changes, never a continuous
animation. Reduce motion makes it immediate. Status is explained in words, not
colour alone. The switch has a single labelled accessibility action.

Primary controls are at least 60 dp high (active Mute/End: 64 dp); secondary
interactive controls are at least 48 dp. Large-font controls stack vertically.
Short/landscape/large-font views scroll the entire content so the controls are not
deliberately fixed outside the viewport. Regular-height views anchor the action
dock while the conversation/status region scrolls. Six stateless Compose previews
cover ready/light, listening/dark, muted/200% text, playback/landscape,
processing/small-phone and missing connection. They never construct AppRuntime.

The implementation follows Android's [minimum-target and labelled-control
guidance](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
and [Compose semantics guidance](https://developer.android.com/develop/ui/compose/accessibility/semantics).
Automatic live announcements are disabled while a hands-free session is active
to avoid adding repeated spoken status into an armed microphone; the text remains
focusable by TalkBack. Actual TalkBack/audio interaction still needs device testing.

## Verification boundary

Pure state tests cover every Start-gate boolean combination, permission/model/
connection recovery, draft protection, capture vs processing, mute races, local
replay vs peer reply, alert priority and ending while native capture winds down.
The final build and static-check results are recorded in
[the verification record](results/hands-free-ui-code19.md).

No UI screenshot, rendered preview, physical tap/microphone, TalkBack, two-phone,
latency, power or speech-quality test is implied by compilation or JVM tests.
At inspection ADB listed the existing phone as offline, and no AVD was configured;
no install, app restart, microphone activation or user-data change was attempted.
Subsequently, the user authorized installation: the preloaded code-19 APK was
installed and launched successfully on RMX1801. See [the device-update record](results/code19-device-update.md)
for the data-preservation checks; no UI/audio acceptance is implied by that launch.

The user-deferred milestone 4 (native-speaker/STT quality evaluation) stays deferred.
Full-duplex audio, ringing/accept/decline and shared floor control are not added.
The GPL corresponding-source release gates also remain unchanged.

## Device acceptance still required

1. Verify both themes at 320/393 dp, 200% font and landscape. Check scroll access,
   transcript sheet and system insets; do not consider IDE preview declarations
   evidence of actual rendering.
2. Toggle PTT → hands-free → PTT without Start: no microphone/service start.
3. Check disconnected, denied mic, missing/loading model, held PTT and unsent
   normal/SOS draft states. No automatic sending; recovery actions are correct.
4. Explicitly start with a consenting peer. Check listening → processing → send,
   incoming playback, mute/unmute, End, route loss/change and mode switching.
5. Verify transcript replay and emergency acknowledgement; ordinary End stops
   hands-free only, while incoming emergency playback retains existing protections.
6. Change tabs while active: return banner and End work. Rotate, navigate back,
   and test process recreation; active consent must not restart after process death.
7. Test TalkBack and Reduce motion, including whether accessibility speech leaks
   into the microphone. Confirm no per-utterance vibration from this UI.

Pre-edit rollback copy (source files and both code-18 APKs):
`.tools/rollback-backups/hands-free-ui-20260919-115210/`.
