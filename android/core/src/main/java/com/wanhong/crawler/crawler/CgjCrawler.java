package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.http.CrawlException;
import com.wanhong.crawler.http.HttpFetcher;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市城市管理局「通知公告」采集器（https://cgj.anqing.gov.cn/xxfb/tzgg/）。
 *
 * <p>列表是服务端渲染的静态 HTML，直接解析 {@code ul.doc_list li}：
 * <pre>
 *   &lt;ul class="doc_list list-14015578"&gt;
 *     &lt;li&gt;&lt;a href="..." title="标题"&gt;&lt;span&gt;标题&lt;/span&gt;&lt;/a&gt;
 *         &lt;span class="right date"&gt;2026-09-17&lt;/span&gt;&lt;/li&gt;
 * </pre>
 * 翻页方式由站点脚本给出：{@code /content/column/14015578?pageIndex=N}，页码从 1 开始。
 */
public class CgjCrawler implements SiteCrawler {

    public static final String SITE_CODE = "cgj";

    private static final String CATEGORY_NAME = "通知公告";

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

    public CgjCrawler(CrawlerSettings settings, HttpFetcher httpFetcher, Logger logger) {
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
        return settings.sites.cgj.name;
    }

    @Override
    public boolean enabled() {
        return settings.sites.cgj.enabled;
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerSettings.Cgj config = settings.sites.cgj;
        String baseUrl = config.baseUrl;
        String listUrl = baseUrl + config.listUrl;

        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (int page = 1; page <= config.maxPages; page++) {
            String pageUrl = page == 1 ? listUrl
                    : baseUrl + config.columnUrl + "?pageIndex=" + page;

            byte[] body;
            try {
                body = httpFetcher.getBytes(pageUrl, listUrl);
            } catch (Exception e) {
                logger.warn("[" + siteName() + "] 列表页第 " + page + " 页抓取失败：" + e);
                context.log("列表页第 " + page + " 页抓取失败：" + e.getMessage());
                if (page == 1 && items.isEmpty()) {
                    // 首页就拿不到数据 → 整站失败（快速失败，避免静默返回 0 条）
                    throw new CrawlException("列表首页抓取失败：" + e.getMessage(), e);
                }
                break;
            }

            List<CrawlItem> pageItems = parseListPage(body, pageUrl, config.name, seenUrls);
            items.addAll(pageItems);
            if (pageItems.isEmpty()) {
                break;
            }
        }
        return items;
    }

    private List<CrawlItem> parseListPage(byte[] body, String pageUrl, String siteName, Set<String> seenUrls) {
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
        Elements rows = doc.select("ul.doc_list li");
        if (rows.isEmpty()) {
            rows = doc.select("div.ls-page-container li");
        }

        for (Element row : rows) {
            Element link = row.selectFirst("a[href]");
            if (link == null) {
                continue;
            }
            String title = link.hasAttr("title") && !link.attr("title").trim().isEmpty()
                    ? link.attr("title") : link.text();
            title = title == null ? "" : title.replace('\u00a0', ' ').trim();
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

            Element dateElement = row.selectFirst("span.right.date");
            if (dateElement == null) {
                dateElement = row.selectFirst("span.date");
            }

            CrawlItem item = new CrawlItem(SITE_CODE, siteName);
            item.setTitle(title);
            item.setUrl(url);
            item.setCategory(CATEGORY_NAME);
            item.setCategoryCode("");
            item.setPublishDate(dateElement == null ? DateUtils.parseFromText(row.text())
                    : DateUtils.parse(dateElement.text().trim()));
            // 该站没有地区字段，只能从标题推断
            item.setRegion(RegionNames.guessFromTitle(title));
            items.add(item);
        }
        return items;
    }
}
