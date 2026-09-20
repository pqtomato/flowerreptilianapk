package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.common.NoticeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 截止日期提醒的挑选与文案。
 *
 * <p>这段逻辑每天自动跑、没人盯着，漏掉一条就等于错过一个能投的项目，
 * 所以边界情况都盯住：已截止的、结果类的、没有截止日的、刚好卡在阈值上的。
 */
class DeadlineReminderTest {

    private static TenderRecord record(String title, int daysFromNow, String noticeType) {
        TenderRecord r = new TenderRecord();
        r.title = title;
        r.noticeType = noticeType;
        r.deadlineDate = LocalDateTime.now().plusDays(daysFromNow).plusHours(1);
        return r;
    }

    @Test
    @DisplayName("只挑阈值内、未截止、可投的公告")
    void picksDueSoon() {
        List<TenderRecord> candidates = Arrays.asList(
                record("三天后截止的招标公告", 3, NoticeType.BID),
                record("十天后截止的招标公告", 10, NoticeType.BID));

        List<TenderRecord> due = DeadlineReminder.pick(candidates, 3);
        assertEquals(1, due.size());
        assertEquals("三天后截止的招标公告", due.get(0).title);
    }

    @Test
    @DisplayName("刚好等于阈值的要提醒（边界包含）")
    void includesBoundary() {
        List<TenderRecord> due = DeadlineReminder.pick(
                Collections.singletonList(record("卡在阈值上", 3, NoticeType.BID)), 3);
        assertEquals(1, due.size());
    }

    @Test
    @DisplayName("已截止的不提醒")
    void skipsExpired() {
        List<TenderRecord> due = DeadlineReminder.pick(
                Collections.singletonList(record("昨天就截止了", -2, NoticeType.BID)), 7);
        assertTrue(due.isEmpty());
    }

    @Test
    @DisplayName("结果 / 合同类不提醒 —— 它们已经定标，提醒没有意义")
    void skipsNonBiddable() {
        List<TenderRecord> candidates = Arrays.asList(
                record("某项目成交结果公告", 2, NoticeType.AWARD),
                record("某项目合同公告", 2, NoticeType.CONTRACT),
                record("某项目流标公告", 2, NoticeType.ABORTED));
        assertTrue(DeadlineReminder.pick(candidates, 7).isEmpty());
    }

    @Test
    @DisplayName("没有截止日期的不提醒（不能凭空提醒）")
    void skipsWithoutDeadline() {
        TenderRecord noDeadline = new TenderRecord();
        noDeadline.title = "没有截止日期的公告";
        noDeadline.noticeType = NoticeType.BID;
        assertTrue(DeadlineReminder.pick(Collections.singletonList(noDeadline), 7).isEmpty());
    }

    @Test
    @DisplayName("按剩余天数升序，最紧急的排最前")
    void sortsByUrgency() {
        List<TenderRecord> candidates = Arrays.asList(
                record("七天后", 7, NoticeType.BID),
                record("一天后", 1, NoticeType.BID),
                record("三天后", 3, NoticeType.BID));
        List<TenderRecord> due = DeadlineReminder.pick(candidates, 7);
        assertEquals(3, due.size());
        assertEquals("一天后", due.get(0).title);
        assertEquals("三天后", due.get(1).title);
        assertEquals("七天后", due.get(2).title);
    }

    @Test
    @DisplayName("阈值为 0 或负数时不提醒（等于关掉）")
    void disabledThreshold() {
        List<TenderRecord> candidates = Collections.singletonList(
                record("一天后", 1, NoticeType.BID));
        assertTrue(DeadlineReminder.pick(candidates, 0).isEmpty());
        assertTrue(DeadlineReminder.pick(candidates, -1).isEmpty());
        assertTrue(DeadlineReminder.pick(null, 3).isEmpty());
    }

    @Test
    @DisplayName("通知标题与正文文案")
    void buildsNotificationText() {
        List<TenderRecord> due = Arrays.asList(
                record("今天的项目", 0, NoticeType.BID),
                record("三天后的项目", 3, NoticeType.BID));
        assertEquals("2 条公告即将截止", DeadlineReminder.buildTitle(due));
        assertEquals("今天截止 · 今天的项目\n剩 3 天 · 三天后的项目",
                DeadlineReminder.buildMessage(due));
    }

    @Test
    @DisplayName("正文最多列 5 条，超出用「…等 N 条」收尾")
    void capsMessageLines() {
        List<TenderRecord> many = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            many.add(record("项目" + i, i, NoticeType.BID));
        }
        String message = DeadlineReminder.buildMessage(DeadlineReminder.pick(many, 7));
        assertEquals(6, message.split("\n").length);
        assertTrue(message.endsWith("…等 7 条"), message);
    }

    @Test
    @DisplayName("超长标题会截断，避免通知被一条撑满")
    void shortensLongTitle() {
        String longTitle = "桐城师范高等专科学校绿化养护服务采购项目（二次）竞争性磋商公告补充说明";
        String message = DeadlineReminder.buildMessage(
                Collections.singletonList(record(longTitle, 2, NoticeType.BID)));

        assertTrue(message.startsWith("剩 2 天 · "), message);
        // 只看标题部分：应被截到 28 字 + 一个省略号
        String titlePart = message.substring("剩 2 天 · ".length());
        assertEquals(29, titlePart.length(), titlePart);
        assertTrue(titlePart.endsWith("…"), titlePart);
        assertTrue(longTitle.startsWith(titlePart.substring(0, 28)), titlePart);
    }
}
