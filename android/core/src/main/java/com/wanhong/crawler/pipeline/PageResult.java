package com.wanhong.crawler.pipeline;

import java.util.ArrayList;
import java.util.List;

/** 分页结果。 */
public class PageResult<T> {

    public final List<T> content;
    public final int page;
    public final int size;
    public final long totalElements;
    public final int totalPages;

    public PageResult(List<T> content, int page, int size, long totalElements) {
        this.content = content == null ? new ArrayList<T>() : content;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }
}
