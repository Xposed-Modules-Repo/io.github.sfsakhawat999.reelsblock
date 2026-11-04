package io.github.sfsakhawat999.reelsblock

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Headless module: not shown in the app drawer (no LAUNCHER category).
 * This page opens only from the module manager's "Module settings" entry.
 * It has no controls — blocking and the toast are always on by design.
 */
class MainActivity : Activity() {

    private val isNight: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val bgColor: Int get() = if (isNight) 0xFF0F1115.toInt() else 0xFFF6F6F7.toInt()
    private val cardColor: Int get() = if (isNight) 0xFF1A1D22.toInt() else 0xFFFFFFFF.toInt()
    private val textColor: Int get() = if (isNight) 0xFFF3F4F6.toInt() else 0xFF111318.toInt()
    private val secondaryColor: Int get() = if (isNight) 0xFF9AA0A6.toInt() else 0xFF5F6368.toInt()
    private val supportedText: Int get() = if (isNight) 0xFF4ADE80.toInt() else 0xFF15803D.toInt()
    private val supportedBg: Int get() = if (isNight) 0x3322C55E else 0x1F16A34A

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()

        window.setBackgroundDrawable(ColorDrawable(bgColor))
        applyEdgeToEdge()

        val root = ScrollView(this).apply {
            setBackgroundColor(bgColor)
            isFillViewport = true
            // Keep scrolled content clipped below the status bar padding
            clipToPadding = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(20), dip(28), dip(20), dip(28))
        }
        root.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        content.addView(header())

        content.addView(
            card(topMarginDp = 24) {
                addView(sectionTitle("HOW IT WORKS"))
                addView(step(1, "Open Shorts or Reels", "The first one plays like normal."))
                addView(step(2, "Try to swipe up", "The pager refuses — a toast explains why."))
                addView(step(3, "Leave the player", "Your next visit starts fresh with one new short."))
            },
        )

        content.addView(
            card {
                addView(sectionTitle("SUPPORTED APPS"))
                addView(appRow("YouTube Shorts", "Tested with YouTube 21.42.243"))
                addView(divider())
                addView(appRow("YouTube Music Samples", "Tested with YouTube Music 9.41.53"))
                addView(divider())
                addView(appRow("Instagram Reels", "Tested with Instagram 453.0.0.57.79"))
                addView(divider())
                addView(appRow("Facebook Reels", "Tested with Facebook 538, 585 and 587"))
                addView(divider())
                addView(
                    appRow(
                        "Chromium browsers",
                        "Chrome, Brave, Edge, Vivaldi, Kiwi, Ecosia, Cromite, …",
                    ),
                )
            },
        )

        content.addView(
            card {
                addView(sectionTitle("IF IT EVER STOPS WORKING"))
                addView(
                    body(
                        "Hooks are anchored to the tested app versions. If an app update " +
                            "breaks blocking, the module writes a version-drift warning to " +
                            "logcat — include it when you open an issue.",
                    ),
                )
            },
        )

        content.addView(githubButton())
        content.addView(
            caption("github.com/sfsakhawat999/ReelsBlock", dip(10)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )
        content.addView(
            caption(
                "v${versionName()}  ·  GPL-3.0  ·  Open from your Xposed manager",
                dip(24),
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            },
        )

        setContentView(root)
        root.requestApplyInsets()
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            root.setPadding(0, top, 0, bottom)
            insets
        }
    }

    // -------- sections --------

    private fun header(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_launcher)
            },
            LinearLayout.LayoutParams(dip(56), dip(56)),
        )
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(14), 0, 0, 0)
        }
        titles.addView(text("ReelsBlock", 30f, textColor, bold = true))
        titles.addView(
            text("One Short. One Reel. Then the pager freezes.", 14f, secondaryColor).apply {
                setPadding(0, dip(4), 0, 0)
            },
        )
        row.addView(titles)
        return row
    }

    private fun step(index: Int, title: String, detail: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dip(12), 0, 0)
        }
        row.addView(
            TextView(this).apply {
                text = index.toString()
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(ACCENT, 999)
            },
            LinearLayout.LayoutParams(dip(26), dip(26)),
        )
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(12), 0, 0, 0)
        }
        copy.addView(text(title, 15f, textColor, bold = true))
        copy.addView(
            text(detail, 13f, secondaryColor).apply {
                setPadding(0, dip(2), 0, 0)
            },
        )
        row.addView(copy)
        return row
    }

    private fun appRow(name: String, version: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dip(10), 0, dip(10))
        }
        val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(text(name, 15f, textColor, bold = true))
        copy.addView(
            text(version, 13f, secondaryColor).apply {
                setPadding(0, dip(2), 0, 0)
            },
        )
        row.addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(
            TextView(this).apply {
                text = "Supported"
                textSize = 12f
                setTextColor(supportedText)
                typeface = Typeface.DEFAULT_BOLD
                background = rounded(supportedBg, 999)
                setPadding(dip(10), dip(4), dip(10), dip(4))
            },
        )
        return row
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(if (isNight) 0xFF262A30.toInt() else 0xFFEDEEF0.toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dip(1),
        ).apply { topMargin = dip(2); bottomMargin = dip(2) }
    }

    private fun githubButton(): LinearLayout {
        val button = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dip(16), dip(16), dip(16), dip(16))
            background = RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF),
                rounded(ACCENT, 16),
                null,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { openRepo() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dip(20) }
        }
        button.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_github)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dip(22), dip(22)),
        )
        button.addView(
            TextView(this).apply {
                text = "View source on GitHub"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dip(10) },
        )
        return button
    }

    // -------- helpers --------

    private fun card(topMarginDp: Int = 14, build: LinearLayout.() -> Unit): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dip(18), dip(16), dip(18), dip(18))
            background = rounded(cardColor, 20)
            build()
        }
        c.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dip(topMarginDp) }
        return c
    }

    private fun sectionTitle(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, 0, 0, dip(2))
    }

    private fun body(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(secondaryColor)
        setPadding(0, dip(8), 0, 0)
        setLineSpacing(dip(3).toFloat(), 1f)
    }

    private fun caption(value: String, topPadding: Int): TextView = TextView(this).apply {
        text = value
        textSize = 12f
        setTextColor(secondaryColor)
        setPadding(0, topPadding, 0, 0)
    }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dip(radiusDp).toFloat()
    }

    private fun openRepo() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, REPO_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            val appearance = if (isNight) 0 else
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                appearance,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            if (!isNight) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
            window.statusBarColor = Color.TRANSPARENT
        }
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            packageManager.getPackageInfo(packageName, 0)
        }
        info.versionName
    }.getOrNull() ?: "0.3.1"

    private fun dip(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ACCENT = 0xFFEF4444.toInt()
        const val REPO_URL = "https://github.com/sfsakhawat999/ReelsBlock"
    }
}
