package com.photoflowmobile.app

import android.app.Application
import androidx.room.Room
import com.photoflowmobile.app.data.datastore.SettingsKeys
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.db.AppDatabase
import com.photoflowmobile.app.data.db.MIGRATION_1_2
import com.photoflowmobile.app.data.db.MIGRATION_2_3
import com.photoflowmobile.app.data.db.MIGRATION_3_4
import com.photoflowmobile.app.data.db.MIGRATION_4_5
import com.photoflowmobile.app.data.db.MIGRATION_5_6
import com.photoflowmobile.app.data.db.MIGRATION_6_7
import com.photoflowmobile.app.data.db.MIGRATION_7_8
import com.photoflowmobile.app.data.db.MIGRATION_8_9
import com.photoflowmobile.app.data.logging.PhotoFlowLog
import com.photoflowmobile.app.data.security.CredentialStore
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PhotoFlowApplication : Application() {

    private companion object { const val TAG = "PhotoFlow/App" }

    val database: AppDatabase by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, "photoflow.db")
            .addMigrations(
                MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9
            )
            .build()
    }

    val credentialStore: CredentialStore by lazy { CredentialStore(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Durable on-device logging. Started before anything else so early startup lines are
        // captured. Logcat is RAM-only and does not survive a reboot, so without this a field
        // test leaves no record of *why* anything happened -- see PhotoFlowLog for the full note.
        PhotoFlowLog.init(this, appScope)
        installCrashHandler()
        appScope.launch {
            settingsDataStore.data
                .map { appSettingsFromPreferences(it) }
                .collect { s ->
                    PhotoFlowLog.enabled = s.loggingEnabled
                    PhotoFlowLog.verbose = s.verboseLogging
                }
        }
        appScope.launch {
            // Guarded: an uncaught throw here runs on a background coroutine during
            // Application.onCreate and takes the whole app down at launch. Credential access is
            // the realistic failure (a Keystore reset or a restored store it cannot decrypt),
            // and a device that cannot upload is far better than one that will not start.
            try { migrateSecretsToCredentialStore() }
            catch (e: Exception) { PhotoFlowLog.e(TAG, "Credential migration failed — continuing", e) }
            try { migrateSettingsSchema() }
            catch (e: Exception) { PhotoFlowLog.e(TAG, "Settings migration failed — continuing", e) }
        }
    }

    /**
     * Writes an uncaught exception to the log file before the process dies, then hands off to
     * the platform handler so the crash still surfaces normally.
     *
     * Without this a field crash leaves nothing behind: the stack trace goes only to logcat,
     * which is gone by the time anyone connects a cable.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { PhotoFlowLog.logCrashSync(thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    // One-time settings rewrites, versioned so each runs exactly once.
    //
    // v1 — bound auto-retry. Builds before this defaulted autoRetryMaxCount to -1 (continuous),
    // which re-uploaded terminally-failed images every few seconds indefinitely. Changing the
    // Kotlin default only helps fresh installs, so installs still carrying -1 are moved to 3.
    // Gating on the schema version means an operator who later chooses Continuous keeps it.
    private suspend fun migrateSettingsSchema() {
        val prefs = settingsDataStore.data.first()
        if ((prefs[SettingsKeys.SETTINGS_SCHEMA_V] ?: 0) >= 1) return
        settingsDataStore.edit { p ->
            if (p[SettingsKeys.AUTO_RETRY_MAX_COUNT] == -1) {
                p[SettingsKeys.AUTO_RETRY_MAX_COUNT] = 3
            }
            p[SettingsKeys.SETTINGS_SCHEMA_V] = 1
        }
    }

    // One-time sweep: moves plaintext FTP passwords and cloud API key into CredentialStore,
    // then blanks the originals. Safe to run on every launch — only acts on non-empty values.
    private suspend fun migrateSecretsToCredentialStore() {
        // FTP passwords: Room → CredentialStore
        val profiles = database.connectionProfileDao().getAllProfilesOnce()
        profiles.forEach { profile ->
            if (profile.password.isNotBlank()) {
                credentialStore.storeFtpPassword(profile.id, profile.password)
                database.connectionProfileDao().update(profile.copy(password = ""))
            }
        }

        // Cloud API key: DataStore → CredentialStore.
        //
        // The DataStore entry is cleared whenever it is present, not only when the credential
        // store happens to be empty. The old conditional meant that once the store held a key,
        // any plaintext copy written afterwards was never cleaned up again — and an earlier
        // build rewrote one on every settings save. AppSettings no longer carries the key at
        // all, so nothing can recreate it, and this sweep removes what older builds left.
        // Keyed on presence, not on the value being non-blank. An earlier build's migration
        // *blanked* this entry rather than removing it, so on already-migrated installs the key
        // is still there holding "". That leaks nothing, but it left the entry behind forever
        // and contradicted the invariant this is supposed to establish: the DataStore does not
        // carry the API key at all. Verified on the Moto G, where the entry survived until this.
        val prefs = settingsDataStore.data.first()
        if (prefs.contains(SettingsKeys.CLOUD_API_KEY)) {
            val apiKey = prefs[SettingsKeys.CLOUD_API_KEY] ?: ""
            if (apiKey.isNotBlank() && credentialStore.getCloudApiKey().isBlank()) {
                credentialStore.storeCloudApiKey(apiKey)
            }
            settingsDataStore.edit { it.remove(SettingsKeys.CLOUD_API_KEY) }
        }
    }
}
