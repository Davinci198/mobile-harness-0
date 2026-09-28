package com.jarves.mh.tools

/**
 * Privilege tiers, ordered by how much power they grant.
 *
 * The tiers are ordered by how much power they grant, and each one needs a backend:
 * - STANDARD: ordinary app permissions, no special grant
 * - ACCESSIBILITY: screen reading and input simulation through an accessibility service
 * - DEBUGGER: ADB-level access through Shizuku
 * - ADMIN: device administrator
 * - ROOT: superuser
 *
 * This app only ships backends for STANDARD (plain app APIs) and ROOT (the proot
 * sandbox guest, which is user space, not a real superuser). DEBUGGER becomes available
 * once Shizuku grants access; ACCESSIBILITY and ADMIN have no backend here yet and are
 * reported as unsupported until a backend exists for them.
 */
enum class AccessLevel {
    STANDARD,
    ACCESSIBILITY,
    DEBUGGER,
    ADMIN,
    ROOT;

    companion object {
        /** Unknown or missing values fall back to the least privileged tier. */
        fun fromString(value: String?): AccessLevel = when (value?.trim()?.uppercase()) {
            "ACCESSIBILITY" -> ACCESSIBILITY
            "DEBUGGER" -> DEBUGGER
            "ADMIN" -> ADMIN
            "ROOT" -> ROOT
            else -> STANDARD
        }
    }
}

/** What the UI needs to draw one tier row. */
data class AccessLevelStatus(
    val level: AccessLevel,
    val available: Boolean,
    val detail: AccessLevelDetail,
)

/**
 * Why a tier is or isn't usable right now. Kept separate from [AccessLevelStatus] so the
 * mapping stays pure and unit-testable without Android.
 */
enum class AccessLevelDetail {
    /** Tier works today. */
    READY,

    /** Backend exists but the user has not granted access yet. */
    NEEDS_PERMISSION,

    /** Backend exists but is not running (e.g. Shizuku is installed but stopped). */
    NOT_RUNNING,

    /** No backend for this tier in this app yet. */
    UNSUPPORTED,
}

/**
 * Availability of every tier given the Shizuku state. Pure on purpose: the Android
 * specific probing lives in the caller, this only maps state to a decision.
 */
fun accessLevelStatuses(shizukuInstalled: Boolean, shizukuRunning: Boolean, shizukuGranted: Boolean): Map<AccessLevel, AccessLevelStatus> =
    AccessLevel.entries.associateWith { level ->
        when (level) {
            AccessLevel.STANDARD -> AccessLevelStatus(level, true, AccessLevelDetail.READY)

            // The proot sandbox is a full Linux userland, so the sandbox tier is live.
            AccessLevel.ROOT -> AccessLevelStatus(level, true, AccessLevelDetail.READY)

            AccessLevel.DEBUGGER -> when {
                shizukuGranted -> AccessLevelStatus(level, true, AccessLevelDetail.READY)
                shizukuRunning -> AccessLevelStatus(level, false, AccessLevelDetail.NEEDS_PERMISSION)
                shizukuInstalled -> AccessLevelStatus(level, false, AccessLevelDetail.NOT_RUNNING)
                else -> AccessLevelStatus(level, false, AccessLevelDetail.UNSUPPORTED)
            }

            AccessLevel.ACCESSIBILITY, AccessLevel.ADMIN ->
                AccessLevelStatus(level, false, AccessLevelDetail.UNSUPPORTED)
        }
    }
