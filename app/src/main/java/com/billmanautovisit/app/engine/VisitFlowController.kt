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

/**
 * Menangani alur Visit sampai CHECK_WAJIB.
 *
 * Penting: INPUT_MIGRASI sengaja TIDAK ditangani di controller ini.
 * Setelah CHECK_WAJIB selesai, state berpindah ke INPUT_MIGRASI lalu controller
 * berhenti. INPUT_MIGRASI ditangani eksklusif oleh BillmanAccessibilityService
 * agar hanya opsi "Tidak" yang dapat dipilih.
 */
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
                AutomationState.CHECK_LUNAS -> handleCheckLunas()
                AutomationState.CHECK_WAJIB -> handleCheckWajib()
                // INPUT_MIGRASI bukan tanggung jawab controller ini.
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
        var dialogConfirmed = false

        repeat(MAX_RETRY_PER_STATE + 1) { attempt ->
            if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return false

            if (isVisitConfirmationVisible()) {
                dialogConfirmed = true
                return@repeat
            }

            val visitButton = poll(WAIT_TIMEOUT_MS) {
                val byId = screenReader.findNodeByResourceId(BTN_VISIT_ID)
                val node = byId ?: screenReader.findNodeByText("Visit", exactMatch = true)
                node?.takeIf {
                    it.isVisibleToUser && screenReader.isNodeOrClickableAncestorEnabled(it)
                }
            }

            if (visitButton == null) {
                if (attempt < MAX_RETRY_PER_STATE) {
                    dataStore.addLog(
                        currentRecordId,
                        AutomationState.TAP_VISIT.name,
                        "WARNING",
                        "Tombol Visit belum ditemukan/aktif; retry ${attempt + 1}/$MAX_RETRY_PER_STATE"
                    )
                    return@repeat
                }
                failCurrentState("Tombol Visit tidak ditemukan/aktif")
                return false
            }

            if (!screenReader.clickNode(visitButton)) {
                if (attempt < MAX_RETRY_PER_STATE) {
                    dataStore.addLog(
                        currentRecordId,
                        AutomationState.TAP_VISIT.name,
                        "WARNING",
                        "ACTION_CLICK Visit gagal; retry ${attempt + 1}/$MAX_RETRY_PER_STATE"
                    )
                    return@repeat
                }
                failCurrentState("Gagal menekan tombol Visit")
                return false
            }

            updateStateAndLog(
                AutomationState.TAP_VISIT,
                "Tombol Visit ditekan pada percobaan ${attempt + 1}; verifikasi popup konfirmasi"
            )

            val popupAppeared = poll(VISIT_CONFIRMATION_APPEAR_TIMEOUT_MS) {
                if (isVisitConfirmationVisible()) true else null
            }

            if (popupAppeared == true) {
                dialogConfirmed = true
                return@repeat
            }

            if (attempt < MAX_RETRY_PER_STATE) {
                dataStore.addLog(
                    currentRecordId,
                    AutomationState.TAP_VISIT.name,
                    "WARNING",
                    "Visit dilaporkan tertekan tetapi popup belum muncul; tekan Visit ulang ${attempt + 1}/$MAX_RETRY_PER_STATE"
                )
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (!dialogConfirmed && !isVisitConfirmationVisible()) {
            failCurrentState("Popup konfirmasi Visit tidak muncul setelah tombol Visit dicoba ulang")
            return false
        }

        // "Ya" di sini HANYA untuk popup konfirmasi Visit rumah, bukan opsi migrasi.
        val yesButton = waitWithRetry(AutomationState.TAP_VISIT) {
            if (!isVisitConfirmationVisible()) {
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
            "Popup Visit terverifikasi dan tombol Ya ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.VISIT_WO_SCREEN)
        return true
    }

    private fun isVisitConfirmationVisible(): Boolean =
        screenReader.isTextVisible(VISIT_CONFIRMATION_TEXT, exactMatch = false)

    private suspend fun handleVisitWoScreen(): Boolean {
        val visitScreen = waitWithRetry(AutomationState.VISIT_WO_SCREEN) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (visitScreen != true) {
            failCurrentState("Layar Visit WO RBM tidak terdeteksi")
            return false
        }

        if (AutomationRuntime.selectedFlow == com.billmanautovisit.app.core.AutomationFlow.TUL) {
            val vi01Badge = waitWithRetry(AutomationState.VISIT_WO_SCREEN) {
                screenReader.findNodeByText("VI-01", exactMatch = true)
            }
            if (!AutomationRuntime.isRunning) return false
            if (vi01Badge == null) {
                failCurrentState("Alur TUL dipilih tetapi badge VI-01 tidak ditemukan")
                return false
            }
            dataStore.addLog(
                currentRecordId,
                AutomationState.VISIT_WO_SCREEN.name,
                "INFO",
                "Badge VI-01 terdeteksi; menjalankan Alur TUL"
            )
            AutomationRuntime.setLatestAction("Badge VI-01 terdeteksi; Alur TUL aktif")
        }

        val cameraVisible = waitWithRetry(AutomationState.VISIT_WO_SCREEN) {
            val camera =
                screenReader.findNodeByResourceId(BTN_PHOTO_INVOICE_BUTTON_ID)
                    ?: screenReader.findNodeByResourceId(BTN_PHOTO_INVOICE_IMAGE_ID)
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
        // Pada Billman V3.0.2 accessibility snapshot, node yang terlihat adalah
        // iv_bukti_form_penyerahan_invoice. Namun resource tombol lama
        // btn_photo_penyerahan_invoice masih ada di APK dan dapat menjadi
        // clickable target yang sebenarnya. Karena itu jangan menganggap
        // ACTION_CLICK berhasil berarti kamera benar-benar sudah terbuka.
        val cameraIds = listOf(
            BTN_PHOTO_INVOICE_BUTTON_ID,
            BTN_PHOTO_INVOICE_IMAGE_ID
        )

        for ((index, resourceId) in cameraIds.withIndex()) {
            if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return false

            val cameraNode = waitWithRetry(AutomationState.TAP_CAMERA_2) {
                screenReader.findNodeByResourceId(resourceId)
                    ?.takeIf {
                        it.isVisibleToUser &&
                            screenReader.isNodeOrClickableAncestorEnabled(it)
                    }
            }

            if (cameraNode == null) {
                if (index == cameraIds.lastIndex) {
                    failCurrentState("Ikon kamera invoice tidak ditemukan/aktif")
                    return false
                }
                continue
            }

            if (!screenReader.clickNode(cameraNode)) {
                dataStore.addLog(
                    currentRecordId,
                    AutomationState.TAP_CAMERA_2.name,
                    "WARNING",
                    "ACTION_CLICK gagal pada target invoice $resourceId"
                )
                continue
            }

            // Verifikasi nyata: CameraTapper harus melihat shutter.
            // Jika tetap berada di Visit WO, coba target selector berikutnya
            // daripada langsung masuk CAMERA_SHUTTER_2.
            val opened = poll(CAMERA_OPEN_VERIFY_TIMEOUT_MS) {
                cameraTapper.findShutterNode()
            }

            if (opened != null) {
                camera2RetryCount = 0
                updateStateAndLog(
                    AutomationState.TAP_CAMERA_2,
                    "Kamera Foto Penyerahan Invoice benar-benar terbuka via $resourceId"
                )
                AutomationRuntime.transitionTo(AutomationState.CAMERA_SHUTTER_2)
                return true
            }

            dataStore.addLog(
                currentRecordId,
                AutomationState.TAP_CAMERA_2.name,
                "WARNING",
                "Target invoice $resourceId terklik tetapi shutter kamera belum muncul; mencoba target berikutnya"
            )
        }

        failCurrentState("Kamera invoice tidak terbuka setelah mencoba seluruh target selector")
        return false
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

        if (AutomationRuntime.selectedFlow == com.billmanautovisit.app.core.AutomationFlow.TUL) {
            currentRecordId?.let {
                dataStore.updateLastState(it, AutomationState.TUL_PHOTO_BA.name)
            }
            dataStore.addLog(
                currentRecordId,
                AutomationState.TUL_PHOTO_BA.name,
                "INFO",
                "Foto invoice selesai; Alur TUL melanjutkan ke foto BA TUL"
            )
            AutomationRuntime.transitionTo(AutomationState.TUL_PHOTO_BA)
            return true
        }

        currentRecordId?.let {
            dataStore.updateLastState(it, AutomationState.CHECK_LUNAS.name)
        }
        dataStore.addLog(
            currentRecordId,
            AutomationState.CHECK_LUNAS.name,
            "INFO",
            "Foto invoice selesai; mencari section Status Pelunasan"
        )
        AutomationRuntime.transitionTo(AutomationState.CHECK_LUNAS)
        return true
    }

    private suspend fun handleCheckLunas(): Boolean {
        val flow = AutomationRuntime.selectedFlow
        val targetId = flow.settlementResourceId
        val targetLabel = flow.settlementLabel

        val radio = waitForVisibleNode(
            state = AutomationState.CHECK_LUNAS,
            resourceId = targetId,
            fallbackText = targetLabel
        )

        if (!AutomationRuntime.isRunning) return false
        if (radio == null) {
            failCurrentState("Radio $targetLabel tidak ditemukan setelah scroll")
            return false
        }

        if (!ensureChecked(
                state = AutomationState.CHECK_LUNAS,
                resourceId = targetId,
                initialNode = radio,
                label = targetLabel
            )
        ) {
            failCurrentState("$targetLabel gagal dipilih")
            return false
        }

        updateStateAndLog(
            AutomationState.CHECK_LUNAS,
            "$targetLabel terpilih (${flow.displayName})"
        )
        AutomationRuntime.setLatestAction("Status Pelunasan: $targetLabel")
        AutomationRuntime.transitionTo(AutomationState.CHECK_WAJIB)
        return true
    }

    private suspend fun handleCheckWajib(): Boolean {
        val checkboxIds = listOf(
            CB_INFORMASI_TAGIHAN_ID to "Informasi tagihan",
            CB_TANGGAL_PELUNASAN_ID to "Tanggal pelunasan",
            CB_INFORMASI_KONSEKUENSI_ID to "Informasi konsekuensi"
        )

        checkboxIds.forEachIndexed { index, (resourceId, label) ->
            val node = waitForVisibleNode(
                state = AutomationState.CHECK_WAJIB,
                resourceId = resourceId,
                fallbackText = null
            )

            if (!AutomationRuntime.isRunning) return false
            if (node == null) {
                failCurrentState("Checkbox Wajib Disampaikan ke-${index + 1} ($label) tidak ditemukan")
                return false
            }

            if (!ensureChecked(
                    state = AutomationState.CHECK_WAJIB,
                    resourceId = resourceId,
                    initialNode = node,
                    label = label
                )
            ) {
                failCurrentState("Checkbox Wajib Disampaikan ke-${index + 1} ($label) gagal dicentang")
                return false
            }

            dataStore.addLog(
                currentRecordId,
                AutomationState.CHECK_WAJIB.name,
                "INFO",
                "Checkbox Wajib Disampaikan ke-${index + 1} dicentang: $label"
            )
        }

        currentRecordId?.let {
            dataStore.updateLastState(it, AutomationState.CHECK_WAJIB.name)
        }

        // Handoff eksplisit. Controller berhenti setelah state ini.
        AutomationRuntime.transitionTo(AutomationState.INPUT_MIGRASI)
        AutomationRuntime.setStatus(
            "RUNNING: INPUT_MIGRASI — menunggu Nama Penghuni/keyboard lalu memilih Tidak"
        )
        return false
    }

    private suspend fun waitForVisibleNode(
        state: AutomationState,
        resourceId: String,
        fallbackText: String?
    ): AccessibilityNodeInfo? = waitWithRetry(state) {
        val byId = screenReader.findNodeByResourceId(resourceId)
        val byText = fallbackText?.let { screenReader.findNodeByText(it, exactMatch = true) }
        val node = byId ?: byText

        if (node != null && node.isVisibleToUser) {
            node
        } else {
            screenReader.scrollForwardOnce()
            null
        }
    }

    private suspend fun ensureChecked(
        state: AutomationState,
        resourceId: String,
        initialNode: AccessibilityNodeInfo,
        label: String
    ): Boolean {
        if (initialNode.isChecked) return true

        if (!screenReader.clickNode(initialNode)) {
            dataStore.addLog(
                currentRecordId,
                state.name,
                "WARNING",
                "ACTION_CLICK gagal untuk $label"
            )
            return false
        }

        return poll(CHECK_STATE_TIMEOUT_MS) {
            val refreshed = screenReader.findNodeByResourceId(resourceId)
            if (refreshed?.isChecked == true) true else null
        } == true
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
            state == AutomationState.CHECK_LUNAS ||
            state == AutomationState.CHECK_WAJIB

    companion object {
        private const val POLL_INTERVAL_MS = 300L
        private const val WAIT_TIMEOUT_MS = 10_000L
        private const val CHECK_STATE_TIMEOUT_MS = 3_000L
        private const val MAX_RETRY_PER_STATE = 2
        private const val MAX_CAMERA_OPEN_RETRY = 1
        private const val STATE_HANDOFF_POLL_MS = 100L
        private const val STATE_HANDOFF_TIMEOUT_MS = 10_000L
        private const val VISIT_CONFIRMATION_APPEAR_TIMEOUT_MS = 3_000L

        private const val BTN_VISIT_ID = "dev.iconpln.mims:id/btn_visit"
        private const val BTN_PHOTO_INVOICE_BUTTON_ID =
            "dev.iconpln.mims:id/btn_photo_penyerahan_invoice"
        private const val BTN_PHOTO_INVOICE_IMAGE_ID =
            "dev.iconpln.mims:id/iv_bukti_form_penyerahan_invoice"
        private const val CAMERA_OPEN_VERIFY_TIMEOUT_MS = 4_000L
        private const val CB_INFORMASI_TAGIHAN_ID =
            "dev.iconpln.mims:id/cb_informasi_tagihan"
        private const val CB_TANGGAL_PELUNASAN_ID =
            "dev.iconpln.mims:id/cb_tanggal_pelunasan"
        private const val CB_INFORMASI_KONSEKUENSI_ID =
            "dev.iconpln.mims:id/cb_informasi_konsekuensi"
        private const val VISIT_CONFIRMATION_TEXT =
            "Anda yakin ingin mengunjungi rumah ini"
    }
}
