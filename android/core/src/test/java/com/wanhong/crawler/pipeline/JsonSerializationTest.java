package com.wanhong.crawler.pipeline;

import com.fasterxml.jackson.databind.JsonNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口 JSON 序列化的回归测试。
 *
 * <p>背景：安卓上 {@code new ObjectMapper()} 不带 JavaTimeModule，
 * 序列化 {@code LocalDateTime} 会直接抛
 * "Java 8 date/time type `java.time.LocalDateTime` not supported by default"，
 * 导致公告列表、采集日志等接口全部返回 500（真机截图里就是这个错）。
 * 旧版跑在 Spring Boot 上时 Spring 自动注册了该模块，所以一直没暴露。
 *
 * <p>这些用例保证：只要走 {@link Json} 工厂，日期就能正常序列化成 ISO 字符串。
 */
class JsonSerializationTest {

    private TenderRecord sampleRecord() {
        TenderRecord record = new TenderRecord();
        record.id = 1L;
        record.siteCode = "aqggzy";
        record.siteName = "安庆市公共资源交易服务网";
        record.title = "花卉苗木采购项目招标公告";
        record.url = "https://example.com/a.html";
        record.category = "招标公告";
        record.region = "怀宁县";
        record.publishDate = LocalDateTime.of(2026, 9, 20, 16, 9, 11);
        record.deadlineDate = LocalDateTime.of(2026, 11, 8, 17, 26, 39);
        record.deadlineEstimated = true;
        record.matchedKeywords = "花卉,苗木";
        record.matchedScope = "TITLE";
        record.crawlTime = LocalDateTime.of(2026, 9, 20, 16, 10, 0);
        record.content = "正文内容";
        record.isNew = true;
        return record;
    }

    @Test
    @DisplayName("公告列表项：时间字段能序列化成 ISO 字符串（真机报错的接口）")
    void serializesTenderListItem() throws Exception {
        Map<String, Object> item = TenderQueryService.toListItem(sampleRecord());

        String json = Json.mapper().writeValueAsString(item);
        JsonNode node = Json.mapper().readTree(json);

        assertEquals("2026-09-20T16:09:11", node.get("publishDate").asText());
        assertEquals("2026-11-08T17:26:39", node.get("deadlineDate").asText());
        assertEquals("2026-09-20T16:10:00", node.get("crawlTime").asText());
        // 页面依赖的派生字段
        assertTrue(node.has("daysLeft"));
        assertTrue(node.has("expired"));
        assertTrue(node.has("isNew"));
        assertTrue(node.has("matchedKeywords"));
        assertEquals("花卉苗木采购项目招标公告", node.get("title").asText());
    }

    @Test
    @DisplayName("公告详情：正文与附件字段齐全且可序列化")
    void serializesTenderDetail() throws Exception {
        TenderRecord record = sampleRecord();
        record.attachments = "[\"公告.pdf :: https://example.com/a.pdf\"]";
        record.deadlineSource = "发布日期+60天估算";

        String json = Json.mapper().writeValueAsString(TenderQueryService.toDetail(record));
        JsonNode node = Json.mapper().readTree(json);

        assertEquals("正文内容", node.get("content").asText());
        assertEquals("发布日期+60天估算", node.get("deadlineSource").asText());
        assertTrue(node.get("attachments").asText().contains("公告.pdf"));
    }

    @Test
    @DisplayName("采集日志：startTime/endTime 能序列化（真机截图里报错的就是这个字段）")
    void serializesCrawlLog() throws Exception {
        CrawlLogRecord log = new CrawlLogRecord();
        log.id = 7L;
        log.siteCode = "cgj";
        log.status = "SUCCESS";
        log.triggerType = "SCHEDULED";
        log.startTime = LocalDateTime.of(2026, 9, 20, 8, 0, 0);
        log.endTime = LocalDateTime.of(2026, 9, 20, 8, 2, 30);
        log.durationMs = 150000;
        log.savedCount = 3;
        log.verifiedCount = 20;
        log.detail = "列表页第 2 页抓取失败：超时";

        String json = Json.mapper().writeValueAsString(
                TenderQueryService.toLogItem(log, "安庆市城市管理局-通知公告", true));
        JsonNode node = Json.mapper().readTree(json);

        assertEquals("2026-09-20T08:00:00", node.get("startTime").asText(),
                "旧版这里会抛 LocalDateTime not supported");
        assertEquals("2026-09-20T08:02:30", node.get("endTime").asText());
        assertEquals("安庆市城市管理局-通知公告", node.get("siteName").asText());
        assertEquals(3, node.get("savedCount").asInt());
        assertEquals("列表页第 2 页抓取失败：超时", node.get("detail").asText());
    }

    @Test
    @DisplayName("日志列表视图不带 detail，避免列表接口传输过大")
    void logListItemOmitsDetail() throws Exception {
        CrawlLogRecord log = new CrawlLogRecord();
        log.id = 8L;
        log.siteCode = "cgj";
        log.status = "SUCCESS";
        log.startTime = LocalDateTime.of(2026, 9, 20, 8, 0, 0);
        log.detail = "很长的逐条明细";

        JsonNode node = Json.mapper().readTree(Json.mapper().writeValueAsString(
                TenderQueryService.toLogItem(log, "城管局", false)));
        assertFalse(node.has("detail"));
        assertEquals("2026-09-20T08:00:00", node.get("startTime").asText());
    }

    @Test
    @DisplayName("采集汇总：可以正常序列化（含 null 的情况）")
    void serializesCrawlSummary() throws Exception {
        CrawlSummary summary = CrawlSummary.of("SUCCESS", "采集完成，新增 5 条");
        summary.savedCount = 5;
        summary.requestCount = 42;

        JsonNode node = Json.mapper().readTree(
                Json.mapper().writeValueAsString(TenderQueryService.toSummaryItem(summary)));
        assertEquals("SUCCESS", node.get("status").asText());
        assertEquals(5, node.get("savedCount").asInt());

        // null 不能抛异常
        assertTrue(Json.mapper().writeValueAsString(TenderQueryService.toSummaryItem(null)).length() > 0);
    }

    @Test
    @DisplayName("每次 create() 都带 java.time 支持（防止有人改回 new ObjectMapper()）")
    void factoryAlwaysSupportsJavaTime() throws Exception {
        String json = Json.create().writeValueAsString(
                java.util.Collections.singletonMap("t", LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
        assertTrue(json.contains("2026-01-02T03:04:05"), "日期应写成 ISO 字符串：" + json);
    }

    @Test
    @DisplayName("解析 epoint 接口返回的 JSON 不受影响")
    void stillParsesEpointJson() throws Exception {
        String payload = "{\"result\":{\"totalcount\":1,\"records\":[{\"title\":\"花卉采购\","
                + "\"webdate\":\"2026-09-08 10:00:00\",\"infoid\":\"id-1\"}]}}";
        JsonNode root = Json.mapper().readTree(payload);
        assertEquals(1, root.path("result").path("totalcount").asInt());
        assertEquals("花卉采购", root.path("result").path("records").get(0).path("title").asText());
    }
}
