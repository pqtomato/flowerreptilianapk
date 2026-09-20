package com.wanhong.crawler.crawler.model;

/**
 * 一次站内检索用的词。
 *
 * <p>作用域决定搜哪个字段：标题词搜 {@code title}；正文词搜 {@code title;content}，
 * 用来捞「标题没写、正文里提到」的公告（实测 aqggzy 搜「郁金香」标题命中 0 条、
 * 正文命中 24 条）。
 */
public class SearchTerm {

    public static final String SCOPE_TITLE = "TITLE";
    public static final String SCOPE_CONTENT = "CONTENT";

    private final String keyword;
    private final String fields;
    private final String scope;

    public SearchTerm(String keyword, String fields, String scope) {
        this.keyword = keyword;
        this.fields = fields;
        this.scope = scope;
    }

    public static SearchTerm title(String keyword) {
        return new SearchTerm(keyword, "title", SCOPE_TITLE);
    }

    public static SearchTerm content(String keyword) {
        return new SearchTerm(keyword, "title;content", SCOPE_CONTENT);
    }

    public String keyword() {
        return keyword;
    }

    public String fields() {
        return fields;
    }

    public String scope() {
        return scope;
    }

    public boolean isContentScope() {
        return SCOPE_CONTENT.equals(scope);
    }
}
