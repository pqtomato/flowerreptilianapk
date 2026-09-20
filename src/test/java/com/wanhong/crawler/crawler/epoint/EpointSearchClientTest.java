package com.wanhong.crawler.crawler.epoint;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpFetcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁定 epoint 站内检索接口的请求体结构与响应解析。
 */
class EpointSearchClientTest {

    private static final String ENDPOINT = "https://example.com/inteligentsearch/rest/esinteligentsearch/getFullTextDataNew";

    private final ObjectMapper objectMapper = new ObjectMapper();

    private HttpFetcher fetcherReturning(String json, int status) {
        HttpFetcher fetcher = mock(HttpFetcher.class);
        when(fetcher.postJson(anyString(), anyString(), nullable(String.class)))
                .thenReturn(new FetchResponse(status, json.getBytes(StandardCharsets.UTF_8),
                        "application/json", ENDPOINT));
        return fetcher;
    }

    private EpointSearchClient clientFor(HttpFetcher fetcher) {
        return new EpointSearchClient(fetcher, objectMapper);
    }

    /** 查询构造快捷方法：query(关键词, cnum, 页码, 每页, 时间窗口) */
    private EpointQuery query(String keyword, String cnum, int pageIndex, int pageSize, int lookbackDays) {
        return new EpointQuery(ENDPOINT, null, keyword, "title", cnum, pageIndex, pageSize, 2000,
                lookbackDays, true);
    }

    private JsonNode capturePayload(HttpFetcher fetcher, EpointQuery query) throws Exception {
        clientFor(fetcher).search(query);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(fetcher).postJson(anyString(), captor.capture(), nullable(String.class));
        return objectMapper.readTree(captor.getValue());
    }

    @Test
    @DisplayName("请求体包含接口要求的全部字段，sort 必须是 JSON 字符串")
    void buildsExpectedPayload() throws Exception {
        HttpFetcher fetcher = fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200);

        JsonNode payload = capturePayload(fetcher, query("花卉", "080", 1, 30, 0));

        assertEquals("", payload.get("token").asText());
        assertEquals("花卉", payload.get("wd").asText());
        assertEquals("title", payload.get("fields").asText());
        assertEquals("080", payload.get("cnum").asText());
        assertEquals(30, payload.get("pn").asInt(), "pn 应为 pageIndex * pageSize");
        assertEquals(30, payload.get("rn").asInt());
        assertEquals(2000, payload.get("cl").asInt());
        assertTrue(payload.get("sort").isTextual(), "sort 必须是字符串形式的 JSON");
        assertEquals("{\"webdate\":\"0\"}", payload.get("sort").asText());
        assertTrue(payload.get("condition").isArray());
        assertTrue(payload.get("unionCondition").isNull(), "阳光采购平台要求 unionCondition 为 null");
        assertTrue(payload.has("statistics"));
        assertTrue(payload.has("noParticiple"));
    }

    @Test
    @DisplayName("unionConditionNull=false 时 unionCondition 是空数组（交易服务网要求）")
    void usesEmptyArrayWhenConfigured() throws Exception {
        HttpFetcher fetcher = fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200);
        EpointQuery request = new EpointQuery(ENDPOINT, "https://ref", "草坪", "title", "", 0, 30, 200, 0, false);

        JsonNode payload = capturePayload(fetcher, request);

        assertTrue(payload.get("unionCondition").isArray());
        assertEquals("", payload.get("cnum").asText());
        assertEquals(0, payload.get("pn").asInt());
    }

    @Test
    @DisplayName("时间窗口：lookbackDays>0 时下发 webdate 过滤条件，=0 时不带条件")
    void buildsTimeFilter() throws Exception {
        HttpFetcher fetcher = fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200);

        JsonNode withWindow = capturePayload(fetcher, query("绿化", "", 0, 30, 30));
        JsonNode time = withWindow.get("time");
        assertEquals(1, time.size());
        assertEquals("webdate", time.get(0).get("fieldName").asText());
        assertTrue(time.get(0).get("startTime").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals("2999-12-31 23:59:59", time.get(0).get("endTime").asText());

        HttpFetcher unlimited = fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200);
        JsonNode noWindow = capturePayload(unlimited, query("绿化", "", 0, 30, 0));
        assertEquals(0, noWindow.get("time").size());
    }

    @Test
    @DisplayName("解析记录：去重、清 HTML 标签、还原实体、解析日期、取 citycode")
    void parsesRecords() {
        String json = """
                {
                  "result": {
                    "totalcount": "42",
                    "records": [
                      {"title":"<span style='color:red;'>花卉</span>采购公告","categorynum":"003002001",
                       "webdate":"2026-09-08 10:00:00","linkurl":"id-1","infoid":"id-1",
                       "citycode":"340822","content":"采购内容&nbsp;花卉&amp;草花"},
                      {"title":"同一条记录的另一个索引分片","categorynum":"003002001",
                       "webdate":"2026-09-08 10:00:00","linkurl":"id-1","infoid":"id-1","content":"dup"}
                    ]
                  }
                }
                """;

        EpointPage page = clientFor(fetcherReturning(json, 200)).search(query("花卉", "", 0, 30, 30));

        assertTrue(page.responseOk());
        assertEquals(42L, page.totalCount(), "totalcount 是字符串时也要能解析");
        assertEquals(1, page.records().size(), "同一 infoid 应去重");
        EpointPage.EpointRecord record = page.records().get(0);
        assertEquals("花卉采购公告", record.title());
        assertEquals("003002001", record.categoryCode());
        assertEquals("340822", record.cityCode());
        assertEquals(LocalDateTime.of(2026, 9, 8, 10, 0), record.publishDate());
        assertEquals("id-1", record.infoId());
        assertFalse(record.content().contains("&nbsp;"));
        assertFalse(record.content().contains("&amp;"));
        assertTrue(record.content().contains("花卉&草花"));
    }

    @Test
    @DisplayName("成功但无数据时 responseOk=true（可与被拦截区分）")
    void distinguishesEmptyFromBlocked() {
        EpointPage empty = clientFor(fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200))
                .search(query("郁金香", "", 0, 30, 30));
        assertTrue(empty.isEmpty());
        assertTrue(empty.responseOk(), "真的没有数据 → responseOk 应为 true");
    }

    @Test
    @DisplayName("非 2xx 返回失败标记而非抛异常")
    void handlesErrorStatus() {
        EpointPage page = clientFor(fetcherReturning("<html>waf block</html>", 503))
                .search(query("花卉", "", 0, 30, 30));
        assertTrue(page.isEmpty());
        assertFalse(page.responseOk());
        assertEquals(0L, page.totalCount());
    }

    @Test
    @DisplayName("返回 HTML 而非 JSON 时标记为失败")
    void handlesNonJsonBody() {
        EpointPage page = clientFor(fetcherReturning("<html><body>405</body></html>", 200))
                .search(query("花卉", "", 0, 30, 30));
        assertTrue(page.isEmpty());
        assertFalse(page.responseOk());
    }

    @Test
    @DisplayName("接口返回 CSRF 拦截信息时标记为失败")
    void handlesCsrfBlock() {
        String json = "{\"controls\":[],\"status\":{\"code\":\"503\",\"text\":\"安全提示：疑似跨站请求伪造\"}}";
        EpointPage page = clientFor(fetcherReturning(json, 200)).search(query("花卉", "080", 0, 30, 30));
        assertTrue(page.isEmpty());
        assertFalse(page.responseOk());
    }

    @Test
    @DisplayName("关键词为空时仍然照常发起请求（由接口返回 0 条）")
    void emptyKeywordStillRequests() {
        EpointPage page = clientFor(fetcherReturning("{\"result\":{\"totalcount\":0,\"records\":[]}}", 200))
                .search(query("", "", 0, 30, 30));
        assertTrue(page.isEmpty());
        assertNotNull(page.records());
    }
}
