package org.antigravity.gateway

import androidx.appcompat.app.AppCompatDelegate
import org.antigravity.gateway.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeModeTest {

    @Test
    fun `stored values round-trip`() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStoredValue("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue("dark"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("system"))
    }

    @Test
    fun `unknown or missing values fall back to follow system`() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromStoredValue("midnight"))
    }

    @Test
    fun `case insensitive parsing keeps user preference`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromStoredValue("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromStoredValue("Light"))
    }

    @Test
    fun `nights map to appcompat delegate modes`() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.delegateMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.delegateMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.delegateMode)
    }
}
