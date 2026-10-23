package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.jarves.mh.BuildConfig
import com.jarves.mh.R
import com.jarves.mh.data.AppLocale
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ChatSectionsMode
import com.jarves.mh.model.DevStack
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.runtime.KeepAliveTracker
import com.jarves.mh.tools.ToolCatalogLocale
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStore
import com.jarves.mh.ui.theme.AppColorTheme
import com.jarves.mh.ui.theme.AppThemeMode
import com.jarves.mh.ui.theme.ColorPicker
import com.jarves.mh.ui.theme.PalettePreview
import com.jarves.mh.ui.theme.PocketAccent
import com.jarves.mh.ui.theme.PocketGreen
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private enum class SettingsCategory { APPEARANCE, AGENT, CONVERSATION, ACCESS, TOOLS, BACKGROUND }

private data class SettingsCategorySpec(
    val id: SettingsCategory,
    val title: String,
    val subtitle: String,
    val keywords: String,
    val icon: ImageVector,
    val accent: Color,
)

private fun normalizeForSearch(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

/**
 * Global settings surface. It floats over the current root screen or project
 * workspace, so changing a preference never tears down the project context.
 *
 * The classic [SettingsScreen] stays reachable from the footer; it is the
 * fallback while the grouped panel is tuned.
 */
@Composable
internal fun SettingsDrawerOverlay(
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    state: AppUiState,
    viewModel: MainViewModel,
) {
    var showClassic by rememberSaveable { mutableStateOf(false) }
    var draggingEdge by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(open) {
        if (open) focusManager.clearFocus()
        progress.animateTo(if (open) 1f else 0f, tween(260))
    }
    BackHandler(enabled = open) { onClose() }
    BackHandler(enabled = showClassic) { showClassic = false }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val drawerWidth = if (maxWidth * 0.86f < 342.dp) maxWidth * 0.86f else 342.dp
        val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }

        // A narrow edge strip keeps the swipe-out gesture away from horizontal
        // chat/terminal gestures. It follows the finger and opens at 32%.
        if (!open && (draggingEdge || progress.value <= 0.001f)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(22.dp)
                    .align(Alignment.CenterStart)
                    .pointerInput(drawerWidthPx) {
                        detectHorizontalDragGestures(
                            onDragStart = { draggingEdge = true },
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (progress.value + dragAmount / drawerWidthPx).coerceIn(0f, 1f)
                                scope.launch { progress.snapTo(next) }
                            },
                            onDragEnd = {
                                draggingEdge = false
                                if (progress.value >= 0.32f) {
                                    onOpen()
                                } else {
                                    scope.launch { progress.animateTo(0f, tween(160)) }
                                }
                            },
                            onDragCancel = {
                                draggingEdge = false
                                scope.launch { progress.animateTo(0f, tween(160)) }
                            },
                        )
                    },
            )
        }

        if (progress.value > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f * progress.value))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { if (open) onClose() },
                    ),
            )
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(((progress.value - 1f) * drawerWidthPx).roundToInt(), 0) }
                    .pointerInput(open, drawerWidthPx) {
                        if (!open) return@pointerInput
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, dragAmount ->
                                change.consume()
                                val next = (progress.value + dragAmount / drawerWidthPx).coerceIn(0f, 1f)
                                scope.launch { progress.snapTo(next) }
                            },
                            onDragEnd = {
                                if (progress.value < 0.68f) {
                                    onClose()
                                } else {
                                    scope.launch { progress.animateTo(1f, tween(160)) }
                                }
                            },
                        )
                    },
                shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 8.dp,
            ) {
                SettingsDrawerContent(
                    state = state,
                    viewModel = viewModel,
                    onClose = onClose,
                    onShowClassic = {
                        onClose()
                        showClassic = true
                    },
                )
            }
        }

        if (showClassic) {
            SettingsScreenFromViewModel(
                state = state,
                viewModel = viewModel,
                onBack = { showClassic = false },
            )
        }
    }
}

@Composable
private fun SettingsDrawerContent(
    state: AppUiState,
    viewModel: MainViewModel,
    onClose: () -> Unit,
    onShowClassic: () -> Unit,
) {
    val context = LocalContext.current
    var category by remember { mutableStateOf<SettingsCategory?>(null) }
    var query by remember { mutableStateOf("") }
    val categories = listOf(
        SettingsCategorySpec(
            SettingsCategory.APPEARANCE,
            stringResource(categoryTitleRes(SettingsCategory.APPEARANCE)),
            stringResource(categorySubtitleRes(SettingsCategory.APPEARANCE)),
            "aspect tema paleta culoare limba theme palette color language",
            Icons.Default.Tune, Color(0xFF22D3EE),
        ),
        SettingsCategorySpec(
            SettingsCategory.AGENT,
            stringResource(categoryTitleRes(SettingsCategory.AGENT)),
            stringResource(categorySubtitleRes(SettingsCategory.AGENT)),
            "agent model provider furnizor chei api keys connection conexiune antigravity google update actualizari",
            Icons.Default.SmartToy, Color(0xFF22C55E),
        ),
        SettingsCategorySpec(
            SettingsCategory.CONVERSATION,
            stringResource(categoryTitleRes(SettingsCategory.CONVERSATION)),
            stringResource(categorySubtitleRes(SettingsCategory.CONVERSATION)),
            "conversatie chat voce microfon vorbire sectiuni voice microphone speech",
            Icons.Default.Psychology, Color(0xFF60A5FA),
        ),
        SettingsCategorySpec(
            SettingsCategory.ACCESS,
            stringResource(categoryTitleRes(SettingsCategory.ACCESS)),
            stringResource(categorySubtitleRes(SettingsCategory.ACCESS)),
            "acces permisiuni shizuku instrumente aprobare access permissions tools",
            Icons.Default.Security, Color(0xFFF28C52),
        ),
        SettingsCategorySpec(
            SettingsCategory.TOOLS,
            stringResource(categoryTitleRes(SettingsCategory.TOOLS)),
            stringResource(categorySubtitleRes(SettingsCategory.TOOLS)),
            "instrumente dev toolchain runtime linux diagnostice cache tools diagnostics",
            Icons.Default.Code, Color(0xFFA855F7),
        ),
        SettingsCategorySpec(
            SettingsCategory.BACKGROUND,
            stringResource(categoryTitleRes(SettingsCategory.BACKGROUND)),
            stringResource(categorySubtitleRes(SettingsCategory.BACKGROUND)),
            "fundal baterie actualizari update keepalive versiune version background battery",
            Icons.Default.BatterySaver, Color(0xFFDB2777),
        ),
    )
    val normalizedQuery = normalizeForSearch(query)
    val filtered = if (normalizedQuery.isBlank()) {
        categories
    } else {
        categories.filter {
            normalizeForSearch("${it.title} ${it.subtitle} ${it.keywords}").contains(normalizedQuery)
        }
    }
    val selectedSpec = category?.let { id -> categories.first { it.id == id } }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedSpec == null) {
                    DrawerIcon(Icons.Default.Settings, PocketAccent, 38.dp)
                } else {
                    IconButton(onClick = { category = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        selectedSpec?.title ?: stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold,
                        style = if (selectedSpec == null) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        selectedSpec?.subtitle ?: stringResource(R.string.settings_subtitle),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            if (selectedSpec == null) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.drawer_search_hint), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, stringResource(R.string.drawer_clear_search))
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                    )
                    DrawerSectionLabel(stringResource(R.string.drawer_categories))
                    if (filtered.isEmpty()) {
                        EmptySearch(query, onClear = { query = "" })
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            filtered.chunked(2).forEach { rowSpecs ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    rowSpecs.forEach { spec ->
                                        CategoryCard(
                                            spec = spec,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                query = ""
                                                category = spec.id
                                            },
                                        )
                                    }
                                    if (rowSpecs.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    DrawerSectionLabel(stringResource(R.string.drawer_quick_toggles))
                    QuickToggles(state = state, viewModel = viewModel)
                    DrawerNote(stringResource(R.string.drawer_note))
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (selectedSpec.id) {
                        SettingsCategory.APPEARANCE -> AppearancePanel(state, viewModel)
                        SettingsCategory.AGENT -> AgentPanel(state, viewModel, onShowClassic)
                        SettingsCategory.CONVERSATION -> ConversationPanel()
                        SettingsCategory.ACCESS -> AccessPanel(state, viewModel)
                        SettingsCategory.TOOLS -> ToolsPanel(state, viewModel)
                        SettingsCategory.BACKGROUND -> BackgroundPanel(state, viewModel)
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "v${BuildConfig.VERSION_NAME}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { category = SettingsCategory.TOOLS },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.settings_diagnostics), fontSize = 11.sp)
                }
                TextButton(
                    onClick = onShowClassic,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(stringResource(R.string.drawer_classic_settings), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun CategoryCard(
    spec: SettingsCategorySpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .heightIn(min = 118.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DrawerIcon(spec.icon, spec.accent, 30.dp)
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                spec.title,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                spec.subtitle,
                fontSize = 10.5.sp,
                lineHeight = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun QuickToggles(state: AppUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var micEnabled by remember { mutableStateOf(prefs.voiceMicEnabled) }
    var keepAliveEnabled by remember { mutableStateOf(prefs.keepAliveEnabled) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column {
            DrawerToggleRow(
                icon = Icons.Default.DarkMode,
                title = stringResource(R.string.settings_theme_dark),
                checked = state.themeMode == AppThemeMode.DARK,
                onCheckedChange = { dark ->
                    viewModel.setThemeMode(if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT)
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            DrawerToggleRow(
                icon = Icons.Default.Mic,
                title = stringResource(R.string.voice_mic_title),
                checked = micEnabled,
                onCheckedChange = {
                    micEnabled = it
                    prefs.voiceMicEnabled = it
                },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            DrawerToggleRow(
                icon = Icons.Default.PowerSettingsNew,
                title = stringResource(R.string.settings_keepalive_title),
                checked = keepAliveEnabled,
                onCheckedChange = {
                    keepAliveEnabled = it
                    prefs.keepAliveEnabled = it
                    KeepAliveTracker.setEnabled(context, it)
                },
            )
        }
    }
}

@Composable
private fun DrawerToggleRow(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = PocketAccent)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DrawerNote(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = PocketAccent.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, PocketAccent.copy(alpha = 0.18f)),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = PocketAccent)
            Spacer(Modifier.width(9.dp))
            Text(
                text,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptySearch(query: String, onClear: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Search, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.drawer_search_empty), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.drawer_search_empty_desc, query),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClear) { Text(stringResource(R.string.drawer_clear_search)) }
        }
    }
}

@Composable
private fun DrawerSectionLabel(text: String) {
    Text(
        text.uppercase(Locale.getDefault()),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.6.sp,
    )
}

@Composable
private fun DrawerIcon(icon: ImageVector, tint: Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .background(tint.copy(alpha = 0.13f), RoundedCornerShape(size * 0.3f))
            .border(1.dp, tint.copy(alpha = 0.28f), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = tint)
    }
}

@Composable
private fun PanelCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            content = content,
        )
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.2.sp,
    )
}

@Composable
private fun ConnectionValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            Modifier.width(78.dp),
            fontSize = 10.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value.ifBlank { "—" },
            Modifier.weight(1f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DrawerChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp)
    }
}

@Composable
private fun AppearancePanel(state: AppUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    var showColorPicker by remember { mutableStateOf(false) }
    val languageTag = if (Build.VERSION.SDK_INT >= 33) AppLocale.currentTag(context) else ""

    PanelCard {
        PanelLabel(stringResource(R.string.settings_appearance))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModernThemeChoice(
                stringResource(R.string.settings_chip_dark),
                Icons.Default.DarkMode,
                state.themeMode == AppThemeMode.DARK,
                { viewModel.setThemeMode(AppThemeMode.DARK) },
                Modifier.weight(1f),
            )
            ModernThemeChoice(
                stringResource(R.string.settings_chip_light),
                Icons.Default.LightMode,
                state.themeMode == AppThemeMode.LIGHT,
                { viewModel.setThemeMode(AppThemeMode.LIGHT) },
                Modifier.weight(1f),
            )
            ModernThemeChoice(
                stringResource(R.string.settings_chip_system),
                Icons.Default.Language,
                state.themeMode == AppThemeMode.SYSTEM,
                { viewModel.setThemeMode(AppThemeMode.SYSTEM) },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(2.dp))
        PanelLabel(stringResource(R.string.settings_color_theme))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorThemeChoice(
                stringResource(R.string.settings_theme_dev),
                listOf(Color(0xFF22C55E), Color(0xFF111823)),
                state.colorTheme == AppColorTheme.DEV,
                { viewModel.setColorTheme(AppColorTheme.DEV) },
                Modifier.weight(1f),
            )
            ColorThemeChoice(
                stringResource(R.string.settings_theme_violet),
                listOf(Color(0xFF7C5CFC), Color(0xFF1F1F28)),
                state.colorTheme == AppColorTheme.VIOLET,
                { viewModel.setColorTheme(AppColorTheme.VIOLET) },
                Modifier.weight(1f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorThemeChoice(
                stringResource(R.string.settings_theme_green),
                listOf(Color(0xFF43A047), Color(0xFF0A140E)),
                state.colorTheme == AppColorTheme.GREEN,
                { viewModel.setColorTheme(AppColorTheme.GREEN) },
                Modifier.weight(1f),
            )
            ColorThemeChoice(
                stringResource(R.string.settings_theme_rose),
                listOf(Color(0xFFDB2777), Color(0xFF7C3AED)),
                state.colorTheme == AppColorTheme.ROSE,
                { viewModel.setColorTheme(AppColorTheme.ROSE) },
                Modifier.weight(1f),
            )
        }
        CustomColorRow(
            enabled = state.colorTheme == AppColorTheme.CUSTOM,
            accent = Color(state.customAccent),
            background = Color(state.customBackground),
            onClick = { showColorPicker = true },
            onReset = viewModel::resetColorTheme,
        )
    }

    if (Build.VERSION.SDK_INT >= 33) {
        PanelCard {
            PanelLabel(stringResource(R.string.settings_language_title))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModernThemeChoice(
                    stringResource(R.string.settings_language_system),
                    Icons.Default.Language,
                    languageTag == AppLocale.TAG_SYSTEM,
                    { AppLocale.set(context, AppLocale.TAG_SYSTEM) },
                    Modifier.weight(1f),
                )
                ModernThemeChoice(
                    stringResource(R.string.settings_language_english),
                    Icons.Default.Language,
                    languageTag == AppLocale.TAG_ENGLISH,
                    { AppLocale.set(context, AppLocale.TAG_ENGLISH) },
                    Modifier.weight(1f),
                )
                ModernThemeChoice(
                    stringResource(R.string.settings_language_romanian),
                    Icons.Default.Language,
                    languageTag == AppLocale.TAG_ROMANIAN,
                    { AppLocale.set(context, AppLocale.TAG_ROMANIAN) },
                    Modifier.weight(1f),
                )
            }
        }
    }

    if (showColorPicker) {
        var draftAccent by remember { mutableStateOf(Color(state.customAccent)) }
        var draftBackground by remember { mutableStateOf(Color(state.customBackground)) }
        Dialog(
            onDismissRequest = { showColorPicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                Modifier.fillMaxWidth(0.94f).padding(top = 28.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        stringResource(R.string.settings_custom_colors),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))
                    ColorPicker(
                        label = stringResource(R.string.settings_custom_accent),
                        color = draftAccent,
                        onColorChange = { draftAccent = it },
                    )
                    Spacer(Modifier.height(14.dp))
                    ColorPicker(
                        label = stringResource(R.string.settings_custom_background),
                        color = draftBackground,
                        onColorChange = { draftBackground = it },
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.settings_custom_preview),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PalettePreview(accent = draftAccent, background = draftBackground)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                viewModel.setCustomColors(draftAccent.toArgb(), draftBackground.toArgb())
                                showColorPicker = false
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.settings_save)) }
                        TextButton(
                            onClick = { showColorPicker = false },
                            modifier = Modifier.weight(1f),
                        ) { Text(stringResource(R.string.settings_cancel)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPanel(
    state: AppUiState,
    viewModel: MainViewModel,
    onShowClassic: () -> Unit,
) {
    var showModelsPicker by remember { mutableStateOf(false) }
    var showAntigravityModels by remember { mutableStateOf(false) }
    val model = if (state.agentKind == AgentKind.ANTIGRAVITY) {
        state.antigravityModel
    } else {
        state.provider.model
    }
    val version = state.installedAgentVersions[state.agentKind]

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerIcon(Icons.Default.SmartToy, PocketGreen, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_active_agent),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.agentKind.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            if (version != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = PocketGreen.copy(alpha = 0.12f),
                ) {
                    Text(
                        "v$version",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp,
                        color = PocketGreen,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_provider),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.provider.kind.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.drawer_model),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    model.ifBlank { "—" },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = {
                    if (state.agentKind == AgentKind.ANTIGRAVITY) {
                        if (state.antigravityModels.isEmpty()) viewModel.refreshAntigravityModels()
                        showAntigravityModels = true
                    } else {
                        viewModel.refreshModelsForPicker()
                        showModelsPicker = true
                    }
                },
                enabled = state.agentKind != AgentKind.ANTIGRAVITY ||
                    state.antigravityAuth.status == AntigravityAuthStatus.SIGNED_IN,
            ) { Text(stringResource(R.string.drawer_change_model)) }
        }
        Text(
            stringResource(R.string.drawer_agent_switch_note),
            fontSize = 10.5.sp,
            lineHeight = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerIcon(Icons.Default.Key, Color(0xFF38BDF8), 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.drawer_connection), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    state.provider.kind.title,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ConnectionValueRow(stringResource(R.string.settings_base_url), state.provider.resolvedBaseUrl)
        ConnectionValueRow(stringResource(R.string.drawer_protocol), state.provider.dshApi)
        ConnectionValueRow(stringResource(R.string.drawer_active_key), state.activeApiKeyName ?: "—")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onShowClassic, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_api_keys), fontSize = 12.sp)
            }
            if (state.agentKind != AgentKind.ANTIGRAVITY) {
                OutlinedButton(
                    onClick = {
                        viewModel.refreshModelsForPicker()
                        showModelsPicker = true
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.drawer_models), fontSize = 12.sp)
                }
            }
        }
        Button(
            onClick = viewModel::pingApi,
            enabled = state.apiPingStatus != ApiPingStatus.PINGING,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.apiPingStatus == ApiPingStatus.PINGING) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.agent_test_connection))
        }
        when (state.apiPingStatus) {
            ApiPingStatus.PINGING -> Text(
                stringResource(R.string.agent_testing_connection),
                fontSize = 11.sp,
                color = PocketAccent,
            )
            ApiPingStatus.OK -> Text(
                stringResource(R.string.agent_connected),
                fontSize = 11.sp,
                color = PocketGreen,
            )
            ApiPingStatus.FAILED -> Text(
                stringResource(R.string.agent_failed),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error,
            )
            ApiPingStatus.IDLE -> Unit
        }
    }

    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DrawerIcon(Icons.Default.Psychology, Color(0xFF34A853), 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.drawer_antigravity), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    stringResource(R.string.drawer_antigravity_desc),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val auth = state.antigravityAuth
        val signedIn = auth.status == AntigravityAuthStatus.SIGNED_IN
        val statusText = when {
            signedIn -> auth.accountEmail?.let { stringResource(R.string.settings_connected_as, it) }
                ?: stringResource(R.string.settings_google_connected)
            !auth.message.isNullOrBlank() -> auth.message.orEmpty()
            auth.status == AntigravityAuthStatus.AWAITING_CODE -> stringResource(R.string.drawer_antigravity_awaiting)
            else -> stringResource(R.string.agent_not_signed_in)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        when {
                            signedIn -> PocketGreen
                            auth.status == AntigravityAuthStatus.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                statusText,
                fontSize = 11.sp,
                color = when {
                    signedIn -> PocketGreen
                    auth.status == AntigravityAuthStatus.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (auth.status == AntigravityAuthStatus.STARTING || auth.status == AntigravityAuthStatus.COMPLETING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        if (signedIn) {
            ConnectionValueRow(stringResource(R.string.settings_model), state.antigravityModel.ifBlank { "—" })
            OutlinedButton(
                onClick = {
                    if (state.antigravityModels.isEmpty()) viewModel.refreshAntigravityModels()
                    showAntigravityModels = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.drawer_change_model), fontSize = 12.sp)
            }
            Text(
                stringResource(R.string.settings_reasoning_effort),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("low", "medium", "high").forEach { effort ->
                    PermissionLevelChoice(
                        label = stringResource(
                            when (effort) {
                                "low" -> R.string.settings_effort_low
                                "medium" -> R.string.settings_effort_medium
                                else -> R.string.settings_effort_high
                            },
                        ),
                        selected = state.antigravityEffort == effort,
                        onClick = { viewModel.setAntigravityEffort(effort) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            DrawerNote(stringResource(R.string.settings_antigravity_autotool_desc))
        }

        OutlinedButton(onClick = onShowClassic, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.drawer_manage_account), fontSize = 12.sp)
        }
    }

    PanelCard {
        AgentUpdateBlock(
            state = state,
            onCheck = viewModel::checkAgentUpdates,
            onUpdate = viewModel::updateAgent,
        )
    }

    if (showAntigravityModels) {
        AntigravityModelDialog(
            state = state,
            onPick = { modelId ->
                showAntigravityModels = false
                viewModel.setAntigravityModel(modelId)
            },
            onRefresh = viewModel::refreshAntigravityModels,
            onDismiss = { showAntigravityModels = false },
        )
    }

    if (showModelsPicker) {
        ModelsPickerDialog(
            state = state,
            onPick = { modelId ->
                showModelsPicker = false
                viewModel.setProviderModel(modelId)
            },
            onPickProviderModel = { kind, modelId, baseUrl, dshApi ->
                showModelsPicker = false
                viewModel.selectProviderAndModel(kind, modelId, baseUrl, dshApi)
            },
            onDiscover = { kind, baseUrl, dshApi ->
                viewModel.discoverModelsForPicker(kind, baseUrl, dshApi)
            },
            onDismiss = { showModelsPicker = false },
        )
    }
}

@Composable
private fun AntigravityModelDialog(
    state: AppUiState,
    onPick: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_antigravity_model_id)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.antigravityModels.isEmpty() && !state.antigravityModelsLoading) {
                    Text(
                        stringResource(R.string.drawer_antigravity_no_models),
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.antigravityModels.forEach { modelId ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(modelId) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = modelId == state.antigravityModel, onClick = null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            modelId,
                            modifier = Modifier.weight(1f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRefresh, enabled = !state.antigravityModelsLoading) {
                if (state.antigravityModelsLoading) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(7.dp))
                } else {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(7.dp))
                }
                Text(stringResource(R.string.settings_refresh_models))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun ConversationPanel() {
    val context = LocalContext.current
    val prefs = remember { AppPreferences(context) }
    var micEnabled by remember { mutableStateOf(prefs.voiceMicEnabled) }
    var speakEnabled by remember { mutableStateOf(prefs.voiceSpeakEnabled) }
    var sectionsMode by remember { mutableStateOf(prefs.chatSectionsMode) }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_chat_sections))
        Text(
            stringResource(R.string.settings_chat_sections_desc),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val modes = listOf(
            ChatSectionsMode.AUTO to R.string.settings_chat_sections_auto,
            ChatSectionsMode.ALWAYS to R.string.settings_chat_sections_open,
            ChatSectionsMode.NEVER to R.string.settings_chat_sections_closed,
        )
        modes.forEach { (mode, labelRes) ->
            DrawerChoiceRow(
                label = stringResource(labelRes),
                selected = sectionsMode == mode,
                onClick = {
                    sectionsMode = mode
                    prefs.chatSectionsMode = mode
                },
            )
        }
    }

    PanelCard {
        DrawerToggleRow(
            icon = Icons.Default.Mic,
            title = stringResource(R.string.voice_mic_title),
            subtitle = stringResource(R.string.voice_mic_desc),
            checked = micEnabled,
            onCheckedChange = {
                micEnabled = it
                prefs.voiceMicEnabled = it
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        DrawerToggleRow(
            icon = Icons.Default.Psychology,
            title = stringResource(R.string.voice_speak_title),
            subtitle = stringResource(R.string.voice_speak_desc),
            checked = speakEnabled,
            onCheckedChange = {
                speakEnabled = it
                prefs.voiceSpeakEnabled = it
            },
        )
    }
}

@Composable
private fun AccessPanel(state: AppUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val toolCatalogLocale = remember(context.resources.configuration.locales[0].toLanguageTag()) {
        runCatching {
            ToolCatalogLocale(context.assets)
                .load(context.resources.configuration.locales[0].toLanguageTag())
        }.getOrDefault(emptyMap<String, String>())
    }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_access_title))
        AccessLevelContent(
            current = state.accessLevel,
            shizuku = state.shizuku,
            onSelect = viewModel::setAccessLevel,
            onRequestAccess = viewModel::requestShizukuAccess,
            onInstall = viewModel::installBundledShizuku,
        )
    }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_permissions_title))
        Text(
            stringResource(R.string.settings_permissions_desc),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.settings_global_default), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PermissionLevelChoice(
                label = stringResource(R.string.settings_allow),
                selected = state.toolPermissionGlobal == ToolPermissionLevel.ALLOW,
                onClick = { viewModel.setToolPermissionGlobal(ToolPermissionLevel.ALLOW) },
                modifier = Modifier.weight(1f),
            )
            PermissionLevelChoice(
                label = stringResource(R.string.settings_ask),
                selected = state.toolPermissionGlobal == ToolPermissionLevel.ASK,
                onClick = { viewModel.setToolPermissionGlobal(ToolPermissionLevel.ASK) },
                modifier = Modifier.weight(1f),
            )
            PermissionLevelChoice(
                label = stringResource(R.string.settings_forbid),
                selected = state.toolPermissionGlobal == ToolPermissionLevel.FORBID,
                onClick = { viewModel.setToolPermissionGlobal(ToolPermissionLevel.FORBID) },
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.settings_per_tool_overrides), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        ToolPermissionStore.knownTools.forEach { tool ->
            PermissionOverrideRow(
                title = toolCatalogLocale["tool_${tool}_name"] ?: tool,
                subtitle = toolCatalogLocale["tool_${tool}_description"],
                selectedOverride = state.toolPermissionOverrides[tool],
                effective = state.toolPermissionOverrides[tool] ?: state.toolPermissionGlobal,
                onSetOverride = { level -> viewModel.setToolPermissionOverride(tool, level) },
                stacked = true,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.settings_plugin_tools), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.settings_plugin_tools_desc),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.pluginTools.isEmpty()) {
            Text(
                stringResource(R.string.settings_plugin_tools_empty),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.pluginTools.forEach { pluginTool ->
                PermissionOverrideRow(
                    title = pluginTool.name,
                    subtitle = listOfNotNull(
                        pluginTool.description.takeIf { it.isNotBlank() },
                        stringResource(R.string.settings_plugin_tools_source, pluginTool.sourceLabel),
                    ).joinToString(" · "),
                    selectedOverride = state.toolPermissionOverrides[pluginTool.name],
                    effective = state.toolPermissionOverrides[pluginTool.name] ?: state.toolPermissionGlobal,
                    onSetOverride = { level -> viewModel.setToolPermissionOverride(pluginTool.name, level) },
                    stacked = true,
                )
            }
        }
    }
}

@Composable
private fun ToolsPanel(state: AppUiState, viewModel: MainViewModel) {
    var terminalCleared by remember { mutableStateOf(false) }
    var showReliabilityHelp by remember { mutableStateOf(false) }
    var stackPendingRemoval by remember { mutableStateOf<DevStack?>(null) }
    val context = LocalContext.current

    stackPendingRemoval?.let { stack ->
        AlertDialog(
            onDismissRequest = { stackPendingRemoval = null },
            title = { Text(stringResource(R.string.settings_remove_toolchain_title, stack.label)) },
            text = { Text(stringResource(R.string.settings_remove_toolchain_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        stackPendingRemoval = null
                        viewModel.removeDevStack(stack)
                    },
                ) { Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { stackPendingRemoval = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_devtools_title))
        Text(
            stringResource(R.string.settings_devstack_desc),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DevStack.entries.forEachIndexed { index, stack ->
            val installed = stack in state.installedDevStacks
            val installing = state.devStackInstalling == stack
            val removing = installing && state.devStackRemoving
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stack.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        stack.installsSummary,
                        fontSize = 10.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    removing -> Text(
                        stringResource(R.string.settings_removing),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                    installing -> Text(
                        "${(state.devStackProgress * 100).toInt()}%",
                        color = PocketAccent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                    )
                    installed && stack == DevStack.WEB -> Text(
                        stringResource(R.string.settings_included),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    installed -> TextButton(
                        onClick = { stackPendingRemoval = stack },
                        enabled = state.devStackInstalling == null,
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) {
                        Text(stringResource(R.string.settings_remove), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    }
                    else -> OutlinedButton(
                        onClick = { viewModel.installDevStack(stack) },
                        enabled = state.devStackInstalling == null,
                        contentPadding = PaddingValues(horizontal = 10.dp),
                    ) { Text(stringResource(R.string.settings_add), fontSize = 11.sp) }
                }
            }
            if (installing) {
                LinearProgressIndicator(
                    progress = { state.devStackProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = PocketAccent,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                state.devStackBytes?.let { (downloaded, total) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            formatTransferMb(downloaded) + " / " + formatTransferMb(total),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        state.devStackBytesPerSecond?.takeIf { it > 0L }?.let { speed ->
                            Text(
                                formatTransferSpeed(speed) + " · " + formatTransferEta(downloaded, total, speed),
                                fontSize = 10.sp,
                                color = PocketAccent,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                state.devStackMessage?.let { message ->
                    Text(
                        message,
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val finishedMessage = state.devStackMessage
            if (!installing && state.devStackMessageStack == stack && finishedMessage != null) {
                Text(
                    finishedMessage,
                    fontSize = 10.sp,
                    color = if (state.devStackFailed) MaterialTheme.colorScheme.error else PocketAccent,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (index != DevStack.entries.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
            }
        }
    }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_linux_runtime))
        RuntimeInfoRow(stringResource(R.string.settings_architecture), "ARM64 (aarch64)")
        RuntimeInfoRow(stringResource(R.string.settings_environment), "Ubuntu 26.04 PRoot")
        RuntimeInfoRow(
            stringResource(R.string.settings_active_agent),
            state.agentKind.title + if (state.installedAgentVersions.containsKey(state.agentKind)) "" else stringResource(R.string.settings_not_installed_suffix),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        Text(
            stringResource(R.string.settings_installed_agents),
            fontSize = 11.sp,
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
        OutlinedButton(
            onClick = {
                viewModel.clearTerminal()
                terminalCleared = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.DeleteSweep, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(
                if (terminalCleared) stringResource(R.string.settings_terminal_cleared) else stringResource(R.string.settings_clear_terminal),
                fontSize = 12.sp,
            )
        }
        OutlinedButton(
            onClick = { showReliabilityHelp = !showReliabilityHelp },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.settings_advanced_reliability), fontSize = 12.sp) }
        AnimatedVisibility(showReliabilityHelp) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(
                    stringResource(R.string.settings_reliability_desc),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                        }.onFailure {
                            context.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.settings_open_dev_options)) }
            }
        }
    }

    DeveloperDiagnosticsSection(
        diagnostics = state.diagnostics,
        onRefresh = viewModel::refreshDeveloperDiagnostics,
        onStopInactive = viewModel::stopInactiveDeveloperProcesses,
        onClearCache = viewModel::clearDeveloperRuntimeCache,
    )
}

@Composable
private fun BackgroundPanel(state: AppUiState, viewModel: MainViewModel) {
    val context = LocalContext.current
    val powerManager = context.getSystemService(PowerManager::class.java)
    var batteryUnrestricted by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    val prefs = remember { AppPreferences(context) }
    var keepAliveEnabled by remember { mutableStateOf(prefs.keepAliveEnabled) }
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun openBackgroundSettings() {
        val requestIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        val detailsIntent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        )
        runCatching { batteryLauncher.launch(requestIntent) }
            .onFailure { batteryLauncher.launch(detailsIntent) }
    }

    PanelCard {
        PanelLabel(stringResource(R.string.settings_background_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (batteryUnrestricted) PocketGreen else MaterialTheme.colorScheme.error, CircleShape),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                if (batteryUnrestricted) stringResource(R.string.settings_active) else stringResource(R.string.settings_inactive),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (batteryUnrestricted) PocketGreen else MaterialTheme.colorScheme.error,
            )
        }
        Text(
            stringResource(R.string.settings_bg_desc),
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = ::openBackgroundSettings, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (batteryUnrestricted) stringResource(R.string.settings_review_android_setting) else stringResource(R.string.settings_enable_android_setting),
                fontSize = 12.sp,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
        DrawerToggleRow(
            icon = Icons.Default.PowerSettingsNew,
            title = stringResource(R.string.settings_keepalive_title),
            subtitle = stringResource(R.string.settings_keepalive_desc),
            checked = keepAliveEnabled,
            onCheckedChange = {
                keepAliveEnabled = it
                prefs.keepAliveEnabled = it
                KeepAliveTracker.setEnabled(context, it)
            },
        )
    }

    UpdatePanel(state, viewModel)

    if (BuildConfig.DEBUG) {
        DebugUpdateChannelSection(
            initialUrl = viewModel.debugUpdateManifestUrl(),
            onSave = viewModel::setDebugUpdateManifestUrl,
            onClear = viewModel::clearDebugUpdateManifestUrl,
        )
    }
}

@Composable
private fun UpdatePanel(state: AppUiState, viewModel: MainViewModel) {
    val update = state.appUpdate
    val busy = state.appUpdateStatus == AppUpdateStatus.DOWNLOADING || state.appUpdateStatus == AppUpdateStatus.INSTALLING
    PanelCard {
        PanelLabel(stringResource(R.string.drawer_updates))
        RuntimeInfoRow(stringResource(R.string.drawer_version), "v${BuildConfig.VERSION_NAME}")
        if (update != null) {
            Text(
                stringResource(R.string.drawer_update_available, update.versionName),
                fontSize = 11.sp,
                color = PocketAccent,
            )
            if (state.appUpdateStatus == AppUpdateStatus.DOWNLOADING) {
                val total = state.appUpdateTotalBytes
                val downloaded = state.appUpdateDownloadedBytes
                if (total > 0L) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatTransferMb(downloaded)} / ${formatTransferMb(total)}",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (state.appUpdateStatus == AppUpdateStatus.INSTALLING) {
                Text(stringResource(R.string.drawer_update_installing), fontSize = 11.sp, color = PocketAccent)
            }
            Button(
                onClick = viewModel::installAppUpdate,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.drawer_install_update))
            }
        }
        state.appUpdateError?.let { error ->
            Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = { viewModel.checkForAppUpdate(force = true) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
            Spacer(Modifier.width(7.dp))
            Text(stringResource(R.string.drawer_check_updates), fontSize = 12.sp)
        }
    }
}

private fun categoryTitleRes(id: SettingsCategory): Int = when (id) {
    SettingsCategory.APPEARANCE -> R.string.drawer_category_appearance
    SettingsCategory.AGENT -> R.string.drawer_category_agent
    SettingsCategory.CONVERSATION -> R.string.drawer_category_conversation
    SettingsCategory.ACCESS -> R.string.drawer_category_access
    SettingsCategory.TOOLS -> R.string.drawer_category_tools
    SettingsCategory.BACKGROUND -> R.string.drawer_category_background
}

private fun categorySubtitleRes(id: SettingsCategory): Int = when (id) {
    SettingsCategory.APPEARANCE -> R.string.drawer_category_appearance_sub
    SettingsCategory.AGENT -> R.string.drawer_category_agent_sub
    SettingsCategory.CONVERSATION -> R.string.drawer_category_conversation_sub
    SettingsCategory.ACCESS -> R.string.drawer_category_access_sub
    SettingsCategory.TOOLS -> R.string.drawer_category_tools_sub
    SettingsCategory.BACKGROUND -> R.string.drawer_category_background_sub
}
