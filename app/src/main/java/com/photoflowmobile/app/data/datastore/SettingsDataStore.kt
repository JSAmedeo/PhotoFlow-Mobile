package com.photoflowmobile.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.photoflowmobile.app.data.model.*

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "photoflow_settings")

object SettingsKeys {
    val DEVICE_MODE               = stringPreferencesKey("device_mode")
    val DARK_MODE                 = booleanPreferencesKey("dark_mode")
    val NAMING_FIELDS             = stringPreferencesKey("naming_fields")
    val NAMING_SEPARATOR          = stringPreferencesKey("naming_separator")
    val NAMING_EXTENSION          = stringPreferencesKey("naming_extension")
    val LOGGING_ENABLED           = booleanPreferencesKey("logging_enabled")
    val AUTO_RETRY_ENABLED        = booleanPreferencesKey("auto_retry_enabled")
    val AUTO_RETRY_INTERVAL_SECS  = intPreferencesKey("auto_retry_interval_secs")
    val AUTO_RETRY_MAX_COUNT      = intPreferencesKey("auto_retry_max_count")
    val SESSION_HISTORY_MAX       = intPreferencesKey("session_history_max")
    val SAVE_BACKUP_TO_PHONE      = booleanPreferencesKey("save_backup_to_phone")
    val AUTO_DELETE_BACKUPS       = booleanPreferencesKey("auto_delete_backups")
    val AUTO_DELETE_AFTER_DAYS    = intPreferencesKey("auto_delete_after_days")
    val ORIENTATION_LOCK_ENABLED  = booleanPreferencesKey("orientation_lock_enabled")
    val ORIENTATION_LOCK          = stringPreferencesKey("orientation_lock")
    // Cloud API
    val CLOUD_VENUE_ID            = intPreferencesKey("cloud_venue_id")
    val CLOUD_DEVICE_DISPLAY_NAME = stringPreferencesKey("cloud_device_display_name")
    val CLOUD_DEVICE_UUID         = stringPreferencesKey("cloud_device_uuid")
    val CLOUD_DEVICE_ID           = intPreferencesKey("cloud_device_id")
}

fun appSettingsFromPreferences(prefs: Preferences) = AppSettings(
    deviceMode             = DeviceMode.entries.firstOrNull { it.name == prefs[SettingsKeys.DEVICE_MODE] }
                                 ?: DeviceMode.TETHERED_DSLR,
    darkMode               = prefs[SettingsKeys.DARK_MODE] ?: true,
    namingFields           = deserializeNamingFields(prefs[SettingsKeys.NAMING_FIELDS] ?: ""),
    namingSeparator        = prefs[SettingsKeys.NAMING_SEPARATOR] ?: "_",
    namingExtension        = prefs[SettingsKeys.NAMING_EXTENSION] ?: "JPG",
    loggingEnabled         = prefs[SettingsKeys.LOGGING_ENABLED] ?: true,
    autoRetryEnabled       = prefs[SettingsKeys.AUTO_RETRY_ENABLED]       ?: true,
    autoRetryIntervalSeconds = prefs[SettingsKeys.AUTO_RETRY_INTERVAL_SECS] ?: 4,
    autoRetryMaxCount      = prefs[SettingsKeys.AUTO_RETRY_MAX_COUNT]     ?: -1,
    sessionHistoryMax      = prefs[SettingsKeys.SESSION_HISTORY_MAX]      ?: 50,
    saveBackupToPhone      = prefs[SettingsKeys.SAVE_BACKUP_TO_PHONE]     ?: false,
    autoDeleteBackups      = prefs[SettingsKeys.AUTO_DELETE_BACKUPS]      ?: false,
    autoDeleteAfterDays    = prefs[SettingsKeys.AUTO_DELETE_AFTER_DAYS]   ?: 30,
    orientationLockEnabled = prefs[SettingsKeys.ORIENTATION_LOCK_ENABLED] ?: false,
    orientationLock        = OrientationLock.entries.firstOrNull { it.name == prefs[SettingsKeys.ORIENTATION_LOCK] }
                                 ?: OrientationLock.LANDSCAPE,
    cloudVenueId           = prefs[SettingsKeys.CLOUD_VENUE_ID]            ?: 1,
    cloudDeviceDisplayName = prefs[SettingsKeys.CLOUD_DEVICE_DISPLAY_NAME] ?: "",
    cloudDeviceUuid        = prefs[SettingsKeys.CLOUD_DEVICE_UUID]         ?: "",
    cloudDeviceId          = prefs[SettingsKeys.CLOUD_DEVICE_ID]           ?: 0
)

fun AppSettings.toPreferences(prefs: MutablePreferences) {
    prefs[SettingsKeys.DEVICE_MODE]              = deviceMode.name
    prefs[SettingsKeys.DARK_MODE]                = darkMode
    prefs[SettingsKeys.NAMING_FIELDS]            = serializeNamingFields(namingFields)
    prefs[SettingsKeys.NAMING_SEPARATOR]         = namingSeparator
    prefs[SettingsKeys.NAMING_EXTENSION]         = namingExtension
    prefs[SettingsKeys.LOGGING_ENABLED]          = loggingEnabled
    prefs[SettingsKeys.AUTO_RETRY_ENABLED]       = autoRetryEnabled
    prefs[SettingsKeys.AUTO_RETRY_INTERVAL_SECS] = autoRetryIntervalSeconds
    prefs[SettingsKeys.AUTO_RETRY_MAX_COUNT]     = autoRetryMaxCount
    prefs[SettingsKeys.SESSION_HISTORY_MAX]      = sessionHistoryMax
    prefs[SettingsKeys.SAVE_BACKUP_TO_PHONE]     = saveBackupToPhone
    prefs[SettingsKeys.AUTO_DELETE_BACKUPS]      = autoDeleteBackups
    prefs[SettingsKeys.AUTO_DELETE_AFTER_DAYS]   = autoDeleteAfterDays
    prefs[SettingsKeys.ORIENTATION_LOCK_ENABLED] = orientationLockEnabled
    prefs[SettingsKeys.ORIENTATION_LOCK]         = orientationLock.name
    prefs[SettingsKeys.CLOUD_VENUE_ID]            = cloudVenueId
    prefs[SettingsKeys.CLOUD_DEVICE_DISPLAY_NAME] = cloudDeviceDisplayName
    prefs[SettingsKeys.CLOUD_DEVICE_UUID]         = cloudDeviceUuid
    prefs[SettingsKeys.CLOUD_DEVICE_ID]           = cloudDeviceId
}
