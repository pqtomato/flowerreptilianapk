package com.wanhong.crawler.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 地区识别：epoint 检索结果里的 citycode 映射成区县名；
 * 没有地区字段的源（城管局）从标题推断，并归一成与 citycode 一致的规范名。
 */
public final class RegionNames {

    private static final Map<String, String> CITY_CODES = new LinkedHashMap<>();

    /**
     * 标题里出现这些词就认为属于该区县。
     *
     * <p>顺序很重要：必须"桐城市"先于"桐城"；刻意不收录"经开区/高新区" ——
     * 它们既对应不到 citycode（筛选器里没这个值），又会把"桐城经开区…"抢先命中。
     */
    private static final List<String> TITLE_REGIONS = Collections.unmodifiableList(java.util.Arrays.asList(
            "迎江区", "大观区", "宜秀区",
            "怀宁县", "桐城市", "潜山市", "太湖县", "宿松县", "望江县", "岳西县",
            "桐城", "怀宁", "潜山", "太湖", "宿松", "望江", "岳西", "迎江", "大观", "宜秀"));

    /** 简称 → 规范名，保证区县筛选器能匹配上。 */
    private static final Map<String, String> SHORT_TO_FULL = new LinkedHashMap<>();

    static {
        CITY_CODES.put("340800", "安庆市");
        CITY_CODES.put("340801", "市辖区");
        CITY_CODES.put("340802", "迎江区");
        CITY_CODES.put("340803", "大观区");
        CITY_CODES.put("340811", "宜秀区");
        CITY_CODES.put("340822", "怀宁县");
        CITY_CODES.put("340824", "潜山市");
        CITY_CODES.put("340825", "太湖县");
        CITY_CODES.put("340826", "宿松县");
        CITY_CODES.put("340827", "望江县");
        CITY_CODES.put("340828", "岳西县");
        CITY_CODES.put("340881", "桐城市");

        SHORT_TO_FULL.put("桐城", "桐城市");
        SHORT_TO_FULL.put("怀宁", "怀宁县");
        SHORT_TO_FULL.put("潜山", "潜山市");
        SHORT_TO_FULL.put("太湖", "太湖县");
        SHORT_TO_FULL.put("宿松", "宿松县");
        SHORT_TO_FULL.put("望江", "望江县");
        SHORT_TO_FULL.put("岳西", "岳西县");
        SHORT_TO_FULL.put("迎江", "迎江区");
        SHORT_TO_FULL.put("大观", "大观区");
        SHORT_TO_FULL.put("宜秀", "宜秀区");
    }

    private RegionNames() {
    }

    public static String byCityCode(String cityCode) {
        if (cityCode == null || cityCode.trim().isEmpty()) {
            return "";
        }
        String value = CITY_CODES.get(cityCode.trim());
        return value == null ? "" : value;
    }

    public static String guessFromTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return "";
        }
        for (String region : TITLE_REGIONS) {
            if (title.contains(region)) {
                String full = SHORT_TO_FULL.get(region);
                return full != null ? full : region;
            }
        }
        return "";
    }

    /** 已收录的区县名（去重），供前端下拉使用。 */
    public static List<String> knownRegions() {
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(CITY_CODES.values())));
    }
}
