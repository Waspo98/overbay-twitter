package app.overbay.twitter

import android.content.SharedPreferences
import app.overbay.twitter.bridge.TwitterNativeBridge
import app.overbay.twitter.model.SessionFilterStats
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.webview.TwitterRequestBlocker
import app.overbay.twitter.webview.isPullRefreshBlockedPath
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwitterWebEngineTest {

    @Test
    fun surfaceThemeClassifiesLightDimAndLightsOutAccurately() {
        assertEquals(TwitterSurfaceTheme.LIGHT, TwitterSurfaceTheme.fromColorInt(0xFFFFFFFF.toInt()))
        assertEquals(TwitterSurfaceTheme.LIGHT, TwitterSurfaceTheme.fromColorInt(0xFFF7F9F9.toInt()))
        assertTrue(TwitterSurfaceTheme.fromColorInt(0xFFFFFFFF.toInt()).isLight)

        assertEquals(TwitterSurfaceTheme.DIM, TwitterSurfaceTheme.fromColorInt(0xFF15202B.toInt()))
        assertFalse(TwitterSurfaceTheme.fromColorInt(0xFF15202B.toInt()).isLight)

        assertEquals(TwitterSurfaceTheme.LIGHTS_OUT, TwitterSurfaceTheme.fromColorInt(0xFF000000.toInt()))
        assertEquals(TwitterSurfaceTheme.LIGHTS_OUT, TwitterSurfaceTheme.fromColorInt(0xFF0F1419.toInt()))
    }

    @Test
    fun repositoryPersistsLastDetectedThemeColorAcrossInstances() {
        val prefs = InMemoryPrefs()
        val repo1 = TwitterConfigRepository(prefs)
        assertNull(repo1.lastThemeColor.value)

        val dim = 0xFF15202B.toInt()
        repo1.updateLastThemeColor(dim)
        assertEquals(dim, repo1.lastThemeColor.value)

        val repo2 = TwitterConfigRepository(prefs)
        assertEquals(dim, repo2.lastThemeColor.value)
    }

    @Test
    fun requestBlockerValidatesBridgeOriginsAndTelemetryJsonPaths() {
        assertTrue(TwitterRequestBlocker.isAllowedOriginUrl("https://x.com"))
        assertTrue(TwitterRequestBlocker.isAllowedOriginUrl("https://mobile.twitter.com"))
        assertTrue(TwitterRequestBlocker.isAllowedOriginUrl("https://x.com:443"))
        assertFalse(TwitterRequestBlocker.isAllowedOriginUrl("http://x.com"))
        assertFalse(TwitterRequestBlocker.isAllowedOriginUrl("https://ads.twitter.com"))
        assertFalse(TwitterRequestBlocker.isAllowedOriginUrl("https://evilx.com"))
        assertFalse(TwitterRequestBlocker.isAllowedOriginUrl("https://youtube.com"))
        assertFalse(TwitterRequestBlocker.isAllowedOriginUrl(null))

        assertTrue(TwitterRequestBlocker.isJsonTelemetryUrl("https://x.com/i/api/1.1/jot/client_event.json"))
        assertTrue(TwitterRequestBlocker.isJsonTelemetryUrl("https://x.com/i/api/graphql/abc/ClientEvent"))
        assertFalse(TwitterRequestBlocker.isJsonTelemetryUrl("https://analytics.twitter.com/i/adsct"))
    }

    @Test
    fun nativeBridgeParsesHexColorsSafely() {
        assertEquals(0xFF15202B.toInt(), TwitterNativeBridge.parseHexColor("#15202B"))
        assertEquals(0xFFFFFFFF.toInt(), TwitterNativeBridge.parseHexColor("#FFFFFF"))
        assertEquals(0xFF000000.toInt(), TwitterNativeBridge.parseHexColor("#000000"))
        assertNull(TwitterNativeBridge.parseHexColor(""))
        assertNull(TwitterNativeBridge.parseHexColor("15202B"))
        assertNull(TwitterNativeBridge.parseHexColor("#FFF"))
        assertNull(TwitterNativeBridge.parseHexColor("#ZZZZZZ"))
    }

    @Test
    fun pullRefreshBlockedPathsAndReloadRevertSemanticsWork() {
        assertTrue(isPullRefreshBlockedPath("https://x.com/messages/123"))
        assertTrue(isPullRefreshBlockedPath("https://x.com/i/chat/123"))
        assertTrue(isPullRefreshBlockedPath("https://x.com/compose/post"))
        assertFalse(isPullRefreshBlockedPath("https://x.com/home"))
        assertFalse(isPullRefreshBlockedPath("https://x.com/user/status/42"))

        val applied = TwitterModConfig()
        val toggled = applied.copy(hideRetweets = true)
        assertTrue(toggled.requiresReloadComparedTo(applied))
        assertFalse(toggled.copy(hideRetweets = false).requiresReloadComparedTo(applied))

        val stats = SessionFilterStats(adsBlocked = 9, clutterBlocked = 4)
        assertEquals(13, stats.totalBlocked)
    }

    @Test
    fun webAssetsRespectLineCeilingsAndCoreEngineContracts() {
        val assetsDir = resolveAssetsDir()
        val interceptor = File(assetsDir, "twitter-interceptor.js").readText()
        val features = File(assetsDir, "twitter-features.js").readText()
        val dom = File(assetsDir, "twitter-dom.js").readText()
        val css = File(assetsDir, "twitter-clean.css").readText()

        assertTrue("twitter-interceptor.js exceeds 450 lines", interceptor.lines().size <= 450)
        assertTrue("twitter-features.js exceeds 450 lines", features.lines().size <= 450)
        assertTrue("twitter-dom.js exceeds 450 lines", dom.lines().size <= 450)
        assertTrue("twitter-clean.css exceeds 450 lines", css.lines().size <= 450)

        // GraphQL interceptor contracts
        assertTrue(interceptor.contains("focalCache"))
        assertTrue(interceptor.contains("indexUserNode"))
        assertTrue(interceptor.contains("reportStatsDelta"))
        assertTrue(interceptor.contains("url.includes(\"Bookmarks\")"))
        assertTrue(interceptor.contains("raw.includes(\"video_info\")"))
        assertFalse(interceptor.contains("videoListCache"))
        assertFalse(interceptor.contains("__TW_STATS__"))

        // Feature engine contracts
        assertTrue(features.contains("window.__TW_FEATURES__"))
        assertTrue(features.contains("getCfg().disableVideoAutoplay"))
        assertFalse(features.contains("aria-labelledby=\"modal-header\""))
        assertTrue(features.contains("[data-testid=\"UserName\"]"))

        // DOM engine contracts
        assertTrue(dom.contains("[role='menuitem'] span"))
        assertTrue(dom.contains("Math.hypot"))
        assertTrue(dom.contains("delete svg.dataset.twLogoChecked"))
        assertFalse(dom.contains("openSettingsWithHaptic?.() ??"))

        // CSS contracts
        assertTrue(css.contains("removeBookmark"))
        assertTrue(css.contains("FloatingActionButtons_Tweet_Button"))
        assertTrue(css.contains("[role=\"group\"] > div:has(> a[href*=\"/analytics\"])"))
    }

    private fun resolveAssetsDir(): File {
        val direct = File("src/main/assets")
        if (direct.isDirectory) return direct
        val fromRoot = File("app/src/main/assets")
        if (fromRoot.isDirectory) return fromRoot
        throw AssertionError("Could not locate app/src/main/assets from ${File(".").absolutePath}")
    }

    private class InMemoryPrefs : SharedPreferences {
        private val data = LinkedHashMap<String, Any?>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(data)
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { clearAll = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clearAll) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            }
        }
    }
}
