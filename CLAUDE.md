# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目定位

安庆地区 **花卉 / 园林绿化招标公告监控**。从四个政府/交易站点定向采集公告，过滤噪声、解析报名截止日期，供"还有哪些能投"使用。

**当前维护的产品是安卓 App（`android/`）**：装在手机上、本地 SQLite 存数据、每天后台自采集。上一版 Spring Boot + 网页服务已于 2026-11-24 从仓库删除，代码保留在 git 历史里（最后一个包含它的提交是 `31cdea3`，`git show 31cdea3:pom.xml` 可取回）。

**并行分支上的 `flutter_app/` 是 Flutter 原生版**（分支 `flutter`）：采集内核用 Dart 重写，
界面是 Material 3 原生页面（不再是 WebView）。两版共用词表、站点规则与数据库结构；
改站点解析时两边都要同步。安卓原生版仍是主线。

## 仓库布局

| 路径 | 说明 |
| --- | --- |
| `android/core/` | 纯 Java 采集内核（Gradle `java-library`，无安卓依赖），可在电脑上跑单测和真实站点联调 |
| `android/app/` | 安卓壳：WebView + 应用内本地 HTTP + WorkManager + SQLite |
| `flutter_app/` | Flutter 跨平台版：Dart 内核（`lib/core`）+ sqflite 数据层 + Material 3 界面 + WorkManager |
| `tools/verify.ps1` | 一键自检（页面静态检查 → 内核单测 → release 打包） |
| `tools/check-mobile-page.js` | 页面静态自检（JS 语法、元素 id、调用的接口、手机适配项） |
| `tools/dsh-*.mjs` | AI 会话历史迁移/恢复工具，与产品无关 |
| `android/tools/*.js` | 一次性的源码改写脚本（幂等），不是运行时代码 |

内置词表在 **`android/core/src/main/resources/keywords/*.txt`**。

## 常用命令

Gradle 命令必须在 `android/` 下执行（Linux/macOS 用 `./gradlew`）。

```powershell
# 全量自检：页面检查 + 96 个离线单测 + release 打包（CI 也调这一个）
powershell -ExecutionPolicy Bypass -File tools\verify.ps1
powershell -ExecutionPolicy Bypass -File tools\verify.ps1 -SkipBuild   # 只改 core 逻辑时

cd android
.\gradlew.bat :core:test                                   # 内核单测
.\gradlew.bat :core:test --tests '*BudgetExtractorTest'    # 单个测试类
.\gradlew.bat :core:test --tests '*LiveCrawlIT' -Dlive=true  # 联网打真实站点（默认跳过，约 1 分钟）
.\gradlew.bat :app:assembleRelease                         # -> app/build/outputs/apk/release/app-release.apk
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:lintDebug                               # 查低版本安卓不存在的 API（NewApi）

# 改了 assets/index.html 后单独检查页面
node tools/check-mobile-page.js android/app/src/main/assets/index.html android/app/src/main/java
```

`LiveCrawlIT` 的报告写在 `android/core/build/live-crawl-report.txt`。

Flutter 版（`flutter_app/`）：

```powershell
# 本机需要：PATH 含 D:\flutter\bin，ANDROID_HOME=D:\Android\Sdk，
# PUB_HOSTED_URL=https://pub.flutter-io.cn，FLUTTER_STORAGE_BASE_URL=https://storage.flutter-io.cn
cd flutter_app
flutter pub get
flutter test                                              # 离线单测
flutter test test/live --dart-define=LIVE=true            # 联网打真实 zh0556，约 30 秒
flutter build apk --release                               # -> build/app/outputs/flutter-apk/
```

- `flutter_local_notifications 23` 要求 **compileSdk 37**（平台包 `platforms;android-37.0`），
  已在 `android/app/build.gradle.kts` 显式抬高，不要退回 `flutter.compileSdkVersion`。
- Windows 上 Kotlin 增量缓存偶发关不掉，`android/gradle.properties` 里
  `kotlin.incremental=false` 是为了构建稳定，不要随便打开。
- Gradle 发行包走华为云镜像并带 sha256（`android/gradle/wrapper/gradle-wrapper.properties`）。

`tools/verify.ps1`、`tools/emulator-log-monitor.ps1` **必须保存为 UTF-8 带 BOM**：Windows PowerShell 5.1 会把无 BOM 的 `.ps1` 按 GBK 解码，中文注释会撑坏语法。

## 架构：内核与平台解耦

这是理解整个项目的关键。`android/core` 只依赖两个接口，不知道安卓存在：

- `http/HttpTransport` —— 安卓实现是 `OkHttpTransport`；测试用 `FakeHttpTransport`。
- `pipeline/TenderRepository` —— 安卓实现是 `SqliteTenderRepository`；测试用 `InMemoryTenderRepository`。

`android/app/.../core/CrawlerHolder` 是唯一的组装点（应用内单例）：把 `CrawlerSettings`、`OkHttpTransport`、`HttpFetcher`、`KeywordRules`、三个 `SiteCrawler`、`CrawlEngine`、`TenderQueryService`、`KeywordService`、`LinkVerifier` 接起来。**加新站点 = 实现 `SiteCrawler` + 在 `CrawlerHolder` 注册 + 在 `CrawlerSettings.Sites` 加配置**。

数据流：`SiteCrawler` 只负责"发现公告"（标题/链接/日期/分类/区县）→ `CrawlEngine.crawl()` 统一做关键词过滤、详情补全、截止日期解析、去重、入库、写日志。

**内核与 UI 之间是 REST 协议，不是函数调用**：`LocalApiServer`（NanoHTTPD，仅 `127.0.0.1:8765`）实现一套接口，`assets/index.html` 通过 fetch 调用。所以页面里调用的每个 `/api/*` 都必须能在 `LocalApiServer` 找到——`check-mobile-page.js` 会做这个交叉校验。

## 几条必须遵守的实现约束

**JSON 一律走 `pipeline/Json.mapper()`**。安卓上裸 `new ObjectMapper()` 不带 `JavaTimeModule`，序列化 `LocalDateTime` 会直接抛异常、接口全 500。`JsonSerializationTest` 专门盯这个。

**页面只有一份维护源**：`android/app/src/main/assets/index.html`。改完必须过 `check-mobile-page.js`，否则会出现"JS 引用了不存在的 id"或"调了后端没有的接口"。

**WebView 不实现的能力不要依赖**：`navigator.clipboard` 在页面失焦时被系统拒绝，复制/分享/加日历走 `AndroidApp` JS 桥；`window.confirm` 在未实现 `onJsConfirm` 时静默返回 false，确认交互用按钮自身变确认态。

**前台服务必须显式传类型**：`CrawlWorker` 用 WorkManager 的三参 `ForegroundInfo(id, notification, FOREGROUND_SERVICE_TYPE_DATA_SYNC)`。两参构造内部把类型写死为 0，Android 14 + targetSdk 34 会抛 `InvalidForegroundServiceTypeException` 并杀进程，清单里声明 `dataSync` 也救不回来。

**SQLite 迁移是手写的**：`data/TenderDbHelper`，`DB_VERSION = 7`，`onUpgrade` 逐版本升级。加字段时**同时用已入库数据回填**（历史迁移都是拿库里的正文重算），不要要求用户重新采集。改 `versionCode`/`versionName` 每次打包递增，应用设置页会显示。

**时区固定 `Asia/Shanghai`**：截止日期与"是否过期"都依赖 `crawler.timezone`，容器/设备默认 UTC 会让过期判断偏 8 小时。

## 领域规则（容易写错的地方）

- **截止日期**：只认正文里写明的日期，解析不到就是「未知」，**不做任何估算**（「发布日 + N 天」估出来的日子与真实报名截止无关，实测会让早已截止的公告显示「还剩 45 天」）。`deadline_estimated` 字段/列仅兼容旧库与导出，恒为 0。时间分隔符要收全 `[:：时点]`，分钟可选必须写 `(?:\d{1,2})?`——写成 `\d{1,2}?` 是懒惰匹配，会把 `09:30` 截成 `09:3`。
- **公告类型** `common/NoticeType`：`bid` / `award` / `contract` / `aborted`。结果类公告没有报名截止时间，本来就不该有截止日；列表默认只显示 `bid`，但认不出类型的一律当可投。
- **排除词只判定标题**，且**先判标题排除词再做关键词匹配**。这是系统能用的关键：`养护`/`绿化`/`园林` 太宽泛，不排除会捞进车辆维修、厕所管护、公路养护、资产拍卖。
- **预算金额只认「预算金额」**。`最高限价` 可能是单价（`3.4元/平方米/年`）或比例（`审定金额的95%`），只展示原文、不参与排序；`预算金额：/` 视为无金额，不能当 0。
- **命中 = 新增 + 重复**。重复项在关键词判定前就被 `findByUrlHash` 挡掉了，所以也要计一次命中，否则日志出现「命中 0 · 重复 7」。
- **检索词上限是均匀取样**，不是取前 N 个。词表按「窄 → 宽」排列，取前 N 等于只挑最窄的词（实测候选 0）。

## 四个站点的坑

| 站点 | 抓取方式 | 关键约束 |
| --- | --- | --- |
| aqggzy | epoint 检索接口 | `wd` 不能为空（所以是**关键词驱动**）；`categorynum` 以 `002` 开头是新闻，丢弃；县级转载公告本站无落地页，链接改用 `/tiaozhuan.html` 并标「跳转链接」 |
| cgj | 静态 HTML 列表 `ul.doc_list li` | 翻页 `?pageIndex=N`，**页码从 1 开始** |
| wyxmzx | epoint 检索接口（**已退化成列表**） | 搜索请求**不能带 `Referer`**（400/CSRF），也**不能带 `Origin`**（503）；自 2026-11-24 起 https 的检索接口与详情页都返回 404，`baseUrl` 必须用 `http://www.wyxmzx.com`；接口**忽略 `wd`/`cnum`/`time`**、`fields` 必须为空串，实际只按 `webdate` 倒序返回全部公告，所以采集改成翻页（`maxPages`）、时间窗在本地过滤、关键词交给引擎 |
| zh0556 | 静态列表页，按「类目×类型」翻页（无检索接口） | 列表项必须在 `div.projectlist ul li` 里选（导航也是 `li`）；日期带方括号；`[报名已结束]` 不是截止时间；详情页 `<title>` 是站点名，**标题只能取列表 `<a>` 文本**；默认跳过拍卖拍租 |

默认 `lookbackDays=30` 时间窗；不加窗会返回大量早已过期的老公告。首次建库用 0。

注意 zh0556 **没有「花卉」标**：站内搜索「花卉 / 草花 / 月季」均为 0 条，
能命中的是「苗木 / 绿化 / 园林 / 绿植」。它补的是苗木绿化线，不要按「能搜到花卉」去承诺。

## 工程约定

- `.gitattributes`：仓库内存 LF，`*.bat` 强制 CRLF，二进制扩展名 `binary`。
- `android/local.properties`（SDK 路径）与 `logs/` 不入库。
- `.gitignore` 里运行时数据的忽略项必须锚定根目录（`/data/`）：写成 `data/` 会连安卓源码包 `.../tender/data/` 一起忽略。
- 依赖走阿里云镜像（`settings.gradle`），Gradle 发行包走腾讯云镜像并带 `sha256` 校验。
