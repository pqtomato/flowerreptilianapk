package com.wanhong.crawler.crawler.model;

/**
 * 一次站内检索用的词。
 *
 * <p>作用域决定检索哪个字段：
 * <ul>
 *   <li>{@code TITLE} → {@code fields=title}，只看标题；</li>
 *   <li>{@code CONTENT} → {@code fields=title;content}，标题正文都搜，
 *       用来捞「标题没写、正文里提到」的公告（实测 aqggzy 搜「郁金香」
 *       标题命中 0 条、正文命中 24 条）。</li>
 * </ul>
 */
public record SearchTerm(String keyword, String fields, String scope) {

    public static final String SCOPE_TITLE = "TITLE";
    public static final String SCOPE_CONTENT = "CONTENT";

    public static SearchTerm title(String keyword) {
        return new SearchTerm(keyword, "title", SCOPE_TITLE);
    }

    public static SearchTerm content(String keyword) {
        return new SearchTerm(keyword, "title;content", SCOPE_CONTENT);
    }
}
