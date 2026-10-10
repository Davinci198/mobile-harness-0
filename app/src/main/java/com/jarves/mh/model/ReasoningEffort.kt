package com.jarves.mh.model

/**
 * The reasoning-effort ladder shown in the chat composer header.
 *
 * [ALL_LEVELS] is the full ladder the UI understands end to end;
 * [levelsFor] narrows it to what a given agent honestly supports — an empty
 * list means "show no effort control at all" rather than a fake knob
 * (only Antigravity currently exposes a reasoning-effort capability, and
 * only through its low/medium/high model variants).
 */
object ReasoningEffort {
    val ALL_LEVELS = listOf("auto", "low", "medium", "high", "xhigh", "max")

    fun levelsFor(kind: AgentKind): List<String> = when (kind) {
        AgentKind.ANTIGRAVITY -> listOf("low", "medium", "high")
        else -> emptyList()
    }
}
