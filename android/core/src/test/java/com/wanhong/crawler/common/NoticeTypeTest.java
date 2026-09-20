package com.wanhong.crawler.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公告类型判定。
 *
 * <p>用例里的标题全部来自模拟器上真实抓到的数据 —— 这些就是当初混进
 * "未截止"列表、还顶着估算假截止日的那批公告。
 */
class NoticeTypeTest {

    @Test
    @DisplayName("真正的招标 / 采购公告判为可投")
    void keepsRealTenders() {
        assertEquals(NoticeType.BID, NoticeType.classify("政府采购",
                "2026年高新区绿化养护工程招标公告"));
        assertEquals(NoticeType.BID, NoticeType.classify("招标公告",
                "安庆市某单位绿化养护服务采购项目"));
        assertEquals(NoticeType.BID, NoticeType.classify("政府采购",
                "某项目竞争性磋商公告"));
    }

    @Test
    @DisplayName("结果类公告：成交 / 中标 / 中标通知书 / 结果公示")
    void detectsAwards() {
        // 注意这些标题里同时含「采购」，必须先判结果类
        assertEquals(NoticeType.AWARD, NoticeType.classify("成交公告",
                "眉山村杨屋组道路改造工程成交结果公告"));
        assertEquals(NoticeType.AWARD, NoticeType.classify("其他交易",
                "桐城市人民医院排污许可证自行监测及证后管理服务采购项目（四次）成交结果公告"));
        assertEquals(NoticeType.AWARD, NoticeType.classify("通知公告",
                "安庆市花木公司项目第三方采购代理项目成交公告"));
        assertEquals(NoticeType.AWARD, NoticeType.classify("政府采购",
                "桐城师范高等专科学校绿化养护服务采购项目[中标通知书]"));
        assertEquals(NoticeType.AWARD, NoticeType.classify("其他交易",
                "2026年怀宁县马庙镇集镇区绿化管护项目成交结果公示"));
    }

    @Test
    @DisplayName("站点给的类别字段本身就是强信号")
    void trustsCategoryField() {
        // 标题看不出类型，但类别写着「中标候选人公示」
        assertEquals(NoticeType.AWARD, NoticeType.classify("中标候选人公示", "某某项目"));
        assertEquals(NoticeType.CONTRACT, NoticeType.classify("采购合同公告", "某某项目"));
    }

    @Test
    @DisplayName("合同类与终止类")
    void detectsContractAndAborted() {
        assertEquals(NoticeType.CONTRACT, NoticeType.classify("采购合同公告",
                "某某绿化养护项目采购合同公告"));
        assertEquals(NoticeType.CONTRACT, NoticeType.classify("", "某某项目合同公示"));
        assertEquals(NoticeType.ABORTED, NoticeType.classify("", "某某项目流标公告"));
        assertEquals(NoticeType.ABORTED, NoticeType.classify("", "某某采购项目废标公告"));
        assertEquals(NoticeType.ABORTED, NoticeType.classify("", "某某项目招标失败公告"));
    }

    @Test
    @DisplayName("更正 / 变更 / 澄清公告仍然可投，不能被当成结果类藏掉")
    void keepsAmendments() {
        assertEquals(NoticeType.BID, NoticeType.classify("更正公告",
                "2026年高新区绿化养护工程招标公告（更正公告）"));
        assertEquals(NoticeType.BID, NoticeType.classify("", "某某项目采购文件澄清公告"));
        assertEquals(NoticeType.BID, NoticeType.classify("", "某某项目开标时间变更公告"));
    }

    @Test
    @DisplayName("认不出类型时一律按可投处理（宁可多显示，不可漏机会）")
    void defaultsToBiddable() {
        assertEquals(NoticeType.BID, NoticeType.classify(null, null));
        assertEquals(NoticeType.BID, NoticeType.classify("", ""));
        assertEquals(NoticeType.BID, NoticeType.classify("服务", "某某项目"));
        assertEquals(NoticeType.BID, NoticeType.classify("其他交易", "皖宜公司招标代理入库"));
    }

    @Test
    @DisplayName("isBiddable：只有明确的非可投类型才被排除，空值按可投")
    void biddableCheck() {
        assertTrue(NoticeType.isBiddable(NoticeType.BID));
        assertTrue(NoticeType.isBiddable(null));
        assertTrue(NoticeType.isBiddable(""));
        assertFalse(NoticeType.isBiddable(NoticeType.AWARD));
        assertFalse(NoticeType.isBiddable(NoticeType.CONTRACT));
        assertFalse(NoticeType.isBiddable(NoticeType.ABORTED));
    }
}
