package com.chencohensolution.journal.network

import android.content.Context
import android.security.KeyChain
import com.chencohensolution.journal.tls.parseX509Certificate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** mTLS over OkHttp, using a client identity the user installed in the Android system KeyChain. */
internal class AndroidMtlsHttpClientFactory(context: Context) : MtlsHttpClientFactory {
    private val appContext = context.applicationContext

    override suspend fun create(identityAlias: String, trustedCas: List<ByteArray>): HttpClient =
        withContext(Dispatchers.IO) {
            val trust = HandshakeCertificates.Builder()
                .addPlatformTrustedCertificates()
                .apply { trustedCas.forEach { addTrustedCertificate(parseX509Certificate(it)) } }
                .build()

            // KeyChain blocks, so it must stay off the main thread.
            val privateKey = KeyChain.getPrivateKey(appContext, identityAlias)
                ?: error("Could not access system private key for $identityAlias")
            val chain = KeyChain.getCertificateChain(appContext, identityAlias)
                ?: error("Could not access system certificate chain for $identityAlias")

            val sslContext = SSLContext.getInstance("TLS").apply {
                init(arrayOf(KeyChainKeyManager(identityAlias, privateKey, chain)), arrayOf(trust.trustManager), null)
            }
            val okHttp = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trust.trustManager)
                .build()

            HttpClient(OkHttp) { engine { preconfigured = okHttp } }
        }
}

/**
 * Presents a single KeyChain identity. KeyChain keys may be hardware-backed and non-exportable,
 * so they are handed to the TLS stack directly instead of being copied into a KeyStore.
 */
private class KeyChainKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun getCertificateChain(alias: String?) = chain
    override fun getPrivateKey(alias: String?) = privateKey
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
}
