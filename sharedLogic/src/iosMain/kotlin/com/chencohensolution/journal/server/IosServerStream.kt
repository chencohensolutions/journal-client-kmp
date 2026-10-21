package com.chencohensolution.journal.server

import com.chencohensolution.journal.network.MtlsChallenges
import com.chencohensolution.journal.settings.JournalSettings
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential

/**
 * Originals that Swift streams with its own URLSession (AVPlayer reads a server-only video through a resource loader,
 * not Ktor): where to fetch one from (`GET /media/{sha256}`, with Range), and the answers to the session's mTLS
 * challenges, given exactly as the shared client gives them. Call from one queue (the session's delegate queue).
 */
class IosServerStream internal constructor(
    private val settings: JournalSettings,
    private val previews: ServerPreviews,
    private val trustedCas: List<ByteArray>,
) {
    private var challenges: Pair<String, MtlsChallenges>? = null

    /** Where [sha256]'s original streams from; null when this device isn't registered. */
    fun url(sha256: String): String? = settings.identityAlias?.let { previews.mediaUrl(sha256) }

    /** The server's certificate checked against the journal's CA; null: cancel the connection. */
    fun serverTrust(challenge: NSURLAuthenticationChallenge): NSURLCredential? = challenges()?.serverTrust(challenge)

    /** This device's certificate from the Keychain; null when it has none. */
    fun clientCertificate(): NSURLCredential? = challenges()?.clientCertificate()

    private fun challenges(): MtlsChallenges? {
        val alias = settings.identityAlias ?: return null
        challenges?.takeIf { it.first == alias }?.let { return it.second }
        return runCatching { MtlsChallenges(alias, trustedCas) }.getOrNull()?.also { challenges = alias to it }
    }
}
