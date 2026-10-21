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
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.credentialForTrust
import platform.Foundation.credentialWithIdentity
import platform.Foundation.serverTrust
import platform.Security.SecTrustEvaluateWithError
import platform.Security.SecTrustSetAnchorCertificates
import platform.Security.SecTrustSetAnchorCertificatesOnly

internal typealias ChallengeCompletion = (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit

/**
 * mTLS over URLSession, using an identity in the app Keychain: imported by [KeychainIdentities] or
 * created by enrollment (IosDeviceKeyStore).
 */
internal class IosMtlsHttpClientFactory : MtlsHttpClientFactory {

    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient {
        val challenges = MtlsChallenges(identityAlias, trustedCas)
        return HttpClient(Darwin) {
            engine {
                handleChallenge { _, _, challenge, completion -> challenges.answer(challenge, completion) }
            }
            // No limits of its own: a request that needs longer than usual (verifying a large video) sets them.
            install(HttpTimeout)
        }
    }
}

/**
 * The answers to a URLSession's TLS challenges as [identity] (a SecIdentity; none: enrollment), trusting [anchors]
 * besides the system roots. Shared by the Ktor client, the video player's URLSession (IosServerStream) and the
 * background upload session ([answerChallenge]).
 */
internal class MtlsChallenges(private val identity: Any?, private val anchors: List<Any>) {
    /** As the Keychain identity [identityAlias], trusting [trustedCas]. */
    constructor(identityAlias: String?, trustedCas: List<ByteArray>) : this(
        identityAlias?.let { KeychainIdentities.find(it) ?: error("Identity \"$it\" is not in the Keychain; import it again") },
        trustedCas.map(::secCertificateObject),
    )

    fun answer(challenge: NSURLAuthenticationChallenge, completion: ChallengeCompletion) {
        when (challenge.protectionSpace.authenticationMethod) {
            NSURLAuthenticationMethodServerTrust -> serverTrust(challenge)
                ?.let { completion(NSURLSessionAuthChallengeUseCredential, it) }
                ?: completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            // No client certificate: the server accepts that for enrollment only.
            NSURLAuthenticationMethodClientCertificate -> clientCertificate()
                ?.let { completion(NSURLSessionAuthChallengeUseCredential, it) }
                ?: completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
            else -> completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
        }
    }

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

/** Answers [challenge] as [identity], trusting [anchors]: for the background upload session. */
internal fun answerChallenge(challenge: NSURLAuthenticationChallenge, identity: Any?, anchors: List<Any>, completion: ChallengeCompletion) =
    MtlsChallenges(identity, anchors).answer(challenge, completion)
