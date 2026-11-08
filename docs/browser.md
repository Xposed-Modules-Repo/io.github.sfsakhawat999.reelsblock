# Browser support (Chromium + 1DM + Firefox, built in)

ReelsBlock covers Chromium-based browsers (Chrome, Brave, Edge, Vivaldi,
Kiwi, Ecosia, Chromium, Cromite, Bromite), the **1DM / 1DM+** built-in
browser (Lightning WebView engine) and **Firefox for Android** (GeckoView)
**without any companion module or userscript host**. The Xposed module itself
hooks the browser's Java layer from inside its process and blocks reel URLs;
each engine exposes a different anchor, described below.

## Engines

- **Chromium** (`com.android.chrome`, `com.brave.browser`,
  `com.microsoft.emmx`, …) — hooks Chromium's tab URL callback, the same
  layer userscript managers use.
- **1DM / 1DM+** (`idm.internet.download.manager` and
  `idm.internet.download.manager.plus`) — both bundle Lightning Browser;
  the module hooks its `acr.browser.lightning.view.LightningWebClient`.
- **Firefox** (`org.mozilla.firefox`, `org.mozilla.firefox_beta`,
  `org.mozilla.fenix`) — GeckoView, hooked through the public
  `GeckoSession` delegate SDK rather than any Fenix class name.

## What is blocked

| URL | Behaviour |
|---|---|
| `youtube.com/shorts/<id>` (`/shorts/feed` included) | Page stopped and replaced with a "Shorts are blocked here" page + Back button |
| `instagram.com/reels…`, `/reel/<id>` | Blocked page |
| `facebook.com/reel…`, `/reels…` (any subdomain) | Blocked page |

Hard block for all three sites — a Short in the browser is treated exactly
like a reel. On Chromium the page starts loading and is then replaced by the
blocked page; on Firefox the load is refused before the reel document commits.
Bytes may move for a moment before the block wins; network-level URL blocking
without a MITM VPN is not possible on Android. Chromium and Firefox are sent
to a self-contained `data:` page; 1DM keeps the URL and paints a full-screen
overlay, so its address bar and back history stay intact.

## How it works

### Chromium

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

### 1DM / 1DM+

Both apps ship Lightning Browser, a WebView browser, so there is no Chromium
tab layer to hook. The stable anchor is
`acr.browser.lightning.view.LightningWebClient`:

1. Lightning's `WebViewClient` receives `onPageStarted`,
   `doUpdateVisitedHistory` and `onPageFinished` for every main-frame
   navigation. The module hooks those overrides on the concrete class — hooking
   `android.webkit.WebViewClient` itself would miss them, because virtual
   dispatch goes straight to `LightningWebClient`.
2. For a matching URL the module paints a full-screen overlay into the current
   document with `WebView.evaluateJavascript`. The overlay is built with
   `createElement`/`textContent` rather than `innerHTML`, so a page with
   Trusted Types or a strict CSP (youtube.com) cannot refuse it. No `data:`
   navigation is used, so Lightning's address bar keeps the real URL and no
   extra history entry is created.
3. A 2 s `WebView.getUrl()` poller re-checks every seen WebView for History-API
   `pushState` changes and repaints the overlay if the page removed it. The
   overlay also pauses any playing `<video>`/`<audio>` element.
4. The overlay follows the browser theme. Its Back button removes the overlay
   and goes back through normal history, or opens the site root when there is
   no previous entry.

### Firefox

Firefox is not a Chromium fork and not a WebView shell: Fenix renders with
GeckoView, whose **public Java SDK** is the hook surface. No Fenix class name
or resource id is involved:

1. Fenix installs its listeners with
   `GeckoSession.setNavigationDelegate(NavigationDelegate)` and
   `GeckoSession.setProgressDelegate(ProgressDelegate)`. The module hooks both
   setters and wraps the incoming listener in a `java.lang.reflect.Proxy`,
   forwarding every callback to Fenix's real listener.
2. A main-frame load or redirect for a reel URL is **denied before the
   document commits** (`onLoadRequest` → `GeckoResult.deny()`). History-API
   (SPA) URL changes — opening a Short from the YouTube home page, for
   example — are caught on `onLocationChange`, with `onPageStart` as a
   backstop.
3. The blocked page is then loaded through `GeckoSession.Loader` with
   `REPLACE_HISTORY`, so the browser's own Back button skips the blocked reel,
   and with `BYPASS_LOAD_URI_DELEGATE`, so Fenix's URL policy cannot refuse
   the `data:` document. The page follows the browser theme (`prefers-color-scheme`)
   and its Back button falls back to the site root on a direct visit that has
   no history.
4. The address bar shows the `data:` URL of the blocked page (Firefox has no
   API to load HTML under another URL); the page itself is the readable
   "Shorts / Reels are blocked here" screen.

## Tested

Android 16 (arm64), YouTube Shorts URL. **Blocked:** Chrome 156, Chrome
Canary, Brave, Edge, Vivaldi, Kiwi, Ecosia, Cromite 153, ChromiumBrowser 151,
Bromite 108, 1DM+ 20.0, and Firefox 159.0.1. Instagram and Facebook Reel URLs
were verified in Firefox as well; the YouTube home → Shorts feed SPA
navigation and the blocked page's Back button were verified there too.
Opera, Samsung Internet, Yandex and Aloha rename or hide the tab delegate
class, so they are **not supported** and were removed from the scope. Not
tested: Chrome Beta/Dev, Opera GX, Firefox Beta/Nightly and Firefox Focus
(not installable here) and `org.chromium.chrome` (AOSP build, not installed).

## Enable

1. In your Xposed manager (Vector), give ReelsBlock scope for your browser —
   every Chromium browser listed above, both 1DM packages and the Firefox
   channels are in the fixed scope.
2. Force-stop the browser (or reboot).

Nothing else to install or configure; there is no toggle by design.

Browsers added to the fixed scope in a later module version can appear
**unticked** after the update (the manager keeps its own scope selection).
Open the module in Vector, use the scope menu's **Use what the module asks
for**, and apply. The list pins each app to "Fixed by the module", but the
tick is what actually enables injection.

## Limitations

- Supported engines only: Chromium, Lightning (1DM / 1DM+) and GeckoView
  (Firefox). Other WebView browsers (DuckDuckGo, Firefox Focus) are not in
  scope.
- Firefox: the blocked page is a `data:` URL, so the address bar shows that
  URL while the blocked page is open. Reels played inside a feed without a URL
  change (for example an inline player on the YouTube home page) are not
  caught — the block keys on the URL. A Firefox update may rename the
  GeckoView SDK methods; the module then logs `version drift` and leaves URLs
  alone.
- Chromium forks may rename or strip the engine classes; the module then logs
  `version drift` and leaves URLs alone instead of blocking.
- 1DM blocking uses `evaluateJavascript`, so it needs JavaScript enabled in
  the 1DM browser (it is on by default). If a future 1DM update renames
  `LightningWebClient`, the module logs `version drift`.
- Reels opened as an SPA modal while the URL stays on the feed (`instagram.com/`,
  `facebook.com/`) are not caught — the block keys on the URL.
- Chrome updates can rename or move the anchors (`TabWebContentsDelegateAndroidImpl`,
  `TabImpl.loadUrl`, `GURL`). The hook logs `version drift` warnings to
  `adb logcat -s ReelsBlock` when a class, field or method disappears.
- Hard block only — no per-browser configuration, no per-visit allowance.
