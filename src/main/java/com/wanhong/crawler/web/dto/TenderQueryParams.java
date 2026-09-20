package com.wanhong.crawler.web.dto;

import java.time.LocalDateTime;

/**
 * 公告查询条件。
 *
 * @param onlyOpen       只看未截止（手机端最常用的筛选）
 * @param onlyNew        只看新公告（未被"全部已读"清掉的）
 * @param trackedOnly    只看收藏
 * @param includeRemoved 是否包含已下架公告（默认不包含）
 * @param sortBy         publish（发布时间倒序，默认）/ deadline（截止时间升序）
 */
public record TenderQueryParams(String siteCode,
                                String keyword,
                                String region,
                                LocalDateTime fromTime,
                                LocalDateTime toTime,
                                boolean onlyOpen,
                                boolean onlyNew,
                                boolean trackedOnly,
                                boolean includeRemoved,
                                String sortBy) {

    public static final String SORT_PUBLISH = "publish";
    public static final String SORT_DEADLINE = "deadline";

    public boolean sortByDeadline() {
        return SORT_DEADLINE.equalsIgnoreCase(sortBy);
    }
}
