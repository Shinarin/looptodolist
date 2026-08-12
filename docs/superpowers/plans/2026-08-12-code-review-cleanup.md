# Gametodolist 汇总审查修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已批准设计（`docs/superpowers/specs/2026-08-12-code-review-cleanup-design.md`）修复全部 A（数据丢失/崩溃）、B（模块断链）、C（便利性）、D（冗余清理）类问题，核心是新建 DataSyncManager 统一变更管线根治"顾此失彼"。

**Architecture:** Room 数据层修正确性（insert/update 分流、唯一索引 v3）→ 领域层语义补全（NONE 即过期、regenerate 统一、WEEKLY/MONTHLY anchor）→ DataSyncManager 收口所有写后效应 → UI 层衔接与便利性 → 清理与模拟器验证。

**Tech Stack:** Kotlin + Jetpack Compose + Room 2.6.1 + Hilt + WorkManager + Glance；测试：JUnit4 + kotlinx-coroutines-test + Robolectric（新增）。

## Global Constraints

- 构建/测试命令（Windows Git Bash，必须先设 JAVA_HOME）：
  ```bash
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
  /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain
  ```
  强制重跑单测：`... gradle cleanTest testDebugUnitTest --rerun --console=plain`
- 验证测试结果：`grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml`（failures/errors 必须为 0）。
- adb shell 里的绝对路径必须加 `MSYS_NO_PATHCONV=1` 前缀；UI 坐标用 `adb shell uiautomator dump` + pull，不靠截图估算。
- minSdk 26 / targetSdk 35 / Java 17 / Room 2.6.1（已有 `@Upsert` 能力但本计划不用——insert/update 分流语义更明确）。
- 窗口语义全线不变：半开 `[start, end)`；`Long.MAX_VALUE` = 长期有效。
- `anchorDate <= 0` = 未设置（存量 WEEKLY/MONTHLY 数据保持旧行为）。
- 提交信息中文 + conventional prefix（fix/feat/refactor/chore/test/docs）；每个 Task 结束提交一次。
- 明确不做：深色主题重做、任务排序 UI（`sortOrder` 保留+注释）、v1→v2 迁移补测、磨砂背景复活。
- spec 修正点（以此计划为准）：① DAO 不用 `@Upsert`，改 insert/update 分流（语义更明确）；② MONTHLY 的日号仍由 `monthDay` 字段决定，`anchorDate` 只约束"首个循环不早于 anchor"（spec 中"日号取 anchor 日"表述作废）；③ 编辑 WEEKLY/MONTHLY 旧模板时开始日期默认"未设置"（开关式 UI），避免改个标题就改变循环行为。

---

## 批次 1：数据层与领域层

### Task 1: DAO insert/update 分流 + Robolectric 集成测试（修编辑丢历史）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/data/dao/TaskTemplateDao.kt`
- Modify: `app/src/main/java/com/gametodolist/data/dao/CategoryDao.kt`
- Modify: `app/src/main/java/com/gametodolist/data/TodoRepository.kt:43,55`
- Modify: `app/build.gradle.kts`（加测试依赖）
- Test: `app/src/test/java/com/gametodolist/RoomIntegrationTest.kt`（新建）
- Modify: `app/src/test/java/com/gametodolist/RolloverManagerTest.kt:22-40`（FakeTemplateDao 接口同步）

**Interfaces:**
- Consumes: 现有实体（`TaskTemplateEntity`/`CategoryEntity` id==0 表示新增）。
- Produces: `TaskTemplateDao.insert(e): Long`（纯插入）、`TaskTemplateDao.update(e)`；`CategoryDao.insert(e): Long`、`CategoryDao.update(e)`；`TodoRepository.saveTemplate(e): Long`、`TodoRepository.upsertCategory(e): Long`（内部按 id==0 分流）。后续任务全部依赖这套新方法。

- [ ] **Step 1: 加测试依赖**

`app/build.gradle.kts` dependencies 块末尾加：

```kotlin
    // JVM 集成测试（真实 Room in-memory）
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
```

- [ ] **Step 2: 写失败集成测试**

新建 `app/src/test/java/com/gametodolist/RoomIntegrationTest.kt`：

```kotlin
package com.gametodolist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gametodolist.data.AppDatabase
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 Room（in-memory）集成测试：验证外键级联下的数据完整性 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class RoomIntegrationTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `编辑模板保留全部历史实例`() = runTest {
        val templateDao = db.taskTemplateDao()
        val instanceDao = db.taskInstanceDao()
        val id = templateDao.insert(
            TaskTemplateEntity(categoryId = null, title = "原始", recurrenceType = "WEEKLY", weekdaysMask = 1)
        )
        instanceDao.insert(TaskInstanceEntity(templateId = id, cycleStart = 1000, windowStart = 1000, windowEnd = 2000, status = "COMPLETED"))
        instanceDao.insert(TaskInstanceEntity(templateId = id, cycleStart = 3000, windowStart = 3000, windowEnd = 4000))

        // 模拟编辑保存：改标题后按 id 写回
        val saved = templateDao.getById(id)!!.copy(title = "改过")
        if (saved.id == 0L) templateDao.insert(saved) else templateDao.update(saved)

        val latest = instanceDao.getLatestForTemplate(id)
        assertNotNull("编辑后当前实例必须仍在", latest)
        assertEquals(3000, latest!!.cycleStart) // 最新的 ACTIVE 实例仍在
        assertEquals(1, instanceDao.getActive().size) // 历史未被级联清空
    }

    @Test
    fun `删除模板仍级联清空实例`() = runTest {
        val templateDao = db.taskTemplateDao()
        val instanceDao = db.taskInstanceDao()
        val id = templateDao.insert(TaskTemplateEntity(categoryId = null, title = "t"))
        instanceDao.insert(TaskInstanceEntity(templateId = id, cycleStart = 1000, windowStart = 1000, windowEnd = 2000))

        templateDao.deleteById(id)

        assertEquals(null, instanceDao.getLatestForTemplate(id))
    }
}
```

说明：测试调用的是 DAO 的 `insert`/`update` 组合；当前 `@Insert(REPLACE)` 下第一个测试会因 FK CASCADE 清空实例而失败（`getLatestForTemplate` 返回 null）。

- [ ] **Step 3: 跑测试确认失败**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle testDebugUnitTest --tests "com.gametodolist.RoomIntegrationTest" --console=plain
```
预期：`编辑模板保留全部历史实例` FAIL（实例被 REPLACE 级联删除）。

- [ ] **Step 4: DAO 去 REPLACE + Repository 分流**

`TaskTemplateDao.kt`：删 `import androidx.room.OnConflictStrategy`，改：

```kotlin
    @Insert
    suspend fun insert(template: TaskTemplateEntity): Long
```

`CategoryDao.kt` 同样改 `@Insert`（去 REPLACE，删 OnConflictStrategy import）。

`TodoRepository.kt` 两处方法改分流：

```kotlin
    suspend fun upsertCategory(category: CategoryEntity): Long =
        if (category.id == 0L) categoryDao.insert(category)
        else { categoryDao.update(category); category.id }

    suspend fun saveTemplate(template: TaskTemplateEntity): Long =
        if (template.id == 0L) templateDao.insert(template)
        else { templateDao.update(template); template.id }
```

`RolloverManagerTest.kt` 的 FakeTemplateDao 同步真实语义（insert 不再 removeAll）：

```kotlin
    override suspend fun insert(template: TaskTemplateEntity): Long {
        val id = (templates.maxOfOrNull { it.id } ?: 0) + 1
        templates.add(template.copy(id = id))
        return id
    }
    override suspend fun update(template: TaskTemplateEntity) {
        templates.removeAll { it.id == template.id }
        templates.add(template)
    }
```

- [ ] **Step 5: 跑全部单测确认通过**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest --console=plain
```
预期：BUILD SUCCESSFUL，`grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml` 无 failures/errors。

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/gametodolist/data app/src/test && git commit -m "fix: 模板/分类保存改 insert/update 分流，根治编辑时 REPLACE 级联清空实例历史"
```

### Task 2: DB v3——实例唯一索引 + 迁移 + exportSchema

**Files:**
- Modify: `app/src/main/java/com/gametodolist/data/entity/TaskInstanceEntity.kt:22`
- Modify: `app/src/main/java/com/gametodolist/data/AppDatabase.kt:12-16`
- Modify: `app/src/main/java/com/gametodolist/data/Migrations.kt`
- Modify: `app/src/main/java/com/gametodolist/di/AppModule.kt:6,25`
- Modify: `app/build.gradle.kts`（ksp schemaLocation）
- Test: `app/src/test/java/com/gametodolist/MigrationTest.kt`（新建）

**Interfaces:**
- Consumes: Task 1 的 DAO 分流。
- Produces: DB version 3；`MIGRATION_2_3`；实例表 `(templateId, cycleStart)` 唯一约束（Task 4 的并发防重依赖它；`TaskInstanceDao.insert` 保持 REPLACE——唯一索引冲突时新行替换旧行，属"每循环保留最新一条"自愈语义）。

- [ ] **Step 1: 写迁移逻辑测试**

新建 `app/src/test/java/com/gametodolist/MigrationTest.kt`（用 SupportSQLiteOpenHelper 手工建 v2 形状表，不依赖 Room identity hash）：

```kotlin
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
```

- [ ] **Step 2: 跑测试确认编译失败/失败**

预期：`MIGRATION_2_3` 未定义，编译失败。

- [ ] **Step 3: 实现 v3**

`Migrations.kt` 追加：

```kotlin
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
```

`TaskInstanceEntity.kt` 的 indices 改：

```kotlin
    indices = [
        Index("templateId"), Index("windowEnd"), Index("status"),
        Index(value = ["templateId", "cycleStart"], unique = true)
    ]
```

`AppDatabase.kt`：`version = 3`、`exportSchema = true`。

`AppModule.kt`：import 加 `MIGRATION_2_3`，`.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。

`app/build.gradle.kts` 顶层（android 块外）加：

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

- [ ] **Step 4: 跑全部单测 + 构建**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
```
预期：全绿；`app/schemas/3.json` 生成（git add 它）。

- [ ] **Step 5: Commit**

```bash
git add app && git commit -m "feat: DB v3 实例表 (templateId,cycleStart) 唯一索引 + 去重迁移，打开 exportSchema"
```

### Task 3: 枚举兜底解析收敛到共享扩展

**Files:**
- Modify: `app/src/main/java/com/gametodolist/domain/Models.kt`
- Modify: `app/src/main/java/com/gametodolist/domain/RecurrenceEngine.kt:27-28,85`
- Modify: `app/src/main/java/com/gametodolist/domain/RolloverManager.kt:36-37,83-84`
- Modify: `app/src/main/java/com/gametodolist/ui/edit/TaskEditViewModel.kt:82-89`
- Modify: `app/src/main/java/com/gametodolist/ui/Formatters.kt:34`
- Test: `app/src/test/java/com/gametodolist/ModelsTest.kt`（新建）

**Interfaces:**
- Produces: `String?.toRecurrenceType(): RecurrenceType`（非法/null→NONE）、`String?.toWindowMode(): WindowMode`（→FULL_CYCLE）。后续任务统一使用。

- [ ] **Step 1: 写测试**

新建 `app/src/test/java/com/gametodolist/ModelsTest.kt`：

```kotlin
package com.gametodolist

import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.WindowMode
import com.gametodolist.domain.toRecurrenceType
import com.gametodolist.domain.toWindowMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    @Test
    fun `合法与非法循环类型解析`() {
        assertEquals(RecurrenceType.WEEKLY, "WEEKLY".toRecurrenceType())
        assertEquals(RecurrenceType.NONE, "garbage".toRecurrenceType())
        assertEquals(RecurrenceType.NONE, null.toRecurrenceType())
    }

    @Test
    fun `窗口模式解析`() {
        assertEquals(WindowMode.CUSTOM, "CUSTOM".toWindowMode())
        assertEquals(WindowMode.FULL_CYCLE, "x".toWindowMode())
        assertEquals(WindowMode.FULL_CYCLE, null.toWindowMode())
    }
}
```

- [ ] **Step 2: 实现扩展**

`Models.kt` 追加：

```kotlin
/** 数据库字符串 → 循环类型；非法/空值安全降级为 NONE */
fun String?.toRecurrenceType(): RecurrenceType =
    runCatching { RecurrenceType.valueOf(this.orEmpty()) }.getOrDefault(RecurrenceType.NONE)

/** 数据库字符串 → 显示时段模式；非法/空值安全降级为 FULL_CYCLE */
fun String?.toWindowMode(): WindowMode =
    runCatching { WindowMode.valueOf(this.orEmpty()) }.getOrDefault(WindowMode.FULL_CYCLE)
```

- [ ] **Step 3: 替换 5 处调用**

- `RecurrenceEngine.kt`：`typeOf` 改 `private fun typeOf(t: TaskTemplateEntity): RecurrenceType = t.recurrenceType.toRecurrenceType()`；`computeWindow` 里 `val mode = t.windowMode.toWindowMode()`。
- `RolloverManager.kt`：两处 `runCatching { RecurrenceType.valueOf(...) }...` 改 `.toRecurrenceType()`。
- `TaskEditViewModel.kt`：加载处 `recurrenceType = t.recurrenceType.toRecurrenceType()`、`windowMode = t.windowMode.toWindowMode()`。
- `Formatters.kt`：`recurrenceLabel` 里 `when (recurrenceType.toRecurrenceType())`（import 改 `com.gametodolist.domain.toRecurrenceType`）。

- [ ] **Step 4: 单测 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest --console=plain
git add app/src && git commit -m "refactor: 枚举字符串兜底解析收敛为 toRecurrenceType/toWindowMode 共享扩展"
```

### Task 4: RolloverManager 语义修复（NONE 即过期 + regenerate 统一 + Mutex）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/domain/RolloverManager.kt`（整体重写）
- Test: `app/src/test/java/com/gametodolist/RolloverManagerTest.kt`（追加用例）

**Interfaces:**
- Consumes: `toRecurrenceType()`（Task 3）、唯一索引（Task 2）、Fake DAO（Task 1 新语义）。
- Produces: `rolloverAll(now, zone)` / `rolloverTemplate(t, now, zone)` / `regenerateActiveInstance(t, now, zone)`（全部内部加锁，public 签名不变）；私有 `rolloverTemplateLocked`/`regenerateLocked`/`expireIfPastDue`。Task 6 将加 `reviveTemplate`。常量 `MAX_CATCH_UP_CYCLES = 500`。

新语义（写进文件头注释）：
- NONE 分支：无实例→建实例后**立即校验过期**；有实例→统一走 `expireIfPastDue`。
- `regenerateActiveInstance`：ACTIVE 实例若循环起点不再符合新规则→作废弃置；否则就地重算窗口（窗口改完可能已过期→作废）；最后总是跑一次结算补齐。
- `rolloverAll`/`rolloverTemplate`/`regenerateActiveInstance` 共用一把 `Mutex`（单进程防并发重复插入，与唯一索引双保险）。注意 Kotlin `Mutex` 不可重入：锁只加在 public 入口，私有 `*Locked` 方法内不再取锁。

- [ ] **Step 1: 追加失败测试**

`RolloverManagerTest.kt` 类内追加（Fake DAO 已具备所需方法）：

```kotlin
    @Test
    fun `不循环带持续天数 开始日期在过去 创建即作废`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 1))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)

        // 1-10 才首次结算：窗口 [1-1, 1-4) 早已过期，应立即作废而不是挂着 ACTIVE
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)

        assertEquals(1, instances.instances.size)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)
    }

    @Test
    fun `编辑把不循环任务开始日期改到过去 立即作废`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 9))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)

        // 用户把开始日期改到 1-01（窗口 [1-1,1-4) 已过期），保存触发 regenerate
        m.regenerateActiveInstance(
            template.copy(anchorDate = millisOf(LocalDate.of(2025, 1, 1))),
            millisOf(LocalDate.of(2025, 1, 10), 10), zone
        )

        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)
    }

    @Test
    fun `编辑改每周规则导致循环起点变化 旧实例作废并生成新循环`() = runTest {
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(weeklyTemplate()), instances)
        // 2025-01-09 周四：当前循环起点周三 1-08
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 9), 10), zone)
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), instances.instances[0].cycleStart)

        // 规则从「周一+周三」改成「周二+周四」：当前循环起点应变更为周二 1-07？不——
        // 周二 1-07 < 周四 1-09 回推命中周二 1-07？（0..6 回推：1-09 是周四，减2天=周二 1-07 命中）
        // 旧实例（起点周三）作废，新实例起点周二
        m.regenerateActiveInstance(
            weeklyTemplate().copy(weekdaysMask = 0b0001010), // 周二 + 周四
            millisOf(LocalDate.of(2025, 1, 9), 10), zone
        )

        val expired = instances.instances.filter { it.status == InstanceStatus.EXPIRED.name }
        val active = instances.instances.filter { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(1, expired.size)
        assertEquals(1, active.size)
        assertEquals(millisOf(LocalDate.of(2025, 1, 9)), active[0].cycleStart) // 周四命中
        assertEquals(millisOf(LocalDate.of(2025, 1, 14)), active[0].windowEnd) // 下一锚点周二
    }

    @Test
    fun `编辑已完成模板且当前循环已过期 结算补出新循环`() = runTest {
        val instances = FakeInstanceDao(
            mutableListOf(
                TaskInstanceEntity(
                    id = 1, templateId = 1,
                    cycleStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowStart = millisOf(LocalDate.of(2025, 1, 6)),
                    windowEnd = millisOf(LocalDate.of(2025, 1, 8)),
                    status = "COMPLETED", completedAt = millisOf(LocalDate.of(2025, 1, 7))
                )
            )
        )
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.regenerateActiveInstance(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 10), 10), zone)

        assertEquals(1, instances.instances.count { it.status == InstanceStatus.COMPLETED.name })
        val active = instances.instances.single { it.status == InstanceStatus.ACTIVE.name }
        assertEquals(millisOf(LocalDate.of(2025, 1, 8)), active.cycleStart)
    }

    @Test
    fun `regenerate 无实例时按规则建新实例`() = runTest {
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(weeklyTemplate()), instances)

        m.regenerateActiveInstance(weeklyTemplate(), millisOf(LocalDate.of(2025, 1, 9), 10), zone)

        assertEquals(1, instances.instances.size)
        assertEquals(InstanceStatus.ACTIVE.name, instances.instances[0].status)
    }
```

- [ ] **Step 2: 跑测试确认前两个失败**

预期：`创建即作废`、`编辑把不循环任务开始日期改到过去` FAIL（现行实现不校验/不结算）。

- [ ] **Step 3: 重写 RolloverManager**

完整替换 `app/src/main/java/com/gametodolist/domain/RolloverManager.kt`：

```kotlin
package com.gametodolist.domain

import com.gametodolist.data.dao.TaskInstanceDao
import com.gametodolist.data.dao.TaskTemplateDao
import com.gametodolist.data.entity.TaskInstanceEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 循环结算：把到期未完成的任务实例作废，并按规则生成下一个新实例。
 *
 * 统一入口语义：
 * - [rolloverAll]：App 启动、回前台、WorkManager 周期任务、数据变更管线调用。
 * - [regenerateActiveInstance]：模板规则变更（编辑保存）后调用——先就地修正当前实例
 *   （循环起点失效则作废弃置，否则重算窗口），再跑一遍结算补齐。
 * - 三个 public 入口共用一把互斥锁，配合实例表 (templateId, cycleStart) 唯一索引，
 *   防止并发结算重复插入。注意 Mutex 不可重入：锁只在 public 入口，私有 *Locked 方法不带锁。
 */
@Singleton
class RolloverManager @Inject constructor(
    private val templateDao: TaskTemplateDao,
    private val instanceDao: TaskInstanceDao
) {
    private val mutex = Mutex()

    suspend fun rolloverAll(
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock {
        templateDao.getActive().forEach { rolloverTemplateLocked(it, nowMillis, zone) }
    }

    suspend fun rolloverTemplate(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock { rolloverTemplateLocked(template, nowMillis, zone) }

    /** 模板规则变更后修正当前实例并结算 */
    suspend fun regenerateActiveInstance(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock { regenerateLocked(template, nowMillis, zone) }

    // ---------- 私有实现（调用方须已持锁） ----------

    private suspend fun rolloverTemplateLocked(
        template: TaskTemplateEntity,
        nowMillis: Long,
        zone: ZoneId
    ) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        if (template.recurrenceType.toRecurrenceType() == RecurrenceType.NONE) {
            // 不循环：保证存在一条一次性实例；设了持续天数的到期后自动作废，不再生成新实例
            val latest = instanceDao.getLatestForTemplate(template.id)
            if (latest == null) {
                val created = insertInstance(
                    template,
                    RecurrenceEngine.currentCycleStartDate(today, template, zone),
                    zone
                )
                // 开始日期在过去：创建即过期，立即作废（与循环分支的过期处理对齐）
                expireIfPastDue(created, nowMillis)
            } else {
                expireIfPastDue(latest, nowMillis)
            }
            return
        }

        var latest: TaskInstanceEntity = instanceDao.getLatestForTemplate(template.id)
            ?: insertInstance(template, RecurrenceEngine.currentCycleStartDate(today, template, zone), zone)

        // 窗口已过：作废（若未完成）并逐循环补齐，直到当前窗口有效
        var guard = 0
        while (latest.windowEnd <= nowMillis && guard++ < MAX_CATCH_UP_CYCLES) {
            if (latest.status == InstanceStatus.ACTIVE.name) {
                instanceDao.markExpired(latest.id)
            }
            val lastCycle = Instant.ofEpochMilli(latest.cycleStart).atZone(zone).toLocalDate()
            latest = insertInstance(template, RecurrenceEngine.nextCycleStartDate(lastCycle, template), zone)
        }
    }

    private suspend fun regenerateLocked(
        template: TaskTemplateEntity,
        nowMillis: Long,
        zone: ZoneId
    ) {
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val latest = instanceDao.getLatestForTemplate(template.id)
        if (latest != null && latest.status == InstanceStatus.ACTIVE.name) {
            val expected = RecurrenceEngine.currentCycleStartDate(today, template, zone)
            val latestCycle = Instant.ofEpochMilli(latest.cycleStart).atZone(zone).toLocalDate()
            if (template.recurrenceType.toRecurrenceType() != RecurrenceType.NONE && latestCycle != expected) {
                // 规则起点变了（如每周一三 → 周二四）：旧实例作废弃置，
                // 并立即按新规则建当前循环实例——rollover 只按窗口过期推进，不会主动建
                instanceDao.markExpired(latest.id)
                expireIfPastDue(insertInstance(template, expected, zone), nowMillis)
            } else {
                // 同循环（或 NONE 换开始日期/天数）：就地重算窗口
                val window = RecurrenceEngine.computeWindow(expected, template, zone)
                instanceDao.update(
                    latest.copy(
                        cycleStart = expected.atStartOfDay(zone).toInstant().toEpochMilli(),
                        windowStart = window.startMillis,
                        windowEnd = window.endMillis
                    )
                )
                // 窗口改完可能整体过期（如开始日期改到过去）：立即作废
                instanceDao.getById(latest.id)?.let { expireIfPastDue(it, nowMillis) }
            }
        }
        rolloverTemplateLocked(template, nowMillis, zone)
    }

    /** 窗口已结束的 ACTIVE 实例立即作废 */
    private suspend fun expireIfPastDue(instance: TaskInstanceEntity, nowMillis: Long) {
        if (instance.status == InstanceStatus.ACTIVE.name &&
            instance.windowEnd != Long.MAX_VALUE && instance.windowEnd <= nowMillis
        ) {
            instanceDao.markExpired(instance.id)
        }
    }

    private suspend fun insertInstance(
        template: TaskTemplateEntity,
        cycle: LocalDate,
        zone: ZoneId
    ): TaskInstanceEntity {
        val window = RecurrenceEngine.computeWindow(cycle, template, zone)
        val entity = TaskInstanceEntity(
            templateId = template.id,
            cycleStart = cycle.atStartOfDay(zone).toInstant().toEpochMilli(),
            windowStart = window.startMillis,
            windowEnd = window.endMillis
        )
        val id = instanceDao.insert(entity)
        return entity.copy(id = id)
    }

    companion object {
        /** 单次结算最多补齐的循环数（防长期未打开 App 时一次插入过多） */
        const val MAX_CATCH_UP_CYCLES = 500
    }
}
```

- [ ] **Step 4: 跑全部单测确认通过**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest --console=plain
```
预期：全绿（含 5 个新用例）。第三个用例的断言数值若与实现不符，以引擎真实语义修正断言（2025-01-09 周四，mask=周二+周四：回推 0 天即命中周四本身，cycleStart=1-09，windowEnd=下一命中周二 1-14）。

- [ ] **Step 5: Commit**

```bash
git add app/src && git commit -m "fix: NONE 任务创建/编辑即过期立即作废；regenerate 语义统一（起点失效作废+窗口重算+结算）；rollover 加互斥锁"
```

### Task 5: RecurrenceEngine 支持 WEEKLY/MONTHLY 开始日期（anchor）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/domain/RecurrenceEngine.kt`
- Test: `app/src/test/java/com/gametodolist/RecurrenceEngineTest.kt`（追加用例）

**Interfaces:**
- Consumes: 无新外部依赖。
- Produces: `currentCycleStartDate(date, t, zone)` 新语义：`anchorDate > 0` 时 WEEKLY/MONTHLY 返回值不早于"首个循环"（首个循环可能在未来，调用方无需特殊处理——窗口在未来即显示"X月X日开始"，anchor 前日期自然不命中窗口）。`nextCycleStartDate` 不变。`anchorDate <= 0` 时行为与现状完全一致。

语义定稿（写入引擎 KDoc）：
- WEEKLY：`first =` 从 anchor 起（含）第一个命中 mask 的日期；`current = max(正常回推结果, first)`。
- MONTHLY：`first =` 不早于 anchor 的第一个 `clampMonthDay(monthDay)`（同月或次月）；`current = max(正常结果, first)`。
- 日号始终由 `monthDay` 决定，anchor 不参与日号计算。

- [ ] **Step 1: 追加失败测试**

`RecurrenceEngineTest.kt` 追加：

```kotlin
    // ---------- WEEKLY/MONTHLY 起始日期 ----------

    @Test
    fun `weekly 起始日期在未来 首个循环不早于起始日期`() {
        // mask=周一，anchor=2025-01-08 周三 → 首个循环 = 下周一 1-13
        val anchor = LocalDate.of(2025, 1, 8)
        val t = template(
            "WEEKLY", weekdaysMask = 0b0000001,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        // 查询 anchor 当天（周三）：正常回推得 1-06，但被钳到首个循环 1-13
        assertEquals(LocalDate.of(2025, 1, 13), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
        // 查询 1-20：正常回推 1-20（周一），≥ first，不受影响
        assertEquals(LocalDate.of(2025, 1, 20), RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 20), t, zone))
    }

    @Test
    fun `weekly 起始日期在过去 行为与未设置一致`() {
        val anchor = LocalDate.of(2024, 12, 1)
        val t = template(
            "WEEKLY", weekdaysMask = 0b0000101,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(monday, RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 1, 7), t, zone))
    }

    @Test
    fun `monthly 起始日期约束首个循环`() {
        // monthDay=15，anchor=2025-03-20 → 3-15 已过，首个循环 = 4-15
        val anchor = LocalDate.of(2025, 3, 20)
        val t = template(
            "MONTHLY", monthDay = 15,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(LocalDate.of(2025, 4, 15), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
        // 5 月后正常
        assertEquals(LocalDate.of(2025, 5, 15), RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 5, 20), t, zone))
    }

    @Test
    fun `monthly 起始日期与月末钳制`() {
        // monthDay=31，anchor=2025-02-10（非闰年）→ 首个循环 = 2-28
        val anchor = LocalDate.of(2025, 2, 10)
        val t = template(
            "MONTHLY", monthDay = 31,
            anchorDate = anchor.atStartOfDay(zone).toInstant().toEpochMilli()
        )
        assertEquals(LocalDate.of(2025, 2, 28), RecurrenceEngine.currentCycleStartDate(anchor, t, zone))
    }

    @Test
    fun `DST 时区窗口换算日期正确`() {
        val ny = ZoneId.of("America/New_York")
        // 2025-03-09 美国进入夏令时；窗口跨 3-08 → 3-10 两天
        val t = template("NONE", windowMode = "CUSTOM", customWindowDays = 2,
            anchorDate = LocalDate.of(2025, 3, 8).atStartOfDay(ny).toInstant().toEpochMilli())
        val cycle = RecurrenceEngine.currentCycleStartDate(LocalDate.of(2025, 3, 8), t, ny)
        val window = RecurrenceEngine.computeWindow(cycle, t, ny)
        val startDate = java.time.Instant.ofEpochMilli(window.startMillis).atZone(ny).toLocalDate()
        val endDate = java.time.Instant.ofEpochMilli(window.endMillis).atZone(ny).toLocalDate()
        assertEquals(LocalDate.of(2025, 3, 8), startDate)
        assertEquals(LocalDate.of(2025, 3, 10), endDate)
    }
```

注意 `template()` 辅助函数已有 `anchorDate`/`windowMode`/`customWindowDays` 参数，直接可用。

- [ ] **Step 2: 跑测试确认前三个失败**

预期：WEEKLY/MONTHLY anchor 用例 FAIL（现行分支忽略 anchorDate）。

- [ ] **Step 3: 实现引擎修改**

`RecurrenceEngine.kt`：

① 文件头 KDoc 的循环语义段加两行：

```
 * - WEEKLY/MONTHLY 设置了 anchorDate 时：首个循环不早于 anchor（之前无循环）；
 *   日号/星期规则不变，anchor <= 0 时行为与未设置一致
```

② 新增两个私有函数（放在 `clampMonthDay` 之后）：

```kotlin
    /** WEEKLY：不早于 anchor 的第一个循环起点（anchor<=0 调用方不会走到这） */
    private fun firstWeeklyCycleOnOrAfter(anchor: LocalDate, t: TaskTemplateEntity): LocalDate =
        (0..6).asSequence()
            .map { anchor.plusDays(it.toLong()) }
            .first { isWeekdaySelected(it, t.weekdaysMask) }

    /** MONTHLY：不早于 anchor 的第一个循环起点（日号按 monthDay，短月钳制） */
    private fun firstMonthlyCycleOnOrAfter(anchor: LocalDate, t: TaskTemplateEntity): LocalDate {
        val sameMonth = clampMonthDay(t.monthDay, anchor)
        return if (sameMonth >= anchor) sameMonth
        else clampMonthDay(t.monthDay, anchor.plusMonths(1))
    }
```

③ `currentCycleStartDate` 的 WEEKLY/MONTHLY 分支改：

```kotlin
            RecurrenceType.WEEKLY -> {
                require(t.weekdaysMask != 0) { "WEEKLY 规则 weekdaysMask 不能为 0" }
                val current = (0..6).asSequence()
                    .map { date.minusDays(it.toLong()) }
                    .first { isWeekdaySelected(it, t.weekdaysMask) }
                if (t.anchorDate > 0) maxOf(current, firstWeeklyCycleOnOrAfter(anchorDateOf(t, zone), t))
                else current
            }
            RecurrenceType.MONTHLY -> {
                val thisMonth = clampMonthDay(t.monthDay, date)
                val current = if (thisMonth <= date) thisMonth
                else clampMonthDay(t.monthDay, date.minusMonths(1))
                if (t.anchorDate > 0) maxOf(current, firstMonthlyCycleOnOrAfter(anchorDateOf(t, zone), t))
                else current
            }
```

- [ ] **Step 4: 跑全部单测确认通过 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest --console=plain
git add app/src && git commit -m "feat: WEEKLY/MONTHLY 支持起始日期（首个循环不早于 anchor），补 DST 窗口测试"
```

### Task 6: 已作废任务复活 + HomeData.expiredGroups（数据层）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/domain/RolloverManager.kt`（加 `reviveTemplate`）
- Modify: `app/src/main/java/com/gametodolist/data/TodoRepository.kt`（加 `reviveTemplate`、HomeData 加字段、observeHomeData 加分区）
- Test: `app/src/test/java/com/gametodolist/RolloverManagerTest.kt`（追加 revive 用例）

**Interfaces:**
- Consumes: Task 4 的 `regenerateLocked`/`expireIfPastDue`。
- Produces:
  - `RolloverManager.reviveTemplate(template: TaskTemplateEntity, nowMillis: Long, zone: ZoneId)`——模板须已先把 anchorDate 更新为今天；把最新 EXPIRED 实例改回 ACTIVE 并重算窗口。
  - `TodoRepository.reviveTemplate(templateId: Long)`——组合上面两步（只接受 NONE 模板）。Repository 新注入 `RolloverManager`（无循环依赖：RolloverManager 只依赖 DAO）。
  - `HomeData(activeGroups, completedGroups, expiredGroups)`——`expiredGroups` 默认 `emptyList()`，组件端（TodoWidget）只读前两区不受影响。
  - 主页 UI（Task 11）消费 `expiredGroups` 并调用 `reviveTemplate`。

- [ ] **Step 1: 追加失败测试**

`RolloverManagerTest.kt` 追加：

```kotlin
    @Test
    fun `复活已作废的不循环任务 窗口从今天重算`() = runTest {
        val template = TaskTemplateEntity(
            id = 1, categoryId = null, title = "一次性", recurrenceType = "NONE",
            windowMode = "CUSTOM", customWindowDays = 3,
            anchorDate = millisOf(LocalDate.of(2025, 1, 1))
        )
        val instances = FakeInstanceDao()
        val m = manager(mutableListOf(template), instances)
        m.rolloverAll(millisOf(LocalDate.of(2025, 1, 10), 10), zone)
        assertEquals(InstanceStatus.EXPIRED.name, instances.instances[0].status)

        // 用户点「重新开启」：ViewModel 已把 anchor 更新为今天（1-10）
        m.reviveTemplate(
            template.copy(anchorDate = millisOf(LocalDate.of(2025, 1, 10))),
            millisOf(LocalDate.of(2025, 1, 10), 12), zone
        )

        val revived = instances.instances[0]
        assertEquals(InstanceStatus.ACTIVE.name, revived.status)
        assertEquals(millisOf(LocalDate.of(2025, 1, 10)), revived.windowStart)
        assertEquals(millisOf(LocalDate.of(2025, 1, 13)), revived.windowEnd)
    }
```

- [ ] **Step 2: 跑测试确认失败**（`reviveTemplate` 未定义，编译失败）

- [ ] **Step 3: 实现**

`RolloverManager.kt` 在 `regenerateActiveInstance` 后加：

```kotlin
    /** 「重新开启」已作废任务：最新 EXPIRED 实例改回 ACTIVE 并按当前规则（新 anchor）重算窗口 */
    suspend fun reviveTemplate(
        template: TaskTemplateEntity,
        nowMillis: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ) = mutex.withLock {
        val latest = instanceDao.getLatestForTemplate(template.id) ?: return@withLock
        if (latest.status != InstanceStatus.EXPIRED.name) return@withLock
        instanceDao.markActive(latest.id)
        regenerateLocked(template, nowMillis, zone)
    }
```

`TodoRepository.kt`：
① 构造注入加 `private val rolloverManager: RolloverManager`（import `com.gametodolist.domain.RolloverManager`、`com.gametodolist.domain.toRecurrenceType`、`com.gametodolist.domain.RecurrenceType`、`java.time.LocalDate`、`java.time.ZoneId`）。
② 加方法：

```kotlin
    /** 「重新开启」已作废的一次性任务：开始日期重置为今天，实例恢复 ACTIVE 并重算窗口 */
    suspend fun reviveTemplate(templateId: Long) {
        val template = templateDao.getById(templateId) ?: return
        // 只有 NONE 任务会长期停留在已作废（循环任务结算时自动续期），其他类型忽略
        if (template.recurrenceType.toRecurrenceType() != RecurrenceType.NONE) return
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val updated = template.copy(anchorDate = today)
        templateDao.update(updated)
        rolloverManager.reviveTemplate(updated)
    }
```

③ `HomeData` 加分区：

```kotlin
data class HomeData(
    val activeGroups: List<CategoryGroup>,
    val completedGroups: List<CategoryGroup>,
    /** 已作废（最新实例 EXPIRED，基本只有一次性+自定义天数任务会落这里） */
    val expiredGroups: List<CategoryGroup> = emptyList()
)
```

④ `observeHomeData` 的 `HomeData(...)` 调用加 `expiredGroups = groupsFor(InstanceStatus.EXPIRED.name)`。

- [ ] **Step 4: 单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "feat: 已作废一次性任务支持重新开启（数据层），主页数据增加已作废分区"
```

---

## 批次 2：提醒链路与统一变更管线

### Task 7: ReminderScheduler 权限降级 + ReminderReceiver 状态校验

**Files:**
- Modify: `app/src/main/java/com/gametodolist/notify/ReminderScheduler.kt`
- Modify: `app/src/main/java/com/gametodolist/notify/ReminderReceiver.kt`（重写）

**Interfaces:**
- Produces: `ReminderScheduler.canScheduleExactReminders(): Boolean`（Task 13 设置页用）；`ReminderReceiver` 只在实例仍 ACTIVE 时弹通知。
- Consumes: `WidgetEntryPoint` 同款 EntryPoint 模式（本任务新建 `notify/ReminderEntryPoint.kt`）。

- [ ] **Step 1: 修改 ReminderScheduler**

- 删 `@SuppressLint("MissingPermission")` 与 import。
- `schedule` 改：

```kotlin
    fun schedule(instanceId: Long, title: String, triggerAtMillis: Long) {
        if (triggerAtMillis <= System.currentTimeMillis()) return
        runCatching {
            if (canScheduleExactReminders()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(instanceId, title)
                )
            } else {
                // Android 14+ 未授权精确闹钟：降级非精确（可能延迟，但不崩溃）
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent(instanceId, title)
                )
            }
        }.onFailure { Log.w(TAG, "schedule failed for instance $instanceId", it) }
    }

    /** 是否可精确提醒（API 31 以下或已授权精确闹钟） */
    fun canScheduleExactReminders(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
```

- import 加 `android.os.Build`、`android.util.Log`；类内加 `private companion object { const val TAG = "ReminderScheduler" }`（若已有 companion 则合并）。

- [ ] **Step 2: 新建 ReminderEntryPoint 并重写 ReminderReceiver**

新建 `app/src/main/java/com/gametodolist/notify/ReminderEntryPoint.kt`：

```kotlin
package com.gametodolist.notify

import android.content.Context
import com.gametodolist.data.dao.TaskInstanceDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderEntryPoint {
    fun instanceDao(): TaskInstanceDao
    fun reminderScheduler(): ReminderScheduler

    companion object {
        fun get(context: Context): ReminderEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, ReminderEntryPoint::class.java)
    }
}
```

`ReminderReceiver.kt` 的 `onReceive` 改为先校验实例状态（goAsync 异步查库），其余通知构建逻辑不变，抽为 `showNotification` 私有方法：

```kotlin
    override fun onReceive(context: Context, intent: Intent) {
        val instanceId = intent.getLongExtra(EXTRA_INSTANCE_ID, -1L)
        if (instanceId == -1L) return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val entryPoint = ReminderEntryPoint.get(context)
                val instance = entryPoint.instanceDao().getById(instanceId)
                if (instance == null || instance.status != InstanceStatus.ACTIVE.name) {
                    // 实例已完成/作废/删除：清理残留闹钟，不弹通知
                    entryPoint.reminderScheduler().cancel(instanceId)
                    return@launch
                }
                showNotification(context, instanceId, title)
            } finally {
                pendingResult.finish()
            }
        }
    }
```

import 加 `com.gametodolist.domain.InstanceStatus`、`kotlinx.coroutines.*`。原通知权限检查与构建代码整体移入 `private fun showNotification(context: Context, instanceId: Long, title: String)`。

- [ ] **Step 3: 构建 + 单测 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "fix: 精确闹钟权限缺失时降级非精确不崩溃；提醒触发前校验实例仍 ACTIVE"
```

### Task 8: DataSyncManager 统一管线 + 全部调用点收口

**Files:**
- Create: `app/src/main/java/com/gametodolist/sync/DataSyncManager.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/WidgetEntryPoint.kt`（加 `dataSync()`）
- Modify: `app/src/main/java/com/gametodolist/notify/RolloverWorker.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/CompleteTaskAction.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/UncompleteTaskAction.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/RefreshWidgetAction.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/edit/TaskEditViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/category/CategoryViewModel.kt`

**Interfaces:**
- Produces: `DataSyncManager.settleAndRefresh()`——后续所有写路径的唯一写后入口（Task 9-14 的 ViewModel 方法都走它）。**本任务起，任何代码不再直接组合调用 rollover/reschedule/WidgetUpdater（设置页外观场景除外）。**
- Consumes: Task 7 的 scheduler（内部已 runCatching）、Task 4 的 rollover（幂等）。

- [ ] **Step 1: 新建 DataSyncManager**

```kotlin
package com.gametodolist.sync

import android.content.Context
import android.util.Log
import com.gametodolist.domain.RolloverManager
import com.gametodolist.notify.ReminderScheduler
import com.gametodolist.widget.WidgetUpdater
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据变更统一管线：任何任务/模板/分类写操作后调用 [settleAndRefresh]。
 * 结算（作废过期+补新循环）→ 全量重排提醒（只排 ACTIVE，刚完成/删除的自动跳过）
 * → 刷新所有桌面组件。三步全量幂等；单步失败记日志，互不影响。
 *
 * 例外：设置页外观改动不涉及任务数据，仍只调 WidgetUpdater.refresh。
 */
@Singleton
class DataSyncManager @Inject constructor(
    private val rolloverManager: RolloverManager,
    private val reminderScheduler: ReminderScheduler,
    @ApplicationContext private val context: Context
) {
    suspend fun settleAndRefresh() {
        runCatching { rolloverManager.rolloverAll() }
            .onFailure { Log.w(TAG, "rollover failed", it) }
        runCatching { reminderScheduler.rescheduleAll() }
            .onFailure { Log.w(TAG, "reschedule failed", it) }
        WidgetUpdater.refresh(context)
    }

    private companion object {
        const val TAG = "DataSyncManager"
    }
}
```

- [ ] **Step 2: WidgetEntryPoint 加入口**

```kotlin
    fun dataSync(): DataSyncManager
```

（import `com.gametodolist.sync.DataSyncManager`。）

- [ ] **Step 3: 改造 RolloverWorker**

构造注入改为 `private val dataSync: DataSyncManager`（去掉 RolloverManager/ReminderScheduler/WidgetUpdater import），`doWork`：

```kotlin
    override suspend fun doWork(): Result = try {
        dataSync.settleAndRefresh()
        Result.success()
    } catch (e: Exception) {
        // 只对有界次数重试，避免权限/数据类永久错误无限重试
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
```

- [ ] **Step 4: 改造三个组件 Action**

`CompleteTaskAction.onAction` 方法体改：

```kotlin
        val instanceId = parameters[INSTANCE_ID_KEY] ?: return
        val entryPoint = WidgetEntryPoint.get(context)
        entryPoint.repository().completeInstance(instanceId)
        entryPoint.dataSync().settleAndRefresh()
```

（删 `rolloverManager()`/`reminderScheduler()` 调用与 `TodoWidget().updateAll`、updateAll import。）

`UncompleteTaskAction.onAction` 方法体改：

```kotlin
        val instanceId = parameters[INSTANCE_ID_KEY] ?: return
        val entryPoint = WidgetEntryPoint.get(context)
        entryPoint.repository().uncompleteInstance(instanceId)
        entryPoint.dataSync().settleAndRefresh()
```

`RefreshWidgetAction.onAction` 方法体改：

```kotlin
        WidgetEntryPoint.get(context).dataSync().settleAndRefresh()
```

- [ ] **Step 5: 改造四个 ViewModel**

`HomeViewModel`：构造改为 `(private val repository: TodoRepository, private val dataSync: DataSyncManager)`（去掉 RolloverManager/ReminderScheduler/WidgetUpdater/Context import 与参数）。加错误状态：

```kotlin
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun consumeError() { _errorMessage.value = null }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { _errorMessage.value = "操作失败，请重试" }
        }
    }
```

四个方法改：

```kotlin
    init {
        launchWrite { dataSync.settleAndRefresh() }
    }

    fun complete(item: TemplateWithInstance) {
        launchWrite {
            item.activeInstance?.let { repository.completeInstance(it.id) }
            dataSync.settleAndRefresh()
        }
    }

    fun uncomplete(item: TemplateWithInstance) {
        launchWrite {
            item.activeInstance?.let { repository.uncompleteInstance(it.id) }
            dataSync.settleAndRefresh()
        }
    }

    fun delete(item: TemplateWithInstance) {
        launchWrite {
            repository.deleteTemplate(item.template.id)
            dataSync.settleAndRefresh()
        }
    }
```

（import 加 `kotlinx.coroutines.flow.MutableStateFlow/asStateFlow`，删 `@ApplicationContext`/Context。）

`CalendarViewModel`：构造去掉 RolloverManager，加 `private val dataSync: DataSyncManager`；`init { viewModelScope.launch { dataSync.settleAndRefresh() } }`。

`TaskEditViewModel`：构造去掉 ReminderScheduler 与 `@ApplicationContext context`（保留 `repository`、`rolloverManager`——regenerate 仍需要），加 `private val dataSync: DataSyncManager`。`save()` 协程内尾部改为（Task 9 会整体改 save，本步只换收尾三行）：

```kotlin
            val newId = repository.saveTemplate(entity)
            if (s.id != 0L) {
                rolloverManager.regenerateActiveInstance(entity.copy(id = s.id))
            }
            dataSync.settleAndRefresh()
            _uiState.update { it.copy(saved = true) }
```

（新建模板不再单独 rollover——settleAndRefresh 的 rolloverAll 会为其建实例。）

`CategoryViewModel`：构造改为 `(private val repository: TodoRepository, private val dataSync: DataSyncManager)`；`save`/`delete` 协程内把 `WidgetUpdater.refresh(context)` 换成 `dataSync.settleAndRefresh()`。

- [ ] **Step 6: 全量单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "refactor: DataSyncManager 统一变更管线，所有写路径收口（结算+重排提醒+刷新组件）"
```

---

## 批次 3：UI 衔接与便利性

### Task 9: 编辑页逻辑修复（categoryId 校验 + anchorEnabled + 防抖 + 错误提示）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/edit/TaskEditViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/edit/TaskEditScreen.kt`（WEEKLY/MONTHLY 分支、数字框、保存按钮、Snackbar）

**Interfaces:**
- Consumes: `DataSyncManager`（Task 8）、引擎 WEEKLY/MONTHLY anchor（Task 5）。
- Produces: `TaskEditUiState` 新字段 `anchorEnabled: Boolean`、`saving: Boolean`、`errorMessage: String?`（删除 `loaded`）；`TaskEditViewModel.isDirty(): Boolean`、`consumeError()`、`delete()`（Task 10 用）。

- [ ] **Step 1: 重写 TaskEditViewModel**

完整替换（要点：`loaded` 删除；`anchorEnabled` 开关；快照脏检查；保存校验分类有效性；WEEKLY/MONTHLY 按开关存 anchor；saving 防抖；异常吐 errorMessage）：

```kotlin
package com.gametodolist.ui.edit

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gametodolist.data.entity.CategoryEntity
import com.gametodolist.data.entity.TaskTemplateEntity
import com.gametodolist.data.TodoRepository
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.domain.RolloverManager
import com.gametodolist.domain.WindowMode
import com.gametodolist.domain.toRecurrenceType
import com.gametodolist.domain.toWindowMode
import com.gametodolist.sync.DataSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TaskEditUiState(
    val id: Long = 0,
    val title: String = "",
    val note: String = "",
    val categoryId: Long? = null,
    val recurrenceType: RecurrenceType = RecurrenceType.NONE,
    val intervalDays: Int = 1,
    val weekdaysMask: Int = 0,
    val monthDay: Int = 1,
    /** WEEKLY/MONTHLY：是否指定开始日期（未指定 = 立即开始，存 anchorDate=0） */
    val anchorEnabled: Boolean = false,
    /** 起始日期（本地零点毫秒）：NONE/INTERVAL_DAYS 始终使用；WEEKLY/MONTHLY 仅 anchorEnabled 时生效 */
    val anchorDate: Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    val windowMode: WindowMode = WindowMode.FULL_CYCLE,
    /** CUSTOM 显示时段：从循环起点起显示的天数（1 = 显示 1 天，最小 1） */
    val customWindowDays: Int = 1,
    val reminderEnabled: Boolean = false,
    val reminderMinutesBefore: Int = 60,
    val saving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: TodoRepository,
    private val rolloverManager: RolloverManager,
    private val dataSync: DataSyncManager
) : ViewModel() {

    private val templateId: Long = savedStateHandle.get<Long>("templateId") ?: -1L
    /** 从日历「+」进入时带入的默认开始日期（本地零点毫秒，<=0 表示未指定） */
    private val presetAnchorDate: Long = savedStateHandle.get<Long>("anchorDate") ?: -1L

    private val _uiState = MutableStateFlow(
        TaskEditUiState().let { s ->
            if (presetAnchorDate > 0) s.copy(anchorDate = presetAnchorDate, anchorEnabled = true) else s
        }
    )
    val uiState: StateFlow<TaskEditUiState> = _uiState.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 加载完成时的快照，用于「放弃修改」脏检查 */
    private var snapshot: TaskEditUiState? = null

    init {
        if (templateId > 0) {
            viewModelScope.launch {
                repository.getTemplate(templateId)?.let { t ->
                    _uiState.update {
                        it.copy(
                            id = t.id,
                            title = t.title,
                            note = t.note,
                            categoryId = t.categoryId,
                            recurrenceType = t.recurrenceType.toRecurrenceType(),
                            intervalDays = t.intervalDays,
                            weekdaysMask = t.weekdaysMask,
                            monthDay = t.monthDay,
                            anchorEnabled = t.anchorDate > 0,
                            anchorDate = if (t.anchorDate > 0) t.anchorDate else it.anchorDate,
                            windowMode = t.windowMode.toWindowMode(),
                            customWindowDays = t.customWindowDays.coerceAtLeast(1),
                            reminderEnabled = t.reminderEnabled,
                            reminderMinutesBefore = t.reminderMinutesBefore
                        )
                    }
                    snapshot = _uiState.value
                }
            }
        } else {
            snapshot = _uiState.value
        }
    }

    fun update(transform: (TaskEditUiState) -> TaskEditUiState) = _uiState.update(transform)

    /** 与加载快照相比是否有未保存修改（忽略 saving/saved/errorMessage 等过程字段） */
    fun isDirty(): Boolean {
        val base = snapshot ?: return false
        fun TaskEditUiState.normalized() = copy(saving = false, saved = false, errorMessage = null)
        return _uiState.value.normalized() != base.normalized()
    }

    fun consumeError() = _uiState.update { it.copy(errorMessage = null) }

    fun save() {
        val s = _uiState.value
        if (s.title.isBlank() || s.saved || s.saving) return
        if (s.recurrenceType == RecurrenceType.WEEKLY && s.weekdaysMask == 0) return

        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                // 分类可能已在管理页被删除：失效的 categoryId 归入未分类，避免任务从主页「隐身」
                val validCategoryId = s.categoryId
                    ?.takeIf { id -> categories.value.any { it.id == id } }
                val entity = TaskTemplateEntity(
                    id = s.id,
                    categoryId = validCategoryId,
                    title = s.title.trim(),
                    note = s.note.trim(),
                    recurrenceType = s.recurrenceType.name,
                    intervalDays = s.intervalDays.coerceAtLeast(1),
                    weekdaysMask = s.weekdaysMask,
                    monthDay = s.monthDay.coerceIn(1, 31),
                    anchorDate = when (s.recurrenceType) {
                        RecurrenceType.NONE, RecurrenceType.INTERVAL_DAYS -> s.anchorDate
                        else -> if (s.anchorEnabled) s.anchorDate else 0L
                    },
                    windowMode = s.windowMode.name,
                    customWindowDays = s.customWindowDays.coerceAtLeast(1),
                    reminderEnabled = s.reminderEnabled,
                    reminderMinutesBefore = s.reminderMinutesBefore
                )
                val newId = repository.saveTemplate(entity)
                if (s.id != 0L) {
                    rolloverManager.regenerateActiveInstance(entity.copy(id = s.id))
                }
                dataSync.settleAndRefresh()
                _uiState.update { it.copy(saved = true, saving = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, errorMessage = "保存失败，请重试") }
            }
        }
    }

    /** 编辑模式下从编辑页直接删除（带确认，由 UI 弹窗后调用） */
    fun delete() {
        val s = _uiState.value
        if (s.id == 0L || s.saving || s.saved) return
        _uiState.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                repository.deleteTemplate(s.id)
                dataSync.settleAndRefresh()
                _uiState.update { it.copy(saved = true, saving = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(saving = false, errorMessage = "删除失败，请重试") }
            }
        }
    }
}
```

- [ ] **Step 2: TaskEditScreen 配套修改**

① Scaffold 加 Snackbar：

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { ... }
    )
```

（import `androidx.compose.material3.SnackbarHost/SnackbarHostState`。）

② WEEKLY 分支改 Column（chips + 开始日期开关），MONTHLY 分支同样：

```kotlin
                RecurrenceType.WEEKLY -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WEEKDAY_LABELS.forEachIndexed { index, label ->
                            val bit = 1 shl index
                            FilterChip(
                                selected = state.weekdaysMask and bit != 0,
                                onClick = {
                                    viewModel.update { it.copy(weekdaysMask = it.weekdaysMask xor bit) }
                                },
                                label = { Text("周$label") }
                            )
                        }
                    }
                    AnchorDateSection(state, viewModel)
                }
                RecurrenceType.MONTHLY -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    NumberField(
                        label = "每月第几日（短月自动取月末）",
                        value = state.monthDay,
                        onValueChange = { v -> viewModel.update { it.copy(monthDay = v) } }
                    )
                    AnchorDateSection(state, viewModel)
                }
```

③ 新增私有 composable（文件底部）：

```kotlin
/** WEEKLY/MONTHLY 的开始日期开关：未指定 = 立即开始（anchorDate 存 0，保持旧行为） */
@Composable
private fun AnchorDateSection(state: TaskEditUiState, viewModel: TaskEditViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("指定开始日期", style = MaterialTheme.typography.titleSmall)
                Text(
                    "首个循环不早于该日期；不指定则立即开始",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.anchorEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(anchorEnabled = v) } }
            )
        }
        if (state.anchorEnabled) {
            DateField(
                label = "开始日期",
                dateMillis = state.anchorDate,
                onPicked = { d -> viewModel.update { it.copy(anchorDate = d) } }
            )
        }
    }
}
```

④ NumberField 过滤负号并就地钳制（值回显即真实保存值）：

```kotlin
@Composable
private fun NumberField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { s ->
            s.filter { it.isDigit() }.toIntOrNull()?.let { onValueChange(it.coerceAtLeast(1)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}
```

⑤ 保存按钮防抖：`enabled = canSave && !state.saving`，按钮文案 `Text(if (state.saving) "保存中…" else "保存")`。

- [ ] **Step 3: 全量单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "fix: 编辑页保存校验失效分类归入未分类；每周/每月支持指定开始日期；保存防抖与失败提示"
```

### Task 10: 编辑页交互（放弃修改保护 + 删除入口）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/edit/TaskEditScreen.kt`

**Interfaces:**
- Consumes: `TaskEditViewModel.isDirty()`/`delete()`（Task 9）。

- [ ] **Step 1: 实现返回保护与删除入口**

① `TaskEditScreen` 顶部加状态与 BackHandler（import `androidx.activity.compose.BackHandler`、`androidx.compose.material3.AlertDialog`、`androidx.compose.material.icons.filled.Delete`）：

```kotlin
    var showDiscardDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (viewModel.isDirty()) showDiscardDialog = true else onBack()
    }

    BackHandler { requestBack() }
```

② TopAppBar 的返回箭头 `onClick = { requestBack() }`；编辑模式（`state.id != 0L`）加删除动作：

```kotlin
            TopAppBar(
                title = { Text(if (state.id == 0L) "新增清单" else "编辑清单") },
                navigationIcon = {
                    IconButton(onClick = { requestBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.id != 0L) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
```

③ 两个确认弹窗（Scaffold 之后）：

```kotlin
    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("放弃修改？") },
            text = { Text("当前修改尚未保存，离开后不会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardDialog = false
                    onBack()
                }) { Text("放弃") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除清单？") },
            text = { Text("「${state.title}」及其历史记录、提醒将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("取消") }
            }
        )
    }
```

注意：`viewModel.delete()` 成功后 `state.saved=true`，已有 `LaunchedEffect(state.saved)` 会自动 `onBack()`。

- [ ] **Step 2: 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle assembleDebug --console=plain
git add app/src && git commit -m "feat: 编辑页返回放弃修改保护（脏检查+确认弹窗），编辑模式加删除入口"
```

### Task 11: 主页（删除确认 + 折叠状态提升 + 加载态 + 已作废区 + 复活 + Snackbar）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/home/HomeViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/home/HomeScreen.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/theme/Color.kt`（加 `CompletedGreen`、`UncategorizedGray`）

**Interfaces:**
- Consumes: `HomeData.expiredGroups`、`TodoRepository.reviveTemplate`（Task 6）、`DataSyncManager`（Task 8）。
- Produces: `HomeViewModel.collapsed: StateFlow<Set<String>>`、`toggleCollapsed(key: String)`、`revive(item)`、`errorMessage/consumeError`（Task 8 已建）。

- [ ] **Step 1: Color.kt 加常量**

```kotlin
val CompletedGreen = Color(0xFF7FB69E)   // 完成态/左滑完成背景
val UncategorizedGray = Color(0xFF9E9E9E) // 未分类默认色
```

- [ ] **Step 2: HomeViewModel 加折叠状态与复活**

`HomeViewModel`（Task 8 版基础上）改：

- `homeData` 类型改 `StateFlow<HomeData?>`，stateIn 初值 `null`（区分加载中与真空态）。
- 加：

```kotlin
    /** 分类分区折叠状态（key = "active:分类id" 等形式），提升到 VM 避免切 tab 丢失 */
    private val _collapsed = MutableStateFlow<Set<String>>(emptySet())
    val collapsed: StateFlow<Set<String>> = _collapsed.asStateFlow()

    fun toggleCollapsed(key: String) {
        _collapsed.update { if (key in it) it - key else it + key }
    }

    /** 「重新开启」已作废的一次性任务 */
    fun revive(item: TemplateWithInstance) {
        launchWrite {
            repository.reviveTemplate(item.template.id)
            dataSync.settleAndRefresh()
        }
    }
```

（import `kotlinx.coroutines.flow.update`。）

- [ ] **Step 3: HomeScreen 改造**

① 收集新状态（删 `remember { mutableStateMapOf }`）：

```kotlin
    val homeData by viewModel.homeData.collectAsStateWithLifecycle()
    val collapsed by viewModel.collapsed.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<TemplateWithInstance?>(null) }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
```

② 加载态（homeData==null 时显示加载而不是空态文案）：

```kotlin
    ) { padding ->
        val data = homeData
        if (data == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (data.activeGroups.isEmpty() && data.completedGroups.isEmpty() && data.expiredGroups.isEmpty()) {
            EmptyState(...) // 原有
            return@Scaffold
        }
        LazyColumn { ... } // 后续 homeData.xxx 引用改 data.xxx
    }
```

③ `categorySection` 的 `collapsed: MutableMap<String, Boolean>` 参数改 `collapsed: Set<String>` + `onToggle: (String) -> Unit`：

```kotlin
private fun LazyListScope.categorySection(
    sectionKey: String,
    group: CategoryGroup,
    collapsed: Set<String>,
    onToggle: (String) -> Unit,
    completed: Boolean,
    onComplete: (TemplateWithInstance) -> Unit,
    onDelete: (TemplateWithInstance) -> Unit,
    onClick: (TemplateWithInstance) -> Unit
) {
    val key = "$sectionKey:${group.category?.id ?: -1L}"
    val isCollapsed = key in collapsed

    item(key = "header_$key") {
        CategoryHeader(group = group, collapsed = isCollapsed, onToggle = { onToggle(key) })
    }

    item(key = "items_$key") {
        AnimatedVisibility(
            visible = !isCollapsed,
            enter = expandVertically(tween(150)) + fadeIn(tween(150)),
            exit = shrinkVertically(tween(130)) + fadeOut(tween(110))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.items.forEach { item ->
                    SwipeableTaskCard(
                        item = item,
                        color = group.color(),
                        completed = completed,
                        onComplete = { onComplete(item) },
                        onRequestDelete = { onDelete(item) },
                        onClick = { onClick(item) }
                    )
                }
            }
        }
    }
}
```

调用处传 `collapsed = collapsed, onToggle = viewModel::toggleCollapsed`。

④ `SwipeableTaskCard` 的 `onDelete` 参数改名 `onRequestDelete`，左滑只弹确认不再直接删（完整替换该 composable）：

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskCard(
    item: TemplateWithInstance,
    color: Color,
    completed: Boolean,
    onComplete: () -> Unit,
    onRequestDelete: () -> Unit,
    onClick: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                // 右滑完成仅对未完成区有效；已完成区右滑不响应
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (!completed) onComplete()
                    false
                }
                // 左滑删除：只通知外部弹确认窗，不直接 dismiss
                SwipeToDismissBoxValue.EndToStart -> { onRequestDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = !completed,
        backgroundContent = {
            val (bg, label) = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> CompletedGreen to "完成"
                SwipeToDismissBoxValue.EndToStart -> ExpiredRed to "删除"
                SwipeToDismissBoxValue.Settled -> Color.Transparent to ""
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                contentAlignment = if (dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd)
                    Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }
    ) {
        TaskCard(
            item = item,
            categoryColor = color,
            onComplete = onComplete,
            onClick = onClick,
            completed = completed
        )
    }
}
```

`categorySection` 的 onDelete 透传改为：主页调用处 `onDelete = { pendingDelete = it }`。删除确认弹窗（Scaffold 后）：

```kotlin
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除清单？") },
            text = { Text("「${item.template.title}」及其历史记录、提醒将一并删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
```

⑤ 已作废区（已完成区之后）：

```kotlin
            if (data.expiredGroups.isNotEmpty()) {
                item(key = "expired_divider") { Spacer(Modifier.padding(top = 8.dp)) }
                sectionHeader("已作废", data.expiredGroups.sumOf { it.items.size })
                data.expiredGroups.forEach { group ->
                    group.items.forEach { item ->
                        item(key = "expired_${group.category?.id ?: -1L}_${item.template.id}") {
                            ExpiredTaskCard(
                                item = item,
                                color = group.color(),
                                onRevive = { viewModel.revive(item) }
                            )
                        }
                    }
                }
            }
```

新增私有 composable：

```kotlin
@Composable
private fun ExpiredTaskCard(
    item: TemplateWithInstance,
    color: Color,
    onRevive: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(color.copy(alpha = 0.45f), MaterialTheme.shapes.small)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.template.title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDecoration = TextDecoration.LineThrough
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                LabelChip("已截止作废", MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onRevive) { Text("重新开启") }
        }
    }
}
```

（import：`androidx.compose.material3.Card`、`TextButton`、`LabelChip`（ui.components）、`TextDecoration`、`TextOverflow`、`CircularProgressIndicator`、`AlertDialog`、`SnackbarHost/SnackbarHostState`、`mutableStateOf/setValue`；`group.color()` 魔数 `Color(0xFF9E9E9E)` 改 `UncategorizedGray`；`SwipeableTaskCard` 背景 `Color(0xFF7FB69E)` 改 `CompletedGreen`、`Color(0xFFB45A5A)` 改 `ExpiredRed`。）

- [ ] **Step 4: 全量单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "feat: 主页删除确认弹窗、折叠状态切 tab 保留、加载态、已作废区与重新开启"
```

### Task 12: 日历（回到今天 + 响应式分类色 + 文案统一）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/calendar/CalendarScreen.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/Formatters.kt:46-58`

**Interfaces:**
- Produces: `remainingLabel(windowStart, windowEnd, referenceMillis)` 加第三参（默认 `System.currentTimeMillis()`）——主页/组件不传时行为不变；日历传所选日期。`CalendarViewModel.goToToday()`。
- Consumes: 无新依赖。

- [ ] **Step 1: Formatters.remainingLabel 加基准参数**

```kotlin
/** 剩余时间展示文案；referenceMillis 为比较基准（默认现在，日历传所选日期） */
fun remainingLabel(
    windowStart: Long,
    windowEnd: Long,
    referenceMillis: Long = System.currentTimeMillis()
): String {
    if (windowEnd == Long.MAX_VALUE) return "长期有效"
    if (windowStart > referenceMillis) return "${windowStart.toDateText()} 开始"
    val refDay = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(windowEnd).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(refDay, endDay)
    return when {
        days < 0 -> "已截止"
        days == 0L -> "今日截止"
        days == 1L -> "明日截止"
        else -> "剩 $days 天"
    }
}
```

- [ ] **Step 2: CalendarViewModel 修改**

- 删 `categoryColor()`（UI 改为响应式查表）。
- 加：

```kotlin
    /** 回到本月并选中今天 */
    fun goToToday() {
        _month.value = YearMonth.now()
        _selectedDate.value = LocalDate.now()
    }
```

- `renewalDatesInMonth` 的 `guard++ < 400` 改 `guard++ < MAX_RENEWAL_CYCLES`，类内加：

```kotlin
    private companion object {
        /** 单月打点最多推进的循环数（防极端规则下长时间计算） */
        const val MAX_RENEWAL_CYCLES = 400
    }
```

- `dayItems` 里 `runCatching { ... }.getOrNull()` 改带日志（import `android.util.Log`）：

```kotlin
                }.onFailure { Log.w("CalendarViewModel", "模板 ${t.id} 规则计算失败", it) }.getOrNull()
```

- [ ] **Step 3: CalendarScreen 修改**

① 分类色响应式（删 `viewModel.categoryColor` 调用）：

```kotlin
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    fun colorOf(categoryId: Long?): Color =
        categories.firstOrNull { it.id == categoryId }?.color?.let(::Color) ?: UncategorizedGray
```

`dotsByDate` 与 `DayItemCard` 的 color 参数改用 `colorOf(...)`（import `com.gametodolist.ui.theme.UncategorizedGray`、`CompletedGreen`、`ExpiredRed`，删三处魔数）。

② 月份切换 Row 的「下月」按钮后加回到今天：

```kotlin
                TextButton(onClick = viewModel::goToToday) { Text("今天") }
```

（import `androidx.compose.material3.TextButton`。）

③ 删私有 `remainingText(...)`，`DayItemCard` 调用处改：

```kotlin
                        remainingLabel(
                            dayItem.windowStart,
                            dayItem.windowEnd,
                            selectedDate.toEpochMillis()
                        )
```

（import 加 `com.gametodolist.ui.remainingLabel`，删 `java.time.temporal.ChronoUnit`。）

- [ ] **Step 4: 全量单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app/src && git commit -m "feat: 日历回到今天按钮；分类色改响应式；剩余天数文案与主页统一"
```

### Task 13: 设置页（精确闹钟提示条 + 位图处理后台化 + 壁纸失败提示）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/TodoWidget.kt:86`（WallpaperCache 调用后台化）

**Interfaces:**
- Consumes: `ReminderScheduler.canScheduleExactReminders()`（Task 7）。

- [ ] **Step 1: SettingsViewModel**

- 构造注入加 `private val reminderScheduler: ReminderScheduler`（import `com.gametodolist.notify.ReminderScheduler`）。
- `WidgetSettingsUiState` 加两字段：`val exactAlarmGranted: Boolean = true`、`val wallpaperError: Boolean = false`。
- `loadState()` 加 `exactAlarmGranted = reminderScheduler.canScheduleExactReminders()`。
- 加：

```kotlin
    /** 从系统设置页返回后刷新精确闹钟授权状态 */
    fun refreshExactAlarmState() {
        _uiState.update { it.copy(exactAlarmGranted = reminderScheduler.canScheduleExactReminders()) }
    }
```

- `decodeWallpaper` 返回 null 且 `hasWallpaper=true` 时视为解码失败：`loadState()` 不直接知道解码结果——改为在 init 与 setWallpaper 的解码后更新：

```kotlin
    init {
        viewModelScope.launch {
            val bmp = decodeWallpaper()
            _wallpaper.value = bmp
            if (WidgetPrefs.hasWallpaper(context) && bmp == null) {
                _uiState.update { it.copy(wallpaperError = true) }
            }
        }
    }
```

- `setWallpaper` 的 `if (!saved) return@launch` 改：

```kotlin
            if (!saved) {
                _uiState.update { it.copy(wallpaperError = true) }
                return@launch
            }
```

（成功后 `wallpaperError = false`，在 `_uiState.update { loadState() }` 处覆盖：`loadState()` 默认 false 即可。）

- [ ] **Step 2: SettingsScreen**

① 精确闹钟提示条（分类管理入口之后、"桌面小组件"区之前；仅 API 31+ 且未授权时显示）：

```kotlin
            if (!state.exactAlarmGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("精确提醒未开启", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "系统未授权精确闹钟，提醒可能延迟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                )
                            }
                        }) { Text("去开启") }
                    }
                }
            }
```

（`val context = LocalContext.current`；import `android.os.Build`、`android.content.Intent`、`android.provider.Settings`、`androidx.compose.material3.Card`、`androidx.compose.ui.platform.LocalContext`。）

② ON_RESUME 刷新授权状态（从系统设置返回后）：

```kotlin
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshExactAlarmState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
```

（import `androidx.compose.runtime.DisposableEffect`、`androidx.lifecycle.Lifecycle`、`androidx.lifecycle.LifecycleEventObserver`、`androidx.lifecycle.compose.LocalLifecycleOwner`。）

③ 壁纸解码/保存失败提示：`WallpaperSection` 的 else 分支文字改条件文案：

```kotlin
            Text(
                if (state.wallpaperError) "壁纸读取失败，请重新选择"
                else "尚未设置壁纸，点击上方按钮选择一张图片",
                ...
            )
```

④ `WallpaperPreview` 的裁剪移出主线程（`remember` → `produceState` + Default 调度）：

```kotlin
    val cropped by produceState<ImageBitmap?>(null, bitmap, cropRect) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                if (cropRect != null && cropRect.size == 4) {
                    Bitmap.createBitmap(
                        bitmap,
                        (cropRect[0] * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
                        (cropRect[1] * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
                        ((cropRect[2] - cropRect[0]) * bitmap.width).toInt()
                            .coerceIn(1, bitmap.width - (cropRect[0] * bitmap.width).toInt()),
                        ((cropRect[3] - cropRect[1]) * bitmap.height).toInt()
                            .coerceIn(1, bitmap.height - (cropRect[1] * bitmap.height).toInt())
                    ).asImageBitmap()
                } else {
                    val r = WallpaperCrop.cropRect(bitmap.width, bitmap.height, previewAspect, 0.5f, 0.5f)
                    Bitmap.createBitmap(bitmap, r[0], r[1], r[2], r[3]).asImageBitmap()
                }
            }.getOrNull()
        }
    }
```

（import `androidx.compose.runtime.produceState`、`androidx.compose.ui.graphics.ImageBitmap`、`kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.withContext`；`previewAspect` 提到 produceState 外。）

- [ ] **Step 3: TodoWidget 壁纸解码后台化**

`TodoWidget.kt:86` 改（import `kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.withContext`）：

```kotlin
        val wallpaper: Bitmap? = if (bgMode == WidgetPrefs.BG_WALLPAPER) {
            withContext(Dispatchers.IO) { WallpaperCache.get(context) }
        } else null
```

- [ ] **Step 4: 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle assembleDebug --console=plain
git add app/src && git commit -m "feat: 设置页精确闹钟授权提示与跳转；位图裁剪/解码移出主线程；壁纸失败提示"
```

### Task 14: 分类页确认与异常保护 + TaskCard 无障碍

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/category/CategoryViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/category/CategoryScreen.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/components/TaskCard.kt`

**Interfaces:**
- Consumes: `DataSyncManager`（Task 8）。

- [ ] **Step 1: CategoryViewModel 异常保护**

```kotlin
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: TodoRepository,
    private val dataSync: DataSyncManager
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun consumeError() { _errorMessage.value = null }

    fun save(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                repository.upsertCategory(category)
                dataSync.settleAndRefresh()
            } catch (e: Exception) {
                _errorMessage.value = "保存失败，请重试"
            }
        }
    }

    fun delete(category: CategoryEntity) {
        viewModelScope.launch {
            try {
                repository.deleteCategory(category)
                dataSync.settleAndRefresh()
            } catch (e: Exception) {
                _errorMessage.value = "删除失败，请重试"
            }
        }
    }
}
```

（Task 8 已把构造改为 dataSync，本步只补错误处理。）

- [ ] **Step 2: CategoryScreen 删除确认 + Snackbar**

- 状态与 Snackbar（import `androidx.compose.material3.SnackbarHost`、`androidx.compose.material3.SnackbarHostState`）：

```kotlin
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<CategoryEntity?>(null) }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
```

- Scaffold 加 `snackbarHost = { SnackbarHost(snackbarHostState) }`；`CategoryRow` 的 `onDelete = { pendingDelete = category }`。
- 删除确认弹窗：

```kotlin
    pendingDelete?.let { category ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除分类？") },
            text = { Text("「${category.name}」将被删除，其中的清单归入未分类。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(category)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
```

- [ ] **Step 3: TaskCard 勾选圈无障碍**

`TaskCard.kt` 两处点击修饰符加 role/label：

已完成图标：

```kotlin
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            onClickLabel = "撤销完成",
                            role = androidx.compose.ui.semantics.Role.Checkbox,
                            onClick = onComplete
                        )
```

未完成圆圈：

```kotlin
                    modifier = Modifier
                        .size(24.dp)
                        .border(2.dp, categoryColor, CircleShape)
                        .clickable(
                            onClickLabel = "完成",
                            role = androidx.compose.ui.semantics.Role.Checkbox,
                            onClick = onComplete
                        )
```

- [ ] **Step 4: 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle assembleDebug --console=plain
git add app/src && git commit -m "feat: 分类删除确认弹窗与写操作异常提示；勾选圈补读屏 role/label"
```

---

## 批次 4：清理

### Task 15: 死代码/依赖/魔数清理 + WallpaperCrop 测试补充

**Files:**
- Modify: `app/src/main/java/com/gametodolist/data/dao/TaskInstanceDao.kt`（删 `observeActive` Flow 版、`observeInRange` 及其注释行）
- Modify: `app/src/main/java/com/gametodolist/data/dao/CategoryDao.kt`（删 `getAll`、`getById`）
- Modify: `app/src/main/java/com/gametodolist/data/TodoRepository.kt`（删 `getActiveInstances`）
- Modify: `app/src/test/java/com/gametodolist/RolloverManagerTest.kt`（Fake DAO 删对应 override）
- Modify: `app/build.gradle.kts`（删 glance-material3）
- Modify: `app/src/main/java/com/gametodolist/data/entity/TaskTemplateEntity.kt:18`（sortOrder 注释）、`TaskInstanceEntity.kt:28`（windowStart 注释）
- Test: `app/src/test/java/com/gametodolist/WallpaperCropTest.kt`（追加用例）

注意：`TaskTemplateDao.update`/`CategoryDao.update` 在 Task 1 分流后已有真实调用方，**保留**。`CategoryEntity.sortOrder`/`TaskTemplateEntity.sortOrder` 保留（删列需重建表，成本不值）。

- [ ] **Step 1: 删除死代码**

- `TaskInstanceDao.kt`：删 `observeActive()`（Flow 版，13-14 行）、`observeInRange`（33-39 行，连同其上注释行）；FakeInstanceDao 删对应两个 override。
- `CategoryDao.kt`：删 `getAll()`、`getById()`。
- `TodoRepository.kt`：删 `getActiveInstances()`。
- `app/build.gradle.kts`：删 `implementation("androidx.glance:glance-material3:1.1.1")`。
- `TaskTemplateEntity.kt`：`val sortOrder: Int = 0,` 上方注释改 `// 预留：任务排序未启用（恒为 0，按 id 排序）`。
- `TaskInstanceEntity.kt`：`val windowStart: Long,  // 显示窗口起点` 注释改 `// 显示窗口起点（保留字段：窗口本可由引擎重算，此列为历史存储）`。

- [ ] **Step 2: 魔数颜色复查**

```bash
grep -rn "0xFF7FB69E\|0xFFB45A5A\|0xFF9E9E9E" app/src/main/java --include="*.kt"
```
确认剩余引用只剩：`domain/Models.kt` 的 `CATEGORY_COLOR_PRESETS`（分类色板，保留原样）与 `ui/theme/Color.kt` 常量定义本身。若 `HomeScreen`/`CalendarScreen` 仍有残留，替换为 `CompletedGreen`/`ExpiredRed`/`UncategorizedGray`。

- [ ] **Step 3: WallpaperCropTest 追加边界用例**

```kotlin
    @Test
    fun `源图与目标同比例 整图返回`() {
        assertArrayEquals(
            intArrayOf(0, 0, 1000, 500),
            WallpaperCrop.cropRect(1000, 500, 2.0f, 0.5f, 0.5f)
        )
    }

    @Test
    fun `1x1 极值源图不崩溃`() {
        val r = WallpaperCrop.cropRect(1, 1, 2.4f, 0.5f, 0.5f)
        assertTrue(r[2] >= 1 || r[3] >= 1)
        assertTrue(r[0] + r[2] <= 1 && r[1] + r[3] <= 1)
    }

    @Test
    fun `返回窗口比例约等于目标比例`() {
        listOf(0.5f, 1.0f, 1.7f, 2.4f).forEach { aspect ->
            val r = WallpaperCrop.cropRect(1080, 1920, aspect, 0.3f, 0.7f)
            val actual = r[2].toFloat() / r[3]
            assertTrue(kotlin.math.abs(actual - aspect) < 0.01f)
        }
    }
```

- [ ] **Step 4: 全量单测 + 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest assembleDebug --console=plain
git add app && git commit -m "chore: 删除死代码与未用依赖（glance-material3），魔数颜色收敛，补裁剪边界测试"
```

### Task 16: 组件边角（onDeleted 清理 + 裁剪页修复 + description）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/widget/WidgetPrefs.kt`（加 `clearCropRect`）
- Modify: `app/src/main/java/com/gametodolist/widget/TodoWidgetReceiver.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/WidgetCropActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/todo_widget_info.xml`

**Interfaces:**
- Produces: `WidgetPrefs.clearCropRect(context, appWidgetId)`。

- [ ] **Step 1: WidgetPrefs.clearCropRect + Receiver.onDeleted**

`WidgetPrefs.kt` 加：

```kotlin
    /** 组件实例删除后清理其裁剪区域键，避免长期残留 */
    fun clearCropRect(context: Context, appWidgetId: Int) {
        val base = KEY_CROP_PREFIX + appWidgetId
        prefs(context).edit()
            .remove(base + "_l").remove(base + "_t")
            .remove(base + "_r").remove(base + "_b")
            .apply()
    }
```

`TodoWidgetReceiver.kt`：

```kotlin
package com.gametodolist.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

class TodoWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodoWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        appWidgetIds.forEach { WidgetPrefs.clearCropRect(context, it) }
    }
}
```

- [ ] **Step 2: WidgetCropActivity 修复**

① `onCreate` 开头（setResult 之后）加无壁纸直退（覆盖 API 26-30 首次添加组件被强制拉起 configure 的死胡同）：

```kotlin
        setResult(RESULT_CANCELED) // configure 流程默认取消，保存成功时改 OK
        if (!WidgetPrefs.hasWallpaper(this)) {
            // 未设壁纸时裁剪无意义（低版本首次添加组件会被系统强制拉起到此）
            finish()
            return
        }
```

② `widgetAspectFromSystem` 的 `sizes?.firstOrNull()` 改取与默认比例最接近的：

```kotlin
                sizes?.minByOrNull { kotlin.math.abs(it.width / it.height - 1.4f) }
                    ?.let { it.width / it.height }
```

③ CropScreen 的同步解码改后台：

```kotlin
    val bitmap by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            if (WidgetPrefs.hasWallpaper(context)) {
                runCatching { BitmapFactory.decodeFile(WidgetPrefs.wallpaperFile(context).absolutePath) }.getOrNull()
            } else null
        }
    }
```

（import `androidx.compose.runtime.produceState`、`getValue/setValue`、`kotlinx.coroutines.Dispatchers/withContext`；decode 期间 bitmap==null 会短暂显示提示页——可接受，或加 `var loaded` 区分，不必过度。）

④ `initialized` Float 改 Boolean：

```kotlin
        var initialized by remember { mutableStateOf(false) }
        if (!initialized) {
            initialized = true
            ...
        }
```

（import `androidx.compose.runtime.mutableStateOf`，删 `mutableFloatStateOf` 中不再用的。）

⑤ 保存按钮：`CoroutineScope(Dispatchers.Main).launch` 改 `lifecycleScope.launch`（import `androidx.lifecycle.lifecycleScope`，删 `kotlinx.coroutines.CoroutineScope` import），且 `setResult` 回传组件 id：

```kotlin
                    (context as? WidgetCropActivity)?.setResult(
                        android.app.Activity.RESULT_OK,
                        android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    )
                    (context as? WidgetCropActivity)?.lifecycleScope?.launch {
                        runCatching { TodoWidget().updateAll(context) }
                    }
```

- [ ] **Step 3: 组件描述**

`strings.xml` 加：

```xml
    <string name="widget_description">在桌面显示循环清单任务，点击即可完成任务</string>
```

`todo_widget_info.xml` 的 `android:description="@string/app_name"` 改 `android:description="@string/widget_description"`。

- [ ] **Step 4: 构建 + Commit**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle assembleDebug --console=plain
git add app && git commit -m "fix: 组件删除后清理裁剪残留键；裁剪页无壁纸直退/回传组件 id/lifecycleScope；补组件描述"
```

---

## 批次 5：全量验证

### Task 17: 全量测试 + 模拟器冒烟 + 文档同步

**Files:**
- Modify: `AGENTS.md`（约定更新）

- [ ] **Step 1: 全量单测 + 构建（强制重跑）**

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle cleanTest testDebugUnitTest --rerun assembleDebug --console=plain
grep -h "testsuite name" app/build/test-results/testDebugUnitTest/*.xml | sed 's/ timestamp.*//'
```
预期：全部 `failures="0" errors="0"`，BUILD SUCCESSFUL。

- [ ] **Step 2: 模拟器冒烟**

```bash
export PATH="$PATH:/c/Users/da270/AppData/Local/Android/Sdk/platform-tools:/c/Users/da270/AppData/Local/Android/Sdk/emulator"
emulator -avd Pixel_6 &   # 后台启动，等待开机完成：adb wait-for-device
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
MSYS_NO_PATHCONV=1 adb shell am start -n com.gametodolist/.ui.MainActivity
```

逐条验证（每条用 `adb exec-out screencap -p > /tmp/s.png` + ReadMediaFile 读 `C:\Users\da270\AppData\Local\Temp\s.png` 确认；坐标用 `adb shell uiautomator dump /sdcard/u.xml && MSYS_NO_PATHCONV=1 adb pull /sdcard/u.xml /tmp/u.xml` 取 bounds）：

1. **NONE 任务创建即作废**：新建不循环任务，自定义持续 1 天、开始日期选昨天 → 主页应出现在「已作废」区而非未完成区。
2. **复活**：点「重新开启」→ 任务回到未完成区，显示「剩 1 天」或「今日截止」。
3. **编辑保历史**：完成一个每周任务（进已完成区）→ 点进去改标题保存 → 主页仍显示该任务（已完成区），标题已更新——历史未被清空（A 类核心回归）。
4. **每周开始日期**：新建每周任务，打开「指定开始日期」选下周一 → 主页未完成区显示「X月X日开始」。
5. **删除确认**：左滑任务 → 弹确认窗，「取消」任务仍在；再滑「删除」任务消失。
6. **放弃修改**：编辑页改标题后点返回 → 弹「放弃修改？」，「继续编辑」留在页面，再返回「放弃」退出。
7. **回到今天**：日历连点三次「下月」→ 点「今天」→ 回到当月且选中今天。
8. **分类删除确认**：分类管理删一个分类 → 弹确认；删除后其任务归入未分类且主页可见。
9. **组件**：桌面添加组件（首次无壁纸应直接添加成功不卡裁剪页）→ 点任务行完成/撤销（划线切换）→ 点「已完成」展开/收起 → 点刷新按钮。
10. **设置**：设置页选壁纸 → 亮度滑块 → 组件头部裁剪按钮进裁剪页，拖选框保存 → 组件显示所选区域；切纯色模式调透明度。
11. **精确提醒提示条**（API 35 模拟器若未授权）：设置页显示提示条，点「去开启」跳系统页，返回后提示消失。

- [ ] **Step 3: 更新 AGENTS.md**

- 「约定」第一条改为：改动任务状态/模板/分类后统一走 `DataSyncManager.settleAndRefresh()`（结算+重排提醒+刷新组件三合一）；仅外观设置改动仍调 `WidgetUpdater.refresh`。
- 「架构地图-领域层」补：`RecurrenceEngine` 的 WEEKLY/MONTHLY 支持 anchorDate（首个循环不早于 anchor，anchor<=0 保持旧行为）；「数据层」DB v3（实例表 (templateId,cycleStart) 唯一索引，exportSchema 开）。
- 「架构地图-提醒」补：Android 14+ 精确闹钟降级；ReminderReceiver 触发前校验实例状态。
- 「UI」段补：主页含已作废区（一次性任务可重新开启）；删除类操作均有确认弹窗。

- [ ] **Step 4: 最终 Commit**

```bash
git add -A && git commit -m "docs: 全量验证通过，AGENTS.md 同步新管线约定与语义"
```
