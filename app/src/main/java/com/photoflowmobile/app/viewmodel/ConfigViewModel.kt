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
import androidx.room.withTransaction
import com.photoflowmobile.app.BuildConfig
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.cloud.CloudApiClient
import com.photoflowmobile.app.data.cloud.CloudDeviceService
import com.photoflowmobile.app.data.cloud.CloudManifestService
import com.photoflowmobile.app.data.cloud.RegistrationResult
import com.photoflowmobile.app.data.logging.PhotoFlowLog
import com.photoflowmobile.app.data.datastore.SettingsKeys
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.datastore.toPreferences
import com.photoflowmobile.app.data.model.*
import com.photoflowmobile.app.data.settings.SettingsExport
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
    /** [profilesNeedingPassword] names the FTP profiles whose passwords must be re-entered. */
    data class ImportSuccess(
        val profilesNeedingPassword: List<String> = emptyList(),
        val apiKeyNeeded: Boolean = false
    ) : SettingsTransferResult()
    data class Error(val message: String) : SettingsTransferResult()
}

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.settingsDataStore
    private val app = application as PhotoFlowApplication
    private val db = app.database
    private val credentialStore = app.credentialStore
    private val connectionProfileDao = db.connectionProfileDao()
    private val sessionDao = db.sessionDao()
    private val sessionImageDao = db.sessionImageDao()

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

    private val _debugRetryState = MutableStateFlow("")
    val debugRetryState: StateFlow<String> = _debugRetryState.asStateFlow()

    fun clearTransferResult() { _transferResult.value = null }
    fun clearManifestResult() { _manifestResult.value = "" }

    fun debugRetryLastUpload() {
        viewModelScope.launch(Dispatchers.IO) {
            val image = sessionImageDao.getLastUploadedImage()
            if (image == null) {
                _debugRetryState.value = "No uploaded photo found"
                return@launch
            }
            // Reset to PENDING — keeps same id (= idempotency_key / local_photo_id)
            sessionImageDao.update(image.copy(uploadState = com.photoflowmobile.app.data.model.UploadState.PENDING, errorMessage = null))
            val constraints = androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
            androidx.work.WorkManager.getInstance(getApplication())
                .enqueueUniqueWork(
                    "upload_${image.id}",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<com.photoflowmobile.app.data.worker.UploadWorker>()
                        .setInputData(androidx.work.workDataOf(com.photoflowmobile.app.data.worker.UploadWorker.KEY_IMAGE_ID to image.id))
                        .setConstraints(constraints)
                        .setBackoffCriteria(
                            androidx.work.BackoffPolicy.EXPONENTIAL,
                            androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                            java.util.concurrent.TimeUnit.MILLISECONDS
                        )
                        .build()
                )
            _debugRetryState.value = "Retrying id=${image.id} idempotency_key=${image.id} prev_uid=${image.cloudPhotoUid ?: "none"}"
        }
    }

    /**
     * The Cloud API key, read from and written to [credentialStore] only.
     *
     * Kept off [AppSettings] deliberately: that model is serialised straight into DataStore in
     * plaintext, so a field there meant every unrelated settings save rewrote the key in the
     * clear. Backed by a MutableStateFlow because EncryptedSharedPreferences is not observable.
     */
    private val _cloudApiKey = MutableStateFlow(credentialStore.getCloudApiKey())
    val cloudApiKey: StateFlow<String> = _cloudApiKey.asStateFlow()

    fun setCloudApiKey(key: String) {
        credentialStore.storeCloudApiKey(key)
        _cloudApiKey.value = key
    }

    fun save(settings: AppSettings) {
        viewModelScope.launch {
            dataStore.edit { prefs -> settings.toPreferences(prefs) }
        }
    }

    fun getFtpPassword(profileId: Long): String = credentialStore.getFtpPassword(profileId)

    fun upsertProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            // Store FTP password in CredentialStore; blank it in Room so it is never persisted plaintext
            if (profile.password.isNotBlank()) {
                credentialStore.storeFtpPassword(profile.id, profile.password)
            }
            val sanitized = profile.copy(password = "")
            if (sanitized.id == 0L) connectionProfileDao.insert(sanitized)
            else connectionProfileDao.update(sanitized)
        }
    }

    fun deleteProfile(profile: ConnectionProfile) {
        viewModelScope.launch {
            credentialStore.removeFtpPassword(profile.id)
            connectionProfileDao.delete(profile)
        }
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
                        val password = credentialStore.getFtpPassword(profile.id)
                        val ok = ftp.login(profile.username, password)
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
                        val apiKey = credentialStore.getCloudApiKey()
                        val (status, body) = CloudApiClient(profile.host, apiKey).get("/health")
                        if (status == 200 && body.contains("ok")) null
                        else if (status == 401) "Auth error (401) — check API key"
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
            if (s.cloudSetupCode.isBlank()) {
                _cloudRegistrationState.value = "Enter a Setup Code first"
                return@launch
            }
            // Generate and persist UUID before the API call so it's stable across retries
            val uuid = if (s.cloudDeviceUuid.isNotBlank()) {
                s.cloudDeviceUuid
            } else {
                UUID.randomUUID().toString().also { newUuid ->
                    dataStore.edit { prefs -> prefs[SettingsKeys.CLOUD_DEVICE_UUID] = newUuid }
                }
            }
            val displayName = s.cloudDeviceDisplayName.ifBlank { "PhotoFlow Device" }
            _cloudRegistrationState.value = "Registering…"
            try {
                val apiKey = credentialStore.getCloudApiKey()
                when (val result = CloudDeviceService.register(
                    CloudApiClient(profile.host, apiKey),
                    setupCode      = s.cloudSetupCode,
                    deviceUuid     = uuid,
                    displayName    = displayName,
                    appVersion     = BuildConfig.VERSION_NAME,
                    deviceModel    = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    stationName    = s.cloudStationName
                )) {
                    is RegistrationResult.Success -> {
                        val device = result.device
                        dataStore.edit { prefs ->
                            prefs[SettingsKeys.CLOUD_DEVICE_UUID] = device.deviceUuid
                            prefs[SettingsKeys.CLOUD_DEVICE_ID]   = device.deviceId
                            prefs[SettingsKeys.CLOUD_VENUE_ID]    = device.venueId
                            prefs[SettingsKeys.CLOUD_VENUE_SLUG]  = device.venueSlug
                        }
                        _cloudRegistrationState.value = "Registered — device_id=${device.deviceId}"
                    }
                    RegistrationResult.AuthError ->
                        _cloudRegistrationState.value = "Auth error (401) — check API key in Settings → Cloud API"
                    RegistrationResult.ConfigError ->
                        _cloudRegistrationState.value = "Invalid setup code (404) — verify the code with your venue"
                    is RegistrationResult.ServerError ->
                        _cloudRegistrationState.value = "Registration failed: HTTP ${result.status}"
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
            if (venueId == 0) {
                _manifestResult.value = "Device not registered — tap REGISTER DEVICE first"
                return@launch
            }
            _manifestResult.value = "Fetching…"
            try {
                val apiKey = credentialStore.getCloudApiKey()
                _manifestResult.value = CloudManifestService.fetch(
                    CloudApiClient(profile.host, apiKey), sessionKey, venueId
                )
            } catch (e: Exception) {
                _manifestResult.value = "Error: ${e.message}"
            }
        }
    }

    /**
     * Writes the on-device logs to a single text file in Downloads.
     *
     * Exists so an operator can retrieve a session's logs without a laptop or ADB. Logcat is
     * RAM-only and does not survive a reboot, so after a field test it is routinely empty —
     * this and the file log behind it are the only durable record.
     */
    fun exportLogs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = PhotoFlowLog.logFiles()
                if (files.isEmpty()) {
                    _transferResult.value = SettingsTransferResult.Error(
                        "No logs on device yet — check Enable logging is on"
                    )
                    return@launch
                }
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                val fileName = "photoflow-logs-$stamp.txt"
                // Oldest first so the export reads chronologically.
                val body = buildString {
                    files.sortedBy { it.name }.forEach { f ->
                        append("===== ").append(f.name).append(" (")
                            .append(f.length()).append(" bytes) =====\n")
                        append(runCatching { f.readText() }.getOrElse { "<unreadable: ${it.message}>\n" })
                        append('\n')
                    }
                }
                writeToDownloads(context, fileName, body, "text/plain")
                _transferResult.value = SettingsTransferResult.ExportSuccess("Downloads/$fileName")
            } catch (e: Exception) {
                _transferResult.value = SettingsTransferResult.Error("Log export failed: ${e.message}")
            }
        }
    }

    /** Shared by the settings and log exports — MediaStore on Q+, direct path below. */
    private fun writeToDownloads(context: Context, fileName: String, body: String, mime: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Could not create file in Downloads")
            resolver.openOutputStream(uri)?.use { out ->
                OutputStreamWriter(out).use { it.write(body) }
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, fileName).writeText(body)
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
                    // Explicit marker so a future importer can tell "no secrets in this file"
                    // from "secrets present but empty", and so the file is self-describing.
                    put("secrets_included", false)
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
                val needPassword = mutableListOf<String>()
                if (profilesJson != null) {
                    // Drop the credentials belonging to the profiles being replaced, otherwise
                    // deleteAll() strands them under ids nothing references any more.
                    connectionProfileDao.getAllProfilesOnce()
                        .forEach { credentialStore.removeFtpPassword(it.id) }
                    db.withTransaction {
                        connectionProfileDao.deleteAll()
                        for (i in 0 until profilesJson.length()) {
                            val parsed = profileFromJson(profilesJson.getJSONObject(i))
                            connectionProfileDao.insert(parsed)
                            if (parsed.connectionType == ConnectionType.FTP) needPassword += parsed.name
                        }
                    }
                }

                // Exports carry no secrets, so the operator has to supply them again. Name what
                // is missing rather than leaving uploads to fail later with an auth error.
                _transferResult.value = SettingsTransferResult.ImportSuccess(
                    profilesNeedingPassword = needPassword,
                    apiKeyNeeded = credentialStore.getCloudApiKey().isBlank()
                )
            } catch (e: Exception) {
                _transferResult.value = SettingsTransferResult.Error("Import failed: ${e.message}")
            }
        }
    }

    // Content lives in SettingsExport so the "no credentials in the export" rule is
    // unit-testable without a JSON implementation on the JVM. See SettingsExportTest.
    private fun settingsToJson(s: AppSettings) = JSONObject(SettingsExport.settingsToMap(s))

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
        autoRetryMaxCount = j.optInt("auto_retry_max_count", 3),
        sessionHistoryMax = j.optInt("session_history_max", 50),
        saveBackupToPhone = j.optBoolean("save_backup_to_phone", false),
        autoDeleteBackups = j.optBoolean("auto_delete_backups", false),
        autoDeleteAfterDays = j.optInt("auto_delete_after_days", 30),
        orientationLockEnabled = j.optBoolean("orientation_lock_enabled", false),
        orientationLock = OrientationLock.entries.firstOrNull { it.name == j.optString("orientation_lock") }
            ?: OrientationLock.LANDSCAPE,
        cloudVenueSlug = j.optString("cloud_venue_slug", ""),
        cloudVenueId = j.optInt("cloud_venue_id", 0),
        cloudDeviceDisplayName = j.optString("cloud_device_display_name", ""),
        cloudStationName = j.optString("cloud_station_name", "")
        // Intentionally not imported:
        //  - uuid and device_id: device identity, not config
        //  - cloud_api_key and cloud_setup_code: credentials. Older exports may still contain
        //    them; ignoring the fields means importing such a file cannot reintroduce a secret
        //    into plaintext settings.
    )

    private fun profilesToJson(profiles: List<ConnectionProfile>) = JSONArray().apply {
        profiles.forEach { put(JSONObject(SettingsExport.profileToMap(it))) }
    }

    private fun profileFromJson(j: JSONObject) = ConnectionProfile(
        name = j.optString("name"),
        connectionType = ConnectionType.entries.firstOrNull { it.name == j.optString("connection_type") }
            ?: ConnectionType.FTP,
        host = j.optString("host"),
        port = j.optInt("port", 21),
        username = j.optString("username"),
        // Always blank, even when importing an older export that still carries a password —
        // reading it would write a secret this build refuses to store.
        password = "",
        remotePath = j.optString("remote_path", "/"),
        photoOp = j.optString("photo_op", ""),
        isActive = j.optBoolean("is_active", false)
    )
}
