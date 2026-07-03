package com.photoflowmobile.app.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted credential storage backed by Android Keystore via EncryptedSharedPreferences.
 *
 * Stores FTP passwords (keyed by profile id) and the Cloud API key. This prevents
 * credentials from appearing in plaintext in the Room database or DataStore files,
 * and ensures they are excluded from cloud/device-transfer backups (enforced separately
 * in backup_rules.xml and data_extraction_rules.xml).
 *
 * Using security-crypto 1.0.0 with MasterKeys (deprecated-but-functional) for stable API.
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            "photoflow_credentials",
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun storeFtpPassword(profileId: Long, password: String) {
        prefs.edit().putString("ftp_$profileId", password).apply()
    }

    fun getFtpPassword(profileId: Long): String =
        prefs.getString("ftp_$profileId", "") ?: ""

    fun removeFtpPassword(profileId: Long) {
        prefs.edit().remove("ftp_$profileId").apply()
    }

    fun storeCloudApiKey(key: String) {
        prefs.edit().putString("cloud_api_key", key).apply()
    }

    /** Returns the stored API key, or [fallback] if the store has no entry yet. */
    fun getCloudApiKey(fallback: String = ""): String =
        prefs.getString("cloud_api_key", fallback) ?: fallback
}
