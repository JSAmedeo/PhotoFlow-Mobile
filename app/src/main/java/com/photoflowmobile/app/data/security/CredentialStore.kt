package com.photoflowmobile.app.data.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.io.File

/**
 * Encrypted credential storage backed by Android Keystore via EncryptedSharedPreferences.
 *
 * Holds FTP passwords (keyed by profile id) and the Cloud API key. This is the single
 * authoritative store for those secrets — they are never written to Room or DataStore.
 * Excluded from backup in backup_rules.xml and data_extraction_rules.xml.
 *
 * Using security-crypto 1.0.0 with MasterKeys (deprecated-but-functional) for stable API.
 */
class CredentialStore(context: Context) {

    private val appContext = context.applicationContext

    /**
     * In-memory last resort. Used only when the encrypted store cannot be opened at all, so the
     * app degrades to "credentials unavailable this run" instead of crashing on every access.
     */
    private val memoryFallback = mutableMapOf<String, String>()
    @Volatile private var usingMemoryFallback = false

    private val prefs: SharedPreferences? by lazy { openOrRecover() }

    /**
     * Opens the encrypted store, recovering once from a corrupt or undecryptable file.
     *
     * This can genuinely fail in the field. The most likely cause is a restored backup or
     * device transfer that carried the ciphertext across without the hardware Keystore key
     * (which cannot be exported) — the backup rules now exclude the file, but an install
     * predating that change, or a Keystore reset after a system update, produces the same
     * state. Previously this was an uncaught `by lazy` reached from Application.onCreate, so
     * the failure took the whole app down at launch rather than just the credentials.
     */
    private fun openOrRecover(): SharedPreferences? {
        try {
            return create()
        } catch (e: Exception) {
            Log.w(TAG, "Encrypted prefs unreadable — discarding and recreating", e)
        }
        // Delete the undecryptable file and try once more. Credentials are lost either way;
        // a usable empty store lets the operator re-enter them instead of reinstalling.
        return try {
            File(appContext.filesDir.parentFile, "shared_prefs/$PREFS_NAME.xml").delete()
            create().also { Log.i(TAG, "Encrypted prefs recreated — credentials must be re-entered") }
        } catch (e: Exception) {
            usingMemoryFallback = true
            Log.e(TAG, "Encrypted prefs unavailable — falling back to memory for this run", e)
            null
        }
    }

    private fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** True when the encrypted store could not be opened and secrets live only in memory. */
    val isDegraded: Boolean get() { prefs; return usingMemoryFallback }

    private fun put(key: String, value: String) {
        val p = prefs
        if (p != null) p.edit().putString(key, value).apply() else memoryFallback[key] = value
    }

    private fun get(key: String, fallback: String): String =
        prefs?.getString(key, fallback) ?: memoryFallback[key] ?: fallback

    private fun remove(key: String) {
        val p = prefs
        if (p != null) p.edit().remove(key).apply() else memoryFallback.remove(key)
    }

    fun storeFtpPassword(profileId: Long, password: String) = put("ftp_$profileId", password)

    fun getFtpPassword(profileId: Long): String = get("ftp_$profileId", "")

    fun removeFtpPassword(profileId: Long) = remove("ftp_$profileId")

    fun storeCloudApiKey(key: String) = put(KEY_CLOUD_API, key)

    /**
     * The Cloud API key, or [fallback] if nothing is stored.
     *
     * [fallback] exists only for the one-time DataStore migration in PhotoFlowApplication.
     * Callers making API requests must not pass one — this store is the single source of truth,
     * and reintroducing a DataStore fallback would resurrect the plaintext copy this replaced.
     */
    fun getCloudApiKey(fallback: String = ""): String = get(KEY_CLOUD_API, fallback)

    private companion object {
        const val TAG = "PhotoFlow/Credentials"
        const val PREFS_NAME = "photoflow_credentials"
        const val KEY_CLOUD_API = "cloud_api_key"
    }
}
