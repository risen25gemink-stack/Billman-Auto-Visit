package com.billmanautovisit.app.engine

class AutomationEngine {
    @Volatile
    var isRunning: Boolean = false
        private set

    @Volatile
    var stopRequested: Boolean = false
        private set

    fun start() {
        stopRequested = false
        isRunning = true
    }

    fun requestStop() {
        stopRequested = true
        isRunning = false
    }
}
