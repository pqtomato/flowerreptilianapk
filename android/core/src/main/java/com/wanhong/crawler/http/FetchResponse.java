package com.wanhong.crawler.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 一次 HTTP 请求的结果（字节已解压）。 */
public class FetchResponse {

    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*[\"']?([\\w\\-]+)", Pattern.CASE_INSENSITIVE);

    private final int status;
    private final byte[] body;
    private final String contentType;
    private final String finalUrl;

    public FetchResponse(int status, byte[] body, String contentType, String finalUrl) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        this.contentType = contentType;
        this.finalUrl = finalUrl;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    public String finalUrl() {
        return finalUrl;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public int size() {
        return body.length;
    }

    public String asString() {
        return new String(body, detectCharset());
    }

    public Charset detectCharset() {
        if (contentType != null) {
            Matcher matcher = CHARSET.matcher(contentType);
            if (matcher.find()) {
                try {
                    return Charset.forName(matcher.group(1));
                } catch (Exception ignored) {
                    // 未知字符集，回退 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
