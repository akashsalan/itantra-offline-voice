# iTantra — Public BLE SOS preview

Version **0.8.0-public-sos-preview**, Android version code **22**.

## Install

- `APKs/app-demoPreloaded-debug.apk`: includes English Small, English Tiny and
  Hindi STT packs. Use this for a quick demonstration.
- `APKs/app-base-debug.apk`: smaller app; install only needed STT packs separately.
  Public SOS receiving/forwarding, typed alerts and built-in TTS do not require STT.
- Both are ARM64 Android builds, use the same application ID, and are alternatives,
  not two separate apps. Updating a compatible existing debug installation should
  preserve its data; no device update was performed for this build.

All STT packs are unchanged and remain in:
`D:\SIH\iTantra_Delivery_2026-09-21\STT_Models`

The older code-21 APKs and model delivery kit were kept intact. Do not mix an old
APK with the new public BLE mode: all participating phones need code 22 or a
compatible public-SOS implementation.

## Use the new feature

1. On every participating phone open **Emergency SOS -> BLE Relay SOS**.
2. Enable Bluetooth, grant the requested Nearby devices permissions, and turn
   on **Receive & auto-forward**. Accept the public-mode notice. No team code,
   pairing, Wi-Fi or normal conversation connection is needed.
3. Create a spoken, typed or preset SOS in that tab; review and confirm it.
   The existing three-second cancellation window is retained.
4. Received valid alerts forward automatically: **A -> B -> C -> D = 3 hops**.
   D receives but does not forward the original again. No extra Forward tap.
5. Use the switch or notification's **Stop BLE SOS** to stop. Sessions stop after
   30 minutes; forwarding packets expire within five minutes. History remains.

## Important limits

Public messages are readable by nearby participants; senders and their claims
are unverified. The app does not contact arbitrary phones or emergency services.
Only compatible phones with receiving ON can participate. Duplicate checks,
rate limits and bounded retries help limit flooding, but cannot defeat malicious
modified apps or radio jamming. Keep clocks approximately correct.

Multi-hop Bluetooth delivery, Android background behavior, audio and the new UI
still require real-phone testing. Automated protocol/policy tests are not physical
proof. For a real chain test, ensure A cannot directly reach C/D.

Design and checklist: `D:\SIH\docs\PUBLIC_BLE_SOS.md`
Executed checks: `D:\SIH\docs\results\public-sos-code22.md`
