# Changelog

## 1.2.0 — 2026-11-09

### Added

- Support for Morphe-patched YouTube (`app.morphe.android.youtube`) and
  YouTube Music (`app.morphe.android.apps.youtube.music`). The patched builds
  keep the stock reel pager class names, so they use the same Shorts / Samples
  hooks as the stock apps.
- Both Morphe packages are included in the module's fixed scope.

### Changed

- Removed the redundant `SUMMARY` file.

## 1.1.0 — 2026-11-08

### Added

- Shorts/Reels URL blocking in Firefox for Android (GeckoView).
- Shorts/Reels URL blocking in the 1DM / 1DM+ built-in browser.

## 1.0.1 — 2026-11-08

### Changed

- README and in-app about page now point at the module repository.

## 1.0.0 — 2026-11-04

- First release: blocks swiping to the next short video in YouTube Shorts,
  YouTube Music Samples, Instagram Reels and Facebook Reels, plus Shorts/Reels
  URL blocking in Chromium-based browsers.
