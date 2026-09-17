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

    fun startSession() {
        sessionStartedAt = System.currentTimeMillis()
        currentState = AutomationState.WO_LIST
        currentIdPelanggan = null
        currentKodeWo = null
        stopRequested = false
        isPaused = false
        isRunning = true
        currentPage = null
        totalPages = null
        latestAction = "Memulai sesi automasi"
        TaggingGate.reset()
        statusMessage = "RUNNING: WO_LIST"
    }

    fun transitionTo(state: AutomationState) {
        if (state == AutomationState.TAP_TAGGING) {
            TaggingGate.reset()
        }
        currentState = state
        statusMessage = if (isPaused) {
            "PAUSED: ${state.name}"
        } else {
            "RUNNING: ${state.name}"
        }
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
        statusMessage = "PAUSED: ${currentState.name}"
        latestAction = "Otomasi dijeda"
    }

    fun resume() {
        if (!isRunning || stopRequested || !isPaused) return
        isPaused = false
        statusMessage = "RUNNING: ${currentState.name}"
        latestAction = "Otomasi dilanjutkan"
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
