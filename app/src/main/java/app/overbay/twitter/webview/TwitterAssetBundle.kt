package app.overbay.twitter.webview

import android.content.Context
import org.json.JSONObject

/**
 * Process-cached bundle of injected JS and CSS assets from `app/src/main/assets/`.
 *
 * Static CSS and JS engines are loaded once per process so recompositions and WebView
 * crash recoveries never re-read APK assets on the main thread.
 */
class TwitterAssetBundle private constructor(
    interceptorJs: String,
    themeJs: String,
    featuresJs: String,
    viewportJs: String,
    domJs: String,
    quotedCss: String
) {
    val coreEngineScript: String = buildString(
        BRIDGE_SHIM_JS.length + quotedCss.length + interceptorJs.length + themeJs.length +
            featuresJs.length + viewportJs.length + domJs.length + 128
    ) {
        append("window.__TWITTER_CLEAN_CSS__ = ")
        append(quotedCss)
        append(";\n")
        append(BRIDGE_SHIM_JS)
        append("\n;\n")
        append(interceptorJs)
        append("\n;\n")
        append(themeJs)
        append("\n;\n")
        append(featuresJs)
        append("\n;\n")
        append(viewportJs)
        append("\n;\n")
        append(domJs)
    }

    fun buildConfigSeedScript(
        configJson: String,
        isSystemDark: Boolean = false,
        restoreStateJson: String? = null
    ): String {
        val extraLen = restoreStateJson?.length ?: 0
        return buildString(configJson.length + extraLen + 240) {
            if (!restoreStateJson.isNullOrBlank()) {
                append("window.__TWITTER_RESTORE_STATE__ = ")
                append(restoreStateJson)
                append(";\n")
            }
            append("window.__TWITTER_SYSTEM_DARK__ = ")
            append(isSystemDark)
            append(";\nwindow.__TWITTER_CONFIG__ = ")
            append(configJson)
            append(";\nif (typeof window.__applyTwitterConfig === 'function') { window.__applyTwitterConfig(window.__TWITTER_CONFIG__, ")
            append(isSystemDark)
            append("); }\n")
        }
    }

    companion object {
        @Volatile
        private var cached: TwitterAssetBundle? = null

        fun getInstance(context: Context): TwitterAssetBundle {
            return cached ?: synchronized(this) {
                cached ?: loadFromAssets(context.applicationContext).also { cached = it }
            }
        }

        private fun loadFromAssets(context: Context): TwitterAssetBundle {
            val interceptorJs = readAsset(context, "twitter-interceptor.js")
            val themeJs = readAsset(context, "twitter-theme.js")
            val featuresJs = readAsset(context, "twitter-features.js")
            val viewportJs = readAsset(context, "twitter-viewport.js")
            val domJs = readAsset(context, "twitter-dom.js")
            val quotedCss = JSONObject.quote(readAsset(context, "twitter-clean.css"))
            return TwitterAssetBundle(interceptorJs, themeJs, featuresJs, viewportJs, domJs, quotedCss)
        }

        private fun readAsset(context: Context, name: String): String {
            return context.assets.open(name).bufferedReader().use { it.readText() }
        }

        internal const val NATIVE_PORT_NAME = "__twNativePort"

        private val BRIDGE_SHIM_JS = """
            (() => {
                if (window.TwitterBridge) return;
                const post = (payload) => {
                    try { window.$NATIVE_PORT_NAME?.postMessage(JSON.stringify(payload)); } catch (_) {}
                };
                window.TwitterBridge = {
                    openSettings: () => post({ type: "openSettings", haptic: false }),
                    openSettingsWithHaptic: () => post({ type: "openSettings", haptic: true }),
                    downloadMedia: (url, filenameHint) => post({ type: "downloadMedia", url: String(url || ""), filenameHint: String(filenameHint || "") }),
                    showLinkActions: (url) => post({ type: "showLinkActions", url: String(url || "") }),
                    showToast: (message) => post({ type: "showToast", message: String(message || "") }),
                    setPullToRefreshModalOpen: (open) => post({ type: "setPullToRefreshModalOpen", open: Boolean(open) }),
                    reportHomeFeeds: (feedsJson) => post({ type: "reportHomeFeeds", feedsJson: String(feedsJson || "") }),
                    onThemeColorChanged: (hexColor) => post({ type: "onThemeColorChanged", hexColor: String(hexColor || "") }),
                    setThemeMode: (mode) => post({ type: "setThemeMode", mode: String(mode || "") }),
                    setAutoDarkVariant: (variant) => post({ type: "setAutoDarkVariant", variant: String(variant || "") }),
                    reportStatsDelta: (ads, clutter) => post({ type: "reportStatsDelta", ads: Number(ads) || 0, clutter: Number(clutter) || 0 })
                };
            })();
        """.trimIndent()
    }
}
