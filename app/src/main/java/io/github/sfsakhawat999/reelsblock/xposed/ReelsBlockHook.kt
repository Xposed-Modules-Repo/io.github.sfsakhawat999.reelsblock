package io.github.sfsakhawat999.reelsblock.xposed

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Executable
import java.lang.reflect.Modifier
import java.util.LinkedHashSet

/**
 * ReelsBlock — allow exactly ONE Short/Reel per visit, then freeze the
 * vertical pager until the user leaves the player screen.
 *
 * Headless module: blocking and the feedback toast are always ON; the app
 * is not visible in the launcher. Module metadata lives in META-INF/xposed.
 *
 * Targets (verified live on-device):
 *
 *  YouTube 21.42.243 / YouTube Music 9.41.53 / Android 16:
 *    Shorts (YouTube) and Samples (YT Music) pager =
 *      com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView
 *      (id "reel_recycler", child of androidx.swiperefreshlayout.SwipeRefreshLayout
 *       "reel_watch_refresher", inside Shell$HomeActivity / MusicActivity).
 *
 *  Instagram (single-activity app; everything obfuscated EXCEPT androidx):
 *    Reels player =
 *      androidx.viewpager2.widget.ViewPager2  with id "clips_viewer_view_pager"
 *      under instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout.
 *      IG renames its own classes every build, so we anchor ONLY on the stable
 *      androidx names + the resource id, and register the pager lazily.
 *
 *  Facebook 538.0.0.53.70 / 585.0.0.47.54 / 587.0.0.0.29 (Redex renames
 *  androidx methods and shifts resource ids between builds):
 *    Reels player = the outermost RecyclerView under a LithoView whose page
 *    child fills the screen. The same pager serves the Reels tab and the
 *    feed-opened ImmersiveActivity player (the tab additionally sits in the
 *    main ViewPager, so a ViewPager ancestor is NOT required).
 *    FB strips paging method names, so only framework overrides are hooked
 *    (layout, touch, scrollBy/scrollTo, detach) and the pager is identified
 *    by shape, never by id or class name.
 *
 *  Chromium browsers (com.android.chrome, com.brave.browser,
 *    com.microsoft.emmx, …) — browser support at the URL layer:
 *    a web page is not a pager we control, so there is no freeze rule here.
 *    Instead the tab delegate's URL-update callback is hooked, TabImpl.getUrl
 *    is watched for SPA pushState changes, and a 2 s poller re-checks every
 *    seen tab; the offender is simply NAVIGATED to a self-contained blocked
 *    data: page (no script runs on the target site, so Trusted Types /
 *    CSP — youtube.com — cannot refuse it; anchor discovery adapted from
 *    ChromeXt, JingMatrix, GPL-3.0). YouTube Shorts are treated exactly
 *    like reels: a hard block, no redirect.
 *
 * Enforcement (interceptor style — blocked = do not chain.proceed()):
 *   - Session = player attached → armed; detached → disarmed.
 *   - While armed, for the tracked pager (and its inner RecyclerView):
 *       onInterceptTouchEvent / onTouchEvent → false (human swipes die)
 *       setCurrentItem / scrollToPosition / smoothScrollToPosition → skipped
 *       SwipeRefresh-like parent → cannot intercept (no pull cycle)
 *   - Taps (like/comment/pause) still work: children receive touches normally;
 *     only the pager's own paging machinery is refused.
 */
class ReelsBlockHook : XposedModule() {

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val target = Engine.targetFor(param.packageName) ?: return
        // Main process only.
        if (!param.isFirstPackage) return
        Engine.bootstrap(
            module = this,
            classLoader = param.classLoader,
            target = target,
        )
    }
}

/**
 * Callback adapter for the libxposed interceptor model: one Kotlin class per
 * hook with a simple (Chain) -> Any? body.
 */
private class GateInterceptor(
    private val body: (XposedInterface.Chain) -> Any?,
) : XposedInterface.Hooker {
    override fun intercept(chain: XposedInterface.Chain): Any? {
        try {
            return body(chain)
        } catch (t: Throwable) {
            Engine.log("ReelsBlock: intercept error: $t")
            return try {
                chain.proceed()
            } catch (t2: Throwable) {
                null
            }
        }
    }
}

internal object Engine {

    const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    const val INSTAGRAM_PACKAGE = "com.instagram.android"
    const val FACEBOOK_PACKAGE = "com.facebook.katana"

    /**
     * Chromium-based browsers that keep the engine Java layer the browser
     * hooks target. Heavily modified forks (Opera, Samsung Internet, Yandex,
     * Aloha) rename or hide it, so they are not in scope. Anything else
     * (Firefox, WebView shells) lacks it entirely.
     */
    val CHROMIUM_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.brave.browser",
        "com.microsoft.emmx",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.ecosia.android",
        "org.chromium.chrome",
        "com.chromium.browser",
        "org.cromite.cromite",
        "org.bromite.bromite",
    )

    // -------- target model --------

    enum class TargetType { YOUTUBE, YOUTUBE_MUSIC, INSTAGRAM, FACEBOOK, BROWSER }

    class Target(
        val type: TargetType,
        val packageName: String,
        val toastText: String,
    )

    fun targetFor(packageName: String): Target? = when (packageName) {
        YOUTUBE_PACKAGE -> Target(TargetType.YOUTUBE, packageName,
            "That's your Short for now. Press back to leave.")
        YOUTUBE_MUSIC_PACKAGE -> Target(TargetType.YOUTUBE_MUSIC, packageName,
            "That's your Sample for now. Press back to leave.")
        INSTAGRAM_PACKAGE -> Target(TargetType.INSTAGRAM, packageName,
            "That's your Reel for now. Press back to leave.")
        FACEBOOK_PACKAGE -> Target(TargetType.FACEBOOK, packageName,
            "That's your Reel for now. Press back to leave.")
        // The blocked page itself is the feedback; no toast for the browser.
        in CHROMIUM_PACKAGES -> Target(TargetType.BROWSER, packageName, "")
        else -> null
    }

    // -------- immutable behavior flags (headless module) --------

    private const val TAG = "ReelsBlock"

    /** Master blocking switch — always on by design. */
    private const val BLOCKING_ENABLED = true

    /** Gentle toast when a blocked swipe is attempted — always on by design. */
    private val SHOW_TOAST = true

    // -------- per-process framework access + session state --------

    private var xposed: XposedInterface? = null

    @Volatile private var shortsSessionActive = false
    private var trackedPager: WeakReference<Any>? = null
    private var trackedInner: WeakReference<Any>? = null

    @Volatile private var blockToastShownThisSession = false
    @Volatile private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True when this particular call must be refused. */
    private fun blockedFor(instance: Any?): Boolean =
        BLOCKING_ENABLED && shortsSessionActive && instance != null &&
            (trackedPager?.get() === instance || trackedInner?.get() === instance)

    private fun beginSession(pager: Any, includeInner: Boolean = true) {
        trackedPager = WeakReference(pager)
        trackedInner = if (!includeInner) {
            null
        } else {
            (pager as? android.view.ViewGroup)?.let { vg ->
                (0 until vg.childCount)
                    .asSequence()
                    .map(vg::getChildAt)
                    .firstOrNull()
                    ?.takeIf { it is android.view.ViewGroup }
                    ?.let { WeakReference<Any>(it) }
            }
        }
        shortsSessionActive = true
        blockToastShownThisSession = false
    }

    private fun endSession() {
        if (!shortsSessionActive) return
        shortsSessionActive = false
        trackedPager = null
        trackedInner = null
    }

    private fun onBlockedAttempt(target: Target) {
        if (!SHOW_TOAST || blockToastShownThisSession) return
        blockToastShownThisSession = true
        val ctx = appContext ?: return
        mainHandler.post {
            runCatching {
                Toast.makeText(ctx, target.toastText, Toast.LENGTH_SHORT).show()
            }.onFailure { log("ReelsBlock: toast failed: ${it.message}") }
        }
    }

    /** Single log channel (logcat: `adb logcat -s ReelsBlock`) — warnings/errors only. */
    fun log(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: Throwable) {
        }
    }

    // -------- bootstrap --------

    fun bootstrap(
        module: XposedModule,
        classLoader: ClassLoader,
        target: Target,
    ) {
        xposed = module
        log("ReelsBlock: active in ${target.packageName}")

        runCatching { hookApplicationCreation(classLoader) }
            .onFailure { log("ReelsBlock: application hook failed: $it") }

        when (target.type) {
            TargetType.YOUTUBE, TargetType.YOUTUBE_MUSIC ->
                runCatching { installYoutubeHooks(classLoader, target) }
                    .onFailure { log("ReelsBlock: youtube hooks failed: $it") }
            TargetType.INSTAGRAM -> runCatching { installInstagramHooks(classLoader, target) }
                .onFailure { log("ReelsBlock: instagram hooks failed: $it") }
            TargetType.FACEBOOK -> runCatching { installFacebookHooks(classLoader, target) }
                .onFailure { log("ReelsBlock: facebook hooks failed: $it") }
            TargetType.BROWSER -> runCatching { installBrowserHooks(classLoader, target) }
                .onFailure { log("ReelsBlock: browser hooks failed: $it") }
        }
    }

    /** Capture the application context (needed for the feedback toast). */
    private fun hookApplicationCreation(classLoader: ClassLoader) {
        val instrumentationClass = Class.forName("android.app.Instrumentation", false, classLoader)
        val method = findDeclaredUpward(instrumentationClass, "callApplicationOnCreate", 1)
            ?: throw IllegalArgumentException("Instrumentation.callApplicationOnCreate not found")

        hookMethod(method, GateInterceptor { chain ->
            val result = chain.proceed()
            val app = chain.getArg(0) as? Application
            if (app != null && appContext == null) {
                appContext = app
            }
            result
        })
    }

    // =====================================================================
    // YouTube — explicit, name-anchored hooks (unchanged from v0.2)
    // =====================================================================

    private const val YT_REEL_PAGER_CLASS =
        "com.google.android.libraries.youtube.reel.internal.pager.ReelRecyclerView"
    private const val YT_RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val YT_SWIPE_REFRESH_LAYOUT_CLASS =
        "androidx.swiperefreshlayout.widget.SwipeRefreshLayout"
    private fun installYoutubeHooks(classLoader: ClassLoader, target: Target) {
        val pagerClass = runCatching { Class.forName(YT_REEL_PAGER_CLASS, false, classLoader) }
            .onFailure { log("ReelsBlock: ReelRecyclerView not found in this build: ${it.message}") }
            .getOrNull() ?: return

        ytHookSessionLifecycle(pagerClass)
        ytSetupGate(pagerClass, target, "onInterceptTouchEvent", 1, blockValue = false, toast = true)
        ytSetupGate(pagerClass, target, "onTouchEvent", 1, blockValue = false, toast = true)
        ytSetupGate(pagerClass, target, "fling", 2, blockValue = false, toast = false)
        ytSetupGate(pagerClass, target, "scrollToPosition", 1, blockValue = null, toast = false)
        ytSetupGate(pagerClass, target, "smoothScrollToPosition", 1, blockValue = null, toast = false)
        ytHookRefreshBarrier(classLoader)
    }

    /** attach → arm session, detach → disarm. */
    private fun ytHookSessionLifecycle(pagerClass: Class<*>) {
        hookMethodAtOn(pagerClass, "onAttachedToWindow", 0) { m ->
            hookAfter(m, pagerClass.name) { beginSession(it) }
        }
        hookMethodAtOn(pagerClass, "onDetachedFromWindow", 0) { m ->
            hookAfter(m, pagerClass.name) { endSession() }
        }
    }

    private inline fun hookAfter(
        m: Method,
        pagerClassName: String,
        crossinline action: (Any) -> Unit,
    ) {
        hookMethod(m, GateInterceptor { chain ->
            val result = chain.proceed()
            val v = chain.thisObject
            if (v != null && v.javaClass.name == pagerClassName) action(v)
            result
        })
    }

    /**
     * Hook [name] resolved from the pager class (walking up), plus the
     * RecyclerView base implementation when it is a different Method.
     * Interceptors no-op unless inside an armed session and the call
     * targets the tracked pager instance.
     */
    private fun ytSetupGate(
        pagerClass: Class<*>,
        target: Target,
        name: String,
        paramCount: Int,
        blockValue: Any?,
        toast: Boolean,
    ) {
        val methods = LinkedHashSet<Method>()
        findDeclaredUpward(pagerClass, name, paramCount)?.let { methods.add(it) }
        resolveBaseClass(pagerClass)?.let { base ->
            findDeclaredUpward(base, name, paramCount)?.let { methods.add(it) }
        }
        if (methods.isEmpty()) {
            log("ReelsBlock: '$name' not found (version drift)")
            return
        }
        for (m in methods) {
            val isBase = m.declaringClass.name != pagerClass.name
            hookMethod(m, GateInterceptor { chain ->
                val unrelated = isBase && chain.thisObject?.javaClass?.name != pagerClass.name
                if (blockedFor(chain.thisObject) && !unrelated) {
                    if (toast) onBlockedAttempt(target)
                    blockValue
                } else {
                    chain.proceed()
                }
            })
        }
    }

    private fun resolveBaseClass(pagerClass: Class<*>): Class<*>? {
        var c = pagerClass.superclass
        while (c != null) {
            if (c.name == YT_RECYCLER_VIEW_CLASS) return c
            c = c.superclass
        }
        return null
    }

    /** The shorts SwipeRefreshLayout wrapper must not catch refused swipes. */
    private fun ytHookRefreshBarrier(classLoader: ClassLoader) {
        val srl = runCatching { Class.forName(YT_SWIPE_REFRESH_LAYOUT_CLASS, false, classLoader) }
            .onFailure { log("ReelsBlock: SwipeRefreshLayout missing: ${it.message}") }
            .getOrNull() ?: return
        val m = findDeclaredUpward(srl, "onInterceptTouchEvent", 1) ?: return
        hookMethod(m, GateInterceptor { chain ->
            if (BLOCKING_ENABLED && shortsSessionActive) {
                false
            } else {
                chain.proceed()
            }
        })
    }

    // =====================================================================
    // Facebook — structural anchors (FB strips androidx method names)
    // =====================================================================
    //
    // FB 585.0.0.47.54 ships a Redex-rebuilt androidx: the obfuscated
    // RecyclerView subclass (X.038A) keeps only framework overrides, and all
    // paging helpers are renamed. We therefore anchor on:
    //   - androidx RecyclerView framework overrides (onLayout,
    //     onInterceptTouchEvent, onTouchEvent, scrollBy/scrollTo, detach)
    //   - the Reels pager's shape: an outermost RecyclerView under a LithoView
    //     with a full-screen page child. The Reels tab and the feed-opened
    //     ImmersiveActivity player use the same shape; only the tab has a
    //     ViewPager ancestor, so that is not part of the check.
    //
    // A session arms when that pager is laid out (and again on first touch),
    // so even the first swipe is refused. Vertical drags are refused from the
    // first movement past the touch slop (taps, likes and pausing still pass
    // through). Touching another list (Home feed, etc.) re-arms the feedback
    // toast for the next visit.

    private const val FB_RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

    /**
     * Reels pager children are real pages (match_parent) or a tiny placeholder
     * (1px in every observed build; 4px covers a 1dp placeholder on 3x screens).
     */
    private const val FB_PAGE_PLACEHOLDER_MAX_PX = 4

    private var fbRvClass: Class<*>? = null
    private var fbLithoViewClass: Class<*>? = null
    private var fbDownY = 0f
    private var fbSwallowGesture = false

    private fun installFacebookHooks(classLoader: ClassLoader, target: Target) {
        val rvClass = runCatching { Class.forName(FB_RECYCLER_VIEW_CLASS, false, classLoader) }
            .onFailure { log("ReelsBlock: FB RecyclerView missing: ${it.message}") }
            .getOrNull() ?: return
        fbRvClass = rvClass
        fbLithoViewClass = runCatching {
            Class.forName("com.facebook.litho.LithoView", false, classLoader)
        }.getOrNull()

        for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) {
            val m = findDeclaredUpward(rvClass, name, 1)
            if (m == null) {
                log("ReelsBlock: FB '$name' not found (version drift)")
                continue
            }
            val intercept = name == "onInterceptTouchEvent"
            hookMethod(m, GateInterceptor { chain -> fbGateTouch(chain, intercept, target) })
        }

        // Programmatic scrolling of the frozen pager.
        for (name in listOf("scrollBy", "scrollTo")) {
            val m = findDeclaredUpward(rvClass, name, 2) ?: continue
            hookMethod(m, GateInterceptor { chain ->
                if (isFbTracked(chain.thisObject)) null else chain.proceed()
            })
        }

        // Arm as soon as the pager has been laid out, so even the first swipe
        // is refused, regardless of the build's class names or resource ids.
        val onLayout = findDeclaredUpward(rvClass, "onLayout", 5)
        if (onLayout != null) {
            hookMethod(onLayout, GateInterceptor { chain ->
                val result = chain.proceed()
                val v = chain.thisObject as? android.view.ViewGroup
                if (v != null && trackedPager?.get() !== v && fbLooksLikeReelPager(v)) {
                    beginSession(v, includeInner = false)
                }
                result
            })
        } else {
            log("ReelsBlock: FB onLayout not found (version drift)")
        }

        // Leaving the player (or FB replacing it) ends the visit.
        val detach = findDeclaredUpward(rvClass, "onDetachedFromWindow", 0)
        if (detach != null) {
            hookMethod(detach, GateInterceptor { chain ->
                val v = chain.thisObject
                val result = chain.proceed()
                if (v != null && isFbTracked(v)) {
                    endSession()
                    fbSwallowGesture = false
                }
                result
            })
        } else {
            log("ReelsBlock: FB onDetachedFromWindow not found (version drift)")
        }
    }

    private fun isFbTracked(instance: Any?): Boolean =
        instance != null && trackedPager?.get() === instance

    private fun fbGateTouch(
        chain: XposedInterface.Chain,
        intercept: Boolean,
        target: Target,
    ): Any? {
        val view = chain.thisObject as? android.view.ViewGroup ?: return chain.proceed()
        val event = chain.getArg(0) as? android.view.MotionEvent ?: return chain.proceed()
        val action = event.actionMasked
        val tracked = trackedPager?.get()

        if (tracked !== view) {
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                // Touching another feed/list means the next Reels visit is new,
                // so the (once per visit) toast is allowed again.
                if (shortsSessionActive && !isInsideTrackedPager(view)) {
                    blockToastShownThisSession = false
                }
                if (intercept && fbLooksLikeReelPager(view)) {
                    beginSession(view, includeInner = false)
                }
            }
            return chain.proceed()
        }

        when (action) {
            android.view.MotionEvent.ACTION_DOWN -> {
                fbDownY = event.y
                fbSwallowGesture = false
                return chain.proceed()
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (!fbSwallowGesture &&
                    kotlin.math.abs(event.y - fbDownY) > fbTouchSlop(view)
                ) {
                    fbSwallowGesture = true
                    onBlockedAttempt(target)
                    fbCancelOriginal(chain, event)
                    return true
                }
                return if (fbSwallowGesture) true else chain.proceed()
            }
            android.view.MotionEvent.ACTION_UP,
            android.view.MotionEvent.ACTION_CANCEL -> {
                fbSwallowGesture = false
                return chain.proceed()
            }
            else -> return if (fbSwallowGesture) true else chain.proceed()
        }
    }

    /** Deliver a CANCEL so the frozen pager drops any half-started drag. */
    private fun fbCancelOriginal(chain: XposedInterface.Chain, event: android.view.MotionEvent) {
        runCatching {
            val cancel = android.view.MotionEvent.obtain(event)
            cancel.action = android.view.MotionEvent.ACTION_CANCEL
            try {
                chain.proceed(arrayOf<Any?>(cancel))
            } finally {
                cancel.recycle()
            }
        }
    }

    private fun fbTouchSlop(view: android.view.View): Int =
        android.view.ViewConfiguration.get(view.context).scaledTouchSlop

    private fun isInsideTrackedPager(view: android.view.View): Boolean {
        val tracked = trackedPager?.get() as? android.view.View ?: return false
        var parent: android.view.ViewParent? = view.parent
        var depth = 0
        while (parent != null && depth < 32) {
            if (parent === tracked) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /**
     * The Reels player (tab or feed-opened) is an outermost RecyclerView under a
     * Litho surface whose children are *pages*: a tiny placeholder plus one or
     * more full pages that all have the same height.
     *
     * Proportions only, so screen size and density do not matter:
     * - the pager covers at least 80% of the root height,
     * - a page is 85%–100% of the pager height (the tab page is inset by the
     *   bottom bar: ~2091px inside a 2151–2266px pager; the feed player's page
     *   fills the pager exactly),
     * - all pages are the same height and full width.
     *
     * Feed surfaces (profile posts, empty-state placeholders, photo viewer)
     * have mixed-height children or an oversized child, so they fail.
     */
    private fun fbLooksLikeReelPager(view: android.view.ViewGroup): Boolean {
        if (view.height <= 0 || view.childCount !in 1..8) return false

        val rootHeight = view.rootView?.height ?: 0
        if (rootHeight <= 0 || view.height < rootHeight * 0.8f) return false

        var placeholder = false
        var pageHeight = -1
        var pages = 0
        for (i in 0 until view.childCount) {
            val child = view.getChildAt(i)
            val h = child.height
            if (h <= FB_PAGE_PLACEHOLDER_MAX_PX) {
                placeholder = true
                continue
            }
            if (h < view.height * 0.85f) return false
            if (h > view.height + 2) return false
            if (child.width != view.width) return false
            if (pageHeight < 0) {
                pageHeight = h
            } else if (kotlin.math.abs(h - pageHeight) > 1) {
                return false
            }
            pages++
        }
        if (!placeholder || pages == 0) return false

        val rvClass = fbRvClass ?: return false
        val lithoClass = fbLithoViewClass
        var parent: android.view.ViewParent? = view.parent
        var hasLitho = false
        var depth = 0
        while (parent != null && depth < 40) {
            if (parent is android.view.View) {
                if (rvClass.isInstance(parent)) return false // inner list, not the pager
                if (lithoClass != null && lithoClass.isInstance(parent)) hasLitho = true
            }
            parent = parent.parent
            depth++
        }
        return hasLitho
    }

    // =====================================================================
    // Instagram — androidx + resource-id anchored (survives IG obfuscation)
    // =====================================================================

    private const val IG_PAGER_TITLE_ID = "clips_viewer_view_pager"
    private const val IG_VIEW_PAGER2_CLASS = "androidx.viewpager2.widget.ViewPager2"
    private const val IG_VIEW_PAGER2_INNER_CLASS =
        "androidx.viewpager2.widget.ViewPager2\$RecyclerViewImpl"
    private const val IG_RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"
    private const val IG_CLIPS_SRL_CLASS =
        "instagram.features.clips.viewer.ui.ClipsSwipeRefreshLayout"

    @Volatile private var instaPagerId: Int = -1

    /** Resolve the player pager's resource id inside the IG process (cached). */
    private fun resolveInstaPagerId(): Int {
        if (instaPagerId >= 0) return instaPagerId
        val ctx = appContext ?: return -1
        val id = runCatching {
            ctx.resources.getIdentifier(IG_PAGER_TITLE_ID, "id", INSTAGRAM_PACKAGE)
        }.getOrDefault(0)
        if (id == 0) {
            log("ReelsBlock: '$IG_PAGER_TITLE_ID' unresolved (version drift)")
            instaPagerId = 0
            return 0
        }
        instaPagerId = id
        return id
    }

    private fun installInstagramHooks(classLoader: ClassLoader, target: Target) {
        val pagerClass = runCatching { Class.forName(IG_VIEW_PAGER2_CLASS, false, classLoader) }
            .onFailure { log("ReelsBlock: ViewPager2 missing in this build: ${it.message}") }
            .getOrNull() ?: return

        // session lifecycle: any ViewPager2 with the clips_viewer_view_pager id
        val viewClass = Class.forName("android.view.View", false, classLoader)
        hookMethodAtOn(viewClass, "onAttachedToWindow", 0) { m ->
            hookMethod(m, GateInterceptor { chain ->
                val result = chain.proceed()
                val v = chain.thisObject
                val pagerId = resolveInstaPagerId()
                if (pagerId > 0 && v != null && pagerClass.isInstance(v) &&
                    (v as android.view.View).id == pagerId &&
                    trackedPager?.get() !== v
                ) {
                    beginSession(v)
                }
                result
            })
        }
        hookMethodAtOn(viewClass, "onDetachedFromWindow", 0) { m ->
            hookMethod(m, GateInterceptor { chain ->
                val result = chain.proceed()
                val v = chain.thisObject
                if (v != null && trackedPager != null && trackedPager?.get() === v) {
                    endSession()
                }
                // also ends if a detached view is the tracked inner recycler
                if (v != null && shortsSessionActive && trackedInner?.get() === v) {
                    endSession()
                }
                result
            })
        }

        // touch gates on the ViewPager2 itself
        for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) {
            val m = findDeclaredUpward(pagerClass, name, 1)
            if (m == null) {
                log("ReelsBlock: '$name' not found on ViewPager2 (version drift)")
                continue
            }
            hookMethod(m, GateInterceptor { chain ->
                val v = chain.thisObject
                val pagerId = resolveInstaPagerId()
                val isPlayerPager = pagerId > 0 && v != null &&
                    pagerClass.isInstance(v) && (v as android.view.View).id == pagerId
                if (shortsSessionActive && isPlayerPager) {
                    if (name == "onInterceptTouchEvent") onBlockedAttempt(target)
                    false
                } else {
                    chain.proceed()
                }
            })
        }

        // paging gate on the inner RecyclerView (parent === tracked pager).
        // Hook BOTH the androidx base class and VP2's inner subclass, since
        // the inner impl overrides the touch methods.
        val rvClass = runCatching { Class.forName(IG_RECYCLER_VIEW_CLASS, false, classLoader) }
            .getOrNull()
        val innerImplClass = runCatching { Class.forName(IG_VIEW_PAGER2_INNER_CLASS, false, classLoader) }
            .getOrNull()
        if (rvClass == null) {
            log("ReelsBlock: androidx RecyclerView missing (unexpected)")
            return
        }
        val rvGateTargets = LinkedHashSet<Method>()
        for (name in listOf("onInterceptTouchEvent", "onTouchEvent")) {
            findDeclaredUpward(rvClass, name, 1)?.let { rvGateTargets.add(it) }
            innerImplClass?.let { cls ->
                findDeclaredUpward(cls, name, 1)?.let { rvGateTargets.add(it) }
            }
        }
        for (m in rvGateTargets) {
            hookMethod(m, GateInterceptor { chain ->
                val v = chain.thisObject
                val parent = v?.let { (it as? android.view.View)?.parent }
                val isInnerOfPlayerPager =
                    shortsSessionActive && parent != null && parent === trackedPager?.get()
                if (isInnerOfPlayerPager) {
                    false
                } else {
                    chain.proceed()
                }
            })
        }

        // programmatic page switching must die too
        val setCurrent = pagerClass.declaredMethods.filter {
            it.name == "setCurrentItem" || it.name == "fakeDragBy" || it.name == "beginFakeDrag"
        }
        if (setCurrent.isEmpty()) {
            log("ReelsBlock: no programmatic paging methods found (version drift)")
        }
        for (m in setCurrent) {
            hookMethod(m, GateInterceptor { chain ->
                val v = chain.thisObject
                val pagerId = resolveInstaPagerId()
                val isPlayerPager = pagerId > 0 && v != null &&
                    pagerClass.isInstance(v) && (v as android.view.View).id == pagerId
                if (shortsSessionActive && isPlayerPager) {
                    null
                } else {
                    chain.proceed()
                }
            })
        }

        // pull-refresh barrier of the clips viewer must not catch verticals
        val srlGate = runCatching { Class.forName(IG_CLIPS_SRL_CLASS, false, classLoader) }
            .getOrNull()?.let { findDeclaredUpward(it, "onInterceptTouchEvent", 1) }
        if (srlGate != null) {
            hookMethod(srlGate, GateInterceptor { chain ->
                if (BLOCKING_ENABLED && shortsSessionActive) false else chain.proceed()
            })
        } else {
            log("ReelsBlock: ClipsSwipeRefreshLayout gate unresolved (version drift)")
        }
    }

    // =====================================================================
    // Chrome — URL-level hard block (no pager to freeze in a web page)
    //
    // Chromium keeps the Java-layer names we need (same anchors ChromeXt
    // uses), so we hook the tab delegate's URL-update callback and NAVIGATE
    // the tab to a self-contained data: page. Navigation happens in the
    // browser process, so no script needs to run on the target site and
    // Trusted Types / CSP (youtube.com) cannot refuse the block.
    // Injection mechanism adapted from ChromeXt (JingMatrix, GPL-3.0).
    // =====================================================================

    private const val CHROME_DELEGATE_CLASS =
        "org.chromium.chrome.browser.tab.TabWebContentsDelegateAndroidImpl"
    private const val CHROME_TAB_CLASS = "org.chromium.chrome.browser.tab.TabImpl"
    private const val CHROME_LOAD_PARAMS_CLASS =
        "org.chromium.content_public.browser.LoadUrlParams"
    private const val CHROME_GURL_CLASS = "org.chromium.url.GURL"
    private const val CHROME_WINDOW_CLASS = "org.chromium.ui.base.WindowAndroid"

    /** Set once the URL hooks are in place (per process). */
    @Volatile private var browserHooked = false

    // Resolved reflection anchors shared by the hooks and the SPA poller.
    @Volatile private var browserTabLoadUrl: Method? = null
    @Volatile private var browserTabGetUrl: Method? = null
    @Volatile private var browserReplaceField: java.lang.reflect.Field? = null
    @Volatile private var browserBackSteps = 2
    @Volatile private var browserLoadParamsCtor: Constructor<*>? = null
    @Volatile private var browserLoadParamsIntFirst = false

    /** Live TabImpl instances seen so far (UI thread only). */
    private val liveTabs = ArrayList<WeakReference<Any>>()
    private val lastInjectMs = HashMap<Int, Long>()
    private val lastInjectUrl = HashMap<Int, String?>()
    @Volatile private var browserPollerStarted = false

    private val BROWSER_SHORTS_RE =
        Regex("""^https?://(?:www\.|m\.)?youtube\.com/shorts/""")
    private val BROWSER_REEL_RE =
        Regex("""^https?://(?:[a-z0-9.-]+\.)?(?:instagram|facebook)\.com/reels?(?:[/?#]|$)""")

    private fun installBrowserHooks(classLoader: ClassLoader, target: Target) {
        // Fast path: older Chrome builds expose the tab classes through the
        // plain package classloader.
        if (browserTryInstall(classLoader, quiet = true)) return

        // Chrome 156+ keeps the browser code in split_chrome.apk behind a
        // lazily wired classloader, so the classloader seen at module init
        // cannot resolve the tab classes yet. ChromeXt's proven anchor:
        // WindowAndroid's Context-taking constructors fire once the browser
        // is up — resolve from the live context's classloader then.
        val windowClass = runCatching {
            Class.forName(CHROME_WINDOW_CLASS, false, classLoader)
        }.onFailure {
            log("ReelsBlock: Chrome WindowAndroid not found (version drift): ${it.message}")
        }.getOrNull() ?: return

        val ctors = windowClass.declaredConstructors.filter {
            val first = it.parameterTypes.firstOrNull()
            first != null && Context::class.java.isAssignableFrom(first)
        }
        if (ctors.isEmpty()) {
            log("ReelsBlock: WindowAndroid has no Context constructor (version drift)")
            return
        }
        for (ctor in ctors) {
            hookMethod(ctor, GateInterceptor { chain ->
                val result = chain.proceed()
                if (!browserHooked) {
                    val ctx = chain.getArg(0) as? Context
                    if (ctx != null) {
                        runCatching { browserTryInstall(ctx.classLoader, quiet = false) }
                            .onFailure { log("ReelsBlock: browser resolve failed: $it") }
                    }
                }
                result
            })
        }
    }

    /** Resolves the Chromium anchors and installs the URL hooks; false if anything is missing. */
    private fun browserTryInstall(classLoader: ClassLoader, quiet: Boolean): Boolean {
        val delegateClass =
            runCatching { Class.forName(CHROME_DELEGATE_CLASS, false, classLoader) }
                .onFailure {
                    if (!quiet) {
                        log("ReelsBlock: Chrome tab delegate not found (version drift): ${it.message}")
                    }
                }
                .getOrNull() ?: return false
        val tabClass = runCatching { Class.forName(CHROME_TAB_CLASS, false, classLoader) }
            .onFailure {
                if (!quiet) {
                    log("ReelsBlock: Chrome TabImpl not found (version drift): ${it.message}")
                }
            }
            .getOrNull() ?: return false
        val loadParamsClass =
            runCatching { Class.forName(CHROME_LOAD_PARAMS_CLASS, false, classLoader) }
                .onFailure {
                    if (!quiet) {
                        log("ReelsBlock: Chrome LoadUrlParams not found (version drift): ${it.message}")
                    }
                }
                .getOrNull() ?: return false

        // delegate -> tab: the delegate keeps exactly one TabImpl field.
        val tabField = delegateClass.declaredFields
            .firstOrNull { it.type == tabClass }
            ?.apply { isAccessible = true } ?: run {
            if (!quiet) log("ReelsBlock: delegate has no TabImpl field (version drift)")
            return false
        }

        // data: payload delivery — resolve once into fields so the
        // interceptor closures and the poller can share them:
        // TabImpl.loadUrl(LoadUrlParams) (signature-matched, name-independent)
        // and the shortest String-taking LoadUrlParams constructor
        // (LoadUrlParams(url, loaderType) wants 0).
        val loadParamsCtor = loadParamsClass.declaredConstructors
            .filter { it.parameterTypes.contains(String::class.java) }
            .minByOrNull { it.parameterTypes.size } ?: run {
            if (!quiet) log("ReelsBlock: LoadUrlParams has no String constructor (version drift)")
            return false
        }
        loadParamsCtor.isAccessible = true
        val ctorTypes = loadParamsCtor.parameterTypes
        if (ctorTypes.size > 2) {
            if (!quiet) log("ReelsBlock: unexpected LoadUrlParams constructor (version drift)")
            return false
        }
        browserLoadParamsCtor = loadParamsCtor
        browserLoadParamsIntFirst = ctorTypes.size == 2 && ctorTypes[0] == Int::class.java

        // LoadUrlParams.setShouldReplaceCurrentEntry(boolean) — R8 inlines the
        // setter on current Chrome, so reach for its backing field instead.
        // When exactly ONE boolean instance field survives on the class it is
        // the replace flag (everything else is inlined away); the blocked page
        // then REPLACES the Short's history entry, so Chrome's own back button
        // bypasses it. Otherwise the page's Back button jumps two entries.
        val boolFields = loadParamsClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == Boolean::class.javaPrimitiveType
        }
        if (boolFields.size == 1) {
            boolFields[0].isAccessible = true
            browserReplaceField = boolFields[0]
            browserBackSteps = 1
        }

        val tabLoadUrl = tabClass.declaredMethods
            .filter { it.parameterTypes contentEquals arrayOf(loadParamsClass) }
            .let { overloads ->
                overloads.firstOrNull { it.returnType != Void.TYPE } ?: overloads.firstOrNull()
            } ?: run {
            if (!quiet) log("ReelsBlock: TabImpl.loadUrl not found (version drift)")
            return false
        }
        tabLoadUrl.isAccessible = true
        browserTabLoadUrl = tabLoadUrl

        // TabImpl.getUrl(), returning a GURL: both the fallback URL source for
        // empty callback payloads and a live check that native triggers (the
        // omnibox polls it after SPA pushState URL changes too).
        val gurlClass = runCatching { Class.forName(CHROME_GURL_CLASS, false, classLoader) }
            .getOrNull()
        val tabGetUrl = gurlClass?.let { gurl ->
            tabClass.methods
                .firstOrNull { it.name == "getUrl" && it.returnType == gurl && it.parameterCount == 0 }
                ?: tabClass.methods.firstOrNull { it.returnType == gurl && it.parameterCount == 0 }
        }?.apply { isAccessible = true }
        browserTabGetUrl = tabGetUrl

        // URL-update callback: "onUpdateUrl(String)" on older builds,
        // "onUpdateTargetUrl(GURL)" on current Chromium (both survive R8
        // because native code calls them by name). Hook every one present —
        // repeated injections are idempotent.
        val urlCallbacks = LinkedHashSet<Method>()
        findDeclaredUpward(delegateClass, "onUpdateUrl", 1)?.let { urlCallbacks.add(it) }
        findDeclaredUpward(delegateClass, "onUpdateTargetUrl", 1)?.let { urlCallbacks.add(it) }
        if (urlCallbacks.isEmpty()) {
            if (!quiet) {
                log("ReelsBlock: no onUpdateUrl/onUpdateTargetUrl on tab delegate (version drift)")
            }
            return false
        }

        if (browserHooked) return true
        for (m in urlCallbacks) {
            hookMethod(m, GateInterceptor { chain ->
                val result = chain.proceed() // never swallow the navigation itself
                try {
                    val tab = tabField.get(chain.thisObject)
                    if (tab != null && browserTabGetUrl != null) {
                        rememberTab(tab)
                        var urlText = browserUrlText(chain.getArg(0))
                        if (urlText.isNullOrEmpty()) {
                            // Chrome hands the initial update an empty GURL;
                            // the current page URL lives on the tab instead.
                            urlText = runCatching {
                                browserUrlText(browserTabGetUrl!!.invoke(tab))
                            }.getOrNull()
                        }
                        if (!urlText.isNullOrEmpty()) {
                            val js = browserBlockScriptFor(urlText)
                            if (js != null) browserInject(tab, urlText, js)
                        }
                    }
                } catch (t: Throwable) {
                    log("ReelsBlock: browser inject failed: $t")
                }
                result
            })
        }

        // SPA navigations (History API push/replaceState) never reach the
        // URL-update callbacks, but they do change the tab's visible URL —
        // hook the native-polled getter as a live trigger and run a light
        // poller over seen tabs so nothing slips through between calls.
        if (tabGetUrl != null) {
            hookMethod(tabGetUrl, GateInterceptor { chain ->
                val result = chain.proceed()
                try {
                    val tab = chain.thisObject
                    if (tab != null) {
                        rememberTab(tab)
                        val spec = browserUrlText(result)
                        if (!spec.isNullOrEmpty()) {
                            val js = browserBlockScriptFor(spec)
                            if (js != null) browserInject(tab, spec, js)
                        }
                    }
                } catch (t: Throwable) {
                    log("ReelsBlock: browser url check failed: $t")
                }
                result
            })
            startBrowserPoller()
        }

        browserHooked = true
        log("ReelsBlock: Chromium reel URLs are being blocked")
        return true
    }

    /** Tracks a live tab instance for the poller (UI thread only). */
    private fun rememberTab(tab: Any) {
        for (ref in liveTabs) if (ref.get() === tab) return
        liveTabs.add(WeakReference(tab))
    }

    /** Loads the payload on [tab] for [url], throttled against self-fighting. */
    private fun browserInject(tab: Any, url: String, js: String) {
        val now = SystemClock.uptimeMillis()
        val key = System.identityHashCode(tab)
        val last = lastInjectMs[key] ?: 0L
        if (now - last < 2500) return
        // Same-URL repeats are the poller's retry for a first injection that
        // was dropped mid-page-load — keep the retry quick (the payload is
        // self-guarded and idempotent, so repeats are invisible).
        if (lastInjectUrl[key] == url && now - last < 5_000) return
        lastInjectMs[key] = now
        lastInjectUrl[key] = url
        // Delivery: a full navigation to a self-contained data: page. Browser
        // process navigations always work — no script is executed on the
        // target page, so Trusted Types / CSP (youtube.com) cannot refuse it.
        val loadUrl = browserTabLoadUrl ?: return
        val ctor = browserLoadParamsCtor ?: return
        val params = runCatching {
            if (ctor.parameterTypes.size == 1) {
                ctor.newInstance(js)
            } else if (browserLoadParamsIntFirst) {
                ctor.newInstance(0, js)
            } else {
                ctor.newInstance(js, 0)
            }
        }.getOrNull() ?: return
        // Never navigate from inside the callback: getUrl/onUpdateUrl run on
        // Chromium's navigation observer path, and a re-entrant loadUrl
        // trips a native CHECK (Chrome dies with SIGTRAP). One main-loop hop
        // moves the navigation out of that window.
        mainHandler.post {
            runCatching {
                browserReplaceField?.let { it.set(params, true) } // Back must skip the Short
                loadUrl.invoke(tab, params)
            }.onFailure { log("ReelsBlock: browser inject failed: ${it.cause ?: it}") }
        }
    }

    /**
     * Re-checks every known live tab on the main thread. UI thread only.
     */
    private fun startBrowserPoller() {
        if (browserPollerStarted) return
        browserPollerStarted = true
        val poll = object : Runnable {
            override fun run() {
                try {
                    val iter = liveTabs.iterator()
                    while (iter.hasNext()) {
                        val tab = iter.next().get()
                        if (tab == null) {
                            iter.remove()
                            continue
                        }
                        val spec =
                            runCatching { browserUrlText(browserTabGetUrl?.invoke(tab)) }.getOrNull()
                        if (!spec.isNullOrEmpty()) {
                            val js = browserBlockScriptFor(spec)
                            if (js != null) browserInject(tab, spec, js)
                        }
                    }
                } catch (t: Throwable) {
                    log("ReelsBlock: browser poll failed: $t")
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.postDelayed(poll, 3000)
    }

    /** GURL hides its text in a String field ("mSpec"); plain strings pass through. */
    private fun browserUrlText(arg: Any?): String? {
        if (arg is String) return arg
        if (arg == null) return null
        val fields = arg.javaClass.declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && it.type == String::class.java
        }
        val field = fields.firstOrNull { it.name == "mSpec" } ?: fields.firstOrNull()
            ?: return null
        field.isAccessible = true
        return field.get(arg) as? String
    }

    /** HTML for the blocked page that replaces a shorts/reel URL. */
    private fun browserBlockPageFor(url: String, backSteps: Int): String {
        val isShorts = BROWSER_SHORTS_RE.containsMatchIn(url)
        var host = ""
        try {
            host = java.net.URI(url).host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {}
        val title = if (isShorts) "Shorts are blocked here" else "Reels are blocked here"
        val sub = if (host.isNotEmpty()) "ReelsBlock \u00b7 $host" else "ReelsBlock"
        return (
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<meta name=\"color-scheme\" content=\"light dark\">" +
                "<title>ReelsBlock</title><style>" +
                ":root{color-scheme:light dark;--rb-bg:#f8f9fa;--rb-fg:#1a1c1e;" +
                "--rb-sub:#5f6368;--rb-btn:#1f6feb;--rb-btn-fg:#fff}" +
                "@media (prefers-color-scheme: dark){:root{--rb-bg:#101418;--rb-fg:#e8eaed;" +
                "--rb-sub:#9aa0a6}}" +
                "html,body{height:100%;margin:0;overflow:hidden;background:var(--rb-bg);" +
                "color:var(--rb-fg)}" +
                ".rb-overlay{position:fixed;inset:0;background:var(--rb-bg);color:var(--rb-fg);" +
                "display:flex;flex-direction:column;align-items:center;justify-content:center;" +
                "gap:10px;font-family:system-ui,Roboto,sans-serif;text-align:center;padding:24px}" +
                ".rb-title{font-size:20px;font-weight:600}" +
                ".rb-sub{font-size:13px;color:var(--rb-sub)}" +
                ".rb-back{margin-top:14px;padding:10px 22px;border:0;border-radius:999px;" +
                "background:var(--rb-btn);color:var(--rb-btn-fg);font-size:14px}" +
                "</style></head><body><div class=\"rb-overlay\">" +
                "<div class=\"rb-title\">$title</div>" +
                "<div class=\"rb-sub\">$sub</div>" +
                "<button class=\"rb-back\" onclick=\"history.go(-$backSteps)\">Back</button>" +
                "</div></body></html>"
            )
    }

    /** URL (a self-contained data: page) to navigate to, or null to leave it alone. */
    private fun browserBlockScriptFor(url: String): String? {
        val page: String
        if (BROWSER_SHORTS_RE.containsMatchIn(url)) page = browserBlockPageFor(url, browserBackSteps)
        else if (BROWSER_REEL_RE.containsMatchIn(url)) page = browserBlockPageFor(url, browserBackSteps)
        else return null
        return "data:text/html;charset=utf-8," + Uri.encode(page)
    }

    // -------- hook helpers --------

    private fun hookMethod(m: Executable, interceptor: XposedInterface.Hooker) {
        runCatching {
            xposed?.hook(m)?.intercept(interceptor)
        }.onFailure { log("ReelsBlock: hook failed on ${m.name}: $it") }
    }

    private fun hookMethodAtOn(
        clazz: Class<*>,
        name: String,
        paramCount: Int,
        attach: (Method) -> Unit,
    ) {
        val m = findDeclaredUpward(clazz, name, paramCount)
        if (m == null) {
            log("ReelsBlock: '$name' hook unavailable on ${clazz.name}")
            return
        }
        attach(m)
    }

    // -------- reflective helpers --------

    /** First declared method with [name] and [paramCount], walking up the hierarchy. */
    private fun findDeclaredUpward(clazz: Class<*>, name: String, paramCount: Int): Method? {
        var c: Class<*>? = clazz
        while (c != null && c != Object::class.java) {
            val found = c!!.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == paramCount
            }
            if (found != null) return found
            c = c.superclass
        }
        return null
    }
}
