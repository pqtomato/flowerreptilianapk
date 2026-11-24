package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.common.Hashing;
import com.wanhong.crawler.common.NoticeType;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.BudgetExtractor;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.CrawlResult;
import com.wanhong.crawler.crawler.model.DetailResult;
import com.wanhong.crawler.crawler.model.NewTender;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.filter.KeywordRules;
import com.wanhong.crawler.http.CrawlException;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集编排：发现 → 关键词过滤 → 详情补全 → 去重入库 → 记日志 → 已下架校验。
 *
 * <p>与旧版一致的核心规则：
 * <ul>
 *   <li>URL 的 SHA-256 作为去重键；</li>
 *   <li><b>先判标题排除词</b>再匹配（否则"标题含排除词、正文命中关键词"的公告会漏进来）；</li>
 *   <li>详情页打不开时用检索接口自带正文，并把链接换成站点兜底地址，不给死链；</li>
 *   <li>入库即记录"已验证"（详情页刚探过），让下架校验的负载与新增量而不是库存量成正比。</li>
 * </ul>
 */
public class CrawlEngine {

    public interface Logger {
        void info(String message);

        void warn(String message, Throwable error);

        Logger SILENT = new Logger() {
            public void info(String message) {
            }

            public void warn(String message, Throwable error) {
            }
        };
    }

    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_KEYWORDS_LENGTH = 500;
    /** 无状态，直接共享一个实例；不必塞进构造函数参数列表里 */
    private static final BudgetExtractor BUDGET_EXTRACTOR = new BudgetExtractor();

    private final CrawlerSettings settings;
    private final List<SiteCrawler> crawlers;
    private final KeywordRules keywordRules;
    private final TenderRepository repository;
    private final HttpFetcher httpFetcher;
    private final DetailParser detailParser;
    private final DeadlineExtractor deadlineExtractor;
    private final LinkVerifier linkVerifier;
    private final ObjectMapper objectMapper;
    private final Logger logger;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String runningSite;
    private volatile LocalDateTime runningSince;
    private volatile CrawlSummary lastSummary;

    public CrawlEngine(CrawlerSettings settings,
                       List<SiteCrawler> crawlers,
                       KeywordRules keywordRules,
                       TenderRepository repository,
                       HttpFetcher httpFetcher,
                       DetailParser detailParser,
                       DeadlineExtractor deadlineExtractor,
                       LinkVerifier linkVerifier,
                       ObjectMapper objectMapper,
                       Logger logger) {
        this.settings = settings;
        this.crawlers = crawlers;
        this.keywordRules = keywordRules;
        this.repository = repository;
        this.httpFetcher = httpFetcher;
        this.detailParser = detailParser;
        this.deadlineExtractor = deadlineExtractor;
        this.linkVerifier = linkVerifier;
        this.objectMapper = objectMapper;
        this.logger = logger == null ? Logger.SILENT : logger;
    }

    public boolean isRunning() {
        return running.get();
    }

    public String getRunningSite() {
        return runningSite;
    }

    /** 本次采集的开始时间（页面显示“开始于 …”） */
    public LocalDateTime getRunningSince() {
        return runningSince;
    }

    public CrawlSummary getLastSummary() {
        return lastSummary;
    }

    public CrawlSummary crawl(String siteCode, Integer maxKeywords, Integer lookbackDays,
                              String triggerType, ProgressListener listener) {
        if (!settings.enabled) {
            return CrawlSummary.of("DISABLED", "采集总开关已关闭");
        }
        if (!running.compareAndSet(false, true)) {
            return CrawlSummary.of("BUSY", "已有采集任务正在执行：" + runningSite);
        }
        this.runningSite = (siteCode == null || siteCode.trim().isEmpty()) ? "all" : siteCode;
        this.runningSince = LocalDateTime.now();
        try {
            CrawlSummary summary = doCrawl(siteCode, maxKeywords, lookbackDays, triggerType, listener);
            this.lastSummary = summary;
            return summary;
        } finally {
            running.set(false);
            runningSite = null;
            runningSince = null;
        }
    }

    private CrawlSummary doCrawl(String siteCode, Integer maxKeywords, Integer lookbackDays,
                                 String triggerType, ProgressListener listener) {
        long startedAt = System.currentTimeMillis();

        List<SiteCrawler> targets = resolveCrawlers(siteCode);
        List<SearchTerm> terms = keywordRules.getSearchTerms();
        if (maxKeywords != null && maxKeywords > 0 && terms.size() > maxKeywords) {
            terms = evenlySample(terms, maxKeywords);
        }
        if (terms.isEmpty()) {
            return CrawlSummary.of("FAILED", "关键词为空，请在「关键词」页添加");
        }

        httpFetcher.resetRequestCount();
        AtomicInteger detailBudget = new AtomicInteger(Math.max(0, settings.request.maxDetailPerRun));

        CrawlSummary summary = new CrawlSummary();
        summary.triggerType = triggerType;
        int okSites = 0;
        for (SiteCrawler crawler : targets) {
            CrawlResult result = crawlOne(crawler, terms, lookbackDays, detailBudget, triggerType, listener);
            if (result.getErrorMessage() == null) {
                okSites++;
            }
            summary.addSite(result);
        }

        // 采集完成后顺带抽查已入库公告是否已下架
        if (settings.verify.enabled && okSites > 0) {
            try {
                summary.verifiedCount = linkVerifier.verify(null, listener);
            } catch (Exception e) {
                logger.warn("已下架校验失败（不影响采集结果）", e);
            }
        }

        summary.durationMs = System.currentTimeMillis() - startedAt;
        summary.finishedAt = LocalDateTime.now();
        if (okSites == 0) {
            summary.status = "FAILED";
            summary.message = "全部站点采集失败";
        } else if (okSites < targets.size()) {
            summary.status = "PARTIAL";
            summary.message = okSites + "/" + targets.size() + " 个站点采集成功，新增 " + summary.savedCount + " 条";
        } else {
            summary.status = "SUCCESS";
            summary.message = "采集完成，新增 " + summary.savedCount + " 条";
        }
        if (summary.verifiedCount > 0) {
            summary.message = summary.message + "；已下架校验 " + summary.verifiedCount + " 条";
        }
        logger.info("采集结束：" + summary.status + "，候选 " + summary.candidateCount
                + "，排除 " + summary.excludedCount + "，命中 " + summary.matchedCount
                + "，新增 " + summary.savedCount + "，耗时 " + summary.durationMs + "ms");
        return summary;
    }

    /**
     * 在整张词表上<b>均匀取样</b> count 个词（首尾都要覆盖）。
     *
     * <p>踩过的坑：原来是直接取前 count 个（{@code subList(0, n)}），
     * 而 {@code flower-keywords.txt} 是「窄 → 宽」排列的：
     * 前面是 花卉 / 花卉苗木 / 花卉种植 / 花卉采购 / 花卉租摆，后面才是 绿化 / 园林 / 景观。
     * 于是界面上那句"手机上想快点可填 10"实际会<b>把宽泛词全跳过</b> ——
     * 实测「90 天窗口 + 前 5 个窄词」候选 0，用户会以为应用坏了。
     *
     * <p>均匀取样缩小的是"覆盖密度"而不是"召回面"，让"少检索几个词"仍然能捞到东西。
     */
    static <T> List<T> evenlySample(List<T> source, int count) {
        List<T> picked = new ArrayList<>();
        if (source == null || source.isEmpty() || count <= 0) {
            return picked;
        }
        if (source.size() <= count) {
            picked.addAll(source);
            return picked;
        }
        if (count == 1) {
            picked.add(source.get(0));
            return picked;
        }
        // 把 [0, size-1] 均分成 count 个点，首尾都取到
        for (int i = 0; i < count; i++) {
            int index = (int) ((long) i * (source.size() - 1) / (count - 1));
            picked.add(source.get(index));
        }
        return picked;
    }

    private List<SiteCrawler> resolveCrawlers(String siteCode) {
        if (siteCode == null || siteCode.trim().isEmpty() || "all".equalsIgnoreCase(siteCode.trim())) {
            List<SiteCrawler> enabled = new ArrayList<>();
            for (SiteCrawler crawler : crawlers) {
                if (crawler.enabled()) {
                    enabled.add(crawler);
                }
            }
            if (enabled.isEmpty()) {
                throw new IllegalStateException("没有任何启用的站点");
            }
            return enabled;
        }
        String code = siteCode.trim().toLowerCase();
        for (SiteCrawler crawler : crawlers) {
            if (crawler.siteCode().equals(code)) {
                if (!crawler.enabled()) {
                    throw new IllegalStateException("站点已禁用：" + code);
                }
                return java.util.Collections.singletonList(crawler);
            }
        }
        throw new IllegalArgumentException("未知站点：" + siteCode);
    }

    private CrawlResult crawlOne(SiteCrawler crawler, List<SearchTerm> terms, Integer lookbackDays,
                                 AtomicInteger detailBudget, String triggerType, ProgressListener listener) {
        CrawlResult result = new CrawlResult(crawler.siteCode(), crawler.siteName());
        long startedAt = System.currentTimeMillis();
        long requestsBefore = httpFetcher.getRequestCount();

        CrawlLogRecord log = new CrawlLogRecord();
        log.siteCode = crawler.siteCode();
        log.triggerType = triggerType;
        log.status = "RUNNING";
        log.startTime = LocalDateTime.now();
        repository.saveLog(log);

        int effectiveLookback = lookbackDays != null ? lookbackDays : siteLookbackDays(crawler.siteCode());
        int contentTerms = 0;
        for (SearchTerm term : terms) {
            if (term.isContentScope()) {
                contentTerms++;
            }
        }
        logger.info("[" + crawler.siteName() + "] 开始采集，检索词 " + terms.size()
                + " 个（其中正文检索 " + contentTerms + " 个），时间窗口 "
                + (effectiveLookback > 0 ? "最近 " + effectiveLookback + " 天" : "不限"));
        if (listener != null) {
            listener.onProgress("正在采集 " + crawler.siteName());
        }

        try {
            CrawlContext context = new CrawlContext(terms, lookbackDays, result::addMessage);
            List<CrawlItem> items = crawler.crawl(context);
            result.addCandidate(items.size());

            for (CrawlItem item : items) {
                try {
                    processItem(item, result, detailBudget);
                } catch (Exception e) {
                    result.incrementFailed();
                    result.addMessage("处理失败：" + item.getTitle() + " -> " + e.getMessage());
                    logger.warn("处理公告失败：" + item.getTitle(), e);
                }
            }
            log.status = "SUCCESS";
        } catch (Exception e) {
            result.setErrorMessage(e.getMessage());
            log.status = "FAILED";
            log.errorMessage = truncate(e.getMessage(), 2000);
            logger.warn("[" + crawler.siteName() + "] 采集失败", e);
        } finally {
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            result.setRequestCount(httpFetcher.getRequestCount() - requestsBefore);
            log.endTime = LocalDateTime.now();
            log.durationMs = result.getDurationMs();
            log.candidateCount = result.getCandidateCount();
            log.excludedCount = result.getExcludedCount();
            log.matchedCount = result.getMatchedCount();
            log.savedCount = result.getSavedCount();
            log.duplicateCount = result.getDuplicateCount();
            log.failedCount = result.getFailedCount();
            log.requestCount = (int) result.getRequestCount();
            log.detail = truncate(join(result.getMessages()), 20000);
            repository.updateLog(log);
            logger.info("[" + crawler.siteName() + "] 采集完成：候选 " + result.getCandidateCount()
                    + "，排除 " + result.getExcludedCount() + "，命中 " + result.getMatchedCount()
                    + "，新增 " + result.getSavedCount() + "，重复 " + result.getDuplicateCount()
                    + "，耗时 " + result.getDurationMs() + "ms");
        }
        return result;
    }

    private void processItem(CrawlItem item, CrawlResult result, AtomicInteger detailBudget) {
        String title = item.getTitle();
        String rawUrl = item.getUrl();
        if (title == null || title.trim().isEmpty() || rawUrl == null || rawUrl.trim().isEmpty()) {
            result.incrementFailed();
            return;
        }

        String urlHash = Hashing.urlHash(rawUrl);
        TenderRecord existing = repository.findByUrlHash(urlHash);
        if (existing != null) {
            handleDuplicate(existing, item, result, detailBudget);
            return;
        }

        // 标题命中排除词直接丢弃。必须先判：下面"正文命中"那条路径标题参数是 null，
        // 否则「XX资产拍卖交易公告」会被正文里的"苗木"放进来
        if (keywordRules.isExcluded(title)) {
            result.incrementExcluded();
            return;
        }

        KeywordRules.MatchResult titleMatch = keywordRules.match(title, null);
        KeywordRules.MatchResult summaryMatch = keywordRules.match(null, item.getSummary());
        if (!titleMatch.isMatched() && !summaryMatch.isMatched()) {
            return;
        }
        result.incrementMatched();

        LocalDateTime now = LocalDateTime.now();
        TenderRecord record = new TenderRecord();
        record.siteCode = item.getSiteCode();
        record.siteName = item.getSiteName();
        record.title = truncate(title, 1000);
        record.url = truncate(rawUrl, 1000);
        record.urlHash = urlHash;
        record.category = truncate(item.getCategory(), 200);
        record.categoryCode = truncate(item.getCategoryCode(), 64);
        record.region = truncate(item.getRegion(), 64);
        record.publishDate = item.getPublishDate();
        record.sourceKeyword = truncate(item.getSourceKeyword(), 128);
        record.summary = truncate(item.getSummary(), MAX_SUMMARY_LENGTH);
        record.crawlTime = now;
        record.createTime = now;
        record.noticeType = NoticeType.classify(record.category, record.title);
        record.isNew = true;
        record.tracked = false;
        record.removed = false;

        boolean detailFetched = false;
        if (settings.detail.enabled && detailBudget.get() > 0 && isFetchable(rawUrl)) {
            detailBudget.decrementAndGet();
            DetailResult detail = fetchDetail(rawUrl);
            if (detail != null) {
                applyDetail(record, detail);
                detailFetched = !detail.isEmpty();
            }
        }
        if (detailFetched) {
            record.linkStatus = "OK";
        } else {
            useSummaryAsContent(record);
            String fallback = fallbackUrlFor(item);
            if (fallback != null && !fallback.trim().isEmpty()) {
                record.accessUrl = truncate(fallback, 1000);
                record.linkStatus = "FALLBACK";
            } else {
                record.linkStatus = "UNKNOWN";
            }
        }
        // 入库时已探过一次链接，直接记为"已验证"：让下架校验的负载与新增量成正比
        record.verifiedAt = now;

        applyDeadline(record);
        record.region = resolveRegion(record);
        applyBudget(record);

        Set<String> hits = new LinkedHashSet<>(titleMatch.keywords());
        hits.addAll(summaryMatch.keywords());
        KeywordRules.MatchResult finalTitle = keywordRules.match(record.title, null);
        String contentForMatch = record.hasContent() ? record.content : record.summary;
        KeywordRules.MatchResult finalContent = keywordRules.match(null, contentForMatch);
        hits.addAll(finalTitle.keywords());
        hits.addAll(finalContent.keywords());

        record.matchedKeywords = truncate(join(hits), MAX_KEYWORDS_LENGTH);
        record.matchedScope = resolveScope(finalTitle.isMatched(), finalContent.isMatched());

        repository.insert(record);
        result.incrementSaved();
        // 只把"可投的"收进通知列表：新入库的成交/合同类公告不是机会，
        // 通知里列出来反而是噪声（与列表默认过滤保持同一个口径）。
        if (record.biddable()) {
            result.addNewItem(new NewTender(record.title, record.deadlineDate));
        }
    }

    /** 已存在的公告：补跨站来源、补正文、补截止日期与区县。 */
    private void handleDuplicate(TenderRecord existing, CrawlItem item,
                                 CrawlResult result, AtomicInteger detailBudget) {
        // 重复项也算"命中"：它在上面那条 findByUrlHash 分支就被拦下了，
        // 根本没走到 incrementMatched()。不补这一句，"命中"就会把重复项漏掉，
        // 日志里会出现「候选 40 · 命中 0 · 重复 7」这种自相矛盾的数字
        // （重复 7 条必然是先命中过才会进库）。补上后恒有 命中 = 新增 + 重复。
        result.incrementMatched();

        boolean changed = false;

        // 自愈：老数据可能带着过期 / 缺失的类型标记，或者挂着"结果类公告估出来的假截止日"。
        // 放在这里可以让存量数据在每次采集时被逐步修正，不必每次都靠 DB 版本升级。
        String expectedType = NoticeType.classify(existing.category, existing.title);
        if (!expectedType.equals(existing.noticeType)) {
            existing.noticeType = expectedType;
            changed = true;
        }
        // 估算功能已停用（v7 迁移会清空存量），这里兜底清掉老库里残留的估算值；
        // 清空后下面的 deadlineDate == null 分支会按正文重新解析一次。
        if (existing.deadlineEstimated) {
            existing.deadlineDate = null;
            existing.deadlineEstimated = false;
            existing.deadlineSource = "";
            changed = true;
        }

        if (!item.getSiteCode().equals(existing.siteCode)) {
            Set<String> alsoFoundOn = new LinkedHashSet<>();
            if (existing.alsoFoundOn != null && !existing.alsoFoundOn.trim().isEmpty()) {
                alsoFoundOn.addAll(Arrays.asList(existing.alsoFoundOn.split(",")));
            }
            if (alsoFoundOn.add(item.getSiteCode())) {
                existing.alsoFoundOn = truncate(join(alsoFoundOn), 255);
                changed = true;
            }
        }

        if (!existing.hasContent() && settings.detail.enabled
                && detailBudget.get() > 0 && isFetchable(existing.url)) {
            detailBudget.decrementAndGet();
            DetailResult detail = fetchDetail(existing.url);
            if (detail != null && !detail.isEmpty()) {
                applyDetail(existing, detail);
                changed = true;
            }
        }
        if (!existing.hasContent()) {
            useSummaryAsContent(existing);
            if (existing.accessUrl == null || existing.accessUrl.trim().isEmpty()) {
                String fallback = fallbackUrlFor(item);
                if (fallback != null && !fallback.trim().isEmpty()) {
                    existing.accessUrl = truncate(fallback, 1000);
                    existing.linkStatus = "FALLBACK";
                    changed = true;
                }
            }
        }
        if (existing.deadlineDate == null) {
            applyDeadline(existing);
            changed = true;
        }
        // 正文补齐后顺带补金额 / 项目编号（老数据，或首次采集时没抓到正文的）
        if (applyBudget(existing)) {
            changed = true;
        }
        String region = resolveRegion(existing);
        if (region != null && !region.trim().isEmpty() && !region.equals(existing.region)) {
            existing.region = region;
            changed = true;
        }
        if (changed) {
            repository.update(existing);
        }
        result.incrementDuplicate();
    }

    private void useSummaryAsContent(TenderRecord record) {
        if (record.hasContent()) {
            return;
        }
        String summary = record.summary;
        if (summary != null && summary.length() >= 60) {
            record.content = summary;
        }
    }

    private void applyDeadline(TenderRecord record) {
        String text = record.hasContent() ? record.content : record.summary;
        // 只认正文里写明的日期；解析不到就是「未知」，不再按发布日期估算 ——
        // 估出来的日子与真实报名截止无关，会让已截止的公告显示成「还剩 N 天」。
        DeadlineExtractor.Deadline deadline = deadlineExtractor.resolve(text, record.publishDate);
        record.deadlineDate = deadline.date();
        // 字段保留是为了兼容旧库结构，估算功能停用后恒为 false
        record.deadlineEstimated = false;
        record.deadlineSource = truncate(deadline.source(), 64);
    }

    /**
     * 从正文里抠「预算金额 / 项目编号 / 最高限价」。
     *
     * <p>已经有金额和编号就不再重跑正则 —— 重复采集时会走到这里，
     * 每次都解析一遍纯属浪费（正文可能上万字）。
     *
     * @return 是否填入了新值（重复采集路径据此决定要不要写库）
     */
    private boolean applyBudget(TenderRecord record) {
        if (record.hasBudget() && record.projectCode != null) {
            return false;
        }
        String text = record.hasContent() ? record.content : record.summary;
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        BudgetExtractor.Budget budget = BUDGET_EXTRACTOR.extract(text);
        boolean changed = false;
        if (budget.amountYuan() != null && !record.hasBudget()) {
            record.budgetYuan = budget.amountYuan();
            changed = true;
        }
        if (budget.capText() != null && !budget.capText().equals(record.budgetCapText)) {
            record.budgetCapText = truncate(budget.capText(), 200);
            changed = true;
        }
        if (budget.projectCode() != null && record.projectCode == null) {
            record.projectCode = truncate(budget.projectCode(), 64);
            changed = true;
        }
        return changed;
    }

    private String resolveRegion(TenderRecord record) {
        if (record.region != null && !record.region.trim().isEmpty()) {
            return record.region;
        }
        return truncate(RegionNames.guessFromTitle(record.title), 64);
    }

    private void applyDetail(TenderRecord record, DetailResult detail) {
        if (detail.contentText() != null && !detail.contentText().trim().isEmpty()) {
            record.content = detail.contentText();
            record.contentHtml = detail.contentHtml();
        }
        if (detail.publishDate() != null && record.publishDate == null) {
            record.publishDate = detail.publishDate();
        }
        if (!detail.attachments().isEmpty()) {
            try {
                record.attachments = objectMapper.writeValueAsString(detail.attachments());
            } catch (Exception e) {
                logger.warn("附件列表序列化失败", e);
            }
        }
    }

    private DetailResult fetchDetail(String url) {
        try {
            FetchResponse response = httpFetcher.get(url, refererOf(url));
            if (!response.isSuccess()) {
                return null;
            }
            return detailParser.parse(response.body(), response.finalUrl());
        } catch (Exception e) {
            return null;
        }
    }

    private String fallbackUrlFor(CrawlItem item) {
        for (SiteCrawler crawler : crawlers) {
            if (crawler.siteCode().equals(item.getSiteCode())) {
                return crawler.fallbackDetailUrl(item);
            }
        }
        return null;
    }

    private boolean isFetchable(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private String refererOf(String url) {
        try {
            URI uri = URI.create(HttpFetcher.encodeUrl(url));
            return uri.getScheme() + "://" + uri.getHost() + "/";
        } catch (Exception e) {
            return null;
        }
    }

    private int siteLookbackDays(String siteCode) {
        if (AqggzyCrawlerCode.equals(siteCode)) {
            return settings.sites.aqggzy.lookbackDays;
        }
        if (WyxmzxCrawlerCode.equals(siteCode)) {
            return settings.sites.wyxmzx.lookbackDays;
        }
        if (Zh0556CrawlerCode.equals(siteCode)) {
            return settings.sites.zh0556.lookbackDays;
        }
        return 0;
    }

    private static final String AqggzyCrawlerCode = "aqggzy";
    private static final String WyxmzxCrawlerCode = "wyxmzx";
    private static final String Zh0556CrawlerCode = "zh0556";

    private String resolveScope(boolean titleHit, boolean contentHit) {
        if (titleHit && contentHit) {
            return "TITLE,CONTENT";
        }
        if (titleHit) {
            return "TITLE";
        }
        return contentHit ? "CONTENT" : "TITLE";
    }

    private static String join(Iterable<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

