package app.overbay.twitter.webview

import android.content.Context
import org.json.JSONObject

/**
 * Loads and caches the injected JS and CSS assets from `app/src/main/assets/`.
 *
 * Static CSS is bundled once into [coreEngineScript] so live config hot-reloads
 * only transmit the compact JSON state payload.
 */
class TwitterAssetBundle(context: Context) {

    private val interceptorJs: String = readAsset(context, "twitter-interceptor.js")
    private val domJs: String = readAsset(context, "twitter-dom.js")
    private val quotedCss: String = JSONObject.quote(readAsset(context, "twitter-clean.css"))

    val coreEngineScript: String = buildString(
        quotedCss.length + interceptorJs.length + domJs.length + 64
    ) {
        append("window.__TWITTER_CLEAN_CSS__ = ")
        append(quotedCss)
        append(";\n")
        append(interceptorJs)
        append("\n;\n")
        append(domJs)
    }

    fun buildConfigSeedScript(configJson: String, isFullyUnfolded: Boolean): String {
        return buildString(configJson.length + 180) {
            append("window.__TWITTER_IS_UNFOLDED__ = ")
            append(isFullyUnfolded)
            append(";\nwindow.__TWITTER_CONFIG__ = ")
            append(configJson)
            append(";\nif (typeof window.__applyTwitterConfig === 'function') { window.__applyTwitterConfig(window.__TWITTER_CONFIG__, ")
            append(isFullyUnfolded)
            append("); }\n")
        }
    }

    private fun readAsset(context: Context, name: String): String {
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }
}
