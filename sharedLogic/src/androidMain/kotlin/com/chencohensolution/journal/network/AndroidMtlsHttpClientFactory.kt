package com.chencohensolution.journal.network

import android.content.Context
import android.security.KeyChain
import com.chencohensolution.journal.enrollment.androidKeyStore
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
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * mTLS over OkHttp. The identity is either a key created by enrollment (app Android Keystore) or
 * one the user installed in the Android system KeyChain.
 */
internal class AndroidMtlsHttpClientFactory(context: Context) : MtlsHttpClientFactory {
    private val appContext = context.applicationContext

    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient =
        withContext(Dispatchers.IO) {
            val trust = HandshakeCertificates.Builder()
                .addPlatformTrustedCertificates()
                .apply { trustedCas.forEach { addTrustedCertificate(parseX509Certificate(it)) } }
                .build()

            val keyManagers = identityAlias?.let { arrayOf(keyManager(it)) }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(keyManagers, arrayOf(trust.trustManager), null)
            }
            val okHttp = OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trust.trustManager)
                // OkHttp waits 10 s by default; the server answers an upload piece only after writing and syncing it,
                // and the last one only after hashing the whole file, which takes longer for a large video.
                .readTimeout(2, TimeUnit.MINUTES)
                .writeTimeout(2, TimeUnit.MINUTES)
                .build()

            HttpClient(OkHttp) { engine { preconfigured = okHttp } }
        }

    /** Blocks (KeyChain and Keystore), so it must stay off the main thread. */
    private fun keyManager(alias: String): KeyChainKeyManager {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(alias)) {
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey
                ?: error("Could not access the device key for $alias")
            val chain = keyStore.getCertificateChain(alias)?.map { it as X509Certificate }?.toTypedArray()
                ?: error("Could not access the device certificate for $alias")
            return KeyChainKeyManager(alias, privateKey, chain)
        }
        val privateKey = KeyChain.getPrivateKey(appContext, alias)
            ?: error("Could not access system private key for $alias")
        val chain = KeyChain.getCertificateChain(appContext, alias)
            ?: error("Could not access system certificate chain for $alias")
        return KeyChainKeyManager(alias, privateKey, chain)
    }
}

/**
 * Presents a single KeyChain or Keystore identity. Such keys may be hardware-backed and non-exportable,
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
