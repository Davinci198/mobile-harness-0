package com.jarves.mh.tools.device

interface ClipboardAdapter {
    fun copy(text: String)
    fun read(): String
}

interface ToastAdapter {
    fun show(text: String): Boolean
}

interface NotificationAdapter {
    fun notify(title: String, text: String): DeviceNotificationDispatch
}

interface HttpsLinkAdapter {
    fun open(url: String)
}

/** Flattened snapshot of the on-screen accessibility tree. */
data class UiNodeSnapshot(
    val id: String,
    val className: String,
    val text: String,
    val contentDescription: String,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val boundsLeft: Int,
    val boundsTop: Int,
    val boundsRight: Int,
    val boundsBottom: Int,
)

interface AccessibilityAdapter {
    /** True once the user has enabled MH's accessibility service in system settings. */
    fun isServiceEnabled(): Boolean

    /** Flattened, bounded snapshot of the current window's node tree, or null when unavailable. */
    fun readTree(maxNodes: Int): List<UiNodeSnapshot>?

    /** Perform a click on the node with [nodeId], or report failure. */
    fun clickNode(nodeId: String): Boolean
}
