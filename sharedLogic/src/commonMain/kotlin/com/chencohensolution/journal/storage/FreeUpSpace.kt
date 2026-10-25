package com.chencohensolution.journal.storage

import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.backup.SoundIds
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.backup.sha256
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Which originals "Free up space" takes off this phone (Settings → Free up space). */
sealed interface FreeUpRule {
    /** Every photo and video this phone backed up. */
    data object Everything : FreeUpRule

    /** Photos and videos taken more than [days] days ago. */
    data class OlderThan(val days: Int) : FreeUpRule

    /** Keeps the newest photos and videos (by date taken) up to [gigabytes] GB; the older ones go. */
    data class KeepNewest(val gigabytes: Int) : FreeUpRule

    companion object {
        val DEFAULT: FreeUpRule = Everything
        val DAYS_OPTIONS = listOf(30, 90, 180, 365, 730)
        val GIGABYTES_OPTIONS = listOf(1, 2, 5, 10, 20, 50)
    }
}

/**
 * Files that leave the phone together or not at all: one photo, or a pair (a RAW and its JPEG, a Live Photo's picture
 * and video), recognised by the same file name apart from the extension, taken within [PAIR_MILLIS] of each other.
 */
data class FreeUpGroup(val items: List<MediaItem>) {
    val bytes: Long get() = items.sumOf { it.sizeBytes ?: 0 }
    val takenAtMillis: Long get() = items.maxOf { it.takenAtMillis }
}

/** What a rule would take off the phone now. */
data class FreeUpPlan(val groups: List<FreeUpGroup>) {
    val photos: Int get() = groups.sumOf { g -> g.items.count { it.type == MediaType.IMAGE } }
    val videos: Int get() = groups.sumOf { g -> g.items.count { it.type == MediaType.VIDEO } }

    /** Files of sound folders: deleted for good, since they have no trash (the app asks first). */
    val sounds: Int get() = groups.sumOf { g -> g.items.count { SoundIds.isSound(it.id) } }
    val bytes: Long get() = groups.sumOf { it.bytes }

    /** How many photos and videos of [source] would go, and their bytes. */
    fun ofSource(source: MediaSource): FreeUpCount =
        count { !SoundIds.isSound(it.id) && it.source == source }

    /** How many files of the sound folder [treeUri] would go, and their bytes. */
    fun ofFolder(treeUri: String): FreeUpCount = count { SoundIds.treeOf(it.id) == treeUri }

    private fun count(predicate: (MediaItem) -> Boolean): FreeUpCount {
        val items = groups.flatMap { it.items }.filter(predicate)
        return FreeUpCount(items.size, items.sumOf { it.sizeBytes ?: 0 })
    }

    /** When the oldest and the newest of them were taken; null when there are none. */
    val takenRange: Pair<Long, Long>? get() =
        groups.flatMap { it.items }.map { it.takenAtMillis }.takeIf { it.isNotEmpty() }?.let { it.min() to it.max() }
}

/** Files of one pill (a source or a sound folder) that would go. */
data class FreeUpCount(val files: Int, val bytes: Long)

private const val PAIR_MILLIS = 2_000L
private const val DAY_MILLIS = 86_400_000L

/** [items] in groups that leave together (see [FreeUpGroup]), newest first. */
internal fun freeUpGroups(items: List<MediaItem>): List<FreeUpGroup> {
    val groups = mutableListOf<MutableList<MediaItem>>()
    val open = mutableMapOf<String, MutableList<MutableList<MediaItem>>>()
    for (item in items.sortedBy { it.takenAtMillis }) {
        // A sound file is never half of a pair.
        val stem = item.fileName?.substringBeforeLast('.')?.lowercase()?.takeUnless { SoundIds.isSound(item.id) }
        val partner = stem?.let { open[it] }?.lastOrNull { g -> item.takenAtMillis - g.maxOf { it.takenAtMillis } <= PAIR_MILLIS }
        if (partner != null) {
            partner += item
        } else {
            val group = mutableListOf(item)
            groups += group
            if (stem != null) open.getOrPut(stem) { mutableListOf() } += group
        }
    }
    return groups.map { FreeUpGroup(it) }.sortedByDescending { it.takenAtMillis }
}

/**
 * What [rule] takes off the phone: of the photos in the library not from the [kept] sources and the files of the sound
 * folders not [keptFolders] (tree URIs), the groups whose every file this device uploaded as it is now ([entries], the
 * backup ledger). The newest that "keep newest" keeps count all files on the phone, backed up or not, of any source.
 */
internal fun freeUpPlan(
    items: List<MediaItem>,
    entries: Map<String, LedgerEntry>,
    rule: FreeUpRule,
    nowMillis: Long,
    kept: Set<MediaSource> = emptySet(),
    keptFolders: Set<String> = emptySet(),
): FreeUpPlan {
    fun backedUp(item: MediaItem) = entries[item.id]?.let { it.uploaded && it.matches(item) } == true
    fun allowed(item: MediaItem) = SoundIds.treeOf(item.id)?.let { it !in keptFolders } ?: (item.source !in kept)
    val groups = freeUpGroups(items.filter { it.isOnDevice })
    val chosen = when (rule) {
        FreeUpRule.Everything -> groups
        is FreeUpRule.OlderThan -> {
            val before = nowMillis - rule.days * DAY_MILLIS
            groups.filter { it.takenAtMillis < before }
        }
        is FreeUpRule.KeepNewest -> {
            val keep = rule.gigabytes.toLong() shl 30
            var kept = 0L
            groups.dropWhile { group -> (kept + group.bytes <= keep).also { if (it) kept += group.bytes } }
        }
    }
    return FreeUpPlan(chosen.filter { group -> group.items.all { backedUp(it) && allowed(it) } })
}

/** What the server found when it read its copy again (journal-server-go `POST /media/{sha256}/verify`). */
enum class ServerVerdict { OK, MISSING, MISMATCH }

/** The server's check of one original; throws when the server can't be reached. */
internal fun interface ServerCheck {
    suspend fun verify(sha256: String): ServerVerdict
}

/** Why a group stays on the phone. */
enum class KeptReason {
    /** The bytes on the phone no longer have the sha256 backup read: read and uploaded again by backup. */
    CHANGED_ON_PHONE,

    /** The server doesn't have it (another server or data folder since): uploaded again by backup. */
    MISSING_ON_SERVER,

    /** The server's copy is damaged; it logs it. */
    DAMAGED_ON_SERVER,
}

/**
 * Checks a group just before it leaves the phone: for each file, the server reads and hashes its copy again while this
 * phone hashes its own, and both must have the sha256 backup uploaded. Null when every file passed.
 */
internal class FreeUpChecker(
    private val files: MediaFiles,
    private val ledger: UploadLedger,
    private val server: ServerCheck,
) {
    suspend fun check(group: FreeUpGroup): KeptReason? {
        for (item in group.items) {
            val entry = ledger.entry(item.id)?.takeIf { it.uploaded && it.matches(item) } ?: return KeptReason.CHANGED_ON_PHONE
            val (onServer, here) = coroutineScope {
                val onServer = async { server.verify(entry.sha256) }
                val here = files.sha256(item)
                onServer.await() to here
            }
            if (here != entry.sha256) {
                ledger.forget(item.id)
                return KeptReason.CHANGED_ON_PHONE
            }
            when (onServer) {
                ServerVerdict.OK -> Unit
                ServerVerdict.MISSING -> {
                    ledger.record(entry.copy(uploaded = false))
                    return KeptReason.MISSING_ON_SERVER
                }
                ServerVerdict.MISMATCH -> return KeptReason.DAMAGED_ON_SERVER
            }
        }
        return null
    }
}
