# ReelsBlock

**One Short. One Reel. Then the pager freezes.**

**Download:** [latest APK](https://github.com/Xposed-Modules-Repo/io.github.sfsakhawat999.reelsblock/releases)
· **Module page:** [modules.lsposed.org](https://modules.lsposed.org/module/io.github.sfsakhawat999.reelsblock)
· **Package:** `io.github.sfsakhawat999.reelsblock`

ReelsBlock is an Xposed module for rooted Android devices that stops doom-scrolling
in YouTube Shorts, YouTube Music Samples, Instagram Reels and Facebook Reels: you
get to watch *exactly one* short/sample/reel per visit — the one that opens — and
every attempt to swipe to the next one is refused until you leave the player.

YouTube search, the home feed, long videos, comments, likes — all untouched.
This module only disarms the vertical pager of the Shorts/Samples/Reels player.

---

## Features

- **YouTube Shorts** — the first Short opens normally; the vertical swipe pager is
  frozen until you exit Shorts.
- **YouTube Music Samples** — the same rule for the Samples tab.
- **Instagram Reels** — same rule for the Reels player.
- **Facebook Reels** — same rule for the Reels tab and for reels/videos opened
  from the feed. Facebook's build renames the AndroidX paging methods, so the
  pager is identified by shape instead of name; see
  [docs/facebook-reels.md](docs/facebook-reels.md).
- **Browser (Chromium)** — Shorts/Reel URLs are blocked inside Chromium-based
  browsers (Chrome, Brave, Edge, Vivaldi, Kiwi, Ecosia, Cromite, …): the module
  hooks Chromium's tab URL callback and overwrites the page with a plain
  blocked page — YouTube Shorts included, no redirect.
  Details: [docs/browser.md](docs/browser.md).
- **Taps still work** — like, comment, pause, and share keep functioning; only the
  paging machinery (swipe / fling / programmatic page change) is blocked.
- **One gentle toast per visit** — "That's your Short, Sample or Reel for now.
  Press back to leave."
- **Headless by design** — no launcher icon, no settings screen, no toggles.
  Blocking and the toast are always on. The module appears only in your
  Xposed manager.
- **Fixed scope** — declares `com.google.android.youtube`,
  `com.google.android.apps.youtube.music`, `com.instagram.android`,
  `com.facebook.katana` and the Chromium browser packages (Chrome
  stable/beta/dev/canary, Brave, Edge, Vivaldi, Kiwi, Ecosia, Chromium,
  Cromite, Bromite) as a static scope, so managers list them automatically
  ("Fixed by the module" in Vector). No manual scope selection.

## Requirements

- Rooted Android device with **Magisk** (or KernelSU) and an LSPosed-compatible
  framework that supports the **modern libxposed API** (API version ≥ 101).
  Tested with the **Vector** manager **2.2** (chsbuffer's LSPosed fork).
- **Android 16** (tested on OPPO, build `A059P`), minSdk 27.
- Target apps (tested versions):
  - YouTube **21.42.243**
  - YouTube Music **9.41.53** (Samples tab)
  - Instagram **453.0.0.57.79**
  - Facebook **538.0.0.53.70**, **585.0.0.47.54**, **587.0.0.0.29**
    (Reels tab and feed-opened reels)

## How it works

ReelsBlock is a *modern* libxposed module (no legacy `de.robv` classes are bundled):

| File | Purpose |
|---|---|
| `app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion=101`, `targetApiVersion=101`, `staticScope=true` |
| `app/src/main/resources/META-INF/xposed/scope.list` | `com.google.android.youtube`, `com.google.android.apps.youtube.music`, `com.instagram.android`, `com.facebook.katana` |
| `app/src/main/resources/META-INF/xposed/java_init.list` | Entry class `io.github.sfsakhawat999.reelsblock.xposed.ReelsBlockHook` |

### Session model

A *visit* begins when the player's vertical pager is attached to a window and ends
when it detaches. While a visit is active, only the tracked pager instance (and its
internal RecyclerView) is refused; every other RecyclerView/ViewPager in the app
keeps scrolling normally.

### Hook anchors

All anchors were verified live on-device. Obfuscated apps are anchored on **stable
androidx class names + resource ids**, never on app-specific obfuscated names:

**YouTube Shorts / YouTube Music Samples**
- Pager: `com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView`
  (id `reel_recycler`); YouTube Music's Samples tab uses the same class and
  structure as Shorts, so one hook covers both
- Gates: `onInterceptTouchEvent`, `onTouchEvent` (the primary gate); `fling`,
  `scrollToPosition`, `smoothScrollToPosition` where the build exposes them
  (YouTube Music 9.41.53 does not, but the touch gates still stop swipes)
- Barrier: `androidx.swiperefreshlayout.widget.SwipeRefreshLayout` (no pull cycle)

**Instagram Reels**
- Pager: stock `androidx.viewpager2.widget.ViewPager2` with resource id
  `clips_viewer_view_pager` (IG renames its own classes every build — the id and
  androidx names are stable)
- Gates: `onInterceptTouchEvent`, `onTouchEvent`, `setCurrentItem`,
  plus the inner RecyclerView (identified by `parent === tracked pager`)
- Barrier: `instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout`

**Facebook Reels** (details: [docs/facebook-reels.md](docs/facebook-reels.md))
- Pager: the outermost full-screen RecyclerView under a `LithoView` whose
  children are one tiny placeholder plus uniform-height full pages (the pager's
  page, not a feed list); this shape matched on 538.0.0.53.70, 585.0.0.47.54 and
  587.0.0.0.29 (Facebook renames the AndroidX paging methods and shifts ids, so
  no name/id anchor is relied on)
- Gates: `onLayout`, `onInterceptTouchEvent`, `onTouchEvent` (swallow after
  touch slop, taps pass through), `scrollBy`, `scrollTo`, `onDetachedFromWindow`
- Session ends when the pager detaches; the toast resets when another list is
  touched

Blocked calls simply **do not `proceed()`** in the interceptor chain — the pager's
own drag/paging pipeline never runs, while touch events still reach child views.

## Building

Requirements: JDK 17+, Android SDK 36, network access for the compile-only
libxposed API artifact.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> **Important:** the libxposed API dependency is `compileOnly`. The framework
> refuses modules that ship `de.robv`/Xposed API classes inside the APK. Do not
> change it to `implementation`.

## Installation

1. Download the latest APK from [Releases](https://github.com/Xposed-Modules-Repo/io.github.sfsakhawat999.reelsblock/releases)
   (or build it yourself — see [Building](#building)) and install it on the rooted device.
2. Open your Xposed manager → Modules → enable **ReelsBlock**.
3. Scope is fixed by the module itself — YouTube, YouTube Music, Instagram,
   Facebook and the Chromium browsers are added automatically.
4. Force-stop YouTube, YouTube Music, Instagram, Facebook and the browser (or
   reboot) so the hook is injected into a fresh process.

## Browser support

Built in — no extra module. Chromium-based browsers are all in the fixed scope:
Chrome (stable/beta/dev/canary), Brave, Edge, Vivaldi, Kiwi, Ecosia, Chromium,
Cromite and Bromite. Enable ReelsBlock for your browser in your Xposed manager
(already listed) and force-stop it. In the browser, Shorts/Reel URLs —
`youtube.com/shorts/…` (incl. `/shorts/feed`), `instagram.com/reels…`,
`instagram.com/reel/<id>`, `facebook.com/reel…` — are navigated away into a
plain "Shorts / Reels are blocked here" page with a Back button. No redirect
for YouTube: a Short in the browser is treated exactly like a reel. Works for
fresh visits and for SPA openers from a site's homepage.

The injection mechanism is adapted from [ChromeXt](https://github.com/JingMatrix/ChromeXt)
(JingMatrix, GPL-3.0). Verified on Chrome, Chrome Canary, Brave, Edge, Vivaldi,
Kiwi, Ecosia, Cromite, ChromiumBrowser and Bromite. Heavy forks (Opera,
Samsung Internet, Yandex, Aloha) rename the engine classes the hook needs and
are not supported. Full details and limitations: [docs/browser.md](docs/browser.md).

## Troubleshooting

- Watch live logs:
  ```bash
  adb logcat -s ReelsBlock
  ```
- Framework logs (Vector/LSPosed):
  ```bash
  su -c "ls /data/adb/lspd/log/"
  ```
- If a target app update breaks blocking, the module logs `(version drift)` for the
  anchor it could not resolve. Please open an
  [issue](https://github.com/Xposed-Modules-Repo/io.github.sfsakhawat999.reelsblock/issues)
  with your app version and the log lines.
- Browser not blocked after a module update? Open the module's scope page in
  Vector and apply **Use what the module asks for** — a browser added in a newer
  version can show up unticked even though the list says "Fixed by the module".

## Limitations

- Anchors are pinned to the tested app versions above; future updates may rename
  classes or resource ids. The module degrades gracefully (logs the drift, keeps
  running).
- **Facebook:** verified on 538.0.0.53.70, 585.0.0.47.54 and 587.0.0.0.29 on
  phones; both the Reels tab and feed-opened reels are blocked. Support is
  device-dependent: on tablets, foldables, landscape or other layouts where
  Facebook does not use this vertical pager, blocking may not apply. A future
  Facebook layout change may also need a re-probe.
- **YouTube Music Samples:** verified on 9.41.53. The Samples tab uses the same
  `ReelRecyclerView` pager as YouTube Shorts; if YouTube Music switches to a
  different player, a re-probe is needed.
- **Browser (Chromium):** a hard URL block, not the one-per-visit rule — a web
  page has no pager we can freeze. Only reel URLs are caught; reels opened as
  SPA modals while the URL stays on the feed are not. Browser updates may break
  the Chromium anchors until the module is updated; forks that strip or rename
  the engine classes log `(version drift)` and simply leave URLs alone. See
  [docs/browser.md](docs/browser.md).
- Only the vertical short/sample/reel pager is blocked — by design. Normal
  scrolling everywhere else stays untouched.
- This is a personal, educational project. Using Xposed modules may violate the
  terms of service of the target apps; use at your own risk.

## License

GNU General Public License v3.0 — see [LICENSE](LICENSE).

## Contributing

Issues and pull requests are welcome — see [CONTRIBUTING.md](CONTRIBUTING.md).
