package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.storage.FreeUpState

/**
 * One pill of the list Settings › Backup and Free up space share: a photo source, or a folder of calls or recordings the
 * user picked. One list for both, the ones that are on first.
 */
sealed interface BackupPill {
    val key: String

    /** Files it has (Backup: on the phone; Free up space: that would go). */
    val count: Int
    val on: Boolean

    data class Source(val source: MediaSource, override val count: Int, override val on: Boolean, val bytes: Long? = null) : BackupPill {
        override val key: String get() = source.id
    }

    /** [lost]: the app can no longer read the folder; tapping the pill picks it again. */
    data class Folder(
        val folder: SoundFolder,
        override val count: Int,
        override val on: Boolean,
        val lost: Boolean = false,
        val bytes: Long? = null,
    ) : BackupPill {
        override val key: String get() = folder.treeUri
    }
}

/** On first, then the rest, each part the most files first (ties keep their order). */
internal fun List<BackupPill>.ordered(): List<BackupPill> =
    sortedWith(compareByDescending<BackupPill> { it.on }.thenByDescending { it.count })

/** Settings › Backup's pills: the sources it offers ([counts], photos per source) and the sound folders. */
fun BackupState.pills(counts: Map<MediaSource, Int>): List<BackupPill> = (
    settings.choices(counts).map { BackupPill.Source(it, counts[it] ?: 0, it in settings.sources) } +
        settings.soundFolders.map { BackupPill.Folder(it, soundCounts[it.treeUri] ?: 0, it.enabled, lost = it.treeUri in lostFolders) }
    ).ordered()

/** Free up space's pills: what of each source and sound folder would go, and whether it may. */
fun FreeUpState.pills(): List<BackupPill> = (
    choices.map { source ->
        val count = plan?.ofSource(source)
        BackupPill.Source(source, count?.files ?: 0, allows(source), count?.bytes)
    } + folders.map { folder ->
        val count = plan?.ofFolder(folder.treeUri)
        BackupPill.Folder(folder, count?.files ?: 0, allowsFolder(folder.treeUri), bytes = count?.bytes)
    }
    ).ordered()
