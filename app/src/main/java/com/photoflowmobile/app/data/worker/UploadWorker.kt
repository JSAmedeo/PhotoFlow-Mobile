package com.photoflowmobile.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.model.ConnectionType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single upload dispatcher that resolves the active connection type at execution time.
 *
 * Routing at execution time (not enqueue time) fixes the startup race where
 * `activeConnection.value` is null before Room emits, causing jobs queued for
 * CLOUD_API profiles to be dispatched as FTP.
 *
 * Delegates to [FtpUploadExecutor] or [CloudUploadExecutor]. Both executors must
 * be called inside [uploadMutex] so only one upload is in flight at a time regardless
 * of transport type.
 */
class UploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_IMAGE_ID = "image_id"
        val uploadMutex = Mutex()
    }

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        if (imageId == -1L) return Result.failure()

        val app = applicationContext as PhotoFlowApplication

        return uploadMutex.withLock {
            // Read active profile at execution time — never depends on ViewModel StateFlow
            val profile = app.database.connectionProfileDao().getActiveProfileOnce()
            when (profile?.connectionType) {
                ConnectionType.CLOUD_API ->
                    CloudUploadExecutor.upload(app, imageId, profile, runAttemptCount)
                else ->
                    FtpUploadExecutor.upload(app, imageId, profile, runAttemptCount)
            }
        }
    }
}
