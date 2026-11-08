# Plan: repair and validate Mac-to-Xiaomi secure display

Date: 2026-11-08 (Europe/Rome)
Status: planned; no implementation or device changes performed by this plan.

## Objective and boundaries

Establish a verified encrypted connection, complete matching-code pairing,
and use the Xiaomi as an extended Mac desktop. Each repair must have its own
evidence and regression checks. We will implement against the existing
OpenDisplay branch, preserve its license and stable development app identities,
and avoid replacing unrelated work already in the working tree.

AirScreen's prior success supports the feasibility of this network and display
workflow. It does not validate our server certificate, Android Keystore signing,
or Network.framework transport. AirScreen reverse engineering is not a dependency.

The source review identifies defects in key permissions, alias selection, and
pairing deadlines. Which defect caused the observed handshake failure remains
unproven. Generic closure errors and unit-test totals are not root-cause evidence.

## Independent workstreams

| Workstream | Main files | Independent deliverable | Integration dependency |
| --- | --- | --- | --- |
| A: Evidence | Android ReceiverServer/ReceiverIdentity; Mac MacSender | Correlated handshake diagnostics | First device experiment |
| B: Certificate selection | Android ReceiverIdentity and new key-manager helper | Exactly one selected identity | Combine with C for device TLS |
| C: Key permissions | Android ReceiverIdentity and device tests | Verified signing policy and migration | A and B for trustworthy wire evidence |
| D: Pairing deadlines | Android ReceiverServer; Mac MacSender and app UI | Explicit protocol states and bounded deadlines | Can be tested with a fake transport |
| E: Interoperability | Diagnostic clients/servers and test documentation | Independent TLS controls | B+C before full app integration |
| F: Product acceptance | Sender/receiver connection and display code | Paired, reconnecting extended desktop | D+E |

Independent means separately implementable and testable. It does not mean
changing several variables during the same device experiment. B and C share a
source file, so integrate their changes sequentially. Use separate commits or
reviewable patches; do not commit unrelated pre-existing changes.

## A. Establish trustworthy evidence

1. Record the current source revision, dirty diff, app versions, signing
   identity, APK version, Android API/provider, Mac OS, and current network
   endpoint. Preserve the existing build artifacts as the diagnostic baseline.
2. Add an attempt identifier and monotonic elapsed time to each side's logs.
   Record TCP accept/connect, TLS start, selected alias and public fingerprint,
   TLS verification callback entry/exit, negotiated protocol/cipher, auth
   message receipt, approval result, and connection close reason.
3. Log full exception cause chains on Android. Distinguish handshake failure
   from auth-message timeout, certificate mismatch, user cancellation, receiver
   lifecycle stop, and the Mac dial deadline. Explicitly set server mode.
4. Record the receiver's foreground/sleep state. Label independent probes so
   an intentional TCP-only disconnect is never confused with an app attempt.
5. Do not log pairing tokens, private keys, or desktop frame contents.

Pass condition: one attempt can be followed from accept to close, with the last
successful stage and local reason for closure identified. If logs cannot answer
whether ClientHello and ServerHello were exchanged, capture only the relevant
host/port handshake traffic and document any required capture authorization.

## B. Bind TLS to the intended certificate

Implement an alias-restricted X509ExtendedKeyManager. Cover both socket and
engine server-alias selection. Return the configured alias only when compatible
with the requested key type/issuer constraints; otherwise return no match.
Certificate-chain and private-key access must refer to the same selected alias.
Do not offer unrelated keys from the app keystore.

Keep one identity object as the source for both TLS and pairing fingerprints.
After a successful handshake, compare the certificate actually served with that
object. Keep existing old aliases intact while investigating; do not rely on
keystore enumeration order or delete keys to force selection.

Tests: a keystore with both EC and RSA aliases; compatible and incompatible
requests; socket and engine callbacks; absent certificate/key; and identical
fingerprints between the served leaf certificate and pairing identity.

Pass condition: every successful handshake serves exactly the selected leaf
certificate, regardless of other aliases or their insertion order.

## C. Repair signing permissions and identity migration

Inspect KeyInfo for the selected key: purposes, digests, padding restrictions,
and whether it is hardware-backed. Check that its public certificate corresponds
to its private key using sign/verify operations. Log public metadata only.

Retain RSA initially to isolate the permission repair from an algorithm change.
Authorize the digests and signing operations required by the actual TLS provider,
including DIGEST_NONE where the provider signs precomputed digests. Authorize
RSA-PSS as well as PKCS#1 signing for TLS 1.3 and 1.2 compatibility. Test provider
behavior on the TV; do not assume that a generic Java Signature test alone
exercises Conscrypt's raw signing path. Do not enable unrelated operations merely
to make an error disappear.

Existing key authorizations cannot be repaired by editing the generator. Build
an explicit versioned migration:

- Reuse an existing identity when its policy and signing checks pass.
- Generate a replacement under a new alias if the current policy is unusable.
- Validate signing and local TLS before marking the replacement active.
- Preserve the old key for rollback; never erase all app data as a shortcut.
- If a real pairing exists, explain that identity rotation requires re-pairing.
  Never silently replace the Mac's pinned fingerprint or reuse old trust as if
  the receiver certificate had not changed.

Device tests: fresh identity generation, app restart stability, migration from
both legacy aliases, failed migration recovery, actual signing, and independent
TLS 1.2/1.3 handshakes. Host unit tests cannot validate Android Keystore behavior.

Pass condition: the selected TV key signs the negotiated TLS handshake, its
fingerprint stays stable across restarts, and failed migration preserves the
previous usable state. If this TV cannot support a required algorithm, record
the exact provider error before selecting a different supported secure identity.

## D. Separate networking deadlines from human approval

Use explicit states: connecting, TLS negotiation, identity check, awaiting Mac
approval, awaiting TV approval, authenticated, streaming, closing.

Initial policy: 15 seconds for connection/TLS, 90 seconds for each human approval
stage, and 10 seconds for the final machine-to-machine response. Apply real
stage deadlines, not only per-read timeouts that partial input can keep resetting.

Send a bounded pairing-intent message after checking the TV certificate and
before showing the Mac confirmation dialog. This allows the receiver to enter
the human-approval state without leaving every unknown connection idle for 90
seconds. Show the code on both devices before confirmation; streaming requires
both approvals. Version this protocol change and show a clear mismatch message.

Keep a small limit on pending unauthenticated connections. Cancel timers and
close pending sockets when the receiver exits. A stale callback from an earlier
connection must not mutate or reconnect the current session. User Disconnect
must cancel attempts and approval dialogs and pause reconnect until Connect.

Tests: approve after 20 seconds; expire each stage; cancel on either device;
receiver exit during approval; stale callback after a new attempt; incomplete
frames; version mismatch; and a second unknown client during a live session.
Use a controllable clock for state-machine tests and one real delayed approval
check on the devices.

Pass condition: a normal reading delay no longer causes a network timeout;
expired or rejected attempts release resources and never start streaming.

## E. Prove interoperability before the full workflow

Run these controls with the receiver awake and each test client identified:

| Client | Server | What it establishes |
| --- | --- | --- |
| Independent TLS client | TV receiver, TLS 1.2 | TV certificate/signing and TLS 1.2 |
| Independent TLS client | TV receiver, TLS 1.3 | TV PSS/provider and TLS 1.3 |
| Minimal Mac Network.framework client | Known-good test TLS server | Mac TLS setup and callback execution |
| Minimal Mac client | TV receiver | Cross-platform handshake without display/UI |
| Full Mac app | TV receiver | Pairing integration after transport passes |

For each successful TV handshake, verify the actual leaf fingerprint against
the selected identity. Independent clients that stop after TLS are expected to
fail application authentication; report TLS success separately from that exit.
Never count disabling pinning in the production app as a successful repair.

If independent clients fail against the TV, investigate server/provider/key
evidence. If they pass but the minimal Mac client fails, inspect Mac TLS options,
verification callback scheduling, and peer metadata. If minimal clients pass
but the full app fails, inspect lifecycle, retries, and pairing state. Restore
normal dual-stack routing after confirming it works; retain an IPv4 workaround
only with reproducible evidence that it is needed.

Pass condition: reproducible encrypted handshakes with correct fingerprints,
followed by successful matching-code pairing and rejection of wrong pins/codes.

## F. Finish security and display acceptance

Before streaming, check the authentication model: the Mac's transmitted
fingerprint alone is not proof of private-key possession. The current design
uses user-approved first pairing and a remembered secret for subsequent client
authentication. Document that model accurately, keep secrets protected, and
test possession of the remembered secret, unauthorized replacement, and the
one-paired-Mac policy. If asymmetric client authentication is required, design
and test a signed challenge rather than treating a fingerprint as authentication.

Then run the original device acceptance checks:

- Move a window onto a genuine extended desktop; verify pointer movement and
  hardware video encoding/decoding. Start with 1080p/30 where supported. The TV
  previously reported a 1280x720 surface; measure the actual output and record
  any negotiated limit rather than claiming 1080p from configuration alone.
- Verify remote navigation, Settings, Disconnect, Forget, and receiver exit.
- Verify automatic connection within 30 seconds on a healthy network, deliberate
  disconnect pause, receiver reopen, Mac sleep/wake, Wi-Fi loss, and changed IP.
- Verify that disconnection removes the virtual display and returns windows.
- Run a 30-minute desktop session, recording memory at regular intervals,
  crashes/freezes, stream statistics, and observed pointer usability.
- Verify launch-at-login and its off switch, install/removal instructions,
  and stable app identities/permissions across an update.
- Disable TV debugging after the final install and confirm ordinary discovery
  and streaming still work. Record how to re-enable it for development updates.

## Execution order and completion record

Implement A first. Prepare B, C, and D as separate patches; integrate B before
C so the key under test is unambiguous. Run E after B+C; integrate D for full
pairing. Complete F only after pairing is repeatable. Stop changing unrelated
variables as soon as a failing layer is isolated.

Append each experiment to WORK_LOG.md with source/build identifiers, device
state, the single changed variable, expected observation, actual result, evidence
location, and whether the change is retained or reverted. Update TLS_REVIEW.md
when a hypothesis becomes confirmed or disproven.

Delivery includes source patches, focused regression/device tests, signed local
Mac app, APK, installation/removal/recovery instructions, and an acceptance
record listing passed and outstanding checks. Completion requires working
device checks, not just compilation. Public publishing and release signing are
outside this delivery.

## References

- [Source review](TLS_REVIEW.md)
- [Work log](WORK_LOG.md)
- [Android key authorization reference](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [TLS 1.3 signing requirements](https://www.rfc-editor.org/rfc/rfc8446.html#section-4.4.3)
