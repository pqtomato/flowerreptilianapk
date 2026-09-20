package com.wanhong.crawler.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地区识别。
 *
 * <p>参考上一版系统的做法：epoint 站群的检索结果里带 {@code citycode}，
 * 直接映射成区县名；而像城管局这类没有地区字段的源，就从标题里猜区县。
 * 手机端据此提供「按区县筛选」。
 */
public final class RegionNames {

    private static final Map<String, String> CITY_CODES = new LinkedHashMap<>();

    /**
     * 标题里出现这些词就认为属于该区县。
     *
     * <p>顺序很重要：必须先匹配"桐城市"再匹配"桐城"；同时刻意<b>不</b>收录
     * "经开区""高新区" —— 它们既无法对应到 citycode（筛选器里不存在这个值），
     * 又会把"桐城经开区…"这类标题抢先命中，导致真正的区县被吞掉。
     */
    private static final List<String> TITLE_REGIONS = List.of(
            "迎江区", "大观区", "宜秀区",
            "怀宁县", "桐城市", "潜山市", "太湖县", "宿松县", "望江县", "岳西县",
            "桐城", "怀宁", "潜山", "太湖", "宿松", "望江", "岳西", "迎江", "大观", "宜秀");

    /**
     * 简称 → 规范名。
     *
     * <p>标题里经常只写"桐城"而不写"桐城市"，必须归一到与 citycode 映射一致的名称，
     * 否则区县筛选器里会出现"桐城"和"桐城市"两个无法互相匹配的值。
     */
    private static final Map<String, String> SHORT_TO_FULL = Map.of(
            "桐城", "桐城市",
            "怀宁", "怀宁县",
            "潜山", "潜山市",
            "太湖", "太湖县",
            "宿松", "宿松县",
            "望江", "望江县",
            "岳西", "岳西县",
            "迎江", "迎江区",
            "大观", "大观区",
            "宜秀", "宜秀区");

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
    }

    private RegionNames() {
    }

    /** 按 citycode 映射区县名，未知返回空串。 */
    public static String byCityCode(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return "";
        }
        return CITY_CODES.getOrDefault(cityCode.trim(), "");
    }

    /**
     * 从标题里猜区县（用于没有地区字段的数据源，如城管局通知公告）。
     *
     * <p>结果会归一成与 {@link #byCityCode} 一致的规范名称，保证区县筛选器可用。
     */
    public static String guessFromTitle(String title) {
        if (title == null || title.isBlank()) {
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

    /** 已收录的区县名，供前端下拉使用。 */
    public static List<String> knownRegions() {
        return CITY_CODES.values().stream().distinct().toList();
    }
}
