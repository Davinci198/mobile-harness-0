package com.jarves.mh.workflows

/**
 * Phrase-similarity matcher (FoneClaw scoring):
 * 100 exact, 80 input contains phrase, 60 phrase contains input,
 * otherwise keyword overlap `(hits × 40) / phraseTokens`. The best-scoring
 * workflow wins; an ambiguous tie is rejected so the input falls through
 * to the normal Agent Execution.
 */
object WorkflowMatcher {

    const val MIN_SCORE = 60

    /** Inputs shorter than this never win via "phrase contains input". */
    private const val MIN_CONTAINED_INPUT = 4

    fun score(input: String, workflow: AgentWorkflow): Int {
        val needle = normalize(input)
        if (needle.isEmpty()) return 0
        val needleTokens = needle.split(' ').toSet()
        val candidates = workflow.matchPhrases.map(::normalize).filter { it.isNotEmpty() } +
            listOf(normalize(workflow.name)).filter { it.isNotEmpty() }
        var best = 0
        for (phrase in candidates) {
            val score = when {
                needle == phrase -> 100
                needle.contains(phrase) -> 80
                phrase.contains(needle) && needle.length >= MIN_CONTAINED_INPUT -> 60
                else -> {
                    val tokens = phrase.split(' ').filter { it.isNotEmpty() }
                    if (tokens.isEmpty()) 0 else (tokens.count { it in needleTokens } * 40) / tokens.size
                }
            }
            if (score > best) best = score
        }
        return best
    }

    /** Top-scoring workflow at or above [MIN_SCORE]; null when none or ambiguous. */
    fun match(input: String, workflows: List<AgentWorkflow>): AgentWorkflow? {
        val scored = workflows.map { it to score(input, it) }.filter { it.second >= MIN_SCORE }
        if (scored.isEmpty()) return null
        val top = scored.maxOf { it.second }
        val winners = scored.filter { it.second == top }
        if (winners.size > 1) return null
        return winners.first().first
    }

    fun normalize(text: String): String =
        text.trim().lowercase().replace(Regex("\\s+"), " ")
}
