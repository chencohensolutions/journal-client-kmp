@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.network

import com.chencohensolution.journal.tls.KeychainIdentities
import com.chencohensolution.journal.tls.secCertificateObject
import com.chencohensolution.journal.tls.withCFBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
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

private typealias ChallengeCompletion = (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit

/**
 * mTLS over URLSession, using an identity in the app Keychain: imported by [KeychainIdentities] or
 * created by enrollment (IosDeviceKeyStore).
 */
internal class IosMtlsHttpClientFactory : MtlsHttpClientFactory {

    override suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient {
        val identity = identityAlias?.let {
            KeychainIdentities.find(it) ?: error("Identity \"$it\" is not in the Keychain; import it again")
        }
        val anchors: List<Any> = trustedCas.map(::secCertificateObject)

        return HttpClient(Darwin) {
            engine {
                handleChallenge { _, _, challenge, completion ->
                    when (challenge.protectionSpace.authenticationMethod) {
                        NSURLAuthenticationMethodServerTrust -> evaluateServerTrust(challenge, anchors, completion)
                        NSURLAuthenticationMethodClientCertificate -> presentIdentity(identity, completion)
                        else -> completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
                    }
                }
            }
        }
    }

    private fun evaluateServerTrust(
        challenge: NSURLAuthenticationChallenge,
        anchors: List<Any>,
        completion: ChallengeCompletion,
    ) {
        val trust = challenge.protectionSpace.serverTrust
        if (trust == null) {
            completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            return
        }
        withCFBridge(anchors) { SecTrustSetAnchorCertificates(trust, it?.reinterpret()) }
        // Keep trusting the system roots as well, like the Android client.
        SecTrustSetAnchorCertificatesOnly(trust, false)

        if (SecTrustEvaluateWithError(trust, null)) {
            completion(NSURLSessionAuthChallengeUseCredential, NSURLCredential.credentialForTrust(trust))
        } else {
            completion(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
        }
    }

    private fun presentIdentity(identity: Any?, completion: ChallengeCompletion) {
        if (identity == null) {
            // No client certificate: the server accepts that for enrollment only.
            completion(NSURLSessionAuthChallengePerformDefaultHandling, null)
            return
        }
        val credential = withCFBridge(identity) { identityRef ->
            NSURLCredential.credentialWithIdentity(
                identity = identityRef!!.reinterpret(),
                certificates = null,
                persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceForSession,
            )
        }
        completion(NSURLSessionAuthChallengeUseCredential, credential)
    }
}
