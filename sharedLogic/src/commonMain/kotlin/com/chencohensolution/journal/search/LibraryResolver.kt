package com.chencohensolution.journal.search

import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.server.ServerMedia

/**
 * sha256s (the server's answers: a search, a person's photos, similar photos) → [library]'s items, in their order. A
 * device photo backup has read carries its sha256; one it hasn't is found by the server file's name and size ([server]),
 * as the library matches them. What the library doesn't show (private, hidden, not synced yet) is left out.
 */
internal fun libraryResolver(library: LibraryState, server: List<ServerMedia>): (List<String>) -> List<MediaItem> {
    val items = library.days.flatMap { it.items }
    val bySha = items.filter { it.sha256 != null }.associateBy { it.sha256!! }
    val serverFiles = server.associateBy { it.sha256 }
    val byNameAndSize by lazy { items.filter { it.sha256 == null && it.fileName != null }.associateBy { it.fileName!!.lowercase() to it.sizeBytes } }
    return { sha256s ->
        sha256s.mapNotNull { sha ->
            bySha[sha] ?: serverFiles[sha]?.let { byNameAndSize[it.fileName.lowercase() to it.size] }
        }.distinctBy { it.id }
    }
}

/** The sha256 the server knows [item] by: its own, or the server file with its name and size. */
internal fun serverSha(item: MediaItem, server: List<ServerMedia>): String? =
    item.sha256 ?: item.fileName?.lowercase()?.let { name -> server.firstOrNull { it.fileName.lowercase() == name && it.size == item.sizeBytes }?.sha256 }
