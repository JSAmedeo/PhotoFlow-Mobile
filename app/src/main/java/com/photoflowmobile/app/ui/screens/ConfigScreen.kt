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
import android.os.Environment
import android.os.StatFs
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.photoflowmobile.app.data.model.*
import com.photoflowmobile.app.ui.theme.*
import com.photoflowmobile.app.viewmodel.ConfigViewModel
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

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(AppBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        ConfigSidebar(
            selected = selectedSection,
            onSelect = { selectedSection = it },
            onNavigateBack = onNavigateBack
        )
        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(AppBorder))
        ConfigContent(
            section = selectedSection,
            settings = settings,
            onSettingsChange = { viewModel.save(it) },
            connectionProfiles = connectionProfiles,
            onUpsertProfile = viewModel::upsertProfile,
            onDeleteProfile = viewModel::deleteProfile,
            onSetActiveProfile = viewModel::setActiveProfile,
            onTestConnection = viewModel::testConnection,
            modifier = Modifier.weight(1f).fillMaxHeight()
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
            .background(AppSurface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "CONFIG",
                color = TextPrimary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
        }
        HorizontalDivider(color = AppBorder, thickness = 1.dp)
        Spacer(Modifier.height(4.dp))
        ConfigSection.entries.forEach { section ->
            ConfigNavItem(
                label = section.label,
                selected = selected == section,
                onClick = { onSelect(section) }
            )
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = AppBorder, thickness = 1.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNavigateBack() }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text(
                "← MAIN SCREEN",
                color = TextSecondary,
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
            .background(if (selected) Blue.copy(alpha = 0.08f) else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(30.dp)
                .background(if (selected) Blue else Color.Transparent)
        )
        Text(
            label,
            color = if (selected) Blue else TextSecondary,
            fontSize = 9.sp,
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppSurface)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                section.label.uppercase(),
                color = TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
        HorizontalDivider(color = AppBorder, thickness = 1.dp)
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
                ConfigSection.GENERAL      -> GeneralSection(settings, onSettingsChange)
                ConfigSection.ABOUT        -> AboutSection()
            }
        }
    }
}

// ── Section: Device Mode ──────────────────────────────────────────────────────

@Composable
private fun DeviceModeSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ModeCard(
            title = "Tethered DSLR",
            subtitle = "USB / LAN / WI-FI",
            selected = settings.deviceMode == DeviceMode.TETHERED_DSLR,
            onClick = { onChange(settings.copy(deviceMode = DeviceMode.TETHERED_DSLR)) },
            modifier = Modifier.weight(1f)
        )
        ModeCard(
            title = "Native Camera",
            subtitle = "BUILT-IN SENSOR",
            selected = settings.deviceMode == DeviceMode.NATIVE_CAMERA,
            onClick = { onChange(settings.copy(deviceMode = DeviceMode.NATIVE_CAMERA)) },
            modifier = Modifier.weight(1f)
        )
    }
    Spacer(Modifier.height(6.dp))
    SectionLabel("ADVANCED")
    ConfigToggleRow("Auto-reconnect", settings.autoReconnect) {
        onChange(settings.copy(autoReconnect = !settings.autoReconnect))
    }
    ConfigDropdownRow(
        label = "Preview quality",
        value = settings.previewQuality.label,
        options = PreviewQuality.entries.map { it.label },
        onSelect = { onChange(settings.copy(previewQuality = PreviewQuality.entries[it])) }
    )
    ConfigDropdownRow(
        label = "Session timeout",
        value = settings.sessionTimeout.label,
        options = SessionTimeout.entries.map { it.label },
        onSelect = { onChange(settings.copy(sessionTimeout = SessionTimeout.entries[it])) }
    )
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
            .border(1.dp, if (selected) Blue else AppBorder)
            .background(if (selected) Blue.copy(alpha = 0.06f) else AppSurface)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            if (selected) "⊙" else "○",
            color = if (selected) Blue else TextDisabled,
            fontSize = 10.sp
        )
        Column {
            Text(
                title,
                color = if (selected) Blue else TextSecondary,
                fontSize = 9.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(subtitle, color = TextDisabled, fontSize = 7.sp, letterSpacing = 0.3.sp)
        }
    }
}

// ── Section: Connections ──────────────────────────────────────────────────────

private data class ConnFieldLabels(
    val host: String, val port: String, val username: String,
    val password: String, val remotePath: String
)

private fun connectionFieldLabels(type: ConnectionType) = when (type) {
    ConnectionType.FTP      -> ConnFieldLabels("Host", "Port", "Username", "Password", "Remote Path")
    ConnectionType.API_HOOK -> ConnFieldLabels("Base URL", "", "API Key", "Bearer Token", "Endpoint Path")
    ConnectionType.CLOUD    -> ConnFieldLabels("Region / Endpoint", "", "Access Key", "Secret Key", "Bucket / Path")
}

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
                .border(0.5.dp, AppBorder)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                "No connections configured.",
                color = TextDisabled,
                fontSize = 8.sp
            )
        }
    }

    profiles.forEach { profile ->
        val isEditingThis = editingId == profile.id
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, if (isEditingThis) Blue.copy(alpha = 0.5f) else AppBorder)
                .background(if (profile.isActive) AppSurfaceRaised else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .border(0.5.dp, AppBorder)
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    profile.connectionType.badge,
                    color = TextDisabled,
                    fontSize = 6.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Text(
                profile.name.ifBlank { "(unnamed)" },
                color = if (profile.isActive) TextPrimary else TextSecondary,
                fontSize = 9.sp,
                fontWeight = if (profile.isActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.weight(1f)
            )
            if (profile.isActive) {
                Text("● ACTIVE", color = Green, fontSize = 7.sp, fontWeight = FontWeight.Bold)
            } else {
                Box(
                    modifier = Modifier
                        .clickable { onSetActive(profile) }
                        .border(0.5.dp, AppBorder)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        "SET ACTIVE",
                        color = TextDisabled,
                        fontSize = 7.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clickable { editingId = if (isEditingThis) null else profile.id }
                    .border(0.5.dp, if (isEditingThis) Blue else AppBorder)
                    .background(if (isEditingThis) Blue.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    if (isEditingThis) "CLOSE" else "EDIT",
                    color = if (isEditingThis) Blue else TextSecondary,
                    fontSize = 7.sp,
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
                Text("×", color = TextDisabled, fontSize = 14.sp)
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
                .border(0.5.dp, AppBorder)
                .clickable { editingId = -1L }
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                "+ ADD CONNECTION",
                color = Blue,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }
    } else {
        Text(
            "NEW CONNECTION",
            color = Blue.copy(alpha = 0.6f),
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
    val labels = connectionFieldLabels(draft.connectionType)
    var typeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, AppBorder)
            .background(AppSurface)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConfigTextField("Name", draft.name) { onDraftChange(draft.copy(name = it)) }

        Row(
            modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Type",
                color = TextSecondary,
                fontSize = 8.sp,
                modifier = Modifier.width(84.dp)
            )
            Box {
                Row(
                    modifier = Modifier
                        .clickable { typeExpanded = true }
                        .border(0.5.dp, AppBorder)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(draft.connectionType.label, color = TextPrimary, fontSize = 8.sp)
                    Text("▾", color = TextSecondary, fontSize = 8.sp)
                }
                DropdownMenu(
                    expanded = typeExpanded,
                    onDismissRequest = { typeExpanded = false },
                    modifier = Modifier.background(AppSurface)
                ) {
                    ConnectionType.entries.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.label, color = TextPrimary, fontSize = 9.sp) },
                            onClick = { onDraftChange(draft.copy(connectionType = type)); typeExpanded = false }
                        )
                    }
                }
            }
        }

        ConfigTextField(labels.host, draft.host) { onDraftChange(draft.copy(host = it)) }
        if (draft.connectionType == ConnectionType.FTP) {
            ConfigTextField(labels.port, draft.port.toString(), keyboardType = KeyboardType.Number) {
                onDraftChange(draft.copy(port = it.toIntOrNull() ?: draft.port))
            }
        }
        ConfigTextField(labels.username, draft.username) { onDraftChange(draft.copy(username = it)) }
        ConfigTextField(labels.password, draft.password, isPassword = true) { onDraftChange(draft.copy(password = it)) }
        ConfigTextField(labels.remotePath, draft.remotePath) { onDraftChange(draft.copy(remotePath = it)) }

        Spacer(Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (draft.connectionType == ConnectionType.FTP) {
                val (testLabel, testColor, testBg) = when (testState) {
                    ConnTestState.IDLE    -> Triple("TEST", Blue, Blue.copy(alpha = 0.08f))
                    ConnTestState.TESTING -> Triple("TESTING...", TextSecondary, Color.Transparent)
                    ConnTestState.SUCCESS -> Triple("● OK", Green, Green.copy(alpha = 0.08f))
                    ConnTestState.FAILED  -> Triple("⚠ FAILED", Warning, Warning.copy(alpha = 0.08f))
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
                    Text(
                        testError,
                        color = Warning,
                        fontSize = 7.sp,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Box(
                modifier = Modifier
                    .border(0.5.dp, AppBorder)
                    .clickable { onCancel() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("CANCEL", color = TextSecondary, fontSize = 8.sp, fontWeight = FontWeight.Bold)
            }
            val canSave = draft.name.isNotBlank()
            Box(
                modifier = Modifier
                    .border(1.dp, if (canSave) Blue else AppBorder)
                    .background(if (canSave) Blue.copy(alpha = 0.08f) else Color.Transparent)
                    .clickable(enabled = canSave) { onSave() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "SAVE PROFILE",
                    color = if (canSave) Blue else TextDisabled,
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
            .border(0.5.dp, AppBorder)
            .clickable { onChange(settings.copy(namingFields = settings.namingFields + NamingField(FieldType.CUSTOM))) }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text("+ ADD FIELD", color = Blue, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Separator", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        listOf("_", "-", ".").forEach { sep ->
            val sel = sep == settings.namingSeparator
            Box(
                modifier = Modifier
                    .clickable { onChange(settings.copy(namingSeparator = sep)) }
                    .border(1.dp, if (sel) Blue else AppBorder)
                    .background(if (sel) Blue.copy(alpha = 0.1f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(sep, color = if (sel) Blue else TextSecondary, fontSize = 9.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Extension", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        listOf("JPG", "DNG", "RAW").forEach { ext ->
            val sel = ext == settings.namingExtension
            Box(
                modifier = Modifier
                    .clickable { onChange(settings.copy(namingExtension = ext)) }
                    .border(1.dp, if (sel) Blue else AppBorder)
                    .background(if (sel) Blue.copy(alpha = 0.1f) else Color.Transparent)
                    .padding(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text(ext, color = if (sel) Blue else TextSecondary, fontSize = 8.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("PREVIEW")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, AppBorder)
            .background(AppBackground)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "${buildNamingPreview(settings.namingFields, settings.namingSeparator)}.${settings.namingExtension}",
            color = TextPrimary,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, AppBorder)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "FIELD $number",
            color = TextDisabled,
            fontSize = 7.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.width(44.dp)
        )
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .border(0.5.dp, Blue.copy(alpha = 0.5f))
                    .background(Blue.copy(alpha = 0.06f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(field.type.label, color = Blue, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text("▾", color = Blue.copy(alpha = 0.6f), fontSize = 8.sp)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AppSurface)
            ) {
                FieldType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label, color = TextPrimary, fontSize = 9.sp) },
                        onClick = { onTypeChange(type); expanded = false }
                    )
                }
            }
        }
        if (field.type == FieldType.CUSTOM) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(AppBackground)
                    .border(0.5.dp, AppBorder)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (field.customValue.isEmpty()) {
                    Text("Enter text...", color = TextDisabled, fontSize = 9.sp)
                }
                BasicTextField(
                    value = field.customValue,
                    onValueChange = onCustomValueChange,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 9.sp),
                    cursorBrush = SolidColor(Blue),
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
                color = if (onRemove != null) TextDisabled else Color.Transparent,
                fontSize = 14.sp
            )
        }
    }
}

// ── Section: General ─────────────────────────────────────────────────────────

@Composable
private fun GeneralSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
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
            .border(1.dp, if (cacheCleared) Green else AppBorder)
            .clickable { if (!cacheCleared) showClearDialog = true }
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(
            if (cacheCleared) "✓ CACHE CLEARED" else "CLEAR APP CACHE",
            color = if (cacheCleared) Green else TextSecondary,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = AppSurface,
            titleContentColor = TextPrimary,
            title = { Text("Clear App Cache?", fontSize = 13.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("WILL BE DELETED:", color = Warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· Image preview cache", color = TextSecondary, fontSize = 9.sp)
                    Text("· Temporary app files", color = TextSecondary, fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    Text("WILL NOT BE DELETED:", color = Green, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    Text("· Session data and history", color = TextSecondary, fontSize = 9.sp)
                    Text("· Captured images", color = TextSecondary, fontSize = 9.sp)
                    Text("· Connection profiles", color = TextSecondary, fontSize = 9.sp)
                    Text("· Transfer queue", color = TextSecondary, fontSize = 9.sp)
                    Text("· App settings", color = TextSecondary, fontSize = 9.sp)
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
                    Text("CLEAR CACHE", color = Warning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("CANCEL", color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
    Spacer(Modifier.height(10.dp))
    SectionLabel("DIAGNOSTICS")
    ConfigToggleRow("Verbose logging",    settings.verboseLogging)    { onChange(settings.copy(verboseLogging = !settings.verboseLogging)) }
    ConfigToggleRow("Save crash reports", settings.saveCrashReports)  { onChange(settings.copy(saveCrashReports = !settings.saveCrashReports)) }
    Spacer(Modifier.height(10.dp))
    SectionLabel("APP INFO")
    InfoRow("Version",    "1.0.0-dev")
    InfoRow("Build",      "2026-04-20")
    InfoRow("Min SDK",    "API 26")
    InfoRow("Target SDK", "API 34")
    InfoRow("Build type", "Debug")
}

private fun formatStorageBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.0f MB".format(bytes / 1_048_576.0)
    else                     -> "$bytes B"
}

@Composable
private fun StorageRow(label: String, fraction: Float, usedLabel: String, totalLabel: String, isWarning: Boolean) {
    val barColor = if (isWarning) Warning else Blue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, AppBorder)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(label, color = TextSecondary, fontSize = 8.sp, modifier = Modifier.width(52.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.weight(1f).height(5.dp),
            color = barColor,
            trackColor = AppBorder
        )
        Text(usedLabel, color = barColor, fontSize = 8.sp, modifier = Modifier.width(28.dp))
        Text(totalLabel, color = TextDisabled, fontSize = 8.sp)
    }
}

// ── Section: About ────────────────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    Text("PhotoFlow Mobile", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp)
    Text(
        "Professional field operations — barcode-based image capture and background FTP transfer.",
        color = TextSecondary,
        fontSize = 9.sp,
        lineHeight = 14.sp
    )
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = AppBorder, thickness = 0.5.dp)
    Spacer(Modifier.height(6.dp))
    InfoRow("Version",  "1.0.0-dev")
    InfoRow("Build",    "001")
    InfoRow("Released", "2026-04-20")
    Spacer(Modifier.height(6.dp))
    HorizontalDivider(color = AppBorder, thickness = 0.5.dp)
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Support", color = TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text("john.amedeo@gmail.com", color = TextPrimary, fontSize = 8.sp)
    }
    Spacer(Modifier.height(8.dp))
    Text("© 2026 PhotoFlow", color = TextDisabled, fontSize = 7.sp)
}

// ── Shared components ─────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text, color = TextDisabled, fontSize = 7.sp, letterSpacing = 0.8.sp)
        HorizontalDivider(color = AppBorder.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ConfigTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 8.sp, modifier = Modifier.width(84.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(color = TextPrimary, fontSize = 9.sp),
            cursorBrush = SolidColor(Blue),
            singleLine = true,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ConfigToggleRow(label: String, value: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clickable { onToggle() }
                .border(1.dp, if (value) Blue else AppBorder)
                .background(if (value) Blue.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(if (value) "ON" else "OFF", color = if (value) Blue else TextDisabled, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ConfigDropdownRow(label: String, value: String, options: List<String>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 9.sp, modifier = Modifier.weight(1f))
        Box {
            Row(
                modifier = Modifier
                    .clickable { expanded = true }
                    .border(0.5.dp, AppBorder)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(value, color = TextPrimary, fontSize = 8.sp)
                Text("▾", color = TextSecondary, fontSize = 8.sp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(AppSurface)) {
                options.forEachIndexed { idx, option ->
                    DropdownMenuItem(
                        text = { Text(option, color = TextPrimary, fontSize = 9.sp) },
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
        modifier = Modifier.fillMaxWidth().border(0.5.dp, AppBorder).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = 8.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 8.sp)
    }
}
