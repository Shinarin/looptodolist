package com.gametodolist

import com.gametodolist.domain.WallpaperCrop
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperCropTest {

    @Test
    fun `宽幅源图裁方形 偏移决定水平位置`() {
        // 2000x1000 裁 1:1 → 1000x1000，x 可滑 0..1000
        assertArrayEquals(
            intArrayOf(0, 0, 1000, 1000),
            WallpaperCrop.cropRect(2000, 1000, 1.0f, 0f, 0.5f)
        )
        assertArrayEquals(
            intArrayOf(500, 0, 1000, 1000),
            WallpaperCrop.cropRect(2000, 1000, 1.0f, 0.5f, 0.5f)
        )
        assertArrayEquals(
            intArrayOf(1000, 0, 1000, 1000),
            WallpaperCrop.cropRect(2000, 1000, 1.0f, 1f, 0.5f)
        )
    }

    @Test
    fun `竖长目标裁高度受限方向`() {
        // 1000x2000 裁 2.4:1 → 宽=1000, 高=416，y 可滑 0..1584
        val rect = WallpaperCrop.cropRect(1000, 2000, 2.4f, 0.5f, 1f)
        assertTrue(rect[0] == 0 && rect[2] == 1000)
        assertTrue(rect[3] == 416)
        assertTrue(rect[1] == 2000 - 416)
    }

    @Test
    fun `偏移越界时收敛到有效范围`() {
        val rect = WallpaperCrop.cropRect(2000, 1000, 1.0f, 5f, -3f)
        assertArrayEquals(intArrayOf(1000, 0, 1000, 1000), rect)
    }

    @Test
    fun `裁剪窗口不越出源图`() {
        listOf(0.5f, 1.0f, 1.7f, 2.4f).forEach { aspect ->
            val r = WallpaperCrop.cropRect(1080, 1920, aspect, 0.3f, 0.7f)
            assertTrue(r[0] >= 0 && r[1] >= 0)
            assertTrue(r[0] + r[2] <= 1080)
            assertTrue(r[1] + r[3] <= 1920)
        }
    }
}
