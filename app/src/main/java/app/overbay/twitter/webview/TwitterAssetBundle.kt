package app.overbay.twitter.webview

import android.content.Context
import org.json.JSONObject

/**
 * Loads and caches the injected JS and CSS assets from `app/src/main/assets/`.
 */
class TwitterAssetBundle(context: Context) {

    private val interceptorJs: String = readAsset(context, "twitter-interceptor.js")
    private val domJs: String = readAsset(context, "twitter-dom.js")
    private val cleanCss: String = readAsset(context, "twitter-clean.css")
    private val quotedCss: String = JSONObject.quote(cleanCss)

    val coreEngineScript: String = buildString {
        append(interceptorJs)
        append("\n;\n")
        append(domJs)
    }

    fun buildConfigSeedScript(configJson: String): String {
        return buildString {
            append("window.__TWITTER_CLEAN_CSS__ = ")
            append(quotedCss)
            append(";\n")
            append("window.__TWITTER_CONFIG__ = ")
            append(configJson)
            append(";\n")
            append("if (typeof window.__applyTwitterConfig === 'function') { window.__applyTwitterConfig(")
            append(configJson)
            append("); }\n")
        }
    }

    private fun readAsset(context: Context, name: String): String {
        return context.assets.open(name).bufferedReader().use { it.readText() }
    }
}
