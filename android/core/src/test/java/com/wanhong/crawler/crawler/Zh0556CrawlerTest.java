package com.wanhong.crawler.crawler;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.http.CrawlException;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.support.FakeHttpTransport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 安庆交控集团集采平台采集器的离线用例。
 *
 * <p>HTML 骨架取自真实页面（列表项带方括号日期、导航里也有 li），
 * 覆盖正常解析、分页、跨类目去重、时间窗过滤与首页失败快速失败。
 */
class Zh0556CrawlerTest {

    private static final String BASE = "https://jc.zh0556.com";
    private static final String PAGE_1 = BASE + "/list/search?category_id=1&type_id=1&path=zb";
    private static final String PAGE_2 = PAGE_1 + "&page=2";
    private static final String PAGE_3 = PAGE_1 + "&page=3";

    private CrawlerSettings settings;
    private FakeHttpTransport transport;
    private HttpFetcher fetcher;
    private Zh0556Crawler crawler;

    @BeforeEach
    void setUp() {
        settings = new CrawlerSettings();
        settings.request.minIntervalMillis = 0;
        settings.request.warmUp = false;
        settings.request.retryTimes = 1;
        settings.request.retryBackoffMillis = 0;

        transport = new FakeHttpTransport();
        fetcher = new HttpFetcher(settings, transport, null);
        crawler = new Zh0556Crawler(settings, fetcher, null);

        // 默认只留一个「类目×类型」组合，避免无关请求干扰断言
        settings.sites.zh0556.categoryIds = new ArrayList<>(Arrays.asList(1));
        settings.sites.zh0556.typeIds = new ArrayList<>(Arrays.asList(1));
        settings.sites.zh0556.maxPagesPerCategory = 1;
    }

    @Test
    @DisplayName("正常解析：标题取列表 a 文本，日期去方括号，sourceId 来自 URL")
    void parsesListItems() {
        transport.routeOk(PAGE_1, listPage(
                item("/zbshow/886", "安庆市公共交通集团办公楼门前停车区域维修项目", "2026-10-29")));

        List<CrawlItem> items = crawler.crawl(context(null));

        assertEquals(1, items.size());
        CrawlItem item = items.get(0);
        assertEquals("安庆市公共交通集团办公楼门前停车区域维修项目", item.getTitle());
        assertEquals(BASE + "/zbshow/886", item.getUrl());
        assertEquals("886", item.getSourceId());
        assertEquals("招标采购", item.getCategory());
        assertEquals("1-1", item.getCategoryCode());
        assertNotNull(item.getPublishDate());
        assertEquals(LocalDateTime.of(2026, 10, 29, 0, 0), item.getPublishDate());
    }

    @Test
    @DisplayName("分页：第 2 页累加，第 3 页为空时停止")
    void followsPaginationUntilEmpty() {
        settings.sites.zh0556.maxPagesPerCategory = 3;
        transport.routeOk(PAGE_1, listPage(item("/zbshow/1", "第一页公告", "2026-11-03")));
        transport.routeOk(PAGE_2, listPage(item("/zbshow/2", "第二页公告", "2026-10-30")));
        transport.routeOk(PAGE_3, listPage());

        List<CrawlItem> items = crawler.crawl(context(null));

        assertEquals(2, items.size());
        assertEquals(3, transport.requestCount());
    }

    @Test
    @DisplayName("跨类目去重：同一 URL 只产出一次")
    void dedupesAcrossCategories() {
        settings.sites.zh0556.categoryIds = new ArrayList<>(Arrays.asList(1, 2));
        String sameItem = item("/zbshow/886", "同一个公告", "2026-11-03");
        transport.routeOk(PAGE_1, listPage(sameItem));
        transport.routeOk(BASE + "/list/search?category_id=2&type_id=1&path=zb", listPage(sameItem));

        List<CrawlItem> items = crawler.crawl(context(null));

        assertEquals(1, items.size());
        assertEquals("招标采购", items.get(0).getCategory());
    }

    @Test
    @DisplayName("时间窗口：窗口外的旧公告在解析阶段就被丢弃")
    void filtersOutItemsOutsideLookback() {
        String oldDate = LocalDateTime.now().minusDays(40).toLocalDate().toString();
        transport.routeOk(PAGE_1, listPage(
                item("/zbshow/1", "新公告", LocalDateTime.now().toLocalDate().toString()),
                item("/zbshow/2", "老公告", oldDate)));

        List<CrawlItem> items = crawler.crawl(context(30));

        assertEquals(1, items.size());
        assertEquals("新公告", items.get(0).getTitle());
    }

    @Test
    @DisplayName("首页失败：抛 CrawlException，不静默返回空列表")
    void failsFastWhenFirstPageFails() {
        transport.defaultStatus(500);

        CrawlException error = assertThrows(CrawlException.class, () -> crawler.crawl(context(null)));
        assertTrue(error.getMessage().contains("列表首页抓取失败"));
    }

    @Test
    @DisplayName("标题与状态：标题取 a 文本，不用站点名；报名状态不影响入库")
    void titleAndStatusHandling() {
        transport.routeOk(PAGE_1, listPage(
                item("/zbshow/892", "神灵潭路南侧硬质围挡及望湖揽胜临时配电房屋面美化建设工程", "2026-11-03")));

        List<CrawlItem> items = crawler.crawl(context(null));

        assertEquals(1, items.size());
        assertEquals("神灵潭路南侧硬质围挡及望湖揽胜临时配电房屋面美化建设工程", items.get(0).getTitle());
        assertTrue(!items.get(0).getTitle().contains("集采平台"));
    }

    // ------------------------------------------------------------------ 夹具

    private CrawlContext context(Integer lookbackDays) {
        List<SearchTerm> terms = new ArrayList<>();
        return new CrawlContext(terms, lookbackDays, null);
    }

    private String listPage(String... items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><title>安庆交控集团集采平台</title></head><body>");
        // 导航也是 li：解析必须限定在 div.projectlist 内
        sb.append("<div class=\"nav\"><ul><li><a href=\"/\">首页</a></li></ul></div>");
        sb.append("<div class=\"projectlist\"><ul>");
        for (String item : items) {
            sb.append(item);
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }

    private String item(String href, String title, String date) {
        return "<li><span class=\"status\">[报名已结束]</span>"
                + "<a href=\"" + href + "\">" + title + "</a>"
                + "<span class=\"time\">[" + date + "]</span></li>";
    }
}
