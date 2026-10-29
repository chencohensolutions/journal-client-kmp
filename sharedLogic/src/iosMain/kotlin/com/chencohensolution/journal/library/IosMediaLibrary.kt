package com.chencohensolution.journal.library

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSKeyedArchiver
import platform.Foundation.NSKeyedUnarchiver
import platform.Foundation.NSNumber
import platform.Foundation.NSTimeZone
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.localTimeZone
import platform.Foundation.NSSelectorFromString
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAlbumRegular
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetMediaSubtypePhotoScreenshot
import platform.Photos.PHAssetMediaSubtypeVideoScreenRecording
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypeVideo
import platform.Photos.PHAssetSourceTypeUserLibrary
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHChange
import platform.Photos.PHFetchOptions
import platform.Photos.PHObjectTypeAsset
import platform.Photos.PHObjectTypeAssetCollection
import platform.Photos.PHPersistentChange
import platform.Photos.PHPersistentChangeToken
import platform.Photos.PHPhotoLibrary
import platform.Photos.PHPhotoLibraryChangeObserverProtocol
import platform.UniformTypeIdentifiers.UTType
import platform.darwin.NSObject
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

/**
 * Reads photos and videos from PhotoKit. Ids are PHAsset local identifiers. File name, size and type come from each
 * asset's files (PHAssetResource), which is slow on a big library: they are kept in [cache], and when many photos
 * are new (the first start) the library is returned without them and they are read in the background, telling
 * [watch] as they arrive. [changesSince] reads only what changed since a mark, by PhotoKit's persistent changes
 * (`fetchPersistentChangesSinceToken`, the token kept as the mark; docs/plans/library-on-disk.md, phase 2).
 */
internal class IosMediaLibrary(private val cache: FileFactsCache? = null) : ChangingLibrary {
    private val factsLock = Mutex()

    /** Local identifier → facts, loaded from [cache] on the first read. Guarded by [factsLock]. */
    private var facts: MutableMap<String, FileFacts>? = null
    private val reading = AtomicInt(0)
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val listeners = AtomicReference<List<() -> Unit>>(emptyList())
    private val observing = AtomicInt(0)
    private val observer = ChangeObserver { listeners.value.forEach { it() } }

    override fun access(): PhotoAccess = when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
        PHAuthorizationStatusAuthorized -> PhotoAccess.FULL
        PHAuthorizationStatusLimited -> PhotoAccess.LIMITED
        PHAuthorizationStatusNotDetermined -> PhotoAccess.NOT_DETERMINED
        else -> PhotoAccess.DENIED
    }

    override suspend fun loadItems(): List<MediaItem> = withContext(Dispatchers.Default) {
        observe()
        val assets = allAssets()
        val known = knownFacts(assets, whole = true)
        val albums = albumsOf()
        assets.map { asset -> itemOf(asset, known[asset.localIdentifier], albums[asset.localIdentifier].orEmpty()) }
    }

    /**
     * What changed since [mark]: the assets PhotoKit's persistent changes name as inserted or updated, and those of the
     * albums they name (an app saving into its album changes its photos' source). Everything is read again when there is
     * no mark, when it was made by another [READER_VERSION], in another time zone (days depend on it) or with another
     * access (picking more photos with limited access changes nothing PhotoKit records), when an album was deleted, and
     * when PhotoKit can't tell (its history was trimmed). An ids-only pass finds what was deleted.
     */
    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    override suspend fun changesSince(mark: String?): LibraryChanges = withContext(Dispatchers.Default) {
        observe()
        val access = access()
        val head = "$READER_VERSION|${NSTimeZone.localTimeZone.name}|$access"
        // The token is taken before the assets are read: what changes meanwhile is read again next time, never missed.
        val token = encode(PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken) ?: ""
        // Items still without their file's facts (read in the background on a first start, or a photo the camera is
        // still processing) get them only by a whole read: PhotoKit won't name them as changed. Until all have them,
        // the mark keeps no token.
        fun markFor(items: List<MediaItem>) = head + "|" + (if (items.any { it.fileName == null }) "" else token)
        val since = mark?.takeIf { access == PhotoAccess.FULL && it.substringBeforeLast("|") == head }
            ?.substringAfterLast("|")?.let(::decode)
        val changed = since?.let(::changedIds)
        if (changed == null) {
            val all = loadItems()
            return@withContext LibraryChanges(markFor(all), full = true, changed = all, present = all.mapTo(HashSet()) { it.id })
        }
        val present = allAssets().mapTo(HashSet()) { it.localIdentifier }
        val items = items(changed.filter { it in present })
        LibraryChanges(markFor(items), full = false, changed = items, present = present)
    }

    override suspend fun items(ids: Collection<String>): List<MediaItem> = withContext(Dispatchers.Default) {
        if (ids.isEmpty()) return@withContext emptyList()
        val assets = ids.chunked(500).flatMap { chunk ->
            val result = PHAsset.fetchAssetsWithLocalIdentifiers(chunk, null)
            (0uL until result.count).map { result.objectAtIndex(it) as PHAsset }
        }.filter { it.mediaType == PHAssetMediaTypeImage || it.mediaType == PHAssetMediaTypeVideo }
        val known = knownFacts(assets, whole = false)
        assets.map { asset -> itemOf(asset, known[asset.localIdentifier], albumsOf(asset)) }
    }

    /**
     * The ids PhotoKit says were inserted or updated since [token], with every asset of the albums it says were inserted
     * or updated; null when it can't tell, or an album was deleted (its photos' sources may have changed).
     */
    @OptIn(ExperimentalForeignApi::class)
    private fun changedIds(token: PHPersistentChangeToken): Set<String>? = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        val result = PHPhotoLibrary.sharedPhotoLibrary().fetchPersistentChangesSinceToken(token, error.ptr) ?: return null
        val ids = HashSet<String>()
        val albums = HashSet<String>()
        var unknown = false
        result.enumerateChangesWithBlock { change, stop ->
            change as PHPersistentChange
            val assets = change.changeDetailsForObjectType(PHObjectTypeAsset, null)
            val collections = change.changeDetailsForObjectType(PHObjectTypeAssetCollection, null)
            if (assets == null || collections == null) {
                unknown = true
            } else {
                assets.insertedLocalIdentifiers.forEach { ids += it as String }
                assets.updatedLocalIdentifiers.forEach { ids += it as String }
                collections.insertedLocalIdentifiers.forEach { albums += it as String }
                collections.updatedLocalIdentifiers.forEach { albums += it as String }
                if (collections.deletedLocalIdentifiers.isNotEmpty()) unknown = true
            }
            if (unknown) stop?.pointed?.value = true
        }
        if (unknown) return null
        if (albums.isNotEmpty()) {
            val found = PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(albums.toList(), null)
            for (i in 0uL until found.count) {
                val assets = PHAsset.fetchAssetsInAssetCollection(found.objectAtIndex(i) as PHAssetCollection, null)
                for (j in 0uL until assets.count) ids += (assets.objectAtIndex(j) as PHAsset).localIdentifier
            }
        }
        ids
    }

    /** Every photo and video, as PHAssets. */
    private fun allAssets(): List<PHAsset> {
        val assets = mutableListOf<PHAsset>()
        for (mediaType in listOf(PHAssetMediaTypeImage, PHAssetMediaTypeVideo)) {
            val result = PHAsset.fetchAssetsWithMediaType(mediaType, null)
            for (i in 0uL until result.count) assets += result.objectAtIndex(i) as PHAsset
        }
        return assets
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun itemOf(asset: PHAsset, file: FileFacts?, albums: List<String>): MediaItem {
        val date = asset.creationDate ?: asset.modificationDate ?: NSDate()
        val parts = NSCalendar.currentCalendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, date)
        val isVideo = asset.mediaType == PHAssetMediaTypeVideo
        val screen = PHAssetMediaSubtypePhotoScreenshot or PHAssetMediaSubtypeVideoScreenRecording
        return MediaItem(
            id = asset.localIdentifier,
            type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            takenAtMillis = date.millis,
            day = LocalDay(parts.year.toInt(), parts.month.toInt(), parts.day.toInt()),
            durationMillis = if (isVideo) (asset.duration * 1000).toLong().takeIf { it > 0 } else null,
            width = asset.pixelWidth.toInt(),
            height = asset.pixelHeight.toInt(),
            sizeBytes = file?.sizeBytes,
            fileName = file?.fileName,
            burstId = asset.burstIdentifier,
            source = sourceOfAsset(
                isScreenshot = asset.mediaSubtypes and screen != 0uL,
                albums = albums,
                inUserLibrary = asset.sourceType == PHAssetSourceTypeUserLibrary,
                originalMimeType = file?.originalMimeType,
                fileName = file?.fileName,
            ),
            location = asset.location?.coordinate?.useContents { GeoPoint(latitude, longitude) },
            modifiedMillis = file?.modifiedMillis,
            mimeType = file?.mimeType,
            folder = albums.joinToString(", ").ifEmpty { null },
        )
    }

    override fun watch(onChange: () -> Unit): () -> Unit {
        listeners.update { it + onChange }
        // Registering before the user answered the photo access prompt would ask for access; loadItems does it then.
        if (access() == PhotoAccess.FULL || access() == PhotoAccess.LIMITED) observe()
        return { listeners.update { it - onChange } }
    }

    private fun observe() {
        if (observing.compareAndSet(0, 1)) PHPhotoLibrary.sharedPhotoLibrary().registerChangeObserver(observer)
    }

    /**
     * The facts of [assets] that are known and current. The rest are read now when they are few (photos taken or
     * changed since the last read), else in the background ([readInBackground]). With [whole] (every asset), the
     * facts of those gone are dropped.
     */
    private suspend fun knownFacts(assets: List<PHAsset>, whole: Boolean): Map<String, FileFacts> {
        val (known, stale) = factsLock.withLock {
            val all = facts ?: (cache?.load() ?: mutableMapOf()).also { facts = it }
            val ids = assets.mapTo(HashSet()) { it.localIdentifier }
            val removed = whole && all.keys.retainAll(ids)
            val stale = assets.filter { asset ->
                val fact = all[asset.localIdentifier]
                fact == null || fact.assetModifiedMillis != asset.modificationDate?.millis
            }
            if (removed && stale.isEmpty()) cache?.save(all)
            HashMap(all) to stale
        }
        if (stale.isEmpty() && known.values.none { it.fileName == null }) return known
        if (stale.size > READ_NOW) {
            readInBackground(stale)
            return known
        }
        // A photo the camera is still processing has no file yet: tried again on each load, a few at a time.
        val staleIds = stale.mapTo(HashSet()) { it.localIdentifier }
        val unread = assets.filter { it.localIdentifier !in staleIds && known[it.localIdentifier]?.fileName == null }.take(RETRY_UNREAD)
        val read = (stale + unread).map { asset -> asset.localIdentifier to readFacts(asset) }
        return factsLock.withLock {
            val all = facts!!
            for ((id, fact) in read) all[id] = fact.after(all[id])
            cache?.save(all)
            HashMap(all)
        }
    }

    /** Reads [assets]' facts a batch at a time, saving and telling the listeners every few seconds. One run at a time. */
    private fun readInBackground(assets: List<PHAsset>) {
        if (!reading.compareAndSet(0, 1)) return
        readScope.launch {
            try {
                var told = NSDate().millis
                for (batch in assets.chunked(READ_BATCH)) {
                    val read = batch.map { asset -> asset.localIdentifier to readFacts(asset) }
                    factsLock.withLock {
                        val all = facts!!
                        for ((id, fact) in read) all[id] = fact.after(all[id])
                    }
                    if (NSDate().millis - told >= TELL_EVERY_MILLIS) {
                        factsLock.withLock { cache?.save(facts!!) }
                        listeners.value.forEach { it() }
                        told = NSDate().millis
                    }
                }
                factsLock.withLock { cache?.save(facts!!) }
            } finally {
                reading.value = 0
            }
            // The library is read again with what arrived, and whatever is new since is read then.
            listeners.value.forEach { it() }
        }
    }

    /**
     * The titles of the regular albums each photo is in, by its id: WhatsApp and other apps save into an album of
     * their own ([sourceOfAsset]). Read on every load, as an album has no date.
     */
    private fun albumsOf(): Map<String, List<String>> {
        val albumsOf = HashMap<String, MutableList<String>>()
        val albums = PHAssetCollection.fetchAssetCollectionsWithType(PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAlbumRegular, null)
        for (i in 0uL until albums.count) {
            val album = albums.objectAtIndex(i) as PHAssetCollection
            val title = album.localizedTitle?.takeIf { it.isNotBlank() } ?: continue
            val assets = PHAsset.fetchAssetsInAssetCollection(album, null)
            for (j in 0uL until assets.count) {
                albumsOf.getOrPut((assets.objectAtIndex(j) as PHAsset).localIdentifier) { mutableListOf() } += title
            }
        }
        return albumsOf
    }

    /** The titles of the regular albums [asset] is in, as [albumsOf] gives them for every photo. */
    private fun albumsOf(asset: PHAsset): List<String> {
        val albums = PHAssetCollection.fetchAssetCollectionsContainingAsset(asset, PHAssetCollectionTypeAlbum, null)
        return (0uL until albums.count).mapNotNull { i ->
            val album = albums.objectAtIndex(i) as PHAssetCollection
            album.localizedTitle?.takeIf { album.assetCollectionSubtype == PHAssetCollectionSubtypeAlbumRegular && it.isNotBlank() }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun encode(token: PHPersistentChangeToken?): String? {
        token ?: return null
        return NSKeyedArchiver.archivedDataWithRootObject(token, requiringSecureCoding = true, error = null)?.base64EncodedStringWithOptions(0u)
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun decode(text: String): PHPersistentChangeToken? {
        if (text.isEmpty()) return null
        val data = NSData.create(base64EncodedString = text, options = 0u) ?: return null
        return NSKeyedUnarchiver.unarchivedObjectOfClass(PHPersistentChangeToken, data, null) as? PHPersistentChangeToken
    }

    // Its own pool: a first read runs through the whole library in one task.
    private fun readFacts(asset: PHAsset): FileFacts = autoreleasepool {
        val files = PHAssetResource.assetResourcesForAsset(asset).map { resource ->
            resource as PHAssetResource
            AssetFile(resource.type, resource.originalFilename, resource.uniformTypeIdentifier, sizeOf(resource))
        }
        factsOf(files, asset.mediaType == PHAssetMediaTypeVideo, asset.modificationDate?.millis, ::mimeTypeOf)
    }

    private class ChangeObserver(private val onChange: () -> Unit) : NSObject(), PHPhotoLibraryChangeObserverProtocol {
        // On a background queue; LibraryController waits for changes to settle.
        override fun photoLibraryDidChange(changeInstance: PHChange) = onChange()
    }

    private companion object {
        /** Changes when what an item holds is read differently, so marks made before read the library whole once. */
        const val READER_VERSION = 1

        /** More stale photos than this are read in the background (a few ms each). */
        const val READ_NOW = 300
        const val READ_BATCH = 200
        const val RETRY_UNREAD = 20
        const val TELL_EVERY_MILLIS = 5_000L
    }
}

/** One of an asset's files, as much as choosing between them needs. [type] is a PHAssetResourceType. */
internal class AssetFile(val type: Long, val name: String, val uti: String, val sizeBytes: Long?)

/**
 * The facts of the file a [MediaItem] stands for, of an asset's [files]: its current version, which is the edit's
 * render (full-size photo or video) when it was edited, else the original photo or video. A Live Photo is its photo
 * (the paired video is the second file of the pair); RAW+JPEG is its JPEG. The name is the original's, so it matches
 * the photo's server copy and other devices. All null while the camera is still processing the photo (iOS 17
 * deferred processing: only a proxy file), or for an asset without its file.
 */
internal fun factsOf(files: List<AssetFile>, isVideo: Boolean, modifiedMillis: Long?, mimeType: (String) -> String?): FileFacts {
    val (original, current) = currentFile(files, isVideo) ?: return FileFacts(modifiedMillis, null, null, null, null, null)
    return FileFacts(
        assetModifiedMillis = modifiedMillis,
        fileName = if (current === original) original.name else editedFileName(original.name, current.name),
        sizeBytes = current.sizeBytes?.takeIf { it > 0 },
        mimeType = mimeType(current.uti),
        originalMimeType = mimeType(original.uti),
        modifiedMillis = modifiedMillis,
    )
}

/**
 * The original of an asset's [files] and the file a [MediaItem] stands for (see [factsOf]); null without an original.
 * Backup reads the second, so it gets the very bytes whose size the library reported.
 */
internal fun currentFile(files: List<AssetFile>, isVideo: Boolean): Pair<AssetFile, AssetFile>? {
    fun file(type: Long) = files.firstOrNull { it.type == type }
    val original = (if (isVideo) file(PHAssetResourceTypeVideo) else file(PHAssetResourceTypePhoto)) ?: return null
    val current = (if (isVideo) file(PHAssetResourceTypeFullSizeVideo) else file(PHAssetResourceTypeFullSizePhoto)) ?: original
    return original to current
}

/** The resource of [asset] that its [MediaItem] stands for, as [factsOf] chooses it: what backup reads. */
internal fun currentResource(asset: PHAsset): PHAssetResource? {
    val resources = PHAssetResource.assetResourcesForAsset(asset).map { it as PHAssetResource }
    val files = resources.map { AssetFile(it.type, it.originalFilename, it.uniformTypeIdentifier, null) }
    val (_, current) = currentFile(files, asset.mediaType == PHAssetMediaTypeVideo) ?: return null
    return resources[files.indexOf(current)]
}

/**
 * A file's size without reading it. iOS 27 has `dataSize`; before, the same number is the undocumented `fileSize`
 * key, which apps have long read this way. Both are asked only if the resource answers them, so a future iOS without
 * them gives null (backup then skips the photo) rather than a crash.
 */
@OptIn(ExperimentalForeignApi::class)
private fun sizeOf(resource: PHAssetResource): Long? {
    for (key in listOf("dataSize", "fileSize")) {
        if (resource.respondsToSelector(NSSelectorFromString(key))) return (resource.valueForKey(key) as? NSNumber)?.longLongValue
    }
    return null
}

/**
 * "public.heic" → "image/heic". The common ones are also listed here: outside an app (the simulator's test runner)
 * UTType knows no MIME types.
 */
internal fun mimeTypeOf(uti: String): String? = UTType.typeWithIdentifier(uti)?.preferredMIMEType ?: KNOWN_MIME_TYPES[uti]

private val KNOWN_MIME_TYPES = mapOf(
    "public.heic" to "image/heic",
    "public.heif" to "image/heif",
    "public.jpeg" to "image/jpeg",
    "public.png" to "image/png",
    "com.compuserve.gif" to "image/gif",
    "org.webmproject.webp" to "image/webp",
    "com.adobe.raw-image" to "image/x-adobe-dng",
    "com.apple.quicktime-movie" to "video/quicktime",
    "public.mpeg-4" to "video/mp4",
)

private val NSDate.millis: Long get() = (timeIntervalSince1970 * 1000).toLong()

private fun <T> AtomicReference<T>.update(change: (T) -> T) {
    while (true) {
        val old = value
        if (compareAndSet(old, change(old))) return
    }
}
