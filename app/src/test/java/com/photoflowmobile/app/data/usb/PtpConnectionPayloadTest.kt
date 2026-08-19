package com.photoflowmobile.app.data.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the FR-4 truncation check in [PtpConnection.readDataAndResponse].
 *
 * The check is `received < expectedPayload`, so everything depends on how a container-length
 * header maps to an expected payload size. The unknown-length sentinel is the trap: cameras
 * that stream data without declaring a length send 0xFFFFFFFF, and if that were ever treated
 * as a huge expectation instead of "not declared", every such transfer would be rejected as
 * truncated and tethering would break on those bodies.
 */
class PtpConnectionPayloadTest {

    private val headerBytes = 12

    /** Mirrors the guard in readDataAndResponse. */
    private fun isTruncated(containerLen: Int, received: Int): Boolean =
        received < PtpConnection.expectedPayloadBytes(containerLen)

    @Test
    fun `declared length yields payload minus header`() {
        assertEquals(0, PtpConnection.expectedPayloadBytes(headerBytes))
        assertEquals(1, PtpConnection.expectedPayloadBytes(headerBytes + 1))
        assertEquals(5_000_000, PtpConnection.expectedPayloadBytes(headerBytes + 5_000_000))
    }

    @Test
    fun `unknown-length sentinel yields zero, not a huge expectation`() {
        // 0xFFFFFFFF read as a signed Int is -1.
        assertEquals(0, PtpConnection.expectedPayloadBytes(-1))
    }

    @Test
    fun `headers too short to hold a payload yield zero rather than a negative`() {
        assertEquals(0, PtpConnection.expectedPayloadBytes(0))
        assertEquals(0, PtpConnection.expectedPayloadBytes(headerBytes - 1))
    }

    @Test
    fun `a complete transfer is not flagged as truncated`() {
        val declared = headerBytes + 4_000_000
        assertFalse(isTruncated(declared, received = 4_000_000))
    }

    @Test
    fun `a short transfer is flagged as truncated`() {
        val declared = headerBytes + 4_000_000
        assertTrue("one byte short still counts", isTruncated(declared, received = 3_999_999))
        assertTrue(isTruncated(declared, received = 0))
    }

    @Test
    fun `unknown-length transfers are never flagged as truncated`() {
        assertFalse(isTruncated(containerLen = -1, received = 0))
        assertFalse(isTruncated(containerLen = -1, received = 8_000_000))
    }

    @Test
    fun `an over-long read is not flagged`() {
        // Some devices pad the final packet; more data than declared is not a truncation.
        val declared = headerBytes + 1_000
        assertFalse(isTruncated(declared, received = 1_024))
    }
}
