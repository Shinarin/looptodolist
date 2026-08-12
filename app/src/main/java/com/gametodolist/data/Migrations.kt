package com.gametodolist.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：task_templates 增加 anchorDate（起始日期）列 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE task_templates ADD COLUMN anchorDate INTEGER NOT NULL DEFAULT 0")
    }
}
