package com.photoflowmobile.app.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudResponseClassifierTest {

    @Test fun `200 is Success`() {
        assertEquals(UploadOutcome.Success, classifyCloudResponse(200))
    }

    @Test fun `201 is Success`() {
        assertEquals(UploadOutcome.Success, classifyCloudResponse(201))
    }

    @Test fun `409 is AlreadyExists`() {
        assertEquals(UploadOutcome.AlreadyExists, classifyCloudResponse(409))
    }

    @Test fun `401 is Terminal`() {
        assertTrue(classifyCloudResponse(401) is UploadOutcome.Terminal)
    }

    @Test fun `404 is Terminal`() {
        assertTrue(classifyCloudResponse(404) is UploadOutcome.Terminal)
    }

    @Test fun `422 is Terminal`() {
        assertTrue(classifyCloudResponse(422) is UploadOutcome.Terminal)
    }

    @Test fun `500 is Retryable`() {
        val result = classifyCloudResponse(500)
        assertTrue(result is UploadOutcome.Retryable)
        assertEquals(500, (result as UploadOutcome.Retryable).status)
    }

    @Test fun `503 is Retryable`() {
        val result = classifyCloudResponse(503)
        assertTrue(result is UploadOutcome.Retryable)
        assertEquals(503, (result as UploadOutcome.Retryable).status)
    }

    @Test fun `418 is Terminal`() {
        assertTrue(classifyCloudResponse(418) is UploadOutcome.Terminal)
    }

    @Test fun `400 is Terminal`() {
        assertTrue(classifyCloudResponse(400) is UploadOutcome.Terminal)
    }

    @Test fun `Terminal carries the status code`() {
        val result = classifyCloudResponse(401)
        assertEquals(401, (result as UploadOutcome.Terminal).status)
    }
}
