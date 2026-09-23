package com.jarves.mh.mcp

import java.io.InputStream
import java.io.OutputStream

interface McpProcessTransport : AutoCloseable {
    val input: InputStream
    val output: OutputStream
    fun stderr(): String
    fun isAlive(): Boolean
    fun terminate()
    fun kill()
    override fun close()
}

fun interface McpProcessLauncher {
    fun launch(config: McpStdioConfig): McpProcessTransport
}
