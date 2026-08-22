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
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
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
import com.photoflowmobile.app.data.model.buildCaptureCode
import com.photoflowmobile.app.data.model.buildFilename
import com.photoflowmobile.app.data.model.sessionKey
import com.photoflowmobile.app.data.cloud.CloudApiClient
import com.photoflowmobile.app.data.cloud.CloudDeviceService
import com.photoflowmobile.app.data.cloud.RegistrationResult
import com.photoflowmobile.app.data.datastore.SettingsKeys
import com.photoflowmobile.app.data.model.ConnectionType
import com.photoflowmobile.app.data.repository.SessionRepository
import com.photoflowmobile.app.data.usb.MtpCameraManager
import com.photoflowmobile.app.data.usb.TetheredStatus
import com.photoflowmobile.app.data.worker.UploadWorker
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.photoflowmobile.app.data.logging.PhotoFlowLog as Log
import androidx.datastore.preferences.core.edit
import com.photoflowmobile.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStore = application.settingsDataStore
    private val db = (application as PhotoFlowApplication).database
    private val repository = SessionRepository(
        db = db,
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

    // Selection IS activation. There is no separate "viewing" session: tapping a row in Session
    // History activates that session (see selectSession), so every consumer of selectedSessionId
    // — thumbnails, review pane, shot count — follows the one active session, and both capture
    // paths write to it. An earlier override-based model let tethered shots land in a session the
    // operator was merely reviewing.
    val selectedSessionId: StateFlow<Long?> = activeSession
        .map { it?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // True when the active session is not the most recently started one — i.e. the operator has
    // deliberately switched capture back to an earlier session. Drives the MainScreen banner so a
    // stray tap in Session History can never silently misfile shots.
    val pastSessionActive: StateFlow<Boolean> = repository.getAllSessions()
        .map { sessions ->
            val active = sessions.firstOrNull { it.status == "active" } ?: return@map false
            val newest = sessions.maxByOrNull { it.startTime } ?: return@map false
            active.id != newest.id
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val transferQueue: StateFlow<List<SessionImage>> = repository.getTransferQueue()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val recentSessions: StateFlow<List<SessionWithCount>> = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> appSettingsFromPreferences(prefs).sessionHistoryMax.let { if (it > 0) it else -1 } }
        .distinctUntilChanged()
        .flatMapLatest { limit -> repository.getSessionsWithImageCount(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Eagerly so Room is queried immediately at ViewModel creation — value is accurate long
    // before MainScreen is first composed (splash + navigation takes ≥1.4 s).
    val noSessionsExist: StateFlow<Boolean> = repository.getAllSessions()
        .map { it.isEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // ── Tethered DSLR (USB MTP) ───────────────────────────────────────────────

    private val mtpCameraManager = MtpCameraManager(
        context         = application,
        scope           = viewModelScope,
        onImageReceived = { filename, data -> handleTetheredImage(filename, data) }
    )

    val tetheredStatus: StateFlow<TetheredStatus> = mtpCameraManager.status
    val liveViewFrame: StateFlow<ByteArray?>      = mtpCameraManager.liveViewFrame

    /**
     * Count of shots that appeared on the camera card while the cable was disconnected and were
     * therefore never imported. 0 when there is nothing to report.
     *
     * This is a warning only — the images are not recovered. See "disconnected-shot recovery" in
     * .claude/prompts/field-readiness-pass.md for the deferred feature that would import them.
     */
    val missedWhileDisconnected: StateFlow<Int> = mtpCameraManager.missedWhileDisconnected
    fun dismissMissedWarning() = mtpCameraManager.dismissMissedWarning()

    // ── No-session prompt ─────────────────────────────────────────────────────

    private val _showNoSessionPrompt = MutableStateFlow(false)
    val showNoSessionPrompt: StateFlow<Boolean> = _showNoSessionPrompt.asStateFlow()
    fun dismissNoSessionPrompt() { _showNoSessionPrompt.value = false }
    fun showNoSessionPrompt()    { _showNoSessionPrompt.value = true }

    fun onUsbDeviceAttached(device: UsbDevice) = mtpCameraManager.onDeviceAttached(device)
    fun onUsbDeviceDetached(device: UsbDevice) = mtpCameraManager.onDeviceDetached(device)
    fun rescanUsbDevices() = mtpCameraManager.rescanForAttachedCamera()
    fun triggerTetheredCapture()               { /* MTP does not support remote shutter */ }

    private suspend fun handleTetheredImage(cameraFilename: String, data: ByteArray) {
        val context = getApplication<Application>()
        pipelineLog("[IMAGE] detected: $cameraFilename (${data.size / 1024}KB)")

        // Read straight from Room, not from a WhileSubscribed StateFlow: this path runs on the
        // USB poll loop and may fire while the app is backgrounded with no UI subscribed, where
        // `.value` would be stale or still null.
        val session = repository.getActiveSession()
        if (session == null) {
            Log.w(PIPELINE_TAG, "[IMAGE] dropped — no active session")
            return
        }

        val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
        val outputDir = File(context.filesDir, "captures").also { it.mkdirs() }

        // Hold the mutex from count-read through DB insert so concurrent tethered arrivals
        // never read the same count and collide on a sequence number.
        val result = captureMutex.withLock {
            val seq = repository.getImagesForSession(session.id).first().size + 1
            val filename = buildFilename(settings, session.barcode, seq)
            val captureCode = buildCaptureCode(session, seq)
            pipelineLog("[IMAGE] renamed: $cameraFilename → $filename (session=${session.sessionKey} seq=$seq captureCode=$captureCode)")
            val file = File(outputDir, filename)
            // This whole path runs on the single PhotoFlow-USB thread (the poll loop calls
            // onImageReceived directly), so a 25 MB write here stalls image polling for its
            // duration. Stay inside captureMutex — the filename depends on the sequence number —
            // but push the blocking write onto the IO pool.
            try { withContext(Dispatchers.IO) { file.writeBytes(data) } }
            catch (e: Exception) { Log.e(PIPELINE_TAG, "[IMAGE] save failed: $filename", e); return@withLock null }
            val id = try {
                repository.saveImage(
                    SessionImage(
                        sessionId       = session.id,
                        filename        = filename,
                        localPath       = file.absolutePath,
                        timestamp       = System.currentTimeMillis(),
                        uploadState     = UploadState.PENDING,
                        captureCode     = captureCode,
                        captureSequence = seq,
                        sortOrder       = seq
                    )
                )
            } catch (e: Exception) { Log.e(PIPELINE_TAG, "[IMAGE] db insert failed: $filename", e); return@withLock null }
            Triple(id, filename, file)
        } ?: return

        val (imageId, renamedFilename, outputFile) = result
        saveBackupIfEnabled(renamedFilename, outputFile)
        try {
            enqueueUpload(imageId, renamedFilename)
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

    // Copies a full-size image, so it must never run on the caller's thread: the tethered path
    // calls it from the PhotoFlow-USB poll thread (stalling image import) and the native path
    // from the main thread (janking the UI mid-shoot).
    private suspend fun saveBackupIfEnabled(filename: String, sourceFile: File) = withContext(Dispatchers.IO) {
        val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
        if (!settings.saveBackupToPhone) return@withContext
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

    // Serialises the read-count → assign-sequence → write-file → DB-insert pipeline so that
    // rapid captures (tethered burst or quick native taps) never read the same count and produce
    // duplicate sequence numbers. Hold time is short: one DB count + one DB insert.
    private val captureMutex = Mutex()

    companion object {
        private const val PIPELINE_TAG = "PhotoFlow/Pipeline"
    }

    // ── Upload dispatch ───────────────────────────────────────────────────────

    private fun buildUploadRequest(imageId: Long): androidx.work.OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_IMAGE_ID to imageId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()

    private fun enqueueUpload(imageId: Long, imageName: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val context = getApplication<Application>()
        val via = activeConnection.value?.connectionType?.badge ?: "FTP"
        WorkManager.getInstance(context).enqueueUniqueWork(
            "upload_$imageId", policy, buildUploadRequest(imageId)
        )
        pipelineLog("[UPLOAD] queued: $imageName (id=$imageId) via $via")
    }

    // ── Cloud device registration ─────────────────────────────────────────────

    private fun registerCloudDeviceIfNeeded(baseUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
            if (settings.cloudDeviceId != 0) return@launch   // already registered

            // Generate and persist UUID before the API call so it's stable across retries
            val uuid = if (settings.cloudDeviceUuid.isNotBlank()) {
                settings.cloudDeviceUuid
            } else {
                UUID.randomUUID().toString().also { newUuid ->
                    dataStore.edit { prefs -> prefs[SettingsKeys.CLOUD_DEVICE_UUID] = newUuid }
                }
            }
            val displayName = settings.cloudDeviceDisplayName.ifBlank { "PhotoFlow Device" }
            val setupCode = settings.cloudSetupCode
            if (setupCode.isBlank()) {
                pipelineLog("[CLOUD] registration skipped — no setup code configured")
                return@launch
            }
            val codeLen = setupCode.length
            pipelineLog("[CLOUD] registering device uuid=$uuid setupCode=***${codeLen}chars")
            try {
                val appInstance = getApplication<PhotoFlowApplication>()
                val apiKey = appInstance.credentialStore.getCloudApiKey()
                when (val result = CloudDeviceService.register(
                    CloudApiClient(baseUrl, apiKey),
                    setupCode      = setupCode,
                    deviceUuid     = uuid,
                    displayName    = displayName,
                    appVersion     = BuildConfig.VERSION_NAME,
                    deviceModel    = Build.MODEL,
                    androidVersion = Build.VERSION.RELEASE,
                    stationName    = settings.cloudStationName
                )) {
                    is RegistrationResult.Success -> {
                        val device = result.device
                        dataStore.edit { prefs ->
                            prefs[SettingsKeys.CLOUD_DEVICE_UUID] = device.deviceUuid
                            prefs[SettingsKeys.CLOUD_DEVICE_ID]   = device.deviceId
                            prefs[SettingsKeys.CLOUD_VENUE_ID]    = device.venueId
                            prefs[SettingsKeys.CLOUD_VENUE_SLUG]  = device.venueSlug
                        }
                        pipelineLog("[CLOUD] registered device_id=${device.deviceId} venueSlug=${device.venueSlug}")
                    }
                    RegistrationResult.AuthError ->
                        pipelineLog("[CLOUD] registration AUTH_ERROR — check API key")
                    RegistrationResult.ConfigError ->
                        pipelineLog("[CLOUD] registration CONFIG_ERROR — invalid setup code")
                    is RegistrationResult.ServerError ->
                        pipelineLog("[CLOUD] registration failed HTTP ${result.status}")
                }
            } catch (e: Exception) {
                pipelineLog("[CLOUD] registration error: ${e.message}")
            }
        }
    }

    // ── Auto-retry loop ───────────────────────────────────────────────────────
    //
    // Architecture: WorkManager is the durable retry engine (Result.retry() + CONNECTED
    // constraint + EXPONENTIAL backoff). This loop is the UI-facing supplement: it exists to
    // honour the user-configurable autoRetryEnabled / interval / max-count settings and to
    // bump the visible retryCount counter that the transfer-queue dialog shows.
    //
    // To avoid a duplicate upload when both paths fire at the same time, we check the
    // WorkManager state before enqueuing: if WorkManager already has a job ENQUEUED, RUNNING,
    // or BLOCKED for that image, we skip the re-enqueue (the worker is already in flight).

    private fun startAutoRetryLoop() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            // Ids already reported as exhausted, so the loop logs that once per image instead of
            // every interval. Entries are dropped when the image leaves the failed set (retried
            // by hand, or finally uploaded).
            val loggedExhausted = mutableSetOf<Long>()
            while (true) {
                val settings = dataStore.data.map { appSettingsFromPreferences(it) }.first()
                if (settings.autoRetryEnabled) {
                    val failed = repository.getFailedImages().first()
                    loggedExhausted.retainAll(failed.map { it.id }.toSet())
                    failed.forEach { image ->
                        val withinLimit = settings.autoRetryMaxCount == -1 ||
                                image.retryCount < settings.autoRetryMaxCount
                        if (!withinLimit && loggedExhausted.add(image.id)) {
                            pipelineLog("[UPLOAD] retry limit reached: ${image.filename} id=${image.id} " +
                                    "after ${image.retryCount} attempt(s) — tap RETRY to try again")
                        }
                        if (withinLimit) {
                            // Skip if WorkManager already has an active job for this image
                            val infos = withContext(Dispatchers.IO) {
                                WorkManager.getInstance(context)
                                    .getWorkInfosForUniqueWork("upload_${image.id}").get()
                            }
                            val alreadyQueued = infos.any { info ->
                                info.state in listOf(
                                    WorkInfo.State.ENQUEUED,
                                    WorkInfo.State.RUNNING,
                                    WorkInfo.State.BLOCKED
                                )
                            }
                            if (!alreadyQueued) {
                                // Increment retry count only — keep FAILED state and error message visible
                                repository.updateImageState(image.copy(retryCount = image.retryCount + 1))
                                // KEEP, never REPLACE: REPLACE tears down the existing work request
                                // and resets WorkManager's runAttemptCount to 0, so the executors'
                                // `runAttemptCount >= 10` cap could never trip. The alreadyQueued
                                // check above already prevents duplicates; KEEP is the backstop.
                                enqueueUpload(image.id, image.filename, ExistingWorkPolicy.KEEP)
                                pipelineLog("[UPLOAD] retrying: ${image.filename} id=${image.id} " +
                                        "attempt=${image.retryCount + 1}/${settings.autoRetryMaxCount.takeIf { it != -1 } ?: "∞"}")
                            }
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

        // Session started / switched / completed
        var prevActiveSession: Session? = null
        viewModelScope.launch {
            activeSession.collect { session ->
                val prev = prevActiveSession
                when {
                    session != null && prev == null -> {
                        pipelineLog("[SESSION] started: barcode=${session.barcode}")
                        _showNoSessionPrompt.value = false
                    }
                    // Switching straight from one session to another happens when the operator
                    // picks an earlier session in Session History, or re-scans a known card.
                    session != null && prev != null && session.id != prev.id -> {
                        pipelineLog("[SESSION] switched: barcode=${prev.barcode} -> ${session.barcode}")
                        _showNoSessionPrompt.value = false
                    }
                    session == null && prev != null ->
                        pipelineLog("[SESSION] completed: barcode=${prev.barcode}")
                }
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
            // Reset any items that were mid-upload when the app was killed
            repository.getTransferQueue().first()
                .filter { it.uploadState == UploadState.UPLOADING }
                .forEach { repository.updateImageState(it.copy(uploadState = UploadState.PENDING)) }

            // Re-enqueue pending uploads from before the restart
            repository.getPendingUploads().first().forEach { image ->
                enqueueUpload(image.id, image.filename)
            }
        }

        startAutoRetryLoop()
        runBackupCleanupIfEnabled()

        // When the user explicitly switches to tethered mode, clear any stale permission-denied
        // or isConnecting state and immediately rescan. This handles the first-launch case where
        // the Android CAMERA dialog suppressed the USB dialog, and the accidental-swipe case
        // where permissionDenied was set and the retry loop permanently stopped.
        //
        // IMPORTANT: only fires on an explicit NATIVE→TETHERED transition, NOT on the initial
        // DataStore value. A StateFlow emits its current value to every new collector, so using
        // .filter { == TETHERED_DSLR }.collect { resetAndRescan() } would fire resetAndRescan()
        // on every app start/process-death restart — racing with the MtpCameraManager.init{}
        // device scan that is already in progress. That race clears isConnecting while openConnection
        // is in its 800ms Samsung sleep, allowing a second openConnection to queue, which then
        // resets the USB endpoint (SET_INTERFACE + CLEAR_HALT) and poisons both sessions.
        var prevDeviceMode: DeviceMode? = null
        viewModelScope.launch {
            deviceMode.collect { mode ->
                val transitionToTethered = prevDeviceMode != null
                        && prevDeviceMode != DeviceMode.TETHERED_DSLR
                        && mode == DeviceMode.TETHERED_DSLR
                prevDeviceMode = mode
                if (transitionToTethered) mtpCameraManager.resetAndRescan()
            }
        }

        // Auto-register with cloud API when a CLOUD_API profile becomes active and device is not yet registered
        viewModelScope.launch {
            activeConnection
                .filterNotNull()
                .filter { it.connectionType == ConnectionType.CLOUD_API }
                .distinctUntilChanged { a, b -> a.host == b.host }
                .collect { profile -> registerCloudDeviceIfNeeded(profile.host) }
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    /**
     * Makes [sessionId] the active session.
     *
     * Selection is activation: the operator may need to add a shot to an earlier session, so
     * picking one in Session History moves capture there — both native and tethered. MainScreen
     * shows a persistent banner ([pastSessionActive]) whenever the active session is not the
     * newest, so this can never happen invisibly.
     */
    fun selectSession(sessionId: Long) {
        viewModelScope.launch { repository.activateSession(sessionId) }
    }

    /** Activates the most recently started session — backs the banner's GO TO NEWEST action. */
    fun activateNewestSession() {
        viewModelScope.launch {
            val newest = repository.getAllSessions().first().maxByOrNull { it.startTime } ?: return@launch
            repository.activateSession(newest.id)
        }
    }

    fun forceRetry(imageId: Long) {
        viewModelScope.launch {
            val image = repository.getImageById(imageId) ?: return@launch
            repository.updateImageState(image.copy(uploadState = UploadState.PENDING, retryCount = 0, errorMessage = null))
            enqueueUpload(imageId, image.filename, ExistingWorkPolicy.REPLACE)
        }
    }

    fun onCaptureRequested() {
        val session = activeSession.value
        if (session == null) {
            _showNoSessionPrompt.value = true
        } else {
            capturePhoto(session.id)
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

            val outputDir = File(context.filesDir, "captures").also { it.mkdirs() }
            // Write to a temp file so CameraX's slow capture is outside the critical section.
            // Sequence number is assigned under captureMutex in the callback after the file exists.
            val tempFile = File(outputDir, "tmp_${System.currentTimeMillis()}.jpg")
            val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

            Log.d(PIPELINE_TAG, "capture: firing session=${session.sessionKey}")
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        viewModelScope.launch callback@{
                            val result = captureMutex.withLock {
                                val seq = repository.getImagesForSession(sessionId).first().size + 1
                                val filename = buildFilename(settings, session.barcode, seq)
                                val captureCode = buildCaptureCode(session, seq)
                                val finalFile = File(outputDir, filename)
                                // renameTo is atomic on the same filesystem; fallback to copy+delete.
                                // On IO because this callback runs on the main thread, and the
                                // copy fallback would otherwise block the UI for a full-size file.
                                val renamed = withContext(Dispatchers.IO) {
                                    if (tempFile.renameTo(finalFile)) true
                                    else runCatching {
                                        tempFile.copyTo(finalFile, overwrite = true); tempFile.delete()
                                    }.isSuccess
                                }
                                if (!renamed) {
                                    Log.e(PIPELINE_TAG, "capture: rename failed $filename")
                                    return@withLock null
                                }
                                val id = try {
                                    repository.saveImage(
                                        SessionImage(
                                            sessionId       = sessionId,
                                            filename        = filename,
                                            localPath       = finalFile.absolutePath,
                                            timestamp       = System.currentTimeMillis(),
                                            uploadState     = UploadState.PENDING,
                                            captureCode     = captureCode,
                                            captureSequence = seq,
                                            sortOrder       = seq
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.e(PIPELINE_TAG, "capture: db insert failed $filename", e)
                                    return@withLock null
                                }
                                Log.d(PIPELINE_TAG, "capture: saved imageId=$id filename=$filename seq=$seq captureCode=$captureCode")
                                Triple(id, filename, finalFile)
                            } ?: return@callback

                            val (imageId, renamedFilename, finalFile) = result
                            saveBackupIfEnabled(renamedFilename, finalFile)
                            enqueueUpload(imageId, renamedFilename)
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        tempFile.delete()
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
