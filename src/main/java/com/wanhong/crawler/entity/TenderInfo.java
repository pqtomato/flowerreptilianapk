package com.wanhong.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 一条花卉 / 园林绿化招标公告。
 */
@Entity
@Table(name = "tender_info", indexes = {
        @Index(name = "idx_tender_site", columnList = "site_code"),
        @Index(name = "idx_tender_publish", columnList = "publish_date"),
        @Index(name = "idx_tender_deadline", columnList = "deadline_date"),
        @Index(name = "idx_tender_region", columnList = "region"),
        // 去重键用 URL 的 SHA-256 摘要：MySQL utf8mb4 下 varchar(1000) 无法建唯一索引
        @Index(name = "uk_tender_url_hash", columnList = "url_hash", unique = true)
})
public class TenderInfo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 站点编码：aqggzy / cgj / wyxmzx */
    @Column(name = "site_code", nullable = false, length = 32)
    private String siteCode;

    @Column(name = "site_name", length = 128)
    private String siteName;

    /** 公告标题 */
    @Column(name = "title", nullable = false, length = 1000)
    private String title;

    /** 详情页地址 */
    @Column(name = "url", nullable = false, length = 1000)
    private String url;

    /** URL 的 SHA-256 摘要，唯一键，用于去重 */
    @Column(name = "url_hash", nullable = false, length = 64)
    private String urlHash;

    /**
     * 面向用户的备用链接：当 {@link #url} 打不开（如站点索引里只有转载记录、
     * 没有落地页）时，改用站点自己的规范跳转地址，避免给出死链。
     */
    @Column(name = "access_url", length = 1000)
    private String accessUrl;

    /** 公告分类名（如：招标公告 / 中标结果公示 / 通知公告） */
    @Column(name = "category", length = 200)
    private String category;

    /** 分类编码 */
    @Column(name = "category_code", length = 64)
    private String categoryCode;

    /** 区县（如"怀宁县"），来自 citycode 映射或标题推断 */
    @Column(name = "region", length = 64)
    private String region;

    /** 发布日期 */
    @Column(name = "publish_date")
    private LocalDateTime publishDate;

    /**
     * 报名 / 投标截止日期。
     *
     * <p>绝大多数站点不给这个字段，所以要么从正文里解析，要么按
     * 「发布日期 + N 天」估算（{@link #deadlineEstimated} 标记）。
     * 手机上"这条还能不能投"完全依赖它。
     */
    @Column(name = "deadline_date")
    private LocalDateTime deadlineDate;

    /** 截止日期是否为估算值（true = 正文里没解析到，按默认天数推算） */
    @Column(name = "deadline_estimated")
    private Boolean deadlineEstimated;

    /** 截止日期来源说明，便于排查（正文解析 / 发布日期+60天估算） */
    @Column(name = "deadline_source", length = 64)
    private String deadlineSource;

    /** 本轮是否新发现（入库置位，手机端点"全部已读"后清零） */
    @Column(name = "is_new", nullable = false)
    private Boolean isNew = Boolean.TRUE;

    /** 是否收藏/跟踪（跟踪的公告不会被下架校验删除） */
    @Column(name = "tracked", nullable = false)
    private Boolean tracked = Boolean.FALSE;

    /** 是否已被下架（校验发现详情页已删除） */
    @Column(name = "removed", nullable = false)
    private Boolean removed = Boolean.FALSE;

    /** 上次链接校验时间 */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 链接状态：OK / FALLBACK（原地址无落地页）/ UNKNOWN / REMOVED */
    @Column(name = "link_status", length = 32)
    private String linkStatus;

    /** 命中关键词，逗号分隔 */
    @Column(name = "matched_keywords", length = 500)
    private String matchedKeywords;

    /** 命中位置：TITLE / CONTENT / TITLE,CONTENT */
    @Column(name = "matched_scope", length = 64)
    private String matchedScope;

    /** 采集时使用的检索词 */
    @Column(name = "source_keyword", length = 128)
    private String sourceKeyword;

    /** 列表页摘要 / 接口返回的正文片段 */
    @Column(name = "summary", length = 2000)
    private String summary;

    /** 详情页正文（纯文本） */
    @Lob
    @Column(name = "content")
    private String content;

    /** 详情页正文（HTML） */
    @Lob
    @Column(name = "content_html")
    private String contentHtml;

    /** 附件列表（JSON 数组字符串） */
    @Lob
    @Column(name = "attachments")
    private String attachments;

    /** 同一公告在多个站点被发现的站点编码，逗号分隔 */
    @Column(name = "also_found_on", length = 255)
    private String alsoFoundOn;

    @Column(name = "crawl_time")
    private LocalDateTime crawlTime;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getUrlHash() {
        return urlHash;
    }

    public void setUrlHash(String urlHash) {
        this.urlHash = urlHash;
    }

    public String getAccessUrl() {
        return accessUrl;
    }

    public void setAccessUrl(String accessUrl) {
        this.accessUrl = accessUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getCategoryCode() {
        return categoryCode;
    }

    public void setCategoryCode(String categoryCode) {
        this.categoryCode = categoryCode;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public LocalDateTime getDeadlineDate() {
        return deadlineDate;
    }

    public void setDeadlineDate(LocalDateTime deadlineDate) {
        this.deadlineDate = deadlineDate;
    }

    public Boolean getDeadlineEstimated() {
        return deadlineEstimated;
    }

    public void setDeadlineEstimated(Boolean deadlineEstimated) {
        this.deadlineEstimated = deadlineEstimated;
    }

    public String getDeadlineSource() {
        return deadlineSource;
    }

    public void setDeadlineSource(String deadlineSource) {
        this.deadlineSource = deadlineSource;
    }

    public Boolean getIsNew() {
        return isNew;
    }

    public void setIsNew(Boolean isNew) {
        this.isNew = isNew;
    }

    public Boolean getTracked() {
        return tracked;
    }

    public void setTracked(Boolean tracked) {
        this.tracked = tracked;
    }

    public Boolean getRemoved() {
        return removed;
    }

    public void setRemoved(Boolean removed) {
        this.removed = removed;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public String getLinkStatus() {
        return linkStatus;
    }

    public void setLinkStatus(String linkStatus) {
        this.linkStatus = linkStatus;
    }

    public LocalDateTime getPublishDate() {
        return publishDate;
    }

    public void setPublishDate(LocalDateTime publishDate) {
        this.publishDate = publishDate;
    }

    public String getMatchedKeywords() {
        return matchedKeywords;
    }

    public void setMatchedKeywords(String matchedKeywords) {
        this.matchedKeywords = matchedKeywords;
    }

    public String getMatchedScope() {
        return matchedScope;
    }

    public void setMatchedScope(String matchedScope) {
        this.matchedScope = matchedScope;
    }

    public String getSourceKeyword() {
        return sourceKeyword;
    }

    public void setSourceKeyword(String sourceKeyword) {
        this.sourceKeyword = sourceKeyword;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getContentHtml() {
        return contentHtml;
    }

    public void setContentHtml(String contentHtml) {
        this.contentHtml = contentHtml;
    }

    public String getAttachments() {
        return attachments;
    }

    public void setAttachments(String attachments) {
        this.attachments = attachments;
    }

    public String getAlsoFoundOn() {
        return alsoFoundOn;
    }

    public void setAlsoFoundOn(String alsoFoundOn) {
        this.alsoFoundOn = alsoFoundOn;
    }

    public LocalDateTime getCrawlTime() {
        return crawlTime;
    }

    public void setCrawlTime(LocalDateTime crawlTime) {
        this.crawlTime = crawlTime;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public void setCreateTime(LocalDateTime createTime) {
        this.createTime = createTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
