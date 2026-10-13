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
 *    — the fully-qualified name of a Service implementing [PluginToolHost];
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
     * Load the plugin's entry-point service as a [PluginToolHost].
     *
     * The class comes from the plugin's own APK through
     * [Context.createPackageContext] with code loading enabled; it must be a
     * public, no-arg constructible implementation of [PluginToolHost].
     * Returns null (and never throws to the caller) when the plugin cannot be
     * attached — a broken plugin must not take discovery down with it.
     */
    fun bind(context: Context, plugin: PluginCandidate): PluginToolHost? {
        val serviceName = plugin.entryPointService ?: return null
        return runCatching {
            val pluginContext = context.createPackageContext(
                plugin.packageName,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
            pluginContext.classLoader
                .loadClass(serviceName)
                .getDeclaredConstructor()
                .newInstance() as PluginToolHost
        }.getOrNull()
    }
}
