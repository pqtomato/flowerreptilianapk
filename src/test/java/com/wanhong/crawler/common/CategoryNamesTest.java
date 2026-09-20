package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CategoryNamesTest {

    @Test
    @DisplayName("9 位子类精确匹配")
    void resolvesExactCode() {
        assertEquals("招标公告", CategoryNames.resolve("003001002"));
        assertEquals("成交公告", CategoryNames.resolve("003002009"));
    }

    @Test
    @DisplayName("未收录的 9 位编码退回 6 位大类")
    void fallsBackToParentCategory() {
        assertEquals("工程建设", CategoryNames.resolve("003001099"));
        assertEquals("政府采购", CategoryNames.resolve("003002001"));
        assertEquals("服务", CategoryNames.resolve("002003001"));
    }

    @Test
    @DisplayName("县级转载编码显示为其他交易，不把原始数字抛到界面上")
    void resolvesUnknownCountyCode() {
        assertEquals("其他交易", CategoryNames.resolve("036001008002"));
        assertEquals("其他交易", CategoryNames.resolve("005009"));
        assertEquals("其他交易", CategoryNames.resolve("004001"));
    }

    @Test
    @DisplayName("空值与未知非数字编码原样返回")
    void handlesEdgeCases() {
        assertEquals("", CategoryNames.resolve(null));
        assertEquals("", CategoryNames.resolve("  "));
        assertEquals("ABC", CategoryNames.resolve("ABC"));
    }
}
