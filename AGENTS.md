# 循环清单（Gametodolist）— 项目框架速览

Android 清单 App：Kotlin + Jetpack Compose + Room + Hilt + WorkManager + Glance 桌面小组件。包名 `com.gametodolist`，minSdk 26，target 35。

## 构建 / 测试（Windows，必配环境）

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"   # 系统无独立 Java
/d/VSCodeCache/Gametodolist/.gradle-dist/gradle-8.10.2/bin/gradle test assembleDebug --console=plain
```

- Gradle 用项目内解压的 8.10.2，系统无 gradle 命令；仓库已配阿里云镜像（settings.gradle.kts）。
- 单测：`app/src/test/java/com/gametodolist/`——RecurrenceEngine / RolloverManager / WallpaperCrop / Models 为 JVM 纯函数测试；MigrationTest 与 RoomIntegrationTest 是 Robolectric 真实 Room（in-memory）集成测试，覆盖迁移与"编辑模板不丢实例历史"等数据完整性。
- 模拟器验证：`emulator -avd Pixel_6`（emulator/adb 在 `C:/Users/da270/AppData/Local/Android/Sdk/` 下）；截图 `adb exec-out screencap -p > /tmp/x.png`，用 ReadMediaFile 读 `C:\Users\da270\AppData\Local\Temp\x.png`。
- Git Bash 陷阱：adb shell 里的 `/sdcard/...` 等绝对路径会被 MSYS 转成 Windows 路径，必须 `MSYS_NO_PATHCONV=1` 前缀；UI 坐标用 `adb shell uiautomator dump` + pull 取精确 bounds，勿靠截图估算（截图是 900x2000 下采样，真实 1080x2400）。
- 读应用私有数据：`adb shell run-as com.gametodolist cat /data/data/com.gametodolist/shared_prefs/widget_prefs.xml`（debug 包可用）。
- 荣耀/华为真机：logcat 被系统禁用（抓不到应用日志），临时诊断改为写应用私有文件再 `run-as cat`；`adb push` 的远端路径也要 `MSYS_NO_PATHCONV=1`。

## 架构地图

- **领域层** `domain/`：`RecurrenceEngine`（纯函数：循环起点/显示窗口计算，NONE/INTERVAL_DAYS/WEEKLY/MONTHLY；WEEKLY/MONTHLY 支持 anchorDate——首个循环不早于 anchor，`anchorDate<=0` 保持旧行为）、`RolloverManager`（结算：到期作废+生成新循环；NONE 有持续天数时到期作废不续期，且创建/编辑即过期立即作废；编辑模板走 `regenerateActiveInstance` 修正当前实例——起点失效则作废/改挂再结算，**已作废模板先复活再修正**，所以编辑已作废任务保存即恢复生效；`reviveTemplate` 复活已作废任务=同一 regenerate 路径；四个 public 入口共用一把 Mutex）、`WallpaperCrop`（cover 裁剪窗口纯函数）、`Models`（枚举 + `toRecurrenceType()`/`toWindowMode()` 兜底扩展）。
- **数据层** `data/`：Room 三表 categories/task_templates/task_instances；模板存规则，实例存某次循环的窗口与状态（ACTIVE/COMPLETED/EXPIRED）。DB v3（v2→v3 去重并加实例表 `(templateId, cycleStart)` 唯一索引；exportSchema 开，schema 在 `app/schemas/`）。模板/分类保存走 insert/update 分流（**不要用 REPLACE 插入模板**——会触发实例表 FK CASCADE 清空历史）。主页数据 = 每模板最新实例分未完成/已完成/已作废三区再按分类分组（`TodoRepository.observeHomeData`）。
- **统一管线** `sync/DataSyncManager`：任何任务/模板/分类写操作后调 `settleAndRefresh()`——结算（rolloverAll）→ 全量重排提醒（只排 ACTIVE，刚完成/删除的自动跳过）→ 刷新所有桌面组件。三步全量幂等、单步失败互不影响。
- **UI** `ui/`：底部三栏（清单/日历/设置，`AppNav`，NavHost fade 150ms）。所有页面用紧凑标题栏（statusBarsPadding + 小 padding，**不用原生 TopAppBar**——它比紧凑栏矮一截留白多）。主页紧凑标题栏+三分区（未完成/已完成划线/已作废可点击编辑、左滑删除、重新开启）+加载态；**无右滑手势**（误划），左滑露出红色「删除」按钮停住、点击才触发删除（`SwipeRevealDeleteBox`，AnchoredDraggable 双锚点）；删除警示弹窗带「不再提醒」勾选，关掉后点删除直接删，设置页「删除清单前提醒」开关可恢复（`data/SettingsPrefs`）；分类折叠状态在 ViewModel；主页与日历页任务列表底部留白 88dp 避让悬浮添加按钮。日历页用引擎对任意日期（含未来）计算当天任务，打点=循环更新日，有"回到今天"。编辑页开始日期（不循环/每N天直接设；每周/每月经"指定开始日期"开关）、显示时段（整循环/长期有效 vs 自定义 N 天，≥1）；脏状态返回弹放弃确认；编辑模式有删除入口。删除任务/分类均有确认弹窗。
- **小组件** `widget/`：Glance `TodoWidget`（SizeMode.Exact）。要点：
  - **刷新模型（Glance 1.1.1 的坑，已踩过）**：`update()` 只在 Glance Preferences 内容变化时才触发重组，且 `provideGlance` 每个会话只执行一次（`provideContent` 设计上不返回）——Room 数据变化本身驱动不了重组，会话存活期（约几十秒）内直接调 update 会被静默忽略，表现为"点了没反应"。所以统一走 `WidgetUpdater.refresh(context)`：先 bump `KEY_DATA_VERSION` 再 update；组合内用 `produceState(key1 = dataVersion)` 重读数据库/壁纸，SharedPreferences 外观值重组时直接读。不要在 provideGlance 里一次性读数据就完事。
  - 结算不放进组件链路（在 App/Worker/动作里做），壁纸位图有文件级缓存（解码在 IO 线程） → 刷新快。
  - 交互状态（已完成区展开）存 Glance Preferences 原子状态（`KEY_SHOW_COMPLETED`），**不要**用 SharedPreferences 存高频交互态（读写竞态会导致点击“无效”）。
  - LazyColumn 的 itemId 必须与任务实例 id 段隔开（组卡片 100_000+/200_000+，头部 Long.MAX_VALUE-1），否则视图复用导致点击错乱。
  - 外观：纯色（透明度）/壁纸（亮度+按 appWidgetId 裁剪矩形），磨砂已废弃（RemoteViews 无法真模糊）。外观存 `WidgetPrefs`（SharedPreferences），改动后调 `WidgetUpdater.refresh`。组件删除后 `onDeleted` 清理裁剪残留键。
  - 换壁纸统一入口 `WidgetWallpaperPickActivity`（透明中转页，同独立任务栈；组件头部图片按钮与设置页「更换壁纸」共用）：系统选图 → `WallpaperImporter.saveToPending` 存挂起文件（屏幕长边封顶 1440..3200、JPEG q92、2 的幂采样防 OOM）→ 直接进裁剪页（`EXTRA_PENDING` 挂起模式）。**暂存式提交**：保存才 `commitPending`（原子替换正式壁纸 + 清空全部旧裁剪区域 + 切壁纸模式，新区域必须在其后写入），取消/返回 `discardPending` 丢弃挂起文件，旧壁纸与裁剪原样保留；桌面无组件实例时跳过裁剪直接生效。
  - 裁剪页 `WidgetCropActivity`：独立 `taskAffinity=""`+`excludeFromRecents`（从组件进入时返回键直接回桌面，不进 App 主栈）；组件头部裁剪按钮 + 长按菜单（provider xml 里 configure + `reconfigurable|configuration_optional`，31+ 生效）进入；选框可拖动/双指缩放。**选框比例只信系统上报的精确尺寸**（`OPTION_APPWIDGET_SIZES` 首个；<31 用 min/max 中值）——荣耀等 Launcher 不上报真实尺寸（SIZES 为空、min/max 是粗范围，Glance 的 LocalSize 是 maxW/minH 拼出来的假值，intent 带的 aspect 同样不可信），此时改自由框选（底部有 全图/1:1/4:3/3:4/16:9/9:16 预设，芯片内带比例形状示意；组件端 ContentScale.Crop 铺满显示，所选即所得）。再次进入时从已存裁剪区域还原比例（匹配预设，≈全图归全图，都不匹配则用原比例）+大小+位置，选框与上次保存完全一致；**挂起模式（换壁纸流程）裁的是挂起文件，不还原旧图区域、从全图重新开始**。保存后走 `WidgetUpdater.refresh`（直接 updateAll 可能被 Glance 静默忽略）。API 26-30 添加组件会被系统强制拉进 configure，**所有退出路径（无壁纸/取消/返回）都必须 RESULT_OK 携带组件 id 放行添加**（CANCELED 会导致组件加不上）。
- **提醒** `notify/`：ReminderScheduler（精确闹钟；Android 14+ 未授权自动降级非精确，不崩溃）、ReminderReceiver（触发前查库校验实例仍 ACTIVE，否则自清残留闹钟不弹）、RolloverWorker（周期结算走 DataSyncManager）、BootReceiver。

## 目录结构（各部分作用）

```
app/src/main/java/com/gametodolist/
├── GameTodoApp.kt          # @HiltAndroidApp：通知渠道、周期结算 Worker、首启默认分类、WorkManager 配置
├── di/AppModule.kt         # Hilt：Room 数据库（注册迁移）与三个 DAO
├── domain/                 # 纯 Kotlin 业务规则（无 Android 依赖，JVM 单测）
│   ├── RecurrenceEngine.kt # 循环起点/显示窗口计算
│   ├── RolloverManager.kt  # 结算：到期作废+补新循环；regenerate/revive；Mutex 串行
│   ├── WallpaperCrop.kt    # cover 裁剪窗口纯函数
│   └── Models.kt           # 枚举、字符串兜底解析、分类色板
├── data/
│   ├── AppDatabase.kt      # Room v3（exportSchema 开，快照在 app/schemas/）
│   ├── Migrations.kt       # v1→v2 anchorDate；v2→v3 实例表唯一索引
│   ├── entity/             # Category / TaskTemplate(规则) / TaskInstance(单次循环状态)
│   ├── dao/                # 三表 DAO
│   ├── SettingsPrefs.kt    # 应用级偏好（删除前提醒开关、主题预设 id theme_id，SharedPreferences app_settings）
│   └── TodoRepository.kt   # 存取 + 主页数据组合（三分区再按分类分组）
├── sync/DataSyncManager.kt # 统一写后管线：结算→重排提醒→刷新组件
├── notify/                 # 提醒调度与接收、周期结算 Worker、开机接收器
├── widget/                 # Glance 桌面小组件
│   ├── TodoWidget.kt       # 组件布局与数据装配（produceState 按 KEY_DATA_VERSION 重读）
│   ├── WidgetUpdater.kt    # 唯一刷新入口：bump 数据版本号 + update
│   ├── TodoWidgetReceiver.kt        # onUpdate 走统一刷新；onDeleted 清裁剪残留键
│   ├── Complete/UncompleteTaskAction.kt      # 行点击完成/取消
│   ├── ToggleShowCompletedAction.kt          # 已完成区展开/收起
│   ├── RefreshWidgetAction.kt                # 手动刷新按钮
│   ├── WidgetPrefs.kt      # 外观与裁剪区域存储（SharedPreferences）
│   ├── WidgetCropActivity.kt # 壁纸裁剪页（独立任务栈；比例预设/自由框选；EXTRA_PENDING 挂起模式）
│   ├── WidgetWallpaperPickActivity.kt # 换壁纸中转页（选图→挂起文件→直接进裁剪页）
│   ├── WallpaperImporter.kt  # 壁纸导入暂存式提交：挂起/提交/丢弃（屏幕长边封顶 JPEG q92）
│   └── WidgetEntryPoint.kt # Hilt EntryPoint（组件/动作取依赖）
└── ui/
    ├── MainActivity.kt     # 唯一主 Activity（edge-to-edge、通知权限请求）
    ├── AppNav.kt           # NavHost：清单/日历/设置三栏 + 分类/编辑二级页
    ├── Formatters.kt       # 日期/循环规则/剩余时间文案（UI 与组件共用）
    ├── theme/              # M3 主题预设体系：ThemePresets（5 套浅色预设，默认图鉴紫，LocalThemePreset 取渐变尾色）+ GameTodoTheme(themeId)
    ├── home/               # 主页：未完成/已完成/已作废三分区 + 分类分组
    ├── calendar/           # 日历：引擎预演任意日期任务，更新日打点
    ├── edit/               # 新增/编辑清单（脏检查、删除入口）
    ├── category/           # 分类管理（增删改、色板）
    ├── settings/           # 外观/壁纸（更换走中转页→裁剪页暂存式提交，返回 ON_RESUME 重读落盘）/精确闹钟授权；预览为 60% 宽 1:1 居中裁剪
    └── components/         # TaskCard、GradientFab（渐变+号）、RemainingChip（剩余天数胶囊）、LabelChip、EmptyState 等共用组件
```

## 约定

- 改动任务状态/模板/分类后，统一走 `DataSyncManager.settleAndRefresh()`（不要手工组合 rollover/重排提醒/刷新组件，防漏步）；仅外观设置改动仍调 `WidgetUpdater.refresh(context)`。
- 主题色不要硬编码：主色系一律走 `colorScheme` 或 `LocalThemePreset`（卡片描边 = `outlineVariant`，FAB 渐变尾色 = `preset.gradientEnd`），新预设加进 `THEME_PRESETS` 即可在设置页被选中；剩余天数的紧急态判定用 `Formatters.remainingDays()`（<=2 天橙、已截止红）。
- 新语义靠纯函数 + JVM 单测落地（引擎、结算、裁剪），数据完整性靠 Robolectric 集成测试，UI 冒烟在 Pixel_6 模拟器上逐条截图验证。
- 已完成任务不消失：划线归到已完成区，点实心勾撤销；组件上整行点击即完成/取消。已作废任务归已作废区，可点击编辑（保存即复活）、左滑删除、"重新开启"（一次性任务从今天开始重算窗口，循环任务回到当前循环）。
