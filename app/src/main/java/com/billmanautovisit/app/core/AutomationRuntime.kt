package com.billmanautovisit.app.core

object AutomationRuntime {
    @Volatile
    var currentState: AutomationState = AutomationState.WO_LIST
        private set

    @Volatile
    var currentIdPelanggan: String? = null
        private set

    @Volatile
    var currentKodeWo: String? = null
        private set

    @Volatile
    var selectedFlow: AutomationFlow = AutomationFlow.LUNAS_MANDIRI
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile
    var sessionStartedAt: Long = 0L
        private set

    @Volatile
    var statusMessage: String = "IDLE"
        private set

    @Volatile
    var latestAction: String = "Belum ada tindakan"
        private set

    @Volatile
    var currentPage: Int? = null
        private set

    @Volatile
    var totalPages: Int? = null
        private set

    fun startSession(flow: AutomationFlow = AutomationFlow.LUNAS_MANDIRI) {
        selectedFlow = flow
        sessionStartedAt = System.currentTimeMillis()
        currentState = AutomationState.WO_LIST
        currentIdPelanggan = null
        currentKodeWo = null
        stopRequested = false
        isPaused = false
        isRunning = true
        currentPage = null
        totalPages = null
        latestAction = "Memulai sesi: ${flow.displayName}"
        TaggingGate.reset()
        statusMessage = "RUNNING: WO_LIST — ${flow.displayName}"
    }

    fun changeSelectedFlow(flow: AutomationFlow): Boolean {
        if (isRunning && !isPaused) return false
        selectedFlow = flow
        latestAction = "Alur dipilih: ${flow.displayName}"
        if (isPaused) {
            statusMessage = "PAUSED: ${currentState.name} — ${flow.displayName}"
        }
        return true
    }

    fun transitionTo(state: AutomationState) {
        if (state == AutomationState.TAP_TAGGING) {
            TaggingGate.reset()
        }
        currentState = state
        statusMessage = if (isPaused) "PAUSED: ${state.name}" else "RUNNING: ${state.name}"
    }

    fun setCurrentWorkOrder(idPelanggan: String?, kodeWo: String?) {
        currentIdPelanggan = idPelanggan
        currentKodeWo = kodeWo
    }

    fun setCurrentId(idPelanggan: String?) {
        currentIdPelanggan = idPelanggan
    }

    fun setPageInfo(current: Int?, total: Int?) {
        currentPage = current
        totalPages = total
    }

    fun setLatestAction(message: String) {
        if (message.isNotBlank()) latestAction = message
    }

    fun setStatus(message: String) {
        statusMessage = message
    }

    fun pause() {
        if (!isRunning || stopRequested || isPaused) return
        isPaused = true
        isRunning = false
        statusMessage = "PAUSED: ${currentState.name}"
        latestAction = "Otomasi dijeda"
    }

    fun resume() {
        if (stopRequested || !isPaused) return
        isPaused = false
        isRunning = true
        statusMessage = "RUNNING: ${currentState.name}"
        latestAction = "Otomasi dilanjutkan — ${selectedFlow.displayName}"
    }

    fun complete(message: String) {
        currentState = AutomationState.WO_LIST
        currentIdPelanggan = null
        currentKodeWo = null
        stopRequested = false
        isPaused = false
        isRunning = false
        TaggingGate.reset()
        latestAction = message
        statusMessage = "COMPLETED: $message"
    }

    fun requestStop() {
        stopRequested = true
        isPaused = false
        isRunning = false
        TaggingGate.reset()
        latestAction = "Otomasi dihentikan"
        statusMessage = "IDLE"
    }

    fun fail(message: String) {
        stopRequested = true
        isPaused = false
        isRunning = false
        TaggingGate.reset()
        latestAction = message
        statusMessage = "ERROR: $message"
    }
}
