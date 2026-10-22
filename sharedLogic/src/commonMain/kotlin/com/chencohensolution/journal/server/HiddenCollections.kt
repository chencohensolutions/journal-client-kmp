package com.chencohensolution.journal.server

import com.chencohensolution.journal.graphql.fragment.CollectionFields
import com.chencohensolution.journal.graphql.fragment.HiddenCollectionsFields
import com.chencohensolution.journal.graphql.type.HiddenCollectionsInput

/**
 * What the user hides on every device (Settings → Private media → Hidden collections). The server does the hiding:
 * hidden files come in the change feed as deleted, so the library drops them like deleted ones, and they aren't counted
 * or found by search. This is the only place the app sees them, as counts. See journal-server-go docs/media.md.
 */
data class HiddenCollections(
    /** Every file WhatsApp saved. */
    val whatsapp: Collection,
    /** Screenshots whose file name says no app; its [Collection.name] is empty. */
    val screenshotsWithoutApp: Collection,
    /** The screenshots of each app, most files first; a hidden app with no files left is listed too. */
    val screenshotApps: List<Collection>,
) {
    /** One group of files: how many it has, hidden or not, and whether it is hidden. */
    data class Collection(val name: String, val files: Int, val hidden: Boolean)

    /**
     * This choice with [collection] (one of this choice's own, compared by identity: a screenshot app may be called
     * WhatsApp) hidden or shown.
     */
    fun with(collection: Collection, hidden: Boolean): HiddenCollections = when {
        collection === whatsapp -> copy(whatsapp = whatsapp.copy(hidden = hidden))
        collection === screenshotsWithoutApp -> copy(screenshotsWithoutApp = screenshotsWithoutApp.copy(hidden = hidden))
        else -> copy(screenshotApps = screenshotApps.map { if (it === collection) it.copy(hidden = hidden) else it })
    }

    internal fun toInput() = HiddenCollectionsInput(
        whatsapp = whatsapp.hidden,
        screenshotsWithoutApp = screenshotsWithoutApp.hidden,
        screenshotApps = screenshotApps.filter { it.hidden }.map { it.name },
    )
}

internal fun HiddenCollectionsFields.toHiddenCollections() = HiddenCollections(
    whatsapp = whatsapp.collectionFields.toCollection(),
    screenshotsWithoutApp = screenshotsWithoutApp.collectionFields.toCollection(),
    screenshotApps = screenshotApps.map { it.collectionFields.toCollection() },
)

private fun CollectionFields.toCollection() = HiddenCollections.Collection(name, files, hidden)
