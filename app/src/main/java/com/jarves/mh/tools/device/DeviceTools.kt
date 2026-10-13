package com.jarves.mh.tools.device

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionGateDecision
import java.net.URI

class DeviceTools(
    private val clipboard: ClipboardAdapter,
    private val toast: ToastAdapter,
    private val notifications: NotificationAdapter,
    private val links: HttpsLinkAdapter,
    private val accessibility: AccessibilityAdapter,
    private val gate: ToolPermissionGate,
    private val hooks: List<AIToolHook> = emptyList(),
) {
    /**
     * The permission gate backing every tool here. Exposed so a host that
     * pre-asks for approval out-of-band (the chat agent's `/device` loopback
     * route) can arm a one-shot [ToolPermissionGate.allowOnce] grant before it
     * re-dispatches the tool, mirroring [com.jarves.mh.workflows.WorkflowReplayExecutor].
     */
    val permissionGate: ToolPermissionGate get() = gate

    fun copyToClipboard(text: String): DeviceToolResult {
        val tool = "ClipboardCopy"
        if (text.isEmpty()) return error(tool, DeviceErrorCode.INVALID_INPUT, "Clipboard text is empty")
        if (text.length > DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS) {
            return error(tool, DeviceErrorCode.INVALID_INPUT, "Clipboard text is too large")
        }
        return execute(tool, "copy ${text.length} clipboard characters") {
            clipboard.copy(text)
            DeviceToolResult.Copied(text.length)
        }
    }

    fun readClipboard(): DeviceToolResult = execute("ClipboardRead", "read clipboard") {
        val text = clipboard.read()
        if (text.isEmpty()) throw DeviceOperationException(DeviceErrorCode.EMPTY_CLIPBOARD, "Clipboard is empty")
        val truncated = text.length > DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS
        val bounded = if (truncated) text.take(DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS) else text
        DeviceToolResult.ClipboardRead(bounded, truncated)
    }

    fun showToast(text: String): DeviceToolResult {
        val tool = "DeviceToast"
        val normalized = text.trim()
        if (normalized.isEmpty()) return error(tool, DeviceErrorCode.INVALID_INPUT, "Toast text is empty")
        if (normalized.length > DeviceToolLimits.MAX_TOAST_CHARS) {
            return error(tool, DeviceErrorCode.INVALID_INPUT, "Toast text is too long")
        }
        return execute(tool, "show toast: $normalized") {
            if (!toast.show(normalized)) throw DeviceOperationException(DeviceErrorCode.ADAPTER_ERROR, "Toast could not be dispatched")
            DeviceToolResult.ToastDispatched(true)
        }
    }

    fun sendNotification(title: String, text: String): DeviceToolResult {
        val tool = "DeviceNotification"
        val normalizedTitle = title.trim()
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return error(tool, DeviceErrorCode.INVALID_INPUT, "Notification text is empty")
        if (normalizedTitle.length > DeviceToolLimits.MAX_NOTIFICATION_TITLE_CHARS) {
            return error(tool, DeviceErrorCode.INVALID_INPUT, "Notification title is too long")
        }
        if (normalizedText.length > DeviceToolLimits.MAX_NOTIFICATION_TEXT_CHARS) {
            return error(tool, DeviceErrorCode.INVALID_INPUT, "Notification text is too long")
        }
        return execute(tool, "send notification: $normalizedTitle") {
            val dispatch = notifications.notify(normalizedTitle, normalizedText)
            DeviceToolResult.NotificationDispatched(dispatch.channelId, dispatch.notificationId)
        }
    }

    fun openHttpsLink(url: String): DeviceToolResult {
        val tool = "DeviceOpenHttpsLink"
        val normalized = validateHttpsUrl(url)
            ?: return error(tool, DeviceErrorCode.INVALID_INPUT, "Only absolute HTTPS links without user info are allowed")
        return execute(tool, "open HTTPS link: $normalized") {
            links.open(normalized)
            DeviceToolResult.LinkOpened(normalized)
        }
    }

    fun readUiTree(): DeviceToolResult {
        val tool = "UiReadTree"
        return execute(tool, "read on-screen UI tree") {
            if (!accessibility.isServiceEnabled()) {
                throw DeviceOperationException(
                    DeviceErrorCode.PERMISSION_REQUIRED,
                    "Accessibility service is not enabled",
                )
            }
            val nodes = accessibility.readTree(DeviceToolLimits.MAX_UI_TREE_NODES)
                ?: throw DeviceOperationException(DeviceErrorCode.ADAPTER_ERROR, "UI tree is unavailable")
            DeviceToolResult.UiTreeRead(nodes.size)
        }
    }

    fun clickUiNode(nodeId: String): DeviceToolResult {
        val tool = "UiClick"
        val normalized = nodeId.trim()
        if (normalized.isEmpty()) return error(tool, DeviceErrorCode.INVALID_INPUT, "Node id is empty")
        if (normalized.length > DeviceToolLimits.MAX_UI_NODE_ID_CHARS) {
            return error(tool, DeviceErrorCode.INVALID_INPUT, "Node id is too long")
        }
        return execute(tool, "click UI node $normalized") {
            if (!accessibility.isServiceEnabled()) {
                throw DeviceOperationException(
                    DeviceErrorCode.PERMISSION_REQUIRED,
                    "Accessibility service is not enabled",
                )
            }
            if (!accessibility.clickNode(normalized)) {
                throw DeviceOperationException(DeviceErrorCode.ADAPTER_ERROR, "Node could not be clicked")
            }
            DeviceToolResult.UiNodeClicked(normalized)
        }
    }

    fun accessibilityStatus(): DeviceToolResult = execute("UiStatus", "check accessibility status") {
        DeviceToolResult.UiStatus(accessibility.isServiceEnabled())
    }

    private fun execute(tool: String, explanation: String, block: () -> DeviceToolResult): DeviceToolResult {
        when (val decision = gate.evaluate(tool, explanation.take(240))) {
            is ToolPermissionGateDecision.Allowed -> Unit
            is ToolPermissionGateDecision.NeedsApproval ->
                return error(tool, DeviceErrorCode.APPROVAL_REQUIRED, "Approval required (ASK)")
            is ToolPermissionGateDecision.Blocked ->
                return error(tool, DeviceErrorCode.PERMISSION_DENIED, decision.reason)
        }
        safely { hooks.forEach { it.onToolExecutionStarted(tool) } }
        return try {
            val result = block()
            safely {
                hooks.forEach {
                    it.onToolExecutionResult(tool, result !is DeviceToolResult.Error, result.summary())
                }
            }
            result
        } catch (error: DeviceOperationException) {
            val result = DeviceToolResult.Error(tool, error.code, error.message ?: "Device operation failed")
            safely { hooks.forEach { it.onToolExecutionResult(tool, false, result.message) } }
            result
        } catch (error: Throwable) {
            safely { hooks.forEach { it.onToolExecutionError(tool, error) } }
            DeviceToolResult.Error(tool, DeviceErrorCode.ADAPTER_ERROR, error.message ?: "Device operation failed")
        } finally {
            safely { hooks.forEach { it.onToolExecutionFinished(tool) } }
        }
    }

    private fun validateHttpsUrl(value: String): String? {
        val url = value.trim()
        if (url.isEmpty() || url.length > DeviceToolLimits.MAX_HTTPS_URL_CHARS) return null
        if (url.any { it.isISOControl() } || url.contains('\\')) return null
        return try {
            val uri = URI(url)
            if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
            if (uri.rawUserInfo != null) return null
            url
        } catch (_: Throwable) {
            null
        }
    }

    private fun error(tool: String, code: DeviceErrorCode, message: String) = DeviceToolResult.Error(tool, code, message)

    private fun DeviceToolResult.summary(): String = when (this) {
        is DeviceToolResult.Copied -> "copied $characters characters"
        is DeviceToolResult.ClipboardRead -> "read ${text.length} clipboard characters"
        is DeviceToolResult.ToastDispatched -> "toast dispatched"
        is DeviceToolResult.NotificationDispatched -> "notification $notificationId dispatched"
        is DeviceToolResult.LinkOpened -> "opened HTTPS link"
        is DeviceToolResult.UiTreeRead -> "read $nodes UI nodes"
        is DeviceToolResult.UiNodeClicked -> "clicked UI node $nodeId"
        is DeviceToolResult.UiStatus -> if (enabled) "accessibility enabled" else "accessibility disabled"
        is DeviceToolResult.Error -> message
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }
}
