# Xiaomi TV Wireless Display Work Log

**Project:** OpenDisplay Mac sender and Xiaomi Android TV receiver  
**Last updated:** 2026-11-08 (Europe/Rome)  
**Purpose:** Append dated notes as implementation, testing, and device work continues.

## Current status

The debug Mac app is signed and installed, and the receiver APK is installed on
the Xiaomi MiTV-MSSP2 running Android 11. The Mac has Screen Recording and
Local Network permission. The TV is discoverable over Bonjour and listens on
secure TCP port 9443. **Pairing has not completed, so the TV has not yet been
verified as a second display.**

The Mac reports TLS error `-9816`; the TV logs an `SSLHandshakeException`
during `SSLSocket.startHandshake()`. The matching-code pairing prompt never
appears. The Mac retry loop was stopped after the latest attempt. No plaintext
fallback was enabled.

## Work log

### 2026-11-08 — Source and evidence review

- **Reviewed:** The work log, Android identity/server implementation, Mac TLS
  and pairing flow, and identity tests against official Android/TLS references.
- **Found:** Incomplete signing authorizations, unrestricted server certificate
  alias selection, and a 10-second pre-auth read timeout that includes the Mac
  user's confirmation delay. The exact cause on the device is still unproven.
- **Clarification:** AirScreen's success supports network/display feasibility;
  earlier failed experiments do not establish a TV-wide TLS incompatibility.
- **Next:** Follow the controlled repair and validation sequence in
  [TLS_REVIEW.md](TLS_REVIEW.md). This session inspected source and documented
  findings; it did not change app code, install builds, or rerun device tests.

### 2026-11-07 — Device and build setup

- Added Android TV launcher support, remote-focus styling, TV-specific receiver
  information, and receiver settings/actions.
- Added the secured Wi-Fi protocol path on port 9443 and Bonjour service
  `_opendisplay._tcp`, keeping the upstream unencrypted path separate.
- Installed the receiver on the Xiaomi TV and enabled the needed Mac
  permissions. TV debugging remains enabled for development and installation.
- Signed the Mac debug build with the Apple Development identity after the
  account and system authorization steps were completed.

### 2026-11-08 — Secure connection investigation

- Confirmed the TV receiver advertises over Bonjour and that TCP port 9443 is
  reachable from the Mac while the receiver is open.
- Confirmed the signed Mac app can discover the TV. The app remains idle after
  disconnecting; no pairing was stored.
- Tested IPv4-constrained discovery, TLS 1.2-only negotiation, an RSA
  Android Keystore identity, and completing TLS before checking the pinned TV
  fingerprint. These changes did not resolve the handshake.
- Removed the unnecessary Mac client certificate from the TLS handshake. Mac
  identity and pairing authentication remain in the encrypted protocol; the TV
  certificate is pinned before application data is sent. The handshake still
  did not complete.
- Cleared the temporary direct-host preference. Normal discovery uses Bonjour.
- Stopped the Mac connection retries after testing. No pairing code or video
  stream was established.

## Validation

- Android debug APK builds and installs successfully.
- Android `testDebugUnitTest` and `lintDebug` pass on the current source.
- Signed Mac debug app builds and verifies successfully.
- Mac test suite: 148 passed, 0 failed.
- Device validation remains incomplete: pairing, video, pointer, reconnect,
  sleep/wake, Wi-Fi interruption, and sustained-session checks have not passed.

## Next steps

1. Diagnose the TLS handshake with explicit phase logging or a packet trace on
   both devices. Determine whether the Mac sends ClientHello and whether the TV
   returns ServerHello and its certificate.
2. Test the Android Keystore certificate/key with a minimal TLS server and
   test the Mac Network.framework client against a known-good TLS server.
3. Fix the failing handshake without adding an unencrypted fallback, rebuild,
   and retry pairing until the matching code appears on both devices.
4. After pairing, verify an extended desktop, video, pointer, disconnect,
   reconnect, sleep/wake, Wi-Fi changes, and a sustained session.
5. Finish install/removal/recovery instructions and turn off USB, Network, and
   Wireless debugging (or Developer options) after device checks pass.

## Entry template

Copy this block to the top of **Work log** when resuming:

```markdown
### YYYY-MM-DD — Short session title

- **Changed:**
- **Tested:**
- **Result:**
- **Next:**
```
