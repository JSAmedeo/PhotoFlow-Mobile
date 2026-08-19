package com.photoflowmobile.app.data.settings

import com.photoflowmobile.app.data.model.AppSettings
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.serializeNamingFields

/**
 * Builds the payload for settings export.
 *
 * Kept as plain maps rather than JSONObject so the contents are unit-testable on the JVM without
 * pulling in a JSON implementation — [ConfigViewModel] wraps the result with `JSONObject(map)`.
 *
 * The export file is written to shared Downloads storage: readable by any app holding storage
 * permission, and swept up by cloud backup. It therefore carries **configuration only, never
 * credentials**. That split is the norm for config export (browsers, VPN clients, MDM profiles
 * all do it); where secrets are portable at all they are wrapped with a user-supplied
 * passphrase, never written in the clear.
 */
object SettingsExport {

    /**
     * Keys that must never appear in an exported file.
     *
     * `cloud_setup_code` counts as a secret: it provisions a device against a venue, so it is a
     * credential rather than a preference. Asserted by SettingsExportTest — if a future change
     * reintroduces one of these, the test fails rather than the leak shipping silently.
     */
    val SECRET_KEYS = setOf("password", "cloud_api_key", "cloud_setup_code")

    fun settingsToMap(s: AppSettings): Map<String, Any> = mapOf(
        "device_mode" to s.deviceMode.name,
        "dark_mode" to s.darkMode,
        "naming_fields" to serializeNamingFields(s.namingFields),
        "naming_separator" to s.namingSeparator,
        "naming_extension" to s.namingExtension,
        "logging_enabled" to s.loggingEnabled,
        "auto_retry_enabled" to s.autoRetryEnabled,
        "auto_retry_interval_secs" to s.autoRetryIntervalSeconds,
        "auto_retry_max_count" to s.autoRetryMaxCount,
        "session_history_max" to s.sessionHistoryMax,
        "save_backup_to_phone" to s.saveBackupToPhone,
        "auto_delete_backups" to s.autoDeleteBackups,
        "auto_delete_after_days" to s.autoDeleteAfterDays,
        "orientation_lock_enabled" to s.orientationLockEnabled,
        "orientation_lock" to s.orientationLock.name,
        "cloud_venue_slug" to s.cloudVenueSlug,
        "cloud_venue_id" to s.cloudVenueId,
        "cloud_device_display_name" to s.cloudDeviceDisplayName,
        "cloud_station_name" to s.cloudStationName
        // Deliberately absent: cloud_api_key, cloud_setup_code.
        // Also absent: cloud_device_uuid / cloud_device_id — device identity, not config.
    )

    fun profileToMap(p: ConnectionProfile): Map<String, Any> = mapOf(
        "name" to p.name,
        "connection_type" to p.connectionType.name,
        "host" to p.host,
        "port" to p.port,
        "username" to p.username,
        "remote_path" to p.remotePath,
        "photo_op" to p.photoOp,
        "is_active" to p.isActive
        // Deliberately absent: password. An earlier version read it back out of CredentialStore
        // and wrote it here in the clear, undoing the point of encrypting it at rest.
    )
}
