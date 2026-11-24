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
    /**
     * 截止日期是否为估算值。
     *
     * <p>已停用：估算只认正文里写明的日期，新数据恒为 {@code false}；
     * 字段保留是为了兼容旧库结构与导出格式。
     */
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
    /**
     * 公告类型：bid（可投）/ award（结果）/ contract（合同）/ aborted（终止）。
     * 见 {@link com.wanhong.crawler.common.NoticeType}。
     */
    public String noticeType;
    /**
     * 预算金额（元）；正文里没写或写「/」时为 null。
     *
     * <p>只有「预算金额」会当钱：「最高限价」在真实公告里可能是
     * {@code 3.4元/平方米/年}（单价）或 {@code 审定金额的95%}（比例），
     * 当总额排序会得出荒谬结果，所以它只留文本（{@link #budgetCapText}）。
     */
    public Long budgetYuan;
    /** 最高限价原文（单价 / 比例都可能），仅用于展示 */
    public String budgetCapText;
    /** 项目编号，例如 H0FSCG26D03G0397 */
    public String projectCode;
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

    /**
     * 这条公告是否值得出现在默认列表里（可投）。
     *
     * <p>结果 / 合同 / 终止类公告不算 —— 它们已经定标或作废，
     * 对"还有哪些能投"没有意义，而且会因为没有报名截止时间而被估出一个假截止日。
     */
    public boolean biddable() {
        return com.wanhong.crawler.common.NoticeType.isBiddable(noticeType);
    }

    /** 有没有可排序的金额（0 与 null 都算没有）。 */
    public boolean hasBudget() {
        return budgetYuan != null && budgetYuan > 0;
    }

    /** 列表上显示的金额短标签，例如「306 万」；没有金额返回空串。 */
    public String budgetLabel() {
        return com.wanhong.crawler.crawler.BudgetExtractor.label(budgetYuan);
    }
}
