package com.wanhong.crawler.common;

import java.util.regex.Pattern;

/**
 * 公告类型判定 —— 这条公告"还能不能投"。
 *
 * <p>为什么需要它：三个站点都会把「成交结果公告」「中标通知书」「采购合同公告」
 * 一起抓回来，这些是<b>已经定标</b>的结果类公告，对"还有哪些能投"没有意义。
 * 更麻烦的是它们正文里没有报名截止时间，会被 {@code DeadlineExtractor}
 * 按「发布日 + N 天」估算出一个<b>未来的假截止日</b>，于是顶着「还剩 48 天」
 * 混进"未截止"列表，甚至因为"按截止排序"而排在最前面 —— 会让人误判。
 *
 * <p><b>判定原则：只排除"确定不是机会"的。</b>
 * 认不出来的一律当作可投（{@link #BID}）：宁可多显示一条，
 * 也不要把真正的招标公告藏起来。所以这里的实现方式是"命中否定模式才算非可投"，
 * 而不是"必须命中正面模式才算可投"。
 */
public final class NoticeType {

    /** 可投：招标 / 采购 / 磋商 / 谈判 / 询价 / 更正澄清等 */
    public static final String BID = "bid";
    /** 结果类：成交 / 中标 / 中选 / 中标候选人公示 / 中标通知书 */
    public static final String AWARD = "award";
    /** 合同类：合同公告 / 合同公示 / 合同备案 */
    public static final String CONTRACT = "contract";
    /** 终止类：流标 / 废标 / 终止 / 采购失败 */
    public static final String ABORTED = "aborted";

    /** 合同类关键词（优先级最高） */
    private static final Pattern CONTRACT_PATTERN = Pattern.compile(
            "合同公告|合同公示|采购合同|合同备案|合同签订|合同变更|合同履行情况");

    /** 结果类关键词 */
    private static final Pattern AWARD_PATTERN = Pattern.compile(
            "成交|中标|中选|结果公告|结果公示|中标候选人|中标通知书|评标结果|定标结果|成交结果");

    /** 终止类关键词 */
    private static final Pattern ABORTED_PATTERN = Pattern.compile(
            "流标|废标|终止公告|中止公告|招标失败|采购失败|终止采购|废标公告|终止招标");

    private NoticeType() {
    }

    /**
     * 判定公告类型。
     *
     * <p>先看类别字段（站点给的 {@code category} 有时直接是「成交公告」「采购合同公告」，
     * 这是最强信号），再看标题。两者拼起来一起匹配即可。
     *
     * @param category 站点给的公告类别，可为 null
     * @param title    公告标题，可为 null
     * @return {@link #BID} / {@link #AWARD} / {@link #CONTRACT} / {@link #ABORTED}
     */
    public static String classify(String category, String title) {
        String text = safe(category) + ' ' + safe(title);
        // 顺序很重要：合同 > 结果 > 终止。像「XX采购项目中标通知书」这种
        // 标题里同时出现「采购」和「中标」，必须先判结果类。
        if (CONTRACT_PATTERN.matcher(text).find()) {
            return CONTRACT;
        }
        if (AWARD_PATTERN.matcher(text).find()) {
            return AWARD;
        }
        if (ABORTED_PATTERN.matcher(text).find()) {
            return ABORTED;
        }
        return BID;
    }

    /**
     * 这条公告是否"可投"（值得出现在默认列表里）。
     *
     * <p>类型为空时返回 {@code true}：老数据或认不出类型时按可投处理，
     * 宁可多显示也不要漏掉真机会。
     */
    public static boolean isBiddable(String noticeType) {
        return noticeType == null || noticeType.isEmpty() || BID.equals(noticeType);
    }

    /** 给界面用的中文短标签。 */
    public static String label(String noticeType) {
        if (AWARD.equals(noticeType)) {
            return "结果公告";
        }
        if (CONTRACT.equals(noticeType)) {
            return "合同公告";
        }
        if (ABORTED.equals(noticeType)) {
            return "已终止";
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
