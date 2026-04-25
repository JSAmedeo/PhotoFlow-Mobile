package com.photoflowmobile.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.photoflowmobile.app.data.db.SessionWithCount
import com.photoflowmobile.app.data.model.ConnectionProfile
import com.photoflowmobile.app.data.model.DeviceMode
import com.photoflowmobile.app.data.model.Session
import com.photoflowmobile.app.data.model.SessionImage
import com.photoflowmobile.app.data.model.UploadState
import com.photoflowmobile.app.data.usb.TetheredState
import com.photoflowmobile.app.data.usb.TetheredStatus
import com.photoflowmobile.app.ui.theme.*
import com.photoflowmobile.app.viewmodel.MainViewModel

@Composable
fun MainScreen(
    onMenuClick: () -> Unit = {},
    onNewSession: () -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val activity = LocalContext.current as android.app.Activity
    BackHandler { activity.moveTaskToBack(true) }

    val deviceMode by viewModel.deviceMode.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val selectedSessionId by viewModel.selectedSessionId.collectAsStateWithLifecycle()
    val transferQueue by viewModel.transferQueue.collectAsStateWithLifecycle()
    val recentSessions by viewModel.recentSessions.collectAsStateWithLifecycle()
    val sessionImages by viewModel.sessionImages.collectAsStateWithLifecycle()
    val reviewImage by viewModel.reviewImage.collectAsStateWithLifecycle()
    val connectionProfiles by viewModel.connectionProfiles.collectAsStateWithLifecycle()
    val activeConnection by viewModel.activeConnection.collectAsStateWithLifecycle()
    val tetheredStatus by viewModel.tetheredStatus.collectAsStateWithLifecycle()
    val selectedSession by viewModel.selectedSession.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TopBar(
            onMenuClick = onMenuClick,
            activeSession = selectedSession ?: activeSession,
            transferQueue = transferQueue,
            onRetry = { viewModel.forceRetry(it) }
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            LeftPanel(
                modifier = Modifier.width(72.dp).fillMaxHeight(),
                onNewSession = onNewSession,
                sessionImages = sessionImages,
                selectedImageId = reviewImage?.id,
                onImageSelected = { viewModel.selectReviewImage(it) }
            )
            CenterPanel(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                deviceMode = deviceMode,
                imageCapture = viewModel.imageCapture,
                latestImage = reviewImage,
                tetheredStatus = tetheredStatus,
                onRemoteTrigger = { viewModel.triggerTetheredCapture() }
            )
            RightPanel(
                modifier = Modifier.width(132.dp).fillMaxHeight(),
                sessions = recentSessions,
                selectedSessionId = selectedSessionId,
                onSessionSelected = { viewModel.selectSession(it) }
            )
        }
        BottomBar(
            deviceMode = deviceMode,
            onCapture = { selectedSessionId?.let { viewModel.capturePhoto(it) } },
            connectionProfiles = connectionProfiles,
            activeConnection = activeConnection,
            onConnectionSelected = { viewModel.setActiveConnection(it) }
        )
    }
}

// ── Top Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    onMenuClick: () -> Unit = {},
    activeSession: Session? = null,
    transferQueue: List<SessionImage> = emptyList(),
    onRetry: (Long) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⊞", color = DarkPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(
            "PHOTOFLOW — MOBILE",
            color = DarkPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                activeSession?.barcode ?: "—",
                color = DarkPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
        }
        WifiSsidChip()
        Spacer(Modifier.width(5.dp))
        FileTransfersButton(transferQueue = transferQueue, onRetry = onRetry)
        Spacer(Modifier.width(8.dp))
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = DarkOnBackground.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp).clickable { onMenuClick() }
        )
    }
    HorizontalDivider(color = DarkBorder, thickness = 1.dp)
}

@Composable
private fun WifiSsidChip() {
    val context = LocalContext.current

    val hasLocationPerm = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    val label by produceState("", context, hasLocationPerm) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        @Suppress("DEPRECATION")
        fun readSsid(): String? {
            if (!hasLocationPerm) return null
            return try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val raw = wm.connectionInfo?.ssid
                if (raw == null || raw == "<unknown ssid>" || raw.isBlank()) null
                else raw.removePrefix("\"").removeSuffix("\"")
            } catch (_: Exception) { null }
        }

        fun computeLabel(): String {
            val hasWifi = cm.allNetworks.any { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (!hasWifi) return "WiFi not connected"
            val ssid = readSsid()
            return if (ssid != null) "Connected - $ssid" else "WiFi Connected"
        }

        value = computeLabel()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = computeLabel() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                value = computeLabel()
            }
            override fun onLost(network: Network) { value = computeLabel() }
        }
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }

    val isConnected = !label.startsWith("WiFi not") && label.isNotEmpty()
    Chip(label, active = true, color = if (isConnected) DarkSuccess else DarkWarning)
}

// ── Left Panel ────────────────────────────────────────────────────────────────

@Composable
private fun LeftPanel(
    modifier: Modifier = Modifier,
    onNewSession: () -> Unit = {},
    sessionImages: List<SessionImage> = emptyList(),
    selectedImageId: Long? = null,
    onImageSelected: (Long) -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .border(1.dp, DarkPrimary)
                .clickable { onNewSession() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "+ NEW\nSESSION",
                color = DarkPrimary,
                fontSize = 8.sp,
                lineHeight = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.3.sp,
                textAlign = TextAlign.Center
            )
        }
        Text(
            "SHOTS · ${sessionImages.size}",
            color = DarkOnBackground.copy(alpha = 0.45f),
            fontSize = 7.sp,
            letterSpacing = 0.5.sp
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            itemsIndexed(sessionImages) { index, image ->
                ThumbnailCell(
                    localPath = image.localPath,
                    label = image.filename,
                    uploadState = image.uploadState,
                    active = image.id == selectedImageId || (selectedImageId == null && index == 0),
                    onClick = { onImageSelected(image.id) }
                )
            }
        }
    }
}

@Composable
private fun ThumbnailCell(
    localPath: String,
    label: String,
    uploadState: UploadState,
    active: Boolean,
    onClick: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(DarkSurfaceVariant)
                .border(
                    width = if (active) 1.dp else 0.5.dp,
                    color = if (active) DarkPrimary else DarkBorder
                )
        ) {
            AsyncImage(
                model = localPath,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            if (uploadState == UploadState.UPLOADED) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .background(DarkSuccess)
                        .padding(horizontal = 2.dp, vertical = 1.dp)
                ) {
                    Text("✓", color = Color.Black, fontSize = 6.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            label,
            color = if (active) DarkPrimary else DarkOnBackground.copy(alpha = 0.5f),
            fontSize = 6.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 8.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 1.dp)
        )
    }
}

// ── Center Panel ──────────────────────────────────────────────────────────────

@Composable
private fun CenterPanel(
    modifier: Modifier = Modifier,
    deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    imageCapture: ImageCapture? = null,
    latestImage: SessionImage? = null,
    tetheredStatus: TetheredStatus = TetheredStatus(),
    onRemoteTrigger: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .background(Color(0xFF050F0F))
            .border(1.dp, DarkPrimary)
            .clip(RectangleShape)
    ) {
        if (deviceMode == DeviceMode.TETHERED_DSLR) {
            TetheredPanel(
                modifier = Modifier.fillMaxSize(),
                status = tetheredStatus,
                latestImage = latestImage
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Live feed (left ~58%) ─────────────────────────
                Box(
                    modifier = Modifier
                        .weight(0.58f)
                        .fillMaxHeight()
                        .clip(RectangleShape)
                ) {
                    CameraPreview(modifier = Modifier.fillMaxSize(), imageCapture = imageCapture)
                    Text(
                        "LIVE",
                        color = DarkPrimary.copy(alpha = 0.85f),
                        fontSize = 8.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    )
                    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        val stroke = 1.5.dp.toPx()
                        val corner = 14.dp.toPx()
                        val w = size.width
                        val h = size.height
                        listOf(
                            Offset(0f, corner) to Offset(0f, 0f),
                            Offset(0f, 0f) to Offset(corner, 0f),
                            Offset(w - corner, 0f) to Offset(w, 0f),
                            Offset(w, 0f) to Offset(w, corner),
                            Offset(0f, h - corner) to Offset(0f, h),
                            Offset(0f, h) to Offset(corner, h),
                            Offset(w, h - corner) to Offset(w, h),
                            Offset(w, h) to Offset(w - corner, h),
                        ).forEach { (start, end) ->
                            drawLine(DarkPrimary.copy(alpha = 0.55f), start, end, strokeWidth = stroke)
                        }
                        // centre focus point
                        val fp = 9.dp.toPx()
                        drawRect(
                            color = DarkPrimary.copy(alpha = 0.45f),
                            topLeft = Offset(w / 2f - fp, h / 2f - fp),
                            size = Size(fp * 2, fp * 2),
                            style = Stroke(width = stroke)
                        )
                    }
                }

                // Divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(DarkBorder)
                )

                // ── Last shot + metadata (right ~42%) ────────────
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .padding(6.dp)
                ) {
                    Text(
                        if (latestImage != null)
                            "REVIEW  ·  ${latestImage.filename}  ·  ${relativeTimeLabel(latestImage.timestamp)} AGO"
                        else
                            "REVIEW  ·  NO IMAGES",
                        color = DarkPrimary.copy(alpha = 0.6f),
                        fontSize = 7.sp,
                        letterSpacing = 0.4.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(1.dp, DarkPrimary.copy(alpha = 0.35f))
                            .background(Color(0xFF060E0E))
                    ) {
                        if (latestImage != null) {
                            AsyncImage(
                                model = latestImage.localPath,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    // EXIF strip
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("ISO 100", "1/250", "F/2.8", "5200K", "85MM", "RAW").forEach { label ->
                            Text(
                                label,
                                color = DarkOnBackground.copy(alpha = 0.5f),
                                fontSize = 6.sp,
                                letterSpacing = 0.2.sp,
                                modifier = Modifier
                                    .border(0.5.dp, DarkBorder)
                                    .padding(horizontal = 3.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "≒ TRANSFER OK",
                            color = Color(0xFF4CAF50).copy(alpha = 0.85f),
                            fontSize = 7.sp,
                            letterSpacing = 0.3.sp,
                            modifier = Modifier
                                .border(0.5.dp, Color(0xFF4CAF50).copy(alpha = 0.35f))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        Text(
                            "↓ 42.4MB",
                            color = DarkOnBackground.copy(alpha = 0.4f),
                            fontSize = 7.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TetheredPanel(
    modifier: Modifier = Modifier,
    status: TetheredStatus = TetheredStatus(),
    latestImage: SessionImage? = null
) {
    Box(modifier = modifier) {

        // Last captured image — full panel background when connected
        if (status.state == TetheredState.CONNECTED && latestImage != null) {
            AsyncImage(
                model = latestImage.localPath,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Corner bracket overlay
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 1.5.dp.toPx()
            val corner = 14.dp.toPx()
            val w = size.width; val h = size.height
            listOf(
                Offset(0f, corner) to Offset(0f, 0f), Offset(0f, 0f) to Offset(corner, 0f),
                Offset(w - corner, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, corner),
                Offset(0f, h - corner) to Offset(0f, h), Offset(0f, h) to Offset(corner, h),
                Offset(w, h - corner) to Offset(w, h), Offset(w, h) to Offset(w - corner, h),
            ).forEach { (s, e) -> drawLine(DarkPrimary.copy(alpha = 0.55f), s, e, strokeWidth = stroke) }
        }

        // Status row — top-left
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (label, color) = when (status.state) {
                TetheredState.CONNECTED    -> "TETHERED"      to DarkPrimary
                TetheredState.CONNECTING   -> "CONNECTING…"   to DarkWarning
                TetheredState.ERROR        -> "ERROR"          to DarkWarning
                TetheredState.DISCONNECTED -> "NO CAMERA"      to DarkOnBackground.copy(alpha = 0.4f)
            }
            Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            if (!status.cameraModel.isNullOrBlank()) {
                Text("·  ${status.cameraModel}", color = DarkOnBackground.copy(alpha = 0.5f), fontSize = 7.sp)
            }
        }

        // Latest image label — bottom-left when an image is shown
        if (status.state == TetheredState.CONNECTED && latestImage != null) {
            Text(
                latestImage.filename,
                color = DarkPrimary.copy(alpha = 0.7f),
                fontSize = 7.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(DarkBackground.copy(alpha = 0.55f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }

        // Guidance overlay when not yet shooting
        if (status.state != TetheredState.CONNECTED || latestImage == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    when (status.state) {
                        TetheredState.DISCONNECTED -> "CONNECT CAMERA VIA USB"
                        TetheredState.CONNECTING   -> "OPENING MTP SESSION…"
                        TetheredState.ERROR        -> status.message?.ifBlank { "CONNECTION ERROR" } ?: "CONNECTION ERROR"
                        TetheredState.CONNECTED    -> "READY — WAITING FOR FIRST SHOT"
                    },
                    color = DarkOnBackground.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp
                )
                if (status.state == TetheredState.DISCONNECTED) {
                    Text(
                        "Camera: Menu → Communication Settings → USB Connection → PC Connection",
                        color = DarkOnBackground.copy(alpha = 0.25f),
                        fontSize = 7.sp,
                        letterSpacing = 0.3.sp
                    )
                }
                if (status.state == TetheredState.ERROR) {
                    Text(
                        "Check: USB Connection = PC Connection  ·  USB cable connected",
                        color = DarkOnBackground.copy(alpha = 0.25f),
                        fontSize = 7.sp,
                        letterSpacing = 0.3.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier = Modifier, imageCapture: ImageCapture? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val hasCameraPermission = remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission.value = granted }
    )
    LaunchedEffect(Unit) {
        if (!hasCameraPermission.value) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission.value) {
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    clipToOutline = true
                }.also { previewView ->
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }
                        val useCases = listOfNotNull(preview, imageCapture).toTypedArray()
                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                *useCases
                            )
                        } catch (_: Exception) {}
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                "CAMERA PERMISSION REQUIRED",
                color = DarkOnBackground.copy(alpha = 0.4f),
                fontSize = 8.sp,
                letterSpacing = 0.5.sp
            )
        }
    }
}

// ── Right Panel ───────────────────────────────────────────────────────────────

@Composable
private fun RightPanel(
    modifier: Modifier = Modifier,
    sessions: List<SessionWithCount>,
    selectedSessionId: Long? = null,
    onSessionSelected: (Long) -> Unit = {}
) {
    Column(modifier = modifier) {
        SessionHistoryBlock(
            sessions = sessions,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            selectedSessionId = selectedSessionId,
            onSessionSelected = onSessionSelected
        )
    }
}

@Composable
private fun SessionHistoryBlock(
    sessions: List<SessionWithCount>,
    modifier: Modifier = Modifier,
    selectedSessionId: Long? = null,
    onSessionSelected: (Long) -> Unit = {}
) {
    Column(modifier = modifier) {
        Text(
            "SESSION HISTORY",
            color = DarkOnBackground.copy(alpha = 0.45f),
            fontSize = 7.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        if (sessions.isEmpty()) {
            Text(
                "— no sessions yet",
                color = DarkOnBackground.copy(alpha = 0.25f),
                fontSize = 7.sp
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(sessions) { swc ->
                    SessionRow(
                        swc = swc,
                        isSelected = swc.session.id == selectedSessionId,
                        onClick = { onSessionSelected(swc.session.id) }
                    )
                }
            }
        }
    }
}

private fun relativeTimeLabel(epochMillis: Long): String {
    val elapsed = System.currentTimeMillis() - epochMillis
    return when {
        elapsed < 2 * 60_000L       -> "NOW"
        elapsed < 60 * 60_000L      -> "${elapsed / 60_000}M"
        elapsed < 24 * 3_600_000L   -> "${elapsed / 3_600_000}H"
        else                         -> "${elapsed / 86_400_000}D"
    }
}

@Composable
private fun SessionRow(
    swc: SessionWithCount,
    isSelected: Boolean = false,
    onClick: () -> Unit = {}
) {
    val session = swc.session
    val isLive = session.status == "active" && session.endTime == null
    val isComplete = session.endTime != null || session.status == "complete"

    val allTransferred = swc.imageCount > 0 && swc.uploadedCount == swc.imageCount
    val anyFailed = swc.failedCount > 0

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, if (isSelected) DarkPrimary else DarkBorder)
            .background(if (isSelected) DarkPrimary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.barcode,
                color = if (isSelected) DarkPrimary else DarkOnBackground,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                relativeTimeLabel(session.startTime),
                color = DarkOnBackground.copy(alpha = 0.35f),
                fontSize = 7.sp
            )
        }
        Column(
            modifier = Modifier.widthIn(min = 32.dp),
            horizontalAlignment = Alignment.End
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (allTransferred) {
                    Text("✓", color = DarkSuccess, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                } else if (anyFailed) {
                    Text("✗", color = DarkWarning, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    swc.imageCount.toString(),
                    color = DarkOnBackground.copy(alpha = 0.8f),
                    fontSize = 9.sp
                )
            }
            Text(
                when {
                    isLive     -> "LIVE"
                    isComplete -> "DONE"
                    else       -> "OPEN"
                },
                color = when {
                    isLive     -> DarkPrimary
                    isComplete -> DarkSuccess
                    else       -> DarkOnBackground.copy(alpha = 0.6f)
                },
                fontSize = 7.sp
            )
        }
    }
}

// ── Bottom Bar ────────────────────────────────────────────────────────────────

@Composable
private fun BottomBar(
    deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    onCapture: () -> Unit = {},
    connectionProfiles: List<ConnectionProfile> = emptyList(),
    activeConnection: ConnectionProfile? = null,
    onConnectionSelected: (ConnectionProfile) -> Unit = {}
) {
    HorizontalDivider(color = DarkBorder, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkSurface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionSelector(
            profiles = connectionProfiles,
            activeProfile = activeConnection,
            onSelected = onConnectionSelected
        )
        Spacer(Modifier.weight(1f))
        if (deviceMode == DeviceMode.NATIVE_CAMERA) {
            CaptureButton(onClick = onCapture)
            Spacer(Modifier.weight(1f))
        }
        Chip(
            label = if (deviceMode == DeviceMode.TETHERED_DSLR) "Tethered Mode" else "Native Mode",
            active = true
        )
    }
}

@Composable
private fun CaptureButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .widthIn(min = 220.dp)
            .border(1.5.dp, DarkPrimary)
            .background(DarkPrimary.copy(alpha = 0.12f))
            .clickable { onClick() }
            .padding(horizontal = 40.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "◉  CAPTURE",
            color = DarkPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun ConnectionSelector(
    profiles: List<ConnectionProfile>,
    activeProfile: ConnectionProfile?,
    onSelected: (ConnectionProfile) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .border(1.dp, DarkPrimary)
                .background(DarkPrimary.copy(alpha = 0.08f))
                .clickable { expanded = true }
                .padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ACTIVE CONNECTION", color = DarkOnBackground.copy(alpha = 0.45f), fontSize = 8.sp)
            Spacer(Modifier.width(5.dp))
            Text(
                activeProfile?.name ?: "None",
                color = if (activeProfile != null) DarkPrimary else DarkOnBackground.copy(alpha = 0.35f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(4.dp))
            Text("▾", color = DarkPrimary, fontSize = 9.sp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(DarkSurface).border(0.5.dp, DarkBorder)
        ) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "No connections configured\nAdd one in Settings →",
                            color = DarkOnBackground.copy(alpha = 0.45f),
                            fontSize = 8.sp,
                            lineHeight = 12.sp
                        )
                    },
                    onClick = { expanded = false }
                )
            } else {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (profile.isActive) "● " else "  ",
                                    color = DarkPrimary,
                                    fontSize = 8.sp
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(
                                        profile.name,
                                        color = if (profile.isActive) DarkPrimary else DarkOnBackground,
                                        fontSize = 9.sp,
                                        fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        "${profile.host}:${profile.port}",
                                        color = DarkOnBackground.copy(alpha = 0.45f),
                                        fontSize = 7.sp
                                    )
                                }
                            }
                        },
                        onClick = { onSelected(profile); expanded = false },
                        modifier = Modifier.background(
                            if (profile.isActive) DarkPrimary.copy(alpha = 0.08f) else Color.Transparent
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FileTransfersButton(
    transferQueue: List<SessionImage> = emptyList(),
    onRetry: (Long) -> Unit = {}
) {
    var showPanel by remember { mutableStateOf(false) }
    val hasErrors = transferQueue.any {
        it.uploadState == UploadState.FAILED || it.uploadState == UploadState.RETRY_REQUIRED
    }
    val color = if (hasErrors) DarkWarning else DarkSuccess

    Box(
        modifier = Modifier
            .border(1.dp, color)
            .background(color.copy(alpha = 0.1f))
            .clickable { showPanel = true }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text("File Transfers", color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }

    if (showPanel) {
        Dialog(
            onDismissRequest = { showPanel = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .fillMaxHeight(0.68f)
                    .background(DarkSurface)
                    .border(1.dp, DarkBorder)
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FILE TRANSFERS",
                        color = DarkPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        "✕",
                        color = DarkOnBackground.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { showPanel = false }
                            .padding(4.dp)
                    )
                }
                HorizontalDivider(
                    color = DarkBorder,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                if (transferQueue.isEmpty()) {
                    Text(
                        "— no pending transfers",
                        color = DarkOnBackground.copy(alpha = 0.35f),
                        fontSize = 9.sp
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(transferQueue) { image ->
                            TransferPanelRow(image = image, onRetry = { onRetry(image.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferPanelRow(
    image: SessionImage,
    onRetry: () -> Unit = {}
) {
    val isFailed = image.uploadState == UploadState.FAILED ||
            image.uploadState == UploadState.RETRY_REQUIRED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, if (isFailed) DarkWarning.copy(alpha = 0.5f) else DarkBorder)
            .background(if (isFailed) DarkWarning.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                image.filename,
                color = DarkOnBackground.copy(alpha = if (isFailed) 1f else 0.85f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            val description = image.errorMessage?.takeIf { it.isNotBlank() }
                ?: when (image.uploadState) {
                    UploadState.PENDING        -> "Queued — waiting to upload"
                    UploadState.UPLOADING      -> "Uploading…"
                    UploadState.UPLOADED       -> "Uploaded successfully"
                    UploadState.FAILED         -> "Upload failed"
                    UploadState.RETRY_REQUIRED -> "Retrying — last attempt failed"
                }
            Text(
                description,
                color = when (image.uploadState) {
                    UploadState.UPLOADED                           -> DarkSuccess.copy(alpha = 0.85f)
                    UploadState.FAILED, UploadState.RETRY_REQUIRED -> DarkWarning.copy(alpha = 0.85f)
                    else                                           -> DarkOnBackground.copy(alpha = 0.5f)
                },
                fontSize = 8.sp,
                lineHeight = 11.sp,
                maxLines = 3
            )
        }
        val (badgeText, badgeColor) = when (image.uploadState) {
            UploadState.PENDING        -> "PENDING"    to DarkOnBackground.copy(alpha = 0.4f)
            UploadState.UPLOADING      -> "↑ UP"       to DarkPrimary
            UploadState.UPLOADED       -> "✓"          to DarkSuccess
            UploadState.FAILED         -> "FAILED"     to DarkWarning
            UploadState.RETRY_REQUIRED -> "AUTO-RETRY" to DarkWarning
        }
        Text(
            badgeText,
            color = badgeColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .border(0.5.dp, badgeColor.copy(alpha = 0.5f))
                .padding(horizontal = 4.dp, vertical = 2.dp)
        )
        if (isFailed) {
            Box(
                modifier = Modifier
                    .border(1.dp, DarkWarning)
                    .background(DarkWarning.copy(alpha = 0.1f))
                    .clickable { onRetry() }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("RETRY", color = DarkWarning, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Shared ────────────────────────────────────────────────────────────────────

@Composable
private fun Chip(label: String, active: Boolean, color: Color = DarkPrimary) {
    Box(
        modifier = Modifier
            .border(1.dp, if (active) color else DarkBorder)
            .background(if (active) color.copy(alpha = 0.1f) else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = if (active) color else DarkOnBackground.copy(alpha = 0.55f),
            fontSize = 8.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}
