package app.overbay.twitter.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Synchronous SharedPreferences-backed repository for [TwitterModConfig].
 *
 * Synchronous cold-start reads guarantee the active config JSON is available before
 * WebViewCompat registers DOCUMENT_START scripts and loads https://x.com.
 */
class TwitterConfigRepository(
    private val prefs: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    private val _config = MutableStateFlow(loadInitial())
    val config: StateFlow<TwitterModConfig> = _config.asStateFlow()

    private fun loadInitial(): TwitterModConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return TwitterModConfig()
        return runCatching { json.decodeFromString<TwitterModConfig>(raw) }
            .getOrDefault(TwitterModConfig())
    }

    fun updateConfig(transform: (TwitterModConfig) -> TwitterModConfig) {
        val updated = transform(_config.value)
        if (updated == _config.value) return
        _config.value = updated
        persist(updated)
    }

    fun addMutePattern(rawPattern: String): Boolean {
        val cleaned = rawPattern.trim()
        if (cleaned.isEmpty()) return false
        val current = _config.value.mutedPatterns
        if (current.any { it.equals(cleaned, ignoreCase = true) }) return false
        updateConfig { it.copy(mutedPatterns = current + cleaned) }
        return true
    }

    fun removeMutePattern(pattern: String) {
        updateConfig { current ->
            current.copy(mutedPatterns = current.mutedPatterns.filterNot { it == pattern })
        }
    }

    fun resetDefaults() {
        val defaults = TwitterModConfig()
        _config.value = defaults
        persist(defaults)
    }

    fun toJsonString(value: TwitterModConfig = _config.value): String {
        return json.encodeToString(value)
    }

    private fun persist(value: TwitterModConfig) {
        prefs.edit().putString(KEY_CONFIG_JSON, json.encodeToString(value)).apply()
    }

    companion object {
        private const val PREFS_NAME = "twitter_mod_prefs"
        private const val KEY_CONFIG_JSON = "config_json"

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
