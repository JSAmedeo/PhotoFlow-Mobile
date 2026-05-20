package com.photoflowmobile.app.data.cloud

import org.json.JSONObject

/**
 * Returned on session upsert success.
 * null returned from [ensureSession] means a hard server/network failure.
 */
data class SessionResult(val cloudSessionId: Long?)

object CloudSessionService {

    /**
     * Upserts a session on the backend for [deviceId] + [sessionKey].
     * Uses POST /sessions/upsert — safe to call on retry; returns the existing session instead
     * of a conflict when the session was already created.
     * - 200/201: success — parses cloudSessionId from response body.
     * - Anything else: returns null — caller should fail the upload.
     */
    fun ensureSession(
        client: CloudApiClient,
        deviceId: Int,
        sessionKey: String,
        sessionKeyType: String = "barcode",
        displayLabel: String? = null
    ): SessionResult? {
        val body = JSONObject().apply {
            put("device_id", deviceId)
            put("session_key", sessionKey)
            put("session_key_type", sessionKeyType)
            put("display_label", displayLabel ?: sessionKey)
        }.toString()

        val (status, responseBody) = client.postJson("/sessions/upsert", body)
        return when {
            status in 200..201 -> {
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val cloudId = json?.optLong("session_id", -1L)?.takeIf { it >= 0 }
                    ?: json?.optLong("id", -1L)?.takeIf { it >= 0 }
                SessionResult(cloudSessionId = cloudId)
            }
            // Defensive: upsert shouldn't 409, but handle gracefully if the server does
            status == 409 -> SessionResult(cloudSessionId = null)
            else -> null
        }
    }
}
