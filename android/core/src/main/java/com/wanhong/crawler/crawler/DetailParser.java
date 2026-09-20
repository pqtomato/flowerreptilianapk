package com.wanhong.crawler.crawler;

import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.config.CrawlerSettings;
import com.wanhong.crawler.crawler.model.DetailResult;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 三站通用的详情页解析器。
 *
 * <p>三个站点都是政务 / epoint 站群，正文容器命名各不相同，因此采用
 * 「候选选择器列表 + 取文本最长者」的策略，并对老页面做「文本密度最大块」兜底。
 */
public class DetailParser {

    public interface Logger {
        void warn(String message);

        Logger SILENT = new Logger() {
            public void warn(String message) {
            }
        };
    }

    private static final List<String> TITLE_SELECTORS = java.util.Arrays.asList(
            "h1.ls-article-title",
            "div.sub-detail-title",
            "div.notice-tit",
            "h1.article-title",
            "div.article-title",
            "h1",
            "h2.title");

    private static final List<String> CONTENT_SELECTORS = java.util.Arrays.asList(
            "div.notice-content",
            "div.ls-article-info",
            "div.sub-detail-content",
            "div.ewb-article",
            "div.article-content",
            "div.bid-box",
            "div.public-style",
            "#content",
            "div.content",
            "article");

    private static final List<String> DATE_SELECTORS = java.util.Arrays.asList(
            "div.ls-article-menu",
            "div.sub-detail-info",
            "div.article-source",
            "div.notice-info",
            "div.info",
            "div.source");

    private static final List<String> ATTACHMENT_SELECTORS = java.util.Arrays.asList(
            "a.enclosure-name",
            "div.enclosure-bd a",
            "div.sub-file-title a",
            "div.sub-detail-file a");

    private static final Pattern FILE_LINK = Pattern.compile(
            "\\.(pdf|docx?|xlsx?|zip|rar|7z|txt)(\\?|$)", Pattern.CASE_INSENSITIVE);

    private static final int MIN_ACCEPTABLE_CONTENT = 200;
    private static final int MAX_ATTACHMENTS = 30;

    private final CrawlerSettings settings;
    private final Logger logger;

    public DetailParser(CrawlerSettings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger == null ? Logger.SILENT : logger;
    }

    /**
     * 解析详情页。
     *
     * @param body    原始响应字节（交给 Jsoup 按 meta 自动识别字符集）
     * @param baseUrl 详情页地址，用于补全相对链接
     */
    public DetailResult parse(byte[] body, String baseUrl) {
        if (body == null || body.length == 0) {
            return DetailResult.EMPTY;
        }
        Document doc;
        try {
            doc = Jsoup.parse(new ByteArrayInputStream(body), null, baseUrl);
        } catch (Exception e) {
            logger.warn("详情页解析失败: " + baseUrl + " (" + e.getMessage() + ")");
            return DetailResult.EMPTY;
        }
        doc.select("script, style, noscript").remove();

        String title = extractTitle(doc);
        LocalDateTime publishDate = extractDate(doc);
        Element contentElement = extractContentElement(doc);

        String contentText = null;
        String contentHtml = null;
        if (contentElement != null) {
            contentText = truncate(normalizeText(contentElement.text()));
            contentHtml = truncate(contentElement.html());
        }
        List<String> attachments = extractAttachments(doc, contentElement);
        return new DetailResult(title, publishDate, contentText, contentHtml, attachments);
    }

    private String extractTitle(Document doc) {
        for (String selector : TITLE_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element == null) {
                continue;
            }
            String candidate = element.hasAttr("title") && !element.attr("title").trim().isEmpty()
                    ? element.attr("title") : element.text();
            candidate = normalizeText(candidate);
            if (candidate != null && candidate.length() >= 4) {
                return candidate;
            }
        }
        return stripSiteSuffix(normalizeText(doc.title()));
    }

    /** 去掉「标题_站点名」「标题 - 站点名」这类后缀。 */
    private String stripSiteSuffix(String title) {
        if (title == null || title.trim().isEmpty()) {
            return title;
        }
        String[] separators = {"_", " - ", "—", "|"};
        for (String separator : separators) {
            int index = title.lastIndexOf(separator);
            if (index > 4 && title.length() - index < 30) {
                return title.substring(0, index).trim();
            }
        }
        return title;
    }

    private LocalDateTime extractDate(Document doc) {
        Element infodate = doc.selectFirst("input#infodate");
        if (infodate != null) {
            LocalDateTime value = DateUtils.parse(infodate.attr("value"));
            if (value != null) {
                return value;
            }
        }
        Element published = doc.selectFirst("input#publishdate");
        if (published != null) {
            LocalDateTime value = DateUtils.parse(published.attr("value"));
            if (value != null) {
                return value;
            }
        }
        for (String selector : DATE_SELECTORS) {
            Element element = doc.selectFirst(selector);
            if (element == null) {
                continue;
            }
            LocalDateTime value = DateUtils.parseFromText(element.text());
            if (value != null) {
                return value;
            }
        }
        Element body = doc.body();
        if (body != null) {
            String head = body.text();
            if (head.length() > 1500) {
                head = head.substring(0, 1500);
            }
            return DateUtils.parseFromText(head);
        }
        return null;
    }

    /** 正文容器：候选选择器中文本最长者；都太短时用「文本密度最大」的块兜底。 */
    private Element extractContentElement(Document doc) {
        Element best = null;
        int bestLength = 0;
        for (String selector : CONTENT_SELECTORS) {
            for (Element element : doc.select(selector)) {
                int length = element.text().length();
                if (length > bestLength) {
                    bestLength = length;
                    best = element;
                }
            }
            if (bestLength >= MIN_ACCEPTABLE_CONTENT) {
                return best;
            }
        }
        if (bestLength >= MIN_ACCEPTABLE_CONTENT) {
            return best;
        }

        Element densest = null;
        int densestLength = 0;
        for (Element element : doc.select("div, article, section, td")) {
            int ownLength = ownTextLength(element);
            if (ownLength > densestLength) {
                densestLength = ownLength;
                densest = element;
            }
        }
        if (densest != null && densestLength > bestLength) {
            return densest;
        }
        return best;
    }

    private int ownTextLength(Element element) {
        StringBuilder sb = new StringBuilder();
        for (Node node : element.childNodes()) {
            if (node instanceof TextNode) {
                sb.append(((TextNode) node).text());
            }
        }
        return sb.length();
    }

    private List<String> extractAttachments(Document doc, Element contentElement) {
        Set<String> result = new LinkedHashSet<>();
        for (String selector : ATTACHMENT_SELECTORS) {
            for (Element link : doc.select(selector)) {
                addAttachment(result, link);
            }
        }
        if (contentElement != null) {
            for (Element link : contentElement.select("a[href]")) {
                String href = link.attr("href");
                if (FILE_LINK.matcher(href).find() || FILE_LINK.matcher(link.text()).find()) {
                    addAttachment(result, link);
                }
            }
        }
        if (result.size() < MAX_ATTACHMENTS) {
            for (Element link : doc.select("a[href]")) {
                if (result.size() >= MAX_ATTACHMENTS) {
                    break;
                }
                if (FILE_LINK.matcher(link.attr("href")).find()) {
                    addAttachment(result, link);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private void addAttachment(Set<String> collector, Element link) {
        if (collector.size() >= MAX_ATTACHMENTS) {
            return;
        }
        String absolute = link.absUrl("href");
        if (absolute == null || absolute.trim().isEmpty()) {
            absolute = link.attr("href");
        }
        if (absolute == null || absolute.trim().isEmpty() || absolute.startsWith("javascript")) {
            return;
        }
        String name = normalizeText(link.text());
        if (name == null || name.trim().isEmpty()) {
            name = absolute.substring(absolute.lastIndexOf('/') + 1);
        }
        collector.add(name + " :: " + absolute);
    }

    private String normalizeText(String text) {
        if (text == null) {
            return null;
        }
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("(\\s*\\n\\s*)+", "\n")
                .trim();
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int max = settings.detail.maxContentLength;
        if (max > 0 && text.length() > max) {
            return text.substring(0, max);
        }
        return text;
    }
}
