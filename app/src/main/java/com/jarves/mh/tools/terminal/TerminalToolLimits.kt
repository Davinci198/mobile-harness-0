package com.jarves.mh.tools.terminal

class TerminalToolLimits {
    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 60_000
        const val MAX_TIMEOUT_MS: Long = 10 * 60_000
        const val MAX_OUTPUT_CHARS: Int = 64 * 1024
        const val MAX_COMMAND_CHARS: Int = 64 * 1024
        const val DEFAULT_ROWS: Int = 40
        const val DEFAULT_COLUMNS: Int = 120
    }
}
