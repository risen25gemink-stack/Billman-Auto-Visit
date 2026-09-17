package com.billmanautovisit.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
    private var residentNameFocusFallbackForId: String? = null
    private var residentNameFallbackBackSentForId: String? = null
    private var migrationNoPreparedForId: String? = null
    private var keyboardDismissId: String? = null
    private var keyboardDismissAttempts: Int = 0

    private val inputRecheckRunnable = Runnable {
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

        if (stateBeforeDispatch == AutomationState.INPUT_MIGRASI) {
            if (!ensureResidentNameIsUserAndKeyboardClosed()) return
            if (!ensureMigrationNoSelected()) return

            AutomationRuntime.transitionTo(AutomationState.TAP_CAMERA_3)
            AutomationRuntime.setStatus(
                "RUNNING: TAP_CAMERA_3 — migrasi prabayar Tidak terpilih"
            )
            AutomationRuntime.setLatestAction(
                "Migrasi prabayar dipilih Tidak; lanjut kamera bukti"
            )
            finalFlowController.onBillmanEvent()
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
            stateBeforeDispatch == AutomationState.CHECK_WAJIB
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
            residentNameFocusFallbackForId = null
            residentNameFallbackBackSentForId = null

            // Jalur utama: isi langsung melalui Accessibility tanpa click/focus.
            // Tujuannya agar soft keyboard tidak pernah dibuka.
            val directSet = screenReader.setTextWithoutKeyboard(field, RESIDENT_NAME_VALUE)
            if (directSet) {
                screenReader.clearFocus(field)
                AutomationRuntime.setStatus(
                    "RUNNING: INPUT_MIGRASI — memverifikasi Nama Penghuni"
                )
                AutomationRuntime.setLatestAction(
                    "Nama Penghuni diisi USER tanpa membuka keyboard; menunggu verifikasi"
                )
                scheduleInputRecheck()
                return false
            }

            // Fallback hanya bila Billman menolak ACTION_SET_TEXT tanpa focus.
            // setText() memberi focus dan dapat membuka IME, sehingga dicatat agar
            // Back dapat dikirim tepat satu kali setelah USER terverifikasi.
            val fallbackSet = screenReader.setText(field, RESIDENT_NAME_VALUE)
            if (!fallbackSet) {
                AutomationRuntime.setStatus(
                    "RUNNING: INPUT_MIGRASI — mencoba ulang Nama Penghuni"
                )
                AutomationRuntime.setLatestAction("Gagal mengisi Nama Penghuni; mencoba ulang")
                scheduleInputRecheck()
                return false
            }

            residentNameFocusFallbackForId = activeId
            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — memverifikasi Nama Penghuni"
            )
            AutomationRuntime.setLatestAction(
                "Nama Penghuni diisi USER lewat fallback focus; menyiapkan penutupan keyboard"
            )
            scheduleInputRecheck()
            return false
        }

        screenReader.clearFocus(field)

        // Bila jalur fallback focus dipakai, kirim Back tepat satu kali walaupun
        // TYPE_INPUT_METHOD tidak terdeteksi. Kita tahu focus baru saja diminta.
        if (
            residentNameFocusFallbackForId == activeId &&
            residentNameFallbackBackSentForId != activeId
        ) {
            residentNameFallbackBackSentForId = activeId
            screenReader.performBack()
            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — menutup keyboard fallback"
            )
            AutomationRuntime.setLatestAction(
                "Nama Penghuni = USER; Back dikirim untuk menutup keyboard fallback"
            )
            scheduleInputRecheck()
            return false
        }

        // Pengaman tambahan untuk perangkat yang tetap membuka IME meski
        // ACTION_SET_TEXT dipanggil tanpa focus.
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

            scheduleInputRecheck()
            return false
        }

        keyboardDismissId = null
        keyboardDismissAttempts = 0
        residentNamePreparedForId = activeId
        AutomationRuntime.setLatestAction("Nama Penghuni = USER; keyboard tidak menghalangi")
        return true
    }

    private fun ensureMigrationNoSelected(): Boolean {
        val activeId = AutomationRuntime.currentIdPelanggan ?: return false
        if (migrationNoPreparedForId == activeId) return true

        val byId = screenReader.findNodeByResourceId(RB_PRABAYAR_TIDAK_ID)
        val radio = byId ?: screenReader.findNodeByText("Tidak", exactMatch = true)

        if (radio == null || !radio.isVisibleToUser) {
            screenReader.scrollForwardOnce()
            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — mencari opsi Tidak"
            )
            AutomationRuntime.setLatestAction("Mencari opsi migrasi prabayar: Tidak")
            scheduleInputRecheck()
            return false
        }

        if (isNodeOrAncestorChecked(radio)) {
            migrationNoPreparedForId = activeId
            AutomationRuntime.setLatestAction("Migrasi prabayar terverifikasi: Tidak")
            return true
        }

        if (!screenReader.clickNode(radio)) {
            AutomationRuntime.setStatus(
                "RUNNING: INPUT_MIGRASI — mencoba ulang opsi Tidak"
            )
            AutomationRuntime.setLatestAction("Gagal menekan opsi Tidak; mencoba ulang")
            scheduleInputRecheck()
            return false
        }

        AutomationRuntime.setStatus(
            "RUNNING: INPUT_MIGRASI — memverifikasi opsi Tidak"
        )
        AutomationRuntime.setLatestAction("Opsi Tidak ditekan; menunggu verifikasi")
        scheduleInputRecheck()
        return false
    }

    private fun isNodeOrAncestorChecked(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isChecked) return true
            current = candidate.parent
        }
        return false
    }

    private fun scheduleInputRecheck() {
        mainHandler.removeCallbacks(inputRecheckRunnable)
        mainHandler.postDelayed(inputRecheckRunnable, INPUT_RECHECK_MS)
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
        private const val RB_PRABAYAR_TIDAK_ID = "dev.iconpln.mims:id/rb_prabayar_tidak"
        private const val INPUT_RECHECK_MS = 250L
        private const val MAX_KEYBOARD_DISMISS_ATTEMPTS = 2
    }
}
