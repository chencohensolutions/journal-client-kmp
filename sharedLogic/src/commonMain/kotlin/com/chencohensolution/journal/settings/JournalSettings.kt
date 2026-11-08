package com.chencohensolution.journal.settings

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.KeepOnPhone
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.discovery.KeptDiscovery
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.server.ServerFeatures
import com.chencohensolution.journal.storage.FreeUpRule

/** Minimal persistent string storage: SharedPreferences on Android, NSUserDefaults on iOS. */
internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String?)
}

internal class JournalSettings(private val store: KeyValueStore) {
    var serverUrl: String
        get() = store.getString(KEY_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = store.putString(KEY_SERVER_URL, value)

    /**
     * Who the server is and where (`serverInfo.discovery`, docs/plans/discovery.md "Phones"); null before a server
     * that tells it answered. Its fingerprint is pinned by the mTLS clients once kept.
     */
    var serverDiscovery: KeptDiscovery?
        get() = store.getString(KEY_SERVER_DISCOVERY)?.let { runCatching { featuresJson.decodeFromString(KeptDiscovery.serializer(), it) }.getOrNull() }
        set(value) = store.putString(KEY_SERVER_DISCOVERY, value?.let { featuresJson.encodeToString(KeptDiscovery.serializer(), it) })

    /** The server key's fingerprint the mTLS clients pin; null: they check the CA and the host name. */
    val serverFingerprint: String? get() = serverDiscovery?.fingerprint

    /** Name of the client identity used for mTLS (Android KeyChain alias / iOS Keychain label). */
    var identityAlias: String?
        get() = store.getString(KEY_IDENTITY_ALIAS)
        set(value) = store.putString(KEY_IDENTITY_ALIAS, value)

    /** When this device's certificate expires (epoch milliseconds); null when not known (enrolled before it was kept). */
    var certificateExpiresAt: Long?
        get() = store.getString(KEY_CERTIFICATE_EXPIRES_AT)?.toLongOrNull()
        set(value) = store.putString(KEY_CERTIFICATE_EXPIRES_AT, value?.toString())

    /** Whether the photo access screen was answered (allowed, denied or "Not now"). */
    var photoAccessRequested: Boolean
        get() = store.getString(KEY_PHOTO_ACCESS_REQUESTED) == "true"
        set(value) = store.putString(KEY_PHOTO_ACCESS_REQUESTED, if (value) "true" else null)

    /** Creation time of the last story id this device issued (see StoryIds). */
    var lastStoryIdMillis: Long?
        get() = store.getString(KEY_LAST_STORY_ID_MILLIS)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_STORY_ID_MILLIS, value?.toString())

    /**
     * Photos and videos of the made-up library added to the real one (debug builds only, see MadeUpLibrary); 0 for
     * none. Read once, when the library is made.
     */
    var madeUpLibrary: Int
        get() = store.getString(KEY_MADE_UP_LIBRARY)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        set(value) = store.putString(KEY_MADE_UP_LIBRARY, value.takeIf { it > 0 }?.toString())

    /** How the Moments tab splits photos into rows; see [TimelineGrouping]. */
    var grouping: TimelineGrouping
        get() = TimelineGrouping(
            mode = store.getString(KEY_GROUPING_MODE)?.let { name -> GroupingMode.entries.firstOrNull { it.name == name } }
                ?: GroupingMode.BY_DAY,
            gapHours = store.getString(KEY_GROUPING_GAP_HOURS)?.toIntOrNull()?.takeIf { it in TimelineGrouping.GAP_HOURS_RANGE }
                ?: TimelineGrouping.DEFAULT_GAP_HOURS,
        )
        set(value) {
            store.putString(KEY_GROUPING_MODE, value.mode.name)
            store.putString(KEY_GROUPING_GAP_HOURS, value.gapHours.toString())
        }

    /** The "Look alike" threshold in percent; null for the platform's default. See [SimilarityThreshold]. */
    var similarityPercent: Int?
        get() = store.getString(KEY_SIMILARITY_PERCENT)?.toIntOrNull()
        set(value) = store.putString(KEY_SIMILARITY_PERCENT, value?.toString())

    /**
     * What the app shows of each source (see [ShownSources]), kept as the sources hidden. Until Oct 24, 2026 it was
     * kept as the fixed sources shown (`shown_sources`), and before Oct 21, 2026 as the Moments tab's filter bar
     * (`feed_sources`, whose WhatsApp and screenshots start off): a fixed source missing from them is hidden.
     */
    var shownSources: ShownSources
        get() {
            val hidden = store.getString(KEY_HIDDEN_SOURCES)?.let(::sourcesOf)
                ?: store.getString(KEY_SHOWN_SOURCES)?.let { old -> OLD_FIXED - sourcesOf(old) }
                ?: store.getString(KEY_FEED_SOURCES)?.let { old ->
                    OLD_FIXED - sourcesOf(old) + ShownSources.DEFAULT_HIDDEN
                }
                ?: ShownSources.DEFAULT_HIDDEN
            // Kept before Imported was a choice (Oct 27, 2026): its WhatsApp and screenshots start off, as for everyone.
            val imported = store.getString(KEY_SHOW_IMPORTED)
            val hiddenNow = if (imported == null) hidden + ShownSources.DEFAULT_HIDDEN_IMPORTED else hidden
            val alwaysShown = store.getString(KEY_ALWAYS_SHOWN_DAYS)?.split(",")?.mapNotNull { entry ->
                val day = LocalDay.parse(entry.substringBefore(":")) ?: return@mapNotNull null
                DaySource(day, MediaSource.parse(unescape(entry.substringAfter(":", ""))) ?: return@mapNotNull null)
            }?.toSet().orEmpty()
            return ShownSources(
                hiddenNow,
                store.getString(KEY_SAY_WHATS_HIDDEN) != "false",
                alwaysShown,
                store.getString(KEY_SHOW_FILTER_BAR) != "false",
                importedShown = imported != "false",
            )
        }
        set(value) {
            store.putString(KEY_HIDDEN_SOURCES, idsOf(value.hidden))
            store.putString(KEY_SHOWN_SOURCES, null)
            store.putString(KEY_FEED_SOURCES, null)
            store.putString(KEY_SAY_WHATS_HIDDEN, value.sayWhatsHidden.toString())
            store.putString(KEY_SHOW_FILTER_BAR, value.showFilterBar.toString())
            store.putString(KEY_SHOW_IMPORTED, value.importedShown.toString())
            store.putString(KEY_ALWAYS_SHOWN_DAYS, value.alwaysShown.joinToString(",") { "${it.day}:${escape(it.source.id)}" }.ifEmpty { null })
        }

    /**
     * Sources kept as their ids with commas between ([MediaSource.id]; a folder's name may have a comma, kept as
     * "%2C"). Ids of the sources before folders ("CAMERA") are read too, and "whatsapp" as the folder WhatsApp it is
     * since Oct 27, 2026, and folders in any case ("folder:WhatsApp", "folder:Petcube"), all written back in lower case
     * ("folder:whatsapp"); an unknown one is skipped.
     */
    private fun sourcesOf(stored: String): Set<MediaSource> =
        stored.split(",").filter { it.isNotEmpty() }.mapNotNull { MediaSource.parse(unescape(it)) }.toSet()

    private fun idsOf(sources: Set<MediaSource>): String = sources.sorted().joinToString(",") { escape(it.id) }

    private fun escape(id: String) = id.replace("%", "%25").replace(",", "%2C")

    private fun unescape(stored: String) = stored.replace("%2C", ",").replace("%25", "%")

    /** What uploads to the server and when (Settings → Backup). */
    var backup: BackupSettings
        get() = BackupSettings(
            enabled = store.getString(KEY_BACKUP_ENABLED) == "true",
            // Camera kept from before folders (Oct 24, 2026) is the Camera folder now, as the owner asked: the other
            // DCIM folders it took then are choices of their own, off until turned on.
            sources = store.getString(KEY_BACKUP_SOURCES)?.let(::sourcesOf) ?: BackupSettings.DEFAULT_SOURCES,
            soundFolders = store.getString(KEY_BACKUP_SOUND_FOLDERS)
                ?.let { runCatching { featuresJson.decodeFromString(StoredSoundFolders.serializer(), it).folders }.getOrNull() }
                .orEmpty(),
            keepOnPhone = if (store.getString(KEY_BACKUP_KEEP_ON_PHONE) == KeepOnPhone.FLAC.name) KeepOnPhone.FLAC else KeepOnPhone.WAV,
            keepJpegXlOnPhone = store.getString(KEY_BACKUP_KEEP_JXL) == "true",
            wifiOnly = store.getString(KEY_BACKUP_WIFI_ONLY) != "false",
            // One per line: network names may contain commas.
            wifiNetworks = store.getString(KEY_BACKUP_WIFI_NETWORKS)?.split("\n")?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            chargingOnly = store.getString(KEY_BACKUP_CHARGING_ONLY) == "true",
            order = store.getString(KEY_BACKUP_ORDER)?.let { name -> UploadOrder.entries.firstOrNull { it.name == name } }
                ?: UploadOrder.OLDEST_FIRST,
        )
        set(value) {
            store.putString(KEY_BACKUP_ENABLED, value.enabled.toString())
            store.putString(KEY_BACKUP_SOURCES, idsOf(value.sources))
            store.putString(
                KEY_BACKUP_SOUND_FOLDERS,
                value.soundFolders.takeIf { it.isNotEmpty() }?.let { featuresJson.encodeToString(StoredSoundFolders.serializer(), StoredSoundFolders(v = 1, folders = it)) },
            )
            store.putString(KEY_BACKUP_KEEP_ON_PHONE, value.keepOnPhone.name)
            store.putString(KEY_BACKUP_KEEP_JXL, value.keepJpegXlOnPhone.toString())
            store.putString(KEY_BACKUP_WIFI_ONLY, value.wifiOnly.toString())
            store.putString(KEY_BACKUP_WIFI_NETWORKS, value.wifiNetworks.joinToString("\n").ifEmpty { null })
            store.putString(KEY_BACKUP_CHARGING_ONLY, value.chargingOnly.toString())
            store.putString(KEY_BACKUP_ORDER, value.order.name)
        }

    /** How much the previews of server photos may take up on this device, in GB (Settings → Storage on this phone). */
    /** Photos per page of search results (Settings › Search); one of [SettingsState.SEARCH_PAGE_OPTIONS]. */
    var searchPageSize: Int
        get() = store.getString(KEY_SEARCH_PAGE_SIZE)?.toIntOrNull()?.takeIf { it in 1..1000 } ?: DEFAULT_SEARCH_PAGE_SIZE
        set(value) = store.putString(KEY_SEARCH_PAGE_SIZE, value.toString())

    /** How Moments is read a page at a time (Settings › Advanced › Loading Moments); each value falls back on its own. */
    var momentsPaging: MomentsPaging
        get() = MomentsPaging(
            rowsPerPage = store.getString(KEY_MOMENTS_ROWS_PER_PAGE)?.toIntOrNull()?.takeIf { it in MomentsPaging.RANGE }
                ?: MomentsPaging.DEFAULT_ROWS_PER_PAGE,
            loadAhead = store.getString(KEY_MOMENTS_LOAD_AHEAD)?.toIntOrNull()?.takeIf { it in MomentsPaging.RANGE }
                ?: MomentsPaging.DEFAULT_LOAD_AHEAD,
        )
        set(value) {
            store.putString(KEY_MOMENTS_ROWS_PER_PAGE, value.rowsPerPage.toString())
            store.putString(KEY_MOMENTS_LOAD_AHEAD, value.loadAhead.toString())
        }

    var previewCacheGigabytes: Int
        get() = store.getString(KEY_PREVIEW_CACHE_GB)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PREVIEW_CACHE_GB
        set(value) = store.putString(KEY_PREVIEW_CACHE_GB, value.toString())

    /**
     * The sources whose photos "Free up space" keeps on the phone (none by default), so a folder seen for the first
     * time may go. Until Oct 24, 2026 it was kept as the fixed sources that may go (`free_up_sources`).
     */
    /** The sound folders (tree URIs) whose files "Free up space" keeps on the phone (none by default). One per line. */
    var freeUpKeptFolders: Set<String>
        get() = store.getString(KEY_FREE_UP_KEPT_FOLDERS)?.split("\n")?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) = store.putString(KEY_FREE_UP_KEPT_FOLDERS, value.joinToString("\n").ifEmpty { null })

    var freeUpKept: Set<MediaSource>
        get() = store.getString(KEY_FREE_UP_KEPT)?.let(::sourcesOf)
            ?: store.getString(KEY_FREE_UP_SOURCES)?.let(::sourcesOf)?.takeIf { it.isNotEmpty() }?.let { OLD_FIXED - it }
            ?: emptySet()
        set(value) {
            store.putString(KEY_FREE_UP_KEPT, idsOf(value))
            store.putString(KEY_FREE_UP_SOURCES, null)
        }

    /** How server photos are shown: previews (format, quality) or their originals (Settings → Storage on this phone). */
    var previewOptions: PreviewOptions
        get() = PreviewOptions(
            enabled = store.getString(KEY_PREVIEWS_ENABLED) != "false",
            format = store.getString(KEY_PREVIEW_FORMAT)?.let { name -> PreviewFormat.entries.firstOrNull { it.name == name } }
                ?: PreviewFormat.WEBP,
            quality = store.getString(KEY_PREVIEW_QUALITY)?.let { name -> PreviewQuality.entries.firstOrNull { it.name == name } }
                ?: PreviewQuality.HIGH,
            firstFormat = store.getString(KEY_FIRST_IMAGE_FORMAT)?.let { name -> PreviewFormat.entries.firstOrNull { it.name == name } }
                ?: PreviewFormat.HEIF,
            firstWidthPercent = store.getString(KEY_FIRST_IMAGE_WIDTH_PERCENT)?.toIntOrNull()?.takeIf { it in 0..100 }
                ?: PreviewOptions.DEFAULT_FIRST_WIDTH_PERCENT,
            videoFramesOnPhone = store.getString(KEY_VIDEO_FRAMES_ON_PHONE) == "true",
        )
        set(value) {
            store.putString(KEY_PREVIEWS_ENABLED, value.enabled.toString())
            store.putString(KEY_PREVIEW_FORMAT, value.format.name)
            store.putString(KEY_PREVIEW_QUALITY, value.quality.name)
            store.putString(KEY_FIRST_IMAGE_FORMAT, value.firstFormat.name)
            store.putString(KEY_FIRST_IMAGE_WIDTH_PERCENT, value.firstWidthPercent.toString())
            store.putString(KEY_VIDEO_FRAMES_ON_PHONE, value.videoFramesOnPhone.toString())
        }

    /** "Show private media" (Settings → Private media); off by default. */
    var privateMedia: PrivateMedia
        get() = PrivateMedia(show = store.getString(KEY_SHOW_PRIVATE_MEDIA) == "true")
        set(value) {
            store.putString(KEY_SHOW_PRIVATE_MEDIA, value.show.toString())
            // The nudity threshold kept here before the server hid nudity.
            store.putString(KEY_PRIVATE_THRESHOLD_PERCENT, null)
        }

    /** The server's last `serverInfo` (see [ServerFeatures]); null before it answered once. */
    var serverFeatures: ServerFeatures?
        get() = store.getString(KEY_SERVER_FEATURES)?.let { runCatching { featuresJson.decodeFromString(ServerFeatures.serializer(), it) }.getOrNull() }
        set(value) = store.putString(KEY_SERVER_FEATURES, value?.let { featuresJson.encodeToString(ServerFeatures.serializer(), it) })

    /**
     * The indexes this phone keeps to search itself (Settings › Search › On this phone), by the server's names
     * (search, faces, ocr, similar, aesthetic). Only the aesthetic scores at first: they are small, and pick a set's best
     * photo and the covers.
     */
    var phoneIndexes: Set<String>
        get() = store.getString(KEY_PHONE_INDEXES)?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: DEFAULT_PHONE_INDEXES
        set(value) = store.putString(KEY_PHONE_INDEXES, value.sorted().joinToString(","))

    /** When the kept indexes last caught up with the server (epoch milliseconds); null: never. */
    var phoneIndexesUpdatedAt: Long?
        get() = store.getString(KEY_PHONE_INDEXES_UPDATED_AT)?.toLongOrNull()
        set(value) = store.putString(KEY_PHONE_INDEXES_UPDATED_AT, value?.toString())

    /** On this phone › Only on Wi-Fi: downloads wait for Wi-Fi (on by default). */
    var phoneIndexesWifiOnly: Boolean
        get() = store.getString(KEY_PHONE_INDEXES_WIFI_ONLY) != "false"
        set(value) = store.putString(KEY_PHONE_INDEXES_WIFI_ONLY, value.toString())

    /** "Show the phone badge" (Settings → Badges): off by default. */
    var showOnPhoneBadge: Boolean
        get() = store.getString(KEY_SHOW_ON_PHONE_BADGE) == "true"
        set(value) = store.putString(KEY_SHOW_ON_PHONE_BADGE, value.toString())

    /** Whether a JPEG XL photo is shared as the JPEG it was packed from (on until turned off). */
    var shareJpegXlAsJpeg: Boolean
        get() = store.getString(KEY_SHARE_JXL_AS_JPEG) != "false"
        set(value) = store.putString(KEY_SHARE_JXL_AS_JPEG, value.toString())

    /** The rule "Free up space" used last ("all", "days:365" or "keep:10"). */
    var freeUpRule: FreeUpRule
        get() {
            val stored = store.getString(KEY_FREE_UP_RULE)
            if (stored == "all") return FreeUpRule.Everything
            val (kind, number) = stored?.split(":")?.takeIf { it.size == 2 } ?: return FreeUpRule.DEFAULT
            val n = number.toIntOrNull()?.takeIf { it > 0 } ?: return FreeUpRule.DEFAULT
            return when (kind) {
                "days" -> FreeUpRule.OlderThan(n)
                "keep" -> FreeUpRule.KeepNewest(n)
                else -> FreeUpRule.DEFAULT
            }
        }
        set(value) = store.putString(
            KEY_FREE_UP_RULE,
            when (value) {
                FreeUpRule.Everything -> "all"
                is FreeUpRule.OlderThan -> "days:${value.days}"
                is FreeUpRule.KeepNewest -> "keep:${value.gigabytes}"
            },
        )

    companion object {
        const val DEFAULT_PREVIEW_CACHE_GB = 10
        const val DEFAULT_SEARCH_PAGE_SIZE = 200
        val DEFAULT_PHONE_INDEXES = setOf("aesthetic")
        private val featuresJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /**
         * The fixed sources when settings were kept as those shown (before Oct 24, 2026), WhatsApp among them: what an
         * old `shown_sources`, `feed_sources` or `free_up_sources` leaves out is read against these.
         */
        private val OLD_FIXED: Set<MediaSource> =
            setOf(MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS, MediaSource.OTHER)
        private const val KEY_SERVER_FEATURES = "server_features"
        private const val KEY_PHONE_INDEXES = "phone_indexes"
        private const val KEY_PHONE_INDEXES_WIFI_ONLY = "phone_indexes_wifi_only"
        private const val KEY_PHONE_INDEXES_UPDATED_AT = "phone_indexes_updated_at"
        const val DEFAULT_SERVER_URL = "https://journal.example.com:56398/query"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_SERVER_DISCOVERY = "server_discovery"
        private const val KEY_IDENTITY_ALIAS = "keychain_alias"
        private const val KEY_CERTIFICATE_EXPIRES_AT = "certificate_expires_at"
        private const val KEY_PHOTO_ACCESS_REQUESTED = "photo_access_requested"
        private const val KEY_LAST_STORY_ID_MILLIS = "last_story_id_millis"
        private const val KEY_MADE_UP_LIBRARY = "made_up_library"
        private const val KEY_GROUPING_MODE = "grouping_mode"
        private const val KEY_GROUPING_GAP_HOURS = "grouping_gap_hours"
        private const val KEY_SIMILARITY_PERCENT = "similarity_percent"
        private const val KEY_FEED_SOURCES = "feed_sources"
        private const val KEY_SHOWN_SOURCES = "shown_sources"
        private const val KEY_HIDDEN_SOURCES = "hidden_sources"
        private const val KEY_SHOW_FILTER_BAR = "show_filter_bar"
        private const val KEY_SHOW_IMPORTED = "show_imported"
        private const val KEY_SAY_WHATS_HIDDEN = "say_whats_hidden"
        private const val KEY_ALWAYS_SHOWN_DAYS = "always_shown_days"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"
        private const val KEY_BACKUP_SOURCES = "backup_sources"
        private const val KEY_BACKUP_SOUND_FOLDERS = "backup_sound_folders"
        private const val KEY_BACKUP_KEEP_ON_PHONE = "backup_keep_on_phone"
        private const val KEY_BACKUP_KEEP_JXL = "backup_keep_jxl"
        private const val KEY_BACKUP_WIFI_ONLY = "backup_wifi_only"
        private const val KEY_BACKUP_WIFI_NETWORKS = "backup_wifi_networks"
        private const val KEY_BACKUP_CHARGING_ONLY = "backup_charging_only"
        private const val KEY_BACKUP_ORDER = "backup_order"
        private const val KEY_PREVIEW_CACHE_GB = "preview_cache_gb"
        private const val KEY_SEARCH_PAGE_SIZE = "search_page_size"
        private const val KEY_MOMENTS_ROWS_PER_PAGE = "moments_rows_per_page"
        private const val KEY_MOMENTS_LOAD_AHEAD = "moments_load_ahead"
        private const val KEY_FREE_UP_RULE = "free_up_rule"
        private const val KEY_FREE_UP_SOURCES = "free_up_sources"
        private const val KEY_FREE_UP_KEPT = "free_up_kept"
        private const val KEY_FREE_UP_KEPT_FOLDERS = "free_up_kept_folders"
        private const val KEY_PREVIEWS_ENABLED = "previews_enabled"
        private const val KEY_PREVIEW_FORMAT = "preview_format"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_FIRST_IMAGE_FORMAT = "first_image_format"
        private const val KEY_FIRST_IMAGE_WIDTH_PERCENT = "first_image_width_percent"
        private const val KEY_VIDEO_FRAMES_ON_PHONE = "video_frames_on_phone"
        private const val KEY_SHOW_PRIVATE_MEDIA = "show_private_media"
        /** No longer read: the server hides nudity (Hidden collections). Removed when "Show private media" is saved. */
        private const val KEY_PRIVATE_THRESHOLD_PERCENT = "private_threshold_percent"
        private const val KEY_SHOW_ON_PHONE_BADGE = "show_on_phone_badge"
        private const val KEY_SHARE_JXL_AS_JPEG = "share_jxl_as_jpeg"
    }
}

/** The picked sound folders as kept in settings; [v] is the format's version, for a change later. */
@kotlinx.serialization.Serializable
private class StoredSoundFolders(val v: Int, val folders: List<SoundFolder>)
