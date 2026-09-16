package com.billmanautovisit.app.data

interface LocalDataStore {
    fun hasWorkOrderInSession(idPelanggan: String, sessionStartedAt: Long): Boolean

    fun beginWorkOrder(
        idPelanggan: String,
        kodeWo: String?,
        lastState: String,
        startedAt: Long
    ): Long

    fun updateLastState(recordId: Long, state: String)

    fun addLog(
        workOrderRecordId: Long?,
        state: String,
        level: String,
        message: String
    )

    fun close()
}
