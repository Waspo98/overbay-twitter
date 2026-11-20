# Twitter for Android (Overbay Labs)

An open-source Android `WebView` client that wraps `https://x.com` to restore classic Twitter branding and remove timeline clutter on your phone.

> **Disclaimer:** This is an independent, non-commercial open-source project by [Overbay.app](https://overbay.app/labs/twitter/). It is **not affiliated with, endorsed by, or connected to X Corp.** and is never distributed on Google Play.

---

## Download

Grab the latest pre-built Android APK directly from GitHub Releases:

- **[Download Twitter-debug.apk](https://github.com/Waspo98/overbay-twitter/releases/download/debug-latest/Twitter-debug.apk)**

*(Requires Android 8.0+ / API 26+. Because this APK is sideloaded outside Google Play, Android will ask permission to install from your browser or file manager.)*

---

## What It Changes

Everything runs locally inside the app's `WebView` and interception pipeline—there are no Overbay servers, no analytics, and no telemetry leaving your device.

### 1. Network & GraphQL Ad Stripping
- Intercepts `/i/api/graphql/*` timeline payloads before React renders them, removing promoted tweets and algorithmic suggestion carousels (*"Who to follow"*, *"Topics to follow"*, *"Communities to join"*). No blank gaps or scroll stutter.
- Blocks known telemetry and ad tracking requests at the Android `WebViewClient` layer (`/i/api/1.1/jot/client_event.json`, `ads-twitter.com`, `analytics.twitter.com`).

### 2. Classic Branding & UI Cleanup
- Swaps the X logo in the app launcher, splash screen, top bar, and navigation drawer back to the classic blue bird (`#1DA1F2`).
- Restores `"Tweet"`, `"Retweet"`, and `"Quote Tweet"` wording across buttons and menus.
- Hides Grok AI surfaces, Subscribe/Premium upsells, and optional view/bookmark counters.

### 3. Feed Control, Media & Muting
- Choose your default Home tab (`For you`, `Following`, or a personal List), reorder or hide feed tabs, and stop timelines from jumping back to the top on resume.
- 1-tap full-bitrate `.mp4` video downloads via Android `DownloadManager`.
- Upgrades `pbs.twimg.com/media` loads and downloads to original resolution (`orig` / `4096x4096`).
- Unwraps `t.co` redirect links directly to their destination URL, with optional long-press actions.
- Custom keyword, `#hashtag`, domain, and regex muting enforced across timelines and quote tweets.

### 4. Built-In Mod Settings
Open **Twitter Mod Settings** directly from the slide-out drawer or by long-pressing the bird icon in the header. Every visual toggle updates live without reloading the page; GraphQL feed toggles prompt a one-tap timeline refresh.

---

## Privacy

- Your login session stays inside Android's local `WebView` cookie storage (`x.com` only).
- All mod preferences and mute rules are stored locally on your device.
- Nothing is sent to Overbay.app or any third-party analytics service.

---

## Building from Source

Requires JDK 17+ and Android SDK (API 36).

```bash
git clone https://github.com/Waspo98/overbay-twitter.git
cd overbay-twitter
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK is written to `Twitter-debug.apk` in the repository root (or `app/build/outputs/apk/debug/`).

## License

Licensed under the [MIT License](LICENSE).
