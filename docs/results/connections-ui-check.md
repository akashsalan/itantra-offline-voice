# Connections UI check - 13 September 2026

Version: `0.4.1-connections-ui`, code 10. Device: RMX1801 / Android 10 (API 29).

Observed through the actual Android UI hierarchy and a screen capture:

- The bottom navigation says Connections; One-to-one and Groups are prominent
  at the top of that screen.
- Selecting Groups from idle Wi-Fi Direct selects the LAN setup. Only Same
  Wi-Fi and Phone Hotspot are offered as group methods. Help explicitly says
  Wi-Fi Direct and Bluetooth are one-to-one only.
- With this phone on Wi-Fi, network readiness, Create a group, Find a group,
  Refresh and the nearby-list empty state are visible without scrolling.
- Create a group opens a focused dialog with group name, password, 8-64-character
  guidance and the eight-person/creator-running explanation.
- Cancel closes that dialog. This UI check did not create a real saved group,
  send a message, record speech or alter user model imports.
- The status card was aligned to the same full width as the other controls.

This is one handset/layout inspection, not a usability study, an accessibility
certification or a multi-phone group test. Functional evidence remains in
[android-local-groups-tests.txt](android-local-groups-tests.txt) and current build
sizes/hashes/checks in [build-summary.json](build-summary.json). Talk, Messages,
ASR/TTS, model import and message protocol behavior were not changed by this UI
follow-up. Updates use `adb install -r`, without clearing data.
