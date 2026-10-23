# SC Viewer — Fire TV app for streamingcommunity

Native Android (Kotlin) app for Fire TV Stick. Wraps the streamingcommunity web
catalogue in a WebView with:

- **Desktop Chrome UA spoof** — Fire TV reports itself as a Windows 11 Chrome
  browser so the site serves the full catalogue + the standard web player
  (it would otherwise detect the `AFT...` UA and gate the UI).
- **Network ad block** — a hosts-style list (~70 ad networks + popunder
  domains) is enforced via `WebViewClient.shouldInterceptRequest`, so ad
  requests are dropped at the network layer with a canned 403 response.
- **Anti-redirect / anti-popunder** — `window.open` is neutralised, every
  link is forced to `target=_self`, `beforeunload` hijacks are stripped, and
  cross-origin navigations are blocked.
- **VibraVid-style domain sync** — at startup and every 30 minutes
  (via WorkManager) the app pulls `domains_s.json` from
  `raw.githubusercontent.com/AstraeLabs/Domains/main/domains_s.json`, caches
  it locally as `domains.json`, and uses the cached copy as fallback if
  GitHub is unreachable. The hardcoded fallback is
  `https://streamingcommunityz.build/`.
- **Virtual mouse cursor** — the D-pad moves a floating pointer over the
  page; OK dispatches a real tap at the pointer position, so *everything* on
  the site is clickable (cards, video controls, cookie banners, search
  boxes) without relying on fragile DOM focus. Speed ramps up while a
  direction is held and the base speed is adjustable (1–10) in
  **Menu → Impostazioni**. Pushing against the top/bottom edge of the
  screen scrolls the page.
- **Fire TV remote conveniences**:
  - **Long-press OK** → opens the favourites overlay.
  - **Menu button** → opens the bottom bar (Home / Favorites / History /
    Reload / Back / Impostazioni).
  - **Skip intro** — when the injected JS detects a `<video>` with
    `currentTime < 30s`, a "Salta intro" button appears bottom-right;
    press OK to skip to 30s.
  - **Watch history** — last 20 visited pages, deduped, accessible via
    the Menu bar.
- **Netflix-dark theme** (`#0F0F0F` / `#E50914`).

## Project layout

```
SCViewer/
├── settings.gradle.kts          # Gradle settings
├── build.gradle.kts             # Root build (AGP 8.5.2, Kotlin 1.9.24)
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── gradlew / gradlew.bat
├── bootstrap-gradle.sh          # One-time wrapper jar bootstrap
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   ├── domains.json             # Hardcoded fallback domain
        │   ├── adblock_hosts.txt        # Default ad-block list
        │   └── page_inject.js           # Cosmetic hide + anti-popunder + skip-intro JS
        ├── java/com/scviewer/fire/
        │   ├── App.kt                   # Application, schedules domain-refresh worker
        │   ├── MainActivity.kt          # Activity, WebView setup, D-pad / Menu key handling
        │   ├── data/
        │   │   ├── DomainConfig.kt      # @Serializable domain model
        │   │   ├── DomainRepository.kt  # Single source of truth for current domain
        │   │   ├── FavoritesStore.kt    # SharedPreferences-backed favorites
        │   │   ├── HistoryStore.kt      # SharedPreferences-backed history (cap 20)
        │   │   └── SettingsStore.kt     # Persisted settings (cursor speed 1–10)
        │   ├── network/
        │   │   ├── AdBlocker.kt         # Hosts-based network blocker
        │   │   ├── DomainFetcher.kt     # Pulls domains_s.json from AstraeLabs/Domains
        │   │   └── PageInjector.kt      # Loads page_inject.js, runs via evaluateJavascript
        │   ├── web/
        │   │   ├── SCWebViewClient.kt   # UA spoof, ad block, anti-redirect, page listener
        │   │   ├── SCWebChromeClient.kt  # Fullscreen video support
        │   │   └── JsInterface.kt       # @JavascriptInterface bridge for skip-intro / runtime ad learning
        │   ├── ui/
        │   │   ├── CursorController.kt   # Virtual mouse cursor (D-pad, accel, edge-scroll)
        │   │   ├── OverlayDialog.kt      # Favorites / history dialog
        │   │   ├── OverlayListAdapter.kt
        │   │   ├── SettingsDialog.kt     # Impostazioni (cursor speed)
        │   │   └── SkipIntroController.kt
        │   └── work/
        │       ├── DomainRefreshWorker.kt   # 30-min periodic domain sync
        │       └── DomainRefreshScheduler.kt
        └── res/
            ├── drawable/                # Banner, cursor_pointer, launcher vector
            ├── layout/                  # activity_main, dialog_settings, overlay_list, item_list
            └── values/                  # colors, strings, themes, dimens
```

## Build

### Prerequisites

- JDK 17 or 21 (required by AGP 8.5.2). On Debian Trixie: `sudo apt install openjdk-21-jdk-headless`
- Android SDK with platform 34 + build-tools 34.0.0
- (Optional but recommended) Android Studio Iguana or newer

> **Note on Debian Trixie:** the `openjdk-17-jdk-headless` package was removed
> from Trixie. Use `openjdk-21-jdk-headless` instead — it ships `jlink` at
> `/usr/lib/jvm/java-21-openjdk-amd64/bin/jlink`, which AGP needs to construct
> the Android JDK image. The project's `app/build.gradle.kts` pins the JVM
> toolchain to 21 accordingly.

### Build the APK from the command line

```bash
cd SCViewer

# 1) If gradle/wrapper/gradle-wrapper.jar is missing (it's a binary file and
#    can't be checked in via text), bootstrap the wrapper once:
./bootstrap-gradle.sh

# 2) Build the debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

For a release APK signed with the debug keystore (handy for sideloading on
a Fire TV Stick without re-enabling developer mode each time):

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

The release build is minified + resource-shrunk with the rules in
`app/proguard-rules.pro` (preserves the JS interface + serialization
serializers).

## Sideload on a Fire TV Stick

> **No computer?** See [TUTORIAL.md](TUTORIAL.md) — install directly on the
> Fire TV with the Downloader app and the short code `7770492`
> (guide in Italian / guida in italiano).

1. **Enable ADB debugging on the Fire TV:**
   Settings → My Fire TV → Developer Options → ADB Debugging = ON,
   Install Unknown Apps = ON for "Docs" (or any file manager).

2. **Connect from your computer** (same Wi-Fi):
   ```bash
   adb connect <fire-tv-ip>:5555
   ```

3. **Install the APK:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. **Launch:** The app shows up on the Fire TV home row as
   "**SC Viewer**" with the red play-chevron icon.

## Updating the ad-block list

The list is at `app/src/main/assets/adblock_hosts.txt`. Standard hosts
syntax — one entry per line, IP optional, `#` for comments:

```
0.0.0.0 doubleclick.net
*.googlesyndication.com
# comment
```

On first launch the asset is copied to internal storage; from then on the
app always reads from disk, so runtime-learned hosts (the JS interface can
report additional ad hosts it observes) are appended and persist across
sessions.

To ship an updated list, edit the asset, rebuild, and reinstall.

## How the desktop UA spoof works

The relevant line is in `MainActivity.configureWebView()`:

```kotlin
userAgentString = SCWebViewClient.DESKTOP_UA
// DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/124.0.0.0 Safari/537.36"
```

This is set before the first `loadUrl()` call, so the very first request the
Fire TV makes to streamingcommunity carries the desktop Chrome UA. The site
sees a Windows 11 Chrome visitor, serves the full catalogue, and serves the
standard web player (which works perfectly in Android WebView).

Some sites also fingerprint in-app browsers via the `X-Requested-With`
header that WebView injects automatically (it equals the package name).
Stripping that header requires either disabling it via
`WebSettings.setSaveFormData(false)` + reflection (Android N+), or by
implementing a custom `WebViewClient.shouldInterceptRequest` and re-issuing
the request via OkHttp. The current build relies on the UA spoof alone,
which is sufficient for streamingcommunity today; if the site adds
`X-Requested-With` fingerprinting in the future, swap in OkHttp-based
request rewriting in `SCWebViewClient.shouldInterceptRequest`.

## How the VibraVid-style domain sync works

1. **Startup (`DomainRepository.init`):** try to load `domains.json` from
   `context.filesDir`. If missing, fall back to the bundled asset. The app
   always has a domain to load — never shows a blank screen.
2. **Launch refresh (`MainActivity.refreshAndLoad`):** the same fetch runs
   once at app launch, mirroring `VibraVid/utils/_startup_prefetch.py`.
3. **Periodic refresh (`DomainRefreshWorker`):** WorkManager runs the
   fetch every 30 minutes (the minimum WorkManager interval is 15 minutes;
   30 matches the VibraVid default and keeps wakeups cheap on the stick).
4. **Failure mode:** if `raw.githubusercontent.com` is unreachable, the
   cached `domains.json` is used. The user sees a brief toast:
   "Aggiornamento dominio fallito, uso cache locale".
5. **Refresh on resume:** `MainActivity.onResume` also triggers a refresh,
   so the app picks up a new domain quickly if the user has been away for
   a while.

The fetch is done via OkHttp with 4s connect / 8s read timeouts, and
tries the `main` branch first, falling back to `master` if the upstream
branch name ever changes.

## Fire TV remote control map

| Key | Action |
|---|---|
| D-pad ↑ ↓ ← → | Move the **virtual mouse cursor**; it accelerates while held |
| D-pad held at top/bottom edge | Scroll the page up/down |
| OK (short press) | **Click** at the cursor position (real tap dispatched to the WebView) |
| OK (long press) | Open the **Favorites** overlay |
| Menu button | Toggle the bottom **menu bar** (Home / Favorites / History / Reload / Back / Impostazioni) |
| Play/Pause | Toggle **play/pause** of the film being watched (works inside the player iframe via the video agent bridge) |
| Impostazioni | Adjust the **cursor speed** (1–10) with a D-pad-friendly seek bar |
| Back | Go back in WebView history; close overlay/menu if open |
| Rewind / Fast-forward | Scroll the page (if the remote has these keys) |
| Skip intro button (bottom-right) | Visible only when a video is in the intro window; press to jump to 30s |

While a film is actually playing (a long, large video detected by the video
agent injected in every frame), the cursor **auto-hides** ~4 s after the last
D-pad press; any D-pad key brings it back (first OK only reveals it, so it
never clicks blindly).

## License & disclaimer

This is a personal-use client. The app does not host, transmit, or store any
audiovisual content — it loads the streamingcommunity website exactly as a
desktop Chrome browser would. All playback happens inside the on-device
WebView, with the site's own player. The ad-block list is a curated subset
of well-known ad networks; nothing in this app circumvents paywalls, DRM,
or any other access control.

The author has no affiliation with streamingcommunity, AstraeLabs, or
VibraVid.
