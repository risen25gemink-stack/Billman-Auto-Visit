package com.billmanautovisit.app.camera

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo

class CameraTapper(
    private val service: AccessibilityService
) {
    private val billmanShutterId = "dev.iconpln.mims:id/iv_shutter"

    fun findShutterNode(): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null

        root.findAccessibilityNodeInfosByViewId(billmanShutterId)
            .firstOrNull { it.isVisibleToUser }
            ?.let { return it }

        return findFirst(root) { node ->
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            val resourceId = node.viewIdResourceName.orEmpty()
            node.isVisibleToUser && (
                description.contains("shutter", ignoreCase = true) ||
                    description.contains("capture", ignoreCase = true) ||
                    resourceId.contains("shutter", ignoreCase = true) ||
                    resourceId.contains("capture", ignoreCase = true)
                )
        }
    }

    fun tapShutter(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.isEnabled &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    private fun findFirst(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findFirst(child, predicate)?.let { return it }
        }
        return null
    }
}
