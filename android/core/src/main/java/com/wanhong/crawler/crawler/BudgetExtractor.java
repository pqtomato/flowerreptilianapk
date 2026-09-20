package com.wanhong.crawler.crawler;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从公告正文里抠「预算金额 / 项目编号 / 最高限价」。
 *
 * <p>为什么只把「预算金额」当钱：真实正文里 {@code 最高限价} 有三种写法 ——
 * {@code 9.5万元}（总额）、{@code 3.4元/平方米/年}（<b>单价</b>）、
 * {@code 审定金额的95%}（比例）。后两种当总额排序会得出荒谬结果，
 * 所以限价只留文本给人看，不参与排序。
 *
 * <p>写法样本全部来自实测抓到的正文（见 {@code BudgetExtractorTest}）：
 * <ul>
 *   <li>{@code 预算金额：306万元} / {@code 预算金额 3060000元}（有冒号/无冒号、万元/元）</li>
 *   <li>{@code 预算金额：574099.03元}（带小数）</li>
 *   <li>{@code 预算金额：/}（<b>空值</b>，不能当成 0）</li>
 *   <li>{@code 项目编号：H0FSCG26D03G0397 FS34081620260014号}（两个编号，取第一个）</li>
 *   <li>{@code 项目编号 H3GC26A07G041}（无冒号）</li>
 *   <li>{@code 投标人交易项目编号：H1QT26Z100124交易项目名称：…}（中文紧贴，必须截断）</li>
 * </ul>
 */
public class BudgetExtractor {

    /** 预算金额：只认这几个明确说法，不用泛化的"预算"以免误命中"预算单位"之类 */
    private static final Pattern AMOUNT = Pattern.compile(
            "(?:采购预算|项目预算|预算金额|预算总价)[：:\\s]*"
                    + "([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(万元|万|元)");

    /**
     * 最高限价：只取一小段文本（可能是单价或比例），不做数值解释。
     *
     * <p>两个细节都是踩出来的：
     * <ul>
     *   <li>分隔符<b>不允许换行</b>：真实公告里 {@code 6、最高限价：\n7、评审方法：…}
     *       很常见，一旦让 {@code \s} 吃掉换行，就会把下一行的序号 "7" 当限价；</li>
     *   <li>取值集合要<b>排除冒号</b>：否则分隔符匹配 0 个字符回溯时，
     *       冒号本身会被当成限价文本抓进来。</li>
     * </ul>
     */
    private static final Pattern CAP = Pattern.compile(
            "最高限价[：: \\t]*([^\\s，。；;、：:]{1,40})");

    /** 项目编号：值基本是 ASCII 字母数字加连字符，遇到中文自然停下 */
    private static final Pattern PROJECT_CODE = Pattern.compile(
            "(?:采购项目编号|交易项目编号|招标编号|采购编号|项目编号)[：: \\t]*"
                    + "([A-Za-z0-9][A-Za-z0-9\\-]{2,})");

    /** 解析结果；三项都可能为 null。 */
    public static class Budget {
        private final Long amountYuan;
        private final String capText;
        private final String projectCode;

        Budget(Long amountYuan, String capText, String projectCode) {
            this.amountYuan = amountYuan;
            this.capText = capText;
            this.projectCode = projectCode;
        }

        public static Budget none() {
            return new Budget(null, null, null);
        }

        public Long amountYuan() {
            return amountYuan;
        }

        public String capText() {
            return capText;
        }

        public String projectCode() {
            return projectCode;
        }
    }

    public Budget extract(String content) {
        if (content == null || content.trim().isEmpty()) {
            return Budget.none();
        }
        return new Budget(parseAmount(content), parseCap(content), parseProjectCode(content));
    }

    private Long parseAmount(String content) {
        Matcher matcher = AMOUNT.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        return toYuan(matcher.group(1), matcher.group(2));
    }

    private String parseCap(String content) {
        Matcher matcher = CAP.matcher(content);
        if (!matcher.find()) {
            return null;
        }
        String captured = matcher.group(1).trim();
        // 全是数字说明抓到的是下一行的序号之类，不是限价
        return captured.matches("[0-9.,]+") ? null : captured;
    }

    private String parseProjectCode(String content) {
        Matcher matcher = PROJECT_CODE.matcher(content);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 「306万元」→ 3060000；「574099.03元」→ 574099（四舍五入到元）。 */
    static Long toYuan(String number, String unit) {
        String digits = number.replace(",", "").trim();
        if (digits.isEmpty()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return null;
        }
        if ("万元".equals(unit) || "万".equals(unit)) {
            value *= 10000d;
        }
        if (value <= 0) {
            // 「预算金额：/」这类空值会走不到这里（正则要求数字），
            // 但 0 元也没有排序意义，一律当作"没有金额"
            return null;
        }
        return Math.round(value);
    }

    /** 给界面用的短标签：「306 万」/「9500 元」。没有金额返回空串。 */
    public static String label(Long yuan) {
        if (yuan == null || yuan <= 0) {
            return "";
        }
        if (yuan >= 10000L) {
            double wan = yuan / 10000d;
            if (wan == Math.floor(wan)) {
                return String.format(Locale.US, "%d 万", (long) wan);
            }
            return String.format(Locale.US, "%.1f 万", wan);
        }
        return yuan + " 元";
    }
}
