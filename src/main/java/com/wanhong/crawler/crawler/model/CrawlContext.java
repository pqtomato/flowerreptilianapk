package com.wanhong.crawler.crawler.model;

import java.util.List;
import java.util.function.Consumer;

/**
 * 采集上下文：传给每个站点采集器的检索词、时间窗口与日志回调。
 */
public class CrawlContext {

    /** 检索词（含作用域，决定搜标题还是搜正文） */
    private final List<SearchTerm> searchTerms;
    /** 本次采集的时间窗口覆盖值；null = 使用站点自身配置 */
    private final Integer lookbackDaysOverride;
    private final Consumer<String> logger;

    public CrawlContext(List<SearchTerm> searchTerms, Consumer<String> logger) {
        this(searchTerms, null, logger);
    }

    public CrawlContext(List<SearchTerm> searchTerms, Integer lookbackDaysOverride, Consumer<String> logger) {
        this.searchTerms = searchTerms;
        this.lookbackDaysOverride = lookbackDaysOverride;
        this.logger = logger == null ? message -> { } : logger;
    }

    public List<SearchTerm> getSearchTerms() {
        return searchTerms;
    }

    /** 检索词文本，用于日志 */
    public List<String> getKeywords() {
        return searchTerms.stream().map(SearchTerm::keyword).toList();
    }

    /**
     * 解析实际使用的时间窗口：本次覆盖值优先，否则用站点配置。
     * 0 表示不限时间（首次建库要历史数据时用）。
     */
    public int resolveLookbackDays(int siteDefault) {
        return lookbackDaysOverride == null ? siteDefault : lookbackDaysOverride;
    }

    public void log(String message) {
        logger.accept(message);
    }
}
