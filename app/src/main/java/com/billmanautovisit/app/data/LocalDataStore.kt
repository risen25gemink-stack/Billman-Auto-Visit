package com.billmanautovisit.app.data

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

    fun close()
}
