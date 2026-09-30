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
 * Alur TUL untuk WO VI-01.
 *
 * Titik masuk: setelah foto invoice pada layar Visit WO selesai.
 * Alur:
 * 1) foto BA TUL
 * 2) pilih Tidak Putus
 * 3) pilih alasan LUNAS OFFLINE
 * 4) isi Nama Penghuni = Pelanggan
 * 5) isi Nomor WA = 0000000000000
 * 6) pilih migrasi prabayar = Tidak
 * 7) handoff ke FinalFlowController pada WAIT_SELESAI
 */
class TulFlowController(
    private val screenReader: ScreenReader,
    private val cameraTapper: CameraTapper,
    private val dataStore: LocalDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = AtomicBoolean(false)

    private var currentRecordId: Long? = null
    private var cameraRetryCount = 0

    fun onBillmanEvent() {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return
        if (!isRelevantState(AutomationRuntime.currentState)) return
        if (!processing.compareAndSet(false, true)) return

        scope.launch {
            try {
                drive()
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

    private suspend fun drive() {
        resolveRecordIdIfNeeded()

        while (AutomationRuntime.isRunning && !AutomationRuntime.stopRequested) {
            val continueLoop = when (AutomationRuntime.currentState) {
                AutomationState.TUL_PHOTO_BA -> handlePhotoBa()
                AutomationState.TUL_SELECT_TIDAK_PUTUS -> handleTidakPutus()
                AutomationState.TUL_SELECT_ALASAN -> handleAlasan()
                AutomationState.TUL_INPUT_DATA -> handleInputData()
                AutomationState.TUL_SELECT_MIGRATION_NO -> handleMigrationNo()
                else -> false
            }
            if (!continueLoop) return
        }
    }

    private suspend fun handlePhotoBa(): Boolean {
        val camera = waitWithRetry(AutomationState.TUL_PHOTO_BA) {
            val byId = TUL_CAMERA_IDS.firstNotNullOfOrNull { id ->
                screenReader.findNodeByResourceId(id)
            }
            val label = screenReader.findNodeByText(BA_TUL_LABEL, exactMatch = false)
            val node = byId ?: label

            if (node != null && node.isVisibleToUser) {
                node.takeIf { screenReader.isNodeOrClickableAncestorEnabled(it) }
            } else {
                if (label != null) {
                    screenReader.showNodeOnScreen(label)
                    screenReader.scrollForwardFrom(label)
                } else {
                    screenReader.scrollForwardOnce()
                }
                null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (camera == null) {
            failCurrentState("Ikon kamera BA TUL tidak ditemukan/aktif")
            return false
        }

        if (!screenReader.clickNode(camera)) {
            failCurrentState("Gagal membuka kamera BA TUL")
            return false
        }

        cameraRetryCount = 0
        updateStateAndLog(
            AutomationState.TUL_PHOTO_BA,
            "Kamera Foto Penyampaian BA TUL VI-01 dibuka"
        )
        AutomationRuntime.transitionTo(AutomationState.TUL_SELECT_TIDAK_PUTUS)
        return true
    }

    private suspend fun handleTidakPutus(): Boolean {
        val shutter = poll(WAIT_TIMEOUT_MS) { cameraTapper.findShutterNode() }

        if (!AutomationRuntime.isRunning) return false
        if (shutter == null) {
            return retryCameraOrFail("Tombol shutter kamera BA TUL tidak terdeteksi")
        }

        if (!cameraTapper.tapShutter(shutter)) {
            return retryCameraOrFail("Shutter kamera BA TUL gagal ditekan")
        }

        val returned = waitWithRetry(AutomationState.TUL_SELECT_TIDAK_PUTUS) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (returned != true) {
            failCurrentState("Tidak kembali ke Visit WO setelah foto BA TUL")
            return false
        }

        val radio = waitWithRetry(AutomationState.TUL_SELECT_TIDAK_PUTUS) {
            findTidakPutusNode()?.takeIf {
                it.isVisibleToUser && screenReader.isNodeOrClickableAncestorEnabled(it)
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (radio == null) {
            failCurrentState("Opsi Tidak Putus tidak ditemukan")
            return false
        }

        if (isChecked(radio)) {
            updateStateAndLog(
                AutomationState.TUL_SELECT_TIDAK_PUTUS,
                "Tidak Putus sudah terpilih"
            )
            AutomationRuntime.transitionTo(AutomationState.TUL_SELECT_ALASAN)
            return true
        }

        if (!screenReader.clickNode(radio)) {
            failCurrentState("Gagal memilih Tidak Putus")
            return false
        }

        val checked = poll(CHECK_STATE_TIMEOUT_MS) {
            findTidakPutusNode()?.let { if (isChecked(it)) true else null }
        } == true

        if (!checked) {
            failCurrentState("Tidak Putus tidak terverifikasi setelah ditekan")
            return false
        }

        updateStateAndLog(
            AutomationState.TUL_SELECT_TIDAK_PUTUS,
            "Foto BA TUL selesai; pilihan Tidak Putus terverifikasi"
        )
        AutomationRuntime.transitionTo(AutomationState.TUL_SELECT_ALASAN)
        return true
    }

    private suspend fun handleAlasan(): Boolean {
        val dropdown = waitWithRetry(AutomationState.TUL_SELECT_ALASAN) {
            // Billman V3.0.2 menyediakan label "Pilih Alasan Tidak Putus".
            // Beberapa build lama memakai variasi kapitalisasi/lowercase.
            screenReader.findNodeByText("Pilih Alasan Tidak Putus", exactMatch = true)
                ?: screenReader.findNodeByText("Pilih alasan tidak diputus", exactMatch = true)
                ?: screenReader.findNodeByText("Pilih alasan tidak putus", exactMatch = false)
        }

        if (!AutomationRuntime.isRunning) return false
        if (dropdown == null) {
            failCurrentState("Dropdown Alasan tidak diputus tidak ditemukan")
            return false
        }

        if (!screenReader.clickNode(dropdown)) {
            failCurrentState("Gagal membuka dropdown Alasan tidak diputus")
            return false
        }

        val option = waitWithRetry(AutomationState.TUL_SELECT_ALASAN) {
            screenReader.findNodeByText("LUNAS OFFLINE", exactMatch = true)
                ?: screenReader.findNodeByText("LUNAS OFFLINE", exactMatch = false)
        }

        if (!AutomationRuntime.isRunning) return false
        if (option == null) {
            failCurrentState("Opsi LUNAS OFFLINE tidak muncul setelah dropdown dibuka")
            return false
        }

        if (!screenReader.clickNode(option)) {
            failCurrentState("Gagal memilih LUNAS OFFLINE")
            return false
        }

        updateStateAndLog(
            AutomationState.TUL_SELECT_ALASAN,
            "Alasan tidak diputus = LUNAS OFFLINE"
        )
        AutomationRuntime.transitionTo(AutomationState.TUL_INPUT_DATA)
        return true
    }

    private suspend fun handleInputData(): Boolean {
        val nameField = waitForField(RESIDENT_NAME_LABEL)
        if (!AutomationRuntime.isRunning) return false
        if (nameField == null) {
            failCurrentState("Field Nama Penghuni tidak ditemukan")
            return false
        }

        if (!setFieldValue(nameField, RESIDENT_NAME_VALUE, "Nama Penghuni")) {
            return false
        }

        val waField = waitForField(WHATSAPP_LABEL)
        if (!AutomationRuntime.isRunning) return false
        if (waField == null) {
            failCurrentState("Field Input Nomor WA tidak ditemukan")
            return false
        }

        if (!setFieldValue(waField, WHATSAPP_VALUE, "Input Nomor WA")) {
            return false
        }

        updateStateAndLog(
            AutomationState.TUL_INPUT_DATA,
            "Nama Penghuni = $RESIDENT_NAME_VALUE; Input Nomor WA = $WHATSAPP_VALUE"
        )
        AutomationRuntime.transitionTo(AutomationState.TUL_SELECT_MIGRATION_NO)
        return true
    }

    private suspend fun handleMigrationNo(): Boolean {
        val node = waitWithRetry(AutomationState.TUL_SELECT_MIGRATION_NO) {
            val byId = screenReader.findNodeByResourceId(MIGRATION_NO_ID)
            val byText = screenReader.findNodeByText("Tidak", exactMatch = true)
            val candidate = byId ?: byText
            if (candidate != null && candidate.isVisibleToUser) {
                candidate.takeIf { screenReader.isNodeOrClickableAncestorEnabled(it) }
            } else {
                screenReader.scrollForwardOnce()
                null
            }
        }

        if (!AutomationRuntime.isRunning) return false
        if (node == null) {
            failCurrentState("Opsi migrasi prabayar Tidak tidak ditemukan")
            return false
        }

        if (!isChecked(node)) {
            if (!screenReader.clickNode(node)) {
                failCurrentState("Gagal memilih migrasi prabayar Tidak")
                return false
            }

            val checked = poll(CHECK_STATE_TIMEOUT_MS) {
                val refreshed = screenReader.findNodeByResourceId(MIGRATION_NO_ID)
                    ?: screenReader.findNodeByText("Tidak", exactMatch = true)
                refreshed?.let { if (isChecked(it)) true else null }
            } == true

            if (!checked) {
                failCurrentState("Migrasi prabayar Tidak tidak terverifikasi")
                return false
            }
        }

        updateStateAndLog(
            AutomationState.TUL_SELECT_MIGRATION_NO,
            "Migrasi prabayar = Tidak; menuju tombol Selesai"
        )
        AutomationRuntime.transitionTo(AutomationState.WAIT_SELESAI)
        return false
    }

    private suspend fun waitForField(label: String): AccessibilityNodeInfo? =
        waitWithRetry(AutomationState.TUL_INPUT_DATA) {
            val field = screenReader.findEditableFieldByLabel(label)
            if (field != null && field.isVisibleToUser) field
            else {
                screenReader.scrollForwardOnce()
                null
            }
        }

    private suspend fun setFieldValue(
        field: AccessibilityNodeInfo,
        value: String,
        label: String
    ): Boolean {
        val current = field.text?.toString()?.trim().orEmpty()
        if (current == value) {
            screenReader.clearFocus(field)
            return true
        }

        val direct = screenReader.setTextWithoutKeyboard(field, value)
        if (direct) {
            screenReader.clearFocus(field)
            val verified = poll(VERIFY_TEXT_TIMEOUT_MS) {
                val refreshed = screenReader.findEditableFieldByLabel(label)
                refreshed?.text?.toString()?.trim()?.takeIf { it == value }
            } != null
            if (verified) return true
        }

        val fallback = screenReader.setText(field, value)
        if (!fallback) {
            failCurrentState("Gagal mengisi $label")
            return false
        }

        screenReader.clearFocus(field)
        val verified = poll(VERIFY_TEXT_TIMEOUT_MS) {
            val refreshed = screenReader.findEditableFieldByLabel(label)
            refreshed?.text?.toString()?.trim()?.takeIf { it == value }
        } != null

        if (!verified) {
            failCurrentState("$label tidak terverifikasi setelah diisi")
            return false
        }

        return true
    }

    private fun findTidakPutusNode(): AccessibilityNodeInfo? {
        return screenReader.findNodeByText("TIDAK PUTUS", exactMatch = true)
            ?: screenReader.findNodeByText("Tidak Putus", exactMatch = true)
    }

    private fun isChecked(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val candidate = current ?: return false
            if (candidate.isChecked) return true
            current = candidate.parent
        }
        return false
    }

    private suspend fun retryCameraOrFail(reason: String): Boolean {
        if (cameraRetryCount >= MAX_CAMERA_RETRY) {
            failCurrentState("$reason setelah retry kamera")
            return false
        }

        cameraRetryCount++
        dataStore.addLog(
            currentRecordId,
            AutomationState.TUL_SELECT_TIDAK_PUTUS.name,
            "WARNING",
            "$reason; membuka ulang kamera BA TUL"
        )

        screenReader.performBack()
        val returned = poll(WAIT_TIMEOUT_MS) {
            if (screenReader.isTextVisible("Visit WO RBM", exactMatch = false)) true else null
        }

        if (returned != true) {
            failCurrentState("Gagal kembali ke Visit WO untuk retry kamera BA TUL")
            return false
        }

        AutomationRuntime.transitionTo(AutomationState.TUL_PHOTO_BA)
        return true
    }

    private fun resolveRecordIdIfNeeded() {
        if (currentRecordId != null) return
        val id = AutomationRuntime.currentIdPelanggan ?: return
        currentRecordId = dataStore.findActiveRecordId(id, AutomationRuntime.sessionStartedAt)
    }

    private fun updateStateAndLog(state: AutomationState, message: String) {
        currentRecordId?.let { dataStore.updateLastState(it, state.name) }
        dataStore.addLog(currentRecordId, state.name, "INFO", message)
        AutomationRuntime.setLatestAction(message)
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
        state == AutomationState.TUL_PHOTO_BA ||
            state == AutomationState.TUL_SELECT_TIDAK_PUTUS ||
            state == AutomationState.TUL_SELECT_ALASAN ||
            state == AutomationState.TUL_INPUT_DATA ||
            state == AutomationState.TUL_SELECT_MIGRATION_NO

    companion object {
        private const val POLL_INTERVAL_MS = 300L
        private const val WAIT_TIMEOUT_MS = 10_000L
        private const val CHECK_STATE_TIMEOUT_MS = 3_000L
        private const val VERIFY_TEXT_TIMEOUT_MS = 2_000L
        private const val MAX_RETRY_PER_STATE = 2
        private const val MAX_CAMERA_RETRY = 1

        private const val BA_TUL_LABEL = "Foto Penyampaian BA TUL VI-01"
        private const val RESIDENT_NAME_LABEL = "Nama Penghuni"
        private const val RESIDENT_NAME_VALUE = "Pelanggan"
        private const val WHATSAPP_LABEL = "Input Nomor WA"
        private const val WHATSAPP_VALUE = "0000000000000"
        private const val MIGRATION_NO_ID = "dev.iconpln.mims:id/rb_prabayar_tidak"

        private val TUL_CAMERA_IDS = listOf(
            // ID diverifikasi dari accessibility snapshot Billman V3.0.2:
            // btn_poto_penyampaian_ba adalah ImageView pemicu foto BA TUL.
            "dev.iconpln.mims:id/btn_poto_penyampaian_ba",
            // Fallback visual node pada area foto.
            "dev.iconpln.mims:id/iv_poto_penyampaian_ba",
            // Kandidat lama dipertahankan sebagai fallback lintas versi.
            "dev.iconpln.mims:id/ambil_foto_penyampaian_ba_tul",
            "dev.iconpln.mims:id/btn_ambil_foto_penyampaian_ba_tul",
            "dev.iconpln.mims:id/foto_form_penyerahan_ba_tul_vi_01"
        )
    }
}
