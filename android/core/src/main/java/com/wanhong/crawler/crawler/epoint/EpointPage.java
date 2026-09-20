package com.wanhong.crawler.crawler.epoint;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内检索结果。
 *
 * <p>{@code responseOk} 用来区分「真的没有数据」和「被 WAF 拦截 / 返回了 HTML」，
 * 避免整轮采集静默跑空（这是本项目开发中真实踩过的坑）。
 */
public class EpointPage {

    private static final EpointPage FAILED = new EpointPage(0L, new ArrayList<EpointRecord>(), false);
    private static final EpointPage EMPTY = new EpointPage(0L, new ArrayList<EpointRecord>(), true);

    private final long totalCount;
    private final List<EpointRecord> records;
    private final boolean responseOk;

    public EpointPage(long totalCount, List<EpointRecord> records, boolean responseOk) {
        this.totalCount = totalCount;
        this.records = records == null ? new ArrayList<EpointRecord>() : records;
        this.responseOk = responseOk;
    }

    public static EpointPage failed() {
        return FAILED;
    }

    public static EpointPage emptyResult() {
        return EMPTY;
    }

    public long totalCount() {
        return totalCount;
    }

    public List<EpointRecord> records() {
        return records;
    }

    public boolean responseOk() {
        return responseOk;
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    /** 一条检索记录。 */
    public static class EpointRecord {
        private final String title;
        private final String categoryCode;
        private final LocalDateTime publishDate;
        private final String linkUrl;
        private final String infoId;
        private final String content;
        private final String cityCode;

        public EpointRecord(String title, String categoryCode, LocalDateTime publishDate,
                            String linkUrl, String infoId, String content, String cityCode) {
            this.title = title;
            this.categoryCode = categoryCode;
            this.publishDate = publishDate;
            this.linkUrl = linkUrl;
            this.infoId = infoId;
            this.content = content;
            this.cityCode = cityCode;
        }

        public String title() {
            return title;
        }

        public String categoryCode() {
            return categoryCode;
        }

        public LocalDateTime publishDate() {
            return publishDate;
        }

        public String linkUrl() {
            return linkUrl;
        }

        public String infoId() {
            return infoId;
        }

        public String content() {
            return content;
        }

        public String cityCode() {
            return cityCode;
        }
    }
}
