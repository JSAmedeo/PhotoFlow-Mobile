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
import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.platform.LocalConfiguration
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

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
    val orientationLockEnabled by viewModel.orientationLockEnabled.collectAsStateWithLifecycle()
    val orientationLock by viewModel.orientationLock.collectAsStateWithLifecycle()

    LaunchedEffect(orientationLockEnabled, orientationLock) {
        activity.requestedOrientation = if (!orientationLockEnabled) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else when (orientationLock) {
            com.photoflowmobile.app.data.model.OrientationLock.LANDSCAPE     -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            com.photoflowmobile.app.data.model.OrientationLock.LANDSCAPE_180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            com.photoflowmobile.app.data.model.OrientationLock.PORTRAIT      -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            com.photoflowmobile.app.data.model.OrientationLock.PORTRAIT_180  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }
    val showNoSessionPrompt by viewModel.showNoSessionPrompt.collectAsStateWithLifecycle()
    val noSessionsExist by viewModel.noSessionsExist.collectAsStateWithLifecycle()
    val pastSessionActive by viewModel.pastSessionActive.collectAsStateWithLifecycle()
    val missedWhileDisconnected by viewModel.missedWhileDisconnected.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        // Collect rather than sample .value — Room can take 2-3 s on first load.
        // filter { it } suspends harmlessly until noSessionsExist becomes true; if sessions
        // exist the emission is always false and this coroutine is cancelled when the screen leaves.
        viewModel.noSessionsExist
            .filter { it }
            .first()
        viewModel.showNoSessionPrompt()
    }

    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    var showSessionHistory by remember { mutableStateOf(false) }

    if (showNoSessionPrompt) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissNoSessionPrompt() },
            containerColor = LocalAppColors.current.surface,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("No Active Session", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Scan a card to start a new session before capturing.",
                    color = LocalAppColors.current.textSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissNoSessionPrompt()
                    onNewSession()
                }) {
                    Text("SCAN CARD", color = LocalAppColors.current.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissNoSessionPrompt() }) {
                    Text("DISMISS", color = LocalAppColors.current.textSecondary, fontSize = 11.sp)
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        TopBar(
            onMenuClick = onMenuClick,
            transferQueue = transferQueue,
            onRetry = { viewModel.forceRetry(it) }
        )
        // Directly under the top bar in both orientations: capture follows the active session,
        // so whenever that is not the newest session the operator must be able to see it.
        if (pastSessionActive) {
            PastSessionBanner(
                sessionLabel = activeSession?.barcode ?: "—",
                onGoToNewest = { viewModel.activateNewestSession() }
            )
        }
        // Data loss, so it sits at top level rather than inside TetheredPanel — the panel's
        // guidance overlay hides once images are showing, which is exactly when this matters.
        if (missedWhileDisconnected > 0) {
            MissedShotsBanner(
                count = missedWhileDisconnected,
                onDismiss = { viewModel.dismissMissedWarning() }
            )
        }
        if (isPortrait) {
            PortraitSessionButtons(
                onNewSession = onNewSession,
                onShowHistory = { showSessionHistory = true }
            )
            PortraitCenterArea(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                deviceMode = deviceMode,
                imageCapture = viewModel.imageCapture,
                latestImage = reviewImage,
                tetheredStatus = tetheredStatus,
                noSessionsExist = noSessionsExist,
                onCapture = { viewModel.onCaptureRequested() }
            )
            PortraitThumbnailRail(
                sessionImages = sessionImages,
                selectedImageId = reviewImage?.id,
                activeSession = activeSession,
                onImageSelected = { viewModel.selectReviewImage(it) }
            )
            PortraitBottomBar(
                deviceMode = deviceMode,
                connectionProfiles = connectionProfiles,
                activeConnection = activeConnection,
                onConnectionSelected = { viewModel.setActiveConnection(it) }
            )
        } else {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                LeftPanel(
                    modifier = Modifier.width(115.dp).fillMaxHeight(),
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
                    noSessionsExist = noSessionsExist,
                    onRemoteTrigger = { viewModel.triggerTetheredCapture() },
                    onCapture = { viewModel.onCaptureRequested() }
                )
                RightPanel(
                    modifier = Modifier.width(99.dp).fillMaxHeight(),
                    sessions = recentSessions,
                    selectedSessionId = selectedSessionId,
                    onSessionSelected = { viewModel.selectSession(it) }
                )
            }
            BottomBar(
                deviceMode = deviceMode,
                activeSession = activeSession,
                connectionProfiles = connectionProfiles,
                activeConnection = activeConnection,
                onConnectionSelected = { viewModel.setActiveConnection(it) }
            )
        }
    }

    if (isPortrait && showSessionHistory) {
        SessionHistorySheet(
            sessions = recentSessions,
            selectedSessionId = selectedSessionId,
            onSessionSelected = { viewModel.selectSession(it) },
            onDismiss = { showSessionHistory = false }
        )
    }
}

// ── Top Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    onMenuClick: () -> Unit = {},
    transferQueue: List<SessionImage> = emptyList(),
    onRetry: (Long) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = androidx.compose.ui.res.painterResource(id = com.photoflowmobile.app.R.drawable.ic_app_logo),
            contentDescription = "PhotoFlow",
            modifier = Modifier.height(24.dp).widthIn(max = 32.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(8.dp))
        Row {
            Text(
                "PHOTOFLOW",
                color = LocalAppColors.current.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                " MOBILE",
                color = LocalAppColors.current.blue,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.weight(1f))
        WifiSsidChip()
        Spacer(Modifier.width(6.dp))
        FileTransfersButton(transferQueue = transferQueue, onRetry = onRetry)
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = LocalAppColors.current.textSecondary,
            modifier = Modifier.size(18.dp).clickable { onMenuClick() }
        )
    }
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 0.5.dp)
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        StatusDot(color = if (isConnected) LocalAppColors.current.green else LocalAppColors.current.warning)
        Text(
            label,
            color = if (isConnected) LocalAppColors.current.green else LocalAppColors.current.warning,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
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
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LocalAppColors.current.green)
                .clickable { onNewSession() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "+ NEW SESSION",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                textAlign = TextAlign.Center
            )
        }
        Text(
            "SHOTS · ${sessionImages.size}",
            color = LocalAppColors.current.textDisabled,
            fontSize = 7.sp,
            letterSpacing = 0.5.sp
        )
        val columnState = rememberLazyListState()
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = columnState,
                modifier = Modifier.fillMaxSize(),
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
            VerticalScrollbarIndicator(
                listState = columnState,
                color = LocalAppColors.current.borderActive,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(3.dp)
            )
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
                .clip(RoundedCornerShape(5.dp))
                .background(LocalAppColors.current.surfaceRaised)
                .border(
                    width = if (active) 1.5.dp else 0.5.dp,
                    color = if (active) LocalAppColors.current.green else LocalAppColors.current.border,
                    shape = RoundedCornerShape(5.dp)
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
                        .padding(3.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(LocalAppColors.current.green.copy(alpha = 0.90f))
                        .size(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            label,
            color = if (active) LocalAppColors.current.textPrimary else LocalAppColors.current.textDisabled,
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
    noSessionsExist: Boolean = false,
    onRemoteTrigger: () -> Unit = {},
    onCapture: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LocalAppColors.current.surface)
            .border(0.5.dp, LocalAppColors.current.border, RoundedCornerShape(10.dp))
    ) {
        if (deviceMode == DeviceMode.TETHERED_DSLR) {
            TetheredPanel(
                modifier = Modifier.fillMaxSize(),
                status = tetheredStatus,
                latestImage = latestImage,
                noSessionsExist = noSessionsExist
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
                    Row(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusDot(color = LocalAppColors.current.green, size = 6.dp)
                        Text(
                            "LIVE",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 8.sp,
                            letterSpacing = 1.sp
                        )
                    }
                    val canvasBracket = LocalAppColors.current.green
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
                            drawLine(canvasBracket.copy(alpha = 0.55f), start, end, strokeWidth = stroke)
                        }
                        // centre focus point
                        val fp = 9.dp.toPx()
                        drawRect(
                            color = canvasBracket.copy(alpha = 0.45f),
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
                        .background(LocalAppColors.current.border)
                )

                // ── Review pane + capture button (right ~42%) ────────
                var showFullscreenLandscape by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("REVIEW", color = LocalAppColors.current.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            if (latestImage != null) {
                                Text("  ·  ", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                                Text(latestImage.filename, color = LocalAppColors.current.textPrimary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("  ${relativeTimeLabel(latestImage.timestamp)} AGO", color = LocalAppColors.current.textSecondary, fontSize = 8.sp)
                            } else {
                                Text("  ·  NO IMAGES", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(LocalAppColors.current.surfaceRaised)
                                .border(0.5.dp, LocalAppColors.current.border, RoundedCornerShape(6.dp))
                                .clickable(enabled = latestImage != null) { showFullscreenLandscape = true }
                        ) {
                            if (latestImage != null) {
                                AsyncImage(
                                    model = latestImage.localPath,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Text("⤢", color = LocalAppColors.current.textDisabled, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                            }
                        }
                    }
                    if (showFullscreenLandscape && latestImage != null) {
                        ReviewFullscreenDialog(image = latestImage, onDismiss = { showFullscreenLandscape = false })
                    }
                    HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(LocalAppColors.current.blue)
                                .clickable { onCapture() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "◉  CAPTURE",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        }
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
    latestImage: SessionImage? = null,
    noSessionsExist: Boolean = false
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
        val canvasBracket = LocalAppColors.current.green
        Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = 1.5.dp.toPx()
            val corner = 14.dp.toPx()
            val w = size.width; val h = size.height
            listOf(
                Offset(0f, corner) to Offset(0f, 0f), Offset(0f, 0f) to Offset(corner, 0f),
                Offset(w - corner, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, corner),
                Offset(0f, h - corner) to Offset(0f, h), Offset(0f, h) to Offset(corner, h),
                Offset(w, h - corner) to Offset(w, h), Offset(w, h) to Offset(w - corner, h),
            ).forEach { (s, e) -> drawLine(canvasBracket.copy(alpha = 0.5f), s, e, strokeWidth = stroke) }
        }

        // Status row — top-left
        Row(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (label, color) = when (status.state) {
                TetheredState.CONNECTED    -> "TETHERED"      to LocalAppColors.current.textPrimary
                TetheredState.CONNECTING   -> "CONNECTING…"   to LocalAppColors.current.warning
                TetheredState.ERROR        -> "ERROR"          to LocalAppColors.current.warning
                TetheredState.DISCONNECTED -> "NO CAMERA"      to LocalAppColors.current.textDisabled
            }
            Text(label, color = color, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
            if (!status.cameraModel.isNullOrBlank()) {
                Text("·  ${status.cameraModel}", color = LocalAppColors.current.textPrimary.copy(alpha = 0.5f), fontSize = 7.sp)
            }
        }

        // Latest image label — bottom-left when an image is shown
        if (status.state == TetheredState.CONNECTED && latestImage != null) {
            Text(
                latestImage.filename,
                color = LocalAppColors.current.textSecondary,
                fontSize = 7.sp,
                letterSpacing = 0.3.sp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(LocalAppColors.current.background.copy(alpha = 0.55f))
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
                    color = LocalAppColors.current.textPrimary.copy(alpha = 0.45f),
                    fontSize = 9.sp,
                    letterSpacing = 0.8.sp
                )
                if (status.state == TetheredState.CONNECTED && noSessionsExist) {
                    Text(
                        "START NEW SESSION\nBEFORE TAKING PHOTO",
                        color = LocalAppColors.current.warning,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 0.5.sp,
                        lineHeight = 22.sp
                    )
                }
                if (status.state == TetheredState.ERROR) {
                    Text(
                        "Check: USB Connection = PC Connection  ·  USB cable connected",
                        color = LocalAppColors.current.textPrimary.copy(alpha = 0.25f),
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
                color = LocalAppColors.current.textPrimary.copy(alpha = 0.4f),
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
            color = LocalAppColors.current.textDisabled,
            fontSize = 7.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(bottom = 3.dp)
        )
        if (sessions.isEmpty()) {
            Text(
                "— no sessions yet",
                color = LocalAppColors.current.textDisabled,
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
            .clip(RoundedCornerShape(7.dp))
            .background(if (isSelected) LocalAppColors.current.surfaceRaised else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.dp, LocalAppColors.current.green.copy(alpha = 0.40f), RoundedCornerShape(7.dp))
                else Modifier
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.barcode,
                color = LocalAppColors.current.textPrimary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                relativeTimeLabel(session.startTime),
                color = LocalAppColors.current.textDisabled,
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
                    Text("✓", color = LocalAppColors.current.green, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                } else if (anyFailed) {
                    Text("✗", color = LocalAppColors.current.warning, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    swc.imageCount.toString(),
                    color = LocalAppColors.current.textPrimary,
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
                    isLive     -> LocalAppColors.current.green
                    isComplete -> LocalAppColors.current.green
                    else       -> LocalAppColors.current.textSecondary
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
    activeSession: Session? = null,
    connectionProfiles: List<ConnectionProfile> = emptyList(),
    activeConnection: ConnectionProfile? = null,
    onConnectionSelected: (ConnectionProfile) -> Unit = {}
) {
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionSelector(
            profiles = connectionProfiles,
            activeProfile = activeConnection,
            onSelected = onConnectionSelected
        )
        Spacer(Modifier.weight(1f))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Active Session",
                color = LocalAppColors.current.textDisabled,
                fontSize = 10.sp,
                letterSpacing = 0.3.sp
            )
            Text(
                activeSession?.barcode ?: "—",
                color = LocalAppColors.current.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
        }
        Spacer(Modifier.weight(1f))
        Chip(
            label = if (deviceMode == DeviceMode.TETHERED_DSLR) "Tethered Mode" else "Native Mode",
            active = true,
            color = LocalAppColors.current.textSecondary
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("ACTIVE CONNECTION", color = LocalAppColors.current.textDisabled, fontSize = 8.sp, letterSpacing = 0.3.sp)
        StatusDot(color = LocalAppColors.current.green, size = 5.dp)
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, LocalAppColors.current.border, RoundedCornerShape(6.dp))
                    .background(LocalAppColors.current.surfaceRaised)
                    .clickable { expanded = true }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    activeProfile?.name ?: "None",
                    color = if (activeProfile != null) LocalAppColors.current.textPrimary else LocalAppColors.current.textDisabled,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
                Text("▾", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
            }
            DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(LocalAppColors.current.surface).border(0.5.dp, LocalAppColors.current.border)
        ) {
            if (profiles.isEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "No connections configured\nAdd one in Settings →",
                            color = LocalAppColors.current.textPrimary.copy(alpha = 0.45f),
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
                                    color = if (profile.isActive) LocalAppColors.current.green else LocalAppColors.current.textSecondary,
                                    fontSize = 8.sp
                                )
                                Spacer(Modifier.width(4.dp))
                                Column {
                                    Text(
                                        profile.name,
                                        color = LocalAppColors.current.textPrimary,
                                        fontSize = 9.sp,
                                        fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        "${profile.host}:${profile.port}",
                                        color = LocalAppColors.current.textDisabled,
                                        fontSize = 7.sp
                                    )
                                }
                            }
                        },
                        onClick = { onSelected(profile); expanded = false },
                        modifier = Modifier.background(
                            if (profile.isActive) LocalAppColors.current.surfaceRaised else Color.Transparent
                        )
                    )
                }
            }
        }
        } // end Box
    } // end outer Row
}

@Composable
private fun FileTransfersButton(
    transferQueue: List<SessionImage> = emptyList(),
    onRetry: (Long) -> Unit = {}
) {
    var showPanel by remember { mutableStateOf(false) }
    val hasErrors = transferQueue.any {
        it.uploadState == UploadState.FAILED
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (hasErrors) LocalAppColors.current.warning.copy(alpha = 0.12f)
                else LocalAppColors.current.surfaceRaised
            )
            .border(
                1.dp,
                if (hasErrors) LocalAppColors.current.warning.copy(alpha = 0.55f)
                else LocalAppColors.current.border,
                RoundedCornerShape(6.dp)
            )
            .clickable { showPanel = true }
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        StatusDot(
            color = if (hasErrors) LocalAppColors.current.warning else LocalAppColors.current.green,
            size = 5.dp
        )
        Text(
            "File Transfers",
            color = if (hasErrors) LocalAppColors.current.warning else LocalAppColors.current.textPrimary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .border(0.5.dp, LocalAppColors.current.border, RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "FILE TRANSFERS",
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        "✕",
                        color = LocalAppColors.current.textPrimary.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clickable { showPanel = false }
                            .padding(4.dp)
                    )
                }
                HorizontalDivider(
                    color = LocalAppColors.current.border,
                    thickness = 0.5.dp,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                if (transferQueue.isEmpty()) {
                    Text(
                        "— no pending transfers",
                        color = LocalAppColors.current.textPrimary.copy(alpha = 0.35f),
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
    val isFailed = image.uploadState == UploadState.FAILED

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, if (isFailed) LocalAppColors.current.warning.copy(alpha = 0.5f) else LocalAppColors.current.border)
            .background(if (isFailed) LocalAppColors.current.warning.copy(alpha = 0.05f) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                image.filename,
                color = LocalAppColors.current.textPrimary.copy(alpha = if (isFailed) 1f else 0.85f),
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
                }
            Text(
                description,
                color = when (image.uploadState) {
                    UploadState.UPLOADED                           -> LocalAppColors.current.green.copy(alpha = 0.85f)
                    UploadState.FAILED                             -> LocalAppColors.current.warning.copy(alpha = 0.85f)
                    else                                           -> LocalAppColors.current.textPrimary.copy(alpha = 0.5f)
                },
                fontSize = 8.sp,
                lineHeight = 11.sp,
                maxLines = 3
            )
        }
        val (badgeText, badgeColor) = when (image.uploadState) {
            UploadState.PENDING   -> "PENDING" to LocalAppColors.current.textSecondary
            UploadState.UPLOADING -> "↑ UP"    to LocalAppColors.current.blue
            UploadState.UPLOADED  -> "✓"       to LocalAppColors.current.green
            UploadState.FAILED    -> "FAILED"  to LocalAppColors.current.warning
        }
        Text(
            badgeText,
            color = badgeColor,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .border(0.5.dp, badgeColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.10f))
                .padding(horizontal = 5.dp, vertical = 2.dp)
        )
        if (isFailed) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, LocalAppColors.current.warning.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                    .background(LocalAppColors.current.warning.copy(alpha = 0.12f))
                    .clickable { onRetry() }
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text("RETRY", color = LocalAppColors.current.warning, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Portrait Layout ───────────────────────────────────────────────────────────

@Composable
private fun PortraitCenterArea(
    modifier: Modifier = Modifier,
    deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    imageCapture: ImageCapture? = null,
    latestImage: SessionImage? = null,
    tetheredStatus: TetheredStatus = TetheredStatus(),
    noSessionsExist: Boolean = false,
    onCapture: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(LocalAppColors.current.surface)
            .border(0.5.dp, LocalAppColors.current.border, RoundedCornerShape(10.dp))
    ) {
        if (deviceMode == DeviceMode.TETHERED_DSLR) {
            TetheredPanel(modifier = Modifier.fillMaxSize(), status = tetheredStatus, latestImage = latestImage, noSessionsExist = noSessionsExist)
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                // Camera preview (top ~58%)
                Box(modifier = Modifier.weight(0.58f).fillMaxWidth()) {
                    CameraPreview(modifier = Modifier.fillMaxSize(), imageCapture = imageCapture)
                    Row(
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusDot(color = LocalAppColors.current.green, size = 6.dp)
                        Text("LIVE", color = LocalAppColors.current.textSecondary, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                    val canvasBorder = LocalAppColors.current.border
                    Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                        val stroke = 1.5.dp.toPx()
                        val corner = 14.dp.toPx()
                        val w = size.width; val h = size.height
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
                            drawLine(canvasBorder.copy(alpha = 0.7f), start, end, strokeWidth = stroke)
                        }
                        val fp = 9.dp.toPx()
                        drawRect(
                            color = canvasBorder.copy(alpha = 0.6f),
                            topLeft = Offset(w / 2f - fp, h / 2f - fp),
                            size = Size(fp * 2, fp * 2),
                            style = Stroke(width = stroke)
                        )
                    }
                }

                HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)

                // Review pane (middle ~32%)
                var showFullscreen by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .weight(0.32f)
                        .fillMaxWidth()
                        .padding(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "REVIEW",
                            color = LocalAppColors.current.textSecondary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        if (latestImage != null) {
                            Text("  ·  ", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                            Text(
                                latestImage.filename,
                                color = LocalAppColors.current.textPrimary,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                "  ${relativeTimeLabel(latestImage.timestamp)} AGO",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 8.sp
                            )
                        } else {
                            Text("  ·  NO IMAGES", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(1.dp, LocalAppColors.current.border)
                            .background(LocalAppColors.current.surfaceRaised)
                            .clickable(enabled = latestImage != null) { showFullscreen = true }
                    ) {
                        if (latestImage != null) {
                            AsyncImage(
                                model = latestImage.localPath,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            Text(
                                "⤢",
                                color = LocalAppColors.current.textDisabled,
                                fontSize = 12.sp,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            )
                        }
                    }
                }
                if (showFullscreen && latestImage != null) {
                    ReviewFullscreenDialog(image = latestImage, onDismiss = { showFullscreen = false })
                }

                HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)

                // Capture button (bottom fixed)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalAppColors.current.blue)
                            .clickable { onCapture() }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "◉  CAPTURE",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitSessionButtons(
    onNewSession: () -> Unit = {},
    onShowHistory: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LocalAppColors.current.green)
                .clickable { onNewSession() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "NEW SESSION",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(LocalAppColors.current.surfaceRaised)
                .border(1.dp, LocalAppColors.current.border, RoundedCornerShape(8.dp))
                .clickable { onShowHistory() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "SESSION HISTORY",
                color = LocalAppColors.current.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
private fun PortraitThumbnailRail(
    sessionImages: List<SessionImage> = emptyList(),
    selectedImageId: Long? = null,
    activeSession: Session? = null,
    onImageSelected: (Long) -> Unit = {}
) {
    val colors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.borderActive)
            .background(colors.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceRaised)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text("Active Session", color = colors.textDisabled, fontSize = 9.sp)
            Spacer(Modifier.width(6.dp))
            Text(
                activeSession?.barcode ?: "—",
                color = colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 2.sp
            )
        }
        HorizontalDivider(color = colors.borderActive, thickness = 1.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Column(
                modifier = Modifier.width(26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("${sessionImages.size}", color = colors.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("SHOTS", color = colors.textDisabled, fontSize = 5.sp, letterSpacing = 0.3.sp)
            }
            val rowState = rememberLazyListState()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LazyRow(
                    state = rowState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(sessionImages) { index, image ->
                        PortraitThumbnail(
                            image = image,
                            active = image.id == selectedImageId || (selectedImageId == null && index == 0),
                            onClick = { onImageSelected(image.id) }
                        )
                    }
                }
                HorizontalScrollbarIndicator(rowState, colors.borderActive)
            }
        }
    }
}

@Composable
private fun PortraitThumbnail(
    image: SessionImage,
    active: Boolean,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(5.dp))
            .background(LocalAppColors.current.surfaceRaised)
            .border(
                width = if (active) 1.5.dp else 0.5.dp,
                color = if (active) LocalAppColors.current.green else LocalAppColors.current.border,
                shape = RoundedCornerShape(5.dp)
            )
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = image.localPath,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        if (image.uploadState == UploadState.UPLOADED) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(LocalAppColors.current.green.copy(alpha = 0.90f))
                    .size(14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("✓", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PortraitBottomBar(
    deviceMode: DeviceMode = DeviceMode.TETHERED_DSLR,
    connectionProfiles: List<ConnectionProfile> = emptyList(),
    activeConnection: ConnectionProfile? = null,
    onConnectionSelected: (ConnectionProfile) -> Unit = {}
) {
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionSelector(
            profiles = connectionProfiles,
            activeProfile = activeConnection,
            onSelected = onConnectionSelected
        )
        Spacer(Modifier.weight(1f))
        Chip(
            label = if (deviceMode == DeviceMode.TETHERED_DSLR) "Tethered Mode" else "Native Mode",
            active = true,
            color = LocalAppColors.current.textSecondary
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionHistorySheet(
    sessions: List<SessionWithCount>,
    selectedSessionId: Long? = null,
    onSessionSelected: (Long) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocalAppColors.current.surface,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(LocalAppColors.current.border)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
        ) {
            Text(
                "SESSION HISTORY",
                color = LocalAppColors.current.textDisabled,
                fontSize = 8.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (sessions.isEmpty()) {
                Text(
                    "— no sessions yet",
                    color = LocalAppColors.current.textDisabled,
                    fontSize = 8.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(sessions) { swc ->
                        SessionRow(
                            swc = swc,
                            isSelected = swc.session.id == selectedSessionId,
                            onClick = { onSessionSelected(swc.session.id); onDismiss() }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// ── Shared ────────────────────────────────────────────────────────────────────

@Composable
private fun Chip(label: String, active: Boolean, color: Color = LocalAppColors.current.blue) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (active) color.copy(alpha = 0.65f) else LocalAppColors.current.border, RoundedCornerShape(50))
            .background(if (active) color.copy(alpha = 0.12f) else Color.Transparent)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(
            label,
            color = if (active) color else LocalAppColors.current.textPrimary.copy(alpha = 0.55f),
            fontSize = 8.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun ReviewFullscreenDialog(image: SessionImage, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.72f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(LocalAppColors.current.surface)
                    .border(0.5.dp, LocalAppColors.current.borderActive, RoundedCornerShape(12.dp))
                    .clickable { onDismiss() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(LocalAppColors.current.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        image.filename,
                        color = LocalAppColors.current.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${relativeTimeLabel(image.timestamp)} AGO",
                        color = LocalAppColors.current.textSecondary,
                        fontSize = 9.sp
                    )
                }
                HorizontalDivider(color = LocalAppColors.current.borderActive, thickness = 1.dp)
                AsyncImage(
                    model = image.localPath,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun HorizontalScrollbarIndicator(listState: LazyListState, color: Color) {
    // Always occupies 3dp — no early return — so LazyRow height never fluctuates.
    // State is read in drawBehind (draw phase only), avoiding recomposition loops.
    val trackColor = color.copy(alpha = 0.2f)
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .drawBehind {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val visible = info.visibleItemsInfo.size
                if (total <= visible) return@drawBehind
                val thumbFraction = (visible.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / (total - visible).toFloat()).coerceIn(0f, 1f)
                drawRect(trackColor)
                val thumbWidth = size.width * thumbFraction
                drawRect(
                    color = color,
                    topLeft = Offset((size.width - thumbWidth) * scrollFraction, 0f),
                    size = Size(thumbWidth, size.height)
                )
            }
    )
}

@Composable
private fun VerticalScrollbarIndicator(listState: LazyListState, color: Color, modifier: Modifier = Modifier) {
    val trackColor = color.copy(alpha = 0.2f)
    Spacer(
        modifier = modifier.drawBehind {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val visible = info.visibleItemsInfo.size
            if (total <= visible) return@drawBehind
            val thumbFraction = (visible.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            val scrollFraction = (listState.firstVisibleItemIndex.toFloat() / (total - visible).toFloat()).coerceIn(0f, 1f)
            drawRect(trackColor)
            val thumbHeight = size.height * thumbFraction
            drawRect(
                color = color,
                topLeft = Offset(0f, (size.height - thumbHeight) * scrollFraction),
                size = Size(size.width, thumbHeight)
            )
        }
    )
}

/**
 * Shown when shots appeared on the camera card while the cable was disconnected.
 *
 * Those frames are already on the card at reconnect, so the poll loop seeds them as pre-existing
 * and never imports them. This banner does not recover them — it exists so the loss is visible
 * rather than silent, since an operator would otherwise finish a session short without knowing.
 * The recovery-import feature is tracked in .claude/prompts/field-readiness-pass.md.
 *
 * Uses `error` rather than `warning` so it stays distinguishable from [PastSessionBanner] when
 * both are on screen at once.
 */
@Composable
private fun MissedShotsBanner(
    count: Int,
    onDismiss: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.error.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatusDot(color = colors.error, size = 6.dp)
        Text(
            if (count == 1) "1 PHOTO NOT IMPORTED" else "$count PHOTOS NOT IMPORTED",
            color = colors.error,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        // Wraps rather than truncating: "still on the camera card" is the part that tells the
        // operator the shots are recoverable by hand, so it must never be the half that is cut.
        Text(
            "Shot while disconnected — still on the camera card",
            color = colors.textPrimary,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "DISMISS",
            color = colors.error,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.error.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * Shown whenever the active session is not the most recently started one.
 *
 * Selecting a session in Session History activates it, so new photos — native and tethered —
 * are filed there. This banner is the standing signal that capture has moved, and its action
 * is the one-tap way back to the newest session.
 */
@Composable
private fun PastSessionBanner(
    sessionLabel: String,
    onGoToNewest: () -> Unit = {}
) {
    val colors = LocalAppColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.warning.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        StatusDot(color = colors.warning, size = 6.dp)
        Text(
            "VIEWING PAST SESSION",
            color = colors.warning,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
        // Wraps rather than truncating — the session code is user data of unbounded length, and
        // it is the half that actually identifies where photos are going.
        Text(
            "NEW PHOTOS GO TO $sessionLabel",
            color = colors.textPrimary,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "GO TO NEWEST",
            color = colors.warning,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, colors.warning.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .clickable { onGoToNewest() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatusDot(color: Color = LocalAppColors.current.green, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
