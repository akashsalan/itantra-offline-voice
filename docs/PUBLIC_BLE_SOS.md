# Public BLE Relay SOS — code 22

## How to use

1. Install the same new build on every participating compatible Android phone.
2. Open **Emergency SOS -> BLE Relay SOS**. Enable Bluetooth and grant Nearby
   devices permissions (Location on older Android). Allow notifications for
   visible alerts/stop controls. Receiving alone does not need a microphone or STT.
3. Turn on **Receive & auto-forward** and accept the public-mode notice. Do this
   on sender and relay/receiver phones. No connection screen, code, pairing,
   internet, router or hotspot is needed for this route.
4. Record, type or choose a preset SOS in the same tab. Review the language/text.
   Voice completion and confirmed typed alerts have the existing three-second
   cancellation window. Once transmitted, an alert cannot be recalled.
5. Accepted incoming alerts automatically become forwarding candidates. The
   receiver does not need to tap Forward, Play or Acknowledge. A -> B -> C -> D
   consumes three radio hops. D receives but does not forward that original again.
6. Expand **Public SOS history** for received/sent messages, receive-hop labels,
   replay, acknowledgement and receipt counts. Check **delivered**, not merely
   queued/relayed. A receipt does not promise rescue.
7. Turn the switch off or use **Stop BLE SOS** in the foreground notification.
   Sessions also stop after 30 minutes. Re-enable explicitly when needed.

Only active compatible iTantra participants can receive. Installing the app alone
does not enable listening. Android process death, background restrictions,
missing advertising support, Bluetooth/permission changes or range gaps prevent
delivery. This is an experimental application-level GATT relay, not SIG Mesh,
an emergency-service integration, or a substitute for established emergency channels.

## Pipeline and separation

- `PublicRelayControls` provides consent/stop controls inside `SosPage`; the SOS
  composer retains route-bound capture/countdown/confirmation checks.
- `AppRuntime` validates the selected public audience epoch again when sending.
- `PublicRelaySession` creates an independent Android Keystore P-256 identity,
  records history in Room, waits for foreground-service promotion and runs the
  bounded BLE pump. No speech model is loaded by the relay itself.
- `PublicRelayPacket` carries signed ID, sender key, language, text, kind,
  reference/recipient (receipts), and creation time. The format and signing domain
  differ from the AES-GCM trusted-team protocol. There is no public shared secret.
- `PublicRelayLedger` automatically admits accepted packets to the forwarding
  queue. It applies hop, lifetime, duplicate, receipt, rate and retry limits.
- `BleRelayLink` reuses serialized GATT writes/fragments, now with configurable
  service/mailbox UUIDs; defaults preserve the trusted-team service. Mandatory
  MTU 23 is supported. Discovery scans in 12-second bursts with 18-second rests;
  discovery/transfer is **not instantaneous**.
- Incoming public text is persisted once, locally spoken once, and produces a
  signed delivery receipt. Playback and human acknowledgement produce their own
  receipts when the original is still pending. No automatic translation.
- Public room/key prefix `ble:public` keeps these rows outside ordinary Wi-Fi,
  LAN and Classic Bluetooth outboxes. Public and private queues never share data.
- Private team setup moved from Connections into Connected SOS. Starting either
  relay mode requires the other to be stopped, avoiding reliance on multi-instance
  advertising support. Existing team codes, history and protocol remain intact.

## Bounds and honest security limits

- Three radio hops per original packet. Origin is hop 0, first receiver hop 1.
- Five-minute signed expiry; accept sender clock at most two minutes ahead.
  Transit budget is also elapsed-time-based and reduced by a full 30-second
  transfer allowance per hop. Actual forwarding can end before five minutes.
  Wrong clocks may reject valid alerts; keep phone clocks approximately correct.
- 1024 UTF-8 bytes of SOS text; at most 2048 bytes per framed protocol packet.
- At most 32 discovered peers, 128 pending packets (eight slots reserved for
  local packets), 1024 recent IDs and 1024 receipt-deduplication keys.
- At most two SOS messages per signing identity per minute, 12 admitted SOS
  messages per minute locally, 120 admitted incoming packets per minute and
  180 attempted signature verifications per minute; bounded 16-frame input channel.
  Locally created packets do not consume the inbound quota check, but do count
  toward recent totals. A saturated network may drop otherwise valid alerts.
- At most three transfer attempts per packet/peer, at least 30 seconds apart;
  no retry to a peer after a complete successful GATT write. Up to four packets
  per connection; randomized peer order and short pump delay reduce collisions.
- Duplicate public SOS rows are suppressed durably by Room; recent IDs/rates
  also survive off/on within the process. Pending forwarding is intentionally
  not restored after stopping or process death. History is kept.
- A successful GATT write means **relayed**, not verified delivery. Unknown
  participants can emit false alerts, lie in receipts, rotate identities or
  ignore cooperative hop limits. Limits mitigate accidental flooding and simple
  abuse, not coordinated hostile peers or deliberate radio jamming.
- Content is readable by participants. Do not include secrets or rely on sender
  labels as verified identities. The app does not send automatic GPS coordinates.

## Verification required on real phones

Automated protocol/policy tests simulate A -> B -> C -> D and rejection of a
fourth forwarding hop. They are not Bluetooth range or Android background tests.
Before describing the feature as field-tested, check the following physically:

- Two compatible phones, same build, public receiving ON, no team code/ordinary
  connection: typed and spoken SOS, correct text/language, one playback, receipts.
- Three phones in an actual chain, with A out of direct range of C: B forwards
  without tapping anything, and C shows hop 2. A three-phone tabletop test with
  every phone in range does not establish multi-hop behavior.
- Four-phone chain through hop 3; a fifth phone reachable only from D must not
  receive the original. Reverse receipts may independently consume up to 3 hops.
- Cyclic topology, repeated frames, Bluetooth toggles, denied/revoked permission,
  screen lock, process death, 30-minute expiry, no peers and full queues.
- Stop while starting, during reception/signature work, speech and countdown;
  ensure no new public send/play/forward after consent ends. Already-transmitted
  packets on other phones cannot be recalled by stopping this phone.
- Private/public modes cannot hear or relay one another. Test connected SOS,
  private BLE, PTT and hands-free regression behavior independently.

Build artifacts and actual executed-check results are recorded separately in
`docs/results/public-sos-code22.md`; no physical acceptance is implied here.
