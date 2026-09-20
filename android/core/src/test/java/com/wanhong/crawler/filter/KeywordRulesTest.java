package com.wanhong.crawler.filter;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.SearchTerm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 关键词规则：三套词表 + 用户增删。 */
class KeywordRulesTest {

    private CrawlerSettings settings;
    private List<CrawlerKeyword> overrides;
    private KeywordRules rules;

    @BeforeEach
    void setUp() {
        settings = new CrawlerSettings();
        overrides = new ArrayList<>();
        rules = new KeywordRules(settings, new KeywordOverrideSource() {
            public List<CrawlerKeyword> loadOverrides() {
                return overrides;
            }
        }, null);
        rules.reload();
    }

    @Test
    @DisplayName("三套词表都能从 classpath 加载")
    void loadsAllWordLists() {
        assertFalse(rules.getTitleKeywords().isEmpty());
        assertFalse(rules.getContentKeywords().isEmpty());
        assertFalse(rules.getExcludeKeywords().isEmpty());
        assertTrue(rules.getTitleKeywords().contains("花卉"));
        assertTrue(rules.getExcludeKeywords().size() >= 50, "排除词表应有一定规模");
    }

    @Test
    @DisplayName("标题命中")
    void matchesTitle() {
        KeywordRules.MatchResult result = rules.match("安庆市城市绿化管理大队草花采购项目成交结果公告", null);
        assertTrue(result.isMatched());
        assertTrue(result.keywords().contains("草花"));
        assertEquals("TITLE", result.scope());
    }

    @Test
    @DisplayName("标题里带空格也能命中")
    void toleratesWhitespace() {
        assertTrue(rules.match("花 卉 采 购 项 目", null).isMatched());
    }

    @Test
    @DisplayName("无关项目不命中")
    void doesNotMatchUnrelated() {
        assertFalse(rules.match("迎江区建设路地块城市更新项目地下人防洞回填工程竞争性谈判公告", null).isMatched());
        assertFalse(rules.match("安庆市立医院东院区西地块项目设计总承包招标公告", null).isMatched());
    }

    @Test
    @DisplayName("正文命中：标题无关但正文含正文词")
    void matchesContentOnly() {
        KeywordRules.MatchResult result = rules.match("某综合整治项目竞争性谈判公告",
                "本项目需采购一批苗木用于沿线补植，并配套绿化养护服务。");
        assertTrue(result.isMatched());
        assertEquals("CONTENT", result.scope());
    }

    @Test
    @DisplayName("排除词只判定标题，正文顺带提及不误伤")
    void excludeOnlyAppliesToTitle() {
        assertTrue(rules.match("花卉采购项目招标公告", "本项目采购时令花卉，同时包含场地保洁服务。").isMatched(),
                "正文里的排除词不应误伤标题正常的公告");
    }

    @Test
    @DisplayName("实测噪声：资产拍卖 / 出租类公告被排除词挡掉")
    void excludesRealNoise() {
        assertFalse(rules.match("宿松县国有林场园林路店面、孚玉路办公用房及五里棚户区门口场地出租", null).isMatched());
        assertFalse(rules.match("桐城市园林绿化管理处的部分设备及喷泉水泵拆除物等资产拍卖项目", null).isMatched());
        assertFalse(rules.match("2026年潜山市营造林项目区林木、毛竹资产拍卖交易公告", null).isMatched());
    }

    @Test
    @DisplayName("isExcluded 可独立调用：避免正文命中路径绕过排除词")
    void isExcludedIsIndependent() {
        String title = "2026年潜山市营造林项目区林木、毛竹资产拍卖交易公告";
        assertTrue(rules.isExcluded(title));
        assertFalse(rules.match(title, "本项目需采购苗木用于补植。").isMatched(),
                "标题含排除词时，即使正文命中关键词也必须丢弃");
        assertFalse(rules.isExcluded("苗木采购项目招标公告"));
        assertFalse(rules.isExcluded(null));
    }

    @Test
    @DisplayName("检索词作用域：标题词搜 title，正文词搜 title;content，且不重复")
    void buildsScopedSearchTerms() {
        List<SearchTerm> terms = rules.getSearchTerms();
        assertFalse(terms.isEmpty());
        int duplicates = 0;
        List<String> seen = new ArrayList<>();
        for (SearchTerm term : terms) {
            if (seen.contains(term.keyword())) {
                duplicates++;
            }
            seen.add(term.keyword());
        }
        assertEquals(0, duplicates, "检索词不应重复");
        // 已在标题词表里的词不应再作为正文检索项出现
        for (SearchTerm term : terms) {
            if ("苗木".equals(term.keyword())) {
                assertEquals("title", term.fields());
            }
        }
    }

    @Test
    @DisplayName("新增自定义标题词后立即生效，并进入检索词")
    void customTitleKeywordTakesEffect() {
        overrides.add(new CrawlerKeyword("绿雕", "绿雕", CrawlerKeyword.Scope.TITLE, CrawlerKeyword.Action.ADD));
        rules.reload();

        assertTrue(rules.getTitleKeywords().contains("绿雕"));
        assertTrue(rules.getCustomTitleKeywords().contains("绿雕"));
        assertTrue(rules.match("城市广场绿雕布置项目招标公告", null).isMatched());
        boolean found = false;
        for (SearchTerm term : rules.getSearchTerms()) {
            if ("绿雕".equals(term.keyword()) && SearchTerm.SCOPE_TITLE.equals(term.scope())) {
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    @DisplayName("新增自定义正文词用 title;content 检索")
    void customContentKeywordUsesContentSearch() {
        overrides.add(new CrawlerKeyword("绿篱", "绿篱", CrawlerKeyword.Scope.CONTENT, CrawlerKeyword.Action.ADD));
        rules.reload();

        SearchTerm matched = null;
        for (SearchTerm term : rules.getSearchTerms()) {
            if ("绿篱".equals(term.keyword())) {
                matched = term;
            }
        }
        assertTrue(matched != null, "自定义正文词应出现在检索词里");
        assertEquals("title;content", matched.fields());
        assertEquals(SearchTerm.SCOPE_CONTENT, matched.scope());
    }

    @Test
    @DisplayName("停用内置词后不再参与匹配与检索")
    void disabledBuiltinIsRemoved() {
        assertTrue(rules.getTitleKeywords().contains("苗木"));
        overrides.add(new CrawlerKeyword("苗木", "苗木", CrawlerKeyword.Scope.TITLE, CrawlerKeyword.Action.DISABLE));
        rules.reload();

        assertFalse(rules.getTitleKeywords().contains("苗木"));
        assertEquals(1, rules.getDisabledCount());
    }

    @Test
    @DisplayName("新增自定义排除词立即生效")
    void customExcludeTakesEffect() {
        overrides.add(new CrawlerKeyword("绿雕", "绿雕", CrawlerKeyword.Scope.EXCLUDE, CrawlerKeyword.Action.ADD));
        rules.reload();

        assertTrue(rules.getExcludeKeywords().contains("绿雕"));
        assertFalse(rules.match("城市广场绿雕布置项目招标公告", null).isMatched());
    }

    @Test
    @DisplayName("额外词表可通过配置追加")
    void supportsExtraKeywordsFromSettings() {
        settings.filter.extraTitleKeywords = Arrays.asList("宿根花卉");
        rules.reload();
        assertTrue(rules.getTitleKeywords().contains("宿根花卉"));
    }

    @Test
    @DisplayName("normalize：去空白 + 小写")
    void normalizesKeywords() {
        assertEquals("花卉", KeywordRules.normalize(" 花 卉 "));
        assertEquals("abc", KeywordRules.normalize("A B C"));
        assertEquals("", KeywordRules.normalize(null));
    }
}
