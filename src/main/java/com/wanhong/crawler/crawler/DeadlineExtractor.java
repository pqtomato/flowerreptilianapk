package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.config.CrawlerProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公告正文里解析「报名 / 投标截止时间」「开标时间」。
 *
 * <p>参考上一版 Python 系统的核心经验：<b>绝大多数站点不提供截止日期字段</b>，
 * 只有少数平台（如池州）的接口直接给 endTime，其余都得从正文里抠，
 * 抠不到就用「发布日期 + N 天」估算 —— 否则手机上根本判断不了
 * 「这条还能不能投」，而"还能不能投"正是监控系统最核心的信息。
 *
 * <p>解析顺序即优先级：投标/响应截止 &gt; 开标时间 &gt; 报名/获取文件截止。
 * 同一次匹配里取"窗口内最后一个日期"，以正确处理
 * 「报名时间：2026年9月10日至2026年9月17日」这种区间写法。
 */
@Component
public class DeadlineExtractor {

    /** 标记词 → 其后窗口内找日期 */
    private static final List<Pattern> MARKERS = List.of(
            // 1) 投标 / 响应 / 报价 / 递交 / 提交 截止时间
            Pattern.compile("(?:投标|响应|报价|竞标|申请|递交|提交|上传|接收)"
                    + "[^。；;\\n]{0,14}?(?:截止|结束)(?:时间|日期|时限)?"),
            // 2) 开标 / 磋商 / 谈判 / 评审 时间
            Pattern.compile("(?:开标|磋商|谈判|评审|竞价)(?:时间|日期|起始时间)"),
            // 3) 获取 / 领取 / 下载 文件截止时间
            Pattern.compile("(?:获取|领取|下载|购买)[^。；;\\n]{0,14}?(?:截止|结束)(?:时间|日期)?"),
            // 4) 报名 / 获取文件 的「至」区间结尾
            Pattern.compile("(?:报名|获取|领取|下载|公示)[^。；;\\n]{0,12}?(?:时间|期限|日期)"
                    + "[^。；;\\n]{0,20}?至")
    );

    private static final int WINDOW = 46;

    /** 窗口边界：遇到这些符号就不再往右看，避免读到下一句话里的日期 */
    private static final String[] BOUNDARIES = {"。", "；", ";", "\n", "，"};

    private final CrawlerProperties properties;

    public DeadlineExtractor(CrawlerProperties properties) {
        this.properties = properties;
    }

    /**
     * 解析结果。
     *
     * @param date      截止日期（估算时也有值）
     * @param estimated true = 未能从正文解析，按发布日期估算
     * @param source    来源标记，便于排查（如 "投标截止" / "估算"）
     */
    public record Deadline(LocalDateTime date, boolean estimated, String source) {

        public static Deadline none() {
            return new Deadline(null, false, "");
        }
    }

    /**
     * 解析截止日期；解析不到时按 {@code default-days} 估算。
     *
     * @param content     公告正文（可为 null）
     * @param publishDate 发布日期，用于兜底与合理性校验
     */
    public Deadline resolve(String content, LocalDateTime publishDate) {
        int fallbackDays = properties.getDeadline().getDefaultDays();
        int maxDays = properties.getDeadline().getMaxDays();

        if (properties.getDeadline().isParseFromContent() && content != null && !content.isBlank()) {
            for (Pattern marker : MARKERS) {
                LocalDateTime parsed = parseAfter(marker, content, publishDate, maxDays);
                if (parsed != null) {
                    return new Deadline(parsed, false, "正文解析");
                }
            }
        }

        if (publishDate != null && fallbackDays > 0) {
            return new Deadline(publishDate.plusDays(fallbackDays), true, "发布日期+" + fallbackDays + "天估算");
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
     * 这样的句子，如果窗口跨到下一句，就会把开标时间当成投标截止时间
     * （取"窗口内最后一个日期"是为了兼容日期区间，反而会选中后一个日期）。
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

    /** 抽出文本里出现的所有日期时间（含只写月日的情况会由 DateUtils 补全，这里只取完整日期） */
    private List<LocalDateTime> allDates(String text) {
        List<LocalDateTime> dates = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d{4}\\s*[-/年.]\\s*\\d{1,2}\\s*[-/月.]\\s*\\d{1,2}"
                + "(?:[日号]?\\s*[ T]?\\s*\\d{1,2}\\s*[:：时]\\s*\\d{1,2}(?:\\s*[:：分]\\s*\\d{1,2})?)?").matcher(text);
        while (matcher.find()) {
            LocalDateTime parsed = DateUtils.parse(matcher.group());
            if (parsed != null) {
                dates.add(parsed);
            }
        }
        return dates;
    }

    /**
     * 合理性校验：截止日期不能早于发布日期，也不能离谱地远（超过 maxDays 视为误判）。
     */
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
