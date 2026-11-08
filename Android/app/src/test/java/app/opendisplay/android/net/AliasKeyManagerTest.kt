package app.opendisplay.android.net

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AliasKeyManagerTest {
    private val current = "receiver-v3"
    private val legacy = "receiver-v2"
    private val key = object : PrivateKey {
        override fun getAlgorithm() = "RSA"
        override fun getFormat(): String? = null
        override fun getEncoded(): ByteArray? = null
    }

    private val delegate = object : X509KeyManager {
        override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String>? =
            when (keyType) {
                "RSA" -> arrayOf(legacy, current)
                "EC" -> arrayOf(legacy)
                else -> null
            }
        override fun chooseServerAlias(keyType: String, issuers: Array<Principal>?, socket: Socket?) = legacy
        override fun getCertificateChain(alias: String): Array<X509Certificate>? = null
        override fun getPrivateKey(alias: String): PrivateKey? = if (alias == current || alias == legacy) key else null
        override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String>? = arrayOf(legacy)
        override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = legacy
    }

    @Test fun onlySelectedCompatibleServerAliasIsExposed() {
        val manager = AliasKeyManager(delegate, current)
        assertArrayEquals(arrayOf(current), manager.getServerAliases("RSA", null))
        assertEquals(current, manager.chooseServerAlias("RSA", null, null))
        assertEquals(current, manager.chooseEngineServerAlias("RSA", null, null))
        assertNull(manager.getServerAliases("EC", null))
        assertNull(manager.chooseServerAlias("EC", null, null))
        assertNull(manager.chooseEngineServerAlias("EC", null, null))
        assertNull(manager.getServerAliases("DSA", null))
    }

    @Test fun legacyKeysAndClientAliasesAreNotExposed() {
        val manager = AliasKeyManager(delegate, current)
        assertEquals(key, manager.getPrivateKey(current))
        assertNull(manager.getPrivateKey(legacy))
        assertNull(manager.getCertificateChain(legacy))
        assertNull(manager.getClientAliases("RSA", null))
        assertNull(manager.chooseClientAlias(arrayOf("RSA"), null, null))
        assertNull(manager.chooseEngineClientAlias(arrayOf("RSA"), null, null))
    }
}
