package com.photoflowmobile.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the FR-3 retry bound.
 *
 * Before FR-3 the shipped default was -1 (continuous), so a terminally-failed upload — wrong
 * API key, 422, unregistered device — was re-uploaded in full every few seconds for the rest of
 * a shoot. These tests pin the bounded default and the limit arithmetic the auto-retry loop uses.
 */
class AppSettingsRetryTest {

    /** Mirrors the `withinLimit` expression in MainViewModel.startAutoRetryLoop. */
    private fun withinLimit(maxCount: Int, retryCount: Int): Boolean =
        maxCount == -1 || retryCount < maxCount

    @Test
    fun `auto retry defaults to three attempts, not continuous`() {
        assertEquals(3, AppSettings().autoRetryMaxCount)
    }

    @Test
    fun `auto retry is enabled by default`() {
        assertTrue(AppSettings().autoRetryEnabled)
    }

    @Test
    fun `default bound allows exactly three attempts then stops`() {
        val max = AppSettings().autoRetryMaxCount
        assertTrue("first attempt", withinLimit(max, retryCount = 0))
        assertTrue("second attempt", withinLimit(max, retryCount = 1))
        assertTrue("third attempt", withinLimit(max, retryCount = 2))
        assertFalse("fourth attempt must be refused", withinLimit(max, retryCount = 3))
        assertFalse("and every attempt after it", withinLimit(max, retryCount = 4))
    }

    @Test
    fun `continuous is still honoured when the operator opts in`() {
        assertTrue(withinLimit(maxCount = -1, retryCount = 0))
        assertTrue(withinLimit(maxCount = -1, retryCount = 999))
    }

    @Test
    fun `a limit of one permits a single attempt`() {
        assertTrue(withinLimit(maxCount = 1, retryCount = 0))
        assertFalse(withinLimit(maxCount = 1, retryCount = 1))
    }
}
