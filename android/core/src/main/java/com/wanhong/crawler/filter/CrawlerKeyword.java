package com.wanhong.crawler.filter;

/**
 * 用户维护的关键词（词表文件之外的增删项）。
 *
 * <p>内置词表放文件里便于版本管理；用户在手机上的改动存数据库，两者合并成实际生效的词表。
 * 一条记录有两种动作：{@code ADD} 新增、{@code DISABLE} 停用某个内置词。
 */
public class CrawlerKeyword {

    public enum Scope {
        /** 标题词：作为站内检索词（搜 title），并判定标题命中 */
        TITLE,
        /** 正文词：检索 title;content，并判定正文命中 */
        CONTENT,
        /** 排除词：命中标题即丢弃 */
        EXCLUDE
    }

    public enum Action {
        ADD,
        DISABLE
    }

    private long id;
    private String keyword;
    private String display;
    private Scope scope;
    private Action action;
    private String note;

    public CrawlerKeyword() {
    }

    public CrawlerKeyword(String keyword, String display, Scope scope, Action action) {
        this.keyword = keyword;
        this.display = display;
        this.scope = scope;
        this.action = action;
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public String getDisplay() {
        return display;
    }

    public void setDisplay(String display) {
        this.display = display;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Action getAction() {
        return action;
    }

    public void setAction(Action action) {
        this.action = action;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
