package com.billmanautovisit.app.engine

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.core.AutomationState
import com.billmanautovisit.app.data.LocalDataStore
import com.billmanautovisit.app.screen.ScreenReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class FinalFlowController(
    private val screenReader: ScreenReader,
    private val cameraTapper: CameraTapper,
    private val dataStore: LocalDataStore,
    private val resumeWoList: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = AtomicBoolean(false)

    private var currentRecordId: Long? = null
    private var camera3RetryCount = 0

    fun onBillmanEvent() {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return
        if (!isRelevantState(AutomationRuntime.currentState)) return
        if (!processing.compareAndSet(false, true)) return

        scope.launch {
            try {
                driveImplementedStates()
            } catch (error: Throwable) {
                failCurrentState("${error.javaClass.simpleName}: ${error.message ?: "unknown error"}")
            } finally {
                processing.set(false)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun driveImplementedStates() {
        resolveRecordIdIfNeeded()

        while (AutomationRuntime.isRunning && !AutomationRuntime.stopRequested) {
            val shouldContinue = when (AutomationRuntime.currentState) {
                AutomationState.TAP_CAMERA_3 -> handleTapCamera3()
                AutomationState.CAMERA_SHUTTER_3 -> handleCameraShutter3()
                AutomationState.WAIT_SELESAI -> handleWaitSelesai()
                AutomationState.TAP_SELESAI -> handleTapSelesai()
                else -> false
            }

            if (!shouldContinue) return
        }
    }

    private suspend fun handleTapCamera3(): Boolean {
        val cameraNode = waitWithRetry(AutomationState.TAP_CAMERA_3) {
            val node = findMigrationCameraNode()
            if (node != null && node.isVisibleToUser &&
                screenReader.isNodeOrClickableAncestorEnabled(node)
            ) {
                node
            } else {
                screenReader.scrollForwardOnce()
                null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (cameraNode == null) {
            failCurrentState("Ikon kamera Foto Bukti Pernyataan Migrasi ke Prabayar tidak ditemukan")
            return false
        }

        if (!screenReader.clickNode(cameraNode)) {
            failCurrentState("Gagal membuka kamera bukti migrasi prabayar")
            return false
        }

        camera3RetryCount = 0
        updateStateAndLog(
            AutomationState.TAP_CAMERA_3,
            "Kamera bukti migrasi prabayar dibuka"
        )
        AutomationRuntime.transitionTo(AutomationState.CAMERA_SHUTTER_3)
        return true
    }

    private suspend fun handleCameraShutter3(): Boolean {
        val shutter = poll(WAIT_TIMEOUT_MS) {
            cameraTapper.findShutterNode()
        }

        if (!AutomationRuntime.isRunning) return false
        if (shutter == null) {
            return retryCamera3OrFail("Tombol shutter kamera bukti migrasi tidak terdeteksi")
        }

        if (!cameraTapper.tapShutter(shutter)) {
            return retryCamera3OrFail("Shutter kamera bukti migrasi terdeteksi tetapi gagal ditekan")
        }

        updateStateAndLog(
            AutomationState.CAMERA_SHUTTER_3,
            "Shutter kamera bukti migrasi ditekan"
        )

        val returned = waitWithRetry(AutomationState.CAMERA_SHUTTER_3) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (returned != true) {
            failCurrentState("Tidak kembali ke layar Visit WO setelah foto bukti migrasi")
            return false
        }

        updateStateAndLog(
            AutomationState.CAMERA_SHUTTER_3,
            "Foto bukti migrasi selesai; menunggu tombol Selesai aktif"
        )
        AutomationRuntime.transitionTo(AutomationState.WAIT_SELESAI)
        return true
    }

    private suspend fun handleWaitSelesai(): Boolean {
        val enabledButton = waitWithRetry(AutomationState.WAIT_SELESAI) {
            val button = findSelesaiButton()
            when {
                button == null -> {
                    screenReader.scrollForwardOnce()
                    null
                }
                !button.isVisibleToUser -> {
                    screenReader.scrollForwardOnce()
                    null
                }
                button.isEnabled && screenReader.isNodeOrClickableAncestorEnabled(button) -> button
                else -> null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (enabledButton == null) {
            failCurrentState("Tombol Selesai tidak menjadi aktif sampai timeout/retry habis")
            return false
        }

        updateStateAndLog(
            AutomationState.WAIT_SELESAI,
            "Tombol Selesai sudah aktif/enabled"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_SELESAI)
        return true
    }

    private suspend fun handleTapSelesai(): Boolean {
        val button = waitWithRetry(AutomationState.TAP_SELESAI) {
            findSelesaiButton()?.takeIf {
                it.isVisibleToUser && it.isEnabled &&
                    screenReader.isNodeOrClickableAncestorEnabled(it)
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (button == null) {
            failCurrentState("Tombol Selesai aktif tidak ditemukan saat akan ditekan")
            return false
        }

        if (!screenReader.clickNode(button)) {
            failCurrentState("Gagal menekan tombol Selesai")
            return false
        }

        updateStateAndLog(
            AutomationState.TAP_SELESAI,
            "Tombol Selesai ditekan; menunggu kembali ke Pilih Work Order"
        )

        val returnedToList = waitWithRetry(AutomationState.TAP_SELESAI) {
            if (screenReader.isTextVisible("Pilih Work Order", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (returnedToList != true) {
            failCurrentState("Setelah menekan Selesai, layar Pilih Work Order tidak muncul")
            return false
        }

        val recordId = currentRecordId
        if (recordId != null) {
            dataStore.markCompleted(
                recordId = recordId,
                lastState = AutomationState.TAP_SELESAI.name,
                finishedAt = System.currentTimeMillis()
            )
            dataStore.addLog(
                recordId,
                AutomationState.TAP_SELESAI.name,
                "INFO",
                "WO selesai dan kembali ke Pilih Work Order"
            )
        }

        currentRecordId = null
        camera3RetryCount = 0
        AutomationRuntime.setCurrentWorkOrder(null, null)
        AutomationRuntime.transitionTo(AutomationState.WO_LIST)

        // Root layar WO_LIST sudah terdeteksi sebelum transisi state, jadi jalankan
        // engine awal sekali secara eksplisit agar loop berikutnya tidak bergantung
        // pada munculnya AccessibilityEvent tambahan.
        resumeWoList()
        return false
    }

    private fun findMigrationCameraNode(): AccessibilityNodeInfo? {
        return MIGRATION_CAMERA_IDS.firstNotNullOfOrNull { resourceId ->
            screenReader.findNodeByResourceId(resourceId)
        } ?: screenReader.findNodeByText(MIGRATION_PHOTO_LABEL, exactMatch = false)
    }

    private fun findSelesaiButton(): AccessibilityNodeInfo? {
        return SELESAI_BUTTON_IDS.firstNotNullOfOrNull { resourceId ->
            screenReader.findNodeByResourceId(resourceId)
        } ?: screenReader.findNodeByText("Selesai", exactMatch = true)
    }

    private suspend fun retryCamera3OrFail(reason: String): Boolean {
        if (camera3RetryCount >= MAX_CAMERA_OPEN_RETRY) {
            failCurrentState("$reason setelah retry kamera")
            return false
        }

        camera3RetryCount += 1
        dataStore.addLog(
            currentRecordId,
            AutomationState.CAMERA_SHUTTER_3.name,
            "WARNING",
            "$reason; buka ulang kamera bukti migrasi 1x"
        )

        screenReader.performBack()
        val returned = poll(WAIT_TIMEOUT_MS) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (returned != true) {
            failCurrentState("Gagal kembali ke Visit WO untuk retry kamera bukti migrasi")
            return false
        }

        AutomationRuntime.transitionTo(AutomationState.TAP_CAMERA_3)
        return true
    }

    private fun resolveRecordIdIfNeeded() {
        if (currentRecordId != null) return
        val idPelanggan = AutomationRuntime.currentIdPelanggan ?: return
        currentRecordId = dataStore.findActiveRecordId(
            idPelanggan,
            AutomationRuntime.sessionStartedAt
        )
    }

    private fun updateStateAndLog(state: AutomationState, message: String) {
        currentRecordId?.let { dataStore.updateLastState(it, state.name) }
        dataStore.addLog(currentRecordId, state.name, "INFO", message)
    }

    private suspend fun <T> waitWithRetry(
        state: AutomationState,
        block: () -> T?
    ): T? {
        repeat(MAX_RETRY_PER_STATE + 1) { attempt ->
            val result = poll(WAIT_TIMEOUT_MS, block)
            if (result != null) return result
            if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return null

            if (attempt < MAX_RETRY_PER_STATE) {
                dataStore.addLog(
                    currentRecordId,
                    state.name,
                    "WARNING",
                    "Timeout; retry ${attempt + 1}/$MAX_RETRY_PER_STATE"
                )
            }
        }
        return null
    }

    private suspend fun <T> poll(timeoutMs: Long, block: () -> T?): T? {
        val startedAt = SystemClock.uptimeMillis()
        while (AutomationRuntime.isRunning && !AutomationRuntime.stopRequested) {
            block()?.let { return it }
            if (SystemClock.uptimeMillis() - startedAt >= timeoutMs) return null
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    private fun failCurrentState(message: String) {
        val state = AutomationRuntime.currentState
        dataStore.addLog(currentRecordId, state.name, "ERROR", message)
        AutomationRuntime.fail("${state.name}: $message")
    }

    private fun isRelevantState(state: AutomationState): Boolean =
        state == AutomationState.TAP_CAMERA_3 ||
            state == AutomationState.CAMERA_SHUTTER_3 ||
            state == AutomationState.WAIT_SELESAI ||
            state == AutomationState.TAP_SELESAI

    companion object {
        private const val POLL_INTERVAL_MS = 300L
        private const val WAIT_TIMEOUT_MS = 10_000L
        private const val MAX_RETRY_PER_STATE = 2
        private const val MAX_CAMERA_OPEN_RETRY = 1

        private const val MIGRATION_PHOTO_LABEL =
            "Foto Bukti Pernyataan Migrasi ke Prabayar"

        private val MIGRATION_CAMERA_IDS = listOf(
            "dev.iconpln.mims:id/iv_prabayar",
            "dev.iconpln.mims:id/btn_prabayar"
        )

        private val SELESAI_BUTTON_IDS = listOf(
            "dev.iconpln.mims:id/btn_selesai",
            "dev.iconpln.mims:id/button_selesai"
        )
    }
}
