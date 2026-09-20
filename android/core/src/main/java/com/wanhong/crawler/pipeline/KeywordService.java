package com.wanhong.crawler.pipeline;

import com.wanhong.crawler.filter.CrawlerKeyword;
import com.wanhong.crawler.filter.KeywordRules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 关键词维护：展示（带命中数）、增删、以及"用新词重扫已入库公告"。
 *
 * <p>重扫的意义：新增关键词后，历史公告里可能已经有命中的，但那些记录入库时还不认识这个词。
 * 重扫把它们标出来，于是 <b>不用重新爬一遍就能立刻看到结果</b>。
 */
public class KeywordService {

    private static final int MAX_KEYWORD_LENGTH = 32;
    private static final int RESCAN_BATCH = 200;

    private final KeywordStore store;
    private final TenderRepository repository;
    private final KeywordRules keywordRules;

    public KeywordService(KeywordStore store, TenderRepository repository, KeywordRules keywordRules) {
        this.store = store;
        this.repository = repository;
        this.keywordRules = keywordRules;
    }

    /** 词表总览：三个作用域的词 + 是否自定义 + 命中多少条公告。 */
    public Map<String, Object> overview() {
        List<CrawlerKeyword> overrides = store.list();
        Set<String> added = new LinkedHashSet<>();
        for (CrawlerKeyword keyword : overrides) {
            if (keyword.getAction() == CrawlerKeyword.Action.ADD) {
                added.add(keyword.getScope() + ":" + keyword.getKeyword());
            }
        }
        Map<String, Long> hitCounts = hitCounts();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", describe(keywordRules.getTitleKeywords(), CrawlerKeyword.Scope.TITLE, added, hitCounts));
        result.put("content", describe(keywordRules.getContentKeywords(), CrawlerKeyword.Scope.CONTENT, added, hitCounts));
        result.put("exclude", describe(keywordRules.getExcludeKeywords(), CrawlerKeyword.Scope.EXCLUDE, added, hitCounts));
        result.put("counts", counts());
        return result;
    }

    public Map<String, Object> counts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("title", keywordRules.getTitleKeywords().size());
        counts.put("content", keywordRules.getContentKeywords().size());
        counts.put("exclude", keywordRules.getExcludeKeywords().size());
        int custom = 0;
        for (CrawlerKeyword keyword : store.list()) {
            if (keyword.getAction() == CrawlerKeyword.Action.ADD) {
                custom++;
            }
        }
        counts.put("custom", custom);
        counts.put("disabled", keywordRules.getDisabledCount());
        return counts;
    }

    private List<Map<String, Object>> describe(List<String> keywords, CrawlerKeyword.Scope scope,
                                               Set<String> added, Map<String, Long> hitCounts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String keyword : keywords) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyword", keyword);
            boolean custom = added.contains(scope.name() + ":" + keyword);
            item.put("custom", custom);
            item.put("source", custom ? "自定义" : "内置");
            item.put("hits", hitCounts.containsKey(keyword) ? hitCounts.get(keyword) : 0L);
            list.add(item);
        }
        // 命中最多的排前面，方便看出哪些词最有用、哪些词该清理
        Collections.sort(list, new Comparator<Map<String, Object>>() {
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Long.compare((Long) b.get("hits"), (Long) a.get("hits"));
            }
        });
        return list;
    }

    /** 每个词在已入库公告里的命中条数（一次性取回内存聚合，比为每个词跑一次查询划算）。 */
    public Map<String, Long> hitCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String keyword : keywordRules.getTitleKeywords()) {
            counts.put(keyword, 0L);
        }
        for (String keyword : keywordRules.getContentKeywords()) {
            if (!counts.containsKey(keyword)) {
                counts.put(keyword, 0L);
            }
        }
        for (String matched : repository.allMatchedKeywords()) {
            if (matched == null || matched.trim().isEmpty()) {
                continue;
            }
            for (String keyword : matched.split(",")) {
                String value = keyword.trim();
                if (!value.isEmpty() && counts.containsKey(value)) {
                    counts.put(value, counts.get(value) + 1);
                }
            }
        }
        return counts;
    }

    /** 新增（或重新启用）一个关键词。 */
    public Map<String, Object> add(String rawKeyword, CrawlerKeyword.Scope scope, String note) {
        String display = rawKeyword == null ? "" : rawKeyword.trim();
        if (display.isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        if (display.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("关键词过长（最多 " + MAX_KEYWORD_LENGTH + " 个字）");
        }
        String normalized = KeywordRules.normalize(display);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("关键词不能只包含空白字符");
        }

        CrawlerKeyword existing = store.findByKeywordAndScope(normalized, scope);
        if (existing != null) {
            if (existing.getAction() == CrawlerKeyword.Action.ADD) {
                throw new IllegalArgumentException("关键词已存在：" + display);
            }
            // 之前被停用过的内置词 → 撤销停用即等于重新启用
            store.remove(existing.getId());
            keywordRules.reload();
            return message("ENABLED", display, scope, "已重新启用内置关键词：" + display);
        }

        if (effectiveKeywords(scope).contains(normalized)) {
            throw new IllegalArgumentException("该关键词已在词表中：" + display);
        }

        store.add(new CrawlerKeyword(normalized, display, scope, CrawlerKeyword.Action.ADD));
        keywordRules.reload();
        return message("ADDED", display, scope, "已添加关键词：" + display);
    }

    /** 删除自定义词 / 停用内置词（内置词不物理删除，记一条 DISABLE 覆盖）。 */
    public Map<String, Object> remove(String rawKeyword, CrawlerKeyword.Scope scope) {
        String display = rawKeyword == null ? "" : rawKeyword.trim();
        String normalized = KeywordRules.normalize(display);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }

        CrawlerKeyword existing = store.findByKeywordAndScope(normalized, scope);
        if (existing != null && existing.getAction() == CrawlerKeyword.Action.ADD) {
            store.remove(existing.getId());
            keywordRules.reload();
            return message("DELETED", display, scope, "已删除自定义关键词：" + display);
        }

        if (!effectiveKeywords(scope).contains(normalized)) {
            throw new IllegalArgumentException("词表里没有这个关键词：" + display);
        }
        if (existing == null) {
            store.add(new CrawlerKeyword(normalized, display, scope, CrawlerKeyword.Action.DISABLE));
        }
        keywordRules.reload();
        return message("DISABLED", display, scope, "已停用内置关键词：" + display);
    }

    private List<String> effectiveKeywords(CrawlerKeyword.Scope scope) {
        if (scope == CrawlerKeyword.Scope.TITLE) {
            return keywordRules.getTitleKeywords();
        }
        if (scope == CrawlerKeyword.Scope.CONTENT) {
            return keywordRules.getContentKeywords();
        }
        return keywordRules.getExcludeKeywords();
    }

    private Map<String, Object> message(String action, String keyword, CrawlerKeyword.Scope scope, String text) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("keyword", keyword);
        result.put("scope", scope.name());
        result.put("message", text);
        return result;
    }

    /** 用当前词表重扫已入库公告，回填命中关键词。 */
    public Map<String, Object> rescan() {
        long total = repository.count();
        int processed = 0;
        int changed = 0;
        int newlyMatched = 0;
        Map<String, Long> perKeyword = new LinkedHashMap<>();

        int pages = (int) Math.ceil((double) total / RESCAN_BATCH);
        for (int page = 0; page < pages; page++) {
            List<TenderRecord> batch = repository.query(new TenderQuery(), RESCAN_BATCH, page * RESCAN_BATCH);
            if (batch.isEmpty()) {
                break;
            }
            for (TenderRecord item : batch) {
                processed++;
                KeywordRules.MatchResult titleMatch = keywordRules.match(item.title, null);
                KeywordRules.MatchResult contentMatch = keywordRules.match(null, item.content);
                Set<String> hits = new LinkedHashSet<>(titleMatch.keywords());
                hits.addAll(contentMatch.keywords());
                if (hits.isEmpty()) {
                    continue;
                }
                String joined = join(hits);
                if (!joined.equals(item.matchedKeywords)) {
                    if (item.matchedKeywords == null || item.matchedKeywords.trim().isEmpty()) {
                        newlyMatched++;
                    }
                    item.matchedKeywords = joined.length() > 500 ? joined.substring(0, 500) : joined;
                    item.matchedScope = resolveScope(titleMatch.isMatched(), contentMatch.isMatched());
                    repository.update(item);
                    changed++;
                    for (String hit : hits) {
                        perKeyword.put(hit, perKeyword.containsKey(hit) ? perKeyword.get(hit) + 1 : 1L);
                    }
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", processed);
        result.put("changed", changed);
        result.put("newlyMatched", newlyMatched);
        result.put("perKeyword", perKeyword);
        return result;
    }

    private String join(Set<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private String resolveScope(boolean titleHit, boolean contentHit) {
        if (titleHit && contentHit) {
            return "TITLE,CONTENT";
        }
        if (titleHit) {
            return "TITLE";
        }
        return contentHit ? "CONTENT" : "TITLE";
    }

    /** 解析作用域参数。 */
    public static CrawlerKeyword.Scope parseScope(String scope) {
        if (scope == null || scope.trim().isEmpty()) {
            return CrawlerKeyword.Scope.TITLE;
        }
        try {
            return CrawlerKeyword.Scope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的作用域：" + scope + "（可选 TITLE / CONTENT / EXCLUDE）");
        }
    }

    /** 供 UI 展示的作用域说明。 */
    public static List<Map<String, String>> scopes() {
        List<Map<String, String>> list = new ArrayList<>();
        list.add(scope("TITLE", "标题词", "作为站内检索词（搜标题），并判定标题命中"));
        list.add(scope("CONTENT", "正文词", "以「标题+正文」检索，能捞到标题没写但正文提到的公告"));
        list.add(scope("EXCLUDE", "排除词", "标题命中即丢弃，用于过滤噪声"));
        return list;
    }

    private static Map<String, String> scope(String value, String label, String hint) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("value", value);
        map.put("label", label);
        map.put("hint", hint);
        return map;
    }
}
