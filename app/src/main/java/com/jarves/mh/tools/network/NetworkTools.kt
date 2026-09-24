package com.jarves.mh.tools.network

import com.jarves.mh.tools.AIToolHook
import kotlinx.coroutines.CancellationException
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision

class NetworkTools(
    private val webPages: WebPageExtractor,
    private val httpClient: HttpClientAdapter,
    private val downloads: DownloadAdapter,
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    suspend fun visitWeb(request: WebPageRequest): NetworkToolResult = execute("VisitWeb", "visit ${request.url}") {
        NetworkToolResult.Page(webPages.extract(request))
    }

    suspend fun httpRequest(request: HttpRequestSpec): NetworkToolResult = execute("HttpRequest", "HTTP ${request.method} ${request.url}") {
        NetworkToolResult.Http(httpClient.execute(request))
    }

    suspend fun downloadFile(request: DownloadRequest): NetworkToolResult = execute("DownloadFile", "download ${request.url}") {
        NetworkToolResult.Downloaded(downloads.download(request))
    }

    private suspend fun execute(tool: String, explanation: String, block: suspend () -> NetworkToolResult): NetworkToolResult {
        when (val decision = gate.evaluate(tool, explanation.take(240))) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval -> return NetworkToolResult.Error(tool, NetworkErrorCode.PERMISSION_REQUIRED, "Approval required (ASK)")
            is ToolPermissionGateDecision.Blocked -> return NetworkToolResult.Error(tool, NetworkErrorCode.PERMISSION_DENIED, decision.reason)
        }
        safely { hooks.forEach { it.onToolExecutionStarted(tool) } }
        return try {
            val result = block()
            safely { hooks.forEach { it.onToolExecutionResult(tool, true, "completed $tool") } }
            result
        } catch (error: CancellationException) {
            throw error
        } catch (error: NetworkOperationException) {
            safely { hooks.forEach { it.onToolExecutionResult(tool, false, error.message.orEmpty()) } }
            NetworkToolResult.Error(tool, error.code, error.message ?: "Network operation failed")
        } catch (error: Throwable) {
            safely { hooks.forEach { it.onToolExecutionError(tool, error) } }
            NetworkToolResult.Error(tool, NetworkErrorCode.ADAPTER_ERROR, error.message ?: "Network operation failed")
        } finally {
            safely { hooks.forEach { it.onToolExecutionFinished(tool) } }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (_: Throwable) { }
    }
}
