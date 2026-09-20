package com.wanhong.crawler.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 政府站群五花八门的日期格式解析。
 */
public final class DateUtils {

    /**
     * 从任意文本里抽取日期时间。
     *
     * <p>注意两处容易踩的坑，都会导致「时分被静默丢弃」：
     * <ol>
     *   <li>「日/号」后缀前不能写 {@code \s*}，否则空格会被它吃掉，
     *       后面本来可选的时分部分就再也匹配不上，正则整体仍然"匹配成功"；</li>
     *   <li>时分分隔符要同时兼容半角和全角冒号。</li>
     * </ol>
     */
    private static final Pattern CN_DATE = Pattern.compile(
            "(\\d{4})\\s*[-/年.]\\s*(\\d{1,2})\\s*[-/月.]\\s*(\\d{1,2})"
                    + "[日号]?"
                    + "(?:\\s*[ T]?\\s*(\\d{1,2})\\s*[:：时]\\s*(\\d{1,2})"
                    + "(?:\\s*[:：分]\\s*(\\d{1,2}))?)?");

    private static final DateTimeFormatter[] DATE_TIME_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"),
    };

    private static final DateTimeFormatter COMPACT_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final DateTimeFormatter[] DATE_FORMATTERS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
    };

    private DateUtils() {
    }

    /**
     * 解析日期时间，失败返回 null。
     *
     * <p>注意必须按「带时间 → 紧凑时间 → 仅日期」的顺序尝试：如果先用仅有日期的
     * 格式去解析 {@code 2026-09-17 16:31}，{@code LocalDate.from} 会直接丢掉时分，
     * 变成当天 00:00（这个坑已经踩过一次）。
     */
    public static LocalDateTime parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(text, formatter);
            } catch (Exception ignored) {
                // 换下一种格式
            }
        }
        if (text.length() == 14) {
            try {
                return LocalDateTime.parse(text, COMPACT_DATE_TIME);
            } catch (Exception ignored) {
                // 继续
            }
        }
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(text, formatter).atStartOfDay();
            } catch (Exception ignored) {
                // 换下一种格式
            }
        }
        return parseFromText(text);
    }

    /**
     * 从任意文本中抽取第一个日期时间（用于"发布日期：2026-09-16 16:31"这类串）。
     */
    public static LocalDateTime parseFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = CN_DATE.matcher(text);
        while (matcher.find()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
                int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
                int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
                if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
                    continue;
                }
                return LocalDateTime.of(LocalDate.of(year, month, day),
                        LocalTime.of(hour, minute, second));
            } catch (Exception ignored) {
                // 继续找下一个
            }
        }
        return null;
    }
}
