package com.gametodolist.ui

import android.app.Activity
import android.os.Build
import kotlin.math.abs

/**
 * 高刷新率适配：给窗口钉住最接近 120Hz 的显示模式。
 *
 * 荣耀/MagicOS 智能刷新率对第三方应用默认按 60Hz 渲染，触摸后才升档，
 * 滑动起步有 60→120 的爬升延迟，表现为每次滑起来先卡一下（实测起步前几帧 16.6~36ms）。
 * 通过 `preferredDisplayModeId` 声明窗口偏好模式后，渲染封顶解除（实测起步即 8.3ms 帧）。
 * 高刷档位从 90Hz 起步挑、优先贴近 120Hz（不为清单类应用拉满 144Hz）。
 * 每个独立窗口（Activity）都要各自调用一次。
 */
internal fun Activity.pinHighRefreshMode() {
    val currentDisplay = if (Build.VERSION.SDK_INT >= 30) display
    else @Suppress("DEPRECATION") windowManager.defaultDisplay
    val modes = currentDisplay?.supportedModes ?: return
    val target = modes.filter { it.refreshRate >= 90f }
        .minByOrNull { abs(it.refreshRate - 120f) }
        ?: modes.maxByOrNull { it.refreshRate }
        ?: return
    val lp = window.attributes
    if (lp.preferredDisplayModeId != target.modeId) {
        lp.preferredDisplayModeId = target.modeId
        window.attributes = lp
    }
}
