package com.photoflowmobile.app.data.cloud

import org.json.JSONObject

object CloudManifestService {

    fun fetch(client: CloudApiClient, sessionKey: String, venueId: Int): String {
        val (status, body) = client.get("/sessions/by-code/$sessionKey/manifest?venue_id=$venueId")
        if (status != 200) return "HTTP $status — ${body.take(200)}"

        return try {
            val json = JSONObject(body)
            val photoCount   = json.optInt("photo_count", -1)
            val deviceCount  = json.optInt("device_count", -1)
            val sessionCount = json.optInt("backend_session_count", -1)
            val collisions   = json.optJSONArray("sequence_collisions")?.length() ?: 0
            buildString {
                appendLine("session_key=$sessionKey  venue=$venueId")
                append("photos=$photoCount  devices=$deviceCount  sessions=$sessionCount")
                if (collisions > 0) append("  ⚠ $collisions collision(s)")
            }
        } catch (e: Exception) {
            "Parse error: ${e.message}"
        }
    }

    /**
     * Fetches a photo's binary content by its photo_uid.
     * Returns the raw bytes on HTTP 200, null on any error.
     * Use photo_uid (the opaque public identifier), not the numeric photo_id.
     */
    fun fetchPhotoFile(client: CloudApiClient, photoUid: String): ByteArray? {
        val (status, bytes) = client.getBytes("/photos/$photoUid/file")
        return if (status == 200 && bytes.isNotEmpty()) bytes else null
    }
}
