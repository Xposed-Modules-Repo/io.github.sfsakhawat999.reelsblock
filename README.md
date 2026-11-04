# ReelsBlock

**One Short. One Reel. Then the pager freezes.**

ReelsBlock lets you watch exactly one Short, Sample or Reel per visit, then refuses to page to the next one until you leave the player. Taps keep working — like, comment, pause and share are untouched — and the rest of each app behaves normally.

It also hard-blocks reel URLs inside Chromium-based browsers.

- **Headless by design** — no launcher icon, no settings screen, no toggle. Blocking is always on; the module only appears in your Xposed manager.
- **No network permission** — the module never collects or sends anything.

## Covered apps

| App | Tested with |
|---|---|
| YouTube Shorts | 21.42.243 |
| YouTube Music Samples | 9.41.53 |
| Instagram Reels | 453.0.0.57.79 |
| Facebook Reels | 538, 585 and 587 |
| Chromium browsers | see below |

## Browser support

Shorts/Reels URLs are blocked inside Chromium-based browsers — no companion module:

`youtube.com/shorts/…` (including `/shorts/feed`), `instagram.com/reels…`, `instagram.com/reel/<id>` and `facebook.com/reel…` are replaced with a plain **"Shorts / Reels are blocked here"** page with a Back button. YouTube Shorts get no redirect. The page follows the browser's light/dark theme.

**Verified on Android 16 (arm64):** Chrome 156, Chrome Canary, Brave, Edge, Vivaldi, Kiwi, Ecosia, Cromite 153, ChromiumBrowser 151 and Bromite 108. Chrome Beta/Dev are in the fixed scope but were not tested.

Opera, Samsung Internet, Yandex and Aloha are **not supported** — those forks rename or hide the engine classes the browser hook needs.

## Requirements

- Rooted Android 12+ device with Magisk (or KernelSU).
- An LSPosed-compatible manager supporting the modern libxposed API (≥ 101). Tested with Vector 2.2.
- The module declares a fixed scope for all supported apps, so targets don't need to be ticked by hand.

## Install

1. Download the latest APK from [Releases](https://github.com/Xposed-Modules-Repo/io.github.sfsakhawat999.reelsblock/releases).
2. Install it and enable **ReelsBlock** in your Xposed manager — it appears only there, not in the app drawer.
3. Force-stop YouTube, YouTube Music, Instagram, Facebook and your browser (or reboot).
4. If a browser is listed as "Fixed by the module" but not ticked, open the module's scope page and apply **Use what the module asks for**.

## Limitations

- The browser block is a hard URL block, not the one-per-visit rule: a web page has no pager to freeze. Reels opened as SPA modals while the URL stays on the feed are not caught.
- App updates can rename hooks; the module then logs `version drift` in `adb logcat -s ReelsBlock` and degrades gracefully instead of crashing.
- Facebook support is device-dependent: it works on phones with the vertical Reels pager, but may not apply on tablets, foldables or landscape layouts.

## Source and license

Source code, issues and release builds: **https://github.com/sfsakhawat999/ReelsBlock**

GPL-3.0. Browser hooks are adapted from [ChromeXt](https://github.com/JingMatrix/ChromeXt) (JingMatrix, GPL-3.0).
