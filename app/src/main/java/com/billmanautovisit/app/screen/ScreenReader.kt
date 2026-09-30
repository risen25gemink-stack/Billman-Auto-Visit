package com.billmanautovisit.app.screen

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.billmanautovisit.app.core.TaggingGate


data class WorkOrderCandidate(
    val kodeWo: String,
    val idPelanggan: String
)

data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int
)

class ScreenReader(
    private val service: AccessibilityService
) {
    // Billman menampilkan Kode WO dalam dua bentuk yang sudah muncul di flow:
    // P323... dan nomor WO numerik panjang (contoh visual: 6013232020260921000508).
    // ID pelanggan tetap dibatasi 9-15 digit agar tidak tertukar dengan Kode WO.
    private val regexKodeWo = Regex("^(?:P323\\d+|\\d{16,30})$")
    private val regexIdPelanggan = Regex("^\\d{9,15}$")
    private val regexPagination = Regex(
        "Halaman\\s+(\\d+)\\s+dari\\s+(\\d+)",
        RegexOption.IGNORE_CASE
    )

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

    fun readPaginationInfo(): PaginationInfo? {
        val root = currentRoot() ?: return null
        val pageNode = findFirst(root) { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            regexPagination.containsMatchIn(text)
        } ?: return null

        val text = pageNode.text?.toString()?.trim().orEmpty()
        val match = regexPagination.find(text) ?: return null
        val current = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val total = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        if (current < 1 || total < 1 || current > total) return null
        return PaginationInfo(current, total)
    }

    fun findNextPageButton(): AccessibilityNodeInfo? =
        findFirstResourceId(NEXT_PAGE_IDS)
            ?: findNodeByContentDescription("next page")
            ?: findNodeByContentDescription("Next")

    fun findPreviousPageButton(): AccessibilityNodeInfo? =
        findFirstResourceId(PREVIOUS_PAGE_IDS)
            ?: findNodeByContentDescription("prev page")
            ?: findNodeByContentDescription("Previous")

    fun findEditableFieldByLabel(label: String): AccessibilityNodeInfo? {
        val root = currentRoot() ?: return null

        val directlyLabeledField = findFirst(root) { node ->
            isEditable(node) && nodeMatchesLabel(node, label)
        }
        if (directlyLabeledField != null) return directlyLabeledField

        val labelNode = findFirst(root) { node -> nodeMatchesLabel(node, label) } ?: return null
        if (isEditable(labelNode)) return labelNode

        findEditableDescendant(labelNode)?.let { return it }
        findEditableAfterNode(labelNode)?.let { return it }

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

        for (index in visibleTexts.indices) {
            val kodeWo = visibleTexts[index].trim()
            if (!regexKodeWo.matches(kodeWo)) continue

            // Billman dapat membungkus ID pelanggan pada node yang berbeda,
            // sehingga ID tidak selalu menjadi visibleTexts[index + 1].
            val searchEnd = minOf(index + MAX_ID_LOOKAHEAD, visibleTexts.lastIndex)
            for (candidateIndex in (index + 1)..searchEnd) {
                val raw = visibleTexts[candidateIndex].trim()
                val directId = extractCustomerId(raw) ?: raw.replace("\\s", "").takeIf { regexIdPelanggan.matches(it) }
                if (directId != null) {
                    if (result.none { it.kodeWo == kodeWo }) {
                        result += WorkOrderCandidate(kodeWo, directId)
                    }
                    break
                }

                // Jangan mengambil ID dari kartu WO berikutnya.
                if (regexKodeWo.matches(raw)) break
            }
        }

        return result
    }

    fun describeWorkOrderScreen(maxItems: Int = 100): String {
        val root = currentRoot() ?: return "rootInActiveWindow=null"
        val lines = mutableListOf<String>()
        collectDiagnosticNodes(root, lines, maxItems)
        return buildString {
            append("root=ok; visibleNodes=").append(lines.size).append('\n')
            lines.forEachIndexed { index, line ->
                append(index + 1).append(". ").append(line).append('\\n')
            }
        }.trimEnd()
    }

    private fun extractCustomerId(value: String): String? {
        val labeled = Regex(
            "(?:ID\\s*Pelanggan)\\s*[:\\-]?\\s*(\\d{9,15})",
            RegexOption.IGNORE_CASE
        ).find(value)
        return labeled?.groupValues?.getOrNull(1)
    }

    private fun collectDiagnosticNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<String>,
        maxItems: Int
    ) {
        if (output.size >= maxItems) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()) {
                output += "text=\${text.take(80).ifEmpty { "-" }}; desc=\${desc.take(60).ifEmpty { "-" }}; id=\${viewId.ifEmpty { "-" }}; class=\${node.className?.toString().orEmpty()}"
            }
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collectDiagnosticNodes(child, output, maxItems)
            if (output.size >= maxItems) return
        }
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

    fun showNodeOnScreen(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (
                candidate.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
                )
            ) {
                return true
            }
            current = candidate.parent
        }
        return false
    }

    fun scrollForwardFrom(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node.parent
        repeat(10) {
            val candidate = current ?: return@repeat
            if (candidate.isScrollable &&
                candidate.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                return true
            }
            current = candidate.parent
        }
        return scrollForwardOnce()
    }

    fun scrollForwardOnce(): Boolean {
        val root = currentRoot() ?: return false
        val scrollables = mutableListOf<AccessibilityNodeInfo>()
        collectScrollableNodes(root, scrollables)

        for (node in scrollables) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                return true
            }
        }
        return false
    }

    /**
     * Mengisi EditText tanpa meminta focus. ACTION_SET_TEXT melalui Accessibility
     * tidak membutuhkan keyboard fisik/IME dan pada mayoritas perangkat tidak
     * memunculkan soft keyboard. Ini dipakai untuk Nama Penghuni.
     */
    fun setTextWithoutKeyboard(node: AccessibilityNodeInfo, value: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                value
            )
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        return result
    }

    /** Fallback untuk field yang memang membutuhkan focus. */
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

    fun clearFocus(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)

    fun isInputMethodVisible(): Boolean =
        service.windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && window.root != null
        }

    fun performBack(): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private fun findFirstResourceId(ids: List<String>): AccessibilityNodeInfo? {
        for (id in ids) {
            findNodeByResourceId(id)?.let { return it }
        }
        return null
    }

    private fun supportsClick(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.any { action ->
            action.id == AccessibilityNodeInfo.ACTION_CLICK
        }

    private fun nodeMatchesLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        val wanted = normalizeFormLabel(label)
        val values = listOfNotNull(
            node.text?.toString(),
            node.hintText?.toString(),
            node.contentDescription?.toString()
        )
        return values.any { normalizeFormLabel(it) == wanted }
    }

    private fun normalizeFormLabel(value: String): String =
        value
            .trim()
            .replace(Regex("[\\s:*]+$"), "")
            .replace(Regex("\\s+"), " ")
            .lowercase()

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

    private fun findEditableAfterNode(startNode: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var branch: AccessibilityNodeInfo? = startNode
        var parent = startNode.parent

        repeat(5) {
            val currentBranch = branch ?: return@repeat
            val currentParent = parent ?: return@repeat

            val branchIndex = findChildIndex(currentParent, currentBranch)
            if (branchIndex >= 0) {
                for (index in (branchIndex + 1) until currentParent.childCount) {
                    val sibling = currentParent.getChild(index) ?: continue
                    findEditableDescendant(sibling)?.let { return it }
                }
            }

            branch = currentParent
            parent = currentParent.parent
        }
        return null
    }

    private fun findChildIndex(parent: AccessibilityNodeInfo, child: AccessibilityNodeInfo): Int {
        for (index in 0 until parent.childCount) {
            val candidate = parent.getChild(index) ?: continue
            if (candidate == child) return index
        }
        return -1
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo, output: MutableList<String>) {
        if (node.isVisibleToUser) {
            node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(output::add)
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectVisibleTexts(it, output) }
        }
    }

    private fun collectScrollableNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityNodeInfo>
    ) {
        if (node.isScrollable) output += node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { collectScrollableNodes(it, output) }
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
        private const val MAX_ID_LOOKAHEAD = 8

        private val NEXT_PAGE_IDS = listOf(
            "dev.iconpln.mims:id/iv_next_page",
            "dev.iconpln.mims:id/btn_next_page"
        )
        private val PREVIOUS_PAGE_IDS = listOf(
            "dev.iconpln.mims:id/iv_previous_page",
            "dev.iconpln.mims:id/btn_prev_page"
        )
    }
}
