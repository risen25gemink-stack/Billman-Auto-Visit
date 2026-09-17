package com.billmanautovisit.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SqliteLocalDataStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION),
    LocalDataStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE work_order_records (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                id_pelanggan TEXT NOT NULL,
                kode_wo TEXT,
                nama_pelanggan TEXT,
                status TEXT NOT NULL,
                last_state TEXT,
                error_message TEXT,
                started_at INTEGER NOT NULL,
                finished_at INTEGER
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE automation_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                work_order_record_id INTEGER,
                state TEXT NOT NULL,
                level TEXT NOT NULL,
                message TEXT NOT NULL,
                FOREIGN KEY(work_order_record_id) REFERENCES work_order_records(id)
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX idx_work_order_session ON work_order_records(id_pelanggan, started_at)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun hasWorkOrderInSession(idPelanggan: String, sessionStartedAt: Long): Boolean {
        readableDatabase.query(
            "work_order_records",
            arrayOf("id"),
            "id_pelanggan = ? AND started_at >= ?",
            arrayOf(idPelanggan, sessionStartedAt.toString()),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    override fun findActiveRecordId(idPelanggan: String, sessionStartedAt: Long): Long? {
        readableDatabase.query(
            "work_order_records",
            arrayOf("id"),
            "id_pelanggan = ? AND started_at >= ? AND status = ?",
            arrayOf(idPelanggan, sessionStartedAt.toString(), "IN_PROGRESS"),
            null,
            null,
            "id DESC",
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    override fun beginWorkOrder(
        idPelanggan: String,
        kodeWo: String?,
        lastState: String,
        startedAt: Long
    ): Long {
        val values = ContentValues().apply {
            put("id_pelanggan", idPelanggan)
            put("kode_wo", kodeWo)
            put("status", "IN_PROGRESS")
            put("last_state", lastState)
            put("started_at", startedAt)
        }
        return writableDatabase.insertOrThrow("work_order_records", null, values)
    }

    override fun updateLastState(recordId: Long, state: String) {
        val values = ContentValues().apply {
            put("last_state", state)
        }
        writableDatabase.update(
            "work_order_records",
            values,
            "id = ?",
            arrayOf(recordId.toString())
        )
    }

    override fun markSkipped(
        recordId: Long,
        lastState: String,
        message: String,
        finishedAt: Long
    ) {
        val values = ContentValues().apply {
            put("status", "SKIPPED")
            put("last_state", lastState)
            put("error_message", message)
            put("finished_at", finishedAt)
        }
        writableDatabase.update(
            "work_order_records",
            values,
            "id = ?",
            arrayOf(recordId.toString())
        )
    }

    override fun markCompleted(
        recordId: Long,
        lastState: String,
        finishedAt: Long
    ) {
        val values = ContentValues().apply {
            put("status", "COMPLETED")
            put("last_state", lastState)
            putNull("error_message")
            put("finished_at", finishedAt)
        }
        writableDatabase.update(
            "work_order_records",
            values,
            "id = ?",
            arrayOf(recordId.toString())
        )
    }

    override fun addLog(
        workOrderRecordId: Long?,
        state: String,
        level: String,
        message: String
    ) {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            if (workOrderRecordId == null) putNull("work_order_record_id")
            else put("work_order_record_id", workOrderRecordId)
            put("state", state)
            put("level", level)
            put("message", message)
        }
        writableDatabase.insertOrThrow("automation_logs", null, values)
    }

    companion object {
        private const val DB_NAME = "billman_auto_visit.db"
        private const val DB_VERSION = 1
    }
}
