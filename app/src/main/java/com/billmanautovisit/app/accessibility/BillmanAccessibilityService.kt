package com.billmanautovisit.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AppConfig
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.core.AutomationState
import com.billmanautovisit.app.core.TaggingGate
import com.billmanautovisit.app.data.SqliteLocalDataStore
import com.billmanautovisit.app.engine.AutomationEngine
import com.billmanautovisit.app.engine.FinalFlowController
import com.billmanautovisit.app.engine.VisitFlowController
import com.billmanautovisit.app.overlay.TrackingOverlayController
import com.billmanautovisit.app.screen.ScreenReader

class BillmanAccessibilityService : AccessibilityService() {

    private lateinit var screenReader: ScreenReader
    private lateinit var cameraTapper: CameraTapper
    private lateinit var localDataStore: SqliteLocalDataStore
    private lateinit var automationEngine: AutomationEngine
    private lateinit var visitFlowController: VisitFlowController
    private lateinit var finalFlowController: FinalFlowController
    private lateinit var trackingOverlay: TrackingOverlayController

    private val mainHandler = Handler(Looper.getMainLooper())
    private var residentNamePreparedForId: String? = null
    private var keyboardDismissId: String? = null
    private var keyboardDismissAttempts: Int = 0

    private val keyboardRecheckRunnable = Runnable {
        if (
            AutomationRuntime.isRunning &&
            !AutomationRuntime.stopRequested &&
            AutomationRuntime.currentState == AutomationState.INPUT_MIGRASI
        ) {
            dispatchCurrentState()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        cameraTapper = CameraTapper(this)
        localDataStore = SqliteLocalDataStore(applicationContext)
        automationEngine = AutomationEngine(screenReader, cameraTapper, localDataStore)
        visitFlowController = VisitFlowController(screenReader, cameraTapper, localDataStore)
        finalFlowController = FinalFlowController(
            screenReader = screenReader,
            cameraTapper = cameraTapper,
            dataStore = localDataStore,
            resumeWoList = { automationEngine.onBillmanEvent() }
        )
        trackingOverlay = TrackingOverlayController(
            service = this,
            dataStore = localDataStore,
            screenReader = screenReader,
            onResumeAutomation = { dispatchCurrentState() }
        )
        trackingOverlay.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return

        captureTaggingResult(safeEvent)

        val packageName = safeEvent.packageName?.toString() ?: return
        if (packageName != AppConfig.TARGET_PACKAGE) return
        if (
            !::automationEngine.isInitialized ||
            !::visitFlowController.isInitialized ||
            !::finalFlowController.isInitialized
        ) return
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return

        dispatchCurrentState()
    }

    private fun dispatchCurrentState() {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return

        val stateBeforeDispatch = AutomationRuntime.currentState

        if (
            stateBeforeDispatch == AutomationState.INPUT_MIGRASI &&
            !ensureResidentNameIsUserAndKeyboardClosed()
        ) {
            return
        }

        automationEngine.onBillmanEvent()

        if (
            stateBeforeDispatch == AutomationState.BACK_TO_MENUJU_WO ||
            stateBeforeDispatch == AutomationState.TAP_VISIT ||
            stateBeforeDispatch == AutomationState.VISIT_WO_SCREEN ||
            stateBeforeDispatch == AutomationState.TAP_CAMERA_2 ||
            stateBeforeDispatch == AutomationState.CAMERA_SHUTTER_2 ||
            stateBeforeDispatch == AutomationState.CHECK_LUNAS ||
            stateBeforeDispatch == AutomationState.CHECK_WAJIB ||
            stateBeforeDispatch == AutomationState.INPUT_MIGRASI
        ) {
            visitFlowController.onBillmanEvent()
        }

        if (
            stateBeforeDispatch == AutomationState.TAP_CAMERA_3 ||
            stateBeforeDispatch == AutomationState.CAMERA_SHUTTER_3 ||
            stateBeforeDispatch == AutomationState.WAIT_SELESAI ||
            stateBeforeDispatch == AutomationState.TAP_SELESAI
        ) {
            finalFlowController.onBillmanEvent()
        }
    }

    private fun ensureResidentNameIsUserAndKeyboardClosed(): Boolean {
        val activeId = AutomationRuntime.currentIdPelanggan ?: return false
        if (residentNamePreparedForId == activeId) return true

        val field = screenReader.findEditableFieldByLabel(RESIDENT_NAME_LABEL)
        if (field == null || !field.isVisibleToUser) {
            screenReader.scrollForwardOnce()
            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — mencari field Nama Penghuni"
            )
            AutomationRuntime.setLatestAction("Mencari field Nama Penghuni")
            return false
        }

        val currentValue = field.text?.toString()?.trim().orEmpty()
        if (currentValue != RESIDENT_NAME_VALUE) {
            keyboardDismissId = null
            keyboardDismissAttempts = 0

            screenReader.clickNode(field)
            val textSet = screenReader.setText(field, RESIDENT_NAME_VALUE)
            if (!textSet) {
                AutomationRuntime.setStatus(
                    "RUNNING: INPUT_MIGRASI — mencoba ulang Nama Penghuni"
                )
                AutomationRuntime.setLatestAction("Gagal mengisi Nama Penghuni; mencoba ulang")
                return false
            }

            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — memverifikasi Nama Penghuni"
            )
            AutomationRuntime.setLatestAction("Nama Penghuni diisi USER; menunggu verifikasi")
            scheduleKeyboardRecheck()
            return false
        }

        if (screenReader.isInputMethodVisible()) {
            if (keyboardDismissId != activeId) {
                keyboardDismissId = activeId
                keyboardDismissAttempts = 0
            }

            if (keyboardDismissAttempts < MAX_KEYBOARD_DISMISS_ATTEMPTS) {
                keyboardDismissAttempts += 1
                screenReader.clearFocus(field)
                screenReader.performBack()
                AutomationRuntime.setStatus(
                    "RUNNING: INPUT_MIGRASI — menutup keyboard"
                )
                AutomationRuntime.setLatestAction(
                    "Nama Penghuni = USER; menutup keyboard (${keyboardDismissAttempts}/$MAX_KEYBOARD_DISMISS_ATTEMPTS)"
                )
            } else {
                AutomationRuntime.setStatus(
                    "RUNNING: INPUT_MIGRASI — menunggu keyboard tertutup"
                )
                AutomationRuntime.setLatestAction("Menunggu keyboard Android tertutup")
            }

            scheduleKeyboardRecheck()
            return false
        }

        screenReader.clearFocus(field)
        keyboardDismissId = null
        keyboardDismissAttempts = 0
        residentNamePreparedForId = activeId
        AutomationRuntime.setLatestAction("Nama Penghuni = USER; keyboard tertutup")
        return true
    }

    private fun scheduleKeyboardRecheck() {
        mainHandler.removeCallbacks(keyboardRecheckRunnable)
        mainHandler.postDelayed(keyboardRecheckRunnable, KEYBOARD_RECHECK_MS)
    }

    private fun captureTaggingResult(event: AccessibilityEvent) {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return
        if (
            AutomationRuntime.currentState != AutomationState.TAP_TAGGING &&
            AutomationRuntime.currentState != AutomationState.TAP_CAMERA_1
        ) {
            return
        }

        val message = event.text
            .mapNotNull { it?.toString() }
            .joinToString(" ")
            .trim()

        if (message.contains(TAGGING_SUCCESS_TEXT, ignoreCase = true)) {
            TaggingGate.markReady()
            AutomationRuntime.setStatus(
                "RUNNING: TAP_CAMERA_1 — Tagging berhasil, kamera siap diproses"
            )
            AutomationRuntime.setLatestAction("Tagging berhasil; kamera siap")
        }
    }

    override fun onInterrupt() {
        AutomationRuntime.requestStop()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        if (::trackingOverlay.isInitialized) trackingOverlay.destroy()
        if (::automationEngine.isInitialized) automationEngine.shutdown()
        if (::visitFlowController.isInitialized) visitFlowController.shutdown()
        if (::finalFlowController.isInitialized) finalFlowController.shutdown()
        if (::localDataStore.isInitialized) localDataStore.close()
        super.onDestroy()
    }

    companion object {
        private const val TAGGING_SUCCESS_TEXT = "Tagging Berhasil"
        private const val RESIDENT_NAME_LABEL = "Nama Penghuni"
        private const val RESIDENT_NAME_VALUE = "USER"
        private const val KEYBOARD_RECHECK_MS = 250L
        private const val MAX_KEYBOARD_DISMISS_ATTEMPTS = 2
    }
}
