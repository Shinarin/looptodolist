package com.gametodolist.widget

import android.appwidget.AppWidgetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.updateAll
import com.gametodolist.ui.theme.GameTodoTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.min

/**
 * 小组件壁纸裁剪页：以组件当前宽高比在图片上框选显示区域。
 * 进入方式：组件头部的裁剪按钮，或长按组件 → 设置（configure）。
 * 选框可拖动、双指缩放；保存后只影响对应组件实例。
 */
class WidgetCropActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val aspect = intent.getFloatExtra(EXTRA_ASPECT, -1f).takeIf { it > 0f }
            ?: widgetAspectFromSystem(appWidgetId)
            ?: 1.4f

        setResult(RESULT_CANCELED) // configure 流程默认取消，保存成功时改 OK
        setContent {
            GameTodoTheme {
                CropScreen(
                    appWidgetId = appWidgetId,
                    aspect = aspect,
                    onClose = { finish() }
                )
            }
        }
    }

    /** 长按菜单进入时没有 aspect 参数，尝试从系统读组件实际尺寸 */
    private fun widgetAspectFromSystem(appWidgetId: Int): Float? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val sizes = AppWidgetManager.getInstance(this)
                    .getAppWidgetOptions(appWidgetId)
                    .getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                sizes?.firstOrNull()?.let { it.width / it.height }
            } else null
        }.getOrNull()
    }

    companion object {
        const val EXTRA_ASPECT = "com.gametodolist.extra.WIDGET_ASPECT"
    }
}

@Composable
private fun CropScreen(
    appWidgetId: Int,
    aspect: Float,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val bitmap = remember {
        if (WidgetPrefs.hasWallpaper(context)) {
            runCatching { BitmapFactory.decodeFile(WidgetPrefs.wallpaperFile(context).absolutePath) }.getOrNull()
        } else null
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose) { Text("取消") }
            Text(
                "裁剪壁纸",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.width(64.dp)) // 与左侧取消按钮对称
        }

        if (bitmap == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "尚未设置壁纸，请先到 设置 → 桌面小组件 选择壁纸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            CropArea(
                bitmap = bitmap,
                aspect = aspect,
                appWidgetId = appWidgetId,
                onClose = onClose
            )
        }
    }
}

@Composable
private fun CropArea(
    bitmap: Bitmap,
    aspect: Float,
    appWidgetId: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val frameW = constraints.maxWidth.toFloat()
        val frameH = constraints.maxHeight.toFloat()

        // 图片适配显示区域（Fit）
        val scale = min(frameW / bitmap.width, frameH / bitmap.height)
        val dispW = bitmap.width * scale
        val dispH = bitmap.height * scale
        val originX = (frameW - dispW) / 2f
        val originY = (frameH - dispH) / 2f

        // 基准选框：与组件同宽高比、恰好内切图片的最大矩形
        val baseW: Float
        val baseH: Float
        if (dispW / dispH > aspect) {
            baseH = dispH
            baseW = dispH * aspect
        } else {
            baseW = dispW
            baseH = dispW / aspect
        }

        var boxScale by remember { mutableFloatStateOf(1f) }
        var centerX by remember { mutableFloatStateOf(0f) }  // 相对图片中心，px
        var centerY by remember { mutableFloatStateOf(0f) }
        var initialized by remember { mutableFloatStateOf(0f) }

        // 已有裁剪则还原选框位置
        if (initialized == 0f) {
            initialized = 1f
            WidgetPrefs.cropRect(context, appWidgetId)?.let { r ->
                val w = (r[2] - r[0]) * dispW
                boxScale = (w / baseW).coerceIn(0.25f, 1f)
                centerX = ((r[0] + r[2]) / 2f - 0.5f) * dispW
                centerY = ((r[1] + r[3]) / 2f - 0.5f) * dispH
            }
        }

        val boxW = baseW * boxScale
        val boxH = baseH * boxScale
        // 选框中心可移动范围
        val maxCx = ((dispW - boxW) / 2f).coerceAtLeast(0f)
        val maxCy = ((dispH - boxH) / 2f).coerceAtLeast(0f)
        centerX = centerX.coerceIn(-maxCx, maxCx)
        centerY = centerY.coerceIn(-maxCy, maxCy)

        fun currentRectFractions(): FloatArray {
            val l = ((dispW / 2f + centerX - boxW / 2f) / dispW).coerceIn(0f, 1f)
            val t = ((dispH / 2f + centerY - boxH / 2f) / dispH).coerceIn(0f, 1f)
            val r = (l + boxW / dispW).coerceIn(0f, 1f)
            val b = (t + boxH / dispH).coerceIn(0f, 1f)
            return floatArrayOf(l, t, r, b)
        }

        // 图片
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset { IntOffset(originX.toInt(), originY.toInt()) }
                .width(with(density) { dispW.toDp() })
                .height(with(density) { dispH.toDp() })
        )

        // 选框外暗色遮罩（四块）
        val boxLeft = originX + dispW / 2f + centerX - boxW / 2f
        val boxTop = originY + dispH / 2f + centerY - boxH / 2f
        val scrim = Color.Black.copy(alpha = 0.55f)
        fun Modifier.scrimRect(x: Float, y: Float, w: Float, h: Float): Modifier =
            this.offset { IntOffset(x.toInt(), y.toInt()) }
                .width(with(density) { w.toDp() })
                .height(with(density) { h.toDp() })
                .background(scrim)
        Box(Modifier.scrimRect(0f, 0f, frameW, boxTop))                                    // 上
        Box(Modifier.scrimRect(0f, boxTop + boxH, frameW, frameH - boxTop - boxH))         // 下
        Box(Modifier.scrimRect(0f, boxTop, boxLeft, boxH))                                 // 左
        Box(Modifier.scrimRect(boxLeft + boxW, boxTop, frameW - boxLeft - boxW, boxH))     // 右

        // 选框（拖动移动、双指缩放）
        Box(
            modifier = Modifier
                .offset { IntOffset(boxLeft.toInt(), boxTop.toInt()) }
                .width(with(density) { boxW.toDp() })
                .height(with(density) { boxH.toDp() })
                .border(2.dp, Color.White)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                        boxScale = (boxScale * zoom).coerceIn(0.25f, 1f)
                        centerX += pan.x
                        centerY += pan.y
                    }
                }
        )

        // 底部操作栏
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "拖动移动选框，双指缩放调整范围；选框比例与当前组件一致",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val r = currentRectFractions()
                    WidgetPrefs.setCropRect(context, appWidgetId, r[0], r[1], r[2], r[3])
                    (context as? WidgetCropActivity)?.setResult(android.app.Activity.RESULT_OK)
                    CoroutineScope(Dispatchers.Main).launch {
                        runCatching { TodoWidget().updateAll(context) }
                    }
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}
