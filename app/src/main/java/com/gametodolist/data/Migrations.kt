package com.gametodolist.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** v1 → v2：task_templates 增加 anchorDate（起始日期）列 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE task_templates ADD COLUMN anchorDate INTEGER NOT NULL DEFAULT 0")
    }
}

/** v2 → v3：清理 (templateId, cycleStart) 重复实例并加唯一索引（防并发重复插入） */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "DELETE FROM task_instances WHERE id NOT IN " +
                "(SELECT MAX(id) FROM task_instances GROUP BY templateId, cycleStart)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX index_task_instances_templateId_cycleStart " +
                "ON task_instances (templateId, cycleStart)"
        )
    }
}

/** v3 → v4：task_templates 增加 paused（停用仍按原规则继续计时）列 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE task_templates ADD COLUMN paused INTEGER NOT NULL DEFAULT 0")
    }
}
