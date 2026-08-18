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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.lifecycle.lifecycleScope
import com.gametodolist.ui.theme.GameTodoTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 小组件壁纸裁剪页：以组件当前宽高比在图片上框选显示区域。
 * 进入方式：组件头部的裁剪按钮，或长按组件 → 设置（configure）。
 * 选框可拖动、双指缩放；保存后只影响对应组件实例。
 *
 * 比例来源：桌面上报的精确尺寸（OPTION_APPWIDGET_SIZES，Pixel 等支持）；
 * 部分 Launcher（如荣耀 MagicOS）不上报真实尺寸（SIZES 为空、min/max 为粗范围），
 * 此时改为自由框选——组件端以 ContentScale.Crop 把所选区域铺满显示，所选即所得。
 */
class WidgetCropActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        // 挂起模式：来自换壁纸中转页，裁剪的是挂起文件。
        // 点保存才原子替换正式壁纸；取消/返回丢弃挂起文件，旧壁纸与裁剪区域原样保留。
        val pendingMode = intent.getBooleanExtra(EXTRA_PENDING, false)
        // null = 桌面未上报组件尺寸，自由框选。
        // intent 里的 aspect 来自组合时的 LocalSize，仅在桌面上报精确尺寸（SIZES 非空）时才可信
        // （荣耀等 Launcher 的 SIZES 为空，LocalSize 是用 min/max 乱拼的，不能用）
        val extra = intent.getFloatExtra(EXTRA_ASPECT, -1f).takeIf { it > 0f && hasExactSizes }
        val aspect = extra ?: widgetAspectFromSystem(appWidgetId)

        // configure 流程（API 26-30 添加组件时系统强制经过本页）：取消/返回/无壁纸都放行添加
        // （RESULT_CANCELED 会让组件加不上桌面），壁纸用默认居中裁剪，之后可随时再裁剪
        setResult(RESULT_OK, android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (pendingMode) {
            val p = WallpaperImporter.pendingFile(this)
            if (!p.exists() || p.length() <= 0L) {
                finish()
                return
            }
            // 返回键 = 取消：丢弃挂起文件（「取消」按钮在 onClose 里统一处理）
            onBackPressedDispatcher.addCallback(
                this,
                object : androidx.activity.OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        WallpaperImporter.discardPending(this@WidgetCropActivity)
                        finish()
                    }
                }
            )
        } else if (!WidgetPrefs.hasWallpaper(this)) {
            // 未设壁纸时裁剪无意义，直接放行
            finish()
            return
        }
        setContent {
            val themeId by remember {
                WidgetEntryPoint.get(this@WidgetCropActivity).settingsPrefs().themeId
            }.collectAsStateWithLifecycle()
            GameTodoTheme(themeId = themeId) {
                CropScreen(
                    appWidgetId = appWidgetId,
                    aspect = aspect,
                    pending = pendingMode,
                    onClose = {
                        // 挂起模式下任何关闭路径（取消/保存后）都确保挂起文件不残留
                        if (pendingMode) WallpaperImporter.discardPending(this@WidgetCropActivity)
                        finish()
                    }
                )
            }
        }
    }

    /** 桌面上报的 SIZES 是否可用（荣耀等 Launcher 返回空列表，此时 LocalSize 传来的比例也不可信） */
    private val hasExactSizes: Boolean
        get() = !exactSizes().isNullOrEmpty()

    private fun exactSizes(): List<android.util.SizeF>? {
        if (Build.VERSION.SDK_INT < 31) return null
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return runCatching {
            AppWidgetManager.getInstance(this).getAppWidgetOptions(id)
                .getParcelableArrayList<android.util.SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
        }.getOrNull()
    }

    /**
     * 从系统读组件当前宽高比：API 31+ 取 SIZES 首个（当前尺寸）；
     * 更低版本用 min/max 中值（老 Launcher 一般 min==max）；读不到返回 null。
     */
    private fun widgetAspectFromSystem(appWidgetId: Int): Float? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                exactSizes()?.firstOrNull()?.let { it.width / it.height }
            } else {
                val opts = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
                val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) +
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
                val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) +
                    opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
                if (w > 0 && h > 0) w.toFloat() / h else null
            }
        }.getOrNull()
    }

    companion object {
        const val EXTRA_ASPECT = "com.gametodolist.extra.WIDGET_ASPECT"
        /** true = 挂起模式：裁剪换壁纸流程的挂起文件，保存才生效，取消丢弃 */
        const val EXTRA_PENDING = "com.gametodolist.extra.WALLPAPER_PENDING"
    }
}

/** 自由框选的比例预设（value 为 w/h，null = 全图） */
private val RATIO_PRESETS: List<Pair<String, Float?>> = listOf(
    "全图" to null,
    "1:1" to 1f,
    "4:3" to 4f / 3f,
    "3:4" to 3f / 4f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f
)

/** 进入裁剪页时从已保存区域还原的初始状态（preset 为 null 表示全图/锁定模式忽略） */
private data class InitialCrop(val preset: Float?, val scale: Float, val cx: Float, val cy: Float)

/** 比例预设的形状示意：按真实宽高比画的描边小矩形 */
@Composable
private fun RatioShapeGlyph(ratio: Float) {
    val color = LocalContentColor.current
    val w = if (ratio >= 1f) 16.dp else 16.dp * ratio
    val h = if (ratio >= 1f) 16.dp / ratio else 16.dp
    Box(
        Modifier
            .width(w)
            .height(h)
            .border(1.dp, color)
            .background(color.copy(alpha = 0.15f))
    )
}

@Composable
private fun CropScreen(
    appWidgetId: Int,
    aspect: Float?,
    pending: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) {
            // 挂起模式裁挂起文件（未生效的新壁纸），否则裁当前正式壁纸
            val f = if (pending) {
                WallpaperImporter.pendingFile(context)
            } else {
                WidgetPrefs.wallpaperFile(context)
            }
            if (f.exists() && f.length() > 0) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
            } else null
        }
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
            // 取消也放行添加（默认 RESULT_OK），仅表示放弃裁剪
            TextButton(onClick = onClose) { Text("取消") }
            Text(
                "裁剪壁纸",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(Modifier.width(64.dp)) // 与左侧取消按钮对称
        }

        val bmp = bitmap
        if (bmp == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "尚未设置壁纸，请先到 设置 → 桌面小组件 选择壁纸",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            CropArea(
                bitmap = bmp,
                aspect = aspect,
                appWidgetId = appWidgetId,
                pending = pending,
                onClose = onClose
            )
        }
    }
}

@Composable
private fun CropArea(
    bitmap: Bitmap,
    aspect: Float?,
    appWidgetId: Int,
    pending: Boolean,
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

        // 选框比例：锁定时为组件宽高比；自由框选（aspect=null）时由底部预设选择，null=全图
        // 初始恢复：从已保存的裁剪区域还原比例（含非预设的自定义比例）、大小与位置，
        // 再次进入时选框与上次保存时完全一致
        val fullRatio = bitmap.width.toFloat() / bitmap.height
        // 挂起模式裁的是新图，旧图的已存裁剪区域无意义，从全图重新开始（与更换壁纸清空区域语义一致）
        val restored = remember {
            if (pending) null else WidgetPrefs.cropRect(context, appWidgetId)?.let { r ->
                val savedRatio = ((r[2] - r[0]) * bitmap.width) / ((r[3] - r[1]) * bitmap.height)
                // 期望比例：锁定模式用组件比例；自由模式先匹配预设（≈全图比例归到全图），都不匹配则沿用原比例
                val desired: Float? = aspect
                    ?: RATIO_PRESETS.map { it.second }.firstOrNull { p ->
                        val eff = p ?: fullRatio
                        kotlin.math.abs(eff - savedRatio) / eff < 0.02f
                    }.let { matched ->
                        when {
                            matched != null -> matched
                            kotlin.math.abs(fullRatio - savedRatio) / fullRatio < 0.02f -> null
                            else -> savedRatio
                        }
                    }
                // 用与下方基准框相同的公式反推缩放系数
                val bw: Float
                val bh: Float
                if (desired == null || dispW / dispH <= desired) {
                    bw = dispW
                    bh = if (desired == null) dispH else dispW / desired
                } else {
                    bh = dispH
                    bw = dispH * desired
                }
                val w = (r[2] - r[0]) * dispW
                InitialCrop(
                    preset = if (aspect != null) null else desired,
                    scale = (w / bw).coerceIn(0.25f, 1f),
                    cx = ((r[0] + r[2]) / 2f - 0.5f) * dispW,
                    cy = ((r[1] + r[3]) / 2f - 0.5f) * dispH
                )
            }
        }
        var ratioPreset by remember { mutableStateOf(restored?.preset) }
        var boxScale by remember { mutableFloatStateOf(restored?.scale ?: 1f) }
        var centerX by remember { mutableFloatStateOf(restored?.cx ?: 0f) }  // 相对图片中心，px
        var centerY by remember { mutableFloatStateOf(restored?.cy ?: 0f) }
        val effectiveAspect = aspect ?: ratioPreset

        // 基准选框：有比例时为内切图片的同比例最大矩形；无比例（全图）时铺满整图
        val baseW: Float
        val baseH: Float
        if (effectiveAspect == null || dispW / dispH <= effectiveAspect) {
            baseW = dispW
            baseH = if (effectiveAspect == null) dispH else dispW / effectiveAspect
        } else {
            baseH = dispH
            baseW = dispH * effectiveAspect
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
            // 自由框选时的比例预设（组件比例锁定时不显示）
            if (aspect == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RATIO_PRESETS.forEach { (label, value) ->
                        FilterChip(
                            selected = ratioPreset == value,
                            onClick = {
                                ratioPreset = value
                                // 换比例后重置选框，避免新旧比例叠加出怪位置
                                boxScale = 1f
                                centerX = 0f
                                centerY = 0f
                            },
                            label = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 形状示意（全图用图片自身比例）
                                    RatioShapeGlyph(value ?: fullRatio)
                                    Spacer(Modifier.width(6.dp))
                                    Text(label)
                                }
                            }
                        )
                    }
                }
            }
            Text(
                if (aspect == null) "拖动移动选框，双指缩放调整范围；所选区域会铺满组件显示"
                else "拖动移动选框，双指缩放调整范围；选框比例与当前组件一致",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = {
                    val r = currentRectFractions()
                    if (pending && !WallpaperImporter.commitPending(context)) {
                        // 挂起文件失效（极少见）：不关闭页面，可取消返回
                        android.widget.Toast.makeText(
                            context, "壁纸保存失败，请重新选择", android.widget.Toast.LENGTH_SHORT
                        ).show()
                        return@Button
                    }
                    // 注意顺序：commitPending 会清空所有裁剪区域，新区域必须在其后写入
                    WidgetPrefs.setCropRect(context, appWidgetId, r[0], r[1], r[2], r[3])
                    (context as? WidgetCropActivity)?.setResult(
                        android.app.Activity.RESULT_OK,
                        android.content.Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    )
                    (context as? WidgetCropActivity)?.lifecycleScope?.launch {
                        // 统一刷新管线：bump 数据版本号保证组件真正重绘（直接 update 可能被静默忽略）
                        runCatching { WidgetUpdater.refresh(context) }
                    }
                    onClose()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("保存") }
        }
    }
}
