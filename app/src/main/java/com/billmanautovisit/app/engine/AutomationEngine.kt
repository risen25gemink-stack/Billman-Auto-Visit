package com.billmanautovisit.app.engine

import android.os.SystemClock
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
    private val dataStore: LocalDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val processing = AtomicBoolean(false)

    private var candidatesOnScreen: List<WorkOrderCandidate> = emptyList()
    private var currentRecordId: Long? = null

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
                AutomationState.TAP_TAGGING -> {
                    AutomationRuntime.setStatus(
                        "RUNNING: TAP_TAGGING — uji berhenti sebelum menekan Tagging"
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

        if (candidatesOnScreen.isEmpty()) {
            return false
        }

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
        if (cardMarker == null) {
            return skipWoNotFoundAndReturnToList()
        }

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
    }
}
