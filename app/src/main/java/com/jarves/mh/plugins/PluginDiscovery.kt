package com.jarves.mh.plugins

/**
 * Runtime capability an installed plugin advertises. A plugin declares one or
 * more; MH uses the declaration to decide which tool namespaces it may serve
 * and whether it is safe to surface in settings.
 *
 * The set is deliberately closed and security-relevant: a plugin can only ever
 * be admitted for a capability MH already knows how to gate. Anything else is
 * not a "new capability", it is an untrusted request.
 */
enum class PluginCapability {
    /** Registers new tool names the agent may call. Gated by the permission gate. */
    TOOLS,

    /** Exposes a custom slash-command surface to the chat UI. */
    COMMANDS,

    /** Adds a settings screen contributed by the plugin. */
    SETTINGS,
}

/**
 * What an installed package looks like once [PluginDiscovery] has looked at it.
 *
 * [packageName] is the stable identity — everything else is presentation or a
 * capability claim that [PluginValidator] still has to accept.
 */
data class PluginCandidate(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val declaredCapabilities: Set<PluginCapability>,
    val entryPointService: String?,
)

/** Outcome of screening one candidate against the admission rules. */
sealed interface PluginVerdict {
    /** The plugin is admitted. */
    data class Admitted(val plugin: PluginCandidate) : PluginVerdict

    /** The plugin is rejected; [reason] is a stable machine token, not a sentence. */
    data class Rejected(val packageName: String, val reason: PluginRejectReason) : PluginVerdict
}

/**
 * Why a plugin was not admitted. Stable tokens so settings UI can map them to
 * localized strings without parsing prose.
 */
enum class PluginRejectReason {
    /** No capability declared, or every declared capability is unknown. */
    NO_USABLE_CAPABILITY,

    /** The declared entry-point service is missing from the manifest. */
    MISSING_ENTRY_POINT,

    /** The package name is MH itself. */
    SELF_PACKAGE,

    /** Version metadata is unusable. */
    INVALID_VERSION,
}

/**
 * Raw facts about an installed package, as gathered by the Android caller.
 *
 * This is the seam between the platform and the pure validator: everything the
 * admission rules need, and nothing else. The caller maps
 * [android.content.pm.PackageManager] results into this type.
 */
data class InstalledPackage(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val declaredCapabilities: Set<String>,
    val entryPointService: String?,
    val declaredServices: Set<String>,
)

/**
 * Skeleton for finding plugin APKs on the device.
 *
 * Pure split: [PluginDiscovery] is handed already-inspected packages and
 * applies [PluginValidator]. It performs no Android calls of its own, so the
 * whole admission path is unit-testable without a device.
 *
 * This is intentionally a skeleton: it discovers and validates. It does not
 * load, sandbox, or execute plugin code.
 */
class PluginDiscovery(
    ownPackageName: String,
    private val validator: PluginValidator = PluginValidator(ownPackageName),
) {

    /** Screen every package and keep only the admitted plugins. */
    fun discover(packages: List<InstalledPackage>): List<PluginCandidate> =
        packages.mapNotNull { candidate ->
            when (val verdict = validate(candidate)) {
                is PluginVerdict.Admitted -> verdict.plugin
                is PluginVerdict.Rejected -> null
            }
        }

    /** Full verdicts, including rejections, for a settings/debug surface. */
    fun discoverWithVerdicts(packages: List<InstalledPackage>): List<PluginVerdict> =
        packages.map { validate(it) }

    private fun validate(pkg: InstalledPackage): PluginVerdict = validator.validate(
        PluginCandidate(
            packageName = pkg.packageName,
            label = pkg.label,
            versionName = pkg.versionName.orEmpty(),
            versionCode = pkg.versionCode,
            declaredCapabilities = PluginContract.parseCapabilities(pkg.declaredCapabilities),
            entryPointService = pkg.entryPointService,
        ),
        pkg.declaredServices,
    )
}
