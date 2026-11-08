package app.opendisplay.android.net

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/** Exposes only the certificate whose fingerprint the receiver advertises for pairing. */
internal class AliasKeyManager(
    private val delegate: X509KeyManager,
    private val alias: String,
) : X509ExtendedKeyManager() {
    private fun available(keyType: String, issuers: Array<Principal>?): Boolean =
        delegate.getServerAliases(keyType, issuers)?.contains(alias) == true

    override fun getServerAliases(keyType: String, issuers: Array<Principal>?): Array<String>? =
        if (available(keyType, issuers)) arrayOf(alias) else null

    override fun chooseServerAlias(keyType: String, issuers: Array<Principal>?, socket: Socket?): String? =
        if (available(keyType, issuers)) alias else null

    override fun chooseEngineServerAlias(keyType: String, issuers: Array<Principal>?, engine: SSLEngine?): String? =
        if (available(keyType, issuers)) alias else null

    override fun getCertificateChain(requestedAlias: String): Array<X509Certificate>? =
        if (requestedAlias == alias) delegate.getCertificateChain(alias) else null

    override fun getPrivateKey(requestedAlias: String): PrivateKey? =
        if (requestedAlias == alias) delegate.getPrivateKey(alias) else null

    // The TV is a TLS server; it never supplies a client certificate.
    override fun getClientAliases(keyType: String, issuers: Array<Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyTypes: Array<String>?, issuers: Array<Principal>?, socket: Socket?): String? = null
    override fun chooseEngineClientAlias(keyTypes: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?): String? = null
}
