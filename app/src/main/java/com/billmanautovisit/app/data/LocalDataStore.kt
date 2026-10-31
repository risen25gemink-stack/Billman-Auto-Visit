package com.billmanautovisit.app.data

data class SessionStats(
    val completed: Int,
    val skipped: Int,
    val inProgress: Int,
    val totalSeen: Int
)

data class AutomationLogEntry(
    val timestamp: Long,
    val state: String,
    val level: String,
    val message: String
)

interface LocalDataStore {
    fun hasWorkOrderInSession(idPelanggan: String, sessionStartedAt: Long): Boolean

    fun findActiveRecordId(idPelanggan: String, sessionStartedAt: Long): Long?

    fun beginWorkOrder(
        idPelanggan: String,
        kodeWo: String?,
        lastState: String,
        startedAt: Long
    ): Long

    fun updateLastState(recordId: Long, state: String)

    fun markSkipped(
        recordId: Long,
        lastState: String,
        message: String,
        finishedAt: Long
    )

    fun markCompleted(
        recordId: Long,
        lastState: String,
        finishedAt: Long
    )

    fun addLog(
        workOrderRecordId: Long?,
        state: String,
        level: String,
        message: String
    )

    fun getSessionStats(sessionStartedAt: Long): SessionStats

    fun getRecentLogs(sessionStartedAt: Long, limit: Int = 150): List<AutomationLogEntry>

    fun close()
}
