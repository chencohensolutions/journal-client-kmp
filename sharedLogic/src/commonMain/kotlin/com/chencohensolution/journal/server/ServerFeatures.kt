package com.chencohensolution.journal.server

import com.apollographql.apollo.api.Error
import com.chencohensolution.journal.graphql.ServerInfoQuery
import kotlinx.serialization.Serializable

/**
 * What the server can do (its `serverInfo`), so the apps offer only what works there: no People without [faces], no
 * text or similar photos in a photo's details without [textInPhotos] or [similar], no Settings › Search › On this phone
 * without [localIndexes]. Kept on this device (the last answer), so the screens know it before the server answers, and
 * offline.
 */
@Serializable
data class ServerFeatures(
    /** The API's version: raised when a change needs newer apps. */
    val api: Int = 0,
    /** The server's build. */
    val version: String = "",
    /** Text search (searchMedia). */
    val search: Boolean = false,
    val previews: Boolean = false,
    val posters: Boolean = false,
    val nudityScan: Boolean = false,
    /** Face search: People, the faces in a photo. */
    val faces: Boolean = false,
    /** Text in photos (a photo's details, and search finds photos by it). */
    val textInPhotos: Boolean = false,
    /** Similar photos (a photo's details) and duplicates. */
    val similar: Boolean = false,
    /** Aesthetic scores: a set's best photo, covers, Best first. */
    val aesthetic: Boolean = false,
    /** Phones may keep the indexes and search themselves (Settings › Search › On this phone). */
    val localIndexes: Boolean = false,
    /** A phone can start, follow and pause the reading of its user's photos (Settings › Search › On your server). */
    val indexing: Boolean = false,
    /** Sound recordings can be in the library: recordings, calls and voice messages (Moments, Hidden collections). */
    val audio: Boolean = false,
    /** Each photo's camera is known (a photo's info), and the user can mark their own (Settings › Your cameras). */
    val cameras: Boolean = false,
    /**
     * Folder ids are in lower case and named by `folderNames`, sound recordings can be call or recording, and the user
     * can merge sound folders into them (Settings › Sound folders).
     */
    val soundFolders: Boolean = false,
    /**
     * JPEG photos are kept as JPEG XL, losslessly, and a JPEG XL the phone made of a JPEG is checked to make that JPEG
     * again before the server takes it: backup then sends JPEGs as JPEG XL.
     */
    val keepsJpegAsJxl: Boolean = false,
    /** A phone can see how its user's copies on other disks are doing and start a pass (Settings › Backup). */
    val copies: Boolean = false,
    /**
     * A video's preview can be its very first frame, and the server cuts that frame out as a one-frame video for a
     * phone that decodes video thumbnails itself (Settings › Storage on this phone).
     */
    val videoFrames: Boolean = false,
) {
    companion object {
        /** Before the server answered once; for Swift, which has no default arguments. */
        val NONE = ServerFeatures()
    }
}

internal fun ServerInfoQuery.ServerInfo.toFeatures() = ServerFeatures(
    api = api,
    version = version,
    search = search,
    previews = previews,
    posters = posters,
    nudityScan = nudityScan,
    faces = faces,
    textInPhotos = textInPhotos,
    similar = similar,
    aesthetic = aesthetic,
    localIndexes = localIndexes,
    indexing = indexing,
    audio = audio,
    cameras = cameras,
    soundFolders = soundFolders,
    keepsJpegAsJxl = keepsJpegAsJxl,
    copies = copies,
)

/**
 * The codes the server puts in a GraphQL error a device acts on (`extensions.code`; journal-server-go docs/media.md
 * "Errors"). Anything else is a plain error, for the log.
 */
internal object ServerErrorCode {
    const val UNAUTHORIZED = "UNAUTHORIZED"
    const val NO_DATA_FOLDER = "NO_DATA_FOLDER"
    const val INVALID_ARGUMENT = "INVALID_ARGUMENT"
    const val SEARCH_OFF = "SEARCH_OFF"
    const val WITHHELD = "WITHHELD"
    const val SIMILAR_OFF = "SIMILAR_OFF"
    const val NOT_READ_YET = "NOT_READ_YET"
    const val INDEXING_OFF = "INDEXING_OFF"
}

/** The error's `extensions.code`; null for a plain error. */
internal val Error.code: String? get() = extensions?.get("code") as? String

/** A GraphQL error from the server, with its [code] when it has one. */
internal class ServerError(message: String, val code: String?) : Exception(message)

/** The first of [errors] as a [ServerError], to throw; null when there are none. */
internal fun firstServerError(errors: List<Error>?): ServerError? = errors?.firstOrNull()?.let { ServerError(it.message, it.code) }
