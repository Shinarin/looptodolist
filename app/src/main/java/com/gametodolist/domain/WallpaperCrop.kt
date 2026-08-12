package com.gametodolist.domain

/** 壁纸裁剪窗口计算（纯函数，便于单元测试） */
object WallpaperCrop {

    /**
     * 覆盖式（cover）裁剪：从源图裁出与目标宽高比一致的最大窗口，
     * 窗口位置由 offsetX/offsetY（0..1）在可滑动范围内决定，0.5 为居中。
     *
     * @return intArrayOf(x, y, width, height)，保证不越界
     */
    fun cropRect(
        srcWidth: Int,
        srcHeight: Int,
        targetAspect: Float,
        offsetX: Float,
        offsetY: Float
    ): IntArray {
        require(srcWidth > 0 && srcHeight > 0 && targetAspect > 0f)
        val srcAspect = srcWidth.toFloat() / srcHeight.toFloat()
        val cropW: Int
        val cropH: Int
        if (srcAspect > targetAspect) {
            cropH = srcHeight
            cropW = (srcHeight * targetAspect).toInt().coerceIn(1, srcWidth)
        } else {
            cropW = srcWidth
            cropH = (srcWidth / targetAspect).toInt().coerceIn(1, srcHeight)
        }
        val maxX = srcWidth - cropW
        val maxY = srcHeight - cropH
        val x = (maxX * offsetX.coerceIn(0f, 1f)).toInt().coerceIn(0, maxX)
        val y = (maxY * offsetY.coerceIn(0f, 1f)).toInt().coerceIn(0, maxY)
        return intArrayOf(x, y, cropW, cropH)
    }
}
