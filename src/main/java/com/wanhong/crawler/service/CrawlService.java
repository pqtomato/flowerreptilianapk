package com.wanhong.crawler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.common.Hashing;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.AqggzyCrawler;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.WyxmzxCrawler;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.CrawlResult;
import com.wanhong.crawler.crawler.model.DetailResult;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.entity.CrawlLog;
import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.repository.CrawlLogRepository;
import com.wanhong.crawler.repository.TenderInfoRepository;
import com.wanhong.crawler.web.dto.CrawlStatus;
import com.wanhong.crawler.web.dto.CrawlSummary;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 采集调度服务：串联「站点发现 → 关键词过滤 → 详情补全 → 去重入库 → 记录日志」。
 */
@Service
public class CrawlService {

    private static final Logger log = LoggerFactory.getLogger(CrawlService.class);

    private static final int MAX_SUMMARY_LENGTH = 2000;
    private static final int MAX_KEYWORDS_LENGTH = 500;

    private final CrawlerProperties properties;
    private final KeywordMatcher keywordMatcher;
    private final TenderInfoRepository tenderRepository;
    private final CrawlLogRepository crawlLogRepository;
    private final HttpFetcher httpFetcher;
    private final DetailParser detailParser;
    private final DeadlineExtractor deadlineExtractor;
    private final VerificationService verificationService;
    private final ObjectMapper objectMapper;
    private final Map<String, SiteCrawler> crawlerByCode = new LinkedHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor;

    private volatile String runningSite;
    private volatile LocalDateTime runningSince;
    private volatile CrawlSummary lastSummary;

    public CrawlService(CrawlerProperties properties,
                        KeywordMatcher keywordMatcher,
                        TenderInfoRepository tenderRepository,
                        CrawlLogRepository crawlLogRepository,
                        HttpFetcher httpFetcher,
                        DetailParser detailParser,
                        DeadlineExtractor deadlineExtractor,
                        VerificationService verificationService,
                        ObjectMapper objectMapper,
                        List<SiteCrawler> crawlers) {
        this.properties = properties;
        this.keywordMatcher = keywordMatcher;
        this.tenderRepository = tenderRepository;
        this.crawlLogRepository = crawlLogRepository;
        this.httpFetcher = httpFetcher;
        this.detailParser = detailParser;
        this.deadlineExtractor = deadlineExtractor;
        this.verificationService = verificationService;
        this.objectMapper = objectMapper;
        for (SiteCrawler crawler : crawlers) {
            crawlerByCode.put(crawler.siteCode(), crawler);
        }
        // 单线程执行器：保证同一时刻只有一个采集任务，天然限流
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "crawler-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public CrawlStatus status() {
        return new CrawlStatus(running.get(), runningSite, runningSince,
                httpFetcher.getRequestCount(), lastSummary, availableSites());
    }

    /** 可用站点清单。 */
    public List<Map<String, Object>> availableSites() {
        List<Map<String, Object>> sites = new ArrayList<>();
        crawlerByCode.forEach((code, crawler) -> {
            Map<String, Object> site = new LinkedHashMap<>();
            site.put("siteCode", code);
            site.put("siteName", crawler.siteName());
            site.put("enabled", crawler.enabled());
            sites.add(site);
        });
        return sites;
    }

    /**
     * 异步触发采集，立即返回。
     *
     * <p>抢占运行标记是在提交任务 <b>之前</b> 同步完成的，这样接口返回的状态就是准确的
     * 「正在采集」，不会出现"返回 running=false 但其实已经开始跑"的竞态。
     */
    public CrawlStatus triggerAsync(String siteCode, Integer maxKeywords, Integer lookbackDays, String triggerType) {
        if (!properties.isEnabled()) {
            this.lastSummary = CrawlSummary.of("DISABLED", "爬虫总开关 crawler.enabled=false，未执行采集");
            return status();
        }
        if (!running.compareAndSet(false, true)) {
            return status();
        }
        this.runningSite = (siteCode == null || siteCode.isBlank()) ? "all" : siteCode;
        this.runningSince = LocalDateTime.now();

        executor.submit(() -> {
            try {
                this.lastSummary = doCrawl(siteCode, maxKeywords, lookbackDays, triggerType);
            } catch (Exception e) {
                log.error("异步采集任务异常", e);
                this.lastSummary = CrawlSummary.of("FAILED", e.getMessage());
            } finally {
                running.set(false);
                runningSite = null;
                runningSince = null;
            }
        });
        return status();
    }

    /**
     * 同步执行采集（使用站点自身的 time window 配置）。
     */
    public CrawlSummary crawl(String siteCode, Integer maxKeywords, String triggerType) {
        return crawl(siteCode, maxKeywords, null, triggerType);
    }

    /**
     * 同步执行采集。
     *
     * @param siteCode     all / aqggzy / cgj / wyxmzx，空视为 all
     * @param maxKeywords  只使用前 N 个检索词（调试或快速采集用），null 表示全部
     * @param lookbackDays 只采集最近 N 天的公告；null 用站点配置，0 表示不限（建库要历史数据）
     * @param triggerType  MANUAL / SCHEDULED
     */
    public CrawlSummary crawl(String siteCode, Integer maxKeywords, Integer lookbackDays, String triggerType) {
        if (!properties.isEnabled()) {
            return CrawlSummary.of("DISABLED", "爬虫总开关 crawler.enabled=false，未执行采集");
        }
        if (!running.compareAndSet(false, true)) {
            return CrawlSummary.of("BUSY", "已有采集任务正在执行：" + runningSite);
        }
        this.runningSite = (siteCode == null || siteCode.isBlank()) ? "all" : siteCode;
        this.runningSince = LocalDateTime.now();
        try {
            CrawlSummary summary = doCrawl(siteCode, maxKeywords, lookbackDays, triggerType);
            this.lastSummary = summary;
            return summary;
        } finally {
            running.set(false);
            runningSite = null;
            runningSince = null;
        }
    }

    /**
     * 真正的采集流程，调用方负责持有运行标记。
     */
    private CrawlSummary doCrawl(String siteCode, Integer maxKeywords, Integer lookbackDays, String triggerType) {
        long startedAt = System.currentTimeMillis();

        List<SiteCrawler> targets = resolveCrawlers(siteCode);
        // 检索词带作用域：标题词搜 title，正文词搜 title;content
        List<SearchTerm> terms = keywordMatcher.getSearchTerms();
        if (maxKeywords != null && maxKeywords > 0 && terms.size() > maxKeywords) {
            terms = terms.subList(0, maxKeywords);
        }
        if (terms.isEmpty()) {
            return CrawlSummary.of("FAILED", "关键词为空，请检查词表文件或在「关键词」页添加");
        }

        httpFetcher.resetRequestCount();
        AtomicInteger detailBudget = new AtomicInteger(Math.max(0, properties.getRequest().getMaxDetailPerRun()));

        CrawlSummary summary = new CrawlSummary();
        summary.setTriggerType(triggerType);
        int okSites = 0;
        for (SiteCrawler crawler : targets) {
            CrawlResult result = crawlOne(crawler, terms, lookbackDays, detailBudget, triggerType);
            if (result.getErrorMessage() == null) {
                okSites++;
            }
            summary.addSite(result);
        }

        // 采集完成后顺带抽查已入库公告是否已下架（沿用上一版系统的流程顺序）
        if (properties.getVerify().isEnabled() && okSites > 0) {
            try {
                int checked = verificationService.verify(null);
                summary.setVerifiedCount(checked);
            } catch (Exception e) {
                log.warn("已下架校验失败（不影响采集结果）：{}", e.getMessage());
            }
        }

        summary.setDurationMs(System.currentTimeMillis() - startedAt);
        if (okSites == 0) {
            summary.setStatus("FAILED");
            summary.setMessage("全部站点采集失败");
        } else if (okSites < targets.size()) {
            summary.setStatus("PARTIAL");
            summary.setMessage(okSites + "/" + targets.size() + " 个站点采集成功");
        } else {
            summary.setStatus("SUCCESS");
            summary.setMessage("采集完成，新增 " + summary.getSavedCount() + " 条");
        }
        if (summary.getVerifiedCount() > 0) {
            summary.setMessage(summary.getMessage() + "；已下架校验 " + summary.getVerifiedCount() + " 条");
        }
        log.info("采集结束：{}，候选 {}，命中 {}，新增 {}，重复 {}，耗时 {}ms",
                summary.getStatus(), summary.getCandidateCount(), summary.getMatchedCount(),
                summary.getSavedCount(), summary.getDuplicateCount(), summary.getDurationMs());
        return summary;
    }

    /** 站点配置的时间窗口，用于日志展示 */
    private int siteLookbackDays(String siteCode) {
        return switch (siteCode) {
            case AqggzyCrawler.SITE_CODE -> properties.getSites().getAqggzy().getLookbackDays();
            case WyxmzxCrawler.SITE_CODE -> properties.getSites().getWyxmzx().getLookbackDays();
            default -> 0;
        };
    }

    private List<SiteCrawler> resolveCrawlers(String siteCode) {        if (siteCode == null || siteCode.isBlank() || "all".equalsIgnoreCase(siteCode.trim())) {
            List<SiteCrawler> enabled = new ArrayList<>();
            crawlerByCode.values().stream().filter(SiteCrawler::enabled).forEach(enabled::add);
            if (enabled.isEmpty()) {
                throw new IllegalStateException("没有任何启用的站点，请检查 crawler.sites.*.enabled");
            }
            return enabled;
        }
        String code = siteCode.trim().toLowerCase();
        SiteCrawler crawler = crawlerByCode.get(code);
        if (crawler == null) {
            throw new IllegalArgumentException("未知站点：" + siteCode + "，可选值：" + crawlerByCode.keySet());
        }
        if (!crawler.enabled()) {
            throw new IllegalStateException("站点已禁用：" + code);
        }
        return List.of(crawler);
    }

    private CrawlResult crawlOne(SiteCrawler crawler, List<SearchTerm> terms, Integer lookbackDays,
                                 AtomicInteger detailBudget, String triggerType) {
        CrawlResult result = new CrawlResult(crawler.siteCode(), crawler.siteName());
        long startedAt = System.currentTimeMillis();
        long requestsBefore = httpFetcher.getRequestCount();

        CrawlLog crawlLog = new CrawlLog();
        crawlLog.setSiteCode(crawler.siteCode());
        crawlLog.setTriggerType(triggerType);
        crawlLog.setStatus("RUNNING");
        crawlLog.setStartTime(LocalDateTime.now());
        crawlLog = crawlLogRepository.save(crawlLog);

        int effectiveLookback = lookbackDays != null ? lookbackDays
                : siteLookbackDays(crawler.siteCode());
        long contentTerms = terms.stream().filter(t -> SearchTerm.SCOPE_CONTENT.equals(t.scope())).count();
        log.info("[{}] 开始采集，检索词 {} 个（其中正文检索 {} 个），时间窗口 {}",
                crawler.siteName(), terms.size(), contentTerms,
                effectiveLookback > 0 ? "最近 " + effectiveLookback + " 天" : "不限");
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
                    log.warn("[{}] 处理公告失败：{}", crawler.siteCode(), item.getTitle(), e);
                }
            }
            crawlLog.setStatus("SUCCESS");
        } catch (Exception e) {
            result.setErrorMessage(e.getMessage());
            crawlLog.setStatus("FAILED");
            crawlLog.setErrorMessage(truncate(e.getMessage(), 2000));
            log.error("[{}] 采集失败", crawler.siteName(), e);
        } finally {
            result.setDurationMs(System.currentTimeMillis() - startedAt);
            result.setRequestCount(httpFetcher.getRequestCount() - requestsBefore);
            crawlLog.setEndTime(LocalDateTime.now());
            crawlLog.setDurationMs(result.getDurationMs());
            crawlLog.setCandidateCount(result.getCandidateCount());
            crawlLog.setMatchedCount(result.getMatchedCount());
            crawlLog.setSavedCount(result.getSavedCount());
            crawlLog.setDuplicateCount(result.getDuplicateCount());
            crawlLog.setFailedCount(result.getFailedCount());
            crawlLog.setRequestCount((int) result.getRequestCount());
            crawlLog.setDetail(truncate(String.join("\n", result.getMessages()), 20000));
            crawlLogRepository.save(crawlLog);
            log.info("[{}] 采集完成：候选 {}，排除 {}，命中 {}，新增 {}，重复 {}，耗时 {}ms",
                    crawler.siteName(), result.getCandidateCount(), result.getExcludedCount(),
                    result.getMatchedCount(), result.getSavedCount(), result.getDuplicateCount(),
                    result.getDurationMs());
        }
        return result;
    }

    /**
     * 处理单条候选公告。
     */
    private void processItem(CrawlItem item, CrawlResult result, AtomicInteger detailBudget) {
        String title = item.getTitle();
        String rawUrl = item.getUrl();
        if (title == null || title.isBlank() || rawUrl == null || rawUrl.isBlank()) {
            result.incrementFailed();
            return;
        }

        String urlHash = Hashing.urlHash(rawUrl);
        Optional<TenderInfo> existingOpt = tenderRepository.findByUrlHash(urlHash);
        if (existingOpt.isPresent()) {
            handleDuplicate(existingOpt.get(), item, result, detailBudget);
            return;
        }

        // 标题命中排除词直接丢弃。
        // 必须在这里先判一次：下面"正文命中"那条路径传的标题是 null，
        // 否则「XX资产拍卖交易公告」这种标题会被正文里的"苗木"放进来。
        if (keywordMatcher.isExcluded(title)) {
            result.incrementExcluded();
            return;
        }

        KeywordMatcher.MatchResult titleMatch = keywordMatcher.match(title, null);
        KeywordMatcher.MatchResult summaryMatch = keywordMatcher.match(null, item.getSummary());
        if (!titleMatch.isMatched() && !summaryMatch.isMatched()) {
            // 未命中关键词，静默跳过
            return;
        }
        result.incrementMatched();

        LocalDateTime now = LocalDateTime.now();
        TenderInfo entity = new TenderInfo();
        entity.setSiteCode(item.getSiteCode());
        entity.setSiteName(item.getSiteName());
        entity.setTitle(truncate(title, 1000));
        entity.setUrl(truncate(rawUrl, 1000));
        entity.setUrlHash(urlHash);
        entity.setCategory(truncate(item.getCategory(), 200));
        entity.setCategoryCode(truncate(item.getCategoryCode(), 64));
        entity.setRegion(truncate(item.getRegion(), 64));
        entity.setPublishDate(item.getPublishDate());
        entity.setSourceKeyword(truncate(item.getSourceKeyword(), 128));
        entity.setSummary(truncate(item.getSummary(), MAX_SUMMARY_LENGTH));
        entity.setCrawlTime(now);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        entity.setIsNew(Boolean.TRUE);
        entity.setTracked(Boolean.FALSE);
        entity.setRemoved(Boolean.FALSE);

        // 抓详情页补全正文、附件与缺失的发布日期
        boolean detailFetched = false;
        if (properties.getDetail().isEnabled() && detailBudget.get() > 0 && isFetchable(rawUrl)) {
            detailBudget.decrementAndGet();
            DetailResult detail = fetchDetail(rawUrl);
            if (detail != null) {
                applyDetail(entity, detail);
                detailFetched = !detail.isEmpty();
            }
        }
        if (detailFetched) {
            entity.setLinkStatus("OK");
        } else {
            // 详情页打不开（常见于转载类记录）：退而使用检索接口自带的正文片段，
            // 并把用户可点击的链接换成站点自己的跳转地址，避免死链
            useSummaryAsContent(entity);
            String fallback = fallbackUrlFor(item);
            if (fallback != null && !fallback.isBlank()) {
                entity.setAccessUrl(truncate(fallback, 1000));
                entity.setLinkStatus("FALLBACK");
            } else {
                entity.setLinkStatus("UNKNOWN");
            }
        }
        // 入库时已经探过一次链接，直接记为"已验证"。
        // 否则每次采集后的「已下架校验」都要把整个库重新请求一遍 —— 实测这会让
        // 一次增量采集从 4 分钟变成 8 分钟以上。标记之后，校验任务只需要在
        // skip-hours（默认 12 小时）之后复查，负载与"新增量"成正比而不是"库存量"。
        entity.setVerifiedAt(now);

        applyDeadline(entity);
        entity.setRegion(resolveRegion(entity));

        Set<String> hits = new LinkedHashSet<>(titleMatch.keywords());
        hits.addAll(summaryMatch.keywords());
        KeywordMatcher.MatchResult finalTitle = keywordMatcher.match(entity.getTitle(), null);
        String contentForMatch = entity.getContent() != null && !entity.getContent().isBlank()
                ? entity.getContent() : entity.getSummary();
        KeywordMatcher.MatchResult finalContent = keywordMatcher.match(null, contentForMatch);
        hits.addAll(finalTitle.keywords());
        hits.addAll(finalContent.keywords());

        entity.setMatchedKeywords(truncate(String.join(",", hits), MAX_KEYWORDS_LENGTH));
        entity.setMatchedScope(resolveScope(finalTitle.isMatched(), finalContent.isMatched()));

        tenderRepository.save(entity);
        result.incrementSaved();
    }

    /**
     * 已存在的公告：补充跨站来源信息，并在之前没抓到正文时补抓一次。
     */
    private void handleDuplicate(TenderInfo existing, CrawlItem item,
                                 CrawlResult result, AtomicInteger detailBudget) {
        boolean changed = false;

        // 同一公告可能同时出现在多个站点（如城管局公告直接外链到交易平台）
        if (!item.getSiteCode().equals(existing.getSiteCode())) {
            Set<String> alsoFoundOn = new LinkedHashSet<>();
            if (existing.getAlsoFoundOn() != null && !existing.getAlsoFoundOn().isBlank()) {
                alsoFoundOn.addAll(Arrays.asList(existing.getAlsoFoundOn().split(",")));
            }
            if (alsoFoundOn.add(item.getSiteCode())) {
                existing.setAlsoFoundOn(truncate(String.join(",", alsoFoundOn), 255));
                changed = true;
            }
        }

        boolean missingContent = existing.getContent() == null || existing.getContent().isBlank();
        if (missingContent && properties.getDetail().isEnabled()
                && detailBudget.get() > 0 && isFetchable(existing.getUrl())) {
            detailBudget.decrementAndGet();
            DetailResult detail = fetchDetail(existing.getUrl());
            if (detail != null && !detail.isEmpty()) {
                applyDetail(existing, detail);
                changed = true;
            }
        }
        if (existing.getContent() == null || existing.getContent().isBlank()) {
            useSummaryAsContent(existing);
            if (existing.getAccessUrl() == null || existing.getAccessUrl().isBlank()) {
                String fallback = fallbackUrlFor(item);
                if (fallback != null && !fallback.isBlank()) {
                    existing.setAccessUrl(truncate(fallback, 1000));
                    existing.setLinkStatus("FALLBACK");
                    changed = true;
                }
            }
        }
        // 老记录补全截止日期与区县
        if (existing.getDeadlineDate() == null) {
            applyDeadline(existing);
            changed = true;
        }
        String region = resolveRegion(existing);
        if (region != null && !region.isBlank() && !region.equals(existing.getRegion())) {
            existing.setRegion(region);
            changed = true;
        }

        if (changed) {
            existing.setUpdateTime(LocalDateTime.now());
            tenderRepository.save(existing);
        }
        result.incrementDuplicate();
    }

    private void applyDetail(TenderInfo entity, DetailResult detail) {
        if (detail.contentText() != null && !detail.contentText().isBlank()) {
            entity.setContent(detail.contentText());
            entity.setContentHtml(detail.contentHtml());
        }
        if (detail.publishDate() != null && entity.getPublishDate() == null) {
            entity.setPublishDate(detail.publishDate());
        }
        if (!detail.attachments().isEmpty()) {
            try {
                entity.setAttachments(objectMapper.writeValueAsString(detail.attachments()));
            } catch (Exception e) {
                log.warn("附件列表序列化失败", e);
            }
        }
    }

    /**
     * 详情页拿不到正文时，退而使用列表/检索接口自带的正文片段。
     */
    private void useSummaryAsContent(TenderInfo entity) {
        if (entity.getContent() != null && !entity.getContent().isBlank()) {
            return;
        }
        String summary = entity.getSummary();
        if (summary != null && summary.length() >= 60) {
            entity.setContent(summary);
        }
    }

    /**
     * 解析 / 估算报名投标截止日期。
     *
     * <p>参考上一版系统：只有极少数平台给真实截止时间，其余都得从正文里抠，
     * 抠不到就按「发布日期 + N 天」估算 —— 手机上"还能不能投"全靠这个字段。
     */
    private void applyDeadline(TenderInfo entity) {
        String text = entity.getContent() != null && !entity.getContent().isBlank()
                ? entity.getContent() : entity.getSummary();
        DeadlineExtractor.Deadline deadline = deadlineExtractor.resolve(text, entity.getPublishDate());
        entity.setDeadlineDate(deadline.date());
        entity.setDeadlineEstimated(deadline.estimated());
        entity.setDeadlineSource(truncate(deadline.source(), 64));
    }

    /** 区县：优先用采集器给的，没有就从标题推断 */
    private String resolveRegion(TenderInfo entity) {
        if (entity.getRegion() != null && !entity.getRegion().isBlank()) {
            return entity.getRegion();
        }
        return truncate(RegionNames.guessFromTitle(entity.getTitle()), 64);
    }

    private String fallbackUrlFor(CrawlItem item) {
        SiteCrawler crawler = crawlerByCode.get(item.getSiteCode());
        if (crawler == null) {
            return null;
        }
        return crawler.fallbackDetailUrl(item);
    }

    /**
     * 抓取详情页，返回 null 表示请求失败或状态码非 2xx。
     */
    private DetailResult fetchDetail(String url) {
        try {
            FetchResponse response = httpFetcher.get(url, refererOf(url));
            if (!response.isSuccess()) {
                log.debug("详情页返回 HTTP {}：{}", response.status(), url);
                return null;
            }
            return detailParser.parse(response.body(), response.finalUrl());
        } catch (Exception e) {
            log.debug("详情页抓取失败：{}（{}）", url, e.getMessage());
            return null;
        }
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

    private String resolveScope(boolean titleHit, boolean contentHit) {
        if (titleHit && contentHit) {
            return "TITLE,CONTENT";
        }
        if (titleHit) {
            return "TITLE";
        }
        if (contentHit) {
            return "CONTENT";
        }
        return "TITLE";
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
