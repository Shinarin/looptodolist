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

## 架构地图

- **领域层** `domain/`：`RecurrenceEngine`（纯函数：循环起点/显示窗口计算，NONE/INTERVAL_DAYS/WEEKLY/MONTHLY；WEEKLY/MONTHLY 支持 anchorDate——首个循环不早于 anchor，`anchorDate<=0` 保持旧行为）、`RolloverManager`（结算：到期作废+生成新循环；NONE 有持续天数时到期作废不续期，且创建/编辑即过期立即作废；编辑模板走 `regenerateActiveInstance` 修正当前实例——起点失效则作废/改挂再结算；`reviveTemplate` 复活已作废一次性任务；四个 public 入口共用一把 Mutex）、`WallpaperCrop`（cover 裁剪窗口纯函数）、`Models`（枚举 + `toRecurrenceType()`/`toWindowMode()` 兜底扩展）。
- **数据层** `data/`：Room 三表 categories/task_templates/task_instances；模板存规则，实例存某次循环的窗口与状态（ACTIVE/COMPLETED/EXPIRED）。DB v3（v2→v3 去重并加实例表 `(templateId, cycleStart)` 唯一索引；exportSchema 开，schema 在 `app/schemas/`）。模板/分类保存走 insert/update 分流（**不要用 REPLACE 插入模板**——会触发实例表 FK CASCADE 清空历史）。主页数据 = 每模板最新实例分未完成/已完成/已作废三区再按分类分组（`TodoRepository.observeHomeData`）。
- **统一管线** `sync/DataSyncManager`：任何任务/模板/分类写操作后调 `settleAndRefresh()`——结算（rolloverAll）→ 全量重排提醒（只排 ACTIVE，刚完成/删除的自动跳过）→ 刷新所有桌面组件。三步全量幂等、单步失败互不影响。
- **UI** `ui/`：底部三栏（清单/日历/设置，`AppNav`，NavHost fade 150ms）。主页紧凑标题栏+三分区（未完成/已完成划线/已作废可重新开启）+加载态；左滑删除弹确认；分类折叠状态在 ViewModel。日历页用引擎对任意日期（含未来）计算当天任务，打点=循环更新日，有"回到今天"。编辑页开始日期（不循环/每N天直接设；每周/每月经"指定开始日期"开关）、显示时段（整循环/长期有效 vs 自定义 N 天，≥1）；脏状态返回弹放弃确认；编辑模式有删除入口。删除任务/分类均有确认弹窗。
- **小组件** `widget/`：Glance `TodoWidget`（SizeMode.Exact）。要点：
  - `provideGlance` 只做轻量读取（不跑 rollover），壁纸位图有文件级缓存（解码在 IO 线程） → 刷新快。
  - 交互状态（已完成区展开）存 Glance Preferences 原子状态（`KEY_SHOW_COMPLETED`），**不要**用 SharedPreferences 存高频交互态（读写竞态会导致点击“无效”）。
  - LazyColumn 的 itemId 必须与任务实例 id 段隔开（组卡片 100_000+/200_000+，头部 Long.MAX_VALUE-1），否则视图复用导致点击错乱。
  - 外观：纯色（透明度）/壁纸（亮度+按 appWidgetId 裁剪矩形），磨砂已废弃（RemoteViews 无法真模糊）。外观存 `WidgetPrefs`（SharedPreferences），改动后调 `WidgetUpdater.refresh`。组件删除后 `onDeleted` 清理裁剪残留键。
  - 裁剪页 `WidgetCropActivity`：组件头部裁剪按钮 + 长按菜单（provider xml 里 configure + `reconfigurable|configuration_optional`，31+ 生效）进入；选框=组件当前宽高比，可拖动/双指缩放。API 26-30 添加组件会被系统强制拉进 configure，**所有退出路径（无壁纸/取消/返回）都必须 RESULT_OK 携带组件 id 放行添加**（CANCELED 会导致组件加不上）。
- **提醒** `notify/`：ReminderScheduler（精确闹钟；Android 14+ 未授权自动降级非精确，不崩溃）、ReminderReceiver（触发前查库校验实例仍 ACTIVE，否则自清残留闹钟不弹）、RolloverWorker（周期结算走 DataSyncManager）、BootReceiver。

## 约定

- 改动任务状态/模板/分类后，统一走 `DataSyncManager.settleAndRefresh()`（不要手工组合 rollover/重排提醒/刷新组件，防漏步）；仅外观设置改动仍调 `WidgetUpdater.refresh(context)`。
- 新语义靠纯函数 + JVM 单测落地（引擎、结算、裁剪），数据完整性靠 Robolectric 集成测试，UI 冒烟在 Pixel_6 模拟器上逐条截图验证。
- 已完成任务不消失：划线归到已完成区，点实心勾撤销；组件上整行点击即完成/取消。已作废的一次性任务归已作废区，可"重新开启"（从今天开始重算窗口）。
