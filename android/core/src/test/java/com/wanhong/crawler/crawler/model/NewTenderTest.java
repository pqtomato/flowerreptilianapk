package com.wanhong.crawler.crawler.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知里"一条公告一行"的文案。
 *
 * <p>「采集完成」通知和「截止提醒」通知共用这套格式化，所以单独测一遍 ——
 * 两处通知的口径必须一致，不然用户会看到同一个项目在两个通知里说法不同。
 */
class NewTenderTest {

    private static LocalDateTime inDays(int days) {
        return LocalDateTime.now().plusDays(days).plusHours(1);
    }

    @Test
    @DisplayName("按剩余天数说话：「剩 N 天」/「今天截止」/「已截止」")
    void describesByRemainingDays() {
        assertEquals("剩 3 天 · 某某绿化养护项目", NewTender.describe("某某绿化养护项目", inDays(3)));
        assertEquals("今天截止 · 某某绿化养护项目",
                NewTender.describe("某某绿化养护项目", LocalDateTime.now().plusHours(2)));
        assertEquals("已截止 · 某某绿化养护项目", NewTender.describe("某某绿化养护项目", inDays(-2)));
    }

    @Test
    @DisplayName("没有截止日期时只显示标题，不硬编一个天数")
    void handlesMissingDeadline() {
        assertEquals("某某绿化养护项目", NewTender.describe("某某绿化养护项目", null));
        assertEquals("", NewTender.leftLabel(null));
    }

    @Test
    @DisplayName("超长标题截断到 28 字 + 省略号，避免一条撑满通知")
    void shortensLongTitle() {
        String longTitle = "桐城师范高等专科学校绿化养护服务采购项目（二次）竞争性磋商公告补充说明";
        String line = NewTender.describe(longTitle, inDays(2));

        assertTrue(line.startsWith("剩 2 天 · "), line);
        String titlePart = line.substring("剩 2 天 · ".length());
        assertEquals(NewTender.MAX_TITLE + 1, titlePart.length(), titlePart);
        assertTrue(titlePart.endsWith("…"), titlePart);
    }

    @Test
    @DisplayName("实例方法与静态方法口径一致")
    void instanceMatchesStatic() {
        LocalDateTime deadline = inDays(5);
        NewTender item = new NewTender("某某项目", deadline);
        assertEquals(NewTender.describe("某某项目", deadline), item.describe());
        assertEquals(5, item.daysLeft());
    }

    @Test
    @DisplayName("没有截止日期时 daysLeft 是极小值，不会被当成\"快到期\"")
    void daysLeftWithoutDeadline() {
        assertEquals(Long.MIN_VALUE, new NewTender("某某项目", null).daysLeft());
    }

    // ---------------------------------------------------------------- mostUrgent

    @Test
    @DisplayName("已截止的排在未截止之后 —— 不能窜到通知第一行")
    void expiredGoesLast() {
        // 这正是实测抓到的 bug：13 条新公告里 5 条已截止，按天数升序排时负数最靠前，
        // 于是通知折叠后显示的第一行成了"已截止 · ..."
        List<NewTender> items = Arrays.asList(
                new NewTender("已截止三天的", inDays(-3)),
                new NewTender("还剩五天的", inDays(5)),
                new NewTender("已截止三十天的", inDays(-30)),
                new NewTender("还剩一天的", inDays(1)));

        List<NewTender> top = NewTender.mostUrgent(items, 5);
        assertEquals("还剩一天的", top.get(0).title);
        assertEquals("还剩五天的", top.get(1).title);
        // 已截止的排在后面，且"刚截止的"优先于"截止很久的"
        assertEquals("已截止三天的", top.get(2).title);
        assertEquals("已截止三十天的", top.get(3).title);
    }

    @Test
    @DisplayName("没有截止日期的垫底，不会顶掉真正紧急的")
    void unknownDeadlineGoesLast() {
        List<NewTender> items = Arrays.asList(
                new NewTender("日期未知的", null),
                new NewTender("还剩两天的", inDays(2)));

        List<NewTender> top = NewTender.mostUrgent(items, 5);
        assertEquals("还剩两天的", top.get(0).title);
        assertEquals("日期未知的", top.get(1).title);
    }

    @Test
    @DisplayName("只取前 limit 条，且不改动入参集合")
    void respectsLimitAndKeepsInputIntact() {
        List<NewTender> items = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            items.add(new NewTender("项目" + i, inDays(i)));
        }
        List<NewTender> top = NewTender.mostUrgent(items, 5);

        assertEquals(5, top.size());
        assertEquals("项目1", top.get(0).title);
        // 入参顺序不能被就地打乱（调用方还要用）
        assertEquals("项目1", items.get(0).title);
        assertEquals("项目8", items.get(7).title);
    }

    @Test
    @DisplayName("空输入 / null 元素都不炸；limit<=0 表示不限制")
    void handlesEmptyAndNulls() {
        assertTrue(NewTender.mostUrgent(null, 5).isEmpty());
        assertTrue(NewTender.mostUrgent(Collections.emptyList(), 5).isEmpty());
        assertEquals(1, NewTender.mostUrgent(
                Arrays.asList(null, new NewTender("甲的", inDays(1))), 5).size());
        assertEquals(2, NewTender.mostUrgent(
                Arrays.asList(new NewTender("甲", inDays(1)), new NewTender("乙", inDays(2))), 0).size());
    }

    @Test
    @DisplayName("expired()：负数天算已截止，无日期算未截止")
    void expiredFlag() {
        assertFalse(new NewTender("甲", inDays(1)).expired());
        assertTrue(new NewTender("乙", inDays(-1)).expired());
        // 没有截止日期不能当成"已截止"，否则会被埋到最后
        assertFalse(new NewTender("丙", null).expired());
    }

    @Test
    @DisplayName("刚过去几小时也算已截止 —— 不能用\"天数\"判断（向零截断会得出 0 天）")
    void expiredWithinSameDay() {
        // ChronoUnit.DAYS.between 是向零截断的：23 小时前算出 0 天，
        // 用 days < 0 判断会把"已经错过"的项目说成"今天截止、还能投"。
        LocalDateTime twentyThreeHoursAgo = LocalDateTime.now().minusHours(23);
        NewTender item = new NewTender("刚截止的", twentyThreeHoursAgo);
        assertTrue(item.expired(), "23 小时前截止的必须算已截止");
        assertEquals(0, item.daysLeft(), "天数确实是 0（所以才不能靠它判断）");
        assertEquals("已截止 · 刚截止的", item.describe());
    }
}
