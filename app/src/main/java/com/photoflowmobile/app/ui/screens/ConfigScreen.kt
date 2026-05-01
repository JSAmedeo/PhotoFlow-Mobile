package com.photoflowmobile.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

private enum class ConfigSection(val label: String) {
    DEVICE_MODE("Device Mode"),
    GENERAL("General"),
    CONNECTIONS("Connections"),
    FILE_NAMING("File Naming"),
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
                        }
                        is SettingsTransferResult.ImportSuccess -> {
                            Text(
                                "All settings and connection profiles have been loaded successfully.",
                                color = LocalAppColors.current.green,
                                fontSize = 10.sp
                            )
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
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
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
            onClearSessionHistory = viewModel::clearSessionHistory,
            onExportSettings = { viewModel.exportSettings(context.applicationContext) },
            onPickImportFile = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            cloudRegistrationState = cloudRegistrationState,
            activeSessionKey = activeSessionKey,
            onRegisterDevice = viewModel::registerDevice,
            onFetchManifest = viewModel::fetchManifest,
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
                onClearSessionHistory = viewModel::clearSessionHistory,
                onExportSettings = { viewModel.exportSettings(context.applicationContext) },
                onPickImportFile = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                cloudRegistrationState = cloudRegistrationState,
                activeSessionKey = activeSessionKey,
                onRegisterDevice = viewModel::registerDevice,
                onFetchManifest = viewModel::fetchManifest,
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
        }
    }
}

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
    onClearSessionHistory: () -> Unit,
    onExportSettings: () -> Unit,
    onPickImportFile: () -> Unit,
    cloudRegistrationState: String,
    activeSessionKey: String?,
    onRegisterDevice: () -> Unit,
    onFetchManifest: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val sections = ConfigSection.entries
    val selectedIndex = sections.indexOf(selectedSection)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(LocalAppColors.current.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalAppColors.current.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CONFIG",
                color = LocalAppColors.current.textPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                "← MAIN SCREEN",
                color = LocalAppColors.current.textSecondary,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.clickable { onNavigateBack() }
            )
        }
        HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp)

        // Scrollable tab row
        ScrollableTabRow(
            selectedTabIndex = selectedIndex,
            containerColor = LocalAppColors.current.surface,
            contentColor = LocalAppColors.current.blue,
            edgePadding = 0.dp,
            divider = { HorizontalDivider(color = LocalAppColors.current.border, thickness = 1.dp) }
        ) {
            sections.forEachIndexed { index, section ->
                Tab(
                    selected = index == selectedIndex,
                    onClick = { onSectionSelect(section) },
                    modifier = Modifier.height(38.dp)
                ) {
                    Text(
                        section.label.uppercase(),
                        color = if (index == selectedIndex) LocalAppColors.current.blue else LocalAppColors.current.textPrimary,
                        fontSize = 8.sp,
                        fontWeight = if (index == selectedIndex) FontWeight.Bold else FontWeight.Normal,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        // Full-width content
        ConfigContent(
            section = selectedSection,
            settings = settings,
            onSettingsChange = onSettingsChange,
            connectionProfiles = connectionProfiles,
            onUpsertProfile = onUpsertProfile,
            onDeleteProfile = onDeleteProfile,
            onSetActiveProfile = onSetActiveProfile,
            onTestConnection = onTestConnection,
            onClearSessionHistory = onClearSessionHistory,
            onExportSettings = onExportSettings,
            onPickImportFile = onPickImportFile,
            cloudRegistrationState = cloudRegistrationState,
            activeSessionKey = activeSessionKey,
            onRegisterDevice = onRegisterDevice,
            onFetchManifest = onFetchManifest,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
    }
}

// ── Sidebar ───────────────────────────────────────────────────────────────────

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

// ── Content area ──────────────────────────────────────────────────────────────

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
    onClearSessionHistory: () -> Unit,
    onExportSettings: () -> Unit,
    onPickImportFile: () -> Unit,
    cloudRegistrationState: String,
    activeSessionKey: String?,
    onRegisterDevice: () -> Unit,
    onFetchManifest: (String) -> Unit,
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
                    onTestConnection = onTestConnection
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
                    onFetchManifest = onFetchManifest
                )
                ConfigSection.ABOUT        -> AboutSection()
            }
        }
    }
}

// ── Section: Device Mode ──────────────────────────────────────────────────────

@Composable
private fun DeviceModeSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                subtitle = "REAR FACING PHONE CAMERA",
                selected = settings.deviceMode == DeviceMode.NATIVE_CAMERA,
                onClick = { onChange(settings.copy(deviceMode = DeviceMode.NATIVE_CAMERA)) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(2.dp))
        SectionLabel("DISPLAY MODE")
        ConfigToggleRow("Dark mode", settings.darkMode) {
            onChange(settings.copy(darkMode = !settings.darkMode))
        }
        Spacer(Modifier.height(6.dp))
        SectionLabel("ORIENTATION LOCK")
        ConfigToggleRow("Enable orientation lock", settings.orientationLockEnabled) {
            onChange(settings.copy(orientationLockEnabled = !settings.orientationLockEnabled))
        }
        if (settings.orientationLockEnabled) {
            ConfigDropdownRow(
                label    = "Lock to",
                value    = settings.orientationLock.label,
                options  = OrientationLock.entries.map { it.label },
                onSelect = { onChange(settings.copy(orientationLock = OrientationLock.entries[it])) }
            )
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
    onTestConnection: (ConnectionProfile, (String?) -> Unit) -> Unit
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
            else -> profiles.find { it.id == editingId } ?: ConnectionProfile(name = "")
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

    SectionLabel("SAVED CONNECTIONS")

    if (profiles.isEmpty() && editingId != -1L) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, LocalAppColors.current.border)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                "No connections configured.",
                color = LocalAppColors.current.textDisabled,
                fontSize = 8.sp
            )
        }
    }

    profiles.forEach { profile ->
        val isEditingThis = editingId == profile.id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, if (isEditingThis) LocalAppColors.current.blue.copy(alpha = 0.5f) else LocalAppColors.current.border)
                .background(if (profile.isActive) LocalAppColors.current.surfaceRaised else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .border(0.5.dp, LocalAppColors.current.border)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    profile.connectionType.badge,
                    color = LocalAppColors.current.textDisabled,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                profile.name.ifBlank { "(unnamed)" },
                color = if (profile.isActive) LocalAppColors.current.textPrimary else LocalAppColors.current.textSecondary,
                fontSize = 11.sp,
                fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (profile.isActive) {
                Text("● ACTIVE", color = LocalAppColors.current.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            } else {
                Box(
                    modifier = Modifier
                        .clickable { onSetActive(profile) }
                        .border(0.5.dp, LocalAppColors.current.border)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "SET ACTIVE",
                        color = LocalAppColors.current.textDisabled,
                        fontSize = 9.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clickable { editingId = if (isEditingThis) null else profile.id }
                    .border(0.5.dp, if (isEditingThis) LocalAppColors.current.blue else LocalAppColors.current.border)
                    .background(if (isEditingThis) LocalAppColors.current.blue.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    if (isEditingThis) "CLOSE" else "EDIT",
                    color = if (isEditingThis) LocalAppColors.current.blue else LocalAppColors.current.textSecondary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Box(
                modifier = Modifier
                    .clickable {
                        onDelete(profile)
                        if (isEditingThis) editingId = null
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text("×", color = LocalAppColors.current.textDisabled, fontSize = 14.sp)
            }
        }
        if (isEditingThis) {
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

    Spacer(Modifier.height(4.dp))

    if (editingId != -1L) {
        Box(
            modifier = Modifier
                .border(0.5.dp, LocalAppColors.current.border)
                .clickable { editingId = -1L }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                "+ ADD CONNECTION",
                color = LocalAppColors.current.blue,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    } else {
        Text(
            "NEW CONNECTION",
            color = LocalAppColors.current.blue.copy(alpha = 0.6f),
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )
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
        // Type selector
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
            }
            ConnectionType.CLOUD_API -> {
                ConfigTextField("Base URL", draft.host,
                    placeholder = "http://192.168.1.x:8000") { onDraftChange(draft.copy(host = it)) }
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
                    .border(1.dp, if (canSave) LocalAppColors.current.blue else LocalAppColors.current.border)
                    .background(if (canSave) LocalAppColors.current.blue.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable(enabled = canSave) { onSave() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "SAVE PROFILE",
                    color = if (canSave) LocalAppColors.current.blue else LocalAppColors.current.textDisabled,
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
    SectionLabel("NAMING FIELDS")
    settings.namingFields.forEachIndexed { index, field ->
        NamingFieldRow(
            number = index + 1,
            field = field,
            onTypeChange = { newType ->
                val newFields = settings.namingFields.mapIndexed { i, f ->
                    if (i == index) NamingField(newType) else f
                }
                onChange(settings.copy(namingFields = newFields))
            },
            onCustomValueChange = { value ->
                val newFields = settings.namingFields.mapIndexed { i, f ->
                    if (i == index) f.copy(customValue = value) else f
                }
                onChange(settings.copy(namingFields = newFields))
            },
            onRemove = if (settings.namingFields.size > 1) ({
                onChange(settings.copy(namingFields = settings.namingFields.filterIndexed { i, _ -> i != index }))
            }) else null
        )
    }
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .border(0.5.dp, LocalAppColors.current.border)
            .clickable { onChange(settings.copy(namingFields = settings.namingFields + NamingField(FieldType.CUSTOM))) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("+ ADD FIELD", color = LocalAppColors.current.blue, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Separator", color = LocalAppColors.current.textSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        listOf("_", "-", ".").forEach { sep ->
            val sel = sep == settings.namingSeparator
            Box(
                modifier = Modifier
                    .clickable { onChange(settings.copy(namingSeparator = sep)) }
                    .border(1.dp, if (sel) LocalAppColors.current.blue else LocalAppColors.current.border)
                    .background(if (sel) LocalAppColors.current.blue.copy(alpha = 0.1f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(sep, color = if (sel) LocalAppColors.current.blue else LocalAppColors.current.textSecondary, fontSize = 9.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, LocalAppColors.current.border).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Extension", color = LocalAppColors.current.textSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        listOf("JPG", "DNG", "RAW").forEach { ext ->
            val sel = ext == settings.namingExtension
            Box(
                modifier = Modifier
                    .clickable { onChange(settings.copy(namingExtension = ext)) }
                    .border(1.dp, if (sel) LocalAppColors.current.blue else LocalAppColors.current.border)
                    .background(if (sel) LocalAppColors.current.blue.copy(alpha = 0.1f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(ext, color = if (sel) LocalAppColors.current.blue else LocalAppColors.current.textSecondary, fontSize = 8.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("PREVIEW")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, LocalAppColors.current.border)
            .background(LocalAppColors.current.background)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "${buildNamingPreview(settings.namingFields, settings.namingSeparator)}.${settings.namingExtension}",
            color = LocalAppColors.current.textPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.3.sp
        )
    }
}

@Composable
private fun NamingFieldRow(
    number: Int,
    field: NamingField,
    onTypeChange: (FieldType) -> Unit,
    onCustomValueChange: (String) -> Unit,
    onRemove: (() -> Unit)?
) {
    var expanded by remember { mutableStateOf(false) }
    // Local draft prevents DataStore round-trip lag from clobbering the cursor on each keystroke.
    // Keyed on (number, field.type) so it resets if the field slot or type changes, but not on
    // every DataStore emission caused by our own edits.
    var customDraft by remember(number, field.type) { mutableStateOf(field.customValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, LocalAppColors.current.border)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "FIELD $number",
            color = LocalAppColors.current.textDisabled,
            fontSize = 7.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(44.dp)
        )
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .border(0.5.dp, LocalAppColors.current.blue.copy(alpha = 0.5f))
                    .background(LocalAppColors.current.blue.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(field.type.label, color = LocalAppColors.current.blue, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("▾", color = LocalAppColors.current.blue.copy(alpha = 0.6f), fontSize = 8.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(LocalAppColors.current.surface)
            ) {
                FieldType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label, color = LocalAppColors.current.textPrimary, fontSize = 9.sp) },
                        onClick = { onTypeChange(type); expanded = false }
                    )
                }
            }
        }
        if (field.type == FieldType.CUSTOM) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(LocalAppColors.current.background)
                    .border(0.5.dp, LocalAppColors.current.border)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (customDraft.isEmpty()) {
                    Text("Enter text...", color = LocalAppColors.current.textDisabled, fontSize = 9.sp)
                }
                BasicTextField(
                    value = customDraft,
                    onValueChange = { newVal ->
                        customDraft = newVal
                        onCustomValueChange(newVal)
                    },
                    textStyle = TextStyle(color = LocalAppColors.current.textPrimary, fontSize = 9.sp),
                    cursorBrush = SolidColor(LocalAppColors.current.blue),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .clickable(enabled = onRemove != null) { onRemove?.invoke() }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                "×",
                color = if (onRemove != null) LocalAppColors.current.textDisabled else Color.Transparent,
                fontSize = 14.sp
            )
        }
    }
}

// ── Section: General ─────────────────────────────────────────────────────────

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
    onFetchManifest: (String) -> Unit
) {
    SectionLabel("FILE TRANSFER")
    ConfigToggleRow("Auto Retry", settings.autoRetryEnabled) {
        onChange(settings.copy(autoRetryEnabled = !settings.autoRetryEnabled))
    }
    if (settings.autoRetryEnabled) {
        val intervalOptions = listOf(4 to "4s", 10 to "10s", 30 to "30s", 60 to "60s", 120 to "120s")
        val intervalLabels = intervalOptions.map { it.second }
        val intervalIdx = intervalOptions.indexOfFirst { it.first == settings.autoRetryIntervalSeconds }
            .takeIf { it >= 0 } ?: 0
        ConfigDropdownRow(
            label    = "Retry interval",
            value    = intervalLabels.getOrElse(intervalIdx) { "4s" },
            options  = intervalLabels,
            onSelect = { onChange(settings.copy(autoRetryIntervalSeconds = intervalOptions[it].first)) }
        )
        val countOptions = listOf(-1 to "Continuous", 1 to "1", 2 to "2", 3 to "3", 5 to "5", 10 to "10")
        val countLabels  = countOptions.map { it.second }
        val countIdx = countOptions.indexOfFirst { it.first == settings.autoRetryMaxCount }
            .takeIf { it >= 0 } ?: 0
        ConfigDropdownRow(
            label    = "Max retries",
            value    = countLabels.getOrElse(countIdx) { "Continuous" },
            options  = countLabels,
            onSelect = { onChange(settings.copy(autoRetryMaxCount = countOptions[it].first)) }
        )
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("SESSION HISTORY")
    val historyOptions = listOf(10 to "10", 25 to "25", 50 to "50", 100 to "100", 200 to "200", -1 to "Unlimited")
    val historyLabels  = historyOptions.map { it.second }
    val historyIdx     = historyOptions.indexOfFirst { it.first == settings.sessionHistoryMax }.takeIf { it >= 0 } ?: 2
    ConfigDropdownRow(
        label    = "Session History Max",
        value    = historyLabels.getOrElse(historyIdx) { "50" },
        options  = historyLabels,
        onSelect = { onChange(settings.copy(sessionHistoryMax = historyOptions[it].first)) }
    )
    Spacer(Modifier.height(6.dp))
    var showClearSessionsDialog by remember { mutableStateOf(false) }
    var sessionsCleared by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .border(1.dp, if (sessionsCleared) LocalAppColors.current.green else LocalAppColors.current.border)
            .clickable { if (!sessionsCleared) showClearSessionsDialog = true }
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(
            if (sessionsCleared) "✓ SESSIONS CLEARED" else "CLEAR SESSION HISTORY",
            color = if (sessionsCleared) LocalAppColors.current.green else LocalAppColors.current.textSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
    if (showClearSessionsDialog) {
        AlertDialog(
            onDismissRequest = { showClearSessionsDialog = false },
            containerColor = LocalAppColors.current.surface,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Clear Session History?", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("WILL BE DELETED:", color = LocalAppColors.current.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· All completed sessions", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Associated image records", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("WILL NOT BE DELETED:", color = LocalAppColors.current.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· Active session", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Image files on device", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Connection profiles", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· App settings", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onClearSessionHistory()
                    showClearSessionsDialog = false
                    sessionsCleared = true
                }) {
                    Text("CLEAR HISTORY", color = LocalAppColors.current.warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearSessionsDialog = false }) {
                    Text("CANCEL", color = LocalAppColors.current.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("PHOTO BACKUP")
    ConfigToggleRow("Save backup to phone", settings.saveBackupToPhone) {
        onChange(settings.copy(saveBackupToPhone = !settings.saveBackupToPhone))
    }
    if (settings.saveBackupToPhone) {
        InfoRow("Location", "Pictures/PhotoFlow")
    }
    ConfigToggleRow("Auto delete backups", settings.autoDeleteBackups) {
        onChange(settings.copy(autoDeleteBackups = !settings.autoDeleteBackups))
    }
    if (settings.autoDeleteBackups) {
        val dayOptions = listOf(1 to "1 day", 7 to "7 days", 14 to "14 days", 30 to "30 days", 60 to "60 days", 90 to "90 days")
        val dayLabels = dayOptions.map { it.second }
        val dayIdx = dayOptions.indexOfFirst { it.first == settings.autoDeleteAfterDays }.takeIf { it >= 0 } ?: 3
        ConfigDropdownRow(
            label    = "Delete after",
            value    = dayLabels.getOrElse(dayIdx) { "30 days" },
            options  = dayLabels,
            onSelect = { onChange(settings.copy(autoDeleteAfterDays = dayOptions[it].first)) }
        )
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("STORAGE")
    val context = LocalContext.current
    val stat = remember { StatFs(Environment.getDataDirectory().path) }
    val totalBytes = stat.totalBytes
    val availableBytes = stat.availableBytes
    val usedFraction = ((totalBytes - availableBytes).toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    StorageRow(
        label      = "Available Phone Storage",
        fraction   = usedFraction,
        usedLabel  = "${(usedFraction * 100).toInt()}%",
        totalLabel = formatStorageBytes(totalBytes),
        isWarning  = usedFraction > 0.85f
    )
    Spacer(Modifier.height(6.dp))
    var showClearDialog by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = Modifier
            .border(1.dp, if (cacheCleared) LocalAppColors.current.green else LocalAppColors.current.border)
            .clickable { if (!cacheCleared) showClearDialog = true }
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(
            if (cacheCleared) "✓ CACHE CLEARED" else "CLEAR APP CACHE",
            color = if (cacheCleared) LocalAppColors.current.green else LocalAppColors.current.textSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = LocalAppColors.current.surface,
            titleContentColor = LocalAppColors.current.textPrimary,
            title = { Text("Clear App Cache?", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("WILL BE DELETED:", color = LocalAppColors.current.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· Image preview cache", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Temporary app files", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("WILL NOT BE DELETED:", color = LocalAppColors.current.green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· Session data and history", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Captured images", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Connection profiles", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· Transfer queue", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                    Text("· App settings", color = LocalAppColors.current.textSecondary, fontSize = 9.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        context.cacheDir?.deleteRecursively()
                        context.externalCacheDir?.deleteRecursively()
                    }
                    showClearDialog = false
                    cacheCleared = true
                }) {
                    Text("CLEAR CACHE", color = LocalAppColors.current.warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("CANCEL", color = LocalAppColors.current.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("LOGS")
    ConfigToggleRow("Enable logging", settings.loggingEnabled) {
        onChange(settings.copy(loggingEnabled = !settings.loggingEnabled))
    }
    InfoRow("Log Location", "PhotoFlow/Pipeline")
    Spacer(Modifier.height(10.dp))
    SectionLabel("IMPORT / EXPORT")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LocalAppColors.current.border)
            .clickable { onExportSettings() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "EXPORT SETTINGS",
                color = LocalAppColors.current.blue,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                "Saves all settings and connection profiles to a JSON file in Downloads",
                color = LocalAppColors.current.textDisabled,
                fontSize = 8.sp
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, LocalAppColors.current.border)
            .clickable { onPickImportFile() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "IMPORT SETTINGS",
                color = LocalAppColors.current.textSecondary,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                "Select a PhotoFlow settings file — overwrites current settings and connections",
                color = LocalAppColors.current.textDisabled,
                fontSize = 8.sp
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("CLOUD API")
    var venueIdDraft by remember(settings.cloudVenueId) { mutableStateOf(settings.cloudVenueId.toString()) }
    ConfigTextField("Venue ID", venueIdDraft, keyboardType = KeyboardType.Number) { v ->
        venueIdDraft = v
        v.toIntOrNull()?.let { onChange(settings.copy(cloudVenueId = it)) }
    }
    var displayNameDraft by remember(settings.cloudDeviceDisplayName) { mutableStateOf(settings.cloudDeviceDisplayName) }
    ConfigTextField("Device Display Name", displayNameDraft) {
        displayNameDraft = it
        onChange(settings.copy(cloudDeviceDisplayName = it))
    }
    InfoRow("Device UUID",
        settings.cloudDeviceUuid.ifBlank { "— (generated on first registration)" })
    InfoRow("Device ID",
        if (settings.cloudDeviceId != 0) settings.cloudDeviceId.toString() else "Not registered")
    Spacer(Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .border(1.dp, LocalAppColors.current.blue)
                .background(LocalAppColors.current.blue.copy(alpha = 0.08f))
                .clickable { onRegisterDevice() }
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text("REGISTER DEVICE", color = LocalAppColors.current.blue,
                fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        if (activeSessionKey != null) {
            Box(
                modifier = Modifier
                    .border(0.5.dp, LocalAppColors.current.border)
                    .clickable { onFetchManifest(activeSessionKey) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("FETCH MANIFEST", color = LocalAppColors.current.textSecondary,
                    fontSize = 8.sp, fontWeight = FontWeight.Bold)
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
        Text(cloudRegistrationState, color = stateColor, fontSize = 8.sp,
            modifier = Modifier.padding(top = 2.dp))
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("APP INFO")
    InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    InfoRow("Built",   BuildConfig.BUILD_TIME)
    InfoRow("Min SDK",    "API 26")
    InfoRow("Target SDK", "API 34")
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
    Text("PhotoFlow Mobile", color = LocalAppColors.current.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    Text(
        "Professional field operations — barcode-based image capture and background FTP transfer.",
        color = LocalAppColors.current.textSecondary,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = LocalAppColors.current.border, thickness = 0.5.dp)
    Spacer(Modifier.height(6.dp))
    InfoRow("Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    InfoRow("Built",   BuildConfig.BUILD_TIME)
    Spacer(Modifier.height(8.dp))
    Text("© 2026 PhotoFlow", color = LocalAppColors.current.textDisabled, fontSize = 9.sp)
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
    onValueChange: (String) -> Unit
) {
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
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth()
            )
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
