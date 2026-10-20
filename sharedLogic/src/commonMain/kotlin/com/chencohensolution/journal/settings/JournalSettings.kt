package com.chencohensolution.journal.settings

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.MomentsFeed

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

    /** The sources the Moments tab shows (its filter bar). */
    var feedSources: Set<MediaSource>
        get() = store.getString(KEY_FEED_SOURCES)
            ?.split(",")
            ?.mapNotNull { name -> MediaSource.entries.firstOrNull { it.name == name } }
            ?.toSet()
            ?: MomentsFeed.DEFAULT_SOURCES
        set(value) = store.putString(KEY_FEED_SOURCES, value.joinToString(",") { it.name })

    /** Trips the user answered "Not now" to, by key ("trip-2026-10-04"). */
    var dismissedTrips: Set<String>
        get() = store.getString(KEY_DISMISSED_TRIPS)?.split(",")?.filter { it.isNotEmpty() }?.toSet().orEmpty()
        set(value) = store.putString(KEY_DISMISSED_TRIPS, value.joinToString(",").ifEmpty { null })

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

    companion object {
        const val DEFAULT_SERVER_URL = "https://192.168.50.2:8080/query"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IDENTITY_ALIAS = "keychain_alias"
        private const val KEY_PHOTO_ACCESS_REQUESTED = "photo_access_requested"
        private const val KEY_LAST_STORY_ID_MILLIS = "last_story_id_millis"
        private const val KEY_GROUPING_MODE = "grouping_mode"
        private const val KEY_GROUPING_GAP_HOURS = "grouping_gap_hours"
        private const val KEY_SIMILARITY_PERCENT = "similarity_percent"
        private const val KEY_FEED_SOURCES = "feed_sources"
        private const val KEY_DISMISSED_TRIPS = "dismissed_trips"
        private const val KEY_BACKUP_ENABLED = "backup_enabled"
        private const val KEY_BACKUP_SOURCES = "backup_sources"
        private const val KEY_BACKUP_WIFI_ONLY = "backup_wifi_only"
        private const val KEY_BACKUP_WIFI_NETWORKS = "backup_wifi_networks"
        private const val KEY_BACKUP_CHARGING_ONLY = "backup_charging_only"
        private const val KEY_BACKUP_ORDER = "backup_order"
    }
}
