package com.photoflowmobile.app.data.worker

import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.ListenableWorker
import com.photoflowmobile.app.BuildConfig
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.cloud.CloudApiClient
import com.photoflowmobile.app.data.cloud.CloudSessionService
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.SessionKeyType
import com.photoflowmobile.app.data.model.UploadState
import com.photoflowmobile.app.data.model.sessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Executes a Cloud API upload for [imageId]. Must be called inside [UploadWorker.uploadMutex].
 */
internal object CloudUploadExecutor {

    private const val TAG = "PhotoFlow/Pipeline"

    suspend fun upload(
        app: PhotoFlowApplication,
        imageId: Long,
        profile: ConnectionProfile,
        runAttemptCount: Int
    ): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val db = app.database
        val imageDao = db.sessionImageDao()
        val sessionDao = db.sessionDao()

        val settings = appSettingsFromPreferences(
            app.settingsDataStore.data.catch { emit(emptyPreferences()) }.first()
        )
        fun log(msg: String) { if (settings.loggingEnabled) Log.i(TAG, msg) }

        val image = imageDao.getById(imageId) ?: return@withContext ListenableWorker.Result.failure()
        val file = File(image.localPath)

        if (!file.exists()) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "Local file not found"))
            return@withContext ListenableWorker.Result.failure()
        }

        val deviceId = settings.cloudDeviceId
        if (deviceId == 0) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED,
                errorMessage = "Cloud device not registered — open Settings → Cloud API → REGISTER"))
            return@withContext ListenableWorker.Result.failure()
        }

        val session = sessionDao.getById(image.sessionId)
        if (session == null) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "Session not found"))
            return@withContext ListenableWorker.Result.failure()
        }

        if (runAttemptCount >= 10) {
            imageDao.update(image.copy(
                uploadState  = UploadState.FAILED,
                errorMessage = "Max automatic retries reached — tap RETRY to try again"
            ))
            return@withContext ListenableWorker.Result.failure()
        }

        if (image.uploadState == UploadState.PENDING) {
            imageDao.update(image.copy(uploadState = UploadState.UPLOADING, errorMessage = null))
        }

        log("[CLOUD] uploading: ${image.filename} (id=$imageId) attempt=${runAttemptCount + 1}")
        log("[CLOUD]   session=${session.sessionKey} type=${session.sessionKeyType} device=$deviceId")
        log("[CLOUD]   captureCode=${image.captureCode} seq=${image.captureSequence} sort=${image.sortOrder}")
        log("[CLOUD]   profile=${profile.host}")

        try {
            // Prefer CredentialStore; fall back to DataStore for pre-migration compat
            val apiKey = app.credentialStore.getCloudApiKey(fallback = settings.cloudApiKey)
            val client = CloudApiClient(profile.host, apiKey)

            val sessionResult = CloudSessionService.ensureSession(
                client, deviceId, session.sessionKey,
                session.sessionKeyType, session.displayLabel
            )
            if (sessionResult == null) {
                val msg = "Failed to create cloud session for ${session.sessionKey}"
                imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — $msg")
                return@withContext ListenableWorker.Result.failure()
            }

            sessionResult.cloudSessionId?.let { cloudId ->
                if (session.cloudSessionId == null) {
                    sessionDao.update(session.copy(cloudSessionId = cloudId))
                    log("[CLOUD] session confirmed: ${session.sessionKey} cloudSessionId=$cloudId")
                }
            } ?: log("[CLOUD] session confirmed: ${session.sessionKey} (already existed or id unavailable)")

            val customLabel: String? = when (session.sessionKeyType) {
                SessionKeyType.CUSTOM.apiValue -> {
                    val base = session.displayLabel ?: session.sessionKey
                    image.captureSequence?.let { "${base}_${it.toString().padStart(2, '0')}" } ?: base
                }
                SessionKeyType.MANUAL.apiValue ->
                    image.captureSequence?.let { "Photo $it" }
                else -> null
            }

            val uploadFields = mutableMapOf(
                "session_key"        to session.sessionKey,
                "device_id"          to deviceId.toString(),
                "idempotency_key"    to image.id.toString(),
                "local_photo_id"     to image.id.toString(),
                "app_version"        to BuildConfig.VERSION_NAME,
                "device_model"       to Build.MODEL,
                "android_version"    to Build.VERSION.RELEASE,
                "station_name"       to settings.cloudStationName,
                "upload_source_path" to image.localPath
            )
            image.captureCode?.let     { uploadFields["capture_code"]     = it }
            image.captureSequence?.let { uploadFields["capture_sequence"] = it.toString() }
            image.sortOrder?.let       { uploadFields["sort_order"]       = it.toString() }
            customLabel?.let           { uploadFields["custom_label"]     = it }

            val (status, responseBody) = client.postMultipart(
                path      = "/photos/upload",
                fields    = uploadFields,
                fileField = "file",
                fileName  = image.filename,
                fileBytes = file.readBytes()
            )

            when {
                status in 200..201 -> {
                    val json       = runCatching { JSONObject(responseBody) }.getOrNull()
                    val photoUid   = json?.optString("photo_uid")?.takeIf { it.isNotBlank() }
                    val photoId    = json?.optLong("photo_id", -1L)?.takeIf { it >= 0 }
                    val uploadedAt = json?.optString("uploaded_at")?.takeIf { it.isNotBlank() }
                        ?.let { parseIso8601Millis(it) }
                    val verb = if (status == 200) "retry-confirmed" else "uploaded"
                    imageDao.update(image.copy(
                        uploadState     = UploadState.UPLOADED,
                        errorMessage    = null,
                        cloudPhotoUid   = photoUid,
                        cloudPhotoId    = photoId,
                        cloudUploadedAt = uploadedAt
                    ))
                    log("[CLOUD] $verb: ${image.filename} (id=$imageId)" +
                        " photo_uid=$photoUid photo_id=$photoId" +
                        " session=${session.sessionKey} captureCode=${image.captureCode} seq=${image.captureSequence}")
                    ListenableWorker.Result.success()
                }
                status == 409 -> {
                    val json     = runCatching { JSONObject(responseBody) }.getOrNull()
                    val photoUid = json?.optString("photo_uid")?.takeIf { it.isNotBlank() }
                    imageDao.update(image.copy(
                        uploadState   = UploadState.UPLOADED,
                        errorMessage  = null,
                        cloudPhotoUid = photoUid
                    ))
                    log("[CLOUD] already-exists (409): ${image.filename} (id=$imageId) photo_uid=$photoUid")
                    ListenableWorker.Result.success()
                }
                status == 401 -> {
                    val msg = "Auth error — check API key in Settings → Cloud API"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] AUTH_ERROR: ${image.filename} (id=$imageId)")
                    ListenableWorker.Result.failure()
                }
                status == 404 -> {
                    val msg = "Session not found on server — re-open or re-scan the session to sync it"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] NON_RETRYABLE (404 session): ${image.filename} (id=$imageId)")
                    ListenableWorker.Result.failure()
                }
                status == 422 -> {
                    val msg = "Upload rejected (422) — missing required fields; check app version"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] NON_RETRYABLE (422): ${image.filename} (id=$imageId) | ${responseBody.take(200)}")
                    ListenableWorker.Result.failure()
                }
                status >= 500 -> {
                    val msg = "Server error (HTTP $status) — will retry automatically"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] RETRYABLE_SERVER_ERROR: ${image.filename} (id=$imageId) — HTTP $status")
                    ListenableWorker.Result.retry()
                }
                else -> {
                    val msg = "Upload failed: HTTP $status"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — HTTP $status | ${responseBody.take(300)}")
                    ListenableWorker.Result.failure()
                }
            }
        } catch (e: SocketTimeoutException) {
            val msg = "Network timeout — will retry automatically"
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
            log("[CLOUD] RETRYABLE_NETWORK_ERROR (timeout): ${image.filename} (id=$imageId)")
            ListenableWorker.Result.retry()
        } catch (e: ConnectException) {
            val msg = "Cannot reach server — check Wi-Fi and Base URL in Settings"
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
            log("[CLOUD] RETRYABLE_NETWORK_ERROR (connect): ${image.filename} (id=$imageId)")
            ListenableWorker.Result.retry()
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Unexpected upload error"
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
            log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — $msg")
            ListenableWorker.Result.failure()
        }
    }

    fun parseIso8601Millis(s: String): Long? = runCatching {
        java.time.Instant.parse(if ('Z' in s || '+' in s) s else "${s}Z").toEpochMilli()
    }.getOrNull()
}
