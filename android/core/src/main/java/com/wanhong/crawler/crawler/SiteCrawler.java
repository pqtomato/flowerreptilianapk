package com.wanhong.crawler.crawler;

import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;

import java.util.List;

/** 站点采集器：只负责「发现公告」，不负责关键词过滤与落库。 */
public interface SiteCrawler {

    String siteCode();

    String siteName();

    boolean enabled();

    List<CrawlItem> crawl(CrawlContext context) throws Exception;

    /**
     * 详情页打不开时的兜底链接。
     *
     * <p>有些站点（交易服务网）的索引里存在「已转载但没有落地页」的记录，拼出来的详情地址
     * 必然 404。此时返回站点自己的规范跳转链接，至少不会给用户死链。
     */
    default String fallbackDetailUrl(CrawlItem item) {
        return null;
    }
}
