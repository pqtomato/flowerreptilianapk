package com.wanhong.crawler.service;

import com.wanhong.crawler.entity.CrawlerKeyword;
import com.wanhong.crawler.entity.TenderInfo;
import com.wanhong.crawler.repository.CrawlerKeywordRepository;
import com.wanhong.crawler.repository.TenderInfoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 关键词维护：展示、增删、命中统计，以及"用新词重扫已入库公告"。
 *
 * <p>为什么需要"重扫"：新增一个关键词后，历史公告里可能已经有命中的，
 * 但那些记录入库时还不认识这个词。重扫会把它们标出来，
 * 于是**不用重新爬一遍就能立刻看到结果** —— 这正是把「采集」和「过滤」解耦的价值。
 */
@Service
public class KeywordService {

    private static final Logger log = LoggerFactory.getLogger(KeywordService.class);

    private static final int RESCAN_BATCH = 200;
    private static final int MAX_KEYWORD_LENGTH = 64;

    private final CrawlerKeywordRepository keywordRepository;
    private final TenderInfoRepository tenderRepository;
    private final KeywordMatcher keywordMatcher;

    public KeywordService(CrawlerKeywordRepository keywordRepository,
                          TenderInfoRepository tenderRepository,
                          KeywordMatcher keywordMatcher) {
        this.keywordRepository = keywordRepository;
        this.tenderRepository = tenderRepository;
        this.keywordMatcher = keywordMatcher;
    }

    /**
     * 词表总览：三个作用域各自的词 + 是否内置 + 命中多少条已入库公告。
     */
    public Map<String, Object> overview() {
        List<CrawlerKeyword> overrides = keywordRepository.findAllByOrderByScopeAscCreateTimeAsc();
        Set<String> added = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (CrawlerKeyword k : overrides) {
            if (k.getAction() == CrawlerKeyword.Action.ADD) {
                added.add(k.getScope() + ":" + k.getKeyword());
            } else {
                disabled.add(k.getScope() + ":" + k.getKeyword());
            }
        }
        Map<String, Long> hitCounts = hitCounts();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", describe(keywordMatcher.getTitleKeywords(), CrawlerKeyword.Scope.TITLE,
                added, disabled, hitCounts));
        result.put("content", describe(keywordMatcher.getContentKeywords(), CrawlerKeyword.Scope.CONTENT,
                added, disabled, hitCounts));
        result.put("exclude", describe(keywordMatcher.getExcludeKeywords(), CrawlerKeyword.Scope.EXCLUDE,
                added, disabled, hitCounts));
        result.put("searchTerms", keywordMatcher.getSearchTerms().stream()
                .map(term -> Map.of("keyword", term.keyword(), "fields", term.fields(), "scope", term.scope()))
                .toList());
        result.put("counts", Map.of(
                "title", keywordMatcher.getTitleKeywords().size(),
                "content", keywordMatcher.getContentKeywords().size(),
                "exclude", keywordMatcher.getExcludeKeywords().size(),
                "custom", overrides.stream().filter(k -> k.getAction() == CrawlerKeyword.Action.ADD).count()));
        return result;
    }

    private List<Map<String, Object>> describe(List<String> keywords,
                                               CrawlerKeyword.Scope scope,
                                               Set<String> added,
                                               Set<String> disabled,
                                               Map<String, Long> hitCounts) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (String keyword : keywords) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("keyword", keyword);
            boolean custom = added.contains(scope.name() + ":" + keyword);
            item.put("custom", custom);
            item.put("source", custom ? "自定义" : "内置");
            item.put("hits", hitCounts.getOrDefault(keyword, 0L));
            list.add(item);
        }
        // 命中最多的排前面，方便一眼看出哪些词最有用、哪些词该清理
        list.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("hits")).reversed());
        return list;
    }

    /**
     * 每个词在已入库公告里的命中条数。
     *
     * <p>一次性把 matched_keywords 取回内存聚合（几百到几万行都很轻），
     * 比为每个词跑一次 count 查询划算得多。
     */
    public Map<String, Long> hitCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String keyword : keywordMatcher.getTitleKeywords()) {
            counts.put(keyword, 0L);
        }
        for (String keyword : keywordMatcher.getContentKeywords()) {
            counts.putIfAbsent(keyword, 0L);
        }
        for (String[] row : tenderRepository.findAllMatchedKeywords()) {
            String matched = row[0];
            if (matched == null || matched.isBlank()) {
                continue;
            }
            for (String keyword : matched.split(",")) {
                String value = keyword.trim();
                if (!value.isEmpty() && counts.containsKey(value)) {
                    counts.merge(value, 1L, Long::sum);
                }
            }
        }
        return counts;
    }

    /**
     * 新增（或重新启用）一个关键词。
     *
     * @param rawKeyword 用户输入的词
     * @param scope      作用域
     */
    @Transactional
    public Map<String, Object> add(String rawKeyword, CrawlerKeyword.Scope scope, String note) {
        String display = rawKeyword == null ? "" : rawKeyword.trim();
        if (display.isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }
        if (display.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("关键词过长（最多 " + MAX_KEYWORD_LENGTH + " 个字符）");
        }
        String normalized = KeywordMatcher.normalize(display);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("关键词不能只包含空白字符");
        }

        List<String> effective = effectiveKeywords(scope);
        boolean alreadyInFile = effective.contains(normalized);

        Optional<CrawlerKeyword> existing = keywordRepository.findByKeywordAndScope(normalized, scope);
        if (existing.isPresent()) {
            CrawlerKeyword record = existing.get();
            if (record.getAction() == CrawlerKeyword.Action.ADD) {
                throw new IllegalArgumentException("关键词已存在：" + display);
            }
            // 之前被停用过的内置词 → 撤销停用即等于重新启用
            keywordRepository.delete(record);
            keywordMatcher.reload();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("action", "ENABLED");
            result.put("keyword", display);
            result.put("scope", scope.name());
            result.put("message", "已重新启用内置关键词：" + display);
            return result;
        }

        if (alreadyInFile) {
            throw new IllegalArgumentException("该关键词已在词表中：" + display);
        }

        keywordRepository.save(new CrawlerKeyword(normalized, display, scope, CrawlerKeyword.Action.ADD, note));
        keywordMatcher.reload();
        log.info("新增关键词：{}（{}）", display, scope);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "ADDED");
        result.put("keyword", display);
        result.put("scope", scope.name());
        result.put("message", "已添加关键词：" + display);
        return result;
    }

    /**
     * 移除一个关键词。
     *
     * <p>内置词不是物理删除，而是记一条 DISABLE 覆盖 —— 因为我们不该在运行时改 jar 里的词表文件。
     */
    @Transactional
    public Map<String, Object> remove(String rawKeyword, CrawlerKeyword.Scope scope) {
        String display = rawKeyword == null ? "" : rawKeyword.trim();
        String normalized = KeywordMatcher.normalize(display);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("关键词不能为空");
        }

        Optional<CrawlerKeyword> existing = keywordRepository.findByKeywordAndScope(normalized, scope);
        if (existing.isPresent()) {
            CrawlerKeyword record = existing.get();
            if (record.getAction() == CrawlerKeyword.Action.ADD) {
                keywordRepository.delete(record);
                keywordMatcher.reload();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("action", "DELETED");
                result.put("keyword", display);
                result.put("scope", scope.name());
                result.put("message", "已删除自定义关键词：" + display);
                return result;
            }
        }

        if (!effectiveKeywords(scope).contains(normalized)) {
            throw new IllegalArgumentException("词表里没有这个关键词：" + display);
        }
        if (existing.isEmpty()) {
            keywordRepository.save(new CrawlerKeyword(normalized, display, scope,
                    CrawlerKeyword.Action.DISABLE, null));
        }
        keywordMatcher.reload();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", "DISABLED");
        result.put("keyword", display);
        result.put("scope", scope.name());
        result.put("message", "已停用内置关键词：" + display + "（可在词表文件里彻底删除）");
        return result;
    }

    private List<String> effectiveKeywords(CrawlerKeyword.Scope scope) {
        return switch (scope) {
            case TITLE -> keywordMatcher.getTitleKeywords();
            case CONTENT -> keywordMatcher.getContentKeywords();
            case EXCLUDE -> keywordMatcher.getExcludeKeywords();
        };
    }

    /**
     * 用当前词表重扫已入库公告，回填命中关键词。
     *
     * <p>新增关键词后调用，即可在**不重新爬取**的前提下看到历史公告里的命中结果。
     *
     * @return 处理条数、发生变化条数、以及本次新词带来的新增命中
     */
    @Transactional
    public Map<String, Object> rescan() {
        long total = tenderRepository.count();
        int processed = 0;
        int changed = 0;
        int newlyMatched = 0;
        Map<String, Long> perKeyword = new LinkedHashMap<>();

        int pages = (int) Math.ceil((double) total / RESCAN_BATCH);
        for (int page = 0; page < pages; page++) {
            List<TenderInfo> batch = tenderRepository.findAll(
                    PageRequest.of(page, RESCAN_BATCH, Sort.by(Sort.Direction.ASC, "id"))).getContent();
            if (batch.isEmpty()) {
                break;
            }
            List<TenderInfo> dirty = new ArrayList<>();
            for (TenderInfo item : batch) {
                processed++;
                KeywordMatcher.MatchResult titleMatch = keywordMatcher.match(item.getTitle(), null);
                KeywordMatcher.MatchResult contentMatch = keywordMatcher.match(null, item.getContent());
                Set<String> hits = new LinkedHashSet<>(titleMatch.keywords());
                hits.addAll(contentMatch.keywords());
                if (hits.isEmpty()) {
                    continue;
                }
                String joined = String.join(",", hits);
                if (!joined.equals(item.getMatchedKeywords())) {
                    Set<String> before = item.getMatchedKeywords() == null ? Set.of()
                            : new LinkedHashSet<>(Arrays.asList(item.getMatchedKeywords().split(",")));
                    if (before.isEmpty()) {
                        newlyMatched++;
                    }
                    item.setMatchedKeywords(joined.length() > 500 ? joined.substring(0, 500) : joined);
                    item.setMatchedScope(resolveScope(titleMatch.isMatched(), contentMatch.isMatched()));
                    item.setUpdateTime(LocalDateTime.now());
                    dirty.add(item);
                    for (String hit : hits) {
                        perKeyword.merge(hit, 1L, Long::sum);
                    }
                }
            }
            if (!dirty.isEmpty()) {
                tenderRepository.saveAll(dirty);
                changed += dirty.size();
            }
        }

        log.info("关键词重扫完成：处理 {} 条，更新 {} 条，其中此前无命中 {} 条", processed, changed, newlyMatched);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processed", processed);
        result.put("changed", changed);
        result.put("newlyMatched", newlyMatched);
        result.put("perKeyword", perKeyword);
        return result;
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

    /** 供前端做作用域校验 */
    public static CrawlerKeyword.Scope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return CrawlerKeyword.Scope.TITLE;
        }
        try {
            return CrawlerKeyword.Scope.valueOf(scope.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未知的作用域：" + scope + "（可选 TITLE / CONTENT / EXCLUDE）");
        }
    }
}
