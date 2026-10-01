package com.jarves.mh.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.jarves.mh.runtime.ShizukuBridge

/**
 * Assembles the right backend for a root, from the permissions the app has right now.
 *
 * Everything here is a live check rather than a value read at startup: the user grants
 * SAF access, Shizuku access or all-files access while the app is open, and the browser
 * has to reflect that without a restart.
 */
class DeviceFsFactory(private val context: Context) {

    fun isAllFilesGranted(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun isShizukuGranted(): Boolean = ShizukuBridge.hasPermission()

    /** The app's own files. Always available, and it is what the guest workspace lives in. */
    fun sandboxRoot(): DeviceRoot.Local =
        DeviceRoot.Local(context.getString(com.jarves.mh.R.string.files_root_sandbox))

    fun allFilesRoot(): DeviceRoot.AllFiles =
        DeviceRoot.AllFiles(context.getString(com.jarves.mh.R.string.files_root_all_files))

    fun safRoot(treeUri: String, label: String): DeviceRoot.SafTree = DeviceRoot.SafTree(label, treeUri)

    fun shizukuRoot(startPath: String): DeviceRoot.Shizuku =
        DeviceRoot.Shizuku(context.getString(com.jarves.mh.R.string.files_root_shizuku), startPath)

    /** The backend that serves [root], or null when the app cannot see it at all. */
    fun open(root: DeviceRoot): DeviceFs? = when (root) {
        is DeviceRoot.Local -> LocalFileSystem(root, context.filesDir)
        is DeviceRoot.SafTree -> runCatching { SafFileSystem(root, context.contentResolver) }.getOrNull()
        is DeviceRoot.AllFiles -> if (isAllFilesGranted()) {
            val base = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
            if (base != null) {
                LocalFileSystem(
                    root = root,
                    base = base,
                    // Shared storage only turns read-only for an app the system refused
                    // all-files access, and such an app never gets this root: the branch
                    // is entered by the grant itself. Refusing the write here anyway is
                    // what put "This location is read only" in front of a folder the app
                    // had just been allowed to write, with no remedy to offer. What the
                    // system still refuses — Android/data, someone else's app directory —
                    // comes back by its own name instead.
                    writeAllowed = true,
                    noAccessError = FsError(
                        FsErrorKind.NO_ACCESS,
                        context.getString(com.jarves.mh.R.string.files_error_all_files),
                        FsRemedy.GRANT_ALL_FILES,
                    ),
                )
            } else {
                null
            }
        } else {
            null
        }
        is DeviceRoot.Shizuku -> if (isShizukuGranted()) ShizukuFileSystem(root) else null
    }

    /** Roots worth showing, in the order a person would expect them. */
    fun availableRoots(safTrees: List<Pair<String, String>>): List<DeviceRoot> = buildList {
        add(sandboxRoot())
        safTrees.forEach { (uri, label) -> add(safRoot(uri, label)) }
        if (isAllFilesGranted()) add(allFilesRoot())
        if (isShizukuGranted()) add(shizukuRoot("/"))
    }

    /**
     * Roots the app knows about but cannot open yet, so the UI can offer the missing
     * step instead of pretending the location does not exist.
     */
    fun unavailableRoots(safTrees: List<Pair<String, String>>): List<FsError> = buildList {
        if (!isAllFilesGranted()) {
            add(
                FsError(
                    FsErrorKind.NO_ACCESS,
                    context.getString(com.jarves.mh.R.string.files_error_all_files),
                    FsRemedy.GRANT_ALL_FILES,
                ),
            )
        }
        if (!isShizukuGranted()) {
            add(
                FsError(
                    FsErrorKind.NO_ACCESS,
                    context.getString(com.jarves.mh.R.string.files_error_shizuku),
                    FsRemedy.REQUEST_SHIZUKU,
                ),
            )
        }
        if (safTrees.isEmpty()) {
            add(
                FsError(
                    FsErrorKind.NO_ACCESS,
                    context.getString(com.jarves.mh.R.string.files_error_no_folder),
                    FsRemedy.PICK_FOLDER,
                ),
            )
        }
    }

    /** The system screen where all-files access is granted. */
    fun allFilesSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        }
}
