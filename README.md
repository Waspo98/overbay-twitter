# Twitter for Android

An open-source Android `WebView` wrapper for `https://x.com` that restores classic Twitter branding, removes ads and Grok, and adds native timeline and media controls.

> **Disclaimer:** This is an independent, non-commercial open-source project by [Overbay.app](https://overbay.app/labs/twitter/). It is not affiliated with, endorsed by, or connected to X Corp. and is not distributed on Google Play.

## Download

Grab the latest signed Android APK from [GitHub Releases](https://github.com/Waspo98/overbay-twitter/releases/latest), or download the rolling debug build ([Twitter-debug.apk](https://github.com/Waspo98/overbay-twitter/releases/download/debug-latest/Twitter-debug.apk)).

Requires Android 8.0+ (API 26+). Because the APK is sideloaded outside Google Play, Android will ask you to allow installs from your browser or file manager.

## Features

Everything runs locally on your phone inside the app's `WebView` and interception scripts. There are no proxy servers or external backends.

### Ad and Clutter Removal
- **GraphQL ad filtering:** Removes promoted tweets and suggestion carousels ("Who to follow", "Topics to follow", "Communities to join") from `/i/api/graphql/*` responses before React renders them, avoiding empty gaps while scrolling.
- **Tracker and Grok blocking:** Blocks telemetry and ad endpoints (`/i/api/1.1/jot/`, `/i/adsct`, `ads-twitter.com`, `ads.x.com`, `analytics.twitter.com`) along with Grok API requests (`/i/api/2/grok/`, `/i/api/graphql/*/Grok*`) at the `WebViewClient` layer.
- **Grok and upsells:** Hides Grok tabs, inline tweet buttons, summaries, profile Subscribe buttons, and Premium prompts.

### Branding and Interface
- **Classic branding:** Replaces the X logo in the launcher, splash screen, header, and drawer with the blue bird (`#1DA1F2`), and changes "Post", "Repost", and "Quote" labels back to "Tweet", "Retweet", and "Quote Tweet".
- **Counters and tabs:** Optionally hides view counts, bookmark counts, and extra bottom-bar tabs (Grok, Communities, Spaces, Jobs).
- **Verified badges:** Choose between showing all checkmarks, hiding paid blue badges while keeping affiliate and legacy labels, or hiding all badges.
- **Theme control:** Pick Follow Device (Auto), Light, Dim (`#15202B`), or Lights Out (`#000000`) from Mod Settings or the slide-out drawer, choose Dim or Lights Out for Auto dark mode, and keep Android system bars, native sheets, and Chrome Custom Tabs in sync.
- **Edge-to-edge layout:** Renders under a frosted status bar and transparent navigation bar, and auto-hides the bottom tab bar and compose button while scrolling down.

### Feeds and Filtering
- **Home feed tabs:** Pick a default Home tab (`For you`, `Following`, or a personal List), reorder or hide tabs, hide suggested topic tabs, and optionally hide the `+` button.
- **Scroll protection:** Stops timelines from auto-refreshing and jumping back to the top when you return from another app.
- **Timeline filters:** Optionally hide retweets, hide quote tweets, or hide unfollowed paid-blue replies in threads (keeping the original poster and accounts you follow).
- **Mute rules:** Mute keywords, `#hashtags`, domains, or `/regex/` patterns across timelines and quote tweets.

### Media and Links
- **1-tap downloads:** Adds download buttons for highest-bitrate `.mp4` videos/GIFs on tweets and full-resolution photos in the image viewer, saving to `Downloads/Twitter` via Android `DownloadManager`.
- **Tweet screenshots:** Adds a capture button to each tweet's action bar that hides floating bars and stitches tall tweets into one PNG. Choose Clean Card, Framed, or Tight framing, toggle engagement metrics, and share, copy, or save to `Pictures/Twitter`.
- **Original-resolution photos:** Upgrades full-screen photo viewer images to `name=orig` automatically.
- **Autoplay control:** Optionally pauses timeline videos and GIFs until you tap play.
- **Clean links:** Unwraps `t.co` redirects, strips tracking parameters (`utm_*`, `fbclid`, `gclid`, and similar), and opens links in Chrome Custom Tabs or your default browser. Long-pressing an external link opens actions to view the clean URL, open it in Archive.today (`archive.ph`), copy, or share.

### Mod Settings and Android Shell
Open **Twitter Mod Settings** from the slide-out drawer or by long-pressing the Home tab or header bird icon. Visual toggles apply right away without reloading; GraphQL filter changes offer a one-tap feed reload.

The native shell also handles `x.com` and `twitter.com` deep links, pull-to-refresh, fullscreen video playback, foldable and tablet layouts (`500dp+`, reflowing live across fold/unfold transitions while preserving your place, with automatic fallback recovery), an offline retry screen, and automatic recovery if Android's WebView renderer crashes.

## Privacy

- Login sessions stay in Android's local `WebView` cookie storage (`x.com` / `twitter.com`).
- Mod settings and mute rules are stored locally in app `SharedPreferences` (`twitter_mod_prefs.xml`).
- The JavaScript-to-native bridge uses origin-scoped `WebMessageListener` channels restricted to `x.com` and `twitter.com` main frames (with a fallback bridge on older WebView builds).
- No usage data or analytics are sent to Overbay.app or third parties.

## Building from Source

Built with Kotlin, Jetpack Compose (Material 3), AndroidX WebKit, and injected JS/CSS assets under `app/src/main/assets/`. Requires JDK 17+ and Android SDK (compileSdk 36).

```bash
git clone https://github.com/Waspo98/overbay-twitter.git
cd overbay-twitter
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The built APK is copied to `Twitter-debug.apk` in the repository root (and `app/build/outputs/apk/debug/app-debug.apk`). Passing `-PbetaBuild=true` (or building from the `beta` branch) builds `TwitterBeta-debug.apk` under `app.overbay.twitter.beta` so it can be installed alongside the main app.

## License

Licensed under the [MIT License](LICENSE).
