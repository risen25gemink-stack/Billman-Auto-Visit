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
    var stopRequested: Boolean = false
        private set

    @Volatile
    var sessionStartedAt: Long = 0L
        private set

    @Volatile
    var statusMessage: String = "IDLE"
        private set

    fun startSession() {
        sessionStartedAt = System.currentTimeMillis()
        currentState = AutomationState.WO_LIST
        currentIdPelanggan = null
        currentKodeWo = null
        stopRequested = false
        isRunning = true
        TaggingGate.reset()
        statusMessage = "RUNNING: WO_LIST"
    }

    fun transitionTo(state: AutomationState) {
        if (state == AutomationState.TAP_TAGGING) {
            TaggingGate.reset()
        }
        currentState = state
        statusMessage = "RUNNING: ${state.name}"
    }

    fun setCurrentWorkOrder(idPelanggan: String?, kodeWo: String?) {
        currentIdPelanggan = idPelanggan
        currentKodeWo = kodeWo
    }

    fun setCurrentId(idPelanggan: String?) {
        currentIdPelanggan = idPelanggan
    }

    fun setStatus(message: String) {
        statusMessage = message
    }

    fun complete(message: String) {
        currentState = AutomationState.WO_LIST
        currentIdPelanggan = null
        currentKodeWo = null
        stopRequested = false
        isRunning = false
        TaggingGate.reset()
        statusMessage = "COMPLETED: $message"
    }

    fun requestStop() {
        stopRequested = true
        isRunning = false
        TaggingGate.reset()
        statusMessage = "IDLE"
    }

    fun fail(message: String) {
        stopRequested = true
        isRunning = false
        TaggingGate.reset()
        statusMessage = "ERROR: $message"
    }
}
