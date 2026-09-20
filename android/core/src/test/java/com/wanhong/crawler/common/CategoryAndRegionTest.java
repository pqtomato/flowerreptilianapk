package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分类编码与区县识别。 */
class CategoryAndRegionTest {

    @Test
    @DisplayName("分类：9 位精确 / 6 位大类 / 未知编码归为其他交易")
    void resolvesCategoryNames() {
        assertEquals("招标公告", CategoryNames.resolve("003001002"));
        assertEquals("成交公告", CategoryNames.resolve("003002009"));
        assertEquals("工程建设", CategoryNames.resolve("003001099"));
        assertEquals("政府采购", CategoryNames.resolve("003002001"));
        assertEquals("服务", CategoryNames.resolve("002003001"));
        // 县级转载编码自成一套，不能把原始数字抛到界面上
        assertEquals("其他交易", CategoryNames.resolve("036001008002"));
        assertEquals("其他交易", CategoryNames.resolve("005009"));
        assertEquals("其他交易", CategoryNames.resolve("004001"));
        assertEquals("", CategoryNames.resolve(null));
        assertEquals("", CategoryNames.resolve("  "));
        assertEquals("ABC", CategoryNames.resolve("ABC"));
    }

    @Test
    @DisplayName("区县：citycode 映射")
    void mapsCityCode() {
        assertEquals("怀宁县", RegionNames.byCityCode("340822"));
        assertEquals("桐城市", RegionNames.byCityCode("340881"));
        assertEquals("潜山市", RegionNames.byCityCode("340824"));
        assertEquals("太湖县", RegionNames.byCityCode("340825"));
        assertEquals("宿松县", RegionNames.byCityCode("340826"));
        assertEquals("望江县", RegionNames.byCityCode("340827"));
        assertEquals("岳西县", RegionNames.byCityCode("340828"));
        assertEquals("迎江区", RegionNames.byCityCode("340802"));
        assertEquals("", RegionNames.byCityCode("999999"));
        assertEquals("", RegionNames.byCityCode(null));
    }

    @Test
    @DisplayName("区县：从标题推断，并归一成规范名")
    void guessesRegionFromTitle() {
        assertEquals("怀宁县", RegionNames.guessFromTitle("怀宁县马庙镇集镇区绿化管护项目成交结果公示"));
        assertEquals("桐城市", RegionNames.guessFromTitle("桐城市西北片区污水管网改造提升工程"));
        assertEquals("岳西县", RegionNames.guessFromTitle("岳西县巍岭乡金黄路改造工程"));
        // 简称要归一，否则筛选器里会出现两个匹配不上的值
        assertEquals("桐城市", RegionNames.guessFromTitle("桐城经开区盛唐南路绿化及照明工程"));
        assertEquals("怀宁县", RegionNames.guessFromTitle("怀宁马庙镇绿化管护项目"));
        // 市级公告不硬套区县
        assertEquals("", RegionNames.guessFromTitle("安庆市菱湖公园水电维修材料采购项目"));
    }

    @Test
    @DisplayName("推断出的区县必须落在可选列表里（否则筛选不出来）")
    void guessedRegionIsFilterable() {
        String region = RegionNames.guessFromTitle("桐城经开区绿化工程");
        assertTrue(RegionNames.knownRegions().contains(region));
        assertFalse(RegionNames.knownRegions().isEmpty());
    }
}
