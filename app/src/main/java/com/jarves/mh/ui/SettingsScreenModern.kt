package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.data.AppLocale
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.DEEPSEEK_HARNESS_PROVIDERS
import com.jarves.mh.model.DSH_PROTOCOL_PROVIDERS
import com.jarves.mh.model.DevStack
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.KeepAliveTracker
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.PocketGreen
import com.jarves.mh.ui.theme.PocketOrange
import kotlinx.coroutines.launch

private enum class SettingsSection { APPEARANCE, TOOLS, PERMISSIONS, BACKGROUND, RUNTIME, UPDATE_CHANNEL }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onPing: () -> Unit,
    onClearTerminal: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onInstallDevStack: (DevStack) -> Unit = {},
    onRemoveDevStack: (DevStack) -> Unit = {},
    onInstallAgent: (AgentKind) -> Unit = {},
    onCheckAgentUpdates: () -> Unit = {},
    onUpdateAgent: (AgentKind) -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onRefreshAntigravityModels: () -> Unit = {},
    onSetAntigravityModel: (String) -> Unit = {},
    onSetAntigravityEffort: (String) -> Unit = {},
    initialDebugUpdateManifestUrl: String = "",
    onSetDebugUpdateManifestUrl: (String) -> Unit = {},
    onClearDebugUpdateManifestUrl: () -> Unit = {},
    onSetToolPermissionGlobal: (com.jarves.mh.tools.ToolPermissionLevel) -> Unit = {},
    onSetToolPermissionOverride: (String, com.jarves.mh.tools.ToolPermissionLevel?) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var terminalCleared by remember { mutableStateOf(false) }
    var showReliabilityHelp by rememberSaveable { mutableStateOf(false) }
    var stackPendingRemoval by remember { mutableStateOf<DevStack?>(null) }
    val powerManager = context.getSystemService(PowerManager::class.java)
    var backgroundExecutionEnabled by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val keepAlivePrefs = remember { AppPreferences(context) }
    var keepAliveEnabled by remember { mutableStateOf(keepAlivePrefs.keepAliveEnabled) }
    val backgroundSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        backgroundExecutionEnabled = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBackgroundSettings() {
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val detailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { backgroundSettingsLauncher.launch(requestIntent) }
            .onFailure { backgroundSettingsLauncher.launch(detailsIntent) }
    }

    stackPendingRemoval?.let { stack ->
        AlertDialog(
            onDismissRequest = { stackPendingRemoval = null },
            title = { Text(stringResource(R.string.settings_remove_toolchain_title, stack.label)) },
            text = {
                Text(stringResource(R.string.settings_remove_toolchain_body))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        stackPendingRemoval = null
                        onRemoveDevStack(stack)
                    },
                ) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { stackPendingRemoval = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    fun toggle(section: SettingsSection) {
        expanded = if (expanded == section) null else section
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 8.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(9.dp),
                                )
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                                    shape = RoundedCornerShape(9.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(stringResource(R.string.settings_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
                            Text(stringResource(R.string.settings_subtitle), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            item {
                SettingsAccordion(
                    title = stringResource(R.string.settings_appearance),
                    subtitle = when (state.themeMode) { AppThemeMode.DARK -> stringResource(R.string.settings_theme_dark); AppThemeMode.LIGHT -> stringResource(R.string.settings_theme_light); AppThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system) },
                    icon = Icons.Default.Tune,
                    expanded = expanded == SettingsSection.APPEARANCE,
                    onClick = { toggle(SettingsSection.APPEARANCE) },
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModernThemeChoice(stringResource(R.string.settings_chip_dark), Icons.Default.DarkMode, state.themeMode == AppThemeMode.DARK, { onSetThemeMode(AppThemeMode.DARK) }, Modifier.weight(1f))
                        ModernThemeChoice(stringResource(R.string.settings_chip_light), Icons.Default.LightMode, state.themeMode == AppThemeMode.LIGHT, { onSetThemeMode(AppThemeMode.LIGHT) }, Modifier.weight(1f))
                        ModernThemeChoice(stringResource(R.string.settings_chip_system), Icons.Default.PhoneAndroid, state.themeMode == AppThemeMode.SYSTEM, { onSetThemeMode(AppThemeMode.SYSTEM) }, Modifier.weight(1f))
                    }
                    if (Build.VERSION.SDK_INT >= 33) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(R.string.settings_language_title),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        val langTag = AppLocale.currentTag(context)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModernThemeChoice(stringResource(R.string.settings_language_system), Icons.Default.Language, langTag == AppLocale.TAG_SYSTEM, { AppLocale.set(context, AppLocale.TAG_SYSTEM) }, Modifier.weight(1f))
                            ModernThemeChoice(stringResource(R.string.settings_language_english), Icons.Default.Language, langTag == AppLocale.TAG_ENGLISH, { AppLocale.set(context, AppLocale.TAG_ENGLISH) }, Modifier.weight(1f))
                            ModernThemeChoice(stringResource(R.string.settings_language_romanian), Icons.Default.Language, langTag == AppLocale.TAG_ROMANIAN, { AppLocale.set(context, AppLocale.TAG_ROMANIAN) }, Modifier.weight(1f))
                        }
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = stringResource(R.string.settings_background_title),
                    subtitle = if (backgroundExecutionEnabled) stringResource(R.string.settings_bg_active_sub) else stringResource(R.string.settings_bg_inactive_sub),
                    icon = Icons.Default.Security,
                    expanded = expanded == SettingsSection.BACKGROUND,
                    onClick = { toggle(SettingsSection.BACKGROUND) },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (backgroundExecutionEnabled) stringResource(R.string.settings_active) else stringResource(R.string.settings_inactive),
                            fontWeight = FontWeight.Bold,
                            color = if (backgroundExecutionEnabled) PocketGreen else MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.settings_bg_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = ::openBackgroundSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (backgroundExecutionEnabled) stringResource(R.string.settings_review_android_setting) else stringResource(R.string.settings_enable_android_setting))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_keepalive_title), fontWeight = FontWeight.SemiBold)
                            Text(
                                stringResource(R.string.settings_keepalive_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = keepAliveEnabled,
                            onCheckedChange = { value ->
                                keepAliveEnabled = value
                                keepAlivePrefs.keepAliveEnabled = value
                                KeepAliveTracker.setEnabled(context, value)
                            },
                        )
                    }
                }
            }

            item {
                val installedCount = state.installedDevStacks.size
                SettingsAccordion(
                    title = stringResource(R.string.settings_devtools_title),
                    subtitle = stringResource(R.string.settings_devstack_subtitle, installedCount),
                    icon = Icons.Default.Code,
                    expanded = expanded == SettingsSection.TOOLS,
                    onClick = { toggle(SettingsSection.TOOLS) },
                ) {
                    Text(stringResource(R.string.settings_devstack_desc), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    DevStack.entries.forEachIndexed { index, stack ->
                        val installed = stack in state.installedDevStacks
                        val installing = state.devStackInstalling == stack
                        val removing = installing && state.devStackRemoving
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stack.label, fontWeight = FontWeight.SemiBold)
                                Text(stack.installsSummary, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            when {
                                removing -> Text(stringResource(R.string.settings_removing), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                installing -> Text("${(state.devStackProgress * 100).toInt()}%", color = PocketOrange, fontWeight = FontWeight.Bold)
                                installed && stack == DevStack.WEB -> Text(stringResource(R.string.settings_included), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                installed -> TextButton(
                                    onClick = { stackPendingRemoval = stack },
                                    enabled = state.devStackInstalling == null,
                                ) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
                                else -> OutlinedButton(onClick = { onInstallDevStack(stack) }, enabled = state.devStackInstalling == null) { Text(stringResource(R.string.settings_add)) }
                            }
                        }
                        if (installing) {
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { state.devStackProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(7.dp),
                                color = PocketOrange,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.height(9.dp))
                            state.devStackBytes?.let { (downloaded, total) ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                ) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(
                                                "${formatTransferMb(downloaded)} of ${formatTransferMb(total)}",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            state.devStackBytesPerSecond?.takeIf { it > 0L }?.let { speed ->
                                                Text(
                                                    "${formatTransferSpeed(speed)} · ${formatTransferEta(downloaded, total, speed)} left",
                                                    fontSize = 11.sp,
                                                    color = PocketOrange,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            }
                                        }
                                        Text(
                                            state.devStackMessage ?: stringResource(R.string.settings_downloading),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            } ?: Text(
                                state.devStackMessage ?: stringResource(R.string.settings_processing),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (index != DevStack.entries.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = stringResource(R.string.settings_permissions_title),
                    subtitle = when (state.toolPermissionGlobal) {
                        com.jarves.mh.tools.ToolPermissionLevel.ALLOW -> stringResource(R.string.settings_perm_global_allow)
                        com.jarves.mh.tools.ToolPermissionLevel.ASK -> stringResource(R.string.settings_perm_global_ask)
                        com.jarves.mh.tools.ToolPermissionLevel.FORBID -> stringResource(R.string.settings_perm_global_forbid)
                    },
                    icon = Icons.Default.Security,
                    expanded = expanded == SettingsSection.PERMISSIONS,
                    onClick = { toggle(SettingsSection.PERMISSIONS) },
                ) {
                    Text(
                        stringResource(R.string.settings_permissions_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(stringResource(R.string.settings_global_default), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PermissionLevelChoice(
                            label = stringResource(R.string.settings_allow),
                            selected = state.toolPermissionGlobal == com.jarves.mh.tools.ToolPermissionLevel.ALLOW,
                            onClick = { onSetToolPermissionGlobal(com.jarves.mh.tools.ToolPermissionLevel.ALLOW) },
                            modifier = Modifier.weight(1f),
                        )
                        PermissionLevelChoice(
                            label = stringResource(R.string.settings_ask),
                            selected = state.toolPermissionGlobal == com.jarves.mh.tools.ToolPermissionLevel.ASK,
                            onClick = { onSetToolPermissionGlobal(com.jarves.mh.tools.ToolPermissionLevel.ASK) },
                            modifier = Modifier.weight(1f),
                        )
                        PermissionLevelChoice(
                            label = stringResource(R.string.settings_forbid),
                            selected = state.toolPermissionGlobal == com.jarves.mh.tools.ToolPermissionLevel.FORBID,
                            onClick = { onSetToolPermissionGlobal(com.jarves.mh.tools.ToolPermissionLevel.FORBID) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.settings_per_tool_overrides), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    com.jarves.mh.tools.ToolPermissionStore.knownTools.forEach { tool ->
                        val effective = state.toolPermissionOverrides[tool] ?: state.toolPermissionGlobal
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(tool, fontWeight = FontWeight.Medium, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            PermissionLevelChoice(
                                label = stringResource(R.string.settings_allow),
                                selected = effective == com.jarves.mh.tools.ToolPermissionLevel.ALLOW &&
                                    state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.ALLOW,
                                onClick = {
                                    onSetToolPermissionOverride(
                                        tool,
                                        if (state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.ALLOW) {
                                            null
                                        } else {
                                            com.jarves.mh.tools.ToolPermissionLevel.ALLOW
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            PermissionLevelChoice(
                                label = stringResource(R.string.settings_ask),
                                selected = effective == com.jarves.mh.tools.ToolPermissionLevel.ASK &&
                                    state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.ASK,
                                onClick = {
                                    onSetToolPermissionOverride(
                                        tool,
                                        if (state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.ASK) {
                                            null
                                        } else {
                                            com.jarves.mh.tools.ToolPermissionLevel.ASK
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(4.dp))
                            PermissionLevelChoice(
                                label = stringResource(R.string.settings_forbid),
                                selected = effective == com.jarves.mh.tools.ToolPermissionLevel.FORBID &&
                                    state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.FORBID,
                                onClick = {
                                    onSetToolPermissionOverride(
                                        tool,
                                        if (state.toolPermissionOverrides[tool] == com.jarves.mh.tools.ToolPermissionLevel.FORBID) {
                                            null
                                        } else {
                                            com.jarves.mh.tools.ToolPermissionLevel.FORBID
                                        },
                                    )
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            item {
                SettingsAccordion(
                    title = stringResource(R.string.settings_linux_runtime),
                    subtitle = "Ubuntu 20.04 PRoot · ARM64",
                    icon = Icons.Default.Terminal,
                    expanded = expanded == SettingsSection.RUNTIME,
                    onClick = { toggle(SettingsSection.RUNTIME) },
                ) {
                    RuntimeInfoRow(stringResource(R.string.settings_architecture), "ARM64 (aarch64)")
                    RuntimeInfoRow(stringResource(R.string.settings_environment), "Ubuntu 20.04 PRoot")
                    RuntimeInfoRow(
                        stringResource(R.string.settings_active_agent),
                        state.agentKind.title + if (state.installedAgentVersions.containsKey(state.agentKind)) "" else stringResource(R.string.settings_not_installed_suffix),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text(
                        stringResource(R.string.settings_installed_agents),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.installedAgentVersions.isEmpty()) {
                        RuntimeInfoRow(stringResource(R.string.settings_status), stringResource(R.string.settings_no_verified_install))
                    } else {
                        AgentKind.entries.forEach { agent ->
                            state.installedAgentVersions[agent]?.let { version ->
                                RuntimeInfoRow(agent.title, "v$version")
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { onClearTerminal(); terminalCleared = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(if (terminalCleared) stringResource(R.string.settings_terminal_cleared) else stringResource(R.string.settings_clear_terminal))
                    }
                    OutlinedButton(
                        onClick = { showReliabilityHelp = !showReliabilityHelp },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.settings_advanced_reliability))
                    }
                    AnimatedVisibility(showReliabilityHelp) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                stringResource(R.string.settings_reliability_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
                                        .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(stringResource(R.string.settings_open_dev_options)) }
                        }
                    }
                }
            }

            if (BuildConfig.DEBUG) {
                item {
                    DebugUpdateChannelSection(
                        initialUrl = initialDebugUpdateManifestUrl,
                        onSave = onSetDebugUpdateManifestUrl,
                        onClear = onClearDebugUpdateManifestUrl,
                    )
                }
            }

            item {
                Surface(
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, Modifier.size(20.dp), tint = PocketOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Mobile Harness", fontWeight = FontWeight.SemiBold)
                            Text(stringResource(R.string.settings_tagline), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.PRIVACY_POLICY_URL)),
                                )
                            }
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.PrivacyTip,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_privacy_policy), fontWeight = FontWeight.Medium)
                        Text(
                            stringResource(R.string.settings_privacy_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.settings_open_privacy),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }
}

private fun formatTransferMb(bytes: Long): String = "%.1f MB".format(bytes.coerceAtLeast(0L) / 1_048_576.0)

private fun formatTransferSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_048_576L -> "%.1f MB/s".format(bytesPerSecond / 1_048_576.0)
    else -> "%.0f KB/s".format(bytesPerSecond / 1_024.0)
}

private fun formatTransferEta(downloaded: Long, total: Long, bytesPerSecond: Long): String {
    val seconds = ((total - downloaded).coerceAtLeast(0L) / bytesPerSecond.coerceAtLeast(1L)).coerceAtLeast(1L)
    return if (seconds >= 60L) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

@Composable
private fun SettingsAccordion(
    title: String,
    subtitle: String,
    icon: ImageVector,
    expanded: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
                }
            }
        }
    }
}

@Composable
private fun AntigravityConnectionSettings(
    state: AppUiState,
    code: String,
    onCode: (String) -> Unit,
    onStartLogin: () -> Unit,
    onSubmitCode: () -> Unit,
    onLogout: () -> Unit,
    onRefreshModels: () -> Unit,
    onSetModel: (String) -> Unit,
    onSetEffort: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val auth = state.antigravityAuth
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.settings_antigravity_official), fontWeight = FontWeight.SemiBold)
            Text(
                auth.message ?: if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                    auth.accountEmail?.let { stringResource(R.string.settings_connected_as, it) } ?: stringResource(R.string.settings_google_connected)
                } else stringResource(R.string.settings_signin_browser),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (auth.status) {
                AntigravityAuthStatus.SIGNED_IN -> OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_logout_antigravity))
                }
                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AntigravityAuthStatus.AWAITING_CODE -> {
                    auth.authorizationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.settings_copy_signin_url)) }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCode,
                        label = { Text(stringResource(R.string.settings_auth_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = onSubmitCode, enabled = code.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_complete_signin))
                    }
                }
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> Button(
                    onClick = onStartLogin,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (auth.status == AntigravityAuthStatus.ERROR) stringResource(R.string.settings_reconnect_google) else stringResource(R.string.settings_signin_google)) }
            }
        }
    }

    if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
        Text(stringResource(R.string.settings_model), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = state.antigravityModel,
            onValueChange = onSetModel,
            label = { Text(stringResource(R.string.settings_antigravity_model_id)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = onRefreshModels,
            enabled = !state.antigravityModelsLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.settings_refresh_models))
        }
        state.antigravityModels.forEach { model ->
            Row(
                Modifier.fillMaxWidth().clickable { onSetModel(model) }.padding(vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(model, Modifier.weight(1f), fontSize = 12.sp)
                SelectionDot(state.antigravityModel == model)
            }
        }
        Text(stringResource(R.string.settings_reasoning_effort), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("low", "medium", "high").forEach { effort ->
                OutlinedButton(onClick = { onSetEffort(effort) }, modifier = Modifier.weight(1f)) {
                    Text(
                        when (effort) {
                            "low" -> stringResource(R.string.settings_effort_low)
                            "medium" -> stringResource(R.string.settings_effort_medium)
                            else -> stringResource(R.string.settings_effort_high)
                        },
                    )
                }
            }
        }
    }

    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(12.dp)) {
        Text(
            stringResource(R.string.settings_antigravity_autotool_desc),
            Modifier.fillMaxWidth().padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun ConnectionSettings(
    state: AppUiState,
    selectedKind: ProviderKind,
    baseUrl: String,
    model: String,
    dshApi: String,
    apiKey: String,
    models: List<DiscoveredModel>,
    isDiscovering: Boolean,
    isValidating: Boolean,
    status: String?,
    statusOk: Boolean,
    savedKeys: List<ApiKeyInfo>,
    newKeyName: String,
    newApiKey: String,
    newKeyVisible: Boolean,
    onPing: () -> Unit,
    onProvider: (ProviderKind) -> Unit,
    onBaseUrl: (String) -> Unit,
    onModel: (String) -> Unit,
    onDshApi: (String) -> Unit,
    onNewKeyName: (String) -> Unit,
    onNewApiKey: (String) -> Unit,
    onToggleNewKey: () -> Unit,
    onAddKey: () -> Unit,
    onActivateKey: (String) -> Unit,
    onRemoveKey: (String) -> Unit,
    onModels: () -> Unit,
    onValidate: () -> Unit,
) {
    val visibleKinds = remember(state.agentKind) { providersForAgent(state.agentKind) }
    var providerExpanded by rememberSaveable { mutableStateOf(false) }
    var addKeyExpanded by rememberSaveable(savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(
                when (state.apiPingStatus) {
                    ApiPingStatus.OK -> Color(0xFF58C9A3)
                    ApiPingStatus.FAILED -> MaterialTheme.colorScheme.error
                    ApiPingStatus.PINGING -> PocketOrange
                    ApiPingStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
                }, CircleShape,
            ))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_active_connection), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(state.provider.model.ifBlank { stringResource(R.string.settings_not_configured) }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                state.activeApiKeyName?.let { name ->
                    Text(stringResource(R.string.settings_key_label, name), fontSize = 11.sp, color = PocketOrange, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.apiPingMessage?.let {
                    Text(it, fontSize = 11.sp, color = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(onClick = onPing, enabled = state.apiPingStatus != ApiPingStatus.PINGING, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text(if (state.apiPingStatus == ApiPingStatus.PINGING) stringResource(R.string.settings_testing) else stringResource(R.string.settings_test))
            }
        }
    }

    Text(stringResource(R.string.settings_provider), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { providerExpanded = !providerExpanded },
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, if (providerExpanded) PocketOrange else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(selectedKind.title, fontWeight = FontWeight.SemiBold)
                Text(selectedKind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Icon(if (providerExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, stringResource(R.string.settings_choose_provider))
        }
    }
    AnimatedVisibility(providerExpanded) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)) {
            Column {
                visibleKinds.forEachIndexed { index, kind ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onProvider(kind)
                        providerExpanded = false
                    }.padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(kind.title, fontWeight = FontWeight.Medium)
                        Text(kind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    SelectionDot(selectedKind == kind)
                }
                if (index != visibleKinds.lastIndex) HorizontalDivider(Modifier.padding(start = 13.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
            }
        }
    }

    if (selectedKind.fixedBaseUrl) {
        Text(
            selectedKind.defaultBaseUrl,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        OutlinedTextField(baseUrl, onBaseUrl, label = { Text(stringResource(R.string.settings_base_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    if (state.agentKind == AgentKind.DEEPSEEK_HARNESS && selectedKind in DSH_PROTOCOL_PROVIDERS) {
        Text(stringResource(R.string.settings_gateway_protocol), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Column {
                listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onDshApi(option) }.padding(horizontal = 13.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(option, Modifier.weight(1f), fontSize = 13.sp)
                        SelectionDot(dshApi == option)
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.settings_model), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(model, onModel, label = { Text(stringResource(R.string.settings_model_id)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = onModels, enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && !isDiscovering, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        if (isDiscovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(if (models.isEmpty()) Icons.Default.Search else Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp))
        Spacer(Modifier.width(7.dp))
        Text(if (models.isEmpty()) stringResource(R.string.settings_find_models) else stringResource(R.string.settings_available_models, models.size))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_api_keys), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.settings_keys_saved, savedKeys.size), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { addKeyExpanded = !addKeyExpanded }) {
            Text(if (addKeyExpanded) stringResource(R.string.settings_cancel) else stringResource(R.string.settings_add_key))
        }
    }
    if (savedKeys.isNotEmpty()) {
        Text(stringResource(R.string.settings_saved_api_keys), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)) {
            Column {
                savedKeys.forEachIndexed { index, key ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onActivateKey(key.id) }.padding(start = 13.dp, top = 9.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(key.name, fontWeight = FontWeight.Medium)
                            Text(
                                if (key.isActive) stringResource(R.string.settings_key_active_now) else stringResource(R.string.settings_key_tap_activate),
                                fontSize = 11.sp,
                                color = if (key.isActive) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SelectionDot(key.isActive)
                        IconButton(onClick = { onRemoveKey(key.id) }) {
                            Icon(Icons.Default.DeleteSweep, stringResource(R.string.settings_remove_key, key.name), Modifier.size(18.dp))
                        }
                    }
                    if (index != savedKeys.lastIndex) HorizontalDivider(Modifier.padding(start = 13.dp))
                }
            }
        }
    }
    AnimatedVisibility(addKeyExpanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                newKeyName,
                onNewKeyName,
                label = { Text(stringResource(R.string.settings_key_name)) },
                placeholder = { Text(stringResource(R.string.settings_key_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                newApiKey,
                onNewApiKey,
                label = { Text(stringResource(R.string.settings_api_key)) },
                singleLine = true,
                visualTransformation = if (newKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = onToggleNewKey) {
                        Icon(if (newKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.settings_show_hide_key))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    onAddKey()
                    addKeyExpanded = false
                },
                enabled = newKeyName.isNotBlank() && newApiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp),
            ) {
                Text(stringResource(R.string.settings_save_api_key))
            }
        }
    }
    if (status != null) {
        Text(status, fontSize = 12.sp, color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
    Button(
        onClick = onValidate,
        enabled = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank() && !isDiscovering && !isValidating,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (isValidating) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(if (isValidating) stringResource(R.string.settings_checking_connection) else stringResource(R.string.settings_test_and_save))
    }
}

@Composable
private fun SelectionDot(selected: Boolean) {
    Box(
        Modifier.size(20.dp).border(if (selected) 2.dp else 1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(9.dp).background(PocketOrange, CircleShape))
    }
}

@Composable
private fun ModernThemeChoice(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) PocketOrange.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(vertical = 13.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, title, Modifier.size(20.dp), tint = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(5.dp))
            Text(title, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
private fun RuntimeInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, fontWeight = FontWeight.Medium, fontSize = 13.sp)
    }
}

@Composable
private fun PermissionLevelChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) PocketOrange.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) PocketOrange else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            label,
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) PocketOrange else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun DebugUpdateChannelSection(
    initialUrl: String,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    val isOverridden = initialUrl.isNotBlank()
    SettingsAccordion(
        title = stringResource(R.string.settings_update_channel),
        subtitle = if (isOverridden) stringResource(R.string.settings_update_overridden) else stringResource(R.string.settings_update_default),
        icon = Icons.Default.Tune,
        expanded = expanded,
        onClick = { expanded = !expanded },
    ) {
        Text(
            stringResource(R.string.settings_update_desc),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.settings_manifest_url)) },
            placeholder = { Text("https://your-tunnel.example/mobile-harness-update.json") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onSave(url) },
                enabled = url.startsWith("https://"),
                modifier = Modifier.weight(1f),
            ) {
                Text(if (isOverridden) stringResource(R.string.settings_replace) else stringResource(R.string.settings_use_check))
            }
            OutlinedButton(
                onClick = onClear,
                enabled = isOverridden,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.settings_reset))
            }
        }
        if (isOverridden) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.settings_current_url, initialUrl),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
