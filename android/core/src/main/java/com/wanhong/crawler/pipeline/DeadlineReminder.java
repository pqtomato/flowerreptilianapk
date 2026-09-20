package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.crawler.model.NewTender;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 截止日期提醒：从已入库的公告里挑出"今天该提醒的"。
 *
 * <p>放在内核里（而不是 Android 侧）是为了能离线单测 —— 这段逻辑决定"会不会漏掉
 * 一个本来能投的项目"，而且是每天自动跑、没人盯着，必须可测。
 *
 * <p>提醒口径：
 * <ul>
 *   <li>只提醒<b>还没截止</b>的（已截止的提醒没有意义）；</li>
 *   <li>只提醒<b>可投</b>的（成交 / 中标 / 合同类不提醒，见 {@code NoticeType}）；</li>
 *   <li>只提醒<b>剩余天数 ≤ 阈值</b>的，避免给还有两个月的项目天天发通知；</li>
 *   <li>按剩余天数升序，最紧急的排最前。</li>
 * </ul>
 */
public final class DeadlineReminder {

    /** 通知正文里最多列几条，多了看不完也刷屏。 */
    public static final int MAX_LINES = 5;

    private DeadlineReminder() {
    }

    /**
     * 挑出需要提醒的公告。
     *
     * @param candidates  候选（一般来自"未截止 + 可投 + 按截止排序"的查询）
     * @param withinDays  提前多少天开始提醒（≤0 视为不提醒，返回空列表）
     * @return 按剩余天数升序的提醒列表，可能为空
     */
    public static List<TenderRecord> pick(List<TenderRecord> candidates, int withinDays) {
        List<TenderRecord> result = new ArrayList<>();
        if (candidates == null || withinDays <= 0) {
            return result;
        }
        for (TenderRecord record : candidates) {
            if (record == null || record.deadlineDate == null) {
                continue;
            }
            if (!record.biddable() || record.expired()) {
                continue;
            }
            long daysLeft = record.daysLeft();
            // daysLeft 为 MIN_VALUE 表示没有截止日期，前面已经挡掉
            if (daysLeft < 0 || daysLeft > withinDays) {
                continue;
            }
            result.add(record);
        }
        result.sort(Comparator.comparingLong(TenderRecord::daysLeft));
        return result;
    }

    /** 通知标题，例如「3 条公告即将截止」。 */
    public static String buildTitle(List<TenderRecord> due) {
        return due.size() + " 条公告即将截止";
    }

    /** 通知正文：每行「剩 N 天 · 标题」，最多 {@link #MAX_LINES} 条，
     *  超出部分用「…等 N 条」收尾。行文案与"采集完成"通知共用 {@link NewTender#describe}。 */
    public static String buildMessage(List<TenderRecord> due) {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(due.size(), MAX_LINES);
        for (int i = 0; i < shown; i++) {
            TenderRecord record = due.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(NewTender.describe(record.title, record.deadlineDate));
        }
        int rest = due.size() - shown;
        if (rest > 0) {
            sb.append("\n…等 ").append(due.size()).append(" 条");
        }
        return sb.toString();
    }
}
