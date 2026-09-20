package com.wanhong.crawler.web;

import com.wanhong.crawler.entity.CrawlerKeyword;
import com.wanhong.crawler.service.KeywordMatcher;
import com.wanhong.crawler.service.KeywordService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关键词维护接口。
 *
 * <p>手机端「关键词」页用这套接口做查看、增删、以及"用新词重扫已入库公告"。
 */
@RestController
@RequestMapping("/api/keywords")
public class KeywordController {

    private final KeywordService keywordService;
    private final KeywordMatcher keywordMatcher;

    public KeywordController(KeywordService keywordService, KeywordMatcher keywordMatcher) {
        this.keywordService = keywordService;
        this.keywordMatcher = keywordMatcher;
    }

    /**
     * 词表总览：标题词 / 正文词 / 排除词，每个词带是否自定义、命中了多少条公告。
     */
    @GetMapping
    public Map<String, Object> overview() {
        return keywordService.overview();
    }

    /**
     * 新增关键词。
     *
     * @param keyword 关键词，如「水生植物」
     * @param scope   TITLE（标题词，会作为站内检索词）/
     *                CONTENT（正文词，会以 title;content 检索正文）/
     *                EXCLUDE（排除词）
     */
    @PostMapping
    public Map<String, Object> add(@RequestParam String keyword,
                                   @RequestParam(defaultValue = "TITLE") String scope,
                                   @RequestParam(required = false) String note) {
        return keywordService.add(keyword, KeywordService.parseScope(scope), note);
    }

    /**
     * 删除自定义关键词 / 停用内置关键词。
     */
    @DeleteMapping
    public Map<String, Object> remove(@RequestParam String keyword,
                                      @RequestParam(defaultValue = "TITLE") String scope) {
        return keywordService.remove(keyword, KeywordService.parseScope(scope));
    }

    /**
     * 用当前词表重扫已入库公告（新增关键词后调用，无需重新爬取即可看到历史命中）。
     */
    @PostMapping("/rescan")
    public Map<String, Object> rescan() {
        return keywordService.rescan();
    }

    /**
     * 重新从文件加载内置词表（同时会合并数据库里的用户增删）。
     */
    @PostMapping("/reload")
    public Map<String, Object> reload() {
        keywordMatcher.reload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("titleKeywordCount", keywordMatcher.getTitleKeywords().size());
        result.put("contentKeywordCount", keywordMatcher.getContentKeywords().size());
        result.put("excludeKeywordCount", keywordMatcher.getExcludeKeywords().size());
        result.put("searchTermCount", keywordMatcher.getSearchTerms().size());
        return result;
    }

    /** 当前会真正用于站内检索的词（含作用域），便于排查"为什么这个词没采到" */
    @GetMapping("/search-terms")
    public List<Map<String, String>> searchTerms() {
        return keywordMatcher.getSearchTerms().stream()
                .map(term -> Map.of("keyword", term.keyword(), "fields", term.fields(), "scope", term.scope()))
                .toList();
    }

    /** 作用域可选值，供前端渲染下拉 */
    @GetMapping("/scopes")
    public List<Map<String, String>> scopes() {
        return List.of(
                Map.of("value", CrawlerKeyword.Scope.TITLE.name(), "label", "标题词",
                        "hint", "作为站内检索词（搜标题），并判定标题命中"),
                Map.of("value", CrawlerKeyword.Scope.CONTENT.name(), "label", "正文词",
                        "hint", "以「标题+正文」检索，能捞到标题没写但正文提到的公告"),
                Map.of("value", CrawlerKeyword.Scope.EXCLUDE.name(), "label", "排除词",
                        "hint", "标题命中即丢弃，用于过滤噪声"));
    }
}
