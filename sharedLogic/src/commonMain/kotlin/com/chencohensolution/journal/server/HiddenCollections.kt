package com.chencohensolution.journal.server

import com.chencohensolution.journal.graphql.fragment.CollectionFields
import com.chencohensolution.journal.graphql.fragment.HiddenCollectionsFields
import com.chencohensolution.journal.graphql.type.AudioHidingInput
import com.chencohensolution.journal.graphql.type.HiddenCollectionsInput
import com.chencohensolution.journal.graphql.type.NudityHidingInput
import com.apollographql.apollo.api.Optional
import com.chencohensolution.journal.library.MediaSource
import kotlin.math.roundToInt

/**
 * What the user hides on every device (Settings → Private settings): apps and folders, screenshots by app, and what the
 * server's nudity scan scored high. The server does the hiding: hidden files come in the change feed as deleted and
 * hidden, so the library drops them like deleted ones and leaves out this phone's own copies too
 * ([ServerLibrary.hidden]), and they aren't counted or found by search. This is the only place the app sees them, as
 * counts. See journal-server-go docs/media.md.
 */
data class HiddenCollections(
    /**
     * The files of each app and folder they came from (WhatsApp, Telegram, Petcube: every source "folder:<name>"), named
     * by the folder, most files first; a hidden one with no files left is listed too. Screenshots aren't among them.
     */
    val apps: List<Collection>,
    /** Screenshots whose file name says no app; its [Collection.name] is empty. */
    val screenshotsWithoutApp: Collection,
    /** The screenshots of each app on screen in them, most files first; a hidden app with no files left is listed too. */
    val screenshotApps: List<Collection>,
    val nudity: Nudity,
    /**
     * The server's sound recordings: recordings, phone calls and WhatsApp voice messages, each hidden until the user
     * shows it. Null from a server without sound recordings (and in callers that don't know them).
     */
    val recordings: Collection? = null,
    val calls: Collection? = null,
    val voiceMessages: Collection? = null,
) {
    /**
     * One group of files: how many it has, hidden or not, and whether it is hidden. [imported] of [files] the server's
     * import placed; the rest came from phones (0 from a server that doesn't say).
     */
    data class Collection(val name: String, val files: Int, val hidden: Boolean, val imported: Int = 0)

    /** WhatsApp's entry of [apps], or an empty one when it has no files and isn't hidden. */
    val whatsapp: Collection
        get() = app(WHATSAPP) ?: Collection(WHATSAPP, 0, false)

    /** The entry of [apps] named [name], ignoring case (the server compares them so); null when it isn't listed. */
    fun app(name: String): Collection? = apps.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** What this hides of one source on every device: all of it ([whole]), or [files] of it. */
    data class OfSource(val whole: Boolean, val files: Int)

    /**
     * What this hides of [source] on every device, for Show in the app: all of an app or folder while it is hidden
     * (WhatsApp, Telegram), and of screenshots those of the hidden apps and, while hidden, those without an app;
     * counted of what phones uploaded or of what was imported, as [source] is. Nothing of the other sources (nudity
     * isn't by source).
     */
    fun hiddenOf(source: MediaSource): OfSource {
        val kind = source.kind
        val folder = kind.folder
        return when {
            folder != null -> OfSource(app(folder)?.hidden == true, 0)
            kind == MediaSource.SCREENSHOTS -> OfSource(
                whole = false,
                files = (screenshotApps + screenshotsWithoutApp).filter { it.hidden }
                    .sumOf { if (source.isImported) it.imported else it.files - it.imported },
            )
            else -> OfSource(false, 0)
        }
    }

    /**
     * The apps and folders hidden whole that have files of [imported] origin (what phones uploaded when false), as
     * sources: Show in the app lists them locked even when none of their files reach this phone.
     */
    fun hiddenApps(imported: Boolean): List<MediaSource> = apps
        .filter { it.hidden && (if (imported) it.imported else it.files - it.imported) > 0 }
        .map { MediaSource.folder(it.name).let { source -> if (imported) MediaSource.imported(source) else source } }

    /**
     * The files the nudity scan scored at [fromPercent] or more, hidden while [hidden] (on, from 50%, until the user
     * chooses). [files] score that much; [notScanned] aren't scanned yet, so they show until they are.
     */
    data class Nudity(val hidden: Boolean, val fromPercent: Int, val files: Int, val notScanned: Int) {
        companion object {
            /** What the screen's slider offers, in steps of 5. */
            val RANGE: IntRange = 10..95
        }
    }

    /**
     * This choice with [collection] (one of this choice's own, compared by identity: a screenshot app may be called
     * WhatsApp) hidden or shown. An app not listed ([whatsapp] without files) is added to [apps].
     */
    fun with(collection: Collection, hidden: Boolean): HiddenCollections = when {
        collection === screenshotsWithoutApp -> copy(screenshotsWithoutApp = screenshotsWithoutApp.copy(hidden = hidden))
        collection === recordings -> copy(recordings = collection.copy(hidden = hidden))
        collection === calls -> copy(calls = collection.copy(hidden = hidden))
        collection === voiceMessages -> copy(voiceMessages = collection.copy(hidden = hidden))
        apps.any { it === collection } -> copy(apps = apps.map { if (it === collection) it.copy(hidden = hidden) else it })
        screenshotApps.any { it === collection } ->
            copy(screenshotApps = screenshotApps.map { if (it === collection) it.copy(hidden = hidden) else it })
        else -> copy(apps = apps + collection.copy(hidden = hidden))
    }

    /** This choice with nudity hidden or shown, from [fromPercent]. */
    fun withNudity(hidden: Boolean, fromPercent: Int = nudity.fromPercent): HiddenCollections =
        copy(nudity = nudity.copy(hidden = hidden, fromPercent = fromPercent))

    internal fun toInput() = HiddenCollectionsInput(
        apps = Optional.present(apps.filter { it.hidden }.map { it.name }),
        // Left out: apps say whether WhatsApp is hidden ("whatsapp" is for apps from before apps).
        whatsapp = Optional.absent(),
        screenshotsWithoutApp = screenshotsWithoutApp.hidden,
        screenshotApps = screenshotApps.filter { it.hidden }.map { it.name },
        nudity = Optional.present(NudityHidingInput(hidden = nudity.hidden, from = nudity.fromPercent / 100.0)),
        // Left out when not known: the server keeps what it has.
        audio = if (recordings != null && calls != null && voiceMessages != null) {
            Optional.present(AudioHidingInput(recordings = recordings.hidden, calls = calls.hidden, voiceMessages = voiceMessages.hidden))
        } else {
            Optional.absent()
        },
    )

    companion object {
        /** WhatsApp's name among [apps], as the server spells it. */
        const val WHATSAPP = "WhatsApp"
    }
}

internal fun HiddenCollectionsFields.toHiddenCollections() = HiddenCollections(
    apps = apps.map { it.collectionFields.toCollection() },
    screenshotsWithoutApp = screenshotsWithoutApp.collectionFields.toCollection(),
    screenshotApps = screenshotApps.map { it.collectionFields.toCollection() },
    nudity = HiddenCollections.Nudity(nudity.hidden, (nudity.from * 100).roundToInt(), nudity.files, nudity.notScanned),
    recordings = recordings.collectionFields.toCollection(),
    calls = calls.collectionFields.toCollection(),
    voiceMessages = voiceMessages.collectionFields.toCollection(),
)

private fun CollectionFields.toCollection() = HiddenCollections.Collection(name, files, hidden, imported)
