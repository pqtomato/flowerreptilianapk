package com.wanhong.crawler.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * epoint 站群的分类编码 → 中文分类名。
 *
 * <p>未收录的编码统一显示成「其他交易」——交易服务网索引里混有大量县级转载记录，
 * 其分类编码自成一套（036xxx / 005xxx / 004xxx），原始编码仍保留在 categoryCode 字段。
 */
public final class CategoryNames {

    public static final String UNKNOWN_CATEGORY = "其他交易";

    private static final Map<String, String> NAMES = new LinkedHashMap<>();

    static {
        // 安庆市公共资源交易服务网
        NAMES.put("003001", "工程建设");
        NAMES.put("003001002", "招标公告");
        NAMES.put("003001005", "答疑澄清");
        NAMES.put("003001009", "中标候选人公示");
        NAMES.put("003001011", "中标结果公示");
        NAMES.put("003001013", "合同公告");
        NAMES.put("003001014", "项目异常");
        NAMES.put("003001019", "招标文件预公示");

        NAMES.put("003002", "政府采购");
        NAMES.put("003002009", "成交公告");
        NAMES.put("003002010", "采购合同公告");
        NAMES.put("003002014", "采购意向公开");

        NAMES.put("003003", "国有产权");
        NAMES.put("003004", "土地矿权");
        NAMES.put("003005", "自主交易");
        NAMES.put("003006", "其他交易");
        NAMES.put("003008", "其他交易");

        // 安庆市阳光采购交易服务平台
        NAMES.put("002001", "工程");
        NAMES.put("002002", "货物");
        NAMES.put("002003", "服务");
        NAMES.put("002004", "竞价采购");
        NAMES.put("002008", "标前公示");
    }

    private CategoryNames() {
    }

    /** 解析分类名：9 位精确 → 6 位大类 → 3 位段 → 其他交易。 */
    public static String resolve(String code) {
        if (code == null || code.trim().isEmpty()) {
            return "";
        }
        String value = code.trim();
        String exact = NAMES.get(value);
        if (exact != null) {
            return exact;
        }
        if (value.length() >= 6) {
            String parent = NAMES.get(value.substring(0, 6));
            if (parent != null) {
                return parent;
            }
        }
        if (value.length() >= 3) {
            String section = NAMES.get(value.substring(0, 3));
            if (section != null) {
                return section;
            }
        }
        return value.matches("\\d+") ? UNKNOWN_CATEGORY : value;
    }

    /** 已收录编码，供排查使用。 */
    public static List<String> knownCodes() {
        return Collections.unmodifiableList(new ArrayList<>(NAMES.keySet()));
    }

    /** 供测试断言使用：全部已知分类名。 */
    public static List<String> knownNames() {
        return Collections.unmodifiableList(new ArrayList<>(new LinkedHashSet<>(NAMES.values())));
    }
}
