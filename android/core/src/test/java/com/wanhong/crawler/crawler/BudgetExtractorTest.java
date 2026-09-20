package com.wanhong.crawler.crawler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 预算 / 项目编号提取。
 *
 * <p><b>用例里的正文片段全部来自实测抓到的真实公告</b>（2026-09-20 那批），
 * 不是编出来的格式 —— 这类解析最容易在"我以为的写法"上翻车。
 */
class BudgetExtractorTest {

    private final BudgetExtractor extractor = new BudgetExtractor();

    @Test
    @DisplayName("预算金额：万元 / 元 / 带小数 / 无冒号 / 带千分位")
    void parsesAmount() {
        assertEquals(3_060_000L, extractor.extract("预算金额：306万元").amountYuan());
        assertEquals(3_060_000L, extractor.extract("预算金额 3060000元").amountYuan());
        assertEquals(95_000L, extractor.extract("预算金额：9.5万元").amountYuan());
        assertEquals(574_099L, extractor.extract("预算金额：574099.03元").amountYuan());
        assertEquals(600_000L, extractor.extract("预算金额：60万元").amountYuan());
        assertEquals(1_234_500L, extractor.extract("预算金额：123.45万元").amountYuan());
        assertEquals(3_060_000L, extractor.extract("预算金额：3,060,000元").amountYuan());
    }

    @Test
    @DisplayName("「预算金额：/」这种空值不能当成 0")
    void ignoresEmptyAmount() {
        assertNull(extractor.extract("4、预算金额：/ 5、项目概况：“花木公司采购代理”项目").amountYuan());
        assertNull(extractor.extract("预算金额：面议").amountYuan());
        assertNull(extractor.extract("").amountYuan());
        assertNull(extractor.extract(null).amountYuan());
    }

    @Test
    @DisplayName("最高限价只留文本：可能是单价或比例，绝不能当总额")
    void keepsCapAsTextOnly() {
        assertEquals("9.5万元", extractor.extract("最高限价：9.5万元 采购需求").capText());
        // 单价 —— 当总额会得出 3.4 元这种荒谬结果
        assertEquals("3.4元/平方米/年", extractor.extract("最高限价：3.4元/平方米/年 采购需求").capText());
        // 比例
        assertEquals("审定金额的95%", extractor.extract("6.最高限价：审定金额的95%； 7.评审方法").capText());
        // 真实写法：冒号后是一段说明文字，照实收下
        assertEquals("招标代理服务费按标准执行",
                extractor.extract("6、最高限价： 招标代理服务费按标准执行").capText());
        // 冒号后换行、下一行是序号：不能把 "7" 当限价
        assertNull(extractor.extract("6、最高限价：\n7、评审方法：综合评分").capText());
        assertNull(extractor.extract("没有限价信息").capText());
    }

    @Test
    @DisplayName("项目编号：两个编号取第一个 / 无冒号 / 中文紧贴要截断")
    void parsesProjectCode() {
        assertEquals("H0FSCG26D03G0397",
                extractor.extract("项目编号：H0FSCG26D03G0397 FS34081620260014号 项目名称").projectCode());
        assertEquals("H3GC26A07G041",
                extractor.extract("项目编号 H3GC26A07G041 招标公告发布时间 2026年08月").projectCode());
        assertEquals("CG-AQ-W2026-559",
                extractor.extract("项目编号：CG-AQ-W2026-559 项目名称：安庆市殡葬管理所").projectCode());
        assertEquals("JT-DL-143", extractor.extract("项目编号 JT-DL-143号 项目名称").projectCode());
        assertEquals("H0JCCG26D03C0369",
                extractor.extract("一、项目编号:H0JCCG26D03C0369 JC340801202603").projectCode());
        // 中文紧贴：必须在中文字符处停下，不能把后面的汉字吞进来
        assertEquals("H1QT26Z100124",
                extractor.extract("投标人交易项目编号：H1QT26Z100124交易项目名称：桐城经开区").projectCode());
        // 长写法优先匹配，避免在「采购项目编号」里错位
        assertEquals("H0FSCG26D03C0369",
                extractor.extract("采购项目编号 H0FSCG26D03C0369 采购项目名称").projectCode());
        assertNull(extractor.extract("本公告没有编号信息").projectCode());
    }

    @Test
    @DisplayName("一条真实正文能同时抠出金额与编号")
    void extractsFromRealContent() {
        String content = "2026年高新区绿化养护工程招标公告 项目概况 一、项目基本情况 "
                + "项目编号：H0FSCG26D03G0397 FS34081620260014号 项目名称：2026年高新区绿化养护工程 "
                + "预算金额：306万元 最高限价：3.4元/平方米/年 采购需求：高新区绿化养护工程，详见招标文件。";

        BudgetExtractor.Budget budget = extractor.extract(content);
        assertEquals(3_060_000L, budget.amountYuan());
        assertEquals("H0FSCG26D03G0397", budget.projectCode());
        assertEquals("3.4元/平方米/年", budget.capText());
    }

    @Test
    @DisplayName("金额标签：整数万不留小数点，小额用元")
    void formatsLabel() {
        assertEquals("306 万", BudgetExtractor.label(3_060_000L));
        assertEquals("60 万", BudgetExtractor.label(600_000L));
        assertEquals("9.5 万", BudgetExtractor.label(95_000L));
        assertEquals("9500 元", BudgetExtractor.label(9_500L));
        assertEquals("", BudgetExtractor.label(null));
        assertEquals("", BudgetExtractor.label(0L));
    }
}
