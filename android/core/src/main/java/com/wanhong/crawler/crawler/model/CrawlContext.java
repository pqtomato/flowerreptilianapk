package com.wanhong.crawler.crawler.model;

import java.util.ArrayList;
import java.util.List;

/** 采集上下文：检索词（含作用域）、时间窗口与日志回调。 */
public class CrawlContext {

    private final List<SearchTerm> searchTerms;
    /** 本次采集的时间窗口覆盖值；null = 用站点自身配置 */
    private final Integer lookbackDaysOverride;
    private final Logger logger;

    public interface Logger {
        void log(String message);
    }

    public CrawlContext(List<SearchTerm> searchTerms, Logger logger) {
        this(searchTerms, null, logger);
    }

    public CrawlContext(List<SearchTerm> searchTerms, Integer lookbackDaysOverride, Logger logger) {
        this.searchTerms = searchTerms;
        this.lookbackDaysOverride = lookbackDaysOverride;
        this.logger = logger;
    }

    public List<SearchTerm> getSearchTerms() {
        return searchTerms;
    }

    /** 检索词文本，用于日志 */
    public List<String> getKeywords() {
        List<String> list = new ArrayList<>();
        for (SearchTerm term : searchTerms) {
            list.add(term.keyword());
        }
        return list;
    }

    public int resolveLookbackDays(int siteDefault) {
        return lookbackDaysOverride == null ? siteDefault : lookbackDaysOverride;
    }

    public void log(String message) {
        if (logger != null) {
            logger.log(message);
        }
    }
}
