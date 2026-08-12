package com.gametodolist.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.datastore.preferences.core.Preferences
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.gametodolist.R
import com.gametodolist.data.CategoryGroup
import com.gametodolist.data.HomeData
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.domain.WallpaperCrop
import com.gametodolist.ui.MainActivity
import com.gametodolist.ui.remainingLabel
import kotlinx.coroutines.flow.first

/** 「已完成」区展开状态在 Glance Preferences 里的键（TodoWidget 与 ToggleShowCompletedAction 共用） */
val KEY_SHOW_COMPLETED = androidx.datastore.preferences.core.booleanPreferencesKey("show_completed")

/**
 * 桌面小组件：可拖拽调整大小。
 * 背景支持纯色（调透明度）/ 壁纸（调亮度、点头部裁剪按钮框选显示区域），在 App 设置页修改。
 * 列表可上下滑动；按分类分组显示组名；点击任务行即完成/取消完成，与 App 内状态实时一致；
 * 「已完成」区默认收起，点标题展开；右上角刷新按钮可手动同步。
 */
class TodoWidget : GlanceAppWidget() {

    // Exact：获取真实尺寸，保证壁纸按实际宽高比裁剪
    override val sizeMode = SizeMode.Exact

    // 展开/收起等交互状态走 Glance 原子状态，避免与 SharedPreferences 产生读写竞争
    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 注意：这里只做轻量读取，结算在 App/Worker/刷新按钮里做，保证刷新速度
        val entryPoint = WidgetEntryPoint.get(context)
        val homeData = entryPoint.repository().observeHomeData().first()

        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrDefault(AppWidgetManager.INVALID_APPWIDGET_ID)

        // 外观设置
        val bgMode = WidgetPrefs.bgMode(context)
        val solidAlpha = WidgetPrefs.solidAlpha(context)
        val wallpaperDim = WidgetPrefs.wallpaperDim(context)
        val wallpaper: Bitmap? = if (bgMode == WidgetPrefs.BG_WALLPAPER) WallpaperCache.get(context) else null
        val cropRect = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetPrefs.cropRect(context, appWidgetId)
        } else null

        provideContent {
            val size = LocalSize.current
            val compact = size.height < 160.dp
            val aspect = if (size.height.value > 0) size.width.value / size.height.value else 1f
            val showCompleted = currentState<Preferences>()[KEY_SHOW_COMPLETED] ?: false
            // 壁纸：优先该实例自定义的裁剪区域，否则按当前宽高比居中裁剪
            val croppedWallpaper = wallpaper?.let { src ->
                runCatching {
                    if (cropRect != null) {
                        Bitmap.createBitmap(
                            src,
                            (cropRect[0] * src.width).toInt().coerceIn(0, src.width - 1),
                            (cropRect[1] * src.height).toInt().coerceIn(0, src.height - 1),
                            ((cropRect[2] - cropRect[0]) * src.width).toInt()
                                .coerceIn(1, src.width - (cropRect[0] * src.width).toInt()),
                            ((cropRect[3] - cropRect[1]) * src.height).toInt()
                                .coerceIn(1, src.height - (cropRect[1] * src.height).toInt())
                        )
                    } else {
                        val r = WallpaperCrop.cropRect(src.width, src.height, aspect, 0.5f, 0.5f)
                        Bitmap.createBitmap(src, r[0], r[1], r[2], r[3])
                    }
                }.getOrNull()
            }

            WidgetContent(
                homeData = homeData,
                compact = compact,
                context = context,
                showCompleted = showCompleted,
                appWidgetId = appWidgetId,
                aspect = aspect,
                style = WidgetStyle(
                    bgMode = if (croppedWallpaper != null) WidgetPrefs.BG_WALLPAPER else bgMode,
                    solidAlpha = solidAlpha,
                    wallpaperDim = wallpaperDim,
                    wallpaper = croppedWallpaper
                )
            )
        }
    }
}

/** 壁纸位图缓存：按文件修改时间+长度失效，避免每次刷新都解码 JPEG */
private object WallpaperCache {
    @Volatile private var key: String? = null
    @Volatile private var cached: Bitmap? = null

    @Synchronized
    fun get(context: Context): Bitmap? {
        val f = WidgetPrefs.wallpaperFile(context)
        if (!WidgetPrefs.hasWallpaper(context)) {
            key = null
            cached = null
            return null
        }
        val k = "${f.lastModified()}_${f.length()}"
        if (k == key) return cached
        val b = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
        key = k
        cached = b
        return b
    }
}

private class WidgetStyle(
    val bgMode: Int,
    val solidAlpha: Float,
    val wallpaperDim: Float,
    val wallpaper: Bitmap?
) {
    val isWallpaper: Boolean get() = bgMode == WidgetPrefs.BG_WALLPAPER && wallpaper != null
    val onDark: Boolean get() = isWallpaper && wallpaperDim >= 0.25f
    // 壁纸且压得较暗时用浅色文字保证可读性
    val textPrimary: Color
        get() = if (onDark) Color(0xFFF5F7F5) else Color(0xFF1A2420)
    val textSecondary: Color
        get() = if (onDark) Color(0xCCF5F7F5) else Color(0xFF5A6B61)
    val accent: Color
        get() = if (onDark) Color(0xFFA8D5BA) else Color(0xFF4E8D6E)
    /** 分组容器底色 */
    val cardTint: Color
        get() = if (onDark) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.62f)
}

private val SolidBg = Color(0xFFF6F8F6)

@androidx.compose.runtime.Composable
private fun WidgetContent(
    homeData: HomeData,
    compact: Boolean,
    context: Context,
    showCompleted: Boolean,
    appWidgetId: Int,
    aspect: Float,
    style: WidgetStyle
) {
    Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(16.dp)) {
        // ---- 背景层 ----
        if (style.isWallpaper) {
            Image(
                provider = ImageProvider(style.wallpaper!!),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxSize()
            )
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = style.wallpaperDim))
            ) {}
        } else {
            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .background(SolidBg.copy(alpha = style.solidAlpha))
            ) {}
        }

        // ---- 内容层 ----
        Column(modifier = GlanceModifier.fillMaxSize().padding(12.dp)) {
            // 头部：标题（点击打开 App）+ 项数 + 刷新 + 壁纸裁剪
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "循环清单",
                    style = TextStyle(
                        color = ColorProvider(style.textPrimary),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = GlanceModifier.clickable(
                        actionStartActivity(Intent(context, MainActivity::class.java))
                    )
                )
                Spacer(GlanceModifier.defaultWeight())
                val total = homeData.activeGroups.sumOf { it.items.size }
                Text(
                    "$total 项",
                    style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 12.sp)
                )
                if (style.isWallpaper && appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    Spacer(GlanceModifier.width(2.dp))
                    // 裁剪本组件的壁纸显示区域
                    HeaderIcon(
                        resId = R.drawable.ic_crop,
                        contentDesc = "裁剪壁纸",
                        tint = style.textSecondary,
                        onClick = actionStartActivity(
                            Intent(context, WidgetCropActivity::class.java)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                                .putExtra(WidgetCropActivity.EXTRA_ASPECT, aspect)
                        )
                    )
                }
                Spacer(GlanceModifier.width(2.dp))
                HeaderIcon(
                    resId = R.drawable.ic_refresh,
                    contentDesc = "刷新",
                    tint = style.textSecondary,
                    onClick = actionRunCallback<RefreshWidgetAction>()
                )
            }
            Spacer(GlanceModifier.height(6.dp))

            val activeTotal = homeData.activeGroups.sumOf { it.items.size }
            val completedTotal = homeData.completedGroups.sumOf { it.items.size }

            if (activeTotal == 0 && completedTotal == 0) {
                Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无任务 🌿",
                        style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 13.sp)
                    )
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    // ---- 未完成：按分类分组，显示组名 ----
                    homeData.activeGroups.forEach { group ->
                        val key = group.category?.id ?: -1L
                        item(itemId = 100_000L + key) {
                            GroupCard(group = group, compact = compact, completed = false, style = style)
                        }
                        item(itemId = 300_000L + key) { Spacer(GlanceModifier.height(8.dp)) }
                    }

                    // ---- 已完成：可展开/收起 ----
                    if (completedTotal > 0) {
                        item(itemId = Long.MAX_VALUE - 1) {
                            CompletedHeader(total = completedTotal, expanded = showCompleted, style = style)
                        }
                        if (showCompleted) {
                            homeData.completedGroups.forEach { group ->
                                val key = group.category?.id ?: -1L
                                item(itemId = 200_000L + key) {
                                    GroupCard(group = group, compact = compact, completed = true, style = style)
                                }
                                item(itemId = 400_000L + key) { Spacer(GlanceModifier.height(8.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 头部小图标按钮：放大点击区域 */
@androidx.compose.runtime.Composable
private fun HeaderIcon(
    resId: Int,
    contentDesc: String,
    tint: Color,
    onClick: androidx.glance.action.Action
) {
    Box(
        modifier = GlanceModifier
            .size(30.dp)
            .clickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(resId),
            contentDescription = contentDesc,
            colorFilter = androidx.glance.ColorFilter.tint(ColorProvider(tint)),
            modifier = GlanceModifier.size(18.dp)
        )
    }
}

private fun CategoryGroup.colorLong(): Long? = category?.color

/** 「已完成」区标题栏：点击展开/收起 */
@androidx.compose.runtime.Composable
private fun CompletedHeader(total: Int, expanded: Boolean, style: WidgetStyle) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clickable(actionRunCallback<ToggleShowCompletedAction>()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "已完成 · $total",
            style = TextStyle(
                color = ColorProvider(style.accent),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            if (expanded) "收起 ▲" else "展开 ▼",
            style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp)
        )
    }
}

/** 一个分类的分组卡片：组名（色点 + 名称 + 数量）+ 任务行 */
@androidx.compose.runtime.Composable
private fun GroupCard(
    group: CategoryGroup,
    compact: Boolean,
    completed: Boolean,
    style: WidgetStyle
) {
    val content: @androidx.compose.runtime.Composable () -> Unit = {
        GroupHeader(group, style)
        group.items.take(if (compact) 6 else 8).forEach { item ->
            TaskRow(item, group.colorLong(), completed = completed, style = style)
        }
    }
    if (compact) {
        // 小尺寸：扁平排列，节省纵向空间
        Column(modifier = GlanceModifier.fillMaxWidth()) { content() }
    } else {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(style.cardTint)
                .cornerRadius(12.dp)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) { content() }
    }
}

@androidx.compose.runtime.Composable
private fun GroupHeader(group: CategoryGroup, style: WidgetStyle) {
    Row(
        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 分类色点
        Box(
            modifier = GlanceModifier
                .size(7.dp)
                .background(group.colorLong()?.let(::Color) ?: style.accent)
                .cornerRadius(4.dp)
        ) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            group.category?.let { "${it.icon} ${it.name}".trim() } ?: "未分类",
            style = TextStyle(
                color = ColorProvider(group.colorLong()?.let(::Color) ?: style.accent),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(GlanceModifier.defaultWeight())
        Text(
            "${group.items.size}",
            style = TextStyle(color = ColorProvider(style.textSecondary), fontSize = 11.sp)
        )
    }
}

@androidx.compose.runtime.Composable
private fun TaskRow(
    item: TemplateWithInstance,
    categoryColor: Long?,
    completed: Boolean,
    style: WidgetStyle
) {
    val instance = item.activeInstance
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            // 整行点击：完成 / 取消完成，与 App 内状态保持一致
            .then(
                if (instance != null) {
                    GlanceModifier.clickable(
                        if (completed) {
                            actionRunCallback<UncompleteTaskAction>(
                                actionParametersOf(UncompleteTaskAction.INSTANCE_ID_KEY to instance.id)
                            )
                        } else {
                            actionRunCallback<CompleteTaskAction>(
                                actionParametersOf(CompleteTaskAction.INSTANCE_ID_KEY to instance.id)
                            )
                        }
                    )
                } else GlanceModifier
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (instance != null) {
            Image(
                provider = ImageProvider(
                    if (completed) R.drawable.ic_check_circle else R.drawable.ic_circle_outline
                ),
                contentDescription = if (completed) "撤销完成" else "完成",
                modifier = GlanceModifier.size(20.dp)
            )
            Spacer(GlanceModifier.width(8.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                item.template.title,
                style = TextStyle(
                    color = ColorProvider(
                        if (completed) style.textSecondary else style.textPrimary
                    ),
                    fontSize = 13.sp,
                    textDecoration = if (completed) TextDecoration.LineThrough else TextDecoration.None
                ),
                maxLines = 1
            )
            if (!completed && instance != null) {
                Text(
                    remainingLabel(instance.windowStart, instance.windowEnd),
                    style = TextStyle(
                        color = ColorProvider(categoryColor?.let(::Color) ?: style.textSecondary),
                        fontSize = 11.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}
