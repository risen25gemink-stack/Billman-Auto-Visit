package com.billmanautovisit.app.core

object AutomationRuntime {
    @Volatile
    var currentState: AutomationState = AutomationState.WO_LIST
        private set

    @Volatile
    var currentIdPelanggan: String? = null
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
        stopRequested = false
        isRunning = true
        statusMessage = "RUNNING: WO_LIST"
    }

    fun transitionTo(state: AutomationState) {
        currentState = state
        statusMessage = "RUNNING: ${state.name}"
    }

    fun setCurrentId(idPelanggan: String?) {
        currentIdPelanggan = idPelanggan
    }

    fun setStatus(message: String) {
        statusMessage = message
    }

    fun requestStop() {
        stopRequested = true
        isRunning = false
        statusMessage = "IDLE"
    }

    fun fail(message: String) {
        stopRequested = true
        isRunning = false
        statusMessage = "ERROR: $message"
    }
}
