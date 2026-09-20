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
| **采集** | 开始采集（选站点/时间窗口/关键词数量）、实时进度、**上次采集结果**、应用设置（定时开关、采集时间、是否抓正文、是否做下架校验、截止提醒） |
| **日志** | 最近 20 次采集的候选/排除/命中/新增/重复/失败与耗时 |

**顶部搜索框与筛选 chips 只属于「公告」页**：切到关键词 / 采集 / 日志时会自动隐藏
（那几页用不到公告的筛选条件），顶部只剩标题与刷新按钮，内容区随之整体上移。

### 「只看可投」——默认开启，过滤掉已经定标的结果公告

**为什么要有它**：三个站点都会把「成交结果公告」「中标通知书」「采购合同公告」
一起抓回来，这些是**已经定标**的结果类公告，对"还有哪些能投"没有意义。实测数据：

| | 条数 |
| --- | --- |
| 入库总计 | 28 |
| 未截止（改造前默认展示） | 18 |
| ↳ 其中成交 / 中标 / 合同类 | **10** |
| **真正可投的** | **8** |

更糟的是这些结果公告正文里没有报名截止时间，会被按「发布日 + 60 天」估出一个
**未来的假截止日**，于是"已定标"的项目顶着「还剩 48 天」混进未截止列表，
甚至因为「按截止排序」而排在最前面 —— 会让人误判。

现在的做法：

- 采集时给每条公告打 `noticeType`（`bid` / `award` / `contract` / `aborted`），
  判定见 `core/.../common/NoticeType.java`；
- **非可投公告不再做截止日估算**（正文里真写了日期仍照常解析）；
- 列表默认只显示 `bid`，顶部给出「已隐藏 N 条结果/合同公告 显示」的提示，
  点「显示」即可展开（想看成交价做参考时用得上）；
- 判定原则是**只排除"确定不是机会"的**：认不出类型的一律当作可投，
  宁可多显示一条也不要把真正的招标公告藏起来（更正 / 变更 / 澄清公告也仍然可投）。

「开始采集」按钮有三态，避免重复点击：可点 → **启动中…** → **采集中…**。
点击后是「入队 → WorkManager 真正开跑」，中间有几秒 `running` 还是 false，
所以专门做了启动宽限期，不会闪回空闲态。采集跑完会自动刷新统计与公告列表并提示。

### 截止提醒——别错过能投的项目

每天在**采集时间之后 30 分钟**自动检查一次，把「提前 N 天内截止」的公告汇总成
**一条**通知（默认提前 3 天，可设 1 / 3 / 7 天）：

```
花卉招标监控 · 现在
1 条公告即将截止
剩 3 天 · 怀宁县2026年度和美乡村市级中心村--公岭镇田铺行政村…
```

几个刻意的取舍：

- **只提醒"还能投"的**：已截止的不提醒（提醒没有意义），
  成交 / 中标 / 合同类也不提醒（已经定标了，见上面的「只看可投」）；
- **没有内容就不发通知**：每天准点报一句"没有内容"只会让人把通知权限关掉，
  反而漏掉真提醒；
- **独立通知渠道**「截止提醒」：和「采集任务」分开，静音采集进度不会连提醒一起静掉；
- **排在采集之后**：这样看到的是当天刚采完的数据，而不是昨天的库；
  也因此没有再给一个"提醒时间"的设置项 —— 少一个要理解的配置；
- **没做"单条提醒"**：每日汇总已经在截止前 3 天覆盖到了，单条提醒只在
  "某个项目想提前更久准备"时才有额外价值，暂不做（需要的话再加）。

设置页有「**立即检查提醒**」按钮，走的是与定时任务**完全相同**的判定逻辑，
用来确认提醒配好了，不用等到第二天。

### 采集完成通知：列出具体条目 + 点击直达新公告

采集跑完不再只说"新增 N 条"，而是把新公告的**标题和剩余天数**直接列在通知里：

```
发现 13 条可投的新公告
剩 3 天 · 怀宁县2026年度和美乡村市级中心村--公岭镇田铺行政村…
剩 7 天 · 安庆市殡葬管理所绿化养护服务采购项目竞争性磋商公告
…等 13 条
```

点一下**直达「新公告」筛选**（公告页 + 新公告 + 未截止 + 只看可投），不用自己去列表里翻。

两条路径都做了：

- **冷启动**：`MainActivity` 把 `?view=new` 拼在 URL 上，页面初始化时读一次；
  必须**在首次 `loadList` 之前**设好筛选状态，否则首次加载用默认条件、而 chips 高亮是"新公告"，两者对不上。
- **应用已开着**：走 `onNewIntent` → `evaluateJavascript("window.applyView('new')")`，
  **不重载页面**（否则会丢滚动位置和当前筛选）。

**踩过的坑**：排序一开始是直接按剩余天数升序，而已截止的天数是**负数**，
于是"已截止"项窜到了通知第一行 —— 折叠状态下只显示第一行，
用户点开看到的就是最没用的那条（实测 13 条新公告里 5 条已截止，恰好把这 5 条顶到最前）。
现在：未截止的按紧急度在前，已截止的整档垫后；若**全部**已截止，标题改成"都已过报名截止时间"。
而且这段挑选逻辑放在内核 `NewTender.mostUrgent` 里 —— 放在 Android 侧时 80 个测试一个都没拦住。

### 通知渠道：进度与重要提醒分开

| 渠道 | 内容 | 建议 |
| --- | --- | --- |
| **采集任务** | 采集进行中的进度通知 | 可以静音 |
| **重要提醒** | 发现新公告、公告快到截止时间 | **建议保留**（一静音就漏机会） |

采集进度是过程噪声，多半想静音；而"发现了新机会"一旦被一起静掉，这个通知就白发了。
两个通知的 `PendingIntent` 用了**不同的 requestCode**：`PendingIntent` 比较时忽略 extras，
同 requestCode 会让两个通知互相覆盖对方的 extras。

### 上次采集结果会落盘

「采集」页会显示上一次采集的结果：

```
SUCCESS  采集完成，新增 28 条
上次采集 09-20 23:19 · 耗时 107.3 秒
候选 68 · 命中 28 · 新增 28 · 重复 0 · 失败 0
```

**计数口径**：`命中 = 新增 + 重复`。重复项在进关键词判定之前就先被
`findByUrlHash` 判为"已存在"了，所以必须在这里也记一次命中 ——
否则日志里会出现「候选 40 · **命中 0** · 重复 7」这种自相矛盾的数字
（重复 7 条必然是先前命中过才会入库）。

**踩过的坑**：这个汇总以前只活在进程内存里（`CrawlEngine.getLastSummary`），
应用被系统回收或手动重启后就退回"尚未执行采集"，而「日志」页明明有历史记录、
统计也还在 —— 两页自相矛盾，看着像数据丢了。

现在在采集结束时把汇总**落一份快照**到 SharedPreferences（收口在
`CrawlSummaryBridge.run`，手动/定时采集都经过它），进程重启后
`/api/crawl/status` 会回退到这份快照。

> 为什么不直接从数据库日志反推：`crawl_log` 是**按站点**存的（一次采集 3 条），
> 没有"这几条属于同一次采集"的分组信息，拼不出"新增 28 条 / 耗时 107 秒"这个汇总数字。
> 真要做得干净，需要给日志加 `run_id` 再聚合 —— 那是更大的改动，暂不需要。

**截止日期**是手机端最关心的信息，做成三级配色徽标：
`剩 N 天`（≤3 天红、≤7 天橙、其余绿）、`今天截止`、`已截止`；
正文里解析不出来的会按「发布日 + 60 天」估算，并打「估算」标记（详情页会提示以原文为准）。

---

## 四、自运行的原理与限制

- **调度**：用 WorkManager 的每日周期任务（`CrawlScheduler`）。设备重启后系统会自动恢复，
  不用额外写开机广播；同时声明了"需要联网""电量不低"的约束。
  截止提醒是**另一个独立的周期任务**（`ReminderScheduler`），排在采集之后 30 分钟 ——
  采集失败或被跳过时提醒仍然会跑，因为提醒用的是已经入库的数据。
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

| 依赖 | 要求 | 本机现状（换机后实测） |
| --- | --- | --- |
| JDK | 17（21 亦可） | **JDK 21.0.11**；字节码仍按 `release 17` 编译，58 个测试全过 |
| Gradle | 8.7 | wrapper 已入库（`gradlew` / `gradlew.bat` / `gradle/wrapper/`），首次运行自动下载 |
| Android SDK | platform-35 + build-tools 35.0.0 + platform-tools | 装在 `D:/Android/Sdk` |
| `android/local.properties` | 指向本机 SDK | 已生成；与机器相关，已 gitignore，**不入库** |

```properties
# android/local.properties
sdk.dir=D:/Android/Sdk
```

> 从零装 SDK（约 300 MB，需要接受 Google 许可）：
>
> ```powershell
> # 1) 下载 commandline-tools 的 zip，解压成 D:\Android\Sdk\cmdline-tools\latest\
> # 2) 装组件
> $sm = 'D:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat'
> & $sm --sdk_root=D:\Android\Sdk --licenses
> & $sm --sdk_root=D:\Android\Sdk --install 'platform-tools' 'platforms;android-35' 'build-tools;35.0.0'
> ```
>
> Gradle 发行包走 `gradle/wrapper/gradle-wrapper.properties` 里的腾讯云镜像
> （官方 `services.gradle.org` 在国内常连不上，`gradle wrapper` 任务甚至会直接报
> `Test of distribution url ... failed`），并带 `distributionSha256Sum` 校验。

### 命令

```powershell
cd android

# 内核单测：57 个离线用例 + 1 个联调用例（默认跳过）
.\gradlew.bat :core:test

# 真实站点联调（联网，约 1 分钟）
.\gradlew.bat :core:test --tests '*LiveCrawlIT' -Dlive=true
#   报告会写到 android/core/build/live-crawl-report.txt

# 出安装包
.\gradlew.bat :app:assembleRelease     # -> app/build/outputs/apk/release/app-release.apk
.\gradlew.bat :app:assembleDebug       # -> app/build/outputs/apk/debug/app-debug.apk

# 代码/资源静态检查（会检查低版本安卓不存在的 API 调用）
.\gradlew.bat :app:lintDebug
```

> Linux / macOS 把 `.\gradlew.bat` 换成 `./gradlew`。
> 用 Android Studio 打开 `android/` 目录即可（它会识别为 Gradle 工程，可直接运行到手机）。

> 国内网络下构建：`settings.gradle` 已把阿里云镜像放在最前面；Gradle 发行包走 wrapper 里的腾讯云镜像，正常能拉动依赖。

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

### 词表顺序会影响"少检索几个词"的效果

采集页的「检索词数量上限」是**在整张词表上均匀取样**（首尾都取到），
不是取前 N 个。原因：`flower-keywords.txt` 是「窄 → 宽」排列的
（花卉 / 花卉苗木 / 花卉种植 / 花卉采购 / 花卉租摆 在前，绿化 / 园林 / 景观 在后），
取前 N 个等于**专挑最窄的词**。实测「前 5 个窄词 + 90 天窗口」候选 **0**，
而同样 5 个词的均匀取样是候选 13 —— 用户会以为应用坏了。

所以：**想扩召回就往词表里加宽泛词；想快就填上限，缩的是覆盖密度而不是召回面。**

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
| **一点「开始采集」应用就闪退**（Android 14 模拟器/真机实测必现） | **踩过一次**：`CrawlWorker` 用的是 `new ForegroundInfo(id, notification)`，而 WorkManager 2.9.1 的这个两参构造函数内部写死 `foregroundServiceType = 0`（NONE）；Android 14 + targetSdk 34 禁止以「无类型」启动前台服务，于是抛 `InvalidForegroundServiceTypeException: Starting FGS with type none` 并杀进程。**清单里声明 `dataSync` 也没用**，会被这个显式 0 覆盖。修法：改用三参构造，显式传 `FOREGROUND_SERVICE_TYPE_DATA_SYNC` |
| 换机器后 `:app` 编译报「找不到符号 / 程序包 com.wanhong.tender.data 不存在」 | **踩过一次**：`.gitignore` 里写的是不带斜杠的 `data/`，它匹配任意层级的 `data` 目录，把安卓源码包 `app/src/main/java/com/wanhong/tender/data/`（SQLite 数据层三个类）一起忽略了 —— 这几个文件从来没进过 git，一拉代码就编译不过。已改成锚定根目录的 `/data/`；万一再遇到，用 `node tools/dsh-recover-files-from-session.mjs --log <会话日志> --match "tender/data" --out . --crlf` 从会话日志里恢复 |

---

## 九、已验证 / 未验证

**已验证**（在这台电脑上）：

- 内核 **89 个单元测试**（88 个离线用例全过，1 个真实站点联调用例默认跳过），其中包含
  **整条采集链路的离线端到端测试**（假的站点采集器 + 假的 HTTP + 内存存储，覆盖关键词过滤、
  排除词、截止日期解析与估算、区县、去重、增量、正文兜底、计数），
  **公告类型判定**（7 个用例）、**截止提醒的挑选/文案**（10 个用例），
  以及**通知文案与选词口径**（`NewTender` 11 个 + `evenlySample` 2 个，
  含"已截止不能排第一""23 小时前也算已截止""取样必须覆盖宽泛词"等回归点）；
- **真实站点联调通过**：三个站点全部采到数据（19 条，0 失败），
  正文、截止日期（正文解析 / 估算）、区县、分类、链接状态都正确 ——
  这说明移植到安卓后采集逻辑没有走样；
- 安卓 App 编译通过并打出可安装的 release APK（已签名），
  manifest 合并、词表资源打包、assets 页面打包均已核对；
- Android Lint 无 `NewApi` 问题（即没有调用低版本安卓不存在的 API，不会一启动就崩）；
- 页面静态自检通过（JS 语法、68 个元素 id、手机端适配项）。

**已在 Android 14 模拟器上跑通**（雷电模拟器：Android 14 / API 34 / x86_64 / 1920x1080）：

- release APK 安装、启动、界面渲染正常；WebView + 应用内本地服务（`127.0.0.1:8765`）正常；
- **从页面触发一次真实全量采集，完整跑完**：
  候选 68 → 排除 23 → 命中 28 → 新增 28，失败 0，143 次请求 / 107 秒；
  入库数据的标题 / 区县 / 分类 / 截止日期 / 命中的关键词 / 链接状态都正确；
- 采集期间前台服务通知正常挂出（渠道「采集任务」），进程全程未被系统杀掉；
- 权限与后台行为：`POST_NOTIFICATIONS` 授权流程正常，Android 14 的
  `Background started FGS: Allowed` 放行，无 `ForegroundServiceStartNotAllowedException`；
- **「只看可投」在真实数据上验证过**：默认列表从 18 条降到 **8 条**，
  「未截止」里非可投公告 **0 条**（改造前有 10 条挂着估算出来的假截止日）；
  「全部」视图显示 13 条可投并提示「已隐藏 15 条结果/合同公告」；
- **数据库 v1 → v3 迁移在真机上验证过**：28 条老数据全部回填了 `notice_type`，
  且估算出来的假截止日被订正清空（`run-as` 对 release 包不可用，改用本地 REST 接口核对）；
- **截止提醒在真机上验证过**：`/api/reminder/check` 按「提前 3 天」口径挑出 1 条
  （`剩 3 天 · 怀宁县2026年度和美乡村…`），通知栏出现
  「1 条公告即将截止」且走的是独立渠道 `deadline_reminder`；
  调度日志确认已排期「每天 8:00 采集之后 30 分钟」；
- **「上次采集结果」跨进程重启在真机上验证过**：跑一次采集拿到汇总
  （含 `finishedAt`）→ `am force-stop` 强杀进程 → 重新启动 →
  `/api/crawl/status` 的 `lastSummary` 仍在，「采集」页正常显示
  「上次采集 09-20 23:19 · 耗时 1.1 秒」，不再退回"尚未执行采集"；
- **采集完成通知与点击直达在真机上验证过**：`pm clear` 后全新首采（新增 28 条 /
  13 条可投），通知标题「发现 13 条可投的新公告」走独立渠道 `deadline_reminder`；
  **冷启动**（`am start … --es view new`）落在「公告 + 新公告 + 未截止 + 只看可投」，
  **运行中**再发一次 intent 时 `LAUNCH_SINGLE_TOP` 投递到 `onNewIntent`，
  PID 不变、页面不重载即切回新公告视图；
- **「最近 90 天」与「不限」两个时间窗都实测过**：分别返回候选 13 / 候选 40，均无异常；
- **`maxKeywords` 均匀取样在真实站点上验证过**：同样的
  `maxKeywords=5&lookbackDays=90`，修复前候选 **0**、修复后候选 **13 / 命中 7 / 新增 7**。

> 这一轮实测**抓出一个必现闪退**：一点「开始采集」就崩
> （`InvalidForegroundServiceTypeException: Starting FGS with type none`）。
> 上一版文档写的"`foregroundServiceType` 已核对"是**错的** —— 清单里声明了 `dataSync`，
> 但 `CrawlWorker` 用 WorkManager 的两参 `ForegroundInfo` 构造函数，它内部把类型写死成 `0`，
> 覆盖了清单声明。已改用三参构造显式传 `FOREGROUND_SERVICE_TYPE_DATA_SYNC`，详见第八节常见问题。

**仍未验证**：

- 真机（尤其是小米 / 华为 / OPPO 等国产 ROM）上的界面渲染与后台存活；
- 定时任务在具体机型 ROM 上的实际执行情况（受各家省电策略影响很大）；
- 通知在真机 Android 13/14 上的展示细节（模拟器上只能确认通知已挂出）。

装到手机上后如果哪里不对，用这个抓日志发我，定位很快：

```bash
adb logcat -s TenderApp CrawlerHolder LocalApiServer CrawlWorker AndroidRuntime
```
