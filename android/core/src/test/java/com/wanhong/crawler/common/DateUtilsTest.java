package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日期解析与去重摘要：两个踩过坑的地方。 */
class DateUtilsTest {

    @Test
    @DisplayName("带时间的格式不能被解析成纯日期（时分被静默丢弃是踩过的坑）")
    void keepsTimeComponent() {
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31), DateUtils.parse("2026-09-17 16:31"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31, 5), DateUtils.parse("2026-09-17 16:31:05"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31), DateUtils.parse("2026/09/17 16:31"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31), DateUtils.parse("2026.09.17 16:31"));
    }

    @Test
    @DisplayName("纯日期与紧凑格式")
    void parsesDateOnlyFormats() {
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("2026-09-17"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("20260917"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 12, 0, 0), DateUtils.parse("20260917120000"));
    }

    @Test
    @DisplayName("中文日期与全角冒号")
    void parsesChineseFormats() {
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("2026年09月17日"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 9, 5), DateUtils.parse("2026年9月17日 9时5分"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31), DateUtils.parseFromText("开标时间：2026-09-17 16：31"));
    }

    @Test
    @DisplayName("「15点00分」也要解析出时分，不能只剩 00:00")
    void parsesDianAsHourMinuteSeparator() {
        // 实测原文：…并于2026年09月24日15点00分（北京时间）前提交响应文件
        // 不接受「点」的话时分被丢掉，截止当天上午就会被判成"已截止"。
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 0),
                DateUtils.parseFromText("并于2026年09月24日15点00分（北京时间）前提交响应文件"));
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 0), DateUtils.parse("2026年09月24日15点00分"));
        // 只有"点"没有"分"也要认
        assertEquals(LocalDateTime.of(2026, 9, 24, 15, 0), DateUtils.parse("2026年09月24日15点"));
    }

    @Test
    @DisplayName("从带前缀的文本中抽取日期")
    void parsesFromText() {
        assertEquals(LocalDateTime.of(2026, 9, 16, 16, 31),
                DateUtils.parseFromText("发布日期：2026-09-16 16:31"));
        assertEquals(LocalDateTime.of(2026, 9, 16, 0, 0),
                DateUtils.parseFromText("【发稿时间 ：2026-09-16 阅读次数： 12】"));
    }

    @Test
    @DisplayName("非法输入返回 null 而不是抛异常")
    void returnsNullForInvalidInput() {
        assertNull(DateUtils.parse(null));
        assertNull(DateUtils.parse(""));
        assertNull(DateUtils.parse("   "));
        assertNull(DateUtils.parse("暂未确定"));
        assertNull(DateUtils.parse("2026-13-45"));
        assertNull(DateUtils.parseFromText("没有日期"));
    }

    @Test
    @DisplayName("URL 规范化与去重键")
    void urlHashing() {
        assertEquals("https://a.com/x", Hashing.normalizeUrl("  https://a.com/x/  "));
        assertEquals("https://a.com/x", Hashing.normalizeUrl("https://a.com/x#top"));
        assertEquals(Hashing.urlHash("https://a.com/x.html"), Hashing.urlHash("https://a.com/x.html/"));
        assertNotEquals(Hashing.urlHash("https://a.com/1.html"), Hashing.urlHash("https://a.com/2.html"));
        assertEquals(64, Hashing.urlHash("https://a.com/公告/花卉.html").length());
        assertTrue(Hashing.urlHash("https://a.com/x").matches("[0-9a-f]{64}"));
    }
}
