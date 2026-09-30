package com.jarves.mh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.jarves.mh.storage.FsFileType
import com.jarves.mh.storage.FsFileTypes
import com.jarves.mh.storage.FsCategoryKind
import com.jarves.mh.storage.FsEntry
import com.jarves.mh.storage.FsError
import com.jarves.mh.storage.FsPaths
import com.jarves.mh.storage.FsRemedy
import com.jarves.mh.storage.FsSort
import com.jarves.mh.storage.FsViewMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The browser's own colours, taken from the design files rather than from the app's theme:
 * a near-black page, two greys for the raised surfaces, one white for text, amber for the
 * accent and violet for a selection. Keeping them together means the browser reads as one
 * surface instead of borrowing a colour from somewhere else in the app.
 */
private object FsPalette {
    val Page = Color(0xFF0F0F10)
    val Raised = Color(0xFF1E1E22)
    val RaisedHigh = Color(0xFF232326)
    val Lower = Color(0xFF1A1A1D)
    val Text = Color(0xFFF7F7F8)
    val Muted = Color(0xFF94A3B8)
    val Accent = Color(0xFFF59E0B)
    val Select = Color(0xFF8B5CF6)
}

/**
 * Browses the whole device, not just the project's workspace.
 *
 * The root picks the backend, so one screen walks the app sandbox, a folder granted
 * through the system picker, shared storage, or the Shizuku shell. At the top of a root it
 * reads as storage: a grid of well-known places. Inside a directory it is a list or a grid
 * of files, and what it cannot reach is a reason plus the step that fixes it, rather than
 * an empty list that looks like an empty disk.
 *
 * Rows stay clean: tap opens, long press selects and raises the action bar. A row full of
 * icons is what makes a file manager feel like a settings screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    roots: List<DeviceRoot>,
    unavailable: List<FsError>,
    activeRoot: DeviceRoot?,
    path: String,
    entries: List<FsEntry>,
    categories: List<FsCategory>,
    searchResults: List<FsEntry>,
    searching: Boolean,
    query: String,
    selectedPath: String?,
    view: FsViewMode,
    sort: FsSort,
    sortAscending: Boolean,
    storageFree: Long,
    storageTotal: Long,
    loading: Boolean,
    error: FsError?,
    openName: String?,
    openContent: String?,
    openLoading: Boolean,
    onOpenRoot: (DeviceRoot) -> Unit,
    onNavigate: (String) -> Unit,
    onGoUp: () -> Unit,
    onGoHome: () -> Unit,
    onRefreshRoots: () -> Unit,
    onPickFolder: (android.net.Uri) -> Unit,
    onOpenEntry: (FsEntry) -> Unit,
    onOpenSearchResult: (FsEntry) -> Unit,
    onCloseFile: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRename: (FsEntry, String) -> Unit,
    onDelete: (FsEntry) -> Unit,
    onRemedy: (FsRemedy) -> Unit,
    onQueryChange: (String) -> Unit,
    onSetView: (FsViewMode) -> Unit,
    onSetSort: (FsSort, Boolean) -> Unit,
    onSelect: (FsEntry) -> Unit,
    onClearSelection: () -> Unit,
) {
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri -> uri?.let(onPickFolder) },
    )

    if (openName != null) {
        FileViewerScreen(filePath = openName, content = openContent, loading = openLoading, onClose = onCloseFile)
        return
    }

    var dialog by remember { mutableStateOf<FsDialog?>(null) }
    var searchOpen by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }

    // A selection left behind by a navigation would point at a file that is no longer on screen.
    LaunchedEffect(path) { onClearSelection() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(activeRoot?.label ?: stringResource(R.string.files_browser_title), fontSize = 16.sp)
                        Text(
                            text = path.ifEmpty { stringResource(R.string.files_root_subtitle) },
                            fontSize = 11.sp,
                            color = FsPalette.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
                    // Segmented, the way the reference puts it: two round buttons where
                    // the active one is a white disc, so the mode is obvious at a glance.
                    Row(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(FsPalette.RaisedHigh)
                            .padding(2.dp),
                    ) {
                        ViewModeButton(
                            icon = Icons.Default.GridView,
                            active = view == FsViewMode.GRID,
                            label = stringResource(R.string.files_view_grid),
                            onClick = { onSetView(FsViewMode.GRID) },
                        )
                        ViewModeButton(
                            icon = Icons.Default.ViewList,
                            active = view == FsViewMode.LIST,
                            label = stringResource(R.string.files_view_list),
                            onClick = { onSetView(FsViewMode.LIST) },
                        )
                    }
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text(
                                stringResource(R.string.files_sort_label, stringResource(sortLabel(sort))),
                                fontSize = 13.sp,
                            )
                            Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            FsSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(sortLabel(option)) +
                                                if (option == sort) {
                                                    if (sortAscending) " ↑" else " ↓"
                                                } else {
                                                    ""
                                                },
                                            fontSize = 13.sp,
                                        )
                                    },
                                    onClick = {
                                        // Picking the one already in use flips its direction.
                                        val ascending = if (option == sort) !sortAscending else true
                                        onSetSort(option, ascending)
                                        sortMenu = false
                                    },
                                )
                            }
                        }
                    }
                    if (path.isNotEmpty()) {
                        IconButton(onClick = { dialog = FsDialog.NewFolder }) {
                            Icon(Icons.Default.CreateNewFolder, stringResource(R.string.files_new_folder))
                        }
                    }
                    IconButton(onClick = onRefreshRoots) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.files_refresh))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = FsPalette.Page),
            )
        },
        bottomBar = {
            val selected = entries.firstOrNull { entry ->
                selectedPath != null && FsPaths.join(path, entry.name) == selectedPath
            }
            if (selected != null) {
                SelectionBar(
                    name = selected.name,
                    onRename = { dialog = FsDialog.Rename(selected) },
                    onDelete = { dialog = FsDialog.Delete(selected) },
                    onClose = onClearSelection,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(FsPalette.Page)) {
            if (searchOpen) {
                SearchField(query = query, searching = searching, onQueryChange = onQueryChange)
            }

            if (path.isNotEmpty()) {
                PlacesBar(roots, activeRoot, onSelect = onOpenRoot, onAddFolder = { folderLauncher.launch(null) })
                LocationHeader(path, storageFree, storageTotal)
            }

            error?.let { FsErrorBanner(it, onRemedy = onRemedy) }
            if (error == null && unavailable.isNotEmpty() && roots.size <= 1) {
                unavailable.firstOrNull()?.let { FsErrorBanner(it, onRemedy = onRemedy) }
            }

            if (searchOpen && query.isNotBlank()) {
                SearchResults(results = searchResults, view = view, onOpen = onOpenSearchResult, onSelect = onSelect)
                return@Column
            }

            val atRoot = path.isEmpty()
            when {
                loading && entries.isEmpty() && categories.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                atRoot && categories.isNotEmpty() ->
                    CategoryGrid(categories, storageFree, storageTotal, onOpen = onNavigate)

                entries.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.files_empty), color = FsPalette.Muted, fontSize = 13.sp)
                }

                view == FsViewMode.GRID -> EntryGrid(entries, path, selectedPath, onOpen = onOpenEntry, onSelect = onSelect)

                else -> Surface(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    color = FsPalette.Raised,
                    border = BorderStroke(1.dp, Color(0xFF27272A)),
                ) {
                    FileTable(
                        entries = entries,
                        path = path,
                        selectedPath = selectedPath,
                        onOpen = onOpenEntry,
                        onSelect = onSelect,
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        is FsDialog.NewFolder -> TextPromptDialog(
            title = stringResource(R.string.files_new_folder),
            label = stringResource(R.string.files_name),
            onConfirm = { onCreateDirectory(it); dialog = null },
            onDismiss = { dialog = null },
        )

        is FsDialog.Rename -> TextPromptDialog(
            title = stringResource(R.string.files_rename),
            label = stringResource(R.string.files_name),
            initial = current.entry.name,
            onConfirm = { onRename(current.entry, it); dialog = null },
            onDismiss = { dialog = null },
        )

        is FsDialog.Delete -> AlertDialog(
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

private sealed interface FsDialog {
    data object NewFolder : FsDialog
    data class Rename(val entry: FsEntry) : FsDialog
    data class Delete(val entry: FsEntry) : FsDialog
}

@Composable
private fun ViewModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (active) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(16.dp),
            tint = if (active) Color.Black else FsPalette.Muted,
        )
    }
}

private fun sortLabel(sort: FsSort): Int = when (sort) {
    FsSort.NAME -> R.string.files_sort_name
    FsSort.DATE -> R.string.files_sort_date
    FsSort.SIZE -> R.string.files_sort_size
    FsSort.TYPE -> R.string.files_sort_type
}

@Composable
private fun SearchField(query: String, searching: Boolean, onQueryChange: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = RoundedCornerShape(50),
            leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(18.dp)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.files_search_clear), Modifier.size(18.dp))
                    }
                }
            },
            textStyle = TextStyle(fontSize = 14.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (searching) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun SearchResults(results: List<FsEntry>, view: FsViewMode, onOpen: (FsEntry) -> Unit, onSelect: (FsEntry) -> Unit) {
    if (results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.files_no_results), color = FsPalette.Muted, fontSize = 13.sp)
        }
        return
    }
    if (view == FsViewMode.GRID) {
        EntryGrid(results, "", null, onOpen, onSelect)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(results, key = { "res-" + it.relativePath }) { entry ->
            FileRow(entry, selected = false, onOpen = { onOpen(entry) }, onSelect = { onSelect(entry) })
        }
    }
}

/** Where you are, and how full the volume is, on one strip above the listing. */
@Composable
private fun LocationHeader(path: String, storageFree: Long, storageTotal: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(FsPalette.Lower)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Home, null, Modifier.size(20.dp), tint = FsPalette.Accent)
        Icon(
            Icons.Default.ChevronRight,
            null,
            Modifier.size(18.dp),
            tint = FsPalette.Muted,
        )
        Icon(
            Icons.Default.Folder,
            null,
            Modifier.size(20.dp),
            tint = Color(0xFFE8A33D),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            FsPaths.nameOf(path),
            fontSize = 12.sp,
            color = FsPalette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (storageTotal > 0L && storageFree >= 0L) {
            val usedPercent = ((storageTotal - storageFree) * 100L) / storageTotal
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = FsPalette.RaisedHigh,
                border = BorderStroke(1.dp, Color(0xFF3F3F46)),
            ) {
                Text(
                    stringResource(R.string.files_used_pct, usedPercent),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** A place to go, as a rounded button: the active one is filled with the accent. */
@Composable
private fun PlacePill(label: String, active: Boolean, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) FsPalette.Accent.copy(alpha = 0.16f) else FsPalette.RaisedHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(14.dp), tint = if (active) FsPalette.Accent else FsPalette.Muted)
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = if (active) FsPalette.Text else FsPalette.Muted)
    }
}

@Composable
private fun PlacesBar(roots: List<DeviceRoot>, active: DeviceRoot?, onSelect: (DeviceRoot) -> Unit, onAddFolder: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.files_places).uppercase(),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.6.sp,
            color = FsPalette.Muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 6.dp),
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
                icon = Icons.Default.Add,
                onClick = onAddFolder,
            )
            PlacePill(
                label = stringResource(R.string.files_go_home),
                active = false,
                icon = Icons.Default.Home,
                onClick = { roots.firstOrNull()?.let(onSelect) },
            )
        }
    }
}

@Composable
private fun CategoryGrid(categories: List<FsCategory>, storageFree: Long, storageTotal: Long, onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(categories, key = { "${it.kind}-${it.path}" }) { category ->
            CategoryTile(category, subtitle = categorySubtitle(category, storageFree, storageTotal)) { onOpen(category.path) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryTile(
    category: FsCategory,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tint = categoryColor(category.kind)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier.size(width = 60.dp, height = 60.dp),
            shape = RoundedCornerShape(18.dp),
            color = tint.copy(alpha = 0.10f),
            border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
            onClick = onClick,
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
            color = FsPalette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            subtitle,
            fontSize = 11.sp,
            color = FsPalette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Each category keeps its own colour, the way a file manager tells them apart at a glance. */
private fun categoryColor(kind: FsCategoryKind): Color = when (kind) {
    FsCategoryKind.STORAGE -> Color(0xFF90A4AE)
    FsCategoryKind.DOWNLOADS -> Color(0xFFD9A05B)
    FsCategoryKind.IMAGES -> Color(0xFFAB47BC)
    FsCategoryKind.AUDIO -> Color(0xFF26A69A)
    FsCategoryKind.VIDEO -> Color(0xFFEF5350)
    FsCategoryKind.DOCUMENTS -> Color(0xFF42A5F5)
    FsCategoryKind.APPS -> Color(0xFF7CB342)
    FsCategoryKind.SYSTEM -> Color(0xFF78909C)
    FsCategoryKind.DATA -> Color(0xFF5C6BC0)
    FsCategoryKind.VENDOR -> Color(0xFF4DB6AC)
    FsCategoryKind.PRODUCT -> Color(0xFF8D6E63)
    FsCategoryKind.WORKSPACES -> Color(0xFF5C6BC0)
    FsCategoryKind.CHATS -> Color(0xFF66BB6A)
    FsCategoryKind.RUNTIME -> Color(0xFFFFA726)
    FsCategoryKind.SETUP -> Color(0xFF42A5F5)
    FsCategoryKind.TERMINAL -> Color(0xFF78909C)
    FsCategoryKind.OTHER -> Color(0xFF8D6E63)
}

/**
 * What sits under the name. A file manager writes "size (count)", and for the storage
 * tile it writes what is free of what, because that is the number people look for first.
 */
@Composable
private fun categorySubtitle(category: FsCategory, storageFree: Long, storageTotal: Long): String {
    if (category.kind == FsCategoryKind.STORAGE && storageTotal > 0L && storageFree >= 0L) {
        val usedPercent = ((storageTotal - storageFree) * 100L) / storageTotal
        return "$usedPercent% ${stringResource(R.string.files_used)}"
    }
    if (category.count < 0) return stringResource(R.string.files_counting)
    // "0 B" is noise: a folder holding only subfolders has no bytes of its own to report.
    if (category.bytes <= 0L) return stringResource(R.string.files_cat_count, category.count)
    return stringResource(R.string.files_size_count, formatFileSize(category.bytes), category.count)
}

private fun categoryIcon(kind: FsCategoryKind): ImageVector = when (kind) {
    FsCategoryKind.STORAGE -> Icons.Default.SmartToy
    FsCategoryKind.DOWNLOADS -> Icons.Default.Download
    FsCategoryKind.IMAGES -> Icons.Default.Image
    FsCategoryKind.AUDIO -> Icons.Default.MusicNote
    FsCategoryKind.VIDEO -> Icons.Default.Movie
    FsCategoryKind.DOCUMENTS -> Icons.Default.Description
    FsCategoryKind.APPS -> Icons.Default.Apps
    FsCategoryKind.SYSTEM,
    FsCategoryKind.DATA,
    FsCategoryKind.VENDOR,
    FsCategoryKind.PRODUCT,
    -> Icons.Default.Memory
    FsCategoryKind.WORKSPACES -> Icons.Default.Folder
    FsCategoryKind.CHATS -> Icons.Default.Chat
    FsCategoryKind.RUNTIME -> Icons.Default.Build
    FsCategoryKind.SETUP -> Icons.Default.Download
    FsCategoryKind.TERMINAL -> Icons.Default.Terminal
    FsCategoryKind.OTHER -> Icons.Default.Folder
}

/**
 * The listing as a card: a header row naming the four columns, one line per entry, and a
 * strip underneath saying what is loaded and what is selected. The columns are laid out
 * the same way the reference lays them out, so the eye runs down one column at a time.
 */
@Composable
private fun FileTable(
    entries: List<FsEntry>,
    path: String,
    selectedPath: String?,
    onOpen: (FsEntry) -> Unit,
    onSelect: (FsEntry) -> Unit,
) {
    val selected = selectedPath ?: ""
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(FsPalette.RaisedHigh)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.files_col_name),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FsPalette.Muted,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.files_col_date),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FsPalette.Muted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.files_col_type),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = FsPalette.Muted,
                )
            }
        }
        items(entries, key = { it.relativePath }) { entry ->
            FileRow(
                entry = entry,
                selected = selected.isNotEmpty() && FsPaths.join(path, entry.name) == selected,
                onOpen = { onOpen(entry) },
                onSelect = { onSelect(entry) },
            )
        }
        item(key = "status") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // The browser holds one selected path at a time, so the count is 0 or 1.
                    stringResource(R.string.files_status, entries.size, if (selected.isEmpty()) 0 else 1),
                    fontSize = 11.sp,
                    color = FsPalette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(entry: FsEntry, selected: Boolean, onOpen: () -> Unit, onSelect: () -> Unit) {
    val type = FsFileTypes.of(entry)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) FsPalette.Select.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onOpen, onLongClick = onSelect)
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The name cell holds the badge and the name and nothing else: the size and the
        // date get columns of their own, so a long name never pushes the numbers around.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TypeBadge(type)
            Spacer(Modifier.width(12.dp))
            Text(
                entry.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = FsPalette.Text,
            )
        }
        Text(
            if (entry.lastModifiedMillis > 0L) formatEntryDate(entry.lastModifiedMillis) else "",
            fontSize = 11.sp,
            color = FsPalette.Muted,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        TypePill(type)
    }
}

/** The type, as a word, in a rounded pill: "Imagine", "PDF", "Arhivă". */
@Composable
private fun TypePill(type: FsFileType) {
    Surface(
        shape = RoundedCornerShape(50),
        color = FsPalette.RaisedHigh,
        border = BorderStroke(1.dp, Color(0xFF3F3F46)),
        modifier = Modifier,
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

/**
 * The coloured square that says what an entry is. A folder is amber, an image violet, an
 * archive a different amber, and the icon is white on top of the colour.
 */
@Composable
private fun TypeBadge(type: FsFileType) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(typeBrush(type)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = typeIcon(type),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = Color.White,
        )
    }
}

private fun typeBrush(type: FsFileType): Brush = when (type) {
    FsFileType.FOLDER -> Brush.linearGradient(listOf(Color(0xFFFBBF24), Color(0xFFF97316)))
    FsFileType.IMAGE -> Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)))
    FsFileType.VIDEO -> Brush.linearGradient(listOf(Color(0xFF3B82F6), Color(0xFF22D3EE)))
    FsFileType.AUDIO -> Brush.linearGradient(listOf(Color(0xFF34D399), Color(0xFF14B8A6)))
    FsFileType.PDF -> Brush.linearGradient(listOf(Color(0xFFEF4444), Color(0xFFE11D48)))
    FsFileType.APK -> Brush.linearGradient(listOf(Color(0xFF84CC16), Color(0xFF16A34A)))
    FsFileType.ARCHIVE -> Brush.linearGradient(listOf(Color(0xFFFCD34D), Color(0xFFD97706)))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryGrid(entries: List<FsEntry>, path: String, selectedPath: String?, onOpen: (FsEntry) -> Unit, onSelect: (FsEntry) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(entries, key = { it.relativePath }) { entry ->
            Column(
                Modifier
                    .background(
                        if (selectedPath != null && FsPaths.join(path, entry.name) == selectedPath) {
                            FsPalette.Select.copy(alpha = 0.14f)
                        } else {
                            Color.Transparent
                        },
                    )
                    .combinedClickable(onClick = { onOpen(entry) }, onLongClick = { onSelect(entry) })
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(30.dp),
                    tint = if (entry.isDirectory) FsPalette.Accent else FsPalette.Muted,
                )
                Spacer(Modifier.height(4.dp))
                Text(entry.name, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}


/** "11 dec. 2024", the shape a Romanian file manager shows. */
private fun formatEntryDate(millis: Long): String =
    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))

@Composable
private fun SelectionBar(name: String, onRename: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    Surface(color = FsPalette.Lower) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 6.dp),
            )
            TextButton(onClick = onRename) {
                Icon(Icons.Default.DriveFileRenameOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.files_rename), fontSize = 12.sp)
            }
            TextButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.files_delete), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Clear, stringResource(R.string.files_clear_selection), Modifier.size(18.dp)) }
        }
    }
}

@Composable
private fun FsErrorBanner(error: FsError, onRemedy: (FsRemedy) -> Unit) {
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
private fun TextPromptDialog(title: String, label: String, initial: String = "", onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, label = { Text(label) }) },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.files_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
