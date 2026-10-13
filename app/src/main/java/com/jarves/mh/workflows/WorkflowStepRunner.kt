package com.jarves.mh.workflows

import com.jarves.mh.tools.calc.CalculateTool
import com.jarves.mh.tools.calc.CalcToolResult
import com.jarves.mh.tools.calc.SleepTool
import com.jarves.mh.tools.device.DeviceToolResult
import com.jarves.mh.tools.device.DeviceTools
import com.jarves.mh.tools.file.FileToolResult
import com.jarves.mh.tools.file.FileTools
import com.jarves.mh.tools.memory.MemoryToolResult
import com.jarves.mh.tools.memory.MemoryTools
import com.jarves.mh.tools.network.NetworkToolResult
import com.jarves.mh.tools.network.NetworkTools
import com.jarves.mh.tools.terminal.TerminalToolResult
import com.jarves.mh.tools.terminal.TerminalTools

/**
 * Executes one workflow step with the in-process MH tool implementations.
 * Every tool is constructed with the same permission gate the executor
 * used, so the [com.jarves.mh.tools.ToolPermissionGate.allowOnce] grant
 * handed over after an approval is consumed by the tool's own gate check.
 */
class WorkflowStepRunner(
    private val projectSlug: String,
    private val fileTools: FileTools,
    private val terminalTools: TerminalTools,
    private val networkTools: NetworkTools,
    private val deviceTools: DeviceTools,
    private val memoryTools: MemoryTools,
    private val calculateTool: CalculateTool,
    private val sleepTool: SleepTool,
) {

    suspend fun run(step: WorkflowStep): StepOutcome {
        val args = step.arguments
        fun arg(key: String): String = args[key].orEmpty()
        return when (val tool = step.toolName) {
            "Calculate" -> calc(calculateTool.calculate(arg("expression")))
            "Sleep" -> {
                val raw = arg("durationMs")
                calc(
                    if (raw.isBlank()) sleepTool.sleep()
                    else raw.toLongOrNull()?.let { sleepTool.sleep(it) }
                        ?: CalcToolResult.Error("Sleep", "Invalid durationMs: $raw"),
                )
            }
            "Read" -> file(fileTools.read(arg("path")))
            "Write" -> file(fileTools.write(arg("path"), arg("content")))
            "Edit" -> file(fileTools.apply(arg("path"), arg("old"), arg("new")))
            "Grep" -> file(fileTools.grep(arg("path"), arg("pattern")))
            "Glob" -> {
                val path = arg("path").ifBlank { "." }
                file(fileTools.find(path, arg("glob")))
            }
            "Bash" -> terminal(
                terminalTools.execute(
                    projectSlug = projectSlug,
                    command = arg("command"),
                    timeoutMs = arg("timeoutMs").toLongOrNull()
                        ?: com.jarves.mh.tools.terminal.TerminalToolLimits.DEFAULT_TIMEOUT_MS,
                ),
            )
            "VisitWeb" -> network(networkTools.visitWeb(com.jarves.mh.tools.network.WebPageRequest(url = arg("url"))))
            "HttpRequest" -> network(
                networkTools.httpRequest(
                    com.jarves.mh.tools.network.HttpRequestSpec(
                        url = arg("url"),
                        method = arg("method").ifBlank { "GET" },
                    ),
                ),
            )
            "DownloadFile" -> network(
                networkTools.downloadFile(
                    com.jarves.mh.tools.network.DownloadRequest(url = arg("url"), filename = arg("filename")),
                ),
            )
            "ClipboardCopy" -> device(deviceTools.copyToClipboard(arg("text")))
            "ClipboardRead" -> device(deviceTools.readClipboard())
            "DeviceToast" -> device(deviceTools.showToast(arg("text")))
            "DeviceNotification" -> device(deviceTools.sendNotification(arg("title"), arg("text")))
            "DeviceOpenHttpsLink" -> device(deviceTools.openHttpsLink(arg("url")))
            "UiReadTree" -> device(deviceTools.readUiTree())
            "UiStatus" -> device(deviceTools.accessibilityStatus())
            "UiClick" -> device(deviceTools.clickUiNode(arg("nodeId")))
            "MemoryCreate" -> memory(
                memoryTools.create(
                    title = arg("title"),
                    content = arg("content"),
                    path = arg("path"),
                    tags = arg("tags").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                ),
            )
            "MemoryRead" -> memory(memoryTools.read(arg("id")))
            "MemorySearch" -> memory(memoryTools.search(arg("query")))
            "MemoryList" -> memory(memoryTools.list())
            else -> StepOutcome(false, "Unsupported tool: $tool")
        }
    }

    private fun calc(result: CalcToolResult): StepOutcome = when (result) {
        is CalcToolResult.Error -> StepOutcome(false, result.message)
        is CalcToolResult.Calculated -> StepOutcome(true, "${result.expression} = ${result.value}")
        is CalcToolResult.Slept -> StepOutcome(true, "slept ${result.durationMs} ms")
    }

    private fun file(result: FileToolResult): StepOutcome = when (result) {
        is FileToolResult.FileToolError -> StepOutcome(false, result.message)
        is FileToolResult.FileContent ->
            StepOutcome(true, "read ${result.path} (${result.totalLines} lines${if (result.truncated) ", truncated" else ""})")
        is FileToolResult.FileWritten ->
            StepOutcome(true, "wrote ${result.path} (${result.bytes} bytes${if (result.appended) ", appended" else ""})")
        is FileToolResult.FileApplied -> StepOutcome(true, "edited ${result.path} (${result.replacements} replacements)")
        is FileToolResult.GrepMatches ->
            StepOutcome(true, "${result.matches.size} matches for '${result.pattern}'${if (result.truncated) ", truncated" else ""}")
        is FileToolResult.GrepMatch -> StepOutcome(true, "${result.path}:${result.lineNumber}")
        is FileToolResult.FoundFiles ->
            StepOutcome(true, "${result.files.size} files matching '${result.glob}'${if (result.truncated) ", truncated" else ""}")
    }

    private fun terminal(result: TerminalToolResult): StepOutcome = when (result) {
        is TerminalToolResult.Error -> StepOutcome(false, result.message)
        is TerminalToolResult.Completed ->
            StepOutcome(true, "exit ${result.exitCode}, ${result.output.length} chars output")
        is TerminalToolResult.TimedOut -> StepOutcome(false, "timed out after ${result.timeoutMs} ms")
    }

    private fun network(result: NetworkToolResult): StepOutcome = when (result) {
        is NetworkToolResult.Error -> StepOutcome(false, result.message)
        is NetworkToolResult.Page -> StepOutcome(true, "page '${result.page.title}' (${result.page.text.length} chars)")
        is NetworkToolResult.Http ->
            StepOutcome(true, "HTTP ${result.response.statusCode} ${result.response.statusMessage} (${result.response.body.size} bytes)")
        is NetworkToolResult.Downloaded ->
            StepOutcome(true, "downloaded ${result.download.path} (${result.download.bytes} bytes)")
    }

    private fun device(result: DeviceToolResult): StepOutcome = when (result) {
        is DeviceToolResult.Error -> StepOutcome(false, result.message)
        is DeviceToolResult.Copied -> StepOutcome(true, "copied ${result.characters} chars to clipboard")
        is DeviceToolResult.ClipboardRead ->
            StepOutcome(true, "clipboard: ${result.text.take(60)}${if (result.truncated) "…" else ""}")
        is DeviceToolResult.ToastDispatched -> StepOutcome(true, "toast shown")
        is DeviceToolResult.NotificationDispatched -> StepOutcome(true, "notification ${result.notificationId} sent")
        is DeviceToolResult.LinkOpened -> StepOutcome(true, "opened ${result.url}")
        is DeviceToolResult.UiTreeRead -> StepOutcome(true, "read ${result.nodes} UI nodes")
        is DeviceToolResult.UiNodeClicked -> StepOutcome(true, "clicked UI node ${result.nodeId}")
        is DeviceToolResult.UiStatus ->
            StepOutcome(true, if (result.enabled) "accessibility enabled" else "accessibility disabled")
    }

    private fun memory(result: MemoryToolResult): StepOutcome = when (result) {
        is MemoryToolResult.Error -> StepOutcome(false, result.message)
        is MemoryToolResult.Created -> StepOutcome(true, "created memory '${result.memory.title}' (${result.memory.id})")
        is MemoryToolResult.Found -> StepOutcome(true, "memory '${result.memory.title}'")
        is MemoryToolResult.Updated -> StepOutcome(true, "updated memory '${result.memory.title}'")
        is MemoryToolResult.Deleted -> StepOutcome(true, "deleted memory ${result.memoryId}")
        is MemoryToolResult.Memories -> StepOutcome(true, "${result.total} memories")
        is MemoryToolResult.SearchResults -> StepOutcome(true, "${result.total} matches")
        is MemoryToolResult.Linked -> StepOutcome(true, "linked ${result.link.fromId} → ${result.link.toId}")
        is MemoryToolResult.Unlinked -> StepOutcome(true, "unlinked ${result.linkId}")
        is MemoryToolResult.Links -> StepOutcome(true, "${result.total} links")
    }
}
