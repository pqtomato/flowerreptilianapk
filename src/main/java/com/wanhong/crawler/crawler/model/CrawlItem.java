package com.wanhong.crawler.crawler.model;

import java.time.LocalDateTime;

/**
 * 采集到的一条候选公告（尚未经过关键词过滤与详情补全）。
 */
public class CrawlItem {

    private String siteCode;
    private String siteName;
    private String title;
    private String url;
    private String categoryCode;
    private String category;
    /** 区县（如"怀宁县"），来自 citycode 映射或标题推断 */
    private String region;
    private LocalDateTime publishDate;
    /** 列表接口/列表页自带的摘要或正文片段 */
    private String summary;
    /** 站点内部的公告 ID（epoint 的 infoid），用于拼接兜底链接 */
    private String sourceId;
    /** 触发本次发现的检索词 */
    private String sourceKeyword;

    public CrawlItem() {
    }

    public CrawlItem(String siteCode, String siteName) {
        this.siteCode = siteCode;
        this.siteName = siteName;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public LocalDateTime getPublishDate() {
        return publishDate;
    }

    public void setPublishDate(LocalDateTime publishDate) {
        this.publishDate = publishDate;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSourceId() {
        return sourceId;
    }

    public void setSourceId(String sourceId) {
        this.sourceId = sourceId;
    }

    public String getSourceKeyword() {
        return sourceKeyword;
    }

    public void setSourceKeyword(String sourceKeyword) {
        this.sourceKeyword = sourceKeyword;
    }

    @Override
    public String toString() {
        return "CrawlItem{" + siteCode + " | " + title + " | " + url + "}";
    }
}
