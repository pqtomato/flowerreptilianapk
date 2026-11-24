package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.http.CrawlException;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安庆交控集团集采平台采集器（https://jc.zh0556.com/）。
 *
 * <p>与另外三个站点不同：这里没有检索接口，也没有关键词参数，只能按
 * 「类目 × 项目类型」翻静态列表页发现公告。列表项结构：
 * <pre>
 *   &lt;li&gt;
 *     &lt;span class="status"&gt;[报名已结束]&lt;/span&gt;
 *     &lt;a href="/zbshow/886"&gt;标题&lt;/a&gt;
 *     &lt;span class="time"&gt;[2026-10-29]&lt;/span&gt;
 *   &lt;/li&gt;
 * </pre>
 *
 * <p>几个必须注意的点：
 * <ul>
 *   <li>列表页里的导航也是 {@code <li>}，所以只能限定在 {@code div.projectlist} 里选；</li>
 *   <li>{@code [报名已结束]} 是站点自己算的，不写进截止日期，避免把"状态"当"日期"；</li>
 *   <li>详情页 {@code <title>} 永远是站点名，公告标题只能取列表 {@code <a>} 的文本；</li>
 *   <li>面包屑会把比选公告写成「中标公示」，文档类型由 {@code CrawlEngine} 按标题判定。</li>
 * </ul>
 */
public class Zh0556Crawler implements SiteCrawler {

    public static final String SITE_CODE = "zh0556";

    /** 类目 1 / 2 的中文名 */
    private static final String CATEGORY_BID = "招标采购";
    private static final String CATEGORY_NON_BID = "非招采购";

    /** 从 /zbshow/886、/cjshow/123 这类地址里取数字 ID */
    private static final Pattern SOURCE_ID = Pattern.compile("/(?:[a-z]+)?show/(\\d+)");

    public interface Logger {
        void warn(String message);

        Logger SILENT = new Logger() {
            public void warn(String message) {
            }
        };
    }

    private final CrawlerSettings settings;
    private final HttpFetcher httpFetcher;
    private final Logger logger;

    public Zh0556Crawler(CrawlerSettings settings, HttpFetcher httpFetcher, Logger logger) {
        this.settings = settings;
        this.httpFetcher = httpFetcher;
        this.logger = logger == null ? Logger.SILENT : logger;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return settings.sites.zh0556.name;
    }

    @Override
    public boolean enabled() {
        return settings.sites.zh0556.enabled;
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerSettings.Zh0556 config = settings.sites.zh0556;
        String homeUrl = config.baseUrl + "/";

        int lookback = context.resolveLookbackDays(config.lookbackDays);
        LocalDateTime cutoff = lookback > 0 ? LocalDateTime.now().minusDays(lookback) : null;

        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        boolean firstRequest = true;

        for (Integer categoryId : config.categoryIds) {
            if (categoryId == null) {
                continue;
            }
            for (Integer typeId : config.typeIds) {
                if (typeId == null) {
                    continue;
                }
                for (int page = 1; page <= config.maxPagesPerCategory; page++) {
                    String pageUrl = buildListUrl(config, categoryId, typeId, page);
                    // 注意：HttpFetcher 对 4xx/5xx 不抛异常，只返回响应，必须自己判状态；
                    // 否则站点挂掉时会静默返回 0 条，看起来像"今天没有新公告"。
                    byte[] body = null;
                    String failure = null;
                    try {
                        FetchResponse response = httpFetcher.get(pageUrl, homeUrl);
                        if (response.isSuccess()) {
                            body = response.body();
                        } else {
                            failure = "HTTP " + response.status();
                        }
                    } catch (RuntimeException e) {
                        failure = e.getMessage();
                    }
                    if (failure != null) {
                        if (firstRequest) {
                            // 第一条列表请求就失败 → 整站失败，避免静默返回 0 条
                            throw new CrawlException("列表首页抓取失败：" + failure);
                        }
                        logger.warn("[" + siteName() + "] 列表页抓取失败：" + pageUrl + "（" + failure + "）");
                        context.log("列表页抓取失败（类目 " + categoryId + " / 类型 " + typeId
                                + " 第 " + page + " 页）：" + failure);
                        break;
                    }
                    firstRequest = false;

                    List<CrawlItem> pageItems = parseListPage(body, pageUrl, categoryId, typeId,
                            cutoff, seenUrls);
                    items.addAll(pageItems);
                    if (pageItems.isEmpty()) {
                        // 空页（或整页都在时间窗之外）→ 该组合不再往后翻
                        break;
                    }
                }
            }
        }
        return items;
    }

    private String buildListUrl(CrawlerSettings.Zh0556 config, int categoryId, int typeId, int page) {
        StringBuilder sb = new StringBuilder(config.baseUrl);
        sb.append(config.listPath)
                .append("?category_id=").append(categoryId)
                .append("&type_id=").append(typeId)
                .append("&path=zb");
        if (page > 1) {
            sb.append("&page=").append(page);
        }
        return sb.toString();
    }

    private List<CrawlItem> parseListPage(byte[] body, String pageUrl, int categoryId, int typeId,
                                          LocalDateTime cutoff, Set<String> seenUrls) {
        List<CrawlItem> items = new ArrayList<>();
        if (body == null || body.length == 0) {
            return items;
        }
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(body), null, pageUrl);
        } catch (Exception e) {
            logger.warn("[" + siteName() + "] 列表页解析失败：" + pageUrl + "（" + e.getMessage() + "）");
            return items;
        }

        // 导航栏也是 <li>，必须限定在项目列表容器里
        Elements rows = doc.select("div.projectlist ul li");
        if (rows.isEmpty()) {
            rows = doc.select("div.projectlist li");
        }

        for (Element row : rows) {
            Element link = row.selectFirst("a[href]");
            if (link == null) {
                continue;
            }
            String title = normalizeText(link.text());
            if (title.isEmpty()) {
                continue;
            }

            String url = link.absUrl("href");
            if (url == null || url.trim().isEmpty()) {
                url = link.attr("href");
            }
            if (url == null || url.trim().isEmpty() || url.startsWith("javascript")) {
                continue;
            }
            if (!seenUrls.add(url)) {
                continue;
            }

            Element timeElement = row.selectFirst("span.time");
            LocalDateTime publishDate = timeElement == null ? null
                    : DateUtils.parse(stripBrackets(normalizeText(timeElement.text())));

            if (cutoff != null && publishDate != null && publishDate.isBefore(cutoff)) {
                continue;
            }

            CrawlItem item = new CrawlItem(SITE_CODE, siteName());
            item.setTitle(title);
            item.setUrl(url);
            item.setCategory(categoryId == 2 ? CATEGORY_NON_BID : CATEGORY_BID);
            item.setCategoryCode(categoryId + "-" + typeId);
            item.setPublishDate(publishDate);
            item.setSourceId(extractSourceId(url));
            // 该站没有地区字段，只能从标题推断
            item.setRegion(RegionNames.guessFromTitle(title));
            items.add(item);
        }
        return items;
    }

    /** 日期在列表里带方括号：[2026-10-29] */
    private String stripBrackets(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("[", "").replace("]", "").replace("【", "").replace("】", "").trim();
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace(' ', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String extractSourceId(String url) {
        Matcher matcher = SOURCE_ID.matcher(url);
        return matcher.find() ? matcher.group(1) : null;
    }
}
