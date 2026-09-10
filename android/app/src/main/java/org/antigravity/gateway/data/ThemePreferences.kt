package org.antigravity.gateway.data

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * User-selectable theme mode. [delegateMode] maps to [AppCompatDelegate] night mode values so the
 * whole app (including dialogs and system bars) follows the choice.
 */
enum class ThemeMode(val storedValue: String, val delegateMode: Int) {
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    companion object {
        fun fromStoredValue(value: String?): ThemeMode =
            entries.firstOrNull { it.storedValue.equals(value, ignoreCase = true) } ?: SYSTEM
    }
}

/**
 * Persists the theme selection independently from the encrypted gateway config so that sensitive
 * provider data and UI preferences never share storage.
 */
class ThemePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: ThemeMode
        get() = ThemeMode.fromStoredValue(prefs.getString(KEY_THEME_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.storedValue).apply()
        }

    companion object {
        const val PREFS_NAME = "gateway_theme_prefs"
        const val KEY_THEME_MODE = "theme_mode"
    }
}
