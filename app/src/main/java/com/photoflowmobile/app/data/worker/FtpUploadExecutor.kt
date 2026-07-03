package com.photoflowmobile.app.data.worker

import android.util.Log
import androidx.datastore.preferences.core.emptyPreferences
import androidx.work.ListenableWorker
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Executes an FTP upload for [imageId]. Must be called inside [UploadWorker.uploadMutex].
 */
internal object FtpUploadExecutor {

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val DATA_TIMEOUT_MS = 15_000
    private const val TAG = "PhotoFlow/Pipeline"

    suspend fun upload(
        app: PhotoFlowApplication,
        imageId: Long,
        profile: ConnectionProfile?,
        runAttemptCount: Int
    ): ListenableWorker.Result = withContext(Dispatchers.IO) {
        val imageDao = app.database.sessionImageDao()
        val loggingEnabled = appSettingsFromPreferences(
            app.settingsDataStore.data.catch { emit(emptyPreferences()) }.first()
        ).loggingEnabled
        fun log(msg: String) { if (loggingEnabled) Log.i(TAG, msg) }

        val image = imageDao.getById(imageId) ?: return@withContext ListenableWorker.Result.failure()
        val file = File(image.localPath)

        if (!file.exists()) return@withContext ListenableWorker.Result.failure()

        if (profile == null) {
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = "No active FTP connection configured"))
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
        log("[UPLOAD] started: ${image.filename} (id=$imageId) attempt=${runAttemptCount + 1}")

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
                log("[UPLOAD] RETRYABLE (timeout): ${image.filename} (id=$imageId)")
                return@withContext ListenableWorker.Result.retry()
            } catch (e: ConnectException) {
                val phoneIp = localIpAddress() ?: "device"
                val msg = "Cannot connect to ${profile.host}:${profile.port} from $phoneIp — server refused connection or is unreachable"
                imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                log("[UPLOAD] RETRYABLE (connect refused): ${image.filename} (id=$imageId)")
                return@withContext ListenableWorker.Result.retry()
            }

            val password = app.credentialStore.getFtpPassword(profile.id)
            if (!ftp.login(profile.username, password)) {
                val msg = "Login failed — check credentials for ${profile.username}@${profile.host} (server: ${ftp.replyString.trim()})"
                imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
                log("[UPLOAD] TERMINAL (auth): ${image.filename} (id=$imageId)")
                return@withContext ListenableWorker.Result.failure()
            }

            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)

            val basePath = profile.remotePath.trimEnd('/') + "/"
            ftp.changeWorkingDirectory(basePath)

            if (profile.photoOp.isNotBlank()) {
                val subdir = profile.photoOp.trim('/')
                ftp.makeDirectory(subdir)
                if (!ftp.changeWorkingDirectory(subdir)) {
                    throw IOException("Could not navigate to Photo Op subfolder \"$subdir\" on ${profile.host}")
                }
            }

            FileInputStream(file).use { stream ->
                if (!ftp.storeFile(image.filename, stream)) {
                    throw IOException("Upload rejected by server — ${ftp.replyString.trim()}")
                }
            }

            ftp.logout()
            imageDao.update(image.copy(uploadState = UploadState.UPLOADED, errorMessage = null))
            log("[UPLOAD] succeeded: ${image.filename} (id=$imageId)")
            ListenableWorker.Result.success()

        } catch (e: IOException) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "I/O error during upload"
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
            log("[UPLOAD] RETRYABLE (io): ${image.filename} (id=$imageId) — $msg")
            ListenableWorker.Result.retry()
        } catch (e: Exception) {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: "Unexpected error during upload"
            imageDao.update(image.copy(uploadState = UploadState.FAILED, errorMessage = msg))
            log("[UPLOAD] TERMINAL: ${image.filename} (id=$imageId) — $msg")
            ListenableWorker.Result.failure()
        } finally {
            if (ftp.isConnected) try { ftp.disconnect() } catch (_: Exception) {}
        }
    }

    private fun localIpAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    } catch (_: Exception) { null }
}
