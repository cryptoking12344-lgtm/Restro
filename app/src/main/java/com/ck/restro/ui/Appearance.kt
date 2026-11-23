package com.ck.restro.ui

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import com.ck.restro.R

/** Theme and accent colour (per device) — the same looks as Orbit EQ. */
object Appearance {
    const val THEME_ORBIT = 0
    const val THEME_MIDNIGHT = 1
    const val THEME_GLASS = 2

    val THEME_NAMES = arrayOf("Orbit", "Midnight", "Liquid Glass")
    val THEME_ORDER = intArrayOf(THEME_GLASS, THEME_ORBIT, THEME_MIDNIGHT)
    val THEME_DESCS = arrayOf(
        "Deep navy with violet and cyan.",
        "Pure black for OLED screens.",
        "Frosted see-through panels over soft colour taken from your photo.",
    )
    val ACCENT_NAMES = arrayOf("Violet", "Cyan", "Mint", "Amber", "Rose")
    val ACCENT_COLORS = intArrayOf(0xFFA78BFA.toInt(), 0xFF22D3EE.toInt(), 0xFF5EEAD4.toInt(), 0xFFFBBF24.toInt(), 0xFFFB7185.toInt())
    private val ACCENT_STYLES = intArrayOf(R.style.Accent_Violet, R.style.Accent_Cyan, R.style.Accent_Mint, R.style.Accent_Amber, R.style.Accent_Rose)

    private fun prefs(ctx: Context) = (ctx.applicationContext ?: ctx).getSharedPreferences("restro_look", Context.MODE_PRIVATE)

    fun theme(ctx: Context) = prefs(ctx).getInt("theme", THEME_GLASS).coerceIn(0, 2)
    fun accent(ctx: Context) = prefs(ctx).getInt("accent", -1).let { if (it in 0..4) it else if (theme(ctx) == THEME_MIDNIGHT) 2 else 0 }
    fun setTheme(ctx: Context, t: Int) = prefs(ctx).edit().putInt("theme", t).remove("accent").apply()
    fun setAccent(ctx: Context, a: Int) = prefs(ctx).edit().putInt("accent", a).apply()

    /** Call before super.onCreate / setContentView. */
    fun apply(activity: Activity) {
        activity.setTheme(
            when (theme(activity)) {
                THEME_MIDNIGHT -> R.style.Theme_Restro_Midnight
                THEME_GLASS -> R.style.Theme_Restro_Glass
                else -> R.style.Theme_Restro
            }
        )
        activity.theme.applyStyle(ACCENT_STYLES[accent(activity)], true)
    }
}

fun Context.attrColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Context.isRetro(): Boolean = false

fun Context.isGlass(): Boolean {
    val tv = TypedValue()
    return theme.resolveAttribute(R.attr.oIsGlass, tv, true) && tv.data != 0
}

fun Context.dp(v: Float) = v * resources.displayMetrics.density
