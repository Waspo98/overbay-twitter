package app.overbay.twitter.model

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray

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

    private val _detectedHomeFeeds = MutableStateFlow(loadDetectedFeeds())
    val detectedHomeFeeds: StateFlow<List<DetectedHomeFeed>> = _detectedHomeFeeds.asStateFlow()

    private fun loadInitial(): TwitterModConfig {
        val raw = prefs.getString(KEY_CONFIG_JSON, null) ?: return TwitterModConfig()
        return decodeConfigJson(raw)
    }

    private fun loadDetectedFeeds(): List<DetectedHomeFeed> {
        val raw = prefs.getString(KEY_DETECTED_FEEDS_JSON, null) ?: return DetectedHomeFeed.DEFAULTS
        val decoded = runCatching { json.decodeFromString<List<DetectedHomeFeed>>(raw) }.getOrNull()
        return DetectedHomeFeed.normalizeList(decoded.orEmpty())
    }

    fun updateConfig(transform: (TwitterModConfig) -> TwitterModConfig) {
        val updated = transform(_config.value)
        if (updated == _config.value) return
        _config.value = updated
        persist(updated)
    }

    fun updateDetectedHomeFeedsFromJson(rawJson: String) {
        val parsed = runCatching {
            val arr = JSONArray(rawJson)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val kind = when (obj.optString("kind", "").lowercase()) {
                        "core" -> HomeFeedKind.CORE
                        "list" -> HomeFeedKind.LIST
                        else -> HomeFeedKind.SUGGESTED
                    }
                    add(DetectedHomeFeed(name, kind))
                }
            }
        }.getOrNull() ?: return
        val normalized = DetectedHomeFeed.normalizeList(parsed)
        if (normalized == _detectedHomeFeeds.value) return
        _detectedHomeFeeds.value = normalized
        prefs.edit { putString(KEY_DETECTED_FEEDS_JSON, json.encodeToString(normalized)) }
    }

    fun toggleHomeFeedVisibility(feed: DetectedHomeFeed, visible: Boolean) {
        updateConfig { current ->
            val name = feed.name.trim()
            val hiddenWithout = current.hiddenHomeFeeds.filterNot { it.equals(name, ignoreCase = true) }
            val pinnedWithout = current.pinnedHomeFeeds.filterNot { it.equals(name, ignoreCase = true) }
            val updated = if (visible) {
                val nextPinned = if (feed.kind == HomeFeedKind.SUGGESTED) pinnedWithout + name else pinnedWithout
                current.copy(hiddenHomeFeeds = hiddenWithout, pinnedHomeFeeds = nextPinned)
            } else {
                current.copy(hiddenHomeFeeds = hiddenWithout + name, pinnedHomeFeeds = pinnedWithout)
            }
            if (!visible && updated.defaultHomeFeed.equals(name, ignoreCase = true)) {
                val fallback = updated.orderedHomeFeeds(_detectedHomeFeeds.value)
                    .firstOrNull { updated.isHomeFeedVisible(it) }?.name ?: updated.defaultHomeFeed
                updated.copy(defaultHomeFeed = fallback)
            } else {
                updated
            }
        }
    }

    fun moveHomeFeed(feedName: String, delta: Int) {
        if (delta == 0) return
        updateConfig { current ->
            val ordered = current.orderedHomeFeeds(_detectedHomeFeeds.value).map { it.name }.toMutableList()
            val idx = ordered.indexOfFirst { it.equals(feedName, ignoreCase = true) }
            val targetIdx = idx + delta
            if (idx < 0 || targetIdx !in ordered.indices) return@updateConfig current
            val moved = ordered.removeAt(idx)
            ordered.add(targetIdx, moved)
            current.copy(homeFeedOrder = ordered)
        }
    }

    fun setDefaultHomeFeed(feedName: String) {
        updateConfig { current ->
            val trimmed = feedName.trim()
            if (trimmed.isEmpty()) return@updateConfig current
            val targetFeed = _detectedHomeFeeds.value.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            val nextHidden = current.hiddenHomeFeeds.filterNot { it.equals(trimmed, ignoreCase = true) }
            val nextPinned = if (targetFeed?.kind == HomeFeedKind.SUGGESTED &&
                current.pinnedHomeFeeds.none { it.equals(trimmed, ignoreCase = true) }
            ) {
                current.pinnedHomeFeeds + trimmed
            } else {
                current.pinnedHomeFeeds
            }
            current.copy(
                defaultHomeFeed = trimmed,
                hiddenHomeFeeds = nextHidden,
                pinnedHomeFeeds = nextPinned
            )
        }
    }

    fun addMutePattern(rawPattern: String): Boolean {
        val cleaned = rawPattern.trim()
        if (TwitterModConfig.validateMutePattern(cleaned) != null) return false
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
        if (_config.value == defaults) return
        _config.value = defaults
        persist(defaults)
    }

    fun toJsonString(value: TwitterModConfig = _config.value): String {
        return json.encodeToString(value)
    }

    private fun persist(value: TwitterModConfig) {
        prefs.edit { putString(KEY_CONFIG_JSON, json.encodeToString(value)) }
    }

    companion object {
        private const val PREFS_NAME = "twitter_mod_prefs"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val KEY_DETECTED_FEEDS_JSON = "detected_home_feeds_json"

        @Volatile
        private var instance: TwitterConfigRepository? = null

        fun getInstance(context: Context): TwitterConfigRepository {
            return instance ?: synchronized(this) {
                instance ?: TwitterConfigRepository(context.applicationContext).also { instance = it }
            }
        }

        internal val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        internal fun decodeConfigJson(raw: String): TwitterModConfig {
            return runCatching {
                val parsed = json.decodeFromString<TwitterModConfig>(raw)
                val obj = json.parseToJsonElement(raw) as? JsonObject ?: return@runCatching parsed
                val legacyHideForYou = obj["hideForYouTab"]?.jsonPrimitive?.booleanOrNull == true
                if (legacyHideForYou && "hiddenHomeFeeds" !in obj) {
                    val hidden = parsed.hiddenHomeFeeds.ifEmpty { listOf("For you") }
                    val fallbackFeed = if (parsed.defaultHomeFeed.equals("For you", ignoreCase = true)) {
                        "Following"
                    } else {
                        parsed.defaultHomeFeed
                    }
                    parsed.copy(hiddenHomeFeeds = hidden, defaultHomeFeed = fallbackFeed)
                } else {
                    parsed
                }
            }.getOrDefault(TwitterModConfig())
        }
    }
}
