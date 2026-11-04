# Browser support (Chromium, built in)

ReelsBlock covers Chromium-based browsers — Chrome, Brave, Edge, Vivaldi,
Kiwi, Ecosia, Chromium, Cromite, Bromite — **without any companion module or
userscript host**. The Xposed module itself hooks Chromium's Java layer — the
same layer userscript managers use — and blocks reel URLs from inside the
browser process.

## What is blocked

| URL | Behaviour |
|---|---|
| `youtube.com/shorts/<id>` (`/shorts/feed` included) | Page stopped and replaced with a "Shorts are blocked here" page + Back button |
| `instagram.com/reels…`, `/reel/<id>` | Blocked page |
| `facebook.com/reel…`, `/reels…` (any subdomain) | Blocked page |

Hard block for all three sites — a Short in the browser is treated exactly
like a reel. The page starts loading and is then navigated away into the
blocked page — bytes move for a moment before the block wins; network-level
URL blocking without a MITM VPN is not possible on Android.

## How it works

Chromium's browser-side Java code carries a handful of stable anchors (the
same layer [ChromeXt](https://github.com/JingMatrix/ChromeXt) works on —
mechanism adapted from ChromeXt, JingMatrix, GPL-3.0):

1. `org.chromium.chrome.browser.tab.TabWebContentsDelegateAndroidImpl` receives
   `onUpdateUrl(String)` on older builds and `onUpdateTargetUrl(GURL)` on
   current ones whenever a tab commits a navigation. Both callback names
   survive R8 because native code calls them by name; we hook every one that
   exists.
2. SPA navigations (`history.pushState` — opening a reel from a site's
   homepage) never hit those callbacks, so `TabImpl.getUrl()` (the
   native-polled getter) is hooked as a live trigger and a light poller
   re-checks every tab seen so far every 2 seconds.
3. The delegate holds its `TabImpl` in a single typed field; we read it
   reflectively. `onUpdateTargetUrl` hands the initial URL update over as an
   empty `GURL`, so the tab's own `getUrl()` is used as the fallback source.
   Chrome 156+ keeps the browser code in `split_chrome.apk` behind a lazily
   wired classloader, so the module also hooks
   `org.chromium.ui.base.WindowAndroid`'s Context constructors (always
   present in the base APK) and resolves all anchors from the live context's
   classloader once the browser is up.
4. For a matching URL the tab is **navigated** to a self-contained
   `data:` page — "Shorts / Reels are blocked here" with a working Back
   button (`TabImpl.loadUrl(LoadUrlParams)`, found by signature, so renames
   don't matter). The navigation is posted to the main looper first: calling
   `loadUrl` re-entrantly from the URL callback trips a native Chromium
   check and kills the browser. No script is executed on the target site, so
   `require-trusted-types-for` / CSP (enforced on youtube.com, which defeats
   `javascript:`-URL script execution) cannot refuse the block. The blocked
   page is plain HTML/CSS and follows Chrome's theme (`prefers-color-scheme`),
   so it is light on a light browser and dark on a dark one. It replaces the
   Short's history entry where that flag is reachable, so Chrome's own back
   button lands before the Short; otherwise the page's Back button jumps back
   two entries.

## Tested

Android 16 (arm64), YouTube Shorts URL. **Blocked:** Chrome 156, Chrome
Canary, Brave, Edge, Vivaldi, Kiwi, Ecosia, Cromite 153, ChromiumBrowser 151,
Bromite 108. Opera, Samsung Internet, Yandex and Aloha rename or hide the tab
delegate class, so they are **not supported** and were removed from the scope.
Not tested: Chrome Beta/Dev and Opera GX (not installable here) and
`org.chromium.chrome` (AOSP build, not installed).

## Enable

1. In your Xposed manager (Vector), give ReelsBlock scope for your browser —
   every Chromium browser listed above is in the fixed scope, so it is ticked
   by default.
2. Force-stop the browser (or reboot).

Nothing else to install or configure; there is no toggle by design.

Browsers added to the fixed scope in a later module version can appear
**unticked** after the update (the manager keeps its own scope selection).
Open the module in Vector, use the scope menu's **Use what the module asks
for**, and apply. The list pins each app to "Fixed by the module", but the
tick is what actually enables injection.

## Limitations

- Chromium only. Firefox and WebView-based browsers (DuckDuckGo, Firefox Focus)
  do not ship the engine classes and are not in scope.
- Chromium forks may rename or strip the engine classes; the module then logs
  `version drift` and leaves URLs alone instead of blocking.
- Reels opened as an SPA modal while the URL stays on the feed (`instagram.com/`,
  `facebook.com/`) are not caught — the block keys on the URL.
- Chrome updates can rename or move the anchors (`TabWebContentsDelegateAndroidImpl`,
  `TabImpl.loadUrl`, `GURL`). The hook logs `version drift` warnings to
  `adb logcat -s ReelsBlock` when a class, field or method disappears.
- Hard block only — no per-browser configuration, no per-visit allowance.
