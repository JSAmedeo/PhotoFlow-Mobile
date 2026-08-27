package com.photoflowmobile.app.data.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CloudUploadExecutorTest {

    @Test fun `UTC Z suffix parses correctly`() {
        val millis = CloudUploadExecutor.parseIso8601Millis("2024-06-15T10:30:00Z")
        assertNotNull(millis)
        // 2024-06-15T10:30:00Z = 1718447400000 ms
        assertEquals(1718447400000L, millis)
    }

    @Test fun `timestamp without Z gets Z appended and parses`() {
        val millis = CloudUploadExecutor.parseIso8601Millis("2024-06-15T10:30:00")
        assertNotNull(millis)
        assertEquals(1718447400000L, millis)
    }

    @Test fun `positive offset parses correctly`() {
        // +02:00 means 2 hours behind UTC, so the UTC moment is 08:30:00
        val millis = CloudUploadExecutor.parseIso8601Millis("2024-06-15T12:30:00+02:00")
        assertNotNull(millis)
        assertEquals(1718447400000L, millis)
    }

    @Test fun `negative offset parses correctly`() {
        // -05:00 means 5 hours ahead of UTC, so the UTC moment is 15:30:00
        val millis = CloudUploadExecutor.parseIso8601Millis("2024-06-15T10:30:00-05:00")
        assertNotNull(millis)
        assertEquals(1718465400000L, millis)
    }

    @Test fun `garbage input returns null`() {
        assertNull(CloudUploadExecutor.parseIso8601Millis("not-a-date"))
    }

    @Test fun `empty string returns null`() {
        assertNull(CloudUploadExecutor.parseIso8601Millis(""))
    }

    @Test fun `partial date returns null`() {
        assertNull(CloudUploadExecutor.parseIso8601Millis("2024-06-15"))
    }
}
