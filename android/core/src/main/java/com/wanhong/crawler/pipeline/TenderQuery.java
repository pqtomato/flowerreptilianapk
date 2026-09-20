package com.wanhong.crawler.pipeline;

import java.time.LocalDateTime;

/** 公告查询条件。 */
public class TenderQuery {

    public static final String SORT_PUBLISH = "publish";
    public static final String SORT_DEADLINE = "deadline";

    public String siteCode;
    public String keyword;
    public String region;
    public LocalDateTime fromTime;
    public LocalDateTime toTime;

    /** 只看未截止（手机端最常用） */
    public boolean onlyOpen;
    public boolean onlyNew;
    public boolean trackedOnly;
    /**
     * 只看"可投"的公告（排除成交 / 中标 / 合同 / 终止类）。
     * 界面上默认开启：实测默认列表里有超过一半是这类已经定标的结果公告。
     */
    public boolean onlyBiddable;
    public boolean includeRemoved;
    public String sortBy = SORT_PUBLISH;

    public boolean sortByDeadline() {
        return SORT_DEADLINE.equalsIgnoreCase(sortBy);
    }

    public TenderQuery copy() {
        TenderQuery copy = new TenderQuery();
        copy.siteCode = siteCode;
        copy.keyword = keyword;
        copy.region = region;
        copy.fromTime = fromTime;
        copy.toTime = toTime;
        copy.onlyOpen = onlyOpen;
        copy.onlyNew = onlyNew;
        copy.trackedOnly = trackedOnly;
        copy.onlyBiddable = onlyBiddable;
        copy.includeRemoved = includeRemoved;
        copy.sortBy = sortBy;
        return copy;
    }
}
