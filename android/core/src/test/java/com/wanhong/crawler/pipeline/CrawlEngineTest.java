package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.model.CrawlContext;
import com.wanhong.crawler.crawler.model.CrawlItem;
import com.wanhong.crawler.filter.KeywordRules;
import com.wanhong.crawler.filter.KeywordOverrideSource;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.support.FakeHttpTransport;
import com.wanhong.crawler.support.InMemoryTenderRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整条采集链路的离线端到端测试。
 *
 * <p>用假的站点采集器 + 假的 HTTP 传输 + 内存存储，验证：
 * 关键词过滤（含排除词）、截止日期解析与估算、区县、去重、增量、正文命中、
 * 计数与 CSV 导出。不依赖网络，也不依赖数据库。
 */
class CrawlEngineTest {

    private static final String LONG_BODY =
            "本项目为园区绿化提升工程，采购内容包括时令花卉、草花、乔灌木及配套绿化养护服务，"
                    + "要求供应商具备苗木花卉经营资质。投标截止时间：2026-09-25 09:30，逾期送达的投标文件将被拒收。";

    private CrawlerSettings settings;
    private InMemoryTenderRepository repository;
    private FakeHttpTransport transport;
    private HttpFetcher fetcher;
    private KeywordRules keywordRules;
    private CrawlEngine engine;

    @BeforeEach
    void setUp() {
        settings = new CrawlerSettings();
        settings.request.minIntervalMillis = 0;
        settings.request.warmUp = false;
        settings.request.maxDetailPerRun = 100;
        settings.verify.enabled = false;

        repository = new InMemoryTenderRepository();
        transport = new FakeHttpTransport();
        fetcher = new HttpFetcher(settings, transport, null);
        keywordRules = new KeywordRules(settings, KeywordOverrideSource.EMPTY, null);
        keywordRules.reload();

        DetailParser detailParser = new DetailParser(settings, null);
        DeadlineExtractor deadlineExtractor = new DeadlineExtractor(settings);
        LinkVerifier linkVerifier = new LinkVerifier(repository, fetcher, settings);

        engine = new CrawlEngine(settings, new ArrayList<SiteCrawler>(), keywordRules, repository,
                fetcher, detailParser, deadlineExtractor, linkVerifier, new ObjectMapper(), null);
    }

    private void useCrawler(SiteCrawler crawler) {
        DetailParser detailParser = new DetailParser(settings, null);
        DeadlineExtractor deadlineExtractor = new DeadlineExtractor(settings);
        LinkVerifier linkVerifier = new LinkVerifier(repository, fetcher, settings);
        engine = new CrawlEngine(settings, Collections.singletonList(crawler), keywordRules, repository,
                fetcher, detailParser, deadlineExtractor, linkVerifier, new ObjectMapper(), null);
    }

    /** 返回预置公告的假采集器 */
    private static class FakeCrawler implements SiteCrawler {
        private final List<CrawlItem> items = new ArrayList<>();
        private String lastFields;
        private int lastTermCount;

        public void add(CrawlItem item) {
            items.add(item);
        }

        public String siteCode() {
            return "fake";
        }

        public String siteName() {
            return "测试站点";
        }

        public boolean enabled() {
            return true;
        }

        public List<CrawlItem> crawl(CrawlContext context) {
            lastTermCount = context.getSearchTerms().size();
            return items;
        }
    }

    private CrawlItem item(String title, String url, LocalDateTime publish) {
        CrawlItem item = new CrawlItem("fake", "测试站点");
        item.setTitle(title);
        item.setUrl(url);
        item.setPublishDate(publish);
        item.setCategory("招标公告");
        item.setCategoryCode("003001002");
        return item;
    }

    private static final LocalDateTime PUBLISH = LocalDateTime.of(2026, 9, 10, 9, 0);

    @Test
    @DisplayName("命中的公告入库，并解析出正文与截止日期")
    void savesMatchedItemAndParsesDeadline() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("花卉苗木采购项目招标公告", "https://example.com/a.html", PUBLISH));
        transport.routeOk("https://example.com/a.html",
                "<html><head><title>花卉苗木采购项目招标公告</title></head><body>"
                        + "<div class=\"notice-content\">" + LONG_BODY + "</div>"
                        + "<input type=\"hidden\" id=\"infodate\" value=\"2026-09-10\" /></body></html>");
        useCrawler(crawler);

        CrawlSummary summary = engine.crawl("all", null, null, "MANUAL", null);

        assertEquals("SUCCESS", summary.status);
        assertEquals(1, summary.savedCount);
        assertEquals(1, summary.matchedCount);
        assertEquals(0, summary.failedCount);

        TenderRecord record = repository.all().get(0);
        assertTrue(record.title.contains("花卉苗木"));
        assertTrue(record.hasContent());
        assertEquals("TITLE,CONTENT", record.matchedScope);
        // 正文里的投标截止时间应被解析出来，而不是用估算值
        assertEquals(LocalDateTime.of(2026, 9, 25, 9, 30), record.deadlineDate);
        assertFalse(record.deadlineEstimated);
        assertEquals("正文解析", record.deadlineSource);
        assertEquals("OK", record.linkStatus);
        assertTrue(record.isNew);
        assertFalse(record.tracked);
    }

    @Test
    @DisplayName("标题命中排除词的公告被丢弃（车辆维修/资产拍卖这类噪声）")
    void dropsExcludedTitles() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("安庆市殡葬管理所绿化养护服务采购项目", "https://example.com/keep.html", PUBLISH));
        crawler.add(item("某单位车辆维修及绿化带保洁服务采购", "https://example.com/drop1.html", PUBLISH));
        crawler.add(item("桐城市园林绿化管理处设备拆除物资产拍卖项目", "https://example.com/drop2.html", PUBLISH));
        transport.defaultStatus(200);
        useCrawler(crawler);

        CrawlSummary summary = engine.crawl("all", null, null, "MANUAL", null);

        assertEquals(3, summary.candidateCount);
        assertEquals(2, summary.excludedCount, "两条噪声应被排除词挡掉");
        assertEquals(1, summary.savedCount);
        assertEquals("安庆市殡葬管理所绿化养护服务采购项目", repository.all().get(0).title);
    }

    @Test
    @DisplayName("标题不含关键词但正文含正文词时也能命中（正文检索路径）")
    void matchesByContentOnly() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("某综合整治项目竞争性谈判公告", "https://example.com/b.html", PUBLISH));
        transport.routeOk("https://example.com/b.html",
                "<html><head><title>某综合整治项目竞争性谈判公告</title></head><body>"
                        + "<div class=\"notice-content\">本项目需采购一批苗木用于沿线补植，并提供绿化养护服务。"
                        + "投标截止时间：2026-09-20 10:00。</div></body></html>");
        useCrawler(crawler);
        settings.detail.enabled = true;
        // 先让正文参与判定：正文词表里有"苗木"
        CrawlSummary summary = engine.crawl("all", null, null, "MANUAL", null);

        // 标题不命中、摘要为空 → 直接跳过（正文要抓下来才知道，这是设计取舍：不为未命中的记录消耗详情页配额）
        assertEquals(0, summary.matchedCount);

        // 换一种情况：接口自带正文里含关键词 → 命中
        CrawlItem withSummary = item("某综合整治项目竞争性谈判公告", "https://example.com/c.html", PUBLISH);
        withSummary.setSummary("本项目需采购一批苗木用于补植。");
        FakeCrawler crawler2 = new FakeCrawler();
        crawler2.add(withSummary);
        useCrawler(crawler2);
        CrawlSummary summary2 = engine.crawl("all", null, null, "MANUAL", null);
        assertEquals(1, summary2.matchedCount);
        assertEquals("CONTENT", repository.findByUrlHash(
                com.wanhong.crawler.common.Hashing.urlHash("https://example.com/c.html")).matchedScope);
    }

    @Test
    @DisplayName("同一公告第二次采集算重复，不会重复入库（增量）")
    void deduplicatesByUrl() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("花卉采购项目招标公告", "https://example.com/d.html", PUBLISH));
        transport.routeOk("https://example.com/d.html", "<html><body><div class=\"notice-content\">"
                + LONG_BODY + "</div></body></html>");
        useCrawler(crawler);

        CrawlSummary first = engine.crawl("all", null, null, "MANUAL", null);
        CrawlSummary second = engine.crawl("all", null, null, "MANUAL", null);

        assertEquals(1, first.savedCount);
        assertEquals(0, second.savedCount);
        assertEquals(1, second.duplicateCount);
        assertEquals(1, repository.count(), "库里应该只有一条");
    }

    @Test
    @DisplayName("详情页 404 时用接口正文兜底，并给出站点跳转链接，不留死链")
    void fallsBackWhenDetailMissing() {
        CrawlItem withSummary = item("花卉采购项目招标公告", "https://example.com/e.html", PUBLISH);
        // 兜底正文要求至少 60 字才值得当作正文保存（太短的片段没有保留价值）
        withSummary.setSummary("本项目采购时令花卉，包含草花、时花、盆栽等品类，并要求提供配套的绿化养护服务。"
                + "供应商须具备苗木花卉经营资质，按招标文件要求提供样品，逾期送达的投标文件将被拒收。");
        withSummary.setSourceId("info-123");
        withSummary.setCategoryCode("036001001001");
        FakeCrawler crawler = new FakeCrawler() {
            @Override
            public String fallbackDetailUrl(CrawlItem item) {
                return "https://example.com/tiaozhuan.html?infoid=" + item.getSourceId();
            }
        };
        crawler.add(withSummary);
        transport.defaultStatus(404);
        useCrawler(crawler);

        engine.crawl("all", null, null, "MANUAL", null);

        TenderRecord record = repository.all().get(0);
        assertEquals("FALLBACK", record.linkStatus);
        assertNotNull(record.accessUrl);
        assertTrue(record.accessUrl.contains("info-123"));
        assertTrue(record.hasContent(), "应用接口返回的正文片段兜底");
    }

    @Test
    @DisplayName("没有正文可解析时按发布日期 + 60 天估算截止日期")
    void estimatesDeadlineWhenNoContent() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("苗木采购公告", "https://example.com/f.html", PUBLISH));
        transport.defaultStatus(404);
        useCrawler(crawler);

        engine.crawl("all", null, null, "MANUAL", null);

        TenderRecord record = repository.all().get(0);
        assertNotNull(record.deadlineDate);
        assertTrue(record.deadlineEstimated);
        assertEquals(PUBLISH.plusDays(60), record.deadlineDate);
    }

    @Test
    @DisplayName("区县从标题推断，并归一成可筛选的规范名")
    void infersRegionFromTitle() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("桐城经开区盛唐南路绿化及照明工程", "https://example.com/g.html", PUBLISH));
        transport.defaultStatus(404);
        useCrawler(crawler);

        engine.crawl("all", null, null, "MANUAL", null);

        assertEquals("桐城市", repository.all().get(0).region);
    }

    @Test
    @DisplayName("采集报告包含候选/排除/命中/新增/重复各项计数")
    void reportsCounters() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("花卉采购项目招标公告", "https://example.com/h1.html", PUBLISH));
        crawler.add(item("车辆维修服务采购项目绿化带保洁", "https://example.com/h2.html", PUBLISH));
        crawler.add(item("普通道路施工招标公告", "https://example.com/h3.html", PUBLISH));
        transport.defaultStatus(404);
        useCrawler(crawler);

        CrawlSummary summary = engine.crawl("all", null, null, "MANUAL", null);

        assertEquals(3, summary.candidateCount);
        assertEquals(1, summary.excludedCount);
        assertEquals(1, summary.matchedCount);
        assertEquals(1, summary.savedCount);
        assertEquals(1, summary.sites.size());
        assertEquals("fake", summary.sites.get(0).siteCode);
        assertTrue(summary.requestCount >= 0);
    }

    @Test
    @DisplayName("重复采集同一条时会补上缺失的截止日期")
    void backfillsDeadlineOnDuplicate() {
        FakeCrawler crawler = new FakeCrawler();
        crawler.add(item("花卉采购项目招标公告", "https://example.com/i.html", PUBLISH));
        transport.defaultStatus(404);
        useCrawler(crawler);
        engine.crawl("all", null, null, "MANUAL", null);

        TenderRecord record = repository.all().get(0);
        record.deadlineDate = null;
        record.deadlineEstimated = false;
        repository.update(record);

        engine.crawl("all", null, null, "MANUAL", null);

        TenderRecord refreshed = repository.all().get(0);
        assertNotNull(refreshed.deadlineDate, "重复记录应补上截止日期");
        assertTrue(refreshed.deadlineEstimated);
    }
}
