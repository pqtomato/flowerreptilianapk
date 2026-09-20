package com.wanhong.crawler.service;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.entity.CrawlerKeyword;
import com.wanhong.crawler.repository.CrawlerKeywordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeywordMatcherTest {

    private CrawlerProperties properties;
    private CrawlerKeywordRepository keywordRepository;
    private KeywordMatcher matcher;

    @BeforeEach
    void setUp() {
        properties = new CrawlerProperties();
        keywordRepository = mock(CrawlerKeywordRepository.class);
        when(keywordRepository.findAllByOrderByScopeAscCreateTimeAsc()).thenReturn(List.of());
        matcher = new KeywordMatcher(properties, keywordRepository);
        matcher.load();
    }

    private void withOverrides(List<CrawlerKeyword> overrides) {
        when(keywordRepository.findAllByOrderByScopeAscCreateTimeAsc()).thenReturn(overrides);
        matcher.reload();
    }

    @Test
    @DisplayName("用户新增的标题词会进入标题词表与检索词")
    void customTitleKeywordIsApplied() {
        withOverrides(List.of(new CrawlerKeyword("绿雕", "绿雕",
                CrawlerKeyword.Scope.TITLE, CrawlerKeyword.Action.ADD, null)));

        assertTrue(matcher.getTitleKeywords().contains("绿雕"));
        assertTrue(matcher.getCustomTitleKeywords().contains("绿雕"));
        assertTrue(matcher.match("城市广场绿雕布置项目招标公告", null).isMatched());
        assertTrue(matcher.getSearchTerms().stream()
                .anyMatch(term -> term.keyword().equals("绿雕")
                        && SearchTerm.SCOPE_TITLE.equals(term.scope())));
    }

    @Test
    @DisplayName("用户新增的正文词用 title;content 检索，能捞到标题没写的公告")
    void customContentKeywordSearchesContent() {
        // 注意：必须选一个不在标题词表里的词，否则它只会被当作标题词搜一次
        withOverrides(List.of(new CrawlerKeyword("绿篱", "绿篱",
                CrawlerKeyword.Scope.CONTENT, CrawlerKeyword.Action.ADD, null)));

        SearchTerm term = matcher.getSearchTerms().stream()
                .filter(t -> t.keyword().equals("绿篱")).findFirst().orElseThrow();
        assertEquals("title;content", term.fields(), "正文词必须检索正文");
        assertEquals(SearchTerm.SCOPE_CONTENT, term.scope());

        // 标题里没有这个词，但正文里有 → 正文命中
        KeywordMatcher.MatchResult result = matcher.match(
                "某河道整治项目招标公告", "本次整治需种植水生植物与绿篱，并配套绿化。");
        assertTrue(result.isMatched());
        assertEquals("CONTENT", result.scope());
    }

    @Test
    @DisplayName("已在标题词表里的词不会再重复做一次正文检索（避免请求量翻倍）")
    void titleKeywordIsNotSearchedTwice() {
        List<SearchTerm> terms = matcher.getSearchTerms();
        long duplicates = terms.stream().map(SearchTerm::keyword).distinct().count();
        assertEquals(terms.size(), duplicates, "检索词不应重复");
        // 正文词表里与标题词表重叠的词，只应出现一次且作用域为标题
        assertTrue(terms.stream().noneMatch(t -> "苗木".equals(t.keyword())
                && SearchTerm.SCOPE_CONTENT.equals(t.scope())));
    }

    @Test
    @DisplayName("用户停用内置词后，该词不再参与匹配与检索")
    void disabledBuiltinKeywordIsRemoved() {
        assertTrue(matcher.getTitleKeywords().contains("苗木"));

        withOverrides(List.of(new CrawlerKeyword("苗木", "苗木",
                CrawlerKeyword.Scope.TITLE, CrawlerKeyword.Action.DISABLE, null)));

        assertFalse(matcher.getTitleKeywords().contains("苗木"));
        assertTrue(matcher.getSearchTerms().stream().noneMatch(t -> t.keyword().equals("苗木")));
    }

    @Test
    @DisplayName("用户新增的排除词立即生效")
    void customExcludeKeywordIsApplied() {
        withOverrides(List.of(new CrawlerKeyword("绿雕", "绿雕",
                CrawlerKeyword.Scope.EXCLUDE, CrawlerKeyword.Action.ADD, null)));

        assertTrue(matcher.getExcludeKeywords().contains("绿雕"));
        assertFalse(matcher.match("城市广场绿雕布置项目招标公告", null).isMatched());
    }

    @Test
    @DisplayName("词表能从 classpath 正常加载")
    void loadsKeywords() {
        assertFalse(matcher.getTitleKeywords().isEmpty());
        assertFalse(matcher.getContentKeywords().isEmpty());
        assertFalse(matcher.getExcludeKeywords().isEmpty());
        assertTrue(matcher.getTitleKeywords().contains("花卉"));
        assertTrue(matcher.getTitleKeywords().contains("草花"));
    }

    @Test
    @DisplayName("标题命中：草花采购")
    void matchesTitleKeyword() {
        KeywordMatcher.MatchResult result = matcher.match("安庆市城市绿化管理大队草花采购项目成交结果公告", null);

        assertTrue(result.isMatched());
        assertTrue(result.keywords().contains("草花"));
        assertTrue(result.titleHit());
        assertFalse(result.contentHit());
        assertEquals("TITLE", result.scope());
    }

    @Test
    @DisplayName("标题里带空格也能命中")
    void toleratesWhitespaceInTitle() {
        KeywordMatcher.MatchResult result = matcher.match("花 卉 采 购 项 目", null);
        assertTrue(result.isMatched());
    }

    @Test
    @DisplayName("无关项目不命中")
    void doesNotMatchUnrelatedTender() {
        assertFalse(matcher.match("迎江区建设路地块城市更新项目地下人防洞回填工程竞争性谈判公告", null).isMatched());
        assertFalse(matcher.match("安庆市立医院东院区西地块项目设计总承包招标公告", null).isMatched());
    }

    @Test
    @DisplayName("正文命中：标题无关但正文含高精度花卉词")
    void matchesContentOnly() {
        KeywordMatcher.MatchResult result = matcher.match(
                "某综合整治项目竞争性谈判公告",
                "本项目需采购一批苗木用于沿线补植，并配套绿化养护服务。");

        assertTrue(result.isMatched());
        assertTrue(result.contentHit());
        assertTrue(result.keywords().contains("苗木"));
        assertEquals("CONTENT", result.scope());
    }

    @Test
    @DisplayName("单独出现的宽泛词『绿化』不会通过正文判定")
    void broadWordOnlyCountsInTitle() {
        KeywordMatcher.MatchResult result = matcher.match(
                "某综合整治项目竞争性谈判公告",
                "本项目包含道路绿化及排水施工内容。");

        // 绿化 只在标题词表里，正文词表刻意不含它，避免误报
        assertFalse(result.isMatched());
    }

    @Test
    @DisplayName("排除词生效")
    void excludeKeywordWins() {
        properties.getFilter().setExcludeKeywords(List.of("废标"));
        matcher.reload();

        assertFalse(matcher.match("花卉采购项目废标公告", null).isMatched());
        assertTrue(matcher.match("花卉采购项目成交公告", null).isMatched());
    }

    @Test
    @DisplayName("实测噪声：资产拍卖 / 出租类公告被排除词挡掉")
    void excludesAssetAndRentalAnnouncements() {
        // 这三个都是真实采集到的误报：被路名"园林路"或"园林绿化"命中
        assertFalse(matcher.match("宿松县国有林场园林路店面、孚玉路办公用房及五里棚户区门口场地出租", null).isMatched(),
                "出租类公告不是招标");
        assertFalse(matcher.match("桐城市园林绿化管理处的部分设备及喷泉水泵拆除物等资产拍卖项目", null).isMatched(),
                "资产拍卖不是招标");
        assertFalse(matcher.match("2026年潜山市营造林项目区林木、毛竹资产拍卖交易公告", null).isMatched(),
                "林木资产拍卖不是花卉招标");
    }

    @Test
    @DisplayName("标题排除判定可独立调用（避免正文命中路径绕过排除词）")
    void isExcludedIsIndependent() {
        // 这是真实数据里的漏洞：标题含"拍卖"，但正文提到苗木 →
        // 走"正文命中"路径时标题参数为 null，排除词曾经失效
        String title = "2026年潜山市营造林项目区林木、毛竹资产拍卖交易公告";
        String content = "本项目需采购苗木用于补植。";

        assertTrue(matcher.isExcluded(title), "标题含排除词应被识别");
        assertTrue(matcher.match(title, content).isMatched() == false,
                "带标题的联合判定仍然应该排除");
        assertFalse(matcher.isExcluded("苗木采购项目招标公告"));
        assertFalse(matcher.isExcluded(null));
        assertFalse(matcher.isExcluded(""));
    }

    @Test
    @DisplayName("排除词只判定标题，正文顺带提及不误伤")
    void excludeOnlyAppliesToTitle() {
        properties.getFilter().setExcludeKeywords(List.of("保洁"));
        matcher.reload();

        // 标题是正常的花卉采购，正文里提到保洁范围，不应被排除
        KeywordMatcher.MatchResult result = matcher.match(
                "花卉采购项目招标公告",
                "本项目采购时令花卉，同时包含场地保洁服务。");
        assertTrue(result.isMatched(), "正文里的排除词不应误伤标题正常的公告");
    }

    @Test
    @DisplayName("额外关键词可通过配置追加")
    void supportsExtraKeywords() {
        properties.getFilter().setExtraTitleKeywords(List.of("绿雕"));
        matcher.reload();

        KeywordMatcher.MatchResult result = matcher.match("城市广场绿雕布置项目招标公告", null);
        assertTrue(result.isMatched());
        assertTrue(result.keywords().contains("绿雕"));
    }

    @Test
    @DisplayName("命中位置可能同时包含标题与正文")
    void reportsBothScopes() {
        KeywordMatcher.MatchResult result = matcher.match(
                "花卉采购项目公告",
                "采购内容为时令花卉若干。");
        assertEquals("TITLE,CONTENT", result.scope());
    }
}
