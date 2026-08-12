# 循环清单（Gametodolist）汇总审查与修复设计

日期：2026-08-12
状态：已获用户批准（行为变更范围：全部按推荐方案执行；路线：方案一 统一管线+全量修复）

## 1. 背景与目标

用户要求从实用/便利角度对项目做汇总审查：剔除不合理代码、衔接各模块功能、验证优化。痛点是"很多改动顾此失彼"。

三路并行审查（领域/数据层、UI 层、组件/提醒层）+ 构建基线验证（21 个 JVM 单测全过）共确认 4 类问题：

- **A 严重（数据丢失/崩溃）**：模板编辑走 `@Insert(REPLACE)` + 实例表 FK CASCADE → 每次编辑清空该模板全部实例历史；Android 14+ 精确闹钟权限默认拒绝而不检查 → SecurityException 崩溃 + RolloverWorker 无限重试；ViewModel 写操作零异常保护。
- **B 功能断链（7 项）**：组件撤销完成不重排提醒；rollover 生成新实例后 4 个调用点不重排提醒；编辑页保存已删除分类 id → 任务主页"隐身"；NONE+自定义天数创建/编辑即过期不结算；日历 FAB 日期对 WEEKLY/MONTHLY 被静默丢弃；EXPIRED 任务无复活路径；并发 rollover 可重复插实例；日历页 rollover 不同步组件。
- **C 便利性（8+ 项）**：删除无确认/无撤销、编辑页返回无放弃保护、折叠状态切 tab 丢失、日历无回到今天、编辑页无删除入口、保存可双击重复提交、加载闪空态、勾选圈无读屏语义、数字输入框可输非法值。
- **D 冗余/清理**：6 处死代码方法、`glance-material3` 未使用依赖、剩余天数文案双份实现、枚举兜底 4 处重复、魔数颜色散落、死字段。

共同根源：**"写后效应"（结算→重排提醒→刷新组件）散落在 6+ 个调用点手工维护**，改一处忘一处。

## 2. 核心架构：DataSync 统一变更管线

新建 `sync/DataSyncManager` 单例（注入 `RolloverManager`、`ReminderScheduler`、`@ApplicationContext Context`）：

```kotlin
/** 任何任务/模板/分类数据变更后的统一入口：结算 → 重排提醒 → 刷新组件。三步全量、幂等、自愈。 */
suspend fun settleAndRefresh() {
    runCatching { rolloverManager.rolloverAll() }.onFailure { Log.w(...) }
    runCatching { reminderScheduler.rescheduleAll() }.onFailure { Log.w(...) }
    WidgetUpdater.refresh(context)  // 内部已有 runCatching
}
```

要点：

- 三步全量且幂等：`rescheduleAll` 只给 ACTIVE 实例排闹钟，刚完成/删除的实例自动被跳过，无需单点 cancel/schedule 的精确优化（表小，开销可忽略）。
- 每步独立 `runCatching` + Log，单步失败不拖垮其余，不再出现"一步抛异常全链崩"。
- `rolloverAll` 内部加 `Mutex`（单进程互斥），配合 DB 唯一索引（见 3.3）双保险防并发重复插实例。
- **所有**数据变更点收敛为一行调用：HomeViewModel（完成/撤销/删除/init）、TaskEditViewModel（保存/删除）、CategoryViewModel（增/删）、CalendarViewModel（init）、组件 4 个 Action（Complete/Uncomplete/Toggle 后的刷新、Refresh）、RolloverWorker。今后新增写路径不可能再漏。
- 例外：设置页外观改动（模式/透明度/亮度/壁纸/裁剪）不涉及任务数据，保持只调 `WidgetUpdater.refresh` 现状；滑块 `onValueChangeFinished` 才刷新的设计保留。
- AGENTS.md 的"改动后手动调 WidgetUpdater.refresh"约定相应更新为"统一走管线"。

## 3. A 类严重修复

### 3.1 编辑丢历史（最高优先）

- `TaskTemplateDao.insert(@Insert REPLACE)` → `@Upsert`（Room 2.6.1 已支持，insert-or-update 不触发 DELETE→CASCADE）。`CategoryDao` 同步改 `@Upsert`。
- 新增 Robolectric + Room in-memory 集成测试 `RoomIntegrationTest`：编辑模板后历史实例仍在；`deleteTemplate` 仍级联清实例（CASCADE 语义保留，用于真删除）。
- 依赖：`testImplementation("org.robolectric:robolectric:4.14.1")`、`testImplementation("androidx.room:room-testing:2.6.1")`。

### 3.2 Android 14+ 精确闹钟

- `ReminderScheduler.schedule`：`Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()` 时降级 `setAndAllowWhileIdle`；整体 `runCatching` 兜底不抛。
- `RolloverWorker`：只对瞬态错误 `Result.retry()`，不再吞所有 Exception 无限重试。
- 设置页提醒区：API 31+ 且无精确权限时显示提示条，点击跳 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM`。

### 3.3 并发重复实例 + DB v3

- `TaskInstanceEntity` 加 `@Index(value=["templateId","cycleStart"], unique=true)`；手写 `MIGRATION_2_3`：先 `DELETE` 同组重复行（保留 MAX(id)），再 `CREATE UNIQUE INDEX`。
- 打开 `exportSchema`（ksp `room.schemaLocation` → `app/schemas`），为后续迁移测试建基线。
- `RolloverManager.rolloverAll` 加 `Mutex.withLock`。

### 3.4 写操作异常保护

- ViewModel 写路径统一 try/catch：失败置 `errorMessage` 状态，主页/编辑页 `LaunchedEffect` 弹 Snackbar。保证 DB/IO 异常不闪退。

## 4. B 类衔接修复

- **断链覆盖**：第 2 节的管线收口天然修复——组件撤销完成重排提醒、rollover 后重排提醒、日历页 rollover 后刷新组件、刷新按钮刷全部组件实例（`update` → `updateAll`）。
- **NONE+自定义天数创建/编辑即过期**：`RolloverManager` 收敛私有校验"实例落库/更新后 `windowEnd != MAX && windowEnd <= now` → `markExpired`"，NONE 分支与循环分支行为一致。
- **`regenerateActiveInstance` 语义统一**：编辑保存后先 `rolloverTemplate`（结算旧的、必要时补新循环），再对 ACTIVE 实例按新规则重算窗口；NONE 分支同样补过期校验。
- **悬挂分类 id**：`TaskEditViewModel.save` 前校验 `categoryId` 仍在分类表中，否则置 null。
- **WEEKLY/MONTHLY 支持开始日期**：
  - 引擎语义：`anchorDate > 0` 时，首个循环不早于 anchor（WEEKLY：从 anchor 起第一个命中 mask 的日期所在循环；MONTHLY：从 anchor 起按月推进，日号取 anchor 日并做月末钳制）；`anchorDate <= 0` 保持旧行为（从今天回推），存量数据无感。
  - `currentCycleStartDate` 对 anchor 之前的查询日期返回"无循环"（日历对 anchor 前的日期不显示该任务）；`nextCycleStartDate` 不越过 anchor 回退。
  - 编辑页：WEEKLY/MONTHLY 也显示"开始日期"字段；保存时不再 `else -> 0L`。
- **已作废复活**：主页新增"已作废"灰色区（最新实例 EXPIRED 的模板，实践中只有 NONE+自定义天数会长期停留），卡片提供"重新开启"：模板 `anchorDate` 更新为今天 + 最新 EXPIRED 实例改回 ACTIVE 并按新 anchor 重算窗口（`TodoRepository.reviveTemplate`）。
- **窗口语义不变**：半开 `[start, end)`、`Long.MAX_VALUE` 表示长期有效，全线保持。

## 5. C 类便利性

- 主页左滑删除任务 → 确认弹窗（说明连带历史实例与提醒一并删除）；分类管理页删除 → 确认弹窗（说明任务归入未分类）。
- 编辑页：标题或内容 dirty 时，返回箭头/系统返回键弹"放弃修改？"（`BackHandler`）。
- 主页分类折叠状态从 `remember` 提升到 `HomeViewModel`（StateFlow<Map<Long,Boolean>>），切 tab 不丢。
- 日历标题栏加"回到今天"按钮（回当月并选中今天）。
- 编辑页编辑模式加删除入口（确认弹窗，删除后返回）。
- 保存按钮加 `saving` 状态位，保存期间禁用防双击重复插入。
- 主页 `HomeData` 初始值改 `null`/加载态，不再闪"还没有清单"。
- `TaskCard` 勾选圈补 `semantics { role = Checkbox; contentDescription }`。
- 编辑页数字输入框：过滤负号/非数字，就地钳制 ≥1 并回显。
- 设置页位图裁剪（`SettingsScreen`）改 `produceState` + `Dispatchers.Default`；`WallpaperCache.get` 内部 `withContext(Dispatchers.IO)`；裁剪页 `remember` 同步解码改 `LaunchedEffect` + IO。
- 日历页 `categoryColor()` 非响应式读取 → `categories.collectAsStateWithLifecycle()`；逐模板 `runCatching` 失败加 Log。
- 壁纸解码失败时设置页提示"壁纸读取失败"。

## 6. D 类清理

- 删死代码：`TaskInstanceDao.observeInRange`/`observeActive`、`TodoRepository.getActiveInstances`、`CategoryDao.getAll`/`getById`/`update`、`TaskTemplateDao.update`（被 `@Upsert` 取代）、`TaskEditUiState.loaded`。
- 删依赖 `androidx.glance:glance-material3`。
- `remainingLabel`（Formatters）与 `CalendarScreen.remainingText` 合并为单函数 `remainingLabel(startMillis, endMillis, referenceDate)`。
- 枚举兜底收敛：`Models.kt` 加 `String.toRecurrenceType()`/`toWindowMode()`/`toInstanceStatus()` 共享扩展，替换 4 处 `runCatching { valueOf }`。
- 魔数颜色 `0xFF7FB69E`/`0xFFB45A5A` 收敛到 `theme/Color.kt`（启用已定义的 `ExpiredRed`）；`DarkColors` 维持现状（明确不做深色重做）。
- 魔法数字 guard（500/400）提为命名常量。
- `sortOrder` 字段保留 + 注释"预留未启用"（删列需重建表，成本不值）。
- 组件边角：`TodoWidgetReceiver.onDeleted` 清理 `crop_rect_<id>_*` 残留键；API 26-30 或无壁纸时 `WidgetCropActivity.onCreate` 直接 `setResult(CANCELED); finish()`；configure 保存回传 `EXTRA_APPWIDGET_ID`；裸 `CoroutineScope` 改 `lifecycleScope`；组件尺寸选取优先 `OPTION_APPWIDGET_WIDTH/HEIGHT`；`todo_widget_info.xml` 加 `widget_description`；`initialized` Float 标志改 Boolean。
- `windowStart` 字段保留 + 注释用途（存储冗余但迁移成本高）。

## 7. 测试计划

- **新增** `RoomIntegrationTest`（Robolectric）：编辑不丢历史、级联删除、唯一索引拒绝同 (templateId, cycleStart) 重复。
- **补** `RolloverManagerTest`：`regenerateActiveInstance` 全分支（NONE/循环 × ACTIVE/非 ACTIVE/latest==null）、NONE 创建即过期立即 EXPIRED、编辑后先 rollover 的语义。
- **补** `RecurrenceEngineTest`：WEEKLY/MONTHLY anchor 语义（含 anchor 前日期无循环）、MONTHLY 月末钳制不漂移、DST 时区（America/New_York）窗口换算。
- **补** `WallpaperCropTest`：exact-fit、1×1 极值、浮点截断、返回窗口比例≈目标比例。
- 每批改动后跑：`gradle test assembleDebug`（环境：JAVA_HOME 指向 Android Studio jbr，用项目内 gradle-8.10.2）。
- 最后 Pixel_6 模拟器冒烟：建任务（各循环类型）、编辑保存验证历史保留、删除确认弹窗、撤销完成、已作废复活、组件完成/撤销/展开/刷新、裁剪流程、提醒授权提示——逐条 `uiautomator dump` 取坐标 + 截图验证（遵守 AGENTS.md 的 MSYS_NO_PATHCONV 等陷阱约定）。

## 8. 执行批次（每批一个 git 提交）

1. **数据层**：`@Upsert`、v3 迁移+唯一索引+exportSchema、RolloverManager 修复（NONE 即过期/regenerate 语义/Mutex/统一过期校验）、引擎 WEEKLY/MONTHLY anchor、枚举兜底扩展 + 全部新单测（含 Robolectric）。
2. **管线**：`DataSyncManager` 收口所有调用点、ReminderScheduler 权限降级、ReminderReceiver 实例状态校验（非 ACTIVE 不弹）、RolloverWorker retry 修正。
3. **UI**：衔接修复（categoryId 校验、WEEKLY/MONTHLY 日期字段、已作废区+复活）+ C 类便利性全项 + Compose 修正。
4. **清理**：D 类全项 + AGENTS.md 约定更新。
5. **验证**：全量单测 + assembleDebug + 模拟器冒烟（逐条截图）。

## 9. 明确不做（YAGNI）

- 深色主题配色重做（维持"深色兜底"注释现状）。
- 任务排序 UI（`sortOrder` 仅注释保留）。
- v1→v2 旧迁移补测（直接为 v3 建测试基线）。
- 磨砂组件背景（已废弃，不复活）。
- 组件展开状态竞态的根治（概率极低，维持 Glance 原子状态现状）。

## 10. 验收标准

- `gradle test assembleDebug` 全绿（含新增 Robolectric 集成测试）。
- 模拟器冒烟清单逐项通过。
- 审查报告中 A/B 类问题全部闭环（逐条对照代码验证），C/D 类按本设计落地。
- AGENTS.md 与新管线约定同步更新。
