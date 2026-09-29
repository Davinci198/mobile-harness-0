package com.jarves.mh.tools

/**
 * Privilege tiers, ordered by how much power they grant. Each one needs a backend:
 * - STANDARD: ordinary app permissions, no special grant
 * - ACCESSIBILITY: screen reading and input simulation through an accessibility service
 * - DEBUGGER: a shell on the device itself, through Shizuku, running as the `shell` user
 * - ADMIN: device administrator
 *
 * There is no root tier: this app has no superuser on the device, and Shizuku hands out a
 * shell user rather than root. The sandbox guest runs as ordinary user space, which is
 * not a device capability either, so it is not offered as a tier.
 *
 * Only STANDARD and DEBUGGER have a backend here. ACCESSIBILITY and ADMIN report as
 * unsupported until one exists for them.
 */
enum class AccessLevel {
    STANDARD,
    ACCESSIBILITY,
    DEBUGGER,
    ADMIN;

    companion object {
        /**
         * Unknown or missing values fall back to the least privileged tier. The retired
         * ROOT value maps onto DEBUGGER, since Shizuku is what took its place.
         */
        fun fromString(value: String?): AccessLevel = when (value?.trim()?.uppercase()) {
            "ACCESSIBILITY" -> ACCESSIBILITY
            "DEBUGGER", "SHIZUKU", "ROOT" -> DEBUGGER
            "ADMIN" -> ADMIN
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
