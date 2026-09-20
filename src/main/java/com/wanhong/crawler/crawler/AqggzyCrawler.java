package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.CategoryNames;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.epoint.EpointPage;
import com.wanhong.crawler.crawler.epoint.EpointQuery;
import com.wanhong.crawler.crawler.epoint.EpointSearchClient;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.http.CrawlException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市公共资源交易服务网（https://aqggzy.anqing.gov.cn/）采集器。
 *
 * <p>该站列表页由前端 Mustache 模板 + AJAX 渲染，直接抓 HTML 拿不到数据，
 * 因此走站内检索接口 {@code /inteligentsearch/rest/esinteligentsearch/getFullTextDataNew}，
 * 按关键词逐词检索。
 *
 * <p>详情页地址规则（epoint 目录约定）：
 * {@code /jyxx/{分类前6位}/{分类全码}/{yyyyMMdd}/{infoid}.html}
 */
@Component
public class AqggzyCrawler implements SiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(AqggzyCrawler.class);

    public static final String SITE_CODE = "aqggzy";

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CrawlerProperties properties;
    private final EpointSearchClient searchClient;

    public AqggzyCrawler(CrawlerProperties properties, EpointSearchClient searchClient) {
        this.properties = properties;
        this.searchClient = searchClient;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return properties.getSites().getAqggzy().getName();
    }

    @Override
    public boolean enabled() {
        return properties.getSites().getAqggzy().isEnabled();
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerProperties.Aqggzy config = properties.getSites().getAqggzy();
        String endpoint = config.getBaseUrl() + config.getSearchPath();
        int lookbackDays = context.resolveLookbackDays(config.getLookbackDays());
        List<CrawlItem> items = new ArrayList<>();
        // 同一公告会被多个关键词命中，这里按 URL 去重
        Set<String> seenUrls = new LinkedHashSet<>();

        for (SearchTerm term : context.getSearchTerms()) {
            for (int page = 0; page < config.getMaxPagesPerKeyword(); page++) {
                EpointPage result = searchClient.search(new EpointQuery(
                        endpoint,
                        config.getReferer(),
                        term.keyword(),
                        // 正文词用 title;content 检索，能捞到"标题没写、正文提到"的公告
                        term.fields(),
                        "",
                        page,
                        config.getPageSize(),
                        config.getContentLength(),
                        lookbackDays,
                        false));

                // 快速失败：第一次请求就返回不可解析的内容（典型是被 WAF 拦截），
                // 直接判定该站点本次采集失败，避免 50 多个关键词白白重试一轮
                if (page == 0 && items.isEmpty() && !result.responseOk()) {
                    throw new CrawlException("检索接口首次请求即失败（疑似被 WAF 拦截或站点改版），已跳过本站点");
                }
                if (!result.responseOk()) {
                    break;
                }
                if (result.isEmpty()) {
                    break;
                }
                for (EpointPage.EpointRecord record : result.records()) {
                    if (isExcluded(config.getExcludedCategoryPrefixes(), record.categoryCode())) {
                        continue;
                    }
                    String url = buildDetailUrl(config.getBaseUrl(), record);
                    if (url == null || !seenUrls.add(url)) {
                        continue;
                    }
                    items.add(toItem(config.getName(), record, url, term.keyword()));
                }

                if (result.records().size() < config.getPageSize()) {
                    break;
                }
                if ((long) (page + 1) * config.getPageSize() >= result.totalCount()) {
                    break;
                }
            }
        }
        log.info("[{}] 检索完成，候选公告 {} 条", siteName(), items.size());
        return items;
    }

    private CrawlItem toItem(String siteName, EpointPage.EpointRecord record, String url, String keyword) {
        CrawlItem item = new CrawlItem(SITE_CODE, siteName);
        item.setTitle(record.title());
        item.setUrl(url);
        item.setCategoryCode(record.categoryCode());
        item.setCategory(CategoryNames.resolve(record.categoryCode()));
        item.setPublishDate(record.publishDate());
        item.setSummary(record.content());
        item.setSourceId(record.infoId().isBlank() ? record.linkUrl() : record.infoId());
        item.setSourceKeyword(keyword);
        // 地区：该站接口直接给 citycode，映射成区县名；没有就从标题猜
        String region = RegionNames.byCityCode(record.cityCode());
        item.setRegion(region.isBlank() ? RegionNames.guessFromTitle(record.title()) : region);
        return item;
    }

    /**
     * 兜底链接：站点自身列表页用的跳转地址。
     *
     * <p>该站索引里混有大量转载自「安庆市公共资源交易平台」的县级公告
     * （categorynum 形如 036001xxx / 005xxx / 004xxx），这些记录在本站没有
     * 落地页，按目录规则拼出来的地址必然 404。此时改用站点自己的
     * {@code /tiaozhuan.html?infoid=..&categorynum=..}，与官网列表页一致。
     */
    @Override
    public String fallbackDetailUrl(CrawlItem item) {
        String sourceId = item.getSourceId();
        if (sourceId == null || sourceId.isBlank()) {
            return null;
        }
        String category = item.getCategoryCode() == null ? "" : item.getCategoryCode();
        return properties.getSites().getAqggzy().getBaseUrl()
                + "/tiaozhuan.html?infoid=" + sourceId + "&categorynum=" + category;
    }

    /**
     * 把检索结果里的 linkurl 还原成详情页地址。
     */
    private String buildDetailUrl(String baseUrl, EpointPage.EpointRecord record) {
        String link = record.linkUrl();
        if (link == null || link.isBlank()) {
            return null;
        }
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        if (link.startsWith("/")) {
            return baseUrl + link;
        }
        String category = record.categoryCode();
        if (category == null || category.length() < 6 || record.publishDate() == null) {
            return null;
        }
        return baseUrl + "/jyxx/" + category.substring(0, 6) + "/" + category + "/"
                + record.publishDate().format(DATE_PATH) + "/" + link + ".html";
    }

    private boolean isExcluded(List<String> prefixes, String categoryCode) {
        if (prefixes == null || prefixes.isEmpty() || categoryCode == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (!prefix.isBlank() && categoryCode.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }
}
