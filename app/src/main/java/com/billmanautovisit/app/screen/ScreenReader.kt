package com.billmanautovisit.app.screen

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.billmanautovisit.app.core.TaggingGate


data class WorkOrderCandidate(
    val kodeWo: String,
    val idPelanggan: String
)

class ScreenReader(
    private val service: AccessibilityService
) {
    private val regexKodeWo = Regex("^P323\\d+$")
    private val regexIdPelanggan = Regex("^323\\d{6,}$")

    fun currentRoot(): AccessibilityNodeInfo? = service.rootInActiveWindow

    fun isTextVisible(text: String, exactMatch: Boolean = true): Boolean =
        findNodeByText(text, exactMatch) != null

    fun findNodeByText(text: String, exactMatch: Boolean = true): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        return findFirst(root) { node ->
            val nodeText = node.text?.toString()?.trim().orEmpty()
            if (exactMatch) nodeText == text else nodeText.contains(text, ignoreCase = true)
        }
    }

    fun findNodeByResourceId(id: String): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        return root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
    }

    fun findNodeByContentDescription(desc: String): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null
        return findFirst(root) { node ->
            node.contentDescription?.toString()?.trim()?.equals(desc, ignoreCase = true) == true
        }
    }

    fun findEditableFieldByLabel(label: String): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null

        val directlyLabeledField = findFirst(root) { node ->
            isEditable(node) && nodeMatchesLabel(node, label)
        }
        if (directlyLabeledField != null) return directlyLabeledField

        val labelNode = findFirst(root) { node -> nodeMatchesLabel(node, label) } ?: return null
        if (isEditable(labelNode)) return labelNode

        findEditableDescendant(labelNode)?.let { return it }

        var ancestor = labelNode.parent
        repeat(3) {
            val current = ancestor ?: return@repeat
            findEditableDescendant(current)?.let { return it }
            ancestor = current.parent
        }

        return null
    }

    fun readWorkOrderCandidates(): List<WorkOrderCandidate> {
        val root = currentRoot() ?: return emptyList()
        val visibleTexts = mutableListOf<String>()
        collectVisibleTexts(root, visibleTexts)

        val result = mutableListOf<WorkOrderCandidate>()
        for (index in 0 until visibleTexts.lastIndex) {
            val kodeWo = visibleTexts[index]
            val idPelanggan = visibleTexts[index + 1]
            if (regexKodeWo.matches(kodeWo) && regexIdPelanggan.matches(idPelanggan)) {
                if (result.none { it.idPelanggan == idPelanggan }) {
                    result += WorkOrderCandidate(kodeWo, idPelanggan)
                }
            }
        }
        return result
    }

    fun isNodeOrClickableAncestorEnabled(node: AccessibilityNodeInfo): Boolean {
        if (node.viewIdResourceName == BILLMAN_TAKE_PICTURE_ID && !TaggingGate.isReady()) {
            return false
        }

        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (candidate.isEnabled && supportsClick(candidate)) return true
            current = candidate.parent
        }
        return node.isEnabled && supportsClick(node)
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(8) {
            val candidate = current ?: return false
            if (candidate.isEnabled &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    fun scrollForwardOnce(): Boolean {
        val root = currentRoot() ?: return false
        val scrollable = findFirst(root) { node ->
            node.isVisibleToUser && node.isScrollable
        } ?: return false

        return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }

    fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    fun performBack(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun supportsClick(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_CLICK
        }

    private fun nodeMatchesLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        val values = listOfNotNull(
            node.text?.toString(),
            node.hintText?.toString(),
            node.contentDescription?.toString()
        ).map { it.trim() }
        return values.any { it.equals(label, ignoreCase = true) }
    }

    private fun isEditable(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || node.className?.toString() == "android.widget.EditText"

    private fun findEditableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditable(node)) return node
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findEditableDescendant(child)?.let { return it }
        }
        return null
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo, output: MutableList<String>) {
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectVisibleTexts(it, output) }
        }
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

    companion object {
        private const val BILLMAN_TAKE_PICTURE_ID = "dev.iconpln.mims:id/btn_take_picture"
    }
}
