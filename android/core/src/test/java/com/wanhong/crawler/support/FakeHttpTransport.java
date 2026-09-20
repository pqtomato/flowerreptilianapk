package com.wanhong.crawler.support;

import com.wanhong.crawler.http.FetchResponse;
import com.wanhong.crawler.http.HttpTransport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 假的 HTTP 传输层：按 URL 返回预置内容，用于离线跑通采集链路。
 */
public class FakeHttpTransport implements HttpTransport {

    private final Map<String, FetchResponse> routes = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private int defaultStatus = 404;

    public FakeHttpTransport route(String url, int status, String body) {
        routes.put(url, new FetchResponse(status,
                body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8),
                "text/html; charset=UTF-8", url));
        return this;
    }

    public FakeHttpTransport routeOk(String url, String body) {
        return route(url, 200, body);
    }

    public FakeHttpTransport defaultStatus(int status) {
        this.defaultStatus = status;
        return this;
    }

    public List<String> requested() {
        return requested;
    }

    public int requestCount() {
        return requested.size();
    }

    @Override
    public FetchResponse send(String method, String url, Map<String, String> headers, byte[] body) throws IOException {
        requested.add(method + " " + url);
        FetchResponse response = routes.get(url);
        if (response != null) {
            return response;
        }
        return new FetchResponse(defaultStatus, new byte[0], "text/html", url);
    }

    @Override
    public void clearCookies(String host) {
        // 测试里不需要
    }
}
