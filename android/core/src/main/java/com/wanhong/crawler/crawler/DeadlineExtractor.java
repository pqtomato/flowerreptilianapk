package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.config.CrawlerSettings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公告正文里解析「报名 / 投标截止时间」「开标时间」。
 *
 * <p>绝大多数站点不提供截止日期字段，只能从正文里抠。抠不到就是<b>未知</b>，
 * 不再按「发布日期 + N 天」估算 —— 估出来的日期和真实报名截止毫无关系，
 * 反而会让用户以为「还剩 45 天」，而公告其实早就截止了（实测踩过）。
 *
 * <p>解析优先级：投标/响应截止 &gt; 开标时间 &gt; 获取文件截止。
 * 同一次匹配取"窗口内最后一个日期"，以正确处理「报名时间：9月10日至9月17日」这种区间写法。
 */
public class DeadlineExtractor {

    /** 标记词 → 其后窗口内找日期 */
    private static final List<Pattern> MARKERS = Arrays.asList(
            Pattern.compile("(?:投标|响应|报价|竞标|申请|递交|提交|上传|接收)"
                    + "[^。；;\\n]{0,14}?(?:截止|结束)(?:时间|日期|时限)?"),
            // 「报名方式：将…盖公章在<日期>前发送…」——日期在标记之后，
            // 且这往往才是真正"还能不能报名"的截止点（交控集采平台全是这种写法）
            Pattern.compile("(?:报名|参选)[^。；;\\n]{0,8}?(?:截止|结束|方式)"),
            Pattern.compile("(?:开标|磋商|谈判|评审|竞价|比选)(?:时间|日期|起始时间)"),
            Pattern.compile("(?:获取|领取|下载|购买)[^。；;\\n]{0,14}?(?:截止|结束)(?:时间|日期)?"),
            Pattern.compile("(?:报名|获取|领取|下载|公示)[^。；;\\n]{0,12}?(?:时间|期限|日期)"
                    + "[^。；;\\n]{0,20}?至"));

    /** 窗口边界：遇到这些符号就不再往右看，避免读到下一句话里的日期 */
    private static final String[] BOUNDARIES = {"。", "；", ";", "\n", "，"};

    private static final int WINDOW = 46;

    /**
     * 正文里的日期时间。
     *
     * <p>时与分之间的分隔符要把「点」也收进来 —— 政府公告里
     * {@code 2026年09月24日15点00分} 是常见写法（实测原文）。不收的话时分被整段丢掉，
     * 只剩 00:00，截止当天上午就会被判成"已截止"，而实际还能投到下午三点。
     */
    private static final Pattern DATE_IN_TEXT =
            Pattern.compile("\\d{4}\\s*[-/年.]\\s*\\d{1,2}\\s*[-/月.]\\s*\\d{1,2}"
                    // 分钟可省：「15点」和「15点00分」都要认。
                    // 注意必须是 (?:\d{1,2})? 而不是 \d{1,2}? —— 后者是"懒惰匹配"，
                    // 会优先只吃 1 位数字，把 09:30 截成 09:3 再解析成 09:03（踩过）
                    + "(?:[日号]?\\s*[ T]?\\s*\\d{1,2}\\s*[:：时点]\\s*(?:\\d{1,2})?"
                    + "(?:\\s*[:：分]\\s*\\d{1,2})?)?");

    private final CrawlerSettings settings;

    public DeadlineExtractor(CrawlerSettings settings) {
        this.settings = settings;
    }

    /** 解析结果。 */
    public static class Deadline {
        private final LocalDateTime date;
        private final String source;

        public Deadline(LocalDateTime date, String source) {
            this.date = date;
            this.source = source;
        }

        public static Deadline none() {
            return new Deadline(null, "");
        }

        public LocalDateTime date() {
            return date;
        }

        public String source() {
            return source;
        }
    }

    /**
     * 解析截止时间。正文里没有明确日期时返回 {@link Deadline#none()}，
     * <b>不做任何估算</b>。
     */
    public Deadline resolve(String content, LocalDateTime publishDate) {
        int maxDays = settings.deadline.maxDays;

        if (settings.deadline.parseFromContent && content != null && !content.trim().isEmpty()) {
            for (Pattern marker : MARKERS) {
                LocalDateTime parsed = parseAfter(marker, content, publishDate, maxDays);
                if (parsed != null) {
                    return new Deadline(parsed, "正文解析");
                }
            }
        }
        return Deadline.none();
    }

    private LocalDateTime parseAfter(Pattern marker, String content, LocalDateTime publishDate, int maxDays) {
        Matcher matcher = marker.matcher(content);
        while (matcher.find()) {
            int from = matcher.end();
            int to = Math.min(content.length(), from + WINDOW);
            String window = limitWindow(content.substring(from, to));

            List<LocalDateTime> dates = allDates(window);
            for (int i = dates.size() - 1; i >= 0; i--) {
                LocalDateTime candidate = dates.get(i);
                if (isSane(candidate, publishDate, maxDays)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * 把取值窗口限制在当前分句内。
     *
     * <p>必须做这个限制：像「投标截止时间：2026-09-20 09:30。开标时间：2026-09-21 09:30。」
     * 这样的句子，如果窗口跨到下一句，取"窗口内最后一个日期"就会选中开标时间。
     */
    private String limitWindow(String window) {
        int boundary = window.length();
        for (String terminator : BOUNDARIES) {
            int index = window.indexOf(terminator);
            if (index >= 0) {
                boundary = Math.min(boundary, index);
            }
        }
        for (Pattern marker : MARKERS) {
            Matcher matcher = marker.matcher(window);
            if (matcher.find() && matcher.start() > 0) {
                boundary = Math.min(boundary, matcher.start());
            }
        }
        return window.substring(0, Math.max(0, boundary));
    }

    private List<LocalDateTime> allDates(String text) {
        List<LocalDateTime> dates = new ArrayList<>();
        Matcher matcher = DATE_IN_TEXT.matcher(text);
        while (matcher.find()) {
            LocalDateTime parsed = DateUtils.parse(matcher.group());
            if (parsed != null) {
                dates.add(parsed);
            }
        }
        return dates;
    }

    /** 合理性校验：不能早于发布日期，也不能离谱地远（超过 maxDays 视为误判）。 */
    private boolean isSane(LocalDateTime candidate, LocalDateTime publishDate, int maxDays) {
        if (publishDate == null) {
            return true;
        }
        if (candidate.isBefore(publishDate)) {
            return false;
        }
        return maxDays <= 0 || !candidate.isAfter(publishDate.plusDays(maxDays));
    }
}
