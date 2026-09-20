package com.wanhong.crawler.filter;

import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.SearchTerm;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 关键词匹配：三套词表 + 用户自定义增删。
 *
 * <ul>
 *   <li><b>标题词表</b>：既是站内检索词，也判定标题命中；</li>
 *   <li><b>正文词表</b>：只判定正文命中（也用于 title;content 检索）；</li>
 *   <li><b>排除词表</b>：命中<b>标题</b>即丢弃，用来挡"车辆维修/厕所管护/资产拍卖"这类噪声。</li>
 * </ul>
 */
public class KeywordRules {

    public interface Logger {
        void info(String message);

        void warn(String message);

        Logger SILENT = new Logger() {
            public void info(String message) {
            }

            public void warn(String message) {
            }
        };
    }

    private final CrawlerSettings settings;
    private final KeywordOverrideSource overrideSource;
    private final Logger logger;

    private volatile List<String> titleKeywords = Collections.emptyList();
    private volatile List<String> contentKeywords = Collections.emptyList();
    private volatile List<String> excludeKeywords = Collections.emptyList();
    private volatile List<String> customTitleKeywords = Collections.emptyList();
    private volatile List<String> customContentKeywords = Collections.emptyList();
    private volatile int disabledCount = 0;

    public KeywordRules(CrawlerSettings settings, KeywordOverrideSource overrideSource, Logger logger) {
        this.settings = settings;
        this.overrideSource = overrideSource == null ? KeywordOverrideSource.EMPTY : overrideSource;
        this.logger = logger == null ? Logger.SILENT : logger;
    }

    /** 重新加载：文件里的内置词 + 数据库里用户维护的增删。 */
    public synchronized void reload() {
        CrawlerSettings.Filter filter = settings.filter;

        List<String> fileTitles = new ArrayList<>(loadKeywords(filter.titleKeywordsFile));
        fileTitles.addAll(filter.extraTitleKeywords);
        List<String> fileContents = new ArrayList<>(loadKeywords(filter.contentKeywordsFile));
        fileContents.addAll(filter.extraContentKeywords);
        List<String> fileExcludes = new ArrayList<>(loadKeywords(filter.excludeKeywordsFile));
        fileExcludes.addAll(filter.excludeKeywords);

        List<CrawlerKeyword> overrides = loadOverrides();
        Set<String> disabled = new LinkedHashSet<>();
        List<String> addedTitle = new ArrayList<>();
        List<String> addedContent = new ArrayList<>();
        List<String> addedExclude = new ArrayList<>();
        for (CrawlerKeyword keyword : overrides) {
            if (keyword.getKeyword() == null) {
                continue;
            }
            if (keyword.getAction() == CrawlerKeyword.Action.DISABLE) {
                disabled.add(keyword.getKeyword());
            } else if (keyword.getScope() == CrawlerKeyword.Scope.TITLE) {
                addedTitle.add(keyword.getKeyword());
            } else if (keyword.getScope() == CrawlerKeyword.Scope.CONTENT) {
                addedContent.add(keyword.getKeyword());
            } else if (keyword.getScope() == CrawlerKeyword.Scope.EXCLUDE) {
                addedExclude.add(keyword.getKeyword());
            }
        }

        // 停用的内置词剔除，再并入用户新增的词
        fileTitles.removeAll(disabled);
        fileContents.removeAll(disabled);
        fileExcludes.removeAll(disabled);
        fileTitles.addAll(addedTitle);
        fileContents.addAll(addedContent);
        fileExcludes.addAll(addedExclude);

        this.titleKeywords = normalizeKeywords(fileTitles);
        this.contentKeywords = normalizeKeywords(fileContents);
        this.excludeKeywords = normalizeKeywords(fileExcludes);
        this.customTitleKeywords = normalizeKeywords(addedTitle);
        this.customContentKeywords = normalizeKeywords(addedContent);
        this.disabledCount = disabled.size();

        logger.info("关键词加载完成：标题词 " + titleKeywords.size() + "（自定义 " + customTitleKeywords.size()
                + "），正文词 " + contentKeywords.size() + "（自定义 " + customContentKeywords.size()
                + "），排除词 " + excludeKeywords.size() + "，停用 " + disabledCount);
    }

    private List<CrawlerKeyword> loadOverrides() {
        try {
            List<CrawlerKeyword> list = overrideSource.loadOverrides();
            return list == null ? Collections.<CrawlerKeyword>emptyList() : list;
        } catch (Exception e) {
            logger.warn("读取自定义关键词失败（本次仅使用文件词表）：" + e.getMessage());
            return Collections.emptyList();
        }
    }

    public List<String> getTitleKeywords() {
        return titleKeywords;
    }

    public List<String> getContentKeywords() {
        return contentKeywords;
    }

    public List<String> getExcludeKeywords() {
        return excludeKeywords;
    }

    public List<String> getCustomTitleKeywords() {
        return customTitleKeywords;
    }

    public List<String> getCustomContentKeywords() {
        return customContentKeywords;
    }

    public int getDisabledCount() {
        return disabledCount;
    }

    /**
     * 站内检索用词：标题词用 {@code fields=title}；
     * 只在正文词表里出现的词用 {@code fields=title;content}。
     * 已在标题词表里的词不重复搜正文，避免请求量翻倍。
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

    /**
     * 标题是否命中排除词。
     *
     * <p>必须能单独调用：一条公告可能"标题命中排除词、却靠正文命中关键词"，
     * 走正文命中那条路径时标题参数是 null，排除词就会失效。
     */
    public boolean isExcluded(String title) {
        String normalized = normalize(title);
        if (normalized.isEmpty()) {
            return false;
        }
        for (String exclude : excludeKeywords) {
            if (normalized.contains(exclude)) {
                return true;
            }
        }
        return false;
    }

    /** 标题 + 正文联合判定。排除词只判定标题，正文顺带提及不误伤。 */
    public MatchResult match(String title, String content) {
        String normalizedTitle = normalize(title);
        String normalizedContent = normalize(content);

        for (String exclude : excludeKeywords) {
            if (!normalizedTitle.isEmpty() && normalizedTitle.contains(exclude)) {
                return MatchResult.NOT_MATCHED;
            }
        }

        Set<String> hits = new LinkedHashSet<>();
        boolean titleHit = false;
        boolean contentHit = false;

        if (settings.filter.matchTitle && !normalizedTitle.isEmpty()) {
            for (String keyword : titleKeywords) {
                if (normalizedTitle.contains(keyword)) {
                    hits.add(keyword);
                    titleHit = true;
                }
            }
        }
        if (settings.filter.matchContent && !normalizedContent.isEmpty()) {
            for (String keyword : contentKeywords) {
                if (normalizedContent.contains(keyword)) {
                    hits.add(keyword);
                    contentHit = true;
                }
            }
        }
        return new MatchResult(hits, titleHit, contentHit);
    }

    /** 一次匹配结果。 */
    public static class MatchResult {
        public static final MatchResult NOT_MATCHED =
                new MatchResult(new LinkedHashSet<String>(), false, false);

        private final Set<String> keywords;
        private final boolean titleHit;
        private final boolean contentHit;

        public MatchResult(Set<String> keywords, boolean titleHit, boolean contentHit) {
            this.keywords = keywords;
            this.titleHit = titleHit;
            this.contentHit = contentHit;
        }

        public Set<String> keywords() {
            return keywords;
        }

        public boolean isMatched() {
            return !keywords.isEmpty();
        }

        public boolean titleHit() {
            return titleHit;
        }

        public boolean contentHit() {
            return contentHit;
        }

        public String scope() {
            if (titleHit && contentHit) {
                return "TITLE,CONTENT";
            }
            if (titleHit) {
                return "TITLE";
            }
            return contentHit ? "CONTENT" : "";
        }

        public String joinedKeywords() {
            StringBuilder sb = new StringBuilder();
            for (String keyword : keywords) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(keyword);
            }
            return sb.toString();
        }
    }

    /**
     * 读取词表。支持三种位置：
     * <ul>
     *   <li>{@code keywords/xxx.txt} → 从 classpath（打进包里）读；</li>
     *   <li>{@code file:xxx} 或绝对路径 → 从文件系统读（方便在手机上替换词表）。</li>
     * </ul>
     */
    private List<String> loadKeywords(String location) {
        if (location == null || location.trim().isEmpty()) {
            return Collections.emptyList();
        }
        InputStream stream = null;
        try {
            String path = location.trim();
            if (path.startsWith("file:")) {
                stream = new FileInputStream(path.substring(5));
            } else if (path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
                stream = new FileInputStream(path);
            } else {
                stream = KeywordRules.class.getClassLoader().getResourceAsStream(path);
            }
            if (stream == null) {
                logger.warn("关键词文件不存在，已跳过：" + location);
                return Collections.emptyList();
            }
            List<String> keywords = new ArrayList<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) {
                    continue;
                }
                int commentIndex = value.indexOf(" #");
                if (commentIndex > 0) {
                    value = value.substring(0, commentIndex).trim();
                }
                if (!value.isEmpty()) {
                    keywords.add(value);
                }
            }
            return keywords;
        } catch (Exception e) {
            logger.warn("关键词文件读取失败：" + location + "（" + e.getMessage() + "）");
            return Collections.emptyList();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
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
        Collections.sort(list, new java.util.Comparator<String>() {
            public int compare(String a, String b) {
                return Integer.compare(b.length(), a.length());
            }
        });
        return Collections.unmodifiableList(list);
    }

    /** 小写化并去掉所有空白字符，规避「花 卉」这类排版干扰。 */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\u00a0', ' ').replaceAll("\\s+", "").toLowerCase();
    }
}
