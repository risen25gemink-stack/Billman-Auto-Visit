package com.billmanautovisit.app.engine

import android.os.SystemClock
import com.billmanautovisit.app.camera.CameraTapper
import com.billmanautovisit.app.core.AutomationRuntime
import com.billmanautovisit.app.core.AutomationState
import com.billmanautovisit.app.data.LocalDataStore
import com.billmanautovisit.app.screen.ScreenReader
import com.billmanautovisit.app.screen.WorkOrderCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class AutomationEngine(
    private val screenReader: ScreenReader,
    private val cameraTapper: CameraTapper,
    private val dataStore: LocalDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = AtomicBoolean(false)

    private var candidatesOnScreen: List<WorkOrderCandidate> = emptyList()
    private var currentRecordId: Long? = null
    private var camera1RetryCount: Int = 0

    fun onBillmanEvent() {
        if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return
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
        while (AutomationRuntime.isRunning && !AutomationRuntime.stopRequested) {
            val shouldContinue = when (AutomationRuntime.currentState) {
                AutomationState.WO_LIST -> handleWoList()
                AutomationState.READ_ID -> handleReadId()
                AutomationState.INPUT_ID -> handleInputId()
                AutomationState.TAP_CARI -> handleTapCari()
                AutomationState.WAIT_CARD -> handleWaitCard()
                AutomationState.TAP_CARD -> handleTapCard()
                AutomationState.MENUJU_WO -> handleMenujuWo()
                AutomationState.TAP_TAGGING -> handleTapTagging()
                AutomationState.TAP_CAMERA_1 -> handleTapCamera1()
                AutomationState.CAMERA_SHUTTER_1 -> handleCameraShutter1()
                AutomationState.BACK_TO_MENUJU_WO -> handleBackToMenujuWo()
                AutomationState.TAP_VISIT -> {
                    AutomationRuntime.setStatus(
                        "RUNNING: TAP_VISIT — uji berhenti sebelum menekan Visit"
                    )
                    false
                }
                else -> false
            }

            if (!shouldContinue) return
        }
    }

    private suspend fun handleWoList(): Boolean {
        val visible = waitWithRetry(AutomationState.WO_LIST) {
            if (screenReader.isTextVisible("Pilih Work Order", exactMatch = false)) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (visible != true) {
            failCurrentState("Layar Pilih Work Order tidak terdeteksi")
            return false
        }

        candidatesOnScreen = poll(WAIT_TIMEOUT_MS) {
            screenReader.readWorkOrderCandidates().takeIf { it.isNotEmpty() }
        } ?: emptyList()

        dataStore.addLog(
            null,
            AutomationState.WO_LIST.name,
            "INFO",
            "Ditemukan ${candidatesOnScreen.size} kandidat WO pada layar"
        )

        if (candidatesOnScreen.isEmpty()) return false

        AutomationRuntime.transitionTo(AutomationState.READ_ID)
        return true
    }

    private fun handleReadId(): Boolean {
        if (candidatesOnScreen.isEmpty()) return false

        val candidate = candidatesOnScreen.firstOrNull { candidate ->
            !dataStore.hasWorkOrderInSession(
                candidate.idPelanggan,
                AutomationRuntime.sessionStartedAt
            )
        } ?: return false

        currentRecordId = dataStore.beginWorkOrder(
            idPelanggan = candidate.idPelanggan,
            kodeWo = candidate.kodeWo,
            lastState = AutomationState.READ_ID.name,
            startedAt = System.currentTimeMillis()
        )

        AutomationRuntime.setCurrentWorkOrder(
            idPelanggan = candidate.idPelanggan,
            kodeWo = candidate.kodeWo
        )
        dataStore.addLog(
            currentRecordId,
            AutomationState.READ_ID.name,
            "INFO",
            "Memilih ID pelanggan ${candidate.idPelanggan} dari WO ${candidate.kodeWo}"
        )
        AutomationRuntime.transitionTo(AutomationState.INPUT_ID)
        return true
    }

    private suspend fun handleInputId(): Boolean {
        val idPelanggan = AutomationRuntime.currentIdPelanggan
        if (idPelanggan.isNullOrBlank()) {
            failCurrentState("ID pelanggan aktif kosong")
            return false
        }

        val field = waitWithRetry(AutomationState.INPUT_ID) {
            screenReader.findEditableFieldByLabel("Cari ID/Nama Pelanggan")
        }

        if (!AutomationRuntime.isRunning) return false
        if (field == null) {
            failCurrentState("Field Cari ID/Nama Pelanggan tidak ditemukan")
            return false
        }

        val clicked = screenReader.clickNode(field)
        val textSet = screenReader.setText(field, idPelanggan)
        if (!clicked || !textSet) {
            failCurrentState(
                "Gagal ${if (!clicked) "tap field" else "set teks"} Cari ID/Nama Pelanggan"
            )
            return false
        }

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.INPUT_ID.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.INPUT_ID.name,
            "INFO",
            "ID pelanggan diisi ke field pencarian"
        )

        AutomationRuntime.transitionTo(AutomationState.TAP_CARI)
        return true
    }

    private suspend fun handleTapCari(): Boolean {
        val button = waitWithRetry(AutomationState.TAP_CARI) {
            screenReader.findNodeByText("Cari", exactMatch = true)
                ?.takeIf { screenReader.isNodeOrClickableAncestorEnabled(it) }
        }

        if (!AutomationRuntime.isRunning) return false
        if (button == null) {
            failCurrentState("Tombol Cari aktif tidak ditemukan")
            return false
        }

        if (!screenReader.clickNode(button)) {
            failCurrentState("Gagal menekan tombol Cari")
            return false
        }

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.TAP_CARI.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.TAP_CARI.name,
            "INFO",
            "Tombol Cari ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.WAIT_CARD)
        return true
    }

    private suspend fun handleWaitCard(): Boolean {
        val kodeWo = AutomationRuntime.currentKodeWo
        if (kodeWo.isNullOrBlank()) {
            failCurrentState("Kode WO aktif kosong")
            return false
        }

        val cardMarker = waitWithRetry(AutomationState.WAIT_CARD) {
            screenReader.findNodeByText(kodeWo, exactMatch = false)
        }

        if (!AutomationRuntime.isRunning) return false
        if (cardMarker == null) return skipWoNotFoundAndReturnToList()

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.WAIT_CARD.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.WAIT_CARD.name,
            "INFO",
            "Card assignment untuk $kodeWo terdeteksi"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_CARD)
        return true
    }

    private suspend fun handleTapCard(): Boolean {
        val kodeWo = AutomationRuntime.currentKodeWo
        if (kodeWo.isNullOrBlank()) {
            failCurrentState("Kode WO aktif kosong")
            return false
        }

        val cardMarker = waitWithRetry(AutomationState.TAP_CARD) {
            screenReader.findNodeByText(kodeWo, exactMatch = false)
        }

        if (!AutomationRuntime.isRunning) return false
        if (cardMarker == null) {
            failCurrentState("Card assignment $kodeWo tidak ditemukan saat akan ditap")
            return false
        }

        if (!screenReader.clickNode(cardMarker)) {
            failCurrentState("Card assignment $kodeWo tidak dapat ditap")
            return false
        }

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.TAP_CARD.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.TAP_CARD.name,
            "INFO",
            "Card assignment $kodeWo ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.MENUJU_WO)
        return true
    }

    private suspend fun handleMenujuWo(): Boolean {
        val detected = waitWithRetry(AutomationState.MENUJU_WO) {
            val titleVisible = screenReader.isTextVisible("Menuju WO", exactMatch = false)
            val navigasiVisible = screenReader.isTextVisible("Navigasi", exactMatch = false)
            val photoVisible = screenReader.isTextVisible("Photo", exactMatch = false)
            val taggingVisible = screenReader.isTextVisible("Tagging", exactMatch = false)
            if (titleVisible && navigasiVisible && photoVisible && taggingVisible) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (detected != true) {
            failCurrentState("Layar Menuju WO tidak terdeteksi lengkap")
            return false
        }

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.MENUJU_WO.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.MENUJU_WO.name,
            "INFO",
            "Layar Menuju WO terdeteksi"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_TAGGING)
        return true
    }

    private suspend fun handleTapTagging(): Boolean {
        val taggingNode = waitWithRetry(AutomationState.TAP_TAGGING) {
            screenReader.findNodeByResourceId(BTN_TAGGING_ID)
                ?: screenReader.findNodeByText("Tagging", exactMatch = true)
        }

        if (!AutomationRuntime.isRunning) return false
        if (taggingNode == null) {
            failCurrentState("Tombol Tagging tidak ditemukan")
            return false
        }

        if (!screenReader.clickNode(taggingNode)) {
            failCurrentState("Gagal menekan tombol Tagging")
            return false
        }

        camera1RetryCount = 0
        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.TAP_TAGGING.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.TAP_TAGGING.name,
            "INFO",
            "Tombol Tagging ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_CAMERA_1)
        return true
    }

    private suspend fun handleTapCamera1(): Boolean {
        val cameraNode = waitWithRetry(AutomationState.TAP_CAMERA_1) {
            screenReader.findNodeByResourceId(BTN_PHOTO_ID)
                ?: screenReader.findNodeByText("Photo", exactMatch = true)
        }

        if (!AutomationRuntime.isRunning) return false
        if (cameraNode == null) {
            failCurrentState("Ikon/tombol kamera Photo tidak ditemukan")
            return false
        }

        if (!screenReader.clickNode(cameraNode)) {
            failCurrentState("Gagal membuka kamera tagging")
            return false
        }

        currentRecordId?.let { dataStore.updateLastState(it, AutomationState.TAP_CAMERA_1.name) }
        dataStore.addLog(
            currentRecordId,
            AutomationState.TAP_CAMERA_1.name,
            "INFO",
            "Kamera tagging dibuka"
        )
        AutomationRuntime.transitionTo(AutomationState.CAMERA_SHUTTER_1)
        return true
    }

    private suspend fun handleCameraShutter1(): Boolean {
        val shutter = poll(WAIT_TIMEOUT_MS) {
            cameraTapper.findShutterNode()
        }

        if (!AutomationRuntime.isRunning) return false
        if (shutter == null) {
            return retryCamera1OrFail("Tombol shutter tidak terdeteksi")
        }

        if (!cameraTapper.tapShutter(shutter)) {
            return retryCamera1OrFail("Tombol shutter terdeteksi tetapi gagal ditekan")
        }

        currentRecordId?.let {
            dataStore.updateLastState(it, AutomationState.CAMERA_SHUTTER_1.name)
        }
        dataStore.addLog(
            currentRecordId,
            AutomationState.CAMERA_SHUTTER_1.name,
            "INFO",
            "Shutter kamera tagging ditekan"
        )
        AutomationRuntime.transitionTo(AutomationState.BACK_TO_MENUJU_WO)
        return true
    }

    private suspend fun retryCamera1OrFail(reason: String): Boolean {
        if (camera1RetryCount >= MAX_CAMERA_OPEN_RETRY) {
            failCurrentState("$reason setelah retry kamera")
            return false
        }

        camera1RetryCount += 1
        dataStore.addLog(
            currentRecordId,
            AutomationState.CAMERA_SHUTTER_1.name,
            "WARNING",
            "$reason; buka ulang kamera 1x"
        )

        screenReader.performBack()
        val returned = poll(WAIT_TIMEOUT_MS) {
            if (screenReader.isTextVisible("Menuju WO", exactMatch = false)) true else null
        }
        if (returned != true) {
            failCurrentState("Gagal kembali ke Menuju WO untuk retry kamera")
            return false
        }

        AutomationRuntime.transitionTo(AutomationState.TAP_CAMERA_1)
        return true
    }

    private suspend fun handleBackToMenujuWo(): Boolean {
        val returned = waitWithRetry(AutomationState.BACK_TO_MENUJU_WO) {
            val titleVisible = screenReader.isTextVisible("Menuju WO", exactMatch = false)
            val visitVisible = screenReader.isTextVisible("Visit", exactMatch = true)
            if (titleVisible && visitVisible) true else null
        }

        if (!AutomationRuntime.isRunning) return false
        if (returned != true) {
            failCurrentState("Tidak kembali ke layar Menuju WO setelah foto tagging")
            return false
        }

        currentRecordId?.let {
            dataStore.updateLastState(it, AutomationState.BACK_TO_MENUJU_WO.name)
        }
        dataStore.addLog(
            currentRecordId,
            AutomationState.BACK_TO_MENUJU_WO.name,
            "INFO",
            "Kembali ke Menuju WO setelah foto tagging"
        )
        AutomationRuntime.transitionTo(AutomationState.TAP_VISIT)
        return true
    }

    private suspend fun skipWoNotFoundAndReturnToList(): Boolean {
        val recordId = currentRecordId
        val message = "WO tidak ditemukan"

        if (recordId != null) {
            dataStore.markSkipped(
                recordId = recordId,
                lastState = AutomationState.WAIT_CARD.name,
                message = message,
                finishedAt = System.currentTimeMillis()
            )
        }
        dataStore.addLog(
            recordId,
            AutomationState.WAIT_CARD.name,
            "WARNING",
            message
        )

        repeat(MAX_BACK_ATTEMPTS) { attempt ->
            if (!AutomationRuntime.isRunning || AutomationRuntime.stopRequested) return false

            if (!screenReader.performBack()) {
                dataStore.addLog(
                    recordId,
                    AutomationState.WAIT_CARD.name,
                    "WARNING",
                    "GLOBAL_ACTION_BACK gagal pada percobaan ${attempt + 1}"
                )
            }

            val backAtList = poll(WAIT_TIMEOUT_MS) {
                if (screenReader.isTextVisible("Pilih Work Order", exactMatch = false)) true else null
            }
            if (backAtList == true) {
                currentRecordId = null
                candidatesOnScreen = emptyList()
                AutomationRuntime.setCurrentWorkOrder(null, null)
                AutomationRuntime.transitionTo(AutomationState.WO_LIST)
                return true
            }
        }

        failCurrentState("Gagal kembali ke Pilih Work Order setelah WO tidak ditemukan")
        return false
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

    companion object {
        private const val POLL_INTERVAL_MS = 300L
        private const val WAIT_TIMEOUT_MS = 10_000L
        private const val MAX_RETRY_PER_STATE = 2
        private const val MAX_BACK_ATTEMPTS = 5
        private const val MAX_CAMERA_OPEN_RETRY = 1

        private const val BTN_TAGGING_ID = "dev.iconpln.mims:id/btn_tagging"
        private const val BTN_PHOTO_ID = "dev.iconpln.mims:id/btn_photo"
    }
}
