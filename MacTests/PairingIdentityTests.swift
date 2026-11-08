import XCTest
import Network

final class PairingIdentityTests: XCTestCase {
    func testIdentityCertificateIsStableAndHasSHA256Fingerprint() throws {
        let first = try PairingIdentity.loadOrCreate()
        let second = try PairingIdentity.loadOrCreate()

        XCTAssertEqual(first.certificateDER, second.certificateDER)
        XCTAssertEqual(first.fingerprint, second.fingerprint)
        XCTAssertEqual(first.fingerprint.count, 64)
    }

    func testComparisonCodeIsSymmetricAndIncludesGrouping() {
        let mac = String(repeating: "A1", count: 32)
        let tv = String(repeating: "B2", count: 32)

        let fromMac = PairingIdentity.comparisonCode(mac, tv)
        let fromTV = PairingIdentity.comparisonCode(tv, mac)

        XCTAssertEqual("03A2-C33F-1F88", fromMac)
        XCTAssertEqual(fromMac, fromTV)
        XCTAssertEqual(fromMac.replacingOccurrences(of: "-", with: "").count, 12)
        XCTAssertEqual(fromMac.filter { $0 == "-" }.count, 2)
    }

    func testTrustedPeerLifecycle() throws {
        try PairingIdentity.forgetTrustedPeer()
        XCTAssertNil(try PairingIdentity.trustedPeer())

        let peer = PairingIdentity.TrustedPeer(fingerprint: "11223344556677889900AABBCCDDEEFF11223344556677889900AABBCCDDEEFF",
                                               token: "FFEEDDCCBBAA00998877665544332211FFEEDDCCBBAA00998877665544332211",
                                               isPaired: false)
        try PairingIdentity.saveTrustedPeer(peer)

        let loaded = try PairingIdentity.trustedPeer()
        XCTAssertEqual(peer, loaded)
        XCTAssertFalse(loaded?.isPaired ?? true)

        let paired = PairingIdentity.TrustedPeer(fingerprint: peer.fingerprint,
                                                 token: peer.token,
                                                 isPaired: true)
        try PairingIdentity.saveTrustedPeer(paired)
        let loadedPaired = try PairingIdentity.trustedPeer()
        XCTAssertEqual(paired, loadedPaired)
        XCTAssertTrue(loadedPaired?.isPaired ?? false)

        try PairingIdentity.forgetTrustedPeer()
        XCTAssertNil(try PairingIdentity.trustedPeer())
    }

    func testTLSLoopbackCertificateExtraction() throws {
        let identity = try PairingIdentity.loadOrCreate()
        guard let serverSecIdentity = sec_identity_create(identity.identity) else {
            XCTFail("Failed to create sec_identity for server")
            return
        }

        let serverTLSOptions = NWProtocolTLS.Options()
        sec_protocol_options_set_local_identity(serverTLSOptions.securityProtocolOptions, serverSecIdentity)
        let listener = try NWListener(using: NWParameters(tls: serverTLSOptions, tcp: NWProtocolTCP.Options()))

        let expectation = expectation(description: "TLS handshake and cert extraction")
        var serverConn: NWConnection?
        var clientConn: NWConnection?

        listener.newConnectionHandler = { incoming in
            serverConn = incoming
            incoming.start(queue: .main)
        }

        let listenerReady = self.expectation(description: "Listener ready")
        listener.stateUpdateHandler = { state in
            if case .ready = state {
                listenerReady.fulfill()
            }
        }
        listener.start(queue: .main)
        wait(for: [listenerReady], timeout: 5.0)

        guard let port = listener.port else {
            XCTFail("Listener port is nil")
            return
        }

        let clientTLSOptions = NWProtocolTLS.Options()
        var extractedFingerprint: String?

        // Test the direct capture in verify_block
        sec_protocol_options_set_verify_block(clientTLSOptions.securityProtocolOptions, { _, secTrust, complete in
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            if let cert = SecTrustGetCertificateAtIndex(trust, 0) {
                let data = SecCertificateCopyData(cert) as Data
                extractedFingerprint = PairingIdentity.fingerprint(data)
            }
            complete(true)
        }, .main)

        let client = NWConnection(host: "127.0.0.1", port: port, using: NWParameters(tls: clientTLSOptions, tcp: NWProtocolTCP.Options()))
        clientConn = client

        client.stateUpdateHandler = { state in
            if case .ready = state {
                expectation.fulfill()
            }
        }
        client.start(queue: .main)

        wait(for: [expectation], timeout: 5.0)

        XCTAssertEqual(extractedFingerprint, identity.fingerprint)

        client.cancel()
        serverConn?.cancel()
        listener.cancel()
    }
}
