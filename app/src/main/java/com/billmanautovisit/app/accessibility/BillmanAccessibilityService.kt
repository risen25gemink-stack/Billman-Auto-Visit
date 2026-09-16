package com.billmanautovisit.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AppConfig
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.core.AutomationState
import com.billmanautovisit.app.core.TaggingGate
import com.billmanautovisit.app.data.SqliteLocalDataStore
import com.billmanautovisit.app.engine.AutomationEngine
import com.billmanautovisit.app.engine.VisitFlowController
import com.billmanautovisit.app.screen.ScreenReader

class BillmanAccessibilityService : AccessibilityService() {

    private lateinit var screenReader: ScreenReader
    private lateinit var cameraTapper: CameraTapper
    private lateinit var localDataStore: SqliteLocalDataStore
    private lateinit var automationEngine: AutomationEngine
    private lateinit var visitFlowController: VisitFlowController

    override fun onServiceConnected() {
        super.onServiceConnected()
        screenReader = ScreenReader(this)
        cameraTapper = CameraTapper(this)
        localDataStore = SqliteLocalDataStore(applicationContext)
        automationEngine = AutomationEngine(screenReader, cameraTapper, localDataStore)
        visitFlowController = VisitFlowController(screenReader, cameraTapper, localDataStore)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val safeEvent = event ?: return

        captureTaggingResult(safeEvent)

        val packageName = safeEvent.packageName?.toString() ?: return
        if (packageName != AppConfig.TARGET_PACKAGE) return
        if (!::automationEngine.isInitialized || !::visitFlowController.isInitialized) return
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return

        val stateBeforeDispatch = AutomationRuntime.currentState
        automationEngine.onBillmanEvent()

        if (
            stateBeforeDispatch == AutomationState.BACK_TO_MENUJU_WO ||
            stateBeforeDispatch == AutomationState.TAP_VISIT ||
            stateBeforeDispatch == AutomationState.VISIT_WO_SCREEN ||
            stateBeforeDispatch == AutomationState.TAP_CAMERA_2 ||
            stateBeforeDispatch == AutomationState.CAMERA_SHUTTER_2 ||
            stateBeforeDispatch == AutomationState.CHECK_LUNAS ||
            stateBeforeDispatch == AutomationState.CHECK_WAJIB ||
            stateBeforeDispatch == AutomationState.INPUT_MIGRASI ||
            stateBeforeDispatch == AutomationState.TAP_CAMERA_3
        ) {
            visitFlowController.onBillmanEvent()
        }
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
        }
    }

    override fun onInterrupt() {
        AutomationRuntime.requestStop()
    }

    override fun onDestroy() {
        if (::automationEngine.isInitialized) automationEngine.shutdown()
        if (::visitFlowController.isInitialized) visitFlowController.shutdown()
        if (::localDataStore.isInitialized) localDataStore.close()
        super.onDestroy()
    }

    companion object {
        private const val TAGGING_SUCCESS_TEXT = "Tagging Berhasil"
    }
}
