package com.chencohensolution.journal.network

import android.content.Context
import android.security.KeyChain
import com.chencohensolution.journal.enrollment.androidKeyStore
import com.chencohensolution.journal.tls.decodePemCertificates
import com.chencohensolution.journal.tls.parseX509Certificate
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
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
import javax.net.ssl.X509TrustManager
import java.security.cert.CertificateException
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SharedPreferencesStore
import com.chencohensolution.journal.tls.ServerPin

/**
 * mTLS over OkHttp. The identity is either a key created by enrollment (app Android Keystore) or
 * one the user installed in the Android system KeyChain.
 */
internal class AndroidMtlsHttpClientFactory(context: Context) : MtlsHttpClientFactory {
    private val appContext = context.applicationContext

    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient =
        withContext(Dispatchers.IO) {
            val okHttp = okHttpClient(identityAlias, trustedCas)
            HttpClient(OkHttp) {
                engine { preconfigured = okHttp }
                // No limits of its own: a request that needs longer than OkHttp's (verifying a large video) sets them.
                install(HttpTimeout)
            }
        }

    /**
     * The OkHttp client under [create], also used directly where OkHttp is needed (the video player streams with it).
     * Blocks (KeyChain and Keystore), so it must stay off the main thread.
     */
    fun okHttpClient(identityAlias: String?, trustedCas: List<ByteArray>): OkHttpClient {
        // Once the server's key (serverInfo.discovery) or its own CA (serverInfo.caCertificate) is kept, they are
        // checked instead of the app's CA and the host name, so the server is reached at any address
        // (docs/plans/discovery.md, "Phones"; journal-server-go docs/install.md, "A new server, and the CA").
        val settings = JournalSettings(SharedPreferencesStore(appContext))
        val pin = settings.serverFingerprint
        val serverCas = settings.serverCa?.let(::decodePemCertificates).orEmpty()
        val kept = pin != null || serverCas.isNotEmpty()
        val trustManager = if (kept) {
            KeptServerTrustManager(serverCas.map(::parseX509Certificate), pin)
        } else {
            HandshakeCertificates.Builder()
                .addPlatformTrustedCertificates()
                .apply { trustedCas.forEach { addTrustedCertificate(parseX509Certificate(it)) } }
                .build()
                .trustManager
        }

        val keyManagers = identityAlias?.let { arrayOf(keyManager(it)) }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .apply { if (kept) hostnameVerifier { _, _ -> true } }
            // OkHttp waits 10 s by default; the server answers an upload piece only after writing and syncing it,
            // and the last one only after hashing the whole file, which takes longer for a large video.
            .readTimeout(2, TimeUnit.MINUTES)
            .writeTimeout(2, TimeUnit.MINUTES)
            .build()
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
 * Accepts the server this phone knows: one whose certificate chains to the server's own CA [serverCas] and is for a
 * server (serverAuth), or whose key (SubjectPublicKeyInfo) hashes to [fingerprint]; whatever its host name or address.
 * Any other is refused. Client certificates aren't checked here (this is the phone's side).
 */
internal class KeptServerTrustManager(serverCas: List<X509Certificate>, private val fingerprint: String?) : X509TrustManager {
    private val byCa: X509TrustManager? = serverCas.takeIf { it.isNotEmpty() }?.let { cas ->
        HandshakeCertificates.Builder().apply { cas.forEach { addTrustedCertificate(it) } }.build().trustManager
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The server sent no certificate")
        if (byCa != null && runCatching { byCa.checkServerTrusted(chain, authType) }.isSuccess &&
            leaf.extendedKeyUsage?.contains(SERVER_AUTH) == true
        ) return
        if (fingerprint != null && ServerPin.fingerprintOfSpki(leaf.publicKey.encoded).equals(fingerprint, ignoreCase = true)) return
        throw CertificateException("The server isn't the one this phone knows")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("Not a server")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private companion object {
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
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
