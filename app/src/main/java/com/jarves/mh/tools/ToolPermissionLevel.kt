package com.jarves.mh.tools

/**
 * Permission policy for a single tool call.
 *
 * - [ASK] waits for an explicit user decision (default, matching ro-operit).
 * - [ALLOW] auto-approves without prompting.
 * - [FORBID] always denies.
 */
enum class ToolPermissionLevel {
    ALLOW,
    ASK,
    FORBID;

    companion object {
        fun fromString(value: String?): ToolPermissionLevel = when (value) {
            "ALLOW" -> ALLOW
            "FORBID" -> FORBID
            "ASK" -> ASK
            else -> ASK
        }
    }
}
