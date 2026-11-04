# Contributing to ReelsBlock

Thanks for your interest. This project is small on purpose — keep changes focused,
defensive, and testable on a real device.

## Ground rules

1. **License** — contributions are accepted under GPL-3.0 (see `LICENSE`).
2. **Never bundle Xposed API classes.** The libxposed API must stay `compileOnly`.
   Frameworks refuse modules whose APK contains `de.robv.android.xposed.*` (or
   libxposed API) class definitions. Verify after building:
   ```bash
   ~/Android/Sdk/build-tools/*/dexdump app/build/outputs/apk/debug/app-debug.apk \
     | grep "Class descriptor.*Lde/robv"   # must print nothing
   ```
3. **Keep module metadata intact** — `META-INF/xposed/module.prop`,
   `scope.list`, and `java_init.list` are what make the module work as a modern
   libxposed module with a fixed scope.
4. **Headless & always-on** — no launcher icon, no settings toggles. Blocking and
   the toast are always enabled by design.
5. **Never crash the host app.** Every hook path must be wrapped defensively
   (`runCatching` + version-drift logging). A missed anchor must degrade to "no
   blocking", never to a YouTube/Instagram crash.

## Development setup

- JDK 17+
- Android SDK 36
- Network access on first build (fetches `io.github.libxposed:api` — compile only)

```bash
./gradlew assembleDebug
```

Install and test on a rooted device with a modern-API-capable framework
(Vector / recent LSPosed).

## Testing checklist

1. Install the APK, enable the module in the manager, force-stop the target app.
2. Open the target app and its short/reel player.
3. Verify:
   - the first short/reel plays normally;
   - swiping up/down does **not** advance;
   - one toast appears per visit;
   - like / comment / pause still work;
   - leaving and re-entering the player starts a fresh visit (one new short/reel).
4. Confirm normal app usage is untouched (home feed scrolling, search, long videos).
5. Check logs for unexpected errors:
   ```bash
   adb logcat -s ReelsBlock
   ```

## Adding support for a new app

1. Find the vertical pager in the live view hierarchy (e.g. `dumpsys activity top`,
   or `uiautomator dump` if the screen idles).
2. Prefer anchors that survive obfuscation:
   - stable androidx class names (`ViewPager2`, `RecyclerView`, `SwipeRefreshLayout`),
   - stable resource ids resolved at runtime via
     `Resources.getIdentifier(name, "id", pkg)`,
   - avoid app-specific class names unless the app does not rename classes.
3. Add a new target in `ReelsBlockHook.kt`:
   - a `TargetType` value and a `Target` entry in `targetFor(...)`,
   - an `installXxxHooks(...)` function following the existing patterns
     (session lifecycle, touch gates, inner-RecyclerView gate, barrier).
4. Add the package to `META-INF/xposed/scope.list`.
5. Document the tested app version in `README.md`.

## Reporting issues

Please include:

- Device model, Android version,
- framework name and version (Vector / LSPosed),
- target app name and version,
- a short description of expected vs. actual behavior,
- `adb logcat -s ReelsBlock` output around the occurrence
  (and framework logs from `/data/adb/lspd/log/` when relevant).

## Pull requests

- Keep the diff focused; one topic per PR.
- Match the existing code style (Kotlin, 4-space indent, defensive hooks).
- Describe how you tested on a real device, including app versions.
- Update `README.md` if user-visible behavior or anchors change.
