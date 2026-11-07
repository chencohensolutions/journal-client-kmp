package com.chencohensolution.journal.swap

import com.chencohensolution.journal.discovery.ServerId
import io.ktor.http.Url

/**
 * What a swap invite's QR code holds (journal-server-go swap.go `swapLink`):
 * `journal://swap?code=…&id=<server id>&key=<DHT key>&address=<https://…>…&from=<user>`. The phone only reads it to
 * check it and to show who it is from; the server joins with the [link] as it was.
 */
data class SwapLink(
    /** The link itself, trimmed: what joinSwap gets. */
    val link: String,
    val code: String,
    /** The host's server id as written in the link ("MFZWI3D-…"). */
    val serverId: String,
    /** The DHT key, or null. */
    val key: String?,
    val addresses: List<String>,
    /** The friend's user on their server, or null. */
    val from: String?,
)

/**
 * The swap link in [text]: the link alone, or a message that holds one (as a friend shares it); null when there is
 * none or it can't be one the server takes (no code, a bad server id, nowhere to find the server).
 */
fun parseSwapLink(text: String): SwapLink? {
    val raw = SWAP_LINK.find(text)?.value ?: return null
    val url = try {
        Url(raw)
    } catch (e: Exception) {
        return null
    }
    if (url.protocol.name != "journal" || url.host != "swap") return null
    val code = url.parameters["code"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val id = url.parameters["id"]?.trim()?.takeIf { ServerId.parse(it) != null } ?: return null
    val key = url.parameters["key"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (key != null && (key.length != 64 || key.any { it !in "0123456789abcdef" })) return null
    val addresses = url.parameters.getAll("address").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (addresses.isEmpty() && key == null) return null
    return SwapLink(raw, code, id, key, addresses, url.parameters["from"]?.trim()?.takeIf { it.isNotEmpty() })
}

private val SWAP_LINK = Regex("journal://swap\\?[^\\s\"'<>]+")
