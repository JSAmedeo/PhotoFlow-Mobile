package com.photoflowmobile.app.data.cloud

import org.json.JSONObject

/**
 * Returned on session ensure success (created or already exists).
 * null returned from [ensureSession] means a hard server/network failure.
 */
data class SessionResult(val cloudSessionId: Long?)

object CloudSessionService {

    /**
     * Ensures a session exists on the backend for [deviceId] + [sessionKey].
     * - 201: created — parses cloudSessionId from response body.
     * - 409: already exists — returns SessionResult(null), treat as success.
     * - Anything else: returns null — caller should fail the upload.
     *
     * Wire field: session_code (backend API name, not renamed on the wire).
     */
    fun ensureSession(
        client: CloudApiClient,
        deviceId: Int,
        sessionKey: String
    ): SessionResult? {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("session_code", sessionKey)      // wire field stays session_code per API contract
            put("notes", JSONObject.NULL)
        }.toString()

        val (status, responseBody) = client.postJson("/sessions", body)
        return when {
            status in 200..201 -> {
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val cloudId = json?.optLong("id", -1L)?.takeIf { it >= 0 }
                    ?: json?.optLong("session_id", -1L)?.takeIf { it >= 0 }
                SessionResult(cloudSessionId = cloudId)
            }
            status == 409 -> SessionResult(cloudSessionId = null)
            else -> null
        }
    }
}
