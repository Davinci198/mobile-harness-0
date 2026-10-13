package com.jarves.mh.plugins

/**
 * Admission rules for a plugin candidate.
 *
 * Pure and deterministic: same inputs, same [PluginVerdict], no I/O and no
 * mutable global state. This is the security boundary for plugins — a
 * candidate must clear every rule here before any plugin code is considered.
 *
 * Rules, in order:
 *  1. Self-package: MH must never admit itself.
 *  2. Version: a plugin with no usable version metadata cannot be tracked.
 *  3. Capability: at least one declared capability MH recognises.
 *  4. Entry point: if the plugin declares a service entry point, that service
 *     must actually be declared in the manifest.
 *
 * The validator stays free of Android types: the caller resolves the manifest
 * up front and passes the declared service names alongside the candidate.
 */
class PluginValidator(private val ownPackageName: String) {

    fun validate(
        candidate: PluginCandidate,
        declaredServices: Set<String> = emptySet(),
    ): PluginVerdict {
        if (candidate.packageName == ownPackageName) {
            return PluginVerdict.Rejected(candidate.packageName, PluginRejectReason.SELF_PACKAGE)
        }
        if (candidate.versionName.isBlank() || candidate.versionCode <= 0L) {
            return PluginVerdict.Rejected(candidate.packageName, PluginRejectReason.INVALID_VERSION)
        }
        if (candidate.declaredCapabilities.isEmpty()) {
            return PluginVerdict.Rejected(
                candidate.packageName,
                PluginRejectReason.NO_USABLE_CAPABILITY,
            )
        }
        val entryPoint = candidate.entryPointService
        if (entryPoint != null &&
            declaredServices.none { it.equals(entryPoint, ignoreCase = true) }
        ) {
            return PluginVerdict.Rejected(
                candidate.packageName,
                PluginRejectReason.MISSING_ENTRY_POINT,
            )
        }
        return PluginVerdict.Admitted(candidate)
    }
}
