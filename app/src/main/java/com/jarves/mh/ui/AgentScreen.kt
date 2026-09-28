package com.jarves.mh.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.data.ApiKeyCredential
import com.jarves.mh.data.ApiKeyInfo
import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.CustomEndpoint
import com.jarves.mh.model.DSH_PROTOCOL_PROVIDERS
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.defaultDshApiForProvider
import com.jarves.mh.model.inferredDshApiForUrl
import com.jarves.mh.model.isHttpScheme
import com.jarves.mh.model.isLoopbackBaseUrl
import com.jarves.mh.model.providersForAgent
import com.jarves.mh.model.providerProtocolForAgent
import com.jarves.mh.model.schemeOf
import com.jarves.mh.model.withDefaultScheme
import com.jarves.mh.network.ConnectionValidation
import com.jarves.mh.network.DiscoveredModel
import com.jarves.mh.network.EndpointDetection
import com.jarves.mh.network.EndpointModelCatalog
import com.jarves.mh.network.ModelDiscoveryResult
import com.jarves.mh.network.ProviderApiClient
import com.jarves.mh.runtime.AntigravityAuthStatus
import com.jarves.mh.ui.theme.PocketAccent
import com.jarves.mh.ui.theme.PocketAccentTeal
import com.jarves.mh.ui.theme.PocketBlue
import com.jarves.mh.ui.theme.PocketCardBorder
import com.jarves.mh.ui.theme.PocketMuted
import com.jarves.mh.ui.theme.PocketOutline
import com.jarves.mh.ui.theme.PocketSurface
import com.jarves.mh.ui.theme.PocketSurfaceVariant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class KeyConnectionStatus(
    val message: String,
    val successful: Boolean? = null,
    val providerMessage: String? = null,
    val label: String = if (successful == true) "Verified" else "Failed",
)

/** Status filter ids used by the model list chips (mirrors the mock's pill row). */
private const val FILTER_ALL = "all"
private const val FILTER_WORKING = "working"
private const val FILTER_BROKEN = "broken"
private const val FILTER_FREE = "free"

/**
 * Collapses "https://inference.example.com/v1/chat/completions" to "inference.example.com".
 * Full base URLs wrap and drown the row; the host plus the already-shown model count is enough.
 */
internal fun hostOnly(rawUrl: String): String {
    val stripped = rawUrl
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
    return stripped.ifBlank { rawUrl }
}

/** One base URL offered under the Base URL field, optionally carrying its gateway protocol. */
private data class PresetEndpoint(
    @StringRes val labelRes: Int,
    val baseUrl: String,
    val dshApi: String? = null,
)

/**
 * Preset base URLs per provider. Custom API gets the local gateways this device
 * can reach without a key; Kimi gets its regional endpoints.
 */
private fun presetEndpointsFor(kind: ProviderKind): List<PresetEndpoint> = when (kind) {
    ProviderKind.KIMI -> listOf(
        PresetEndpoint(R.string.preset_endpoint_kimi_international, "https://api.moonshot.ai/anthropic"),
        PresetEndpoint(R.string.preset_endpoint_kimi_china, "https://api.moonshot.cn/anthropic"),
    )
    ProviderKind.CUSTOM -> listOf(
        PresetEndpoint(R.string.preset_endpoint_ollama, "http://localhost:11434", "openai-completions"),
        PresetEndpoint(R.string.preset_endpoint_lmstudio, "http://localhost:1234", "openai-completions"),
        PresetEndpoint(R.string.preset_endpoint_vllm, "http://localhost:8000", "openai-completions"),
    )
    else -> emptyList()
}

/** Latency badge color: green under 2s, yellow under 5s, red when broken or slower. */
internal fun latencyColor(model: DiscoveredModel): Color = when {
    model.isBroken -> Color(0xFFFCA5A5)
    model.latencyMs == null -> Color(0xFF7A7A86)
    model.latencyMs!! < 2_000 -> Color(0xFF6EE7B7)
    model.latencyMs!! < 5_000 -> Color(0xFFFCD34D)
    else -> Color(0xFFFCA5A5)
}

/** Formats Antigravity model identifiers into clean, human-friendly names. */
internal fun formatAntigravityModelName(id: String): String = when (id) {
    "gemini-3.8-flash-high" -> "Gemini 3.8 Flash (High)"
    "gemini-3.8-flash-medium" -> "Gemini 3.8 Flash"
    "gemini-3.8-flash-low" -> "Gemini 3.8 Flash (Low)"
    "gemini-3.6-flash-high" -> "Gemini 3.6 Flash (High)"
    "gemini-3.6-flash-medium" -> "Gemini 3.6 Flash"
    "gemini-3.6-flash-low" -> "Gemini 3.6 Flash (Low)"
    "gemini-3.1-pro-high" -> "Gemini 3.1 Pro (High)"
    "gemini-3.1-pro-low" -> "Gemini 3.1 Pro (Low)"
    "claude-sonnet-4-6" -> "Claude 3.7 Sonnet"
    "claude-opus-4-6-thinking" -> "Claude 3.7 Opus (Thinking)"
    "gpt-oss-120b-medium" -> "GPT-OSS 120B"
    else -> id.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
    }
}

/** Returns tier classification badge resources for Antigravity models. */
internal fun formatAntigravityModelTier(id: String): Int? = when {
    id.contains("3.8") -> R.string.agent_tier_recommended
    id.contains("3.6") -> R.string.agent_tier_stable
    id.contains("3.1-pro") -> R.string.agent_tier_pro
    id.contains("claude") -> R.string.agent_tier_anthropic
    id.contains("gpt") -> R.string.agent_tier_opensource
    else -> null
}

/**
 * Dedicated Agent + AI connection hub.
 *
 * Replaces the old dashboard Terminal tab. Terminal is now a FAB
 * on the project/workspace screen; this screen owns:
 *  1. Status hero (active agent + model + connectivity)
 *  2. Coding agent picker + installs + updates
 *  3. AI connection (Antigravity OAuth OR provider + model + API keys)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    state: AppUiState,
    onSaveProvider: (ProviderProfile, String) -> Unit,
    onDiscoverModels: suspend (ProviderProfile, String) -> ModelDiscoveryResult,
    onValidateProvider: suspend (ProviderProfile, String, List<DiscoveredModel>) -> ConnectionValidation,
    onPing: () -> Unit,
    getSavedApiKey: (ProviderKind) -> String,
    getSavedApiKeys: (ProviderKind) -> List<ApiKeyInfo>,
    onAddApiKey: (ProviderKind, String, String) -> List<ApiKeyInfo>,
    onActivateApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onRemoveApiKey: (ProviderKind, String) -> List<ApiKeyInfo>,
    onListKeySecrets: suspend (ProviderKind) -> List<ApiKeyCredential> = { emptyList() },
    onUpsertCustomEndpoint: (CustomEndpoint) -> Unit = {},
    onDeleteCustomEndpoint: (String) -> Unit = {},
    onDetectEndpoint: suspend (String, String) -> EndpointDetection = { _, _ ->
        EndpointDetection(null, emptyList(), null)
    },
    onSelectAgent: (AgentKind) -> Unit = {},
    onInstallAgent: (AgentKind) -> Unit = {},
    onCheckAgentUpdates: () -> Unit = {},
    onUpdateAgent: (AgentKind) -> Unit = {},
    onStartAntigravityLogin: () -> Unit = {},
    onSubmitAntigravityCode: (String) -> Unit = {},
    onLogoutAntigravity: () -> Unit = {},
    onRefreshAntigravityModels: () -> Unit = {},
    onSetAntigravityModel: (String) -> Unit = {},
    onSetAntigravityEffort: (String) -> Unit = {},
    onScanModels: (ProviderProfile, String, List<DiscoveredModel>) -> Unit = { _, _, _ -> },
    onHideBrokenChange: (Boolean) -> Unit = {},
    onAutoScanChange: (Boolean) -> Unit = {},
    onDeleteModelCatalog: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var selectedKind by rememberSaveable(state.provider.kind) { mutableStateOf(state.provider.kind) }
    var baseUrl by rememberSaveable(state.provider.baseUrl) { mutableStateOf(state.provider.baseUrl) }
    var model by rememberSaveable(state.provider.model) { mutableStateOf(state.provider.model) }
    var dshApi by rememberSaveable(state.provider.dshApi) { mutableStateOf(state.provider.dshApi) }
    var apiKey by rememberSaveable(selectedKind) { mutableStateOf(getSavedApiKey(selectedKind)) }
    var savedKeys by remember(selectedKind, state.activeApiKeyName) {
        mutableStateOf(getSavedApiKeys(selectedKind))
    }
    var newKeyName by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newApiKey by rememberSaveable(selectedKind) { mutableStateOf("") }
    var newKeyVisible by rememberSaveable(selectedKind, savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    var models by remember(selectedKind, baseUrl, state.modelCatalogs) {
        val kindName = selectedKind.name
        val url = if (selectedKind.fixedBaseUrl) selectedKind.defaultBaseUrl else baseUrl
        val catalog = state.modelCatalogs.find { it.matches(kindName, url) }
        mutableStateOf(catalog?.models ?: emptyList())
    }
    var modelSearch by rememberSaveable(selectedKind) { mutableStateOf("") }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showScanLog by rememberSaveable { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var isValidating by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var statusProviderMessage by remember { mutableStateOf<String?>(null) }
    var keyConnectionStatuses by remember(selectedKind) {
        mutableStateOf<Map<String, KeyConnectionStatus>>(emptyMap())
    }
    // Antigravity model sheet state
    var showAntigravityModelSheet by rememberSaveable { mutableStateOf(false) }
    var antigravitySearch by rememberSaveable { mutableStateOf("") }
    var antigravityCode by rememberSaveable { mutableStateOf("") }
    var viewedAgent by rememberSaveable { mutableStateOf(state.agentKind) }

    var showEndpointEditor by rememberSaveable { mutableStateOf(false) }
    var editingEndpointId by rememberSaveable { mutableStateOf("") }
    var endpointLabel by rememberSaveable { mutableStateOf("") }
    var endpointUrl by rememberSaveable { mutableStateOf("") }
    var endpointModel by rememberSaveable { mutableStateOf("") }
    var endpointDsh by rememberSaveable { mutableStateOf("") }
    var endpointKeyName by rememberSaveable { mutableStateOf("") }
    var endpointDetecting by remember { mutableStateOf(false) }
    var endpointDetectFailed by rememberSaveable { mutableStateOf(false) }
    var endpointDetectNote by rememberSaveable { mutableStateOf("") }
    var pendingDeleteEndpointId by rememberSaveable { mutableStateOf("") }
    // Inline key entry inside the endpoint dialog, so a key can be added
    // without leaving the endpoint being created.
    var endpointKeyNew by rememberSaveable { mutableStateOf(false) }
    var endpointNewKeyName by rememberSaveable { mutableStateOf("") }
    var endpointNewKeySecret by rememberSaveable { mutableStateOf("") }
    var endpointNewKeyVisible by rememberSaveable { mutableStateOf(false) }
    var endpointKeySecrets by remember { mutableStateOf<List<ApiKeyCredential>>(emptyList()) }
    var copiedKeyId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(showEndpointEditor, selectedKind) {
        endpointKeySecrets = if (showEndpointEditor) onListKeySecrets(selectedKind) else emptyList()
    }
    LaunchedEffect(copiedKeyId) {
        if (copiedKeyId.isNotEmpty()) {
            delay(1_500)
            copiedKeyId = ""
        }
    }

    val orderedAgents = remember(state.primaryAgentKind) {
        listOf(state.primaryAgentKind) + AgentKind.entries.filterNot { it == state.primaryAgentKind }
    }
    val viewedAgentInstalled = viewedAgent == state.agentKind ||
        state.installedAgentVersions.containsKey(viewedAgent)

    val providerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val antigravitySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeBrokenIds = remember(models, state.brokenModelIds) {
        val fromModels = models.filter(DiscoveredModel::isBroken).map(DiscoveredModel::id).toSet()
        if (fromModels.isNotEmpty()) fromModels else state.brokenModelIds
    }
    var statusFilter by rememberSaveable { mutableStateOf(if (state.hideBrokenModels) FILTER_WORKING else FILTER_ALL) }
    val filteredModels = remember(models, modelSearch, statusFilter, activeBrokenIds) {
        val list = when (statusFilter) {
            FILTER_WORKING -> models.filterNot { it.id in activeBrokenIds }
            FILTER_BROKEN -> models.filter { it.id in activeBrokenIds }
            FILTER_FREE -> models.filter(DiscoveredModel::isFree)
            else -> models
        }
        val q = modelSearch.trim()
        if (q.isBlank()) list else list.filter {
            it.id.contains(q, true) || it.displayName.contains(q, true)
        }
    }

    val statsBroken = remember(models, activeBrokenIds) {
        models.count { it.id in activeBrokenIds }
    }
    val statsAvgLatencyMs = remember(models) {
        models.mapNotNull(DiscoveredModel::latencyMs).takeIf { it.isNotEmpty() }?.average()
    }
    val statsAvgLatencyLabel = remember(statsAvgLatencyMs) {
        statsAvgLatencyMs?.let { String.format(java.util.Locale.ROOT, "%.1fs", it / 1000.0) } ?: "—"
    }

    val antigravityModelList = remember(state.antigravityModels) {
        if (state.antigravityModels.isNotEmpty()) state.antigravityModels
        else listOf(
            "gemini-3.8-flash-high",
            "gemini-3.8-flash-medium",
            "gemini-3.6-flash-high",
            "gemini-3.6-flash-medium",
            "gemini-3.6-flash-low",
            "gemini-3.1-pro-high",
            "gemini-3.1-pro-low",
            "claude-sonnet-4-6",
            "claude-opus-4-6-thinking",
            "gpt-oss-120b-medium",
        )
    }

    val filteredAntigravityModels = remember(antigravityModelList, antigravitySearch) {
        val q = antigravitySearch.trim()
        if (q.isBlank()) antigravityModelList
        else antigravityModelList.filter {
            it.contains(q, true) || formatAntigravityModelName(it).contains(q, true)
        }
    }

    // Auto-scan (first integration or manual) should surface the terminal log.
    LaunchedEffect(state.isModelScanning) {
        if (state.isModelScanning) showModels = true
    }

    fun discoverModels() {
        val effectiveKey = apiKey.trim().ifBlank { newApiKey.trim() }
        val supportsPublicDiscovery = selectedKind == ProviderKind.LLM_ROUTER ||
            selectedKind == ProviderKind.OPENCODE_ZEN
        // A loopback gateway on this device serves its model list without credentials.
        val loopbackTarget = isLoopbackBaseUrl(if (selectedKind.fixedBaseUrl) selectedKind.defaultBaseUrl else baseUrl)
        if (effectiveKey.isBlank() && !supportsPublicDiscovery && !loopbackTarget) {
            status = context.getString(R.string.agent_api_key_first)
            statusOk = false
            statusProviderMessage = null
            newKeyVisible = true
            showModels = true
            return
        }
        scope.launch {
            isDiscovering = true
            status = context.getString(R.string.agent_discovering_from, selectedKind.title)
            statusOk = true
            statusProviderMessage = null
            val kind = selectedKind
            val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else withDefaultScheme(baseUrl)
            val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
            when (val result = onDiscoverModels(profile, effectiveKey)) {
                is ModelDiscoveryResult.Success -> {
                    models = result.models
                    if (apiKey.isBlank() && newApiKey.isNotBlank()) {
                        val keyName = newKeyName.trim().ifBlank { context.getString(R.string.agent_key_for, kind.title) }
                        savedKeys = onAddApiKey(kind, keyName, newApiKey.trim())
                        apiKey = getSavedApiKey(kind)
                        newKeyName = ""
                        newApiKey = ""
                        newKeyVisible = false
                    }
                    status = context.getString(R.string.agent_discovered, result.models.size, selectedKind.title)
                    statusOk = true
                    statusProviderMessage = null
                    showModels = true
                }
                is ModelDiscoveryResult.Failure -> {
                    status = result.message
                    statusOk = false
                    statusProviderMessage = result.providerMessage
                }
            }
            isDiscovering = false
        }
    }

    /** Header pill action: discover when the list is still empty, otherwise re-run the health scan. */
    fun runDiscoverOrTest() {
        if (models.isEmpty()) {
            discoverModels()
            return
        }
        val kind = selectedKind
        val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else withDefaultScheme(baseUrl)
        val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
        val key = apiKey.trim().ifBlank { newApiKey.trim() }
        onScanModels(profile, key, models)
    }

    // ── Live connection status calculations ──
    val isAntigravity = state.agentKind == AgentKind.ANTIGRAVITY
    val antigravityTesting = isAntigravity && state.apiPingStatus == ApiPingStatus.PINGING
    val antigravityHelloFailed = isAntigravity && state.apiPingStatus == ApiPingStatus.FAILED
    val pillLoading = antigravityTesting || (!isAntigravity && state.apiPingStatus == ApiPingStatus.PINGING)

    val onlinePill = Color(0xFF34D399)
    val warnPill = Color(0xFFFBBF24)
    val idlePill = Color(0xFF6B7280)
    val (dot, label, pillBg) = if (isAntigravity) {
        when {
            antigravityTesting -> Triple(PocketAccent, stringResource(R.string.agent_testing), PocketAccent.copy(alpha = 0.10f))
            state.antigravityAuth.status != AntigravityAuthStatus.SIGNED_IN || antigravityHelloFailed ->
                Triple(warnPill, stringResource(R.string.agent_attention), warnPill.copy(alpha = 0.08f))
            else -> Triple(onlinePill, stringResource(R.string.agent_online), onlinePill.copy(alpha = 0.10f))
        }
    } else {
        when (state.apiPingStatus) {
            ApiPingStatus.OK -> Triple(onlinePill, stringResource(R.string.agent_online), onlinePill.copy(alpha = 0.10f))
            ApiPingStatus.FAILED -> Triple(warnPill, stringResource(R.string.agent_attention), warnPill.copy(alpha = 0.08f))
            ApiPingStatus.PINGING -> Triple(PocketAccent, stringResource(R.string.agent_testing), PocketAccent.copy(alpha = 0.10f))
            ApiPingStatus.IDLE -> Triple(idlePill, stringResource(R.string.agent_not_tested), idlePill.copy(alpha = 0.18f))
        }
    }

    // ── Antigravity Model Modal Bottom Sheet ──
    if (showAntigravityModelSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAntigravityModelSheet = false },
            sheetState = antigravitySheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.agent_select_model), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.agent_available_antigravity, filteredAntigravityModels.size),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onRefreshAntigravityModels, enabled = !state.antigravityModelsLoading) {
                        if (state.antigravityModelsLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, stringResource(R.string.settings_refresh_models))
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = antigravitySearch,
                    onValueChange = { antigravitySearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    placeholder = { Text(stringResource(R.string.agent_search_series)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(12.dp))

                if (filteredAntigravityModels.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.agent_no_models), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredAntigravityModels, key = { it }) { modelId ->
                            val isSelected = state.antigravityModel == modelId
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) PocketAccent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketAccent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSetAntigravityModel(modelId)
                                        showAntigravityModelSheet = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                formatAntigravityModelName(modelId),
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketAccent else MaterialTheme.colorScheme.onSurface,
                                            )
                                            val tier = formatAntigravityModelTier(modelId)
                                            if (tier != null) {
                                                Spacer(Modifier.width(8.dp))
                                                Surface(
                                                    color = if (isSelected) PocketAccent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(4.dp),
                                                ) {
                                                    Text(
                                                        stringResource(tier),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isSelected) PocketAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            modelId,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun persistEndpoint(statusRes: Int, vararg args: Any) {
        val endpoint = if (editingEndpointId.isBlank()) {
            CustomEndpoint(
                label = endpointLabel.trim(),
                baseUrl = withDefaultScheme(endpointUrl),
                model = endpointModel.trim(),
                dshApi = endpointDsh,
                keyName = endpointKeyName,
            )
        } else {
            CustomEndpoint(
                id = editingEndpointId,
                label = endpointLabel.trim(),
                baseUrl = withDefaultScheme(endpointUrl),
                model = endpointModel.trim(),
                dshApi = endpointDsh,
                keyName = endpointKeyName,
            )
        }
        onUpsertCustomEndpoint(endpoint)
        showEndpointEditor = false
        status = if (args.isEmpty()) {
            context.getString(statusRes)
        } else {
            context.getString(statusRes, *args)
        }
        statusOk = true
    }

    if (showEndpointEditor) {
        val dialogClipboard = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { if (!endpointDetecting) showEndpointEditor = false },
            title = { Text(stringResource(R.string.agent_endpoint_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = endpointLabel,
                        onValueChange = { endpointLabel = it },
                        label = { Text(stringResource(R.string.agent_endpoint_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    OutlinedTextField(
                        value = endpointUrl,
                        onValueChange = {
                            endpointUrl = it
                            // A new URL invalidates the previous probe result and any
                            // model/protocol inherited from the old endpoint.
                            endpointDetectFailed = false
                            endpointDetectNote = ""
                            endpointModel = ""
                            endpointDsh = ""
                        },
                        label = { Text(stringResource(R.string.settings_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (endpointUrl.isNotBlank()) {
                        if (schemeOf(endpointUrl).isEmpty()) {
                            Text(
                                stringResource(R.string.agent_url_needs_scheme),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (!isHttpScheme(endpointUrl)) {
                            Text(
                                stringResource(R.string.agent_url_bad_scheme),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = endpointModel,
                        onValueChange = { endpointModel = it },
                        label = { Text(stringResource(R.string.settings_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    if (endpointDetecting) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            stringResource(R.string.agent_endpoint_detecting),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (endpointDetectFailed) {
                        Text(
                            stringResource(R.string.agent_endpoint_detect_fail),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (endpointDetectNote.isNotBlank()) {
                            Text(
                                endpointDetectNote,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            stringResource(R.string.settings_gateway_protocol),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
                            Column {
                                listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                                    Row(
                                        Modifier.fillMaxWidth().clickable { endpointDsh = option }.padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(option, Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                        AgentSelectionDot(endpointDsh == option)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.settings_api_key),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (endpointKeyNew) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = endpointNewKeyName,
                                onValueChange = { endpointNewKeyName = it },
                                label = { Text(stringResource(R.string.settings_key_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            OutlinedTextField(
                                value = endpointNewKeySecret,
                                onValueChange = { endpointNewKeySecret = it },
                                label = { Text(stringResource(R.string.settings_api_key)) },
                                singleLine = true,
                                visualTransformation = if (endpointNewKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { endpointNewKeyVisible = !endpointNewKeyVisible }) {
                                        Icon(
                                            if (endpointNewKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                            stringResource(R.string.agent_toggle_visibility),
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Text(
                                stringResource(R.string.agent_key_back_saved),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = PocketAccent,
                                modifier = Modifier
                                    .clickable { endpointKeyNew = false }
                                    .align(Alignment.Start)
                                    .padding(vertical = 2.dp),
                            )
                        }
                    } else {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable { endpointKeyName = "" }.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.agent_endpoint_current_key),
                                        Modifier.weight(1f),
                                        fontSize = 12.sp,
                                    )
                                    AgentSelectionDot(endpointKeyName == "")
                                }
                                savedKeys.forEach { key ->
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                    Column(
                                        Modifier.fillMaxWidth().clickable { endpointKeyName = key.name }.padding(horizontal = 12.dp, vertical = 7.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(key.name, Modifier.weight(1f), fontSize = 12.sp)
                                            AgentSelectionDot(endpointKeyName == key.name)
                                        }
                                        endpointKeySecrets.firstOrNull { it.id == key.id }?.let { credential ->
                                            Row(
                                                Modifier.padding(top = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    credential.secret,
                                                    Modifier.weight(1f),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 2,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                IconButton(
                                                    onClick = {
                                                        dialogClipboard.setText(AnnotatedString(credential.secret))
                                                        copiedKeyId = key.id
                                                    },
                                                    modifier = Modifier.size(28.dp),
                                                ) {
                                                    Icon(
                                                        if (copiedKeyId == key.id) Icons.Default.Check else Icons.Default.ContentCopy,
                                                        stringResource(R.string.agent_key_copy),
                                                        Modifier.size(15.dp),
                                                        tint = if (copiedKeyId == key.id) PocketAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                Row(
                                    Modifier.fillMaxWidth().clickable { endpointKeyNew = true }.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        stringResource(R.string.agent_key_add_new),
                                        Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        color = PocketAccent,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(Icons.Default.Add, null, Modifier.size(15.dp), tint = PocketAccent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            // A key typed in the dialog joins the pool first, so the
                            // endpoint can reference it by name like any other key.
                            if (endpointKeyNew && endpointNewKeySecret.isNotBlank()) {
                                val name = endpointNewKeyName.trim().ifBlank {
                                    context.getString(R.string.agent_key_for, selectedKind.title)
                                }
                                savedKeys = onAddApiKey(selectedKind, name, endpointNewKeySecret.trim())
                                endpointKeyName = name
                                endpointKeySecrets = onListKeySecrets(selectedKind)
                                endpointKeyNew = false
                                endpointNewKeyName = ""
                                endpointNewKeySecret = ""
                                endpointNewKeyVisible = false
                            }
                            if (endpointDetectFailed) {
                                persistEndpoint(R.string.agent_endpoint_saved)
                            } else if (!endpointDetecting) {
                                endpointDetecting = true
                                val detected = onDetectEndpoint(withDefaultScheme(endpointUrl), endpointKeyName)
                                endpointDetecting = false
                                if (detected.dshApi != null) {
                                    endpointDsh = detected.dshApi
                                    if (endpointModel.isBlank()) {
                                        endpointModel = detected.models.firstOrNull()?.id.orEmpty()
                                    }
                                    persistEndpoint(
                                        R.string.agent_endpoint_saved_detected,
                                        detected.dshApi,
                                        detected.models.size,
                                    )
                                } else {
                                    endpointDetectFailed = true
                                    endpointDetectNote = detected.failure.orEmpty()
                                    if (endpointDsh.isBlank()) endpointDsh = inferredDshApiForUrl(endpointUrl)
                                    if (endpointModel.isBlank()) {
                                        endpointModel = detected.models.firstOrNull()?.id.orEmpty()
                                    }
                                }
                            }
                        }
                    },
                    enabled = endpointLabel.isNotBlank() && endpointUrl.isNotBlank() && !endpointDetecting &&
                        (!endpointKeyNew || endpointNewKeySecret.isNotBlank()),
                ) {
                    Text(stringResource(R.string.proj_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showEndpointEditor = false },
                    enabled = !endpointDetecting,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    if (pendingDeleteEndpointId.isNotEmpty()) {
        val target = state.customEndpoints.firstOrNull { it.id == pendingDeleteEndpointId }
        AlertDialog(
            onDismissRequest = { pendingDeleteEndpointId = "" },
            title = { Text(stringResource(R.string.agent_endpoint_delete)) },
            text = { Text(stringResource(R.string.agent_endpoint_delete_ask, target?.label.orEmpty())) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteCustomEndpoint(pendingDeleteEndpointId)
                    pendingDeleteEndpointId = ""
                }) {
                    Text(stringResource(R.string.agent_endpoint_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteEndpointId = "" }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    // ── Provider Models Modal Bottom Sheet ──
    if (showModels) {
        ModalBottomSheet(
            onDismissRequest = { showModels = false },
            sheetState = providerSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.82f)
                    .padding(horizontal = 20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.agent_available_models),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3f).sp,
                        )
                        Text(
                            if (models.isEmpty()) {
                                selectedKind.title
                            } else {
                                stringResource(
                                    R.string.agent_verified_avg,
                                    models.size - statsBroken,
                                    models.size,
                                    statsAvgLatencyLabel,
                                )
                            },
                            fontSize = 13.sp,
                            color = PocketMuted,
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = PocketSurfaceVariant,
                        border = BorderStroke(1.dp, PocketOutline),
                        enabled = !isDiscovering && !state.isModelScanning,
                        onClick = ::runDiscoverOrTest,
                        modifier = Modifier.padding(start = 12.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (isDiscovering || state.isModelScanning) {
                                CircularProgressIndicator(
                                    Modifier.size(14.dp),
                                    strokeWidth = 1.6.dp,
                                    color = PocketMuted,
                                )
                            } else {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (models.isEmpty()) R.string.agent_discover_models else R.string.agent_test_short,
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                            )
                        }
                    }
                }
                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(14.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(PocketSurfaceVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((models.size - statsBroken).toFloat() / models.size)
                                .fillMaxHeight()
                                .background(PocketAccent),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    ModelStatsGrid(
                        working = models.size - statsBroken,
                        broken = statsBroken,
                        avgLatencyMs = statsAvgLatencyMs,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = modelSearch,
                    onValueChange = { modelSearch = it },
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = PocketMuted) },
                    placeholder = { Text(stringResource(R.string.agent_search_custom), color = Color(0xFF6B6B76)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketAccent.copy(alpha = 0.5f),
                        unfocusedBorderColor = PocketCardBorder,
                        focusedContainerColor = Color(0xFF1C1C22),
                        unfocusedContainerColor = Color(0xFF1C1C22),
                    ),
                )
                Spacer(Modifier.height(12.dp))

                // Status filter pills (mock: all / working / broken / free) + autoscan toggle.
                val statusChips = listOf(
                    FILTER_ALL to stringResource(R.string.agent_filter_all),
                    FILTER_WORKING to stringResource(R.string.agent_filter_working),
                    FILTER_BROKEN to stringResource(R.string.agent_filter_broken),
                    FILTER_FREE to stringResource(R.string.agent_filter_free),
                )
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    statusChips.forEach { (id, label) ->
                        item(key = id) {
                            PillToggleChip(
                                text = label,
                                active = statusFilter == id,
                                onClick = {
                                    statusFilter = id
                                    onHideBrokenChange(id == FILTER_WORKING)
                                },
                            )
                        }
                    }
                    item(key = "autoscan") {
                        PillToggleChip(
                            text = stringResource(R.string.agent_autoscan),
                            active = state.autoScanEnabled,
                            onClick = { onAutoScanChange(!state.autoScanEnabled) },
                        )
                    }
                }

                if (models.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1C1C22),
                        border = BorderStroke(1.dp, PocketCardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Box(
                                Modifier
                                    .size(24.dp)
                                    .background(Color(0xFF23232E), CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    null,
                                    tint = PocketMuted,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                stringResource(R.string.agent_discover_hint, models.size, state.modelCatalogs.size),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                color = Color(0xFFB8B8C2),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))

                // Keep the log open while a scan runs so progress stays visible.
                LaunchedEffect(state.isModelScanning) {
                    if (state.isModelScanning) showScanLog = true
                }
                if (state.modelScanLines.isNotEmpty() || state.isModelScanning) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF111113),
                        border = BorderStroke(1.dp, Color(0xFF222222)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showScanLog = !showScanLog },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(20.dp).background(PocketSurfaceVariant, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "_",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = PocketMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.agent_log_details),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFC9C9D1),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.agent_log_latest),
                                fontSize = 11.sp,
                                color = Color(0xFF6B6B76),
                            )
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (showScanLog) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    if (showScanLog) R.string.settings_collapse else R.string.settings_expand,
                                ),
                                tint = Color(0xFF4B4B55),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    if (showScanLog) {
                        Spacer(Modifier.height(8.dp))
                        ModelScanTerminal(
                            lines = state.modelScanLines,
                            scanning = state.isModelScanning,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (status != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (statusOk) PocketAccent.copy(alpha = 0.09f)
                        else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        border = BorderStroke(
                            1.dp,
                            if (statusOk) PocketAccent.copy(alpha = 0.28f)
                            else MaterialTheme.colorScheme.error.copy(alpha = 0.35f),
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isDiscovering) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 1.6.dp,
                                        color = PocketAccent,
                                    )
                                } else {
                                    Icon(
                                        if (statusOk) Icons.Default.Info else Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = if (statusOk) PocketAccent else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(15.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    status.orEmpty(),
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            statusProviderMessage?.let { providerMessage ->
                                Text(
                                    stringResource(R.string.agent_provider_msg, providerMessage),
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 23.dp, top = 5.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                if (!isDiscovering) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "${stringResource(R.string.agent_models_list)} · ${filteredModels.size}".uppercase(java.util.Locale.ROOT),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp,
                        color = Color(0xFF6B6B76),
                    )
                }

                if (isDiscovering) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(Modifier.size(32.dp), color = PocketAccent, strokeWidth = 3.dp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                stringResource(R.string.agent_discovering_from, selectedKind.title),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (filteredModels.isEmpty()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (modelSearch.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PocketAccent.copy(alpha = 0.12f),
                                border = BorderStroke(1.dp, PocketAccent.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = modelSearch.trim()
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Default.Check, null, tint = PocketAccent, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.agent_use_custom_id), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketAccent)
                                    }
                                }
                            }
                        }


                        SecondaryActionButton(
                            onClick = ::discoverModels,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.agent_discover_api))
                        }

                        val recommended = remember(selectedKind) { defaultModelsForProvider(selectedKind) }
                        if (recommended.isNotEmpty()) {
                            Text(
                                stringResource(R.string.agent_recommended_models).uppercase(java.util.Locale.ROOT),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp,
                                color = PocketMuted,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            recommended.forEach { opt ->
                                val isSelected = model == opt.id
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isSelected) PocketAccent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) PocketAccent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = opt.id
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                opt.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = if (isSelected) PocketAccent else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                opt.id,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        AgentSelectionDot(selected = isSelected)
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (modelSearch.isNotBlank() && filteredModels.none { it.id.equals(modelSearch.trim(), ignoreCase = true) }) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = PocketAccent.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, PocketAccent.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            model = modelSearch.trim()
                                            modelSearch = ""
                                            showModels = false
                                        },
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.Check, null, tint = PocketAccent, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(stringResource(R.string.agent_use_custom_id), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text(modelSearch.trim(), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PocketAccent)
                                        }
                                    }
                                }
                            }
                        }
                        items(filteredModels, key = { it.id }) { option ->
                            val isSelected = model == option.id
                            val isBrokenModel = option.isBroken || option.id in activeBrokenIds
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) Color(0xFF20202A) else Color(0xFF1A1A20),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) PocketAccent.copy(alpha = 0.4f) else PocketCardBorder,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        model = option.id
                                        modelSearch = ""
                                        showModels = false
                                    },
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    option.latencyLabel?.let { label ->
                                        val badge = latencyColor(option)
                                        Surface(
                                            shape = CircleShape,
                                            color = badge.copy(alpha = 0.13f),
                                            border = BorderStroke(1.dp, badge.copy(alpha = 0.22f)),
                                        ) {
                                            Text(
                                                label,
                                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                fontFamily = FontFamily.Monospace,
                                                color = badge,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                option.displayName,
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (option.isFree) {
                                                Spacer(Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0x1F34D399),
                                                    border = BorderStroke(1.dp, Color(0x3334D399)),
                                                ) {
                                                    Text(
                                                        stringResource(R.string.agent_free),
                                                        Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = 0.5.sp,
                                                        color = Color(0xFF6EE7B7),
                                                        maxLines = 1,
                                                    )
                                                }
                                            }
                                        }
                                        if (option.displayName != option.id) {
                                            Text(
                                                option.id,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF7A7A86),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    Box(
                                        Modifier
                                            .size(6.dp)
                                            .background(
                                                if (isBrokenModel) Color(0xFFF87171) else Color(0xFF34D399),
                                                CircleShape,
                                            ),
                                    )
                                    AgentSelectionDot(selected = isSelected)
                                }
                            }
                        }

                        if (state.modelCatalogs.isNotEmpty()) {
                            item(key = "saved-endpoints") {
                                Column(Modifier.padding(top = 16.dp)) {
                                    Text(
                                        stringResource(R.string.agent_endpoints).uppercase(java.util.Locale.ROOT),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp,
                                        color = Color(0xFF6B6B76),
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = PocketSurfaceVariant,
                                        border = BorderStroke(1.dp, PocketCardBorder),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column {
                                            state.modelCatalogs.forEachIndexed { index, catalog ->
                                                if (index > 0) {
                                                    HorizontalDivider(color = Color(0xFF22222B))
                                                }
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                                ) {
                                                    Box(
                                                        Modifier
                                                            .size(32.dp)
                                                            .background(PocketSurfaceVariant, RoundedCornerShape(10.dp))
                                                            .border(1.dp, PocketOutline, RoundedCornerShape(10.dp)),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Link,
                                                            null,
                                                            tint = PocketMuted,
                                                            modifier = Modifier.size(16.dp),
                                                        )
                                                    }
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            hostOnly(catalog.baseUrl),
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Medium,
                                                            color = Color.White,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        Text(
                                                            stringResource(
                                                                R.string.agent_catalog_line,
                                                                catalog.kindName,
                                                                catalog.models.size,
                                                            ),
                                                            fontSize = 11.sp,
                                                            color = Color(0xFF7A7A86),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = { onDeleteModelCatalog(catalog.key) },
                                                        modifier = Modifier.size(28.dp),
                                                    ) {
                                                        Icon(
                                                            Icons.Default.Delete,
                                                            contentDescription = stringResource(R.string.agent_delete_list),
                                                            tint = PocketMuted,
                                                            modifier = Modifier.size(14.dp),
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.padding(top = 4.dp),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    Brush.linearGradient(
                                        listOf(PocketAccent, PocketAccentTeal),
                                    ),
                                    RoundedCornerShape(14.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Default.SmartToy,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                stringResource(R.string.agent_ai_agent),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                                letterSpacing = (-0.4).sp,
                                color = Color(0xFFF5F5F7),
                            )
                            Text(
                                if (isAntigravity) {
                                    "Antigravity · ${formatAntigravityModelName(state.antigravityModel)}"
                                } else {
                                    "${state.agentKind.title} · ${model.ifBlank { selectedKind.title }}"
                                },
                                fontSize = 13.sp,
                                color = PocketMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    // Top Bar Live Status Pill
                    Surface(
                        color = pillBg,
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.dp, dot.copy(alpha = 0.35f)),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(6.5.dp).background(dot, CircleShape))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (pillLoading) stringResource(R.string.agent_checking) else label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = dot,
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 1. Compact 3-Way Segmented Engine Selector ──
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF1E1E24),
                        border = BorderStroke(1.dp, PocketCardBorder),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            orderedAgents.forEach { agent ->
                                val isSelected = viewedAgent == agent
                                val isInstalled = agent == state.agentKind || state.installedAgentVersions.containsKey(agent)
                                val updateAvailable = state.agentUpdates.containsKey(agent)
                                val pendingChangeCount = state.pendingChangesByAgent[agent] ?: 0
                                val shortTitle = when (agent) {
                                    AgentKind.ANTIGRAVITY -> "Antigravity"
                                    AgentKind.DEEPSEEK_HARNESS -> "DeepSeek"
                                    AgentKind.CLAUDE_CODE -> "Claude Code"
                                    AgentKind.OPENCODE -> "OpenCode"
                                    AgentKind.HERMES -> "Hermes"
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable(enabled = state.agentInstalling == null) {
                                            viewedAgent = agent
                                            if (isInstalled) onSelectAgent(agent)
                                        },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                shortTitle,
                                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                                fontSize = 12.sp,
                                                color = if (isSelected) Color.Black else PocketMuted,
                                                maxLines = 1,
                                            )
                                            if (pendingChangeCount > 0) {
                                                Spacer(Modifier.width(4.dp))
                                                Surface(
                                                    shape = CircleShape,
                                                    color = MaterialTheme.colorScheme.primaryContainer,
                                                ) {
                                                    Text(
                                                        pendingChangeCount.toString(),
                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                        fontSize = 9.sp,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    )
                                                }
                                            }
                                            if (updateAvailable) {
                                                Spacer(Modifier.width(3.dp))
                                                Box(Modifier.size(5.dp).background(PocketAccent, CircleShape))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.agentInstalling == viewedAgent) {
                        Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        state.agentMessage ?: stringResource(R.string.agent_installing),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(
                                    progress = { state.agentProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val downloaded = state.agentDownloadedBytes
                                val total = state.agentTotalBytes
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth()) {
                                    Text(
                                        if (downloaded != null || total != null) {
                                            buildString {
                                                append(formatAgentBytes(downloaded ?: 0L))
                                                total?.let { append(" / ${formatAgentBytes(it)}") }
                                            }
                                        } else {
                                            stringResource(R.string.agent_processing_files)
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        buildString {
                                            append("${(state.agentProgress * 100).toInt()}%")
                                            state.agentBytesPerSecond?.takeIf { it > 0L }?.let {
                                                append(" · ${formatAgentBytes(it)}/s")
                                            }
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    } else if (!viewedAgentInstalled) {
                        Spacer(Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, PocketCardBorder),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    stringResource(R.string.agent_not_installed, viewedAgent.title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    stringResource(R.string.agent_install_package, viewedAgent.downloadNote),
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                Button(
                                    onClick = { onInstallAgent(viewedAgent) },
                                    enabled = state.agentInstalling == null,
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                ) {
                                    Text(stringResource(R.string.agent_install_btn, viewedAgent.title), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Primary Configuration Card (Antigravity OR Provider) ──
            item {
                if (!viewedAgentInstalled || viewedAgent != state.agentKind) {
                    // Installation/selection guidance is shown directly below the tabs.
                } else if (state.agentKind == AgentKind.ANTIGRAVITY) {
                    AgentAntigravityCard(
                        state = state,
                        code = antigravityCode,
                        onCode = { antigravityCode = it },
                        onStartLogin = onStartAntigravityLogin,
                        onSubmitCode = { onSubmitAntigravityCode(antigravityCode); antigravityCode = "" },
                        onLogout = onLogoutAntigravity,
                        onRefreshModels = onRefreshAntigravityModels,
                        onOpenModelSheet = { showAntigravityModelSheet = true },
                        onSetEffort = onSetAntigravityEffort,
                        onTest = onPing,
                    )
                } else {
                    AgentProviderCard(
                        state = state,
                        selectedKind = selectedKind,
                        baseUrl = baseUrl,
                        model = model,
                        dshApi = dshApi,
                        apiKey = apiKey,
                        models = models,
                        isDiscovering = isDiscovering,
                        isValidating = isValidating,
                        status = status,
                        statusOk = statusOk,
                        statusProviderMessage = statusProviderMessage,
                        keyConnectionStatuses = keyConnectionStatuses,
                        savedKeys = savedKeys,
                        onListKeySecrets = onListKeySecrets,
                        newKeyName = newKeyName,
                        newApiKey = newApiKey,
                        newKeyVisible = newKeyVisible,
                        onProvider = { kind ->
                            selectedKind = kind
                            baseUrl = kind.defaultBaseUrl
                            model = kind.defaultModel
                            dshApi = defaultDshApiForProvider(kind)
                            modelSearch = ""
                            showModels = false
                            newKeyName = ""
                            newApiKey = ""
                            status = null
                            statusProviderMessage = null
                        },
                        onBaseUrl = {
                            baseUrl = it
                            if (selectedKind == ProviderKind.CUSTOM) {
                                dshApi = inferredDshApiForUrl(it)
                            }
                            status = null
                            statusProviderMessage = null
                            keyConnectionStatuses = emptyMap()
                        },
                        onModel = { model = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onDshApi = { dshApi = it; status = null; statusProviderMessage = null; keyConnectionStatuses = emptyMap() },
                        onNewKeyName = { newKeyName = it },
                        onNewApiKey = { newApiKey = it },
                        onToggleNewKey = { newKeyVisible = !newKeyVisible },
                        onAddKey = {
                            savedKeys = onAddApiKey(selectedKind, newKeyName, newApiKey.trim())
                            newKeyName = ""
                            newApiKey = ""
                            apiKey = getSavedApiKey(selectedKind)
                            status = context.getString(R.string.agent_key_added, selectedKind.title)
                            statusOk = true
                        },
                        onActivateKey = { keyId ->
                            savedKeys = onActivateApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            status = context.getString(R.string.agent_key_activated, selectedKind.title)
                            statusOk = true
                        },
                        onRemoveKey = { keyId ->
                            savedKeys = onRemoveApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            keyConnectionStatuses = keyConnectionStatuses - keyId
                            status = context.getString(R.string.agent_key_removed, selectedKind.title)
                            statusOk = true
                        },
                        customEndpoints = state.customEndpoints,
                        onApplyEndpoint = { ep ->
                            baseUrl = ep.baseUrl
                            model = ep.model
                            dshApi = ep.dshApi
                            val keyId = savedKeys.firstOrNull { it.name == ep.keyName }?.id
                            if (keyId != null) savedKeys = onActivateApiKey(selectedKind, keyId)
                            apiKey = getSavedApiKey(selectedKind)
                            onSaveProvider(
                                ProviderProfile(selectedKind, ep.baseUrl, ep.model, dshApi = ep.dshApi),
                                apiKey.trim(),
                            )
                            status = context.getString(R.string.agent_endpoint_applied, ep.label)
                            statusOk = true
                        },
                        onCreateEndpoint = {
                            editingEndpointId = ""
                            endpointLabel = ""
                            endpointUrl = baseUrl
                            endpointModel = model
                            endpointDsh = dshApi
                            endpointKeyName = savedKeys.firstOrNull { it.isActive }?.name ?: ""
                            endpointDetectFailed = false
                            endpointDetectNote = ""
                            endpointKeyNew = false
                            endpointNewKeyName = ""
                            endpointNewKeySecret = ""
                            endpointNewKeyVisible = false
                            showEndpointEditor = true
                        },
                        onEditEndpoint = { ep ->
                            editingEndpointId = ep.id
                            endpointLabel = ep.label
                            endpointUrl = ep.baseUrl
                            endpointModel = ep.model
                            endpointDsh = ep.dshApi
                            endpointKeyName = ep.keyName
                            endpointDetectFailed = false
                            endpointDetectNote = ""
                            endpointKeyNew = false
                            endpointNewKeyName = ""
                            endpointNewKeySecret = ""
                            endpointNewKeyVisible = false
                            showEndpointEditor = true
                        },
                        onDeleteEndpoint = { pendingDeleteEndpointId = it },
                        onOpenModelSheet = {
                            showModels = true
                        },
                        onDiscover = ::discoverModels,
                        onValidate = {
                            scope.launch {
                                isValidating = true
                                val activeKeyId = savedKeys.firstOrNull { it.isActive }?.id
                                if (activeKeyId != null) {
                                    keyConnectionStatuses = keyConnectionStatuses +
                                        (activeKeyId to KeyConnectionStatus(context.getString(R.string.agent_checking_connection)))
                                    status = null
                                } else {
                                    status = context.getString(R.string.agent_checking_connection)
                                    statusOk = true
                                }
                                val kind = selectedKind
                                val url = if (kind.fixedBaseUrl) kind.defaultBaseUrl else withDefaultScheme(baseUrl)
                                val profile = ProviderProfile(kind, url, model.trim(), dshApi = dshApi)
                                if (kind == ProviderKind.CLAUDE) {
                                    onSaveProvider(profile, apiKey.trim())
                                    status = context.getString(R.string.agent_claude_hint)
                                    statusOk = true
                                    activeKeyId?.let {
                                        keyConnectionStatuses = keyConnectionStatuses +
                                            (it to KeyConnectionStatus(context.getString(R.string.agent_token_saved), true, label = context.getString(R.string.agent_saved)))
                                    }
                                } else when (val result = onValidateProvider(profile, apiKey.trim(), models)) {
                                    is ConnectionValidation.Success -> {
                                        onSaveProvider(profile, apiKey.trim())
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, true, label = context.getString(R.string.agent_verified)))
                                        } else {
                                            status = result.message
                                            statusOk = true
                                        }
                                    }
                                    is ConnectionValidation.Failure -> {
                                        if (activeKeyId != null) {
                                            keyConnectionStatuses = keyConnectionStatuses +
                                                (activeKeyId to KeyConnectionStatus(result.message, false, result.providerMessage, result.label))
                                        } else {
                                            status = result.message
                                            statusOk = false
                                            statusProviderMessage = result.providerMessage
                                        }
                                    }
                                }
                                isValidating = false
                            }
                        },
                    )
                }
            }

            // ── 3. Runtime Updates Card ──
            item {
                AgentUpdateBlock(
                    state = state,
                    onCheck = onCheckAgentUpdates,
                    onUpdate = onUpdateAgent,
                )
            }

            // ── 4. Subtle Footer ──
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.agent_apikeys_note),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

/**
 * Modern Antigravity Configuration Bento Card.
 */
@Composable
private fun AgentAntigravityCard(
    state: AppUiState,
    code: String,
    onCode: (String) -> Unit,
    onStartLogin: () -> Unit,
    onSubmitCode: () -> Unit,
    onLogout: () -> Unit,
    onRefreshModels: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onSetEffort: (String) -> Unit,
    onTest: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val auth = state.antigravityAuth

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, PocketCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.agent_google_account), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            // Google Account Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF34A853).copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF34A853).copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("G", color = Color(0xFF34A853), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    val email = auth.accountEmail
                    Text(
                        email ?: if (auth.status == AntigravityAuthStatus.SIGNED_IN) stringResource(R.string.agent_connected) else stringResource(R.string.agent_not_signed_in),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (auth.status == AntigravityAuthStatus.SIGNED_IN) stringResource(R.string.agent_connected_google) else stringResource(R.string.agent_antigravity_required),
                        fontSize = 11.sp,
                        color = if (auth.status == AntigravityAuthStatus.SIGNED_IN) Color(0xFF2E9D72) else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                    Text(
                        stringResource(R.string.agent_disconnect),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { onLogout() }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }

            // Authentication actions if not signed in
            when (auth.status) {
                AntigravityAuthStatus.STARTING, AntigravityAuthStatus.COMPLETING -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                AntigravityAuthStatus.AWAITING_CODE -> {
                    auth.authorizationUrl?.let { url ->
                        OutlinedButton(
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Text(stringResource(R.string.settings_copy_signin_url))
                        }
                    }
                    OutlinedTextField(
                        value = code,
                        onValueChange = onCode,
                        label = { Text(stringResource(R.string.settings_auth_code)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    )
                    Button(
                        onClick = onSubmitCode,
                        enabled = code.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(stringResource(R.string.settings_complete_signin))
                    }
                }
                AntigravityAuthStatus.SIGNED_OUT, AntigravityAuthStatus.ERROR -> {
                    Button(
                        onClick = onStartLogin,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(if (auth.status == AntigravityAuthStatus.ERROR) stringResource(R.string.settings_reconnect_google) else stringResource(R.string.settings_signin_google))
                    }
                }
                AntigravityAuthStatus.SIGNED_IN -> {}
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Active Intelligence Model Tile
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.agent_active_model),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = !state.antigravityModelsLoading) { onRefreshModels() }
                                .padding(start = 6.dp, top = 2.dp, bottom = 2.dp),
                        ) {
                            if (state.antigravityModelsLoading) {
                                CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.4.dp)
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                stringResource(R.string.agent_sync),
                                fontSize = 11.sp,
                                color = PocketAccent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenModelSheet() },
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(PocketAccent.copy(alpha = 0.12f), RoundedCornerShape(9.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = PocketAccent,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                val currentModel = state.antigravityModel.ifBlank { stringResource(R.string.agent_select_model) }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        formatAntigravityModelName(currentModel),
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    currentModel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = stringResource(R.string.agent_choose_model),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Reasoning Depth (Effort) Segmented Capsule
                Column(modifier = Modifier.fillMaxWidth()) {
                    val effortCaption = when (state.antigravityEffort) {
                        "low" -> stringResource(R.string.agent_effort_fast)
                        "medium" -> stringResource(R.string.agent_effort_balanced)
                        "high" -> stringResource(R.string.agent_effort_deep)
                        else -> stringResource(R.string.agent_effort_balanced)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.agent_reasoning_depth),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            effortCaption,
                            fontSize = 11.sp,
                            color = PocketAccent,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            listOf("low", "medium", "high").forEach { effort ->
                                val isSelected = state.antigravityEffort == effort
                                Surface(
                                    shape = RoundedCornerShape(9.dp),
                                    color = if (isSelected) PocketAccent else Color.Transparent,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onSetEffort(effort) },
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 7.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            effort.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() },
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color(0xFF241107) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (auth.status == AntigravityAuthStatus.SIGNED_IN) {
                SecondaryActionButton(
                    onClick = onTest,
                    enabled = state.apiPingStatus != ApiPingStatus.PINGING,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    if (state.apiPingStatus == ApiPingStatus.PINGING) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketMuted)
                        Spacer(Modifier.width(8.dp))
                    } else {
                        Icon(Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketMuted)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (state.apiPingStatus == ApiPingStatus.PINGING) stringResource(R.string.agent_testing_connection) else stringResource(R.string.agent_test_connection),
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                state.apiPingMessage?.takeIf { state.apiPingStatus != ApiPingStatus.IDLE }?.let { message ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.apiPingStatus == ApiPingStatus.FAILED) Icons.Default.Warning else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else Color(0xFF2E9D72),
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            message,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = if (state.apiPingStatus == ApiPingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            Text(
                stringResource(R.string.agent_auto_approval),
                fontSize = 10.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AgentProviderCard(
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
    statusProviderMessage: String?,
    keyConnectionStatuses: Map<String, KeyConnectionStatus>,
    savedKeys: List<ApiKeyInfo>,
    onListKeySecrets: suspend (ProviderKind) -> List<ApiKeyCredential>,
    newKeyName: String,
    newApiKey: String,
    newKeyVisible: Boolean,
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
    customEndpoints: List<CustomEndpoint>,
    onApplyEndpoint: (CustomEndpoint) -> Unit,
    onCreateEndpoint: () -> Unit,
    onEditEndpoint: (CustomEndpoint) -> Unit,
    onDeleteEndpoint: (String) -> Unit,
    onOpenModelSheet: () -> Unit,
    onDiscover: () -> Unit,
    onValidate: () -> Unit,
) {
    val context = LocalContext.current
    val visibleKinds = remember(state.agentKind) { providersForAgent(state.agentKind) }
    var connectionExpanded by rememberSaveable(selectedKind) { mutableStateOf(false) }
    // Reset the filter when the Agent changes so a stale query can't hide every provider.
    var providerQuery by rememberSaveable(state.agentKind) { mutableStateOf("") }
    // Keep this state across provider changes so selecting Custom API can
    // immediately reveal its required setup instead of resetting on recomposition.
    var endpointExpanded by rememberSaveable(state.agentKind) { mutableStateOf(false) }
    var keysExpanded by rememberSaveable(state.agentKind, selectedKind) { mutableStateOf(false) }
    var addKeyExpanded by rememberSaveable(savedKeys.isEmpty()) { mutableStateOf(savedKeys.isEmpty()) }
    val activeKey = savedKeys.firstOrNull { it.isActive }
    val activeKeyStatus = activeKey?.let { keyConnectionStatuses[it.id] }
    // Secrets are revealed only while the credentials card is open, so a collapsed
    // card never keeps decrypted keys in composition state.
    val keyClipboard = LocalClipboardManager.current
    var keySecrets by remember { mutableStateOf<List<ApiKeyCredential>>(emptyList()) }
    var keyCopiedId by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(keysExpanded, selectedKind, savedKeys.size) {
        keySecrets = if (keysExpanded) onListKeySecrets(selectedKind) else emptyList()
    }
    LaunchedEffect(keyCopiedId) {
        if (keyCopiedId.isNotEmpty()) {
            delay(1_500)
            keyCopiedId = ""
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, PocketCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Text(
                stringResource(R.string.agent_ai_provider).uppercase(java.util.Locale.ROOT),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                color = PocketMuted,
            )
            Spacer(Modifier.height(2.dp))

            PremiumSummaryRow(
                icon = Icons.Default.Link,
                title = selectedKind.title,
                subtitle = selectedKind.subtitle,
                expanded = connectionExpanded,
                onClick = { connectionExpanded = !connectionExpanded },
            )

            AnimatedVisibility(connectionExpanded) {
                Column(
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val query = providerQuery.trim()
                    val filteredKinds = if (query.isEmpty()) {
                        visibleKinds
                    } else {
                        visibleKinds.filter {
                            it.title.contains(query, ignoreCase = true) ||
                                it.subtitle.contains(query, ignoreCase = true)
                        }
                    }
                    // Only the fuller provider lists are worth filtering; a short list stays one tap away.
                    if (visibleKinds.size > 6) {
                        OutlinedTextField(
                            value = providerQuery,
                            onValueChange = { providerQuery = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(15.dp)) },
                            placeholder = { Text(stringResource(R.string.agent_provider_search), fontSize = 12.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f),
                    ) {
                        Column {
                            if (filteredKinds.isEmpty()) {
                                Text(
                                    stringResource(R.string.agent_provider_no_match),
                                    fontSize = 12.sp,
                                    color = PocketMuted,
                                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 14.dp),
                                )
                            }
                            filteredKinds.forEachIndexed { index, kind ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            onProvider(kind)
                                            connectionExpanded = false
                                            endpointExpanded = kind == ProviderKind.CUSTOM
                                        }
                                        .padding(horizontal = 13.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(kind.title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(kind.subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    AgentSelectionDot(selectedKind == kind)
                                }
                                if (index != filteredKinds.lastIndex) {
                                    HorizontalDivider(Modifier.padding(start = 13.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                }
                            }
                        }
                    }

                }
            }

            if (selectedKind != ProviderKind.CLAUDE) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                PremiumSummaryRow(
                    icon = Icons.Default.Info,
                    title = if (selectedKind == ProviderKind.CUSTOM) stringResource(R.string.agent_custom_settings) else stringResource(R.string.agent_endpoint_protocol),
                    subtitle = buildString {
                        append(baseUrl.ifBlank { stringResource(R.string.agent_base_url_required) })
                        if (selectedKind in DSH_PROTOCOL_PROVIDERS) {
                            append(" · ")
                            append(if (selectedKind.fixedProtocol) defaultDshApiForProvider(selectedKind) else dshApi)
                        }
                    },
                    expanded = endpointExpanded,
                    onClick = { endpointExpanded = !endpointExpanded },
                )

                AnimatedVisibility(endpointExpanded) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (selectedKind == ProviderKind.CUSTOM) {
                            Text(
                                stringResource(R.string.agent_provider_hint),
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Row(
                                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            stringResource(R.string.agent_endpoints).uppercase(java.util.Locale.ROOT),
                                            Modifier.weight(1f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            letterSpacing = 1.sp,
                                            color = PocketMuted,
                                        )
                                        Text(
                                            stringResource(R.string.agent_endpoint_new),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = PocketAccent,
                                            modifier = Modifier.clickable(onClick = onCreateEndpoint).padding(4.dp),
                                        )
                                    }
                                    if (customEndpoints.isEmpty()) {
                                        Text(
                                            stringResource(R.string.agent_endpoint_empty),
                                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    customEndpoints.forEach { ep ->
                                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(
                                                Modifier
                                                    .weight(1f)
                                                    .clickable { onApplyEndpoint(ep) }
                                                    .padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                                            ) {
                                                Text(
                                                    ep.label,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    listOf(hostOnly(ep.baseUrl), ep.model).filter(String::isNotBlank).joinToString(" · "),
                                                    fontSize = 11.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            IconButton(onClick = { onEditEndpoint(ep) }) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    stringResource(R.string.agent_endpoint_title),
                                                    modifier = Modifier.size(17.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            IconButton(onClick = { onDeleteEndpoint(ep.id) }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    stringResource(R.string.agent_endpoint_delete),
                                                    modifier = Modifier.size(17.dp),
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val presets = remember(selectedKind) { presetEndpointsFor(selectedKind) }
                        val baseUrlForPreview = if (selectedKind.fixedBaseUrl) selectedKind.defaultBaseUrl else baseUrl
                        val previewProtocol = providerProtocolForAgent(
                            ProviderProfile(selectedKind, baseUrlForPreview, model, dshApi = dshApi),
                            state.agentKind,
                        )
                        val requestPreview = remember(baseUrlForPreview, previewProtocol) {
                            if (baseUrlForPreview.isBlank()) {
                                ""
                            } else {
                                ProviderApiClient().requestPreviewUrl(baseUrlForPreview, previewProtocol)
                            }
                        }
                        val urlSupporting: @Composable (() -> Unit)? = when {
                            selectedKind.fixedBaseUrl -> ({
                                Text(stringResource(R.string.agent_fixed_by, selectedKind.title))
                            })
                            baseUrl.isNotBlank() && schemeOf(baseUrl).isEmpty() -> ({
                                Text(stringResource(R.string.agent_url_needs_scheme), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            })
                            baseUrl.isNotBlank() && !isHttpScheme(baseUrl) -> ({
                                Text(stringResource(R.string.agent_url_bad_scheme), color = MaterialTheme.colorScheme.error)
                            })
                            requestPreview.isNotEmpty() -> ({
                                Text(
                                    stringResource(R.string.agent_url_preview, requestPreview),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            })
                            else -> null
                        }
                        val presetTrailing: @Composable (() -> Unit)? = if (presets.isNotEmpty() && !selectedKind.fixedBaseUrl) {
                            {
                                var presetMenu by rememberSaveable(selectedKind) { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { presetMenu = true }) {
                                        Icon(
                                            Icons.Default.KeyboardArrowDown,
                                            contentDescription = stringResource(R.string.agent_endpoint_presets),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                                        presets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(preset.labelRes), fontSize = 13.sp) },
                                                onClick = {
                                                    presetMenu = false
                                                    onBaseUrl(preset.baseUrl)
                                                    preset.dshApi?.let(onDshApi)
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        }
                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { if (!selectedKind.fixedBaseUrl) onBaseUrl(it) },
                            label = { Text(stringResource(R.string.settings_base_url)) },
                            supportingText = urlSupporting,
                            trailingIcon = presetTrailing,
                            readOnly = selectedKind.fixedBaseUrl,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                        )
                        if (selectedKind in DSH_PROTOCOL_PROVIDERS && !selectedKind.fixedProtocol) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.settings_gateway_protocol), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(5.dp))
                                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)) {
                                    Column {
                                        listOf("anthropic-messages", "openai-completions", "openai-responses").forEach { option ->
                                            Row(
                                                Modifier.fillMaxWidth().clickable { onDshApi(option) }.padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(option, Modifier.weight(1f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                                AgentSelectionDot(dshApi == option)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, PocketCardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.agent_model_access).uppercase(java.util.Locale.ROOT),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.5.sp,
                    color = PocketMuted,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = CircleShape,
                    color = PocketCardBorder,
                    border = BorderStroke(1.dp, Color(0xFF2E2E3A)),
                    modifier = Modifier.clickable(enabled = !isDiscovering, onClick = onDiscover),
                ) {
                    Text(
                        if (isDiscovering) stringResource(R.string.agent_discovering)
                        else if (models.isEmpty()) stringResource(R.string.agent_discover_models)
                        else stringResource(R.string.agent_models_count, models.size),
                        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFC7C7CF),
                        maxLines = 1,
                    )
                }
            }

            if (selectedKind != ProviderKind.CLAUDE) {
                PremiumSummaryRow(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.agent_ai_model),
                    subtitle = model.ifBlank { stringResource(R.string.agent_select_or_type) },
                    expanded = false,
                    onClick = onOpenModelSheet,
                )
            } else {
                Text(
                    stringResource(R.string.agent_setup_token_hint),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }

            if (status != null) {
                Column(modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (statusOk) Icons.Default.Info else Icons.Default.Warning, null, tint = if (statusOk) PocketAccent else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(status, fontSize = 10.sp, lineHeight = 14.sp, color = if (statusOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                    }
                    statusProviderMessage?.let { providerMessage ->
                        Text(stringResource(R.string.agent_provider_msg, providerMessage), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 21.dp, top = 4.dp))
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            PremiumSummaryRow(
                icon = Icons.Default.Key,
                title = if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_subscription_token) else stringResource(R.string.agent_credentials),
                subtitle = buildString {
                    append(activeKey?.name ?: if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_no_token) else stringResource(R.string.agent_no_key))
                    if (activeKey != null) append(stringResource(R.string.agent_active_suffix))
                    activeKeyStatus?.let {
                        append(" · ")
                        append(when (it.successful) { true -> stringResource(R.string.agent_verified); false -> stringResource(R.string.agent_failed); null -> stringResource(R.string.agent_checking) })
                    }
                },
                positive = activeKeyStatus?.successful == true,
                error = activeKeyStatus?.successful == false,
                expanded = keysExpanded,
                onClick = { keysExpanded = !keysExpanded },
            )

            activeKeyStatus?.let { keyStatus ->
                if (!keysExpanded) {
                    Text(
                        keyStatus.message,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        color = when (keyStatus.successful) {
                            true -> Color(0xFF2E9D72)
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                    )
                    keyStatus.providerMessage?.let { providerMessage ->
                        Text(
                            stringResource(R.string.agent_provider_msg, providerMessage),
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(keysExpanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_saved_tokens, savedKeys.size) else stringResource(R.string.agent_saved_keys, savedKeys.size), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        Text(
                            if (addKeyExpanded) stringResource(R.string.settings_cancel) else stringResource(R.string.agent_add_key),
                            fontSize = 11.sp,
                            color = PocketAccent,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { addKeyExpanded = !addKeyExpanded }.padding(6.dp),
                        )
                    }
                    savedKeys.forEach { key ->
                        val keyStatus = keyConnectionStatuses[key.id]
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)) {
                            Column {
                                Row(
                                    Modifier.fillMaxWidth().clickable { onActivateKey(key.id) }.padding(start = 12.dp, top = 7.dp, bottom = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key.name, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                        Text(if (key.isActive) stringResource(R.string.settings_active) else stringResource(R.string.settings_key_tap_activate), fontSize = 10.sp, color = if (key.isActive) PocketAccent else MaterialTheme.colorScheme.onSurfaceVariant)
                                        keySecrets.firstOrNull { it.id == key.id }?.let { credential ->
                                            Row(
                                                Modifier.padding(top = 3.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    credential.secret,
                                                    Modifier.weight(1f),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    maxLines = 3,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                IconButton(
                                                    onClick = {
                                                        keyClipboard.setText(AnnotatedString(credential.secret))
                                                        keyCopiedId = key.id
                                                    },
                                                    modifier = Modifier.size(30.dp).padding(start = 4.dp),
                                                ) {
                                                    Icon(
                                                        if (keyCopiedId == key.id) Icons.Default.Check else Icons.Default.ContentCopy,
                                                        stringResource(if (keyCopiedId == key.id) R.string.agent_key_copied else R.string.agent_key_copy),
                                                        Modifier.size(15.dp),
                                                        tint = if (keyCopiedId == key.id) PocketAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    AgentSelectionDot(key.isActive)
                                    IconButton(onClick = { onRemoveKey(key.id) }) {
                                        Icon(Icons.Default.DeleteSweep, stringResource(R.string.settings_remove), Modifier.size(17.dp))
                                    }
                                }
                                keyStatus?.let {
                                    Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (it.successful == null) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp)
                                        else Icon(if (it.successful) Icons.Default.CheckCircle else Icons.Default.Warning, null, tint = if (it.successful) Color(0xFF2E9D72) else MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(7.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(it.message, fontSize = 10.sp, lineHeight = 14.sp, color = if (it.successful == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                            it.providerMessage?.let { providerMessage ->
                                                Text(stringResource(R.string.agent_provider_msg, providerMessage), fontSize = 10.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(addKeyExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newKeyName,
                                onValueChange = { input ->
                                    if ((input.startsWith("sk-") || input.startsWith("ant-") || input.length > 30) && !input.contains(" ") && newApiKey.isBlank()) {
                                        onNewApiKey(input.trim())
                                        onNewKeyName(context.getString(R.string.agent_key_for, selectedKind.title))
                                    } else onNewKeyName(input)
                                },
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_token_name) else stringResource(R.string.settings_key_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            OutlinedTextField(
                                value = newApiKey,
                                onValueChange = onNewApiKey,
                                label = { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_setup_token_label) else stringResource(R.string.settings_api_key)) },
                                singleLine = true,
                                visualTransformation = if (newKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = { IconButton(onClick = onToggleNewKey) { Icon(if (newKeyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(R.string.agent_toggle_visibility)) } },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            )
                            Button(
                                onClick = { onAddKey(); addKeyExpanded = false },
                                enabled = newKeyName.isNotBlank() && newApiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text(if (selectedKind == ProviderKind.CLAUDE) stringResource(R.string.agent_save_token) else stringResource(R.string.settings_save_api_key)) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    Spacer(Modifier.height(16.dp))
    // Loopback gateways on this device answer without credentials.
    val loopbackEndpoint = isLoopbackBaseUrl(if (selectedKind.fixedBaseUrl) selectedKind.defaultBaseUrl else baseUrl)
    if (loopbackEndpoint && apiKey.isBlank() && selectedKind != ProviderKind.CLAUDE) {
        Text(
            stringResource(R.string.agent_local_no_key),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            color = PocketMuted,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
    }
    SecondaryActionButton(
        onClick = onValidate,
        enabled = (apiKey.isNotBlank() || loopbackEndpoint) && !isDiscovering && !isValidating &&
            (selectedKind == ProviderKind.CLAUDE || (baseUrl.isNotBlank() && model.isNotBlank())),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        if (isValidating) {
            CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.8.dp, color = PocketMuted)
            Spacer(Modifier.width(8.dp))
        } else {
            Icon(if (selectedKind == ProviderKind.CLAUDE) Icons.Default.Check else Icons.Default.Refresh, null, Modifier.size(16.dp), tint = PocketMuted)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            when {
                isValidating && selectedKind == ProviderKind.CLAUDE -> stringResource(R.string.agent_saving_token)
                isValidating -> stringResource(R.string.agent_testing_connection)
                selectedKind == ProviderKind.CLAUDE -> stringResource(R.string.agent_save_sub_token)
                else -> stringResource(R.string.agent_test_connection)
            },
            fontWeight = FontWeight.SemiBold,
        )
    }
    Text(
        stringResource(
            R.string.agent_last_check,
            when (state.apiPingStatus) {
                ApiPingStatus.OK -> stringResource(R.string.agent_online)
                ApiPingStatus.PINGING -> stringResource(R.string.agent_testing)
                ApiPingStatus.FAILED -> stringResource(R.string.agent_attention)
                ApiPingStatus.IDLE -> stringResource(R.string.agent_not_tested)
            },
        ),
        fontSize = 11.sp,
        textAlign = TextAlign.Center,
        color = Color(0xFF6B6B76),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

@Composable
private fun PremiumSummaryRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    positive: Boolean = false,
    error: Boolean = false,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(PocketSurfaceVariant, RoundedCornerShape(12.dp))
                .border(1.dp, PocketOutline, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = Color(0xFFC9C9D1), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Color.White)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = when {
                    error -> MaterialTheme.colorScheme.error
                    positive -> Color(0xFF6EE7B7)
                    else -> PocketMuted
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (expanded) stringResource(R.string.settings_collapse) else stringResource(R.string.settings_expand),
            tint = Color(0xFF4B4B55),
        )
    }
}

/**
 * Compact Runtime Updates Card.
 */
@Composable
private fun AgentUpdateBlock(
    state: AppUiState,
    onCheck: () -> Unit,
    onUpdate: (AgentKind) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_runtime_updates), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(
                    state.agentUpdateMessage ?: stringResource(R.string.agent_stay_current),
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val canCheck = !state.agentUpdatesChecking && state.agentUpdating == null && state.agentInstalling == null
            Row(
                modifier = Modifier
                    .clickable(enabled = canCheck, onClick = onCheck)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.agentUpdatesChecking) {
                    CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = PocketAccent, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.agent_check_updates), color = PocketAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        state.agentUpdates.forEach { (agent, update) ->
                val updating = state.agentUpdating == agent
                val downloaded = state.agentUpdateDownloadedBytes
                val total = state.agentUpdateTotalBytes
                val fraction = if (updating && downloaded != null && total != null && total > 0L) {
                    (downloaded.toFloat() / total).coerceIn(0f, 1f)
                } else state.agentUpdateProgress.coerceIn(0f, 1f)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = PocketAccent.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, PocketAccent.copy(alpha = 0.28f)),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(agent.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("v${update.installedVersion} → v${update.latestVersion}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(stringResource(R.string.agent_update), color = PocketAccent, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        if (updating) {
                            state.agentUpdateMessage?.let { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            Button(
                                onClick = { onUpdate(agent) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = state.agentUpdating == null && state.agentInstalling == null,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(stringResource(R.string.agent_update_btn, agent.title), fontSize = 12.sp)
                            }
                        }
                    }
                }
        }
    }
}

private fun formatAgentBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

/**
 * Fixed-size three-dot wave. All three dots are always laid out, only their
 * alpha animates — so surrounding text never shifts while loading.
 */
@Composable
private fun AgentTypingDots(
    color: Color,
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 6.dp,
    spacing: androidx.compose.ui.unit.Dp = 4.dp,
) {
    val transition = rememberInfiniteTransition(label = "agent-typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "wave",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing)) {
        repeat(3) { index ->
            val alpha = 0.25f + 0.75f * ((phase + index / 3f) % 1f)
            Box(Modifier.size(dotSize).background(color.copy(alpha = alpha), CircleShape))
        }
    }
}

@Composable
private fun AgentSelectionDot(selected: Boolean) {
    Box(
        Modifier
            .size(20.dp)
            .background(if (selected) Color.White else Color.Transparent, CircleShape)
            .border(1.5.dp, if (selected) Color.White else PocketOutline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** Three-up summary of the current model list: working, broken, and average probe latency. */
@Composable
private fun ModelStatsGrid(
    working: Int,
    broken: Int,
    avgLatencyMs: Double?,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(
            label = stringResource(R.string.agent_stat_working),
            value = working.toString(),
            accent = Color(0xFF4ADE80),
            modifier = Modifier.weight(1f),
        )
        StatCell(
            label = stringResource(R.string.agent_stat_broken),
            value = broken.toString(),
            accent = Color(0xFFFB7185),
            modifier = Modifier.weight(1f),
        )
        StatCell(
            label = stringResource(R.string.agent_stat_avg_latency),
            value = avgLatencyMs?.let { "%.1fs".format(it / 1000.0) } ?: "—",
            accent = PocketMuted,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PocketSurface,
        border = BorderStroke(1.dp, PocketCardBorder),
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    label,
                    fontSize = 11.sp,
                    color = PocketMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pill chip that acts as a toggle: white fill and a check mark while active,
 * dark surface and muted label otherwise. Replaces the old Switch toggles.
 */
@Composable
private fun PillToggleChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (active) Color.White else Color(0xFF1E1E24),
        border = BorderStroke(1.dp, if (active) Color.White else PocketCardBorder),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (active) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (active) Color.Black else PocketMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * Neutral secondary action button: #1f1f28 fill with a #2a2a32 hairline.
 * The redesign replaces oversized accent buttons with this quieter style,
 * leaving the violet accent for indicators, selections, and the header avatar.
 */
@Composable
private fun SecondaryActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = PocketSurfaceVariant,
            contentColor = Color.White,
            disabledContainerColor = PocketSurfaceVariant.copy(alpha = 0.45f),
            disabledContentColor = PocketMuted,
        ),
        border = BorderStroke(1.dp, PocketOutline),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) { content(this) }
}

/** Provides popular default models for providers when discovery hasn't been run or is unavailable. */
private fun defaultModelsForProvider(kind: ProviderKind): List<DiscoveredModel> = when (kind) {
    ProviderKind.DEEPSEEK -> listOf(
        DiscoveredModel("deepseek-v4-flash", "DeepSeek-V4 Flash"),
    )
    ProviderKind.OPENCODE_ZEN -> listOf(
        DiscoveredModel(ProviderKind.OPENCODE_ZEN.defaultModel, "OpenCode Zen default"),
    )
    ProviderKind.NVIDIA_NIM -> listOf(
        DiscoveredModel(ProviderKind.NVIDIA_NIM.defaultModel, "Qwen 2.5 Coder 32B"),
    )
    ProviderKind.ANTHROPIC -> listOf(
        DiscoveredModel("claude-3-7-sonnet-20250219", "Claude 3.7 Sonnet (Hybrid)"),
        DiscoveredModel("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet v2"),
        DiscoveredModel("claude-3-5-haiku-20241022", "Claude 3.5 Haiku"),
    )
    ProviderKind.LLM_ROUTER -> listOf(
        DiscoveredModel("deepseek/deepseek-r1", "DeepSeek R1 (via OpenRouter)"),
        DiscoveredModel("anthropic/claude-3.7-sonnet", "Claude 3.7 Sonnet"),
        DiscoveredModel("openai/gpt-4o", "GPT-4o"),
        DiscoveredModel("meta-llama/llama-3.3-70b-instruct", "Llama 3.3 70B"),
    )
    ProviderKind.KIMI -> listOf(
        DiscoveredModel("kimi-k2.6", "Kimi K2.6"),
        DiscoveredModel("moonshot-v1-8k", "Moonshot v1 8K"),
        DiscoveredModel("moonshot-v1-32k", "Moonshot v1 32K"),
    )
    else -> if (kind.defaultModel.isNotBlank()) listOf(
        DiscoveredModel(kind.defaultModel, "${kind.title} Default (${kind.defaultModel})")
    ) else emptyList()
}

/** Terminal-style live log for the model health scan (mirrors the functionez scanner output). */
@Composable
internal fun ModelScanTerminal(
    lines: List<String>,
    scanning: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = modifier.heightIn(max = 200.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF111111),
        border = BorderStroke(1.dp, Color(0xFF1F1F23)),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            items(lines) { line ->
                // Soft, desaturated log palette — the old neon red/green reads as noise.
                val color = when {
                    line.startsWith("✓") -> Color(0xFF4ADE80).copy(alpha = 0.8f)
                    line.startsWith("✗") || line.startsWith("!") -> Color(0xFFFB7185).copy(alpha = 0.8f)
                    line.startsWith("T") -> Color(0xFFFBBF24).copy(alpha = 0.8f)
                    line.startsWith("$") || line.startsWith("Done") -> PocketAccent.copy(alpha = 0.8f)
                    else -> PocketMuted
                }
                Text(
                    line,
                    color = color,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
            if (scanning) {
                item {
                    Text(
                        "…",
                        color = PocketAccent,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
