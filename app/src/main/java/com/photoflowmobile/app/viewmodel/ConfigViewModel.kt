package com.photoflowmobile.app.viewmodel

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.cloud.CloudApiClient
import com.photoflowmobile.app.data.cloud.CloudDeviceService
import com.photoflowmobile.app.data.cloud.CloudManifestService
import com.photoflowmobile.app.data.datastore.SettingsKeys
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.datastore.toPreferences
import com.photoflowmobile.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class SettingsTransferResult {
    data class ExportSuccess(val displayPath: String) : SettingsTransferResult()
    object ImportSuccess : SettingsTransferResult()
    data class Error(val message: String) : SettingsTransferResult()
}

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.settingsDataStore
    private val connectionProfileDao = (application as PhotoFlowApplication).database.connectionProfileDao()
    private val sessionDao = (application as PhotoFlowApplication).database.sessionDao()

    val settings: StateFlow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences()) else throw e
        }
        .map { appSettingsFromPreferences(it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AppSettings()
        )

    val connectionProfiles: StateFlow<List<ConnectionProfile>> =
        connectionProfileDao.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeSessionKey: StateFlow<String?> = sessionDao.getAllSessions()
        .map { sessions -> sessions.firstOrNull { it.status == "active" }?.barcode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _transferResult = MutableStateFlow<SettingsTransferResult?>(null)
    val transferResult: StateFlow<SettingsTransferResult?> = _transferResult.asStateFlow()

    private val _cloudRegistrationState = MutableStateFlow("")
    val cloudRegistrationState: StateFlow<String> = _cloudRegistrationState.asStateFlow()

    private val _manifestResult = MutableStateFlow("")
    val manifestResult: StateFlow<String> = _manifestResult.asStateFlow()

    fun clearTransferResult() { _transferResult.value = null }
    fun clearManifestResult() { _manifestResult.value = "" }

    fun save(settings: AppSettings) {
        viewModelScope.launch {
            dataStore.edit { prefs -> settings.toPreferences(prefs) }
        }
    }

    fun upsertProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            if (profile.id == 0L) connectionProfileDao.insert(profile)
            else connectionProfileDao.update(profile)
        }
    }

    fun deleteProfile(profile: ConnectionProfile) {
        viewModelScope.launch { connectionProfileDao.delete(profile) }
    }

    fun setActiveProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            connectionProfileDao.clearAllActive()
            connectionProfileDao.update(profile.copy(isActive = true))
        }
    }

    fun clearSessionHistory() {
        viewModelScope.launch {
            sessionDao.deleteImagesForCompletedSessions()
            sessionDao.deleteCompletedSessions()
        }
    }

    fun testConnection(profile: ConnectionProfile, onResult: (String?) -> Unit) {
        when (profile.connectionType) {
            ConnectionType.FTP -> viewModelScope.launch {
                val error: String? = withContext(Dispatchers.IO) {
                    val ftp = FTPClient()
                    try {
                        ftp.connectTimeout = 5_000
                        ftp.connect(profile.host, profile.port)
                        val ok = ftp.login(profile.username, profile.password)
                        ftp.logout()
                        if (ok) null else "Login failed — check username and password"
                    } catch (e: java.net.SocketTimeoutException) {
                        "Connection timed out (${profile.host}:${profile.port})"
                    } catch (e: java.net.ConnectException) {
                        "Could not reach ${profile.host}:${profile.port}"
                    } catch (e: Exception) {
                        e.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
                    } finally {
                        if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) {}
                    }
                }
                onResult(error)
            }
            ConnectionType.CLOUD_API -> viewModelScope.launch {
                val error: String? = withContext(Dispatchers.IO) {
                    try {
                        val (status, body) = CloudApiClient(profile.host).get("/health")
                        if (status == 200 && body.contains("ok")) null
                        else "Unexpected response: HTTP $status"
                    } catch (e: Exception) {
                        e.message?.takeIf { it.isNotBlank() } ?: "Connection failed"
                    }
                }
                onResult(error)
            }
        }
    }

    // ── Cloud API operations ──────────────────────────────────────────────────

    fun registerDevice() {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = connectionProfiles.value.firstOrNull {
                it.connectionType == ConnectionType.CLOUD_API && it.isActive
            } ?: run {
                _cloudRegistrationState.value = "No active Cloud API profile"
                return@launch
            }
            val s = settings.value
            val uuid = s.cloudDeviceUuid.ifBlank { UUID.randomUUID().toString() }
            val displayName = s.cloudDeviceDisplayName.ifBlank { "PhotoFlow Device" }
            _cloudRegistrationState.value = "Registering…"
            try {
                val device = CloudDeviceService.register(
                    CloudApiClient(profile.host), s.cloudVenueId, uuid, displayName
                )
                if (device != null) {
                    dataStore.edit { prefs ->
                        prefs[SettingsKeys.CLOUD_DEVICE_UUID] = device.deviceUuid
                        prefs[SettingsKeys.CLOUD_DEVICE_ID]   = device.deviceId
                    }
                    _cloudRegistrationState.value = "Registered — device_id=${device.deviceId}"
                } else {
                    _cloudRegistrationState.value = "Registration failed — check logs"
                }
            } catch (e: Exception) {
                _cloudRegistrationState.value = "Error: ${e.message}"
            }
        }
    }

    fun fetchManifest(sessionKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val profile = connectionProfiles.value.firstOrNull {
                it.connectionType == ConnectionType.CLOUD_API && it.isActive
            } ?: run {
                _manifestResult.value = "No active Cloud API profile"
                return@launch
            }
            val venueId = settings.value.cloudVenueId
            _manifestResult.value = "Fetching…"
            try {
                _manifestResult.value = CloudManifestService.fetch(
                    CloudApiClient(profile.host), sessionKey, venueId
                )
            } catch (e: Exception) {
                _manifestResult.value = "Error: ${e.message}"
            }
        }
    }

    // ── Settings export / import ──────────────────────────────────────────────

    fun exportSettings(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val currentSettings = settings.value
                val profiles = connectionProfiles.value

                val timestamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val fileName = "photoflow-settings-$timestamp.json"
                val json = JSONObject().apply {
                    put("version", 1)
                    put("exported_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
                    put("settings", settingsToJson(currentSettings))
                    put("connection_profiles", profilesToJson(profiles))
                }
                val jsonString = json.toString(2)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/json")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("Could not create file in Downloads")
                    resolver.openOutputStream(uri)?.use { out ->
                        OutputStreamWriter(out).use { writer -> writer.write(jsonString) }
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    downloadsDir.mkdirs()
                    java.io.File(downloadsDir, fileName).writeText(jsonString)
                }

                _transferResult.value = SettingsTransferResult.ExportSuccess("Downloads/$fileName")
            } catch (e: Exception) {
                _transferResult.value = SettingsTransferResult.Error("Export failed: ${e.message}")
            }
        }
    }

    fun importSettings(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.bufferedReader().readText()
                } ?: throw Exception("Could not read selected file")

                val root = JSONObject(jsonString)
                val version = root.optInt("version", 1)
                if (version > 1) throw Exception("Unsupported settings file version ($version)")

                val newSettings = settingsFromJson(root.getJSONObject("settings"))
                dataStore.edit { prefs -> newSettings.toPreferences(prefs) }

                val profilesJson = root.optJSONArray("connection_profiles")
                if (profilesJson != null) {
                    connectionProfileDao.deleteAll()
                    for (i in 0 until profilesJson.length()) {
                        connectionProfileDao.insert(profileFromJson(profilesJson.getJSONObject(i)))
                    }
                }

                _transferResult.value = SettingsTransferResult.ImportSuccess
            } catch (e: Exception) {
                _transferResult.value = SettingsTransferResult.Error("Import failed: ${e.message}")
            }
        }
    }

    private fun settingsToJson(s: AppSettings) = JSONObject().apply {
        put("device_mode", s.deviceMode.name)
        put("dark_mode", s.darkMode)
        put("naming_fields", serializeNamingFields(s.namingFields))
        put("naming_separator", s.namingSeparator)
        put("naming_extension", s.namingExtension)
        put("logging_enabled", s.loggingEnabled)
        put("auto_retry_enabled", s.autoRetryEnabled)
        put("auto_retry_interval_secs", s.autoRetryIntervalSeconds)
        put("auto_retry_max_count", s.autoRetryMaxCount)
        put("session_history_max", s.sessionHistoryMax)
        put("save_backup_to_phone", s.saveBackupToPhone)
        put("auto_delete_backups", s.autoDeleteBackups)
        put("auto_delete_after_days", s.autoDeleteAfterDays)
        put("orientation_lock_enabled", s.orientationLockEnabled)
        put("orientation_lock", s.orientationLock.name)
        put("cloud_venue_id", s.cloudVenueId)
        put("cloud_device_display_name", s.cloudDeviceDisplayName)
    }

    private fun settingsFromJson(j: JSONObject) = AppSettings(
        deviceMode = DeviceMode.entries.firstOrNull { it.name == j.optString("device_mode") }
            ?: DeviceMode.TETHERED_DSLR,
        darkMode = j.optBoolean("dark_mode", true),
        namingFields = deserializeNamingFields(j.optString("naming_fields")),
        namingSeparator = j.optString("naming_separator", "_"),
        namingExtension = j.optString("naming_extension", "JPG"),
        loggingEnabled = j.optBoolean("logging_enabled", true),
        autoRetryEnabled = j.optBoolean("auto_retry_enabled", true),
        autoRetryIntervalSeconds = j.optInt("auto_retry_interval_secs", 4),
        autoRetryMaxCount = j.optInt("auto_retry_max_count", -1),
        sessionHistoryMax = j.optInt("session_history_max", 50),
        saveBackupToPhone = j.optBoolean("save_backup_to_phone", false),
        autoDeleteBackups = j.optBoolean("auto_delete_backups", false),
        autoDeleteAfterDays = j.optInt("auto_delete_after_days", 30),
        orientationLockEnabled = j.optBoolean("orientation_lock_enabled", false),
        orientationLock = OrientationLock.entries.firstOrNull { it.name == j.optString("orientation_lock") }
            ?: OrientationLock.LANDSCAPE,
        cloudVenueId = j.optInt("cloud_venue_id", 1),
        cloudDeviceDisplayName = j.optString("cloud_device_display_name", "")
        // uuid and device_id intentionally not imported — they're device identity, not config
    )

    private fun profilesToJson(profiles: List<ConnectionProfile>) = JSONArray().apply {
        profiles.forEach { p ->
            put(JSONObject().apply {
                put("name", p.name)
                put("connection_type", p.connectionType.name)
                put("host", p.host)
                put("port", p.port)
                put("username", p.username)
                put("password", p.password)
                put("remote_path", p.remotePath)
                put("is_active", p.isActive)
            })
        }
    }

    private fun profileFromJson(j: JSONObject) = ConnectionProfile(
        name = j.optString("name"),
        connectionType = ConnectionType.entries.firstOrNull { it.name == j.optString("connection_type") }
            ?: ConnectionType.FTP,
        host = j.optString("host"),
        port = j.optInt("port", 21),
        username = j.optString("username"),
        password = j.optString("password"),
        remotePath = j.optString("remote_path", "/"),
        isActive = j.optBoolean("is_active", false)
    )
}
