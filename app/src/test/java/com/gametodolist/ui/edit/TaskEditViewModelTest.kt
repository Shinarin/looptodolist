package com.gametodolist.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditViewModelTest {
    @Test
    fun `编辑实体映射保留停用标记`() {
        val state = TaskEditUiState(
            id = 42,
            title = " 停用清单 ",
            paused = true
        )

        val template = state.toTaskTemplate(validCategoryId = null)

        assertEquals(42, template.id)
        assertEquals("停用清单", template.title)
        assertTrue(template.paused)
    }
}
