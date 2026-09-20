# 安庆花卉 / 园林绿化招标监控

针对以下三个站点定向采集 **花卉、苗木、园林绿化**相关的招标 / 采购 / 中标 / 合同公告，
过滤噪声、解析报名投标截止日期，供手机端随时查看"还有哪些能投"：

| 站点 | 地址 | 站点性质 |
| --- | --- | --- |
| 安庆市公共资源交易服务网 | https://aqggzy.anqing.gov.cn/ | epoint 站群，列表由 AJAX 渲染 |
| 安庆市城市管理局 · 通知公告 | https://cgj.anqing.gov.cn/xxfb/tzgg/ | 政府站群，列表为服务端渲染静态 HTML |
| 安庆市阳光采购交易服务平台 | http://www.wyxmzx.com/jyxx/ | 安庆市皖宜项目咨询管理有限公司旗下平台，epoint 站群 |

## 现在的主线是安卓 App

**日常使用请看 → [android/README.md](android/README.md)**（安装、后台自运行设置、构建与排错）。

安卓 App 的特点：**装在手机上、本地存数据、每天自己在后台采集**，不依赖电脑。
安装包构建后位于 `android/app/build/outputs/apk/release/app-release.apk`。

### 仓库结构

| 目录 | 说明 |
| --- | --- |
| `android/` | **当前维护的产品**：安卓 App + 共用采集内核（`android/core`，纯 Java，可在电脑上跑单测与真实站点联调） |
| `src/` + `pom.xml` | 上一版 **Spring Boot + 网页** 服务，**已停用**，仅作参考保留（其中 `src/main/resources/keywords/*.txt` 的词表已复制到 `android/core`，后续以安卓侧为准） |
| `参考资料借鉴说明.md` | 对照前一代 Python 系统，说明哪些运营经验被采纳、哪些被刻意放弃 |
| `tools/check-mobile-page.js` | 页面静态自检脚本（JS 语法、元素 id、手机端适配项） |
| `tools/dsh-history-import.mjs` | 换机器时把 DSH 会话历史迁移包合并进本机 `~/.dsh`（幂等，含校验与备份） |
| `tools/dsh-verify-with-dsh-reader.mjs` | 用 DSH 自己的存储后端复读会话日志，证明"导入的历史真能读出来" |
| `tools/dsh-recover-files-from-session.mjs` | **从会话日志恢复文件**（重放 write/edit）；还能用 `--check-missing` 对照磁盘找出被漏掉的文件 |

> 停用的 Spring Boot 版仍然可以 `mvn clean package` 构建运行，但它<b>不再接收功能更新</b>；
> 所有新功能都在安卓侧。若确认不再需要，可以删除根目录的 `pom.xml` 与 `src/`。

---

## 抓取策略与踩过的坑（两版共用，仍然适用）

下面这些结论是两版共用的采集知识，改安卓版逻辑前建议先读一遍；
新版实现位于 `android/core/src/main/java/com/wanhong/crawler/`。

### 1. 安庆市公共资源交易服务网 —— 走站内检索接口

- 列表页是 **Mustache 模板 + AJAX 渲染**，直接抓 HTML 只能拿到空 `<ul>`。
- 改用站点自带的检索接口 `POST /inteligentsearch/rest/esinteligentsearch/getFullTextDataNew`，
  关键参数 `wd`（关键词，**不能为空**）、`fields=title`、`pn`/`rn`、`sort`。
  因此采集是**关键词驱动**的。
- 新闻资讯类索引（`categorynum` 以 `002` 开头）不是招标公告，配置里排除。
- 详情页按 epoint 目录约定拼接 `/jyxx/{分类前6位}/{分类全码}/{yyyyMMdd}/{infoid}.html`。
- **已知坑**：索引里混有大量转载自「安庆市公共资源交易平台」的县级公告
  （`036xxx` / `005xxx` / `004xxx`），这些记录在本站**没有落地页**，任何拼接都 404
  （已逐一试过 12 种路径）。处理方式：用检索接口自带正文作为内容，
  链接改用站点自己的 `/tiaozhuan.html`，界面标「跳转链接」，不给死链。

### 2. 安庆市城市管理局 · 通知公告 —— 解析静态列表页

- 服务端渲染，解析 `ul.doc_list li`（`a[title]` + `span.right.date`）。
- 翻页 `/content/column/14015578?pageIndex=N`，**页码从 1 开始**。
- 参考文档称"分页被 WAF 拦截只能抓第 1 页"，实测**分页可用**。

### 3. 安庆市阳光采购交易服务平台 —— 走站内检索接口

- 走检索接口，索引分类号 `cnum=080`；接口在 `cl` 长度内直接返回正文。
- **两个必须注意的坑**（实测确认）：

  | 现象 | 原因 | 处理 |
  | --- | --- | --- |
  | 返回 `503 安全提示：疑似跨站请求伪造【ESE0102001】` | 请求带了 `Origin` 头 | 不发送 `Origin` |
  | 返回 `400` / CSRF 提示 | 请求带了 **`Referer`** 头 | 该站 `referer` 配置留空 |

### 4. 时间窗口：把过期公告挡在门外

不加窗口时，检索接口会把很早以前的老公告当成"最新结果"返回。实测：

| 站点 / 关键词 | 不加时间窗 | 加 7 天窗口 |
| --- | --- | --- |
| aqggzy / 绿化 | 5893 条（最早到 2026-07） | 2 条 |
| wyxmzx / 绿化 | 106 条（**最早 2024-05**） | 1 条 |

所以用 `time[{fieldName:webdate}]` 做窗口过滤，默认 30 天。效果：同样全量采集一次，
候选从 465 条降到 56 条、耗时从 440 秒降到 106 秒。需要历史时用 `lookbackDays=0` 补一次。

### 5. 证书问题

三个站点的 HTTPS 证书 **JDK 都无法正常校验**（浏览器正常是因为 Windows 会靠 AIA 自动补链）：

| 站点 | JDK 报错 |
| --- | --- |
| `aqggzy.anqing.gov.cn` | `PKIX path building failed`（证书链不完整） |
| `cgj.anqing.gov.cn` | `PKIX path building failed`（证书链不完整） |
| `www.wyxmzx.com` | 证书**已过期**，且 SAN 里没有该域名 |

安卓侧按域名放宽（见 `android/README.md` 第五节），比全局关闭校验安全。

### 6. 关键词过滤：排除词是能用的关键

`养护`、`绿化`、`园林` 这类词太宽泛，不配排除词会把"车辆维修""农村厕所管护"
"公路养护""资产拍卖""店面出租"都捞进来。默认 93 个排除词，分 7 组。
两条规则：**排除词只判定标题**（正文顺带提及不误伤）；
**先判标题排除词再匹配**（否则"标题含排除词、正文命中关键词"的公告会漏进来）。

---

## 旧版 Spring Boot 服务（已停用）

以下内容仅对停用的服务版有效，保留作为参考实现（数据库表结构、REST 接口设计等）。

### 快速开始

```bash
mvn clean package -DskipTests
java -jar target/flower-tender-crawler.jar     # 默认 H2 文件库，零配置
# 浏览器打开 http://localhost:8080
```

命令行一次性采集（采完即退出，适合接入计划任务）：

```bash
java -jar target/flower-tender-crawler.jar --crawl.once=true --crawl.site=all
java -jar target/flower-tender-crawler.jar --crawl.once=true --crawl.lookbackDays=0   # 首次建库
```

参数：`--crawl.site=all|aqggzy|cgj|wyxmzx`、`--crawl.lookbackDays=N`（0=不限）、
`--crawl.maxKeywords=N`。退出码 0 成功、1 失败。

---

## 二、手机端界面（同一套页面，现已搬进 App）

页面是**移动优先**写的，电脑上也能用（宽屏自动变两列 + 右侧抽屉详情）。

**布局**

- 顶部固定：标题 + 新公告角标 + 「全部已读」+ 刷新 + 搜索框 + 筛选入口；
- 中部横向滑动的筛选 chips：`未截止` / `新公告` / `收藏` / `全部` / `按截止排序`；
- 卡片式列表（手机端不用表格）：标题高亮命中词、截止倒计时徽标、站点/区县/分类/日期、
  命中关键词标签、右上角星标收藏；
- 底部四个 Tab：**公告** / **关键词** / **采集** / **日志**；
- 详情：从底部升起的全屏抽屉（宽屏变右侧抽屉），含正文全文、附件下载、原文跳转、收藏。

### 关键词维护（手机端直接改词表）

底部「关键词」页把词表变成可维护的功能，不用改配置文件、也不用重新打包：

- **直接显示**当前三个作用域的全部关键词，每个词后面标着**命中了多少条公告**
  （一眼看出哪些词有用、哪些词长期为 0 该清理），自定义词有高亮边框；
- **添加关键词**，三选一：
  - **标题词** —— 作为站内检索词（搜标题），并判定标题命中；
  - **正文词** —— 以「标题 + 正文」检索，能捞到**标题没写但正文提到**的公告
    （实测 aqggzy 搜「郁金香」：只搜标题 0 条，搜标题+正文 **24 条**）；
  - **排除词** —— 标题命中即丢弃，用来过滤噪声。
- 添加后**立即自动「重扫已入库公告」**：用新词重新匹配库里已有的公告，
  所以**不用重新爬一遍就能看到历史命中**，并给出「查看这几条 →」直接跳到列表；
- 点关键词右侧 ✕ 可**删除自定义词 / 停用内置词**（内置词不会被物理删除，
  只是记一条停用记录，随时能恢复）；
- 「重载内置词表」从文件重新读取并合并用户的增删；「用当前词表采集」直接去站点抓新公告。

**针对手机做的适配**

| 项 | 实现 |
| --- | --- |
| 刘海屏 / 底部手势条 | `viewport-fit=cover` + `env(safe-area-inset-*)` |
| 触摸目标 | Tab 栏 ≥58px、按钮 46px、星标 30px，均满足触控尺寸要求 |
| 滚动加载 | `IntersectionObserver` 触底自动加载，同时保留「加载更多」按钮兜底 |
| 手势 | 详情页右滑关闭；抽屉点遮罩关闭 |
| 输入 | 搜索框 400ms 防抖，回车即搜 |
| 深色模式 | `prefers-color-scheme: dark` 全套配色 |
| 免缩放 | `maximum-scale=1`，避免 iOS 输入框聚焦时页面放大 |

**手机上最关心的信息 —— 截止日期**，做成了三级配色徽标：

- `剩 N 天`：≤3 天红色、≤7 天橙色、其余绿色；
- `今天截止` / `已截止`（灰）；
- 正文里解析不到时按「发布日 + 60 天」估算，并打「估算」标记，详情页提示"以原文为准"。

默认视图是 **未截止 + 按截止时间升序**（最紧急的排最前），
因为这是"监控工具"和"数据仓库"的区别所在。

---

## 三、项目结构（旧版服务）

```
src/main/java/com/wanhong/crawler/
├── CrawlerApplication.java              启动类（含统一时区设置）
├── CrawlOnceRunner.java                 命令行一次性采集
├── config/
│   ├── CrawlerProperties.java           crawler.* 配置映射
│   └── HostnameVerificationInitializer.java  证书开关（容器启动前生效）
├── http/
│   ├── HttpFetcher.java                 UA 轮换 / 限速 / 重试 / 解压 / cookie 预热 / 证书降级
│   └── FetchResponse.java, CrawlException.java
├── crawler/
│   ├── SiteCrawler.java                 站点采集器接口（只负责"发现公告"）
│   ├── AqggzyCrawler.java / CgjCrawler.java / WyxmzxCrawler.java
│   ├── DetailParser.java                详情页解析（三站通用）
│   ├── DeadlineExtractor.java           报名/投标截止日期解析
│   ├── epoint/                          epoint 站内检索接口客户端
│   └── model/                           CrawlItem / CrawlContext / CrawlResult / DetailResult
├── service/
│   ├── CrawlService.java                编排：发现 → 过滤 → 详情补全 → 去重入库 → 记日志
│   ├── KeywordMatcher.java              关键词/排除词匹配与热加载
│   ├── VerificationService.java         已下架公告校验
│   ├── TenderQueryService.java          查询 / 统计 / 收藏 / 已读
│   └── ExportService.java               CSV 导出
├── repository/                          JPA 仓库与查询条件
├── entity/                              TenderInfo / CrawlLog
├── web/                                 REST 接口与统一异常
└── scheduler/CrawlScheduler.java        定时采集

src/main/resources/
├── application.yml                      主配置（含各站点参数与坑位说明）
├── application-mysql.yml                MySQL 环境配置
├── keywords/flower-keywords.txt         标题关键词（兼作检索词）
├── keywords/flower-core-keywords.txt    正文关键词（高精度）
├── keywords/exclude-keywords.txt        排除词（81 个）
└── static/index.html                    手机端自适应页面

tools/check-mobile-page.js               手机端页面静态自检脚本（见第八节）
```

数据流：**采集器只负责发现公告**（标题、链接、日期、分类、区县），
关键词过滤、截止日期解析、详情补全、去重、落库由 `CrawlService` 统一处理，
新增站点只需实现 `SiteCrawler` + 加一段配置。

---

## 四、关键词与过滤（旧版服务）

三套词表 + 手机端可维护的自定义词：

| 来源 | 作用 | 数量 |
| --- | --- | --- |
| `keywords/flower-keywords.txt` | 站内检索词 + **标题命中**判定 | 49 |
| `keywords/flower-core-keywords.txt` | **正文命中**判定（精度高） | 44 |
| `keywords/exclude-keywords.txt` | **排除词**，命中即丢弃 | 93 |
| 数据库 `crawler_keyword` 表 | 用户在手机上增删的词（ADD / DISABLE 覆盖） | 运行时 |

内置词表放文件里便于版本管理与追溯；用户改动存表，两者合并成"实际生效"的词表。
**检索词的作用域**决定搜哪个字段：标题词搜 `title`，正文词搜 `title;content`
（已经在标题词表里的词不会重复搜一次正文，避免请求量翻倍）。

**排除词是这套系统能用的关键**（前一代系统最重要的运营经验）：
`养护`、`绿化`、`园林` 这类词太宽泛，不配排除词会把"车辆维修""农村厕所管护"
"公路养护"甚至"资产拍卖""店面出租"都捞进来。默认排除词分 7 组：
车辆机械 / 环卫物业 / 弱电设备医疗 / 房建市政 / 一般货物服务 / 能源 / 资产处置。

两条规则：

1. **排除词只判定标题** —— 正文里顺带提到"保洁""车辆"不误伤正常的花卉采购公告；
2. 文件末尾有一段**注释掉的"业务判断"排除词**（山体绿化、道路绿化、高标准农田、
   国家储备林…）：这是前一代系统"把大型市政绿化工程也排除掉"的做法，
   对花木公司可能合理，但属于业务口径，默认不启用。

**用外部词表文件（免重新打包）**：

```bash
java -jar target/flower-tender-crawler.jar \
  --crawler.filter.title-keywords-file=file:./config/flower-keywords.txt \
  --crawler.filter.exclude-keywords-file=file:./config/exclude-keywords.txt
```

**调优建议**：

- 想更聚焦纯花卉：停用 `绿化 / 绿化养护 / 园林 / 园林绿化 / 景观 / 行道树`（手机上点 ✕ 即可）；
- 想扩大召回：加 `花境、绿篱、花坛花、绿雕、宿根花卉` 等；
- 树种名（香樟/桂花/海棠/玉兰/紫薇/樱花）**只保留在正文词表里**、不进标题词表：
  它们大量出现在路名与小区名里（"香樟里小区消防维修""桂花路改造"），
  放在标题匹配会误报；但出现在正文里就是高精度信号，所以正文检索（`title;content`）仍然用得上；
- 刻意**不收录** `造林/植树`：会把林木、毛竹资产拍卖捞进来。

---

## 五、数据模型要点（旧版服务，字段与 App 的 SQLite 一致）

`tender_info` 表关键字段：

| 字段 | 说明 |
| --- | --- |
| `url_hash` | URL 的 SHA-256 摘要，**唯一索引**（MySQL utf8mb4 下 varchar(1000) 无法建唯一索引，所以索引建在摘要上） |
| `region` | 区县，来自 `citycode` 映射或标题推断 |
| `deadline_date` | **报名/投标截止日期**（手机端最关键的字段） |
| `deadline_estimated` / `deadline_source` | 是否估算 / 来源（正文解析 / 发布日期+60天估算） |
| `is_new` | 新公告标记，入库置位，「全部已读」后清零 |
| `tracked` | 收藏/跟踪，收藏的公告即使下架也保留 |
| `removed` / `verified_at` / `link_status` | 下架标记与链接校验（`OK` / `FALLBACK` / `REMOVED` / `UNKNOWN`） |
| `matched_keywords` / `matched_scope` | 命中词与命中位置（TITLE / CONTENT / TITLE,CONTENT） |
| `access_url` | 原地址无落地页时的站点跳转地址 |

---

## 六、REST 接口（App 内置本地服务实现了同一套接口）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/tenders` | 分页查询。参数：`site` `keyword` `region` `from` `to` `onlyOpen` `onlyNew` `tracked` `includeRemoved` `sort=publish\|deadline` `page` `size` |
| GET | `/api/tenders/{id}` | 公告详情（含正文、附件、截止日期来源） |
| POST | `/api/tenders/{id}/track?tracked=true` | 收藏 / 取消收藏 |
| POST | `/api/tenders/mark-read` | 全部标记已读，返回剩余新公告数 |
| GET | `/api/tenders/stats` | 统计：总数 / 未截止 / 新 / 收藏 / 各站点 / 各区县 / 已知区县列表 |
| GET | `/api/tenders/export.csv` | 导出 CSV（含截止日期与剩余天数；UTF-8 BOM，已防 CSV 注入） |
| POST | `/api/crawl/run?site=all&lookbackDays=30&maxKeywords=` | **异步**触发采集，立即返回 |
| POST | `/api/crawl/run-sync?site=all` | **同步**触发采集 |
| POST | `/api/crawl/verify?limit=50` | 手动触发已下架校验 |
| GET | `/api/crawl/status` | 采集状态 / 上次结果 |
| GET | `/api/crawl/logs?limit=20` | 采集日志列表 |
| GET | `/api/crawl/logs/{id}` | 单条日志完整明细（含逐条失败原因） |
| GET | `/api/crawl/sites` | 站点概览 |
| GET | `/api/keywords` | **词表总览**：三个作用域的词 + 是否自定义 + 各自命中多少条公告 |
| POST | `/api/keywords?keyword=&scope=TITLE\|CONTENT\|EXCLUDE` | **新增关键词** |
| DELETE | `/api/keywords?keyword=&scope=` | 删除自定义词 / 停用内置词 |
| POST | `/api/keywords/rescan` | **用当前词表重扫已入库公告**（新增词后无需重新爬取即可看到历史命中） |
| POST | `/api/keywords/reload` | 重新从文件加载内置词表（并合并用户增删） |
| GET | `/api/keywords/search-terms` | 当前真正会用于站内检索的词及其字段（排查"这词为什么没采到"） |
| GET | `/api/keywords/scopes` | 作用域可选值与说明 |

示例：

```bash
# 手机端默认视图：未截止 + 最紧急优先
curl "http://localhost:8080/api/tenders?onlyOpen=true&sort=deadline&size=5"

# 桐城市的收藏
curl "http://localhost:8080/api/tenders?region=%E6%A1%90%E5%9F%8E%E5%B8%82&tracked=true"
```

---

## 七、配置说明（旧版服务 application.yml）

```yaml
crawler:
  enabled: true
  timezone: Asia/Shanghai        # 截止日期与过期判断统一时区
  request:
    min-interval-millis: 700     # 同域名最小请求间隔（礼貌抓取）
    retry-times: 3               # 网络异常 / 5xx / 429 / 403 重试
    max-detail-per-run: 300      # 单次最多抓多少详情页（控制耗时）
    warm-up: true                # 首次访问域名前 GET 首页拿 WAF cookie；403 自动重取
    cert-fallback-to-insecure: true   # 证书链校验失败自动降级（有 WARN）
  filter:
    title-keywords-file: keywords/flower-keywords.txt
    content-keywords-file: keywords/flower-core-keywords.txt
    exclude-keywords-file: keywords/exclude-keywords.txt
    match-title: true
    match-content: true
  deadline:
    parse-from-content: true
    default-days: 60             # 解析不到时按 发布日 + N 天 估算
    max-days: 400                # 超出此范围视为误判
  verify:
    enabled: true                # 采集后自动校验已下架
    limit: 150
    skip-hours: 12
  schedule:
    enabled: true
    cron: "0 30 2 * * ?"
    zone: Asia/Shanghai
  sites:
    aqggzy: { lookback-days: 30, max-pages-per-keyword: 2, content-length: 2000 }
    cgj:    { max-pages: 2 }
    wyxmzx: { lookback-days: 30, cnum: "080", referer: "" }   # referer 必须为空
```

### 切换到 MySQL

```bash
mysql -uroot -p -e "CREATE DATABASE flower_crawler DEFAULT CHARSET utf8mb4;"
java -jar target/flower-tender-crawler.jar --spring.profiles.active=mysql
```

建表由 Hibernate `ddl-auto: update` 自动完成。生产环境记得关掉 H2 控制台
（`spring.h2.console.enabled=false`）。

### 手机端页面自检

`tools/check-mobile-page.js` 是一个不依赖浏览器的静态自检脚本：
检查内联 JS 语法、JS 引用的元素 id 是否都存在、手机端适配项是否齐全。

```bash
node tools/check-mobile-page.js src/main/resources/static/index.html src/main/java
```

---

## 八、合规与注意事项

- 内置**同域名最小请求间隔（默认 700ms）+ 随机 UA + 失败退避 + cookie 预热**，属于礼貌抓取，
  请勿把 `min-interval-millis` 调得过低；
- 只采集公开的招标公告信息，遵守目标站点 `robots.txt` 与相关法律法规，
  不要用于批量转载或商业转售；
- 建议每天 1~2 次增量即可（默认每天 02:30 一次）；
- 目标站点改版会导致选择器失效，需要更新对应 `SiteCrawler` 或 `DetailParser` 的选择器。

---

## 九、已验证的真实采集结果（旧版服务）

本机实测（全量采集一次，`lookbackDays=0` 建立历史基线）：

| 站点 | 结果 |
| --- | --- |
| 安庆市公共资源交易服务网 | 245 条（含正文与附件） |
| 安庆市城市管理局 | 7 条 |
| 安庆市阳光采购交易服务平台 | 116 条 |
| 合计 | **368 条，0 失败**，另有 **82 条被排除词正确过滤** |

其中截止日期：正文解析约 1/4，其余为发布日期估算，无缺失。
单元测试 56 个全部通过，手机端页面静态自检全部通过。

样例命中标题：

- 安庆市城市绿化管理大队草花采购项目成交结果公告
- 安庆职业技术学院校园季节性速生花卉和宿舍周边绿篱景观项目成交公告
- 安庆市机场路护栏花箱提升项目采购及安装服务合同公告
- 皖西大别山（岳西县）水土保持与生态修复项目苗木采购竞争性谈判公告
- 安庆市殡葬管理所绿化养护服务采购项目竞争性磋商公告

被排除词正确挡掉的噪声（真实数据）：

- 宿松县国有林场**园林路**店面…场地**出租**（被路名"园林路"误命中）
- 桐城市园林绿化管理处的部分设备及喷泉水泵**拆除物**等**资产拍卖**项目
- 2026年潜山市营造林项目区林木、毛竹**资产拍卖**交易公告

---

## 十、已知限制

1. 交易服务网索引里的转载类记录（`036xxx` / `005xxx` / `004xxx`）在本站没有落地页，
   只能给跳转链接 —— 上游数据本身如此；这类记录分类编码自成一套，
   界面统一显示为「其他交易」，原始编码保留在 `categoryCode`；
2. `www.wyxmzx.com` 的 HTTP 与 HTTPS 是两套不同虚拟主机（HTTP 下列表接口返回空），
   只能走 HTTPS + 跳过证书校验；
3. 站内检索接口的 `wd` 不支持空值，所以交易网与阳光采购平台是「关键词驱动」采集，
   关键词表之外的公告不会被采到 —— 需要更全量时扩充词表；
4. 截止日期目前约 1/4 能从正文解析出来，其余为估算值（`deadline_estimated=true`），
   界面上已明确标注，实际投标前请以原文为准；
5. 详情页正文依赖选择器，站点改版后需要同步调整；
6. **未实现消息推送**：目前仍需用户主动打开页面查看。若要"手机上收到提醒"，
   接一个 Pushplus / 企业微信机器人即可（数据侧 `is_new` + `deadline_date` 已就绪），
   详见 [参考资料借鉴说明.md](参考资料借鉴说明.md) 第五节。


