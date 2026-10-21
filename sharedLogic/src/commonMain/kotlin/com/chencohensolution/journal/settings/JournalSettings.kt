package com.chencohensolution.journal.settings

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
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

    /** Name of the client identity used for mTLS (Android KeyChain alias / iOS Keychain label). */
    var identityAlias: String?
        get() = store.getString(KEY_IDENTITY_ALIAS)
        set(value) = store.putString(KEY_IDENTITY_ALIAS, value)

    /** Whether the photo access screen was answered (allowed, denied or "Not now"). */
    var photoAccessRequested: Boolean
        get() = store.getString(KEY_PHOTO_ACCESS_REQUESTED) == "true"
        set(value) = store.putString(KEY_PHOTO_ACCESS_REQUESTED, if (value) "true" else null)

    /** Creation time of the last story id this device issued (see StoryIds). */
    var lastStoryIdMillis: Long?
        get() = store.getString(KEY_LAST_STORY_ID_MILLIS)?.toLongOrNull()
        set(value) = store.putString(KEY_LAST_STORY_ID_MILLIS, value?.toString())

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
     * What the app shows of each source (see [ShownSources]). Before Oct 21, 2026 it was only the Moments tab's
     * filter bar (`feed_sources`): its Camera and Other choices are kept, and WhatsApp and screenshots start off.
     */
    var shownSources: ShownSources
        get() {
            val sources = store.getString(KEY_SHOWN_SOURCES)?.let(::sourcesOf)
                ?: store.getString(KEY_FEED_SOURCES)?.let { old -> sourcesOf(old) - setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS) }
                ?: ShownSources.DEFAULT_SOURCES
            val alwaysShown = store.getString(KEY_ALWAYS_SHOWN_DAYS)?.split(",")?.mapNotNull { entry ->
                val (day, source) = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                DaySource(LocalDay.parse(day) ?: return@mapNotNull null, MediaSource.entries.firstOrNull { it.name == source } ?: return@mapNotNull null)
            }?.toSet().orEmpty()
            return ShownSources(sources, store.getString(KEY_SAY_WHATS_HIDDEN) != "false", alwaysShown)
        }
        set(value) {
            store.putString(KEY_SHOWN_SOURCES, value.sources.joinToString(",") { it.name })
            store.putString(KEY_FEED_SOURCES, null)
            store.putString(KEY_SAY_WHATS_HIDDEN, value.sayWhatsHidden.toString())
            store.putString(KEY_ALWAYS_SHOWN_DAYS, value.alwaysShown.joinToString(",") { "${it.day}:${it.source.name}" }.ifEmpty { null })
        }

    private fun sourcesOf(stored: String): Set<MediaSource> =
        stored.split(",").mapNotNull { name -> MediaSource.entries.firstOrNull { it.name == name } }.toSet()

    /** What uploads to the server and when (Settings → Backup). */
    var backup: BackupSettings
        get() = BackupSettings(
            enabled = store.getString(KEY_BACKUP_ENABLED) == "true",
            sources = store.getString(KEY_BACKUP_SOURCES)
                ?.split(",")
                ?.mapNotNull { name -> MediaSource.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: BackupSettings.DEFAULT_SOURCES,
            wifiOnly = store.getString(KEY_BACKUP_WIFI_ONLY) != "false",
            // One per line: network names may contain commas.
            wifiNetworks = store.getString(KEY_BACKUP_WIFI_NETWORKS)?.split("\n")?.filter { it.isNotEmpty() }?.toSet().orEmpty(),
            chargingOnly = store.getString(KEY_BACKUP_CHARGING_ONLY) == "true",
            order = store.getString(KEY_BACKUP_ORDER)?.let { name -> UploadOrder.entries.firstOrNull { it.name == name } }
                ?: UploadOrder.OLDEST_FIRST,
        )
        set(value) {
            store.putString(KEY_BACKUP_ENABLED, value.enabled.toString())
            store.putString(KEY_BACKUP_SOURCES, value.sources.joinToString(",") { it.name })
            store.putString(KEY_BACKUP_WIFI_ONLY, value.wifiOnly.toString())
            store.putString(KEY_BACKUP_WIFI_NETWORKS, value.wifiNetworks.joinToString("\n").ifEmpty { null })
            store.putString(KEY_BACKUP_CHARGING_ONLY, value.chargingOnly.toString())
            store.putString(KEY_BACKUP_ORDER, value.order.name)
        }

    /** How much the previews of server photos may take up on this device, in GB (Settings → Storage on this phone). */
    var previewCacheGigabytes: Int
        get() = store.getString(KEY_PREVIEW_CACHE_GB)?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PREVIEW_CACHE_GB
        set(value) = store.putString(KEY_PREVIEW_CACHE_GB, value.toString())

    /** The sources whose photos "Free up space" may take off the phone (all by default). */
    var freeUpSources: Set<MediaSource>
        get() = store.getString(KEY_FREE_UP_SOURCES)
            ?.split(",")
            ?.mapNotNull { name -> MediaSource.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: MediaSource.entries.toSet()
        set(value) = store.putString(KEY_FREE_UP_SOURCES, value.joinToString(",") { it.name })

    /** How server photos are shown: previews (format, quality) or their originals (Settings → Storage on this phone). */
    var previewOptions: PreviewOptions
        get() = PreviewOptions(
            enabled = store.getString(KEY_PREVIEWS_ENABLED) != "false",
            format = store.getString(KEY_PREVIEW_FORMAT)?.let { name -> PreviewFormat.entries.firstOrNull { it.name == name } }
                ?: PreviewFormat.WEBP,
            quality = store.getString(KEY_PREVIEW_QUALITY)?.let { name -> PreviewQuality.entries.firstOrNull { it.name == name } }
                ?: PreviewQuality.HIGH,
        )
        set(value) {
            store.putString(KEY_PREVIEWS_ENABLED, value.enabled.toString())
            store.putString(KEY_PREVIEW_FORMAT, value.format.name)
            store.putString(KEY_PREVIEW_QUALITY, value.quality.name)
        }

    /** "Show private media" and its threshold (Settings → Private media); off by default. */
    var privateMedia: PrivateMedia
        get() = PrivateMedia(
            show = store.getString(KEY_SHOW_PRIVATE_MEDIA) == "true",
            thresholdPercent = store.getString(KEY_PRIVATE_THRESHOLD_PERCENT)?.toIntOrNull()?.takeIf { it in PrivateMedia.RANGE }
                ?: PrivateMedia.DEFAULT_THRESHOLD_PERCENT,
        )
        set(value) {
            store.putString(KEY_SHOW_PRIVATE_MEDIA, value.show.toString())
            store.putString(KEY_PRIVATE_THRESHOLD_PERCENT, value.thresholdPercent.toString())
        }

    /** "Show the phone badge" (Settings → Badges): off by default. */
    var showOnPhoneBadge: Boolean
        get() = store.getString(KEY_SHOW_ON_PHONE_BADGE) == "true"
        set(value) = store.putString(KEY_SHOW_ON_PHONE_BADGE, value.toString())

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
        const val DEFAULT_SERVER_URL = "https://journal.example.com:56398/query"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IDENTITY_ALIAS = "keychain_alias"
        private const val KEY_PHOTO_ACCESS_REQUESTED = "photo_access_requested"
        private const val KEY_LAST_STORY_ID_MILLIS = "last_story_id_millis"
        private const val KEY_GROUPING_MODE = "grouping_mode"
        private const val KEY_GROUPING_GAP_HOURS = "grouping_gap_hours"
        private const val KEY_SIMILARITY_PERCENT = "similarity_percent"
        private const val KEY_FEED_SOURCES = "feed_sources"
        private const val KEY_SHOWN_SOURCES = "shown_sources"
        private const val KEY_SAY_WHATS_HIDDEN = "say_whats_hidden"
        private const val KEY_ALWAYS_SHOWN_DAYS = "always_shown_days"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"
        private const val KEY_BACKUP_SOURCES = "backup_sources"
        private const val KEY_BACKUP_WIFI_ONLY = "backup_wifi_only"
        private const val KEY_BACKUP_WIFI_NETWORKS = "backup_wifi_networks"
        private const val KEY_BACKUP_CHARGING_ONLY = "backup_charging_only"
        private const val KEY_BACKUP_ORDER = "backup_order"
        private const val KEY_PREVIEW_CACHE_GB = "preview_cache_gb"
        private const val KEY_FREE_UP_RULE = "free_up_rule"
        private const val KEY_FREE_UP_SOURCES = "free_up_sources"
        private const val KEY_PREVIEWS_ENABLED = "previews_enabled"
        private const val KEY_PREVIEW_FORMAT = "preview_format"
        private const val KEY_PREVIEW_QUALITY = "preview_quality"
        private const val KEY_SHOW_PRIVATE_MEDIA = "show_private_media"
        private const val KEY_PRIVATE_THRESHOLD_PERCENT = "private_threshold_percent"
        private const val KEY_SHOW_ON_PHONE_BADGE = "show_on_phone_badge"
    }
}
