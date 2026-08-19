package com.photoflowmobile.app.data.model

enum class DeviceMode { TETHERED_DSLR, NATIVE_CAMERA }



enum class OrientationLock(val label: String) {
    LANDSCAPE("Landscape"),
    LANDSCAPE_180("Landscape 180"),
    PORTRAIT("Portrait"),
    PORTRAIT_180("Portrait 180")
}

enum class FieldType(val label: String) {
    BARCODE("Barcode"), SEQUENCE("Seq Number"), CUSTOM("Custom")
}

data class NamingField(val type: FieldType, val customValue: String = "")

data class AppSettings(
    val deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    val darkMode: Boolean = true,
    val namingFields: List<NamingField> = defaultNamingFields(),
    val namingSeparator: String = "_",
    val namingExtension: String = "JPG",
    val loggingEnabled: Boolean = true,
    val autoRetryEnabled: Boolean = true,
    val autoRetryIntervalSeconds: Int = 4,
    // Bounded by default. Continuous (-1) retries terminal failures — a wrong API key, a 422,
    // an unregistered device — forever, re-uploading the full file every interval for the rest
    // of a shoot. Operators can still opt into -1 in Settings.
    val autoRetryMaxCount: Int = 3,
    val sessionHistoryMax: Int = 50,  // -1 = unlimited
    val saveBackupToPhone: Boolean = false,
    val autoDeleteBackups: Boolean = false,
    val autoDeleteAfterDays: Int = 30,
    val orientationLockEnabled: Boolean = false,
    val orientationLock: OrientationLock = OrientationLock.LANDSCAPE,
    // Cloud API
    val cloudSetupCode: String = "",                    // setup code from venue — sent on device registration
    val cloudVenueSlug: String = "",                    // returned by backend after registration; persisted
    val cloudVenueId: Int = 0,                          // returned by backend after registration
    val cloudDeviceDisplayName: String = "",
    val cloudDeviceUuid: String = "",
    val cloudDeviceId: Int = 0,         // 0 = not yet registered
    // NOTE: the Cloud API key is deliberately NOT a field here. AppSettings is persisted verbatim
    // to DataStore in plaintext, so carrying the key would recreate the plaintext copy on every
    // save. It lives only in CredentialStore (Keystore-backed); read it from there.
    val cloudStationName: String = ""   // optional station label sent with registration and uploads
)

fun defaultNamingFields(): List<NamingField> = listOf(
    NamingField(FieldType.CUSTOM),
    NamingField(FieldType.BARCODE),
    NamingField(FieldType.SEQUENCE)
)

fun buildNamingPreview(fields: List<NamingField>, separator: String): String =
    fields.joinToString(separator) { field ->
        when (field.type) {
            FieldType.BARCODE  -> "0006"
            FieldType.SEQUENCE -> "01"
            FieldType.CUSTOM   -> field.customValue.ifBlank { "TEXT" }
        }
    }

fun buildFilename(settings: AppSettings, barcode: String, sequenceNumber: Int): String {
    val parts = settings.namingFields.map { field ->
        when (field.type) {
            FieldType.BARCODE  -> barcode
            FieldType.SEQUENCE -> sequenceNumber.toString().padStart(2, '0')
            FieldType.CUSTOM   -> field.customValue.ifBlank { "custom" }
        }
    }.filter { it.isNotBlank() }
    return parts.joinToString(settings.namingSeparator) + ".${settings.namingExtension.lowercase()}"
}

fun serializeNamingFields(fields: List<NamingField>): String =
    fields.joinToString("|") { "${it.type.name}:${it.customValue}" }

fun deserializeNamingFields(encoded: String): List<NamingField> {
    if (encoded.isBlank()) return defaultNamingFields()
    val result = encoded.split("|").mapNotNull { part ->
        val colon = part.indexOf(':')
        if (colon < 0) return@mapNotNull null
        val typeName = part.substring(0, colon)
        val customValue = part.substring(colon + 1)
        val type = FieldType.entries.firstOrNull { it.name == typeName } ?: return@mapNotNull null
        NamingField(type, customValue)
    }
    return result.ifEmpty { defaultNamingFields() }
}
