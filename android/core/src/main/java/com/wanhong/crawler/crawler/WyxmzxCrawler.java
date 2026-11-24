package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.CategoryNames;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.epoint.EpointPage;
import com.wanhong.crawler.crawler.epoint.EpointQuery;
import com.wanhong.crawler.crawler.epoint.EpointSearchClient;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.http.CrawlException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市阳光采购交易服务平台（https://www.wyxmzx.com/jyxx/）采集器。
 *
 * <p>列表页原本走 epoint 站内检索接口（{@code cnum=080}）；2026-11-24 起该接口会忽略
 * 关键词，只按 {@code webdate} 倒序返回全部公告，所以采集改成翻页列表，
 * 关键词匹配交给 CrawlEngine。接口在 {@code cl} 长度内直接返回正文，
 * 大部分记录不需要回源详情页。
 *
 * <p>四个必须注意的坑（前两个已在 HttpFetcher 里处理）：
 * <ul>
 *   <li>带 {@code Origin} 头 → 503「疑似跨站请求伪造」；</li>
 *   <li>带 {@code Referer} 头 → 400。所以该站 referer 配置必须为空；</li>
 *   <li>https 的检索接口与详情页都返回 404，只有 http 正常；</li>
 *   <li>接口的 {@code time} 日期过滤、非空 {@code fields}、{@code cnum=080} 都会让
 *       结果变成 0 条（实测），所以这里 <b>不发时间窗、fields 传空串、cnum 传空串</b>，
 *       拿到记录后按发布日期在本地过滤。</li>
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
        // 接口的 time 过滤会让结果变成 0 条，时间窗在本地过
        LocalDateTime since = lookbackDays > 0
                ? LocalDateTime.now(ZoneId.of(settings.timezone)).minusDays(lookbackDays)
                : null;
        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (int page = 0; page < config.maxPages; page++) {
            EpointPage result = searchClient.search(new EpointQuery(
                    endpoint,
                    config.referer,
                    // 接口忽略关键词，按列表翻页；关键词匹配交给 CrawlEngine
                    "",
                    // 该站接口只有 fields 为空串时才返回结果（填 title 会 0 条）
                    "",
                    config.cnum,
                    page,
                    config.pageSize,
                    config.contentLength,
                    // 不传时间窗：带上 time 过滤后该站接口返回空结果
                    0,
                    // 该站接口要求 unionCondition 为 null 而非空数组
                    true));

            if (page == 0 && items.isEmpty() && !result.responseOk()) {
                throw new CrawlException("检索接口首次请求即失败（疑似被 WAF 拦截），已跳过本站点");
            }
            if (!result.responseOk() || result.isEmpty()) {
                break;
            }

            // 整页都早于时间窗就收工（记录按 webdate 倒序）
            boolean allBeforeWindow = since != null;
            for (EpointPage.EpointRecord record : result.records()) {
                LocalDateTime published = record.publishDate();
                if (published == null || since == null || !published.isBefore(since)) {
                    allBeforeWindow = false;
                }
                if (isExcluded(config.excludedCategoryPrefixes, record.categoryCode())) {
                    continue;
                }
                // 本地时间窗（接口不支持 time 过滤）
                if (since != null && published != null && published.isBefore(since)) {
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
                String region = RegionNames.byCityCode(record.cityCode());
                item.setRegion(region.isEmpty() ? RegionNames.guessFromTitle(record.title()) : region);
                items.add(item);
            }

            if (allBeforeWindow) {
                break;
            }
            if (result.records().size() < config.pageSize) {
                break;
            }
            if ((long) (page + 1) * config.pageSize >= result.totalCount()) {
                break;
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
