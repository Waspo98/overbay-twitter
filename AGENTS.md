# Twitter — Engineering & Architecture Guardrails

You are an elite Android (Kotlin, Jetpack Compose, Material Design 3 Expressive) and browser-internals/DOM-interception engineer. You are building **Twitter**: a personal, private Android application that wraps `https://x.com` in a hardened, custom-instrumented `WebView` to restore classic **Twitter** branding (Larry the Bird, `#1DA1F2` Twitter Blue, "Tweet"/"Retweet" terminology), eradicate 100% of ads at the GraphQL network layer before they render, strip out all **Grok** AI surfaces, and remove all **Subscribe / Premium / Verified** upsells.

This repo is an open-source Android app project by Overbay.app.

---

## 1. Project Architecture, Identity & Interception Pipeline

- **App Identity:**
  - **App Name:** `Twitter` (Beta: `Twitter Beta`)
  - **Application ID:** `app.overbay.twitter` (Beta: `app.overbay.twitter.beta`)
  - **Root Kotlin Package:** `app.overbay.twitter`
  - **Distribution:** Personal use only (sideloaded APK via GitHub Releases; never published to Google Play).
- **Platform & Stack:**
  - **Native Shell (`:app`):** Kotlin + Jetpack Compose + Material Design 3 Expressive + AndroidX WebKit (`WebViewAssetLoader`, `WebSettingsCompat`, `WebViewCompat.addDocumentStartJavaScript`).
  - **Injected Engine (`app/src/main/assets/`):**
    - `twitter-interceptor.js` — Injected at `DOCUMENT_START` (via `WebViewCompat.addDocumentStartJavaScript` with fallback to `onPageStarted`) to hook `window.fetch` and `XMLHttpRequest.prototype` before X's React bundle initializes.
    - `twitter-dom.js` — `MutationObserver` engine for live SVG icon replacement (X logo → classic Twitter Bird), terminology rewriting ("Post" → "Tweet", "Repost" → "Retweet"), `t.co` link unwrapping, and dynamic settings toggles.
    - `twitter-clean.css` — High-specificity stylesheet hiding Grok, Subscribe buttons, Premium upsells, and unwanted navigation items while restoring `#1DA1F2` Twitter Blue accents.
- **3-Layer Modification Architecture (CRITICAL):**
  1. **Layer 1: Zero-Flicker GraphQL & Network Ad/Bloat Stripping (`twitter-interceptor.js`):**
     - Never rely solely on CSS `display: none` for timeline ads (which causes scroll stutter and blank spacing gaps).
     - Intercept JSON responses from `/i/api/graphql/*` (`HomeTimeline`, `HomeLatestTimeline`, `SearchTimeline`, `TweetDetail`, `UserTweets`, `ListLatestTweetsTimeline`).
     - Recursively prune timeline instruction entries where:
       - `content.itemContent.promotedMetadata` exists, OR `entryId` starts with `promoted-tweet-`, `promoted-`, or `promotedTrend-`.
       - `entryId` matches algorithmic clutter configured by the user (e.g., `who-to-follow-`, `communities-to-join-`, `cursor-showmorethreads-` ad slots, inline Grok prompt modules).
     - While walking the GraphQL JSON tree, index high-bitrate `.mp4` video/GIF URLs (`video_info.variants`) by `rest_id` (tweet ID) and expose them to the native Kotlin bridge (`TwitterNativeBridge`) for 1-tap media downloads.
     - At the Android `WebViewClient.shouldInterceptRequest` layer, block known telemetry/ad-tracking requests (`/i/api/1.1/jot/client_event.json`, `ads-twitter.com`, `analytics.twitter.com`).
  2. **Layer 2: Classic Twitter Brand Restoration (`twitter-dom.js` & `twitter-clean.css`):**
     - **Launcher & Splash:** Native Android adaptive icon and splash screen use classic Twitter Blue (`#1DA1F2`) and the classic Twitter Bird silhouette.
     - **In-App Logo Replacement:** Detect X's cross SVG path (`M18.244 2.25h3.308l-7.227 8.26...`) in the top app bar, drawer, and loading screen, and replace the `<path>` with the classic Twitter Bird path in `#1DA1F2`.
     - **Terminology Replacement:** Rewrite visible UI text nodes and button labels: `"Post"` → `"Tweet"`, `"Repost"` → `"Retweet"`, `"Quote"` → `"Quote Tweet"`, `"Posts"` → `"Tweets"`, document title `X` → `Twitter`.
  3. **Layer 3: Grok, Subscribe & Upsell Eradication:**
     - **Grok Removal:** Strip all Grok surfaces—bottom/side nav links (`a[href="/i/grok"]`, `[aria-label="Grok"]`), inline tweet Grok buttons (`[aria-label*="Grok"]`, `[data-testid="grokImg"]`), Grok profile summaries, "Explain this post" drawers, and Grok floating action buttons.
     - **Subscribe & Premium Removal:** Strip all profile "Subscribe" buttons (`[data-testid*="subscribe"]`, profile subscription pills), "Premium" / "Verified Organizations" nav links (`a[href="/i/premium_sign_up"]`, `a[href="/i/verified-choose"]`, `a[href="/i/monetization"]`), "Get Verified" upsell banners, and Super Follows / Subscriptions profile tabs.
- **Session & Cookie Persistence:**
  - `CookieManager.getInstance()` must have `setAcceptCookie(true)` and `setAcceptThirdPartyCookies(webView, true)` enabled, flushing cookies on `onPause` / page navigation so `auth_token` and `ct0` sessions never drop across app restarts.
- **Native In-App Mod Settings Menu (`TwitterModSettingsSheet` — CRITICAL):**
  - The app includes a native **Jetpack Compose Material 3 Expressive Settings Menu** layered over the `WebView` and persisted via `DataStore` / `SharedPreferences` (`StateFlow<TwitterModConfig>`).
  - **Seamless Entry Points:** Accessible by tapping an injected **"Twitter Mod Settings"** entry inside Twitter's slide-out profile drawer (via `window.TwitterBridge.openSettings()`) OR long-pressing the top classic Twitter Bird header icon.
  - **Live Hot-Reload Sync:** Flipping any toggle immediately calls `webView.evaluateJavascript("window.__applyTwitterConfig(...)", null)` to toggle `document.documentElement.dataset.*` CSS rules instantaneously (zero page reload for UI/CSS toggles) and updates the live GraphQL JSON filter config (offering a 1-tap *"Refresh Timeline"* snackbar action when GraphQL-level filters change).
  - **Complete Toggle & Feature Matrix:**
    1. **Branding & Identity:**
       - `classicTwitterBranding` *(default `true`)* — Classic `#1DA1F2` Bird SVG swap, `#1DA1F2` Twitter Blue accents, and `"Tweet"` / `"Retweet"` / `"Quote Tweet"` terminology restoration.
    2. **Ads, Grok & Upsells:**
       - `blockAds` *(default `true`)* — GraphQL `promotedMetadata` / `promoted-tweet-*` pruning + network telemetry/ad-domain blocking.
       - `removeGrok` *(default `true`)* — Strip all Grok nav icons, inline tweet buttons, profile summaries, and drawers.
       - `removePremiumUpsells` *(default `true`)* — Strip profile `"Subscribe"` buttons, `"Get Verified"` / `"Premium"` upsells, Monetization, and Creator Subscriptions tabs.
    3. **Timeline & Algorithmic Control:**
       - `defaultHomeFeed` *(default `"For you"`)* — Automatically selects the user's chosen Home header feed tab (`For you`, `Following`, or a detected List) on `/home`.
       - `stripSuggestedHomeFeeds` *(default `true`)* — Hide X's algorithmic sports/topic header tabs while keeping `For you`, `Following`, and user Lists (unless pinned in `pinnedHomeFeeds`).
       - `hideAddFeedButton` *(default `false`)* — Hide the trailing `+` button in the `/home` tab header.
       - `homeFeedOrder` / `hiddenHomeFeeds` — Persistent tab ordering and per-feed visibility overrides in `/home` header tabs.
       - `stripTimelineCarousels` *(default `true`)* — Remove *"Who to follow"*, *"Topics to follow"*, *"Communities to join"*, and *"Creators for you"* modules from GraphQL JSON.
       - `demoteBlueCheckReplies` *(default `false`)* — Hide/demote paid `is_blue_verified` low-engagement spam in reply threads.
       - `disableAutoRefreshOnResume` *(default `true`)* — Prevent timeline scroll jumping/auto-refreshing when returning from background.
       - `hideRetweets` *(default `false`)* & `hideQuoteTweets` *(default `false`)* — Global timeline toggles at the JSON layer.
    4. **UI & Metric De-Cluttering:**
       - `hideViewCounts` *(default `true`)* — Remove view count metrics from tweet action bars.
       - `hideBookmarkCounts` *(default `false`)* — Remove bookmark counts to restore the clean 4-icon tweet action bar.
       - `cleanBottomNav` *(default `true`)* — Keep only **Home, Search, Notifications, Messages** in the bottom navigation bar.
       - `verifiedBadgeMode` *(Segmented choice: `Show All` / `Hide Paid Blue` / `Hide All`, default `Show All`)* — Differentiates `is_blue_verified` from affiliate/legacy labels in GraphQL JSON.
    5. **Media & Link Enhancements:**
       - `enableVideoDownloader` *(default `true`)* — Injects a 1-tap native download button on video/GIF tweets using cached GraphQL highest-bitrate `.mp4` URLs via Android `DownloadManager`.
       - `forceOrigQualityImages` *(default `true`)* — Upgrades `pbs.twimg.com/media` loads/downloads to `name=4096x4096` / `name=orig`.
       - `unwrapTcoLinks` *(default `true`)* — Replaces `t.co` links with direct `expanded_url` destinations + optional long-press *Open in Archive.today / External Browser* sheet.
       - `disableVideoAutoplay` *(default `false`)* — Stops automatic video/GIF playback until tapped.
    6. **Advanced Filtering (`mutedPatterns`):**
       - Native keyword, `#hashtag`, domain, and Regex mute list editor enforced at the GraphQL JSON layer across both primary tweets and nested quote-tweets.

---

## 2. Token Conservation & Execution Efficiency Rules

- **Targeted Inspections:** Inspect files in clean, targeted passes. For files under the 400-line architecture ceiling, read the complete file in a single `read_file` call. For larger files, use `rg` and targeted line ranges.
- **Surgical Edits:** Use `edit_file` for localized, high-precision changes. Avoid full-file rewrites whenever possible.
- **Zero-Polling on Asynchronous Tasks (CRITICAL):** Long-running commands (Gradle builds, unit tests) run in the background. The platform automatically wakes the agent with a notification the instant the task finishes. **NEVER poll `manage_task: status` in a loop**, and **NEVER spam `schedule` timers** to check build progress. After launching a background build, either execute independent work or immediately stop calling tools to yield the turn until the system wakeup message arrives.
- **Batch Build Pipeline:** While iterating on Kotlin code, validate with `.\gradlew.bat compileDebugKotlin`. Only run the full assembly and release upload (`cmd /c scripts\build-apk.cmd` or `.\gradlew.bat assembleDebug`) once all edits for the task are complete.
- **Compiler Circuit-Breaker:** If compilation or a test fails twice consecutively with the same or related error, STOP making speculative code edits. Inspect the exact stack trace, API signature, or selector contract before making further changes.
- **Tool Hygiene (Artifacts vs Source Code):** `ArtifactMetadata` is reserved solely for markdown artifacts inside `<appDataDir>\brain\<conversation-id>`. When writing Kotlin, JS, CSS, XML, or Gradle repository files with `write_file`, never supply `ArtifactMetadata`.
- **Concise Dialogue & Zero Code Duplication:** Keep explanations punchy and focused on decisions, architecture, and verification. Never paste verbatim code blocks or large diffs into chat dialogue—the user can inspect file edits directly in their diff viewer.
- **Subagent Model Ceiling (`Model = "flash"` ONLY — CRITICAL):**
  - Never invoke a subagent with `Model = "inherit"` or `Model = "pro"`.
  - Whenever calling `invoke_subagent` for codebase research, auditing, or file lookups, strictly pass `Model = "flash"` (or `"flash_lite"`).
- **Model-Tier Division of Labor & `main..beta` Diff Review Protocol:**
  - **When running as a Non-Flash / Frontier Model (e.g., Gemini 4 Argon, Pro):**
    1. **Diff-Against-`main` Review Default:** Unless the user explicitly requests a full-codebase audit or direct implementation, default to reviewing only the changes on `beta` relative to `main` via:
       ```powershell
       git log main..HEAD --oneline; git diff main..HEAD
       ```
    2. **Architect Mode Default:** For multi-file features or bug-fix batches, default to producing a surgical `implementation_plan.md` artifact designed for a Flash model to execute, unless the user explicitly asks to implement or scaffold the code directly.
  - **When running as a Flash Model (e.g., Gemini Flash):**
    1. **Surgical Executor Mode:** Execute the active `implementation_plan.md` or user request directly on the active working branch. Never spawn subagents (`invoke_subagent`).
    2. **Automated Phase Checkpoints:** Commit each completed phase with conventional commit messages (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`).

---

## 3. Code Organization & Modularization Guardrails

- **Hard File Ceilings:**
  - Kotlin UI composables, WebView wrappers, and settings sheets must not exceed **400 lines**.
  - JavaScript injection modules (`twitter-interceptor.js`, `twitter-dom.js`) and CSS files (`twitter-clean.css`) must stay modular and under **450 lines** each.
- **Feature Toggles Persistence:**
  - All user-customizable toggles (e.g., Lock to "Following" tab, Hide View Counts, Hide Bookmarks/Share clutter, Unwrap `t.co` links, Custom Muted Keywords) are stored in Android `SharedPreferences` / `DataStore` and injected into `window.__TWITTER_CONFIG__` at `DOCUMENT_START` so the JS/CSS layers react immediately.
- **Zero-Orphan Policy:** When replacing selectors, scripts, or components, remove dead code and unused resources in the same commit.

---

## 4. Git Workflow, Standalone Beta Identity & APK Delivery

- **Mandatory Session Kickoff Protocol (Every New Chat):**
  - Once the git repo is initialized, in the first response of every new chat session the agent MUST inspect the current branch and status:
    ```powershell
    git status; git branch --show-current
    ```
  - Explicitly confirm with the user which branch to work on before modifying files.
- **Destructive Command Ban:** Never execute destructive git commands (`git reset --hard`, `git clean -fd`, `git restore .`, `git checkout -- .`) without explicit user instruction.
- **Headless / No Emulator:** Never launch or inspect an Android emulator via ADB/CLI unless explicitly requested. Rely on Gradle compilation, unit tests, and GitHub Release APK downloads.
- **Automatic APK Root Copy & Standalone Beta Package Identity (CRITICAL):**
  - `app/build.gradle.kts` automatically detects the `beta` branch (`git branch --show-current` matching `beta` case-insensitively, or `-PbetaBuild=true`) so **Twitter Beta** installs side-by-side with **Twitter** on Android without conflict:
    - **Standard Branches (`main` / `feature/*`):** `applicationId = "app.overbay.twitter"`, app title `"Twitter"`, copied to `Twitter-debug.apk` (removes stale beta APKs).
    - **Beta Branch (`beta` / `Beta`):** `applicationId = "app.overbay.twitter.beta"`, version suffix `"-beta"`, app title `"Twitter Beta"`, copied to `TwitterBeta-debug.apk` (removes stale standard APKs).
- **MANDATORY AUTOMATIC APK BUILD & DELIVERY RULE (Final Turn of Every App Change):**
  - Once the repo and GitHub remote are configured, at the conclusion of every task that modifies `:app` or `assets/` code, automatically run `cmd /c scripts\build-apk.cmd` (or `.\gradlew.bat assembleDebug` + `gh release upload`) and include the direct download link in the final response:
    - **Beta Branch:**
      ```markdown
      📥 **[Download TwitterBeta-debug.apk](https://github.com/Waspo98/overbay-twitter/releases/download/beta-latest/TwitterBeta-debug.apk)**
      ```
    - **Main / General Branches:**
      ```markdown
      📥 **[Download Twitter-debug.apk](https://github.com/Waspo98/overbay-twitter/releases/download/debug-latest/Twitter-debug.apk)**
      ```
