package com.wanhong.crawler.crawler.epoint;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内检索结果。
 *
 * @param responseOk 接口是否返回了可解析的结果。false 表示被 WAF 拦截或返回了 HTML，
 *                   用来区分「真的没有数据」和「请求失败」，避免整轮静默跑空。
 */
public record EpointPage(long totalCount, List<EpointRecord> records, boolean responseOk) {

    private static final EpointPage FAILED = new EpointPage(0L, List.of(), false);

    /** 请求/解析失败 */
    public static EpointPage failed() {
        return FAILED;
    }

    /** 成功但没有数据 */
    public static EpointPage emptyResult() {
        return new EpointPage(0L, List.of(), true);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /**
     * 一条检索记录。
     *
     * @param cityCode epoint 的地区编码，用于映射区县（如 340822 → 怀宁县）
     */
    public record EpointRecord(String title,
                               String categoryCode,
                               LocalDateTime publishDate,
                               String linkUrl,
                               String infoId,
                               String content,
                               String cityCode) {
    }
}
