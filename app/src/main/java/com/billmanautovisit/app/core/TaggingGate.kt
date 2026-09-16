package com.billmanautovisit.app.core

object TaggingGate {
    @Volatile
    private var ready: Boolean = false

    fun reset() {
        ready = false
    }

    fun markReady() {
        ready = true
    }

    fun isReady(): Boolean = ready
}
