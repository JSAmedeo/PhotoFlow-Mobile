package com.photoflowmobile.app.viewmodel

import android.app.Application
import android.hardware.usb.UsbDevice
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.photoflowmobile.app.PhotoFlowApplication
import com.photoflowmobile.app.data.datastore.appSettingsFromPreferences
import com.photoflowmobile.app.data.datastore.settingsDataStore
import com.photoflowmobile.app.data.db.SessionWithCount
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.DeviceMode
import com.photoflowmobile.app.data.model.OrientationLock
import com.photoflowmobile.app.data.usb.TetheredState
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionImage
import com.photoflowmobile.app.data.model.UploadState
import com.photoflowmobile.app.data.model.buildFilename
import com.photoflowmobile.app.data.repository.SessionRepository
import com.photoflowmobile.app.data.usb.MtpCameraManager
import com.photoflowmobile.app.data.usb.TetheredStatus
import com.photoflowmobile.app.data.worker.FtpUploadWorker
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.settingsDataStore
    private val db = (application as PhotoFlowApplication).database
    private val repository = SessionRepository(
        sessionDao = db.sessionDao(),
        sessionImageDao = db.sessionImageDao()
    )
    private val connectionProfileDao = db.connectionProfileDao()

    // ── Connection profiles ───────────────────────────────────────────────────

    val connectionProfiles: StateFlow<List<ConnectionProfile>> =
        connectionProfileDao.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeConnection: StateFlow<ConnectionProfile?> =
        connectionProfileDao.getActiveProfile()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setActiveConnection(profile: ConnectionProfile) {
        viewModelScope.launch {
            connectionProfileDao.clearAllActive()
            connectionProfileDao.update(profile.copy(isActive = true))
        }
    }

    // ── Native camera ─────────────────────────────────────────────────────────

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
        )
        .build()

    // ── Settings & device mode ────────────────────────────────────────────────

    val deviceMode: StateFlow<DeviceMode> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { appSettingsFromPreferences(it).deviceMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DeviceMode.TETHERED_DSLR)

    val orientationLockEnabled: StateFlow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { appSettingsFromPreferences(it).orientationLockEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val orientationLock: StateFlow<OrientationLock> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { appSettingsFromPreferences(it).orientationLock }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrientationLock.LANDSCAPE)

    val darkMode: StateFlow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { appSettingsFromPreferences(it).darkMode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val loggingEnabled: StateFlow<Boolean> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { appSettingsFromPreferences(it).loggingEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    private fun pipelineLog(msg: String) { if (loggingEnabled.value) Log.i(PIPELINE_TAG, msg) }

    // ── Session state ─────────────────────────────────────────────────────────

    val activeSession: StateFlow<Session?> = repository.getAllSessions()
        .map { sessions -> sessions.firstOrNull { it.status == "active" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _overrideSessionId = MutableStateFlow<Long?>(null)

    val selectedSessionId: StateFlow<Long?> = combine(_overrideSessionId, activeSession) { override, active ->
        override ?: active?.id
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val transferQueue: StateFlow<List<SessionImage>> = repository.getTransferQueue()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentSessions: StateFlow<List<SessionWithCount>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> appSettingsFromPreferences(prefs).sessionHistoryMax.let { if (it > 0) it else -1 } }
        .distinctUntilChanged()
        .flatMapLatest { limit -> repository.getSessionsWithImageCount(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val sessionImages: StateFlow<List<SessionImage>> = selectedSessionId
        .flatMapLatest { id ->
            if (id != null) repository.getImagesForSession(id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Review image selection ────────────────────────────────────────────────

    private val _reviewImageId = MutableStateFlow<Long?>(null)

    val reviewImage: StateFlow<SessionImage?> = combine(_reviewImageId, sessionImages) { selectedId, images ->
        if (selectedId != null) images.firstOrNull { it.id == selectedId } ?: images.firstOrNull()
        else images.firstOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectReviewImage(imageId: Long) { _reviewImageId.value = imageId }

    val selectedSession: StateFlow<Session?> = selectedSessionId
        .flatMapLatest { id ->
            if (id != null) repository.getAllSessions().map { sessions -> sessions.firstOrNull { it.id == id } }
            else flowOf(null)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Tethered DSLR (USB MTP) ───────────────────────────────────────────────

    private val mtpCameraManager = MtpCameraManager(
        context         = application,
        scope           = viewModelScope,
        onImageReceived = { filename, data -> handleTetheredImage(filename, data) }
    )

    val tetheredStatus: StateFlow<TetheredStatus> = mtpCameraManager.status
    val liveViewFrame: StateFlow<ByteArray?>      = mtpCameraManager.liveViewFrame

    fun onUsbDeviceAttached(device: UsbDevice) = mtpCameraManager.onDeviceAttached(device)
    fun onUsbDeviceDetached(device: UsbDevice) = mtpCameraManager.onDeviceDetached(device)
    fun triggerTetheredCapture()               { /* MTP does not support remote shutter */ }

    private suspend fun handleTetheredImage(cameraFilename: String, data: ByteArray) {
        val context = getApplication<Application>()
        pipelineLog("[IMAGE] detected: $cameraFilename (${data.size / 1024}KB)")

        val session = selectedSession.value
        if (session == null) {
            Log.w(PIPELINE_TAG, "[IMAGE] dropped — no active session")
            return
        }

        val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
        val existingCount = repository.getImagesForSession(session.id).first().size
        val renamedFilename = buildFilename(settings, session.barcode, existingCount + 1)
        pipelineLog("[IMAGE] renamed: $cameraFilename → $renamedFilename")

        val outputDir  = File(context.filesDir, "captures").also { it.mkdirs() }
        val outputFile = File(outputDir, renamedFilename)
        try {
            outputFile.writeBytes(data)
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "[IMAGE] save failed: $renamedFilename", e)
            return
        }
        saveBackupIfEnabled(renamedFilename, outputFile)

        val imageId = try {
            repository.saveImage(
                SessionImage(
                    sessionId   = session.id,
                    filename    = renamedFilename,
                    localPath   = outputFile.absolutePath,
                    timestamp   = System.currentTimeMillis(),
                    uploadState = UploadState.PENDING
                )
            )
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "[IMAGE] db insert failed: $renamedFilename", e); return
        }

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "ftp_upload_$imageId",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<FtpUploadWorker>()
                    .setInputData(workDataOf(FtpUploadWorker.KEY_IMAGE_ID to imageId))
                    .build()
            )
            pipelineLog("[UPLOAD] queued: $renamedFilename (id=$imageId)")
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "[UPLOAD] enqueue failed: $renamedFilename id=$imageId", e)
        }
    }

    private fun runBackupCleanupIfEnabled() {
        viewModelScope.launch {
            val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
            if (!settings.autoDeleteBackups) return@launch
            val context = getApplication<Application>()
            val cutoffMs = System.currentTimeMillis() - (settings.autoDeleteAfterDays * 86_400_000L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} < ?"
                    val selectionArgs = arrayOf("Pictures/PhotoFlow/%", (cutoffMs / 1000).toString())
                    val deleted = context.contentResolver.delete(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs
                    )
                    pipelineLog("backup cleanup: deleted $deleted file(s) older than ${settings.autoDeleteAfterDays}d")
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoFlow")
                    if (dir.exists()) {
                        var deleted = 0
                        dir.listFiles()?.forEach { file ->
                            if (file.lastModified() < cutoffMs) {
                                file.delete()
                                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                                deleted++
                            }
                        }
                        pipelineLog("backup cleanup: deleted $deleted file(s) older than ${settings.autoDeleteAfterDays}d")
                    }
                }
            } catch (e: Exception) {
                Log.e(PIPELINE_TAG, "backup cleanup failed", e)
            }
        }
    }

    private suspend fun saveBackupIfEnabled(filename: String, sourceFile: File) {
        val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
        if (!settings.saveBackupToPhone) return
        val context = getApplication<Application>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoFlow")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> sourceFile.inputStream().copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoFlow")
                dir.mkdirs()
                sourceFile.copyTo(File(dir, filename), overwrite = true)
                android.media.MediaScannerConnection.scanFile(context, arrayOf(File(dir, filename).absolutePath), null, null)
            }
            pipelineLog("backup saved: $filename")
        } catch (e: Exception) {
            Log.e(PIPELINE_TAG, "backup failed: $filename", e)
        }
    }

    companion object {
        private const val PIPELINE_TAG = "PhotoFlow/Pipeline"
    }

    // ── Auto-retry loop ───────────────────────────────────────────────────────

    private fun startAutoRetryLoop() {
        viewModelScope.launch {
            while (true) {
                val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
                if (settings.autoRetryEnabled) {
                    val context = getApplication<Application>()
                    repository.getFailedImages().first().forEach { image ->
                        val withinLimit = settings.autoRetryMaxCount == -1 ||
                                image.retryCount < settings.autoRetryMaxCount
                        if (withinLimit) {
                            // Increment retry count only — keep FAILED state and error message visible
                            repository.updateImageState(image.copy(retryCount = image.retryCount + 1))
                            WorkManager.getInstance(context).enqueueUniqueWork(
                                "ftp_upload_${image.id}",
                                ExistingWorkPolicy.REPLACE,
                                OneTimeWorkRequestBuilder<FtpUploadWorker>()
                                    .setInputData(workDataOf(FtpUploadWorker.KEY_IMAGE_ID to image.id))
                                    .build()
                            )
                            pipelineLog("[UPLOAD] retrying: ${image.filename} id=${image.id} attempt=${image.retryCount + 1}")
                        }
                    }
                    delay(settings.autoRetryIntervalSeconds * 1_000L)
                } else {
                    delay(5_000L)
                }
            }
        }
    }

    // ── Init: startup recovery ────────────────────────────────────────────────

    init {
        // App launched
        viewModelScope.launch {
            dataStore.data
                .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                .first().let { if (appSettingsFromPreferences(it).loggingEnabled) Log.i(PIPELINE_TAG, "[APP] launched") }
        }

        // Active connection loaded
        viewModelScope.launch {
            activeConnection.collect { profile ->
                if (profile != null)
                    pipelineLog("[CONFIG] connection loaded: \"${profile.name}\" (${profile.host}:${profile.port})")
            }
        }

        // Camera connection changed
        viewModelScope.launch {
            tetheredStatus.collect { status ->
                pipelineLog(when (status.state) {
                    TetheredState.DISCONNECTED -> "[CAMERA] disconnected"
                    TetheredState.CONNECTING   -> "[CAMERA] connecting"
                    TetheredState.CONNECTED    -> "[CAMERA] connected: ${status.cameraModel ?: "unknown model"}"
                    TetheredState.ERROR        -> "[CAMERA] error: ${status.message ?: "unknown"}"
                })
            }
        }

        // Session started / completed
        var prevActiveId: Long? = null
        var prevActiveSession: Session? = null
        viewModelScope.launch {
            activeSession.collect { session ->
                if (session?.id != prevActiveId && prevActiveId != null) {
                    _overrideSessionId.value = null
                }
                if (session != null && prevActiveSession == null)
                    pipelineLog("[SESSION] started: barcode=${session.barcode}")
                else if (session == null && prevActiveSession != null)
                    pipelineLog("[SESSION] completed: barcode=${prevActiveSession!!.barcode}")
                prevActiveId = session?.id
                prevActiveSession = session
            }
        }

        // Clear review selection when the viewed session changes
        viewModelScope.launch {
            selectedSessionId.collect { _reviewImageId.value = null }
        }

        // Auto-select newest image whenever a new photo arrives
        var prevFirstImageId: Long? = null
        viewModelScope.launch {
            sessionImages.collect { images ->
                val firstId = images.firstOrNull()?.id
                if (firstId != null && firstId != prevFirstImageId && prevFirstImageId != null) {
                    _reviewImageId.value = null
                }
                prevFirstImageId = firstId
            }
        }

        viewModelScope.launch {
            val context = getApplication<Application>()
            val wm = WorkManager.getInstance(context)

            // Reset any items that were mid-upload when the app was killed
            repository.getTransferQueue().first()
                .filter { it.uploadState == UploadState.UPLOADING }
                .forEach { repository.updateImageState(it.copy(uploadState = UploadState.PENDING)) }

            // Re-enqueue pending uploads from before the restart
            repository.getPendingUploads().first().forEach { image ->
                wm.enqueueUniqueWork(
                    "ftp_upload_${image.id}",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<FtpUploadWorker>()
                        .setInputData(workDataOf(FtpUploadWorker.KEY_IMAGE_ID to image.id))
                        .build()
                )
            }
        }

        startAutoRetryLoop()
        runBackupCleanupIfEnabled()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun selectSession(sessionId: Long) {
        _overrideSessionId.value = sessionId
    }

    fun forceRetry(imageId: Long) {
        viewModelScope.launch {
            val image = repository.getImageById(imageId) ?: return@launch
            repository.updateImageState(image.copy(uploadState = UploadState.PENDING, retryCount = 0, errorMessage = null))
            WorkManager.getInstance(getApplication<Application>()).enqueueUniqueWork(
                "ftp_upload_$imageId",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<FtpUploadWorker>()
                    .setInputData(workDataOf(FtpUploadWorker.KEY_IMAGE_ID to imageId))
                    .build()
            )
        }
    }

    fun capturePhoto(sessionId: Long) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
            val session = repository.getAllSessions().first().firstOrNull { it.id == sessionId } ?: run {
                Log.w(PIPELINE_TAG, "capture: no session found for id=$sessionId")
                return@launch
            }
            val existingCount = repository.getImagesForSession(sessionId).first().size
            val renamedFilename = buildFilename(settings, session.barcode, existingCount + 1)

            val outputDir  = File(context.filesDir, "captures").also { it.mkdirs() }
            val outputFile = File(outputDir, renamedFilename)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

            Log.d(PIPELINE_TAG, "capture: firing filename=$renamedFilename session=${session.barcode}")
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        viewModelScope.launch {
                            saveBackupIfEnabled(renamedFilename, outputFile)
                            val imageId = repository.saveImage(
                                SessionImage(
                                    sessionId   = sessionId,
                                    filename    = renamedFilename,
                                    localPath   = outputFile.absolutePath,
                                    timestamp   = System.currentTimeMillis(),
                                    uploadState = UploadState.PENDING
                                )
                            )
                            Log.d(PIPELINE_TAG, "capture: saved imageId=$imageId filename=$renamedFilename")
                            WorkManager.getInstance(context).enqueueUniqueWork(
                                "ftp_upload_$imageId",
                                ExistingWorkPolicy.KEEP,
                                OneTimeWorkRequestBuilder<FtpUploadWorker>()
                                    .setInputData(workDataOf(FtpUploadWorker.KEY_IMAGE_ID to imageId))
                                    .build()
                            )
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        Log.e(PIPELINE_TAG, "capture: FAILED code=${exception.imageCaptureError} msg=${exception.message}")
                    }
                }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        mtpCameraManager.release()
    }
}
