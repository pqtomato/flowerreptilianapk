package com.wanhong.crawler.crawler;

import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;

import java.util.List;

/**
 * 站点采集器：只负责「发现公告」，不负责关键词过滤与落库。
 */
public interface SiteCrawler {

    /** 站点编码 */
    String siteCode();

    /** 站点名称 */
    String siteName();

    /** 是否启用 */
    boolean enabled();

    /** 发现候选公告 */
    List<CrawlItem> crawl(CrawlContext context) throws Exception;

    /**
     * 详情页打不开时的兜底链接。
     *
     * <p>有些站点的索引里存在「已转载但没有落地页」的记录，拼出来的详情地址会 404。
     * 这种情况下返回站点自己的规范跳转链接，至少不会给用户一个死链。默认没有兜底。
     */
    default String fallbackDetailUrl(CrawlItem item) {
        return null;
    }
}
