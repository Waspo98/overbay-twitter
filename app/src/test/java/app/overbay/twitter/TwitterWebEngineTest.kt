package app.overbay.twitter

import android.content.SharedPreferences
import app.overbay.twitter.bridge.TwitterNativeBridge
import app.overbay.twitter.model.SessionFilterStats
import app.overbay.twitter.model.TwitterConfigRepository
import app.overbay.twitter.model.TwitterDarkVariant
import app.overbay.twitter.model.TwitterModConfig
import app.overbay.twitter.model.TwitterThemeMode
import app.overbay.twitter.ui.computeUncoveredNavBottomDp
import app.overbay.twitter.ui.computeUncoveredTopInsetDp
import app.overbay.twitter.ui.theme.TwitterSurfaceTheme
import app.overbay.twitter.ui.theme.resolveInitialColorInt
import app.overbay.twitter.webview.DisplayLayoutState
import app.overbay.twitter.webview.FoldableLayoutPolicy
import app.overbay.twitter.webview.TwitterAssetBundle
import app.overbay.twitter.webview.TwitterRequestBlocker
import app.overbay.twitter.webview.TwitterWebController
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
    fun themeModeResolutionAndCookieSyncRespectDeviceLightAndDarkModes() {
        assertEquals(TwitterThemeMode.DIM, TwitterThemeMode.fromWireValue("dim"))
        assertEquals(TwitterThemeMode.LIGHTS_OUT, TwitterThemeMode.fromWireValue("LIGHTS_OUT"))
        assertEquals(TwitterThemeMode.AUTO, TwitterThemeMode.fromWireValue("unknown"))
        assertEquals(TwitterDarkVariant.DIM, TwitterDarkVariant.fromWireValue("dim"))
        assertEquals(TwitterDarkVariant.LIGHTS_OUT, TwitterDarkVariant.fromWireValue("unknown"))

        // Auto resolves light in day mode and honors autoDarkVariant (Dim vs Lights Out) in night mode
        val dimColor = TwitterSurfaceTheme.DIM.backgroundColorInt
        val blackColor = TwitterSurfaceTheme.LIGHTS_OUT.backgroundColorInt
        val lightColor = TwitterSurfaceTheme.LIGHT.backgroundColorInt
        assertEquals(
            lightColor,
            TwitterThemeMode.AUTO.resolveInitialColorInt(blackColor, isSystemDark = false, autoDarkVariant = TwitterDarkVariant.DIM)
        )
        assertEquals(
            dimColor,
            TwitterThemeMode.AUTO.resolveInitialColorInt(blackColor, isSystemDark = true, autoDarkVariant = TwitterDarkVariant.DIM)
        )
        assertEquals(
            blackColor,
            TwitterThemeMode.AUTO.resolveInitialColorInt(dimColor, isSystemDark = true, autoDarkVariant = TwitterDarkVariant.LIGHTS_OUT)
        )
        assertEquals(lightColor, TwitterThemeMode.LIGHT.resolveInitialColorInt(blackColor, isSystemDark = true))
        assertEquals(dimColor, TwitterThemeMode.DIM.resolveInitialColorInt(lightColor, isSystemDark = false))
        assertEquals(blackColor, TwitterThemeMode.LIGHTS_OUT.resolveInitialColorInt(lightColor, isSystemDark = false))

        assertEquals("1", TwitterWebController.extractNightModeCookie("auth_token=abc; night_mode=1; ct0=xyz"))
        assertNull(TwitterWebController.extractNightModeCookie("auth_token=abc; night_mode=9"))

        assertEquals("0", TwitterWebController.resolveTargetNightModeCookie(TwitterThemeMode.AUTO, isSystemDark = false))
        assertEquals("2", TwitterWebController.resolveTargetNightModeCookie(TwitterThemeMode.AUTO, isSystemDark = true))
        assertEquals("0", TwitterWebController.resolveTargetNightModeCookie(TwitterThemeMode.LIGHT, isSystemDark = true))
        assertEquals("2", TwitterWebController.resolveTargetNightModeCookie(TwitterThemeMode.DIM, isSystemDark = false))
        assertEquals("2", TwitterWebController.resolveTargetNightModeCookie(TwitterThemeMode.LIGHTS_OUT, isSystemDark = false))

        // Dim <-> Lights Out never needs reload; polarity flips trigger fallback reload only if live matchMedia didn't switch
        assertTrue(TwitterWebController.shouldReloadForThemeMode(TwitterThemeMode.AUTO, isSystemDark = false, currentSurface = TwitterSurfaceTheme.LIGHTS_OUT))
        assertFalse(TwitterWebController.shouldReloadForThemeMode(TwitterThemeMode.AUTO, isSystemDark = false, currentSurface = TwitterSurfaceTheme.LIGHT))
        assertFalse(TwitterWebController.shouldReloadForThemeMode(TwitterThemeMode.DIM, isSystemDark = true, currentSurface = TwitterSurfaceTheme.LIGHTS_OUT))
        assertFalse(TwitterWebController.shouldReloadForThemeMode(TwitterThemeMode.LIGHTS_OUT, isSystemDark = true, currentSurface = TwitterSurfaceTheme.DIM))
        assertTrue(TwitterWebController.shouldReloadForThemeMode(TwitterThemeMode.DIM, isSystemDark = false, currentSurface = TwitterSurfaceTheme.LIGHT))
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
        assertTrue(TwitterRequestBlocker.isJsonTelemetryUrl("https://x.com/i/api/2/client_event.json"))
        assertTrue(TwitterRequestBlocker.isJsonTelemetryUrl("https://x.com/i/api/1.1/branch/init.json"))
        assertTrue(TwitterRequestBlocker.isJsonTelemetryUrl("https://x.com/i/api/graphql/abc/ClientEvent"))
        assertFalse(TwitterRequestBlocker.isJsonTelemetryUrl("https://analytics.twitter.com/i/adsct"))

        assertTrue(TwitterRequestBlocker.shouldBlockGrokApi("https://x.com/i/api/graphql/abc123/GrokHome?variables={}"))
        assertTrue(TwitterRequestBlocker.shouldBlockGrokApi("https://x.com/i/api/graphql/xyz/GrokAnalyzePost"))
        assertTrue(TwitterRequestBlocker.shouldBlockGrokApi("https://x.com/i/api/2/grok/add_response.json"))
        assertFalse(TwitterRequestBlocker.shouldBlockGrokApi("https://x.com/i/api/graphql/xyz/SearchTimeline?variables=%7B%22rawQuery%22%3A%22grok%22%7D"))
        assertFalse(TwitterRequestBlocker.shouldBlockGrokApi("https://x.com/i/api/graphql/xyz/HomeTimeline"))
        assertFalse(TwitterRequestBlocker.shouldBlockGrokApi("https://example.com/i/api/graphql/xyz/GrokHome"))

        assertEquals(
            TwitterRequestBlocker.BlockDecision.ALLOW,
            TwitterRequestBlocker.classifyRequest("https://pbs.twimg.com/media/abc.jpg", blockAds = true, removeGrok = true)
        )
        assertEquals(
            TwitterRequestBlocker.BlockDecision.BLOCK_TELEMETRY,
            TwitterRequestBlocker.classifyRequest("https://x.com/i/api/1.1/branch/init.json", blockAds = true, removeGrok = true)
        )
        assertEquals(
            TwitterRequestBlocker.BlockDecision.BLOCK_GROK,
            TwitterRequestBlocker.classifyRequest("https://x.com/i/api/graphql/abc/GrokHome", blockAds = true, removeGrok = true)
        )
        assertEquals(
            TwitterRequestBlocker.BlockDecision.ALLOW,
            TwitterRequestBlocker.classifyRequest("https://x.com/i/api/graphql/abc/GrokHome", blockAds = true, removeGrok = false)
        )
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
    fun foldableLayoutPolicyClassifiesPosturesRotationsSplitScreenAndZoom() {
        val unfoldedPortrait = FoldableLayoutPolicy.resolve(690, 690, 420)
        val unfoldedLandscape = FoldableLayoutPolicy.resolve(841, 690, 420)
        val unfoldedZoomed = FoldableLayoutPolicy.resolve(560, 560, 480)
        assertTrue(unfoldedPortrait.isExpanded)
        assertTrue(unfoldedLandscape.isExpanded)
        assertTrue(unfoldedZoomed.isExpanded)
        // Rotating an unfolded foldable does not trigger a layout transition
        assertFalse(FoldableLayoutPolicy.requiresLayoutTransition(unfoldedPortrait, unfoldedLandscape))

        val foldedPortrait = FoldableLayoutPolicy.resolve(384, 384, 420)
        val foldedLandscape = FoldableLayoutPolicy.resolve(840, 384, 420)
        assertFalse(foldedPortrait.isExpanded)
        assertFalse(foldedLandscape.isExpanded)
        // Rotating a folded phone / slab phone for fullscreen video does not trigger a transition
        assertFalse(FoldableLayoutPolicy.requiresLayoutTransition(foldedPortrait, foldedLandscape))

        // Folding and unfolding trigger layout transitions
        assertTrue(FoldableLayoutPolicy.requiresLayoutTransition(unfoldedPortrait, foldedPortrait))
        assertTrue(FoldableLayoutPolicy.requiresLayoutTransition(foldedPortrait, unfoldedPortrait))

        // 50/50 split-screen on an unfolded foldable drops below 500dp and triggers compact layout
        val splitScreenHalf = FoldableLayoutPolicy.resolve(345, 690, 420)
        assertFalse(splitScreenHalf.isExpanded)
        assertTrue(FoldableLayoutPolicy.requiresLayoutTransition(unfoldedPortrait, splitScreenHalf))

        // Density change alone (e.g. cover display with higher DPI or Display Size toggle) triggers transition
        val higherDpiFolded = FoldableLayoutPolicy.resolve(384, 384, 480)
        assertTrue(FoldableLayoutPolicy.requiresLayoutTransition(foldedPortrait, higherDpiFolded))

        // Wide viewport expectation tracks actual viewport width (including folded landscape) & DPR
        assertTrue(FoldableLayoutPolicy.expectsWideViewport(840, fallbackExpanded = false))
        assertFalse(FoldableLayoutPolicy.expectsWideViewport(384, fallbackExpanded = true))
        assertTrue(FoldableLayoutPolicy.expectsWideViewport(0, fallbackExpanded = true))
        assertEquals(2.625f, FoldableLayoutPolicy.expectedDevicePixelRatio(420), 0.0001f)
        assertEquals(3.0f, FoldableLayoutPolicy.expectedDevicePixelRatio(480), 0.0001f)
        assertEquals(0f, FoldableLayoutPolicy.expectedDevicePixelRatio(0), 0.0001f)

        // Transient undefined Configuration dimensions preserve the prior fallback state safely
        val transientUndefined = FoldableLayoutPolicy.resolve(0, 0, 420, fallback = unfoldedPortrait)
        assertEquals(unfoldedPortrait, transientUndefined)
        assertEquals(
            DisplayLayoutState(isExpanded = false, densityDpi = 420),
            FoldableLayoutPolicy.resolve(0, 0, 420)
        )
    }

    @Test
    fun safeInternalUrlFiltersBlankAndExternalUrls() {
        assertTrue(TwitterWebController.isSafeInternalUrl("https://x.com/home"))
        assertTrue(TwitterWebController.isSafeInternalUrl("https://x.com/user/status/1234567890"))
        assertTrue(TwitterWebController.isSafeInternalUrl("https://mobile.twitter.com/i/flow/login"))
        assertTrue(TwitterWebController.isSafeInternalUrl("https://x.com:443/notifications?tab=mentions"))

        assertFalse(TwitterWebController.isSafeInternalUrl(null))
        assertFalse(TwitterWebController.isSafeInternalUrl(""))
        assertFalse(TwitterWebController.isSafeInternalUrl("   "))
        assertFalse(TwitterWebController.isSafeInternalUrl("about:blank"))
        assertFalse(TwitterWebController.isSafeInternalUrl("http://x.com/home"))
        assertFalse(TwitterWebController.isSafeInternalUrl("https://ads.twitter.com/pixel"))
        assertFalse(TwitterWebController.isSafeInternalUrl("https://example.com/status/1"))

        val locationJson = """{"url":"https:\/\/x.com\/user\/status\/998877","scrollY":420,"anchorTweetId":"998877","activeHomeTab":"Following"}"""
        assertEquals(locationJson, TwitterWebController.normalizeCapturedLocationJson(" $locationJson "))
        assertNull(TwitterWebController.normalizeCapturedLocationJson("null"))
        assertNull(TwitterWebController.normalizeCapturedLocationJson("\"not-an-object\""))
        assertEquals(
            "https://x.com/user/status/998877",
            TwitterWebController.extractUrlFromLocationJson(locationJson)
        )
        assertNull(
            TwitterWebController.extractUrlFromLocationJson("""{"url":"https://example.com/evil","scrollY":10}""")
        )

        val controller = TwitterWebController()
        controller.stageRestoreLocationJson(locationJson)
        assertEquals("https://x.com/user/status/998877", controller.lastKnownUrl)
        assertEquals(locationJson, controller.consumePendingRestoreStateJson())
        assertNull(controller.consumePendingRestoreStateJson())
    }

    @Test
    fun webAssetsRespectLineCeilingsAndCoreEngineContracts() {
        val assetsDir = resolveAssetsDir()
        val interceptor = File(assetsDir, "twitter-interceptor.js").readText()
        val theme = File(assetsDir, "twitter-theme.js").readText()
        val features = File(assetsDir, "twitter-features.js").readText()
        val chrome = File(assetsDir, "twitter-chrome.js").readText()
        val drawer = File(assetsDir, "twitter-drawer.js").readText()
        val viewport = File(assetsDir, "twitter-viewport.js").readText()
        val dom = File(assetsDir, "twitter-dom.js").readText()
        val css = File(assetsDir, "twitter-clean.css").readText()

        assertTrue("twitter-interceptor.js exceeds 450 lines", interceptor.lines().size <= 450)
        assertTrue("twitter-theme.js exceeds 450 lines", theme.lines().size <= 450)
        assertTrue("twitter-features.js exceeds 450 lines", features.lines().size <= 450)
        assertTrue("twitter-chrome.js exceeds 450 lines", chrome.lines().size <= 450)
        assertTrue("twitter-drawer.js exceeds 450 lines", drawer.lines().size <= 450)
        assertTrue("twitter-viewport.js exceeds 450 lines", viewport.lines().size <= 450)
        assertTrue("twitter-dom.js exceeds 450 lines", dom.lines().size <= 450)
        assertTrue("twitter-clean.css exceeds 450 lines", css.lines().size <= 450)

        // GraphQL interceptor contracts
        assertTrue(interceptor.contains("focalCache"))
        assertTrue(interceptor.contains("indexUserNode"))
        assertTrue(interceptor.contains("reportStatsDelta"))
        assertTrue(interceptor.contains("url.includes(\"Bookmarks\")"))
        assertTrue(interceptor.contains("raw.includes(\"video_info\")"))
        assertTrue(interceptor.contains("createLazyJsonResponse"))
        assertTrue(interceptor.contains("cleanResponseHeaders"))
        assertFalse(interceptor.contains("videoListCache"))
        assertFalse(interceptor.contains("__TW_STATS__"))
        val nonTimelineGqlPattern = Regex("""NON_TIMELINE_GQL_RE\s*=\s*/(.+)/i;""")
            .find(interceptor)?.groupValues?.get(1)
            ?: throw AssertionError("Missing NON_TIMELINE_GQL_RE in twitter-interceptor.js")
        val nonTimelineGqlRe = Regex(nonTimelineGqlPattern, RegexOption.IGNORE_CASE)
        assertTrue(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/FavoriteTweet"))
        assertTrue(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/CreateTweet"))
        assertTrue(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/GrokHome"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/Followers"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/Following"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/FollowersYouKnow"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/ExploreSidebar"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/MutedUsers"))
        assertFalse(nonTimelineGqlRe.containsMatchIn("https://x.com/i/api/graphql/abc/BlockedUsers"))

        // Theme engine contracts
        assertTrue(theme.contains("prefers-color-scheme"))
        assertTrue(theme.contains("__twNotifyColorSchemeChange"))
        assertTrue(theme.contains("isEffectiveDarkScheme"))
        assertTrue(theme.contains("syncSurfaceStyleSheet"))
        assertTrue(theme.contains("scannedCounts"))
        assertTrue(theme.contains("emittedSelectors"))
        assertTrue(theme.contains("currentCssParts"))
        assertTrue(theme.contains("syncDrawerThemeBars"))
        assertTrue(theme.contains("trackActiveDrawer"))
        assertTrue(theme.contains("setThemeMode"))
        assertTrue(theme.contains("setAutoDarkVariant"))

        // Feature engine contracts
        assertTrue(features.contains("window.__TW_FEATURES__"))
        assertTrue(features.contains("getCfg().disableVideoAutoplay"))
        assertFalse(features.contains("aria-labelledby=\"modal-header\""))
        assertTrue(features.contains("[data-testid=\"UserName\"]"))
        val nonProfilePattern = Regex("""NON_PROFILE_PATH_RE\s*=\s*/(.+)/i;""")
            .find(features)?.groupValues?.get(1)
            ?: throw AssertionError("Missing NON_PROFILE_PATH_RE in twitter-features.js")
        val nonProfileRe = Regex(nonProfilePattern, RegexOption.IGNORE_CASE)
        assertTrue(nonProfileRe.containsMatchIn("/home"))
        assertTrue(nonProfileRe.containsMatchIn("/i/bookmarks"))
        assertTrue(nonProfileRe.containsMatchIn("/i/lists/12345"))
        assertFalse(nonProfileRe.containsMatchIn("/jack"))
        assertFalse(nonProfileRe.containsMatchIn("/jack/status/123"))

        // Chrome insets, glass, auto-hide & drawer exit-ghost contracts
        assertTrue(chrome.contains("window.__TW_CHROME__"))
        assertTrue(chrome.contains("__twSetNavInsets"))
        assertFalse(chrome.contains("__twSetNavInsetBottom"))
        assertTrue(chrome.contains("syncNavInsets"))
        assertTrue(chrome.contains("syncTopInsets"))
        assertTrue(chrome.contains("ensureStatusBarGlass"))
        assertTrue(chrome.contains("tw-status-bar-glass"))
        assertTrue(chrome.contains("data-tw-top-bar-offset"))
        assertTrue(chrome.contains("data-tw-top-bar-autohide"))
        assertTrue(chrome.contains("hostChain"))
        assertTrue(chrome.contains("/i/chat"))
        assertTrue(chrome.contains("scrollerLastY.has"))
        assertTrue(chrome.contains("syncBottomOffsetHost"))
        assertTrue(chrome.contains("--tw-nav-inset-top"))
        assertTrue(chrome.contains("--tw-nav-inset-bottom"))
        assertTrue(chrome.contains("data-tw-bottom-bar-surface"))
        assertTrue(chrome.contains("FloatingActionButtonBase"))
        assertTrue(chrome.contains("readScrollTargetY"))
        assertTrue(chrome.contains("twBottomHidden"))
        assertTrue(chrome.contains("--tw-bottom-bar-base-h"))
        assertFalse(chrome.contains("data-tw-top-bar-surface"))
        assertFalse(chrome.contains("data-tw-top-bar-expand"))
        assertFalse(chrome.contains("data-tw-top-bar-host"))
        assertFalse(chrome.contains("data-tw-top-col-pad"))
        assertFalse(chrome.contains("data-tw-bottom-bar\""))
        assertFalse(chrome.contains("data-tw-bottom-bar-clear"))
        assertFalse(chrome.contains("__twDumpBottomDebug"))

        // Drawer exit-ghost engine contracts
        assertTrue(drawer.contains("window.__TW_DRAWER__"))
        assertTrue(drawer.contains("trackActiveDrawer"))
        assertTrue(drawer.contains("onDomRemoval"))
        assertTrue(drawer.contains("tw-drawer-exit-ghost"))
        assertTrue(drawer.contains("isAlreadySlidOffscreen"))

        // Viewport & location-restore engine contracts
        assertTrue(viewport.contains("window.__TW_VIEWPORT__"))
        assertFalse(viewport.contains("__twSetNavInsetBottom"))
        assertTrue(viewport.contains("__twCaptureLocation"))
        assertTrue(viewport.contains("__twCancelReflow"))
        assertTrue(viewport.contains("__twReflowViewport"))
        assertTrue(viewport.contains("__twCheckLayoutAligned"))
        assertTrue(viewport.contains("window.devicePixelRatio"))
        assertTrue(viewport.contains("tryApplyPendingScrollRestore"))

        // DOM engine contracts
        assertTrue(dom.contains("[role='menuitem'] span"))
        assertTrue(dom.contains("Math.hypot"))
        assertTrue(dom.contains("checkedLogoSvgs = new WeakSet()"))
        assertTrue(dom.contains("tw-mod-preconnect"))
        assertTrue(dom.contains("twSurfaceOverride"))
        assertFalse(dom.contains("twSurfaceTheme"))
        assertTrue(dom.contains("syncNavInsets"))
        assertTrue(dom.contains("navInsetsDirty"))
        assertTrue(dom.contains("app-bar-back"))
        assertTrue(dom.contains("effectiveCheckNavInsets"))
        assertFalse(dom.contains("twLogoChecked"))
        assertFalse(dom.contains("openSettingsWithHaptic?.() ??"))
        assertFalse(dom.contains("__TWITTER_IS_UNFOLDED__"))
        assertFalse(dom.contains("twForceBottomNav"))

        // CSS contracts
        assertTrue(css.contains("removeBookmark"))
        assertTrue(css.contains("FloatingActionButtons_Tweet_Button"))
        assertTrue(css.contains(".tw-mod-theme-bar"))
        assertTrue(css.contains(".tw-mod-dark-subrow"))
        assertTrue(css.contains("[role=\"group\"] > div:has(> a[href*=\"/analytics\"])"))
        assertTrue(css.contains("#tw-status-bar-glass"))
        assertTrue(css.contains("z-index: 9000;"))
        assertFalse(css.contains("z-index: 2147483640;"))
        assertTrue(css.contains("data-tw-top-inset=\"true\""))
        assertTrue(css.contains("data-tw-top-bar-offset=\"true\""))
        assertTrue(css.contains("data-tw-top-bar-clear-glass=\"true\""))
        assertTrue(css.contains("data-tw-glass-unified=\"true\""))
        assertTrue(css.contains("#layers:has([data-testid=\"Drawer\"]"))
        assertTrue(css.contains("#tw-drawer-exit-ghost"))
        assertTrue(css.contains("--tw-top-hdr-h"))
        assertTrue(css.contains("clip-path: inset(0 0 var(--tw-top-hdr-h, 0px) 0)"))
        assertTrue(css.contains("data-tw-top-bar-autohide=\"true\""))
        assertTrue(css.contains("clip-path: inset(100% 0 0 0)"))
        assertTrue(css.contains("--tw-nav-inset-top"))
        assertFalse(css.contains("data-tw-top-bar-surface"))
        assertFalse(css.contains("data-tw-top-bar-expand"))
        assertFalse(css.contains("data-tw-top-bar-host"))
        assertFalse(css.contains("data-tw-top-col-pad"))
        assertTrue(css.contains("data-tw-nav-inset=\"true\""))
        assertTrue(css.contains("data-tw-bottom-bar-surface=\"true\""))
        assertTrue(css.contains("[data-testid=\"FloatingActionButtonBase\"]"))
        assertTrue(css.contains("data-tw-bottom-hidden=\"true\""))
        assertTrue(css.contains("--tw-nav-inset-bottom"))
        assertFalse(css.contains("data-tw-bottom-bar-clear"))
        assertFalse(css.contains("data-tw-bottom-bar-host"))
        assertFalse(css.contains("rgba(255, 255, 255, 0.85)"))
        assertFalse(css.contains("data-tw-force-bottom-nav"))
    }

    @Test
    fun assetBundleBuildsSafeNavInsetAndConfigSeedScripts() {
        val bundle = TwitterAssetBundle(
            interceptorJs = "/*INTERCEPTOR*/",
            themeJs = "/*THEME*/",
            featuresJs = "/*FEATURES*/",
            chromeJs = "/*CHROME*/",
            drawerJs = "/*DRAWER*/",
            viewportJs = "/*VIEWPORT*/",
            domJs = "/*DOM*/",
            quotedCss = "\"\""
        )
        val navScript = bundle.buildNavInsetScript(navInsetBottomDp = 24, navInsetTopDp = 36)
        assertTrue(navScript.contains("window.__TWITTER_NAV_INSET_TOP__ = 36;"))
        assertTrue(navScript.contains("window.__TWITTER_NAV_INSET_BOTTOM__ = 24;"))
        assertTrue(navScript.contains("window.__twSetNavInsets(36, 24);"))

        val clampedNavScript = bundle.buildNavInsetScript(navInsetBottomDp = -12, navInsetTopDp = -8)
        assertTrue(clampedNavScript.contains("window.__TWITTER_NAV_INSET_TOP__ = 0;"))
        assertTrue(clampedNavScript.contains("window.__TWITTER_NAV_INSET_BOTTOM__ = 0;"))
        assertTrue(clampedNavScript.contains("window.__twSetNavInsets(0, 0);"))

        val seedScript = bundle.buildConfigSeedScript(
            configJson = "{\"blockAds\":true}",
            isSystemDark = true,
            restoreStateJson = "{\"scrollY\":120}",
            navInsetBottomDp = -5,
            navInsetTopDp = 44
        )
        assertTrue(seedScript.contains("window.__TWITTER_RESTORE_STATE__ = {\"scrollY\":120};"))
        assertTrue(seedScript.contains("window.__TWITTER_NAV_INSET_TOP__ = 44;"))
        assertTrue(seedScript.contains("window.__TWITTER_NAV_INSET_BOTTOM__ = 0;"))
        assertTrue(seedScript.contains("window.__TWITTER_SYSTEM_DARK__ = true;"))
        assertTrue(seedScript.contains("window.__applyTwitterConfig(window.__TWITTER_CONFIG__, true);"))
        assertTrue(bundle.coreEngineScript.indexOf("/*THEME*/") < bundle.coreEngineScript.indexOf("/*CHROME*/"))
        assertTrue(bundle.coreEngineScript.indexOf("/*CHROME*/") < bundle.coreEngineScript.indexOf("/*DRAWER*/"))
        assertTrue(bundle.coreEngineScript.indexOf("/*DRAWER*/") < bundle.coreEngineScript.indexOf("/*VIEWPORT*/"))
        assertTrue(bundle.coreEngineScript.indexOf("/*VIEWPORT*/") < bundle.coreEngineScript.indexOf("/*DOM*/"))
    }

    @Test
    fun uncoveredTopInsetDpRespectsCutoutsDensityAndFullscreenVideo() {
        assertEquals(36, computeUncoveredTopInsetDp(statusTopPx = 99, cutoutTopPx = 0, density = 2.75f, isFullscreenVideo = false))
        assertEquals(44, computeUncoveredTopInsetDp(statusTopPx = 99, cutoutTopPx = 121, density = 2.75f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredTopInsetDp(statusTopPx = 99, cutoutTopPx = 121, density = 2.75f, isFullscreenVideo = true))
        assertEquals(0, computeUncoveredTopInsetDp(statusTopPx = 0, cutoutTopPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredTopInsetDp(statusTopPx = 99, cutoutTopPx = 0, density = 0f, isFullscreenVideo = false))
    }

    @Test
    fun uncoveredNavBottomDpRespectsImeCutoutsDensityAndFullscreenVideo() {
        assertEquals(24, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 0, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(48, computeUncoveredNavBottomDp(navBottomPx = 144, imeBottomPx = 0, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(24, computeUncoveredNavBottomDp(navBottomPx = 64, imeBottomPx = 0, cutoutBottomPx = 0, density = 2.75f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 900, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(14, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 30, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 0, cutoutBottomPx = 80, density = 3.0f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 0, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = true))
        assertEquals(0, computeUncoveredNavBottomDp(navBottomPx = 0, imeBottomPx = 0, cutoutBottomPx = 0, density = 3.0f, isFullscreenVideo = false))
        assertEquals(0, computeUncoveredNavBottomDp(navBottomPx = 72, imeBottomPx = 0, cutoutBottomPx = 0, density = 0f, isFullscreenVideo = false))
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
