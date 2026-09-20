package com.wanhong.crawler.pipeline;

import java.time.LocalDateTime;

/**
 * 一条花卉 / 园林绿化招标公告。
 *
 * <p>字段与旧版数据库一一对应。用公开字段是为了让 SQLite 存取代码保持简短
 * （这是一个纯数据载体，没有行为）。
 */
public class TenderRecord {

    public long id;
    public String siteCode;
    public String siteName;
    public String title;
    /** 详情页地址（去重键基于它的 SHA-256） */
    public String url;
    public String urlHash;
    /** 面向用户的备用链接：原地址打不开时用站点自己的跳转地址 */
    public String accessUrl;
    public String category;
    public String categoryCode;
    public String region;
    public LocalDateTime publishDate;
    /** 报名 / 投标截止日期 —— 手机端"还能不能投"的依据 */
    public LocalDateTime deadlineDate;
    /** 截止日期是否为估算值 */
    public boolean deadlineEstimated;
    public String deadlineSource;
    public String matchedKeywords;
    public String matchedScope;
    public String sourceKeyword;
    public String summary;
    public String content;
    public String contentHtml;
    /** 附件列表（JSON 数组字符串） */
    public String attachments;
    /** 同一公告在多个站点被发现的情况 */
    public String alsoFoundOn;
    public boolean isNew;
    public boolean tracked;
    public boolean removed;
    public LocalDateTime verifiedAt;
    /** OK / FALLBACK / UNKNOWN / REMOVED */
    public String linkStatus;
    public LocalDateTime crawlTime;
    public LocalDateTime createTime;

    /** 列表视图用：距离截止还有几天（负数=已截止） */
    public long daysLeft() {
        if (deadlineDate == null) {
            return Long.MIN_VALUE;
        }
        return java.time.temporal.ChronoUnit.DAYS.between(LocalDateTime.now(), deadlineDate);
    }

    public boolean expired() {
        return deadlineDate != null && deadlineDate.isBefore(LocalDateTime.now());
    }

    public boolean hasContent() {
        return content != null && !content.trim().isEmpty();
    }

    public boolean hasAttachment() {
        return attachments != null && !attachments.trim().isEmpty() && !"[]".equals(attachments);
    }
}
