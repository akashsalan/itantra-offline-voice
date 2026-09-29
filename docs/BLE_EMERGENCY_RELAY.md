# Experimental trusted-team BLE emergency relay

Implemented in the field-preview update; not a verified field mesh and not the
Bluetooth SIG Mesh standard. Discovery alone is never labelled mesh. Only short
emergency text uses this path. Normal PTT, silent chat, Wi-Fi Direct, Bluetooth
Classic and LAN groups keep their existing transports and wire protocols.

## Sender and receiver flows

1. Connections → Emergency BLE relay → create/join a trusted team. Share the
   random 256-bit code offline with intended members and compare the team tag.
2. Each phone explicitly starts relay. A connected-device foreground notification
   remains present; the session stops after 30 minutes unless restarted.
3. Open the dedicated SOS page → choose nearby trusted relay team → hold its
   emergency PTT → offline STT → 3-second cancellation window. Presets/typed
   text instead require explicit confirmation. See [SOS_FLOW.md](SOS_FLOW.md).
4. Queue one signed, AES-256-GCM-encrypted alert. Nearby team mailboxes receive
   serialized GATT fragments. Intermediate phones forward it, up to 3 radio hops.
5. Every member of that trusted team is an intended recipient. A new authenticated
   alert enters existing Room history and the existing eSpeak/alarm-audio path.
   Repeats are bounded by the existing 1–3 repeat setting. Android audio policy
   still applies. Silent chat never enters this queue.
6. Delivered, played and human-acknowledged receipts are signed/encrypted packets
   flooded back toward the original sender. Counts are unique device signers;
   the total group size is unknown. No receipt is a guarantee of human safety.

The route is explicit. There is **no automatic fallback**, cross-transport SOS
mirroring, public distress broadcast, location collection, or hosted service.

## Bounds and security model

| Concern | Implementation / limitation |
| --- | --- |
| GATT / MTU | Connectable mailbox; writes with response; negotiated MTU, default 23. Six-byte fragment header; maximum ATT value 512. 2,048-byte encrypted packet limit; 1,024-byte UTF-8 alert limit. No throughput claim. |
| Routing | Bounded flooding to recent team-tag advertisements, not an optimal routing tree. At most 32 discovered addresses and one outbound GATT connection at a time. |
| Queue / retries | At most 64 packets, four attempts per packet/address at least 30 seconds apart; at most four packets per connection. Maximum eight inbound fragment assemblers; 16 complete frames awaiting processing. |
| Expiry | Five-minute initial elapsed-time budget. Local residence is subtracted before forwarding, plus the whole 30-second transfer timeout. This can expire early; it cannot be used as exact cross-phone latency. Pending packets are not resumed after reboot. |
| Duplicates / replay | Persistent IDs plus per-signing-device monotonic sequence and a 64-message replay window. Out-of-order packets older than that window are deliberately rejected. Maximum 128 device windows; no silent eviction. |
| Authentication | Shared random team code admits a device; ECDSA P-256 signs its immutable message. Android Keystore retains private signing and wrapping keys. Membership is not a verified real-world identity; any code holder may join/read. |
| Confidentiality | AES-GCM for complete message and forwarding envelope. Public discovery tag reveals likely team proximity; Bluetooth metadata is not hidden. No security certification or military-grade claim. |
| Storage | Small encrypted queue and replay metadata in no-backup app storage. Messages use the existing Room database. No schema migration. Diagnostic exports exclude identifiers, text, keys and network addresses. |
| Background | Explicit foreground start only; no boot auto-start. A connected-device foreground service improves continuity but does not defeat Doze/vendor restrictions or guarantee relaying with a locked screen. |
| Battery | 12-second discovery bursts separated by 18 seconds; advertising while active. No idle microphone. Battery effect and range are not measured. |
| Compatibility | Separate UUIDs and `ble:` message namespace. Legacy peers do not gain relay support; every relay hop needs this version. Existing LAN/radio frames are unchanged. |

Phone-to-phone clocks are never subtracted. A played-ACK interval includes remote
playback and return delivery; AudioTrack submission is not exact acoustic onset.
Do not infer battery, range, throughput or ten-language accuracy from compilation.

## Required physical verification (not replaced by unit tests)

Use three authorized phones A, B and C with the same team code, and keep A and C
outside direct BLE range. Verify A→B→C and returning receipts. Repeat with B
stopped to establish that delivery depended on actual relaying. Check duplicate
paths cause one playback, expiry stops forwarding, wrong-team packets are ignored,
and a fourth non-team phone never receives plaintext or triggers playback.
Then check interruption, process restart, screen-off/vendor battery restrictions,
Bluetooth revocation, minimum MTU, concurrent Classic/Wi-Fi and multiple senders.
These are a separate physical verification phase, not claimed by this update.

Implementation references (platform behaviour, not copied application code):

- [Android BLE data transfer](https://developer.android.com/develop/connectivity/bluetooth/ble/transfer-ble-data)
- [Android BLE background restrictions](https://developer.android.com/develop/connectivity/bluetooth/ble/background)
- [Android GATT MTU negotiation](https://developer.android.com/reference/android/bluetooth/BluetoothGatt#requestMtu(int))
