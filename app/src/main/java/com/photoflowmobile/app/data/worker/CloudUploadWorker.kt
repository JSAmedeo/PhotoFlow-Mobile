package com.photoflowmobile.app.data.worker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photoflowmobile.app.BuildConfig
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.cloud.CloudApiClient
import com.photoflowmobile.app.data.cloud.CloudSessionService
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.model.ConnectionType
import com.photoflowmobile.app.data.model.SessionKeyType
import com.photoflowmobile.app.data.model.UploadState
import com.photoflowmobile.app.data.model.sessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class CloudUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_IMAGE_ID = "image_id"
        private const val TAG = "PhotoFlow/Pipeline"
        val uploadMutex = Mutex()
    }

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        if (imageId == -1L) return Result.failure()

        val app = applicationContext as PhotoFlowApplication
        val db = app.database
        val imageDao = db.sessionImageDao()
        val sessionDao = db.sessionDao()
        val profileDao = db.connectionProfileDao()

        val settings = appSettingsFromPreferences(
            app.settingsDataStore.data.catch { emit(emptyPreferences()) }.first()
        )
        fun log(msg: String) { if (settings.loggingEnabled) Log.i(TAG, msg) }

        // WorkManager has retried enough — the in-app loop can still resurrect failed items
        val image = imageDao.getById(imageId) ?: return Result.failure()
        if (runAttemptCount >= 10) {
            imageDao.update(image.copy(
                uploadState  = UploadState.FAILED,
                errorMessage = "Max automatic retries reached — tap RETRY to try again"
            ))
            return Result.failure()
        }

        val file = File(image.localPath)
        if (!file.exists()) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "Local file not found"))
            return Result.failure()
        }

        val profile = profileDao.getActiveProfileOnce()
        if (profile == null || profile.connectionType != ConnectionType.CLOUD_API) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED,
                errorMessage = "No active Cloud API connection configured"))
            return Result.failure()
        }

        val deviceId = settings.cloudDeviceId
        if (deviceId == 0) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED,
                errorMessage = "Cloud device not registered — open Settings → Cloud API → REGISTER"))
            return Result.failure()
        }

        val session = sessionDao.getById(image.sessionId)
        if (session == null) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "Session not found"))
            return Result.failure()
        }

        return uploadMutex.withLock {
            if (image.uploadState == UploadState.PENDING) {
                imageDao.update(image.copy(uploadState = UploadState.UPLOADING, errorMessage = null))
            }

            log("[CLOUD] uploading: ${image.filename} (id=$imageId) attempt=${runAttemptCount + 1}")
            log("[CLOUD]   session=${session.sessionKey} type=${session.sessionKeyType} device=$deviceId")
            log("[CLOUD]   captureCode=${image.captureCode} seq=${image.captureSequence} sort=${image.sortOrder}")
            log("[CLOUD]   profile=${profile.host}")

            withContext(Dispatchers.IO) {
                try {
                    val client = CloudApiClient(profile.host, settings.cloudApiKey)

                    // Ensure session exists on backend before uploading (upsert — safe to retry)
                    val sessionResult = CloudSessionService.ensureSession(
                        client, deviceId, session.sessionKey,
                        session.sessionKeyType, session.displayLabel
                    )
                    if (sessionResult == null) {
                        val msg = "Failed to create cloud session for ${session.sessionKey}"
                        imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                        log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — $msg")
                        return@withContext Result.failure()
                    }

                    // Persist cloudSessionId to the session if we got one back and don't have it yet
                    sessionResult.cloudSessionId?.let { cloudId ->
                        if (session.cloudSessionId == null) {
                            sessionDao.update(session.copy(cloudSessionId = cloudId))
                            log("[CLOUD] session confirmed: ${session.sessionKey} cloudSessionId=$cloudId")
                        }
                    } ?: log("[CLOUD] session confirmed: ${session.sessionKey} (already existed or id unavailable)")

                    // Build upload metadata fields — only include what's actually set
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
                        "station_name"       to settings.cloudStationName,   // required; blank is valid
                        "upload_source_path" to image.localPath              // strongly recommended
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
                            Result.success()
                        }
                        status == 409 -> {
                            // UPLOAD_ALREADY_EXISTS — idempotency or capture_code collision; mark uploaded
                            val json    = runCatching { JSONObject(responseBody) }.getOrNull()
                            val photoUid = json?.optString("photo_uid")?.takeIf { it.isNotBlank() }
                            imageDao.update(image.copy(
                                uploadState   = UploadState.UPLOADED,
                                errorMessage  = null,
                                cloudPhotoUid = photoUid
                            ))
                            log("[CLOUD] already-exists (409): ${image.filename} (id=$imageId) photo_uid=$photoUid")
                            Result.success()
                        }
                        status == 401 -> {
                            // AUTH_ERROR — bad/missing API key; no point retrying without key change
                            val msg = "Auth error — check API key in Settings → Cloud API"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] AUTH_ERROR: ${image.filename} (id=$imageId)")
                            Result.failure()
                        }
                        status == 404 -> {
                            // Session not found on server — session upsert needed before upload can proceed
                            val msg = "Session not found on server — re-open or re-scan the session to sync it"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] NON_RETRYABLE (404 session): ${image.filename} (id=$imageId)")
                            Result.failure()
                        }
                        status == 422 -> {
                            // NON_RETRYABLE — missing required fields; indicates a client-side bug
                            val msg = "Upload rejected (422) — missing required fields; check app version"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] NON_RETRYABLE (422): ${image.filename} (id=$imageId) | ${responseBody.take(200)}")
                            Result.failure()
                        }
                        status >= 500 -> {
                            // RETRYABLE_SERVER_ERROR — temporary server issue; WorkManager will back off and retry
                            val msg = "Server error (HTTP $status) — will retry automatically"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] RETRYABLE_SERVER_ERROR: ${image.filename} (id=$imageId) — HTTP $status")
                            Result.retry()
                        }
                        else -> {
                            val msg = "Upload failed: HTTP $status"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — HTTP $status | ${responseBody.take(300)}")
                            Result.failure()
                        }
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    val msg = "Network timeout — will retry automatically"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] RETRYABLE_NETWORK_ERROR (timeout): ${image.filename} (id=$imageId)")
                    Result.retry()
                } catch (e: java.net.ConnectException) {
                    val msg = "Cannot reach server — check Wi-Fi and Base URL in Settings"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] RETRYABLE_NETWORK_ERROR (connect): ${image.filename} (id=$imageId)")
                    Result.retry()
                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Unexpected upload error"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — $msg")
                    Result.failure()
                }
            }
        }
    }

    private fun parseIso8601Millis(s: String): Long? = runCatching {
        // Append Z if no timezone info — backend may return bare ISO timestamps
        java.time.Instant.parse(if ('Z' in s || '+' in s) s else "${s}Z").toEpochMilli()
    }.getOrNull()
}
