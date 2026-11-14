package org.antigravity.gateway.data

import android.content.Context
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.antigravity.gateway.R

/**
 * The four selectable skins. Each entry owns a full theme resource, so switching a skin
 * never depends on the system night mode: the palette is explicit and stays identical on
 * every device.
 */
enum class Skin(
    val storedValue: String,
    @StyleRes val themeRes: Int,
    @StringRes val labelRes: Int,
    val chipColor: Int,
    val chipBorder: Int
) {
    MIN_LIGHT(
        "min",
        R.style.Theme_AntigravityGateway,
        R.string.skin_min_light,
        0xFFFBFBFC.toInt(),
        0xFFD8DBE0.toInt()
    ),
    MIN_DARK(
        "mnd",
        R.style.Theme_AntigravityGateway_MinDark,
        R.string.skin_min_dark,
        0xFF0F1013.toInt(),
        0xFF3A3F47.toInt()
    ),
    SKETCH(
        "skc",
        R.style.Theme_AntigravityGateway_Skc,
        R.string.skin_sketch,
        0xFFF7F1DE.toInt(),
        0xFF3A4150.toInt()
    ),
    SILK(
        "acr",
        R.style.Theme_AntigravityGateway_Acr,
        R.string.skin_silk,
        0xFF1B2C4E.toInt(),
        0xFF4E6FA8.toInt()
    );

    companion object {
        /**
         * Maps a stored value to a skin. Builds before the four-skin model stored
         * `light` / `dark` / `system`; `system` collapses to the light skin because the app
         * no longer follows the system setting.
         */
        fun fromStoredValue(value: String?): Skin = when (value?.lowercase()) {
            "min", "light" -> MIN_LIGHT
            "mnd", "dark" -> MIN_DARK
            "skc" -> SKETCH
            "acr" -> SILK
            null, "system" -> MIN_LIGHT
            else -> MIN_LIGHT
        }
    }
}

/** Persists the skin choice separately from the encrypted gateway config. */
class SkinPreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var skin: Skin
        get() = runCatching { Skin.fromStoredValue(prefs.getString(KEY_SKIN, null)) }
            .getOrDefault(Skin.MIN_LIGHT)
        set(value) {
            runCatching { prefs.edit().putString(KEY_SKIN, value.storedValue).apply() }
        }

    companion object {
        const val PREFS_NAME = "gateway_theme_prefs"
        const val KEY_SKIN = "theme_mode"
    }
}
