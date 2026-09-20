package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionNamesTest {

    @Test
    @DisplayName("citycode 映射区县")
    void mapsCityCode() {
        assertEquals("怀宁县", RegionNames.byCityCode("340822"));
        assertEquals("桐城市", RegionNames.byCityCode("340881"));
        assertEquals("潜山市", RegionNames.byCityCode("340824"));
        assertEquals("太湖县", RegionNames.byCityCode("340825"));
        assertEquals("宿松县", RegionNames.byCityCode("340826"));
        assertEquals("望江县", RegionNames.byCityCode("340827"));
        assertEquals("岳西县", RegionNames.byCityCode("340828"));
        assertEquals("迎江区", RegionNames.byCityCode("340802"));
        assertEquals("宜秀区", RegionNames.byCityCode("340811"));
        assertEquals("大观区", RegionNames.byCityCode("340803"));
    }

    @Test
    @DisplayName("未知 citycode 返回空串")
    void unknownCityCode() {
        assertEquals("", RegionNames.byCityCode("999999"));
        assertEquals("", RegionNames.byCityCode(null));
        assertEquals("", RegionNames.byCityCode("  "));
    }

    @Test
    @DisplayName("从标题推断区县（城管局这类没有地区字段的源）")
    void guessesFromTitle() {
        assertEquals("怀宁县", RegionNames.guessFromTitle("怀宁县马庙镇集镇区绿化管护项目成交结果公示"));
        assertEquals("桐城市", RegionNames.guessFromTitle("桐城市西北片区污水管网改造提升工程监理中标候选人公示"));
        assertEquals("潜山市", RegionNames.guessFromTitle("潜山市第一人民医院室外道路、景观配套工程"));
        assertEquals("岳西县", RegionNames.guessFromTitle("岳西县巍岭乡金黄路改造工程"));
    }

    @Test
    @DisplayName("标题只写简称时归一到规范名，保证区县筛选器可以匹配")
    void normalizesShortNameFromTitle() {
        assertEquals("桐城市", RegionNames.guessFromTitle("桐城经开区盛唐南路绿化及照明工程"));
        assertEquals("怀宁县", RegionNames.guessFromTitle("怀宁马庙镇绿化管护项目"));
        assertEquals("宿松县", RegionNames.guessFromTitle("宿松县城区绿化提升"));
    }

    @Test
    @DisplayName("推断结果必须落在可选区县列表里")
    void guessedRegionIsFilterable() {
        String region = RegionNames.guessFromTitle("桐城经开区绿化工程");
        assertTrue(RegionNames.knownRegions().contains(region),
                "推断出的区县必须与 citycode 映射使用同一套名称，否则筛选不出来");
    }

    @Test
    @DisplayName("市级公告不硬套区县")
    void returnsEmptyForCityLevel() {
        assertEquals("", RegionNames.guessFromTitle("安庆市菱湖公园水电维修材料采购项目成交结果公告"));
    }

    @Test
    @DisplayName("区县列表非空")
    void exposesKnownRegions() {
        assertTrue(RegionNames.knownRegions().contains("怀宁县"));
        assertTrue(RegionNames.knownRegions().size() >= 10);
    }
}
