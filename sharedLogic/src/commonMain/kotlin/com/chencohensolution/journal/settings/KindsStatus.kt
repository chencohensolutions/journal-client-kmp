package com.chencohensolution.journal.settings

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.library.SourceTotal
import com.chencohensolution.journal.server.HiddenCollections

/**
 * Settings › Show in the app, a status page (option c3 of the UI lab, Oct 27, 2026): every kind of file the user has,
 * each with its files and how many of this phone's aren't on the server yet. Nothing here changes: hiding on this phone
 * is the Moments filter bar's, hiding on every device Private settings'. A kind Private settings hides on every device
 * isn't named; [hiddenEverywhere] only counts them.
 */
data class KindsStatus(
    /** Photo and video kinds, the most files first, Other last; what the server's import placed is in its kind. */
    val kinds: List<Kind>,
    /** Calls, Voice recordings, Voice messages, those with files and not hidden on every device. */
    val sounds: List<SoundKind>,
    /**
     * How many kinds (screenshot apps among them) Private settings hides on every device; null until it was opened in
     * this run of the app (the page then leaves the line out).
     */
    val hiddenEverywhere: Int?,
) {
    /** Every listed kind's files, for the line at the top. */
    val files: Int get() = kinds.sumOf { it.files } + sounds.sumOf { it.files }

    /** Every listed kind's files on this phone that aren't on the server yet. */
    val notBackedUp: Int get() = kinds.sumOf { it.notBackedUp } + sounds.sumOf { it.notBackedUp }

    data class Kind(
        val source: MediaSource,
        /** Its files, on the phone and the server alike. */
        val files: Int,
        /** Its files on this phone that backup takes and that aren't on the server yet; 0 when all are. */
        val notBackedUp: Int,
    )

    data class SoundKind(val kind: AudioKind, val files: Int, val notBackedUp: Int)

    companion object {
        /** The sound kinds in the order the page lists them. */
        val SOUND_ORDER: List<AudioKind> = listOf(AudioKind.CALL, AudioKind.RECORDING, AudioKind.VOICE_MESSAGE)

        /**
         * The page from the library's counts per source ([LibraryState.sourceTotals]: imported ones folded into their
         * kind), the sound recordings it shows ([sounds]) with those of the backed-up sound folders not on the server yet
         * ([soundsNotBackedUp], BackupState.soundsNotBackedUp), and what Private settings hides ([hidden],
         * ServerLibrary.hiddenEverywhere; null when not known): a kind it hides whole is left out and counted instead.
         */
        fun of(
            sources: Map<MediaSource, SourceTotal>,
            sounds: List<SoundRecording>,
            soundsNotBackedUp: Map<AudioKind, Int>,
            hidden: HiddenCollections?,
        ): KindsStatus {
            val byKind = HashMap<MediaSource, SourceTotal>()
            sources.forEach { (source, total) ->
                val kind = source.kind
                if (kind.isSound) return@forEach
                byKind[kind] = byKind[kind]?.plus(total) ?: total
            }
            val kinds = byKind
                .filter { (kind, total) -> total.items > 0 && hidden?.hiddenOf(kind)?.whole != true }
                .map { (kind, total) -> Kind(kind, total.items, total.notBackedUp) }
                .sortedWith(compareBy<Kind> { it.source == MediaSource.OTHER }.thenByDescending { it.files }.thenBy { it.source })
            val onServer = sounds.groupingBy { it.kind }.eachCount()
            val soundKinds = SOUND_ORDER
                .filter { hidden?.sound(it)?.hidden != true }
                .map { SoundKind(it, (onServer[it] ?: 0) + (soundsNotBackedUp[it] ?: 0), soundsNotBackedUp[it] ?: 0) }
                .filter { it.files > 0 }
            return KindsStatus(kinds, soundKinds, hidden?.hiddenKinds())
        }
    }
}

/** The server's collection of sound recordings of [kind]; null from a server without them. */
internal fun HiddenCollections.sound(kind: AudioKind): HiddenCollections.Collection? = when (kind) {
    AudioKind.CALL -> calls
    AudioKind.RECORDING -> recordings
    AudioKind.VOICE_MESSAGE -> voiceMessages
}

/**
 * How many kinds this hides on every device that have files: apps and folders, screenshot apps (and screenshots without
 * an app), Sent to you and the sound kinds. Nudity isn't a kind.
 */
internal fun HiddenCollections.hiddenKinds(): Int =
    (apps + screenshotApps + listOfNotNull(screenshotsWithoutApp, sentToYou, recordings, calls, voiceMessages))
        .count { it.hidden && it.files > 0 }
