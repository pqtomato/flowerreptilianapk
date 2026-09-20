package com.wanhong.crawler.crawler.model;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 本次采集新入库的一条公告 —— 只带通知展示需要的字段。
 *
 * <p>为什么不直接用 {@code TenderRecord}：那是持久化载体，塞进采集结果里会把
 * 整个 pipeline 类型拖进 crawler 模型层（形成反向依赖）。通知只需要标题和截止日期。
 *
 * <p>「剩 N 天 · 标题」这套行文案也放在这里，让<b>采集完成通知</b>和<b>截止提醒通知</b>
 * 共用同一份格式化逻辑 —— 否则两处各写一遍，迟早会不一致。
 * 同理，{@link #mostUrgent} 也放在内核里，这样"通知里先列哪几条"是可以离线单测的。
 */
public class NewTender {

    /** 通知里单条标题的最大长度，超出截断加省略号。 */
    public static final int MAX_TITLE = 28;

    /** 已截止的排在所有未截止之后；同一档内用它做相对排序。 */
    private static final long EXPIRED_BASE = 1_000_000L;

    public final String title;
    public final LocalDateTime deadlineDate;

    public NewTender(String title, LocalDateTime deadlineDate) {
        this.title = title;
        this.deadlineDate = deadlineDate;
    }

    /** 距离截止还有几天；没有截止日期返回 {@link Long#MIN_VALUE}。 */
    public long daysLeft() {
        if (deadlineDate == null) {
            return Long.MIN_VALUE;
        }
        return ChronoUnit.DAYS.between(LocalDateTime.now(), deadlineDate);
    }

    /** 是否已经过了报名截止时间。没有截止日期的算"未截止"（宁可不漏）。 */
    public boolean expired() {
        // 必须比时间戳，不能比 daysLeft()：ChronoUnit.DAYS.between 是**向零截断**的，
        // 23 小时前截止的项目算出来是 0 天，会被误判成"今天截止、还没过期"。
        return deadlineDate != null && deadlineDate.isBefore(LocalDateTime.now());
    }

    /**
     * 挑出通知里要列出的那几条：<b>未截止的按剩余天数升序在前，已截止的排最后</b>，
     * 没有截止日期的垫底。
     *
     * <p>踩过的坑：一开始直接按 {@code daysLeft} 升序排，而已截止的天数是<b>负数</b>，
     * 于是"已截止"项窜到了通知第一行 —— 而折叠状态下只显示第一行，
     * 用户点开通知看到的就是最没用的那条。实测 13 条新公告里 5 条已截止，
     * 恰好把这 5 条顶到了最前面。
     *
     * @param items 候选
     * @param limit 最多取几条（≤0 表示不限制）
     * @return 新列表，不会回写调用方的集合
     */
    public static List<NewTender> mostUrgent(List<NewTender> items, int limit) {
        List<NewTender> sorted = new ArrayList<>();
        if (items != null) {
            for (NewTender item : items) {
                if (item != null) {
                    sorted.add(item);
                }
            }
        }
        sorted.sort(Comparator.comparingLong(NewTender::urgencyRank));
        if (limit > 0 && sorted.size() > limit) {
            return new ArrayList<>(sorted.subList(0, limit));
        }
        return sorted;
    }

    /**
     * 排序用的紧急度：未截止的越小越急；已截止的整档排到未截止之后；
     * 没有截止日期的排最后。
     */
    private long urgencyRank() {
        if (deadlineDate == null) {
            return Long.MAX_VALUE;
        }
        LocalDateTime now = LocalDateTime.now();
        if (deadlineDate.isBefore(now)) {
            // 已截止：整档垫在未截止之后（未截止最大也就几百天，远小于 EXPIRED_BASE）。
            // 同一档内"刚截止的"更值得看一眼，所以按"过去了多少分钟"升序。
            return EXPIRED_BASE + ChronoUnit.MINUTES.between(deadlineDate, now);
        }
        return ChronoUnit.DAYS.between(now, deadlineDate);
    }

    /** 通知里的一行，例如「剩 3 天 · 怀宁县2026年度和美乡村…」。 */
    public String describe() {
        return describe(title, deadlineDate);
    }

    /** 同一套行文案的静态版本，供只持有原始字段的调用方使用。 */
    public static String describe(String title, LocalDateTime deadline) {
        String shortened = shorten(title);
        String left = leftLabel(deadline);
        return left.isEmpty() ? shortened : left + " · " + shortened;
    }

    /** 「今天截止」比「剩 0 天」更像人话；已截止的也直接说清楚。 */
    public static String leftLabel(LocalDateTime deadline) {
        if (deadline == null) {
            return "";
        }
        LocalDateTime now = LocalDateTime.now();
        // 与 expired() 保持同一口径：不能靠 days 的正负判断，那样 23 小时前截止的会显示成"今天截止"
        if (deadline.isBefore(now)) {
            return "已截止";
        }
        long days = ChronoUnit.DAYS.between(now, deadline);
        return days == 0 ? "今天截止" : "剩 " + days + " 天";
    }

    public static String shorten(String title) {
        if (title == null) {
            return "";
        }
        String trimmed = title.trim();
        return trimmed.length() <= MAX_TITLE ? trimmed : trimmed.substring(0, MAX_TITLE) + "…";
    }
}
