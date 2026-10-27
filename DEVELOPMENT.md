# 循环清单（Gametodolist）开发文档

> 本文面向接手/维护本项目的开发者，介绍整体架构、各模块职责、关键设计决策与开发约定。
> 快速上手命令见 `AGENTS.md`；本文侧重"为什么这么做"与模块间的衔接关系。

## 1. 项目概述

Android 循环待办清单 App。核心概念是**模板 + 实例**分离：

- **任务模板**（`task_templates`）：用户创建的清单项，保存循环规则（不循环 / 每 N 天 / 每周几 / 每月几日）、显示时段、提醒配置。
- **任务实例**（`task_instances`）：模板每经过一个循环周期生成一条实例，记录该次循环的显示窗口 `[windowStart, windowEnd)` 与状态（`ACTIVE` / `COMPLETED` / `EXPIRED`）。

用户看到的"一个任务"= 模板 + 它的最新实例。到期未完成的实例自动**作废**（EXPIRED）并生成下一循环的新实例；已完成的不消失，划线归入"已完成"区。

**技术栈**：Kotlin + Jetpack Compose（BOM 2024.12.01）+ Room 2.6.1 + Hilt 2.56.2 + WorkManager 2.10 + Glance 1.1.1 桌面小组件。包名 `com.gametodolist`，minSdk 26 / targetSdk 35，JVM 17。

## 2. 构建与测试

```bash
# Windows 必须指定 JDK（系统无独立 Java），Gradle 用项目内解压的 8.10.2
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
/d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain
```

- 依赖仓库已配阿里云镜像（`settings.gradle.kts`）。
- 单元测试在 `app/src/test/java/com/gametodolist/`：
  - **纯函数 JVM 测试**：`RecurrenceEngineTest`（17 例）、`RolloverManagerTest`（13 例）、`WallpaperCropTest`（7 例）、`ModelsTest` —— 领域逻辑全部落在纯函数上，这是本项目的核心测试策略。
  - **Robolectric 集成测试**（真实 Room in-memory）：`MigrationTest`（v1→v2→v3 迁移）、`RoomIntegrationTest`（DAO 语义、"编辑模板不丢实例历史"等数据完整性）。
- Room `exportSchema = true`，schema 快照在 `app/schemas/`，改表结构后必须提交新快照。

## 3. 架构总览

```
ui/ (Compose 页面 + ViewModel)
  │  写操作
  ▼
sync/DataSyncManager.settleAndRefresh()   ← 统一管线（唯一入口）
  │  ① 结算          ② 重排提醒        ③ 刷新桌面组件
  ▼                ▼                  ▼
domain/RolloverManager   notify/ReminderScheduler   widget/WidgetUpdater
  │  纯函数计算
  ▼
domain/RecurrenceEngine（循环规则） / WallpaperCrop（裁剪）
  │
  ▼
data/ (Room: AppDatabase + 3 DAO + TodoRepository)
```

分层规则：

- **domain/**：纯 Kotlin，不依赖 Android（`ZoneId` 由调用方注入），可 JVM 单测。所有"规则语义"只能写在这里。
- **data/**：Room 三表 + Repository。Repository 只做数据存取与组合，不含业务规则。
- **sync/**：唯一的写后处理管线。
- **ui/**：Compose + ViewModel（Hilt）。ViewModel 不直接碰 DAO。
- **widget/**：Glance 小组件，通过 `WidgetEntryPoint`（Hilt EntryPointAccessors）拿到 Repository/DataSyncManager。
- **notify/**：闹钟调度、广播接收、周期 Worker。

## 4. 领域层（domain/）—— 业务语义的核心

### 4.1 RecurrenceEngine（循环引擎，纯函数）

输入模板 + 日期，输出循环起点与显示窗口。四种循环类型：

| 类型 | 循环起点规则 |
|---|---|
| `NONE` | 只有锚点日一个起点（一次性任务） |
| `INTERVAL_DAYS` | 以锚点为基准每 N 天 |
| `WEEKLY` | `weekdaysMask` 位掩码选中的每个星期几（bit0=周一…bit6=周日） |
| `MONTHLY` | 每月 `monthDay` 日，短月钳制到月末 |
| `AFTER_COMPLETION` | 完成后计时（月卡模式）：无日历锚定，恢复日 = 完成日期 + `intervalDays` |

**锚点（anchorDate）**：本地零点毫秒，`<= 0` 表示未设置（回退用 `createdAt`）。WEEKLY/MONTHLY 设置锚点后**首个循环不早于锚点**（之前无循环）。

**显示窗口** `[start, end)`，end 即截止时间：

- `FULL_CYCLE`：[本次循环起点 00:00, 下次循环起点 00:00)。NONE 的 FULL_CYCLE = 长期有效（end = `Long.MAX_VALUE`）。
- `CUSTOM`：[循环起点 00:00, 起点 + N 天 00:00)，N ≥ 1。
- `AFTER_COMPLETION`：恒为 [起点 00:00, 长期有效)，忽略 windowMode。

**完成后计时（AFTER_COMPLETION，月卡模式）**：未完成实例窗口恒为长期有效（不过期不作废）；点完成时实例 `windowEnd` 被改写为「完成日期 + N 天」的本地零点（进入冷却，由 `TodoRepository.completeInstance` 特判，撤销完成改回长期有效）；冷却到期后结算以恢复日（= `completedAt` 日期 + N，`cooldownEndDate`）为起点生成新未完成实例，旧实例保留为已完成历史。编辑间隔天数保存时按原完成日期以新 N 重算恢复日（`regenerateLocked` 末尾特判），恢复日已过则同次结算立即恢复；从其他类型切入且最新实例已完成时按其完成时间起算。提醒无截止时间语义，固定恢复日当天 09:00 触发（`ReminderScheduler` 对该类型单独算触发点）。

三个 public 函数：`currentCycleStartDate`（含某日的循环起点）、`nextCycleStartDate`（下一个起点）、`computeWindow`（算窗口）。WEEKLY 的 `weekdaysMask` 为 0 是非法状态，直接 `require` 抛异常（编辑页已拦截，日历页对异常模板跳过并记日志）。

### 4.2 RolloverManager（结算器）

职责：作废到期未完成的实例、按规则补生成新循环实例。**三个 public 入口共用一把不可重入 Mutex**：

- `rolloverAll()`：全量结算。App 启动（主页/日历 VM 的 init）、WorkManager 周期任务、数据变更管线都会调到。
- `regenerateActiveInstance(template)`：**编辑模板保存后**调用。就地修正当前实例再全量结算。**已作废（EXPIRED）的最新实例会先恢复 ACTIVE 再修正**——所以编辑已作废任务保存即复活。三个分支：
  - 目标循环已有历史实例 → 删旧规则下的"幻影实例"，保留历史（绝不动 COMPLETED；EXPIRED 但窗口仍覆盖现在的会复活并按新规则重算窗口，否则任务卡死在已作废区）；
  - 起点前移 → 旧实例作废弃置留痕，按新规则建新实例；
  - 起点后移且无历史 → 幻影实例就地改挂到新起点（否则主页按最大 cycleStart 取到已作废实例，任务会"隐身"）。
- `reviveTemplate(template)`："重新开启"已作废任务，与 regenerate 同一条路径（Repository 层对不循环任务先把 anchorDate 重置为今天；循环任务按规则算当前循环，无需改开始日期）。

私有 `*Locked` 方法不加锁（Mutex 不可重入，锁只挂在 public 入口）。单次结算最多补 `MAX_CATCH_UP_CYCLES = 500` 个循环，防止长期未打开 App 时爆量插入。

**NONE + 自定义天数**的任务到期作废后不再续期；开始日期设在过去则创建即过期立即作废——这就是主页"已作废"区的来源。

### 4.3 Models / WallpaperCrop

- `Models.kt`：枚举 + `String?.toRecurrenceType()` / `toWindowMode()` 兜底扩展（DB 里非法值安全降级为 NONE / FULL_CYCLE，不会因脏数据崩溃）+ 分类预设色板。
- `WallpaperCrop.kt`：cover 式裁剪窗口纯函数，返回 `[x, y, w, h]`，小组件壁纸按组件实际宽高比裁剪用。

## 5. 数据层（data/）

### 5.1 表结构（DB v3）

- `categories`：名称、颜色（ARGB Long）、icon、sortOrder。
- `task_templates`：规则字段见 §4.1；`sortOrder` 是预留字段（恒 0，未启用拖拽排序）；`customStartMinutes/customEndMinutes` 已废弃（CUSTOM 简化为按天数）。
- `task_instances`：`templateId` 外键 **CASCADE 删除**；索引含 `(templateId, cycleStart)` **唯一索引**（防并发结算重复插入）。

迁移：v1→v2 加 `anchorDate` 列；v2→v3 先去重再加唯一索引。

> ⚠️ **绝不能用 REPLACE 插入模板**——REPLACE 实际是 DELETE+INSERT，会触发实例表 FK CASCADE 清空该模板全部实例历史。模板/分类保存一律走 `TodoRepository` 的 insert/update 分流（`id == 0L` 判断）。实例表的 `insert` 是 REPLACE 但只用于结算器内部撞唯一索引的场景，属设计内行为。

### 5.2 TodoRepository

- 写操作：`upsertCategory` / `saveTemplate`（insert/update 分流）、`deleteCategory`（先把该分类下模板的 categoryId 置 NULL 再删分类）、`deleteTemplate`、`completeInstance` / `uncompleteInstance`、`reviveTemplate`（仅 NONE 类型有效）。
- 读操作（Flow）：
  - `observeHomeData()`：主页数据 = combine（分类、未归档模板、每模板最新实例）→ 按最新实例状态分 **未完成 / 已完成 / 已作废** 三区，每区再按分类分组（未分类组排最后，空组不出现）。
  - `observeLatestInstances()`：每模板最新实例（按 cycleStart 取 MAX），主页/日历/小组件共用。

## 6. 统一管线（sync/DataSyncManager）

```kotlin
suspend fun settleAndRefresh() {
    runCatching { rolloverManager.rolloverAll() }      // ① 结算
    runCatching { reminderScheduler.rescheduleAll() }  // ② 全量重排提醒（只排 ACTIVE）
    WidgetUpdater.refresh(context)                     // ③ 刷新所有桌面组件
}
```

**约定：任何任务/模板/分类的写操作之后，必须调 `settleAndRefresh()`**，不要手工组合三步（防漏步）。三步全量幂等，单步失败记日志互不影响。唯一例外：设置页只改外观时调 `WidgetUpdater.refresh(context)` 即可。

重排提醒是"全量重建"思路：只给当前 ACTIVE 实例排闹钟，刚完成/删除的实例自动不再排；残留的旧闹钟由 `ReminderReceiver` 触发时自清（见 §8）。

## 7. UI 层（ui/）

底部三栏（清单 / 日历 / 设置）+ 两个二级页（分类管理、任务编辑），`AppNav.kt` NavHost，切换动画 fade 150ms。编辑路由 `edit?templateId={}&anchorDate={}`（-1 表示新建 / 未指定日期）。**所有页面统一用紧凑标题栏**（`statusBarsPadding` + 小 padding），不用原生 `TopAppBar`（它比紧凑栏多一截顶部留白）。

- **主页** `home/`：紧凑标题栏 + 三分区（未完成 / 已完成划线 / 已作废可点击编辑、左滑删除、"重新开启"）+ 分类分组折叠（折叠状态在 `HomeViewModel`，切 tab 不丢）。**无右滑手势**（误划问题）；左滑露出红色「删除」按钮停住、点击按钮才触发删除（`SwipeRevealDeleteBox`：AnchoredDraggable 双锚点 Closed/Revealed，自实现，不用 SwipeToDismissBox——它不支持"停住露出"态）。删除警示弹窗带「不再提醒」勾选，关掉后点删除直接删；设置页「删除清单前提醒」开关可恢复（`data/SettingsPrefs.kt`，SharedPreferences `app_settings`）。任务列表底部留白 88dp 避让悬浮添加按钮（日历页同理）。`HomeViewModel.init` 触发一次 `settleAndRefresh()`（即"回 App 即结算"）。
- **日历页** `calendar/`：不查库预演——对每个模板用引擎算所选日期的窗口，覆盖该日即显示；该循环已有真实实例则取真实状态。月视图打点 = 循环更新日。未来日期可看。"回到今天"按钮。点"+"进编辑页时带入所选日期为默认开始日期。
- **编辑页** `edit/`：开始日期（NONE/INTERVAL_DAYS 直接显示日期选择；WEEKLY/MONTHLY 经"指定开始日期"开关，不开则 anchorDate=0 立即开始）、显示时段（整循环 vs 自定义 N 天）、提醒开关与提前量。脏状态返回弹"放弃修改"确认（快照对比，忽略 saving/saved 等过程字段）。编辑模式有删除入口（带确认）。保存后若是编辑，先 `regenerateActiveInstance` 再走统一管线。
- **分类管理** `category/`：增删改 + 色板（FlowRow 横纵都留间距），删除有确认。
- **设置页** `settings/`：小组件外观（纯色透明度 / 壁纸变暗度 / 更换壁纸）+ 精确闹钟授权状态（可跳系统设置）。**更换壁纸与组件头部图片按钮统一走中转页 `WidgetWallpaperPickActivity`**：系统选图 → `WallpaperImporter.saveToPending`（按屏幕长边封顶 1440..3200px、JPEG q92、先读边界按 2 的幂采样防 OOM）→ 直接进裁剪页（挂起模式），保存才生效、取消则旧壁纸原样保留（暂存式提交，见小组件节第 8 条）；返回本页时 ON_RESUME 重读落盘状态刷新预览（`refreshWallpaperState`）。滑块拖动中只落盘（`persist`），松手才刷新组件，避免拖动期间狂刷 RemoteViews。壁纸"效果预览"为页面宽 60% 的居中 1:1 方形，对裁剪区域（无则用原图）做居中最大裁剪，不固定比例拉伸。
- `Formatters.kt`：日期/循环规则/剩余时间文案（"今日截止 / 剩 N 天 / 长期有效"等），UI 与小组件共用。
- `HighRefreshMode.kt`：`Activity.pinHighRefreshMode()`——以 `preferredDisplayModeId` 给窗口钉最接近 120Hz 的显示模式（90Hz 起步挑档），解除荣耀/MagicOS 智能刷新率对第三方应用的 60Hz 渲染封顶与滑动起步的 60→120 爬升卡顿（实测修复后起步即 8.3ms 帧）。`MainActivity` 与 `WidgetCropActivity` 的 `onCreate` 都已调用；**新增独立 Activity 必须同样调用**。

ViewModel 统一模式：写操作包 `try/catch` 转成 `errorMessage` StateFlow，UI 弹 Snackbar 后 `consumeError()`。

## 8. 提醒（notify/）

- `ReminderScheduler`：AlarmManager 精确闹钟（`setExactAndAllowWhileIdle`）。Android 14+ 未授权 `SCHEDULE_EXACT_ALARM` 时自动降级 `setAndAllowWhileIdle`（可能延迟但不崩溃）。提醒时刻 = `windowEnd - reminderMinutesBefore`。PendingIntent requestCode 用 `instanceId xor Int.MIN_VALUE`（避开通知 id 冲突）。
- `ReminderReceiver`：闹钟触发后**先查库校验实例仍 ACTIVE** 才弹通知；若已完成/作废/删除则顺手 `cancel` 掉残留闹钟（自清），不弹。通知点击回 MainActivity。Android 13+ 无通知权限直接不弹（MainActivity 启动时请求权限）。
- `RolloverWorker`：WorkManager 周期任务（12 小时，`ExistingPeriodicWorkPolicy.KEEP`），内容就是 `settleAndRefresh()`。最多重试 3 次。
- `BootReceiver`：开机完成后 enqueue 一次性 RolloverWorker 补结算 + 重排闹钟（闹钟不持久化，重启后必须重排）。

## 9. 桌面小组件（widget/）

Glance `TodoWidget`（`SizeMode.Exact` 拿真实尺寸，保证壁纸按实际宽高比裁剪）。内容与主页一致：未完成按分类分组 + 已完成区（默认收起，点标题展开）。

关键设计（都是踩坑后的结论，改动前必读）：

1. **刷新模型（Glance 1.1.1 的核心坑）**：`GlanceAppWidget.update()` 只是给会话发事件，**仅当 Glance Preferences 内容变化时才真正重组**；且 `provideGlance` 每个会话只执行一次（`provideContent` 设计上永不返回）。因此 Room 数据变化后光调 `update()` 是没用的——会话存活期（约几十秒）内会被静默忽略，表现为"点了没反应、隔很久才刷"。正确姿势已封装在 `WidgetUpdater.refresh(context)`：先 `updateAppWidgetState` bump `KEY_DATA_VERSION` 再 `update`；组合内容里用 `produceState(key1 = dataVersion)` 重读数据库与壁纸，外观值（SharedPreferences）在重组时直接同步读取。`provideGlance` 里的一次性读取只用于新会话的首帧。任何组件刷新都必须走 `WidgetUpdater.refresh`，不要直接调 `update()`/`updateAll()`。
2. **结算不放进组件链路**（在 App/Worker/动作回调里做），`provideGlance` 只做轻量读取，壁纸位图有文件级缓存（按 lastModified+length 失效，解码在 IO 线程） → 刷新快。
3. **交互状态**（已完成区展开 `KEY_SHOW_COMPLETED`）存 Glance Preferences 原子状态（`PreferencesGlanceStateDefinition`），**不要**用 SharedPreferences 存高频交互态（读写竞态会导致点击"无效"）。外观设置（低频）才走 SharedPreferences（`WidgetPrefs`）。
4. **LazyColumn 的 itemId 必须与任务实例 id 段隔开**：组卡片 100_000+/200_000+，间隔 300_000+/400_000+，头部 `Long.MAX_VALUE-1`。否则 RemoteViews 视图复用导致点击错乱。
5. 整行点击即完成/取消完成（`CompleteTaskAction` / `UncompleteTaskAction`），动作内直接走 `DataSyncManager.settleAndRefresh()`，与 App 实时一致。
6. 外观：纯色（透明度 20-100%）/ 壁纸（变暗 0-60%，按 appWidgetId 存裁剪矩形，千分比精度）。磨砂模式已废弃（RemoteViews 无法真模糊），历史遗留值按纯色处理。壁纸且压得较暗（≥25%）时切浅色文字。
7. 组件删除后 `TodoWidgetReceiver.onDeleted` 清理该实例的裁剪键残留。系统触发的更新（周期/添加组件）在 `onUpdate` 里也走 `WidgetUpdater.refresh` 统一 bump 版本号。
8. **裁剪页 `WidgetCropActivity`**：独立 `taskAffinity=""` + `excludeFromRecents`——从组件进入时不开进 App 主任务栈，返回键直接回桌面。入口：组件头部裁剪按钮（壁纸模式下显示）或长按组件的系统"设置"菜单（provider xml 配了 `configure` + `reconfigurable|configuration_optional`，API 31+ 生效），以及换壁纸中转页（`EXTRA_PENDING` 挂起模式：裁挂起文件而非正式壁纸，不还原旧图区域、从全图开始；保存时先 `WallpaperImporter.commitPending`——原子替换正式壁纸 + 清空全部旧裁剪区域 + 切壁纸模式——再写入新区域；取消/返回 `discardPending` 丢弃，旧壁纸原样保留）。选框可拖动/双指缩放。**选框比例只信系统上报的精确尺寸**（`OPTION_APPWIDGET_SIZES` 首个；API <31 用 min/max 中值）——荣耀等 Launcher 不上报真实尺寸（SIZES 为空、min/max 是粗范围，Glance 的 `LocalSize` 是用 maxW/minH 拼出的假值，intent 带的 aspect 同样不可信），此时改为自由框选（底部有 全图/1:1/4:3/3:4/16:9/9:16 比例预设，芯片内带比例形状示意，组件端 `ContentScale.Crop` 把所选区域铺满显示，所选即所得）。再次进入时从已存裁剪区域完整还原比例（匹配预设，2% 容差；≈全图归全图；都不匹配则用原比例作为自定义值）+ 大小 + 位置。更换壁纸在保存时清空全部已存裁剪区域（`commitPending`——区域是相对旧图的相对值，对新图无意义）。保存后走 `WidgetUpdater.refresh`（直接 `updateAll` 可能被 Glance 静默忽略，见第 1 条）。API 26-30 添加组件时系统会强制拉进 configure Activity——**所有退出路径（无壁纸/取消/返回）都必须 `RESULT_OK` 携带组件 id 放行添加**，返回 CANCELED 会导致组件加不上。

## 10. 依赖注入与入口

- `GameTodoApp`（`@HiltAndroidApp`）：创建通知渠道、排周期结算 Worker、首启种默认分类（工作/生活/学习）。实现 `Configuration.Provider` 注入 `HiltWorkerFactory`。
- `di/AppModule`：提供 Room 数据库（注册 MIGRATION_1_2 / 2_3）与三个 DAO。
- 非 Hilt 场景（Glance 小组件、ReminderReceiver）通过 EntryPointAccessors 取依赖：`WidgetEntryPoint`（repository + dataSync）、`ReminderEntryPoint`（instanceDao + reminderScheduler）。

## 11. 权限与 Manifest 要点

`POST_NOTIFICATIONS`（13+ 运行时请求）、`SCHEDULE_EXACT_ALARM`（14+ 可能需用户授权，代码已降级处理）、`RECEIVE_BOOT_COMPLETED`。`MainActivity` 为单 LAUNCHER；`WidgetCropActivity` exported=true（系统桌面要拉起它）；`WidgetWallpaperPickActivity` exported=false + 半透明主题（仅应用内与组件 PendingIntent 拉起，无界面中转页）。

## 12. 开发约定（新增功能时遵守）

1. **写操作后必走 `DataSyncManager.settleAndRefresh()`**；不要手工组合结算/重排/刷新。
2. **新业务语义先落成 domain 纯函数 + JVM 单测**，再接 UI；数据完整性场景补 Robolectric 集成测试。
3. 改表结构：升版本号 + 写 Migration + 提交 `app/schemas/` 新快照 + MigrationTest 覆盖。
4. 枚举存 DB 用 String（`toRecurrenceType()` 等兜底扩展防脏数据）。
5. UI 文案统一走 `Formatters.kt`，小组件与 App 内保持一致。
6. 模拟器 UI 冒烟：Pixel_6，截图用 `adb exec-out screencap`；点击坐标以 `uiautomator dump` 的 bounds 为准（截图是下采样的，按比例换算会点错）；adb shell 内绝对路径加 `MSYS_NO_PATHCONV=1` 前缀。荣耀/华为真机 logcat 被系统禁用，临时诊断改为写应用私有文件再 `run-as` 读取。
