package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.common.RegionNames;
import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.http.CrawlException;
import com.wanhong.crawler.http.HttpFetcher;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 安庆市城市管理局「通知公告」采集器（https://cgj.anqing.gov.cn/xxfb/tzgg/）。
 *
 * <p>该站列表是服务端渲染的静态 HTML，直接解析即可：
 * <pre>
 *   &lt;ul class="doc_list list-14015578"&gt;
 *     &lt;li&gt;&lt;a href="..." title="标题"&gt;&lt;span&gt;标题&lt;/span&gt;&lt;/a&gt;
 *         &lt;span class="right date"&gt;2026-09-17&lt;/span&gt;&lt;/li&gt;
 * </pre>
 *
 * <p>翻页方式由站点脚本给出：{@code /content/column/14015578?pageIndex=N}，页码从 1 开始。
 *
 * <p>列表里偶尔会直接外链到公共资源交易网 / 阳光采购平台的公告，这些同样是有效数据，
 * 保持原链接入库。
 */
@Component
public class CgjCrawler implements SiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(CgjCrawler.class);

    public static final String SITE_CODE = "cgj";

    private static final String CATEGORY_NAME = "通知公告";

    private final CrawlerProperties properties;
    private final HttpFetcher httpFetcher;

    public CgjCrawler(CrawlerProperties properties, HttpFetcher httpFetcher) {
        this.properties = properties;
        this.httpFetcher = httpFetcher;
    }

    @Override
    public String siteCode() {
        return SITE_CODE;
    }

    @Override
    public String siteName() {
        return properties.getSites().getCgj().getName();
    }

    @Override
    public boolean enabled() {
        return properties.getSites().getCgj().isEnabled();
    }

    @Override
    public List<CrawlItem> crawl(CrawlContext context) {
        CrawlerProperties.Cgj config = properties.getSites().getCgj();
        String baseUrl = config.getBaseUrl();
        String listUrl = baseUrl + config.getListUrl();

        List<CrawlItem> items = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();

        for (int page = 1; page <= config.getMaxPages(); page++) {
            String pageUrl = page == 1
                    ? listUrl
                    : baseUrl + config.getColumnUrl() + "?pageIndex=" + page;

            byte[] body;
            try {
                body = httpFetcher.getBytes(pageUrl, listUrl);
            } catch (Exception e) {
                log.warn("[{}] 列表页第 {} 页抓取失败：{}", siteName(), page, e.toString());
                context.log("列表页第 " + page + " 页抓取失败：" + e.getMessage());
                if (page == 1 && items.isEmpty()) {
                    // 第一页就拿不到数据 → 整站失败（快速失败，避免静默返回 0 条）
                    throw new CrawlException("列表首页抓取失败：" + e.getMessage(), e);
                }
                // 翻页失败不影响已拿到的数据
                break;
            }

            List<CrawlItem> pageItems = parseListPage(body, pageUrl, config.getName(), seenUrls);
            items.addAll(pageItems);
            log.debug("[{}] 列表页第 {} 页解析出 {} 条", siteName(), page, pageItems.size());
            if (pageItems.isEmpty()) {
                break;
            }
        }
        log.info("[{}] 列表解析完成，候选公告 {} 条", siteName(), items.size());
        return items;
    }

    private List<CrawlItem> parseListPage(byte[] body, String pageUrl, String siteName, Set<String> seenUrls) {
        List<CrawlItem> items = new ArrayList<>();
        if (body == null || body.length == 0) {
            return items;
        }
        Document doc;
        try {
            // Jsoup.parse(InputStream, null, baseUri) 会按 meta 声明自动识别字符集
            doc = Jsoup.parse(new ByteArrayInputStream(body), null, pageUrl);
        } catch (Exception e) {
            log.warn("[{}] 列表页解析失败：{}（{}）", siteName(), pageUrl, e.getMessage());
            return items;
        }
        Elements rows = doc.select("ul.doc_list li");
        if (rows.isEmpty()) {
            // 兜底：某些模板不带 doc_list 类名
            rows = doc.select("div.ls-page-container li");
        }

        for (Element row : rows) {
            Element link = row.selectFirst("a[href]");
            if (link == null) {
                continue;
            }
            String title = link.hasAttr("title") && !link.attr("title").isBlank()
                    ? link.attr("title")
                    : link.text();
            title = title == null ? "" : title.replace('\u00a0', ' ').trim();
            if (title.isEmpty()) {
                continue;
            }

            String url = link.absUrl("href");
            if (url == null || url.isBlank()) {
                url = link.attr("href");
            }
            if (url == null || url.isBlank() || url.startsWith("javascript")) {
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
            // 该站没有地区字段，只能从标题推断（沿用上一版系统的做法）
            item.setRegion(RegionNames.guessFromTitle(title));
            items.add(item);
        }
        return items;
    }
}
