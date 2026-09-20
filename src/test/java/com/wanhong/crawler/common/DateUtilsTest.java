package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateUtilsTest {

    @Test
    @DisplayName("常见日期时间格式")
    void parsesCommonFormats() {
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("2026-09-17"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31), DateUtils.parse("2026-09-17 16:31"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 16, 31, 5), DateUtils.parse("2026-09-17 16:31:05"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("2026/09/17"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("20260917"));
        assertEquals(LocalDateTime.of(2026, 10, 21, 0, 0), DateUtils.parse("2026-10-21"));
    }

    @Test
    @DisplayName("中文日期格式")
    void parsesChineseFormats() {
        assertEquals(LocalDateTime.of(2026, 9, 17, 0, 0), DateUtils.parse("2026年09月17日"));
        assertEquals(LocalDateTime.of(2026, 9, 17, 9, 5), DateUtils.parse("2026年9月17日 9时5分"));
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
        assertNull(DateUtils.parseFromText("没有日期"));
        assertNull(DateUtils.parse("2026-13-45"));
    }
}
