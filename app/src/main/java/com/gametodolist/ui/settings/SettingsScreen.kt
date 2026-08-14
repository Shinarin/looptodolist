package com.gametodolist.ui.settings

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gametodolist.widget.WidgetPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onManageCategories: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wallpaper by viewModel.wallpaper.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 从系统设置页返回后刷新精确闹钟授权状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshExactAlarmState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(viewModel::setWallpaper) }

    Scaffold(
        topBar = {
            // 紧凑标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---- 分类管理入口 ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onManageCategories)
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("分类管理", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()
            Spacer(Modifier.height(4.dp))

            // ---- 精确闹钟授权提示（仅未授权时显示） ----
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

            // ---- 桌面小组件 ----
            Text("桌面小组件", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

            Text("背景样式", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val modes = listOf(WidgetPrefs.BG_SOLID, WidgetPrefs.BG_WALLPAPER)
                val labels = listOf("纯色", "壁纸")
                modes.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.bgMode == mode,
                        onClick = { viewModel.setBgMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                    ) { Text(labels[index]) }
                }
            }

            when (state.bgMode) {
                WidgetPrefs.BG_SOLID -> LabeledSlider(
                    label = "透明度 ${state.solidAlpha}%",
                    value = state.solidAlpha / 100f,
                    onChange = { viewModel.setSolidAlpha((it * 100).toInt()) },
                    onFinished = viewModel::refreshWidget
                )
                WidgetPrefs.BG_WALLPAPER -> WallpaperSection(
                    state = state,
                    wallpaper = wallpaper,
                    onPick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onClear = viewModel::clearWallpaper,
                    onDimChange = { viewModel.setWallpaperDim(100 - (it * 100).toInt()) },
                    onDimFinished = viewModel::refreshWidget
                )
            }

            Text(
                "说明：修改即时生效，也可点小组件右上角刷新按钮手动同步；点组件头部的裁剪按钮可框选壁纸显示区域。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ---- 壁纸设置：亮度滑块 + 效果预览 ----
@Composable
private fun WallpaperSection(
    state: WidgetSettingsUiState,
    wallpaper: Bitmap?,
    onPick: () -> Unit,
    onClear: () -> Unit,
    onDimChange: (Float) -> Unit,
    onDimFinished: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onPick) { Text(if (state.hasWallpaper) "更换壁纸" else "选择壁纸") }
            if (state.hasWallpaper) {
                TextButton(onClick = onClear) { Text("清除") }
            }
        }

        if (state.hasWallpaper && wallpaper != null) {
            LabeledSlider(
                label = "亮度（越亮越好读） ${100 - state.wallpaperDim}%",
                value = (100 - state.wallpaperDim) / 100f,
                onChange = onDimChange,
                onFinished = onDimFinished
            )

            // 亮度效果概览（裁剪区域与组件一致）
            Text("效果预览", style = MaterialTheme.typography.labelLarge)
            WallpaperPreview(
                bitmap = wallpaper,
                dim = state.wallpaperDim / 100f,
                cropRect = state.cropRect
            )
        } else {
            Text(
                if (state.wallpaperError) "壁纸读取失败，请重新选择"
                else "尚未设置壁纸，点击上方按钮选择一张图片",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 壁纸 + 亮度遮罩 + 示例文字的概览；有裁剪区域时按组件同款区域显示。
 *  预览为页面宽 60% 的居中方形，图片居中最大裁剪（1:1），不拉伸变形 */
@Composable
private fun WallpaperPreview(bitmap: Bitmap, dim: Float, cropRect: List<Float>?) {
    val onDark = dim >= 0.25f
    val textPrimary = if (onDark) Color(0xFFF5F7F5) else Color(0xFF1A2420)
    val textSecondary = if (onDark) Color(0xCCF5F7F5) else Color(0xFF5A6B61)

    // 与小组件一致的裁剪：自定义区域优先，否则用原图
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
                    bitmap.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
        ) {
            cropped?.let { preview ->
                Image(
                    bitmap = preview,
                    contentDescription = "壁纸亮度预览",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "晨跑 5 公里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = textPrimary
                )
                Text(
                    "背单词 20 个",
                    style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.LineThrough),
                    color = textSecondary
                )
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onFinished)
    }
}
