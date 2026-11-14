package org.antigravity.gateway

import org.antigravity.gateway.data.Skin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinTest {

    @Test
    fun `stored values round-trip`() {
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue("min"))
        assertEquals(Skin.MIN_DARK, Skin.fromStoredValue("mnd"))
        assertEquals(Skin.SKETCH, Skin.fromStoredValue("skc"))
        assertEquals(Skin.SILK, Skin.fromStoredValue("acr"))
    }

    @Test
    fun `values from the day night era migrate onto the new palette`() {
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue("light"))
        assertEquals(Skin.MIN_DARK, Skin.fromStoredValue("dark"))
        // The app no longer follows the system setting, so a leftover "system" collapses to light.
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue("system"))
    }

    @Test
    fun `unknown or missing values fall back to the light skin`() {
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue(null))
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue(""))
        assertEquals(Skin.MIN_LIGHT, Skin.fromStoredValue("midnight"))
    }

    @Test
    fun `case insensitive parsing keeps the user preference`() {
        assertEquals(Skin.SKETCH, Skin.fromStoredValue("SKC"))
        assertEquals(Skin.SILK, Skin.fromStoredValue("Acr"))
    }

    @Test
    fun `every skin has its own key and theme`() {
        val skins = Skin.values()
        assertEquals(4, skins.size)
        assertEquals(skins.size, skins.map { it.storedValue }.toSet().size)
        assertEquals(skins.size, skins.map { it.themeRes }.toSet().size)
        assertEquals(skins.size, skins.map { it.labelRes }.toSet().size)
        assertTrue(skins.all { it.storedValue.isNotBlank() })
    }
}
