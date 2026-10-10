package com.jarves.mh.model

/**
 * Pure history edits behind the tap-to-reveal message actions (copy / edit /
 * regenerate / delete). No Android dependencies so the truncate-and-resend
 * plan runs under JVM unit tests on CI.
 */
object ChatHistoryActions {

    /**
     * What a regenerate has to do: [kept] replaces the visible history and
     * [resend] is fired as a fresh prompt right after.
     */
    data class RegeneratePlan(val kept: List<ChatMessage>, val resend: String)

    /**
     * Plan a regenerate for [messageId]:
     * - user message → drop it and everything after it, resend its own text;
     * - assistant message → drop the stale answer together with the user
     *   prompt that produced it, then resend that prompt.
     *
     * Returns null when the id is unknown, when no preceding user prompt
     * exists, or when there is nothing left to send — callers must treat
     * null as "do nothing" so history is never truncated by accident.
     */
    fun planRegenerate(messages: List<ChatMessage>, messageId: String): RegeneratePlan? {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val target = messages[index]
        val userIdx = if (target.fromUser) {
            index
        } else {
            (index - 1 downTo 0).firstOrNull { messages[it].fromUser } ?: return null
        }
        val prompt = messages[userIdx].text.trim()
        if (prompt.isEmpty()) return null
        return RegeneratePlan(kept = messages.take(userIdx), resend = prompt)
    }

    /** Messages after deleting [messageId]; the rest keep identity and order. */
    fun withoutMessage(messages: List<ChatMessage>, messageId: String): List<ChatMessage> =
        messages.filterNot { it.id == messageId }
}
