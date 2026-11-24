# 花卉招标监控 · Flutter 版

安庆地区 **花卉 / 园林绿化招标公告监控** 的跨平台原生实现。与仓库里的安卓原生版（`../android/`）功能一致：
本地 SQLite 存数据、每天后台自采集、只显示「还能投」的公告。

采集内核是 **纯 Dart 重写**（不通过 JNI 复用 Java 代码），同一套逻辑可以跑在 Android / iOS 上。

## 运行

```powershell
# 环境变量（已在本机配置）
# PATH += D:\flutter\bin
# ANDROID_HOME = D:\Android\Sdk
# PUB_HOSTED_URL = https://pub.flutter-io.cn
# FLUTTER_STORAGE_BASE_URL = https://storage.flutter-io.cn

cd flutter_app
flutter pub get
flutter run                       # 连真机 / 模拟器
flutter test                      # 离线单测
flutter build apk --release       # -> build/app/outputs/flutter-apk/app-release.apk
```

Gradle 发行包走华为云镜像（`android/gradle/wrapper/gradle-wrapper.properties` 带 sha256），
避免 services.gradle.org 在国内超时。

debug 包的 applicationId 带 `.debug` 后缀（`com.wanhong.tender.debug`），
`flutter run` 不会覆盖手机上已安装的正式版、也不会继承它的数据库和后台任务；
要验证「从安卓版升级」的兼容路径，请用 release 包（与安卓版同包名、同调试签名）。

## 目录

| 路径 | 说明 |
| --- | --- |
| `lib/core/` | 纯 Dart 采集内核：站点采集器、epoint 检索、详情/截止/预算解析、关键词过滤、采集编排、下架校验、提醒判定 |
| `lib/data/` | sqflite 数据库（表结构与安卓版 v7 一致）、仓库实现、设置存储 |
| `lib/services/` | 组装点 `AppServices`、WorkManager 调度、本地通知 |
| `lib/ui/` | Material 3 原生界面（公告 / 关键词 / 采集 / 日志 / 设置） |
| `assets/keywords/` | 内置词表，与安卓版同一份 |

数据流与安卓版相同：`SiteCrawler` 只负责发现公告 → `CrawlEngine.crawl()` 统一做
「先排除后关键词匹配 → 详情补全 → 截止日期解析 → 去重入库 → 写日志」。

## 必须遵守的领域规则

- **截止日期只认正文里写明的日期**，解析不到就是「截止日期未知」，不做任何估算。
  `deadline_estimated` 仅为兼容旧库保留，恒为 false。
- **排除词只判定标题**，且先判排除词再做关键词匹配。
- **预算金额只认「预算金额」**；`最高限价` 可能是单价或比例，只展示原文、不参与排序。
- **命中 = 新增 + 重复**：重复项在关键词判定前就被 `findByUrlHash` 挡掉，也要计一次命中。
- **检索词上限是均匀取样**，不是取前 N 个。
- 时间一律用 `DateUtils.nowCn()`（UTC+8 墙钟、naive DateTime）与固定宽度 ISO 串，
  避免设备时区不是东八区时「是否过期」判断偏 8 小时。

## 后台任务

| 任务 | 唯一名 | 说明 |
| --- | --- | --- |
| 每日采集 | `flower-tender-daily` | 每天 `scheduleHour` 点，需联网、电量不低，以 `dataSync` 前台服务运行 |
| 截止提醒 | `flower-tender-reminder` | 采集后 30 分钟检查一次（同一天的数据），没有到期公告不发通知 |
| 手动采集 | `flower-tender-manual` | 需要「退到后台也继续跑」时用 |

- Android 14+ 必须声明前台服务类型：`android/gradle.properties` 里的
  `workmanager.enableDataSyncForegroundService=true` 不能删。
- WorkManager 的时间不精确，Doze / 国产 ROM 后台限制都可能推迟；页面上的「立即采集」始终可用。
- iOS 的 BGTask 标识已写进 `ios/Runner/Info.plist`，需在真机 / Xcode 下验证。
- 三个任务名与安卓原生版**同名**（见上表）。从安卓版覆盖安装上来时，WorkManager
  里留着旧版注册的记录，Worker 类是 Java 的 `CrawlWorker`/`ReminderWorker`。
  启动时的 `ensureScheduled()` 必须用 `ExistingPeriodicWorkPolicy.update` 重写这些记录，
  用 `keep` 会「保留」旧记录，到点反射加载不存在的 Java 类，Flutter 的采集永远不会跑。

## 与安卓原生版的关系

两个版本并行维护，共用同一套词表、站点规则与数据库结构。新增站点 / 修站点解析时，
`lib/core/crawler/` 与 `../android/core/.../crawler/` 需要同步改。

数据库文件名与 applicationId 都与安卓版相同（`com.wanhong.tender`、`flower_tender.db`），
但 Flutter 版库版本从 1 起算；覆盖安装遇到安卓版留下的 v7 库时会直接沿用、不删数据。
