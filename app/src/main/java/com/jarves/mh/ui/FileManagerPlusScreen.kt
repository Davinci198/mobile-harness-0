package com.jarves.mh.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.storage.DeviceRoot
import com.jarves.mh.storage.FsCategory
import com.jarves.mh.storage.FsCategoryKind
import com.jarves.mh.storage.FsClipboardOperation
import com.jarves.mh.storage.FsEntry
import com.jarves.mh.storage.FsError
import com.jarves.mh.storage.FsFileType
import com.jarves.mh.storage.FsFileTypes
import com.jarves.mh.storage.FsPaths
import com.jarves.mh.storage.FsRemedy
import com.jarves.mh.storage.FsSort
import com.jarves.mh.storage.FsViewMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file manager, in the arrangement the design files describe.
 *
 * The root picks the backend, so one screen walks the app sandbox, a folder granted through
 * the system picker, shared storage, or the Shizuku shell. Everything above the listing is
 * chrome on a dark page; everything in the listing is one line per entry, with a colour per
 * kind of file, because that is what makes a screen read as a file manager rather than as a
 * list of settings.
 *
 * A tap opens, a long press picks, and a second tap on a picked entry picks it up. On a
 * touch screen that is the only version of select-then-act that does not need a keyboard,
 * so the copy and cut shortcuts live on the toolbar instead of on Ctrl.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerPlusScreen(
    roots: List<DeviceRoot>,
    unavailable: List<FsError>,
    activeRoot: DeviceRoot?,
    path: String,
    entries: List<FsEntry>,
    categories: List<FsCategory>,
    searchResults: List<FsEntry>,
    searching: Boolean,
    query: String,
    selection: Set<String>,
    clipboardCount: Int,
    status: String,
    storageFree: Long,
    storageTotal: Long,
    view: FsViewMode,
    sort: FsSort,
    sortAscending: Boolean,
    loading: Boolean,
    error: FsError?,
    previewPath: String?,
    lightboxPath: String?,
    openName: String?,
    openContent: String?,
    openLoading: Boolean,
    onOpenRoot: (DeviceRoot) -> Unit,
    onNavigate: (String) -> Unit,
    onGoUp: () -> Unit,
    onGoHome: () -> Unit,
    onBackToProjects: () -> Unit,
    onRefreshRoots: () -> Unit,
    onPickFolder: (android.net.Uri) -> Unit,
    onOpenEntry: (FsEntry) -> Unit,
    onCloseFile: () -> Unit,
    onOpenElsewhere: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRename: (FsEntry, String) -> Unit,
    onDelete: (FsEntry) -> Unit,
    onExtract: (FsEntry) -> Unit,
    onRemedy: (FsRemedy) -> Unit,
    onQueryChange: (String) -> Unit,
    onSetView: (FsViewMode) -> Unit,
    onSetSort: (FsSort, Boolean) -> Unit,
    onToggleSelect: (FsEntry) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onArchive: () -> Unit,
    onShare: (List<FsEntry>) -> Unit,
    onTogglePreview: (FsEntry?) -> Unit,
    onOpenLightbox: () -> Unit,
    onStepLightbox: (Int) -> Unit,
    onCloseLightbox: () -> Unit,
) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(onPickFolder) },
    )

    var dialog by remember { mutableStateOf<FmpDialog?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    LaunchedEffect(path) { onClearSelection() }

    // On a gesture-navigation phone the system back arrives from either edge, and with no
    // handler here it fell straight through to the activity: the home bar or the app
    // closed instead of walking back out. It unwinds this screen's own stack first —
    // overlays, then the folder chain — and hands the last one to the main tab rather
    // than to the activity, so leaving Files is two deliberate gestures and never one.
    BackHandler {
        when {
            // A dialog owns its own window, but if the gesture reaches this callback the
            // dialog still has to be what goes, not the folder underneath it.
            dialog != null -> dialog = null
            lightboxPath != null -> onCloseLightbox()
            openName != null -> onCloseFile()
            searchOpen -> {
                searchOpen = false
                onQueryChange("")
            }

            previewPath != null -> onTogglePreview(null)
            path.isNotEmpty() -> onGoUp()
            else -> onBackToProjects()
        }
    }

    if (openName != null) {
        TextViewer(
            name = openName,
            content = openContent,
            loading = openLoading,
            onClose = onCloseFile,
            onOpenElsewhere = onOpenElsewhere,
        )
        return
    }

    if (lightboxPath != null) {
        Lightbox(
            path = lightboxPath,
            onNext = { onStepLightbox(1) },
            onPrevious = { onStepLightbox(-1) },
            onClose = onCloseLightbox,
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp), tint = FmpColors.Accent)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "File Manager+",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // The title yields the space first: without a weight the badge
                            // is measured last against whatever is left and gets clipped
                            // to "3." when the actions row is wide.
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(6.dp))
                        VersionBadge()
                    }
                },
                navigationIcon = {
                    IconButton(onClick = if (path.isEmpty()) onGoHome else onGoUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.files_up))
                    }
                },
                actions = {
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Default.Search, stringResource(R.string.files_search))
                    }
                    Row(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(FmpColors.RaisedHigh)
                            .padding(2.dp),
                    ) {
                        ViewModeButton(Icons.Default.GridView, view == FsViewMode.GRID, stringResource(R.string.files_view_grid)) {
                            onSetView(FsViewMode.GRID)
                        }
                        ViewModeButton(Icons.Default.ViewList, view == FsViewMode.LIST, stringResource(R.string.files_view_list)) {
                            onSetView(FsViewMode.LIST)
                        }
                    }
                    // The sort field lives on the button rather than in its label: spelled
                    // out, "Sortează: Nume" leaves the title no room and it gets cut to
                    // "File...". The menu still names the field.
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(
                                Icons.Default.Sort,
                                stringResource(R.string.files_sort_label, stringResource(sortLabel(sort))),
                            )
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            FsSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(sortLabel(option)) + if (option == sort) {
                                                if (sortAscending) " ↑" else " ↓"
                                            } else {
                                                ""
                                            },
                                            fontSize = 13.sp,
                                        )
                                    },
                                    onClick = {
                                        onSetSort(option, if (option == sort) !sortAscending else true)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onRefreshRoots) { Icon(Icons.Default.Refresh, stringResource(R.string.files_refresh)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FmpColors.Page),
            )
        },
        bottomBar = {
            BottomBar(
                status = status,
                clipboardCount = clipboardCount,
                onSelectAll = onSelectAll,
                onCopy = onCopy,
                onCut = onCut,
                onPaste = onPaste,
                onArchive = onArchive,
                onShare = { onShare(entries.filter { FsPaths.join(path, it.name) in selection }) },
                onClear = onClearSelection,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(FmpColors.Page)) {
            if (searchOpen) {
                FmpSearchField(query = query, searching = searching, onQueryChange = onQueryChange)
            }

            PlacesBar(
                roots = roots,
                active = activeRoot,
                categories = categories,
                onSelect = onOpenRoot,
                onAddFolder = { folderLauncher.launch(null) },
            )
            Breadcrumb(path = path, onNavigate = onNavigate)

            error?.let { FmpErrorBanner(it, onRemedy) }
            if (error == null && unavailable.isNotEmpty() && roots.size <= 1) {
                unavailable.firstOrNull()?.let { FmpErrorBanner(it, onRemedy) }
            }
            if (searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (searchOpen && query.isNotBlank()) {
                FmpTable(
                    entries = searchResults,
                    path = "",
                    selection = selection,
                    view = view,
                    onOpen = onOpenEntry,
                    onToggleSelect = onToggleSelect,
                    onTogglePreview = onTogglePreview,
                )
                return@Column
            }

            val atRoot = path.isEmpty()
            when {
                loading && entries.isEmpty() && categories.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                atRoot && categories.isNotEmpty() -> StorageGrid(categories, storageFree, storageTotal, onNavigate)

                entries.isEmpty() && error == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.files_empty), color = FmpColors.Muted, fontSize = 13.sp)
                    }

                else -> Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        FmpTable(
                            entries = entries,
                            path = path,
                            selection = selection,
                            view = view,
                            onOpen = onOpenEntry,
                            onToggleSelect = onToggleSelect,
                            onTogglePreview = onTogglePreview,
                            onLongPress = { entry -> if (entry.isDirectory) onNavigate(FsPaths.join(path, entry.name)) else onShare(listOf(entry)) },
                        )
                    }
                    // The side panel is the one piece of the desktop layout that is worth
                    // keeping on a phone: it costs nothing until something is picked.
                    previewPath?.let { preview ->
                        PreviewPanel(
                            path = preview,
                            onClose = { onTogglePreview(null) },
                            onOpenLightbox = onOpenLightbox,
                        )
                    }
                }
            }
        }
    }

    when (val current = dialog) {
        is FmpDialog.NewFolder -> FmpPromptDialog(
            title = stringResource(R.string.files_new_folder),
            onConfirm = { onCreateDirectory(it); dialog = null },
            onDismiss = { dialog = null },
        )

        is FmpDialog.Rename -> FmpPromptDialog(
            title = stringResource(R.string.files_rename),
            initial = current.entry.name,
            onConfirm = { onRename(current.entry, it); dialog = null },
            onDismiss = { dialog = null },
        )

        is FmpDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.files_delete_title)) },
            text = { Text(current.entry.name) },
            confirmButton = {
                TextButton(onClick = { onDelete(current.entry); dialog = null }) {
                    Text(stringResource(R.string.files_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )

        null -> Unit
    }
}

private sealed interface FmpDialog {
    data object NewFolder : FmpDialog
    data class Rename(val entry: FsEntry) : FmpDialog
    data class Delete(val entry: FsEntry) : FmpDialog
}


// ---- palette -----------------------------------------------------------------

/**
 * The page, its raised surfaces, one accent and one selection colour. Kept together so
 * the browser reads as a single surface instead of borrowing colours from the host app.
 */
private object FmpColors {
    val Page = Color(0xFF0F0F10)
    val Raised = Color(0xFF1E1E22)
    val RaisedHigh = Color(0xFF232326)
    val Lower = Color(0xFF1A1A1D)
    val Text = Color(0xFFF7F7F8)
    val Muted = Color(0xFF94A3B8)
    val Accent = Color(0xFFF59E0B)
    val Select = Color(0xFF8B5CF6)
}

// ---- header ------------------------------------------------------------------

@Composable
private fun VersionBadge() {
    Surface(
        shape = RoundedCornerShape(50),
        color = FmpColors.Accent.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, FmpColors.Accent.copy(alpha = 0.4f)),
    ) {
        Text(
            "3.8.3",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            // Without these the badge is squeezed by the toolbar's action row and breaks
            // into a tall pill reading "3." over "8.".
            maxLines = 1,
            softWrap = false,
            color = FmpColors.Accent,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ViewModeButton(icon: ImageVector, active: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, Modifier.size(16.dp), tint = if (active) Color.Black else FmpColors.Muted)
    }
}

@Composable
private fun FmpSearchField(query: String, searching: Boolean, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(50),
        placeholder = { Text(stringResource(R.string.files_search), fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Clear, stringResource(R.string.files_search_clear), Modifier.size(18.dp))
                }
            }
        },
        textStyle = TextStyle(fontSize = 13.sp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedBorderColor = FmpColors.Select,
            unfocusedBorderColor = Color(0xFF3F3F46),
            focusedContainerColor = FmpColors.RaisedHigh,
            unfocusedContainerColor = FmpColors.RaisedHigh,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
    )
    if (searching) {
        Text("", modifier = Modifier.height(0.dp))
    }
}

// ---- places ------------------------------------------------------------------

/**
 * The places to go, as pills. On the design files this is a sidebar; on a phone the same
 * list runs sideways, which is the same places without eating a third of the width.
 */
@Composable
private fun PlacesBar(
    roots: List<DeviceRoot>,
    active: DeviceRoot?,
    categories: List<FsCategory>,
    onSelect: (DeviceRoot) -> Unit,
    onAddFolder: () -> Unit,
) {
    Column {
        Text(
            stringResource(R.string.files_places).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = FmpColors.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            roots.forEach { root ->
                PlacePill(
                    label = root.label,
                    active = root == active,
                    icon = Icons.Default.Folder,
                    onClick = { onSelect(root) },
                )
            }
            PlacePill(
                label = stringResource(R.string.files_add_folder),
                active = false,
                icon = Icons.Default.CreateNewFolder,
                onClick = onAddFolder,
            )
        }
    }
}

@Composable
private fun PlacePill(label: String, active: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) FmpColors.Accent.copy(alpha = 0.16f) else FmpColors.RaisedHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (active) FmpColors.Accent else FmpColors.Muted)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = if (active) FmpColors.Text else FmpColors.Muted)
    }
}

/** Home, then every folder above the one on screen, each of them tappable. */
@Composable
private fun Breadcrumb(path: String, onNavigate: (String) -> Unit) {
    if (path.isEmpty()) return
    val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(FmpColors.Lower)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onNavigate("") }, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Default.Home, null, Modifier.size(16.dp), tint = FmpColors.Accent)
        }
        parts.forEachIndexed { index, name ->
            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp), tint = FmpColors.Muted)
            val target = parts.take(index + 1).joinToString("/")
            Text(
                name,
                fontSize = 12.sp,
                color = if (index == parts.lastIndex) FmpColors.Text else FmpColors.Muted,
                modifier = Modifier.clickable { onNavigate(target) }.padding(horizontal = 4.dp),
            )
        }
    }
}

// ---- the listing -------------------------------------------------------------

@Composable
private fun FmpTable(
    entries: List<FsEntry>,
    path: String,
    selection: Set<String>,
    view: FsViewMode,
    onOpen: (FsEntry) -> Unit,
    onToggleSelect: (FsEntry) -> Unit,
    onTogglePreview: (FsEntry?) -> Unit,
    onLongPress: (FsEntry) -> Unit = {},
) {
    if (view == FsViewMode.GRID) {
        EntryGrid(entries, path, selection, onOpen, onToggleSelect, onTogglePreview)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "head") {
            Row(
                Modifier.fillMaxWidth().background(FmpColors.RaisedHigh).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.files_col_name),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FmpColors.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.files_col_date),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FmpColors.Muted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.files_col_type),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FmpColors.Muted,
                )
            }
        }
        items(entries, key = { it.relativePath }) { entry ->
            FmpRow(
                entry = entry,
                path = path,
                selected = FsPaths.join(path, entry.name) in selection,
                onOpen = { onOpen(entry) },
                onToggleSelect = { onToggleSelect(entry) },
                onTogglePreview = { onTogglePreview(entry) },
                onLongPress = { onLongPress(entry) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FmpRow(
    entry: FsEntry,
    path: String,
    selected: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onTogglePreview: () -> Unit,
    onLongPress: () -> Unit,
) {
    val type = FsFileTypes.of(entry)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FmpColors.Select.copy(alpha = 0.16f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onToggleSelect)
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeBadge(type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = FmpColors.Text,
            )
            if (entry.isDirectory) {
                Text(
                    stringResource(R.string.files_folder),
                    fontSize = 11.sp,
                    color = FmpColors.Muted,
                )
            }
        }
        Text(
            if (entry.lastModifiedMillis > 0L) fmpDate(entry.lastModifiedMillis) else "",
            fontSize = 11.sp,
            color = FmpColors.Muted,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        TypePill(type)
        IconButton(onClick = onTogglePreview, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.SelectAll, null, Modifier.size(16.dp), tint = FmpColors.Muted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryGrid(
    entries: List<FsEntry>,
    path: String,
    selection: Set<String>,
    onOpen: (FsEntry) -> Unit,
    onToggleSelect: (FsEntry) -> Unit,
    onTogglePreview: (FsEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(entries, key = { it.relativePath }) { entry ->
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (FsPaths.join(path, entry.name) in selection) {
                            FmpColors.Select.copy(alpha = 0.16f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .combinedClickable(onClick = { onOpen(entry) }, onLongClick = { onToggleSelect(entry) })
                    .clickable { onTogglePreview(entry) }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TypeBadge(FsFileTypes.of(entry), size = 48)
                Spacer(Modifier.height(6.dp))
                Text(
                    entry.name,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = FmpColors.Text,
                )
            }
        }
    }
}

/** The coloured square that says what an entry is, with a white icon on top. */
@Composable
private fun TypeBadge(type: FsFileType, size: Int = 36) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(fmpBrush(type)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(typeIcon(type), null, Modifier.size((size * 0.55).dp), tint = Color.White)
    }
}

private fun fmpBrush(type: FsFileType): Brush = when (type) {
    FsFileType.FOLDER -> Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFF97316)))
    FsFileType.IMAGE -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
    FsFileType.VIDEO -> Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF22D3EE)))
    FsFileType.AUDIO -> Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF14B8A6)))
    FsFileType.PDF -> Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFE11D48)))
    FsFileType.APK -> Brush.linearGradient(listOf(Color(0xFF84CC16), Color(0xFF16A34A)))
    FsFileType.ARCHIVE -> Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFD97706)))
    FsFileType.DOC -> Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)))
    FsFileType.OTHER -> Brush.linearGradient(listOf(Color(0xFF94A3B8), Color(0xFF475569)))
}

private fun typeIcon(type: FsFileType): ImageVector = when (type) {
    FsFileType.FOLDER -> Icons.Default.Folder
    FsFileType.IMAGE -> Icons.Default.Image
    FsFileType.VIDEO -> Icons.Default.Movie
    FsFileType.AUDIO -> Icons.Default.MusicNote
    FsFileType.PDF -> Icons.Default.PictureAsPdf
    FsFileType.APK -> Icons.Default.Android
    FsFileType.ARCHIVE -> Icons.Default.FolderZip
    FsFileType.DOC -> Icons.Default.Description
    FsFileType.OTHER -> Icons.Default.InsertDriveFile
}

@Composable
private fun TypePill(type: FsFileType) {
    Surface(
        shape = RoundedCornerShape(50),
        color = FmpColors.RaisedHigh,
        border = BorderStroke(1.dp, Color(0xFF3F3F46)),
    ) {
        Text(
            stringResource(typeLabel(type)),
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

private fun typeLabel(type: FsFileType): Int = when (type) {
    FsFileType.FOLDER -> R.string.files_type_folder
    FsFileType.IMAGE -> R.string.files_type_image
    FsFileType.VIDEO -> R.string.files_type_video
    FsFileType.AUDIO -> R.string.files_type_audio
    FsFileType.PDF -> R.string.files_type_pdf
    FsFileType.APK -> R.string.files_type_apk
    FsFileType.ARCHIVE -> R.string.files_type_archive
    FsFileType.DOC -> R.string.files_type_doc
    FsFileType.OTHER -> R.string.files_type_other
}

private fun sortLabel(sort: FsSort): Int = when (sort) {
    FsSort.NAME -> R.string.files_sort_name
    FsSort.DATE -> R.string.files_sort_date
    FsSort.SIZE -> R.string.files_sort_size
    FsSort.TYPE -> R.string.files_sort_type
}

/** "11 dec. 2024" — the shape a Romanian file manager shows. */
private fun fmpDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

// ---- root grid ---------------------------------------------------------------

@Composable
private fun StorageGrid(categories: List<FsCategory>, storageFree: Long, storageTotal: Long, onNavigate: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(categories, key = { "${it.kind}-${it.path}" }) { category ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val tint = categoryColor(category.kind)
                Surface(
                    modifier = Modifier.size(width = 60.dp, height = 60.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = tint.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
                    onClick = { onNavigate(category.path) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(categoryIcon(category.kind), null, Modifier.size(30.dp), tint = tint)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    category.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = FmpColors.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    storageSubtitle(category, storageFree, storageTotal),
                    fontSize = 11.sp,
                    color = FmpColors.Muted,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun storageSubtitle(category: FsCategory, free: Long, total: Long): String {
    if (category.kind == FsCategoryKind.STORAGE && total > 0L && free >= 0L) {
        return stringResource(R.string.files_used_pct, ((total - free) * 100L) / total)
    }
    if (category.count < 0) return stringResource(R.string.files_counting)
    if (category.bytes <= 0L) return stringResource(R.string.files_cat_count, category.count)
    return stringResource(
        R.string.files_size_count,
        formatFileSize(category.bytes),
        category.count,
    )
}

/** Each category keeps its own icon and colour, so they are told apart at a glance. */
private fun categoryIcon(kind: FsCategoryKind): ImageVector = when (kind) {
    FsCategoryKind.STORAGE -> Icons.Default.FolderOpen
    FsCategoryKind.DOWNLOADS, FsCategoryKind.SETUP -> Icons.Default.Archive
    FsCategoryKind.IMAGES -> Icons.Default.Image
    FsCategoryKind.AUDIO -> Icons.Default.MusicNote
    FsCategoryKind.VIDEO -> Icons.Default.Movie
    FsCategoryKind.DOCUMENTS -> Icons.Default.Description
    FsCategoryKind.APPS -> Icons.Default.Android
    FsCategoryKind.SYSTEM -> Icons.Default.Lock
    FsCategoryKind.DATA -> Icons.Default.Folder
    FsCategoryKind.VENDOR, FsCategoryKind.PRODUCT -> Icons.Default.Memory
    FsCategoryKind.WORKSPACES, FsCategoryKind.OTHER -> Icons.Default.Folder
    FsCategoryKind.CHATS -> Icons.Default.Chat
    FsCategoryKind.RUNTIME -> Icons.Default.Build
    FsCategoryKind.TERMINAL -> Icons.Default.Terminal
}

private fun categoryColor(kind: FsCategoryKind): Color = when (kind) {
    FsCategoryKind.STORAGE -> Color(0xFF90A4AE)
    FsCategoryKind.DOWNLOADS, FsCategoryKind.SETUP -> Color(0xFFD9A05B)
    FsCategoryKind.IMAGES -> Color(0xFFAB47BC)
    FsCategoryKind.AUDIO -> Color(0xFF26A69A)
    FsCategoryKind.VIDEO -> Color(0xFFEF5350)
    FsCategoryKind.DOCUMENTS -> Color(0xFF42A5F5)
    FsCategoryKind.APPS -> Color(0xFF7CB342)
    FsCategoryKind.SYSTEM -> Color(0xFF78909C)
    FsCategoryKind.DATA -> Color(0xFF5C6BC0)
    FsCategoryKind.VENDOR, FsCategoryKind.PRODUCT -> Color(0xFF4DB6AC)
    FsCategoryKind.WORKSPACES -> Color(0xFF5C6BC0)
    FsCategoryKind.CHATS -> Color(0xFF66BB6A)
    FsCategoryKind.RUNTIME -> Color(0xFFFFA726)
    FsCategoryKind.TERMINAL -> Color(0xFF78909C)
    FsCategoryKind.OTHER -> Color(0xFF8D6E63)
}

// ---- footer ------------------------------------------------------------------

/**
 * The status strip: how much is loaded, how much is picked, and the actions that apply to
 * whatever is picked. Kept to one row so the listing keeps the height.
 */
@Composable
private fun BottomBar(
    status: String,
    clipboardCount: Int,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onArchive: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = FmpColors.Lower) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                status.ifEmpty { stringResource(R.string.files_status_idle) },
                fontSize = 11.sp,
                color = FmpColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            BarAction(Icons.Default.SelectAll, stringResource(R.string.files_select_all), onSelectAll)
            BarAction(Icons.Default.ContentCopy, stringResource(R.string.files_copy_action), onCopy)
            BarAction(Icons.Default.ContentCut, stringResource(R.string.files_cut), onCut)
            BarAction(Icons.Default.ContentPaste, stringResource(R.string.files_paste), onPaste)
            BarAction(Icons.Default.Archive, stringResource(R.string.files_archive), onArchive)
            BarAction(Icons.Default.Share, stringResource(R.string.files_share), onShare)
            BarAction(Icons.Default.Clear, stringResource(R.string.files_clear_selection), onClear)
            Text("v3.8.3", fontSize = 10.sp, color = FmpColors.Muted, modifier = Modifier.padding(end = 4.dp))
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Icon(icon, label, Modifier.size(16.dp), tint = FmpColors.Muted)
    }
}

// ---- side preview and lightbox ----------------------------------------------

/** A narrow panel beside the listing: what this entry is, and what can be done to it. */
@Composable
private fun PreviewPanel(path: String, onClose: () -> Unit, onOpenLightbox: () -> Unit) {
    Column(
        Modifier
            .width(320.dp)
            .fillMaxHeight()
            .background(FmpColors.Raised)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.files_preview),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = FmpColors.Muted,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Clear, stringResource(R.string.files_close), Modifier.size(16.dp), tint = FmpColors.Muted)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            FsPaths.nameOf(path),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = FmpColors.Text,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Text(path, fontSize = 11.sp, color = FmpColors.Muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onOpenLightbox) {
            Icon(Icons.Default.Image, null, Modifier.size(16.dp), tint = FmpColors.Accent)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.files_open_fullscreen), fontSize = 13.sp, color = FmpColors.Accent)
        }
    }
}

/**
 * A fullscreen look at one image, with the next and the previous. There is no zoom
 * without a pinch handler; the tap targets are large instead.
 */
@Composable
private fun Lightbox(path: String, onNext: () -> Unit, onPrevious: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            FsPaths.nameOf(path),
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
        Text(
            stringResource(R.string.files_lightbox_hint),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onPrevious) { Text(stringResource(R.string.files_previous), color = Color.White) }
            TextButton(onClick = onNext) { Text(stringResource(R.string.files_next), color = Color.White) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.files_close), color = Color.White) }
        }
    }
}

// ---- small pieces ------------------------------------------------------------

@Composable
private fun FmpErrorBanner(error: FsError, onRemedy: (FsRemedy) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(error.message, fontSize = 12.sp, modifier = Modifier.weight(1f))
            error.remedy?.let { remedy ->
                TextButton(onClick = { onRemedy(remedy) }) { Text(stringResource(remedyLabel(remedy)), fontSize = 12.sp) }
            }
        }
    }
}

private fun remedyLabel(remedy: FsRemedy): Int = when (remedy) {
    FsRemedy.PICK_FOLDER -> R.string.files_add_folder
    FsRemedy.GRANT_ALL_FILES -> R.string.files_grant_all_files
    FsRemedy.REQUEST_SHIZUKU -> R.string.files_grant_shizuku
}

@Composable
private fun FmpPromptDialog(title: String, initial: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(stringResource(R.string.files_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.files_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

// ---- text preview -------------------------------------------------------------

/** A plain look at a text file, the way a file manager previews one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextViewer(
    name: String,
    content: String?,
    loading: Boolean,
    onClose: () -> Unit,
    onOpenElsewhere: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.files_close))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenElsewhere) {
                        Icon(
                            Icons.Default.OpenInNew,
                            stringResource(R.string.files_open_with),
                            tint = FmpColors.Muted,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FmpColors.Page),
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(FmpColors.Page)) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                content == null -> Text(
                    stringResource(R.string.files_no_content),
                    color = FmpColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    item { Text(content, fontSize = 12.sp, color = FmpColors.Text, modifier = Modifier.padding(12.dp)) }
                }
            }
        }
    }
}
