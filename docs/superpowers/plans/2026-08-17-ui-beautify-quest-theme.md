# 循环清单 UI 美化（冒险图鉴主题）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 App 浅色主题换成最终选定的「冒险图鉴」方案（紫罗兰默认主题 + 渐变 FAB + 圆角方形分类点 + 彩色数量徽章 + 6dp 渐变色条；"剩 N 天"用浅色胶囊；不做进度环、不做连续天数徽章），并在设置页提供 5 套主题颜色自选、即时生效。仅改视觉与主题配置，不改任何运行逻辑与信息架构。

**Architecture:** 全部改动集中在 UI 层：`ui/theme/Color.kt`+`Theme.kt` 换色板与形状，`ui/components/` 新增 `RemainingChip.kt` 并重构 `TaskCard.kt`，`HomeScreen.kt`/`CalendarScreen.kt` 套用新组件样式，`domain/Models.kt` 更新分类预设色板（仅影响新建分类）。桌面小组件（Glance）有自己的硬编码色值，不受主题改动影响，保持原样。

**Tech Stack:** Kotlin + Jetpack Compose（BOM 2024.12.01，`Modifier.shadow(spotColor=…)` 可用）+ Material 3 + JUnit4。

## Global Constraints

- 不改任何业务/数据/交互逻辑：左滑删除锚点行为、点圆圈完成、分类折叠、结算管线一律不动。
- 小组件（`widget/`）不在范围：不得改它的色值与刷新逻辑。
- 构建/测试命令（Windows Git Bash，必须先设 JAVA_HOME）：
  ```bash
  export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
  /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain
  ```
- adb shell 的 `/sdcard/...` 路径必须加 `MSYS_NO_PATHCONV=1` 前缀。
- 提交纪律：每个任务结束后的 `git commit` 步骤，执行前必须先获得用户当次确认（会话规则优先于计划里的提交步骤）；未确认就跳过提交继续写代码。
- 新语义用纯函数 + JVM 单测落地（本计划只有 `remainingDays` 一处新语义）。

---

### Task 1: 主题预设体系（Color.kt / ThemePresets.kt / Theme.kt）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/theme/Color.kt`（整文件替换为全局共享常量）
- Create: `app/src/main/java/com/gametodolist/ui/theme/ThemePresets.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/theme/Theme.kt`（整文件替换）

**Interfaces:**
- Produces（后续任务依赖这些名字）：
  - `data class ThemePreset(id, label, primary, onPrimaryContainer, primaryContainer, background, surfaceVariant, outline, cardBorder, gradientEnd)`（全部 `Color`，后八个字段名即用途）
  - `val THEME_PRESETS: List<ThemePreset>`、`const val DEFAULT_THEME_ID = "quest"`、`fun themePresetOf(id: String?): ThemePreset`
  - `val LocalThemePreset: ProvidableCompositionLocal<ThemePreset>`（默认 quest；`GameTodoTheme` 内 provide）
  - `GameTodoTheme(themeId: String = DEFAULT_THEME_ID, darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)`
  - 共享常量：`QuestSecondary = #6F6A8F`、`AppSurface = #FFFFFF`、`AppOnSurface = #211D3B`、`AppOnSurfaceVariant = #6F6A8F`、`ExpiredRed = #E06262`、`CompletedGreen = #45C48A`、`UncategorizedGray = #9B96B8`、`UrgentOrange = #FF9A5C`、`UrgentOrangeDark = #E8763A`
  - 卡片描边走 `colorScheme.outlineVariant`（= preset.cardBorder），卡片投影用 `colorScheme.primary`，FAB 渐变尾色用 `LocalThemePreset.current.gradientEnd`——**不再有 CardBorder/VioletShadow/FabGlow 等常量**

- [ ] **Step 1: 替换 Color.kt 为共享常量**

```kotlin
package com.gametodolist.ui.theme

import androidx.compose.ui.graphics.Color

// 全局共享色（不随主题预设变化）
val QuestSecondary = Color(0xFF6F6A8F)

val AppSurface = Color(0xFFFFFFFF)
val AppOnBackground = Color(0xFF211D3B)
val AppOnSurface = Color(0xFF211D3B)
val AppOnSurfaceVariant = Color(0xFF6F6A8F)

val ExpiredRed = Color(0xFFE06262)        // 已作废
val CompletedGreen = Color(0xFF45C48A)    // 完成态
val UncategorizedGray = Color(0xFF9B96B8) // 未分类默认色

val UrgentOrange = Color(0xFFFF9A5C)      // 剩余 <=2 天胶囊底色
val UrgentOrangeDark = Color(0xFFE8763A)  // 剩余 <=2 天胶囊文字
```

- [ ] **Step 2: 新建 ThemePresets.kt**

```kotlin
package com.gametodolist.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 一套可切换的浅色主题预设；cardBorder 映射到 colorScheme.outlineVariant */
data class ThemePreset(
    val id: String,
    val label: String,
    val primary: Color,
    val onPrimaryContainer: Color,
    val primaryContainer: Color,
    val background: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val cardBorder: Color,
    val gradientEnd: Color
)

const val DEFAULT_THEME_ID = "quest"

val THEME_PRESETS = listOf(
    ThemePreset("quest", "图鉴紫", Color(0xFF6C5CE7), Color(0xFF3A2E8F), Color(0xFFE7E2FF),
        Color(0xFFF6F4FF), Color(0xFFEFECFB), Color(0xFFD8D2F0), Color(0xFFECE8FB), Color(0xFF9D6CFF)),
    ThemePreset("mint", "薄荷绿", Color(0xFF4E8D6E), Color(0xFF0A2E1B), Color(0xFFD0EAD9),
        Color(0xFFF6F8F6), Color(0xFFE8EEE9), Color(0xFFC4D0C7), Color(0xFFE3EAE4), Color(0xFF7FB69E)),
    ThemePreset("sky", "晴空蓝", Color(0xFF4A8FE7), Color(0xFF0B3B78), Color(0xFFDFEBFC),
        Color(0xFFF4F7FD), Color(0xFFE9EFF9), Color(0xFFC6D5EA), Color(0xFFE3EAF7), Color(0xFF7FB3F0)),
    ThemePreset("sakura", "樱花粉", Color(0xFFE87A9A), Color(0xFF6E1E3C), Color(0xFFFCE3EB),
        Color(0xFFFDF4F7), Color(0xFFF9EAF0), Color(0xFFEACBD6), Color(0xFFF6E4EC), Color(0xFFF2A7C0)),
    ThemePreset("mikan", "蜜柑橙", Color(0xFFE8833A), Color(0xFF6B3405), Color(0xFFFCE8D8),
        Color(0xFFFDF7F1), Color(0xFFF8EFE6), Color(0xFFEAD6C4), Color(0xFFF5E8DA), Color(0xFFF2AC6E))
)

fun themePresetOf(id: String?): ThemePreset =
    THEME_PRESETS.firstOrNull { it.id == id } ?: THEME_PRESETS.first()

/** 当前主题预设（FAB 渐变等取 gradientEnd 用），由 GameTodoTheme provide */
val LocalThemePreset = staticCompositionLocalOf { THEME_PRESETS.first() }
```

- [ ] **Step 3: 替换 Theme.kt**

```kotlin
package com.gametodolist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

private fun lightColorsOf(p: ThemePreset) = lightColorScheme(
    primary = p.primary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = p.primaryContainer,
    onPrimaryContainer = p.onPrimaryContainer,
    secondary = QuestSecondary,
    secondaryContainer = p.surfaceVariant,
    tertiary = UrgentOrange,
    background = p.background,
    onBackground = AppOnBackground,
    surface = AppSurface,
    onSurface = AppOnSurface,
    surfaceVariant = p.surfaceVariant,
    onSurfaceVariant = AppOnSurfaceVariant,
    outline = p.outline,
    outlineVariant = p.cardBorder
)

// 深色兜底：保持可读即可，主打浅色预设
private val DarkColors = darkColorScheme()

val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(18.dp),  // 卡片圆角 16 -> 18
    large = RoundedCornerShape(22.dp)
)

@Composable
fun GameTodoTheme(
    themeId: String = DEFAULT_THEME_ID,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val preset = themePresetOf(themeId)
    CompositionLocalProvider(LocalThemePreset provides preset) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else lightColorsOf(preset),
            shapes = AppShapes,
            typography = Typography(),
            content = content
        )
    }
}
```

（顶部补 `import androidx.compose.ui.graphics.Color`。）

- [ ] **Step 4: 编译验证**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL（`GameTodoTheme` 新增参数有默认值，MainActivity/WidgetCropActivity 现调用处无需改；用 Grep 搜 `Mint|Sage|Apricot` 确认无残留引用）

- [ ] **Step 5: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/ui/theme/
git commit -m "style: 主题预设体系（5 套浅色系，默认图鉴紫）"
```

---

### Task 2: remainingDays 纯函数 + JVM 单测（TDD）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/Formatters.kt`（在 `remainingLabel` 后追加）
- Test: `app/src/test/java/com/gametodolist/FormattersTest.kt`（新建）

**Interfaces:**
- Produces: `fun remainingDays(windowStart: Long, windowEnd: Long, referenceMillis: Long = System.currentTimeMillis()): Long?`——长期有效/尚未开始返回 `null`；今日截止返回 `0`；已截止返回负数。Task 3 与 Task 5 用它判定胶囊颜色。

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/java/com/gametodolist/FormattersTest.kt`：

```kotlin
package com.gametodolist

import com.gametodolist.ui.remainingDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FormattersTest {
    private val zone: ZoneId = ZoneId.systemDefault()
    private fun LocalDate.dayMillis() = atStartOfDay(zone).toInstant().toEpochMilli()
    private val today: LocalDate = LocalDate.now()

    @Test
    fun `长期有效返回 null`() {
        assertNull(remainingDays(today.dayMillis(), Long.MAX_VALUE))
    }

    @Test
    fun `尚未开始返回 null`() {
        assertNull(remainingDays(today.plusDays(3).dayMillis(), today.plusDays(10).dayMillis()))
    }

    @Test
    fun `今日截止返回 0`() {
        assertEquals(0L, remainingDays(today.dayMillis(), today.dayMillis()))
    }

    @Test
    fun `剩 N 天返回 N`() {
        assertEquals(2L, remainingDays(today.dayMillis(), today.plusDays(2).dayMillis()))
        assertEquals(6L, remainingDays(today.dayMillis(), today.plusDays(6).dayMillis()))
    }

    @Test
    fun `已截止返回负数`() {
        assertEquals(-1L, remainingDays(today.dayMillis(), today.minusDays(1).dayMillis()))
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test --tests com.gametodolist.FormattersTest --console=plain`
Expected: 编译失败，`unresolved reference: remainingDays`

- [ ] **Step 3: 实现 remainingDays**

在 `Formatters.kt` 的 `remainingLabel` 函数之后追加：

```kotlin
/** 剩余天数：长期有效/尚未开始返回 null；已截止返回负数 */
fun remainingDays(
    windowStart: Long,
    windowEnd: Long,
    referenceMillis: Long = System.currentTimeMillis()
): Long? {
    if (windowEnd == Long.MAX_VALUE) return null
    if (windowStart > referenceMillis) return null
    val refDay = Instant.ofEpochMilli(referenceMillis).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(windowEnd).atZone(zone).toLocalDate()
    return ChronoUnit.DAYS.between(refDay, endDay)
}
```

- [ ] **Step 4: 运行确认通过**

Run: 同 Step 2 的 gradle test 命令
Expected: BUILD SUCCESSFUL，5 个测试全过

- [ ] **Step 5: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/ui/Formatters.kt app/src/test/java/com/gametodolist/FormattersTest.kt
git commit -m "feat(ui): remainingDays 纯函数，供剩余天数胶囊判定紧急态"
```

---

### Task 3: RemainingChip 组件 + TaskCard 重构

**Files:**
- Create: `app/src/main/java/com/gametodolist/ui/components/RemainingChip.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/components/TaskCard.kt`（整文件替换，LabelChip/EmptyState 保留在文件尾部）

**Interfaces:**
- Consumes: Task 1 的 `UrgentOrange / UrgentOrangeDark / ExpiredRed / CompletedGreen`、`colorScheme.outlineVariant`（卡片描边）、`colorScheme.primary`（卡片紫色投影的 spotColor）；Task 2 的 `remainingDays(...)`
- Produces（Task 5 的日历页复用）：
  - `@Composable fun RemainingChip(text: String, color: Color, container: Color = color.copy(alpha = 0.12f))`
  - `fun remainingChipColors(days: Long?, categoryColor: Color): Pair<Color, Color>`（返回 文字色 to 底色）

- [ ] **Step 1: 新建 RemainingChip.kt**

```kotlin
package com.gametodolist.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gametodolist.ui.theme.ExpiredRed
import com.gametodolist.ui.theme.UrgentOrange
import com.gametodolist.ui.theme.UrgentOrangeDark

/** 剩余天数胶囊配色：已截止红、<=2 天紧急橙、其余分类色；返回 文字色 to 底色 */
fun remainingChipColors(days: Long?, categoryColor: Color): Pair<Color, Color> = when {
    days == null -> categoryColor to categoryColor.copy(alpha = 0.12f)
    days < 0 -> ExpiredRed to ExpiredRed.copy(alpha = 0.12f)
    days <= 2 -> UrgentOrangeDark to UrgentOrange.copy(alpha = 0.20f)
    else -> categoryColor to categoryColor.copy(alpha = 0.12f)
}

/** 剩余天数胶囊：浅色版——浅色底 + 强调色文字，全圆角 */
@Composable
fun RemainingChip(text: String, color: Color, container: Color = color.copy(alpha = 0.12f)) {
    Surface(color = container, shape = CircleShape) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
        )
    }
}
```

- [ ] **Step 2: 重构 TaskCard.kt**

完整替换 `app/src/main/java/com/gametodolist/ui/components/TaskCard.kt` 为：

```kotlin
package com.gametodolist.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gametodolist.data.TemplateWithInstance
import com.gametodolist.domain.RecurrenceType
import com.gametodolist.ui.recurrenceLabel
import com.gametodolist.ui.remainingDays
import com.gametodolist.ui.remainingLabel
import com.gametodolist.ui.theme.CompletedGreen

/** 任务卡片：左侧渐变分类色条 + 圆形勾选 + 标题/备注 + 剩余天数胶囊；completed 时标题划线 */
@Composable
fun TaskCard(
    item: TemplateWithInstance,
    categoryColor: Color,
    onComplete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    completed: Boolean = false
) {
    val template = item.template
    val instance = item.activeInstance

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = MaterialTheme.shapes.medium,
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
            )
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Box {
            // 左侧 6dp 渐变色条，贴卡片左缘
            val barColor = if (completed) categoryColor.copy(alpha = 0.45f) else categoryColor
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(6.dp)
                    .height(44.dp)
                    .background(
                        Brush.verticalGradient(listOf(barColor, barColor.copy(alpha = 0.55f))),
                        RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                    )
            )
            Row(
                modifier = Modifier.padding(start = 18.dp, top = 12.dp, end = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 圆形完成按钮：未完成=分类色描边空心圆，已完成=绿底白勾（点击撤销完成）
                if (completed) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(CompletedGreen, CircleShape)
                            .clickable(
                                onClickLabel = "撤销完成",
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onClick = onComplete
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .border(2.5.dp, categoryColor, CircleShape)
                            .clickable(
                                onClickLabel = "完成",
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onClick = onComplete
                            )
                    )
                }
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = template.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDecoration = if (completed) TextDecoration.LineThrough else null
                        ),
                        color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (template.note.isNotBlank()) {
                        Text(
                            text = template.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (template.recurrenceType != RecurrenceType.NONE.name) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = template.recurrenceLabel(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // 右侧：剩余天数胶囊（<=2 天橙、已截止红、其余分类色）；已完成显示绿色「已完成」
                if (instance != null) {
                    Spacer(Modifier.width(10.dp))
                    if (completed) {
                        RemainingChip("已完成", CompletedGreen)
                    } else {
                        val (fg, bg) = remainingChipColors(
                            remainingDays(instance.windowStart, instance.windowEnd),
                            categoryColor
                        )
                        RemainingChip(
                            remainingLabel(instance.windowStart, instance.windowEnd),
                            fg, bg
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LabelChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = color.copy(alpha = 0.14f),
        shape = CircleShape
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "🌿", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

- [ ] **Step 3: 编译 + 全量单测**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/ui/components/
git commit -m "style: 任务卡片图鉴化（渐变色条/描边勾选圈/剩余天数胶囊）"
```

---

### Task 4: HomeScreen（分区标题胶囊 / 分类组头 / 渐变 FAB / 已作废卡片同步）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/home/HomeScreen.kt:107-127`（topBar/FAB）、`:155,172,190`（sectionHeader 调用）、`:243-252`（sectionHeader 定义）、`:296-332`（CategoryHeader）、`:438-473`（ExpiredTaskCard）

**Interfaces:**
- Consumes: Task 1 的 `LocalThemePreset`（FAB 渐变尾色）与 `colorScheme.primary/outlineVariant`；Task 3 的 `RemainingChip`
- Produces: `@Composable fun GradientFab(onClick: () -> Unit, modifier: Modifier = Modifier)`（`ui/components/GradientFab.kt`，Task 5 日历页复用）

- [ ] **Step 1: 渐变 FAB（抽成共用组件 GradientFab）**

新建 `app/src/main/java/com/gametodolist/ui/components/GradientFab.kt`：

```kotlin
package com.gametodolist.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.gametodolist.ui.theme.LocalThemePreset

/** 渐变 FAB：主色→预设渐变尾色，带主色光晕；主页/日历页共用 */
@Composable
fun GradientFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val glow = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .size(56.dp)
            .shadow(10.dp, RoundedCornerShape(20.dp), spotColor = glow.copy(alpha = 0.35f), ambientColor = glow.copy(alpha = 0.35f))
            .background(
                Brush.linearGradient(listOf(glow, LocalThemePreset.current.gradientEnd)),
                RoundedCornerShape(20.dp)
            )
            .clickable(onClickLabel = "新增任务", role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
    }
}
```

HomeScreen 的 `floatingActionButton` 块（`:123-127`）替换为：

```kotlin
floatingActionButton = {
    GradientFab(onClick = { onEditTask(null) })
}
```

（HomeScreen 需新增 import：`com.gametodolist.ui.components.GradientFab`；移除 `FloatingActionButton` import。topBar 标题 `"循环清单"` 颜色 `colorScheme.primary` 跟随主题，不动。）

- [ ] **Step 2: 分区标题 + 计数胶囊**

`sectionHeader`（`:243-252`）替换为：

```kotlin
private fun LazyListScope.sectionHeader(title: String, count: Int, unit: String) {
    item(key = "section_$title") {
        Row(
            modifier = Modifier.padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(8.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Text(
                    text = "$count $unit",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }
    }
}
```

三处调用改为：`sectionHeader("未完成", …, "项待办")`、`sectionHeader("已完成", …, "项")`、`sectionHeader("已作废", …, "项")`。（需新增 import：`androidx.compose.material3.Surface`、`androidx.compose.ui.text.font.FontWeight`。）

- [ ] **Step 3: 分类组头（圆角方形点 + 彩色数量徽章）**

`CategoryHeader`（`:296-332`）中：

- 圆点由 `size(10.dp) + CircleShape` 改为 `size(12.dp) + RoundedCornerShape(4.dp)`
- 分类名加粗：`style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)`
- 数量文本（`:320-324`）替换为彩色徽章：

```kotlin
Surface(color = group.color().copy(alpha = 0.14f), shape = CircleShape) {
    Text(
        text = "${group.items.size}",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = group.color(),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}
```

- 折叠箭头（KeyboardArrowUp/Down）与整行 clickable 保持不变。

- [ ] **Step 4: ExpiredTaskCard 同步新卡片语言**

`ExpiredTaskCard`（`:438-473`）：`Card` 加 `border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)` 与 `elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)`；色条由 4dp 单色改为与 TaskCard 一致的 6dp 渐变（两端色 `color.copy(alpha = 0.45f)` 与 `color.copy(alpha = 0.25f)`，`RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)`）；其余（划线标题、`LabelChip("已截止作废", …)`、"重新开启"按钮）不动。（需新增 import：`androidx.compose.foundation.BorderStroke`、`androidx.compose.material3.CardDefaults`、`androidx.compose.foundation.shape.RoundedCornerShape`、`androidx.compose.ui.graphics.Brush`。）

- [ ] **Step 5: 编译 + 全量单测**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/ui/home/HomeScreen.kt
git commit -m "style: 主页图鉴化（分区计数胶囊/分类徽章/渐变FAB）"
```

---

### Task 5: 日历页 DayItemCard 同步 + 分类预设色板更新

**Files:**
- Modify: `app/src/main/java/com/gametodolist/ui/calendar/CalendarScreen.kt:267-322`（DayItemCard）
- Modify: `app/src/main/java/com/gametodolist/domain/Models.kt:20-30`（CATEGORY_COLOR_PRESETS）

**Interfaces:**
- Consumes: Task 3 的 `RemainingChip`、`remainingChipColors`；Task 2 的 `remainingDays`；Task 1 的 `colorScheme.outlineVariant`；Task 4 的 `GradientFab`
- Produces: 无新接口

- [ ] **Step 0: FAB 换 GradientFab**

`floatingActionButton` 块（`:92-97`）替换为 `GradientFab(onClick = { onAddTask(selectedDate.toEpochMillis()) })`，import `com.gametodolist.ui.components.GradientFab`，移除 `FloatingActionButton` import。

- [ ] **Step 1: DayItemCard 同步**

- 左侧圆点 `size(10.dp) + CircleShape` 改为 `size(12.dp) + RoundedCornerShape(4.dp)`
- `Card` 加 `border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)`
- 右侧剩余天数 `Text`（`:309-318`）替换为胶囊：

```kotlin
else -> {
    val (fg, bg) = remainingChipColors(
        remainingDays(dayItem.windowStart, dayItem.windowEnd, selectedDate.toEpochMillis()),
        color
    )
    RemainingChip(
        remainingLabel(dayItem.windowStart, dayItem.windowEnd, selectedDate.toEpochMillis()),
        fg, bg
    )
}
```

- 「已完成」「已作废」的 `LabelChip` 不动（Task 3 已把 LabelChip 改成全圆胶囊）。

（CalendarScreen 需新增 import：`androidx.compose.foundation.BorderStroke`、`androidx.compose.foundation.shape.RoundedCornerShape`、`com.gametodolist.ui.components.RemainingChip`、`com.gametodolist.ui.components.remainingChipColors`、`com.gametodolist.ui.remainingDays`。）

- [ ] **Step 2: 分类预设色板换图鉴色系**

`Models.kt:20-30` 替换为（只影响新建分类可选色，已存分类色值不迁移）：

```kotlin
/** 预设分类色板（图鉴色系） */
val CATEGORY_COLOR_PRESETS: List<Long> = listOf(
    0xFF5B8DEF, // 图鉴蓝
    0xFFFF7BAC, // 糖果粉
    0xFFF7B733, // 金币黄
    0xFF45C48A, // 通关绿
    0xFF9D6CFF, // 魔法紫
    0xFF4ECDC4, // 水晶青
    0xFFFF9A5C, // 活力橙
    0xFF9E9E9E  // 暖灰
)
```

- [ ] **Step 3: 全量测试 + 编译**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL（ModelsTest 不引用色板常量，不受影响）

- [ ] **Step 4: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/ui/calendar/CalendarScreen.kt app/src/main/java/com/gametodolist/domain/Models.kt
git commit -m "style: 日历页同步胶囊与方点，分类色板换图鉴色系"
```

---

### Task 6: 主题颜色自选接入（SettingsPrefs / ViewModel / Activity / 设置页色板）

**Files:**
- Modify: `app/src/main/java/com/gametodolist/data/SettingsPrefs.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/com/gametodolist/ui/settings/SettingsScreen.kt`（在"删除清单前提醒"行之后插入）
- Modify: `app/src/main/java/com/gametodolist/ui/MainActivity.kt`
- Modify: `app/src/main/java/com/gametodolist/widget/WidgetCropActivity.kt`（仅 GameTodoTheme 调用处）

**Interfaces:**
- Consumes: Task 1 的 `THEME_PRESETS / themePresetOf / LocalThemePreset` / `GameTodoTheme(themeId=…)`
- Produces: `SettingsPrefs.themeId: StateFlow<String>`（未设置时为 `""`，`themePresetOf("")` 回退默认紫）、`SettingsPrefs.setThemeId(id: String)`、`SettingsViewModel.setThemeId(id: String)`、`WidgetSettingsUiState.themeId: String`

- [ ] **Step 1: SettingsPrefs 增加 themeId**

`SettingsPrefs.kt` 的伴生对象加 `private const val KEY_THEME_ID = "theme_id"`，类内加：

```kotlin
private val _themeId = MutableStateFlow(prefs.getString(KEY_THEME_ID, "") ?: "")

/** 主题预设 id（"" = 默认图鉴紫），设置页色板切换 */
val themeId: StateFlow<String> = _themeId.asStateFlow()

fun setThemeId(id: String) {
    prefs.edit().putString(KEY_THEME_ID, id).apply()
    _themeId.value = id
}
```

- [ ] **Step 2: SettingsViewModel 透传**

`WidgetSettingsUiState` 加字段 `val themeId: String = ""`；`loadState()` 里加 `themeId = settingsPrefs.themeId.value`；新增：

```kotlin
fun setThemeId(id: String) {
    settingsPrefs.setThemeId(id)
    _uiState.update { it.copy(themeId = id) }
}
```

- [ ] **Step 3: 设置页"主题颜色"色板**

`SettingsScreen.kt` 在"删除清单前提醒" Row 之后、精确闹钟提示卡之前插入：

```kotlin
// ---- 主题颜色 ----
Text("主题颜色", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    THEME_PRESETS.forEach { preset ->
        val selected = state.themeId.ifEmpty { DEFAULT_THEME_ID } == preset.id
        Box(
            modifier = Modifier
                .size(40.dp)
                .then(if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                .padding(if (selected) 4.dp else 0.dp)
                .background(Brush.linearGradient(listOf(preset.primary, preset.gradientEnd)), CircleShape)
                .clickable(onClickLabel = preset.label, role = androidx.compose.ui.semantics.Role.Button) {
                    viewModel.setThemeId(preset.id)
                },
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}
```

（需新增 import：`com.gametodolist.ui.theme.THEME_PRESETS / DEFAULT_THEME_ID`、`androidx.compose.foundation.border`、`androidx.compose.foundation.shape.CircleShape`、`androidx.compose.material.icons.Icons / filled.Check`、`androidx.compose.ui.graphics.Brush`。效果参考 `docs/design/plan-c-settings.png` 的色板行。）

- [ ] **Step 4: Activity 接入主题**

`MainActivity.kt`：类内加 `@Inject lateinit var settingsPrefs: SettingsPrefs`；`setContent` 改为：

```kotlin
setContent {
    val themeId by settingsPrefs.themeId.collectAsStateWithLifecycle()
    GameTodoTheme(themeId = themeId) {
        AppNav()
    }
}
```

（需 import：`javax.inject.Inject`、`com.gametodolist.data.SettingsPrefs`、`androidx.lifecycle.compose.collectAsStateWithLifecycle`、`androidx.compose.runtime.getValue`。）

`WidgetCropActivity.kt`：找到 `GameTodoTheme { ... }` 调用处；该类若是 `@AndroidEntryPoint` 则同样 `@Inject lateinit var settingsPrefs: SettingsPrefs` 并传 `themeId`，否则用文件内已有的 `WidgetEntryPoint` 模式经 `EntryPointAccessors` 取 `SettingsPrefs`（在 `WidgetEntryPoint` 接口加 `fun settingsPrefs(): SettingsPrefs`），`remember + collectAsStateWithLifecycle` 后传入。

- [ ] **Step 5: 编译 + 全量单测**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit（需用户确认）**

```bash
git add app/src/main/java/com/gametodolist/data/SettingsPrefs.kt app/src/main/java/com/gametodolist/ui/settings/ app/src/main/java/com/gametodolist/ui/MainActivity.kt app/src/main/java/com/gametodolist/widget/WidgetCropActivity.kt app/src/main/java/com/gametodolist/widget/WidgetEntryPoint.kt
git commit -m "feat: 设置页主题颜色自选（5 套预设，即时生效）"
```

---

### Task 7: 模拟器装机截图验证

**Files:**
- 无代码改动（发现偏差才回到对应任务修）

- [ ] **Step 1: 全量验证**

Run: `export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" && /d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain`
Expected: BUILD SUCCESSFUL，全部单测通过

- [ ] **Step 2: 装模拟器并截图**

```bash
export PATH="$PATH:/c/Users/da270/AppData/Local/Android/Sdk/platform-tools:/c/Users/da270/AppData/Local/Android/Sdk/emulator"
emulator -avd Pixel_6 &   # 已在运行则跳过
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.gametodolist/.ui.MainActivity
sleep 3
adb exec-out screencap -p > /tmp/home.png
```

主页截图后用 ReadMediaFile 读 `C:\Users\da270\AppData\Local\Temp\home.png`，对照 `docs/design/plan-c-quest-log.png` 检查：紫色主题、渐变 FAB、圆角方形分类点、数量徽章、渐变色条、剩余天数胶囊（含 ≤2 天橙色态）；再切日历页同样截图验证。最后进设置页点"主题颜色"色板（如薄荷绿），回主页确认全 App 换色即时生效，再切回图鉴紫。

- [ ] **Step 3: 更新文档**

若配色/形状常量或组件结构有变，同步 `AGENTS.md` 中「架构地图-UI」与约定段落的措辞（如"清新薄荷绿"描述）。

- [ ] **Step 4: Commit（需用户确认）**

```bash
git add AGENTS.md docs/design/
git commit -m "docs: 主题换图鉴配色，同步约定文档与设计稿"
```
