package com.chencohensolution.journal.settings

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

    /** How the journal splits unsorted photos into rows; see [TimelineGrouping]. */
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

    companion object {
        const val DEFAULT_SERVER_URL = "https://192.168.50.150:8080/query"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IDENTITY_ALIAS = "keychain_alias"
        private const val KEY_PHOTO_ACCESS_REQUESTED = "photo_access_requested"
        private const val KEY_LAST_STORY_ID_MILLIS = "last_story_id_millis"
        private const val KEY_GROUPING_MODE = "grouping_mode"
        private const val KEY_GROUPING_GAP_HOURS = "grouping_gap_hours"
    }
}
