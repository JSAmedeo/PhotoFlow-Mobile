package com.photoflowmobile.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.res.Configuration
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.photoflowmobile.app.BuildConfig
import com.photoflowmobile.app.data.model.*
import com.photoflowmobile.app.ui.theme.*
import com.photoflowmobile.app.viewmodel.ConfigViewModel
import com.photoflowmobile.app.viewmodel.SettingsTransferResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ConfigSection(val label: String, val shortLabel: String = label) {
    DEVICE_MODE("Device Mode", "Device"),
    GENERAL("General"),
    CONNECTIONS("Connections", "Connect"),
    FILE_NAMING("File Naming", "Naming"),
    ABOUT("About")
}

private enum class ConnTestState { IDLE, TESTING, SUCCESS, FAILED }

// ── Screen ────────────────────────────────────────────────────────────────────

@Composable
fun ConfigScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: ConfigViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selectedSection by remember { mutableStateOf(ConfigSection.DEVICE_MODE) }
    val connectionProfiles by viewModel.connectionProfiles.collectAsStateWithLifecycle()
    val configuration = LocalConfiguration.current
    val isPortrait = configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    val context = LocalContext.current
    val activity = context as? Activity
    val transferResult by viewModel.transferResult.collectAsStateWithLifecycle()
    val cloudRegistrationState by viewModel.cloudRegistrationState.collectAsStateWithLifecycle()
    val manifestResult by viewModel.manifestResult.collectAsStateWithLifecycle()
    val activeSessionKey by viewModel.activeSessionKey.collectAsStateWithLifecycle()
    val debugRetryState by viewModel.debugRetryState.collectAsStateWithLifecycle()
    val cloudApiKey by viewModel.cloudApiKey.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importSettings(context.applicationContext, it) }
    }

    transferResult?.let { result ->
        AlertDialog(
            onDismissRequest = { viewModel.clearTransferResult() },
            containerColor = LocalAppColors.current.surface,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = {
                Text(
                    when (result) {
                        is SettingsTransferResult.ExportSuccess -> "Settings Exported"
                        is SettingsTransferResult.ImportSuccess -> "Settings Imported"
                        is SettingsTransferResult.Error -> "Transfer Failed"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (result) {
                        is SettingsTransferResult.ExportSuccess -> {
                            Text("File saved to:", color = LocalAppColors.current.textSecondary, fontSize = 10.sp)
                            Spacer(Modifier.height(2.dp))
                            Text(result.displayPath, color = LocalAppColors.current.green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Passwords and API keys are not included — this file goes to " +
                                        "shared storage, so it carries configuration only.",
                                color = LocalAppColors.current.textSecondary,
                                fontSize = 9.sp,
                                lineHeight = 12.sp
                            )
                        }
                        is SettingsTransferResult.ImportSuccess -> {
                            Text(
                                "Settings and connection profiles loaded.",
                                color = LocalAppColors.current.green,
                                fontSize = 10.sp
                            )
                            // Exports carry no secrets, so say plainly what still has to be
                            // entered — otherwise the first upload fails with an auth error.
                            if (result.profilesNeedingPassword.isNotEmpty() || result.apiKeyNeeded) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Still needed:",
                                    color = LocalAppColors.current.warning,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (result.profilesNeedingPassword.isNotEmpty()) {
                                    Text(
                                        "FTP password for: " + result.profilesNeedingPassword.joinToString(", "),
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp
                                    )
                                }
                                if (result.apiKeyNeeded) {
                                    Text(
                                        "Cloud API key: Settings > General > Cloud API",
                                        color = LocalAppColors.current.textSecondary,
                                        fontSize = 9.sp,
                                        lineHeight = 12.sp
                                    )
                                }
                            }
                        }
                        is SettingsTransferResult.Error -> {
                            Text(result.message, color = LocalAppColors.current.warning, fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearTransferResult() }) {
                    Text("OK", color = LocalAppColors.current.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    LaunchedEffect(settings.orientationLockEnabled, settings.orientationLock) {
        activity?.requestedOrientation = if (!settings.orientationLockEnabled) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        } else when (settings.orientationLock) {
            OrientationLock.LANDSCAPE     -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            OrientationLock.LANDSCAPE_180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            OrientationLock.PORTRAIT      -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            OrientationLock.PORTRAIT_180  -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
        }
    }

    if (manifestResult.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { viewModel.clearManifestResult() },
            containerColor = LocalAppColors.current.surface,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Manifest", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(manifestResult, color = LocalAppColors.current.textSecondary, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            },
            confirmButton = {
                TextButton(onClick = { viewModel.clearManifestResult() }) {
                    Text("OK", color = LocalAppColors.current.blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    if (isPortrait) {
        PortraitConfigLayout(
            selectedSection = selectedSection,
            onSectionSelect = { selectedSection = it },
            settings = settings,
            onSettingsChange = { viewModel.save(it) },
            connectionProfiles = connectionProfiles,
            onUpsertProfile = viewModel::upsertProfile,
            onDeleteProfile = viewModel::deleteProfile,
            onSetActiveProfile = viewModel::setActiveProfile,
            onTestConnection = viewModel::testConnection,
            onLoadFtpPassword = viewModel::getFtpPassword,
            onClearSessionHistory = viewModel::clearSessionHistory,
            onExportSettings = { viewModel.exportSettings(context.applicationContext) },
            onPickImportFile = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            cloudRegistrationState = cloudRegistrationState,
            activeSessionKey = activeSessionKey,
            onRegisterDevice = viewModel::registerDevice,
            onFetchManifest = viewModel::fetchManifest,
            debugRetryState = debugRetryState,
            onDebugRetry = viewModel::debugRetryLastUpload,
            cloudApiKey = cloudApiKey,
            onCloudApiKeyChange = viewModel::setCloudApiKey,
            onNavigateBack = onNavigateBack
        )
    } else {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(LocalAppColors.current.background)
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            ConfigSidebar(
                selected = selectedSection,
                onSelect = { selectedSection = it },
                onNavigateBack = onNavigateBack
            )
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(LocalAppColors.current.border))
            ConfigContent(
                section = selectedSection,
                settings = settings,
                onSettingsChange = { viewModel.save(it) },
                connectionProfiles = connectionProfiles,
                onUpsertProfile = viewModel::upsertProfile,
                onDeleteProfile = viewModel::deleteProfile,
                onSetActiveProfile = viewModel::setActiveProfile,
                onTestConnection = viewModel::testConnection,
                onLoadFtpPassword = viewModel::getFtpPassword,
                onClearSessionHistory = viewModel::clearSessionHistory,
                onExportSettings = { viewModel.exportSettings(context.applicationContext) },
                onPickImportFile = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                cloudRegistrationState = cloudRegistrationState,
                activeSessionKey = activeSessionKey,
                onRegisterDevice = viewModel::registerDevice,
                onFetchManifest = viewModel::fetchManifest,
                debugRetryState = debugRetryState,
                onDebugRetry = viewModel::debugRetryLastUpload,
                cloudApiKey = cloudApiKey,
                onCloudApiKeyChange = viewModel::setCloudApiKey,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

// ── Portrait layout ───────────────────────────────────────────────────────────

@Composable
private fun PortraitConfigLayout(
    selectedSection: ConfigSection,
    onSectionSelect: (ConfigSection) -> Unit,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    connectionProfiles: List<ConnectionProfile>,
    onUpsertProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (ConnectionProfile) -> Unit,
    onSetActiveProfile: (ConnectionProfile) -> Unit,
    onTestConnection: (ConnectionProfile, (String?) -> Unit) -> Unit,
    onLoadFtpPassword: (Long) -> String,
    onClearSessionHistory: () -> Unit,
    onExportSettings: () -> Unit,
    onPickImportFile: () -> Unit,
    cloudRegistrationState: String,
    activeSessionKey: String?,
    onRegisterDevice: () -> Unit,
    onFetchManifest: (String) -> Unit,
    debugRetryState: String,
    onDebugRetry: () -> Unit,
    cloudApiKey: String,
    onCloudApiKeyChange: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val portraitTabs = listOf(
        ConfigSection.DEVICE_MODE,
        ConfigSection.GENERAL,
        ConfigSection.CONNECTIONS,
        ConfigSection.FILE_NAMING,
        ConfigSection.ABOUT
    )
    val hasActiveConnection = connectionProfiles.any { it.isActive }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        SettingsBrandTopBar(
            onNavigateBack = onNavigateBack,
            hasActiveConnection = hasActiveConnection
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            SettingsPageHeader()
            SettingsPillTabBar(
                tabs = portraitTabs,
                selected = selectedSection,
                onSelect = onSectionSelect
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                when (selectedSection) {
                    ConfigSection.DEVICE_MODE -> DeviceModeSection(settings, onSettingsChange)
                    ConfigSection.GENERAL -> GeneralSection(
                        settings = settings,
                        onChange = onSettingsChange,
                        onClearSessionHistory = onClearSessionHistory,
                        onExportSettings = onExportSettings,
                        onPickImportFile = onPickImportFile,
                        cloudRegistrationState = cloudRegistrationState,
                        activeSessionKey = activeSessionKey,
                        onRegisterDevice = onRegisterDevice,
                        onFetchManifest = onFetchManifest,
                        debugRetryState = debugRetryState,
                        onDebugRetry = onDebugRetry,
                        cloudApiKey = cloudApiKey,
                        onCloudApiKeyChange = onCloudApiKeyChange
                    )
                    ConfigSection.CONNECTIONS -> ConnectionsSection(
                        profiles = connectionProfiles,
                        onUpsert = onUpsertProfile,
                        onDelete = onDeleteProfile,
                        onSetActive = onSetActiveProfile,
                        onTestConnection = onTestConnection,
                        onLoadFtpPassword = onLoadFtpPassword
                    )
                    ConfigSection.FILE_NAMING -> FileNamingSection(settings, onSettingsChange)
                    ConfigSection.ABOUT -> AboutSection()
                }
            }
        }
    }
}

// ── Brand top bar (portrait) ──────────────────────────────────────────────────

@Composable
private fun SettingsBrandTopBar(
    onNavigateBack: () -> Unit,
    hasActiveConnection: Boolean
) {
    val appColors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(appColors.background)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(appColors.surface)
                    .border(1.dp, appColors.border, RoundedCornerShape(10.dp))
                    .clickable { onNavigateBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = appColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Color(0xFF6FC79A), Color(0xFF4FA77E))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PHOTOFLOW",
                    color = appColors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.9.sp
                )
                Text(
                    "MOBILE",
                    color = appColors.green,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.9.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (hasActiveConnection) appColors.green else appColors.textDisabled)
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(appColors.surface)
                    .border(1.dp, appColors.border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = null,
                    tint = appColors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        HorizontalDivider(color = appColors.border, thickness = 1.dp)
    }
}

// ── Page header (portrait) ────────────────────────────────────────────────────

@Composable
private fun SettingsPageHeader() {
    val appColors = LocalAppColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 18.dp, start = 20.dp, end = 20.dp, bottom = 14.dp)
    ) {
        Text(
            "SETTINGS",
            color = appColors.textDisabled,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.4.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Config",
            color = appColors.textPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Configure transfer, history, backup, and storage.",
            color = appColors.textSecondary,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

// ── Pill tab bar (portrait) ───────────────────────────────────────────────────

@Composable
private fun SettingsPillTabBar(
    tabs: List<ConfigSection>,
    selected: ConfigSection,
    onSelect: (ConfigSection) -> Unit
) {
    val appColors = LocalAppColors.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(appColors.surface)
                .border(1.dp, appColors.border, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            tabs.forEach { tab ->
                val isActive = tab == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (isActive) appColors.surfaceRaised else Color.Transparent)
                        .clickable { onSelect(tab) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        tab.shortLabel.uppercase(),
                        color = if (isActive) appColors.textPrimary else appColors.textSecondary,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
        }
    }
}

// ── Card section wrapper ──────────────────────────────────────────────────────

@Composable
private fun SettingsSection(
    label: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    val appColors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = appColors.textSecondary, modifier = Modifier.size(14.dp))
            Text(
                label,
                color = appColors.textSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(appColors.surface)
                .border(1.dp, appColors.border, RoundedCornerShape(16.dp))
        ) {
            content()
        }
    }
}

// ── Setting row (inside a card) ───────────────────────────────────────────────

@Composable
private fun SettingRow(
    label: String,
    subLabel: String? = null,
    showDivider: Boolean = true,
    control: @Composable () -> Unit
) {
    val appColors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(label, color = appColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                if (subLabel != null) {
                    Text(subLabel, color = appColors.textDisabled, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            control()
        }
        if (showDivider) {
            HorizontalDivider(color = appColors.border, thickness = 1.dp)
        }
    }
}

// ── Ghost button row (inside a card) ─────────────────────────────────────────

@Composable
private fun GhostRowButton(
    label: String,
    icon: ImageVector? = null,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val appColors = LocalAppColors.current
    val textColor = if (danger) appColors.cta else appColors.textSecondary
    val borderColor = if (danger) appColors.cta.copy(alpha = 0.33f) else appColors.border
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
            }
            Text(label, color = textColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
        }
    }
}

// ── Mint switch ───────────────────────────────────────────────────────────────

@Composable
private fun SettingsSwitch(checked: Boolean, onToggle: () -> Unit) {
    val appColors = LocalAppColors.current
    Switch(
        checked = checked,
        onCheckedChange = { onToggle() },
        colors = SwitchDefaults.colors(
            checkedTrackColor = appColors.green,
            checkedThumbColor = Color.White,
            checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = Color(0xFF33373A),
            uncheckedThumbColor = Color(0xFFE8E8E8),
            uncheckedBorderColor = Color.Transparent
        )
    )
}

// ── Stepper ───────────────────────────────────────────────────────────────────

@Composable
private fun Stepper(
    value: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    suffix: String = "",
    min: Int = 1
) {
    val appColors = LocalAppColors.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(appColors.surfaceRaised)
            .border(1.dp, appColors.border, RoundedCornerShape(10.dp))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = value > min) { onDecrement() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                "−",
                color = if (value > min) appColors.textSecondary else appColors.textDisabled,
                fontSize = 18.sp,
                lineHeight = 18.sp
            )
        }
        Text(
            "$value$suffix",
            modifier = Modifier.widthIn(min = 44.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = appColors.textPrimary
        )
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onIncrement() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = appColors.textSecondary, fontSize = 18.sp, lineHeight = 18.sp)
        }
    }
}

// ── Select pill ───────────────────────────────────────────────────────────────

@Composable
private fun SelectPill(
    value: String,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    val appColors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(appColors.surfaceRaised)
                .border(1.dp, appColors.border, RoundedCornerShape(10.dp))
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = appColors.textPrimary)
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = appColors.textSecondary, modifier = Modifier.size(14.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(appColors.surface)
        ) {
            options.forEachIndexed { idx, opt ->
                DropdownMenuItem(
                    text = { Text(opt, color = appColors.textPrimary, fontSize = 13.sp) },
                    onClick = { onSelect(idx); expanded = false }
                )
            }
        }
    }
}

// ── Storage card content ──────────────────────────────────────────────────────

@Composable
private fun StorageCardContent(
    fraction: Float,
    usedBytes: Long,
    totalBytes: Long,
    isWarning: Boolean,
    onClearCache: () -> Unit
) {
    val appColors = LocalAppColors.current
    val pct = (fraction * 100).toInt()
    Column(modifier = Modifier.padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Phone storage", color = appColors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${formatStorageBytes(usedBytes)} of ${formatStorageBytes(totalBytes)} used",
                    color = appColors.textDisabled,
                    fontSize = 12.sp
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "$pct",
                    color = appColors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    "%",
                    color = appColors.textSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 1.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
                .background(Color(0xFF33373A))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(99.dp))
                    .background(
                        if (isWarning) Brush.horizontalGradient(listOf(appColors.warning, appColors.warning))
                        else Brush.horizontalGradient(listOf(Color(0xFF6FC79A), Color(0xFF5BAF87)))
                    )
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, appColors.border, RoundedCornerShape(12.dp))
                .clickable { onClearCache() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, tint = appColors.textSecondary, modifier = Modifier.size(16.dp))
            Text("Clear app cache", color = appColors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Sidebar (landscape) ───────────────────────────────────────────────────────

@Composable
private fun ConfigSidebar(
    selected: ConfigSection,
    onSelect: (ConfigSection) -> Unit,
    onNavigateBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .fillMaxHeight()
            .background(LocalAppColors.current.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CONFIG",
                color = LocalAppColors.current.textPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
        HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
        Spacer(Modifier.height(4.dp))
        ConfigSection.entries.forEach { section ->
            ConfigNavItem(
                label = section.label,
                selected = selected == section,
                onClick = { onSelect(section) }
            )
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateBack() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "← MAIN SCREEN",
                color = LocalAppColors.current.textSecondary,
                fontSize = 8.sp,
                letterSpacing = 0.5.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ConfigNavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (selected) LocalAppColors.current.blue.copy(alpha = 0.08f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(if (selected) LocalAppColors.current.blue else Color.Transparent)
        )
        Text(
            label,
            color = if (selected) LocalAppColors.current.blue else LocalAppColors.current.textPrimary,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        )
    }
}

// ── Content area (landscape + shared) ────────────────────────────────────────

@Composable
private fun ConfigContent(
    section: ConfigSection,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    connectionProfiles: List<ConnectionProfile>,
    onUpsertProfile: (ConnectionProfile) -> Unit,
    onDeleteProfile: (ConnectionProfile) -> Unit,
    onSetActiveProfile: (ConnectionProfile) -> Unit,
    onTestConnection: (ConnectionProfile, (String?) -> Unit) -> Unit,
    onLoadFtpPassword: (Long) -> String,
    onClearSessionHistory: () -> Unit,
    onExportSettings: () -> Unit,
    onPickImportFile: () -> Unit,
    cloudRegistrationState: String,
    activeSessionKey: String?,
    onRegisterDevice: () -> Unit,
    onFetchManifest: (String) -> Unit,
    debugRetryState: String,
    onDebugRetry: () -> Unit,
    cloudApiKey: String,
    onCloudApiKeyChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalAppColors.current.surface)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                section.label.uppercase(),
                color = LocalAppColors.current.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (section) {
                ConfigSection.DEVICE_MODE  -> DeviceModeSection(settings, onSettingsChange)
                ConfigSection.CONNECTIONS  -> ConnectionsSection(
                    profiles = connectionProfiles,
                    onUpsert = onUpsertProfile,
                    onDelete = onDeleteProfile,
                    onSetActive = onSetActiveProfile,
                    onTestConnection = onTestConnection,
                    onLoadFtpPassword = onLoadFtpPassword
                )
                ConfigSection.FILE_NAMING  -> FileNamingSection(settings, onSettingsChange)
                ConfigSection.GENERAL      -> GeneralSection(
                    settings = settings,
                    onChange = onSettingsChange,
                    onClearSessionHistory = onClearSessionHistory,
                    onExportSettings = onExportSettings,
                    onPickImportFile = onPickImportFile,
                    cloudRegistrationState = cloudRegistrationState,
                    activeSessionKey = activeSessionKey,
                    onRegisterDevice = onRegisterDevice,
                    onFetchManifest = onFetchManifest,
                    debugRetryState = debugRetryState,
                    onDebugRetry = onDebugRetry,
                    cloudApiKey = cloudApiKey,
                    onCloudApiKeyChange = onCloudApiKeyChange
                )
                ConfigSection.ABOUT        -> AboutSection()
            }
        }
    }
}

// ── Section: Device Mode ──────────────────────────────────────────────────────

@Composable
private fun DeviceModeSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSection(label = "DEVICE MODE", icon = Icons.Default.Devices) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeCard(
                    title = "Tethered DSLR",
                    subtitle = "USB CONNECTION",
                    selected = settings.deviceMode == DeviceMode.TETHERED_DSLR,
                    onClick = { onChange(settings.copy(deviceMode = DeviceMode.TETHERED_DSLR)) },
                    modifier = Modifier.weight(1f)
                )
                ModeCard(
                    title = "Native Camera",
                    subtitle = "PHONE CAMERA",
                    selected = settings.deviceMode == DeviceMode.NATIVE_CAMERA,
                    onClick = { onChange(settings.copy(deviceMode = DeviceMode.NATIVE_CAMERA)) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        SettingsSection(label = "DISPLAY MODE", icon = Icons.Default.DarkMode) {
            SettingRow(
                label = "Dark mode",
                showDivider = false,
                control = {
                    SettingsSwitch(settings.darkMode) { onChange(settings.copy(darkMode = !settings.darkMode)) }
                }
            )
        }
        SettingsSection(label = "ORIENTATION LOCK", icon = Icons.Default.ScreenRotation) {
            SettingRow(
                label = "Enable orientation lock",
                showDivider = settings.orientationLockEnabled,
                control = {
                    SettingsSwitch(settings.orientationLockEnabled) {
                        onChange(settings.copy(orientationLockEnabled = !settings.orientationLockEnabled))
                    }
                }
            )
            if (settings.orientationLockEnabled) {
                SettingRow(
                    label = "Lock to",
                    showDivider = false,
                    control = {
                        SelectPill(
                            value = settings.orientationLock.label,
                            options = OrientationLock.entries.map { it.label },
                            onSelect = { onChange(settings.copy(orientationLock = OrientationLock.entries[it])) }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clickable { onClick() }
            .border(1.dp, if (selected) LocalAppColors.current.blue else LocalAppColors.current.border)
            .background(if (selected) LocalAppColors.current.blue.copy(alpha = 0.06f) else LocalAppColors.current.surface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (selected) "⊙" else "○",
            color = if (selected) LocalAppColors.current.blue else LocalAppColors.current.textDisabled,
            fontSize = 10.sp
        )
        Column {
            Text(
                title,
                color = if (selected) LocalAppColors.current.blue else LocalAppColors.current.textSecondary,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(subtitle, color = LocalAppColors.current.textDisabled, fontSize = 9.sp, letterSpacing = 0.3.sp)
        }
    }
}

// ── Section: Connections ──────────────────────────────────────────────────────

@Composable
private fun ConnectionsSection(
    profiles: List<ConnectionProfile>,
    onUpsert: (ConnectionProfile) -> Unit,
    onDelete: (ConnectionProfile) -> Unit,
    onSetActive: (ConnectionProfile) -> Unit,
    onTestConnection: (ConnectionProfile, (String?) -> Unit) -> Unit,
    onLoadFtpPassword: (Long) -> String
) {
    var editingId by remember { mutableStateOf<Long?>(null) }
    var draftForm by remember { mutableStateOf(ConnectionProfile(name = "")) }
    var testState by remember { mutableStateOf(ConnTestState.IDLE) }
    var testError by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(editingId) {
        testState = ConnTestState.IDLE
        testError = ""
        draftForm = when (editingId) {
            null -> ConnectionProfile(name = "")
            -1L  -> ConnectionProfile(name = "", connectionType = ConnectionType.FTP)
            else -> {
                val base = profiles.find { it.id == editingId } ?: ConnectionProfile(name = "")
                // Room stores password blank after WI-3; restore from CredentialStore for editing
                if (base.connectionType == ConnectionType.FTP && base.id > 0L)
                    base.copy(password = onLoadFtpPassword(base.id))
                else base
            }
        }
    }

    val onTest: () -> Unit = {
        testState = ConnTestState.TESTING
        testError = ""
        onTestConnection(draftForm) { error ->
            testState = if (error == null) ConnTestState.SUCCESS else ConnTestState.FAILED
            testError = error ?: ""
            scope.launch {
                delay(3_000)
                testState = ConnTestState.IDLE
                testError = ""
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSection(label = "SAVED CONNECTIONS", icon = Icons.Default.Lan) {
            if (profiles.isEmpty() && editingId != -1L) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp)) {
                    Text("No connections configured.", color = LocalAppColors.current.textDisabled, fontSize = 13.sp)
                }
            }
            profiles.forEachIndexed { idx, profile ->
                ConnectionProfileRow(
                    profile = profile,
                    isEditing = editingId == profile.id,
                    showDivider = idx < profiles.lastIndex || true,
                    onEdit = { editingId = if (editingId == profile.id) null else profile.id },
                    onDelete = { onDelete(profile); if (editingId == profile.id) editingId = null },
                    onSetActive = { onSetActive(profile) }
                )
            }
            GhostRowButton(
                label = if (editingId == -1L) "Cancel" else "Add connection",
                icon = if (editingId == -1L) Icons.Default.Close else Icons.Default.Add,
                onClick = { editingId = if (editingId == -1L) null else -1L }
            )
        }

        if (editingId != null) {
            val formLabel = if (editingId == -1L) "NEW CONNECTION" else "EDIT CONNECTION"
            val formIcon = if (editingId == -1L) Icons.Default.Add else Icons.Default.Edit
            SettingsSection(label = formLabel, icon = formIcon) {
                Box(modifier = Modifier.padding(8.dp)) {
                    ConnectionEditForm(
                        draft = draftForm,
                        onDraftChange = { draftForm = it },
                        testState = testState,
                        testError = testError,
                        onTest = onTest,
                        onSave = { onUpsert(draftForm); editingId = null },
                        onCancel = { editingId = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectionProfileRow(
    profile: ConnectionProfile,
    isEditing: Boolean,
    showDivider: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetActive: () -> Unit
) {
    val appColors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (profile.isActive) appColors.green.copy(alpha = 0.05f) else Color.Transparent)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(appColors.surfaceRaised)
                    .border(1.dp, appColors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(profile.connectionType.badge, color = appColors.textDisabled, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    profile.name.ifBlank { "(unnamed)" },
                    color = if (profile.isActive) appColors.textPrimary else appColors.textSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (profile.isActive) FontWeight.SemiBold else FontWeight.Normal
                )
                if (profile.isActive) {
                    Text("● ACTIVE", color = appColors.green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!profile.isActive) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, appColors.border, RoundedCornerShape(8.dp))
                            .clickable { onSetActive() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text("SET ACTIVE", color = appColors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isEditing) appColors.green.copy(alpha = 0.1f) else Color.Transparent)
                        .border(1.dp, if (isEditing) appColors.green else appColors.border, RoundedCornerShape(8.dp))
                        .clickable { onEdit() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(if (isEditing) "CLOSE" else "EDIT", color = if (isEditing) appColors.green else appColors.textSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, tint = appColors.textDisabled, modifier = Modifier.size(16.dp))
                }
            }
        }
        if (showDivider) HorizontalDivider(color = appColors.border)
    }
}

@Composable
private fun ConnectionEditForm(
    draft: ConnectionProfile,
    onDraftChange: (ConnectionProfile) -> Unit,
    testState: ConnTestState,
    testError: String,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, LocalAppColors.current.border)
            .background(LocalAppColors.current.surface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ConnectionType.entries.forEach { type ->
                val selected = draft.connectionType == type
                Box(
                    modifier = Modifier
                        .border(1.dp, if (selected) LocalAppColors.current.blue else LocalAppColors.current.border)
                        .background(if (selected) LocalAppColors.current.blue.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onDraftChange(draft.copy(connectionType = type)) }
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(type.badge, color = if (selected) LocalAppColors.current.blue else LocalAppColors.current.textSecondary,
                        fontSize = 8.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        ConfigTextField("Name", draft.name) { onDraftChange(draft.copy(name = it)) }
        when (draft.connectionType) {
            ConnectionType.FTP -> {
                ConfigTextField("Host", draft.host) { onDraftChange(draft.copy(host = it)) }
                ConfigTextField("Port", draft.port.toString(), keyboardType = KeyboardType.Number) {
                    onDraftChange(draft.copy(port = it.toIntOrNull() ?: draft.port))
                }
                ConfigTextField("Username",    draft.username)           { onDraftChange(draft.copy(username = it)) }
                ConfigTextField("Password",    draft.password, isPassword = true) { onDraftChange(draft.copy(password = it)) }
                ConfigTextField("Remote Path", draft.remotePath)         { onDraftChange(draft.copy(remotePath = it)) }
                ConfigTextField(
                    label = "Photo Op",
                    value = draft.photoOp,
                    placeholder = "subfolder name (optional)"
                ) { onDraftChange(draft.copy(photoOp = it)) }
            }
            ConnectionType.CLOUD_API -> {
                ConfigTextField("Base URL", draft.host,
                    placeholder = "http://192.168.x.x:8000/api/v1") { onDraftChange(draft.copy(host = it)) }
            }
        }

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (testLabel, testColor, testBg) = when (testState) {
                ConnTestState.IDLE    -> Triple("TEST", LocalAppColors.current.blue, LocalAppColors.current.blue.copy(alpha = 0.08f))
                ConnTestState.TESTING -> Triple("TESTING...", LocalAppColors.current.textSecondary, Color.Transparent)
                ConnTestState.SUCCESS -> Triple("● OK", LocalAppColors.current.green, LocalAppColors.current.green.copy(alpha = 0.08f))
                ConnTestState.FAILED  -> Triple("⚠ FAILED", LocalAppColors.current.warning, LocalAppColors.current.warning.copy(alpha = 0.08f))
            }
            Box(
                modifier = Modifier
                    .border(1.dp, testColor)
                    .background(testBg)
                    .clickable(enabled = testState == ConnTestState.IDLE) { onTest() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(testLabel, color = testColor, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            if (testState == ConnTestState.FAILED && testError.isNotBlank()) {
                Text(testError, color = LocalAppColors.current.warning, fontSize = 7.sp, modifier = Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                modifier = Modifier
                    .border(0.5.dp, LocalAppColors.current.border)
                    .clickable { onCancel() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("CANCEL", color = LocalAppColors.current.textSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            val canSave = draft.name.isNotBlank()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (canSave) LocalAppColors.current.blue else LocalAppColors.current.border)
                    .clickable(enabled = canSave) { onSave() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "SAVE PROFILE",
                    color = if (canSave) Color.White else LocalAppColors.current.textDisabled,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ── Section: File Naming ──────────────────────────────────────────────────────

@Composable
private fun FileNamingSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val appColors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSection(label = "NAMING FIELDS", icon = Icons.Default.TextFields) {
            settings.namingFields.forEachIndexed { index, field ->
                NamingFieldRow(
                    number = index + 1,
                    field = field,
                    showDivider = true,
                    onTypeChange = { newType ->
                        onChange(settings.copy(namingFields = settings.namingFields.mapIndexed { i, f ->
                            if (i == index) NamingField(newType) else f
                        }))
                    },
                    onCustomValueChange = { value ->
                        onChange(settings.copy(namingFields = settings.namingFields.mapIndexed { i, f ->
                            if (i == index) f.copy(customValue = value) else f
                        }))
                    },
                    onRemove = if (settings.namingFields.size > 1) ({
                        onChange(settings.copy(namingFields = settings.namingFields.filterIndexed { i, _ -> i != index }))
                    }) else null
                )
            }
            GhostRowButton(
                label = "Add field",
                icon = Icons.Default.Add,
                onClick = { onChange(settings.copy(namingFields = settings.namingFields + NamingField(FieldType.CUSTOM))) }
            )
        }
        SettingsSection(label = "OPTIONS", icon = Icons.Default.Tune) {
            SettingRow(label = "Separator", showDivider = true, control = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("_", "-", ".").forEach { sep ->
                        val sel = sep == settings.namingSeparator
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) appColors.green else appColors.surfaceRaised)
                                .border(1.dp, if (sel) appColors.green else appColors.border, RoundedCornerShape(8.dp))
                                .clickable { onChange(settings.copy(namingSeparator = sep)) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(sep, color = if (sel) Color.White else appColors.textSecondary, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            })
            SettingRow(label = "Extension", showDivider = false, control = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("JPG", "DNG", "RAW").forEach { ext ->
                        val sel = ext == settings.namingExtension
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) appColors.green else appColors.surfaceRaised)
                                .border(1.dp, if (sel) appColors.green else appColors.border, RoundedCornerShape(8.dp))
                                .clickable { onChange(settings.copy(namingExtension = ext)) }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(ext, color = if (sel) Color.White else appColors.textSecondary, fontSize = 13.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            })
        }
        SettingsSection(label = "PREVIEW", icon = Icons.Default.Visibility) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Text(
                    "${buildNamingPreview(settings.namingFields, settings.namingSeparator)}.${settings.namingExtension}",
                    color = appColors.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
    }
}

@Composable
private fun NamingFieldRow(
    number: Int,
    field: NamingField,
    onTypeChange: (FieldType) -> Unit,
    onCustomValueChange: (String) -> Unit,
    onRemove: (() -> Unit)?,
    showDivider: Boolean = true
) {
    val appColors = LocalAppColors.current
    var expanded by remember { mutableStateOf(false) }
    var customDraft by remember(number, field.type) { mutableStateOf(field.customValue) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(appColors.surfaceRaised)
                    .border(1.dp, appColors.border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text("$number", color = appColors.textDisabled, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(appColors.green.copy(alpha = 0.08f))
                        .border(1.dp, appColors.green.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { expanded = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(field.type.label, color = appColors.green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Default.KeyboardArrowDown, null, tint = appColors.green, modifier = Modifier.size(14.dp))
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(appColors.surface)
                ) {
                    FieldType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label, color = appColors.textPrimary, fontSize = 13.sp) },
                            onClick = { onTypeChange(type); expanded = false }
                        )
                    }
                }
            }
            if (field.type == FieldType.CUSTOM) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(appColors.surfaceRaised)
                        .border(1.dp, appColors.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (customDraft.isEmpty()) {
                        Text("Enter text...", color = appColors.textDisabled, fontSize = 12.sp)
                    }
                    BasicTextField(
                        value = customDraft,
                        onValueChange = { newVal -> customDraft = newVal; onCustomValueChange(newVal) },
                        textStyle = TextStyle(color = appColors.textPrimary, fontSize = 12.sp),
                        cursorBrush = SolidColor(appColors.blue),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (onRemove != null) {
                Box(
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { onRemove() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Close, null, tint = appColors.textDisabled, modifier = Modifier.size(16.dp))
                }
            } else {
                Spacer(Modifier.width(28.dp))
            }
        }
        if (showDivider) HorizontalDivider(color = appColors.border)
    }
}

// ── Section: General ──────────────────────────────────────────────────────────

@Composable
private fun GeneralSection(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    onClearSessionHistory: () -> Unit,
    onExportSettings: () -> Unit,
    onPickImportFile: () -> Unit,
    cloudRegistrationState: String,
    activeSessionKey: String?,
    onRegisterDevice: () -> Unit,
    onFetchManifest: (String) -> Unit,
    debugRetryState: String,
    onDebugRetry: () -> Unit,
    cloudApiKey: String,
    onCloudApiKeyChange: (String) -> Unit
) {
    val appColors = LocalAppColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

        // ── File Transfer ─────────────────────────────────────────────────────
        SettingsSection(label = "FILE TRANSFER", icon = Icons.Default.Sync) {
            SettingRow(
                label = "Auto retry",
                subLabel = "Re-attempt failed transfers automatically",
                showDivider = settings.autoRetryEnabled,
                control = {
                    SettingsSwitch(settings.autoRetryEnabled) {
                        onChange(settings.copy(autoRetryEnabled = !settings.autoRetryEnabled))
                    }
                }
            )
            if (settings.autoRetryEnabled) {
                SettingRow(
                    label = "Retry interval",
                    subLabel = "Wait time between attempts",
                    showDivider = true,
                    control = {
                        val intervalVal = settings.autoRetryIntervalSeconds.coerceAtLeast(1)
                        Stepper(
                            value = intervalVal,
                            onDecrement = { onChange(settings.copy(autoRetryIntervalSeconds = (intervalVal - 1).coerceAtLeast(1))) },
                            onIncrement = { onChange(settings.copy(autoRetryIntervalSeconds = intervalVal + 1)) },
                            suffix = "s",
                            min = 1
                        )
                    }
                )
                val maxRetryOptions = listOf("1" to 1, "3" to 3, "5" to 5, "Continuous" to -1)
                val currentMaxLabel = maxRetryOptions.find { it.second == settings.autoRetryMaxCount }?.first ?: "Continuous"
                SettingRow(
                    label = "Max retries",
                    showDivider = false,
                    control = {
                        SelectPill(
                            value = currentMaxLabel,
                            options = maxRetryOptions.map { it.first },
                            onSelect = { idx -> onChange(settings.copy(autoRetryMaxCount = maxRetryOptions[idx].second)) }
                        )
                    }
                )
            }
        }

        // ── Session History ───────────────────────────────────────────────────
        var showClearSessionsDialog by remember { mutableStateOf(false) }
        var sessionsCleared by remember { mutableStateOf(false) }

        SettingsSection(label = "SESSION HISTORY", icon = Icons.Default.History) {
            val histVal = settings.sessionHistoryMax.takeIf { it > 0 } ?: 50
            SettingRow(
                label = "Session history max",
                subLabel = "Number of recent sessions kept on device",
                showDivider = true,
                control = {
                    Stepper(
                        value = histVal,
                        onDecrement = { onChange(settings.copy(sessionHistoryMax = (histVal - 1).coerceAtLeast(5))) },
                        onIncrement = { onChange(settings.copy(sessionHistoryMax = histVal + 1)) },
                        min = 5
                    )
                }
            )
            GhostRowButton(
                label = if (sessionsCleared) "Session history cleared" else "Clear session history",
                icon = Icons.Default.Delete,
                onClick = { if (!sessionsCleared) showClearSessionsDialog = true }
            )
        }

        if (showClearSessionsDialog) {
            AlertDialog(
                onDismissRequest = { showClearSessionsDialog = false },
                containerColor = appColors.surface,
                titleContentColor = appColors.textPrimary,
                title = { Text("Clear Session History?", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("WILL BE DELETED:", color = appColors.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("· All completed sessions", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Associated image records", color = appColors.textSecondary, fontSize = 9.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("WILL NOT BE DELETED:", color = appColors.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("· Active session", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Image files on device", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Connection profiles", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· App settings", color = appColors.textSecondary, fontSize = 9.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onClearSessionHistory()
                        showClearSessionsDialog = false
                        sessionsCleared = true
                    }) {
                        Text("CLEAR HISTORY", color = appColors.cta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearSessionsDialog = false }) {
                        Text("CANCEL", color = appColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // ── Photo Backup ──────────────────────────────────────────────────────
        SettingsSection(label = "PHOTO BACKUP", icon = Icons.Default.Backup) {
            SettingRow(
                label = "Save backup to phone",
                subLabel = "Keep a local copy of every capture",
                showDivider = true,
                control = {
                    SettingsSwitch(settings.saveBackupToPhone) {
                        onChange(settings.copy(saveBackupToPhone = !settings.saveBackupToPhone))
                    }
                }
            )
            SettingRow(
                label = "Auto-delete backups",
                subLabel = "Remove after successful transfer",
                showDivider = settings.autoDeleteBackups,
                control = {
                    SettingsSwitch(settings.autoDeleteBackups) {
                        onChange(settings.copy(autoDeleteBackups = !settings.autoDeleteBackups))
                    }
                }
            )
            if (settings.autoDeleteBackups) {
                val dayOptions = listOf(1 to "1 day", 7 to "7 days", 14 to "14 days", 30 to "30 days", 60 to "60 days", 90 to "90 days")
                val dayLabels = dayOptions.map { it.second }
                val dayIdx = dayOptions.indexOfFirst { it.first == settings.autoDeleteAfterDays }.takeIf { it >= 0 } ?: 3
                SettingRow(
                    label = "Delete after",
                    showDivider = false,
                    control = {
                        SelectPill(
                            value = dayLabels.getOrElse(dayIdx) { "30 days" },
                            options = dayLabels,
                            onSelect = { idx -> onChange(settings.copy(autoDeleteAfterDays = dayOptions[idx].first)) }
                        )
                    }
                )
            }
        }

        // ── Storage ───────────────────────────────────────────────────────────
        val stat = remember { StatFs(Environment.getDataDirectory().path) }
        val totalBytes = stat.totalBytes
        val availableBytes = stat.availableBytes
        val usedBytes = totalBytes - availableBytes
        val usedFraction = (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        var showClearDialog by remember { mutableStateOf(false) }

        SettingsSection(label = "STORAGE", icon = Icons.Default.Storage) {
            StorageCardContent(
                fraction = usedFraction,
                usedBytes = usedBytes,
                totalBytes = totalBytes,
                isWarning = usedFraction > 0.85f,
                onClearCache = { showClearDialog = true }
            )
        }

        if (showClearDialog) {
            AlertDialog(
                onDismissRequest = { showClearDialog = false },
                containerColor = appColors.surface,
                titleContentColor = appColors.textPrimary,
                title = { Text("Clear App Cache?", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("WILL BE DELETED:", color = appColors.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("· Image preview cache", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Temporary app files", color = appColors.textSecondary, fontSize = 9.sp)
                        Spacer(Modifier.height(2.dp))
                        Text("WILL NOT BE DELETED:", color = appColors.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("· Session data and history", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Captured images", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Connection profiles", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· Transfer queue", color = appColors.textSecondary, fontSize = 9.sp)
                        Text("· App settings", color = appColors.textSecondary, fontSize = 9.sp)
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        scope.launch(Dispatchers.IO) {
                            context.cacheDir?.deleteRecursively()
                            context.externalCacheDir?.deleteRecursively()
                        }
                        showClearDialog = false
                    }) {
                        Text("CLEAR CACHE", color = appColors.cta, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showClearDialog = false }) {
                        Text("CANCEL", color = appColors.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        // ── Logs (existing style) ─────────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel("LOGS")
            ConfigToggleRow("Enable logging", settings.loggingEnabled) {
                onChange(settings.copy(loggingEnabled = !settings.loggingEnabled))
            }
            InfoRow("Log Location", "PhotoFlow/Pipeline")
        }

        // ── Import / Export (existing style) ──────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("IMPORT / EXPORT")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LocalAppColors.current.border)
                    .clickable { onExportSettings() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("EXPORT SETTINGS", color = LocalAppColors.current.blue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Text("Saves all settings and connection profiles to a JSON file in Downloads", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LocalAppColors.current.border)
                    .clickable { onPickImportFile() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("IMPORT SETTINGS", color = LocalAppColors.current.textSecondary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    Text("Select a PhotoFlow settings file — overwrites current settings and connections", color = LocalAppColors.current.textDisabled, fontSize = 8.sp)
                }
            }
        }

        // ── Cloud API (existing style) ────────────────────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("CLOUD API")
            var setupCodeDraft by remember(settings.cloudSetupCode) { mutableStateOf(settings.cloudSetupCode) }
            ConfigTextField("Setup Code", setupCodeDraft, placeholder = "Venue setup code") { v ->
                setupCodeDraft = v
                onChange(settings.copy(cloudSetupCode = v))
            }
            // Bound to CredentialStore via onCloudApiKeyChange, not to settings: the key must
            // never travel through AppSettings, which is persisted to DataStore in plaintext.
            var apiKeyDraft by remember(cloudApiKey) { mutableStateOf(cloudApiKey) }
            var apiKeyVisible by remember { mutableStateOf(false) }
            ConfigTextField(
                label = "API Key",
                value = apiKeyDraft,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingContent = {
                    Text(
                        if (apiKeyVisible) "HIDE" else "SHOW",
                        color = LocalAppColors.current.textDisabled,
                        fontSize = 7.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable { apiKeyVisible = !apiKeyVisible }.padding(4.dp)
                    )
                }
            ) { v ->
                apiKeyDraft = v
                onCloudApiKeyChange(v)
            }
            var stationNameDraft by remember(settings.cloudStationName) { mutableStateOf(settings.cloudStationName) }
            ConfigTextField("Station Name (optional)", stationNameDraft) { v ->
                stationNameDraft = v
                onChange(settings.copy(cloudStationName = v))
            }
            var displayNameDraft by remember(settings.cloudDeviceDisplayName) { mutableStateOf(settings.cloudDeviceDisplayName) }
            ConfigTextField("Device Display Name", displayNameDraft) {
                displayNameDraft = it
                onChange(settings.copy(cloudDeviceDisplayName = it))
            }
            InfoRow("Device UUID", settings.cloudDeviceUuid.ifBlank { "— (generated on first registration)" })
            InfoRow("Device ID", if (settings.cloudDeviceId != 0) settings.cloudDeviceId.toString() else "Not registered")
            if (settings.cloudVenueId != 0) InfoRow("Venue ID", settings.cloudVenueId.toString())
            if (settings.cloudVenueSlug.isNotBlank()) InfoRow("Venue Slug", settings.cloudVenueSlug)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(LocalAppColors.current.blue)
                        .clickable { onRegisterDevice() }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text("REGISTER DEVICE", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                }
                if (activeSessionKey != null) {
                    Box(
                        modifier = Modifier
                            .border(0.5.dp, LocalAppColors.current.border)
                            .clickable { onFetchManifest(activeSessionKey) }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("FETCH MANIFEST", color = LocalAppColors.current.textSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (cloudRegistrationState.isNotBlank()) {
                val stateColor = when {
                    cloudRegistrationState.startsWith("Registered") -> LocalAppColors.current.green
                    cloudRegistrationState.startsWith("Error") ||
                    cloudRegistrationState.startsWith("No active") ||
                    cloudRegistrationState.startsWith("Registration failed") -> LocalAppColors.current.warning
                    else -> LocalAppColors.current.textSecondary
                }
                Text(cloudRegistrationState, color = stateColor, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }

        // ── DEV ONLY ──────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .border(1.dp, LocalAppColors.current.warning.copy(alpha = 0.5f))
                .clickable { onDebugRetry() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                "RETRY LAST UPLOAD (DEBUG)",
                color = LocalAppColors.current.warning,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        if (debugRetryState.isNotBlank()) {
            Text(
                debugRetryState,
                color = LocalAppColors.current.textSecondary,
                fontSize = 7.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        // ── App Info ──────────────────────────────────────────────────────────
        SettingsSection(label = "APP INFO", icon = Icons.Default.Info) {
            CardInfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", showDivider = true)
            CardInfoRow("Built", BuildConfig.BUILD_TIME, showDivider = true)
            CardInfoRow("Min SDK", "API 26", showDivider = true)
            CardInfoRow("Target SDK", "API 34", showDivider = false)
        }
    }
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
    else                     -> "$bytes B"
}

@Composable
private fun StorageRow(label: String, fraction: Float, usedLabel: String, totalLabel: String, isWarning: Boolean) {
    val barColor = if (isWarning) LocalAppColors.current.warning else LocalAppColors.current.blue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, LocalAppColors.current.border)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 10.sp, modifier = Modifier.width(52.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f).height(5.dp),
            color = barColor,
            trackColor = LocalAppColors.current.border
        )
        Text(usedLabel, color = barColor, fontSize = 10.sp, modifier = Modifier.width(28.dp))
        Text(totalLabel, color = LocalAppColors.current.textDisabled, fontSize = 10.sp)
    }
}

// ── Section: About ────────────────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    val appColors = LocalAppColors.current
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        SettingsSection(label = "APP INFO", icon = Icons.Default.Info) {
            CardInfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", showDivider = true)
            CardInfoRow("Built", BuildConfig.BUILD_TIME, showDivider = true)
            CardInfoRow("Min SDK", "API 26", showDivider = true)
            CardInfoRow("Target SDK", "API 34", showDivider = false)
        }
        SettingsSection(label = "ABOUT", icon = Icons.Default.PhotoCamera) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("PhotoFlow Mobile", color = appColors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Professional field operations — barcode-based image capture and background FTP transfer.",
                    color = appColors.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
                Text("© 2026 PhotoFlow", color = appColors.textDisabled, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CardInfoRow(label: String, value: String, showDivider: Boolean = true) {
    val appColors = LocalAppColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = appColors.textSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = appColors.textPrimary, fontSize = 13.sp)
        }
        if (showDivider) HorizontalDivider(color = appColors.border)
    }
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text, color = LocalAppColors.current.textPrimary, fontSize = 9.sp, letterSpacing = 0.8.sp)
        HorizontalDivider(color = LocalAppColors.current.border.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    placeholder: String = "",
    visualTransformation: VisualTransformation? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    onValueChange: (String) -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val transform = visualTransformation
        ?: if (isPassword && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 11.sp, modifier = Modifier.width(96.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(placeholder, color = LocalAppColors.current.textDisabled, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = LocalAppColors.current.textPrimary, fontSize = 12.sp),
                cursorBrush = SolidColor(LocalAppColors.current.blue),
                singleLine = true,
                visualTransformation = transform,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (isPassword) {
            IconButton(
                onClick = { passwordVisible = !passwordVisible },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    tint = LocalAppColors.current.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else if (trailingContent != null) {
            trailingContent()
        }
    }
}

@Composable
private fun ConfigToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clickable { onToggle() }
                .border(1.dp, if (value) LocalAppColors.current.blue else LocalAppColors.current.border)
                .background(if (value) LocalAppColors.current.blue.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(if (value) "ON" else "OFF", color = if (value) LocalAppColors.current.blue else LocalAppColors.current.textDisabled, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConfigDropdownRow(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .border(0.5.dp, LocalAppColors.current.border)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(value, color = LocalAppColors.current.textPrimary, fontSize = 11.sp)
                Text("▾", color = LocalAppColors.current.textSecondary, fontSize = 10.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(LocalAppColors.current.surface)) {
                options.forEachIndexed { idx, option ->
                    DropdownMenuItem(
                        text = { Text(option, color = LocalAppColors.current.textPrimary, fontSize = 11.sp) },
                        onClick = { onSelect(idx); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = LocalAppColors.current.textSecondary, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = LocalAppColors.current.textPrimary, fontSize = 10.sp)
    }
}
