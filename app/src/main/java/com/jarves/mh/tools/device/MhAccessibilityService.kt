package com.jarves.mh.tools.device

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service backing [AndroidAccessibilityAdapter].
 *
 * Deliberately minimal: it holds a reference to the most recent root node and
 * exposes tree reads and node clicks. All permission gating lives in
 * [DeviceTools]; this service never makes policy decisions.
 */
class MhAccessibilityService : AccessibilityService() {

    @Volatile
    private var root: AccessibilityNodeInfo? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        root = null
        super.onDestroy()
    }

    /** Capture the current window root as the active tree. */
    fun refreshRoot() {
        root = rootInActiveWindow
    }

    fun currentRoot(): AccessibilityNodeInfo? = root

    fun performClick(node: AccessibilityNodeInfo): Boolean = node.performAction(
        AccessibilityNodeInfo.ACTION_CLICK,
    )

    companion object {
        @Volatile
        var instance: MhAccessibilityService? = null
            private set

        fun isEnabled(): Boolean = instance != null
    }
}
