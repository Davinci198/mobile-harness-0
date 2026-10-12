package com.jarves.mh.tools

/**
 * Semantic risk of a tool in the risk-graded approval policy.
 *
 * The only level eligible for auto-execution is [LOW]. Anything that reads
 * private data, changes device/app state, affects the outside world, or
 * destroys data must not run under a blanket "global allow" — it needs an
 * explicit per-tool override or a user approval.
 */
enum class ToolRiskLevel {
    /** Read-only, no side effects, no private data. */
    LOW,

    /** Reads private or user-owned data (clipboard, memory, messages…). */
    SENSITIVE_READ,

    /** Changes device/app state (writes, notifications, opening links…). */
    DEVICE_CONTROL,

    /** Affects the outside world (shell, network requests, installs…). */
    EXTERNAL_EFFECT,

    /** Permanently destroys data. */
    DESTRUCTIVE;

    /** Hard rule: only LOW-risk tools may auto-execute. */
    val isAutoEligible: Boolean get() = this == LOW

    companion object {
        fun fromString(value: String?): ToolRiskLevel = entries.firstOrNull { it.name == value } ?: DEVICE_CONTROL
    }
}
