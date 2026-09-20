package com.wanhong.crawler.crawler.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 详情页解析结果。
 */
public record DetailResult(String title,
                           LocalDateTime publishDate,
                           String contentText,
                           String contentHtml,
                           List<String> attachments) {

    public static final DetailResult EMPTY = new DetailResult(null, null, null, null, List.of());

    public boolean isEmpty() {
        return (contentText == null || contentText.isBlank())
                && (title == null || title.isBlank())
                && (attachments == null || attachments.isEmpty());
    }
}
