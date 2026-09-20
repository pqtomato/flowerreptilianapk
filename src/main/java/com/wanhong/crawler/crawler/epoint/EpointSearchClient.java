package com.wanhong.crawler.crawler.epoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.common.DateUtils;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * epoint 站群站内检索接口客户端。
 *
 * <p>安庆市公共资源交易服务网与安庆市阳光采购交易服务平台同属 epoint 站群，
 * 均提供 {@code /inteligentsearch/rest/esinteligentsearch/getFullTextDataNew} 接口。
 *
 * <p>注意：{@code wd}（关键词）不能为空，否则 totalcount 恒为 0，因此本采集器是
 * 「按关键词驱动」的。另需注意阳光采购平台的 WAF 会拦截携带 {@code Origin} 头的
 * 请求，{@link HttpFetcher} 已保证不发送该头。
 */
@Component
public class EpointSearchClient {

    private static final Logger log = LoggerFactory.getLogger(EpointSearchClient.class);

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpFetcher httpFetcher;
    private final ObjectMapper objectMapper;

    public EpointSearchClient(HttpFetcher httpFetcher, ObjectMapper objectMapper) {
        this.httpFetcher = httpFetcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行一次站内检索。
     */
    public EpointPage search(EpointQuery query) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", "");
        payload.put("pn", query.pageIndex() * query.pageSize());
        payload.put("rn", query.pageSize());
        payload.put("sdt", "");
        payload.put("edt", "");
        payload.put("wd", query.keyword() == null ? "" : query.keyword());
        payload.put("inc_wd", "");
        payload.put("exc_wd", "");
        payload.put("fields", query.fields());
        payload.put("cnum", query.cnum() == null ? "" : query.cnum());
        payload.put("sort", "{\"webdate\":\"0\"}");
        payload.put("ssort", "title");
        payload.put("cl", query.contentLength());
        payload.put("terminal", "");
        payload.put("condition", List.of());
        payload.put("time", buildTimeFilter(query.lookbackDays()));
        payload.put("highlights", "");
        payload.put("statistics", null);
        payload.put("unionCondition", query.unionConditionNull() ? null : List.of());
        payload.put("accuracy", "");
        payload.put("noParticiple", "1");
        payload.put("searchRange", null);

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.error("检索参数序列化失败", e);
            return EpointPage.failed();
        }

        FetchResponse response = httpFetcher.postJson(query.endpoint(), json, query.referer());
        if (!response.isSuccess()) {
            log.warn("检索接口返回 HTTP {}：{}（关键词={}）", response.status(), query.endpoint(), query.keyword());
            return EpointPage.failed();
        }
        return parse(response, query);
    }

    private EpointPage parse(FetchResponse response, EpointQuery query) {
        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception e) {
            String preview = response.asString();
            if (preview.length() > 200) {
                preview = preview.substring(0, 200);
            }
            log.warn("检索接口返回非 JSON（关键词={}）：{}", query.keyword(), preview.replaceAll("\\s+", " "));
            return EpointPage.failed();
        }

        JsonNode result = root.path("result");
        if (result.isMissingNode() || result.isNull()) {
            JsonNode status = root.path("status");
            if (!status.isMissingNode()) {
                log.warn("检索接口返回异常状态：{}", status.path("text").asText(""));
            }
            return EpointPage.failed();
        }

        long totalCount = asLong(result.get("totalcount"), 0L);

        List<EpointPage.EpointRecord> records = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode array = result.get("records");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                String linkUrl = text(node, "linkurl");
                String infoId = text(node, "infoid");
                String dedupeKey = !infoId.isBlank() ? infoId : linkUrl;
                if (dedupeKey.isBlank() || !seen.add(dedupeKey)) {
                    continue;
                }
                records.add(new EpointPage.EpointRecord(
                        cleanTitle(text(node, "title")),
                        text(node, "categorynum"),
                        DateUtils.parse(text(node, "webdate")),
                        linkUrl,
                        infoId,
                        cleanContent(text(node, "content")),
                        text(node, "citycode")));
            }
        }
        return new EpointPage(totalCount, records, true);
    }

    /**
     * 构造时间范围过滤条件。
     *
     * <p>不限制时间时，关键词检索会返回大量早已过期的老公告
     * （实测 wyxmzx 关键词「绿化」会带出 2024 年的记录），既浪费详情页配额，
     * 又会让手机端无法判断"还能不能投"。
     */
    private static List<Map<String, Object>> buildTimeFilter(int lookbackDays) {
        if (lookbackDays <= 0) {
            return List.of();
        }
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("fieldName", "webdate");
        window.put("startTime", LocalDateTime.now().minusDays(lookbackDays).format(TIME_FORMAT));
        window.put("endTime", "2999-12-31 23:59:59");
        return List.of(window);
    }

    private static String text(JsonNode node, String field) {        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        return value.asText("");
    }

    private static long asLong(JsonNode node, long defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        try {
            return Long.parseLong(node.asText("0").trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** 检索结果里的标题可能带高亮 HTML 标签，这里清掉。 */
    private static String cleanTitle(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (raw.indexOf('<') < 0) {
            return raw.trim();
        }
        return Jsoup.parse(raw).text().trim();
    }

    /**
     * 接口返回的正文片段里带 {@code &nbsp;} 之类的 HTML 实体，统一还原并压缩空白。
     */
    private static String cleanContent(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String text = org.jsoup.parser.Parser.unescapeEntities(raw, false);
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("(\\s*\\n\\s*)+", "\n")
                .trim();
    }
}
