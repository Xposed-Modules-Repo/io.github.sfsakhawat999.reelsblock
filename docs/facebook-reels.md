# Facebook Reels — implementation notes

Status: **implemented and verified on three Facebook builds** for both the
**Reels tab** and **reels/videos opened from the feed**:

| Build | Note |
|---|---|
| **538.0.0.53.70** | ~1 year old; pager id `0x7f0a2307`, class `X.2tg` |
| **585.0.0.47.54** | original implementation target; pager id `0x7f0a2306`, class `X.038A` (ids recorded for reference only — the gate does not use them) |
| **587.0.0.0.29** | newer build; same shape |

The ID and class names differ across builds; only the **shape** is stable.

Everything below was confirmed live on-device with `adb logcat -s ReelsBlock`
and temporary probes. Facebook ships a Redex-rebuilt AndroidX, so this document
records *why* the implementation is shaped the way it is.

## What Facebook does to AndroidX

Facebook rewrites the app with Redex. In the shipped APK:

- `androidx.recyclerview.widget.RecyclerView` still exists, but **every method
  that is not an override of a framework method has been renamed** to `A00`,
  `A01`, … `A1X`, `GnA`, `Jnc`, etc.
- `androidx.viewpager.widget.ViewPager` is the same: `setCurrentItem`,
  `setCurrentItemInternal`, `dispatchOnPageSelected`, `onPageScrolled` are gone
  or renamed; only framework overrides (`onAttachedToWindow`, `onTouchEvent`,
  `onInterceptTouchEvent`, `computeScroll`, `draw`, …) keep their names.
- Resource names are stripped too: `getResourceEntryName()` returns
  `(name removed)`. Only numeric ids are available.

Consequences:

- **Cannot** hook `setCurrentItem`, `fling`, `smoothScrollBy`,
  `scrollToPosition`, `setLayoutManager`, `setAdapter`, `addOnScrollListener`,
  `dispatchOnPageSelected`, … — those names do not exist in the target process.
- **Can** hook the framework overrides above. They are stable across Redex
  rebuilds because they override `android.view.View` / `ViewGroup` methods.
- Class names of Facebook's own subclasses (`X.038A`, `X.02Bp`, …) and numeric
  resource ids change between Facebook builds, so the gate does not use either:
  it identifies the pager by shape only.

## Verified layouts

- Main shell activity: `com.facebook.katana.activity.FbMainTabActivity`
  (`dumpsys window` misleadingly reports `LoginActivity` as resumed).
- Main tab pager: obfuscated subclass `X.02Bp` of
  `androidx.viewpager.widget.ViewPager`, id `0x7f0a205b`.
- Reels tab pager: RecyclerView subclass `X.038A`, id `0x7f0a2306`, inside a
  `com.facebook.litho.LithoView` chain, then `FrameLayout` with
  `android:id/content`, then the tab `ViewPager`. Its page is `1080x2091`
  inside a pager that is `1080x2151`–`1080x2266` (the bottom bar changes the
  pager height, not the page), so page ≠ pager height on the tab.
- Home feed list: a different RecyclerView (`X.02ny`, id `0x102000a` = `android:id/list`).
- A vertical swipe in the Reels tab is handled by the Reels RecyclerView itself
  (the probe saw `touchDown` on it, no `fling` call — the paging helpers are
  renamed and invisible to name-based hooks).
- Reels/videos opened from the feed open `com.facebook.katana.immersiveactivity.ImmersiveActivity`
  and reuse the same pager: same class and id as the tab player on that build,
  same ancestors up to the container above it — but **without** the tab
  ViewPager above it.
- The Reels pager's **child 0 is a 1-pixel LithoView placeholder**
  (`1080x1`); the actual reel page is the other child. On the tab the page is
  `1080x2091`; in `ImmersiveActivity` it is `1080x2176`, exactly the pager
  height. A check on child 0 alone is wrong.
- On **538.0.0.53.70** the same shape appears with different names: pager
  `X.2tg`, id `0x7f0a2307`, ancestors `X.2lA` → `ComponentHost` → `LithoView` →
  `FrameLayout` → `X.5KC(0x7f0a06d8)` → `X.5KC(0x7f0a2306)` → … → decor.
  The numeric ids shifted (`2306` is now an *ancestor*, `2307` is the pager),
  which is exactly why the gate must not depend on ids.
- The same shape and gate also worked on the newer build (587.0.0.0.29). In all
  three builds the first Reels-tab swipe is refused and feed-opened reels are
  refused.

## Identification (structural, version tolerant)

`fbLooksLikeReelPager(view)` returns true when **all** of these hold:

1. It is an **outermost** RecyclerView (no RecyclerView ancestor, which
   excludes Litho's inner lists inside feed posts),
2. `view.height >= 80%` of the root view height,
3. `1 <= childCount <= 8` (a pager, not a feed list),
4. its children are **pages**: at least one tiny placeholder (≤4px, the
   observed placeholder is 1px) and at least one full page,
5. every page is **85%–100% of the pager height**, **full width**, and every
   page has the **same height** (within 1px),
6. it has a `com.facebook.litho.LithoView` ancestor (both the Reels tab and
   `ImmersiveActivity` satisfy this; the Home feed RecyclerView does not).

Only proportions are used, so screen size and density do not matter. The
85%–100% window exists because the tab page is inset by the bottom bar
(2091/2266 ≈ 92%) while the feed player's page fills the pager exactly.

No `ViewPager` ancestor and **no resource id** is used: the tab player has a
ViewPager ancestor, the feed-opened `ImmersiveActivity` player does not, and ids
shift between builds.

The check is deliberately tolerant of renamed classes and shifted ids, and it
was confirmed on 538.0.0.53.70, 585.0.0.47.54 and 587.0.0.0.29 (tab and
feed-opened players in all three).

### False positives fixed (585.0.0.47.54)

The first version only required *one child ≥90% of the list height*. Every
profile/page feed is also a full-screen Litho RecyclerView, so it matched
whenever it had few/tall children, e.g.:

| Surface | Children (px) | List height | Why it matched |
|---|---|---|---|
| profile feed at rest | 1823, 1398, 1996 | 2151 | 1996 ≥ 90% |
| profile first load (empty) | 708, 0, 62, 2392 | 2330 | 2392 ≥ 90% |
| full-screen photo viewer | 4760, 1 | 2091 | 4760 ≥ 90% |

None of these has a tiny placeholder plus uniform pages that fit inside the
list (the `2392`/`4760` children are *taller* than their list). The real pager is
`X.038A#0x7f0a2306` with `[1, 2091]` (tab) or `[1, 2176]` (feed), whereas the
generic feed list on that build is `X.038A#0x7f0a2154`. The rule is now the
page-stack shape in points 4–5 above; profiles and the photo viewer no longer
arm the gate.

The check runs:

- after every `onLayout` of a RecyclerView, so the gate arms **before** the
  first swipe (this was the first-swipe hole on other builds), and
- on `ACTION_DOWN` as a fallback when the view was already laid out.

## Touch gating

Hooks (all resolved with `findDeclaredUpward`, so only real framework overrides):

| Method | Behaviour on the tracked pager |
|---|---|
| `onLayout` | arms the session as soon as the pager is laid out |
| `onInterceptTouchEvent` | decides whether the gesture is a tap or a drag |
| `onTouchEvent` | same decision when the pager itself is the touch target |
| `scrollBy`, `scrollTo` | skipped (no programmatic scrolling) |
| `onDetachedFromWindow` | ends the session and clears gesture state |

Gesture algorithm (`fbGateTouch`):

1. `ACTION_DOWN` on a matching pager → `beginSession(pager)` (no inner view is
   tracked for Facebook) and remember `downY`.
2. `ACTION_MOVE`:
   - before the movement exceeds `ViewConfiguration.scaledTouchSlop` → let the
     original run (so taps, like/comment/pause work),
   - at the first move beyond slop → mark the gesture swallowed, show the toast
     (once per session), deliver an `ACTION_CANCEL` to the original pager so it
     drops any half-started drag, then return `true` for the rest of the gesture.
3. `ACTION_UP` / `ACTION_CANCEL` → reset gesture state.

Returning `true` from `onInterceptTouchEvent` after cancelling the original
means the pager never receives the MOVE stream, so **no scroll, fling or snap**
can start — regardless of which renamed helper Facebook would have called.

Taps pass through because no interception happens before the touch slop.

### Session / toast semantics

- Session arms when the Reels pager is laid out (and on its first touch).
- It ends when that pager detaches (or is replaced).
- The toast latch is reset when the user touches **another** RecyclerView that
  is not a descendant of the tracked pager (Home feed, friends, …). That models
  "a new visit" even though the tab ViewPager's page-change callbacks cannot be
  hooked.
- Blocking does **not** depend on the session ending: while the tracked pager
  exists, it stays frozen.

## Files

- Hook code: `app/src/main/java/io/github/sfsakhawat999/reelsblock/xposed/ReelsBlockHook.kt`
  (`installFacebookHooks`, `fbGateTouch`, `fbLooksLikeReelPager`).
- Scope: `app/src/main/resources/META-INF/xposed/scope.list` includes
  `com.facebook.katana`.

## Diagnostics

```bash
adb logcat -s ReelsBlock
```

- `ReelsBlock: active in com.facebook.katana` — module injected and running.
- `... 'onInterceptTouchEvent' not found (version drift)` — anchor changed; the
  gate is not installed.
- No toast and free scrolling usually means the Reels pager failed the
  structural check (or the module is not injected at all).

### After reinstalling Facebook

Uninstalling/reinstalling Facebook assigns a **new UID**. Vector's running
daemon can keep the old UID→scope cache, so it hands the new process the
injection binder but never loads the module (no `active in ...` line). Fix:
toggle ReelsBlock off/on in the manager, or reboot.

```bash
# CLI equivalent (root)
su -c "/data/adb/lspd/cli modules disable io.github.sfsakhawat999.reelsblock"
su -c "/data/adb/lspd/cli modules enable io.github.sfsakhawat999.reelsblock"
```

## Known gaps

None for Facebook on the three verified builds. The check is proportional
(page/pager ratios, root-height ratio), so other resolutions and densities work;
a tablet/foldable/landscape layout that does not use this vertical pager would
simply not arm. Future builds may still change the layout (for example, drop the
Litho wrapper, the tiny placeholder, or the uniform pages); the module then logs
`(version drift)` or simply does not arm, and the shape must be re-probed.

No resource id is used anywhere in the Facebook gate.

## Re-probing checklist

When a version changes or a new player needs an anchor:

1. Install a probe build that logs, on `RecyclerView.onAttachedToWindow`,
   `onLayout` and `ACTION_DOWN`: concrete class, numeric id, parents
   (8–10 levels), `height`, `childCount`, every child's height, the nearest
   `LithoView` ancestor, and the activity from `onResume`.
2. Ask the user to open the target player and swipe once (do not script UI
   actions on the device).
3. `adb logcat -d -s ReelsBlock` and compare with the verified layout above.
4. Add the new shape/activity to `fbLooksLikeReelPager` (or a second predicate
   for the feed player) and retest.
