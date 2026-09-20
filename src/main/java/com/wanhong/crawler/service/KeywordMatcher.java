package com.wanhong.crawler.service;

import com.wanhong.crawler.config.CrawlerProperties;
import com.wanhong.crawler.crawler.model.SearchTerm;
import com.wanhong.crawler.entity.CrawlerKeyword;
import com.wanhong.crawler.repository.CrawlerKeywordRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 花卉 / 园林绿化关键词匹配器。
 *
 * <p>两套词表：
 * <ul>
 *   <li><b>标题词表</b>：既是站内检索的检索词，也用于标题命中判定；</li>
 *   <li><b>正文词表</b>：只放高精度花卉词，用于正文二次判定，
 *       避免「绿化 / 园林」这类词在无关项目正文里造成误报。</li>
 * </ul>
 */
@Service
public class KeywordMatcher {

    private static final Logger log = LoggerFactory.getLogger(KeywordMatcher.class);

    private final CrawlerProperties properties;
    private final CrawlerKeywordRepository keywordRepository;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    private volatile List<String> titleKeywords = List.of();
    private volatile List<String> contentKeywords = List.of();
    private volatile List<String> excludeKeywords = List.of();
    /** 用户新增的词（文件之外），标题词 / 正文词 */
    private volatile List<String> customTitleKeywords = List.of();
    private volatile List<String> customContentKeywords = List.of();

    public KeywordMatcher(CrawlerProperties properties, CrawlerKeywordRepository keywordRepository) {
        this.properties = properties;
        this.keywordRepository = keywordRepository;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    /**
     * 重新加载词表：文件里的内置词 + 数据库里用户维护的增删项。
     */
    public synchronized void reload() {
        CrawlerProperties.Filter filter = properties.getFilter();

        List<String> fileTitles = new ArrayList<>(loadKeywords(filter.getTitleKeywordsFile()));
        fileTitles.addAll(filter.getExtraTitleKeywords());
        List<String> fileContents = new ArrayList<>(loadKeywords(filter.getContentKeywordsFile()));
        fileContents.addAll(filter.getExtraContentKeywords());
        List<String> fileExcludes = new ArrayList<>(loadKeywords(filter.getExcludeKeywordsFile()));
        fileExcludes.addAll(filter.getExcludeKeywords());

        // 用户在手机端维护的增删
        List<CrawlerKeyword> overrides = loadOverrides();
        Set<String> disabled = overrides.stream()
                .filter(k -> k.getAction() == CrawlerKeyword.Action.DISABLE)
                .map(CrawlerKeyword::getKeyword).collect(Collectors.toSet());
        List<String> addedTitle = overrides.stream()
                .filter(k -> k.getAction() == CrawlerKeyword.Action.ADD
                        && k.getScope() == CrawlerKeyword.Scope.TITLE)
                .map(CrawlerKeyword::getKeyword).toList();
        List<String> addedContent = overrides.stream()
                .filter(k -> k.getAction() == CrawlerKeyword.Action.ADD
                        && k.getScope() == CrawlerKeyword.Scope.CONTENT)
                .map(CrawlerKeyword::getKeyword).toList();
        List<String> addedExclude = overrides.stream()
                .filter(k -> k.getAction() == CrawlerKeyword.Action.ADD
                        && k.getScope() == CrawlerKeyword.Scope.EXCLUDE)
                .map(CrawlerKeyword::getKeyword).toList();

        // 停用的内置词剔除，再并入用户新增的词
        fileTitles.removeIf(disabled::contains);
        fileContents.removeIf(disabled::contains);
        fileExcludes.removeIf(disabled::contains);
        fileTitles.addAll(addedTitle);
        fileContents.addAll(addedContent);
        fileExcludes.addAll(addedExclude);

        this.titleKeywords = normalizeKeywords(fileTitles);
        this.contentKeywords = normalizeKeywords(fileContents);
        this.excludeKeywords = normalizeKeywords(fileExcludes);
        this.customTitleKeywords = normalizeKeywords(addedTitle);
        this.customContentKeywords = normalizeKeywords(addedContent);

        log.info("关键词加载完成：标题词 {} 个（含自定义 {}），正文词 {} 个（含自定义 {}），"
                        + "排除词 {} 个，停用 {} 个",
                titleKeywords.size(), customTitleKeywords.size(),
                contentKeywords.size(), customContentKeywords.size(),
                excludeKeywords.size(), disabled.size());
    }

    private List<CrawlerKeyword> loadOverrides() {
        try {
            return keywordRepository.findAllByOrderByScopeAscCreateTimeAsc();
        } catch (Exception e) {
            log.warn("读取自定义关键词失败（本次仅使用文件词表）：{}", e.getMessage());
            return List.of();
        }
    }

    /** 用户自定义的标题词 */
    public List<String> getCustomTitleKeywords() {
        return customTitleKeywords;
    }

    /** 用户自定义的正文词 */
    public List<String> getCustomContentKeywords() {
        return customContentKeywords;
    }

    /**
     * 站内检索用词。
     *
     * <p>标题词用 {@code fields=title}；**只在正文里出现的词**（用户添加的正文词，
     * 或者不在标题词表里的正文词）用 {@code fields=title;content}，
     * 这样"标题没写、正文提到"的公告也能被采到。
     * 已经在标题词表里的词不需要再搜一遍正文，避免请求量翻倍。
     */
    public List<SearchTerm> getSearchTerms() {
        List<SearchTerm> terms = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String keyword : titleKeywords) {
            if (seen.add(keyword)) {
                terms.add(SearchTerm.title(keyword));
            }
        }
        Set<String> titleSet = new LinkedHashSet<>(titleKeywords);
        for (String keyword : contentKeywords) {
            if (!titleSet.contains(keyword) && seen.add(keyword)) {
                terms.add(SearchTerm.content(keyword));
            }
        }
        return terms;
    }

    /** 检索用词表（用于站内检索接口）。 */
    public List<String> getTitleKeywords() {
        return titleKeywords;
    }

    public List<String> getContentKeywords() {
        return contentKeywords;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords;
    }

    /**
     * 标题是否命中排除词。
     *
     * <p>单独暴露这个方法是因为：一条公告可能**标题命中排除词、却靠正文命中关键词**
     * （例如「XX资产拍卖交易公告」正文里提到苗木）。{@link #match} 只在标题非空时
     * 才检查排除词，走"正文命中"这条路径时标题参数是 null，排除词就失效了 ——
     * 所以调用方必须在两条路径之前，先用标题做一次排除判定。
     */
    public boolean isExcluded(String title) {
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isEmpty()) {
            return false;
        }
        for (String exclude : excludeKeywords) {
            if (normalizedTitle.contains(exclude)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标题 + 正文联合判定。
     *
     * <p>排除词<b>只判定标题</b>：正文里顺带提到"保洁""车辆"等内容不该误伤
     * 一条正常的花卉采购公告（参考上一版系统"关键词太宽泛必须配排除词"的经验）。
     *
     * @param title   标题
     * @param content 正文（可为 null）
     */
    public MatchResult match(String title, String content) {
        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(content);

        // 命中排除词直接判定为不相关
        for (String exclude : excludeKeywords) {
            if (normalizedTitle.contains(exclude)) {
                return MatchResult.NOT_MATCHED;
            }
        }

        Set<String> hits = new LinkedHashSet<>();
        boolean titleHit = false;
        boolean contentHit = false;

        if (properties.getFilter().isMatchTitle() && !normalizedTitle.isEmpty()) {
            for (String keyword : titleKeywords) {
                if (normalizedTitle.contains(keyword)) {
                    hits.add(keyword);
                    titleHit = true;
                }
            }
        }
        if (properties.getFilter().isMatchContent() && !normalizedContent.isEmpty()) {
            for (String keyword : contentKeywords) {
                if (normalizedContent.contains(keyword)) {
                    hits.add(keyword);
                    contentHit = true;
                }
            }
        }
        return new MatchResult(hits, titleHit, contentHit);
    }

    private List<String> loadKeywords(String location) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("关键词文件不存在，已跳过：{}", location);
            return List.of();
        }
        List<String> keywords = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                // 支持「关键词 # 备注」这种行内注释
                int commentIndex = value.indexOf(" #");
                if (commentIndex > 0) {
                    value = value.substring(0, commentIndex).trim();
                }
                if (!value.isEmpty()) {
                    keywords.add(value);
                }
            }
        } catch (Exception e) {
            log.error("关键词文件读取失败：{}", location, e);
        }
        return keywords;
    }

    /** 去重 + 小写 + 去空白，并按长度倒序（长词优先，命中信息更精确）。 */
    private List<String> normalizeKeywords(List<String> raw) {
        Set<String> unique = new LinkedHashSet<>();
        for (String keyword : raw) {
            String normalized = normalize(keyword);
            if (!normalized.isEmpty()) {
                unique.add(normalized);
            }
        }
        List<String> list = new ArrayList<>(unique);
        list.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return Collections.unmodifiableList(list);
    }

    /** 小写化并去掉所有空白字符，规避「花 卉」这类排版干扰。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ').replaceAll("\\s+", "").toLowerCase();
    }

    /**
     * 一次匹配结果。
     */
    public record MatchResult(Set<String> keywords, boolean titleHit, boolean contentHit) {

        public static final MatchResult NOT_MATCHED = new MatchResult(Set.of(), false, false);

        public boolean isMatched() {
            return !keywords.isEmpty();
        }

        /** 命中位置：TITLE / CONTENT / TITLE,CONTENT */
        public String scope() {
            if (titleHit && contentHit) {
                return "TITLE,CONTENT";
            }
            if (titleHit) {
                return "TITLE";
            }
            if (contentHit) {
                return "CONTENT";
            }
            return "";
        }

        public String joinedKeywords() {
            return String.join(",", keywords);
        }
    }
}
