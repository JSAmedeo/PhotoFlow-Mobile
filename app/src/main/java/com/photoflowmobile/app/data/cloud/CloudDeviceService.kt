package com.photoflowmobile.app.data.cloud

import org.json.JSONObject

data class RegisteredDevice(
    val deviceId: Int,
    val venueId: Int,
    val deviceUuid: String,
    val displayName: String
)

object CloudDeviceService {

    fun register(
        client: CloudApiClient,
        venueId: Int,
        deviceUuid: String,
        displayName: String
    ): RegisteredDevice? {
        val body = JSONObject().apply {
            put("venue_id", venueId)
            put("device_uuid", deviceUuid)
            put("display_name", displayName)
        }.toString()

        val (status, responseBody) = client.postJson("/devices/register", body)

        // 200/201 = new registration; 409 = already registered — both return the device object
        if (status !in 200..201 && status != 409) return null

        return try {
            val json = JSONObject(responseBody)
            RegisteredDevice(
                deviceId    = json.getInt("device_id"),
                venueId     = json.getInt("venue_id"),
                deviceUuid  = json.getString("device_uuid"),
                displayName = json.optString("display_name", displayName)
            )
        } catch (_: Exception) { null }
    }
}
