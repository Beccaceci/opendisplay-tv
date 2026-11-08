import CryptoKit
import Foundation
import Network
import Security

/// Persistent self-signed device identity used by the secured Wi-Fi transport.
/// The private key stays in the macOS Keychain; only its certificate is stored
/// alongside it so the same fingerprint survives app restarts.
struct PairingIdentity {
    private static let keyTag = Data("app.opendisplay.mac.identity.v1".utf8)
    private static let certificateService = "app.opendisplay.mac.identity"
    private static let certificateAccount = "certificate.der"
    private static let peerService = "app.opendisplay.mac.peer"
    private static let peerAccount = "paired-tv"

    struct TrustedPeer: Codable, Equatable {
        let fingerprint: String
        let token: String
        let isPaired: Bool
    }

    let certificate: SecCertificate
    let privateKey: SecKey
    let identity: SecIdentity
    let certificateDER: Data

    var fingerprint: String {
        Self.fingerprint(certificateDER)
    }

    static func fingerprint(_ der: Data) -> String {
        SHA256.hash(data: der).map { String(format: "%02X", $0) }.joined()
    }

    static func loadOrCreate() throws -> PairingIdentity {
        let privateKey = try loadOrCreatePrivateKey()
        let certificateDER = try loadOrCreateCertificate(for: privateKey)
        guard let certificate = SecCertificateCreateWithData(nil, certificateDER as CFData) else {
            throw IdentityError.invalidCertificate
        }
        guard let identity = SecIdentityCreate(nil, certificate, privateKey) else {
            throw IdentityError.identityCreation(errSecInternalComponent)
        }
        return PairingIdentity(certificate: certificate,
                               privateKey: privateKey,
                               identity: identity,
                               certificateDER: certificateDER)
    }

    static func comparisonCode(_ firstFingerprint: String, _ secondFingerprint: String) -> String {
        let joined = [firstFingerprint.uppercased(), secondFingerprint.uppercased()]
            .sorted().joined(separator: ":")
        let digest = SHA256.hash(data: Data(joined.utf8))
        let prefix = digest.prefix(6).map { String(format: "%02X", $0) }.joined()
        return stride(from: 0, to: prefix.count, by: 4).map { start in
            let lower = prefix.index(prefix.startIndex, offsetBy: start)
            let upper = prefix.index(lower, offsetBy: min(4, prefix.count - start))
            return String(prefix[lower..<upper])
        }.joined(separator: "-")
    }

    static func trustedPeer() throws -> TrustedPeer? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: peerService,
            kSecAttrAccount as String: peerAccount,
            kSecReturnData as String: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw IdentityError.keychain(status)
        }
        return try JSONDecoder().decode(TrustedPeer.self, from: data)
    }

    static func saveTrustedPeer(_ peer: TrustedPeer) throws {
        let encoded = try JSONEncoder().encode(peer)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: peerService,
            kSecAttrAccount as String: peerAccount,
        ]
        let update = SecItemUpdate(query as CFDictionary, [kSecValueData as String: encoded] as CFDictionary)
        if update == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = encoded
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            let status = SecItemAdd(add as CFDictionary, nil)
            guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        } else if update != errSecSuccess {
            throw IdentityError.keychain(update)
        }
    }

    static func forgetTrustedPeer() throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: peerService,
            kSecAttrAccount as String: peerAccount,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound || status == errSecInvalidOwnerEdit else {
            throw IdentityError.keychain(status)
        }
    }

    static func makeToken() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        guard status == errSecSuccess else { throw IdentityError.keychain(status) }
        return bytes.map { String(format: "%02X", $0) }.joined()
    }

    private static func loadOrCreatePrivateKey() throws -> SecKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: keyTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecReturnRef as String: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let key = result as! SecKey? { return key }
        guard status == errSecItemNotFound else { throw IdentityError.keychain(status) }

        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: keyTag,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ],
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw IdentityError.keyGeneration
        }
        return key
    }

    private static func loadOrCreateCertificate(for privateKey: SecKey) throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: certificateService,
            kSecAttrAccount as String: certificateAccount,
            kSecReturnData as String: true,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecSuccess, let data = result as? Data { return data }
        guard status == errSecItemNotFound else { throw IdentityError.keychain(status) }

        guard let publicKey = SecKeyCopyPublicKey(privateKey),
              let publicKeyData = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else {
            throw IdentityError.keyGeneration
        }
        let der = try X509.selfSignedP256(publicKey: publicKeyData, signingKey: privateKey)
        let add: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: certificateService,
            kSecAttrAccount as String: certificateAccount,
            kSecValueData as String: der,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let addStatus = SecItemAdd(add as CFDictionary, nil)
        guard addStatus == errSecSuccess else { throw IdentityError.keychain(addStatus) }
        return der
    }

    enum IdentityError: Error {
        case keychain(OSStatus)
        case keyGeneration
        case invalidCertificate
        case identityCreation(OSStatus)
    }
}

/// Minimal DER encoder for a self-signed P-256 X.509 v3 leaf certificate.
/// Certificate creation is kept local and uses the platform Keychain key.
private enum X509 {
    static func selfSignedP256(publicKey: Data, signingKey: SecKey) throws -> Data {
        let signatureAlgorithm = sequence(oid([1, 2, 840, 10045, 4, 3, 2])) // ecdsa-with-SHA256
        let name = sequence(set(sequence(oid([2, 5, 4, 3]) + utf8("OpenDisplay Mac"))))
        let now = Date()
        let validity = sequence(utcTime(now.addingTimeInterval(-86_400)) +
                                 utcTime(now.addingTimeInterval(10 * 365 * 86_400)))
        var serialBytes = Data(SHA256.hash(data: Data(UUID().uuidString.utf8)).prefix(16))
        serialBytes[0] &= 0x7f
        if serialBytes.allSatisfy({ $0 == 0 }) { serialBytes[serialBytes.count - 1] = 1 }
        let serial = integer(serialBytes)

        let publicKeyInfo = sequence(
            sequence(oid([1, 2, 840, 10045, 2, 1]) + oid([1, 2, 840, 10045, 3, 1, 7])) +
            bitString(publicKey)
        )
        let extensions = explicit(3, sequence(
            extensionValue(oid([2, 5, 29, 19]), critical: true, value: sequence(Data())) + // CA=false
            extensionValue(oid([2, 5, 29, 15]), critical: true, value: bitString(Data([0x80]))) + // digitalSignature
            extensionValue(oid([2, 5, 29, 37]), critical: false,
                           value: sequence(oid([1, 3, 6, 1, 5, 5, 7, 3, 1]) +
                                           oid([1, 3, 6, 1, 5, 5, 7, 3, 2]))
        )))
        let tbs = sequence(
            explicit(0, integer(Data([2]))) + serial + signatureAlgorithm + name + validity +
            name + publicKeyInfo + extensions
        )
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(signingKey,
                                                     .ecdsaSignatureMessageX962SHA256,
                                                     tbs as CFData,
                                                     &error) as Data? else {
            if let error { throw error.takeRetainedValue() }
            throw PairingIdentity.IdentityError.keyGeneration
        }
        return sequence(tbs + signatureAlgorithm + bitString(signature))
    }

    private static func extensionValue(_ oidValue: Data, critical: Bool, value: Data) -> Data {
        let flag = critical ? boolean(true) : Data()
        return sequence(oidValue + flag + octetString(value))
    }

    private static func oid(_ components: [UInt64]) -> Data {
        precondition(components.count >= 2)
        var encoded = Data()
        let values = [components[0] * 40 + components[1]] + Array(components.dropFirst(2))
        for component in values {
            var value = component
            var bytes = [UInt8(value & 0x7f)]
            value >>= 7
            while value > 0 {
                bytes.insert(UInt8(value & 0x7f) | 0x80, at: 0)
                value >>= 7
            }
            encoded.append(contentsOf: bytes)
        }
        return tagged(0x06, encoded)
    }

    private static func utf8(_ value: String) -> Data { tagged(0x0c, Data(value.utf8)) }
    private static func utcTime(_ date: Date) -> Data {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "yyMMddHHmmss'Z'"
        return tagged(0x17, Data(formatter.string(from: date).utf8))
    }
    private static func integer(_ data: Data) -> Data {
        var bytes = data
        if let first = bytes.first, first & 0x80 != 0 { bytes.insert(0, at: 0) }
        return tagged(0x02, bytes)
    }
    private static func boolean(_ value: Bool) -> Data { tagged(0x01, Data([value ? 0xff : 0])) }
    private static func bitString(_ data: Data) -> Data { tagged(0x03, Data([0]) + data) }
    private static func octetString(_ data: Data) -> Data { tagged(0x04, data) }
    private static func sequence(_ data: Data) -> Data { tagged(0x30, data) }
    private static func set(_ data: Data) -> Data { tagged(0x31, data) }
    private static func explicit(_ number: UInt8, _ data: Data) -> Data { tagged(0xa0 + number, data) }

    private static func tagged(_ tag: UInt8, _ content: Data) -> Data {
        var result = Data([tag])
        if content.count < 128 {
            result.append(UInt8(content.count))
        } else {
            var length = content.count
            var bytes: [UInt8] = []
            while length > 0 {
                bytes.insert(UInt8(length & 0xff), at: 0)
                length >>= 8
            }
            result.append(0x80 | UInt8(bytes.count))
            result.append(contentsOf: bytes)
        }
        result.append(content)
        return result
    }
}
