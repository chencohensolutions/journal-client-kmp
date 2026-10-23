package com.chencohensolution.journal.server

import com.chencohensolution.journal.graphql.fragment.CollectionFields
import com.chencohensolution.journal.graphql.fragment.HiddenCollectionsFields
import com.chencohensolution.journal.graphql.type.HiddenCollectionsInput
import com.chencohensolution.journal.graphql.type.NudityHidingInput
import com.apollographql.apollo.api.Optional
import kotlin.math.roundToInt

/**
 * What the user hides on every device (Settings → Private media → Hidden collections): collections, and what the
 * server's nudity scan scored high. The server does the hiding: hidden files come in the change feed as deleted and
 * hidden, so the library drops them like deleted ones and leaves out this phone's own copies too
 * ([ServerLibrary.hidden]), and they aren't counted or found by search. This is the only place the app sees them, as
 * counts. See journal-server-go docs/media.md.
 */
data class HiddenCollections(
    /** Every file WhatsApp saved. */
    val whatsapp: Collection,
    /** Screenshots whose file name says no app; its [Collection.name] is empty. */
    val screenshotsWithoutApp: Collection,
    /** The screenshots of each app, most files first; a hidden app with no files left is listed too. */
    val screenshotApps: List<Collection>,
    val nudity: Nudity,
) {
    /** One group of files: how many it has, hidden or not, and whether it is hidden. */
    data class Collection(val name: String, val files: Int, val hidden: Boolean)

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
     * WhatsApp) hidden or shown.
     */
    fun with(collection: Collection, hidden: Boolean): HiddenCollections = when {
        collection === whatsapp -> copy(whatsapp = whatsapp.copy(hidden = hidden))
        collection === screenshotsWithoutApp -> copy(screenshotsWithoutApp = screenshotsWithoutApp.copy(hidden = hidden))
        else -> copy(screenshotApps = screenshotApps.map { if (it === collection) it.copy(hidden = hidden) else it })
    }

    /** This choice with nudity hidden or shown, from [fromPercent]. */
    fun withNudity(hidden: Boolean, fromPercent: Int = nudity.fromPercent): HiddenCollections =
        copy(nudity = nudity.copy(hidden = hidden, fromPercent = fromPercent))

    internal fun toInput() = HiddenCollectionsInput(
        whatsapp = whatsapp.hidden,
        screenshotsWithoutApp = screenshotsWithoutApp.hidden,
        screenshotApps = screenshotApps.filter { it.hidden }.map { it.name },
        nudity = Optional.present(NudityHidingInput(hidden = nudity.hidden, from = nudity.fromPercent / 100.0)),
    )
}

internal fun HiddenCollectionsFields.toHiddenCollections() = HiddenCollections(
    whatsapp = whatsapp.collectionFields.toCollection(),
    screenshotsWithoutApp = screenshotsWithoutApp.collectionFields.toCollection(),
    screenshotApps = screenshotApps.map { it.collectionFields.toCollection() },
    nudity = HiddenCollections.Nudity(nudity.hidden, (nudity.from * 100).roundToInt(), nudity.files, nudity.notScanned),
)

private fun CollectionFields.toCollection() = HiddenCollections.Collection(name, files, hidden)
