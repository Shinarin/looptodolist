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
        assertEquals(RecurrenceType.AFTER_COMPLETION, "AFTER_COMPLETION".toRecurrenceType())
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
