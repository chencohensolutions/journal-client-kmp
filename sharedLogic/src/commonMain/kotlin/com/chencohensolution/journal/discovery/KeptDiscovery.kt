package com.chencohensolution.journal.discovery

import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import kotlinx.serialization.Serializable

/**
 * Who the server is and where, as the app keeps it from `serverInfo.discovery` (docs/plans/discovery.md, "Phones"):
 * its key's [fingerprint] (pinned by the mTLS clients), its [id] as people read it, its DHT [key] (null when it doesn't
 * publish there), and the [addresses] to try when the one in use stops answering, the one that last worked first.
 * [v] is the format's version.
 */
@Serializable
data class KeptDiscovery(
    val v: Int = 1,
    val fingerprint: String,
    val id: String,
    val key: String? = null,
    val addresses: List<String> = emptyList(),
    /** The address the phone was enrolled with: it stays in [addresses]. */
    val enrolled: String? = null,
) {
    companion object {
        const val MAX_ADDRESSES = 16

        /**
         * What to keep after the server answered at [workingUrl] with [fingerprint], [id], [key] and [addresses],
         * given what was kept before ([old]): the address that worked first, then the server's own list, then the
         * ones kept before; at most [MAX_ADDRESSES], the enrolled address always among them.
         */
        fun merge(
            old: KeptDiscovery?,
            workingUrl: String,
            fingerprint: String,
            id: String,
            key: String?,
            addresses: List<String>,
        ): KeptDiscovery {
            val working = originOf(workingUrl)
            // A new key is a new server: what was kept of the old one doesn't apply.
            val previous = old?.takeIf { it.fingerprint.equals(fingerprint, ignoreCase = true) }
            val enrolled = previous?.enrolled ?: old?.enrolled ?: working
            val all = (listOfNotNull(working) + addresses.mapNotNull(::originOf) + previous?.addresses.orEmpty()).distinct()
            return KeptDiscovery(
                fingerprint = fingerprint.lowercase(),
                id = id,
                key = key?.lowercase()?.takeIf { it.length == 64 },
                addresses = capped(all, enrolled),
                enrolled = enrolled,
            )
        }

        /** [all] without repeats, at most [MAX_ADDRESSES], keeping [enrolled] when it is among them. */
        private fun capped(all: List<String>, enrolled: String?): List<String> {
            val distinct = all.distinct()
            val first = distinct.take(MAX_ADDRESSES)
            return if (enrolled == null || enrolled in first || enrolled !in distinct) first else first.dropLast(1) + enrolled
        }

        /** `https://host:port` of [url] (the GraphQL endpoint or an address); null when it isn't an https URL. */
        fun originOf(url: String): String? = runCatching {
            val parsed = URLBuilder(url.trim()).build()
            if (parsed.protocol != URLProtocol.HTTPS || parsed.host.isEmpty()) return null
            val host = if (':' in parsed.host && !parsed.host.startsWith("[")) "[${parsed.host}]" else parsed.host
            "https://$host:${parsed.port}"
        }.getOrNull()
    }

    /** [address] moved to the front: it is the one that works now. */
    fun working(address: String): KeptDiscovery {
        val origin = originOf(address) ?: return this
        return copy(addresses = capped(listOf(origin) + addresses, enrolled))
    }

    /** [found] (the server's DHT record, newer than what was kept) before the kept addresses. */
    fun withFound(found: List<String>): KeptDiscovery =
        copy(addresses = capped(found.mapNotNull(::originOf) + addresses, enrolled))
}
