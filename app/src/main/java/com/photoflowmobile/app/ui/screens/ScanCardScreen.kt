package com.photoflowmobile.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.photoflowmobile.app.ui.theme.*
import com.photoflowmobile.app.viewmodel.ScanCardViewModel
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScanCardScreen(
    onSessionStarted: () -> Unit = {},
    viewModel: ScanCardViewModel = viewModel()
) {
    var manualCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        ScanTopBar()
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            BarcodeScanPreview(
                modifier = Modifier.fillMaxSize(),
                onBarcodeDetected = { barcode ->
                    if (barcode.isNotBlank()) {
                        viewModel.startSession(barcode, onSessionStarted)
                    }
                }
            )
            Box(modifier = Modifier.fillMaxSize().border(1.dp, LocalAppColors.current.border))
            Text(
                "SCAN CARD",
                color = LocalAppColors.current.blue.copy(alpha = 0.5f),
                fontSize = 8.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
            ScanReticle(modifier = Modifier.align(Alignment.Center))
            Text(
                "AIM AT CARD BARCODE",
                color = LocalAppColors.current.textPrimary.copy(alpha = 0.5f),
                fontSize = 9.sp,
                letterSpacing = 1.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp)
            )
        }
        ManualEntryBar(
            value = manualCode,
            onValueChange = { manualCode = it },
            onSubmit = {
                if (manualCode.isNotBlank()) {
                    viewModel.startSession(manualCode, onSessionStarted)
                }
            }
        )
    }
}

@Composable
private fun BarcodeScanPreview(
    modifier: Modifier = Modifier,
    onBarcodeDetected: (String) -> Unit
) {
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
        val handled = remember { AtomicBoolean(false) }

        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }.also { previewView ->
                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val scanner = BarcodeScanning.getClient()
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(ContextCompat.getMainExecutor(ctx)) { proxy ->
                            val mediaImage = proxy.image
                            if (mediaImage != null && !handled.get()) {
                                val inputImage = InputImage.fromMediaImage(
                                    mediaImage, proxy.imageInfo.rotationDegrees
                                )
                                scanner.process(inputImage)
                                    .addOnSuccessListener { barcodes ->
                                        barcodes.firstOrNull()?.rawValue?.let { value ->
                                            if (handled.compareAndSet(false, true)) {
                                                onBarcodeDetected(value)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            } else {
                                proxy.close()
                            }
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
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

@Composable
private fun ScanTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⊞", color = LocalAppColors.current.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(5.dp))
        Text(
            "PHOTOFLOW — MOBILE",
            color = LocalAppColors.current.blue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.width(5.dp))
        Text(
            "· SCAN CARD",
            color = LocalAppColors.current.textPrimary.copy(alpha = 0.45f),
            fontSize = 10.sp
        )
        Spacer(Modifier.weight(1f))
        Icon(
            Icons.Default.Menu,
            contentDescription = null,
            tint = LocalAppColors.current.textPrimary.copy(alpha = 0.5f),
            modifier = Modifier.size(16.dp)
        )
    }
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
}

@Composable
private fun ScanReticle(modifier: Modifier = Modifier) {
    val reticleColor = LocalAppColors.current.blue
    Canvas(modifier = modifier.size(200.dp)) {
        val stroke = 2.dp.toPx()
        val corner = 28.dp.toPx()
        val w = size.width
        val h = size.height
        val segments = listOf(
            Offset(0f, corner) to Offset(0f, 0f),
            Offset(0f, 0f) to Offset(corner, 0f),
            Offset(w - corner, 0f) to Offset(w, 0f),
            Offset(w, 0f) to Offset(w, corner),
            Offset(0f, h - corner) to Offset(0f, h),
            Offset(0f, h) to Offset(corner, h),
            Offset(w, h - corner) to Offset(w, h),
            Offset(w, h) to Offset(w - corner, h),
        )
        segments.forEach { (start, end) ->
            drawLine(reticleColor, start, end, strokeWidth = stroke)
        }
    }
}

@Composable
private fun ManualEntryBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit
) {
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalAppColors.current.surface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "MANUAL ENTRY",
            color = LocalAppColors.current.textPrimary.copy(alpha = 0.45f),
            fontSize = 8.sp,
            letterSpacing = 0.5.sp
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF111111))
                .border(0.5.dp, LocalAppColors.current.border)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    "Enter session code...",
                    color = LocalAppColors.current.textPrimary.copy(alpha = 0.25f),
                    fontSize = 9.sp
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = LocalAppColors.current.textPrimary, fontSize = 9.sp),
                cursorBrush = SolidColor(LocalAppColors.current.blue),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Box(
            modifier = Modifier
                .background(if (value.isNotBlank()) LocalAppColors.current.blue else LocalAppColors.current.surfaceRaised)
                .border(1.dp, if (value.isNotBlank()) LocalAppColors.current.blue else LocalAppColors.current.border)
                .clickable(enabled = value.isNotBlank()) { onSubmit() }
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(
                "GO",
                color = if (value.isNotBlank()) LocalAppColors.current.background else LocalAppColors.current.textPrimary.copy(alpha = 0.3f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    }
}
