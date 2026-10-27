package com.chencohensolution.journal.server

/**
 * Private settings' list (option c2 of the UI lab, Oct 27, 2026): every kind of file the server can hide on every
 * device, once, with a Show switch each (on = shown), the hidden ones included: the apps and folders, Screenshots with
 * a chip per app it was taken in, the sound kinds, Sent to you. Nudity stays apart ([HiddenCollections.nudity]). Each
 * kind carries the server's [HiddenCollections.Collection], so a switch goes back as
 * `HiddenCollectionsController.set(kind.collection, hidden = !shown)`, which the server reads as `apps`,
 * `screenshotApps`, `screenshotsWithoutApp`, `audio` or `sentToYou`.
 */
data class PrivateKinds(
    /** Every kind but nudity, the most files first; [Type.SCREENSHOTS] among them once, its apps in [screenshotApps]. */
    val kinds: List<Kind>,
    /**
     * The apps screenshots were taken in, the most first, screenshots without an app among them (its [Kind.name] is
     * empty); each a chip under Screenshots.
     */
    val screenshotApps: List<Kind>,
) {
    enum class Type { FOLDER, SCREENSHOTS, SENT_TO_YOU, CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    /**
     * A kind: [name] is a folder's or a screenshot app's (as the server names it; empty for screenshots without an app
     * and for the others). [collection] is null only for the Screenshots line, which has no switch of its own.
     */
    data class Kind(val type: Type, val name: String, val files: Int, val shown: Boolean, val collection: HiddenCollections.Collection?)

    companion object {
        /**
         * The list from the server's [collections]. Apps and screenshot apps are all listed (the server lists a hidden
         * one with no files left, so it can be shown again); screenshots without an app, Sent to you and the sound kinds
         * only with files, or while hidden for the first two (sound kinds are hidden until shown, so an empty one would
         * only be noise).
         */
        fun of(collections: HiddenCollections): PrivateKinds {
            fun kind(type: Type, collection: HiddenCollections.Collection, name: String = "") =
                Kind(type, name, collection.files, !collection.hidden, collection)
            val withoutApp = collections.screenshotsWithoutApp.takeIf { it.files > 0 || it.hidden }
            val shots = (collections.screenshotApps.map { kind(Type.SCREENSHOTS, it, it.name) } +
                listOfNotNull(withoutApp?.let { kind(Type.SCREENSHOTS, it) }))
                .sortedByDescending { it.files }
            val kinds = buildList {
                collections.apps.forEach { add(kind(Type.FOLDER, it, it.name)) }
                if (shots.isNotEmpty()) add(Kind(Type.SCREENSHOTS, "", shots.sumOf { it.files }, shots.any { it.shown }, null))
                collections.sentToYou?.takeIf { it.files > 0 || it.hidden }?.let { add(kind(Type.SENT_TO_YOU, it)) }
                collections.calls?.takeIf { it.files > 0 }?.let { add(kind(Type.CALLS, it)) }
                collections.recordings?.takeIf { it.files > 0 }?.let { add(kind(Type.VOICE_RECORDINGS, it)) }
                collections.voiceMessages?.takeIf { it.files > 0 }?.let { add(kind(Type.VOICE_MESSAGES, it)) }
            }.sortedByDescending { it.files }
            return PrivateKinds(kinds, shots)
        }
    }
}
