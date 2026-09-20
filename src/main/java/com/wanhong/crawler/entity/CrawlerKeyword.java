package com.wanhong.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDateTime;

/**
 * 用户维护的关键词（在词表文件之外的增量/覆盖项）。
 *
 * <p>设计取舍：内置词表放在 classpath 文件里（便于版本管理），
 * 用户在手机端做的增删改动存到这张表，两者合并成"实际生效"的词表。
 * 这样既能保证内置词表可追溯，又不必为了改一个词去重新打包 jar。
 *
 * <p>一条记录有两种动作：
 * <ul>
 *   <li>{@code ADD} —— 新增的词（文件里没有）；</li>
 *   <li>{@code DISABLE} —— 停用某个内置词（文件里有，但用户不想要了）。</li>
 * </ul>
 */
@Entity
@Table(name = "crawler_keyword",
        uniqueConstraints = @UniqueConstraint(name = "uk_crawler_keyword", columnNames = {"keyword", "scope"}))
public class CrawlerKeyword {

    /** 关键词作用域 */
    public enum Scope {
        /** 标题词：作为站内检索词（检索 title 字段），并判定标题命中 */
        TITLE,
        /** 正文词：检索 title;content 字段，并判定正文命中 */
        CONTENT,
        /** 排除词：命中标题即丢弃 */
        EXCLUDE
    }

    /** 动作 */
    public enum Action {
        ADD,
        DISABLE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归一化后的关键词（小写、去空白），用于匹配与唯一约束 */
    @Column(name = "keyword", nullable = false, length = 128)
    private String keyword;

    /** 展示用的原始写法 */
    @Column(name = "display", length = 128)
    private String display;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16)
    private Scope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private Action action;

    /** 备注（可选） */
    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    public CrawlerKeyword() {
    }

    public CrawlerKeyword(String keyword, String display, Scope scope, Action action, String note) {
        this.keyword = keyword;
        this.display = display;
        this.scope = scope;
        this.action = action;
        this.note = note;
        this.createTime = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }
}
