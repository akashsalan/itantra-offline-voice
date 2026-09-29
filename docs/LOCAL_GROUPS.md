# Local-network groups - 13 September 2026

Current UI: `0.4.1-connections-ui` (code 10); group protocol introduced in code 9.
This is the approved group/DM
extension to [ITANTRA_PRD.md](../ITANTRA_PRD.md), not a claim that every PRD
acceptance gate has passed. No ASR pack or TTS engine was replaced.

## Choose a connection

| Connection method | What people must do first | Supported conversation |
|---|---|---|
| Wi-Fi Direct | Turn on Wi-Fi; no router or hotspot required | Existing one-to-one link |
| Bluetooth Classic | Enable Bluetooth and pair/approve as needed | Existing one-to-one link |
| Same Wi-Fi | Join the same router or hotspot network | Local one-to-one or named group |
| Phone Hotspot | One person enables a hotspot; everyone else joins it | Local one-to-one or named group |

Same Wi-Fi and Phone Hotspot use the same LAN engine with different setup help.
Neither needs internet. BLE remains nearby presence discovery, not a message
transport. Wi-Fi Direct is a separate Android peer-to-peer connection, not just
two phones already attached to a router.

Any reachable phone on the shared network can be the creator. If four phones
share a hotspot, the hotspot owner OR any of the three clients can host. Some
routers and hotspot implementations isolate clients: try the hotspot owner as
creator or use another network. A manual address cannot bypass isolation.

## Create or join a group

1. On every phone, open Connections, then the prominent Groups section.
   Groups shows only Same Wi-Fi and Phone Hotspot. Choose the setup you are using.
   Hosting/joining unlocks after detecting an actual local Wi-Fi/hotspot IPv4
   address, not merely a turned-on Wi-Fi toggle. Groups itself stays visible.
2. The creator taps Create a group. Its dialog asks for a group name (1-48
   characters) and a separate password (8-64 characters), then Create group.
3. Others see its name, creator, short room ID and advertised count. Discovery
   refreshes about every five seconds while Connections is open; Refresh is manual.
4. Tap Join. Compare the displayed creator code against the creator's Connections
   screen, check the confirmation box, then enter the group password.
5. The creator stays in the app's foreground-service session while the group
   operates. If using a hotspot, its provider must keep that hotspot enabled.

The software cap is eight connected people including the creator, not a measured
hardware capacity. There is one active room per phone. Creators can pause new
joins. Known members still need the password to reconnect. Stopping hosting
disconnects everyone; **Resume [group name]** restores the last hosted group ID,
name, salted password verifier and known identities on that creator's phone.
There is no automatic creator migration. If the creator's IP changes, members
may need to rediscover and rejoin. Creating a new group replaces the resumable
configuration, not message history; name/password are set at creation, not edited
in-place. Rejoining the same room reveals its existing conversation history.

For local one-to-one use, choose One-to-one instead of Groups. One person taps
Make this phone available. The other selects the phone and checks its code;
the available phone must approve the invitation. A direct room remains tied to
its first peer. Stop and create new availability before accepting someone else.

If discovery fails, expand connection help. The creator's local IP:38774 is
shown; enter it on the joining phone. Host identity confirmation still applies.

## Talk and Messages

- Talk shows the group name and connected count. Tap its header to see members.
  Own bubbles are right; other speakers are left with their device names.
- Recording makes a transcript, not sender-side automatic TTS. Auto-send sends
  after transcription when connected; otherwise review the draft and Send.
- Incoming speech is synthesized in the message's language. Receiving Hindi
  does not change a receiver's selected English ASR model, and Hindi still plays
  in Hindi. TTS voices are bundled; ASR packs are independently importable.
- Auto-play can be turned off. Replay buttons queue speech through the same
  single audio player; messages do not speak simultaneously. Group emergencies
  move ahead of waiting normal speech but do not interrupt speech already playing.
- Messages is always silent text-only. In a group, use Group chat for everyone
  or Direct to select a member. Switching that selection does NOT redirect Talk.
- Private-thread unread badges, per-thread drafts and group/thread history are
  separate. Offline members can be selected to read history but cannot receive
  a newly composed direct message until connected.
- A new outgoing group message snapshots currently connected recipients. Later
  joiners do not receive earlier text. Reconnect/retry retains the original set.
- Delivery is per recipient: delivered, audio-played and human-acknowledged are
  distinct. Delivery is not declared complete merely because the creator relayed
  the frame. Failed sends have Retry; relevant rejoining members resume retries.

## Wire/security/storage boundaries

`lan/LanSession.kt` owns room membership, admission, relay, per-recipient receipts
and reconnect. `LanDiscovery.kt` uses Android NSD `_itantra-lan._tcp.` and bounded
UDP presence/query messages on 38775, never message text/passwords. Message TCP
is 38774. `LanNetworks.kt` binds to on-link Wi-Fi/hotspot IPv4 interfaces rather
than the default internet/mobile network. Route access is injectable for tests.

`protocol/src/main/proto/lan.proto` is additive to the preserved legacy schema.
Frames retain length bounds and CRC validation; data wraps the existing NFC
Unicode/language/priority envelope. The creator assigns canonical sender names
from authenticated certificate membership. Four total sends use 1/2/4-second
retry waits, with message-ID deduplication and fixed original audiences.

TLS 1.3 is preferred; TLS 1.2 ECDHE/AEAD supports the minSdk 26 baseline. Each
installation has a non-exported AndroidKeyStore EC identity. The joining user
checks its host code before JOIN/password transmission; subsequent automatic
reconnects pin that host certificate. Group admission stores a salted PBKDF2
verifier, not a plaintext password. Reconnect credentials are held only in RAM.
All of this is offline, using the OS APIs, not a voice/crypto cloud SDK.

**Direct messages are not encrypted against the creator.** The creator relays
their TLS-decrypted contents; only the intended participant receives an ordinary
incoming DM. Other group members do not receive that DM. Hidden local relay rows
are an implementation detail, not protection from the host. Old Wi-Fi Direct
and Classic still use their existing controlled-demo link-layer security; the
new LAN TLS layer must not be advertised as securing those legacy transports.

Room v3 adds room/target/receipt metadata and migrates v1/v2 without deleting
history. Legacy recovery/pumps exclude LAN rows. Diagnostics expose real local
frame size, ACK elapsed time, receiver-local playback timing, ASR/TTS RTF and
memory/model sizes. Framed bytes exclude TLS/IP overhead; local timing is not
acoustic-onset measurement or a cross-device synchronized clock estimate.

## Verification and remaining physical checks

See [results/build-summary.json](results/build-summary.json) for current artifact
hashes/sizes, 52 JVM tests and lint results. The 19 lint warnings include the
intentional custom local-identity trust-manager warning; they are not silently
suppressed. Functional checks do not amount to an independent security audit.

[results/android-local-groups-tests.txt](results/android-local-groups-tests.txt)
records the RMX1801/API 29 suite. Eight tests cover:

- Three separate AndroidKeyStore/TLS/Room sessions on **one phone's loopback**:
  Hindi Unicode fan-out, dropped delivery ACK, retry/dedup, no sender loopback,
  targeted silent DM, receipt sets, named/password group resume.
- Real Wi-Fi interface: NSD/UDP discovery and an encrypted message/ACK through
  that phone's local Wi-Fi address. Still one phone, not two radio endpoints.
- Mutual TLS identity and rejection of wrong pins, including forced TLS 1.2.
- Wrong passwords, paused joins and explicit local one-to-one consent.
- Existing v1 history migration through v3, database reopen and legacy protocol
  ACK/retry/dedup/chat regression tests; legacy link in that test is in-memory.
- Native eSpeak PCM synthesis for all ten bundled voices and engine reopening.

The receipt test invokes the playback-ACK API directly; it does not claim audio
was heard. Playback queue unit tests check ordering and exclusion. No new ASR
accuracy, range, eight-phone capacity or multi-device acoustic measurement was
performed. The user's earlier successful EN/HI two-phone and Classic trials
apply to the legacy app path, not automatically to this new feature.

Manual next check: install the same APK on at least two phones and try LAN group
joining, Talk/auto-play and silent chat. Use **three phones** for DM isolation
and simultaneous-speaker queue behavior. Repeat on a router, with the hotspot
owner hosting, and with a hotspot client hosting; try wrong passwords, paused
joins, creator stop/resume, recipient rejoin, and airplane-mode/offline operation
with the needed local radio re-enabled. Record which phone/router models work.
Keep one additional person able to see the creator code during first join.

Formal ten-language WER/listening, hands-free VAD, release source distribution,
IPv6-only networks, host migration and independent security review remain open.
