package com.wanhong.crawler.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次 HTTP 请求的原始响应。
 */
public record FetchResponse(int status, byte[] body, String contentType, String finalUrl) {

    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?([\\w\\-]+)", Pattern.CASE_INSENSITIVE);

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public String asString() {
        return new String(body, detectCharset());
    }

    public Charset detectCharset() {
        if (contentType != null) {
            Matcher m = CHARSET.matcher(contentType);
            if (m.find()) {
                try {
                    return Charset.forName(m.group(1));
                } catch (Exception ignored) {
                    // 未知字符集，回退 UTF-8
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    public int size() {
        return body == null ? 0 : body.length;
    }
}
