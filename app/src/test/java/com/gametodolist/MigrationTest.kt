package com.gametodolist

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gametodolist.data.MIGRATION_2_3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MigrationTest {

    private fun v2Db(): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(ApplicationProvider.getApplicationContext())
                .name(null) // in-memory
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE task_instances (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "templateId INTEGER NOT NULL, cycleStart INTEGER NOT NULL, " +
                                "windowStart INTEGER NOT NULL, windowEnd INTEGER NOT NULL, " +
                                "status TEXT NOT NULL, completedAt INTEGER)"
                        )
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        ).writableDatabase

    @Test
    fun `v2到v3 清理重复并建唯一索引`() {
        val db = v2Db()
        // 同 (templateId, cycleStart) 两条（模拟并发重复插入的脏数据）
        db.execSQL("INSERT INTO task_instances (templateId, cycleStart, windowStart, windowEnd, status) VALUES (1, 100, 100, 200, 'EXPIRED')")
        db.execSQL("INSERT INTO task_instances (templateId, cycleStart, windowStart, windowEnd, status) VALUES (1, 100, 100, 200, 'ACTIVE')")

        MIGRATION_2_3.migrate(db)

        db.query("SELECT COUNT(*) FROM task_instances").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        // 保留的是 id 较大（最新插入）的一条
        db.query("SELECT status FROM task_instances").use { c ->
            c.moveToFirst(); assertEquals("ACTIVE", c.getString(0))
        }
        // 唯一索引生效：重复插入被拒
        val rejected = runCatching {
            db.execSQL("INSERT INTO task_instances (templateId, cycleStart, windowStart, windowEnd, status) VALUES (1, 100, 100, 200, 'ACTIVE')")
        }.isFailure
        assertTrue(rejected)
        db.close()
    }
}
