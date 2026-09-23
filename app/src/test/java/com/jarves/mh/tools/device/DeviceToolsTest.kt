package com.jarves.mh.tools.device

import com.jarves.mh.tools.AIToolHook
import com.jarves.mh.tools.AIToolHookDecision
import com.jarves.mh.tools.ToolPermissionGate
import com.jarves.mh.tools.ToolPermissionLevel
import com.jarves.mh.tools.ToolPermissionStorage
import com.jarves.mh.tools.ToolPermissionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeClipboard(var value: String = "") : ClipboardAdapter {
    var copies = 0
    var reads = 0
    var failure: DeviceOperationException? = null
    override fun copy(text: String) {
        failure?.let { throw it }
        copies++
        value = text
    }
    override fun read(): String {
        reads++
        failure?.let { throw it }
        return value
    }
}

private class FakeToast(var result: Boolean = true) : ToastAdapter {
    var calls = 0
    var text = ""
    override fun show(text: String): Boolean {
        calls++
        this.text = text
        return result
    }
}

private class FakeNotifications(
    var dispatch: DeviceNotificationDispatch = DeviceNotificationDispatch(DeviceToolLimits.NOTIFICATION_CHANNEL_ID, DeviceToolLimits.NOTIFICATION_ID),
) : NotificationAdapter {
    var calls = 0
    var failure: DeviceOperationException? = null
    override fun notify(title: String, text: String): DeviceNotificationDispatch {
        calls++
        failure?.let { throw it }
        return dispatch
    }
}

private class FakeLinks : HttpsLinkAdapter {
    var calls = 0
    var url = ""
    var failure: DeviceOperationException? = null
    override fun open(url: String) {
        calls++
        this.url = url
        failure?.let { throw it }
    }
}

class DeviceToolsTest {
    private lateinit var store: ToolPermissionStore
    private lateinit var clipboard: FakeClipboard
    private lateinit var toast: FakeToast
    private lateinit var notifications: FakeNotifications
    private lateinit var links: FakeLinks
    private lateinit var tools: DeviceTools

    @Before
    fun setUp() {
        store = ToolPermissionStore(object : ToolPermissionStorage {
            private val values = mutableMapOf<String, String>()
            override fun read(key: String): String? = values[key]
            override fun write(key: String, value: String?) {
                if (value == null) values.remove(key) else values[key] = value
            }
        })
        store.globalDefault = ToolPermissionLevel.ALLOW
        clipboard = FakeClipboard()
        toast = FakeToast()
        notifications = FakeNotifications()
        links = FakeLinks()
        tools = DeviceTools(clipboard, toast, notifications, links, ToolPermissionGate(store))
    }

    @Test
    fun copyPreservesTextAndReturnsCount() {
        val result = tools.copyToClipboard("  secret\u0000value  ")
        assertTrue(result is DeviceToolResult.Copied)
        assertEquals("  secret\u0000value  ", clipboard.value)
        assertEquals(1, clipboard.copies)
    }

    @Test
    fun copyRejectsEmptyAndOversizedBeforeAdapter() {
        assertError(tools.copyToClipboard(""), DeviceErrorCode.INVALID_INPUT)
        assertError(tools.copyToClipboard("x".repeat(DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS + 1)), DeviceErrorCode.INVALID_INPUT)
        assertEquals(0, clipboard.copies)
    }

    @Test
    fun clipboardReadTruncatesAndReportsEmpty() {
        clipboard.value = "abcdef"
        val result = tools.readClipboard() as DeviceToolResult.ClipboardRead
        assertEquals("abcdef", result.text)
        assertTrue(!result.truncated)
        clipboard.value = "x".repeat(DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS + 10)
        val truncated = tools.readClipboard() as DeviceToolResult.ClipboardRead
        assertEquals(DeviceToolLimits.MAX_CLIPBOARD_TEXT_CHARS, truncated.text.length)
        assertTrue(truncated.truncated)
        clipboard.value = ""
        assertError(tools.readClipboard(), DeviceErrorCode.EMPTY_CLIPBOARD)
    }

    @Test
    fun toastValidatesAndDispatches() {
        val result = tools.showToast(" hello ")
        assertEquals("hello", toast.text)
        assertTrue(result is DeviceToolResult.ToastDispatched)
        assertError(tools.showToast(" "), DeviceErrorCode.INVALID_INPUT)
        assertError(tools.showToast("x".repeat(DeviceToolLimits.MAX_TOAST_CHARS + 1)), DeviceErrorCode.INVALID_INPUT)
    }

    @Test
    fun notificationMapsPermissionAndDisabledErrors() {
        notifications.failure = DeviceOperationException(DeviceErrorCode.PERMISSION_REQUIRED, "denied")
        assertError(tools.sendNotification("", "body"), DeviceErrorCode.PERMISSION_REQUIRED)
        assertEquals(1, notifications.calls)
        notifications.failure = DeviceOperationException(DeviceErrorCode.NOTIFICATIONS_DISABLED, "disabled")
        assertError(tools.sendNotification("title", "body"), DeviceErrorCode.NOTIFICATIONS_DISABLED)
    }

    @Test
    fun notificationValidatesAndReturnsFixedDispatch() {
        val result = tools.sendNotification(" title ", " body ") as DeviceToolResult.NotificationDispatched
        assertEquals(DeviceToolLimits.NOTIFICATION_ID, result.notificationId)
        assertEquals(DeviceToolLimits.NOTIFICATION_CHANNEL_ID, result.channelId)
        assertError(tools.sendNotification("x".repeat(81), "body"), DeviceErrorCode.INVALID_INPUT)
        assertError(tools.sendNotification("title", "x".repeat(1001)), DeviceErrorCode.INVALID_INPUT)
    }

    @Test
    fun onlyStrictHttpsLinksAreOpened() {
        val result = tools.openHttpsLink(" https://example.com/path?q=1 ") as DeviceToolResult.LinkOpened
        assertEquals("https://example.com/path?q=1", result.url)
        assertEquals(result.url, links.url)
        for (url in listOf(
            "http://example.com",
            "intent://example.com",
            "content://x",
            "file:///x",
            "javascript:alert(1)",
            "https://user:pass@example.com",
            "https:///path",
            "example.com",
            "https://example.com\\@evil.com",
        )) {
            assertError(tools.openHttpsLink(url), DeviceErrorCode.INVALID_INPUT)
        }
        assertEquals(1, links.calls)
    }

    @Test
    fun askAndForbidBlockEverySideEffect() {
        store.globalDefault = ToolPermissionLevel.ASK
        assertError(tools.copyToClipboard("x"), DeviceErrorCode.APPROVAL_REQUIRED)
        assertError(tools.readClipboard(), DeviceErrorCode.APPROVAL_REQUIRED)
        assertError(tools.showToast("x"), DeviceErrorCode.APPROVAL_REQUIRED)
        assertError(tools.sendNotification("x", "x"), DeviceErrorCode.APPROVAL_REQUIRED)
        assertError(tools.openHttpsLink("https://example.com"), DeviceErrorCode.APPROVAL_REQUIRED)
        assertEquals(0, clipboard.copies + clipboard.reads + toast.calls + notifications.calls + links.calls)

        store.globalDefault = ToolPermissionLevel.FORBID
        assertError(tools.copyToClipboard("x"), DeviceErrorCode.PERMISSION_DENIED)
        assertEquals(0, clipboard.copies)
    }

    @Test
    fun perToolOverrideWinsOverGlobalAsk() {
        store.globalDefault = ToolPermissionLevel.ASK
        store.setOverride("ClipboardCopy", ToolPermissionLevel.ALLOW)
        assertTrue(tools.copyToClipboard("x") is DeviceToolResult.Copied)
    }

    @Test
    fun hookCanVetoBeforeSideEffect() {
        val blocking = DeviceTools(
            clipboard,
            toast,
            notifications,
            links,
            ToolPermissionGate(store, listOf(object : AIToolHook {
                override fun onToolCallIntercept(toolName: String, explanation: String) =
                    AIToolHookDecision.Block("blocked")
            })),
        )
        assertError(blocking.showToast("x"), DeviceErrorCode.PERMISSION_DENIED)
        assertEquals(0, toast.calls)
    }

    @Test
    fun hooksFireSuccessAndFailure() {
        val events = mutableListOf<String>()
        val gated = DeviceTools(
            clipboard,
            toast,
            notifications,
            links,
            ToolPermissionGate(store),
            listOf(object : AIToolHook {
                override fun onToolExecutionStarted(toolName: String) { events += "start:$toolName" }
                override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) {
                    events += "result:$toolName:$success"
                }
                override fun onToolExecutionFinished(toolName: String) { events += "end:$toolName" }
            }),
        )
        gated.showToast("ok")
        assertEquals(listOf("start:DeviceToast", "result:DeviceToast:true", "end:DeviceToast"), events)
    }

    @Test
    fun throwingHooksDoNotChangeResult() {
        val throwing = DeviceTools(
            clipboard,
            toast,
            notifications,
            links,
            ToolPermissionGate(store),
            listOf(object : AIToolHook {
                override fun onToolExecutionStarted(toolName: String) = throw IllegalStateException("hook")
                override fun onToolExecutionResult(toolName: String, success: Boolean, summary: String) = throw IllegalStateException("hook")
                override fun onToolExecutionFinished(toolName: String) = throw IllegalStateException("hook")
            }),
        )
        assertTrue(throwing.showToast("ok") is DeviceToolResult.ToastDispatched)
    }

    @Test
    fun knownToolsIncludeDeviceTools() {
        val expected = listOf(
            "ClipboardCopy",
            "ClipboardRead",
            "DeviceToast",
            "DeviceNotification",
            "DeviceOpenHttpsLink",
        )
        assertTrue(ToolPermissionStore.knownTools.containsAll(expected))
    }

    private fun assertError(result: DeviceToolResult, code: DeviceErrorCode) {
        assertTrue(result.toString(), result is DeviceToolResult.Error)
        assertEquals(code, (result as DeviceToolResult.Error).code)
    }
}
