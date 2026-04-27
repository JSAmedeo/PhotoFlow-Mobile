package com.photoflowmobile.app.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.model.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream
import java.net.ConnectException
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class FtpUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_IMAGE_ID = "image_id"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val DATA_TIMEOUT_MS = 15_000
        private const val TAG = "PhotoFlow/Pipeline"
        val uploadMutex = Mutex()
    }

    override suspend fun doWork(): Result {
        val imageId = inputData.getLong(KEY_IMAGE_ID, -1L)
        if (imageId == -1L) return Result.failure()

        val app = applicationContext as PhotoFlowApplication
        val db = app.database
        val imageDao = db.sessionImageDao()
        val profileDao = db.connectionProfileDao()

        val loggingEnabled = appSettingsFromPreferences(
            app.settingsDataStore.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.first()
        ).loggingEnabled
        fun workerLog(msg: String) { if (loggingEnabled) Log.i(TAG, msg) }

        val image = imageDao.getById(imageId) ?: return Result.failure()
        val file = File(image.localPath)
        if (!file.exists()) return Result.failure()

        val profile = profileDao.getActiveProfileOnce() ?: run {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "No active FTP connection configured"))
            return Result.failure()
        }

        return uploadMutex.withLock {
            // Only show UPLOADING indicator for fresh PENDING uploads, not silent background retries
            if (image.uploadState == UploadState.PENDING) {
                imageDao.update(image.copy(uploadState = UploadState.UPLOADING, errorMessage = null))
            }
            workerLog("[UPLOAD] started: ${image.filename} (id=$imageId)")
            withContext(Dispatchers.IO) {
                val ftp = FTPClient()
                ftp.connectTimeout = CONNECT_TIMEOUT_MS
                ftp.setDataTimeout(java.time.Duration.ofMillis(DATA_TIMEOUT_MS.toLong()))

                try {
                    try {
                        ftp.connect(profile.host, profile.port)
                    } catch (e: SocketTimeoutException) {
                        val phoneIp = localIpAddress() ?: "device"
                        val msg = "Connection timed out — could not reach ${profile.host}:${profile.port} from $phoneIp within ${CONNECT_TIMEOUT_MS / 1000}s"
                        imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                        workerLog("[UPLOAD] failed: ${image.filename} (id=$imageId) — connection timed out")
                        return@withContext Result.failure()
                    } catch (e: ConnectException) {
                        val phoneIp = localIpAddress() ?: "device"
                        val msg = "Cannot connect to ${profile.host}:${profile.port} from $phoneIp — server refused connection or is unreachable"
                        imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                        workerLog("[UPLOAD] failed: ${image.filename} (id=$imageId) — server unreachable")
                        return@withContext Result.failure()
                    }

                    if (!ftp.login(profile.username, profile.password)) {
                        val msg = "Login failed — check credentials for ${profile.username}@${profile.host} (server: ${ftp.replyString.trim()})"
                        imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                        workerLog("[UPLOAD] failed: ${image.filename} (id=$imageId) — authentication failed")
                        return@withContext Result.failure()
                    }

                    ftp.enterLocalPassiveMode()
                    ftp.setFileType(FTP.BINARY_FILE_TYPE)

                    val remotePath = profile.remotePath.trimEnd('/') + "/"
                    ftp.changeWorkingDirectory(remotePath)

                    FileInputStream(file).use { stream ->
                        if (!ftp.storeFile(image.filename, stream)) {
                            throw Exception("Upload rejected by server — ${ftp.replyString.trim()}")
                        }
                    }

                    ftp.logout()
                    imageDao.update(image.copy(uploadState = UploadState.UPLOADED, errorMessage = null))
                    workerLog("[UPLOAD] succeeded: ${image.filename} (id=$imageId)")
                    Result.success()

                } catch (e: Exception) {
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "Unexpected error during upload"
                    imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                    workerLog("[UPLOAD] failed: ${image.filename} (id=$imageId) — upload error")
                    Result.failure()
                } finally {
                    if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (_: Exception) { null }
}
