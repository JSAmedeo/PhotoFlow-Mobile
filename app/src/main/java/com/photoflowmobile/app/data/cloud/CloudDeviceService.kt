package com.photoflowmobile.app.data.cloud

import org.json.JSONObject

data class RegisteredDevice(
    val deviceId: Int,
    val venueId: Int,
    val venueSlug: String,
    val deviceUuid: String,
    val displayName: String
)

sealed class RegistrationResult {
    data class Success(val device: RegisteredDevice) : RegistrationResult()
    object AuthError : RegistrationResult()      // 401 — bad/missing API key
    object ConfigError : RegistrationResult()    // 404 — unknown setup code
    data class ServerError(val status: Int, val body: String) : RegistrationResult()
}

object CloudDeviceService {

    /**
     * Registers or re-registers this device via POST /devices/register-with-setup-code.
     * - 201: new registration
     * - 200: same device_uuid already registered — returns existing device
     * - 401: AUTH_ERROR (bad/missing API key)
     * - 404: CONFIG_ERROR (invalid setup code)
     * - Other: ServerError
     */
    fun register(
        client: CloudApiClient,
        setupCode: String,
        deviceUuid: String,
        displayName: String,
        appVersion: String = "",
        deviceModel: String = "",
        androidVersion: String = "",
        stationName: String = ""
    ): RegistrationResult {
        val body = JSONObject().apply {
            put("setup_code", setupCode)
            put("device_uuid", deviceUuid)
            put("display_name", displayName)
            if (appVersion.isNotBlank())     put("app_version", appVersion)
            if (deviceModel.isNotBlank())    put("device_model", deviceModel)
            if (androidVersion.isNotBlank()) put("android_version", androidVersion)
            if (stationName.isNotBlank())    put("station_name", stationName)
        }.toString()

        val (status, responseBody) = client.postJson("/devices/register-with-setup-code", body)

        return when (status) {
            200, 201 -> try {
                val json = JSONObject(responseBody)
                RegistrationResult.Success(
                    RegisteredDevice(
                        deviceId    = json.getInt("device_id"),
                        venueId     = json.getInt("venue_id"),
                        venueSlug   = json.optString("venue_slug", ""),
                        deviceUuid  = json.getString("device_uuid"),
                        displayName = json.optString("display_name", displayName)
                    )
                )
            } catch (e: Exception) {
                RegistrationResult.ServerError(status, "Parse error: ${e.message}")
            }
            401 -> RegistrationResult.AuthError
            404 -> RegistrationResult.ConfigError
            else -> RegistrationResult.ServerError(status, responseBody.take(200))
        }
    }
}
