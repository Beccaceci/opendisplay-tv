# Xiaomi TV support plan

## Goal

Use the Xiaomi Android TV as a real extended desktop for the MacBook, over the
home Wi-Fi network. Keep setup simple: open the receiver on the TV, select it
once on the Mac, then use remembered-device reconnect when both apps are
available again.

## Starting point

This branch adapts the GPL-3.0 OpenDisplay project. The repository already has
a macOS sender that creates a virtual display, captures and encodes it, and
discovers Android receivers with Bonjour. Its Android app already decodes the
video stream and advertises itself to the Mac. Reusing that protocol avoids an
AirPlay reimplementation and its interoperability/security complications.

The codebase says Android Wi-Fi receivers are already remembered and can
reconnect automatically after a first manual selection. Its current Android
receiver is designed around phones/tablets, so Android TV launcher behavior,
remote navigation, persistent receiver lifecycle, and Xiaomi decoding still
need device validation.

## Implemented

- Added the Leanback launcher category and a TV banner so the receiver can be
  listed in an Android TV launcher.
- Marked touch input optional so a TV is not excluded as a touchless device.
- Report Android TV as its device type and omit the shake-for-settings hint.
- Added a clear focus outline to the Settings button for remote navigation.
- Replaced the Wi-Fi service type with `_opendisplay._tcp` and the receiver
  port with 9443 so the fork does not collide with the original receiver or
  Xiaomi's existing service on port 9000.
- Added persistent Mac and TV TLS identities, a matching fingerprint code,
  first-pair approval on both screens, remembered-device tokens, certificate
  pinning, and a TV-side Forget Mac action. There is no plaintext fallback;
  the UDP cursor channel is disabled.
- Added a Mac Forget TV action and made Accessibility permission optional, so
  an extended display does not wait for touch input that a TV cannot provide.
- Remembered Wi-Fi receivers now reconnect whenever Bonjour discovery sees
  them, including after the receiver opens or Wi-Fi returns. A deliberate
  Disconnect removes that remembered target.
- Added a visible macOS login-item switch using `SMAppService.mainApp`.
- Updated the Mac first-run text for the TV workflow and diagnose Bonjour's
  `NoAuth` response as a local-network permission problem.

## Remaining work

- Resolve the TLS handshake failure between the signed Mac app and Xiaomi
  receiver. Both devices are discoverable/reachable and the receiver listens on
  9443, but macOS reports `-9816` and Android logs an `SSLHandshakeException`
  during `startHandshake`. IPv4-only routing, TLS 1.2 negotiation, an RSA TV
  identity, and allowing the handshake to complete before certificate pinning
  did not resolve it. Pairing has not started; do not weaken encryption.
- Complete a real stream and verify screen capture, virtual display, TV video,
  reconnect, Wi-Fi interruption, pointer behavior, display recovery, and a
  sustained session. Screen Recording and Local Network permissions are enabled.
- Verify login-item registration with the signed app and finish TV-side
  disconnect controls.
- Finish install, removal, and recovery instructions. Keep TV debugging enabled
  only until device checks are complete, then turn it off.

## AirScreen and REA

REA is appropriate for documenting AirScreen's observable setup and runtime
behavior, but the new app should be an independent implementation. Do not copy
AirScreen code, assets, protocol internals, or attempt to bypass its paid
limits. A read-only copy of the TV-installed package was captured for analysis;
REA's full package inspection did not return within several minutes, so no
AirScreen implementation conclusions are claimed from that attempt.

## Validation status

The latest Android debug build, unit tests, and lint pass. The signed Mac debug
app builds and verifies with the Apple Development identity; all 148 Mac tests
pass. Screen Recording and Local Network permissions are enabled, and Bonjour
finds the foregrounded Xiaomi MiTV-MSSP2 (Android 11) on the same network. The
TV listens on TLS port 9443. A secure session has not been established: Android
reports a read error during TLS `startHandshake`, and macOS retries with
`-9816`. The pairing code, stream, display recovery, reconnect and sustained-
session checks remain unverified. Do not call device acceptance complete until
a desktop is visibly streaming to the TV and the required checks pass.
