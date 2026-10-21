@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.network

import com.chencohensolution.journal.tls.KeychainIdentities
import com.chencohensolution.journal.tls.secCertificateObject
import com.chencohensolution.journal.tls.withCFBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodClientCertificate
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.credentialWithIdentity
import platform.Foundation.serverTrust
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly

/**
 * mTLS over URLSession, using an identity in the app Keychain: imported by [KeychainIdentities] or
 * created by enrollment (IosDeviceKeyStore).
 */
internal class IosMtlsHttpClientFactory : MtlsHttpClientFactory {

    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient {
        val challenges = MtlsChallenges(identityAlias, trustedCas)
        return HttpClient(Darwin) {
            engine {
                handleChallenge { _, _, challenge, completion ->
                    when (challenge.protectionSpace.authenticationMethod) {
                        NSURLAuthenticationMethodServerTrust -> challenges.serverTrust(challenge)
                            ?.let { completion(NSURLSessionAuthChallengeUseCredential, it) }
                            ?: completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
                        // No client certificate: the server accepts that for enrollment only.
                        NSURLAuthenticationMethodClientCertificate -> challenges.clientCertificate()
                            ?.let { completion(NSURLSessionAuthChallengeUseCredential, it) }
                            ?: completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
                        else -> completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
                    }
                }
            }
            // No limits of its own: a request that needs longer than usual (verifying a large video) sets them.
            install(HttpTimeout)
        }
    }
}

/**
 * The answers to a URLSession's TLS challenges as [identityAlias] (none: enrollment), trusting [trustedCas] besides the
 * system roots. Shared by the Ktor client and the video player's own URLSession (IosServerStream).
 */
internal class MtlsChallenges(identityAlias: String?, trustedCas: List<ByteArray>) {
    private val identity: Any? = identityAlias?.let {
        KeychainIdentities.find(it) ?: error("Identity \"$it\" is not in the Keychain; import it again")
    }
    private val anchors: List<Any> = trustedCas.map(::secCertificateObject)

    /** The server's certificate checked against the journal's CA (and the system roots); null: don't connect. */
    fun serverTrust(challenge: NSURLAuthenticationChallenge): NSURLCredential? {
        val trust = challenge.protectionSpace.serverTrust ?: return null
        withCFBridge(anchors) { SecTrustSetAnchorCertificates(trust, it?.reinterpret()) }
        // Keep trusting the system roots as well, like the Android client.
        SecTrustSetAnchorCertificatesOnly(trust, false)
        return if (SecTrustEvaluateWithError(trust, null)) NSURLCredential.credentialForTrust(trust) else null
    }

    /** This device's certificate; null without an identity. */
    fun clientCertificate(): NSURLCredential? {
        val identity = identity ?: return null
        return withCFBridge(identity) { identityRef ->
            NSURLCredential.credentialWithIdentity(
                identity = identityRef!!.reinterpret(),
                certificates = null,
                persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceForSession,
            )
        }
    }
}
