package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.common.NoticeType;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.AqggzyCrawler;
import com.wanhong.crawler.crawler.CgjCrawler;
import com.wanhong.crawler.crawler.DeadlineExtractor;
import com.wanhong.crawler.crawler.DetailParser;
import com.wanhong.crawler.crawler.SiteCrawler;
import com.wanhong.crawler.crawler.WyxmzxCrawler;
import com.wanhong.crawler.crawler.Zh0556Crawler;
import com.wanhong.crawler.crawler.epoint.EpointSearchClient;
import com.wanhong.crawler.filter.KeywordOverrideSource;
import com.wanhong.crawler.filter.KeywordRules;
import com.wanhong.crawler.http.HttpFetcher;
import com.wanhong.crawler.support.InMemoryTenderRepository;
import com.wanhong.crawler.support.JdkHttpTransport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 打真实站点的联调测试（默认跳过，需 -Dlive=true）。
 *
 * <p>价值：安卓版用的是同一份内核（只是把网络层换成 OkHttp），所以只要这个测试通过，
 * 就说明"移植到安卓后采集逻辑本身没走样"。它需要联网，因此默认不跑。
 *
 * <p>执行方式：{@code gradle :core:test --tests '*LiveCrawlIT' -Dlive=true}
 */
@EnabledIfSystemProperty(named = "live", matches = "true")
class LiveCrawlIT {

    @Test
    @DisplayName("真实站点联调：四个站点都能采到数据，且过滤/截止日期逻辑生效")
    void crawlsRealSites() {
        CrawlerSettings settings = new CrawlerSettings();
        settings.request.minIntervalMillis = 700;
        // 详情配额要够覆盖本次命中的全部条目，否则后入库的记录会没有正文
        settings.request.maxDetailPerRun = 80;
        settings.sites.aqggzy.maxPagesPerKeyword = 1;
        settings.sites.wyxmzx.maxPagesPerKeyword = 1;
        settings.sites.cgj.maxPages = 1;
        settings.sites.zh0556.maxPagesPerCategory = 1;
        settings.verify.enabled = false;
        // 只跑少量关键词，够证明链路通即可
        settings.sites.aqggzy.lookbackDays = 30;
        settings.sites.wyxmzx.lookbackDays = 365;

        InMemoryTenderRepository repository = new InMemoryTenderRepository();
        HttpFetcher fetcher = new HttpFetcher(settings, new JdkHttpTransport(settings), null);
        KeywordRules rules = new KeywordRules(settings, KeywordOverrideSource.EMPTY, null);
        rules.reload();

        EpointSearchClient searchClient = new EpointSearchClient(fetcher, new ObjectMapper(), null);
        DetailParser detailParser = new DetailParser(settings, null);
        DeadlineExtractor deadlineExtractor = new DeadlineExtractor(settings);
        LinkVerifier verifier = new LinkVerifier(repository, fetcher, settings);

        List<SiteCrawler> crawlers = new ArrayList<>();
        crawlers.add(new AqggzyCrawler(settings, searchClient));
        crawlers.add(new CgjCrawler(settings, fetcher, null));
        crawlers.add(new WyxmzxCrawler(settings, searchClient));
        crawlers.add(new Zh0556Crawler(settings, fetcher, null));

        CrawlEngine engine = new CrawlEngine(settings, crawlers, rules, repository, fetcher,
                detailParser, deadlineExtractor, verifier, new ObjectMapper(), null);

        // 取前 15 个检索词（按长度倒序，即最长的那些），控制在 1~2 分钟内跑完
        CrawlSummary summary = engine.crawl("all", 15, null, "MANUAL", null);

        StringBuilder report = new StringBuilder();
        report.append("=== 真实站点采集结果 ===\n");
        report.append("状态: ").append(summary.status).append(" / ").append(summary.message).append('\n');
        report.append("候选 ").append(summary.candidateCount)
                .append("，排除 ").append(summary.excludedCount)
                .append("，命中 ").append(summary.matchedCount)
                .append("，新增 ").append(summary.savedCount)
                .append("，失败 ").append(summary.failedCount)
                .append("，请求 ").append(summary.requestCount)
                .append("，耗时 ").append(summary.durationMs).append("ms\n");
        for (CrawlSummary.SiteSummary site : summary.sites) {
            report.append("  - ").append(site.siteName).append("：候选 ").append(site.candidateCount)
                    .append("，排除 ").append(site.excludedCount)
                    .append("，命中 ").append(site.matchedCount)
                    .append("，新增 ").append(site.savedCount)
                    .append("，失败 ").append(site.failedCount)
                    .append(site.errorMessage == null ? "" : "，错误：" + site.errorMessage)
                    .append('\n');
        }
        for (TenderRecord record : repository.all()) {
            report.append("    * [").append(record.siteCode).append("] ").append(record.title)
                    .append("\n      截止 ").append(record.deadlineDate == null ? "未知" : record.deadlineDate)
                    .append(" | 区县 ").append(record.region)
                    .append(" | 分类 ").append(record.category)
                    .append(" | 命中 ").append(record.matchedKeywords)
                    .append(" | 正文 ").append(record.hasContent() ? record.content.length() + "字" : "无")
                    .append(" | 链接 ").append(record.linkStatus)
                    .append('\n');
        }
        writeReport(report.toString());
        System.out.println(report);

        assertTrue(summary.savedCount > 0, "真实站点应该能采到数据");
        assertEquals(0, summary.failedCount, "不应该有处理失败");

        int withContent = 0;
        int withoutDeadline = 0;
        for (TenderRecord record : repository.all()) {
            if (record.hasContent()) {
                withContent++;
            }
            // 详情抓取成功的记录必须有正文（配额用完的记录会是 UNKNOWN，属预期）
            if ("OK".equals(record.linkStatus)) {
                assertTrue(record.hasContent(), "详情页抓取成功的记录应该有正文：" + record.title);
            }
            // 估算已停用：截止日期要么来自正文解析，要么就是未知，不能出现估算值
            assertFalse(record.deadlineEstimated,
                    "不应再出现估算的截止日期：" + record.title);
            if (NoticeType.isBiddable(record.noticeType) && record.deadlineDate == null) {
                // 正文里确实可能没写截止时间，只统计不判失败
                withoutDeadline++;
            }
        }
        assertTrue(withContent > 0, "至少应该有记录抓到正文");
        System.out.println("正文未写明截止日期的可投公告：" + withoutDeadline + " 条（预期，不再估算）");
    }

    /** 结果同时落盘，便于在 CI/终端里直接查看（Gradle 默认不显示测试的 stdout） */
    private static void writeReport(String content) {
        try {
            java.nio.file.Path path = java.nio.file.Paths.get("build", "live-crawl-report.txt");
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 报告写不进去不影响测试结论
        }
    }
}
