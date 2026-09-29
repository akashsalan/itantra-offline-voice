# Voice-first SOS: 0.6.1-sos-preview

## Real-world use case

A prepared rescue team, campus safety team or community volunteer group has
installed its language packs before a network outage. Phones can form a Wi-Fi
Direct group without a router, internet service or a phone hotspot. Normal
one-to-one and group voice/text traffic stays on the existing transports.

When a member needs help, SOS opens a dedicated page. They describe **what
happened, where they are and what help is needed**. Offline STT converts the
recording into priority text. The receiver gets the existing emergency
notification, local speech playback and an explicit acknowledgement action.
This is communication with fellow iTantra users, not dispatch to emergency
services, automatic translation or guaranteed rescue.

If a team cannot maintain a direct connection, its members can explicitly enable
the experimental trusted-team BLE relay. Short emergency text can be forwarded
by other active team phones. This is actual bounded application-level GATT relay
code, **not yet verified as a working three-phone field mesh**, not Bluetooth SIG
Mesh, and not discovery renamed as mesh. Radio range, Android restrictions,
device support, team membership and forwarding expiry still limit reach.

## Sender

```text
Talk → Emergency SOS (new page; no idle listening)
  Current person / whole connected group OR nearby trusted relay team
  Hold to record SOS
    Release / enabled 2-second speech-following pause / 15-second maximum
    Offline STT; reject silence, unusable speech and failed transcripts
    Exact transcript → 3-second Cancel SOS window
    Priority text queue → per-alert delivered / played / acknowledged status
```

- PTT itself is the deliberate action. Opening SOS does not record or send.
- Touch exploration supports a labelled start/finish action. Haptic feedback and
  concise live status complement text; colour alone never indicates state.
- Initial silence cannot trigger a send. Lifting after automatic finalization
  cannot send a second time. A new capture requires finger lift.
- Capture and confirmation snapshot recipients. Changed/disconnected recipients
  cancel automatic sending, never silently retarget or switch transports.
- Without a route, speech stays as an SOS draft. Connecting later does **not**
  automatically send it. Explicit review/confirmation is required.
- Presets and typed alerts have a confirmation dialog, then the same cancel
  window. Non-English presets still need native-speaker review.
- Leaving the page/backgrounding cancels pending confirmation. Once text is
  enqueued, leaving does not recall it from other phones.
- SOS has a separate in-memory draft and measurement binding from Talk. Normal
  Talk text is not replaced or marked sent. Unsent drafts are not process-death
  persistence; saved sent/received history remains in the existing database.
- The normal Auto-send setting does not control SOS: the displayed explicit SOS
  workflow does. No continuous microphone or hosted speech service is added.

## Receiver

```text
Authenticated new priority alert
  → Persist once / duplicate protection
  → Emergency banner + Android notification (permissions/policy permitting)
  → Existing local TTS and emergency-priority playback
  → Delivered receipt / played receipt
  → User chooses Acknowledge → human acknowledgement returned
```

Existing authorized alarm-volume boost and bounded repeat settings remain.
Android can restrict notifications/audio; playback is not uninterruptible.
Acknowledgement does not promise that help is coming. An intermediate mailbox
accepting a BLE packet is not proof of delivery to other team members.

## Routes and limits

The selected connected group's online recipients are used; private group-chat
selection cannot turn SOS into an accidental private alert. BLE targets every
reachable member with the same trusted-team code and active relay. The total
BLE team size is unknown; advertisements are not authenticated membership.

Only one route is selected. Cross-transport mirroring and automatic fallback are
not implemented; those would need common alert IDs, cross-route deduplication
and physical tests before enabling safe combined delivery. Arbitrary nearby
phones, satellites and emergency services cannot be notified by this feature.

BLE is limited to 1,024 UTF-8 text bytes, three hops and a five-minute local
forwarding budget. Relay sessions stop after 30 minutes. See
[BLE_EMERGENCY_RELAY.md](BLE_EMERGENCY_RELAY.md) for security/battery/background
constraints and required isolated A→B→C verification.

## Visual treatment and verification

Original radio vectors replace generic branding cues in the app header and SOS
entry. This is a local-radio symbol, not a satellite capability claim. The SOS
page has one recipient summary, a large fixed PTT target, scrollable message and
delivery details, and secondary preset/typing controls. Emergency red is used
for SOS, with restrained state-colour transitions and page fades respecting
the reduced-motion preference. No bitmap assets, icon-font downloads, new
permissions, speech weights, wire formats or database migrations are introduced.

Focused deterministic tests cover the cancellation window, one completion per
voice capture, no rearming on finger release/cancel, new-press recovery, changed
recipients and no-route/blank rejection, plus the existing four PTT endpoint
tests. Device and artifact evidence is in
[field-preview-checks.md](results/field-preview-checks.md).
