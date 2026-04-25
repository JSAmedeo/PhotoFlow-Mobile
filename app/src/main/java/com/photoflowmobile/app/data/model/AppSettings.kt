package com.photoflowmobile.app.data.model

enum class DeviceMode { TETHERED_DSLR, NATIVE_CAMERA }

enum class PreviewQuality(val label: String) {
    LOW("Low"), MEDIUM("Medium"), HIGH("High")
}

enum class SessionTimeout(val label: String) {
    MIN_30("30 min"), MIN_60("60 min"), MIN_120("120 min"), NEVER("Never")
}

enum class FieldType(val label: String) {
    BARCODE("Barcode"), SEQUENCE("Seq Number"), CUSTOM("Custom")
}

data class NamingField(val type: FieldType, val customValue: String = "")

data class AppSettings(
    val deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    val autoReconnect: Boolean = true,
    val previewQuality: PreviewQuality = PreviewQuality.HIGH,
    val sessionTimeout: SessionTimeout = SessionTimeout.MIN_60,
    val namingFields: List<NamingField> = defaultNamingFields(),
    val namingSeparator: String = "_",
    val namingExtension: String = "JPG",
    val verboseLogging: Boolean = false,
    val saveCrashReports: Boolean = true,
    val autoRetryEnabled: Boolean = true,
    val autoRetryIntervalSeconds: Int = 4,
    val autoRetryMaxCount: Int = -1,  // -1 = continuous
    val sessionHistoryMax: Int = 50,  // -1 = unlimited
    val saveBackupToPhone: Boolean = false,
    val autoDeleteBackups: Boolean = false,
    val autoDeleteAfterDays: Int = 30
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
