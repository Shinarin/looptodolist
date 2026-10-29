package com.gametodolist

import android.content.ContextWrapper
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.gametodolist.data.AppDatabase
import com.gametodolist.data.MIGRATION_2_3
import com.gametodolist.data.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

    @Test
    fun `v3到v4 保留完整数据并通过Room校验`() {
        val testDir = File(System.getProperty("user.dir"), "build/tmp/migration-test").apply { mkdirs() }
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext()) {
            override fun getDatabasePath(name: String): File = File(testDir, name)
        }
        val dbName = "full-v3.db"
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(dbName)
                    .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(db: SupportSQLiteDatabase) = createV3Schema(db)
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                    })
                    .build()
            )
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO categories (id, name, color, icon, sortOrder) VALUES (3, '游戏', 123456, 'G', 2)")
            db.execSQL(
                "INSERT INTO task_templates (" +
                    "id, categoryId, title, note, sortOrder, recurrenceType, intervalDays, weekdaysMask, " +
                    "monthDay, windowMode, customWindowDays, customStartMinutes, customEndMinutes, " +
                    "reminderEnabled, reminderMinutesBefore, anchorDate, archived, createdAt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    7L, 3L, "完整旧清单", "保留备注", 9L, "WEEKLY", 14L, 65L,
                    31L, "CUSTOM", 5L, 120L, 900L, 1L, 45L, 123456789L, 0L, 987654321L
                )
            )
            db.execSQL(
                "INSERT INTO task_instances (id, templateId, cycleStart, windowStart, windowEnd, status, completedAt) " +
                    "VALUES (11, 7, 1000, 1000, 2000, 'COMPLETED', 1500)"
            )
            db.execSQL(
                "INSERT INTO task_instances (id, templateId, cycleStart, windowStart, windowEnd, status, completedAt) " +
                    "VALUES (12, 7, 3000, 3000, 4000, 'ACTIVE', NULL)"
            )
            helper.close()

            val room = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                .addMigrations(MIGRATION_3_4)
                .build()
            try {
                val migrated = room.openHelper.writableDatabase
                val expected = mapOf<String, Any>(
                    "id" to 7L,
                    "categoryId" to 3L,
                    "title" to "完整旧清单",
                    "note" to "保留备注",
                    "sortOrder" to 9L,
                    "recurrenceType" to "WEEKLY",
                    "intervalDays" to 14L,
                    "weekdaysMask" to 65L,
                    "monthDay" to 31L,
                    "windowMode" to "CUSTOM",
                    "customWindowDays" to 5L,
                    "customStartMinutes" to 120L,
                    "customEndMinutes" to 900L,
                    "reminderEnabled" to 1L,
                    "reminderMinutesBefore" to 45L,
                    "anchorDate" to 123456789L,
                    "archived" to 0L,
                    "createdAt" to 987654321L
                )
                migrated.query("SELECT * FROM task_templates WHERE id = 7").use { c ->
                    assertTrue(c.moveToFirst())
                    expected.forEach { (column, value) ->
                        val index = c.getColumnIndex(column)
                        val actual = if (value is String) c.getString(index) else c.getLong(index)
                        assertEquals(column, value, actual)
                    }
                    assertEquals(0, c.getInt(c.getColumnIndex("paused")))
                }
                migrated.query("SELECT name, color, icon, sortOrder FROM categories WHERE id = 3").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("游戏", c.getString(0))
                    assertEquals(123456, c.getInt(1))
                    assertEquals("G", c.getString(2))
                    assertEquals(2, c.getInt(3))
                }
                migrated.query("SELECT COUNT(*) FROM task_instances").use { c ->
                    c.moveToFirst()
                    assertEquals(2, c.getInt(0))
                }
                migrated.query("SELECT status, completedAt FROM task_instances WHERE id = 11").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("COMPLETED", c.getString(0))
                    assertEquals(1500, c.getInt(1))
                }
                migrated.query("SELECT status, completedAt FROM task_instances WHERE id = 12").use { c ->
                    assertTrue(c.moveToFirst())
                    assertEquals("ACTIVE", c.getString(0))
                    assertTrue(c.isNull(1))
                }
                val indices = mutableSetOf<String>()
                migrated.query("PRAGMA index_list('task_instances')").use { c ->
                    while (c.moveToNext()) indices.add(c.getString(c.getColumnIndex("name")))
                }
                assertEquals(
                    setOf(
                        "index_task_instances_templateId",
                        "index_task_instances_windowEnd",
                        "index_task_instances_status",
                        "index_task_instances_templateId_cycleStart"
                    ),
                    indices
                )
                migrated.query("PRAGMA integrity_check").use { c ->
                    c.moveToFirst()
                    assertEquals("ok", c.getString(0))
                }
            } finally {
                room.close()
            }
        } finally {
            val path = context.getDatabasePath(dbName).path
            listOf("", "-wal", "-shm", "-journal").forEach { suffix -> File(path + suffix).delete() }
        }
    }

    private fun createV3Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `color` INTEGER NOT NULL, `icon` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_templates` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `categoryId` INTEGER, `title` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, `recurrenceType` TEXT NOT NULL, " +
                "`intervalDays` INTEGER NOT NULL, `weekdaysMask` INTEGER NOT NULL, `monthDay` INTEGER NOT NULL, " +
                "`windowMode` TEXT NOT NULL, `customWindowDays` INTEGER NOT NULL, " +
                "`customStartMinutes` INTEGER NOT NULL, `customEndMinutes` INTEGER NOT NULL, " +
                "`reminderEnabled` INTEGER NOT NULL, `reminderMinutesBefore` INTEGER NOT NULL, " +
                "`anchorDate` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_templates_categoryId` ON `task_templates` (`categoryId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `task_instances` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, " +
                "`cycleStart` INTEGER NOT NULL, `windowStart` INTEGER NOT NULL, `windowEnd` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `completedAt` INTEGER, " +
                "FOREIGN KEY(`templateId`) REFERENCES `task_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_instances_templateId` ON `task_instances` (`templateId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_instances_windowEnd` ON `task_instances` (`windowEnd`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_task_instances_status` ON `task_instances` (`status`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_task_instances_templateId_cycleStart` " +
                "ON `task_instances` (`templateId`, `cycleStart`)"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        db.execSQL(
            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                "VALUES (42, '6c83d28a3fab7c17c6c5c8dd3e278e40')"
        )
    }
}
