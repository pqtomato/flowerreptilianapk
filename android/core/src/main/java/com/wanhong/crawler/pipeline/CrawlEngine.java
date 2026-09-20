package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.common.Hashing;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.CrawlResult;
import com.wanhong.crawler.crawler.model.DetailResult;
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
            terms = terms.subList(0, maxKeywords);
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
    }

    /** 已存在的公告：补跨站来源、补正文、补截止日期与区县。 */
    private void handleDuplicate(TenderRecord existing, CrawlItem item,
                                 CrawlResult result, AtomicInteger detailBudget) {
        boolean changed = false;

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
        DeadlineExtractor.Deadline deadline = deadlineExtractor.resolve(text, record.publishDate);
        record.deadlineDate = deadline.date();
        record.deadlineEstimated = deadline.estimated();
        record.deadlineSource = truncate(deadline.source(), 64);
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
        return 0;
    }

    private static final String AqggzyCrawlerCode = "aqggzy";
    private static final String WyxmzxCrawlerCode = "wyxmzx";

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

