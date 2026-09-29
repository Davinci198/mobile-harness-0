package com.jarves.mh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarves.mh.R
import com.jarves.mh.storage.DeviceRoot
import com.jarves.mh.storage.FsCategory
import com.jarves.mh.storage.FsCategoryKind
import com.jarves.mh.storage.FsEntry
import com.jarves.mh.storage.FsError
import com.jarves.mh.storage.FsRemedy
import com.jarves.mh.ui.theme.PocketAccent
import com.jarves.mh.ui.theme.PocketMuted
import androidx.compose.material3.ExperimentalMaterial3Api as ExperimentalMaterial3
import androidx.compose.material3.FilterChip

/**
 * Browses the whole device, not just the project's workspace.
 *
 * The root picks the backend, so the same screen walks the app sandbox, a folder granted
 * through the system picker, shared storage or the Shizuku shell without the UI knowing
 * the difference. What it cannot reach is shown as a reason plus the step to fix it,
 * rather than an empty list that looks like an empty disk.
 */
@OptIn(ExperimentalMaterial3::class)
@Composable
fun FilesScreen(
    roots: List<DeviceRoot>,
    unavailable: List<FsError>,
    activeRoot: DeviceRoot?,
    path: String,
    entries: List<FsEntry>,
    categories: List<FsCategory>,
    loading: Boolean,
    error: FsError?,
    openName: String?,
    openContent: String?,
    openLoading: Boolean,
    onOpenRoot: (DeviceRoot) -> Unit,
    onNavigate: (String) -> Unit,
    onGoUp: () -> Unit,
    onRefreshRoots: () -> Unit,
    onPickFolder: (android.net.Uri, String) -> Unit,
    onOpenEntry: (FsEntry) -> Unit,
    onCloseFile: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRename: (FsEntry, String) -> Unit,
    onDelete: (FsEntry) -> Unit,
    onRemedy: (FsRemedy) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri ->
            if (uri != null) {
                val label = uri.toString().substringAfterLast('/').substringAfterLast(':').ifBlank { uri.toString() }
                onPickFolder(uri, label)
            }
        },
    )

    if (openName != null) {
        FileViewerScreen(
            filePath = openName,
            content = openContent,
            loading = openLoading,
            onClose = onCloseFile,
        )
        return
    }

    var dialog by remember { mutableStateOf<FsDialog?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.files_browser_title), fontSize = 17.sp)
                        if (!path.isEmpty()) {
                            Text(path, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onGoUp, enabled = path.isNotEmpty()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.files_up))
                    }
                },
                actions = {
                    IconButton(onClick = onRefreshRoots) { Icon(Icons.Default.Refresh, stringResource(R.string.files_refresh)) }
                    IconButton(onClick = { dialog = FsDialog.NewFolder }) { Icon(Icons.Default.CreateNewFolder, stringResource(R.string.files_new_folder)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            RootSwitcher(roots = roots, active = activeRoot, onSelect = onOpenRoot, onAddFolder = { folderLauncher.launch(null) })

            error?.let { FsErrorBanner(it, onRemedy = onRemedy) }
            if (unavailable.isNotEmpty() && roots.size <= 1) {
                unavailable.firstOrNull()?.let { FsErrorBanner(it, onRemedy = onRemedy) }
            }

            when {
                loading && entries.isEmpty() && categories.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

                // At the root of a root the grid reads as storage; once inside, a path
                // turns into a plain list, because a grid of file names is unreadable.
                path.isEmpty() && categories.isNotEmpty() -> LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(categories, key = { "${it.kind}-${it.path}" }) { category ->
                        CategoryTile(category, onClick = { onNavigate(category.path) })
                    }
                }

                entries.isEmpty() && error == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.files_empty), color = PocketMuted, fontSize = 13.sp)
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(entries, key = { it.relativePath }) { entry ->
                        FileRow(
                            entry = entry,
                            onOpen = { onOpenEntry(entry) },
                            onRename = { dialog = FsDialog.Rename(entry) },
                            onDelete = { dialog = FsDialog.Delete(entry) },
                        )
                    }
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
private fun RootSwitcher(
    roots: List<DeviceRoot>,
    active: DeviceRoot?,
    onSelect: (DeviceRoot) -> Unit,
    onAddFolder: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        roots.forEach { root ->
            // A plain chip gave no way to tell which root you are in, which matters when
            // two of them show the same-looking folders.
            FilterChip(
                selected = root == active,
                onClick = { onSelect(root) },
                label = { Text(root.label, fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        if (root is DeviceRoot.SafTree) Icons.Default.FolderOpen else Icons.Default.Folder,
                        null,
                        Modifier.size(16.dp),
                    )
                },
            )
        }
        FilterChip(
            selected = false,
            onClick = onAddFolder,
            label = { Text(stringResource(R.string.files_add_folder), fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) },
        )
    }
}

@Composable
private fun CategoryTile(category: FsCategory, onClick: () -> Unit) {
    Column(
        Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(width = 62.dp, height = 56.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    categoryIcon(category.kind),
                    null,
                    Modifier.size(28.dp),
                    tint = PocketAccent,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            category.label,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            stringResource(R.string.files_cat_count, category.count),
            fontSize = 11.sp,
            color = PocketMuted,
        )
    }
}

private fun categoryIcon(kind: FsCategoryKind): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    FsCategoryKind.STORAGE -> Icons.Default.SmartToy
    FsCategoryKind.DOWNLOADS -> Icons.Default.Download
    FsCategoryKind.IMAGES -> Icons.Default.Image
    FsCategoryKind.AUDIO -> Icons.Default.MusicNote
    FsCategoryKind.VIDEO -> Icons.Default.Movie
    FsCategoryKind.DOCUMENTS -> Icons.Default.Description
    FsCategoryKind.APPS -> Icons.Default.Apps
    FsCategoryKind.SYSTEM, FsCategoryKind.DATA, FsCategoryKind.VENDOR, FsCategoryKind.PRODUCT -> Icons.Default.Memory
    FsCategoryKind.WORKSPACES -> Icons.Default.Folder
    FsCategoryKind.CHATS -> Icons.Default.Chat
    FsCategoryKind.RUNTIME, FsCategoryKind.SETUP -> Icons.Default.Build
    FsCategoryKind.TERMINAL -> Icons.Default.Terminal
    FsCategoryKind.OTHER -> Icons.Default.Folder
}

@Composable
private fun FileRow(entry: FsEntry, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                entry.isDirectory -> Icons.Default.Folder
                !entry.readable -> Icons.Default.Lock
                else -> Icons.Default.Folder
            },
            null,
            Modifier.size(20.dp),
            tint = if (entry.isDirectory) PocketAccent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!entry.isDirectory) {
                Text(formatFileSize(entry.sizeBytes), fontSize = 11.sp, color = PocketMuted)
            }
        }
        IconButton(onClick = onRename) { Icon(Icons.Default.DriveFileRenameOutline, stringResource(R.string.files_rename), Modifier.size(18.dp)) }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.files_delete), Modifier.size(18.dp)) }
    }
}

@Composable
private fun FsErrorBanner(error: FsError, onRemedy: (FsRemedy) -> Unit) {
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(error.message, fontSize = 12.sp, modifier = Modifier.weight(1f))
            error.remedy?.let { remedy ->
                TextButton(onClick = { onRemedy(remedy) }) {
                    Text(stringResource(remedyLabel(remedy)), fontSize = 12.sp)
                }
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
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text(label) },
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
