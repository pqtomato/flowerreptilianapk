package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingTest {

    @Test
    @DisplayName("URL 规范化：去结尾斜杠、去锚点、去首尾空白")
    void normalizesUrl() {
        assertEquals("https://a.com/x", Hashing.normalizeUrl("  https://a.com/x/  "));
        assertEquals("https://a.com/x", Hashing.normalizeUrl("https://a.com/x#section"));
        assertEquals("https://a.com/x", Hashing.normalizeUrl("https://a.com/x///"));
        assertEquals("", Hashing.normalizeUrl(null));
    }

    @Test
    @DisplayName("同一公告的不同写法得到同一个去重键")
    void sameHashForEquivalentUrls() {
        assertEquals(
                Hashing.urlHash("https://a.com/jyxx/x.html"),
                Hashing.urlHash("https://a.com/jyxx/x.html/"));
        assertEquals(
                Hashing.urlHash("https://a.com/jyxx/x.html"),
                Hashing.urlHash("https://a.com/jyxx/x.html#top"));
    }

    @Test
    @DisplayName("不同 URL 的去重键不同，摘要长度 64")
    void differentUrlDifferentHash() {
        String first = Hashing.urlHash("https://a.com/1.html");
        String second = Hashing.urlHash("https://a.com/2.html");
        assertNotEquals(first, second);
        assertEquals(64, first.length());
        assertTrue(first.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("中文 URL 也能稳定摘要")
    void handlesChineseUrls() {
        String hash = Hashing.urlHash("https://a.com/公告/花卉.html");
        assertEquals(64, hash.length());
        assertEquals(hash, Hashing.urlHash("https://a.com/公告/花卉.html"));
    }
}
