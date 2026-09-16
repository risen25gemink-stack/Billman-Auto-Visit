package com.billmanautovisit.app.engine

import android.os.SystemClock
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

class VisitFlowController(
    private val screenReader: ScreenReader,
    private val cameraTapper: CameraTapper,
    private val dataStore: LocalDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = AtomicBoolean(false)

    private var currentRecordId: Long? = null
    private var camera2RetryCount: Int = 0

    fun onBillmanEvent() {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return
        if (!isRelevantState(AutomationRuntime.currentState)) return
        if (!processing.compareAndSet(false, true)) return

        scope.launch {
            try {
                if (AutomationRuntime.currentState == AutomationState.BACK_TO_MENUJU_WO) {
                    waitForTapVisitTransition()
                }
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
                AutomationState.TAP_VISIT -> handleTapVisit()
                AutomationState.VISIT_WO_SCREEN -> handleVisitWoScreen()
                AutomationState.TAP_CAMERA_2 -> handleTapCamera2()
                AutomationState.CAMERA_SHUTTER_2 -> handleCameraShutter2()
                AutomationState.CHECK_LUNAS -> {
                    AutomationRuntime.setStatus(
                        "RUNNING: CHECK_LUNAS — uji berhenti setelah foto invoice"
                    )
                    false
                }
                else -> false
            }

            if (!shouldContinue) return
        }
    }

    private suspend fun waitForTapVisitTransition() {
        val startedAt = SystemClock.uptimeMillis()
        while (AutomationRuntime.isRunning && !AutomationRuntime.stopRequested) {
            if (AutomationRuntime.currentState == AutomationState.TAP_VISIT) return
            if (AutomationRuntime.currentState != AutomationState.BACK_TO_MENUJU_WO) return
            if (SystemClock.uptimeMillis() - startedAt >= STATE_HANDOFF_TIMEOUT_MS) return
            delay(STATE_HANDOFF_POLL_MS)
        }
    }

    private suspend fun handleTapVisit(): Boolean {
        val visitButton = waitWithRetry(AutomationState.TAP_VISIT) {
            val byId = screenReader.findNodeByResourceId(BTN_VISIT_ID)
            val node = byId ?: screenReader.findNodeByText("Visit", exactMatch = true)
            node?.takeIf {
                it.isVisibleToUser && screenReader.isNodeOrClickableAncestorEnabled(it)
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (visitButton == null) {
            failCurrentState("Tombol Visit tidak ditemukan/aktif")
            return false
        }

        if (!screenReader.clickNode(visitButton)) {
            failCurrentState("Gagal menekan tombol Visit")
            return false
        }

        updateStateAndLog(
            AutomationState.TAP_VISIT,
            "Tombol Visit ditekan; menunggu dialog konfirmasi"
        )

        val dialogVisible = waitWithRetry(AutomationState.TAP_VISIT) {
            if (
                screenReader.isTextVisible(
                    VISIT_CONFIRMATION_TEXT,
                    exactMatch = false
                )
            ) {
                true
            } else {
                null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (dialogVisible != true) {
            failCurrentState("Dialog konfirmasi Visit tidak muncul")
            return false
        }

        val yesButton = waitWithRetry(AutomationState.TAP_VISIT) {
            if (!screenReader.isTextVisible(VISIT_CONFIRMATION_TEXT, exactMatch = false)) {
                null
            } else {
                screenReader.findNodeByText("Ya", exactMatch = true)
                    ?.takeIf {
                        it.isVisibleToUser && screenReader.isNodeOrClickableAncestorEnabled(it)
                    }
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (yesButton == null) {
            failCurrentState("Tombol Ya pada dialog konfirmasi Visit tidak ditemukan/aktif")
            return false
        }

        if (!screenReader.clickNode(yesButton)) {
            failCurrentState("Gagal menekan Ya pada dialog konfirmasi Visit")
            return false
        }

        updateStateAndLog(
            AutomationState.TAP_VISIT,
            "Konfirmasi Visit: Ya ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.VISIT_WO_SCREEN)
        return true
    }

    private suspend fun handleVisitWoScreen(): Boolean {
        val visitScreen = waitWithRetry(AutomationState.VISIT_WO_SCREEN) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (visitScreen != true) {
            failCurrentState("Layar Visit WO RBM tidak terdeteksi")
            return false
        }

        val cameraVisible = waitWithRetry(AutomationState.VISIT_WO_SCREEN) {
            val camera = screenReader.findNodeByResourceId(BTN_PHOTO_INVOICE_ID)
            if (camera != null && camera.isVisibleToUser) {
                true
            } else {
                screenReader.scrollForwardOnce()
                null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (cameraVisible != true) {
            failCurrentState("Ikon kamera Foto Penyerahan Invoice tidak ditemukan setelah scroll")
            return false
        }

        updateStateAndLog(
            AutomationState.VISIT_WO_SCREEN,
            "Layar Visit WO terdeteksi dan kamera invoice terlihat"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_CAMERA_2)
        return true
    }

    private suspend fun handleTapCamera2(): Boolean {
        val cameraNode = waitWithRetry(AutomationState.TAP_CAMERA_2) {
            screenReader.findNodeByResourceId(BTN_PHOTO_INVOICE_ID)
                ?.takeIf {
                    it.isVisibleToUser && screenReader.isNodeOrClickableAncestorEnabled(it)
                }
        }

        if (!AutomationRuntime.isRunning) return false
        if (cameraNode == null) {
            failCurrentState("Ikon kamera invoice tidak ditemukan/aktif")
            return false
        }

        if (!screenReader.clickNode(cameraNode)) {
            failCurrentState("Gagal membuka kamera invoice")
            return false
        }

        camera2RetryCount = 0
        updateStateAndLog(
            AutomationState.TAP_CAMERA_2,
            "Kamera Foto Penyerahan Invoice dibuka"
        )
        AutomationRuntime.transitionTo(AutomationState.CAMERA_SHUTTER_2)
        return true
    }

    private suspend fun handleCameraShutter2(): Boolean {
        val shutter = poll(WAIT_TIMEOUT_MS) {
            cameraTapper.findShutterNode()
        }

        if (!AutomationRuntime.isRunning) return false
        if (shutter == null) {
            return retryCamera2OrFail("Tombol shutter kamera invoice tidak terdeteksi")
        }

        if (!cameraTapper.tapShutter(shutter)) {
            return retryCamera2OrFail("Shutter kamera invoice terdeteksi tetapi gagal ditekan")
        }

        updateStateAndLog(
            AutomationState.CAMERA_SHUTTER_2,
            "Shutter kamera invoice ditekan"
        )

        val returned = waitWithRetry(AutomationState.CAMERA_SHUTTER_2) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (returned != true) {
            failCurrentState("Tidak kembali ke layar Visit WO setelah foto invoice")
            return false
        }

        currentRecordId?.let {
            dataStore.updateLastState(it, AutomationState.CHECK_LUNAS.name)
        }
        dataStore.addLog(
            currentRecordId,
            AutomationState.CHECK_LUNAS.name,
            "INFO",
            "Foto invoice selesai; siap ke Status Pelunasan"
        )
        AutomationRuntime.transitionTo(AutomationState.CHECK_LUNAS)
        return true
    }

    private suspend fun retryCamera2OrFail(reason: String): Boolean {
        if (camera2RetryCount >= MAX_CAMERA_OPEN_RETRY) {
            failCurrentState("$reason setelah retry kamera")
            return false
        }

        camera2RetryCount += 1
        dataStore.addLog(
            currentRecordId,
            AutomationState.CAMERA_SHUTTER_2.name,
            "WARNING",
            "$reason; buka ulang kamera invoice 1x"
        )

        screenReader.performBack()
        val returned = poll(WAIT_TIMEOUT_MS) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (returned != true) {
            failCurrentState("Gagal kembali ke Visit WO untuk retry kamera invoice")
            return false
        }

        AutomationRuntime.transitionTo(AutomationState.VISIT_WO_SCREEN)
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
        state == AutomationState.BACK_TO_MENUJU_WO ||
            state == AutomationState.TAP_VISIT ||
            state == AutomationState.VISIT_WO_SCREEN ||
            state == AutomationState.TAP_CAMERA_2 ||
            state == AutomationState.CAMERA_SHUTTER_2 ||
            state == AutomationState.CHECK_LUNAS

    companion object {
        private const val POLL_INTERVAL_MS = 300L
        private const val WAIT_TIMEOUT_MS = 10_000L
        private const val MAX_RETRY_PER_STATE = 2
        private const val MAX_CAMERA_OPEN_RETRY = 1
        private const val STATE_HANDOFF_POLL_MS = 100L
        private const val STATE_HANDOFF_TIMEOUT_MS = 10_000L

        private const val BTN_VISIT_ID = "dev.iconpln.mims:id/btn_visit"
        private const val BTN_PHOTO_INVOICE_ID =
            "dev.iconpln.mims:id/btn_photo_penyerahan_invoice"
        private const val VISIT_CONFIRMATION_TEXT =
            "Anda yakin ingin mengunjungi rumah ini"
    }
}
