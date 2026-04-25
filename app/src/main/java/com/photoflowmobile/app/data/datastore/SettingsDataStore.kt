package com.photoflowmobile.app.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.photoflowmobile.app.data.model.*

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "photoflow_settings")

object SettingsKeys {
    val DEVICE_MODE               = stringPreferencesKey("device_mode")
    val AUTO_RECONNECT            = booleanPreferencesKey("auto_reconnect")
    val PREVIEW_QUALITY           = stringPreferencesKey("preview_quality")
    val SESSION_TIMEOUT           = stringPreferencesKey("session_timeout")
    val NAMING_FIELDS             = stringPreferencesKey("naming_fields")
    val NAMING_SEPARATOR          = stringPreferencesKey("naming_separator")
    val NAMING_EXTENSION          = stringPreferencesKey("naming_extension")
    val VERBOSE_LOGGING           = booleanPreferencesKey("verbose_logging")
    val SAVE_CRASH_REPORTS        = booleanPreferencesKey("save_crash_reports")
    val AUTO_RETRY_ENABLED        = booleanPreferencesKey("auto_retry_enabled")
    val AUTO_RETRY_INTERVAL_SECS  = intPreferencesKey("auto_retry_interval_secs")
    val AUTO_RETRY_MAX_COUNT      = intPreferencesKey("auto_retry_max_count")
    val SESSION_HISTORY_MAX       = intPreferencesKey("session_history_max")
    val SAVE_BACKUP_TO_PHONE      = booleanPreferencesKey("save_backup_to_phone")
    val AUTO_DELETE_BACKUPS       = booleanPreferencesKey("auto_delete_backups")
    val AUTO_DELETE_AFTER_DAYS    = intPreferencesKey("auto_delete_after_days")
}

fun appSettingsFromPreferences(prefs: Preferences) = AppSettings(
    deviceMode             = DeviceMode.entries.firstOrNull { it.name == prefs[SettingsKeys.DEVICE_MODE] }
                                 ?: DeviceMode.TETHERED_DSLR,
    autoReconnect          = prefs[SettingsKeys.AUTO_RECONNECT]   ?: true,
    previewQuality         = PreviewQuality.entries.firstOrNull { it.name == prefs[SettingsKeys.PREVIEW_QUALITY] }
                                 ?: PreviewQuality.HIGH,
    sessionTimeout         = SessionTimeout.entries.firstOrNull { it.name == prefs[SettingsKeys.SESSION_TIMEOUT] }
                                 ?: SessionTimeout.MIN_60,
    namingFields           = deserializeNamingFields(prefs[SettingsKeys.NAMING_FIELDS] ?: ""),
    namingSeparator        = prefs[SettingsKeys.NAMING_SEPARATOR] ?: "_",
    namingExtension        = prefs[SettingsKeys.NAMING_EXTENSION] ?: "JPG",
    verboseLogging         = prefs[SettingsKeys.VERBOSE_LOGGING]  ?: false,
    saveCrashReports       = prefs[SettingsKeys.SAVE_CRASH_REPORTS] ?: true,
    autoRetryEnabled       = prefs[SettingsKeys.AUTO_RETRY_ENABLED]       ?: true,
    autoRetryIntervalSeconds = prefs[SettingsKeys.AUTO_RETRY_INTERVAL_SECS] ?: 4,
    autoRetryMaxCount      = prefs[SettingsKeys.AUTO_RETRY_MAX_COUNT]     ?: -1,
    sessionHistoryMax      = prefs[SettingsKeys.SESSION_HISTORY_MAX]      ?: 50,
    saveBackupToPhone      = prefs[SettingsKeys.SAVE_BACKUP_TO_PHONE]     ?: false,
    autoDeleteBackups      = prefs[SettingsKeys.AUTO_DELETE_BACKUPS]      ?: false,
    autoDeleteAfterDays    = prefs[SettingsKeys.AUTO_DELETE_AFTER_DAYS]   ?: 30
)

fun AppSettings.toPreferences(prefs: MutablePreferences) {
    prefs[SettingsKeys.DEVICE_MODE]              = deviceMode.name
    prefs[SettingsKeys.AUTO_RECONNECT]           = autoReconnect
    prefs[SettingsKeys.PREVIEW_QUALITY]          = previewQuality.name
    prefs[SettingsKeys.SESSION_TIMEOUT]          = sessionTimeout.name
    prefs[SettingsKeys.NAMING_FIELDS]            = serializeNamingFields(namingFields)
    prefs[SettingsKeys.NAMING_SEPARATOR]         = namingSeparator
    prefs[SettingsKeys.NAMING_EXTENSION]         = namingExtension
    prefs[SettingsKeys.VERBOSE_LOGGING]          = verboseLogging
    prefs[SettingsKeys.SAVE_CRASH_REPORTS]       = saveCrashReports
    prefs[SettingsKeys.AUTO_RETRY_ENABLED]       = autoRetryEnabled
    prefs[SettingsKeys.AUTO_RETRY_INTERVAL_SECS] = autoRetryIntervalSeconds
    prefs[SettingsKeys.AUTO_RETRY_MAX_COUNT]     = autoRetryMaxCount
    prefs[SettingsKeys.SESSION_HISTORY_MAX]      = sessionHistoryMax
    prefs[SettingsKeys.SAVE_BACKUP_TO_PHONE]     = saveBackupToPhone
    prefs[SettingsKeys.AUTO_DELETE_BACKUPS]      = autoDeleteBackups
    prefs[SettingsKeys.AUTO_DELETE_AFTER_DAYS]   = autoDeleteAfterDays
}
