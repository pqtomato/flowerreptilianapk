package com.wanhong.crawler.crawler;

import com.wanhong.crawler.config.CrawlerSettings;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 截止日期解析：手机端「还能不能投」的核心字段。 */
class DeadlineExtractorTest {

    private static final LocalDateTime PUBLISH = LocalDateTime.of(2026, 9, 10, 9, 0);

    private DeadlineExtractor extractor;

    @BeforeEach
    void setUp() {
        CrawlerSettings settings = new CrawlerSettings();
        settings.deadline.defaultDays = 60;
        settings.deadline.maxDays = 400;
        extractor = new DeadlineExtractor(settings);
    }

    @Test
    @DisplayName("投标截止时间：完整日期时间")
    void parsesBidDeadline() {
        DeadlineExtractor.Deadline deadline = extractor.resolve(
                "一、项目基本情况。投标截止时间：2026-09-20 09:30，逾期送达的投标文件将被拒收。", PUBLISH);
        assertEquals(LocalDateTime.of(2026, 9, 20, 9, 30), deadline.date());
        assertFalse(deadline.estimated());
    }

    @Test
    @DisplayName("响应文件提交截止时间：中文日期 + 时分")
    void parsesChineseDateDeadline() {
        DeadlineExtractor.Deadline deadline = extractor.resolve(
                "响应文件提交截止时间：2026年09月25日 14时30分，地点：市城管局三楼。", PUBLISH);
        assertEquals(LocalDateTime.of(2026, 9, 25, 14, 30), deadline.date());
    }

    @Test
    @DisplayName("报名时间区间取区间结尾")
    void takesEndOfDateRange() {
        DeadlineExtractor.Deadline deadline = extractor.resolve(
                "报名时间：2026年9月11日至2026年9月18日，逾期不再受理。", PUBLISH);
        assertEquals(LocalDateTime.of(2026, 9, 18, 0, 0), deadline.date());
    }

    @Test
    @DisplayName("没有投标截止时退回开标时间")
    void fallsBackToBidOpeningTime() {
        DeadlineExtractor.Deadline deadline = extractor.resolve(
                "开标时间：2026-09-22 10:00，开标地点：安庆市公共资源交易中心。", PUBLISH);
        assertEquals(LocalDateTime.of(2026, 9, 22, 10, 0), deadline.date());
    }

    @Test
    @DisplayName("优先取投标截止，不能被后一句的开标时间顶掉（踩过的坑）")
    void prefersBidDeadlineOverOpening() {
        DeadlineExtractor.Deadline deadline = extractor.resolve(
                "投标截止时间：2026-09-20 09:30。开标时间：2026-09-21 09:30。", PUBLISH);
        assertEquals(LocalDateTime.of(2026, 9, 20, 9, 30), deadline.date());
    }

    @Test
    @DisplayName("解析不到时按发布日期 + N 天估算，并标记为估算值")
    void estimatesWhenNotFound() {
        DeadlineExtractor.Deadline deadline = extractor.resolve("本项目采用综合评分法，详见招标文件。", PUBLISH);
        assertEquals(PUBLISH.plusDays(60), deadline.date());
        assertTrue(deadline.estimated());
        assertNotNull(deadline.source());
    }

    @Test
    @DisplayName("正文为空 / 无发布日期时不会抛异常")
    void handlesMissingInput() {
        assertTrue(extractor.resolve(null, PUBLISH).estimated());
        assertTrue(extractor.resolve("   ", PUBLISH).estimated());
        assertNull(extractor.resolve(null, null).date());
    }

    @Test
    @DisplayName("早于发布日期的解析结果视为误判，改用估算")
    void rejectsDateBeforePublish() {
        DeadlineExtractor.Deadline deadline = extractor.resolve("投标截止时间：2025-01-01 09:00。", PUBLISH);
        assertTrue(deadline.estimated());
        assertEquals(PUBLISH.plusDays(60), deadline.date());
    }

    @Test
    @DisplayName("远超合理范围的日期视为误判")
    void rejectsTooFarFutureDate() {
        assertTrue(extractor.resolve("投标截止时间：2030-01-01 09:00。", PUBLISH).estimated());
    }
}
