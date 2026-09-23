package com.jarves.mh.tools.device

object DeviceToolLimits {
    const val MAX_CLIPBOARD_TEXT_CHARS = 65_536
    const val MAX_TOAST_CHARS = 240
    const val MAX_NOTIFICATION_TITLE_CHARS = 80
    const val MAX_NOTIFICATION_TEXT_CHARS = 1_000
    const val MAX_HTTPS_URL_CHARS = 2_048
    const val NOTIFICATION_CHANNEL_ID = "device-tools"
    const val NOTIFICATION_ID = 43
}

enum class DeviceErrorCode {
    INVALID_INPUT,
    APPROVAL_REQUIRED,
    PERMISSION_DENIED,
    PERMISSION_REQUIRED,
    NOTIFICATIONS_DISABLED,
    NO_HANDLER,
    ACTIVITY_LAUNCH_BLOCKED,
    CLIPBOARD_UNAVAILABLE,
    EMPTY_CLIPBOARD,
    ADAPTER_ERROR,
}

class DeviceOperationException(
    val code: DeviceErrorCode,
    message: String,
) : IllegalStateException(message)

data class DeviceNotificationDispatch(
    val channelId: String,
    val notificationId: Int,
)

sealed interface DeviceToolResult {
    data class Copied(val characters: Int) : DeviceToolResult
    data class ClipboardRead(val text: String, val truncated: Boolean) : DeviceToolResult
    data class ToastDispatched(val dispatched: Boolean) : DeviceToolResult
    data class NotificationDispatched(
        val channelId: String,
        val notificationId: Int,
    ) : DeviceToolResult
    data class LinkOpened(val url: String) : DeviceToolResult
    data class Error(
        val tool: String,
        val code: DeviceErrorCode,
        val message: String,
    ) : DeviceToolResult
}
