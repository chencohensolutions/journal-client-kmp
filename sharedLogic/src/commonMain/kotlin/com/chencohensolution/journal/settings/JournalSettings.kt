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

    companion object {
        const val DEFAULT_SERVER_URL = "https://192.168.50.2:8080/graphql"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_IDENTITY_ALIAS = "keychain_alias"
    }
}
