# 花卉招标监控 · 安卓版

手机本地自运行的招标公告监控 App：自己在后台抓取、自己存本地、自己定时跑，不依赖电脑。

- **包名**：`com.wanhong.tender`
- **最低版本**：Android 8.0（API 26）
- **目标版本**：Android 14（API 34）
- **安装包**：`app/build/outputs/apk/release/app-release.apk`（约 3 MB）

---

## 一、安装到手机

### 1. 取安装包

在电脑上构建后，安装包在：

```
android/app/build/outputs/apk/release/app-release.apk
```

（`app-debug.apk` 也能装，但包名带 `.debug` 后缀、体积更大，日常用 release 版即可。）

### 2. 传到手机

任选一种：微信/QQ 文件传输助手、数据线拷贝、或在同一 WiFi 下用手机浏览器访问电脑的临时 HTTP 服务。

### 3. 安装

用手机上的文件管理器点开 APK 安装。首次会提示"未知来源应用"，允许即可
（个人自用应用没有上应用商店，这是正常的）。

### 4. 用 adb 安装（可选，电脑连了数据线时最省事）

```bash
# 手机开启「开发者选项 → USB 调试」后
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe" install -r \
  android/app/build/outputs/apk/release/app-release.apk
```

---

## 二、首次使用（重要）

### 1. 允许通知

Android 13+ 会弹窗询问通知权限，**必须允许** —— 后台采集的进度和结果都靠通知栏提示。
拒绝了可以到「系统设置 → 应用 → 花卉招标监控 → 通知」里重新打开。

### 2. 允许后台自运行（必读）

**这是 App 能不能"自运行"的关键**，而且是系统限制，不是应用的问题：
安卓会冻结/杀掉后台应用。请按下面做一遍：

1. 打开 App → 底部「采集」→「应用设置」→ 点「**点此打开电池优化设置**」，选择"允许"；
2. 再到系统设置里（各品牌位置不同，通常在「应用管理 → 花卉招标监控」）打开：
   - **自启动**（小米/红米：安全中心 → 应用管理 → 权限 → 自启动）
   - **后台运行 / 后台活动**（华为/荣耀：应用启动管理 → 手动管理 → 允许后台活动，并允许"关联启动"）
   - **省电策略**改为"无限制"（OPPO/vivo/一加：电池 → 应用耗电管理 → 允许后台行为）
   - 最近任务里给应用**加锁**（防止一键清理时被杀）

> 如果没做这一步：手动点"开始采集"能正常跑，但**每天定时的自动采集不会执行**。

### 3. 第一次采集建议先建历史基线

「采集」页 → 采集范围选「全部站点」→ 时间窗口选「**不限（首次建库，较慢）**」→ 点开始采集。
一次全量约 5~10 分钟、400 多次请求，会在通知栏显示进度，可以锁屏让它自己跑。

之后把时间窗口改回「最近 30 天」，每天自动增量即可（30 天窗口实测能把采集时间从 ~440 秒降到 ~100 秒）。

### 4. 顺手调一下词表

底部「关键词」页：能看到每个词命中了多少条公告，长期为 0 的词可以删掉；
发现漏了某个词（比如「绿篱」「水生植物」），直接添加，然后点「重扫已入库公告」——
**不用重新爬一遍**就能看到历史数据里的命中结果。

---

## 三、功能

底部四个 Tab：

| Tab | 作用 |
| --- | --- |
| **公告** | 卡片列表。默认「未截止 + 按截止时间升序」（最紧急的排最前）；筛选 chips：未截止 / 新公告 / 收藏 / 全部 / 按截止排序；点标题看全文与附件，右上角星标收藏，右滑关闭详情 |
| **关键词** | 词表展示（带命中数）、添加/删除/停用、重扫已入库公告、按当前词表采集 |
| **采集** | 开始采集（选站点/时间窗口/关键词数量）、实时进度、应用设置（定时开关、采集时间、是否抓正文、是否做下架校验） |
| **日志** | 最近 20 次采集的候选/排除/命中/新增/重复/失败与耗时 |

**截止日期**是手机端最关心的信息，做成三级配色徽标：
`剩 N 天`（≤3 天红、≤7 天橙、其余绿）、`今天截止`、`已截止`；
正文里解析不出来的会按「发布日 + 60 天」估算，并打「估算」标记（详情页会提示以原文为准）。

---

## 四、自运行的原理与限制

- **调度**：用 WorkManager 的每日周期任务（`CrawlScheduler`）。设备重启后系统会自动恢复，
  不用额外写开机广播；同时声明了"需要联网""电量不低"的约束。
- **长任务保护**：采集时用**前台服务 + 通知栏进度**（`CrawlWorker.setForegroundAsync`），
  这样几分钟的采集不会被系统掐掉。
- **时间不精确**：WorkManager 是"机会式"调度，Doze/省电模式下会被推迟，
  可能比设定时间晚几分钟到几十分钟。这是安卓正常行为，不是 bug。
- **最短周期**：WorkManager 周期任务最小间隔是 15 分钟，所以做不到"每分钟跑一次"。
  本 App 是每天一次 + 手动立即采集。
- **国产 ROM**：如果没做第二节的"允许后台自运行"，定时任务会被直接杀掉。

---

## 五、架构

```
android/
├── core/                纯 Java 模块（可在电脑上跑单测，也可被安卓引用）
│   └── src/main/java/com/wanhong/crawler/
│       ├── common/      日期解析、URL 摘要、分类名、区县
│       ├── config/      CrawlerSettings（配置）
│       ├── http/        HttpTransport 端口 + HttpFetcher（UA轮换/限速/重试/cookie预热/证书降级）
│       ├── crawler/     三个站点采集器、详情页解析、截止日期解析
│       ├── filter/      关键词规则（标题词/正文词/排除词 + 用户自定义）
│       └── pipeline/    采集编排 CrawlEngine、查询、关键词维护、下架校验
└── app/                 安卓应用
    └── src/main/
        ├── assets/index.html          页面（手机端界面，唯一维护源）
        ├── java/com/wanhong/tender/
        │   ├── core/        CrawlerHolder：把内核与安卓实现接起来
        │   ├── data/        SQLite（TenderDbHelper / SqliteTenderRepository / SqliteKeywordStore）
        │   ├── net/         OkHttpTransport：HttpTransport 的安卓实现
        │   ├── crawl/       CrawlWorker（前台服务）+ CrawlScheduler + 通知
        │   ├── server/      LocalApiServer：应用内本地 HTTP 服务
        │   └── ui/          MainActivity（WebView）
        └── res/             图标、主题、字符串
```

### 三个关键设计

1. **采集内核与平台解耦**。内核只依赖一个 `HttpTransport` 接口和 `TenderRepository` 接口：
   - 安卓：`OkHttpTransport` + SQLite；
   - 电脑/测试：`JdkHttpTransport`（测试夹具）+ 内存实现。

   好处是**同一份采集逻辑既能在手机上跑，也能在电脑上跑单测和真实站点联调**，
   移植之后不存在"两套逻辑逐渐跑偏"的问题。

2. **界面复用而不是重写**。现有手机端页面已经调好，App 内起一个只监听 `127.0.0.1` 的
   本地 HTTP 服务（NanoHTTPD）提供同样的 REST 接口，WebView 指向它 ——
   **页面代码一行没改**。附带好处：你也可以用手机浏览器直接打开 `http://127.0.0.1:8765`
   查看（App 在前台运行时）。

3. **所有 JSON 输出统一走 `Json.mapper()`**（`android/core/.../pipeline/Json.java`）。
   这不是风格问题而是必须的：安卓上直接 `new ObjectMapper()` **不带 JavaTimeModule**，
   序列化 `java.time.LocalDateTime` 会直接抛
   `Java 8 date/time type 'java.time.LocalDateTime' not supported by default`，
   接口全部 500。旧版跑在 Spring Boot 上时 Spring 自动注册了该模块，所以移植时没暴露 ——
   真机上一次就踩到了。`JsonSerializationTest` 现在专门盯着这个行为。

### 证书问题（三个站点都有）

| 站点 | 问题 |
| --- | --- |
| aqggzy / cgj | 证书链不完整（JDK 报 PKIX path building failed） |
| wyxmzx | 证书已过期，且 SAN 里没有该域名 |

安卓侧的处理：`OkHttpTransport` 对 `settings.request.insecureTlsHosts` 里的域名使用
**按域名放宽**的客户端（信任所有证书 + 跳过主机名校验），内核在遇到证书错误时会把该域名
自动加进列表并打日志。比"全局关闭校验"安全得多，也比电脑端的 JVM 级开关精细。

---

## 六、重新构建

### 环境

- JDK 17
- Android SDK：platform 35 + build-tools 35（本项目构建机器上已有）
- Gradle 8.7（仓库自带 wrapper，`gradlew` 会自动下载）
- `android/local.properties` 里指向你的 SDK（该文件与机器相关，已 gitignore）

```properties
sdk.dir=C:/Users/<你的用户名>/AppData/Local/Android/Sdk
```

### 命令

```bash
cd android

# 跑内核单测（50 个，不联网）
gradle :core:test

# 真实站点联调（联网，约 1 分钟）
gradle :core:test --tests '*LiveCrawlIT' -Dlive=true
#   报告会写到 android/core/build/live-crawl-report.txt

# 出安装包
gradle :app:assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
gradle :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk

# 代码/资源静态检查（会检查低版本安卓不存在的 API 调用）
gradle :app:lintDebug
```

用 Android Studio 打开 `android/` 目录即可（它会识别为 Gradle 工程，可直接运行到手机）。

> 国内网络下构建：`settings.gradle` 已把阿里云镜像放在最前面，正常能拉动依赖。

---

## 七、改词表 / 改界面

- **改关键词（日常）**：用 App 内的「关键词」页，改动存本地数据库，**不用重新打包**。
- **改内置词表**：编辑 `android/core/src/main/resources/keywords/*.txt` 后重新构建。
  三个文件：`flower-keywords.txt`（标题词/检索词）、`flower-core-keywords.txt`（正文词）、
  `exclude-keywords.txt`（排除词）。
- **改界面**：编辑 `android/app/src/main/assets/index.html`（唯一维护源），
  改完重新构建。可以用 `tools/check-mobile-page.js` 做静态自检：

  ```bash
  node tools/check-mobile-page.js android/app/src/main/assets/index.html android/app/src/main/java
  ```

---

## 八、常见问题

| 现象 | 原因与处理 |
| --- | --- |
| 打开白屏 / 提示"本地服务连接失败" | 本地服务未起来。退出应用重开；仍不行看 `adb logcat -s TenderApp LocalApiServer` |
| 定时采集不执行 | 99% 是系统限制：按第二节第 2 步允许自启动 / 后台运行 / 电池白名单 |
| 手动采集能跑但很慢 | 正常。一次全量 5~10 分钟；把时间窗口改成「最近 30 天」可显著加快；关掉「抓取详情页正文」能再快约 5 倍 |
| 某些公告点开是「跳转链接」 | 交易服务网索引里有县级转载公告，本站没有落地页（实测任何拼法都 404）。App 会自动改用官方跳转地址，避免死链 |
| 截止日期显示「估算」 | 正文里没写报名/投标截止时间，按「发布日 + 60 天」估算。投标前请以原文为准 |
| 通知栏没有进度 | 通知权限没给；或系统把应用的后台通知关了 |
| 想清空数据重来 | 系统设置里清除应用数据，或卸载重装（数据库在应用私有目录） |

---

## 九、已验证 / 未验证

**已验证**（在这台电脑上）：

- 内核 50 个单元测试全部通过，其中包含**整条采集链路的离线端到端测试**
  （假的站点采集器 + 假的 HTTP + 内存存储，覆盖关键词过滤、排除词、截止日期解析与估算、
  区县、去重、增量、正文兜底、计数）；
- **真实站点联调通过**：三个站点全部采到数据（19 条，0 失败），
  正文、截止日期（正文解析 / 估算）、区县、分类、链接状态都正确 ——
  这说明移植到安卓后采集逻辑没有走样；
- 安卓 App 编译通过并打出可安装的 release APK（已签名），
  manifest 合并、`foregroundServiceType`、词表资源打包、assets 页面打包均已核对；
- Android Lint 无 `NewApi` 问题（即没有调用低版本安卓不存在的 API，不会一启动就崩）；
- 页面静态自检通过（JS 语法、61 个元素 id、手机端适配项）。

**未验证**（因为这台机器没有连接手机、也没有可用的模拟器镜像）：

- 真机上的界面渲染效果、WebView + 本地服务在真机上的表现；
- 定时任务在具体机型 ROM 上的实际执行情况（受各家省电策略影响很大）；
- 通知在 Android 13/14 上的展示细节。

装到手机上后如果哪里不对，用这个抓日志发我，定位很快：

```bash
adb logcat -s TenderApp CrawlerHolder LocalApiServer CrawlWorker AndroidRuntime
```
