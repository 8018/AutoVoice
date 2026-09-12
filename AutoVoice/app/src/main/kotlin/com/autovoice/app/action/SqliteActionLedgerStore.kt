package com.autovoice.app.action

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.autovoice.app.action.ActionLedgerStore.Companion.STATE_EXECUTING
import com.autovoice.app.action.ActionLedgerStore.Companion.STATE_RESULT_UNKNOWN

/**
 * D07b 手机本地动作账本(独立 SQLite 业务库,与遥测/其他数据分离)。
 * 使用 Android 框架 SQLite 默认日志模式,不自研 WAL。
 */
class SqliteActionLedgerStore(context: Context) : ActionLedgerStore {

    private val helper = object : SQLiteOpenHelper(
        context, "action-ledger.db", null, 1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE action_records (
                    action_id TEXT PRIMARY KEY,
                    summary TEXT NOT NULL,
                    state TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 首版,无迁移
        }
    }

    override fun claim(actionId: String, summary: String): Boolean {
        if (actionId.isBlank()) return false
        val now = System.currentTimeMillis()
        val values = android.content.ContentValues().apply {
            put("action_id", actionId)
            put("summary", summary)
            put("state", STATE_EXECUTING)
            put("created_at", now)
            put("updated_at", now)
        }
        val inserted = helper.writableDatabase.insertWithOnConflict(
            "action_records", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        return inserted != -1L
    }

    override fun markTerminal(actionId: String, state: String) {
        if (actionId.isBlank()) return
        val values = android.content.ContentValues().apply {
            put("state", state)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update(
            "action_records", values, "action_id = ?", arrayOf(actionId),
        )
    }

    override fun stateOf(actionId: String): String? {
        if (actionId.isBlank()) return null
        helper.readableDatabase.query(
            "action_records", arrayOf("state"), "action_id = ?", arrayOf(actionId),
            null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    override fun recoverUnknowns() {
        val values = android.content.ContentValues().apply {
            put("state", STATE_RESULT_UNKNOWN)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update(
            "action_records", values, "state = ?", arrayOf(STATE_EXECUTING),
        )
    }
}
