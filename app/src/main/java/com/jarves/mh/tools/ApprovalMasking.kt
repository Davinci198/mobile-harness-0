package com.jarves.mh.tools

/**
 * Masks secrets in text shown on approval cards (sanitized parameters):
 * keys, tokens, passwords and long opaque blobs
 * never reach the confirmation UI in the clear.
 */
object ApprovalMasking {

    private val secretAssignments = Regex(
        """(?i)\b((?:api[_-]?key|apikey|token|secret|password|passwd|authorization|bearer|credential)\b\s*[=:]\s*)([^\s"',;]+)""",
    )

    private val knownKeyPrefixes = Regex(
        """\b(sk|nvapi|ghp|gho|ghs|github_pat|xox[abps]|glpat|AKIA)[-_][A-Za-z0-9._\-]{8,}""",
    )

    private val bearerHeader = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._\-]{8,}""")

    private val longOpaqueBlob = Regex("""\b[A-Za-z0-9+/_=\-]{32,}""")

    /** Returns [text] with every secret-looking value replaced by `•••`. */
    fun mask(text: String): String {
        var out = text
        out = secretAssignments.replace(out) { "${it.groupValues[1]}•••" }
        out = bearerHeader.replace(out) { "${it.groupValues[1]}•••" }
        out = knownKeyPrefixes.replace(out, "•••")
        // Long opaque blobs (hashes, base64 payloads) are useless to review
        // and may carry data; keep surrounding text intact.
        out = longOpaqueBlob.replace(out) { match ->
            val value = match.value
            // Don't re-mask strings we already reduced to bullets.
            if (value.contains('•')) match.value else "•••…(${value.length})"
        }
        return out
    }

    fun maskAll(items: List<String>): List<String> = items.map(::mask)
}
