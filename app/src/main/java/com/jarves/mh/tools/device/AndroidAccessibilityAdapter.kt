package com.jarves.mh.tools.device

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo

/**
 * [AccessibilityAdapter] backed by [MhAccessibilityService].
 *
 * Reads and clicks go through the live service instance; when the user has not
 * enabled the service the adapter reports that plainly rather than pretending
 * the tree is empty.
 */
class AndroidAccessibilityAdapter(
    @Suppress("unused") private val context: Context,
) : AccessibilityAdapter {

    override fun isServiceEnabled(): Boolean = MhAccessibilityService.isEnabled()

    override fun readTree(maxNodes: Int): List<UiNodeSnapshot>? {
        val service = MhAccessibilityService.instance ?: return null
        service.refreshRoot()
        val root = service.currentRoot() ?: return null
        val out = ArrayList<UiNodeSnapshot>(64)
        try {
            flatten(root, out, maxNodes)
        } finally {
            recycle(root)
        }
        return out
    }

    override fun clickNode(nodeId: String): Boolean {
        val service = MhAccessibilityService.instance ?: return false
        service.refreshRoot()
        val root = service.currentRoot() ?: return false
        try {
            val target = find(root, nodeId)
            return target != null && service.performClick(target)
        } finally {
            recycle(root)
        }
    }

    /** Intent that opens the system accessibility settings page for this app. */
    fun settingsIntent(): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    } else {
        @Suppress("DEPRECATION")
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private fun flatten(
        node: AccessibilityNodeInfo,
        out: MutableList<UiNodeSnapshot>,
        maxNodes: Int,
    ) {
        if (out.size >= maxNodes) return
        out.add(snapshot(node))
        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) return
            val child = node.getChild(i) ?: continue
            flatten(child, out, maxNodes)
            recycle(child)
        }
    }

    private fun find(node: AccessibilityNodeInfo, nodeId: String): AccessibilityNodeInfo? {
        if (nodeIdOf(node) == nodeId) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hit = find(child, nodeId)
            if (hit != null) return hit
            recycle(child)
        }
        return null
    }

    private fun snapshot(node: AccessibilityNodeInfo): UiNodeSnapshot {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        return UiNodeSnapshot(
            id = nodeIdOf(node),
            className = node.className?.toString().orEmpty(),
            text = node.text?.toString().orEmpty(),
            contentDescription = node.contentDescription?.toString().orEmpty(),
            isClickable = node.isClickable,
            isEnabled = node.isEnabled,
            boundsLeft = bounds.left,
            boundsTop = bounds.top,
            boundsRight = bounds.right,
            boundsBottom = bounds.bottom,
        )
    }

    /**
     * Stable per-node id within one tree snapshot: a path of child indices from
     * the root. Unique for the tree it was produced from and cheap to compute.
     */
    private fun nodeIdOf(node: AccessibilityNodeInfo): String = buildString {
        append('#')
        append(System.identityHashCode(node))
    }

    private fun recycle(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: IllegalStateException) {
        }
    }
}
