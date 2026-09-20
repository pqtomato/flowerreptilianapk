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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市阳光采购交易服务平台（https://www.wyxmzx.com/jyxx/）采集器。
 *
 * <p>该站列表页 {@code /jyxx/{分类}/bussinessInfo.html} 的列表数据同样是 AJAX 渲染，
 * 因此走与 aqggzy 相同的 epoint 站内检索接口，索引分类号 {@code cnum=080}
 * （该平台全部交易信息）。
 *
 * <p>特别注意：该站 WAF 会把携带 {@code Origin} 头的接口请求判定为
 * 「疑似跨站请求伪造」并拒绝，{@link com.wanhong.crawler.http.HttpFetcher} 不会发送该头。
 *
 * <p>该接口在 {@code cl} 长度范围内直接返回正文，可省掉一次详情页请求；
 * 正文过短时才回源抓详情页。
 */
@Component
public class WyxmzxCrawler implements SiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(WyxmzxCrawler.class);

    public static final String SITE_CODE = "wyxmzx";

    private final CrawlerProperties properties;
    private final EpointSearchClient searchClient;

    public WyxmzxCrawler(CrawlerProperties properties, EpointSearchClient searchClient) {
        this.properties = properties;
        this.searchClient = searchClient;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return properties.getSites().getWyxmzx().getName();
    }

    @Override
    public boolean enabled() {
        return properties.getSites().getWyxmzx().isEnabled();
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerProperties.Wyxmzx config = properties.getSites().getWyxmzx();
        String endpoint = config.getBaseUrl() + config.getSearchPath();
        int lookbackDays = context.resolveLookbackDays(config.getLookbackDays());
        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (SearchTerm term : context.getSearchTerms()) {
            for (int page = 0; page < config.getMaxPagesPerKeyword(); page++) {
                EpointPage result = searchClient.search(new EpointQuery(
                        endpoint,
                        config.getReferer(),
                        term.keyword(),
                        term.fields(),
                        config.getCnum(),
                        page,
                        config.getPageSize(),
                        config.getContentLength(),
                        lookbackDays,
                        // 该站接口要求 unionCondition 为 null 而非空数组
                        true));

                // 快速失败：首次请求即不可解析（如被 WAF 判定为跨站请求伪造）则跳过本站点
                if (page == 0 && items.isEmpty() && !result.responseOk()) {
                    throw new CrawlException("检索接口首次请求即失败（疑似被 WAF 拦截），已跳过本站点");
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
                    CrawlItem item = new CrawlItem(SITE_CODE, config.getName());
                    item.setTitle(record.title());
                    item.setUrl(url);
                    item.setCategoryCode(record.categoryCode());
                    item.setCategory(CategoryNames.resolve(record.categoryCode()));
                    item.setPublishDate(record.publishDate());
                    item.setSummary(record.content());
                    item.setSourceId(record.infoId().isBlank() ? record.linkUrl() : record.infoId());
                    item.setSourceKeyword(term.keyword());
                    String region = RegionNames.byCityCode(record.cityCode());
                    item.setRegion(region.isBlank() ? RegionNames.guessFromTitle(record.title()) : region);
                    items.add(item);
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
        return baseUrl + "/" + link;
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
