package com.nervus.sdk.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NervusThemeTest {

    @Test
    fun `light scheme uses blue primary`() {
        assertEquals(Color(0xFF1565C0), LightColors.primary)
    }

    @Test
    fun `light scheme uses teal secondary`() {
        assertEquals(Color(0xFF00897B), LightColors.secondary)
    }

    @Test
    fun `light scheme background is light`() {
        assertNotEquals(Color.Black, LightColors.background)
    }

    @Test
    fun `dark scheme uses light blue primary`() {
        assertEquals(Color(0xFF9ECAFF), DarkColors.primary)
    }

    @Test
    fun `dark scheme uses light teal secondary`() {
        assertEquals(Color(0xFF80CBC4), DarkColors.secondary)
    }

    @Test
    fun `dark scheme background is dark`() {
        assertNotEquals(Color.White, DarkColors.background)
    }

    @Test
    fun `light and dark primaries differ`() {
        assertNotEquals(LightColors.primary, DarkColors.primary)
    }
}
