package com.photoflowmobile.app.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.datastore.toPreferences
import com.photoflowmobile.app.data.model.AppSettings
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.ConnectionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient
import java.io.IOException

class ConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.settingsDataStore
    private val connectionProfileDao = (application as PhotoFlowApplication).database.connectionProfileDao()

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

    fun testConnection(profile: ConnectionProfile, onResult: (String?) -> Unit) {
        if (profile.connectionType != ConnectionType.FTP) {
            onResult("Connection test not supported for this type")
            return
        }
        viewModelScope.launch {
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
    }
}
