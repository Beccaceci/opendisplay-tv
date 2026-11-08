# TLS failure review

Date: 2026-11-08 (Europe/Rome)

## Conclusion

The previous log records unsuccessful experiments, not a proven root cause.
AirScreen working is useful evidence of a viable Mac-to-TV network/display
workflow. It does not exercise this receiver's Android Keystore identity,
Network.framework client, or TLS port. The strongest code findings are in our
receiver's key authorization and certificate selection. Diagnose those before
changing network settings again. This review changed documentation only.

## Findings from the source

1. **RSA key authorization is incomplete for the offered TLS versions.**
   `ReceiverIdentity.generate()` permits SHA-256 and PKCS#1 signature padding
   only. Android documents that TLS signing commonly needs `DIGEST_NONE`
   because the TLS stack computes the digest itself. TLS 1.3 RSA
   CertificateVerify requires PSS. The current key policy does not authorize
   PSS. This is a concrete compatibility defect, but not yet proof of the
   particular device failure. The earlier EC implementation also omitted
   explicit digest authorization, so changing EC to RSA was not a controlled
   test of a correctly configured identity.

2. **The TLS certificate is not bound to the configured identity alias.**
   The code reads the v2 certificate to calculate its fingerprint, but passes
   the entire app AndroidKeyStore to KeyManagerFactory. The old EC alias was
   not removed by the update. The provider can choose another compatible
   alias. Thus the RSA experiment did not establish which certificate was
   actually served. A different served certificate would also cause comparison
   codes to disagree after TLS succeeds. Alias selection must be deterministic.

3. **The initial authentication timeout includes human interaction.**
   `ReceiverServer.authorize()` sets a 10-second socket read timeout before
   TLS and leaves it in place while waiting for `secureAuth`. The Mac displays
   a modal confirmation before sending that message. Taking more than ten
   seconds can close an otherwise successful connection. The separate
   90-second TV approval timeout starts only after the request arrives.
   This is a subsequent pairing bug; it does not explain a failure already
   located inside startHandshake.

4. **Current tests do not establish TLS interoperability.**
   The Android identity test checks comparison-code formatting/symmetry.
   Mac identity tests check certificate stability and comparison codes.
   Passing 148 Mac tests and Android lint does not prove that the TV's actual
   Keystore key can sign a TLS handshake or that both apps can pair.

5. **The error log does not identify the failing TLS operation.**
   Several earlier TCP-only probes intentionally closed without speaking TLS.
   Those failures must not be treated as evidence about the app handshake.
   The OpenSSL trial that read zero server bytes is stronger evidence, but it
   still needs correlation with the exact receiver connection and exception.
   Client cancellation, TV lifecycle shutdown, key selection, and key signing
   need distinct log events. An IPv6 advertisement alone does not prove an
   address is stale, and -9816 alone does not prove a certificate defect.

## Repair and validation sequence

1. Add per-connection identifiers and elapsed-time logs: accept, TLS begin,
   selected alias, public certificate fingerprint, signing failure/cause,
   TLS completion/version/cipher, auth request, approval, close initiator.
   Never log private keys or pairing tokens. Explicitly configure server mode.
2. Use an alias-restricted X509ExtendedKeyManager for both socket and engine
   server-alias callbacks. Its certificate chain and private key must be those
   whose fingerprint is used by pairing. Test this with multiple aliases.
3. Validate the selected key's actual KeyInfo authorizations and perform local
   sign/verify checks with the algorithms needed by the TV TLS provider.
   Provision a correctly authorized key (including the raw-digest path where
   required and PSS if RSA is used with TLS 1.3). Existing key authorizations
   are fixed at generation: a source edit alone does not repair an existing
   alias. Preserve existing identities; use explicit migration/re-pairing when
   a key must change, and do not erase all app data as a diagnostic shortcut.
4. Establish one TLS 1.2 and one TLS 1.3 handshake from an independent client
   to the TV and verify the served fingerprint against the configured one.
   Test the Mac client against an independent TLS server as a control. If the
   TV tests fail, focus on its provider/key; if they pass while Mac fails,
   inspect the Mac path. Capture handshake packets if phase logs remain unclear.
5. Give handshake, authentication-message waiting, and human approval separate
   bounded deadlines. Test a deliberate delay longer than ten seconds on the
   Mac confirmation screen. Preserve certificate pinning before app data.
6. Validate matching-code pairing, rejection of changed certificates, saved
   trust, intentional disconnect, and unauthorized session replacement. Then
   resume extended-desktop, pointer, recovery, and 30-minute device checks.

Success at each step must be measured on the actual devices. Neither AirScreen
reverse engineering nor unencrypted fallback is required to investigate these
source defects.

## Primary references

- [Android key generation authorizations](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [Conscrypt key manager implementation](https://android.googlesource.com/platform/external/conscrypt/+/29916ef38dc9cb4e4c6e3fdb87d4e921546d3ef4/common/src/main/java/org/conscrypt/KeyManagerImpl.java)
- [TLS 1.3 CertificateVerify requirements, RFC 8446 section 4.4.3](https://www.rfc-editor.org/rfc/rfc8446.html#section-4.4.3)
