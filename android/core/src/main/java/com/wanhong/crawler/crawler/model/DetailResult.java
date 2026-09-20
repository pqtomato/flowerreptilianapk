package com.wanhong.crawler.crawler.model;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 详情页解析结果。 */
public class DetailResult {

    public static final DetailResult EMPTY = new DetailResult(null, null, null, null,
            Collections.<String>emptyList());

    private final String title;
    private final LocalDateTime publishDate;
    private final String contentText;
    private final String contentHtml;
    private final List<String> attachments;

    public DetailResult(String title, LocalDateTime publishDate, String contentText,
                        String contentHtml, List<String> attachments) {
        this.title = title;
        this.publishDate = publishDate;
        this.contentText = contentText;
        this.contentHtml = contentHtml;
        this.attachments = attachments == null ? new ArrayList<String>() : attachments;
    }

    public String title() {
        return title;
    }

    public LocalDateTime publishDate() {
        return publishDate;
    }

    public String contentText() {
        return contentText;
    }

    public String contentHtml() {
        return contentHtml;
    }

    public List<String> attachments() {
        return attachments;
    }

    public boolean isEmpty() {
        return (contentText == null || contentText.trim().isEmpty())
                && (title == null || title.trim().isEmpty())
                && attachments.isEmpty();
    }
}
