package com.jarves.mh.plugins

import android.content.Context
import android.content.pm.PackageManager

/**
 * The Android half of plugin discovery: reads the plugin contract out of
 * installed APK manifests and hands [PluginDiscovery] already-inspected
 * [InstalledPackage]s, then attaches a [PluginToolHost] to admitted plugins.
 *
 * The contract an APK declares in its own manifest:
 *  - `<meta-data android:name="com.jarves.mh.plugin.capabilities" android:value="TOOLS"/>`
 *    — comma-separated [PluginCapability] names;
 *  - `<meta-data android:name="com.jarves.mh.plugin.entry_point" android:value=".MyPluginService"/>`
 *    — the fully-qualified name of the exported Service serving [PluginIpc];
 *  - that service must also appear in the plugin's `<service>` declarations
 *    (enforced by [PluginValidator], not here).
 *
 * Everything Android-specific lives in this file so the registry and the
 * admission rules stay pure JVM code.
 */
object PluginPackageManager {

    const val META_CAPABILITIES = "com.jarves.mh.plugin.capabilities"
    const val META_ENTRY_POINT = "com.jarves.mh.plugin.entry_point"

    /** Inspect every installed package; the result feeds [PluginDiscovery.discover]. */
    fun inspect(context: Context): List<InstalledPackage> {
        val packageManager = context.packageManager
        val flags = PackageManager.GET_META_DATA or PackageManager.GET_SERVICES
        return packageManager.getInstalledPackages(flags).mapNotNull { info ->
            val metaData = info.applicationInfo?.metaData ?: return@mapNotNull null
            val capabilities = metaData.getString(META_CAPABILITIES)
                ?.split(',')
                ?.map(String::trim)
                ?.filter(String::isNotEmpty)
                ?.toSet()
                ?.takeIf(Set<String>::isNotEmpty)
                ?: return@mapNotNull null
            val versionCode =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    info.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode.toLong()
                }
            InstalledPackage(
                packageName = info.packageName,
                label = info.applicationInfo?.loadLabel(packageManager)?.toString().orEmpty(),
                versionName = info.versionName,
                versionCode = versionCode,
                declaredCapabilities = capabilities,
                entryPointService = metaData.getString(META_ENTRY_POINT)?.trim()?.takeIf(String::isNotEmpty),
                declaredServices = info.services.orEmpty().mapNotNull { it.name }.toSet(),
            )
        }
    }

    /**
     * Attach to the plugin's exported Messenger service.
     *
     * Plugin code must stay in the plugin process. The returned adapter speaks
     * [PluginIpc] and never reflectively loads or instantiates plugin classes in
     * the host process.
     */
    fun bind(context: Context, plugin: PluginCandidate): PluginToolHost? {
        return runCatching {
            IpcPluginToolHost(context, plugin.packageName, plugin.entryPointService)
        }.getOrNull()
    }
}
