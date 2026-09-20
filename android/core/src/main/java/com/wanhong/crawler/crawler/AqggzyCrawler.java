package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.CategoryNames;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.epoint.EpointPage;
import com.wanhong.crawler.crawler.epoint.EpointQuery;
import com.wanhong.crawler.crawler.epoint.EpointSearchClient;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.http.CrawlException;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市公共资源交易服务网（https://aqggzy.anqing.gov.cn/）采集器。
 *
 * <p>列表页由前端 Mustache 模板 + AJAX 渲染，直接抓 HTML 拿不到数据，因此走站内检索接口，
 * 按关键词逐词检索。详情页地址按 epoint 目录约定拼接：
 * {@code /jyxx/{分类前6位}/{分类全码}/{yyyyMMdd}/{infoid}.html}
 */
public class AqggzyCrawler implements SiteCrawler {

    public static final String SITE_CODE = "aqggzy";

    private static final DateTimeFormatter DATE_PATH = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final CrawlerSettings settings;
    private final EpointSearchClient searchClient;

    public AqggzyCrawler(CrawlerSettings settings, EpointSearchClient searchClient) {
        this.settings = settings;
        this.searchClient = searchClient;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return settings.sites.aqggzy.name;
    }

    @Override
    public boolean enabled() {
        return settings.sites.aqggzy.enabled;
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerSettings.Aqggzy config = settings.sites.aqggzy;
        String endpoint = config.baseUrl + config.searchPath;
        int lookbackDays = context.resolveLookbackDays(config.lookbackDays);
        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (SearchTerm term : context.getSearchTerms()) {
            for (int page = 0; page < config.maxPagesPerKeyword; page++) {
                EpointPage result = searchClient.search(new EpointQuery(
                        endpoint,
                        config.referer,
                        term.keyword(),
                        // 正文词用 title;content 检索，能捞到"标题没写、正文提到"的公告
                        term.fields(),
                        "",
                        page,
                        config.pageSize,
                        config.contentLength,
                        lookbackDays,
                        false));

                // 快速失败：首次请求就不可解析（典型是被 WAF 拦截），直接判该站点失败，
                // 避免 50 多个关键词逐个白跑重试
                if (page == 0 && items.isEmpty() && !result.responseOk()) {
                    throw new CrawlException("检索接口首次请求即失败（疑似被 WAF 拦截或站点改版），已跳过本站点");
                }
                if (!result.responseOk() || result.isEmpty()) {
                    break;
                }

                for (EpointPage.EpointRecord record : result.records()) {
                    if (isExcluded(config.excludedCategoryPrefixes, record.categoryCode())) {
                        continue;
                    }
                    String url = buildDetailUrl(config.baseUrl, record);
                    if (url == null || !seenUrls.add(url)) {
                        continue;
                    }
                    items.add(toItem(config.name, record, url, term.keyword()));
                }

                if (result.records().size() < config.pageSize) {
                    break;
                }
                if ((long) (page + 1) * config.pageSize >= result.totalCount()) {
                    break;
                }
            }
        }
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
        item.setSourceId(record.infoId().isEmpty() ? record.linkUrl() : record.infoId());
        item.setSourceKeyword(keyword);
        String region = RegionNames.byCityCode(record.cityCode());
        item.setRegion(region.isEmpty() ? RegionNames.guessFromTitle(record.title()) : region);
        return item;
    }

    /** 把检索结果里的 linkurl 还原成详情页地址。 */
    private String buildDetailUrl(String baseUrl, EpointPage.EpointRecord record) {
        String link = record.linkUrl();
        if (link == null || link.trim().isEmpty()) {
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

    /**
     * 兜底链接：站点自身列表页用的跳转地址。
     *
     * <p>该站索引里混有大量转载自「安庆市公共资源交易平台」的县级公告
     * （categorynum 形如 036001xxx / 005xxx / 004xxx），这些记录在本站没有落地页，
     * 按目录规则拼出来的地址必然 404，所以改用站点自己的 tiaozhuan.html。
     */
    @Override
    public String fallbackDetailUrl(CrawlItem item) {
        String sourceId = item.getSourceId();
        if (sourceId == null || sourceId.trim().isEmpty()) {
            return null;
        }
        String category = item.getCategoryCode() == null ? "" : item.getCategoryCode();
        return settings.sites.aqggzy.baseUrl
                + "/tiaozhuan.html?infoid=" + sourceId + "&categorynum=" + category;
    }

    private boolean isExcluded(List<String> prefixes, String categoryCode) {
        if (prefixes == null || prefixes.isEmpty() || categoryCode == null) {
            return false;
        }
        for (String prefix : prefixes) {
            if (prefix != null && !prefix.trim().isEmpty() && categoryCode.startsWith(prefix.trim())) {
                return true;
            }
        }
        return false;
    }
}
