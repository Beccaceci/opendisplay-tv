package app.opendisplay.android.net

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Calendar
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

/** Stable per-install TLS identity for the Wi-Fi receiver. */
object ReceiverIdentity {
    private const val KEYSTORE = "AndroidKeyStore"
    // Conscrypt's CryptoUpcalls.rsaSignDigestWithPrivateKey executes raw
    // RSA operations via Cipher.getInstance("RSA/ECB/NoPadding") in DECRYPT_MODE.
    // Therefore, the KeyStore key requires PURPOSE_DECRYPT and ENCRYPTION_PADDING_NONE.
    private const val ALIAS = "opendisplay.receiver.tls.v4"

    data class Identity(val certificate: X509Certificate, val serverSocketFactory: SSLServerSocketFactory) {
        val fingerprint: String
            get() = sha256Fingerprint(certificate.encoded)
    }

    fun loadOrCreate(context: Context): Identity {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        migrateIdentityIfNeeded(store, context)

        val refreshed = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val certificate = refreshed.getCertificate(ALIAS) as? X509Certificate
            ?: error("Receiver TLS certificate is unavailable")
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(refreshed, null) }
        val selected = keyManagerFactory.keyManagers.filterIsInstance<X509KeyManager>().firstOrNull { manager ->
            manager.getCertificateChain(ALIAS)?.firstOrNull()?.encoded?.contentEquals(certificate.encoded) == true &&
                manager.getPrivateKey(ALIAS) != null
        } ?: error("Receiver TLS alias has no matching certificate and private key")
        val keyManagers = arrayOf<KeyManager>(AliasKeyManager(selected, ALIAS))
        val tls = SSLContext.getInstance("TLS").apply { init(keyManagers, null, SecureRandom()) }
        return Identity(certificate, tls.serverSocketFactory)
    }

    private fun generate(context: Context) {
        val start = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }.time
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }.time
        // RSA is supported consistently by older Android TV TLS providers as
        // a server signing key; the certificate remains pinned by fingerprint.
        val generator = KeyPairGenerator.getInstance("RSA", KEYSTORE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_SIGN or
                    android.security.keystore.KeyProperties.PURPOSE_VERIFY or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT,
            )
                .setKeySize(2048)
                .setDigests(
                    android.security.keystore.KeyProperties.DIGEST_NONE,
                    android.security.keystore.KeyProperties.DIGEST_SHA256,
                    android.security.keystore.KeyProperties.DIGEST_SHA384,
                    android.security.keystore.KeyProperties.DIGEST_SHA512,
                )
                .setSignaturePaddings(
                    android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                    android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                )
                .setEncryptionPaddings(
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE,
                    android.security.keystore.KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1,
                )
                .setRandomizedEncryptionRequired(false)
                .setCertificateSubject(X500Principal("CN=OpenDisplay Android TV"))
                .setCertificateSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
                .setCertificateNotBefore(start)
                .setCertificateNotAfter(end)
                .build()
            generator.initialize(spec)
        } else {
            @Suppress("DEPRECATION")
            val spec = KeyPairGeneratorSpec.Builder(context)
                .setAlias(ALIAS)
                .setKeyType("RSA")
                .setKeySize(2048)
                .setSubject(X500Principal("CN=OpenDisplay Android TV"))
                .setSerialNumber(BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE))
                .setStartDate(start)
                .setEndDate(end)
                .build()
            @Suppress("DEPRECATION")
            generator.initialize(spec)
        }
        generator.generateKeyPair()
    }

    /** Provision the new alias once, retaining earlier keys for explicit rollback/re-pairing. */
    private fun migrateIdentityIfNeeded(store: KeyStore, context: Context) {
        if (!store.containsAlias(ALIAS)) generate(context)
        // Exercise the actual AndroidKeyStore private key before the TLS server
        // advertises this identity. A failed check aborts startup without
        // deleting the previous EC or v2 RSA identity.
        val refreshed = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val privateKey = refreshed.getKey(ALIAS, null) as? PrivateKey
            ?: error("Receiver TLS private key is unavailable")
        val certificate = refreshed.getCertificate(ALIAS) as? X509Certificate
            ?: error("Receiver TLS certificate is unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val info = KeyFactory.getInstance("RSA", KEYSTORE).getKeySpec(
                privateKey, android.security.keystore.KeyInfo::class.java,
            )
            check(android.security.keystore.KeyProperties.DIGEST_NONE in info.digests) {
                "Receiver TLS key does not authorize raw-digest signing"
            }
            check(android.security.keystore.KeyProperties.SIGNATURE_PADDING_RSA_PSS in info.signaturePaddings) {
                "Receiver TLS key does not authorize RSA-PSS"
            }
            check(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE in info.encryptionPaddings) {
                "Receiver TLS key does not authorize ENCRYPTION_PADDING_NONE for Conscrypt raw RSA operations"
            }
        }
        // Test Conscrypt's raw RSA CryptoUpcalls path
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, privateKey)

        val payload = "OpenDisplay TLS identity self-test".toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(privateKey)
            update(payload)
        }.sign()
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(certificate.publicKey)
            update(payload)
            verify(signature)
        }) { "Receiver TLS certificate and private key do not match" }
    }

    fun sha256Fingerprint(certificateDer: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(certificateDer)
        .joinToString("") { "%02X".format(it) }

    /** A human-comparable code with enough entropy to make fingerprint spoofing impractical. */
    fun comparisonCode(firstFingerprint: String, secondFingerprint: String): String {
        val pair = listOf(firstFingerprint.uppercase(), secondFingerprint.uppercase()).sorted()
            .joinToString(":")
        val digest = MessageDigest.getInstance("SHA-256").digest(pair.toByteArray(Charsets.UTF_8))
        // 12 hex digits (48 bits), grouped for reading aloud or comparing on TV.
        val code = digest.take(6).joinToString("") { "%02X".format(it) }
        return code.chunked(4).joinToString("-")
    }
}
