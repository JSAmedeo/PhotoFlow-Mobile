package com.photoflowmobile.app.data.worker

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

        val image = imageDao.getById(imageId) ?: return Result.failure()
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

            log("[CLOUD] uploading: ${image.filename} (id=$imageId)")
            log("[CLOUD]   session=${session.sessionKey} type=${session.sessionKeyType} device=$deviceId")
            log("[CLOUD]   captureCode=${image.captureCode} seq=${image.captureSequence} sort=${image.sortOrder}")
            log("[CLOUD]   profile=${profile.host}")

            withContext(Dispatchers.IO) {
                try {
                    val client = CloudApiClient(profile.host)

                    // Ensure session exists on backend before uploading
                    val sessionResult = CloudSessionService.ensureSession(client, deviceId, session.sessionKey)
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
                        "session_code" to session.sessionKey,   // wire field name per API contract
                        "device_id"    to deviceId.toString()
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
                            val json        = runCatching { JSONObject(responseBody) }.getOrNull()
                            val photoUid    = json?.optString("photo_uid")?.takeIf { it.isNotBlank() }
                            val photoId     = json?.optLong("photo_id", -1L)?.takeIf { it >= 0 }
                            val uploadedAt  = json?.optString("uploaded_at")?.takeIf { it.isNotBlank() }
                                ?.let { parseIso8601Millis(it) }

                            imageDao.update(image.copy(
                                uploadState     = UploadState.UPLOADED,
                                errorMessage    = null,
                                cloudPhotoUid   = photoUid,
                                cloudPhotoId    = photoId,
                                cloudUploadedAt = uploadedAt
                            ))
                            log("[CLOUD] uploaded: ${image.filename} (id=$imageId)" +
                                " photo_uid=$photoUid photo_id=$photoId" +
                                " session=${session.sessionKey} captureCode=${image.captureCode} seq=${image.captureSequence}")
                            Result.success()
                        }
                        status == 409 -> {
                            // Duplicate — file already on server; safe to mark uploaded
                            imageDao.update(image.copy(uploadState = UploadState.UPLOADED, errorMessage = null))
                            log("[CLOUD] duplicate upload (already on server): ${image.filename} (id=$imageId)")
                            Result.success()
                        }
                        else -> {
                            val msg = "Upload failed: HTTP $status"
                            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                            log("[CLOUD] FAILED: ${image.filename} (id=$imageId) — HTTP $status | ${responseBody.take(300)}")
                            Result.failure()
                        }
                    }
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
