package com.gametodolist

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.gametodolist.data.AppDatabase
import com.gametodolist.data.TodoRepository
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
        val repository = TodoRepository(db.categoryDao(), templateDao, instanceDao)
        val id = templateDao.insert(
            TaskTemplateEntity(categoryId = null, title = "原始", recurrenceType = "WEEKLY", weekdaysMask = 1)
        )
        instanceDao.insert(TaskInstanceEntity(templateId = id, cycleStart = 1000, windowStart = 1000, windowEnd = 2000, status = "COMPLETED"))
        instanceDao.insert(TaskInstanceEntity(templateId = id, cycleStart = 3000, windowStart = 3000, windowEnd = 4000))

        // 模拟编辑保存：改标题后按 id 写回（走 Repository 的 insert/update 分流）
        val saved = templateDao.getById(id)!!.copy(title = "改过")
        repository.saveTemplate(saved)

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
