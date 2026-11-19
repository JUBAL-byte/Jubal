package app.jubal.qeshev

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import kotlin.math.roundToInt

/** Look-and-feel settings: light/dark theme and display size. */
object Settings {
    private const val PREFS = "qeshev_settings"
    private const val KEY_THEME = "theme"
    private const val KEY_SCALE = "scale"

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    val THEMES = listOf(
        THEME_SYSTEM to "אוטומטי, לפי הטלפון",
        THEME_LIGHT to "בהיר",
        THEME_DARK to "כהה",
    )

    /** 0 means automatic. */
    val SCALES = listOf(
        0f to "אוטומטי, לפי המסך",
        0.8f to "קטן מאוד",
        0.9f to "קטן",
        1.0f to "רגיל",
        1.12f to "גדול",
        1.25f to "גדול מאוד",
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun theme(context: Context): Int = prefs(context).getInt(KEY_THEME, THEME_SYSTEM)

    fun setTheme(context: Context, theme: Int) {
        prefs(context).edit().putInt(KEY_THEME, theme).apply()
        applyTheme(context)
    }

    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(
            when (theme(context)) {
                THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
    }

    fun scale(context: Context): Float = prefs(context).getFloat(KEY_SCALE, 0f)

    fun setScale(context: Context, scale: Float) {
        prefs(context).edit().putFloat(KEY_SCALE, scale).commit()
    }

    fun themeLabel(context: Context): String = THEMES.firstOrNull { it.first == theme(context) }?.second ?: THEMES[0].second

    fun scaleLabel(context: Context): String {
        val s = scale(context)
        return SCALES.firstOrNull { kotlin.math.abs(it.first - s) < 0.001f }?.second ?: SCALES[0].second
    }

    /**
     * Returns a context whose display density is adjusted to the chosen size.
     * Automatic mode only steps in on unusually narrow screens, so the layout never gets cramped.
     */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        val metrics = base.resources.displayMetrics
        val chosen = scale(base)
        val factor = if (chosen > 0f) {
            chosen
        } else {
            val sw = config.smallestScreenWidthDp
            if (sw in 1..299) (sw / 320f).coerceAtLeast(0.8f) else 1f
        }
        if (kotlin.math.abs(factor - 1f) < 0.001f) return base

        // Bigger factor = more pixels per dp = everything drawn larger.
        val newDpi = (config.densityDpi * factor).roundToInt().coerceAtLeast(80)
        val density = newDpi / 160f
        config.densityDpi = newDpi
        config.screenWidthDp = (metrics.widthPixels / density).toInt()
        config.screenHeightDp = (metrics.heightPixels / density).toInt()
        config.smallestScreenWidthDp = minOf(config.screenWidthDp, config.screenHeightDp)
        return base.createConfigurationContext(config)
    }

    fun isCustomScale(context: Context): Boolean = scale(context) > 0f
}
