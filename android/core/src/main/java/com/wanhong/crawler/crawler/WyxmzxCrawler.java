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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市阳光采购交易服务平台（https://www.wyxmzx.com/jyxx/）采集器。
 *
 * <p>列表页同样是 AJAX 渲染，走 epoint 站内检索接口，索引分类号 {@code cnum=080}。
 * 接口在 {@code cl} 长度内直接返回正文，大部分记录不需要回源详情页。
 *
 * <p>两个必须注意的坑（已在 HttpFetcher / 配置里处理）：
 * <ul>
 *   <li>带 {@code Origin} 头 → 503「疑似跨站请求伪造」；</li>
 *   <li>带 {@code Referer} 头 → 400。所以该站 referer 配置必须为空。</li>
 * </ul>
 */
public class WyxmzxCrawler implements SiteCrawler {

    public static final String SITE_CODE = "wyxmzx";

    private final CrawlerSettings settings;
    private final EpointSearchClient searchClient;

    public WyxmzxCrawler(CrawlerSettings settings, EpointSearchClient searchClient) {
        this.settings = settings;
        this.searchClient = searchClient;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return settings.sites.wyxmzx.name;
    }

    @Override
    public boolean enabled() {
        return settings.sites.wyxmzx.enabled;
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerSettings.Wyxmzx config = settings.sites.wyxmzx;
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
                        term.fields(),
                        config.cnum,
                        page,
                        config.pageSize,
                        config.contentLength,
                        lookbackDays,
                        // 该站接口要求 unionCondition 为 null 而非空数组
                        true));

                if (page == 0 && items.isEmpty() && !result.responseOk()) {
                    throw new CrawlException("检索接口首次请求即失败（疑似被 WAF 拦截），已跳过本站点");
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
                    CrawlItem item = new CrawlItem(SITE_CODE, config.name);
                    item.setTitle(record.title());
                    item.setUrl(url);
                    item.setCategoryCode(record.categoryCode());
                    item.setCategory(CategoryNames.resolve(record.categoryCode()));
                    item.setPublishDate(record.publishDate());
                    item.setSummary(record.content());
                    item.setSourceId(record.infoId().isEmpty() ? record.linkUrl() : record.infoId());
                    item.setSourceKeyword(term.keyword());
                    String region = RegionNames.byCityCode(record.cityCode());
                    item.setRegion(region.isEmpty() ? RegionNames.guessFromTitle(record.title()) : region);
                    items.add(item);
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
        return baseUrl + "/" + link;
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
