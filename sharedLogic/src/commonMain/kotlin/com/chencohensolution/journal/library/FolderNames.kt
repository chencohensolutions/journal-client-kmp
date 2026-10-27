@file:OptIn(ExperimentalAtomicApi::class)

package com.chencohensolution.journal.library

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The names folder sources are shown by. A folder source's id is "folder:" and its folder's name in lower case
 * ("folder:whatsapp", "folder:calls"), so folders that differ only by case are one source, as the server has them
 * since Oct 27, 2026; the name shown is, the first that is known:
 * 1. an app the name table knows, as it spells it (WhatsApp, Telegram, Signal, Facebook, Messenger, Snapchat);
 * 2. the server's name for it, the spelling most of its files have ([setServer], from `folderNames` once per sync,
 *    kept in the library database);
 * 3. the spelling the phone saw ([saw]: its own folders as MediaStore names them, and ids kept with capitals, such as
 *    the phone's own rows in the database and settings saved before Oct 27, 2026);
 * 4. the id's name with its first letter made a capital ("petcube" is "Petcube").
 * Safe to call from any thread: a lookup reads a map that is replaced whole.
 */
object FolderNames {
    private val server = AtomicReference<Map<String, String>>(emptyMap())
    private val seen = AtomicReference<Map<String, String>>(emptyMap())

    /** How the folder [folder] (any case: "petcube", "Petcube") is shown: "Petcube". */
    fun nameOf(folder: String): String {
        val key = folder.trim().lowercase()
        return SourceNames.appSpelling(key) ?: server.load()[key] ?: seen.load()[key] ?: capitalized(key)
    }

    /**
     * The spelling this phone saw of [folder] (an app the name table knows as it spells it), for what the phone keeps of
     * its own files (`device_item`), so its own spelling outlives a restart, and tells the server; the id's name when it
     * saw none.
     */
    internal fun spelling(folder: String): String =
        folder.lowercase().let { key -> SourceNames.appSpelling(key) ?: seen.load()[key] ?: key }

    /**
     * A spelling of a folder the phone met ("Petcube", "Voice Recorder"). The first is kept; one all in lower case
     * says nothing the id doesn't, and isn't kept.
     */
    internal fun saw(spelling: String) {
        val key = spelling.lowercase()
        if (key == spelling) return
        while (true) {
            val current = seen.load()
            if (key in current) return
            if (seen.compareAndSet(current, current + (key to spelling))) return
        }
    }

    /** The server's names, by id ("folder:calls" to "Calls"); ids that aren't folders are left out. */
    internal fun setServer(names: Map<String, String>) {
        server.store(
            names.mapNotNull { (id, name) ->
                val folder = MediaSource.parse(id)?.kind?.folder ?: return@mapNotNull null
                name.trim().takeIf { it.isNotEmpty() }?.let { folder to it }
            }.toMap(),
        )
    }

    /** Forgets every name: for the tests. */
    internal fun clear() {
        server.store(emptyMap())
        seen.store(emptyMap())
    }

    private fun capitalized(name: String): String = name.replaceFirstChar { if (it.isLowerCase()) it.titlecaseChar() else it }
}
